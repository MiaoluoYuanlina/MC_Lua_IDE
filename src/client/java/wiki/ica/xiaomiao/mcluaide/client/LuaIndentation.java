package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
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
		String masked = maskNonCode(source.substring(0, safeCursor));
		IndentState state = new IndentState();
		for (String line : masked.split("\n", -1)) state.line(line);
		return INDENT.repeat(state.depth());
	}

	static String desiredIndentForLine(String source, int lineStart) {
		int safeStart = Math.max(0, Math.min(lineStart, source.length()));
		int lineEnd = source.indexOf('\n', safeStart);
		if (lineEnd < 0) lineEnd = source.length();
		String maskedThroughLine = maskNonCode(source.substring(0, lineEnd));
		String before = maskedThroughLine.substring(0, safeStart);
		IndentState state = beforeLine(before);
		String currentMasked = maskedThroughLine.substring(safeStart);
		return INDENT.repeat(state.lineDepth(currentMasked));
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
		int lineStart = source.lastIndexOf('\n', safeStart - 1) + 1;
		String prefix = source.substring(lineStart, safeStart);
		String sourceBeforeInsertion = source.substring(0, safeStart);
		String maskedPrefix = maskNonCode(sourceBeforeInsertion);
		maskedPrefix = maskedPrefix.substring(maskedPrefix.lastIndexOf('\n') + 1);
		String maskedInserted = maskNonCode(sourceBeforeInsertion + normalized).substring(sourceBeforeInsertion.length());
		IndentState state = beforeLine(maskNonCode(source.substring(0, lineStart)));
		String[] pastedLines = normalized.split("\n", -1);
		String[] maskedPastedLines = maskedInserted.split("\n", -1);
		List<String> formatted = new ArrayList<>(pastedLines.length);

		for (int index = 0; index < pastedLines.length; index++) {
			String content = pastedLines[index].stripLeading().stripTrailing();
			if (index == 0 && !prefix.isBlank()) {
				formatted.add(maskedPrefix.isBlank() ? pastedLines[index] : content);
				state.line(maskedPrefix + maskedPastedLines[index]);
				continue;
			}
			if (content.isEmpty()) {
				formatted.add("");
				state.line("");
				continue;
			}
			String trimmedOriginal = pastedLines[index].strip();
			boolean entirelyNonCode = maskedPastedLines[index].isBlank() && !pastedLines[index].isBlank();
			boolean delimiterOrComment = trimmedOriginal.startsWith("--")
					|| trimmedOriginal.matches("\\[=*\\[.*") || trimmedOriginal.matches("\\]=*\\]");
			if (entirelyNonCode && !delimiterOrComment) {
				// Whitespace inside Lua long strings is data, so never reformat these lines.
				formatted.add(pastedLines[index]);
				state.line(maskedPastedLines[index]);
				continue;
			}
			String masked = maskedPastedLines[index].stripLeading().stripTrailing();
			String indent = INDENT.repeat(state.lineDepth(masked));
			// The caller may insert after existing indentation (components/UI).
			// Do not add the same leading whitespace for a second time.
			if (index == 0 && prefix.isBlank() && !prefix.isEmpty()) {
				int existing = 0;
				for (int c = 0; c < prefix.length(); c++) existing += prefix.charAt(c) == '\t' ? 4 : 1;
				indent = indent.substring(Math.min(existing, indent.length()));
			}
			formatted.add(indent + content);
			state.line(masked);
		}
		return String.join("\n", formatted);
	}

	static boolean pasteWillBeMultiline(String clipboard) {
		return expandCompactCode(normalizeNewlines(clipboard)).contains("\n");
	}

	/** Only change leading indentation. Never rewrite the contents of multiline strings. */
	static String formatDocument(String source) {
		List<StringSpan> strings = new ArrayList<>();
		String masked = maskNonCode(source, strings);
		IndentState state = new IndentState();
		StringBuilder result = new StringBuilder(source.length());
		int start = 0, stringIndex = 0;
		while (start <= source.length()) {
			int end = source.indexOf('\n', start);
			if (end < 0) end = source.length();
			while (stringIndex < strings.size() && strings.get(stringIndex).end() <= start) stringIndex++;
			boolean insideString = stringIndex < strings.size() && strings.get(stringIndex).start() < start;
			String line = source.substring(start, end);
			String code = masked.substring(start, end);
			int leading = 0;
			while (leading < line.length() && (line.charAt(leading) == ' ' || line.charAt(leading) == '\t')) leading++;
			if (insideString) result.append(line);
			else if (leading < line.length() && !line.equals("\r")) {
				int depth = state.lineDepth(code);
				if (result.length() + (long) depth * 4 + line.length() - leading > wiki.ica.xiaomiao.mcluaide.network.LuaProtocol.MAX_SCRIPT_CHARS)
					throw new IllegalArgumentException("整理后的文件超过 5,000,000 个字符，未修改原文");
				result.append(INDENT.repeat(depth)).append(line.substring(leading));
			} else if (line.endsWith("\r")) result.append('\r');
			state.line(code);
			if (end == source.length()) break;
			result.append('\n');
			start = end + 1;
		}
		return result.toString();
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

	private static IndentState beforeLine(String maskedBefore) {
		IndentState state = new IndentState();
		String[] lines = maskedBefore.split("\n", -1);
		for (int i = 0; i + 1 < lines.length; i++) state.line(lines[i]);
		return state;
	}

	/** Keep delimiter identity as well as depth: end) must close one visual level,
	 * while a call opened on an earlier physical line still has its own level. */
	private static final class IndentState {
		private final Deque<Frame> frames = new ArrayDeque<>();
		private int line;
		int depth() { return frames.stream().mapToInt(frame -> frame.weight).sum(); }
		int lineDepth(String masked) {
			IndentState preview = new IndentState();
			for (Frame frame : frames) {
				Frame copy = new Frame(frame.kind, frame.line, frame.weight);
				copy.parametersPending = frame.parametersPending;
				preview.frames.addLast(copy);
			}
			for (var token : LuaCodeAnalyzer.lex(masked)) {
				String text = token.text();
				if (text.equals("else") || text.equals("elseif")) {
					Frame conditional = preview.find("if");
					return Math.max(0, preview.depth() - (conditional == null ? 0 : conditional.weight));
				}
				if (!Set.of("end", "until", ")", "}", "]").contains(text)) break;
				preview.close(text, false);
			}
			return preview.depth();
		}
		void line(String masked) {
			for (var token : LuaCodeAnalyzer.lex(masked)) {
				String text = token.text();
				switch (text) {
					case "function" -> {
						// foo(function(...): call continuation and callback body are
						// one indentation step, not two. Retain the '(' at weight 0.
						for (Frame frame : frames) {
							if (frame.line != line || !frame.kind.equals("(")) break;
							frame.weight = 0;
						}
						frames.push(new Frame("function", line, 1));
					}
					case "if" -> frames.push(new Frame("if", line, 0));
					case "then" -> { Frame frame = find("if"); if (frame != null) frame.weight = 1; }
					case "for", "while" -> frames.push(new Frame("loop", line, 0));
					case "do" -> {
						Frame frame = frames.peek();
						if (frame != null && frame.kind.equals("loop") && frame.weight == 0) frame.weight = 1;
						else frames.push(new Frame("do", line, 1));
					}
					case "repeat" -> frames.push(new Frame("repeat", line, 1));
					case "(", "{", "[" -> {
						Frame top = frames.peek();
						boolean parameters = text.equals("(") && top != null && top.parametersPending;
						int weight = parameters ? 0 : 1;
						if (parameters) top.parametersPending = false;
						frames.push(new Frame(text, line, weight));
					}
					case "end", "until", ")", "}", "]" -> close(text);
					default -> { }
				}
			}
			line++;
		}
		private Frame find(String kind) {
			for (Frame frame : frames) if (frame.kind.equals(kind)) return frame;
			return null;
		}
		private void close(String token) { close(token, true); }
		private void close(String token, boolean restoreContinuation) {
			Frame target = null;
			String kind = switch (token) { case ")" -> "("; case "}" -> "{"; case "]" -> "["; case "until" -> "repeat"; default -> ""; };
			for (Frame frame : frames) {
				if (kind.isEmpty() ? Set.of("function", "if", "loop", "do").contains(frame.kind) : frame.kind.equals(kind)) {
					target = frame; break;
				}
			}
			if (target != null) {
				while (frames.pop() != target) { }
				// On the closing line, end is aligned with foo(function(...).
				// Subsequent arguments still belong to foo's continuation level.
				if (restoreContinuation && target.kind.equals("function")) for (Frame frame : frames) {
					if (!frame.kind.equals("(") || frame.line != target.line) break;
					frame.weight = 1;
				}
			}
		}
	}
	private static final class Frame {
		final String kind; final int line; int weight;
		boolean parametersPending;
		Frame(String kind, int line, int weight) {
			this.kind = kind; this.line = line; this.weight = weight;
			parametersPending = kind.equals("function");
		}
	}

	private record StringSpan(int start, int end) { }
	private static String maskNonCode(String source) { return maskNonCode(source, null); }
	private static String maskNonCode(String source, List<StringSpan> strings) {
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
				if (strings != null) strings.add(new StringSpan(index, end));
				maskRange(masked, source, index, end);
				index = end;
			} else {
				int level = longBracketLevel(source, index);
				if (level >= 0) {
					int end = longBracketEnd(source, index, level);
					if (strings != null) strings.add(new StringSpan(index, end));
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
