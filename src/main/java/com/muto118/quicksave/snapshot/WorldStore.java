package com.muto118.quicksave.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.muto118.quicksave.QuicksaveMod;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Content-addressed save storage for one world.
 *
 * <pre>
 * quicksaves/&lt;world&gt;/
 *   objects/ab/ab34f9...          one file per unique content (xxHash64, 16 hex chars)
 *   objects/tmp/                  in-flight copies, moved into place when complete
 *   saves/&lt;slot&gt;/manifest.json   every world file: path, size, mtime, hash
 *   saves/&lt;slot&gt;/meta.json       {@link SaveMeta}
 *   saves/&lt;slot&gt;/thumb.png       256x144 screenshot (optional)
 *   saves/&lt;slot&gt;/complete        written last; a slot without it is ignored
 *   _pre_load/manifest.json       the world right before the last load
 *   _pre_load/restore.inprogress  present while a restore runs (world=, slot=, started=)
 *   hashcache.json                path -> size, mtime, hash: unchanged files aren't re-read
 * </pre>
 *
 * Objects are immutable. Restores always write real copies (never links): Minecraft
 * rewrites region files in place, which would corrupt a linked object.
 *
 * <p>M1/M2 "legacy" slots ({@code saves/<slot>/world/} full copies, {@code _pre_load/world/})
 * are still read, and legacy slots are converted to manifests on the next save.
 *
 * <p>Mutating methods hold one global lock: there is only ever one world open.
 */
public final class WorldStore {
	static final String MANIFEST = "manifest.json";
	static final String META = "meta.json";
	public static final String THUMBNAIL = "thumb.png";
	static final String COMPLETE = "complete";
	static final String PRE_LOAD = "_pre_load";
	static final String RESTORE_MARKER = "restore.inprogress";
	static final String LEGACY_WORLD = "world";
	private static final String CACHE = "hashcache.json";
	/**
	 * A cached hash is only trusted if the file's mtime is at least this much older
	 * than when it was hashed (the "racy git" rule): a file rewritten within the
	 * filesystem's timestamp granularity could otherwise keep the same size+mtime.
	 */
	static final long RACY_MS = 2000L;
	private static final int BUFFER = 1 << 20;
	private static final Object LOCK = new Object();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public record Entry(String path, long size, long mtime, String hash) {
	}

	public record Manifest(int version, List<Entry> files) {
	}

	/**
	 * @param files      world files in the snapshot
	 * @param hashed     files that had to be read (new or changed since the last save)
	 * @param newObjects objects written
	 * @param newBytes   bytes written to objects/
	 */
	public record SaveStats(int files, int hashed, int newObjects, long newBytes) {
	}

	record CacheEntry(long size, long mtime, String hash, long checkedAt) {
	}

	private final Path dir;
	private final Path objects;
	private final Path saves;
	private final Path preLoad;
	@Nullable
	private Map<String, CacheEntry> cache;

	public WorldStore(Path dir) {
		this.dir = dir;
		this.objects = dir.resolve("objects");
		this.saves = dir.resolve("saves");
		this.preLoad = dir.resolve(PRE_LOAD);
	}

	public Path dir() {
		return this.dir;
	}

	public Path preLoadDir() {
		return this.preLoad;
	}

	public Path restoreMarker() {
		return this.preLoad.resolve(RESTORE_MARKER);
	}

	Path slotDir(String slot) {
		return this.saves.resolve(slot);
	}

	Path objectPath(String hash) {
		return this.objects.resolve(hash.substring(0, 2)).resolve(hash);
	}

	// ---------------------------------------------------------------- listing

	/** All complete slots, newest first. Safe to call from any thread. */
	public List<SnapshotStore.Slot> list() {
		List<SnapshotStore.Slot> slots = new ArrayList<>();
		if (!Files.isDirectory(this.saves)) {
			return slots;
		}
		try (var children = Files.list(this.saves)) {
			for (Path slotDir : (Iterable<Path>) children::iterator) {
				String id = slotDir.getFileName().toString();
				if (id.endsWith(".tmp") || !Files.isRegularFile(slotDir.resolve(COMPLETE))) {
					continue;
				}
				try {
					slots.add(new SnapshotStore.Slot(id, readMeta(slotDir)));
				} catch (RuntimeException e) {
					QuicksaveMod.LOGGER.warn("Skipping unreadable slot {}", slotDir, e);
				}
			}
		} catch (IOException e) {
			QuicksaveMod.LOGGER.warn("Could not list {}", this.saves, e);
		}
		slots.sort(Comparator.comparingLong((SnapshotStore.Slot slot) -> slot.meta().createdMs()).reversed());
		return slots;
	}

	public boolean hasSlot(String slot) {
		return Files.isRegularFile(slotDir(slot).resolve(COMPLETE));
	}

	static SaveMeta readMeta(Path slotDir) {
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
			created = Instant.parse(Files.readString(slotDir.resolve(COMPLETE), StandardCharsets.UTF_8).trim()).toEpochMilli();
		} catch (IOException | RuntimeException e) {
			created = slotDir.toFile().lastModified();
		}
		return new SaveMeta("Quicksave", SlotType.QUICK, created, 0L, null, 0, 0, 0, 0f, 0L);
	}

	// ---------------------------------------------------------------- saving

	/**
	 * Snapshots {@code worldDir} into slot {@code slotId}. The world must not change
	 * while this runs (server thread with saving disabled, or server stopped).
	 */
	public SaveStats save(Path worldDir, String slotId, SaveMeta meta) throws IOException {
		return this.save(worldDir, slotId, meta, null);
	}

	/** @param thumbnail PNG bytes for thumb.png, or null */
	public SaveStats save(Path worldDir, String slotId, SaveMeta meta, byte @Nullable [] thumbnail) throws IOException {
		synchronized (LOCK) {
			this.migrateLegacySlots();
			Path slotDir = slotDir(slotId);
			Path tmp = this.saves.resolve(slotId + ".tmp");
			deleteRecursively(tmp);
			Files.createDirectories(tmp);
			int[] counters = new int[3];
			long[] bytes = new long[1];
			Manifest manifest = this.snapshot(worldDir, counters, bytes);
			writeJson(tmp.resolve(MANIFEST), manifest);
			writeJson(tmp.resolve(META), meta);
			if (thumbnail != null) {
				Files.write(tmp.resolve(THUMBNAIL), thumbnail);
			}
			Files.writeString(tmp.resolve(COMPLETE), Instant.ofEpochMilli(meta.createdMs()).toString(), StandardCharsets.UTF_8);
			deleteRecursively(slotDir);
			Files.move(tmp, slotDir, StandardCopyOption.ATOMIC_MOVE);
			this.saveCache();
			return new SaveStats(counters[0], counters[1], counters[2], bytes[0]);
		}
	}

	/**
	 * Reads the world into objects/ and returns its manifest. Files whose size and
	 * mtime match the hash cache (and aren't racy) are not read at all.
	 */
	private Manifest snapshot(Path worldDir, int[] counters, long[] newBytes) throws IOException {
		Map<String, CacheEntry> oldCache = this.cache();
		Map<String, CacheEntry> newCache = new HashMap<>();
		List<Entry> entries = new ArrayList<>();
		for (Path file : worldFiles(worldDir)) {
			String rel = relative(worldDir, file);
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			long size = attrs.size();
			long mtime = attrs.lastModifiedTime().toMillis();
			CacheEntry cached = oldCache.get(rel);
			String hash;
			if (cached != null && cached.size() == size && cached.mtime() == mtime
					&& mtime < cached.checkedAt() - RACY_MS && Files.isRegularFile(objectPath(cached.hash()))) {
				hash = cached.hash();
				newCache.put(rel, cached);
			} else {
				long[] written = new long[1];
				hash = this.ingest(file, written);
				counters[1]++;
				if (written[0] >= 0) {
					counters[2]++;
					newBytes[0] += written[0];
				}
				BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
				if (after.size() != size || after.lastModifiedTime().toMillis() != mtime) {
					throw new IOException(rel + " changed while it was being saved");
				}
				newCache.put(rel, new CacheEntry(size, mtime, hash, System.currentTimeMillis()));
			}
			entries.add(new Entry(rel, size, mtime, hash));
			counters[0]++;
		}
		this.cache = newCache;
		return new Manifest(1, entries);
	}

	/**
	 * Copies a file into objects/ while hashing it, in one pass.
	 *
	 * @param written set to the bytes written, or -1 if the object already existed
	 * @return the hash
	 */
	private String ingest(Path file, long[] written) throws IOException {
		Path tmpDir = this.objects.resolve("tmp");
		Files.createDirectories(tmpDir);
		Path tmp = tmpDir.resolve(UUID.randomUUID() + ".part");
		Xxh64 hasher = new Xxh64();
		long total = 0;
		try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(tmp)) {
			byte[] buffer = new byte[BUFFER];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				hasher.update(buffer, 0, read);
				out.write(buffer, 0, read);
				total += read;
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		String hash = hasher.hex();
		Path target = objectPath(hash);
		if (Files.isRegularFile(target)) {
			Files.delete(tmp);
			written[0] = -1;
		} else {
			Files.createDirectories(target.getParent());
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			written[0] = total;
		}
		return hash;
	}

	// ---------------------------------------------------------------- restoring

	/**
	 * Restores a slot over the world folder. The world must be closed (server
	 * stopped, session.lock released).
	 *
	 * <ol>
	 * <li>Snapshot the current world as _pre_load (cheap: only changed files are copied).</li>
	 * <li>Write _pre_load/restore.inprogress.</li>
	 * <li>Wipe the world folder (except session.lock) and rebuild it from the slot.</li>
	 * <li>Delete the marker.</li>
	 * </ol>
	 * If step 3 fails the world is rebuilt from _pre_load and the error rethrown. If
	 * that fails too, the marker stays so the next launch can offer recovery.
	 */
	public void restore(Path worldDir, String slotId) throws IOException {
		synchronized (LOCK) {
			Path slotDir = slotDir(slotId);
			if (!Files.isRegularFile(slotDir.resolve(COMPLETE))) {
				throw new NoSuchFileException(slotDir.toString(), null, "slot is missing or incomplete");
			}
			if (Files.exists(this.restoreMarker())) {
				// A previous restore never finished: _pre_load holds the last good
				// world, so don't overwrite it with whatever is in the world folder now.
				throw new IOException("A previous restore did not finish; restart the game to finish or roll back "
						+ "that restore first (pre-load backup: " + this.preLoad + ").");
			}
			this.capturePreLoad(worldDir);
			Files.writeString(this.restoreMarker(), "world=" + worldDir.getFileName() + "\nslot=" + slotId
					+ "\nstarted=" + Instant.now() + "\n", StandardCharsets.UTF_8);
			this.rebuildOrRollBack(worldDir, slotDir, true);
			Files.deleteIfExists(this.restoreMarker());
			// The previous _pre_load's objects are garbage now.
			this.gcQuietly();
		}
	}

	private void gcQuietly() {
		try {
			this.gc();
		} catch (IOException | RuntimeException e) {
			QuicksaveMod.LOGGER.warn("Garbage collection in {} failed; it will run again after the next save", this.dir, e);
		}
	}

	/** Thrown before the world folder was touched: the slot is broken, the world is fine. */
	static final class VerifyException extends IOException {
		VerifyException(String message) {
			super(message);
		}
	}

	/** Snapshots the world into _pre_load/manifest.json (replacing any previous one). */
	public void capturePreLoad(Path worldDir) throws IOException {
		synchronized (LOCK) {
			Files.createDirectories(this.preLoad);
			Manifest manifest = this.snapshot(worldDir, new int[3], new long[1]);
			Path tmp = this.preLoad.resolve(MANIFEST + ".tmp");
			writeJson(tmp, manifest);
			Files.move(tmp, this.preLoad.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			deleteRecursively(this.preLoad.resolve(LEGACY_WORLD));
			this.saveCache();
		}
	}

	/**
	 * @param worldIntact true when the world folder still holds the pre-load state, so a
	 *                    slot that fails verification needs no rollback at all
	 */
	private void rebuildOrRollBack(Path worldDir, Path sourceDir, boolean worldIntact) throws IOException {
		try {
			this.rebuild(worldDir, sourceDir);
		} catch (VerifyException e) {
			if (worldIntact) {
				Files.deleteIfExists(this.restoreMarker());
				QuicksaveMod.LOGGER.error("Not loading {}: {}. The world was not touched.", sourceDir.getFileName(), e.getMessage());
				throw e;
			}
			this.rollBackAfterFailure(worldDir, sourceDir, e);
			throw e;
		} catch (IOException | RuntimeException e) {
			this.rollBackAfterFailure(worldDir, sourceDir, e);
			throw e;
		}
		this.saveCache();
	}

	private void rollBackAfterFailure(Path worldDir, Path sourceDir, Exception e) {
		QuicksaveMod.LOGGER.error("Restore from {} failed, rolling back from {}", sourceDir, this.preLoad, e);
		try {
			this.rebuild(worldDir, this.preLoad);
			Files.deleteIfExists(this.restoreMarker());
			QuicksaveMod.LOGGER.info("Rollback from pre-load backup succeeded");
		} catch (IOException | RuntimeException rollback) {
			e.addSuppressed(rollback);
			QuicksaveMod.LOGGER.error("Rollback FAILED; the pre-load backup is kept at {}", this.preLoad, rollback);
		}
	}

	/**
	 * Wipes the world folder and rebuilds it from a slot or _pre_load directory
	 * (manifest, or a legacy world/ full copy).
	 */
	void rebuild(Path worldDir, Path sourceDir) throws IOException {
		Path manifestFile = sourceDir.resolve(MANIFEST);
		Path legacy = sourceDir.resolve(LEGACY_WORLD);
		if (!Files.isRegularFile(manifestFile) && !Files.isDirectory(legacy)) {
			throw new VerifyException(sourceDir + " has no manifest and no world copy");
		}
		Manifest manifest = null;
		if (Files.isRegularFile(manifestFile)) {
			manifest = readManifest(manifestFile);
			// Check every object before the world is touched.
			for (Entry entry : manifest.files()) {
				resolveSafely(worldDir, entry.path());
				this.verifyObject(entry);
			}
		} else if (!Files.isRegularFile(legacy.resolve("level.dat"))) {
			throw new VerifyException("legacy copy " + legacy + " has no level.dat");
		}
		Files.createDirectories(worldDir);
		wipeWorld(worldDir);
		if (manifest == null) {
			copyTree(legacy, worldDir);
			this.cache = new HashMap<>();
			return;
		}
		Map<String, CacheEntry> newCache = new HashMap<>();
		long now = System.currentTimeMillis();
		for (Entry entry : manifest.files()) {
			Path target = resolveSafely(worldDir, entry.path());
			Files.createDirectories(target.getParent());
			this.copyObjectVerified(entry, target);
			// Keep the saved mtime so the next save recognises the file as unchanged.
			Files.setLastModifiedTime(target, FileTime.fromMillis(entry.mtime()));
			newCache.put(entry.path(), new CacheEntry(entry.size(), entry.mtime(), entry.hash(), now));
		}
		this.cache = newCache;
	}

	private void verifyObject(Entry entry) throws VerifyException {
		Path object = objectPath(entry.hash());
		Xxh64 hasher = new Xxh64();
		long total = 0;
		try (InputStream in = Files.newInputStream(object)) {
			byte[] buffer = new byte[BUFFER];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				hasher.update(buffer, 0, read);
				total += read;
			}
		} catch (IOException e) {
			throw new VerifyException("object " + entry.hash() + " for " + entry.path() + " is unreadable: " + e);
		}
		if (total != entry.size() || !hasher.hex().equals(entry.hash())) {
			throw new VerifyException("object " + entry.hash() + " for " + entry.path() + " is corrupt");
		}
	}

	/** Real byte copy of an object to {@code target}, checking size and hash on the way. */
	private void copyObjectVerified(Entry entry, Path target) throws IOException {
		Path object = objectPath(entry.hash());
		Xxh64 hasher = new Xxh64();
		long total = 0;
		try (InputStream in = Files.newInputStream(object); OutputStream out = Files.newOutputStream(target)) {
			byte[] buffer = new byte[BUFFER];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				hasher.update(buffer, 0, read);
				out.write(buffer, 0, read);
				total += read;
			}
		}
		if (total != entry.size() || !hasher.hex().equals(entry.hash())) {
			throw new IOException("Object " + entry.hash() + " for " + entry.path() + " is corrupt (size " + total
					+ ", expected " + entry.size() + ")");
		}
	}

	/** Re-runs an interrupted restore of {@code slotId}. _pre_load is left as it is. */
	public void finishInterrupted(Path worldDir, String slotId) throws IOException {
		synchronized (LOCK) {
			if (!this.hasSlot(slotId)) {
				throw new NoSuchFileException("slot '" + slotId + "' is missing; roll back instead");
			}
			this.rebuildOrRollBack(worldDir, slotDir(slotId), false);
			Files.deleteIfExists(this.restoreMarker());
			this.gcQuietly();
		}
	}

	/** Puts the world back to how it was before an interrupted restore started. */
	public void rollBackInterrupted(Path worldDir) throws IOException {
		synchronized (LOCK) {
			boolean hasManifest = Files.isRegularFile(this.preLoad.resolve(MANIFEST));
			boolean hasLegacy = Files.isRegularFile(this.preLoad.resolve(LEGACY_WORLD).resolve("level.dat"));
			if (!hasManifest && !hasLegacy) {
				throw new NoSuchFileException(this.preLoad.toString(), null, "pre-load backup is missing or incomplete");
			}
			this.rebuild(worldDir, this.preLoad);
			this.saveCache();
			Files.deleteIfExists(this.restoreMarker());
			this.gcQuietly();
		}
	}

	// ---------------------------------------------------------------- rotation, gc, pruning

	/** Deletes a slot and the objects only it used. */
	public void delete(String slotId) throws IOException {
		synchronized (LOCK) {
			deleteRecursively(slotDir(slotId));
			this.gc();
		}
	}

	/** Changes a slot's display name (meta.json is rewritten atomically). */
	public void rename(String slotId, String name) throws IOException {
		synchronized (LOCK) {
			Path slotDir = slotDir(slotId);
			if (!Files.isRegularFile(slotDir.resolve(COMPLETE))) {
				throw new NoSuchFileException(slotDir.toString());
			}
			SaveMeta old = readMeta(slotDir);
			SaveMeta renamed = new SaveMeta(name, old.type(), old.createdMs(), old.dayTime(), old.dimension(), old.x(),
					old.y(), old.z(), old.health(), old.playtimeTicks());
			Path tmp = slotDir.resolve(META + ".tmp");
			writeJson(tmp, renamed);
			Files.move(tmp, slotDir.resolve(META), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/** Path of a slot's thumbnail (may not exist). */
	public Path thumbnail(String slotId) {
		return slotDir(slotId).resolve(THUMBNAIL);
	}

	/** Adds a thumbnail to an existing slot (autosaves get theirs after the fact). No-op if the slot is gone. */
	public void writeThumbnail(String slotId, byte[] png) throws IOException {
		synchronized (LOCK) {
			Path slotDir = slotDir(slotId);
			if (!Files.isRegularFile(slotDir.resolve(COMPLETE))) {
				return;
			}
			Path tmp = slotDir.resolve(THUMBNAIL + ".tmp");
			Files.write(tmp, png);
			Files.move(tmp, slotDir.resolve(THUMBNAIL), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/** Deletes the oldest slots of {@code type} beyond the newest {@code keep}. Returns the deleted ids. */
	public List<String> rotate(SlotType type, int keep) throws IOException {
		synchronized (LOCK) {
			List<String> deleted = new ArrayList<>();
			List<SnapshotStore.Slot> ofType = this.list().stream().filter(s -> s.meta().type() == type).toList();
			for (int i = Math.max(0, keep); i < ofType.size(); i++) {
				deleteRecursively(slotDir(ofType.get(i).id()));
				deleted.add(ofType.get(i).id());
			}
			return deleted;
		}
	}

	/** Deletes objects that no manifest (slots or _pre_load) refers to. Returns bytes freed. */
	public long gc() throws IOException {
		synchronized (LOCK) {
			Set<String> live = new HashSet<>();
			List<Path> manifests = new ArrayList<>();
			if (Files.isDirectory(this.saves)) {
				try (var slots = Files.list(this.saves)) {
					slots.map(slot -> slot.resolve(MANIFEST)).filter(Files::isRegularFile).forEach(manifests::add);
				}
			}
			Path preLoadManifest = this.preLoad.resolve(MANIFEST);
			if (Files.isRegularFile(preLoadManifest)) {
				manifests.add(preLoadManifest);
			}
			for (Path manifest : manifests) {
				for (Entry entry : readManifest(manifest).files()) {
					live.add(entry.hash());
				}
			}
			long freed = 0;
			if (!Files.isDirectory(this.objects)) {
				return 0;
			}
			try (var prefixes = Files.list(this.objects)) {
				for (Path prefix : (Iterable<Path>) prefixes::iterator) {
					if (!Files.isDirectory(prefix)) {
						continue;
					}
					if (prefix.getFileName().toString().equals("tmp")) {
						freed += sizeOf(prefix);
						deleteRecursively(prefix);
						continue;
					}
					try (var objectFiles = Files.list(prefix)) {
						for (Path object : (Iterable<Path>) objectFiles::iterator) {
							if (!live.contains(object.getFileName().toString())) {
								freed += Files.size(object);
								Files.delete(object);
							}
						}
					}
				}
			}
			return freed;
		}
	}

	/**
	 * Problems with the store, empty when healthy: every manifest's objects must
	 * exist with the right size, and no object may be unreferenced (after a GC).
	 */
	public List<String> checkConsistency() throws IOException {
		synchronized (LOCK) {
			List<String> problems = new ArrayList<>();
			Set<String> live = new HashSet<>();
			List<Path> manifests = new ArrayList<>();
			if (Files.isDirectory(this.saves)) {
				try (var slots = Files.list(this.saves)) {
					slots.map(slot -> slot.resolve(MANIFEST)).filter(Files::isRegularFile).forEach(manifests::add);
				}
			}
			if (Files.isRegularFile(this.preLoad.resolve(MANIFEST))) {
				manifests.add(this.preLoad.resolve(MANIFEST));
			}
			for (Path manifest : manifests) {
				for (Entry entry : readManifest(manifest).files()) {
					live.add(entry.hash());
					Path object = objectPath(entry.hash());
					if (!Files.isRegularFile(object)) {
						problems.add(manifest.getParent().getFileName() + ": missing object for " + entry.path());
					} else if (Files.size(object) != entry.size()) {
						problems.add(manifest.getParent().getFileName() + ": wrong size object for " + entry.path());
					}
				}
			}
			if (Files.isDirectory(this.objects)) {
				try (var all = Files.walk(this.objects)) {
					all.filter(Files::isRegularFile)
							.filter(f -> !f.getParent().getFileName().toString().equals("tmp"))
							.filter(f -> !live.contains(f.getFileName().toString()))
							.forEach(f -> problems.add("unreferenced object " + f.getFileName()));
				}
			}
			return problems;
		}
	}

	/** Bytes used on disk by this world's saves (objects, legacy copies, metadata). */
	public long totalBytes() throws IOException {
		return Files.isDirectory(this.dir) ? sizeOf(this.dir) : 0L;
	}

	/**
	 * While over {@code maxBytes}, deletes the oldest autosave, then the oldest
	 * quicksave, then garbage-collects. Never deletes manual saves or {@code protect}.
	 * Returns the deleted slot ids.
	 */
	public List<String> prune(long maxBytes, String protect) throws IOException {
		synchronized (LOCK) {
			List<String> deleted = new ArrayList<>();
			while (this.totalBytes() > maxBytes) {
				List<SnapshotStore.Slot> slots = this.list();
				SnapshotStore.Slot victim = oldest(slots, SlotType.AUTO, protect);
				if (victim == null) {
					victim = oldest(slots, SlotType.QUICK, protect);
				}
				if (victim == null) {
					QuicksaveMod.LOGGER.warn("Saves use {} MB, over the {} MB cap, but only manual saves and the newest save are left",
							this.totalBytes() >> 20, maxBytes >> 20);
					break;
				}
				deleteRecursively(slotDir(victim.id()));
				deleted.add(victim.id());
				this.gc();
			}
			return deleted;
		}
	}

	@Nullable
	private static SnapshotStore.Slot oldest(List<SnapshotStore.Slot> newestFirst, SlotType type, String protect) {
		for (int i = newestFirst.size() - 1; i >= 0; i--) {
			SnapshotStore.Slot slot = newestFirst.get(i);
			if (slot.meta().type() == type && !slot.id().equals(protect)) {
				return slot;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- legacy (M1/M2) slots

	/** Converts full-copy slots (saves/&lt;slot&gt;/world/) into manifests. */
	void migrateLegacySlots() throws IOException {
		if (!Files.isDirectory(this.saves)) {
			return;
		}
		List<Path> legacy = new ArrayList<>();
		try (var slots = Files.list(this.saves)) {
			slots.filter(slot -> Files.isRegularFile(slot.resolve(COMPLETE))
							&& Files.isDirectory(slot.resolve(LEGACY_WORLD))
							&& !Files.isRegularFile(slot.resolve(MANIFEST)))
					.forEach(legacy::add);
		}
		for (Path slot : legacy) {
			Path world = slot.resolve(LEGACY_WORLD);
			List<Entry> entries = new ArrayList<>();
			for (Path file : worldFiles(world)) {
				BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
				String hash = this.ingest(file, new long[1]);
				entries.add(new Entry(relative(world, file), attrs.size(), attrs.lastModifiedTime().toMillis(), hash));
			}
			writeJson(slot.resolve(MANIFEST + ".tmp"), new Manifest(1, entries));
			Files.move(slot.resolve(MANIFEST + ".tmp"), slot.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
			deleteRecursively(world);
			QuicksaveMod.LOGGER.info("Converted legacy save {} ({} files) to the object store", slot.getFileName(), entries.size());
		}
	}

	// ---------------------------------------------------------------- hash cache

	private Map<String, CacheEntry> cache() {
		if (this.cache == null) {
			this.cache = new HashMap<>();
			Path file = this.dir.resolve(CACHE);
			if (Files.isRegularFile(file)) {
				try {
					Map<String, CacheEntry> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
							new TypeToken<Map<String, CacheEntry>>() { }.getType());
					if (loaded != null) {
						this.cache.putAll(loaded);
					}
				} catch (IOException | RuntimeException e) {
					QuicksaveMod.LOGGER.warn("Ignoring unreadable {}", file, e);
				}
			}
		}
		return this.cache;
	}

	private void saveCache() throws IOException {
		if (this.cache == null) {
			return;
		}
		Files.createDirectories(this.dir);
		Path tmp = this.dir.resolve(CACHE + ".tmp");
		writeJson(tmp, this.cache);
		Files.move(tmp, this.dir.resolve(CACHE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	// ---------------------------------------------------------------- file helpers

	static Manifest readManifest(Path file) throws IOException {
		Manifest manifest = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Manifest.class);
		if (manifest == null || manifest.files() == null) {
			throw new IOException("Empty or invalid manifest " + file);
		}
		return manifest;
	}

	private static void writeJson(Path file, Object value) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(value), StandardCharsets.UTF_8);
	}

	/** Regular files under the world folder, sorted, without the top-level session.lock. */
	static List<Path> worldFiles(Path worldDir) throws IOException {
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile() && !(file.getParent().equals(worldDir)
						&& file.getFileName().toString().equals(SnapshotStore.SESSION_LOCK))) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		files.sort(Comparator.naturalOrder());
		return files;
	}

	/** World-relative path with '/' separators. */
	static String relative(Path root, Path file) {
		return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
	}

	/** Resolves a manifest path, refusing anything that would land outside the world folder. */
	static Path resolveSafely(Path worldDir, String rel) throws IOException {
		Path target = worldDir.resolve(rel).normalize();
		if (!target.startsWith(worldDir.normalize()) || target.equals(worldDir.normalize())) {
			throw new IOException("Manifest path escapes the world folder: " + rel);
		}
		return target;
	}

	/** Deletes everything inside the world folder except the top-level session.lock. */
	static void wipeWorld(Path worldDir) throws IOException {
		try (var children = Files.list(worldDir)) {
			for (Path child : (Iterable<Path>) children::iterator) {
				if (!child.getFileName().toString().equals(SnapshotStore.SESSION_LOCK)) {
					deleteRecursively(child);
				}
			}
		}
	}

	/** Real byte copy of a directory tree, skipping a top-level session.lock. */
	static void copyTree(Path from, Path to) throws IOException {
		Files.createDirectories(to);
		for (Path file : worldFiles(from)) {
			Path target = to.resolve(relative(from, file));
			Files.createDirectories(target.getParent());
			Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}

	static long sizeOf(Path root) throws IOException {
		long[] total = new long[1];
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				total[0] += attrs.size();
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE; // deleted while walking
			}
		});
		return total[0];
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
			public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(d);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
