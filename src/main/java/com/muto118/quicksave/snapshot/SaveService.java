package com.muto118.quicksave.snapshot;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.guard.SaveGuard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-thread entry point for every save (keys, pause menu, autosaves, /qs save). */
public final class SaveService {
	private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/** Called on the server thread after every save attempt, refusals included. */
	@FunctionalInterface
	public interface Listener {
		void onSave(SlotType type, Result result);
	}

	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private SaveService() {
	}

	public static void addListener(Listener listener) {
		LISTENERS.add(listener);
	}

	/**
	 * @param refusal set when the guard refused the save
	 * @param error   set when the copy failed
	 * @param outcome set on success: slot id, stats, rotated/pruned slots
	 * @param millis  time the server thread was blocked by the save itself
	 */
	public record Result(@Nullable SaveGuard.Refusal refusal, @Nullable Exception error,
			@Nullable SnapshotStore.SaveOutcome outcome, long millis) {
		public boolean success() {
			return this.refusal == null && this.error == null;
		}

		@Nullable
		public String slot() {
			return this.outcome == null ? null : this.outcome.slot();
		}
	}

	/** Must run on the server thread. Checks the guard, then flushes and snapshots. */
	public static Result trySave(MinecraftServer server, @Nullable ServerPlayerEntity player, SlotType type,
			@Nullable String name) {
		Result result = doSave(server, player, type, name);
		for (Listener listener : LISTENERS) {
			try {
				listener.onSave(type, result);
			} catch (RuntimeException e) {
				QuicksaveMod.LOGGER.error("Save listener failed", e);
			}
		}
		return result;
	}

	private static Result doSave(MinecraftServer server, @Nullable ServerPlayerEntity player, SlotType type,
			@Nullable String name) {
		SaveGuard.Refusal refusal = SaveGuard.check(server, player);
		if (refusal != null) {
			return new Result(refusal, null, null, 0L);
		}
		String world = QuicksaveMod.worldFolderName(server);
		Path worldDir = QuicksaveMod.worldDir(server);
		SaveMeta meta = describe(player, type, name);
		long start = System.nanoTime();
		SnapshotStore.SaveOutcome[] saved = new SnapshotStore.SaveOutcome[1];
		try {
			WorldFlusher.runFlushed(server, () -> saved[0] = SnapshotStore.save(worldDir, world, meta));
		} catch (Exception e) {
			QuicksaveMod.LOGGER.error("Saving '{}' ({}) failed", world, type, e);
			return new Result(null, e, null, 0L);
		}
		long ms = (System.nanoTime() - start) / 1_000_000L;
		SnapshotStore.SaveOutcome outcome = saved[0];
		try {
			outcome = SnapshotStore.afterSave(world, outcome, type);
		} catch (Exception e) {
			// The save itself is complete; cleanup can run again next time.
			QuicksaveMod.LOGGER.warn("Rotation/cleanup after saving '{}' failed", world, e);
		}
		WorldStore.SaveStats stats = outcome.stats();
		QuicksaveMod.LOGGER.info("Saved '{}' to {} in {} ms: {} files, {} read, {} new objects ({} KB){}{}",
				world, outcome.slot(), ms, stats.files(), stats.hashed(), stats.newObjects(), stats.newBytes() >> 10,
				outcome.rotatedOut().isEmpty() ? "" : ", rotated out " + outcome.rotatedOut(),
				outcome.pruned().isEmpty() ? "" : ", pruned " + outcome.pruned());
		return new Result(null, null, outcome, ms);
	}

	private static SaveMeta describe(ServerPlayerEntity player, SlotType type, @Nullable String name) {
		String label = name != null && !name.isBlank() ? name.trim() : switch (type) {
			case QUICK -> "Quicksave";
			case AUTO -> "Autosave";
			case MANUAL -> "Save " + LocalDateTime.now().format(LABEL_TIME);
		};
		return new SaveMeta(
				label,
				type,
				System.currentTimeMillis(),
				player.getServerWorld().getTimeOfDay(),
				player.getServerWorld().getRegistryKey().getValue().toString(),
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getHealth(),
				player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME)));
	}
}
