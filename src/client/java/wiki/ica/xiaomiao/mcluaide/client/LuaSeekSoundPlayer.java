package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.RandomSource;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaSeekSound;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Uses the normal game mixer and Fabric's per-instance stream hook, not the IDE preview channel. */
public final class LuaSeekSoundPlayer {
    private static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;
    private static final long MAX_TOTAL_AUDIO_BYTES = 128L * 1024 * 1024;
    private static final Set<SeekInstance> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicLong AUDIO_BYTES = new AtomicLong();
    private static final ThreadPoolExecutor PREPARE = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "MC Lua IDE sound seek");
                thread.setDaemon(true); return thread;
            });

    private LuaSeekSoundPlayer() { }

    static SeekInstance play(Minecraft client, LuaSeekSound request) {
        if (client.level == null || !client.level.dimension().identifier().equals(request.dimension())) return null;
        return play(client.getSoundManager(), client.getResourceManager(), request);
    }

    static SeekInstance play(SoundManager manager, ResourceProvider resources, LuaSeekSound request) {
        if (ACTIVE.size() >= 32) {
            McLuaIdeMod.LOGGER.warn("忽略声音定位请求：同时播放的定位声音已达到 32 个"); return null;
        }
        SeekInstance instance = new SeekInstance(resources, request);
        ACTIVE.add(instance);
        try {
            if (manager.play(instance) == SoundEngine.PlayResult.NOT_STARTED) instance.cancel();
        } catch (RuntimeException error) { instance.cancel(); throw error; }
        return instance;
    }

    /** Called by the game sound engine so /stopsound, reload and disconnect also cancel pending decoding. */
    public static void cancel(SoundInstance instance) {
        if (instance instanceof SeekInstance seek) seek.cancel();
    }

    public static void cancelAll() { for (SeekInstance instance : ACTIVE) instance.cancel(); }
    static int activeCount() { return ACTIVE.size(); }
    static long audioBytes() { return AUDIO_BYTES.get(); }

    static final class SeekInstance extends SimpleSoundInstance implements TickableSoundInstance {
        private final ResourceProvider resources;
        private final double startSeconds;
        private volatile boolean stopped;
        private OwnedStream stream;
        volatile String error = "";
        volatile double duration = -1;
        volatile boolean prepared;

        SeekInstance(ResourceProvider resources, LuaSeekSound request) {
            super(request.soundId(), request.category(), request.volume(), request.pitch(),
                    RandomSource.create(request.seed()), false, 0,
                    request.relative() ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR,
                    request.x(), request.y(), request.z(), request.relative());
            this.resources = resources; this.startSeconds = request.startSeconds();
        }

        @Override public WeighedSoundEvents resolve(SoundManager manager) {
            WeighedSoundEvents event = super.resolve(manager);
            // Only this instance becomes streamed. Never change the shared sounds.json definition or cache.
            if (sound != SoundManager.EMPTY_SOUND && sound != SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                sound = new Sound(sound.getLocation(), sound.getVolume(), sound.getPitch(), sound.getWeight(),
                        sound.getType(), true, false, sound.getAttenuationDistance());
            }
            return event;
        }

        @Override public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library,
                Identifier path, boolean looping) {
            try {
                return CompletableFuture.supplyAsync(() -> prepare(path), PREPARE);
            } catch (RuntimeException failure) {
                fail(failure); return CompletableFuture.completedFuture(empty());
            }
        }

        private AudioStream prepare(Identifier path) {
            long reserved = 0;
            AudioStream decoded = null;
            try {
                byte[] bytes = readResource(path);
                long total = AUDIO_BYTES.addAndGet(bytes.length); reserved = bytes.length;
                if (total > MAX_TOTAL_AUDIO_BYTES) throw new IOException("定位声音占用超过 128 MiB，请先停止其他声音");
                duration = LuaOggMetadata.read(bytes).duration();
                if (stopped) throw new CancellationException();
                if (duration >= 0 && startSeconds >= duration) {
                    cancel(); return empty(); // Seeking past EOF must not fall back to playback from zero.
                }
                decoded = LuaOggSeekStream.open(bytes, startSeconds, () -> stopped);
                synchronized (this) {
                    if (stopped) throw new CancellationException();
                    stream = new OwnedStream(decoded, reserved);
                    decoded = null; reserved = 0; prepared = true;
                    return stream;
                }
            } catch (Exception failure) {
                if (!(failure instanceof CancellationException)) fail(failure);
                else cancel();
                return empty();
            } finally {
                if (decoded != null) try { decoded.close(); } catch (IOException ignored) { }
                if (reserved != 0) AUDIO_BYTES.addAndGet(-reserved);
            }
        }

        private byte[] readResource(Identifier path) throws IOException {
            try (var input = resources.getResourceOrThrow(path).open()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                for (int count; (count = input.read(chunk)) != -1;) {
                    if (stopped) throw new CancellationException();
                    if (output.size() + count > MAX_AUDIO_BYTES) throw new IOException("定位播放的单个 OGG 文件不能超过 32 MiB");
                    output.write(chunk, 0, count);
                }
                return output.toByteArray();
            }
        }

        private void fail(Exception failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            McLuaIdeMod.LOGGER.warn("无法从 {} 秒播放声音 {}：{}", startSeconds, getIdentifier(), error);
            cancel();
        }

        void cancel() {
            OwnedStream current;
            synchronized (this) { stopped = true; current = stream; }
            if (current != null) try { current.close(); } catch (IOException ignored) { }
            ACTIVE.remove(this);
        }

        @Override public boolean isStopped() { return stopped; }
        @Override public boolean canPlaySound() { return !stopped; }
        @Override public void tick() { }

        private final class OwnedStream implements AudioStream {
            private AudioStream delegate;
            private final AudioFormat format;
            private final long reserved;
            OwnedStream(AudioStream delegate, long reserved) {
                this.delegate = delegate; this.format = delegate.getFormat(); this.reserved = reserved;
            }
            @Override public AudioFormat getFormat() { return format; }
            @Override public synchronized ByteBuffer read(int bytes) throws IOException {
                // A stopped request can complete after the channel was removed. It must never restart audio.
                if (stopped || delegate == null) return ByteBuffer.allocateDirect(0);
                return delegate.read(bytes);
            }
            @Override public synchronized void close() throws IOException {
                if (delegate == null) return;
                AudioStream current = delegate; delegate = null;
                try { current.close(); }
                finally { AUDIO_BYTES.addAndGet(-reserved); stopped = true; ACTIVE.remove(SeekInstance.this); }
            }
        }
    }

    private static AudioStream empty() {
        return new AudioStream() {
            @Override public AudioFormat getFormat() { return new AudioFormat(44100, 16, 1, true, false); }
            @Override public ByteBuffer read(int bytes) { return ByteBuffer.allocateDirect(0); }
            @Override public void close() { }
        };
    }
}
