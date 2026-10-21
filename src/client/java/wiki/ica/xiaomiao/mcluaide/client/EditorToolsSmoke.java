package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;

/** Pure-logic checks for the editor tools: lexer, brackets, find/replace, symbols, snippets and diff. No game classes needed. */
public final class EditorToolsSmoke {
	private static int checks;

	private EditorToolsSmoke() { }

	public static void main(String[] args) throws Exception {
		lexer(); brackets(); search(); symbols(); snippets(); diff(); fuzzy();
		System.out.println("Editor tools smoke passed: " + checks + " checks; lexer, brackets, find/replace, symbols/rename/outline, snippets and diff");
	}

	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

	private static void lexer() {
		var tokens = LuaLexer.tokenize("local s = \"a--b\" -- c\nx = [[long ]] .. 'q'");
		require(tokens.stream().filter(t -> t.kind() == LuaLexer.Kind.COMMENT).count() == 1, "one comment");
		require(tokens.stream().anyMatch(t -> t.kind() == LuaLexer.Kind.STRING && t.text().equals("\"a--b\"")), "dashes inside a string are not a comment");
		require(tokens.stream().anyMatch(t -> t.kind() == LuaLexer.Kind.STRING && t.text().equals("[[long ]]")), "long string");
		var longComment = LuaLexer.tokenize("--[==[ a ]] b ]==] y");
		require(longComment.size() == 2 && longComment.get(0).kind() == LuaLexer.Kind.COMMENT && longComment.get(1).text().equals("y"), "levelled long comment");
		var broken = LuaLexer.tokenize("print(\"unterminated\nx = 1");
		require(broken.stream().anyMatch(t -> t.text().equals("x")), "an unterminated string ends at the line break");
		require(LuaLexer.tokenize("0x1F 3.5e-2 ..").size() == 3, "numbers and operators");
		require(LuaLexer.tokenize("").isEmpty(), "empty input");
	}

	private static void brackets() {
		String source = "f(a[1], {x=\")\"})";
		var tokens = LuaLexer.tokenize(source);
		var pairs = BracketMatcher.pairs(tokens);
		require(pairs.size() == 3, "three pairs; the bracket inside the string is ignored, got " + pairs.size());
		var outer = BracketMatcher.at(pairs, 2); // just after "("
		require(outer != null && outer.open() == 1 && outer.close() == source.length() - 1, "outer pair found from the caret after its opener");
		require(BracketMatcher.at(pairs, source.length()).open() == 1, "closing bracket directly before the caret");
		require(BracketMatcher.at(pairs, 0) == null, "no pair next to plain text");
		require(BracketMatcher.unmatched(LuaLexer.tokenize("f(a")).equals(List.of(1)), "unmatched opener reported");
		require(BracketMatcher.unmatched(LuaLexer.tokenize("a)")).equals(List.of(1)), "unmatched closer reported");
		require(BracketMatcher.shouldAutoClose("print", 5, '('), "auto-close a bracket at the end");
		require(!BracketMatcher.shouldAutoClose("don", 3, '\''), "an apostrophe inside a word does not pair");
		require(BracketMatcher.shouldAutoClose("x = ", 4, '"'), "a quote after a space pairs");
		require(BracketMatcher.stepsOver("f()", 2, ')') && !BracketMatcher.stepsOver("f(a", 2, ')'), "step over an existing closer");
		require(BracketMatcher.deletesPair("()", 1) && !BracketMatcher.deletesPair("(a)", 1), "backspace removes an empty pair");
	}

	private static void search() throws Exception {
		var plain = EditorSearch.Options.DEFAULT;
		require(EditorSearch.findAll("Foo foo FOO", "foo", plain).size() == 3, "case-insensitive");
		require(EditorSearch.findAll("Foo foo FOO", "foo", new EditorSearch.Options(true, false, false)).size() == 1, "case-sensitive");
		require(EditorSearch.findAll("foobar foo", "foo", new EditorSearch.Options(false, true, false)).size() == 1, "whole word");
		require(EditorSearch.findAll("a.b a+b", "a.b", plain).size() == 1, "literal mode escapes regex characters");
		require(EditorSearch.findAll("foo fooo", "fo+", new EditorSearch.Options(false, false, true)).size() == 2, "regex");
		require(EditorSearch.replaceAll("foo fooo", "f(o+)", new EditorSearch.Options(false, false, true), "<$1>").text().equals("<oo> <ooo>"), "regex groups");
		require(EditorSearch.replaceAll("a.b a+b", "a.b", plain, "X").text().equals("X a+b"), "literal replace");
		require(EditorSearch.replaceAll("aaa", "a", plain, "$1").text().equals("$1$1$1"), "a literal replacement keeps $ signs");
		require(EditorSearch.replaceAll("nothing", "x", plain, "y").count() == 0, "no matches");
		require(EditorSearch.find("a b a", "a", plain, 1, true).start() == 4, "next match");
		require(EditorSearch.find("a b a", "a", plain, 5, true).start() == 0, "wraps to the first match");
		require(EditorSearch.find("a b a", "a", plain, 4, false).start() == 0, "previous match");
		require(EditorSearch.find("a b a", "a", plain, 0, false).start() == 4, "wraps backwards");
		require(EditorSearch.replacementFor("foo", new EditorSearch.Match(0, 3), "f(o+)", new EditorSearch.Options(false, false, true), "[$1]").equals("[oo]"), "single regex replacement");
		boolean rejected = false;
		try { EditorSearch.findAll("x", "(", new EditorSearch.Options(false, false, true)); } catch (EditorSearch.QueryException expected) { rejected = true; }
		require(rejected, "an invalid regular expression is rejected, not thrown as a crash");
		long start = System.nanoTime();
		// Backtracking-prone patterns must never freeze the game: newer JDKs optimise some of them, the rest hit the time limit.
		for (String pattern : new String[]{"(a+)+$", "(a|aa)+$", "(a*)*b$x"}) {
			try { EditorSearch.findAll("a".repeat(45) + "c", pattern, new EditorSearch.Options(false, false, true)); } catch (EditorSearch.QueryException expected) { }
		}
		require((System.nanoTime() - start) < 4_000_000_000L, "backtracking-prone regular expressions return or abort promptly");
	}

	private static void symbols() {
		String source = """
				local x = 1
				local function f(a)
				  local x = a + x
				  return x
				end
				for i = 1, 3 do print(i) end
				y = f(x)
				function g() return y end
				local t = { key = x }
				""";
		var index = LuaSymbolIndex.build(source);
		int outerX = source.indexOf("x = 1"), innerX = source.indexOf("x = a"), rhsX = source.indexOf("+ x") + 2, returnX = source.indexOf("return x") + 7;
		var outer = index.definitionAt(outerX);
		require(outer != null && outer.kind() == LuaSymbolIndex.Kind.LOCAL, "outer x is a local");
		require(index.definitionAt(rhsX).id() == outer.id(), "the right side of 'local x = a + x' still sees the OUTER x");
		require(index.definitionAt(returnX).id() == index.definitionAt(innerX).id() && index.definitionAt(returnX).id() != outer.id(), "after the statement x is the inner local");
		require(index.referencesOf(outer.id()).size() == 4, "outer x: declaration, inner right side, y = f(x), the table value; got " + index.referencesOf(outer.id()).size());
		require(index.referencesOf(index.definitionAt(innerX).id()).size() == 2, "inner x: declaration and return");
		var parameter = index.definitionAt(source.indexOf("(a)") + 1);
		require(parameter.kind() == LuaSymbolIndex.Kind.PARAMETER && index.referencesOf(parameter.id()).size() == 2, "parameter a");
		var loop = index.definitionAt(source.indexOf("for i") + 4);
		require(loop.kind() == LuaSymbolIndex.Kind.LOOP_VARIABLE && index.referencesOf(loop.id()).size() == 2, "loop variable i");
		var global = index.definitionAt(source.indexOf("y = f"));
		require(global.kind() == LuaSymbolIndex.Kind.GLOBAL && index.referencesOf(global.id()).size() == 2, "global y used twice");
		require(index.definitionAt(source.indexOf("key")) == null, "a table key is not a variable");
		var names = index.outline().stream().map(LuaSymbolIndex.OutlineEntry::name).toList();
		require(names.equals(List.of("f", "g")), "outline lists the functions, got " + names);
		var edits = index.renameEdits(outerX, "count");
		require(edits != null && edits.size() == 4, "rename touches every reference");
		String renamed = LuaSymbolIndex.apply(source, edits);
		require(renamed.contains("local count = 1") && renamed.contains("local x = a + count") && renamed.contains("return x") && renamed.contains("{ key = count }"),
				"rename changes only the outer variable");
		require(index.renameEdits(outerX, "end") == null && index.renameEdits(outerX, "1bad") == null && index.renameEdits(outerX, "ok_name") != null, "name validation");
		var unfinished = LuaSymbolIndex.build("local a = 1\nlocal function broken(\n  print(a)\n");
		require(unfinished.symbols().stream().anyMatch(s -> s.name().equals("a")), "half-typed code still produces symbols");
		var shadow = LuaSymbolIndex.build("local v = 1\nif true then local v = 2 print(v) end\nprint(v)");
		require(shadow.referencesOf(shadow.definitionAt(0 + 6).id()).size() == 2, "a local inside a block does not leak out");
	}

	private static void snippets() {
		var snippet = LuaSnippets.find("for");
		require(snippet != null && LuaSnippets.find("nope") == null, "lookup");
		var expansion = LuaSnippets.expand(snippet, "  ");
		require(expansion.text().equals("for i = 1, 10 do\n      \n  end"), "indentation is applied to following lines, got " + expansion.text().replace("\n", "|"));
		require(expansion.stops().get(0).index() == 1 && expansion.text().substring(expansion.stops().get(0).start(), expansion.stops().get(0).end()).equals("i"), "first stop is the loop variable");
		require(expansion.stops().getLast().index() == 0, "$0 is visited last");
		require(LuaSnippets.wordBefore("local x = fori", 14).equals("fori") && LuaSnippets.wordBefore("a.b", 3).equals("b"), "word before the caret");
		require(LuaSnippets.indentAt("if x then\n    foo", 17).equals("    "), "indent of the current line");
		require(LuaSnippets.all().size() >= 10, "built-in templates");
	}

	private static void fuzzy() {
		require(FuzzyMatcher.score("mn", "main.lua") >= 0 && FuzzyMatcher.score("xyz", "main.lua") == -1, "subsequence matching");
		var files = List.of("util/main_helper.lua", "main.lua", "domain.lua", "shops/Main.lua");
		var ranked = FuzzyMatcher.rank("main", files, s -> s, 10);
		require(ranked.get(0).equals("main.lua") && !ranked.contains("xyz"), "the exact file name ranks first, got " + ranked);
		require(FuzzyMatcher.rank("", files, s -> s, 2).size() == 2, "an empty query keeps the list (limited)");
		require(FuzzyMatcher.rank("mh", files, s -> s, 10).get(0).equals("util/main_helper.lua"), "word-start letters match across a path");
		require(FuzzyMatcher.score("fo", "FooBar") > FuzzyMatcher.score("fo", "xxfxxo"), "a tight match beats a scattered one");
	}

	private static void diff() {
		var changed = TextDiff.diff("a\nb\nc\n", "a\nx\nc\n");
		require(changed.size() == 4 && changed.get(1).kind() == TextDiff.Kind.DELETE && changed.get(2).kind() == TextDiff.Kind.INSERT, "one replaced line");
		require(TextDiff.changeStarts(changed).equals(List.of(1)), "change start");
		require(TextDiff.identical(TextDiff.diff("same\n", "same\n")), "identical texts");
		var added = TextDiff.diff("", "new\n");
		require(added.size() == 1 && added.get(0).kind() == TextDiff.Kind.INSERT, "everything added to an empty text");
		var mixed = TextDiff.diff("1\n2\n3\n4\n5", "1\n3\n4\n6\n5");
		require(mixed.stream().filter(l -> l.kind() == TextDiff.Kind.DELETE).count() == 1 && mixed.stream().filter(l -> l.kind() == TextDiff.Kind.INSERT).count() == 1, "multi-hunk diff");
		require(TextDiff.lines("a\nb\n").size() == 2 && TextDiff.lines("").isEmpty(), "line splitting");
		var sides = TextDiff.sideBySide(TextDiff.diff("a\nb\nc", "a\nx\nc"));
		require(sides.size() == 3 && sides.get(1).left().text().equals("b") && sides.get(1).right().text().equals("x") && sides.get(1).changed() && !sides.get(0).changed(), "side by side pairs a replaced line");
		var uneven = TextDiff.sideBySide(TextDiff.diff("a\nb\nc\nd", "a\nx\nd"));
		require(uneven.size() == 4 && uneven.get(2).right() == null && uneven.get(2).left().text().equals("c"), "two removed lines against one added line leave an empty cell");
	}
}
