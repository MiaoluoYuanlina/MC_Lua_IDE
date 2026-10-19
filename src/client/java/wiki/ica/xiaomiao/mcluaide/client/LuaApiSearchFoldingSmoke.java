package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Real sidebar mouse routing without rendering, including filtered folds and persisted navigation. */
public final class LuaApiSearchFoldingSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var docs = create(); docs.openAt("MC.Player.list");
		Properties normal = navigation(docs);
		query(docs, "MC.Item.name");
		require(has(docs, "METHOD"), "Search did not auto-expand matching paths");
		String selected = navigation(docs).getProperty("apiDocs.navigation.symbol");
		click(docs, "GROUP"); require(!has(docs, "METHOD") && label(docs, "GROUP").startsWith("▸"), "Filtered group cannot collapse");
		click(docs, "SECTION"); require(!has(docs, "GROUP") && label(docs, "SECTION").startsWith("▸"), "Filtered section cannot collapse");
		click(docs, "SECTION"); require(has(docs, "GROUP") && !has(docs, "METHOD"), "Reopening parent forgot child's folded state");
		require(navigation(docs).getProperty("apiDocs.navigation.symbol").equals(selected), "Folding changed selected function details");
		Properties folded = navigation(docs);
		docs.close(); docs.open(); require(navigation(docs).equals(folded) && !has(docs, "METHOD"), "Reopen expanded search folds");
		var restored = create(); restored.restoreNavigation(folded); restored.open();
		require(!has(restored, "METHOD") && has(restored, "GROUP"), "Restored filter lost collapsed groups");
		LuaApiDocumentation.replaceExternal(List.of()); restored.tick();
		require(!has(restored, "METHOD"), "Catalog refresh reopened collapsed search group");
		click(restored, "GROUP"); require(has(restored, "METHOD") && label(restored, "GROUP").startsWith("▾"), "Filtered group cannot reopen");
		click(restored, "ROOT"); require(rows(restored).size() == 1, "Filtered root cannot collapse");
		click(restored, "ROOT"); require(has(restored, "METHOD"), "Filtered root cannot reopen");
		click(docs, "GROUP");
		require(docs.keyPressed(new net.minecraft.client.input.KeyEvent(264, 0, 2)), "Keyboard cannot navigate filtered results");
		query(docs, ""); Properties cleared = navigation(docs);
		require(cleared.getProperty("apiDocs.navigation.sections.count").equals(normal.getProperty("apiDocs.navigation.sections.count"))
				&& cleared.getProperty("apiDocs.navigation.groups.count").equals(normal.getProperty("apiDocs.navigation.groups.count"))
				&& cleared.getProperty("apiDocs.navigation.groups.0").equals(normal.getProperty("apiDocs.navigation.groups.0")), "Search folding changed unfiltered browsing folds");
		query(docs, "MC.Item.name"); click(docs, "GROUP"); query(docs, "MC.Entity.spawn");
		require(has(docs, "METHOD"), "New query did not reveal matches");
		for (String filter : List.of("附魔", "fh")) {
			query(docs, filter); require(has(docs, "SECTION"), "Chinese/initial search lost results");
			click(docs, "SECTION"); require(label(docs, "SECTION").startsWith("▸"), "Chinese/initial search section cannot fold");
		}
		var descriptor = new McLuaIdeApi.Descriptor("search_test", "FoldTest", "", "ping", List.of(),
				Map.of("zh_cn", new McLuaIdeApi.Translation("折叠测试", "测试方法", "true", "", "FoldTest.ping()", Map.of())), false);
		var page = new McLuaIdeDocsApi.Page("search_test", "guide", "intro", Map.of("zh_cn", new McLuaIdeDocsApi.Translation("指南", "折叠测试指南",
				List.of(new McLuaIdeDocsApi.Element(McLuaIdeDocsApi.ElementKind.TEXT, "折叠测试手册")))));
		try {
			LuaApiDocumentation.replaceExternal(List.of(descriptor), List.of(page)); docs.tick();
			query(docs, "FoldTest.ping"); require(has(docs, "METHOD"), "External API search missing");
			click(docs, "SECTION"); require(!has(docs, "METHOD"), "External custom root cannot fold under search");
			click(docs, "SECTION"); require(has(docs, "METHOD"), "External custom root cannot reopen");
			query(docs, "折叠测试手册"); require(has(docs, "PAGE"), "Third-party page search missing");
			click(docs, "SECTION"); require(!has(docs, "PAGE"), "Third-party page category cannot collapse");
			click(docs, "SECTION"); require(has(docs, "PAGE"), "Third-party page category cannot reopen");
		} finally { LuaApiDocumentation.replaceExternal(List.of()); }
		System.out.println("API search folding smoke passed: " + checks + " checks; real mouse clicks, root/sections/groups, Chinese/initials, custom roots/pages, selection, independent folds and persistence");
	}
	private static LuaApiDocsOverlay create() throws Exception {
		var docs = new LuaApiDocsOverlay(null, null, ignored -> {}); docs.resize(1000, 700); docs.open();
		var progress = LuaApiDocsOverlay.class.getDeclaredField("progress"); progress.setAccessible(true); progress.setFloat(docs, 1F); return docs;
	}
	private static void query(LuaApiDocsOverlay docs, String query) throws Exception {
		var method = LuaApiDocsOverlay.class.getDeclaredMethod("setFilterQuery", String.class); method.setAccessible(true); method.invoke(docs, query);
	}
	private static List<?> rows(LuaApiDocsOverlay docs) throws Exception { return (List<?>) invoke(docs, "treeRows"); }
	private static boolean has(LuaApiDocsOverlay docs, String type) throws Exception {
		for (var row : rows(docs)) if (invoke(row, "type").toString().equals(type)) return true; return false;
	}
	private static String label(LuaApiDocsOverlay docs, String type) throws Exception {
		for (var row : rows(docs)) if (invoke(row, "type").toString().equals(type)) return (String) invoke(row, "label"); throw new AssertionError("Missing row " + type);
	}
	private static void click(LuaApiDocsOverlay docs, String type) throws Exception {
		var rows = rows(docs); int index = 0;
		while (index < rows.size() && !invoke(rows.get(index), "type").toString().equals(type)) index++;
		require(index < rows.size(), "Missing clickable " + type);
		int scroll = Math.max(0, index - 2);
		var field = LuaApiDocsOverlay.class.getDeclaredField("navigationScroll"); field.setAccessible(true); field.setInt(docs, scroll);
		int x = (int) invoke(docs, "panelX") + 16, y = (int) invoke(docs, "mainTop") + 3 + (index - scroll) * 19 + 8;
		require(docs.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false), "Sidebar click not handled");
	}
	private static Object invoke(Object target, String name) throws Exception { var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); }
	private static Properties navigation(LuaApiDocsOverlay docs) { var properties = new Properties(); docs.captureNavigation(properties); return properties; }
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
