package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Root settings page. Server-owned categories can be added here without crowding the script directory. */
public final class LuaSettingsScreen extends Screen {
	private final Screen previousScreen;
	private String mcpNotice = "";

	public LuaSettingsScreen(Screen previousScreen) {
		super(Component.literal(LuaUiText.text("MC Lua IDE 设置")));
		this.previousScreen = previousScreen;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(360, width - 40);
		int left = (width - panelWidth) / 2;
		boolean connected = minecraft.getConnection() != null;
		Button limits = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("Lua 限制…")), button ->
				minecraft.setScreen(new LuaLimitsScreen(this)))
				.bounds(left, 54, panelWidth, 24).build());
		Button access = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("服务器接入…")), button ->
				minecraft.setScreen(new ServerAccessSettingsScreen(this)))
				.bounds(left, 86, panelWidth, 24).build());
		limits.active = connected;
		access.active = connected;
		Button mcp = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("MCP 服务器（服务端）…")), button -> {
			mcpNotice = "正在检查管理员权限…";
			ScriptManager.checkAccess().whenComplete((ignored, failure) -> minecraft.execute(() -> {
				if (failure != null) { mcpNotice = ScriptManager.message(failure); return; }
				LuaIdeScreen ide = previousScreen instanceof LuaIdeScreen existing ? existing : new LuaIdeScreen(this, "");
				minecraft.setScreen(ide); ide.setExtensionWindowOpen(LuaMcpWindow.SERVER_ID, true);
			}));
		}).bounds(left, 118, panelWidth, 24).build());
		mcp.active = connected;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(width / 2 - 50, height - 34, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal(minecraft.getConnection() == null
				? "请先进入目标服务器，再由管理员修改服务器全局配置"
				: "服务端全局设置：所有管理员与脚本统一使用同一份配置"),
				width / 2, 34, 0xFFFF5555);
		graphics.centeredText(font, Component.literal(LuaUiText.text("Lua 限制：控制新启动脚本的资源、API 和文件权限")),
				width / 2, 158, 0xFF888888);
		graphics.centeredText(font, Component.literal(LuaUiText.text("服务器接入：选择普通玩家是否必须安装客户端模组")),
				width / 2, 172, 0xFF888888);
		graphics.centeredText(font, Component.literal(LuaUiText.text("MCP：端口、启停、访问令牌和调用日志；默认关闭")), width / 2, 186, 0xFF888888);
		if (!mcpNotice.isBlank()) graphics.centeredText(font, font.plainSubstrByWidth(mcpNotice, width - 20), width / 2, height - 50, 0xFFFF777F);
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
