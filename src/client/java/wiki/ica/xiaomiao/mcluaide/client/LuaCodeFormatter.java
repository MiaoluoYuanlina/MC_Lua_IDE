package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;
import java.io.StringWriter;

final class LuaCodeFormatter {
	private LuaCodeFormatter() { }
	static boolean indentationOnly(String before, String after) {
		int a = 0, b = 0;
		while (true) {
			while (a < before.length() && (before.charAt(a) == ' ' || before.charAt(a) == '\t')) a++;
			while (b < after.length() && (after.charAt(b) == ' ' || after.charAt(b) == '\t')) b++;
			if (a == before.length() || b == after.length()) return a == before.length() && b == after.length();
			while (true) {
				if (a == before.length() || b == after.length()) return a == before.length() && b == after.length();
				if (before.charAt(a) != after.charAt(b)) return false;
				if (before.charAt(a++) == '\n') { b++; break; }
				b++;
			}
		}
	}
	static int mapCaret(String before, String after, int caret) {
		int oldStart = before.lastIndexOf('\n', Math.max(0, caret) - 1) + 1;
		int newStart = 0;
		for (int i = 0; i < oldStart; i++) if (before.charAt(i) == '\n') {
			int end = after.indexOf('\n', newStart);
			if (end < 0) return after.length();
			newStart = end + 1;
		}
		int oldIndent = oldStart, newIndent = newStart;
		while (oldIndent < before.length() && (before.charAt(oldIndent) == ' ' || before.charAt(oldIndent) == '\t')) oldIndent++;
		while (newIndent < after.length() && (after.charAt(newIndent) == ' ' || after.charAt(newIndent) == '\t')) newIndent++;
		int lineEnd = after.indexOf('\n', newStart);
		return Math.clamp(newIndent + Math.max(0, caret - oldIndent), newStart, lineEnd < 0 ? after.length() : lineEnd);
	}
	static String format(String source, String name, boolean jsonDocument) throws java.io.IOException {
		if (!jsonDocument) return LuaIndentation.formatDocument(source);
		if (name.endsWith(".mcfunction")) {
			// Minecraft commands are line-oriented, not Lua blocks.
			return source.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"))
					+ (source.endsWith("\n") ? "\n" : "");
		}
		if (!(name.endsWith(".json") || name.endsWith(".mcmeta")))
			throw new IllegalArgumentException("当前文件不支持整理代码；支持 Lua、JSON、mcmeta 和 mcfunction");
		var value = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(source, JsonElement.class);
		if (value == null) throw new IllegalArgumentException("JSON 文件不能为空");
		StringWriter output = new StringWriter();
		try (JsonWriter writer = new JsonWriter(output)) {
			writer.setIndent("    ");
			new GsonBuilder().disableHtmlEscaping().create().toJson(value, writer);
		}
		return output + (source.endsWith("\n") ? "\n" : "");
	}
}
