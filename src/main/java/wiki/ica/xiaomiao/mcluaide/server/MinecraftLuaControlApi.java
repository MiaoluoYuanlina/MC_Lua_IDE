package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Broad server-control namespaces layered on top of the small strongly typed core API. */
final class MinecraftLuaControlApi {
	private static final int API_TIMEOUT_SECONDS = 10;
	private static final Identifier DEFAULT_DIMENSION = Identifier.withDefaultNamespace("overworld");
	private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:+\\-/]{1,128}");
	private static final Pattern SCORE_NAME = Pattern.compile("[A-Za-z0-9_.+\\-]{1,64}");

	private MinecraftLuaControlApi() {
	}

	static void install(LuaTable mc, MinecraftServer server) {
		extend(mc, "Block", blockApi(server));
		extend(mc, "Item", itemApi(server));
		extend(mc, "Player", playerApi(server));
		extend(mc, "World", worldApi(server));
		extend(mc, "Server", serverApi(server));
		setNamespace(mc, "Entity", entityApi(server));
		setNamespace(mc, "Effect", effectApi(server));
		setNamespace(mc, "Sound", soundApi(server));
		setNamespace(mc, "Particle", particleApi(server));
		setNamespace(mc, "Title", titleApi(server));
		setNamespace(mc, "Score", scoreApi(server));
		setNamespace(mc, "Team", teamApi(server));
		setNamespace(mc, "GameRule", gameRuleApi(server));
		setNamespace(mc, "Border", borderApi(server));
		setNamespace(mc, "Attribute", attributeApi(server));
		setNamespace(mc, "Advancement", advancementApi(server));
		setNamespace(mc, "Recipe", recipeApi(server));
		setNamespace(mc, "Loot", lootApi(server));
		setNamespace(mc, "Structure", structureApi(server));
		setNamespace(mc, "Registry", registryApi());
	}

	private static LuaTable blockApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "exists", args -> LuaValue.valueOf(BuiltInRegistries.BLOCK.containsKey(id(args, 1))));
		bind(api, "name", args -> {
			Identifier id = id(args, 1);
			var block = BuiltInRegistries.BLOCK.getOptional(id)
					.orElseThrow(() -> new LuaError("未知方块：" + id));
			return LuaValue.valueOf(Component.translatable(block.getDescriptionId()).getString());
		});
		bind(api, "fill", args -> fillBlocks(server, args, false));
		bind(api, "replace", args -> fillBlocks(server, args, true));
		bind(api, "clone", args -> cloneBlocks(server, args));
		return api;
	}

	private static LuaTable itemApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "exists", args -> LuaValue.valueOf(BuiltInRegistries.ITEM.containsKey(id(args, 1))));
		bind(api, "name", args -> {
			Identifier id = id(args, 1);
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new LuaError("未知物品：" + id));
			return LuaValue.valueOf(item.getName(item.getDefaultInstance()).getString());
		});
		bind(api, "maxStack", args -> {
			Identifier id = id(args, 1);
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new LuaError("未知物品：" + id));
			return LuaValue.valueOf(item.getDefaultMaxStackSize());
		});
		bind(api, "clear", args -> {
			if (!args.arg(4).isnil()) {
				if (args.arg(2).isnil()) throw new LuaError("按组件清除物品时必须填写 itemId");
				UUID uuid = playerUuid(args, 1);
				int maximum = args.arg(3).isnil() ? Integer.MAX_VALUE
						: rangedInt(args.checkint(3), 0, 999999, "清除数量");
				onServer(server, current -> {
					ServerPlayer player = player(current, uuid);
					ItemStack expected = LuaItemStackCodec.create(
							current, args.arg(2), LuaValue.valueOf(1), args.arg(4), 1);
					int remaining = maximum;
					for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
						ItemStack stack = player.getInventory().getItem(slot);
						if (!LuaItemStackCodec.sameItemAndComponents(stack, expected)) continue;
						int removed = Math.min(stack.getCount(), remaining);
						stack.shrink(removed);
						remaining -= removed;
					}
					player.inventoryMenu.broadcastChanges();
					return true;
				});
				return LuaValue.TRUE;
			}
			StringBuilder command = new StringBuilder("clear ").append(playerTarget(server, args, 1));
			if (!args.arg(2).isnil()) command.append(' ').append(id(args, 2));
			if (!args.arg(3).isnil()) command.append(' ').append(rangedInt(args.checkint(3), 0, 999999, "清除数量"));
			return runCommand(server, command.toString());
		});
		bind(api, "replaceHeld", args -> {
			UUID uuid = playerUuid(args, 1);
			onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				ItemStack stack = LuaItemStackCodec.create(
						current, args.arg(2), args.arg(3), args.arg(4), 999);
				if (stack.getCount() > stack.getMaxStackSize()) stack.setCount(stack.getMaxStackSize());
				player.setItemSlot(EquipmentSlot.MAINHAND, stack);
				player.inventoryMenu.broadcastChanges();
				return true;
			});
			return LuaValue.TRUE;
		});
		return api;
	}

	private static LuaTable playerApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "online", args -> {
			UUID uuid = playerUuid(args, 1);
			boolean online = onServer(server, current -> current.getPlayerList().getPlayer(uuid) != null);
			return LuaValue.valueOf(online);
		});
		bind(api, "food", args -> {
			UUID uuid = playerUuid(args, 1);
			return onServer(server, current -> {
				FoodData food = player(current, uuid).getFoodData();
				LuaTable result = new LuaTable();
				result.set("level", food.getFoodLevel());
				result.set("saturation", food.getSaturationLevel());
				result.set("needsFood", LuaValue.valueOf(food.needsFood()));
				return result;
			});
		});
		bind(api, "setFood", args -> {
			UUID uuid = playerUuid(args, 1);
			int foodLevel = rangedInt(args.checkint(2), 0, 20, "饥饿值");
			double saturation = finite(args.optdouble(3, Math.min(foodLevel, 5.0)), "饱和度");
			onServer(server, current -> {
				FoodData food = player(current, uuid).getFoodData();
				food.setFoodLevel(foodLevel);
				food.setSaturation((float) Math.clamp(saturation, 0.0, foodLevel));
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "experience", args -> {
			UUID uuid = playerUuid(args, 1);
			return onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				LuaTable result = new LuaTable();
				result.set("level", player.experienceLevel);
				result.set("total", player.totalExperience);
				result.set("progress", player.experienceProgress);
				return result;
			});
		});
		bind(api, "addExperience", args -> {
			String unit = args.optboolean(3, false) ? "levels" : "points";
			return runCommand(server, "experience add " + playerTarget(server, args, 1) + " " + args.checkint(2) + " " + unit);
		});
		bind(api, "setExperience", args -> {
			String unit = args.optboolean(3, false) ? "levels" : "points";
			return runCommand(server, "experience set " + playerTarget(server, args, 1) + " "
					+ Math.max(0, args.checkint(2)) + " " + unit);
		});
		bind(api, "heal", args -> {
			UUID uuid = playerUuid(args, 1);
			Double amount = args.arg(2).isnil() ? null : positive(args.checkdouble(2), "治疗量");
			double healed = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				float before = player.getHealth();
				player.setHealth(amount == null ? player.getMaxHealth()
						: (float) Math.min(player.getMaxHealth(), before + amount));
				return (double) (player.getHealth() - before);
			});
			return LuaValue.valueOf(healed);
		});
		bind(api, "damage", args -> {
			String type = args.arg(3).isnil() ? "minecraft:generic" : id(args, 3).toString();
			return runCommand(server, "damage " + playerTarget(server, args, 1) + " "
					+ positive(args.checkdouble(2), "伤害值") + " " + type);
		});
		bind(api, "kill", args -> runCommand(server, "kill " + playerTarget(server, args, 1)));
		bind(api, "kick", args -> runCommand(server, "kick " + playerTarget(server, args, 1) + " "
				+ cleanText(args.optjstring(2, "你已被服务器脚本断开连接"))));
		bind(api, "setSpawn", args -> runInDimension(server, optionalDimension(args, 5), "spawnpoint "
				+ playerTarget(server, args, 1) + " " + integer(args, 2) + " " + integer(args, 3) + " " + integer(args, 4)));
		bind(api, "setRotation", args -> runCommand(server, "tp " + playerTarget(server, args, 1) + " ~ ~ ~ "
				+ finite(args.checkdouble(2), "yaw") + " " + finite(args.checkdouble(3), "pitch")));
		return api;
	}

	private static LuaTable entityApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "get", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			return onServer(server, current -> {
				Entity entity = findEntity(current, uuid);
				return entity == null ? LuaValue.NIL : entityTable(entity);
			});
		});
		bind(api, "exists", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			boolean exists = onServer(server, current -> findEntity(current, uuid) != null);
			return LuaValue.valueOf(exists);
		});
		bind(api, "list", args -> onServer(server, current -> entityList(level(current, optionalDimension(args, 1)), null)));
		bind(api, "nearby", args -> {
			double x = finite(args.checkdouble(1), "x");
			double y = finite(args.checkdouble(2), "y");
			double z = finite(args.checkdouble(3), "z");
			double radius = Math.clamp(positive(args.checkdouble(4), "半径"), 0.0, 1024.0);
			Identifier dimension = optionalDimension(args, 5);
			return onServer(server, current -> entityList(level(current, dimension), entity ->
					entity.distanceToSqr(x, y, z) <= radius * radius));
		});
		bind(api, "spawn", args -> {
			Identifier entityId = id(args, 1);
			double x = coordinate(args, 2);
			double y = coordinate(args, 3);
			double z = coordinate(args, 4);
			Identifier dimension = optionalDimension(args, 5);
			LuaValue components = args.arg(6);
			return onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId)
						.orElseThrow(() -> new LuaError("未知实体：" + entityId));
				Entity entity = type.create(level, EntitySpawnReason.COMMAND);
				if (entity == null) throw new LuaError("该实体类型不能由脚本生成：" + entityId);
				entity.setPos(x, y, z);
				applyEntityComponents(current, entity, components);
				return LuaValue.valueOf(level.addFreshEntity(entity));
			});
		});
		bind(api, "teleport", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			double x = finite(args.checkdouble(2), "x");
			double y = finite(args.checkdouble(3), "y");
			double z = finite(args.checkdouble(4), "z");
			Identifier targetDimension = args.arg(5).isnil() ? null : id(args, 5);
			boolean moved = onServer(server, current -> {
				Entity entity = requireEntity(current, uuid);
				ServerLevel target = targetDimension == null ? (ServerLevel) entity.level() : level(current, targetDimension);
				return entity.teleportTo(target, x, y, z, Set.of(), entity.getYRot(), entity.getXRot(), true);
			});
			return LuaValue.valueOf(moved);
		});
		bind(api, "velocity", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			double x = finite(args.checkdouble(2), "速度 X");
			double y = finite(args.checkdouble(3), "速度 Y");
			double z = finite(args.checkdouble(4), "速度 Z");
			onServer(server, current -> { requireEntity(current, uuid).setDeltaMovement(x, y, z); return true; });
			return LuaValue.TRUE;
		});
		bind(api, "kill", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			onServer(server, current -> { Entity entity = requireEntity(current, uuid); entity.kill((ServerLevel) entity.level()); return true; });
			return LuaValue.TRUE;
		});
		bind(api, "remove", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			onServer(server, current -> {
				Entity entity = requireEntity(current, uuid);
				if (entity instanceof ServerPlayer) throw new LuaError("玩家不能用 Entity.remove 移除，请使用 Player.kick 或 Player.kill");
				entity.discard();
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "setName", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			LuaValue name = args.arg(2);
			boolean visible = args.optboolean(3, true);
			onServer(server, current -> {
				Entity entity = requireEntity(current, uuid);
				entity.setCustomName(LuaTextComponentCodec.decode(current, name));
				entity.setCustomNameVisible(visible);
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "setGlowing", args -> mutateEntity(server, args, entity -> entity.setGlowingTag(args.checkboolean(2))));
		bind(api, "setInvulnerable", args -> mutateEntity(server, args, entity -> entity.setInvulnerable(args.checkboolean(2))));
		bind(api, "setGravity", args -> mutateEntity(server, args, entity -> entity.setNoGravity(!args.checkboolean(2))));
		bind(api, "fire", args -> mutateEntity(server, args, entity -> {
			int seconds = rangedInt(args.checkint(2), 0, 86400, "燃烧秒数");
			if (seconds == 0) entity.clearFire(); else entity.setRemainingFireTicks(seconds * 20);
		}));
		bind(api, "tags", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			return onServer(server, current -> stringTable(requireEntity(current, uuid).entityTags().stream().sorted().toList()));
		});
		bind(api, "addTag", args -> tagEntity(server, args, true));
		bind(api, "removeTag", args -> tagEntity(server, args, false));
		return api;
	}

	private static LuaTable effectApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "give", args -> runCommand(server, "effect give " + entityUuid(args, 1) + " " + id(args, 2) + " "
				+ rangedInt(args.optint(3, 30), 1, 1000000, "持续秒数") + " "
				+ rangedInt(args.optint(4, 0), 0, 255, "效果等级") + " " + !args.optboolean(5, true)));
		bind(api, "clear", args -> runCommand(server, "effect clear " + entityUuid(args, 1)
				+ (args.arg(2).isnil() ? "" : " " + id(args, 2))));
		bind(api, "list", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			return onServer(server, current -> {
				Entity entity = requireEntity(current, uuid);
				if (!(entity instanceof LivingEntity living)) throw new LuaError("该实体不是生物，不能拥有状态效果");
				LuaTable result = new LuaTable();
				int index = 1;
				for (var effect : living.getActiveEffects()) {
					LuaTable entry = new LuaTable();
					entry.set("id", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
					entry.set("duration", effect.getDuration());
					entry.set("amplifier", effect.getAmplifier());
					entry.set("ambient", LuaValue.valueOf(effect.isAmbient()));
					entry.set("visible", LuaValue.valueOf(effect.isVisible()));
					result.set(index++, entry);
				}
				return result;
			});
		});
		return api;
	}

	private static LuaTable soundApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "play", args -> {
			String target = args.arg(9).isnil() ? "@a" : playerTarget(server, args, 9);
			String category = enumToken(args.optjstring(7, "master"), "声音分类",
					"master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice", "ui");
			String command = "playsound " + id(args, 1) + " " + category + " " + target + " "
					+ coordinate(args, 2) + " " + coordinate(args, 3) + " " + coordinate(args, 4) + " "
					+ positive(args.optdouble(5, 1.0), "音量") + " " + positive(args.optdouble(6, 1.0), "音调");
			return runInDimension(server, optionalDimension(args, 8), command);
		});
		bind(api, "stop", args -> {
			String category = args.arg(3).isnil() ? "*" : enumToken(args.checkjstring(3), "声音分类",
					"master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice", "ui", "*");
			String sound = args.arg(2).isnil() ? "" : " " + id(args, 2);
			return runCommand(server, "stopsound " + playerTarget(server, args, 1) + " " + category + sound);
		});
		return api;
	}

	private static LuaTable particleApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "spawn", args -> {
			int count = rangedInt(args.optint(5, 1), 0, 100000, "粒子数量");
			double spread = positive(args.optdouble(6, 0.0), "扩散范围");
			double speed = positive(args.optdouble(7, 0.0), "粒子速度");
			String mode = args.optboolean(8, false) ? "force" : "normal";
			String command = "particle " + id(args, 1) + " " + coordinate(args, 2) + " " + coordinate(args, 3)
					+ " " + coordinate(args, 4) + " " + spread + " " + spread + " " + spread + " " + speed
					+ " " + count + " " + mode + " @a";
			return runInDimension(server, optionalDimension(args, 9), command);
		});
		return api;
	}

	private static LuaTable titleApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "show", args -> title(server, args, "title"));
		bind(api, "subtitle", args -> title(server, args, "subtitle"));
		bind(api, "actionbar", args -> title(server, args, "actionbar"));
		bind(api, "times", args -> runCommand(server, "title " + playerTarget(server, args, 1) + " times "
				+ rangedInt(args.checkint(2), 0, 72000, "淡入时间") + " "
				+ rangedInt(args.checkint(3), 0, 72000, "停留时间") + " "
				+ rangedInt(args.checkint(4), 0, 72000, "淡出时间")));
		bind(api, "clear", args -> runCommand(server, "title " + playerTarget(server, args, 1) + " clear"));
		bind(api, "reset", args -> runCommand(server, "title " + playerTarget(server, args, 1) + " reset"));
		return api;
	}

	private static LuaTable scoreApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "create", args -> runCommand(server, "scoreboard objectives add " + scoreName(args, 1)
				+ " " + safeToken(args.optjstring(2, "dummy"), "计分标准")
				+ (args.arg(3).isnil() ? "" : " " + LuaTextComponentCodec.commandJson(server, args.arg(3)))));
		bind(api, "remove", args -> runCommand(server, "scoreboard objectives remove " + scoreName(args, 1)));
		bind(api, "set", args -> runCommand(server, "scoreboard players set " + scoreHolder(args.checkjstring(1))
				+ " " + scoreName(args, 2) + " " + args.checkint(3)));
		bind(api, "add", args -> runCommand(server, "scoreboard players add " + scoreHolder(args.checkjstring(1))
				+ " " + scoreName(args, 2) + " " + args.checkint(3)));
		bind(api, "reset", args -> runCommand(server, "scoreboard players reset " + scoreHolder(args.checkjstring(1))
				+ (args.arg(2).isnil() ? "" : " " + scoreName(args, 2))));
		bind(api, "display", args -> runCommand(server, "scoreboard objectives setdisplay "
				+ safeToken(args.checkjstring(1), "显示位置") + (args.arg(2).isnil() ? "" : " " + scoreName(args, 2))));
		return api;
	}

	private static LuaTable teamApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "create", args -> runCommand(server, "team add " + scoreName(args, 1)
				+ (args.arg(2).isnil() ? "" : " " + LuaTextComponentCodec.commandJson(server, args.arg(2)))));
		bind(api, "remove", args -> runCommand(server, "team remove " + scoreName(args, 1)));
		bind(api, "join", args -> runCommand(server, "team join " + scoreName(args, 1) + " " + scoreHolder(args.checkjstring(2))));
		bind(api, "leave", args -> runCommand(server, "team leave " + scoreHolder(args.checkjstring(1))));
		bind(api, "color", args -> teamModify(server, args, "color", safeToken(args.checkjstring(2), "颜色")));
		bind(api, "friendlyFire", args -> teamModify(server, args, "friendlyFire", Boolean.toString(args.checkboolean(2))));
		bind(api, "prefix", args -> teamModify(server, args, "prefix", LuaTextComponentCodec.commandJson(server, args.arg(2))));
		bind(api, "suffix", args -> teamModify(server, args, "suffix", LuaTextComponentCodec.commandJson(server, args.arg(2))));
		return api;
	}

	private static LuaTable gameRuleApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "names", args -> registryTable(BuiltInRegistries.GAME_RULE));
		bind(api, "set", args -> runInDimension(server, optionalDimension(args, 3), "gamerule "
				+ safeToken(args.checkjstring(1), "游戏规则") + " " + safeToken(args.checkjstring(2), "规则值")));
		return api;
	}

	private static LuaTable borderApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "set", args -> runCommand(server, "worldborder set " + positive(args.checkdouble(1), "边界直径")
				+ " " + rangedInt(args.optint(2, 0), 0, 86400, "变化秒数")));
		bind(api, "add", args -> runCommand(server, "worldborder add " + finite(args.checkdouble(1), "增加直径")
				+ " " + rangedInt(args.optint(2, 0), 0, 86400, "变化秒数")));
		bind(api, "center", args -> runCommand(server, "worldborder center " + coordinate(args, 1) + " " + coordinate(args, 2)));
		bind(api, "warning", args -> runCommand(server, "worldborder warning distance "
				+ rangedInt(args.checkint(1), 0, 30000000, "警告距离")));
		bind(api, "damage", args -> runCommand(server, "worldborder damage amount " + positive(args.checkdouble(1), "边界伤害")));
		return api;
	}

	private static LuaTable attributeApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "setBase", args -> runCommand(server, "attribute " + entityUuid(args, 1) + " " + id(args, 2)
				+ " base set " + finite(args.checkdouble(3), "属性值")));
		bind(api, "addModifier", args -> {
			String operation = enumToken(args.checkjstring(5), "运算方式",
					"add_value", "add_multiplied_base", "add_multiplied_total");
			return runCommand(server, "attribute " + entityUuid(args, 1) + " " + id(args, 2) + " modifier add "
					+ id(args, 3) + " " + finite(args.checkdouble(4), "修改量") + " " + operation);
		});
		bind(api, "removeModifier", args -> runCommand(server, "attribute " + entityUuid(args, 1) + " " + id(args, 2)
				+ " modifier remove " + id(args, 3)));
		return api;
	}

	private static LuaTable advancementApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "grant", args -> runCommand(server, "advancement grant " + playerTarget(server, args, 1) + " only " + id(args, 2)));
		bind(api, "revoke", args -> runCommand(server, "advancement revoke " + playerTarget(server, args, 1) + " only " + id(args, 2)));
		return api;
	}

	private static LuaTable recipeApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "give", args -> runCommand(server, "recipe give " + playerTarget(server, args, 1) + " " + id(args, 2)));
		bind(api, "take", args -> runCommand(server, "recipe take " + playerTarget(server, args, 1) + " " + id(args, 2)));
		return api;
	}

	private static LuaTable lootApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "give", args -> runCommand(server, "loot give " + playerTarget(server, args, 1) + " loot " + id(args, 2)));
		bind(api, "insert", args -> runInDimension(server, optionalDimension(args, 5), "loot insert "
				+ integer(args, 1) + " " + integer(args, 2) + " " + integer(args, 3) + " loot " + id(args, 4)));
		return api;
	}

	private static LuaTable structureApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "place", args -> runInDimension(server, optionalDimension(args, 5), "place template " + id(args, 1)
				+ " " + integer(args, 2) + " " + integer(args, 3) + " " + integer(args, 4)));
		return api;
	}

	private static LuaTable registryApi() {
		LuaTable api = new LuaTable();
		bind(api, "kinds", args -> stringTable(List.of("blocks", "items", "entities", "effects", "sounds",
				"particles", "potions", "gameRules", "fluids", "blockEntities", "attributes", "dataComponents")));
		bind(api, "list", args -> registryTable(registry(args.checkjstring(1))));
		bind(api, "blocks", args -> registryTable(BuiltInRegistries.BLOCK));
		bind(api, "items", args -> registryTable(BuiltInRegistries.ITEM));
		bind(api, "entities", args -> registryTable(BuiltInRegistries.ENTITY_TYPE));
		bind(api, "effects", args -> registryTable(BuiltInRegistries.MOB_EFFECT));
		bind(api, "sounds", args -> registryTable(BuiltInRegistries.SOUND_EVENT));
		bind(api, "particles", args -> registryTable(BuiltInRegistries.PARTICLE_TYPE));
		bind(api, "potions", args -> registryTable(BuiltInRegistries.POTION));
		bind(api, "gameRules", args -> registryTable(BuiltInRegistries.GAME_RULE));
		bind(api, "dataComponents", args -> registryTable(BuiltInRegistries.DATA_COMPONENT_TYPE));
		return api;
	}

	private static LuaTable worldApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "setTime", args -> runInDimension(server, optionalDimension(args, 2), "time set " + safeToken(args.checkjstring(1), "时间")));
		bind(api, "addTime", args -> runInDimension(server, optionalDimension(args, 2), "time add " + args.checklong(1)));
		bind(api, "setWeather", args -> {
			String weather = enumToken(args.checkjstring(1), "天气", "clear", "rain", "thunder");
			String duration = args.arg(2).isnil() ? "" : " " + rangedInt(args.checkint(2), 1, 1000000, "天气秒数");
			return runCommand(server, "weather " + weather + duration);
		});
		bind(api, "difficulty", args -> {
			String difficulty = onServer(server, current -> current.getWorldData().getDifficulty().getSerializedName());
			return LuaValue.valueOf(difficulty);
		});
		bind(api, "setDifficulty", args -> runCommand(server, "difficulty "
				+ enumToken(args.checkjstring(1), "难度", "peaceful", "easy", "normal", "hard")));
		bind(api, "setSpawn", args -> runInDimension(server, optionalDimension(args, 4), "setworldspawn "
				+ integer(args, 1) + " " + integer(args, 2) + " " + integer(args, 3)));
		bind(api, "explode", args -> runInDimension(server, optionalDimension(args, 6), "summon minecraft:tnt "
				+ coordinate(args, 1) + " " + coordinate(args, 2) + " " + coordinate(args, 3)
				+ " {fuse:" + rangedInt(args.optint(4, 0), 0, 32767, "引信 Tick") + ",explosion_power:"
				+ Math.clamp(finite(args.optdouble(5, 4.0), "爆炸威力"), 0.0, 127.0) + "f}"));
		bind(api, "lightning", args -> runInDimension(server, optionalDimension(args, 4), "summon minecraft:lightning_bolt "
				+ coordinate(args, 1) + " " + coordinate(args, 2) + " " + coordinate(args, 3)));
		bind(api, "forceLoad", args -> runInDimension(server, optionalDimension(args, 3), "forceload add "
				+ integer(args, 1) + " " + integer(args, 2)));
		bind(api, "unforceLoad", args -> runInDimension(server, optionalDimension(args, 3), "forceload remove "
				+ integer(args, 1) + " " + integer(args, 2)));
		bind(api, "isChunkLoaded", args -> {
			int chunkX = args.checkint(1);
			int chunkZ = args.checkint(2);
			Identifier dimension = optionalDimension(args, 3);
			boolean loaded = onServer(server, current -> level(current, dimension).hasChunk(chunkX, chunkZ));
			return LuaValue.valueOf(loaded);
		});
		return api;
	}

	private static LuaTable serverApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "tickCount", args -> LuaValue.valueOf(onServer(server, MinecraftServer::getTickCount)));
		bind(api, "maxPlayers", args -> {
			int value = onServer(server, current -> current.getPlayerList().getMaxPlayers());
			return LuaValue.valueOf(value);
		});
		bind(api, "viewDistance", args -> {
			int value = onServer(server, current -> current.getPlayerList().getViewDistance());
			return LuaValue.valueOf(value);
		});
		bind(api, "simulationDistance", args -> {
			int value = onServer(server, current -> current.getPlayerList().getSimulationDistance());
			return LuaValue.valueOf(value);
		});
		bind(api, "tickRate", args -> {
			float value = onServer(server, current -> current.tickRateManager().tickrate());
			return LuaValue.valueOf(value);
		});
		bind(api, "setTickRate", args -> {
			float rate = (float) Math.clamp(positive(args.checkdouble(1), "Tick 速率"), 1.0, 10000.0);
			onServer(server, current -> { current.tickRateManager().setTickRate(rate); return true; });
			return LuaValue.TRUE;
		});
		bind(api, "frozen", args -> {
			boolean value = onServer(server, current -> current.tickRateManager().isFrozen());
			return LuaValue.valueOf(value);
		});
		bind(api, "setFrozen", args -> {
			boolean frozen = args.checkboolean(1);
			onServer(server, current -> { current.tickRateManager().setFrozen(frozen); return true; });
			return LuaValue.TRUE;
		});
		bind(api, "step", args -> {
			int ticks = rangedInt(args.optint(1, 1), 1, 1000000, "步进 Tick");
			onServer(server, current -> { current.tickRateManager().setFrozen(true); current.tickRateManager().setFrozenTicksToRun(ticks); return true; });
			return LuaValue.TRUE;
		});
		bind(api, "save", args -> {
			boolean saved = onServer(server, current -> current.saveEverything(false, true, false));
			return LuaValue.valueOf(saved);
		});
		return api;
	}

	private static LuaValue fillBlocks(MinecraftServer server, Varargs args, boolean replaceOnly) {
		int x1=args.checkint(1), y1=args.checkint(2), z1=args.checkint(3);
		int x2=args.checkint(4), y2=args.checkint(5), z2=args.checkint(6);
		Identifier fromId = replaceOnly ? id(args, 7) : null;
		Identifier toId = id(args, replaceOnly ? 8 : 7);
		Identifier dimension = optionalDimension(args, replaceOnly ? 9 : 8);
		return onServer(server, current -> {
			ServerLevel level = level(current, dimension);
			var toBlock = BuiltInRegistries.BLOCK.getOptional(toId)
					.orElseThrow(() -> new LuaError("未知方块：" + toId));
			var fromBlock = replaceOnly ? BuiltInRegistries.BLOCK.getOptional(fromId)
					.orElseThrow(() -> new LuaError("未知方块：" + fromId)) : null;
			long changed=0;
			for(long xx=Math.min(x1,x2); xx<=Math.max(x1,x2); xx++)
			for(long yy=Math.min(y1,y2); yy<=Math.max(y1,y2); yy++)
			for(long zz=Math.min(z1,z2); zz<=Math.max(z1,z2); zz++) {
				BlockPos pos=new BlockPos((int)xx,(int)yy,(int)zz);
				if(!level.isInWorldBounds(pos)) throw new LuaError("填充区域超出世界边界：" + pos.toShortString());
				if(replaceOnly && level.getBlockState(pos).getBlock()!=fromBlock) continue;
				if(level.setBlockAndUpdate(pos,toBlock.defaultBlockState())) changed++;
			}
			return LuaValue.valueOf(changed>0);
		});
	}

	private static LuaValue cloneBlocks(MinecraftServer server, Varargs args) {
		int x1=args.checkint(1), y1=args.checkint(2), z1=args.checkint(3);
		int x2=args.checkint(4), y2=args.checkint(5), z2=args.checkint(6);
		int toX=args.checkint(7), toY=args.checkint(8), toZ=args.checkint(9);
		Identifier dimension=optionalDimension(args,10);
		return onServer(server,current -> {
			ServerLevel level=level(current,dimension);
			int minX=Math.min(x1,x2), minY=Math.min(y1,y2), minZ=Math.min(z1,z2);
			int maxX=Math.max(x1,x2), maxY=Math.max(y1,y2), maxZ=Math.max(z1,z2);
			long volume=((long)maxX-minX+1)*((long)maxY-minY+1)*((long)maxZ-minZ+1);
			List<CloneCell> cells=new ArrayList<>((int)Math.min(volume,65536L));
			for(long xx=minX;xx<=maxX;xx++) for(long yy=minY;yy<=maxY;yy++) for(long zz=minZ;zz<=maxZ;zz++) {
				BlockPos source=new BlockPos((int)xx,(int)yy,(int)zz);
				long tx=(long)toX+(xx-minX), ty=(long)toY+(yy-minY), tz=(long)toZ+(zz-minZ);
				if(tx<Integer.MIN_VALUE||tx>Integer.MAX_VALUE||ty<Integer.MIN_VALUE||ty>Integer.MAX_VALUE||tz<Integer.MIN_VALUE||tz>Integer.MAX_VALUE)
					throw new LuaError("复制目标坐标超出整数范围");
				BlockPos target=new BlockPos((int)tx,(int)ty,(int)tz);
				if(!level.isInWorldBounds(source)||!level.isInWorldBounds(target))
					throw new LuaError("复制区域超出世界边界");
				BlockState state=level.getBlockState(source);
				BlockEntity blockEntity=level.getBlockEntity(source);
				CompoundTag tag=blockEntity==null?null:blockEntity.saveWithFullMetadata(current.registryAccess());
				cells.add(new CloneCell(target,state,tag));
			}
			long changed=0;
			for(CloneCell cell:cells) {
				if(level.setBlockAndUpdate(cell.target(),cell.state())) changed++;
				if(cell.blockEntityTag()!=null) {
					CompoundTag tag=cell.blockEntityTag();
					tag.putInt("x",cell.target().getX()); tag.putInt("y",cell.target().getY()); tag.putInt("z",cell.target().getZ());
					BlockEntity copy=BlockEntity.loadStatic(cell.target(),cell.state(),tag,current.registryAccess());
					if(copy!=null) { level.setBlockEntity(copy); copy.setChanged(); }
				}
			}
			return LuaValue.valueOf(changed>0);
		});
	}

	private record CloneCell(BlockPos target, BlockState state, CompoundTag blockEntityTag) {}

	private static void applyEntityComponents(MinecraftServer server, Entity entity, LuaValue components) {
		if(components==null || components.isnil()) return;
		DataComponentPatch patch=LuaItemStackCodec.decodePatch(server.registryAccess(),components);
		for(var entry:patch.entrySet()) {
			if(entry.getValue().isPresent()) setEntityComponent(entity,entry.getKey(),entry.getValue().get());
		}
	}

	private static LuaValue mutateEntity(MinecraftServer server, Varargs args, java.util.function.Consumer<Entity> mutation) {
		UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
		onServer(server, current -> { mutation.accept(requireEntity(current, uuid)); return true; });
		return LuaValue.TRUE;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void setEntityComponent(Entity entity, DataComponentType type, Object value) {
		entity.setComponent(type, value);
	}

	private static LuaValue tagEntity(MinecraftServer server, Varargs args, boolean add) {
		UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
		String tag = scoreName(args, 2);
		boolean changed = onServer(server, current -> add
				? requireEntity(current, uuid).addTag(tag) : requireEntity(current, uuid).removeTag(tag));
		return LuaValue.valueOf(changed);
	}

	private static LuaValue title(MinecraftServer server, Varargs args, String kind) {
		return runCommand(server, "title " + playerTarget(server, args, 1) + " " + kind + " "
				+ LuaTextComponentCodec.commandJson(server, args.arg(2)));
	}

	private static LuaValue teamModify(MinecraftServer server, Varargs args, String option, String value) {
		return runCommand(server, "team modify " + scoreName(args, 1) + " " + option + " " + value);
	}

	private static LuaValue runInDimension(MinecraftServer server, Identifier dimension, String command) {
		return runCommand(server, "execute in " + dimension + " run " + command);
	}

	private static LuaValue runCommand(MinecraftServer server, String command) {
		onServer(server, current -> {
			current.getCommands().performPrefixedCommand(current.createCommandSourceStack(), command);
			return true;
		});
		return LuaValue.TRUE;
	}

	private static LuaTable entityList(ServerLevel level, java.util.function.Predicate<Entity> filter) {
		LuaTable result = new LuaTable();
		int index = 1;
		for (Entity entity : level.getAllEntities()) {
			if (filter == null || filter.test(entity)) result.set(index++, entityTable(entity));
			if (index > 10000) break;
		}
		return result;
	}

	private static LuaTable entityTable(Entity entity) {
		LuaTable result = new LuaTable();
		result.set("uuid", entity.getStringUUID());
		result.set("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		result.set("name", entity.getName().getString());
		result.set("runtimeId", entity.getId());
		result.set("x", entity.getX());
		result.set("y", entity.getY());
		result.set("z", entity.getZ());
		result.set("yaw", entity.getYRot());
		result.set("pitch", entity.getXRot());
		result.set("dimension", entity.level().dimension().identifier().toString());
		result.set("living", LuaValue.valueOf(entity instanceof LivingEntity));
		result.set("glowing", LuaValue.valueOf(entity.isCurrentlyGlowing()));
		result.set("invulnerable", LuaValue.valueOf(entity.isInvulnerable()));
		result.set("gravity", LuaValue.valueOf(!entity.isNoGravity()));
		if (entity instanceof LivingEntity living) {
			result.set("health", living.getHealth());
			result.set("maxHealth", living.getMaxHealth());
		}
		return result;
	}

	private static Entity findEntity(MinecraftServer server, UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntityInAnyDimension(uuid);
			if (entity != null) return entity;
		}
		return null;
	}

	private static Entity requireEntity(MinecraftServer server, UUID uuid) {
		Entity entity = findEntity(server, uuid);
		if (entity == null) throw new LuaError("实体不存在或尚未加载：" + uuid);
		return entity;
	}

	private static Registry<?> registry(String kind) {
		return switch (kind.toLowerCase(Locale.ROOT)) {
			case "blocks", "block" -> BuiltInRegistries.BLOCK;
			case "items", "item" -> BuiltInRegistries.ITEM;
			case "entities", "entity" -> BuiltInRegistries.ENTITY_TYPE;
			case "effects", "effect" -> BuiltInRegistries.MOB_EFFECT;
			case "sounds", "sound" -> BuiltInRegistries.SOUND_EVENT;
			case "particles", "particle" -> BuiltInRegistries.PARTICLE_TYPE;
			case "potions", "potion" -> BuiltInRegistries.POTION;
			case "gamerules", "gamerule" -> BuiltInRegistries.GAME_RULE;
			case "fluids", "fluid" -> BuiltInRegistries.FLUID;
			case "blockentities", "blockentity" -> BuiltInRegistries.BLOCK_ENTITY_TYPE;
			case "attributes", "attribute" -> BuiltInRegistries.ATTRIBUTE;
			case "datacomponents", "datacomponent", "components", "component" -> BuiltInRegistries.DATA_COMPONENT_TYPE;
			default -> throw new LuaError("未知注册表：" + kind + "；可使用 MC.Registry.kinds() 查看类型");
		};
	}

	private static LuaTable registryTable(Registry<?> registry) {
		return stringTable(registry.keySet().stream().map(Identifier::toString).sorted().toList());
	}

	private static LuaTable stringTable(List<String> values) {
		LuaTable result = new LuaTable();
		for (int index = 0; index < values.size(); index++) result.set(index + 1, values.get(index));
		return result;
	}

	private static String blockBox(Varargs args, int start) {
		return integer(args, start) + " " + integer(args, start + 1) + " " + integer(args, start + 2) + " "
				+ integer(args, start + 3) + " " + integer(args, start + 4) + " " + integer(args, start + 5);
	}

	private static int integer(Varargs args, int index) {
		return args.checkint(index);
	}

	private static double coordinate(Varargs args, int index) {
		return finite(args.checkdouble(index), "坐标");
	}

	private static Identifier optionalDimension(Varargs args, int index) {
		return args.arg(index).isnil() ? DEFAULT_DIMENSION : id(args, index);
	}

	private static Identifier id(Varargs args, int index) {
		String raw = args.checkjstring(index);
		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (id == null) throw new LuaError("无效资源 ID：" + raw);
		return id;
	}

	private static UUID playerUuid(Varargs args, int index) {
		return uuid(args.checkjstring(index), "玩家 UUID");
	}

	private static String playerTarget(MinecraftServer server, Varargs args, int index) {
		UUID uuid = playerUuid(args, index);
		return onServer(server, current -> player(current, uuid).getScoreboardName());
	}

	private static String entityUuid(Varargs args, int index) {
		return uuid(args.checkjstring(index), "实体 UUID").toString();
	}

	private static UUID uuid(String raw, String label) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new LuaError("无效" + label + "：" + raw);
		}
	}

	private static ServerPlayer player(MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player == null) throw new LuaError("玩家不在线或 UUID 不存在：" + uuid);
		return player;
	}

	private static ServerLevel level(MinecraftServer server, Identifier dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().equals(dimension)) return level;
		}
		throw new LuaError("维度不存在或未加载：" + dimension);
	}

	private static String cleanText(String text) {
		return text.replace('\r', ' ').replace('\n', ' ').strip();
	}

	private static String safeToken(String value, String label) {
		String token = value.strip();
		if (!SAFE_TOKEN.matcher(token).matches()) throw new LuaError(label + "包含不允许的字符：" + value);
		return token;
	}

	private static String scoreName(Varargs args, int index) {
		String name = args.checkjstring(index).strip();
		if (!SCORE_NAME.matcher(name).matches()) throw new LuaError("名称只能包含字母、数字、点、下划线、加号和减号");
		return name;
	}

	private static String scoreHolder(String value) {
		String holder = value.strip();
		try {
			return UUID.fromString(holder).toString();
		} catch (IllegalArgumentException ignored) {
			return scoreName(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(holder)}), 1);
		}
	}

	private static String enumToken(String value, String label, String... allowed) {
		String normalized = value.toLowerCase(Locale.ROOT);
		for (String option : allowed) if (option.equals(normalized)) return normalized;
		throw new LuaError(label + "必须是：" + String.join(", ", allowed));
	}

	private static int rangedInt(int value, int minimum, int maximum, String label) {
		if (value < minimum || value > maximum) throw new LuaError(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
		return value;
	}

	private static double finite(double value, String label) {
		if (!Double.isFinite(value)) throw new LuaError(label + "必须是有限数字");
		return value;
	}

	private static double positive(double value, String label) {
		value = finite(value, label);
		if (value < 0) throw new LuaError(label + "不能为负数");
		return value;
	}

	private static void extend(LuaTable mc, String name, LuaTable additions) {
		LuaTable target = mc.get(name).checktable();
		for (LuaValue key = LuaValue.NIL; ; ) {
			Varargs next = additions.next(key);
			key = next.arg1();
			if (key.isnil()) break;
			target.set(key, next.arg(2));
		}
	}

	private static void setNamespace(LuaTable mc, String name, LuaTable namespace) {
		mc.set(name, namespace);
		mc.set(name.toLowerCase(Locale.ROOT), namespace);
	}

	private static void bind(LuaTable table, String name, LuaCallable function) {
		table.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				try {
					return function.invoke(args);
				} catch (LuaError error) {
					throw error;
				} catch (Throwable error) {
					throw new LuaError(message(error));
				}
			}
		});
	}

	private static <T> T onServer(MinecraftServer server, Function<MinecraftServer, T> action) {
		if (server.isSameThread()) return action.apply(server);
		return await(server.submit(() -> action.apply(server)), "等待 Minecraft 服务端响应超时");
	}

	private static <T> T await(CompletableFuture<T> future, String timeoutMessage) {
		try {
			return future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new LuaError("脚本已停止");
		} catch (TimeoutException error) {
			throw new LuaError(timeoutMessage);
		} catch (ExecutionException error) {
			throw new LuaError(message(error.getCause()));
		}
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	@FunctionalInterface
	private interface LuaCallable {
		Varargs invoke(Varargs args);
	}
}
