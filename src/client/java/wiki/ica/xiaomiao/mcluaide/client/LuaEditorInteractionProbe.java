package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultilineTextFieldAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.StringViewAccessor;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

/** Opt-in native editor regression probe; never touches a user save/configuration. */
final class LuaEditorInteractionProbe {
    private static int stage;
    private static long started, until;
    private static CompletableFuture<Void> reload;
    private static LuaIdeScreen ide;
    private static int checks;

    static void tick(Minecraft client) {
        if (stage == 3 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                started = System.nanoTime();
                client.getLanguageManager().setSelected("zh_cn"); client.options.languageCode = "zh_cn";
                client.options.guiScale().set(1); client.resizeGui();
                reload = client.reloadResourcePacks(); stage = 1; return;
            }
            require(System.nanoTime() - started < 50_000_000_000L, "Probe timed out at stage " + stage);
            if (stage == 1) {
                if (!reload.isDone()) return;
                reload.join();
                var document = new LuaIdeSession.Document("editor_probe", false, false, 0, 0);
                new LuaIdeSession(client.gameDirectory.toPath().resolve("config/mc_lua_ide"), "local")
                        .save(new LuaIdeSession.State(java.util.List.of(document), document.key()));
                ide = new LuaIdeScreen(client.screen, "editor_probe"); client.setScreen(ide);
                var open = LuaIdeScreen.class.getDeclaredMethod("openDocument", String.class, boolean.class);
                open.setAccessible(true); open.invoke(ide, "editor_probe", false);
                set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
                set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
				// The probe saves/reopens API browsing later; close any window saved
				// by an earlier probe before exercising mouse/scrollbar hit tests.
				((LuaApiDocsOverlay) value(ide, "apiDocsOverlay")).close();
                var previousInsert = (LuaInsertOverlay) value(ide, "insertOverlay");
                previousInsert.close(); set(previousInsert, "progress", 0F);
                var previousComponents = (LuaComponentEditorOverlay) value(ide, "componentEditorOverlay");
                previousComponents.close(); set(previousComponents, "progress", 0F);
                LuaFileOverlay savedFiles = (LuaFileOverlay) value(ide, "fileOverlay");
                savedFiles.close(); set(savedFiles, "progress", 0F);
                set(ide, "outputVisible", false); set(ide, "outputProgress", 0F); set(ide, "layoutLocked", false);
                set(ide, "animatedLeftDockExtent", -1F); set(ide, "animatedRightDockExtent", -1F); set(ide, "animatedBottomDockExtent", -1F);
                var initialLayout = LuaIdeScreen.class.getDeclaredMethod("applyDockLayout"); initialLayout.setAccessible(true); initialLayout.invoke(ide);
                for (float uiScale : new float[]{1F, 1.25F}) {
                    set(ide, "pendingInterfaceScale", uiScale); ide.tick();
                    for (float codeScale : new float[]{1F, 1.35F, 2F}) verify(uiScale, codeScale);
                }
                set(ide, "pendingInterfaceScale", 1F); ide.tick();
                verifyWrapped();
                verify(1F, 1F);
                verifyDockedScrollbars();
                verifyHoverSuppression();
                verifyIndentationAndModules();
                verifyUndoCursor();
                verifyWindowShortcutsAndFontZoom();
                verifyInputMethodFocus(client);
                verifyLogCopy(client);
                verifyFoldingMenusAndSession(client);
                verifyApiBrowsing(client);
				setValidationSource("local value = 1\ndo\n    local value = 2\nend\nMC.Chat.broadcast(false)\n");
                stage = 2; until = System.nanoTime() + 700_000_000L; return;
            }
            if (stage == 2 && System.nanoTime() >= until) {
                LuaApiDocsOverlay docs = (LuaApiDocsOverlay) value(ide, "apiDocsOverlay");
                java.util.Properties state = new java.util.Properties(); docs.captureNavigation(state);
                require(state.getProperty("apiDocs.navigation.treeScroll").equals("4")
                        && state.getProperty("apiDocs.navigation.contentScroll").equals("64"),
                        "Rendering reopened API window lost a valid browsing scroll position");
				if (validationPending()) return;
				require(value(ide, "errorLineNumbers").equals(java.util.Set.of(5)), "Semantic error gutter not on argument line");
				require(value(ide, "warningLineNumbers").equals(java.util.Set.of(3)), "Shadow warning gutter not on declaration line");
				require(((String) value(ide, "syntaxMessage")).contains("1 个错误 / 1 个警告"), "Errors and warnings not counted separately");
				verifyDiagnosticColor("value", 3, 0xE6AD45);
				verifyDiagnosticColor("false", 5, 0xFF5555);
				setValidationSource("local value = 1\ndo\n    local value = 2\nend\nprint(value)\n");
				stage = 4; until = System.nanoTime() + 700_000_000L; return;
			}
			if (stage == 4 && System.nanoTime() >= until) {
				if (validationPending()) return;
				require(value(ide, "errorLineNumbers").equals(java.util.Set.of()), "Warning-only source still has error gutter");
				require(value(ide, "warningLineNumbers").equals(java.util.Set.of(3)), "Valid-with-warning branch cleared warnings");
				require(((String) value(ide, "syntaxMessage")).contains("1 个警告"), "Warning-only status not displayed");
				require((int) value(ide, "syntaxColor") == 0xFFE6AD45, "Warning-only status is not amber");
				setValidationSource("local value = 1\nprint(value)\n");
				stage = 5; until = System.nanoTime() + 700_000_000L; return;
			}
			if (stage == 5 && System.nanoTime() >= until) {
				if (validationPending()) return;
				require(value(ide, "errorLineNumbers").equals(java.util.Set.of())
						&& value(ide, "warningLineNumbers").equals(java.util.Set.of()), "Fixed source left stale diagnostic gutters");
				require(((java.util.List<?>) value(ide, "editorErrorRanges")).isEmpty(), "Fixed source left stale colored ranges");
				require(((String) value(ide, "syntaxMessage")).contains("检查通过"), "Fixed source did not restore success status");
                net.minecraft.client.Screenshot.grab(client.gameDirectory, "editor-selection.png",
                        client.getMainRenderTarget(), 1, ignored -> { });
                System.out.println("Editor interaction real UI probe passed: " + checks
                        + " checks; selections, drag-hover suppression, scrollbars/dock splitters, indentation, module Tab completion, API browsing and diagnostics");
                stage = 3; client.stop();
            }
        } catch (Exception | AssertionError error) {
            stage = 3; client.stop(); throw new IllegalStateException("Editor interaction probe failed", error);
        }
    }

	private static void setValidationSource(String source) throws Exception {
		set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
		MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
		editor.active = editor.visible = true; editor.setValue(source);
		var validate = LuaIdeScreen.class.getDeclaredMethod("scheduleValidation", String.class);
		validate.setAccessible(true); validate.invoke(ide, source);
	}

	private static boolean validationPending() throws Exception {
		return ((String) value(ide, "syntaxMessage")).contains("正在检查");
	}

	@SuppressWarnings("unchecked")
	private static void verifyIndentationAndModules() throws Exception {
		MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
		MultilineTextField text = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
		prepareCompletion("MC.Event.onBlockPlaced(function(event)|");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		require(text.value().endsWith("\n    ") && !text.value().endsWith("\n        "), "Callback Enter inserted eight spaces");
		text.insertText("if event.x then");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		require(text.value().endsWith("\n        "), "Nested callback block not indented eight spaces");
		text.insertText("end");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		require(text.value().endsWith("    end\n    "), "Closing nested block did not dedent to four spaces");
		text.insertText("end)");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		require(text.value().endsWith("\nend)\n"), "Closing callback did not dedent to top level");
		prepareCompletion("function f()\n    |");
		var insert = LuaIdeScreen.class.getDeclaredMethod("insertComponentPatch", String.class);
		insert.setAccessible(true); insert.invoke(ide, "{\nvalue = 1\n}");
		require(text.value().equals("function f()\n    {\n        value = 1\n    }"), "Component insertion doubled existing indentation: " + text.value());

		// Use a loaded, unsaved tab as the module source; no user file is read or changed.
		Class<?> documentType = Class.forName(LuaIdeScreen.class.getName() + "$OpenDocument");
		var constructor = documentType.getDeclaredConstructor(String.class, boolean.class, boolean.class);
		constructor.setAccessible(true);
		Object module = constructor.newInstance("probe/util", false, false);
		set(module, "loaded", true);
		set(module, "source", "local M={}\nfunction M.greet(name)\n--#name=问候\nreturn name\nend\nfunction M:reset(value) end\nreturn M");
		((java.util.Map<String, Object>) value(ide, "openDocuments")).put("lua:probe/util", module);
		String imported = "local util = MC.Script.require('probe.util')\n";
		prepareCompletion(imported + "util.gr|");
		chooseCompletion("util.greet");
		var label = LuaIdeScreen.class.getDeclaredMethod("suggestionLabel", String.class); label.setAccessible(true);
		require(((String) label.invoke(ide, "util.greet")).contains("问候"), "Module Chinese description missing in real popup");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
		require(text.value().equals(imported + "util.greet()") && text.cursor() == text.value().length() - 1,
				"Module Tab did not insert call/position cursor in parentheses: " + text.value());
		prepareCompletion(imported + "util.gr|('x')"); chooseCompletion("util.greet");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
		require(text.value().equals(imported + "util.greet('x')"), "Module Tab duplicated existing call parentheses");
		prepareCompletion(imported + "MC.Event.onItemUsed(util.gr|)"); chooseCompletion("util.greet");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
		require(text.value().equals(imported + "MC.Event.onItemUsed(util.greet)"), "Callback reference completion called function instead of passing it");
		prepareCompletion(imported + "print(util.gr|)"); chooseCompletion("util.greet");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
		require(text.value().equals(imported + "print(util.greet())"), "Module completion inside generic call failed");
		prepareCompletion(imported + "util:re|"); chooseCompletion("util:reset");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
		require(text.value().equals(imported + "util:reset()"), "Module method colon completion failed");
		prepareCompletion(imported + "do\nlocal util={}\nutil.gr|\nend");
		require(!((java.util.List<String>) value(ide, "suggestions")).contains("util.greet"), "Module completion ignored local shadowing");
		set(module, "source", "return { changed = function() end }");
		prepareCompletion(imported + "util.|"); chooseCompletion("util.changed");
		require(!((java.util.List<String>) value(ide, "suggestions")).contains("util.greet"), "Unsaved module edit left stale function completion");
		((java.util.Map<String, Object>) value(ide, "openDocuments")).remove("lua:probe/util");
	}
	private static void prepareCompletion(String marked) throws Exception {
		int caret = marked.indexOf('|');
		setValidationSource(marked.replace("|", ""));
		MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
		ide.setFocused(editor);
		MultilineTextField text = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
		text.setSelecting(false); text.seekCursor(Whence.ABSOLUTE, caret);
		var refresh = LuaIdeScreen.class.getDeclaredMethod("refreshSuggestions"); refresh.setAccessible(true); refresh.invoke(ide);
	}
	private static void chooseCompletion(String completion) throws Exception {
		java.util.List<?> suggestions = (java.util.List<?>) value(ide, "suggestions");
		int index = suggestions.indexOf(completion);
		require(index >= 0, "Module candidate missing: " + completion + " in " + suggestions);
		set(ide, "selectedSuggestion", index);
	}

	private static void verifyDiagnosticColor(String text, int line, int rgb) throws Exception {
		MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
		String source = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField().value();
		int begin = 0;
		for (int current = 1; current < line; current++) begin = source.indexOf('\n', begin) + 1;
		int position = source.indexOf(text, begin);
		var colored = LuaSyntaxHighlighter.highlight(editor, source, text, position, line);
		var color = colored.getSiblings().getFirst().getStyle().getColor();
		require(color != null && (color.getValue() & 0xFFFFFF) == rgb, "Wrong semantic diagnostic color for " + text);
	}

    private static void verifyFoldingMenusAndSession(Minecraft client) throws Exception {
        String source = "local values = {\n    1,\n    2\n}\nif values then\n    print(values)\nend\n"
                + "local function outer()\n    local function inner()\n        return 42\n    end\n    return inner()\nend\n";
        var document = value(ide, "currentDocument");
        set(document, "loaded", true); set(document, "loading", false); set(document, "pendingFolds", null);
        set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
        var editor = (MultiLineEditBox) value(ide, "editor"); editor.active = editor.visible = true;
        editor.setValue(source); set(ide, "lastSavedSource", source); set(document, "savedSource", source);
        var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        field.seekCursor(Whence.ABSOLUTE, source.indexOf("return 42"));
        pressFoldingMenu(2);
        var state = LuaFunctionFolding.state(field); var model = state.model(source);
        require(model.functions.stream().allMatch(state::collapsed), "Real function-fold menu leaves a nested function expanded");
        require(model.folds.stream().filter(f -> !(f instanceof LuaFunctionModel.Function)).noneMatch(state::collapsed),
                "Function-only menu also folds if/table");
        require(field.cursor() == model.functions.getFirst().start(), "Bulk fold left caret in hidden code");
        pressFoldingMenu(0);
        require(state.snapshot(source).starts().isEmpty(), "Real expand-all menu leaves hidden nested folds");
        pressFoldingMenu(1);
        var expected = state.snapshot(source);
        require(expected.starts().size() == model.folds.size() && editor.getValue().equals(source), "Collapse-all changed source or missed if/table/functions");
        require(field.getLineCount() < source.lines().count(), "Bulk action did not reflow real editor display rows");
        require(editor.active, "Closing folding menu left editor disabled");
        ide.onClose();
        require(client.screen != ide, "Clean IDE close failed");
        ide = new LuaIdeScreen(null, "editor_probe"); client.setScreen(ide);
        document = value(ide, "currentDocument");
        require(expected.equals(value(document, "pendingFolds")), "Closing/reopening IDE did not load persisted fold metadata");
        set(document, "source", source); set(document, "savedSource", source); set(document, "lastSnapshot", source);
        set(document, "loaded", true); set(document, "loading", false);
        var restore = LuaIdeScreen.class.getDeclaredMethod("restoreCurrentDocument"); restore.setAccessible(true); restore.invoke(ide);
        editor = (MultiLineEditBox) value(ide, "editor"); field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        state = LuaFunctionFolding.state(field);
        require(state.snapshot(source).equals(expected) && field.getLineCount() < source.lines().count(),
                "Restored file did not restore all nested folds/display rows");
        require(value(document, "pendingFolds") == null, "Fold restore was not consumed after text loaded");
        // Recreating widgets must not reset per-document folds.
        client.resizeGui();
        editor = (MultiLineEditBox) value(ide, "editor"); field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        require(LuaFunctionFolding.state(field).snapshot(source).equals(expected), "Widget resize/rebuild lost folds");
        var open = LuaIdeScreen.class.getDeclaredMethod("openDocument", String.class, boolean.class); open.setAccessible(true);
        open.invoke(ide, "folding_second", false);
        Object second = value(ide, "currentDocument");
        String secondSource = "local function second()\n    print('second')\nend\n";
        set(second, "source", secondSource); set(second, "savedSource", secondSource); set(second, "lastSnapshot", secondSource);
        set(second, "loaded", true); set(second, "loading", false); restore.invoke(ide);
        pressFoldingMenu(1);
        open.invoke(ide, "editor_probe", false);
        require(LuaFunctionFolding.state(field).snapshot(source).equals(expected), "Switching files lost first document folds");
        open.invoke(ide, "folding_second", false);
        require(LuaFunctionFolding.state(field).snapshot(secondSource).starts().size() == 1, "Switching files lost second document folds");
        open.invoke(ide, "editor_probe", false);
        pressFoldingMenu(0);
        require(LuaFunctionFolding.state(field).snapshot(source).starts().isEmpty() && editor.getValue().equals(source),
                "Reopened file cannot expand without changing source");
        set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
        System.out.println("Folding real IDE probe passed: submenu mouse clicks, function-only/all/expand, nested folds, caret, source preservation, close/reopen and widget rebuild");
    }

    @SuppressWarnings("unchecked")
    private static void pressFoldingMenu(int index) throws Exception {
        var toggle = LuaIdeScreen.class.getDeclaredMethod("toggleMenu", String.class); toggle.setAccessible(true); toggle.invoke(ide, "tools");
        var tools = (java.util.List<net.minecraft.client.gui.components.Button>) value(ide, "toolMenuItems");
        var parent = tools.stream().filter(button -> button.getMessage().getString().contains("代码折叠")).findFirst().orElseThrow();
        float scale = (float) value(ide, "interfaceScale");
        require(ide.mouseClicked(new MouseButtonEvent((parent.getX()+5)*scale, (parent.getY()+5)*scale, new MouseButtonInfo(0,0)), false),
                "Folding submenu parent was not clickable");
        var items = (java.util.List<net.minecraft.client.gui.components.Button>) value(ide, "foldingMenuItems");
        require(items.size() == 3 && items.stream().allMatch(button -> button.visible), "Folding submenu not fully visible");
        var button = items.get(index);
        require(ide.mouseClicked(new MouseButtonEvent((button.getX()+5)*scale, (button.getY()+5)*scale, new MouseButtonInfo(0,0)), false),
                "Folding submenu child outside parent was not clickable");
        require(items.stream().noneMatch(item -> item.visible), "Folding action did not dismiss menus");
    }

    private static void verifyApiBrowsing(Minecraft client) throws Exception {
        LuaApiDocsOverlay docs = (LuaApiDocsOverlay) value(ide, "apiDocsOverlay");
        docs.setDockSide(DockSide.FLOATING);
        docs.restoreNavigation(new java.util.Properties()); docs.open();
        java.util.Properties state = new java.util.Properties(); docs.captureNavigation(state);
        require(state.getProperty("apiDocs.navigation.rootExpanded").equals("false")
                && state.getProperty("apiDocs.navigation.sections.count").equals("0")
                && state.getProperty("apiDocs.navigation.groups.count").equals("0"), "Real API window does not start collapsed");
        require(docs.openAt("MC.Event.onScriptStopped"), "Real API direct jump failed");
        docs.restoreGeometry(new WindowGeometry(110, 60, 660, 360));
        state.clear(); docs.captureNavigation(state);
        state.setProperty("apiDocs.navigation.treeScroll", "4");
        state.setProperty("apiDocs.navigation.contentScroll", "64");
        docs.restoreNavigation(state);
        docs.close(); docs.open();
        java.util.Properties reopened = new java.util.Properties(); docs.captureNavigation(reopened);
        require(state.equals(reopened), "Closing API window lost browsing state");
        WindowGeometry geometry = docs.captureGeometry();
        // These isolated probe directories never contain user documents/settings.
        client.setScreen(null);
        ide = new LuaIdeScreen(null, "editor_probe"); client.setScreen(ide);
        docs = (LuaApiDocsOverlay) value(ide, "apiDocsOverlay");
        reopened.clear(); docs.captureNavigation(reopened);
        require(docs.isOpen() && state.equals(reopened), "Closing/recreating the IDE lost saved API browsing state");
        require(geometry.equals(docs.captureGeometry()), "Recreating the IDE lost the API window geometry");
    }

    private static void verifyWrapped() throws Exception {
        set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
        MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
        editor.active = editor.visible = true;
        MultilineTextField field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        var access = (MultilineTextFieldAccessor) field;
        Font font = (Font) value(ide, "codeFont");
        var settings = (LuaPersonalizationSettings) value(ide, "personalization");
        settings.codeWordWrap = true;
        set(ide, "codeScale", 1F); LuaSyntaxHighlighter.setScale(editor, 1F);
        LuaSyntaxHighlighter.setPersonalization(editor, settings);
        LuaEditorViewport.register(editor, font, true);
        access.mcLuaIde$setWidth(LuaEditorViewport.flowWidth(true, editor.getWidth(), settings.cellWidth));
        editor.setValue("Player ".repeat(200)); access.mcLuaIde$reflowDisplayLines(); editor.setScrollAmount(0);
        require(field.getLineCount() > 1, "Wrap did not generate visual rows");
        require(LuaEditorViewport.offset(editor) == 0 && LuaEditorViewport.horizontalScrollbar(editor) == null,
                "Wrapped editor has horizontal scroll");
        var second = (StringViewAccessor) access.mcLuaIde$getDisplayLines().get(1);
        int begin = second.mcLuaIde$getBeginIndex(), end = begin + 2;
        double x = editor.getX() + 4 + font.width(field.value().substring(begin, end)) * settings.cellWidth + .2;
        int rowHeight = LuaEditorViewport.lineHeight(editor);
        click(editor.getX() + 4 + .2, editor.getY() + 4 + rowHeight / 2.0, 1F, false);
        drag(x, editor.getY() + 4 + rowHeight * 1.5, 1F); release(x, editor.getY() + 4 + rowHeight * 1.5, 1F);
        selection(field, 0, end, "Wrapped cross-row drag");
        require(LuaEditorViewport.selectionRectangles(editor).size() == 2, "Wrapped selection geometry");
        select(field, 0, 6);
        require(!LuaEditorViewport.occurrenceRectangles(editor).isEmpty(), "Wrapped occurrences");
        ide.verifyDoubleClickForTesting();
        require(field.getSelectedText().equals("5"), "Existing smart selection regression");
    }

    private static void verify(float uiScale, float codeScale) throws Exception {
        set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
        set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
        MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
        MultilineTextField field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        Font font = (Font) value(ide, "codeFont");
        LuaPersonalizationSettings settings = (LuaPersonalizationSettings) value(ide, "personalization");
        settings.codeWordWrap = false;
        settings.lineHeight = 1.23F;
        set(ide, "codeScale", codeScale);
        LuaSyntaxHighlighter.setScale(editor, codeScale); LuaSyntaxHighlighter.setPersonalization(editor, settings);
        LuaEditorViewport.register(editor, font, false);
        var access = (MultilineTextFieldAccessor) field;
        access.mcLuaIde$setWidth(LuaEditorViewport.flowWidth(false, editor.getWidth(), codeScale));
        String prefix = "a".repeat(259) + " ";
        String row = prefix + "Player Player player Player";
        editor.active = editor.visible = true;
        editor.setValue((row + "\n").repeat(80)); access.mcLuaIde$reflowDisplayLines();
        editor.setScrollAmount(0);
        float widthScale = codeScale * LuaSyntaxHighlighter.cellWidth(editor);
        int start = prefix.length(), end = start + 6;
        LuaEditorViewport.scroll(editor, -font.width(prefix) * widthScale / 30 + 3, 0, false, 1);
        int offset = LuaEditorViewport.offset(editor);
        double xStart = editor.getX() + 4 - offset + font.width(prefix) * widthScale + .2;
        double xEnd = editor.getX() + 4 - offset + font.width(row.substring(0, end)) * widthScale + .2;
        double y = editor.getY() + 4 + LuaEditorViewport.lineHeight(editor) / 2.0;
        click(xStart, y, uiScale, false); drag(xEnd, y, uiScale); release(xEnd, y, uiScale);
        selection(field, start, end, "Long-line forward drag");
        require(LuaEditorViewport.offset(editor) == offset, "Inside click changed horizontal offset");
        require(LuaEditorViewport.occurrenceRectangles(editor).size() > 2, "Same text occurrences not highlighted");
        click(xEnd, y, uiScale, false); drag(xStart, y, uiScale); release(xStart, y, uiScale);
        selection(field, start, end, "Long-line reversed drag");
        click(xStart + font.width("Pla") * widthScale, y, uiScale, true);
        selection(field, start, end, "Long-line double-click");
        release(xStart, y, uiScale);

        // A two-row selection must reach the long row's real end, not viewportWidth - padding.
        select(field, start, row.length() + 1 + end);
        editor.setScrollAmount(0);
        var rectangles = LuaEditorViewport.selectionRectangles(editor);
        require(rectangles.size() == 2, "Multiline rectangles count");
        require(rectangles.getFirst().right() >= editor.getX() + 4 + Math.round(font.width(row) * widthScale),
                "Long multiline selection clipped in document coordinates");
        // This is a lower, partially scrolled row: input must match the rounded rendered line height.
        editor.setScrollAmount(37.5);
        int visualRow = 10;
        y = editor.getY() + 4 + visualRow * LuaEditorViewport.lineHeight(editor) - editor.scrollAmount()
                + LuaEditorViewport.lineHeight(editor) - .1;
        if (y < editor.getBottom() - 5) {
            offset = LuaEditorViewport.offset(editor);
            xStart = editor.getX() + 4 - offset + font.width(prefix) * widthScale + .2;
            click(xStart, y, uiScale, false); release(xStart, y, uiScale);
            require(field.cursor() == visualRow * (row.length() + 1) + start, "Fractional scaled row hit test");
        }

        select(field, start, end); editor.setScrollAmount(0);
        int anchor = access.mcLuaIde$getSelectCursor(), cursor = field.cursor();
        var horizontal = LuaEditorViewport.horizontalScrollbar(editor);
        require(horizontal != null, "Missing horizontal scrollbar");
        double hx = horizontal.thumbStart() + horizontal.thumbSize() / 2.0, hy = horizontal.top() + 2;
        click(hx, hy, uiScale, false);
        drag(editor.getRight() + 100, hy, uiScale); release(editor.getRight() + 100, hy, uiScale);
        require(LuaEditorViewport.offset(editor) > 0, "Horizontal drag failed");
        selection(field, start, end, "Scrollbar preserves selected text");
        click(horizontal.left() + 1, hy, uiScale, false);
        drag(editor.getX() - 100, hy, uiScale); release(editor.getX() - 100, hy, uiScale);
        require(LuaEditorViewport.offset(editor) == 0, "Horizontal drag outside viewport did not clamp");
        // Scrollbar track click and drag must not invoke onClick's cursor movement.
        var vertical = LuaEditorViewport.verticalScrollbar(editor);
        require(vertical != null, "Missing vertical scrollbar");
        double vx = vertical.left() + 2;
        click(vx, vertical.bottom() - 20, uiScale, false);
        require(editor.scrollAmount() > 0, "Vertical track click failed");
        drag(vx, editor.getBottom() + 100, uiScale); release(vx, editor.getBottom() + 100, uiScale);
        require(editor.scrollAmount() == editor.maxScrollAmount(), "Vertical drag to bottom failed");
        require(field.cursor() == cursor && access.mcLuaIde$getSelectCursor() == anchor, "Vertical bar changed selection");
        vertical = LuaEditorViewport.verticalScrollbar(editor);
        click(vx, vertical.thumbStart() + 2, uiScale, false);
        drag(vx, editor.getY() - 100, uiScale); release(vx, editor.getY() - 100, uiScale);
        require(editor.scrollAmount() == 0, "Vertical drag outside viewport did not clamp");
        // Keyboard/selection still works after releasing a scrollbar outside the editor.
        field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, start);
        require(field.cursor() == start, "Cursor stuck after scrollbar release");
        select(field, start, end); editor.setScrollAmount(0);
        require(!LuaEditorViewport.occurrenceRectangles(editor).isEmpty(), "Occurrences after scroll/cursor change");
        field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, start);
        require(LuaEditorViewport.occurrenceRectangles(editor).isEmpty(), "Deselection left stale highlights");
        select(field, start, end); editor.setScrollAmount(0);
    }

    private static void verifyDockedScrollbars() throws Exception {
        Class<?> toolType = Class.forName(LuaIdeScreen.class.getName() + "$ToolWindow");
        var filesField = toolType.getDeclaredField("FILES"); filesField.setAccessible(true);
        var outputField = toolType.getDeclaredField("OUTPUT"); outputField.setAccessible(true);
        var dock = LuaIdeScreen.class.getDeclaredMethod("dockTool", toolType, DockSide.class); dock.setAccessible(true);
        var layout = LuaIdeScreen.class.getDeclaredMethod("applyDockLayout"); layout.setAccessible(true);
        var hit = LuaIdeScreen.class.getDeclaredMethod("dockResizeSideAt", double.class, double.class); hit.setAccessible(true);
        var nativeBar = net.minecraft.client.gui.components.AbstractTextAreaWidget.class.getDeclaredMethod("scrollBarX");
        nativeBar.setAccessible(true);
        for (float scale : new float[]{1F, 1.25F}) {
            set(ide, "pendingInterfaceScale", scale); ide.tick();
            var settings = (LuaPersonalizationSettings) value(ide, "personalization");
            LuaFileOverlay files = (LuaFileOverlay) value(ide, "fileOverlay");
            files.open(); set(files, "progress", 1F);
            set(ide, "outputVisible", true); set(ide, "outputProgress", 1F);
            dock.invoke(ide, filesField.get(null), DockSide.RIGHT);
            dock.invoke(ide, outputField.get(null), DockSide.BOTTOM);
            set(ide, "animatedRightDockExtent", -1F); set(ide, "animatedBottomDockExtent", -1F);
            layout.invoke(ide);
            for (int i = 0; i < 35; i++) ide.tick(); // Let each docked frame reach its rendered bounds.
            MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
            var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
            var access = (MultilineTextFieldAccessor) field;
            set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
            editor.active = editor.visible = true;
            settings.codeWordWrap = false; set(ide, "codeScale", 1F);
            LuaSyntaxHighlighter.setScale(editor, 1F); LuaSyntaxHighlighter.setPersonalization(editor, settings);
            LuaEditorViewport.register(editor, (Font) value(ide, "codeFont"), false);
            access.mcLuaIde$setWidth(LuaEditorViewport.flowWidth(false, editor.getWidth(), 1F));
            editor.setValue(("local value = '" + "x".repeat(500) + "'\n").repeat(200));
            access.mcLuaIde$reflowDisplayLines();
            for (int mode = 0; mode < 3; mode++) {
                set(ide, "layoutLocked", mode != 0); settings.resizeDockWhenLocked = mode == 2;
                layout.invoke(ide);
                for (int i = 0; i < 35; i++) ide.tick();
                editor.active = editor.visible = true; editor.setScrollAmount(0); select(field, 0, 5);
                int rightWidth = (int) value(ide, "rightDockWidth"), bottomHeight = (int) value(ide, "bottomDockHeight");
                var vertical = LuaEditorViewport.verticalScrollbar(editor);
                require(vertical != null && vertical.left() == (int) nativeBar.invoke(editor), "Scrollbar hit strip differs from vanilla render strip");
                double rightBoundary = ide.width - 8 - (float) value(ide, "animatedRightDockExtent");
                require(rightBoundary - vertical.right() >= 5, "No separate gap between scrollbar and dock splitter");
                double vx = vertical.right() - .25, vy = vertical.bottom() - 12;
                require(hit.invoke(ide, vx, vy) == DockSide.FLOATING, "Scrollbar incorrectly highlights a dock divider");
                click(vx, vy, scale, false);
                require(value(ide, "activeDockResize") == DockSide.FLOATING, "Vertical scrollbar click starts dock resize");
                drag(vx, editor.getBottom() + 80, scale); release(vx, editor.getBottom() + 80, scale);
                require(editor.scrollAmount() == editor.maxScrollAmount(), "Docked vertical scrollbar cannot reach bottom: "
                        + editor.scrollAmount() + "/" + editor.maxScrollAmount() + " scale=" + scale + " mode=" + mode);
                selection(field, 0, 5, "Docked scrollbar keeps selection");
                require((int) value(ide, "rightDockWidth") == rightWidth && (int) value(ide, "bottomDockHeight") == bottomHeight,
                        "Scrollbar drag changes dock geometry");
                var horizontal = LuaEditorViewport.horizontalScrollbar(editor);
                double hx = horizontal.thumbStart() + horizontal.thumbSize() / 2.0, hy = horizontal.bottom() - .25;
                require(hit.invoke(ide, hx, hy) == DockSide.FLOATING, "Horizontal scrollbar highlights bottom dock divider");
                click(hx, hy, scale, false); drag(editor.getRight() + 80, hy, scale); release(editor.getRight() + 80, hy, scale);
                require(LuaEditorViewport.offset(editor) > 0 && (int) value(ide, "bottomDockHeight") == bottomHeight,
                        "Horizontal scrollbar conflicts with bottom dock resizing");

                boolean resizable = mode != 1;
                double dx = (vertical.right() + rightBoundary) / 2, dy = editor.getY() + editor.getHeight() / 2.0;
                click(dx, dy, scale, false);
                require(value(ide, "activeDockResize") == (resizable ? DockSide.RIGHT : DockSide.FLOATING), "Adjacent right splitter/lock routing");
                if (resizable) drag(dx + 18, dy, scale);
                release(dx + 18, dy, scale);
                require(((int) value(ide, "rightDockWidth") != rightWidth) == resizable, "Right splitter no longer resizes independently");
                double bottomBoundary = ide.height - 30 - (float) value(ide, "animatedBottomDockExtent");
                dx = editor.getX() + editor.getWidth() / 2.0; dy = (editor.getBottom() + bottomBoundary) / 2;
                click(dx, dy, scale, false);
                require(value(ide, "activeDockResize") == (resizable ? DockSide.BOTTOM : DockSide.FLOATING), "Adjacent bottom splitter/lock routing");
                if (resizable) drag(dx, dy + 12, scale);
                release(dx, dy + 12, scale);
                require(((int) value(ide, "bottomDockHeight") != bottomHeight) == resizable, "Bottom splitter no longer resizes independently");
            }
            set(ide, "layoutLocked", false); settings.resizeDockWhenLocked = false;
            files.close(); set(files, "progress", 0F);
            set(ide, "outputVisible", false); set(ide, "outputProgress", 0F);
            dock.invoke(ide, filesField.get(null), DockSide.FLOATING);
            dock.invoke(ide, outputField.get(null), DockSide.FLOATING);
            set(ide, "animatedRightDockExtent", -1F); set(ide, "animatedBottomDockExtent", -1F); layout.invoke(ide);
        }
        set(ide, "pendingInterfaceScale", 1F); ide.tick();
    }

    private static void verifyHoverSuppression() throws Exception {
        for (float scale : new float[]{1F, 1.25F}) {
            set(ide, "pendingInterfaceScale", scale); ide.tick(); release(0, 0, scale);
            setValidationSource("local function sample(value)\n    --@value=测试变量说明\n    return value\nend\n"
                    + "MC.Player.addExperience('uuid', 5)\n" + "-- padding\n".repeat(100));
            MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
            editor.setScrollAmount(0);
            double[] api = hoverPoint("MC.Player.addExperience"), variable = hoverPoint("value"), function = hoverPoint("sample");
            require(hoverTooltipAt(api), "API hover missing before mouse press");
            require(hoverTooltipAt(variable), "Variable hover missing before mouse press");
            require(hoverTooltipAt(function), "Local function hover missing before mouse press");
            click(variable[0], variable[1], scale, false);
            require(!hoverTooltipAt(api) && !hoverTooltipAt(variable) && !hoverTooltipAt(function), "Held left mouse shows a code tooltip");
            drag(api[0], api[1], scale);
            require(!hoverTooltipAt(api), "Drag selection shows API tooltip");
            require(!((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField().getSelectedText().isEmpty(), "Tooltip suppression breaks code selection");
            ide.mouseReleased(new MouseButtonEvent(api[0] * scale, api[1] * scale, new MouseButtonInfo(1, 0)));
            require(!hoverTooltipAt(api), "Releasing right mouse incorrectly restores left-drag tooltip");
            release(editor.getX() - 50, editor.getY() - 50, scale);
            require(hoverTooltipAt(api) && hoverTooltipAt(variable) && hoverTooltipAt(function), "Release outside editor leaves tooltips suppressed");
            click(variable[0], variable[1], scale, true);
            require(!hoverTooltipAt(variable), "Held double-click shows tooltip");
            release(variable[0], variable[1], scale);
            require(hoverTooltipAt(variable), "Completed selection permanently hides tooltip");
            var bar = LuaEditorViewport.verticalScrollbar(editor);
            require(bar != null, "Hover test needs vertical scrollbar");
            click(bar.left() + 2, bar.top() + 4, scale, false);
            require(!hoverTooltipAt(api), "Scrollbar left press shows code tooltip");
            release(bar.right() + 30, bar.top() + 4, scale);
            require(hoverTooltipAt(api), "Scrollbar early-return release leaves tooltip suppressed");
        }
        set(ide, "pendingInterfaceScale", 1F); ide.tick();
    }

    private static double[] hoverPoint(String symbol) throws Exception {
        MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
        String source = editor.getValue(); int index = source.indexOf(symbol), start = source.lastIndexOf('\n', index - 1) + 1;
        int row = (int) source.substring(0, start).chars().filter(character -> character == '\n').count();
        Font font = (Font) value(ide, "codeFont");
        float scale = LuaSyntaxHighlighter.scale(editor) * LuaSyntaxHighlighter.cellWidth(editor);
        return new double[]{editor.getX() + 4 + (font.width(source.substring(start, index)) + font.width(symbol.substring(0, 1)) / 2.0) * scale,
                editor.getY() + 4 + (row + .5) * LuaEditorViewport.lineHeight(editor)};
    }

    private static boolean hoverTooltipAt(double[] point) throws Exception {
        var graphics = new net.minecraft.client.gui.GuiGraphicsExtractor(Minecraft.getInstance(),
                new net.minecraft.client.renderer.state.gui.GuiRenderState(), (int) point[0], (int) point[1]);
        var hover = LuaIdeScreen.class.getDeclaredMethod("extractApiHover", net.minecraft.client.gui.GuiGraphicsExtractor.class,
                MultilineTextField.class, int.class, int.class); hover.setAccessible(true);
        var editor = (MultiLineEditBox) value(ide, "editor");
        hover.invoke(ide, graphics, ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField(), (int) point[0], (int) point[1]);
        return value(graphics, "deferredTooltip") != null;
    }

    private static void verifyLogCopy(Minecraft client) throws Exception {
        String clipboard = client.keyboardHandler.getClipboard();
        @SuppressWarnings("unchecked") var lines = (java.util.List<String>) value(ide, "outputLines");
        lines.clear(); lines.add("alpha 中文"); lines.add("beta tail");
        set(ide, "outputDock", DockSide.FLOATING); set(ide, "outputVisible", true); set(ide, "outputProgress", 1F);
        set(ide, "outputWindowX", 100); set(ide, "outputWindowY", 50);
        set(ide, "outputWindowWidth", 300); set(ide, "outputWindowHeight", 150); set(ide, "outputScrollLines", 0);
        Field titleField = LuaIdeScreen.class.getDeclaredField("OUTPUT_TITLE_HEIGHT"); titleField.setAccessible(true);
        int title = titleField.getInt(null);
        double top = 50 + title + 5;
        click(109, top + 2, 1F, false);
        require((boolean) value(ide, "outputSelecting"), "Log text drag selection did not start");
        drag(109 + client.font.width("beta"), top + client.font.lineHeight + 2, 1F);
        release(109 + client.font.width("beta"), top + client.font.lineHeight + 2, 1F);
        ide.keyPressed(new KeyEvent(InputConstants.KEY_C, 0, 2));
        require(client.keyboardHandler.getClipboard().equals("alpha 中文\nbeta"), "Cross-line Chinese log copy incorrect");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, 2));
        ide.keyPressed(new KeyEvent(InputConstants.KEY_C, 0, 2));
        require(client.keyboardHandler.getClipboard().equals(String.join("\n", lines)), "Log Ctrl+A/C did not copy all");
        click(100 + 300 - 40, 58, 1F, false);
        require(client.keyboardHandler.getClipboard().equals(String.join("\n", lines)), "Copy-all log button failed");
        var close = LuaIdeScreen.class.getDeclaredMethod("closeOutputConsole"); close.setAccessible(true); close.invoke(ide);
        client.keyboardHandler.setClipboard(clipboard);
    }
    private static void verifyUndoCursor() throws Exception {
        var editor = (MultiLineEditBox) value(ide, "editor");
        var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        String original = "local a = 1\n" + "print(a)\n".repeat(300);
        set(ide, "internalTextChange", true); editor.setValue(original);
        set(ide, "internalTextChange", false); set(ide, "lastSnapshot", original);
        ((java.util.Deque<?>) value(ide, "undoHistory")).clear();
        ((java.util.Deque<?>) value(ide, "redoHistory")).clear();
        ide.setFocused(editor); editor.active = true;
        field.seekCursor(Whence.ABSOLUTE, 9); field.insertText("2");
        editor.setScrollAmount(0);
        ide.keyPressed(new KeyEvent(InputConstants.KEY_Z, 0, 2));
        require(editor.getValue().equals(original) && field.cursor() == 9, "Undo jumped to end rather than surviving edit location");
        require(editor.scrollAmount() == 0, "Undo scrolled top-of-file edit to bottom");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_Y, 0, 2));
        require(field.cursor() <= 10 && editor.scrollAmount() == 0, "Redo jumped to end");
        String edited = editor.getValue();
        ide.keyPressed(new KeyEvent(InputConstants.KEY_Z, 0, 2));
        ide.keyPressed(new KeyEvent(InputConstants.KEY_Z, 0, 3));
        require(editor.getValue().equals(edited) && field.cursor() <= 10 && editor.scrollAmount() == 0,
                "Ctrl+Shift+Z did not redo in place");
        require(LuaTextHistory.mapCursor("abcXYZdef", "abcdef", 5) == 3, "Deleted caret did not collapse nearby");
        require(LuaTextHistory.mapCursor("abcXYZdef", "abcdef", 8) == 5, "Unchanged suffix caret did not follow code");
        require(LuaTextHistory.mapCursor("abc", "abc", 1) == 1, "Unchanged caret moved");
    }
    private static void verifyWindowShortcutsAndFontZoom() throws Exception {
        var editor = (MultiLineEditBox) value(ide, "editor");
        var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        var docs = (LuaApiDocsOverlay) value(ide, "apiDocsOverlay");
        var insert = (LuaInsertOverlay) value(ide, "insertOverlay");
        var components = (LuaComponentEditorOverlay) value(ide, "componentEditorOverlay");
        docs.close(); insert.close(); components.close();
        set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
        set(ide, "outputVisible", false); set(ide, "outputProgress", 0F);
        set(ide, "sourceLoaded", true); set(ide, "sourceLoading", false);
        editor.setValue("local value = 1"); field.seekCursor(Whence.ABSOLUTE, 0);
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F1, 0, 2));
        require(docs.isOpen() && !(boolean) value(ide, "manualOpen"), "Ctrl+F1 fell back to manual instead of API docs");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F1, 0, 2));
        require(docs.isOpen() && (boolean) value(docs, "keyboardFocused"), "Repeated Ctrl+F1 closed or unfocused docs");

        editor.setValue("MC.World.setTime(1000, nil)"); field.seekCursor(Whence.ABSOLUTE, 24);
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F2, 0, 2));
        require(insert.isOpen() && insert.kind() == LuaInsertCatalog.Kind.DIMENSION,
                "Ctrl+F2 did not recognize dimension argument while docs had focus");
        require((boolean) value(insert, "keyboardFocused"), "Ctrl+F2 insert search did not get keyboard focus");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F2, 0, 2));
        require(insert.isOpen(), "Repeated Ctrl+F2 closed insert window");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F3, 0, 2));
        require((boolean) value(ide, "outputVisible") && !(boolean) value(insert, "keyboardFocused"),
                "Ctrl+F3 failed to open logs or transfer focus");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F3, 0, 2));
        require((boolean) value(ide, "outputVisible"), "Repeated Ctrl+F3 closed logs");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F4, 0, 2));
        require(components.isOpen() && (boolean) value(components, "keyboardFocused"), "Ctrl+F4 failed to focus component editor");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F4, 0, 2));
        require(components.isOpen(), "Repeated Ctrl+F4 closed component editor");

        Class<?> toolType = Class.forName(LuaIdeScreen.class.getName() + "$ToolWindow");
        var dock = LuaIdeScreen.class.getDeclaredMethod("dockTool", toolType, DockSide.class); dock.setAccessible(true);
        var active = LuaIdeScreen.class.getDeclaredMethod("activeToolAt", DockSide.class); active.setAccessible(true);
        var apiTool = toolType.getDeclaredField("API_DOCS"); apiTool.setAccessible(true);
        var insertTool = toolType.getDeclaredField("INSERT"); insertTool.setAccessible(true);
        dock.invoke(ide, apiTool.get(null), DockSide.RIGHT); dock.invoke(ide, insertTool.get(null), DockSide.RIGHT);
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F1, 0, 2));
        require(active.invoke(ide, DockSide.RIGHT) == apiTool.get(null), "Ctrl+F1 did not reveal docked API tab");
        ide.keyPressed(new KeyEvent(InputConstants.KEY_F2, 0, 2));
        require(active.invoke(ide, DockSide.RIGHT) == insertTool.get(null), "Ctrl+F2 did not reveal docked insert tab");
        docs.setDockSide(DockSide.FLOATING); insert.setDockSide(DockSide.FLOATING);
        docs.close(); insert.close(); components.close();
        set(docs, "progress", 0F); set(insert, "progress", 0F); set(components, "progress", 0F);
        set(ide, "outputVisible", false); set(ide, "outputProgress", 0F); set(ide, "outputKeyboardFocused", false);
        var files = (LuaFileOverlay) value(ide, "fileOverlay"); files.close(); set(files, "progress", 0F);
        var layout = LuaIdeScreen.class.getDeclaredMethod("applyDockLayout"); layout.setAccessible(true); layout.invoke(ide);

        var settings = (LuaPersonalizationSettings) value(ide, "personalization");
        settings.fontPercent = 100;
        var fontScale = LuaIdeScreen.class.getDeclaredMethod("setFontScale", float.class, String.class); fontScale.setAccessible(true);
        fontScale.invoke(ide, 1F, "100%");
        editor.active = editor.visible = true;
        editor.setValue("local selected = 42\n" + "print(selected)\n".repeat(100)); select(field, 6, 14);
        String source = editor.getValue();
        var wheel = LuaIdeScreen.class.getDeclaredMethod("mouseScrolledLogical", double.class, double.class, double.class, double.class, boolean.class);
        wheel.setAccessible(true);
        double x = editor.getX() + 10, y = editor.getY() + 25;
        wheel.invoke(ide, x, y, 0D, 1D, true);
        require(settings.fontPercent == 110, "Ctrl+wheel up did not increase font size");
        selection(field, 6, 14, "Font zoom changed selection");
        require(editor.getValue().equals(source), "Font zoom changed code");
        wheel.invoke(ide, x, y, 0D, -1D, true);
        require(settings.fontPercent == 100, "Ctrl+wheel down did not decrease font size");
        wheel.invoke(ide, x, y, 0D, 100D, true);
        require(settings.fontPercent == 300, "Font maximum is not clamped");
        wheel.invoke(ide, x, y, 0D, -100D, true);
        require(settings.fontPercent == 10, "Font minimum is not clamped");
        wheel.invoke(ide, 1D, 1D, 0D, 1D, true);
        require(settings.fontPercent == 10, "Ctrl+wheel outside code changed font size");
        fontScale.invoke(ide, 1F, "100%");
        wheel.invoke(ide, x, y, 0D, -1D, false);
        require(settings.fontPercent == 100, "Ordinary wheel changed font size");
        field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, 0);
        ide.setFocused(editor);
    }
    private static void verifyInputMethodFocus(Minecraft client) throws Exception {
        MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
        ide.setFocused(editor); editor.setFocused(true);
        client.textInputManager().startTextInput();
        var unused = new net.minecraft.client.gui.components.EditBox(client.font, 0, 0, 40, 20,
                net.minecraft.network.chat.Component.literal("probe"));
        unused.setFocused(false); unused.setFocused(false);
        require((boolean) value(client.textInputManager(), "textInputEnabled"), "Inactive tool field disabled editor IME");
        editor.setFocused(true);
        require((boolean) value(client.textInputManager(), "textInputEnabled"), "Repeated focus disabled editor IME");
        String source = editor.getValue();
        require(ide.charTyped(new net.minecraft.client.input.CharacterEvent('中')), "Chinese committed character not handled");
        require(editor.getValue().contains("中"), "Chinese committed character was lost");
        editor.setValue(source);
        java.lang.reflect.Method visibility = java.util.Arrays.stream(MultiLineEditBox.class.getDeclaredMethods())
                .filter(method -> method.getName().contains("cursorVisibility")).findFirst().orElseThrow();
        visibility.setAccessible(true);
        require((boolean) visibility.invoke(editor, 0L) && (boolean) visibility.invoke(editor, 400L),
                "Blink cycle changes editor text segmentation");
    }
    private static void select(MultilineTextField field, int start, int end) {
        field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, start);
        field.setSelecting(true); field.seekCursor(Whence.ABSOLUTE, end); field.setSelecting(false);
    }
    private static void selection(MultilineTextField field, int start, int end, String message) {
        var access = (MultilineTextFieldAccessor) field;
        require(Math.min(field.cursor(), access.mcLuaIde$getSelectCursor()) == start
                && Math.max(field.cursor(), access.mcLuaIde$getSelectCursor()) == end,
                message + ": " + field.cursor() + "/" + access.mcLuaIde$getSelectCursor() + " expected " + start + "/" + end);
    }
    private static MouseButtonEvent event(double x, double y, float scale) { return new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(0, 0)); }
    private static void click(double x, double y, float scale, boolean twice) { ide.mouseClicked(event(x, y, scale), twice); }
    private static void drag(double x, double y, float scale) { ide.mouseDragged(event(x, y, scale), 0, 0); }
    private static void release(double x, double y, float scale) { ide.mouseReleased(event(x, y, scale)); }
    private static Object value(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
