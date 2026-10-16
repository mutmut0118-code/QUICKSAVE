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

/** Pause menu "Save": name a manual save. An empty name gets a date-based label. */
public class SaveNameScreen extends Screen {
	@Nullable
	private final Screen parent;
	private TextFieldWidget nameField;

	public SaveNameScreen(@Nullable Screen parent) {
		super(Text.translatable("quicksave.save_name.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.nameField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, this.height / 2 - 20, 200, 20,
				Text.translatable("quicksave.save_name.field"));
		this.nameField.setMaxLength(64);
		this.nameField.setPlaceholder(Text.translatable("quicksave.save_name.placeholder"));
		this.addDrawableChild(this.nameField);
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.save_name.save"), button -> this.save())
				.dimensions(this.width / 2 - 100, this.height / 2 + 10, 98, 20).build());
		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> this.close())
				.dimensions(this.width / 2 + 2, this.height / 2 + 10, 98, 20).build());
		this.setInitialFocus(this.nameField);
	}

	public void setName(String name) {
		this.nameField.setText(name);
	}

	public void save() {
		String name = this.nameField.getText();
		this.client.setScreen(null);
		SaveRequests.request(this.client, SlotType.MANUAL, name.isBlank() ? null : name);
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
