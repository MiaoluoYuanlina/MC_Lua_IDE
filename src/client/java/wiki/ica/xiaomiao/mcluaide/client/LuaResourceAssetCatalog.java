package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Searchable identifiers backed by the active client resource packs rather than a game registry. */
final class LuaResourceAssetCatalog {
	static final String ITEM_MODEL = "mc_lua_ide:item_model_asset";
	static final String EQUIPMENT = "mc_lua_ide:equipment_asset";
	static final String TOOLTIP_STYLE = "mc_lua_ide:tooltip_style_asset";
	static final String TEXTURE = "mc_lua_ide:texture_asset";
	static final String FONT = "mc_lua_ide:font_asset";
	static final String ATTRIBUTE_MODIFIER = "mc_lua_ide:attribute_modifier_id";

	private LuaResourceAssetCatalog() {
	}

	/** Returns {@code null} when the identifier names a normal server registry. */
	static List<LuaInsertCatalog.Entry> load(String catalogId, Minecraft minecraft) {
		ResourceManager resources = minecraft.getResourceManager();
		return switch (catalogId) {
			case ITEM_MODEL -> itemModels(resources);
			case EQUIPMENT -> scan(resources, "equipment", ".json", "装备模型资源");
			case TOOLTIP_STYLE -> tooltipStyles(resources);
			case TEXTURE -> scan(resources, "textures", ".png", "纹理资源");
			case FONT -> scan(resources, "font", ".json", "字体资源");
			case ATTRIBUTE_MODIFIER -> attributeModifiers();
			default -> null;
		};
	}

	static List<LuaRegistryCatalogData.RegistryType> types(Minecraft minecraft) {
		List<LuaRegistryCatalogData.RegistryType> result = new ArrayList<>();
		addType(result, minecraft, ITEM_MODEL, "物品模型资源 ID", "当前客户端资源包与 Mod 的 assets/*/items/*.json");
		addType(result, minecraft, EQUIPMENT, "装备模型资源 ID", "当前客户端资源包与 Mod 的 assets/*/equipment/*.json");
		addType(result, minecraft, TOOLTIP_STYLE, "提示框样式 ID", "成对的 tooltip/*_background 与 *_frame 精灵");
		addType(result, minecraft, TEXTURE, "纹理资源 ID", "当前客户端资源包与 Mod 的全部 PNG 纹理");
		addType(result, minecraft, FONT, "字体资源 ID", "当前客户端资源包与 Mod 的 assets/*/font/*.json");
		addType(result, minecraft, ATTRIBUTE_MODIFIER, "属性修饰符 ID",
				"从全部原版与 Mod 物品的默认组件收集到的属性修饰符 ID");
		return List.copyOf(result);
	}

	private static void addType(List<LuaRegistryCatalogData.RegistryType> result, Minecraft minecraft,
			String id, String label, String description) {
		List<LuaInsertCatalog.Entry> entries = load(id, minecraft);
		result.add(new LuaRegistryCatalogData.RegistryType(id, label, description, "client-resources",
				entries == null ? 0 : entries.size()));
	}

	private static List<LuaInsertCatalog.Entry> itemModels(ResourceManager resources) {
		List<LuaInsertCatalog.Entry> scanned = scan(resources, "items", ".json", "物品模型资源");
		List<LuaInsertCatalog.Entry> localized = new ArrayList<>(scanned.size());
		for (LuaInsertCatalog.Entry entry : scanned) {
			Identifier id = Identifier.tryParse(entry.value());
			String name = entry.name();
			if (id != null) {
				try {
					var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
					if (item != null) name = item.getName(item.getDefaultInstance()).getString();
				} catch (RuntimeException ignored) {
					// Keep the readable resource-path fallback for incomplete third-party models.
				}
			}
			localized.add(new LuaInsertCatalog.Entry(entry.value(), name, entry.namespace(), entry.description()));
		}
		return List.copyOf(localized);
	}

	private static List<LuaInsertCatalog.Entry> attributeModifiers() {
		Map<String, LuaInsertCatalog.Entry> result = new LinkedHashMap<>();
		for (var item : BuiltInRegistries.ITEM) {
			try {
				var stack = item.getDefaultInstance();
				var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
				if (modifiers == null) continue;
				String itemName = item.getName(stack).getString();
				for (var entry : modifiers.modifiers()) {
					Identifier id = entry.modifier().id();
					String value = id.toString();
					result.putIfAbsent(value, new LuaInsertCatalog.Entry(value,
							id.getPath().replace('_', ' '), id.getNamespace(),
							"默认用于：" + itemName));
				}
			} catch (RuntimeException ignored) {
				// One broken third-party default stack must not hide the other searchable IDs.
			}
		}
		return sorted(result);
	}

	private static List<LuaInsertCatalog.Entry> scan(ResourceManager resources, String directory,
			String suffix, String description) {
		Map<String, LuaInsertCatalog.Entry> result = new LinkedHashMap<>();
		String prefix = directory + "/";
		for (Identifier resource : resources.listResources(directory,
				id -> id.getPath().startsWith(prefix) && id.getPath().endsWith(suffix)).keySet()) {
			String path = resource.getPath();
			String logicalPath = path.substring(prefix.length(), path.length() - suffix.length());
			if (logicalPath.isBlank()) continue;
			String value = resource.getNamespace() + ":" + logicalPath;
			result.putIfAbsent(value, entry(value, resource.getNamespace(), logicalPath, description));
		}
		return sorted(result);
	}

	private static List<LuaInsertCatalog.Entry> tooltipStyles(ResourceManager resources) {
		Map<String, Integer> masks = new LinkedHashMap<>();
		String directory = "textures/gui/sprites/tooltip";
		String prefix = directory + "/";
		for (Identifier resource : resources.listResources(directory,
				id -> id.getPath().startsWith(prefix) && id.getPath().endsWith(".png")).keySet()) {
			String relative = resource.getPath().substring(prefix.length(), resource.getPath().length() - 4);
			int bit;
			String style;
			if (relative.endsWith("_background")) {
				style = relative.substring(0, relative.length() - "_background".length());
				bit = 1;
			} else if (relative.endsWith("_frame")) {
				style = relative.substring(0, relative.length() - "_frame".length());
				bit = 2;
			} else {
				continue;
			}
			if (style.isBlank()) continue;
			String value = resource.getNamespace() + ":" + style;
			masks.merge(value, bit, (left, right) -> left | right);
		}
		Map<String, LuaInsertCatalog.Entry> result = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> candidate : masks.entrySet()) {
			// A valid custom tooltip style needs both sprites. Ignore half-installed resources.
			if (candidate.getValue() != 3) continue;
			Identifier id = Identifier.tryParse(candidate.getKey());
			if (id == null) continue;
			result.put(candidate.getKey(), entry(candidate.getKey(), id.getNamespace(), id.getPath(),
					"提示框背景与边框资源"));
		}
		return sorted(result);
	}

	private static LuaInsertCatalog.Entry entry(String value, String namespace, String path, String description) {
		String name = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ');
		return new LuaInsertCatalog.Entry(value, name, namespace, description);
	}

	private static List<LuaInsertCatalog.Entry> sorted(Map<String, LuaInsertCatalog.Entry> entries) {
		return entries.values().stream()
				.sorted(Comparator.comparing(LuaInsertCatalog.Entry::value, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}
}
