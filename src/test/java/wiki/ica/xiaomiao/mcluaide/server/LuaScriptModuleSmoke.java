package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.jse.JsePlatform;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.io.IOException;
import java.util.Map;

/** Executable smoke test for directory names, module return values, caching and sandboxing. */
public final class LuaScriptModuleSmoke {
	private LuaScriptModuleSmoke() { }

	public static void main(String[] args) {
		if (!LuaScriptNames.isValid("demo_modules/util")
				|| LuaScriptNames.isValid("../secret")
				|| LuaScriptNames.isValid("demo//util")
				|| LuaScriptNames.isValid("demo\\util")
				|| !LuaScriptNames.moduleName("demo_modules.util.lua").equals("demo_modules/util"))
			throw new AssertionError("Script path validation failed");

		Globals globals = JsePlatform.standardGlobals();
		globals.set("MC", new LuaTable());
		globals.set("loadCount", 0);
		Map<String, String> modules = Map.of(
				"demo_modules/util", "loadCount = loadCount + 1; return { square = function(n) return n * n end }",
				"demo_modules/cycle_a", "return require('demo_modules.cycle_b')",
				"demo_modules/cycle_b", "return require('demo_modules.cycle_a')");
		LuaScriptModuleLoader.install(globals, name -> {
			String source = modules.get(name);
			if (source == null) throw new IOException("脚本不存在：" + name);
			return source;
		}, () -> { }, 100_000);
		boolean passed = globals.load("local a = MC.Script.require('demo_modules.util'); "
				+ "local b = require('demo_modules/util.lua'); "
				+ "return a == b and a.square(7) == 49 and loadCount == 1", "smoke.lua")
				.call().toboolean();
		if (!passed) throw new AssertionError("Module return value or same-run cache failed");
		expectLuaError(globals, "require('../secret')", "脚本路径");
		expectLuaError(globals, "require('demo_modules.cycle_a')", "循环引用");
		expectLuaError(globals, "require('demo_modules.missing')", "脚本不存在");
		System.out.println("Lua script module smoke passed");
	}

	private static void expectLuaError(Globals globals, String source, String expected) {
		try { globals.load(source, "expected-error.lua").call(); }
		catch (LuaError error) {
			if (error.getMessage() != null && error.getMessage().contains(expected)) return;
			throw new AssertionError("Unexpected Lua error: " + error.getMessage(), error);
		}
		throw new AssertionError("Expected Lua error containing " + expected);
	}
}
