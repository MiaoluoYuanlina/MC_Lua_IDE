package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wiki.ica.xiaomiao.mcluaide.event.LuaGameEvents;

/** Emits a server event only after BlockItem has successfully changed the world. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
	@Inject(method = "placeBlock", at = @At("RETURN"))
	private void mcLuaIde$afterBlockPlaced(BlockPlaceContext context, BlockState requestedState,
			CallbackInfoReturnable<Boolean> callback) {
		if (!callback.getReturnValueZ() || !(context.getLevel() instanceof ServerLevel level)
				|| !(context.getPlayer() instanceof ServerPlayer player)) {
			return;
		}
		BlockPos pos = context.getClickedPos().immutable();
		LuaGameEvents.BLOCK_PLACED.invoker().onBlockPlaced(
				player, level, pos, level.getBlockState(pos), context.getHand());
	}
}
