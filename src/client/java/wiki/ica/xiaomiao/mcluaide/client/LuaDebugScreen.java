package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** F12 switches; breakpoint controls and key editing live in the adjacent subpage. */
final class LuaDebugScreen extends Screen {
	private final Screen previous;
	private Button variableToggle, logToggle;
	private Button breakpointToggle, keySettingsButton;

	LuaDebugScreen(Screen previous) {
		super(Component.literal(LuaUiText.text("Lua 调试")));
		this.previous = previous;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(390, width - 24);
		int left = (width - panelWidth) / 2;
		int top = (height - 224) / 2;
		variableToggle = addRenderableWidget(Button.builder(Component.empty(), button -> {
			LuaDebugHud.setVariablesEnabled(!LuaDebugHud.variablesEnabled());
			updateToggle();
		}).bounds(left + 15, top + 46, panelWidth - 30, 23).build());
		breakpointToggle = addRenderableWidget(Button.builder(Component.empty(), button -> {
			LuaBreakpointClient.setShowInfo(!LuaBreakpointClient.showInfo()); updateToggle();
		}).bounds(left + 15, top + 74, panelWidth - 58, 23).build());
		keySettingsButton = addRenderableWidget(Button.builder(Component.literal("…"),
				button -> minecraft.setScreen(new LuaDebugKeysScreen(this)))
				.bounds(left + panelWidth - 39, top + 74, 24, 23)
				.tooltip(Tooltip.create(Component.literal(LuaUiText.text("断点控制与快捷键 · 自定义按键 / F 键及小键盘预设")))).build());
		logToggle = addRenderableWidget(Button.builder(Component.empty(), button -> {
			LuaLogHud.setEnabled(!LuaLogHud.enabled()); updateToggle();
		}).bounds(left + 15, top + 102, panelWidth - 30, 23)
				.tooltip(Tooltip.create(Component.literal(LuaUiText.text("游戏画面显示脚本输出、错误和停止状态；普通运行也可显示，不影响游戏操作")))).build());
		addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("位置调整…")),
				button -> minecraft.setScreen(new LuaHudLayoutScreen(this)))
				.bounds(left + 15, top + 130, panelWidth - 30, 23)
				.tooltip(Tooltip.create(Component.literal(LuaUiText.text("预览全部游戏内调试浮窗；拖动移动、边框缩放，右键调整背景透明度；自动保存，不改变显示开关")))).build());
		updateToggle();
		addRenderableWidget(Button.builder(Component.literal(previous instanceof LuaIdeScreen ? "返回 IDE" : "返回游戏"), button -> onClose())
				.bounds(left + panelWidth - 105, top + 193, 90, 22).build());
	}

	private void updateToggle() {
		variableToggle.setMessage(Component.literal(LuaUiText.text("显示 Lua 变量值：") +
				(LuaDebugHud.variablesEnabled() ? LuaUiText.text("开") : LuaUiText.text("关"))));
		breakpointToggle.setMessage(Component.literal(LuaUiText.text("断点信息显示：") + (LuaBreakpointClient.showInfo() ? LuaUiText.text("开") : LuaUiText.text("关"))));
		logToggle.setMessage(Component.literal(LuaUiText.text("显示 Lua 日志：") + (LuaLogHud.enabled() ? LuaUiText.text("开") : LuaUiText.text("关"))));
	}
	boolean editorActionAvailable() { return previous instanceof LuaIdeScreen ide && ide.isBreakpointDocument(); }
	void perform(LuaDebugKeyBindings.Action action) {
		if (action.editorOnly) {
			if (previous instanceof LuaIdeScreen ide) ide.performDebuggerAction(action);
		} else LuaBreakpointClient.control(action.command);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.blurBeforeThisStratum();
		graphics.fill(0, 0, width, height, 0x8A090C13);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelWidth = Math.min(390, width - 24);
		int left = (width - panelWidth) / 2;
		int top = (height - 224) / 2;
		graphics.fill(left, top, left + panelWidth, top + 224, 0xE91B2029);
		graphics.fill(left, top, left + panelWidth, top + 2, 0xFF8FA7FF);
		graphics.text(font, title, left + 15, top + 15, 0xFFF1F3FF);
		graphics.text(font, Component.literal(LuaUiText.text("F12 打开或关闭此面板")), left + 15, top + 30, 0xFFADB6C8);
		graphics.text(font, font.plainSubstrByWidth(LuaBreakpointClient.status(), panelWidth - 30),
				left + 15, top + 162, 0xFFFFCB82);
		graphics.text(font, LuaBreakpointClient.state().pauseGame() ? "断点会冻结世界模拟 · 影响所有玩家" : "断点只暂停脚本 · … 可设置暂停游戏",
				left + 15, top + 178, LuaBreakpointClient.state().pauseGame() ? 0xFFFF929F : 0xFFADB6C8);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_F12) {
			onClose();
			return true;
		}
		var action = LuaDebugKeyBindings.match(event);
		if (action != null) { perform(action); return true; }
		return super.keyPressed(event);
	}

	@Override public void onClose() { minecraft.setScreen(previous); }
	@Override public boolean isPauseScreen() { return false; }
}
