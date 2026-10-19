package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class LuaDebugSnapshotSmoke {
	private LuaDebugSnapshotSmoke() { }

	public static void main(String[] args) {
		Globals globals = JsePlatform.debugGlobals();
		LuaValue debug = globals.get("debug");
		Map<String, LuaValue> baseline = LuaDebugSnapshot.baseline(globals);
		AtomicReference<String> snapshot = new AtomicReference<>("");
		AtomicReference<String> closureSnapshot = new AtomicReference<>("");
		debug.get("sethook").call(new ZeroArgFunction() {
			@Override public LuaValue call() {
				String next = LuaDebugSnapshot.capture("sample", globals, debug, baseline);
				if (next.contains("\"name\":\"score\"")) snapshot.set(next);
				if (next.contains("\"scope\":\"闭包 · 栈1\",\"name\":\"hidden\""))
					closureSnapshot.set(next);
				return LuaValue.NIL;
			}
		}, LuaValue.valueOf(""), LuaValue.valueOf(1));
		globals.load("local score = 17\nplayerName = 'Alex'\nscore = score + 1\n", "sample.lua").call();
		String result = snapshot.get();
		if (!result.contains("\"name\":\"score\"") || !result.contains("\"value\":\"18\""))
			throw new AssertionError("Local variable was not sampled: " + result);
		if (!result.contains("\"type\":\"number\""))
			throw new AssertionError("Variable type was not included: " + result);
		if (!result.contains("\"name\":\"playerName\""))
			throw new AssertionError("Script global was not sampled: " + result);
		if (result.contains("\"name\":\"print\""))
			throw new AssertionError("Built-in global leaked into variable list: " + result);
		globals.load("local hidden = 42\nfunction callback() return hidden + 1 end\ncallback()\n",
				"closure.lua").call();
		if (!closureSnapshot.get().contains("\"value\":\"42\""))
			throw new AssertionError("Closure upvalue was not sampled: " + closureSnapshot.get());

		AtomicReference<String> callbackSnapshot = new AtomicReference<>("");
		debug.get("sethook").call(new VarArgFunction() {
			@Override public Varargs invoke(Varargs arguments) {
				if (!arguments.arg1().tojstring().equals("line")) return LuaValue.NIL;
				String next = LuaDebugSnapshot.capture("callback", globals, debug, baseline);
				if (next.contains("\"name\":\"models\"")) callbackSnapshot.set(next);
				return LuaValue.NIL;
			}
		}, LuaValue.valueOf("lr"), LuaValue.valueOf(1000));
		globals.load("function onBlockUsed()\n"
				+ "  local models = { main = { id = 'test:model' } }\n"
				+ "  local observed = models\n"
				+ "end\n"
				+ "onBlockUsed()\n", "callback.lua").call();
		JsonObject captured = JsonParser.parseString(callbackSnapshot.get()).getAsJsonObject();
		boolean foundTree = false;
		for (var element : captured.getAsJsonArray("variables")) {
			JsonObject variable = element.getAsJsonObject();
			if (!variable.get("name").getAsString().equals("models")) continue;
			JsonObject main = variable.getAsJsonArray("children").get(0).getAsJsonObject();
			JsonObject id = main.getAsJsonArray("children").get(0).getAsJsonObject();
			foundTree = main.get("name").getAsString().equals("main")
					&& id.get("name").getAsString().equals("id")
					&& id.get("value").getAsString().equals("\"test:model\"");
		}
		if (!foundTree) throw new AssertionError("Callback local table was not captured as a tree: " + captured);
		globals.load("function cycleTest()\n"
				+ "  local models = {}\n"
				+ "  models.self = models\n"
				+ "  local observed = models\n"
				+ "end\n"
				+ "cycleTest()\n", "cycle.lua").call();
		if (!callbackSnapshot.get().contains("循环引用"))
			throw new AssertionError("Cyclic table was not bounded: " + callbackSnapshot.get());
		AtomicReference<String> duplicateLocals = new AtomicReference<>("");
		debug.get("sethook").call(new VarArgFunction() {
			@Override public Varargs invoke(Varargs arguments) {
				if (!arguments.arg1().tojstring().equals("line")) return LuaValue.NIL;
				String next = LuaDebugSnapshot.capture("duplicate", globals, debug, baseline);
				if (next.split("\"name\":\"registrationId\"", -1).length == 3)
					duplicateLocals.set(next);
				return LuaValue.NIL;
			}
		}, LuaValue.valueOf("l"), LuaValue.NIL);
		globals.load("local registrationId = 1\nlocal registrationId = 2\nlocal marker = registrationId\n",
				"duplicate.lua").call();
		if (duplicateLocals.get().isEmpty())
			throw new AssertionError("Same-name local registrations were not captured separately");

		LuaDebugSnapshot.History history = new LuaDebugSnapshot.History();
		history.recordMain("{\"variables\":["
				+ "{\"scope\":\"局部 · 栈1\",\"name\":\"registrationId\",\"value\":\"1\",\"type\":\"number\"},"
				+ "{\"scope\":\"局部 · 栈1\",\"name\":\"registrationId\",\"value\":\"2\",\"type\":\"number\"}"
				+ "],\"omitted\":0}");
		history.recordListener(1, "itemUsed", "{\"variables\":["
				+ "{\"scope\":\"局部 · 栈1\",\"name\":\"event\",\"value\":\"{…}\",\"type\":\"table\"}"
				+ "],\"omitted\":0}");
		history.recordListener(2, "blockPlaced", "{\"variables\":["
				+ "{\"scope\":\"局部 · 栈1\",\"name\":\"event2\",\"value\":\"{…}\",\"type\":\"table\"}"
				+ "],\"omitted\":0}");
		String combined = history.encoded("sample");
		if (!combined.contains("监听 #1 · itemUsed") || !combined.contains("监听 #2 · blockPlaced")
				|| !combined.contains("registrationId [#2]") || !combined.contains("\"name\":\"event\"")
				|| !combined.contains("\"name\":\"event2\""))
			throw new AssertionError("Listener samples were not kept separately: " + combined);
		history.removeListener(1);
		if (history.encoded("sample").contains("监听 #1 · itemUsed"))
			throw new AssertionError("Removed listener was still displayed");
	}
}
