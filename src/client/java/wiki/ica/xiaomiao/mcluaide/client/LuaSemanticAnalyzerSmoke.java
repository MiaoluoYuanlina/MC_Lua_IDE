package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;

/** Fast scope/flow/range regressions, run by analyzerSmoke without a game world. */
final class LuaSemanticAnalyzerSmoke {
	private static int checks;
	private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
	private static final String SPAWN = "MC.Entity.spawn('minecraft:pig', 0, 64, 0)";

	static void verify() {
		checks = 0;
		String cooldownPlayer = "local uuid = '00000000-0000-0000-0000-000000000001'\n";
		valid(cooldownPlayer + "local filter = {['minecraft:custom_data']={new_type='fire_charge'}}\n"
				+ "print(MC.Item.isOnCooldown(uuid, 'minecraft:knowledge_book', filter))\n"
				+ "MC.Item.startCooldown(uuid, 'minecraft:knowledge_book', nil, filter)\n"
				+ "MC.Item.setCooldown(uuid, 'minecraft:knowledge_book', 100, filter)\n"
				+ "MC.Item.clearCooldown(uuid, 'minecraft:knowledge_book', filter)");
		error(cooldownPlayer + "MC.Item.setCooldown(uuid, 'minecraft:knowledge_book', true)", "类型错误", "true", 2);
		error(cooldownPlayer + "MC.Item.setCooldown(uuid, 'minecraft:knowledge_book')", "参数不足", ")", 2);
		// Reproduce the stopped-callback bug: the interval captures the local UUID,
		// but the independent cleanup callback reads the still-nil global.
		String stopped = """
				Riding_Entity_UUID = nil
				function Start()
				    local Riding_Entity_UUID = MC.Entity.spawn('minecraft:block_display', 0, 64, 0)
				    MC.Event.onInterval(10, function(event)
				        MC.Entity.teleport(Riding_Entity_UUID, event.x, 64, 0)
				    end)
				end
				MC.Event.onScriptStarted(Start)
				MC.Event.onScriptStopped(function(event)
				    MC.Entity.kill(Riding_Entity_UUID)
				end)
				""";
		error(stopped, "当前是nil", "Riding_Entity_UUID", 10);
		warning(stopped, "遮蔽", "Riding_Entity_UUID", 3);
		valid(stopped.replace("local Riding_Entity_UUID =", "Riding_Entity_UUID ="));
		valid(stopped.replace("MC.Entity.kill(Riding_Entity_UUID)", "if Riding_Entity_UUID then MC.Entity.kill(Riding_Entity_UUID) end"));

		error("local x = x", "未定义变量", "x", 1);
		error("for i = i, 10 do print(i) end", "未定义变量", "i", 1);
		error("for k,v in pairs(v) do print(k,v) end", "未定义变量", "v", 1);
		error("repeat local x = 1 until x > 0\nprint(x)", "未定义变量", "x", 2);
		valid("local x = 1\ndo local x = x + 1; print(x) end; print(x)");
		valid("local i = 1; for i = i, 10 do print(i) end");
		valid("repeat local x = 1 until\n x > 0");
		valid("local function f(n) if n > 0 then return f(n-1) end end; f(2)");
		valid("function a() return b() end; function b() return 1 end; a()");
		valid("local t = {}; function t:f(x) return self, x end; t:f(1)");
		valid("local a = require('modules/sample'); a.call()");
		valid("MC.Event.onBlockPlaced(function(event) print(event.x) end)");
		error("MC.Event.onBlockPlaced(function(event) print(event.x) end)\nprint(event)", "未定义变量", "event", 2);
		valid("function a() local x = true; print(x) end\nfunction b() local x = 'ok'; MC.Chat.broadcast(x) end");
		error("local x = true; do local x = 'ok' end; MC.Chat.broadcast(x)", "类型错误", "x", 1);
		valid("local registrationId; registrationId = MC.Event.onInterval(10, function() MC.Event.off(registrationId) end)");
		valid("local function onStop() MC.Entity.kill(id) end; id = " + SPAWN + "; MC.Event.onScriptStopped(onStop)");
		valid("local id = nil; local function init() id = " + SPAWN + " end; init(); MC.Entity.kill(id)");
		valid("function init() id = " + SPAWN + " end; init(); MC.Entity.kill(id)");
		valid("local id = nil; local function init() id = " + SPAWN + " end; local function start() init() end; start(); MC.Entity.kill(id)");
		valid("local id = nil; local init = function() id = " + SPAWN + " end; init(); MC.Entity.kill(id)");

		error("local a,b = 1; MC.Chat.broadcast(b)", "类型错误", "b", 1);
		error("local a,b; MC.Entity.kill(b)", "类型错误", "b", 1);
		valid("local a,b = 'a','b'; a,b = b,a; MC.Chat.broadcast(a); MC.Chat.broadcast(b)");
		valid("local function results() return 'a','b' end; local a,b = results(); MC.Chat.broadcast(b)");
		valid("MC.Block.set(table.unpack({1,2,3,'minecraft:stone'}))");
		error("MC.Block.set((table.unpack({1,2,3,'minecraft:stone'})))", "参数不足", ")", 1);
		valid("function f(...) MC.Block.set(...) end");
		error("local call = MC.Chat.broadcast; call(true)", "类型错误", "true", 1);
		error("MC.Entity:spawn('minecraft:pig', 0, 64, 0)", "点号", "MC.Entity", 1);
		valid("local MC = {Entity={spawn=function() return 'ok' end}}; MC.Entity.spawn()");
		error("MC.Entity.kill 'bad-uuid'", "UUID 字符串", "'bad-uuid'", 1);
		valid("MC.Chat.broadcast '中文内容'");
		valid("MC.Chat.broadcast {text='中文内容'}");
		error("MC.Entity.kill((nil))", "类型错误", "(nil)", 1);
		error("MC.Entity.kill(((nil)))", "类型错误", "((nil))", 1);
		warning("local x = 1; function x(x) print(x) end", "参数", "x", 1);
		warning("local function f(x, x) return x end", "同名参数", "x", 1);

		error("local n = nil; print(n.value)", "字段", "n", 1);
		error("local n = 5; print(n[1])", "字段", "n", 1);
		error("local f = nil; f()", "不能调用", "f", 1);
		error("local f = 4; f()", "不能调用", "f", 1);
		error("local n = nil; print(n + 1)", "算术", "n", 1);
		error("print(1 + true)", "算术", "true", 1);
		error("print(-false)", "负号", "false", 1);
		error("print(#42)", "长度", "42", 1);
		error("print('bad' * 2)", "算术", "'bad'", 1);
		error("print('a' .. false)", "连接", "false", 1);
		error("print(3 < 'x')", "大小比较", "3 < 'x'", 1);
		valid("print('2' + 3, '2' * '4', 'x' .. 4, #'hello')");
		valid("local text = tostring(4); print(text + 3, text * 2)");
		valid("print('1' < '2', 'a' < 'b')");
		error("print(3 < '2')", "大小比较", "3 < '2'", 1);
		error("for i = 0.5, 2, 0.25 do MC.sleep(i) end", "类型错误", "i", 1);
		valid("local t = setmetatable({}, {__add=function(a,b) return 1 end, __call=function() return 1 end}); print(t+1); t()");
		valid("local name = 'hello'; print(name:sub(1,2))");
		error("for i = true, 10 do print(i) end", "for 初始值", "true", 1);
		warning("for i = 1, 10, 0 do print(i) end", "步长为 0", "0", 1);
		warning("print(4 / 0)", "除数为 0", "0", 1);
		warning("local t = {name='a', name='b'}", "重复的表键", "name", 1);
		warning("local t = {[1]='a', [1.0]='b'}", "重复的表键", "1.0", 1);
		warning("local t = {name='a', ['name']='b'}", "重复的表键", "'name'", 1);
		error("local t = {[nil]='a'}", "键不能是 nil", "nil", 1);
		valid("local t = {'a','b', value={value=3}}; print(t.value.value)");
		valid("print('中文：变量、停止回调、表字段、🎮')");
		valid("print('\\255', '\\xFF', '中文\\n换行')");
		valid("MC.Block.set(0,64,0,[[\nminecraft:stone]])");
		valid("MC.Block.set(0,64,0,[=[\r\nminecraft:stone]=])");
		valid("MC.Entity.addData('00000000-0000-0000-0000-000000000000', {\n    [\"minecraft:custom_name\"] = \"队伍\",\n})");
		valid("MC.Entity.addData('00000000-0000-0000-0000-000000000000', '{Fuse:40s}')");
		valid("MC.Entity.hideData('00000000-0000-0000-0000-000000000000', {\"minecraft:custom_name\"})");
		valid("MC.Block.addData(0, 64, 0, {[\"minecraft:custom_name\"] = \"宝箱\"})");
		valid("MC.Block.hideData(0, 64, 0, 'minecraft:custom_name')");

		String guarded = "local id = nil; if condition then id = " + SPAWN + " end; ";
		warning("local condition = true; " + guarded + "MC.Entity.kill(id)", "可能是 nil", "id", 1);
		valid("local condition = true; " + guarded + "if id then MC.Entity.kill(id) end");
		valid("local condition = true; " + guarded + "if id ~= nil then MC.Entity.kill(id) end");
		valid("local condition = true; " + guarded + "if not id then return end; MC.Entity.kill(id)");
		valid("local condition = true; " + guarded + "assert(id); MC.Entity.kill(id)");
		valid("local condition = true; " + guarded + "MC.Entity.kill(id or '" + UUID + "')");
		valid("local id = MC.Entity.get('" + UUID + "'); if id and id.health then print(id.health) end");
		error("MC.sleep(-1)", "毫秒数", "-1", 1);
		error("MC.Event.onInterval(0, function() end)", "毫秒数", "0", 1);
		error("MC.Event.onBlockPlaced(42)", "类型错误", "42", 1);
		error("local n = 3 / 2; MC.sleep(n)", "类型错误", "n", 1);
		valid("local n = 2 * 3; MC.sleep(n)");
		// Exact occurrences, CRLF, tabs, comments, and inserted lines must not drift.
		error("-- a\r\n\tMC.Chat.broadcast(true)", "类型错误", "true", 2);
		error("MC.Chat.broadcast(true)\n\nMC.Chat.broadcast(true)", "类型错误", "true", 3);
		error("-- 中文注释\nMC.Chat.broadcast( false )\n\n", "类型错误", "false", 2);
		error("MC.Chat.broadcast(\n-- 注释\nfalse)", "类型错误", "false", 3);
		valid("-- MC.Chat.broadcast(false)\nprint([[MC.Entity.kill(nil)]])");
		error("local s = 'a'.invalid", "符号", ".", 1);
		error("local padding = '" + "x".repeat(70_000) + "'; MC.Chat.broadcast(false)", "类型错误", "false", 1);
		valid("local x = \"minecraft:\" .. 'stone'; MC.Block.set(0,64,0,x)");
		warning("local print = function() end; print()", "遮蔽内置", "print", 1);

		String large = "do local x = 1; print(x) end\n".repeat(20_000) + "MC.Chat.broadcast(false)\n";
		long start = System.nanoTime();
		error(large, "类型错误", "false", 20_001);
		if (System.nanoTime() - start > 10_000_000_000L) throw new AssertionError("Large-file analysis exceeded ten seconds");
		LuaCodeAnalyzer.Analysis limited = analyze("MC.Chat.broadcast(false)\n".repeat(250));
		if (limited.errorCount() != 100) throw new AssertionError("Diagnostic cap failed: " + limited.diagnostics().size());
		checks++;
		System.out.println("Lua semantic analyzer smoke passed: " + checks + " checks (including 20,001-line source)");
	}

	private static LuaCodeAnalyzer.Analysis analyze(String source) { return LuaCodeAnalyzer.analyze("semantic_probe", source); }
	private static void valid(String source) {
		var result = analyze(source);
		if (!result.valid()) throw new AssertionError("Legal Lua rejected: " + source + "\n" + result.diagnostics());
		checks++;
	}
	private static void error(String source, String message, String marked, int line) {
		check(source, message, marked, line, LuaCodeAnalyzer.Severity.ERROR);
	}
	private static void warning(String source, String message, String marked, int line) {
		check(source, message, marked, line, LuaCodeAnalyzer.Severity.WARNING);
	}
	private static void check(String source, String message, String marked, int line, LuaCodeAnalyzer.Severity severity) {
		var result = analyze(source);
		if (result.diagnostics().stream().noneMatch(d -> d.severity() == severity && d.line() == line
				&& d.message().contains(message) && source.substring(d.start(), d.end()).equals(marked)))
			throw new AssertionError("Expected " + severity + " " + message + " at line " + line + " on [" + marked
					+ "]: " + result.diagnostics() + "\n" + (source.length() < 2000 ? source : "large file"));
		checks++;
	}
}
