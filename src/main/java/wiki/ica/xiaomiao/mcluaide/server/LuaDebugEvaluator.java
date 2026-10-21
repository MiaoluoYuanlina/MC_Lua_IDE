package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Evaluates a Lua expression in the context of a paused stack frame: the frame's local variables and upvalues are visible, then
 * the globals. It only reads: the environment is a fresh table, so an assignment inside the expression cannot change the frame's
 * locals (function calls inside the expression do run, exactly as in the Lua code being debugged).
 * <p>
 * It must be called on the Lua thread that is stopped in the debug hook, because LuaJ's stack is not safe to read from other threads.
 */
final class LuaDebugEvaluator {
	static final int MAX_EXPRESSION_CHARS = 512, MAX_LOCALS = 250;

	/** {@code raw} is the evaluated value (for truthiness), valid only when {@code ok}. */
	record Result(boolean ok, String text, String type, String error, LuaValue raw) {
		static Result failure(String message) { return new Result(false, "", "", message, LuaValue.NIL); }
	}

	private LuaDebugEvaluator() { }

	/** @param level Lua stack level as used by debug.getinfo: 1 is the function that is currently executing */
	static Result evaluate(Globals globals, LuaValue debug, int level, String expression) {
		String expr = expression == null ? "" : expression.strip();
		if (expr.isEmpty()) return Result.failure("表达式为空");
		if (expr.length() > MAX_EXPRESSION_CHARS) return Result.failure("表达式过长（最多 " + MAX_EXPRESSION_CHARS + " 个字符）");
		if (globals == null || debug == null || debug.isnil()) return Result.failure("调试环境不可用");
		try {
			// LuaJ runs hooks only for closures whose environment is a Globals, so the frame's view is a Globals that shares the
			// script's debug library and running thread: otherwise the budget hook below could never interrupt the expression.
			Globals environment = new Globals();
			environment.debuglib = globals.debuglib;
			environment.running = globals.running;
			LuaValue getLocal = debug.get("getlocal"), getUpvalue = debug.get("getupvalue"), getInfo = debug.get("getinfo");
			for (int index = 1; index <= MAX_LOCALS; index++) {
				Varargs local = getLocal.invoke(LuaValue.varargsOf(LuaValue.valueOf(level), LuaValue.valueOf(index)));
				LuaValue name = local.arg1();
				if (name.isnil()) break;
				String text = name.tojstring();
				if (!text.startsWith("(")) environment.set(text, local.arg(2)); // a later (inner) local shadows an earlier one
			}
			LuaValue function = getInfo.invoke(LuaValue.varargsOf(LuaValue.valueOf(level), LuaValue.valueOf("f"))).arg1().get("func");
			LuaValue fallback = globals;
			if (function.isfunction()) for (int index = 1; index <= MAX_LOCALS; index++) {
				Varargs upvalue = getUpvalue.invoke(LuaValue.varargsOf(function, LuaValue.valueOf(index)));
				LuaValue name = upvalue.arg1();
				if (name.isnil()) break;
				String text = name.tojstring();
				if (text.equals("_ENV")) continue;
				if (environment.rawget(text).isnil()) environment.set(text, upvalue.arg(2));
			}
			LuaTable meta = new LuaTable();
			meta.set("__index", fallback);
			environment.setmetatable(meta);
			LuaValue chunk = globals.load("return " + expr, "=eval", environment);
			LuaValue value = budgeted(globals, chunk);
			return new Result(true, LuaDebugSnapshot.describeValue(value), value.typename(), "", value);
		} catch (LuaError error) {
			return Result.failure(clean(error.getMessage()));
		} catch (RuntimeException error) {
			return Result.failure(clean(error.getMessage()));
		}
	}

	/** Instructions and wall time one expression may use: plenty for reading values, small enough that a mistake cannot hang the script. */
	static final int BUDGET_INSTRUCTIONS = 200_000;
	static final long BUDGET_NANOS = 250_000_000L;
	static final String BUDGET_MESSAGE = "表达式超出执行预算（可能是死循环或过重的计算）";
	private static final int BUDGET_STEP = 100;
	private static final ThreadLocal<Boolean> EVALUATING = new ThreadLocal<>();

	/** True while this thread is evaluating an expression; errors raised by the expression are not "exceptions" of the script. */
	static boolean evaluating() { return Boolean.TRUE.equals(EVALUATING.get()); }

	/**
	 * Runs the expression with a temporary count hook. Evaluation happens inside the debugger's own hook, where LuaJ normally
	 * suppresses every hook (so the script's stop hook could not interrupt an endless expression); this swaps in a budget hook for
	 * the duration and restores the thread's hook state afterwards.
	 */
	private static LuaValue budgeted(Globals globals, LuaValue chunk) {
		LuaThread.State state = globals.running.state;
		LuaValue hook = state.hookfunc;
		boolean line = state.hookline, call = state.hookcall, ret = state.hookrtrn, inHook = state.inhook;
		int count = state.hookcount;
		long deadline = System.nanoTime() + BUDGET_NANOS;
		int[] steps = {0};
		state.hookfunc = new VarArgFunction() {
			@Override public Varargs invoke(Varargs args) {
				if (++steps[0] * BUDGET_STEP > BUDGET_INSTRUCTIONS || System.nanoTime() > deadline) throw new LuaError(BUDGET_MESSAGE);
				return LuaValue.NONE;
			}
		};
		state.hookcount = BUDGET_STEP; state.hookline = false; state.hookcall = false; state.hookrtrn = false; state.inhook = false;
		Boolean outer = EVALUATING.get();
		EVALUATING.set(Boolean.TRUE);
		try {
			return chunk.invoke().arg1();
		} finally {
			state.hookfunc = hook; state.hookcount = count; state.hookline = line; state.hookcall = call; state.hookrtrn = ret; state.inhook = inHook;
			if (outer == null) EVALUATING.remove(); else EVALUATING.set(outer);
		}
	}

	private static String clean(String message) {
		if (message == null || message.isBlank()) return "求值失败";
		// Messages look like "eval:1 attempt to ..."; the chunk name is noise for the user.
		String text = message.replaceFirst("^eval:\\d+\\s*", "").replaceFirst("^\\[string \"[^\"]*\"]:\\d+:\\s*", "");
		return text.length() > 300 ? text.substring(0, 300) + "…" : text;
	}

	/**
	 * Fills a logpoint message: every {expression} is evaluated in the frame. Braces can be written literally as \{ and \}.
	 * An expression that fails is shown as {<error>} so a typo is visible instead of silently dropped.
	 */
	static String interpolate(Globals globals, LuaValue debug, int level, String message) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < message.length(); i++) {
			char c = message.charAt(i);
			if (c == '\\' && i + 1 < message.length() && (message.charAt(i + 1) == '{' || message.charAt(i + 1) == '}')) { out.append(message.charAt(++i)); continue; }
			if (c != '{') { out.append(c); continue; }
			int depth = 1, end = i + 1;
			while (end < message.length() && depth > 0) {
				char d = message.charAt(end);
				if (d == '{') depth++; else if (d == '}') depth--;
				end++;
			}
			if (depth != 0) { out.append(message, i, message.length()); break; }
			Result result = evaluate(globals, debug, level, message.substring(i + 1, end - 1));
			out.append(result.ok() ? plain(result) : "{<" + result.error() + ">}");
			i = end - 1;
		}
		return out.toString();
	}

	/** Strings print without quotes in logs; everything else uses the debugger's value format. */
	private static String plain(Result result) {
		return result.raw().type() == LuaValue.TSTRING ? result.raw().tojstring() : result.text();
	}
}
