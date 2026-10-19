package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Sample-accurate random access using Minecraft's bundled Vorbis library, with game-decoder fallback. */
final class LuaOggSeekStream implements AudioStream {
    private ByteBuffer encoded;
    private long decoder;
    private final AudioFormat format;

    static AudioStream open(byte[] bytes, double seconds, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        if (seconds <= 0) return new JOrbisAudioStream(new ByteArrayInputStream(bytes));
        try {
            return new LuaOggSeekStream(bytes, seconds);
        } catch (IOException unsupported) {
            // Some uncommon Vorbis features (e.g. floor 0) are only supported by the game decoder.
            return skipGameStream(bytes, seconds, cancelled);
        }
    }

    private LuaOggSeekStream(byte[] bytes, double seconds) throws IOException {
        encoded = MemoryUtil.memAlloc(bytes.length);
        encoded.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var error = stack.mallocInt(1);
            decoder = STBVorbis.stb_vorbis_open_memory(encoded, error, null);
            if (decoder == 0) throw new IOException("Vorbis 定位解码器无法打开音频（错误 " + error.get(0) + "）");
            var info = STBVorbis.stb_vorbis_get_info(decoder, STBVorbisInfo.malloc(stack));
            if (info.sample_rate() <= 0 || info.channels() <= 0) throw new IOException("音频采样率或声道无效");
            format = new AudioFormat(info.sample_rate(), 16, Math.min(2, info.channels()), true, false);
            long sample = (long) Math.floor(seconds * info.sample_rate());
            if (sample < 0 || sample >= 0xFFFFFFFFL || !STBVorbis.stb_vorbis_seek(decoder, (int) sample))
                throw new IOException("无法定位到 " + LuaOggMetadata.time(seconds));
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Override public AudioFormat getFormat() { return format; }

    @Override public ByteBuffer read(int bytes) throws IOException {
        if (decoder == 0) throw new IOException("音频流已关闭");
        int frameSize = format.getFrameSize();
        int size = Math.max(frameSize, bytes / frameSize * frameSize);
        // SoundBuffer takes a GC-managed direct PCM buffer, not a memAlloc-owned buffer.
        ByteBuffer result = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder,
                format.getChannels(), result.asShortBuffer());
        int error = STBVorbis.stb_vorbis_get_error(decoder);
        if (error != STBVorbis.VORBIS__no_error) throw new IOException("Vorbis 解码失败（错误 " + error + "）");
        result.limit(frames * frameSize);
        return result;
    }

    @Override public void close() {
        if (decoder != 0) { STBVorbis.stb_vorbis_close(decoder); decoder = 0; }
        if (encoded != null) { MemoryUtil.memFree(encoded); encoded = null; }
    }

    static AudioStream skipGameStream(byte[] bytes, double seconds, BooleanSupplier cancelled) throws IOException {
        JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(bytes));
        try {
            int frame = stream.getFormat().getFrameSize();
            long remaining = (long) Math.floor(seconds * stream.getFormat().getSampleRate()) * frame;
            ByteBuffer tail = ByteBuffer.allocate(0);
            while (remaining > 0) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                ByteBuffer chunk = stream.read((int) Math.min(remaining, 64 * 1024));
                if (chunk == null || !chunk.hasRemaining()) throw new IOException("定位目标超出了音频有效数据");
                int used = (int) Math.min(remaining, chunk.remaining());
                chunk.position(chunk.position() + used);
                remaining -= used;
                if (chunk.hasRemaining()) tail = chunk.slice();
            }
            ByteBuffer pending = tail;
            return new AudioStream() {
                private ByteBuffer first = pending;
                @Override public AudioFormat getFormat() { return stream.getFormat(); }
                @Override public ByteBuffer read(int size) throws IOException {
                    if (first != null && first.hasRemaining()) {
                        ByteBuffer result = first;
                        first = null;
                        return result;
                    }
                    return stream.read(size);
                }
                @Override public void close() throws IOException { first = null; stream.close(); }
            };
        } catch (IOException | RuntimeException exception) {
            stream.close();
            throw exception;
        }
    }
}
