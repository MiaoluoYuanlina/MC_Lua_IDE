package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.LuaDebuggerCamera;

@Mixin(KeyboardInput.class)
abstract class DebuggerInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void debuggerInput(CallbackInfo ci) {
		if (LuaDebuggerCamera.suppressPlayerMovement()) { keyPresses = Input.EMPTY; moveVector = Vec2.ZERO; }
	}
}
