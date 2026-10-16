package com.muto118.quicksave.flow;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.snapshot.SnapshotStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * On the first title screen after launch: if a restore was cut off (crash or
 * power loss mid-load), ask whether to finish it or roll the world back to the
 * _pre_load copy.
 */
public final class RecoveryPrompt {
	private static boolean checked;
	/** Worlds whose recovery failed this session; not asked about again until restart. */
	private static final Set<String> GAVE_UP = new HashSet<>();

	private RecoveryPrompt() {
	}

	/** Called for every title screen; only the first one after launch checks. */
	public static void onTitleScreen(MinecraftClient client, Screen titleScreen) {
		if (checked) {
			return;
		}
		checked = true;
		client.send(() -> showIfNeeded(client, titleScreen));
	}

	/** Returns true if a prompt was shown. */
	public static boolean showIfNeeded(MinecraftClient client, Screen returnTo) {
		List<SnapshotStore.InterruptedRestore> interrupted = SnapshotStore.findInterrupted().stream()
				.filter(r -> !GAVE_UP.contains(r.worldFolderName()))
				.toList();
		if (interrupted.isEmpty()) {
			return false;
		}
		SnapshotStore.InterruptedRestore restore = interrupted.get(0);
		QuicksaveMod.LOGGER.warn("Found an unfinished restore of '{}' (slot {})", restore.worldFolderName(), restore.slot());
		client.setScreen(new ConfirmScreen(
				finish -> resolve(client, returnTo, restore, finish),
				Text.translatable("quicksave.recovery.title"),
				Text.translatable("quicksave.recovery.message", restore.worldFolderName()),
				Text.translatable("quicksave.recovery.finish"),
				Text.translatable("quicksave.recovery.rollback")));
		return true;
	}

	private static void resolve(MinecraftClient client, Screen returnTo, SnapshotStore.InterruptedRestore restore,
			boolean finish) {
		client.setScreenAndRender(new MessageScreen(Text.translatable("quicksave.recovery.working")));
		Path worldDir = client.getLevelStorage().getSavesDirectory().resolve(restore.worldFolderName());
		Text result;
		try {
			if (finish) {
				SnapshotStore.finishInterrupted(worldDir, restore);
			} else {
				SnapshotStore.rollBackInterrupted(worldDir, restore);
			}
			QuicksaveMod.LOGGER.info("{} the unfinished restore of '{}'", finish ? "Finished" : "Rolled back",
					restore.worldFolderName());
			result = Text.translatable(finish ? "quicksave.recovery.finished" : "quicksave.recovery.rolled_back",
					restore.worldFolderName());
		} catch (Exception e) {
			QuicksaveMod.LOGGER.error("Recovering '{}' failed", restore.worldFolderName(), e);
			GAVE_UP.add(restore.worldFolderName());
			result = Text.translatable("quicksave.recovery.failed", String.valueOf(e.getMessage()));
		}
		client.setScreen(new NoticeScreen(() -> {
			if (!showIfNeeded(client, returnTo)) {
				client.setScreen(returnTo);
			}
		}, Text.translatable("quicksave.recovery.title"), result, ScreenTexts.OK, true));
	}
}
