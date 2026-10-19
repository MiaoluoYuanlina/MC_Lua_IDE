package wiki.ica.xiaomiao.mcluaide.client.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import wiki.ica.xiaomiao.mcluaide.client.LuaWindowShortcutCloseGuard;

@Mixin(Window.class)
public abstract class WindowShortcutCloseMixin {
	@ModifyVariable(method = "setWindowCloseCallback", at = @At("HEAD"), argsOnly = true)
	private Runnable mcLuaIde$preserveAltF4(Runnable callback) {
		return () -> { if (!LuaWindowShortcutCloseGuard.intercept()) callback.run(); };
	}
}
