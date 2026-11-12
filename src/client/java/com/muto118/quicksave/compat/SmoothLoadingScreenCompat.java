package com.muto118.quicksave.compat;

import com.muto118.quicksave.QuicksaveMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/**
 * Compatibility with Smooth Loading Screen (mod id {@code smoothloadingscreen}, closed source,
 * bundles "juxtui"). Reflection only, so there is no build dependency; it disables itself after
 * the first failure (e.g. a future version renamed something).
 *
 * <p>That mod arms its join transition when a world is clicked in the Singleplayer list
 * ({@code WorldListWidget.WorldEntry#play} → {@code PanoramaHover.forceApply(client, folderName)})
 * and resets its exit/capture state on the title screen. Quicksave's reload goes from the
 * world straight back into the same world, through neither, so:
 * <ul>
 * <li>{@link #beforeReopen}: reset its leftover state like the title screen does
 * ({@code PanoramaAlign.cancel()}), then arm the transition exactly like clicking the world
 * does, so a Quicksave load gets the same smooth fade. Without the reset, an exit state left
 * over from the world we just closed kept the transition from ever finishing (no fade, and the
 * hand stayed pushed off-screen: the user's bug report).</li>
 * <li>{@link #afterLoadSettled}: once the reopened world has been on screen for a few seconds,
 * clear anything still hiding the first-person hand: its frame-by-frame panorama capture
 * ({@code WorldPanoramaCaptureFrame}: HUD hidden, FOV, panorama mode), its exit state
 * ({@code PanoramaAlign.hudAlpha() < 1} pushes the hand off-screen), and vanilla's
 * {@code GameRenderer.renderingPanorama} flag (vanilla skips the hand while it is set; this
 * check runs whether or not the mod is installed).</li>
 * </ul>
 */
public final class SmoothLoadingScreenCompat {
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("smoothloadingscreen");
	private static boolean disabled;

	private SmoothLoadingScreenCompat() {
	}

	public static boolean isLoaded() {
		return LOADED;
	}

	private static final String[] BUSY_FLAGS = {"isArmed", "isPreFogging", "isSwinging", "isHandoverAnimating",
			"isCameraTransitionActive"};

	/** True while that mod's join transition is legitimately running (don't judge or touch it). */
	public static boolean transitionRunning() {
		if (!LOADED || disabled) {
			return false;
		}
		try {
			Class<?> align = Class.forName("juxtui.util.PanoramaAlign");
			for (String flag : BUSY_FLAGS) {
				if ((Boolean) align.getMethod(flag).invoke(null)) {
					return true;
				}
			}
			return false;
		} catch (Throwable e) {
			disable("reading its transition state", e);
			return false;
		}
	}

	/** Last resort for a transition that never ends: cancel it (camera, fog, fade, exit state). */
	public static void cancelTransition() {
		if (!LOADED || disabled) {
			return;
		}
		try {
			Class.forName("juxtui.util.PanoramaAlign").getMethod("cancel").invoke(null);
			QuicksaveMod.LOGGER.info("Cancelled a Smooth Loading Screen transition that never finished");
		} catch (Throwable e) {
			disable("cancelling a stuck transition", e);
		}
	}

	/** Render thread, right before Quicksave reopens the world. */
	public static void beforeReopen(MinecraftClient client, String worldFolderName) {
		if (!LOADED || disabled) {
			return;
		}
		try {
			// Like the title screen does before any join: drop leftover exit/entry/camera state
			// from the world we just left (left over, it keeps the next transition from ever
			// finishing, and the hand stays pushed off-screen)...
			Class.forName("juxtui.util.PanoramaAlign").getMethod("cancel").invoke(null);
			// ...then arm the join exactly like clicking the world in the Singleplayer list.
			Class.forName("juxtui.util.PanoramaHover")
					.getMethod("forceApply", MinecraftClient.class, String.class)
					.invoke(null, client, worldFolderName);
		} catch (Throwable e) {
			disable("arming the join transition", e);
		}
	}

	/**
	 * Render thread, a few seconds after a Quicksave load put the player back in the world with
	 * no screen open and that mod's transition finished (its entry fade is 1.2 s). Returns what
	 * it fixed.
	 */
	public static String afterLoadSettled(MinecraftClient client) {
		StringBuilder fixed = new StringBuilder();
		if (LOADED && !disabled) {
			try {
				Class<?> frame = Class.forName("juxtui.util.WorldPanoramaCaptureFrame");
				if ((Boolean) frame.getMethod("active").invoke(null)) {
					frame.getMethod("finish").invoke(null);
					fixed.append(" panorama capture left running;");
				}
				Class<?> align = Class.forName("juxtui.util.PanoramaAlign");
				float alpha = (Float) align.getMethod("hudAlpha").invoke(null);
				if (alpha < 0.999F) {
					align.getMethod("endExit").invoke(null);
					fixed.append(" HUD/hand fade stuck at ").append(alpha).append(';');
				}
			} catch (Throwable e) {
				disable("checking for a stuck transition", e);
			}
		}
		if (client.gameRenderer.isRenderingPanorama()) {
			client.gameRenderer.setRenderingPanorama(false);
			client.gameRenderer.setBlockOutlineEnabled(true);
			fixed.append(" GameRenderer left in panorama mode;");
		}
		String result = fixed.toString().trim();
		if (!result.isEmpty()) {
			QuicksaveMod.LOGGER.info("Restored the hand/HUD after a load:{}", " " + result);
		}
		return result;
	}

	private static void disable(String what, Throwable e) {
		disabled = true;
		Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
				? ite.getCause() : e;
		QuicksaveMod.LOGGER.warn("Smooth Loading Screen compat disabled ({} failed): {}", what, cause.toString());
	}
}
