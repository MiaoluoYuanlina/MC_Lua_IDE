package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A small, forgiving Lua 5.2 tokenizer used by the editor tools (bracket matching, symbol index, snippets).
 * It never throws: unterminated strings and comments simply run to the end of the text, so half-typed code still
 * produces sensible tokens while the user is editing.
 */
final class LuaLexer {
	enum Kind { NAME, KEYWORD, NUMBER, STRING, COMMENT, OP }

	record Token(Kind kind, int start, int end, String text) {
		boolean is(String value) { return text.equals(value); }
		boolean isKeyword(String value) { return kind == Kind.KEYWORD && text.equals(value); }
	}

	static final Set<String> KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
			"if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while");

	private LuaLexer() { }

	static List<Token> tokenize(String source) {
		List<Token> tokens = new ArrayList<>();
		int length = source.length(), index = 0;
		while (index < length) {
			char c = source.charAt(index);
			if (Character.isWhitespace(c)) { index++; continue; }
			int start = index;
			if (c == '-' && index + 1 < length && source.charAt(index + 1) == '-') {
				int level = longBracketLevel(source, index + 2);
				index = level >= 0 ? skipLongBracket(source, index + 2, level) : skipLine(source, index);
				tokens.add(new Token(Kind.COMMENT, start, index, source.substring(start, index)));
			} else if (c == '"' || c == '\'') {
				index = skipQuoted(source, index);
				tokens.add(new Token(Kind.STRING, start, index, source.substring(start, index)));
			} else if (c == '[' && longBracketLevel(source, index) >= 0) {
				index = skipLongBracket(source, index, longBracketLevel(source, index));
				tokens.add(new Token(Kind.STRING, start, index, source.substring(start, index)));
			} else if (Character.isDigit(c) || c == '.' && index + 1 < length && Character.isDigit(source.charAt(index + 1))) {
				index = skipNumber(source, index);
				tokens.add(new Token(Kind.NUMBER, start, index, source.substring(start, index)));
			} else if (isNameStart(c)) {
				while (index < length && isNamePart(source.charAt(index))) index++;
				String text = source.substring(start, index);
				tokens.add(new Token(KEYWORDS.contains(text) ? Kind.KEYWORD : Kind.NAME, start, index, text));
			} else {
				index += operatorLength(source, index);
				tokens.add(new Token(Kind.OP, start, index, source.substring(start, index)));
			}
		}
		return tokens;
	}

	static boolean isNameStart(char c) { return c == '_' || Character.isLetter(c); }
	static boolean isNamePart(char c) { return c == '_' || Character.isLetterOrDigit(c); }

	/** The number of '=' in a long bracket opener at {@code index} ("[==["), or -1 when there is none. */
	static int longBracketLevel(String source, int index) {
		if (index >= source.length() || source.charAt(index) != '[') return -1;
		int level = 0, cursor = index + 1;
		while (cursor < source.length() && source.charAt(cursor) == '=') { level++; cursor++; }
		return cursor < source.length() && source.charAt(cursor) == '[' ? level : -1;
	}

	private static int skipLongBracket(String source, int index, int level) {
		String closer = "]" + "=".repeat(level) + "]";
		int close = source.indexOf(closer, index + level + 2);
		return close < 0 ? source.length() : close + closer.length();
	}

	private static int skipLine(String source, int index) {
		int end = source.indexOf('\n', index);
		return end < 0 ? source.length() : end;
	}

	private static int skipQuoted(String source, int index) {
		char quote = source.charAt(index);
		int cursor = index + 1;
		while (cursor < source.length()) {
			char c = source.charAt(cursor);
			if (c == '\\') { cursor += 2; continue; }
			if (c == quote) return cursor + 1;
			if (c == '\n') return cursor; // an unterminated string ends at the line break
			cursor++;
		}
		return source.length();
	}

	private static int skipNumber(String source, int index) {
		int cursor = index;
		if (source.startsWith("0x", cursor) || source.startsWith("0X", cursor)) {
			cursor += 2;
			while (cursor < source.length() && (Character.digit(source.charAt(cursor), 16) >= 0 || source.charAt(cursor) == '.')) cursor++;
			return cursor;
		}
		while (cursor < source.length()) {
			char c = source.charAt(cursor);
			if (Character.isDigit(c) || c == '.') cursor++;
			else if ((c == 'e' || c == 'E') && cursor + 1 < source.length()) {
				cursor++;
				if (source.charAt(cursor) == '+' || source.charAt(cursor) == '-') cursor++;
			} else break;
		}
		return cursor;
	}

	private static int operatorLength(String source, int index) {
		if (source.startsWith("...", index)) return 3;
		if (source.startsWith("..", index) || source.startsWith("==", index) || source.startsWith("~=", index)
				|| source.startsWith("<=", index) || source.startsWith(">=", index) || source.startsWith("::", index)) return 2;
		return 1;
	}
}
