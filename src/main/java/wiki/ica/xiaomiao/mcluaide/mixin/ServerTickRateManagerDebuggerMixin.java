package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.server.LuaDebuggerGamePause;

@Mixin(ServerTickRateManager.class)
abstract class ServerTickRateManagerDebuggerMixin {
	@Inject(method = "setFrozen", at = @At("HEAD"))
	private void mcLuaIde$freezeOwnership(boolean frozen, CallbackInfo callback) {
		LuaDebuggerGamePause.externalFreezeChange((ServerTickRateManager) (Object) this);
	}
}
