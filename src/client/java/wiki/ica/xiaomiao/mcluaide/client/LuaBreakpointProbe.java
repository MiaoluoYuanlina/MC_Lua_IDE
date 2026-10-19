package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import wiki.ica.xiaomiao.mcluaide.network.*;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in real Minecraft editor/runner check; isolated run directory, no save or live server. */
final class LuaBreakpointProbe {
	private static final String SOURCE = "local function double(value)\n    local result = value * 2\n    return result\nend\nlocal answer = double(4)\nprint('结果：', answer)\n";
	private static int stage;
	private static long started, waitUntil, sequence;
	private static LuaIdeScreen ide;
	private static MultiLineEditBox editor;
	private static LuaExtensionWindow breakpointWindow;
	private static MultilineTextField text;
	private static UUID owner;
	private static CompletableFuture<Void> reload;
	private static CompletableFuture<ServerScriptManager.ExecutionResult> result;
	private static final List<String> output = new CopyOnWriteArrayList<>();

	static void tick(Minecraft client) {
		if (stage == 10 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime();
				client.getLanguageManager().setSelected("zh_cn"); client.options.languageCode = "zh_cn";
				reload = client.reloadResourcePacks(); stage = 1; return;
			}
			if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(40)) throw new AssertionError("Breakpoint probe timed out at stage " + stage);
			if (stage == 1) {
				if (!reload.isDone()) return; reload.join();
				ide = new LuaIdeScreen(client.screen, ""); client.setScreen(ide);
				set("manualOpen", false); set("manualProgress", 0F);
				Class<?> documentClass = Class.forName(LuaIdeScreen.class.getName() + "$OpenDocument");
				Constructor<?> constructor = documentClass.getDeclaredConstructor(String.class, boolean.class, boolean.class);
				constructor.setAccessible(true); Object document = constructor.newInstance("probe", false, false);
				setField(document, "loaded", true); setField(document, "source", SOURCE); setField(document, "savedSource", SOURCE);
				set("currentDocument", document); set("scriptName", "probe"); set("sourceLoaded", true);
				@SuppressWarnings("unchecked") Map<String, Object> documents = (Map<String, Object>) get("openDocuments");
				documents.put("lua:probe", document);
				editor = (MultiLineEditBox) get("editor"); text = (MultilineTextField) call("textField");
				editor.active = true; ide.setFocused(editor); editor.setValue(SOURCE);
				LuaBreakpointStore store = (LuaBreakpointStore) get("breakpointStore"); store.clear("probe");
				text.seekCursor(Whence.ABSOLUTE, LuaBreakpointStore.lineStart(SOURCE, 5));
				ide.keyPressed(new KeyEvent(InputConstants.KEY_F9, 0, 0));
				require(store.lines("probe").equals(Set.of(5)), "F9 did not set current line");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_F9, 0, 0));
				require(store.lines("probe").isEmpty(), "F9 did not remove breakpoint");
				int lineHeight = (int) call("scaledCodeLineHeight");
				int x = (int) call("editorOuterX") + 10, y = editor.getY() + 4 + lineHeight * 4 + 2;
				float scale = (float) get("interfaceScale");
				ide.mouseClicked(new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(0, 0)), false);
				require(store.lines("probe").equals(Set.of(5)), "Gutter click targeted wrong line");
				editor.setValue("\n" + SOURCE);
				require(store.lines("probe").equals(Set.of(6)), "Enter above marker did not follow code");
				ide.setFocused(editor); ide.keyPressed(new KeyEvent(InputConstants.KEY_Z, 0, 2));
				require(editor.getValue().equals(SOURCE) && store.lines("probe").equals(Set.of(5)), "Real Ctrl+Z left breakpoint on wrong line");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_F9, 0, 3));
				require(store.lines("probe").isEmpty(), "Ctrl+Shift+F9 did not clear file");
				// Non-default whole-IDE scaling must not change gutter hit testing.
				set("pendingInterfaceScale", 1.25F); ide.tick();
				editor = (MultiLineEditBox) get("editor"); text = (MultilineTextField) call("textField");
				lineHeight = (int) call("scaledCodeLineHeight"); x = (int) call("editorOuterX") + 10;
				y = editor.getY() + 4 + lineHeight * 4 + 2; scale = (float) get("interfaceScale");
				ide.mouseClicked(new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(0, 0)), false);
				require(store.lines("probe").equals(Set.of(5)), "Scaled gutter click failed");
				set("pendingInterfaceScale", 1F); ide.tick();
				editor = (MultiLineEditBox) get("editor"); text = (MultilineTextField) call("textField");
				owner = UUID.randomUUID();
				ServerScriptManager.configureBreakpoints(owner, store.config().encode());
				result = new CompletableFuture<>();
				ServerScriptManager.start(null, owner, "probe", SOURCE, true, output::add, result::complete);
				stage = 2; return;
			}
			LuaDebuggerState state = LuaDebuggerState.decode(ServerScriptManager.debuggerState());
			if (stage == 2) {
				if (!state.paused()) { if (result.isDone()) throw new AssertionError("Runner finished before breakpoint: " + result.join()); return; }
				require(state.line() == 5, "Runner paused on wrong line");
				accept(state); ide.tick(); require((int) call("pausedLine") == 5, "IDE did not highlight exact paused source");
				verifyBreakpointWindow(client, state);
				waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); stage = 3; return;
			}
			if (stage == 3) {
				if (System.nanoTime() < waitUntil) return;
				screenshot(client, "breakpoint-window.png");
				verifyWindowGeometry();
				verifyDockTabReordering();
				ide.setFocused(editor);
				sequence = state.sequence(); ServerScriptManager.controlDebugger(state.id(), "step_into"); stage = 4; return;
			}
			if (stage == 4) {
				if (!state.paused() || state.sequence() <= sequence) return;
				require(state.line() == 2, "Runner single-step did not enter function");
				require(ServerScriptManager.debugSnapshot().contains("\"name\":\"value\""), "Runner did not force paused local snapshot");
				screenshot(client, "dock-tab-reorder.png");
				finishDockTabReordering();
				accept(state); ide.tick(); ide.keyPressed(new KeyEvent(InputConstants.KEY_F12, 0, 0));
				require(client.screen instanceof LuaDebugScreen, "F12 did not open debugger from IDE");
				Field toggle = LuaDebugScreen.class.getDeclaredField("breakpointToggle"); toggle.setAccessible(true);
				Button button = (Button) toggle.get(client.screen);
				client.screen.mouseClicked(new MouseButtonEvent(button.getX() + 5, button.getY() + 5, new MouseButtonInfo(0, 0)), false);
				require(LuaBreakpointClient.showInfo(), "Breakpoint toggle did not enable HUD");
				waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); stage = 5; return;
			}
			if (stage == 5) {
				if (System.nanoTime() < waitUntil) return;
				screenshot(client, "breakpoint-menu.png");
				verifyKeySettings(client);
				waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); stage = 55; return;
			}
			if (stage == 55) {
				if (System.nanoTime() < waitUntil) return;
				screenshot(client, "breakpoint-key-settings.png");
				client.screen.onClose();
				client.screen.keyPressed(new KeyEvent(InputConstants.KEY_F12, 0, 0)); require(client.screen == ide, "F12 did not return to IDE");
				var store = (LuaBreakpointStore) get("breakpointStore");
				ide.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0, 0, 0));
				require(store.lines("probe").contains(2), "IDE did not use numpad breakpoint key");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_F9, 0, 0));
				require(store.lines("probe").contains(2), "Old F9 binding remained active after changing preset");
				ide.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0, 0, 0));
				LuaDebugKeyBindings.settings().apply(LuaDebugKeyBindings.Preset.FUNCTION_KEYS);
				LuaDebugKeyBindings.settings().save();
				sequence = state.sequence(); ServerScriptManager.controlDebugger(state.id(), "step_over"); stage = 6; return;
			}
			if (stage == 6) {
				if (!state.paused() || state.sequence() <= sequence) return;
				require(state.line() == 3, "Runner step over failed");
				accept(state); ide.tick();
				editor.setValue("\n" + SOURCE); require((int) call("pausedLine") == -1, "Modified source kept an incorrect execution highlight");
				editor.setValue(SOURCE); sequence = state.sequence(); ServerScriptManager.controlDebugger(state.id(), "step_out"); stage = 7; return;
			}
			if (stage == 7) {
				if (!state.paused() || state.sequence() <= sequence) return;
				require(state.line() == 6, "Runner step out failed");
				ServerScriptManager.controlDebugger(state.id(), "continue"); stage = 8; return;
			}
			if (stage == 8) {
				if (!result.isDone()) return;
				require(result.join().succeeded() && output.stream().anyMatch(line -> line.contains("8")), "Runner resume/output failed: " + output);
				result = new CompletableFuture<>(); output.clear();
				ServerScriptManager.start(null, owner, "probe", SOURCE, false, output::add, result::complete); stage = 9; return;
			}
			if (stage == 9) {
				if (!result.isDone()) return;
				require(result.join().succeeded(), "Normal runner unexpectedly paused/failed");
				System.out.println("Breakpoint real editor/runner probe passed: F9, gutter, scale, Enter/Ctrl+Z, clear, real execution pause, locals, into/over/out, modified-source safety, F12 toggle, settings subpage, presets, custom chords, conflicts, unbinding, repeat guard, ordinary run, IDE debugger window, menu pagination, seven controls, detail links, scroll, drag, resize, docking, layout lock, dock tab reorder and restoration");
				ServerScriptManager.shutdownDebugger(); stage = 10; client.stop();
			}
		} catch (Exception | AssertionError error) {
			ServerScriptManager.shutdownDebugger(); stage = 10;
			throw new IllegalStateException("Breakpoint real editor/runner probe failed", error);
		}
	}
	@SuppressWarnings("unchecked")
	private static void verifyBreakpointWindow(Minecraft client, LuaDebuggerState state) throws Exception {
		Map<?, LuaExtensionWindow> windows = (Map<?, LuaExtensionWindow>) get("extensionWindows");
		breakpointWindow = windows.values().stream().filter(window -> window.id().equals(LuaBreakpointWindow.ID)).findFirst().orElseThrow();
		// Ignore layouts left by earlier probe runs and the preceding interface-scale check.
		ide.setExtensionWindowOpen(LuaBreakpointWindow.ID, false); breakpointWindow.setDockSide(DockSide.FLOATING);
		Properties initial = new Properties(); String prefix = LuaBreakpointWindow.ID + ".";
		initial.setProperty(prefix + "x", "80"); initial.setProperty(prefix + "y", "40");
		initial.setProperty(prefix + "width", "300"); initial.setProperty(prefix + "height", "220");
		breakpointWindow.loadLayout(initial);
		Button ui = ide.children().stream().filter(element -> element instanceof Button button
				&& button.getMessage().getString().equals("UI与窗口")).map(element -> (Button) element).findFirst().orElseThrow();
		click(client, ui);
		List<Button> windowItems = (List<Button>) get("windowMenuItems");
		click(client, windowItems.getFirst());
		List<Button> views = (List<Button>) get("viewMenuItems");
		require(views.stream().allMatch(button -> button.getY() + button.getHeight() <= ide.height), "View menu extends off screen");
		Button next = views.stream().filter(button -> button.getMessage().getString().equals("下一页")).findFirst().orElseThrow();
		click(client, next);
		require(((List<Button>) get("viewMenuItems")).stream().anyMatch(button -> button.getMessage().getString().equals("Minecraft UI 编辑器")), "View pagination lost tools");
		click(client, ((List<Button>) get("viewMenuItems")).stream().filter(button -> button.getMessage().getString().equals("上一页")).findFirst().orElseThrow());
		click(client, ((List<Button>) get("viewMenuItems")).stream().filter(button -> button.getMessage().getString().equals("断点调试")).findFirst().orElseThrow());
		require(breakpointWindow.isOpen(), "View menu did not open debugger window");
		for (int i = 0; i < 30; i++) breakpointWindow.tick();
		LuaBreakpointWindow content = (LuaBreakpointWindow) field(breakpointWindow, "content");
		require(content.details().stream().anyMatch(row -> row.text().contains("命中断点")), "Pause reason missing");
		require(state.code().equals("local answer = double(4)") && content.details().stream().anyMatch(row -> row.text().contains(state.code())), "Actual paused code missing from IDE details");
		require(content.details().stream().anyMatch(row -> row.link() && row.source().equals("probe") && row.line() == 5), "Breakpoint/frame location missing");
		content.resize(288, 182);
		content.keyPressed(new KeyEvent(InputConstants.KEY_END, 0, 0));
		require((int) field(content, "scroll") > 0, "Debugger information does not scroll");
		content.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, 0));

		List<LuaDebugKeyBindings.Action> actions = new ArrayList<>();
		List<String> locations = new ArrayList<>();
		LuaBreakpointWindow fixture = new LuaBreakpointWindow(client.font, actions::add,
				(name, line) -> locations.add(name + ":" + line), () -> true, () -> false);
		fixture.resize(288, 600);
		LuaBreakpointWindow compact = new LuaBreakpointWindow(client.font, actions::add,
				(name, line) -> { }, () -> true, () -> false);
		compact.resize(178, 122);
		Method compactTop = LuaBreakpointWindow.class.getDeclaredMethod("detailTop"); compactTop.setAccessible(true);
		require((int) compactTop.invoke(compact) + 15 <= 122, "New settings left no visible detail row in minimum-size frame");
		int[][] positions = {{2, 2}, {99, 2}, {196, 2}, {2, 27}, {99, 27}, {196, 27}, {2, 52}};
		for (int[] position : positions) fixture.mouseClicked(mouse(position[0], position[1]), false);
		require(actions.size() == 7 && actions.containsAll(List.of(LuaDebugKeyBindings.Action.values())), "Not all seven debugger buttons dispatch actions");
		Method visual = LuaBreakpointWindow.class.getDeclaredMethod("visualDetails"); visual.setAccessible(true);
		Method detailTop = LuaBreakpointWindow.class.getDeclaredMethod("detailTop"); detailTop.setAccessible(true);
		List<LuaBreakpointWindow.Detail> rows = (List<LuaBreakpointWindow.Detail>) visual.invoke(fixture);
		for (int index = 0; index < rows.size(); index++) {
			if (rows.get(index).link()) { fixture.mouseClicked(mouse(4, (int) detailTop.invoke(fixture) + index * 15 + 4), false); break; }
		}
		require(locations.contains("probe:5"), "Clicking detailed location did not navigate");
		accept(LuaDebuggerState.inactive()); actions.clear();
		for (int[] position : positions) fixture.mouseClicked(mouse(position[0], position[1]), false);
		require(actions.equals(List.of(LuaDebugKeyBindings.Action.TOGGLE_BREAKPOINT, LuaDebugKeyBindings.Action.CLEAR_BREAKPOINTS)), "Runtime controls enabled with no debugging execution");
		accept(state);
		// Real host dispatch reaches the IDE and preserves the open file's text.
		Method navigate = LuaIdeScreen.class.getDeclaredMethod("openDebuggerLocation", String.class, int.class); navigate.setAccessible(true);
		navigate.invoke(ide, "probe", 2);
		require(text.cursor() == LuaBreakpointStore.lineStart(SOURCE, 2) && editor.getValue().equals(SOURCE), "Stack navigation overwrote code or targeted wrong line");
		navigate.invoke(ide, "probe", 5);
		ide.setExtensionWindowOpen(LuaBreakpointWindow.ID, true);
		Properties geometry = new Properties(); breakpointWindow.saveLayout(geometry);
		int x = Integer.parseInt(geometry.getProperty(LuaBreakpointWindow.ID + ".x"));
		int y = Integer.parseInt(geometry.getProperty(LuaBreakpointWindow.ID + ".y"));
		text.seekCursor(Whence.ABSOLUTE, LuaBreakpointStore.lineStart(SOURCE, 2));
		ide.mouseClicked(mouse(x + 10, y + 32 + (int) detailTop.invoke(content) + 15 + 4), false);
		ide.mouseReleased(mouse(x + 10, y + 130));
		require(text.cursor() == LuaBreakpointStore.lineStart(SOURCE, 5), "Real window location click did not move editor caret");
		// The editor-only controls remain usable even while focus belongs to this window.
		ide.mouseClicked(mouse(x + 6 + 196, y + 32 + 27), false); ide.mouseReleased(mouse(x + 202, y + 59));
		require(!LuaBreakpointClient.store().lines("probe").contains(5), "Debugger window button did not remove current-line breakpoint");
		ide.mouseClicked(mouse(x + 6 + 196, y + 32 + 27), false); ide.mouseReleased(mouse(x + 202, y + 59));
		require(LuaBreakpointClient.store().lines("probe").contains(5), "Debugger window button did not restore current-line breakpoint");
	}
	private static void verifyWindowGeometry() throws Exception {
		Properties initial = new Properties(); breakpointWindow.saveLayout(initial);
		String prefix = LuaBreakpointWindow.ID + ".";
		int x = Integer.parseInt(initial.getProperty(prefix + "x")), y = Integer.parseInt(initial.getProperty(prefix + "y"));
		int width = Integer.parseInt(initial.getProperty(prefix + "width"));
		ide.mouseClicked(mouse(x, y + 90), false);
		ide.mouseDragged(mouse(x + 18, y + 90), 18, 0); ide.mouseReleased(mouse(x + 18, y + 90));
		Properties resized = new Properties(); breakpointWindow.saveLayout(resized);
		require(Integer.parseInt(resized.getProperty(prefix + "width")) == width - 18, "Debugger window resize failed");
		x += 18;
		ide.mouseClicked(mouse(x + 50, y + 15), false);
		ide.mouseDragged(mouse(8, 80), 8 - x - 50, 80 - y - 15); ide.mouseReleased(mouse(8, 80));
		require(breakpointWindow.dockSide() == DockSide.LEFT, "Debugger did not dock by dragging to left edge");
		for (int i = 0; i < 35; i++) ide.tick();
		breakpointWindow.setLayoutLocked(true);
		int dockX = (int) field(breakpointWindow, "x"), dockY = (int) field(breakpointWindow, "y");
		breakpointWindow.mouseClicked(mouse(dockX + 50, dockY + 15), false);
		require(!breakpointWindow.isDragging(), "Locked debugger still drags");
		breakpointWindow.mouseReleased(mouse(dockX + 50, dockY + 15));
		call("rememberWorkspaceLayout"); call("flushWorkspaceLayout");
		Properties stored = (Properties) call("readExtensionLayout");
		require("LEFT".equals(stored.getProperty(prefix + "dock")) && "true".equals(stored.getProperty(prefix + "open")), "Debugger visibility/dock not saved");
		breakpointWindow.setDockSide(DockSide.FLOATING); breakpointWindow.close(); breakpointWindow.loadLayout(stored);
		require(breakpointWindow.isOpen() && breakpointWindow.dockSide() == DockSide.LEFT, "Debugger layout not restored");
		breakpointWindow.setLayoutLocked(false); breakpointWindow.setDockSide(DockSide.FLOATING); call("applyDockLayout");
	}
	@SuppressWarnings("unchecked")
	private static void verifyDockTabReordering() throws Exception {
		Class<?> toolType = Class.forName(LuaIdeScreen.class.getName() + "$ToolWindow");
		Method dock = LuaIdeScreen.class.getDeclaredMethod("setToolDock", toolType, DockSide.class); dock.setAccessible(true);
		Method toolsAt = LuaIdeScreen.class.getDeclaredMethod("dockedToolsAt", DockSide.class); toolsAt.setAccessible(true);
		Method regionAt = LuaIdeScreen.class.getDeclaredMethod("dockRegion", DockSide.class); regionAt.setAccessible(true);
		Method activeAt = LuaIdeScreen.class.getDeclaredMethod("activeToolAt", DockSide.class); activeAt.setAccessible(true);
		for (Object tool : (List<?>) call("toolWindows")) dock.invoke(ide, tool, DockSide.FLOATING);
		LuaInsertOverlay insert = (LuaInsertOverlay) get("insertOverlay"); insert.open();
		LuaVariableOverlay variables = (LuaVariableOverlay) get("variableOverlay"); variables.open();
		ide.setExtensionWindowOpen(LuaBreakpointWindow.ID, true);
		Object insertTool = toolField(toolType, "INSERT"), variableTool = toolField(toolType, "VARIABLES");
		Map<?, LuaExtensionWindow> windows = (Map<?, LuaExtensionWindow>) get("extensionWindows");
		Object debuggerTool = windows.entrySet().stream().filter(entry -> entry.getValue() == breakpointWindow).findFirst().orElseThrow().getKey();
		List<Object> chosen = List.of(insertTool, variableTool, debuggerTool);
		float scale = (float) get("interfaceScale");
		set("layoutLocked", false); call("hideMenus");
		for (DockSide side : List.of(DockSide.LEFT, DockSide.RIGHT, DockSide.BOTTOM)) {
			for (Object tool : chosen) dock.invoke(ide, tool, side);
			call("applyDockLayout"); for (int index = 0; index < 35; index++) ide.tick();
			Object region = regionAt.invoke(ide, side);
			int x = regionInt(region, "x"), y = regionInt(region, "y"), width = regionInt(region, "width");
			List<Object> before = new ArrayList<>((List<Object>) toolsAt.invoke(ide, side));
			require(before.size() == 3, "Probe requires three docked tabs at " + side);
			double first = x + width / 6.0, last = x + width * 5 / 6.0, tabY = y + 9;
			// Exercises the real scaled input dispatcher for every edge, including extension windows.
			set("interfaceScale", side == DockSide.RIGHT ? 1.25F : side == DockSide.BOTTOM ? 0.85F : 1.0F);
			ide.mouseClicked(scaledMouse(first, tabY), false); ide.mouseReleased(scaledMouse(first, tabY));
			require(activeAt.invoke(ide, side) == before.getFirst(), "Single click did not select dock tab");
			require(before.equals(toolsAt.invoke(ide, side)), "Single click reordered tabs");
			ide.mouseClicked(scaledMouse(first, tabY), false);
			ide.mouseDragged(scaledMouse(first + 2, tabY), 2, 0);
			require(!(boolean) get("dockTabDragging"), "Tiny pointer jitter starts tab drag");
			ide.mouseDragged(scaledMouse(last, tabY), last - first, 0);
			List<Object> forward = new ArrayList<>(before); forward.removeFirst(); forward.add(before.getFirst());
			require(forward.equals(toolsAt.invoke(ide, side)), "Forward tab drag failed at " + side);
			require(activeAt.invoke(ide, side) == before.getFirst(), "Reorder changed active window");
			ide.mouseReleased(scaledMouse(last, tabY));
			require(get("pressedDockTab") == null && !(boolean) get("dockTabDragging"), "Tab drag leaked after release");
			ide.mouseClicked(scaledMouse(last, tabY), false);
			ide.mouseDragged(scaledMouse(first, tabY), first - last, 0); ide.mouseReleased(scaledMouse(first, tabY));
			require(before.equals(toolsAt.invoke(ide, side)), "Backward tab drag failed at " + side);
			set("layoutLocked", true); call("applyDockLayout");
			ide.mouseClicked(scaledMouse(last, tabY), false);
			ide.mouseDragged(scaledMouse(first, tabY), first - last, 0); ide.mouseReleased(scaledMouse(first, tabY));
			require(before.equals(toolsAt.invoke(ide, side)), "Locked layout still reorders tabs");
			require(activeAt.invoke(ide, side) == before.getLast(), "Locked layout cannot switch tabs");
			set("layoutLocked", false); call("applyDockLayout");
			ide.mouseClicked(scaledMouse(first, tabY), false);
			ide.mouseDragged(scaledMouse(last, tabY + 60), last - first, 60); ide.mouseReleased(scaledMouse(last, tabY + 60));
			require(before.equals(toolsAt.invoke(ide, side)), "Dragging outside tab bar corrupted order");
			ide.mouseClicked(scaledMouse(first, tabY), false);
			ide.mouseDragged(scaledMouse(last, tabY), last - first, 0); ide.mouseReleased(scaledMouse(last, tabY));
			call("rememberWorkspaceLayout"); call("flushWorkspaceLayout");
			List<String> tokens = new ArrayList<>();
			Method name = toolType.getDeclaredMethod("name"); name.setAccessible(true);
			for (Object tool : forward) tokens.add((String) name.invoke(tool));
			Field path = LuaIdeScreen.class.getDeclaredField("WINDOW_GEOMETRY_FILE"); path.setAccessible(true);
			Properties saved = LuaIdeSession.readProperties((java.nio.file.Path) path.get(null));
			require(new DockTabOrder(saved).ordered(side, tokens.reversed(), java.util.function.Function.identity()).equals(tokens), "Tab order not restored from disk at " + side);
			set("interfaceScale", scale);
		}
		// Leave a real multi-window bar visible for the next rendered frame/screenshot.
		for (Object tool : chosen) dock.invoke(ide, tool, DockSide.LEFT);
		call("applyDockLayout"); for (int index = 0; index < 35; index++) ide.tick();
		System.out.println("Real dock tab reorder probe passed: left/right/bottom, forward/backward, click, pointer threshold, selected window, scaled input, layout lock, extension tabs and disk restoration");
	}
	private static void finishDockTabReordering() throws Exception {
		((LuaInsertOverlay) get("insertOverlay")).close();
		((LuaInsertOverlay) get("insertOverlay")).setDockSide(DockSide.FLOATING);
		((LuaVariableOverlay) get("variableOverlay")).close();
		((LuaVariableOverlay) get("variableOverlay")).setDockSide(DockSide.FLOATING);
		ide.setExtensionWindowOpen(LuaBreakpointWindow.ID, false); breakpointWindow.setDockSide(DockSide.FLOATING);
		call("applyDockLayout");
	}
	private static Object toolField(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
	}
	private static int regionInt(Object region, String name) throws Exception {
		Method method = region.getClass().getDeclaredMethod(name); method.setAccessible(true); return (int) method.invoke(region);
	}
	private static MouseButtonEvent scaledMouse(double x, double y) throws Exception {
		float scale = (float) get("interfaceScale"); return mouse(x * scale, y * scale);
	}
	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
	}
	private static MouseButtonEvent mouse(double x, double y) { return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)); }
	@SuppressWarnings("unchecked")
	private static void verifyKeySettings(Minecraft client) throws Exception {
		verifyExecutionDisconnect();
		LuaDebugScreen debugPage = (LuaDebugScreen) client.screen;
		client.setScreen(ide);
		List<Button> tools = (List<Button>) get("toolMenuItems");
		Button serverConfig = tools.stream().filter(button -> button.getMessage().getString().equals("服务器配置…"))
				.findFirst().orElseThrow();
		Method toggleTools = LuaIdeScreen.class.getDeclaredMethod("toggleMenu", String.class);
		toggleTools.setAccessible(true); toggleTools.invoke(ide, "tools");
		click(client, serverConfig);
		require(client.screen instanceof LuaSettingsScreen, "Tools server configuration entry did not open settings");
		client.screen.onClose();
		require(client.screen == ide, "Server configuration did not return to original IDE");
		client.setScreen(debugPage);
		require(client.screen.children().size() == 5, "Unexpected main F12 page buttons");
		Button logs = (Button) field(client.screen, "logToggle");
		click(client, logs);
		require(LuaLogHud.enabled() && logs.getMessage().getString().endsWith("开"), "Lua log HUD toggle did not enable");
		LuaLogHud.append("probe.lua", "first\nsecond");
		require(LuaLogHud.snapshot().getLast().text().equals("second"), "Multiline log output was lost");
		for (int index = 0; index < 220; index++) LuaLogHud.append("probe.lua", "line " + index);
		require(LuaLogHud.snapshot().size() == 200 && LuaLogHud.snapshot().getLast().text().equals("line 219"), "HUD history is not bounded/latest-first");
		LuaLogHud.append("probe.lua", "✗ test error");
		require(LuaLogHud.snapshot().getLast().color() == 0xFFFF929F, "HUD error color incorrect");
		click(client, logs);
		require(!LuaLogHud.enabled(), "Lua log HUD toggle did not disable");
		LuaLogHud.disconnected();
		require(LuaLogHud.snapshot().isEmpty(), "Logs leaked across server disconnect");
		Field field = LuaDebugScreen.class.getDeclaredField("keySettingsButton"); field.setAccessible(true);
		Button entry = (Button) field.get(client.screen);
		click(client, entry); require(client.screen instanceof LuaDebugKeysScreen, "Small button did not open shortcut subpage");
		LuaDebugKeysScreen screen = (LuaDebugKeysScreen) client.screen;
		List<Button> visible = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
		for (Button item : visible) {
			require(item.getX() >= 0 && item.getY() >= 0 && item.getX() + item.getWidth() <= screen.width
					&& item.getY() + item.getHeight() <= screen.height, "Debugger settings button outside screen: " + item.getMessage().getString());
			for (Button other : visible) if (item != other) require(!(item.getX() < other.getX() + other.getWidth()
					&& item.getX() + item.getWidth() > other.getX() && item.getY() < other.getY() + other.getHeight()
					&& item.getY() + item.getHeight() > other.getY()), "Debugger settings buttons overlap");
		}
		require(field(screen, "movementToggle") instanceof Button && field(screen, "cameraToggle") instanceof Button, "New debugger settings absent from F12");
		Field pause = LuaDebugKeysScreen.class.getDeclaredField("pauseGameToggle"); pause.setAccessible(true);
		require(((Button) pause.get(screen)).getMessage().getString().equals(LuaBreakpointClient.pauseGameLabel()), "F12 pause-game setting not synchronized");
		Field keysField = LuaDebugKeysScreen.class.getDeclaredField("keyButtons"); keysField.setAccessible(true);
		Map<LuaDebugKeyBindings.Action, Button> keys = (Map<LuaDebugKeyBindings.Action, Button>) keysField.get(screen);
		require(keys.size() == 7, "Shortcut subpage did not contain all seven actions");
		Button preset = screen.children().stream().filter(element -> element instanceof Button button && button.getMessage().getString().equals("预设：小键盘"))
				.map(element -> (Button) element).findFirst().orElseThrow();
		click(client, preset);
		var settings = LuaDebugKeyBindings.settings();
		require(settings.match(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_5, 0) == LuaDebugKeyBindings.Action.PAUSE, "Numpad preset button had no effect");
		click(client, keys.get(LuaDebugKeyBindings.Action.PAUSE));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F12, 0, 0));
		require(client.screen == screen && settings.binding(LuaDebugKeyBindings.Action.PAUSE).key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_5, "Reserved F12 changed binding or escaped capture");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_G, 0, 6));
		require(settings.match(InputConstants.KEY_G, 6) == LuaDebugKeyBindings.Action.PAUSE, "Custom Ctrl+Alt+G capture failed");
		click(client, keys.get(LuaDebugKeyBindings.Action.STEP_INTO));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_G, 0, 6));
		require(settings.binding(LuaDebugKeyBindings.Action.STEP_INTO).key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_7, "Duplicate capture silently overwrote step key");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
		click(client, keys.get(LuaDebugKeyBindings.Action.STOP));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, 0, 0));
		require(settings.binding(LuaDebugKeyBindings.Action.STOP).key() == -1, "Backspace failed to unbind");
		click(client, keys.get(LuaDebugKeyBindings.Action.STEP_OUT));
		screen.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT, 0, 1));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
		require(settings.binding(LuaDebugKeyBindings.Action.STEP_OUT).key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_9, "Modifier-only/cancel changed binding");
		require(LuaDebugKeyBindings.onGameKey(client.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_REPEAT,
				new KeyEvent(InputConstants.KEY_G, 0, 6)), "Held debug key was not protected against repeat");
		var restored = new LuaDebugKeyBindings.Settings(client.gameDirectory.toPath().resolve("config/mc_lua_ide/debug-keys.properties"));
		for (var action : LuaDebugKeyBindings.Action.values()) require(restored.binding(action).equals(settings.binding(action)), "GUI custom binding was not saved: " + action);
	}
	private static void click(Minecraft client, Button button) {
		client.screen.mouseClicked(new MouseButtonEvent(button.getX() + 5, button.getY() + 5, new MouseButtonInfo(0, 0)), false);
	}
	private static void verifyExecutionDisconnect() throws Exception {
		Constructor<ScriptManager.ScriptExecution> constructor = ScriptManager.ScriptExecution.class.getDeclaredConstructor(
				long.class, String.class, String.class, java.util.function.Consumer.class, java.util.function.Consumer.class);
		constructor.setAccessible(true);
		java.util.function.Consumer<String> output = ignored -> { };
		java.util.function.Consumer<ScriptManager.ExecutionResult> completed = ignored -> { };
		var old = constructor.newInstance(9001L, "old", "old.lua", output, completed);
		var next = constructor.newInstance(9002L, "next", "next.lua", output, completed);
		Field active = ScriptManager.class.getDeclaredField("activeExecution"); active.setAccessible(true);
		setField(old, "requestSent", true);
		active.set(null, next);
		ScriptManager.reconcileStopped(old, false);
		require(ScriptManager.activeExecution() == next && next.isRunning(), "Old STOP response cleared a new execution");
		ScriptManager.reconcileStopped(next, false);
		require(next.isRunning(), "STOP response cleared an unsent execution");
		setField(next, "requestSent", true);
		ScriptManager.reconcileStopped(next, false);
		require(!next.isRunning() && ScriptManager.activeExecution() == null, "Missing terminal event left a phantom execution");
		var broken = constructor.newInstance(9003L, "broken", "broken.lua", output,
				(java.util.function.Consumer<ScriptManager.ExecutionResult>) ignored -> { throw new IllegalStateException("probe callback"); });
		Field executions = ScriptManager.class.getDeclaredField("EXECUTIONS"); executions.setAccessible(true);
		@SuppressWarnings("unchecked") Map<Long, ScriptManager.ScriptExecution> handles =
				(Map<Long, ScriptManager.ScriptExecution>) executions.get(null);
		handles.put(9001L, old); handles.put(9003L, broken); active.set(null, broken);
		Method disconnect = ScriptManager.class.getDeclaredMethod("disconnect", String.class); disconnect.setAccessible(true);
		disconnect.invoke(null, "probe disconnect");
		require(handles.isEmpty() && ScriptManager.activeExecution() == null && !old.isRunning() && !broken.isRunning(),
				"Broken disconnect callback left execution state behind");
	}
	private static void screenshot(Minecraft client, String file) {
		net.minecraft.client.Screenshot.grab(client.gameDirectory, file, client.getMainRenderTarget(), 1,
				message -> System.out.println("Breakpoint screenshot: " + message.getString()));
	}
	private static void accept(LuaDebuggerState state) throws Exception {
		Method accept = LuaBreakpointClient.class.getDeclaredMethod("accept", String.class); accept.setAccessible(true); accept.invoke(null, state.encode());
	}
	private static Object get(String name) throws Exception { Field field = LuaIdeScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(ide); }
	private static void set(String name, Object value) throws Exception { setField(ide, name, value); }
	private static void setField(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
	private static Object call(String name) throws Exception { Method method = LuaIdeScreen.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(ide); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
