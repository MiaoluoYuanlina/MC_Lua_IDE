package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Go to definition, find references and rename across the workspace's Lua scripts (see {@link LuaWorkspaceIndex}). The editor asks
 * this first; it answers only for globals and members of required modules, and leaves locals to the per-file tools.
 * Scripts that are not open are read from the server (open tabs win, with their unsaved text). A rename shows a preview first:
 * open files are changed in their tabs (unsaved), closed files are saved with a conflict check.
 */
final class CrossFileActions implements NativeCodeArea.CrossFile {
	private static final int MAX_FILES = 500;
	private final EditorHost host;
	private final Consumer<String> notice;
	private List<LuaWorkspaceIndex.Location> results = List.of();
	private String resultsTitle = "";
	private boolean showResults, busy;
	private Map<String, String> renamed = Map.of();
	private Map<String, Integer> renameCounts = Map.of();
	private Map<String, String> renameOriginals = Map.of();
	private String renameLabel = "";
	private boolean showRename;

	CrossFileActions(EditorHost host, Consumer<String> notice) { this.host = host; this.notice = notice; }

	private LuaWorkspaceIndex.Target target(String source, int offset) {
		var doc = host.currentDocument();
		if (doc == null || !doc.lua()) return null;
		return LuaWorkspaceIndex.targetAt(LuaWorkspaceIndex.File.of(doc.path, source), offset);
	}

	/** Builds the index from every Lua script: open tabs as edited, the rest as saved on the server. */
	private CompletableFuture<LuaWorkspaceIndex> index() {
		Map<String, String> sources = new LinkedHashMap<>();
		for (var doc : host.openDocuments()) if (doc.lua()) { doc.capture(); sources.put(doc.path, doc.source); }
		List<String> missing = new ArrayList<>();
		for (var entry : host.tree(ImGuiIdeDocument.Kind.LUA)) {
			if (entry.directory() || sources.containsKey(entry.path())) continue;
			if (sources.size() + missing.size() >= MAX_FILES) break;
			missing.add(entry.path());
		}
		List<CompletableFuture<Void>> reads = new ArrayList<>();
		Map<String, String> loaded = new java.util.concurrent.ConcurrentHashMap<>();
		for (String path : missing) reads.add(ScriptManager.peek(path).handle((text, error) -> { if (text != null) loaded.put(path, text); return null; }));
		return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
			List<LuaWorkspaceIndex.File> files = new ArrayList<>();
			sources.forEach((path, text) -> files.add(LuaWorkspaceIndex.File.of(path, text)));
			loaded.forEach((path, text) -> files.add(LuaWorkspaceIndex.File.of(path, text)));
			return new LuaWorkspaceIndex(files);
		});
	}

	private void run(LuaWorkspaceIndex.Target target, Consumer<LuaWorkspaceIndex> action) {
		if (busy) return;
		busy = true;
		notice.accept(LuaUiText.text("正在分析工作区中的脚本…", "Indexing the workspace scripts…"));
		index().whenComplete((index, error) -> Minecraft.getInstance().execute(() -> {
			busy = false;
			if (error != null) { notice.accept(ScriptManager.message(error)); return; }
			action.accept(index);
		}));
	}

	private void open(LuaWorkspaceIndex.Location location) { host.openAt(ImGuiIdeDocument.Kind.LUA, location.path(), location.start(), location.end()); }

	private static String describe(LuaWorkspaceIndex.Target target) { return target.member() ? target.module() + "." + target.name() : target.name(); }

	@Override public boolean definition(String source, int offset) {
		var target = target(source, offset);
		if (target == null) return false;
		run(target, index -> {
			var found = index.definitions(target);
			if (found.isEmpty()) notice.accept(LuaUiText.text("没有在工作区脚本中找到 ", "No definition in the workspace scripts for ") + describe(target)
					+ LuaUiText.text("（可能是内置 API 或运行时才创建的）", " (it may be a built-in API or created at runtime)"));
			else if (found.size() == 1) { open(found.getFirst()); notice.accept(""); }
			else show(LuaUiText.text("定义：", "Definitions: ") + describe(target), found);
		});
		return true;
	}

	@Override public boolean references(String source, int offset) {
		var target = target(source, offset);
		if (target == null) return false;
		run(target, index -> {
			var found = index.references(target);
			show(LuaUiText.text("引用：", "References: ") + describe(target) + " · " + found.size(), found);
		});
		return true;
	}

	@Override public boolean spansFiles(String source, int offset) { return target(source, offset) != null; }

	@Override public boolean rename(String source, int offset, String newName) {
		var target = target(source, offset);
		if (target == null || !LuaSymbolIndex.validName(newName)) return false;
		run(target, index -> {
			var edits = index.renameEdits(target, newName);
			if (edits == null || edits.isEmpty()) { notice.accept(LuaUiText.text("没有可重命名的位置", "Nothing to rename")); return; }
			Map<String, String> next = new LinkedHashMap<>(), originals = new LinkedHashMap<>();
			Map<String, Integer> counts = new LinkedHashMap<>();
			edits.forEach((path, list) -> {
				String original = index.file(path).source();
				originals.put(path, original); next.put(path, LuaSymbolIndex.apply(original, list)); counts.put(path, list.size());
			});
			renamed = next; renameCounts = counts; renameOriginals = originals;
			renameLabel = describe(target) + " → " + newName; showRename = true; notice.accept("");
		});
		return true;
	}

	private void show(String title, List<LuaWorkspaceIndex.Location> found) {
		results = found; resultsTitle = title; showResults = true; notice.accept("");
	}

	/** Draws the result list and the rename preview; call once per frame. */
	void draw() {
		if (showResults) { ImGui.openPopup("cross_file_results"); showResults = false; }
		ImGui.setNextWindowSize(640, 360, imgui.flag.ImGuiCond.Appearing);
		if (ImGui.beginPopupModal("cross_file_results", ImGuiWindowFlags.NoTitleBar)) {
			ImGui.textUnformatted(resultsTitle);
			ImGui.separator();
			ImGui.beginChild("cross_file_list", 0, -ImGui.getFrameHeightWithSpacing() - 4, true);
			for (int i = 0; i < results.size(); i++) {
				var location = results.get(i);
				if (ImGui.selectable(location.path() + ".lua:" + location.line() + "   " + location.preview() + "##cf" + i)) { open(location); ImGui.closeCurrentPopup(); }
			}
			if (results.isEmpty()) ImGui.textDisabled(LuaUiText.text("没有结果", "No results"));
			ImGui.endChild();
			if (ImGui.button(LuaUiText.text("关闭", "Close")) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
		if (showRename) { ImGui.openPopup("cross_file_rename"); showRename = false; }
		if (ImGui.beginPopupModal("cross_file_rename", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.AlwaysAutoResize)) {
			ImGui.textUnformatted(LuaUiText.text("跨文件重命名：", "Rename across files: ") + renameLabel);
			ImGui.separator();
			renameCounts.forEach((path, count) -> ImGui.bulletText(path + ".lua · " + count + LuaUiText.text(" 处", " places")
					+ (openDocument(path) != null ? LuaUiText.text("（已打开，改为未保存）", " (open: changed, not saved)") : LuaUiText.text("（未打开，将直接保存）", " (closed: saved directly)"))));
			ImGui.spacing();
			if (ImGui.button(LuaUiText.text("应用", "Apply"))) { apply(); ImGui.closeCurrentPopup(); }
			ImGui.sameLine();
			if (ImGui.button(LuaUiText.text("取消", "Cancel")) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
	}

	private ImGuiIdeDocument openDocument(String path) {
		for (var doc : host.openDocuments()) if (doc.lua() && doc.path.equals(path)) return doc;
		return null;
	}

	private void apply() {
		int saved = 0, edited = 0;
		for (var entry : renamed.entrySet()) {
			var doc = openDocument(entry.getKey());
			if (doc != null) { doc.external(entry.getValue()); edited++; continue; }
			// Saved against the version the edits were computed from, so a concurrent change is refused instead of overwritten.
			ScriptManager.rememberBase(entry.getKey(), renameOriginals.get(entry.getKey()));
			String path = entry.getKey();
			ScriptManager.save(path, entry.getValue()).whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
				if (error != null) notice.accept(path + ".lua: " + (ScriptManager.isConflict(error) ? ScriptManager.conflictText(error) : ScriptManager.message(error)));
			}));
			saved++;
		}
		notice.accept(LuaUiText.text("已重命名：" + edited + " 个打开的文件（未保存），" + saved + " 个文件已保存",
				"Renamed: " + edited + " open file(s) changed (not saved), " + saved + " file(s) saved"));
	}
}
