package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Server-authoritative player menus, inventories, equipment and block containers. */
final class MinecraftLuaInteractionApi {
	private static final int API_TIMEOUT_SECONDS = 10;
	private static final Identifier DEFAULT_DIMENSION = Identifier.withDefaultNamespace("overworld");

	private MinecraftLuaInteractionApi() {
	}

	static void install(LuaTable mc, MinecraftServer server, ServerScriptManager.ScriptExecution execution) {
		extend(mc, "Player", playerApi(server));
		extend(mc, "Player", LuaPlayerMenuApi.create(server, execution));
		extend(mc, "Item", itemApi(server));
		extend(mc, "Item", LuaInventoryTrade.api(server));
		extend(mc, "Entity", entityItemApi(server));
		setNamespace(mc, "Container", containerApi(server));
	}

	private static LuaTable playerApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "openBlock", args -> openBlock(server, args, 1, 2));
		bind(api, "openCrafting", args -> openStation(server, args, Station.CRAFTING));
		bind(api, "openEnderChest", args -> {
			UUID uuid = playerUuid(args, 1);
			String title = args.optjstring(2, "末影箱");
			int containerId = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				player.getEnderChestInventory().setActiveChest(null);
				return openMenu(player, new SimpleMenuProvider(
						(id, inventory, ignored) -> ChestMenu.threeRows(id, inventory, player.getEnderChestInventory()),
						Component.literal(title)));
			});
			return menuResult(containerId);
		});
		bind(api, "openAnvil", args -> openStation(server, args, Station.ANVIL));
		bind(api, "openEnchanting", args -> openStation(server, args, Station.ENCHANTING));
		bind(api, "openGrindstone", args -> openStation(server, args, Station.GRINDSTONE));
		bind(api, "openLoom", args -> openStation(server, args, Station.LOOM));
		bind(api, "openCartography", args -> openStation(server, args, Station.CARTOGRAPHY));
		bind(api, "openStonecutter", args -> openStation(server, args, Station.STONECUTTER));
		bind(api, "openSmithing", args -> openStation(server, args, Station.SMITHING));
		bind(api, "closeMenu", args -> {
			UUID uuid = playerUuid(args, 1);
			boolean wasOpen = onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				boolean open = player.containerMenu != player.inventoryMenu;
				player.closeContainer();
				return open;
			});
			return LuaValue.valueOf(wasOpen);
		});
		bind(api, "menu", args -> {
			UUID uuid = playerUuid(args, 1);
			return onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				AbstractContainerMenu menu = player.containerMenu;
				boolean open = menu != player.inventoryMenu;
				LuaTable result = new LuaTable();
				result.set("open", LuaValue.valueOf(open));
				result.set("containerId", menu.containerId);
				result.set("slots", menu.slots.size());
				result.set("className", menu.getClass().getSimpleName());
				LuaContainerEvents.describe(menu, player).forEach((key, value) -> {
					if (value instanceof Boolean b) result.set(key, LuaValue.valueOf(b));
					else if (value instanceof Number n) result.set(key, LuaValue.valueOf(n.doubleValue()));
					else result.set(key, String.valueOf(value));
				});
				if (open) {
					Identifier type = BuiltInRegistries.MENU.getKey(menu.getType());
					result.set("type", type == null ? "unknown" : type.toString());
				} else {
					result.set("type", "minecraft:inventory");
				}
				return result;
			});
		});
		bind(api, "abilities", args -> {
			UUID uuid = playerUuid(args, 1);
			return onServer(server, current -> abilitiesTable(player(current, uuid).getAbilities()));
		});
		bind(api, "setFlying", args -> {
			UUID uuid = playerUuid(args, 1);
			boolean mayFly = args.checkboolean(2);
			boolean flying = args.optboolean(3, mayFly);
			onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				Abilities abilities = player.getAbilities();
				abilities.mayfly = mayFly;
				abilities.flying = mayFly && flying;
				player.onUpdateAbilities();
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "setSpeeds", args -> {
			UUID uuid = playerUuid(args, 1);
			float walk = (float) ranged(args.checkdouble(2), 0.0, 2.0, "行走速度");
			float fly = (float) ranged(args.checkdouble(3), 0.0, 2.0, "飞行速度");
			onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				player.getAbilities().setWalkingSpeed(walk);
				player.getAbilities().setFlyingSpeed(fly);
				player.onUpdateAbilities();
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "absorption", args -> {
			UUID uuid = playerUuid(args, 1);
			double absorption = onServer(server, current -> (double) player(current, uuid).getAbsorptionAmount());
			return LuaValue.valueOf(absorption);
		});
		bind(api, "setAbsorption", args -> {
			UUID uuid = playerUuid(args, 1);
			float value = (float) ranged(args.checkdouble(2), 0.0, 2048.0, "伤害吸收值");
			onServer(server, current -> {
				player(current, uuid).setAbsorptionAmount(value);
				return true;
			});
			return LuaValue.valueOf(value);
		});
		return api;
	}

	private static LuaTable itemApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "inventory", args -> {
			UUID uuid = playerUuid(args, 1);
			return onServer(server, current -> {
				Inventory inventory = player(current, uuid).getInventory();
				LuaTable result = new LuaTable();
				for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
					result.set(slot + 1, stackTable(current, inventory.getItem(slot), slot));
				}
				return result;
			});
		});
		bind(api, "getSlot", args -> {
			UUID uuid = playerUuid(args, 1);
			int slot = args.checkint(2);
			return onServer(server, current -> {
				Inventory inventory = player(current, uuid).getInventory();
				checkSlot(slot, inventory.getContainerSize(), "背包槽位");
				return stackTable(current, inventory.getItem(slot), slot);
			});
		});
		bind(api, "setSlot", args -> {
			UUID uuid = playerUuid(args, 1);
			int slot = args.checkint(2);
			return onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				Inventory inventory = player.getInventory();
				checkSlot(slot, inventory.getContainerSize(), "背包槽位");
				ItemStack requested = stack(current, args, 3, 4, 5);
				limitStack(requested, requested.getMaxStackSize());
				inventory.setItem(slot, requested);
				syncInventory(player);
				return stackTable(current, requested, slot);
			});
		});
		bind(api, "clearSlot", args -> {
			UUID uuid = playerUuid(args, 1);
			int slot = args.checkint(2);
			return onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				Inventory inventory = player.getInventory();
				checkSlot(slot, inventory.getContainerSize(), "背包槽位");
				ItemStack previous = inventory.getItem(slot).copy();
				inventory.setItem(slot, ItemStack.EMPTY);
				syncInventory(player);
				return stackTable(current, previous, slot);
			});
		});
		bind(api, "swapSlots", args -> {
			UUID uuid = playerUuid(args, 1);
			int first = args.checkint(2);
			int second = args.checkint(3);
			onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				Inventory inventory = player.getInventory();
				checkSlot(first, inventory.getContainerSize(), "第一个背包槽位");
				checkSlot(second, inventory.getContainerSize(), "第二个背包槽位");
				ItemStack held = inventory.getItem(first);
				inventory.setItem(first, inventory.getItem(second));
				inventory.setItem(second, held);
				syncInventory(player);
				return true;
			});
			return LuaValue.TRUE;
		});
		bind(api, "selectedSlot", args -> {
			UUID uuid = playerUuid(args, 1);
			int slot = onServer(server, current -> player(current, uuid).getInventory().getSelectedSlot());
			return LuaValue.valueOf(slot);
		});
		bind(api, "setSelectedSlot", args -> {
			UUID uuid = playerUuid(args, 1);
			int slot = rangedInt(args.checkint(2), 0, Inventory.getSelectionSize() - 1, "快捷栏槽位");
			onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				player.getInventory().setSelectedSlot(slot);
				player.connection.send(new ClientboundSetHeldSlotPacket(slot));
				return true;
			});
			return LuaValue.valueOf(slot);
		});
		bind(api, "equipment", args -> {
			UUID uuid = playerUuid(args, 1);
			EquipmentSlot slot = equipmentSlot(args.checkjstring(2));
			return onServer(server, current -> stackTable(
					current, player(current, uuid).getItemBySlot(slot), slot.getName()));
		});
		bind(api, "setEquipment", args -> {
			UUID uuid = playerUuid(args, 1);
			EquipmentSlot slot = equipmentSlot(args.checkjstring(2));
			return onServer(server, current -> {
				ServerPlayer player = player(current, uuid);
				ItemStack requested = stack(current, args, 3, 4, 5);
				limitStack(requested, requested.getMaxStackSize());
				player.setItemSlot(slot, requested);
				syncInventory(player);
				return stackTable(current, requested, slot.getName());
			});
		});
		return api;
	}

	private static LuaTable entityItemApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "equipment", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			EquipmentSlot slot = equipmentSlot(args.checkjstring(2));
			return onServer(server, current -> stackTable(
					current, livingEntity(current, uuid).getItemBySlot(slot), slot.getName()));
		});
		bind(api, "setEquipment", args -> {
			UUID uuid = uuid(args.checkjstring(1), "实体 UUID");
			EquipmentSlot slot = equipmentSlot(args.checkjstring(2));
			return onServer(server, current -> {
				LivingEntity entity = livingEntity(current, uuid);
				ItemStack requested = stack(current, args, 3, 4, 5);
				limitStack(requested, requested.getMaxStackSize());
				entity.setItemSlot(slot, requested);
				return stackTable(current, requested, slot.getName());
			});
		});
		return api;
	}

	private static LuaTable containerApi(MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "info", args -> withContainer(server, args, 4, (level, pos, blockEntity, container) -> {
			LuaTable result = new LuaTable();
			result.set("blockId", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
			Identifier type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
			result.set("blockEntityType", type == null ? "unknown" : type.toString());
			result.set("size", container.getContainerSize());
			result.set("empty", LuaValue.valueOf(container.isEmpty()));
			result.set("x", pos.getX());
			result.set("y", pos.getY());
			result.set("z", pos.getZ());
			result.set("dimension", level.dimension().identifier().toString());
			return result;
		}));
		bind(api, "list", args -> withContainer(server, args, 4, (level, pos, blockEntity, container) -> {
			LuaTable result = new LuaTable();
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				result.set(slot + 1, stackTable(server, container.getItem(slot), slot));
			}
			return result;
		}));
		bind(api, "getSlot", args -> {
			int slot = args.checkint(4);
			return withContainer(server, args, 5, (level, pos, blockEntity, container) -> {
				checkSlot(slot, container.getContainerSize(), "容器槽位");
				return stackTable(server, container.getItem(slot), slot);
			});
		});
		bind(api, "setSlot", args -> {
			int slot = args.checkint(4);
			boolean legacyDimension = isLegacyDimensionArgument(args.arg(7), args.arg(8));
			int dimensionIndex = legacyDimension ? 7 : 8;
			LuaValue components = legacyDimension ? LuaValue.NIL : args.arg(7);
			return withContainer(server, args, dimensionIndex, (level, pos, blockEntity, container) -> {
				checkSlot(slot, container.getContainerSize(), "容器槽位");
				ItemStack requested = stack(server, args.arg(5), args.arg(6), components);
				int maximum = requested.isEmpty() ? 0 : Math.min(requested.getMaxStackSize(), container.getMaxStackSize(requested));
				limitStack(requested, maximum);
				container.setItem(slot, requested);
				container.setChanged();
				level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
				return stackTable(server, requested, slot);
			});
		});
		bind(api, "clear", args -> withContainer(server, args, 4, (level, pos, blockEntity, container) -> {
			int removed = 0;
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				removed += container.getItem(slot).getCount();
			}
			container.clearContent();
			container.setChanged();
			level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
			return LuaValue.valueOf(removed);
		}));
		bind(api, "open", args -> openBlock(server, args, 1, 2));
		return api;
	}

	private static LuaValue openBlock(MinecraftServer server, Varargs args, int uuidIndex, int coordinateStart) {
		UUID uuid = playerUuid(args, uuidIndex);
		BlockPos pos = blockPos(args, coordinateStart);
		Identifier dimension = optionalDimension(args, coordinateStart + 3);
		int containerId = onServer(server, current -> {
			ServerPlayer player = player(current, uuid);
			ServerLevel level = level(current, dimension);
			ensurePosition(level, pos);
			MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
			if (provider == null) throw new LuaError("该方块没有可打开的菜单："
					+ BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
			int opened = openMenu(player, provider);
			if (opened >= 0) LuaContainerEvents.origin(player.containerMenu, pos, dimension.toString());
			return opened;
		});
		return menuResult(containerId);
	}

	private static LuaValue openStation(MinecraftServer server, Varargs args, Station station) {
		UUID uuid = playerUuid(args, 1);
		String title = args.optjstring(2, station.title);
		int containerId = onServer(server, current -> {
			ServerPlayer player = player(current, uuid);
			SimpleMenuProvider provider = new SimpleMenuProvider((id, inventory, menuPlayer) -> {
				ContainerLevelAccess access = ContainerLevelAccess.create(menuPlayer.level(), menuPlayer.blockPosition());
				return createStationMenu(station, id, inventory, access);
			}, Component.literal(title));
			int opened = openMenu(player, provider);
			if (opened >= 0) LuaContainerEvents.originless(player.containerMenu);
			return opened;
		});
		return menuResult(containerId);
	}

	private static AbstractContainerMenu createStationMenu(Station station, int id, Inventory inventory,
			ContainerLevelAccess access) {
		return switch (station) {
			case CRAFTING -> new CraftingMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case ANVIL -> new AnvilMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case ENCHANTING -> new EnchantmentMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case GRINDSTONE -> new GrindstoneMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case LOOM -> new LoomMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case CARTOGRAPHY -> new CartographyTableMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case STONECUTTER -> new StonecutterMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
			case SMITHING -> new SmithingMenu(id, inventory, access) {
				@Override public boolean stillValid(Player player) { return true; }
			};
		};
	}

	private static int openMenu(ServerPlayer player, MenuProvider provider) {
		OptionalInt opened = player.openMenu(provider);
		return opened.orElse(-1);
	}

	private static LuaValue menuResult(int containerId) {
		return containerId < 0 ? LuaValue.NIL : LuaValue.valueOf(containerId);
	}

	private static LuaTable abilitiesTable(Abilities abilities) {
		LuaTable result = new LuaTable();
		result.set("invulnerable", LuaValue.valueOf(abilities.invulnerable));
		result.set("flying", LuaValue.valueOf(abilities.flying));
		result.set("mayFly", LuaValue.valueOf(abilities.mayfly));
		result.set("instantBuild", LuaValue.valueOf(abilities.instabuild));
		result.set("mayBuild", LuaValue.valueOf(abilities.mayBuild));
		result.set("walkingSpeed", abilities.getWalkingSpeed());
		result.set("flyingSpeed", abilities.getFlyingSpeed());
		return result;
	}

	private static LuaTable stackTable(MinecraftServer server, ItemStack stack, int slot) {
		LuaTable result = stackTable(server, stack);
		result.set("slot", slot);
		return result;
	}

	private static LuaTable stackTable(MinecraftServer server, ItemStack stack, String slot) {
		LuaTable result = stackTable(server, stack);
		result.set("slot", slot);
		return result;
	}

	private static LuaTable stackTable(MinecraftServer server, ItemStack stack) {
		return LuaItemStackCodec.toLua(server, stack);
	}

	private static ItemStack stack(MinecraftServer server, Varargs args, int idIndex, int countIndex,
			int componentsIndex) {
		if (args.arg(idIndex).isnil()) return ItemStack.EMPTY;
		return stack(server, args.arg(idIndex), args.arg(countIndex), args.arg(componentsIndex));
	}

	private static ItemStack stack(MinecraftServer server, LuaValue id, LuaValue count, LuaValue components) {
		if (id.isnil()) return ItemStack.EMPTY;
		return LuaItemStackCodec.create(server, id, count, components, 999);
	}

	private static void limitStack(ItemStack stack, int maximum) {
		if (!stack.isEmpty() && stack.getCount() > maximum) stack.setCount(maximum);
	}

	private static boolean isLegacyDimensionArgument(LuaValue seventh, LuaValue eighth) {
		if (!eighth.isnil() || !seventh.isstring()) return false;
		return !seventh.checkjstring().stripLeading().startsWith("{");
	}

	private static void syncInventory(ServerPlayer player) {
		player.inventoryMenu.broadcastChanges();
		if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
	}

	private static EquipmentSlot equipmentSlot(String name) {
		String normalized = name.strip().toLowerCase(Locale.ROOT);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getName().equals(normalized) || slot.getSerializedName().equals(normalized)) return slot;
		}
		throw new LuaError("未知装备槽位：" + name + "；可用 mainhand/offhand/feet/legs/chest/head/body/saddle");
	}

	private static <T extends Varargs> T withContainer(MinecraftServer server, Varargs args,
			int dimensionIndex, ContainerAction<T> action) {
		BlockPos pos = blockPos(args, 1);
		Identifier dimension = optionalDimension(args, dimensionIndex);
		return onServer(server, current -> {
			ServerLevel level = level(current, dimension);
			ensurePosition(level, pos);
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof Container container)) {
				throw new LuaError("该方块实体不是可读写容器：" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
			}
			return action.apply(level, pos, blockEntity, container);
		});
	}

	private static BlockPos blockPos(Varargs args, int start) {
		return new BlockPos(args.checkint(start), args.checkint(start + 1), args.checkint(start + 2));
	}

	private static Identifier optionalDimension(Varargs args, int index) {
		return args.arg(index).isnil() ? DEFAULT_DIMENSION : id(args, index);
	}

	private static Identifier id(Varargs args, int index) {
		String raw = args.checkjstring(index);
		Identifier parsed = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (parsed == null) throw new LuaError("无效资源 ID：" + raw);
		return parsed;
	}

	private static UUID playerUuid(Varargs args, int index) {
		try {
			return UUID.fromString(args.checkjstring(index));
		} catch (IllegalArgumentException error) {
			throw new LuaError("无效玩家 UUID：" + args.arg(index).tojstring());
		}
	}

	static ServerPlayer player(MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player == null) throw new LuaRecoverableError("玩家不在线或 UUID 不存在：" + uuid);
		return player;
	}

	private static UUID uuid(String raw, String label) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new LuaError("无效" + label + "：" + raw);
		}
	}

	private static LivingEntity livingEntity(MinecraftServer server, UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(uuid);
			if (entity == null) continue;
			if (entity instanceof LivingEntity living) return living;
			throw new LuaError("目标实体不是有生命实体（LivingEntity），不能读写装备：" + uuid);
		}
		throw new LuaRecoverableError("实体不存在或未加载：" + uuid);
	}

	private static ServerLevel level(MinecraftServer server, Identifier dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().equals(dimension)) return level;
		}
		throw new LuaError("维度不存在或未加载：" + dimension);
	}

	private static void ensurePosition(ServerLevel level, BlockPos pos) {
		if (!level.isInWorldBounds(pos)) throw new LuaError("坐标超出世界边界：" + pos.toShortString());
		if (!level.hasChunkAt(pos)) throw new LuaRecoverableError("坐标所在区块尚未加载：" + pos.toShortString());
	}

	private static void checkSlot(int slot, int size, String label) {
		if (slot < 0 || slot >= size) throw new LuaError(label + "必须在 0 到 " + (size - 1) + " 之间");
	}

	private static int rangedInt(int value, int minimum, int maximum, String label) {
		if (value < minimum || value > maximum) throw new LuaError(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
		return value;
	}

	private static double ranged(double value, double minimum, double maximum, String label) {
		if (!Double.isFinite(value) || value < minimum || value > maximum) {
			throw new LuaError(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
		}
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

	static <T> T onServer(MinecraftServer server, Function<MinecraftServer, T> action) {
		if (server.isSameThread()) return action.apply(server);
		return await(server.submit(() -> action.apply(server)));
	}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new LuaError("脚本已停止");
		} catch (TimeoutException error) {
			throw new LuaError("等待 Minecraft 服务端响应超时");
		} catch (ExecutionException error) {
			throw LuaRecoverableError.fromServer(error.getCause(), message(error.getCause()));
		}
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private enum Station {
		CRAFTING("工作台"),
		ANVIL("铁砧"),
		ENCHANTING("附魔台"),
		GRINDSTONE("砂轮"),
		LOOM("织布机"),
		CARTOGRAPHY("制图台"),
		STONECUTTER("切石机"),
		SMITHING("锻造台");

		private final String title;

		Station(String title) {
			this.title = title;
		}
	}

	@FunctionalInterface
	private interface LuaCallable {
		Varargs invoke(Varargs args);
	}

	@FunctionalInterface
	private interface ContainerAction<T extends Varargs> {
		T apply(ServerLevel level, BlockPos pos, BlockEntity blockEntity, Container container);
	}
}
