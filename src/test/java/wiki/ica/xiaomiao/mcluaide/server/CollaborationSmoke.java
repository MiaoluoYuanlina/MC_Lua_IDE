package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Audit log (record, rotate, read newest first) and multi-administrator editing (presence, conflict-checked saves). */
public final class CollaborationSmoke {
	private static int checks;

	public static void main(String[] args) throws Exception {
		audit(); presence(); checkedSaves();
		System.out.println("Collaboration smoke passed: " + checks + " checks; audit log, presence and conflict-checked saves");
	}

	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

	private static void audit() throws Exception {
		Path directory = Files.createTempDirectory("mc-lua-ide-audit");
		LuaAuditLog.useDirectoryForTests(directory);
		UUID alex = UUID.randomUUID();
		LuaAuditLog.record("Alex", alex, "SAVE", "shop/main", "requested");
		LuaAuditLog.record("Alex", alex, "RUN", "shop/main\nwith a newline", "failed: boom");
		LuaAuditLog.record("console", null, "COMMAND_STOP", "", "ok");
		var entries = JsonParser.parseString(LuaAuditLog.recent(10)).getAsJsonArray();
		require(entries.size() == 3, "three entries");
		require(entries.get(0).getAsJsonObject().get("action").getAsString().equals("COMMAND_STOP"), "newest first");
		require(entries.get(1).getAsJsonObject().get("target").getAsString().equals("shop/main with a newline"), "one line per entry");
		require(entries.get(2).getAsJsonObject().get("uuid").getAsString().equals(alex.toString()), "player id kept");
		require(JsonParser.parseString(LuaAuditLog.recent(2)).getAsJsonArray().size() == 2, "limit respected");
		String long_ = "x".repeat(2000);
		for (int i = 0; i < 3000; i++) LuaAuditLog.record("Alex", alex, "SAVE", long_, "ok");
		require(Files.size(directory.resolve("audit.log")) <= 1_048_576, "the current file stays under 1 MB");
		require(Files.exists(directory.resolve("audit.log.1")), "full files are rotated");
		require(JsonParser.parseString(LuaAuditLog.recent(300)).getAsJsonArray().size() == 300, "reading continues into rotated files");
		require(JsonParser.parseString(LuaAuditLog.recent(1)).getAsJsonArray().get(0).getAsJsonObject().get("target").getAsString().length() <= 301, "long text is clipped");
	}

	private static void presence() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		LuaEditPresence.report(a, "Alex", "{\"files\":[{\"kind\":\"lua\",\"path\":\"shop/main\",\"dirty\":true},{\"kind\":\"evil\",\"path\":\"x\"}]}");
		var seenByB = JsonParser.parseString(LuaEditPresence.report(b, "Bo", "{\"files\":[]}")).getAsJsonObject().getAsJsonArray("others");
		require(seenByB.size() == 1, "Bo sees Alex's one valid file (unknown kinds are dropped)");
		var file = seenByB.get(0).getAsJsonObject();
		require(file.get("player").getAsString().equals("Alex") && file.get("path").getAsString().equals("shop/main") && file.get("dirty").getAsBoolean(), "file, player and unsaved flag");
		var seenByA = JsonParser.parseString(LuaEditPresence.report(a, "Alex", "{\"files\":[]}")).getAsJsonObject().getAsJsonArray("others");
		require(seenByA.isEmpty(), "Bo has nothing open, and Alex never sees himself");
		LuaEditPresence.disconnected(a);
		require(JsonParser.parseString(LuaEditPresence.report(b, "Bo", "{}")).getAsJsonObject().getAsJsonArray("others").isEmpty(), "a disconnected player disappears");
	}

	private static void checkedSaves() throws Exception {
		Map<String, String> disk = new HashMap<>();
		LuaEditPresence.Reader reader = name -> disk.getOrDefault(name, "");
		LuaEditPresence.Writer writer = disk::put;
		LuaEditPresence.saveChecked("new", "Alex", body("", "print(1)"), reader, writer);
		require(disk.get("new").equals("print(1)"), "a new file saves with an empty base");
		String v1 = LuaBreakpoints.hash("print(1)");
		LuaEditPresence.saveChecked("new", "Alex", body(v1, "print(2)"), reader, writer);
		require(disk.get("new").equals("print(2)"), "a save based on the current version succeeds");
		boolean refused = false;
		try { LuaEditPresence.saveChecked("new", "Bo", body(v1, "print(3)"), reader, writer); }
		catch (IllegalStateException conflict) { refused = conflict.getMessage().startsWith(LuaEditPresence.CONFLICT) && conflict.getMessage().contains("Alex"); }
		require(refused, "a save based on an older version is refused and names who saved last");
		require(disk.get("new").equals("print(2)"), "the refused save changed nothing");
		LuaEditPresence.saveChecked("new", "Bo", body(v1, "print(2)"), reader, writer);
		require(disk.get("new").equals("print(2)"), "saving the same text as on disk is not a conflict");
		refused = false;
		try { LuaEditPresence.saveChecked("new", "Bo", body("", "other"), reader, writer); } catch (IllegalStateException conflict) { refused = true; }
		require(refused, "claiming a new file that already exists is refused");
	}

	private static String body(String base, String source) {
		var json = new com.google.gson.JsonObject();
		json.addProperty("base", base); json.addProperty("source", source);
		return json.toString();
	}
}
