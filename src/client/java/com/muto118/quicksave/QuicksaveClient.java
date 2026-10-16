package com.muto118.quicksave;

import com.muto118.quicksave.config.QuicksaveConfig;
import com.muto118.quicksave.flow.RecoveryPrompt;
import com.muto118.quicksave.flow.ReloadController;
import com.muto118.quicksave.flow.SaveRequests;
import com.muto118.quicksave.snapshot.SaveService;
import com.muto118.quicksave.snapshot.SlotType;
import com.muto118.quicksave.snapshot.SnapshotStore;
import com.muto118.quicksave.ui.LoadMenuScreen;
import com.muto118.quicksave.ui.PauseMenuButtons;
import com.muto118.quicksave.ui.Thumbnails;
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
	/** Unbound by default. */
	public static final KeyBinding LOAD_MENU_KEY = new KeyBinding("key.quicksave.load_menu", InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY);

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(QUICKSAVE_KEY);
		KeyBindingHelper.registerKeyBinding(QUICKLOAD_KEY);
		KeyBindingHelper.registerKeyBinding(LOAD_MENU_KEY);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (QUICKSAVE_KEY.wasPressed()) {
				onQuicksaveKey(client);
			}
			while (QUICKLOAD_KEY.wasPressed()) {
				onQuickloadKey(client);
			}
			while (LOAD_MENU_KEY.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					LoadMenuScreen.open(client, null, false);
				}
			}
			SaveRequests.tick(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SaveRequests.reset();
			Thumbnails.reset();
		});

		// Autosaves run on the integrated server by themselves: toast, and give the slot a thumbnail.
		SaveService.addListener((type, result) -> {
			if (type == SlotType.AUTO && result.success() && result.outcome() != null) {
				MinecraftClient client = MinecraftClient.getInstance();
				String world = result.outcome().world();
				String slot = result.outcome().slot();
				client.execute(() -> {
					SaveToast.show(client, Text.translatable("quicksave.toast.autosaved"),
							Text.translatable("quicksave.toast.took", result.millis()));
					// The recent capture right away (the quit autosave lands while the world is
					// closing and no frame will come), replaced by a fresh one if a frame does.
					byte[] recent = Thumbnails.recent();
					if (recent != null) {
						writeThumbnail(world, slot, recent);
					}
					if (client.world != null) {
						Thumbnails.request(png -> writeThumbnail(world, slot, png));
					}
				});
			}
		});

		// Keep a fresh capture around for the quit autosave (Save and Quit is on the pause menu).
		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen && client.world != null) {
				Thumbnails.request(png -> { });
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen gameMenu) {
				PauseMenuButtons.addTo(client, gameMenu);
			} else if (screen instanceof TitleScreen) {
				RecoveryPrompt.onTitleScreen(client, screen);
			}
		});
	}

	private static void writeThumbnail(String world, String slot, byte[] png) {
		try {
			SnapshotStore.store(world).writeThumbnail(slot, png);
		} catch (Exception e) {
			QuicksaveMod.LOGGER.warn("Could not write the thumbnail of {}", slot, e);
		}
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
