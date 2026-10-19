package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class JapaneseSearchSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		try {
			for (String locale : List.of("ja", "ja_jp", "JA-JP")) check(LuaUiText.japanese(locale), "Japanese locale " + locale);
			for (String locale : List.of("zh_cn", "zh_tw", "en_us", "jv_id", "")) check(!LuaUiText.japanese(locale), "Other locale " + locale);
			for (String[] pair : new String[][] {
				{"コマンドブロック","komandoburokku"}, {"コマンドブロック","koma"}, {"コマンドブロック","komando burokku"},
				{"コマンド","こまんど"}, {"こまんど","コマンド"}, {"ｺﾏﾝﾄﾞﾌﾞﾛｯｸ","komandoburokku"},
				{"コマンド","ｺﾏﾝﾄﾞ"}, {"コマンド","ＫＯＭＡＮＤＯ"}, {"ダイヤモンド","daiyamondo"},
				{"キャッシュ","kyasshu"}, {"キャッシュ","kyassyu"}, {"シチュー","shichuu"}, {"シチュー","sityu"},
				{"チェスト","chesuto"}, {"ジャングル","janguru"}, {"ファイル","fairu"}, {"ヴァニラ","vanira"},
				{"クォーツ","kwootsu"}, {"クォーツ","kuootsu"}, {"クォーツ","kwotsu"},
				{"コーヒー","koohii"}, {"コーヒー","kohi"}, {"コーヒー","kōhī"}, {"カ\u3099ラス","garasu"},
				{"変数","hensuu"}, {"変数","hensu"}, {"保存","hozon"}, {"保存","ほぞん"},
				{"鉄の剣","tetsunoken"}, {"鉄の剣","てつの剣"}, {"てつのけん","鉄の剣"}, {"金床","kanatoko"}, {"ルートテーブル","ruutoteeburu"},
				{"test:custom · テストMod","tesuto"}, {"ローマ字","romaji"}
			}) check(JapaneseSearchIndex.matches(pair[0], pair[1]), pair[0] + " should match " + pair[1]);
			check(!JapaneseSearchIndex.matches("ブロック", "buroku"), "Small tsu must not disappear");
			check(!JapaneseSearchIndex.matches("foo龘bar", "foobar"), "Unknown kanji is not dropped");
			check(!JapaneseSearchIndex.matches("foo😀bar", "foobar"), "Emoji is not dropped between words");
			check(!JapaneseSearchIndex.matches("命令方块", "mlfk"), "Never use Chinese Pinyin in Japanese");
			check(!JapaneseSearchIndex.matches("コマンド", "komando", false), "Romaji switch disables conversion");
			check(JapaneseSearchIndex.matches("コマンド", "こまんど", false), "Kana remains usable with romaji off");
			check(JapaneseSearchIndex.matches("minecraft:stone", "stone", false), "Literal IDs always searchable");
			check(JapaneseSearchIndex.matches("", ""), "Empty search");
			range("x ﾌﾞﾛｯｸ y", "burokku", 2, 7);
			range("😀コマンド!", "komando", 2, 6);
			range("x 保存 y", "hozon", 2, 4);
			range("カ\u3099ラス", "が", 0, 2);
			for (int i = 0; i < JapaneseSearchIndex.cacheLimit() + 50; i++) JapaneseSearchIndex.matches("テスト" + i, "tesuto");
			check(JapaneseSearchIndex.cacheSize() <= JapaneseSearchIndex.cacheLimit(), "Bounded reading cache");

			LuaUiText.testLanguage("ja_jp");
			check(LuaUiText.text("文件").equals("ファイル"), "Japanese menu");
			check(LuaUiText.text("关闭", "Off").equals("オフ"), "Context-sensitive Japanese text");
			for (String english : new java.util.HashSet<>(LuaUiText.englishTranslations().values())) {
				check(LuaUiText.japaneseTranslations().containsKey(english), "Missing Japanese label: " + english);
				check(!LuaUiText.japaneseTranslations().get(english).isBlank(), "Blank Japanese label");
			}
			check(LuaUiText.manualLocale().equals("ja_jp") && MarkdownManualDocument.load().lines().getFirst().text().contains("ユーザーガイド"), "Japanese guide");
			for (String unchanged : List.of("minecraft:stone", "Test/コマンド.lua", "local text = 'コマンド'", "User input"))
				check(LuaUiText.text(unchanged).equals(unchanged), "No data rewriting");
			var entry = new LuaInsertCatalog.Entry("minecraft:command_block", "コマンドブロック", "minecraft");
			check(entry.matches("komando") && entry.matches("こまんど"), "ID picker romaji/kana");
			check(LuaInsertCatalog.Kind.ITEM.label().equals("アイテム ID") && LuaUiText.matchesSearch(LuaInsertCatalog.Kind.ITEM.label(), "aitemu"), "Localized type search");
			var registry = new LuaRegistryCatalogData.RegistryType("test:custom", "変数", "", "test", 1);
			check(registry.matches("hensuu"), "Registry type search");
			String annotated = LuaRegistryCatalogData.entryLabel("ダイヤモンド", "Diamond", "钻石");
			check(annotated.contains("钻石") && JapaneseSearchIndex.matches(annotated, "daiyamondo"), "Server annotations do not hide real Japanese names");
			check(LuaRegistryCatalogData.entryLabel("", "Fallback", "Mod label").equals("Mod label"), "Custom annotation fallback preserved");
			check(new LuaRegistryCatalogData.RegistryType("minecraft:enchantment", "附魔 ID", "", "minecraft", 1).matches("enchanto"), "Native Japanese type caption retained with Chinese fallback annotation");
			Class<?> namespaceClass = Class.forName(LuaInsertOverlay.class.getName() + "$NamespaceOption");
			var constructor = namespaceClass.getDeclaredConstructor(String.class, String.class, int.class); constructor.setAccessible(true);
			Object namespace = constructor.newInstance("test", "テストMod", 10);
			var matcher = namespaceClass.getDeclaredMethod("matches", String.class); matcher.setAccessible(true);
			check((boolean) matcher.invoke(namespace, "tesuto"), "Namespace romaji search");

			var docs = new LuaApiDocsOverlay(null, null, ignored -> {}); docs.resize(1000, 700); docs.open();
			var progress = LuaApiDocsOverlay.class.getDeclaredField("progress"); progress.setAccessible(true); progress.setFloat(docs, 1);
			Properties state = new Properties(); state.setProperty("apiDocs.navigation.pinyin", "false");
			state.setProperty("apiDocs.navigation.romaji", "true"); docs.restoreNavigation(state);
			check(!docs.pinyinToggleVisible() && docs.romajiToggleVisible() && docs.filterInputRight(1000) == 871, "Japanese toggle and input geometry");
			var countDoc = LuaApiDocumentation.allEntries().stream().filter(item -> item.symbol().equals("MC.Item.count")).findFirst().orElseThrow();
			var matches = LuaApiDocsOverlay.class.getDeclaredMethod("matchesFilter", LuaApiDocumentation.DocumentEntry.class, String.class); matches.setAccessible(true);
			check((boolean) matches.invoke(docs, countDoc, "inbentori"), "API description romaji search");
			var setQuery = LuaApiDocsOverlay.class.getDeclaredMethod("setFilterQuery", String.class); setQuery.setAccessible(true); setQuery.invoke(docs, "komando");
			var highlights = LuaApiDocsOverlay.class.getDeclaredMethod("filterMatchRanges", String.class); highlights.setAccessible(true);
			check(!((List<?>) highlights.invoke(docs, "コマンドを実行")).isEmpty(), "API romaji highlight");
			clickToggle(docs);
			Properties saved = new Properties(); docs.captureNavigation(saved);
			check(saved.getProperty("apiDocs.navigation.romaji").equals("false") && saved.getProperty("apiDocs.navigation.pinyin").equals("false"), "Separate persisted switches");
			check(!(boolean) matches.invoke(docs, countDoc, "inbentori"), "Real toggle disables API romaji search");
			check(((List<?>) highlights.invoke(docs, "コマンドを実行")).isEmpty(), "Disabled toggle removes phonetic highlights");
			check((boolean) matches.invoke(docs, countDoc, "いんべんとり"), "Kana search retained after toggle");
			var restored = new LuaApiDocsOverlay(null, null, ignored -> {}); restored.restoreNavigation(saved);
			Properties roundtrip = new Properties(); restored.captureNavigation(roundtrip);
			check(roundtrip.getProperty("apiDocs.navigation.romaji").equals("false"), "Restart restores romaji preference");
			check(LuaApiDocumentation.find("MC.Item.startCooldown").summary().contains("クールダウン"), "Japanese API summary");
			check(LuaApiDocumentation.find("MC.Event.onBlockBroken").rawDetails().startsWith("event.type"), "Japanese-first startup never writes localized text into canonical API metadata");
			check(LuaApiDocumentation.find("MC.Item.count").tooltip().get(1).getString().startsWith("説明："), "Japanese hover heading");
			check(LuaApiDocumentation.find("MC.Item.startCooldown").describe("ticks?").contains("Tick"), "Japanese parameters");
			check(LuaCodeAnalyzer.analyze("ja", "local players = MC.Player.list()\nfor _, player in ipairs(players) do print(player.uuid) end").valid(), "Raw analyzer hints unchanged");
			var descriptor = new McLuaIdeApi.Descriptor("ja_test", "JaTest", "", "ping", List.of(), Map.of(
					"en_us", new McLuaIdeApi.Translation("Test", "English summary", "boolean", "", "JaTest.ping()", Map.of()),
					"ja_jp", new McLuaIdeApi.Translation("テスト", "日本語の説明", "真偽値", "", "JaTest.ping()", Map.of())), false);
			LuaApiDocumentation.replaceExternal(List.of(descriptor));
			check(LuaApiDocumentation.find("JaTest.ping").summary().equals("日本語の説明"), "Third-party Japanese translation");
			LuaUiText.testLanguage("en_us");
			check(!entry.matches("komando") && !registry.matches("hensuu") && !docs.romajiToggleVisible(), "English disables Japanese expansion");
			check(LuaApiDocumentation.find("JaTest.ping").summary().equals("English summary"), "External English language restoration");
			LuaUiText.testLanguage("zh_cn");
			check(docs.pinyinToggleVisible() && !docs.romajiToggleVisible(), "Chinese keeps its own Pinyin toggle");
			check(new LuaInsertCatalog.Entry("minecraft:command_block", "命令方块", "minecraft").matches("mlfk"), "Chinese Pinyin still works");
			System.out.println("Japanese language/search smoke passed: " + checks + " checks");
		} finally { LuaUiText.testLanguage(null); LuaApiDocumentation.replaceExternal(List.of()); }
	}
	private static void range(String text, String query, int start, int end) {
		var ranges = JapaneseSearchIndex.ranges(text, query, true);
		check(ranges.stream().anyMatch(range -> range.start() == start && range.end() == end), "Original highlight offsets: " + text + " / " + query + " = " + ranges);
	}
	private static int field(Object target, String name) throws Exception {
		var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(target);
	}
	private static void clickToggle(LuaApiDocsOverlay docs) throws Exception {
		var panel = LuaApiDocsOverlay.class.getDeclaredMethod("panelX"); panel.setAccessible(true);
		int right = (int) panel.invoke(docs) + field(docs, "windowWidth");
		int y = field(docs, "windowY") + field(docs, "TITLE_HEIGHT") + field(docs, "BREADCRUMB_HEIGHT") + 15;
		check(docs.mouseClicked(new MouseButtonEvent(right - 16, y, new MouseButtonInfo(0, 0)), false), "Real romaji toggle click");
	}
	private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
}
