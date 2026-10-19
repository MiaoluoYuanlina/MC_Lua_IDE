package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseCooldown;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class LuaItemCooldownSmoke {
	private static int checks;

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		for (var item : List.of(Items.KNOWLEDGE_BOOK, Items.PAPER, Items.ENDER_PEARL))
			item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var id = LuaValue.valueOf("minecraft:knowledge_book");
		var any = LuaItemStackCodec.filter(registries, id, LuaValue.NIL);
		var fire = LuaItemStackCodec.filter(registries, id,
				LuaValue.valueOf("{\"minecraft:custom_data\":{\"new_type\":\"fire_charge\"}}"));
		var missing = LuaItemStackCodec.filter(registries, id,
				LuaValue.valueOf("{\"minecraft:custom_data\":{\"new_type\":\"missing\"}}"));
		SimpleContainer inventory = new SimpleContainer(41);
		RecordingCooldowns cooldowns = new RecordingCooldowns();
		check(!LuaItemCooldowns.isOnCooldown(inventory, cooldowns, any), "empty inventory query");
		check(LuaItemCooldowns.change(inventory, cooldowns, any, 100, false) == 0, "empty inventory start");
		check(cooldowns.started.isEmpty(), "missing item sends no cooldown");
		ItemStack first = LuaItemStackCodec.create(registries, id, LuaValue.valueOf(1), LuaValue.valueOf("""
				{"minecraft:custom_data":{"new_type":"fire_charge","extra":3},
				 "minecraft:item_name":"火焰弹","minecraft:item_model":"minecraft:fire_charge",
				 "minecraft:lore":["右键发射"]}
				"""), 64);
		inventory.setItem(0, first);
		inventory.setItem(40, first.copy());
		inventory.setItem(1, new ItemStack(Items.PAPER));
		var original = first.copy();
		check(LuaItemCooldowns.change(inventory, cooldowns, fire, 100, false) == 1, "partial components and unique group");
		check(cooldowns.started.size() == 1 && cooldowns.lastTicks == 100, "one start notification");
		check(LuaItemCooldowns.isOnCooldown(inventory, cooldowns, fire), "matching item cooling");
		check(!LuaItemCooldowns.isOnCooldown(inventory, cooldowns, missing), "filter does not query nonmatching stacks");
		check(!cooldowns.isOnCooldown(inventory.getItem(1)), "other item group unchanged");
		tick(cooldowns, 30);
		check(Math.abs(cooldowns.getCooldownPercent(first, 0) - 0.7f) < .0001f, "vanilla tick countdown");
		LuaItemCooldowns.change(inventory, cooldowns, fire, 10, false);
		tick(cooldowns, 9);
		check(cooldowns.isOnCooldown(first), "set resets remaining duration");
		tick(cooldowns, 1);
		check(!cooldowns.isOnCooldown(first), "natural completion exact tick");
		check(!cooldowns.ended.isEmpty(), "natural completion notifies");
		LuaItemCooldowns.change(inventory, cooldowns, any, null, false);
		check(cooldowns.lastTicks == 20, "no component default 20 ticks");
		check(LuaItemCooldowns.change(inventory, cooldowns, any, null, true) == 1, "clear group count");
		check(!cooldowns.isOnCooldown(first), "clear immediate");
		LuaItemCooldowns.change(inventory, cooldowns, any, 100, false);
		LuaItemCooldowns.change(inventory, cooldowns, any, 0, false);
		check(!cooldowns.isOnCooldown(first), "zero ticks clears instead of zero interval");
		check(ItemStack.isSameItemSameComponents(first, original) && first.getCount() == original.getCount(), "never alters components or count");

		Identifier group = Identifier.parse("test:shared_spell");
		first.set(DataComponents.USE_COOLDOWN, new UseCooldown(2, Optional.of(group)));
		inventory.getItem(40).set(DataComponents.USE_COOLDOWN, new UseCooldown(4, Optional.of(group)));
		inventory.getItem(1).set(DataComponents.USE_COOLDOWN, new UseCooldown(1, Optional.of(group)));
		check(LuaItemCooldowns.change(inventory, cooldowns, fire, null, false) == 1 && cooldowns.lastTicks == 80,
				"use_cooldown duration uses max in shared group");
		check(cooldowns.started.getLast().equals(group), "native component group");
		check(cooldowns.isOnCooldown(inventory.getItem(1)), "shared group affects other item types");
		check(LuaItemCooldowns.change(inventory, cooldowns, missing, null, true) == 0 && cooldowns.isOnCooldown(first), "missing match does not clear");
		LuaItemCooldowns.change(inventory, cooldowns, fire, null, true);
		check(!cooldowns.isOnCooldown(inventory.getItem(1)), "clearing group also restores shared item");
		inventory.getItem(40).set(DataComponents.USE_COOLDOWN, new UseCooldown(3, Optional.of(Identifier.parse("test:second"))));
		check(LuaItemCooldowns.change(inventory, cooldowns, fire, 40, false) == 2, "same item ID distinct groups both updated");
		inventory.setItem(0, ItemStack.EMPTY);
		check(LuaItemCooldowns.isOnCooldown(inventory, cooldowns, fire), "offhand slot included");
		check(LuaItemCooldowns.change(inventory, cooldowns, any, null, true) == 1, "only currently matching groups cleared");
		check(!LuaItemCooldowns.isOnCooldown(inventory, cooldowns, fire), "offhand cooldown cleared");
		check(cooldowns.isOnCooldown(inventory.getItem(1)), "nonmatching group's lingering cooldown preserved");

		check(LuaItemCooldowns.duration(LuaValue.NIL, true) == null, "optional duration");
		check(LuaItemCooldowns.duration(LuaValue.valueOf(0), false) == 0, "zero accepted");
		check(LuaItemCooldowns.duration(LuaValue.valueOf(LuaItemCooldowns.MAX_TICKS), false) == LuaItemCooldowns.MAX_TICKS, "duration boundary");
		for (LuaValue invalid : List.of(LuaValue.NIL, LuaValue.TRUE, LuaValue.valueOf("20"), LuaValue.valueOf(-1),
				LuaValue.valueOf(1.5), LuaValue.valueOf(Double.NaN), LuaValue.valueOf(Double.POSITIVE_INFINITY),
				LuaValue.valueOf((long) LuaItemCooldowns.MAX_TICKS + 1))) {
			try { LuaItemCooldowns.duration(invalid, false); throw new AssertionError("accepted bad duration: " + invalid); }
			catch (LuaError error) { check(error.getMessage().contains("冷却时间"), "Chinese duration validation"); }
		}
		cooldowns.started.clear();
		inventory.getItem(40).set(DataComponents.USE_COOLDOWN, new UseCooldown(Float.MAX_VALUE));
		try { LuaItemCooldowns.change(inventory, cooldowns, fire, null, false); throw new AssertionError("accepted excessive default"); }
		catch (LuaError expected) { check(cooldowns.started.isEmpty(), "validate default before mutation"); }
		System.out.println("Item cooldown smoke passed: " + checks + " checks");
	}

	private static void tick(ItemCooldowns cooldowns, int count) { for (int i = 0; i < count; i++) cooldowns.tick(); }
	private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
	private static final class RecordingCooldowns extends ItemCooldowns {
		final List<Identifier> started = new ArrayList<>();
		final List<Identifier> ended = new ArrayList<>();
		int lastTicks;
		@Override protected void onCooldownStarted(Identifier id, int ticks) { started.add(id); lastTicks = ticks; }
		@Override protected void onCooldownEnded(Identifier id) { ended.add(id); }
	}
}
