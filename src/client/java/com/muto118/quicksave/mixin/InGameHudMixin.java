package com.muto118.quicksave.mixin;

import com.muto118.quicksave.ui.DeathCam;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No crosshair during the death cam. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void quicksave$hideCrosshairInDeathCam(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		if (DeathCam.isActive()) {
			ci.cancel();
		}
	}
}
