package wiki.ica.xiaomiao.mcluaide.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import wiki.ica.xiaomiao.mcluaide.server.LuaBlockBreakCapture;
import java.util.Map;

/** Axiom special/creative breaking applies its own changes, bypassing vanilla and Fabric AFTER.
 * Optional string target: Axiom is not a dependency, and its permission/cancellation handling is untouched. */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBlock", remap = false)
abstract class AxiomBlockBreakMixin {
    @Shadow @Final private boolean breaking;
    @Shadow @Final private BlockHitResult blockHit;
    @Shadow @Final private Map<BlockPos, BlockState> blocks;

    @WrapMethod(method = "handle")
    private void mcLuaIde$observeAxiomBreak(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        if (!breaking || blockHit == null || !blocks.containsKey(blockHit.getBlockPos())) {
            original.call(server, player);
            return;
        }
        // Observe the clicked block, not all neighbour/symmetry changes: a two-part bed is one action.
        try (var attempt = LuaBlockBreakCapture.begin(player.level(), player, blockHit.getBlockPos())) {
            original.call(server, player);
            // A rejected action leaves the target unchanged. Exceptions do not emit a success event.
            // Existing nested vanilla/Fabric notifications mark this scope, preventing duplicates.
            attempt.finish(true);
        }
    }
}
