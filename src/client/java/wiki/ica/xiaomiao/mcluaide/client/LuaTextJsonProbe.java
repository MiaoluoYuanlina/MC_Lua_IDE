package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Fresh-world UI/network regression. No existing saves or scripts are used. */
final class LuaTextJsonProbe {
    private static final String SOURCE = "local playerName = 'Alex'\nMC.Chat.broadcast(\"\")\nprint('text-json-runtime-ok')\n";
    private static int stage, switches, checks;
    private static long started;
    private static LuaIdeScreen ide;
    private static LuaTextJsonOverlay overlay;
    private static String originalClipboard, copied;
    private static CompletableFuture<Void> saved;
    private static CompletableFuture<ScriptManager.ExecutionResult> completed;
    private static final List<String> logs = new CopyOnWriteArrayList<>();

    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                require(client.gameDirectory.toPath().toAbsolutePath().toString().contains("text-json-probe"), "Unsafe fixture directory");
                originalClipboard = client.keyboardHandler.getClipboard(); started = System.nanoTime(); stage = 1;
                client.options.guiScale().set(1); client.resizeGui(); client.options.renderDistance().set(2); client.options.simulationDistance().set(2);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Text JSON probe cancelled"); }); return;
            }
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(180), "Text JSON probe timed out at stage " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("text_json_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                var method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true); method.invoke(create);
                stage = 2; return;
            }
            if (stage == 2) {
                if (client.player == null || !ScriptManager.isServerAvailable()) return;
                saved = ScriptManager.save("text_json_probe", SOURCE); stage = 3; return;
            }
            if (stage == 3) {
                if (!saved.isDone()) return; saved.join();
                ide = new LuaIdeScreen(null, "text_json_probe"); client.setScreen(ide); stage = 4; return;
            }
            if (stage == 4) {
                if (!Boolean.TRUE.equals(get(ide, "sourceLoaded"))) return;
                var field = ((MultiLineEditBoxAccessor)editor()).mcLuaIde$getTextField();
                field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, SOURCE.indexOf("\"\"") + 1);
                require(ide.keyPressed(new KeyEvent(InputConstants.KEY_I, 0, 2)), "Ctrl+I was not consumed");
                overlay = (LuaTextJsonOverlay)get(ide, "textJsonOverlay");
                require(overlay.isOpen() && get(ide, "textJsonInsertionTarget") != null, "Ctrl+I failed to open text builder for argument");
                stage = 5; return;
            }
            if (stage == 5) {
                if ((float)get(overlay, "progress") < .97F) return;
                if (segment().kind != LuaTextJsonDocument.Kind.LUA_VARIABLE) {
                    require(switches++ < LuaTextJsonDocument.Kind.values().length, "Lua variable type cannot be selected");
                    click((int)get(overlay, "sidebarRight") + 8 + 5 + 95, (int)get(overlay, "detailTop") + 10); return;
                }
                require(get(overlay, "focus").toString().equals("LUA_VARIABLE"), "Selecting variable type did not focus new input");
                ide.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, 0));
                for (int i = 0; i < 5; i++) ide.keyPressed(new KeyEvent(InputConstants.KEY_DELETE, 0, 0));
                for (int point : "playerName".codePoints().toArray()) require(ide.charTyped(new CharacterEvent(point)), "Variable character input was not handled");
                require(segment().luaVariable.equals("playerName"), "Variable input went to code rather than builder");
                segment().bold = true; segment().hover = LuaTextJsonDocument.Hover.TEXT; segment().hoverText = "动态变量";
                segment().click = LuaTextJsonDocument.Click.RUN_COMMAND; segment().clickValue = "/help";
                stage = 6; return;
            }
            if (stage == 6) {
                clickBottom(1);
                copied = client.keyboardHandler.getClipboard();
                require(copied.contains(" .. ") && copied.contains("tostring((playerName))") && copied.contains("click_event"), "Copy button did not produce full dynamic expression");
                require(editor().getValue().equals(SOURCE), "Copy unexpectedly edited source");
                clickBottom(0);
                String actual = editor().getValue();
                require(actual.equals(SOURCE.replace("\"\"", copied)), "Insert did not replace complete argument with copied expression");
                require(LuaCodeAnalyzer.analyze("text_json_probe", actual).valid(), "IDE rejected generated dynamic text");
                completed = new CompletableFuture<>();
                ScriptManager.startNormal("text_json_probe", actual, logs::add, completed::complete);
                stage = 7; return;
            }
            if (stage == 7) {
                if (!completed.isDone()) return;
                var result = completed.join();
                require(result.error() == null && !result.stopped() && logs.contains("text-json-runtime-ok"), "Generated chat expression failed on server: " + result + " " + logs);
                client.keyboardHandler.setClipboard(originalClipboard); stage = 99; client.setScreen(null);
                System.out.println("Text JSON real-game probe passed: " + checks + " checks; Ctrl+I argument detection, actual type selection/input, dynamic clipboard, exact argument insertion, IDE diagnostics and network/server chat execution");
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            int failed = stage; stage = 99;
            if (originalClipboard != null) client.keyboardHandler.setClipboard(originalClipboard);
            client.stop(); throw new IllegalStateException("Text JSON probe failed at stage " + failed, failure);
        }
    }
    private static Object get(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static MultiLineEditBox editor() throws Exception { return (MultiLineEditBox)get(ide, "editor"); }
    @SuppressWarnings("unchecked") private static LuaTextJsonDocument.Segment segment() throws Exception {
        return ((List<LuaTextJsonDocument.Segment>)get(overlay, "segments")).getFirst();
    }
    private static void clickBottom(int index) throws Exception {
        int x = (int)get(overlay, "sidebarRight") + 8;
        var method = LuaTextJsonOverlay.class.getDeclaredMethod("panelX"); method.setAccessible(true);
        int right = (int)method.invoke(overlay) + (int)get(overlay, "windowWidth");
        int buttonWidth = (right - 12 - x - 8) / 3;
        click(x + index * (buttonWidth + 4) + buttonWidth / 2.0,
                (int)get(overlay, "windowY") + (int)get(overlay, "windowHeight") - 20);
    }
    private static void click(double x, double y) throws Exception {
        float scale = (float)get(ide, "interfaceScale");
        var event = new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(0, 0));
        require(ide.mouseClicked(event, false), "Builder click not handled"); ide.mouseReleased(event);
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
