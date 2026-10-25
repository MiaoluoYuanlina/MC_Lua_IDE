package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

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
		if (kind.equals("block")) return describeBlock(server, requested, locale);
		Resolved resolved = resolve(kind, requested);
		ItemStack stack = resolved.item().getDefaultInstance();
		Map<String, JsonElement> defaults = encodedDefaults(server, stack);
		Map<String, JsonElement> examples = componentExamples(server);

		List<ComponentEntry> entries = new ArrayList<>();
		for (Identifier id : BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
			DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
			if (type == null || type.isTransient()) continue;
			String componentId = id.toString();
			Metadata metadata = METADATA.getOrDefault(componentId, Metadata.generic(id));
			int relevance = defaults.containsKey(componentId) ? 2 : UNIVERSAL.contains(componentId) ? 1 : 0;
			JsonElement value = defaults.get(componentId);
			entries.add(new ComponentEntry(componentId, metadata, relevance,
					value == null ? METADATA.containsKey(componentId) ? metadata.example() : examples.getOrDefault(componentId, metadata.example()) : value));
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
		for (ComponentEntry entry : entries) {
			JsonObject encoded = entry.toJson(locale);
			components.add(encoded);
		}
		root.add("components", components);
		root.add("componentIdHints", hintCatalog(locale));
		return root.toString();
	}

	private static String describeBlock(MinecraftServer server, Identifier requested, String locale) {
		var block = BuiltInRegistries.BLOCK.getOptional(requested)
				.orElseThrow(() -> new IllegalArgumentException("未知方块：" + requested));
		BlockState state = block.defaultBlockState();
		Map<String, ComponentTreeSchema.Node> fields = new java.util.LinkedHashMap<>();
		for (Property<?> property : block.getStateDefinition().getProperties()) {
			List<String> choices = propertyChoices(property);
			fields.put(property.getName(), ComponentTreeSchema.choices(property.getName(),
					propertyValue(state, property), choices.toArray(String[]::new)));
		}
		JsonArray components = new JsonArray();
		JsonObject stateEntry = new ComponentEntry("states",
				new Metadata("方块状态", "该方块实际放置后的状态属性；方向、朝向、开关等由此方块的注册状态决定", "json", new JsonObject()),
				2, new JsonObject()).toJson(locale);
		stateEntry.add("schema", new ComponentTreeSchema.Node("方块状态", "object", new JsonObject(),
				fields, null, List.of(), "", false).encode());
		components.add(stateEntry);
		if (state.hasBlockEntity()) {
			JsonObject dataEntry = new ComponentEntry("blockEntityData",
				new Metadata("方块实体数据", "已放置方块实体的自定义存储数据；不是方块物品的数据组件", "json", new JsonObject()),
				1, new JsonObject()).toJson(locale);
			dataEntry.add("schema", blockEntitySchema(server, state).encode());
			components.add(dataEntry);
		}
		JsonObject root = new JsonObject();
		root.addProperty("kind", "block");
		root.addProperty("requestedId", requested.toString());
		root.addProperty("itemId", requested.toString());
		root.addProperty("name", net.minecraft.network.chat.Component.translatable(block.getDescriptionId()).getString());
		root.addProperty("sourceHint", "真实方块状态 / 方块实体数据 · 不是物品堆叠组件");
		root.add("components", components);
		root.add("componentIdHints", hintCatalog(locale));
		return root.toString();
	}

	private static ComponentTreeSchema.Node blockEntitySchema(MinecraftServer server, BlockState state) {
		BlockEntity entity = createBlockEntity(state);
		Map<String, ComponentTreeSchema.Node> fields = new java.util.LinkedHashMap<>();
		if (entity != null) {
			try {
				var registries = server == null
						? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) : server.registryAccess();
				JsonElement saved = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, entity.saveCustomOnly(registries));
				if (saved.isJsonObject()) saved.getAsJsonObject().entrySet().forEach(entry ->
						fields.put(entry.getKey(), ComponentTreeSchema.observed(entry.getKey(), entry.getValue())));
			} catch (RuntimeException ignored) {
				// Some mod block entities require a live level to save. Explicit templates below still work.
			}
		}
		if (entity instanceof Container container) fields.put("Items", containerItemsSchema(container,
				entity instanceof AbstractFurnaceBlockEntity));
		if (entity instanceof AbstractFurnaceBlockEntity) {
			fields.put("cooking_time_spent", ComponentTreeSchema.scalar("已烹饪刻数", "integer", "0"));
			fields.put("cooking_total_time", ComponentTreeSchema.scalar("本次烹饪所需刻数", "integer", "200"));
			fields.put("lit_time_remaining", ComponentTreeSchema.scalar("剩余燃烧刻数", "integer", "0"));
			fields.put("lit_total_time", ComponentTreeSchema.scalar("总燃烧刻数", "integer", "0"));
		}
		if (entity instanceof SignBlockEntity) {
			fields.put("front_text", signSideSchema("正面文字"));
			fields.put("back_text", signSideSchema("背面文字"));
			fields.put("is_waxed", ComponentTreeSchema.scalar("封蜡（禁止玩家编辑）", "boolean", "false"));
		}
		return new ComponentTreeSchema.Node("方块实体数据", "object", new JsonObject(),
				fields, null, List.of(), "", true);
	}

	private static ComponentTreeSchema.Node containerItemsSchema(Container container, boolean furnace) {
		Map<String, ComponentTreeSchema.Node> slotFields = new java.util.LinkedHashMap<>();
		slotFields.put("Slot", ComponentTreeSchema.scalar(furnace ? "槽位（0 原料 / 1 燃料 / 2 成品）"
				: "槽位序号（0～" + (container.getContainerSize() - 1) + "）", "integer", "0"));
		slotFields.putAll(ComponentTreeSchema.itemStack().fields());
		ComponentTreeSchema.Node slot = new ComponentTreeSchema.Node("容器槽位", "object",
				JsonParser.parseString("{\"Slot\":0,\"id\":\"minecraft:stone\",\"count\":1}"),
				slotFields, null, List.of(), "", false);
		return new ComponentTreeSchema.Node("存放物品", "array", new JsonArray(), Map.of(),
				slot, List.of(), "", false);
	}

	private static ComponentTreeSchema.Node signSideSchema(String label) {
		JsonArray lines = new JsonArray();
		for (int index = 0; index < 4; index++) {
			JsonObject text = new JsonObject();
			text.addProperty("text", "");
			lines.add(text);
		}
		ComponentTreeSchema.Node messages = new ComponentTreeSchema.Node("四行文字", "array", lines,
				Map.of(), ComponentTreeSchema.textComponent(), List.of(), "", false);
		Map<String, ComponentTreeSchema.Node> fields = new java.util.LinkedHashMap<>();
		fields.put("messages", messages);
		fields.put("filtered_messages", new ComponentTreeSchema.Node("过滤后的四行文字", "array",
				lines.deepCopy(), Map.of(), ComponentTreeSchema.textComponent(), List.of(), "", false));
		fields.put("color", ComponentTreeSchema.choices("染料文字颜色", "black", "white", "orange", "magenta",
				"light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
				"brown", "green", "red", "black"));
		fields.put("has_glowing_text", ComponentTreeSchema.scalar("发光文字", "boolean", "false"));
		JsonObject seed = new JsonObject();
		seed.add("messages", lines.deepCopy());
		return new ComponentTreeSchema.Node(label, "object", seed, fields, null, List.of(), "", false);
	}

	private static BlockEntity createBlockEntity(BlockState state) {
		return state.getBlock() instanceof EntityBlock block ? block.newBlockEntity(BlockPos.ZERO, state) : null;
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static <T extends Comparable<T>> List<String> propertyChoices(net.minecraft.world.level.block.state.properties.Property<T> property) {
		return property.getPossibleValues().stream().map(property::getName).toList();
	}

	static String validate(MinecraftServer server, String rawItemId, String rawPatch) {
		int split = rawItemId == null ? -1 : rawItemId.indexOf('\n');
		String kind = split < 0 ? "item" : rawItemId.substring(0, split).strip();
		String targetId = split < 0 ? rawItemId : rawItemId.substring(split + 1);
		Identifier id = identifier(targetId);
		if (kind.equals("entity")) return validateEntity(server, id, rawPatch);
		if (kind.equals("block")) return validateBlock(server, id, rawPatch);
		if (!kind.equals("item")) {
			throw new IllegalArgumentException("未知组件目标类型：" + kind);
		}
		Item item = BuiltInRegistries.ITEM.getOptional(id)
				.orElseThrow(() -> new IllegalArgumentException("未知物品组件目标：" + id));
		DataComponentPatch parsed = decodePatch(server, rawPatch);
		ItemStack stack = new ItemStack(item.builtInRegistryHolder(), 1, parsed);
		ItemStack.validateStrict(stack)
				.getOrThrow(problem -> new IllegalArgumentException("组件组合无效：" + problem));
		return net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(
				RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), parsed)
				.getOrThrow(problem -> new IllegalArgumentException("无法规范化组件：" + problem)).toString();
	}

	private static String validateBlock(MinecraftServer server, Identifier id, String rawConfig) {
		var block = BuiltInRegistries.BLOCK.getOptional(id)
				.orElseThrow(() -> new IllegalArgumentException("未知方块：" + id));
		JsonElement parsed = JsonParser.parseString(rawConfig);
		if (!parsed.isJsonObject()) throw new IllegalArgumentException("方块配置必须是对象");
		JsonObject config = parsed.getAsJsonObject();
		for (var field : config.entrySet()) {
			switch (field.getKey()) {
				case "states" -> {
					if (!field.getValue().isJsonObject()) throw new IllegalArgumentException("方块状态必须是对象");
					for (var stateField : field.getValue().getAsJsonObject().entrySet()) {
						Property<?> property = block.getStateDefinition().getProperty(stateField.getKey());
						if (property == null) throw new IllegalArgumentException("该方块没有状态属性：" + stateField.getKey());
						if (!stateField.getValue().isJsonPrimitive() || !stateField.getValue().getAsJsonPrimitive().isString()
								|| property.getValue(stateField.getValue().getAsString()).isEmpty()) {
							throw new IllegalArgumentException("方块状态 " + stateField.getKey() + " 的值不在允许范围内");
						}
					}
				}
				case "blockEntityData" -> {
					if (!block.defaultBlockState().hasBlockEntity())
						throw new IllegalArgumentException("该方块没有方块实体数据：" + id);
					if (!field.getValue().isJsonObject()) throw new IllegalArgumentException("方块实体数据必须是对象");
					validateBlockEntityData(server, block.defaultBlockState(), field.getValue().getAsJsonObject());
				}
				case "itemComponents" -> {
					if (!block.defaultBlockState().hasBlockEntity())
						throw new IllegalArgumentException("该方块不能接收方块物品组件：" + id);
					Item item = block.asItem();
					if (item.getDefaultInstance().isEmpty())
						throw new IllegalArgumentException("该方块没有对应的物品形式：" + id);
					DataComponentPatch patch = decodePatch(server, field.getValue().toString());
					ItemStack.validateStrict(new ItemStack(item.builtInRegistryHolder(), 1, patch))
							.getOrThrow(problem -> new IllegalArgumentException("方块物品组件无效：" + problem));
				}
				default -> throw new IllegalArgumentException("未知方块配置字段：" + field.getKey());
			}
		}
		return config.toString();
	}

	private static void validateBlockEntityData(MinecraftServer server, BlockState state, JsonObject data) {
		BlockEntity entity = createBlockEntity(state);
		if (entity == null) throw new IllegalArgumentException("此方块无法创建方块实体");
		var registries = server == null
				? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) : server.registryAccess();
		var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		if (entity instanceof Container container && data.has("Items")) {
			if (data.get("Items").isJsonObject() && data.getAsJsonObject("Items").isEmpty())
				data.add("Items", new JsonArray());
			if (!data.get("Items").isJsonArray()) throw new IllegalArgumentException("Items 必须是槽位数组");
			Set<Integer> seen = new HashSet<>();
			for (JsonElement raw : data.getAsJsonArray("Items")) {
				int slot;
				if (server == null) {
					// Lightweight codec tests bootstrap registries but do not bind item default components.
					if (!raw.isJsonObject() || !raw.getAsJsonObject().has("Slot") || !raw.getAsJsonObject().has("id"))
						throw new IllegalArgumentException("容器物品槽必须包含 Slot 和 id");
					slot = raw.getAsJsonObject().get("Slot").getAsInt();
					identifier(raw.getAsJsonObject().get("id").getAsString());
				} else {
					ItemStackWithSlot entry = ItemStackWithSlot.CODEC.parse(ops, raw)
							.getOrThrow(problem -> new IllegalArgumentException("容器物品槽无效：" + problem));
					slot = entry.slot();
					ItemStack.validateStrict(entry.stack())
							.getOrThrow(problem -> new IllegalArgumentException("槽位 " + slot + " 的物品无效：" + problem));
				}
				if (slot < 0 || slot >= container.getContainerSize())
					throw new IllegalArgumentException("槽位 " + slot + " 超出 0～" + (container.getContainerSize() - 1));
				if (!seen.add(slot)) throw new IllegalArgumentException("容器槽位重复：" + slot);
			}
		}
		if (entity instanceof AbstractFurnaceBlockEntity) {
			for (String key : List.of("cooking_time_spent", "cooking_total_time", "lit_time_remaining", "lit_total_time")) {
				if (!data.has(key)) continue;
				JsonElement raw = data.get(key);
				if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())
					throw new IllegalArgumentException(key + " 必须是整数刻数");
				int value;
				try { value = raw.getAsInt(); }
				catch (RuntimeException error) { throw new IllegalArgumentException(key + " 必须是整数刻数"); }
				if (value < 0 || value > Short.MAX_VALUE || !raw.getAsString().equals(Integer.toString(value)))
					throw new IllegalArgumentException(key + " 必须在 0～32767 刻之间");
			}
		}
		if (entity instanceof SignBlockEntity) {
			for (String side : List.of("front_text", "back_text")) {
				if (!data.has(side)) continue;
				SignText.DIRECT_CODEC.parse(ops, data.get(side))
						.getOrThrow(problem -> new IllegalArgumentException(side + " 文字格式无效：" + problem));
			}
			if (data.has("is_waxed") && (!data.get("is_waxed").isJsonPrimitive()
					|| !data.get("is_waxed").getAsJsonPrimitive().isBoolean())) {
				throw new IllegalArgumentException("is_waxed 必须为 true 或 false");
			}
		}
	}

	private static String describeEntity(MinecraftServer server, Identifier requested, String locale) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(requested)
				.orElseThrow(() -> new IllegalArgumentException("未知实体：" + requested));
		Entity entity = createEntity(server, type);
		Map<String, JsonElement> defaults = encodedEntityDefaults(server, type, entity);
		Set<String> applicable = applicableEntityComponents(defaults, entity);

		List<ComponentEntry> entries = new ArrayList<>();
		Map<String, ComponentTreeSchema.Node> displaySchemas = new HashMap<>();
		entries.add(new ComponentEntry(LuaEntityAttachments.FIELD,
				new Metadata("Fabric 模组附加数据", "附件 ID 到持久化数据的对象；按提供此附件的服务端模组 Codec 校验，例如女仆 profile.model_id；不是物品组件或任意实体 NBT", "json", new JsonObject()),
				1, new JsonObject()));
		List<LuaDisplayEntityConfig.Field> specialFields = new ArrayList<>(LuaDisplayEntityConfig.fields(entity));
		specialFields.addAll(LuaItemEntityConfig.fields(server, entity));
		for (LuaDisplayEntityConfig.Field field : specialFields) {
			String editor = switch (field.schema().kind()) {
				case "boolean", "integer", "number" -> field.schema().kind();
				default -> "json";
			};
			entries.add(new ComponentEntry(field.id(), new Metadata(field.label(), field.description(), editor, field.initial()),
					2, field.initial()));
			displaySchemas.put(field.id(), field.schema());
		}
		if (entity instanceof LivingEntity living) {
			entries.add(new ComponentEntry("health", new Metadata("生命值", "生成后设置生物的当前生命值，不是物品数据组件", "number",
					new JsonPrimitive(living.getHealth())), 2, new JsonPrimitive(living.getHealth())));
			entries.add(new ComponentEntry("equipment", new Metadata("穿戴装备", "按装备槽位配置物品堆；每件装备可以再编辑自己的物品组件", "json",
					new JsonObject()), 2, new JsonObject()));
		}
		for (String flag : List.of("glowing", "invulnerable", "noGravity")) {
			String label = switch (flag) {
				case "glowing" -> "发光轮廓";
				case "invulnerable" -> "无敌";
				default -> "不受重力";
			};
			entries.add(new ComponentEntry(flag, new Metadata(label, "直接作用于实体的状态，不属于物品堆叠组件", "boolean",
					new JsonPrimitive(false)), 1, new JsonPrimitive(false)));
		}
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
		root.addProperty("sourceHint", LuaDisplayEntityConfig.isDisplay(entity)
				? "展示实体专有 NBT 字段 + 实体数据组件 · 不是刷怪蛋物品组件"
				: entity instanceof net.minecraft.world.entity.item.ItemEntity
				? "掉落物实体 NBT 默认数据 + 专属可选标签 + 实体组件 · Item 中单独编辑物品堆组件"
				: "实体类型组件 · 直接作用于实体，不是刷怪蛋物品组件");
		JsonArray components = new JsonArray();
		for (ComponentEntry entry : entries) {
			JsonObject encoded = entry.toJson(locale);
			ComponentTreeSchema.Node displaySchema = displaySchemas.get(entry.id());
			if (displaySchema != null) encoded.add("schema", displaySchema.encode());
			if (entry.id().equals("equipment")) {
				Map<String, ComponentTreeSchema.Node> slots = new java.util.LinkedHashMap<>();
				for (String slot : List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle")) {
					slots.put(slot, ComponentTreeSchema.itemStack());
				}
				encoded.add("schema", new ComponentTreeSchema.Node("穿戴装备", "object", new JsonObject(),
						slots, null, List.of(), "", false).encode());
			}
			components.add(encoded);
		}
		root.add("components", components);
		root.add("componentIdHints", hintCatalog(locale));
		return root.toString();
	}

	private static JsonObject hintCatalog(String locale) {
		JsonObject catalog = new JsonObject();
		McLuaIdeRegistryApi.componentHintCatalog().forEach((id, hints) -> catalog.add(id, encodeHints(hints, locale)));
		return catalog;
	}

	private static JsonArray encodeHints(List<McLuaIdeRegistryApi.ComponentHint> hints, String locale) {
		JsonArray result = new JsonArray();
		for (McLuaIdeRegistryApi.ComponentHint hint : hints) {
			McLuaIdeRegistryApi.Text text = hint.translation(locale);
			JsonObject encoded = new JsonObject();
			encoded.addProperty("path", hint.pathPattern());
			encoded.addProperty("target", hint.target().name().toLowerCase(java.util.Locale.ROOT));
			encoded.addProperty("registry", hint.registryId());
			encoded.addProperty("label", text.label());
			encoded.addProperty("description", text.description());
			result.add(encoded);
		}
		return result;
	}

	private static String validateEntity(MinecraftServer server, Identifier id, String rawPatch) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
				.orElseThrow(() -> new IllegalArgumentException("未知实体：" + id));
		Entity entity = createEntity(server, type);
		Set<String> applicable = applicableEntityComponents(encodedEntityDefaults(server, type, entity), entity);
		JsonElement raw = JsonParser.parseString(rawPatch);
		if (!raw.isJsonObject()) throw new IllegalArgumentException("实体配置必须是对象");
		JsonObject config = raw.getAsJsonObject();
		JsonObject componentValues = new JsonObject();
		JsonObject displayValues = new JsonObject();
		JsonObject droppedItemValues = new JsonObject();
		for (var field : config.entrySet()) {
			String name = field.getKey();
			JsonElement value = field.getValue();
			if (name.equals(LuaEntityAttachments.FIELD)) {
				LuaEntityAttachments.validate(server, value);
				continue;
			}
			if (LuaItemEntityConfig.supports(entity, name)) {
				droppedItemValues.add(name, value.deepCopy());
				continue;
			}
			if (LuaDisplayEntityConfig.supports(entity, name)) {
				displayValues.add(name, value.deepCopy());
				continue;
			}
			if (name.startsWith("minecraft:") || name.indexOf(':') > 0) {
				componentValues.add(name, value.deepCopy());
				continue;
			}
			switch (name) {
				case "health" -> {
					if (!(entity instanceof LivingEntity living) || !value.isJsonPrimitive()
							|| !value.getAsJsonPrimitive().isNumber())
						throw new IllegalArgumentException("只有生物可以设置数值型生命值");
					double health = value.getAsDouble();
					if (!Double.isFinite(health) || health < 0 || health > living.getMaxHealth())
						throw new IllegalArgumentException("生命值须在 0 到 " + living.getMaxHealth() + " 之间");
				}
				case "equipment" -> {
					if (!(entity instanceof LivingEntity) || !value.isJsonObject())
						throw new IllegalArgumentException("只有生物可以设置穿戴装备对象");
					for (var slot : value.getAsJsonObject().entrySet()) {
						if (!List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle").contains(slot.getKey()))
							throw new IllegalArgumentException("未知装备槽位：" + slot.getKey());
						ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), slot.getValue())
								.getOrThrow(problem -> new IllegalArgumentException("装备 " + slot.getKey() + " 无效：" + problem));
					}
				}
				case "glowing", "invulnerable", "noGravity" -> {
					if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
						throw new IllegalArgumentException(name + " 必须为 true 或 false");
				}
				default -> throw new IllegalArgumentException("未知实体配置字段：" + name);
			}
		}
		if (!displayValues.isEmpty()) LuaDisplayEntityConfig.apply(server, entity, displayValues);
		if (!droppedItemValues.isEmpty()) LuaItemEntityConfig.apply(server, entity, droppedItemValues);
		DataComponentPatch parsed = decodePatch(server, componentValues.toString());
		for (Map.Entry<DataComponentType<?>, java.util.Optional<?>> entry : parsed.entrySet()) {
			Identifier componentId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
			if (componentId == null || !applicable.contains(componentId.toString())) {
				throw new IllegalArgumentException("组件不适用于实体 " + id + "：" + componentId);
			}
			if (entity != null && entry.getValue().isPresent()) {
				setEntityComponent(entity, entry.getKey(), entry.getValue().get());
			}
		}
		JsonObject canonical = DataComponentPatch.CODEC.encodeStart(
				RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), parsed)
				.getOrThrow(problem -> new IllegalArgumentException("无法规范化实体组件：" + problem)).getAsJsonObject();
		for (var field : config.entrySet()) {
			if (field.getKey().indexOf(':') < 0 || field.getKey().equals(LuaEntityAttachments.FIELD))
				canonical.add(field.getKey(), field.getValue().deepCopy());
		}
		return canonical.toString();
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
			default -> throw new IllegalArgumentException("未知选择类型：" + kind);
		};
	}

	/** Components with a current value, the always-available ones, and every component the entity class handles (even while absent). */
	private static Set<String> applicableEntityComponents(Map<String, JsonElement> defaults, Entity entity) {
		Set<String> applicable = new HashSet<>(defaults.keySet());
		applicable.addAll(BASE_ENTITY_COMPONENTS);
		applicable.addAll(LuaEntityComponentSupport.supported(entity));
		return applicable;
	}

	private static Entity createEntity(MinecraftServer server, EntityType<?> type) {
		// Some types refuse one spawn reason; try the others so their components can still be listed.
		for (EntitySpawnReason reason : new EntitySpawnReason[] {EntitySpawnReason.COMMAND, EntitySpawnReason.SPAWN_ITEM_USE, EntitySpawnReason.LOAD, EntitySpawnReason.NATURAL}) {
			try {
				Entity entity = type.create(server.overworld(), reason);
				if (entity != null) return entity;
			} catch (RuntimeException ignored) {
				// try the next reason
			}
		}
		return null;
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

	private static Map<String, JsonElement> componentExamples(MinecraftServer server) {
		Map<String, JsonElement> result = new HashMap<>();
		var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		for (Item item : BuiltInRegistries.ITEM) {
			for (TypedDataComponent<?> component : item.getDefaultInstance().getComponents()) {
				Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
				if (id == null || component.type().isTransient() || result.containsKey(id.toString())) continue;
				component.encodeValue(ops).result().ifPresent(value -> result.put(id.toString(), value));
			}
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
			var schema = ComponentTreeSchema.withHints(ComponentTreeSchema.component(id, value), McLuaIdeRegistryApi.componentHints(id), locale);
			result.add("schema", schema.encode());
			if (!METADATA.containsKey(id) && !schema.label().equals(id)) result.addProperty("label", schema.label());
			result.add("idHints", encodeHints(McLuaIdeRegistryApi.componentHints(id), locale));
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
