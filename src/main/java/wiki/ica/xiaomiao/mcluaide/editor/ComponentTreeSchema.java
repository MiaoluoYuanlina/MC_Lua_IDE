package wiki.ica.xiaomiao.mcluaide.editor;

import com.google.gson.*;
import java.util.*;

/** Editor metadata, not a replacement for Minecraft's authoritative component codecs. */
public final class ComponentTreeSchema {
	private ComponentTreeSchema() { }

	public record Node(String label, String kind, JsonElement seed, Map<String, Node> fields,
			Node element, List<String> choices, String registry, boolean custom) {
		public Node {
			fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
			choices = List.copyOf(choices);
		}
		public JsonElement value() { return seed.deepCopy(); }
		public JsonObject encode() {
			JsonObject out = new JsonObject();
			out.addProperty("label", label); out.addProperty("kind", kind); out.add("seed", value());
			out.addProperty("registry", registry); out.addProperty("custom", custom);
			JsonObject children = new JsonObject(); fields.forEach((key, node) -> children.add(key, node.encode())); out.add("fields", children);
			if (element != null) out.add("element", element.encode());
			JsonArray options = new JsonArray(); choices.forEach(options::add); out.add("choices", options);
			return out;
		}
		public static Node decode(JsonObject in) {
			Map<String, Node> fields = new LinkedHashMap<>();
			in.getAsJsonObject("fields").entrySet().forEach(e -> fields.put(e.getKey(), decode(e.getValue().getAsJsonObject())));
			List<String> choices = new ArrayList<>(); in.getAsJsonArray("choices").forEach(v -> choices.add(v.getAsString()));
			return new Node(in.get("label").getAsString(), in.get("kind").getAsString(), in.get("seed").deepCopy(), fields,
					in.has("element") ? decode(in.getAsJsonObject("element")) : null, choices,
					in.get("registry").getAsString(), in.get("custom").getAsBoolean());
		}
	}

	public static Node observed(String label, JsonElement value) {
		if (value == null || value.isJsonNull()) return scalar(label, "string", "\"\"");
		if (value.isJsonObject()) {
			Map<String, Node> fields = new LinkedHashMap<>();
			value.getAsJsonObject().entrySet().forEach(e -> fields.put(e.getKey(), observed(e.getKey(), e.getValue())));
			return new Node(label, "object", value.deepCopy(), fields, null, List.of(), "", true);
		}
		if (value.isJsonArray()) return new Node(label, "array", value.deepCopy(), Map.of(),
				value.getAsJsonArray().isEmpty() ? null : observed("条目", value.getAsJsonArray().get(0)), List.of(), "", true);
		var p = value.getAsJsonPrimitive();
		return scalar(label, p.isBoolean() ? "boolean" : p.isNumber() ? (p.toString().matches("-?\\d+") ? "integer" : "number") : "string", value.toString());
	}

	public static Node component(String id, JsonElement current) {
		Node node = BUILTINS.get(id);
		if (node != null && current != null && current.isJsonPrimitive() && current.getAsJsonPrimitive().isString() && node.kind.equals("object")) {
			if (node.fields.containsKey("id") || node.fields.containsKey("text")) {
				JsonObject expanded = new JsonObject(); expanded.add(node.fields.containsKey("id") ? "id" : "text", current.deepCopy());
				return merge(node, expanded);
			}
		}
		return node == null ? observed(id, current) : merge(node, current);
	}

	/** Explicit mod ID hints also expose optional fields that do not occur in defaults. */
	public static Node withHints(Node node, List<wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi.ComponentHint> hints, String locale) {
		for (var hint : hints) {
			if (hint.target() != wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi.Target.VALUE) continue;
			String[] parts = hint.pathPattern().isEmpty() ? new String[0] : hint.pathPattern().substring(1).split("/");
			if (BUILTINS.containsKey(hint.componentId()) && !contains(node, parts)) continue;
			node = addHint(node, parts, 0, hint.registryId(), hint.translation(locale).label());
		}
		return node;
	}

	private static boolean contains(Node node, String[] path) {
		for (String raw : path) {
			if (node == null) return false;
			String part = raw.replace("~1", "/").replace("~0", "~");
			node = part.equals("*") ? node.element : node.fields.get(part);
		}
		return node != null;
	}

	private static Node addHint(Node node, String[] parts, int index, String registry, String label) {
		if (index == parts.length) {
			if (node != null && (!node.kind.equals("string") && (!node.fields.isEmpty() || !node.seed.isJsonObject()))) return node;
			return new Node(node == null ? label : node.label, "string", node != null && node.seed.isJsonPrimitive() ? node.value() : new JsonPrimitive(""),
					Map.of(), null, node == null ? List.of() : node.choices, registry, false);
		}
		String part = parts[index].replace("~1", "/").replace("~0", "~");
		if (node == null) node = part.equals("*") ? array(label, null) : object(label, "{}");
		// A codec may offer scalar and structured alternatives; keep an existing structured template.
		if (!node.kind.equals("object") && !node.kind.equals("array")) return node;
		if (part.equals("*")) {
			return new Node(node.label, node.kind, node.value(), node.fields, addHint(node.element, parts, index + 1, registry, label), node.choices, node.registry, node.custom);
		}
		Map<String, Node> children = new LinkedHashMap<>(node.fields);
		children.put(part, addHint(children.get(part), parts, index + 1, registry, label));
		return new Node(node.label, node.kind, node.value(), children, node.element, node.choices, node.registry, node.custom);
	}

	private static Node merge(Node node, JsonElement value) {
		if (value == null || value.isJsonNull()) return node;
		boolean same = node.kind.equals("object") ? value.isJsonObject() : node.kind.equals("array") ? value.isJsonArray()
				: value.isJsonPrimitive() && node.seed.isJsonPrimitive()
				&& value.getAsJsonPrimitive().isString() == node.seed.getAsJsonPrimitive().isString();
		if (!same) return node;
		Map<String, Node> children = new LinkedHashMap<>(node.fields);
		if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> children.put(e.getKey(),
				node.fields.containsKey(e.getKey()) ? merge(node.fields.get(e.getKey()), e.getValue()) : observed(e.getKey(), e.getValue())));
		// Empty generic examples must not discard a valid required-field template.
		JsonElement seed = value;
		if (value.isJsonObject()) {
			JsonObject object = node.seed.isJsonObject() ? node.seed.getAsJsonObject().deepCopy() : new JsonObject();
			value.getAsJsonObject().entrySet().forEach(e -> object.add(e.getKey(), e.getValue().deepCopy())); seed = object;
		}
		return new Node(node.label, node.kind, seed.deepCopy(), children, node.element, node.choices, node.registry, node.custom);
	}

	public static Node scalar(String label, String kind, String json) { return new Node(label, kind, JsonParser.parseString(json), Map.of(), null, List.of(), "", false); }
	public static Node choices(String label, String initial, String... values) { return new Node(label, "string", new JsonPrimitive(initial), Map.of(), null, List.of(values), "", false); }

	/** Consume-effect codecs are discriminated by 'type'; their child shapes differ. */
	public static Node variant(Node node, JsonElement actual) {
		if (node == null || !node.fields.containsKey("type") || !node.fields.get("type").registry.equals("minecraft:consume_effect_type")
				|| actual == null || !actual.isJsonObject() || !actual.getAsJsonObject().has("type")) return node;
		JsonElement typeValue = actual.getAsJsonObject().get("type");
		if (!typeValue.isJsonPrimitive() || !typeValue.getAsJsonPrimitive().isString()) return node;
		String type = typeValue.getAsString();
		String seed = "{\"type\":" + typeValue + "}";
		Node selector = id("效果种类", "consume_effect_type", type);
		return switch (type) {
			case "minecraft:remove_effects" -> object("移除状态效果", seed.substring(0, seed.length() - 1) + ",\"effects\":[]}", "type", selector,
					"effects", array("要移除的效果", id("状态效果", "mob_effect", "minecraft:speed")));
			case "minecraft:clear_all_effects" -> object("清除所有状态效果", seed, "type", selector);
			case "minecraft:teleport_randomly" -> object("随机传送", seed, "type", selector, "diameter", number("传送直径", 16));
			case "minecraft:play_sound" -> object("播放声音", seed.substring(0, seed.length() - 1) + ",\"sound\":\"minecraft:entity.generic.eat\"}", "type", selector,
					"sound", id("声音", "sound_event", "minecraft:entity.generic.eat"));
			case "minecraft:apply_effects" -> object("施加状态效果", seed.substring(0, seed.length() - 1) + ",\"effects\":[]}", "type", selector,
					"effects", array("状态效果", effect()), "probability", number("概率（0～1）", 1));
			default -> {
				Node inferred = observed("自定义使用效果", actual);
				Map<String, Node> fields = new LinkedHashMap<>(inferred.fields); fields.put("type", selector);
				yield new Node(inferred.label, inferred.kind, inferred.value(), fields, inferred.element, inferred.choices, inferred.registry, true);
			}
		};
	}
	private static Node str(String label, String value) { return scalar(label, "string", new JsonPrimitive(value).toString()); }
	private static Node integer(String label, int value) { return scalar(label, "integer", Integer.toString(value)); }
	private static Node number(String label, double value) { return scalar(label, "number", Double.toString(value)); }
	private static Node bool(String label, boolean value) { return scalar(label, "boolean", Boolean.toString(value)); }
	private static Node id(String label, String registry, String value) { return new Node(label, "string", new JsonPrimitive(value), Map.of(), null, List.of(), "minecraft:" + registry, false); }
	private static Node array(String label, Node entry) { return new Node(label, "array", new JsonArray(), Map.of(), entry, List.of(), "", false); }
	private static Node object(String label, String seed, Object... pairs) {
		Map<String, Node> fields = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], (Node) pairs[i + 1]);
		return new Node(label, "object", JsonParser.parseString(seed), fields, null, List.of(), "", false);
	}
	private static Node map(String label, Node entry) { return new Node(label, "object", new JsonObject(), Map.of(), entry, List.of(), "", true); }
	private static Node components() { return new Node("物品内部组件", "components", new JsonObject(), Map.of(), null, List.of(), "", true); }
	private static Node stack() { return object("物品堆", "{\"id\":\"minecraft:stone\",\"count\":1}", "id", id("物品", "item", "minecraft:stone"), "count", integer("数量", 1), "components", components()); }
	public static Node itemStack() { return stack(); }
	public static Node textComponent() { return text(); }
	public static Node itemComponents() {
		return new Node("物品内部组件", "object", new JsonObject(), BUILTINS, null, List.of(), "", true);
	}
	private static Node effect() { return object("状态效果", "{\"id\":\"minecraft:speed\",\"duration\":200}",
			"id", id("效果", "mob_effect", "minecraft:speed"), "amplifier", integer("等级（0 为 I 级）", 0), "duration", integer("持续时间（刻）", 200),
			"ambient", bool("环境效果", false), "show_particles", bool("显示粒子", true), "show_icon", bool("显示图标", true)); }
	private static Node text() { return object("文本", "{\"text\":\"文字\"}", "text", str("文字内容", "文字"), "color", str("颜色（名称或 #RRGGBB）", "white"),
			"bold", bool("粗体", false), "italic", bool("斜体", false), "underlined", bool("下划线", false), "strikethrough", bool("删除线", false), "obfuscated", bool("乱码效果", false)); }

	private static final Map<String, Node> BUILTINS = builtins();
	private static Map<String, Node> builtins() {
		Map<String, Node> map = new LinkedHashMap<>();
		map.put("custom_data", map("自定义数据", null));
		map.put("custom_name", text()); map.put("item_name", text()); map.put("lore", array("物品描述", text()));
		map.put("max_stack_size", integer("最大堆叠数量", 64)); map.put("max_damage", integer("最大耐久", 100));
		map.put("damage", integer("已损耗耐久", 0)); map.put("repair_cost", integer("修复惩罚", 0));
		map.put("enchantment_glint_override", bool("附魔光效", true));
		map.put("rarity", choices("稀有度", "common", "common", "uncommon", "rare", "epic"));
		for (String unit : List.of("unbreakable", "glider", "intangible_projectile")) map.put(unit, object("无参数组件", "{}"));
		for (String ench : List.of("enchantments", "stored_enchantments")) map.put(ench, map("附魔（ID → 等级）", integer("附魔等级", 1)));
		map.put("potion_contents", object("药水内容", "{}", "potion", id("预设药水", "potion", "minecraft:water"), "custom_color", integer("自定义颜色（RGB 整数）", 16711680),
				"custom_effects", array("自定义效果", effect()), "custom_name", str("自定义药水名称", "自定义药水")));
		map.put("suspicious_stew_effects", array("谜之炖菜效果", object("效果", "{\"id\":\"minecraft:night_vision\",\"duration\":160}",
				"id", id("效果", "mob_effect", "minecraft:night_vision"), "duration", integer("持续时间（刻）", 160))));
		map.put("container", array("容器内容", object("容器槽位", "{\"slot\":0,\"item\":{\"id\":\"minecraft:stone\",\"count\":1}}",
				"slot", integer("槽位（从 0 开始）", 0), "item", stack())));
		map.put("bundle_contents", array("收纳袋物品", stack())); map.put("charged_projectiles", array("已装填弹药", stack())); map.put("use_remainder", stack());
		map.put("food", object("食物属性", "{\"nutrition\":1,\"saturation\":0.6}", "nutrition", integer("恢复饥饿值", 1),
				"saturation", number("恢复饱和度", 0.6), "can_always_eat", bool("饥饿值满时也能食用", false)));
		Node consume = object("食用触发效果", "{\"type\":\"minecraft:apply_effects\",\"effects\":[]}",
				"type", id("效果种类", "consume_effect_type", "minecraft:apply_effects"), "effects", array("状态效果", effect()), "probability", number("概率（0～1）", 1));
		map.put("consumable", object("食用/使用行为", "{}", "consume_seconds", number("使用时长（秒）", 1.6),
				"animation", choices("使用动画", "eat", "none", "eat", "drink", "block", "bow", "spear", "crossbow", "spyglass", "toot_horn", "brush", "bundle"),
				"sound", id("使用声音", "sound_event", "minecraft:entity.generic.eat"), "has_consume_particles", bool("显示使用粒子", true), "on_consume_effects", array("使用后效果", consume)));
		map.put("death_protection", object("死亡保护", "{}", "death_effects", array("触发效果", consume)));
		map.put("tool", object("工具规则", "{\"rules\":[]}", "rules", array("挖掘规则", object("规则", "{\"blocks\":\"minecraft:stone\"}",
				"blocks", id("方块或 #标签", "block", "minecraft:stone"), "speed", number("挖掘速度", 1), "correct_for_drops", bool("允许正确掉落", true))),
				"default_mining_speed", number("默认挖掘速度", 1), "damage_per_block", integer("每次挖掘消耗耐久", 1), "can_destroy_blocks_in_creative", bool("创造模式允许破坏", true)));
		Node blockCondition = object("方块条件", "{\"blocks\":\"minecraft:stone\"}", "blocks", id("方块或 #标签", "block", "minecraft:stone"),
				"state", map("方块状态条件", str("匹配状态值", "")), "nbt", map("方块实体数据条件", null));
		for (String key : List.of("can_break", "can_place_on")) {
			JsonArray initial = new JsonArray(); initial.add(blockCondition.value());
			map.put(key, new Node(key.equals("can_break") ? "可破坏的方块条件" : "允许放置的方块条件", "array", initial,
					Map.of(), blockCondition, List.of(), "", false));
		}
		map.put("attribute_modifiers", array("属性修饰符", object("修饰符", "{\"type\":\"minecraft:attack_damage\",\"id\":\"minecraft:custom\",\"amount\":1,\"operation\":\"add_value\"}",
				"type", id("属性", "attribute", "minecraft:attack_damage"), "id", str("自定义修饰符 ID", "minecraft:custom"), "amount", number("数值", 1),
				"operation", choices("运算", "add_value", "add_value", "add_multiplied_base", "add_multiplied_total"),
				"slot", choices("生效槽位", "any", "any", "mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "body", "saddle"))));
		map.put("equippable", object("装备属性", "{\"slot\":\"head\"}", "slot", choices("装备槽位", "head", "mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle"),
				"equip_sound", id("装备声音", "sound_event", "minecraft:item.armor.equip_generic"), "asset_id", str("装备模型资源", "minecraft:diamond"),
				"allowed_entities", array("允许装备的实体", id("实体", "entity_type", "minecraft:player")), "dispensable", bool("可由发射器装备", true),
				"swappable", bool("允许快捷替换", true), "damage_on_hurt", bool("受伤时消耗耐久", true)));
		map.put("repairable", object("修复材料", "{\"items\":[]}", "items", array("修复物品", id("物品", "item", "minecraft:diamond"))));
		map.put("trim", object("盔甲纹饰", "{\"material\":\"minecraft:gold\",\"pattern\":\"minecraft:sentry\"}",
				"material", id("纹饰材料", "trim_material", "minecraft:gold"), "pattern", id("纹饰图案", "trim_pattern", "minecraft:sentry")));
		map.put("custom_model_data", object("自定义模型数据", "{}", "floats", array("浮点数", number("数值", 0)), "flags", array("布尔值", bool("开关", false)), "strings", array("字符串", str("文本", "")), "colors", array("颜色", integer("RGB 整数", 16777215))));
		map.put("tooltip_display", object("提示信息", "{}", "hide_tooltip", bool("隐藏整个提示框", false), "hidden_components", array("隐藏的组件", id("组件", "data_component_type", "minecraft:enchantments"))));
		map.put("use_cooldown", object("使用冷却", "{\"seconds\":1}", "seconds", number("冷却秒数", 1), "cooldown_group", str("自定义冷却组 ID", "minecraft:custom")));
		map.put("container_loot", object("容器战利品", "{\"loot_table\":\"minecraft:chests/simple_dungeon\"}", "loot_table", id("战利品表", "loot_table", "minecraft:chests/simple_dungeon"), "seed", integer("随机种子", 0)));
		map.put("block_state", map("方块状态", str("状态值", "")));
		map.put("entity_data", map("实体数据", null)); map.put("block_entity_data", map("方块实体数据", null)); map.put("bucket_entity_data", map("桶中实体数据", null));
		map.put("profile", object("玩家档案", "{}", "name", str("玩家名称", "Steve"), "properties", array("皮肤属性", object("属性", "{\"name\":\"textures\",\"value\":\"\"}", "name", str("属性名", "textures"), "value", str("属性值", ""), "signature", str("签名", "")))));
		map.put("banner_patterns", array("旗帜图案", object("图案层", "{\"pattern\":\"minecraft:base\",\"color\":\"white\"}", "pattern", id("图案", "banner_pattern", "minecraft:base"), "color", choices("染料颜色", "white", "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"))));
		map.put("pot_decorations", array("饰纹陶罐装饰", id("物品", "item", "minecraft:brick")));
		for (String key : List.of("instrument", "jukebox_playable", "damage_type", "provides_trim_material")) {
			String registry = switch (key) { case "jukebox_playable" -> "jukebox_song"; case "provides_trim_material" -> "trim_material"; default -> key; };
			String initial = switch (key) { case "instrument" -> "ponder_goat_horn"; case "jukebox_playable" -> "13"; case "provides_trim_material" -> "gold"; default -> "generic"; };
			map.put(key, id(key, registry, "minecraft:" + initial));
		}
		Map<String, Node> result = new LinkedHashMap<>(); map.forEach((key, node) -> result.put("minecraft:" + key, node)); return Map.copyOf(result);
	}
}
