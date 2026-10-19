package wiki.ica.xiaomiao.mcluaide.client;

/** Regression cases for Enter, closing tokens and generated/multiline insertions. */
final class LuaIndentationSmoke {
	private static int checks;

	static void verify() {
		checks = 0;
		newline("MC.Event.onBlockPlaced(function(event)", 1);
		newline("MC.Event.onInterval(1000, function(event)", 1);
		newline("local id = MC.Event.onBlockPlaced(function(event)\n    print(event.x)", 1);
		newline("MC.Event.onBlockPlaced(function(event)\n    if event.x then", 2);
		newline("MC.Event.onBlockPlaced(function(event)\n    if event.x then\n    end", 1);
		newline("MC.Event.onBlockPlaced(function(event)\nend)", 0);
		newline("foo(function(event)\nend,", 1);
		line("foo(function(event)\n|end,", 0);
		newline("if true then\n    MC.Event.onBlockPlaced(function(event)", 2);
		newline("function f(x)", 1);
		newline("function f(x) print(", 2);
		newline("function f\n(\n    x,\n    y", 1);
		newline("function f(\n    x,\n    y\n)", 1);
		newline("foo(\n    function(event)", 2);
		newline("foo(\n    function(event)\n    end", 1);
		newline("foo(\n    function(event)\n    end\n)", 0);
		newline("local config = {\n    handler = function(event)", 2);
		newline("local config = {\n    handler = function(event)\n    end,", 1);
		newline("for i = 1, 10 do", 1);
		newline("while true do", 1);
		newline("do", 1);
		newline("repeat\n    do\n    end", 1);
		newline("repeat\n    print('until end')\nuntil true", 0);
		newline("if true then -- function(x) {\n    local text = 'end) {}'", 1);
		newline("if true then\n    --[[\n        function f()\n        end\n    ]]", 1);
		line("MC.Event.onBlockPlaced(function(event)\n|end)", 0);
		line("if true then\n    MC.Event.onBlockPlaced(function(event)\n|end)", 1);
		line("if true then\n|elseif false then", 0);
		line("if true then\n|else", 0);
		line("local t = {\n    nested = {\n|    }", 1);
		line("local t = {\n|}", 0);
		paste("MC.Event.onBlockPlaced(function(event)\nprint(event.x)\nend)", "", 0,
				"MC.Event.onBlockPlaced(function(event)\n    print(event.x)\nend)");
		paste("if event.x then\nprint(event.x)\nend", "MC.Event.onBlockPlaced(function(event)\n", 42,
				"    if event.x then\n        print(event.x)\n    end");
		String before = "function f()\n    ";
		paste("{\nvalue = 1\n}", before, before.length(), "{\n        value = 1\n    }");
		paste("{\nvalue = 1\n}", "function f()\n\t", 14, "{\n        value = 1\n    }");
		paste("local t = {\na = 1\n}", "if true then\n", 13, "    local t = {\n        a = 1\n    }");
		paste("if true then print(1) end", "", 0, "if true then\n    print(1)\nend");
		paste("foo(function(x) print(x) end)", "", 0, "foo(function(x)\n    print(x)\nend)");
		paste("print(1)\r\nprint(2)", "", 0, "print(1)\nprint(2)");
		paste("local s = [[\n  keep these spaces\n]]\nprint(s)", "", 0,
				"local s = [[\n  keep these spaces\n]]\nprint(s)");
		paste("print(1)\nprint(2)", "\nprint(3)", 0, "print(1)\nprint(2)");
		System.out.println("Lua indentation smoke passed: " + checks + " checks");
	}
	private static void newline(String source, int depth) {
		equal("newline: " + source, LuaIndentation.indentForNewLine(source, source.length()), "    ".repeat(depth));
	}
	private static void line(String marked, int depth) {
		int cursor = marked.indexOf('|');
		equal("closing: " + marked, LuaIndentation.desiredIndentForLine(marked.replace("|", ""), cursor), "    ".repeat(depth));
	}
	private static void paste(String clipboard, String source, int start, String expected) {
		equal("paste: " + clipboard, LuaIndentation.formatPaste(clipboard, source, start), expected);
	}
	private static void equal(String name, String actual, String expected) {
		checks++;
		if (!actual.equals(expected)) throw new AssertionError(name + "\nExpected [" + expected + "]\nActual [" + actual + "]");
	}
}
