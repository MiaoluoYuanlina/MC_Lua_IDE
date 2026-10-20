package wiki.ica.xiaomiao.mcluaide.client.mixin;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.ImGuiIdeRuntime;

@Mixin(RenderSystem.class)
abstract class ImGuiPresentMixin {
    @Inject(method = "flipFrame", at = @At("HEAD"))
    private static void mcLuaIde$renderIndependentUi(TracyFrameCapture capture, CallbackInfo ci) {
        ImGuiIdeRuntime.renderFrame();
    }
}
