package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.editor.PackResourceId;
import java.util.*;

/** File-defined IDs, including editable definitions that are not yet active in the game. */
final class LuaPackIdCatalog {
	record Spec(String registry, String directory, String suffix, boolean tag) { }
	private static final Map<MinecraftServer, Map<String, Map<String, String>>> CACHE = new WeakHashMap<>();
	static synchronized void invalidate(MinecraftServer server) { CACHE.remove(server); }
	static synchronized Map<String, Map<String, String>> snapshot(MinecraftServer server) {
		return CACHE.computeIfAbsent(server, LuaPackIdCatalog::scan);
	}
	private static Map<String, Map<String, String>> scan(MinecraftServer server) {
		List<Spec> specs = new ArrayList<>();
		Set<Identifier> registries = new LinkedHashSet<>(BuiltInRegistries.REGISTRY.keySet());
		server.registryAccess().listRegistryKeys().forEach(key -> registries.add(key.identifier()));
		server.registries().compositeAccess().listRegistryKeys().forEach(key -> registries.add(key.identifier()));
		server.reloadableRegistries().lookup().listRegistryKeys().forEach(key -> registries.add(key.identifier()));
		for (Identifier id : registries) {
			var key = ResourceKey.createRegistryKey(id);
			specs.add(new Spec(id.toString(), Registries.elementsDirPath(key), ".json", false));
			specs.add(new Spec(id.toString(), Registries.tagsDirPath(key), ".json", true));
		}
		for (String type : List.of("recipe", "advancement", "dimension", "predicate", "item_modifier", "function")) {
			specs.add(new Spec("minecraft:" + type, type, type.equals("function") ? ".mcfunction" : ".json", false));
			specs.add(new Spec("minecraft:" + type, "tags/" + type, ".json", true));
		}
		specs.add(new Spec("minecraft:structure_template", "structure", ".nbt", false));
		Map<String, Map<String, String>> result = new LinkedHashMap<>();
		try {
			server.getResourceManager().listResources("", id -> id.getPath().endsWith(".json")
					|| id.getPath().endsWith(".mcfunction") || id.getPath().endsWith(".nbt"))
					.keySet().forEach(file -> add(result, specs, file, "已加载数据包资源"));
		} catch (RuntimeException error) { McLuaIdeMod.LOGGER.warn("Could not index active datapack resource IDs", error); }
		try {
			for (String entry : DatapackWorkspaceManager.list(server)) {
				if (!entry.startsWith("F:")) continue;
				Identifier file = PackResourceId.workspaceFile(entry.substring(2), "data");
				if (file != null) add(result, specs, file, "编辑目录中的定义；可能尚未启用或重载，不代表游戏已注册");
			}
		} catch (Exception error) { McLuaIdeMod.LOGGER.warn("Could not index editable datapack IDs", error); }
		return result;
	}
	static void add(Map<String, Map<String, String>> result, List<Spec> specs, Identifier file, String description) {
		for (Spec spec : specs) {
			String id = PackResourceId.logical(file, spec.directory(), spec.suffix(), spec.tag());
			if (id != null) result.computeIfAbsent(spec.registry(), ignored -> new LinkedHashMap<>()).putIfAbsent(id, description);
		}
	}
}
