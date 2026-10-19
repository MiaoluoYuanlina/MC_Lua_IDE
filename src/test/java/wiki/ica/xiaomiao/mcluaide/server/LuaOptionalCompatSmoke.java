package wiki.ica.xiaomiao.mcluaide.server;

import wiki.ica.xiaomiao.mcluaide.mixin.LuaOptionalCompatMixins;

/** Axiom is intentionally not on this JVM's mod path. Never load its optional target when absent. */
public final class LuaOptionalCompatSmoke {
    public static void main(String[] args) {
        var plugin = new LuaOptionalCompatMixins();
        if (!plugin.getMixins().isEmpty()) throw new AssertionError("Axiom absent but optional target was registered");
        if (!plugin.shouldApplyMixin("net.minecraft.server.level.ServerPlayerGameMode", "ServerPlayerBlockBreakMixin"))
            throw new AssertionError("Optional compat disabled a normal server mixin");
        System.out.println("Optional block-break compatibility smoke passed: Axiom absent, no optional targets; vanilla mixins retained");
    }
}
