package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Headless checks: metadata persistence, real window bounds, and asynchronous close saves. */
public final class LuaIdeSessionSmoke {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path sandbox = Files.createTempDirectory("mc_lua_ide_session_test_");
		try {
			checkSessions(sandbox);
			checkGeometry();
			checkApiNavigation(sandbox);
			checkCloseQueue();
			System.out.println("IDE session/layout/close-save smoke passed");
		} finally {
			try (var paths = Files.walk(sandbox)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private static void checkSessions(Path root) throws Exception {
		LuaIdeSession session = new LuaIdeSession(root, "world:测试世界:player:admin");
		String foldedSource = "local function hello()\n    print('hello')\nend\nif true then\n    hello()\nend\n";
		var folded = new LuaFunctionFolding.State(); folded.model(foldedSource);
		folded.apply(LuaFunctionFolding.Action.COLLAPSE_ALL);
		var lua = new LuaIdeSession.Document("示例/hello", false, false, 128, 42.5, folded.snapshot(foldedSource));
		var json = new LuaIdeSession.Document("demo/data/test/recipe/demo.json", true, false, 7, 11);
		var png = new LuaIdeSession.Document("demo/assets/test/textures/item/demo.png", true, true, 0, 0);
		var function = new LuaIdeSession.Document("demo/data/test/function/demo.mcfunction", true, false, 9, 33);
		var expected = new LuaIdeSession.State(List.of(lua, json, png, function), json.key());
		require(session.save(expected), "Session save failed");
		require(new LuaIdeSession(root, "world:测试世界:player:admin").load().equals(expected), "Session order/active/cursor/scroll lost");
		var restoredFolds = new LuaFunctionFolding.State();
		restoredFolds.restore(foldedSource, session.load().documents().getFirst().folds());
		require(restoredFolds.snapshot(foldedSource).equals(lua.folds()), "Session lost per-file folded functions/if offsets");
		require(new LuaIdeSession(root, "world:other:player:admin").load().documents().isEmpty(), "World sessions leaked");
		require(new LuaIdeSession(root, "world:测试世界:player:other").load().documents().isEmpty(), "Player sessions leaked");
		require(session.file.getParent().equals(root.resolve("sessions")), "Session filename escaped config directory");
		session.save(new LuaIdeSession.State(List.of(), ""));
		require(session.load().documents().isEmpty(), "Closed tabs were reopened");
		Properties broken = new Properties();
		broken.setProperty("count", "99999");
		broken.setProperty("active", "lua:missing");
		broken.setProperty("document.0.name", "../../outside");
		broken.setProperty("document.1.name", "test/hello");
		broken.setProperty("document.1.cursor", "invalid");
		broken.setProperty("document.1.scroll", "NaN");
		broken.setProperty("document.1.folds.hash", "invalid hash");
		broken.setProperty("document.1.folds.starts", "-1,garbage,20,20");
		broken.setProperty("document.2.name", "test/hello");
		broken.setProperty("document.3.name", "C:/unsafe");
		require(LuaIdeSession.writeProperties(session.file, broken), "Broken fixture save failed");
		var recovered = session.load();
		require(recovered.documents().size() == 1 && recovered.documents().getFirst().cursor() == 0
				&& recovered.documents().getFirst().scroll() == 0 && recovered.active().equals("lua:test/hello")
				&& recovered.documents().getFirst().folds().starts().isEmpty(), "Bad metadata not sanitized");
		try (var paths = Files.list(session.file.getParent())) {
			require(paths.noneMatch(path -> path.toString().endsWith(".tmp")), "Temporary state file leaked");
		}
		Path blocked = root.resolve("not-a-directory"); Files.createFile(blocked);
		require(!LuaIdeSession.writeProperties(blocked.resolve("state.properties"), new Properties()), "Write failure was ignored");
	}

	private static void checkGeometry() throws Exception {
		WindowGeometry geometry = new WindowGeometry(110, 60, 600, 420);
		Properties state = new Properties(); geometry.write(state, "window");
		require(geometry.equals(WindowGeometry.read(state, "window")), "Geometry round-trip failed");
		state.setProperty("window.width", "-10");
		require(WindowGeometry.read(state, "window") == null, "Invalid geometry accepted");
		require(WindowGeometry.read(new Properties(), "missing") == null, "Absent geometry did not use defaults");
		WindowBoundsAnimation animation = new WindowBoundsAnimation();
		animation.animateTo(0, 0, 100, 100, geometry.x(), geometry.y(), geometry.width(), geometry.height());
		require(animation.destination(0, 0, 100, 100).equals(geometry), "Closing during animation saved intermediate bounds");
		List<Object> windows = List.of(new LuaInsertOverlay(null, null, ignored -> { }),
				new LuaFileOverlay(null, null, "", ignored -> { }, ignored -> { }),
				new LuaVariableOverlay(null), new LuaApiDocsOverlay(null, null, ignored -> { }),
				new LuaResourcePackToolOverlay(null, null, LuaResourcePackToolOverlay.Kind.DIRECTORY,
						ignored -> { }, ignored -> { }, () -> null, ignored -> { }, ignored -> true));
		for (Object window : windows) {
			var type = window.getClass();
			type.getDeclaredMethod("resize", int.class, int.class).invoke(window, 1000, 700);
			type.getDeclaredMethod("restoreGeometry", WindowGeometry.class).invoke(window, geometry);
			require(type.getDeclaredMethod("captureGeometry").invoke(window).equals(geometry), type + " floating restore failed");
			type.getDeclaredMethod("setDockSide", DockSide.class).invoke(window, DockSide.LEFT);
			type.getDeclaredMethod("setDockBounds", int.class, int.class, int.class, int.class).invoke(window, 8, 68, 240, 500);
			for (int index = 0; index < 30; index++) type.getDeclaredMethod("tick").invoke(window);
			require(type.getDeclaredMethod("captureGeometry").invoke(window).equals(geometry), type + " docking overwrote floating layout");
			type.getDeclaredMethod("setDockSide", DockSide.class).invoke(window, DockSide.FLOATING);
			require(type.getDeclaredMethod("captureGeometry").invoke(window).equals(geometry), type + " undock lost layout");
			type.getDeclaredMethod("resize", int.class, int.class).invoke(window, 320, 240);
			type.getDeclaredMethod("restoreGeometry", WindowGeometry.class).invoke(window, geometry);
			WindowGeometry clamped = (WindowGeometry) type.getDeclaredMethod("captureGeometry").invoke(window);
			require(clamped.x() >= 0 && clamped.y() >= 0 && clamped.x() + clamped.width() <= 320
					&& clamped.y() + clamped.height() <= 240, type + " restored outside smaller screen");
		}
	}

	private static void checkApiNavigation(Path root) throws Exception {
		var docs = new LuaApiDocsOverlay(null, null, ignored -> { });
		docs.resize(1000, 700);
		docs.open();
		Properties defaults = apiNavigation(docs);
		require(defaults.getProperty("apiDocs.navigation.rootExpanded").equals("false")
				&& defaults.getProperty("apiDocs.navigation.sections.count").equals("0")
				&& defaults.getProperty("apiDocs.navigation.groups.count").equals("0"), "First API open is not fully collapsed");
		require(defaults.getProperty("apiDocs.navigation.symbol").isEmpty(), "First API open selected a hidden method");
		LuaApiDocumentation.replaceExternal(List.of()); docs.tick();
		require(defaults.equals(apiNavigation(docs)), "Catalog refresh expanded API categories");
		require(docs.openAt("MC.Entity.spawn"), "Direct API jump failed");
		Properties jumped = apiNavigation(docs);
		require(jumped.getProperty("apiDocs.navigation.rootExpanded").equals("true")
				&& jumped.containsValue("server") && jumped.containsValue("server:Entity"), "Direct API jump did not expand its path");
		// A user can collapse the selected method's ancestors while leaving its details visible.
		jumped.setProperty("apiDocs.navigation.rootExpanded", "false");
		jumped.setProperty("apiDocs.navigation.sections.count", "0");
		jumped.setProperty("apiDocs.navigation.groups.count", "0");
		jumped.setProperty("apiDocs.navigation.treeScroll", "12");
		jumped.setProperty("apiDocs.navigation.contentScroll", "187");
		jumped.setProperty("apiDocs.navigation.filter", "spawn");
		jumped.setProperty("apiDocs.navigation.pinyin", "false");
		docs.restoreNavigation(jumped);
		Properties expected = apiNavigation(docs);
		docs.close(); docs.open();
		require(expected.equals(apiNavigation(docs)), "Same-window reopen lost API browsing position or expanded ancestors");
		Path saved = root.resolve("api-navigation.properties");
		require(LuaIdeSession.writeProperties(saved, expected), "API navigation could not be saved");
		var reopened = new LuaApiDocsOverlay(null, null, ignored -> { });
		reopened.resize(1000, 700); reopened.restoreNavigation(LuaIdeSession.readProperties(saved)); reopened.open();
		require(expected.equals(apiNavigation(reopened)), "New IDE instance lost API selection, filter, folds or scroll");
		LuaApiDocumentation.replaceExternal(List.of()); reopened.tick();
		require(expected.equals(apiNavigation(reopened)), "Catalog reload reset restored API position");
		require(reopened.openAt("MC.Player.list"), "Restored window cannot jump to another API");
		jumped = apiNavigation(reopened);
		require(jumped.getProperty("apiDocs.navigation.filter").isEmpty()
				&& jumped.getProperty("apiDocs.navigation.contentScroll").equals("0")
				&& jumped.getProperty("apiDocs.navigation.symbol").equals("MC.Player.list")
				&& jumped.containsValue("server:Player"), "Explicit function jump kept stale position");
		// External API catalogs arrive asynchronously after the IDE opens.
		Properties pending = new Properties();
		pending.setProperty("apiDocs.navigation.symbol", "BrowseTest.ping");
		pending.setProperty("apiDocs.navigation.contentScroll", "91");
		pending.setProperty("apiDocs.navigation.sections.count", "1");
		pending.setProperty("apiDocs.navigation.sections.0", "root:browsetest");
		reopened.restoreNavigation(pending); reopened.close(); reopened.open();
		require(apiNavigation(reopened).getProperty("apiDocs.navigation.symbol").equals("BrowseTest.ping"), "Missing external API was discarded before catalog arrival");
		var translation = new wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi.Translation(
				"浏览测试", "测试函数", "true", "", "BrowseTest.ping()", Map.of());
		var descriptor = new wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi.Descriptor(
				"browse_test", "BrowseTest", "", "ping", List.of(), Map.of("zh_cn", translation), false);
		var page = new wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi.Page("browse_test", "guide", "intro",
				Map.of("zh_cn", new wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi.Translation("指南", "入门",
						List.of(new wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi.Element(
								wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi.ElementKind.TEXT, "使用方法")))));
		try {
			LuaApiDocumentation.replaceExternal(List.of(descriptor), List.of(page)); reopened.tick();
			require(apiNavigation(reopened).getProperty("apiDocs.navigation.contentScroll").equals("91"), "External catalog arrival lost scroll position");
			var field = LuaApiDocsOverlay.class.getDeclaredField("selected"); field.setAccessible(true);
			require(((LuaApiDocumentation.DocumentEntry) field.get(reopened)).symbol().equals("BrowseTest.ping"), "Deferred external API selection was not resolved");
			pending.remove("apiDocs.navigation.symbol");
			pending.setProperty("apiDocs.navigation.page", LuaApiDocumentation.allPages().getFirst().key());
			reopened.restoreNavigation(pending);
			Properties pageState = apiNavigation(reopened); reopened.close(); reopened.open();
			require(pageState.equals(apiNavigation(reopened)), "Third-party document page browsing state was lost");
			LuaApiDocumentation.replaceExternal(List.of()); reopened.tick();
			require(pageState.equals(apiNavigation(reopened)), "Temporarily missing document page lost its saved key");
			LuaApiDocumentation.replaceExternal(List.of(descriptor), List.of(page)); reopened.tick();
			field = LuaApiDocsOverlay.class.getDeclaredField("selectedPage"); field.setAccessible(true);
			require(field.get(reopened) != null && pageState.equals(apiNavigation(reopened)), "Deferred page selection was not resolved");
		} finally { LuaApiDocumentation.replaceExternal(List.of()); }
		Properties malformed = new Properties();
		malformed.setProperty("apiDocs.navigation.treeScroll", "-5");
		malformed.setProperty("apiDocs.navigation.contentScroll", "bad");
		malformed.setProperty("apiDocs.navigation.sections.count", "2147483647");
		malformed.setProperty("apiDocs.navigation.groups.count", "-1");
		malformed.setProperty("apiDocs.navigation.filter", "😀".repeat(170));
		reopened.restoreNavigation(malformed);
		Properties safe = apiNavigation(reopened);
		require(safe.getProperty("apiDocs.navigation.treeScroll").equals("0")
				&& safe.getProperty("apiDocs.navigation.contentScroll").equals("0")
				&& safe.getProperty("apiDocs.navigation.groups.count").equals("0")
				&& safe.getProperty("apiDocs.navigation.filter").codePointCount(0, safe.getProperty("apiDocs.navigation.filter").length()) == 160,
				"Malformed API browsing metadata was not bounded safely");
		System.out.println("API documentation navigation smoke passed: collapsed defaults, reopen/restart, folds, selection, scroll, filter, direct jumps, delayed mod catalogs and pages");
	}

	private static Properties apiNavigation(LuaApiDocsOverlay docs) {
		Properties properties = new Properties(); docs.captureNavigation(properties); return properties;
	}

	private static void checkCloseQueue() {
		List<Integer> attempted = new ArrayList<>();
		Map<Integer, CompletableFuture<Void>> acknowledgements = new HashMap<>();
		AtomicBoolean closed = new AtomicBoolean();
		CompletableFuture<Void> saving = LuaCloseSaveQueue.saveAll(List.of(1, 2, 3), document -> {
			attempted.add(document);
			var acknowledgement = new CompletableFuture<Void>(); acknowledgements.put(document, acknowledgement);
			return acknowledgement;
		}, Runnable::run);
		saving.thenRun(() -> closed.set(true));
		require(attempted.equals(List.of(1)) && !closed.get(), "Closed before first save acknowledgement");
		acknowledgements.get(1).complete(null);
		require(attempted.equals(List.of(1, 2)) && !closed.get(), "Closed before all files were saved");
		acknowledgements.get(2).completeExceptionally(new IllegalStateException("permission denied"));
		require(saving.isCompletedExceptionally() && !closed.get() && attempted.equals(List.of(1, 2)), "Failure closed editor or saved later documents");
		attempted.clear();
		LuaCloseSaveQueue.saveAll(List.of(1, 2, 3), document -> {
			attempted.add(document); return CompletableFuture.completedFuture(null);
		}, Runnable::run).thenRun(() -> closed.set(true)).join();
		require(closed.get() && attempted.equals(List.of(1, 2, 3)), "Successful save did not finish closing");
		require(LuaCloseSaveQueue.saveAll(List.of(1, 2), document -> { throw new IllegalStateException("write failed"); }, Runnable::run)
				.isCompletedExceptionally(), "Synchronous failure escaped save queue");
		require(LuaCloseSaveQueue.saveAll(List.of(), document -> { throw new AssertionError(); }, Runnable::run).isDone(), "Clean close tried to save");
	}
	private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
