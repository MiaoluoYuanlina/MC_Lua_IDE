package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Decoded server snapshot used by the item-stack component editor. */
record LuaComponentEditorData(String kind, String requestedId, String itemId, String name,
		String sourceHint, List<ComponentInfo> components) {
	static LuaComponentEditorData decode(String encoded) {
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		List<ComponentInfo> components = new ArrayList<>();
		for (JsonElement raw : root.getAsJsonArray("components")) {
			JsonObject value = raw.getAsJsonObject();
			List<IdHint> idHints = new ArrayList<>();
			if (value.has("idHints")) {
				for (JsonElement encodedHint : value.getAsJsonArray("idHints")) {
					JsonObject hint = encodedHint.getAsJsonObject();
					idHints.add(new IdHint(hint.get("path").getAsString(), hint.get("target").getAsString(),
							hint.get("registry").getAsString(), hint.get("label").getAsString(),
							hint.get("description").getAsString()));
				}
			}
			components.add(new ComponentInfo(
					value.get("id").getAsString(), value.get("label").getAsString(),
					value.get("description").getAsString(), value.get("editor").getAsString(),
					value.get("relevance").getAsInt(), value.get("default").deepCopy(), List.copyOf(idHints)));
		}
		return new LuaComponentEditorData(root.get("kind").getAsString(),
				root.get("requestedId").getAsString(), root.get("itemId").getAsString(),
				root.get("name").getAsString(), root.get("sourceHint").getAsString(),
				List.copyOf(components));
	}

	static JsonObject patch(Map<String, JsonElement> values) {
		JsonObject patch = new JsonObject();
		values.forEach((id, value) -> patch.add(id, value.deepCopy()));
		return patch;
	}

	static String luaComponents(JsonObject patch) {
		return luaValue(patch, 0);
	}

	static String luaStack(String itemId, JsonObject patch) {
		return "{\n    id = " + quote(itemId) + ",\n    count = 1,\n    components = "
				+ indentFollowing(luaComponents(patch), "    ") + "\n}";
	}

	static String luaEntity(String entityType, JsonObject patch) {
		return "{\n    type = " + quote(entityType) + ",\n    components = "
				+ indentFollowing(luaComponents(patch), "    ") + "\n}";
	}

	private static String luaValue(JsonElement value, int depth) {
		if (value == null || value.isJsonNull()) return "nil";
		if (value.isJsonPrimitive()) {
			var primitive = value.getAsJsonPrimitive();
			if (primitive.isString()) return quote(primitive.getAsString());
			return primitive.toString();
		}
		String indent = "    ".repeat(depth);
		String childIndent = "    ".repeat(depth + 1);
		if (value.isJsonArray()) {
			if (value.getAsJsonArray().isEmpty()) return "{}";
			StringBuilder result = new StringBuilder("{\n");
			for (JsonElement child : value.getAsJsonArray()) {
				result.append(childIndent).append(luaValue(child, depth + 1)).append(",\n");
			}
			return result.append(indent).append('}').toString();
		}
		JsonObject object = value.getAsJsonObject();
		if (object.isEmpty()) return "{}";
		StringBuilder result = new StringBuilder("{\n");
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			result.append(childIndent).append('[').append(quote(entry.getKey())).append("] = ")
					.append(luaValue(entry.getValue(), depth + 1)).append(",\n");
		}
		return result.append(indent).append('}').toString();
	}

	private static String quote(String value) {
		return new com.google.gson.JsonPrimitive(value).toString();
	}

	private static String indentFollowing(String value, String indent) {
		return value.replace("\n", "\n" + indent);
	}

	record ComponentInfo(String id, String label, String description, String editor,
			int relevance, JsonElement defaultValue, List<IdHint> idHints) {
		String searchable() {
			return (id + " " + label + " " + description + " "
					+ ChineseSearchIndex.initials(label + description)).toLowerCase(java.util.Locale.ROOT);
		}
	}

	record IdHint(String path, String target, String registry, String label, String description) {
		boolean keyTarget() {
			return target.equals("key");
		}
	}
}
