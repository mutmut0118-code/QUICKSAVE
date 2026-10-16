package com.muto118.quicksave.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.muto118.quicksave.QuicksaveMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * config/quicksave.json. Missing keys fall back to the defaults below, and the
 * file is rewritten on load so new keys show up for the player to edit.
 */
public final class QuicksaveConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static QuicksaveConfig instance = new QuicksaveConfig();

	public int quicksaveSlots = 3;
	public int autosaveSlots = 3;
	public int autosaveIntervalMinutes = 10;
	public boolean autosaveOnDimensionChange = true;
	public boolean autosaveOnSleep = true;
	public boolean autosaveOnQuit = true;
	public double deathCamSeconds = 2.0;
	/** "pullback", "orbit" or "fixed". */
	public String deathCamStyle = "pullback";
	public int deathCountdownSeconds = 3;
	/** Pause the game (like the pause menu) once the "You Died!" screen is up. */
	public boolean pauseOnDeathScreen = true;
	/** Vanilla's red wash behind the death screen. */
	public boolean deathScreenTint = true;
	/** A dark vignette around the edges of the death screen. */
	public boolean deathScreenVignette = false;
	/** Death screen title. Empty = "You Died!" (or a resource pack's quicksave.death.title). */
	public String deathTitle = "";
	public boolean confirmQuickload = true;
	public boolean allowSaveInCombat = false;
	public int combatRadius = 16;
	public int combatCooldownSeconds = 5;
	/** Any of "falling", "in_lava". */
	public List<String> blockSaveWhen = new ArrayList<>(List.of("falling", "in_lava"));
	public boolean allowInHardcore = false;
	public int maxStorageMB = 4096;

	public static QuicksaveConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(QuicksaveMod.MOD_ID + ".json");
	}

	public static void load() {
		Path path = path();
		QuicksaveConfig loaded = null;
		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, QuicksaveConfig.class);
			} catch (IOException | JsonParseException e) {
				QuicksaveMod.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}
		instance = loaded != null ? loaded.sanitized() : new QuicksaveConfig();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			QuicksaveMod.LOGGER.warn("Could not write {}", path, e);
		}
	}

	/** Writes the current values to config/quicksave.json (used by the config screen). */
	public static void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(instance.sanitized()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			QuicksaveMod.LOGGER.warn("Could not write {}", path, e);
		}
	}

	public boolean blocks(String situation) {
		return this.blockSaveWhen.contains(situation);
	}

	public DeathCamStyle camStyle() {
		try {
			return DeathCamStyle.valueOf(this.deathCamStyle.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return DeathCamStyle.PULLBACK;
		}
	}

	private QuicksaveConfig sanitized() {
		this.quicksaveSlots = Math.max(1, this.quicksaveSlots);
		this.autosaveSlots = Math.max(1, this.autosaveSlots);
		this.autosaveIntervalMinutes = Math.max(0, this.autosaveIntervalMinutes);
		this.deathCamSeconds = Math.max(0.0, this.deathCamSeconds);
		if (this.deathTitle == null) {
			this.deathTitle = "";
		}
		if (this.deathCamStyle == null) {
			this.deathCamStyle = "pullback";
		}
		this.deathCountdownSeconds = Math.max(0, this.deathCountdownSeconds);
		this.combatRadius = Math.max(0, this.combatRadius);
		this.combatCooldownSeconds = Math.max(0, this.combatCooldownSeconds);
		if (this.blockSaveWhen == null) {
			this.blockSaveWhen = new ArrayList<>();
		}
		this.maxStorageMB = Math.max(0, this.maxStorageMB);
		return this;
	}

	public enum DeathCamStyle {
		PULLBACK, ORBIT, FIXED
	}
}
