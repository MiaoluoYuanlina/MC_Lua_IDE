package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.UUID;
import java.util.function.Function;

/** Server-only player menu/cursor API; no client extension channel is used. */
final class LuaPlayerMenuApi {
    static LuaTable create(MinecraftServer server, ServerScriptManager.ScriptExecution execution) {
        LuaTable api = new LuaTable();
        bind(api, "openChest", args -> {
            UUID uuid = uuid(args);
            LuaTable items = args.arg(3).isnil() ? new LuaTable() : args.checktable(3);
            int highest = 0;
            for (LuaValue key : items.keys()) {
                if (!key.isinttype() || key.toint() < 1 || key.toint() > 54)
                    throw new LuaError("箱子物品数组索引必须为 1 到 54；数组第 1 项对应槽位 0");
                highest = Math.max(highest, key.toint());
            }
            int rows = args.optint(4, Math.max(3, (highest + 8) / 9));
            if (rows < 1 || rows > 6 || highest > rows * 9) throw new LuaError("箱子行数必须为 1～6，物品数组不能超出行数 × 9");
            boolean readOnly = args.optboolean(5, false);
            return MinecraftLuaInteractionApi.onServer(server, current -> {
                ServerPlayer player = MinecraftLuaInteractionApi.player(current, uuid);
                var title = args.arg(2).isnil() ? net.minecraft.network.chat.Component.literal("箱子") : LuaTextComponentCodec.decode(current, args.arg(2));
                SimpleContainer contents = new SimpleContainer(rows * 9);
                // Decode/validate every entry before closing the player's existing menu.
                for (LuaValue key : items.keys()) {
                    LuaValue entry = items.get(key);
                    ItemStack stack;
                    if (entry.istable()) stack = stack(current, entry.get("id"), entry.get("count"), entry.get("components"));
                    else stack = stack(current, entry, LuaValue.NIL, LuaValue.NIL);
                    contents.setItem(key.toint() - 1, stack);
                }
                var opened = player.openMenu(new SimpleMenuProvider((id, inventory, ignored) ->
                        new LuaVirtualChestMenu(id, inventory, contents, rows, title, readOnly, execution), title));
                return opened.isPresent() ? LuaValue.valueOf(opened.getAsInt()) : LuaValue.NIL;
            });
        });
        bind(api, "menuItem", args -> MinecraftLuaInteractionApi.onServer(server, current -> {
            ServerPlayer player = MinecraftLuaInteractionApi.player(current, uuid(args));
            AbstractContainerMenu menu = expected(player, args.arg(3));
            int slot = checkedSlot(menu, args.checkint(2));
            LuaTable result = LuaItemStackCodec.toLua(current, menu.getSlot(slot).getItem());
            result.set("slotId", slot); result.set("containerId", menu.containerId);
            return result;
        }));
        bind(api, "setMenuItem", args -> MinecraftLuaInteractionApi.onServer(server, current -> {
            ServerPlayer player = MinecraftLuaInteractionApi.player(current, uuid(args));
            AbstractContainerMenu menu = expected(player, args.arg(6));
            int slot = checkedSlot(menu, args.checkint(2));
            ItemStack stack = stack(current, args.arg(3), args.arg(4), args.arg(5));
            if (stack.getCount() > menu.getSlot(slot).getMaxStackSize(stack)) throw new LuaError("物品数量超过目标槽位上限");
            menu.getSlot(slot).set(stack);
            menu.broadcastChanges();
            LuaTable result = LuaItemStackCodec.toLua(current, stack);
            result.set("slotId", slot); result.set("containerId", menu.containerId);
            return result;
        }));
        bind(api, "carried", args -> MinecraftLuaInteractionApi.onServer(server, current -> {
            ServerPlayer player = MinecraftLuaInteractionApi.player(current, uuid(args));
            return LuaItemStackCodec.toLua(current, expected(player, args.arg(2)).getCarried());
        }));
        bind(api, "setCarried", args -> MinecraftLuaInteractionApi.onServer(server, current -> {
            ServerPlayer player = MinecraftLuaInteractionApi.player(current, uuid(args));
            AbstractContainerMenu menu = expected(player, args.arg(5));
            ItemStack stack = stack(current, args.arg(2), args.arg(3), args.arg(4));
            menu.setCarried(stack); menu.broadcastChanges();
            return LuaItemStackCodec.toLua(current, stack);
        }));
        return api;
    }

    private static UUID uuid(Varargs args) {
        String raw = args.checkjstring(1);
        try { return UUID.fromString(raw); } catch (IllegalArgumentException error) { throw new LuaError("无效玩家 UUID：" + raw); }
    }
    static AbstractContainerMenu expected(ServerPlayer player, LuaValue expected) {
        if (!expected.isnil() && player.containerMenu.containerId != expected.checkint())
            throw new LuaRecoverableError("玩家当前打开的容器已改变，已跳过过期容器操作");
        return player.containerMenu;
    }
    private static int checkedSlot(AbstractContainerMenu menu, int slot) {
        if (slot < 0 || slot >= menu.slots.size()) throw new LuaError("菜单槽位须在 0～" + (menu.slots.size() - 1) + " 之间");
        return slot;
    }
    private static ItemStack stack(MinecraftServer server, LuaValue id, LuaValue count, LuaValue components) {
        if (id.isnil() || id.isstring() && (id.tojstring().equals("minecraft:air") || id.tojstring().equals("air"))
                || !count.isnil() && count.checkint() == 0) return ItemStack.EMPTY;
        ItemStack result = LuaItemStackCodec.create(server, id, count, components, 99_999);
        if (result.getCount() > result.getMaxStackSize()) throw new LuaError("单槽物品数量超过此物品组件定义的堆叠上限：" + result.getMaxStackSize());
        return result;
    }
    private static void bind(LuaTable api, String name, Function<Varargs, Varargs> function) {
        api.set(name, new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return function.apply(args); }
        });
    }
}
