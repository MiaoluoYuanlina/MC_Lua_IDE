package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wiki.ica.xiaomiao.mcluaide.client.LuaSeekSoundPlayer;

@Mixin(SoundEngine.class)
public abstract class SeekSoundCancellationMixin {
    @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
    private void mcLuaIde$cancelSeek(SoundInstance instance, CallbackInfo callback) {
        LuaSeekSoundPlayer.cancel(instance);
    }

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void mcLuaIde$cancelAllSeeks(CallbackInfo callback) {
        LuaSeekSoundPlayer.cancelAll();
    }
}
