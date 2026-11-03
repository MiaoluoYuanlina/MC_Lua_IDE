package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded, process-wide record of Lua script output. Every start path (IDE, command, MCP) feeds it through
 * {@link ServerScriptManager}, so a reader can follow {@code seq} to fetch only what is new.
 */
public final class LuaRuntimeLog {
	private static final int CAPACITY = 1000, MAX_LINE = 1000;
	private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
	private static long next = 1;

	public record Entry(long seq, long time, String script, String line) { }

	private LuaRuntimeLog() { }

	public static synchronized void add(String script, String line) {
		String text = line == null ? "" : line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line;
		ENTRIES.addLast(new Entry(next++, System.currentTimeMillis(), script == null ? "" : script, text));
		while (ENTRIES.size() > CAPACITY) ENTRIES.removeFirst();
	}

	public static synchronized void clear() {
		ENTRIES.clear();
	}

	/** Entries with {@code seq > since}, oldest first, at most {@code limit}. {@code nextSince} is the cursor for the next call. */
	public static synchronized JsonObject read(long since, int limit) {
		JsonArray lines = new JsonArray();
		long last = since;
		boolean more = false;
		for (Entry entry : ENTRIES) {
			if (entry.seq() <= since) continue;
			if (lines.size() >= limit) { more = true; break; }
			JsonObject item = new JsonObject();
			item.addProperty("seq", entry.seq());
			item.addProperty("time", entry.time());
			item.addProperty("script", entry.script());
			item.addProperty("line", entry.line());
			lines.add(item);
			last = entry.seq();
		}
		JsonObject out = new JsonObject();
		out.add("lines", lines);
		out.addProperty("nextSince", last);
		out.addProperty("more", more);
		// Older lines are dropped once the buffer is full; tell the reader if its cursor points before the oldest kept line.
		out.addProperty("dropped", !ENTRIES.isEmpty() && since + 1 < ENTRIES.peekFirst().seq() && since > 0);
		return out;
	}
}
