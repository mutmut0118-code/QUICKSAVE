package com.muto118.quicksave.ui;

import com.muto118.quicksave.guard.SaveGuard;
import com.muto118.quicksave.snapshot.SaveService;
import com.muto118.quicksave.snapshot.SlotType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Corner toasts for saving. All share one toast type, so "Quicksaving..." is
 * replaced in place by "Quicksaved" or a refusal. M5 reskins this.
 */
public final class SaveToast {
	private static final SystemToast.Type TYPE = new SystemToast.Type(3000L);

	private SaveToast() {
	}

	public static void saving(MinecraftClient client, SlotType type) {
		show(client, Text.translatable(type == SlotType.QUICK ? "quicksave.toast.quicksaving" : "quicksave.toast.saving"), null);
	}

	public static void result(MinecraftClient client, SlotType type, SaveService.Result result) {
		if (result.refusal() != null) {
			refused(client, result.refusal());
		} else if (result.error() != null) {
			show(client, Text.translatable("quicksave.toast.failed"), Text.literal(String.valueOf(result.error().getMessage())));
		} else {
			show(client, Text.translatable(type == SlotType.QUICK ? "quicksave.toast.quicksaved" : "quicksave.toast.saved"),
					Text.translatable("quicksave.toast.took", result.millis()));
		}
	}

	public static void refused(MinecraftClient client, SaveGuard.Refusal refusal) {
		show(client, Text.translatable("quicksave.refused." + refusal.key), null);
	}

	public static void show(MinecraftClient client, Text title, @Nullable Text description) {
		SystemToast.show(client.getToastManager(), TYPE, title, description);
	}
}
