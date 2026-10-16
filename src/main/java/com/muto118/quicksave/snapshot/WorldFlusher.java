package com.muto118.quicksave.snapshot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings the on-disk world to a consistent state on the server thread.
 *
 * <p>Sequence: turn saving off on every {@link ServerWorld} (the /save-off switch),
 * {@code saveAll(suppressLogs=false, flush=true, force=true)} (force overrides
 * savingDisabled for this one save), run the copy, then restore each world's
 * previous savingDisabled value (so a player's own /save-off is respected).
 *
 * <p>M1 runs the copy on the server thread inside {@link #runFlushed}, so the
 * game cannot write to disk during the copy at all. See CLAUDE.md
 * ("savingDisabled and chunk unloads") for why moving the copy off-thread needs
 * more than savingDisabled.
 */
public final class WorldFlusher {
	private WorldFlusher() {
	}

	@FunctionalInterface
	public interface IoAction {
		void run() throws java.io.IOException;
	}

	/** Must be called on the server thread. */
	public static void runFlushed(MinecraftServer server, IoAction action) throws java.io.IOException {
		if (!server.isOnThread()) {
			throw new IllegalStateException("WorldFlusher must run on the server thread");
		}
		List<ServerWorld> worlds = new ArrayList<>();
		List<Boolean> previous = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			worlds.add(world);
			previous.add(world.savingDisabled);
			world.savingDisabled = true;
		}
		try {
			server.saveAll(false, true, true);
			action.run();
		} finally {
			for (int i = 0; i < worlds.size(); i++) {
				worlds.get(i).savingDisabled = previous.get(i);
			}
		}
	}
}
