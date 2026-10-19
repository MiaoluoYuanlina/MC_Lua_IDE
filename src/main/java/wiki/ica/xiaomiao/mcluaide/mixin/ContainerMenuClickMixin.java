package wiki.ica.xiaomiao.mcluaide.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import wiki.ica.xiaomiao.mcluaide.server.LuaContainerEvents;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ContainerMenuClickMixin {
    @WrapOperation(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V"))
    private void mcLuaIde$observe(AbstractContainerMenu menu, int slot, int button, ContainerInput input, Player player, Operation<Void> original) {
        var capture = LuaContainerEvents.begin(menu, player, slot, button, input);
        original.call(menu, slot, button, input, player);
        if (capture != null) capture.finish();
    }
}
