package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Snapshot-backed text selection so a live refresh cannot change the text being copied. */
final class LuaVariableText {
	record Line(LuaDebugHud.DisplayRow row, String text, int start, int textStart, int end) { }
	private List<LuaDebugHud.DisplayRow> rows = List.of();
	private List<Line> lines = List.of();
	private String source = "";
	private int anchor, caret;
	private boolean selecting;
	void update(List<LuaDebugHud.DisplayRow> next) {
		if (selecting || hasSelection() || rows == next) return;
		rows = next;
		List<Line> result = new ArrayList<>(); StringBuilder text = new StringBuilder();
		for (var row : next) {
			if (!result.isEmpty()) text.append('\n');
			int start = text.length(); text.append("    ".repeat(Math.clamp(row.depth(), 0, 8)));
			int textStart = text.length(); String value = (row.branch() ? "▾ " : "") + row.text() + row.value();
			text.append(value); result.add(new Line(row, value, start, textStart, text.length()));
		}
		lines = List.copyOf(result); source = text.toString(); anchor = caret = 0;
	}
	List<Line> lines() { return lines; }
	String source() { return source; }
	boolean selecting() { return selecting; }
	boolean hasSelection() { return anchor != caret; }
	int start() { return Math.min(anchor, caret); }
	int end() { return Math.max(anchor, caret); }
	String selected() { return source.substring(start(), end()); }
	void begin(int offset) { anchor = caret = safe(offset); selecting = true; }
	void drag(int offset) { if (selecting) caret = safe(offset); }
	void release() { selecting = false; }
	void clear() { selecting = false; anchor = caret = 0; }
	void selectAll() { selecting = false; anchor = 0; caret = source.length(); }
	void selectLine(int index) {
		if (lines.isEmpty()) return;
		var line = lines.get(Math.clamp(index, 0, lines.size() - 1));
		anchor = line.start; caret = line.end; selecting = false;
	}
	int offsetAt(int index, double relativeX, ToIntFunction<String> width) {
		if (lines.isEmpty()) return 0;
		var line = lines.get(Math.clamp(index, 0, lines.size() - 1));
		if (relativeX < 0) return line.start;
		int previous = 0;
		for (int i = 0; i < line.text.length();) {
			int next = line.text.offsetByCodePoints(i, 1), measured = width.applyAsInt(line.text.substring(0, next));
			if (relativeX < (previous + measured) / 2D) return line.textStart + i;
			previous = measured; i = next;
		}
		return line.end;
	}
	private int safe(int offset) {
		int result = Math.clamp(offset, 0, source.length());
		if (result > 0 && result < source.length() && Character.isLowSurrogate(source.charAt(result)) && Character.isHighSurrogate(source.charAt(result - 1))) result--;
		return result;
	}
}
