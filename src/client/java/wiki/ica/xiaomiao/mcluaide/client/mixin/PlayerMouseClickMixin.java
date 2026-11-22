package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.MouseClickReporter;

@Mixin(MouseHandler.class)
abstract class PlayerMouseClickMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void mcLuaIdeMouseClick(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		MouseClickReporter.onButton(window, button.button(), action);
	}
}
