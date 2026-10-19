package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.LuaDebuggerCamera;

@Mixin(MouseHandler.class)
abstract class DebuggerMouseMixin {
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void debuggerTurn(LocalPlayer player, double x, double y) { LuaDebuggerCamera.turn(player, x, y); }
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void debuggerScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (LuaDebuggerCamera.active() && Minecraft.getInstance().screen == null) ci.cancel();
	}
}
