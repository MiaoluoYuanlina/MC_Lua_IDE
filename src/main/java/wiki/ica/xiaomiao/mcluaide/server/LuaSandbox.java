package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseIoLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.luaj.vm2.lib.jse.JseOsLib;
import org.luaj.vm2.lib.jse.LuajavaLib;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

/**
 * Builds Lua environments from an allowlist of libraries.
 * <p>
 * LuaJ's {@code JsePlatform.standardGlobals()} loads everything (io, os, luajava, package) and callers then had to remember
 * to clear each dangerous entry. Here a library is only loaded when the server's limits allow it, so a library that is not
 * allowed never exists in the environment at all, and a library added by a future LuaJ version is not exposed by default.
 * {@code ServerScriptManager.sandbox} still clears the same names afterwards as a second layer.
 */
public final class LuaSandbox {
	private LuaSandbox() { }

	/**
	 * Environment for running a script. The base, table, string, math, bit32, coroutine and debug libraries are always
	 * loaded: the cooperative runtime and the debugger rely on coroutines and debug hooks internally, and the sandbox
	 * only hides the names a script may see when the limits forbid them.
	 */
	public static Globals create(LuaLimits limits) {
		Globals globals = new Globals();
		globals.load(new JseBaseLib());
		if (limits.allowPackageLoading()) globals.load(new PackageLib());
		globals.load(new Bit32Lib());
		globals.load(new TableLib());
		globals.load(new StringLib());
		globals.load(new CoroutineLib());
		globals.load(new JseMathLib());
		if (limits.allowUnrestrictedFileIo()) globals.load(new JseIoLib());
		if (limits.allowOperatingSystem()) globals.load(new JseOsLib());
		if (limits.allowJavaInterop()) globals.load(new LuajavaLib());
		globals.load(new LuaErrorDebugLib());
		LoadState.install(globals);
		LuaC.install(globals);
		return globals;
	}

	/** Compiler only: enough to check syntax without exposing or even loading any library. */
	public static Globals syntaxOnly() {
		Globals globals = new Globals();
		LoadState.install(globals);
		LuaC.install(globals);
		return globals;
	}
}