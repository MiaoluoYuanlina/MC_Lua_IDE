package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Immutable, post-click server snapshots. Lua callbacks never run inside vanilla menu mutations. */
public final class LuaContainerEvents {
    private static final String[] EVENTS = {"containerClicked", "containerItemTaken", "containerItemPlaced",
            "containerItemToCursor", "inventoryItemToCursor"};
    private static final Map<AbstractContainerMenu, Origin> ORIGINS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ClassValue<List<Field>> CONTEXT_FIELDS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            List<Field> result = new ArrayList<>();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
                for (Field field : current.getDeclaredFields())
                    if (!Modifier.isStatic(field.getModifiers()) && (Container.class.isAssignableFrom(field.getType())
                            || BlockEntity.class.isAssignableFrom(field.getType())
                            || ContainerLevelAccess.class.isAssignableFrom(field.getType())) && field.trySetAccessible()) result.add(field);
            return List.copyOf(result);
        }
    };
    private LuaContainerEvents() { }

    static void origin(AbstractContainerMenu menu, BlockPos pos, String dimension) {
        ORIGINS.put(menu, new Origin(pos.getX(), pos.getY(), pos.getZ(), dimension));
    }
    static void originless(AbstractContainerMenu menu) { ORIGINS.put(menu, null); }

    static void closeOwned(MinecraftServer server, ServerScriptManager.ScriptExecution execution) {
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers())
                if (player.containerMenu instanceof LuaVirtualChestMenu menu && menu.owner == execution) player.closeContainer();
        });
    }

    public static Capture begin(AbstractContainerMenu menu, net.minecraft.world.entity.player.Player player,
                                int slot, int button, ContainerInput input) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.containerMenu != menu
                || !ServerScriptManager.observesAny(EVENTS)) return null;
        return new Capture(menu, serverPlayer, slot, button, input);
    }

    public static final class Capture {
        private final AbstractContainerMenu menu;
        private final ServerPlayer player;
        private final int clicked, button;
        private final ContainerInput input;
        private final List<ItemStack> before;
        private final ItemStack cursor;
        private Capture(AbstractContainerMenu menu, ServerPlayer player, int clicked, int button, ContainerInput input) {
            this.menu = menu; this.player = player; this.clicked = clicked; this.button = button; this.input = input;
            before = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            cursor = menu.getCarried().copy();
        }
        public void finish() {
            ItemStack afterCursor = menu.getCarried().copy();
            int cursorBudget = afterCursor.isEmpty() ? 0 : afterCursor.getCount()
                    - (ItemStack.isSameItemSameComponents(cursor, afterCursor) ? cursor.getCount() : 0);
            boolean changed = !ItemStack.matches(cursor, afterCursor);
            for (int i = 0; i < Math.min(before.size(), menu.slots.size()); i++) {
                ItemStack old = before.get(i), now = menu.getSlot(i).getItem().copy();
                if (ItemStack.matches(old, now)) continue;
                changed = true;
                boolean same = ItemStack.isSameItemSameComponents(old, now);
                int taken = old.isEmpty() ? 0 : old.getCount() - (same ? now.getCount() : 0);
                int placed = now.isEmpty() ? 0 : now.getCount() - (same ? old.getCount() : 0);
                boolean inventory = inventorySlot(menu, player, i);
                Map<String, Object> data = base(menu, player, i, button, input);
                data.put("clickedSlotId", clicked);
                data.put("before", item(player, old, old.getCount()));
                data.put("after", item(player, now, now.getCount()));
                data.put("cursorBefore", item(player, cursor, cursor.getCount()));
                data.put("cursorAfter", item(player, afterCursor, afterCursor.getCount()));
                if (taken > 0) {
                    putItem(data, player, old, taken);
                    if (!inventory) ServerScriptManager.emitEvent("containerItemTaken", data);
                    if (cursorBudget > 0 && ItemStack.isSameItemSameComponents(old, afterCursor)) {
                        int count = Math.min(taken, cursorBudget); cursorBudget -= count;
                        putItem(data, player, old, count);
                        ServerScriptManager.emitEvent(inventory ? "inventoryItemToCursor" : "containerItemToCursor", data);
                    }
                }
                if (placed > 0 && !inventory) {
                    putItem(data, player, now, placed);
                    ServerScriptManager.emitEvent("containerItemPlaced", data);
                }
            }
            Map<String, Object> click = base(menu, player, clicked, button, input);
            ItemStack stack = clicked >= 0 && clicked < before.size() ? before.get(clicked) : ItemStack.EMPTY;
            putItem(click, player, stack, stack.getCount());
            click.put("cursorBefore", item(player, cursor, cursor.getCount()));
            click.put("cursorAfter", item(player, afterCursor, afterCursor.getCount()));
            click.put("changed", changed);
            ServerScriptManager.emitEvent("containerClicked", click);
        }
    }

    private static boolean inventorySlot(AbstractContainerMenu menu, ServerPlayer player, int slot) {
        return menu == player.inventoryMenu || menu.slots.get(slot).container == player.getInventory();
    }

    static Map<String, Object> describe(AbstractContainerMenu menu, ServerPlayer player) {
        Map<String, Object> data = new LinkedHashMap<>();
        String type = menu == player.inventoryMenu ? "minecraft:inventory" : String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType()));
        data.put("containerType", type); data.put("containerId", menu.containerId);
        data.put("virtual", menu instanceof LuaVirtualChestMenu);
        data.put("readOnly", menu instanceof LuaVirtualChestMenu chest && chest.readOnly);
        if (menu instanceof LuaVirtualChestMenu chest) data.put("containerTitle", chest.title.getString());
        Origin origin = ORIGINS.get(menu);
        if (!(menu instanceof LuaVirtualChestMenu) && origin == null && !ORIGINS.containsKey(menu)) {
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory()) continue;
                origin = resolve(slot.container, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (origin != null) break;
            }
            if (origin == null) origin = resolve(menu, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }
        if (origin != null) {
            data.put("x", origin.x); data.put("y", origin.y); data.put("z", origin.z); data.put("dimension", origin.dimension);
        }
        return data;
    }

    private static Map<String, Object> base(AbstractContainerMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        Map<String, Object> data = describe(menu, player);
        data.put("playerUuid", player.getUUID().toString()); data.put("playerName", player.getName().getString());
        data.put("playerX", player.getX()); data.put("playerY", player.getY()); data.put("playerZ", player.getZ());
        data.put("playerDimension", player.level().dimension().identifier().toString());
        data.put("slotId", slot); data.put("button", button); data.put("clickType", input.name());
        data.put("slotArea", slot < 0 || slot >= menu.slots.size() ? "outside" : inventorySlot(menu, player, slot) ? "inventory" : "container");
        if (slot >= 0 && slot < menu.slots.size()) {
            Slot target = menu.getSlot(slot);
            data.put("containerSlotId", target.getContainerSlot());
            if (!inventorySlot(menu, player, slot) && !(menu instanceof LuaVirtualChestMenu)) {
                Origin origin = resolve(target.container, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (origin != null) {
                    data.put("x", origin.x); data.put("y", origin.y); data.put("z", origin.z); data.put("dimension", origin.dimension);
                }
            }
        }
        return data;
    }

    private static Map<String, Object> item(ServerPlayer player, ItemStack stack, int count) {
        var components = LuaItemStackCodec.toJavaComponents(player.level().getServer(), stack);
        return Map.of("id", stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                "count", Math.max(0, count), "empty", stack.isEmpty(), "components", components);
    }
    private static void putItem(Map<String, Object> data, ServerPlayer player, ItemStack stack, int count) {
        var item = item(player, stack, count);
        data.put("item", item); data.put("itemId", item.get("id")); data.put("count", item.get("count"));
        data.put("itemCount", item.get("count")); data.put("itemComponents", item.get("components"));
    }

    private static Origin resolve(Object value, Set<Object> seen, int depth) {
        if (value == null || depth > 3 || !seen.add(value)) return null;
        if (value instanceof BlockEntity block && block.getLevel() != null)
            return new Origin(block.getBlockPos().getX(), block.getBlockPos().getY(), block.getBlockPos().getZ(), block.getLevel().dimension().identifier().toString());
        if (value instanceof Entity entity)
            return new Origin(entity.getX(), entity.getY(), entity.getZ(), entity.level().dimension().identifier().toString());
        if (value instanceof ContainerLevelAccess access)
            return access.evaluate((level, pos) -> new Origin(pos.getX(), pos.getY(), pos.getZ(), level.dimension().identifier().toString())).orElse(null);
        for (Field field : CONTEXT_FIELDS.get(value.getClass())) {
            try {
                Origin found = resolve(field.get(value), seen, depth + 1);
                if (found != null) return found;
            } catch (IllegalAccessException ignored) { }
        }
        return null;
    }
    private record Origin(double x, double y, double z, String dimension) { }
}
