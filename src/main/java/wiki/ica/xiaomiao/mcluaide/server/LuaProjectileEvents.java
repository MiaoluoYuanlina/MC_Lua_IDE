package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * projectileHitBlock / projectileHitEntity: raised when any projectile (arrow, trident, snowball, fireball, wind charge,
 * modded ones) reports a hit, before the projectile reacts to it. Called from {@code ProjectileHitMixin}.
 */
public final class LuaProjectileEvents {
	private LuaProjectileEvents() { }

	public static void hit(Projectile projectile, HitResult hit) {
		if (!(projectile.level() instanceof ServerLevel level) || hit == null) return;
		if (hit.getType() != HitResult.Type.BLOCK && hit.getType() != HitResult.Type.ENTITY) return;
		Map<String, Object> data = describe(projectile, level);
		data.put("hitX", hit.getLocation().x);
		data.put("hitY", hit.getLocation().y);
		data.put("hitZ", hit.getLocation().z);
		if (hit instanceof BlockHitResult block) {
			var pos = block.getBlockPos();
			data.put("blockId", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
			data.put("x", pos.getX());
			data.put("y", pos.getY());
			data.put("z", pos.getZ());
			data.put("face", block.getDirection().getName());
			data.put("inside", block.isInside());
			ServerScriptManager.emitEvent("projectileHitBlock", data);
		} else if (hit instanceof EntityHitResult entityHit) {
			Entity target = entityHit.getEntity();
			put(data, "target", target);
			if (target instanceof ServerPlayer player) {
				data.put("targetIsPlayer", true);
				data.put("targetPlayerName", player.getName().getString());
			} else data.put("targetIsPlayer", false);
			ServerScriptManager.emitEvent("projectileHitEntity", data);
		}
	}

	private static Map<String, Object> describe(Projectile projectile, ServerLevel level) {
		Map<String, Object> data = new LinkedHashMap<>();
		put(data, "projectile", projectile);
		data.put("dimension", level.dimension().identifier().toString());
		Entity owner = projectile.getOwner();
		data.put("hasOwner", owner != null);
		if (owner != null) {
			put(data, "owner", owner);
			if (owner instanceof ServerPlayer player) {
				data.put("playerUuid", player.getUUID().toString());
				data.put("playerName", player.getName().getString());
			}
		}
		return data;
	}

	private static void put(Map<String, Object> data, String prefix, Entity entity) {
		data.put(prefix + "Uuid", entity.getStringUUID());
		data.put(prefix + "Type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		data.put(prefix + "Name", entity.getName().getString());
		data.put(prefix + "RuntimeId", entity.getId());
		data.put(prefix + "X", entity.getX());
		data.put(prefix + "Y", entity.getY());
		data.put(prefix + "Z", entity.getZ());
	}
}
