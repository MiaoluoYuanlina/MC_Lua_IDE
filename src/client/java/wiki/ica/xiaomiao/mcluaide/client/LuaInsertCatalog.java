package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runtime registry snapshots used by the IDE resource-ID picker. */
final class LuaInsertCatalog {
	private LuaInsertCatalog() {
	}

	static List<Entry> load(Kind kind, Minecraft client) {
		List<Entry> entries = switch (kind) {
			case ITEM -> registryEntries(BuiltInRegistries.ITEM.keySet(), id -> {
				var item = BuiltInRegistries.ITEM.getValue(id);
				if (item == null) return "";
				try { return item.getName(item.getDefaultInstance()).getString(); }
				catch (RuntimeException unboundComponents) {
					// 26.1 binds item components on joining a world. Keep localized search available before then.
					return item instanceof net.minecraft.world.item.BlockItem blockItem
							? Component.translatable(blockItem.getBlock().getDescriptionId()).getString()
							: Component.translatable("item." + id.getNamespace() + "." + id.getPath().replace('/', '.')).getString();
				}
			});
			case BLOCK -> registryEntries(BuiltInRegistries.BLOCK.keySet(), id -> {
				var block = BuiltInRegistries.BLOCK.getValue(id);
				return block == null ? "" : Component.translatable(block.getDescriptionId()).getString();
			});
			case ENTITY -> registryEntries(BuiltInRegistries.ENTITY_TYPE.keySet(), id -> {
				var type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
				return type == null ? "" : type.getDescription().getString();
			});
			case PLAYER -> playerEntries(client);
			case DIMENSION -> dimensionEntries(client);
			case GAME_RULE -> registryEntries(BuiltInRegistries.GAME_RULE.keySet(), LuaInsertCatalog::gameRuleName);
			case SOUND -> LuaResourceAssetCatalog.sounds(client);
			default -> {
				List<Entry> local = kind.resource() ? LuaResourceAssetCatalog.load(kind.registryId(), client) : null;
				yield local == null ? List.of() : local;
			}
		};
		return entries.stream().sorted(Comparator.comparing(Entry::value, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	private static List<Entry> registryEntries(Iterable<Identifier> identifiers, NameResolver resolver) {
		List<Entry> result = new ArrayList<>();
		for (Identifier id : identifiers) {
			String name;
			try {
				name = resolver.resolve(id);
			} catch (RuntimeException ignored) {
				name = "";
			}
			result.add(new Entry(id.toString(), name, id.getNamespace()));
		}
		return result;
	}

	private static List<Entry> playerEntries(Minecraft client) {
		if (client.getConnection() == null) return List.of();
		List<Entry> result = new ArrayList<>();
		for (var info : client.getConnection().getOnlinePlayers()) {
			var profile = info.getProfile();
			result.add(new Entry(profile.id().toString(), profile.name(), "online"));
		}
		return result;
	}

	static List<TypedEntry> allEntries(java.util.Map<Kind, List<Entry>> catalogs) {
		List<TypedEntry> result = new ArrayList<>();
		for (Kind kind : Kind.values()) {
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (Entry entry : catalogs.getOrDefault(kind, List.of()))
				if (seen.add(entry.value())) result.add(new TypedEntry(kind, entry));
		}
		return result.stream().sorted(Comparator.comparing(TypedEntry::value, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(entry -> entry.kind().ordinal())).toList();
	}

	record TypedEntry(Kind kind, Entry entry) {
		String value() { return entry.value(); }
		String namespace() { return entry.namespace(); }
		String label() { return "[" + kind.tabLabel() + "] " + entry.label(); }
		boolean matches(String needle) {
			return entry.matches(needle) || LuaUiText.matchesSearch(kind.label(), needle);
		}
		String soundId() { return kind == Kind.SOUND ? value() : null; }
	}

	@FunctionalInterface
	private interface NameResolver {
		String resolve(Identifier id);
	}

	private static List<Entry> dimensionEntries(Minecraft client) {
		if (client.getConnection() == null) return List.of();
		return registryEntries(client.getConnection().levels().stream().map(key -> key.identifier()).toList(), LuaInsertCatalog::dimensionName);
	}

	static String dimensionName(Identifier id) {
		String key = "dimension." + id.getNamespace() + "." + id.getPath().replace('/', '.');
		String translated = Component.translatable(key).getString();
		if (!translated.equals(key)) return translated;
		return switch (id.toString()) {
			case "minecraft:overworld" -> LuaUiText.text("主世界", "Overworld");
			case "minecraft:the_nether" -> LuaUiText.text("下界", "The Nether");
			case "minecraft:the_end" -> LuaUiText.text("末地", "The End");
			default -> "";
		};
	}

	static String gameRuleName(Identifier id) {
		var rule = BuiltInRegistries.GAME_RULE.getValue(id);
		String key = rule == null ? "gamerule." + id.getNamespace() + "." + id.getPath().replace('/', '.')
				: rule.getDescriptionId();
		String translated = Component.translatable(key).getString();
		return translated.equals(key) ? "" : translated;
	}

	enum Kind {
		ITEM("物品 ID", "物品", ""),
		BLOCK("方块 ID", "方块", ""),
		ENTITY("实体 ID", "实体", ""),
		PLAYER("玩家 UUID", "玩家", ""),
		EFFECT("状态效果 ID", "状态", "minecraft:mob_effect"),
		ENCHANTMENT("附魔效果 ID", "附魔", "minecraft:enchantment"),
		SOUND("声音 ID", "声音", "minecraft:sound_event"),
		PARTICLE("粒子效果 ID", "粒子", "minecraft:particle_type"),
		STRUCTURE("结构模板 ID", "结构", "minecraft:structure_template"),
		LOOT_TABLE("战利品表 ID", "战利", "minecraft:loot_table"),
		ITEM_MODEL("物品模型资源 ID", "物品模型", LuaResourceAssetCatalog.ITEM_MODEL),
		MODEL("模型资源 ID", "模型", LuaResourceAssetCatalog.MODEL),
		TEXTURE("纹理资源 ID", "纹理", LuaResourceAssetCatalog.TEXTURE),
		SOUND_FILE("OGG 音频文件 ID", "音频文件", LuaResourceAssetCatalog.SOUND_FILE),
		FONT("字体资源 ID", "字体", LuaResourceAssetCatalog.FONT),
		EQUIPMENT("装备模型资源 ID", "装备模型", LuaResourceAssetCatalog.EQUIPMENT),
		TOOLTIP_STYLE("提示框样式 ID", "提示样式", LuaResourceAssetCatalog.TOOLTIP_STYLE),
		PARTICLE_ASSET("粒子贴图定义 ID", "粒子贴图", LuaResourceAssetCatalog.PARTICLE),
		BLOCKSTATE("方块状态模型 ID", "方块状态", LuaResourceAssetCatalog.BLOCKSTATE),
		ATLAS("纹理图集 ID", "图集", LuaResourceAssetCatalog.ATLAS),
		DIMENSION("维度 ID", "维度", "minecraft:dimension"),
		GAME_RULE("游戏规则 ID", "规则", "minecraft:game_rule");

		private final String label;
		private final String tabLabel;
		private final String registryId;

		Kind(String label, String tabLabel, String registryId) {
			this.label = label;
			this.tabLabel = tabLabel;
			this.registryId = registryId;
		}

		String label() {
			return LuaUiText.text(label);
		}

		String tabLabel() { return LuaUiText.text(tabLabel); }
		String registryId() { return registryId; }
		boolean resource() { return registryId.startsWith("mc_lua_ide:"); }
		static List<Kind> group(boolean resources) {
			return java.util.Arrays.stream(values()).filter(kind -> kind.resource() == resources).toList();
		}
	}

	record Entry(String value, String name, String namespace, String initials, String description) {
		Entry(String value, String name, String namespace) {
			this(value, name, namespace, ChineseSearchIndex.initials(name), "");
		}

		Entry(String value, String name, String namespace, String description) {
			this(value, name, namespace, ChineseSearchIndex.initials(name + " " + description), description);
		}

		String label() {
			String base = name == null || name.isBlank() || name.equals(value) ? value : value + "  —  " + name;
			return description == null || description.isBlank() ? base : base + " · " + description;
		}

		boolean matches(String needle) {
			return needle.isEmpty()
					|| label().toLowerCase(java.util.Locale.ROOT).contains(needle)
					|| LuaUiText.chinese() && initials.contains(needle)
					|| LuaUiText.japanese() && JapaneseSearchIndex.matches(label(), needle)
					|| namespace.toLowerCase(java.util.Locale.ROOT).contains(needle);
		}
	}
}
