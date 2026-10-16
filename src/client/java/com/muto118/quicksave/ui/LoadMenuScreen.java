package com.muto118.quicksave.ui;

import com.muto118.quicksave.QuicksaveClient;
import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.flow.ReloadController;
import com.muto118.quicksave.snapshot.SaveMeta;
import com.muto118.quicksave.snapshot.SlotType;
import com.muto118.quicksave.snapshot.SnapshotStore;
import com.muto118.quicksave.snapshot.WorldStore;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Skyrim-style load menu: every save of the open world, newest first, with a
 * thumbnail, name/type, in-game day and time, real date, dimension, position and
 * health. Load, Rename, Delete. Opened from the pause menu, the death screen's
 * "Load..." and the (unbound by default) load menu key.
 */
public class LoadMenuScreen extends Screen {
	private static final DateTimeFormatter REAL_TIME = DateTimeFormatter.ofPattern("MMM d yyyy, HH:mm", Locale.ROOT)
			.withZone(ZoneId.systemDefault());
	private static final int THUMB_W = 96;
	private static final int THUMB_H = 54;

	@Nullable
	private final Screen parent;
	/** From the death screen: load without asking, the world isn't paused. */
	private final boolean fromDeath;
	private final String worldName;
	private SaveList list;
	private ButtonWidget loadButton;
	private ButtonWidget renameButton;
	private ButtonWidget deleteButton;
	@Nullable
	private String selectedId;
	private Text storageText = Text.empty();
	private final List<Identifier> textures = new ArrayList<>();
	private boolean busy;

	public LoadMenuScreen(@Nullable Screen parent, boolean fromDeath, String worldName) {
		super(Text.translatable("quicksave.load_menu.title"));
		this.parent = parent;
		this.fromDeath = fromDeath;
		this.worldName = worldName;
	}

	/** Opens the menu for the open world, or shows why it can't. */
	public static void open(MinecraftClient client, @Nullable Screen parent, boolean fromDeath) {
		String world = QuicksaveClient.activeWorldFolderName(client);
		if (world == null) {
			SaveToast.show(client, Text.translatable("quicksave.refused.inactive"), null);
			return;
		}
		client.setScreen(new LoadMenuScreen(parent, fromDeath, world));
	}

	@Override
	protected void init() {
		this.list = this.addDrawableChild(new SaveList(this.client, this.width, this.height - 64 - 32, 32, THUMB_H + 6));
		int y = this.height - 52;
		this.loadButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.load_menu.load"),
				button -> this.loadSelected()).dimensions(this.width / 2 - 154, y, 150, 20).build());
		this.renameButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.load_menu.rename"),
				button -> this.renameSelected()).dimensions(this.width / 2 + 4, y, 72, 20).build());
		this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("quicksave.load_menu.delete"),
				button -> this.deleteSelected()).dimensions(this.width / 2 + 82, y, 72, 20).build());
		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> this.close())
				.dimensions(this.width / 2 - 154, y + 24, 308, 20).build());
		this.reload();
	}

	/** Re-reads the saves (after a rename/delete) and the storage total. */
	public void reload() {
		this.freeTextures();
		List<SnapshotStore.Slot> slots = SnapshotStore.list(this.worldName);
		this.list.setSlots(slots);
		this.updateButtons();
		WorldStore store = SnapshotStore.store(this.worldName);
		CompletableFuture.supplyAsync(() -> {
			try {
				return store.totalBytes();
			} catch (Exception e) {
				return -1L;
			}
		}, Util.getIoWorkerExecutor()).thenAcceptAsync(bytes -> this.storageText = Text.translatable(
				"quicksave.load_menu.storage", slots.size(), bytes < 0 ? "?" : String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)),
				this.client);
	}

	@Nullable
	public SnapshotStore.Slot selected() {
		SaveEntry entry = this.list.getSelectedOrNull();
		return entry == null ? null : entry.slot;
	}

	/** Selects a slot by id (used by the self-test and to keep the selection after a reload). */
	public boolean select(String slotId) {
		for (SaveEntry entry : this.list.children()) {
			if (entry.slot.id().equals(slotId)) {
				this.list.setSelected(entry);
				return true;
			}
		}
		return false;
	}

	public int entryCount() {
		return this.list.children().size();
	}

	private void updateButtons() {
		boolean has = this.selected() != null && !this.busy;
		this.loadButton.active = has;
		this.renameButton.active = has;
		this.deleteButton.active = has;
	}

	@Override
	public void tick() {
		this.updateButtons();
	}

	private void loadSelected() {
		SnapshotStore.Slot slot = this.selected();
		if (slot == null) {
			return;
		}
		if (this.fromDeath) {
			ReloadController.load(this.client, slot.id());
			return;
		}
		this.client.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				ReloadController.load(this.client, slot.id());
			} else {
				this.client.setScreen(this);
			}
		}, Text.translatable("quicksave.load_menu.confirm_load", slot.meta().name()),
				Text.translatable("quicksave.confirm_load.message")));
	}

	private void renameSelected() {
		SnapshotStore.Slot slot = this.selected();
		if (slot == null) {
			return;
		}
		this.client.setScreen(new SaveNameScreen(this, Text.translatable("quicksave.load_menu.rename_title"),
				slot.meta().name(), Text.translatable("quicksave.load_menu.rename"), false, name -> {
			try {
				SnapshotStore.store(this.worldName).rename(slot.id(), name);
			} catch (Exception e) {
				QuicksaveMod.LOGGER.error("Renaming {} failed", slot.id(), e);
				SaveToast.show(this.client, Text.translatable("quicksave.load_menu.failed"), Text.literal(String.valueOf(e.getMessage())));
			}
			this.client.setScreen(this);
			this.select(slot.id());
		}));
	}

	private void deleteSelected() {
		SnapshotStore.Slot slot = this.selected();
		if (slot == null) {
			return;
		}
		this.client.setScreen(new ConfirmScreen(confirmed -> {
			this.client.setScreen(this);
			if (!confirmed) {
				return;
			}
			this.busy = true;
			WorldStore store = SnapshotStore.store(this.worldName);
			CompletableFuture.runAsync(() -> {
				try {
					store.delete(slot.id());
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}, Util.getIoWorkerExecutor()).whenCompleteAsync((v, error) -> {
				this.busy = false;
				if (error != null) {
					QuicksaveMod.LOGGER.error("Deleting {} failed", slot.id(), error);
					SaveToast.show(this.client, Text.translatable("quicksave.load_menu.failed"), Text.literal(String.valueOf(error.getMessage())));
				}
				this.reload();
			}, this.client);
		}, Text.translatable("quicksave.load_menu.confirm_delete", slot.meta().name()),
				Text.translatable("quicksave.load_menu.confirm_delete.message"),
				Text.translatable("quicksave.load_menu.delete"), ScreenTexts.CANCEL));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, this.storageText, this.width / 2, 20, Colors.GRAY);
		if (this.list.children().isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("quicksave.load_menu.empty"),
					this.width / 2, this.height / 2 - 20, Colors.GRAY);
		}
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.freeTextures();
	}

	@Override
	public boolean shouldPause() {
		return !this.fromDeath;
	}

	private void freeTextures() {
		for (Identifier id : this.textures) {
			this.client.getTextureManager().destroyTexture(id);
		}
		this.textures.clear();
		if (this.list != null) {
			for (SaveEntry entry : this.list.children()) {
				entry.texture = null;
				entry.textureLoaded = false;
			}
		}
	}

	// ------------------------------------------------------------------ text helpers

	static Text describeType(SlotType type) {
		return Text.translatable("quicksave.slot_type." + type.name().toLowerCase(Locale.ROOT));
	}

	static String dimensionName(@Nullable String dimension) {
		if (dimension == null) {
			return "?";
		}
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "The End";
			default -> {
				String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
				yield path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}

	/** "Day 12, 14:30" from world time of day (0 = 06:00 on day 1). */
	static String gameTime(long dayTime) {
		long day = dayTime / 24000L + 1;
		long ticks = dayTime % 24000L;
		long hours = (ticks / 1000L + 6) % 24;
		long minutes = (ticks % 1000L) * 60 / 1000;
		return String.format(Locale.ROOT, "Day %d, %02d:%02d", day, hours, minutes);
	}

	// ------------------------------------------------------------------ list

	final class SaveList extends AlwaysSelectedEntryListWidget<SaveEntry> {
		SaveList(MinecraftClient client, int width, int height, int y, int itemHeight) {
			super(client, width, height, y, itemHeight);
		}

		void setSlots(List<SnapshotStore.Slot> slots) {
			String keep = LoadMenuScreen.this.selectedId;
			this.clearEntries();
			for (SnapshotStore.Slot slot : slots) {
				SaveEntry entry = new SaveEntry(slot);
				this.addEntry(entry);
				if (slot.id().equals(keep)) {
					this.setSelected(entry);
				}
			}
			if (this.getSelectedOrNull() == null && !this.children().isEmpty()) {
				this.setSelected(this.children().get(0));
			}
		}

		@Override
		public void setSelected(@Nullable SaveEntry entry) {
			super.setSelected(entry);
			LoadMenuScreen.this.selectedId = entry == null ? null : entry.slot.id();
		}

		@Override
		public int getRowWidth() {
			return 320;
		}
	}

	final class SaveEntry extends AlwaysSelectedEntryListWidget.Entry<SaveEntry> {
		final SnapshotStore.Slot slot;
		@Nullable
		Identifier texture;
		boolean textureLoaded;
		private long lastClick;

		SaveEntry(SnapshotStore.Slot slot) {
			this.slot = slot;
		}

		@Nullable
		private Identifier texture() {
			if (!this.textureLoaded) {
				this.textureLoaded = true;
				Path png = SnapshotStore.store(LoadMenuScreen.this.worldName).thumbnail(this.slot.id());
				if (Files.isRegularFile(png)) {
					try (InputStream in = Files.newInputStream(png)) {
						NativeImage image = NativeImage.read(in);
						Identifier id = Identifier.of(QuicksaveMod.MOD_ID,
								"thumb/" + Integer.toHexString(LoadMenuScreen.this.worldName.hashCode()) + "/" + this.slot.id());
						LoadMenuScreen.this.client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
						LoadMenuScreen.this.textures.add(id);
						this.texture = id;
					} catch (Exception e) {
						QuicksaveMod.LOGGER.warn("Bad thumbnail {}", png, e);
					}
				}
			}
			return this.texture;
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX,
				int mouseY, boolean hovered, float tickDelta) {
			SaveMeta meta = this.slot.meta();
			Identifier tex = this.texture();
			if (tex != null) {
				RenderSystem.enableBlend();
				context.drawTexture(tex, x, y, 0.0F, 0.0F, THUMB_W, THUMB_H, THUMB_W, THUMB_H);
				RenderSystem.disableBlend();
			} else {
				context.fill(x, y, x + THUMB_W, y + THUMB_H, 0xFF202020);
				context.drawCenteredTextWithShadow(LoadMenuScreen.this.textRenderer, Text.literal(dimensionName(meta.dimension())),
						x + THUMB_W / 2, y + THUMB_H / 2 - 4, Colors.GRAY);
			}
			int tx = x + THUMB_W + 6;
			var tr = LoadMenuScreen.this.textRenderer;
			Text name = Text.literal(meta.name()).append(Text.literal("  ")).append(describeType(meta.type()).copy()
					.formatted(Formatting.GRAY));
			context.drawText(tr, name, tx, y + 2, 0xFFFFFF, false);
			String line2 = meta.dimension() == null ? gameTime(meta.dayTime())
					: gameTime(meta.dayTime()) + "  ·  " + dimensionName(meta.dimension())
					+ String.format(Locale.ROOT, "  ·  %d, %d, %d", Math.round(meta.x()), Math.round(meta.y()), Math.round(meta.z()));
			context.drawText(tr, line2, tx, y + 15, Colors.GRAY, false);
			String line3 = REAL_TIME.format(Instant.ofEpochMilli(meta.createdMs()))
					+ (meta.health() > 0 ? String.format(Locale.ROOT, "  ·  ❤ %.1f", meta.health()) : "");
			context.drawText(tr, line3, tx, y + 27, Colors.GRAY, false);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			LoadMenuScreen.this.list.setSelected(this);
			long now = Util.getMeasuringTimeMs();
			if (now - this.lastClick < 250L) {
				LoadMenuScreen.this.loadSelected();
			}
			this.lastClick = now;
			return true;
		}

		@Override
		public Text getNarration() {
			return Text.translatable("narrator.select", this.slot.meta().name());
		}
	}
}
