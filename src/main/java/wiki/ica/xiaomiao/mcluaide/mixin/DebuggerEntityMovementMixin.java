package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.server.LuaDebuggerGamePause;

/** Anchor bodies and occupied vehicles, including gravity/pushes, without skipping player ticks. */
@Mixin(Entity.class)
abstract class DebuggerEntityMovementMixin {
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void debuggerPhysics(MoverType type, Vec3 movement, CallbackInfo ci) {
		if (!LuaDebuggerGamePause.movementControlsActive()) return;
		Entity entity = (Entity)(Object)this;
		if (entity.level().isClientSide()) return;
		boolean locked = entity instanceof ServerPlayer player && LuaDebuggerGamePause.movementLocked(player);
		if (!locked && entity.isVehicle()) for (Entity passenger : entity.getIndirectPassengers()) {
			if (passenger instanceof ServerPlayer player && LuaDebuggerGamePause.movementLocked(player)) { locked = true; break; }
		}
		if (locked) { entity.setDeltaMovement(Vec3.ZERO); ci.cancel(); }
	}
}
