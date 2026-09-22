package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A server-requested client notice that can safely return to the previous screen. */
final class LuaClientNoticeScreen extends AlertScreen {
	private final Screen parent;

	LuaClientNoticeScreen(Minecraft minecraft, Screen parent, Component title, Component body) {
		super(() -> minecraft.setScreen(parent), title, body);
		this.parent = parent;
	}

	Screen parent() {
		return parent;
	}
}
