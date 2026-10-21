package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;
import wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Live examples and observed editable fields from the active server data packs. */
final class DatapackJsonEditorData {
	private static final Gson STRICT = new GsonBuilder().setStrictness(Strictness.STRICT).create();
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_SOURCE_BYTES = 300_000;
	private static final int MAX_OPTIONS = 1_800;
	private static final int MAX_SCHEMA_SOURCES = 32;
	private static final int MAX_SCHEMA_NODES = 650;
	private static final int MAX_SCHEMA_CHARS = 140_000;

	private DatapackJsonEditorData() { }

	static String categories(MinecraftServer server) {
		Set<String> paths = new TreeSet<>();
		// The game does not allow listing the pack root (it logs "Invalid path ''"), so list the known datapack directories instead.
		for (String root : LuaPackIdCatalog.directories(server)) {
			try {
				server.getResourceManager().listResources(root, id -> id.getPath().endsWith(".json"))
						.keySet().forEach(id -> {
							String path = id.getPath();
							int slash = path.lastIndexOf('/');
							if (slash < 1) return;
							String directory = path.substring(0, slash);
							String category = DatapackScaffoldCatalog.jsonType(directory);
							if (category.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*") && paths.size() < 256)
								paths.add(category);
						});
			} catch (RuntimeException ignored) {
				// A directory that cannot be listed only means fewer discovered categories; the built-in ones remain.
			}
		}
		try {
			for (String file : DatapackWorkspaceManager.list(server)) {
				if (!file.startsWith("F:")) continue;
				Identifier id = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.substring(2), "data");
				if (id == null || !id.getPath().endsWith(".json")) continue;
				int slash = id.getPath().lastIndexOf('/');
				if (slash > 0) paths.add(DatapackScaffoldCatalog.jsonType(id.getPath().substring(0, slash)));
			}
		} catch (IOException ignored) { }
		JsonArray array = new JsonArray();
		paths.forEach(array::add);
		return array.toString();
	}

	static String options(MinecraftServer server, String category) throws IOException {
		checkCategory(category);
		List<String> ids = sourceIds(server, category);
		JsonArray entries = new JsonArray();
		ids.stream().limit(MAX_OPTIONS).forEach(entries::add);
		JsonObject result = new JsonObject();
		result.add("sources", entries);
		result.addProperty("total", ids.size());
		result.addProperty("observed", Math.min(MAX_SCHEMA_SOURCES, ids.size()));
		ComponentTreeSchema.Node schema = observedSchema(server, category, ids);
		JsonObject schemaJson = schema.encode();
		result.add("schema", schemaJson);
		String encoded = result.toString();
		if (schemaJson.toString().length() > MAX_SCHEMA_CHARS) {
			result.add("schema", emptySchema().encode());
			result.addProperty("schemaLimited", true);
			encoded = result.toString();
		}
		while (encoded.length() > 480_000 && !entries.isEmpty()) {
			entries.remove(entries.size() - 1);
			result.addProperty("sourcesLimited", true);
			encoded = result.toString();
		}
		if (ids.size() > entries.size()) result.addProperty("sourcesLimited", true);
		return encoded;
	}

	static String source(MinecraftServer server, String category, String id) throws IOException {
		checkCategory(category);
		Identifier sourceId = Identifier.tryParse(id);
		if (sourceId == null || sourceId.getPath().isBlank()) throw new IOException("无效示例 ID：" + id);
		Identifier resourceId = Identifier.fromNamespaceAndPath(sourceId.getNamespace(),
				category + "/" + sourceId.getPath() + ".json");
		Resource resource = server.getResourceManager().getResource(resourceId).orElse(null);
		if (resource == null) {
			for (String file : DatapackWorkspaceManager.list(server)) {
				if (!file.startsWith("F:")) continue;
				Identifier workspaceId = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.substring(2), "data");
				if (resourceId.equals(workspaceId)) return PRETTY.toJson(JsonParser.parseString(
						DatapackWorkspaceManager.read(server, file.substring(2)))) + "\n";
			}
			if (category.equals("dimension")) return DatapackDimensionEditorData.read(server, id);
			throw new IOException("游戏和编辑目录中都没有这个 JSON 示例：" + id);
		}
		JsonElement parsed = read(resource);
		return PRETTY.toJson(parsed) + "\n";
	}

	static String validate(MinecraftServer server, String category, String source) {
		checkCategory(category);
		if (source == null || source.length() > MAX_SOURCE_BYTES) throw new IllegalArgumentException("JSON 内容过大");
		JsonElement json = STRICT.fromJson(source, JsonElement.class);
		if (json == null || json.isJsonNull()) throw new IllegalArgumentException("JSON 内容不能为空");
		if (category.equals("dimension")) return DatapackDimensionEditorData.validate(server, source);
		if (category.startsWith("tags/")) {
			if (!json.isJsonObject() || !json.getAsJsonObject().has("values")
					|| !json.getAsJsonObject().get("values").isJsonArray())
				throw new IllegalArgumentException("标签 JSON 必须包含 values 数组");
		}
		return "JSON 语法有效；此类型未执行游戏 Codec 语义校验";
	}

	private static ComponentTreeSchema.Node observedSchema(MinecraftServer server, String category, List<String> ids) {
		ComponentTreeSchema.Node schema = emptySchema();
		Budget budget = new Budget();
		if (category.equals("pack.mcmeta"))
			return merge(schema, JsonParser.parseString("{\"pack\":{\"description\":\"\"}}"), category, List.of(), 0, budget);
		if (ids.isEmpty()) {
			String template = DatapackExampleTemplates.curated(category);
			if (template != null) return merge(schema, JsonParser.parseString(template), category, List.of(), 0, budget);
		}
		for (String id : schemaSampleIds(ids)) {
			try {
				JsonElement sample = JsonParser.parseString(source(server, category, id));
				schema = merge(schema, sample, category, List.of(), 0, budget);
			} catch (RuntimeException | IOException ignored) {
				// A bad pack entry is not allowed to prevent editing other valid samples.
			}
			if (budget.nodes >= MAX_SCHEMA_NODES) break;
		}
		return schema;
	}

	private static List<String> schemaSampleIds(List<String> ids) {
		if (ids.size() <= MAX_SCHEMA_SOURCES) return ids;
		Set<String> selected = new LinkedHashSet<>();
		for (int index = 0; index < MAX_SCHEMA_SOURCES / 2; index++) selected.add(ids.get(index));
		for (int index = 0; index < MAX_SCHEMA_SOURCES / 2; index++)
			selected.add(ids.get((int) ((long) index * ids.size() / (MAX_SCHEMA_SOURCES / 2))));
		return List.copyOf(selected);
	}

	private static ComponentTreeSchema.Node merge(ComponentTreeSchema.Node original, JsonElement sample,
			String category, List<String> path, int depth, Budget budget) {
		if (sample == null || sample.isJsonNull() || depth > 13 || budget.nodes >= MAX_SCHEMA_NODES) return original;
		if (sample.isJsonObject()) {
			Map<String, ComponentTreeSchema.Node> fields = new LinkedHashMap<>(original.fields());
			for (Map.Entry<String, JsonElement> entry : sample.getAsJsonObject().entrySet()) {
				if (fields.size() >= 50 || budget.nodes >= MAX_SCHEMA_NODES) break;
				String key = entry.getKey();
				List<String> childPath = new ArrayList<>(path);
				childPath.add(key);
				ComponentTreeSchema.Node previous = fields.getOrDefault(key, blankNode(key));
				fields.put(key, merge(previous, entry.getValue(), category, childPath, depth + 1, budget));
			}
			return new ComponentTreeSchema.Node(original.label(), "object", new JsonObject(), fields,
					null, List.of(), "", true);
		}
		if (sample.isJsonArray()) {
			ComponentTreeSchema.Node element = original.element() == null ? blankNode("条目") : original.element();
			int inspected = 0;
			for (JsonElement child : sample.getAsJsonArray()) {
				element = merge(element, child, category, append(path, "*"), depth + 1, budget);
				if (++inspected >= 3 || budget.nodes >= MAX_SCHEMA_NODES) break;
			}
			return new ComponentTreeSchema.Node(original.label(), "array", new JsonArray(), Map.of(),
					element, List.of(), "", true);
		}
		budget.nodes++;
		JsonPrimitive primitive = sample.getAsJsonPrimitive();
		String kind = primitive.isBoolean() ? "boolean" : primitive.isNumber() ? "number" : "string";
		List<String> choices = new ArrayList<>(original.choices());
		if (primitive.isString() && choices.size() < 16 && !choices.contains(primitive.getAsString()))
			choices.add(primitive.getAsString());
		String registry = primitive.isString() ? registryFor(category, path) : "";
		return new ComponentTreeSchema.Node(original.label(), kind,
				original.seed().isJsonNull() ? primitive.deepCopy() : original.value(), Map.of(), null,
				choices, registry, false);
	}

	private static String registryFor(String category, List<String> path) {
		if (path.isEmpty()) return "";
		String key = path.getLast().toLowerCase(java.util.Locale.ROOT);
		if (category.startsWith("tags/") && path.getFirst().equals("values")
				&& (path.size() == 2 || path.size() == 3 && key.equals("id"))) {
			return "minecraft:" + category.substring("tags/".length());
		}
		if (category.equals("dimension")) {
			if (path.equals(List.of("type"))) return "minecraft:dimension_type";
			if (path.equals(List.of("generator", "settings"))) return "minecraft:worldgen/noise_settings";
		}
		if (key.equals("id") && (category.equals("recipe") && path.contains("result")
				|| category.equals("advancement") && path.contains("icon"))) return "minecraft:item";
		return switch (key) {
			case "item", "items", "ingredient" -> "minecraft:item";
			case "result" -> category.equals("recipe") ? "minecraft:item" : "";
			case "block", "blocks" -> "minecraft:block";
			case "entity", "entity_type" -> "minecraft:entity_type";
			case "biome" -> "minecraft:worldgen/biome";
			case "effect", "mob_effect" -> "minecraft:mob_effect";
			case "enchantment" -> "minecraft:enchantment";
			case "sound" -> "minecraft:sound_event";
			case "structure" -> "minecraft:worldgen/structure";
			case "structure_set" -> "minecraft:worldgen/structure_set";
			case "configured_feature" -> "minecraft:worldgen/configured_feature";
			case "placed_feature" -> "minecraft:worldgen/placed_feature";
			case "loot_table" -> "minecraft:loot_table";
			case "type" -> category.equals("recipe") ? "minecraft:recipe_serializer" : "";
			default -> "";
		};
	}

	private static List<String> sourceIds(MinecraftServer server, String category) {
		if (category.equals("pack.mcmeta")) return List.of();
		String prefix = category + "/";
		Map<Identifier, Resource> found = server.getResourceManager().listResources(category,
				id -> id.getPath().startsWith(prefix) && id.getPath().endsWith(".json"));
		Set<String> ids = new LinkedHashSet<>();
		if (category.equals("dimension")) server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)
				.listElementIds().forEach(key -> ids.add(key.identifier().toString()));
		found.keySet().stream().sorted(Comparator.comparing(Identifier::toString)).forEach(id -> {
			String path = id.getPath().substring(prefix.length(), id.getPath().length() - ".json".length());
			if (!path.isBlank()) ids.add(id.getNamespace() + ":" + path);
		});
		try {
			for (String file : DatapackWorkspaceManager.list(server)) {
				if (!file.startsWith("F:")) continue;
				Identifier physical = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.substring(2), "data");
				if (physical == null) continue;
				String id = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.logical(physical, category, ".json", false);
				if (id != null) ids.add(id);
			}
		} catch (IOException ignored) { }
		return ids.stream().sorted().toList();
	}

	private static JsonElement read(Resource resource) throws IOException {
		try (InputStream stream = resource.open()) {
			byte[] data = stream.readNBytes(MAX_SOURCE_BYTES + 1);
			if (data.length > MAX_SOURCE_BYTES) throw new IOException("示例 JSON 超过大小限制");
			JsonElement json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
			if (json == null || json.isJsonNull()) throw new IOException("示例 JSON 为空");
			return json;
		}
	}

	private static void checkCategory(String category) {
		if (category == null || !category.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")
				|| category.contains("..") || category.length() > 120)
			throw new IllegalArgumentException("无效数据包 JSON 类型");
	}

	private static ComponentTreeSchema.Node emptySchema() {
		return new ComponentTreeSchema.Node("数据包 JSON", "object", new JsonObject(), Map.of(),
				null, List.of(), "", true);
	}

	private static ComponentTreeSchema.Node blankNode(String label) {
		return new ComponentTreeSchema.Node(label, "unknown", JsonNull.INSTANCE, Map.of(),
				null, List.of(), "", true);
	}

	private static List<String> append(List<String> path, String part) {
		List<String> result = new ArrayList<>(path);
		result.add(part);
		return result;
	}

	private static final class Budget {
		private int nodes;
	}
}
