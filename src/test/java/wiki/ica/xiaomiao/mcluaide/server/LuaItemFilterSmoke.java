package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.luaj.vm2.LuaValue;
import java.util.List;
import java.util.ArrayList;

public final class LuaItemFilterSmoke {
	private static int checks;
	public static void main(String[] args) {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		for (var item : List.of(Items.KNOWLEDGE_BOOK, Items.PAPER, Items.IRON_INGOT))
			item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		LuaValue id = LuaValue.valueOf("minecraft:knowledge_book");
		var actual = LuaItemStackCodec.create(registries, id, LuaValue.valueOf(8), LuaValue.valueOf("""
				{"minecraft:custom_data":{"new_type":"fire_charge","nested":{"value":3,"extra":true},"tags":[1,2]},
				 "minecraft:item_model":"minecraft:fire_charge","minecraft:item_name":"火焰弹",
				 "minecraft:lore":["右键发射"],"minecraft:max_stack_size":64}
				"""), 999);
		for (String filter : List.of("{}", "{\"minecraft:custom_data\":{\"new_type\":\"fire_charge\"}}",
				"{\"minecraft:custom_data\":{\"nested\":{\"value\":3}}}", "{\"minecraft:custom_data\":{\"tags\":[2]}}",
				"{\"minecraft:item_model\":\"minecraft:fire_charge\"}", "{\"minecraft:item_name\":\"火焰弹\"}",
				"{\"minecraft:lore\":[\"右键发射\"]}", "{\"minecraft:max_stack_size\":64}"))
			require(LuaItemStackCodec.filter(registries, id, LuaValue.valueOf(filter)).matches(actual), "Partial filter rejected: " + filter);
		for (String filter : List.of("{\"minecraft:custom_data\":{\"new_type\":\"other\"}}",
				"{\"minecraft:custom_data\":{\"nested\":{\"missing\":true}}}", "{\"minecraft:custom_data\":{\"tags\":[9]}}",
				"{\"minecraft:item_model\":\"minecraft:tnt\"}", "{\"minecraft:max_stack_size\":32}",
				"{\"minecraft:custom_data\":{\"new_type\":\"fire_charge\"},\"minecraft:item_name\":\"other\"}"))
			require(!LuaItemStackCodec.filter(registries, id, LuaValue.valueOf(filter)).matches(actual), "Mismatched filter accepted: " + filter);
		var marker = LuaItemStackCodec.filter(registries, id, LuaValue.valueOf("{\"minecraft:custom_data\":{\"new_type\":\"fire_charge\"}}"));
		require(!marker.matches(new ItemStack(Items.PAPER)) && !marker.matches(ItemStack.EMPTY) && !marker.matches(null), "ID/empty safety");
		require(!marker.matches(new ItemStack(Items.KNOWLEDGE_BOOK)), "Required component absent");
		require(LuaItemStackCodec.filter(registries, id, LuaValue.NIL).matches(actual), "Nil should match by ID");
		var fromLua = new org.luaj.vm2.LuaTable(); var data = new org.luaj.vm2.LuaTable(); data.set("new_type", "fire_charge"); fromLua.set("minecraft:custom_data", data);
		require(LuaItemStackCodec.filter(registries, id, fromLua).matches(actual), "Lua-table filter differs from JSON filter");
		var noName = LuaItemStackCodec.filter(registries, id, LuaValue.valueOf("{\"!minecraft:custom_name\":{}}"));
		require(noName.matches(actual), "Absent-component filter rejected");
		actual.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("renamed"));
		require(!noName.matches(actual) && marker.matches(actual), "Component absence / unspecified name filtering");
		var defaultSize = LuaItemStackCodec.filter(registries, id, LuaValue.valueOf("{\"minecraft:max_stack_size\":64}"));
		actual.set(DataComponents.MAX_STACK_SIZE, 32);
		require(!defaultSize.matches(actual), "Explicit default-valued constraint was lost");
		actual.set(DataComponents.MAX_STACK_SIZE, 64);
		List<ItemStack> slots = new ArrayList<>(List.of(actual.copy(), ItemStack.EMPTY, ItemStack.EMPTY));
		var cost = new LuaInventoryTrade.Request(new ItemStack(Items.KNOWLEDGE_BOOK), 3, marker);
		var reward = new LuaInventoryTrade.Request(new ItemStack(Items.PAPER), 1, false);
		require(LuaInventoryTrade.apply(slots, List.of(cost), List.of(reward)) == null && slots.getFirst().getCount() == 5, "Trade cost still requires complete components");
		// Different components must never be merged just because filters now support partial matching.
		var ordinary = new ItemStack(Items.KNOWLEDGE_BOOK, 3);
		slots = new ArrayList<>(List.of(actual.copy(), ItemStack.EMPTY, ItemStack.EMPTY));
		require(LuaInventoryTrade.apply(slots, List.of(), List.of(new LuaInventoryTrade.Request(ordinary, 3, false))) == null
				&& slots.get(0).getCount() == 8 && slots.get(1).getCount() == 3 && !slots.get(1).has(DataComponents.CUSTOM_DATA), "Rewards incorrectly merged across components");
		String saved = LuaItemStackCodec.normalizeComponents(registries, fromLua).get("componentsJson").tojstring();
		require(saved.contains("fire_charge") && !saved.contains("item_model"), "Compiling filter mutated caller input");
		System.out.println("Item filter smoke passed: " + checks + " checks; supplied components only, nested NBT subset, Lua/JSON, defaults, removals, ID/type safety, trade costs and exact reward merging");
	}
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
