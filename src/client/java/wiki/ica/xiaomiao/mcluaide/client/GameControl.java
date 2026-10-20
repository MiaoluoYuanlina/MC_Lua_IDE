package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Direct control of the game from the IDE's game viewport.
 * <p>
 * While the IDE screen is open Minecraft treats the keyboard and mouse as UI input. To give the game real control, the IDE
 * screen is closed (so the game grabs the mouse and handles keys exactly as in normal play) while the IDE keeps being drawn
 * as a non-interactive overlay, so the viewport window stays on screen and shows the live picture. Esc ends the mode,
 * consumed before the game can open its pause menu, and reopens the IDE screen exactly as it was left.
 */
public final class GameControl {
    private static ImGuiIdeScreen screen;
    private static boolean active, escapeConsumed;
    private static long lastTick;
    private GameControl() { }

    static boolean active() { return active; }

    /** Marks the IDE as controlled-from-the-game. The caller closes the IDE screen afterwards. */
    static boolean enter(ImGuiIdeScreen ide) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return false;
        screen = ide; active = true; escapeConsumed = false; lastTick = 0;
        return true;
    }

    /** Ends the mode and brings the IDE screen back; the game releases the mouse. */
    static void exit() {
        if (!active) return;
        active = false;
        var ide = screen;
        if (ide != null) Minecraft.getInstance().setScreen(ide);
    }

    /** The IDE to draw as an overlay this frame, or null when the game is not being controlled or another screen is open. */
    static ImGuiIdeScreen overlay(Minecraft client) {
        if (!active) return null;
        if (client.level == null || client.player == null) { abandon(); return null; }
        return client.screen == null ? screen : null;
    }

    /** The world was left while controlling: the IDE screen can never be shown again, so release it properly. */
    private static void abandon() {
        var ide = screen;
        active = false; screen = null;
        if (ide != null) ide.abandonGameControl();
    }

    /** Keeps the IDE's own per-tick work (file sync, extensions, recovery) running while it is not the open screen. */
    static void tick(Minecraft client) {
        var ide = overlay(client);
        if (ide != null) ide.tick();
    }

    /** First look at every key in the game: Esc leaves control mode instead of opening the pause menu. */
    public static boolean onKey(long window, int action, KeyEvent event) {
        var client = Minecraft.getInstance();
        if (window != client.getWindow().handle() || event.key() != GLFW.GLFW_KEY_ESCAPE) return false;
        if (action == GLFW.GLFW_RELEASE) { boolean consumed = escapeConsumed; escapeConsumed = false; return consumed; }
        if (!active || client.screen != null) return false;
        if (action == GLFW.GLFW_PRESS) { escapeConsumed = true; exit(); }
        return true;
    }
}
