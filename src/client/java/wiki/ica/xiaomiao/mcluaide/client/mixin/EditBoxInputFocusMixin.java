package wiki.ica.xiaomiao.mcluaide.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import wiki.ica.xiaomiao.mcluaide.client.LuaIdeScreen;

/** Invisible tool fields must not repeatedly stop the active editor's IME session. */
@Mixin(EditBox.class)
public abstract class EditBoxInputFocusMixin {
	@WrapMethod(method = "setFocused")
	private void mcLuaIde$onlyNotifyFocusChanges(boolean focused, Operation<Void> original) {
		if (Minecraft.getInstance().screen instanceof LuaIdeScreen
				&& ((EditBox) (Object) this).isFocused() == focused) return;
		original.call(focused);
	}
}
