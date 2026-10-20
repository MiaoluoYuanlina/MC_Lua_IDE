package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Built-in debugger content hosted by the same dockable frame as extension windows. */
final class LuaBreakpointWindow implements McLuaIdeWindowApi.NativeContent {
    @Override public void renderImGui() {
        for(var button:controls()){imgui.ImGui.beginDisabled(!button.enabled);NativeUi.action(button.label,()->control.accept(button.action));imgui.ImGui.endDisabled();}
        boolean pause=LuaBreakpointClient.state().pauseGame(),next=NativeUi.check("遇到断点暂停游戏（全服）",pause);if(next!=pause)LuaBreakpointClient.setPauseGame(next);
        NativeUi.action(LuaBreakpointClient.movementLabel(),()->LuaBreakpointClient.setAllowPlayerMovement(!LuaBreakpointClient.state().allowPlayerMovement()));
        NativeUi.action(LuaDebuggerCamera.label(),()->LuaDebuggerCamera.setEnabled(!LuaDebuggerCamera.enabled()));
        for(var detail:details()) {
            imgui.ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,NativeUi.rgba(detail.color));
            if(imgui.ImGui.selectable(detail.text)&&!detail.source.isBlank())navigate.accept(detail.source,detail.line);
            imgui.ImGui.popStyleColor();
        }
    }
	static final String ID = "mc_lua_ide:breakpoint_debugger";
	private static final int ROW_HEIGHT = 15, BUTTON_HEIGHT = 21, GAP = 4;
	private static final int TEXT = 0xFFCEDAEA, MUTED = 0xFF93A9C2, GOLD = 0xFFFFCF77;
	private final Font font;
	private final Consumer<LuaDebugKeyBindings.Action> control;
	private final BiConsumer<String, Integer> navigate;
	private final BooleanSupplier editable, sourceChanged;
	private int width = 288, height = 230, scroll;
	private String lastStop = "";
	private record Control(LuaDebugKeyBindings.Action action, String label, int x, int y, int width, boolean enabled) { }
	record Detail(String text, int color, String source, int line) {
		boolean link() { return line > 0 && LuaScriptNames.isValid(source); }
	}

	LuaBreakpointWindow(Font font, Consumer<LuaDebugKeyBindings.Action> control,
			BiConsumer<String, Integer> navigate, BooleanSupplier editable, BooleanSupplier sourceChanged) {
		this.font = font; this.control = control; this.navigate = navigate;
		this.editable = editable; this.sourceChanged = sourceChanged;
	}

	@Override public void resize(int width, int height) { this.width = width; this.height = height; }
	@Override public void tick() {
		var state = LuaBreakpointClient.state();
		String stop = state.id() + ":" + state.sequence();
		if (!stop.equals(lastStop)) { lastStop = stop; scroll = 0; }
	}

	private List<Control> controls() {
		var state = LuaBreakpointClient.state();
		boolean running = state.running() && state.enabled();
		int columns = Math.max(1, Math.min(3, (width + GAP) / 56));
		int cellWidth = Math.max(1, (width - (columns - 1) * GAP) / columns);
		var actions = new LuaDebugKeyBindings.Action[]{LuaDebugKeyBindings.Action.PAUSE,
				LuaDebugKeyBindings.Action.STEP_INTO, LuaDebugKeyBindings.Action.STEP_OVER,
				LuaDebugKeyBindings.Action.STEP_OUT, LuaDebugKeyBindings.Action.STOP,
				LuaDebugKeyBindings.Action.TOGGLE_BREAKPOINT, LuaDebugKeyBindings.Action.CLEAR_BREAKPOINTS};
		String[] labels = {state.paused() ? LuaUiText.text("继续") : LuaUiText.text("暂停"), LuaUiText.text("单步进入"), LuaUiText.text("单步越过"), LuaUiText.text("单步跳出"), LuaUiText.text("停止"), LuaUiText.text("切换行断点"), LuaUiText.text("清空本文件")};
		List<Control> result = new ArrayList<>();
		for (int index = 0; index < actions.length; index++) {
			var action = actions[index];
			boolean enabled = action.editorOnly ? editable.getAsBoolean()
					: running && (action == LuaDebugKeyBindings.Action.PAUSE || action == LuaDebugKeyBindings.Action.STOP || state.paused());
			result.add(new Control(action, labels[index], (index % columns) * (cellWidth + GAP),
					(index / columns) * (buttonHeight() + GAP), cellWidth, enabled));
		}
		return result;
	}

	private int detailTop() {
		return motionToggleY() + buttonHeight() + 6;
	}
	private int buttonHeight() { return height < 170 ? 14 : BUTTON_HEIGHT; }
	private int pauseToggleY() { return controls().getLast().y + buttonHeight() + GAP; }
	private int motionToggleY() { return pauseToggleY() + buttonHeight() + GAP; }
	private int visibleRows() { return Math.max(1, (height - detailTop() - 2) / ROW_HEIGHT); }
	private List<Detail> visualDetails() {
		List<Detail> result = new ArrayList<>();
		for (Detail row : details()) {
			String remaining = row.text;
			while (!remaining.isEmpty()) {
				String part = font.plainSubstrByWidth(remaining, Math.max(12, width - 10));
				if (part.isEmpty()) part = remaining.substring(0, 1);
				result.add(new Detail(part, row.color, row.source, row.line));
				remaining = remaining.substring(part.length());
			}
		}
		return result;
	}

	List<Detail> details() {
		var state = LuaBreakpointClient.state();
		List<Detail> rows = new ArrayList<>();
		add(rows, LuaUiText.text("执行状态：") + (!state.running() ? LuaUiText.text("未运行") : !state.enabled() ? LuaUiText.text("普通运行（未启用调试）") : state.paused() ? LuaUiText.text("已暂停") : LuaUiText.text("调试运行中")),
				state.paused() ? GOLD : TEXT);
		if (state.paused()) {
			rows.add(new Detail(LuaUiText.text("暂停位置：") + state.source() + ".lua:" + state.line(), GOLD, state.source(), state.line()));
			add(rows, LuaUiText.text("待执行代码：") + (state.line() == 0 ? LuaUiText.text("等待事件，暂无待执行语句") : state.code().isEmpty() ? LuaUiText.text("该行为空") : state.code()), 0xFFA9E9CE);
			add(rows, LuaUiText.text("暂停原因：") + state.reason(), GOLD);
			if (sourceChanged.getAsBoolean()) add(rows, "代码与运行版本不同；请停止后重新运行", 0xFFFF8793);
		}
		if (!state.script().isEmpty()) add(rows, LuaUiText.text("脚本：") + state.script() + ".lua", TEXT);
		add(rows, LuaUiText.text("断点暂停游戏：") + (state.pauseGame() ? LuaUiText.text("开") : LuaUiText.text("关"))
				+ (state.gamePaused() ? " · 世界已由调试冻结" : ""), state.gamePaused() ? GOLD : MUTED);
		add(rows, LuaUiText.text("服务器统一设置；开启后会暂停所有玩家的世界模拟"), 0xFFFF929F);
		add(rows, LuaBreakpointClient.movementLabel() + "（全服；仅调试冻结时）", MUTED);
		add(rows, LuaDebuggerCamera.label() + LuaUiText.text("（仅当前管理员）"), 0xFF9DDCFF);
		add(rows, "摄像机优先：移动/跳跃/潜行/疾跑控制视角，玩家不动", MUTED);
		add(rows, LuaUiText.text("提示：") + LuaBreakpointClient.status(), state.paused() ? GOLD : MUTED);
		if (!state.running() || !state.enabled()) add(rows, "使用 IDE 运行或 /lua testrun 启动调试", GOLD);
		add(rows, LuaUiText.text("调用栈 · 点击跳转"), 0xFF9CC8FF);
		if (state.stack().isEmpty()) add(rows, state.paused() ? LuaUiText.text("当前等待事件，暂无活动调用栈") : LuaUiText.text("暂停后显示调用栈"), MUTED);
		for (int index = 0; index < state.stack().size(); index++) {
			LuaDebuggerState.Frame frame = state.stack().get(index);
			rows.add(new Detail((index == 0 ? "→ " : "  ") + "#" + (index + 1) + " " + frame.name()
					+ " · " + frame.source() + ".lua:" + frame.line(), index == 0 ? GOLD : TEXT, frame.source(), frame.line()));
		}
		var files = LuaBreakpointClient.store().config().files();
		int total = files.values().stream().mapToInt(file -> file.lines().size()).sum();
		add(rows, "已设置断点：" + total + " 个 · " + files.size() + LuaUiText.text(" 个文件"), 0xFF9CC8FF);
		if (files.isEmpty()) add(rows, LuaUiText.text("单击行号栏或按快捷键添加断点"), MUTED);
		files.entrySet().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey)).forEach(entry -> {
			entry.getValue().lines().stream().sorted().forEach(line ->
					rows.add(new Detail("● " + entry.getKey() + ".lua:" + line, 0xFFFF929F, entry.getKey(), line)));
		});
		if (state.running()) {
			add(rows, "服务端断点：" + state.breakpointCount() + LuaUiText.text(" 个"), MUTED);
			add(rows, "执行 ID：" + state.id(), MUTED);
		}
		add(rows, "继续／单步／停止解除调试冻结；关闭窗口不会继续", MUTED);
		add(rows, LuaDebugKeyBindings.runHint(), MUTED);
		add(rows, LuaDebugKeyBindings.stepHint(), MUTED);
		add(rows, "F12 → … 可修改调试快捷键", MUTED);
		return rows;
	}
	private static void add(List<Detail> rows, String text, int color) { rows.add(new Detail(text, color, "", 0)); }

	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (Control button : controls()) {
			boolean hover = inside(mouseX, mouseY, button.x, button.y, button.width, buttonHeight());
			int background = !button.enabled ? 0xFF1D2733 : hover ? 0xFF3E648A
					: button.action == LuaDebugKeyBindings.Action.STOP ? 0xFF693C4B : 0xFF2A435E;
			graphics.fill(button.x, button.y, button.x + button.width, button.y + buttonHeight(), background);
			graphics.centeredText(font, font.plainSubstrByWidth(button.label, Math.max(1, button.width - 6)),
					button.x + button.width / 2, button.y + (buttonHeight() - 9) / 2, button.enabled ? 0xFFEAF2FF : 0xFF637489);
		}
		int toggleY = pauseToggleY();
		boolean toggleHover = inside(mouseX, mouseY, 0, toggleY, width, buttonHeight());
		graphics.fill(0, toggleY, width, toggleY + buttonHeight(), toggleHover ? 0xFF5A4955 : 0xFF3D3340);
		graphics.text(font, font.plainSubstrByWidth(LuaBreakpointClient.pauseGameLabel() + " · 全服", Math.max(1, width - 8)),
				4, toggleY + (buttonHeight() - 9) / 2, 0xFFFF929F);
		int half = (width - GAP) / 2;
		settingButton(graphics, mouseX, mouseY, 0, half, LuaUiText.text("玩家移动：") + (LuaBreakpointClient.movementPending() ? "…" : LuaBreakpointClient.state().allowPlayerMovement() ? LuaUiText.text("开") : LuaUiText.text("关")));
		settingButton(graphics, mouseX, mouseY, half + GAP, width - half - GAP, LuaUiText.text("摄像机：") + (LuaDebuggerCamera.enabled() ? LuaUiText.text("开") : LuaUiText.text("关")));
		List<Detail> rows = visualDetails();
		int top = detailTop(), visible = visibleRows();
		scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - visible));
		// Row rendering is bounded by the frame's scissor; do not replace that parent scissor.
		for (int index = scroll; index < Math.min(rows.size(), scroll + visible); index++) {
			Detail row = rows.get(index);
			int rowY = top + (index - scroll) * ROW_HEIGHT;
			boolean hover = row.link() && inside(mouseX, mouseY, 0, rowY, width - 8, ROW_HEIGHT);
			if (hover) graphics.fill(0, rowY, width - 8, rowY + ROW_HEIGHT, 0xFF293D54);
			graphics.text(font, font.plainSubstrByWidth(row.text, Math.max(1, width - 10)), 2, rowY + 3,
					hover ? 0xFFFFFFFF : row.color);
		}
		if (rows.size() > visible && height > top + 12) {
			int trackHeight = height - top, thumbHeight = Math.max(10, trackHeight * visible / rows.size());
			int thumbY = top + (trackHeight - thumbHeight) * scroll / Math.max(1, rows.size() - visible);
			graphics.fill(width - 4, top, width - 2, height, 0xFF293A4C);
			graphics.fill(width - 5, thumbY, width - 1, thumbY + thumbHeight, 0xFF82B6E7);
		}
	}
	private void settingButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x, int cellWidth, String label) {
		int y = motionToggleY(); boolean hover = inside(mouseX, mouseY, x, y, cellWidth, buttonHeight());
		graphics.fill(x, y, x + cellWidth, y + buttonHeight(), hover ? 0xFF3E648A : 0xFF2A435E);
		graphics.centeredText(font, font.plainSubstrByWidth(label, Math.max(1, cellWidth - 6)), x + cellWidth / 2,
				y + (buttonHeight() - 9) / 2, 0xFFB3DAF8);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return false;
		for (Control button : controls()) {
			if (inside(event.x(), event.y(), button.x, button.y, button.width, buttonHeight())) {
				if (button.enabled) control.accept(button.action);
				return true;
			}
		}
		if (inside(event.x(), event.y(), 0, pauseToggleY(), width, buttonHeight())) {
			LuaBreakpointClient.setPauseGame(!LuaBreakpointClient.state().pauseGame()); return true;
		}
		int half = (width - GAP) / 2;
		if (inside(event.x(), event.y(), 0, motionToggleY(), half, buttonHeight())) {
			LuaBreakpointClient.setAllowPlayerMovement(!LuaBreakpointClient.state().allowPlayerMovement()); return true;
		}
		if (inside(event.x(), event.y(), half + GAP, motionToggleY(), width - half - GAP, buttonHeight())) {
			LuaDebuggerCamera.setEnabled(!LuaDebuggerCamera.enabled()); return true;
		}
		if (event.y() >= detailTop() && event.y() < height && event.x() >= 0 && event.x() < width - 8) {
			List<Detail> rows = visualDetails();
			int index = scroll + (int) (event.y() - detailTop()) / ROW_HEIGHT;
			if (index < rows.size() && rows.get(index).link()) {
				Detail row = rows.get(index); navigate.accept(row.source, row.line);
			}
			return true;
		}
		return false;
	}
	private void scrollRows(int delta) { scroll = Math.clamp(scroll + delta, 0, Math.max(0, visualDetails().size() - visibleRows())); }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (vertical != 0) scrollRows(vertical > 0 ? -3 : 3);
		return true;
	}
	@Override public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case InputConstants.KEY_UP -> scrollRows(-1);
			case InputConstants.KEY_DOWN -> scrollRows(1);
			case InputConstants.KEY_PAGEUP -> scrollRows(-visibleRows());
			case InputConstants.KEY_PAGEDOWN -> scrollRows(visibleRows());
			case InputConstants.KEY_HOME -> scroll = 0;
			case InputConstants.KEY_END -> scrollRows(Integer.MAX_VALUE - scroll);
			default -> { return false; }
		}
		return true;
	}
	private static boolean inside(double x, double y, int left, int top, int width, int height) {
		return x >= left && y >= top && x < left + width && y < top + height;
	}
}
