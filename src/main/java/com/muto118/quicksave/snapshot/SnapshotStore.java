package com.muto118.quicksave.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muto118.quicksave.QuicksaveMod;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * M1/M2 snapshot store: a slot is a plain full copy of the world folder.
 *
 * <pre>
 * .minecraft/quicksaves/&lt;world folder&gt;/
 *   saves/&lt;slot&gt;/world/        full copy of saves/&lt;world&gt;/ (minus session.lock)
 *   saves/&lt;slot&gt;/meta.json     {@link SaveMeta} (M1 slots have none: treated as a quicksave)
 *   saves/&lt;slot&gt;/complete      written last; a slot without it is ignored
 *   _pre_load/world/            copy of the world taken right before a restore
 *   _pre_load/restore.inprogress  present while a restore is running (world=, slot=)
 * </pre>
 *
 * Slot names: "quick" (F6, overwritten; rotation is M3) and "manual-yyyyMMdd-HHmmss".
 *
 * M3 replaces the slot layout with content-addressed objects + manifests.
 * All copies are real byte copies, never hardlinks: Minecraft rewrites region
 * files in place, so a hardlink would let the live world corrupt the snapshot.
 */
public final class SnapshotStore {
	/** The quicksave slot. M3 turns this into rotating quick-N slots. */
	public static final String QUICK_SLOT = "quick";
	private static final String META = "meta.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter SLOT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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

	/** A saved slot of one world. */
	public record Slot(String id, SaveMeta meta) {
	}

	public static boolean hasSave(String worldFolderName) {
		return latest(worldFolderName, null).isPresent();
	}

	/** All complete slots, newest first. */
	public static List<Slot> list(String worldFolderName) {
		Path savesDir = worldStore(worldFolderName).resolve("saves");
		List<Slot> slots = new ArrayList<>();
		if (!Files.isDirectory(savesDir)) {
			return slots;
		}
		try (var children = Files.list(savesDir)) {
			for (Path dir : (Iterable<Path>) children::iterator) {
				String id = dir.getFileName().toString();
				if (id.endsWith(".tmp") || !Files.isRegularFile(dir.resolve(COMPLETE_MARKER))) {
					continue;
				}
				slots.add(new Slot(id, readMeta(dir)));
			}
		} catch (IOException e) {
			QuicksaveMod.LOGGER.warn("Could not list saves of '{}'", worldFolderName, e);
		}
		slots.sort(Comparator.comparingLong((Slot slot) -> slot.meta().createdMs()).reversed());
		return slots;
	}

	/** Newest complete slot whose type is in {@code types} (null = any type). */
	public static Optional<Slot> latest(String worldFolderName, @Nullable Collection<SlotType> types) {
		return list(worldFolderName).stream()
				.filter(slot -> types == null || types.contains(slot.meta().type()))
				.findFirst();
	}

	/** A fresh slot id for a save of this type. */
	public static String newSlotId(SlotType type) {
		return switch (type) {
			case QUICK -> QUICK_SLOT;
			case AUTO -> "auto";
			case MANUAL -> "manual-" + LocalDateTime.now().format(SLOT_TIME);
		};
	}

	private static SaveMeta readMeta(Path slotDir) {
		Path meta = slotDir.resolve(META);
		if (Files.isRegularFile(meta)) {
			try {
				SaveMeta parsed = GSON.fromJson(Files.readString(meta, StandardCharsets.UTF_8), SaveMeta.class);
				if (parsed != null && parsed.type() != null) {
					return parsed;
				}
			} catch (IOException | RuntimeException e) {
				QuicksaveMod.LOGGER.warn("Bad {}, using defaults", meta, e);
			}
		}
		// M1 slot (no meta.json): a quicksave, created when "complete" was written.
		long created;
		try {
			created = Instant.parse(Files.readString(slotDir.resolve(COMPLETE_MARKER), StandardCharsets.UTF_8).trim())
					.toEpochMilli();
		} catch (IOException | RuntimeException e) {
			created = slotDir.toFile().lastModified();
		}
		return new SaveMeta("Quicksave", SlotType.QUICK, created, 0L, null, 0, 0, 0, 0f, 0L);
	}

	/**
	 * Copies the (already flushed) world folder into a slot. Writes into a
	 * temporary directory first and swaps it in, so a crash mid-save never
	 * leaves a half-written slot that looks complete.
	 */
	public static void save(Path worldDir, String worldFolderName, String slot, SaveMeta meta) throws IOException {
		Path slotDir = slotDir(worldFolderName, slot);
		Path tmp = slotDir.resolveSibling(slot + ".tmp");
		deleteRecursively(tmp);
		Files.createDirectories(tmp);
		copyWorld(worldDir, tmp.resolve("world"));
		Files.writeString(tmp.resolve(META), GSON.toJson(meta), StandardCharsets.UTF_8);
		Files.writeString(tmp.resolve(COMPLETE_MARKER), Instant.ofEpochMilli(meta.createdMs()).toString(),
				StandardCharsets.UTF_8);
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
	 * later launch can offer recovery ({@link #findInterrupted()}).
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
					+ "restart the game to finish or roll back that restore first (pre-load backup: " + preLoad + ").");
		}

		deleteRecursively(preLoad);
		Files.createDirectories(preLoad);
		copyWorld(worldDir, preLoad.resolve("world"));
		Files.writeString(marker, "world=" + worldFolderName + "\nslot=" + slot + "\nstarted=" + Instant.now() + "\n",
				StandardCharsets.UTF_8);

		copyOverWorldOrRollBack(worldDir, worldFolderName, slot, sourceWorld);
	}

	private static void copyOverWorldOrRollBack(Path worldDir, String worldFolderName, String slot, Path sourceWorld)
			throws IOException {
		Path preLoad = preLoadDir(worldFolderName);
		Path marker = restoreMarker(worldFolderName);
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

	/** A restore that was cut off (crash, power loss) before it finished. */
	public record InterruptedRestore(String worldFolderName, @Nullable String slot) {
	}

	/** Every world whose _pre_load still has a restore.inprogress marker. */
	public static List<InterruptedRestore> findInterrupted() {
		List<InterruptedRestore> found = new ArrayList<>();
		Path root = rootDir();
		if (!Files.isDirectory(root)) {
			return found;
		}
		try (var worlds = Files.list(root)) {
			for (Path store : (Iterable<Path>) worlds::iterator) {
				String world = store.getFileName().toString();
				Path marker = restoreMarker(world);
				if (!Files.isRegularFile(marker)) {
					continue;
				}
				Properties props = new Properties();
				try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
					props.load(reader);
				} catch (IOException e) {
					QuicksaveMod.LOGGER.warn("Unreadable {}", marker, e);
				}
				found.add(new InterruptedRestore(world, props.getProperty("slot")));
			}
		} catch (IOException e) {
			QuicksaveMod.LOGGER.warn("Could not scan {}", root, e);
		}
		return found;
	}

	/** Re-runs the interrupted restore. _pre_load (the last good world) is left as it is. */
	public static void finishInterrupted(Path worldDir, InterruptedRestore restore) throws IOException {
		if (restore.slot() == null || !Files.isRegularFile(slotDir(restore.worldFolderName(), restore.slot()).resolve(COMPLETE_MARKER))) {
			throw new NoSuchFileException("slot '" + restore.slot() + "' is missing; roll back instead");
		}
		Files.createDirectories(worldDir);
		copyOverWorldOrRollBack(worldDir, restore.worldFolderName(), restore.slot(),
				slotDir(restore.worldFolderName(), restore.slot()).resolve("world"));
		Files.deleteIfExists(restoreMarker(restore.worldFolderName()));
	}

	/** Puts the world back the way it was before the interrupted restore started. */
	public static void rollBackInterrupted(Path worldDir, InterruptedRestore restore) throws IOException {
		Path backup = preLoadDir(restore.worldFolderName()).resolve("world");
		if (!Files.isRegularFile(backup.resolve("level.dat"))) {
			throw new NoSuchFileException(backup.toString(), null, "pre-load backup is incomplete");
		}
		Files.createDirectories(worldDir);
		wipeWorld(worldDir);
		copyWorld(backup, worldDir);
		Files.deleteIfExists(restoreMarker(restore.worldFolderName()));
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
