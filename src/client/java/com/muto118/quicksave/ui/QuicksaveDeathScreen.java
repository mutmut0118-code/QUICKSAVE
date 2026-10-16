package com.muto118.quicksave.ui;

import com.muto118.quicksave.flow.ReloadController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * M1 death screen: death message plus "Load Last Save" and "Quit to Title".
 * There is deliberately no Respawn button. The countdown, death cam and
 * "Load..." button arrive in M2.
 */
public class QuicksaveDeathScreen extends Screen {
	private static final Text TITLE = Text.translatable("deathScreen.title");

	@Nullable
	private final Text message;
	private ButtonWidget loadButton;
	private ButtonWidget quitButton;
	private int ticksSinceDeath;
	private boolean actionTaken;

	public QuicksaveDeathScreen(@Nullable Text message) {
		super(TITLE);
		this.message = message;
	}

	@Override
	protected void init() {
		this.ticksSinceDeath = 0;
		this.loadButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.death.load"), button -> {
			this.actionTaken = true;
			this.setButtonsActive(false);
			ReloadController.loadLastSave(this.client);
		}).dimensions(this.width / 2 - 100, this.height / 4 + 72, 200, 20).build());
		this.quitButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.death.quit"), button -> {
			this.actionTaken = true;
			this.setButtonsActive(false);
			quitToTitle(this.client);
		}).dimensions(this.width / 2 - 100, this.height / 4 + 96, 200, 20).build());
		this.setButtonsActive(false);
	}

	@Override
	public void tick() {
		super.tick();
		// Like vanilla: buttons unlock after one second so a click meant for
		// the fight doesn't land on them.
		if (++this.ticksSinceDeath == 20 && !this.actionTaken) {
			this.setButtonsActive(true);
		}
	}

	private void setButtonsActive(boolean active) {
		this.loadButton.active = active;
		this.quitButton.active = active;
	}

	/** Same as the vanilla death screen's title button: the player stays dead, and rejoining shows this screen again. */
	private static void quitToTitle(MinecraftClient client) {
		if (client.world != null) {
			client.world.disconnect();
		}
		client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
		client.setScreen(new TitleScreen());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.getMatrices().push();
		context.getMatrices().scale(2.0F, 2.0F, 2.0F);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2 / 2, 30, 0xFFFFFF);
		context.getMatrices().pop();
		if (this.message != null) {
			context.drawCenteredTextWithShadow(this.textRenderer, this.message, this.width / 2, 85, 0xFFFFFF);
		}
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("quicksave.death.loading"),
				this.width / 2, 100, 0xC0C0C0);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Same red wash as the vanilla death screen.
		context.fillGradient(0, 0, this.width, this.height, 0x60500000, 0xA0803030);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
