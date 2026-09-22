package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.net.URI;
import java.util.Locale;

/** Applies validated client-only requests received from the connected server. */
final class LuaClientCommandHandler {
	private LuaClientCommandHandler() {
	}

	static void handle(Minecraft client, LuaProtocol.ClientCommand command) {
		LuaProtocol.ClientAction action;
		try {
			action = LuaProtocol.ClientAction.byId(command.actionId());
		} catch (IllegalArgumentException error) {
			McLuaIdeMod.LOGGER.warn("Ignored unknown MC Lua IDE client action {}", command.actionId());
			return;
		}
		switch (action) {
			case MESSAGE -> {
				if (client.player != null) client.player.sendSystemMessage(component(client, command.first()));
			}
			case TOAST -> client.getToastManager().addToast(SystemToast.multiline(client,
					new SystemToast.SystemToastId(), component(client, command.first()),
					component(client, command.second())));
			case NOTICE -> client.setScreen(new LuaClientNoticeScreen(
					client, client.screen, component(client, command.first()), component(client, command.second())));
			case CLOSE_NOTICE -> {
				if (client.screen instanceof LuaClientNoticeScreen notice) client.setScreen(notice.parent());
			}
			case OPEN_URL_PROMPT -> openUrlPrompt(client, command.first());
			case OPEN_IDE -> McLuaIdeClient.openIde(client);
		}
	}

	private static Component component(Minecraft client, String raw) {
		if (client.level == null || raw == null || raw.isBlank()) return Component.literal(raw == null ? "" : raw);
		String value = raw.stripLeading();
		if (!value.startsWith("{") && !value.startsWith("[") && !value.startsWith("\"")) {
			return Component.literal(raw);
		}
		try {
			return ComponentSerialization.CODEC.parse(
					RegistryOps.create(JsonOps.INSTANCE, client.level.registryAccess()), JsonParser.parseString(raw))
					.getOrThrow();
		} catch (RuntimeException error) {
			McLuaIdeMod.LOGGER.warn("Ignored malformed client text component", error);
			return Component.literal(raw);
		}
	}

	private static void openUrlPrompt(Minecraft client, String raw) {
		URI uri;
		try {
			uri = URI.create(raw);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) return;
		} catch (IllegalArgumentException error) {
			return;
		}
		Screen parent = client.screen;
		client.setScreen(new ConfirmLinkScreen(accepted -> {
			if (accepted) Util.getPlatform().openUri(uri);
			client.setScreen(parent);
		}, Component.literal("服务器脚本请求打开链接"), uri, true));
	}
}
