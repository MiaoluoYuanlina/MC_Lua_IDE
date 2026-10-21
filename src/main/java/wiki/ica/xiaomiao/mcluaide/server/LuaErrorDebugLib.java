package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.DebugLib;

/**
 * LuaJ's debug library, plus a notification when a Lua error starts unwinding. LuaJ builds an error's traceback through
 * {@link DebugLib#traceback(int)} from {@code LuaClosure.errorHook} while the failing frame is still on the call stack, which makes
 * that call the one place where a debugger can stop at the line that raised the error (for exception breakpoints). Calls from
 * scripts ({@code debug.traceback()}) and from Java are not reported.
 */
final class LuaErrorDebugLib extends DebugLib {
	private volatile Runnable onError;
	private boolean reporting;

	/** The library installed in {@code globals}, or null when it is a different one. */
	static LuaErrorDebugLib of(Globals globals) { return globals.debuglib instanceof LuaErrorDebugLib lib ? lib : null; }

	/** Called on the Lua thread that raised the error, with the error's frames still inspectable through the debug library. */
	void onError(Runnable listener) { onError = listener; }

	@Override public String traceback(int level) {
		String text = super.traceback(level);
		Runnable listener = onError;
		if (listener != null && !reporting && raisedByLua()) {
			reporting = true;
			try { listener.run(); } finally { reporting = false; }
		}
		return text;
	}

	private static boolean raisedByLua() {
		// Frames: raisedByLua, traceback, then the caller.
		return StackWalker.getInstance().walk(frames -> frames.skip(2).limit(1)
				.anyMatch(frame -> frame.getClassName().equals("org.luaj.vm2.LuaClosure") && frame.getMethodName().equals("errorHook")));
	}
}
