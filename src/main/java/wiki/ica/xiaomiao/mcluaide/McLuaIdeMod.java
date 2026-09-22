package wiki.ica.xiaomiao.mcluaide;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.resources.Identifier;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import wiki.ica.xiaomiao.mcluaide.server.LuaFabricEventBridge;
import wiki.ica.xiaomiao.mcluaide.server.LuaServerNetworking;

public final class McLuaIdeMod implements ModInitializer {
	public static final String MOD_ID = "mc_lua_ide";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LuaProtocol.registerPayloadTypes();
		LuaServerNetworking.register();
		LuaFabricEventBridge.register();
		LOGGER.info("MC Lua IDE server runtime initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
