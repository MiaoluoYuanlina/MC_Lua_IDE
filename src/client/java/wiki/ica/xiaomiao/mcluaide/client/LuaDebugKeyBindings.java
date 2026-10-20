package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;
import java.util.*;

/** One persisted chord table for the IDE, F12 pages and native game input. */
public final class LuaDebugKeyBindings {
	public enum Action {
		TOGGLE_BREAKPOINT("切换当前行断点", "", true), CLEAR_BREAKPOINTS("清空当前文件断点", "", true),
		PAUSE("暂停 / 继续", "toggle", false), STEP_INTO("单步进入", "step_into", false),
		STEP_OVER("单步越过", "step_over", false), STEP_OUT("单步跳出", "step_out", false),
		STOP("停止脚本", "stop", false);
		final String label, command;
		final boolean editorOnly;
		Action(String label, String command, boolean editorOnly) {
			this.label = label; this.command = command; this.editorOnly = editorOnly;
		}
	}
	public enum Preset { FUNCTION_KEYS, NUMPAD }
	private static final int MODIFIERS = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT;
	private static Settings settings;
	private static final Set<Integer> consumedGameKeys = new HashSet<>();
	private LuaDebugKeyBindings() { }
	static Settings settings() {
		if (settings == null) settings = new Settings(FabricLoader.getInstance().getConfigDir()
				.resolve("mc_lua_ide").resolve("debug-keys.properties"));
		return settings;
	}
	static Action match(KeyEvent event) { return settings().match(event.key(), event.modifiers()); }
	static String label(Action action) { return settings().binding(action).label(); }
	static String runHint() {
		return label(Action.PAUSE) + " 暂停/继续 · " + label(Action.STOP) + " 停止";
	}
	static String stepHint() {
		return label(Action.STEP_INTO) + " 进入 · " + label(Action.STEP_OVER) + " 越过 · " + label(Action.STEP_OUT) + " 跳出";
	}
	/** Called only by KeyboardHandler on the client thread; never captures ordinary game input without a debug run. */
	public static boolean onGameKey(long window, int eventAction, KeyEvent event) {
		Minecraft client = Minecraft.getInstance();
		if (window != client.getWindow().handle()) return false;
		if (eventAction == GLFW.GLFW_RELEASE) return consumedGameKeys.remove(event.key());
		if (eventAction == GLFW.GLFW_REPEAT) {
			boolean debugScreen = client.screen instanceof LuaIdeScreen || client.screen instanceof ImGuiIdeScreen || client.screen instanceof LuaDebugScreen || client.screen instanceof LuaDebugKeysScreen;
			return consumedGameKeys.contains(event.key()) || debugScreen && match(event) != null;
		}
		if (client.screen != null || client.player == null || !ScriptManager.isServerAvailable()) return false;
		Action action = match(event);
		var state = LuaBreakpointClient.state();
		if (action == null || action.editorOnly || !state.running() || !state.enabled()) return false;
		consumedGameKeys.add(event.key());
		LuaBreakpointClient.control(action.command);
		return true;
	}
	static void disconnected() { consumedGameKeys.clear(); }
	static boolean modifierKey(int key) {
		return key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER;
	}

	record Binding(int key, int modifiers) {
		Binding { modifiers &= MODIFIERS; }
		boolean matches(int key, int modifiers) { return this.key >= 0 && this.key == key && (modifiers & GLFW.GLFW_MOD_SUPER) == 0 && this.modifiers == (modifiers & MODIFIERS); }
		String label() {
			if (key < 0) return "未绑定";
			String name;
			if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) name = "F" + (key - GLFW.GLFW_KEY_F1 + 1);
			else if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) name = "小键盘 " + (key - GLFW.GLFW_KEY_KP_0);
			else name = switch (key) {
				case GLFW.GLFW_KEY_KP_DECIMAL -> "小键盘 .";
				case GLFW.GLFW_KEY_KP_ADD -> "小键盘 +";
				case GLFW.GLFW_KEY_KP_SUBTRACT -> "小键盘 -";
				case GLFW.GLFW_KEY_KP_MULTIPLY -> "小键盘 *";
				case GLFW.GLFW_KEY_KP_DIVIDE -> "小键盘 /";
				case GLFW.GLFW_KEY_KP_ENTER -> "小键盘 Enter";
				case GLFW.GLFW_KEY_KP_EQUAL -> "小键盘 =";
				default -> key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z || key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9
						? Character.toString((char) key) : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
			};
			return ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 ? "Ctrl+" : "")
					+ ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? "Shift+" : "")
					+ ((modifiers & GLFW.GLFW_MOD_ALT) != 0 ? "Alt+" : "") + name;
		}
	}

	static final class Settings {
		private final Path file;
		private final EnumMap<Action, Binding> bindings = new EnumMap<>(Action.class);
		Settings(Path file) {
			this.file = file; bindings.putAll(preset(Preset.FUNCTION_KEYS));
			Properties saved = LuaIdeSession.readProperties(file);
			for (Action action : Action.values()) {
				String value = saved.getProperty(action.name()); if (value == null) continue;
				try {
					String[] parts = value.split(",", -1);
					int key = Integer.parseInt(parts[0]), modifiers = Integer.parseInt(parts[1]);
					validate(key, modifiers); bindings.put(action, new Binding(key, modifiers));
				} catch (RuntimeException ignored) { }
			}
			// Malformed files may repeat a chord: retain the first, unbind the duplicates.
			Set<Binding> seen = new HashSet<>();
			for (Action action : Action.values()) {
				Binding binding = binding(action);
				if (binding.key >= 0 && !seen.add(binding)) bindings.put(action, new Binding(-1, 0));
			}
		}
		Binding binding(Action action) { return bindings.get(action); }
		Action match(int key, int modifiers) {
			for (Action action : Action.values()) if (binding(action).matches(key, modifiers)) return action;
			return null;
		}
		void assign(Action action, int key, int modifiers) {
			if ((modifiers & GLFW.GLFW_MOD_SUPER) != 0) throw new IllegalArgumentException("组合键仅支持 Ctrl / Shift / Alt，不支持 Windows 键");
			modifiers &= MODIFIERS; validate(key, modifiers);
			Binding candidate = new Binding(key, modifiers);
			for (Action other : Action.values()) if (other != action && key >= 0 && binding(other).equals(candidate))
				throw new IllegalArgumentException("与“" + other.label + "”的快捷键重复，请选择其他按键");
			bindings.put(action, candidate);
		}
		void apply(Preset preset) { bindings.clear(); bindings.putAll(preset(preset)); }
		boolean save() {
			Properties saved = new Properties();
			bindings.forEach((action, binding) -> saved.setProperty(action.name(), binding.key + "," + binding.modifiers));
			return LuaIdeSession.writeProperties(file, saved);
		}
		private static void validate(int key, int modifiers) {
			if (key != -1 && (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST || modifierKey(key)))
				throw new IllegalArgumentException("请选择一个普通按键，可搭配 Ctrl / Shift / Alt");
			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_F11 || key == GLFW.GLFW_KEY_F12)
				throw new IllegalArgumentException("Esc、F11、F12 保留给关闭、全屏和调试菜单");
			if ((modifiers & ~MODIFIERS) != 0) throw new IllegalArgumentException("无效的组合键");
		}
		static EnumMap<Action, Binding> preset(Preset preset) {
			EnumMap<Action, Binding> result = new EnumMap<>(Action.class);
			boolean function = preset == Preset.FUNCTION_KEYS;
			result.put(Action.TOGGLE_BREAKPOINT, new Binding(function ? GLFW.GLFW_KEY_F9 : GLFW.GLFW_KEY_KP_0, 0));
			result.put(Action.CLEAR_BREAKPOINTS, new Binding(function ? GLFW.GLFW_KEY_F9 : GLFW.GLFW_KEY_KP_0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT));
			result.put(Action.PAUSE, new Binding(function ? GLFW.GLFW_KEY_F6 : GLFW.GLFW_KEY_KP_5, 0));
			result.put(Action.STEP_INTO, new Binding(function ? GLFW.GLFW_KEY_F7 : GLFW.GLFW_KEY_KP_7, 0));
			result.put(Action.STEP_OVER, new Binding(function ? GLFW.GLFW_KEY_F10 : GLFW.GLFW_KEY_KP_8, 0));
			result.put(Action.STEP_OUT, new Binding(function ? GLFW.GLFW_KEY_F8 : GLFW.GLFW_KEY_KP_9, 0));
			result.put(Action.STOP, new Binding(function ? GLFW.GLFW_KEY_F6 : GLFW.GLFW_KEY_KP_DECIMAL, function ? GLFW.GLFW_MOD_SHIFT : 0));
			return result;
		}
	}
}
