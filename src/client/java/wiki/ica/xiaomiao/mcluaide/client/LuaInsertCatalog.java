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
				return item == null ? "" : item.getName(item.getDefaultInstance()).getString();
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

	@FunctionalInterface
	private interface NameResolver {
		String resolve(Identifier id);
	}

	enum Kind {
		ITEM("物品 ID"),
		BLOCK("方块 ID"),
		ENTITY("实体 ID"),
		PLAYER("玩家 UUID");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		String label() {
			return label;
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
					|| initials.contains(needle)
					|| namespace.toLowerCase(java.util.Locale.ROOT).contains(needle);
		}
	}
}
