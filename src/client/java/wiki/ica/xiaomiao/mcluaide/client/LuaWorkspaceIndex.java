package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-file navigation for the Lua scripts of a workspace, built from each file's {@link LuaSymbolIndex}:
 * <ul>
 * <li>globals: a function or variable that one script defines and another uses (scripts loaded with require share globals);</li>
 * <li>module members: {@code local util = require("lib.util")} then {@code util.format(...)} refers to
 * {@code function M.format} (or {@code M.format = ...}) in {@code lib/util.lua}.</li>
 * </ul>
 * Like the per-file index it is lexical: it does not follow values through tables, metatables or function calls.
 */
final class LuaWorkspaceIndex {
	/** One script: its path without ".lua", text and symbol index. */
	record File(String path, String source, LuaSymbolIndex symbols, List<LuaLexer.Token> tokens) {
		static File of(String path, String source) {
			List<LuaLexer.Token> tokens = new ArrayList<>();
			for (LuaLexer.Token token : LuaLexer.tokenize(source)) if (token.kind() != LuaLexer.Kind.COMMENT) tokens.add(token);
			return new File(path, source, LuaSymbolIndex.build(source), tokens);
		}
	}
	/** A place in a script; {@code line} starts at 1. */
	record Location(String path, int start, int end, int line, String preview) { }
	/** What the caret is on, seen across files: a global name, or a member of a required module. */
	record Target(String name, String module) {
		boolean member() { return module != null; }
	}

	private final Map<String, File> files = new LinkedHashMap<>();

	LuaWorkspaceIndex(Collection<File> files) { for (File file : files) this.files.put(file.path(), file); }

	File file(String path) { return files.get(path); }
	Collection<File> files() { return files.values(); }

	// ---- what is under the caret -----------------------------------------------------------------------------------------

	/** The cross-file target at {@code offset} in {@code file}, or null for locals (they never leave their file) and non-names. */
	static Target targetAt(File file, int offset) {
		int index = tokenAt(file.tokens(), offset);
		if (index < 0) return null;
		LuaLexer.Token token = file.tokens().get(index);
		if (token.kind() != LuaLexer.Kind.NAME) return null;
		// member access: X.member / X:member where X is bound to require("module")
		if (index >= 2 && (file.tokens().get(index - 1).is(".") || file.tokens().get(index - 1).is(":"))) {
			LuaLexer.Token owner = file.tokens().get(index - 2);
			String module = requiredModule(file, owner);
			return module == null ? null : new Target(token.text(), module);
		}
		LuaSymbolIndex.Occurrence occurrence = file.symbols().occurrenceAt(token.start());
		if (occurrence == null) return null;
		LuaSymbolIndex.Kind kind = file.symbols().symbol(occurrence.symbol()).kind();
		return kind == LuaSymbolIndex.Kind.GLOBAL || kind == LuaSymbolIndex.Kind.FUNCTION ? new Target(token.text(), null) : null;
	}

	private static int tokenAt(List<LuaLexer.Token> tokens, int offset) {
		int found = -1;
		for (int i = 0; i < tokens.size(); i++) {
			LuaLexer.Token token = tokens.get(i);
			if (offset >= token.start() && offset <= token.end()) { found = i; if (offset < token.end()) return i; }
			if (token.start() > offset) break;
		}
		return found;
	}

	/** If {@code name} is a local whose declaration is "local name = require(...)", the module path ("lib/util"); otherwise null. */
	private static String requiredModule(File file, LuaLexer.Token name) {
		LuaSymbolIndex.Occurrence occurrence = file.symbols().occurrenceAt(name.start());
		if (occurrence == null) return null;
		LuaSymbolIndex.Symbol symbol = file.symbols().symbol(occurrence.symbol());
		if (symbol.kind() != LuaSymbolIndex.Kind.LOCAL) return null;
		List<LuaLexer.Token> tokens = file.tokens();
		for (int i = 0; i < tokens.size(); i++) {
			if (tokens.get(i).start() != symbol.declStart()) continue;
			// local name = require ( "x" )   |   local name = require "x"
			int at = i + 1;
			if (at + 2 >= tokens.size() || !tokens.get(at).is("=") || !tokens.get(at + 1).text().equals("require")) return null;
			at += 2;
			if (tokens.get(at).is("(")) at++;
			if (at >= tokens.size() || tokens.get(at).kind() != LuaLexer.Kind.STRING) return null;
			return modulePath(tokens.get(at).text());
		}
		return null;
	}

	private static String modulePath(String literal) {
		if (literal.length() < 2) return null;
		String text = literal.charAt(0) == '[' ? literal.replaceAll("^\\[=*\\[|]=*]$", "") : literal.substring(1, literal.length() - 1);
		try { return LuaScriptNames.moduleName(text); } catch (RuntimeException invalid) { return null; }
	}

	// ---- definitions and references --------------------------------------------------------------------------------------

	/** Where the target is defined: "function name(" / "name = ..." for globals, "function M.name" / "M.name = ..." in the module. */
	List<Location> definitions(Target target) {
		List<Location> result = new ArrayList<>();
		if (target.member()) {
			File module = files.get(target.module());
			if (module != null) for (int i : memberDefinitionTokens(module, target.name())) result.add(location(module, module.tokens().get(i)));
			return result;
		}
		for (File file : files.values()) {
			for (int i = 0; i < file.tokens().size(); i++) {
				LuaLexer.Token token = file.tokens().get(i);
				if (!token.text().equals(target.name()) || token.kind() != LuaLexer.Kind.NAME || !isGlobalAt(file, token)) continue;
				boolean function = i > 0 && file.tokens().get(i - 1).text().equals("function");
				boolean assigned = i + 1 < file.tokens().size() && file.tokens().get(i + 1).is("=")
						&& (i == 0 || !file.tokens().get(i - 1).is(".") && !file.tokens().get(i - 1).is(":"));
				if (function || assigned) result.add(location(file, token));
			}
		}
		return result;
	}

	/** Every use of the target in every file, definitions included. */
	List<Location> references(Target target) {
		List<Location> result = new ArrayList<>();
		for (File file : files.values()) for (int i : referenceTokens(file, target)) result.add(location(file, file.tokens().get(i)));
		return result;
	}

	/** Renames the target in every file; the map holds the edits per file path (only files that change). */
	Map<String, List<LuaSymbolIndex.Edit>> renameEdits(Target target, String newName) {
		if (!LuaSymbolIndex.validName(newName)) return null;
		Map<String, List<LuaSymbolIndex.Edit>> edits = new LinkedHashMap<>();
		for (File file : files.values()) {
			List<LuaSymbolIndex.Edit> list = new ArrayList<>();
			for (int i : referenceTokens(file, target)) {
				LuaLexer.Token token = file.tokens().get(i);
				list.add(new LuaSymbolIndex.Edit(token.start(), token.end(), newName));
			}
			if (!list.isEmpty()) edits.put(file.path(), list);
		}
		return edits;
	}

	private List<Integer> referenceTokens(File file, Target target) {
		List<Integer> result = new ArrayList<>();
		List<LuaLexer.Token> tokens = file.tokens();
		for (int i = 0; i < tokens.size(); i++) {
			LuaLexer.Token token = tokens.get(i);
			if (token.kind() != LuaLexer.Kind.NAME || !token.text().equals(target.name())) continue;
			boolean afterDot = i > 0 && (tokens.get(i - 1).is(".") || tokens.get(i - 1).is(":"));
			if (target.member()) {
				if (!afterDot || i < 2) continue;
				LuaLexer.Token owner = tokens.get(i - 2);
				if (target.module().equals(requiredModule(file, owner))) result.add(i);
				else if (file.path().equals(target.module()) && memberDefinitionTokens(file, target.name()).contains(i)) result.add(i);
				else if (file.path().equals(target.module()) && ownerIsModuleTable(file, owner)) result.add(i);
			} else if (!afterDot && isGlobalAt(file, token)) result.add(i);
		}
		return result;
	}

	/** In the module file: the tokens naming {@code member} in "function T.member", "function T:member" or "T.member = ..." where T is the returned table. */
	private static List<Integer> memberDefinitionTokens(File module, String member) {
		List<Integer> result = new ArrayList<>();
		List<LuaLexer.Token> tokens = module.tokens();
		for (int i = 2; i < tokens.size(); i++) {
			LuaLexer.Token token = tokens.get(i);
			if (token.kind() != LuaLexer.Kind.NAME || !token.text().equals(member)) continue;
			if (!tokens.get(i - 1).is(".") && !tokens.get(i - 1).is(":")) continue;
			LuaLexer.Token owner = tokens.get(i - 2);
			if (!ownerIsModuleTable(module, owner)) continue;
			boolean function = i >= 3 && tokens.get(i - 3).text().equals("function");
			boolean assigned = i + 1 < tokens.size() && tokens.get(i + 1).is("=");
			if (function || assigned) result.add(i);
		}
		return result;
	}

	/** Whether {@code owner} is the table the module returns ("return M" at the end of the file). */
	private static boolean ownerIsModuleTable(File module, LuaLexer.Token owner) {
		List<LuaLexer.Token> tokens = module.tokens();
		for (int i = tokens.size() - 2; i >= 0; i--) {
			if (tokens.get(i).text().equals("return") && tokens.get(i + 1).kind() == LuaLexer.Kind.NAME)
				return tokens.get(i + 1).text().equals(owner.text());
		}
		return false;
	}

	private static boolean isGlobalAt(File file, LuaLexer.Token token) {
		LuaSymbolIndex.Occurrence occurrence = file.symbols().occurrenceAt(token.start());
		if (occurrence == null || occurrence.start() != token.start()) return false;
		LuaSymbolIndex.Kind kind = file.symbols().symbol(occurrence.symbol()).kind();
		return kind == LuaSymbolIndex.Kind.GLOBAL || kind == LuaSymbolIndex.Kind.FUNCTION;
	}

	private static Location location(File file, LuaLexer.Token token) {
		String text = file.source();
		int lineStart = text.lastIndexOf('\n', Math.max(0, token.start() - 1)) + 1, lineEnd = text.indexOf('\n', token.start());
		String preview = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd).strip();
		return new Location(file.path(), token.start(), token.end(), LuaSymbolIndex.lineOf(text, token.start()) + 1, preview.length() > 160 ? preview.substring(0, 160) + "…" : preview);
	}
}
