package wiki.ica.xiaomiao.mcluaide.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Register optional targets only when their mod is present; avoids missing-class warnings at startup. */
public final class LuaOptionalCompatMixins implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() {
        return FabricLoader.getInstance().isModLoaded("axiom") ? List.of("compat.AxiomBlockBreakMixin") : List.of();
    }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) { }
}
