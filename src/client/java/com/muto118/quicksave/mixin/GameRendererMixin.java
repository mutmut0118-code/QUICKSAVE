package com.muto118.quicksave.mixin;

import com.muto118.quicksave.ui.DeathCam;
import com.muto118.quicksave.ui.Thumbnails;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** Save thumbnails: the framebuffer holds the world only here (HUD, toasts and screens are drawn next). */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;updateWorldIcon()V"))
	private void quicksave$captureThumbnail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
		Thumbnails.onWorldRendered(MinecraftClient.getInstance());
	}

	/** No first-person hand while the death cam looks at the body (vanilla checks the perspective option, not the camera). */
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void quicksave$hideHandInDeathCam(Camera camera, float tickDelta, Matrix4f matrix4f, CallbackInfo ci) {
		if (DeathCam.isActive()) {
			ci.cancel();
		}
	}
}
