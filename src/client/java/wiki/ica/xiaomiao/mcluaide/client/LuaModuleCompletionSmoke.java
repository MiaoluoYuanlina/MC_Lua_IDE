package wiki.ica.xiaomiao.mcluaide.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LuaModuleCompletionSmoke {
	private static int checks;
	private static final String UTIL = """
			local M = {}
			local function hidden(x) return x end
			function M.greet(name)
			    --#name=问候
			    --#Introduction=返回问候语
			    --@param=name 玩家名称
			    return '你好' .. name
			end
			M.square = function(number) return number * number end
			function M:reset(value) self.value = value end
			M.exported = hidden
			M.nested = { echo = function(message) return message end }
			return M
			""";

	static void verify() {
		checks = 0;
		module("local util = require('demo/util')\nutil.|", "util", "demo/util");
		module("local util = MC.Script.require('demo.util.lua')\nutil.|", "util", "demo/util");
		module("local util = require 'demo.util'\nutil.|", "util", "demo/util");
		module("local util = require [[demo.util]]\nutil.|", "util", "demo/util");
		module("local util = require(\n    'demo.util'\n)\nutil.|", "util", "demo/util");
		module("local a, util = 1, require('demo.util')\nutil.|", "util", "demo/util");
		module("local util = require('demo.util'); util.|", "util", "demo/util");
		module("util = require('demo.util')\nutil.|", "util", "demo/util");
		module("local util = require('demo.util')\nlocal alias = util\nalias.|", "alias", "demo/util");
		module("local util = require('demo.util')\nfunction test(event)\n    util.|\nend", "util", "demo/util");
		module("local util = require('demo.util')\ndo\nlocal util = {}\nend\nutil.|", "util", "demo/util");
		module("local util = require('demo.util')\nMC.Event.onItemUsed(util.|)", "util", "demo/util");
		module("local util = require('demo.util')\nutil.greet('x')\nutil.|", "util", "demo/util");
		module("local util = require('demo.util')\nutil.value = 1\nutil.|", "util", "demo/util");
		module("local util = require('demo.util')\nutil = {}\nutil.|", "util", null);
		module("local util = require('demo.util')\nfunction test(util)\nutil.|\nend", "util", null);
		module("local util = require('demo.util')\ndo\nlocal util = {}\nutil.|\nend", "util", null);
		module("util.|\nlocal util = require('demo.util')", "util", null);
		module("do\nlocal util = require('demo.util')\nend\nutil.|", "util", null);
		module("local path = 'demo.util'\nlocal util = require(path)\nutil.|", "util", null);
		module("local util = require('../private')\nutil.|", "util", null);
		module("local util = require('demo.util').nested\nutil.|", "util", null);
		module("-- local util = require('demo.util')\nutil.|", "util", null);

		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("demo/util", UTIL);
		LuaModuleCompletion index = new LuaModuleCompletion();
		List<LuaArgumentCompletion.Choice> choices = index.choices("util", "demo/util", "util.", sources::get);
		contains(choices, "util.greet", "util.square", "util:reset", "util.exported", "util.nested.echo");
		excludes(choices, "util.hidden");
		var greet = choices.stream().filter(c -> c.insertion().equals("util.greet")).findFirst().orElseThrow();
		check(greet.label().contains("util.greet(name)") && greet.label().contains("问候"), "Module signature and Chinese label lost: " + greet);
		check(greet.tooltip().contains("demo/util.lua") && greet.tooltip().contains("第 3 行")
				&& greet.tooltip().contains("返回问候语"), "Module documentation lost: " + greet.tooltip());
		contains(index.choices("util", "demo/util", "util.sq", sources::get), "util.square");
		excludes(index.choices("util", "demo/util", "util.sq", sources::get), "util.greet");
		contains(index.choices("util", "demo/util", "util:re", sources::get), "util:reset");
		contains(index.choices("util", "demo/util", "util.nested.", sources::get), "util.nested.echo");
		check(index.choices("util", "missing", "util.", sources::get).isEmpty(), "Missing module invented members");

		sources.put("direct", "local function helper(a,b) return a+b end\nreturn { add = helper, ping = function() end }");
		contains(index.choices("d", "direct", "d.", sources::get), "d.add", "d.ping");
		excludes(index.choices("d", "direct", "d.", sources::get), "d.helper");
		sources.put("aliases", "local M={}\nlocal a=M\nfunction a.run(x) end\nM['stop']=function() end\nreturn M");
		contains(index.choices("a", "aliases", "a.", sources::get), "a.run", "a.stop");
		sources.put("reexport", "return require('demo.util')");
		contains(index.choices("r", "reexport", "r.", sources::get), "r.greet", "r.square");
		sources.put("wrapper", "return { util = MC.Script.require('demo.util') }");
		contains(index.choices("w", "wrapper", "w.util.", sources::get), "w.util.greet");
		sources.put("cycle_a", "return require('cycle_b')"); sources.put("cycle_b", "return require('cycle_a')");
		check(index.choices("c", "cycle_a", "c.", sources::get).isEmpty(), "Cyclic imports failed to terminate");
		sources.put("recursive", "local M={}\nM.self=M\nfunction M.run() end\nreturn M");
		check(index.choices("c", "recursive", "c.", sources::get).size() < 20, "Recursive tables failed to terminate");
		sources.put("invalid", "local M={}\nfunction M.bad(");
		check(index.choices("c", "invalid", "c.", sources::get).isEmpty(), "Incomplete module crashed or invented exports");
		sources.put("demo/util", "return { changed = function(x) end }");
		contains(index.choices("util", "demo/util", "util.", sources::get), "util.changed");
		excludes(index.choices("util", "demo/util", "util.", sources::get), "util.greet", "util.square");
		sources.put("no_return", "local M={}\nfunction M.run() end");
		check(index.choices("c", "no_return", "c.", sources::get).isEmpty(), "Non-exported module table leaked");
		sources.put("side_effect", "error('must never execute')\nreturn { safe = function() end }");
		contains(index.choices("c", "side_effect", "c.", sources::get), "c.safe");
		sources.put("Particle_Feast/Effect", "local M={}\nfunction M.Effect_Arc(x,y,z,x2,y2,z2,\nR,G,B,Growth,time,Height) end\nreturn M");
		var arc = index.choices("rEffect", "Particle_Feast/Effect", "rEffect.", sources::get);
		contains(arc, "rEffect.Effect_Arc");
		check(arc.getFirst().label().contains("Growth, time, Height)"), "Multiline module signature lost parameters");
		String tail = "\nreturn { large = function() end }";
		sources.put("large", "--" + "x".repeat(wiki.ica.xiaomiao.mcluaide.network.LuaProtocol.MAX_SCRIPT_CHARS - tail.length() - 2) + tail);
		contains(index.choices("c", "large", "c.", sources::get), "c.large");
		System.out.println("Lua module completion smoke passed: " + checks + " checks");
	}

	private static void module(String marked, String name, String expected) {
		int caret = marked.indexOf('|');
		var value = LuaCodeAnalyzer.visibleVariableValues(marked.replace("|", ""), caret).get(name);
		String actual = value == null ? null : value.moduleName();
		check(java.util.Objects.equals(actual, expected), "Module binding " + name + ": expected=" + expected + " actual=" + actual + "\n" + marked);
	}
	private static void contains(List<LuaArgumentCompletion.Choice> choices, String... expected) {
		for (String name : expected) check(choices.stream().anyMatch(c -> c.insertion().equals(name)), "Missing " + name + ": " + choices);
	}
	private static void excludes(List<LuaArgumentCompletion.Choice> choices, String... excluded) {
		for (String name : excluded) check(choices.stream().noneMatch(c -> c.insertion().equals(name)), "Unexpected " + name + ": " + choices);
	}
	private static void check(boolean condition, String message) {
		checks++;
		if (!condition) throw new AssertionError(message);
	}
}
