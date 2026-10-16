package com.muto118.quicksave;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.muto118.quicksave.config.QuicksaveConfig;
import com.muto118.quicksave.guard.SaveGuard;
import com.muto118.quicksave.snapshot.SaveService;
import com.muto118.quicksave.snapshot.SlotType;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class QuicksaveMod implements ModInitializer {
	public static final String MOD_ID = "quicksave";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		QuicksaveConfig.load();
		SaveGuard.register();

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
			// /qs save          -> quicksave slot (same as F6)
			// /qs save <name>   -> named manual save
			dispatcher.register(CommandManager.literal("qs")
					.then(CommandManager.literal("save")
							.executes(ctx -> runSave(ctx.getSource(), SlotType.QUICK, null))
							.then(CommandManager.argument("name", StringArgumentType.greedyString())
									.executes(ctx -> runSave(ctx.getSource(), SlotType.MANUAL,
											StringArgumentType.getString(ctx, "name"))))));
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

	private static int runSave(ServerCommandSource source, SlotType type, @Nullable String name) {
		SaveService.Result result = SaveService.trySave(source.getServer(), source.getPlayer(), type, name);
		if (result.refusal() != null) {
			source.sendError(Text.translatable("quicksave.refused." + result.refusal().key));
			return 0;
		}
		if (result.error() != null) {
			source.sendError(Text.translatable("quicksave.save.failed", String.valueOf(result.error().getMessage())));
			return 0;
		}
		source.sendFeedback(() -> Text.translatable("quicksave.command.save.done", result.millis()), false);
		return Command.SINGLE_SUCCESS;
	}
}
