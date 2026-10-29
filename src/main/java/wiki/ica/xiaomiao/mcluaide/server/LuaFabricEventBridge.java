package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import wiki.ica.xiaomiao.mcluaide.event.LuaGameEvents;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registers Fabric's server callbacks once and converts them into immutable Lua event payloads. */

public final class LuaFabricEventBridge {
	private LuaFabricEventBridge() {
	}

	public static void register() {
		LuaGameEvents.BLOCK_PLACED.register(ServerScriptManager::onBlockPlaced);
		LuaGameEvents.COMMAND_EXECUTED.register(LuaFabricEventBridge::command);

		ServerPlayerEvents.JOIN.register(player -> emit("playerJoined", playerData(player)));
		ServerPlayerEvents.LEAVE.register(player -> emit("playerLeft", playerData(player)));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			Map<String, Object> data = playerData(newPlayer);
			data.put("alive", alive);
			emit("playerRespawned", data);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			Map<String, Object> data = playerData(player);
			data.put("fromDimension", dimension(origin));
			data.put("toDimension", dimension(destination));
			emit("playerChangedDimension", data);
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) return;
			if (LuaBlockBreakCapture.markFabricEvent(serverLevel, serverPlayer, pos))
				blockBroken(serverLevel, serverPlayer, pos, state, blockEntity != null);
		});
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				Map<String, Object> data = playerData(serverPlayer);
				putBlock(data, pos, serverLevel.getBlockState(pos), serverLevel);
				data.put("hand", hand(hand));
				data.put("face", direction.getSerializedName());
				emit("blockAttacked", data);
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				Map<String, Object> data = playerData(serverPlayer);
				putBlock(data, hit.getBlockPos(), serverLevel.getBlockState(hit.getBlockPos()), serverLevel);
				data.put("hand", hand(hand));
				data.put("face", hit.getDirection().getSerializedName());
				putItem(data, "item", player.getItemInHand(hand), serverLevel);
				emit("blockUsed", data);
			}
			return InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				Map<String, Object> data = playerData(serverPlayer);
				data.put("hand", hand(hand));
				ItemStack stack = player.getItemInHand(hand);
				putItem(data, "item", stack, serverLevel);
				data.put("count", stack.getCount());
				emit("itemUsed", data);
			}
			return InteractionResult.PASS;
		});

		AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
			if (level instanceof ServerLevel && player instanceof ServerPlayer serverPlayer) {
				Map<String, Object> data = playerData(serverPlayer);
				data.put("hand", hand(hand));
				putEntity(data, "target", target);
				emit("entityAttacked", data);
			}
			return InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				Map<String, Object> data = playerData(serverPlayer);
				data.put("hand", hand(hand));
				putItem(data, "item", player.getItemInHand(hand), serverLevel);
				putEntity(data, "target", target);
				emit("entityUsed", data);
			}
			return InteractionResult.PASS;
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			Map<String, Object> data = entityData(entity);
			data.put("dimension", dimension(level));
			emit("entityLoaded", data);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			Map<String, Object> data = entityData(entity);
			data.put("dimension", dimension(level));
			emit("entityUnloaded", data);
		});
		ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register((original, moved, origin, destination) -> {
			Map<String, Object> data = entityData(moved);
			data.put("fromDimension", dimension(origin));
			data.put("toDimension", dimension(destination));
			data.put("originalUuid", original.getStringUUID());
			emit("entityChangedDimension", data);
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			Map<String, Object> data = entityData(entity);
			putDamage(data, source);
			data.put("baseDamage", baseDamage);
			data.put("damageTaken", damageTaken);
			data.put("blocked", blocked);
			data.put("remainingHealth", entity.getHealth());
			emit("entityDamaged", data);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Map<String, Object> data = entityData(entity);
			putDamage(data, source);
			emit("entityDied", data);
			if (entity instanceof ServerPlayer player) {
				Map<String, Object> playerDeath = playerData(player);
				putDamage(playerDeath, source);
				playerDeath.put("deathMessage", source.getLocalizedDeathMessage(player).getString());
				emit("playerDied", playerDeath);
			}
		});
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, source) -> {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("dimension", dimension(level));
			putEntity(data, "killer", killer);
			putEntity(data, "killed", killed);
			putDamage(data, source);
			emit("entityKilledOther", data);
		});
		ServerEntityEvents.EQUIPMENT_CHANGE.register((entity, slot, oldStack, newStack) -> {
			Map<String, Object> data = entityData(entity);
			data.put("slot", slot.getSerializedName());
			ServerLevel level = (ServerLevel) entity.level();
			putItem(data, "oldItem", oldStack, level);
			putItem(data, "newItem", newStack, level);
			data.put("oldCount", oldStack.getCount());
			data.put("newCount", newStack.getCount());
			emit("equipmentChanged", data);
		});

		ServerMobEffectEvents.AFTER_ADD.register((effect, entity, context) -> {
			Map<String, Object> data = entityData(entity);
			putEffect(data, effect);
			data.put("fromCommand", context.isFromCommand());
			emit("effectAdded", data);
		});
		ServerMobEffectEvents.AFTER_REMOVE.register((effect, entity, context) -> {
			Map<String, Object> data = entityData(entity);
			putEffect(data, effect);
			data.put("fromCommand", context.isFromCommand());
			emit("effectRemoved", data);
		});
		EntitySleepEvents.START_SLEEPING.register((entity, pos) -> sleep("sleepStarted", entity, pos));
		EntitySleepEvents.STOP_SLEEPING.register((entity, pos) -> sleep("sleepStopped", entity, pos));

		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) -> {
			Map<String, Object> data = chunkData(level, chunk.getPos().x(), chunk.getPos().z());
			data.put("newChunk", newChunk);
			emit("chunkLoaded", data);
		});
		ServerChunkEvents.CHUNK_GENERATE.register((level, chunk) ->
				emit("chunkGenerated", chunkData(level, chunk.getPos().x(), chunk.getPos().z())));
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
				emit("chunkUnloaded", chunkData(level, chunk.getPos().x(), chunk.getPos().z())));
		ServerLevelEvents.LOAD.register((server, level) -> emit("levelLoaded", levelData(level)));
		ServerLevelEvents.UNLOAD.register((server, level) -> emit("levelUnloaded", levelData(level)));
		ServerTickEvents.START_SERVER_TICK.register(ServerScriptManager::tickDebugger);
		ServerTickEvents.END_SERVER_TICK.register(LuaTeamAggression::tick);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("tick", server.getTickCount());
			data.put("playerCount", server.getPlayerCount());
			emit("serverTick", data);
		});
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			Map<String, Object> data = levelData(level);
			data.put("tick", level.getGameTime());
			emit("levelTick", data);
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, player, bound) -> {
			Map<String, Object> data = playerData(player);
			data.put("message", message.signedContent());
			data.put("decoratedMessage", message.decoratedContent().getString());
			emit("chat", data);
		});
	}

	private static void command(CommandSourceStack source, String command) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("command", command);
		data.put("sourceName", source.getTextName());
		data.put("dimension", dimension(source.getLevel()));
		data.put("x", source.getPosition().x());
		data.put("y", source.getPosition().y());
		data.put("z", source.getPosition().z());
		if (source.getEntity() != null) putEntity(data, "source", source.getEntity());
		if (source.getPlayer() != null) {
			data.put("playerUuid", source.getPlayer().getUUID().toString());
			data.put("playerName", source.getPlayer().getName().getString());
		}
		emit("command", data);
	}

	private static void sleep(String type, LivingEntity entity, BlockPos pos) {
		Map<String, Object> data = entityData(entity);
		data.put("bedX", pos.getX());
		data.put("bedY", pos.getY());
		data.put("bedZ", pos.getZ());
		emit(type, data);
	}

	private static Map<String, Object> playerData(ServerPlayer player) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("playerUuid", player.getUUID().toString());
		data.put("playerName", player.getName().getString());
		data.put("x", player.getX());
		data.put("y", player.getY());
		data.put("z", player.getZ());
		data.put("dimension", dimension(player.level()));
		return data;
	}

	private static Map<String, Object> entityData(Entity entity) {
		Map<String, Object> data = new LinkedHashMap<>();
		putEntity(data, "entity", entity);
		data.put("dimension", dimension(entity.level()));
		return data;
	}

	private static void putEntity(Map<String, Object> data, String prefix, Entity entity) {
		data.put(prefix + "Uuid", entity.getStringUUID());
		data.put(prefix + "Type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		data.put(prefix + "Name", entity.getName().getString());
		data.put(prefix + "RuntimeId", entity.getId());
		data.put(prefix + "X", entity.getX());
		data.put(prefix + "Y", entity.getY());
		data.put(prefix + "Z", entity.getZ());
	}

	static void blockBroken(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, boolean hadBlockEntity) {
		Map<String, Object> data = playerData(player);
		putBlock(data, pos, state, level);
		data.put("hadBlockEntity", hadBlockEntity);
		emit("blockBroken", data);
	}

	private static void putBlock(Map<String, Object> data, BlockPos pos, BlockState state, ServerLevel level) {
		data.put("blockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		data.put("x", pos.getX());
		data.put("y", pos.getY());
		data.put("z", pos.getZ());
		data.put("dimension", dimension(level));
	}

	private static void putDamage(Map<String, Object> data, DamageSource source) {
		data.put("damageType", source.getMsgId());
		if (source.getEntity() != null) putEntity(data, "attacker", source.getEntity());
		if (source.getDirectEntity() != null) putEntity(data, "direct", source.getDirectEntity());
	}

	private static void putEffect(Map<String, Object> data, MobEffectInstance effect) {
		data.put("effectId", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
		data.put("duration", effect.getDuration());
		data.put("amplifier", effect.getAmplifier());
		data.put("ambient", effect.isAmbient());
		data.put("visible", effect.isVisible());
	}

	private static Map<String, Object> chunkData(ServerLevel level, int chunkX, int chunkZ) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("chunkX", chunkX);
		data.put("chunkZ", chunkZ);
		data.put("minBlockX", chunkX << 4);
		data.put("minBlockZ", chunkZ << 4);
		data.put("dimension", dimension(level));
		return data;
	}

	private static Map<String, Object> levelData(ServerLevel level) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("dimension", dimension(level));
		data.put("gameTime", level.getGameTime());
		data.put("dayTime", level.getOverworldClockTime());
		return data;
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static void putItem(Map<String, Object> data, String prefix, ItemStack stack, ServerLevel level) {
		data.put(prefix + "Id", itemId(stack));
		data.put(prefix + "Count", stack.isEmpty() ? 0 : stack.getCount());
		data.put(prefix + "Components", LuaItemStackCodec.toJavaComponents(level.getServer(), stack));
	}

	private static String hand(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand";
	}

	private static String dimension(Level level) {
		return level.dimension().identifier().toString();
	}

	private static void emit(String type, Map<String, ?> data) {
		ServerScriptManager.emitEvent(type, data);
	}
}
