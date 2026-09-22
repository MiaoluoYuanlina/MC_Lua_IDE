package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayDeque;
import java.util.Deque;

/** Lightweight Lua-aware cursor parser used for argument completion. */
final class LuaCursorContext {
	private LuaCursorContext() {
	}

	static Argument argumentAt(String source, int requestedCursor) {
		CursorState cursorState = scanToCursor(source, requestedCursor);
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

	private static CursorState scanToCursor(String source, int requestedCursor) {
		int cursor = Math.max(0, Math.min(source.length(), requestedCursor));
		Deque<Frame> delimiters = new ArrayDeque<>();
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
					if (character == '-' && index + 1 < cursor && source.charAt(index + 1) == '-') {
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
						String symbol = character == '(' ? symbolBefore(source, index) : "";
						delimiters.push(new Frame(character, symbol, 0, index + 1));
					} else if (character == ')' || character == ']' || character == '}') {
						popMatching(delimiters, matchingOpen(character));
					} else if (character == ',' && !delimiters.isEmpty() && delimiters.peek().delimiter == '(') {
						Frame call = delimiters.peek();
						call.argumentIndex++;
						call.argumentStart = index + 1;
						call.argumentStarts.add(index + 1);
					}
				}
			}
		}
		return new CursorState(cursor, delimiters);
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

	record ComponentArgument(String symbol, int index, int replacementStart, int replacementEnd,
			LuaInsertCatalog.Kind targetKind, String targetId) {
	}

	private record CursorState(int cursor, Deque<Frame> delimiters) {
	}

	private record ArgumentRange(int start, int end) {
	}

	private static final class Frame {
		private final char delimiter;
		private final String symbol;
		private final java.util.List<Integer> argumentStarts = new java.util.ArrayList<>();
		private int argumentIndex;
		private int argumentStart;

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
