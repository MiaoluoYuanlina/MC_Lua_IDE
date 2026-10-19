package wiki.ica.xiaomiao.mcluaide.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import wiki.ica.xiaomiao.mcluaide.server.LuaCommandBlockContext;

@Mixin(BaseCommandBlock.class)
abstract class LuaCommandBlockMixin {
	@WrapMethod(method = "performCommand")
	private boolean mcLuaIde$commandOrigin(ServerLevel level, Operation<Boolean> original) {
		return LuaCommandBlockContext.perform((BaseCommandBlock) (Object) this, level, () -> original.call(level));
	}
}
