package wiki.ica.xiaomiao.mcluaide.client;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.lib.jse.JsePlatform;
import net.minecraft.resources.Identifier;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fast, side-effect-free Lua syntax and MC API argument analysis for the editor. */
final class LuaCodeAnalyzer {
	private static final int MAX_DIAGNOSTICS = 100;
	private static final Pattern ERROR_LINE = Pattern.compile(":(\\d+):");
	private static final Pattern ERROR_NEAR = Pattern.compile("near ['\"]([^'\"]+)['\"]");
	private static final Pattern ERROR_SYMBOL = Pattern.compile("unexpected symbol \\d+ \\(([^)]*)\\)");
	private static final Pattern ERROR_OPENING = Pattern.compile("to close ['\"]([^'\"]+)['\"] at line (\\d+)");
	private static final Pattern NUMBER = Pattern.compile(
			"(?i)(?:0x[0-9a-f]+(?:\\.[0-9a-f]*)?(?:p[+-]?\\d+)?|(?:\\d+\\.?\\d*|\\.\\d+)(?:e[+-]?\\d+)?)");
	private static final Set<String> KEYWORDS = Set.of(
			"and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
			"local", "not", "or", "repeat", "return", "then", "until", "while");
	private static final Set<String> TWO_CHARACTER_SYMBOLS = Set.of(
			"==", "~=", "<=", ">=", "..", "::", "//", "<<", ">>");
	private static final Set<String> EXPRESSION_CONTINUATIONS = Set.of(
			".", ":", "[", "(", "+", "-", "*", "/", "//", "%", "^", "..",
			"==", "~=", "<", ">", "<=", ">=", "and", "or", "&", "|", "<<", ">>");
	private static final Set<String> TABLE_PARAMETERS = Set.of("definition", "grid", "entries", "items", "costs", "rewards");
	static final Set<String> PREDEFINED_GLOBALS = Set.of(
			"_G", "_VERSION", "assert", "collectgarbage", "dofile", "error", "getmetatable",
			"ipairs", "load", "loadfile", "next", "pairs", "pcall", "print", "rawequal", "rawget",
			"rawlen", "rawset", "require", "select", "setmetatable", "tonumber", "tostring", "type",
			"xpcall", "coroutine", "package", "string", "utf8", "table", "math", "io", "os",
			"debug", "bit32", "luajava", "unpack", "MC", "mc");
	private static final Set<String> BOOLEAN_PARAMETERS = Set.of(
			"drops", "mayFly", "flying", "levels", "visible", "enabled", "showParticles", "force", "keepExisting", "dropItems");
	private static final Set<String> INTEGER_PARAMETERS = Set.of(
			"count", "firstSlot", "secondSlot", "food", "ticks", "fuseTicks", "chunkX", "chunkZ",
			"fadeIn", "stay", "fadeOut", "amplifier", "intervalTicks", "registrationId", "milliseconds", "intervalMs");
	private static final Set<String> NUMBER_PARAMETERS = Set.of(
			"x", "y", "z", "x1", "y1", "z1", "x2", "y2", "z2", "toX", "toY", "toZ",
			"walkingSpeed", "flyingSpeed", "amount", "saturation", "yaw", "pitch", "radius", "seconds",
			"duration", "volume", "spread", "speed", "power", "size", "distance", "rate", "startSeconds");
	private static final Set<String> RESOURCE_PARAMETERS = Set.of(
			"id", "itemId", "blockId", "fromId", "toId", "entityType", "effectId", "soundId",
			"particleId", "attributeId", "modifierId", "advancementId", "recipeId", "lootTableId",
			"structureId", "dimension", "damageType", "enchantmentId");
	private static final Set<String> EVENT_NAMES = Set.of(
			"blockPlaced", "blockBroken", "blockAttacked", "blockUsed", "itemUsed",
			"entityAttacked", "entityUsed", "entityLoaded", "entityUnloaded", "entityChangedDimension",
			"entityDamaged", "entityDied", "entityKilledOther", "equipmentChanged",
			"projectileHitBlock", "projectileHitEntity",
			"playerJoined", "playerLeft", "playerDied", "playerRespawned", "playerChangedDimension",
			"effectAdded", "effectRemoved", "sleepStarted", "sleepStopped",
			"chunkLoaded", "chunkGenerated", "chunkUnloaded", "levelLoaded", "levelUnloaded",
			"serverTick", "levelTick", "chat", "command", "uiAction", "interval", "scriptStarted", "scriptStopped",
			"containerClicked", "containerItemTaken", "containerItemPlaced", "containerItemToCursor", "inventoryItemToCursor");
	private static final Map<String, Set<String>> ENUM_VALUES = Map.ofEntries(
			Map.entry("mode", Set.of("survival", "creative", "adventure", "spectator")),
			Map.entry("weather", Set.of("clear", "rain", "thunder")),
			Map.entry("difficulty", Set.of("peaceful", "easy", "normal", "hard")),
			Map.entry("operation", Set.of("add_value", "add_multiplied_base", "add_multiplied_total")),
			Map.entry("method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
			Map.entry("equipmentSlot", Set.of("mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle")),
			Map.entry("category", Set.of("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice")),
			Map.entry("kind", Set.of("blocks", "items", "entities", "effects", "sounds", "particles", "potions", "gameRules",
					"fluids", "blockEntities", "attributes", "dataComponents")));

	private LuaCodeAnalyzer() {
	}

	static Analysis analyze(String scriptName, String source) {
		String safeSource = source == null ? "" : source;
		Diagnostic syntax = compile(safeSource, scriptName == null || scriptName.isBlank() ? "script" : scriptName);
		if (syntax != null) return new Analysis(List.of(syntax));
		List<Diagnostic> semantic = LuaSemanticAnalyzer.analyze(safeSource);
		if (semantic != null) return new Analysis(semantic);
		List<Diagnostic> diagnostics = new ArrayList<>(validateUndefinedVariables(safeSource));
		if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.addAll(validateApiCalls(safeSource));
		if (diagnostics.size() > MAX_DIAGNOSTICS) diagnostics = new ArrayList<>(diagnostics.subList(0, MAX_DIAGNOSTICS));
		return new Analysis(diagnostics);
	}

	private static Diagnostic compile(String source, String scriptName) {
		try {
			wiki.ica.xiaomiao.mcluaide.server.LuaSandbox.syntaxOnly().load(source, scriptName + ".lua");
			return null;
		} catch (LuaError error) {
			return syntaxDiagnostic(source, error.getMessage());
		} catch (RuntimeException error) {
			int end = source.isEmpty() ? 0 : 1;
			return diagnostic(source, "Lua 编译器无法解析脚本：" + safeMessage(error), 0, end);
		}
	}

	private static Diagnostic syntaxDiagnostic(String source, String rawMessage) {
		String raw = rawMessage == null ? "未知语法错误" : rawMessage;
		Matcher lineMatcher = ERROR_LINE.matcher(raw);
		int line = lineMatcher.find() ? Math.max(1, Integer.parseInt(lineMatcher.group(1))) : 1;
		int lineStart = lineStart(source, line);
		int lineEnd = lineEnd(source, lineStart);
		Matcher nearMatcher = ERROR_NEAR.matcher(raw);
		String near = nearMatcher.find() ? nearMatcher.group(1) : null;
		Matcher symbolMatcher = ERROR_SYMBOL.matcher(raw);
		if (near == null && symbolMatcher.find()) near = symbolMatcher.group(1);
		int start;
		int end;
		Matcher openingMatcher = ERROR_OPENING.matcher(raw);
		if (openingMatcher.find()) {
			String opening = openingMatcher.group(1);
			int openingLine = Math.max(1, Integer.parseInt(openingMatcher.group(2)));
			int openingLineStart = lineStart(source, openingLine);
			int openingLineEnd = lineEnd(source, openingLineStart);
			int relative = source.substring(openingLineStart, openingLineEnd).lastIndexOf(opening);
			start = relative >= 0 ? openingLineStart + relative
					: lastVisibleCharacter(source, openingLineStart, openingLineEnd);
			end = Math.min(source.length(), start + Math.max(1, relative >= 0 ? opening.length() : 1));
		} else if ("<eof>".equals(near)) {
			start = lastVisibleCharacter(source);
			end = Math.min(source.length(), start + 1);
		} else if (near != null && !near.isEmpty()) {
			String nearText = near;
			Token marked = lex(source.substring(lineStart, lineEnd)).stream()
					.filter(token -> token.text().equals(nearText)).findFirst().orElse(null);
			int relative = marked == null ? source.substring(lineStart, lineEnd).lastIndexOf(near) : marked.start();
			start = relative >= 0 ? lineStart + relative : lastVisibleCharacter(source, lineStart, lineEnd);
			end = Math.min(source.length(), start + Math.max(1, relative >= 0 ? near.length() : 1));
		} else if (raw.contains("unfinished string")) {
			int single = source.lastIndexOf('\'', Math.max(lineStart, lineEnd - 1));
			int quoted = source.lastIndexOf('"', Math.max(lineStart, lineEnd - 1));
			start = Math.max(lineStart, Math.max(single, quoted));
			end = Math.max(start + 1, lineEnd);
		} else {
			start = lastVisibleCharacter(source, lineStart, lineEnd);
			end = Math.min(source.length(), start + 1);
		}
		if (source.isEmpty()) start = end = 0;
		return diagnostic(source, translateSyntaxError(raw, near), start, end);
	}

	private static String translateSyntaxError(String raw, String near) {
		String lower = raw.toLowerCase(Locale.ROOT);
		String explanation;
		if (lower.contains("'end' expected")) {
			explanation = "缺少 end，请检查 if、for、while、do 或 function 是否正确结束";
		} else if (lower.contains("')' expected")) {
			explanation = "缺少右括号 )，请检查函数调用或表达式的括号是否成对";
		} else if (lower.contains("']' expected")) {
			explanation = "缺少右方括号 ]，请检查下标或多行字符串是否完整";
		} else if (lower.contains("'}' expected")) {
			explanation = "缺少右花括号 }，请检查表是否完整";
		} else if (lower.contains("'then' expected")) {
			explanation = "条件后缺少 then";
		} else if (lower.contains("'do' expected")) {
			explanation = "循环条件后缺少 do";
		} else if (lower.contains("'until' expected")) {
			explanation = "repeat 代码块缺少 until 条件";
		} else if (lower.contains("'=' expected")) {
			explanation = "此处缺少赋值符号 =";
		} else if (lower.contains("<name> expected") || lower.contains("name expected")) {
			explanation = "此处需要变量名或字段名";
		} else if (lower.contains("unexpected symbol") || lower.contains("unexpected token")) {
			explanation = "此处出现了不应出现的符号或关键字";
		} else if (lower.contains("function arguments expected")) {
			explanation = "函数调用缺少参数括号或字符串参数";
		} else if (lower.contains("unfinished long string")) {
			explanation = "多行字符串没有正确闭合";
		} else if (lower.contains("unfinished string")) {
			explanation = "字符串没有正确闭合，请补上对应引号";
		} else if (lower.contains("invalid escape") || lower.contains("escape sequence")) {
			explanation = "字符串中包含无效的转义字符";
		} else if (lower.contains("malformed number")) {
			explanation = "数字格式不正确";
		} else if (lower.contains("no visible label")) {
			explanation = "goto 找不到当前作用域内对应的标签";
		} else if (lower.contains("break outside loop")) {
			explanation = "break 只能写在循环内部";
		} else if (lower.contains("cannot use '...' outside")) {
			explanation = "可变参数 ... 只能在可变参数函数内部使用";
		} else if (lower.contains("too many local variables")) {
			explanation = "当前函数中的局部变量数量过多";
		} else if (lower.contains("syntax error")) {
			explanation = "Lua 语法有误，请检查关键字、括号、逗号和表达式是否完整";
		} else {
			explanation = "Lua 语法有误，请检查此处的关键字、括号和表达式";
		}
		if (near != null && !near.equals("<eof>")) explanation += "；出错位置附近：“" + near + "”";
		return explanation;
	}

	private static List<Diagnostic> validateApiCalls(String source) {
		List<Token> tokens = lex(source);
		List<Diagnostic> diagnostics = new ArrayList<>();
		Map<String, StaticValue> variables = new HashMap<>();
		for (int index = 0; index < tokens.size() && diagnostics.size() < MAX_DIAGNOSTICS; index++) {
			captureAssignment(tokens, index, source, variables);
			Token token = tokens.get(index);
			if (token.type() != TokenType.IDENTIFIER || !LuaApiDocumentation.isApiRoot(token.text())) continue;
			int cursor = index;
			StringBuilder symbol = new StringBuilder(token.text());
			while (cursor + 2 < tokens.size() && tokens.get(cursor + 1).text().equals(".")
					&& tokens.get(cursor + 2).type() == TokenType.IDENTIFIER) {
				symbol.append('.').append(tokens.get(cursor + 2).text());
				cursor += 2;
			}
			if (cursor + 1 >= tokens.size() || !tokens.get(cursor + 1).text().equals("(")) continue;
			String usedSymbol = symbol.toString();
			String canonical = canonicalSymbol(usedSymbol);
			if (canonical == null) {
				diagnostics.add(diagnostic(source, "未知的 API：“" + usedSymbol + "”，请使用 Tab 补全或查询 API 文档",
						token.start(), tokens.get(cursor).end()));
				continue;
			}
			if (!canonical.equals(usedSymbol)) {
				diagnostics.add(diagnostic(source, "API 大小写不正确，应写为 “" + canonical + "”",
						token.start(), tokens.get(cursor).end()));
				continue;
			}
			LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(canonical);
			Call call = parseCall(tokens, cursor + 1, source);
			if (call == null) continue;
			validateCall(source, canonical, entry, call, variables, diagnostics);
		}
		return List.copyOf(diagnostics);
	}

	private static void captureAssignment(List<Token> tokens, int index, String source,
			Map<String, StaticValue> variables) {
		Token token = tokens.get(index);
		if (token.text().equals("function") && index + 1 < tokens.size()
				&& tokens.get(index + 1).type() == TokenType.IDENTIFIER) {
			variables.put(tokens.get(index + 1).text(), new StaticValue(ValueType.FUNCTION, null));
			return;
		}
		int nameIndex = token.text().equals("local") ? index + 1 : index;
		if (nameIndex >= tokens.size() || tokens.get(nameIndex).type() != TokenType.IDENTIFIER) return;
		if (nameIndex > 0 && (tokens.get(nameIndex - 1).text().equals(".")
				|| tokens.get(nameIndex - 1).text().equals(":"))) return;
		if (nameIndex + 2 >= tokens.size() || !tokens.get(nameIndex + 1).text().equals("=")) return;
		int valueIndex = nameIndex + 2;
		StaticValue value = infer(List.of(tokens.get(valueIndex)), source, variables);
		if (valueIndex + 1 < tokens.size() && continuesExpression(tokens.get(valueIndex + 1).text()))
			value = new StaticValue(ValueType.UNKNOWN, null);
		// A later dynamic assignment invalidates a previously inferred literal.
		variables.put(tokens.get(nameIndex).text(), value);
	}

	private static boolean continuesExpression(String token) {
		return EXPRESSION_CONTINUATIONS.contains(token);
	}

	/** Names visible at the caret, using the same comment/string lexer as diagnostics. */
	static List<String> visibleVariables(String source, int cursor) {
		return List.copyOf(visibleVariableValues(source, cursor).keySet());
	}

	static Map<String, StaticValue> visibleVariableValues(String source, int cursor) {
		if (source == null || source.isEmpty()) return Map.of();
		int end = Math.max(0, Math.min(cursor, source.length()));
		List<Token> tokens = lex(source.substring(0, end));
		Deque<Set<String>> scopes = new ArrayDeque<>();
		scopes.push(new LinkedHashSet<>());
		Map<Set<String>, Map<String, StaticValue>> values = new IdentityHashMap<>();
		Map<String, StaticValue> lookup = new AbstractMap<>() {
			@Override public StaticValue get(Object key) {
				if (!(key instanceof String name)) return null;
				Set<String> scope = variableScope(scopes, name);
				return scope == null ? null : values.getOrDefault(scope, Map.of()).getOrDefault(name,
						new StaticValue(ValueType.UNKNOWN, null));
			}
			@Override public Set<Map.Entry<String, StaticValue>> entrySet() { return scopeValues(scopes, values).entrySet(); }
		};
		Deque<VariableBlock> blocks = new ArrayDeque<>();
		int pendingLoopDo = 0;
		int tableDepth = 0;
		boolean closeRepeatAfterCondition = false;
		for (int index = 0; index < tokens.size(); index++) {
			Token token = tokens.get(index);
			if (closeRepeatAfterCondition && index > 0) {
				Token previousToken = tokens.get(index - 1);
				if (previousToken.text().equals(";")
						|| source.substring(previousToken.end(), token.start()).indexOf('\n') >= 0) {
					if (!blocks.isEmpty() && blocks.peek().kind().equals("repeat"))
						popVariableBlock(scopes, blocks);
					closeRepeatAfterCondition = false;
				}
			}
			String text = token.text();
			if (text.equals("{")) { tableDepth++; continue; }
			if (text.equals("}")) { tableDepth = Math.max(0, tableDepth - 1); continue; }
			if (text.equals("local")) {
				int next = index + 1;
				if (next < tokens.size() && tokens.get(next).text().equals("function")) {
					if (next + 1 < tokens.size() && tokens.get(next + 1).type() == TokenType.IDENTIFIER) {
						scopes.peek().add(tokens.get(next + 1).text());
						values.computeIfAbsent(scopes.peek(), ignored -> new HashMap<>()).put(
								tokens.get(next + 1).text(), new StaticValue(ValueType.FUNCTION, null));
					}
				} else {
					while (next < tokens.size() && tokens.get(next).type() == TokenType.IDENTIFIER) {
						scopes.peek().add(tokens.get(next).text());
						next++;
						if (next >= tokens.size() || !tokens.get(next).text().equals(",")) break;
						next++;
					}
				}
				continue;
			}
			if (text.equals("function")) {
				int next = index + 1;
				boolean method = false;
				if (next < tokens.size() && tokens.get(next).type() == TokenType.IDENTIFIER) {
					String name = tokens.get(next).text();
					if (index == 0 || !tokens.get(index - 1).text().equals("local")) {
						if (next + 1 >= tokens.size() || !tokens.get(next + 1).text().equals(".")
								&& !tokens.get(next + 1).text().equals(":")) {
							Set<String> owner = variableScope(scopes, name);
							if (owner == null) owner = scopes.getLast();
							owner.add(name);
							values.computeIfAbsent(owner, ignored -> new HashMap<>()).put(name,
									new StaticValue(ValueType.FUNCTION, null));
						}
					}
					while (next + 2 < tokens.size() && (tokens.get(next + 1).text().equals(".")
							|| tokens.get(next + 1).text().equals(":"))
							&& tokens.get(next + 2).type() == TokenType.IDENTIFIER) {
						method |= tokens.get(next + 1).text().equals(":");
						next += 2;
					}
				}
				while (next < tokens.size() && !tokens.get(next).text().equals("(")) next++;
				Set<String> parameters = new LinkedHashSet<>();
				if (method) parameters.add("self");
				for (int parameter = next + 1; parameter < tokens.size()
						&& !tokens.get(parameter).text().equals(")"); parameter++) {
					if (tokens.get(parameter).type() == TokenType.IDENTIFIER)
						parameters.add(tokens.get(parameter).text());
				}
				scopes.push(parameters);
				blocks.push(new VariableBlock("function", true));
				continue;
			}
			if (text.equals("for")) {
				Set<String> loop = new LinkedHashSet<>();
				for (int next = index + 1; next < tokens.size(); next++) {
					String candidate = tokens.get(next).text();
					if (candidate.equals("=") || candidate.equals("in") || candidate.equals("do")) break;
					if (tokens.get(next).type() == TokenType.IDENTIFIER) loop.add(candidate);
				}
				scopes.push(loop);
				int afterNames = index + 1 + loop.size() * 2 - 1;
				if (afterNames < tokens.size() && tokens.get(afterNames).text().equals("="))
					for (String name : loop) values.computeIfAbsent(loop, ignored -> new HashMap<>())
							.put(name, new StaticValue(ValueType.INTEGER, null));
				blocks.push(new VariableBlock("loop", true));
				pendingLoopDo++;
				continue;
			}
			if (text.equals("then")) {
				scopes.push(new LinkedHashSet<>());
				blocks.push(new VariableBlock("branch", true));
				continue;
			}
			if (text.equals("else") || text.equals("elseif")) {
				if (!blocks.isEmpty() && blocks.peek().kind().equals("branch")) popVariableBlock(scopes, blocks);
				if (text.equals("else")) {
					scopes.push(new LinkedHashSet<>());
					blocks.push(new VariableBlock("branch", true));
				}
				continue;
			}
			if (text.equals("repeat")) {
				scopes.push(new LinkedHashSet<>());
				blocks.push(new VariableBlock("repeat", true));
				continue;
			}
			if (text.equals("do")) {
				if (pendingLoopDo > 0) pendingLoopDo--;
				else {
					scopes.push(new LinkedHashSet<>());
					blocks.push(new VariableBlock("do", true));
				}
				continue;
			}
			if (text.equals("end")) {
				popVariableBlock(scopes, blocks);
				continue;
			}
			if (text.equals("until")) {
				closeRepeatAfterCondition = true;
				continue;
			}
			if (token.type() != TokenType.IDENTIFIER) continue;
			String previous = index > 0 ? tokens.get(index - 1).text() : "";
			if (previous.equals(".") || previous.equals(":")) continue;
			if (tableDepth > 0) continue;
			if (isAssignmentTarget(tokens, index)) {
				Set<String> owner = variableScope(scopes, text);
				if (owner == null) { owner = scopes.getLast(); owner.add(text); }
				StaticValue value = completionAssignment(tokens, index, source, lookup);
				// A callback body is not executed by completion. Do not treat an outer write as a known live value.
				if (owner != scopes.peek() && blocks.stream().anyMatch(block -> block.kind().equals("function")))
					value = new StaticValue(ValueType.UNKNOWN, null);
				values.computeIfAbsent(owner, ignored -> new HashMap<>()).put(text, value);
			}
		}
		if (closeRepeatAfterCondition && !tokens.isEmpty()) {
			String tail = source.substring(tokens.getLast().end(), end);
			if ((tail.indexOf('\n') >= 0 || tail.indexOf(';') >= 0)
					&& !blocks.isEmpty() && blocks.peek().kind().equals("repeat"))
				popVariableBlock(scopes, blocks);
		}
		return scopeValues(scopes, values);
	}

	private static Set<String> variableScope(Deque<Set<String>> scopes, String name) {
		for (Set<String> scope : scopes) if (scope.contains(name)) return scope;
		return null;
	}

	private static Map<String, StaticValue> scopeValues(Deque<Set<String>> scopes,
			Map<Set<String>, Map<String, StaticValue>> values) {
		Map<String, StaticValue> result = new LinkedHashMap<>();
		for (Set<String> scope : scopes) for (String name : scope) result.putIfAbsent(name,
				values.getOrDefault(scope, Map.of()).getOrDefault(name, new StaticValue(ValueType.UNKNOWN, null)));
		return result;
	}

	private static StaticValue completionAssignment(List<Token> tokens, int nameIndex, String source,
			Map<String, StaticValue> variables) {
		int equal = nameIndex + 1;
		int ordinal = 0;
		for (int previous = nameIndex - 1; previous > 0 && tokens.get(previous).text().equals(",")
				&& tokens.get(previous - 1).type() == TokenType.IDENTIFIER; previous -= 2) ordinal++;
		while (equal < tokens.size() && !tokens.get(equal).text().equals("=")) {
			equal++;
		}
		int start = equal + 1;
		int depth = 0;
		int expression = 0;
		int end = start;
		for (; end < tokens.size(); end++) {
			Token token = tokens.get(end);
			String text = token.text();
			if (depth == 0 && (text.equals(";") || end > start && !sameLine(source, tokens.get(end - 1), token)
					&& !continuesExpression(tokens.get(end - 1).text()) && !continuesExpression(text))) break;
			if (depth == 0 && text.equals(",")) {
				if (expression == ordinal) break;
				expression++; start = end + 1; continue;
			}
			if (text.equals("(") || text.equals("{") || text.equals("[")) depth++;
			if (text.equals(")") || text.equals("}") || text.equals("]")) {
				if (depth == 0) break;
				depth--;
			}
		}
		if (start >= tokens.size() || start >= end || expression != ordinal) return new StaticValue(ValueType.UNKNOWN, null);
		List<Token> expressionTokens = tokens.subList(start, end);
		String module = LuaModuleCompletion.requiredModule(expressionTokens);
		if (module != null) return new StaticValue(ValueType.UNKNOWN, null, module);
		StaticValue literal = infer(expressionTokens, source, variables);
		if (literal.type() != ValueType.UNKNOWN) return literal;
		String expressionText = source.substring(tokens.get(start).start(), tokens.get(end - 1).end()).strip();
		Matcher call = Pattern.compile("^([\\w.]+)\\s*\\(").matcher(expressionText);
		if (call.find()) {
			String symbol = call.group(1);
			if (expressionText.matches("(?s).*\\.(?:uuid|playerUuid|entityUuid|vehicleUuid|itemId|blockId|entityId|soundId|dimension)$"))
				return new StaticValue(ValueType.STRING, null);
			LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(symbol);
			String returns = entry == null ? "" : entry.rawReturns();
			if (Set.of("tostring", "type").contains(symbol) || returns.contains("字符串")) return new StaticValue(ValueType.STRING, null);
			if (symbol.equals("tonumber") || returns.contains("整数") || returns.contains("数量")) return new StaticValue(ValueType.INTEGER, null);
			if (returns.contains("布尔") || returns.startsWith("是否") || returns.equals("true")) return new StaticValue(ValueType.BOOLEAN, null);
			if (returns.contains("数组") || returns.contains("信息表") || returns.startsWith("{")) return new StaticValue(ValueType.TABLE, null);
		}
		if (expressionText.matches("[\\w.]+\\.(?:uuid|playerUuid|entityUuid|vehicleUuid|itemId|blockId|entityId|effectId|soundId|dimension)"))
			return new StaticValue(ValueType.STRING, null);
		if (expressionTokens.stream().anyMatch(token -> token.text().equals(".."))) return new StaticValue(ValueType.STRING, null);
		if (expressionTokens.stream().anyMatch(token -> Set.of("==", "~=", "<", ">", "<=", ">=", "not").contains(token.text())))
			return new StaticValue(ValueType.BOOLEAN, null);
		if (expressionTokens.stream().allMatch(token -> token.type() == TokenType.NUMBER
				|| token.type() == TokenType.IDENTIFIER && Set.of(ValueType.INTEGER, ValueType.NUMBER)
						.contains(variables.getOrDefault(token.text(), new StaticValue(ValueType.UNKNOWN, null)).type())
				|| Set.of("+", "-", "*", "/", "%", "^", "(", ")").contains(token.text())))
			return new StaticValue(expressionTokens.stream().anyMatch(token -> token.text().equals("/") || token.text().equals("^")
					|| token.type() == TokenType.NUMBER && numberValue(token.text()).type() == ValueType.NUMBER
					|| token.type() == TokenType.IDENTIFIER && variables.getOrDefault(token.text(),
							new StaticValue(ValueType.UNKNOWN, null)).type() == ValueType.NUMBER)
					? ValueType.NUMBER : ValueType.INTEGER, null);
		return literal;
	}

	static ExpectedType parameterType(LuaCursorContext.Parameter parameter) {
		LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(parameter.symbol());
		McLuaIdeApi.ValueType declared = entry != null && parameter.index() < entry.parameterTypes().size()
				? entry.parameterTypes().get(parameter.index()) : null;
		return expectedType(parameter.symbol(), cleanParameter(parameter.name()), declared);
	}

	static boolean accepts(ExpectedType expected, StaticValue value) { return matches(expected, value.type()); }

	static Set<String> parameterValues(LuaCursorContext.Parameter parameter) {
		String name = cleanParameter(parameter.name());
		if (name.equals("eventName")) return EVENT_NAMES;
		if (name.equals("language")) return Set.of("zh_cn", "zh_tw", "en_us", "en_gb", "ja_jp", "ko_kr", "de_de", "fr_fr", "es_es", "pt_br", "ru_ru");
		if (name.equals("kind") && !parameter.symbol().equals("MC.Registry.list")) return Set.of();
		return ENUM_VALUES.getOrDefault(name, Set.of());
	}

	private static List<Diagnostic> validateUndefinedVariables(String source) {
		List<Token> tokens = lex(source);
		List<Diagnostic> result = new ArrayList<>();
		Deque<Set<String>> scopes = new ArrayDeque<>();
		scopes.push(new HashSet<>(PREDEFINED_GLOBALS));
		Deque<VariableBlock> blocks = new ArrayDeque<>();
		Set<Integer> declarations = new HashSet<>();
		Set<Integer> functionPath = new HashSet<>();
		int tableDepth = 0;
		int pendingLoopDo = 0;
		boolean closeRepeatAfterCondition = false;

		for (int index = 0; index < tokens.size() && result.size() < MAX_DIAGNOSTICS; index++) {
			Token token = tokens.get(index);
			if (closeRepeatAfterCondition && index > 0) {
				Token previousToken = tokens.get(index - 1);
				if (previousToken.text().equals(";")
						|| source.substring(previousToken.end(), token.start()).indexOf('\n') >= 0) {
					if (!blocks.isEmpty() && blocks.peek().kind().equals("repeat")) popVariableBlock(scopes, blocks);
					closeRepeatAfterCondition = false;
				}
			}
			String text = token.text();
			if (text.equals("{")) tableDepth++;
			else if (text.equals("}")) tableDepth = Math.max(0, tableDepth - 1);

			if (text.equals("local")) {
				int cursor = index + 1;
				if (cursor < tokens.size() && tokens.get(cursor).text().equals("function")) cursor++;
				while (cursor < tokens.size()) {
					Token name = tokens.get(cursor);
					if (name.type() != TokenType.IDENTIFIER) break;
					declarations.add(cursor);
					scopes.peek().add(name.text());
					cursor++;
					if (cursor >= tokens.size() || !tokens.get(cursor).text().equals(",")) break;
					cursor++;
				}
				continue;
			}

			if (text.equals("function")) {
				int cursor = index + 1;
				boolean method = false;
				if (cursor < tokens.size() && tokens.get(cursor).type() == TokenType.IDENTIFIER) {
					if (!declarations.contains(cursor)) {
						if (cursor + 1 < tokens.size() && (tokens.get(cursor + 1).text().equals(".")
								|| tokens.get(cursor + 1).text().equals(":"))) {
							if (!isDefined(scopes, tokens.get(cursor).text())) {
								result.add(diagnostic(source, "未定义变量：“" + tokens.get(cursor).text() + "”",
										tokens.get(cursor).start(), tokens.get(cursor).end()));
							}
						} else {
							scopes.getLast().add(tokens.get(cursor).text());
							declarations.add(cursor);
						}
					}
					functionPath.add(cursor);
					while (cursor + 2 < tokens.size() && (tokens.get(cursor + 1).text().equals(".")
							|| tokens.get(cursor + 1).text().equals(":"))
							&& tokens.get(cursor + 2).type() == TokenType.IDENTIFIER) {
						method |= tokens.get(cursor + 1).text().equals(":");
						cursor += 2;
						functionPath.add(cursor);
					}
				}
				while (cursor < tokens.size() && !tokens.get(cursor).text().equals("(")) cursor++;
				Set<String> functionScope = new HashSet<>();
				if (method) functionScope.add("self");
				for (int parameter = cursor + 1; parameter < tokens.size()
						&& !tokens.get(parameter).text().equals(")"); parameter++) {
					if (tokens.get(parameter).type() == TokenType.IDENTIFIER) {
						declarations.add(parameter);
						functionScope.add(tokens.get(parameter).text());
					}
				}
				scopes.push(functionScope);
				blocks.push(new VariableBlock("function", true));
				continue;
			}

			if (text.equals("for")) {
				Set<String> loopScope = new HashSet<>();
				for (int cursor = index + 1; cursor < tokens.size(); cursor++) {
					String candidate = tokens.get(cursor).text();
					if (candidate.equals("=") || candidate.equals("in") || candidate.equals("do")) break;
					if (tokens.get(cursor).type() == TokenType.IDENTIFIER) {
						declarations.add(cursor);
						loopScope.add(candidate);
					}
				}
				scopes.push(loopScope);
				blocks.push(new VariableBlock("loop", true));
				pendingLoopDo++;
				continue;
			}

			if (text.equals("then")) {
				scopes.push(new HashSet<>());
				blocks.push(new VariableBlock("branch", true));
				continue;
			}
			if (text.equals("else") || text.equals("elseif")) {
				if (!blocks.isEmpty() && blocks.peek().kind().equals("branch")) popVariableBlock(scopes, blocks);
				if (text.equals("else")) {
					scopes.push(new HashSet<>());
					blocks.push(new VariableBlock("branch", true));
				}
				continue;
			}
			if (text.equals("repeat")) {
				scopes.push(new HashSet<>());
				blocks.push(new VariableBlock("repeat", true));
				continue;
			}
			if (text.equals("do")) {
				if (pendingLoopDo > 0) pendingLoopDo--;
				else {
					scopes.push(new HashSet<>());
					blocks.push(new VariableBlock("do", true));
				}
				continue;
			}
			if (text.equals("end")) {
				popVariableBlock(scopes, blocks);
				continue;
			}
			if (text.equals("until")) {
				// repeat 的局部变量在 until 条件里仍然可见，条件结束后才退出作用域。
				closeRepeatAfterCondition = true;
				continue;
			}

			if (token.type() != TokenType.IDENTIFIER || declarations.contains(index)
					|| functionPath.contains(index) || LuaApiDocumentation.isApiRoot(text)) continue;
			String previous = index > 0 ? tokens.get(index - 1).text() : "";
			String next = index + 1 < tokens.size() ? tokens.get(index + 1).text() : "";
			if (previous.equals(".") || previous.equals(":") || previous.equals("::")
					|| next.equals("::") || previous.equals("goto")) continue;
			if (tableDepth > 0 && next.equals("=") && (previous.equals("{") || previous.equals(","))) continue;
			if (isAssignmentTarget(tokens, index)) {
				scopes.getLast().add(text);
				declarations.add(index);
				continue;
			}
			if (!isDefined(scopes, text)) {
				result.add(diagnostic(source, "未定义变量：“" + text + "”", token.start(), token.end()));
			}
		}
		return List.copyOf(result);
	}

	private static boolean isDefined(Deque<Set<String>> scopes, String name) {
		for (Set<String> scope : scopes) if (scope.contains(name)) return true;
		return false;
	}

	private static boolean isAssignmentTarget(List<Token> tokens, int index) {
		int cursor = index;
		while (cursor + 1 < tokens.size()) {
			String separator = tokens.get(cursor + 1).text();
			if (separator.equals("=")) return true;
			if (!separator.equals(",") || cursor + 2 >= tokens.size()
					|| tokens.get(cursor + 2).type() != TokenType.IDENTIFIER) return false;
			cursor += 2;
		}
		return false;
	}

	private static void popVariableBlock(Deque<Set<String>> scopes, Deque<VariableBlock> blocks) {
		if (blocks.isEmpty()) return;
		VariableBlock block = blocks.pop();
		if (block.scope() && scopes.size() > 1) scopes.pop();
	}

	private static boolean sameLine(String source, Token a, Token b) {
		int start=Math.max(0,Math.min(source.length(),a.end()));
		int end=Math.max(start,Math.min(source.length(),b.start()));
		return source.indexOf('\n',start)<0 || source.indexOf('\n',start)>=end;
	}

	private static Call parseCall(List<Token> tokens, int openIndex, String source) {
		int parenthesis = 1;
		int brackets = 0;
		int braces = 0;
		int argumentStart = openIndex + 1;
		int pendingLoopDo = 0;
		Deque<String> blocks = new ArrayDeque<>();
		List<Argument> arguments = new ArrayList<>();
		for (int index = openIndex + 1; index < tokens.size(); index++) {
			Token token = tokens.get(index);
			String text = token.text();
			if (token.type() == TokenType.KEYWORD) {
				switch (text) {
					case "function", "if" -> blocks.push("end");
					case "for", "while" -> {
						blocks.push("end");
						pendingLoopDo++;
					}
					case "repeat" -> blocks.push("until");
					case "do" -> {
						if (pendingLoopDo > 0) pendingLoopDo--;
						else blocks.push("end");
					}
					case "end" -> {
						if (!blocks.isEmpty() && blocks.peek().equals("end")) blocks.pop();
					}
					case "until" -> {
						if (!blocks.isEmpty() && blocks.peek().equals("until")) blocks.pop();
					}
				}
			}
			if (text.equals("(")) parenthesis++;
			else if (text.equals(")")) {
				parenthesis--;
				if (parenthesis == 0) {
					addArgument(tokens, argumentStart, index, arguments);
					return new Call(arguments, token.start(), token.end());
				}
			} else if (text.equals("[")) brackets++;
			else if (text.equals("]")) brackets--;
			else if (text.equals("{")) braces++;
			else if (text.equals("}")) braces--;
			else if (text.equals(",") && parenthesis == 1 && brackets == 0 && braces == 0 && blocks.isEmpty()) {
				addArgument(tokens, argumentStart, index, arguments);
				argumentStart = index + 1;
			}
		}
		return null;
	}

	private static void addArgument(List<Token> tokens, int startIndex, int endIndex, List<Argument> arguments) {
		if (startIndex >= endIndex) return;
		List<Token> argumentTokens = List.copyOf(tokens.subList(startIndex, endIndex));
		arguments.add(new Argument(argumentTokens, argumentTokens.getFirst().start(), argumentTokens.getLast().end()));
	}

	private static void validateCall(String source, String symbol, LuaApiDocumentation.Entry entry, Call call,
			Map<String, StaticValue> variables, List<Diagnostic> diagnostics) {
		List<String> parameters = entry.parameters();
		int required = (int) parameters.stream().filter(parameter -> !parameter.endsWith("?")).count();
		if (call.arguments().size() < required) {
			String missing = cleanParameter(parameters.get(call.arguments().size()));
			diagnostics.add(diagnostic(source,
					symbol + " 参数不足：至少需要 " + required + " 个参数，缺少参数“" + missing + "”",
					call.closeStart(), call.closeEnd()));
			return;
		}
		if (call.arguments().size() > parameters.size()) {
			Argument extra = call.arguments().get(parameters.size());
			diagnostics.add(diagnostic(source,
					symbol + " 参数过多：最多只能填写 " + parameters.size() + " 个参数",
					extra.start(), extra.end()));
		}
		int count = Math.min(call.arguments().size(), parameters.size());
		if (symbol.equals("MC.Sound.play")) {
			List<ValueType> types = call.arguments().stream().map(argument -> infer(argument.tokens(), source, variables).type()).toList();
			// The token fallback cannot know how many values a final unknown call
			// returns (e.g. play(id, 0, getYZ())). Do not assume those are missing nils.
			boolean possiblyExpanded = types.size() < 4 && !types.isEmpty() && types.getLast() == ValueType.UNKNOWN;
			int mixed = possiblyExpanded ? -1 : mixedSoundCoordinate(types);
			if (mixed >= 0) {
				Argument argument = mixed < call.arguments().size() ? call.arguments().get(mixed) : null;
				diagnostics.add(diagnostic(source, "MC.Sound.play 的 x、y、z 必须同时填写数字，或同时传 nil（不分坐标播放）",
						argument == null ? call.closeStart() : argument.start(), argument == null ? call.closeEnd() : argument.end()));
			}
		}
		for (int index = 0; index < count && diagnostics.size() < MAX_DIAGNOSTICS; index++) {
			String rawParameter = parameters.get(index);
			String parameter = cleanParameter(rawParameter);
			Argument argument = call.arguments().get(index);
			StaticValue value = infer(argument.tokens(), source, variables);
			McLuaIdeApi.ValueType declaredType = index < entry.parameterTypes().size()
					? entry.parameterTypes().get(index) : null;
			ExpectedType expected = expectedType(symbol, parameter, declaredType);
			if (value.type() == ValueType.NIL && rawParameter.endsWith("?")) continue;
			if (!matches(expected, value.type())) {
				diagnostics.add(diagnostic(source,
						symbol + " 的参数" + (index + 1) + "“" + parameter + "”类型错误：需要"
								+ expected.chinese() + "，当前是" + value.type().chinese(),
						argument.start(), argument.end()));
				continue;
			}
			String valueProblem = validateLiteral(symbol, parameter, value, declaredType);
			if (valueProblem != null) {
				diagnostics.add(diagnostic(source,
						symbol + " 的参数" + (index + 1) + "“" + parameter + "”填写错误：" + valueProblem,
						argument.start(), argument.end()));
			}
		}
	}

	static ExpectedType expectedType(String symbol, String parameter, McLuaIdeApi.ValueType declaredType) {
		if (Set.of("rows", "slotId", "expectedContainerId").contains(parameter)) return ExpectedType.INTEGER;
		if (parameter.equals("readOnly")) return ExpectedType.BOOLEAN;
		if (symbol.equals("MC.Player.openChest") && parameter.equals("title")) return ExpectedType.TABLE_OR_STRING;
		if (declaredType != null) {
			return switch (declaredType) {
				case ANY -> ExpectedType.ANY;
				case NUMBER -> ExpectedType.NUMBER;
				case INTEGER -> ExpectedType.INTEGER;
				case BOOLEAN -> ExpectedType.BOOLEAN;
				case TABLE -> ExpectedType.TABLE;
				case FUNCTION -> ExpectedType.FUNCTION;
				case STRING, BLOCK_ID, ITEM_ID, ENTITY_ID, EFFECT_ID, ENCHANTMENT_ID,
						SOUND_ID, PARTICLE_ID, STRUCTURE_ID, LOOT_TABLE_ID,
						RESOURCE_ID, PLAYER_UUID, ENTITY_UUID ->
						ExpectedType.STRING;
			};
		}
		if (parameter.equals("callback")) return ExpectedType.FUNCTION;
		if (TABLE_PARAMETERS.contains(parameter)) return ExpectedType.TABLE;
		if (parameter.equals("components") || parameter.endsWith("OrJson")) return ExpectedType.TABLE_OR_STRING;
		// addData takes a Lua table (data components / NBT fields) or an SNBT string; hideData takes a name or a list of names.
		if ((symbol.endsWith(".addData") && parameter.equals("data")) || (symbol.endsWith(".hideData") && parameter.equals("keys"))) return ExpectedType.TABLE_OR_STRING;
		if (BOOLEAN_PARAMETERS.contains(parameter)) return ExpectedType.BOOLEAN;
		if (parameter.equals("slot")) {
			return symbol.startsWith("MC.Item.") || symbol.startsWith("MC.Container.")
					? ExpectedType.INTEGER : ExpectedType.STRING;
		}
		if (parameter.equals("value")) {
			if (symbol.equals("MC.Entity.setGlowing") || symbol.equals("MC.Entity.setInvulnerable")
					|| symbol.equals("MC.Server.setFrozen")) return ExpectedType.BOOLEAN;
			if (symbol.equals("MC.GameRule.set")) return ExpectedType.ANY;
			return symbol.startsWith("MC.Score.") ? ExpectedType.INTEGER : ExpectedType.NUMBER;
		}
		if (parameter.equals("time")) return ExpectedType.NUMBER_OR_STRING;
		if (INTEGER_PARAMETERS.contains(parameter)) return ExpectedType.INTEGER;
		if (NUMBER_PARAMETERS.contains(parameter)) {
			if ((symbol.startsWith("MC.Block.") || symbol.startsWith("MC.Container."))
					&& Set.of("x", "y", "z").contains(parameter)) return ExpectedType.INTEGER;
			if (Set.of("x1", "y1", "z1", "x2", "y2", "z2", "toX", "toY", "toZ").contains(parameter)) {
				return ExpectedType.INTEGER;
			}
			return ExpectedType.NUMBER;
		}
		return ExpectedType.STRING;
	}

	/** Parameter indices include soundId. Unknown values are not guessed. */
	static int mixedSoundCoordinate(List<ValueType> types) {
		int nil = -1;
		boolean number = false;
		for (int i = 1; i <= 3; i++) {
			ValueType type = i < types.size() ? types.get(i) : ValueType.NIL;
			if (type == ValueType.NIL) nil = i;
			if (type == ValueType.NUMBER || type == ValueType.INTEGER) number = true;
		}
		return number ? nil : -1;
	}

	static boolean matches(ExpectedType expected, ValueType actual) {
		if (actual == ValueType.UNKNOWN) return true;
		return switch (expected) {
			case ANY -> true;
			case STRING -> actual == ValueType.STRING;
			case NUMBER -> actual == ValueType.NUMBER || actual == ValueType.INTEGER;
			case INTEGER -> actual == ValueType.INTEGER;
			case BOOLEAN -> actual == ValueType.BOOLEAN;
			case FUNCTION -> actual == ValueType.FUNCTION;
			case TABLE -> actual == ValueType.TABLE;
			case TABLE_OR_STRING -> actual == ValueType.TABLE || actual == ValueType.STRING;
			case NUMBER_OR_STRING -> actual == ValueType.NUMBER || actual == ValueType.INTEGER || actual == ValueType.STRING;
		};
	}

	static String validateLiteral(String symbol, String parameter, StaticValue value,
			McLuaIdeApi.ValueType declaredType) {
		if (value.literal() == null) return null;
		String literal = value.literal();
		boolean declaredUuid = declaredType == McLuaIdeApi.ValueType.PLAYER_UUID
				|| declaredType == McLuaIdeApi.ValueType.ENTITY_UUID;
		if ((declaredUuid || parameter.equals("uuid") || parameter.equals("playerUuid")
				|| parameter.equals("vehicleUuid")) && !isUuid(literal)) {
			return "需要标准 UUID 字符串，例如 123e4567-e89b-12d3-a456-426614174000";
		}
		boolean declaredResource = declaredType == McLuaIdeApi.ValueType.BLOCK_ID
				|| declaredType == McLuaIdeApi.ValueType.ITEM_ID
				|| declaredType == McLuaIdeApi.ValueType.ENTITY_ID
				|| declaredType == McLuaIdeApi.ValueType.RESOURCE_ID;
		if ((declaredResource || RESOURCE_PARAMETERS.contains(parameter)) && !literal.isBlank()
				&& !(parameter.equals("id") && symbol.startsWith("MC.Mod."))
				&& Identifier.tryParse(literal.contains(":") ? literal : "minecraft:" + literal) == null) {
			return "资源 ID 格式无效；可填写 stone 或 minecraft:stone";
		}
		Set<String> allowed = parameter.equals("kind") && !symbol.equals("MC.Registry.list")
				? null : ENUM_VALUES.get(parameter);
		if (allowed != null) {
			boolean matches = parameter.equals("method")
					? allowed.contains(literal.toUpperCase(Locale.ROOT)) : allowed.contains(literal);
			if (!matches) return "可用值为 " + String.join("、", allowed);
		}
		if (parameter.equals("eventName") && !EVENT_NAMES.contains(literal)) {
			return "未知事件；请使用 MC.Event.names() 或事件专用注册方法";
		}
		if (parameter.equals("url") && !literal.startsWith("http://") && !literal.startsWith("https://")) {
			return "URL 必须以 http:// 或 https:// 开头";
		}
		return null;
	}

	private static StaticValue infer(List<Token> tokens, String source, Map<String, StaticValue> variables) {
		if (tokens.isEmpty()) return new StaticValue(ValueType.UNKNOWN, null);
		if (tokens.size() == 2 && (tokens.getFirst().text().equals("-") || tokens.getFirst().text().equals("+"))
				&& tokens.get(1).type() == TokenType.NUMBER) {
			return numberValue(tokens.get(1).text());
		}
		Token first = tokens.getFirst();
		if (tokens.size() != 1 && !(first.type() == TokenType.SYMBOL && first.text().equals("{"))
				&& !(first.type() == TokenType.KEYWORD && first.text().equals("function")))
			return new StaticValue(ValueType.UNKNOWN, null);
		return switch (first.type()) {
			case STRING -> new StaticValue(ValueType.STRING, stringValue(first.text()));
			case NUMBER -> numberValue(first.text());
			case BOOLEAN -> new StaticValue(ValueType.BOOLEAN, first.text());
			case NIL -> new StaticValue(ValueType.NIL, null);
			case IDENTIFIER -> tokens.size() == 1
					? variables.getOrDefault(first.text(), new StaticValue(ValueType.UNKNOWN, null))
					: new StaticValue(ValueType.UNKNOWN, null);
			case KEYWORD -> first.text().equals("function")
					? new StaticValue(ValueType.FUNCTION, null)
					: first.text().equals("not")
							? new StaticValue(ValueType.BOOLEAN, null)
							: new StaticValue(ValueType.UNKNOWN, null);
			case SYMBOL -> first.text().equals("{")
					? new StaticValue(ValueType.TABLE, null) : new StaticValue(ValueType.UNKNOWN, null);
		};
	}

	private static StaticValue numberValue(String literal) {
		try {
			String normalized = literal.toLowerCase(Locale.ROOT);
			if (normalized.startsWith("0x") && !normalized.contains(".") && !normalized.contains("p")) {
				return new StaticValue(ValueType.INTEGER, literal);
			}
			double value = Double.parseDouble(literal);
			return new StaticValue(Double.isFinite(value) && value == Math.rint(value)
					? ValueType.INTEGER : ValueType.NUMBER, literal);
		} catch (NumberFormatException ignored) {
			return new StaticValue(ValueType.NUMBER, literal);
		}
	}

	static String stringValue(String raw) {
		if (raw.startsWith("[") && raw.endsWith("]")) {
			int open = raw.indexOf('[', 1);
			int level = open - 1;
			int contentEnd = raw.length() - level - 2;
			if (open < 1 || contentEnd < open + 1) return null;
			int contentStart = open + 1;
			if (contentStart < contentEnd && (raw.charAt(contentStart) == '\n' || raw.charAt(contentStart) == '\r')) {
				char first = raw.charAt(contentStart++);
				if (contentStart < contentEnd && (raw.charAt(contentStart) == '\n' || raw.charAt(contentStart) == '\r')
						&& raw.charAt(contentStart) != first) contentStart++;
			}
			return raw.substring(contentStart, contentEnd);
		}
		if (raw.length() < 2 || raw.indexOf('\\') >= 0) return null;
		return raw.substring(1, raw.length() - 1);
	}

	static String canonicalSymbol(String symbol) {
		for (String candidate : LuaApiDocumentation.symbols()) {
			if (candidate.equals(symbol)) return candidate;
		}
		for (String candidate : LuaApiDocumentation.symbols()) {
			if (candidate.equalsIgnoreCase(symbol)) return candidate;
		}
		return null;
	}

	static List<Token> lex(String source) {
		List<Token> result = new ArrayList<>();
		int index = 0;
		while (index < source.length()) {
			char current = source.charAt(index);
			if (Character.isWhitespace(current)) {
				index++;
				continue;
			}
			if (source.startsWith("--", index)) {
				int level = longBracketLevel(source, index + 2);
				index = level >= 0 ? longBracketEnd(source, index + 2, level) : lineEnd(source, index + 2);
				continue;
			}
			if (current == '\'' || current == '"') {
				int end = quotedStringEnd(source, index, current);
				result.add(new Token(index, end, source.substring(index, end), TokenType.STRING));
				index = end;
				continue;
			}
			int longLevel = longBracketLevel(source, index);
			if (longLevel >= 0) {
				int end = longBracketEnd(source, index, longLevel);
				result.add(new Token(index, end, source.substring(index, end), TokenType.STRING));
				index = end;
				continue;
			}
			if (Character.isDigit(current) || current == '.' && index + 1 < source.length()
					&& Character.isDigit(source.charAt(index + 1))) {
				Matcher matcher = NUMBER.matcher(source);
				matcher.region(index, source.length());
				if (matcher.lookingAt()) {
					int end = matcher.end();
					result.add(new Token(index, end, source.substring(index, end), TokenType.NUMBER));
					index = end;
					continue;
				}
			}
			if (isIdentifierStart(current)) {
				int end = index + 1;
				while (end < source.length() && isIdentifierPart(source.charAt(end))) end++;
				String text = source.substring(index, end);
				TokenType type = text.equals("true") || text.equals("false") ? TokenType.BOOLEAN
						: text.equals("nil") ? TokenType.NIL
						: isKeyword(text) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
				result.add(new Token(index, end, text, type));
				index = end;
				continue;
			}
			String operator = index + 1 < source.length() ? source.substring(index, index + 2) : "";
			if (TWO_CHARACTER_SYMBOLS.contains(operator)) {
				result.add(new Token(index, index + 2, operator, TokenType.SYMBOL));
				index += 2;
			} else {
				result.add(new Token(index, index + 1, source.substring(index, index + 1), TokenType.SYMBOL));
				index++;
			}
		}
		return List.copyOf(result);
	}

	private static boolean isKeyword(String text) {
		return KEYWORDS.contains(text);
	}

	private static int quotedStringEnd(String source, int start, char quote) {
		int index = start + 1;
		while (index < source.length()) {
			char current = source.charAt(index++);
			if (current == '\\' && index < source.length()) index++;
			else if (current == quote || current == '\n' || current == '\r') break;
		}
		return index;
	}

	private static int longBracketLevel(String source, int start) {
		if (start < 0 || start >= source.length() || source.charAt(start) != '[') return -1;
		int cursor = start + 1;
		while (cursor < source.length() && source.charAt(cursor) == '=') cursor++;
		return cursor < source.length() && source.charAt(cursor) == '[' ? cursor - start - 1 : -1;
	}

	private static int longBracketEnd(String source, int start, int level) {
		String closing = "]" + "=".repeat(level) + "]";
		int closingStart = source.indexOf(closing, start + level + 2);
		return closingStart < 0 ? source.length() : closingStart + closing.length();
	}

	private static boolean isIdentifierStart(char value) {
		return value == '_' || Character.isLetter(value);
	}

	private static boolean isIdentifierPart(char value) {
		return value == '_' || Character.isLetterOrDigit(value);
	}

	private static boolean isUuid(String value) {
		try {
			UUID.fromString(value);
			return value.length() == 36;
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private static String cleanParameter(String parameter) {
		return parameter.endsWith("?") ? parameter.substring(0, parameter.length() - 1) : parameter;
	}

	static Diagnostic diagnostic(String source, String message, int rawStart, int rawEnd) {
		int start = Math.max(0, Math.min(source.length(), rawStart));
		int end = Math.max(start, Math.min(source.length(), rawEnd));
		if (!source.isEmpty() && (start == end || isWhitespaceOnly(source, start, end))) {
			int anchor = previousVisibleCharacter(source, start);
			if (anchor < 0) anchor = nextVisibleCharacter(source, end);
			if (anchor >= 0) {
				start = anchor;
				end = anchor + 1;
			} else if (start == end) {
				start = Math.max(0, Math.min(source.length() - 1, start));
				end = Math.min(source.length(), start + 1);
			}
		}
		int line = 1;
		int column = 1;
		for (int index = 0; index < start; index++) {
			if (source.charAt(index) == '\n') {
				line++;
				column = 1;
			} else {
				column++;
			}
		}
		return new Diagnostic(message, start, end, line, column);
	}

	private static boolean isWhitespaceOnly(String source, int start, int end) {
		if (start >= end) return true;
		for (int index = start; index < end; index++) {
			if (!Character.isWhitespace(source.charAt(index))) return false;
		}
		return true;
	}

	private static int previousVisibleCharacter(String source, int offset) {
		for (int index = Math.min(source.length(), offset) - 1; index >= 0; index--) {
			if (!Character.isWhitespace(source.charAt(index))) return index;
		}
		return -1;
	}

	private static int nextVisibleCharacter(String source, int offset) {
		for (int index = Math.max(0, offset); index < source.length(); index++) {
			if (!Character.isWhitespace(source.charAt(index))) return index;
		}
		return -1;
	}

	private static int lineStart(String source, int targetLine) {
		int line = 1;
		for (int index = 0; index < source.length(); index++) {
			if (line == targetLine) return index;
			if (source.charAt(index) == '\n') line++;
		}
		return source.length();
	}

	private static int lineEnd(String source, int start) {
		int newline = source.indexOf('\n', Math.max(0, start));
		return newline < 0 ? source.length() : newline;
	}

	private static int lastVisibleCharacter(String source) {
		return lastVisibleCharacter(source, 0, source.length());
	}

	private static int lastVisibleCharacter(String source, int start, int end) {
		for (int index = Math.min(source.length(), end) - 1; index >= Math.max(0, start); index--) {
			if (!Character.isWhitespace(source.charAt(index))) return index;
		}
		return Math.max(0, Math.min(source.length(), start));
	}

	private static String safeMessage(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	record Analysis(List<Diagnostic> diagnostics) {
		Analysis {
			diagnostics = List.copyOf(diagnostics);
		}

		boolean valid() {
			return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
		}

		long errorCount() { return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Severity.ERROR).count(); }
		long warningCount() { return diagnostics.size() - errorCount(); }

		Diagnostic first() {
			return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
					.findFirst().orElse(diagnostics.isEmpty() ? null : diagnostics.getFirst());
		}
	}

	enum Severity { ERROR, WARNING }

	record Diagnostic(String message, int start, int end, int line, int column, Severity severity) {
		Diagnostic(String message, int start, int end, int line, int column) {
			this(message, start, end, line, column, Severity.ERROR);
		}
		String displayMessage() {
			return "第 " + line + " 行，第 " + column + " 列：" + message;
		}
	}

	record Token(int start, int end, String text, TokenType type) {
	}

	private record VariableBlock(String kind, boolean scope) {
	}

	private record Argument(List<Token> tokens, int start, int end) {
	}

	private record Call(List<Argument> arguments, int closeStart, int closeEnd) {
	}

	record StaticValue(ValueType type, String literal, String moduleName) {
		StaticValue(ValueType type, String literal) { this(type, literal, null); }
	}

	enum TokenType { IDENTIFIER, KEYWORD, STRING, NUMBER, BOOLEAN, NIL, SYMBOL }

	enum ValueType {
		UNKNOWN("无法静态判断的表达式"), STRING("字符串"), NUMBER("小数"), INTEGER("整数"),
		BOOLEAN("布尔值"), FUNCTION("函数"), TABLE("表"), NIL("nil");

		private final String chinese;

		ValueType(String chinese) {
			this.chinese = chinese;
		}

		String chinese() {
			return chinese;
		}
	}

	enum ExpectedType {
		ANY("任意值"), STRING("字符串"), NUMBER("数字"), INTEGER("整数"), BOOLEAN("布尔值"),
		FUNCTION("函数"), TABLE("表"), TABLE_OR_STRING("Lua 表或 JSON 对象字符串"),
		NUMBER_OR_STRING("数字或字符串");

		private final String chinese;

		ExpectedType(String chinese) {
			this.chinese = chinese;
		}

		String chinese() {
			return chinese;
		}
	}
}
