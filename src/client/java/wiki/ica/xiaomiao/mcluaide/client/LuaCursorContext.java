package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayDeque;
import java.util.Deque;

/** Lightweight Lua-aware cursor parser used for argument completion. */
final class LuaCursorContext {
	private LuaCursorContext() {
	}

	/** Only the innermost call owns completion; an outer ID argument must not leak into a table/callback. */
	static Parameter parameterAt(String source, int requestedCursor) {
		CursorState state = scanToCursor(source, requestedCursor);
		if (state.lexicalState() == LexicalState.LINE_COMMENT || state.lexicalState() == LexicalState.LONG_COMMENT
				|| state.lexicalState() == LexicalState.LONG_STRING) return null;
		Frame frame = state.delimiters().peek();
		if (frame == null || frame.delimiter != '(' || frame.symbol.isEmpty() || frame.functionNesting > 0) return null;
		LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(frame.symbol);
		if (entry == null || frame.argumentIndex >= entry.parameters().size()) return null;
		int cursor = state.cursor();
		int start = cursor;
		int end = cursor;
		boolean quoted = state.lexicalState() == LexicalState.STRING;
		if (quoted) {
			int trimmed = frame.argumentStart;
			while (trimmed < cursor && Character.isWhitespace(source.charAt(trimmed))) trimmed++;
			if (trimmed >= cursor || source.charAt(trimmed) != '\'' && source.charAt(trimmed) != '"') return null;
			start = trimmed + 1;
			end = findQuoteEnd(source, start, source.charAt(trimmed));
			if (end < 0) end = cursor;
		} else {
			boolean resource = LuaApiDocumentation.insertionKind(frame.symbol, frame.argumentIndex) != null;
			while (start > frame.argumentStart && completionCharacter(source.charAt(start - 1), resource)) start--;
			while (end < source.length() && completionCharacter(source.charAt(end), resource)) end++;
			// Completion immediately after a closed string is not inside that string.
			if (cursor > 0 && (source.charAt(cursor - 1) == '\'' || source.charAt(cursor - 1) == '"')) return null;
		}
		String parameter = entry.parameters().get(frame.argumentIndex);
		return new Parameter(frame.symbol, frame.argumentIndex, parameter, start, end,
				source.substring(start, cursor), quoted);
	}

	private static boolean completionCharacter(char character, boolean resource) {
		return resource ? isResourceCharacter(character)
				: Character.isLetterOrDigit(character) || character == '_' || character == '.';
	}

	static Argument argumentAt(String source, int requestedCursor) {
		CursorState cursorState = scanToCursor(source, requestedCursor);
		for (Frame frame : cursorState.delimiters()) {
			if (frame.delimiter == '{' && frame.symbol.equals("enchantment_keys")) {
				Argument key = enchantmentKeyAt(source, cursorState.cursor(), frame);
				if (key != null) return key;
			}
		}
		for (Frame frame : cursorState.delimiters()) {
			if (frame.delimiter != '(' || frame.symbol.isEmpty()) continue;
			LuaInsertCatalog.Kind kind = LuaApiDocumentation.insertionKind(frame.symbol, frame.argumentIndex);
			if (kind != null) return buildArgument(source, cursorState.cursor(), frame, kind);
		}
		return null;
	}

	static ComponentArgument itemComponentsArgumentAt(String source, int requestedCursor) {
		CursorState cursorState = scanToCursor(source, requestedCursor);
		for (Frame frame : cursorState.delimiters()) {
			if (frame.delimiter != '(' || frame.symbol.isEmpty()) continue;
			if (!LuaApiDocumentation.isItemComponentsArgument(frame.symbol, frame.argumentIndex)) continue;
			ArgumentRange range = findArgumentRange(source, cursorState.cursor(), frame.argumentStart);
			LuaApiDocumentation.ComponentTarget target = LuaApiDocumentation.componentTarget(
					frame.symbol, frame.argumentIndex);
			String targetId = target == null ? "" : resourceLiteral(argumentText(source, frame,
					target.argumentIndex(), cursorState.cursor()));
			return new ComponentArgument(frame.symbol, frame.argumentIndex, range.start(), range.end(),
					target == null ? null : target.kind(), targetId);
		}
		return null;
	}

	static TextJsonArgument textJsonArgumentAt(String source, int requestedCursor) {
		CursorState cursorState = scanToCursor(source, requestedCursor);
		if (cursorState.lexicalState() == LexicalState.LINE_COMMENT
				|| cursorState.lexicalState() == LexicalState.LONG_COMMENT) return null;
		for (Frame frame : cursorState.delimiters()) {
			if (frame.delimiter != '(' || frame.symbol.isEmpty()) continue;
			if (!LuaApiDocumentation.isTextJsonArgument(frame.symbol, frame.argumentIndex)) continue;
			ArgumentRange range = findArgumentRange(source, cursorState.cursor(), frame.argumentStart);
			return new TextJsonArgument(frame.symbol, frame.argumentIndex, range.start(), range.end());
		}
		return null;
	}

	private static CursorState scanToCursor(String source, int requestedCursor) {
		int cursor = Math.max(0, Math.min(source.length(), requestedCursor));
		Deque<Frame> delimiters = new ArrayDeque<>();
		Deque<LexicalBlock> blocks = new ArrayDeque<>();
		int pendingLoopDo = 0;
		LexicalState state = LexicalState.NORMAL;
		char quote = 0;
		boolean escaped = false;

		for (int index = 0; index < cursor; index++) {
			char character = source.charAt(index);
			switch (state) {
				case LINE_COMMENT -> {
					if (character == '\n') state = LexicalState.NORMAL;
				}
				case LONG_COMMENT, LONG_STRING -> {
					if (character == ']' && index + 1 < cursor && source.charAt(index + 1) == ']') {
						state = LexicalState.NORMAL;
						index++;
					}
				}
				case STRING -> {
					if (escaped) {
						escaped = false;
					} else if (character == '\\') {
						escaped = true;
					} else if (character == quote) {
						state = LexicalState.NORMAL;
					}
				}
				case NORMAL -> {
					if (Character.isLetter(character) || character == '_') {
						int wordEnd = index + 1;
						while (wordEnd < cursor && (Character.isLetterOrDigit(source.charAt(wordEnd)) || source.charAt(wordEnd) == '_')) wordEnd++;
						String word = source.substring(index, wordEnd);
						switch (word) {
							case "function" -> {
								Frame owner = delimiters.peek();
								if (owner != null && owner.delimiter == '(') owner.functionNesting++;
								else owner = null;
								blocks.push(new LexicalBlock(owner));
							}
							case "if", "repeat" -> blocks.push(new LexicalBlock(null));
							case "for", "while" -> { blocks.push(new LexicalBlock(null)); pendingLoopDo++; }
							case "do" -> { if (pendingLoopDo > 0) pendingLoopDo--; else blocks.push(new LexicalBlock(null)); }
							case "end", "until" -> {
								if (!blocks.isEmpty()) {
									Frame owner = blocks.pop().functionOwner();
									if (owner != null) owner.functionNesting = Math.max(0, owner.functionNesting - 1);
								}
							}
						}
						index = wordEnd - 1;
					} else if (character == '-' && index + 1 < cursor && source.charAt(index + 1) == '-') {
						if (index + 3 < cursor && source.charAt(index + 2) == '[' && source.charAt(index + 3) == '[') {
							state = LexicalState.LONG_COMMENT;
							index += 3;
						} else {
							state = LexicalState.LINE_COMMENT;
							index++;
						}
					} else if (character == '[' && index + 1 < cursor && source.charAt(index + 1) == '[') {
						state = LexicalState.LONG_STRING;
						index++;
					} else if (character == '\'' || character == '"') {
						state = LexicalState.STRING;
						quote = character;
						escaped = false;
					} else if (character == '(' || character == '[' || character == '{') {
						String symbol = character == '(' ? symbolBefore(source, index)
								: character == '{' ? componentMapBefore(source, index) : "";
						delimiters.push(new Frame(character, symbol, 0, index + 1));
					} else if (character == ')' || character == ']' || character == '}') {
						popMatching(delimiters, matchingOpen(character));
					} else if (character == ',' && !delimiters.isEmpty() && delimiters.peek().delimiter == '('
							&& delimiters.peek().functionNesting == 0) {
						Frame call = delimiters.peek();
						call.argumentIndex++;
						call.argumentStart = index + 1;
						call.argumentStarts.add(index + 1);
					}
				}
			}
		}
		return new CursorState(cursor, delimiters, state);
	}

	private static String argumentText(String source, Frame frame, int argumentIndex, int cursor) {
		if (argumentIndex < 0 || argumentIndex >= frame.argumentStarts.size()) return "";
		int start = frame.argumentStarts.get(argumentIndex);
		int end = argumentIndex + 1 < frame.argumentStarts.size()
				? frame.argumentStarts.get(argumentIndex + 1) - 1 : cursor;
		start = Math.max(0, Math.min(start, source.length()));
		end = Math.max(start, Math.min(end, source.length()));
		return source.substring(start, end).strip();
	}

	private static String resourceLiteral(String argument) {
		if (argument == null || argument.length() < 2) return "";
		char quote = argument.charAt(0);
		if (quote != '\'' && quote != '"') return "";
		int end = findQuoteEnd(argument, 1, quote);
		if (end < 1) return "";
		String value = argument.substring(1, end);
		if (value.isBlank()) return "";
		for (int index = 0; index < value.length(); index++) {
			if (!isResourceCharacter(value.charAt(index))) return "";
		}
		return value;
	}

	private static Argument buildArgument(String source, int cursor, Frame frame, LuaInsertCatalog.Kind kind) {
		int trimmedStart = Math.max(0, Math.min(frame.argumentStart, cursor));
		while (trimmedStart < cursor && Character.isWhitespace(source.charAt(trimmedStart))) trimmedStart++;

		if (trimmedStart < cursor && (source.charAt(trimmedStart) == '\'' || source.charAt(trimmedStart) == '"')) {
			char quote = source.charAt(trimmedStart);
			int contentStart = trimmedStart + 1;
			int contentEnd = findQuoteEnd(source, contentStart, quote);
			if (contentEnd < 0) contentEnd = cursor;
			int queryEnd = Math.max(contentStart, Math.min(cursor, contentEnd));
			return new Argument(frame.symbol, frame.argumentIndex, kind, contentStart, contentEnd,
					source.substring(contentStart, queryEnd));
		}

		int tokenStart = cursor;
		while (tokenStart > trimmedStart && isResourceCharacter(source.charAt(tokenStart - 1))) tokenStart--;
		int tokenEnd = cursor;
		while (tokenEnd < source.length() && isResourceCharacter(source.charAt(tokenEnd))) tokenEnd++;
		String query = source.substring(tokenStart, cursor);
		return new Argument(frame.symbol, frame.argumentIndex, kind, tokenStart, tokenEnd, query);
	}

	private static int findQuoteEnd(String source, int start, char quote) {
		boolean escaped = false;
		for (int index = start; index < source.length(); index++) {
			char character = source.charAt(index);
			if (escaped) {
				escaped = false;
			} else if (character == '\\') {
				escaped = true;
			} else if (character == quote) {
				return index;
			} else if (character == '\n') {
				return -1;
			}
		}
		return -1;
	}

	private static ArgumentRange findArgumentRange(String source, int cursor, int argumentStart) {
		int start = Math.max(0, Math.min(argumentStart, source.length()));
		while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
		if (start >= source.length()) return new ArgumentRange(cursor, cursor);

		Deque<Character> nested = new ArrayDeque<>();
		LexicalState state = LexicalState.NORMAL;
		char quote = 0;
		boolean escaped = false;
		boolean contentSeen = false;
		int end = source.length();
		for (int index = start; index < source.length(); index++) {
			char character = source.charAt(index);
			switch (state) {
				case LINE_COMMENT -> {
					if (character == '\n') {
						if (nested.isEmpty()) {
							end = index;
							index = source.length();
						} else {
							state = LexicalState.NORMAL;
						}
					}
				}
				case LONG_COMMENT, LONG_STRING -> {
					if (character == ']' && index + 1 < source.length() && source.charAt(index + 1) == ']') {
						state = LexicalState.NORMAL;
						index++;
					}
				}
				case STRING -> {
					if (escaped) {
						escaped = false;
					} else if (character == '\\') {
						escaped = true;
					} else if (character == quote) {
						state = LexicalState.NORMAL;
					}
				}
				case NORMAL -> {
					if (nested.isEmpty() && (character == ',' || character == ')')) {
						end = index;
						index = source.length();
					} else if (nested.isEmpty() && character == '\n' && contentSeen) {
						end = index;
						index = source.length();
					} else if (character == '-' && index + 1 < source.length() && source.charAt(index + 1) == '-') {
						contentSeen = true;
						if (index + 3 < source.length() && source.charAt(index + 2) == '['
								&& source.charAt(index + 3) == '[') {
							state = LexicalState.LONG_COMMENT;
							index += 3;
						} else {
							state = LexicalState.LINE_COMMENT;
							index++;
						}
					} else if (character == '[' && index + 1 < source.length() && source.charAt(index + 1) == '[') {
						contentSeen = true;
						state = LexicalState.LONG_STRING;
						index++;
					} else if (character == '\'' || character == '"') {
						contentSeen = true;
						state = LexicalState.STRING;
						quote = character;
						escaped = false;
					} else if (character == '(' || character == '[' || character == '{') {
						contentSeen = true;
						nested.push(character);
					} else if (character == ')' || character == ']' || character == '}') {
						char matching = matchingOpen(character);
						if (!nested.isEmpty() && nested.peek() == matching) nested.pop();
					} else if (!Character.isWhitespace(character)) {
						contentSeen = true;
					}
				}
			}
		}

		while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
		if (!contentSeen || end < start) return new ArgumentRange(cursor, cursor);
		return new ArgumentRange(start, end);
	}

	private static boolean isResourceCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_' || character == '-'
				|| character == '.' || character == ':' || character == '/';
	}

	private static String symbolBefore(String source, int openParenthesis) {
		int end = openParenthesis;
		while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
		int start = end;
		while (start > 0) {
			char character = source.charAt(start - 1);
			if (!Character.isLetterOrDigit(character) && character != '_' && character != '.') break;
			start--;
		}
		return source.substring(start, end);
	}

	private static String componentMapBefore(String source, int openBrace) {
		String prefix = source.substring(Math.max(0, openBrace - 100), openBrace);
		return prefix.matches("(?s).*\\[\\s*['\"]minecraft:(?:enchantments|stored_enchantments)['\"]\\s*\\]\\s*=\\s*")
				|| prefix.matches("(?s).*(?:enchantments|stored_enchantments)\\s*=\\s*")
				? "enchantment_keys" : "";
	}

	private static Argument enchantmentKeyAt(String source, int cursor, Frame frame) {
		int bracket = source.lastIndexOf('[', Math.max(0, cursor - 1));
		if (bracket < frame.argumentStart) return null;
		int quoteAt = bracket + 1;
		while (quoteAt < cursor && Character.isWhitespace(source.charAt(quoteAt))) quoteAt++;
		if (quoteAt >= cursor || (source.charAt(quoteAt) != '\'' && source.charAt(quoteAt) != '"')) return null;
		int start = quoteAt + 1;
		int end = findQuoteEnd(source, start, source.charAt(quoteAt));
		if (end >= 0 && cursor > end) return null;
		if (end < 0) end = cursor;
		return new Argument("附魔组件", 0, LuaInsertCatalog.Kind.ENCHANTMENT,
				start, end, source.substring(start, Math.max(start, Math.min(cursor, end))));
	}

	private static char matchingOpen(char close) {
		return switch (close) {
			case ')' -> '(';
			case ']' -> '[';
			default -> '{';
		};
	}

	private static void popMatching(Deque<Frame> delimiters, char open) {
		while (!delimiters.isEmpty()) {
			if (delimiters.pop().delimiter == open) return;
		}
	}

	record Argument(String symbol, int index, LuaInsertCatalog.Kind kind,
			int replacementStart, int replacementEnd, String query) {
	}

	record Parameter(String symbol, int index, String name, int start, int end, String query, boolean quoted) {
	}

	record ComponentArgument(String symbol, int index, int replacementStart, int replacementEnd,
			LuaInsertCatalog.Kind targetKind, String targetId) {
	}

	record TextJsonArgument(String symbol, int index, int replacementStart, int replacementEnd) {
	}

	private record CursorState(int cursor, Deque<Frame> delimiters, LexicalState lexicalState) {
	}

	private record LexicalBlock(Frame functionOwner) { }

	private record ArgumentRange(int start, int end) {
	}

	private static final class Frame {
		private final char delimiter;
		private final String symbol;
		private final java.util.List<Integer> argumentStarts = new java.util.ArrayList<>();
		private int argumentIndex;
		private int argumentStart;
		private int functionNesting;

		private Frame(char delimiter, String symbol, int argumentIndex, int argumentStart) {
			this.delimiter = delimiter;
			this.symbol = symbol;
			this.argumentIndex = argumentIndex;
			this.argumentStart = argumentStart;
			this.argumentStarts.add(argumentStart);
		}
	}

	private enum LexicalState {
		NORMAL,
		STRING,
		LONG_STRING,
		LINE_COMMENT,
		LONG_COMMENT
	}
}
