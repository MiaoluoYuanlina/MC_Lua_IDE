package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.LuaDebuggerCamera;

@Mixin(GameRenderer.class)
abstract class DebuggerCameraHandMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void debuggerCameraHand(CallbackInfo ci) { if (LuaDebuggerCamera.active()) ci.cancel(); }
}
