package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Completion after {@code variable.} (or {@code variable:}) for variables whose shape the editor can tell without running
 * anything: the {@code event} parameter of MC.Event callbacks, results of MC API calls that return a documented table,
 * entries of MC.Player.list / MC.Entity.list loops, table constructors and strings.
 */
final class LuaMemberCompletion {
	private record Member(String name, String detail, boolean method) { }
	private record Shape(List<Member> members) {
		static final Shape UNKNOWN = new Shape(List.of());
	}

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final List<String> STRING_METHODS = List.of("byte", "find", "format", "gmatch", "gsub", "len", "lower", "match", "rep", "reverse", "sub", "upper");
	private static final Set<String> ITERATORS = Set.of("ipairs", "pairs");
	private static final Map<String, List<String>> LIST_ELEMENTS = Map.of(
			"MC.Player.list", List.of("uuid", "name", "dimension", "x", "y", "z"),
			"MC.Entity.list", List.of("uuid", "type", "name", "runtimeId", "x", "y", "z", "yaw", "pitch", "dimension", "living", "glowing",
					"invulnerable", "gravity", "vehicleUuid", "passengerUuids", "health", "maxHealth"));
	private static final Set<String> STRING_RESULTS = Set.of("tostring", "string.format", "string.rep", "string.lower", "string.upper", "string.sub", "string.reverse");

	private LuaMemberCompletion() { }

	static List<LuaArgumentCompletion.Choice> choices(String source, int cursor, String prefix) {
		int separator = Math.max(prefix.lastIndexOf('.'), prefix.lastIndexOf(':'));
		if (separator <= 0) return List.of();
		String owner = prefix.substring(0, separator), typed = prefix.substring(separator + 1);
		if (!IDENTIFIER.matcher(owner).matches()) return List.of();
		Shape shape = resolve(source, cursor, owner);
		if (shape.members().isEmpty()) return List.of();
		char typedSeparator = prefix.charAt(separator);
		List<LuaArgumentCompletion.Choice> result = new ArrayList<>();
		for (Member member : shape.members()) {
			if (typedSeparator == ':' && !member.method() || !member.name().regionMatches(true, 0, typed, 0, typed.length())) continue;
			String insertion = owner + (member.method() ? ':' : '.') + member.name();
			if (insertion.equalsIgnoreCase(prefix)) continue;
			result.add(new LuaArgumentCompletion.Choice(insertion, insertion + " - " + member.detail(), insertion + "\n" + member.detail(), 0, member.method()));
		}
		return result;
	}

	private static Shape resolve(String source, int cursor, String name) {
		List<LuaCodeAnalyzer.Token> tokens = LuaCodeAnalyzer.lex(source.substring(0, Math.max(0, Math.min(cursor, source.length()))));
		Deque<Map<String, Shape>> frames = new ArrayDeque<>();
		frames.push(new HashMap<>());
		boolean pendingDo = false;
		for (int i = 0; i < tokens.size(); i++) {
			var token = tokens.get(i);
			if (token.type() != LuaCodeAnalyzer.TokenType.KEYWORD) continue;
			switch (token.text()) {
				case "function" -> {
					int open = i + 1;
					while (open < tokens.size() && !tokens.get(open).text().equals("(")) open++;
					Map<String, Shape> parameters = new HashMap<>();
					int index = 0, j = open + 1;
					for (; j < tokens.size() && !tokens.get(j).text().equals(")"); j++) {
						if (tokens.get(j).type() == LuaCodeAnalyzer.TokenType.IDENTIFIER)
							parameters.put(tokens.get(j).text(), index++ == 0 ? callbackShape(tokens, i) : Shape.UNKNOWN);
					}
					frames.push(parameters);
					i = Math.max(i, Math.min(j, tokens.size() - 1));
				}
				case "if", "repeat" -> frames.push(new HashMap<>());
				case "while" -> { frames.push(new HashMap<>()); pendingDo = true; }
				case "for" -> { frames.push(loopVariables(tokens, i)); pendingDo = true; }
				case "do" -> { if (pendingDo) pendingDo = false; else frames.push(new HashMap<>()); }
				case "end", "until" -> { if (frames.size() > 1) frames.pop(); }
				case "local" -> declare(tokens, i, frames);
				default -> { }
			}
		}
		for (Map<String, Shape> frame : frames) {
			Shape shape = frame.get(name);
			if (shape != null) return shape;
		}
		return Shape.UNKNOWN;
	}

	private static Map<String, Shape> loopVariables(List<LuaCodeAnalyzer.Token> tokens, int forIndex) {
		Map<String, Shape> variables = new HashMap<>();
		List<String> names = new ArrayList<>();
		int i = forIndex + 1;
		for (; i < tokens.size(); i++) {
			String text = tokens.get(i).text();
			if (text.equals("in") || text.equals("=")) break;
			if (tokens.get(i).type() == LuaCodeAnalyzer.TokenType.IDENTIFIER) names.add(text);
		}
		for (String loopName : names) variables.put(loopName, Shape.UNKNOWN);
		if (i < tokens.size() && tokens.get(i).text().equals("in") && names.size() >= 2) {
			// for _, entry in ipairs(MC.Player.list()) do
			int call = i + 1;
			if (call + 1 < tokens.size() && ITERATORS.contains(tokens.get(call).text()) && tokens.get(call + 1).text().equals("(")) {
				String callee = path(tokens, call + 2);
				List<String> fields = callee == null ? null : LIST_ELEMENTS.get(canonical(callee));
				if (fields != null) variables.put(names.get(1), fieldShape(fields));
			}
		}
		return variables;
	}

	private static void declare(List<LuaCodeAnalyzer.Token> tokens, int localIndex, Deque<Map<String, Shape>> frames) {
		int i = localIndex + 1;
		if (i < tokens.size() && tokens.get(i).text().equals("function")) {
			if (i + 1 < tokens.size() && tokens.get(i + 1).type() == LuaCodeAnalyzer.TokenType.IDENTIFIER) frames.peek().put(tokens.get(i + 1).text(), Shape.UNKNOWN);
			return;
		}
		List<String> names = new ArrayList<>();
		while (i < tokens.size() && tokens.get(i).type() == LuaCodeAnalyzer.TokenType.IDENTIFIER) {
			names.add(tokens.get(i++).text());
			if (i < tokens.size() && tokens.get(i).text().equals(",")) i++; else break;
		}
		Shape first = i < tokens.size() && tokens.get(i).text().equals("=") ? classify(tokens, i + 1, frames) : Shape.UNKNOWN;
		for (int n = 0; n < names.size(); n++) frames.peek().put(names.get(n), n == 0 ? first : Shape.UNKNOWN);
	}

	private static Shape classify(List<LuaCodeAnalyzer.Token> tokens, int start, Deque<Map<String, Shape>> frames) {
		if (start >= tokens.size()) return Shape.UNKNOWN;
		var first = tokens.get(start);
		if (first.type() == LuaCodeAnalyzer.TokenType.STRING) return stringShape();
		if (first.text().equals("{")) return tableShape(tokens, start);
		if (first.type() != LuaCodeAnalyzer.TokenType.IDENTIFIER) return Shape.UNKNOWN;
		String callee = path(tokens, start);
		if (callee == null) return Shape.UNKNOWN;
		int after = start + callee.split("\\.").length * 2 - 1;
		boolean call = after < tokens.size() && tokens.get(after).text().equals("(");
		if (!call) {
			if (!callee.contains(".")) for (Map<String, Shape> frame : frames) {
				Shape alias = frame.get(callee);
				if (alias != null) return alias;
			}
			return Shape.UNKNOWN;
		}
		if (STRING_RESULTS.contains(callee)) return stringShape();
		String name = canonical(callee);
		var entry = name.startsWith("MC.") ? LuaApiDocumentation.find(name) : null;
		return entry == null ? Shape.UNKNOWN : returnShape(entry.rawReturns());
	}

	/** An API whose documented result is a table such as {x, y, z, yaw, pitch, dimension}: those names are the fields. */
	private static Shape returnShape(String returns) {
		if (returns == null) return Shape.UNKNOWN;
		String text = returns.strip();
		int close = text.indexOf('}');
		if (!text.startsWith("{") || close < 0) return Shape.UNKNOWN;
		List<String> names = new ArrayList<>();
		for (String part : text.substring(1, close).split("[,，、]")) {
			var matcher = IDENTIFIER.matcher(part.strip());
			if (matcher.lookingAt() && !names.contains(matcher.group())) names.add(matcher.group());
		}
		return names.isEmpty() ? Shape.UNKNOWN : fieldShape(names);
	}

	private static Shape tableShape(List<LuaCodeAnalyzer.Token> tokens, int open) {
		List<String> names = new ArrayList<>();
		int depth = 0;
		for (int i = open; i < tokens.size(); i++) {
			String text = tokens.get(i).text();
			if (text.equals("{") || text.equals("(") || text.equals("[")) depth++;
			else if (text.equals("}") || text.equals(")") || text.equals("]")) { depth--; if (depth <= 0) break; }
			else if (depth == 1 && tokens.get(i).type() == LuaCodeAnalyzer.TokenType.IDENTIFIER && i + 1 < tokens.size() && tokens.get(i + 1).text().equals("=")
					&& !names.contains(text)) names.add(text);
		}
		return names.isEmpty() ? Shape.UNKNOWN : fieldShape(names);
	}

	private static Shape stringShape() {
		List<Member> methods = new ArrayList<>();
		for (String name : STRING_METHODS) methods.add(new Member(name, LuaUiText.text("字符串方法", "String method", "文字列メソッド"), true));
		return new Shape(methods);
	}

	private static Shape fieldShape(List<String> names) {
		List<Member> members = new ArrayList<>();
		for (String name : names) members.add(new Member(name, describe(name), false));
		return new Shape(members);
	}

	/** The parameter of a callback given to MC.Event.onXxx(...) or MC.Event.on("xxx", ...) is the event table. */
	private static Shape callbackShape(List<LuaCodeAnalyzer.Token> tokens, int functionIndex) {
		if (functionIndex == 0) return Shape.UNKNOWN;
		String before = tokens.get(functionIndex - 1).text();
		if (!before.equals("(") && !before.equals(",")) return Shape.UNKNOWN;
		int depth = 0, i = functionIndex - 1, callOpen = -1;
		while (i >= 0) {
			String text = tokens.get(i).text();
			if (text.equals(")") || text.equals("}") || text.equals("]")) depth++;
			else if (text.equals("(") || text.equals("{") || text.equals("[")) {
				if (depth == 0) { if (text.equals("(")) callOpen = i; break; }
				depth--;
			}
			i--;
		}
		if (callOpen < 1) return Shape.UNKNOWN;
		int end = callOpen - 1;
		StringBuilder callee = new StringBuilder(tokens.get(end).text());
		while (end >= 2 && (tokens.get(end - 1).text().equals(".") || tokens.get(end - 1).text().equals(":"))) {
			callee.insert(0, tokens.get(end - 2).text() + tokens.get(end - 1).text());
			end -= 2;
		}
		String name = callee.toString();
		String eventName = null;
		if (name.equalsIgnoreCase("MC.Event.on")) {
			int argument = callOpen + 1;
			if (argument < tokens.size() && tokens.get(argument).type() == LuaCodeAnalyzer.TokenType.STRING) eventName = LuaCodeAnalyzer.stringValue(tokens.get(argument).text());
		} else if (name.toLowerCase(Locale.ROOT).startsWith("mc.event.on") && name.length() > "MC.Event.on".length()) {
			eventName = name.substring("MC.Event.on".length());
		}
		if (eventName == null || eventName.isBlank()) return Shape.UNKNOWN;
		return fieldShape(LuaApiDocumentation.eventFields(eventName));
	}

	private static String path(List<LuaCodeAnalyzer.Token> tokens, int start) {
		if (start >= tokens.size() || tokens.get(start).type() != LuaCodeAnalyzer.TokenType.IDENTIFIER) return null;
		StringBuilder path = new StringBuilder(tokens.get(start).text());
		int i = start + 1;
		while (i + 1 < tokens.size() && tokens.get(i).text().equals(".") && tokens.get(i + 1).type() == LuaCodeAnalyzer.TokenType.IDENTIFIER) {
			path.append('.').append(tokens.get(i + 1).text());
			i += 2;
		}
		return path.toString();
	}

	private static String canonical(String callee) {
		if (LuaApiDocumentation.find(callee) != null) {
			for (String candidate : LuaApiDocumentation.symbols()) if (candidate.equalsIgnoreCase(callee)) return candidate;
		}
		return callee;
	}

	private static String describe(String field) {
		return switch (field) {
			case "type" -> LuaUiText.text("事件名称", "Event name", "イベント名");
			case "playerUuid" -> LuaUiText.text("玩家 UUID", "Player UUID", "プレイヤーの UUID");
			case "playerName" -> LuaUiText.text("玩家名", "Player name", "プレイヤー名");
			case "uuid" -> "UUID";
			case "name" -> LuaUiText.text("名称", "Name", "名前");
			case "x", "y", "z" -> LuaUiText.text("坐标 " + field.toUpperCase(Locale.ROOT), "Coordinate " + field.toUpperCase(Locale.ROOT), "座標 " + field.toUpperCase(Locale.ROOT));
			case "yaw", "pitch" -> LuaUiText.text("朝向角度", "Facing angle", "向きの角度");
			case "dimension" -> LuaUiText.text("维度 ID", "Dimension ID", "ディメンション ID");
			case "blockId" -> LuaUiText.text("方块 ID", "Block ID", "ブロック ID");
			case "itemId" -> LuaUiText.text("物品 ID", "Item ID", "アイテム ID");
			case "itemCount", "count" -> LuaUiText.text("数量／次数", "Count", "数量／回数");
			case "itemComponents" -> LuaUiText.text("物品数据组件", "Item data components", "アイテムのデータコンポーネント");
			case "hand" -> LuaUiText.text("使用的手", "Hand used", "使用した手");
			case "message" -> LuaUiText.text("消息文本", "Message text", "メッセージ");
			case "command" -> LuaUiText.text("命令文本", "Command text", "コマンド");
			case "registrationId" -> LuaUiText.text("事件注册 ID", "Event registration ID", "イベント登録 ID");
			case "scriptName" -> LuaUiText.text("脚本名", "Script name", "スクリプト名");
			case "elapsedMs" -> LuaUiText.text("已经过的毫秒", "Elapsed milliseconds", "経過ミリ秒");
			case "intervalMs" -> LuaUiText.text("间隔毫秒", "Interval in milliseconds", "間隔（ミリ秒）");
			case "reason" -> LuaUiText.text("结束原因", "End reason", "終了理由");
			case "success" -> LuaUiText.text("是否成功", "Whether it succeeded", "成功したか");
			case "error" -> LuaUiText.text("错误信息（无错误时为 nil）", "Error message (nil when none)", "エラー内容（なければ nil）");
			default -> LuaUiText.text("字段", "Field", "フィールド");
		};
	}
}
