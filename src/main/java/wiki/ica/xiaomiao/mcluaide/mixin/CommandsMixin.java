package wiki.ica.xiaomiao.mcluaide.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.event.LuaGameEvents;

/** Observes every command submitted to the authoritative server command manager. */
@Mixin(Commands.class)
abstract class CommandsMixin {
	@Inject(method = "performPrefixedCommand", at = @At("HEAD"))
	private void mcLuaIde$beforeCommand(CommandSourceStack source, String command, CallbackInfo callback) {
		LuaGameEvents.COMMAND_EXECUTED.invoker().onCommand(source, command);
	}
}
