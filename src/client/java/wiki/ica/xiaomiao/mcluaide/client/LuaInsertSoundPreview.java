package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Owns one local preview instance. Never stops sounds belonging to the world or scripts. */
final class LuaInsertSoundPreview {
    interface Audio { String play(String id); void stop(); }
    private final Audio audio;
    private String currentId, message = "";
    private boolean keyboard, pointerSeen, suspended;
    private double mouseX, mouseY;
    LuaInsertSoundPreview(Audio audio) { this.audio = audio; }
    LuaInsertSoundPreview(Minecraft client) { this(new NativeAudio(client)); }
    String currentId() { return currentId; }
    String message() { return message; }
    void keyboard(String id) { suspended = false; keyboard = true; target(id); }
    void pointer(double x, double y, String hoveredId) {
        if (pointerSeen && (x != mouseX || y != mouseY)) { keyboard = false; suspended = false; }
        pointerSeen = true; mouseX = x; mouseY = y;
        if (!keyboard && !suspended) target(hoveredId);
    }
    void mouseNavigation() { suspended = false; keyboard = false; }
    void suspend() { target(null); keyboard = false; suspended = true; }
    void clear() { target(null); keyboard = false; suspended = false; pointerSeen = false; }
    private void target(String id) {
        if (java.util.Objects.equals(currentId, id)) return;
        audio.stop(); currentId = id; message = "";
        if (id != null) {
            try { String failure = audio.play(id); if (failure != null) message = failure; }
            catch (RuntimeException error) { audio.stop(); message = "声音试听失败，请检查客户端声音资源"; }
        }
    }
    private static final class NativeAudio implements Audio {
        private final Minecraft client;
        private PreviewSound instance;
        NativeAudio(Minecraft client) { this.client = client; }
        public String play(String raw) {
            Identifier id = Identifier.tryParse(raw);
            if (id == null) return "声音 ID 格式无效";
            if (client.getSoundManager().getSoundEvent(id) == null) return "客户端未加载此声音资源，无法试听";
            instance = new PreviewSound(id);
            // Let fast cursor/key changes settle before allocating a native audio channel.
            // Superseded instances remain unplayable even if their delayed task runs later.
            client.getSoundManager().playDelayed(instance, 2);
            return null;
        }
        public void stop() {
            if (instance == null) return;
            instance.stopped = true;
            client.getSoundManager().stop(instance);
            instance = null;
        }
    }
    private static final class PreviewSound extends SimpleSoundInstance implements TickableSoundInstance {
        private volatile boolean stopped;
        PreviewSound(Identifier id) {
            super(id, SoundSource.MASTER, .5F, 1F, RandomSource.create(), false, 0,
                    SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        }
        @Override public boolean canPlaySound() { return !stopped; }
        @Override public boolean isStopped() { return stopped; }
        @Override public void tick() { }
    }
}
