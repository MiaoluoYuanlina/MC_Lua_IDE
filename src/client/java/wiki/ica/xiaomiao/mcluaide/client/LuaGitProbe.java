package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in UI/network regression, confined to build/git-probe and a newly created test world. No GitHub writes. */
final class LuaGitProbe {
	private static int stage, checks, editorWidth, floatingWidth;
	private static long started, until;
	private static CompletableFuture<?> task;
	private static LuaIdeScreen ide;
	private static LuaGitScreen git;
	private static String firstSha;
	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); stage = 1;
				client.options.guiScale().set(1); client.resizeGui();
				client.options.renderDistance().set(3); client.options.simulationDistance().set(5);
				CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Git probe creation cancelled"); }); return;
			}
			check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(180), "Probe timeout at " + stage, false);
			if (stage == 1) {
				if (!(client.screen instanceof CreateWorldScreen create)) return;
				create.getUiState().setName("git_" + System.currentTimeMillis()); create.getUiState().setAllowCommands(true);
				create.getUiState().setGenerateStructures(false);
				call(create, "onCreate"); stage = 2; return;
			}
			if (stage == 2) {
				if (client.player == null || !ScriptManager.isServerAvailable()) return;
				task = ScriptManager.checkAccess().thenCompose(ignored -> ScriptManager.saveGitConfig("", "main", LuaGit.Mode.LOCAL))
						.thenCompose(ignored -> ScriptManager.save("git_probe", "print(1)\n"));
				stage = 3; return;
			}
			if (stage == 3) {
				if (!ready()) return;
				ide = new LuaIdeScreen(null, "git_probe"); client.setScreen(ide);
				call(ide, "openDocument", new Class<?>[]{String.class, boolean.class}, "git_probe", false);
				set(ide, "manualOpen", false); set(ide, "manualProgress", 0F); call(ide, "openGitScreen");
				git = (LuaGitScreen) get(ide, "gitPanel"); stage = 4; until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); return;
			}
			if (stage == 4) {
				if (!idle() || !(boolean) get(ide, "sourceLoaded") || System.nanoTime() < until) return;
				// Discard only the previous probe's remembered geometry, so repeat runs exercise a real resize.
				git.setDockSide(DockSide.FLOATING);
				call(git, "applyBounds", new Class<?>[]{int.class, int.class, int.class, int.class},
						14, 16, Math.min(740, ide.width - 28), Math.min(470, ide.height - 32));
				check(get(git, "mode") == LuaGit.Mode.LOCAL, "Default local mode");
				check(((Button) get(git, "pushButton")).active, "Local commit does not require URL");
				((EditBox) get(git, "message")).setValue("第一个版本"); ((EditBox) get(git, "description")).setValue("测试版本简介详情");
				click("pushButton"); stage = 5; return;
			}
			if (stage == 5) {
				if (!idle()) return;
				LuaGit.History history = (LuaGit.History) get(git, "history");
				check(history != null && history.commits().size() == 1, "Real network creates first local version");
				firstSha = history.commits().getFirst().sha(); check(history.commits().getFirst().summary().equals("第一个版本"), "Summary in history");
				task = ScriptManager.save("git_probe", "print(2)\n").thenCompose(ignored -> ScriptManager.save("git_late", "print('late')"));
				stage = 6; return;
			}
			if (stage == 6) {
				if (!ready()) return;
				((EditBox) get(git, "message")).setValue("第二个版本");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 2)); stage = 7; return;
			}
			if (stage == 7) {
				if (!idle()) return;
				check(((LuaGit.History) get(git, "history")).commits().size() == 2, "Ctrl+Enter commits through IDE keyboard routing");
				click("historyTab");
				int left = (int) get(git, "left"), top = (int) get(git, "top");
				ide.mouseClicked(mouse(left + 35, top + 140), false); stage = 8; return;
			}
			if (stage == 8) {
				if (!idle()) return;
				LuaGit.Detail detail = (LuaGit.Detail) get(git, "detail");
				check(detail != null && detail.files().size() == 2, "Click history row loads changed files");
				check(detail.source().contains("late") || detail.source().contains("print(2)"), "Historical file preview delivered");
				click("previewButton"); check((boolean) get(git, "sourcePreview"), "Toggle diff/code preview");
				// Resize via the actual IDE mouse routing, then exercise narrow/docked layouts.
				int right = (int) get(git, "right"), top = (int) get(git, "top");
				ide.mouseClicked(mouse(right, top + 180), false);
				ide.mouseDragged(mouse(right - 160, top + 180), -160, 0); ide.mouseReleased(mouse(right - 160, top + 180));
				check((int) get(git, "right") - (int) get(git, "left") < 740, "Floating Git panel resizes");
				floatingWidth = (int) get(git, "right") - (int) get(git, "left");
				editorWidth = ((MultiLineEditBox) get(ide, "editor")).getWidth(); git.setDockSide(DockSide.RIGHT); call(ide, "applyDockLayout");
				until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); stage = 9; return;
			}
			if (stage == 9) {
				if (System.nanoTime() < until) return;
				check(((MultiLineEditBox) get(ide, "editor")).getWidth() < editorWidth, "Docked panel shrinks code viewport");
				check(git.dockSide() == DockSide.RIGHT, "Git panel docks with other windows");
				check(((Button) get(git, "listButton")).visible, "Narrow history has return-to-list control");
				assertVisibleBounds(); click("listButton");
				check(!(boolean) get(git, "detailOpen"), "Return to narrow history list");
				int left = (int) get(git, "left"), top = (int) get(git, "top");
				ide.mouseClicked(mouse(left + 35, top + 182), false); stage = 10; return;
			}
			if (stage == 10) {
				if (!idle()) return;
				LuaGit.Detail detail = (LuaGit.Detail) get(git, "detail");
				check(detail.sha().equals(firstSha) && detail.message().contains("测试版本简介详情"), "Select earlier version with full description");
				MultiLineEditBox editor = (MultiLineEditBox) get(ide, "editor"); editor.setValue("print(1)\n-- unsaved\n");
				click("restoreButton"); check(((String) get(git, "status")).contains("未保存"), "Restore protects unsaved editor");
				check(!(boolean) get(git, "confirmed"), "Unsaved restore not armed"); editor.setValue("print(1)\n");
				click("restoreButton"); check((boolean) get(git, "confirmed"), "Restore needs second confirmation");
				click("restoreButton"); stage = 11; return;
			}
			if (stage == 11) {
				if (!idle() || (boolean) get(ide, "sourceLoading")) return;
				check(((String) get(git, "status")).contains("已恢复"), "Restore completion reaches UI");
				check(((MultiLineEditBox) get(ide, "editor")).getValue().equals("print(1)\n"), "Open file reloads restored source");
				check(((LuaGit.History) get(git, "history")).commits().size() == 4, "Backup and restore append two history records");
				task = ScriptManager.read("git_probe").thenAccept(source -> check(source.equals("print(1)\n"), "Server code actually restored"))
						.thenCompose(ignored -> ScriptManager.listScripts()).thenAccept(list -> check(!list.contains("git_late"), "Newer file actually removed"));
				stage = 12; return;
			}
			if (stage == 12) {
				if (!ready()) return;
				click("githubButton"); check(get(git, "mode") == LuaGit.Mode.GITHUB, "GitHub switch works");
				stage = 120; return;
			}
			if (stage == 120) {
				if (!idle()) return;
				check(((Button) get(git, "loginButton")).visible, "GitHub starts with browser-login choice");
				check(((Button) get(git, "loginButton")).active, "Bundled public App enables browser login without manual setup");
				check(((LuaGit.Account) get(git, "account")).clientId().equals("Ov23li9ALF0BE5QLuRIs"), "Default App reaches client through actual server response");
				click("configureButton"); assertVisibleBounds();
				check(((EditBox) get(git, "clientId")).visible && ((Button) get(git, "clientIdSaveButton")).visible, "Public App configuration accessible in narrow dock");
				// Fake display DTOs only. Never call LOGIN or CREATE_REPOSITORY against GitHub.
				call(git, "applyAccount", new Class<?>[]{LuaGit.Account.class}, new LuaGit.Account(LuaGit.LoginPhase.PENDING, "0123456789abcdef1234", "", "ABCD-1234", "https://github.com/login/device", System.currentTimeMillis() + 900000, 5, "Synthetic verification code"));
				set(git, "nextAuthPoll", Long.MAX_VALUE); page("ACCOUNT"); assertVisibleBounds();
				click("codeButton"); check(client.keyboardHandler.getClipboard().equals("ABCD-1234"), "One-time code can be copied via root UI routing");
				click("logoutButton"); stage = 121; return;
			}
			if (stage == 121) {
				if (!idle()) return;
				check(((LuaGit.Account) get(git, "account")).phase() == LuaGit.LoginPhase.SIGNED_OUT, "Cancel returns to browser login through actual server request");
				call(git, "applyAccount", new Class<?>[]{LuaGit.Account.class}, new LuaGit.Account(LuaGit.LoginPhase.CONNECTED, "0123456789abcdef1234", "synthetic-owner", "", "", 0, 5, "Synthetic account only"));
				page("ACCOUNT"); click("newRepoButton"); assertVisibleBounds();
				check((boolean) get(git, "privateRepository"), "New repositories default to private");
				((EditBox) get(git, "repoName")).setValue("synthetic-repository"); click("createRepoButton");
				check((boolean) get(git, "createConfirmed"), "Repository creation requires explicit second confirmation, no HTTP before it");
				click("visibilityButton"); check(!(boolean) get(git, "privateRepository") && !(boolean) get(git, "createConfirmed"), "Changing visibility clears creation confirmation");
				set(git, "repositories", new LuaGit.Repositories(java.util.stream.IntStream.range(0, 30)
						.mapToObj(i -> new LuaGit.Repository("owner/repo" + i, "https://github.com/owner/repo" + i + ".git", "main", true, "测试仓库" + i)).toList(), 1, true));
				page("REPOSITORIES"); assertVisibleBounds();
				((EditBox) get(git, "repoSearch")).setValue("repo29");
				check(((java.util.List<?>) call(git, "filteredRepositories")).size() == 1, "Repository name filtering works");
				int rowY = (int) call(git, "repositoryTop"), rowX = (int) get(git, "left") + 20;
				ide.mouseClicked(mouse(rowX, rowY + 8), false); ide.mouseReleased(mouse(rowX, rowY + 8));
				check(get(git, "selectedRepository").equals("owner/repo29") && ((Button) get(git, "selectRepoButton")).active, "Root IDE routes repository selection");
				((EditBox) get(git, "repoSearch")).setValue("测试仓库");
				check(((java.util.List<?>) call(git, "filteredRepositories")).size() == 30, "Repository description filtering supports Chinese");
				git.mouseScrolled(rowX, rowY + 8, 0, -1); check((int) get(git, "repoScroll") > 0, "Repository list scrolls in narrow dock");
				call(git, "applyAccount", new Class<?>[]{LuaGit.Account.class}, new LuaGit.Account(LuaGit.LoginPhase.CONFIGURE, "", "", "", "", 0, 5, "No real login"));
				page("ACCOUNT");
				click("commitTab"); check(!((Button) get(git, "pushButton")).active, "GitHub upload disabled without saved repository");
				stage = 122; return;
			}
			if (stage == 122) {
				if (!idle()) return;
				click("settingsTab"); assertVisibleBounds(); check(((EditBox) get(git, "repository")).visible, "Repository setting shown only on settings page");
				click("localButton"); check(get(git, "mode") == LuaGit.Mode.LOCAL, "Switch back to local without network");
				click("saveButton"); stage = 13; return;
			}
			if (stage == 13) {
				if (!idle()) return;
				check(!(boolean) get(git, "dirty"), "Local mode settings saved with blank GitHub URL");
				git.setDockSide(DockSide.FLOATING); call(ide, "applyDockLayout"); click("historyTab");
				until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); stage = 14; return;
			}
			if (stage == 14) {
				if (System.nanoTime() < until || !idle()) return;
				check(git.captureGeometry().width() > 0, "Floating geometry preserved after undocking");
				check(git.dockSide() == DockSide.FLOATING && (int) get(git, "right") - (int) get(git, "left") == floatingWidth,
						"Clicking a tab during undock animation preserves floating width");
				assertVisibleBounds();
				set(ide, "pendingInterfaceScale", 1.25F); ide.tick(); assertVisibleBounds();
				set(ide, "pendingInterfaceScale", 1F); ide.tick();
				LuaGit.History history = (LuaGit.History) get(git, "history");
				call(git, "select", new Class<?>[]{String.class, String.class}, history.commits().getFirst().sha(), "");
				stage = 15; until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); return;
			}
			if (stage == 15) {
				if (!idle() || System.nanoTime() < until) return;
				assertVisibleBounds(); check(get(git, "detail") != null, "Detailed preview survives UI scaling");
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "git-history.png", client.getMainRenderTarget(), 1, ignored -> { });
				set(git, "mode", LuaGit.Mode.GITHUB);
				call(git, "applyAccount", new Class<?>[]{LuaGit.Account.class}, new LuaGit.Account(LuaGit.LoginPhase.CONNECTED, "0123456789abcdef1234", "synthetic-owner", "", "", 0, 5, "Synthetic UI display: no real account"));
				((EditBox) get(git, "repoSearch")).setValue(""); page("REPOSITORIES");
				stage = 16; until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); return;
			}
			if (stage == 16) {
				if (System.nanoTime() < until) return;
				assertVisibleBounds();
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "github-repositories.png", client.getMainRenderTarget(), 1, ignored -> { });
				page("CONFIGURE_GITHUB");
				call(git, "applyBounds", new Class<?>[]{int.class, int.class, int.class, int.class}, 20, 40, 360, 300);
				stage = 17; until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); return;
			}
			if (stage == 17) {
				if (System.nanoTime() < until) return;
				assertVisibleBounds();
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "github-configuration.png", client.getMainRenderTarget(), 1, ignored -> { });
				check((int) get(git, "notesMaximum") > 0, "Setup instructions scroll in minimum-size panel");
				git.mouseScrolled(40, 245, 0, -4); check((int) get(git, "notesScroll") > 0, "Mouse wheel exposes all OAuth setup instructions");
				System.out.println("Git real UI/network probe passed: " + checks + " checks; browser-login UI, repository selection, scrollable setup, commit, history, restore, drag/resize/dock (no real GitHub writes)");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) { stage = 99; client.stop(); throw new IllegalStateException("Git UI probe failed at " + stage, error); }
	}
	private static boolean ready() { if (task != null && !task.isDone()) return false; if (task != null) { task.join(); task = null; } return true; }
	private static boolean idle() throws Exception { return !(boolean) get(git, "busy") && !(boolean) get(git, "loading") && !(boolean) get(git, "authBusy"); }
	@SuppressWarnings({"unchecked", "rawtypes"}) private static void page(String name) throws Exception {
		set(git, "page", Enum.valueOf((Class) get(git, "page").getClass(), name)); call(git, "layoutWidgets");
	}
	private static MouseButtonEvent mouse(double x, double y) { return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)); }
	private static void click(String name) throws Exception {
		Button button = (Button) get(git, name); check(button.visible && button.active, "Button clickable: " + name);
		check(ide.mouseClicked(mouse(button.getX() + button.getWidth() / 2, button.getY() + 8), false), "IDE routes click: " + name);
		ide.mouseReleased(mouse(button.getX() + 8, button.getY() + 8));
	}
	private static void assertVisibleBounds() throws Exception {
		int left = (int) get(git, "left"), right = (int) get(git, "right"), top = (int) get(git, "top"), bottom = (int) get(git, "bottom");
		for (String name : new String[]{"pushButton", "restoreButton", "saveButton", "backButton", "localButton", "githubButton", "historyTab", "previewButton", "copyButton", "listButton",
				"accountButton", "loginButton", "browserButton", "codeButton", "logoutButton", "configureButton", "clientIdSaveButton", "appGuideButton", "reposButton", "reposRefreshButton", "reposNextButton", "reposPreviousButton", "newRepoButton", "selectRepoButton", "visibilityButton", "createRepoButton"}) {
			Button button = (Button) get(git, name); if (!button.visible) continue;
			check(button.getX() >= left && button.getRight() <= right && button.getY() >= top && button.getBottom() <= bottom, "Control inside panel: " + name);
		}
	}
	private static Object get(Object target, String name) throws Exception { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
	private static void set(Object target, String name, Object value) throws Exception { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
	private static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[]{}); }
	private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
	private static void check(boolean condition, String message) { check(condition, message, true); }
	private static void check(boolean condition, String message, boolean count) { if (!condition) throw new AssertionError(message); if (count) checks++; }
}
