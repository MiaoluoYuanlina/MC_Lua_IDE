package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.server.LuaLimitConfig;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Opt-in actual Fabric runner check. Isolated directory, never opens a user world. */
final class LuaSleepProbe {
	private static CompletableFuture<Void> probe;
	private static int checks;
	static void tick(Minecraft client) {
		if (client.getOverlay() != null) return;
		if (probe == null) {
			probe = CompletableFuture.runAsync(() -> {
				try { test(); }
				catch (Exception error) { throw new CompletionException(error); }
			});
		} else if (probe.isDone()) {
			probe.join();
			System.out.println("Lua sleep real runner probe passed: " + checks + " checks");
			client.stop();
		}
	}

	private static void test() throws Exception {
		String source = """
				MC.Event.onItemUsed(function(event)
				    local slowName = event.playerName
				    print('slow-start', slowName)
				    MC.sleep(1000)
				    print('slow-end', slowName)
				end)
				MC.Event.onItemUsed(function(event)
				    local fastName = event.playerName
				    print('fast-start', fastName)
				    MC.sleep(50)
				    print('fast-end', fastName)
				end)
				MC.Event.onBlockPlaced(function(event)
				    local blockName = event.blockId
				    print('block', blockName)
				end)
				print('main-start')
				MC.sleep(400)
				print('main-end')
				""";
		for (boolean debug : List.of(false, true)) {
			try (Run run = new Run(source, debug)) {
				run.awaitLine("main-start");
				emit("itemUsed", Map.of("playerName", "first"));
				run.awaitLine("slow-start\tfirst");
				emit("blockPlaced", Map.of("blockId", "minecraft:stone"));
				emit("itemUsed", Map.of("playerName", "second"));
				run.awaitLine("slow-end\tsecond");
				require(run.output.indexOf("fast-start\tfirst") < run.output.indexOf("slow-end\tfirst"), "Same event callback blocked");
				require(run.output.indexOf("block\tminecraft:stone") < run.output.indexOf("main-end"), "Main sleep blocked different event");
				require(run.output.indexOf("slow-start\tsecond") < run.output.indexOf("slow-end\tfirst"), "Same listener could not re-enter while waiting");
				require(run.output.indexOf("fast-end\tfirst") < run.output.indexOf("main-end"), "Short wait blocked by long wait");
				if (debug) {
					String snapshot = ServerScriptManager.debugSnapshot();
					require(snapshot.contains("slowName") && snapshot.contains("fastName") && snapshot.contains("blockName"), "Listener snapshots overwrote each other: " + snapshot);
				} else require(!LuaDebuggerState.decode(ServerScriptManager.debuggerState()).enabled(), "Normal run enabled debugger");
				run.stop(); require(run.result.get(2, TimeUnit.SECONDS).stopped(), "Stop not reported");
			}
		}
		try (Run run = new Run("MC.Event.onItemUsed(function(e) print('waiting'); MC.sleep(3600000); print('bad') end)\nprint('ready')", false)) {
			run.awaitLine("ready"); emit("itemUsed", Map.of()); run.awaitLine("waiting");
			run.stop(); require(run.result.get(2, TimeUnit.SECONDS).stopped(), "Hour-long wait not interrupted");
			require(!run.output.contains("bad"), "Stopped callback resumed code");
		}
		try (Run run = new Run("MC.Event.onItemUsed(function(e) MC.sleep(30); print('finished'); MC.Event.clear() end)\nprint('ready')", true)) {
			run.awaitLine("ready"); emit("itemUsed", Map.of());
			var result = run.result.get(3, TimeUnit.SECONDS);
			require(!result.stopped() && result.error() == null && run.output.contains("finished"), "Final sleeping callback did not finish naturally: " + result);
		}
		try (Run run = new Run("MC.Event.onItemUsed(function(e) error('event-failed') end)\nprint('ready'); MC.sleep(3600000)", false)) {
			run.awaitLine("ready"); emit("itemUsed", Map.of());
			var result = run.result.get(3, TimeUnit.SECONDS);
			require(result.error() != null && result.error().contains("event-failed"), "Callback error lost while main waits: " + result);
		}
		LuaLimits original = LuaLimitConfig.current();
		try {
			Map<String, String> values = new LinkedHashMap<>(original.toMap()); values.put("maxRuntimeSeconds", "1");
			LuaLimitConfig.save(LuaLimits.fromMap(values));
			try (Run run = new Run("MC.sleep(3600000)", false)) {
				var result = run.result.get(3, TimeUnit.SECONDS);
				require(result.error() != null && result.error().contains("最长运行时间"), "Runtime limit bypassed by waiting: " + result);
			}
			String pausedSource = "MC.Event.onItemUsed(function(e)\nlocal waiting = 9\nprint('at-breakpoint')\nMC.sleep(100)\nMC.Event.clear()\nend)\nprint('ready')\nMC.sleep(300)\nprint('main-end')\n";
			UUID owner = UUID.randomUUID();
			ServerScriptManager.configureBreakpoints(owner, new LuaBreakpoints(Map.of("sleep_probe",
					new LuaBreakpoints.File(LuaBreakpoints.hash(pausedSource), Set.of(3)))).encode());
			try (Run run = new Run(pausedSource, true, owner)) {
				run.awaitLine("ready"); emit("itemUsed", Map.of());
				await(() -> LuaDebuggerState.decode(ServerScriptManager.debuggerState()).paused());
				Thread.sleep(1200); // Longer than the configured runtime limit; all flows must remain paused.
				require(!run.result.isDone() && !run.output.contains("main-end"), "Another sleeping flow escaped breakpoint pause");
				var state = LuaDebuggerState.decode(ServerScriptManager.debuggerState());
				require(state.line() == 3 && ServerScriptManager.debugSnapshot().contains("waiting"), "Paused callback locals/source wrong");
				ServerScriptManager.controlDebugger(state.id(), "continue");
				var result = run.result.get(2, TimeUnit.SECONDS);
				require(!result.stopped() && result.error() == null && run.output.contains("main-end"), "Paused time counted against another flow's runtime: " + result);
			}
		} finally { LuaLimitConfig.save(original); }
		try (Run run = new Run("print('restart-ok')", false)) {
			require(run.result.get(2, TimeUnit.SECONDS).error() == null && run.output.contains("restart-ok"), "Old wait leaked into restarted runner");
		}
	}

	private static final class Run implements AutoCloseable {
		final List<String> output = new CopyOnWriteArrayList<>();
		final CompletableFuture<ServerScriptManager.ExecutionResult> result = new CompletableFuture<>();
		final ServerScriptManager.ScriptExecution execution;
		Run(String source, boolean debug) {
			this(source, debug, UUID.randomUUID());
		}
		Run(String source, boolean debug, UUID owner) {
			execution = ServerScriptManager.start(null, owner, "sleep_probe", source, debug, output::add, result::complete);
			ServerScriptManager.debugSnapshot(); // Subscribe before any callbacks for local sampling.
		}
		void awaitLine(String line) throws Exception {
			await(() -> {
				if (result.isDone()) throw new AssertionError("Runner ended early: " + result.join());
				return output.contains(line);
			});
		}
		void stop() { execution.stop(); }
		@Override public void close() throws Exception { if (!result.isDone()) stop(); result.get(3, TimeUnit.SECONDS); }
	}
	private static void emit(String event, Map<String, Object> data) throws Exception {
		Method method = ServerScriptManager.class.getDeclaredMethod("emitEvent", String.class, Map.class);
		method.setAccessible(true); method.invoke(null, event, data);
	}
	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError("Sleep probe timed out");
			Thread.sleep(5);
		}
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
