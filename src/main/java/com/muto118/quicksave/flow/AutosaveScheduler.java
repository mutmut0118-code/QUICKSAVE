package com.muto118.quicksave.flow;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.config.QuicksaveConfig;
import com.muto118.quicksave.guard.SaveGuard;
import com.muto118.quicksave.snapshot.SaveService;
import com.muto118.quicksave.snapshot.SlotType;
import com.muto118.quicksave.snapshot.SnapshotStore;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Autosaves (server thread): every {@code autosaveIntervalMinutes} of unpaused play,
 * shortly after a dimension change, after sleeping through the night, on quit
 * ({@code ServerPlayNetworkHandlerMixin}) and a "Start" save on the first join
 * of a world that has no saves yet.
 *
 * <p>A save refused for a passing reason (combat, falling, lava) stays pending
 * and is retried every 2 s. Any successful save resets the interval timer.
 */
public final class AutosaveScheduler {
	/** Refusals worth waiting out; anything else drops the pending autosave. */
	private static final Set<SaveGuard.Refusal> RETRY = EnumSet.of(SaveGuard.Refusal.COMBAT,
			SaveGuard.Refusal.FALLING, SaveGuard.Refusal.IN_LAVA, SaveGuard.Refusal.NO_PLAYER);
	private static final int RETRY_TICKS = 40;
	private static final int START_DELAY_TICKS = 100;
	/**
	 * Long enough for the new dimension's spawn chunks to finish generating: the
	 * flush waits for in-flight chunk work, which made a 3 s delay hitch for 6 s.
	 */
	private static final int DIMENSION_DELAY_TICKS = 200;
	private static final int SLEEP_DELAY_TICKS = 20;

	public enum Reason {
		START("Start"),
		TIMER("Autosave"),
		DIMENSION("Autosave (new dimension)"),
		SLEEP("Autosave (slept)"),
		QUIT("Autosave (quit)");

		public final String label;

		Reason(String label) {
			this.label = label;
		}
	}

	private record Pending(Reason reason, int dueTick) {
	}

	private static int ticksSinceSave;
	@Nullable
	private static Pending pending;
	/** Set by the client right before it closes the world to load a save. */
	private static volatile boolean suppressQuitAutosave;
	/** Self-test hook: interval in ticks instead of the configured minutes (0 = use config). */
	public static volatile int testIntervalTicks;

	private AutosaveScheduler() {
	}

	public static void register() {
		SaveService.addListener((type, result) -> {
			if (result.success()) {
				ticksSinceSave = 0;
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(AutosaveScheduler::tick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (QuicksaveMod.isActive(server) && !SnapshotStore.hasSave(QuicksaveMod.worldFolderName(server))) {
				schedule(server, Reason.START, START_DELAY_TICKS);
			}
		});

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			if (QuicksaveConfig.get().autosaveOnDimensionChange) {
				schedule(destination.getServer(), Reason.DIMENSION, DIMENSION_DELAY_TICKS);
			}
		});

		EntitySleepEvents.STOP_SLEEPING.register((entity, sleepingPos) -> {
			if (entity instanceof ServerPlayerEntity player && QuicksaveConfig.get().autosaveOnSleep
					&& isMorning(player.getServerWorld())) {
				schedule(player.getServer(), Reason.SLEEP, SLEEP_DELAY_TICKS);
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> reset());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
	}

	private static void reset() {
		ticksSinceSave = 0;
		pending = null;
		suppressQuitAutosave = false;
	}

	/** Woke up because the night was skipped (time of day just reset to morning). */
	private static boolean isMorning(ServerWorld world) {
		long time = world.getTimeOfDay() % 24000L;
		return time < 1000L;
	}

	private static void schedule(MinecraftServer server, Reason reason, int delayTicks) {
		int due = server.getTicks() + delayTicks;
		if (pending == null || reason == Reason.START || due < pending.dueTick()) {
			pending = new Pending(reason, due);
		} else {
			pending = new Pending(reason, pending.dueTick());
		}
	}

	private static void tick(MinecraftServer server) {
		if (!QuicksaveMod.isActive(server)) {
			return;
		}
		ticksSinceSave++;
		int interval = testIntervalTicks > 0 ? testIntervalTicks : QuicksaveConfig.get().autosaveIntervalMinutes * 1200;
		if (interval > 0 && ticksSinceSave >= interval && pending == null) {
			schedule(server, Reason.TIMER, 0);
		}
		Pending due = pending;
		if (due == null || server.getTicks() < due.dueTick()) {
			return;
		}
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		SaveService.Result result = SaveService.trySave(server, players.isEmpty() ? null : players.get(0), SlotType.AUTO,
				due.reason().label);
		if (result.refusal() != null && RETRY.contains(result.refusal())) {
			pending = new Pending(due.reason(), server.getTicks() + RETRY_TICKS);
		} else {
			pending = null;
			if (!result.success()) {
				ticksSinceSave = 0; // dead, LAN-open or failed: don't hammer every tick
			}
		}
	}

	/** Called by the client before closing the world for a load: that quit must not autosave. */
	public static void suppressNextQuitAutosave() {
		suppressQuitAutosave = true;
	}

	/** {@code ServerPlayNetworkHandlerMixin}: the player is leaving while still in the world. */
	public static void onPlayerQuit(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null || !QuicksaveMod.isActive(server) || !QuicksaveConfig.get().autosaveOnQuit) {
			return;
		}
		if (suppressQuitAutosave) {
			suppressQuitAutosave = false;
			return;
		}
		if (!server.isOnThread()) {
			QuicksaveMod.LOGGER.warn("Skipping the quit autosave: disconnect handled off the server thread");
			return;
		}
		pending = null;
		SaveService.trySave(server, player, SlotType.AUTO, Reason.QUIT.label);
	}
}
