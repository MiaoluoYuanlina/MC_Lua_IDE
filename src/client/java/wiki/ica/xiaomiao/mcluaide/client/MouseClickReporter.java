package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

/** Reports raw button transitions rather than tick samples, so rapid clicks are preserved. */
public final class MouseClickReporter {
	private static final ButtonTracker TRACKER = new ButtonTracker();
	private MouseClickReporter() { }

	static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TRACKER.clear());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TRACKER.clear());
	}

	public static void onButton(long window, int button, int action) {
		Minecraft client = Minecraft.getInstance();
		if (window != client.getWindow().handle() || client.player == null) return;
		boolean modern = ClientPlayNetworking.canSend(LuaProtocol.MouseButtonAction.TYPE);
		if (!modern && !ClientPlayNetworking.canSend(LuaProtocol.MouseClick.TYPE)) return;
		boolean gameplay = client.screen == null && client.mouseHandler.isMouseGrabbed() && client.isWindowActive();
		if (!TRACKER.accept(gameplay, button, action)) return;
		boolean pressed = action == GLFW.GLFW_PRESS;
		if (modern) ClientPlayNetworking.send(new LuaProtocol.MouseButtonAction(button == GLFW.GLFW_MOUSE_BUTTON_LEFT, pressed));
		else if (pressed) ClientPlayNetworking.send(new LuaProtocol.MouseClick(button == GLFW.GLFW_MOUSE_BUTTON_LEFT));
	}

	/** Matches each reported press with one release, including releases after opening a GUI. */
	static final class ButtonTracker {
		private final boolean[] held = new boolean[2];
		boolean accept(boolean gameplay, int button, int action) {
			if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
			if (action == GLFW.GLFW_PRESS && gameplay && !held[button]) {
				held[button] = true;
				return true;
			}
			if (action == GLFW.GLFW_RELEASE && held[button]) {
				held[button] = false;
				return true;
			}
			return false;
		}
		void clear() { java.util.Arrays.fill(held, false); }
	}
}
