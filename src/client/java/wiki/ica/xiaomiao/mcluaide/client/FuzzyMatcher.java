package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Fuzzy "subsequence" matching and ranking for quick open, symbol search and the command palette. */
final class FuzzyMatcher {
	private FuzzyMatcher() { }

	/**
	 * How well {@code query} matches {@code text}: -1 when its characters do not all appear in order, otherwise a score where higher is
	 * better. Consecutive characters, matches at the start of a word or path segment, and shorter texts rank higher.
	 */
	static int score(String query, String text) {
		if (query.isEmpty()) return 0;
		String q = query.toLowerCase(Locale.ROOT), t = text.toLowerCase(Locale.ROOT);
		int score = 0, position = 0, previous = -2;
		for (int i = 0; i < q.length(); i++) {
			char c = q.charAt(i);
			if (Character.isWhitespace(c)) continue;
			int found = t.indexOf(c, position);
			if (found < 0) return -1;
			score += 10;
			if (found == previous + 1) score += 18;                       // consecutive run
			if (found == 0 || !Character.isLetterOrDigit(text.charAt(found - 1))) score += 14; // word / path segment start
			else if (Character.isUpperCase(text.charAt(found)) && Character.isLowerCase(text.charAt(found - 1))) score += 10; // camelCase hump
			score -= Math.min(6, found - position);                       // gaps cost a little
			previous = found; position = found + 1;
		}
		if (t.startsWith(q)) score += 40;
		else if (t.contains(q)) score += 20;
		return score - Math.min(30, text.length() / 4);
	}

	/** The items that match, best first; with an empty query everything is kept in its original order. */
	static <T> List<T> rank(String query, List<T> items, Function<T, String> text, int limit) {
		if (query.isBlank()) return items.size() <= limit ? items : new ArrayList<>(items.subList(0, limit));
		record Scored<T>(T item, int score) { }
		List<Scored<T>> scored = new ArrayList<>();
		for (T item : items) {
			int value = score(query, text.apply(item));
			if (value >= 0) scored.add(new Scored<>(item, value));
		}
		scored.sort(Comparator.comparingInt((Scored<T> s) -> s.score()).reversed());
		List<T> result = new ArrayList<>();
		for (Scored<T> entry : scored) { result.add(entry.item()); if (result.size() >= limit) break; }
		return result;
	}
}
