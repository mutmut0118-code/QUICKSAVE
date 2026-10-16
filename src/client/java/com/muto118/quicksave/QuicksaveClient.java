package com.muto118.quicksave;

import com.muto118.quicksave.config.QuicksaveConfig;
import com.muto118.quicksave.flow.RecoveryPrompt;
import com.muto118.quicksave.flow.ReloadController;
import com.muto118.quicksave.flow.SaveRequests;
import com.muto118.quicksave.snapshot.SlotType;
import com.muto118.quicksave.ui.PauseMenuButtons;
import com.muto118.quicksave.ui.SaveToast;
import com.muto118.quicksave.guard.SaveGuard;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public class QuicksaveClient implements ClientModInitializer {
	public static final String KEY_CATEGORY = "key.categories.quicksave";

	/** F6: F5 is vanilla's perspective key. */
	public static final KeyBinding QUICKSAVE_KEY = new KeyBinding("key.quicksave.quicksave", InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_F6, KEY_CATEGORY);
	public static final KeyBinding QUICKLOAD_KEY = new KeyBinding("key.quicksave.quickload", InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_F9, KEY_CATEGORY);

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(QUICKSAVE_KEY);
		KeyBindingHelper.registerKeyBinding(QUICKLOAD_KEY);
		// The "open load menu" key (unbound by default) arrives with the load menu in M4.

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (QUICKSAVE_KEY.wasPressed()) {
				onQuicksaveKey(client);
			}
			while (QUICKLOAD_KEY.wasPressed()) {
				onQuickloadKey(client);
			}
			SaveRequests.tick(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SaveRequests.reset());

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen gameMenu) {
				PauseMenuButtons.addTo(client, gameMenu);
			} else if (screen instanceof TitleScreen) {
				RecoveryPrompt.onTitleScreen(client, screen);
			}
		});
	}

	public static void onQuicksaveKey(MinecraftClient client) {
		if (client.player == null || client.getServer() == null) {
			return;
		}
		SaveRequests.request(client, SlotType.QUICK, null);
	}

	public static void onQuickloadKey(MinecraftClient client) {
		if (client.player == null || client.getServer() == null) {
			return;
		}
		if (activeWorldFolderName(client) == null) {
			SaveToast.refused(client, SaveGuard.Refusal.INACTIVE);
			return;
		}
		if (!QuicksaveConfig.get().confirmQuickload) {
			ReloadController.loadLatest(client, ReloadController.QUICK_OR_AUTO);
			return;
		}
		client.setScreen(new ConfirmScreen(confirmed -> {
			client.setScreen(null);
			if (confirmed) {
				ReloadController.loadLatest(client, ReloadController.QUICK_OR_AUTO);
			}
		}, Text.translatable("quicksave.confirm_quickload.title"), Text.translatable("quicksave.confirm_load.message")));
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
