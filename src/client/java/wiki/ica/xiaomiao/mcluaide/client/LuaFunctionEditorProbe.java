package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultilineTextFieldAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.StringViewAccessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Opt-in real Minecraft editor regression in an isolated game directory. */
final class LuaFunctionEditorProbe {
    private static final String SOURCE = """
            local function welcome(player, message)
                --#name=欢迎玩家
                --#Introduction=向指定玩家发送欢迎信息
                --@player=目标玩家
                --@message=欢迎内容
                local function format(value)
                    --#name=格式化消息
                    --@value=待处理的消息
                    return '[' .. value .. ']'
                end
                return player .. ': ' .. format(message)
            end

            welcome('Alex', '你好')
            """;
    private static int stage, ticks, checks;
    private static LuaIdeScreen ide;
    private static MultiLineEditBox editor;
    private static MultilineTextField text;
    private static CompletableFuture<Void> languageReload;
    private static double hoverX, hoverY;

    static void tick(Minecraft client) {
        if (stage == 7 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                client.getLanguageManager().setSelected("zh_cn");
                client.options.languageCode = "zh_cn";
                languageReload = client.reloadResourcePacks();
                stage = 1;
                return;
            }
            if (stage == 1) {
                if (!languageReload.isDone()) return;
                languageReload.join();
                ide = new LuaIdeScreen(client.screen, "");
                client.setScreen(ide);
                set("sourceLoaded", true);
                set("manualOpen", false);
                set("manualProgress", 0F);
                editor = (MultiLineEditBox) get("editor");
                text = (MultilineTextField) invoke("textField");
                editor.active = true;
                ide.setFocused(editor);
                editor.setValue(SOURCE);
                stage = 2;
                return;
            }
            if (stage == 2) {
                int expanded = text.getLineCount();
                clickFold(1);
                require(text.getLineCount() < expanded, "gutter click hides display rows");
                require(text.value().equals(SOURCE) && editor.getValue().equals(SOURCE), "fold preserves complete raw source");
                require((int) invoke("gutterLineAt", new Class<?>[]{double.class}, rowY(1)) == 13, "visible gutter skips folded source rows");
                ((MultilineTextFieldAccessor) text).mcLuaIde$reflowDisplayLines();
                require(text.getLineCount() < expanded, "fold survives reflow");
                text.setSelecting(false);
                text.seekCursor(Whence.ABSOLUTE, SOURCE.indexOf("return '['"));
                require(text.getLineCount() == expanded, "absolute cursor jump unfolds containing function");
                clickFold(6);
                int nestedRows = text.getLineCount();
                require(nestedRows < expanded, "nested function folds independently");
                clickFold(1);
                clickFold(1);
                require(text.getLineCount() == nestedRows, "nested fold retained when parent reopens");
                text.seekCursor(Whence.ABSOLUTE, SOURCE.indexOf("return '['"));
                require(text.getLineCount() == expanded, "nested hidden cursor unfolds nested body");
                clickFold(1);
                text.setSelecting(false);
                text.seekCursor(Whence.ABSOLUTE, 0);
                text.setSelecting(true);
                text.seekCursor(Whence.ABSOLUTE, SOURCE.length());
                require(text.getSelectedText().equals(SOURCE), "select all copies folded code too");
                text.setSelecting(false);
                text.seekCursor(Whence.ABSOLUTE, 0);
                text.insertText("-- prefix\n");
                require(text.value().equals("-- prefix\n" + SOURCE), "editing folded header does not delete body");
                invoke("undo");
                require(text.value().equals(SOURCE), "undo restores original complete source");
                invoke("redo");
                require(text.value().equals("-- prefix\n" + SOURCE), "redo preserves body");
                editor.setValue(SOURCE);
                text.seekCursor(Whence.ABSOLUTE, SOURCE.length());
                text.insertText("wel");
                invoke("refreshSuggestions");
                require(suggestions().contains("welcome"), "user function appears in completion");
                require(invoke("suggestionLabel", new Class<?>[]{String.class}, "welcome").equals("welcome · 欢迎玩家"), "completion displays translated name");
                set("selectedSuggestion", suggestions().indexOf("welcome"));
                ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
                require(text.value().endsWith("welcome()"), "completion inserts raw function name and parentheses");
                editor.setValue(SOURCE + "欢迎");
                text.seekCursor(Whence.ABSOLUTE, text.value().length());
                invoke("refreshSuggestions");
                require(suggestions().contains("welcome"), "Chinese translated name finds function");
                editor.setValue(SOURCE);
                text.seekCursor(Whence.ABSOLUTE, SOURCE.length());
                require(invoke("documentedApiSymbolAround", new Class<?>[]{String.class, int.class}, SOURCE, SOURCE.lastIndexOf("welcome(")).equals("welcome"), "function call has metadata hover target");
                require(invoke("documentedApiSymbolAround", new Class<?>[]{String.class, int.class}, SOURCE, SOURCE.indexOf("player ..")).equals("player"), "variable use has metadata hover target");
                require(invoke("documentedApiSymbolAround", new Class<?>[]{String.class, int.class}, SOURCE, SOURCE.indexOf("目标玩家")) == null, "annotation text is not a code hover target");
                // A nested fold followed by its parent produces a rendered summary for screenshot review.
                text.seekCursor(Whence.ABSOLUTE, SOURCE.indexOf("return '['"));
                clickFold(6);
                clickFold(1);
                invoke("clearSuggestions");
                editor.setScrollAmount(0);
                stage = 3;
                ticks = 0;
                return;
            }
            if (stage == 3 && ++ticks >= 10) {
                screenshot(client, "function-folded.png");
                clickFold(1);
                text.seekCursor(Whence.ABSOLUTE, SOURCE.length());
                text.insertText("wel");
                invoke("refreshSuggestions");
                set("selectedSuggestion", suggestions().indexOf("welcome"));
                editor.setScrollAmount(0);
                stage = 4;
                ticks = 0;
                return;
            }
            if (stage == 4 && ++ticks >= 10) {
                screenshot(client, "function-completion.png");
                editor.setValue(SOURCE);
                text.seekCursor(Whence.ABSOLUTE, SOURCE.length());
                invoke("clearSuggestions");
                editor.setScrollAmount(0);
                int visual = 0;
                for (Object raw : text.iterateLines()) {
                    int begin = ((StringViewAccessor) raw).mcLuaIde$getBeginIndex();
                    if (begin == SOURCE.lastIndexOf("welcome(")) break;
                    visual++;
                }
                double x = editor.getX() + 12, y = rowY(visual);
                require(invoke("apiSymbolAt", new Class<?>[]{MultilineTextField.class, double.class, double.class}, text, x, y).equals("welcome"), "actual hover maps visible row to full-source function");
                double scale = (float) get("interfaceScale") * client.getWindow().getGuiScale();
                hoverX = x * scale;
                hoverY = y * scale;
                setMouse(client);
                stage = 5;
                ticks = 0;
                return;
            }
            if (stage == 5) setMouse(client);
            if (stage == 5 && ++ticks >= 10) {
                screenshot(client, "function-hover.png");
                verifyStructuralFolds();
                verifyViewport();
                stage = 6;
                ticks = 0;
                return;
            }
            if (stage == 6 && ++ticks >= 10) {
                screenshot(client, "editor-horizontal.png");
                System.out.println("Function real editor probe passed: " + checks + " checks; annotations, gutter, nested function/array/if folds, cursor reveal, full-source copy, undo/redo, completion, wrap toggle, horizontal wheel, scaled caret, mouse selection and API hover");
                stage = 7;
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            throw new IllegalStateException("Function real editor probe failed", failure);
        }
    }

    private static void verifyStructuralFolds() throws Exception {
        String source = "local array = {\n 1,\n 2\n}\nif true then\n if false then\n  print(1)\n end\nelse\n print(2)\nend\nprint('after')\n";
        editor.setValue(source);
        int expanded = text.getLineCount();
        clickFold(5);
        require(text.getLineCount() < expanded, "actual if gutter folds all branches");
        require(text.value().equals(source), "if folding preserves raw source");
        require(!visibleOffset(source.indexOf("else")) && !visibleOffset(source.indexOf("end\nprint")), "if fold includes else and matching end");
        require(visibleOffset(source.indexOf("print('after')")), "if fold leaves following line visible");
        text.seekCursor(Whence.ABSOLUTE, source.indexOf("print(2)"));
        require(text.getLineCount() == expanded, "cursor jump reveals hidden else");
        clickFold(1);
        require(text.getLineCount() < expanded && !visibleOffset(source.indexOf(" 1,")), "actual array gutter folds elements");
        require(visibleOffset(source.indexOf("if true")), "array fold leaves following if visible");
        text.seekCursor(Whence.ABSOLUTE, source.indexOf(" 2"));
        require(text.getLineCount() == expanded, "cursor jump reveals array elements");
        clickFold(6); clickFold(5); clickFold(5);
        require(!visibleOffset(source.indexOf("print(1)")), "nested if fold retained after outer reopen");
        text.seekCursor(Whence.ABSOLUTE, source.indexOf("print(1)"));
        require(text.getLineCount() == expanded, "hidden nested if cursor unfolds parents");
    }

    private static boolean visibleOffset(int offset) {
        for (Object raw : text.iterateLines()) {
            var view = (StringViewAccessor) raw;
            if (offset >= view.mcLuaIde$getBeginIndex() && offset < view.mcLuaIde$getEndIndex()) return true;
        }
        return false;
    }

    private static void verifyViewport() throws Exception {
        var settings = (LuaPersonalizationSettings) get("personalization");
        var font = (net.minecraft.client.gui.Font) get("codeFont");
        String source = "local value = " + "1 + ".repeat(160) + "MC.Player.list()\nprint(value)";
        editor.setValue(source);
        require(text.getLineCount() > 2, "long source wraps by default");
        settings.codeWordWrap = false;
        LuaEditorViewport.register(editor, font, false);
        invoke("updateEditorFlowWidth");
        require(text.getLineCount() == 2 && text.value().equals(source), "disabling wrap only changes display lines");
        for (float scale : new float[]{.5F, 1F, 2F}) {
            set("codeScale", scale); settings.cellWidth = 1.3F;
            LuaSyntaxHighlighter.setScale(editor, scale);
            LuaSyntaxHighlighter.setPersonalization(editor, settings);
            invoke("updateEditorFlowWidth");
            text.setSelecting(false); text.seekCursor(Whence.ABSOLUTE, 0);
            require(LuaEditorViewport.offset(editor) == 0, "home reveals left edge");
            editor.setScrollAmount(0);
            require(LuaEditorViewport.scroll(editor, 0, -1, true, 1), "shift wheel handled");
            require(LuaEditorViewport.offset(editor) == 30 && editor.scrollAmount() == 0, "shift wheel moves horizontally only");
            LuaEditorViewport.scroll(editor, 0, 100000, true, 1);
            require(LuaEditorViewport.offset(editor) == 0, "horizontal left edge clamps");
            float ui = (float) get("interfaceScale");
            ide.mouseScrolled((editor.getX() + 10) * ui, (editor.getY() + 10) * ui, -1, 0);
            require(LuaEditorViewport.offset(editor) == 30, "IDE horizontal wheel dispatch");
            text.seekCursor(Whence.ABSOLUTE, source.indexOf('\n'));
            int offset = LuaEditorViewport.offset(editor);
            double caret = font.width(source.substring(0, source.indexOf('\n'))) * scale * settings.cellWidth - offset;
            require(offset > 30 && caret >= 0 && caret <= editor.getWidth() - 8, "scaled caret revealed after End");
            int target = source.indexOf("Player") + 2;
            double x = editor.getX() + 5 + font.width(source.substring(0, target)) * scale * settings.cellWidth - offset + 1;
            ide.mouseClicked(new MouseButtonEvent(x * ui, rowY(0) * ui, new MouseButtonInfo(0, 0)), true);
            require(text.getSelectedText().equals("Player"), "double click selects correct scrolled token");
            target = source.indexOf("list");
            x = editor.getX() + 5 + font.width(source.substring(0, target)) * scale * settings.cellWidth - LuaEditorViewport.offset(editor) + 1;
            require(invoke("apiSymbolAt", new Class<?>[]{MultilineTextField.class, double.class, double.class}, text, x, rowY(0)).equals("MC.Player.list"), "hover identifies scrolled API");
            text.setSelecting(false); text.seekCursor(Whence.ABSOLUTE, 0);
            text.setSelecting(true); text.seekCursor(Whence.ABSOLUTE, source.length());
            require(text.getSelectedText().equals(source), "copy complete unwrapped source");
            text.setSelecting(false);
        }
        settings.codeWordWrap = true;
        LuaEditorViewport.register(editor, font, true); invoke("updateEditorFlowWidth");
        require(text.getLineCount() > 2 && LuaEditorViewport.offset(editor) == 0 && text.value().equals(source), "re-enabling wrap resets horizontal offset without source edit");
        // No fold binding is required for JSON/mcfunction wrapping.
        LuaFunctionFolding.bind(text, null);
        settings.codeWordWrap = false; LuaEditorViewport.register(editor, font, false); invoke("updateEditorFlowWidth");
        editor.setValue("{\"text\":\"" + "测试".repeat(300) + "\"}");
        require(text.getLineCount() == 1, "JSON without function binding supports unwrapped lines");
        settings.cellWidth = 1; set("codeScale", 1F);
        LuaSyntaxHighlighter.setScale(editor, 1); LuaSyntaxHighlighter.setPersonalization(editor, settings);
        LuaFunctionFolding.bind(text, new LuaFunctionFolding.State());
        editor.setValue(source); invoke("clearSuggestions");
        text.seekCursor(Whence.ABSOLUTE, source.indexOf('\n'));
        editor.setScrollAmount(0);
    }

    private static void clickFold(int logicalLine) throws Exception {
        int visual = 0;
        for (Object raw : text.iterateLines()) {
            int begin = ((StringViewAccessor) raw).mcLuaIde$getBeginIndex();
            if (LuaFunctionModel.lineAt(text.value(), begin) == logicalLine) {
                float scale = (float) get("interfaceScale");
                boolean handled = ide.mouseClicked(new MouseButtonEvent((editor.getX() - 5) * scale,
                        rowY(visual) * scale, new MouseButtonInfo(0, 0)), false);
                require(handled, "fold mouse input handled on line " + logicalLine);
                return;
            }
            visual++;
        }
        throw new AssertionError("No visible header line " + logicalLine);
    }

    private static double rowY(int visual) throws Exception {
        int height = (int) invoke("scaledCodeLineHeight");
        return editor.getY() + 4 + (visual + 0.5) * height - editor.scrollAmount();
    }
    @SuppressWarnings("unchecked") private static List<String> suggestions() throws Exception { return (List<String>) get("suggestions"); }
    private static Object get(String name) throws Exception { Field field = LuaIdeScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(ide); }
    private static void set(String name, Object value) throws Exception { Field field = LuaIdeScreen.class.getDeclaredField(name); field.setAccessible(true); field.set(ide, value); }
    private static Object invoke(String name) throws Exception { return invoke(name, new Class<?>[0]); }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception { Method method = LuaIdeScreen.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(ide, args); }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void screenshot(Minecraft client, String name) { net.minecraft.client.Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), 1, ignored -> { }); }
    private static void setMouse(Minecraft client) throws Exception {
        // The isolated window may be unfocused; set its own render input without moving the OS cursor.
        Field x = client.mouseHandler.getClass().getDeclaredField("xpos"), y = client.mouseHandler.getClass().getDeclaredField("ypos");
        x.setAccessible(true); y.setAccessible(true);
        x.setDouble(client.mouseHandler, hoverX); y.setDouble(client.mouseHandler, hoverY);
    }
}
