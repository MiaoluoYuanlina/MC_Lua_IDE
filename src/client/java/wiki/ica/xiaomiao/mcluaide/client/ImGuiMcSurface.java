package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.lwjgl.opengl.GL30;
import wiki.ica.xiaomiao.mcluaide.client.mixin.ImGuiGameRendererAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.ImGuiGuiRendererAccessor;
import java.util.function.Consumer;

/** Render existing game-aware tools (items, skins, 3D previews) into an ImGui-owned viewport. */
public final class ImGuiMcSurface implements AutoCloseable {
    private static RenderTarget activeTarget;
    private TextureTarget target;
    private final GuiRenderState state = new GuiRenderState();
    public static RenderTarget activeTarget() { return activeTarget; }

    long render(int logicalWidth, int logicalHeight, float density, int mouseX, int mouseY, Consumer<GuiGraphicsExtractor> extract) {
        var client = Minecraft.getInstance();
        var windowState = client.gameRenderer.getGameRenderState().windowRenderState;
        int originalWidth = windowState.width, originalHeight = windowState.height;
        // Rasterize at the displayed size, not at the virtual canvas size. At 10%
        // interface zoom a large virtual editor must not allocate a 20k texture.
        int w = Math.max(1, (int)Math.ceil(logicalWidth * density)), h = Math.max(1, (int)Math.ceil(logicalHeight * density));
        if (target == null || target.width != w || target.height != h) {
            close(); target = new TextureTarget("MC Lua IDE tool viewport", w, h, true);
        }
        var game = (ImGuiGameRendererAccessor) client.gameRenderer;
        var renderer = game.mcLuaIde$guiRenderer(); var access = (ImGuiGuiRendererAccessor)renderer;
        var previousState = access.mcLuaIde$state();
        int draw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        var previousTarget = activeTarget;
        RenderSystem.backupProjectionMatrix();
        try {
            windowState.width = w; windowState.height = h;
            activeTarget = target; access.mcLuaIde$state(state); state.reset();
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0xff111925, target.getDepthTexture(), 1);
            var graphics = new GuiGraphicsExtractor(client, state, mouseX, mouseY);
            graphics.pose().scale(density / windowState.guiScale, density / windowState.guiScale);
            extract.accept(graphics); graphics.extractDeferredElements(mouseX, mouseY, 0);
            renderer.render(game.mcLuaIde$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
            return ((GlTexture)target.getColorTexture()).glId();
        } finally {
            windowState.width = originalWidth; windowState.height = originalHeight;
            try { renderer.endFrame(); }
            finally {
                access.mcLuaIde$state(previousState); activeTarget = previousTarget;
                state.reset(); RenderSystem.restoreProjectionMatrix();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            }
        }
    }
    int width() { return target == null ? 1 : target.width; }
    int height() { return target == null ? 1 : target.height; }
    @Override public void close() { if (target != null) { target.destroyBuffers(); target = null; } }
}
