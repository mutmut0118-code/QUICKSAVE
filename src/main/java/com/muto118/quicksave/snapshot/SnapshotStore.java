package com.muto118.quicksave.snapshot;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.config.QuicksaveConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Static entry point to the saves of every world under {@code .minecraft/quicksaves/}.
 * Storage itself lives in {@link WorldStore}; this class adds the slot policy:
 * new slot ids, rotation ({@code quicksaveSlots}, {@code autosaveSlots}), garbage
 * collection and the {@code maxStorageMB} cap.
 */
public final class SnapshotStore {
	public static final String SESSION_LOCK = "session.lock";
	private static final DateTimeFormatter SLOT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

	/** A saved slot of one world. */
	public record Slot(String id, SaveMeta meta) {
	}

	/** What one save did. */
	public record SaveOutcome(String world, String slot, WorldStore.SaveStats stats, List<String> rotatedOut, List<String> pruned,
			long freedBytes) {
	}

	private SnapshotStore() {
	}

	public static Path rootDir() {
		return FabricLoader.getInstance().getGameDir().resolve("quicksaves");
	}

	public static WorldStore store(String worldFolderName) {
		return new WorldStore(rootDir().resolve(worldFolderName));
	}

	public static Path preLoadDir(String worldFolderName) {
		return store(worldFolderName).preLoadDir();
	}

	public static Path restoreMarker(String worldFolderName) {
		return store(worldFolderName).restoreMarker();
	}

	public static boolean hasSave(String worldFolderName) {
		return !list(worldFolderName).isEmpty();
	}

	/** All complete slots, newest first. */
	public static List<Slot> list(String worldFolderName) {
		return store(worldFolderName).list();
	}

	/** Newest complete slot whose type is in {@code types} (null = any type). */
	public static Optional<Slot> latest(String worldFolderName, @Nullable Collection<SlotType> types) {
		return list(worldFolderName).stream()
				.filter(slot -> types == null || types.contains(slot.meta().type()))
				.findFirst();
	}

	/** A fresh, unique slot id such as quick-20261016-213011-123. */
	static String newSlotId(WorldStore store, SlotType type) {
		String base = switch (type) {
			case QUICK -> "quick";
			case AUTO -> "auto";
			case MANUAL -> "manual";
		} + "-" + LocalDateTime.now().format(SLOT_TIME);
		String id = base;
		for (int i = 2; Files.exists(store.slotDir(id)); i++) {
			id = base + "-" + i;
		}
		return id;
	}

	/**
	 * Saves the (already flushed, not changing) world into a new slot. Call
	 * {@link #afterSave} once the world may change again.
	 */
	public static SaveOutcome save(Path worldDir, String worldFolderName, SaveMeta meta, byte @Nullable [] thumbnail)
			throws IOException {
		WorldStore store = store(worldFolderName);
		String slot = newSlotId(store, meta.type());
		WorldStore.SaveStats stats = store.save(worldDir, slot, meta, thumbnail);
		return new SaveOutcome(worldFolderName, slot, stats, List.of(), List.of(), 0L);
	}

	/** Rotation, garbage collection and the storage cap. Doesn't touch the world folder. */
	public static SaveOutcome afterSave(String worldFolderName, SaveOutcome saved, SlotType type) throws IOException {
		WorldStore store = store(worldFolderName);
		QuicksaveConfig config = QuicksaveConfig.get();
		List<String> rotated = switch (type) {
			case QUICK -> store.rotate(SlotType.QUICK, config.quicksaveSlots);
			case AUTO -> store.rotate(SlotType.AUTO, config.autosaveSlots);
			case MANUAL -> List.of();
		};
		long freed = store.gc();
		List<String> pruned = config.maxStorageMB > 0
				? store.prune((long) config.maxStorageMB << 20, saved.slot())
				: List.of();
		return new SaveOutcome(saved.world(), saved.slot(), saved.stats(), rotated, pruned, freed);
	}

	/** Restores a slot over the world folder (world closed). See {@link WorldStore#restore}. */
	public static void restore(Path worldDir, String worldFolderName, String slot) throws IOException {
		store(worldFolderName).restore(worldDir, slot);
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
			for (Path dir : (Iterable<Path>) worlds::iterator) {
				String world = dir.getFileName().toString();
				Path marker = store(world).restoreMarker();
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

	public static void finishInterrupted(Path worldDir, InterruptedRestore restore) throws IOException {
		if (restore.slot() == null) {
			throw new IOException("the marker doesn't say which save was loading; roll back instead");
		}
		store(restore.worldFolderName()).finishInterrupted(worldDir, restore.slot());
	}

	public static void rollBackInterrupted(Path worldDir, InterruptedRestore restore) throws IOException {
		store(restore.worldFolderName()).rollBackInterrupted(worldDir);
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
}
