package wiki.ica.xiaomiao.mcluaide.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The left and right mouse buttons as each player's own client last reported them. The server cannot see these itself (a click on
 * empty air with an empty hand sends no packet), so only players with the mod installed have a state; for anyone else it is absent.
 */
public final class LuaMouseState {
	/** Both buttons at one moment. */
	public record Buttons(boolean left, boolean right) { }

	private static final Map<UUID, Buttons> STATES = new ConcurrentHashMap<>();

	private LuaMouseState() {
	}

	public static void report(UUID player, boolean left, boolean right) {
		STATES.put(player, new Buttons(left, right));
	}

	/** The last report, or {@code null} when this player's client never reported (the mod is not installed there). */
	public static Buttons get(UUID player) {
		return STATES.get(player);
	}

	public static void disconnected(UUID player) {
		STATES.remove(player);
	}

	public static void clear() {
		STATES.clear();
	}
}
