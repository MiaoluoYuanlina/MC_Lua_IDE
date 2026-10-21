package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;

/** The outline of the current Lua file: its functions as a tree, filterable, click to jump. */
final class OutlinePanel {
	private final EditorHost host;
	private final ImString filter = new ImString(64);

	OutlinePanel(EditorHost host) { this.host = host; }

	void draw() {
		var doc = host.currentDocument();
		var index = host.codeArea().symbolIndex();
		if (doc == null || doc.binary() || !doc.lua() || index == null) {
			ImGui.textWrapped(LuaUiText.text("打开一个 Lua 文件以查看它的大纲。", "Open a Lua file to see its outline."));
			return;
		}
		ImGui.setNextItemWidth(-1);
		ImGui.inputTextWithHint("##outline_filter", LuaUiText.text("筛选函数…", "Filter functions…"), filter);
		String needle = filter.get().strip().toLowerCase(java.util.Locale.ROOT);
		var entries = index.outline();
		ImGui.textDisabled(LuaUiText.text("函数 ", "Functions ") + entries.size());
		ImGui.separator();
		ImGui.beginChild("outline_list", 0, 0, false);
		int shown = 0;
		for (int i = 0; i < entries.size(); i++) {
			var entry = entries.get(i);
			if (!needle.isEmpty() && !entry.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
			shown++;
			ImGui.pushID(i);
			ImGui.indent(needle.isEmpty() ? entry.depth() * 14 : 0);
			String prefix = entry.kind() == LuaSymbolIndex.Kind.LOCAL_FUNCTION ? "local " : "";
			int line = LuaSymbolIndex.lineOf(doc.source, entry.offset()) + 1;
			if (ImGui.selectable(prefix + entry.name() + "##entry")) host.codeArea().goToOffset(entry.offset());
			ImGui.sameLine(Math.max(160, ImGui.getContentRegionMaxX() - 52));
			ImGui.textDisabled(":" + line);
			ImGui.unindent(needle.isEmpty() ? entry.depth() * 14 : 0);
			ImGui.popID();
		}
		if (shown == 0) ImGui.textDisabled(LuaUiText.text("没有匹配的函数", "No matching functions"));
		ImGui.endChild();
	}
}
