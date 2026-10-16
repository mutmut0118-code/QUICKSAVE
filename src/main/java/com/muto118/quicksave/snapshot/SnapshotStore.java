package com.muto118.quicksave.snapshot;

import com.muto118.quicksave.QuicksaveMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * M1 snapshot store: a slot is a plain full copy of the world folder.
 *
 * <pre>
 * .minecraft/quicksaves/&lt;world folder&gt;/
 *   saves/&lt;slot&gt;/world/        full copy of saves/&lt;world&gt;/ (minus session.lock)
 *   saves/&lt;slot&gt;/complete      written last; a slot without it is ignored
 *   _pre_load/world/            copy of the world taken right before a restore
 *   _pre_load/restore.inprogress  present while a restore is running
 * </pre>
 *
 * M3 replaces the slot layout with content-addressed objects + manifests.
 * All copies are real byte copies, never hardlinks: Minecraft rewrites region
 * files in place, so a hardlink would let the live world corrupt the snapshot.
 */
public final class SnapshotStore {
	/** The single slot used by M1's /qs save. */
	public static final String M1_SLOT = "quick";

	public static final String SESSION_LOCK = "session.lock";
	private static final String COMPLETE_MARKER = "complete";
	private static final String PRE_LOAD = "_pre_load";
	private static final String RESTORE_MARKER = "restore.inprogress";

	private SnapshotStore() {
	}

	public static Path rootDir() {
		return FabricLoader.getInstance().getGameDir().resolve("quicksaves");
	}

	public static Path worldStore(String worldFolderName) {
		return rootDir().resolve(worldFolderName);
	}

	public static Path slotDir(String worldFolderName, String slot) {
		return worldStore(worldFolderName).resolve("saves").resolve(slot);
	}

	public static Path preLoadDir(String worldFolderName) {
		return worldStore(worldFolderName).resolve(PRE_LOAD);
	}

	public static Path restoreMarker(String worldFolderName) {
		return preLoadDir(worldFolderName).resolve(RESTORE_MARKER);
	}

	public static boolean hasSave(String worldFolderName) {
		return Files.isRegularFile(slotDir(worldFolderName, M1_SLOT).resolve(COMPLETE_MARKER));
	}

	/**
	 * Copies the (already flushed) world folder into a slot. Writes into a
	 * temporary directory first and swaps it in, so a crash mid-save never
	 * leaves a half-written slot that looks complete.
	 */
	public static void save(Path worldDir, String worldFolderName, String slot) throws IOException {
		Path slotDir = slotDir(worldFolderName, slot);
		Path tmp = slotDir.resolveSibling(slot + ".tmp");
		deleteRecursively(tmp);
		Files.createDirectories(tmp);
		copyWorld(worldDir, tmp.resolve("world"));
		Files.writeString(tmp.resolve(COMPLETE_MARKER), Instant.now().toString(), StandardCharsets.UTF_8);
		deleteRecursively(slotDir);
		Files.move(tmp, slotDir, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Restores a slot over the world folder. The world must be closed (server
	 * stopped, session.lock released) before calling this.
	 *
	 * <ol>
	 * <li>Copy the current world to _pre_load/world.</li>
	 * <li>Write _pre_load/restore.inprogress.</li>
	 * <li>Wipe the world folder (except session.lock) and copy the slot in.</li>
	 * <li>Delete the marker.</li>
	 * </ol>
	 * If step 3 fails, the world is rolled back from _pre_load and the original
	 * exception is rethrown. If the rollback also fails, the marker stays so a
	 * later launch can offer recovery (M2).
	 */
	public static void restore(Path worldDir, String worldFolderName, String slot) throws IOException {
		Path source = slotDir(worldFolderName, slot);
		if (!Files.isRegularFile(source.resolve(COMPLETE_MARKER))) {
			throw new NoSuchFileException(source.toString(), null, "slot is missing or incomplete");
		}
		Path sourceWorld = source.resolve("world");

		Path preLoad = preLoadDir(worldFolderName);
		Path marker = restoreMarker(worldFolderName);
		if (Files.exists(marker)) {
			// A previous restore never finished: _pre_load holds the last good
			// world, so don't overwrite it with whatever is in the world folder now.
			throw new IOException("A previous restore of '" + worldFolderName + "' did not finish; "
					+ "the pre-load backup is at " + preLoad + ". Recover it manually before loading again.");
		}

		deleteRecursively(preLoad);
		Files.createDirectories(preLoad);
		copyWorld(worldDir, preLoad.resolve("world"));
		Files.writeString(marker, "world=" + worldFolderName + "\nslot=" + slot + "\nstarted=" + Instant.now() + "\n",
				StandardCharsets.UTF_8);

		try {
			wipeWorld(worldDir);
			copyWorld(sourceWorld, worldDir);
		} catch (IOException | RuntimeException e) {
			QuicksaveMod.LOGGER.error("Restore of slot '{}' failed, rolling back from {}", slot, preLoad, e);
			try {
				wipeWorld(worldDir);
				copyWorld(preLoad.resolve("world"), worldDir);
				Files.deleteIfExists(marker);
				QuicksaveMod.LOGGER.info("Rollback from pre-load backup succeeded");
			} catch (IOException | RuntimeException rollback) {
				e.addSuppressed(rollback);
				QuicksaveMod.LOGGER.error("Rollback FAILED; the pre-load backup is kept at {}", preLoad, rollback);
			}
			throw e;
		}
		Files.deleteIfExists(marker);
	}

	/**
	 * True if another holder (this JVM's integrated server or another process)
	 * still holds the world's session.lock.
	 */
	public static boolean isSessionLocked(Path worldDir) {
		Path lock = worldDir.resolve(SESSION_LOCK);
		if (!Files.exists(lock)) {
			return false;
		}
		try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
			FileLock fileLock = channel.tryLock();
			if (fileLock == null) {
				return true;
			}
			fileLock.release();
			return false;
		} catch (OverlappingFileLockException e) {
			return true;
		} catch (IOException e) {
			QuicksaveMod.LOGGER.warn("Could not probe {}", lock, e);
			return true;
		}
	}

	/** Copies every file under {@code from} to {@code to}, skipping the top-level session.lock. */
	static void copyWorld(Path from, Path to) throws IOException {
		Files.createDirectories(to);
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.getParent().equals(from) && file.getFileName().toString().equals(SESSION_LOCK)) {
					return FileVisitResult.CONTINUE;
				}
				if (!attrs.isRegularFile()) {
					return FileVisitResult.CONTINUE;
				}
				Files.copy(file, to.resolve(from.relativize(file).toString()),
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Deletes everything inside the world folder except the top-level session.lock. */
	static void wipeWorld(Path worldDir) throws IOException {
		try (var children = Files.list(worldDir)) {
			for (Path child : (Iterable<Path>) children::iterator) {
				if (child.getFileName().toString().equals(SESSION_LOCK)) {
					continue;
				}
				deleteRecursively(child);
			}
		}
	}

	static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
