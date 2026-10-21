package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import wiki.ica.xiaomiao.mcluaide.network.*;
import java.util.*;
import java.util.concurrent.*;

/** Exercises real LuaJ hooks on another thread, not simulated debugger transitions. */
public final class LuaBreakpointSmoke {
	private static final String SOURCE = "local function child(n)\n"
			+ "    local doubled = n * 2\n"
			+ "    return doubled\n"
			+ "end\n"
			+ "local value = 4\n"
			+ "local answer = child(value)\n"
			+ "result = answer\n";
	public static void main(String[] args) throws Exception {
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 6), true, false)) {
			var first = f.await(6, 0);
			require(first.code().equals("local answer = child(value)"), "Paused code is not the executing source line");
			require(LuaDebuggerState.decode(first.encode()).equals(first), "Paused code did not survive transport");
			require(f.sample.contains("\"name\":\"value\"") && f.sample.contains("\"value\":\"4\""), "Paused locals missing");
			require(first.stack().getFirst().source().equals("sample"), "Wrong stack source");
			f.control("step_into"); var second = f.await(2, first.sequence());
			require(second.code().equals("    local doubled = n * 2"), "Step code lost source indentation");
			require(second.stack().size() >= 2, "Nested call stack missing");
			f.control("step_over"); var third = f.await(3, second.sequence());
			require(f.sample.contains("\"name\":\"doubled\"") && f.sample.contains("\"value\":\"8\""), "Stepped variable missing");
			f.control("step_out"); var fourth = f.await(7, third.sequence());
			f.control("continue"); f.complete();
			require(f.globals.get("result").checkint() == 8, "Continue altered result");
			require(f.debugger.pausedNanos() > 0, "Paused time not excluded from runtime limit");
		}
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 6), true, false)) {
			var first = f.await(6, 0); f.control("step_over"); f.await(7, first.sequence());
			f.control("continue"); f.complete();
		}
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 6), false, false)) {
			f.complete(); require(!f.debugger.state().paused(), "Normal run hit a breakpoint");
		}
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE + "-- changed", 6), true, false)) {
			f.complete(); require(!f.debugger.state().paused(), "Stale source breakpoint was used");
		}
		String module = "local amount = 12\nreturn amount\n";
		try (Fixture f = new Fixture("root", "result = require('lib.helper')\n", config("lib/helper", module, 2), true, false,
				Map.of("lib/helper", module))) {
			var paused = f.await(2, 0); require(paused.source().equals("lib/helper"), "Module breakpoint used root source");
			require(paused.code().equals("return amount"), "Module code came from root script");
			f.control("continue"); f.complete(); require(f.globals.get("result").checkint() == 12, "Module return corrupted");
		}
		String callback = "function callback()\nlocal eventValue = 9\nresult = eventValue\nend\n";
		try (Fixture f = new Fixture("event", callback, config("event", callback, 3), true, true)) {
			var first = f.await(3, 0); f.control("continue"); var second = f.await(3, first.sequence());
			require(second.source().equals("event"), "Callback source missing");
			f.control("continue"); f.complete();
		}
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 6), true, false)) {
			f.await(6, 0);
			boolean rejected = false;
			try { f.debugger.control("old-execution", "continue"); } catch (IllegalStateException expected) { rejected = true; }
			require(rejected && f.debugger.state().paused(), "Stale control resumed a different execution");
			f.debugger.configure(LuaBreakpoints.empty()); f.control("continue"); f.complete();
		}
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 6), true, false)) {
			f.await(6, 0); f.debugger.finish(); f.thread.interrupt(); f.thread.join(2000);
			require(!f.thread.isAlive(), "Stopped worker stayed blocked on breakpoint");
		}
		checkIdleAndPause(); checkCodec(); checkFreezeLease(); checkCodePreview(); checkAdvancedBreakpoints();
		System.out.println("Lua breakpoint smoke passed: real hooks, locals, call stack, into/over/out, modules, callbacks, normal run, stale source/control, stop and idle pause");
	}
	private static void checkIdleAndPause() throws Exception {
		LuaBreakpointController debugger = new LuaBreakpointController("listener", true, LuaBreakpoints.empty());
		debugger.control(debugger.state().id(), "pause");
		Thread worker = Thread.startVirtualThread(debugger::idle);
		long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		try {
			while (!debugger.state().paused() && System.nanoTime() < limit) Thread.sleep(5);
			require(debugger.state().paused() && debugger.state().line() == 0, "Idle listener could not pause");
			debugger.control(debugger.state().id(), "step_over"); worker.join(1000);
			require(!worker.isAlive(), "Idle single step blocked worker");
		} finally { debugger.finish(); worker.interrupt(); worker.join(1000); }
		String loop = "local total = 0\nwhile total < 100000000 do\ntotal = total + 1\nend\n";
		try (Fixture f = new Fixture("loop", loop, LuaBreakpoints.empty(), true, false)) {
			f.control("pause"); var paused = f.awaitAny(); require(paused.line() >= 1, "Manual pause has no line");
			f.debugger.finish(); f.thread.interrupt(); f.thread.join(2000);
			require(!f.thread.isAlive(), "Stop of manual pause failed");
		}
	}
	private static void checkCodec() {
		var original = config("folder/test", SOURCE, 2, 6);
		require(LuaBreakpoints.decode(original.encode()).equals(original), "Breakpoint codec changed configuration");
		for (String invalid : List.of("{\"../unsafe\":{\"hash\":\"\",\"lines\":[1]}}",
				"{\"x\":{\"hash\":\"\",\"lines\":[0]}}", "{\"x\":{\"hash\":\"\",\"lines\":[2.2]}}")) {
			boolean rejected = false;
			try { LuaBreakpoints.decode(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected, "Unsafe breakpoint config accepted: " + invalid);
		}
		require(LuaDebuggerState.decode(LuaDebuggerState.inactive().encode()).equals(LuaDebuggerState.inactive()), "Debugger state codec failed");
		var settings = LuaDebuggerState.inactive().withGamePause(true, false);
		require(LuaDebuggerState.decode(settings.encode()).equals(settings), "Server debugger options codec failed");
		require(settings.allowPlayerMovement(), "Existing player movement behavior was not preserved by default");
		var locked = settings.withGamePause(true, true, false);
		require(!LuaDebuggerState.decode(locked.encode()).allowPlayerMovement() && locked.gamePaused(), "Player movement option lost in network codec");
	}
	private static final String LOOP = "local total = 0\nfor i = 1, 5 do\n    total = total + i\nend\nresult = total\n";
	private static LuaBreakpoints withOption(String name, String source, int line, LuaBreakpoints.Option option) {
		return new LuaBreakpoints(Map.of(name, new LuaBreakpoints.File(LuaBreakpoints.hash(source), Set.of(line), Map.of(line, option))));
	}
	/** Conditions, hit counts, logpoints, expression evaluation in any frame, run to cursor: all against real LuaJ hooks. */
	private static void checkAdvancedBreakpoints() throws Exception {
		// Codec: options survive the network encoding, and an old configuration without options still decodes.
		var optioned = withOption("loop", LOOP, 3, new LuaBreakpoints.Option("i == 3", 2, "i={i}"));
		require(LuaBreakpoints.decode(optioned.encode()).equals(optioned), "breakpoint options changed in the codec");
		require(LuaBreakpoints.decode("{\"x\":{\"hash\":\"\",\"lines\":[3]}}").files().get("x").option(3).isPlain(), "legacy breakpoint configuration lost");
		require(new LuaBreakpoints.File("", Set.of(1), Map.of(2, new LuaBreakpoints.Option("x", 0, ""))).options().isEmpty(), "an option on a line without a breakpoint was kept");
		boolean rejected = false;
		try { new LuaBreakpoints.Option("x".repeat(600), 0, ""); } catch (IllegalArgumentException expected) { rejected = true; }
		require(rejected, "an oversized condition was accepted");

		// Condition: only the iteration where i == 3 stops; locals are readable through the evaluator.
		try (Fixture f = new Fixture("loop", LOOP, withOption("loop", LOOP, 3, new LuaBreakpoints.Option("i == 3", 0, "")), true, false)) {
			var stop = f.await(3, 0);
			var values = f.evaluate(0, "i", "total", "total + i * 2", "nope", "1 +");
			require(values.get(0).ok() && values.get(0).text().equals("3"), "local i was not 3: " + values.get(0));
			require(values.get(1).text().equals("3"), "total was not 3 after two iterations: " + values.get(1));
			require(values.get(2).text().equals("9"), "expression with locals: " + values.get(2));
			require(values.get(3).ok() && values.get(3).text().equals("nil"), "an unknown name evaluates to nil, like Lua");
			require(!values.get(4).ok() && !values.get(4).error().isEmpty(), "a syntax error is reported, not thrown");
			f.control("continue"); f.complete();
			require(f.globals.get("result").checkint() == 15, "the condition changed the script result");
			require(f.debugger.state().sequence() == stop.sequence(), "the condition stopped more than once");
		}
		// Hit count: stop on the 3rd time the line is reached.
		try (Fixture f = new Fixture("loop", LOOP, withOption("loop", LOOP, 3, new LuaBreakpoints.Option("", 3, "")), true, false)) {
			// "From the 3rd time on": iterations 3, 4 and 5 stop, 1 and 2 do not.
			var third = f.await(3, 0);
			require(f.evaluate(0, "i").get(0).text().equals("3"), "hit count 3 should first stop on the third iteration");
			f.control("continue"); var fourth = f.await(3, third.sequence());
			require(f.evaluate(0, "i").get(0).text().equals("4"), "and keep stopping afterwards");
			f.control("continue"); f.await(3, fourth.sequence());
			f.control("continue"); f.complete();
		}
		// Logpoint: prints and never stops.
		try (Fixture f = new Fixture("loop", LOOP, withOption("loop", LOOP, 3, new LuaBreakpoints.Option("", 0, "i={i} total={total} \\{raw\\}")), true, false)) {
			f.complete();
			require(f.logs.size() == 5, "logpoint should print once per iteration, got " + f.logs);
			require(f.logs.get(0).equals("[日志点 loop.lua:3] i=1 total=0 {raw}") && f.logs.get(4).contains("i=5 total=10"), "logpoint text: " + f.logs);
			require(!f.debugger.state().paused() && f.globals.get("result").checkint() == 15, "a logpoint must not stop or change the run");
		}
		// A condition that errors stops the script and says why, instead of being ignored.
		try (Fixture f = new Fixture("loop", LOOP, withOption("loop", LOOP, 3, new LuaBreakpoints.Option("nope.field > 1", 0, "")), true, false)) {
			var stop = f.await(3, 0);
			require(stop.reason().startsWith("断点条件出错"), "condition error reason: " + stop.reason()); f.debugger.configure(LuaBreakpoints.empty()); // the broken condition would stop on every iteration
			f.control("continue"); f.complete();
		}
		// Run to cursor: from the loop header to the last line, without an explicit breakpoint there.
		try (Fixture f = new Fixture("loop", LOOP, config("loop", LOOP, 1), true, false)) {
			var first = f.await(1, 0);
			f.control("run_to:loop:5"); var arrived = f.await(5, first.sequence());
			require(arrived.reason().equals("运行到光标"), "run to cursor reason: " + arrived.reason());
			require(f.evaluate(0, "total").get(0).text().equals("15"), "the loop should have finished before line 5");
			boolean bad = false;
			try { f.debugger.control(f.debugger.state().id(), "run_to:../x:1"); } catch (RuntimeException expected) { bad = true; }
			require(bad, "an unsafe run-to target was accepted");
			f.control("continue"); f.complete();
		}
		// Frames: evaluate in the caller of the function the script stopped in.
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 3), true, false)) {
			f.await(3, 0);
			var inner = f.evaluate(0, "doubled", "n");
			require(inner.get(0).text().equals("8") && inner.get(1).text().equals("4"), "locals of the stopped frame: " + inner);
			var outer = f.evaluate(1, "value", "answer");
			require(outer.get(0).text().equals("4") && outer.get(1).text().equals("nil"), "locals of the caller frame: " + outer);
			f.control("continue"); f.complete();
		}
		// Evaluation needs a pause, and a stale execution id is refused.
		try (Fixture f = new Fixture("sample", SOURCE, config("sample", SOURCE, 6), true, false)) {
			f.await(6, 0);
			boolean stale = false;
			try { f.debugger.evaluate("old-execution", 0, List.of("1")).get(1, TimeUnit.SECONDS); } catch (ExecutionException expected) { stale = true; }
			require(stale, "a stale execution id was evaluated");
			f.control("continue"); f.complete();
			boolean running = false;
			try { f.debugger.evaluate(f.debugger.state().id(), 0, List.of("1")).get(1, TimeUnit.SECONDS); } catch (ExecutionException expected) { running = true; }
			require(running, "an evaluation was accepted while the script was not paused");
		}
		System.out.println("Advanced breakpoint smoke passed: options codec, conditions, hit counts, logpoints, condition errors, run to cursor, frame evaluation");
	}
	private static void checkCodePreview() throws Exception {
		String source = "local value = 1\r\nresult = value + 2\r\n";
		try (Fixture f = new Fixture("crlf", source, config("crlf", source, 2), true, false)) {
			var paused = f.await(2, 0); require(paused.code().equals("result = value + 2"), "CRLF leaked into code preview");
			f.debugger.configure(config("crlf", "different edited source", 2));
			require(f.debugger.state().code().equals(paused.code()), "Editor breakpoint sync replaced executing code preview");
			f.control("continue"); f.complete();
		}
	}
	private static void checkFreezeLease() {
		LuaDebuggerFreezeLease lease = new LuaDebuggerFreezeLease();
		java.util.concurrent.atomic.AtomicBoolean frozen = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
		java.util.function.Consumer<Boolean> setter = value -> { lease.externalChange(); frozen.set(value); writes.incrementAndGet(); };
		lease.update("run:1", true, frozen::get, setter);
		require(frozen.get() && lease.owned(), "Breakpoint did not freeze world");
		lease.update("run:1", true, frozen::get, setter);
		require(writes.get() == 1, "Pause repeatedly writes tick manager state");
		lease.update("", false, frozen::get, setter);
		require(!frozen.get() && !lease.owned(), "Resume/stop/option off did not release freeze");
		frozen.set(true); int count = writes.get();
		lease.update("run:2", true, frozen::get, setter); lease.update("", false, frozen::get, setter);
		require(frozen.get() && writes.get() == count, "Debugger changed pre-existing /tick freeze");
		frozen.set(false); lease.update("run:3", true, frozen::get, setter);
		// A deliberate /tick freeze while already frozen must transfer ownership, too.
		lease.externalChange(); frozen.set(true);
		lease.update("", false, frozen::get, setter);
		require(frozen.get() && !lease.owned(), "Resume undid an explicit vanilla freeze during debugging");
		frozen.set(false); lease.update("run:4", true, frozen::get, setter);
		lease.externalChange(); frozen.set(false); lease.update("run:4", true, frozen::get, setter);
		require(!frozen.get() && !lease.owned(), "Explicit /tick unfreeze was immediately overridden");
		lease.update("run:5", true, frozen::get, setter);
		require(frozen.get() && lease.owned(), "Next breakpoint did not reacquire world freeze");
		lease.update("", false, frozen::get, setter);
		require(!frozen.get(), "Disconnect/shutdown left world frozen");
		System.out.println("Debugger world-freeze lease smoke passed: acquire, resume/stop/off, pre-existing freeze, external overrides, next pause and cleanup");
	}
	private static LuaBreakpoints config(String name, String source, Integer... lines) {
		return new LuaBreakpoints(Map.of(name, new LuaBreakpoints.File(LuaBreakpoints.hash(source), Set.of(lines))));
	}
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
	private static final class Fixture implements AutoCloseable {
		final Globals globals = JsePlatform.debugGlobals();
		final LuaBreakpointController debugger;
		final Thread thread;
		final CompletableFuture<Void> done = new CompletableFuture<>();
		volatile String sample = "";
		final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
		/** Evaluates expressions in a paused frame through the same path the network handler uses. */
		List<LuaDebugEvaluator.Result> evaluate(int frame, String... expressions) throws Exception {
			return debugger.evaluate(debugger.state().id(), frame, List.of(expressions)).get(4, TimeUnit.SECONDS);
		}
		Fixture(String name, String source, LuaBreakpoints config, boolean enabled, boolean callbacks) {
			this(name, source, config, enabled, callbacks, Map.of());
		}
		Fixture(String name, String source, LuaBreakpoints config, boolean enabled, boolean callbacks, Map<String, String> modules) {
			debugger = new LuaBreakpointController(name, enabled, config); debugger.source(name, source);
			debugger.attach(globals, logs::add);
			LuaValue debug = globals.get("debug"); Map<String, LuaValue> baseline = LuaDebugSnapshot.baseline(globals);
			LuaScriptModuleLoader.install(globals, module -> {
				String text = modules.get(module); if (text == null) throw new java.io.IOException("Missing module");
				debugger.source(module, text); return text;
			}, () -> {}, 100000);
			debug.get("sethook").call(new VarArgFunction() {
				@Override public Varargs invoke(Varargs args) {
					if (Thread.currentThread().isInterrupted()) throw new LuaError("stopped");
					if (args.arg1().tojstring().equals("line")) debugger.line(debug, args.arg(2).optint(0),
							() -> sample = LuaDebugSnapshot.capture(name, globals, debug, baseline));
					return LuaValue.NIL;
				}
			}, LuaValue.valueOf("l"), LuaValue.valueOf(1000));
			thread = Thread.startVirtualThread(() -> {
				try {
					globals.load(source, name + ".lua").call();
					if (callbacks) { globals.get("callback").call(); globals.get("callback").call(); }
					done.complete(null);
				} catch (Throwable error) { done.completeExceptionally(error); }
				finally { debugger.finish(); }
			});
		}
		void control(String action) { debugger.control(debugger.state().id(), action); }
		LuaDebuggerState await(int line, long after) throws Exception {
			long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
			while (System.nanoTime() < limit) {
				var state = debugger.state();
				if (state.paused() && state.sequence() > after) {
					require(state.line() == line, "Expected line " + line + " but paused at " + state.encode()); return state;
				}
				if (done.isDone()) { done.join(); throw new AssertionError("Script ended without pausing at " + line); }
				Thread.sleep(5);
			}
			throw new AssertionError("Timeout awaiting breakpoint " + line + ": " + debugger.state().encode());
		}
		LuaDebuggerState awaitAny() throws Exception {
			long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
			while (System.nanoTime() < limit) {
				if (debugger.state().paused()) return debugger.state();
				Thread.sleep(5);
			}
			throw new AssertionError("Manual pause timed out");
		}
		void complete() throws Exception { done.get(4, TimeUnit.SECONDS); thread.join(1000); }
		@Override public void close() throws Exception { debugger.finish(); thread.interrupt(); thread.join(2000); }
	}
}
