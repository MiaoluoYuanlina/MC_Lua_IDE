package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.*;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in isolated game test. Uses a dedicated test registry key and mocked AI, never user credentials. */
final class LuaAiProbe {
    private static int stage, checks;
    private static long started;
    private static LuaIdeScreen ide;
    private static LuaAiController controller;
    private static LuaAiSecretStore store;
    private static LuaExtensionWindow frame;
    private static CompletableFuture<Void> files;
    private static String runtimeSource = LuaAiSmoke.SOURCE + "error('fixture runtime failure')\n";
    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                started = System.nanoTime(); stage = 1;
                require(client.gameDirectory.toPath().toAbsolutePath().toString().contains("ai-probe"), "Unsafe game fixture directory");
                client.options.guiScale().set(1); client.resizeGui(); client.options.renderDistance().set(2); client.options.simulationDistance().set(6);
                store = new LuaAiSecretStore(LuaAiSecretStore.KEY + "_Test_" + UUID.randomUUID().toString().replace("-", ""));
                var preferences = new LuaAiSettings(); preferences.apis.put(preferences.selected, new LuaAiSettings.Api(preferences.selected.endpoint, "fixture_model", "fake_game_test_key")); store.save(preferences);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("AI probe cancelled"); }); return;
            }
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(180), "AI probe timeout stage " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("ai_" + System.currentTimeMillis()); create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                invoke(create, "onCreate", new Class<?>[0]); stage = 2; return;
            }
            if (stage == 2) {
                if (client.level == null || client.player == null || !ScriptManager.isServerAvailable()) return;
                files = ScriptManager.save("main", runtimeSource).thenCompose(ignored -> ScriptManager.save("lib/math", "return { x = 3 }\n")); stage = 3; return;
            }
            if (stage == 3) {
                if (!files.isDone()) return; files.join();
                ide = new LuaIdeScreen(null, "main");
                controller = testController(client);
                set(ide, "ai", controller); client.setScreen(ide); stage = 4; return;
            }
            if (stage == 4) {
                if (!Boolean.TRUE.equals(get(ide, "sourceLoaded")) || get(ide, "aiProblem") == null) return;
                require(!controller.settings().enabled && !controller.busy(), "Default-off AI sent a request");
                invoke(ide, "toggleMenu", new Class<?>[]{String.class}, "tools");
                @SuppressWarnings("unchecked") List<Button> menu = (List<Button>) get(ide, "toolMenuItems");
                Button aiButton = menu.stream().filter(button -> button.getMessage().getString().contains("AI工具")).findFirst().orElseThrow();
                require(ide.mouseClicked(mouse(aiButton.getX() + 5, aiButton.getY() + 5), false), "AI Tools menu not clickable");
                ide.mouseReleased(mouse(aiButton.getX() + 5, aiButton.getY() + 5));
                frame = frame(); for (int i = 0; i < 20; i++) frame.tick();
                require(frame.isOpen() && get(frame, "content") instanceof LuaAiWindow, "AI window failed to open");
                verifyWindow(client); ((LuaAiWindow)get(frame, "content")).mouseClicked(mouse(15, 210), false);
                require(controller.busy(), "Manual analysis button did nothing"); stage = 5; return;
            }
            if (stage == 5) {
                if (controller.busy()) return;
                require(controller.marks(LuaAiSmoke.MAIN, runtimeSource).size() == 1, "Current source mark missing");
                require(controller.marks(LuaAiSmoke.MODULE, "return { x = 3 }\n").size() == 1, "Server dependency mark missing");
                require(logs().stream().anyMatch(text -> text.contains("missing 未定义")), "AI response missing from run log");
                verifyAnnotations(client);
                var next = controller.settings(); next.enabled = true; controller.configure(next);
                ide.setExtensionWindowOpen(LuaAiWindow.ID, false);
                invoke(ide, "runScript", new Class<?>[0]); stage = 6; return;
            }
            if (stage == 6) {
                if (get(ide, "aiRuntimeProblem") == null || controller.busy() || logs().stream().noneMatch(text -> text.contains("AI 分析"))) return;
                require(((LuaAiAgent.Problem)get(ide, "aiRuntimeProblem")).error().contains("fixture runtime failure"), "Runtime error was not captured");
                require(controller.marks(LuaAiSmoke.MAIN, runtimeSource).size() == 1, "Automatic runtime analysis failed");
                var execution = (ScriptManager.ScriptExecution)get(ide, "outputExecution"); execution.appendIdeMessage("fixture later Lua output"); invoke(ide, "syncExecutionState", new Class<?>[0]);
                require(logs().stream().anyMatch(text -> text.contains("missing 未定义")), "Later output erased AI reply");
                var preferences = controller.settings(); preferences.enabled = false; controller.configure(preferences);
                require(!store.load().enabled, "Disabled preference not persisted");
                frame.open(); frame.setDockSide(DockSide.RIGHT); frame.setDockBounds(500, 68, 290, 260); for (int i = 0; i < 20; i++) frame.tick();
                require(frame.dockSide() == DockSide.RIGHT, "AI frame cannot dock");
                preferences.enabled = true; controller.configure(preferences);
                reopen(client);
                require(get(ide, "outputExecution") == execution && (boolean)get(ide, "outputVisible"), "Finished log not restored on new IDE");
                require(logs().stream().anyMatch(text -> text.contains("missing 未定义")) && get(controller, "pending") == null, "Completed AI reply missing or reanalyzed on reopen");
                client.setScreen(null);
                ScriptManager.startNormal("main", LuaAiSmoke.SOURCE + "MC.sleep(250)\nerror('background fixture failure')\n", value -> {}, value -> {});
                stage = 7; return;
            }
            if (stage == 7) {
                var latest = ScriptManager.lastExecution(); if (latest == null || latest.isRunning()) return;
                require(latest.result().error() != null, "Background runtime error missing");
                reopen(client);
                require(get(ide, "outputExecution") == latest && get(ide, "runningExecution") == null, "Background finished execution was restored as running");
                require(get(controller, "pending") != null && logs().stream().anyMatch(text -> text.contains("background fixture failure")), "Unanalyzed background error/log not restored");
                require(((LuaAiAgent.Problem)get(controller, "pending")).source().equals(latest.originalSource()), "Analysis uses edited code instead of run snapshot");
                set(controller, "due", 0L);
                Field cooldown = LuaAiController.class.getDeclaredField("lastAutomaticRequest"); cooldown.setAccessible(true); cooldown.setLong(null, 0);
                controller.tick(); require(controller.busy(), "Background error failed to trigger automatic analysis");
                stage = 8; return;
            }
            if (stage == 8) {
                if (controller.busy()) return;
                require(logs().stream().anyMatch(text -> text.contains("[AI 分析")), "Background analysis reply not logged");
                reopen(client);
                require(get(controller, "pending") == null && logs().stream().filter(text -> text.contains("[AI 分析")).count() == 1, "Background analysis repeated after reopening");
                client.setScreen(null);
                ScriptManager.startNormal("main", "print('finished-background-log')", value -> {}, value -> {});
                stage = 9; return;
            }
            if (stage == 9) {
                var latest = ScriptManager.lastExecution(); if (latest == null || latest.isRunning()) return;
                require(latest.result().error() == null && !latest.result().stopped(), "Normal completion fixture failed");
                reopen(client);
                require(logs().contains("finished-background-log") && get(controller, "pending") == null, "Normal completion did not restore log or triggered AI");
                client.setScreen(null);
                ScriptManager.startNormal("main", "MC.Block.get(1000000, 64, 1000000)\nprint('warning-background-log')", value -> {}, value -> {});
                stage = 10; return;
            }
            if (stage == 10) {
                var latest = ScriptManager.lastExecution(); if (latest == null || latest.isRunning()) return;
                require(latest.result().error() == null && !latest.result().stopped(), "Warning fixture became fatal");
                reopen(client);
                require(logs().contains("warning-background-log") && logs().stream().anyMatch(text -> text.startsWith("⚠"))
                        && get(controller, "pending") == null, "Warning log lost or incorrectly sent to AI");
                controller.cancel(false); store.deleteTestKey(); store = null;
                stage = 99; System.out.println("AI real game/UI probe passed: " + checks + " checks; providers/tools, config, annotations, completed/background run log restoration, original source, automatic once across new IDEs, normal/warning exclusion; zero real AI requests"); client.stop();
            }
        } catch (Exception | AssertionError error) {
            int failed = stage; stage = 99; if (controller != null) controller.cancel(false);
            try { if (store != null) store.deleteTestKey(); } catch (Exception ignored) { }
            client.stop(); throw new IllegalStateException("AI native probe failed stage " + failed, error);
        }
    }
    private static LuaAiController testController(Minecraft client) {
        return new LuaAiController(new LuaAiController.Host() {
            public LuaAiAgent.Problem currentProblem() { try { return (LuaAiAgent.Problem)invoke(ide, "currentAiProblem", new Class<?>[0]); } catch (Exception e) { throw new IllegalStateException(e); } }
            public LuaAiAgent.Workspace workspace() { try { return (LuaAiAgent.Workspace)invoke(ide, "aiWorkspace", new Class<?>[0]); } catch (Exception e) { throw new IllegalStateException(e); } }
            public void dispatch(Runnable action) { client.execute(action); }
            public void log(String text) { try { invoke(ide, "appendAiOutput", new Class<?>[]{String.class}, text); } catch (Exception e) { throw new IllegalStateException(e); } }
            public LuaAiAnalysisHistory analysisHistory(LuaAiAgent.Problem p) {
                try {
                    var source = (LuaAiAgent.Problem)get(ide, "aiRunSource");
                    var execution = (ScriptManager.ScriptExecution)get(ide, "outputExecution");
                    return execution != null && source != null && source.path().equals(p.path()) && source.source().equals(p.source())
                            ? execution.aiAnalysisHistory() : null;
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        }, store, LuaAiSmoke.mock());
    }
    private static void reopen(Minecraft client) throws Exception {
        client.setScreen(null);
        ide = new LuaIdeScreen(null, "main"); controller = testController(client);
        set(ide, "ai", controller); client.setScreen(ide);
    }
    private static void verifyWindow(Minecraft client) throws Exception {
        // Prior probe runs can persist a docked/minimum-width frame; use a known floating fixture.
        frame.setDockSide(DockSide.FLOATING);
        set(frame, "x", 50); set(frame, "y", 68); set(frame, "width", 560); set(frame, "height", 405);
        for (int i = 0; i < 20; i++) frame.tick();
        LuaAiWindow window = (LuaAiWindow)get(frame, "content"); window.resize(538, 380);
        for (int i = 0; i < 4; i++) {
            require(window.mouseClicked(mouse(i * 135 + 20, 35), false), "Provider button not clickable");
            require(((LuaAiSettings)get(window, "draft")).selected == LuaAiSettings.Provider.values()[i], "Provider did not switch");
            ((EditBox)get(window, "model")).setValue("fixture_model"); ((EditBox)get(window, "key")).setValue("fake_game_test_key");
        }
        window.mouseClicked(mouse(15, 35), false);
        require(((EditBox)get(window, "key")).getValue().isEmpty(), "Stored key filled into visible field");
        EditBox key = (EditBox)get(window, "key"); key.setValue("synthetic_password");
        var rendered = (net.minecraft.util.FormattedCharSequence) invoke(key, "applyFormat", new Class<?>[]{String.class, int.class}, key.getValue(), 0);
        StringBuilder text = new StringBuilder(); rendered.accept((index, style, cp) -> { text.appendCodePoint(cp); return true; }); require(text.toString().equals("*".repeat(key.getValue().length())), "Key input is not masked");
        key.setValue("");
        window.mouseClicked(mouse(15, 360), false); require(!controller.settings().enabled, "Saving credentials enabled AI");
        EditBox model = (EditBox)get(window, "model"); window.mouseClicked(mouse(110, 100), false);
        require(model.isFocused(), "Model field not editable"); window.keyPressed(new KeyEvent(65, 0, 2)); window.charTyped(new CharacterEvent('z')); require(model.getValue().equals("z"), "Field keyboard input failed");
        model.setValue("fixture_model"); window.mouseClicked(mouse(15, 360), false);
        int width = (int)get(frame, "width"), left = (int)invoke(frame, "panelX", new Class<?>[0]), top = (int)get(frame, "y");
        require(frame.mouseClicked(mouse(left + width - 1, top + 70), false), "Resize border not interactive");
        frame.mouseDragged(mouse(left + width - 31, top + 70), -30, 0); frame.mouseReleased(mouse(left + width - 31, top + 70));
        require((int)get(frame, "width") < width, "Frame width did not resize");
        for (int i = 0; i < 20; i++) frame.tick();
        left = (int)invoke(frame, "panelX", new Class<?>[0]); top = (int)get(frame, "y");
        require(frame.mouseClicked(mouse(left + 30, top + 12), false), "Title not draggable"); frame.mouseDragged(mouse(left + 60, top + 30), 30, 18); frame.mouseReleased(mouse(left + 60, top + 30));
        require((int)get(frame, "x") != left || (int)get(frame, "y") != top, "Frame did not move");
        // Last item must remain reachable even at a small logical height.
        int height = ide.height; ide.height = 240; invoke(ide, "buildToolsMenu", new Class<?>[0]);
        for (int page = 0; page < 3; page++) {
            @SuppressWarnings("unchecked") List<Button> buttons = (List<Button>)get(ide, "toolMenuItems");
            require(buttons.stream().allMatch(button -> button.getY() + button.getHeight() <= 240), "Tool menu extends offscreen");
            if (buttons.stream().anyMatch(button -> button.getMessage().getString().contains("AI工具"))) break;
            Button next = buttons.stream().filter(button -> button.getMessage().getString().startsWith("下一页")).findFirst().orElseThrow(); next.onPress(new KeyEvent(257, 0, 0));
        }
        ide.height = height; set(ide, "toolsMenuPage", 0); invoke(ide, "buildToolsMenu", new Class<?>[0]); invoke(ide, "hideMenus", new Class<?>[0]);
        window.resize(538, 380); set(window, "scroll", 0);
    }
    private static void verifyAnnotations(Minecraft client) throws Exception {
        ide.setExtensionWindowOpen(LuaAiWindow.ID, false); invoke(ide, "closeOutputConsole", new Class<?>[0]);
        for (int i = 0; i < 20; i++) ide.tick();
        MultiLineEditBox editor = (MultiLineEditBox)get(ide, "editor"); var field = ((MultiLineEditBoxAccessor)editor).mcLuaIde$getTextField();
        for (float scale : new float[]{1F, 1.35F, 2F}) {
            set(ide, "codeScale", scale); LuaSyntaxHighlighter.setScale(editor, scale);
            var rectangles = ide.aiRectangles(field); require(!rectangles.isEmpty(), "Annotation rectangles missing at scale " + scale);
            var rect = rectangles.getFirst(); require(rect.mark().start() == runtimeSource.indexOf("missing") && rect.right() > rect.left(), "Annotation range/layout incorrect");
            int x = (rect.left() + rect.right()) / 2, y = (rect.top() + rect.bottom()) / 2;
            var graphics = new GuiGraphicsExtractor(client, new GuiRenderState(), x, y);
            require((boolean)invoke(ide, "extractAiHover", new Class<?>[]{GuiGraphicsExtractor.class, MultilineTextField.class, int.class, int.class}, graphics, field, x, y), "Annotation tooltip failed");
            set(ide, "leftMouseHeld", true); require(!(boolean)invoke(ide, "extractAiHover", new Class<?>[]{GuiGraphicsExtractor.class, MultilineTextField.class, int.class, int.class}, graphics, field, x, y), "Annotation tooltip covered drag selection"); set(ide, "leftMouseHeld", false);
        }
        require(ide.keyPressed(new KeyEvent(256, 0, 0)) && client.screen == ide, "Esc hovering annotation closed IDE");
        require(controller.marks(LuaAiSmoke.MAIN, runtimeSource).isEmpty() && controller.marks(LuaAiSmoke.MODULE, "return { x = 3 }\n").isEmpty(), "Esc did not clear every annotation");
        set(ide, "codeScale", 1F); LuaSyntaxHighlighter.setScale(editor, 1F);
    }
    private static LuaExtensionWindow frame() throws Exception { return ((Map<?,?>)get(ide, "extensionWindows")).values().stream().map(LuaExtensionWindow.class::cast).filter(window -> window.id().equals(LuaAiWindow.ID)).findFirst().orElseThrow(); }
    @SuppressWarnings("unchecked") private static List<String> logs() throws Exception { return (List<String>)get(ide, "outputLines"); }
    private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static MouseButtonEvent mouse(double x, double y) { return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)); }
    private static Field field(Object object, String name) throws Exception { for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { } throw new NoSuchFieldException(name); }
    private static Object get(Object object, String name) throws Exception { return field(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) throws Exception { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args); }
}
