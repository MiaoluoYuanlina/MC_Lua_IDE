package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Entity NBT that is not a data component: Motion, ExplosionPower, fuse times, owners and so on. The fields an entity offers are
 * simply the ones its own serializer writes, so every entity type (modded ones too) is covered without a hand-made list, and a
 * value is merged into the entity's NBT with the type of the saved field kept (a byte stays a byte, a double list a double list).
 */
final class LuaEntityNbtConfig {
	/** Identity, position and passengers are set when the entity is spawned or are handled by other settings. */
	private static final Set<String> EXCLUDED = Set.of("id", "UUID", "Pos", "Passengers", "CustomName", "Glowing", "Invulnerable", "NoGravity");

	private LuaEntityNbtConfig() { }

	private static CompoundTag saved(MinecraftServer server, Entity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		entity.saveWithoutId(output);
		return output.buildResult();
	}

	static boolean supports(MinecraftServer server, Entity entity, String key) {
		if (entity == null || key == null || key.isEmpty() || key.indexOf(':') >= 0 || EXCLUDED.contains(key)) return false;
		try { return saved(server, entity).contains(key); } catch (RuntimeException failure) { return false; }
	}

	static List<LuaDisplayEntityConfig.Field> fields(MinecraftServer server, Entity entity, Set<String> alreadyOffered) {
		if (entity == null) return List.of();
		CompoundTag tag;
		try { tag = saved(server, entity); } catch (RuntimeException failure) { return List.of(); }
		List<LuaDisplayEntityConfig.Field> result = new ArrayList<>();
		for (String key : new java.util.TreeSet<>(tag.keySet())) {
			if (EXCLUDED.contains(key) || key.indexOf(':') >= 0 || alreadyOffered.contains(key)) continue;
			JsonElement value = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag.get(key));
			String[] known = LuaEntityNbtLabels.LABELS.get(key);
			result.add(new LuaDisplayEntityConfig.Field(key, known == null ? key : known[0], known == null ? "实体 NBT 字段（原样写入实体数据，保持原有数值类型）" : known[1], value.deepCopy(),
					ComponentTreeSchema.observed(key, value)));
		}
		return List.copyOf(result);
	}

	static void apply(MinecraftServer server, Entity entity, JsonObject values) {
		if (values.isEmpty()) return;
		CompoundTag base = saved(server, entity);
		CompoundTag patch = new CompoundTag();
		for (var field : values.entrySet()) {
			String key = field.getKey();
			if (!supports(server, entity, key)) throw new IllegalArgumentException("实体不支持 NBT 字段：" + key);
			patch.put(key, convert(field.getValue(), base.get(key), key));
		}
		CompoundTag merged = base.copy();
		merged.merge(patch);
		entity.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), merged));
	}

	static Tag convert(JsonElement value, Tag template, String path) {
		if (value == null || value.isJsonNull()) throw new IllegalArgumentException(path + " 不能为 null");
		if (value.isJsonObject()) {
			CompoundTag result = new CompoundTag();
			CompoundTag known = template instanceof CompoundTag compound ? compound : null;
			for (var entry : value.getAsJsonObject().entrySet())
				result.put(entry.getKey(), convert(entry.getValue(), known == null ? null : known.get(entry.getKey()), path + "." + entry.getKey()));
			return result;
		}
		if (value.isJsonArray()) {
			JsonArray array = value.getAsJsonArray();
			if (template instanceof net.minecraft.nbt.IntArrayTag) {
				int[] numbers = new int[array.size()];
				for (int i = 0; i < numbers.length; i++) numbers[i] = (int) exact(array.get(i).getAsBigDecimal(), Integer.MIN_VALUE, Integer.MAX_VALUE, path + "[" + i + "]");
				return new net.minecraft.nbt.IntArrayTag(numbers);
			}
			if (template instanceof net.minecraft.nbt.LongArrayTag) {
				long[] numbers = new long[array.size()];
				for (int i = 0; i < numbers.length; i++) numbers[i] = array.get(i).getAsBigDecimal().setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
				return new net.minecraft.nbt.LongArrayTag(numbers);
			}
			Tag element = template instanceof ListTag list && !list.isEmpty() ? list.get(0) : null;
			ListTag result = new ListTag();
			int index = 0;
			for (JsonElement child : array) result.add(convert(child, element, path + "[" + index++ + "]"));
			return result;
		}
		var primitive = value.getAsJsonPrimitive();
		if (primitive.isBoolean()) return ByteTag.valueOf(primitive.getAsBoolean());
		if (primitive.isString()) return StringTag.valueOf(primitive.getAsString());
		java.math.BigDecimal number = primitive.getAsBigDecimal();
		if (template instanceof ByteTag) return ByteTag.valueOf((byte) exact(number, Byte.MIN_VALUE, Byte.MAX_VALUE, path));
		if (template instanceof ShortTag) return ShortTag.valueOf((short) exact(number, Short.MIN_VALUE, Short.MAX_VALUE, path));
		if (template instanceof IntTag) return IntTag.valueOf((int) exact(number, Integer.MIN_VALUE, Integer.MAX_VALUE, path));
		if (template instanceof LongTag) return LongTag.valueOf(number.setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact());
		if (template instanceof FloatTag) return FloatTag.valueOf(number.floatValue());
		if (template instanceof DoubleTag) return DoubleTag.valueOf(number.doubleValue());
		try { return IntTag.valueOf(number.intValueExact()); } catch (ArithmeticException notInteger) { return DoubleTag.valueOf(number.doubleValue()); }
	}

	private static long exact(java.math.BigDecimal number, long minimum, long maximum, String path) {
		try {
			long value = number.longValueExact();
			if (value < minimum || value > maximum) throw new ArithmeticException();
			return value;
		} catch (ArithmeticException invalid) {
			throw new IllegalArgumentException(path + " 必须是 " + minimum + "～" + maximum + " 之间的整数");
		}
	}
}
