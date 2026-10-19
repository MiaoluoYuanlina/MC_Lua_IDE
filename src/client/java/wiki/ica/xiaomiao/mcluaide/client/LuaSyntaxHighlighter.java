package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LuaSyntaxHighlighter {
	private static final Map<MultiLineEditBox, EditorState> LUA_EDITORS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Pattern NUMBER_PATTERN = Pattern.compile(
			"(?i)(?:0x[0-9a-f]+(?:\\.[0-9a-f]*)?(?:p[+-]?\\d+)?|(?:\\d+\\.?\\d*|\\.\\d+)(?:e[+-]?\\d+)?)");
	private static final Set<String> KEYWORDS = Set.of(
			"and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
			"local", "not", "or", "repeat", "return", "then", "until", "while");
	private static final Set<String> LITERALS = Set.of("true", "false", "nil");
	private static final Set<String> FUNCTION_SUBCOMMANDS = Set.of(
			"as", "at", "if", "unless", "run", "store", "result", "success", "positioned",
			"rotated", "facing", "anchored", "in", "align", "on", "summon", "entity",
			"block", "blocks", "score", "matches", "data", "predicate", "biome", "loaded",
			"give", "clear", "replace", "with", "from", "set", "add", "remove", "list");
	private static final Set<String> BUILTINS = Set.of(
			"assert", "collectgarbage", "error", "getmetatable", "ipairs", "next", "pairs", "pcall", "print",
			"rawequal", "rawget", "rawlen", "rawset", "select", "setmetatable", "tonumber", "tostring", "type",
			"xpcall", "_G", "_VERSION", "MC", "math", "string", "table", "coroutine", "utf8", "bit32");
	private static final String[] OPERATORS = {
			"...", "//", "<<", ">>", "==", "~=", "<=", ">=", "::", "..",
			"+", "-", "*", "/", "%", "^", "#", "&", "~", "|", "<", ">", "="
	};

	private LuaSyntaxHighlighter() {
	}

	public static void register(MultiLineEditBox editor) {
		LUA_EDITORS.put(editor, new EditorState());
	}

	public static void setJsonMode(MultiLineEditBox editor, boolean jsonMode) {
		EditorState state = state(editor);
		synchronized (state) {
			if (state.jsonMode == jsonMode) return;
			state.jsonMode = jsonMode;
			state.cachedSource = null;
			state.cachedTokens = List.of();
		}
	}

	public static void setPlainMode(MultiLineEditBox editor, boolean plainMode) {
		EditorState state = state(editor);
		synchronized (state) {
			if (state.plainMode == plainMode) return;
			state.plainMode = plainMode;
			state.cachedSource = null;
			state.cachedTokens = List.of();
		}
	}

	public static void setFunctionMode(MultiLineEditBox editor, boolean functionMode) {
		EditorState state = state(editor);
		synchronized (state) {
			if (state.functionMode == functionMode) return;
			state.functionMode = functionMode;
			state.cachedSource = null;
			state.cachedTokens = List.of();
		}
	}

	public static boolean isLuaEditor(MultiLineEditBox editor) {
		return LUA_EDITORS.containsKey(editor);
	}

	public static void clearErrors(MultiLineEditBox editor) {
		EditorState state = state(editor);
		synchronized (state) {
			state.errorRanges = List.of();
		}
	}

	public static void setErrors(MultiLineEditBox editor, List<ErrorRange> ranges) {
		EditorState state = state(editor);
		synchronized (state) {
			state.errorRanges = ranges.stream()
					.filter(range -> range.start() >= 0 && range.end() > range.start())
					.sorted(java.util.Comparator.comparingInt(ErrorRange::start).thenComparing(ErrorRange::warning))
					.toList();
		}
	}

	public static void setTheme(MultiLineEditBox editor, Theme theme) {
		EditorState state = state(editor);
		synchronized (state) {
			state.theme = theme;
		}
	}

	public static void setScale(MultiLineEditBox editor, float scale) {
		EditorState state = state(editor);
		synchronized (state) {
			state.scale = scale;
		}
	}

	public static float scale(MultiLineEditBox editor) {
		EditorState state = state(editor);
		synchronized (state) {
			return state.scale;
		}
	}

	public static void setPersonalization(MultiLineEditBox editor, LuaPersonalizationSettings settings) {
		EditorState state = state(editor);
		synchronized (state) {
			state.lineHeight = settings.lineHeight;
			state.cellWidth = settings.cellWidth;
			state.cursorShape = settings.cursorShape;
			state.cursorWidth = settings.cursorWidth;
			state.cursorColor = settings.cursorColor;
			state.cursorBlink = settings.cursorBlink;
			state.syntaxColors = Map.copyOf(settings.syntaxColors);
		}
	}

	public static float lineHeight(MultiLineEditBox editor) { return state(editor).lineHeight; }
	public static float cellWidth(MultiLineEditBox editor) { return state(editor).cellWidth; }
	public static LuaPersonalizationSettings.CursorShape cursorShape(MultiLineEditBox editor) { return state(editor).cursorShape; }
	public static int cursorWidth(MultiLineEditBox editor) { return state(editor).cursorWidth; }
	public static int cursorColor(MultiLineEditBox editor) { return state(editor).cursorColor; }
	public static boolean cursorBlink(MultiLineEditBox editor) { return state(editor).cursorBlink; }

	private static EditorState state(MultiLineEditBox editor) {
		synchronized (LUA_EDITORS) {
			return LUA_EDITORS.computeIfAbsent(editor, ignored -> new EditorState());
		}
	}

	public static Component highlight(MultiLineEditBox editor, String source, String visibleText,
			int absoluteStart, int logicalLine) {
		EditorState state = state(editor);
		Theme theme;
		Map<ColorRole, Integer> syntaxColors;
		List<ErrorRange> errorRanges;
		List<TokenSpan> tokens;
		synchronized (state) {
			theme = state.theme;
			syntaxColors = state.syntaxColors;
			errorRanges = state.errorRanges;
			refreshCache(state, source);
			tokens = state.cachedTokens;
		}
		if (absoluteStart < 0 || absoluteStart + visibleText.length() > source.length()
				|| !source.regionMatches(absoluteStart, visibleText, 0, visibleText.length())) {
			return highlightSegment(visibleText, visibleText, 0,
					state.plainMode ? List.of() : state.functionMode ? tokenizeFunction(visibleText)
							: state.jsonMode ? tokenizeJson(visibleText) : tokenize(visibleText), theme,
					syntaxColors, List.of());
		}
		return highlightSegment(source, visibleText, absoluteStart, tokens, theme, syntaxColors, errorRanges);
	}

	public static int logicalLineAt(MultiLineEditBox editor, String source, int absoluteStart) {
		if (absoluteStart < 0) {
			return -1;
		}
		EditorState state = state(editor);
		synchronized (state) {
			refreshCache(state, source);
			int index = Arrays.binarySearch(state.lineStarts, Math.min(absoluteStart, source.length()));
			return index >= 0 ? index + 1 : -index - 1;
		}
	}

	private static void refreshCache(EditorState state, String source) {
		if (source.equals(state.cachedSource)) {
			return;
		}
		state.cachedSource = source;
		state.cachedTokens = state.plainMode ? List.of() : state.functionMode ? tokenizeFunction(source)
				: state.jsonMode ? tokenizeJson(source) : tokenize(source);
		state.lineStarts = findLineStarts(source);
	}

	private static int[] findLineStarts(String source) {
		int lineCount = 1;
		for (int index = 0; index < source.length(); index++) {
			if (source.charAt(index) == '\n') lineCount++;
		}
		int[] starts = new int[lineCount];
		int line = 1;
		for (int index = 0; index < source.length(); index++) {
			if (source.charAt(index) == '\n') starts[line++] = index + 1;
		}
		return starts;
	}

	public static Component highlightSnippet(String text, Theme theme) {
		return highlightSnippet(text, theme, Map.of());
	}

	public static Component highlightSnippet(String text, Theme theme, Map<ColorRole, Integer> syntaxColors) {
		return highlightSegment(text, text, 0, tokenize(text), theme, syntaxColors, List.of());
	}

	public static Component highlightSnippet(String source, String visibleText, int absoluteStart, Theme theme) {
		return highlightSegment(source, visibleText, absoluteStart, tokenize(source), theme, Map.of(), List.of());
	}

	private static Component highlightSegment(String source, String visibleText, int absoluteStart,
			List<TokenSpan> tokens, Theme theme, Map<ColorRole, Integer> syntaxColors,
			List<ErrorRange> errorRanges) {
		MutableComponent result = Component.empty();
		int segmentEnd = absoluteStart + visibleText.length();
		int cursor = absoluteStart;
		for (TokenSpan token : tokens) {
			if (token.end() <= absoluteStart) {
				continue;
			}
			if (token.start() >= segmentEnd) {
				break;
			}
			int tokenStart = Math.max(token.start(), absoluteStart);
			int tokenEnd = Math.min(token.end(), segmentEnd);
			if (tokenStart > cursor) {
				appendRange(result, source, cursor, tokenStart,
						color(theme, syntaxColors, ColorRole.NORMAL), color(theme, syntaxColors, ColorRole.ERROR), errorRanges);
			}
			appendRange(result, source, tokenStart, tokenEnd,
					color(theme, syntaxColors, role(token.type())), color(theme, syntaxColors, ColorRole.ERROR), errorRanges);
			cursor = tokenEnd;
		}
		if (cursor < segmentEnd) {
			appendRange(result, source, cursor, segmentEnd,
					color(theme, syntaxColors, ColorRole.NORMAL), color(theme, syntaxColors, ColorRole.ERROR), errorRanges);
		}
		return result;
	}

	private static int color(Theme theme, Map<ColorRole, Integer> syntaxColors, ColorRole role) {
		return syntaxColors.getOrDefault(role, theme.defaultColor(role));
	}

	private static ColorRole role(TokenType type) {
		return switch (type) {
			case KEYWORD -> ColorRole.KEYWORD;
			case STRING -> ColorRole.STRING;
			case NUMBER -> ColorRole.NUMBER;
			case FUNCTION, BUILTIN -> ColorRole.FUNCTION;
			case COMMENT -> ColorRole.COMMENT;
			case LITERAL, LABEL -> ColorRole.LITERAL;
			case OPERATOR -> ColorRole.OPERATOR;
			case MEMBER -> ColorRole.MEMBER;
			case PARAMETER -> ColorRole.PARAMETER;
			case IDENTIFIER, PUNCTUATION -> ColorRole.NORMAL;
		};
	}

	private static void appendRange(MutableComponent result, String source, int start, int end,
			int normal, int error, List<ErrorRange> ranges) {
		int cursor = start;
		for (ErrorRange range : ranges) {
			if (range.end() <= cursor) continue;
			if (range.start() >= end) break;
			int errorStart = Math.max(cursor, range.start());
			int errorEnd = Math.min(end, range.end());
			if (errorStart > cursor) append(result, source.substring(cursor, errorStart), normal);
			if (errorEnd > errorStart) append(result, source.substring(errorStart, errorEnd), range.warning() ? 0xFFE6AD45 : error);
			cursor = Math.max(cursor, errorEnd);
			if (cursor >= end) break;
		}
		if (cursor < end) append(result, source.substring(cursor, end), normal);
	}

	private static void append(MutableComponent result, String text, int color) {
		if (!text.isEmpty()) {
			result.append(Component.literal(text).withStyle(style -> style.withColor(color)));
		}
	}

	private static List<TokenSpan> tokenize(String source) {
		List<TokenSpan> tokens = new ArrayList<>();
		int index = 0;
		while (index < source.length()) {
			char character = source.charAt(index);
			if (Character.isWhitespace(character)) {
				index++;
				continue;
			}

			if (startsWith(source, index, "--")) {
				int level = longBracketLevel(source, index + 2);
				int end = level >= 0 ? longBracketEnd(source, index + 2, level)
						: lineEnd(source, index + 2);
				tokens.add(new TokenSpan(index, end, TokenType.COMMENT));
				index = end;
				continue;
			}

			if (character == '\'' || character == '"') {
				int end = quotedStringEnd(source, index, character);
				tokens.add(new TokenSpan(index, end, TokenType.STRING));
				index = end;
				continue;
			}

			int longStringLevel = longBracketLevel(source, index);
			if (longStringLevel >= 0) {
				int end = longBracketEnd(source, index, longStringLevel);
				tokens.add(new TokenSpan(index, end, TokenType.STRING));
				index = end;
				continue;
			}

			if (Character.isDigit(character)
					|| (character == '.' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1)))) {
				Matcher matcher = NUMBER_PATTERN.matcher(source);
				matcher.region(index, source.length());
				if (matcher.lookingAt()) {
					int end = matcher.end();
					if (end > index && source.charAt(end - 1) == '.' && end < source.length() && source.charAt(end) == '.') {
						end--;
					}
					tokens.add(new TokenSpan(index, end, TokenType.NUMBER));
					index = end;
					continue;
				}
			}

			if (isIdentifierStart(character)) {
				int end = index + 1;
				while (end < source.length() && isIdentifierPart(source.charAt(end))) {
					end++;
				}
				String identifier = source.substring(index, end);
				TokenType type = KEYWORDS.contains(identifier) ? TokenType.KEYWORD
						: LITERALS.contains(identifier) ? TokenType.LITERAL
						: BUILTINS.contains(identifier) || LuaApiDocumentation.isApiRoot(identifier)
								? TokenType.BUILTIN : TokenType.IDENTIFIER;
				tokens.add(new TokenSpan(index, end, type));
				index = end;
				continue;
			}

			String operator = matchingOperator(source, index);
			if (operator != null) {
				tokens.add(new TokenSpan(index, index + operator.length(), TokenType.OPERATOR));
				index += operator.length();
				continue;
			}

			tokens.add(new TokenSpan(index, index + 1, TokenType.PUNCTUATION));
			index++;
		}
		classifyIdentifiers(tokens, source);
		classifyParameters(tokens, source);
		return List.copyOf(tokens);
	}

	private static List<TokenSpan> tokenizeJson(String source) {
		List<TokenSpan> tokens = new ArrayList<>();
		int index = 0;
		while (index < source.length()) {
			char c = source.charAt(index);
			if (Character.isWhitespace(c)) { index++; continue; }
			if (c == '"') {
				int end = index + 1;
				boolean escaped = false;
				while (end < source.length()) {
					char next = source.charAt(end++);
					if (next == '"' && !escaped) break;
					if (next == '\\' && !escaped) escaped = true;
					else escaped = false;
				}
				int lookahead = end;
				while (lookahead < source.length() && Character.isWhitespace(source.charAt(lookahead))) lookahead++;
				tokens.add(new TokenSpan(index, end,
						lookahead < source.length() && source.charAt(lookahead) == ':' ? TokenType.MEMBER : TokenType.STRING));
				index = end;
				continue;
			}
			if (c == '-' || Character.isDigit(c)) {
				int end = index + 1;
				while (end < source.length() && "0123456789.eE+-".indexOf(source.charAt(end)) >= 0) end++;
				tokens.add(new TokenSpan(index, end, TokenType.NUMBER));
				index = end;
				continue;
			}
			if (Character.isLetter(c)) {
				int end = index + 1;
				while (end < source.length() && Character.isLetter(source.charAt(end))) end++;
				tokens.add(new TokenSpan(index, end, TokenType.LITERAL));
				index = end;
				continue;
			}
			tokens.add(new TokenSpan(index, index + 1,
					c == ':' || c == ',' || c == '{' || c == '}' || c == '[' || c == ']'
							? TokenType.OPERATOR : TokenType.PUNCTUATION));
			index++;
		}
		return List.copyOf(tokens);
	}

	private static List<TokenSpan> tokenizeFunction(String source) {
		List<TokenSpan> tokens = new ArrayList<>();
		int lineStart = 0;
		while (lineStart < source.length()) {
			int end = source.indexOf('\n', lineStart);
			if (end < 0) end = source.length();
			int index = lineStart;
			while (index < end && Character.isWhitespace(source.charAt(index))) index++;
			if (index < end && source.charAt(index) == '#') {
				tokens.add(new TokenSpan(index, end, TokenType.COMMENT));
			} else if (index < end) {
				if (source.charAt(index) == '$') {
					tokens.add(new TokenSpan(index, index + 1, TokenType.OPERATOR));
					index++;
				}
				int commandEnd = index;
				while (commandEnd < end && !Character.isWhitespace(source.charAt(commandEnd))) commandEnd++;
				if (commandEnd > index) tokens.add(new TokenSpan(index, commandEnd, TokenType.KEYWORD));
				index = commandEnd;
				while (index < end) {
					char c = source.charAt(index);
					if (Character.isWhitespace(c)) { index++; continue; }
					if (c == '"' || c == '\'') {
						int stringEnd = Math.min(end, quotedStringEnd(source, index, c));
						tokens.add(new TokenSpan(index, stringEnd, TokenType.STRING));
						index = stringEnd; continue;
					}
					if (c == '$' && index + 1 < end && source.charAt(index + 1) == '(') {
						int close = source.indexOf(')', index + 2);
						int macroEnd = close < 0 || close >= end ? end : close + 1;
						tokens.add(new TokenSpan(index, macroEnd, TokenType.LABEL));
						index = macroEnd; continue;
					}
					if (c == '@' && index + 1 < end && "paresn".indexOf(source.charAt(index + 1)) >= 0) {
						tokens.add(new TokenSpan(index, index + 2, TokenType.FUNCTION));
						index += 2; continue;
					}
					if (c == '#' && index + 1 < end && isResourcePart(source.charAt(index + 1))) {
						int tagEnd = index + 1;
						while (tagEnd < end && isResourcePart(source.charAt(tagEnd))) tagEnd++;
						tokens.add(new TokenSpan(index, tagEnd, TokenType.LITERAL));
						index = tagEnd; continue;
					}
					if (c == '~' || c == '^' || Character.isDigit(c)
							|| c == '-' && index + 1 < end && Character.isDigit(source.charAt(index + 1))) {
						int numberEnd = index + 1;
						while (numberEnd < end && (Character.isDigit(source.charAt(numberEnd))
								|| source.charAt(numberEnd) == '.' || source.charAt(numberEnd) == '-')) numberEnd++;
						tokens.add(new TokenSpan(index, numberEnd, TokenType.NUMBER));
						index = numberEnd; continue;
					}
					if (isResourcePart(c)) {
						int wordEnd = index + 1;
						while (wordEnd < end && isResourcePart(source.charAt(wordEnd))) wordEnd++;
						String word = source.substring(index, wordEnd);
						TokenType type = word.indexOf(':') >= 0 ? TokenType.MEMBER
								: word.equals("true") || word.equals("false") ? TokenType.LITERAL
								: FUNCTION_SUBCOMMANDS.contains(word) ? TokenType.KEYWORD
								: TokenType.IDENTIFIER;
						tokens.add(new TokenSpan(index, wordEnd, type));
						index = wordEnd; continue;
					}
					tokens.add(new TokenSpan(index, index + 1, TokenType.OPERATOR));
					index++;
				}
			}
			lineStart = end + 1;
		}
		return List.copyOf(tokens);
	}

	private static boolean isResourcePart(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '/' || c == '.' || c == '-';
	}

	static void verifyFunctionHighlighting() {
		List<TokenSpan> tokens = tokenizeFunction("# 注释\nexecute as @p run give @s minecraft:diamond #minecraft:logs\n$say $(name)");
		if (tokens.stream().noneMatch(token -> token.type() == TokenType.COMMENT)
				|| tokens.stream().noneMatch(token -> token.type() == TokenType.FUNCTION)
				|| tokens.stream().noneMatch(token -> token.type() == TokenType.MEMBER)
				|| tokens.stream().noneMatch(token -> token.type() == TokenType.LITERAL)
				|| tokens.stream().noneMatch(token -> token.type() == TokenType.LABEL))
			throw new IllegalStateException("mcfunction 高亮分类缺失");
	}

	private static void classifyIdentifiers(List<TokenSpan> tokens, String source) {
		for (int index = 0; index < tokens.size(); index++) {
			TokenSpan token = tokens.get(index);
			if (token.type() != TokenType.IDENTIFIER) {
				continue;
			}
			String previous = tokenText(tokens, source, index - 1);
			String next = tokenText(tokens, source, index + 1);
			TokenType type = token.type();
			if ((previous.equals("::") && next.equals("::"))) {
				type = TokenType.LABEL;
			} else if (previous.equals(".") || previous.equals(":")) {
				type = TokenType.MEMBER;
			} else if (next.equals("(")) {
				type = TokenType.FUNCTION;
			} else if (next.equals("=") && insideTable(tokens, source, index)) {
				type = TokenType.MEMBER;
			}
			if (type != token.type()) {
				tokens.set(index, new TokenSpan(token.start(), token.end(), type));
			}
		}
	}

	private static void classifyParameters(List<TokenSpan> tokens, String source) {
		boolean waitingForParameters = false;
		int parameterDepth = 0;
		for (int index = 0; index < tokens.size(); index++) {
			TokenSpan token = tokens.get(index);
			String text = source.substring(token.start(), token.end());
			if (token.type() == TokenType.KEYWORD && text.equals("function")) {
				waitingForParameters = true;
				continue;
			}
			if (waitingForParameters) {
				if (text.equals("(")) {
					waitingForParameters = false;
					parameterDepth = 1;
				}
				continue;
			}
			if (parameterDepth > 0) {
				if (text.equals("(")) {
					parameterDepth++;
				} else if (text.equals(")")) {
					parameterDepth--;
				} else if (parameterDepth == 1 && token.type() == TokenType.IDENTIFIER) {
					tokens.set(index, new TokenSpan(token.start(), token.end(), TokenType.PARAMETER));
				}
			}
		}
	}

	private static boolean insideTable(List<TokenSpan> tokens, String source, int tokenIndex) {
		int depth = 0;
		for (int index = tokenIndex - 1; index >= 0; index--) {
			String token = tokenText(tokens, source, index);
			if (token.equals("}")) depth++;
			if (token.equals("{")) {
				if (depth == 0) return true;
				depth--;
			}
			if (depth == 0 && (token.equals(";") || token.equals("end"))) return false;
		}
		return false;
	}

	private static String tokenText(List<TokenSpan> tokens, String source, int index) {
		if (index < 0 || index >= tokens.size()) {
			return "";
		}
		TokenSpan token = tokens.get(index);
		return source.substring(token.start(), token.end());
	}

	private static int lineEnd(String source, int start) {
		int newline = source.indexOf('\n', start);
		return newline < 0 ? source.length() : newline;
	}

	private static int quotedStringEnd(String source, int start, char quote) {
		int index = start + 1;
		while (index < source.length()) {
			char character = source.charAt(index);
			if (character == '\\' && index + 1 < source.length()) {
				index += 2;
				continue;
			}
			index++;
			if (character == quote || character == '\n') {
				break;
			}
		}
		return index;
	}

	private static int longBracketLevel(String source, int start) {
		if (start < 0 || start >= source.length() || source.charAt(start) != '[') {
			return -1;
		}
		int index = start + 1;
		while (index < source.length() && source.charAt(index) == '=') {
			index++;
		}
		return index < source.length() && source.charAt(index) == '[' ? index - start - 1 : -1;
	}

	private static int longBracketEnd(String source, int start, int level) {
		String closing = "]" + "=".repeat(level) + "]";
		int contentStart = start + level + 2;
		int closingStart = source.indexOf(closing, contentStart);
		return closingStart < 0 ? source.length() : closingStart + closing.length();
	}

	private static String matchingOperator(String source, int index) {
		for (String operator : OPERATORS) {
			if (startsWith(source, index, operator)) {
				return operator;
			}
		}
		return null;
	}

	private static boolean startsWith(String source, int index, String value) {
		return index >= 0 && index + value.length() <= source.length() && source.startsWith(value, index);
	}

	private static boolean isIdentifierStart(char character) {
		return character == '_' || Character.isLetter(character);
	}

	private static boolean isIdentifierPart(char character) {
		return character == '_' || Character.isLetterOrDigit(character);
	}

	public enum Theme {
		DARK(ChatFormatting.LIGHT_PURPLE, ChatFormatting.GREEN, ChatFormatting.GOLD,
				ChatFormatting.AQUA, ChatFormatting.DARK_GRAY, ChatFormatting.WHITE, ChatFormatting.RED,
				ChatFormatting.BLUE, ChatFormatting.YELLOW, ChatFormatting.DARK_AQUA, ChatFormatting.YELLOW,
				0xE00B0E14),
		LIGHT(ChatFormatting.DARK_PURPLE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
				ChatFormatting.BLUE, ChatFormatting.DARK_GRAY, ChatFormatting.BLACK, ChatFormatting.RED,
				ChatFormatting.DARK_PURPLE, ChatFormatting.DARK_GRAY, ChatFormatting.DARK_BLUE, ChatFormatting.GOLD,
				0xE0E8E8E8),
		HIGH_CONTRAST(ChatFormatting.LIGHT_PURPLE, ChatFormatting.GREEN, ChatFormatting.YELLOW,
				ChatFormatting.AQUA, ChatFormatting.WHITE, ChatFormatting.WHITE, ChatFormatting.RED,
				ChatFormatting.YELLOW, ChatFormatting.YELLOW, ChatFormatting.AQUA, ChatFormatting.GOLD,
				0xF0000000);

		private final ChatFormatting keyword;
		private final ChatFormatting string;
		private final ChatFormatting number;
		private final ChatFormatting function;
		private final ChatFormatting comment;
		private final ChatFormatting normal;
		private final ChatFormatting error;
		private final ChatFormatting literal;
		private final ChatFormatting operator;
		private final ChatFormatting member;
		private final ChatFormatting parameter;
		private final int background;

		Theme(ChatFormatting keyword, ChatFormatting string, ChatFormatting number, ChatFormatting function,
				ChatFormatting comment, ChatFormatting normal, ChatFormatting error, ChatFormatting literal,
				ChatFormatting operator, ChatFormatting member, ChatFormatting parameter, int background) {
			this.keyword = keyword;
			this.string = string;
			this.number = number;
			this.function = function;
			this.comment = comment;
			this.normal = normal;
			this.error = error;
			this.literal = literal;
			this.operator = operator;
			this.member = member;
			this.parameter = parameter;
			this.background = background;
		}

		public int defaultColor(ColorRole role) {
			ChatFormatting formatting = switch (role) {
				case NORMAL -> normal;
				case KEYWORD -> keyword;
				case STRING -> string;
				case NUMBER -> number;
				case FUNCTION -> function;
				case COMMENT -> comment;
				case LITERAL -> literal;
				case OPERATOR -> operator;
				case MEMBER -> member;
				case PARAMETER -> parameter;
				case ERROR -> error;
			};
			return formatting.getColor();
		}

		public int background() {
			return background;
		}
	}

	public enum ColorRole {
		NORMAL("普通文本", "variable"), KEYWORD("关键字", "local / function"),
		STRING("字符串", "\"hello\""), NUMBER("数字", "123.45"),
		FUNCTION("函数与内置 API", "MC.Player.list"), COMMENT("注释", "-- 注释"),
		LITERAL("常量与标签", "true / nil"), OPERATOR("运算符", "+ = =="),
		MEMBER("成员与字段", "addExperience"), PARAMETER("函数参数", "playerUuid"),
		ERROR("语法错误", "错误字段");
		public final String label;
		public final String sample;
		ColorRole(String label, String sample) { this.label = label; this.sample = sample; }
	}

	private enum TokenType {
		KEYWORD, STRING, NUMBER, FUNCTION, BUILTIN, COMMENT, LITERAL, OPERATOR, MEMBER, PARAMETER,
		LABEL, IDENTIFIER, PUNCTUATION
	}

	private record TokenSpan(int start, int end, TokenType type) {
	}

	public record ErrorRange(int start, int end, boolean warning) {
		public ErrorRange(int start, int end) { this(start, end, false); }
	}

	private static final class EditorState {
		private boolean jsonMode;
		private boolean plainMode;
		private boolean functionMode;
		private List<ErrorRange> errorRanges = List.of();
		private Theme theme = Theme.DARK;
		private float scale = 1.0F;
		private volatile float lineHeight = 1.0F;
		private volatile float cellWidth = 1.0F;
		private volatile LuaPersonalizationSettings.CursorShape cursorShape = LuaPersonalizationSettings.CursorShape.BAR;
		private volatile int cursorWidth = 1;
		private volatile int cursorColor = 0xFFE6F3FF;
		private volatile boolean cursorBlink = true;
		private Map<ColorRole, Integer> syntaxColors = Map.of();
		private String cachedSource = "";
		private List<TokenSpan> cachedTokens = List.of();
		private int[] lineStarts = { 0 };
	}
}
