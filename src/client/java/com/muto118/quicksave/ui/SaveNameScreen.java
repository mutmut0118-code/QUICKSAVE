package com.muto118.quicksave.ui;

import com.muto118.quicksave.flow.SaveRequests;
import com.muto118.quicksave.snapshot.SlotType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A one-line name prompt. Used by the pause menu "Save" (name a manual save; empty
 * gets a date-based label) and by the load menu's Rename.
 */
public class SaveNameScreen extends Screen {
	@Nullable
	private final Screen parent;
	private final String initial;
	private final Text confirmText;
	private final Consumer<String> onConfirm;
	private final boolean allowEmpty;
	private TextFieldWidget nameField;
	private ButtonWidget confirmButton;

	/** Pause menu Save: closes to the game and starts a manual save. */
	public SaveNameScreen(@Nullable Screen parent) {
		this(parent, Text.translatable("quicksave.save_name.title"), "", Text.translatable("quicksave.save_name.save"), true,
				null);
	}

	/**
	 * @param onConfirm gets the trimmed name; null means "manual save" (close the screen, then save)
	 */
	public SaveNameScreen(@Nullable Screen parent, Text title, String initial, Text confirmText, boolean allowEmpty,
			@Nullable Consumer<String> onConfirm) {
		super(title);
		this.parent = parent;
		this.initial = initial;
		this.confirmText = confirmText;
		this.allowEmpty = allowEmpty;
		this.onConfirm = onConfirm != null ? onConfirm : name -> {
			this.client.setScreen(null);
			SaveRequests.request(this.client, SlotType.MANUAL, name.isEmpty() ? null : name);
		};
	}

	@Override
	protected void init() {
		this.nameField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, this.height / 2 - 20, 200, 20,
				Text.translatable("quicksave.save_name.field"));
		this.nameField.setMaxLength(64);
		this.nameField.setPlaceholder(Text.translatable("quicksave.save_name.placeholder"));
		this.nameField.setText(this.initial);
		this.nameField.setChangedListener(text -> this.updateButton());
		this.addDrawableChild(this.nameField);
		this.confirmButton = this.addDrawableChild(ButtonWidget.builder(this.confirmText, button -> this.save())
				.dimensions(this.width / 2 - 100, this.height / 2 + 10, 98, 20).build());
		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> this.close())
				.dimensions(this.width / 2 + 2, this.height / 2 + 10, 98, 20).build());
		this.setInitialFocus(this.nameField);
		this.updateButton();
	}

	private void updateButton() {
		this.confirmButton.active = this.allowEmpty || !this.nameField.getText().isBlank();
	}

	public void setName(String name) {
		this.nameField.setText(name);
	}

	/** Confirm (the Save / Rename button). */
	public void save() {
		if (!this.confirmButton.active) {
			return;
		}
		this.onConfirm.accept(this.nameField.getText().trim());
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			this.save();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFF);
	}
}
