package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Matching brackets for the code editor. Brackets inside strings and comments never count (the lexer separates them). */
final class BracketMatcher {
	/** Offsets of an opening and its closing bracket character. */
	record Pair(int open, int close) { }

	private BracketMatcher() { }

	private static boolean opener(String text) { return text.equals("(") || text.equals("[") || text.equals("{"); }
	private static boolean closer(String text) { return text.equals(")") || text.equals("]") || text.equals("}"); }
	private static boolean pairs(String open, String close) {
		return open.equals("(") && close.equals(")") || open.equals("[") && close.equals("]") || open.equals("{") && close.equals("}");
	}

	/** All matched pairs, outermost first by open offset. Unbalanced brackets are simply absent. */
	static List<Pair> pairs(List<LuaLexer.Token> tokens) {
		List<Pair> result = new ArrayList<>();
		Deque<LuaLexer.Token> stack = new ArrayDeque<>();
		for (LuaLexer.Token token : tokens) {
			if (token.kind() != LuaLexer.Kind.OP) continue;
			if (opener(token.text())) stack.push(token);
			else if (closer(token.text())) {
				if (!stack.isEmpty() && pairs(stack.peek().text(), token.text())) result.add(new Pair(stack.pop().start(), token.start()));
			}
		}
		result.sort((a, b) -> Integer.compare(a.open, b.open));
		return result;
	}

	/**
	 * The pair next to the caret: a bracket directly before it wins (as in most editors), otherwise one directly after it.
	 * Null when the caret is not touching a matched bracket.
	 */
	static Pair at(List<Pair> pairs, int caret) {
		for (int offset : new int[]{caret - 1, caret}) {
			for (Pair pair : pairs) if (pair.open == offset || pair.close == offset) return pair;
		}
		return null;
	}

	/** Offsets of brackets that have no partner, for an "unbalanced" hint. */
	static List<Integer> unmatched(List<LuaLexer.Token> tokens) {
		List<Integer> result = new ArrayList<>();
		Deque<LuaLexer.Token> stack = new ArrayDeque<>();
		for (LuaLexer.Token token : tokens) {
			if (token.kind() != LuaLexer.Kind.OP) continue;
			if (opener(token.text())) stack.push(token);
			else if (closer(token.text())) {
				if (!stack.isEmpty() && pairs(stack.peek().text(), token.text())) stack.pop();
				else result.add(token.start());
			}
		}
		for (LuaLexer.Token left : stack) result.add(left.start());
		result.sort(Integer::compare);
		return result;
	}

	/** The closing character to insert for a typed opening character or quote, or 0 when nothing should be inserted. */
	static char autoClose(char typed) {
		return switch (typed) { case '(' -> ')'; case '[' -> ']'; case '{' -> '}'; case '"' -> '"'; case '\'' -> '\''; default -> 0; };
	}

	/**
	 * Whether typing {@code typed} at {@code caret} should add its partner. Quotes only pair when the previous character is not
	 * part of a word (so "don't" is not mangled) and the next character does not already continue a word.
	 */
	static boolean shouldAutoClose(String source, int caret, char typed) {
		char partner = autoClose(typed);
		if (partner == 0) return false;
		char previous = caret > 0 ? source.charAt(caret - 1) : ' ', next = caret < source.length() ? source.charAt(caret) : ' ';
		if (typed == '"' || typed == '\'') {
			if (LuaLexer.isNamePart(previous) || LuaLexer.isNamePart(next) || previous == typed || previous == '\\') return false;
			return true;
		}
		return !LuaLexer.isNamePart(next) || next == ' ';
	}

	/** True when typing the closing character should just step over the identical one already after the caret. */
	static boolean stepsOver(String source, int caret, char typed) {
		return (closer(String.valueOf(typed)) || typed == '"' || typed == '\'') && caret < source.length() && source.charAt(caret) == typed;
	}

	/** True when Backspace between an empty pair such as "()" should delete both characters. */
	static boolean deletesPair(String source, int caret) {
		if (caret <= 0 || caret >= source.length()) return false;
		char open = source.charAt(caret - 1), close = source.charAt(caret);
		return autoClose(open) == close;
	}
}
