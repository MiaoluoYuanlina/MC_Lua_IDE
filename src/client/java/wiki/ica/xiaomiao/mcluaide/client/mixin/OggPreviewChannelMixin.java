package wiki.ica.xiaomiao.mcluaide.client.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wiki.ica.xiaomiao.mcluaide.client.OggPreviewChannel;

@Mixin(Channel.class)
public abstract class OggPreviewChannelMixin implements OggPreviewChannel {
    @Shadow @Final private int source;
    @Unique private boolean mcLuaIde$tracking;
    @Unique private double mcLuaIde$removedSeconds;

    @Override public void mcLuaIde$trackPreview() {
        mcLuaIde$tracking = true;
        mcLuaIde$removedSeconds = 0;
    }

    @Override public double mcLuaIde$previewSeconds() {
        // AL_SEC_OFFSET is relative to the current queue; add buffers already removed by Minecraft.
        return mcLuaIde$removedSeconds + Math.max(0, AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET));
    }

    @Redirect(method = "removeProcessedBuffers", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/AL10;alSourceUnqueueBuffers(I[I)V", remap = false))
    private void mcLuaIde$recordProcessedBuffers(int sourceId, int[] buffers) {
        AL10.alSourceUnqueueBuffers(sourceId, buffers);
        if (!mcLuaIde$tracking) return;
        for (int buffer : buffers) {
            mcLuaIde$removedSeconds += OggPreviewChannel.bufferSeconds(
                    AL10.alGetBufferi(buffer, AL10.AL_SIZE), AL10.alGetBufferi(buffer, AL10.AL_BITS),
                    AL10.alGetBufferi(buffer, AL10.AL_CHANNELS), AL10.alGetBufferi(buffer, AL10.AL_FREQUENCY));
        }
    }
}
