package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.LuaValue;
import wiki.ica.xiaomiao.mcluaide.network.*;
import java.util.*;

/** Only awaitPause runs on the Lua worker. Commands just change state and notify it. */
final class LuaBreakpointController {
	private final String id = UUID.randomUUID().toString(), script;
	private final boolean enabled;
	private final Map<String, String> sources = new HashMap<>();
	private final Map<String, String> sourceText = new HashMap<>();
	private volatile LuaBreakpoints breakpoints;
	private volatile LuaDebuggerState state;
	private volatile boolean stopped;
	private boolean paused, pauseRequested;
	private String step = "";
	private int stepDepth;
	private long sequence, pausedNanos, pauseStartedNanos;

	LuaBreakpointController(String script, boolean enabled, LuaBreakpoints breakpoints) {
		this.script = script; this.enabled = enabled; this.breakpoints = breakpoints;
		state = new LuaDebuggerState(id, script, true, enabled, false, "", 0, "", "", 0, List.of(), count(), "", false, false, true);
	}
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
			default -> throw new IllegalArgumentException("未知调试操作：" + command);
		}
	}
	private int depthAtPause;

	void line(LuaValue debug, int line, Runnable captureVariables) {
		if (!enabled || stopped || !needsHook()) return;
		LuaValue info = debug.get("getinfo").invoke(LuaValue.varargsOf(
				LuaValue.valueOf(1), LuaValue.valueOf("Sln"))).arg1();
		if (info.isnil()) return;
		String name = LuaBreakpoints.scriptName(info.get("source").optjstring(""));
		if (name.isEmpty() || line < 1) return;
		String hash;
		boolean hit;
		synchronized (this) {
			hash = sources.getOrDefault(name, "");
			LuaBreakpoints.File file = breakpoints.files().get(name);
			hit = file != null && file.lines().contains(line)
					&& (file.hash().isEmpty() || file.hash().equals(hash));
			if (!hit && !pauseRequested && step.isEmpty()) return;
		}
		List<LuaDebuggerState.Frame> stack = new ArrayList<>();
		int depth = 0;
		for (int level = 1; level <= 128; level++) {
			LuaValue frame = debug.get("getinfo").invoke(LuaValue.varargsOf(
					LuaValue.valueOf(level), LuaValue.valueOf("Sln"))).arg1();
			if (frame.isnil()) break;
			String what = frame.get("what").optjstring("");
			if (what.equals("Lua") || what.equals("main")) depth++;
			String frameSource = LuaBreakpoints.scriptName(frame.get("source").optjstring(""));
			if (!frameSource.isEmpty() && stack.size() < 24)
				stack.add(new LuaDebuggerState.Frame(frameSource, frame.get("currentline").optint(0),
						frame.get("name").optjstring(what.equals("main") ? "主脚本" : "匿名函数")));
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
			awaitPause(name, line, hash, hit ? "命中断点" : pauseRequested ? "手动暂停" : "单步执行", depth, stack);
		}
	}

	private synchronized boolean needsHook() {
		return pauseRequested || !step.isEmpty() || !breakpoints.files().isEmpty();
	}
	/** Idle listeners still accept pause/continue without blocking the server event producer. */
	synchronized void idle() {
		if (enabled && pauseRequested && !stopped)
			awaitPause(script, 0, sources.getOrDefault(script, ""), "等待事件（下一次回调可单步）", 0, List.of());
	}
	private void awaitPause(String source, int line, String hash, String reason,
			int depth, List<LuaDebuggerState.Frame> stack) {
		if (stopped) return;
		pauseRequested = false; step = ""; depthAtPause = depth; paused = true; sequence++;
		publish(source, line, hash, reason, List.copyOf(stack));
		long started = System.nanoTime();
		pauseStartedNanos = started;
		try {
			while (paused && !stopped) wait();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			stopped = true; paused = false;
		} finally {
			pausedNanos += System.nanoTime() - started;
			pauseStartedNanos = 0;
		}
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
