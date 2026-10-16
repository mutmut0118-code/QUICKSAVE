package com.muto118.quicksave.mixin;

import com.muto118.quicksave.ui.DeathCam;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No first-person hand while the death cam looks at the body (vanilla checks the perspective option, not the camera). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void quicksave$hideHandInDeathCam(Camera camera, float tickDelta, Matrix4f matrix4f, CallbackInfo ci) {
		if (DeathCam.isActive()) {
			ci.cancel();
		}
	}
}
