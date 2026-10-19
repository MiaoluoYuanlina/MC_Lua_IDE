package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;

/** Opt-in real widget/dispatch regression, isolated in build/window-shortcuts-probe. */
final class LuaWindowShortcutsProbe {
	private static int stage, checks;
	private static long started, until;
	private static LuaIdeScreen ide;
	private static LuaPersonalizationScreen panel;
	private static java.util.concurrent.CompletableFuture<Void> nativeKeys;
	private interface ProbeUser32 extends com.sun.jna.win32.StdCallLibrary {
		void keybd_event(byte key, byte scan, int flags, com.sun.jna.Pointer extra);
		com.sun.jna.Pointer GetForegroundWindow();
	}
	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime();
				require(client.gameDirectory.toPath().toString().contains("window-shortcuts-probe"), "Unsafe fixture directory");
				client.options.guiScale().set(1); client.resizeGui();
				ide = new LuaIdeScreen(client.screen, ""); client.setScreen(ide);
				set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
				set(ide, "recoveryPrompt", false);
				var settings = (LuaPersonalizationSettings) get(ide, "personalization"); settings.windowShortcuts.reset();
				for (int modifiers : new int[]{2, 1, 4}) for (int f = 1; f <= 4; f++) {
					key(f, modifiers);
					String field = switch (f) { case 1 -> "apiDocsOverlay"; case 2 -> "insertOverlay";
						case 3 -> "outputVisible"; default -> "componentEditorOverlay"; };
					require(f == 3 ? (boolean) get(ide, field) : isOpen(get(ide, field)), "Default alias did not open " + modifiers + "/F" + f);
					if (f == 2) {
						require((boolean) get(get(ide, field), "keyboardFocused"), "Insert lost keyboard priority");
						ide.charTyped(new CharacterEvent('a'));
						require(get(get(ide, field), "query").equals("a"), "Shortcut typing not routed to insert");
					}
					key(f, modifiers); require(f == 3 ? (boolean) get(ide, field) : isOpen(get(ide, field)), "Repeated chord toggled off window");
					// Close explicitly between tests to avoid changing hit-stack priority.
					if (f == 3) call(ide, "closeOutputConsole"); else call(get(ide, field), "close");
				}
				var insert = (LuaInsertOverlay) get(ide, "insertOverlay"); insert.setDockSide(DockSide.RIGHT);
				set(ide, "layoutLocked", true); key(2, 1);
				require(get(ide, "activeRightDock").toString().equals(getToolName("INSERT")), "Shortcut did not activate dock tab");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
				require(!insert.isOpen(), "Shortcut dock window did not close on Esc");
				insert.setDockSide(DockSide.FLOATING); set(ide, "layoutLocked", false);
				for (int modifiers = 1; modifiers <= 7; modifiers++) {
					settings.windowShortcuts.set(modifiers, 12, LuaWindowShortcuts.Target.INSERT);
					key(12, modifiers);
					require(client.screen == ide && insert.isOpen(), "Modified F12 opened debug instead of custom window");
					insert.close();
				}
				settings.windowShortcuts.set(2, 1, LuaWindowShortcuts.Target.NONE);
				key(1, 2); require(!isOpen(get(ide, "apiDocsOverlay")) && !(boolean) get(ide, "manualOpen"), "Unbound Ctrl+F1 fell back to manual");
				require(!ide.handleAltFunctionClose(2), "Non-Alt close intercepted");
				settings.windowShortcuts.set(4, 4, LuaWindowShortcuts.Target.NONE);
				require(!ide.handleAltFunctionClose(4), "Unbound Alt+F4 close intercepted");
				settings.windowShortcuts.reset();
				key(12, 0); require(client.screen instanceof LuaDebugScreen, "Bare F12 changed"); client.setScreen(ide);
				call(ide, "openPersonalization"); panel = (LuaPersonalizationScreen) get(ide, "personalizationPanel");
				setSection("SHORTCUTS"); waitRender(1); return;
			}
			require(System.nanoTime() - started < 60_000_000_000L, "Window shortcut probe timeout at " + stage);
			if (System.nanoTime() < until) return;
			if (stage == 1) {
				// Toggle Shift alongside Ctrl, step F1 -> F2, then open the target chooser.
				int left = (int) get(panel, "contentLeft") + 10, right = (int) get(panel, "contentRight") - 12;
				int y = (int) get(panel, "viewTop") + 58, cw = (right - left - 8) / 3;
				click(left + cw + 12, y + 12); click(right - 15, y + 35 + 12); click(left + 12, y + 70 + 12);
				require((int) get(panel, "shortcutModifiers") == 3 && (int) get(panel, "shortcutFunction") == 2
						&& (boolean) get(panel, "shortcutChoicesOpen"), "Modifier/function/chooser controls failed");
				waitRender(2); return;
			}
			if (stage == 2) {
				int left = (int) get(panel, "contentLeft") + 10, right = (int) get(panel, "contentRight") - 12;
				int columns = right - left >= 300 ? 2 : 1, cw = (right - left - (columns - 1) * 4) / columns;
				int choice = LuaWindowShortcuts.Target.OUTPUT.ordinal();
				click(left + choice % columns * (cw + 4) + 12,
						(int) get(panel, "viewTop") + 58 + 105 + choice / columns * 28 + 12);
				require(((LuaPersonalizationSettings) get(panel, "draft")).windowShortcuts.get(3, 2) == LuaWindowShortcuts.Target.OUTPUT,
						"Target choice click failed");
				require(((LuaPersonalizationSettings) get(ide, "personalization")).windowShortcuts.get(3, 2) == LuaWindowShortcuts.Target.NONE,
						"Draft applied before Save");
				button("saveButton"); require((boolean) get(panel, "closing"), "Save did not start embedded close animation");
				require(LuaPersonalizationSettings.load().windowShortcuts.get(3, 2) == LuaWindowShortcuts.Target.OUTPUT, "Shortcut not persisted");
				key(2, 3); require((boolean) get(ide, "outputVisible"), "Saved shortcut not applied immediately"); call(ide, "closeOutputConsole");
				waitRender(20); return;
			}
			if (stage == 20) {
				require(get(ide, "personalizationPanel") == null, "Save close animation did not finish");
				call(ide, "openPersonalization"); panel = (LuaPersonalizationScreen) get(ide, "personalizationPanel");
				((LuaPersonalizationSettings) get(panel, "draft")).windowShortcuts.set(3, 2, LuaWindowShortcuts.Target.NONE);
				button("cancelButton");
				require(LuaPersonalizationSettings.load().windowShortcuts.get(3, 2) == LuaWindowShortcuts.Target.OUTPUT
						&& ((LuaPersonalizationSettings) get(ide, "personalization")).windowShortcuts.get(3, 2) == LuaWindowShortcuts.Target.OUTPUT,
						"Discard leaked draft mappings");
				waitRender(21); return;
			}
			if (stage == 21) {
				require(get(ide, "personalizationPanel") == null, "Discard close animation did not finish");
				// Native OS Alt+F4 test only when the probe owns the foreground GLFW window.
				if (System.getProperty("os.name").startsWith("Windows")
						&& GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_FOCUSED) != 0) {
					long nativeWindow = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(client.getWindow().handle());
					nativeKeys = java.util.concurrent.CompletableFuture.runAsync(() -> {
						try {
							ProbeUser32 api = com.sun.jna.Native.load("user32", ProbeUser32.class);
							if (com.sun.jna.Pointer.nativeValue(api.GetForegroundWindow()) != nativeWindow)
								throw new IllegalStateException("Probe no longer owns foreground window");
							try { api.keybd_event((byte) 0x12, (byte) 0, 0, null);
								api.keybd_event((byte) 0x73, (byte) 0, 0, null); Thread.sleep(150); }
							finally { api.keybd_event((byte) 0x73, (byte) 0, 2, null); api.keybd_event((byte) 0x12, (byte) 0, 2, null); }
						} catch (Exception error) { throw new IllegalStateException(error); }
					});
					waitRender(3); return;
				}
				System.out.println("OS Alt+F4 simulation skipped: probe window not foreground"); waitRender(4); return;
			}
			if (stage == 3) {
				if (!nativeKeys.isDone()) return;
				nativeKeys.join();
				require(client.screen == ide && !GLFW.glfwWindowShouldClose(client.getWindow().handle())
						&& isOpen(get(ide, "componentEditorOverlay")), "Native Alt+F4 closed the game or failed to open components");
				System.out.println("Native Windows Alt+F4 window-close guard passed");
				call(get(ide, "componentEditorOverlay"), "close");
				waitRender(4); return;
			}
			if (stage == 4) {
				call(ide, "openPersonalization"); panel = (LuaPersonalizationScreen) get(ide, "personalizationPanel"); setSection("SHORTCUTS");
				var bounds = panel.getClass().getDeclaredMethod("applyBounds", int.class, int.class, int.class, int.class);
				bounds.setAccessible(true); bounds.invoke(panel, 15, 15, 360, 240); waitRender(5); return;
			}
			if (stage == 5) {
				int left = (int) get(panel, "contentLeft") + 10, right = (int) get(panel, "contentRight") - 12;
				int y = (int) get(panel, "viewTop") + 58, cw = (right - left - 8) / 3;
				click(left + 2 * (cw + 4) + 12, y + 12); require((int) get(panel, "shortcutModifiers") == 6, "Compact Alt toggle clipped");
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "window-shortcuts.png", client.getMainRenderTarget(), 1, ignored -> { });
				System.out.println("Window shortcuts real UI probe passed: " + checks + " checks"); stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) {
			stage = 99; client.stop(); throw new IllegalStateException("Window shortcuts probe failed at " + stage, error);
		}
	}
	private static void waitRender(int next) { stage = next; until = System.nanoTime() + 1_200_000_000L; }
	private static void key(int f, int modifiers) { ide.keyPressed(new KeyEvent(289 + f, 0, modifiers)); }
	private static Object get(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
	private static void set(Object object, String name, Object value) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
	private static Object call(Object object, String name) throws Exception { var m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
	private static boolean isOpen(Object object) throws Exception { return (boolean) call(object, "isOpen"); }
	private static String getToolName(String name) throws Exception {
		for (var type : LuaIdeScreen.class.getDeclaredClasses()) if (type.getSimpleName().equals("ToolWindow")) {
			var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null).toString();
		} throw new AssertionError("No tool type");
	}
	@SuppressWarnings({"rawtypes", "unchecked"}) private static void setSection(String name) throws Exception {
		Field f = panel.getClass().getDeclaredField("section"); f.setAccessible(true); f.set(panel, Enum.valueOf((Class) f.getType(), name));
	}
	private static void click(double x, double y) { panel.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false); }
	private static void button(String name) throws Exception { var button = (Button) get(panel, name); click(button.getX() + 5, button.getY() + 5); }
	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
