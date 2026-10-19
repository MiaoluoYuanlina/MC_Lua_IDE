package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.Deque;

/** Keep useful undo/redo snapshots without retaining hundreds of multi-megabyte files. */
final class LuaTextHistory {
	static final int MAX_ENTRIES = 100;
	static final long MAX_CHARS = LuaProtocol.MAX_SCRIPT_CHARS * 2L;

	private LuaTextHistory() { }

	/** Follow unchanged text across an edit; deleted positions collapse to the nearest surviving location. */
	static int mapCursor(String before, String after, int cursor) {
		int offset = Math.clamp(cursor, 0, before.length());
		int prefix = 0, limit = Math.min(before.length(), after.length());
		while (prefix < limit && before.charAt(prefix) == after.charAt(prefix)) prefix++;
		int oldEnd = before.length(), newEnd = after.length();
		while (oldEnd > prefix && newEnd > prefix && before.charAt(oldEnd - 1) == after.charAt(newEnd - 1)) {
			oldEnd--; newEnd--;
		}
		int mapped = offset <= prefix ? offset : offset >= oldEnd
				? newEnd + offset - oldEnd : prefix + Math.min(offset - prefix, newEnd - prefix);
		// Do not place the caret inside a supplementary Unicode character.
		if (mapped > 0 && mapped < after.length() && Character.isHighSurrogate(after.charAt(mapped - 1))
				&& Character.isLowSurrogate(after.charAt(mapped))) mapped--;
		return mapped;
	}

	static void trim(Deque<String> history) {
		long characters = 0;
		for (String snapshot : history) characters += snapshot.length();
		while (history.size() > 1 && (history.size() > MAX_ENTRIES || characters > MAX_CHARS)) {
			characters -= history.removeFirst().length();
		}
	}
}
