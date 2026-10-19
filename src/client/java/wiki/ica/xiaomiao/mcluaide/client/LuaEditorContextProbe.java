package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in UI regression confined to build/editor-context-probe. Restores the original clipboard. */
final class LuaEditorContextProbe {
    private static final String SOURCE = "local alpha = 1\nprint(alpha)\n";
    private static int stage, checks;
    private static long started;
    private static CompletableFuture<Void> saved;
    private static LuaIdeScreen ide;
    private static String originalClipboard;

    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                require(client.gameDirectory.toPath().toAbsolutePath().toString().contains("editor-context-probe"), "Unsafe probe directory");
                originalClipboard = client.keyboardHandler.getClipboard(); started = System.nanoTime(); stage = 1;
                client.options.guiScale().set(1); client.resizeGui(); client.options.renderDistance().set(2); client.options.simulationDistance().set(2);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Probe cancelled"); }); return;
            }
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(180), "Editor context probe timeout stage " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("editor_context_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                var method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true); method.invoke(create); stage = 2; return;
            }
            if (stage == 2) {
                if (client.player == null || !ScriptManager.isServerAvailable()) return;
                saved = ScriptManager.save("context_probe", SOURCE); stage = 3; return;
            }
            if (stage == 3) {
                if (!saved.isDone()) return; saved.join();
                ide = new LuaIdeScreen(null, "context_probe"); client.setScreen(ide); stage = 4; return;
            }
            if (stage == 4) {
                if (!Boolean.TRUE.equals(get("sourceLoaded"))) return;
                require(editor().getValue().equals(SOURCE), "Wrong fixture source");
                select(6, 11); rightAtSelection();
                var selected = LuaIdeScreen.class.getDeclaredMethod("selectedEditorText"); selected.setAccessible(true);
                require(menu().isOpen() && selected.invoke(ide).equals("alpha"), "Right click lost the selection");
                stage = 5; return; // Allow the open menu to actually render for a frame.
            }
            if (stage == 5) {
                action(LuaEditorContextMenu.Action.COPY);
                require(client.keyboardHandler.getClipboard().equals("alpha") && editor().getValue().equals(SOURCE), "Copy changed source or copied wrong selection");
                select(6, 11); rightAtSelection(); action(LuaEditorContextMenu.Action.CUT);
                require(editor().getValue().equals("local  = 1\nprint(alpha)\n"), "Cut did not delete selection");
                rightAtCaret(); action(LuaEditorContextMenu.Action.UNDO);
                require(editor().getValue().equals(SOURCE), "Cut undo did not restore source");
                select(6, 11); rightAtSelection(); action(LuaEditorContextMenu.Action.DELETE);
                require(editor().getValue().equals("local  = 1\nprint(alpha)\n"), "Delete did not delete selection");
                rightAtCaret(); action(LuaEditorContextMenu.Action.UNDO);
                require(editor().getValue().equals(SOURCE), "Delete undo did not restore source");
                client.keyboardHandler.setClipboard("beta"); select(6, 11); rightAtSelection(); action(LuaEditorContextMenu.Action.PASTE);
                require(editor().getValue().equals("local beta = 1\nprint(alpha)\n"), "Paste did not replace selection");
                rightAtCaret(); action(LuaEditorContextMenu.Action.UNDO);
                require(editor().getValue().equals(SOURCE), "Paste undo did not restore source");
                select(6, 11); rightAtSelection(); action(LuaEditorContextMenu.Action.SEARCH);
                require(Boolean.TRUE.equals(get("searchOpen")) && get("searchQuery").equals("alpha"), "Search failed to use selection");
                invoke("closeSearch");
                field().setSelecting(false); field().seekCursor(Whence.ABSOLUTE, 0); rightAtCaret();
                require(!menu().enabled(LuaEditorContextMenu.Action.COPY) && !menu().enabled(LuaEditorContextMenu.Action.CUT)
                        && !menu().enabled(LuaEditorContextMenu.Action.DELETE), "Selection-only actions enabled without a selection");
                ide.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
                require(!menu().isOpen() && client.screen == ide, "Escape closed IDE instead of popup");
                rightAtCaret(); click(2, ide.height - 2, 0);
                require(!menu().isOpen(), "Outside click did not dismiss popup");
                select(6, 11); rightAtSelection();
                click(menu().x() + 8, menu().y() + 3 + menu().rowHeight() / 2.0, 0);
                require(!menu().isOpen() && editor().isFocused(), "Click did not return editor focus");
                // Right-click elsewhere must move the cursor rather than operate on an old selection.
                select(6, 11);
                click(editor().getX() + 4 + ((net.minecraft.client.gui.Font)get("codeFont")).width("local alpha = "), editor().getY() + 7, 1);
                require(menu().isOpen() && !field().hasSelection() && field().cursor() >= 13, "Right click outside selection did not move caret");
                ide.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
                stage = 99; client.keyboardHandler.setClipboard(originalClipboard); client.setScreen(null);
                System.out.println("Editor context real-game probe passed: " + checks + " checks; copy/cut/paste/delete/undo/search, selection preservation, outside caret, disabled actions, focus, Escape, and outside clicks");
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            int failed = stage; stage = 99;
            if (originalClipboard != null) client.keyboardHandler.setClipboard(originalClipboard);
            client.stop(); throw new IllegalStateException("Editor context probe failed at " + failed, failure);
        }
    }
    private static MultiLineEditBox editor() throws Exception { return (MultiLineEditBox)get("editor"); }
    private static MultilineTextField field() throws Exception { return ((MultiLineEditBoxAccessor)editor()).mcLuaIde$getTextField(); }
    private static LuaEditorContextMenu menu() throws Exception { return (LuaEditorContextMenu)get("editorContextMenu"); }
    private static Object get(String name) throws Exception { var member = LuaIdeScreen.class.getDeclaredField(name); member.setAccessible(true); return member.get(ide); }
    private static void invoke(String name) throws Exception { var member = LuaIdeScreen.class.getDeclaredMethod(name); member.setAccessible(true); member.invoke(ide); }
    private static void select(int start, int end) throws Exception {
        field().setSelecting(false); field().seekCursor(Whence.ABSOLUTE, start);
        field().setSelecting(true); field().seekCursor(Whence.ABSOLUTE, end); field().setSelecting(false);
    }
    private static void rightAtSelection() throws Exception {
        var font = (net.minecraft.client.gui.Font)get("codeFont");
        click(editor().getX() + 4 + font.width("local al"), editor().getY() + 7, 1);
    }
    private static void rightAtCaret() throws Exception {
        // The first-line blank after 'local' is outside the selected token, but remains a valid editor target.
        click(editor().getX() + 5, editor().getY() + 7, 1);
    }
    private static void action(LuaEditorContextMenu.Action action) throws Exception {
        require(menu().enabled(action), "Action unexpectedly disabled: " + action);
        click(menu().x() + 10, menu().y() + 3 + action.ordinal() * menu().rowHeight() + menu().rowHeight() / 2.0, 0);
        require(!menu().isOpen(), "Action did not close popup: " + action);
    }
    private static void click(double x, double y, int button) throws Exception {
        float scale = (float)get("interfaceScale");
        var event = new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(button, 0));
        boolean menuHit = menu().contains(x, y);
        boolean handled = ide.mouseClicked(event, false);
        if (button == 1 || menuHit) require(handled, "Mouse event was not consumed: " + button + " at " + x + "," + y);
        ide.mouseReleased(event);
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
