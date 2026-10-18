package com.seolwoni.farmassist.compat;

import com.seolwoni.farmassist.gui.FarmConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FarmConfigScreen::new;
	}
}
