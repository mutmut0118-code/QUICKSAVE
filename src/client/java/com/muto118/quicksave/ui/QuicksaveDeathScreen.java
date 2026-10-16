package com.muto118.quicksave.ui;

import com.muto118.quicksave.config.QuicksaveConfig;
import com.muto118.quicksave.flow.ReloadController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Replaces the vanilla death screen when the world has a save.
 *
 * <ol>
 * <li>Death cam: {@code deathCamSeconds} of the world only, while
 * {@link DeathCam} pulls the camera away from the body. A click or key skips it.</li>
 * <li>"You Died!", the death message and "Loading last save in N...". At 0 the
 * last save loads. With {@code deathCountdownSeconds = 0} it waits for a click.</li>
 * </ol>
 * Buttons: Load Last Save, Load... (the load menu; stops the countdown), Quit to
 * Title. No Respawn.
 */
public class QuicksaveDeathScreen extends Screen {
	private static final Text TITLE = Text.translatable("deathScreen.title");
	private static final long FADE_MS = 400L;
	/** Like vanilla: buttons stay locked this long so a click meant for the fight doesn't hit them. */
	private static final long MIN_BUTTON_DELAY_MS = 1000L;

	@Nullable
	private final Text message;
	private final long openedAt = Util.getMeasuringTimeMs();
	private final long camMs;
	private final long countdownMs;
	private final float startYaw;
	private long camSkippedAt = -1L;
	private ButtonWidget loadButton;
	private ButtonWidget loadMenuButton;
	private ButtonWidget quitButton;
	/** Set once the player opened the load menu: from then on, wait for a click. */
	private boolean countdownCancelled;
	private boolean actionTaken;

	public QuicksaveDeathScreen(@Nullable Text message) {
		super(TITLE);
		this.message = message;
		QuicksaveConfig config = QuicksaveConfig.get();
		this.camMs = Math.round(config.deathCamSeconds * 1000.0);
		this.countdownMs = config.deathCountdownSeconds * 1000L;
		MinecraftClient client = MinecraftClient.getInstance();
		this.startYaw = client.player != null ? client.player.getYaw() : 0.0F;
	}

	@Override
	protected void init() {
		this.loadButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.death.load"),
				button -> this.load()).dimensions(this.width / 2 - 100, this.height / 4 + 72, 200, 20).build());
		this.loadMenuButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.death.load_menu"), button -> {
			this.countdownCancelled = true;
			LoadMenuScreen.open(this.client, this, true);
		}).dimensions(this.width / 2 - 100, this.height / 4 + 96, 200, 20).build());
		this.quitButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.death.quit"), button -> {
			this.actionTaken = true;
			this.updateButtons();
			quitToTitle(this.client);
		}).dimensions(this.width / 2 - 100, this.height / 4 + 120, 200, 20).build());
		this.updateButtons();
	}

	private long elapsed() {
		return Util.getMeasuringTimeMs() - this.openedAt;
	}

	/** Milliseconds after which the UI shows (end of the death cam). */
	private long uiAt() {
		return this.camSkippedAt >= 0 ? this.camSkippedAt : this.camMs;
	}

	public boolean inCamPhase() {
		return this.elapsed() < this.uiAt();
	}

	/** Seconds since the screen opened (drives the orbit style). */
	public float camSeconds(float tickDelta) {
		return this.elapsed() / 1000.0F;
	}

	/** How far the death cam has run, 0..1. */
	public float camProgress() {
		if (this.camMs <= 0) {
			return 1.0F;
		}
		float progress = this.camSkippedAt >= 0 ? 1.0F : (float) this.elapsed() / this.camMs;
		return MathHelper.clamp(progress, 0.0F, 1.0F);
	}

	public boolean hasDeathCam() {
		return this.camMs > 0;
	}

	public float startYaw() {
		return this.startYaw;
	}

	/** Remaining countdown in ms, or -1 when waiting for a click. */
	private long remainingCountdown() {
		if (this.countdownMs <= 0 || this.countdownCancelled) {
			return -1L;
		}
		return Math.max(0L, this.uiAt() + this.countdownMs - this.elapsed());
	}

	private void load() {
		if (this.actionTaken) {
			return;
		}
		this.actionTaken = true;
		this.updateButtons();
		ReloadController.loadLatest(this.client, ReloadController.ANY);
	}

	private void updateButtons() {
		boolean visible = !this.inCamPhase();
		boolean active = visible && !this.actionTaken && this.elapsed() >= MIN_BUTTON_DELAY_MS;
		this.loadButton.visible = visible;
		this.loadMenuButton.visible = visible;
		this.quitButton.visible = visible;
		this.loadButton.active = active;
		this.loadMenuButton.active = active;
		this.quitButton.active = active;
	}

	@Override
	public void tick() {
		super.tick();
		this.updateButtons();
		if (!this.actionTaken && !this.inCamPhase() && this.remainingCountdown() == 0L) {
			this.load();
		}
	}

	/** Same as the vanilla death screen's title button: the player stays dead, and rejoining shows this screen again. */
	private static void quitToTitle(MinecraftClient client) {
		if (client.world != null) {
			client.world.disconnect();
		}
		client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
		client.setScreen(new TitleScreen());
	}

	private boolean skipCam() {
		if (this.inCamPhase()) {
			this.camSkippedAt = this.elapsed();
			this.updateButtons();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return this.skipCam() || super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return this.skipCam() || super.keyPressed(keyCode, scanCode, modifiers);
	}

	private float uiAlpha() {
		return MathHelper.clamp((float) (this.elapsed() - this.uiAt()) / FADE_MS, 0.0F, 1.0F);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (this.inCamPhase()) {
			return;
		}
		int alpha = Math.max(4, (int) (this.uiAlpha() * 255.0F)) << 24;
		context.getMatrices().push();
		context.getMatrices().scale(2.0F, 2.0F, 2.0F);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2 / 2, 30, 0xFFFFFF | alpha);
		context.getMatrices().pop();
		if (this.message != null) {
			context.drawCenteredTextWithShadow(this.textRenderer, this.message, this.width / 2, 85, 0xFFFFFF | alpha);
		}
		long remaining = this.remainingCountdown();
		Text status;
		if (this.actionTaken) {
			status = Text.translatable("quicksave.death.loading");
		} else if (remaining < 0) {
			status = Text.translatable("quicksave.death.waiting");
		} else {
			status = Text.translatable("quicksave.death.countdown", (remaining + 999L) / 1000L);
		}
		context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, 100, 0xC0C0C0 | alpha);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		if (this.inCamPhase()) {
			return;
		}
		// Same red wash as the vanilla death screen, faded in after the death cam.
		float a = this.uiAlpha();
		context.fillGradient(0, 0, this.width, this.height,
				((int) (0x60 * a) << 24) | 0x500000, ((int) (0xA0 * a) << 24) | 0x803030);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	/** Keep the world running so the death cam has something to look at. */
	@Override
	public boolean shouldPause() {
		return false;
	}
}
