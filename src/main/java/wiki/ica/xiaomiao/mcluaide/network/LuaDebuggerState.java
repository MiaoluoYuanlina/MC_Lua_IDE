package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.Gson;
import java.util.List;

/** Snapshotted on the Lua thread, so networking never reads a running Lua stack. */
public record LuaDebuggerState(String id, String script, boolean running, boolean enabled,
		boolean paused, String source, int line, String hash, String reason, long sequence,
		List<Frame> stack, int breakpointCount, String code, boolean pauseGame, boolean gamePaused,
		boolean allowPlayerMovement) {
	private static final Gson GSON = new Gson();
	public record Frame(String source, int line, String name) { }
	public static LuaDebuggerState inactive() {
		return new LuaDebuggerState("", "", false, false, false, "", 0, "", "", 0, List.of(), 0, "", false, false, true);
	}
	public LuaDebuggerState withGamePause(boolean enabled, boolean frozenByDebugger) {
		return withGamePause(enabled, frozenByDebugger, allowPlayerMovement);
	}
	public LuaDebuggerState withGamePause(boolean enabled, boolean frozenByDebugger, boolean allowMovement) {
		return new LuaDebuggerState(id, script, running, this.enabled, paused, source, line, hash, reason,
				sequence, stack, breakpointCount, code, enabled, frozenByDebugger, allowMovement);
	}
	public String encode() { return GSON.toJson(this); }
	public static LuaDebuggerState decode(String data) { return GSON.fromJson(data, LuaDebuggerState.class); }
}
