package wiki.ica.xiaomiao.mcluaide.server;

import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Curated Minecraft/Fabric API backed exclusively by the authoritative server. */
final class MinecraftLuaApi {
	private static final String API_VERSION = "1";
	private static final int API_TIMEOUT_SECONDS = 10;
	private static final Identifier DEFAULT_DIMENSION = Identifier.withDefaultNamespace("overworld");

	private MinecraftLuaApi() {
	}

	static void install(Globals globals, MinecraftServer server,
			ServerScriptManager.ScriptExecution execution, LuaLimits limits) {
		LuaTable mc = new LuaTable();
		setNamespace(mc, "Block", blockApi(server));
		setNamespace(mc, "Item", itemApi(server));
		setNamespace(mc, "Player", playerApi(server));
		setNamespace(mc, "World", worldApi(server));
		setNamespace(mc, "Chat", chatApi(server));
		setNamespace(mc, "Command", commandApi(server));
		setNamespace(mc, "Mod", modApi());
		setNamespace(mc, "Game", gameApi());
		setNamespace(mc, "Server", serverApi(server));
		setNamespace(mc, "Event", eventApi(execution));
		MinecraftLuaUiApi.install(mc, server, execution);
		MinecraftLuaClientApi.install(mc, server, execution);
		MinecraftLuaControlApi.install(mc, server, execution);
		MinecraftLuaInteractionApi.install(mc, server, execution);
		LuaLocalizedNames.install(mc, server);
		LuaManagedPackApi.install(mc, server);
		applyLimits(mc, limits);
		MinecraftLuaFileApi.install(mc, execution);
		MinecraftLuaNetworkApi.install(mc, limits);
		setNamespace(mc, "Limits", limitsApi(limits));
		bind(mc, "sleep", args -> {
			execution.sleepMilliseconds(args.checkint(1));
			return LuaValue.TRUE;
		});
		bind(mc, "apiVersion", args -> LuaValue.valueOf(API_VERSION));
		globals.set("MC", mc);
		McLuaIdeApi.installInto(globals, mc, server, execution.name());
		LuaRuntimeWarnings.install(mc, globals, execution.warnings(), execution::checkRuntime);
	}

	private static void applyLimits(LuaTable mc, LuaLimits limits) {
		if (!limits.allowEvents()) removeNamespaces(mc, "Event");
		if (!limits.allowCommandApi()) removeNamespaces(mc, "Command");
		if (!limits.allowWorldApi()) removeNamespaces(mc,
				"Block", "World", "GameRule", "Border", "Structure", "Loot");
		if (!limits.allowPlayerApi()) removeNamespaces(mc,
				"Player", "Effect", "Attribute");
		if (!limits.allowEntityApi()) removeNamespaces(mc, "Entity");
		if (!limits.allowInventoryApi()) {
			removeNamespaces(mc, "Item", "Container");
			removeMethods(mc, "Player", "openChest", "menuItem", "setMenuItem", "carried", "setCarried", "openBlock", "openCrafting", "openEnderChest", "openAnvil",
					"openEnchanting", "openGrindstone", "openLoom", "openCartography",
					"openStonecutter", "openSmithing", "closeMenu", "menu");
			removeMethods(mc, "Entity", "equipment", "setEquipment", "components");
		}
		if (!limits.allowPresentationApi()) removeNamespaces(mc,
				"Chat", "Sound", "Particle", "Title", "UI", "Client");
		if (!limits.allowServerApi()) removeNamespaces(mc,
				"Server", "Game", "Mod", "Score", "Team", "Advancement", "Recipe", "Pack");
		if (!limits.allowRegistryApi()) removeNamespaces(mc, "Registry", "Enchantment");
	}

	private static LuaTable limitsApi(LuaLimits limits) {
		LuaTable api = new LuaTable();
		bind(api, "current", args -> {
			LuaTable result = new LuaTable();
			limits.toMap().forEach((key, value) -> {
				if (value.equals("true") || value.equals("false")) {
					result.set(key, LuaValue.valueOf(Boolean.parseBoolean(value)));
				} else {
					result.set(key, LuaValue.valueOf(Integer.parseInt(value)));
				}
			});
			return result;
		});
		return api;
	}

	private static void removeNamespaces(LuaTable mc, String... names) {
		for (String name : names) {
			mc.set(name, LuaValue.NIL);
			mc.set(name.toLowerCase(Locale.ROOT), LuaValue.NIL);
		}
	}

	private static void removeMethods(LuaTable mc, String namespace, String... methods) {
		LuaValue value = mc.get(namespace);
		if (!value.istable()) return;
		LuaTable table = value.checktable();
		for (String method : methods) table.set(method, LuaValue.NIL);
	}

	private static LuaTable eventApi(ServerScriptManager.ScriptExecution execution) {
		LuaTable api = new LuaTable();
		bind(api, "on", args -> {
			String eventName = args.checkjstring(1);
			return LuaValue.valueOf(eventName.equals("interval")
					? execution.registerInterval(args.optdouble(3, 1000), args.checkfunction(2))
					: execution.registerEvent(eventName, args.checkfunction(2), args.optint(3, 1)));
		});
		bind(api, "onInterval", args -> LuaValue.valueOf(execution.registerInterval(
				args.checkdouble(1), args.checkfunction(2))));
		bind(api, "names", args -> {
			LuaTable names = new LuaTable();
			for (int index = 0; index < ServerScriptManager.EVENT_NAMES.size(); index++) {
				names.set(index + 1, ServerScriptManager.EVENT_NAMES.get(index));
			}
			return names;
		});
		Map<String, String> methods = new LinkedHashMap<>();
		methods.put("onScriptStarted", "scriptStarted");
		methods.put("onScriptStopped", "scriptStopped");
		methods.put("onBlockPlaced", "blockPlaced");
		methods.put("onBlockBroken", "blockBroken");
		methods.put("onBlockAttacked", "blockAttacked");
		methods.put("onBlockUsed", "blockUsed");
		methods.put("onItemUsed", "itemUsed");
		methods.put("onContainerClicked", "containerClicked");
		methods.put("onContainerItemTaken", "containerItemTaken");
		methods.put("onContainerItemPlaced", "containerItemPlaced");
		methods.put("onContainerItemToCursor", "containerItemToCursor");
		methods.put("onInventoryItemToCursor", "inventoryItemToCursor");
		methods.put("onEntityAttacked", "entityAttacked");
		methods.put("onEntityUsed", "entityUsed");
		methods.put("onEntityLoaded", "entityLoaded");
		methods.put("onEntityUnloaded", "entityUnloaded");
		methods.put("onEntityChangedDimension", "entityChangedDimension");
		methods.put("onEntityDamaged", "entityDamaged");
		methods.put("onEntityDied", "entityDied");
		methods.put("onEntityKilledOther", "entityKilledOther");
		methods.put("onEquipmentChanged", "equipmentChanged");
		methods.put("onPlayerJoined", "playerJoined");
		methods.put("onPlayerLeft", "playerLeft");
		methods.put("onPlayerDied", "playerDied");
		methods.put("onPlayerRespawned", "playerRespawned");
		methods.put("onPlayerChangedDimension", "playerChangedDimension");
		methods.put("onEffectAdded", "effectAdded");
		methods.put("onEffectRemoved", "effectRemoved");
		methods.put("onSleepStarted", "sleepStarted");
		methods.put("onSleepStopped", "sleepStopped");
		methods.put("onChunkLoaded", "chunkLoaded");
		methods.put("onChunkGenerated", "chunkGenerated");
		methods.put("onChunkUnloaded", "chunkUnloaded");
		methods.put("onLevelLoaded", "levelLoaded");
		methods.put("onLevelUnloaded", "levelUnloaded");
		methods.put("onServerTick", "serverTick");
		methods.put("onLevelTick", "levelTick");
		methods.put("onChat", "chat");
		methods.put("onCommand", "command");
		methods.put("onUiAction", "uiAction");
		for (Map.Entry<String, String> method : methods.entrySet()) {
			boolean tickEvent = method.getValue().equals("serverTick") || method.getValue().equals("levelTick");
			bind(api, method.getKey(), args -> LuaValue.valueOf(execution.registerEvent(
					method.getValue(), args.checkfunction(1), tickEvent ? args.optint(2, 1) : 1)));
		}
		bind(api, "off", args -> LuaValue.valueOf(execution.unregisterEvent(args.checkint(1))));
		bind(api, "clear", args -> LuaValue.valueOf(execution.clearEvents()));
		return api;
	}

	private static LuaTable blockApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "get", args -> {
			BlockPos pos = blockPos(args, 1);
			Identifier dimension = optionalDimension(args.arg(4));
			return onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				ensureUsablePosition(level, pos);
				BlockState state = level.getBlockState(pos);
				Block block = state.getBlock();
				LuaTable result = new LuaTable();
				result.set("id", BuiltInRegistries.BLOCK.getKey(block).toString());
				result.set("name", Component.translatable(block.getDescriptionId()).getString());
				result.set("air", LuaValue.valueOf(state.isAir()));
				result.set("x", pos.getX());
				result.set("y", pos.getY());
				result.set("z", pos.getZ());
				result.set("dimension", dimension.toString());
				LuaTable states = new LuaTable();
				for (Property<?> property : state.getProperties()) states.set(property.getName(), stateValue(state, property));
				result.set("states", states);
				ItemStack blockStack = block.asItem().getDefaultInstance();
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (!blockStack.isEmpty() && blockEntity != null) {
					blockStack.applyComponents(blockEntity.collectComponents());
				}
				LuaTable itemData = LuaItemStackCodec.toLua(current, blockStack);
				result.set("itemComponents", itemData.get("components"));
				result.set("itemComponentsJson", itemData.get("componentsJson"));
				LuaComponentSnapshots.blockEntity(current, blockEntity, result);
				return result;
			});
		});
		bind(api, "id", args -> {
			BlockPos pos = blockPos(args, 1);
			Identifier dimension = optionalDimension(args.arg(4));
			String id = onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				ensureUsablePosition(level, pos);
				return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
			});
			return LuaValue.valueOf(id);
		});
		bind(api, "isAir", args -> {
			BlockPos pos = blockPos(args, 1);
			Identifier dimension = optionalDimension(args.arg(4));
			boolean air = onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				ensureUsablePosition(level, pos);
				return level.getBlockState(pos).isAir();
			});
			return LuaValue.valueOf(air);
		});
		bind(api, "set", args -> {
			BlockPos pos = blockPos(args, 1);
			Identifier id = identifier(args.checkjstring(4));
			Identifier dimension = optionalDimension(args.arg(5));
			boolean changed = onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				Block block = BuiltInRegistries.BLOCK.getOptional(id)
						.orElseThrow(() -> new IllegalArgumentException("未知方块：" + id));
				ensureUsablePosition(level, pos);
				LuaValue config = args.arg(6);
				if (config.isstring()) config = LuaItemStackCodec.jsonObjectToLua(config.checkjstring());
				boolean blockConfig = config.istable() && (config.checktable().keys().length == 0 || !config.get("states").isnil()
						|| !config.get("blockEntityData").isnil() || !config.get("itemComponents").isnil());
				BlockState state = block.defaultBlockState();
				if (blockConfig) {
				LuaComponentEditorCatalog.validate(current, "block\n" + id,
						LuaItemStackCodec.objectToJson(config).toString());
					LuaValue states = config.get("states");
					if (!states.isnil()) {
						if (!states.istable()) throw new LuaError("方块 states 必须是属性表");
						for (LuaValue key : states.checktable().keys()) {
							String propertyName = key.checkjstring();
							Property<?> property = block.getStateDefinition().getProperty(propertyName);
							if (property == null) throw new LuaError("该方块没有状态属性：" + propertyName);
							state = withStateValue(state, property, states.get(key).checkjstring());
						}
					}
					for (LuaValue key : config.checktable().keys()) {
						String field = key.checkjstring();
						if (!field.equals("states") && !field.equals("blockEntityData") && !field.equals("itemComponents"))
							throw new LuaError("未知方块配置字段：" + field);
					}
				}
				ItemStack blockStack = ItemStack.EMPTY;
				LuaValue itemComponents = blockConfig ? config.get("itemComponents") : config;
				if (!itemComponents.isnil()) {
					if (!state.hasBlockEntity()) {
						throw new IllegalArgumentException("该方块没有可接收物品堆组件的方块实体：" + id);
					}
					Identifier itemId = BuiltInRegistries.ITEM.getKey(block.asItem());
					if (itemId == null || block.asItem().getDefaultInstance().isEmpty()) {
						throw new IllegalArgumentException("该方块没有可承载数据组件的物品形式：" + id);
					}
					blockStack = LuaItemStackCodec.create(current, LuaValue.valueOf(itemId.toString()),
							LuaValue.valueOf(1), itemComponents, 1);
				}
				CompoundTag blockEntityData = null;
				if (blockConfig && !config.get("blockEntityData").isnil()) {
					if (!state.hasBlockEntity()) throw new LuaError("该方块没有方块实体数据：" + id);
					var json = LuaItemStackCodec.objectToJson(config.get("blockEntityData"));
					var tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
					if (!(tag instanceof CompoundTag compound)) throw new LuaError("方块实体数据必须是对象");
					blockEntityData = compound;
					if (block instanceof EntityBlock entityBlock) {
						BlockEntity probe = entityBlock.newBlockEntity(pos, state);
						if (probe != null) probe.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
								current.registryAccess(), blockEntityData));
					}
				}
				boolean placed = level.setBlockAndUpdate(pos, state);
				if (placed && !blockStack.isEmpty()) {
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (blockEntity == null) {
						throw new IllegalArgumentException("该方块没有可接收物品堆组件的方块实体：" + id);
					}
					blockEntity.applyComponentsFromItemStack(blockStack);
					blockEntity.setChanged();
					level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
				}
				if (placed && blockEntityData != null) {
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (blockEntity == null) throw new LuaError("方块实体未能创建：" + id);
					blockEntity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
							current.registryAccess(), blockEntityData));
					blockEntity.setChanged();
					level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
				}
				return placed;
			});
			return LuaValue.valueOf(changed);
		});
		bind(api, "breakAt", args -> {
			BlockPos pos = blockPos(args, 1);
			boolean drops = args.optboolean(4, true);
			Identifier dimension = optionalDimension(args.arg(5));
			boolean changed = onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				ensureUsablePosition(level, pos);
				return level.destroyBlock(pos, drops, null, 512);
			});
			return LuaValue.valueOf(changed);
		});
		return api;
	}

	private static <T extends Comparable<T>> String stateValue(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static <T extends Comparable<T>> BlockState withStateValue(BlockState state,
			Property<T> property, String value) {
		T parsed = property.getValue(value)
				.orElseThrow(() -> new LuaError("方块状态 " + property.getName() + " 不支持值：" + value));
		return state.setValue(property, parsed);
	}

	private static LuaTable itemApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "isOnCooldown", args -> itemCooldown(server, args, LuaItemCooldowns.Action.QUERY));
		bind(api, "startCooldown", args -> itemCooldown(server, args, LuaItemCooldowns.Action.START));
		bind(api, "setCooldown", args -> itemCooldown(server, args, LuaItemCooldowns.Action.SET));
		bind(api, "clearCooldown", args -> itemCooldown(server, args, LuaItemCooldowns.Action.CLEAR));
		bind(api, "held", args -> {
			UUID uuid = playerUuid(args, 1);
			return onServer(server, current -> LuaItemStackCodec.toLua(
					current, player(current, uuid).getMainHandItem()));
		});
		bind(api, "count", args -> {
			UUID uuid = playerUuid(args, 1);
			int count = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				var filter = LuaItemStackCodec.filter(current, args.arg(2), args.arg(3));
				int total = 0;
				for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
					ItemStack stack = player.getInventory().getItem(slot);
					if (filter.matches(stack)) total += stack.getCount();
				}
				return total;
			});
			return LuaValue.valueOf(count);
		});
		bind(api, "give", args -> {
			UUID uuid = playerUuid(args, 1);
			int delivered = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				ItemStack stack = LuaItemStackCodec.create(
						current, args.arg(2), args.arg(3), args.arg(4), 999);
				int count = stack.getCount();
				player.getInventory().add(stack);
				int inserted = count - stack.getCount();
				if (!stack.isEmpty()) {
					player.drop(stack, false);
					inserted = count;
				}
				return inserted;
			});
			return LuaValue.valueOf(delivered);
		});
		bind(api, "validate", args -> onServer(server, current -> {
			ItemStack stack = LuaItemStackCodec.create(
					current, args.arg(1), args.arg(2), args.arg(3), 999);
			return LuaItemStackCodec.toLua(current, stack);
		}));
		return api;
	}

	private static LuaValue itemCooldown(MinecraftServer server, Varargs args, LuaItemCooldowns.Action action) {
		UUID uuid = playerUuid(args, 1);
		boolean timed = action == LuaItemCooldowns.Action.START || action == LuaItemCooldowns.Action.SET;
		Integer ticks = timed ? LuaItemCooldowns.duration(args.arg(3), action == LuaItemCooldowns.Action.START) : null;
		return onServer(server, current -> {
			ServerPlayer player = player(current, uuid);
			var filter = LuaItemStackCodec.filter(current, args.arg(2), args.arg(timed ? 4 : 3));
			if (action == LuaItemCooldowns.Action.QUERY)
				return LuaValue.valueOf(LuaItemCooldowns.isOnCooldown(player.getInventory(), player.getCooldowns(), filter));
			return LuaValue.valueOf(LuaItemCooldowns.change(player.getInventory(), player.getCooldowns(),
					filter, ticks, action == LuaItemCooldowns.Action.CLEAR));
		});
	}

	private static LuaTable playerApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "list", args -> onServer(server, current -> {
			LuaTable result = new LuaTable();
			int index = 1;
			for (ServerPlayer player : current.getPlayerList().getPlayers()) {
				LuaTable entry = new LuaTable();
				entry.set("uuid", player.getUUID().toString());
				entry.set("name", player.getName().getString());
				entry.set("dimension", player.level().dimension().identifier().toString());
				entry.set("x", player.getX());
				entry.set("y", player.getY());
				entry.set("z", player.getZ());
				result.set(index++, entry);
			}
			return result;
		}));
		bind(api, "uuid", args -> {
			String name = args.checkjstring(1);
			String uuid = onServer(server, current -> {
				ServerPlayer found = current.getPlayerList().getPlayerByName(name);
				return found == null ? null : found.getUUID().toString();
			});
			return uuid == null ? LuaValue.NIL : LuaValue.valueOf(uuid);
		});
		bind(api, "name", args -> {
			UUID uuid = playerUuid(args, 1);
			String name = onServer(server, current -> player(current, uuid).getName().getString());
			return LuaValue.valueOf(name);
		});
		bind(api, "position", args -> {
			UUID uuid = playerUuid(args, 1);
			PlayerSnapshot snapshot = onServer(server, current -> snapshot(player(current, uuid)));
			LuaTable result = new LuaTable();
			result.set("x", snapshot.x());
			result.set("y", snapshot.y());
			result.set("z", snapshot.z());
			result.set("yaw", snapshot.yaw());
			result.set("pitch", snapshot.pitch());
			result.set("dimension", snapshot.dimension());
			return result;
		});
		bind(api, "facing", args -> {
			UUID uuid = playerUuid(args, 1);
			PlayerSnapshot snapshot = onServer(server, current -> snapshot(player(current, uuid)));
			var facing = LuaRelativePosition.facing(snapshot.yaw(), snapshot.pitch());
			LuaTable result = new LuaTable();
			result.set("yaw", facing.yaw());
			result.set("pitch", facing.pitch());
			result.set("direction", facing.direction());
			return result;
		});
		bind(api, "relativePosition", args -> {
			UUID uuid = playerUuid(args, 1);
			String direction = args.checkjstring(2), plane = args.checkjstring(3);
			double distance = args.checkdouble(4);
			PlayerSnapshot snapshot = onServer(server, current -> snapshot(player(current, uuid)));
			LuaRelativePosition.Point point;
			try { point = LuaRelativePosition.offset(snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch(), direction, plane, distance); }
			catch (IllegalArgumentException invalid) { throw new LuaError(invalid.getMessage()); }
			LuaTable result = new LuaTable();
			result.set("x", point.x());
			result.set("y", point.y());
			result.set("z", point.z());
			result.set("blockX", (int) Math.floor(point.x()));
			result.set("blockY", (int) Math.floor(point.y()));
			result.set("blockZ", (int) Math.floor(point.z()));
			result.set("dimension", snapshot.dimension());
			return result;
		});
		bind(api, "health", args -> {
			UUID uuid = playerUuid(args, 1);
			PlayerSnapshot snapshot = onServer(server, current -> snapshot(player(current, uuid)));
			LuaTable result = new LuaTable();
			result.set("current", snapshot.health());
			result.set("max", snapshot.maxHealth());
			return result;
		});
		bind(api, "gameMode", args -> {
			UUID uuid = playerUuid(args, 1);
			String mode = onServer(server, current -> player(current, uuid).gameMode().getName());
			return LuaValue.valueOf(mode);
		});
		bind(api, "teleport", args -> {
			UUID uuid = playerUuid(args, 1);
			double x = finite(args.checkdouble(2), "x");
			double y = finite(args.checkdouble(3), "y");
			double z = finite(args.checkdouble(4), "z");
			Identifier dimension = args.arg(5).isnil() ? null : identifier(args.checkjstring(5));
			boolean changed = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				ServerLevel target = dimension == null ? player.level() : level(current, dimension);
				BlockPos targetPos = BlockPos.containing(x, y, z);
				if (!target.isInWorldBounds(targetPos)) throw new IllegalArgumentException("传送目标超出世界边界");
				return player.teleportTo(target, x, y, z, Set.of(), player.getYRot(), player.getXRot(), true);
			});
			return LuaValue.valueOf(changed);
		});
		bind(api, "setHealth", args -> {
			UUID uuid = playerUuid(args, 1);
			double requested = finite(args.checkdouble(2), "生命值");
			double applied = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				float value = (float) Math.clamp(requested, 0.0, player.getMaxHealth());
				player.setHealth(value);
				return (double) value;
			});
			return LuaValue.valueOf(applied);
		});
		bind(api, "setGameMode", args -> {
			UUID uuid = playerUuid(args, 1);
			String name = args.checkjstring(2).toLowerCase(Locale.ROOT);
			GameType mode = switch (name) {
				case "survival" -> GameType.SURVIVAL;
				case "creative" -> GameType.CREATIVE;
				case "adventure" -> GameType.ADVENTURE;
				case "spectator" -> GameType.SPECTATOR;
				default -> throw new LuaError("未知游戏模式：" + name);
			};
			boolean changed = onServer(server, current -> player(current, uuid).setGameMode(mode));
			return LuaValue.valueOf(changed);
		});
		return api;
	}

	private static LuaTable worldApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "dimensions", args -> onServer(server, current -> {
			LuaTable result = new LuaTable();
			int index = 1;
			for (ServerLevel level : current.getAllLevels()) {
				result.set(index++, level.dimension().identifier().toString());
			}
			return result;
		}));
		bind(api, "gameTime", args -> {
			Identifier dimension = optionalDimension(args.arg(1));
			long time = onServer(server, current -> level(current, dimension).getLevelData().getGameTime());
			return LuaValue.valueOf(time);
		});
		bind(api, "clockTime", args -> {
			Identifier dimension = optionalDimension(args.arg(1));
			long time = onServer(server, current -> level(current, dimension).getOverworldClockTime());
			return LuaValue.valueOf(time);
		});
		bind(api, "weather", args -> {
			Identifier dimension = optionalDimension(args.arg(1));
			WeatherSnapshot weather = onServer(server, current -> {
				ServerLevel level = level(current, dimension);
				return new WeatherSnapshot(level.isRaining(), level.isThundering());
			});
			LuaTable result = new LuaTable();
			result.set("raining", LuaValue.valueOf(weather.raining()));
			result.set("thundering", LuaValue.valueOf(weather.thundering()));
			result.set("name", weather.thundering() ? "thunder" : weather.raining() ? "rain" : "clear");
			result.set("dimension", dimension.toString());
			return result;
		});
		bind(api, "seed", args -> {
			Identifier dimension = optionalDimension(args.arg(1));
			long seed = onServer(server, current -> level(current, dimension).getSeed());
			return LuaValue.valueOf(Long.toString(seed));
		});
		return api;
	}

	private static LuaTable chatApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "message", args -> {
			UUID uuid = playerUuid(args, 1);
			LuaValue text = args.arg(2);
			onServer(server, current -> {
				player(current, uuid).sendSystemMessage(LuaTextComponentCodec.decode(current, text));
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "broadcast", args -> {
			LuaValue text = args.arg(1);
			onServer(server, current -> {
				current.getPlayerList().broadcastSystemMessage(LuaTextComponentCodec.decode(current, text), false);
				return true;
			});
			return LuaValue.TRUE;
		});
		return api;
	}

	private static LuaTable commandApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "run", args -> {
			String command = normalizedCommand(args.checkjstring(1));
			onServer(server, current -> {
				current.getCommands().performPrefixedCommand(current.createCommandSourceStack(), command);
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "runAs", args -> {
			UUID uuid = playerUuid(args, 1);
			String command = normalizedCommand(args.checkjstring(2));
			onServer(server, current -> {
				current.getCommands().performPrefixedCommand(player(current, uuid).createCommandSourceStack(), command);
				return true;
			});
			return LuaValue.TRUE;
		});
		return api;
	}

	private static LuaTable modApi() {
		LuaTable api = new LuaTable();
		bind(api, "isLoaded", args -> LuaValue.valueOf(FabricLoader.getInstance().isModLoaded(args.checkjstring(1))));
		bind(api, "version", args -> {
			String id = args.checkjstring(1);
			String version = FabricLoader.getInstance().getModContainer(id)
					.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
			return version == null ? LuaValue.NIL : LuaValue.valueOf(version);
		});
		bind(api, "list", args -> {
			LuaTable result = new LuaTable();
			int index = 1;
			for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
				LuaTable entry = new LuaTable();
				entry.set("id", container.getMetadata().getId());
				entry.set("name", container.getMetadata().getName());
				entry.set("version", container.getMetadata().getVersion().getFriendlyString());
				result.set(index++, entry);
			}
			return result;
		});
		return api;
	}

	private static LuaTable gameApi() {
		LuaTable api = new LuaTable();
		bind(api, "version", args -> LuaValue.valueOf(modVersion("minecraft")));
		bind(api, "loaderVersion", args -> LuaValue.valueOf(modVersion("fabricloader")));
		return api;
	}

	private static LuaTable serverApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "playerCount", args -> {
			int count = onServer(server, current -> current.getPlayerList().getPlayerCount());
			return LuaValue.valueOf(count);
		});
		bind(api, "isDedicated", args -> {
			boolean dedicated = onServer(server, MinecraftServer::isDedicatedServer);
			return LuaValue.valueOf(dedicated);
		});
		bind(api, "motd", args -> {
			String motd = onServer(server, MinecraftServer::getMotd);
			return LuaValue.valueOf(motd);
		});
		return api;
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

	private static BlockPos blockPos(Varargs args, int start) {
		return new BlockPos(args.checkint(start), args.checkint(start + 1), args.checkint(start + 2));
	}

	private static UUID playerUuid(Varargs args, int index) {
		String raw = args.checkjstring(index);
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new LuaError("无效玩家 UUID：" + raw);
		}
	}

	private static ServerPlayer player(MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player == null) throw new LuaRecoverableError("玩家不在线或 UUID 不存在：" + uuid);
		return player;
	}

	private static ServerLevel level(MinecraftServer server, Identifier dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().equals(dimension)) return level;
		}
		throw new IllegalArgumentException("维度不存在或未加载：" + dimension);
	}

	private static Identifier optionalDimension(LuaValue value) {
		return value.isnil() ? DEFAULT_DIMENSION : identifier(value.checkjstring());
	}

	private static Identifier identifier(String raw) {
		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (id == null) throw new LuaError("无效资源 ID：" + raw);
		return id;
	}

	private static String normalizedCommand(String raw) {
		String command = raw.strip();
		if (command.startsWith("/")) command = command.substring(1);
		if (command.isBlank()) throw new LuaError("命令不能为空");
		return command;
	}

	private static int rangedInt(int value, int minimum, int maximum, String label) {
		if (value < minimum || value > maximum) {
			throw new LuaError(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
		}
		return value;
	}

	private static double finite(double value, String label) {
		if (!Double.isFinite(value)) throw new LuaError(label + "必须是有限数字");
		return value;
	}

	private static void ensureUsablePosition(ServerLevel level, BlockPos pos) {
		if (!level.isInWorldBounds(pos)) throw new IllegalArgumentException("坐标超出世界边界");
		if (!level.isLoaded(pos)) throw new LuaRecoverableError("目标区块尚未加载：" + pos.toShortString());
	}

	private static PlayerSnapshot snapshot(ServerPlayer player) {
		return new PlayerSnapshot(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
				player.getHealth(), player.getMaxHealth(), player.level().dimension().identifier().toString());
	}

	private static String modVersion(String id) {
		return FabricLoader.getInstance().getModContainer(id)
				.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
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
			throw LuaRecoverableError.fromServer(error.getCause(), message(error.getCause()));
		}
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	@FunctionalInterface
	private interface LuaCallable {
		Varargs invoke(Varargs args);
	}

	private record PlayerSnapshot(double x, double y, double z, float yaw, float pitch,
			float health, float maxHealth, String dimension) {
	}

	private record WeatherSnapshot(boolean raining, boolean thundering) {
	}
}
