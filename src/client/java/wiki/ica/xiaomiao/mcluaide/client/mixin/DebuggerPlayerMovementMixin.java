package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.LuaDebuggerCamera;

@Mixin(LocalPlayer.class)
abstract class DebuggerPlayerMovementMixin {
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void debuggerMove(MoverType type, Vec3 movement, CallbackInfo ci) {
		if (LuaDebuggerCamera.suppressPlayerMovement()) {
			((LocalPlayer)(Object)this).setDeltaMovement(Vec3.ZERO); ci.cancel();
		}
	}
}
