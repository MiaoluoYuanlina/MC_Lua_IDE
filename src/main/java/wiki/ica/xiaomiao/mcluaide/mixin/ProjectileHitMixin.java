package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.server.LuaProjectileEvents;

/** Every projectile reports its hits through Projectile.onHit, whether it struck a block or an entity. */
@Mixin(Projectile.class)
abstract class ProjectileHitMixin {
	@Inject(method = "onHit", at = @At("HEAD"))
	private void mcLuaIde$projectileHit(HitResult hit, CallbackInfo callback) {
		LuaProjectileEvents.hit((Projectile) (Object) this, hit);
	}
}
