package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LuaIndentation {
	static final String INDENT = "    ";
	private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private LuaIndentation() {
	}

	static String indentForNewLine(String source, int cursor) {
		int safeCursor = Math.max(0, Math.min(cursor, source.length()));
		int lineStart = source.lastIndexOf('\n', Math.max(0, safeCursor - 1)) + 1;
		String masked = maskNonCode(source.substring(0, safeCursor));
		String[] lines = masked.split("\n", -1);
		int depth = 0;
		for (int index = 0; index + 1 < lines.length; index++) {
			depth = depthAfterLine(depth, lines[index]);
		}
		String currentLine = lines.length == 0 ? "" : lines[lines.length - 1];
		if (lineStart == safeCursor && currentLine.isEmpty()) {
			return INDENT.repeat(depth);
		}
		return INDENT.repeat(depthAfterLine(depth, currentLine));
	}

	static String desiredIndentForLine(String source, int lineStart) {
		int safeStart = Math.max(0, Math.min(lineStart, source.length()));
		int lineEnd = source.indexOf('\n', safeStart);
		if (lineEnd < 0) lineEnd = source.length();
		String maskedThroughLine = maskNonCode(source.substring(0, lineEnd));
		String before = maskedThroughLine.substring(0, safeStart);
		int depth = 0;
		for (String line : before.split("\n", -1)) {
			depth = depthAfterLine(depth, line);
		}
		String currentMasked = maskedThroughLine.substring(safeStart);
		return INDENT.repeat(Math.max(0, depth + leadingAdjustment(currentMasked)));
	}

	static boolean startsWithClosingToken(String source, int lineStart, int lineEnd) {
		int safeStart = Math.max(0, Math.min(lineStart, source.length()));
		int safeEnd = Math.max(safeStart, Math.min(lineEnd, source.length()));
		String trimmed = maskNonCode(source.substring(0, safeEnd)).substring(safeStart).stripLeading();
		return trimmed.matches("(?:end|else|elseif|until)(?:\\b.*)?")
				|| trimmed.startsWith("}") || trimmed.startsWith(")") || trimmed.startsWith("]");
	}

	static String formatPaste(String clipboard, String source, int insertionStart) {
		String normalized = expandCompactCode(normalizeNewlines(clipboard));
		if (!normalized.contains("\n")) {
			return normalized;
		}
		int safeStart = Math.max(0, Math.min(insertionStart, source.length()));
		int lineStart = source.lastIndexOf('\n', Math.max(0, safeStart - 1)) + 1;
		String prefix = source.substring(lineStart, safeStart);
		String sourceBeforeInsertion = source.substring(0, safeStart);
		String maskedPrefix = maskNonCode(sourceBeforeInsertion);
		maskedPrefix = maskedPrefix.substring(maskedPrefix.lastIndexOf('\n') + 1);
		String maskedInserted = maskNonCode(sourceBeforeInsertion + normalized).substring(sourceBeforeInsertion.length());
		int baseDepth = depthBeforeLine(source, lineStart);
		String[] pastedLines = normalized.split("\n", -1);
		String[] maskedPastedLines = maskedInserted.split("\n", -1);
		List<String> formatted = new ArrayList<>(pastedLines.length);
		int depth = baseDepth;

		for (int index = 0; index < pastedLines.length; index++) {
			String content = pastedLines[index].stripLeading().stripTrailing();
			if (index == 0 && !prefix.isBlank()) {
				formatted.add(maskedPrefix.isBlank() ? pastedLines[index] : content);
				depth = depthAfterLine(depth, maskedPrefix + maskedPastedLines[index]);
				continue;
			}
			if (content.isEmpty()) {
				formatted.add("");
				continue;
			}
			String trimmedOriginal = pastedLines[index].strip();
			boolean entirelyNonCode = maskedPastedLines[index].isBlank() && !pastedLines[index].isBlank();
			boolean delimiterOrComment = trimmedOriginal.startsWith("--")
					|| trimmedOriginal.matches("\\[=*\\[.*") || trimmedOriginal.matches("\\]=*\\]");
			if (entirelyNonCode && !delimiterOrComment) {
				// Whitespace inside Lua long strings is data, so never reformat these lines.
				formatted.add(pastedLines[index]);
				continue;
			}
			String masked = maskedPastedLines[index].stripLeading().stripTrailing();
			int lineDepth = Math.max(0, depth + leadingAdjustment(masked));
			formatted.add(INDENT.repeat(lineDepth) + content);
			depth = depthAfterLine(depth, masked);
		}
		return String.join("\n", formatted);
	}

	static boolean pasteWillBeMultiline(String clipboard) {
		return expandCompactCode(normalizeNewlines(clipboard)).contains("\n");
	}

	private static String normalizeNewlines(String text) {
		return text.replace("\r\n", "\n").replace('\r', '\n');
	}

	private static String expandCompactCode(String source) {
		if (source.contains("\n") || source.isBlank()) {
			return source;
		}
		String masked = maskNonCode(source);
		Set<Integer> breaks = new TreeSet<>();
		for (int index = 0; index < masked.length(); index++) {
			if (masked.charAt(index) == ';') {
				breaks.add(index + 1);
			}
		}
		Matcher matcher = WORD.matcher(masked);
		while (matcher.find()) {
			String word = matcher.group();
			switch (word) {
				case "then", "do", "repeat" -> addBreakAfterContent(breaks, masked, matcher.end());
				case "else" -> {
					addBreakBeforeContent(breaks, masked, matcher.start());
					addBreakAfterContent(breaks, masked, matcher.end());
				}
				case "elseif", "until" -> addBreakBeforeContent(breaks, masked, matcher.start());
				case "end" -> {
					addBreakBeforeContent(breaks, masked, matcher.start());
					int next = nextNonWhitespace(masked, matcher.end());
					if (next < masked.length() && isIdentifierStart(masked.charAt(next))) {
						breaks.add(matcher.end());
					}
				}
				case "function" -> {
					int close = functionParametersEnd(masked, matcher.end());
					if (close >= 0) addBreakAfterContent(breaks, masked, close);
				}
				default -> {
				}
			}
		}
		if (breaks.isEmpty()) {
			return source;
		}
		List<String> segments = new ArrayList<>();
		int start = 0;
		for (int boundary : breaks) {
			if (boundary <= start || boundary > source.length()) continue;
			String segment = source.substring(start, boundary).strip();
			if (!segment.isEmpty()) segments.add(segment);
			start = boundary;
		}
		String tail = source.substring(start).strip();
		if (!tail.isEmpty()) segments.add(tail);
		return segments.size() > 1 ? String.join("\n", segments) : source;
	}

	private static void addBreakBeforeContent(Set<Integer> breaks, String source, int position) {
		if (!source.substring(0, position).isBlank()) breaks.add(position);
	}

	private static void addBreakAfterContent(Set<Integer> breaks, String source, int position) {
		if (!source.substring(position).isBlank()) breaks.add(position);
	}

	private static int nextNonWhitespace(String source, int start) {
		int index = start;
		while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
		return index;
	}

	private static int functionParametersEnd(String masked, int start) {
		int open = masked.indexOf('(', start);
		if (open < 0) return -1;
		int depth = 0;
		for (int index = open; index < masked.length(); index++) {
			if (masked.charAt(index) == '(') depth++;
			if (masked.charAt(index) == ')' && --depth == 0) return index + 1;
		}
		return -1;
	}

	private static boolean isIdentifierStart(char character) {
		return character == '_' || Character.isLetter(character);
	}

	private static int depthBeforeLine(String source, int lineStart) {
		String masked = maskNonCode(source.substring(0, Math.max(0, Math.min(lineStart, source.length()))));
		int depth = 0;
		for (String line : masked.split("\n", -1)) {
			depth = depthAfterLine(depth, line);
		}
		return depth;
	}

	private static int leadingAdjustment(String line) {
		String trimmed = line.stripLeading();
		int adjustment = 0;
		if (trimmed.matches("(?:end|until)(?:\\b.*)?")
				|| trimmed.matches("(?:else|elseif)(?:\\b.*)?")) {
			adjustment--;
		}
		int index = 0;
		while (index < trimmed.length()) {
			char character = trimmed.charAt(index);
			if (character == '}' || character == ')' || character == ']') {
				adjustment--;
				index++;
				while (index < trimmed.length() && Character.isWhitespace(trimmed.charAt(index))) index++;
			} else {
				break;
			}
		}
		return adjustment;
	}

	private static int depthAfterLine(int depthBefore, String line) {
		String trimmed = line.strip();
		if (trimmed.isEmpty()) return depthBefore;
		List<String> words = new ArrayList<>();
		Matcher matcher = WORD.matcher(trimmed);
		while (matcher.find()) words.add(matcher.group());

		int delta = delimiterDelta(trimmed);
		int forOrWhile = 0;
		int doCount = 0;
		for (String word : words) {
			switch (word) {
				case "function", "repeat" -> delta++;
				case "for", "while" -> forOrWhile++;
				case "do" -> doCount++;
				case "end", "until" -> delta--;
				default -> {
				}
			}
		}
		if (words.contains("if") && words.contains("then")) delta++;
		delta += Math.max(0, doCount - forOrWhile);
		delta += Math.min(doCount, forOrWhile);
		return Math.max(0, depthBefore + delta);
	}

	private static int delimiterDelta(String line) {
		int delta = 0;
		for (int index = 0; index < line.length(); index++) {
			delta += switch (line.charAt(index)) {
				case '{', '(', '[' -> 1;
				case '}', ')', ']' -> -1;
				default -> 0;
			};
		}
		return delta;
	}

	private static String maskNonCode(String source) {
		StringBuilder masked = new StringBuilder(source);
		int index = 0;
		while (index < source.length()) {
			char character = source.charAt(index);
			if (character == '-' && index + 1 < source.length() && source.charAt(index + 1) == '-') {
				int level = longBracketLevel(source, index + 2);
				int end = level >= 0 ? longBracketEnd(source, index + 2, level) : lineEnd(source, index + 2);
				maskRange(masked, source, index, end);
				index = end;
			} else if (character == '\'' || character == '"') {
				int end = quotedStringEnd(source, index, character);
				maskRange(masked, source, index, end);
				index = end;
			} else {
				int level = longBracketLevel(source, index);
				if (level >= 0) {
					int end = longBracketEnd(source, index, level);
					maskRange(masked, source, index, end);
					index = end;
				} else {
					index++;
				}
			}
		}
		return masked.toString();
	}

	private static void maskRange(StringBuilder masked, String source, int start, int end) {
		for (int index = start; index < end; index++) {
			if (source.charAt(index) != '\n') masked.setCharAt(index, ' ');
		}
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
			if (character == quote || character == '\n') break;
		}
		return index;
	}

	private static int longBracketLevel(String source, int start) {
		if (start < 0 || start >= source.length() || source.charAt(start) != '[') return -1;
		int index = start + 1;
		while (index < source.length() && source.charAt(index) == '=') index++;
		return index < source.length() && source.charAt(index) == '[' ? index - start - 1 : -1;
	}

	private static int longBracketEnd(String source, int start, int level) {
		String closing = "]" + "=".repeat(level) + "]";
		int closingStart = source.indexOf(closing, start + level + 2);
		return closingStart < 0 ? source.length() : closingStart + closing.length();
	}
}
