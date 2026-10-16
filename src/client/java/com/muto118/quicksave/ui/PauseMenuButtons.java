package com.muto118.quicksave.ui;

import com.muto118.quicksave.QuicksaveClient;
import com.muto118.quicksave.flow.ReloadController;
import com.muto118.quicksave.snapshot.SnapshotStore;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.List;

/**
 * Adds Save and Load above "Save and Quit to Title" on the pause menu.
 * Uses Fabric's ScreenEvents.AFTER_INIT instead of a GameMenuScreen mixin: the
 * exit button and everything below it move down one row.
 */
public final class PauseMenuButtons {
	private static final int ROW = 24;

	private PauseMenuButtons() {
	}

	public static void addTo(MinecraftClient client, GameMenuScreen screen) {
		if (client.getServer() == null || client.player == null) {
			return;
		}
		List<ClickableWidget> buttons = Screens.getButtons(screen);
		ClickableWidget exit = buttons.stream()
				.filter(b -> b.getMessage().getContent() instanceof TranslatableTextContent t && t.getKey().equals("menu.returnToMenu"))
				.findFirst().orElse(null);
		if (exit == null) {
			return; // F3+Esc pause (no menu) or a layout another mod replaced
		}
		int rowY = exit.getY();
		for (ClickableWidget button : buttons) {
			if (button.getY() >= rowY) {
				button.setY(button.getY() + ROW);
			}
		}

		String world = QuicksaveClient.activeWorldFolderName(client);
		boolean active = world != null;
		int half = (exit.getWidth() - 4) / 2;

		ButtonWidget save = ButtonWidget.builder(Text.translatable("quicksave.menu.save"),
						button -> client.setScreen(new SaveNameScreen(screen)))
				.dimensions(exit.getX(), rowY, half, 20).build();
		ButtonWidget load = ButtonWidget.builder(Text.translatable("quicksave.menu.load"),
						button -> client.setScreen(new ConfirmScreen(confirmed -> {
							if (!confirmed || !ReloadController.loadLatest(client, ReloadController.ANY)) {
								client.setScreen(screen);
							}
						}, Text.translatable("quicksave.confirm_load.title"), Text.translatable("quicksave.confirm_load.message"))))
				.dimensions(exit.getX() + exit.getWidth() - half, rowY, half, 20).build();

		save.active = active;
		load.active = active && SnapshotStore.hasSave(world);
		if (!active) {
			Tooltip lan = Tooltip.of(Text.translatable("quicksave.refused.inactive"));
			save.setTooltip(lan);
			load.setTooltip(lan);
		} else if (!load.active) {
			load.setTooltip(Tooltip.of(Text.translatable("quicksave.toast.nothing_to_load")));
		}
		buttons.add(save);
		buttons.add(load);
	}
}
