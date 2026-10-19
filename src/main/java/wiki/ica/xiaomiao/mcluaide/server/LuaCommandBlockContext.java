package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Provenance from actual vanilla block/minecart execution, never inferred from an entity or position. */
public final class LuaCommandBlockContext {
	private static final ThreadLocal<Origin> CURRENT = new ThreadLocal<>();
	private LuaCommandBlockContext() { }

	public static boolean perform(BaseCommandBlock block, ServerLevel level, BooleanSupplier original) {
		Origin previous = CURRENT.get();
		CURRENT.set(new Origin(block, level));
		try { return original.getAsBoolean(); }
		finally {
			if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
		}
	}

	static Origin current(CommandSourceStack source) {
		Origin origin = CURRENT.get();
		return origin != null && origin.level.getServer() == source.getServer() && origin.block.isValid()
				? origin : null;
	}

	record Origin(BaseCommandBlock block, ServerLevel level) {
		UUID owner() {
			CommandSourceStack source = block.createCommandSourceStack(level, CommandSource.NULL);
			String identity = source.getEntity() == null ? source.getPosition().toString()
					: source.getEntity().getUUID().toString();
			return UUID.nameUUIDFromBytes(("mc_lua_ide:command_block:" + level.dimension().identifier() + ":" + identity)
					.getBytes(StandardCharsets.UTF_8));
		}

		void output(Component message) {
			// Vanilla's CloseableCommandBlockSource is closed before asynchronous Lua prints arrive.
			if (!block.isValid() || !block.isTrackOutput()) return;
			block.setLastOutput(message);
			block.onUpdated(level);
		}
	}
}
