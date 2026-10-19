package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.network.LuaMcpFileChanged;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connection-scoped, bounded handoff. Game thread only; no filesystem polling or credentials. */
final class LuaMcpFileSync {
	record Update(long revision, LuaMcpFileChanged change, boolean dismissed) { }
	private static final Map<String, Update> updates = new LinkedHashMap<>();
	private static long revision;
	private LuaMcpFileSync() { }
	static void receive(LuaMcpFileChanged change) {
		updates.remove(change.name()); updates.put(change.name(), new Update(++revision, change, false));
		long chars = 0;
		for (var update : updates.values()) chars += update.change.source().length();
		while (updates.size() > 32 || chars > 10_000_000) {
			var first = updates.entrySet().iterator(); var removed = first.next();
			chars -= removed.getValue().change.source().length(); first.remove();
		}
	}
	static long revision() { return revision; }
	static Update latest(String name) { return updates.get(name); }
	static void saved(String name) { updates.remove(name); }
	static void observedRead(String name, String source, long requestedAt) {
		var update = updates.get(name);
		if (update != null && update.revision <= requestedAt && !update.change.source().equals(source)) updates.remove(name);
	}
	static void dismissAll() {
		updates.replaceAll((name, update) -> new Update(update.revision, update.change, true));
	}
	static List<Update> snapshots() { return List.copyOf(updates.values()); }
	static void disconnected() { updates.clear(); revision++; }
}
