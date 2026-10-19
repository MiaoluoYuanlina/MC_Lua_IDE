package wiki.ica.xiaomiao.mcluaide.client;

import java.util.function.ToIntFunction;

/** Shared filename viewport; callers keep the complete path for filesystem/network operations. */
final class DirectoryPathInput {
	record View(int start, int end, String text) { }
	private DirectoryPathInput() { }
	static int leafStart(String value) { return value.lastIndexOf('/') + 1; }
	static View view(String value, int cursor, int minimum, int width, ToIntFunction<String> measure) {
		minimum = Math.clamp(minimum, 0, value.length());
		cursor = Math.clamp(cursor, minimum, value.length());
		width = Math.max(1, width);
		int start = cursor;
		while (start > minimum) {
			int previous = value.offsetByCodePoints(start, -1);
			if (measure.applyAsInt(value.substring(previous, cursor)) > Math.max(1, width - 1)) break;
			start = previous;
		}
		int end = start;
		while (end < value.length()) {
			int next = value.offsetByCodePoints(end, 1);
			if (measure.applyAsInt(value.substring(start, next)) > width) break;
			end = next;
		}
		return new View(start, end, value.substring(start, end));
	}
	static int offsetAt(String value, View view, double relativeX, ToIntFunction<String> measure) {
		if (relativeX <= 0) return view.start();
		int previousWidth = 0;
		for (int index = view.start(); index < view.end();) {
			int next = value.offsetByCodePoints(index, 1);
			int nextWidth = measure.applyAsInt(value.substring(view.start(), next));
			if (relativeX < (previousWidth + nextWidth) / 2.0) return index;
			previousWidth = nextWidth;
			index = next;
		}
		return view.end();
	}
	static String parent(String value) { return value.substring(0, leafStart(value)); }
	static String shorten(String value, int width, ToIntFunction<String> measure) {
		if (measure.applyAsInt(value) <= width) return value;
		int start = 0;
		while (start < value.length() && measure.applyAsInt("…" + value.substring(start)) > width)
			start = value.offsetByCodePoints(start, 1);
		return width < measure.applyAsInt("…") ? "" : "…" + value.substring(start);
	}
}
