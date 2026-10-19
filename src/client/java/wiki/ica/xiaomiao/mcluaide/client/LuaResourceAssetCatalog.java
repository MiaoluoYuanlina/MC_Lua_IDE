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
	static final String MODEL = "mc_lua_ide:model_asset";
	static final String SOUND_FILE = "mc_lua_ide:sound_asset";
	static final String PARTICLE = "mc_lua_ide:particle_asset";
	static final String BLOCKSTATE = "mc_lua_ide:blockstate_asset";
	static final String ATLAS = "mc_lua_ide:atlas_asset";
	static final String ATTRIBUTE_MODIFIER = "mc_lua_ide:attribute_modifier_id";
	private static final Map<String, List<LuaInsertCatalog.Entry>> CACHE = new LinkedHashMap<>();
	private static List<LuaResourcePackWorkspace.Entry> workspaceFiles;
	static synchronized void invalidate() { CACHE.clear(); workspaceFiles = null; }
	static synchronized List<LuaResourcePackWorkspace.Entry> workspaceFiles() {
		if (workspaceFiles == null) {
			try { workspaceFiles = LuaResourcePackWorkspace.list(); }
			catch (java.io.IOException error) {
				wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.LOGGER.warn("Could not index resourcepack workspace IDs", error);
				workspaceFiles = List.of();
			}
		}
		return workspaceFiles;
	}

	private LuaResourceAssetCatalog() {
	}

	/** Returns {@code null} when the identifier names a normal server registry. */
	static synchronized List<LuaInsertCatalog.Entry> load(String catalogId, Minecraft minecraft) {
		if (!java.util.Set.of(ITEM_MODEL, EQUIPMENT, TOOLTIP_STYLE, TEXTURE, FONT, MODEL, SOUND_FILE,
				PARTICLE, BLOCKSTATE, ATLAS, ATTRIBUTE_MODIFIER).contains(catalogId)) return null;
		if (CACHE.containsKey(catalogId)) return CACHE.get(catalogId);
		ResourceManager resources = minecraft.getResourceManager();
		List<LuaInsertCatalog.Entry> result = switch (catalogId) {
			case ITEM_MODEL -> itemModels(resources);
			case EQUIPMENT -> scan(resources, "equipment", ".json", "装备模型资源");
			case TOOLTIP_STYLE -> tooltipStyles(resources);
			case TEXTURE -> scan(resources, "textures", ".png", "纹理资源");
			case FONT -> scan(resources, "font", ".json", "字体资源");
			case MODEL -> scan(resources, "models", ".json", "模型资源（不是物品注册 ID）");
			case SOUND_FILE -> scan(resources, "sounds", ".ogg", "音频文件资源（不是 sounds.json 音效事件 ID）");
			case PARTICLE -> scan(resources, "particles", ".json", "粒子贴图定义（不是粒子类型注册 ID）");
			case BLOCKSTATE -> scan(resources, "blockstates", ".json", "方块状态模型映射（不是方块注册 ID）");
			case ATLAS -> scan(resources, "atlases", ".json", "纹理图集定义");
			case ATTRIBUTE_MODIFIER -> attributeModifiers();
			default -> null;
		};
		CACHE.put(catalogId, result);
		return result;
	}

	static List<LuaRegistryCatalogData.RegistryType> types(Minecraft minecraft) {
		List<LuaRegistryCatalogData.RegistryType> result = new ArrayList<>();
		addType(result, minecraft, ITEM_MODEL, "物品模型资源 ID", "当前客户端资源包与 Mod 的 assets/*/items/*.json");
		addType(result, minecraft, EQUIPMENT, "装备模型资源 ID", "当前客户端资源包与 Mod 的 assets/*/equipment/*.json");
		addType(result, minecraft, TOOLTIP_STYLE, "提示框样式 ID", "成对的 tooltip/*_background 与 *_frame 精灵");
		addType(result, minecraft, TEXTURE, "纹理资源 ID", "当前客户端资源包与 Mod 的全部 PNG 纹理");
		addType(result, minecraft, FONT, "字体资源 ID", "当前客户端资源包与 Mod 的 assets/*/font/*.json");
		addType(result, minecraft, MODEL, "模型资源 ID", "assets/*/models/*.json，包括物品和方块模型");
		addType(result, minecraft, SOUND_FILE, "OGG 音频文件 ID", "assets/*/sounds/*.ogg，不等同于音效事件 ID");
		addType(result, minecraft, PARTICLE, "粒子贴图定义 ID", "assets/*/particles/*.json，不创建新的粒子类型");
		addType(result, minecraft, BLOCKSTATE, "方块状态模型 ID", "assets/*/blockstates/*.json，不创建新的方块");
		addType(result, minecraft, ATLAS, "纹理图集 ID", "assets/*/atlases/*.json");
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
		for (var file : workspaceFiles()) {
			if (file.directory()) continue;
			Identifier resource = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.path(), "assets");
			if (resource == null) continue;
			String value = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.logical(resource, directory, suffix, false);
			if (value != null) result.putIfAbsent(value, entry(value, resource.getNamespace(), Identifier.parse(value).getPath(),
					description + " · 编辑目录中的定义，可能尚未启用或重载"));
		}
		return sorted(result);
	}

	static synchronized List<LuaInsertCatalog.Entry> sounds(Minecraft minecraft) {
		String key = "minecraft:sound_event";
		if (CACHE.containsKey(key)) return CACHE.get(key);
		Map<String, LuaInsertCatalog.Entry> result = new LinkedHashMap<>();
		ResourceManager manager = minecraft.getResourceManager();
		// Root files cannot be enumerated with an empty listResources directory in this game version.
		for (String namespace : manager.getNamespaces()) {
			Identifier id = Identifier.fromNamespaceAndPath(namespace, "sounds.json");
			for (var resource : manager.getResourceStack(id)) {
				try (var reader = resource.openAsReader()) {
					addSounds(result, namespace, com.google.gson.JsonParser.parseReader(reader).getAsJsonObject(), "已加载客户端音效定义");
				} catch (Exception error) {
					wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.LOGGER.warn("Could not read sound IDs from {}", id, error);
				}
			}
		}
		for (var file : workspaceFiles()) {
			if (file.directory()) continue;
			Identifier id = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.path(), "assets");
			if (id == null || !id.getPath().equals("sounds.json")) continue;
			try {
				addSounds(result, id.getNamespace(), com.google.gson.JsonParser.parseString(LuaResourcePackWorkspace.readText(file.path())).getAsJsonObject(),
						"编辑目录中的音效定义，可能尚未启用或重载资源包");
			} catch (Exception error) {
				wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.LOGGER.warn("Could not read sound IDs from workspace {}", file.path(), error);
			}
		}
		List<LuaInsertCatalog.Entry> entries = sorted(result); CACHE.put(key, entries); return entries;
	}
	static void addSounds(Map<String, LuaInsertCatalog.Entry> result, String namespace, com.google.gson.JsonObject json, String description) {
		for (String name : json.keySet()) {
			Identifier id = Identifier.tryParse(namespace + ":" + name);
			if (id != null) result.putIfAbsent(id.toString(), entry(id.toString(), namespace, name, description));
		}
	}
	static List<LuaInsertCatalog.Entry> merge(List<LuaInsertCatalog.Entry> loaded, List<LuaInsertCatalog.Entry> extra) {
		Map<String, LuaInsertCatalog.Entry> result = new LinkedHashMap<>();
		loaded.forEach(entry -> result.put(entry.value(), entry));
		extra.forEach(entry -> result.putIfAbsent(entry.value(), entry));
		return sorted(result);
	}

	private static List<LuaInsertCatalog.Entry> tooltipStyles(ResourceManager resources) {
		Map<String, Integer> masks = new LinkedHashMap<>();
		String directory = "textures/gui/sprites/tooltip";
		for (LuaInsertCatalog.Entry resource : scan(resources, directory, ".png", "提示框精灵")) {
			Identifier resourceId = Identifier.parse(resource.value());
			String relative = resourceId.getPath();
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
			String value = resourceId.getNamespace() + ":" + style;
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
