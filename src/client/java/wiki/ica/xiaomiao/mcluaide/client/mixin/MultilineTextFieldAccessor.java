package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultilineTextField.class)
public interface MultilineTextFieldAccessor {
	@Accessor("cursor")
	int mcLuaIde$getCursor();

	@Accessor("selectCursor")
	int mcLuaIde$getSelectCursor();

	@Mutable
	@Accessor("width")
	void mcLuaIde$setWidth(int width);

	@Accessor("width")
	int mcLuaIde$getWidth();

	@Invoker("reflowDisplayLines")
	void mcLuaIde$reflowDisplayLines();
}
