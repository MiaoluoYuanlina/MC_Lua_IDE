package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

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
			LuaTable environment = new LuaTable();
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
			LuaValue value = chunk.invoke().arg1();
			return new Result(true, LuaDebugSnapshot.describeValue(value), value.typename(), "", value);
		} catch (LuaError error) {
			return Result.failure(clean(error.getMessage()));
		} catch (RuntimeException error) {
			return Result.failure(clean(error.getMessage()));
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
