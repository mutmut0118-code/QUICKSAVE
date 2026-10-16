package com.muto118.quicksave;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import org.jetbrains.annotations.Nullable;

public class QuicksaveClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// M1 has nothing to register on the client: the death screen swap is a
		// mixin, and /qs save is a server command. Keys and ticks arrive in M2.
	}

	/**
	 * Folder name of the open singleplayer world, or null when there is no
	 * integrated server or Quicksave is inactive (LAN open).
	 */
	@Nullable
	public static String activeWorldFolderName(MinecraftClient client) {
		IntegratedServer server = client.getServer();
		if (server == null || !QuicksaveMod.isActive(server)) {
			return null;
		}
		return QuicksaveMod.worldFolderName(server);
	}
}
