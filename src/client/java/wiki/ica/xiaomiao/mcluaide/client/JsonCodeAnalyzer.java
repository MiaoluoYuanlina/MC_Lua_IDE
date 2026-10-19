package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight JSON diagnostics for files opened from the data-pack directory. */
final class JsonCodeAnalyzer {
	private static final Pattern LOCATION = Pattern.compile("line (\\d+) column (\\d+)");
	private static final Gson STRICT_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

	private JsonCodeAnalyzer() { }

	static LuaCodeAnalyzer.Analysis analyze(String source) {
		try {
			JsonElement parsed = STRICT_JSON.fromJson(source, JsonElement.class);
			if (parsed == null) throw new IllegalArgumentException("JSON 文件不能为空");
			return new LuaCodeAnalyzer.Analysis(List.of());
		} catch (RuntimeException error) {
			String message = error.getMessage() == null ? "无法解析 JSON" : error.getMessage();
			Matcher match = LOCATION.matcher(message);
			int line = 1;
			int column = 1;
			if (match.find()) {
				line = Math.max(1, Integer.parseInt(match.group(1)));
				column = Math.max(1, Integer.parseInt(match.group(2)));
			}
			int start = 0;
			for (int i = 1; i < line && start < source.length(); i++) {
				int newline = source.indexOf('\n', start);
				start = newline < 0 ? source.length() : newline + 1;
			}
			start = Math.min(source.length(), start + column - 1);
			if (start == source.length() && start > 0) start--;
			int end = Math.min(source.length(), start + 1);
			return new LuaCodeAnalyzer.Analysis(List.of(new LuaCodeAnalyzer.Diagnostic(
					"JSON 格式错误：" + message, start, end, line, column)));
		}
	}
}
