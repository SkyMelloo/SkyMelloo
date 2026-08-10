package com.melloo.skymelloo.client.integration;

import com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SkyMellooSettingsScreen::new;
	}
}
