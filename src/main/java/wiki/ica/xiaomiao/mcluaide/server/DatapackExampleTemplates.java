package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Provides editable examples for the server's actual data-pack format, preferring vanilla resources. */
final class DatapackExampleTemplates {
	private static final int MAX_EXAMPLE_BYTES = 16_384;
	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();

	private DatapackExampleTemplates() { }

	static String example(MinecraftServer server, String targetPath) throws IOException {
		DatapackScaffoldCatalog.Category category = categoryForFile(targetPath);
		if (category == null || !category.textFile())
			throw new IOException("该路径没有可用的数据包示例模板");
		if (category.path().equals("dimension")) {
			try {
				return DatapackDimensionEditorData.read(server, "minecraft:overworld");
			} catch (RuntimeException error) {
				throw new IOException("无法从当前游戏读取可用的维度模板：" + error.getMessage(), error);
			}
		}
		String curated = curated(category.path());
		if (curated != null) return curated;
		String vanilla = vanillaExample(server, category.path());
		String source = vanilla == null ? category.template() : vanilla;
		return category.path().startsWith("tags/") ? tagExample(source) : source;
	}

	static DatapackScaffoldCatalog.Category categoryForFile(String path) {
		if (path == null) return null;
		String[] parts = path.split("/", 4);
		if (parts.length < 4 || !parts[1].equals("data")) return null;
		int lastSlash = parts[3].lastIndexOf('/');
		if (lastSlash < 0) return null;
		DatapackScaffoldCatalog.Category category = DatapackScaffoldCatalog.fileCategory(
				parts[3].substring(0, lastSlash));
		return category != null && path.endsWith(category.extension()) ? category : null;
	}

	static String curated(String category) {
		return switch (category) {
			case "advancement" -> """
					{
					  "display": {
					    "icon": {"id": "minecraft:book"},
					    "title": {"text": "示例进度"},
					    "description": {"text": "在这里填写进度描述"}
					  },
					  "criteria": {
					    "example": {"trigger": "minecraft:impossible"}
					  },
					  "requirements": [["example"]]
					}
					""";
			case "recipe" -> """
					{
					  "type": "minecraft:crafting_shapeless",
					  "category": "misc",
					  "ingredients": ["minecraft:acacia_planks"],
					  "result": {"id": "minecraft:acacia_button", "count": 1}
					}
					""";
			case "loot_table" -> """
					{
					  "type": "minecraft:generic",
					  "pools": [
					    {
					      "rolls": 1,
					      "entries": [
					        {"type": "minecraft:item", "name": "minecraft:apple"}
					      ]
					    }
					  ]
					}
					""";
			case "predicate" -> """
					{
					  "condition": "minecraft:random_chance",
					  "chance": 0.5
					}
					""";
			case "item_modifier" -> """
					{
					  "function": "minecraft:set_count",
					  "count": 2
					}
					""";
			case "function" -> """
					# 示例函数：每行一条命令，不要写开头的 /
					say Hello from data pack!
					""";
			case "dimension" -> """
					{
					  "type": "minecraft:overworld",
					  "generator": {
					    "type": "minecraft:flat",
					    "settings": {
					      "biome": "minecraft:plains",
					      "features": false,
					      "lakes": false,
					      "layers": [
					        {"block": "minecraft:bedrock", "height": 1},
					        {"block": "minecraft:stone", "height": 3},
					        {"block": "minecraft:grass_block", "height": 1}
					      ],
					      "structure_overrides": []
					    }
					  }
					}
					""";
			default -> null;
		};
	}

	private static String vanillaExample(MinecraftServer server, String categoryPath) {
		String prefix = categoryPath + "/";
		try {
			Map<Identifier, List<Resource>> resources = server.getResourceManager().listResourceStacks(
					categoryPath, id -> id.getNamespace().equals("minecraft")
							&& id.getPath().startsWith(prefix) && id.getPath().endsWith(".json"));
			return resources.entrySet().stream()
					.sorted(Comparator.<Map.Entry<Identifier, List<Resource>>>comparingInt(
							entry -> entry.getKey().getPath().length())
							.thenComparing(entry -> entry.getKey().getPath()))
					.map(entry -> readVanilla(entry.getValue()))
					.filter(value -> value != null && !value.isBlank())
					.findFirst().orElse(null);
		} catch (RuntimeException error) {
			return null;
		}
	}

	private static String tagExample(String source) {
		try {
			JsonObject tag = JsonParser.parseString(source).getAsJsonObject();
			if (!tag.has("replace")) tag.addProperty("replace", false);
			if (!tag.has("values")) tag.add("values", new JsonArray());
			return PRETTY_JSON.toJson(tag) + "\n";
		} catch (RuntimeException error) {
			return "{\n  \"replace\": false,\n  \"values\": []\n}\n";
		}
	}

	private static String readVanilla(List<Resource> stack) {
		if (stack == null || stack.isEmpty()) return null;
		Resource resource = stack.stream().filter(value -> "vanilla".equals(value.sourcePackId()))
				.findFirst().orElse(stack.get(0));
		try (InputStream stream = resource.open()) {
			byte[] bytes = stream.readNBytes(MAX_EXAMPLE_BYTES + 1);
			if (bytes.length > MAX_EXAMPLE_BYTES) return null;
			String source = new String(bytes, StandardCharsets.UTF_8);
			return source.endsWith("\n") ? source : source + "\n";
		} catch (IOException error) {
			return null;
		}
	}
}
