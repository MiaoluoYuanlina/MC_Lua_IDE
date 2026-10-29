package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Adds data (SNBT) to, or hides (removes) data from, a live entity or the block entity of a placed block. "Adding" merges the given
 * fields into the saved data and keeps the type of every field that already exists; "hiding" drops top-level fields, which the game
 * reads back as their defaults.
 */
final class LuaDataLabels {
	private static final Set<String> ENTITY_PROTECTED = Set.of("id", "UUID", "Pos", "Passengers");
	private static final Set<String> BLOCK_PROTECTED = Set.of("id", "x", "y", "z");

	private LuaDataLabels() { }

	/** A Lua table or an SNBT string such as {@code {Fuse:40s}}. */
	static CompoundTag parse(LuaValue data, CompoundTag template) {
		if (data == null || data.isnil()) throw new LuaError("请提供要添加的数据（Lua 表或 SNBT 字符串）");
		if (data.isstring()) {
			try { return TagParser.parseCompoundFully(data.checkjstring()); }
			catch (CommandSyntaxException invalid) { throw new LuaError("SNBT 无法解析：" + invalid.getMessage()); }
		}
		if (!data.istable()) throw new LuaError("数据必须是 Lua 表或 SNBT 字符串");
		JsonObject json = JsonParser.parseString(LuaItemStackCodec.objectToJson(data).toString()).getAsJsonObject();
		CompoundTag result = new CompoundTag();
		try {
			for (var field : json.entrySet())
				result.put(field.getKey(), LuaEntityNbtConfig.convert(field.getValue(), template == null ? null : template.get(field.getKey()), field.getKey()));
		} catch (IllegalArgumentException invalid) { throw new LuaError(invalid.getMessage()); }
		return result;
	}

	/** One field name or a list of them. */
	static List<String> keys(LuaValue value) {
		List<String> keys = new ArrayList<>();
		if (value.isstring()) keys.add(value.checkjstring());
		else if (value.istable()) for (int i = 1; i <= value.length(); i++) keys.add(value.get(i).checkjstring());
		else throw new LuaError("请提供要隐藏的字段名或字段名列表");
		if (keys.isEmpty()) throw new LuaError("字段名列表不能为空");
		return keys;
	}

	static void entity(MinecraftServer server, Entity entity, LuaValue data, boolean add) {
		if (entity instanceof ServerPlayer) throw new LuaError("不能通过该函数修改玩家数据");
		CompoundTag base = LuaEntityNbtConfig.saved(server, entity);
		CompoundTag merged = base.copy();
		if (add) {
			CompoundTag patch = parse(data, base);
			for (String key : patch.keySet()) if (ENTITY_PROTECTED.contains(key)) throw new LuaError("不能修改实体字段：" + key);
			merged.merge(patch);
		} else {
			for (String key : keys(data)) {
				if (ENTITY_PROTECTED.contains(key)) throw new LuaError("不能隐藏实体字段：" + key);
				merged.remove(key);
			}
		}
		entity.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), merged));
	}

	static void block(MinecraftServer server, ServerLevel level, BlockPos pos, LuaValue data, boolean add) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) throw new LuaError("该位置的方块没有方块实体，没有可添加或隐藏的数据");
		BlockState state = level.getBlockState(pos);
		CompoundTag base = blockEntity.saveWithFullMetadata(server.registryAccess());
		CompoundTag merged = base.copy();
		if (add) {
			CompoundTag patch = parse(data, base);
			for (String key : patch.keySet()) if (BLOCK_PROTECTED.contains(key)) throw new LuaError("不能修改方块实体字段：" + key);
			merged.merge(patch);
		} else {
			for (String key : keys(data)) {
				if (BLOCK_PROTECTED.contains(key)) throw new LuaError("不能隐藏方块实体字段：" + key);
				merged.remove(key);
			}
		}
		BlockEntity updated = BlockEntity.loadStatic(pos, state, merged, server.registryAccess());
		if (updated == null) throw new LuaError("方块实体无法读取修改后的数据");
		level.setBlockEntity(updated);
		updated.setChanged();
		level.sendBlockUpdated(pos, state, state, 3);
	}
}
