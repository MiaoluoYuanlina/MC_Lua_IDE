package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Server-authoritative resource ID catalog, including synchronized dynamic and mod registries. */
final class LuaRegistryCatalog {
	private static final int PAGE_SIZE = 1_500;

	private LuaRegistryCatalog() {
	}

	static String describe(MinecraftServer server, String rawRegistryId, String body) {
		String locale = requestLocale(body);
		if ("*".equals(rawRegistryId)) return describeTypes(server, locale);
		Identifier registryId = Identifier.tryParse(rawRegistryId == null ? "" : rawRegistryId.strip());
		if (registryId == null) throw new IllegalArgumentException("无效注册表 ID：" + rawRegistryId);
		Registry<?> registry = findRegistry(server, registryId);
		if (registry == null) throw new IllegalArgumentException("服务器不存在注册表：" + registryId);

		List<Identifier> identifiers = new ArrayList<>(registry.keySet());
		identifiers.sort(Comparator.comparing(Identifier::toString, String.CASE_INSENSITIVE_ORDER));
		int offset = requestOffset(body);
		if (offset < 0 || offset > identifiers.size()) throw new IllegalArgumentException("无效注册表分页位置：" + offset);
		int end = Math.min(identifiers.size(), offset + PAGE_SIZE);
		JsonObject root = new JsonObject();
		root.addProperty("registry", registryId.toString());
		root.addProperty("total", identifiers.size());
		root.addProperty("nextOffset", end < identifiers.size() ? end : -1);
		JsonArray entries = new JsonArray();
		for (int index = offset; index < end; index++) {
			Identifier id = identifiers.get(index);
			JsonObject entry = new JsonObject();
			entry.addProperty("id", id.toString());
			entry.addProperty("name", displayName(registry.getValue(id)));
			McLuaIdeRegistryApi.Annotation annotation = McLuaIdeRegistryApi.entryAnnotation(
					registryId.toString(), id.toString());
			if (annotation != null) {
				McLuaIdeRegistryApi.Text text = annotation.translation(locale);
				entry.addProperty("annotation", text.label());
				entry.addProperty("description", text.description());
			}
			entries.add(entry);
		}
		root.add("entries", entries);
		return root.toString();
	}

	private static String describeTypes(MinecraftServer server, String locale) {
		Set<Identifier> ids = new LinkedHashSet<>(BuiltInRegistries.REGISTRY.keySet());
		server.registryAccess().listRegistryKeys().forEach(key -> ids.add(key.identifier()));
		List<Identifier> sorted = ids.stream()
				.sorted(Comparator.comparing(Identifier::toString, String.CASE_INSENSITIVE_ORDER))
				.toList();
		JsonObject root = new JsonObject();
		root.addProperty("registry", "*");
		JsonArray types = new JsonArray();
		for (Identifier id : sorted) {
			Registry<?> registry = findRegistry(server, id);
			if (registry == null) continue;
			McLuaIdeRegistryApi.Annotation annotation = McLuaIdeRegistryApi.registryAnnotation(id.toString());
			String label;
			String description;
			if (annotation != null) {
				McLuaIdeRegistryApi.Text text = annotation.translation(locale);
				label = text.label();
				description = text.description();
			} else {
				label = readable(id.getPath()) + " ID";
				description = "由 " + id.getNamespace() + " 提供的注册表；条目从当前服务器实时读取";
			}
			JsonObject type = new JsonObject();
			type.addProperty("id", id.toString());
			type.addProperty("label", label);
			type.addProperty("description", description);
			type.addProperty("namespace", id.getNamespace());
			type.addProperty("count", registry.size());
			types.add(type);
		}
		root.add("types", types);
		return root.toString();
	}

	private static String requestLocale(String body) {
		if (body == null || body.isBlank()) return "zh_cn";
		int split = body.indexOf('\n');
		String locale = (split < 0 ? body : body.substring(0, split)).strip();
		return locale.isEmpty() ? "zh_cn" : locale;
	}

	private static int requestOffset(String body) {
		if (body == null) return 0;
		int split = body.indexOf('\n');
		if (split < 0 || split + 1 >= body.length()) return 0;
		try {
			return Integer.parseInt(body.substring(split + 1).strip());
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException("无效注册表分页位置");
		}
	}

	private static String readable(String path) {
		return path.replace('_', ' ').replace('/', ' ');
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Registry<?> findRegistry(MinecraftServer server, Identifier registryId) {
		ResourceKey registryKey = ResourceKey.createRegistryKey(registryId);
		Registry<?> dynamic = (Registry<?>) server.registryAccess().lookup(registryKey).orElse(null);
		if (dynamic != null) return dynamic;
		return BuiltInRegistries.REGISTRY.getOptional(registryId).orElse(null);
	}

	private static String displayName(Object value) {
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
}
