package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;
import java.nio.file.Path;
import java.util.*;

/**
 * Per-world/server and player metadata, including breakpoints in closed module tabs, their conditions / hit counts / log messages
 * and the watch expressions.
 */
final class LuaBreakpointStore {
	static final int MAX_WATCHES = 40;
	private final Path file;
	private final Map<String, LuaBreakpoints.File> files = new LinkedHashMap<>();
	private final Map<String, String> hashes = new HashMap<>();
	private final List<String> watches = new ArrayList<>();
	private LuaBreakpoints.ExceptionMode exceptions = LuaBreakpoints.ExceptionMode.OFF;
	LuaBreakpointStore(Path sessionFile) {
		file = sessionFile.resolveSibling(sessionFile.getFileName() + "-breakpoints.properties");
		Properties saved = LuaIdeSession.readProperties(file);
		try { files.putAll(LuaBreakpoints.decode(saved.getProperty("breakpoints", "{}")).files()); }
		catch (IllegalArgumentException ignored) { }
		files.forEach((name, data) -> hashes.put(name, data.hash()));
		try {
			for (var element : JsonParser.parseString(saved.getProperty("watches", "[]")).getAsJsonArray())
				if (watches.size() < MAX_WATCHES && !element.getAsString().isBlank() && element.getAsString().length() <= 512) watches.add(element.getAsString().strip());
		} catch (RuntimeException ignored) { }
		try { exceptions = LuaBreakpoints.ExceptionMode.valueOf(saved.getProperty("exceptions", "OFF")); } catch (IllegalArgumentException ignored) { }
	}
	Set<Integer> lines(String name) {
		LuaBreakpoints.File entry = files.get(name);
		return entry == null ? Set.of() : entry.lines();
	}
	Map<Integer, LuaBreakpoints.Option> options(String name) {
		LuaBreakpoints.File entry = files.get(name);
		return entry == null ? Map.of() : entry.options();
	}
	LuaBreakpoints.Option option(String name, int line) {
		LuaBreakpoints.File entry = files.get(name);
		return entry == null ? LuaBreakpoints.Option.NONE : entry.option(line);
	}
	String hash(String name) { return hashes.getOrDefault(name, ""); }
	LuaBreakpoints config() { return new LuaBreakpoints(files, exceptions); }
	/** When the debugger pauses on a Lua error (sent to the server with the breakpoints). */
	LuaBreakpoints.ExceptionMode exceptions() { return exceptions; }
	void setExceptions(LuaBreakpoints.ExceptionMode mode) { exceptions = mode == null ? LuaBreakpoints.ExceptionMode.OFF : mode; }
	List<String> watches() { return List.copyOf(watches); }
	boolean addWatch(String expression) {
		String text = expression == null ? "" : expression.strip();
		if (text.isEmpty() || text.length() > 512 || watches.contains(text) || watches.size() >= MAX_WATCHES) return false;
		watches.add(text); return true;
	}
	void removeWatch(String expression) { watches.remove(expression); }
	void save() {
		Properties data = new Properties(); data.setProperty("breakpoints", config().encode());
		JsonArray array = new JsonArray(); watches.forEach(array::add);
		data.setProperty("watches", array.toString());
		data.setProperty("exceptions", exceptions.name());
		LuaIdeSession.writeProperties(file, data);
	}
	private void requireRoom(String name) {
		int total = files.values().stream().mapToInt(f -> f.lines().size()).sum();
		if (total >= LuaBreakpoints.MAX_POINTS || (!files.containsKey(name) && files.size() >= LuaBreakpoints.MAX_FILES))
			throw new IllegalStateException("断点数量已达到上限：128 个文件 / 1024 个断点");
	}
	boolean toggle(String name, int line, String source) {
		Set<Integer> lines = new TreeSet<>(lines(name));
		if (!lines.remove(line)) { requireRoom(name); lines.add(line); }
		put(name, lines, source, options(name)); return lines.contains(line);
	}
	/** Sets the conditions of the breakpoint on a line, creating the breakpoint when there is none yet. */
	void setOption(String name, int line, LuaBreakpoints.Option option, String source) {
		Set<Integer> lines = new TreeSet<>(lines(name));
		if (lines.add(line)) requireRoom(name);
		Map<Integer, LuaBreakpoints.Option> options = new TreeMap<>(options(name));
		if (option.isPlain()) options.remove(line); else options.put(line, option);
		Map<String, LuaBreakpoints.File> candidate = new LinkedHashMap<>(files);
		candidate.put(name, new LuaBreakpoints.File(LuaBreakpoints.hash(source), lines, options));
		if (new LuaBreakpoints(candidate).encode().length() > LuaBreakpoints.MAX_ENCODED_CHARS)
			throw new IllegalStateException("断点条件文本总量过大，请删除一些条件");
		put(name, lines, source, options);
	}
	void clear(String name) { files.remove(name); }
	void source(String name, String source) { put(name, lines(name), source, options(name)); }
	private void put(String name, Set<Integer> lines, String source, Map<Integer, LuaBreakpoints.Option> options) {
		String hash = LuaBreakpoints.hash(source); hashes.put(name, hash);
		if (lines.isEmpty()) files.remove(name);
		else files.put(name, new LuaBreakpoints.File(hash, lines, options));
	}

	/** Keep line anchors with their text, rather than letting Enter move the wrong marker. Options move with their lines. */
	void edit(String name, String before, String after) {
		if (before.equals(after) || LuaCodeFormatter.indentationOnly(before, after)) { source(name, after); return; }
		int prefix = 0, suffix = 0;
		while (prefix < before.length() && prefix < after.length()
				&& before.charAt(prefix) == after.charAt(prefix)) prefix++;
		while (suffix < before.length() - prefix && suffix < after.length() - prefix
				&& before.charAt(before.length() - suffix - 1) == after.charAt(after.length() - suffix - 1)) suffix++;
		int oldEnd = before.length() - suffix, delta = after.length() - before.length();
		Set<Integer> moved = new TreeSet<>();
		Map<Integer, LuaBreakpoints.Option> movedOptions = new TreeMap<>();
		Map<Integer, LuaBreakpoints.Option> previous = options(name);
		for (int line : lines(name)) {
			int start = lineStart(before, line);
			if (start < 0) continue;
			if (start >= oldEnd) start += delta;
			else if (start >= prefix) {
				// A replaced line retains a marker, but a deleted whole line does not.
				int end = before.indexOf('\n', start);
				if (end >= 0 && oldEnd > end && after.length() - suffix == prefix) continue;
				start = prefix;
			}
			start = Math.clamp(start, 0, after.length());
			int newLine = 1;
			for (int i = 0; i < start; i++) if (after.charAt(i) == '\n') newLine++;
			moved.add(newLine);
			if (previous.containsKey(line)) movedOptions.put(newLine, previous.get(line));
		}
		put(name, moved, after, movedOptions);
	}
	static int lineStart(String source, int line) {
		int offset = 0;
		for (int current = 1; current < line; current++) {
			offset = source.indexOf('\n', offset);
			if (offset < 0) return -1;
			offset++;
		}
		return offset;
	}
}
