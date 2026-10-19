package wiki.ica.xiaomiao.mcluaide.client;

final class LuaCodeFormatterSmoke {
	private static int checks;
	static void verify() throws Exception {
		checks = 0;
		lua("if true then\n\tprint(1)\n        else\n print(2)\n   end\n",
				"if true then\n    print(1)\nelse\n    print(2)\nend\n");
		lua("MC.Event.onItemUsed(function(event)\n        if event then\nprint(event)\nend\n    end)",
				"MC.Event.onItemUsed(function(event)\n    if event then\n        print(event)\n    end\nend)");
		lua("local t = {\n    a = {\nx = 1,\n            },\n        handler = function(x)\nprint(x)\n     end,\n    }",
				"local t = {\n    a = {\n        x = 1,\n    },\n    handler = function(x)\n        print(x)\n    end,\n}");
		lua("if true then\nlocal s = [=[\n   value  \n    ]=]\nprint(s)\nend",
				"if true then\n    local s = [=[\n   value  \n    ]=]\n    print(s)\nend");
		lua("if true then print('end then do') end", "if true then print('end then do') end");
		lua("if true then\r\n\tprint(1)  \r\n\tend\r\n", "if true then\r\n    print(1)  \r\nend\r\n");
		lua("--[[ if function(x)\n     end ]]\nlocal s = ' if end '", "--[[ if function(x)\nend ]]\nlocal s = ' if end '");
		equal(LuaCodeFormatter.format("{\"x\":{\"中文\":\"<abc>\"}}\n", "test.json", true),
				"{\n    \"x\": {\n        \"中文\": \"<abc>\"\n    }\n}\n");
		equal(LuaCodeFormatter.format("  say hello  \n execute as @a run say end\n", "test.mcfunction", true), "say hello\nexecute as @a run say end\n");
		try { LuaCodeFormatter.format("{broken", "test.json", true); throw new AssertionError("Invalid JSON accepted"); }
		catch (com.google.gson.JsonParseException expected) { checks++; }
		try { LuaCodeFormatter.format("{x:1}", "test.json", true); throw new AssertionError("Non-JSON syntax accepted"); }
		catch (com.google.gson.JsonParseException expected) { checks++; }
		try { LuaCodeFormatter.format("null garbage", "test.json", true); throw new AssertionError("Trailing garbage accepted"); }
		catch (com.google.gson.JsonParseException expected) { checks++; }
		try { LuaCodeFormatter.format("text", "en_us.lang", true); throw new AssertionError("Unsupported format accepted"); }
		catch (IllegalArgumentException expected) { checks++; }
		String before = "if true then\n\tprint(1)\nend\n";
		String formatted = LuaIndentation.formatDocument(before);
		require(LuaCodeFormatter.indentationOnly(before, formatted), "Formatter should only change leading indentation");
		require(LuaCodeFormatter.indentationOnly(formatted, before), "Undo should only change indentation");
		require(!LuaCodeFormatter.indentationOnly("print(1)\n", "print(2)\n"), "Code modification must not count as indentation");
		require(!LuaCodeFormatter.indentationOnly("a\nb\n", "ab\n"), "Removed newline must move breakpoints");
		require(!LuaCodeFormatter.indentationOnly("a\n", "a"), "Final newline modification must not count as indentation");
		require(LuaCodeFormatter.indentationOnly("a\n  ", "a\n"), "Final blank indentation is allowed");
		require(LuaCodeFormatter.mapCaret(before, formatted, before.indexOf("1")) == formatted.indexOf("1"), "Caret should stay on the same code character");
		String large = "if true then\n" + "print(1)\n".repeat(25_000) + "end\n";
		require(LuaIndentation.formatDocument(large).equals("if true then\n" + "    print(1)\n".repeat(25_000) + "end\n"), "Large file indentation");
		System.out.println("Code formatter smoke passed: " + checks + " checks");
	}
	private static void lua(String source, String expected) {
		String result = LuaIndentation.formatDocument(source); equal(result, expected);
		equal(LuaIndentation.formatDocument(result), result);
	}
	private static void equal(String value, String expected) { require(value.equals(expected), "Expected [" + expected + "] but got [" + value + "]"); }
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
