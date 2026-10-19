package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, immutable debugger configuration; contains metadata, never Lua source. */
public record LuaBreakpoints(Map<String, File> files) {
	public static final int MAX_FILES = 128, MAX_POINTS = 1024, MAX_LINE = 100_001;
	public record File(String hash, Set<Integer> lines) {
		public File { lines = Set.copyOf(lines); }
	}
	public LuaBreakpoints { files = Map.copyOf(files); }
	public static LuaBreakpoints empty() { return new LuaBreakpoints(Map.of()); }

	public String encode() {
		JsonObject root = new JsonObject();
		files.forEach((name, file) -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("hash", file.hash());
			JsonArray lines = new JsonArray();
			file.lines().stream().sorted().forEach(lines::add);
			entry.add("lines", lines); root.add(name, entry);
		});
		return root.toString();
	}

	public static LuaBreakpoints decode(String json) {
		try {
			if (json == null || json.length() > 65_536) throw new IllegalArgumentException();
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (root.size() > MAX_FILES) throw new IllegalArgumentException();
			Map<String, File> files = new LinkedHashMap<>();
			int count = 0;
			for (var entry : root.entrySet()) {
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
				if (!lines.isEmpty()) files.put(entry.getKey(), new File(hash, lines));
			}
			return new LuaBreakpoints(files);
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
