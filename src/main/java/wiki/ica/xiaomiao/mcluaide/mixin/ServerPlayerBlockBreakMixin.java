package wiki.ica.xiaomiao.mcluaide.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import wiki.ica.xiaomiao.mcluaide.server.LuaBlockBreakCapture;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerBlockBreakMixin {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean mcLuaIde$observeCompletedBreak(BlockPos pos, Operation<Boolean> original) {
        try (var attempt = LuaBlockBreakCapture.begin(level, player, pos)) {
            boolean succeeded = original.call(pos);
            attempt.finish(succeeded);
            return succeeded;
        }
    }
}
