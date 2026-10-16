package com.muto118.quicksave.mixin;

import com.muto118.quicksave.ui.DeathCam;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla removes your own body client-side 1 s after death. Keep it while
 * the death cam is looking at it; the world is reloaded afterwards anyway.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
	@Redirect(method = "updatePostDeath",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;remove(Lnet/minecraft/entity/Entity$RemovalReason;)V"))
	private void quicksave$keepBodyForDeathCam(ClientPlayerEntity self, Entity.RemovalReason reason) {
		if (!DeathCam.isActive()) {
			self.remove(reason);
		}
	}
}
