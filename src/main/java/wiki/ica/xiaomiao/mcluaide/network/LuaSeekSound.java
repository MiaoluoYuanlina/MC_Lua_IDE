package wiki.ica.xiaomiao.mcluaide.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

/** Server-authorized playback offset. No audio bytes or client filesystem paths cross the network. */
public record LuaSeekSound(Identifier soundId, SoundSource category, Identifier dimension,
        double x, double y, double z, float volume, float pitch, double startSeconds, long seed, boolean relative)
        implements CustomPacketPayload {
    public static final Type<LuaSeekSound> TYPE = new Type<>(McLuaIdeMod.id("seek_sound_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LuaSeekSound> CODEC = StreamCodec.of(
            LuaSeekSound::write, LuaSeekSound::read);

    /** Source compatibility for existing positional callers. */
    public LuaSeekSound(Identifier soundId, SoundSource category, Identifier dimension,
            double x, double y, double z, float volume, float pitch, double startSeconds, long seed) {
        this(soundId, category, dimension, x, y, z, volume, pitch, startSeconds, seed, false);
    }

    public LuaSeekSound {
        if (soundId == null || category == null || dimension == null
                || soundId.toString().length() > LuaProtocol.MAX_NAME_CHARS
                || dimension.toString().length() > LuaProtocol.MAX_NAME_CHARS)
            throw new IllegalArgumentException("声音 ID、分类或维度无效");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("坐标必须是有限数字");
        if (!Float.isFinite(volume) || volume < 0)
            throw new IllegalArgumentException("音量必须是非负的有限数字");
        if (!Float.isFinite(pitch) || pitch < 0 || pitch > 2)
            throw new IllegalArgumentException("音调必须在 0 到 2 之间");
        if (!Double.isFinite(startSeconds) || startSeconds < 0)
            throw new IllegalArgumentException("起播时间 startSeconds 必须是非负的有限秒数");
    }

    private static LuaSeekSound read(RegistryFriendlyByteBuf buffer) {
        return new LuaSeekSound(Identifier.parse(buffer.readUtf(LuaProtocol.MAX_NAME_CHARS)),
                buffer.readEnum(SoundSource.class), Identifier.parse(buffer.readUtf(LuaProtocol.MAX_NAME_CHARS)),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(),
                buffer.readFloat(), buffer.readDouble(), buffer.readLong(), buffer.readBoolean());
    }

    private static void write(RegistryFriendlyByteBuf buffer, LuaSeekSound sound) {
        buffer.writeUtf(sound.soundId.toString(), LuaProtocol.MAX_NAME_CHARS);
        buffer.writeEnum(sound.category);
        buffer.writeUtf(sound.dimension.toString(), LuaProtocol.MAX_NAME_CHARS);
        buffer.writeDouble(sound.x); buffer.writeDouble(sound.y); buffer.writeDouble(sound.z);
        buffer.writeFloat(sound.volume); buffer.writeFloat(sound.pitch);
        buffer.writeDouble(sound.startSeconds); buffer.writeLong(sound.seed); buffer.writeBoolean(sound.relative);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
