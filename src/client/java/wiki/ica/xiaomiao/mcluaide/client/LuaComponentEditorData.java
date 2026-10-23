package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Decoded server snapshot for item, placed-block and entity configuration editors. */
record LuaComponentEditorData(String kind, String requestedId, String itemId, String name,
		String sourceHint, List<ComponentInfo> components, Map<String, List<IdHint>> componentIdHints) {
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
					value.get("relevance").getAsInt(), value.get("default").deepCopy(), List.copyOf(idHints),
					value.has("schema") ? ComponentTreeSchema.Node.decode(value.getAsJsonObject("schema"))
							: ComponentTreeSchema.component(value.get("id").getAsString(), value.get("default"))));
		}
		Map<String, List<IdHint>> catalog = new java.util.HashMap<>();
		for (ComponentInfo info : components) catalog.put(info.id(), info.idHints());
		if (root.has("componentIdHints")) {
			root.getAsJsonObject("componentIdHints").entrySet().forEach(entry -> {
				List<IdHint> hints = new ArrayList<>();
				for (JsonElement raw : entry.getValue().getAsJsonArray()) {
					JsonObject hint = raw.getAsJsonObject();
					hints.add(new IdHint(hint.get("path").getAsString(), hint.get("target").getAsString(),
							hint.get("registry").getAsString(), hint.get("label").getAsString(),
							hint.get("description").getAsString()));
				}
				catalog.put(entry.getKey(), List.copyOf(hints));
			});
		}
		return new LuaComponentEditorData(root.get("kind").getAsString(),
				root.get("requestedId").getAsString(), root.get("itemId").getAsString(),
				root.get("name").getAsString(), root.get("sourceHint").getAsString(),
				List.copyOf(components), Map.copyOf(catalog));
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
		return "{\n    type = " + quote(entityType) + ",\n    config = "
				+ indentFollowing(luaComponents(patch), "    ") + "\n}";
	}

	static String luaBlock(String blockId, JsonObject config) {
		return "{\n    id = " + quote(blockId) + ",\n    config = "
				+ indentFollowing(luaComponents(config), "    ") + "\n}";
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

	/** Item components in command syntax: {@code [minecraft:custom_name={text:"x"},!minecraft:food]}. */
	static String commandComponents(JsonObject patch) {
		StringBuilder result = new StringBuilder("[");
		for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
			if (result.length() > 1) result.append(',');
			if (entry.getValue() == null || entry.getValue().isJsonNull()) result.append('!').append(entry.getKey());
			else result.append(entry.getKey()).append('=').append(snbt(entry.getValue()));
		}
		return result.append(']').toString();
	}

	/** Block state properties plus block entity data in command syntax: {@code [facing=north]{Lock:"key"}}. */
	static String commandBlock(String blockId, JsonObject config, boolean withId) {
		StringBuilder result = new StringBuilder(withId ? blockId : "");
		if (config.has("states") && config.get("states").isJsonObject() && !config.getAsJsonObject("states").isEmpty()) {
			result.append('[');
			boolean first = true;
			for (Map.Entry<String, JsonElement> state : config.getAsJsonObject("states").entrySet()) {
				if (!first) result.append(',');
				first = false;
				JsonElement value = state.getValue();
				result.append(state.getKey()).append('=')
						.append(value.isJsonPrimitive() ? value.getAsJsonPrimitive().getAsString() : value.toString());
			}
			result.append(']');
		}
		if (config.has("blockEntityData") && config.get("blockEntityData").isJsonObject()
				&& !config.getAsJsonObject("blockEntityData").isEmpty()) result.append(snbt(config.get("blockEntityData")));
		return result.toString();
	}

	/** Entity configuration as an NBT compound for summon-style commands. */
	static String commandEntity(JsonObject patch) { return snbt(patch); }

	static String snbt(JsonElement value) {
		if (value == null || value.isJsonNull()) return "{}";
		if (value.isJsonPrimitive()) {
			var primitive = value.getAsJsonPrimitive();
			if (primitive.isString()) return snbtString(primitive.getAsString());
			return primitive.toString();
		}
		if (value.isJsonArray()) {
			StringBuilder result = new StringBuilder("[");
			for (JsonElement child : value.getAsJsonArray()) {
				if (result.length() > 1) result.append(',');
				result.append(snbt(child));
			}
			return result.append(']').toString();
		}
		StringBuilder result = new StringBuilder("{");
		for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
			if (result.length() > 1) result.append(',');
			String key = entry.getKey();
			result.append(key.matches("[A-Za-z0-9_.+-]+") ? key : snbtString(key)).append(':').append(snbt(entry.getValue()));
		}
		return result.append('}').toString();
	}

	private static String snbtString(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static String quote(String value) {
		return new com.google.gson.JsonPrimitive(value).toString();
	}

	private static String indentFollowing(String value, String indent) {
		return value.replace("\n", "\n" + indent);
	}

	record ComponentInfo(String id, String label, String description, String editor,
			int relevance, JsonElement defaultValue, List<IdHint> idHints, ComponentTreeSchema.Node schema) {
		String searchable() {
			StringBuilder details = new StringBuilder(label).append(' ').append(description);
			schema.fields().forEach((key, node) -> {
				details.append(' ').append(key).append(' ').append(node.label());
				node.fields().forEach((childKey, child) -> details.append(' ').append(childKey)
						.append(' ').append(child.label()));
			});
			return (id + " " + details + " " + LuaUiText.searchInitials(details.toString()))
					.toLowerCase(java.util.Locale.ROOT);
		}
	}

	record IdHint(String path, String target, String registry, String label, String description) {
		boolean keyTarget() {
			return target.equals("key");
		}
	}
}
