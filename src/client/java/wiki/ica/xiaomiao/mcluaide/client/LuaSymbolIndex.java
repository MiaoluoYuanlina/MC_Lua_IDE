package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scope-aware symbol table for one Lua source text: where each local, parameter, loop variable and function is declared, every
 * place it is used, and an outline of the functions. It powers go to definition, find references, rename, the outline view and
 * the "same word" highlight.
 * <p>
 * It works on the forgiving {@link LuaLexer} token stream with a statement-boundary heuristic instead of a full parser, so it keeps
 * giving useful answers while the code is half typed (a full parser rejects the whole file on the first syntax error).
 */
final class LuaSymbolIndex {
	enum Kind { LOCAL, PARAMETER, LOCAL_FUNCTION, FUNCTION, GLOBAL, LOOP_VARIABLE }

	record Symbol(int id, String name, Kind kind, int declStart, int declEnd) { }
	record Occurrence(int start, int end, int symbol, boolean declaration) { }
	/** {@code depth} counts the enclosing functions, so the outline can be shown as a tree. */
	record OutlineEntry(String name, Kind kind, int offset, int depth) { }
	record Edit(int start, int end, String text) { }

	private final List<Symbol> symbols = new ArrayList<>();
	private final List<Occurrence> occurrences = new ArrayList<>();
	private final List<OutlineEntry> outline = new ArrayList<>();
	private final Map<String, Integer> globals = new HashMap<>();

	// ---- construction ---------------------------------------------------------------------------------------------------

	private record Pending(int depth, List<LuaLexer.Token> names, boolean function) { }
	private static final Set<String> STATEMENT_KEYWORDS = Set.of("local", "if", "for", "while", "repeat", "function", "return",
			"end", "else", "elseif", "until", "do", "break", "goto");

	private final List<Map<String, Integer>> scopes = new ArrayList<>();
	private final List<Boolean> functionScope = new ArrayList<>();
	private final List<Pending> pending = new ArrayList<>();
	private List<LuaLexer.Token> pendingFor;
	private boolean untilPending;

	private LuaSymbolIndex() { }

	static LuaSymbolIndex build(String source) {
		LuaSymbolIndex index = new LuaSymbolIndex();
		List<LuaLexer.Token> tokens = new ArrayList<>();
		for (LuaLexer.Token token : LuaLexer.tokenize(source)) if (token.kind() != LuaLexer.Kind.COMMENT) tokens.add(token);
		index.scan(tokens);
		return index;
	}

	private int declare(LuaLexer.Token name, Kind kind) {
		int id = symbols.size();
		symbols.add(new Symbol(id, name.text(), kind, name.start(), name.end()));
		scopes.getLast().put(name.text(), id);
		occurrences.add(new Occurrence(name.start(), name.end(), id, true));
		return id;
	}

	private int resolve(String name) {
		for (int i = scopes.size() - 1; i >= 0; i--) {
			Integer found = scopes.get(i).get(name);
			if (found != null) return found;
		}
		return -1;
	}

	private void use(LuaLexer.Token name) {
		int id = resolve(name.text());
		if (id >= 0) { occurrences.add(new Occurrence(name.start(), name.end(), id, false)); return; }
		Integer global = globals.get(name.text());
		if (global == null) {
			global = symbols.size();
			symbols.add(new Symbol(global, name.text(), Kind.GLOBAL, name.start(), name.end()));
			globals.put(name.text(), global);
			occurrences.add(new Occurrence(name.start(), name.end(), global, true));
		} else occurrences.add(new Occurrence(name.start(), name.end(), global, false));
	}

	private void push(boolean function) { scopes.add(new HashMap<>()); functionScope.add(function); }
	private void pop() { if (scopes.size() > 1) { scopes.removeLast(); functionScope.removeLast(); } }
	private int functionDepth() { int depth = 0; for (boolean f : functionScope) if (f) depth++; return depth; }

	private static boolean endsOperand(LuaLexer.Token token) {
		return switch (token.kind()) {
			case NAME, NUMBER, STRING -> true;
			case KEYWORD -> token.is("true") || token.is("false") || token.is("nil") || token.is("end");
			case OP -> token.is(")") || token.is("]") || token.is("}") || token.is("...");
			default -> false;
		};
	}

	private static boolean startsStatement(LuaLexer.Token token) {
		return token.kind() == LuaLexer.Kind.NAME || token.kind() == LuaLexer.Kind.KEYWORD && STATEMENT_KEYWORDS.contains(token.text())
				|| token.kind() == LuaLexer.Kind.OP && (token.is("::") || token.is(";"));
	}

	private void flush(boolean everything) {
		while (!pending.isEmpty() && (everything || pending.getLast().depth() == scopes.size())) {
			Pending group = pending.removeLast();
			// Declare into the scope the statement belongs to, even when a nested scope is still open (unterminated code).
			Map<String, Integer> target = scopes.get(Math.min(group.depth(), scopes.size()) - 1);
			for (LuaLexer.Token name : group.names()) {
				int id = symbols.size();
				symbols.add(new Symbol(id, name.text(), group.function() ? Kind.LOCAL_FUNCTION : Kind.LOCAL, name.start(), name.end()));
				target.put(name.text(), id);
				occurrences.add(new Occurrence(name.start(), name.end(), id, true));
				if (group.function()) outline.add(new OutlineEntry(name.text(), Kind.LOCAL_FUNCTION, name.start(), functionDepthAt(group.depth())));
			}
		}
	}
	private int functionDepthAt(int scopeCount) { int depth = 0; for (int i = 0; i < Math.min(scopeCount, functionScope.size()); i++) if (functionScope.get(i)) depth++; return depth; }

	private void scan(List<LuaLexer.Token> tokens) {
		push(false);
		List<Character> brackets = new ArrayList<>();
		LuaLexer.Token previous = null;
		for (int i = 0; i < tokens.size(); i++) {
			LuaLexer.Token token = tokens.get(i);
			LuaLexer.Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
			if (previous != null && endsOperand(previous) && startsStatement(token)) {
				flush(false);
				if (untilPending) { untilPending = false; pop(); }
			}
			if (token.kind() == LuaLexer.Kind.KEYWORD) {
				switch (token.text()) {
					case "local" -> {
						if (next != null && next.isKeyword("function") && i + 2 < tokens.size() && tokens.get(i + 2).kind() == LuaLexer.Kind.NAME) {
							LuaLexer.Token name = tokens.get(i + 2);
							declare(name, Kind.LOCAL_FUNCTION);
							outline.add(new OutlineEntry(name.text(), Kind.LOCAL_FUNCTION, name.start(), functionDepth()));
							i = readParameters(tokens, i + 3, false);
							previous = tokens.get(Math.min(i, tokens.size() - 1)); continue;
						}
						List<LuaLexer.Token> names = new ArrayList<>();
						int cursor = i + 1;
						while (cursor < tokens.size() && tokens.get(cursor).kind() == LuaLexer.Kind.NAME) {
							names.add(tokens.get(cursor++));
							if (cursor < tokens.size() && tokens.get(cursor).is(",")) cursor++; else break;
						}
						boolean function = cursor + 1 < tokens.size() && tokens.get(cursor).is("=") && tokens.get(cursor + 1).isKeyword("function") && names.size() == 1;
						pending.add(new Pending(scopes.size(), names, function));
						// The statement ends after its names (and any value): the last name counts as an operand so that the
						// next statement start is recognised as a boundary even when there is no "= value" part.
						i = cursor - 1; previous = names.isEmpty() ? token : names.getLast(); continue;
					}
					case "function" -> {
						i = functionStatement(tokens, i);
						previous = tokens.get(Math.min(i, tokens.size() - 1)); continue;
					}
					case "for" -> {
						List<LuaLexer.Token> names = new ArrayList<>();
						int cursor = i + 1;
						while (cursor < tokens.size() && tokens.get(cursor).kind() == LuaLexer.Kind.NAME) {
							names.add(tokens.get(cursor++));
							if (cursor < tokens.size() && tokens.get(cursor).is(",")) cursor++; else break;
						}
						pendingFor = names; i = cursor - 1; previous = token; continue;
					}
					case "do" -> {
						push(false);
						if (pendingFor != null) { for (LuaLexer.Token name : pendingFor) declare(name, Kind.LOOP_VARIABLE); pendingFor = null; }
					}
					case "then", "repeat" -> push(false);
					case "elseif" -> pop();
					case "else" -> { pop(); push(false); }
					case "end" -> pop();
					case "until" -> untilPending = true;
					case "goto" -> { if (next != null && next.kind() == LuaLexer.Kind.NAME) { i++; previous = next; continue; } }
					default -> { }
				}
			} else if (token.kind() == LuaLexer.Kind.NAME) {
				boolean field = previous != null && previous.kind() == LuaLexer.Kind.OP && (previous.is(".") || previous.is(":"));
				boolean label = previous != null && previous.is("::");
				boolean tableKey = !brackets.isEmpty() && brackets.getLast() == '{' && previous != null
						&& (previous.is("{") || previous.is(",") || previous.is(";")) && next != null && next.is("=");
				if (!field && !label && !tableKey) use(token);
			} else if (token.kind() == LuaLexer.Kind.OP) {
				switch (token.text()) {
					case "(", "[", "{" -> brackets.add(token.text().charAt(0));
					case ")", "]", "}" -> { if (!brackets.isEmpty()) brackets.removeLast(); }
					default -> { }
				}
			}
			previous = token;
		}
		flush(true);
	}

	/** Reads "(a, b, ...)" starting at {@code from}, opens the function scope and declares the parameters; returns the index of ")". */
	private int readParameters(List<LuaLexer.Token> tokens, int from, boolean method) {
		push(true);
		int cursor = from;
		if (cursor >= tokens.size() || !tokens.get(cursor).is("(")) return cursor - 1;
		cursor++;
		while (cursor < tokens.size() && !tokens.get(cursor).is(")")) {
			if (tokens.get(cursor).kind() == LuaLexer.Kind.NAME) declare(tokens.get(cursor), Kind.PARAMETER);
			cursor++;
		}
		return cursor;
	}

	/** "function name(...)", "function a.b:c(...)" and anonymous "function(...)" in expressions. */
	private int functionStatement(List<LuaLexer.Token> tokens, int at) {
		int cursor = at + 1;
		StringBuilder fullName = new StringBuilder();
		LuaLexer.Token first = null;
		boolean method = false;
		if (cursor < tokens.size() && tokens.get(cursor).kind() == LuaLexer.Kind.NAME) {
			first = tokens.get(cursor);
			fullName.append(first.text()); cursor++;
			while (cursor + 1 < tokens.size() && (tokens.get(cursor).is(".") || tokens.get(cursor).is(":"))
					&& tokens.get(cursor + 1).kind() == LuaLexer.Kind.NAME) {
				method |= tokens.get(cursor).is(":");
				fullName.append(tokens.get(cursor).text()).append(tokens.get(cursor + 1).text());
				cursor += 2;
			}
		}
		boolean plain = first != null && fullName.length() == first.text().length();
		if (first != null) {
			int local = resolve(first.text());
			if (local >= 0) occurrences.add(new Occurrence(first.start(), first.end(), local, false));
			else {
				Integer global = globals.get(first.text());
				if (global == null) {
					global = symbols.size();
					symbols.add(new Symbol(global, first.text(), plain ? Kind.FUNCTION : Kind.GLOBAL, first.start(), first.end()));
					globals.put(first.text(), global);
					occurrences.add(new Occurrence(first.start(), first.end(), global, true));
				} else occurrences.add(new Occurrence(first.start(), first.end(), global, false));
			}
			outline.add(new OutlineEntry(fullName.toString(), Kind.FUNCTION, first.start(), functionDepth()));
		}
		int end = readParameters(tokens, cursor, method);
		return end;
	}

	// ---- queries --------------------------------------------------------------------------------------------------------

	List<Symbol> symbols() { return symbols; }
	List<Occurrence> occurrences() { return occurrences; }
	List<OutlineEntry> outline() { return outline; }
	Symbol symbol(int id) { return symbols.get(id); }

	/** The identifier occurrence the caret is on or directly after, or null. */
	Occurrence occurrenceAt(int offset) {
		Occurrence best = null;
		for (Occurrence occurrence : occurrences) {
			if (offset >= occurrence.start() && offset <= occurrence.end()) {
				// Prefer the one the caret is inside over one it merely touches the end of.
				if (best == null || offset < occurrence.end()) best = occurrence;
			}
		}
		return best;
	}

	/** Where the symbol under the offset is declared, or null when the offset is not on a known identifier. */
	Symbol definitionAt(int offset) {
		Occurrence occurrence = occurrenceAt(offset);
		return occurrence == null ? null : symbols.get(occurrence.symbol());
	}

	List<Occurrence> referencesOf(int symbolId) {
		List<Occurrence> result = new ArrayList<>();
		for (Occurrence occurrence : occurrences) if (occurrence.symbol() == symbolId) result.add(occurrence);
		return result;
	}

	static boolean validName(String name) {
		if (name == null || name.isEmpty() || LuaLexer.KEYWORDS.contains(name) || !LuaLexer.isNameStart(name.charAt(0))) return false;
		for (int i = 1; i < name.length(); i++) if (!LuaLexer.isNamePart(name.charAt(i))) return false;
		return true;
	}

	/**
	 * The edits that rename the symbol under {@code offset} everywhere in this file, or null when there is nothing to rename or the
	 * new name is not a valid Lua identifier. A global is renamed only inside this file: other scripts using it are not touched.
	 */
	List<Edit> renameEdits(int offset, String newName) {
		if (!validName(newName)) return null;
		Occurrence at = occurrenceAt(offset);
		if (at == null) return null;
		List<Edit> edits = new ArrayList<>();
		for (Occurrence occurrence : referencesOf(at.symbol())) edits.add(new Edit(occurrence.start(), occurrence.end(), newName));
		return edits;
	}

	/** Applies non-overlapping edits to a text (any order). */
	static String apply(String source, List<Edit> edits) {
		List<Edit> sorted = new ArrayList<>(edits);
		sorted.sort((a, b) -> Integer.compare(b.start(), a.start()));
		StringBuilder out = new StringBuilder(source);
		for (Edit edit : sorted) out.replace(edit.start(), edit.end(), edit.text());
		return out.toString();
	}

	/** Zero-based line of an offset, for turning symbols into "line N" labels. */
	static int lineOf(String source, int offset) {
		int line = 0;
		for (int i = 0; i < Math.min(offset, source.length()); i++) if (source.charAt(i) == '\n') line++;
		return line;
	}
}
