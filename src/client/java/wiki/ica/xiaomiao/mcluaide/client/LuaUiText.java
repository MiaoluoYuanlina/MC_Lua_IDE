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

	static String text(String chinese) {
		if (chinese == null || chinese() || chinese.isEmpty()) return chinese;
		String english = ENGLISH.getOrDefault(chinese, chinese);
		return japanese() ? JAPANESE.getOrDefault(chinese, JAPANESE.getOrDefault(english, english)) : english;
	}

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
