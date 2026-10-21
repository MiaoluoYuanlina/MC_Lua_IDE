package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Data-only extensions: JSON manifests that contribute snippets, simple commands and terminal shells without any Java.
 * Sources: {@code config/mc_lua_ide/extensions/*.json} and {@code extensions/<name>/extension.json} (the player's own files, may add
 * shells), and {@code assets/<modid>/mc_lua_ide/extension.json} inside any installed mod (snippets and commands only, owned by that mod).
 * Command actions are limited to inserting text, copying text, showing a notice and opening a registered window: a manifest cannot run code.
 * A bad entry is reported and skipped; the rest of the file still loads.
 */
final class ExtensionManifests {
	static final int SCHEMA = 1, MAX_FILE_BYTES = 256 * 1024, MAX_SNIPPETS = 200, MAX_COMMANDS = 100, MAX_SHELLS = 20, MAX_TEXT = 8000;

	/** One manifest before parsing. {@code forcedOwner} is set for manifests inside mod jars; {@code trusted} allows shells. */
	record Source(String label, String forcedOwner, boolean trusted, String json) { }
	record Loaded(String owner, String name, String version, String source, int snippets, int commands, int shells) { }
	record Report(List<Loaded> loaded, List<String> problems) {
		String summary() {
			int snippets = loaded.stream().mapToInt(Loaded::snippets).sum(), commands = loaded.stream().mapToInt(Loaded::commands).sum(), shells = loaded.stream().mapToInt(Loaded::shells).sum();
			return LuaUiText.text("扩展清单：" + loaded.size() + " 个，片段 " + snippets + "，命令 " + commands + "，终端 " + shells + (problems.isEmpty() ? "" : "；问题 " + problems.size() + " 个（见日志）"),
					"Extension manifests: " + loaded.size() + ", snippets " + snippets + ", commands " + commands + ", shells " + shells + (problems.isEmpty() ? "" : "; " + problems.size() + " problem(s) (see the log)"));
		}
	}

	private static final Set<String> OWNERS = new HashSet<>();
	private static volatile Report last = new Report(List.of(), List.of());
	private ExtensionManifests() { }

	static Report last() { return last; }

	private static boolean everLoaded;
	/** Loads the manifests the first time it is called in this game run; true when it did. */
	static synchronized boolean loadOnce() {
		if (everLoaded) return false;
		everLoaded = true; reload(); return true;
	}

	/** Reads every manifest source from disk and (re)registers its contributions, replacing what an earlier load added. */
	static synchronized Report reload() {
		List<Source> sources = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		Path directory = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide").resolve("extensions");
		if (Files.isDirectory(directory)) {
			try (var stream = Files.list(directory)) {
				for (Path entry : stream.sorted().toList()) {
					Path file = Files.isDirectory(entry) ? entry.resolve("extension.json") : entry;
					if (!file.getFileName().toString().endsWith(".json") || !Files.isRegularFile(file)) continue;
					read(file, directory.relativize(file).toString(), null, true, sources, problems);
				}
			} catch (IOException error) { problems.add("extensions: " + error.getMessage()); }
		}
		for (var mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			var path = mod.findPath("assets/" + id + "/mc_lua_ide/extension.json");
			if (path.isPresent() && Files.isRegularFile(path.get())) read(path.get(), id + ":extension.json", id, false, sources, problems);
		}
		Report report = loadSources(sources, installedMods());
		report.problems().addAll(0, problems);
		return report;
	}

	private static Set<String> installedMods() {
		Set<String> ids = new HashSet<>();
		for (var mod : FabricLoader.getInstance().getAllMods()) ids.add(mod.getMetadata().getId());
		return ids;
	}

	private static void read(Path file, String label, String owner, boolean trusted, List<Source> sources, List<String> problems) {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
			if (bytes.length > MAX_FILE_BYTES) { problems.add(label + ": file is larger than " + MAX_FILE_BYTES / 1024 + " KB, skipped"); return; }
			sources.add(new Source(label, owner, trusted, new String(bytes, StandardCharsets.UTF_8)));
		} catch (IOException error) { problems.add(label + ": " + error.getMessage()); }
	}

	/** Pure part of the loader (no file access), also used by the smoke test. */
	static synchronized Report loadSources(List<Source> sources, Set<String> installedMods) {
		for (String owner : OWNERS) McLuaIdeEditorApi.unregisterOwner(owner);
		OWNERS.clear();
		List<Loaded> loaded = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (Source source : sources) {
			try { parse(source, installedMods, loaded, problems); }
			catch (RuntimeException error) { problems.add(source.label() + ": " + error.getMessage()); }
		}
		last = new Report(loaded, problems);
		for (String problem : problems) McLuaIdeMod.LOGGER.warn("Extension manifest: {}", problem);
		if (!loaded.isEmpty()) McLuaIdeMod.LOGGER.info("Loaded {} extension manifest(s)", loaded.size());
		return last;
	}

	private static void parse(Source source, Set<String> installedMods, List<Loaded> loaded, List<String> problems) {
		JsonObject root;
		try { root = JsonParser.parseString(source.json()).getAsJsonObject(); }
		catch (RuntimeException invalid) { problems.add(source.label() + ": not a valid JSON object"); return; }
		int schema = root.has("schema") ? number(root.get("schema"), -1) : SCHEMA;
		if (schema != SCHEMA) { problems.add(source.label() + ": unsupported schema " + schema + " (this IDE reads schema " + SCHEMA + ")"); return; }
		String owner = source.forcedOwner() != null ? source.forcedOwner() : string(root, "id");
		if (owner == null || !owner.matches("[a-z][a-z0-9_-]{1,63}")) { problems.add(source.label() + ": \"id\" must match [a-z][a-z0-9_-]{1,63}"); return; }
		if (source.forcedOwner() == null && installedMods.contains(owner)) { problems.add(source.label() + ": id \"" + owner + "\" belongs to an installed mod; choose another id"); return; }
		if (OWNERS.contains(owner)) { problems.add(source.label() + ": id \"" + owner + "\" is already used by another manifest"); return; }
		OWNERS.add(owner);
		String name = root.has("name") ? string(root, "name") : owner;
		String version = root.has("version") ? string(root, "version") : "";
		int snippets = 0, commands = 0, shells = 0;
		for (JsonElement element : array(root, "snippets", MAX_SNIPPETS, source, problems)) {
			try {
				JsonObject item = element.getAsJsonObject();
				McLuaIdeEditorApi.snippet(owner, string(item, "trigger"), item.has("description") ? string(item, "description") : "", text(item, "body"));
				snippets++;
			} catch (RuntimeException error) { problems.add(source.label() + " snippet: " + error.getMessage()); }
		}
		for (JsonElement element : array(root, "commands", MAX_COMMANDS, source, problems)) {
			try {
				JsonObject item = element.getAsJsonObject();
				var action = action(item.getAsJsonObject("action"));
				McLuaIdeEditorApi.command(owner, string(item, "id")).title(Component.literal(title(item))).shortcutHint(item.has("hint") ? string(item, "hint") : "")
						.contextMenu(item.has("context_menu") && item.get("context_menu").getAsBoolean()).run(action).register();
				commands++;
			} catch (RuntimeException error) { problems.add(source.label() + " command: " + error.getMessage()); }
		}
		JsonArray shellArray = array(root, "shells", MAX_SHELLS, source, problems);
		if (shellArray.size() > 0 && !source.trusted()) problems.add(source.label() + ": shells are only read from config/mc_lua_ide/extensions, not from mods; ignored");
		else for (JsonElement element : shellArray) {
			try {
				JsonObject item = element.getAsJsonObject();
				List<String> command = new ArrayList<>();
				for (JsonElement part : item.getAsJsonArray("command")) command.add(part.getAsString());
				McLuaIdeEditorApi.shell(owner, string(item, "id"), string(item, "name"), command);
				shells++;
			} catch (RuntimeException error) { problems.add(source.label() + " shell: " + error.getMessage()); }
		}
		loaded.add(new Loaded(owner, name, version, source.label(), snippets, commands, shells));
	}

	private static JsonArray array(JsonObject root, String key, int limit, Source source, List<String> problems) {
		if (!root.has(key)) return new JsonArray();
		JsonArray array = root.getAsJsonArray(key);
		if (array.size() > limit) {
			problems.add(source.label() + ": \"" + key + "\" has " + array.size() + " entries; only the first " + limit + " are used");
			JsonArray cut = new JsonArray(); for (int i = 0; i < limit; i++) cut.add(array.get(i)); return cut;
		}
		return array;
	}

	private static int number(JsonElement element, int fallback) { try { return element.getAsInt(); } catch (RuntimeException error) { return fallback; } }

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("missing \"" + key + "\"");
		return element.getAsString();
	}

	/** A string, or an array of lines joined with newlines (easier to write multi-line snippets in JSON). */
	private static String text(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null) throw new IllegalArgumentException("missing \"" + key + "\"");
		String value;
		if (element.isJsonArray()) {
			StringBuilder joined = new StringBuilder();
			for (JsonElement line : element.getAsJsonArray()) { if (joined.length() > 0) joined.append('\n'); joined.append(line.getAsString()); }
			value = joined.toString();
		} else value = element.getAsString();
		if (value.length() > MAX_TEXT) throw new IllegalArgumentException("\"" + key + "\" is longer than " + MAX_TEXT + " characters");
		return value;
	}

	/** {@code "title": "text"} or {@code "title": {"zh_cn": "…", "en_us": "…"}}. */
	private static String title(JsonObject item) {
		JsonElement element = item.get("title");
		if (element == null) throw new IllegalArgumentException("missing \"title\"");
		if (element.isJsonPrimitive()) return element.getAsString();
		JsonObject names = element.getAsJsonObject();
		String zh = names.has("zh_cn") ? names.get("zh_cn").getAsString() : null, en = names.has("en_us") ? names.get("en_us").getAsString() : null;
		if (zh == null && en == null) throw new IllegalArgumentException("\"title\" needs zh_cn or en_us");
		return LuaUiText.text(zh != null ? zh : en, en != null ? en : zh);
	}

	private static Consumer<McLuaIdeEditorApi.EditorContext> action(JsonObject action) {
		if (action == null) throw new IllegalArgumentException("missing \"action\"");
		String type = string(action, "type");
		return switch (type) {
			case "insert" -> { String template = text(action, "text"); yield context -> {
				if (!context.insertText(expand(template, context))) context.notice(LuaUiText.text("当前没有可编辑的文件", "No editable file is open"));
			}; }
			case "copy" -> { String template = text(action, "text"); yield context -> context.copyText(expand(template, context)); }
			case "notice" -> { String template = text(action, "text"); yield context -> context.notice(expand(template, context)); }
			case "open_window" -> { String id = string(action, "window"); yield context -> {
				if (!context.openWindow(id)) context.notice(LuaUiText.text("窗口不存在：", "Unknown window: ") + id);
			}; }
			default -> throw new IllegalArgumentException("unknown action type \"" + type + "\" (use insert, copy, notice or open_window)");
		};
	}

	/** Variables: {@code ${selection}}, {@code ${file}}, {@code ${line}}. Anything else stays as written. */
	static String expand(String template, McLuaIdeEditorApi.EditorContext context) {
		if (template.indexOf("${") < 0) return template;
		return template.replace("${selection}", context.selectedText()).replace("${file}", context.documentName()).replace("${line}", Integer.toString(context.caretLine()));
	}
}
