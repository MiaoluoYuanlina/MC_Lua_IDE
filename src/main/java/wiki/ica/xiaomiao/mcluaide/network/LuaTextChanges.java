package wiki.ica.xiaomiao.mcluaide.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Bounded linear line-anchor diff: precise character spans within stable lines, no quadratic LCS. */
public final class LuaTextChanges {
	public static final int MAX_EDITS = 256;
	public record Edit(int oldStart, int oldEnd, int start, int end) { }
	private record Line(int start, int end) { }
	private LuaTextChanges() { }
	public static String hash(String source) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
	}
	public static List<Edit> between(String before, String after) {
		if (before.equals(after)) return List.of();
		int prefix = 0, limit = Math.min(before.length(), after.length());
		while (prefix < limit && before.charAt(prefix) == after.charAt(prefix)) prefix++;
		if (prefix > 0 && prefix < before.length() && Character.isLowSurrogate(before.charAt(prefix))) prefix--;
		int oldEnd = before.length(), newEnd = after.length();
		while (oldEnd > prefix && newEnd > prefix && before.charAt(oldEnd - 1) == after.charAt(newEnd - 1)) { oldEnd--; newEnd--; }
		if (newEnd < after.length() && newEnd > 0 && Character.isLowSurrogate(after.charAt(newEnd))) { oldEnd++; newEnd++; }
		var anchors = new HashMap<String, Line>();
		int lines = 0;
		for (int start = prefix; start < oldEnd;) {
			int end = lineEnd(before, start, oldEnd);
			if (++lines > 20_000) return List.of(new Edit(prefix, oldEnd, prefix, newEnd));
			String text = before.substring(start, end);
			if (!text.isBlank()) anchors.merge(text, new Line(start, end), (a, b) -> new Line(-1, -1));
			start = end;
		}
		List<Edit> result = new ArrayList<>();
		int oldCursor = prefix, newCursor = prefix;
		for (int start = prefix; start < newEnd;) {
			int end = lineEnd(after, start, newEnd);
			Line anchor = anchors.get(after.substring(start, end));
			if (anchor != null && anchor.start >= oldCursor) {
				add(result, before, after, oldCursor, anchor.start, newCursor, start);
				oldCursor = anchor.end; newCursor = end;
				if (result.size() >= MAX_EDITS - 1) break;
			}
			start = end;
		}
		add(result, before, after, oldCursor, oldEnd, newCursor, newEnd);
		return List.copyOf(result);
	}
	private static int lineEnd(String source, int start, int limit) {
		int newline = source.indexOf('\n', start);
		return newline < 0 || newline >= limit ? limit : newline + 1;
	}
	private static void add(List<Edit> result, String before, String after, int a, int b, int c, int d) {
		while (a < b && c < d && before.charAt(a) == after.charAt(c)) { a++; c++; }
		while (b > a && d > c && before.charAt(b - 1) == after.charAt(d - 1)) { b--; d--; }
		if (a == b && c == d) return;
		if (a > 0 && a < before.length() && Character.isLowSurrogate(before.charAt(a))) { a--; c--; }
		if (d > 0 && d < after.length() && Character.isLowSurrogate(after.charAt(d))) { b++; d++; }
		result.add(new Edit(a, b, c, d));
	}
	public static int mapCursor(List<Edit> edits, int cursor, int newLength) {
		int delta = 0;
		for (Edit edit : edits) {
			if (cursor < edit.oldStart) break;
			if (cursor <= edit.oldEnd) return Math.clamp(edit.start + Math.min(cursor - edit.oldStart, edit.end - edit.start), 0, newLength);
			delta = edit.end - edit.oldEnd;
		}
		return Math.clamp(cursor + delta, 0, newLength);
	}
}
