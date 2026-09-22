package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultilineTextFieldAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.StringViewAccessor;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LuaIdeScreen extends Screen {
	private static final int MANUAL_TITLE_HEIGHT = 28;
	private static final int MANUAL_HINT_HEIGHT = 24;
	private static final int MANUAL_RESIZE_MARGIN = 6;
	private static final int MANUAL_MIN_WIDTH = 280;
	private static final int MANUAL_MIN_HEIGHT = 180;
	private static final int RESIZE_LEFT = 1;
	private static final int RESIZE_RIGHT = 2;
	private static final int RESIZE_TOP = 4;
	private static final int RESIZE_BOTTOM = 8;
	private static final int SEARCH_HEIGHT = 30;
	private static final int OUTPUT_TITLE_HEIGHT = 24;
	private static final int OUTPUT_RESIZE_MARGIN = 6;
	private static final int OUTPUT_MIN_WIDTH = 240;
	private static final int OUTPUT_MIN_HEIGHT = 100;
	private static final int EDITOR_OUTER_X = 12;
	private static final int LINE_NUMBER_GUTTER_WIDTH = 56;
	private static final int EDITOR_X = EDITOR_OUTER_X + LINE_NUMBER_GUTTER_WIDTH;
	private static final int WORKSPACE_TOP = 68;
	private static final int WORKSPACE_BOTTOM_MARGIN = 30;
	private static final int DOCK_GAP = 5;
	private static final int DOCK_DIVIDER_HIT = 5;
	private static final int DOCK_TAB_HEIGHT = 22;
	private static final int MIN_EDITOR_WIDTH = 246;
	private static final int MIN_EDITOR_HEIGHT = 82;
	private static final int MIN_DOCK_SIDE_WIDTH = 220;
	private static final int MIN_DOCK_BOTTOM_HEIGHT = 96;
	private static final int SUGGESTION_ROW_HEIGHT = 13;
	private static final int SUGGESTION_MAX_VISIBLE_ROWS = 10;
	private static final List<String> BASE_COMPLETIONS = buildCompletions();
	private static final Path WORKSPACE_LAYOUT_FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("mc_lua_ide").resolve("ide-layout.properties");
	private static WorkspaceLayout rememberedWorkspaceLayout = loadWorkspaceLayout();
	private static float rememberedInterfaceScale = 1.0F;

	private static List<String> buildCompletions() {
		List<String> completions = new ArrayList<>(List.of(
			"and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
			"if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
			"assert", "error", "ipairs", "next", "pairs", "pcall", "print", "select", "tonumber", "tostring", "type", "xpcall",
			"math.abs", "math.ceil", "math.floor", "math.max", "math.min", "math.random", "math.sqrt",
			"string.find", "string.format", "string.gsub", "string.len", "string.lower", "string.sub", "string.upper",
			"table.concat", "table.insert", "table.remove", "table.sort"));
		return List.copyOf(completions);
	}

	private static List<String> completions() {
		List<String> completions = new ArrayList<>(BASE_COMPLETIONS);
		completions.addAll(LuaApiDocumentation.symbols());
		return completions;
	}

	private final Screen previousScreen;
	private final String scriptName;
	private final MarkdownManualDocument.Document manualDocument = MarkdownManualDocument.load();
	private final ManualTextIndex manualTextIndex = buildManualTextIndex(manualDocument.lines());
	private MultiLineEditBox editor;
	private Button runButton;
	private Button runMenuButton;
	private ScriptManager.ScriptExecution runningExecution;
	private ScriptManager.ScriptExecution outputExecution;
	private ScriptManager.ExecutionResult observedExecutionResult;
	private long observedOutputLineCount;
	private boolean outputVisible;
	private float outputProgress;
	private final List<String> outputLines = new ArrayList<>();
	private int outputWindowX = -1;
	private int outputWindowY = -1;
	private int outputWindowWidth = -1;
	private int outputWindowHeight = -1;
	private boolean outputDragging;
	private int outputResizeEdges;
	private double outputDragOffsetX;
	private double outputDragOffsetY;
	private double outputResizeStartMouseX;
	private double outputResizeStartMouseY;
	private int outputResizeStartX;
	private int outputResizeStartY;
	private int outputResizeStartWidth;
	private int outputResizeStartHeight;
	private DockSide outputDock = DockSide.FLOATING;
	private int outputFloatingX = -1;
	private int outputFloatingY = -1;
	private int outputFloatingWidth = -1;
	private int outputFloatingHeight = -1;
	private int outputScrollLines;
	private final List<Button> topMenuButtons = new ArrayList<>();
	private final List<Button> fileMenuItems = new ArrayList<>();
	private final List<Button> fontMenuItems = new ArrayList<>();
	private final List<Button> interfaceScaleMenuItems = new ArrayList<>();
	private final List<Button> themeMenuItems = new ArrayList<>();
	private final List<Button> windowMenuItems = new ArrayList<>();
	private final List<Button> viewMenuItems = new ArrayList<>();
	private Button layoutLockButton;
	private EditBox scaleInput;
	private Button scaleApplyButton;
	private ScaleTarget scaleTarget = ScaleTarget.FONT;
	private final Deque<String> undoHistory = new ArrayDeque<>();
	private final Deque<String> redoHistory = new ArrayDeque<>();
	private String lastSnapshot = "";
	private String lastSavedSource = "";
	private String cachedSource = "";
	private boolean sourceLoaded;
	private boolean sourceLoading;
	private boolean internalTextChange;
	private LuaSyntaxHighlighter.Theme theme = LuaSyntaxHighlighter.Theme.DARK;
	private float codeScale = 1.0F;
	private float interfaceScale = rememberedInterfaceScale;
	private Float pendingInterfaceScale;
	private int pendingInterfacePercent = 100;
	private int surfaceWidth = -1;
	private int surfaceHeight = -1;
	private boolean logicalDimensionsApplied;
	private boolean manualOpen;
	private float manualProgress;
	private int manualScroll;
	private int manualWindowX = -1;
	private int manualWindowY = 18;
	private int manualWindowWidth = -1;
	private int manualWindowHeight = -1;
	private int manualContentHeight;
	private boolean manualDragging;
	private int manualResizeEdges;
	private double manualDragOffsetX;
	private double manualDragOffsetY;
	private double manualResizeStartMouseX;
	private double manualResizeStartMouseY;
	private int manualResizeStartX;
	private int manualResizeStartY;
	private int manualResizeStartWidth;
	private int manualResizeStartHeight;
	private DockSide manualDock = DockSide.FLOATING;
	private int manualFloatingX = -1;
	private int manualFloatingY = 18;
	private int manualFloatingWidth = -1;
	private int manualFloatingHeight = -1;
	private final List<ManualCopyTarget> manualCopyTargets = new ArrayList<>();
	private final List<ManualTextSpan> manualTextSpans = new ArrayList<>();
	private LuaInsertOverlay insertOverlay;
	private LuaApiDocsOverlay apiDocsOverlay;
	private LuaFileOverlay fileOverlay;
	private LuaComponentEditorOverlay componentEditorOverlay;
	private LuaTextJsonOverlay textJsonOverlay;
	private ComponentInsertionTarget componentInsertionTarget;
	private EditBox searchBox;
	private boolean manualOnTop = true;
	private boolean searchOpen;
	private float searchProgress;
	private String searchQuery = "";
	private final List<Integer> searchMatches = new ArrayList<>();
	private int selectedSearchMatch = -1;
	private boolean manualSelecting;
	private int manualSelectionAnchor = -1;
	private int manualSelectionCaret = -1;
	private String activeMenu = "";
	private String activeSubmenu = "";
	private boolean layoutLocked;
	private int leftDockWidth = -1;
	private int rightDockWidth = -1;
	private int bottomDockHeight = -1;
	private float animatedLeftDockExtent = -1.0F;
	private float animatedRightDockExtent = -1.0F;
	private float animatedBottomDockExtent = -1.0F;
	private int targetLeftDockExtent;
	private int targetRightDockExtent;
	private int targetBottomDockExtent;
	private ToolWindow activeLeftDock;
	private ToolWindow activeRightDock;
	private ToolWindow activeBottomDock;
	private float animatedLeftTabIndex = -1.0F;
	private float animatedRightTabIndex = -1.0F;
	private float animatedBottomTabIndex = -1.0F;
	private float leftDockReveal = 1.0F;
	private float rightDockReveal = 1.0F;
	private float bottomDockReveal = 1.0F;
	private float dockPreviewPulse;
	private DockSide activeDockResize = DockSide.FLOATING;
	private boolean workspaceLayoutRestored;
	private boolean workspaceLayoutSavePending;
	private long workspaceLayoutSaveDueNanos;
	private final WindowBoundsAnimation manualBoundsAnimation = new WindowBoundsAnimation();
	private final WindowBoundsAnimation outputBoundsAnimation = new WindowBoundsAnimation();
	private List<String> suggestions = List.of();
	private int selectedSuggestion;
	private int completionStart;
	private int completionEnd;
	private double animatedSuggestionSelection;
	private double animatedSuggestionScroll;
	private double targetSuggestionScroll;
	private long suggestionAnimationNanos;
	private boolean suggestionAnimationReady;
	private final AtomicInteger validationRevision = new AtomicInteger();
	private List<LuaSyntaxHighlighter.ErrorRange> editorErrorRanges = List.of();
	private Set<Integer> errorLineNumbers = Set.of();
	private String syntaxMessage = "正在检查语法…";
	private String validationDetail = "正在检查 Lua 语法和 MC API 参数…";
	private int syntaxColor = 0xFFFFFF55;
	private String status = "Tab 补全 · Ctrl+F 搜索 · Ctrl+F1 API 文档 · Ctrl+S 保存 · Ctrl+Enter 运行";

	private enum ToolWindow {
		MANUAL,
		INSERT,
		API_DOCS,
		COMPONENT_EDITOR,
		TEXT_JSON,
		FILES,
		OUTPUT
	}

	private enum ScaleTarget {
		FONT,
		INTERFACE
	}

	private record ComponentInsertionTarget(String source, int start, int end) {
	}

	private record WorkspaceLayout(boolean locked, int leftWidth, int rightWidth, int bottomHeight,
			DockSide manual, DockSide insert, DockSide apiDocs, DockSide componentEditor, DockSide textJson,
			DockSide files, DockSide output,
			ToolWindow activeLeft, ToolWindow activeRight, ToolWindow activeBottom,
			boolean manualVisible, boolean insertVisible, boolean apiDocsVisible, boolean componentEditorVisible, boolean textJsonVisible,
			boolean filesVisible, boolean outputVisible) {
		private static WorkspaceLayout defaults() {
			return new WorkspaceLayout(false, -1, -1, -1,
					DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING,
					DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, null, null, null,
					false, false, false, false, false, false, false);
		}
	}

	private static WorkspaceLayout loadWorkspaceLayout() {
		WorkspaceLayout defaults = WorkspaceLayout.defaults();
		if (!Files.isRegularFile(WORKSPACE_LAYOUT_FILE)) return defaults;
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(WORKSPACE_LAYOUT_FILE, StandardCharsets.UTF_8)) {
			properties.load(reader);
			return new WorkspaceLayout(
					Boolean.parseBoolean(properties.getProperty("locked", "false")),
					parseLayoutInt(properties, "leftWidth", defaults.leftWidth()),
					parseLayoutInt(properties, "rightWidth", defaults.rightWidth()),
					parseLayoutInt(properties, "bottomHeight", defaults.bottomHeight()),
					parseDockSide(properties, "manual", defaults.manual()),
					parseDockSide(properties, "insert", defaults.insert()),
					parseDockSide(properties, "apiDocs", defaults.apiDocs()),
					parseDockSide(properties, "componentEditor", defaults.componentEditor()),
					parseDockSide(properties, "textJson", defaults.textJson()),
					parseDockSide(properties, "files", defaults.files()),
					parseDockSide(properties, "output", defaults.output()),
					parseToolWindow(properties, "activeLeft"),
					parseToolWindow(properties, "activeRight"),
					parseToolWindow(properties, "activeBottom"),
					Boolean.parseBoolean(properties.getProperty("manualVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("insertVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("apiDocsVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("componentEditorVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("textJsonVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("filesVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("outputVisible", "false")));
		} catch (Exception error) {
			McLuaIdeMod.LOGGER.warn("Could not load IDE workspace layout from {}; defaults will be used",
					WORKSPACE_LAYOUT_FILE, error);
			return defaults;
		}
	}

	private static int parseLayoutInt(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static DockSide parseDockSide(Properties properties, String key, DockSide fallback) {
		try {
			return DockSide.valueOf(properties.getProperty(key, fallback.name()));
		} catch (IllegalArgumentException ignored) {
			return fallback;
		}
	}

	private static ToolWindow parseToolWindow(Properties properties, String key) {
		String value = properties.getProperty(key, "").trim();
		if (value.isEmpty()) return null;
		try {
			return ToolWindow.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static synchronized void saveWorkspaceLayout(WorkspaceLayout layout) {
		Properties properties = new Properties();
		properties.setProperty("locked", Boolean.toString(layout.locked()));
		properties.setProperty("leftWidth", Integer.toString(layout.leftWidth()));
		properties.setProperty("rightWidth", Integer.toString(layout.rightWidth()));
		properties.setProperty("bottomHeight", Integer.toString(layout.bottomHeight()));
		properties.setProperty("manual", layout.manual().name());
		properties.setProperty("insert", layout.insert().name());
		properties.setProperty("apiDocs", layout.apiDocs().name());
		properties.setProperty("componentEditor", layout.componentEditor().name());
		properties.setProperty("textJson", layout.textJson().name());
		properties.setProperty("files", layout.files().name());
		properties.setProperty("output", layout.output().name());
		properties.setProperty("activeLeft", layout.activeLeft() == null ? "" : layout.activeLeft().name());
		properties.setProperty("activeRight", layout.activeRight() == null ? "" : layout.activeRight().name());
		properties.setProperty("activeBottom", layout.activeBottom() == null ? "" : layout.activeBottom().name());
		properties.setProperty("manualVisible", Boolean.toString(layout.manualVisible()));
		properties.setProperty("insertVisible", Boolean.toString(layout.insertVisible()));
		properties.setProperty("apiDocsVisible", Boolean.toString(layout.apiDocsVisible()));
		properties.setProperty("componentEditorVisible", Boolean.toString(layout.componentEditorVisible()));
		properties.setProperty("textJsonVisible", Boolean.toString(layout.textJsonVisible()));
		properties.setProperty("filesVisible", Boolean.toString(layout.filesVisible()));
		properties.setProperty("outputVisible", Boolean.toString(layout.outputVisible()));
		try {
			Files.createDirectories(WORKSPACE_LAYOUT_FILE.getParent());
			Path temporary = WORKSPACE_LAYOUT_FILE.resolveSibling(WORKSPACE_LAYOUT_FILE.getFileName() + ".tmp");
			try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				properties.store(writer, "MC Lua IDE client workspace layout");
			}
			try {
				Files.move(temporary, WORKSPACE_LAYOUT_FILE, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, WORKSPACE_LAYOUT_FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception error) {
			McLuaIdeMod.LOGGER.warn("Could not save IDE workspace layout to {}", WORKSPACE_LAYOUT_FILE, error);
		}
	}

	public LuaIdeScreen(Screen previousScreen, String scriptName) {
		super(Component.literal(scriptName + ".lua — MC Lua IDE"));
		this.previousScreen = previousScreen;
		this.scriptName = scriptName;
	}

	@Override
	protected void init() {
		prepareLogicalDimensions();
		ensureManualGeometry();
		ensureOutputGeometry();
		editor = MultiLineEditBox.builder()
				.setX(EDITOR_X)
				.setY(68)
				.setShowBackground(false)
				.setShowDecorations(false)
				.setPlaceholder(Component.translatable("screen.mc_lua_ide.code_hint"))
				.build(font, width - EDITOR_X - 12, normalEditorHeight(), Component.translatable("screen.mc_lua_ide.editor"));
		editor.setCharacterLimit(100_000);
		LuaSyntaxHighlighter.register(editor);
		LuaSyntaxHighlighter.setTheme(editor, theme);
		LuaSyntaxHighlighter.setScale(editor, codeScale);
		editor.setValueListener(value -> {
			cachedSource = value;
			recordHistory(value);
			refreshSuggestions();
			scheduleValidation(value);
			if (searchOpen) refreshSearchMatches(false);
		});
		addRenderableWidget(editor);
		searchBox = new EditBox(font, 0, 0, 120, 20, Component.literal("代码搜索"));
		searchBox.setMaxLength(500);
		searchBox.setHint(Component.literal("输入要查找的代码…"));
		searchBox.setResponder(value -> {
			searchQuery = value;
			refreshSearchMatches(true);
		});
		searchBox.setValue(searchQuery);
		searchBox.visible = searchOpen;
		searchBox.active = searchOpen;
		addWidget(searchBox);
		if (insertOverlay == null) {
			insertOverlay = new LuaInsertOverlay(minecraft, font, this::insertCatalogEntry);
		}
		insertOverlay.resize(width, height);
		if (apiDocsOverlay == null) {
			apiDocsOverlay = new LuaApiDocsOverlay(minecraft, font, this::insertApiTemplate);
		}
		apiDocsOverlay.resize(width, height);
		if (componentEditorOverlay == null) {
			componentEditorOverlay = new LuaComponentEditorOverlay(minecraft, font,
					this::insertComponentPatch, message -> status = message);
		}
		componentEditorOverlay.resize(width, height);
		if (textJsonOverlay == null) {
			textJsonOverlay = new LuaTextJsonOverlay(minecraft, font,
					this::insertTextJson, message -> status = message);
		}
		textJsonOverlay.resize(width, height);
		if (fileOverlay == null) {
			fileOverlay = new LuaFileOverlay(minecraft, font, scriptName, this::openScript,
					message -> status = message);
		}
		fileOverlay.resize(width, height);
		restoreWorkspaceLayout();
		applyDockLayout();
		editor.active = sourceLoaded && (!fileOverlay.isOpen() || fileOverlay.dockSide().docked());
		if (sourceLoaded) {
			internalTextChange = true;
			editor.setValue(cachedSource);
			internalTextChange = false;
			lastSnapshot = cachedSource;
			scheduleValidation(cachedSource);
		} else if (!sourceLoading) {
			sourceLoading = true;
			status = "正在从服务器读取 " + scriptName + ".lua…";
			ScriptManager.read(scriptName).whenComplete((source, failure) -> minecraft.execute(() -> {
				sourceLoading = false;
				if (failure != null) {
					status = "加载失败：" + ScriptManager.message(failure);
					return;
				}
				sourceLoaded = true;
				cachedSource = source;
				lastSavedSource = source;
				lastSnapshot = source;
				if (minecraft.screen != this || editor == null) return;
				internalTextChange = true;
				try {
					editor.setValue(source);
				} finally {
					internalTextChange = false;
				}
				scheduleValidation(source);
				status = "已从服务器读取 " + scriptName + ".lua";
				syncOverlayState();
			}));
		}

		buildMenuBar();
		syncOverlayState();
		restoreBackgroundRun();
		if (!insertOverlay.isOpen() && !apiDocsOverlay.isOpen() && !fileOverlay.isOpen()) setInitialFocus(editor);
	}

	@Override
	protected void repositionElements() {
		// Minecraft supplies physical GUI dimensions here. The IDE itself is laid out in
		// a scale-independent logical coordinate system so rendering and input stay aligned.
		surfaceWidth = Math.max(1, width);
		surfaceHeight = Math.max(1, height);
		logicalDimensionsApplied = true;
		applyLogicalDimensions();
		rebuildWidgets();
	}

	private void prepareLogicalDimensions() {
		if (!logicalDimensionsApplied) {
			surfaceWidth = Math.max(1, width);
			surfaceHeight = Math.max(1, height);
			logicalDimensionsApplied = true;
		}
		applyLogicalDimensions();
	}

	private void applyLogicalDimensions() {
		width = Math.max(1, Math.round(surfaceWidth / interfaceScale));
		height = Math.max(1, Math.round(surfaceHeight / interfaceScale));
	}

	private int normalEditorHeight() {
		return Math.max(70, height - WORKSPACE_TOP - WORKSPACE_BOTTOM_MARGIN);
	}

	private void buildMenuBar() {
		topMenuButtons.clear();
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal("文件"), button -> toggleMenu("file"))
				.bounds(8, 8, 58, 20).build()));
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal("窗口"), button -> toggleMenu("window"))
				.bounds(68, 8, 58, 20).build()));
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal("搜索"), button -> openSearchFromSelection())
				.bounds(128, 8, 58, 20).build()));
		runButton = addRenderableWidget(Button.builder(runControlLabel(isScriptRunning()), button -> toggleRun())
				.bounds(188, 8, 86, 20).build());
		topMenuButtons.add(runButton);

		fileMenuItems.clear();
		fileMenuItems.add(menuItem("目录", 8, 30, 150, this::toggleFileOverlay));
		fileMenuItems.add(menuItem("保存", 8, 52, 150, this::saveScript));
		runMenuButton = menuItem("运行", 8, 74, 150, this::toggleRun);
		fileMenuItems.add(runMenuButton);
		updateRunControls();

		fontMenuItems.clear();
		addScalePresets(fontMenuItems, ScaleTarget.FONT);

		interfaceScaleMenuItems.clear();
		addScalePresets(interfaceScaleMenuItems, ScaleTarget.INTERFACE);

		themeMenuItems.clear();
		themeMenuItems.add(menuItem("深色", 240, 30, 136, () -> setTheme(LuaSyntaxHighlighter.Theme.DARK, "深色")));
		themeMenuItems.add(menuItem("浅色", 240, 52, 136, () -> setTheme(LuaSyntaxHighlighter.Theme.LIGHT, "浅色")));
		themeMenuItems.add(menuItem("高对比度", 240, 74, 136, () -> setTheme(LuaSyntaxHighlighter.Theme.HIGH_CONTRAST, "高对比度")));

		windowMenuItems.clear();
		windowMenuItems.add(submenuItem("视图  ▶", 68, 30, 170, "view"));
		windowMenuItems.add(submenuItem("字号  ▶", 68, 52, 170, "font"));
		windowMenuItems.add(submenuItem("配色  ▶", 68, 74, 170, "theme"));
		windowMenuItems.add(submenuItem("界面缩放  ▶", 68, 96, 170, "interface_scale"));
		layoutLockButton = menuItem(layoutLockLabel(), 68, 118, 170, this::toggleLayoutLock);
		windowMenuItems.add(layoutLockButton);

		viewMenuItems.clear();
		viewMenuItems.add(menuItem("使用手册", 240, 30, 150, this::toggleManual));
		viewMenuItems.add(menuItem("插入", 240, 52, 150, this::toggleInsertOverlay));
		viewMenuItems.add(menuItem("API 文档", 240, 74, 150, this::toggleApiDocs));
		viewMenuItems.add(menuItem("数据组件编辑器", 240, 96, 150, this::toggleComponentEditor));
		viewMenuItems.add(menuItem("文本 JSON 编辑器", 240, 118, 150, this::toggleTextJsonEditor));
		viewMenuItems.add(menuItem("运行日志", 240, 140, 150, this::toggleOutputConsole));

		scaleInput = new EditBox(font, 240, 164, 84, 20, Component.literal("缩放百分比"));
		scaleInput.setMaxLength(4);
		scaleInput.visible = false;
		scaleInput.active = false;
		addRenderableWidget(scaleInput);
		scaleApplyButton = Button.builder(Component.literal("应用"), ignored -> applyCustomScale())
				.bounds(326, 164, 50, 20).build();
		scaleApplyButton.visible = false;
		addRenderableWidget(scaleApplyButton);
		hideMenus();
	}

	private boolean isScriptRunning() {
		return runningExecution != null && runningExecution.isRunning();
	}

	private Component runControlLabel(boolean running) {
		return Component.literal(running ? "■ 停止" : "▶ 运行")
				.withColor(running ? 0xFF7777 : 0x72E69A);
	}

	private void updateRunControls() {
		boolean running = isScriptRunning();
		if (runButton != null) runButton.setMessage(runControlLabel(running));
		if (runMenuButton != null) {
			runMenuButton.setMessage(Component.literal(running ? "停止" : "运行")
					.withColor(running ? 0xFF7777 : 0x72E69A));
		}
	}

	private void addScalePresets(List<Button> target, ScaleTarget type) {
		int[] percentages = {10, 50, 100, 150, 200, 300};
		for (int index = 0; index < percentages.length; index++) {
			int percent = percentages[index];
			target.add(menuItem(percent + "%", 240, 30 + index * 22, 136,
					() -> applyScale(type, percent)));
		}
	}

	private Button menuItem(String label, int x, int y, int itemWidth, Runnable action) {
		Button button = Button.builder(Component.literal(label), ignored -> {
			action.run();
			hideMenus();
		}).bounds(x, y, itemWidth, 20).build();
		addRenderableWidget(button);
		return button;
	}

	private Button submenuItem(String label, int x, int y, int itemWidth, String submenu) {
		Button button = Button.builder(Component.literal(label), ignored -> showSubmenu(submenu))
				.bounds(x, y, itemWidth, 20).build();
		addRenderableWidget(button);
		return button;
	}

	private void toggleMenu(String menu) {
		boolean opening = !activeMenu.equals(menu);
		hideMenus();
		if (!opening) {
			return;
		}
		activeMenu = menu;
		List<Button> items = switch (menu) {
			case "file" -> fileMenuItems;
			case "window" -> windowMenuItems;
			default -> List.of();
		};
		items.forEach(item -> item.visible = true);
		editor.active = false;
	}

	private void showSubmenu(String submenu) {
		boolean opening = !activeSubmenu.equals(submenu);
		activeSubmenu = opening ? submenu : "";
		fontMenuItems.forEach(item -> item.visible = false);
		interfaceScaleMenuItems.forEach(item -> item.visible = false);
		themeMenuItems.forEach(item -> item.visible = false);
		viewMenuItems.forEach(item -> item.visible = false);
		setScaleEditorVisible(false);
		if (!opening) return;
		List<Button> items = switch (submenu) {
			case "font" -> fontMenuItems;
			case "interface_scale" -> interfaceScaleMenuItems;
			case "theme" -> themeMenuItems;
			case "view" -> viewMenuItems;
			default -> List.of();
		};
		items.forEach(item -> item.visible = true);
		if (submenu.equals("font")) showScaleEditor(ScaleTarget.FONT);
		if (submenu.equals("interface_scale")) showScaleEditor(ScaleTarget.INTERFACE);
	}

	private void hideMenus() {
		activeMenu = "";
		activeSubmenu = "";
		fileMenuItems.forEach(item -> item.visible = false);
		fontMenuItems.forEach(item -> item.visible = false);
		interfaceScaleMenuItems.forEach(item -> item.visible = false);
		themeMenuItems.forEach(item -> item.visible = false);
		windowMenuItems.forEach(item -> item.visible = false);
		viewMenuItems.forEach(item -> item.visible = false);
		setScaleEditorVisible(false);
		syncOverlayState();
	}

	private void showScaleEditor(ScaleTarget target) {
		scaleTarget = target;
		int percent = Math.round((target == ScaleTarget.FONT ? codeScale : interfaceScale) * 100.0F);
		scaleInput.setValue(Integer.toString(percent));
		setScaleEditorVisible(true);
	}

	private void setScaleEditorVisible(boolean visible) {
		if (scaleInput != null) {
			scaleInput.visible = visible;
			scaleInput.active = visible;
			if (!visible && getFocused() == scaleInput) setFocused(null);
		}
		if (scaleApplyButton != null) scaleApplyButton.visible = visible;
	}

	private void applyCustomScale() {
		if (scaleInput == null) return;
		try {
			int percent = Integer.parseInt(scaleInput.getValue().trim().replace("%", ""));
			if (percent < 10 || percent > 300) {
				status = "缩放范围必须是 10%–300%";
				return;
			}
			applyScale(scaleTarget, percent);
			hideMenus();
		} catch (NumberFormatException exception) {
			status = "请输入 10–300 之间的整数百分比";
		}
	}

	private void applyScale(ScaleTarget target, int percent) {
		float scale = Math.max(0.10F, Math.min(3.0F, percent / 100.0F));
		if (target == ScaleTarget.FONT) {
			setFontScale(scale, percent + "%");
		} else {
			setInterfaceScale(scale, percent);
		}
	}

	private String layoutLockLabel() {
		return (layoutLocked ? "✓ " : "□ ") + "固定当前布局";
	}

	private void toggleLayoutLock() {
		layoutLocked = !layoutLocked;
		if (layoutLockButton != null) layoutLockButton.setMessage(Component.literal(layoutLockLabel()));
		setOverlayLayoutLocked();
		hideMenus();
		status = layoutLocked
				? "已固定当前吸附布局 · 浮动窗口仍可自由移动"
				: "已解除布局固定 · 可拖动吸附窗口或分隔线";
	}

	private void setFontScale(float scale, String label) {
		codeScale = Math.max(0.10F, Math.min(3.0F, scale));
		LuaSyntaxHighlighter.setScale(editor, codeScale);
		updateEditorFlowWidth();
		editor.refreshScrollAmount();
		((MultiLineEditBoxAccessor) editor).mcLuaIde$scrollToCursor();
		status = "字号已切换为：" + label;
	}

	private void setInterfaceScale(float scale, int percent) {
		pendingInterfaceScale = Math.max(0.10F, Math.min(3.0F, scale));
		pendingInterfacePercent = percent;
		status = "正在把整个 IDE 界面缩放为 " + percent + "%…";
	}

	private void setTheme(LuaSyntaxHighlighter.Theme newTheme, String label) {
		theme = newTheme;
		LuaSyntaxHighlighter.setTheme(editor, newTheme);
		status = "配色已切换为：" + label;
	}

	private boolean revealDockedTool(ToolWindow tool) {
		DockSide side = toolDock(tool);
		if (!toolOpen(tool) || !side.docked() || activeToolAt(side) == tool) return false;
		setSelectedDockTool(side, tool);
		hideMenus();
		syncOverlayState();
		status = "已切换到" + dockSideLabel(side) + "标签：“" + toolWindowLabel(tool) + "”";
		return true;
	}

	private void toggleManual() {
		if (revealDockedTool(ToolWindow.MANUAL)) return;
		if (manualOpen && !manualDock.docked() && !manualOnTop && insertOverlay != null
				&& insertOverlay.isOpen() && !insertOverlay.dockSide().docked()) {
			manualOnTop = true;
			hideMenus();
			syncOverlayState();
			status = "使用手册已切换到前面 · 再次点击可关闭";
			return;
		}
		boolean opening = !manualOpen;
		manualOpen = opening;
		if (opening) {
			manualOnTop = true;
			manualScroll = 0;
			activateDockedTool(ToolWindow.MANUAL);
		}
		hideMenus();
		syncOverlayState();
	}

	private void toggleInsertOverlay() {
		if (insertOverlay == null) return;
		componentInsertionTarget = null;
		LuaCursorContext.Argument argument = currentArgumentContext();
		if (revealDockedTool(ToolWindow.INSERT)) {
			selectArgumentRange(argument);
			insertOverlay.open(argument == null ? null : argument.kind(),
					argument == null ? "" : argument.query());
			insertOverlay.requestKeyboardFocus();
			status = argument == null
					? "插入器已显示 · 输入文字搜索"
					: "已识别 " + argument.symbol() + " 的参数" + (argument.index() + 1)
							+ " · 自动切换到" + argument.kind().label();
			return;
		}
		if (insertOverlay.isOpen() && !insertOverlay.dockSide().docked()
				&& manualOpen && !manualDock.docked() && manualOnTop) {
			manualOnTop = false;
			insertOverlay.requestKeyboardFocus();
			hideMenus();
			syncOverlayState();
			status = "插入窗口已切换到前面 · 再次点击可关闭";
			return;
		}
		boolean opening = !insertOverlay.isOpen();
		hideMenus();
		if (opening) {
			manualOnTop = false;
			selectArgumentRange(argument);
			insertOverlay.open(argument == null ? null : argument.kind(),
					argument == null ? "" : argument.query());
			activateDockedTool(ToolWindow.INSERT);
			clearSuggestions();
			status = argument == null
					? "插入器已打开 · 输入文字搜索 · Ctrl+I 关闭"
					: "已识别 " + argument.symbol() + " 的参数" + (argument.index() + 1)
							+ " · 自动切换到" + argument.kind().label();
		} else {
			insertOverlay.close();
			status = "已关闭插入器";
		}
		syncOverlayState();
	}

	private void toggleApiDocs() {
		if (apiDocsOverlay == null) return;
		if (revealDockedTool(ToolWindow.API_DOCS)) return;
		hideMenus();
		if (apiDocsOverlay.isOpen()) {
			apiDocsOverlay.close();
			status = "已关闭 API 文档";
		} else {
			if (searchOpen) closeSearch();
			apiDocsOverlay.open();
			activateDockedTool(ToolWindow.API_DOCS);
			status = "API 文档已打开 · 可按中文、英文或拼音首字母筛选";
		}
		syncOverlayState();
	}

	private void toggleComponentEditor() {
		if (componentEditorOverlay == null) return;
		if (revealDockedTool(ToolWindow.COMPONENT_EDITOR)) {
			componentEditorOverlay.requestKeyboardFocus();
			return;
		}
		hideMenus();
		if (componentEditorOverlay.isOpen()) {
			componentEditorOverlay.close();
			componentInsertionTarget = null;
			status = "已关闭数据组件编辑器";
		} else {
			componentInsertionTarget = null;
			componentEditorOverlay.open();
			activateDockedTool(ToolWindow.COMPONENT_EDITOR);
			clearSuggestions();
			status = "数据组件编辑器已打开 · 选择物品、方块或实体后可视化编辑";
		}
		syncOverlayState();
	}

	private void toggleTextJsonEditor() {
		if (textJsonOverlay == null) return;
		if (revealDockedTool(ToolWindow.TEXT_JSON)) {
			textJsonOverlay.requestKeyboardFocus();
			return;
		}
		hideMenus();
		if (textJsonOverlay.isOpen()) {
			textJsonOverlay.close();
			status = "已关闭文本 JSON 编辑器";
		} else {
			textJsonOverlay.open();
			activateDockedTool(ToolWindow.TEXT_JSON);
			clearSuggestions();
			status = "文本 JSON 编辑器已打开 · 可设置十六进制颜色、字体和格式效果";
		}
		syncOverlayState();
	}

	private boolean openApiDocumentation(String symbol) {
		if (apiDocsOverlay == null || !apiDocsOverlay.openAt(symbol)) return false;
		activateDockedTool(ToolWindow.API_DOCS);
		hideMenus();
		if (searchOpen) closeSearch();
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		status = "已定位 API 文档：" + symbol;
		syncOverlayState();
		return true;
	}

	private void toggleFileOverlay() {
		if (fileOverlay == null) return;
		if (revealDockedTool(ToolWindow.FILES)) return;
		hideMenus();
		if (fileOverlay.isOpen()) {
			fileOverlay.close();
			status = "已关闭文件窗口";
		} else {
			if (searchOpen) closeSearch();
			fileOverlay.open();
			activateDockedTool(ToolWindow.FILES);
			status = "文件窗口已打开 · 可新建、读取或移除 Lua 脚本";
		}
		syncOverlayState();
	}

	private void openScript(String name) {
		if (!editor.getValue().equals(lastSavedSource) && !saveScript()) return;
		minecraft.setScreen(new LuaIdeScreen(previousScreen, name));
	}

	private void syncOverlayState() {
		if (editor != null) {
			editor.active = sourceLoaded && activeMenu.isEmpty()
					&& (fileOverlay == null || !fileOverlay.isOpen() || fileOverlay.dockSide().docked());
		}
		applyDockLayout();
	}

	private void setOverlayLayoutLocked() {
		if (insertOverlay != null) insertOverlay.setLayoutLocked(layoutLocked);
		if (apiDocsOverlay != null) apiDocsOverlay.setLayoutLocked(layoutLocked);
		if (componentEditorOverlay != null) componentEditorOverlay.setLayoutLocked(layoutLocked);
		if (textJsonOverlay != null) textJsonOverlay.setLayoutLocked(layoutLocked);
		if (fileOverlay != null) fileOverlay.setLayoutLocked(layoutLocked);
	}

	private DockSide toolDock(ToolWindow tool) {
		return switch (tool) {
			case MANUAL -> manualDock;
			case INSERT -> insertOverlay == null ? DockSide.FLOATING : insertOverlay.dockSide();
			case API_DOCS -> apiDocsOverlay == null ? DockSide.FLOATING : apiDocsOverlay.dockSide();
			case COMPONENT_EDITOR -> componentEditorOverlay == null
					? DockSide.FLOATING : componentEditorOverlay.dockSide();
			case TEXT_JSON -> textJsonOverlay == null ? DockSide.FLOATING : textJsonOverlay.dockSide();
			case FILES -> fileOverlay == null ? DockSide.FLOATING : fileOverlay.dockSide();
			case OUTPUT -> outputDock;
		};
	}

	private boolean toolOpen(ToolWindow tool) {
		return switch (tool) {
			case MANUAL -> manualOpen;
			case INSERT -> insertOverlay != null && insertOverlay.isOpen();
			case API_DOCS -> apiDocsOverlay != null && apiDocsOverlay.isOpen();
			case COMPONENT_EDITOR -> componentEditorOverlay != null && componentEditorOverlay.isOpen();
			case TEXT_JSON -> textJsonOverlay != null && textJsonOverlay.isOpen();
			case FILES -> fileOverlay != null && fileOverlay.isOpen();
			case OUTPUT -> outputVisible;
		};
	}

	private boolean toolVisible(ToolWindow tool) {
		return switch (tool) {
			case MANUAL -> manualOpen || manualProgress > 0.01F;
			case INSERT -> insertOverlay != null && (insertOverlay.isOpen() || insertOverlay.isVisible());
			case API_DOCS -> apiDocsOverlay != null && (apiDocsOverlay.isOpen() || apiDocsOverlay.isVisible());
			case COMPONENT_EDITOR -> componentEditorOverlay != null
					&& (componentEditorOverlay.isOpen() || componentEditorOverlay.isVisible());
			case TEXT_JSON -> textJsonOverlay != null && (textJsonOverlay.isOpen() || textJsonOverlay.isVisible());
			case FILES -> fileOverlay != null && (fileOverlay.isOpen() || fileOverlay.isVisible());
			case OUTPUT -> isOutputRendered();
		};
	}

	private List<ToolWindow> dockedToolsAt(DockSide side) {
		if (!side.docked()) return List.of();
		List<ToolWindow> tools = new ArrayList<>();
		for (ToolWindow tool : ToolWindow.values()) {
			if (toolVisible(tool) && toolDock(tool) == side) tools.add(tool);
		}
		return tools;
	}

	private ToolWindow activeToolAt(DockSide side) {
		List<ToolWindow> tools = dockedToolsAt(side);
		if (tools.isEmpty()) return null;
		ToolWindow preferred = selectedDockTool(side);
		if (preferred != null && tools.contains(preferred)) return preferred;
		ToolWindow fallback = tools.getFirst();
		setSelectedDockTool(side, fallback);
		return fallback;
	}

	private ToolWindow selectedDockTool(DockSide side) {
		return switch (side) {
			case LEFT -> activeLeftDock;
			case RIGHT -> activeRightDock;
			case BOTTOM -> activeBottomDock;
			case FLOATING -> null;
		};
	}

	private void setSelectedDockTool(DockSide side, ToolWindow tool) {
		ToolWindow previous = selectedDockTool(side);
		switch (side) {
			case LEFT -> activeLeftDock = tool;
			case RIGHT -> activeRightDock = tool;
			case BOTTOM -> activeBottomDock = tool;
			case FLOATING -> {
			}
		}
		if (tool != null && tool != previous) {
			switch (side) {
				case LEFT -> leftDockReveal = 0.0F;
				case RIGHT -> rightDockReveal = 0.0F;
				case BOTTOM -> bottomDockReveal = 0.0F;
				case FLOATING -> {
				}
			}
		}
	}

	private void activateDockedTool(ToolWindow tool) {
		DockSide side = toolDock(tool);
		if (!side.docked()) return;
		setSelectedDockTool(side, tool);
		applyDockLayout();
	}

	private boolean toolCanInteract(ToolWindow tool) {
		DockSide side = toolDock(tool);
		return !side.docked() || activeToolAt(side) == tool;
	}

	private void setToolDock(ToolWindow tool, DockSide side) {
		switch (tool) {
			case MANUAL -> setManualDock(side);
			case INSERT -> insertOverlay.setDockSide(side);
			case API_DOCS -> apiDocsOverlay.setDockSide(side);
			case COMPONENT_EDITOR -> componentEditorOverlay.setDockSide(side);
			case TEXT_JSON -> textJsonOverlay.setDockSide(side);
			case FILES -> fileOverlay.setDockSide(side);
			case OUTPUT -> setOutputDock(side);
		}
	}

	private void setManualDock(DockSide side) {
		if (side == manualDock) return;
		if (!manualDock.docked() && side.docked()) {
			manualFloatingX = manualWindowX;
			manualFloatingY = manualWindowY;
			manualFloatingWidth = manualWindowWidth;
			manualFloatingHeight = manualWindowHeight;
		}
		DockSide previous = manualDock;
		manualDock = side;
		manualResizeEdges = 0;
		if (previous.docked() && !side.docked()) {
			if (manualFloatingWidth > 0) manualWindowWidth = manualFloatingWidth;
			if (manualFloatingHeight > 0) manualWindowHeight = manualFloatingHeight;
			if (manualFloatingX >= 0) manualWindowX = manualFloatingX;
			manualWindowY = manualFloatingY;
			ensureManualGeometry();
			manualBoundsAnimation.snapTo(manualWindowX, manualWindowY,
					manualWindowWidth, manualWindowHeight);
		}
	}

	private void setOutputDock(DockSide side) {
		if (side == outputDock) return;
		if (!outputDock.docked() && side.docked()) {
			outputFloatingX = outputWindowX;
			outputFloatingY = outputWindowY;
			outputFloatingWidth = outputWindowWidth;
			outputFloatingHeight = outputWindowHeight;
		}
		DockSide previous = outputDock;
		outputDock = side;
		outputResizeEdges = 0;
		if (previous.docked() && !side.docked()) {
			if (outputFloatingWidth > 0) outputWindowWidth = outputFloatingWidth;
			if (outputFloatingHeight > 0) outputWindowHeight = outputFloatingHeight;
			if (outputFloatingX >= 0) outputWindowX = outputFloatingX;
			if (outputFloatingY >= 0) outputWindowY = outputFloatingY;
			ensureOutputGeometry();
			outputBoundsAnimation.snapTo(outputWindowX, outputWindowY,
					outputWindowWidth, outputWindowHeight);
		}
	}

	private void dockTool(ToolWindow tool, DockSide side) {
		if (!side.docked()) {
			setToolDock(tool, DockSide.FLOATING);
			applyDockLayout();
			return;
		}
		if (side == DockSide.LEFT) leftDockWidth = Math.max(leftDockWidth, preferredDockWidth(tool));
		if (side == DockSide.RIGHT) rightDockWidth = Math.max(rightDockWidth, preferredDockWidth(tool));
		if (side == DockSide.BOTTOM) bottomDockHeight = Math.max(bottomDockHeight, preferredDockHeight(tool));
		setToolDock(tool, side);
		setSelectedDockTool(side, tool);
		status = toolWindowLabel(tool) + "已加入" + dockSideLabel(side) + "标签组";
		applyDockLayout();
	}

	private int preferredDockWidth(ToolWindow tool) {
		int desired = switch (tool) {
			case API_DOCS -> 520;
			case COMPONENT_EDITOR -> 560;
			case TEXT_JSON -> 480;
			case FILES -> 430;
			case MANUAL -> 400;
			case INSERT -> 380;
			case OUTPUT -> 360;
		};
		return Math.min(desired, Math.max(MIN_DOCK_SIDE_WIDTH,
				width - MIN_EDITOR_WIDTH - DOCK_GAP - 16));
	}

	private int preferredDockHeight(ToolWindow tool) {
		return switch (tool) {
			case API_DOCS, FILES, INSERT -> 250;
			case TEXT_JSON -> 360;
			case COMPONENT_EDITOR -> 340;
			case MANUAL -> 230;
			case OUTPUT -> 180;
		};
	}

	private int minimumDockWidth(ToolWindow tool) {
		return switch (tool) {
			case API_DOCS -> 320;
			case COMPONENT_EDITOR -> 360;
			case TEXT_JSON -> 330;
			case FILES, INSERT -> 260;
			case MANUAL -> 240;
			case OUTPUT -> MIN_DOCK_SIDE_WIDTH;
		};
	}

	private int minimumDockHeight(ToolWindow tool) {
		return switch (tool) {
			case API_DOCS, INSERT -> 170;
			case TEXT_JSON -> 330;
			case COMPONENT_EDITOR -> 300;
			case FILES -> 155;
			case MANUAL -> 145;
			case OUTPUT -> MIN_DOCK_BOTTOM_HEIGHT;
		};
	}

	private int minimumDockWidth(DockSide side) {
		return dockedToolsAt(side).stream().mapToInt(this::minimumDockWidth)
				.max().orElse(MIN_DOCK_SIDE_WIDTH);
	}

	private int minimumDockHeight(DockSide side) {
		int contentMinimum = dockedToolsAt(side).stream().mapToInt(this::minimumDockHeight)
				.max().orElse(MIN_DOCK_BOTTOM_HEIGHT);
		return contentMinimum + dockTabHeight(side);
	}

	private int dockTabHeight(DockSide side) {
		return dockedToolsAt(side).size() > 1 ? DOCK_TAB_HEIGHT : 0;
	}

	private String toolWindowLabel(ToolWindow tool) {
		return switch (tool) {
			case MANUAL -> "使用手册";
			case INSERT -> "插入窗口";
			case API_DOCS -> "API 文档";
			case COMPONENT_EDITOR -> "数据组件编辑器";
			case TEXT_JSON -> "文本 JSON 编辑器";
			case FILES -> "文件窗口";
			case OUTPUT -> "运行日志";
		};
	}

	private String dockSideLabel(DockSide side) {
		return switch (side) {
			case LEFT -> "左侧";
			case RIGHT -> "右侧";
			case BOTTOM -> "底部";
			case FLOATING -> "浮动";
		};
	}

	private void applyDockLayout() {
		if (editor == null) return;
		setOverlayLayoutLocked();
		int workLeft = 8;
		int workRight = Math.max(workLeft + 1, width - 8);
		int workBottom = Math.max(WORKSPACE_TOP + 1, height - WORKSPACE_BOTTOM_MARGIN);
		int workWidth = Math.max(1, workRight - workLeft);
		int workHeight = Math.max(1, workBottom - WORKSPACE_TOP);
		if (leftDockWidth < 0) leftDockWidth = Math.min(390, Math.max(MIN_DOCK_SIDE_WIDTH, (int) (width * 0.31F)));
		if (rightDockWidth < 0) rightDockWidth = Math.min(390, Math.max(MIN_DOCK_SIDE_WIDTH, (int) (width * 0.31F)));
		if (bottomDockHeight < 0) bottomDockHeight = Math.min(250,
				Math.max(MIN_DOCK_BOTTOM_HEIGHT, (int) (workHeight * 0.34F)));

		ToolWindow leftTool = activeToolAt(DockSide.LEFT);
		ToolWindow rightTool = activeToolAt(DockSide.RIGHT);
		ToolWindow bottomTool = activeToolAt(DockSide.BOTTOM);
		int sideCount = (leftTool == null ? 0 : 1) + (rightTool == null ? 0 : 1);
		int minSide = Math.min(MIN_DOCK_SIDE_WIDTH,
				Math.max(100, (workWidth - MIN_EDITOR_WIDTH - sideCount * DOCK_GAP) / Math.max(1, sideCount)));
		int maxCombined = Math.max(0, workWidth - MIN_EDITOR_WIDTH - sideCount * DOCK_GAP);
		int leftWidth = leftTool == null ? 0 : Math.max(Math.min(minimumDockWidth(DockSide.LEFT), maxCombined),
				Math.max(minSide, leftDockWidth));
		int rightWidth = rightTool == null ? 0 : Math.max(Math.min(minimumDockWidth(DockSide.RIGHT), maxCombined),
				Math.max(minSide, rightDockWidth));
		if (leftWidth + rightWidth > maxCombined && leftWidth + rightWidth > 0) {
			double scale = maxCombined / (double) (leftWidth + rightWidth);
			leftWidth = leftTool == null ? 0 : Math.max(90, (int) Math.floor(leftWidth * scale));
			rightWidth = rightTool == null ? 0 : Math.max(90, maxCombined - leftWidth);
		}
		if (leftTool != null) leftDockWidth = leftWidth;
		if (rightTool != null) rightDockWidth = rightWidth;
		int maxBottom = Math.max(MIN_DOCK_BOTTOM_HEIGHT, workHeight - MIN_EDITOR_HEIGHT - DOCK_GAP);
		int bottomMinimum = bottomTool == null ? 0 : Math.min(minimumDockHeight(DockSide.BOTTOM), maxBottom);
		int bottomHeight = bottomTool == null ? 0
				: Math.max(bottomMinimum, Math.min(bottomDockHeight, maxBottom));
		if (bottomTool != null) bottomDockHeight = bottomHeight;
		targetLeftDockExtent = leftWidth;
		targetRightDockExtent = rightWidth;
		targetBottomDockExtent = bottomHeight;
		if (animatedLeftDockExtent < 0.0F || activeDockResize == DockSide.LEFT) {
			animatedLeftDockExtent = targetLeftDockExtent;
		}
		if (animatedRightDockExtent < 0.0F || activeDockResize == DockSide.RIGHT) {
			animatedRightDockExtent = targetRightDockExtent;
		}
		if (animatedBottomDockExtent < 0.0F || activeDockResize == DockSide.BOTTOM) {
			animatedBottomDockExtent = targetBottomDockExtent;
		}
		int renderedLeftWidth = renderedDockExtent(animatedLeftDockExtent);
		int renderedRightWidth = renderedDockExtent(animatedRightDockExtent);
		int renderedBottomHeight = renderedDockExtent(animatedBottomDockExtent);

		int editorOuterLeft = renderedLeftWidth == 0 ? EDITOR_OUTER_X
				: workLeft + renderedLeftWidth + DOCK_GAP;
		int editorRight = renderedRightWidth == 0 ? width - 12
				: workRight - renderedRightWidth - DOCK_GAP;
		int editorBottom = renderedBottomHeight == 0 ? workBottom
				: workBottom - renderedBottomHeight - DOCK_GAP;
		int editorX = editorOuterLeft + LINE_NUMBER_GUTTER_WIDTH;
		int editorWidth = Math.max(40, editorRight - editorX);
		int editorHeight = Math.max(40, editorBottom - WORKSPACE_TOP);
		if (editor.getX() != editorX) editor.setX(editorX);
		if (editor.getY() != WORKSPACE_TOP) editor.setY(WORKSPACE_TOP);
		if (editor.getWidth() != editorWidth) editor.setWidth(editorWidth);
		updateEditorFlowWidth();
		if (editor.getHeight() != editorHeight) editor.setHeight(editorHeight);

		if (leftTool != null && renderedLeftWidth > 0) {
			int tabs = dockTabHeight(DockSide.LEFT);
			for (ToolWindow tool : dockedToolsAt(DockSide.LEFT)) {
				setDockBounds(tool, workLeft, WORKSPACE_TOP + tabs,
						renderedLeftWidth, Math.max(1, workHeight - tabs));
			}
		}
		if (rightTool != null && renderedRightWidth > 0) {
			int tabs = dockTabHeight(DockSide.RIGHT);
			for (ToolWindow tool : dockedToolsAt(DockSide.RIGHT)) {
				setDockBounds(tool, workRight - renderedRightWidth, WORKSPACE_TOP + tabs,
						renderedRightWidth, Math.max(1, workHeight - tabs));
			}
		}
		if (bottomTool != null && renderedBottomHeight > 0) {
			int tabs = dockTabHeight(DockSide.BOTTOM);
			for (ToolWindow tool : dockedToolsAt(DockSide.BOTTOM)) {
				setDockBounds(tool, editorOuterLeft, workBottom - renderedBottomHeight + tabs,
						Math.max(1, editorRight - editorOuterLeft),
						Math.max(1, renderedBottomHeight - tabs));
			}
		}
		rememberWorkspaceLayout();
	}

	private int renderedDockExtent(float extent) {
		return Math.max(0, Math.round(extent));
	}

	private void advanceWorkspaceLayoutAnimation() {
		animatedLeftDockExtent = approachExtent(animatedLeftDockExtent, targetLeftDockExtent);
		animatedRightDockExtent = approachExtent(animatedRightDockExtent, targetRightDockExtent);
		animatedBottomDockExtent = approachExtent(animatedBottomDockExtent, targetBottomDockExtent);
	}

	private float approachExtent(float current, int target) {
		if (current < 0.0F) return target;
		float difference = target - current;
		if (Math.abs(difference) < 0.5F) return target;
		return current + difference * 0.30F;
	}

	private void updateEditorFlowWidth() {
		if (editor == null) return;
		MultilineTextFieldAccessor fieldAccess = (MultilineTextFieldAccessor) textField();
		int flowWidth = Math.max(1, Math.round((editor.getWidth() - 8) / Math.max(0.10F, codeScale)));
		if (fieldAccess.mcLuaIde$getWidth() == flowWidth) return;
		fieldAccess.mcLuaIde$setWidth(flowWidth);
		fieldAccess.mcLuaIde$reflowDisplayLines();
		editor.refreshScrollAmount();
	}

	private void restoreWorkspaceLayout() {
		if (workspaceLayoutRestored) return;
		workspaceLayoutRestored = true;
		WorkspaceLayout saved = rememberedWorkspaceLayout;
		layoutLocked = saved.locked();
		leftDockWidth = saved.leftWidth();
		rightDockWidth = saved.rightWidth();
		bottomDockHeight = saved.bottomHeight();
		setManualDock(saved.manual());
		insertOverlay.setDockSide(saved.insert());
		apiDocsOverlay.setDockSide(saved.apiDocs());
		componentEditorOverlay.setDockSide(saved.componentEditor());
		textJsonOverlay.setDockSide(saved.textJson());
		fileOverlay.setDockSide(saved.files());
		setOutputDock(saved.output());
		activeLeftDock = saved.activeLeft();
		activeRightDock = saved.activeRight();
		activeBottomDock = saved.activeBottom();
		manualOpen = saved.manualVisible();
		if (saved.insertVisible()) insertOverlay.open();
		if (saved.apiDocsVisible()) apiDocsOverlay.open();
		if (saved.componentEditorVisible()) componentEditorOverlay.open();
		if (saved.textJsonVisible()) textJsonOverlay.open();
		if (saved.filesVisible()) fileOverlay.open();
		outputVisible = saved.outputVisible();
		setOverlayLayoutLocked();
	}

	private void rememberWorkspaceLayout() {
		if (!workspaceLayoutRestored || insertOverlay == null || apiDocsOverlay == null
				|| componentEditorOverlay == null || textJsonOverlay == null || fileOverlay == null) return;
		WorkspaceLayout current = new WorkspaceLayout(layoutLocked,
				leftDockWidth, rightDockWidth, bottomDockHeight,
				manualDock, insertOverlay.dockSide(), apiDocsOverlay.dockSide(),
				componentEditorOverlay.dockSide(), textJsonOverlay.dockSide(), fileOverlay.dockSide(), outputDock,
				persistedSelectedDockTool(DockSide.LEFT), persistedSelectedDockTool(DockSide.RIGHT),
				persistedSelectedDockTool(DockSide.BOTTOM),
				manualOpen, insertOverlay.isOpen(), apiDocsOverlay.isOpen(), componentEditorOverlay.isOpen(), textJsonOverlay.isOpen(),
				fileOverlay.isOpen(), outputVisible);
		if (current.equals(rememberedWorkspaceLayout)) return;
		rememberedWorkspaceLayout = current;
		workspaceLayoutSavePending = true;
		workspaceLayoutSaveDueNanos = System.nanoTime() + 500_000_000L;
	}

	private ToolWindow persistedSelectedDockTool(DockSide side) {
		ToolWindow selected = selectedDockTool(side);
		if (selected != null && toolDock(selected) == side) return selected;
		for (ToolWindow tool : ToolWindow.values()) {
			if (toolDock(tool) == side) return tool;
		}
		return null;
	}

	private void flushWorkspaceLayout() {
		if (!workspaceLayoutSavePending) return;
		saveWorkspaceLayout(rememberedWorkspaceLayout);
		workspaceLayoutSavePending = false;
	}

	private void setDockBounds(ToolWindow tool, int x, int y, int width, int height) {
		switch (tool) {
			case MANUAL -> manualBoundsAnimation.animateTo(
					manualWindowX, manualWindowY, manualWindowWidth, manualWindowHeight,
					x, y, width, height);
			case INSERT -> insertOverlay.setDockBounds(x, y, width, height);
			case API_DOCS -> apiDocsOverlay.setDockBounds(x, y, width, height);
			case COMPONENT_EDITOR -> componentEditorOverlay.setDockBounds(x, y, width, height);
			case TEXT_JSON -> textJsonOverlay.setDockBounds(x, y, width, height);
			case FILES -> fileOverlay.setDockBounds(x, y, width, height);
			case OUTPUT -> outputBoundsAnimation.animateTo(
					outputWindowX, outputWindowY, outputWindowWidth, outputWindowHeight,
					x, y, width, height);
		}
	}

	private boolean beginDockResize(double mouseX, double mouseY) {
		if (layoutLocked) return false;
		applyDockLayout();
		int workLeft = 8;
		int workRight = width - 8;
		int workBottom = height - WORKSPACE_BOTTOM_MARGIN;
		int visibleLeftWidth = renderedDockExtent(animatedLeftDockExtent);
		int visibleRightWidth = renderedDockExtent(animatedRightDockExtent);
		int visibleBottomHeight = renderedDockExtent(animatedBottomDockExtent);
		if (activeToolAt(DockSide.BOTTOM) != null) {
			int dividerY = workBottom - visibleBottomHeight;
			int left = activeToolAt(DockSide.LEFT) == null ? EDITOR_OUTER_X
					: workLeft + visibleLeftWidth + DOCK_GAP;
			int right = activeToolAt(DockSide.RIGHT) == null ? width - 12
					: workRight - visibleRightWidth - DOCK_GAP;
			if (mouseX >= left && mouseX <= right && Math.abs(mouseY - dividerY) <= DOCK_DIVIDER_HIT) {
				activeDockResize = DockSide.BOTTOM;
				return true;
			}
		}
		if (activeToolAt(DockSide.LEFT) != null
				&& mouseY >= WORKSPACE_TOP && mouseY <= workBottom
				&& Math.abs(mouseX - (workLeft + visibleLeftWidth)) <= DOCK_DIVIDER_HIT) {
			activeDockResize = DockSide.LEFT;
			return true;
		}
		if (activeToolAt(DockSide.RIGHT) != null
				&& mouseY >= WORKSPACE_TOP && mouseY <= workBottom
				&& Math.abs(mouseX - (workRight - visibleRightWidth)) <= DOCK_DIVIDER_HIT) {
			activeDockResize = DockSide.RIGHT;
			return true;
		}
		return false;
	}

	private void resizeDock(double mouseX, double mouseY) {
		int workLeft = 8;
		int workRight = width - 8;
		int workBottom = height - WORKSPACE_BOTTOM_MARGIN;
		if (activeDockResize == DockSide.LEFT) {
			int reservedRight = activeToolAt(DockSide.RIGHT) == null ? 0 : rightDockWidth + DOCK_GAP;
			int max = Math.max(100, workRight - workLeft - reservedRight - MIN_EDITOR_WIDTH - DOCK_GAP);
			int minSide = Math.min(minimumDockWidth(DockSide.LEFT), max);
			leftDockWidth = clamp((int) Math.round(mouseX - workLeft), minSide, max);
		} else if (activeDockResize == DockSide.RIGHT) {
			int reservedLeft = activeToolAt(DockSide.LEFT) == null ? 0 : leftDockWidth + DOCK_GAP;
			int max = Math.max(100, workRight - workLeft - reservedLeft - MIN_EDITOR_WIDTH - DOCK_GAP);
			int minSide = Math.min(minimumDockWidth(DockSide.RIGHT), max);
			rightDockWidth = clamp((int) Math.round(workRight - mouseX), minSide, max);
		} else if (activeDockResize == DockSide.BOTTOM) {
			int workHeight = workBottom - WORKSPACE_TOP;
			int max = Math.max(MIN_DOCK_BOTTOM_HEIGHT, workHeight - MIN_EDITOR_HEIGHT - DOCK_GAP);
			int minimum = Math.min(minimumDockHeight(DockSide.BOTTOM), max);
			bottomDockHeight = clamp((int) Math.round(workBottom - mouseY),
					minimum, max);
		}
		applyDockLayout();
	}

	private int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private void finishToolDrag(ToolWindow tool, boolean wasDragging, double mouseX, double mouseY) {
		if (wasDragging && !layoutLocked) {
			DockSide target = DockSide.atEdge(mouseX, mouseY, width, height);
			if (target.docked()) {
				dockTool(tool, target);
				return;
			}
		}
		applyDockLayout();
	}

	private void undockManualForDrag(double mouseX, double mouseY) {
		setManualDock(DockSide.FLOATING);
		manualDragOffsetX = Math.min(Math.max(18.0, manualDragOffsetX),
				Math.max(18.0, manualWindowWidth - 18.0));
		manualDragOffsetY = 12.0;
		manualWindowX = Math.max(0, Math.min(width - manualWindowWidth,
				(int) Math.round(mouseX - manualDragOffsetX)));
		manualWindowY = Math.max(0, Math.min(height - manualWindowHeight,
				(int) Math.round(mouseY - manualDragOffsetY)));
		manualBoundsAnimation.snapTo(manualWindowX, manualWindowY,
				manualWindowWidth, manualWindowHeight);
	}

	private void undockOutputForDrag(double mouseX, double mouseY) {
		setOutputDock(DockSide.FLOATING);
		outputDragOffsetX = Math.min(Math.max(18.0, outputDragOffsetX),
				Math.max(18.0, outputWindowWidth - 18.0));
		outputDragOffsetY = 10.0;
		outputWindowX = Math.max(0, Math.min(width - outputWindowWidth,
				(int) Math.round(mouseX - outputDragOffsetX)));
		outputWindowY = Math.max(0, Math.min(height - outputWindowHeight,
				(int) Math.round(mouseY - outputDragOffsetY)));
		outputBoundsAnimation.snapTo(outputWindowX, outputWindowY,
				outputWindowWidth, outputWindowHeight);
	}

	private LuaCursorContext.Argument currentArgumentContext() {
		if (editor == null) return null;
		MultilineTextField field = textField();
		return LuaCursorContext.argumentAt(field.value(), field.cursor());
	}

	private LuaCursorContext.ComponentArgument currentItemComponentsArgumentContext() {
		if (editor == null) return null;
		MultilineTextField field = textField();
		return LuaCursorContext.itemComponentsArgumentAt(field.value(), field.cursor());
	}

	private void openComponentEditorForArgument(LuaCursorContext.ComponentArgument argument) {
		if (componentEditorOverlay == null) return;
		MultilineTextField field = textField();
		String source = field.value();
		int start = Math.max(0, Math.min(source.length(), argument.replacementStart()));
		int end = Math.max(start, Math.min(source.length(), argument.replacementEnd()));
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, start);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, end);
		field.setSelecting(false);
		componentInsertionTarget = new ComponentInsertionTarget(source, start, end);
		hideMenus();
		manualOnTop = false;
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		componentEditorOverlay.openForContext(argument.targetKind(), argument.targetId());
		componentEditorOverlay.requestKeyboardFocus();
		activateDockedTool(ToolWindow.COMPONENT_EDITOR);
		clearSuggestions();
		String target = argument.targetKind() == null ? ""
				: " · " + argument.targetKind().label()
						+ (argument.targetId().isBlank() ? "" : " " + argument.targetId());
		status = "已识别 " + argument.symbol() + " 的参数" + (argument.index() + 1)
				+ " 为物品堆叠组件" + target + " · 编辑后可直接替换当前参数";
		syncOverlayState();
	}

	private void openArgumentCompletion(LuaCursorContext.Argument argument) {
		selectArgumentRange(argument);
		hideMenus();
		manualOnTop = false;
		insertOverlay.open(argument.kind(), argument.query());
		clearSuggestions();
		status = "Tab 参数补全 · " + argument.symbol() + " 参数" + (argument.index() + 1)
				+ "需要" + argument.kind().label();
		syncOverlayState();
	}

	private void selectArgumentRange(LuaCursorContext.Argument argument) {
		if (argument == null) return;
		MultilineTextField field = textField();
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, argument.replacementStart());
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, argument.replacementEnd());
		field.setSelecting(false);
	}

	private void openSearchFromSelection() {
		String selected = selectedEditorText();
		searchOpen = true;
		if (!selected.isEmpty()) {
			searchQuery = selected;
		}
		if (searchBox != null) {
			searchBox.visible = true;
			searchBox.active = true;
			searchBox.setValue(searchQuery);
			searchBox.setCursorPosition(searchQuery.length());
			searchBox.setHighlightPos(0);
			setInitialFocus(searchBox);
		} else {
			refreshSearchMatches(true);
		}
		hideMenus();
		syncOverlayState();
		status = searchQuery.isEmpty()
				? "搜索已打开 · 输入内容开始查找"
				: "已用选中文本查找：" + singleLineSearchLabel(searchQuery);
	}

	private void closeSearch() {
		searchOpen = false;
		if (searchBox != null) searchBox.active = false;
		if (getFocused() == searchBox && editor != null) setInitialFocus(editor);
		syncOverlayState();
		status = "已关闭搜索";
	}

	private String selectedEditorText() {
		if (editor == null) return "";
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) textField();
		int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		if (start == end) return "";
		return textField().value().substring(start, end);
	}

	private void useSelectionAsSearchQuery() {
		String selected = selectedEditorText();
		if (selected.isEmpty()) {
			status = "请先在编辑器中选择要查找的文字";
			return;
		}
		searchQuery = selected;
		if (searchBox != null) {
			searchBox.setValue(searchQuery);
			searchBox.setCursorPosition(searchQuery.length());
			searchBox.setHighlightPos(0);
			setInitialFocus(searchBox);
		} else {
			refreshSearchMatches(true);
		}
		status = "已填入选中文本：" + singleLineSearchLabel(searchQuery);
	}

	private void insertApiTemplate(String template) {
		if (template == null || template.isBlank()) return;
		MultilineTextField field = textField();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int selectionStart = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int selectionEnd = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		String source = field.value();
		int lineStart = source.lastIndexOf('\n', Math.max(0, selectionStart - 1)) + 1;
		int replaceStart = source.substring(lineStart, selectionStart).isBlank() ? lineStart : selectionStart;
		String sourceAfterSelection = source.substring(0, replaceStart) + source.substring(selectionEnd);
		boolean needsLeadingNewline = replaceStart > 0
				&& sourceAfterSelection.charAt(replaceStart - 1) != '\n';
		String layoutSource = sourceAfterSelection;
		int templateStart = replaceStart;
		if (needsLeadingNewline) {
			layoutSource = sourceAfterSelection.substring(0, replaceStart) + "\n"
					+ sourceAfterSelection.substring(replaceStart);
			templateStart++;
		}
		String baseIndent = LuaIndentation.indentForNewLine(layoutSource, templateStart);
		String formatted = indentTemplate(template, baseIndent);
		boolean suffixStartsOnNewLine = selectionEnd < source.length() && source.charAt(selectionEnd) == '\n';
		String replacement = (needsLeadingNewline ? "\n" : "") + formatted
				+ (suffixStartsOnNewLine ? "" : "\n");
		replaceRange(replaceStart, selectionEnd, replacement);
		status = "已从 API 文档插入 Lua 模板 · 自动使用四空格缩进";
		refreshSuggestions();
	}

	private void insertComponentPatch(String componentsLua) {
		if (componentsLua == null || componentsLua.isBlank()) return;
		MultilineTextField field = textField();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int start;
		int end;
		if (componentInsertionTarget != null && field.value().equals(componentInsertionTarget.source())) {
			start = componentInsertionTarget.start();
			end = componentInsertionTarget.end();
		} else {
			start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
			end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		}
		componentInsertionTarget = null;
		String formatted = LuaIndentation.formatPaste(componentsLua, field.value(), start);
		replaceRange(start, end, formatted);
		clearSuggestions();
		status = "已插入数据组件表 · 导出前已通过服务器编解码校验";
	}

	private void insertTextJson(String luaJsonString) {
		if (luaJsonString == null || luaJsonString.isBlank()) return;
		MultilineTextField field = textField();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		replaceRange(start, end, luaJsonString);
		clearSuggestions();
		status = "已插入 Minecraft 文本组件 JSON 字符串";
	}

	private static String indentTemplate(String template, String baseIndent) {
		String normalized = template.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
		StringBuilder result = new StringBuilder(normalized.length() + baseIndent.length() * 4);
		String[] lines = normalized.split("\n", -1);
		for (int index = 0; index < lines.length; index++) {
			if (index > 0) result.append('\n');
			String line = lines[index];
			if (line.isBlank()) continue;
			result.append(baseIndent);
			int offset = 0;
			while (offset < line.length() && (line.charAt(offset) == ' ' || line.charAt(offset) == '\t')) {
				result.append(line.charAt(offset) == '\t' ? LuaIndentation.INDENT : " ");
				offset++;
			}
			result.append(line.substring(offset));
		}
		return result.toString();
	}

	private void refreshSearchMatches(boolean moveToMatch) {
		searchMatches.clear();
		selectedSearchMatch = -1;
		if (searchQuery.isEmpty() || editor == null) return;
		String source = editor.getValue().toLowerCase(Locale.ROOT);
		String needle = searchQuery.toLowerCase(Locale.ROOT);
		int offset = 0;
		while (offset <= source.length() - needle.length()) {
			int match = source.indexOf(needle, offset);
			if (match < 0) break;
			searchMatches.add(match);
			offset = match + Math.max(1, needle.length());
		}
		if (searchMatches.isEmpty()) {
			status = "没有找到：“" + singleLineSearchLabel(searchQuery) + "”";
			return;
		}
		int cursor = textField().cursor();
		selectedSearchMatch = 0;
		for (int index = 0; index < searchMatches.size(); index++) {
			if (searchMatches.get(index) >= cursor) {
				selectedSearchMatch = index;
				break;
			}
		}
		if (moveToMatch) {
			selectSearchMatch();
		} else {
			status = "已标记 " + searchMatches.size() + " 个搜索结果";
		}
	}

	private void moveSearchMatch(int direction) {
		if (searchMatches.isEmpty()) {
			refreshSearchMatches(true);
			return;
		}
		selectedSearchMatch = Math.floorMod(selectedSearchMatch + direction, searchMatches.size());
		selectSearchMatch();
	}

	private void selectSearchMatch() {
		if (selectedSearchMatch < 0 || selectedSearchMatch >= searchMatches.size()) return;
		int start = searchMatches.get(selectedSearchMatch);
		MultilineTextField field = textField();
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, start);
		status = "搜索结果 " + (selectedSearchMatch + 1) + " / " + searchMatches.size();
	}

	private String singleLineSearchLabel(String value) {
		String label = value.replace('\n', ' ').replace('\r', ' ');
		return label.length() > 40 ? label.substring(0, 37) + "…" : label;
	}

	private boolean handleSearchKey(KeyEvent event) {
		if (!searchOpen) return false;
		if (event.key() == InputConstants.KEY_ESCAPE) {
			closeSearch();
			return true;
		}
		if (getFocused() == searchBox && event.key() == InputConstants.KEY_UP) {
			moveSearchMatch(-1);
			return true;
		}
		if (getFocused() == searchBox
				&& (event.key() == InputConstants.KEY_DOWN || event.key() == InputConstants.KEY_RETURN)) {
			moveSearchMatch(1);
			return true;
		}
		return super.keyPressed(event);
	}

	private void recordHistory(String value) {
		if (internalTextChange || value.equals(lastSnapshot)) {
			return;
		}
		undoHistory.addLast(lastSnapshot);
		while (undoHistory.size() > 100) {
			undoHistory.removeFirst();
		}
		redoHistory.clear();
		lastSnapshot = value;
	}

	private void scheduleValidation(String source) {
		int revision = validationRevision.incrementAndGet();
		syntaxMessage = "● 正在检查…";
		validationDetail = "正在检查 Lua 语法、MC API 参数数量和参数类型…";
		syntaxColor = 0xFFFFFF55;
		editorErrorRanges = List.of();
		errorLineNumbers = Set.of();
		LuaSyntaxHighlighter.clearErrors(editor);
		CompletableFuture.supplyAsync(() -> {
			if (revision != validationRevision.get()) {
				return null;
			}
			return LuaCodeAnalyzer.analyze(scriptName, source);
		}, CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS)).whenComplete((analysis, failure) ->
				minecraft.execute(() -> {
					if (revision != validationRevision.get()) {
						return;
					}
					if (failure != null) {
						editorErrorRanges = List.of();
						errorLineNumbers = Set.of();
						syntaxMessage = "✗ 检查失败";
						validationDetail = "检查器发生错误：" + ScriptManager.message(failure);
						syntaxColor = 0xFFFF5555;
						LuaSyntaxHighlighter.clearErrors(editor);
					} else if (analysis.valid()) {
						editorErrorRanges = List.of();
						errorLineNumbers = Set.of();
						syntaxMessage = "✓ 检查通过";
						validationDetail = "Lua 语法正确，未发现 MC API 参数数量或类型错误";
						syntaxColor = 0xFF55FF55;
						LuaSyntaxHighlighter.clearErrors(editor);
					} else {
						LuaCodeAnalyzer.Diagnostic first = analysis.first();
						errorLineNumbers = Set.copyOf(analysis.diagnostics().stream()
								.map(LuaCodeAnalyzer.Diagnostic::line)
								.filter(line -> line > 0)
								.toList());
						syntaxMessage = "✗ " + analysis.diagnostics().size() + " 个问题";
						validationDetail = first.displayMessage()
								+ (analysis.diagnostics().size() > 1 ? "（另有 "
								+ (analysis.diagnostics().size() - 1) + " 个问题）" : "");
						syntaxColor = 0xFFFF5555;
						editorErrorRanges = analysis.diagnostics().stream()
								.map(diagnostic -> new LuaSyntaxHighlighter.ErrorRange(
										diagnostic.start(), diagnostic.end()))
								.toList();
						LuaSyntaxHighlighter.setErrors(editor, editorErrorRanges);
					}
				}));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (scaleInput != null && scaleInput.visible && getFocused() == scaleInput) {
			if (event.key() == InputConstants.KEY_RETURN) {
				applyCustomScale();
				return true;
			}
			if (event.key() == InputConstants.KEY_ESCAPE) {
				hideMenus();
				return true;
			}
			return super.keyPressed(event);
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_I) {
			LuaCursorContext.ComponentArgument componentArgument = currentItemComponentsArgumentContext();
			if (componentArgument != null) {
				openComponentEditorForArgument(componentArgument);
			} else {
				toggleInsertOverlay();
			}
			return true;
		}
		if (insertOverlay != null && insertOverlay.isOpen() && toolCanInteract(ToolWindow.INSERT)) {
			boolean handled = insertOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (componentEditorOverlay != null && componentEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.COMPONENT_EDITOR)) {
			boolean handled = componentEditorOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (textJsonOverlay != null && textJsonOverlay.isOpen()
				&& toolCanInteract(ToolWindow.TEXT_JSON)) {
			boolean handled = textJsonOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (fileOverlay != null && fileOverlay.isOpen() && toolCanInteract(ToolWindow.FILES)) {
			boolean handled = fileOverlay.keyPressed(event);
			if (handled) {
				syncOverlayState();
				return true;
			}
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_F) {
			openSearchFromSelection();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_F1) {
			String symbol = apiSymbolAtCursor();
			if (openApiDocumentation(symbol)) return true;
		}
		if (event.key() == InputConstants.KEY_F1) {
			toggleManual();
			return true;
		}
		if (searchOpen && (getFocused() == searchBox || event.key() == InputConstants.KEY_ESCAPE)) {
			return handleSearchKey(event);
		}
		if (apiDocsOverlay != null && apiDocsOverlay.isOpen()
				&& toolCanInteract(ToolWindow.API_DOCS) && apiDocsOverlay.keyPressed(event)) {
			syncOverlayState();
			return true;
		}
		if (manualOpen && toolCanInteract(ToolWindow.MANUAL)
				&& event.hasControlDown() && event.key() == InputConstants.KEY_C
				&& hasManualSelection()) {
			copyManualSelection();
			return true;
		}
		if (manualOpen && toolCanInteract(ToolWindow.MANUAL)
				&& event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			manualSelectionAnchor = 0;
			manualSelectionCaret = manualTextIndex.text().length();
			status = "已全选使用手册，按 Ctrl+C 复制";
			return true;
		}
		if (manualOpen && toolCanInteract(ToolWindow.MANUAL) && event.key() == InputConstants.KEY_ESCAPE
				&& (insertOverlay == null || !insertOverlay.isOpen() || manualOnTop)) {
			if (manualDock.docked()) return true;
			manualOpen = false;
			return true;
		}
		if (manualOpen && toolCanInteract(ToolWindow.MANUAL) && event.key() == InputConstants.KEY_ESCAPE) {
			if (manualDock.docked()) return true;
			manualOpen = false;
			return true;
		}
		if (!manualOpen && editor.isFocused() && event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			pasteSmart();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_Z) {
			undo();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_Y) {
			redo();
			return true;
		}
		if (editor.isFocused() && event.hasControlDown() && event.key() == InputConstants.KEY_SLASH) {
			toggleCurrentLineComment();
			return true;
		}
		if (editor.isFocused() && !suggestions.isEmpty() && event.key() == InputConstants.KEY_UP) {
			moveSuggestionSelection(-1);
			return true;
		}
		if (editor.isFocused() && !suggestions.isEmpty() && event.key() == InputConstants.KEY_DOWN) {
			moveSuggestionSelection(1);
			return true;
		}
		if (editor.isFocused() && event.key() == InputConstants.KEY_RIGHT) {
			MultilineTextField field = textField();
			MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
			if (access.mcLuaIde$getCursor() != access.mcLuaIde$getSelectCursor()) {
				int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
				field.setSelecting(false);
				field.seekCursor(Whence.ABSOLUTE, end);
				refreshSuggestions();
				return true;
			}
		}
		if (editor.isFocused() && event.key() == InputConstants.KEY_TAB) {
			if (suggestions.isEmpty()) {
				LuaCursorContext.Argument argument = currentArgumentContext();
				if (argument != null) {
					openArgumentCompletion(argument);
				} else {
					textField().insertText("    ");
					status = "已插入 4 个空格";
				}
			} else {
				applySelectedSuggestion();
			}
			return true;
		}
		if (editor.isFocused() && !suggestions.isEmpty() && event.key() == InputConstants.KEY_ESCAPE) {
			clearSuggestions();
			return true;
		}
		if (editor.isFocused() && event.key() == InputConstants.KEY_RETURN && event.hasControlDown()) {
			toggleRun();
			return true;
		}
		if (event.key() == InputConstants.KEY_S && event.hasControlDown()) {
			saveScript();
			return true;
		}
		if (editor.isFocused() && event.key() == InputConstants.KEY_RETURN) {
			insertIndentedNewline();
			refreshSuggestions();
			return true;
		}
		boolean handled = super.keyPressed(event);
		if (handled && editor.isFocused()) {
			refreshSuggestions();
		}
		return handled;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (scaleInput != null && scaleInput.visible && getFocused() == scaleInput) {
			return super.charTyped(event);
		}
		if (insertOverlay != null && insertOverlay.isOpen() && toolCanInteract(ToolWindow.INSERT)
				&& insertOverlay.charTyped(event)) return true;
		if (componentEditorOverlay != null && componentEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.COMPONENT_EDITOR)
				&& componentEditorOverlay.charTyped(event)) return true;
		if (textJsonOverlay != null && textJsonOverlay.isOpen()
				&& toolCanInteract(ToolWindow.TEXT_JSON)
				&& textJsonOverlay.charTyped(event)) return true;
		if (fileOverlay != null && fileOverlay.isOpen() && toolCanInteract(ToolWindow.FILES)
				&& fileOverlay.charTyped(event)) return true;
		if (searchOpen && getFocused() == searchBox) return super.charTyped(event);
		if (apiDocsOverlay != null && apiDocsOverlay.isOpen() && toolCanInteract(ToolWindow.API_DOCS)
				&& apiDocsOverlay.charTyped(event)) return true;
		boolean handled = super.charTyped(event);
		if (handled && editor.isFocused() && !manualOpen) {
			int codepoint = event.codepoint();
			if (codepoint == 'd' || codepoint == 'e' || codepoint == 'f' || codepoint == 'l'
					|| codepoint == '}' || codepoint == ')' || codepoint == ']') {
				reindentCurrentClosingLine(true);
			}
			refreshSuggestions();
		}
		return handled;
	}

	private void undo() {
		if (undoHistory.isEmpty()) {
			status = "没有可撤销的修改";
			return;
		}
		String previous = undoHistory.removeLast();
		redoHistory.addLast(editor.getValue());
		applyHistory(previous);
		status = "已撤销";
	}

	private void redo() {
		if (redoHistory.isEmpty()) {
			status = "没有可重做的修改";
			return;
		}
		String next = redoHistory.removeLast();
		undoHistory.addLast(editor.getValue());
		applyHistory(next);
		status = "已重做";
	}

	private void applyHistory(String source) {
		internalTextChange = true;
		editor.setValue(source);
		internalTextChange = false;
		lastSnapshot = source;
		clearSuggestions();
		scheduleValidation(source);
	}

	private void toggleCurrentLineComment() {
		MultilineTextField field = textField();
		String source = field.value();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int selectionStart = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int selectionEnd = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int lineStart = source.lastIndexOf('\n', Math.max(0, selectionStart - 1)) + 1;
		int effectiveEnd = selectionEnd > selectionStart && selectionEnd > 0 && source.charAt(selectionEnd - 1) == '\n'
				? selectionEnd - 1 : selectionEnd;
		int lineEnd = source.indexOf('\n', effectiveEnd);
		if (lineEnd < 0) {
			lineEnd = source.length();
		}
		String block = source.substring(lineStart, lineEnd);
		String[] lines = block.split("\n", -1);
		boolean allCommented = true;
		for (String line : lines) {
			if (!line.isBlank() && !line.stripLeading().startsWith("--")) {
				allCommented = false;
				break;
			}
		}
		String transformed = allCommented
				? block.replaceAll("(?m)^(\\s*)-- ?", "$1")
				: block.replaceAll("(?m)^(\\s*)(?=\\S)", "$1-- ");
		replaceRange(lineStart, lineEnd, transformed);
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, lineStart);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, lineStart + transformed.length());
		field.setSelecting(false);
		status = allCommented ? "已取消所选行注释" : "已注释所选行";
	}

	private void replaceRange(int start, int end, String replacement) {
		MultilineTextField field = textField();
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, start);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, end);
		field.insertText(replacement);
		field.setSelecting(false);
	}

	private void refreshSuggestions() {
		if (editor == null || !editor.isFocused()) {
			clearSuggestions();
			return;
		}
		MultilineTextField field = textField();
		String source = field.value();
		int cursor = field.cursor();
		int start = cursor;
		while (start > 0) {
			char character = source.charAt(start - 1);
			if (!Character.isLetterOrDigit(character) && character != '_' && character != '.') {
				break;
			}
			start--;
		}
		String prefix = source.substring(start, cursor);
		if (prefix.isEmpty()) {
			clearSuggestions();
			return;
		}
		completionStart = start;
		completionEnd = cursor;
		String previousSelection = suggestions.isEmpty() ? null
				: suggestions.get(Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1)));
		List<String> nextSuggestions = completions().stream()
				.filter(value -> value.regionMatches(true, 0, prefix, 0, prefix.length())
						&& !value.equalsIgnoreCase(prefix))
				.sorted(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
				.toList();
		if (nextSuggestions.isEmpty()) {
			clearSuggestions();
			return;
		}
		suggestions = nextSuggestions;
		int preservedIndex = previousSelection == null ? -1 : suggestions.indexOf(previousSelection);
		selectedSuggestion = preservedIndex >= 0 ? preservedIndex : 0;
		if (!suggestionAnimationReady || preservedIndex < 0) {
			resetSuggestionAnimation();
		} else {
			animatedSuggestionSelection = Math.max(0.0,
					Math.min(animatedSuggestionSelection, suggestions.size() - 1.0));
			animatedSuggestionScroll = Math.max(0.0,
					Math.min(animatedSuggestionScroll, suggestions.size() - 1.0));
			targetSuggestionScroll = Math.max(0.0,
					Math.min(targetSuggestionScroll, suggestions.size() - 1.0));
		}
	}

	private void moveSuggestionSelection(int direction) {
		if (suggestions.isEmpty() || direction == 0) return;
		selectedSuggestion = Math.max(0,
				Math.min(suggestions.size() - 1, selectedSuggestion + direction));
	}

	private void clearSuggestions() {
		suggestions = List.of();
		selectedSuggestion = 0;
		suggestionAnimationReady = false;
		suggestionAnimationNanos = 0L;
	}

	private void resetSuggestionAnimation() {
		animatedSuggestionSelection = selectedSuggestion;
		animatedSuggestionScroll = 0.0;
		targetSuggestionScroll = 0.0;
		suggestionAnimationNanos = System.nanoTime();
		suggestionAnimationReady = true;
	}

	private void applySelectedSuggestion() {
		if (suggestions.isEmpty()) {
			return;
		}
		String completion = suggestions.get(selectedSuggestion);
		MultilineTextField field = textField();
		String source = field.value();
		boolean apiFunction = LuaApiDocumentation.find(completion) != null;
		boolean alreadyCalled = completionEnd < source.length() && source.charAt(completionEnd) == '(';
		String replacement = apiFunction && !alreadyCalled ? completion + "()" : completion;
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, completionStart);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, completionEnd);
		field.insertText(replacement);
		field.setSelecting(false);
		if (apiFunction && !alreadyCalled) {
			field.seekCursor(Whence.ABSOLUTE, completionStart + completion.length() + 1);
		}
		clearSuggestions();
		status = "已补全：" + LuaApiDocumentation.completionLabel(completion);
		refreshSuggestions();
	}

	private void insertIndentedNewline() {
		reindentCurrentClosingLine(false);
		MultilineTextField field = textField();
		String source = field.value();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int cursor = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		String indent = LuaIndentation.indentForNewLine(source, cursor);
		field.insertText("\n" + indent);
	}

	private void pasteSmart() {
		String clipboard = minecraft.keyboardHandler.getClipboard();
		if (clipboard.isEmpty()) {
			return;
		}
		MultilineTextField field = textField();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		String source = field.value();
		int selectionStart = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int selectionEnd = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int replaceStart = selectionStart;
		boolean multiline = LuaIndentation.pasteWillBeMultiline(clipboard);
		if (multiline) {
			int lineStart = source.lastIndexOf('\n', Math.max(0, selectionStart - 1)) + 1;
			if (source.substring(lineStart, selectionStart).isBlank()) {
				replaceStart = lineStart;
			}
		}
		String formatted = LuaIndentation.formatPaste(clipboard, source, replaceStart);
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, replaceStart);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, selectionEnd);
		field.insertText(formatted);
		field.setSelecting(false);
		clearSuggestions();
		int lineCount = formatted.split("\n", -1).length;
		status = multiline ? "已智能整理并粘贴 " + lineCount + " 行 Lua 代码" : "已粘贴";
	}

	private void insertCatalogEntry(LuaInsertCatalog.Entry entry) {
		MultilineTextField field = textField();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int selectionStart = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int selectionEnd = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		String source = field.value();
		String inserted = isInsideQuotedString(source, selectionStart)
				? entry.value() : "\"" + entry.value().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		replaceRange(selectionStart, selectionEnd, inserted);
		clearSuggestions();
		status = "已插入：" + entry.value();
	}

	private boolean isInsideQuotedString(String source, int cursor) {
		int safeCursor = Math.max(0, Math.min(cursor, source.length()));
		int lineStart = source.lastIndexOf('\n', Math.max(0, safeCursor - 1)) + 1;
		char quote = 0;
		boolean escaped = false;
		for (int index = lineStart; index < safeCursor; index++) {
			char character = source.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (character == '\\' && quote != 0) {
				escaped = true;
			} else if (quote == 0 && (character == '\'' || character == '\"')) {
				quote = character;
			} else if (character == quote) {
				quote = 0;
			}
		}
		return quote != 0;
	}

	private boolean reindentCurrentClosingLine(boolean showStatus) {
		MultilineTextField field = textField();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		if (access.mcLuaIde$getCursor() != access.mcLuaIde$getSelectCursor()) {
			return false;
		}
		String source = field.value();
		int cursor = field.cursor();
		int lineStart = source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
		int lineEnd = source.indexOf('\n', cursor);
		if (lineEnd < 0) lineEnd = source.length();
		String line = source.substring(lineStart, lineEnd);
		if (!source.substring(cursor, lineEnd).isBlank()
				|| !LuaIndentation.startsWithClosingToken(source, lineStart, lineEnd)) {
			return false;
		}
		int leadingLength = line.length() - line.stripLeading().length();
		String desiredIndent = LuaIndentation.desiredIndentForLine(source, lineStart);
		if (leadingLength <= desiredIndent.length()) {
			return false;
		}
		int contentCursorOffset = cursor - lineStart - leadingLength;
		internalTextChange = true;
		try {
			replaceRange(lineStart, lineStart + leadingLength, desiredIndent);
		} finally {
			internalTextChange = false;
		}
		field.seekCursor(Whence.ABSOLUTE,
				lineStart + desiredIndent.length() + Math.max(0, contentCursorOffset));
		lastSnapshot = field.value();
		if (showStatus) {
			status = "已自动对齐结束关键字";
		}
		return true;
	}

	private MultilineTextField textField() {
		return ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
	}

	private boolean saveScript() {
		if (!sourceLoaded) {
			status = "脚本尚未从服务器加载完成";
			return false;
		}
		try {
			String source = editor.getValue();
			status = "正在保存到服务器…";
			ScriptManager.save(scriptName, source).whenComplete((ignored, failure) -> minecraft.execute(() -> {
				if (failure != null) {
					status = "保存失败：" + ScriptManager.message(failure);
					return;
				}
				lastSavedSource = source;
				status = "已保存到服务器：" + scriptName + ".lua";
			}));
			return true;
		} catch (Exception error) {
			status = "保存失败：" + error.getMessage();
			return false;
		}
	}

	private void toggleRun() {
		if (runningExecution != null && runningExecution.isRunning()) {
			status = "正在停止 " + scriptName + ".lua…";
			runningExecution.stop();
			return;
		}
		runScript();
	}

	private void runScript() {
		if (!sourceLoaded) {
			status = "脚本尚未从服务器加载完成";
			return;
		}
		ScriptManager.ScriptExecution active = ScriptManager.activeExecution();
		if (active != null) {
			if (active.name().equals(scriptName)) {
				attachExecution(active, true);
				status = "脚本仍在后台运行，已恢复运行日志";
			} else {
				status = "已有脚本在后台运行：" + active.name() + ".lua";
			}
			return;
		}
		outputLines.clear();
		outputScrollLines = 0;
		outputVisible = true;
		ensureOutputGeometry();
		status = "正在运行 " + scriptName + ".lua…";
		try {
			lastSavedSource = editor.getValue();
			runningExecution = ScriptManager.start(
					scriptName,
					editor.getValue(),
					line -> { },
					result -> { });
			attachExecution(runningExecution, false);
			updateRunControls();
		} catch (Exception error) {
			status = "运行失败：" + error.getMessage();
			appendOutput("✗ " + error.getMessage());
			runningExecution = null;
			updateRunControls();
		}
	}

	private void restoreBackgroundRun() {
		ScriptManager.ScriptExecution active = ScriptManager.activeExecution();
		if (active == null || !active.name().equals(scriptName)) return;
		attachExecution(active, true);
		status = "已恢复后台运行的 " + scriptName + ".lua 及其实时日志";
	}

	private void attachExecution(ScriptManager.ScriptExecution execution, boolean animateOpen) {
		outputExecution = execution;
		runningExecution = execution.isRunning() ? execution : null;
		observedExecutionResult = null;
		outputLines.clear();
		outputLines.addAll(execution.outputLines());
		observedOutputLineCount = execution.outputLineCount();
		outputScrollLines = 0;
		outputVisible = true;
		activateDockedTool(ToolWindow.OUTPUT);
		if (animateOpen) outputProgress = 0.0F;
		ensureOutputGeometry();
		updateRunControls();
	}

	private void syncExecutionState() {
		if (outputExecution == null) return;
		long lineCount = outputExecution.outputLineCount();
		if (lineCount != observedOutputLineCount) {
			long added = Math.max(0L, lineCount - observedOutputLineCount);
			if (outputScrollLines > 0 && added > 0) {
				outputScrollLines += (int) Math.min(Integer.MAX_VALUE, added);
			}
			outputLines.clear();
			outputLines.addAll(outputExecution.outputLines());
			observedOutputLineCount = lineCount;
		}
		ScriptManager.ExecutionResult result = outputExecution.result();
		if (result == null || result == observedExecutionResult) return;
		observedExecutionResult = result;
		runningExecution = null;
		updateRunControls();
		if (result.stopped()) status = "脚本已停止";
		else if (result.error() != null) status = "运行失败：" + result.error();
		else status = "脚本执行完成";
	}

	private void closeOutputConsole() {
		outputVisible = false;
		outputDragging = false;
		outputResizeEdges = 0;
		status = "已关闭运行日志";
	}

	private void toggleOutputConsole() {
		if (revealDockedTool(ToolWindow.OUTPUT)) return;
		if (outputVisible) {
			closeOutputConsole();
			return;
		}
		ScriptManager.ScriptExecution active = ScriptManager.activeExecution();
		if (active != null && active.name().equals(scriptName) && active != outputExecution) {
			attachExecution(active, true);
			status = "已打开后台运行脚本的实时日志";
			return;
		}
		outputVisible = true;
		outputProgress = 0.0F;
		ensureOutputGeometry();
		activateDockedTool(ToolWindow.OUTPUT);
		status = outputLines.isEmpty() ? "运行日志已打开 · 当前还没有输出" : "运行日志已打开";
		applyDockLayout();
	}

	private void appendOutput(String output) {
		int addedLines = 0;
		for (String line : output.split("\\R", -1)) {
			outputLines.add(line);
			addedLines++;
		}
		if (outputScrollLines > 0) outputScrollLines += addedLines;
		while (outputLines.size() > 200) {
			outputLines.removeFirst();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int logicalMouseX = logicalMouse(mouseX);
		int logicalMouseY = logicalMouse(mouseY);
		graphics.pose().pushMatrix();
		graphics.pose().scale(interfaceScale, interfaceScale);
		extractLogicalRenderState(graphics, logicalMouseX, logicalMouseY, partialTick);
		graphics.pose().popMatrix();
	}

	private void extractLogicalRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 4, width, 31, 0xE0181818);
		graphics.fill(0, 32, width, 52, 0xD010141A);
		graphics.fill(0, 51, width, 52, 0xFF303846);
		graphics.fill(0, 52, width, 66, 0xE015181E);
		graphics.fill(0, 65, width, 66, syntaxColor);
		int editorOuterX = editorOuterX();
		graphics.fill(editorOuterX, editor.getY(), editor.getRight(), editor.getBottom(), theme.background());
		graphics.fill(editorOuterX, editor.getY(), editor.getX() - 1, editor.getBottom(), lineNumberBackground());
		graphics.fill(editor.getX() - 1, editor.getY() + 1, editor.getX(), editor.getBottom() - 1, 0xFF4A515C);
		graphics.outline(editorOuterX, editor.getY(), editor.getRight() - editorOuterX,
				editor.getHeight(), 0xFF555555);
		int infoHalf = Math.max(80, width / 2);
		String fileLabel = "◆ " + scriptName + ".lua";
		graphics.text(font, font.plainSubstrByWidth(fileLabel, infoHalf - 22), 12, 38, 0xFF66B3FF);
		String visibleSyntax = font.plainSubstrByWidth(syntaxMessage, Math.max(40, width - infoHalf - 18));
		graphics.text(font, visibleSyntax, infoHalf, 38, syntaxColor);
		String visibleDetail = font.plainSubstrByWidth(validationDetail, Math.max(40, width - 24));
		graphics.text(font, visibleDetail, 12, 55, syntaxColor);
		MultilineTextField field = textField();
		extractErrorHighlights(graphics, field);
		extractSearchHighlights(graphics, field);
		extractToolWindows(graphics, mouseX, mouseY, true);
		extractDockTabs(graphics, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		extractLineNumbers(graphics, field);
		String visibleStatus = status.length() > 120 ? status.substring(0, 117) + "…" : status;
		graphics.text(font, visibleStatus, 12, height - 24, status.startsWith("运行失败") ? 0xFFFF7777 : 0xFFAAAAAA);
		int logicalCursorLine = LuaSyntaxHighlighter.logicalLineAt(editor, field.value(), field.cursor());
		String position = "Ln " + logicalCursorLine + ", Col " + currentColumn(field);
		graphics.text(font, position, width - font.width(position) - 12, height - 24, 0xFF888888);
		boolean suggestionCovered = !suggestions.isEmpty() && suggestionPopupContains(mouseX, mouseY);
		if (!pointInsideFloatingTool(mouseX, mouseY)
				&& !pointInsideSearch(mouseX, mouseY)
				&& !menuWidgetAt(mouseX, mouseY)
				&& !suggestionCovered) {
			extractApiHover(graphics, field, mouseX, mouseY);
		}
		extractSuggestions(graphics, field, mouseX, mouseY);
		extractDockPreview(graphics, mouseX, mouseY);
		extractDockDividers(graphics, mouseX, mouseY);
		extractToolWindows(graphics, mouseX, mouseY, false);
		extractSearch(graphics, mouseX, mouseY, partialTick);
		extractMenusOnTop(graphics, mouseX, mouseY, partialTick);
	}

	private int logicalMouse(int physicalCoordinate) {
		return (int) Math.round(physicalCoordinate / Math.max(0.10F, interfaceScale));
	}

	private double logicalMouse(double physicalCoordinate) {
		return physicalCoordinate / Math.max(0.10F, interfaceScale);
	}

	private void extractToolWindows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean docked) {
		if (docked) {
			for (DockSide side : List.of(DockSide.LEFT, DockSide.RIGHT, DockSide.BOTTOM)) {
				ToolWindow active = activeToolAt(side);
				if (active != null) {
					extractToolWindow(active, graphics, mouseX, mouseY);
					extractDockContentTransition(side, graphics);
				}
			}
			return;
		}
		if (isOutputRendered() && !outputDock.docked()) {
			extractOutputConsole(graphics, mouseX, mouseY);
		}
		if (manualOnTop) {
			if (insertOverlay != null && !insertOverlay.dockSide().docked()) {
				insertOverlay.render(graphics, mouseX, mouseY);
			}
			if (!manualDock.docked()) extractManual(graphics, mouseX, mouseY);
		} else {
			if (!manualDock.docked()) extractManual(graphics, mouseX, mouseY);
			if (insertOverlay != null && !insertOverlay.dockSide().docked()) {
				insertOverlay.render(graphics, mouseX, mouseY);
			}
		}
		if (apiDocsOverlay != null && !apiDocsOverlay.dockSide().docked()) {
			apiDocsOverlay.render(graphics, mouseX, mouseY);
		}
		if (textJsonOverlay != null && !textJsonOverlay.dockSide().docked()) {
			textJsonOverlay.render(graphics, mouseX, mouseY);
		}
		if (componentEditorOverlay != null && !componentEditorOverlay.dockSide().docked()) {
			componentEditorOverlay.render(graphics, mouseX, mouseY);
		}
		if (fileOverlay != null && !fileOverlay.dockSide().docked()) {
			fileOverlay.render(graphics, mouseX, mouseY);
		}
	}

	private void extractToolWindow(ToolWindow tool, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		switch (tool) {
			case MANUAL -> extractManual(graphics, mouseX, mouseY);
			case INSERT -> insertOverlay.render(graphics, mouseX, mouseY);
			case API_DOCS -> apiDocsOverlay.render(graphics, mouseX, mouseY);
			case COMPONENT_EDITOR -> componentEditorOverlay.render(graphics, mouseX, mouseY);
			case TEXT_JSON -> textJsonOverlay.render(graphics, mouseX, mouseY);
			case FILES -> fileOverlay.render(graphics, mouseX, mouseY);
			case OUTPUT -> {
				if (isOutputRendered()) extractOutputConsole(graphics, mouseX, mouseY);
			}
		}
	}

	private void extractDockTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (DockSide side : List.of(DockSide.LEFT, DockSide.RIGHT, DockSide.BOTTOM)) {
			List<ToolWindow> tools = dockedToolsAt(side);
			if (tools.size() < 2) continue;
			DockRegion region = dockRegion(side);
			if (region == null) continue;
			graphics.fill(region.x(), region.y(), region.right(), region.y() + DOCK_TAB_HEIGHT, 0xFF111821);
			ToolWindow active = activeToolAt(side);
			int activeIndex = Math.max(0, tools.indexOf(active));
			float animatedIndex = animateDockTabIndex(side, activeIndex);
			for (int index = 0; index < tools.size(); index++) {
				int left = region.x() + region.width() * index / tools.size();
				int right = region.x() + region.width() * (index + 1) / tools.size();
				ToolWindow tool = tools.get(index);
				boolean hovered = mouseX >= left && mouseX < right
						&& mouseY >= region.y() && mouseY < region.y() + DOCK_TAB_HEIGHT;
				boolean selected = tool == active;
				graphics.fill(left, region.y(), right, region.y() + DOCK_TAB_HEIGHT,
						selected ? 0xFF243A50 : hovered ? 0xFF1D2B39 : 0xFF151E28);
				if (index > 0) graphics.fill(left, region.y() + 4, left + 1,
						region.y() + DOCK_TAB_HEIGHT - 4, 0xFF354454);
				String label = font.plainSubstrByWidth(dockTabLabel(tool), Math.max(8, right - left - 10));
				graphics.centeredText(font, label, (left + right) / 2, region.y() + 7,
						selected ? 0xFFFFFFFF : 0xFF9DAEBD);
			}
			float tabWidth = region.width() / (float) tools.size();
			int indicatorLeft = Math.round(region.x() + animatedIndex * tabWidth) + 1;
			int indicatorRight = Math.round(indicatorLeft + tabWidth) - 2;
			graphics.fill(indicatorLeft, region.y() + DOCK_TAB_HEIGHT - 2,
					Math.max(indicatorLeft + 1, indicatorRight),
					region.y() + DOCK_TAB_HEIGHT, 0xFF68B8F5);
			graphics.outline(region.x(), region.y(), region.width(), DOCK_TAB_HEIGHT, 0xFF435567);
		}
	}

	private float animateDockTabIndex(DockSide side, int targetIndex) {
		float current = switch (side) {
			case LEFT -> animatedLeftTabIndex;
			case RIGHT -> animatedRightTabIndex;
			case BOTTOM -> animatedBottomTabIndex;
			case FLOATING -> targetIndex;
		};
		if (current < 0.0F) current = targetIndex;
		current += (targetIndex - current) * 0.32F;
		if (Math.abs(targetIndex - current) < 0.015F) current = targetIndex;
		switch (side) {
			case LEFT -> animatedLeftTabIndex = current;
			case RIGHT -> animatedRightTabIndex = current;
			case BOTTOM -> animatedBottomTabIndex = current;
			case FLOATING -> {
			}
		}
		return current;
	}

	private void extractDockContentTransition(DockSide side, GuiGraphicsExtractor graphics) {
		float progress = switch (side) {
			case LEFT -> leftDockReveal;
			case RIGHT -> rightDockReveal;
			case BOTTOM -> bottomDockReveal;
			case FLOATING -> 1.0F;
		};
		if (progress >= 0.999F) return;
		DockRegion region = dockRegion(side);
		if (region == null || region.width() <= 0 || region.height() <= 0) return;
		int top = region.y() + dockTabHeight(side);
		int bottom = region.y() + region.height();
		if (bottom <= top) return;
		float eased = progress * progress * (3.0F - 2.0F * progress);
		if (side == DockSide.LEFT) {
			int edge = region.x() + Math.round(region.width() * eased);
			graphics.fill(edge, top, region.right(), bottom, 0xE810151D);
			graphics.fill(Math.max(region.x(), edge - 2), top, edge, bottom, 0xFF68B8F5);
		} else if (side == DockSide.RIGHT) {
			int edge = region.right() - Math.round(region.width() * eased);
			graphics.fill(region.x(), top, edge, bottom, 0xE810151D);
			graphics.fill(edge, top, Math.min(region.right(), edge + 2), bottom, 0xFF68B8F5);
		} else {
			int edge = bottom - Math.round((bottom - top) * eased);
			graphics.fill(region.x(), top, region.right(), edge, 0xE810151D);
			graphics.fill(region.x(), edge, region.right(), Math.min(bottom, edge + 2), 0xFF68B8F5);
		}
	}

	private String dockTabLabel(ToolWindow tool) {
		return switch (tool) {
			case MANUAL -> "手册";
			case INSERT -> "插入";
			case API_DOCS -> "API";
			case COMPONENT_EDITOR -> "组件";
			case TEXT_JSON -> "文本";
			case FILES -> "文件";
			case OUTPUT -> "日志";
		};
	}

	private DockRegion dockRegion(DockSide side) {
		int workLeft = 8;
		int workRight = width - 8;
		int workBottom = height - WORKSPACE_BOTTOM_MARGIN;
		int leftExtent = renderedDockExtent(animatedLeftDockExtent);
		int rightExtent = renderedDockExtent(animatedRightDockExtent);
		int bottomExtent = renderedDockExtent(animatedBottomDockExtent);
		return switch (side) {
			case LEFT -> activeToolAt(side) == null || leftExtent <= 0 ? null
					: new DockRegion(workLeft, WORKSPACE_TOP, leftExtent, workBottom - WORKSPACE_TOP);
			case RIGHT -> activeToolAt(side) == null || rightExtent <= 0 ? null
					: new DockRegion(workRight - rightExtent, WORKSPACE_TOP,
						rightExtent, workBottom - WORKSPACE_TOP);
			case BOTTOM -> activeToolAt(side) == null || bottomExtent <= 0 ? null
					: new DockRegion(editorOuterX(), workBottom - bottomExtent,
						Math.max(1, editor.getRight() - editorOuterX()), bottomExtent);
			case FLOATING -> null;
		};
	}

	private boolean handleDockTabClick(double mouseX, double mouseY) {
		for (DockSide side : List.of(DockSide.LEFT, DockSide.RIGHT, DockSide.BOTTOM)) {
			List<ToolWindow> tools = dockedToolsAt(side);
			if (tools.size() < 2) continue;
			DockRegion region = dockRegion(side);
			if (region == null || mouseX < region.x() || mouseX >= region.right()
					|| mouseY < region.y() || mouseY >= region.y() + DOCK_TAB_HEIGHT) continue;
			int index = Math.min(tools.size() - 1, Math.max(0,
					(int) ((mouseX - region.x()) * tools.size() / Math.max(1, region.width()))));
			ToolWindow tool = tools.get(index);
			setSelectedDockTool(side, tool);
			if (fileOverlay != null && tool != ToolWindow.FILES) fileOverlay.releaseKeyboardFocus();
			if (apiDocsOverlay != null && tool != ToolWindow.API_DOCS) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null && tool != ToolWindow.INSERT) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null && tool != ToolWindow.COMPONENT_EDITOR) {
				componentEditorOverlay.releaseKeyboardFocus();
			}
			if (textJsonOverlay != null && tool != ToolWindow.TEXT_JSON) textJsonOverlay.releaseKeyboardFocus();
			applyDockLayout();
			status = "已切换到" + dockTabLabel(tool) + "窗口";
			return true;
		}
		return false;
	}

	private void extractMenusOnTop(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		for (Button button : topMenuButtons) {
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		for (Button button : visibleMenuButtons()) {
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		if (scaleInput != null && scaleInput.visible) {
			scaleInput.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		if (scaleApplyButton != null && scaleApplyButton.visible) {
			scaleApplyButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	private List<Button> visibleMenuButtons() {
		List<Button> visible = new ArrayList<>();
		for (List<Button> items : List.of(fileMenuItems, windowMenuItems, viewMenuItems,
				fontMenuItems, interfaceScaleMenuItems, themeMenuItems)) {
			for (Button button : items) if (button.visible) visible.add(button);
		}
		return visible;
	}

	private void extractDockPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (layoutLocked || draggingTool() == null) return;
		DockSide target = DockSide.atEdge(mouseX, mouseY, width, height);
		if (!target.docked()) return;
		int workLeft = 8;
		int workRight = width - 8;
		int workBottom = height - WORKSPACE_BOTTOM_MARGIN;
		int previewWidth = Math.min(390, Math.max(MIN_DOCK_SIDE_WIDTH, (int) (width * 0.31F)));
		int previewHeight = Math.min(250, Math.max(MIN_DOCK_BOTTOM_HEIGHT,
				(int) ((workBottom - WORKSPACE_TOP) * 0.34F)));
		int left;
		int top;
		int right;
		int bottom;
		if (target == DockSide.LEFT) {
			left = workLeft;
			top = WORKSPACE_TOP;
			right = Math.min(workRight, workLeft + previewWidth);
			bottom = workBottom;
		} else if (target == DockSide.RIGHT) {
			left = Math.max(workLeft, workRight - previewWidth);
			top = WORKSPACE_TOP;
			right = workRight;
			bottom = workBottom;
		} else {
			left = editorOuterX();
			top = Math.max(WORKSPACE_TOP, workBottom - previewHeight);
			right = editor.getRight();
			bottom = workBottom;
		}
		int alpha = 0x48 + Math.round((0.5F + 0.5F * (float) Math.sin(dockPreviewPulse)) * 0x24);
		graphics.fill(left, top, right, bottom, (alpha << 24) | 0x004D9EEA);
		int inset = 1 + Math.round((0.5F + 0.5F * (float) Math.sin(dockPreviewPulse)) * 2.0F);
		graphics.outline(left + inset, top + inset,
				Math.max(1, right - left - inset * 2), Math.max(1, bottom - top - inset * 2), 0xFF75C7FF);
		String action = activeToolAt(target) == null ? "释放以吸附到" : "释放以加入标签组：";
		graphics.centeredText(font, action + dockSideLabel(target),
				(left + right) / 2, top + Math.max(8, (bottom - top) / 2 - 4), 0xFFFFFFFF);
	}

	private ToolWindow draggingTool() {
		if (manualDragging) return ToolWindow.MANUAL;
		if (insertOverlay != null && insertOverlay.isDragging()) return ToolWindow.INSERT;
		if (apiDocsOverlay != null && apiDocsOverlay.isDragging()) return ToolWindow.API_DOCS;
		if (componentEditorOverlay != null && componentEditorOverlay.isDragging()) return ToolWindow.COMPONENT_EDITOR;
		if (textJsonOverlay != null && textJsonOverlay.isDragging()) return ToolWindow.TEXT_JSON;
		if (fileOverlay != null && fileOverlay.isDragging()) return ToolWindow.FILES;
		if (outputDragging) return ToolWindow.OUTPUT;
		return null;
	}

	private void extractDockDividers(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int workLeft = 8;
		int workRight = width - 8;
		int workBottom = height - WORKSPACE_BOTTOM_MARGIN;
		int normal = layoutLocked ? 0xFF59616B : 0xFF3F6F91;
		int hovered = 0xFF75C7FF;
		int leftExtent = renderedDockExtent(animatedLeftDockExtent);
		int rightExtent = renderedDockExtent(animatedRightDockExtent);
		int bottomExtent = renderedDockExtent(animatedBottomDockExtent);
		if (activeToolAt(DockSide.LEFT) != null) {
			int x = workLeft + leftExtent + 2;
			int color = !layoutLocked && Math.abs(mouseX - (workLeft + leftExtent)) <= DOCK_DIVIDER_HIT
					? hovered : normal;
			graphics.fill(x, WORKSPACE_TOP, x + 1, workBottom, color);
		}
		if (activeToolAt(DockSide.RIGHT) != null) {
			int x = workRight - rightExtent - 3;
			int color = !layoutLocked && Math.abs(mouseX - (workRight - rightExtent)) <= DOCK_DIVIDER_HIT
					? hovered : normal;
			graphics.fill(x, WORKSPACE_TOP, x + 1, workBottom, color);
		}
		if (activeToolAt(DockSide.BOTTOM) != null) {
			int y = workBottom - bottomExtent - 3;
			int color = !layoutLocked && Math.abs(mouseY - (workBottom - bottomExtent)) <= DOCK_DIVIDER_HIT
					? hovered : normal;
			graphics.fill(editorOuterX(), y, editor.getRight(), y + 1, color);
		}
	}

	private void extractLineNumbers(GuiGraphicsExtractor graphics, MultilineTextField field) {
		String source = field.value();
		int currentLine = LuaSyntaxHighlighter.logicalLineAt(editor, source, field.cursor());
		int codeLineHeight = scaledCodeLineHeight();
		int visualLine = 0;
		int previousLogicalLine = -1;
		int editorOuterX = editorOuterX();
		graphics.enableScissor(editorOuterX + 1, editor.getY() + 1,
				editor.getX() - 1, editor.getBottom() - 1);
		for (Object rawView : field.iterateLines()) {
			StringViewAccessor view = (StringViewAccessor) rawView;
			int begin = Math.max(0, Math.min(source.length(), view.mcLuaIde$getBeginIndex()));
			int logicalLine = LuaSyntaxHighlighter.logicalLineAt(editor, source, begin);
			int y = editor.getY() + 4 + visualLine * codeLineHeight - (int) editor.scrollAmount();
			if (logicalLine != previousLogicalLine && y + codeLineHeight >= editor.getY()
					&& y <= editor.getBottom()) {
				boolean error = errorLineNumbers.contains(logicalLine);
				if (logicalLine == currentLine) {
					graphics.fill(editorOuterX + 1, y - 1, editor.getX() - 1,
							y + codeLineHeight, error ? 0x553D1017 : currentLineBackground());
				}
				if (error) {
					graphics.fill(editorOuterX + 1, y - 1, editorOuterX + 4,
							y + codeLineHeight, 0xFFFF3F52);
				}
				drawLineNumber(graphics, Integer.toString(logicalLine), y,
						error ? 0xFFFF5555 : logicalLine == currentLine ? 0xFF7FC8FF : lineNumberColor());
			}
			previousLogicalLine = logicalLine;
			visualLine++;
		}
		graphics.disableScissor();
	}

	private void drawLineNumber(GuiGraphicsExtractor graphics, String number, int y, int color) {
		int scaledWidth = Math.round(font.width(number) * codeScale);
		int x = editor.getX() - 7 - scaledWidth;
		if (Math.abs(codeScale - 1.0F) < 0.001F) {
			graphics.text(font, number, x, y, color);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(codeScale, codeScale);
		graphics.text(font, number, 0, 0, color);
		graphics.pose().popMatrix();
	}

	private int scaledCodeLineHeight() {
		return Math.max(1, Math.round(font.lineHeight * codeScale));
	}

	private int lineNumberBackground() {
		return switch (theme) {
			case LIGHT -> 0xFFE0E5EB;
			case HIGH_CONTRAST -> 0xFF080808;
			case DARK -> 0xFF15191F;
		};
	}

	private int currentLineBackground() {
		return theme == LuaSyntaxHighlighter.Theme.LIGHT ? 0x332A78B8 : 0x333D8BC9;
	}

	private int lineNumberColor() {
		return theme == LuaSyntaxHighlighter.Theme.LIGHT ? 0xFF59636E : 0xFF87909B;
	}

	private void extractSearch(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (searchProgress <= 0.01F) return;
		layoutSearchBox();
		int x = searchPanelX();
		int y = searchPanelY();
		int panelWidth = searchPanelWidth();
		int right = x + panelWidth;
		graphics.fill(x - 3, y + 3, right + 3, y + SEARCH_HEIGHT + 3, 0x65000000);
		graphics.fill(x, y, right, y + SEARCH_HEIGHT, 0xFA111923);
		graphics.outline(x, y, panelWidth, SEARCH_HEIGHT, 0xFF66B3FF);
		graphics.text(font, "⌕", x + 8, y + 10, 0xFF78BEFF);

		int closeLeft = right - 25;
		int nextLeft = closeLeft - 25;
		int previousLeft = nextLeft - 25;
		int countLeft = previousLeft - 55;
		if (searchBox != null) searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);

		String count = searchMatches.isEmpty() ? "0 / 0" : (selectedSearchMatch + 1) + " / " + searchMatches.size();
		graphics.centeredText(font, count, countLeft + 27, y + 11,
				searchQuery.isEmpty() || !searchMatches.isEmpty() ? 0xFF9EB0C3 : 0xFFFF6B6B);
		renderSearchButton(graphics, mouseX, mouseY, previousLeft, y + 5, "↑", false);
		renderSearchButton(graphics, mouseX, mouseY, nextLeft, y + 5, "↓", false);
		renderSearchButton(graphics, mouseX, mouseY, closeLeft, y + 5, "×", true);
	}

	private void layoutSearchBox() {
		if (searchBox == null) return;
		int x = searchPanelX();
		int y = searchPanelY();
		int right = x + searchPanelWidth();
		int countLeft = right - 25 - 25 - 25 - 55;
		searchBox.setX(x + 25);
		searchBox.setY(y + 5);
		searchBox.setWidth(Math.max(40, countLeft - 4 - (x + 25)));
		searchBox.visible = searchProgress > 0.01F;
		searchBox.active = searchOpen;
	}

	private void extractErrorHighlights(GuiGraphicsExtractor graphics, MultilineTextField field) {
		if (editorErrorRanges.isEmpty()) return;
		String source = field.value();
		int codeLineHeight = scaledCodeLineHeight();
		int visualLine = 0;
		graphics.enableScissor(editor.getX() + 1, editor.getY() + 1,
				editor.getRight() - 1, editor.getBottom() - 1);
		for (Object rawView : field.iterateLines()) {
			StringViewAccessor view = (StringViewAccessor) rawView;
			int begin = Math.max(0, Math.min(source.length(), view.mcLuaIde$getBeginIndex()));
			int end = Math.max(begin, Math.min(source.length(), view.mcLuaIde$getEndIndex()));
			int y = editor.getY() + 4 + visualLine * codeLineHeight - (int) editor.scrollAmount();
			if (y + codeLineHeight >= editor.getY() && y <= editor.getBottom()) {
				for (LuaSyntaxHighlighter.ErrorRange range : editorErrorRanges) {
					if (range.end() <= begin) continue;
					if (range.start() >= end) break;
					int partStart = Math.max(begin, range.start());
					int partEnd = Math.min(end, range.end());
					if (partStart >= partEnd) continue;
					int left = editor.getX() + 5
							+ Math.round(font.width(source.substring(begin, partStart)) * codeScale);
					int right = left + Math.max(2,
							Math.round(font.width(source.substring(partStart, partEnd)) * codeScale));
					int rowBottom = Math.min(editor.getBottom(), y + codeLineHeight);
					graphics.fill(left, y - 1, right, rowBottom, 0x44FF3045);
					graphics.fill(left, Math.max(y, rowBottom - 2), right, rowBottom, 0xFFFF4D62);
				}
			}
			visualLine++;
		}
		graphics.disableScissor();
	}

	private void extractSearchHighlights(GuiGraphicsExtractor graphics, MultilineTextField field) {
		if (!searchOpen || searchQuery.isEmpty() || searchMatches.isEmpty()) return;
		String source = field.value();
		int queryLength = searchQuery.length();
		int codeLineHeight = scaledCodeLineHeight();
		int visualLine = 0;
		int matchIndex = 0;
		graphics.enableScissor(editor.getX() + 1, editor.getY() + 1, editor.getRight() - 1, editor.getBottom() - 1);
		for (Object rawView : field.iterateLines()) {
			StringViewAccessor view = (StringViewAccessor) rawView;
			int begin = Math.max(0, Math.min(source.length(), view.mcLuaIde$getBeginIndex()));
			int end = Math.max(begin, Math.min(source.length(), view.mcLuaIde$getEndIndex()));
			while (matchIndex < searchMatches.size() && searchMatches.get(matchIndex) + queryLength <= begin) {
				matchIndex++;
			}
			int y = editor.getY() + 4 + visualLine * codeLineHeight - (int) editor.scrollAmount();
			if (y + codeLineHeight >= editor.getY() && y <= editor.getBottom()) {
				for (int index = matchIndex; index < searchMatches.size(); index++) {
					int matchStart = searchMatches.get(index);
					if (matchStart >= end) break;
					int partStart = Math.max(begin, matchStart);
					int partEnd = Math.min(end, matchStart + queryLength);
					if (partStart >= partEnd) continue;
					int left = editor.getX() + 5 + Math.round(font.width(source.substring(begin, partStart)) * codeScale);
					int right = left + Math.max(2,
							Math.round(font.width(source.substring(partStart, partEnd)) * codeScale));
					int color = index == selectedSearchMatch ? 0xB04A86C5 : 0x805E551A;
					graphics.fill(left, y, right, y + codeLineHeight, color);
				}
			}
			visualLine++;
		}
		graphics.disableScissor();
	}

	private void renderSearchButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, String label, boolean destructive) {
		boolean hovered = insideSearch(mouseX, mouseY, left, top, left + 20, top + 20);
		int color = hovered ? (destructive ? 0xFFB83A46 : 0xFF285A8C) : 0xFF283746;
		graphics.fill(left, top, left + 20, top + 20, color);
		graphics.centeredText(font, label, left + 10, top + 6, 0xFFFFFFFF);
	}

	private void extractOutputConsole(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ensureOutputGeometry();
		int left = outputWindowX;
		int top = outputPanelY();
		int right = left + outputWindowWidth;
		int bottom = top + outputWindowHeight;
		graphics.fill(left - 4, top + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(left, top, right, bottom, 0xF5080B10);
		graphics.fill(left + 1, top + 1, right - 1, top + OUTPUT_TITLE_HEIGHT, 0xFF18222F);
		graphics.outline(left, top, outputWindowWidth, outputWindowHeight, 0xFF687584);
		String state = runningExecution != null && runningExecution.isRunning() ? "运行中" : "运行日志";
		graphics.text(font, "OUTPUT · " + state, left + 9, top + 8,
				runningExecution != null && runningExecution.isRunning() ? 0xFF7ED6A5 : 0xFF8BA7C7);
		int closeLeft = right - 25;
		String windowHint = font.plainSubstrByWidth("标题栏拖至边缘吸附 · 边框缩放",
				Math.max(0, closeLeft - left - 110));
		if (!windowHint.isEmpty()) graphics.text(font, windowHint, left + 104, top + 8, 0xFF758393);
		boolean closeHovered = mouseX >= closeLeft && mouseX <= right - 5
				&& mouseY >= top + 3 && mouseY <= top + 20;
		graphics.fill(closeLeft, top + 3, right - 5, top + 20, closeHovered ? 0xFFB83A46 : 0xFF303640);
		graphics.centeredText(font, "×", closeLeft + 10, top + 5, 0xFFFFFFFF);

		int contentLeft = left + 9;
		int contentTop = top + OUTPUT_TITLE_HEIGHT + 5;
		int contentRight = right - 10;
		int contentBottom = bottom - 7;
		List<OutputVisualLine> visualLines = outputVisualLines(Math.max(20, contentRight - contentLeft - 7));
		int maxLines = Math.max(1, (contentBottom - contentTop) / font.lineHeight);
		int maxScroll = Math.max(0, visualLines.size() - maxLines);
		outputScrollLines = Math.max(0, Math.min(maxScroll, outputScrollLines));
		int lastLine = Math.max(0, visualLines.size() - outputScrollLines);
		int firstLine = Math.max(0, lastLine - maxLines);
		int y = contentTop;
		graphics.enableScissor(left + 2, contentTop - 1, right - 2, contentBottom + 1);
		for (int index = firstLine; index < lastLine; index++) {
			OutputVisualLine line = visualLines.get(index);
			graphics.text(font, line.text(), contentLeft, y, line.color());
			y += font.lineHeight;
		}
		graphics.disableScissor();

		if (maxScroll > 0) {
			int trackTop = contentTop;
			int trackHeight = Math.max(8, contentBottom - contentTop);
			int thumbHeight = Math.max(10, trackHeight * maxLines / Math.max(maxLines, visualLines.size()));
			int thumbY = trackTop + (trackHeight - thumbHeight) * (maxScroll - outputScrollLines)
					/ Math.max(1, maxScroll);
			graphics.fill(right - 6, trackTop, right - 3, trackTop + trackHeight, 0xFF202731);
			graphics.fill(right - 6, thumbY, right - 3, thumbY + thumbHeight, 0xFF5F9FD5);
		}

		int hoveredEdges = outputResizeEdges != 0 ? outputResizeEdges
				: outputInteractive() ? outputResizeEdgesAt(mouseX, mouseY) : 0;
		int edgeColor = 0xFF75C7FF;
		if ((hoveredEdges & RESIZE_LEFT) != 0) graphics.fill(left - 1, top, left + 2, bottom, edgeColor);
		if ((hoveredEdges & RESIZE_RIGHT) != 0) graphics.fill(right - 2, top, right + 1, bottom, edgeColor);
		if ((hoveredEdges & RESIZE_TOP) != 0) graphics.fill(left, top - 1, right, top + 2, edgeColor);
		if ((hoveredEdges & RESIZE_BOTTOM) != 0) graphics.fill(left, bottom - 2, right, bottom + 1, edgeColor);
		graphics.fill(right - 13, bottom - 4, right - 3, bottom - 2, 0xFF75C7FF);
		graphics.fill(right - 9, bottom - 8, right - 3, bottom - 6, 0xFF75C7FF);
	}

	private List<OutputVisualLine> outputVisualLines(int maxWidth) {
		List<OutputVisualLine> visualLines = new ArrayList<>();
		for (String rawLine : outputLines) {
			int color = rawLine.startsWith("✗") ? 0xFFFF6868
					: rawLine.startsWith("✓") ? 0xFF62E68A
					: rawLine.startsWith("▶") || rawLine.startsWith("■") ? 0xFF7FC8FF : 0xFFDDDDDD;
			List<FormattedCharSequence> wrapped = font.split(Component.literal(rawLine), maxWidth);
			if (wrapped.isEmpty()) {
				visualLines.add(new OutputVisualLine(Component.literal(" ").getVisualOrderText(), color));
			} else {
				for (FormattedCharSequence line : wrapped) visualLines.add(new OutputVisualLine(line, color));
			}
		}
		return visualLines;
	}

	@Override
	public void tick() {
		if (pendingInterfaceScale != null) {
			interfaceScale = pendingInterfaceScale;
			pendingInterfaceScale = null;
			rememberedInterfaceScale = interfaceScale;
			applyLogicalDimensions();
			rebuildWidgets();
			status = "整个 IDE 界面已缩放为 " + pendingInterfacePercent + "%";
		}
		super.tick();
		syncExecutionState();
		if (insertOverlay != null) insertOverlay.tick();
		if (apiDocsOverlay != null) apiDocsOverlay.tick();
		if (componentEditorOverlay != null) componentEditorOverlay.tick();
		if (textJsonOverlay != null) textJsonOverlay.tick();
		if (fileOverlay != null) fileOverlay.tick();
		float target = manualOpen ? 1.0F : 0.0F;
		manualProgress += (target - manualProgress) * 0.28F;
		if (Math.abs(target - manualProgress) < 0.01F) {
			manualProgress = target;
		}
		float searchTarget = searchOpen ? 1.0F : 0.0F;
		searchProgress += (searchTarget - searchProgress) * 0.32F;
		if (Math.abs(searchTarget - searchProgress) < 0.01F) searchProgress = searchTarget;
		float outputTarget = outputVisible ? 1.0F : 0.0F;
		outputProgress += (outputTarget - outputProgress) * 0.24F;
		if (Math.abs(outputTarget - outputProgress) < 0.01F) outputProgress = outputTarget;
		if (!manualDragging && manualResizeEdges == 0) {
			WindowBoundsAnimation.Bounds bounds = manualBoundsAnimation.tick(
					manualWindowX, manualWindowY, manualWindowWidth, manualWindowHeight);
			manualWindowX = bounds.x();
			manualWindowY = bounds.y();
			manualWindowWidth = bounds.width();
			manualWindowHeight = bounds.height();
		}
		if (!outputDragging && outputResizeEdges == 0) {
			WindowBoundsAnimation.Bounds bounds = outputBoundsAnimation.tick(
					outputWindowX, outputWindowY, outputWindowWidth, outputWindowHeight);
			outputWindowX = bounds.x();
			outputWindowY = bounds.y();
			outputWindowWidth = bounds.width();
			outputWindowHeight = bounds.height();
		}
		leftDockReveal = Math.min(1.0F, leftDockReveal + 0.19F);
		rightDockReveal = Math.min(1.0F, rightDockReveal + 0.19F);
		bottomDockReveal = Math.min(1.0F, bottomDockReveal + 0.19F);
		dockPreviewPulse = (dockPreviewPulse + 0.075F) % ((float) Math.PI * 2.0F);
		advanceWorkspaceLayoutAnimation();
		applyDockLayout();
		if (workspaceLayoutSavePending && System.nanoTime() >= workspaceLayoutSaveDueNanos) {
			flushWorkspaceLayout();
		}
	}

	private int searchPanelWidth() {
		return Math.max(260, Math.min(430, width - 24));
	}

	private int searchPanelX() {
		return Math.max(12, width - searchPanelWidth() - 12);
	}

	private int searchPanelY() {
		float eased = 1.0F - (1.0F - searchProgress) * (1.0F - searchProgress);
		return Math.round(28 + (56 - 28) * eased);
	}

	private boolean pointInsideSearch(double mouseX, double mouseY) {
		int x = searchPanelX();
		int y = searchPanelY();
		return searchProgress > 0.5F && insideSearch(mouseX, mouseY, x, y, x + searchPanelWidth(), y + SEARCH_HEIGHT);
	}

	private boolean handleSearchMouseClicked(MouseButtonEvent event) {
		if (!pointInsideSearch(event.x(), event.y())) return false;
		int x = searchPanelX();
		int y = searchPanelY();
		int right = x + searchPanelWidth();
		int closeLeft = right - 25;
		int nextLeft = closeLeft - 25;
		int previousLeft = nextLeft - 25;
		int countLeft = previousLeft - 55;
		if (insideSearch(event.x(), event.y(), closeLeft, y + 5, closeLeft + 20, y + 25)) {
			closeSearch();
		} else if (insideSearch(event.x(), event.y(), nextLeft, y + 5, nextLeft + 20, y + 25)) {
			moveSearchMatch(1);
		} else if (insideSearch(event.x(), event.y(), previousLeft, y + 5, previousLeft + 20, y + 25)) {
			moveSearchMatch(-1);
		} else if (insideSearch(event.x(), event.y(), x + 3, y + 3, x + 24, y + 27)) {
			useSelectionAsSearchQuery();
		} else if (insideSearch(event.x(), event.y(), x + 25, y + 5, countLeft - 4, y + 25)) {
			return false;
		}
		return true;
	}

	private static boolean insideSearch(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		return mouseScrolledLogical(logicalMouse(mouseX), logicalMouse(mouseY), horizontalAmount, verticalAmount);
	}

	private boolean mouseScrolledLogical(double mouseX, double mouseY,
			double horizontalAmount, double verticalAmount) {
		ensureManualGeometry();
		boolean floatingToolHit = pointInsideFloatingTool(mouseX, mouseY);
		if (fileOverlay != null && toolCanInteract(ToolWindow.FILES)
				&& (!fileOverlay.dockSide().docked() || !floatingToolHit)
				&& fileOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (componentEditorOverlay != null && toolCanInteract(ToolWindow.COMPONENT_EDITOR)
				&& (!componentEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& componentEditorOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (textJsonOverlay != null && toolCanInteract(ToolWindow.TEXT_JSON)
				&& (!textJsonOverlay.dockSide().docked() || !floatingToolHit)
				&& textJsonOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (pointInsideSearch(mouseX, mouseY)) return true;
		if (apiDocsOverlay != null && toolCanInteract(ToolWindow.API_DOCS)
				&& (!apiDocsOverlay.dockSide().docked() || !floatingToolHit)
				&& apiDocsOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		boolean manualHit = manualProgress > 0.5F && toolCanInteract(ToolWindow.MANUAL)
				&& pointInsideManual(mouseX, mouseY);
		boolean insertHit = insertOverlay != null && toolCanInteract(ToolWindow.INSERT)
				&& insertOverlay.contains(mouseX, mouseY);
		if (manualOnTop && manualHit && (!manualDock.docked() || !floatingToolHit)) {
			manualScroll = Math.max(0, Math.min(maxManualScroll(),
					manualScroll - (int) Math.round(verticalAmount * 30)));
			return true;
		}
		if (!manualOnTop && insertHit && (!insertOverlay.dockSide().docked() || !floatingToolHit)
				&& insertOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (manualHit && (!manualDock.docked() || !floatingToolHit)) {
			manualOnTop = true;
			manualScroll = Math.max(0, Math.min(maxManualScroll(),
					manualScroll - (int) Math.round(verticalAmount * 30)));
			return true;
		}
		if (insertHit && (!insertOverlay.dockSide().docked() || !floatingToolHit)) {
			manualOnTop = false;
			return insertOverlay.mouseScrolled(mouseX, mouseY, verticalAmount);
		}
		if (outputInteractive() && toolCanInteract(ToolWindow.OUTPUT) && pointInsideOutput(mouseX, mouseY)
				&& (!outputDock.docked() || !floatingToolHit)) {
			int steps = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * 3.0));
			outputScrollLines = Math.max(0,
					outputScrollLines + (verticalAmount > 0 ? steps : -steps));
			return true;
		}
		if (isOutputRendered() && toolCanInteract(ToolWindow.OUTPUT)
				&& pointInsideOutput(mouseX, mouseY)) return true;
		if (!suggestions.isEmpty() && suggestionPopupContains(mouseX, mouseY)) {
			int steps = Math.max(1, (int) Math.round(Math.abs(verticalAmount)));
			moveSuggestionSelection(verticalAmount > 0 ? -steps : steps);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent logicalEvent = logicalEvent(event);
		return mouseClickedLogical(logicalEvent, doubleClick);
	}

	private boolean mouseClickedLogical(MouseButtonEvent event, boolean doubleClick) {
		ensureManualGeometry();
		if (menuWidgetAt(event.x(), event.y())) {
			return super.mouseClicked(event, doubleClick);
		}
		boolean floatingToolHit = pointInsideFloatingTool(event.x(), event.y());
		if (event.button() == 0 && !floatingToolHit && handleDockTabClick(event.x(), event.y())) return true;
		if (event.button() == 0 && !floatingToolHit && beginDockResize(event.x(), event.y())) return true;
		if (fileOverlay != null && toolCanInteract(ToolWindow.FILES)
				&& (!fileOverlay.dockSide().docked() || !floatingToolHit)
				&& fileOverlay.mouseClicked(event)) {
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (componentEditorOverlay != null && toolCanInteract(ToolWindow.COMPONENT_EDITOR)
				&& (!componentEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& componentEditorOverlay.mouseClicked(event, doubleClick)) {
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (textJsonOverlay != null && toolCanInteract(ToolWindow.TEXT_JSON)
				&& (!textJsonOverlay.dockSide().docked() || !floatingToolHit)
				&& textJsonOverlay.mouseClicked(event)) {
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (pointInsideSearch(event.x(), event.y())) {
			if (handleSearchMouseClicked(event)) return true;
			setFocused(searchBox);
			searchBox.setFocused(true);
			return searchBox.mouseClicked(event, doubleClick);
		}
		if (apiDocsOverlay != null && toolCanInteract(ToolWindow.API_DOCS)
				&& (!apiDocsOverlay.dockSide().docked() || !floatingToolHit)
				&& apiDocsOverlay.mouseClicked(event, doubleClick)) {
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		boolean manualHit = manualProgress > 0.5F && toolCanInteract(ToolWindow.MANUAL)
				&& (pointInsideManual(event.x(), event.y()) || manualResizeEdgesAt(event.x(), event.y()) != 0);
		boolean insertHit = insertOverlay != null && toolCanInteract(ToolWindow.INSERT)
				&& insertOverlay.contains(event.x(), event.y());
		if (!manualOnTop && insertHit && (!insertOverlay.dockSide().docked() || !floatingToolHit)
				&& insertOverlay.mouseClicked(event)) {
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (!manualHit && insertHit && (!insertOverlay.dockSide().docked() || !floatingToolHit)
				&& insertOverlay.mouseClicked(event)) {
			manualOnTop = false;
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (manualHit && (!manualDock.docked() || !floatingToolHit)) manualOnTop = true;
		int panelX = manualPanelX();
		int panelRight = panelX + manualWindowWidth;
		int panelBottom = manualWindowY + manualWindowHeight;
		if (manualProgress > 0.5F && toolCanInteract(ToolWindow.MANUAL)
				&& (!manualDock.docked() || !floatingToolHit)) {
			if (event.x() >= panelRight - 25 && event.x() <= panelRight - 5
					&& event.y() >= manualWindowY + 4 && event.y() <= manualWindowY + 24) {
				manualOpen = false;
				return true;
			}
			for (ManualCopyTarget target : manualCopyTargets) {
				if (target.contains(event.x(), event.y())) {
					minecraft.keyboardHandler.setClipboard(target.source());
					status = "已复制手册中的 " + target.language() + " 示例代码";
					return true;
				}
			}

			int edges = manualResizeEdgesAt(event.x(), event.y());
			if (edges != 0) {
				manualResizeEdges = edges;
				manualResizeStartMouseX = event.x();
				manualResizeStartMouseY = event.y();
				manualResizeStartX = panelX;
				manualResizeStartY = manualWindowY;
				manualResizeStartWidth = manualWindowWidth;
				manualResizeStartHeight = manualWindowHeight;
				return true;
			}

			if (pointInsideManual(event.x(), event.y())) {
				if (event.y() <= manualWindowY + MANUAL_TITLE_HEIGHT) {
					if (manualDock.docked() && layoutLocked) return true;
					manualDragging = true;
					manualDragOffsetX = event.x() - panelX;
					manualDragOffsetY = event.y() - manualWindowY;
				} else if (event.y() >= manualContentTop() && event.y() <= panelBottom) {
					int offset = manualOffsetAt(event.x(), event.y());
					if (doubleClick) {
						selectManualWord(offset);
					} else {
						manualSelectionAnchor = offset;
						manualSelectionCaret = offset;
						manualSelecting = true;
					}
				}
				return true;
			}
		}
		if (outputInteractive() && toolCanInteract(ToolWindow.OUTPUT)
				&& (!outputDock.docked() || !floatingToolHit)) {
			ensureOutputGeometry();
			if (outputCloseContains(event.x(), event.y())) {
				closeOutputConsole();
				return true;
			}
			int edges = outputResizeEdgesAt(event.x(), event.y());
			if (event.button() == 0 && edges != 0) {
				outputResizeEdges = edges;
				outputResizeStartMouseX = event.x();
				outputResizeStartMouseY = event.y();
				outputResizeStartX = outputWindowX;
				outputResizeStartY = outputWindowY;
				outputResizeStartWidth = outputWindowWidth;
				outputResizeStartHeight = outputWindowHeight;
				return true;
			}
			if (pointInsideOutput(event.x(), event.y())) {
				if (event.button() == 0 && event.y() <= outputWindowY + OUTPUT_TITLE_HEIGHT) {
					if (outputDock.docked() && layoutLocked) return true;
					outputDragging = true;
					outputDragOffsetX = event.x() - outputWindowX;
					outputDragOffsetY = event.y() - outputWindowY;
				}
				return true;
			}
		}
		if (isOutputRendered() && toolCanInteract(ToolWindow.OUTPUT)
				&& pointInsideOutput(event.x(), event.y())) return true;
		if (event.button() == 0) {
			int suggestionIndex = suggestionIndexAt(event.x(), event.y());
			if (suggestionIndex >= 0) {
				selectedSuggestion = suggestionIndex;
				animatedSuggestionSelection = suggestionIndex;
				applySelectedSuggestion();
				return true;
			}
		}
		if (event.button() == 0 && event.hasControlDown()
				&& pointInsideEditor(event.x(), event.y())) {
			String symbol = apiSymbolAt(textField(), event.x(), event.y());
			if (openApiDocumentation(symbol)) return true;
		}
		boolean editorHit = event.button() == 0 && pointInsideEditor(event.x(), event.y());
		if (editorHit) {
			if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			syncOverlayState();
		}
		boolean handled = super.mouseClicked(event, doubleClick);
		if (editorHit && doubleClick && editor.isFocused()) smartSelectEditorToken();
		if (editorHit && editor.isFocused()) {
			// MultiLineEditBox updates its cursor during mouseClicked. Refresh afterwards so the
			// popup follows the new caret and never keeps results from the previous token.
			refreshSuggestions();
		}
		return handled;
	}

	private void smartSelectEditorToken() {
		MultilineTextField field=textField();
		String source=field.value();
		if(source.isEmpty()) return;
		MultilineTextFieldAccessor access=(MultilineTextFieldAccessor)field;
		int a=Math.min(access.mcLuaIde$getCursor(),access.mcLuaIde$getSelectCursor());
		int b=Math.max(access.mcLuaIde$getCursor(),access.mcLuaIde$getSelectCursor());
		int offset=Math.max(0,Math.min(source.length()-1,a==b?a:(a+b-1)/2));
		char c=source.charAt(offset);
		int start=offset,end=offset+1;
		if(Character.isLetterOrDigit(c)||c=='_') {
			while(start>0 && isEditorIdentifierCharacter(source.charAt(start-1))) start--;
			while(end<source.length() && isEditorIdentifierCharacter(source.charAt(end))) end++;
		} else if(Character.isWhitespace(c)) {
			while(start>0 && Character.isWhitespace(source.charAt(start-1)) && source.charAt(start-1)!='\n') start--;
			while(end<source.length() && Character.isWhitespace(source.charAt(end)) && source.charAt(end)!='\n') end++;
		} else if(c=='"' || c=='\'') {
			char quote=c;
			int close=offset+1;
			while(close<source.length() && source.charAt(close)!=quote && source.charAt(close)!='\n') {
				if(source.charAt(close)=='\\' && close+1<source.length()) close+=2; else close++;
			}
			if(close<source.length() && source.charAt(close)==quote) { start=offset; end=close+1; }
		}
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE,start);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE,end);
		field.setSelecting(false);
	}

	private static boolean isEditorIdentifierCharacter(char c) {
		return Character.isLetterOrDigit(c)||c=='_';
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		MouseButtonEvent logicalEvent = logicalEvent(event);
		return mouseDraggedLogical(logicalEvent, dragX / interfaceScale, dragY / interfaceScale);
	}

	private boolean mouseDraggedLogical(MouseButtonEvent event, double dragX, double dragY) {
		if (activeDockResize.docked()) {
			resizeDock(event.x(), event.y());
			return true;
		}
		if (fileOverlay != null && fileOverlay.isInteracting() && fileOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (apiDocsOverlay != null && apiDocsOverlay.isInteracting() && apiDocsOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (componentEditorOverlay != null && componentEditorOverlay.isInteracting()
				&& componentEditorOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (textJsonOverlay != null && textJsonOverlay.isInteracting()
				&& textJsonOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (outputResizeEdges != 0) {
			resizeOutput(event.x(), event.y());
			return true;
		}
		if (outputDragging) {
			if (outputDock.docked()) undockOutputForDrag(event.x(), event.y());
			outputWindowX = Math.max(0, Math.min(width - outputWindowWidth,
					(int) Math.round(event.x() - outputDragOffsetX)));
			outputWindowY = Math.max(0, Math.min(height - outputWindowHeight,
					(int) Math.round(event.y() - outputDragOffsetY)));
			outputBoundsAnimation.snapTo(outputWindowX, outputWindowY,
					outputWindowWidth, outputWindowHeight);
			applyDockLayout();
			return true;
		}
		if (manualResizeEdges != 0) {
			resizeManual(event.x(), event.y());
			return true;
		}
		if (manualDragging) {
			if (manualDock.docked()) undockManualForDrag(event.x(), event.y());
			manualWindowX = Math.max(0, Math.min(width - manualWindowWidth,
					(int) Math.round(event.x() - manualDragOffsetX)));
			manualWindowY = Math.max(0, Math.min(height - manualWindowHeight,
					(int) Math.round(event.y() - manualDragOffsetY)));
			manualBoundsAnimation.snapTo(manualWindowX, manualWindowY,
					manualWindowWidth, manualWindowHeight);
			applyDockLayout();
			return true;
		}
		if (manualSelecting) {
			int contentTop = manualContentTop();
			int contentBottom = manualWindowY + manualWindowHeight - 3;
			if (event.y() < contentTop + 8) {
				manualScroll = Math.max(0, manualScroll - 12);
			} else if (event.y() > contentBottom - 8) {
				manualScroll = Math.min(maxManualScroll(), manualScroll + 12);
			}
			manualSelectionCaret = manualOffsetAt(event.x(), event.y());
			return true;
		}
		if (insertOverlay != null && insertOverlay.isInteracting() && insertOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return mouseReleasedLogical(logicalEvent(event));
	}

	private boolean mouseReleasedLogical(MouseButtonEvent event) {
		if (activeDockResize.docked()) {
			activeDockResize = DockSide.FLOATING;
			applyDockLayout();
			return true;
		}
		if (fileOverlay != null && fileOverlay.isInteracting()) {
			boolean wasDragging = fileOverlay.isDragging();
			if (!fileOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.FILES, wasDragging, event.x(), event.y());
			syncOverlayState();
			return true;
		}
		if (apiDocsOverlay != null && apiDocsOverlay.isInteracting()) {
			boolean wasDragging = apiDocsOverlay.isDragging();
			if (!apiDocsOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.API_DOCS, wasDragging, event.x(), event.y());
			return true;
		}
		if (componentEditorOverlay != null && componentEditorOverlay.isInteracting()) {
			boolean wasDragging = componentEditorOverlay.isDragging();
			if (!componentEditorOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.COMPONENT_EDITOR, wasDragging, event.x(), event.y());
			syncOverlayState();
			return true;
		}
		if (textJsonOverlay != null && textJsonOverlay.isInteracting()) {
			boolean wasDragging = textJsonOverlay.isDragging();
			if (!textJsonOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.TEXT_JSON, wasDragging, event.x(), event.y());
			syncOverlayState();
			return true;
		}
		if (outputDragging || outputResizeEdges != 0) {
			boolean wasDragging = outputDragging;
			outputDragging = false;
			outputResizeEdges = 0;
			finishToolDrag(ToolWindow.OUTPUT, wasDragging, event.x(), event.y());
			return true;
		}
		if (manualDragging || manualResizeEdges != 0) {
			boolean wasDragging = manualDragging;
			manualDragging = false;
			manualResizeEdges = 0;
			finishToolDrag(ToolWindow.MANUAL, wasDragging, event.x(), event.y());
			return true;
		}
		if (manualSelecting) {
			manualSelecting = false;
			if (hasManualSelection()) {
				status = "已选择手册文字，按 Ctrl+C 复制";
			}
			return true;
		}
		if (insertOverlay != null && insertOverlay.isInteracting()) {
			boolean wasDragging = insertOverlay.isDragging();
			if (!insertOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.INSERT, wasDragging, event.x(), event.y());
			syncOverlayState();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		super.mouseMoved(logicalMouse(mouseX), logicalMouse(mouseY));
	}

	private MouseButtonEvent logicalEvent(MouseButtonEvent event) {
		return new MouseButtonEvent(logicalMouse(event.x()), logicalMouse(event.y()), event.buttonInfo());
	}

	private boolean menuWidgetAt(double mouseX, double mouseY) {
		for (Button button : topMenuButtons) {
			if (button.visible && button.isMouseOver(mouseX, mouseY)) return true;
		}
		for (Button button : visibleMenuButtons()) {
			if (button.isMouseOver(mouseX, mouseY)) return true;
		}
		return scaleInput != null && scaleInput.visible && scaleInput.isMouseOver(mouseX, mouseY)
				|| scaleApplyButton != null && scaleApplyButton.visible
				&& scaleApplyButton.isMouseOver(mouseX, mouseY);
	}

	private boolean pointInsideFloatingTool(double mouseX, double mouseY) {
		if (fileOverlay != null && !fileOverlay.dockSide().docked()
				&& fileOverlay.contains(mouseX, mouseY)) return true;
		if (apiDocsOverlay != null && !apiDocsOverlay.dockSide().docked()
				&& apiDocsOverlay.contains(mouseX, mouseY)) return true;
		if (componentEditorOverlay != null && !componentEditorOverlay.dockSide().docked()
				&& componentEditorOverlay.contains(mouseX, mouseY)) return true;
		if (textJsonOverlay != null && !textJsonOverlay.dockSide().docked()
				&& textJsonOverlay.contains(mouseX, mouseY)) return true;
		if (manualProgress > 0.01F && !manualDock.docked()
				&& pointInsideManual(mouseX, mouseY)) return true;
		if (insertOverlay != null && !insertOverlay.dockSide().docked()
				&& insertOverlay.contains(mouseX, mouseY)) return true;
		return isOutputRendered() && !outputDock.docked() && pointInsideOutput(mouseX, mouseY);
	}

	private boolean outputCloseContains(double mouseX, double mouseY) {
		ensureOutputGeometry();
		int right = outputWindowX + outputWindowWidth;
		return mouseX >= right - 25 && mouseX <= right - 5
				&& mouseY >= outputPanelY() + 3 && mouseY <= outputPanelY() + 20;
	}

	private boolean isOutputRendered() {
		return outputVisible || outputProgress > 0.01F;
	}

	private boolean outputInteractive() {
		return outputVisible && outputProgress >= 0.999F;
	}

	private int outputPanelY() {
		float progress = Math.max(0.0F, Math.min(1.0F, outputProgress));
		float eased = progress * progress * (3.0F - 2.0F * progress);
		int hiddenY = height + 8;
		return Math.round(hiddenY + (outputWindowY - hiddenY) * eased);
	}

	private void ensureOutputGeometry() {
		if (outputDock.docked()) return;
		int availableWidth = Math.max(1, width);
		int availableHeight = Math.max(1, height);
		int minWidth = Math.min(OUTPUT_MIN_WIDTH, availableWidth);
		int minHeight = Math.min(OUTPUT_MIN_HEIGHT, availableHeight);
		if (outputWindowWidth < 0) {
			outputWindowWidth = Math.min(Math.max(minWidth, (int) (width * 0.58F)), Math.max(minWidth, width - 28));
		}
		if (outputWindowHeight < 0) {
			outputWindowHeight = Math.min(Math.max(minHeight, (int) (height * 0.34F)), Math.max(minHeight, height - 28));
		}
		outputWindowWidth = Math.max(minWidth, Math.min(outputWindowWidth, availableWidth));
		outputWindowHeight = Math.max(minHeight, Math.min(outputWindowHeight, availableHeight));
		if (outputWindowX < 0) outputWindowX = Math.max(0, width - outputWindowWidth - 18);
		if (outputWindowY < 0) outputWindowY = Math.max(0, height - outputWindowHeight - 58);
		outputWindowX = Math.max(0, Math.min(width - outputWindowWidth, outputWindowX));
		outputWindowY = Math.max(0, Math.min(height - outputWindowHeight, outputWindowY));
	}

	private boolean pointInsideOutput(double mouseX, double mouseY) {
		ensureOutputGeometry();
		int panelY = outputPanelY();
		return isOutputRendered() && mouseX >= outputWindowX && mouseX <= outputWindowX + outputWindowWidth
				&& mouseY >= panelY && mouseY <= panelY + outputWindowHeight;
	}

	private int outputResizeEdgesAt(double mouseX, double mouseY) {
		if (!outputInteractive()) return 0;
		if (outputDock.docked()) return 0;
		ensureOutputGeometry();
		int right = outputWindowX + outputWindowWidth;
		int bottom = outputWindowY + outputWindowHeight;
		if (mouseX < outputWindowX - OUTPUT_RESIZE_MARGIN || mouseX > right + OUTPUT_RESIZE_MARGIN
				|| mouseY < outputWindowY - OUTPUT_RESIZE_MARGIN || mouseY > bottom + OUTPUT_RESIZE_MARGIN) {
			return 0;
		}
		int edges = 0;
		if (Math.abs(mouseX - outputWindowX) <= OUTPUT_RESIZE_MARGIN) edges |= RESIZE_LEFT;
		if (Math.abs(mouseX - right) <= OUTPUT_RESIZE_MARGIN) edges |= RESIZE_RIGHT;
		if (Math.abs(mouseY - outputWindowY) <= OUTPUT_RESIZE_MARGIN) edges |= RESIZE_TOP;
		if (Math.abs(mouseY - bottom) <= OUTPUT_RESIZE_MARGIN) edges |= RESIZE_BOTTOM;
		return edges;
	}

	private void resizeOutput(double mouseX, double mouseY) {
		int deltaX = (int) Math.round(mouseX - outputResizeStartMouseX);
		int deltaY = (int) Math.round(mouseY - outputResizeStartMouseY);
		int left = outputResizeStartX;
		int right = outputResizeStartX + outputResizeStartWidth;
		int top = outputResizeStartY;
		int bottom = outputResizeStartY + outputResizeStartHeight;
		int minWidth = Math.min(OUTPUT_MIN_WIDTH, width);
		int minHeight = Math.min(OUTPUT_MIN_HEIGHT, height);
		if ((outputResizeEdges & RESIZE_LEFT) != 0) {
			left = Math.max(0, Math.min(right - minWidth, outputResizeStartX + deltaX));
		}
		if ((outputResizeEdges & RESIZE_RIGHT) != 0) {
			right = Math.min(width, Math.max(left + minWidth, outputResizeStartX + outputResizeStartWidth + deltaX));
		}
		if ((outputResizeEdges & RESIZE_TOP) != 0) {
			top = Math.max(0, Math.min(bottom - minHeight, outputResizeStartY + deltaY));
		}
		if ((outputResizeEdges & RESIZE_BOTTOM) != 0) {
			bottom = Math.min(height, Math.max(top + minHeight, outputResizeStartY + outputResizeStartHeight + deltaY));
		}
		outputWindowX = left;
		outputWindowY = top;
		outputWindowWidth = right - left;
		outputWindowHeight = bottom - top;
		outputBoundsAnimation.snapTo(outputWindowX, outputWindowY,
				outputWindowWidth, outputWindowHeight);
	}

	private void ensureManualGeometry() {
		if (manualDock.docked()) return;
		if (manualWindowWidth < 0) {
			manualWindowWidth = Math.min(Math.max(MANUAL_MIN_WIDTH, (int) (width * 0.62F)), Math.max(MANUAL_MIN_WIDTH, width - 20));
		}
		if (manualWindowHeight < 0) {
			manualWindowHeight = Math.min(Math.max(MANUAL_MIN_HEIGHT, (int) (height * 0.82F)), Math.max(MANUAL_MIN_HEIGHT, height - 20));
		}
		manualWindowWidth = Math.min(manualWindowWidth, width);
		manualWindowHeight = Math.min(manualWindowHeight, height);
		if (manualWindowX < 0) {
			manualWindowX = Math.max(0, width - manualWindowWidth - 10);
		}
		manualWindowX = Math.max(0, Math.min(width - manualWindowWidth, manualWindowX));
		manualWindowY = Math.max(0, Math.min(height - manualWindowHeight, manualWindowY));
	}

	private int manualPanelX() {
		ensureManualGeometry();
		return Math.round(width + (manualWindowX - width) * manualProgress);
	}

	private boolean pointInsideManual(double mouseX, double mouseY) {
		int panelX = manualPanelX();
		return mouseX >= panelX && mouseX <= panelX + manualWindowWidth
				&& mouseY >= manualWindowY && mouseY <= manualWindowY + manualWindowHeight;
	}

	private int manualResizeEdgesAt(double mouseX, double mouseY) {
		if (manualDock.docked()) return 0;
		int panelX = manualPanelX();
		int panelRight = panelX + manualWindowWidth;
		int panelBottom = manualWindowY + manualWindowHeight;
		if (mouseX < panelX - MANUAL_RESIZE_MARGIN || mouseX > panelRight + MANUAL_RESIZE_MARGIN
				|| mouseY < manualWindowY - MANUAL_RESIZE_MARGIN || mouseY > panelBottom + MANUAL_RESIZE_MARGIN) {
			return 0;
		}
		int edges = 0;
		if (Math.abs(mouseX - panelX) <= MANUAL_RESIZE_MARGIN) {
			edges |= RESIZE_LEFT;
		}
		if (Math.abs(mouseX - panelRight) <= MANUAL_RESIZE_MARGIN) {
			edges |= RESIZE_RIGHT;
		}
		if (Math.abs(mouseY - manualWindowY) <= MANUAL_RESIZE_MARGIN) {
			edges |= RESIZE_TOP;
		}
		if (Math.abs(mouseY - panelBottom) <= MANUAL_RESIZE_MARGIN) {
			edges |= RESIZE_BOTTOM;
		}
		return edges;
	}

	private void resizeManual(double mouseX, double mouseY) {
		int deltaX = (int) Math.round(mouseX - manualResizeStartMouseX);
		int deltaY = (int) Math.round(mouseY - manualResizeStartMouseY);
		int left = manualResizeStartX;
		int right = manualResizeStartX + manualResizeStartWidth;
		int top = manualResizeStartY;
		int bottom = manualResizeStartY + manualResizeStartHeight;
		if ((manualResizeEdges & RESIZE_LEFT) != 0) {
			left = Math.max(0, Math.min(right - MANUAL_MIN_WIDTH, manualResizeStartX + deltaX));
		}
		if ((manualResizeEdges & RESIZE_RIGHT) != 0) {
			right = Math.min(width, Math.max(left + MANUAL_MIN_WIDTH,
					manualResizeStartX + manualResizeStartWidth + deltaX));
		}
		if ((manualResizeEdges & RESIZE_TOP) != 0) {
			top = Math.max(0, Math.min(bottom - MANUAL_MIN_HEIGHT, manualResizeStartY + deltaY));
		}
		if ((manualResizeEdges & RESIZE_BOTTOM) != 0) {
			bottom = Math.min(height, Math.max(top + MANUAL_MIN_HEIGHT,
					manualResizeStartY + manualResizeStartHeight + deltaY));
		}
		manualWindowX = left;
		manualWindowY = top;
		manualWindowWidth = right - left;
		manualWindowHeight = bottom - top;
		manualBoundsAnimation.snapTo(manualWindowX, manualWindowY,
				manualWindowWidth, manualWindowHeight);
		manualScroll = Math.min(manualScroll, maxManualScroll());
	}

	private int manualContentTop() {
		return manualWindowY + MANUAL_TITLE_HEIGHT + MANUAL_HINT_HEIGHT;
	}

	private int maxManualScroll() {
		int viewportHeight = Math.max(1, manualWindowHeight - MANUAL_TITLE_HEIGHT - MANUAL_HINT_HEIGHT - 12);
		return Math.max(0, manualContentHeight - viewportHeight);
	}

	private void extractManual(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		manualCopyTargets.clear();
		manualTextSpans.clear();
		if (manualProgress <= 0.0F) {
			return;
		}
		ensureManualGeometry();
		int panelX = manualPanelX();
		int panelRight = panelX + manualWindowWidth;
		int panelBottom = manualWindowY + manualWindowHeight;
		int contentTop = manualContentTop();

		graphics.fill(panelX - 4, manualWindowY + 4, panelRight + 4, panelBottom + 4, 0x70000000);
		graphics.fill(panelX, manualWindowY, panelRight, panelBottom, 0xFD10151E);
		graphics.outline(panelX, manualWindowY, manualWindowWidth, manualWindowHeight, 0xFF5A9FE6);
		graphics.fill(panelX + 1, manualWindowY + 1, panelRight - 1, manualWindowY + MANUAL_TITLE_HEIGHT, 0xFF1D2A3A);
		graphics.text(font, "▣  MC Lua IDE 使用手册", panelX + 10, manualWindowY + 9, 0xFF78BEFF);
		boolean closeHovered = mouseX >= panelRight - 25 && mouseX <= panelRight - 5
				&& mouseY >= manualWindowY + 4 && mouseY <= manualWindowY + 24;
		graphics.fill(panelRight - 25, manualWindowY + 4, panelRight - 5, manualWindowY + 24,
				closeHovered ? 0xFFB83A46 : 0xFF313D4C);
		graphics.centeredText(font, "×", panelRight - 15, manualWindowY + 10, 0xFFFFFFFF);

		graphics.fill(panelX + 1, manualWindowY + MANUAL_TITLE_HEIGHT, panelRight - 1, contentTop, 0xFF151C27);
		String manualHint = font.plainSubstrByWidth("拖选复制 · 标题栏拖至边缘吸附 · 边框缩放",
				Math.max(20, manualWindowWidth - 20));
		graphics.text(font, manualHint, panelX + 10,
				manualWindowY + MANUAL_TITLE_HEIGHT + 8, 0xFF93A4B8);

		graphics.enableScissor(panelX + 2, contentTop, panelRight - 2, panelBottom - 2);
		int y = contentTop + 10 - manualScroll;
		List<MarkdownManualDocument.Line> lines = manualDocument.lines();
		for (int index = 0; index < lines.size();) {
			MarkdownManualDocument.Line line = lines.get(index);
			if (line.kind() == MarkdownManualDocument.Kind.SPACE) {
				y += 7;
				index++;
				continue;
			}
			if (line.kind() == MarkdownManualDocument.Kind.CODE) {
				int blockIndex = line.codeBlockIndex();
				int endIndex = index;
				while (endIndex < lines.size() && lines.get(endIndex).kind() == MarkdownManualDocument.Kind.CODE
						&& lines.get(endIndex).codeBlockIndex() == blockIndex) {
					endIndex++;
				}
				MarkdownManualDocument.CodeBlock block = manualDocument.codeBlocks().get(blockIndex);
				List<List<WrappedPart>> codeParts = new ArrayList<>();
				int codeLineCount = 0;
				for (int lineIndex = index; lineIndex < endIndex; lineIndex++) {
					List<WrappedPart> parts = wrapManualText(lines.get(lineIndex).text(), manualWindowWidth - 42);
					codeParts.add(parts);
					codeLineCount += parts.size();
				}
				int blockHeight = 23 + Math.max(1, codeLineCount) * (font.lineHeight + 2) + 7;
				graphics.fill(panelX + 10, y, panelRight - 11, y + blockHeight, 0xFF0A0F16);
				graphics.outline(panelX + 10, y, manualWindowWidth - 21, blockHeight, 0xFF344256);
				graphics.fill(panelX + 11, y + 1, panelRight - 12, y + 21, 0xFF182231);
				graphics.text(font, block.language() + " 示例", panelX + 18, y + 7, 0xFF73D7FF);
				int copyX = panelRight - 66;
				boolean copyHovered = mouseX >= copyX && mouseX <= panelRight - 17 && mouseY >= y + 3 && mouseY <= y + 19;
				graphics.fill(copyX, y + 3, panelRight - 17, y + 19, copyHovered ? 0xFF2874A6 : 0xFF24364B);
				graphics.centeredText(font, "复制", (copyX + panelRight - 17) / 2, y + 7, 0xFFFFFFFF);
				if (y + 19 >= contentTop && y + 3 <= panelBottom) {
					manualCopyTargets.add(new ManualCopyTarget(copyX, y + 3, panelRight - 17, y + 19,
							block.language(), block.source()));
				}
				int codeY = y + 26;
				int blockSourceLineStart = 0;
				for (int lineIndex = index; lineIndex < endIndex; lineIndex++) {
					int documentLineStart = manualTextIndex.lineOffsets()[lineIndex];
					for (WrappedPart part : codeParts.get(lineIndex - index)) {
						String visibleCode = part.text().isEmpty() ? " " : part.text();
						Component highlightedCode = part.text().isEmpty() ? Component.literal(" ")
								: LuaSyntaxHighlighter.highlightSnippet(block.source(), visibleCode,
										blockSourceLineStart + part.start(), theme);
						drawManualText(graphics, visibleCode,
								highlightedCode, panelX + 19, codeY,
								0xFFFFFFFF, documentLineStart + part.start(), documentLineStart + part.end());
						codeY += font.lineHeight + 2;
					}
					blockSourceLineStart += lines.get(lineIndex).text().length() + 1;
				}
				y += blockHeight + 9;
				index = endIndex;
				continue;
			}

			int textX = panelX + 16;
			int textWidth = manualWindowWidth - 34;
			int color = 0xFFD7DEE8;
			if (line.kind() == MarkdownManualDocument.Kind.HEADING) {
				graphics.fill(panelX + 10, y - 4, panelRight - 10, y + font.lineHeight + 7, 0xFF16283A);
				graphics.fill(panelX + 10, y - 4, panelX + 13, y + font.lineHeight + 7, 0xFF4AA3F0);
				color = 0xFF7FC4FF;
				textX = panelX + 19;
			} else if (line.kind() == MarkdownManualDocument.Kind.SUBHEADING) {
				color = 0xFFFFCB6B;
				graphics.fill(panelX + 11, y + font.lineHeight + 3, panelRight - 12, y + font.lineHeight + 4, 0xFF4B5260);
			} else if (line.kind() == MarkdownManualDocument.Kind.QUOTE) {
				graphics.fill(panelX + 10, y - 3, panelRight - 11, y + font.lineHeight + 7, 0xFF172330);
				color = 0xFF9CB4CA;
			}
			for (WrappedPart part : wrapManualText(line.text(), textWidth)) {
				drawManualText(graphics, part.text(), Component.literal(part.text()), textX, y, color,
						manualTextIndex.lineOffsets()[index] + part.start(),
						manualTextIndex.lineOffsets()[index] + part.end());
				y += font.lineHeight + 2;
			}
			y += line.kind() == MarkdownManualDocument.Kind.HEADING ? 10
					: line.kind() == MarkdownManualDocument.Kind.SUBHEADING ? 8 : 3;
			index++;
		}
		manualContentHeight = Math.max(0, y + manualScroll - contentTop);
		manualScroll = Math.min(manualScroll, maxManualScroll());
		graphics.disableScissor();

		int viewportHeight = panelBottom - contentTop - 4;
		if (manualContentHeight > viewportHeight) {
			int trackTop = contentTop + 4;
			int trackHeight = viewportHeight - 8;
			int thumbHeight = Math.max(18, trackHeight * viewportHeight / manualContentHeight);
			int thumbY = trackTop + (trackHeight - thumbHeight) * manualScroll / Math.max(1, maxManualScroll());
			graphics.fill(panelRight - 6, trackTop, panelRight - 3, trackTop + trackHeight, 0xFF202B38);
			graphics.fill(panelRight - 6, thumbY, panelRight - 3, thumbY + thumbHeight, 0xFF5A9FE6);
		}

		int hoveredEdges = manualResizeEdges != 0 ? manualResizeEdges : manualResizeEdgesAt(mouseX, mouseY);
		int edgeColor = 0xFF77C1FF;
		if ((hoveredEdges & RESIZE_LEFT) != 0) graphics.fill(panelX - 1, manualWindowY, panelX + 2, panelBottom, edgeColor);
		if ((hoveredEdges & RESIZE_RIGHT) != 0) graphics.fill(panelRight - 2, manualWindowY, panelRight + 1, panelBottom, edgeColor);
		if ((hoveredEdges & RESIZE_TOP) != 0) graphics.fill(panelX, manualWindowY - 1, panelRight, manualWindowY + 2, edgeColor);
		if ((hoveredEdges & RESIZE_BOTTOM) != 0) graphics.fill(panelX, panelBottom - 2, panelRight, panelBottom + 1, edgeColor);
		graphics.fill(panelRight - 13, panelBottom - 4, panelRight - 3, panelBottom - 2, 0xFF76B8F2);
		graphics.fill(panelRight - 9, panelBottom - 8, panelRight - 3, panelBottom - 6, 0xFF76B8F2);
	}

	private void drawManualText(GuiGraphicsExtractor graphics, String visibleText, Component styledText,
			int x, int y, int color, int documentStart, int documentEnd) {
		int contentTop = manualContentTop();
		int contentBottom = manualWindowY + manualWindowHeight - 2;
		if (y + font.lineHeight >= contentTop && y <= contentBottom) {
			ManualTextSpan span = new ManualTextSpan(x, y, visibleText, documentStart, documentEnd);
			manualTextSpans.add(span);
			if (hasManualSelection()) {
				int selectionStart = Math.min(manualSelectionAnchor, manualSelectionCaret);
				int selectionEnd = Math.max(manualSelectionAnchor, manualSelectionCaret);
				int overlapStart = Math.max(selectionStart, documentStart);
				int overlapEnd = Math.min(selectionEnd, documentEnd);
				if (overlapStart < overlapEnd) {
					int localStart = overlapStart - documentStart;
					int localEnd = overlapEnd - documentStart;
					int highlightLeft = x + font.width(visibleText.substring(0, localStart));
					int highlightRight = x + font.width(visibleText.substring(0, localEnd));
					graphics.fill(highlightLeft, y - 1, Math.max(highlightLeft + 1, highlightRight),
							y + font.lineHeight, 0xB34A82C2);
				}
			}
		}
		graphics.text(font, styledText.getVisualOrderText(), x, y, color);
	}

	private List<WrappedPart> wrapManualText(String text, int maxWidth) {
		if (text.isEmpty()) {
			return List.of(new WrappedPart("", 0, 0));
		}
		List<WrappedPart> parts = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			String fitting = font.plainSubstrByWidth(text.substring(start), Math.max(8, maxWidth));
			int end = Math.min(text.length(), start + Math.max(1, fitting.length()));
			if (end < text.length()) {
				for (int candidate = end - 1; candidate > start; candidate--) {
					if (Character.isWhitespace(text.charAt(candidate))) {
						end = candidate + 1;
						break;
					}
				}
			}
			parts.add(new WrappedPart(text.substring(start, end), start, end));
			start = end;
		}
		return parts;
	}

	private int manualOffsetAt(double mouseX, double mouseY) {
		if (manualTextSpans.isEmpty()) {
			return 0;
		}
		ManualTextSpan closest = manualTextSpans.getFirst();
		double closestDistance = Double.MAX_VALUE;
		for (ManualTextSpan span : manualTextSpans) {
			double verticalDistance = mouseY < span.y() ? span.y() - mouseY
					: mouseY > span.y() + font.lineHeight ? mouseY - span.y() - font.lineHeight : 0;
			if (verticalDistance < closestDistance) {
				closest = span;
				closestDistance = verticalDistance;
			}
			if (verticalDistance == 0) {
				break;
			}
		}
		int selectableLength = Math.max(0, closest.end() - closest.start());
		if (selectableLength == 0 || mouseX <= closest.x()) {
			return closest.start();
		}
		int previousWidth = 0;
		for (int index = 1; index <= selectableLength; index++) {
			int nextWidth = font.width(closest.text().substring(0, index));
			if (mouseX < closest.x() + (previousWidth + nextWidth) / 2.0) {
				return closest.start() + index - 1;
			}
			previousWidth = nextWidth;
		}
		return closest.end();
	}

	private boolean hasManualSelection() {
		return manualSelectionAnchor >= 0 && manualSelectionCaret >= 0
				&& manualSelectionAnchor != manualSelectionCaret;
	}

	private void copyManualSelection() {
		int start = Math.max(0, Math.min(manualSelectionAnchor, manualSelectionCaret));
		int end = Math.min(manualTextIndex.text().length(), Math.max(manualSelectionAnchor, manualSelectionCaret));
		minecraft.keyboardHandler.setClipboard(manualTextIndex.text().substring(start, end));
		status = "已复制选中的手册文字";
	}

	private void selectManualWord(int offset) {
		String text = manualTextIndex.text();
		if (text.isEmpty()) {
			return;
		}
		int cursor = Math.max(0, Math.min(text.length() - 1, offset));
		if (Character.isWhitespace(text.charAt(cursor)) && cursor > 0) {
			cursor--;
		}
		int start = cursor;
		int end = cursor + 1;
		while (start > 0 && isManualWordCharacter(text.charAt(start - 1))) {
			start--;
		}
		while (end < text.length() && isManualWordCharacter(text.charAt(end))) {
			end++;
		}
		manualSelectionAnchor = start;
		manualSelectionCaret = end;
		manualSelecting = false;
		status = "已选择手册文字，按 Ctrl+C 复制";
	}

	private boolean isManualWordCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_';
	}

	private static ManualTextIndex buildManualTextIndex(List<MarkdownManualDocument.Line> lines) {
		StringBuilder text = new StringBuilder();
		int[] offsets = new int[lines.size()];
		for (int index = 0; index < lines.size(); index++) {
			offsets[index] = text.length();
			text.append(lines.get(index).text());
			if (index + 1 < lines.size()) {
				text.append('\n');
			}
		}
		return new ManualTextIndex(text.toString(), offsets);
	}

	private void extractSuggestions(GuiGraphicsExtractor graphics, MultilineTextField field, int mouseX, int mouseY) {
		if (suggestions.isEmpty()) {
			return;
		}
		SuggestionPopup popup = suggestionPopup(field);
		if (popup == null) return;
		advanceSuggestionAnimation(popup.visibleRows());

		graphics.fill(popup.x(), popup.y(), popup.right(), popup.bottom(), 0xF0101010);
		graphics.outline(popup.x(), popup.y(), popup.width(), popup.height(), 0xFF777777);
		int contentTop = popup.y() + 3;
		int contentBottom = popup.bottom() - 3;
		graphics.enableScissor(popup.x() + 1, popup.y() + 2, popup.right() - 1, popup.bottom() - 2);

		int highlightY = contentTop + (int) Math.round(
				(animatedSuggestionSelection - animatedSuggestionScroll) * SUGGESTION_ROW_HEIGHT);
		if (highlightY + 11 >= contentTop && highlightY - 1 <= contentBottom) {
			graphics.fill(popup.x() + 2, highlightY - 1, popup.right() - 2, highlightY + 11, 0xFF285A8C);
		}

		int firstIndex = Math.max(0, (int) Math.floor(animatedSuggestionScroll) - 1);
		int lastIndex = Math.min(suggestions.size(),
				(int) Math.ceil(animatedSuggestionScroll) + popup.visibleRows() + 1);
		int textWidth = popup.width() - (suggestions.size() > popup.visibleRows() ? 18 : 12);
		for (int index = firstIndex; index < lastIndex; index++) {
			int itemY = contentTop + (int) Math.round(
					(index - animatedSuggestionScroll) * SUGGESTION_ROW_HEIGHT);
			String label = LuaApiDocumentation.completionLabel(suggestions.get(index));
			graphics.text(font, font.plainSubstrByWidth(label, textWidth), popup.x() + 6, itemY, 0xFFFFFFFF);
		}

		if (suggestions.size() > popup.visibleRows()) {
			int trackTop = popup.y() + 3;
			int trackHeight = popup.height() - 6;
			int thumbHeight = Math.max(8,
					(int) Math.round(trackHeight * (popup.visibleRows() / (double) suggestions.size())));
			double maxScroll = Math.max(1.0, suggestions.size() - popup.visibleRows());
			int thumbY = trackTop + (int) Math.round((trackHeight - thumbHeight)
					* (animatedSuggestionScroll / maxScroll));
			graphics.fill(popup.right() - 5, trackTop, popup.right() - 3, trackTop + trackHeight, 0xFF30343A);
			graphics.fill(popup.right() - 5, thumbY, popup.right() - 3, thumbY + thumbHeight, 0xFF76A9D6);
		}
		graphics.disableScissor();

		int hoveredSuggestion = suggestionIndexAt(mouseX, mouseY, popup);
		if (hoveredSuggestion >= 0) {
			showApiTooltip(graphics, LuaApiDocumentation.find(suggestions.get(hoveredSuggestion)), mouseX, mouseY);
		}
	}

	private SuggestionPopup suggestionPopup(MultilineTextField field) {
		if (suggestions.isEmpty() || editor == null) return null;
		int lineIndex = field.getLineAtCursor();
		String source = field.value();
		int cursor = Math.max(0, Math.min(field.cursor(), source.length()));
		int logicalLineStart = source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
		String beforeCursor = source.substring(logicalLineStart, cursor);
		int popupWidth = suggestions.stream()
				.map(LuaApiDocumentation::completionLabel)
				.mapToInt(font::width).max().orElse(80) + 16;
		popupWidth = Math.max(110, Math.min(Math.max(110, width - 24), Math.min(360, popupWidth)));
		int maxRowsByEditor = Math.max(1, (editor.getHeight() - 10) / SUGGESTION_ROW_HEIGHT);
		int visibleRows = Math.min(suggestions.size(), Math.min(SUGGESTION_MAX_VISIBLE_ROWS, maxRowsByEditor));
		int popupHeight = visibleRows * SUGGESTION_ROW_HEIGHT + 6;
		int popupX = editor.getX() + 5 + Math.round(font.width(beforeCursor) * codeScale);
		int codeLineHeight = scaledCodeLineHeight();
		int caretY = editor.getY() + 8 + lineIndex * codeLineHeight - (int) editor.scrollAmount();
		int popupY = caretY + codeLineHeight;
		popupX = Math.max(12, Math.min(width - popupWidth - 12, popupX));
		if (popupY + popupHeight > editor.getBottom()) {
			popupY = caretY - popupHeight;
		}
		popupY = Math.max(editor.getY() + 2, Math.min(editor.getBottom() - popupHeight - 2, popupY));
		return new SuggestionPopup(popupX, popupY, popupWidth, popupHeight, visibleRows);
	}

	private void advanceSuggestionAnimation(int visibleRows) {
		if (suggestions.isEmpty()) return;
		int maxScroll = Math.max(0, suggestions.size() - visibleRows);
		targetSuggestionScroll = Math.max(0.0, Math.min(targetSuggestionScroll, maxScroll));
		if (selectedSuggestion < targetSuggestionScroll) {
			targetSuggestionScroll = selectedSuggestion;
		} else if (selectedSuggestion >= targetSuggestionScroll + visibleRows) {
			targetSuggestionScroll = selectedSuggestion - visibleRows + 1.0;
		}
		targetSuggestionScroll = Math.max(0.0, Math.min(targetSuggestionScroll, maxScroll));

		long now = System.nanoTime();
		if (!suggestionAnimationReady || suggestionAnimationNanos == 0L) {
			animatedSuggestionSelection = selectedSuggestion;
			animatedSuggestionScroll = targetSuggestionScroll;
			suggestionAnimationReady = true;
			suggestionAnimationNanos = now;
			return;
		}
		double elapsedSeconds = Math.min(0.05, Math.max(0.0, (now - suggestionAnimationNanos) / 1_000_000_000.0));
		suggestionAnimationNanos = now;
		double blend = 1.0 - Math.exp(-elapsedSeconds * 18.0);
		animatedSuggestionSelection += (selectedSuggestion - animatedSuggestionSelection) * blend;
		animatedSuggestionScroll += (targetSuggestionScroll - animatedSuggestionScroll) * blend;
		if (Math.abs(selectedSuggestion - animatedSuggestionSelection) < 0.01) {
			animatedSuggestionSelection = selectedSuggestion;
		}
		if (Math.abs(targetSuggestionScroll - animatedSuggestionScroll) < 0.01) {
			animatedSuggestionScroll = targetSuggestionScroll;
		}
	}

	private boolean suggestionPopupContains(double mouseX, double mouseY) {
		SuggestionPopup popup = suggestionPopup(textField());
		return popup != null && mouseX >= popup.x() && mouseX <= popup.right()
				&& mouseY >= popup.y() && mouseY <= popup.bottom();
	}

	private int suggestionIndexAt(double mouseX, double mouseY) {
		SuggestionPopup popup = suggestionPopup(textField());
		return popup == null ? -1 : suggestionIndexAt(mouseX, mouseY, popup);
	}

	private int suggestionIndexAt(double mouseX, double mouseY, SuggestionPopup popup) {
		if (mouseX < popup.x() + 2 || mouseX > popup.right() - 2
				|| mouseY < popup.y() + 2 || mouseY > popup.bottom() - 2) {
			return -1;
		}
		int firstIndex = Math.max(0, (int) Math.floor(animatedSuggestionScroll) - 1);
		int lastIndex = Math.min(suggestions.size(),
				(int) Math.ceil(animatedSuggestionScroll) + popup.visibleRows() + 1);
		int contentTop = popup.y() + 3;
		for (int index = firstIndex; index < lastIndex; index++) {
			int itemY = contentTop + (int) Math.round(
					(index - animatedSuggestionScroll) * SUGGESTION_ROW_HEIGHT);
			if (mouseY >= itemY - 1 && mouseY <= itemY + 11) return index;
		}
		return -1;
	}

	private boolean pointInsideEditor(double mouseX, double mouseY) {
		return editor != null && mouseX >= editor.getX() && mouseX <= editor.getRight()
				&& mouseY >= editor.getY() && mouseY <= editor.getBottom();
	}

	private int editorOuterX() {
		return editor == null ? EDITOR_OUTER_X : editor.getX() - LINE_NUMBER_GUTTER_WIDTH;
	}

	private record SuggestionPopup(int x, int y, int width, int height, int visibleRows) {
		int right() {
			return x + width;
		}

		int bottom() {
			return y + height;
		}
	}

	private record DockRegion(int x, int y, int width, int height) {
		int right() {
			return x + width;
		}
	}

	private void extractApiHover(GuiGraphicsExtractor graphics, MultilineTextField field, int mouseX, int mouseY) {
		if (mouseX < editor.getX() + 4 || mouseX > editor.getRight() - 4
				|| mouseY < editor.getY() + 4 || mouseY > editor.getBottom() - 4) {
			return;
		}
		String symbol = apiSymbolAt(field, mouseX, mouseY);
		if (symbol != null) {
			showApiTooltip(graphics, LuaApiDocumentation.find(symbol), mouseX, mouseY);
		}
	}

	private String apiSymbolAt(MultilineTextField field, double mouseX, double mouseY) {
		double documentY = mouseY - editor.getY() - 4 + editor.scrollAmount();
		if (documentY < 0) return null;
		int requestedLine = (int) (documentY / scaledCodeLineHeight());
		int lineIndex = 0;
		StringViewAccessor selectedView = null;
		for (Object rawView : field.iterateLines()) {
			if (lineIndex++ == requestedLine) {
				selectedView = (StringViewAccessor) rawView;
				break;
			}
		}
		if (selectedView == null) return null;
		String source = field.value();
		int begin = Math.max(0, Math.min(source.length(), selectedView.mcLuaIde$getBeginIndex()));
		int end = Math.max(begin, Math.min(source.length(), selectedView.mcLuaIde$getEndIndex()));
		if (begin == end) return null;

		double documentX = (mouseX - editor.getX() - 5) / Math.max(0.1F, codeScale);
		if (documentX < 0) return null;
		String visualLine = source.substring(begin, end);
		if (documentX > font.width(visualLine) + 2) return null;
		int relative = visualLine.length() - 1;
		int previousWidth = 0;
		for (int index = 0; index < visualLine.length(); index++) {
			int nextWidth = font.width(visualLine.substring(0, index + 1));
			if (documentX < (previousWidth + nextWidth) / 2.0) {
				relative = index;
				break;
			}
			previousWidth = nextWidth;
		}
		int offset = begin + relative;
		if (offset >= source.length()) return null;
		if (!isApiSymbolCharacter(source.charAt(offset))) {
			if (offset > begin && source.charAt(offset) == '(') offset--;
			else return null;
		}
		return documentedApiSymbolAround(source, offset);
	}

	private String apiSymbolAtCursor() {
		if (editor == null) return null;
		MultilineTextField field = textField();
		String source = field.value();
		if (source.isEmpty()) return null;
		int cursor = Math.max(0, Math.min(source.length(), field.cursor()));
		int offset = cursor;
		if (offset >= source.length() || !isApiSymbolCharacter(source.charAt(offset))) {
			offset = Math.min(source.length() - 1, cursor - 1);
			while (offset >= 0 && Character.isWhitespace(source.charAt(offset))) offset--;
			if (offset >= 0 && source.charAt(offset) == '(') {
				offset--;
				while (offset >= 0 && Character.isWhitespace(source.charAt(offset))) offset--;
			}
		}
		return documentedApiSymbolAround(source, offset);
	}

	private String documentedApiSymbolAround(String source, int offset) {
		if (offset < 0 || offset >= source.length() || !isApiSymbolCharacter(source.charAt(offset))) {
			return null;
		}
		int start = offset;
		int finish = offset + 1;
		while (start > 0 && isApiSymbolCharacter(source.charAt(start - 1))) start--;
		while (finish < source.length() && isApiSymbolCharacter(source.charAt(finish))) finish++;
		String symbol = source.substring(start, finish);
		return LuaApiDocumentation.find(symbol) == null ? null : symbol;
	}

	private boolean isApiSymbolCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_' || character == '.';
	}

	private void showApiTooltip(GuiGraphicsExtractor graphics, LuaApiDocumentation.Entry entry,
			int mouseX, int mouseY) {
		if (entry != null) {
			graphics.setComponentTooltipForNextFrame(font, entry.tooltip(), mouseX, mouseY);
		}
	}

	private int currentColumn(MultilineTextField field) {
		String source = field.value();
		int cursor = field.cursor();
		return cursor - source.lastIndexOf('\n', Math.max(0, cursor - 1));
	}

	@Override
	public void onClose() {
		rememberWorkspaceLayout();
		flushWorkspaceLayout();
		minecraft.setScreen(previousScreen);
	}

	@Override
	public void removed() {
		rememberWorkspaceLayout();
		flushWorkspaceLayout();
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private record ManualCopyTarget(int left, int top, int right, int bottom, String language, String source) {
		private boolean contains(double x, double y) {
			return x >= left && x <= right && y >= top && y <= bottom;
		}
	}

	private record ManualTextIndex(String text, int[] lineOffsets) {
	}

	private record WrappedPart(String text, int start, int end) {
	}

	private record OutputVisualLine(FormattedCharSequence text, int color) {
	}

	private record ManualTextSpan(int x, int y, String text, int start, int end) {
	}
}
