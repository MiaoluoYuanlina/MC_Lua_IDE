package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Root settings page. Server-owned categories can be added here without crowding the script directory. */
public final class LuaSettingsScreen extends Screen {
	private final Screen previousScreen;

	public LuaSettingsScreen(Screen previousScreen) {
		super(Component.literal("MC Lua IDE 设置"));
		this.previousScreen = previousScreen;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(360, width - 40);
		int left = (width - panelWidth) / 2;
		addRenderableWidget(Button.builder(Component.literal("Lua 限制…"), button ->
				minecraft.setScreen(new LuaLimitsScreen(this)))
				.bounds(left, 54, panelWidth, 24).build());
		addRenderableWidget(Button.builder(Component.literal("服务器接入…"), button ->
				minecraft.setScreen(new ServerAccessSettingsScreen(this)))
				.bounds(left, 86, panelWidth, 24).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(width / 2 - 50, height - 34, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("服务端全局设置：所有管理员与脚本统一使用同一份配置"),
				width / 2, 34, 0xFFFF5555);
		graphics.centeredText(font, Component.literal("Lua 限制：控制新启动脚本的资源、API 和文件权限"),
				width / 2, 122, 0xFF888888);
		graphics.centeredText(font, Component.literal("服务器接入：选择普通玩家是否必须安装客户端模组"),
				width / 2, 136, 0xFF888888);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(previousScreen);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
