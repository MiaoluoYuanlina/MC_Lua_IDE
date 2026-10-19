package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;
import java.nio.file.Path;
import java.util.*;

/** Per-world/server and player metadata, including breakpoints in closed module tabs. */
final class LuaBreakpointStore {
	private final Path file;
	private final Map<String, LuaBreakpoints.File> files = new LinkedHashMap<>();
	private final Map<String, String> hashes = new HashMap<>();
	LuaBreakpointStore(Path sessionFile) {
		file = sessionFile.resolveSibling(sessionFile.getFileName() + "-breakpoints.properties");
		String json = LuaIdeSession.readProperties(file).getProperty("breakpoints", "{}");
		try { files.putAll(LuaBreakpoints.decode(json).files()); }
		catch (IllegalArgumentException ignored) { }
		files.forEach((name, data) -> hashes.put(name, data.hash()));
	}
	Set<Integer> lines(String name) {
		LuaBreakpoints.File entry = files.get(name);
		return entry == null ? Set.of() : entry.lines();
	}
	String hash(String name) { return hashes.getOrDefault(name, ""); }
	LuaBreakpoints config() { return new LuaBreakpoints(files); }
	void save() {
		Properties data = new Properties(); data.setProperty("breakpoints", config().encode());
		LuaIdeSession.writeProperties(file, data);
	}
	boolean toggle(String name, int line, String source) {
		Set<Integer> lines = new TreeSet<>(lines(name));
		if (!lines.remove(line)) {
			int total = files.values().stream().mapToInt(f -> f.lines().size()).sum();
			if (total >= LuaBreakpoints.MAX_POINTS || (!files.containsKey(name) && files.size() >= LuaBreakpoints.MAX_FILES))
				throw new IllegalStateException("断点数量已达到上限：128 个文件 / 1024 个断点");
			lines.add(line);
		}
		put(name, lines, source); return lines.contains(line);
	}
	void clear(String name) { files.remove(name); }
	void source(String name, String source) { put(name, lines(name), source); }
	private void put(String name, Set<Integer> lines, String source) {
		String hash = LuaBreakpoints.hash(source); hashes.put(name, hash);
		if (lines.isEmpty()) files.remove(name);
		else files.put(name, new LuaBreakpoints.File(hash, lines));
	}

	/** Keep line anchors with their text, rather than letting Enter move the wrong marker. */
	void edit(String name, String before, String after) {
		if (before.equals(after) || LuaCodeFormatter.indentationOnly(before, after)) { source(name, after); return; }
		int prefix = 0, suffix = 0;
		while (prefix < before.length() && prefix < after.length()
				&& before.charAt(prefix) == after.charAt(prefix)) prefix++;
		while (suffix < before.length() - prefix && suffix < after.length() - prefix
				&& before.charAt(before.length() - suffix - 1) == after.charAt(after.length() - suffix - 1)) suffix++;
		int oldEnd = before.length() - suffix, delta = after.length() - before.length();
		Set<Integer> moved = new TreeSet<>();
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
		}
		put(name, moved, after);
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
