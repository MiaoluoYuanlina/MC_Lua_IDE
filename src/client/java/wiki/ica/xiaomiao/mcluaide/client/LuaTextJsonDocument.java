package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.luaj.vm2.compiler.LuaC;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The version-specific Minecraft text-component document produced by the visual editor. */
final class LuaTextJsonDocument {
	private static final Pattern GRAPHEME = Pattern.compile("\\X");
	private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");

	enum Kind {
		TEXT("普通文字"), SCORE("积分榜分数"), SELECTOR("目标选择器"), TRANSLATE("翻译键"), KEYBIND("按键名称"),
		LUA_VARIABLE("Lua 变量");
		final String label;
		Kind(String label) { this.label = label; }
	}

	enum Hover {
		NONE("无"), TEXT("显示文字"), ITEM("显示物品"), ENTITY("显示实体");
		final String label;
		Hover(String label) { this.label = label; }
	}

	enum Click {
		NONE("无"), RUN_COMMAND("执行命令"), SUGGEST_COMMAND("填入命令"), OPEN_URL("打开网址"),
		COPY("复制文字"), CHANGE_PAGE("翻页"), SHOW_DIALOG("打开对话框");
		final String label;
		Click(String label) { this.label = label; }
	}

	static final class Segment {
		Kind kind = Kind.TEXT;
		String text = "示例文字";
		String scoreName = "@s";
		String scoreObjective = "points";
		String selector = "@p";
		String separator = ", ";
		String translate = "chat.type.text";
		String fallback = "";
		String keybind = "key.jump";
		String luaVariable = "value";
		private String checkedVariable;
		private String variableError;
		String color = "#55FFFF";
		String fontId = "minecraft:default";
		boolean bold;
		boolean italic;
		boolean underlined;
		boolean strikethrough;
		boolean obfuscated;
		Hover hover = Hover.NONE;
		String hoverText = "悬浮提示";
		String hoverItem = "minecraft:diamond";
		String hoverCount = "1";
		String hoverEntityType = "minecraft:pig";
		String hoverEntityUuid = "00000000-0000-0000-0000-000000000001";
		String hoverEntityName = "";
		Click click = Click.NONE;
		String clickValue = "/help";
		boolean gradient;
		String gradientStops = "#55FFFF, #FFFF55, #FF5555";

		Segment copy() {
			Segment result = new Segment();
			result.kind = kind; result.text = text; result.scoreName = scoreName;
			result.scoreObjective = scoreObjective; result.selector = selector; result.separator = separator;
			result.translate = translate; result.fallback = fallback; result.keybind = keybind;
			result.luaVariable = luaVariable;
			result.color = color; result.fontId = fontId;
			result.bold = bold; result.italic = italic; result.underlined = underlined;
			result.strikethrough = strikethrough; result.obfuscated = obfuscated;
			result.hover = hover; result.hoverText = hoverText; result.hoverItem = hoverItem;
			result.hoverCount = hoverCount; result.hoverEntityType = hoverEntityType;
			result.hoverEntityUuid = hoverEntityUuid; result.hoverEntityName = hoverEntityName;
			result.click = click; result.clickValue = clickValue;
			result.gradient = gradient; result.gradientStops = gradientStops;
			return result;
		}

		String previewText() {
			return switch (kind) {
				case TEXT -> text;
				case SCORE -> "‹分数 " + scoreName + "/" + scoreObjective + "›";
				case SELECTOR -> "‹选择器 " + selector + "›";
				case TRANSLATE -> fallback.isBlank() ? "‹翻译 " + translate + "›" : fallback;
				case KEYBIND -> "‹按键 " + keybind + "›";
				case LUA_VARIABLE -> "‹Lua：" + luaVariable + "›";
			};
		}

		String variableExpression() {
			String expression = luaVariable == null ? "" : luaVariable.strip();
			if (!expression.equals(checkedVariable)) {
				checkedVariable = expression; variableError = null;
				if (expression.isEmpty()) variableError = "请填写 Lua 变量或字段表达式，例如 name、event.playerName";
				else if (expression.length() > 1024) variableError = "Lua 变量表达式不能超过 1024 个字符";
				else {
					try {
						// Compile only. Never evaluate user expressions while editing or previewing.
						LuaC.instance.compile(new ByteArrayInputStream(("return (" + expression + ")").getBytes(StandardCharsets.UTF_8)), "text_json_variable");
					} catch (Exception error) { variableError = "Lua 变量表达式格式无效：" + error.getMessage(); }
				}
			}
			if (variableError != null) throw new IllegalArgumentException(variableError);
			return expression;
		}
	}

	private LuaTextJsonDocument() { }

	static JsonElement build(List<Segment> segments) {
		if (segments.isEmpty()) throw new IllegalArgumentException("至少需要一个文本片段");
		JsonArray extras = new JsonArray();
		for (int index = 0; index < segments.size(); index++) {
			Segment segment = segments.get(index);
			try { appendSegment(extras, segment); }
			catch (IllegalArgumentException error) {
				throw new IllegalArgumentException("第 " + (index + 1) + " 段：" + error.getMessage(), error);
			}
		}
		if (extras.size() == 1) return extras.get(0);
		JsonObject root = new JsonObject();
		root.addProperty("text", "");
		root.add("extra", extras);
		return root;
	}

	static boolean hasVariables(List<Segment> segments) {
		return segments.stream().anyMatch(segment -> segment.kind == Kind.LUA_VARIABLE);
	}

	/** A complete Lua expression returning JSON; dynamic values are inserted into text strings only. */
	static String luaExpression(List<Segment> segments) {
		if (!hasVariables(segments)) return luaString(build(segments).toString());
		List<Part> parts = new ArrayList<>();
		for (int index = 0; index < segments.size(); index++) {
			Segment segment = segments.get(index); JsonArray components = new JsonArray();
			try { appendSegment(components, segment); }
			catch (IllegalArgumentException error) { throw new IllegalArgumentException("第 " + (index + 1) + " 段：" + error.getMessage(), error); }
			for (JsonElement component : components)
				parts.add(new Part(component.getAsJsonObject(), segment.kind == Kind.LUA_VARIABLE ? segment.variableExpression() : null));
		}
		List<String> terms = new ArrayList<>();
		StringBuilder literal = new StringBuilder(parts.size() > 1 ? "{\"text\":\"\",\"extra\":[" : "");
		for (int index = 0; index < parts.size(); index++) {
			if (index > 0) literal.append(',');
			Part part = parts.get(index);
			if (part.variable() == null) literal.append(part.json());
			else {
				literal.append("{\"text\":\"");
				terms.add(luaString(literal.toString())); literal.setLength(0);
				// Only ASCII control characters, quotes and backslashes need JSON escaping.
				String pattern = "[%z" + (char) 1 + "-" + (char) 31 + "\\\"]";
				terms.add("(string.gsub(tostring((" + part.variable() + ")), " + luaString(pattern)
						+ ", function(c) return string.format(" + luaString("\\u%04x") + ", string.byte(c)) end))");
				literal.append('"');
				JsonObject style = part.json().deepCopy(); style.remove("text");
				if (style.size() > 0) literal.append(',').append(style.toString().substring(1));
				else literal.append('}');
			}
		}
		if (parts.size() > 1) literal.append("]}");
		terms.add(luaString(literal.toString()));
		return "(" + String.join(" .. ", terms) + ")";
	}

	/** Lua 5.2 literals, not JSON literals: Lua does not understand JSON's backslash-u escapes. */
	static String luaString(String value) {
		StringBuilder result = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\' -> result.append("\\\\");
				case '"' -> result.append("\\\"");
				case '\n' -> result.append("\\n");
				case '\r' -> result.append("\\r");
				case '\t' -> result.append("\\t");
				default -> {
					if (c < 32 || c == 127) result.append(String.format(Locale.ROOT, "\\%03d", (int) c));
					else result.append(c);
				}
			}
		}
		return result.append('"').toString();
	}

	private record Part(JsonObject json, String variable) { }

	private static void appendSegment(JsonArray result, Segment segment) {
		if (segment.gradient && segment.kind != Kind.TEXT)
			throw new IllegalArgumentException("动态内容不能逐字渐变，请切换为普通文字或关闭渐变");
		if (segment.gradient) {
			List<String> graphemes = graphemes(segment.text);
			List<Integer> colors = gradientColors(segment.gradientStops, graphemes.size());
			if (graphemes.isEmpty()) throw new IllegalArgumentException("渐变文字不能为空");
			for (int index = 0; index < graphemes.size(); index++)
				result.add(component(segment, graphemes.get(index), colors.get(index)));
		} else {
			result.add(component(segment, null, firstColor(segment.gradientStops)));
		}
	}

	private static JsonObject component(Segment segment, String character, int color) {
		JsonObject result = new JsonObject();
		switch (segment.kind) {
			case TEXT -> result.addProperty("text", character == null ? segment.text : character);
			case SCORE -> {
				if (segment.scoreName.isBlank() || segment.scoreObjective.isBlank())
					throw new IllegalArgumentException("分数需要计分对象和目标名称");
				JsonObject score = new JsonObject();
				score.addProperty("name", segment.scoreName.strip());
				score.addProperty("objective", segment.scoreObjective.strip());
				result.add("score", score);
			}
			case SELECTOR -> {
				if (segment.selector.isBlank()) throw new IllegalArgumentException("目标选择器不能为空");
				result.addProperty("selector", segment.selector.strip());
				if (!segment.separator.isEmpty()) result.addProperty("separator", segment.separator);
			}
			case TRANSLATE -> {
				if (segment.translate.isBlank()) throw new IllegalArgumentException("翻译键不能为空");
				result.addProperty("translate", segment.translate.strip());
				if (!segment.fallback.isBlank()) result.addProperty("fallback", segment.fallback);
			}
			case KEYBIND -> {
				if (segment.keybind.isBlank()) throw new IllegalArgumentException("按键 ID 不能为空");
				result.addProperty("keybind", segment.keybind.strip());
			}
			case LUA_VARIABLE -> {
				segment.variableExpression();
				result.addProperty("text", segment.previewText());
			}
		}
		result.addProperty("color", String.format(Locale.ROOT, "#%06X", color));
		if (!segment.fontId.isBlank()) {
			if (Identifier.tryParse(segment.fontId.strip()) == null)
				throw new IllegalArgumentException("字体 ID 格式无效");
			result.addProperty("font", segment.fontId.strip());
		}
		if (segment.bold) result.addProperty("bold", true);
		if (segment.italic) result.addProperty("italic", true);
		if (segment.underlined) result.addProperty("underlined", true);
		if (segment.strikethrough) result.addProperty("strikethrough", true);
		if (segment.obfuscated) result.addProperty("obfuscated", true);
		addHover(result, segment);
		addClick(result, segment);
		return result;
	}

	private static void addHover(JsonObject result, Segment segment) {
		if (segment.hover == Hover.NONE) return;
		JsonObject hover = new JsonObject();
		switch (segment.hover) {
			case TEXT -> {
				hover.addProperty("action", "show_text");
				String value = segment.hoverText.strip();
				if (value.isEmpty()) throw new IllegalArgumentException("悬浮文字不能为空");
				if (value.startsWith("{") || value.startsWith("[")) {
					try { hover.add("value", JsonParser.parseString(value)); }
					catch (Exception error) { throw new IllegalArgumentException("悬浮文本 JSON 格式无效", error); }
				} else hover.addProperty("value", segment.hoverText);
			}
			case ITEM -> {
				hover.addProperty("action", "show_item");
				if (Identifier.tryParse(segment.hoverItem.strip()) == null)
					throw new IllegalArgumentException("悬浮物品 ID 无效");
				hover.addProperty("id", segment.hoverItem.strip());
				int count = positiveInteger(segment.hoverCount, "物品数量");
				if (count != 1) hover.addProperty("count", count);
			}
			case ENTITY -> {
				hover.addProperty("action", "show_entity");
				if (Identifier.tryParse(segment.hoverEntityType.strip()) == null)
						throw new IllegalArgumentException("悬浮实体类型 ID 无效");
				hover.addProperty("id", segment.hoverEntityType.strip());
				try { hover.addProperty("uuid", UUID.fromString(segment.hoverEntityUuid.strip()).toString()); }
				catch (Exception error) { throw new IllegalArgumentException("悬浮实体 UUID 无效", error); }
				if (!segment.hoverEntityName.isBlank()) hover.addProperty("name", segment.hoverEntityName);
			}
			case NONE -> { }
		}
		result.add("hover_event", hover);
	}

	private static void addClick(JsonObject result, Segment segment) {
		if (segment.click == Click.NONE) return;
		JsonObject click = new JsonObject();
		String value = segment.clickValue.strip();
		if (value.isEmpty()) throw new IllegalArgumentException("点击动作参数不能为空");
		switch (segment.click) {
			case RUN_COMMAND -> { click.addProperty("action", "run_command"); click.addProperty("command", value); }
			case SUGGEST_COMMAND -> { click.addProperty("action", "suggest_command"); click.addProperty("command", value); }
			case OPEN_URL -> {
				URI uri;
				try { uri = URI.create(value); }
				catch (Exception error) { throw new IllegalArgumentException("网址格式无效", error); }
				if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
					throw new IllegalArgumentException("网址必须以 http:// 或 https:// 开头");
				click.addProperty("action", "open_url"); click.addProperty("url", value);
			}
			case COPY -> { click.addProperty("action", "copy_to_clipboard"); click.addProperty("value", segment.clickValue); }
			case CHANGE_PAGE -> { click.addProperty("action", "change_page"); click.addProperty("page", positiveInteger(value, "页码")); }
			case SHOW_DIALOG -> {
				if (Identifier.tryParse(value) == null) throw new IllegalArgumentException("对话框 ID 无效");
				click.addProperty("action", "show_dialog"); click.addProperty("dialog", value);
			}
			case NONE -> { }
		}
		result.add("click_event", click);
	}

	static int rgb(String raw) {
		String value = raw == null ? "" : raw.strip();
		if (!HEX.matcher(value).matches()) throw new IllegalArgumentException("颜色必须是 #RRGGBB 六位十六进制值");
		if (value.startsWith("#")) value = value.substring(1);
		return Integer.parseInt(value, 16);
	}

	static int firstColor(String stops) {
		String[] values = stops == null ? new String[0] : stops.strip().split("[，,;；\\s]+");
		if (values.length == 0 || values[0].isBlank())
			throw new IllegalArgumentException("请填写第一个十六进制颜色");
		return rgb(values[0]);
	}

	static List<Integer> gradientColors(String stops, int count) {
		String[] values = stops == null ? new String[0] : stops.strip().split("[，,;；\\s]+");
		if (values.length < 2 || values.length > 32)
			throw new IllegalArgumentException("渐变需要 2～32 个十六进制颜色，使用逗号分隔");
		List<Integer> palette = new ArrayList<>();
		for (String value : values) palette.add(rgb(value));
		List<Integer> result = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			double position = count < 2 ? 0 : index * (palette.size() - 1.0D) / (count - 1);
			int first = Math.min(palette.size() - 1, (int) Math.floor(position));
			int second = Math.min(palette.size() - 1, first + 1);
			result.add(mix(palette.get(first), palette.get(second), position - first));
		}
		return result;
	}

	static List<String> graphemes(String text) {
		List<String> result = new ArrayList<>();
		Matcher matcher = GRAPHEME.matcher(text);
		while (matcher.find()) result.add(matcher.group());
		return result;
	}

	private static int mix(int first, int second, double amount) {
		int red = channel(first >> 16, second >> 16, amount);
		int green = channel(first >> 8, second >> 8, amount);
		int blue = channel(first, second, amount);
		return red << 16 | green << 8 | blue;
	}

	private static int channel(int first, int second, double amount) {
		return (int) Math.round((first & 255) * (1 - amount) + (second & 255) * amount);
	}

	private static int positiveInteger(String value, String label) {
		try {
			int parsed = Integer.parseInt(value.strip());
			if (parsed > 0) return parsed;
		} catch (Exception ignored) { }
		throw new IllegalArgumentException(label + "必须是正整数");
	}
}
