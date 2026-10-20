package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** GLFW still sends Windows Alt+F4 as a close request after the screen handles the key. */
public final class LuaWindowShortcutCloseGuard {
	private LuaWindowShortcutCloseGuard() { }
	public static boolean intercept() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || !(client.screen instanceof LuaIdeScreen || client.screen instanceof ImGuiIdeScreen)) return false;
		long window = client.getWindow().handle();
		if (!pressed(window, GLFW.GLFW_KEY_F4)) return false;
		int modifiers = (pressed(window, GLFW.GLFW_KEY_LEFT_ALT) || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT) ? 4 : 0)
				| (pressed(window, GLFW.GLFW_KEY_LEFT_CONTROL) || pressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL) ? 2 : 0)
				| (pressed(window, GLFW.GLFW_KEY_LEFT_SHIFT) || pressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0)
				| (pressed(window, GLFW.GLFW_KEY_LEFT_SUPER) || pressed(window, GLFW.GLFW_KEY_RIGHT_SUPER) ? 8 : 0);
		boolean handled = client.screen instanceof LuaIdeScreen ide ? ide.handleAltFunctionClose(modifiers) : ((ImGuiIdeScreen)client.screen).handleAltFunctionClose(modifiers);
		if (!handled) return false;
		GLFW.glfwSetWindowShouldClose(window, false);
		return true;
	}
	private static boolean pressed(long window, int key) { return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS; }
}
