package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.Identifier;
import wiki.ica.xiaomiao.mcluaide.editor.PackResourceId;
import java.util.*;

public final class PackIdCatalogSmoke {
	public static void main(String[] args) {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		List<LuaPackIdCatalog.Spec> specs = List.of(
				new LuaPackIdCatalog.Spec("minecraft:recipe", "recipe", ".json", false),
				new LuaPackIdCatalog.Spec("minecraft:loot_table", "loot_table", ".json", false),
				new LuaPackIdCatalog.Spec("minecraft:worldgen/biome", "worldgen/biome", ".json", false),
				new LuaPackIdCatalog.Spec("minecraft:item", "tags/item", ".json", true),
				new LuaPackIdCatalog.Spec("minecraft:function", "function", ".mcfunction", false),
				new LuaPackIdCatalog.Spec("minecraft:structure_template", "structure", ".nbt", false),
				new LuaPackIdCatalog.Spec("other_mod:custom", "other_mod/custom", ".json", false));
		Map<String, Map<String, String>> index = new LinkedHashMap<>();
		for (String file : List.of("recipe/tools/test.json", "loot_table/chests/test.json", "worldgen/biome/diamond.json",
				"tags/item/my_items.json", "function/example/tick.mcfunction", "structure/base.nbt", "other_mod/custom/example.json"))
			LuaPackIdCatalog.add(index, specs, Identifier.parse("test:" + file), "workspace");
		require(index.get("minecraft:recipe").containsKey("test:tools/test"), "Nested recipe ID mapping failed");
		require(index.get("minecraft:loot_table").containsKey("test:chests/test"), "Loot table ID mapping failed");
		require(index.get("minecraft:worldgen/biome").containsKey("test:diamond"), "Worldgen ID mapping failed");
		require(index.get("minecraft:item").containsKey("#test:my_items"), "Tags lost # or invented item IDs");
		require(index.get("minecraft:function").containsKey("test:example/tick"), "Function ID mapping failed");
		require(index.get("minecraft:structure_template").containsKey("test:base"), "Structure ID mapping failed");
		require(index.get("other_mod:custom").containsKey("test:example"), "Mod registry path mapping failed");
		LuaPackIdCatalog.add(index, specs, Identifier.parse("test:recipe/tools/test.json"), "duplicate");
		require(index.get("minecraft:recipe").get("test:tools/test").equals("workspace"), "Duplicate source replaced priority");
		require(PackResourceId.logical(Identifier.parse("test:recipe/../escape.json"), "recipe", ".json", false) == null, "Traversal ID accepted");
		require(PackResourceId.workspaceFile("pack/data/test/worldgen/biome/diamond.json", "data")
				.equals(Identifier.parse("test:worldgen/biome/diamond.json")), "Workspace namespace extraction failed");
		require(PackResourceId.workspaceFile("pack/pack.mcmeta", "data") == null, "Metadata treated as registry ID");
		require(PackResourceId.logical(Identifier.parse("test:models/item/demo.json"), "items", ".json", false) == null, "Model confused with an item definition");
		System.out.println("Pack ID mapping smoke passed");
	}
	private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
