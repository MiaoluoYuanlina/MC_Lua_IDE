package wiki.ica.xiaomiao.mcluaide.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Public extension API for registry and component-editor metadata.
 *
 * <p>The actual registry contents are always read from the running Minecraft server. This API only
 * adds human-readable annotations and tells the component editor which registry a JSON field uses.
 * Third-party mods should register their metadata from their common initializer.</p>
 */
public final class McLuaIdeRegistryApi {
	public static final int EXTENSION_API_VERSION = 1;
	private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
	private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(?:_[a-z]{2})?");
	private static final Map<String, Annotation> REGISTRIES = new LinkedHashMap<>();
	private static final Map<String, Annotation> ENTRIES = new LinkedHashMap<>();
	private static final Map<String, List<ComponentHint>> COMPONENT_HINTS = new LinkedHashMap<>();

	static {
		registerBuiltins();
	}

	private McLuaIdeRegistryApi() {
	}

	/** Starts an annotation for one registry type, for example {@code example_mod:spell}. */
	public static AnnotationBuilder registry(String ownerModId, String registryId) {
		return new AnnotationBuilder(ownerModId, resourceId(registryId, "注册表 ID"), null);
	}

	/** Starts an annotation for one concrete entry in a registry. */
	public static AnnotationBuilder entry(String ownerModId, String registryId, String entryId) {
		return new AnnotationBuilder(ownerModId, resourceId(registryId, "注册表 ID"),
				resourceId(entryId, "条目 ID"));
	}

	/**
	 * Starts an ID-type hint for a component JSON field. Paths use JSON-pointer-like segments and
	 * accept {@code *} as one object key or array-index wildcard. An empty path addresses the root.
	 */
	public static ComponentHintBuilder componentHint(String ownerModId, String componentId,
			String pathPattern, Target target, String registryId) {
		return new ComponentHintBuilder(ownerModId, componentId, pathPattern, target, registryId);
	}

	/** Registers an annotation built by {@link #registry} or {@link #entry}. */
	public static synchronized void register(Annotation annotation) {
		Objects.requireNonNull(annotation, "annotation");
		String ownedId = annotation.entryId() == null ? annotation.registryId() : annotation.entryId();
		requireOwned(annotation.ownerModId(), ownedId);
		Map<String, Annotation> target = annotation.entryId() == null ? REGISTRIES : ENTRIES;
		String key = annotation.entryId() == null ? annotation.registryId()
				: entryKey(annotation.registryId(), annotation.entryId());
		if (target.putIfAbsent(key, annotation) != null) {
			throw new IllegalArgumentException("ID 注释已注册：" + ownedId);
		}
	}

	/** Registers a component-field registry hint. */
	public static synchronized void register(ComponentHint hint) {
		Objects.requireNonNull(hint, "hint");
		requireOwned(hint.ownerModId(), hint.componentId());
		List<ComponentHint> hints = COMPONENT_HINTS.computeIfAbsent(hint.componentId(), ignored -> new ArrayList<>());
		boolean duplicate = hints.stream().anyMatch(value -> value.pathPattern().equals(hint.pathPattern())
				&& value.target() == hint.target());
		if (duplicate) throw new IllegalArgumentException("组件字段 ID 提示已注册："
				+ hint.componentId() + " " + hint.pathPattern() + " " + hint.target());
		hints.add(hint);
	}

	/** Runtime snapshot consumed by MC Lua IDE. */
	public static synchronized Annotation registryAnnotation(String registryId) {
		return REGISTRIES.get(registryId);
	}

	/** Runtime snapshot consumed by MC Lua IDE. */
	public static synchronized Annotation entryAnnotation(String registryId, String entryId) {
		return ENTRIES.get(entryKey(registryId, entryId));
	}

	/** Runtime snapshot consumed by MC Lua IDE. */
	public static synchronized List<ComponentHint> componentHints(String componentId) {
		return List.copyOf(COMPONENT_HINTS.getOrDefault(componentId, List.of()));
	}

	public enum Target {
		KEY,
		VALUE
	}

	public record Text(String label, String description) {
		public Text {
			label = boundedRequired(label, "显示名称", 128);
			description = bounded(description, 512);
		}
	}

	public record Annotation(String ownerModId, String registryId, String entryId,
			Map<String, Text> translations) {
		public Annotation {
			requireModId(ownerModId);
			registryId = resourceId(registryId, "注册表 ID");
			if (entryId != null) entryId = resourceId(entryId, "条目 ID");
			translations = immutableTranslations(translations);
		}

		public Text translation(String requestedLocale) {
			return localized(translations, requestedLocale);
		}
	}

	public record ComponentHint(String ownerModId, String componentId, String pathPattern,
			Target target, String registryId, Map<String, Text> translations) {
		public ComponentHint {
			requireModId(ownerModId);
			componentId = resourceId(componentId, "组件 ID");
			pathPattern = normalizePath(pathPattern);
			target = Objects.requireNonNull(target, "target");
			registryId = resourceId(registryId, "注册表 ID");
			translations = immutableTranslations(translations);
		}

		public Text translation(String requestedLocale) {
			return localized(translations, requestedLocale);
		}
	}

	public static final class AnnotationBuilder {
		private final String ownerModId;
		private final String registryId;
		private final String entryId;
		private final Map<String, Text> translations = new LinkedHashMap<>();

		private AnnotationBuilder(String ownerModId, String registryId, String entryId) {
			requireModId(ownerModId);
			this.ownerModId = ownerModId;
			this.registryId = registryId;
			this.entryId = entryId;
		}

		public AnnotationBuilder translation(String locale, String label, String description) {
			putTranslation(translations, locale, new Text(label, description));
			return this;
		}

		public Annotation build() {
			return new Annotation(ownerModId, registryId, entryId, translations);
		}

		public void register() {
			McLuaIdeRegistryApi.register(build());
		}
	}

	public static final class ComponentHintBuilder {
		private final String ownerModId;
		private final String componentId;
		private final String pathPattern;
		private final Target target;
		private final String registryId;
		private final Map<String, Text> translations = new LinkedHashMap<>();

		private ComponentHintBuilder(String ownerModId, String componentId, String pathPattern,
				Target target, String registryId) {
			requireModId(ownerModId);
			this.ownerModId = ownerModId;
			this.componentId = resourceId(componentId, "组件 ID");
			this.pathPattern = normalizePath(pathPattern);
			this.target = Objects.requireNonNull(target, "target");
			this.registryId = resourceId(registryId, "注册表 ID");
		}

		public ComponentHintBuilder translation(String locale, String label, String description) {
			putTranslation(translations, locale, new Text(label, description));
			return this;
		}

		public ComponentHint build() {
			return new ComponentHint(ownerModId, componentId, pathPattern, target, registryId, translations);
		}

		public void register() {
			McLuaIdeRegistryApi.register(build());
		}
	}

	private static void registerBuiltins() {
		builtinRegistry("item", "物品 ID", "全部可用物品，包括其他模组注册的物品");
		builtinRegistry("block", "方块 ID", "全部可用方块，包括其他模组注册的方块");
		builtinRegistry("entity_type", "实体类型 ID", "可生成、检测或控制的实体类型");
		builtinRegistry("mob_effect", "状态效果 ID", "药水与生物状态效果");
		builtinRegistry("enchantment", "附魔 ID", "物品可用的附魔类型");
		builtinRegistry("timeline", "时间轴 ID", "时间轴定义，例如 day、moon 与 villager_schedule");
		builtinRegistry("world_clock", "世界时钟 ID", "维度使用的世界时钟定义，例如 overworld");
		builtinRegistry("biome", "生物群系 ID", "世界中的生物群系");
		builtinRegistry("dimension", "维度 ID", "服务器当前加载的维度");
		builtinRegistry("dimension_type", "维度类型 ID", "维度环境与时间规则类型");
		builtinRegistry("attribute", "属性 ID", "实体与玩家属性");
		builtinRegistry("potion", "药水 ID", "预设药水内容");
		builtinRegistry("sound_event", "声音事件 ID", "客户端可播放的声音事件");
		builtinRegistry("particle_type", "粒子类型 ID", "可生成的粒子类型");
		builtinRegistry("fluid", "流体 ID", "水、熔岩及模组流体");
		builtinRegistry("recipe", "配方 ID", "服务器数据包与模组提供的配方");
		builtinRegistry("data_component_type", "物品堆组件类型 ID", "物品堆可保存的数据组件类型");
		builtinRegistry("game_event", "游戏事件 ID", "幽匿传感器等系统使用的游戏事件");
		builtinRegistry("damage_type", "伤害类型 ID", "伤害来源的规则与显示类型");
		builtinRegistry("loot_table", "战利品表 ID", "数据包与模组提供的战利品表");
		builtinRegistry("instrument", "乐器 ID", "山羊角等物品使用的乐器声音");
		builtinRegistry("jukebox_song", "唱片歌曲 ID", "唱片机可播放的歌曲");
		builtinRegistry("painting_variant", "画变种 ID", "画的图案与尺寸变种");
		builtinRegistry("banner_pattern", "旗帜图案 ID", "旗帜、盾牌与旗帜图案组件可用的图案");
		builtinRegistry("trim_material", "纹饰材料 ID", "盔甲纹饰使用的材料");
		builtinRegistry("trim_pattern", "纹饰图案 ID", "盔甲纹饰使用的图案");
		builtinRegistry("map_decoration_type", "地图标记类型 ID", "地图组件中的图标与标记类型");
		builtinRegistry("consume_effect_type", "食用效果类型 ID", "食用或死亡保护组件执行的效果类型");
		builtinRegistry("villager_type", "村民变种 ID", "村民所在生物群系对应的变种");
		builtinRegistry("wolf_variant", "狼变种 ID", "狼的外观变种");
		builtinRegistry("wolf_sound_variant", "狼声音变种 ID", "狼的声音变种");
		builtinRegistry("pig_variant", "猪变种 ID", "猪的外观变种");
		builtinRegistry("pig_sound_variant", "猪声音变种 ID", "猪的声音变种");
		builtinRegistry("cow_variant", "牛变种 ID", "牛的外观变种");
		builtinRegistry("cow_sound_variant", "牛声音变种 ID", "牛的声音变种");
		builtinRegistry("chicken_variant", "鸡变种 ID", "鸡的外观变种");
		builtinRegistry("chicken_sound_variant", "鸡声音变种 ID", "鸡的声音变种");
		builtinRegistry("zombie_nautilus_variant", "僵尸鹦鹉螺变种 ID", "僵尸鹦鹉螺的外观变种");
		builtinRegistry("frog_variant", "青蛙变种 ID", "青蛙的外观变种");
		builtinRegistry("cat_variant", "猫变种 ID", "猫的外观变种");
		builtinRegistry("cat_sound_variant", "猫声音变种 ID", "猫的声音变种");
		builtinEntry("timeline", "day", "昼夜时间轴", "主世界一天内的时间标记与循环");
		builtinEntry("world_clock", "overworld", "主世界时钟", "主世界维度使用的昼夜时钟");
		builtinEntry("item", "apple", "苹果", "常用食物物品，可直接用于物品堆 ID");
		builtinEntry("item", "diamond", "钻石", "常用合成材料物品");
		builtinEntry("item", "diamond_sword", "钻石剑", "常用武器物品");
		builtinEntry("block", "stone", "石头", "常用基础方块");
		builtinEntry("block", "command_block", "命令方块", "可执行命令的管理员方块");
		builtinEntry("mob_effect", "dolphins_grace", "海豚的恩惠", "提高玩家在水中的游泳速度");
		builtinEntry("mob_effect", "speed", "速度", "提高实体移动速度");
		builtinEntry("mob_effect", "regeneration", "生命恢复", "持续恢复生命值");
		builtinEntry("mob_effect", "strength", "力量", "提高近战攻击伤害");
		builtinEntry("enchantment", "sharpness", "锋利", "提高近战伤害的常用附魔");
		builtinEntry("enchantment", "unbreaking", "耐久", "降低物品消耗耐久的概率");
		builtinEntry("enchantment", "mending", "经验修补", "使用经验修复物品耐久");
		builtinEntry("enchantment", "efficiency", "效率", "提高工具挖掘速度");

		builtinHint("enchantments", "/*", Target.KEY, "enchantment", "附魔 ID");
		builtinHint("stored_enchantments", "/*", Target.KEY, "enchantment", "附魔 ID");
		builtinHint("potion_contents", "/potion", Target.VALUE, "potion", "药水 ID");
		builtinHint("potion_contents", "/custom_effects/*/id", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("container", "/*/id", Target.VALUE, "item", "物品 ID");
		builtinHint("container", "/*/item/id", Target.VALUE, "item", "物品 ID");
		builtinHint("bundle_contents", "/*/id", Target.VALUE, "item", "物品 ID");
		builtinHint("charged_projectiles", "/*/id", Target.VALUE, "item", "物品 ID");
		builtinHint("attribute_modifiers", "/*/type", Target.VALUE, "attribute", "属性 ID");
		builtinCatalogHint("attribute_modifiers", "/*/id", Target.VALUE,
				"mc_lua_ide:attribute_modifier_id", "属性修饰符 ID");
		builtinHint("equippable", "/allowed_entities/*", Target.VALUE, "entity_type", "实体类型 ID");
		builtinHint("repairable", "/items/*", Target.VALUE, "item", "物品 ID");
		builtinHint("trim", "/material", Target.VALUE, "trim_material", "纹饰材料 ID");
		builtinHint("trim", "/pattern", Target.VALUE, "trim_pattern", "纹饰图案 ID");
		builtinHint("instrument", "", Target.VALUE, "instrument", "乐器 ID");
		builtinHint("jukebox_playable", "/song", Target.VALUE, "jukebox_song", "唱片歌曲 ID");
		builtinHint("recipes", "/*", Target.VALUE, "recipe", "配方 ID");

		// Root resource/holder components. These used to be plain text fields and therefore had no
		// searchable candidate list even though their codec requires a resource identifier.
		builtinHint("damage_type", "", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinCatalogHint("item_model", "", Target.VALUE, "mc_lua_ide:item_model_asset", "物品模型资源 ID");
		builtinCatalogHint("tooltip_style", "", Target.VALUE, "mc_lua_ide:tooltip_style_asset", "提示框样式 ID");
		builtinHint("provides_trim_material", "", Target.VALUE, "trim_material", "纹饰材料 ID");
		builtinHint("jukebox_playable", "", Target.VALUE, "jukebox_song", "唱片歌曲 ID");
		builtinHint("break_sound", "", Target.VALUE, "sound_event", "声音 ID");
		builtinHint("note_block_sound", "", Target.VALUE, "sound_event", "声音 ID");
		builtinHint("villager_variant", "", Target.VALUE, "villager_type", "村民变种 ID");
		builtinHint("wolf_variant", "", Target.VALUE, "wolf_variant", "狼变种 ID");
		builtinHint("wolf_sound_variant", "", Target.VALUE, "wolf_sound_variant", "狼声音变种 ID");
		builtinHint("pig_variant", "", Target.VALUE, "pig_variant", "猪变种 ID");
		builtinHint("pig_sound_variant", "", Target.VALUE, "pig_sound_variant", "猪声音变种 ID");
		builtinHint("cow_variant", "", Target.VALUE, "cow_variant", "牛变种 ID");
		builtinHint("cow_sound_variant", "", Target.VALUE, "cow_sound_variant", "牛声音变种 ID");
		builtinHint("chicken_variant", "", Target.VALUE, "chicken_variant", "鸡变种 ID");
		builtinHint("chicken_sound_variant", "", Target.VALUE, "chicken_sound_variant", "鸡声音变种 ID");
		builtinHint("zombie_nautilus_variant", "", Target.VALUE, "zombie_nautilus_variant", "僵尸鹦鹉螺变种 ID");
		builtinHint("frog_variant", "", Target.VALUE, "frog_variant", "青蛙变种 ID");
		builtinHint("painting_variant", "", Target.VALUE, "painting_variant", "画变种 ID");
		builtinHint("cat_variant", "", Target.VALUE, "cat_variant", "猫变种 ID");
		builtinHint("cat_sound_variant", "", Target.VALUE, "cat_sound_variant", "猫声音变种 ID");
		builtinHint("provides_banner_patterns", "/*", Target.VALUE, "banner_pattern", "旗帜图案 ID");

		// Nested vanilla component fields that contain registry-backed identifiers.
		builtinHint("tooltip_display", "/hidden_components/*", Target.VALUE, "data_component_type", "组件类型 ID");
		builtinHint("consumable", "/sound", Target.VALUE, "sound_event", "声音 ID");
		builtinHint("consumable", "/on_consume_effects/*/type", Target.VALUE, "consume_effect_type", "食用效果类型 ID");
		builtinHint("consumable", "/on_consume_effects/*/effects/*/id", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("consumable", "/on_consume_effects/*/effects/*", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("use_remainder", "/convert_into/id", Target.VALUE, "item", "物品 ID");
		builtinHint("use_remainder", "/id", Target.VALUE, "item", "物品 ID");
		builtinHint("damage_resistant", "/types", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinHint("damage_resistant", "/types/*", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinHint("tool", "/rules/*/blocks", Target.VALUE, "block", "方块 ID");
		builtinHint("tool", "/rules/*/blocks/*", Target.VALUE, "block", "方块 ID");
		builtinHint("equippable", "/equip_sound", Target.VALUE, "sound_event", "装备声音 ID");
		builtinHint("equippable", "/shearing_sound", Target.VALUE, "sound_event", "剪除声音 ID");
		builtinCatalogHint("equippable", "/asset_id", Target.VALUE,
				"mc_lua_ide:equipment_asset", "装备模型资源 ID");
		builtinCatalogHint("equippable", "/camera_overlay", Target.VALUE,
				"mc_lua_ide:texture_asset", "镜头覆盖纹理 ID");
		builtinHint("use_cooldown", "/cooldown_group", Target.VALUE, "item", "冷却组 ID（通常使用物品 ID）");
		builtinHint("debug_stick_state", "/*", Target.KEY, "block", "方块 ID");
		builtinHint("repairable", "/items", Target.VALUE, "item", "物品 ID");
		builtinHint("repairable", "/items/*", Target.VALUE, "item", "物品 ID");
		builtinHint("death_protection", "/death_effects/*/type", Target.VALUE, "consume_effect_type", "死亡保护效果类型 ID");
		builtinHint("death_protection", "/death_effects/*/effects/*/id", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("death_protection", "/death_effects/*/effects/*", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("blocks_attacks", "/damage_reductions/*/type", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinHint("blocks_attacks", "/damage_reductions/*/type/*", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinHint("blocks_attacks", "/bypassed_by", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinHint("blocks_attacks", "/bypassed_by/*", Target.VALUE, "damage_type", "伤害类型 ID");
		builtinHint("blocks_attacks", "/block_sound", Target.VALUE, "sound_event", "格挡声音 ID");
		builtinHint("blocks_attacks", "/disable_sound", Target.VALUE, "sound_event", "破盾声音 ID");
		builtinHint("kinetic_weapon", "/sound", Target.VALUE, "sound_event", "声音 ID");
		builtinHint("kinetic_weapon", "/hit_sound", Target.VALUE, "sound_event", "命中声音 ID");
		builtinHint("suspicious_stew_effects", "/*/id", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("suspicious_stew_effects", "/*/effect", Target.VALUE, "mob_effect", "状态效果 ID");
		builtinHint("map_decorations", "/*/type", Target.VALUE, "map_decoration_type", "地图标记类型 ID");
		builtinHint("lodestone_tracker", "/target/dimension", Target.VALUE, "dimension", "维度 ID");
		builtinHint("container_loot", "/loot_table", Target.VALUE, "loot_table", "战利品表 ID");
		builtinHint("banner_patterns", "/*/pattern", Target.VALUE, "banner_pattern", "旗帜图案 ID");
		builtinHint("pot_decorations", "/*", Target.VALUE, "item", "物品 ID");
		builtinHint("bees", "/*/entity_data/id", Target.VALUE, "entity_type", "实体 ID");
	}

	private static void builtinRegistry(String path, String label, String description) {
		REGISTRIES.put("minecraft:" + path, builtinAnnotation("minecraft:" + path, null, label, description));
	}

	private static void builtinEntry(String registryPath, String entryPath, String label, String description) {
		String registry = "minecraft:" + registryPath;
		String entry = "minecraft:" + entryPath;
		ENTRIES.put(entryKey(registry, entry), builtinAnnotation(registry, entry, label, description));
	}

	private static void builtinHint(String componentPath, String path, Target target,
			String registryPath, String label) {
		builtinCatalogHint(componentPath, path, target, "minecraft:" + registryPath, label);
	}

	private static void builtinCatalogHint(String componentPath, String path, Target target,
			String catalogId, String label) {
		String component = "minecraft:" + componentPath;
		ComponentHint hint = new ComponentHint("mc_lua_ide", component, path, target,
				catalogId, Map.of("zh_cn", new Text(label,
						catalogId.startsWith("mc_lua_ide:")
								? "按管理员客户端当前启用的原版、资源包与 Mod 资源筛选并插入"
								: "按当前服务器注册表筛选并插入")));
		COMPONENT_HINTS.computeIfAbsent(component, ignored -> new ArrayList<>()).add(hint);
	}

	private static Annotation builtinAnnotation(String registryId, String entryId,
			String label, String description) {
		return new Annotation("mc_lua_ide", registryId, entryId,
				Map.of("zh_cn", new Text(label, description)));
	}

	private static String entryKey(String registryId, String entryId) {
		return registryId + '\0' + entryId;
	}

	private static void requireOwned(String ownerModId, String resourceId) {
		String namespace = resourceId.substring(0, resourceId.indexOf(':'));
		if (!ownerModId.equals("mc_lua_ide") && !namespace.equals(ownerModId)) {
			throw new IllegalArgumentException("模组 " + ownerModId + " 只能注册自己命名空间的注释：" + resourceId);
		}
	}

	private static String normalizePath(String raw) {
		String value = raw == null ? "" : raw.strip();
		if (value.isEmpty()) return "";
		if (!value.startsWith("/") || value.endsWith("/")) {
			throw new IllegalArgumentException("字段路径必须为空或形如 /field/*/id：" + raw);
		}
		for (String segment : value.substring(1).split("/", -1)) {
			if (segment.isEmpty() || (!segment.equals("*") && !segment.matches("[A-Za-z0-9_.:-]+"))) {
				throw new IllegalArgumentException("字段路径包含无效片段：" + raw);
			}
		}
		return value;
	}

	private static Map<String, Text> immutableTranslations(Map<String, Text> source) {
		if (source == null || source.isEmpty()) throw new IllegalArgumentException("至少需要一种注释翻译");
		Map<String, Text> result = new LinkedHashMap<>();
		source.forEach((locale, text) -> putTranslation(result, locale, text));
		return Collections.unmodifiableMap(result);
	}

	private static void putTranslation(Map<String, Text> target, String locale, Text text) {
		String normalized = normalizeLocale(locale);
		if (!LOCALE.matcher(normalized).matches()) throw new IllegalArgumentException("无效语言代码：" + locale);
		if (target.putIfAbsent(normalized, Objects.requireNonNull(text, "text")) != null) {
			throw new IllegalArgumentException("翻译重复：" + normalized);
		}
	}

	private static Text localized(Map<String, Text> translations, String locale) {
		String normalized = normalizeLocale(locale);
		Text exact = translations.get(normalized);
		if (exact != null) return exact;
		int underscore = normalized.indexOf('_');
		String language = underscore < 0 ? normalized : normalized.substring(0, underscore);
		for (Map.Entry<String, Text> entry : translations.entrySet()) {
			if (entry.getKey().equals(language) || entry.getKey().startsWith(language + "_")) return entry.getValue();
		}
		Text english = translations.get("en_us");
		return english != null ? english : translations.values().iterator().next();
	}

	private static String resourceId(String value, String label) {
		String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		if (!RESOURCE_ID.matcher(normalized).matches()) throw new IllegalArgumentException(label + " 无效：" + value);
		return normalized;
	}

	private static void requireModId(String value) {
		if (value == null || !MOD_ID.matcher(value).matches()) throw new IllegalArgumentException("无效模组 ID：" + value);
	}

	private static String normalizeLocale(String locale) {
		return locale == null ? "" : locale.strip().replace('-', '_').toLowerCase(Locale.ROOT);
	}

	private static String boundedRequired(String value, String label, int maximum) {
		String result = bounded(value, maximum);
		if (result.isBlank()) throw new IllegalArgumentException(label + "不能为空");
		return result;
	}

	private static String bounded(String value, int maximum) {
		String result = value == null ? "" : value;
		if (result.length() > maximum) throw new IllegalArgumentException("注释字段不能超过 " + maximum + " 个字符");
		return result;
	}
}
