package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the player's vanilla cooldown groups, not a parallel script-local timer. */
final class LuaItemCooldowns {
	static final int MAX_TICKS = 1_728_000;

	enum Action { QUERY, START, SET, CLEAR }

	private LuaItemCooldowns() {}

	static Integer duration(LuaValue value, boolean optional) {
		if (optional && value.isnil()) return null;
		if (value.type() != LuaValue.TNUMBER) throw new LuaError("冷却时间 ticks 必须是整数（20 Tick = 1 秒）");
		double ticks = value.checkdouble();
		if (!Double.isFinite(ticks) || ticks != Math.rint(ticks) || ticks < 0 || ticks > MAX_TICKS)
			throw new LuaError("冷却时间 ticks 必须是 0 到 " + MAX_TICKS + " 的整数（20 Tick = 1 秒）");
		return (int) ticks;
	}

	static boolean isOnCooldown(Container inventory, ItemCooldowns cooldowns, LuaItemStackCodec.ItemFilter filter) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (filter.matches(stack) && cooldowns.isOnCooldown(stack)) return true;
		}
		return false;
	}

	static int change(Container inventory, ItemCooldowns cooldowns, LuaItemStackCodec.ItemFilter filter,
			Integer ticks, boolean clear) {
		Map<Identifier, Integer> groups = new LinkedHashMap<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!filter.matches(stack)) continue;
			var useCooldown = stack.get(DataComponents.USE_COOLDOWN);
			int duration = clear ? 0 : ticks != null ? ticks : useCooldown == null ? 20 : useCooldown.ticks();
			if (duration < 0 || duration > MAX_TICKS)
				throw new LuaError("物品 use_cooldown 的冷却时间超出范围，请显式指定 0 到 " + MAX_TICKS + " Tick");
			// Several stacks may share a group. Apply it once; default duration uses the longest one.
			groups.merge(cooldowns.getCooldownGroup(stack), duration, Math::max);
		}
		groups.forEach((group, duration) -> {
			if (duration == 0) cooldowns.removeCooldown(group);
			else cooldowns.addCooldown(group, duration);
		});
		return groups.size();
	}
}
