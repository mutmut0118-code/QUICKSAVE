package com.muto118.quicksave.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStoreTest {
	private static final long HOUR = 3_600_000L;

	@TempDir
	Path tmp;
	Path world;
	WorldStore store;
	long clock = System.currentTimeMillis() - 10 * HOUR;

	@BeforeEach
	void setUp() throws IOException {
		this.world = this.tmp.resolve("saves").resolve("My World");
		this.store = new WorldStore(this.tmp.resolve("quicksaves").resolve("My World"));
		write("level.dat", bytes(1, 300));
		write("region/r.0.0.mca", bytes(2, 8192));
		write("region/r.0.1.mca", bytes(3, 8192));
		write("data/raids.dat", bytes(4, 50));
		write("playerdata/abc.dat", bytes(5, 400));
		Files.writeString(this.world.resolve(SnapshotStore.SESSION_LOCK), "lock");
	}

	// ------------------------------------------------------------ helpers

	static byte[] bytes(int seed, int size) {
		byte[] data = new byte[size];
		new Random(seed).nextBytes(data);
		return data;
	}

	/** Writes a world file with an old, distinct mtime so the hash cache can trust it. */
	void write(String rel, byte[] data) throws IOException {
		Path file = this.world.resolve(rel);
		Files.createDirectories(file.getParent());
		Files.write(file, data);
		Files.setLastModifiedTime(file, FileTime.fromMillis(this.clock += 1000));
	}

	Map<String, String> worldContents() throws IOException {
		Map<String, String> contents = new TreeMap<>();
		for (Path file : WorldStore.worldFiles(this.world)) {
			contents.put(WorldStore.relative(this.world, file), Xxh64.toHex(Xxh64.hash(Files.readAllBytes(file))));
		}
		return contents;
	}

	SaveMeta meta(SlotType type, long created) {
		return new SaveMeta(type.name(), type, created, 0L, "minecraft:overworld", 0, 64, 0, 20f, 0L);
	}

	WorldStore.SaveStats save(String slot, SlotType type) throws IOException {
		return this.store.save(this.world, slot, meta(type, this.clock += 1000));
	}

	Set<String> objectHashes() throws IOException {
		Set<String> hashes = new HashSet<>();
		Path objects = this.store.dir().resolve("objects");
		try (Stream<Path> files = Files.walk(objects)) {
			files.filter(Files::isRegularFile).forEach(f -> hashes.add(f.getFileName().toString()));
		}
		return hashes;
	}

	Set<String> referencedHashes() throws IOException {
		Set<String> hashes = new HashSet<>();
		for (SnapshotStore.Slot slot : this.store.list()) {
			for (WorldStore.Entry e : WorldStore.readManifest(this.store.slotDir(slot.id()).resolve(WorldStore.MANIFEST)).files()) {
				hashes.add(e.hash());
			}
		}
		Path preLoad = this.store.preLoadDir().resolve(WorldStore.MANIFEST);
		if (Files.isRegularFile(preLoad)) {
			for (WorldStore.Entry e : WorldStore.readManifest(preLoad).files()) {
				hashes.add(e.hash());
			}
		}
		return hashes;
	}

	// ------------------------------------------------------------ tests

	@Test
	void restoreRewindsChangesAdditionsAndDeletions() throws IOException {
		Map<String, String> original = worldContents();
		save("quick-1", SlotType.QUICK);

		write("region/r.0.0.mca", bytes(99, 8192));        // changed
		write("region/r.5.5.mca", bytes(98, 4096));        // created after the save
		Files.delete(this.world.resolve("data/raids.dat")); // deleted after the save
		Files.writeString(this.world.resolve(SnapshotStore.SESSION_LOCK), "held by server");

		this.store.restore(this.world, "quick-1");

		assertEquals(original, worldContents());
		assertEquals("held by server", Files.readString(this.world.resolve(SnapshotStore.SESSION_LOCK)),
				"session.lock is never touched");
		assertFalse(Files.exists(this.store.restoreMarker()));
		assertTrue(Files.isRegularFile(this.store.preLoadDir().resolve(WorldStore.MANIFEST)));
	}

	@Test
	void restoredFilesAreRealCopiesNotLinks() throws IOException {
		save("quick-1", SlotType.QUICK);
		this.store.restore(this.world, "quick-1");
		// Simulate Minecraft rewriting a region file in place.
		Path region = this.world.resolve("region/r.0.0.mca");
		Files.write(region, bytes(77, 8192));
		write("level.dat", bytes(1, 300));
		Map<String, String> changed = worldContents();
		this.store.restore(this.world, "quick-1");
		assertNotEquals(changed.get("region/r.0.0.mca"), worldContents().get("region/r.0.0.mca"),
				"the stored object must not have changed with the world file");
		assertArrayEquals(bytes(2, 8192), Files.readAllBytes(region));
	}

	@Test
	void secondSaveOnlyReadsChangedFiles() throws IOException {
		WorldStore.SaveStats first = save("quick-1", SlotType.QUICK);
		assertEquals(5, first.files());
		assertEquals(5, first.hashed());
		assertEquals(5, first.newObjects());

		WorldStore.SaveStats unchanged = save("quick-2", SlotType.QUICK);
		assertEquals(0, unchanged.hashed(), "size+mtime unchanged: nothing re-read");
		assertEquals(0, unchanged.newObjects());

		write("region/r.0.1.mca", bytes(42, 8192));
		WorldStore.SaveStats oneChanged = save("quick-3", SlotType.QUICK);
		assertEquals(1, oneChanged.hashed());
		assertEquals(1, oneChanged.newObjects());
		assertEquals(8192, oneChanged.newBytes());
	}

	@Test
	void hashCacheSurvivesANewStoreInstance() throws IOException {
		save("quick-1", SlotType.QUICK);
		this.store = new WorldStore(this.store.dir());
		assertEquals(0, save("quick-2", SlotType.QUICK).hashed());
	}

	@Test
	void sameSizeSameMtimeRewriteInsideRacyWindowIsDetected() throws IOException {
		Path region = this.world.resolve("region/r.0.0.mca");
		long recent = System.currentTimeMillis();
		Files.write(region, bytes(10, 8192));
		Files.setLastModifiedTime(region, FileTime.fromMillis(recent));
		save("quick-1", SlotType.QUICK);

		// Rewritten within the timestamp granularity: same size, same mtime, new bytes.
		Files.write(region, bytes(11, 8192));
		Files.setLastModifiedTime(region, FileTime.fromMillis(recent));
		save("quick-2", SlotType.QUICK);

		this.store.restore(this.world, "quick-2");
		assertArrayEquals(bytes(11, 8192), Files.readAllBytes(region));
		this.store.restore(this.world, "quick-1");
		assertArrayEquals(bytes(10, 8192), Files.readAllBytes(region));
	}

	@Test
	void restoreKeepsSavedMtimesSoTheNextSaveIsCheap() throws IOException {
		save("quick-1", SlotType.QUICK);
		write("region/r.0.0.mca", bytes(50, 8192));
		this.store.restore(this.world, "quick-1");
		assertEquals(0, save("quick-2", SlotType.QUICK).hashed());
	}

	@Test
	void rotationKeepsNewestAndGcDropsUnreferencedObjects() throws IOException {
		for (int i = 1; i <= 5; i++) {
			write("region/r.0.0.mca", bytes(100 + i, 8192));
			save("quick-" + i, SlotType.QUICK);
		}
		save("manual-1", SlotType.MANUAL);
		List<String> deleted = this.store.rotate(SlotType.QUICK, 3);
		assertEquals(List.of("quick-2", "quick-1"), deleted);
		assertEquals(List.of("manual-1", "quick-5", "quick-4", "quick-3"),
				this.store.list().stream().map(SnapshotStore.Slot::id).toList());

		assertTrue(this.store.gc() >= 2 * 8192);
		assertEquals(referencedHashes(), objectHashes());
		assertEquals(List.of(), this.store.checkConsistency());

		this.store.restore(this.world, "quick-3");
		assertArrayEquals(bytes(103, 8192), Files.readAllBytes(this.world.resolve("region/r.0.0.mca")));
	}

	@Test
	void gcKeepsObjectsOnlyThePreLoadBackupUses() throws IOException {
		save("quick-1", SlotType.QUICK);
		write("region/r.0.0.mca", bytes(60, 8192));
		this.store.restore(this.world, "quick-1"); // _pre_load now holds the bytes(60) version
		assertEquals(referencedHashes(), objectHashes(), "restore garbage-collects by itself");
		write("region/r.0.0.mca", bytes(61, 8192));
		this.store.restore(this.world, "quick-1"); // replaces _pre_load: the bytes(60) object is garbage
		assertEquals(referencedHashes(), objectHashes());
		assertEquals(List.of(), this.store.checkConsistency());
		write("region/r.0.0.mca", bytes(60, 8192));
		this.store.restore(this.world, "quick-1");
		this.store.rollBackInterrupted(this.world);
		assertArrayEquals(bytes(60, 8192), Files.readAllBytes(this.world.resolve("region/r.0.0.mca")));
	}

	@Test
	void pruneDeletesOldestAutosavesFirstThenQuicksavesButNeverManualOrNewest() throws IOException {
		write("region/r.9.9.mca", bytes(200, 64 * 1024));
		save("manual-1", SlotType.MANUAL);
		for (int i = 1; i <= 3; i++) {
			write("region/r.9.9.mca", bytes(200 + i, 64 * 1024));
			save("auto-" + i, SlotType.AUTO);
		}
		write("region/r.9.9.mca", bytes(210, 64 * 1024));
		save("quick-1", SlotType.QUICK);
		write("region/r.9.9.mca", bytes(211, 64 * 1024));
		save("quick-2", SlotType.QUICK);

		List<String> pruned = this.store.prune(1, "quick-2"); // impossible cap: prune all it may
		assertEquals(List.of("auto-1", "auto-2", "auto-3", "quick-1"), pruned);
		assertEquals(List.of("quick-2", "manual-1"), this.store.list().stream().map(SnapshotStore.Slot::id).toList());
		assertEquals(referencedHashes(), objectHashes());
	}

	@Test
	void legacyFullCopySlotsAreReadAndConverted() throws IOException {
		Map<String, String> original = worldContents();
		Path legacySlot = this.store.dir().resolve("saves").resolve("quick");
		WorldStore.copyTree(this.world, legacySlot.resolve("world"));
		Files.writeString(legacySlot.resolve(WorldStore.COMPLETE), "2026-10-16T04:50:47Z");

		List<SnapshotStore.Slot> slots = this.store.list();
		assertEquals(1, slots.size());
		assertEquals(SlotType.QUICK, slots.get(0).meta().type());

		write("level.dat", bytes(31, 300));
		save("quick-new", SlotType.QUICK); // converts the legacy slot
		assertTrue(Files.isRegularFile(legacySlot.resolve(WorldStore.MANIFEST)));
		assertFalse(Files.exists(legacySlot.resolve("world")));

		this.store.restore(this.world, "quick");
		assertEquals(original, worldContents());
	}

	@Test
	void legacyPreLoadCopyCanBeRolledBack() throws IOException {
		Map<String, String> original = worldContents();
		WorldStore.copyTree(this.world, this.store.preLoadDir().resolve("world"));
		Files.writeString(this.store.restoreMarker(), "world=My World\nslot=quick\n");
		Files.writeString(this.world.resolve("junk.txt"), "half restored");
		Files.delete(this.world.resolve("level.dat"));

		this.store.rollBackInterrupted(this.world);
		assertEquals(original, worldContents());
		assertFalse(Files.exists(this.store.restoreMarker()));
	}

	@Test
	void corruptObjectRollsTheWorldBackAndKeepsNothingHalfDone() throws IOException {
		save("quick-1", SlotType.QUICK);
		write("region/r.0.0.mca", bytes(70, 8192));
		Map<String, String> beforeLoad = worldContents();

		String hash = WorldStore.readManifest(this.store.slotDir("quick-1").resolve(WorldStore.MANIFEST)).files().stream()
				.filter(e -> e.path().equals("region/r.0.1.mca")).findFirst().orElseThrow().hash();
		Files.write(this.store.objectPath(hash), bytes(0, 8192)); // bit rot (same size: only the hash catches it)

		assertThrows(IOException.class, () -> this.store.restore(this.world, "quick-1"));
		assertEquals(beforeLoad, worldContents(), "rolled back to the world as it was before the load");
		assertFalse(Files.exists(this.store.restoreMarker()));
	}

	@Test
	void refusesToRestoreOverAnUnfinishedRestore() throws IOException {
		save("quick-1", SlotType.QUICK);
		Files.createDirectories(this.store.preLoadDir());
		Files.writeString(this.store.restoreMarker(), "world=My World\nslot=quick-1\n");
		assertThrows(IOException.class, () -> this.store.restore(this.world, "quick-1"));
	}

	@Test
	void finishInterruptedRebuildsTheSlot() throws IOException {
		Map<String, String> saved = worldContents();
		save("quick-1", SlotType.QUICK);
		write("level.dat", bytes(80, 300));
		this.store.capturePreLoad(this.world);
		Files.writeString(this.store.restoreMarker(), "world=My World\nslot=quick-1\n");
		Files.writeString(this.world.resolve("junk.txt"), "half restored");

		this.store.finishInterrupted(this.world, "quick-1");
		assertEquals(saved, worldContents());
		assertFalse(Files.exists(this.store.restoreMarker()));
	}

	@Test
	void manifestPathsCannotEscapeTheWorldFolder() {
		assertThrows(IOException.class, () -> WorldStore.resolveSafely(this.world, "../other/level.dat"));
		assertThrows(IOException.class, () -> WorldStore.resolveSafely(this.world, "region/../../x"));
	}

	@Test
	void incompleteSlotsAreInvisible() throws IOException {
		Files.createDirectories(this.store.dir().resolve("saves").resolve("quick-x"));
		Files.writeString(this.store.dir().resolve("saves").resolve("quick-x").resolve(WorldStore.MANIFEST), "{}");
		assertTrue(this.store.list().isEmpty());
		assertThrows(IOException.class, () -> this.store.restore(this.world, "quick-x"));
	}
}
