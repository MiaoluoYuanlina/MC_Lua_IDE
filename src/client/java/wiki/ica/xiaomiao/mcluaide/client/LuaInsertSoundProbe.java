package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Native mixer regression at the title screen, confined to build/insert-sound-probe. */
final class LuaInsertSoundProbe {
    private static int stage, checks;
    private static long started;
    private static LuaInsertOverlay overlay;
    private static LuaInsertSoundPreview preview;
    private static SoundInstance previous, current, unrelated;
    private static final List<SoundInstance> cancelled = new ArrayList<>();
    private static double mx, my;
    private static double originalVolume;
    private static long cleanedAt;

    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                started = System.nanoTime();
                require(client.gameDirectory.toPath().toAbsolutePath().toString().contains("insert-sound-probe"), "Unsafe fixture directory");
                originalVolume = client.options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
                client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1D);
                overlay = new LuaInsertOverlay(client, client.font, ignored -> {});
                overlay.resize(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
                set(overlay, "open", true); set(overlay, "progress", 1F); set(overlay, "keyboardFocused", true);
                set(overlay, "kind", LuaInsertCatalog.Kind.SOUND);
                set(overlay, "filtered", List.of(sound("minecraft:music.creative"), sound("minecraft:music.menu")));
                preview = (LuaInsertSoundPreview)get(overlay, "soundPreview");
                var panelX = LuaInsertOverlay.class.getDeclaredMethod("panelX"); panelX.setAccessible(true);
                mx = (int)panelX.invoke(overlay) + 30; my = (int)get(overlay, "windowY") + (int)get(overlay, "LIST_TOP") + 7;
                unrelated = new SimpleSoundInstance(Identifier.parse("minecraft:music.end"), SoundSource.MASTER, .05F, 1F,
                        RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
                client.getSoundManager().play(unrelated);
                overlay.updateSoundPreview(mx, my, true, true); current = instance();
                require(current != null && preview.currentId().equals("minecraft:music.creative"), "Hover did not create native sound instance");
                stage = 1; return;
            }
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(60), "Insert sound probe timeout at " + stage);
            if (stage == 1) {
                if (!active(client, current) || !active(client, unrelated)) return;
                require(current.isRelative() && current.getAttenuation() == SoundInstance.Attenuation.NONE && current.getSource() == SoundSource.MASTER,
                        "Preview depended on camera/world position");
                previous = current;
                require(overlay.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0)), "Arrow down not handled");
                overlay.updateSoundPreview(mx, my, true, true); current = instance();
                require(preview.currentId().equals("minecraft:music.menu") && current != previous && stopped(previous), "Key selection failed to replace hover instance");
                stage = 2; return;
            }
            if (stage == 2) {
                if (!active(client, current) || active(client, previous)) return;
                require(active(client, unrelated), "Preview stopped unrelated world sound");
                for (int i = 0; i < 30; i++) {
                    var old = instance();
                    overlay.keyPressed(new KeyEvent(i % 2 == 0 ? InputConstants.KEY_UP : InputConstants.KEY_DOWN, 0, 0));
                    cancelled.add(old); require(stopped(old), "Rapid switch did not cancel old instance");
                }
                current = instance(); stage = 3; return;
            }
            if (stage == 3) {
                if (!active(client, current) || cancelled.stream().anyMatch(sound -> active(client, sound))) return;
                require(active(client, unrelated), "Rapid switching stopped unrelated sound");
                previous = current;
                overlay.updateSoundPreview(mx + 1, my, true, true); current = instance();
                require(preview.currentId().equals("minecraft:music.creative") && stopped(previous), "Mouse movement did not resume hover playback");
                stage = 4; return;
            }
            if (stage == 4) {
                if (!active(client, current) || active(client, previous)) return;
                previous = current;
                overlay.updateSoundPreview(0, 0, true, true);
                require(instance() == null && stopped(previous), "Leaving list did not stop native sound");
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_UP, 0, 0));
                overlay.updateSoundPreview(0, 0, true, true);
                require(instance() != null, "Keyboard preview required mouse hover");
                previous = instance(); overlay.releaseKeyboardFocus();
                overlay.updateSoundPreview(0, 0, true, true);
                require(instance() == null && stopped(previous), "Focus loss failed to stop preview");
                overlay.updateSoundPreview(mx, my, true, true); previous = instance();
                overlay.updateSoundPreview(mx, my, false, true);
                require(instance() == null && stopped(previous), "Hidden/docked-inactive window retained preview");
                overlay.updateSoundPreview(mx, my, true, false);
                require(instance() == null, "Covered row started hover playback");
                overlay.updateSoundPreview(mx, my, true, true); previous = instance();
                overlay.close(); require(instance() == null && stopped(previous), "Closing window retained preview");
                set(overlay, "open", true); set(overlay, "keyboardFocused", true);
                set(overlay, "filtered", List.of(sound("minecraft:music.creative"),
                        new LuaInsertCatalog.TypedEntry(LuaInsertCatalog.Kind.ITEM, new LuaInsertCatalog.Entry("minecraft:diamond", "钻石", "minecraft")),
                        sound("minecraft:music.menu")));
                set(overlay, "selectedRow", 0);
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_UP, 0, 0)); previous = instance();
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
                require(instance() == null && stopped(previous), "Mixed-list item selection played audio or retained previous sound");
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
                require(instance() != null && preview.currentId().equals("minecraft:music.menu"), "Mixed-list arrow navigation failed to play sound row");
                overlay.stopSoundPreview();
                overlay.updateSoundPreview(mx, my + 20, true, true);
                require(instance() == null, "Mixed-list item hover played audio");
                overlay.updateSoundPreview(mx, my + 40, true, true);
                require(instance() != null && preview.currentId().equals("minecraft:music.menu"), "Mixed-list sound hover did not play");
                previous = instance();
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
                require(overlay.kind() == LuaInsertCatalog.Kind.PARTICLE && instance() == null && stopped(previous), "Leaving sound page retained native preview");
                set(overlay, "filtered", List.of(sound("minecraft:music.menu"))); set(overlay, "selectedRow", 0);
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0)); overlay.updateSoundPreview(mx, my, true, true);
                require(instance() == null, "Non-sound category previewed stale sound data");
                set(overlay, "kind", LuaInsertCatalog.Kind.SOUND);
                overlay.close(); set(overlay, "open", true); set(overlay, "keyboardFocused", true);
                set(overlay, "filtered", List.of(sound("test:missing_audio")));
                set(overlay, "selectedRow", 0);
                overlay.keyPressed(new KeyEvent(InputConstants.KEY_UP, 0, 0));
                require(instance() == null && preview.message().contains("未加载"), "Server-only resource did not fail gracefully");
                overlay.close(); client.getSoundManager().stop(unrelated);
                client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(originalVolume);
                cleanedAt = System.nanoTime(); stage = 5; return;
            }
            if (stage == 5) {
                if (System.nanoTime() - cleanedAt < TimeUnit.SECONDS.toNanos(2)) return;
                require(cancelled.stream().noneMatch(sound -> active(client, sound)), "Cancelled delayed playback returned after close");
                stage = 99;
                System.out.println("Insert sound native probe passed: " + checks + " checks; hover/arrows, native playback, fast cancellation, keyboard/mouse priority, local attenuation, unrelated sounds, focus/hide/cover/close and missing resources");
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            int failed = stage; stage = 99;
            if (overlay != null) overlay.stopSoundPreview();
            if (unrelated != null) client.getSoundManager().stop(unrelated);
            client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(originalVolume);
            client.stop(); throw new IllegalStateException("Insert sound probe failed at stage " + failed, failure);
        }
    }
    private static Object get(Object owner, String name) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static LuaInsertCatalog.TypedEntry sound(String id) {
        return new LuaInsertCatalog.TypedEntry(LuaInsertCatalog.Kind.SOUND,
                new LuaInsertCatalog.Entry(id, id, id.substring(0, id.indexOf(':'))));
    }
    private static void set(Object owner, String name, Object value) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private static SoundInstance instance() throws Exception { return (SoundInstance)get(get(preview, "audio"), "instance"); }
    private static boolean active(Minecraft client, SoundInstance sound) { return sound != null && client.getSoundManager().isActive(sound); }
    private static boolean stopped(SoundInstance sound) { return sound instanceof TickableSoundInstance tickable && tickable.isStopped(); }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
