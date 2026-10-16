package com.muto118.quicksave.flow;

import com.muto118.quicksave.QuicksaveClient;
import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.snapshot.SnapshotStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * Close -> pre-load backup -> restore -> reopen.
 *
 * <p>Runs on the render thread except for the file work, which runs on a
 * dedicated worker thread while a {@link MessageScreen} is shown. Only one
 * reload can be in flight at a time.
 */
public final class ReloadController {
	public enum State {
		IDLE,
		/** Disconnecting; the integrated server is shutting down. */
		CLOSING,
		/** Waiting for session.lock, writing the pre-load backup, copying the slot back. */
		RESTORING,
		/** IntegratedServerLoader is opening the world again. */
		REOPENING
	}

	private static final long LOCK_TIMEOUT_MS = 30_000L;
	private static final long LOCK_POLL_MS = 100L;

	private static volatile State state = State.IDLE;

	private ReloadController() {
	}

	public static State state() {
		return state;
	}

	/** Reloads the open world from M1's single slot. Must be called on the render thread. */
	public static void loadLastSave(MinecraftClient client) {
		load(client, SnapshotStore.M1_SLOT);
	}

	public static void load(MinecraftClient client, String slot) {
		if (state != State.IDLE) {
			QuicksaveMod.LOGGER.warn("Ignoring load request: reload already in progress ({})", state);
			return;
		}
		IntegratedServer server = client.getServer();
		String worldName = QuicksaveClient.activeWorldFolderName(client);
		if (server == null || worldName == null) {
			QuicksaveMod.LOGGER.warn("Ignoring load request: no active singleplayer world");
			return;
		}
		if (!SnapshotStore.hasSave(worldName)) {
			QuicksaveMod.LOGGER.warn("Ignoring load request: '{}' has no save in slot '{}'", worldName, slot);
			return;
		}
		Path worldDir = QuicksaveMod.worldDir(server);
		QuicksaveMod.LOGGER.info("Loading slot '{}' of '{}'", slot, worldName);

		// 1. Close the world exactly like the pause menu's "Save and Quit to Title".
		//    MinecraftClient#disconnect blocks (rendering the screen) until the
		//    integrated server thread has stopped and closed its session.
		state = State.CLOSING;
		try {
			if (client.world != null) {
				client.world.disconnect();
			}
			client.disconnect(new MessageScreen(Text.translatable("quicksave.reload.closing")));
		} catch (RuntimeException e) {
			state = State.IDLE;
			QuicksaveMod.LOGGER.error("Closing the world failed", e);
			fail(client, e);
			return;
		}

		// 2. File work off the render thread.
		state = State.RESTORING;
		client.setScreen(new MessageScreen(Text.translatable("quicksave.reload.restoring")));
		Thread worker = new Thread(() -> {
			Exception error = null;
			try {
				waitForSessionLock(worldDir);
				SnapshotStore.restore(worldDir, worldName, slot);
			} catch (Exception e) {
				error = e;
			}
			Exception result = error;
			client.execute(() -> {
				if (result != null) {
					state = State.IDLE;
					QuicksaveMod.LOGGER.error("Restoring slot '{}' of '{}' failed", slot, worldName, result);
					fail(client, result);
				} else {
					reopen(client, worldName);
				}
			});
		}, "Quicksave restore");
		worker.setDaemon(false);
		worker.start();
	}

	private static void reopen(MinecraftClient client, String worldName) {
		// 3. Reopen through the same path the world list uses.
		state = State.REOPENING;
		QuicksaveMod.LOGGER.info("Restore complete, reopening '{}'", worldName);
		try {
			client.createIntegratedServerLoader().start(worldName, () -> client.setScreen(new TitleScreen()));
		} finally {
			state = State.IDLE;
		}
	}

	private static void waitForSessionLock(Path worldDir) throws Exception {
		long deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS;
		while (SnapshotStore.isSessionLocked(worldDir)) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("session.lock was not released within " + LOCK_TIMEOUT_MS / 1000 + " s");
			}
			Thread.sleep(LOCK_POLL_MS);
		}
	}

	private static void fail(MinecraftClient client, Exception e) {
		client.setScreen(new NoticeScreen(
				() -> client.setScreen(new TitleScreen()),
				Text.translatable("quicksave.reload.failed.title"),
				Text.translatable("quicksave.reload.failed.message", String.valueOf(e.getMessage()))));
	}
}
