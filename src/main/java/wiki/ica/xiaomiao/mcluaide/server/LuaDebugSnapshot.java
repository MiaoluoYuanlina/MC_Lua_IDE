package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;

/** Bounded, read-only snapshots taken on the Lua execution thread. */
final class LuaDebugSnapshot {
	private static final int MAX_VARIABLES = 256;
	private static final int MAX_FRAMES = 32;
	private static final int MAX_LOCALS_PER_FRAME = 64;
	private static final int MAX_TABLE_FIELDS = 8;
	private static final int MAX_TREE_DEPTH = 5;
	private static final int MAX_TREE_CHILDREN = 64;
	private static final int MAX_TREE_NODES = 768;
	private static final int MAX_HISTORY_CONTEXTS = 32;
	private static final int MAX_HISTORY_CHARS = 400_000;

	private LuaDebugSnapshot() { }

	static Map<String, LuaValue> baseline(Globals globals) {
		Map<String, LuaValue> values = new LinkedHashMap<>();
		LuaValue key = LuaValue.NIL;
		while (true) {
			Varargs pair = globals.next(key);
			key = pair.arg1();
			if (key.isnil()) return values;
			if (key.type() == LuaValue.TSTRING) values.put(key.tojstring(), pair.arg(2));
		}
	}

	static String capture(String script, Globals globals, LuaValue debugLibrary,
			Map<String, LuaValue> baseline) {
		LuaValue getLocal = debugLibrary.get("getlocal");
		LuaValue getInfo = debugLibrary.get("getinfo");
		LuaValue getUpvalue = debugLibrary.get("getupvalue");
		JsonObject result = new JsonObject();
		result.addProperty("running", true);
		result.addProperty("script", script);
		JsonArray variables = new JsonArray();
		TreeBudget treeBudget = new TreeBudget(MAX_TREE_NODES);
		int omitted = 0;
		for (int frame = 1; frame <= MAX_FRAMES; frame++) {
			LuaValue info = getInfo.invoke(LuaValue.varargsOf(
					LuaValue.valueOf(frame), LuaValue.valueOf("f"))).arg1();
			if (info.isnil()) break;
			for (int index = 1; index <= MAX_LOCALS_PER_FRAME; index++) {
				Varargs local = getLocal.invoke(LuaValue.varargsOf(
						LuaValue.valueOf(frame), LuaValue.valueOf(index)));
				if (local.arg1().isnil()) break;
				String name = local.arg1().tojstring();
				if (name.startsWith("(") || name.startsWith("*")) continue;
				if (variables.size() < MAX_VARIABLES)
					add(variables, "局部 · 栈" + frame, name, local.arg(2), treeBudget);
				else omitted++;
			}
			LuaValue function = info.get("func");
			if (function.isfunction()) {
				for (int index = 1; index <= MAX_LOCALS_PER_FRAME; index++) {
					Varargs upvalue = getUpvalue.invoke(LuaValue.varargsOf(function, LuaValue.valueOf(index)));
					if (upvalue.arg1().isnil()) break;
					String name = upvalue.arg1().tojstring();
					if (name.equals("_ENV") || name.startsWith("(") || name.startsWith("*")) continue;
					if (variables.size() < MAX_VARIABLES)
						add(variables, "闭包 · 栈" + frame, name, upvalue.arg(2), treeBudget);
					else omitted++;
				}
			}
		}
		LuaValue key = LuaValue.NIL;
		int scanned = 0;
		while (scanned++ < 2048) {
			Varargs pair = globals.next(key);
			key = pair.arg1();
			if (key.isnil()) break;
			if (key.type() != LuaValue.TSTRING) continue;
			String name = key.tojstring();
			LuaValue value = pair.arg(2);
			if (baseline.get(name) == value) continue;
			if (variables.size() < MAX_VARIABLES) add(variables, "全局", name, value, treeBudget);
			else omitted++;
		}
		result.add("variables", variables);
		result.addProperty("omitted", omitted);
		return result.toString();
	}

	static String inactive() {
		JsonObject result = new JsonObject();
		result.addProperty("running", false);
		result.add("variables", new JsonArray());
		return result.toString();
	}

	static String disabled(String script) {
		JsonObject result = new JsonObject();
		result.addProperty("running", true);
		result.addProperty("debugEnabled", false);
		result.addProperty("script", script);
		result.add("variables", new JsonArray());
		result.addProperty("omitted", 0);
		return result.toString();
	}

	static String waiting(String script) {
		JsonObject result = new JsonObject();
		result.addProperty("running", true);
		result.addProperty("script", script);
		result.add("variables", new JsonArray());
		result.addProperty("omitted", 0);
		return result.toString();
	}

	/** Retains one bounded latest sample per listener, so a later event cannot erase another listener's variables. */
	static final class History {
		private JsonObject main;
		private JsonObject globals;
		private final Map<Integer, ListenerSample> listeners = new LinkedHashMap<>();

		void recordMain(String snapshot) {
			main = JsonParser.parseString(snapshot).getAsJsonObject();
			globals = main;
		}

		void recordIdle(String snapshot) {
			globals = JsonParser.parseString(snapshot).getAsJsonObject();
		}

		void recordListener(int id, String eventName, String snapshot) {
			JsonObject sample = JsonParser.parseString(snapshot).getAsJsonObject();
			listeners.put(id, new ListenerSample(eventName, sample));
			globals = sample;
			while (listeners.size() > MAX_HISTORY_CONTEXTS)
				listeners.remove(listeners.keySet().iterator().next());
		}

		void removeListener(int id) { listeners.remove(id); }

		String encoded(String script) {
			JsonObject result = new JsonObject();
			result.addProperty("running", true);
			result.addProperty("script", script);
			JsonArray combined = new JsonArray();
			Map<String, Integer> duplicateNames = new HashMap<>();
			int[] budget = {0, 0}; // characters, omitted variables
			append(combined, main, "主脚本", false, duplicateNames, budget);
			for (Map.Entry<Integer, ListenerSample> entry : listeners.entrySet())
				append(combined, entry.getValue().snapshot(),
						"监听 #" + entry.getKey() + " · " + entry.getValue().eventName(),
						false, duplicateNames, budget);
			append(combined, globals, "", true, duplicateNames, budget);
			result.add("variables", combined);
			result.addProperty("omitted", budget[1]);
			return result.toString();
		}

		private static void append(JsonArray target, JsonObject sample, String context, boolean globalsOnly,
				Map<String, Integer> duplicateNames, int[] budget) {
			if (sample == null) return;
			budget[1] += sample.has("omitted") ? Math.max(0, sample.get("omitted").getAsInt()) : 0;
			for (var element : sample.getAsJsonArray("variables")) {
				JsonObject original = element.getAsJsonObject();
				String scope = original.get("scope").getAsString();
				if (globalsOnly != scope.equals("全局")) continue;
				JsonObject variable = original.deepCopy();
				if (!globalsOnly) variable.addProperty("scope", context + " · " + scope);
				String key = variable.get("scope").getAsString() + '\u0000' + variable.get("name").getAsString();
				int occurrence = duplicateNames.merge(key, 1, Integer::sum);
				if (occurrence > 1) variable.addProperty("name", variable.get("name").getAsString() + " [#" + occurrence + "]");
				int length = variable.toString().length();
				if (target.size() >= MAX_VARIABLES || budget[0] + length > MAX_HISTORY_CHARS) {
					budget[1]++;
					continue;
				}
				budget[0] += length;
				target.add(variable);
			}
		}

		private record ListenerSample(String eventName, JsonObject snapshot) { }
	}

	private static void add(JsonArray variables, String scope, String name, LuaValue value,
			TreeBudget budget) {
		JsonObject entry = new JsonObject();
		entry.addProperty("scope", scope);
		entry.addProperty("name", abbreviate(name, 96));
		entry.addProperty("type", value.typename());
		boolean tree = !scope.equals("全局") && value.istable();
		entry.addProperty("value", tree ? "{…}" : describe(value, 0, new IdentityHashMap<>()));
		if (tree) entry.add("children", children(value, 0, new IdentityHashMap<>(), budget));
		variables.add(entry);
	}

	private static JsonArray children(LuaValue table, int depth,
			IdentityHashMap<LuaValue, Boolean> seen, TreeBudget budget) {
		JsonArray result = new JsonArray();
		if (depth >= MAX_TREE_DEPTH || budget.remaining <= 0
				|| seen.put(table, Boolean.TRUE) != null) return result;
		LuaValue key = LuaValue.NIL;
		int count = 0;
		try {
			while (count < MAX_TREE_CHILDREN && budget.remaining > 0) {
				Varargs pair = table.next(key);
				key = pair.arg1();
				if (key.isnil()) break;
				count++;
				budget.remaining--;
				LuaValue child = pair.arg(2);
				JsonObject node = new JsonObject();
				node.addProperty("name", key.type() == LuaValue.TSTRING
						? abbreviate(key.tojstring(), 80)
						: key.type() == LuaValue.TNUMBER ? "[" + key.tojstring() + "]"
						: "[" + key.typename() + "]");
				boolean cycle = child.istable() && seen.containsKey(child);
				node.addProperty("type", child.typename());
				node.addProperty("value", cycle ? "{循环引用}"
						: child.istable() ? "{…}" : describe(child, 0, new IdentityHashMap<>()));
				if (child.istable() && !cycle && depth + 1 < MAX_TREE_DEPTH && budget.remaining > 0)
					node.add("children", children(child, depth + 1, seen, budget));
				result.add(node);
			}
			boolean more = budget.remaining == 0;
			if (!more && count == MAX_TREE_CHILDREN) more = !table.next(key).arg1().isnil();
			if (more) {
				JsonObject marker = new JsonObject();
				marker.addProperty("name", "…");
				marker.addProperty("type", "notice");
				marker.addProperty("value", "更多字段未显示");
				result.add(marker);
			}
		} catch (RuntimeException error) {
			JsonObject failure = new JsonObject();
			failure.addProperty("name", "…");
			failure.addProperty("type", "notice");
			failure.addProperty("value", "无法读取表字段");
			result.add(failure);
		} finally {
			seen.remove(table);
		}
		return result;
	}

	private static final class TreeBudget {
		private int remaining;
		private TreeBudget(int remaining) { this.remaining = remaining; }
	}

	private static String describe(LuaValue value, int depth, IdentityHashMap<LuaValue, Boolean> seen) {
		if (value.isnil()) return "nil";
		if (value.type() == LuaValue.TSTRING) return quote(value.tojstring());
		if (value.type() == LuaValue.TNUMBER || value.type() == LuaValue.TBOOLEAN)
			return value.tojstring();
		if (!value.istable()) return "<" + value.typename() + ">";
		if (depth >= 2) return "{…}";
		if (seen.put(value, Boolean.TRUE) != null) return "{循环引用}";
		StringBuilder text = new StringBuilder("{");
		LuaValue key = LuaValue.NIL;
		int count = 0;
		try {
			while (count < MAX_TABLE_FIELDS) {
				Varargs pair = value.next(key);
				key = pair.arg1();
				if (key.isnil()) break;
				if (count++ > 0) text.append(", ");
				text.append(key.type() == LuaValue.TSTRING
						? abbreviate(key.tojstring(), 48) : "[" + key.typename() + "]");
				text.append('=').append(describe(pair.arg(2), depth + 1, seen));
				if (text.length() > 240) { text.append("…"); break; }
			}
			if (count == MAX_TABLE_FIELDS) text.append(", …");
		} catch (RuntimeException ignored) {
			text.append("…");
		} finally {
			seen.remove(value);
		}
		return text.append('}').toString();
	}

	private static String quote(String source) {
		String safe = abbreviate(source, 160).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
				.replace("\t", "\\t").replace("\"", "\\\"");
		return "\"" + safe + "\"";
	}

	private static String abbreviate(String value, int maximum) {
		return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
	}
}
