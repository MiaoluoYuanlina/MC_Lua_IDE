package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Who changed or ran what through the IDE, kept on the server in {@code config/mc_lua_ide/audit.log} (one JSON object per line).
 * The file rotates at 1 MB and keeps three older files. Administrators can read the most recent entries in the IDE; nobody can
 * change or delete them through the protocol.
 */
public final class LuaAuditLog {
	private static final long MAX_BYTES = 1_048_576;
	private static final int KEEP = 3, MAX_TEXT = 300;
	private static volatile Path directory;
	private static final Object LOCK = new Object();

	private LuaAuditLog() { }

	/** Smoke tests run without a loader and point the log at a scratch directory. */
	public static void useDirectoryForTests(Path path) { directory = path; }

	private static Path file() {
		Path base = directory != null ? directory : FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide");
		return base.resolve("audit.log");
	}

	/** One action by a player (or the server console when {@code playerId} is null). {@code outcome} is "requested", "ok" or an error. */
	public static void record(String player, UUID playerId, String action, String target, String outcome) {
		JsonObject entry = new JsonObject();
		entry.addProperty("time", Instant.now().toString());
		entry.addProperty("player", clip(player));
		entry.addProperty("uuid", playerId == null ? "" : playerId.toString());
		entry.addProperty("action", clip(action));
		entry.addProperty("target", clip(target));
		entry.addProperty("outcome", clip(outcome));
		String line = entry + "\n";
		synchronized (LOCK) {
			try {
				Path path = file();
				Files.createDirectories(path.getParent());
				if (Files.exists(path) && Files.size(path) + line.length() > MAX_BYTES) rotate(path);
				Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException error) {
				McLuaIdeMod.LOGGER.warn("Could not write the MC Lua IDE audit log", error);
			}
		}
	}

	private static void rotate(Path path) throws IOException {
		for (int i = KEEP; i >= 1; i--) {
			Path older = path.resolveSibling(path.getFileName() + "." + i), newer = i == 1 ? path : path.resolveSibling(path.getFileName() + "." + (i - 1));
			if (Files.exists(newer)) Files.move(newer, older, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The newest {@code limit} entries, newest first, as a JSON array (current file, then the rotated ones if needed). */
	public static String recent(int limit) {
		Deque<String> lines = new ArrayDeque<>();
		synchronized (LOCK) {
			Path path = file();
			for (int i = 0; i <= KEEP && lines.size() < limit; i++) {
				Path source = i == 0 ? path : path.resolveSibling(path.getFileName() + "." + i);
				if (!Files.exists(source)) continue;
				try {
					List<String> content = Files.readAllLines(source, StandardCharsets.UTF_8);
					for (int j = content.size() - 1; j >= 0 && lines.size() < limit; j--) if (!content.get(j).isBlank()) lines.addLast(content.get(j));
				} catch (IOException error) {
					McLuaIdeMod.LOGGER.warn("Could not read the MC Lua IDE audit log {}", source, error);
				}
			}
		}
		JsonArray array = new JsonArray();
		for (String line : lines) {
			try { array.add(JsonParser.parseString(line).getAsJsonObject()); } catch (RuntimeException skipped) { }
		}
		return array.toString();
	}

	private static String clip(String text) {
		if (text == null) return "";
		String single = text.replace('\n', ' ').replace('\r', ' ');
		return single.length() > MAX_TEXT ? single.substring(0, MAX_TEXT) + "…" : single;
	}
}
