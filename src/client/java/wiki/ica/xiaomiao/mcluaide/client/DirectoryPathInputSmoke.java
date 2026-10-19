package wiki.ica.xiaomiao.mcluaide.client;

import java.util.function.ToIntFunction;

final class DirectoryPathInputSmoke {
	public static void main(String[] args) {
		ToIntFunction<String> width = value -> value.codePointCount(0, value.length()) * 6;
		String path = "demo/assets/test/models/item/diamond_sword.json";
		int start = DirectoryPathInput.leafStart(path);
		require(path.substring(start).equals("diamond_sword.json"), "Incorrect editable basename");
		require(DirectoryPathInput.parent(path).equals("demo/assets/test/models/item/"), "Parent prefix lost");
		var end = DirectoryPathInput.view(path, path.length(), start, 30, width);
		require(end.start() >= start && end.end() == path.length() && width.applyAsInt(end.text()) <= 30, "Long basename does not scroll to caret");
		var beginning = DirectoryPathInput.view(path, start, start, 30, width);
		require(beginning.start() == start && beginning.text().equals("diamo"), "Home does not reveal first characters");
		require(DirectoryPathInput.offsetAt(path, beginning, 7, width) == start + 1, "Mouse hit testing has wrong offset");
		require(DirectoryPathInput.offsetAt(path, beginning, -10, width) == start, "Click can edit hidden parent");
		require(DirectoryPathInput.offsetAt(path, beginning, 999, width) == beginning.end(), "Click passes visible text");
		String unicode = "parent/测试🖉_long_file.json";
		for (int cursor = unicode.indexOf('/') + 1; cursor < unicode.length(); cursor = unicode.offsetByCodePoints(cursor, 1)) {
			var view = DirectoryPathInput.view(unicode, cursor, DirectoryPathInput.leafStart(unicode), 24, width);
			require(view.start() <= cursor && view.end() >= cursor, "Caret not in Unicode viewport");
			require(view.start() == 0 || !Character.isLowSurrogate(unicode.charAt(view.start())), "Viewport splits surrogate pair");
		}
		require(DirectoryPathInput.parent("test").isEmpty(), "Root filename has parent");
		require(DirectoryPathInput.leafStart("folder/") == 7, "Empty leaf drops chosen directory");
		require(DirectoryPathInput.shorten("very_long/parent/path/", 60, width).startsWith("…"), "Parent not ellipsized");
		require(DirectoryPathInput.shorten("a/", 60, width).equals("a/"), "Short parent changed");
		System.out.println("Directory path input smoke passed: prefix, basename, narrow viewport, caret/mouse, Unicode, root and breadcrumb ellipsis");
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
