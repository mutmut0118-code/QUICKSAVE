package com.muto118.quicksave.compat;

import com.muto118.quicksave.config.QuicksaveConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cloth Config screen for config/quicksave.json. Changes apply immediately on Save. */
public final class ClothConfigScreen {
	private ClothConfigScreen() {
	}

	private static Text t(String key) {
		return Text.translatable("quicksave.config." + key);
	}

	private static Text tip(String key) {
		return Text.translatable("quicksave.config." + key + ".tooltip");
	}

	public static Screen create(Screen parent) {
		QuicksaveConfig c = QuicksaveConfig.get();
		QuicksaveConfig d = new QuicksaveConfig();
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("quicksave.config.title"))
				.setSavingRunnable(QuicksaveConfig::save);
		ConfigEntryBuilder e = builder.entryBuilder();

		ConfigCategory slots = builder.getOrCreateCategory(t("category.slots"));
		slots.addEntry(e.startIntField(t("quicksaveSlots"), c.quicksaveSlots).setDefaultValue(d.quicksaveSlots).setMin(1)
				.setTooltip(tip("quicksaveSlots")).setSaveConsumer(v -> c.quicksaveSlots = v).build());
		slots.addEntry(e.startIntField(t("autosaveSlots"), c.autosaveSlots).setDefaultValue(d.autosaveSlots).setMin(1)
				.setTooltip(tip("autosaveSlots")).setSaveConsumer(v -> c.autosaveSlots = v).build());
		slots.addEntry(e.startIntField(t("maxStorageMB"), c.maxStorageMB).setDefaultValue(d.maxStorageMB).setMin(0)
				.setTooltip(tip("maxStorageMB")).setSaveConsumer(v -> c.maxStorageMB = v).build());

		ConfigCategory autosave = builder.getOrCreateCategory(t("category.autosave"));
		autosave.addEntry(e.startIntField(t("autosaveIntervalMinutes"), c.autosaveIntervalMinutes)
				.setDefaultValue(d.autosaveIntervalMinutes).setMin(0).setTooltip(tip("autosaveIntervalMinutes"))
				.setSaveConsumer(v -> c.autosaveIntervalMinutes = v).build());
		autosave.addEntry(e.startBooleanToggle(t("autosaveOnDimensionChange"), c.autosaveOnDimensionChange)
				.setDefaultValue(d.autosaveOnDimensionChange).setSaveConsumer(v -> c.autosaveOnDimensionChange = v).build());
		autosave.addEntry(e.startBooleanToggle(t("autosaveOnSleep"), c.autosaveOnSleep)
				.setDefaultValue(d.autosaveOnSleep).setSaveConsumer(v -> c.autosaveOnSleep = v).build());
		autosave.addEntry(e.startBooleanToggle(t("autosaveOnQuit"), c.autosaveOnQuit)
				.setDefaultValue(d.autosaveOnQuit).setSaveConsumer(v -> c.autosaveOnQuit = v).build());

		ConfigCategory death = builder.getOrCreateCategory(t("category.death"));
		death.addEntry(e.startDoubleField(t("deathCamSeconds"), c.deathCamSeconds).setDefaultValue(d.deathCamSeconds)
				.setMin(0).setMax(30).setTooltip(tip("deathCamSeconds")).setSaveConsumer(v -> c.deathCamSeconds = v).build());
		death.addEntry(e.startEnumSelector(t("deathCamStyle"), QuicksaveConfig.DeathCamStyle.class, c.camStyle())
				.setDefaultValue(QuicksaveConfig.DeathCamStyle.PULLBACK)
				.setEnumNameProvider(style -> t("deathCamStyle." + style.name().toLowerCase(Locale.ROOT)))
				.setSaveConsumer(v -> c.deathCamStyle = v.name().toLowerCase(Locale.ROOT)).build());
		death.addEntry(e.startIntField(t("deathCountdownSeconds"), c.deathCountdownSeconds)
				.setDefaultValue(d.deathCountdownSeconds).setMin(0).setMax(60).setTooltip(tip("deathCountdownSeconds"))
				.setSaveConsumer(v -> c.deathCountdownSeconds = v).build());
		death.addEntry(e.startBooleanToggle(t("confirmQuickload"), c.confirmQuickload)
				.setDefaultValue(d.confirmQuickload).setSaveConsumer(v -> c.confirmQuickload = v).build());

		ConfigCategory rules = builder.getOrCreateCategory(t("category.rules"));
		rules.addEntry(e.startBooleanToggle(t("allowSaveInCombat"), c.allowSaveInCombat)
				.setDefaultValue(d.allowSaveInCombat).setSaveConsumer(v -> c.allowSaveInCombat = v).build());
		rules.addEntry(e.startIntField(t("combatRadius"), c.combatRadius).setDefaultValue(d.combatRadius).setMin(0)
				.setMax(128).setTooltip(tip("combatRadius")).setSaveConsumer(v -> c.combatRadius = v).build());
		rules.addEntry(e.startIntField(t("combatCooldownSeconds"), c.combatCooldownSeconds)
				.setDefaultValue(d.combatCooldownSeconds).setMin(0).setMax(120).setTooltip(tip("combatCooldownSeconds"))
				.setSaveConsumer(v -> c.combatCooldownSeconds = v).build());
		rules.addEntry(e.startBooleanToggle(t("blockFalling"), c.blocks("falling")).setDefaultValue(true)
				.setTooltip(tip("blockFalling")).setSaveConsumer(v -> setBlock(c, "falling", v)).build());
		rules.addEntry(e.startBooleanToggle(t("blockInLava"), c.blocks("in_lava")).setDefaultValue(true)
				.setSaveConsumer(v -> setBlock(c, "in_lava", v)).build());
		rules.addEntry(e.startBooleanToggle(t("allowInHardcore"), c.allowInHardcore)
				.setDefaultValue(d.allowInHardcore).setTooltip(tip("allowInHardcore"))
				.setSaveConsumer(v -> c.allowInHardcore = v).build());
		return builder.build();
	}

	private static void setBlock(QuicksaveConfig config, String situation, boolean blocked) {
		List<String> list = new ArrayList<>(config.blockSaveWhen);
		list.remove(situation);
		if (blocked) {
			list.add(situation);
		}
		config.blockSaveWhen = list;
	}
}
