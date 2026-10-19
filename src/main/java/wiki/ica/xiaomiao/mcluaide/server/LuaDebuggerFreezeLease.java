package wiki.ica.xiaomiao.mcluaide.server;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Own only a freeze we applied; an explicit vanilla/mod tick command takes ownership back. */
final class LuaDebuggerFreezeLease {
	private boolean owned, changing;
	private String stop = "", suppressedStop = "";
	boolean owned() { return owned; }
	void externalChange() {
		if (!changing) { owned = false; suppressedStop = stop; }
	}
	void update(String pausedStop, boolean wanted, BooleanSupplier frozen, Consumer<Boolean> setFrozen) {
		if (!wanted) {
			if (owned) {
				try { changing = true; setFrozen.accept(false); }
				finally { changing = false; owned = false; }
			}
			stop = ""; suppressedStop = ""; return;
		}
		if (!stop.equals(pausedStop)) { stop = pausedStop; suppressedStop = ""; }
		if (!owned && !stop.equals(suppressedStop) && !frozen.getAsBoolean()) {
			try { changing = true; setFrozen.accept(true); owned = true; }
			finally { changing = false; }
		}
	}
}
