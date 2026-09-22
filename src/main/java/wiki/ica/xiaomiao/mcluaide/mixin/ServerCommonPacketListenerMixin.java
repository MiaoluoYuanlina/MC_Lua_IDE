package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.server.MinecraftLuaUiApi;

/** Routes vanilla dialog custom actions back to the active server-side Lua execution. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerMixin {
	@Inject(method = "handleCustomClickAction", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"))
	private void mcLuaIde$handleDialogAction(ServerboundCustomClickActionPacket packet, CallbackInfo callback) {
		if ((Object) this instanceof ServerGamePacketListenerImpl listener) {
			MinecraftLuaUiApi.handleCustomClick(listener.player, packet);
		}
	}
}
