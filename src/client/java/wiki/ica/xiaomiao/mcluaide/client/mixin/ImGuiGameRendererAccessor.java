package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface ImGuiGameRendererAccessor {
    @Accessor("guiRenderer") GuiRenderer mcLuaIde$guiRenderer();
    @Accessor("fogRenderer") FogRenderer mcLuaIde$fogRenderer();
}
