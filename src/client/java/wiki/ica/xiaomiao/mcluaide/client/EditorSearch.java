package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Find and replace over plain text: literal or regular expression, case and whole-word options, safe against runaway patterns. */
final class EditorSearch {
	record Options(boolean caseSensitive, boolean wholeWord, boolean regex) {
		static final Options DEFAULT = new Options(false, false, false);
	}
	record Match(int start, int end) { int length() { return end - start; } }
	record Replaced(String text, int count) { }

	/** An unusable query: an invalid regular expression or one that took too long to evaluate. */
	static final class QueryException extends Exception {
		QueryException(String message) { super(message); }
	}

	static final int MAX_MATCHES = 20_000;
	private static final long TIME_LIMIT_NANOS = 400_000_000L;
	private static final String WORD = "[\\p{L}\\p{N}_]";

	private EditorSearch() { }

	static Pattern compile(String query, Options options) throws QueryException {
		if (query == null || query.isEmpty()) throw new QueryException("");
		String body = options.regex() ? query : Pattern.quote(query);
		if (options.wholeWord()) body = "(?<!" + WORD + ")(?:" + body + ")(?!" + WORD + ")";
		int flags = Pattern.MULTILINE | (options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		try { return Pattern.compile(body, flags); }
		catch (PatternSyntaxException error) { throw new QueryException(error.getDescription()); }
	}

	/** Fails with a QueryException once a single operation exceeds the time limit, instead of freezing the game thread. */
	private static final class Deadline implements CharSequence {
		private final CharSequence text;
		private final long deadline = System.nanoTime() + TIME_LIMIT_NANOS;
		private int counter;
		Deadline(CharSequence text) { this.text = text; }
		@Override public int length() { return text.length(); }
		@Override public char charAt(int index) {
			if ((++counter & 0x3FFF) == 0 && System.nanoTime() > deadline) throw new IllegalStateException("search timed out");
			return text.charAt(index);
		}
		@Override public CharSequence subSequence(int start, int end) { return new Deadline(text.subSequence(start, end)); }
		@Override public String toString() { return text.toString(); }
	}

	static List<Match> findAll(String text, String query, Options options) throws QueryException {
		Pattern pattern = compile(query, options);
		List<Match> result = new ArrayList<>();
		try {
			Matcher matcher = pattern.matcher(new Deadline(text));
			while (matcher.find() && result.size() < MAX_MATCHES) {
				if (matcher.end() == matcher.start()) continue; // an empty match is never useful in an editor
				result.add(new Match(matcher.start(), matcher.end()));
			}
		} catch (IllegalStateException timeout) { throw new QueryException("表达式过于复杂，已中止 / expression too complex"); }
		return result;
	}

	/** The next (or previous) match relative to {@code from}, wrapping around the document; null when nothing matches. */
	static Match find(String text, String query, Options options, int from, boolean forward) throws QueryException {
		List<Match> all = findAll(text, query, options);
		if (all.isEmpty()) return null;
		if (forward) {
			for (Match match : all) if (match.start() >= from) return match;
			return all.getFirst();
		}
		for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).end() <= from) return all.get(i);
		return all.getLast();
	}

	/** The text a single match is replaced with, expanding $1 / ${name} / \\ in regular-expression mode. */
	static String replacementFor(String text, Match match, String query, Options options, String replacement) throws QueryException {
		return options.regex() ? expand(compile(query, options), text, match, replacement) : replacement;
	}
	static Replaced replaceAll(String text, String query, Options options, String replacement) throws QueryException {
		Pattern pattern = compile(query, options);
		List<Match> matches = findAll(text, query, options);
		if (matches.isEmpty()) return new Replaced(text, 0);
		StringBuilder out = new StringBuilder(text.length());
		int cursor = 0;
		for (Match match : matches) {
			out.append(text, cursor, match.start());
			out.append(options.regex() ? expand(pattern, text, match, replacement) : replacement);
			cursor = match.end();
		}
		out.append(text, cursor, text.length());
		return new Replaced(out.toString(), matches.size());
	}

	private static String expand(Pattern pattern, String text, Match match, String replacement) throws QueryException {
		Matcher matcher = pattern.matcher(text);
		matcher.region(match.start(), match.end());
		matcher.useTransparentBounds(true); matcher.useAnchoringBounds(false);
		try {
			if (!matcher.find() || matcher.start() != match.start()) return replacement;
			StringBuilder out = new StringBuilder();
			matcher.appendReplacement(out, replacement);
			return out.substring(match.start()); // appendReplacement also copied the text before the match
		} catch (IllegalArgumentException | IndexOutOfBoundsException error) { throw new QueryException("替换表达式无效 / invalid replacement"); }
	}
}
