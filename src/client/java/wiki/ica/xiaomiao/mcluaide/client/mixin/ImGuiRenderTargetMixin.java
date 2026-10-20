package wiki.ica.xiaomiao.mcluaide.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wiki.ica.xiaomiao.mcluaide.client.ImGuiMcSurface;

@Mixin(Minecraft.class)
abstract class ImGuiRenderTargetMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void mcLuaIde$toolTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = ImGuiMcSurface.activeTarget();
        if (target != null) cir.setReturnValue(target);
    }
}
