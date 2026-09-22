package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/** Decodes server registry IDs and enriches them with the administrator client's localized names. */
final class LuaRegistryCatalogData {
	private LuaRegistryCatalogData() {
	}

	static Page decodePage(String encoded, Minecraft minecraft) {
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		String registryId = root.get("registry").getAsString();
		List<LuaInsertCatalog.Entry> result = new ArrayList<>();
		for (JsonElement raw : root.getAsJsonArray("entries")) {
			JsonObject entry = raw.getAsJsonObject();
			String id = entry.get("id").getAsString();
			String fallback = entry.has("name") ? entry.get("name").getAsString() : "";
			String annotation = entry.has("annotation") ? entry.get("annotation").getAsString() : "";
			String description = entry.has("description") ? entry.get("description").getAsString() : "";
			String localized = localName(minecraft, registryId, id);
			Identifier identifier = Identifier.tryParse(id);
			result.add(new LuaInsertCatalog.Entry(id,
					annotation.isBlank() ? (localized.isBlank() ? fallback : localized) : annotation,
					identifier == null ? "" : identifier.getNamespace(), description));
		}
		int nextOffset = root.has("nextOffset") ? root.get("nextOffset").getAsInt() : -1;
		int total = root.has("total") ? root.get("total").getAsInt() : result.size();
		return new Page(List.copyOf(result), nextOffset, total);
	}

	static List<RegistryType> decodeTypes(String encoded) {
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		List<RegistryType> result = new ArrayList<>();
		for (JsonElement raw : root.getAsJsonArray("types")) {
			JsonObject type = raw.getAsJsonObject();
			result.add(new RegistryType(type.get("id").getAsString(), type.get("label").getAsString(),
					type.get("description").getAsString(), type.get("namespace").getAsString(),
					type.get("count").getAsInt()));
		}
		return List.copyOf(result);
	}

	private static String localName(Minecraft minecraft, String registryId, String rawId) {
		Identifier id = Identifier.tryParse(rawId);
		if (id == null) return "";
		Object value = registryValue(minecraft, registryId, id);
		try {
			return switch (value) {
				case Item item -> item.getName(item.getDefaultInstance()).getString();
				case Block block -> Component.translatable(block.getDescriptionId()).getString();
				case EntityType<?> entity -> entity.getDescription().getString();
				case Enchantment enchantment -> enchantment.description().getString();
				case MobEffect effect -> effect.getDisplayName().getString();
				case Attribute attribute -> Component.translatable(attribute.getDescriptionId()).getString();
				case Potion potion -> Component.translatable("item.minecraft.potion.effect." + potion.name()).getString();
				case null, default -> "";
			};
		} catch (RuntimeException ignored) {
			return "";
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object registryValue(Minecraft minecraft, String rawRegistryId, Identifier id) {
		Identifier registryId = Identifier.tryParse(rawRegistryId);
		if (registryId == null) return null;
		ResourceKey registryKey = ResourceKey.createRegistryKey(registryId);
		Registry<?> registry = null;
		if (minecraft.level != null) {
			registry = (Registry<?>) minecraft.level.registryAccess().lookup(registryKey).orElse(null);
		}
		if (registry == null) registry = BuiltInRegistries.REGISTRY.getOptional(registryId).orElse(null);
		return registry == null ? null : registry.getValue(id);
	}

	record Page(List<LuaInsertCatalog.Entry> entries, int nextOffset, int total) {
	}

	record RegistryType(String id, String label, String description, String namespace, int count) {
		private String searchable() {
			return (id + " " + label + " " + description + " " + namespace + " "
					+ ChineseSearchIndex.initials(label + " " + description)).toLowerCase(java.util.Locale.ROOT);
		}

		boolean matches(String needle) {
			return needle == null || needle.isBlank() || searchable().contains(needle.toLowerCase(java.util.Locale.ROOT));
		}

		String display() {
			return label + "  " + id + "  (" + count + ")";
		}
	}
}
