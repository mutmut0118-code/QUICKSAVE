package com.muto118.quicksave.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu entrypoint (only loaded when Mod Menu is installed). The screen needs
 * Cloth Config; without it Mod Menu shows no config button and the JSON file is
 * the way to configure.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return parent -> null;
		}
		// Separate class so Cloth Config classes are only loaded when it's present.
		return ClothConfigScreen::create;
	}
}
