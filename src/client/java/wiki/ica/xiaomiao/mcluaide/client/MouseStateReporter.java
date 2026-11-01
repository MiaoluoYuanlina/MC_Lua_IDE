package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

/**
 * Tells the server which mouse buttons this player is holding, so scripts can read them with MC.Player.leftClick/rightClick even when
 * the click hits nothing. Sent only when a button changes, and again after joining a server.
 */
final class MouseStateReporter {
	private static boolean sent;
	private static boolean lastLeft;
	private static boolean lastRight;

	private MouseStateReporter() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(MouseStateReporter::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sent = false);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> sent = false);
	}

	private static void tick(Minecraft client) {
		if (client.player == null || !ClientPlayNetworking.canSend(LuaProtocol.MouseState.TYPE)) return;
		boolean left = client.mouseHandler.isLeftPressed();
		boolean right = client.mouseHandler.isRightPressed();
		if (sent && left == lastLeft && right == lastRight) return;
		ClientPlayNetworking.send(new LuaProtocol.MouseState(left, right));
		sent = true;
		lastLeft = left;
		lastRight = right;
	}
}
