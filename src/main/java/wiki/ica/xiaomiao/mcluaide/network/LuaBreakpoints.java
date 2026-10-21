package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, immutable debugger configuration; contains metadata, never Lua source. */
/**
 * @param exceptions when the debugger pauses on a Lua error: never, only when no pcall/xpcall will catch it, or always
 */
public record LuaBreakpoints(Map<String, File> files, ExceptionMode exceptions) {
	public enum ExceptionMode { OFF, UNCAUGHT, ALL }
	/** Stored next to the file entries under a key no script name can have. */
	private static final String EXCEPTIONS_KEY = "#exceptions";
	public static final int MAX_FILES = 128, MAX_POINTS = 1024, MAX_LINE = 100_001;
	/** The encoded configuration must fit the control packet (100,000 characters) with room to spare. */
	public static final int MAX_ENCODED_CHARS = 96_000;

	/**
	 * What makes a breakpoint conditional. A breakpoint without an option always pauses; with options it pauses only when the
	 * {@code condition} is true and it has been reached {@code hitCount} times. A non-empty {@code logMessage} turns it into a
	 * logpoint: the message is printed to the script output (with {expression} parts evaluated) and execution continues.
	 */
	public record Option(String condition, int hitCount, String logMessage) {
		public static final int MAX_TEXT = 512, MAX_HITS = 1_000_000;
		public static final Option NONE = new Option("", 0, "");
		public Option {
			condition = condition == null ? "" : condition.strip();
			logMessage = logMessage == null ? "" : logMessage.strip();
			if (condition.length() > MAX_TEXT || logMessage.length() > MAX_TEXT) throw new IllegalArgumentException("条件或日志文本过长（最多 " + MAX_TEXT + " 个字符）");
			if (hitCount < 0 || hitCount > MAX_HITS) throw new IllegalArgumentException("命中次数无效");
		}
		/** True when this option changes nothing (no condition, no hit count above one, no log message). */
		public boolean isPlain() { return condition.isEmpty() && hitCount <= 1 && logMessage.isEmpty(); }
		public boolean logpoint() { return !logMessage.isEmpty(); }
	}

	public record File(String hash, Set<Integer> lines, Map<Integer, Option> options) {
		public File {
			final Set<Integer> copy = Set.copyOf(lines);
			lines = copy;
			Map<Integer, Option> kept = new TreeMap<>();
			if (options != null) options.forEach((line, option) -> { if (copy.contains(line) && option != null && !option.isPlain()) kept.put(line, option); });
			options = Map.copyOf(kept);
		}
		public File(String hash, Set<Integer> lines) { this(hash, lines, Map.of()); }
		public Option option(int line) { return options.getOrDefault(line, Option.NONE); }
	}
	public LuaBreakpoints { files = Map.copyOf(files); exceptions = exceptions == null ? ExceptionMode.OFF : exceptions; }
	public LuaBreakpoints(Map<String, File> files) { this(files, ExceptionMode.OFF); }
	public static LuaBreakpoints empty() { return new LuaBreakpoints(Map.of()); }
	public LuaBreakpoints withExceptions(ExceptionMode mode) { return new LuaBreakpoints(files, mode); }

	public String encode() {
		JsonObject root = new JsonObject();
		files.forEach((name, file) -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("hash", file.hash());
			JsonArray lines = new JsonArray();
			file.lines().stream().sorted().forEach(lines::add);
			entry.add("lines", lines);
			if (!file.options().isEmpty()) {
				JsonObject options = new JsonObject();
				new TreeMap<>(file.options()).forEach((line, option) -> {
					JsonObject data = new JsonObject();
					if (!option.condition().isEmpty()) data.addProperty("cond", option.condition());
					if (option.hitCount() > 1) data.addProperty("hits", option.hitCount());
					if (!option.logMessage().isEmpty()) data.addProperty("log", option.logMessage());
					options.add(Integer.toString(line), data);
				});
				entry.add("options", options);
			}
			root.add(name, entry);
		});
		if (exceptions != ExceptionMode.OFF) root.addProperty(EXCEPTIONS_KEY, exceptions.name().toLowerCase(java.util.Locale.ROOT));
		return root.toString();
	}

	public static LuaBreakpoints decode(String json) {
		try {
			if (json == null || json.length() > MAX_ENCODED_CHARS) throw new IllegalArgumentException();
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (root.size() > MAX_FILES + 1) throw new IllegalArgumentException();
			ExceptionMode exceptions = ExceptionMode.OFF;
			Map<String, File> files = new LinkedHashMap<>();
			int count = 0;
			for (var entry : root.entrySet()) {
				if (entry.getKey().equals(EXCEPTIONS_KEY)) { exceptions = ExceptionMode.valueOf(entry.getValue().getAsString().toUpperCase(java.util.Locale.ROOT)); continue; }
				LuaScriptNames.requireValid(entry.getKey());
				JsonObject data = entry.getValue().getAsJsonObject();
				String hash = data.get("hash").getAsString();
				if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
				Set<Integer> lines = new TreeSet<>();
				for (JsonElement element : data.getAsJsonArray("lines")) {
					if (++count > MAX_POINTS) throw new IllegalArgumentException();
					int line = element.getAsInt();
					if (line < 1 || line > MAX_LINE || element.getAsDouble() != line)
						throw new IllegalArgumentException();
					lines.add(line);
				}
				Map<Integer, Option> options = new TreeMap<>();
				if (data.has("options")) for (var optionEntry : data.getAsJsonObject("options").entrySet()) {
					int line = Integer.parseInt(optionEntry.getKey());
					JsonObject item = optionEntry.getValue().getAsJsonObject();
					options.put(line, new Option(item.has("cond") ? item.get("cond").getAsString() : "",
							item.has("hits") ? item.get("hits").getAsInt() : 0, item.has("log") ? item.get("log").getAsString() : ""));
				}
				if (!lines.isEmpty()) files.put(entry.getKey(), new File(hash, lines, options));
			}
			if (files.size() > MAX_FILES) throw new IllegalArgumentException();
			return new LuaBreakpoints(files, exceptions);
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("断点配置无效（最多 128 个文件、1024 个断点）", error);
		}
	}

	public static String hash(String source) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	public static String scriptName(String debugSource) {
		if (debugSource == null) return "";
		if (debugSource.startsWith("@") || debugSource.startsWith("=")) debugSource = debugSource.substring(1);
		if (!debugSource.endsWith(".lua")) return "";
		String name = debugSource.substring(0, debugSource.length() - 4);
		return LuaScriptNames.isValid(name) ? name : "";
	}
}
