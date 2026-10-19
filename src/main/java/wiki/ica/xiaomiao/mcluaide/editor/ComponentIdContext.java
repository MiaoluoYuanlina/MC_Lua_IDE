package wiki.ica.xiaomiao.mcluaide.editor;

import com.google.gson.JsonElement;
import java.util.List;
import java.util.Set;

/** Resolves a tree field relative to its innermost component, not its outer container. */
public record ComponentIdContext(String componentId, List<String> path, JsonElement root) {
	public ComponentIdContext {
		path = List.copyOf(path);
	}

	public static ComponentIdContext resolve(String componentId, JsonElement root, List<String> path) {
		String active = componentId;
		JsonElement activeRoot = root;
		JsonElement node = root;
		int start = 0;
		for (int i = 0; i < path.size(); i++) {
			String part = path.get(i);
			node = child(node, part);
			if (i > 0 && path.get(i - 1).equals("components") && !part.startsWith("!")) {
				active = part.contains(":") ? part : "minecraft:" + part;
				activeRoot = node;
				start = i + 1;
			}
		}
		return new ComponentIdContext(active, path.subList(start, path.size()), activeRoot);
	}

	public String componentPath() {
		return componentId.substring(componentId.indexOf(':') + 1);
	}

	public String field() {
		for (int i = path.size() - 1; i >= 0; i--) {
			if (!path.get(i).matches("[0-9]+")) return path.get(i).toLowerCase(java.util.Locale.ROOT);
		}
		return "";
	}

	public boolean matches(String pattern) {
		String[] parts = pattern.isEmpty() ? new String[0] : pattern.substring(1).split("/", -1);
		if (parts.length != path.size()) return false;
		for (int i = 0; i < parts.length; i++) {
			String expected = parts[i].replace("~1", "/").replace("~0", "~");
			if (!expected.equals("*") && !expected.equals(path.get(i))) return false;
		}
		return true;
	}

	/** Only infer ambiguous 'id' from a nearby structural owner; never from an outer bag. */
	public String structuralRegistry() {
		if (path.isEmpty()) return null;
		String field = path.getLast().toLowerCase(java.util.Locale.ROOT);
		if (!field.equals("id") && !field.equals("name")) return null;
		JsonElement parent = root;
		for (int i = 0; i + 1 < path.size(); i++) parent = child(parent, path.get(i));
		String owner = path.size() < 2 ? componentPath() : path.get(path.size() - 2).toLowerCase(java.util.Locale.ROOT);
		if (owner.matches("[0-9]+") && path.size() > 2) owner = path.get(path.size() - 3).toLowerCase(java.util.Locale.ROOT);
		if (field.equals("name")) return Set.of("blockstate", "block_state").contains(owner) ? "minecraft:block" : null;
		if (Set.of("effects", "custom_effects", "active_effects", "suspicious_stew_effects").contains(owner)) return "minecraft:mob_effect";
		if (Set.of("entity_data", "bucket_entity_data", "passengers", "entity").contains(owner)) return "minecraft:entity_type";
		if (owner.equals("block_entity_data")) return "minecraft:block_entity_type";
		if (Set.of("item", "items", "handitems", "armoritems", "inventory", "equipment", "saddle",
				"mainhand", "offhand", "head", "chest", "legs", "feet", "body",
				"bundle_contents", "charged_projectiles", "use_remainder", "convert_into").contains(owner)) return "minecraft:item";
		if (parent != null && parent.isJsonObject()
				&& (parent.getAsJsonObject().has("count") || parent.getAsJsonObject().has("Count"))) return "minecraft:item";
		return null;
	}

	private static JsonElement child(JsonElement node, String part) {
		if (node == null) return null;
		if (node.isJsonObject()) return node.getAsJsonObject().get(part);
		if (node.isJsonArray()) {
			try {
				int index = Integer.parseInt(part);
				return index >= 0 && index < node.getAsJsonArray().size() ? node.getAsJsonArray().get(index) : null;
			} catch (NumberFormatException ignored) { }
		}
		return null;
	}
}
