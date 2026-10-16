package com.muto118.quicksave.mixin;

import com.muto118.quicksave.ui.DeathCam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No "poof" around your own body while the death cam keeps it on screen (client only). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "addDeathParticles", at = @At("HEAD"), cancellable = true)
	private void quicksave$noPoofInDeathCam(CallbackInfo ci) {
		if ((Object) this == MinecraftClient.getInstance().player && DeathCam.isActive()) {
			ci.cancel();
		}
	}
}
