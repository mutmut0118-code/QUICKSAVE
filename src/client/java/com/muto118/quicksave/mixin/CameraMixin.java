package com.muto118.quicksave.mixin;

import com.muto118.quicksave.ui.DeathCam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Death cam: re-aims the camera at the dead player's body (see {@link DeathCam}). */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean thirdPerson;

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	protected abstract void moveBy(float f, float g, float h);

	@Shadow
	protected abstract float clipToSpace(float f);

	@Inject(method = "update", at = @At("TAIL"))
	private void quicksave$deathCam(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
			float tickDelta, CallbackInfo ci) {
		if (focusedEntity != MinecraftClient.getInstance().player) {
			return;
		}
		DeathCam.Pose pose = DeathCam.current(tickDelta);
		if (pose == null) {
			return;
		}
		this.setRotation(pose.yaw(), pose.pitch());
		this.setPos(
				MathHelper.lerp(tickDelta, focusedEntity.prevX, focusedEntity.getX()),
				MathHelper.lerp(tickDelta, focusedEntity.prevY, focusedEntity.getY()) + 0.4,
				MathHelper.lerp(tickDelta, focusedEntity.prevZ, focusedEntity.getZ()));
		this.moveBy(-this.clipToSpace(pose.distance()), 0.0F, 0.0F);
		// Renders the body and hides the first-person hand.
		this.thirdPerson = true;
	}
}
