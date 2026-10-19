package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;

/** Reads and validates dimensions against the server's currently loaded registries. */
final class DatapackDimensionEditorData {
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_EDITOR_JSON = 400_000;

	private DatapackDimensionEditorData() { }

	static String read(MinecraftServer server, String idText) {
		Identifier id = Identifier.tryParse(idText);
		if (id == null) throw new IllegalArgumentException("无效维度 ID：" + idText);
		var key = ResourceKey.create(Registries.LEVEL_STEM, id);
		var registry = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
		LevelStem stem = registry.get(key).orElseThrow(() ->
				new IllegalArgumentException("当前游戏未注册此维度：" + id)).value();
		JsonElement json = LevelStem.CODEC.encodeStart(
				RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), stem).getOrThrow();
		String result = PRETTY.toJson(json);
		if (result.length() > MAX_EDITOR_JSON) throw new IllegalArgumentException("维度数据过大，无法在编辑器中加载");
		return result;
	}

	static String validate(MinecraftServer server, String source) {
		if (source == null || source.length() > MAX_EDITOR_JSON)
			throw new IllegalArgumentException("维度 JSON 过大");
		JsonElement parsed = JsonParser.parseString(source);
		if (!(parsed instanceof JsonObject)) throw new IllegalArgumentException("维度定义必须是 JSON 对象");
		LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), parsed).getOrThrow();
		return "维度 JSON 有效";
	}
}
