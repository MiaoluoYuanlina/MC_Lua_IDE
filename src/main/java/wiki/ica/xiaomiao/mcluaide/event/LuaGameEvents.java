package wiki.ica.xiaomiao.mcluaide.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

/** Server-side game events exposed to the Lua runtime. */
public final class LuaGameEvents {
	public static final Event<BlockPlaced> BLOCK_PLACED = EventFactory.createArrayBacked(
			BlockPlaced.class,
			listeners -> (player, level, pos, state, hand) -> {
				for (BlockPlaced listener : listeners) {
					listener.onBlockPlaced(player, level, pos, state, hand);
				}
			});
	public static final Event<CommandExecuted> COMMAND_EXECUTED = EventFactory.createArrayBacked(
			CommandExecuted.class,
			listeners -> (source, command) -> {
				for (CommandExecuted listener : listeners) listener.onCommand(source, command);
			});

	private LuaGameEvents() {
	}

	@FunctionalInterface
	public interface BlockPlaced {
		void onBlockPlaced(ServerPlayer player, ServerLevel level, BlockPos pos,
				BlockState state, InteractionHand hand);
	}

	@FunctionalInterface
	public interface CommandExecuted {
		void onCommand(CommandSourceStack source, String command);
	}
}
