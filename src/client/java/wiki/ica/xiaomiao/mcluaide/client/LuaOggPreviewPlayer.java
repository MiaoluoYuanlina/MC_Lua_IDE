package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import wiki.ica.xiaomiao.mcluaide.client.mixin.SoundEngineAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.SoundManagerAccessor;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;

/** Streams previews through the game device; opens/seeks on a bounded background worker. */
final class LuaOggPreviewPlayer {
    private static final ExecutorService PREPARE = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MC Lua IDE audio preview");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile ChannelAccess.ChannelHandle active;
    private static volatile int generation;
    private static volatile boolean requested;
    private static volatile String error = "";
    private static volatile double position;
    private static volatile double duration = -1;
    private static volatile double startOffset;
    private static volatile String state = "未播放";
    private static volatile boolean started;
    private static volatile long nextPollNanos;
    static String error() { return error; }
    static String state() { return state; }
    static double position() { return position; }

    /** Schedule bounded polling on the sound thread, never call OpenAL from rendering. */
    static synchronized void poll() {
        ChannelAccess.ChannelHandle handle = active;
        if (!requested || !started || handle == null) return;
        if (handle.isStopped()) { finished(generation); return; }
        long now = System.nanoTime();
        if (now < nextPollNanos) return;
        nextPollNanos = now + 33_000_000L;
        int token = generation;
        handle.execute(channel -> {
            if (token != generation || !requested) return;
            if (channel.stopped()) { finished(token); return; }
            double seconds = startOffset + ((OggPreviewChannel) channel).mcLuaIde$previewSeconds();
            synchronized (LuaOggPreviewPlayer.class) {
                if (token == generation && Double.isFinite(seconds))
                    position = Math.max(position, duration >= 0 ? Math.min(duration, seconds) : seconds);
            }
        });
    }

    private static synchronized void finished(int token) {
        if (token != generation) return;
        if (duration >= 0) position = duration;
        requested = false;
        state = "播放结束";
    }

    private LuaOggPreviewPlayer() { }

    static boolean playing() { return requested && (active == null || !active.isStopped()); }

    static void play(Minecraft minecraft, byte[] data, double length) throws IOException {
        begin(minecraft, data, length, 0);
    }

    static void seek(Minecraft minecraft, byte[] data, double length, double seconds) {
        if (!Double.isFinite(length) || length <= 0 || !Double.isFinite(seconds)) return;
        begin(minecraft, data, length, Math.clamp(seconds, 0, length));
    }

    private static synchronized void begin(Minecraft minecraft, byte[] data, double length, double seconds) {
        stop();
        duration = length;
        startOffset = seconds;
        position = seconds;
        if (length >= 0 && seconds >= length) { state = "播放结束"; return; }
        ChannelAccess channels = ((SoundEngineAccessor) ((SoundManagerAccessor)
                minecraft.getSoundManager()).mcLuaIde$getSoundEngine()).mcLuaIde$getChannelAccess();
        int token = generation;
        state = seconds > 0 ? "正在定位…" : "正在准备…";
        requested = true;
        PREPARE.execute(() -> prepare(channels, data, seconds, token));
    }

    private static void prepare(ChannelAccess channels, byte[] data, double seconds, int token) {
        if (token != generation) return;
        AudioStream stream;
        try {
            stream = LuaOggSeekStream.open(data, seconds, () -> token != generation);
        } catch (CancellationException cancelled) { return; }
        catch (IOException | RuntimeException exception) { failed(token, exception); return; }
        if (token != generation) { close(stream); return; }
        channels.createHandle(Library.Pool.STREAMING).whenComplete((handle, failure) -> {
            if (handle == null || failure != null) {
                close(stream);
                failed(token, failure == null ? new IOException("没有可用的游戏音频通道；请检查游戏音频设备") : failure);
                return;
            }
            synchronized (LuaOggPreviewPlayer.class) {
                if (token != generation) {
                    close(stream);
                    handle.execute(channel -> channel.stop());
                    return;
                }
                active = handle;
            }
            handle.execute(channel -> {
                if (token != generation) { close(stream); channel.stop(); return; }
                try {
                    ((OggPreviewChannel) channel).mcLuaIde$trackPreview();
                    channel.setRelative(true);
                    channel.disableAttenuation();
                    channel.setVolume(0.75F);
                    channel.attachBufferStream(stream);
                    channel.play();
                    synchronized (LuaOggPreviewPlayer.class) {
                        if (token == generation) { started = true; state = "正在播放"; }
                        else channel.stop();
                    }
                } catch (RuntimeException exception) {
                    channel.stop();
                    close(stream);
                    failed(token, exception);
                }
            });
        });
    }

    private static synchronized void failed(int token, Throwable failure) {
        if (token != generation) return;
        requested = false;
        state = "播放失败";
        error = ScriptManager.message(failure);
    }

    private static void close(AudioStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    static synchronized void stop() {
        generation++;
        error = "";
        requested = false;
        started = false;
        position = 0;
        duration = -1;
        startOffset = 0;
        state = "未播放";
        nextPollNanos = 0;
        ChannelAccess.ChannelHandle handle = active;
        active = null;
        if (handle != null && !handle.isStopped()) handle.execute(channel -> channel.stop());
    }
}
