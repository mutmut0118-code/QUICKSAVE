package com.muto118.quicksave;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.muto118.quicksave.snapshot.SnapshotStore;
import com.muto118.quicksave.snapshot.WorldFlusher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class QuicksaveMod implements ModInitializer {
	public static final String MOD_ID = "quicksave";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (server.isDedicated()) {
				LOGGER.info("Quicksave is singleplayer-only and is inactive on this dedicated server");
			}
		});

		// Death is only logged here. It never triggers a save: the client side
		// decides whether to reload (see MinecraftClientMixin).
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayerEntity player && !player.getServer().isDedicated()) {
				LOGGER.info("Player {} died ({})", player.getName().getString(), damageSource.getName());
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			if (!environment.integrated) {
				return;
			}
			dispatcher.register(CommandManager.literal("qs")
					.then(CommandManager.literal("save").executes(QuicksaveMod::runSave)));
		});
	}

	/** Folder name of the world under saves/, e.g. "New World". */
	public static String worldFolderName(MinecraftServer server) {
		return worldDir(server).getFileName().toString();
	}

	/** Absolute, normalized saves/&lt;world&gt; path. */
	public static Path worldDir(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
	}

	/** Singleplayer and not opened to LAN. */
	public static boolean isActive(MinecraftServer server) {
		return !server.isDedicated() && !server.isRemote();
	}

	private static int runSave(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		MinecraftServer server = source.getServer();
		if (!isActive(server)) {
			source.sendError(Text.translatable("quicksave.command.inactive"));
			return 0;
		}
		String worldName = worldFolderName(server);
		Path worldDir = worldDir(server);
		long start = System.nanoTime();
		try {
			WorldFlusher.runFlushed(server, () -> SnapshotStore.save(worldDir, worldName, SnapshotStore.M1_SLOT));
		} catch (Exception e) {
			LOGGER.error("Quicksave of '{}' failed", worldName, e);
			source.sendError(Text.translatable("quicksave.command.save.failed", String.valueOf(e.getMessage())));
			return 0;
		}
		long ms = (System.nanoTime() - start) / 1_000_000L;
		LOGGER.info("Saved '{}' to slot '{}' in {} ms", worldName, SnapshotStore.M1_SLOT, ms);
		source.sendFeedback(() -> Text.translatable("quicksave.command.save.done", ms), false);
		return Command.SINGLE_SUCCESS;
	}
}
