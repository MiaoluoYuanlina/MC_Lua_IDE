package wiki.ica.xiaomiao.mcluaide.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu entry point for server-owned configuration. */
public final class McLuaIdeModMenuApi implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return LuaSettingsScreen::new;
	}
}
