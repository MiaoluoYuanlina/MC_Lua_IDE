package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentIdContext;
import java.util.List;

final class ComponentIdContextSmoke {
	static void run() {
		check("use_remainder", "\"minecraft:glass_bottle\"", List.of(), false, "item");
		check("use_remainder", "{}", List.of("id"), false, "item");
		check("container", "[]", List.of("0", "item"), false, "item");
		check("container", "[]", List.of("0", "item", "id"), false, "item");
		check("bundle_contents", "[]", List.of("0"), false, "item");
		check("charged_projectiles", "[]", List.of("0"), false, "item");
		check("repairable", "{}", List.of("items"), false, "item");
		check("repairable", "{}", List.of("items", "0"), false, "item");
		check("enchantments", "{}", List.of("minecraft:sharpness"), true, "enchantment");
		check("potion_contents", "{}", List.of("custom_effects", "0", "id"), false, "mob_effect");
		check("can_break", "{}", List.of("predicates", "0", "blocks"), false, "block");
		check("tool", "{}", List.of("rules", "0", "blocks", "0"), false, "block");
		check("consumable", "{}", List.of("on_consume_effects", "0", "type"), false, "consume_effect_type");
		check("consumable", "{}", List.of("on_consume_effects", "0", "effects", "0", "id"), false, "mob_effect");
		check("equippable", "{}", List.of("allowed_entities"), false, "entity_type");
		check("container", "[]", List.of("0", "item", "components", "minecraft:enchantments", "minecraft:sharpness"), true, "enchantment");
		check("container", "[]", List.of("0", "item", "components", "minecraft:potion_contents", "custom_effects", "0", "id"), false, "mob_effect");
		check("bundle_contents", "[]", List.of("0", "components", "minecraft:container", "0", "item", "components", "minecraft:use_remainder"), false, "item");
		check("entity_data", "{}", List.of("equipment", "mainhand", "id"), false, "item");
		check("block_entity_data", "{}", List.of("Items", "0", "id"), false, "item");
		check("entity_data", "{}", List.of("Passengers", "0", "id"), false, "entity_type");
		check("entity_data", "{}", List.of("active_effects", "0", "id"), false, "mob_effect");
		check("entity_data", "{}", List.of("BlockState", "Name"), false, "block");

		McLuaIdeRegistryApi.componentHint("probe_mod", "probe_mod:focus", "/spell", McLuaIdeRegistryApi.Target.VALUE, "probe_mod:spell")
				.translation("zh_cn", "法术 ID", "回归测试").register();
		ComponentIdContext mod = ComponentIdContext.resolve("minecraft:container", JsonParser.parseString("[]"),
				List.of("0", "item", "components", "probe_mod:focus", "spell"));
		if (!mod.componentId().equals("probe_mod:focus") || !mod.matches("/spell")
				|| McLuaIdeRegistryApi.componentHintCatalog().get(mod.componentId()).isEmpty()) throw new AssertionError("嵌套模组提示丢失");
		ComponentIdContext unrelated = ComponentIdContext.resolve("minecraft:container", JsonParser.parseString("[]"),
				List.of("0", "item", "components", "minecraft:custom_data", "id"));
		if (unrelated.structuralRegistry() != null) throw new AssertionError("任意自定义 id 不能误判为外层物品 ID");
		ComponentIdContext escaped = ComponentIdContext.resolve("probe_mod:focus", JsonParser.parseString("{}"), List.of("probe_mod:path/value"));
		if (!escaped.matches("/probe_mod:path~1value")) throw new AssertionError("JSON Pointer 转义错误");
		System.out.println("Component ID inference smoke test passed: scalar stacks, nested stacks, effects, tags, NBT and mod hints");
	}

	private static void check(String component, String json, List<String> path, boolean key, String expected) {
		ComponentIdContext context = ComponentIdContext.resolve("minecraft:" + component, JsonParser.parseString(json), path);
		String actual = McLuaIdeRegistryApi.componentHints(context.componentId()).stream()
				.filter(hint -> (hint.target() == McLuaIdeRegistryApi.Target.KEY) == key && context.matches(hint.pathPattern()))
				.map(McLuaIdeRegistryApi.ComponentHint::registryId).findFirst().orElse(context.structuralRegistry());
		if (!("minecraft:" + expected).equals(actual)) throw new AssertionError(component + " " + path + ": " + actual + " != " + expected);
	}
}
