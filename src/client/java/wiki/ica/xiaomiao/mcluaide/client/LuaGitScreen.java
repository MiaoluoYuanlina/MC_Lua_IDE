package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.net.URI;
import net.minecraft.util.Util;

/** Administrator-only Git publishing and browser-login flow. Access tokens never enter this UI. */
final class LuaGitScreen extends Screen {
	private final LuaIdeScreen previous;
	private EditBox repository;
	private EditBox branch;
	private EditBox message, description;
	private Button pushButton;
	private Button saveButton;
	private Button backButton;
	private Button localButton, githubButton, commitTab, historyTab, settingsTab;
	private Button refreshButton, olderButton, newerButton, restoreButton, previewButton, listButton, copyButton;
	private enum Page { COMMIT, HISTORY, SETTINGS, ACCOUNT, REPOSITORIES, CREATE_REPOSITORY, CONFIGURE_GITHUB }
	private EditBox clientId, repoSearch, repoName, repoDescription;
	private Button accountButton, loginButton, browserButton, codeButton, logoutButton, configureButton, clientIdSaveButton, appGuideButton;
	private Button reposButton, reposRefreshButton, reposNextButton, reposPreviousButton, newRepoButton, selectRepoButton, visibilityButton, createRepoButton;
	private LuaGit.Account account;
	private LuaGit.Repositories repositories;
	private String selectedRepository = "";
	private int repoScroll;
	private int notesScroll, notesMaximum;
	private boolean authBusy, privateRepository = true, createConfirmed;
	private long nextAuthPoll;
	private Page page = Page.COMMIT;
	private LuaGit.Mode mode = LuaGit.Mode.LOCAL;
	private LuaGit.History history;
	private LuaGit.Detail detail;
	private String selectedSha = "";
	private String location = "";
	private int historyScroll, fileScroll, previewScroll, horizontalScroll;
	private boolean sourcePreview, detailOpen;
	private List<String> previewLines = List.of();
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private int left, top, right, bottom;
	private int floatingX = -1, floatingY = -1;
	private int floatingWidth = -1, floatingHeight = -1;
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked;
	private int resizeEdges;
	private double resizeStartX, resizeStartY;
	private int resizeLeft, resizeTop, resizeRight, resizeBottom;
	private boolean embedded, dragging;
	private boolean closing, applyingAnimatedBounds;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();
	private double dragOffsetX, dragOffsetY;
	private boolean loading;
	private boolean loaded;
	private boolean busy;
	private boolean dirty;
	private boolean confirmed;
	private boolean updating;
	private String status = "正在读取服务端 Git 设置…";

	LuaGitScreen(LuaIdeScreen previous) {
		super(Component.literal(LuaUiText.text("MC Lua IDE · Git 版本管理")));
		this.previous = previous;
	}

	void openEmbedded(int width, int height) {
		embedded = true;
		init(width, height);
		rememberFloatingBounds();
		int targetX = left;
		int targetY = top;
		int targetWidth = right - left;
		int targetHeight = bottom - top;
		applyingAnimatedBounds = true;
		applyBounds(width + 12, targetY, targetWidth, targetHeight);
		applyingAnimatedBounds = false;
		boundsAnimation.animateTo(left, top, right - left, bottom - top,
				targetX, targetY, targetWidth, targetHeight);
	}

	boolean isClosing() { return closing; }
	void reopen() {
		if (!closing) return;
		closing = false;
		if (!dockSide.docked()) boundsAnimation.animateTo(left, top, right - left, bottom - top,
				floatingX, floatingY, floatingWidth, floatingHeight);
	}

	boolean contains(double x, double y) {
		int margin = dockSide.docked() ? 0 : 5;
		return embedded && !closing && x >= left - margin && x < right + margin
				&& y >= top - margin && y < bottom + margin;
	}

	boolean isDraggingPanel() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }
	WindowGeometry captureGeometry() {
		if (!dockSide.docked() && !boundsAnimation.active()) rememberFloatingBounds();
		return new WindowGeometry(floatingX, floatingY, floatingWidth, floatingHeight);
	}
	void restoreGeometry(WindowGeometry geometry) {
		if (geometry == null) return;
		floatingX = geometry.x(); floatingY = geometry.y();
		floatingWidth = geometry.width(); floatingHeight = geometry.height();
	}

	void setDockSide(DockSide side) {
		if (dockSide == side) return;
		if (!dockSide.docked() && !boundsAnimation.active()) rememberFloatingBounds();
		DockSide previous = dockSide;
		dockSide = side;
		resizeEdges = 0;
		if (previous.docked() && !side.docked() && floatingWidth > 0 && floatingHeight > 0) {
			boundsAnimation.animateTo(left, top, right - left, bottom - top,
					Math.clamp(floatingX, 0, Math.max(0, width - floatingWidth)),
					Math.clamp(floatingY, 0, Math.max(0, height - floatingHeight)),
					Math.min(floatingWidth, width), Math.min(floatingHeight, height));
		}
	}
	void setDockBounds(int x, int y, int width, int height) {
		if (dockSide.docked() && !closing) boundsAnimation.animateTo(left, top, right - left, bottom - top,
				x, y, width, height);
	}

	@Override public void tick() {
		super.tick();
		if (!closing && mode == LuaGit.Mode.GITHUB && !authBusy && account != null && account.phase() == LuaGit.LoginPhase.PENDING
				&& System.currentTimeMillis() >= nextAuthPoll) pollLogin();
		if (dragging || resizeEdges != 0) return;
		WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(left, top, right - left, bottom - top);
		applyingAnimatedBounds = true;
		applyBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
		applyingAnimatedBounds = false;
		if (closing && !bounds.moving()) previous.closeGitPanel(this);
	}

	@Override
	protected void init() {
		String oldRepository = repository == null ? "" : repository.getValue();
		String oldBranch = branch == null ? "main" : branch.getValue();
		String oldMessage = message == null ? "更新工作区" : message.getValue();
		String oldDescription = description == null ? "" : description.getValue();
		int panelWidth = Math.min(floatingWidth > 0 ? floatingWidth : 740, Math.max(1, width - 28));
		int panelHeight = Math.min(floatingHeight > 0 ? floatingHeight : 470, Math.max(1, height - 22));
		left = embedded && floatingX >= 0 ? Math.clamp(floatingX, 0, width - panelWidth)
				: (width - panelWidth) / 2;
		right = left + panelWidth;
		top = embedded && floatingY >= 0 ? Math.clamp(floatingY, 0, height - panelHeight)
				: (height - panelHeight) / 2;
		bottom = top + panelHeight;
		int fieldX = left + 142;
		int fieldWidth = Math.max(100, right - fieldX - 18);
		repository = addRenderableWidget(new EditBox(font, fieldX, top + 55, fieldWidth, 20,
				Component.literal(LuaUiText.text("GitHub 仓库地址"))));
		repository.setMaxLength(200);
		branch = addRenderableWidget(new EditBox(font, fieldX, top + 87, fieldWidth, 20,
				Component.literal(LuaUiText.text("目标分支"))));
		branch.setMaxLength(240);
		message = addRenderableWidget(new EditBox(font, fieldX, top + 119, fieldWidth, 20,
				Component.literal(LuaUiText.text("提交说明"))));
		message.setMaxLength(240);
		description = addRenderableWidget(new EditBox(font, fieldX, top + 145, fieldWidth, 20, Component.literal(LuaUiText.text("详细说明（可选）"))));
		description.setMaxLength(2000);
		updating = true;
		repository.setValue(oldRepository);
		branch.setValue(oldBranch);
		message.setValue(oldMessage);
		description.setValue(oldDescription);
		updating = false;
		repository.setResponder(value -> changed());
		branch.setResponder(value -> changed());
		message.setResponder(value -> updateButtons());
		saveButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("保存仓库设置")), ignored -> save())
				.bounds(left + 17, bottom - 38, 112, 22).build());
		pushButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("推送已保存文件")), ignored -> push())
				.bounds(right - 251, bottom - 38, 137, 22).build());
		backButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("关闭")), ignored -> onClose())
				.bounds(right - 106, bottom - 38, 88, 22).build());
		localButton = button(LuaUiText.text("本地 Git"), () -> switchMode(LuaGit.Mode.LOCAL));
		githubButton = button("GitHub", () -> switchMode(LuaGit.Mode.GITHUB));
		commitTab = button(LuaUiText.text("提交"), () -> setPage(Page.COMMIT));
		historyTab = button("版本历史", () -> { setPage(Page.HISTORY); if (history == null) refreshHistory(0); });
		settingsTab = button("设置", () -> setPage(Page.SETTINGS));
		refreshButton = button(LuaUiText.text("刷新"), () -> refreshHistory(history == null ? 0 : history.offset()));
		olderButton = button("更早 ›", () -> refreshHistory(history.offset() + 30));
		newerButton = button("‹ 更新", () -> refreshHistory(Math.max(0, history.offset() - 30)));
		restoreButton = button(LuaUiText.text("恢复所选版本"), this::restore);
		previewButton = button(LuaUiText.text("查看代码"), () -> { sourcePreview = !sourcePreview; preparePreview(); updateButtons(); });
		listButton = button("‹ 历史", () -> { detailOpen = false; updateButtons(); });
		copyButton = button("复制代码", () -> { if (detail != null) minecraft.keyboardHandler.setClipboard(detail.source()); });
		String oldId = clientId == null ? "" : clientId.getValue(), oldSearch = repoSearch == null ? "" : repoSearch.getValue();
		String oldName = repoName == null ? "" : repoName.getValue(), oldRepoDescription = repoDescription == null ? "" : repoDescription.getValue();
		clientId = field("OAuth App Client ID", 100, oldId);
		repoSearch = field("筛选本页仓库", 140, oldSearch);
		repoName = field("新仓库名称", 100, oldName);
		repoDescription = field("仓库简介（可选）", 350, oldRepoDescription);
		repoSearch.setResponder(value -> { repoScroll = 0; selectedRepository = ""; updateButtons(); });
		repoName.setResponder(value -> { createConfirmed = false; updateButtons(); });
		repoDescription.setResponder(value -> { createConfirmed = false; updateButtons(); });
		accountButton = button(LuaUiText.text("GitHub 登录"), this::openAccount);
		loginButton = button("使用浏览器登录", this::startLogin);
		loginButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"授权 repo 会允许 App 访问账号授权范围内的公有/私有仓库。\n凭据只保存在服务端内存：请只在可信服务器登录。\n退出服务器会清除凭据；撤销 App 授权请在 GitHub Settings → Applications 操作。")));
		browserButton = button("打开登录页面", () -> openLink("https://github.com/login/device"));
		codeButton = button("复制验证码", () -> { if (account != null) { minecraft.keyboardHandler.setClipboard(account.userCode()); status = "验证码已复制，请粘贴到 GitHub 登录页面"; } });
		logoutButton = button("退出登录 / 取消授权", this::logout);
		configureButton = button("配置 Client ID", () -> { setPage(Page.CONFIGURE_GITHUB); if (account != null) clientId.setValue(account.clientId()); });
		clientIdSaveButton = button("保存 Client ID", this::saveClientId);
		appGuideButton = button("OAuth App 设置", () -> openLink("https://github.com/settings/developers"));
		reposButton = button("选择已有仓库", () -> { setPage(Page.REPOSITORIES); loadRepositories(1); });
		reposRefreshButton = button("刷新仓库", () -> loadRepositories(repositories == null ? 1 : repositories.page()));
		reposNextButton = button("下一页 ›", () -> loadRepositories(repositories.page() + 1));
		reposPreviousButton = button("‹ 上一页", () -> loadRepositories(Math.max(1, repositories.page() - 1)));
		newRepoButton = button("新建仓库", () -> { createConfirmed = false; setPage(Page.CREATE_REPOSITORY); });
		selectRepoButton = button("使用所选仓库", () -> selectRepository(selectedRepository));
		visibilityButton = button(LuaUiText.text("私有仓库 ✓"), () -> { privateRepository = !privateRepository; createConfirmed = false; updateButtons(); });
		createRepoButton = button(LuaUiText.text("创建并使用仓库"), this::createRepository);
		layoutWidgets();
		if (!loading && !loaded) load();
		updateButtons();
	}

	private Button button(String label, Runnable action) {
		return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(0, 0, 70, 20).build());
	}
	private EditBox field(String label, int length, String value) {
		EditBox box = addRenderableWidget(new EditBox(font, 0, 0, 100, 20, Component.literal(label)));
		box.setMaxLength(length); box.setValue(value); return box;
	}
	private boolean connected() { return account != null && account.phase() == LuaGit.LoginPhase.CONNECTED; }
	private void setPage(Page next) {
		if (mode == LuaGit.Mode.GITHUB && (next == Page.COMMIT || next == Page.HISTORY) && !connected()) { openAccount(); return; }
		page = next; notesScroll = 0; confirmed = false; layoutWidgets();
	}
	private void switchMode(LuaGit.Mode next) {
		if (busy || loading || mode == next) return;
		mode = next; history = null; detail = null; selectedSha = ""; detailOpen = false; confirmed = false;
		status = next == LuaGit.Mode.LOCAL ? "本地模式：无需 GitHub，提交只保存在服务器磁盘" : "GitHub 模式：先使用浏览器登录，再选择或新建仓库";
		if (next == LuaGit.Mode.GITHUB) { dirty = true; openAccount(); return; }
		if (page != Page.COMMIT && page != Page.HISTORY && page != Page.SETTINGS) page = Page.COMMIT;
		// Save the preference without overwriting unsaved repository input.
		if (!dirty && (next == LuaGit.Mode.LOCAL || !repository.getValue().isBlank())) save(false);
		else { changed(); if (page == Page.HISTORY && next == LuaGit.Mode.LOCAL) refreshHistory(0); }
		updateButtons();
	}

	private void rememberFloatingBounds() {
		floatingX = left; floatingY = top;
		floatingWidth = right - left; floatingHeight = bottom - top;
	}

	private void applyBounds(int x, int y, int panelWidth, int panelHeight) {
		if (left == x && top == y && right - left == panelWidth && bottom - top == panelHeight) return;
		left = x; top = y; right = x + panelWidth; bottom = y + panelHeight;
		if (repository == null) return;
		layoutWidgets();
		if (!dockSide.docked() && !applyingAnimatedBounds) rememberFloatingBounds();
	}

	private void position(Button button, int x, int y, int w) { button.setX(x); button.setY(y); button.setWidth(Math.max(12, w)); }
	private void layoutWidgets() {
		int w = right - left, fieldX = left + Math.min(114, w / 3), fieldWidth = Math.max(12, right - fieldX - 12);
		repository.setX(fieldX); repository.setY(top + 108); repository.setWidth(fieldWidth);
		branch.setX(fieldX); branch.setY(top + 140); branch.setWidth(fieldWidth);
		message.setX(fieldX); message.setY(top + 108); message.setWidth(fieldWidth);
		description.setX(fieldX); description.setY(top + 140); description.setWidth(fieldWidth);
		if (localButton == null) return;
		position(localButton, left + 12, top + 40, 90);
		position(githubButton, left + 106, top + 40, 90);
		int tabWidth = Math.max(30, (w - 32) / 3);
		position(commitTab, left + 12, top + 66, tabWidth);
		position(historyTab, left + 16 + tabWidth, top + 66, tabWidth);
		position(settingsTab, left + 20 + tabWidth * 2, top + 66, tabWidth);
		position(refreshButton, left + 12, top + 98, 50);
		position(newerButton, left + 66, top + 98, 56);
		position(olderButton, left + 126, top + 98, 56);
		position(previewButton, right - 92, top + 98, 80);
		position(listButton, left + 12, top + 98, 70);
		position(copyButton, right - 178, top + 98, 80);
		int footerWidth = Math.max(50, (w - 32) / 2);
		position(saveButton, left + 12, bottom - 36, footerWidth);
		position(pushButton, left + 12, bottom - 36, footerWidth);
		position(restoreButton, left + 12, bottom - 36, footerWidth);
		position(backButton, right - 26, top + 4, 20);
		backButton.setHeight(20); backButton.visible = false;
		if (accountButton != null) {
			position(accountButton, right - 132, top + 40, 120);
			for (EditBox field : List.of(clientId, repoName, repoDescription)) { field.setX(fieldX); field.setWidth(fieldWidth); }
			clientId.setY(top + 108); repoName.setY(top + 108); repoDescription.setY(top + 140);
			repoSearch.setX(left + 68); repoSearch.setY(top + 108); repoSearch.setWidth(Math.max(12, w - 80));
			position(loginButton, left + 12, top + 138, Math.min(200, w - 24));
			position(browserButton, left + 12, top + 158, Math.min(160, (w - 28) / 2));
			position(codeButton, left + 16 + Math.min(160, (w - 28) / 2), top + 158, Math.min(130, (w - 28) / 2));
			position(logoutButton, left + 12, bottom - 36, footerWidth);
			position(configureButton, right - 132, top + 98, 120);
			position(clientIdSaveButton, left + 12, bottom - 36, footerWidth);
			position(appGuideButton, left + 12, top + 140, 130);
			position(reposButton, left + 12, top + 138, Math.min(160, (w - 28) / 2));
			position(newRepoButton, right - 142, top + 138, 130);
			position(reposRefreshButton, left + 12, top + 138, 88);
			position(reposPreviousButton, left + 104, top + 138, 70);
			position(reposNextButton, left + 178, top + 138, 70);
			position(selectRepoButton, left + 12, bottom - 36, footerWidth);
			position(visibilityButton, fieldX, top + 172, Math.min(160, fieldWidth));
			position(createRepoButton, left + 12, bottom - 36, footerWidth);
			// A narrow dock uses a second row for the new-repository action.
			if (w < 460 && page == Page.REPOSITORIES) position(newRepoButton, left + 12, top + 164, 130);
		}
		updateButtons();
	}

	private void changed() {
		if (updating) return;
		dirty = true; confirmed = false; updateButtons();
	}
	private void openLink(String url) {
		try { Util.getPlatform().openUri(URI.create(url)); }
		catch (RuntimeException error) { status = "无法打开浏览器，请手动访问 GitHub 登录页面"; }
	}
	private void openAccount() {
		if (busy || loading || authBusy) return;
		page = Page.ACCOUNT; notesScroll = 0; layoutWidgets();
		authentication(ScriptManager.githubAccount(), false, false);
	}
	private void authentication(java.util.concurrent.CompletableFuture<LuaGit.Account> request, boolean openBrowser, boolean chooseRepo) {
		authBusy = true; updateButtons();
		request.whenComplete((result, error) -> minecraft.execute(() -> {
			authBusy = false;
			if (error != null) { status = "GitHub 登录失败：" + ScriptManager.message(error); nextAuthPoll = System.currentTimeMillis() + 10000; }
			else {
				applyAccount(result);
				if (mode == LuaGit.Mode.GITHUB && !closing) {
					if (openBrowser && result.phase() == LuaGit.LoginPhase.PENDING) openLink("https://github.com/login/device");
					if (chooseRepo && connected()) { setPage(Page.REPOSITORIES); loadRepositories(1); }
				}
			}
			updateButtons();
		}));
	}
	private void applyAccount(LuaGit.Account result) {
		account = result; status = result.notice();
		nextAuthPoll = System.currentTimeMillis() + Math.max(5, result.pollSeconds()) * 1000L;
		if (clientId != null && !clientId.isFocused()) clientId.setValue(result.clientId());
		updateButtons();
	}
	private void startLogin() {
		if (authBusy || account == null || account.clientId().isBlank()) return;
		status = "正在生成一次性验证码，随后打开浏览器…";
		authentication(ScriptManager.githubLogin(), true, true);
	}
	private void pollLogin() { authentication(ScriptManager.githubPoll(), false, true); }
	private void logout() {
		if (authBusy) return;
		repositories = null; selectedRepository = ""; page = Page.ACCOUNT; layoutWidgets();
		authentication(ScriptManager.githubLogout(), false, false);
	}
	private void saveClientId() {
		if (authBusy) return;
		status = "正在保存公开 Client ID（留空恢复内置应用，不需要密钥）…";
		authentication(ScriptManager.githubClientId(clientId.getValue().strip()), false, false);
	}
	private void loadRepositories(int requestedPage) {
		if (authBusy || !connected()) return;
		authBusy = true; status = "正在读取可写的 GitHub 仓库…"; updateButtons();
		ScriptManager.githubRepositories(requestedPage).whenComplete((result, error) -> minecraft.execute(() -> {
			authBusy = false;
			if (error != null) status = "读取仓库失败：" + ScriptManager.message(error);
			else {
				repositories = result; repoScroll = 0; selectedRepository = "";
				status = "第 " + result.page() + " 页 · 可写仓库 " + result.repositories().size() + " 个；搜索筛选本页，更多仓库请翻页";
			}
			updateButtons();
		}));
	}
	private List<LuaGit.Repository> filteredRepositories() {
		if (repositories == null) return List.of();
		String query = repoSearch.getValue().strip().toLowerCase(java.util.Locale.ROOT);
		return repositories.repositories().stream().filter(repo -> (repo.fullName() + " " + repo.description()).toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
	}
	private void selectRepository(String name) {
		if (authBusy || name.isEmpty() || !connected()) return;
		authBusy = true; status = "正在验证仓库写入权限并保存服务端设置…"; updateButtons();
		ScriptManager.githubSelectRepository(name).whenComplete((repo, error) -> minecraft.execute(() -> {
			authBusy = false;
			if (error != null) status = "选择仓库失败：" + ScriptManager.message(error);
			else {
				updating = true; repository.setValue(repo.url()); branch.setValue(repo.branch()); updating = false; dirty = false;
				history = null; detail = null; selectedSha = ""; detailOpen = false;
				status = "已使用 " + repo.fullName() + " · " + repo.branch() + "，填写简介即可上传（服务端共享仓库设置）";
				if (mode == LuaGit.Mode.GITHUB) setPage(Page.COMMIT);
			}
			updateButtons();
		}));
	}
	private void createRepository() {
		if (authBusy || !connected() || repoName.getValue().isBlank()) return;
		if (!createConfirmed) {
			createConfirmed = true; status = "再次点击确认在 " + account.login() + " 的 GitHub 账号中新建"
					+ (privateRepository ? "私有" : "公开（所有人可读取）") + "仓库，不会覆盖已有仓库"; updateButtons(); return;
		}
		createConfirmed = false; authBusy = true; status = "正在新建 GitHub 仓库…"; updateButtons();
		ScriptManager.githubCreateRepository(repoName.getValue().strip(), repoDescription.getValue(), privateRepository)
				.whenComplete((repo, error) -> minecraft.execute(() -> {
					authBusy = false;
					if (error != null) status = "新建仓库失败：" + ScriptManager.message(error);
					else {
						// Keep the created repository available if saving the shared server configuration fails.
						repositories = new LuaGit.Repositories(List.of(repo), 1, false); repoSearch.setValue("");
						selectedRepository = repo.fullName(); setPage(Page.REPOSITORIES); selectRepository(repo.fullName());
					}
					updateButtons();
				}));
	}

	private void load() {
		loading = true;
		ScriptManager.gitConfig().whenComplete((config, error) -> minecraft.execute(() -> {
			loading = false;
			if (error != null) status = LuaUiText.text("读取失败：") + ScriptManager.message(error);
			else if (!dirty && repository != null) {
				loaded = true; updating = true;
				repository.setValue(config.repository()); branch.setValue(config.branch()); mode = config.mode();
				updating = false;
				status = mode == LuaGit.Mode.LOCAL ? "本地模式：填写简介即可保存版本，无需联网" : "GitHub 模式：填写简介即可提交并上传";
				if (mode == LuaGit.Mode.GITHUB) openAccount(); else refreshHistory(0, false);
			} else status = "已保留你刚输入的设置，请保存后再上传";
			updateButtons();
		}));
	}

	private void save() { save(true); }
	private void save(boolean showStatus) {
		if (busy || loading) return;
		busy = true; confirmed = false;
		if (showStatus) status = "正在保存服务端 Git 设置…";
		updateButtons();
		ScriptManager.saveGitConfig(repository.getValue().strip(), branch.getValue().strip(), mode).whenComplete((ignored, error) ->
				minecraft.execute(() -> {
					busy = false;
					if (error != null) status = LuaUiText.text("保存失败：") + ScriptManager.message(error);
					else {
						dirty = false;
						if (showStatus) status = "设置已保存；GitHub 浏览器登录凭据只保存在服务端内存";
						history = null; detail = null; selectedSha = ""; detailOpen = false;
						refreshHistory(0, false);
					}
					updateButtons();
				}));
	}

	private void push() {
		if (busy || loading || authBusy || (mode == LuaGit.Mode.GITHUB && (!connected() || dirty || repository.getValue().isBlank()))) return;
		if (previous.hasUnsavedDocuments()) { status = "请先保存编辑器所有未保存内容，再提交版本"; return; }
		busy = true; confirmed = false;
		status = mode == LuaGit.Mode.LOCAL ? "正在保存本地版本…" : "正在提交并上传 GitHub…";
		updateButtons();
		ScriptManager.commitGit(mode, message.getValue(), description.getValue()).whenComplete((result, error) -> minecraft.execute(() -> {
			busy = false; status = error == null ? result : "提交/上传失败：" + ScriptManager.message(error);
			refreshHistory(0, false); updateButtons();
		}));
	}

	private void updateButtons() {
		if (localButton == null) return;
		boolean ready = !busy && !loading && !authBusy;
		saveButton.active = ready;
		pushButton.active = ready && !message.getValue().isBlank() && (mode == LuaGit.Mode.LOCAL || connected() && !dirty && !repository.getValue().isBlank());
		pushButton.setMessage(Component.literal(mode == LuaGit.Mode.LOCAL ? "保存本地版本" : "提交并上传"));
		localButton.active = ready && mode != LuaGit.Mode.LOCAL;
		localButton.setMessage(Component.literal(mode == LuaGit.Mode.LOCAL ? "● 本地 Git" : LuaUiText.text("本地 Git")));
		githubButton.setMessage(Component.literal(mode == LuaGit.Mode.GITHUB ? "● GitHub" : "GitHub"));
		githubButton.active = ready && mode != LuaGit.Mode.GITHUB;
		commitTab.active = page != Page.COMMIT; historyTab.active = page != Page.HISTORY; settingsTab.active = page != Page.SETTINGS;
		repository.active = branch.active = message.active = description.active = ready;
		repository.visible = branch.visible = saveButton.visible = page == Page.SETTINGS;
		message.visible = description.visible = pushButton.visible = page == Page.COMMIT;
		boolean showDetail = page == Page.HISTORY && detailOpen && detail != null;
		boolean narrow = right - left < 560;
		refreshButton.visible = newerButton.visible = olderButton.visible = page == Page.HISTORY && !(narrow && showDetail);
		refreshButton.active = ready && (mode == LuaGit.Mode.LOCAL || !dirty);
		newerButton.active = ready && history != null && history.offset() > 0;
		olderButton.active = ready && history != null && history.more();
		listButton.visible = narrow && showDetail;
		previewButton.visible = showDetail;
		copyButton.visible = showDetail && (narrow || right - left >= 750);
		previewButton.setMessage(Component.literal(sourcePreview ? "查看差异" : LuaUiText.text("查看代码")));
		restoreButton.visible = page == Page.HISTORY;
		restoreButton.active = ready && !selectedSha.isEmpty();
		restoreButton.setMessage(Component.literal(confirmed ? "确认恢复（先备份）" : LuaUiText.text("恢复所选版本")));
		for (EditBox field : List.of(repository, branch, message, description)) if (!field.visible && field.isFocused()) {
			field.setFocused(false); if (getFocused() == field) setFocused(null);
		}
		if (accountButton == null) return;
		accountButton.visible = mode == LuaGit.Mode.GITHUB;
		accountButton.active = ready;
		accountButton.setMessage(Component.literal(connected() ? "账号 / 选择仓库" : LuaUiText.text("GitHub 登录")));
		boolean loginPage = page == Page.ACCOUNT, configure = page == Page.CONFIGURE_GITHUB, repos = page == Page.REPOSITORIES, create = page == Page.CREATE_REPOSITORY;
		boolean pending = account != null && account.phase() == LuaGit.LoginPhase.PENDING;
		for (Button button : List.of(loginButton, browserButton, codeButton, logoutButton, configureButton, clientIdSaveButton,
				appGuideButton, reposButton, reposRefreshButton, reposNextButton, reposPreviousButton, newRepoButton, selectRepoButton, visibilityButton, createRepoButton)) {
			button.visible = false; button.active = ready;
		}
		loginButton.visible = loginPage && !pending && !connected(); loginButton.active = ready && account != null && !account.clientId().isBlank();
		browserButton.visible = codeButton.visible = loginPage && pending;
		logoutButton.visible = loginPage && (pending || connected());
		logoutButton.setMessage(Component.literal(pending ? "取消本次登录" : LuaUiText.text("退出登录")));
		configureButton.visible = loginPage && !pending;
		clientId.visible = clientIdSaveButton.visible = appGuideButton.visible = configure;
		repoSearch.visible = repos; repoSearch.active = ready;
		repoName.visible = repoDescription.visible = create; repoName.active = repoDescription.active = ready;
		reposButton.visible = loginPage && connected();
		newRepoButton.visible = (loginPage && connected()) || repos;
		reposRefreshButton.visible = reposNextButton.visible = reposPreviousButton.visible = repos;
		reposNextButton.active = ready && repositories != null && repositories.more();
		reposPreviousButton.active = ready && repositories != null && repositories.page() > 1;
		selectRepoButton.visible = repos; selectRepoButton.active = ready && !selectedRepository.isEmpty();
		visibilityButton.visible = create; visibilityButton.setMessage(Component.literal(privateRepository ? LuaUiText.text("私有仓库 ✓") : "公开仓库（所有人可读）"));
		createRepoButton.visible = create; createRepoButton.active = ready && !repoName.getValue().isBlank();
		createRepoButton.setMessage(Component.literal(createConfirmed ? "确认创建并使用" : LuaUiText.text("创建并使用仓库")));
		for (EditBox field : List.of(clientId, repoSearch, repoName, repoDescription)) if (!field.visible && field.isFocused()) {
			field.setFocused(false); if (getFocused() == field) setFocused(null);
		}
	}

	private void refreshHistory(int offset) { refreshHistory(offset, true); }
	private void refreshHistory(int offset, boolean showStatus) {
		if (busy || loading) return;
		if (mode == LuaGit.Mode.GITHUB && !connected()) { status = "请先通过浏览器登录 GitHub，再选择仓库"; return; }
		if (mode == LuaGit.Mode.GITHUB && dirty) { status = "请先保存仓库设置，再读取 GitHub 历史"; return; }
		busy = true; confirmed = false;
		if (showStatus) status = "正在读取版本历史…";
		updateButtons();
		ScriptManager.gitHistory(mode, offset).whenComplete((result, error) -> minecraft.execute(() -> {
			busy = false;
			if (error != null) { if (showStatus || history == null) status = "读取历史失败：" + ScriptManager.message(error); }
			else {
				history = result; location = result.location(); historyScroll = 0;
				if (showStatus) status = result.notice();
				if (result.commits().stream().noneMatch(commit -> commit.sha().equals(selectedSha))) { selectedSha = ""; detail = null; detailOpen = false; }
			}
			updateButtons();
		}));
	}

	private void select(String sha, String file) {
		if (busy) return;
		busy = true; confirmed = false; selectedSha = sha;
		status = "正在读取版本详情…"; updateButtons();
		ScriptManager.gitDetail(mode, sha, file).whenComplete((result, error) -> minecraft.execute(() -> {
			busy = false;
			if (error != null) status = "读取详情失败：" + ScriptManager.message(error);
			else { detail = result; detailOpen = true; if (file.isEmpty()) fileScroll = 0; preparePreview(); status = "选中文件查看差异；恢复会先自动备份当前工作区"; }
			updateButtons();
		}));
	}
	private void preparePreview() {
		previewScroll = 0; horizontalScroll = 0;
		previewLines = detail == null ? List.of() : List.of((sourcePreview ? detail.source() : detail.diff()).split("\\n", -1));
	}
	private void restore() {
		if (busy || selectedSha.isEmpty()) return;
		if (previous.hasUnsavedDocuments()) { confirmed = false; status = "请先保存编辑器所有未保存内容，再恢复版本"; updateButtons(); return; }
		if (!confirmed) { confirmed = true; status = "再次点击将恢复整个 Lua/IDE 数据包工作区（含删除新文件）；自动备份，不改写历史"; updateButtons(); return; }
		confirmed = false; busy = true; status = "正在创建恢复前备份并恢复文件…"; updateButtons();
		ScriptManager.restoreGit(mode, selectedSha).whenComplete((result, error) -> minecraft.execute(() -> {
			busy = false; status = error == null ? result : "恢复失败：" + ScriptManager.message(error);
			if (error == null) previous.gitWorkspaceRestored();
			refreshHistory(0, false); updateButtons();
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!embedded) graphics.fill(0, 0, width, height, 0xE8090D14);
		graphics.fill(left - 4, top + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(left, top, right, bottom, 0xFE0E141D);
		graphics.outline(left, top, right - left, bottom - top, 0xFFB084F5);
		graphics.fill(left + 1, top + 1, right - 1, top + 36, 0xFF2B2040);
		text(graphics, "◆ Git · 版本管理", left + 14, top + 13, right - 34, 0xFFD6B7FF);
		IdeWindowCloseButton.render(graphics, font, right, top, mouseX, mouseY);
		if (page == Page.HISTORY) renderHistory(graphics, mouseX, mouseY);
		else if (page == Page.ACCOUNT || page == Page.REPOSITORIES || page == Page.CREATE_REPOSITORY || page == Page.CONFIGURE_GITHUB)
			renderGitHub(graphics, mouseX, mouseY);
		else {
			text(graphics, page == Page.COMMIT ? "版本简介" : "GitHub 地址", left + 12, top + 114, repository.getX() - 4, 0xFFD7E6F3);
			text(graphics, page == Page.COMMIT ? "详细说明" : LuaUiText.text("目标分支"), left + 12, top + 146, repository.getX() - 4, 0xFFD7E6F3);
			String notes = page == Page.COMMIT
					? (mode == LuaGit.Mode.LOCAL ? "本地提交只保存在服务器磁盘，不联网。\n" : "GitHub：提交后立即上传，失败时仍保留本地版本。\n")
						+ "范围：已保存的 Lua 脚本与 IDE 数据包（含 NBT/PNG/OGG）。\n不包含客户端资源包；请先保存所有编辑内容。\n简介必填，详细说明可选；Ctrl+Enter 快速提交。"
					: "GitHub：点击右上角“账号 / 选择仓库”使用浏览器登录。\n选择已有仓库或新建仓库后自动填写地址与默认分支。\n仓库设置由服务端共享；账号登录状态按管理员隔离。\n本地模式无需登录；旧版服务器凭据仍可供管理脚本使用。\n仅版本化 mc_lua_ide 子目录，其他仓库文件保持不变。";
			graphics.enableScissor(left + 10, top + 171, right - 10, Math.max(top + 171, bottom - 72));
			int y = top + 174;
			for (var line : font.split(Component.literal(notes), Math.max(12, right - left - 28))) {
				graphics.text(font, line, left + 14, y, 0xFFB5C9DB); y += font.lineHeight + 3;
			}
			if (!location.isEmpty()) {
				y += 8;
				for (var line : font.split(Component.literal(LuaUiText.text("版本库：") + location), Math.max(12, right - left - 28))) {
					graphics.text(font, line, left + 14, y, 0xFF8599B0); y += font.lineHeight + 3;
				}
			}
			graphics.disableScissor();
		}
		graphics.fill(left + 1, bottom - 70, right - 1, bottom - 1, 0xFF101923);
		int statusY = bottom - 64;
		for (var line : font.split(Component.literal(status), Math.max(12, right - left - 28))) {
			if (statusY + font.lineHeight > bottom - 39) break;
			graphics.text(font, line, left + 14, statusY, status.contains(LuaUiText.text("失败")) ? 0xFFFF8585 : confirmed ? 0xFFFFBA7B : 0xFF96CAE8);
			statusY += font.lineHeight + 2;
		}
		if (contains(mouseX, mouseY) && mouseY >= bottom - 70 && mouseY < bottom - 39 && !dragging && resizeEdges == 0)
			graphics.setTooltipForNextFrame(font, Component.literal(status), mouseX, mouseY);
		if (embedded && !dockSide.docked()) ResizeBorderHint.render(graphics,
				left, top, right, bottom, resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY), 0xFFB084F5);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void text(GuiGraphicsExtractor graphics, String value, int x, int y, int end, int color) {
		graphics.text(font, font.plainSubstrByWidth(value, Math.max(0, end - x)), x, y, color);
	}
	private boolean wide() { return right - left >= 560; }
	private int repositoryTop() { return top + (right - left < 460 ? 190 : 164); }
	private void lines(GuiGraphicsExtractor graphics, String value, int y, int color) {
		var lines = font.split(Component.literal(value), Math.max(12, right - left - 28));
		int rows = Math.max(1, visibleRows(y, bodyBottom(), font.lineHeight + 3));
		notesMaximum = Math.max(0, lines.size() - rows); notesScroll = Math.clamp(notesScroll, 0, notesMaximum);
		graphics.enableScissor(left + 10, y, right - 10, Math.max(y, bodyBottom()));
		for (int i = notesScroll; i < Math.min(lines.size(), notesScroll + rows); i++) {
			graphics.text(font, lines.get(i), left + 14, y, color); y += font.lineHeight + 3;
		}
		graphics.disableScissor();
	}
	private void renderGitHub(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.enableScissor(left + 10, top + 94, right - 10, Math.max(top + 94, bodyBottom()));
		if (page == Page.ACCOUNT) {
			text(graphics, connected() ? "已登录：" + account.login() : "第 1 步 · 使用浏览器登录", left + 14, top + 105, right - 142, 0xFFD8BBFF);
			if (account != null && account.phase() == LuaGit.LoginPhase.PENDING) {
				text(graphics, "一次性验证码：" + account.userCode(), left + 14, top + 135, right - 14, 0xFFFFD48B);
				lines(graphics, "在 github.com/login/device 输入验证码并授权。\n剩余 " + Math.max(0, (account.expiresAt() - System.currentTimeMillis()) / 1000) + " 秒；授权成功后自动进入仓库选择。", top + 185, 0xFFB3C8DD);
			} else if (connected()) lines(graphics, "第 2 步 · 选择已有仓库或新建仓库。\n当前仓库：" + (repository.getValue().isBlank() ? "尚未选择" : repository.getValue())
					+ "\n登录状态按管理员隔离；退出服务器后清除凭据。\n退出登录不会撤销 GitHub 网站上的 App 授权。", top + 172, 0xFFB3C8DD);
			else lines(graphics, "已内置公共登录应用，直接点击“使用浏览器登录”。\n浏览器中完成登录，不在游戏内输入密码。\n需要授权 repo：可访问账号授权范围内的公有/私有仓库。\n凭据仅驻留服务端内存，请只在可信服务器上登录。\n自建应用可点击右上角配置；各用户仍登录自己的账号。", top + 172, 0xFFE2BE83);
		} else if (page == Page.CONFIGURE_GITHUB) {
			text(graphics, "Client ID", left + 14, top + 114, clientId.getX() - 4, 0xFFD8BBFF);
			lines(graphics, "可选：使用服务器自己的 OAuth App；默认应用已内置。\n留空保存可恢复内置应用；环境变量配置优先。\nGitHub Settings → Developer settings → OAuth Apps → New OAuth App。\n主页和回调可填项目主页；此登录使用 Device Flow。\n创建后勾选 Enable Device Flow，复制 Client ID 到上方。\n不要输入 Client Secret 或 Token；保存会清除现有登录。\n保存后点击右上角 GitHub 登录。", top + 172, 0xFFB3C8DD);
		} else if (page == Page.CREATE_REPOSITORY) {
			text(graphics, "仓库名称", left + 14, top + 114, repoName.getX() - 4, 0xFFD8BBFF);
			text(graphics, "简介", left + 14, top + 146, repoDescription.getX() - 4, 0xFFD8BBFF);
			lines(graphics, "在 " + (account == null ? "当前账号" : account.login()) + " 下创建；默认私有，不覆盖已有仓库。\n创建后自动选用，可填写版本简介上传代码。", top + 201, privateRepository ? 0xFFB3C8DD : 0xFFFFAF79);
		} else {
			text(graphics, LuaUiText.text("搜索"), left + 14, top + 114, repoSearch.getX() - 4, 0xFFB3C8DD);
			List<LuaGit.Repository> list = filteredRepositories();
			int start = repositoryTop(), rows = visibleRows(start, bodyBottom(), 38);
			graphics.fill(left + 12, start, right - 12, bodyBottom(), 0xFF0A1018);
			if (list.isEmpty()) text(graphics, authBusy ? LuaUiText.text("正在读取…") : "本页无匹配的可写仓库；可翻页或新建", left + 18, start + 9, right - 18, 0xFF9EACC1);
			for (int i = 0; i < rows && repoScroll + i < list.size(); i++) {
				LuaGit.Repository repo = list.get(repoScroll + i); int y = start + i * 38;
				boolean hovered = mouseX >= left + 12 && mouseX < right - 12 && mouseY >= y && mouseY < y + 36;
				if (repo.fullName().equals(selectedRepository) || hovered) graphics.fill(left + 13, y, right - 13, y + 36,
						repo.fullName().equals(selectedRepository) ? 0xFF382951 : 0xFF1B2738);
				text(graphics, repo.fullName() + (repo.privateRepo() ? " · 私有" : " · 公开"), left + 18, y + 5, right - 18, 0xFFD6BBFF);
				text(graphics, repo.branch() + " · " + repo.description(), left + 18, y + 21, right - 18, 0xFF99AEC5);
				if (hovered && !dragging && resizeEdges == 0) graphics.setTooltipForNextFrame(font, Component.literal(repo.fullName() + "\n" + repo.description() + "\n" + repo.url()), mouseX, mouseY);
			}
		}
		graphics.disableScissor();
	}
	private int listRight() { return wide() ? left + Math.min(280, (right - left) * 38 / 100) : right - 12; }
	private int detailLeft() { return wide() ? listRight() + 10 : left + 12; }
	private int bodyBottom() { return bottom - 74; }
	private int fileStart() { return top + 168; }
	private int fileBottom() { return Math.min(bodyBottom(), fileStart() + Math.max(0, Math.min(72, (bodyBottom() - fileStart()) / 3))); }
	private int previewTop() { return fileBottom() + 18; }
	private boolean showingDetail() { return detailOpen && detail != null; }
	private int visibleRows(int start, int end, int rowHeight) { return Math.max(0, (end - start) / rowHeight); }

	private void renderHistory(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (wide() || !showingDetail()) {
			int end = listRight();
			graphics.fill(left + 12, top + 128, end, bodyBottom(), 0xFF0A1018);
			int rows = visibleRows(top + 132, bodyBottom(), 42);
			if (history == null || history.commits().isEmpty()) text(graphics, busy ? LuaUiText.text("正在读取…") : "暂无版本，先保存本地版本或上传", left + 18, top + 140, end - 4, 0xFF9CACC0);
			else for (int i = 0; i < rows && historyScroll + i < history.commits().size(); i++) {
				LuaGit.Commit commit = history.commits().get(historyScroll + i);
				int y = top + 132 + i * 42;
				boolean selected = commit.sha().equals(selectedSha);
				if (selected || mouseX >= left + 12 && mouseX < end && mouseY >= y && mouseY < y + 40)
					graphics.fill(left + 13, y, end - 1, y + 40, selected ? 0xFF382951 : 0xFF1A2738);
				text(graphics, commit.sha().substring(0, 8) + " · " + DATE.format(Instant.ofEpochSecond(commit.time())), left + 18, y + 4, end - 5, 0xFF92BBDD);
				text(graphics, commit.summary(), left + 18, y + 16, end - 5, 0xFFE0D7F5);
				text(graphics, mode == LuaGit.Mode.LOCAL ? "本地版本" : commit.published() ? "✓ 已上传" : "● 尚未上传", left + 18, y + 28, end - 5, commit.published() ? 0xFF85D5B0 : 0xFFE0BA75);
				if (mouseX >= left + 12 && mouseX < end && mouseY >= y && mouseY < y + 40 && !dragging)
					graphics.setTooltipForNextFrame(font, Component.literal(commit.summary() + "\n" + commit.sha()), mouseX, mouseY);
			}
			if (history != null && history.commits().size() > rows) text(graphics, "↕ 滚轮 · 更早/更新切换页", left + 18, bodyBottom() - 10, end - 4, 0xFF748A9F);
		}
		if (!showingDetail()) {
			if (wide()) text(graphics, "← 选择版本查看简介、文件差异与代码", detailLeft() + 5, top + 142, right - 12, 0xFF9DAFC3);
			return;
		}
		int x = detailLeft();
		graphics.fill(x, top + 128, right - 12, bodyBottom(), 0xFF0A1018);
		String summary = detail.message().split("\\n", 2)[0];
		text(graphics, detail.sha().substring(0, 8) + " · " + summary, x + 5, top + 134, right - 17, 0xFFD5B9FC);
		text(graphics, "修改文件 " + detail.totalFiles() + (detail.totalFiles() > detail.files().size() ? "（显示前512个）" : " · 点击文件查看"), x + 5, top + 151, right - 17, 0xFF9FB5C9);
		if (mouseX >= x && mouseX < right - 12 && mouseY >= top + 128 && mouseY < top + 151)
			graphics.setTooltipForNextFrame(font, Component.literal(detail.message()), mouseX, mouseY);
		int fileRows = visibleRows(fileStart(), fileBottom(), 18);
		for (int i = 0; i < fileRows && fileScroll + i < detail.files().size(); i++) {
			LuaGit.Change file = detail.files().get(fileScroll + i); int y = fileStart() + i * 18;
			if (file.path().equals(detail.file())) graphics.fill(x + 1, y, right - 13, y + 17, 0xFF22364A);
			String kind = switch (file.status()) { case "A" -> "新增"; case "D" -> LuaUiText.text("删除"); case "M" -> "修改"; default -> file.status(); };
			text(graphics, kind + "  " + file.path(), x + 5, y + 4, right - 18,
					file.status().equals("D") ? 0xFFFF9898 : file.status().equals("A") ? 0xFF9ADBBB : 0xFF99BFFA);
		}
		if (previewTop() >= bodyBottom() - 12) return;
		text(graphics, (sourcePreview ? "代码" : "差异：绿色新增，红色删除") + (detail.truncated() ? " · 预览截断" : ""), x + 5, fileBottom() + 4, right - 17, 0xFF8EA4BD);
		graphics.enableScissor(x + 3, previewTop(), right - 15, bodyBottom());
		int rows = visibleRows(previewTop(), bodyBottom(), 12);
		for (int i = 0; i < rows && previewScroll + i < previewLines.size(); i++) {
			String line = previewLines.get(previewScroll + i);
			int color = !sourcePreview && line.startsWith("+") ? 0xFF8BD6AC : !sourcePreview && line.startsWith("-") ? 0xFFFF9999 : line.startsWith("@@") ? 0xFF96BDF9 : 0xFFD2DCE7;
			graphics.text(font, line, x + 5 - horizontalScroll, previewTop() + i * 12, color);
		}
		graphics.disableScissor();
	}

	private int resizeEdgesAt(double x, double y) {
		if (dockSide.docked() || x < left - 5 || x > right + 5 || y < top - 5 || y > bottom + 5)
			return 0;
		int edges = 0;
		if (Math.abs(x - left) <= 5) edges |= 1;
		if (Math.abs(x - right) <= 5) edges |= 2;
		if (Math.abs(y - top) <= 5) edges |= 4;
		if (Math.abs(y - bottom) <= 5) edges |= 8;
		return edges;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (embedded && !contains(event.x(), event.y())) return false;
		if (event.button() == 0 && IdeWindowCloseButton.contains(event.x(), event.y(), right, top)) { onClose(); return true; }
		if (embedded && event.button() == 0 && !dockSide.docked()) {
			resizeEdges = resizeEdgesAt(event.x(), event.y());
			if (resizeEdges != 0) {
				boundsAnimation.snapTo(left, top, right - left, bottom - top);
				resizeStartX = event.x(); resizeStartY = event.y();
				resizeLeft = left; resizeTop = top; resizeRight = right; resizeBottom = bottom;
				return true;
			}
		}
		if (embedded && event.button() == 0 && event.y() >= top && event.y() < top + 36) {
			dragging = !dockSide.docked() || !layoutLocked;
			if (dragging) boundsAnimation.snapTo(left, top, right - left, bottom - top);
			dragOffsetX = event.x() - left;
			dragOffsetY = event.y() - top;
			return true;
		}
		if (page == Page.HISTORY && event.button() == 0 && event.y() >= top + 132 && event.y() < bodyBottom()) {
			setFocused(null);
			if ((wide() || !showingDetail()) && event.x() >= left + 12 && event.x() < listRight() && history != null) {
				int row = historyScroll + (int) ((event.y() - top - 132) / 42);
				if (row >= 0 && row < history.commits().size()) select(history.commits().get(row).sha(), "");
				return true;
			}
			if (showingDetail() && event.x() >= detailLeft() && event.x() < right - 12
					&& event.y() >= fileStart() && event.y() < fileBottom()) {
				int row = fileScroll + (int) ((event.y() - fileStart()) / 18);
				if (row >= 0 && row < detail.files().size()) {
					int previousScroll = fileScroll;
					select(selectedSha, detail.files().get(row).path());
					fileScroll = previousScroll;
				}
				return true;
			}
		}
		if (page == Page.REPOSITORIES && event.button() == 0 && event.x() >= left + 12 && event.x() < right - 12
				&& event.y() >= repositoryTop() && event.y() < bodyBottom() && !authBusy) {
			int row = repoScroll + (int) ((event.y() - repositoryTop()) / 38);
			List<LuaGit.Repository> list = filteredRepositories();
			if (row < list.size()) { selectedRepository = list.get(row).fullName(); status = "已选 " + selectedRepository + "，点击“使用所选仓库”"; updateButtons(); }
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (resizeEdges != 0) {
			int dx = (int) Math.round(event.x() - resizeStartX);
			int dy = (int) Math.round(event.y() - resizeStartY);
			int minimumWidth = Math.min(360, Math.max(1, width - 28));
			int minimumHeight = Math.min(300, Math.max(1, height - 22));
			int newLeft = (resizeEdges & 1) != 0 ? Math.clamp(resizeLeft + dx, 0, resizeRight - minimumWidth) : resizeLeft;
			int newRight = (resizeEdges & 2) != 0 ? Math.clamp(resizeRight + dx, newLeft + minimumWidth, width) : resizeRight;
			int newTop = (resizeEdges & 4) != 0 ? Math.clamp(resizeTop + dy, 0, resizeBottom - minimumHeight) : resizeTop;
			int newBottom = (resizeEdges & 8) != 0 ? Math.clamp(resizeBottom + dy, newTop + minimumHeight, height) : resizeBottom;
			applyBounds(newLeft, newTop, newRight - newLeft, newBottom - newTop);
			return true;
		}
		if (!dragging) return super.mouseDragged(event, dragX, dragY);
		if (dockSide.docked()) {
			setDockSide(DockSide.FLOATING);
			dragOffsetX = Math.min(dragOffsetX, Math.max(18, right - left - 18));
			dragOffsetY = 12;
		}
		boundsAnimation.snapTo(left, top, right - left, bottom - top);
		int nextX = Math.clamp((int) Math.round(event.x() - dragOffsetX), 0, width - (right - left));
		int nextY = Math.clamp((int) Math.round(event.y() - dragOffsetY), 0, height - (bottom - top));
		applyBounds(nextX, nextY, right - left, bottom - top);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (resizeEdges != 0) { resizeEdges = 0; return true; }
		if (dragging) { dragging = false; return true; }
		return super.mouseReleased(event);
	}

	@Override public void onClose() {
		if (!embedded) { minecraft.setScreen(previous); return; }
		if (closing) return;
		closing = true;
		int targetX = dockSide == DockSide.LEFT ? -(right - left) - 12
				: dockSide == DockSide.BOTTOM ? left : width + 12;
		int targetY = dockSide == DockSide.BOTTOM ? height + 12 : top;
		boundsAnimation.animateTo(left, top, right - left, bottom - top,
				targetX, targetY, right - left, bottom - top);
	}
	@Override public boolean isPauseScreen() { return false; }

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (contains(mouseX, mouseY) && (page == Page.ACCOUNT || page == Page.CONFIGURE_GITHUB || page == Page.CREATE_REPOSITORY)) {
			notesScroll = Math.clamp(notesScroll + (int) Math.round(-vertical * 2), 0, notesMaximum); return true;
		}
		if (contains(mouseX, mouseY) && page == Page.REPOSITORIES) {
			repoScroll = Math.clamp(repoScroll + (int) Math.round(-vertical * 3), 0,
					Math.max(0, filteredRepositories().size() - Math.max(1, visibleRows(repositoryTop(), bodyBottom(), 38)))); return true;
		}
		if (!contains(mouseX, mouseY) || page != Page.HISTORY) return false;
		int steps = (int) Math.round(-vertical * 3);
		if (mouseY < top + 128 || mouseY >= bodyBottom()) return true;
		if ((wide() || !showingDetail()) && mouseX < listRight() && history != null)
			historyScroll = Math.clamp(historyScroll + steps, 0, Math.max(0, history.commits().size() - Math.max(1, visibleRows(top + 132, bodyBottom(), 42))));
		else if (showingDetail() && mouseY >= fileStart() && mouseY < fileBottom())
			fileScroll = Math.clamp(fileScroll + steps, 0, Math.max(0, detail.files().size() - Math.max(1, visibleRows(fileStart(), fileBottom(), 18))));
		else if (showingDetail() && mouseY >= previewTop()) {
			if (horizontal != 0 || InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LSHIFT)
					|| InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RSHIFT))
				horizontalScroll = Math.clamp(horizontalScroll + (int) Math.round(horizontal != 0 ? -horizontal * 24 : -vertical * 24), 0,
						Math.max(0, previewLines.stream().mapToInt(font::width).max().orElse(0) - (right - detailLeft() - 34)));
			else previewScroll = Math.clamp(previewScroll + steps, 0, Math.max(0, previewLines.size() - Math.max(1, visibleRows(previewTop(), bodyBottom(), 12))));
		}
		return true;
	}
	@Override public boolean keyPressed(KeyEvent event) {
		if (event.hasControlDown() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			if (page == Page.COMMIT && pushButton.active) push();
			return true;
		}
		if (page == Page.REPOSITORIES && event.key() == InputConstants.KEY_RETURN && selectRepoButton.active) { selectRepository(selectedRepository); return true; }
		if (event.key() == InputConstants.KEY_ESCAPE && (page == Page.CONFIGURE_GITHUB || page == Page.CREATE_REPOSITORY)) { openAccount(); return true; }
		if (page == Page.HISTORY && event.key() == InputConstants.KEY_ESCAPE && showingDetail() && !wide()) {
			detailOpen = false; updateButtons(); return true;
		}
		return super.keyPressed(event);
	}
}
