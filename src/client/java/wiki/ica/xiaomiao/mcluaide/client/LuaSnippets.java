package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab-triggered code templates. Typing a trigger word (such as {@code for}) and pressing Tab inserts the template and selects its
 * first placeholder; Tab then moves through the others and finally to {@code $0}. Syntax: {@code ${1:default}}, {@code $1}, {@code $0}.
 */
final class LuaSnippets {
	record Snippet(String trigger, String description, String body) { }
	/** A placeholder position inside the expanded text (offsets are relative to the start of the inserted text). */
	record Stop(int index, int start, int end) { }
	record Expansion(String text, List<Stop> stops) { }

	private static final String INDENT = "    ";
	private static final Map<String, Snippet> BUILT_IN = new LinkedHashMap<>();
	private static void add(String trigger, String description, String body) { BUILT_IN.put(trigger, new Snippet(trigger, description, body)); }
	static {
		add("fn", "function", "function ${1:name}(${2:args})\n\t$0\nend");
		add("lf", "local function", "local function ${1:name}(${2:args})\n\t$0\nend");
		add("anon", "anonymous function", "function(${1:args})\n\t$0\nend");
		add("for", "numeric for", "for ${1:i} = ${2:1}, ${3:10} do\n\t$0\nend");
		add("fori", "for … in ipairs", "for ${1:i}, ${2:value} in ipairs(${3:list}) do\n\t$0\nend");
		add("forp", "for … in pairs", "for ${1:key}, ${2:value} in pairs(${3:table}) do\n\t$0\nend");
		add("if", "if … then", "if ${1:condition} then\n\t$0\nend");
		add("ife", "if … else", "if ${1:condition} then\n\t${2:-- yes}\nelse\n\t$0\nend");
		add("eif", "elseif", "elseif ${1:condition} then\n\t$0");
		add("while", "while loop", "while ${1:condition} do\n\t$0\nend");
		add("rep", "repeat … until", "repeat\n\t$0\nuntil ${1:condition}");
		add("req", "require", "local ${1:module} = require(\"${2:module}\")$0");
		add("pcall", "protected call", "local ${1:ok}, ${2:err} = pcall(function()\n\t$0\nend)");
		add("tbl", "table", "local ${1:name} = {\n\t$0\n}");
		add("print", "print", "print(${1:value})$0");
	}

	private LuaSnippets() { }

	static List<Snippet> all() { return new ArrayList<>(BUILT_IN.values()); }
	static Snippet find(String trigger) { return BUILT_IN.get(trigger); }

	/** The identifier immediately before {@code caret} (the possible trigger), or "". */
	static String wordBefore(String source, int caret) {
		int start = caret;
		while (start > 0 && LuaLexer.isNamePart(source.charAt(start - 1))) start--;
		return source.substring(start, Math.min(caret, source.length()));
	}

	/** Expands a template for insertion at a line that already has {@code indent} of leading whitespace. */
	static Expansion expand(Snippet snippet, String indent) {
		StringBuilder out = new StringBuilder();
		List<Stop> stops = new ArrayList<>();
		String body = snippet.body();
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '\n') { out.append('\n').append(indent); continue; }
			if (c == '\t') { out.append(INDENT); continue; }
			if (c == '$' && i + 1 < body.length()) {
				if (body.charAt(i + 1) == '{') {
					int close = body.indexOf('}', i);
					int colon = body.indexOf(':', i);
					if (close > 0) {
						String number = body.substring(i + 2, colon > 0 && colon < close ? colon : close);
						String text = colon > 0 && colon < close ? body.substring(colon + 1, close) : "";
						int start = out.length();
						out.append(text);
						stops.add(new Stop(Integer.parseInt(number), start, out.length()));
						i = close; continue;
					}
				} else if (Character.isDigit(body.charAt(i + 1))) {
					stops.add(new Stop(body.charAt(i + 1) - '0', out.length(), out.length()));
					i++; continue;
				}
			}
			out.append(c);
		}
		if (stops.stream().noneMatch(stop -> stop.index() == 0)) stops.add(new Stop(0, out.length(), out.length()));
		// Visit order: 1, 2, 3 … and $0 last.
		stops.sort((a, b) -> Integer.compare(a.index() == 0 ? Integer.MAX_VALUE : a.index(), b.index() == 0 ? Integer.MAX_VALUE : b.index()));
		return new Expansion(out.toString(), stops);
	}

	/** Leading whitespace of the line containing {@code offset}. */
	static String indentAt(String source, int offset) {
		int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
		int end = lineStart;
		while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) end++;
		return source.substring(lineStart, end);
	}
}
