package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imgui.ImGui;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells the server which files this IDE has open (every few seconds) and remembers which files other administrators have open,
 * so the editor can warn before two people edit the same file. Conflicting saves are still caught by the server (see ScriptManager.save).
 */
final class EditPresenceClient {
	/** Another administrator who has a file open. */
	record Viewer(String player, boolean dirty) { }
	private static final long INTERVAL_NANOS = 5_000_000_000L;
	private static Map<String, List<Viewer>> others = Map.of();
	private static long nextReport;
	private static boolean pending;

	private EditPresenceClient() { }

	private static String key(ImGuiIdeDocument.Kind kind, String path) { return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + path; }

	/** Call from the IDE's tick with its open documents. */
	static void tick(Collection<ImGuiIdeDocument> documents) {
		long now = System.nanoTime();
		if (pending || now < nextReport || !ScriptManager.isServerAvailable()) return;
		nextReport = now + INTERVAL_NANOS; pending = true;
		JsonArray files = new JsonArray();
		for (var doc : documents) {
			if (files.size() >= 64 || doc.binary()) continue;
			JsonObject item = new JsonObject();
			item.addProperty("kind", doc.kind.name().toLowerCase(java.util.Locale.ROOT));
			item.addProperty("path", doc.path); item.addProperty("dirty", doc.dirty());
			files.add(item);
		}
		JsonObject body = new JsonObject(); body.add("files", files);
		ScriptManager.filePresence(body.toString()).whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
			pending = false;
			if (error != null) return;
			try {
				Map<String, List<Viewer>> next = new HashMap<>();
				for (var element : JsonParser.parseString(data).getAsJsonObject().getAsJsonArray("others")) {
					JsonObject item = element.getAsJsonObject();
					String fileKey = item.get("kind").getAsString() + ":" + item.get("path").getAsString();
					next.computeIfAbsent(fileKey, ignored -> new ArrayList<>()).add(new Viewer(item.get("player").getAsString(), item.get("dirty").getAsBoolean()));
				}
				others = next;
			} catch (RuntimeException ignored) { }
		}));
	}

	static List<Viewer> viewers(ImGuiIdeDocument doc) { return others.getOrDefault(key(doc.kind, doc.path), List.of()); }

	/** A one-line warning above the editor when someone else has this file open. */
	static void banner(ImGuiIdeDocument doc) {
		var viewers = viewers(doc);
		if (viewers.isEmpty()) return;
		List<String> names = new ArrayList<>();
		boolean editing = false;
		for (var viewer : viewers) { names.add(viewer.player() + (viewer.dirty() ? "*" : "")); editing |= viewer.dirty(); }
		String text = LuaUiText.text("也在编辑此文件：", "Also open by: ") + String.join(", ", names)
				+ (editing ? LuaUiText.text("（* 有未保存的改动，保存前请先沟通）", " (* has unsaved changes; coordinate before saving)") : "");
		if (editing) ImGui.textColored(1f, .62f, .3f, 1f, text); else ImGui.textColored(.55f, .78f, 1f, 1f, text);
	}

	static void disconnected() { others = Map.of(); pending = false; nextReport = 0; }
}
