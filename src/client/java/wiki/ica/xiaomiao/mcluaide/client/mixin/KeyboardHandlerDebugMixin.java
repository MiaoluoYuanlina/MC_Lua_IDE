package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.GameControl;
import wiki.ica.xiaomiao.mcluaide.client.LuaDebugKeyBindings;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerDebugMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void mcLuaIde$debugKeys(long window, int action, KeyEvent event, CallbackInfo callback) {
		if (GameControl.onKey(window, action, event) || LuaDebugKeyBindings.onGameKey(window, action, event)) callback.cancel();
	}
}
