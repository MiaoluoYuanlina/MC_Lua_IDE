package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.luaj.vm2.LuaError;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets chosen mobs actively pick fights with everything that is not on their team: players who are not teammates and living
 * entities that belong to a different team. A mob without a team attacks players only. The set lives in memory, so it is cleared
 * when the server stops.
 */
final class LuaTeamAggression {
	private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
	private static final int INTERVAL_TICKS = 10;

	private LuaTeamAggression() { }

	static boolean enabled(UUID uuid) { return ACTIVE.contains(uuid); }

	static void set(Entity entity, boolean enabled) {
		if (!(entity instanceof Mob)) throw new LuaError("只有生物可以主动攻击其他队伍或玩家");
		if (enabled) ACTIVE.add(entity.getUUID()); else ACTIVE.remove(entity.getUUID());
	}

	static void clear() { ACTIVE.clear(); }

	static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty() || server.getTickCount() % INTERVAL_TICKS != 0) return;
		for (Iterator<UUID> it = ACTIVE.iterator(); it.hasNext(); ) {
			UUID uuid = it.next();
			Mob mob = find(server, uuid);
			if (mob == null) { if (!loaded(server, uuid)) it.remove(); continue; }
			if (!mob.isAlive()) { it.remove(); continue; }
			LivingEntity current = mob.getTarget();
			if (current != null && current.isAlive() && hostile(mob, current)) continue;
			mob.setTarget(nearestEnemy(mob));
		}
	}

	private static Mob find(MinecraftServer server, UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) if (level.getEntityInAnyDimension(uuid) instanceof Mob mob) return mob;
		return null;
	}

	/** An entity that is merely unloaded keeps its setting; one that is gone for good is dropped. */
	private static boolean loaded(MinecraftServer server, UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) if (level.getEntityInAnyDimension(uuid) != null) return true;
		return false;
	}

	private static LivingEntity nearestEnemy(Mob mob) {
		double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
		return mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(range), other -> hostile(mob, other)).stream()
				.filter(mob::hasLineOfSight)
				.min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
	}

	/** True when the mob should fight {@code other}: a non-teammate player, or a living entity on another team. */
	static boolean hostile(Mob mob, LivingEntity other) {
		if (other == mob || !other.isAlive() || other.isSpectator() || !mob.canAttack(other)) return false;
		if (other instanceof Player player && player.isCreative()) return false;
		if (mob.isAlliedTo(other)) return false;
		return other instanceof Player || other.getTeam() != null;
	}
}
