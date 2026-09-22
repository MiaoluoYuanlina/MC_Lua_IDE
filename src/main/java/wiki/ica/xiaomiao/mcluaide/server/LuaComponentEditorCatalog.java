package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-authoritative component metadata and defaults consumed by the visual editor. */
final class LuaComponentEditorCatalog {
	private static final Set<String> UNIVERSAL = Set.of(
			"minecraft:custom_data", "minecraft:max_stack_size", "minecraft:max_damage",
			"minecraft:damage", "minecraft:unbreakable", "minecraft:custom_name",
			"minecraft:item_name", "minecraft:item_model", "minecraft:lore",
			"minecraft:rarity", "minecraft:enchantments", "minecraft:custom_model_data",
			"minecraft:tooltip_display", "minecraft:repair_cost",
			"minecraft:enchantment_glint_override", "minecraft:tooltip_style");
	private static final Set<String> BLOCK_COMPONENTS = Set.of(
			"minecraft:block_entity_data", "minecraft:block_state", "minecraft:container",
			"minecraft:lock", "minecraft:container_loot", "minecraft:bees",
			"minecraft:banner_patterns", "minecraft:base_color", "minecraft:pot_decorations");
	private static final Set<String> BASE_ENTITY_COMPONENTS = Set.of(
			"minecraft:custom_data", "minecraft:custom_name");
	private static final Map<String, Metadata> METADATA = metadata();

	private LuaComponentEditorCatalog() {
	}

	static String describe(MinecraftServer server, String kind, String rawId) {
		int split = rawId == null ? -1 : rawId.indexOf('\n');
		String locale = split < 0 ? "zh_cn" : rawId.substring(0, split).strip();
		String requestedText = split < 0 ? rawId : rawId.substring(split + 1);
		Identifier requested = identifier(requestedText);
		if (kind.equals("entity")) return describeEntity(server, requested, locale);
		Resolved resolved = resolve(kind, requested);
		ItemStack stack = resolved.item().getDefaultInstance();
		Map<String, JsonElement> defaults = encodedDefaults(server, stack);

		List<ComponentEntry> entries = new ArrayList<>();
		for (Identifier id : BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
			DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
			if (type == null || type.isTransient()) continue;
			String componentId = id.toString();
			Metadata metadata = METADATA.getOrDefault(componentId, Metadata.generic(id));
			int relevance = defaults.containsKey(componentId) ? 2
					: UNIVERSAL.contains(componentId)
						|| kind.equals("block") && BLOCK_COMPONENTS.contains(componentId) ? 1 : 0;
			JsonElement value = defaults.get(componentId);
			entries.add(new ComponentEntry(componentId, metadata, relevance,
					value == null ? metadata.example() : value));
		}
		entries.sort(Comparator.comparingInt(ComponentEntry::relevance).reversed()
				.thenComparing(ComponentEntry::id, String.CASE_INSENSITIVE_ORDER));

		JsonObject root = new JsonObject();
		root.addProperty("kind", kind);
		root.addProperty("requestedId", requested.toString());
		root.addProperty("itemId", BuiltInRegistries.ITEM.getKey(resolved.item()).toString());
		root.addProperty("name", stack.getHoverName().getString());
		root.addProperty("sourceHint", resolved.hint());
		JsonArray components = new JsonArray();
		for (ComponentEntry entry : entries) components.add(entry.toJson(locale));
		root.add("components", components);
		return root.toString();
	}

	static String validate(MinecraftServer server, String rawItemId, String rawPatch) {
		int split = rawItemId == null ? -1 : rawItemId.indexOf('\n');
		String kind = split < 0 ? "item" : rawItemId.substring(0, split).strip();
		String targetId = split < 0 ? rawItemId : rawItemId.substring(split + 1);
		Identifier id = identifier(targetId);
		if (kind.equals("entity")) return validateEntity(server, id, rawPatch);
		if (!kind.equals("item") && !kind.equals("block")) {
			throw new IllegalArgumentException("未知组件目标类型：" + kind);
		}
		Item item;
		if (kind.equals("block")) {
			var block = BuiltInRegistries.BLOCK.getOptional(id)
					.orElseThrow(() -> new IllegalArgumentException("未知方块组件目标：" + id));
			item = block.asItem();
			if (item.getDefaultInstance().isEmpty()) {
				throw new IllegalArgumentException("该方块没有对应物品形式：" + id);
			}
		} else {
			item = BuiltInRegistries.ITEM.getOptional(id)
					.orElseThrow(() -> new IllegalArgumentException("未知物品组件目标：" + id));
		}
		DataComponentPatch parsed = decodePatch(server, rawPatch);
		ItemStack stack = new ItemStack(item.builtInRegistryHolder(), 1, parsed);
		ItemStack.validateStrict(stack)
				.getOrThrow(problem -> new IllegalArgumentException("组件组合无效：" + problem));
		return net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(
				RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), parsed)
				.getOrThrow(problem -> new IllegalArgumentException("无法规范化组件：" + problem)).toString();
	}

	private static String describeEntity(MinecraftServer server, Identifier requested, String locale) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(requested)
				.orElseThrow(() -> new IllegalArgumentException("未知实体：" + requested));
		Entity entity = createEntity(server, type);
		Map<String, JsonElement> defaults = encodedEntityDefaults(server, type, entity);
		Set<String> applicable = new HashSet<>(defaults.keySet());
		applicable.addAll(BASE_ENTITY_COMPONENTS);

		List<ComponentEntry> entries = new ArrayList<>();
		for (Identifier id : BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
			DataComponentType<?> componentType = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
			if (componentType == null || componentType.isTransient() || !applicable.contains(id.toString())) continue;
			Metadata metadata = METADATA.getOrDefault(id.toString(), Metadata.generic(id));
			JsonElement value = defaults.get(id.toString());
			entries.add(new ComponentEntry(id.toString(), metadata, value == null ? 1 : 2,
					value == null ? metadata.example() : value));
		}
		entries.sort(Comparator.comparingInt(ComponentEntry::relevance).reversed()
				.thenComparing(ComponentEntry::id, String.CASE_INSENSITIVE_ORDER));

		JsonObject root = new JsonObject();
		root.addProperty("kind", "entity");
		root.addProperty("requestedId", requested.toString());
		// Kept for wire compatibility with older administrator clients; entity clients use requestedId.
		root.addProperty("itemId", requested.toString());
		root.addProperty("name", type.getDescription().getString());
		root.addProperty("sourceHint", "实体类型组件 · 直接作用于实体，不是刷怪蛋物品组件");
		JsonArray components = new JsonArray();
		for (ComponentEntry entry : entries) components.add(entry.toJson(locale));
		root.add("components", components);
		return root.toString();
	}

	private static String validateEntity(MinecraftServer server, Identifier id, String rawPatch) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
				.orElseThrow(() -> new IllegalArgumentException("未知实体：" + id));
		Entity entity = createEntity(server, type);
		Set<String> applicable = new HashSet<>(encodedEntityDefaults(server, type, entity).keySet());
		applicable.addAll(BASE_ENTITY_COMPONENTS);
		DataComponentPatch parsed = decodePatch(server, rawPatch);
		for (Map.Entry<DataComponentType<?>, java.util.Optional<?>> entry : parsed.entrySet()) {
			Identifier componentId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
			if (componentId == null || !applicable.contains(componentId.toString())) {
				throw new IllegalArgumentException("组件不适用于实体 " + id + "：" + componentId);
			}
			if (entity != null && entry.getValue().isPresent()) {
				setEntityComponent(entity, entry.getKey(), entry.getValue().get());
			}
		}
		return DataComponentPatch.CODEC.encodeStart(
				RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), parsed)
				.getOrThrow(problem -> new IllegalArgumentException("无法规范化实体组件：" + problem)).toString();
	}

	private static DataComponentPatch decodePatch(MinecraftServer server, String rawPatch) {
		JsonElement source = JsonParser.parseString(rawPatch);
		if (!source.isJsonObject()) throw new IllegalArgumentException("组件必须是 JSON 对象");
		return DataComponentPatch.CODEC.parse(
				RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), source)
				.getOrThrow(problem -> new IllegalArgumentException("组件值无效：" + problem));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void setEntityComponent(Entity entity, DataComponentType type, Object value) {
		entity.setComponent(type, value);
	}

	private static Resolved resolve(String kind, Identifier id) {
		return switch (kind) {
			case "item" -> new Resolved(BuiltInRegistries.ITEM.getOptional(id)
					.orElseThrow(() -> new IllegalArgumentException("未知物品：" + id)), "物品默认组件");
			case "block" -> {
				var block = BuiltInRegistries.BLOCK.getOptional(id)
						.orElseThrow(() -> new IllegalArgumentException("未知方块：" + id));
				Item item = block.asItem();
				if (item.getDefaultInstance().isEmpty()) {
					throw new IllegalArgumentException("该方块没有对应物品形式：" + id);
				}
				yield new Resolved(item, "方块物品 · 可编辑方块实体/方块状态组件");
			}
			default -> throw new IllegalArgumentException("未知选择类型：" + kind);
		};
	}

	private static Entity createEntity(MinecraftServer server, EntityType<?> type) {
		try {
			return type.create(server.overworld(), EntitySpawnReason.COMMAND);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static Map<String, JsonElement> encodedDefaults(MinecraftServer server, ItemStack stack) {
		Map<String, JsonElement> result = new HashMap<>();
		var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		for (TypedDataComponent<?> component : stack.getComponents()) {
			Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
			if (id == null || component.type().isTransient()) continue;
			component.encodeValue(ops).result().ifPresent(value -> result.put(id.toString(), value));
		}
		return result;
	}

	private static Map<String, JsonElement> encodedEntityDefaults(MinecraftServer server,
			EntityType<?> type, Entity entity) {
		Map<String, JsonElement> result = new HashMap<>();
		var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		for (TypedDataComponent<?> component : type.builtInRegistryHolder().components()) {
			encodeComponent(result, ops, component);
		}
		if (entity != null) {
			for (Identifier id : BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
				DataComponentType<?> componentType = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
				if (componentType == null || componentType.isTransient()) continue;
				Object value;
				try {
					value = entity.get(componentType);
				} catch (RuntimeException ignored) {
					continue;
				}
				if (value != null) encodeComponent(result, ops,
						TypedDataComponent.createUnchecked(componentType, value));
			}
		}
		return result;
	}

	private static void encodeComponent(Map<String, JsonElement> result,
			RegistryOps<JsonElement> ops, TypedDataComponent<?> component) {
		Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
		if (id == null || component.type().isTransient()) return;
		component.encodeValue(ops).result().ifPresent(value -> result.put(id.toString(), value));
	}

	private static Identifier identifier(String raw) {
		Identifier id = Identifier.tryParse(raw == null ? "" : raw.strip());
		if (id == null) throw new IllegalArgumentException("无效资源 ID：" + raw);
		return id;
	}

	private static Map<String, Metadata> metadata() {
		Map<String, Metadata> values = new HashMap<>();
		put(values, "custom_data", "自定义数据", "模组或脚本保存的任意结构化数据", "json", "{}");
		put(values, "max_stack_size", "最大堆叠数", "控制该物品堆允许的最大数量", "integer", "64");
		put(values, "max_damage", "最大耐久", "设置物品的最大耐久值", "integer", "100");
		put(values, "damage", "当前损伤", "设置已消耗的耐久值", "integer", "0");
		put(values, "unbreakable", "无法破坏", "使物品不会因耐久耗尽而损坏", "unit", "{}");
		put(values, "custom_name", "自定义名称", "设置物品或实体的显示名称；支持文本组件", "text_component", "{\"text\":\"自定义名称\"}");
		put(values, "item_name", "固有名称", "设置物品固有名称，通常不显示斜体", "text_component", "{\"text\":\"物品名称\"}");
		put(values, "item_model", "物品模型", "指定用于渲染此物品的模型资源 ID", "resource", "\"minecraft:stone\"");
		put(values, "tooltip_style", "提示框样式", "指定由资源包提供的物品提示框背景与边框样式 ID",
				"resource", "\"minecraft:default\"");
		put(values, "lore", "物品描述", "设置物品提示中的多行文本组件", "json", "[]");
		put(values, "rarity", "稀有度", "设置名称颜色和稀有度", "enum", "\"common\"");
		put(values, "enchantments", "附魔", "设置物品附魔及等级", "json", "{}");
		put(values, "can_place_on", "可放置于", "冒险模式中允许放置到的方块条件", "json", "{}");
		put(values, "can_break", "可破坏", "冒险模式中允许破坏的方块条件", "json", "{}");
		put(values, "attribute_modifiers", "属性修饰符", "装备或持有时应用的属性修改", "json", "[]");
		put(values, "custom_model_data", "自定义模型数据", "为资源包模型提供自定义数据", "json", "{}");
		put(values, "repair_cost", "修复惩罚", "铁砧操作累计的额外等级成本", "integer", "0");
		put(values, "enchantment_glint_override", "强制附魔光效", "强制显示或隐藏附魔光效", "boolean", "true");
		put(values, "food", "食物属性", "定义营养、饱和度等食物行为", "json", "{}");
		put(values, "consumable", "使用/食用行为", "定义使用动画、时长和效果", "json", "{}");
		put(values, "tool", "工具规则", "定义挖掘速度、掉落规则与耐久消耗", "json", "{}");
		put(values, "weapon", "武器规则", "定义武器的攻击和耐久行为", "json", "{}");
		put(values, "equippable", "可装备", "定义装备槽、模型与装备限制", "json", "{}");
		put(values, "glider", "滑翔能力", "允许物品像鞘翅一样用于滑翔", "unit", "{}");
		put(values, "entity_data", "实体数据", "刷怪蛋放置实体时写入的实体数据", "json", "{}");
		put(values, "bucket_entity_data", "桶中实体数据", "生物桶释放实体时使用的数据", "json", "{}");
		put(values, "block_entity_data", "方块实体数据", "方块物品放置后写入方块实体的数据", "json", "{}");
		put(values, "container", "容器内容", "保存潜影盒等容器物品的内容", "json", "[]");
		put(values, "block_state", "方块状态", "放置方块时应用的方块状态属性", "json", "{}");
		put(values, "lock", "容器锁", "设置容器打开条件", "json", "{}");
		put(values, "profile", "玩家档案", "玩家头颅使用的名称、UUID 与皮肤属性", "json", "{}");
		put(values, "potion_contents", "药水内容", "设置药水类型、颜色和自定义效果", "json", "{}");
		put(values, "bundle_contents", "收纳袋内容", "设置收纳袋内部的物品堆", "json", "[]");
		return Map.copyOf(values);
	}

	private static void put(Map<String, Metadata> values, String path, String label,
			String description, String editor, String exampleJson) {
		values.put("minecraft:" + path, new Metadata(label, description, editor,
				JsonParser.parseString(exampleJson)));
	}

	private record Resolved(Item item, String hint) {
	}

	private record ComponentEntry(String id, Metadata metadata, int relevance, JsonElement value) {
		JsonObject toJson(String locale) {
			JsonObject result = new JsonObject();
			result.addProperty("id", id);
			result.addProperty("label", metadata.label());
			result.addProperty("description", metadata.description());
			result.addProperty("editor", metadata.editor());
			result.addProperty("relevance", relevance);
			result.add("default", value.deepCopy());
			JsonArray hints = new JsonArray();
			for (McLuaIdeRegistryApi.ComponentHint hint : McLuaIdeRegistryApi.componentHints(id)) {
				McLuaIdeRegistryApi.Text text = hint.translation(locale);
				JsonObject encoded = new JsonObject();
				encoded.addProperty("path", hint.pathPattern());
				encoded.addProperty("target", hint.target().name().toLowerCase(java.util.Locale.ROOT));
				encoded.addProperty("registry", hint.registryId());
				encoded.addProperty("label", text.label());
				encoded.addProperty("description", text.description());
				hints.add(encoded);
			}
			result.add("idHints", hints);
			return result;
		}
	}

	private record Metadata(String label, String description, String editor, JsonElement example) {
		static Metadata generic(Identifier id) {
			String readable = id.getPath().replace('_', ' ');
			return new Metadata(readable, "由 " + id.getNamespace()
					+ " 注册的组件；使用 JSON 编辑，具体约束由所属模组定义", "json", new JsonObject());
		}
	}
}
