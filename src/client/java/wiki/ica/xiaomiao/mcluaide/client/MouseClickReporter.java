package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

/** Reports raw presses rather than tick samples, so rapid clicks are preserved. */
public final class MouseClickReporter {
	private MouseClickReporter() { }

	public static void onButton(long window, int button, int action) {
		Minecraft client = Minecraft.getInstance();
		if (window != client.getWindow().handle()) return;
		if (shouldReport(client.player != null, client.screen != null, client.mouseHandler.isMouseGrabbed(),
				client.isWindowActive(), button, action)
				&& ClientPlayNetworking.canSend(LuaProtocol.MouseClick.TYPE))
			ClientPlayNetworking.send(new LuaProtocol.MouseClick(button == GLFW.GLFW_MOUSE_BUTTON_LEFT));
	}

	static boolean shouldReport(boolean hasPlayer, boolean hasScreen, boolean grabbed, boolean active,
			int button, int action) {
		return hasPlayer && !hasScreen && grabbed && active && action == GLFW.GLFW_PRESS
				&& (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
	}
}
