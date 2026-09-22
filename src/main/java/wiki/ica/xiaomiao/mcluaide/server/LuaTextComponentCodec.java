package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/** Registry-aware conversion between Lua values and Minecraft JSON text components. */
final class LuaTextComponentCodec {
	private static final int MAX_JSON_CHARS = 65_536;
	private static final int MAX_VALUES = 4_096;
	private static final int MAX_DEPTH = 32;

	private LuaTextComponentCodec() {
	}

	static Component decode(MinecraftServer server, LuaValue value) {
		if (value == null || value.isnil()) throw new LuaError("文本不能为空");
		if (value.isstring()) {
			String raw = value.checkjstring();
			if (!looksLikeJson(raw)) return Component.literal(raw);
			return decodeJson(server, parseJson(raw));
		}
		if (!value.istable()) {
			throw new LuaError("文本参数必须是普通字符串、Minecraft 文本组件 JSON 字符串或 Lua 表");
		}
		return decodeJson(server, luaToJson(value, new Budget(), 0, true));
	}

	static String commandJson(MinecraftServer server, LuaValue value) {
		try {
			return ComponentSerialization.CODEC.encodeStart(
					RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), decode(server, value))
					.getOrThrow(problem -> new LuaError("无法生成文本组件 JSON：" + problem)).toString();
		} catch (LuaError error) {
			throw error;
		} catch (RuntimeException error) {
			throw new LuaError("无法生成文本组件 JSON：" + message(error));
		}
	}

	private static Component decodeJson(MinecraftServer server, JsonElement json) {
		try {
			return ComponentSerialization.CODEC.parse(
					RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), json)
					.getOrThrow(problem -> new LuaError("无效 Minecraft 文本组件：" + problem));
		} catch (LuaError error) {
			throw error;
		} catch (RuntimeException error) {
			throw new LuaError("无效 Minecraft 文本组件：" + message(error));
		}
	}

	private static boolean looksLikeJson(String raw) {
		String stripped = raw.stripLeading();
		return stripped.startsWith("{") || stripped.startsWith("[") || stripped.startsWith("\"");
	}

	private static JsonElement parseJson(String raw) {
		if (raw.length() > MAX_JSON_CHARS) {
			throw new LuaError("文本组件 JSON 不能超过 " + MAX_JSON_CHARS + " 个字符");
		}
		try {
			return JsonParser.parseString(raw);
		} catch (RuntimeException error) {
			throw new LuaError("文本组件 JSON 格式错误：" + message(error));
		}
	}

	private static JsonElement luaToJson(LuaValue value, Budget budget, int depth, boolean forceObject) {
		if (++budget.values > MAX_VALUES) throw new LuaError("文本组件包含的值过多，最多 " + MAX_VALUES + " 项");
		if (depth > MAX_DEPTH) throw new LuaError("文本组件嵌套过深，最多 " + MAX_DEPTH + " 层");
		if (value.isnil()) return JsonNull.INSTANCE;
		if (value.isboolean()) return new JsonPrimitive(value.checkboolean());
		if (value.isnumber()) {
			if (value.isint()) return new JsonPrimitive(value.checklong());
			double number = value.checkdouble();
			if (!Double.isFinite(number)) throw new LuaError("文本组件中的数字必须是有限值");
			return new JsonPrimitive(number);
		}
		if (value.isstring()) return new JsonPrimitive(value.checkjstring());
		if (!value.istable()) throw new LuaError("文本组件只支持字符串、数字、布尔值、nil、表和数组");

		LuaTable table = value.checktable();
		LuaValue[] keys = table.keys();
		boolean array = !forceObject && keys.length > 0;
		int maximumIndex = 0;
		if (array) {
			for (LuaValue key : keys) {
				if (!key.isint() || key.checkint() < 1) {
					array = false;
					break;
				}
				maximumIndex = Math.max(maximumIndex, key.checkint());
			}
			array = array && maximumIndex == keys.length;
		}
		if (array) {
			JsonArray result = new JsonArray();
			for (int index = 1; index <= maximumIndex; index++) {
				result.add(luaToJson(table.get(index), budget, depth + 1, false));
			}
			return result;
		}

		JsonObject result = new JsonObject();
		for (LuaValue key : keys) {
			if (!key.isstring()) {
				throw new LuaError("文本组件对象的键必须是字符串；数组必须使用从 1 开始的连续整数索引");
			}
			result.add(key.checkjstring(), luaToJson(table.get(key), budget, depth + 1, false));
		}
		return result;
	}

	private static String message(Throwable error) {
		String text = error == null ? null : error.getMessage();
		return text == null || text.isBlank() ? "未知格式错误" : text;
	}

	private static final class Budget {
		private int values;
	}
}
