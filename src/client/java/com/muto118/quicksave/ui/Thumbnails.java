package com.muto118.quicksave.ui;

import com.muto118.quicksave.QuicksaveMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 256x144 save thumbnails of the world only: no HUD, chat, toasts or menus.
 *
 * <p>A capture is requested, then taken at the next frame right after the world is
 * drawn and before the HUD and screens (the spot vanilla uses for the world-list
 * icon, see {@code GameRendererMixin}). The world is still drawn under paused
 * menus, so this works from the pause menu too. The last capture is kept for saves
 * made after the world stopped rendering (the quit autosave).
 */
public final class Thumbnails {
	public static final int WIDTH = 256;
	public static final int HEIGHT = 144;
	private static final long CACHE_MS = 5 * 60_000L;

	private static final List<Consumer<byte[]>> WAITING = new ArrayList<>();
	private static byte @Nullable [] lastCapture;
	private static long lastCaptureAt;

	private Thumbnails() {
	}

	/** Render thread. {@code callback} gets the PNG at the next rendered world frame. */
	public static void request(Consumer<byte[]> callback) {
		WAITING.add(callback);
	}

	/** Render thread: the last capture if recent (for when no world frame is coming). */
	public static byte @Nullable [] recent() {
		return lastCapture != null && Util.getMeasuringTimeMs() - lastCaptureAt < CACHE_MS ? lastCapture : null;
	}

	/** Drops pending requests (world closed). */
	public static void reset() {
		WAITING.clear();
	}

	/** {@code GameRendererMixin}: the world was just drawn; HUD and screens come after. */
	public static void onWorldRendered(MinecraftClient client) {
		if (WAITING.isEmpty()) {
			return;
		}
		byte[] png = capture(client);
		List<Consumer<byte[]>> callbacks = new ArrayList<>(WAITING);
		WAITING.clear();
		if (png == null) {
			return;
		}
		lastCapture = png;
		lastCaptureAt = Util.getMeasuringTimeMs();
		for (Consumer<byte[]> callback : callbacks) {
			try {
				callback.accept(png);
			} catch (RuntimeException e) {
				QuicksaveMod.LOGGER.warn("Thumbnail callback failed", e);
			}
		}
	}

	/** Center-crops the framebuffer to 16:9 and scales it to 256x144 PNG bytes. */
	private static byte @Nullable [] capture(MinecraftClient client) {
		try (NativeImage shot = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
				NativeImage thumb = new NativeImage(WIDTH, HEIGHT, false)) {
			int w = shot.getWidth();
			int h = shot.getHeight();
			int cropW = Math.min(w, h * 16 / 9);
			int cropH = Math.min(h, w * 9 / 16);
			shot.resizeSubRectTo((w - cropW) / 2, (h - cropH) / 2, cropW, cropH, thumb);
			return thumb.getBytes();
		} catch (Exception e) {
			QuicksaveMod.LOGGER.warn("Could not capture a save thumbnail", e);
			return null;
		}
	}
}
