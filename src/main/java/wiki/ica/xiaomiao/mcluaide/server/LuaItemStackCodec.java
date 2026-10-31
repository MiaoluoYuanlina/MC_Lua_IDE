package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts lossless, registry-aware item component patches between Lua and Minecraft codecs. */
final class LuaItemStackCodec {
	private static final int MAX_COMPONENT_JSON_CHARS = 262_144;
	private static final int MAX_COMPONENT_VALUES = 16_384;
	private static final int MAX_COMPONENT_DEPTH = 32;

	private LuaItemStackCodec() {
	}

	static ItemStack create(MinecraftServer server, LuaValue itemIdValue, LuaValue countValue,
			LuaValue componentsValue, int maximumCount) {
		return create(server.registryAccess(), itemIdValue, countValue, componentsValue, maximumCount);
	}

	static ItemStack create(HolderLookup.Provider registries, LuaValue itemIdValue, LuaValue countValue,
			LuaValue componentsValue, int maximumCount) {
		Identifier id = identifier(itemIdValue.checkjstring());
		Item item = BuiltInRegistries.ITEM.getOptional(id)
				.orElseThrow(() -> new LuaError("未知物品：" + id));
		int count = countValue.isnil() ? 1 : countValue.checkint();
		if (count < 1 || count > maximumCount) {
			throw new LuaError("物品数量必须在 1 到 " + maximumCount + " 之间");
		}
		DataComponentPatch patch = decodePatch(registries, componentsValue);
		try {
			return new ItemStack(item.builtInRegistryHolder(), count, patch);
		} catch (RuntimeException error) {
			throw new LuaError("无法创建物品堆：" + message(error));
		}
	}

	static LuaTable toLua(MinecraftServer server, ItemStack stack) {
		return toLua(server.registryAccess(), stack);
	}

	static LuaTable toLua(HolderLookup.Provider registries, ItemStack stack) {
		LuaTable result = new LuaTable();
		boolean empty = stack == null || stack.isEmpty();
		result.set("empty", LuaValue.valueOf(empty));
		result.set("count", empty ? 0 : stack.getCount());
		if (empty) {
			result.set("id", "minecraft:air");
			result.set("name", "");
			result.set("maxStack", 0);
			result.set("damage", 0);
			result.set("maxDamage", 0);
			LuaTable none = new LuaTable();
			result.set("components", none);
			result.set("componentsJson", none); // the same table: structured data, not text
			return result;
		}

		result.set("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		result.set("name", stack.getHoverName().getString());
		result.set("maxStack", stack.getMaxStackSize());
		result.set("damage", stack.getDamageValue());
		result.set("maxDamage", stack.getMaxDamage());
		JsonElement patch = encodePatch(registries, stack);
		LuaValue components = jsonToLua(patch);
		result.set("components", components);
		result.set("componentsJson", components); // the same table: structured data, not text
		return result;
	}

	static Object toJavaComponents(MinecraftServer server, ItemStack stack) {
		return toJavaComponents(server.registryAccess(), stack);
	}

	static Object toJavaComponents(HolderLookup.Provider registries, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return Map.of();
		return jsonToJava(encodePatch(registries, stack));
	}

	/** A filter is the explicit patch, not a constructed stack: prototypes must not add conditions. */
	record ItemFilter(Item item, DataComponentPatch components) {
		boolean matches(ItemStack actual) {
			if (actual == null || actual.isEmpty() || !actual.is(item)) return false;
			for (var entry : components.entrySet()) {
				Object value = actual.get(entry.getKey());
				if (entry.getValue().isEmpty()) { if (value != null) return false; continue; }
				Object expected = entry.getValue().get();
				if (entry.getKey() == DataComponents.CUSTOM_DATA && expected instanceof CustomData pattern) {
					if (!(value instanceof CustomData data) || !data.matchedBy(pattern.copyTag())) return false;
				} else if (!java.util.Objects.equals(value, expected)) return false;
			}
			return true;
		}
	}
	static ItemFilter filter(MinecraftServer server, LuaValue itemId, LuaValue components) {
		return filter(server.registryAccess(), itemId, components);
	}
	static ItemFilter filter(HolderLookup.Provider registries, LuaValue itemId, LuaValue components) {
		Identifier id = identifier(itemId.checkjstring());
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new LuaError("未知物品：" + id));
		return new ItemFilter(item, decodePatch(registries, components));
	}

	static LuaTable normalizeComponents(HolderLookup.Provider registries, LuaValue value) {
		DataComponentPatch patch = decodePatch(registries, value);
		JsonElement json = encodePatch(registries, patch);
		LuaTable result = new LuaTable();
		LuaValue components = jsonToLua(json);
		result.set("components", components);
		result.set("componentsJson", components); // the same table: structured data, not text
		return result;
	}

	static DataComponentPatch decodePatch(HolderLookup.Provider registries, LuaValue value) {
		if (value == null || value.isnil()) return DataComponentPatch.EMPTY;
		JsonElement json;
		try {
			if (value.isstring()) {
				String raw = value.checkjstring();
				if (raw.length() > MAX_COMPONENT_JSON_CHARS) {
					throw new LuaError("物品堆栈组件 JSON 不能超过 " + MAX_COMPONENT_JSON_CHARS + " 个字符");
				}
				json = JsonParser.parseString(raw);
			} else if (value.istable()) {
				json = luaToJson(value, new Budget(), 0, true);
			} else {
				throw new LuaError("物品堆栈组件必须是 Lua 表、JSON 对象字符串或 nil");
			}
		} catch (LuaError error) {
			throw error;
		} catch (RuntimeException error) {
			throw new LuaError("物品堆栈组件 JSON 格式错误：" + message(error));
		}
		if (!json.isJsonObject()) throw new LuaError("物品堆栈组件最外层必须是对象表");
		try {
			return DataComponentPatch.CODEC.parse(
					RegistryOps.create(JsonOps.INSTANCE, registries), json)
					.getOrThrow(problem -> new LuaError("无效物品堆栈组件：" + problem));
		} catch (LuaError error) {
			throw error;
		} catch (RuntimeException error) {
			throw new LuaError("无效物品堆栈组件：" + message(error));
		}
	}

	static JsonObject objectToJson(LuaValue value) {
		JsonElement encoded = luaToJson(value, new Budget(), 0, true);
		if (!encoded.isJsonObject()) throw new LuaError("配置必须是 Lua 对象表");
		return encoded.getAsJsonObject();
	}

	static LuaValue jsonObjectToLua(String raw) {
		if (raw.length() > MAX_COMPONENT_JSON_CHARS) throw new LuaError("配置 JSON 过长");
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(raw);
		} catch (RuntimeException error) {
			throw new LuaError("配置 JSON 格式错误：" + message(error));
		}
		if (!parsed.isJsonObject()) throw new LuaError("配置 JSON 最外层必须是对象");
		return jsonToLua(parsed);
	}

	private static JsonElement encodePatch(HolderLookup.Provider registries, ItemStack stack) {
		return encodePatch(registries, stack.getComponentsPatch().forget(type -> type.isTransient()));
	}

	private static JsonElement encodePatch(HolderLookup.Provider registries, DataComponentPatch patch) {
		try {
			return DataComponentPatch.CODEC.encodeStart(
					RegistryOps.create(JsonOps.INSTANCE, registries), patch)
					.getOrThrow(problem -> new LuaError("无法读取物品堆栈组件：" + problem));
		} catch (LuaError error) {
			throw error;
		} catch (RuntimeException error) {
			throw new LuaError("无法读取物品堆栈组件：" + message(error));
		}
	}

	private static JsonElement luaToJson(LuaValue value, Budget budget, int depth, boolean forceObject) {
		if (++budget.values > MAX_COMPONENT_VALUES) {
			throw new LuaError("物品堆栈组件包含的值过多，最多 " + MAX_COMPONENT_VALUES + " 项");
		}
		if (depth > MAX_COMPONENT_DEPTH) {
			throw new LuaError("物品堆栈组件嵌套过深，最多 " + MAX_COMPONENT_DEPTH + " 层");
		}
		if (value.isnil()) return JsonNull.INSTANCE;
		if (value.isboolean()) return new JsonPrimitive(value.checkboolean());
		if (value.isnumber()) {
			if (value.isint()) return new JsonPrimitive(value.checklong());
			double number = value.checkdouble();
			if (!Double.isFinite(number)) throw new LuaError("物品堆栈组件中的数字必须是有限值");
			return new JsonPrimitive(number);
		}
		if (value.isstring()) return new JsonPrimitive(value.checkjstring());
		if (!value.istable()) {
			throw new LuaError("物品堆栈组件只支持字符串、数字、布尔值、nil、表和数组");
		}

		LuaTable table = value.checktable();
		LuaValue[] keys = table.keys();
		boolean array = !forceObject && keys.length > 0;
		int maximumIndex = 0;
		if (array) {
			for (LuaValue key : keys) {
				if (!key.isint()) {
					array = false;
					break;
				}
				int index = key.checkint();
				if (index < 1) {
					array = false;
					break;
				}
				maximumIndex = Math.max(maximumIndex, index);
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
				throw new LuaError("物品堆栈组件对象的键必须是字符串；数组必须使用从 1 开始的连续整数索引");
			}
			result.add(key.checkjstring(), luaToJson(table.get(key), budget, depth + 1, false));
		}
		return result;
	}

	private static LuaValue jsonToLua(JsonElement value) {
		if (value == null || value.isJsonNull()) return LuaValue.NIL;
		if (value.isJsonArray()) {
			LuaTable result = new LuaTable();
			int index = 1;
			for (JsonElement item : value.getAsJsonArray()) result.set(index++, jsonToLua(item));
			return result;
		}
		if (value.isJsonObject()) {
			LuaTable result = new LuaTable();
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				LuaValue converted = jsonToLua(entry.getValue());
				if (!converted.isnil()) result.set(entry.getKey(), converted);
			}
			return result;
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (primitive.isBoolean()) return LuaValue.valueOf(primitive.getAsBoolean());
		if (primitive.isString()) return LuaValue.valueOf(primitive.getAsString());
		BigDecimal number = primitive.getAsBigDecimal();
		try {
			return number.scale() <= 0 ? LuaValue.valueOf(number.longValueExact())
					: LuaValue.valueOf(number.doubleValue());
		} catch (ArithmeticException ignored) {
			return LuaValue.valueOf(number.doubleValue());
		}
	}

	private static Object jsonToJava(JsonElement value) {
		if (value == null || value.isJsonNull()) return null;
		if (value.isJsonArray()) {
			List<Object> result = new ArrayList<>();
			for (JsonElement item : value.getAsJsonArray()) result.add(jsonToJava(item));
			return List.copyOf(result);
		}
		if (value.isJsonObject()) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				Object converted = jsonToJava(entry.getValue());
				if (converted != null) result.put(entry.getKey(), converted);
			}
			return Map.copyOf(result);
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (primitive.isBoolean()) return primitive.getAsBoolean();
		if (primitive.isString()) return primitive.getAsString();
		BigDecimal number = primitive.getAsBigDecimal();
		try {
			return number.scale() <= 0 ? number.longValueExact() : number.doubleValue();
		} catch (ArithmeticException ignored) {
			return number.doubleValue();
		}
	}

	private static Identifier identifier(String raw) {
		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (id == null) throw new LuaError("无效资源 ID：" + raw);
		return id;
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	private static final class Budget {
		private int values;
	}
}
