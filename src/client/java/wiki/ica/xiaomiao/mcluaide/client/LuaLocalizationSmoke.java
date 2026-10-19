package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Language switching, Pinyin policy and literal safety, without changing host/game settings. */
public final class LuaLocalizationSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		try {
			for (String language : List.of("zh", "zh_cn", "zh_tw", "zh_hk", "zh_mo", "ZH-Hans", "zh-Hant-TW", "lzh", "yue_hk"))
				check(LuaUiText.chinese(language), "Chinese language variant " + language);
			for (String language : List.of("en_us", "en_gb", "de_de", "ja_jp", "ko_kr", "fr_fr", "az_az", ""))
				check(!LuaUiText.chinese(language), "Non-Chinese language " + language);
			var entry = new LuaInsertCatalog.Entry("minecraft:command_block", "命令方块", "minecraft");
			var type = new LuaRegistryCatalogData.RegistryType("test:custom", "命令方块", "", "test", 1);
			var docs = new LuaApiDocsOverlay(null, null, ignored -> {});
			Properties state = new Properties(); state.setProperty("apiDocs.navigation.pinyin", "true"); docs.restoreNavigation(state);
			LuaUiText.testLanguage("zh_cn");
			check(entry.matches("mlfk") && type.matches("mlfk"), "Chinese registry initial search");
			check(docs.pinyinToggleVisible() && docs.filterInputRight(1000) == 897, "Chinese toggle geometry");
			check(LuaInsertCatalog.Kind.ITEM.label().equals("物品 ID"), "Chinese kind label");
			check(LuaUiText.text("文件").equals("文件"), "Chinese original label");
			check(MarkdownManualDocument.load().lines().stream().anyMatch(line -> line.text().contains("使用手册")), "Chinese manual");
			LuaUiText.testLanguage("en_us");
			check(!entry.matches("mlfk") && !type.matches("mlfk"), "English disables cached Pinyin indexes");
			check(entry.matches("command_block") && entry.matches("命令方块"), "English keeps literal ID/name search");
			check(!docs.pinyinToggleVisible() && docs.filterInputRight(1000) == 990, "English hides toggle and expands input");
			check(LuaUiText.searchInitials("命令方块").isEmpty(), "English initial helper empty");
			check(LuaInsertCatalog.Kind.ITEM.label().equals("Item ID"), "Dynamic English kind label");
			for (var translation : LuaUiText.englishTranslations().entrySet()) {
				check(!translation.getValue().isBlank(), "Missing English label " + translation.getKey());
				check(LuaUiText.text(translation.getKey()).equals(translation.getValue()), "English resource lookup");
			}
			for (String unchanged : List.of("minecraft:diamond", "TestFolder/Example.lua", "local text = '文件'", "A user's private text"))
				check(LuaUiText.text(unchanged).equals(unchanged), "Never rewrite identifiers/code/user strings");
			var manual = MarkdownManualDocument.load();
			check(manual.lines().stream().anyMatch(line -> line.text().contains("User Guide")), "English manual");
			check(manual.codeBlocks().size() >= 8, "English manual runnable examples");
			for (String api : List.of("MC.Item.count", "MC.Item.startCooldown", "MC.Player.openChest", "MC.Entity.spawn", "MC.Event.onBlockBroken")) {
				var doc = LuaApiDocumentation.find(api);
				check(!doc.summary().matches("(?s).*[\\p{IsHan}].*"), "English API summary " + api);
				check(!doc.returns().matches("(?s).*[\\p{IsHan}].*"), "English API return description " + api);
				check(doc.tooltip().get(1).getString().startsWith("Description:"), "Localized hover heading " + api);
			}
			String source = "local players = MC.Player.list()\nfor _, p in ipairs(players) do print(p.uuid) end";
			check(LuaCodeAnalyzer.analyze("localization", source).valid(), "English UI preserves raw type inference");
			check(!LuaCodeAnalyzer.analyze("localization_type", "local flag = MC.Player.online('00000000-0000-0000-0000-000000000001')\nprint(flag + 1)").valid(), "English API labels preserve boolean arithmetic diagnostics");
			check(LuaApiMarkdownExporter.render(LuaApiDocumentation.allEntries(), List.of()).startsWith("# MC Lua IDE · API Reference"), "English Markdown export heading");
			var descriptor = new McLuaIdeApi.Descriptor("lang_test", "LangTest", "", "ping", List.of(), Map.of(
					"zh_cn", new McLuaIdeApi.Translation("语言测试", "中文简介", "布尔值", "", "LangTest.ping()", Map.of()),
					"en_us", new McLuaIdeApi.Translation("Language Test", "English extension summary", "boolean", "", "LangTest.ping()", Map.of())), false);
			LuaApiDocumentation.replaceExternal(List.of(descriptor));
			check(LuaApiDocumentation.find("LangTest.ping").summary().equals("English extension summary"), "Third-party English translation");
			LuaUiText.testLanguage("zh_tw");
			check(entry.matches("mlfk") && docs.pinyinToggleVisible(), "Switch back to Chinese restores Pinyin");
			check(LuaInsertCatalog.Kind.ITEM.label().equals("物品 ID"), "Switch back restores kind labels");
			LuaUiText.testLanguage("zh_cn");
			check(LuaApiDocumentation.find("LangTest.ping").summary().equals("中文简介"), "Third-party translation refreshes on language change");
			Properties preserved = new Properties(); docs.captureNavigation(preserved);
			check(preserved.getProperty("apiDocs.navigation.pinyin").equals("true"), "Language switching preserves Pinyin preference");
			System.out.println("Localization smoke passed: " + checks + " checks");
		} finally { LuaUiText.testLanguage(null); LuaApiDocumentation.replaceExternal(List.of()); }
	}
	private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
}
