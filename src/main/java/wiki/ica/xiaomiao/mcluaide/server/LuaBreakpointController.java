package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import wiki.ica.xiaomiao.mcluaide.network.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Breakpoints, stepping, conditions, hit counts, logpoints, run-to-cursor and expression evaluation for one script run.
 * Only {@link #line}, {@link #idle} and the evaluation service run on the Lua worker; commands from the network just change state
 * and notify it. Anything that executes Lua code (conditions, logpoints, watches) runs on the Lua worker, outside the monitor
 * where possible, because LuaJ's stack may only be read from the thread that owns it.
 */
final class LuaBreakpointController {
	private final String id = UUID.randomUUID().toString(), script;
	private final boolean enabled;
	private final Map<String, String> sources = new HashMap<>();
	private final Map<String, String> sourceText = new HashMap<>();
	private final Map<String, Integer> hitCounts = new HashMap<>();
	private volatile LuaBreakpoints breakpoints;
	private volatile LuaDebuggerState state;
	private volatile boolean stopped;
	private boolean paused, pauseRequested;
	private String step = "", runToFile = "";
	private int stepDepth, runToLine;
	private long sequence, pausedNanos, pauseStartedNanos;
	// Needed to run Lua code for conditions, logpoints and watches; see attach().
	private Globals globals;
	private Consumer<String> logSink = text -> { };
	private LuaValue pausedDebug;
	private List<Integer> pausedLevels = List.of();
	private final Deque<EvaluationRequest> evaluations = new ArrayDeque<>();
	private record EvaluationRequest(int frame, List<String> expressions, CompletableFuture<List<LuaDebugEvaluator.Result>> future) { }

	LuaBreakpointController(String script, boolean enabled, LuaBreakpoints breakpoints) {
		this.script = script; this.enabled = enabled; this.breakpoints = breakpoints;
		state = new LuaDebuggerState(id, script, true, enabled, false, "", 0, "", "", 0, List.of(), count(), "", false, false, true);
	}
	/** Gives the controller what it needs to evaluate expressions: the script's globals and where logpoint output goes. */
	synchronized void attach(Globals globals, Consumer<String> logSink) { this.globals = globals; this.logSink = logSink; }
	synchronized void source(String name, String source) {
		sources.put(name, LuaBreakpoints.hash(source)); sourceText.put(name, source);
	}
	LuaDebuggerState state() { return state; }
	synchronized long pausedNanos() {
		return pausedNanos + (pauseStartedNanos == 0 ? 0 : System.nanoTime() - pauseStartedNanos);
	}
	private int count() { return breakpoints.files().values().stream().mapToInt(f -> f.lines().size()).sum(); }
	synchronized void configure(LuaBreakpoints config) {
		breakpoints = config;
		publish(state.source(), state.line(), state.hash(), state.reason(), state.stack());
	}

	synchronized void control(String executionId, String command) {
		if (stopped || !enabled) throw new IllegalStateException("当前没有可调试的 Lua 脚本");
		if (!id.equals(executionId)) throw new IllegalStateException("脚本已切换，请重新读取断点信息");
		switch (command) {
			case "pause" -> { if (!paused) pauseRequested = true; }
			case "continue", "step_into", "step_over", "step_out" -> {
				if (!paused) throw new IllegalStateException("请先暂停脚本再继续或单步执行");
				step = command.equals("continue") ? "" : command;
				stepDepth = depthAtPause;
				if (stepDepth == 0 && !step.isEmpty()) step = "step_into";
				pauseRequested = false; paused = false;
				publish(state.source(), state.line(), state.hash(), "", state.stack());
				notifyAll();
			}
			default -> {
				if (command.startsWith("run_to:")) runTo(command);
				else throw new IllegalArgumentException("未知调试操作：" + command);
			}
		}
	}

	/** "run_to:folder/file:12" resumes and pauses again when that line is reached (a one-shot temporary breakpoint). */
	private void runTo(String command) {
		int split = command.lastIndexOf(':');
		String file = command.substring("run_to:".length(), Math.max("run_to:".length(), split));
		int line;
		try { line = Integer.parseInt(command.substring(split + 1)); } catch (NumberFormatException error) { throw new IllegalArgumentException("运行到光标：行号无效"); }
		if (!LuaScriptNames.isValid(file) || line < 1 || line > LuaBreakpoints.MAX_LINE) throw new IllegalArgumentException("运行到光标：位置无效");
		if (!paused) throw new IllegalStateException("请先暂停脚本再运行到光标");
		runToFile = file; runToLine = line;
		step = ""; stepDepth = depthAtPause; pauseRequested = false; paused = false;
		publish(state.source(), state.line(), state.hash(), "", state.stack());
		notifyAll();
	}
	private int depthAtPause;

	/**
	 * Evaluates expressions in a paused stack frame (0 = the frame the script stopped in). The work happens on the Lua worker while
	 * it waits in the pause; the future fails when the script is not paused or has moved on.
	 */
	synchronized CompletableFuture<List<LuaDebugEvaluator.Result>> evaluate(String executionId, int frame, List<String> expressions) {
		if (stopped || !enabled) return CompletableFuture.failedFuture(new IllegalStateException("当前没有可调试的 Lua 脚本"));
		if (!id.equals(executionId)) return CompletableFuture.failedFuture(new IllegalStateException("脚本已切换，请重新读取断点信息"));
		if (!paused) return CompletableFuture.failedFuture(new IllegalStateException("请先暂停脚本再求值"));
		if (expressions.size() > 40) return CompletableFuture.failedFuture(new IllegalArgumentException("一次最多求值 40 个表达式"));
		var request = new EvaluationRequest(frame, List.copyOf(expressions), new CompletableFuture<>());
		evaluations.add(request); notifyAll();
		return request.future();
	}

	void line(LuaValue debug, int line, Runnable captureVariables) {
		if (!enabled || stopped || !needsHook()) return;
		LuaValue info = debug.get("getinfo").invoke(LuaValue.varargsOf(
				LuaValue.valueOf(1), LuaValue.valueOf("Sln"))).arg1();
		if (info.isnil()) return;
		String name = LuaBreakpoints.scriptName(info.get("source").optjstring(""));
		if (name.isEmpty() || line < 1) return;
		String hash;
		boolean hit, runTo;
		LuaBreakpoints.Option option = LuaBreakpoints.Option.NONE;
		synchronized (this) {
			hash = sources.getOrDefault(name, "");
			LuaBreakpoints.File file = breakpoints.files().get(name);
			hit = file != null && file.lines().contains(line)
					&& (file.hash().isEmpty() || file.hash().equals(hash));
			if (hit) option = file.option(line);
			runTo = !runToFile.isEmpty() && runToFile.equals(name) && runToLine == line;
			if (!hit && !runTo && !pauseRequested && step.isEmpty()) return;
		}
		String reason = hit ? "命中断点" : "";
		if (hit && !option.isPlain()) {
			// Conditions and logpoints run Lua code, so they run here, not under the monitor that Pause and Stop also need.
			if (!option.condition().isEmpty()) {
				var result = LuaDebugEvaluator.evaluate(globals, debug, 1, option.condition());
				if (!result.ok()) reason = "断点条件出错：" + result.error(); // stop so the mistake is noticed
				else if (!result.raw().toboolean()) hit = false;
			}
			if (hit && reason.equals("命中断点")) {
				int reached;
				synchronized (this) { reached = hitCounts.merge(name + ":" + line, 1, Integer::sum); }
				if (option.hitCount() > 1 && reached < option.hitCount()) hit = false;
			}
			if (hit && reason.equals("命中断点") && option.logpoint()) {
				logSink.accept("[日志点 " + name + ".lua:" + line + "] " + LuaDebugEvaluator.interpolate(globals, debug, 1, option.logMessage()));
				hit = false; // a logpoint prints and carries on
			}
		}
		if (runTo) {
			hit = true; reason = "运行到光标";
			synchronized (this) { runToFile = ""; }
		}
		List<LuaDebuggerState.Frame> stack = new ArrayList<>();
		List<Integer> levels = new ArrayList<>();
		synchronized (this) { if (!hit && !pauseRequested && step.isEmpty()) return; }
		int depth = 0;
		for (int level = 1; level <= 128; level++) {
			LuaValue frame = debug.get("getinfo").invoke(LuaValue.varargsOf(
					LuaValue.valueOf(level), LuaValue.valueOf("Sln"))).arg1();
			if (frame.isnil()) break;
			String what = frame.get("what").optjstring("");
			if (what.equals("Lua") || what.equals("main")) depth++;
			String frameSource = LuaBreakpoints.scriptName(frame.get("source").optjstring(""));
			if (!frameSource.isEmpty() && stack.size() < 24) {
				stack.add(new LuaDebuggerState.Frame(frameSource, frame.get("currentline").optint(0),
						frame.get("name").optjstring(what.equals("main") ? "主脚本" : "匿名函数")));
				levels.add(level);
			}
		}
		synchronized (this) {
			boolean stepped = switch (step) {
				case "step_into" -> true;
				case "step_over" -> depth <= stepDepth;
				case "step_out" -> depth < stepDepth;
				default -> false;
			};
			if (!hit && !pauseRequested && !stepped) return;
			captureVariables.run();
			pausedDebug = debug; pausedLevels = List.copyOf(levels);
			awaitPause(name, line, hash, hit ? reason : pauseRequested ? "手动暂停" : "单步执行", depth, stack);
		}
	}

	private synchronized boolean needsHook() {
		return pauseRequested || !step.isEmpty() || !runToFile.isEmpty() || !breakpoints.files().isEmpty();
	}
	/** Idle listeners still accept pause/continue without blocking the server event producer. */
	synchronized void idle() {
		if (enabled && pauseRequested && !stopped) {
			pausedDebug = null; pausedLevels = List.of();
			awaitPause(script, 0, sources.getOrDefault(script, ""), "等待事件（下一次回调可单步）", 0, List.of());
		}
	}
	private void awaitPause(String source, int line, String hash, String reason,
			int depth, List<LuaDebuggerState.Frame> stack) {
		if (stopped) return;
		pauseRequested = false; step = ""; depthAtPause = depth; paused = true; sequence++;
		publish(source, line, hash, reason, List.copyOf(stack));
		long started = System.nanoTime();
		pauseStartedNanos = started;
		try {
			while (paused && !stopped) {
				if (evaluations.isEmpty()) wait(); else serveEvaluation();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			stopped = true; paused = false;
		} finally {
			pausedNanos += System.nanoTime() - started;
			pauseStartedNanos = 0;
			failPendingEvaluations();
			pausedDebug = null; pausedLevels = List.of();
		}
	}

	/** Runs one queued evaluation request on the Lua worker; the stack it reads is the one frozen by the pause. */
	private void serveEvaluation() {
		EvaluationRequest request = evaluations.poll();
		if (request == null) return;
		try {
			List<LuaDebugEvaluator.Result> results = new ArrayList<>();
			for (String expression : request.expressions()) {
				if (pausedLevels.isEmpty()) results.add(LuaDebugEvaluator.Result.failure("当前没有活动的 Lua 调用栈（正在等待事件）"));
				else {
					int level = pausedLevels.get(Math.clamp(request.frame(), 0, pausedLevels.size() - 1));
					results.add(LuaDebugEvaluator.evaluate(globals, pausedDebug, level, expression));
				}
			}
			request.future().complete(results);
		} catch (Throwable error) {
			request.future().completeExceptionally(error);
		}
	}
	private void failPendingEvaluations() {
		for (EvaluationRequest request; (request = evaluations.poll()) != null; )
			request.future().completeExceptionally(new IllegalStateException("脚本已继续运行，无法再求值"));
	}
	synchronized void finish() {
		stopped = true; paused = false;
		publish(state.source(), state.line(), state.hash(), "已停止", state.stack());
		notifyAll();
	}
	private void publish(String source, int line, String hash, String reason, List<LuaDebuggerState.Frame> stack) {
		state = new LuaDebuggerState(id, script, !stopped, enabled, paused, source, line, hash, reason,
				sequence, stack, count(), codeAt(source, line), false, false, true);
	}
	private String codeAt(String name, int line) {
		String source = sourceText.get(name);
		if (source == null || line < 1) return "";
		int start = 0;
		for (int current = 1; current < line; current++) {
			int newline = source.indexOf('\n', start);
			if (newline < 0) return "";
			start = newline + 1;
		}
		int end = source.indexOf('\n', start);
		if (end < 0) end = source.length();
		if (end > start && source.charAt(end - 1) == '\r') end--;
		return source.substring(start, Math.min(end, start + 4096))
				+ (end - start > 4096 ? " …（该行过长，预览已截断）" : "");
	}
}
