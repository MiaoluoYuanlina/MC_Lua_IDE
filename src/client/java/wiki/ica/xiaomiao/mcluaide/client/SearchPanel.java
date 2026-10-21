package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find (and replace) across the workspace: Lua scripts, datapack files and resource-pack text files. Files are read one after the
 * other through the same permission-checked channel as the editor, open unsaved tabs are searched as they are on screen, and the
 * search is bounded so a huge project can never freeze the game.
 */
final class SearchPanel {
	private static final int MAX_FILES = 800, MAX_HITS = 5000, MAX_HITS_PER_FILE = 500, MAX_FILE_CHARS = 2_000_000;
	private record Target(ImGuiIdeDocument.Kind kind, String path) { }
	private record Hit(int start, int end, int line, String preview) { }
	private record FileResult(ImGuiIdeDocument.Kind kind, String path, List<Hit> hits) { }

	private final EditorHost host;
	private final ImString query = new ImString(256), replacement = new ImString(256);
	private boolean caseSensitive, wholeWord, regex, scopeLua = true, scopeDatapack = true, scopeResource;
	private final List<FileResult> results = new ArrayList<>();
	private int token, totalHits, scanned, totalFiles;
	private boolean running, focusRequest, confirmRequest;
	private String status = "", error = "";

	SearchPanel(EditorHost host) { this.host = host; }

	void focusQuery() { focusRequest = true; }
	/** Starts a search for {@code text} (used by "find in files" on a selection). */
	void searchFor(String text) { query.set(text); start(); }

	private EditorSearch.Options options() { return new EditorSearch.Options(caseSensitive, wholeWord, regex); }

	private List<Target> collect() {
		List<Target> targets = new ArrayList<>();
		for (var kind : ImGuiIdeDocument.Kind.values()) {
			boolean enabled = switch (kind) { case LUA -> scopeLua; case DATAPACK -> scopeDatapack; case RESOURCEPACK -> scopeResource; };
			if (!enabled) continue;
			for (var entry : host.tree(kind)) {
				if (entry.directory() || targets.size() >= MAX_FILES) continue;
				String path = entry.path().toLowerCase(Locale.ROOT);
				boolean text = kind == ImGuiIdeDocument.Kind.LUA || path.endsWith(".json") || path.endsWith(".mcfunction") || path.endsWith(".mcmeta") || path.endsWith(".txt") || path.endsWith(".lua");
				if (text) targets.add(new Target(kind, entry.path()));
			}
		}
		return targets;
	}

	private void start() {
		int run = ++token;
		results.clear(); totalHits = scanned = 0; error = ""; status = "";
		if (query.get().isEmpty()) return;
		try { EditorSearch.compile(query.get(), options()); }
		catch (EditorSearch.QueryException problem) { error = problem.getMessage(); return; }
		var targets = collect();
		totalFiles = targets.size(); running = true;
		scan(run, targets, 0);
	}

	private void scan(int run, List<Target> targets, int index) {
		if (run != token) return;
		if (index >= targets.size()) { running = false; status = LuaUiText.text("完成：", "Done: ") + totalHits + LuaUiText.text(" 处，", " matches in ") + results.size() + LuaUiText.text(" 个文件", " files"); return; }
		var target = targets.get(index);
		for (var doc : host.openDocuments()) if (doc.kind == target.kind() && doc.path.equals(target.path())) { process(run, targets, index, doc.source); return; }
		host.deliver(host.read(target.kind(), target.path()), text -> process(run, targets, index, text), message -> process(run, targets, index, ""));
	}

	private void process(int run, List<Target> targets, int index, String text) {
		if (run != token) return;
		scanned++;
		if (text.length() <= MAX_FILE_CHARS && !text.isEmpty()) {
			try {
				var matches = EditorSearch.findAll(text, query.get(), options());
				if (!matches.isEmpty()) {
					List<Hit> hits = new ArrayList<>();
					for (var match : matches) {
						if (hits.size() >= MAX_HITS_PER_FILE) break;
						int lineStart = text.lastIndexOf('\n', Math.max(0, match.start() - 1)) + 1, lineEnd = text.indexOf('\n', match.start());
						if (match.start() == 0) lineStart = 0;
						String preview = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd).strip();
						hits.add(new Hit(match.start(), match.end(), LuaSymbolIndex.lineOf(text, match.start()) + 1, preview.length() > 160 ? preview.substring(0, 160) + "…" : preview));
					}
					results.add(new FileResult(targets.get(index).kind(), targets.get(index).path(), hits));
					totalHits += hits.size();
				}
			} catch (EditorSearch.QueryException problem) { running = false; error = problem.getMessage(); return; }
		}
		if (totalHits >= MAX_HITS) { running = false; status = LuaUiText.text("结果过多，已只显示前 ", "Too many results; showing the first ") + totalHits; return; }
		scan(run, targets, index + 1);
	}

	// ---- replace in files --------------------------------------------------------------------------------------------------

	private void replaceAllInFiles() {
		int run = ++token;
		running = true; error = "";
		var files = new ArrayList<>(results);
		results.clear();
		replaceNext(run, files, 0, new int[3]); // replacements, files changed, skipped
	}

	private void replaceNext(int run, List<FileResult> files, int index, int[] counters) {
		if (run != token) return;
		if (index >= files.size()) {
			running = false;
			status = LuaUiText.text("已替换 " + counters[0] + " 处，涉及 " + counters[1] + " 个文件" + (counters[2] > 0 ? "；跳过 " + counters[2] + " 个有未保存修改的文件" : ""),
					"Replaced " + counters[0] + " in " + counters[1] + " files" + (counters[2] > 0 ? "; skipped " + counters[2] + " with unsaved edits" : ""));
			return;
		}
		var file = files.get(index);
		for (var doc : host.openDocuments()) if (doc.kind == file.kind() && doc.path.equals(file.path()) && doc.dirty()) { counters[2]++; replaceNext(run, files, index + 1, counters); return; }
		host.deliver(host.read(file.kind(), file.path()), text -> {
			try {
				var result = EditorSearch.replaceAll(text, query.get(), options(), replacement.get());
				if (result.count() == 0) { replaceNext(run, files, index + 1, counters); return; }
				host.deliver(host.write(file.kind(), file.path(), result.text()), ignored -> {
					counters[0] += result.count(); counters[1]++; replaceNext(run, files, index + 1, counters);
				}, message -> { error = message; replaceNext(run, files, index + 1, counters); });
			} catch (EditorSearch.QueryException problem) { running = false; error = problem.getMessage(); }
		}, message -> { error = message; replaceNext(run, files, index + 1, counters); });
	}

	// ---- drawing ------------------------------------------------------------------------------------------------------------

	private boolean toggle(String label, boolean value, String tooltip) {
		if (value) ImGui.pushStyleColor(ImGuiCol.Button, ImGui.getColorU32(ImGuiCol.ButtonActive));
		boolean pressed = ImGui.smallButton(label);
		if (value) ImGui.popStyleColor();
		if (ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
		return pressed ? !value : value;
	}

	void draw() {
		if (focusRequest) { ImGui.setKeyboardFocusHere(); focusRequest = false; }
		ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 200));
		boolean enter = ImGui.inputTextWithHint("##find_in_files", LuaUiText.text("在所有文件中查找", "Find in all files"), query, ImGuiInputTextFlags.EnterReturnsTrue);
		ImGui.sameLine(); caseSensitive = toggle("Aa", caseSensitive, LuaUiText.text("区分大小写", "Match case"));
		ImGui.sameLine(); wholeWord = toggle("W", wholeWord, LuaUiText.text("全字匹配", "Whole word"));
		ImGui.sameLine(); regex = toggle(".*", regex, LuaUiText.text("正则表达式", "Regular expression"));
		ImGui.sameLine(); if (ImGui.button(LuaUiText.text("搜索", "Search")) || enter) start();
		ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 200));
		ImGui.inputTextWithHint("##replace_in_files", LuaUiText.text("替换为", "Replace with"), replacement);
		ImGui.sameLine();
		ImGui.beginDisabled(results.isEmpty() || running);
		if (ImGui.button(LuaUiText.text("全部替换…", "Replace all…"))) confirmRequest = true;
		ImGui.endDisabled();
		scopeLua = NativeUi.check(LuaUiText.text("Lua 脚本", "Lua scripts"), scopeLua);
		ImGui.sameLine(); scopeDatapack = NativeUi.check(LuaUiText.text("数据包", "Datapacks"), scopeDatapack);
		ImGui.sameLine(); scopeResource = NativeUi.check(LuaUiText.text("资源包", "Resource packs"), scopeResource);
		if (!error.isEmpty()) ImGui.textColored(1f, .45f, .4f, 1f, error);
		if (running) ImGui.textDisabled(LuaUiText.text("正在搜索… ", "Searching… ") + scanned + "/" + totalFiles + " · " + totalHits);
		else if (!status.isEmpty()) ImGui.textDisabled(status);
		ImGui.separator();
		ImGui.beginChild("search_results", 0, 0, false);
		for (int i = 0; i < results.size(); i++) {
			var file = results.get(i);
			ImGui.pushID(i);
			String title = file.path() + (file.kind() == ImGuiIdeDocument.Kind.LUA ? ".lua" : "") + "  (" + file.hits().size() + ")";
			if (ImGui.collapsingHeader(title + "##file", ImGuiTreeNodeFlags.DefaultOpen)) {
				for (int h = 0; h < file.hits().size(); h++) {
					var hit = file.hits().get(h);
					if (ImGui.selectable(hit.line() + "   " + hit.preview() + "##hit" + h)) host.openAt(file.kind(), file.path(), hit.start(), hit.end());
				}
			}
			ImGui.popID();
		}
		ImGui.endChild();
		if (confirmRequest) { ImGui.openPopup("confirm_replace_all##search"); confirmRequest = false; }
		if (ImGui.beginPopupModal("confirm_replace_all##search", ImGuiWindowFlags.AlwaysAutoResize)) {
			ImGui.textWrapped(LuaUiText.text("将把 " + totalHits + " 处匹配替换为“" + replacement.get() + "”，涉及 " + results.size() + " 个文件。这会直接写入服务器文件，有未保存修改的已打开文件会被跳过。",
					"Replace " + totalHits + " matches with \"" + replacement.get() + "\" in " + results.size() + " files. Files are written directly; open files with unsaved edits are skipped."));
			if (ImGui.button(LuaUiText.text("替换", "Replace"))) { replaceAllInFiles(); ImGui.closeCurrentPopup(); }
			ImGui.sameLine(); if (ImGui.button(LuaUiText.text("取消", "Cancel"))) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
	}
}
