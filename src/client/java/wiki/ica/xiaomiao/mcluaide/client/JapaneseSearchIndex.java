package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Search-only kana normalization and mapped romaji. Never applies Chinese Han transliteration. */
final class JapaneseSearchIndex {
	private static final Map<String, String> KANA = kanaTable();
	private static final Map<Character, List<Reading>> READINGS = loadReadings();
	private static final Map<String, Index> CACHE = new LinkedHashMap<>(64, .75f, true);
	private static final Map<String, Query> QUERIES = new LinkedHashMap<>(64, .75f, true);
	private static int cachedCharacters;
	private static final int MAX_ENTRIES = 8192, MAX_CACHED_CHARACTERS = 1_000_000, MAX_TEXT = 65_536;
	private JapaneseSearchIndex() {}

	record Range(int start, int end) {}
	private record Reading(String text, String kana) {}
	private record Index(List<Mapped> kana, List<Mapped> roman) {}
	private record Query(String normalized, String reading, List<String> roman) {}
	private record Mapped(String text, int[] starts, int[] ends) {}

	static boolean matches(String text, String query) { return matches(text, query, true); }
	static boolean matches(String text, String query, boolean romaji) {
		if (text == null || query == null || query.isBlank()) return query == null || query.isBlank();
		Index index = index(text);
		Query parsed = query(query);
		if (parsed.normalized.isEmpty()) return false;
		for (Mapped value : index.kana) if (value.text.contains(parsed.normalized) || value.text.contains(parsed.reading)) return true;
		if (!romaji) return false;
		for (String needle : parsed.roman)
			for (Mapped value : index.roman) if (!needle.isEmpty() && value.text.contains(needle)) return true;
		return false;
	}

	static List<Range> ranges(String text, String query, boolean romaji) {
		if (text == null || query == null || query.isBlank()) return List.of();
		Index index = index(text);
		Query parsed = query(query);
		if (parsed.normalized.isEmpty()) return List.of();
		List<Range> result = new ArrayList<>();
		for (Mapped value : index.kana) { collect(value, parsed.normalized, result); collect(value, parsed.reading, result); }
		if (romaji)
			for (String needle : parsed.roman) for (Mapped value : index.roman) collect(value, needle, result);
		result.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));
		List<Range> merged = new ArrayList<>();
		for (Range range : result) {
			if (merged.isEmpty() || range.start > merged.getLast().end) merged.add(range);
			else { var previous = merged.removeLast(); merged.add(new Range(previous.start, Math.max(previous.end, range.end))); }
		}
		return List.copyOf(merged);
	}

	private static void collect(Mapped value, String needle, List<Range> result) {
		if (needle.isEmpty()) return;
		for (int at = value.text.indexOf(needle); at >= 0 && result.size() < 256; at = value.text.indexOf(needle, at + needle.length()))
			result.add(new Range(value.starts[at], value.ends[at + needle.length() - 1]));
	}

	private static synchronized Index index(String text) {
		if (text.length() > MAX_TEXT) {
			int end = MAX_TEXT;
			if (Character.isLowSurrogate(text.charAt(end))) end--;
			return build(text.substring(0, end));
		}
		Index cached = CACHE.get(text);
		if (cached != null) return cached;
		Index result = build(text);
		CACHE.put(text, result); cachedCharacters += text.length();
		while (CACHE.size() > MAX_ENTRIES || cachedCharacters > MAX_CACHED_CHARACTERS) {
			String first = CACHE.keySet().iterator().next(); CACHE.remove(first); cachedCharacters -= first.length();
		}
		return result;
	}
	static synchronized int cacheSize() { return CACHE.size(); }
	static int cacheLimit() { return MAX_ENTRIES; }
	private static synchronized Query query(String source) {
		Query existing = QUERIES.get(source);
		if (existing != null) return existing;
		Mapped normalized = normalize(source.strip());
		Query result = new Query(normalized.text, reading(normalized).text,
				latinQuery(normalized.text) ? romanQueries(normalized.text) : List.of());
		if (source.length() <= 4096) {
			QUERIES.put(source, result);
			while (QUERIES.size() > 64) QUERIES.remove(QUERIES.keySet().iterator().next());
		}
		return result;
	}

	private static Index build(String text) {
		Mapped normalized = normalize(text);
		Mapped reading = reading(normalized);
		Mapped roman = romanize(reading);
		Mapped alternative = transform(roman, false);
		return new Index(List.of(normalized, reading), List.of(roman, alternative, transform(roman, true), transform(alternative, true)));
	}

	private static Mapped normalize(String source) {
		Builder out = new Builder();
		for (int at = 0; at < source.length();) {
			int start = at; at += Character.charCount(source.codePointAt(at));
			while (at < source.length()) {
				int next = source.codePointAt(at);
				if (next != 0xFF9E && next != 0xFF9F && Character.getType(next) != Character.NON_SPACING_MARK) break;
				at += Character.charCount(next);
			}
			String cluster = Normalizer.normalize(source.substring(start, at), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
			for (int offset = 0; offset < cluster.length();) {
				int code = cluster.codePointAt(offset); offset += Character.charCount(code);
				if (code >= 0x30A1 && code <= 0x30F6) code -= 0x60;
				out.add(new String(Character.toChars(code)), start, at);
			}
		}
		return out.finish();
	}

	private static Mapped reading(Mapped source) {
		Builder out = new Builder();
		for (int at = 0; at < source.text.length();) {
			Reading found = null;
			for (Reading candidate : READINGS.getOrDefault(source.text.charAt(at), List.of()))
				if (source.text.startsWith(candidate.text, at)) { found = candidate; break; }
			int length = found == null ? Character.charCount(source.text.codePointAt(at)) : found.text.length();
			out.add(found == null ? source.text.substring(at, at + length) : found.kana, source.starts[at], source.ends[at + length - 1]);
			at += length;
		}
		return out.finish();
	}

	private static Mapped romanize(Mapped source) {
		Builder out = new Builder();
		for (int at = 0; at < source.text.length();) {
			char c = source.text.charAt(at);
			int length = Character.charCount(source.text.codePointAt(at));
			String roman = null;
			if (at + 1 < source.text.length()) roman = KANA.get(source.text.substring(at, at + 2));
			if (roman != null) length = 2;
			else if (c == 'っ') {
				String next = at + 2 < source.text.length() ? KANA.get(source.text.substring(at + 1, at + 3)) : null;
				if (next == null && at + 1 < source.text.length()) next = KANA.get(source.text.substring(at + 1, at + 2));
				roman = next != null && next.startsWith("ch") ? "t"
						: next != null && "aeioun".indexOf(next.charAt(0)) < 0 ? next.substring(0, 1) : "xtsu";
			} else if (c == 'ー') roman = out.lastVowel();
			else roman = KANA.get(String.valueOf(c));
			if (roman == null) {
				if (c < 128 && Character.isLetterOrDigit(c)) roman = String.valueOf(c);
				else if (Character.isWhitespace(c) || "・-'’_".indexOf(c) >= 0) roman = "";
				else roman = "\u0000"; // Unknown kanji/emoji separate words; never silently concatenate across them.
			}
			out.add(roman, source.starts[at], source.ends[at + length - 1]);
			at += length;
		}
		return out.finish();
	}

	private static final String[][] ALIASES = {{"kwa","kua"},{"kwi","kui"},{"kwe","kue"},{"kwo","kuo"},{"sha","sya"},{"shu","syu"},{"sho","syo"},{"cha","tya"},{"chu","tyu"},{"cho","tyo"},
			{"ja","zya"},{"ju","zyu"},{"jo","zyo"},{"shi","si"},{"chi","ti"},{"tsu","tu"},{"fu","hu"},{"ji","zi"}};
	private static Mapped transform(Mapped source, boolean shortenVowels) {
		Builder out = new Builder();
		for (int at = 0; at < source.text.length();) {
			String replacement = null; int length = 1;
			if (shortenVowels && at + 1 < source.text.length()) {
				char c = source.text.charAt(at), next = source.text.charAt(at + 1);
				if ("aeiou".indexOf(c) >= 0 && (next == c || c == 'o' && next == 'u')) { replacement = String.valueOf(c); length = 2; }
			} else if (!shortenVowels) for (String[] alias : ALIASES) {
				if (source.text.startsWith(alias[0], at)) { replacement = alias[1]; length = alias[0].length(); break; }
			}
			out.add(replacement == null ? source.text.substring(at, at + 1) : replacement, source.starts[at], source.ends[at + length - 1]);
			at += length;
		}
		return out.finish();
	}

	private static boolean latinQuery(String text) { return text.matches("[\\p{IsLatin}\\p{M}0-9\\s'’_\\-]+"); }
	private static List<String> romanQueries(String query) {
		String latin = Normalizer.normalize(query, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").replaceAll("[\\s'’_\\-]", "");
		Mapped mapped = normalize(latin);
		return List.of(latin, transform(mapped, false).text, transform(mapped, true).text, transform(transform(mapped, false), true).text);
	}

	private static Map<String, String> kanaTable() {
		Map<String, String> result = new HashMap<>();
		String[] rows = {"あいうえお:a i u e o", "かきくけこ:ka ki ku ke ko", "さしすせそ:sa shi su se so", "たちつてと:ta chi tsu te to",
				"なにぬねの:na ni nu ne no", "はひふへほ:ha hi fu he ho", "まみむめも:ma mi mu me mo", "やゆよ:ya yu yo", "らりるれろ:ra ri ru re ro",
				"わゐゑをん:wa wi we wo n", "がぎぐげご:ga gi gu ge go", "ざじずぜぞ:za ji zu ze zo", "だぢづでど:da ji zu de do", "ばびぶべぼ:ba bi bu be bo", "ぱぴぷぺぽ:pa pi pu pe po", "ぁぃぅぇぉ:a i u e o", "ゔ:vu", "ゕゖ:ka ke"};
		for (String row : rows) {
			String[] halves = row.split(":"); String[] values = halves[1].split(" ");
			for (int i = 0; i < halves[0].length(); i++) result.put(halves[0].substring(i, i + 1), values[i]);
		}
		String[] bases = {"き:k", "ぎ:g", "し:sh", "じ:j", "ち:ch", "ぢ:j", "に:n", "ひ:h", "び:b", "ぴ:p", "み:m", "り:r"};
		for (String base : bases) {
			String[] halves = base.split(":");
			for (int i = 0; i < 3; i++) result.put(halves[0] + "ゃゅょ".charAt(i), halves[1] + (halves[1].length() == 1 && !halves[1].equals("j") ? "y" : "") + "auo".charAt(i));
		}
		for (String pair : new String[]{"ふぁ:fa","ふぃ:fi","ふぇ:fe","ふぉ:fo","てぃ:ti","でぃ:di","とぅ:tu","どぅ:du","しぇ:she","じぇ:je","ちぇ:che","うぃ:wi","うぇ:we","うぉ:wo","ゔぁ:va","ゔぃ:vi","ゔぇ:ve","ゔぉ:vo","つぁ:tsa","つぃ:tsi","つぇ:tse","つぉ:tso","きぇ:kye","いぇ:ye"}) {
			String[] halves = pair.split(":"); result.put(halves[0], halves[1]);
		}
		for (String pair : new String[]{"くぁ:kwa","くぃ:kwi","くぇ:kwe","くぉ:kwo","ぐぁ:gwa","ぐぃ:gwi","ぐぇ:gwe","ぐぉ:gwo","てゅ:tyu","でゅ:dyu","ふゅ:fyu","すぃ:si","ずぃ:zi"}) {
			String[] halves = pair.split(":"); result.put(halves[0], halves[1]);
		}
		return Map.copyOf(result);
	}

	private static Map<Character, List<Reading>> loadReadings() {
		try (var input = JapaneseSearchIndex.class.getResourceAsStream("/assets/mc_lua_ide/i18n/ja_readings.json")) {
			if (input == null) throw new IllegalStateException("Missing Japanese search readings");
			Map<Character, List<Reading>> result = new HashMap<>();
			var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			json.entrySet().forEach(entry -> {
				String key = normalize(entry.getKey()).text, kana = normalize(entry.getValue().getAsString()).text;
				if (!key.isEmpty() && !kana.isBlank()) result.computeIfAbsent(key.charAt(0), ignored -> new ArrayList<>()).add(new Reading(key, kana));
			});
			result.values().forEach(list -> list.sort(Comparator.comparingInt((Reading reading) -> reading.text.length()).reversed()));
			return Map.copyOf(result);
		} catch (java.io.IOException error) { throw new IllegalStateException("Cannot load Japanese readings", error); }
	}

	private static final class Builder {
		private final StringBuilder text = new StringBuilder();
		private final List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
		void add(String value, int start, int end) {
			text.append(value); for (int i = 0; i < value.length(); i++) { starts.add(start); ends.add(end); }
		}
		String lastVowel() {
			for (int i = text.length() - 1; i >= 0; i--) { char c = text.charAt(i); if (c == '\u0000') break; if ("aeiou".indexOf(c) >= 0) return String.valueOf(c); }
			return "\u0000";
		}
		Mapped finish() { return new Mapped(text.toString(), starts.stream().mapToInt(Integer::intValue).toArray(), ends.stream().mapToInt(Integer::intValue).toArray()); }
	}
}
