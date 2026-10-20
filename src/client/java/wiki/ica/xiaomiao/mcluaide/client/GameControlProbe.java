package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.glfw.GLFW;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

/**
 * Opt-in real-world check of IDE game control: creates a throw-away world in its own run directory, opens the IDE,
 * enters game control, verifies the game owns the mouse and keyboard, then leaves with Esc.
 */
final class GameControlProbe {
    private static int ticks, stage, wait, checks, settle;
    private static ImGuiIdeScreen ide;
    private static double startX, startZ;
    private static String shot = "gamecontrol-ide.png";

    static void tick(Minecraft client) {
        if (++ticks > 6000) throw new IllegalStateException("game control probe timed out at stage " + stage);
        if (wait > 0) { wait--; return; }
        switch (stage) {
            case 0 -> {
                if (!(client.screen instanceof TitleScreen) || ticks < 80) return;
                var settings = new LevelSettings("gc_probe", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
                client.createWorldOpenFlows().createFreshLevel("gc_probe", settings, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, new TitleScreen());
                stage = 1;
            }
            case 1 -> {
                if (client.level == null || client.player == null || client.screen != null) return;
                wait = 80; stage = 2; // let chunks arrive
            }
            case 2 -> {
                ide = new ImGuiIdeScreen(null, ""); client.setScreen(ide); wait = 40; stage = 3;
            }
            case 3 -> {
                ide.probeWorkspace().open("NATIVE_GAMEVIEW"); wait = 40; stage = 4;
            }
            case 4 -> {
                require(client.screen == ide && !client.mouseHandler.isMouseGrabbed(), "IDE screen should own the mouse before control");
                require(ide.enterGameControl(), "enterGameControl refused inside a world");
                wait = 5; stage = 5;
            }
            case 5 -> {
                require(client.screen == null, "IDE screen was not closed for game control");
                require(GameControl.active(), "game control not active");
                // Minecraft only grabs the cursor for a focused window; an unattended VM window is never focused.
                boolean focused = GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
                if (focused) require(client.mouseHandler.isMouseGrabbed(), "the game did not grab the mouse");
                else McLuaIdeMod.LOGGER.info("GAME_CONTROL_PROBE window unfocused: mouse-grab check skipped");
                startX = client.player.getX(); startZ = client.player.getZ();
                // The game's own key handling must receive movement keys now.
                press(client, GLFW.GLFW_PRESS, new KeyEvent(GLFW.GLFW_KEY_W, 0, 0));
                wait = 25; stage = 6;
            }
            case 6 -> {
                press(client, GLFW.GLFW_RELEASE, new KeyEvent(GLFW.GLFW_KEY_W, 0, 0));
                double moved = Math.hypot(client.player.getX() - startX, client.player.getZ() - startZ);
                require(moved > 0.3, "the player did not move while controlling (moved " + moved + ")");
                require(client.screen == null && GameControl.active(), "still controlling after movement");
                shot = "gamecontrol-active.png"; ImGuiIdeProbe.capture = true; ImGuiIdeProbe.screenshotNameOverride(shot);
                wait = 10; stage = 7;
            }
            case 7 -> {
                press(client, GLFW.GLFW_PRESS, new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                press(client, GLFW.GLFW_RELEASE, new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                require(!GameControl.active(), "Esc did not leave game control");
                require(client.screen == ide, "Esc did not bring the IDE back (screen=" + client.screen + ")");
                require(!(client.screen instanceof PauseScreen), "Esc opened the pause menu");
                wait = 10; stage = 8;
            }
            case 8 -> {
                require(!client.mouseHandler.isMouseGrabbed(), "the mouse stayed grabbed after returning to the IDE");
                // A second round trip must work the same way.
                require(ide.enterGameControl(), "second enterGameControl refused"); wait = 5; stage = 9;
            }
            case 9 -> {
                if (client.screen != null && ++settle < 200) return; // the screen closes after the next rendered frame
                require(client.screen == null && GameControl.active(), "second control entry failed (screen=" + client.screen + ", active=" + GameControl.active() + ")");
                press(client, GLFW.GLFW_PRESS, new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                press(client, GLFW.GLFW_RELEASE, new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                require(client.screen == ide && !GameControl.active(), "second Esc round trip failed");
                // Esc with no control active must keep its normal meaning (not swallowed).
                require(!GameControl.onKey(client.getWindow().handle(), GLFW.GLFW_PRESS, new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)), "Esc swallowed outside control");
                McLuaIdeMod.LOGGER.info("GAME_CONTROL_PROBE_OK {} checks", checks);
                client.stop(); stage = 10;
            }
            default -> { }
        }
    }

    /** KeyboardHandler.keyPress is the game's own GLFW key callback; call it exactly as GLFW would. */
    private static void press(Minecraft client, int action, KeyEvent event) {
        try {
            var method = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
            method.setAccessible(true);
            method.invoke(client.keyboardHandler, client.getWindow().handle(), action, event);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static void require(boolean condition, String text) { checks++; if (!condition) throw new AssertionError(text); }
}
