package wiki.ica.xiaomiao.mcluaide.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.components.MultilineTextField$StringView")
public interface StringViewAccessor {
	@Accessor("beginIndex")
	int mcLuaIde$getBeginIndex();

	@Accessor("endIndex")
	int mcLuaIde$getEndIndex();
}
