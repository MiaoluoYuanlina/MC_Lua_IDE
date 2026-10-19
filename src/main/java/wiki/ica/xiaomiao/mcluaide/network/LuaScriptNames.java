package wiki.ica.xiaomiao.mcluaide.network;

import java.util.regex.Pattern;

/** Validated, slash-separated paths beneath the server's Lua script directory. */
public final class LuaScriptNames {
	public static final int MAX_PATH_LENGTH = 200;
	private static final int MAX_DEPTH = 6;
	private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]{1,48}");

	private LuaScriptNames() { }

	public static boolean isValid(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_PATH_LENGTH) return false;
		String[] parts = name.split("/", -1);
		if (parts.length > MAX_DEPTH) return false;
		for (String part : parts) if (!PART.matcher(part).matches()) return false;
		return true;
	}

	public static String requireValid(String name) {
		if (!isValid(name)) throw new IllegalArgumentException(
				"脚本路径仅能用 / 分隔的字母、数字、下划线和连字符；每层 1-48 字符，最多 6 层");
		return name;
	}

	/** Accepts require("shared.math") or require("shared/math"), optionally with .lua. */
	public static String moduleName(String requested) {
		if (requested == null) throw new IllegalArgumentException("模块名称不能为空");
		String name = requested.strip();
		if (name.endsWith(".lua")) name = name.substring(0, name.length() - 4);
		if (!name.contains("/")) name = name.replace('.', '/');
		return requireValid(name);
	}
}
