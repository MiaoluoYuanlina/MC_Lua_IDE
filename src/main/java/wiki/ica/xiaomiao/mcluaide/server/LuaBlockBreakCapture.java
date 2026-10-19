package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Supplements Lua events only; never changes vanilla removal, drops, permissions or Fabric events. */
public final class LuaBlockBreakCapture {
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private LuaBlockBreakCapture() { }

    public static Attempt begin(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Attempt attempt = new Attempt(level, player, pos.immutable(), CURRENT.get());
        CURRENT.set(attempt);
        return attempt;
    }

    /** The normal Fabric path still delivers the event at its original point, once per attempt. */
    public static boolean markFabricEvent(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || attempt.level != level || attempt.player != player || !attempt.pos.equals(pos)) return true;
        if (attempt.emitted) return false;
        attempt.markEmitted();
        return true;
    }

    public static final class Attempt implements AutoCloseable {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final BlockPos pos;
        private final BlockState originalState;
        private final boolean hadBlockEntity;
        private final Attempt parent;
        private boolean emitted;

        private Attempt(ServerLevel level, ServerPlayer player, BlockPos pos, Attempt parent) {
            this.level = level; this.player = player; this.pos = pos; this.parent = parent;
            originalState = level.getBlockState(pos);
            hadBlockEntity = level.getBlockEntity(pos) != null;
        }

        public void finish(boolean succeeded) {
            // Bed FOOT in creative removes HEAD first; its neighbour update already removes FOOT.
            // The later removeBlock returns false, so Fabric's Block.destroy hook is never reached.
            // Only compensate an accepted action whose original target really disappeared/replaced.
            if (succeeded && !emitted && !originalState.isAir() && !level.getBlockState(pos).is(originalState.getBlock())) {
                markEmitted();
                LuaFabricEventBridge.blockBroken(level, player, pos, originalState, hadBlockEntity);
            }
        }

        private void markEmitted() {
            // A BEFORE listener may synchronously break the same target itself. Its genuine event
            // also satisfies the outer attempt; do not compensate that same removal a second time.
            for (Attempt attempt = this; attempt != null; attempt = attempt.parent)
                if (attempt.level == level && attempt.player == player && attempt.pos.equals(pos)) attempt.emitted = true;
        }

        @Override public void close() {
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }
}
