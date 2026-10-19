package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.LuaFunctionFolding;

@Mixin(MultilineTextField.class)
public abstract class MultilineTextFieldFoldingMixin {
    @Inject(method="reflowDisplayLines",at=@At("RETURN"))
    private void mcLuaIde$foldRows(CallbackInfo ci){LuaFunctionFolding.filter((MultilineTextField)(Object)this);}
    @Inject(method="seekCursor",at=@At(value="INVOKE",target="Ljava/lang/Runnable;run()V"))
    private void mcLuaIde$revealCursor(Whence whence,int offset,CallbackInfo ci){LuaFunctionFolding.revealCursor((MultilineTextField)(Object)this);}
    @Inject(method="insertText",at=@At("HEAD"))
    private void mcLuaIde$unfoldInsert(String text,CallbackInfo ci){LuaFunctionFolding.beforeEdit((MultilineTextField)(Object)this);}
    @Inject(method="deleteText",at=@At("HEAD"))
    private void mcLuaIde$unfoldDelete(int amount,CallbackInfo ci){LuaFunctionFolding.beforeEdit((MultilineTextField)(Object)this);}
}
