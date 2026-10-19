package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Same-execution, script-root-only Lua module loader; never delegates to the host package path. */
final class LuaScriptModuleLoader {
	@FunctionalInterface
	interface Source {
		String read(String name) throws IOException;
	}

	private LuaScriptModuleLoader() { }

	static void install(Globals globals, Source sourceLoader, Runnable checkRuntime, int maxScriptChars) {
		install(globals, sourceLoader, checkRuntime, maxScriptChars, null, Thread::currentThread);
	}

	static void install(Globals globals, Source sourceLoader, Runnable checkRuntime, int maxScriptChars,
			Runnable waitForModule, Supplier<Object> currentFlow) {
		Map<String, LuaValue> cache = new LinkedHashMap<>();
		Map<String, Object> loading = new LinkedHashMap<>();
		Map<Object, List<String>> stacks = new LinkedHashMap<>();
		Map<Object, Object> waiting = new LinkedHashMap<>();
		long[] totalChars = {0};
		LuaValue safeRequire = new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				String name;
				try { name = LuaScriptNames.moduleName(args.checkjstring(1)); }
				catch (IllegalArgumentException error) { throw new LuaError(error.getMessage()); }
				checkRuntime.run();
				LuaValue cached = cache.get(name);
				if (cached != null) return cached;
				Object flow = currentFlow.get();
				List<String> stack = stacks.getOrDefault(flow, new ArrayList<>());
				while (loading.containsKey(name)) {
					Object owner = loading.get(name);
					Object dependency = owner;
					while (dependency != null && dependency != flow) dependency = waiting.get(dependency);
					if (owner == flow || dependency == flow || waitForModule == null)
						throw new LuaError("Lua 模块循环引用：" + String.join(" → ", stack) + " → " + name);
					waiting.put(flow, owner);
					try { checkRuntime.run(); waitForModule.run(); }
					finally { waiting.remove(flow); }
					cached = cache.get(name);
					if (cached != null) return cached;
				}
				if (cache.size() + loading.size() >= 128)
					throw new LuaError("单次运行最多加载 128 个 Lua 模块");
				String source;
				try { source = sourceLoader.read(name); }
				catch (IOException error) { throw new LuaError("加载模块 " + name + " 失败：" + error.getMessage()); }
				if (source.length() > maxScriptChars)
					throw new LuaError("模块 " + name + " 超过服务器脚本长度限制");
				totalChars[0] += source.length();
				long aggregateLimit = Math.min(20_000_000L, (long) maxScriptChars * 4L);
				if (totalChars[0] > aggregateLimit)
					throw new LuaError("本次运行加载的模块总长度超过限制：" + aggregateLimit);
				loading.put(name, flow);
				stacks.put(flow, stack);
				stack.add(name);
				try {
					LuaValue result = globals.load(source, name + ".lua").call();
					checkRuntime.run();
					if (result.isnil()) result = LuaValue.TRUE;
					cache.put(name, result);
					return result;
				} finally {
					loading.remove(name);
					stack.remove(stack.size() - 1);
					if (stack.isEmpty()) stacks.remove(flow);
				}
			}
		};
		globals.set("require", safeRequire);
		LuaTable scriptApi = new LuaTable();
		scriptApi.set("require", safeRequire);
		LuaValue mc = globals.get("MC");
		if (mc.istable()) {
			mc.set("Script", scriptApi);
			mc.set("script", scriptApi);
		}
	}
}
