package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.*;

/** Atomic inventory transaction for shops: no async Lua check/deduct/give race or partial charges. */
final class LuaInventoryTrade {
    static LuaTable api(MinecraftServer server) {
        LuaTable api = new LuaTable();
        api.set("trade", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                UUID uuid;
                try { uuid = UUID.fromString(args.checkjstring(1)); }
                catch (IllegalArgumentException error) { throw new LuaError("无效玩家 UUID"); }
                return MinecraftLuaInteractionApi.onServer(server, current -> {
                    var player = MinecraftLuaInteractionApi.player(current, uuid);
                    LuaPlayerMenuApi.expected(player, args.arg(4));
                    List<Request> costs = requests(current, args.checktable(2), true), rewards = requests(current, args.checktable(3), false);
                    List<ItemStack> staged = new ArrayList<>();
                    for (int slot = 0; slot < 36; slot++) staged.add(player.getInventory().getItem(slot).copy());
                    String failure = apply(staged, costs, rewards);
                    if (failure != null) return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, LuaValue.valueOf(failure)});
                    for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, staged.get(slot));
                    player.containerMenu.broadcastChanges();
                    return LuaValue.TRUE;
                });
            }
        });
        return api;
    }
    private static List<Request> requests(MinecraftServer server, LuaTable array, boolean costs) {
        LuaValue[] keys = array.keys();
        if (keys.length > 54) throw new LuaError("交易数组最多 54 项");
        List<Request> result = new ArrayList<>();
        for (int i = 1; i <= keys.length; i++) {
            LuaTable entry = array.get(i).checktable();
            int count = entry.get("count").optint(1);
            if (count < 1 || count > 999_999) throw new LuaError("交易数量须为 1～999999");
            var filter = costs ? LuaItemStackCodec.filter(server, entry.get("id"), entry.get("components")) : null;
            ItemStack item = costs ? new ItemStack(filter.item())
                    : LuaItemStackCodec.create(server, entry.get("id"), LuaValue.valueOf(1), entry.get("components"), 1);
            if (item.isEmpty()) throw new LuaError("交易物品不能为空气");
            result.add(new Request(item, count, filter));
        }
        return result;
    }
    static String apply(List<ItemStack> staged, List<Request> costs, List<Request> rewards) {
        for (Request cost : costs) {
            int remaining = cost.count;
            for (ItemStack item : staged) {
                if (item.isEmpty() || (cost.filter != null ? !cost.filter.matches(item) : !item.is(cost.item.getItem()))) continue;
                int removed = Math.min(remaining, item.getCount()); item.shrink(removed); remaining -= removed;
                if (remaining == 0) break;
            }
            if (remaining > 0) return "货币不足";
        }
        for (Request reward : rewards) {
            int remaining = reward.count;
            for (ItemStack item : staged) {
                if (item.isEmpty() || !ItemStack.isSameItemSameComponents(item, reward.item)) continue;
                int count = Math.min(remaining, Math.max(0, item.getMaxStackSize() - item.getCount()));
                item.grow(count); remaining -= count;
            }
            for (int slot = 0; remaining > 0 && slot < staged.size(); slot++) {
                if (!staged.get(slot).isEmpty()) continue;
                int count = Math.min(remaining, reward.item.getMaxStackSize());
                staged.set(slot, reward.item.copyWithCount(count)); remaining -= count;
            }
            if (remaining > 0) return "背包空间不足";
        }
        return null;
    }
    record Request(ItemStack item, int count, LuaItemStackCodec.ItemFilter filter) {
        Request(ItemStack item, int count, boolean matchComponents) {
            this(item, count, matchComponents ? new LuaItemStackCodec.ItemFilter(item.getItem(), item.getComponentsPatch()) : null);
        }
    }
}
