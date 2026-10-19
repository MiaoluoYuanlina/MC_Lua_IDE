package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import wiki.ica.xiaomiao.mcluaide.client.mixin.SoundEngineAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.SoundManagerAccessor;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in real sound-thread regression check for streamed offsets; uses only generated silence. */
final class LuaOggPlaybackProbe {
    private static int stage;
    private static long start;
    private static volatile ChannelAccess.ChannelHandle handle;
    private static volatile double previous;
    private static volatile boolean crossedFirstBuffer, crossedSecondBuffer;
    private static volatile String failure;
    private static double duration;
	private static byte[] ogg;
	private static LuaBinaryPreview preview;

    static void tick(Minecraft minecraft) {
        if (stage == 5 || minecraft.getOverlay() != null) return;
        try {
            if (failure != null) throw new IllegalStateException(failure);
            if (stage == 0) {
                stage = 1;
                start = System.nanoTime();
                ChannelAccess channels = ((SoundEngineAccessor) ((SoundManagerAccessor)
                        minecraft.getSoundManager()).mcLuaIde$getSoundEngine()).mcLuaIde$getChannelAccess();
                channels.createHandle(Library.Pool.STREAMING).whenComplete((created, error) -> {
                    if (created == null || error != null) { failure = "No sound device/channel: " + error; return; }
                    created.execute(channel -> {
                        try {
                            ((OggPreviewChannel) channel).mcLuaIde$trackPreview();
                            channel.setRelative(true);
                            channel.disableAttenuation();
                            channel.setVolume(0);
                            channel.attachBufferStream(new Silence());
                            channel.play();
                            handle = created;
                        } catch (Throwable throwable) { failure = throwable.toString(); }
                    });
                });
            } else if (stage == 1) {
                if (handle != null && !handle.isStopped()) handle.execute(channel -> {
                    if (channel.stopped()) return;
                    double seconds = ((OggPreviewChannel) channel).mcLuaIde$previewSeconds();
                    if (seconds + 0.002 < previous) failure = "Stream position jumped backwards: " + previous + " -> " + seconds;
                    previous = seconds;
                    crossedFirstBuffer |= seconds > 1.1;
                    crossedSecondBuffer |= seconds > 2.1;
                });
                if (handle != null && handle.isStopped()) {
                    if (!crossedFirstBuffer || !crossedSecondBuffer) throw new IllegalStateException("Stream offset failed to span removed buffers: " + previous);
                    ogg = Files.readAllBytes(Path.of(System.getProperty("mc_lua_ide.oggPlaybackProbe")));
                    duration = LuaOggMetadata.read(ogg).duration();
                    LuaOggPreviewPlayer.play(minecraft, ogg, duration);
                    stage = 2;
                    start = System.nanoTime();
                }
            } else if (stage == 2) {
                LuaOggPreviewPlayer.poll();
                if (!LuaOggPreviewPlayer.error().isEmpty()) throw new IllegalStateException(LuaOggPreviewPlayer.error());
                if (LuaOggPreviewPlayer.state().equals("播放结束")) {
                    if (Math.abs(LuaOggPreviewPlayer.position() - duration) > 0.000001)
                        throw new IllegalStateException("Natural completion did not retain full progress");
                    LuaOggPreviewPlayer.stop();
                    if (LuaOggPreviewPlayer.position() != 0 || LuaOggPreviewPlayer.playing())
                        throw new IllegalStateException("Stop did not reset progress");
                    preview = new LuaBinaryPreview(minecraft, "test.ogg", ogg);
                    // Drag from 25% through 75%, and release at 50% while stopped.
                    preview.click(minecraft, 0, 56, 117, 0, 0, 200, 250);
                    preview.drag(144, 117);
                    preview.release(minecraft, 0, 100);
                    if (Math.abs(LuaOggPreviewPlayer.position() - duration / 2) > 0.000001)
                        throw new IllegalStateException("Seek did not set the drag-release target");
                    stage = 3;
                    start = System.nanoTime();
                }
            } else if (stage == 3) {
                LuaOggPreviewPlayer.poll();
                if (!LuaOggPreviewPlayer.error().isEmpty()) throw new IllegalStateException(LuaOggPreviewPlayer.error());
                if (LuaOggPreviewPlayer.state().equals("播放结束")) {
                    if (LuaOggPreviewPlayer.position() != duration) throw new IllegalStateException("Seeked playback ended at the wrong position");
                    // End-of-file drag, backward seek, a second pending seek, then immediate stop.
                    preview.click(minecraft, 0, 100, 117, 0, 0, 200, 250);
                    preview.release(minecraft, 0, 500);
                    if (LuaOggPreviewPlayer.playing() || LuaOggPreviewPlayer.position() != duration)
                        throw new IllegalStateException("End-of-file seek restarted audio");
                    LuaOggPreviewPlayer.seek(minecraft, ogg, duration, 0);
                    LuaOggPreviewPlayer.seek(minecraft, ogg, duration, duration * 0.7);
                    LuaOggPreviewPlayer.stop();
                    stage = 4;
                    start = System.nanoTime();
                }
            } else if (stage == 4 && System.nanoTime() - start > 600_000_000L) {
                LuaOggPreviewPlayer.poll();
                if (LuaOggPreviewPlayer.playing() || LuaOggPreviewPlayer.position() != 0 || !LuaOggPreviewPlayer.state().equals("未播放"))
                    throw new IllegalStateException("An obsolete pending seek resumed after stop");
                preview.close(minecraft);
                System.out.println("OGG playback probe passed: buffer rollover, completion, drag seek, end seek, seek cancellation");
                stage = 5;
                minecraft.stop();
            }
            if (System.nanoTime() - start > 15_000_000_000L)
                throw new IllegalStateException("Sound probe timed out at stage " + stage);
        } catch (Exception exception) { throw new IllegalStateException("OGG playback probe failed", exception); }
    }

    private static final class Silence implements AudioStream {
        private int remaining = 22050 * 4 * 3 + 22050 * 4 / 5;
        @Override public AudioFormat getFormat() { return new AudioFormat(22050, 16, 2, true, false); }
        @Override public ByteBuffer read(int size) {
            int count = Math.min(size, remaining);
            remaining -= count;
            ByteBuffer result = ByteBuffer.allocateDirect(count);
            // Explicitly initialize PCM, independent of native allocator behavior.
            while (result.hasRemaining()) result.put((byte) 0);
            return result.flip();
        }
        @Override public void close() { remaining = 0; }
    }
}
