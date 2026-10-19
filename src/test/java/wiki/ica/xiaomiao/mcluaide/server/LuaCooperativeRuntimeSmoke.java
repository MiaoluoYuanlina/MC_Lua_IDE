package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real Lua stacks and hooks: independent timers, globals, locals, cancellation and errors. */
public final class LuaCooperativeRuntimeSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		try (Fixture f = new Fixture(false, 32)) {
			f.submit("main", "local n = 11\nprint('main-start')\nsleep(400)\nprint('main-end', n)");
			f.awaitOutput("main-start");
			f.submit("slow", "local n = 22\nprint('slow-start')\nsleep(900)\nprint('slow-end', n)");
			f.submit("fast", "local n = 33\nprint('fast-start')\nsleep(30)\nprint('fast-end', n)");
			f.submit("plain", "print('plain'); shared = 42");
			f.complete();
			require(f.output.indexOf("plain") < f.output.indexOf("fast-end\t33"), "Plain flow blocked by sleep");
			require(f.output.indexOf("fast-end\t33") < f.output.indexOf("main-end\t11"), "Short wait blocked by main wait");
			require(f.output.indexOf("main-end\t11") < f.output.indexOf("slow-end\t22"), "Main wait blocked by longer callback");
			require(f.globals.get("shared").checkint() == 42, "Globals no longer shared");
			require(f.locals.get("main").contains("\"name\":\"n\""), "Main locals missing after switch");
			require(f.locals.get("slow").contains("\"value\":\"22\""), "Listener locals mixed with another flow");
		}
		try (Fixture f = new Fixture(true, 32)) {
			f.submit("nested", "local c = coroutine.create(function() local v = 7; print('nested-start'); sleep(80); coroutine.yield(v); return v + 1 end); local ok, n = coroutine.resume(c); assert(ok and n == 7); local ok2, n2 = coroutine.resume(c); assert(ok2 and n2 == 8); local w = coroutine.wrap(function() sleep(5); return 9 end); assert(w() == 9); print('nested-end')");
			f.awaitOutput("nested-start");
			f.submit("other", "print('other-during-coroutine')");
			f.complete();
			require(f.output.indexOf("other-during-coroutine") < f.output.indexOf("nested-end"), "Nested coroutine blocks events");
		}
		try (Fixture f = new Fixture(false, 2)) {
			f.submit("one", "print('one'); sleep(3600000); print('should-not-run')");
			f.awaitOutput("one");
			f.submit("two", "print('two'); sleep(3600000); print('should-not-run')");
			f.awaitOutput("two");
			require(!f.runtime.submit("overflow", () -> {}), "Sleeping flows exceed event capacity");
			long start = System.nanoTime(); f.runtime.cancel(); f.runtime.close();
			require(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "Stop waited for sleep deadline");
			require(!f.output.contains("should-not-run"), "Stopped timers resumed Lua code");
		}
		try (Fixture f = new Fixture(false, 8)) {
			f.submit("zero", "local n = 5; sleep(0); assert(n == 5); local ok = pcall(sleep, -1); assert(not ok); ok = pcall(sleep, 3600001); assert(not ok)");
			f.complete(); require(true, "Zero/range check failed");
		}
		try (Fixture f = new Fixture(false, 8)) {
			f.submit("waiting", "print('waiting'); sleep(3600000); print('should-not-run')");
			f.awaitOutput("waiting");
			f.submit("error", "error('callback-failed')");
			await(() -> { try { f.runtime.checkFailure(); return false; } catch (LuaError expected) { return true; } });
			try { f.runtime.checkFailure(); throw new AssertionError("Callback error swallowed"); }
			catch (LuaError expected) { require(expected.getMessage().contains("callback-failed"), "Wrong callback error"); }
		}
		checkModules(); checkBreakpoint(); checkLoopStop(); checkSerializedFlows();
		System.out.println("Cooperative Lua sleep smoke passed: " + checks + " checks");
	}

	private static void checkModules() throws Exception {
		try (Fixture f = new Fixture(true, 8)) {
			Map<String, String> sources = Map.of("slow", "print('module-start'); sleep(100); return { value = 13 }",
					"fast", "return { value = 8 }");
			LuaScriptModuleLoader.install(f.globals, sources::get, f::check, 100_000,
					() -> f.runtime.sleepMilliseconds(1), f.runtime::currentFlow);
			f.submit("module-main", "local m = require('slow'); assert(m.value == 13); print('module-main-end')");
			f.awaitOutput("module-start");
			f.submit("module-other", "assert(require('fast').value == 8); local m = require('slow'); assert(m.value == 13); print('module-other-end')");
			f.complete();
			require(Collections.frequency(f.output, "module-start") == 1, "Waiting module loaded twice");
			require(f.output.contains("module-other-end"), "Concurrent require incorrectly reported a cycle");
		}
		try (Fixture f = new Fixture(false, 8)) {
			Map<String, String> sources = Map.of("a", "print('a-start'); sleep(50); return require('b')",
					"b", "return require('a')");
			LuaScriptModuleLoader.install(f.globals, sources::get, f::check, 100_000,
					() -> f.runtime.sleepMilliseconds(1), f.runtime::currentFlow);
			f.submit("module-a", "require('a')"); f.awaitOutput("a-start");
			f.submit("module-b", "require('b')");
			await(() -> { try { f.runtime.checkFailure(); return false; } catch (LuaError expected) { return true; } });
			try { f.runtime.checkFailure(); throw new AssertionError("Cross-flow module cycle not detected"); }
			catch (LuaError error) { require(error.getMessage().contains("循环引用"), "Wrong module cycle diagnostic"); }
		}
	}

	private static void checkSerializedFlows() throws Exception {
		try (Fixture f = new Fixture(false, 128)) {
			f.globals.set("total", LuaValue.ZERO);
			for (int i = 0; i < 64; i++)
				f.submit("stress-" + i, "local mine = " + i + "\nsleep(" + i % 5 + ")\nfor n = 1, 20 do total = total + 1 end\nassert(mine == " + i + ")");
			f.complete();
			require(f.globals.get("total").checkint() == 1280, "Concurrent Lua state access lost increments");
		}
	}

	private static void checkBreakpoint() throws Exception {
		try (Fixture f = new Fixture(false, 8)) {
			String source = "local n = 17\nprint('before-break')\nsleep(80)\nprint('after-break', n)\n";
			f.debugger.source("bp", source);
			f.debugger.configure(new LuaBreakpoints(Map.of("bp", new LuaBreakpoints.File(LuaBreakpoints.hash(source), Set.of(3)))));
			f.submit("bp", source);
			await(() -> f.debugger.state().paused());
			require(f.debugger.state().line() == 3 && f.debugger.state().code().equals("sleep(80)"), "Breakpoint source wrong");
			f.debugger.control(f.debugger.state().id(), "step_over");
			await(() -> f.debugger.state().paused() && f.debugger.state().line() == 4);
			require(f.locals.get("bp").contains("\"value\":\"17\""), "Stepped locals missing");
			f.debugger.control(f.debugger.state().id(), "continue"); f.complete();
		}
	}

	private static void checkLoopStop() throws Exception {
		try (Fixture f = new Fixture(true, 8)) {
			f.submit("loop", "local c = coroutine.create(function() print('loop-start'); while true do end end); coroutine.resume(c)");
			f.awaitOutput("loop-start");
			f.cancelled.set(true); f.runtime.cancel(); f.runtime.close();
			require(!f.runtime.hasTasks(), "Nested coroutine bypassed stop hook");
		}
	}

	private static final class Fixture implements AutoCloseable {
		final Globals globals = JsePlatform.debugGlobals();
		final LuaValue debug = globals.get("debug");
		final AtomicBoolean cancelled = new AtomicBoolean();
		final List<String> output = new CopyOnWriteArrayList<>();
		final Map<String, String> locals = new ConcurrentHashMap<>();
		final LuaBreakpointController debugger = new LuaBreakpointController("sample", true, LuaBreakpoints.empty());
		volatile Object context;
		final LuaCooperativeRuntime runtime;
		Fixture(boolean coroutine, int capacity) {
			if (!coroutine) globals.set("coroutine", LuaValue.NIL);
			LuaValue hook = new VarArgFunction() {
				@Override public Varargs invoke(Varargs args) {
					check();
					if (args.arg1().tojstring().equals("line")) {
						locals.put(Objects.toString(context, "idle"), LuaDebugSnapshot.capture("sample", globals, debug, Map.of()));
						debugger.line(debug, args.arg(2).optint(0), () -> {});
					}
					return LuaValue.NIL;
				}
			};
			runtime = new LuaCooperativeRuntime(globals, hook, true, 100, capacity, this::check, c -> context = c);
			globals.set("sleep", new VarArgFunction() {
				@Override public Varargs invoke(Varargs args) { runtime.sleepMilliseconds(args.checkint(1)); return LuaValue.TRUE; }
			});
			globals.set("print", new VarArgFunction() {
				@Override public Varargs invoke(Varargs args) {
					StringJoiner line = new StringJoiner("\t");
					for (int i = 1; i <= args.narg(); i++) line.add(args.arg(i).tojstring());
					output.add(line.toString()); return LuaValue.NIL;
				}
			});
		}
		void check() { if (cancelled.get()) throw new LuaError("stopped"); }
		void submit(String name, String source) {
			// Compile before another flow starts: loading bytecode is not VM execution.
			LuaValue function = globals.load(source, name + ".lua");
			require(runtime.submit(name, function::call), "Task rejected unexpectedly");
		}
		void awaitOutput(String line) throws Exception { await(() -> output.contains(line)); }
		void complete() throws Exception { await(() -> !runtime.hasTasks()); runtime.checkFailure(); }
		@Override public void close() { debugger.finish(); cancelled.set(true); runtime.close(); }
	}
	private static void await(java.util.function.BooleanSupplier condition) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > end) throw new AssertionError("Timed out");
			Thread.sleep(2);
		}
	}
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message); checks++;
	}
}
