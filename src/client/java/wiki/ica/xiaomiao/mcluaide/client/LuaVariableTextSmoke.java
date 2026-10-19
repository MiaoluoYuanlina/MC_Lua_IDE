package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;

public final class LuaVariableTextSmoke {
	private static int checks;
	public static void main(String[] args) {
		var text = new LuaVariableText();
		var rows = List.of(new LuaDebugHud.DisplayRow("局部与闭包变量", "", "", 0, true),
				new LuaDebugHud.DisplayRow("监听 #1", "", "", 1, true),
				new LuaDebugHud.DisplayRow("count = ", "123", "number", 2, false),
				new LuaDebugHud.DisplayRow("name = ", "\"中文😀\"", "string", 2, false));
		text.update(rows);
		require(text.source().contains("        count = 123\n") && text.lines().size() == 4, "Tree text/indentation lost");
		int start = text.source().indexOf("123"); text.begin(start); text.drag(start + 3); text.release();
		require(text.selected().equals("123") && text.hasSelection() && !text.selecting(), "Partial text selection");
		var next = List.of(new LuaDebugHud.DisplayRow("count = ", "999", "number", 1, false));
		text.update(next); require(text.selected().equals("123") && text.source().contains("123"), "Live snapshot changed selected text");
		text.clear(); text.update(next); require(text.source().contains("999") && !text.hasSelection(), "Clear did not resume live text");
		text.clear(); text.update(rows); text.begin(start + 3); text.drag(start); text.release();
		require(text.selected().equals("123"), "Reverse selection");
		text.clear(); text.update(rows); text.selectLine(3);
		require(text.selected().equals("        name = \"中文😀\""), "Double-click line missing full value");
		text.clear(); text.update(rows); int emoji = text.source().indexOf("😀"); text.begin(emoji + 1);
		require(text.start() == emoji, "Selection splits UTF-16 surrogate");
		text.drag(emoji + 2); text.release(); require(text.selected().equals("😀"), "Emoji selection");
		text.clear(); text.update(rows);
		var line = text.lines().get(2);
		require(text.offsetAt(2, 0, String::length) == line.textStart(), "Text left-edge hit");
		require(text.offsetAt(2, -1, String::length) == line.start(), "Indent hit");
		require(text.offsetAt(2, 1000, String::length) == line.end(), "Long line endpoint");
		String longValue = "中😀".repeat(6000);
		text.update(List.of(new LuaDebugHud.DisplayRow("long = ", longValue, "string", 0, false))); text.selectAll();
		require(text.selected().equals("long = " + longValue), "Ctrl+A truncated full value");
		text.clear(); text.update(List.of()); text.selectAll();
		require(text.selected().isEmpty() && text.offsetAt(1, 5, String::length) == 0, "Empty window selection");
		System.out.println("Variable text smoke passed: " + checks + " checks; tree text, range/line/all selection, Unicode, complete long values and frozen live snapshots");
	}
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
