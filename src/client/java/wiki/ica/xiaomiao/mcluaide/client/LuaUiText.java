package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Client-only IDE localization. Never translate script contents, identifiers or saved configuration keys. */
final class LuaUiText {
	private static final Map<String, String> ENGLISH = load("en_us");
	private static final Map<String, String> JAPANESE = load("ja_jp");
	private static String testLanguage;

	private LuaUiText() {}

	static String language() {
		if (testLanguage != null) return testLanguage;
		Minecraft client = Minecraft.getInstance();
		// Headless editor regressions historically use Chinese fixtures.
		return client == null || client.options == null ? "zh_cn" : client.options.languageCode;
	}

	static boolean chinese() { return chinese(language()); }
	/** Languages typed through an input method; their Ctrl+Space / Shift+Space / Ctrl+Shift belong to the IME, not to editor shortcuts. */
	static boolean imeLanguage() {
		String normalized = language() == null ? "" : language().toLowerCase(Locale.ROOT).replace('-', '_');
		return chinese() || japanese() || normalized.equals("ko") || normalized.startsWith("ko_");
	}
	static boolean japanese() { return japanese(language()); }
	static boolean japanese(String language) {
		if (language == null) return false;
		String normalized = language.toLowerCase(Locale.ROOT).replace('-', '_');
		return normalized.equals("ja") || normalized.startsWith("ja_");
	}
	static boolean chinese(String language) {
		if (language == null) return false;
		String normalized = language.toLowerCase(Locale.ROOT).replace('-', '_');
		return normalized.equals("zh") || normalized.startsWith("zh_")
				|| normalized.equals("lzh") || normalized.startsWith("lzh_")
				|| normalized.equals("yue") || normalized.startsWith("yue_");
	}

	private static final Map<String, String> SEGMENT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<Character, java.util.List<String>> SEGMENT_KEYS = segmentKeys();

	private static Map<Character, java.util.List<String>> segmentKeys() {
		Map<Character, java.util.List<String>> result = new java.util.HashMap<>();
		for (String key : ENGLISH.keySet()) {
			if (key.length() >= 2 && key.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF)) result.computeIfAbsent(key.charAt(0), c -> new java.util.ArrayList<>()).add(key);
		}
		result.values().forEach(list -> list.sort((a, b) -> b.length() - a.length()));
		return result;
	}

	/** Runtime text built from fragments cannot match a whole-string entry, so known fragments are translated one by one. */
	private static String bySegments(String chinese) {
		if (chinese.codePoints().noneMatch(c -> c >= 0x4E00 && c <= 0x9FFF) || chinese.matches("(?s).*['\"={}].*")) return chinese; // code and user text stay as written
		if (SEGMENT_CACHE.size() > 4096) SEGMENT_CACHE.clear();
		boolean ja = japanese();
		return SEGMENT_CACHE.computeIfAbsent((ja ? "j" : "e") + chinese, ignored -> {
			StringBuilder out = new StringBuilder();
			boolean matched = false;
			for (int i = 0; i < chinese.length(); ) {
				String hit = null;
				for (String key : SEGMENT_KEYS.getOrDefault(chinese.charAt(i), java.util.List.of())) {
					if (chinese.startsWith(key, i)) { hit = key; break; }
				}
				if (hit == null) { out.append(chinese.charAt(i++)); continue; }
				String english = ENGLISH.get(hit);
				out.append(ja ? JAPANESE.getOrDefault(hit, JAPANESE.getOrDefault(english, english)) : english);
				i += hit.length();
				matched = true;
			}
			return matched ? out.toString() : chinese;
		});
	}

	static String text(String chinese) {
		if (chinese == null || chinese() || chinese.isEmpty()) return chinese;
		String english = ENGLISH.get(chinese);
		if (english == null) {
			String ja = japanese() ? JAPANESE.get(chinese) : null;
			return ja != null ? ja : bySegments(chinese);
		}
		return japanese() ? JAPANESE.getOrDefault(chinese, JAPANESE.getOrDefault(english, english)) : english;
	}

	/** For text built at runtime (counts, paths), which the dictionaries cannot match: all three languages given directly. */
	static String text(String chinese, String english, String japanese) { return chinese() ? chinese : japanese() ? japanese : english; }
	static String text(String chinese, String english) { return chinese() ? chinese : japanese() ? JAPANESE.getOrDefault(chinese, JAPANESE.getOrDefault(english, english)) : english; }
	static String localizeEnglish(String english) { return japanese() ? JAPANESE.getOrDefault(english, english) : english; }
	static String manualLocale() { return chinese() ? "zh_cn" : japanese() ? "ja_jp" : "en_us"; }
	static boolean matchesSearch(String text, String needle) {
		if (needle == null || needle.isBlank()) return true;
		return text != null && (text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)) || phoneticMatches(text, needle));
	}
	static boolean phoneticMatches(String text, String needle) {
		if (text == null || needle == null || needle.isBlank()) return false;
		return chinese() ? ChineseSearchIndex.initials(text).contains(needle.toLowerCase(Locale.ROOT))
				: japanese() && JapaneseSearchIndex.matches(text, needle);
	}
	static String searchInitials(String text) { return chinese() ? ChineseSearchIndex.initials(text) : ""; }
	static void testLanguage(String language) { testLanguage = language; }
	static Map<String, String> englishTranslations() { return ENGLISH; }
	static Map<String, String> japaneseTranslations() { return JAPANESE; }

	private static Map<String, String> load(String locale) {
		try (var input = LuaUiText.class.getResourceAsStream("/assets/mc_lua_ide/i18n/" + locale + ".json")) {
			if (input == null) throw new IllegalStateException("Missing IDE translations: " + locale);
			var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> result = new LinkedHashMap<>();
			json.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
			return Map.copyOf(result);
		} catch (java.io.IOException error) { throw new IllegalStateException("Cannot load IDE translations: " + locale, error); }
	}
}
