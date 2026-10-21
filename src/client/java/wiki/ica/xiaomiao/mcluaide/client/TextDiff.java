package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;

/** Line-based difference between two texts, for the compare view. */
final class TextDiff {
	enum Kind { EQUAL, INSERT, DELETE }
	/** {@code oldLine}/{@code newLine} are zero-based, or -1 when the line does not exist on that side. */
	record Line(Kind kind, int oldLine, int newLine, String text) { }

	private static final long MAX_CELLS = 25_000_000L;

	private TextDiff() { }

	static List<String> lines(String text) {
		List<String> result = new ArrayList<>(List.of(text.split("\n", -1)));
		if (result.size() > 1 && result.getLast().isEmpty()) result.removeLast(); // a trailing newline is not an extra line
		if (text.isEmpty()) result.clear();
		return result;
	}

	static List<Line> diff(String oldText, String newText) {
		List<String> a = lines(oldText), b = lines(newText);
		List<Line> result = new ArrayList<>();
		int prefix = 0;
		while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
		int suffix = 0;
		while (suffix < a.size() - prefix && suffix < b.size() - prefix && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
		for (int i = 0; i < prefix; i++) result.add(new Line(Kind.EQUAL, i, i, a.get(i)));
		int aEnd = a.size() - suffix, bEnd = b.size() - suffix;
		int n = aEnd - prefix, m = bEnd - prefix;
		if ((long) n * m > MAX_CELLS) {
			// Too large for an exact answer: show the middle as one replaced block rather than freezing the game.
			for (int i = prefix; i < aEnd; i++) result.add(new Line(Kind.DELETE, i, -1, a.get(i)));
			for (int j = prefix; j < bEnd; j++) result.add(new Line(Kind.INSERT, -1, j, b.get(j)));
		} else {
			int[][] lcs = new int[n + 1][m + 1];
			for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
				lcs[i][j] = a.get(prefix + i).equals(b.get(prefix + j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			int i = 0, j = 0;
			while (i < n && j < m) {
				if (a.get(prefix + i).equals(b.get(prefix + j))) { result.add(new Line(Kind.EQUAL, prefix + i, prefix + j, a.get(prefix + i))); i++; j++; }
				else if (lcs[i + 1][j] >= lcs[i][j + 1]) { result.add(new Line(Kind.DELETE, prefix + i, -1, a.get(prefix + i))); i++; }
				else { result.add(new Line(Kind.INSERT, -1, prefix + j, b.get(prefix + j))); j++; }
			}
			while (i < n) { result.add(new Line(Kind.DELETE, prefix + i, -1, a.get(prefix + i))); i++; }
			while (j < m) { result.add(new Line(Kind.INSERT, -1, prefix + j, b.get(prefix + j))); j++; }
		}
		for (int k = 0; k < suffix; k++) result.add(new Line(Kind.EQUAL, aEnd + k, bEnd + k, a.get(aEnd + k)));
		return result;
	}

	/** One row of a side-by-side view: the old line on the left, the new one on the right; either side may be absent. */
	record Pair(Line left, Line right) {
		boolean changed() { return left == null || right == null || left.kind() != Kind.EQUAL; }
	}

	/** Lines the two sides of a diff up: a run of deletions and the insertions that follow it share rows. */
	static List<Pair> sideBySide(List<Line> diff) {
		List<Pair> rows = new ArrayList<>();
		int i = 0;
		while (i < diff.size()) {
			Line line = diff.get(i);
			if (line.kind() == Kind.EQUAL) { rows.add(new Pair(line, line)); i++; continue; }
			List<Line> removed = new ArrayList<>(), added = new ArrayList<>();
			while (i < diff.size() && diff.get(i).kind() == Kind.DELETE) removed.add(diff.get(i++));
			while (i < diff.size() && diff.get(i).kind() == Kind.INSERT) added.add(diff.get(i++));
			for (int k = 0; k < Math.max(removed.size(), added.size()); k++)
				rows.add(new Pair(k < removed.size() ? removed.get(k) : null, k < added.size() ? added.get(k) : null));
		}
		return rows;
	}

	/** Indices (into the diff) where a run of changed lines starts: the targets of "next / previous change". */
	static List<Integer> changeStarts(List<Line> diff) {
		List<Integer> starts = new ArrayList<>();
		boolean inChange = false;
		for (int i = 0; i < diff.size(); i++) {
			boolean changed = diff.get(i).kind() != Kind.EQUAL;
			if (changed && !inChange) starts.add(i);
			inChange = changed;
		}
		return starts;
	}

	static boolean identical(List<Line> diff) { return diff.stream().allMatch(line -> line.kind() == Kind.EQUAL); }
}
