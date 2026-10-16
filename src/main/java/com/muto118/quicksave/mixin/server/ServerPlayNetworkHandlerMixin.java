package com.muto118.quicksave.mixin.server;

import com.muto118.quicksave.flow.AutosaveScheduler;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Autosave on quit": runs while the leaving player is still in the world. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
	@Shadow
	public ServerPlayerEntity player;

	@Inject(method = "onDisconnected", at = @At("HEAD"))
	private void quicksave$autosaveOnQuit(DisconnectionInfo info, CallbackInfo ci) {
		AutosaveScheduler.onPlayerQuit(this.player);
	}
}
