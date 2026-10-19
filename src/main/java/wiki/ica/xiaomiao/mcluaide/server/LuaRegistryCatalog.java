package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
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
import java.util.LinkedHashMap;
import java.util.Map;
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
		HolderLookup.RegistryLookup<?> registry = findRegistry(server, registryId);
		Map<String, Object> values = entries(server, registryId, registry);
		List<String> identifiers = new ArrayList<>(values.keySet());
		identifiers.sort(String.CASE_INSENSITIVE_ORDER);
		int offset = requestOffset(body);
		if (offset < 0 || offset > identifiers.size()) throw new IllegalArgumentException("无效注册表分页位置：" + offset);
		int end = Math.min(identifiers.size(), offset + PAGE_SIZE);
		JsonObject root = new JsonObject();
		root.addProperty("registry", registryId.toString());
		root.addProperty("total", identifiers.size());
		root.addProperty("nextOffset", end < identifiers.size() ? end : -1);
		JsonArray entries = new JsonArray();
		for (int index = offset; index < end; index++) {
			String id = identifiers.get(index);
			JsonObject entry = new JsonObject();
			entry.addProperty("id", id);
			entry.addProperty("name", id.startsWith("#") ? "注册表标签" : displayName(values.get(id)));
			String fileDescription = LuaPackIdCatalog.snapshot(server).getOrDefault(registryId.toString(), Map.of()).get(id);
			if (fileDescription != null && values.get(id) != null && fileDescription.contains("尚未"))
				fileDescription = "当前服务器已有该 ID；编辑目录可能包含尚未重载的修改";
			if (fileDescription != null) entry.addProperty("description", fileDescription);
			if (id.startsWith("#")) entry.addProperty("description", "标签集合；仅用于支持 #标签 的字段"
					+ (fileDescription == null ? "" : " · " + fileDescription));
			McLuaIdeRegistryApi.Annotation annotation = McLuaIdeRegistryApi.entryAnnotation(
					registryId.toString(), id);
			if (annotation != null) {
				McLuaIdeRegistryApi.Text text = annotation.translation(locale);
				entry.addProperty("annotation", text.label());
				entry.addProperty("description", text.description() + (fileDescription == null ? "" : " · " + fileDescription));
			}
			entries.add(entry);
		}
		root.add("entries", entries);
		return root.toString();
	}

	private static String describeTypes(MinecraftServer server, String locale) {
		Set<Identifier> ids = new LinkedHashSet<>(BuiltInRegistries.REGISTRY.keySet());
		server.registryAccess().listRegistryKeys().forEach(key -> ids.add(key.identifier()));
		server.registries().compositeAccess().listRegistryKeys().forEach(key -> ids.add(key.identifier()));
		server.reloadableRegistries().lookup().listRegistryKeys().forEach(key -> ids.add(key.identifier()));
		for (String extra : List.of("recipe", "advancement", "dimension", "structure_template")) ids.add(Identifier.withDefaultNamespace(extra));
		LuaPackIdCatalog.snapshot(server).keySet().forEach(id -> ids.add(Identifier.parse(id)));
		List<Identifier> sorted = ids.stream()
				.sorted(Comparator.comparing(Identifier::toString, String.CASE_INSENSITIVE_ORDER))
				.toList();
		JsonObject root = new JsonObject();
		root.addProperty("registry", "*");
		JsonArray types = new JsonArray();
		for (Identifier id : sorted) {
			HolderLookup.RegistryLookup<?> registry = findRegistry(server, id);
			if (registry == null && !specialCatalog(id) && !LuaPackIdCatalog.snapshot(server).containsKey(id.toString())) continue;
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
			type.addProperty("count", entries(server, id, registry).size());
			types.add(type);
		}
		root.add("types", types);
		return root.toString();
	}

	private static boolean specialCatalog(Identifier id) {
		return id.getNamespace().equals("minecraft") && Set.of("recipe", "advancement", "dimension", "structure_template").contains(id.getPath());
	}

	private static Map<String, Object> entries(MinecraftServer server, Identifier id, HolderLookup.RegistryLookup<?> registry) {
		Map<String, Object> values = new LinkedHashMap<>();
		LuaPackIdCatalog.snapshot(server).getOrDefault(id.toString(), Map.of()).keySet().forEach(key -> values.put(key, null));
		if (specialCatalog(id)) {
			switch (id.getPath()) {
				case "recipe" -> server.getRecipeManager().getRecipes().forEach(recipe -> values.put(recipe.id().identifier().toString(), recipe));
				case "advancement" -> server.getAdvancements().getAllAdvancements().forEach(advancement -> values.put(advancement.id().toString(), advancement));
				case "dimension" -> server.levelKeys().forEach(key -> values.put(key.identifier().toString(), key));
				case "structure_template" -> server.getStructureManager().listTemplates()
						.forEach(template -> values.put(template.toString(), template));
			}
			return values;
		}
		if (registry == null) {
			if (!values.isEmpty()) return values;
			throw new IllegalArgumentException("服务器不存在注册表：" + id);
		}
		registry.listElements().forEach(holder -> values.put(holder.key().identifier().toString(), holder.value()));
		registry.listTags().forEach(tag -> values.put("#" + tag.key().location(), tag));
		return values;
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
	private static HolderLookup.RegistryLookup<?> findRegistry(MinecraftServer server, Identifier registryId) {
		ResourceKey registryKey = ResourceKey.createRegistryKey(registryId);
		HolderLookup.RegistryLookup<?> reloadable = (HolderLookup.RegistryLookup<?>) server.reloadableRegistries().lookup().lookup(registryKey).orElse(null);
		if (reloadable != null) return reloadable;
		HolderLookup.RegistryLookup<?> dynamic = (HolderLookup.RegistryLookup<?>) server.registryAccess().lookup(registryKey).orElse(null);
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
