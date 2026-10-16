package com.muto118.quicksave.ui;

import com.muto118.quicksave.config.QuicksaveConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Third-person camera pose while {@link QuicksaveDeathScreen} is open.
 * Applied by {@code CameraMixin} after vanilla positions the camera.
 */
public final class DeathCam {
	/**
	 * @param yaw      degrees
	 * @param pitch    degrees, positive looks down
	 * @param distance blocks back from the body (clipped against walls)
	 */
	public record Pose(float yaw, float pitch, float distance) {
	}

	private DeathCam() {
	}

	/** True while the death screen drives the camera. */
	public static boolean isActive() {
		return MinecraftClient.getInstance().currentScreen instanceof QuicksaveDeathScreen screen && screen.hasDeathCam();
	}

	@Nullable
	public static Pose current(float tickDelta) {
		if (!(MinecraftClient.getInstance().currentScreen instanceof QuicksaveDeathScreen screen) || !screen.hasDeathCam()) {
			return null;
		}
		float progress = easeOutCubic(screen.camProgress());
		float yaw = screen.startYaw();
		return switch (QuicksaveConfig.get().camStyle()) {
			case PULLBACK -> new Pose(yaw + 25.0F * progress, MathHelper.lerp(progress, 20.0F, 50.0F),
					MathHelper.lerp(progress, 1.0F, 6.0F));
			case ORBIT -> new Pose(yaw + 40.0F * screen.camSeconds(tickDelta), 35.0F,
					MathHelper.lerp(progress, 1.0F, 4.5F));
			case FIXED -> new Pose(yaw, 30.0F, 4.0F);
		};
	}

	private static float easeOutCubic(float t) {
		float inv = 1.0F - t;
		return 1.0F - inv * inv * inv;
	}
}
