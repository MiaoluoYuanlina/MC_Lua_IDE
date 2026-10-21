package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Several administrators editing the same workspace: who has which file open (and unsaved), and saves that refuse to overwrite a
 * version the editor never saw. Presence is reported by each IDE every few seconds and forgotten when it stops reporting.
 */
public final class LuaEditPresence {
	/** A file an administrator has open. {@code kind} is lua, datapack or resourcepack. */
	record OpenFile(String kind, String path, boolean dirty) { }
	private record Report(String player, List<OpenFile> files, long at) { }
	private record LastSave(String player, long at) { }

	static final long EXPIRE_MILLIS = 30_000;
	private static final int MAX_FILES = 64;
	/** The prefix of a refused save, so the IDE can offer to reload, compare or overwrite. */
	public static final String CONFLICT = "CONFLICT:";
	private static final Map<UUID, Report> REPORTS = new ConcurrentHashMap<>();
	private static final Map<String, LastSave> LAST_SAVES = new ConcurrentHashMap<>();

	private LuaEditPresence() { }

	/** Body: {"files":[{"kind":"lua","path":"a/b","dirty":true}]}. Returns {"others":[{"player","kind","path","dirty","seconds"}]}. */
	static String report(UUID playerId, String playerName, String body) {
		List<OpenFile> files = new ArrayList<>();
		JsonObject request = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
		if (request.has("files")) for (var element : request.getAsJsonArray("files")) {
			if (files.size() >= MAX_FILES) break;
			JsonObject item = element.getAsJsonObject();
			String kind = item.get("kind").getAsString(), path = item.get("path").getAsString();
			if (!kind.matches("lua|datapack|resourcepack") || path.isBlank() || path.length() > 512) continue;
			files.add(new OpenFile(kind, path, item.has("dirty") && item.get("dirty").getAsBoolean()));
		}
		long now = System.currentTimeMillis();
		REPORTS.put(playerId, new Report(playerName, List.copyOf(files), now));
		REPORTS.entrySet().removeIf(entry -> now - entry.getValue().at() > EXPIRE_MILLIS);
		JsonArray others = new JsonArray();
		REPORTS.forEach((id, report) -> {
			if (id.equals(playerId)) return;
			for (OpenFile file : report.files()) {
				JsonObject item = new JsonObject();
				item.addProperty("player", report.player()); item.addProperty("kind", file.kind());
				item.addProperty("path", file.path()); item.addProperty("dirty", file.dirty());
				item.addProperty("seconds", (now - report.at()) / 1000);
				others.add(item);
			}
		});
		JsonObject reply = new JsonObject();
		reply.add("others", others);
		return reply.toString();
	}

	static void disconnected(UUID playerId) { REPORTS.remove(playerId); }

	/** Remembers who saved a script last (also MCP and plain saves), for the conflict message. */
	static void saved(String name, String player) { LAST_SAVES.put(name, new LastSave(player, System.currentTimeMillis())); }

	/**
	 * Body: {"base":"sha256 of the version the editor started from, empty for a new file","source":"..."}. Saves when the file on
	 * disk is still that version; otherwise throws a message starting with {@link #CONFLICT}.
	 */
	static void saveChecked(String name, String playerName, String body) throws IOException {
		saveChecked(name, playerName, body, ServerScriptManager::readIfPresent, ServerScriptManager::save);
	}

	interface Reader { String read(String name) throws IOException; }
	interface Writer { void write(String name, String source) throws IOException; }

	/** As above with the storage passed in (the smoke test uses a map). */
	static void saveChecked(String name, String playerName, String body, Reader reader, Writer writer) throws IOException {
		JsonObject request = JsonParser.parseString(body).getAsJsonObject();
		String base = request.has("base") ? request.get("base").getAsString() : "";
		String source = request.get("source").getAsString();
		synchronized (LuaEditPresence.class) {
			String current = reader.read(name);
			String currentHash = current.isEmpty() ? "" : LuaBreakpoints.hash(current);
			boolean unchanged = currentHash.equals(base) || current.equals(source);
			if (!unchanged) {
				LastSave last = LAST_SAVES.get(name);
				String who = last == null ? "" : last.player() + "，" + Math.max(0, (System.currentTimeMillis() - last.at()) / 1000) + " 秒前";
				throw new IllegalStateException(CONFLICT + "此文件在你打开后已被修改" + (who.isEmpty() ? "" : "（" + who + "）") + "，保存已取消");
			}
			writer.write(name, source);
			saved(name, playerName);
		}
	}
}
