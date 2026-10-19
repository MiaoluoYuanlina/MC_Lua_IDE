package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.server.LuaVirtualChestMenu;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ReadOnlyChestSyncMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handleContainerClick", at = @At("RETURN"))
    private void mcLuaIde$resync(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player.containerMenu instanceof LuaVirtualChestMenu menu && menu.readOnly && menu.containerId == packet.containerId())
            menu.broadcastFullState(); // Override vanilla clients' locally predicted pickup/drag/shift actions.
    }
}
