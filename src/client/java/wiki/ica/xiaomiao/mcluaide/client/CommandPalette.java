package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.List;

/**
 * One box for everything, in the style of modern editors: plain text finds files (Ctrl+P), a leading {@code >} runs commands
 * (Ctrl+Shift+P), {@code @} jumps to a symbol of the current file (Ctrl+Shift+O) and {@code :} goes to a line.
 */
final class CommandPalette {
	record Entry(String label, String detail, Runnable action) { }
	interface Source {
		List<Entry> files();
		List<Entry> commands();
		List<Entry> symbols();
		void goToLine(int line);
	}

	private static final int MAX_ROWS = 14;
	private final Source source;
	private final ImString text = new ImString(256);
	private boolean open, focus;
	private int selection, framesOpen;

	CommandPalette(Source source) { this.source = source; }

	boolean isOpen() { return open; }
	void close() { open = false; }
	/** Opens with some starting text: "" for files, ">" for commands, "@" for symbols, ":" for a line number. */
	void open(String initial) { open = true; focus = true; text.set(initial); selection = 0; framesOpen = 0; }

	private enum Mode { FILES, COMMANDS, SYMBOLS, LINE }
	private Mode mode() {
		String value = text.get();
		if (value.startsWith(">")) return Mode.COMMANDS;
		if (value.startsWith("@")) return Mode.SYMBOLS;
		if (value.startsWith(":")) return Mode.LINE;
		return Mode.FILES;
	}

	void draw() {
		if (!open) return;
		var viewport = ImGui.getMainViewport();
		ImGui.setNextWindowPos(viewport.getCenterX(), viewport.getPosY() + 70, ImGuiCond.Always, .5f, 0);
		ImGui.setNextWindowSize(Math.min(620, viewport.getSizeX() - 40), 0, ImGuiCond.Always);
		ImGui.setNextWindowFocus();
		boolean visible = ImGui.begin("##command_palette", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
				| ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoScrollbar);
		if (visible) {
			if (focus) { ImGui.setKeyboardFocusHere(); focus = false; }
			ImGui.setNextItemWidth(-1);
			String before = text.get();
			ImGui.inputTextWithHint("##palette_input", LuaUiText.text("转到文件 · > 命令 · @ 符号 · : 行号", "Go to file · > command · @ symbol · : line"), text);
			if (!before.equals(text.get())) selection = 0;
			var mode = mode();
			String query = mode == Mode.FILES ? text.get() : text.get().substring(1).strip();
			List<Entry> entries = mode == Mode.LINE ? List.of() : FuzzyMatcher.rank(query,
					mode == Mode.FILES ? source.files() : mode == Mode.COMMANDS ? source.commands() : source.symbols(), e -> e.label() + " " + e.detail(), 200);
			int count = entries.size();
			selection = count == 0 ? 0 : Math.clamp(selection, 0, count - 1);
			boolean activate = ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter);
			if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) selection = count == 0 ? 0 : (selection + 1) % count;
			if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) selection = count == 0 ? 0 : (selection - 1 + count) % count;
			if (mode == Mode.LINE) {
				ImGui.textDisabled(LuaUiText.text("输入行号后按 Enter 转到该行", "Type a line number and press Enter"));
				if (activate) {
					try { source.goToLine(Integer.parseInt(query.strip())); close(); } catch (NumberFormatException ignored) { }
				}
			} else if (count == 0) ImGui.textDisabled(LuaUiText.text("没有匹配项", "No matches"));
			else {
				ImGui.beginChild("palette_list", 0, Math.min(count, MAX_ROWS) * (ImGui.getTextLineHeightWithSpacing() + 2) + 6, false);
				for (int i = 0; i < count; i++) {
					var entry = entries.get(i);
					ImGui.pushID(i);
					if (ImGui.selectable(entry.label() + "##row", i == selection)) { selection = i; activate = true; }
					if (!entry.detail().isEmpty()) { ImGui.sameLine(Math.max(250, ImGui.getContentRegionMaxX() - ImGui.calcTextSize(entry.detail()).x - 8)); ImGui.textDisabled(entry.detail()); }
					if (i == selection && (ImGui.isKeyPressed(ImGuiKey.DownArrow) || ImGui.isKeyPressed(ImGuiKey.UpArrow))) ImGui.setScrollHereY();
					ImGui.popID();
				}
				ImGui.endChild();
				if (activate) { var chosen = entries.get(selection); close(); chosen.action().run(); }
			}
			// Clicking anywhere else dismisses it, like a menu.
			if (++framesOpen > 3 && !ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)) close();
		}
		ImGui.end();
	}
}
