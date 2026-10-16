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

/** Server-thread entry point for every save (keys, pause menu, /qs save). */
public final class SaveService {
	private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private SaveService() {
	}

	/**
	 * @param refusal  set when the guard refused the save
	 * @param error    set when the copy failed
	 * @param slot     slot id written on success
	 */
	public record Result(@Nullable SaveGuard.Refusal refusal, @Nullable Exception error, @Nullable String slot, long millis) {
		public boolean success() {
			return this.refusal == null && this.error == null;
		}
	}

	/** Must run on the server thread. Re-checks the guard, then flushes and copies. */
	public static Result trySave(MinecraftServer server, @Nullable ServerPlayerEntity player, SlotType type,
			@Nullable String name) {
		SaveGuard.Refusal refusal = SaveGuard.check(server, player);
		if (refusal != null) {
			return new Result(refusal, null, null, 0L);
		}
		String world = QuicksaveMod.worldFolderName(server);
		Path worldDir = QuicksaveMod.worldDir(server);
		String slot = SnapshotStore.newSlotId(type);
		SaveMeta meta = describe(server, player, type, name);
		long start = System.nanoTime();
		try {
			WorldFlusher.runFlushed(server, () -> SnapshotStore.save(worldDir, world, slot, meta));
		} catch (Exception e) {
			QuicksaveMod.LOGGER.error("Saving '{}' to slot '{}' failed", world, slot, e);
			return new Result(null, e, null, 0L);
		}
		long ms = (System.nanoTime() - start) / 1_000_000L;
		QuicksaveMod.LOGGER.info("Saved '{}' to slot '{}' in {} ms", world, slot, ms);
		return new Result(null, null, slot, ms);
	}

	private static SaveMeta describe(MinecraftServer server, ServerPlayerEntity player, SlotType type,
			@Nullable String name) {
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
