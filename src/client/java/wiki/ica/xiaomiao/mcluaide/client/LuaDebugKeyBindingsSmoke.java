package wiki.ica.xiaomiao.mcluaide.client;

import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;

/** No game/window required: exact modifier matching, presets and configuration recovery. */
public final class LuaDebugKeyBindingsSmoke {
	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("lua-debug-keys-");
		try {
			Path file = root.resolve("debug-keys.properties");
			var settings = new LuaDebugKeyBindings.Settings(file);
			require(settings.match(GLFW.GLFW_KEY_F6, 0) == LuaDebugKeyBindings.Action.PAUSE, "F preset pause missing");
			require(settings.match(GLFW.GLFW_KEY_F6, GLFW.GLFW_MOD_SHIFT) == LuaDebugKeyBindings.Action.STOP, "Shift+F6 dispatched pause instead of stop");
			require(settings.match(GLFW.GLFW_KEY_F9, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT) == LuaDebugKeyBindings.Action.CLEAR_BREAKPOINTS, "Clear chord missing");
			require(settings.match(GLFW.GLFW_KEY_F9, GLFW.GLFW_MOD_CONTROL) == null, "Extra modifier triggered unrelated action");
			require(settings.match(GLFW.GLFW_KEY_F6, GLFW.GLFW_MOD_SUPER) == null, "Windows modifier was silently ignored");
			settings.apply(LuaDebugKeyBindings.Preset.NUMPAD);
			for (var action : LuaDebugKeyBindings.Action.values()) {
				var binding = settings.binding(action);
				require(binding.key() >= GLFW.GLFW_KEY_KP_0 && binding.key() <= GLFW.GLFW_KEY_KP_EQUAL, "Numpad preset contained a non-numpad key");
				require(settings.match(binding.key(), binding.modifiers() | GLFW.GLFW_MOD_NUM_LOCK | GLFW.GLFW_MOD_CAPS_LOCK) == action, "Lock keys broke matching: " + action);
			}
			require(settings.match(GLFW.GLFW_KEY_F6, 0) == null, "Old F preset stayed active after switch");
			settings.assign(LuaDebugKeyBindings.Action.STOP, GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT);
			require(settings.match(GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT) == LuaDebugKeyBindings.Action.STOP, "Custom chord missing");
			require(settings.binding(LuaDebugKeyBindings.Action.STOP).label().equals("Ctrl+Alt+X"), "Custom key label incorrect");
			boolean rejected = false;
			try { settings.assign(LuaDebugKeyBindings.Action.PAUSE, GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT); }
			catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected && settings.binding(LuaDebugKeyBindings.Action.PAUSE).key() == GLFW.GLFW_KEY_KP_5, "Conflict overwrote another binding");
			for (int key : List.of(GLFW.GLFW_KEY_F11, GLFW.GLFW_KEY_F12, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_LEFT_SHIFT, 99999)) {
				rejected = false;
				try { settings.assign(LuaDebugKeyBindings.Action.PAUSE, key, 0); } catch (IllegalArgumentException expected) { rejected = true; }
				require(rejected, "Reserved or invalid key accepted: " + key);
			}
			require(settings.save(), "Configuration save failed");
			var restored = new LuaDebugKeyBindings.Settings(file);
			for (var action : LuaDebugKeyBindings.Action.values()) require(restored.binding(action).equals(settings.binding(action)), "Restart lost custom chord " + action);
			settings.assign(LuaDebugKeyBindings.Action.STOP, -1, 0);
			require(settings.match(GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT) == null, "Unbound key still matched");
			Properties corrupt = new Properties(); corrupt.setProperty("PAUSE", "294,0"); corrupt.setProperty("STOP", "294,0");
			corrupt.setProperty("STEP_OVER", "999999,99999"); LuaIdeSession.writeProperties(file, corrupt);
			var safe = new LuaDebugKeyBindings.Settings(file);
			require(safe.binding(LuaDebugKeyBindings.Action.STOP).key() < 0, "Repeated stored binding was not neutralized");
			require(safe.binding(LuaDebugKeyBindings.Action.STEP_OVER).key() == GLFW.GLFW_KEY_F10, "Corrupt setting did not retain safe default");
			System.out.println("Debug key bindings smoke passed: F/numpad presets, modifier isolation, lock keys, conflicts, reserved keys, unbinding and persistence");
		} finally {
			try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
		}
	}
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
