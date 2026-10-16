package com.muto118.quicksave.flow;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.guard.SaveGuard;
import com.muto118.quicksave.snapshot.SaveService;
import com.muto118.quicksave.snapshot.SlotType;
import com.muto118.quicksave.ui.SaveToast;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Client side of a save: ask the integrated server whether saving is allowed,
 * show "Quicksaving..." and give it a few ticks on screen, then run the save
 * on the server thread and report the result as a toast.
 *
 * <p>The short delay is deliberate: the copy blocks the server thread, and the
 * toast going up first makes that hitch read as "the game is saving".
 */
public final class SaveRequests {
	/** Client ticks between showing the toast and starting the copy. */
	private static final int TOAST_LEAD_TICKS = 4;

	private record Pending(SlotType type, @Nullable String name, UUID player) {
	}

	private static boolean busy;
	@Nullable
	private static Pending pending;
	private static int delay;
	@Nullable
	private static SaveService.Result lastResult;

	private SaveRequests() {
	}

	public static boolean isBusy() {
		return busy;
	}

	/** Result of the most recent finished request (refusals included). */
	@Nullable
	public static SaveService.Result lastResult() {
		return lastResult;
	}

	/** Render thread. */
	public static void request(MinecraftClient client, SlotType type, @Nullable String name) {
		IntegratedServer server = client.getServer();
		if (server == null || client.player == null) {
			return;
		}
		if (!QuicksaveMod.isActive(server)) {
			finish(client, type, new SaveService.Result(SaveGuard.Refusal.INACTIVE, null, null, 0L));
			return;
		}
		if (busy || ReloadController.state() != ReloadController.State.IDLE) {
			SaveToast.refused(client, SaveGuard.Refusal.BUSY);
			return;
		}
		busy = true;
		UUID playerId = client.player.getUuid();
		server.execute(() -> {
			SaveGuard.Refusal refusal = SaveGuard.check(server, server.getPlayerManager().getPlayer(playerId));
			client.execute(() -> {
				if (refusal != null) {
					finish(client, type, new SaveService.Result(refusal, null, null, 0L));
					return;
				}
				SaveToast.saving(client, type);
				pending = new Pending(type, name, playerId);
				delay = TOAST_LEAD_TICKS;
			});
		});
	}

	/** END_CLIENT_TICK. */
	public static void tick(MinecraftClient client) {
		if (pending == null || --delay > 0) {
			return;
		}
		Pending request = pending;
		pending = null;
		IntegratedServer server = client.getServer();
		if (server == null) {
			busy = false;
			return;
		}
		server.execute(() -> {
			SaveService.Result result = SaveService.trySave(server, server.getPlayerManager().getPlayer(request.player()),
					request.type(), request.name());
			client.execute(() -> finish(client, request.type(), result));
		});
	}

	private static void finish(MinecraftClient client, SlotType type, SaveService.Result result) {
		busy = false;
		lastResult = result;
		SaveToast.result(client, type, result);
	}

	/** Drops a queued save, e.g. when the world closes before it ran. */
	public static void reset() {
		pending = null;
		busy = false;
	}
}
