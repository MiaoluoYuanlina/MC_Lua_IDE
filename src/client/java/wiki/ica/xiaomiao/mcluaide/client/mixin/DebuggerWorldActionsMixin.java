package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;
import wiki.ica.xiaomiao.mcluaide.client.LuaDebuggerCamera;

@Mixin(Minecraft.class)
abstract class DebuggerWorldActionsMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void debuggerKeys(CallbackInfo ci) { LuaDebuggerCamera.clearWorldActions((Minecraft)(Object)this); }
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void debuggerAttack(CallbackInfoReturnable<Boolean> ci) { if (LuaDebuggerCamera.active()) ci.setReturnValue(false); }
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void debuggerContinueAttack(boolean held, CallbackInfo ci) { if (LuaDebuggerCamera.active()) ci.cancel(); }
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void debuggerUse(CallbackInfo ci) { if (LuaDebuggerCamera.active()) ci.cancel(); }
}
