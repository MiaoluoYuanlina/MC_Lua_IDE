package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import java.util.*;

public final class LuaInventoryTradeSmoke {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Trade check " + checks); }
    private static List<ItemStack> empty(int size) {
        List<ItemStack> result=new ArrayList<>();for(int i=0;i<size;i++)result.add(ItemStack.EMPTY);return result;
    }
    public static void main(String[] ignored) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 26.1 item defaults are normally bound by the world data loader; bind only this test's fixtures.
        for (var item : List.of(Items.IRON_INGOT, Items.WHITE_WOOL))
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        var costs=List.of(new LuaInventoryTrade.Request(new ItemStack(Items.IRON_INGOT),4,false));
        var rewards=List.of(new LuaInventoryTrade.Request(new ItemStack(Items.WHITE_WOOL),16,false));
        var slots=empty(36);slots.set(0,new ItemStack(Items.IRON_INGOT,8));
        check(LuaInventoryTrade.apply(slots,costs,rewards)==null);
        check(slots.get(0).getCount()==4&&slots.get(1).is(Items.WHITE_WOOL)&&slots.get(1).getCount()==16);
        slots=empty(1);slots.set(0,new ItemStack(Items.IRON_INGOT,8));
        check(LuaInventoryTrade.apply(slots,costs,rewards)!=null);
        slots=empty(1);slots.set(0,new ItemStack(Items.IRON_INGOT,4));
        check(LuaInventoryTrade.apply(slots,costs,rewards)==null&&slots.get(0).is(Items.WHITE_WOOL));
        slots=empty(3);slots.set(0,new ItemStack(Items.IRON_INGOT,4));
        check(LuaInventoryTrade.apply(slots,costs,List.of(new LuaInventoryTrade.Request(new ItemStack(Items.WHITE_WOOL),130,false)))==null);
        check(slots.stream().mapToInt(ItemStack::getCount).sum()==130&&slots.stream().allMatch(s->s.getCount()<=s.getMaxStackSize()));
        ItemStack special=new ItemStack(Items.IRON_INGOT,8);special.set(DataComponents.CUSTOM_NAME,Component.literal("special"));
        slots=empty(36);slots.set(0,special.copy());
        check(LuaInventoryTrade.apply(slots,List.of(new LuaInventoryTrade.Request(new ItemStack(Items.IRON_INGOT),4,true)),rewards)==null);
        slots=empty(36);slots.set(0,special.copy());
        check(LuaInventoryTrade.apply(slots,List.of(new LuaInventoryTrade.Request(special.copyWithCount(1),4,true)),rewards)==null);
        slots=empty(36);slots.set(0,special.copy());
        check(LuaInventoryTrade.apply(slots,costs,rewards)==null);
        slots=empty(36);slots.set(0,new ItemStack(Items.IRON_INGOT,6));
        check(LuaInventoryTrade.apply(slots,List.of(costs.getFirst(),costs.getFirst()),rewards)!=null);
        System.out.println("Inventory trade smoke passed: "+checks+" checks");
    }
}
