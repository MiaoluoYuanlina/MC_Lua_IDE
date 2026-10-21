package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiTableBgTarget;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * Side-by-side comparison of the current file with its saved version or with another open file. Changed lines are tinted
 * (red removed, green added) and the toolbar jumps between changes.
 */
final class DiffPanel {
	private static final int REMOVED = 0x505050E0, ADDED = 0x5060C050, EMPTY = 0x20808080;
	private final EditorHost host;
	private int target;               // 0 = the saved version, otherwise an index into the other open documents
	private String cacheKey = "";
	private List<TextDiff.Pair> rows = List.of();
	private List<Integer> changeStarts = List.of();
	private int added, removed, currentChange = -1, scrollToRow = -1;

	DiffPanel(EditorHost host) { this.host = host; }

	private List<ImGuiIdeDocument> others(ImGuiIdeDocument current) {
		List<ImGuiIdeDocument> list = new ArrayList<>();
		for (var doc : host.openDocuments()) if (doc != current && !doc.binary()) list.add(doc);
		return list;
	}

	void draw() {
		var doc = host.currentDocument();
		if (doc == null || doc.binary()) { ImGui.textWrapped(LuaUiText.text("打开一个文本文件后可以与保存版本或其他文件对比。", "Open a text file to compare it with its saved version or another file.")); return; }
		var others = others(doc);
		if (target > others.size()) target = 0;
		String leftName = target == 0 ? LuaUiText.text("已保存版本", "Saved version") : others.get(target - 1).filename();
		ImGui.setNextItemWidth(Math.min(300, ImGui.getContentRegionAvailX() * .5f));
		if (ImGui.beginCombo("##diff_target", LuaUiText.text("对比：", "Compare with: ") + leftName)) {
			if (ImGui.selectable(LuaUiText.text("已保存版本", "Saved version"), target == 0)) target = 0;
			for (int i = 0; i < others.size(); i++) if (ImGui.selectable(others.get(i).filename() + "##other" + i, target == i + 1)) target = i + 1;
			ImGui.endCombo();
		}
		String left = target == 0 ? doc.saved : others.get(target - 1).source, right = doc.source;
		String key = doc.key() + ":" + doc.revision + ":" + target + ":" + (target == 0 ? doc.saved.hashCode() : left.hashCode());
		if (!key.equals(cacheKey)) {
			cacheKey = key;
			var diff = TextDiff.diff(left, right);
			rows = TextDiff.sideBySide(diff);
			changeStarts = new ArrayList<>();
			boolean inChange = false;
			for (int i = 0; i < rows.size(); i++) { boolean changed = rows.get(i).changed(); if (changed && !inChange) changeStarts.add(i); inChange = changed; }
			added = (int) diff.stream().filter(l -> l.kind() == TextDiff.Kind.INSERT).count();
			removed = (int) diff.stream().filter(l -> l.kind() == TextDiff.Kind.DELETE).count();
			currentChange = -1;
		}
		ImGui.sameLine(); ImGui.beginDisabled(changeStarts.isEmpty());
		if (ImGui.smallButton("↑##prev_change")) { currentChange = currentChange <= 0 ? changeStarts.size() - 1 : currentChange - 1; scrollToRow = changeStarts.get(currentChange); }
		ImGui.sameLine(); if (ImGui.smallButton("↓##next_change")) { currentChange = (currentChange + 1) % changeStarts.size(); scrollToRow = changeStarts.get(currentChange); }
		ImGui.endDisabled();
		ImGui.sameLine();
		if (changeStarts.isEmpty()) ImGui.textDisabled(LuaUiText.text("没有差异", "No differences"));
		else ImGui.textDisabled(changeStarts.size() + LuaUiText.text(" 处改动 · ", " changes · ") + "+" + added + " −" + removed);
		if (ImGui.beginTable("diff_table", 4, ImGuiTableFlags.ScrollY | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable)) {
			ImGui.tableSetupScrollFreeze(0, 1);
			ImGui.tableSetupColumn("#", ImGuiTableColumnFlags.WidthFixed, 42);
			ImGui.tableSetupColumn(leftName, ImGuiTableColumnFlags.WidthStretch);
			ImGui.tableSetupColumn("#", ImGuiTableColumnFlags.WidthFixed, 42);
			ImGui.tableSetupColumn(doc.filename(), ImGuiTableColumnFlags.WidthStretch);
			ImGui.tableHeadersRow();
			float rowHeight = ImGui.getTextLineHeight() + ImGui.getStyle().getCellPaddingY() * 2;
			if (scrollToRow >= 0) { ImGui.setScrollY(Math.max(0, (scrollToRow - 2) * rowHeight)); scrollToRow = -1; }
			var clipper = new ImGuiListClipper();
			try {
				clipper.begin(rows.size(), rowHeight);
				while (clipper.step()) for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
					var pair = rows.get(i);
					ImGui.tableNextRow();
					ImGui.tableSetColumnIndex(0); lineNumber(pair.left());
					ImGui.tableSetColumnIndex(1); cell(pair.left(), pair.left() != null && pair.left().kind() == TextDiff.Kind.DELETE ? REMOVED : pair.left() == null ? EMPTY : 0, 1);
					ImGui.tableSetColumnIndex(2); lineNumber(pair.right());
					ImGui.tableSetColumnIndex(3); cell(pair.right(), pair.right() != null && pair.right().kind() == TextDiff.Kind.INSERT ? ADDED : pair.right() == null ? EMPTY : 0, 3);
				}
			} finally { clipper.destroy(); }
			ImGui.endTable();
		}
	}

	private void lineNumber(TextDiff.Line line) {
		if (line == null) return;
		int number = (line.kind() == TextDiff.Kind.INSERT ? line.newLine() : line.oldLine()) + 1;
		ImGui.textDisabled(Integer.toString(number));
	}

	private void cell(TextDiff.Line line, int background, int column) {
		if (background != 0) ImGui.tableSetBgColor(ImGuiTableBgTarget.CellBg, background, column);
		if (line != null) ImGui.textUnformatted(line.text());
	}
}
