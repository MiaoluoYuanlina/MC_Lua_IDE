package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dropped items store these fields as entity NBT, not as ItemStack data components. */
final class LuaItemEntityConfig {
	private LuaItemEntityConfig() { }
	static boolean supports(Entity entity, String key) {
		return entity instanceof ItemEntity && switch (key) {
			case "Item", "Health", "Age", "PickupDelay", "Owner", "Thrower" -> true;
			default -> false;
		};
	}

	static List<LuaDisplayEntityConfig.Field> fields(MinecraftServer server, Entity entity) {
		if (!(entity instanceof ItemEntity item)) return List.of();
		// Ask the actual entity's serializer for defaults. Never add this fixture to the world.
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		item.saveWithoutId(output);
		JsonObject saved = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, output.buildResult()).getAsJsonObject();
		var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		JsonElement stack = saved.has("Item") ? saved.get("Item") : ItemStack.CODEC.encodeStart(ops,
				new ItemStack(Items.STONE)).getOrThrow();
		List<LuaDisplayEntityConfig.Field> result = new ArrayList<>();
		result.add(new LuaDisplayEntityConfig.Field("Item", "掉落物品堆", "实体携带的物品 ID、数量和嵌套物品数据组件；不是刷怪蛋或实体自身的组件",
				stack.deepCopy(), ComponentTreeSchema.itemStack()));
		addNumber(result, saved, "Health", "掉落物生命值", "掉落物的耐久生命值（不是生物 health），范围 0～32767", 5);
		addNumber(result, saved, "Age", "存在时间", "游戏刻，通常 6000 刻后消失；-32768 为不限寿命。范围 -32768～32767", 0);
		addNumber(result, saved, "PickupDelay", "拾取延迟", "游戏刻；0 可立即拾取，32767 为永不拾取。范围 0～32767", 0);
		for (String key : List.of("Owner", "Thrower")) {
			String label = key.equals("Owner") ? "拾取归属 UUID" : "投掷者 UUID";
			String description = key.equals("Owner") ? "指定可优先拾取的实体 UUID；不设置时使用原版默认拾取规则"
					: "投掷来源实体 UUID（可填写玩家 UUID）；与拾取归属是不同字段";
			UUID uuid = saved.has(key) ? UUIDUtil.CODEC.parse(ops, saved.get(key)).result().orElse(new UUID(0, 0)) : new UUID(0, 0);
			JsonPrimitive value = new JsonPrimitive(uuid.toString());
			result.add(new LuaDisplayEntityConfig.Field(key, label, description, value,
					ComponentTreeSchema.scalar(label, "string", value.toString())));
		}
		return List.copyOf(result);
	}

	private static void addNumber(List<LuaDisplayEntityConfig.Field> fields, JsonObject saved,
			String key, String label, String description, int fallback) {
		JsonElement value = saved.has(key) ? saved.get(key).deepCopy() : new JsonPrimitive(fallback);
		fields.add(new LuaDisplayEntityConfig.Field(key, label, description, value,
				ComponentTreeSchema.scalar(label, "integer", value.toString())));
	}

	static void apply(MinecraftServer server, Entity entity, JsonObject values) {
		if (values.isEmpty()) return;
		if (!(entity instanceof ItemEntity)) throw new IllegalArgumentException("这些掉落物标签仅适用于物品实体");
		TagValueOutput patch = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		for (var field : values.entrySet()) {
			String key = field.getKey(); JsonElement value = field.getValue();
			if (!supports(entity, key)) throw new IllegalArgumentException("掉落物不支持字段：" + key);
			switch (key) {
				case "Item" -> {
					ItemStack stack = ItemStack.CODEC.parse(ops, value)
							.getOrThrow(problem -> new IllegalArgumentException("掉落物 Item 物品堆无效：" + problem));
					if (stack.isEmpty()) throw new IllegalArgumentException("掉落物 Item 不能是空物品堆");
					patch.store(key, ItemStack.CODEC, stack);
				}
				case "Health", "Age", "PickupDelay" -> {
					int number;
					try {
						if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
						number = value.getAsBigDecimal().intValueExact();
					} catch (RuntimeException error) { throw new IllegalArgumentException(key + " 必须是整数"); }
					int minimum = key.equals("Age") ? Short.MIN_VALUE : 0;
					if (number < minimum || number > Short.MAX_VALUE)
						throw new IllegalArgumentException(key + " 须在 " + minimum + "～32767 之间");
					patch.putShort(key, (short) number);
				}
				case "Owner", "Thrower" -> {
					UUID uuid;
					try {
						if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length() != 36)
							throw new IllegalArgumentException();
						uuid = UUID.fromString(value.getAsString());
					} catch (RuntimeException error) { throw new IllegalArgumentException(key + " 必须是标准 UUID 字符串"); }
					patch.store(key, UUIDUtil.CODEC, uuid);
				}
			}
		}
		// Preserve position, UUID, flags, stack and any omitted mod/entity fields when reloading NBT.
		TagValueOutput base = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		entity.saveWithoutId(base);
		var merged = base.buildResult(); merged.merge(patch.buildResult());
		entity.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), merged));
	}
}
