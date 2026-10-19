package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.EnumMap;

/** Compact subpage: a control button and an editable chord per action. */
final class LuaDebugKeysScreen extends Screen {
	private final LuaDebugScreen previous;
	private final LuaDebugKeyBindings.Settings settings = LuaDebugKeyBindings.settings();
	private final EnumMap<LuaDebugKeyBindings.Action, Button> controlButtons = new EnumMap<>(LuaDebugKeyBindings.Action.class);
	private final EnumMap<LuaDebugKeyBindings.Action, Button> keyButtons = new EnumMap<>(LuaDebugKeyBindings.Action.class);
	private LuaDebugKeyBindings.Action capturing;
	private Button pauseGameToggle;
	private Button movementToggle, cameraToggle;
	private String notice = "点击快捷键后按下新按键；支持 Ctrl / Shift / Alt";
	private int noticeColor = 0xFFADB6C8;
	LuaDebugKeysScreen(LuaDebugScreen previous) { super(Component.literal(LuaUiText.text("断点控制与快捷键"))); this.previous = previous; }
	private int panelHeight() { return Math.min(300, Math.max(220, height - 8)); }

	@Override protected void init() {
		controlButtons.clear(); keyButtons.clear();
		int panelWidth = Math.min(430, width - 24), left = (width - panelWidth) / 2, top = (height - panelHeight()) / 2;
		int contentWidth = panelWidth - 30, half = (contentWidth - 5) / 2;
		pauseGameToggle = addRenderableWidget(Button.builder(Component.empty(), button ->
				LuaBreakpointClient.setPauseGame(!LuaBreakpointClient.state().pauseGame()))
				.bounds(left + 15, top + 25, contentWidth, 18)
				.tooltip(Tooltip.create(Component.literal(LuaUiText.text("服务端统一开关，影响所有玩家的世界模拟；继续、停止或关闭开关后释放调试冻结。不会解除原有 /tick freeze。")))).build());
		movementToggle = addRenderableWidget(Button.builder(Component.empty(), button ->
				LuaBreakpointClient.setAllowPlayerMovement(!LuaBreakpointClient.state().allowPlayerMovement()))
				.bounds(left + 15, top + 46, half, 18)
				.tooltip(Tooltip.create(Component.literal(LuaUiText.text("服务器统一设置，默认开启：调试冻结世界时仍允许玩家移动。关闭后限制所有玩家移动；不影响独立的 /tick freeze。")))).build());
		cameraToggle = addRenderableWidget(Button.builder(Component.empty(), button -> LuaDebuggerCamera.setEnabled(!LuaDebuggerCamera.enabled()))
				.bounds(left + 20 + half, top + 46, half, 18)
				.tooltip(Tooltip.create(Component.literal(LuaUiText.text("仅当前管理员客户端，自动保存。脚本暂停时使用自由摄像机，玩家留在原地；移动/跳跃/潜行/疾跑键控制飞行，鼠标转动视角。继续、停止、关闭开关或断开连接后退出。")))).build());
		addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("预设：F 键")), button -> applyPreset(LuaDebugKeyBindings.Preset.FUNCTION_KEYS))
				.bounds(left + 15, top + 94, half, 17).build());
		addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("预设：小键盘")), button -> applyPreset(LuaDebugKeyBindings.Preset.NUMPAD))
				.bounds(left + 20 + half, top + 94, half, 17).build());
		int actionWidth = Math.max(108, contentWidth * 43 / 100), keyX = left + 18 + actionWidth;
		int keyWidth = contentWidth - actionWidth - 25, index = 0;
		int rowStep = Math.clamp((panelHeight() - 150) / 7, 11, 18), rowHeight = rowStep - 2;
		for (var action : LuaDebugKeyBindings.Action.values()) {
			int y = top + 127 + index++ * rowStep;
			controlButtons.put(action, addRenderableWidget(Button.builder(Component.literal(action.label), button -> previous.perform(action))
					.bounds(left + 15, y, actionWidth, rowHeight)
					.tooltip(Tooltip.create(Component.literal(action.editorOnly ? LuaUiText.text("操作 IDE 当前 Lua 文件") : LuaUiText.text("操作服务端调试脚本")))).build()));
			keyButtons.put(action, addRenderableWidget(Button.builder(Component.empty(), button -> {
				capturing = action; notice = "正在设置“" + action.label + "”：按键确认，Esc 取消，退格/Delete 取消绑定";
				noticeColor = 0xFFFFCB82; refreshLabels();
			}).bounds(keyX, y, keyWidth, rowHeight).tooltip(Tooltip.create(Component.literal(LuaUiText.text("点击后按新快捷键，可搭配 Ctrl / Shift / Alt")))).build()));
			addRenderableWidget(Button.builder(Component.literal("×"), button -> assign(action, -1, 0))
					.bounds(left + panelWidth - 33, y, 18, rowHeight).tooltip(Tooltip.create(Component.literal(LuaUiText.text("取消此项快捷键绑定")))).build());
		}
		addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("返回")), button -> onClose())
				.bounds(left + panelWidth - 85, top + panelHeight() - 23, 70, 18).build());
		refreshLabels(); tick();
	}
	private void applyPreset(LuaDebugKeyBindings.Preset preset) {
		capturing = null; settings.apply(preset);
		persist("已应用" + (preset == LuaDebugKeyBindings.Preset.FUNCTION_KEYS ? " F 键" : "小键盘") + "预设");
		refreshLabels();
	}
	private void assign(LuaDebugKeyBindings.Action action, int key, int modifiers) {
		try {
			settings.assign(action, key, modifiers); capturing = null;
			persist("已设置“" + action.label + "”：" + settings.binding(action).label());
			String conflict = key < 0 ? "" : minecraftConflict(key, modifiers);
			if (!conflict.isEmpty()) { notice += " · 同时用于 " + conflict; noticeColor = 0xFFFFCB82; }
		} catch (IllegalArgumentException error) { notice = error.getMessage(); noticeColor = 0xFFFF7777; }
		refreshLabels();
	}
	private String minecraftConflict(int key, int modifiers) {
		KeyEvent keyEvent = new KeyEvent(key, 0, modifiers);
		for (var mapping : minecraft.options.keyMappings) if (mapping.matches(keyEvent))
			return Component.translatable(mapping.getName()).getString();
		return "";
	}
	private void persist(String success) {
		boolean saved = settings.save(); notice = saved ? success : "快捷键已生效，但保存失败，请检查客户端配置目录权限";
		noticeColor = saved ? 0xFF9CDBB1 : 0xFFFF7777;
	}
	private void refreshLabels() {
		keyButtons.forEach((action, button) -> button.setMessage(Component.literal(capturing == action ? "请按新快捷键…" : settings.binding(action).label())));
	}
	@Override public void tick() {
		var state = LuaBreakpointClient.state();
		pauseGameToggle.setMessage(Component.literal(LuaBreakpointClient.pauseGameLabel()));
		pauseGameToggle.active = capturing == null && !LuaBreakpointClient.pauseGamePending();
		movementToggle.setMessage(Component.literal(LuaBreakpointClient.movementLabel()));
		movementToggle.active = capturing == null && !LuaBreakpointClient.movementPending();
		cameraToggle.setMessage(Component.literal(LuaUiText.text("自由摄像机：") + (LuaDebuggerCamera.enabled() ? LuaUiText.text("开") : LuaUiText.text("关"))));
		cameraToggle.active = capturing == null;
		controlButtons.forEach((action, button) -> {
			button.active = capturing == null && (action.editorOnly ? previous.editorActionAvailable()
					: state.running() && state.enabled() && (action == LuaDebugKeyBindings.Action.PAUSE || action == LuaDebugKeyBindings.Action.STOP || state.paused()));
			if (action == LuaDebugKeyBindings.Action.PAUSE) button.setMessage(Component.literal(state.paused() ? "继续脚本" : "暂停脚本"));
		});
	}
	@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.blurBeforeThisStratum(); graphics.fill(0, 0, width, height, 0x8A090C13);
	}
	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelWidth = Math.min(430, width - 24), left = (width - panelWidth) / 2, top = (height - panelHeight()) / 2;
		graphics.fill(left, top, left + panelWidth, top + panelHeight(), 0xE91B2029);
		graphics.fill(left, top, left + panelWidth, top + 2, 0xFF8FA7FF);
		graphics.text(font, title, left + 15, top + 10, 0xFFF1F3FF);
		graphics.text(font, font.plainSubstrByWidth("世界/玩家移动：全服设置 · 自由摄像机：仅本客户端", panelWidth - 30), left + 15, top + 68, 0xFFFF929F);
		var state = LuaBreakpointClient.state();
		String code = state.paused() ? state.line() == 0 ? "等待事件回调" : state.code().strip() : "脚本暂停后显示待执行代码";
		graphics.text(font, font.plainSubstrByWidth("代码：" + code, panelWidth - 30), left + 15, top + 80, 0xFFA9E9CE);
		graphics.text(font, LuaUiText.text("执行操作"), left + 15, top + 114, 0xFFADB6C8);
		graphics.text(font, LuaUiText.text("快捷键（点击修改 · 自动保存）"), left + 18 + Math.max(108, (panelWidth - 30) * 43 / 100), top + 114, 0xFFADB6C8);
		String feedback = LuaBreakpointClient.status().contains("设置失败") || LuaBreakpointClient.status().startsWith("无法设置") || LuaBreakpointClient.status().startsWith("自由摄像机未开启")
				? LuaBreakpointClient.status() : notice;
		graphics.text(font, font.plainSubstrByWidth(feedback, panelWidth - 120), left + 15, top + panelHeight() - 18, noticeColor);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
	@Override public boolean keyPressed(KeyEvent event) {
		if (capturing != null) {
			if (event.key() == InputConstants.KEY_ESCAPE) {
				capturing = null; notice = "已取消修改"; noticeColor = 0xFFADB6C8; refreshLabels(); return true;
			}
			if (LuaDebugKeyBindings.modifierKey(event.key())) return true;
			if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE) assign(capturing, -1, 0);
			else assign(capturing, event.key(), event.modifiers());
			return true;
		}
		if (event.key() == InputConstants.KEY_F12) { onClose(); return true; }
		var action = LuaDebugKeyBindings.match(event);
		if (action != null) { previous.perform(action); return true; }
		return super.keyPressed(event);
	}
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (capturing != null) { capturing = null; refreshLabels(); tick(); }
		return super.mouseClicked(event, doubleClick);
	}
	@Override public void onClose() { capturing = null; minecraft.setScreen(previous); }
	@Override public boolean isPauseScreen() { return false; }
}
