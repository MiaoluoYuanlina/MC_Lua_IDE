package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/** Executable codec round-trip check that does not require a running game server. */
public final class LuaItemStackCodecSmoke {
	private LuaItemStackCodecSmoke() {
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ComponentIdContextSmoke.run();
		ComponentTreeSchemaSmoke.run(registries);
		LuaItemStackCodec.normalizeComponents(registries, LuaValue.valueOf("""
				{"minecraft:use_remainder":"minecraft:glass_bottle",
				 "minecraft:bundle_contents":["minecraft:apple",{"id":"minecraft:stone","count":2}],
				 "minecraft:charged_projectiles":["minecraft:arrow"],
				 "minecraft:container":[{"slot":0,"item":"minecraft:apple"}]}
				"""));

		LuaTable customName = new LuaTable();
		customName.set("text", "组件测试剑");
		customName.set("color", "gold");
		LuaTable components = new LuaTable();
		components.set("minecraft:custom_name", customName);
		components.set("minecraft:unbreakable", new LuaTable());

		LuaTable encoded = LuaItemStackCodec.normalizeComponents(registries, components);
		String json = encoded.get("componentsJson").checkjstring();
		if (!json.contains("minecraft:custom_name") || !json.contains("minecraft:unbreakable")) {
			throw new AssertionError("组件编码结果缺少预期字段：" + json);
		}

		LuaTable decoded = LuaItemStackCodec.normalizeComponents(registries, LuaValue.valueOf(json));
		if (!json.equals(decoded.get("componentsJson").checkjstring())) {
			throw new AssertionError("物品堆组件 JSON 往返后不一致");
		}
		System.out.println("Item component codec smoke test passed: " + json);
	}
}
