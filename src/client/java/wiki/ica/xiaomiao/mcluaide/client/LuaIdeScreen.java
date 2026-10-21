package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
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
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultilineTextFieldAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.StringViewAccessor;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LuaIdeScreen extends Screen implements ExtensionWindowHost {
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
	private static final int LINE_NUMBER_GUTTER_WIDTH = 64;
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
	private static final Path WINDOW_GEOMETRY_FILE = WORKSPACE_LAYOUT_FILE.resolveSibling("window-geometry.properties");
	private static WorkspaceLayout rememberedWorkspaceLayout = loadWorkspaceLayout();
	private static float rememberedInterfaceScale = 1.0F;
	private static boolean firstManualShown;

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

	private static List<String> completions(List<String> variables) {
		List<String> completions = new ArrayList<>(BASE_COMPLETIONS);
		completions.addAll(LuaApiDocumentation.symbols());
		completions.addAll(variables);
		return completions;
	}

	private final Screen previousScreen;
	private final ImGuiIdeScreen nativeOwner;
	private ToolWindow nativeInputTool;
	private String nativeActivatedTool;
	private final Map<String, String> nativeToolSizes = new java.util.HashMap<>();
	private boolean nativeOpening;
	private boolean nativeDebugRun = true;
	private String scriptName;
	private final Map<String, OpenDocument> openDocuments = new LinkedHashMap<>();
	private OpenDocument currentDocument;
	private final LuaIdeSession session;
	private final LuaFileRecovery fileRecovery;
	private final Map<String, RecoveryTracking> recoveryTracking = new HashMap<>();
	private final Deque<LuaFileRecovery.Entry> recoveryRestoreQueue = new ArrayDeque<>();
	private boolean recoveryPrompt, recoveryClosed, recoveryRestoring;
	private int recoveryPromptChoice;
	private long nextRecoverySnapshot;
	private String reportedRecoveryFailure = "";
	private final LuaBreakpointStore breakpointStore;
	private final LuaFunctionFolding.State draftFunctionFolds = new LuaFunctionFolding.State();
	private int hoveredSymbolOffset;
	private boolean leftMouseHeld;
	private String observedDebuggerStop = "", pendingDebuggerReveal = "";
	private String pendingDebuggerLocation = "";
	private int pendingDebuggerLine;
	private int viewMenuPage;
	private int toolsMenuPage;
	private LuaAiAgent.Problem aiProblem, aiRuntimeProblem, aiRunSource;
	private long observedAiCallbackError;
	private boolean restoreRunPending = true;
	private LuaAiAgent.Mark hoveredAiMark;
	private long observedMcpRevision;
	private final LuaAiController ai = new LuaAiController(new LuaAiController.Host() {
		public LuaAiAgent.Problem currentProblem() { return currentAiProblem(); }
		public LuaAiAgent.Workspace workspace() { return aiWorkspace(); }
		public void dispatch(Runnable action) { minecraft.execute(action); }
		public void log(String text) { appendAiOutput(text); }
		public LuaAiAnalysisHistory analysisHistory(LuaAiAgent.Problem problem) {
			return aiRunSource != null && outputExecution != null
					&& aiRunSource.path().equals(problem.path()) && aiRunSource.source().equals(problem.source())
					? outputExecution.aiAnalysisHistory() : null;
		}
	});
	private final Properties windowGeometry = LuaIdeSession.readProperties(WINDOW_GEOMETRY_FILE);
	private final DockTabOrder dockTabOrder = new DockTabOrder(windowGeometry);
	private final Map<DockSide, Map<ToolWindow, Float>> animatedDockTabPositions = new java.util.EnumMap<>(DockSide.class);
	private ToolWindow pressedDockTab;
	private DockSide pressedDockTabSide = DockSide.FLOATING;
	private double dockTabPressX, dockTabPressY, dockTabDragX, dockTabDragOffsetX;
	private boolean dockTabDragging;
	private Runnable pendingCloseAction;
	private List<OpenDocument> pendingCloseDocuments = List.of();
	private boolean closeSaving;
	private String closeError = "";
	private int closeChoice;
	private boolean firstManualPending;
	private int documentTabScroll;
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
	private int outputSelectionAnchor, outputSelectionCaret;
	private boolean outputSelecting, outputKeyboardFocused;
	private String outputSelectionSource = "";
	private final List<Button> topMenuButtons = new ArrayList<>();
	private LuaPersonalizationScreen personalizationPanel;
	private LuaGitScreen gitPanel;
	private LuaApiExportScreen exportPanel;
	private Screen frontSettingsPanel;
	private Screen focusedSettingsPanel;
	private Screen pressedSettingsPanel;
	private boolean pendingPersonalizationRebuild;
	private DockSide rememberedGitDock = DockSide.FLOATING;
	private DockSide rememberedPersonalizationDock = DockSide.FLOATING;
	private DockSide rememberedExportDock = DockSide.FLOATING;
	private final List<Button> fileMenuItems = new ArrayList<>();
	private final List<Button> toolMenuItems = new ArrayList<>();
	private final List<Button> foldingMenuItems = new ArrayList<>();
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
	private final LuaEditorContextMenu editorContextMenu = new LuaEditorContextMenu();
	private OpenDocument editorContextDocument;
	private final Deque<String> redoHistory = new ArrayDeque<>();
	private String lastSnapshot = "";
	private String lastSavedSource = "";
	private String cachedSource = "";
	private boolean sourceLoaded;
	private boolean sourceLoading;
	private boolean internalTextChange;
	private LuaPersonalizationSettings personalization = LuaPersonalizationSettings.load();
	private LuaSyntaxHighlighter.Theme theme = personalization.theme;
	private float codeScale = personalization.fontPercent / 100.0F;
	private Font codeFont;
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
	private final Map<ToolWindow, LuaExtensionWindow> extensionWindows = new LinkedHashMap<>();
	private final List<Button> extensionMenuItems = new ArrayList<>();
	private int extensionMenuPage;
	private LuaExtensionWindow focusedExtensionWindow;
	private LuaExtensionWindow pressedExtensionWindow;
	private Properties rememberedExtensionLayout = new Properties();
	private static final Path EXTENSION_LAYOUT_FILE = WORKSPACE_LAYOUT_FILE.resolveSibling("extension-window-layout.properties");
	private LuaVariableOverlay variableOverlay;
	private boolean variableWindowFocused;
	private LuaFileOverlay fileOverlay;
	private LuaFileOverlay datapackOverlay;
	private LuaResourcePackToolOverlay resourceDirectoryOverlay;
	private LuaResourcePackToolOverlay resourceJsonOverlay;
	private LuaResourcePackToolOverlay resourcePngOverlay;
	private LuaComponentEditorOverlay componentEditorOverlay;
	private LuaDatapackJsonEditorOverlay datapackJsonEditorOverlay;
	private LuaTextJsonOverlay textJsonOverlay;
	private LuaMinecraftUiEditorOverlay minecraftUiEditorOverlay;
	private ComponentInsertionTarget componentInsertionTarget;
	private TextJsonInsertionTarget textJsonInsertionTarget;
	private EditBox searchBox;
	private boolean manualOnTop = true;
	private boolean searchOpen;
	private float searchProgress;
	private String searchQuery = "";
	private final List<Integer> searchMatches = new ArrayList<>();
	private int selectedSearchMatch = -1;
	private boolean manualSelecting;
	private boolean manualKeyboardFocused;
	private int manualSelectionAnchor = -1;
	private int manualSelectionCaret = -1;
	private String activeMenu = "";
	private String activeSubmenu = "";
	private boolean layoutLocked;
	private final Set<ToolWindow> shortcutOpenedTools = new HashSet<>();
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
	private int functionSuggestionRevision;
	private Map<String, String> functionSuggestionTooltips = Map.of();
	private Map<String, LuaArgumentCompletion.Choice> argumentSuggestionChoices = Map.of();
	private Map<String, LuaArgumentCompletion.Choice> moduleSuggestionChoices = Map.of();
	private final LuaModuleCompletion moduleCompletion = new LuaModuleCompletion();
	private final Map<String, ModuleSource> moduleSources = new LinkedHashMap<>();
	private final Set<String> moduleSourceRequests = new HashSet<>();
	private final Map<String, Long> moduleSourceFailures = new HashMap<>();
	private final Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>> argumentCatalogs = new LinkedHashMap<>();
	private final Set<LuaInsertCatalog.Kind> argumentCatalogRequests = new HashSet<>();
	private int argumentCatalogRevision = -1;
	private int argumentApiRevision = -1;
	private String dismissedSuggestionSource;
	private int dismissedSuggestionCursor = -1;
	private int completionEnd;
	private double animatedSuggestionSelection;
	private double animatedSuggestionScroll;
	private double targetSuggestionScroll;
	private long suggestionAnimationNanos;
	private boolean suggestionAnimationReady;
	private final AtomicInteger validationRevision = new AtomicInteger();
	private List<LuaSyntaxHighlighter.ErrorRange> editorErrorRanges = List.of();
	private Set<Integer> errorLineNumbers = Set.of();
	private Set<Integer> warningLineNumbers = Set.of();
	private String syntaxMessage = LuaUiText.text("正在检查语法…");
	private String validationDetail = LuaUiText.text("正在检查 Lua 语法和 MC API 参数…");
	private int syntaxColor = 0xFFFFFF55;
	private String status = LuaUiText.text("Tab 补全 · Ctrl+F 搜索 · Ctrl+F1 API 文档 · Ctrl+S 保存 · Ctrl+Enter 运行");

	private static final class ToolWindow {
		private enum Kind { MANUAL, INSERT, API_DOCS, COMPONENT_EDITOR, DATAPACK_JSON_EDITOR, TEXT_JSON, MINECRAFT_UI, FILES, DATAPACK_FILES, RESOURCE_FILES, RESOURCE_JSON, RESOURCE_PNG, OUTPUT, VARIABLES, GIT, PERSONALIZATION, EXPORT, EXTENSION }
		private static final Map<String, ToolWindow> TOKENS = new LinkedHashMap<>();
		static final ToolWindow MANUAL = builtin(Kind.MANUAL);
		static final ToolWindow INSERT = builtin(Kind.INSERT);
		static final ToolWindow API_DOCS = builtin(Kind.API_DOCS);
		static final ToolWindow COMPONENT_EDITOR = builtin(Kind.COMPONENT_EDITOR);
		static final ToolWindow DATAPACK_JSON_EDITOR = builtin(Kind.DATAPACK_JSON_EDITOR);
		static final ToolWindow TEXT_JSON = builtin(Kind.TEXT_JSON);
		static final ToolWindow MINECRAFT_UI = builtin(Kind.MINECRAFT_UI);
		static final ToolWindow FILES = builtin(Kind.FILES);
		static final ToolWindow DATAPACK_FILES = builtin(Kind.DATAPACK_FILES);
		static final ToolWindow RESOURCE_FILES = builtin(Kind.RESOURCE_FILES);
		static final ToolWindow RESOURCE_JSON = builtin(Kind.RESOURCE_JSON);
		static final ToolWindow RESOURCE_PNG = builtin(Kind.RESOURCE_PNG);
		static final ToolWindow OUTPUT = builtin(Kind.OUTPUT);
		static final ToolWindow VARIABLES = builtin(Kind.VARIABLES);
		static final ToolWindow GIT = builtin(Kind.GIT);
		static final ToolWindow PERSONALIZATION = builtin(Kind.PERSONALIZATION);
		static final ToolWindow EXPORT = builtin(Kind.EXPORT);
		final Kind kind;
		final String id;
		private ToolWindow(Kind kind, String id) { this.kind = kind; this.id = id; }
		private static ToolWindow builtin(Kind kind) {
			ToolWindow tool = new ToolWindow(kind, "");
			TOKENS.put(kind.name(), tool); return tool;
		}
		static ToolWindow extension(String id) {
			return TOKENS.computeIfAbsent("EXT:" + id, ignored -> new ToolWindow(Kind.EXTENSION, id));
		}
		static ToolWindow valueOf(String name) {
			if (name.startsWith("EXT:")) return extension(name.substring(4));
			ToolWindow tool = TOKENS.get(name);
			if (tool == null) throw new IllegalArgumentException(name);
			return tool;
		}
		String name() { return kind == Kind.EXTENSION ? "EXT:" + id : kind.name(); }
	}
	private List<ToolWindow> toolWindows() {
		List<ToolWindow> result = new ArrayList<>();
		for (ToolWindow.Kind kind : ToolWindow.Kind.values())
			if (kind != ToolWindow.Kind.EXTENSION) result.add(ToolWindow.valueOf(kind.name()));
		result.addAll(extensionWindows.keySet());
		return result;
	}

	private enum ScaleTarget {
		FONT,
		INTERFACE
	}

	private record ComponentInsertionTarget(String source, int start, int end) {
	}

	private record TextJsonInsertionTarget(String script, String source, int start, int end) {
	}

	private record WorkspaceLayout(boolean locked, int leftWidth, int rightWidth, int bottomHeight,
			DockSide manual, DockSide insert, DockSide apiDocs, DockSide componentEditor, DockSide datapackJsonEditor, DockSide textJson,
			DockSide minecraftUi, DockSide files, DockSide datapackFiles,
			DockSide resourceFiles, DockSide resourceJson, DockSide resourcePng, DockSide output, DockSide variables,
			ToolWindow activeLeft, ToolWindow activeRight, ToolWindow activeBottom,
			boolean manualVisible, boolean insertVisible, boolean apiDocsVisible, boolean componentEditorVisible, boolean datapackJsonEditorVisible, boolean textJsonVisible,
			boolean minecraftUiVisible,
			boolean filesVisible, boolean datapackVisible,
			boolean resourceFilesVisible, boolean resourceJsonVisible, boolean resourcePngVisible,
			boolean outputVisible, boolean variablesVisible,
			DockSide git, DockSide personalization, boolean gitVisible, boolean personalizationVisible,
			DockSide export, boolean exportVisible) {
		private static WorkspaceLayout defaults() {
			return new WorkspaceLayout(false, -1, -1, -1,
					DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING,
					DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING,
					DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING, DockSide.FLOATING,
					null, null, null,
					false, false, false, false, false, false, false, false, false, false, false, false, false, false,
					DockSide.FLOATING, DockSide.FLOATING, false, false,
					DockSide.FLOATING, false);
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
					parseDockSide(properties, "datapackJsonEditor", defaults.datapackJsonEditor()),
					parseDockSide(properties, "textJson", defaults.textJson()),
					parseDockSide(properties, "minecraftUi", defaults.minecraftUi()),
					parseDockSide(properties, "files", defaults.files()),
					parseDockSide(properties, "datapackFiles", defaults.datapackFiles()),
					parseDockSide(properties, "resourceFiles", defaults.resourceFiles()),
					parseDockSide(properties, "resourceJson", defaults.resourceJson()),
					parseDockSide(properties, "resourcePng", defaults.resourcePng()),
					parseDockSide(properties, "output", defaults.output()),
					parseDockSide(properties, "variables", defaults.variables()),
					parseToolWindow(properties, "activeLeft"),
					parseToolWindow(properties, "activeRight"),
					parseToolWindow(properties, "activeBottom"),
					Boolean.parseBoolean(properties.getProperty("manualVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("insertVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("apiDocsVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("componentEditorVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("datapackJsonEditorVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("textJsonVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("minecraftUiVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("filesVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("datapackVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("resourceFilesVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("resourceJsonVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("resourcePngVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("outputVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("variablesVisible", "false")),
					parseDockSide(properties, "git", defaults.git()),
					parseDockSide(properties, "personalization", defaults.personalization()),
					Boolean.parseBoolean(properties.getProperty("gitVisible", "false")),
					Boolean.parseBoolean(properties.getProperty("personalizationVisible", "false")),
					parseDockSide(properties, "export", defaults.export()),
					Boolean.parseBoolean(properties.getProperty("exportVisible", "false")));
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
		properties.setProperty("datapackJsonEditor", layout.datapackJsonEditor().name());
		properties.setProperty("textJson", layout.textJson().name());
		properties.setProperty("minecraftUi", layout.minecraftUi().name());
		properties.setProperty("files", layout.files().name());
		properties.setProperty("datapackFiles", layout.datapackFiles().name());
		properties.setProperty("resourceFiles", layout.resourceFiles().name());
		properties.setProperty("resourceJson", layout.resourceJson().name());
		properties.setProperty("resourcePng", layout.resourcePng().name());
		properties.setProperty("output", layout.output().name());
		properties.setProperty("variables", layout.variables().name());
		properties.setProperty("git", layout.git().name());
		properties.setProperty("personalization", layout.personalization().name());
		properties.setProperty("export", layout.export().name());
		properties.setProperty("activeLeft", layout.activeLeft() == null ? "" : layout.activeLeft().name());
		properties.setProperty("activeRight", layout.activeRight() == null ? "" : layout.activeRight().name());
		properties.setProperty("activeBottom", layout.activeBottom() == null ? "" : layout.activeBottom().name());
		properties.setProperty("manualVisible", Boolean.toString(layout.manualVisible()));
		properties.setProperty("insertVisible", Boolean.toString(layout.insertVisible()));
		properties.setProperty("apiDocsVisible", Boolean.toString(layout.apiDocsVisible()));
		properties.setProperty("componentEditorVisible", Boolean.toString(layout.componentEditorVisible()));
		properties.setProperty("datapackJsonEditorVisible", Boolean.toString(layout.datapackJsonEditorVisible()));
		properties.setProperty("textJsonVisible", Boolean.toString(layout.textJsonVisible()));
		properties.setProperty("minecraftUiVisible", Boolean.toString(layout.minecraftUiVisible()));
		properties.setProperty("filesVisible", Boolean.toString(layout.filesVisible()));
		properties.setProperty("datapackVisible", Boolean.toString(layout.datapackVisible()));
		properties.setProperty("resourceFilesVisible", Boolean.toString(layout.resourceFilesVisible()));
		properties.setProperty("resourceJsonVisible", Boolean.toString(layout.resourceJsonVisible()));
		properties.setProperty("resourcePngVisible", Boolean.toString(layout.resourcePngVisible()));
		properties.setProperty("outputVisible", Boolean.toString(layout.outputVisible()));
		properties.setProperty("variablesVisible", Boolean.toString(layout.variablesVisible()));
		properties.setProperty("gitVisible", Boolean.toString(layout.gitVisible()));
		properties.setProperty("personalizationVisible", Boolean.toString(layout.personalizationVisible()));
		properties.setProperty("exportVisible", Boolean.toString(layout.exportVisible()));
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
		this(previousScreen, scriptName, null);
	}

	LuaIdeScreen(Screen previousScreen, String scriptName, ImGuiIdeScreen nativeOwner) {
		super(Component.literal("MC Lua IDE"));
		this.nativeOwner = nativeOwner;
		this.previousScreen = previousScreen;
		var client = net.minecraft.client.Minecraft.getInstance();
		var server = client.getSingleplayerServer();
		String scope = server != null ? "world:" + server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.toAbsolutePath().normalize() : client.getCurrentServer() != null
				? "server:" + client.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "local";
		if (client.player != null) scope += ":player:" + client.player.getUUID();
		session = nativeOwner == null ? new LuaIdeSession(WORKSPACE_LAYOUT_FILE.getParent(), scope) : nativeOwner.nativeSession();
		fileRecovery = LuaFileRecovery.forSession(session);
		recoveryPrompt = nativeOwner == null && !fileRecovery.entries().isEmpty();
		breakpointStore = LuaBreakpointClient.store();
		LuaIdeSession.State savedSession = nativeOwner == null ? session.load() : new LuaIdeSession.State(List.of(), "");
		for (LuaIdeSession.Document saved : savedSession.documents()) {
			OpenDocument document = new OpenDocument(saved.name(), saved.json(), saved.resource());
			document.cursor = saved.cursor(); document.scroll = saved.scroll();
			document.pendingFolds = saved.folds();
			openDocuments.put(document.key(), document);
		}
		currentDocument = openDocuments.get(savedSession.active());
		this.scriptName = scriptName == null ? "" : scriptName;
		if (currentDocument != null) this.scriptName = currentDocument.name;
		else if (!this.scriptName.isBlank() && !Files.isRegularFile(session.file)) {
			currentDocument = new OpenDocument(this.scriptName, false, false);
			openDocuments.put(currentDocument.key(), currentDocument);
		}
		try {
			float savedScale = Float.parseFloat(windowGeometry.getProperty("interfaceScale", ""));
			if (Float.isFinite(savedScale)) interfaceScale = Math.clamp(savedScale, 0.10F, 3.0F);
		}
		catch (NumberFormatException ignored) { }
		firstManualPending = nativeOwner == null && !firstManualShown && currentDocument == null && !Files.isRegularFile(WORKSPACE_LAYOUT_FILE);
		if (nativeOwner != null) interfaceScale = 1;
		if (firstManualPending) firstManualShown = true;
	}

	@Override
	protected void init() {
		editorContextMenu.close();
		clearDockTabDrag();
		if (editor != null && currentDocument != null) rememberCurrentDocument();
		prepareLogicalDimensions();
		ensureManualGeometry();
		ensureOutputGeometry();
		codeFont = personalization.editorFont(minecraft);
		editor = MultiLineEditBox.builder()
				.setX(EDITOR_X)
				.setY(68)
				.setShowBackground(false)
				.setShowDecorations(false)
				.setCursorColor(personalization.cursorColor)
				.setPlaceholder(Component.translatable("screen.mc_lua_ide.code_hint"))
				.build(codeFont, width - EDITOR_X - 12, normalEditorHeight(), Component.translatable("screen.mc_lua_ide.editor"));
		editor.setCharacterLimit(LuaProtocol.MAX_SCRIPT_CHARS);
		LuaSyntaxHighlighter.register(editor);
		LuaSyntaxHighlighter.setJsonMode(editor, currentDocument != null && currentDocument.json
				&& !currentDocument.name.endsWith(".mcfunction"));
		LuaSyntaxHighlighter.setFunctionMode(editor, currentDocument != null
				&& currentDocument.name.endsWith(".mcfunction"));
		LuaSyntaxHighlighter.setTheme(editor, theme);
		LuaSyntaxHighlighter.setScale(editor, codeScale);
		LuaSyntaxHighlighter.setPersonalization(editor, personalization);
		LuaEditorViewport.register(editor, codeFont, personalization.codeWordWrap);
		editor.setValueListener(value -> {
			if (!value.equals(cachedSource) && currentDocument != null) {
				ai.edited(aiPath(currentDocument)); hoveredAiMark = null; aiRuntimeProblem = null;
				currentDocument.mcpMarks = List.of();
			}
			if (currentDocument != null && !currentDocument.json && sourceLoaded) {
				boolean hasBreakpoints = !breakpointStore.lines(currentDocument.name).isEmpty();
				// Document loads set cachedSource before setValue; undo/indent edits do not.
				breakpointStore.edit(currentDocument.name, cachedSource, value);
				if (hasBreakpoints) LuaBreakpointClient.changed();
			}
			cachedSource = value;
			if (currentDocument != null && currentDocument.binary()) return;
			recordHistory(value);
			refreshSuggestions();
			scheduleValidation(value);
			if (searchOpen) refreshSearchMatches(false);
		});
		addRenderableWidget(editor);
		searchBox = new EditBox(font, 0, 0, 120, 20, Component.literal(LuaUiText.text("代码搜索")));
		searchBox.setMaxLength(500);
		searchBox.setHint(Component.literal(LuaUiText.text("输入要查找的代码…")));
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
		if (variableOverlay == null) variableOverlay = new LuaVariableOverlay(font);
		variableOverlay.resize(width, height);
		if (componentEditorOverlay == null) {
			componentEditorOverlay = new LuaComponentEditorOverlay(minecraft, font,
					this::insertComponentPatch, message -> status = message);
		}
		componentEditorOverlay.resize(width, height);
		if (datapackJsonEditorOverlay == null) {
			datapackJsonEditorOverlay = new LuaDatapackJsonEditorOverlay(minecraft, font,
					this::insertDatapackJson, message -> status = message,
					() -> currentDatapackJsonCategory() != null ? currentDocument.name : null,
					this::currentDatapackJson);
		}
		datapackJsonEditorOverlay.resize(width, height);
		if (textJsonOverlay == null) {
			textJsonOverlay = new LuaTextJsonOverlay(minecraft, font,
					this::insertTextJson, message -> status = message);
		}
		textJsonOverlay.resize(width, height);
		if (minecraftUiEditorOverlay == null) {
			minecraftUiEditorOverlay = new LuaMinecraftUiEditorOverlay(minecraft, font,
					this::insertMinecraftUiTemplate, message -> status = message);
		}
		minecraftUiEditorOverlay.resize(width, height);
		if (fileOverlay == null) {
			fileOverlay = new LuaFileOverlay(minecraft, font, scriptName, this::openScript,
					message -> status = message);
		}
		fileOverlay.resize(width, height);
		if (datapackOverlay == null) {
			datapackOverlay = new LuaFileOverlay(minecraft, font, "", this::openDatapackFile,
					message -> status = message, LuaFileOverlay.Mode.DATAPACK, this::onDatapackRenamed);
		}
		datapackOverlay.resize(width, height);
		if (resourceDirectoryOverlay == null) resourceDirectoryOverlay = new LuaResourcePackToolOverlay(
				minecraft, font, LuaResourcePackToolOverlay.Kind.DIRECTORY, this::openResourcePackFile,
				ignored -> { }, this::currentResourcePng, message -> status = message, this::canChangeResourcePath);
		if (resourceJsonOverlay == null) resourceJsonOverlay = new LuaResourcePackToolOverlay(
				minecraft, font, LuaResourcePackToolOverlay.Kind.JSON, ignored -> { },
				this::replaceResourceJsonSource, this::currentResourcePng, this::resourceJsonMessage,
				ignored -> true, () -> currentDocument != null && currentDocument.resource && !currentDocument.binary()
						? currentDocument.name : "");
		if (resourcePngOverlay == null) resourcePngOverlay = new LuaResourcePackToolOverlay(
				minecraft, font, LuaResourcePackToolOverlay.Kind.PNG, ignored -> { },
				ignored -> { }, this::currentResourcePng, message -> status = message, ignored -> true);
		resourceDirectoryOverlay.resize(width, height);
		resourceJsonOverlay.resize(width, height);
		resourcePngOverlay.resize(width, height);
		initializeExtensionWindows();
		if (nativeOwner == null) restoreWorkspaceLayout();
		if (variableOverlay.isOpen()) LuaDebugHud.setIdeVariablesEnabled(true);
		if (firstManualPending) {
			firstManualPending = false;
			manualOpen = true;
			manualScroll = 0;
			insertOverlay.close();
			apiDocsOverlay.close();
			variableOverlay.close();
			componentEditorOverlay.close();
			datapackJsonEditorOverlay.close();
			textJsonOverlay.close();
			minecraftUiEditorOverlay.close();
			fileOverlay.close();
			datapackOverlay.close();
			resourceDirectoryOverlay.close();
			resourceJsonOverlay.close();
			resourcePngOverlay.close();
			outputVisible = false;
			activateDockedTool(ToolWindow.MANUAL);
		}
		applyDockLayout();
		// Initial font/viewport sizing reflows an empty field. Bind only when the document is ready,
		// otherwise that temporary empty value would erase its existing fold anchors.
		LuaFunctionFolding.bind(textField(), currentDocument == null ? draftFunctionFolds
				: currentDocument.json ? null : currentDocument.functionFolds);
		if (currentDocument != null) restoreCurrentDocument();
		else {
			sourceLoaded = false;
			sourceLoading = false;
			cachedSource = "";
			lastSavedSource = "";
			lastSnapshot = "";
			syntaxMessage = LuaUiText.text("● 未打开脚本");
			validationDetail = LuaUiText.text("从文件窗口新建或读取脚本");
			status = LuaUiText.text("尚未打开文件 · 点击“文件 → Lua / datapack / resourcepacks 目录”");
		}

		ensureCurrentDocumentTabVisible();
		fileOverlay.setCurrentScript(currentDocument != null && !currentDocument.json ? currentDocument.name : "");
		datapackOverlay.setCurrentScript(currentDocument != null && currentDocument.json && !currentDocument.resource ? currentDocument.name : "");
		buildMenuBar();
		if (personalizationPanel != null) personalizationPanel.resize(width, height);
		if (gitPanel != null) gitPanel.resize(width, height);
		if (exportPanel != null) exportPanel.resize(width, height);
		syncOverlayState();
		if (restoreRunPending) {
			restoreRunPending = false;
			restoreLastRun();
		}
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
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("文件")), button -> toggleMenu("file"))
				.bounds(8, 8, 58, 20).build()));
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("UI与窗口")), button -> toggleMenu("window"))
				.bounds(68, 8, 94, 20).build()));
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("搜索")), button -> openSearchFromSelection())
				.bounds(164, 8, 58, 20).build()));
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal("Git"), button -> openGitScreen())
				.bounds(224, 8, 46, 20).build()));
		topMenuButtons.add(addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("工具")), button -> toggleMenu("tools"))
				.bounds(272, 8, 52, 20).build()));
		runButton = addRenderableWidget(Button.builder(runControlLabel(isScriptRunning()), button -> toggleRun())
				.bounds(326, 8, 86, 20).build());
		topMenuButtons.add(runButton);

		fileMenuItems.clear();
		fileMenuItems.add(menuItem(LuaUiText.text("Lua 目录"), 8, 30, 150, this::toggleFileOverlay));
		fileMenuItems.add(menuItem(LuaUiText.text("datapack 目录"), 8, 52, 150, this::toggleDatapackOverlay));
		fileMenuItems.add(menuItem(LuaUiText.text("resourcepacks 目录"), 8, 74, 150, this::toggleResourceDirectory));
		fileMenuItems.add(menuItem(LuaUiText.text("保存"), 8, 96, 150, this::saveScript));
		runMenuButton = menuItem(LuaUiText.text("运行"), 8, 118, 150, this::toggleRun);
		fileMenuItems.add(runMenuButton);
		int toolsX = Math.max(0, Math.min(272, width - 210));
		buildToolsMenu();
		foldingMenuItems.clear();
		int foldWidth = Math.min(160, width);
		int foldX = toolsX + 212 + foldWidth <= width ? toolsX + 212
				: Math.max(0, toolsX - foldWidth - 2);
		int foldY = Math.max(30, Math.min(184, height - 66));
		foldingMenuItems.add(menuItem(LuaUiText.text("展开全部代码"), foldX, foldY, foldWidth,
				() -> applyCodeFolding(LuaFunctionFolding.Action.EXPAND_ALL)));
		foldingMenuItems.add(menuItem(LuaUiText.text("折叠全部代码"), foldX, foldY + 22, foldWidth,
				() -> applyCodeFolding(LuaFunctionFolding.Action.COLLAPSE_ALL)));
		foldingMenuItems.add(menuItem(LuaUiText.text("折叠函数代码"), foldX, foldY + 44, foldWidth,
				() -> applyCodeFolding(LuaFunctionFolding.Action.COLLAPSE_FUNCTIONS)));
		updateRunControls();

		fontMenuItems.clear();
		addScalePresets(fontMenuItems, ScaleTarget.FONT);

		interfaceScaleMenuItems.clear();
		addScalePresets(interfaceScaleMenuItems, ScaleTarget.INTERFACE);

		themeMenuItems.clear();
		themeMenuItems.add(menuItem(LuaUiText.text("深色"), 240, 30, 136, () -> setTheme(LuaSyntaxHighlighter.Theme.DARK, LuaUiText.text("深色"))));
		themeMenuItems.add(menuItem(LuaUiText.text("浅色"), 240, 52, 136, () -> setTheme(LuaSyntaxHighlighter.Theme.LIGHT, LuaUiText.text("浅色"))));
		themeMenuItems.add(menuItem(LuaUiText.text("高对比度"), 240, 74, 136, () -> setTheme(LuaSyntaxHighlighter.Theme.HIGH_CONTRAST, LuaUiText.text("高对比度"))));

		windowMenuItems.clear();
		windowMenuItems.add(submenuItem(LuaUiText.text("视图  ▶"), 68, 30, 170, "view"));
		windowMenuItems.add(submenuItem(LuaUiText.text("字号  ▶"), 68, 52, 170, "font"));
		windowMenuItems.add(submenuItem(LuaUiText.text("配色  ▶"), 68, 74, 170, "theme"));
		windowMenuItems.add(submenuItem(LuaUiText.text("界面缩放  ▶"), 68, 96, 170, "interface_scale"));
		windowMenuItems.add(menuItem(LuaUiText.text("个性化…"), 68, 118, 170, this::openPersonalization));
		layoutLockButton = menuItem(layoutLockLabel(), 68, 140, 170, this::toggleLayoutLock);
		windowMenuItems.add(layoutLockButton);
		windowMenuItems.add(submenuItem(LuaUiText.text("Mod 窗口  ▶"), 68, 162, 170, "extensions"));
		buildExtensionMenu();

		buildViewMenu();

		scaleInput = new EditBox(font, 240, 164, 84, 20, Component.literal(LuaUiText.text("缩放百分比")));
		scaleInput.setMaxLength(4);
		scaleInput.visible = false;
		scaleInput.active = false;
		addRenderableWidget(scaleInput);
		scaleApplyButton = Button.builder(Component.literal(LuaUiText.text("应用")), ignored -> applyCustomScale())
				.bounds(326, 164, 50, 20).build();
		scaleApplyButton.visible = false;
		addRenderableWidget(scaleApplyButton);
		hideMenus();
	}

	private boolean isScriptRunning() {
		return runningExecution != null && runningExecution.isRunning();
	}

	private Component runControlLabel(boolean running) {
		return Component.literal(running ? LuaUiText.text("■ 停止") : LuaUiText.text("▶ 运行"))
				.withColor(running ? 0xFF7777 : 0x72E69A);
	}

	private void updateRunControls() {
		boolean running = isScriptRunning();
		if (runButton != null) runButton.setMessage(runControlLabel(running));
		if (runButton != null) runButton.active = running || currentDocument != null
				&& (!currentDocument.json || isFunctionDocument());
		if (runMenuButton != null) {
			runMenuButton.active = running || currentDocument != null
					&& (!currentDocument.json || isFunctionDocument());
			runMenuButton.setMessage(Component.literal(running ? LuaUiText.text("停止") : LuaUiText.text("运行"))
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

	private void openIndependentUi() {
		if (!ImGuiIdeRuntime.available()) { status = LuaUiText.text("新版 UI 不可用，请查看日志", "New UI unavailable; see log"); return; }
		rememberCurrentDocument();
		requestClose(openDocuments.values().stream().filter(OpenDocument::dirty).toList(), () -> {
			recoveryClosed = true;
			for (String key : List.copyOf(recoveryTracking.keySet())) clearRecoveryDraft(key);
			saveSession();
			minecraft.setScreen(new ImGuiIdeScreen(previousScreen, currentDocument != null && !currentDocument.json ? currentDocument.name : ""));
		});
	}

	private void buildToolsMenu() {
		toolMenuItems.forEach(this::removeWidget); toolMenuItems.clear();
		String[] names = {LuaUiText.text("整理代码"), LuaUiText.text("重载资源包"), LuaUiText.text("重载数据包"), LuaUiText.text("上传并设置服务器资源包…"), LuaUiText.text("导出当前 API…"),
				LuaUiText.text("服务器配置…"), LuaUiText.text("文件恢复…"), LuaUiText.text("代码折叠  ▶"), LuaUiText.text("MCP 服务器…"), LuaUiText.text("AI工具…"),
				LuaUiText.text("新版 UI…", "New UI…")};
		Runnable[] actions = {this::formatCurrentCode, this::reloadClientResourcePacks, this::reloadServerDatapacks,
				() -> setExtensionWindowOpen(LuaServerPackWindow.ID, true), this::exportCurrentApiDocumentation,
				() -> minecraft.setScreen(new LuaSettingsScreen(this)), () -> setExtensionWindowOpen(LuaFileRecoveryWindow.ID, true),
				() -> showSubmenu("folding"), () -> setExtensionWindowOpen(LuaMcpWindow.CLIENT_ID, true),
				() -> setExtensionWindowOpen(LuaAiWindow.ID, true), this::openIndependentUi};
		int rows = Math.max(3, (height - 34) / 22), perPage = rows >= names.length ? names.length : rows - 2;
		int pages = (names.length + perPage - 1) / perPage;
		toolsMenuPage = Math.clamp(toolsMenuPage, 0, pages - 1);
		int x = Math.max(0, Math.min(272, width - 210)), row = 0;
		for (int index = toolsMenuPage * perPage; index < Math.min(names.length, (toolsMenuPage + 1) * perPage); index++) {
			if (index == 7) toolMenuItems.add(submenuItem(names[index], x, 30 + row * 22, 210, "folding"));
			else toolMenuItems.add(menuItem(names[index], x, 30 + row * 22, 210, actions[index]));
			row++;
		}
		if (pages > 1) for (int delta : new int[]{-1, 1}) {
			int direction = delta;
			toolMenuItems.add(Button.builder(Component.literal((delta < 0 ? LuaUiText.text("上一页") : LuaUiText.text("下一页")) + " · " + (toolsMenuPage + 1) + "/" + pages), ignored -> {
				toolsMenuPage = Math.floorMod(toolsMenuPage + direction, pages); buildToolsMenu();
				toolMenuItems.forEach(item -> item.visible = true); activeSubmenu = ""; foldingMenuItems.forEach(item -> item.visible = false);
			}).bounds(x, 30 + row++ * 22, 210, 20).build());
		}
		// Pagination buttons must participate in the same input dispatch as all other menus.
		for (Button button : toolMenuItems) if (!children().contains(button)) addRenderableWidget(button);
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
			case "tools" -> toolMenuItems;
			default -> List.of();
		};
		items.forEach(item -> item.visible = true);
		editor.active = false;
	}

	private void showSubmenu(String submenu) {
		boolean opening = !activeSubmenu.equals(submenu);
		activeSubmenu = opening ? submenu : "";
		foldingMenuItems.forEach(item -> item.visible = false);
		fontMenuItems.forEach(item -> item.visible = false);
		interfaceScaleMenuItems.forEach(item -> item.visible = false);
		themeMenuItems.forEach(item -> item.visible = false);
		viewMenuItems.forEach(item -> item.visible = false);
		extensionMenuItems.forEach(item -> item.visible = false);
		setScaleEditorVisible(false);
		if (!opening) return;
		List<Button> items = switch (submenu) {
			case "folding" -> foldingMenuItems;
			case "font" -> fontMenuItems;
			case "interface_scale" -> interfaceScaleMenuItems;
			case "theme" -> themeMenuItems;
			case "view" -> viewMenuItems;
			case "extensions" -> extensionMenuItems;
			default -> List.of();
		};
		items.forEach(item -> item.visible = true);
		if (submenu.equals("font")) showScaleEditor(ScaleTarget.FONT);
		if (submenu.equals("interface_scale")) showScaleEditor(ScaleTarget.INTERFACE);
	}

	private void dismissDirectoryMenus() {
		if (fileOverlay != null) fileOverlay.dismissContext();
		if (datapackOverlay != null) datapackOverlay.dismissContext();
		if (resourceDirectoryOverlay != null) resourceDirectoryOverlay.dismissDirectoryContext();
	}

	private void hideMenus() {
		activeMenu = "";
		activeSubmenu = "";
		fileMenuItems.forEach(item -> item.visible = false);
		toolMenuItems.forEach(item -> item.visible = false);
		foldingMenuItems.forEach(item -> item.visible = false);
		fontMenuItems.forEach(item -> item.visible = false);
		interfaceScaleMenuItems.forEach(item -> item.visible = false);
		themeMenuItems.forEach(item -> item.visible = false);
		windowMenuItems.forEach(item -> item.visible = false);
		viewMenuItems.forEach(item -> item.visible = false);
		extensionMenuItems.forEach(item -> item.visible = false);
		setScaleEditorVisible(false);
		syncOverlayState();
	}

	private void applyCodeFolding(LuaFunctionFolding.Action action) {
		if (!sourceLoaded || isJsonDocument() || isFunctionDocument() || editor == null || !editor.visible
				|| !LuaFunctionFolding.apply(textField(), action)) {
			status = LuaUiText.text("请先打开 Lua 文件，代码折叠仅改变显示，不修改源代码");
			return;
		}
		clearSuggestions();
		editor.setScrollAmount(editor.scrollAmount());
		rememberCurrentDocument();
		status = switch (action) {
			case EXPAND_ALL -> LuaUiText.text("已展开全部代码");
			case COLLAPSE_ALL -> LuaUiText.text("已折叠全部可折叠代码（函数、if 和数组／表）");
			case COLLAPSE_FUNCTIONS -> LuaUiText.text("已折叠全部函数代码");
		};
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
				status = LuaUiText.text("缩放范围必须是 10%–300%");
				return;
			}
			applyScale(scaleTarget, percent);
			hideMenus();
		} catch (NumberFormatException exception) {
			status = LuaUiText.text("请输入 10–300 之间的整数百分比");
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
		return (layoutLocked ? "✓ " : "□ ") + LuaUiText.text("固定当前布局");
	}

	private void toggleLayoutLock() {
		layoutLocked = !layoutLocked;
		if (layoutLockButton != null) layoutLockButton.setMessage(Component.literal(layoutLockLabel()));
		setOverlayLayoutLocked();
		hideMenus();
		status = layoutLocked
				? LuaUiText.text("已固定当前吸附布局 · 浮动窗口仍可自由移动")
				: LuaUiText.text("已解除布局固定 · 可拖动吸附窗口或分隔线");
	}

	private void setFontScale(float scale, String label) {
		codeScale = Math.max(0.10F, Math.min(3.0F, scale));
		personalization.fontPercent = Math.round(codeScale * 100);
		savePersonalization();
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

	private void zoomCodeFont(double wheelAmount) {
		if (!Double.isFinite(wheelAmount) || wheelAmount == 0) return;
		int percent = Math.max(10, Math.min(300,
				personalization.fontPercent + (int) Math.round(wheelAmount * 10)));
		if (percent != personalization.fontPercent) setFontScale(percent / 100F, percent + "%");
	}

	private void setTheme(LuaSyntaxHighlighter.Theme newTheme, String label) {
		theme = newTheme;
		personalization.theme = newTheme;
		savePersonalization();
		LuaSyntaxHighlighter.setTheme(editor, newTheme);
		status = "配色已切换为：" + label;
	}

	private boolean canResizeDock() {
		if (nativeOwner != null) return false;
		return !layoutLocked || personalization.resizeDockWhenLocked;
	}

	private void openPersonalization() {
		if (personalizationPanel == null) {
			personalizationPanel = new LuaPersonalizationScreen(this, personalization);
			personalizationPanel.restoreGeometry(WindowGeometry.read(windowGeometry, "personalization"));
			personalizationPanel.openEmbedded(width, height);
			personalizationPanel.setDockSide(rememberedPersonalizationDock);
		} else personalizationPanel.reopen();
		frontSettingsPanel = personalizationPanel;
		focusedSettingsPanel = personalizationPanel;
		hideMenus();
		activateDockedTool(ToolWindow.PERSONALIZATION);
		applyDockLayout();
	}

	private void openGitScreen() {
		if (gitPanel == null) {
			gitPanel = new LuaGitScreen(this);
			gitPanel.restoreGeometry(WindowGeometry.read(windowGeometry, "git"));
			gitPanel.openEmbedded(width, height);
			gitPanel.setDockSide(rememberedGitDock);
		} else gitPanel.reopen();
		frontSettingsPanel = gitPanel;
		focusedSettingsPanel = gitPanel;
		hideMenus();
		activateDockedTool(ToolWindow.GIT);
		applyDockLayout();
	}

	boolean hasUnsavedDocuments() {
		if (currentDocument != null) rememberCurrentDocument();
		return openDocuments.values().stream().anyMatch(OpenDocument::dirty);
	}

	/** Reload only saved server documents. Edits made while a Git request was in flight are preserved. */
	void gitWorkspaceRestored() {
		if (!isCurrentIde()) return;
		if (currentDocument != null) rememberCurrentDocument();
		for (OpenDocument document : openDocuments.values()) {
			if (document.resource || document.dirty()) continue;
			document.readRevision++; document.loading = false;
			if (document.preview != null) document.preview.close(minecraft);
			document.preview = null;
			document.loaded = false;
			document.undo.clear(); document.redo.clear();
		}
		moduleSources.clear(); moduleSourceFailures.clear();
		if (currentDocument != null && !currentDocument.resource && !currentDocument.dirty() && !currentDocument.loading)
			restoreCurrentDocument();
		if (fileOverlay != null) fileOverlay.refreshScripts();
		if (datapackOverlay != null) datapackOverlay.refreshScripts();
	}

	/** Apply authoritative MCP saves to loaded and background tabs, without losing unsaved edits. */
	void syncMcpFiles() {
		if (editor == null || !isCurrentIde() || recoveryPrompt || pendingCloseAction != null || closeSaving) return;
		rememberCurrentDocument();
		if (observedMcpRevision != LuaMcpFileSync.revision()) {
			observedMcpRevision = LuaMcpFileSync.revision();
			for (var update : LuaMcpFileSync.snapshots()) {
				moduleSources.remove(update.change().name()); moduleSourceFailures.remove(update.change().name());
			}
			if (fileOverlay != null) fileOverlay.refreshScripts();
		}
		for (OpenDocument document : openDocuments.values()) {
			if (document.json || document.resource) continue;
			var update = LuaMcpFileSync.latest(document.name);
			if (update == null || update.revision() <= document.mcpRevision) continue;
			document.mcpRevision = update.revision();
			if (document.dirty() && !document.source.equals(update.change().source())) {
				minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(useMcp -> {
					var latest = LuaMcpFileSync.latest(document.name);
					if (latest != null && openDocuments.containsValue(document)) {
						document.mcpRevision = latest.revision();
						if (useMcp) applyMcpUpdate(document, latest);
						else {
							document.savedSource = latest.change().source(); document.mcpMarks = List.of();
							if (document == currentDocument) lastSavedSource = document.savedSource;
						}
					}
					minecraft.setScreen(this);
					status = useMcp ? LuaUiText.text("已同步 MCP 修改 · 原内容可 Ctrl+Z 撤销找回") : "已保留未保存内容；服务器文件已由 MCP 修改，保存将覆盖服务器版本";
				}, Component.literal(LuaUiText.text("MCP 修改了 ") + document.displayName()),
						Component.literal(LuaUiText.text("此文件有未保存内容。载入 MCP 最新版本吗？原内容将保留在撤销记录中。保留草稿不会改写服务器文件。")),
						Component.literal(LuaUiText.text("载入 MCP 版本")), Component.literal(LuaUiText.text("保留未保存内容"))));
				return;
			}
			applyMcpUpdate(document, update);
		}
	}
	private void applyMcpUpdate(OpenDocument document, LuaMcpFileSync.Update update) {
		String before = document.source, source = update.change().source();
		boolean changed = !before.equals(source);
		if (document.loaded && changed) { document.undo.addLast(before); LuaTextHistory.trim(document.undo); document.redo.clear(); }
		document.cursor = wiki.ica.xiaomiao.mcluaide.network.LuaTextChanges.hash(before).equals(update.change().beforeHash())
				? wiki.ica.xiaomiao.mcluaide.network.LuaTextChanges.mapCursor(update.change().edits(), document.cursor, source.length())
				: LuaTextHistory.mapCursor(before, source, document.cursor);
		if (changed && document.loaded) {
			breakpointStore.edit(document.name, before, source);
			LuaBreakpointClient.changed();
		}
		document.readRevision++; document.loading = false; document.loaded = true;
		document.source = document.savedSource = document.lastSnapshot = source;
		document.mcpMarks = update.dismissed() ? List.of() : mcpMarks(document, update.change());
		document.mcpSource = source; document.mcpRevision = update.revision();
		moduleSources.remove(document.name); moduleSourceFailures.remove(document.name);
		if (document == currentDocument) {
			editorContextMenu.close(); restoreCurrentDocument(); clearSuggestions();
		}
		status = "MCP 已同步：" + document.displayName() + " · 修改处已标色，悬浮按 Esc 清除";
	}
	private List<LuaAiAgent.Mark> mcpMarks(OpenDocument document, wiki.ica.xiaomiao.mcluaide.network.LuaMcpFileChanged change) {
		List<LuaAiAgent.Mark> marks = new ArrayList<>(); String source = change.source();
		for (var edit : change.edits()) {
			int start = edit.start(), end = edit.end();
			boolean removed = start == end;
			if (removed) { start = Math.max(0, start - (start == source.length() ? 1 : 0)); end = Math.min(source.length(), start + 1); }
			if (start > 0 && start < source.length() && Character.isLowSurrogate(source.charAt(start))) start--;
			if (end > 0 && end < source.length() && Character.isLowSurrogate(source.charAt(end))) end++;
			if (start < end) marks.add(new LuaAiAgent.Mark(aiPath(document), "", start, end,
					removed ? "MCP 在此位置删除了代码。文件已同步到服务器保存版本。" : "这段代码由 MCP 修改，已同步到服务器保存版本。", "mcp"));
		}
		return List.copyOf(marks);
	}
	private void clearMcpMarks() {
		LuaMcpFileSync.dismissAll();
		for (var document : openDocuments.values()) document.mcpMarks = List.of();
	}

	void closePersonalizationPanel(LuaPersonalizationScreen panel) {
		if (personalizationPanel != panel) return;
		panel.captureGeometry().write(windowGeometry, "personalization");
		rememberedPersonalizationDock = panel.dockSide();
		personalizationPanel = null;
		settingsPanelClosed(panel);
	}

	void closeGitPanel(LuaGitScreen panel) {
		if (gitPanel != panel) return;
		panel.captureGeometry().write(windowGeometry, "git");
		rememberedGitDock = panel.dockSide();
		gitPanel = null;
		settingsPanelClosed(panel);
	}

	void closeExportPanel(LuaApiExportScreen panel) {
		if (exportPanel != panel) return;
		panel.captureGeometry().write(windowGeometry, "export");
		rememberedExportDock = panel.dockSide();
		exportPanel = null;
		settingsPanelClosed(panel);
	}

	private void settingsPanelClosed(Screen panel) {
		if (pressedSettingsPanel == panel) pressedSettingsPanel = null;
		if (frontSettingsPanel == panel) frontSettingsPanel = exportPanel != null ? exportPanel
				: gitPanel != null ? gitPanel : personalizationPanel;
		if (focusedSettingsPanel == panel) focusedSettingsPanel = frontSettingsPanel;
		applyDockLayout();
	}

	private Screen settingsPanelAt(double x, double y) {
		boolean extensionFloatingHit = extensionWindows.values().stream()
				.anyMatch(window -> !window.dockSide().docked() && window.hitFrame(x, y));
		if (frontSettingsPanel == exportPanel && exportPanel != null && toolCanInteract(ToolWindow.EXPORT)
				&& (!extensionFloatingHit || !exportPanel.dockSide().docked())
				&& exportPanel.contains(x, y)) return exportPanel;
		if (frontSettingsPanel == gitPanel && gitPanel != null && toolCanInteract(ToolWindow.GIT)
				&& (!extensionFloatingHit || !gitPanel.dockSide().docked())
				&& gitPanel.contains(x, y)) return gitPanel;
		if (frontSettingsPanel == personalizationPanel && personalizationPanel != null
				&& toolCanInteract(ToolWindow.PERSONALIZATION)
				&& (!extensionFloatingHit || !personalizationPanel.dockSide().docked())
				&& personalizationPanel.contains(x, y)) return personalizationPanel;
		if (gitPanel != null && toolCanInteract(ToolWindow.GIT)
				&& (!extensionFloatingHit || !gitPanel.dockSide().docked()) && gitPanel.contains(x, y)) return gitPanel;
		if (personalizationPanel != null && toolCanInteract(ToolWindow.PERSONALIZATION)
				&& (!extensionFloatingHit || !personalizationPanel.dockSide().docked())
				&& personalizationPanel.contains(x, y)) return personalizationPanel;
		if (exportPanel != null && toolCanInteract(ToolWindow.EXPORT)
				&& (!extensionFloatingHit || !exportPanel.dockSide().docked()) && exportPanel.contains(x, y))
			return exportPanel;
		return null;
	}

	private Screen focusedSettingsPanel() {
		if (focusedSettingsPanel == exportPanel && exportPanel != null && !exportPanel.isClosing()
				&& toolCanInteract(ToolWindow.EXPORT))
			return exportPanel;
		if (focusedSettingsPanel == gitPanel && gitPanel != null && !gitPanel.isClosing()
				&& toolCanInteract(ToolWindow.GIT))
			return gitPanel;
		if (focusedSettingsPanel == personalizationPanel && personalizationPanel != null
				&& !personalizationPanel.isClosing() && toolCanInteract(ToolWindow.PERSONALIZATION)) return personalizationPanel;
		focusedSettingsPanel = null;
		return null;
	}

	void setPersonalization(LuaPersonalizationSettings settings) {
		personalization = settings;
		theme = settings.theme;
		codeScale = settings.fontPercent / 100.0F;
		LuaEditorBackground.reload();
		pendingPersonalizationRebuild = true;
		status = LuaUiText.text("个性化设置已保存并应用");
	}

	private void savePersonalization() {
		try { personalization.save(); }
		catch (java.io.IOException error) { status = LuaUiText.text("保存个性化设置失败：") + error.getMessage(); }
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
		shortcutOpenedTools.remove(ToolWindow.MANUAL);
		if (revealDockedTool(ToolWindow.MANUAL)) return;
		if (manualOpen && !manualDock.docked() && !manualOnTop && insertOverlay != null
				&& insertOverlay.isOpen() && !insertOverlay.dockSide().docked()) {
			manualOnTop = true;
			hideMenus();
			syncOverlayState();
			status = LuaUiText.text("使用手册已切换到前面 · 再次点击可关闭");
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
		if (isFunctionDocument()) { toggleFunctionInsertOverlay(); return; }
		shortcutOpenedTools.remove(ToolWindow.INSERT);
		componentInsertionTarget = null;
		LuaCursorContext.Argument argument = currentArgumentContext();
		if (revealDockedTool(ToolWindow.INSERT)) {
			selectArgumentRange(argument);
			insertOverlay.open(argument == null ? null : argument.kind(),
					argument == null ? "" : argument.query());
			insertOverlay.requestKeyboardFocus();
			status = argument == null
					? LuaUiText.text("插入器已显示 · 输入文字搜索")
					: "已识别 " + argument.symbol() + " 的参数" + (argument.index() + 1)
							+ " · 自动切换到" + argument.kind().label();
			return;
		}
		if (insertOverlay.isOpen() && argument != null && insertOverlay.kind() != argument.kind()) {
			selectArgumentRange(argument);
			insertOverlay.open(argument.kind(), argument.query());
			insertOverlay.requestKeyboardFocus();
			activateDockedTool(ToolWindow.INSERT);
			status = "已根据光标切换到" + argument.kind().label();
			syncOverlayState();
			return;
		}
		if (insertOverlay.isOpen() && !insertOverlay.dockSide().docked()
				&& manualOpen && !manualDock.docked() && manualOnTop) {
			manualOnTop = false;
			insertOverlay.requestKeyboardFocus();
			hideMenus();
			syncOverlayState();
			status = LuaUiText.text("插入窗口已切换到前面 · 再次点击可关闭");
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
					? LuaUiText.text("插入器已打开 · 输入文字搜索 · Ctrl+I 关闭")
					: "已识别 " + argument.symbol() + " 的参数" + (argument.index() + 1)
							+ " · 自动切换到" + argument.kind().label();
		} else {
			insertOverlay.close();
			status = LuaUiText.text("已关闭插入器");
		}
		syncOverlayState();
	}

	private void toggleApiDocs() {
		if (apiDocsOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.API_DOCS);
		if (revealDockedTool(ToolWindow.API_DOCS)) return;
		hideMenus();
		if (apiDocsOverlay.isOpen()) {
			apiDocsOverlay.close();
			status = LuaUiText.text("已关闭 API 文档");
		} else {
			if (searchOpen) closeSearch();
			apiDocsOverlay.open();
			activateDockedTool(ToolWindow.API_DOCS);
			status = LuaUiText.text("API 文档已打开 · 可按中文、英文或拼音首字母筛选");
		}
		syncOverlayState();
	}

	private void toggleComponentEditor() {
		if (componentEditorOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.COMPONENT_EDITOR);
		if (revealDockedTool(ToolWindow.COMPONENT_EDITOR)) {
			componentEditorOverlay.requestKeyboardFocus();
			return;
		}
		hideMenus();
		if (componentEditorOverlay.isOpen()) {
			componentEditorOverlay.close();
			componentInsertionTarget = null;
			status = LuaUiText.text("已关闭数据组件编辑器");
		} else {
			componentInsertionTarget = null;
			componentEditorOverlay.open();
			activateDockedTool(ToolWindow.COMPONENT_EDITOR);
			clearSuggestions();
			status = LuaUiText.text("数据组件编辑器已打开 · 选择物品、方块或实体后可视化编辑");
		}
		syncOverlayState();
	}

	private void toggleTextJsonEditor() {
		if (textJsonOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.TEXT_JSON);
		textJsonInsertionTarget = null;
		if (revealDockedTool(ToolWindow.TEXT_JSON)) {
			textJsonOverlay.requestKeyboardFocus();
			return;
		}
		hideMenus();
		if (textJsonOverlay.isOpen()) {
			textJsonOverlay.close();
			status = LuaUiText.text("已关闭文本 JSON 编辑器");
		} else {
			textJsonOverlay.open();
			activateDockedTool(ToolWindow.TEXT_JSON);
			clearSuggestions();
			status = LuaUiText.text("文本 JSON 编辑器已打开 · 可设置十六进制颜色、字体和格式效果");
		}
		syncOverlayState();
	}

	private boolean formattingCode, reloadingResourcePacks, reloadingDatapacks;
	private String toolNotice = "";
	private long toolNoticeUntil;
	private void showToolNotice(String message) {
		status = message; toolNotice = message;
		toolNoticeUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
	}

	private void formatCurrentCode() {
		if (formattingCode) { status = LuaUiText.text("正在整理代码…"); return; }
		if (!sourceLoaded || currentDocument == null || currentDocument.binary()) {
			status = LuaUiText.text("请先打开一个可编辑的代码文件"); return;
		}
		OpenDocument document = currentDocument;
		String source = editor.getValue();
		formattingCode = true;
		toolNoticeUntil = 0;
		status = LuaUiText.text("正在整理代码…");
		CompletableFuture.supplyAsync(() -> {
			try { return LuaCodeFormatter.format(source, document.name, document.json); }
			catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
		}).whenComplete((formatted, error) -> minecraft.execute(() -> {
			formattingCode = false;
			if (error != null) { showToolNotice(LuaUiText.text("整理失败：") + ScriptManager.message(error)); return; }
			if (currentDocument != document || !editor.getValue().equals(source)) {
				status = "文件已切换或内容已修改，未应用整理结果，请重试"; return;
			}
			if (source.equals(formatted)) { status = LuaUiText.text("代码缩进已整齐，无需修改"); return; }
			if (formatted.length() > LuaProtocol.MAX_SCRIPT_CHARS) { status = "整理后的文件超过编辑上限"; return; }
			int caret = LuaCodeFormatter.mapCaret(source, formatted, textField().cursor());
			replaceRange(0, source.length(), formatted);
			textField().seekCursor(Whence.ABSOLUTE, caret);
			clearSuggestions();
			showToolNotice(LuaUiText.text("已按 4 个空格整理代码 · Ctrl+Z 可撤销 · 尚未保存"));
		}));
	}

	private void reloadClientResourcePacks() {
		if (reloadingResourcePacks) { status = LuaUiText.text("资源包正在重载…"); return; }
		reloadingResourcePacks = true;
		toolNoticeUntil = 0;
		status = "正在重载客户端资源包（仅加载已启用的资源包，未保存内容不会生效）…";
		try {
			minecraft.getResourcePackRepository().reload();
			minecraft.reloadResourcePacks().whenComplete((ignored, error) -> minecraft.execute(() -> {
				reloadingResourcePacks = false;
				showToolNotice(error == null ? LuaUiText.text("资源包重载完成，插入 ID 列表已更新") : LuaUiText.text("资源包重载失败：") + ScriptManager.message(error));
			}));
		} catch (RuntimeException error) {
			reloadingResourcePacks = false; showToolNotice(LuaUiText.text("资源包重载失败：") + ScriptManager.message(error));
		}
	}

	private void reloadServerDatapacks() {
		if (reloadingDatapacks) { status = LuaUiText.text("数据包正在重载…"); return; }
		reloadingDatapacks = true;
		toolNoticeUntil = 0;
		status = "正在重载服务器数据包（请先保存；维度等动态注册表修改仍可能需要重进存档）…";
		ScriptManager.reloadDatapacks().whenComplete((message, error) -> minecraft.execute(() -> {
			reloadingDatapacks = false;
			showToolNotice(error == null ? message : LuaUiText.text("数据包重载失败：") + ScriptManager.message(error));
		}));
	}

	private void exportCurrentApiDocumentation() {
		if (exportPanel == null) {
			exportPanel = new LuaApiExportScreen(this);
			exportPanel.restoreGeometry(WindowGeometry.read(windowGeometry, "export"));
			exportPanel.openEmbedded(width, height);
			exportPanel.setDockSide(rememberedExportDock);
		} else exportPanel.reopen();
		frontSettingsPanel = exportPanel;
		focusedSettingsPanel = exportPanel;
		activateDockedTool(ToolWindow.EXPORT);
		applyDockLayout();
		refreshExportDocumentation(exportPanel);
	}

	private void refreshExportDocumentation(LuaApiExportScreen targetPanel) {
		ScriptManager.refreshApiDocumentation().whenComplete((count, failure) -> minecraft.execute(() -> {
			if (failure != null) {
				targetPanel.setFailure(ScriptManager.message(failure));
				return;
			}
			List<LuaApiDocumentation.DocumentEntry> functions = LuaApiDocumentation.allEntries();
			List<LuaApiDocumentation.DocumentPage> pages = LuaApiDocumentation.allPages();
			targetPanel.setMarkdown(LuaApiMarkdownExporter.render(functions, pages),
					functions.size(), pages.size());
		}));
	}

	void setExportStatus(String message) { status = message; }

	private void toggleFunctionInsertOverlay() {
		var connection = minecraft.getConnection();
		McFunctionCursorContext.Target target = connection == null
				? McFunctionCursorContext.at(textField().value(), textField().cursor())
				: McFunctionCursorContext.at(textField().value(), textField().cursor(),
						connection.getCommands(), connection.getSuggestionsProvider());
		if (revealDockedTool(ToolWindow.INSERT)) {
			openFunctionInsertTarget(target);
			status = target == null ? LuaUiText.text("已显示 ID 插入器；可手动切换类型")
					: "已识别命令参数为" + target.kind().label() + "；将插入原始 ID";
			return;
		}
		hideMenus();
		if (insertOverlay.isOpen() && target != null && insertOverlay.kind() != target.kind()) {
			openFunctionInsertTarget(target);
			status = "已切换到" + target.kind().label() + "；将插入原始 ID";
		} else if (insertOverlay.isOpen()) {
			insertOverlay.close();
			status = LuaUiText.text("已关闭插入器");
		} else {
			openFunctionInsertTarget(target);
			activateDockedTool(ToolWindow.INSERT);
			status = target == null ? LuaUiText.text("已打开 ID 插入器；可手动切换类型")
					: "已识别命令参数为" + target.kind().label() + "；将插入原始 ID";
		}
		syncOverlayState();
	}

	private void openFunctionInsertTarget(McFunctionCursorContext.Target target) {
		if (target != null) {
			MultilineTextField field = textField();
			field.setSelecting(false);
			field.seekCursor(Whence.ABSOLUTE, target.start());
			field.setSelecting(true);
			field.seekCursor(Whence.ABSOLUTE, target.end());
			field.setSelecting(false);
		}
		insertOverlay.open(target == null ? null : target.kind(), target == null ? "" : target.query());
		insertOverlay.requestKeyboardFocus();
		clearSuggestions();
	}

	private void toggleDatapackJsonEditor() {
		if (datapackJsonEditorOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.DATAPACK_JSON_EDITOR);
		if (revealDockedTool(ToolWindow.DATAPACK_JSON_EDITOR)) {
			datapackJsonEditorOverlay.requestKeyboardFocus();
			return;
		}
		hideMenus();
		if (datapackJsonEditorOverlay.isOpen()) {
			datapackJsonEditorOverlay.close();
			status = "已关闭数据包 JSON 编辑器";
		} else {
			datapackJsonEditorOverlay.open();
			activateDockedTool(ToolWindow.DATAPACK_JSON_EDITOR);
			status = "数据包 JSON 编辑：选择类型、导入当前文件，或读取当前游戏中的示例";
		}
		syncOverlayState();
	}

	private void toggleMinecraftUiEditor() {
		if (minecraftUiEditorOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.MINECRAFT_UI);
		if (revealDockedTool(ToolWindow.MINECRAFT_UI)) {
			minecraftUiEditorOverlay.requestKeyboardFocus();
			return;
		}
		hideMenus();
		if (minecraftUiEditorOverlay.isOpen()) {
			minecraftUiEditorOverlay.close();
			status = "已关闭 Minecraft UI 编辑器";
		} else {
			minecraftUiEditorOverlay.open();
			activateDockedTool(ToolWindow.MINECRAFT_UI);
			clearSuggestions();
			status = "Minecraft UI 编辑器已打开 · 可编辑组件、保存模板并插入代码";
		}
		syncOverlayState();
	}

	void openMinecraftUiEditorForTesting() {
		if (minecraftUiEditorOverlay != null && !minecraftUiEditorOverlay.isOpen()) toggleMinecraftUiEditor();
	}

	private boolean openApiDocumentation(String symbol) {
		if (apiDocsOverlay == null || !apiDocsOverlay.openAt(symbol)) return false;
		shortcutOpenedTools.add(ToolWindow.API_DOCS);
		activateDockedTool(ToolWindow.API_DOCS);
		hideMenus();
		if (searchOpen) closeSearch();
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		status = "已定位 API 文档：" + symbol;
		syncOverlayState();
		return true;
	}

	private void openShortcutWindow(LuaWindowShortcuts.Target target) {
		if (target == LuaWindowShortcuts.Target.NONE) return;
		hideMenus();
		if (searchOpen) closeSearch();
		focusedSettingsPanel = null;
		focusExtensionWindow(null);
		manualKeyboardFocused = variableWindowFocused = outputKeyboardFocused = false;
		manualOnTop = false;
		if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
		if (datapackJsonEditorOverlay != null) datapackJsonEditorOverlay.releaseKeyboardFocus();
		if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
		if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
		if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
		if (datapackOverlay != null) datapackOverlay.releaseKeyboardFocus();
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_FILES, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_PNG)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null) overlay.releaseKeyboardFocus();
		}
		setFocused(editor);
		clearSuggestions();
		ToolWindow tool = ToolWindow.valueOf(target.tool);
		switch (target) {
			case API_DOCS -> {
				if (openApiDocumentation(apiSymbolAtCursor())) return;
				tool = ToolWindow.API_DOCS;
				apiDocsOverlay.open();
				status = "已打开 API 文档";
			}
			case INSERT -> {
				tool = ToolWindow.INSERT;
				componentInsertionTarget = null;
				textJsonInsertionTarget = null;
				if (isFunctionDocument()) {
					var connection = minecraft.getConnection();
					openFunctionInsertTarget(connection == null
							? McFunctionCursorContext.at(textField().value(), textField().cursor())
							: McFunctionCursorContext.at(textField().value(), textField().cursor(),
									connection.getCommands(), connection.getSuggestionsProvider()));
				} else {
					LuaCursorContext.Argument argument = currentArgumentContext();
					selectArgumentRange(argument);
					insertOverlay.open(argument == null ? null : argument.kind(), argument == null ? "" : argument.query());
					insertOverlay.requestKeyboardFocus();
				}
				status = LuaUiText.text("已打开插入器 · 输入搜索，回车插入");
			}
			case OUTPUT -> {
				tool = ToolWindow.OUTPUT;
				if (!outputVisible) toggleOutputConsole();
				outputKeyboardFocused = true;
			}
			case COMPONENT_EDITOR -> {
				tool = ToolWindow.COMPONENT_EDITOR;
				LuaCursorContext.ComponentArgument argument = currentItemComponentsArgumentContext();
				if (argument != null) { openComponentEditorForArgument(argument); return; }
				if (!componentEditorOverlay.isOpen()) {
					componentInsertionTarget = null;
					componentEditorOverlay.open();
				}
				componentEditorOverlay.requestKeyboardFocus();
				status = "已打开数据组件编辑器";
			}
			case MANUAL -> {
				if (!manualOpen) toggleManual();
				manualOnTop = manualKeyboardFocused = true;
			}
			case FILES -> { if (!fileOverlay.isOpen()) fileOverlay.open(); fileOverlay.requestKeyboardFocus(); }
			case DATAPACK_FILES -> { if (!datapackOverlay.isOpen()) datapackOverlay.open(); datapackOverlay.requestKeyboardFocus(); }
			case RESOURCE_FILES, RESOURCE_JSON, RESOURCE_PNG -> {
				if (!toolOpen(tool)) {
					if (target == LuaWindowShortcuts.Target.RESOURCE_JSON) toggleResourceJson();
					else toggleResourceTool(resourceTool(tool), tool);
				}
				resourceTool(tool).requestKeyboardFocus();
			}
			case DATAPACK_JSON_EDITOR -> {
				if (!datapackJsonEditorOverlay.isOpen()) toggleDatapackJsonEditor();
				datapackJsonEditorOverlay.requestKeyboardFocus();
			}
			case TEXT_JSON -> {
				LuaCursorContext.TextJsonArgument argument = currentTextJsonArgumentContext();
				if (argument != null) { openTextJsonEditorForArgument(argument); break; }
				if (!textJsonOverlay.isOpen()) toggleTextJsonEditor();
				textJsonOverlay.requestKeyboardFocus();
			}
			case MINECRAFT_UI -> {
				if (!minecraftUiEditorOverlay.isOpen()) toggleMinecraftUiEditor();
				minecraftUiEditorOverlay.requestKeyboardFocus();
			}
			case VARIABLES -> {
				if (!variableOverlay.isOpen()) toggleVariableOverlay();
				variableWindowFocused = true;
			}
			case BREAKPOINTS, COLOR -> {
				if (!extensionWindows.containsKey(tool)) return;
				setExtensionWindowOpen(tool.id, true);
				focusExtensionWindow(extensionWindows.get(tool));
			}
			case GIT -> openGitScreen();
			case PERSONALIZATION -> openPersonalization();
			case NONE -> { return; }
		}
		shortcutOpenedTools.add(tool);
		activateDockedTool(tool);
		syncOverlayState();
	}

	/** Only an enabled IDE Alt+F4 chord may cancel the native close request. */
	boolean handleAltFunctionClose(int modifiers) {
		if (recoveryPrompt || pendingCloseAction != null) return false;
		LuaWindowShortcuts.Target target = personalization.windowShortcuts.match(InputConstants.KEY_F4, modifiers);
		if ((modifiers & LuaWindowShortcuts.ALT) == 0 || target == LuaWindowShortcuts.Target.NONE) return false;
		openShortcutWindow(target);
		return true;
	}

	private void toggleFileOverlay() {
		if (fileOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.FILES);
		if (revealDockedTool(ToolWindow.FILES)) return;
		hideMenus();
		if (fileOverlay.isOpen()) {
			fileOverlay.close();
			status = LuaUiText.text("已关闭 Lua 目录");
		} else {
			if (searchOpen) closeSearch();
			fileOverlay.open();
			activateDockedTool(ToolWindow.FILES);
			status = LuaUiText.text("Lua 目录已打开 · 可新建、读取或移除脚本");
		}
		syncOverlayState();
	}

	private void openScript(String name) {
		if (nativeOwner != null && !nativeOpening) { nativeOwner.open(ImGuiIdeDocument.Kind.LUA, name, true); return; }
		if (!ScriptManager.isValidName(name)) {
			status = "无法打开脚本：路径格式无效";
			return;
		}
		openDocument(name, false);
	}

	private void toggleResourceDirectory() { toggleResourceTool(resourceDirectoryOverlay, ToolWindow.RESOURCE_FILES); }
	private void toggleResourceJson() {
		if (currentDocument != null && currentDocument.resource && !currentDocument.binary())
			resourceJsonOverlay.setJsonSource(editor.getValue());
		toggleResourceTool(resourceJsonOverlay, ToolWindow.RESOURCE_JSON);
	}
	private void toggleResourcePng() { toggleResourceTool(resourcePngOverlay, ToolWindow.RESOURCE_PNG); }
	private void toggleResourceTool(LuaResourcePackToolOverlay overlay, ToolWindow tool) {
		if (overlay == null) return;
		shortcutOpenedTools.remove(tool);
		if (revealDockedTool(tool)) return;
		hideMenus();
		if (overlay.isOpen()) overlay.close();
		else { overlay.open(); activateDockedTool(tool); }
		syncOverlayState();
	}

	private LuaResourcePngCanvas currentResourcePng() {
		return currentDocument != null && currentDocument.resource ? currentDocument.pngCanvas : null;
	}

	private boolean canChangeResourcePath(String path) {
		for (OpenDocument document : openDocuments.values()) {
			if (document.resource && (document.name.equals(path) || document.name.startsWith(path + "/"))) return false;
		}
		return true;
	}

	private void resourceJsonMessage(String message) {
		if ("resource-json-refresh".equals(message)) {
			if (currentDocument != null && currentDocument.resource && !currentDocument.binary())
				resourceJsonOverlay.setJsonSource(editor.getValue());
			else status = LuaUiText.text("请先从 resourcepacks 目录打开 JSON 文件");
		} else status = message;
	}

	private void replaceResourceJsonSource(String source) {
		if (currentDocument == null || !currentDocument.resource || currentDocument.binary()) {
			status = LuaUiText.text("请先从 resourcepacks 目录打开 JSON 文件");
			return;
		}
		editor.setValue(source);
		cachedSource = source;
		currentDocument.source = source;
		scheduleValidation(source);
		status = LuaUiText.text("已同步至主编辑区 · Ctrl+S 保存");
	}

	private void openResourcePackFile(String path) {
		if (nativeOwner != null && !nativeOpening) { nativeOwner.open(ImGuiIdeDocument.Kind.RESOURCEPACK, path, true); return; }
		if (path == null || !(LuaResourcePackWorkspace.editableText(path) || path.endsWith(".png") || path.endsWith(".ogg"))) {
			status = "支持编辑资源包 JSON、.mcmeta、PNG，以及预览 OGG 音频";
			return;
		}
		openDocument(path, true, true);
		if (path.endsWith(".png") && !resourcePngOverlay.isOpen()) {
			resourcePngOverlay.open();
			activateDockedTool(ToolWindow.RESOURCE_PNG);
		}
	}

	private void openDatapackFile(String path) {
		if (nativeOwner != null && !nativeOpening) { nativeOwner.open(ImGuiIdeDocument.Kind.DATAPACK, path, true); return; }
		if (path == null || !(path.endsWith(".json") || path.endsWith("/pack.mcmeta")
				|| path.endsWith(".mcfunction") || path.endsWith(".png")
				|| path.endsWith(".ogg") || path.endsWith(".nbt"))) {
			status = "无法打开：仅支持 JSON、mcfunction、PNG、OGG 与 NBT";
			return;
		}
		openDocument(path, true);
	}

	private void onDatapackRenamed(LuaFileOverlay.RenameResult renamed) {
		if (nativeOwner != null) nativeOwner.datapackRenamed(renamed);
		rememberCurrentDocument();
		List<OpenDocument> documents = new ArrayList<>(openDocuments.values());
		openDocuments.clear();
		for (OpenDocument document : documents) {
			if (document.json && !document.resource && (document.name.equals(renamed.oldPath())
					|| renamed.directory() && document.name.startsWith(renamed.oldPath() + "/"))) {
				if (document.preview != null) {
					document.preview.close(minecraft);
					document.preview = null;
					document.loaded = false;
				}
				document.name = renamed.newPath() + document.name.substring(renamed.oldPath().length());
			}
			openDocuments.put(document.key(), document);
		}
		if (currentDocument != null && currentDocument.json && !currentDocument.resource) {
			scriptName = currentDocument.name;
			datapackOverlay.setCurrentScript(currentDocument.name);
			if (currentDocument.binary() && !currentDocument.loaded) restoreCurrentDocument();
		}
		ensureCurrentDocumentTabVisible();
	}

	private void openDocument(String name, boolean json) {
		openDocument(name, json, false);
	}

	private void openDocument(String name, boolean json, boolean resource) {
		String key = (resource ? "resourcepack:" : json ? "datapack:" : "lua:") + name;
		if (!openDocuments.containsKey(key) && openDocuments.size() >= 32) {
			status = LuaUiText.text("最多同时打开 32 个文件；请先关闭一些标签");
			return;
		}
		rememberCurrentDocument();
		if (currentDocument != null && currentDocument.binary()) {
			LuaOggPreviewPlayer.stop();
			if (currentDocument.preview != null) currentDocument.preview.close(minecraft);
		}
		currentDocument = openDocuments.computeIfAbsent(key, ignored -> new OpenDocument(name, json, resource));
		scriptName = name;
		fileOverlay.setCurrentScript(json ? "" : name);
		datapackOverlay.setCurrentScript(json && !resource ? name : "");
		LuaSyntaxHighlighter.setJsonMode(editor, json && !name.endsWith(".mcfunction"));
		LuaSyntaxHighlighter.setFunctionMode(editor, json && name.endsWith(".mcfunction"));
		clearSuggestions();
		LuaFunctionFolding.bind(textField(), json ? null : currentDocument.functionFolds);
		restoreCurrentDocument();
		if (resource && !currentDocument.binary() && currentDocument.loaded)
			resourceJsonOverlay.setJsonSource(currentDocument.source);
		restoreBackgroundRun();
		ensureCurrentDocumentTabVisible();
		updateRunControls();
		hideMenus();
		syncOverlayState();
	}

	private void toggleDatapackOverlay() {
		if (datapackOverlay == null) return;
		shortcutOpenedTools.remove(ToolWindow.DATAPACK_FILES);
		if (revealDockedTool(ToolWindow.DATAPACK_FILES)) return;
		hideMenus();
		if (datapackOverlay.isOpen()) {
			datapackOverlay.close();
			status = LuaUiText.text("已关闭 datapack 目录");
		} else {
			if (searchOpen) closeSearch();
			datapackOverlay.open();
			activateDockedTool(ToolWindow.DATAPACK_FILES);
			status = LuaUiText.text("datapack 目录已打开 · 文件夹图标创建模板，文件图标创建 JSON");
		}
		syncOverlayState();
	}

	private void rememberCurrentDocument() {
		if (currentDocument == null || editor == null) return;
		OpenDocument document = currentDocument;
		if (document.binary()) return;
		if (!sourceLoaded) return;
		document.source = editor.getValue();
		document.savedSource = lastSavedSource;
		document.cursor = textField().cursor();
		document.scroll = editor.scrollAmount();
		document.lastSnapshot = lastSnapshot;
		document.undo.clear();
		document.undo.addAll(undoHistory);
		document.redo.clear();
		document.redo.addAll(redoHistory);
	}

	private void restoreCurrentDocument() {
		OpenDocument document = currentDocument;
		if (document == null || editor == null) return;
		if (document.binary()) {
			if (document.preview != null) document.preview.activate(minecraft);
			validationRevision.incrementAndGet();
			clearSuggestions();
			LuaSyntaxHighlighter.clearErrors(editor);
			sourceLoaded = false;
			sourceLoading = document.loading;
			internalTextChange = true;
			try { editor.setValue(""); }
			finally { internalTextChange = false; }
			editor.visible = false;
			if (editor.isFocused()) setFocused(null);
			syntaxMessage = document.resource && document.name.endsWith(".png") ? LuaUiText.text("● PNG 画布") : LuaUiText.text("● 只读预览");
			validationDetail = document.resource && document.name.endsWith(".png") ? LuaUiText.text("资源包图片可编辑 · 工具在独立窗口") : LuaUiText.text("PNG 图片 / OGG 音频 / NBT 树形内容");
			syntaxColor = 0xFF8CCFC6;
			if (!document.loaded && !document.loading) loadDocument(document);
			else status = "正在预览 " + document.displayName();
			syncOverlayState();
			return;
		}
		editor.visible = true;
		sourceLoaded = document.loaded;
		sourceLoading = document.loading;
		cachedSource = document.source;
		lastSavedSource = document.savedSource;
		undoHistory.clear();
		undoHistory.addAll(document.undo);
		redoHistory.clear();
		redoHistory.addAll(document.redo);
		restoreDocumentFolds(document);
		internalTextChange = true;
		try { editor.setValue(document.source); }
		finally { internalTextChange = false; }
		lastSnapshot = document.lastSnapshot;
		textField().seekCursor(Whence.ABSOLUTE,
				document.json ? Math.max(0, Math.min(document.cursor, document.source.length()))
						: document.functionFolds.visibleCursor(document.cursor));
		editor.setScrollAmount(document.scroll);
		if (document.loaded) {
			scheduleValidation(document.source);
			status = "正在编辑 " + document.displayName()
					+ (document.source.equals(document.savedSource) ? "" : " · 尚未保存");
		} else if (!document.loading) {
			loadDocument(document);
		}
		syncOverlayState();
	}

	private void restoreDocumentFolds(OpenDocument document) {
		if (!document.json && document.loaded && document.pendingFolds != null) {
			document.functionFolds.restore(document.source, document.pendingFolds);
			document.cursor = document.functionFolds.visibleCursor(document.cursor);
			document.pendingFolds = null;
		}
	}

	private void loadDocument(OpenDocument document) {
		long readRevision = ++document.readRevision;
		document.loading = true;
		if (currentDocument == document) sourceLoading = true;
		status = (document.resource ? "正在从客户端读取 " : "正在从服务器读取 ") + document.displayName() + "…";
		if (document.resource && document.name.endsWith(".ogg")) {
			CompletableFuture.supplyAsync(() -> {
				try { return LuaResourcePackWorkspace.readOgg(document.name); }
				catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
			}).whenComplete((bytes, failure) -> minecraft.execute(() -> {
				document.loading = false;
				if (!openDocuments.containsValue(document) || !isCurrentIde()) return;
				if (failure != null) {
					if (currentDocument == document) { sourceLoading = false; status = "音频预览失败：" + ScriptManager.message(failure); }
					return;
				}
				document.preview = new LuaBinaryPreview(minecraft, document.name, bytes);
				document.loaded = true;
				if (currentDocument == document) { sourceLoading = false; status = "已打开 OGG 音频预览：" + document.displayName(); }
			}));
			return;
		}
		if (document.resource && document.name.endsWith(".png")) {
			try {
				document.pngCanvas = new LuaResourcePngCanvas(minecraft, document.name);
				document.loaded = true;
				sourceLoading = false;
				status = "已打开 PNG 画布：" + document.displayName();
			} catch (Exception error) { sourceLoading = false; status = "打开 PNG 失败：" + error.getMessage(); }
			document.loading = false;
			return;
		}
		if (document.resource) {
			try {
				String source = LuaResourcePackWorkspace.readText(document.name);
				if (source.length() > LuaProtocol.MAX_SCRIPT_CHARS)
					throw new java.io.IOException("文本超过主编辑器 " + LuaProtocol.MAX_SCRIPT_CHARS + " 字符限制");
				document.source = source;
				document.savedSource = source;
				document.lastSnapshot = source;
				document.loaded = true;
				if (currentDocument == document) {
					sourceLoaded = true; sourceLoading = false;
					cachedSource = source; lastSavedSource = source; lastSnapshot = source;
					internalTextChange = true;
					try { editor.setValue(source); } finally { internalTextChange = false; }
					textField().seekCursor(Whence.ABSOLUTE, Math.min(document.cursor, source.length()));
					editor.setScrollAmount(document.scroll);
					resourceJsonOverlay.setJsonSource(source);
					scheduleValidation(source);
					status = "已打开客户端资源包 JSON：" + document.displayName();
				}
			} catch (Exception error) { sourceLoading = false; status = "打开 JSON 失败：" + error.getMessage(); }
			document.loading = false;
			return;
		}
		if (document.binary()) {
			ScriptManager.readDatapackPreview(document.name).whenComplete((bytes, failure) ->
					minecraft.execute(() -> {
					if (document.readRevision != readRevision) return;
					document.loading = false;
					if (failure != null) {
						if (currentDocument == document) {
							sourceLoading = false;
							status = "预览失败：" + ScriptManager.message(failure);
						}
						return;
					}
					document.preview = new LuaBinaryPreview(minecraft, document.name, bytes);
					document.loaded = true;
					if (currentDocument == document && isCurrentIde()) {
						sourceLoading = false;
						status = "已加载只读预览：" + document.displayName();
						syncOverlayState();
					}
				}));
			return;
		}
		(document.json ? ScriptManager.readDatapackFile(document.name) : ScriptManager.read(document.name))
				.whenComplete((source, failure) -> minecraft.execute(() -> {
			if (document.readRevision != readRevision) return;
			document.loading = false;
			if (failure != null) {
				if (currentDocument == document) {
					sourceLoading = false;
					status = "加载失败：" + ScriptManager.message(failure);
				}
				return;
			}
			document.loaded = true;
			document.source = source;
			document.savedSource = source;
			document.lastSnapshot = source;
			if (currentDocument != document || !isCurrentIde() || editor == null) return;
			restoreDocumentFolds(document);
			sourceLoaded = true;
			sourceLoading = false;
			cachedSource = source;
			lastSavedSource = source;
			lastSnapshot = source;
			internalTextChange = true;
			try { editor.setValue(source); }
			finally { internalTextChange = false; }
			textField().seekCursor(Whence.ABSOLUTE, Math.min(document.cursor, source.length()));
			editor.setScrollAmount(document.scroll);
			clearSuggestions();
			scheduleValidation(source);
			status = "已从服务器读取 " + document.displayName();
			syncOverlayState();
		}));
	}

	private void closeDocument(String key) {
		OpenDocument document = openDocuments.get(key);
		if (document == null) return;
		if (document == currentDocument) rememberCurrentDocument();
		ScriptManager.ScriptExecution running = ScriptManager.activeExecution();
		if (running != null && running.name().equals(document.name)) {
			status = "脚本正在运行，请先停止再关闭标签：" + document.name;
			return;
		}
		if (document.dirty()) {
			requestClose(List.of(document), () -> closeDocumentConfirmed(key));
			return;
		}
		closeDocumentConfirmed(key);
	}

	private void closeDocumentConfirmed(String key) {
		OpenDocument document = openDocuments.get(key);
		if (document != null && !document.json) moduleSources.remove(document.name);
		if (document == null) return;
		clearRecoveryDraft(key);
		openDocuments.remove(key);
		if (document.preview != null) document.preview.close(minecraft);
		if (document.pngCanvas != null) document.pngCanvas.close();
		if (document.binary()) LuaOggPreviewPlayer.stop();
		if (document == currentDocument) {
			currentDocument = null;
			scriptName = "";
			if (!openDocuments.isEmpty()) {
				OpenDocument next = openDocuments.values().iterator().next();
				if (next.resource) openResourcePackFile(next.name);
				else if (next.json) openDatapackFile(next.name);
				else openScript(next.name);
			} else {
				LuaFunctionFolding.bind(textField(), draftFunctionFolds);
				internalTextChange = true;
				try { editor.setValue(""); }
				finally { internalTextChange = false; }
				cachedSource = "";
				lastSavedSource = "";
				lastSnapshot = "";
				undoHistory.clear();
				redoHistory.clear();
				sourceLoaded = false;
				sourceLoading = false;
				fileOverlay.setCurrentScript("");
				datapackOverlay.setCurrentScript("");
				editor.visible = true;
				LuaSyntaxHighlighter.setJsonMode(editor, false);
				LuaSyntaxHighlighter.setFunctionMode(editor, false);
				syntaxMessage = LuaUiText.text("● 未打开脚本");
				validationDetail = "";
				syncOverlayState();
			}
		}
		status = "已关闭标签：" + document.displayName();
		updateRunControls();
	}

	private int documentTabWidth(OpenDocument document) {
		return Math.min(176, Math.max(88, font.width(document.tabLabel()) + 35));
	}

	private void ensureCurrentDocumentTabVisible() {
		if (currentDocument == null) return;
		List<OpenDocument> documents = new ArrayList<>(openDocuments.values());
		int target = documents.indexOf(currentDocument);
		if (target < 0) return;
		documentTabScroll = Math.min(documentTabScroll, target);
		int available = Math.max(90, width - 55);
		while (documentTabScroll < target) {
			int used = 0;
			for (int index = documentTabScroll; index <= target; index++)
				used += documentTabWidth(documents.get(index)) + 3;
			if (used <= available) break;
			documentTabScroll++;
		}
	}

	private void renderDocumentTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (openDocuments.isEmpty()) {
			graphics.text(font, font.plainSubstrByWidth(
					"未打开文件 · 从“文件”选择 Lua 或 datapack 目录", Math.max(30, width - 62)),
					12, 39, 0xFF7D98A4);
			return;
		}
		List<OpenDocument> documents = new ArrayList<>(openDocuments.values());
		documentTabScroll = Math.max(0, Math.min(documentTabScroll, documents.size() - 1));
		int right = width - 46;
		int x = 10;
		for (int index = documentTabScroll; index < documents.size(); index++) {
			OpenDocument document = documents.get(index);
			int tabWidth = documentTabWidth(document);
			if (x + tabWidth > right) break;
			boolean active = document == currentDocument;
			boolean hovered = mouseX >= x && mouseX < x + tabWidth && mouseY >= 33 && mouseY < 52;
			graphics.fill(x, 33, x + tabWidth, 52,
					active ? 0xFF305D6D : hovered ? 0xFF2A3D48 : 0xFF1D2A33);
			graphics.fill(x, 50, x + tabWidth, 52, active ? 0xFF6CCFC5 : 0xFF3D5660);
			boolean dirty = document.pngCanvas != null && document.pngCanvas.document().dirty()
					|| !document.binary() && document.loaded && (document == currentDocument
				? !editor.getValue().equals(lastSavedSource)
				: !document.source.equals(document.savedSource));
			String label = (dirty ? "● " : "") + document.tabLabel();
			graphics.text(font, font.plainSubstrByWidth(label, tabWidth - 26), x + 6, 39,
					active ? 0xFFE4F9F6 : 0xFFAAC0C1);
			graphics.centeredText(font, "×", x + tabWidth - 11, 39,
					hovered ? 0xFFFFB3B3 : 0xFF89A7AD);
			x += tabWidth + 3;
		}
		graphics.fill(width - 43, 34, width - 24, 51, 0xFF253844);
		graphics.fill(width - 22, 34, width - 3, 51, 0xFF253844);
		graphics.centeredText(font, "‹", width - 34, 39, 0xFFD1E6E6);
		graphics.centeredText(font, "›", width - 13, 39, 0xFFD1E6E6);
	}

	private boolean handleDocumentTabClick(double mouseX, double mouseY) {
		if (mouseY < 33 || mouseY >= 52) return false;
		if (openDocuments.isEmpty()) return true;
		List<OpenDocument> documents = new ArrayList<>(openDocuments.values());
		if (mouseX >= width - 43 && mouseX < width - 24) {
			documentTabScroll = Math.max(0, documentTabScroll - 1);
			return true;
		}
		if (mouseX >= width - 22 && mouseX < width - 3) {
			documentTabScroll = Math.min(documents.size() - 1, documentTabScroll + 1);
			return true;
		}
		int x = 10;
		for (int index = documentTabScroll; index < documents.size(); index++) {
			OpenDocument document = documents.get(index);
			int tabWidth = documentTabWidth(document);
			if (x + tabWidth > width - 46) break;
			if (mouseX >= x && mouseX < x + tabWidth) {
				if (mouseX >= x + tabWidth - 20) closeDocument(document.key());
				else if (document.resource) openResourcePackFile(document.name);
				else if (document.json) openDatapackFile(document.name);
				else openScript(document.name);
				return true;
			}
			x += tabWidth + 3;
		}
		return true;
	}

	private void syncOverlayState() {
		if (editor != null) {
			editor.active = sourceLoaded && (currentDocument == null || !currentDocument.binary())
					&& activeMenu.isEmpty()
					&& (fileOverlay == null || !fileOverlay.isOpen() || fileOverlay.dockSide().docked());
		}
		applyDockLayout();
	}

	private void setOverlayLayoutLocked() {
		extensionWindows.values().forEach(window -> window.setLayoutLocked(layoutLocked));
		if (insertOverlay != null) insertOverlay.setLayoutLocked(layoutLocked);
		if (apiDocsOverlay != null) apiDocsOverlay.setLayoutLocked(layoutLocked);
		if (variableOverlay != null) variableOverlay.setLayoutLocked(layoutLocked);
		if (componentEditorOverlay != null) componentEditorOverlay.setLayoutLocked(layoutLocked);
		if (datapackJsonEditorOverlay != null) datapackJsonEditorOverlay.setLayoutLocked(layoutLocked);
		if (textJsonOverlay != null) textJsonOverlay.setLayoutLocked(layoutLocked);
		if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.setLayoutLocked(layoutLocked);
		if (fileOverlay != null) fileOverlay.setLayoutLocked(layoutLocked);
		if (datapackOverlay != null) datapackOverlay.setLayoutLocked(layoutLocked);
		if (resourceDirectoryOverlay != null) resourceDirectoryOverlay.setLayoutLocked(layoutLocked);
		if (resourceJsonOverlay != null) resourceJsonOverlay.setLayoutLocked(layoutLocked);
		if (resourcePngOverlay != null) resourcePngOverlay.setLayoutLocked(layoutLocked);
		if (gitPanel != null) gitPanel.setLayoutLocked(layoutLocked);
		if (personalizationPanel != null) personalizationPanel.setLayoutLocked(layoutLocked);
		if (exportPanel != null) exportPanel.setLayoutLocked(layoutLocked);
	}

	private DockSide toolDock(ToolWindow tool) {
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.containsKey(tool) ? extensionWindows.get(tool).dockSide() : DockSide.FLOATING;
			case MANUAL -> manualDock;
			case INSERT -> insertOverlay == null ? DockSide.FLOATING : insertOverlay.dockSide();
			case API_DOCS -> apiDocsOverlay == null ? DockSide.FLOATING : apiDocsOverlay.dockSide();
			case COMPONENT_EDITOR -> componentEditorOverlay == null
					? DockSide.FLOATING : componentEditorOverlay.dockSide();
			case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay == null
					? DockSide.FLOATING : datapackJsonEditorOverlay.dockSide();
			case TEXT_JSON -> textJsonOverlay == null ? DockSide.FLOATING : textJsonOverlay.dockSide();
			case MINECRAFT_UI -> minecraftUiEditorOverlay == null ? DockSide.FLOATING : minecraftUiEditorOverlay.dockSide();
			case FILES -> fileOverlay == null ? DockSide.FLOATING : fileOverlay.dockSide();
			case DATAPACK_FILES -> datapackOverlay == null ? DockSide.FLOATING : datapackOverlay.dockSide();
			case RESOURCE_FILES -> resourceDirectoryOverlay == null ? DockSide.FLOATING : resourceDirectoryOverlay.dockSide();
			case RESOURCE_JSON -> resourceJsonOverlay == null ? DockSide.FLOATING : resourceJsonOverlay.dockSide();
			case RESOURCE_PNG -> resourcePngOverlay == null ? DockSide.FLOATING : resourcePngOverlay.dockSide();
			case OUTPUT -> outputDock;
			case VARIABLES -> variableOverlay == null ? DockSide.FLOATING : variableOverlay.dockSide();
			case GIT -> gitPanel == null ? rememberedGitDock : gitPanel.dockSide();
			case PERSONALIZATION -> personalizationPanel == null
					? rememberedPersonalizationDock : personalizationPanel.dockSide();
			case EXPORT -> exportPanel == null ? rememberedExportDock : exportPanel.dockSide();
		};
	}

	private boolean toolOpen(ToolWindow tool) {
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).isOpen();
			case MANUAL -> manualOpen;
			case INSERT -> insertOverlay != null && insertOverlay.isOpen();
			case API_DOCS -> apiDocsOverlay != null && apiDocsOverlay.isOpen();
			case COMPONENT_EDITOR -> componentEditorOverlay != null && componentEditorOverlay.isOpen();
			case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay != null && datapackJsonEditorOverlay.isOpen();
			case TEXT_JSON -> textJsonOverlay != null && textJsonOverlay.isOpen();
			case MINECRAFT_UI -> minecraftUiEditorOverlay != null && minecraftUiEditorOverlay.isOpen();
			case FILES -> fileOverlay != null && fileOverlay.isOpen();
			case DATAPACK_FILES -> datapackOverlay != null && datapackOverlay.isOpen();
			case RESOURCE_FILES -> resourceDirectoryOverlay != null && resourceDirectoryOverlay.isOpen();
			case RESOURCE_JSON -> resourceJsonOverlay != null && resourceJsonOverlay.isOpen();
			case RESOURCE_PNG -> resourcePngOverlay != null && resourcePngOverlay.isOpen();
			case OUTPUT -> outputVisible;
			case VARIABLES -> variableOverlay != null && variableOverlay.isOpen();
			case GIT -> gitPanel != null;
			case PERSONALIZATION -> personalizationPanel != null;
			case EXPORT -> exportPanel != null;
		};
	}

	private boolean toolVisible(ToolWindow tool) {
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).isVisible();
			case MANUAL -> manualOpen || manualProgress > 0.01F;
			case INSERT -> insertOverlay != null && (insertOverlay.isOpen() || insertOverlay.isVisible());
			case API_DOCS -> apiDocsOverlay != null && (apiDocsOverlay.isOpen() || apiDocsOverlay.isVisible());
			case COMPONENT_EDITOR -> componentEditorOverlay != null
					&& (componentEditorOverlay.isOpen() || componentEditorOverlay.isVisible());
			case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay != null
					&& (datapackJsonEditorOverlay.isOpen() || datapackJsonEditorOverlay.isVisible());
			case TEXT_JSON -> textJsonOverlay != null && (textJsonOverlay.isOpen() || textJsonOverlay.isVisible());
			case MINECRAFT_UI -> minecraftUiEditorOverlay != null
					&& (minecraftUiEditorOverlay.isOpen() || minecraftUiEditorOverlay.isVisible());
			case FILES -> fileOverlay != null && (fileOverlay.isOpen() || fileOverlay.isVisible());
			case DATAPACK_FILES -> datapackOverlay != null && (datapackOverlay.isOpen() || datapackOverlay.isVisible());
			case RESOURCE_FILES -> resourceDirectoryOverlay != null && (resourceDirectoryOverlay.isOpen() || resourceDirectoryOverlay.isVisible());
			case RESOURCE_JSON -> resourceJsonOverlay != null && (resourceJsonOverlay.isOpen() || resourceJsonOverlay.isVisible());
			case RESOURCE_PNG -> resourcePngOverlay != null && (resourcePngOverlay.isOpen() || resourcePngOverlay.isVisible());
			case OUTPUT -> isOutputRendered();
			case VARIABLES -> variableOverlay != null && variableOverlay.isVisible();
			case GIT -> gitPanel != null;
			case PERSONALIZATION -> personalizationPanel != null;
			case EXPORT -> exportPanel != null;
		};
	}

	private List<ToolWindow> dockedToolsAt(DockSide side) {
		if (!side.docked()) return List.of();
		List<ToolWindow> tools = new ArrayList<>();
		for (ToolWindow tool : toolWindows()) {
			if (toolVisible(tool) && toolDock(tool) == side) tools.add(tool);
		}
		return dockTabOrder.ordered(side, tools, ToolWindow::name);
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
		if (nativeOwner != null) { nativeActivatedTool = tool.name(); return; }
		DockSide side = toolDock(tool);
		if (!side.docked()) return;
		setSelectedDockTool(side, tool);
		applyDockLayout();
	}

	private boolean toolCanInteract(ToolWindow tool) {
		if (nativeOwner != null) return nativeInputTool == tool;
		DockSide side = toolDock(tool);
		return !side.docked() || activeToolAt(side) == tool;
	}

	private void setToolDock(ToolWindow tool, DockSide side) {
		switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).setDockSide(side);
			case MANUAL -> setManualDock(side);
			case INSERT -> insertOverlay.setDockSide(side);
			case API_DOCS -> apiDocsOverlay.setDockSide(side);
			case COMPONENT_EDITOR -> componentEditorOverlay.setDockSide(side);
			case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay.setDockSide(side);
			case TEXT_JSON -> textJsonOverlay.setDockSide(side);
			case MINECRAFT_UI -> minecraftUiEditorOverlay.setDockSide(side);
			case FILES -> fileOverlay.setDockSide(side);
			case DATAPACK_FILES -> datapackOverlay.setDockSide(side);
			case RESOURCE_FILES -> resourceDirectoryOverlay.setDockSide(side);
			case RESOURCE_JSON -> resourceJsonOverlay.setDockSide(side);
			case RESOURCE_PNG -> resourcePngOverlay.setDockSide(side);
			case OUTPUT -> setOutputDock(side);
			case VARIABLES -> variableOverlay.setDockSide(side);
			case GIT -> {
				rememberedGitDock = side;
				if (gitPanel != null) gitPanel.setDockSide(side);
			}
			case PERSONALIZATION -> {
				rememberedPersonalizationDock = side;
				if (personalizationPanel != null) personalizationPanel.setDockSide(side);
			}
			case EXPORT -> {
				rememberedExportDock = side;
				if (exportPanel != null) exportPanel.setDockSide(side);
			}
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
		int desired = switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).preferredWidth();
			case API_DOCS -> 520;
			case COMPONENT_EDITOR -> 560;
			case DATAPACK_JSON_EDITOR -> 560;
			case TEXT_JSON -> 480;
			case MINECRAFT_UI -> 600;
			case FILES -> 430;
			case DATAPACK_FILES -> 430;
			case RESOURCE_FILES -> 310;
			case RESOURCE_JSON -> 420;
			case RESOURCE_PNG -> 420;
			case MANUAL -> 400;
			case INSERT -> 380;
			case OUTPUT -> 360;
			case VARIABLES -> 300;
			case GIT -> 430;
			case PERSONALIZATION -> 470;
			case EXPORT -> 500;
		};
		return Math.min(desired, Math.max(MIN_DOCK_SIDE_WIDTH,
				width - MIN_EDITOR_WIDTH - DOCK_GAP - 16));
	}

	private int preferredDockHeight(ToolWindow tool) {
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).preferredHeight();
			case API_DOCS, FILES, DATAPACK_FILES, RESOURCE_FILES, INSERT -> 250;
			case RESOURCE_JSON -> 340;
			case RESOURCE_PNG -> 430;
			case TEXT_JSON -> 360;
			case MINECRAFT_UI -> 410;
			case COMPONENT_EDITOR -> 340;
			case DATAPACK_JSON_EDITOR -> 340;
			case MANUAL -> 230;
			case OUTPUT -> 180;
			case VARIABLES -> 230;
			case GIT -> 260;
			case PERSONALIZATION -> 310;
			case EXPORT -> 290;
		};
	}

	private int minimumDockWidth(ToolWindow tool) {
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).minimumWidth();
			case API_DOCS -> 320;
			case COMPONENT_EDITOR -> 360;
			case DATAPACK_JSON_EDITOR -> 360;
			case TEXT_JSON -> 330;
			case MINECRAFT_UI -> 400;
			case FILES, DATAPACK_FILES -> LuaFileOverlay.MIN_WIDTH;
			case RESOURCE_FILES -> 170;
			case RESOURCE_JSON -> 240;
			case RESOURCE_PNG -> 200;
			case INSERT -> 260;
			case MANUAL -> 240;
			case OUTPUT -> MIN_DOCK_SIDE_WIDTH;
			case VARIABLES -> 190;
			case GIT -> 360;
			case PERSONALIZATION -> 360;
			case EXPORT -> 330;
		};
	}

	private int minimumDockHeight(ToolWindow tool) {
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).minimumHeight();
			case API_DOCS, INSERT -> 170;
			case TEXT_JSON -> 330;
			case MINECRAFT_UI -> 390;
			case COMPONENT_EDITOR -> 300;
			case DATAPACK_JSON_EDITOR -> 300;
			case FILES, DATAPACK_FILES -> 155;
			case RESOURCE_FILES -> 230;
			case RESOURCE_JSON -> 260;
			case RESOURCE_PNG -> 430;
			case MANUAL -> 145;
			case OUTPUT -> MIN_DOCK_BOTTOM_HEIGHT;
			case VARIABLES -> 120;
			case GIT -> 300;
			case PERSONALIZATION -> 240;
			case EXPORT -> 240;
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
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).title();
			case MANUAL -> LuaUiText.text("使用手册");
			case INSERT -> LuaUiText.text("插入窗口");
			case API_DOCS -> LuaUiText.text("API 文档");
			case COMPONENT_EDITOR -> LuaUiText.text("数据组件编辑器");
			case DATAPACK_JSON_EDITOR -> LuaUiText.text("数据包 JSON 编辑器");
			case TEXT_JSON -> LuaUiText.text("文本 JSON 编辑器");
			case MINECRAFT_UI -> LuaUiText.text("Minecraft UI 编辑器");
			case FILES -> LuaUiText.text("Lua 目录");
			case DATAPACK_FILES -> LuaUiText.text("datapack 目录");
			case RESOURCE_FILES -> LuaUiText.text("resourcepacks 目录");
			case RESOURCE_JSON -> LuaUiText.text("资源包 JSON 编辑器");
			case RESOURCE_PNG -> LuaUiText.text("图片工具");
			case OUTPUT -> LuaUiText.text("运行日志");
			case VARIABLES -> LuaUiText.text("变量显示");
			case GIT -> "Git";
			case PERSONALIZATION -> LuaUiText.text("个性化");
			case EXPORT -> "导出 API";
		};
	}

	private String dockSideLabel(DockSide side) {
		return switch (side) {
			case LEFT -> LuaUiText.text("左侧");
			case RIGHT -> LuaUiText.text("右侧");
			case BOTTOM -> LuaUiText.text("底部");
			case FLOATING -> "浮动";
		};
	}

	private void applyDockLayout() {
		if (nativeOwner != null) return;
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
		int minSide = Math.min(LuaFileOverlay.MIN_WIDTH,
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
		// The vanilla text area's vertical scrollbar is outside its widget bounds.
		// Reserve its width before the dock gap, so it never covers the splitter.
		int editorWidth = Math.max(40, editorRight - editorX - editor.scrollbarWidth());
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
		int flowWidth = LuaEditorViewport.flowWidth(personalization.codeWordWrap, editor.getWidth(), characterWidthScale());
		if (fieldAccess.mcLuaIde$getWidth() == flowWidth) return;
		fieldAccess.mcLuaIde$setWidth(flowWidth);
		fieldAccess.mcLuaIde$reflowDisplayLines();
		editor.refreshScrollAmount();
	}

	private void restoreWorkspaceLayout() {
		if (workspaceLayoutRestored) return;
		workspaceLayoutRestored = true;
		restoreWindowGeometry();
		WorkspaceLayout saved = rememberedWorkspaceLayout;
		layoutLocked = saved.locked();
		leftDockWidth = saved.leftWidth();
		rightDockWidth = saved.rightWidth();
		bottomDockHeight = saved.bottomHeight();
		setManualDock(saved.manual());
		insertOverlay.setDockSide(saved.insert());
		apiDocsOverlay.setDockSide(saved.apiDocs());
		componentEditorOverlay.setDockSide(saved.componentEditor());
		datapackJsonEditorOverlay.setDockSide(saved.datapackJsonEditor());
		textJsonOverlay.setDockSide(saved.textJson());
		minecraftUiEditorOverlay.setDockSide(saved.minecraftUi());
		fileOverlay.setDockSide(saved.files());
		datapackOverlay.setDockSide(saved.datapackFiles());
		resourceDirectoryOverlay.setDockSide(saved.resourceFiles());
		resourceJsonOverlay.setDockSide(saved.resourceJson());
		resourcePngOverlay.setDockSide(saved.resourcePng());
		setOutputDock(saved.output());
		variableOverlay.setDockSide(saved.variables());
		rememberedGitDock = saved.git();
		rememberedPersonalizationDock = saved.personalization();
		rememberedExportDock = saved.export();
		if (saved.gitVisible() && gitPanel == null) {
			gitPanel = new LuaGitScreen(this);
			gitPanel.restoreGeometry(WindowGeometry.read(windowGeometry, "git"));
			gitPanel.openEmbedded(width, height);
			gitPanel.setDockSide(rememberedGitDock);
		}
		if (saved.personalizationVisible() && personalizationPanel == null) {
			personalizationPanel = new LuaPersonalizationScreen(this, personalization);
			personalizationPanel.restoreGeometry(WindowGeometry.read(windowGeometry, "personalization"));
			personalizationPanel.openEmbedded(width, height);
			personalizationPanel.setDockSide(rememberedPersonalizationDock);
		}
		if (saved.exportVisible() && exportPanel == null) {
			exportPanel = new LuaApiExportScreen(this);
			exportPanel.restoreGeometry(WindowGeometry.read(windowGeometry, "export"));
			exportPanel.openEmbedded(width, height);
			exportPanel.setDockSide(rememberedExportDock);
			refreshExportDocumentation(exportPanel);
		}
		if (frontSettingsPanel == null)
			frontSettingsPanel = exportPanel != null ? exportPanel
					: personalizationPanel != null ? personalizationPanel : gitPanel;
		activeLeftDock = saved.activeLeft();
		activeRightDock = saved.activeRight();
		activeBottomDock = saved.activeBottom();
		manualOpen = saved.manualVisible();
		if (saved.insertVisible()) insertOverlay.open();
		if (saved.apiDocsVisible()) apiDocsOverlay.open();
		if (saved.componentEditorVisible()) componentEditorOverlay.open();
		if (saved.datapackJsonEditorVisible()) datapackJsonEditorOverlay.open();
		if (saved.textJsonVisible()) textJsonOverlay.open();
		if (saved.minecraftUiVisible()) minecraftUiEditorOverlay.open();
		if (saved.filesVisible()) fileOverlay.open();
		if (saved.datapackVisible()) datapackOverlay.open();
		if (saved.resourceFilesVisible()) resourceDirectoryOverlay.open();
		if (saved.resourceJsonVisible()) resourceJsonOverlay.open();
		if (saved.resourcePngVisible()) resourcePngOverlay.open();
		outputVisible = saved.outputVisible();
		if (saved.variablesVisible()) variableOverlay.open();
		setOverlayLayoutLocked();
	}

	private Properties extensionLayoutSnapshot() {
		Properties state = new Properties();
		extensionWindows.values().forEach(window -> window.saveLayout(state));
		return state;
	}

	private void restoreWindowGeometry() {
		insertOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "insert"));
		apiDocsOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "apiDocs"));
		apiDocsOverlay.restoreSidebarWidth(parseLayoutInt(windowGeometry, "apiDocs.sidebarWidth", -1));
		apiDocsOverlay.restoreNavigation(windowGeometry);
		variableOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "variables"));
		componentEditorOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "componentEditor"));
		datapackJsonEditorOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "datapackJsonEditor"));
		textJsonOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "textJson"));
		minecraftUiEditorOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "minecraftUi"));
		fileOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "files"));
		datapackOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "datapackFiles"));
		resourceDirectoryOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "resourceFiles"));
		resourceJsonOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "resourceJson"));
		resourcePngOverlay.restoreGeometry(WindowGeometry.read(windowGeometry, "resourcePng"));
		WindowGeometry manual = WindowGeometry.read(windowGeometry, "manual");
		if (manual != null) {
			manualWindowX = manual.x(); manualWindowY = manual.y();
			manualWindowWidth = manual.width(); manualWindowHeight = manual.height();
			ensureManualGeometry();
			manualBoundsAnimation.snapTo(manualWindowX, manualWindowY, manualWindowWidth, manualWindowHeight);
		}
		WindowGeometry output = WindowGeometry.read(windowGeometry, "output");
		if (output != null) {
			outputWindowX = output.x(); outputWindowY = output.y();
			outputWindowWidth = output.width(); outputWindowHeight = output.height();
			ensureOutputGeometry();
			outputBoundsAnimation.snapTo(outputWindowX, outputWindowY, outputWindowWidth, outputWindowHeight);
		}
	}

	private void captureWindowGeometry() {
		dockTabOrder.save(windowGeometry);
		insertOverlay.captureGeometry().write(windowGeometry, "insert");
		apiDocsOverlay.captureGeometry().write(windowGeometry, "apiDocs");
		windowGeometry.setProperty("apiDocs.sidebarWidth", Integer.toString(apiDocsOverlay.preferredSidebarWidth()));
		apiDocsOverlay.captureNavigation(windowGeometry);
		variableOverlay.captureGeometry().write(windowGeometry, "variables");
		componentEditorOverlay.captureGeometry().write(windowGeometry, "componentEditor");
		datapackJsonEditorOverlay.captureGeometry().write(windowGeometry, "datapackJsonEditor");
		textJsonOverlay.captureGeometry().write(windowGeometry, "textJson");
		minecraftUiEditorOverlay.captureGeometry().write(windowGeometry, "minecraftUi");
		fileOverlay.captureGeometry().write(windowGeometry, "files");
		datapackOverlay.captureGeometry().write(windowGeometry, "datapackFiles");
		resourceDirectoryOverlay.captureGeometry().write(windowGeometry, "resourceFiles");
		resourceJsonOverlay.captureGeometry().write(windowGeometry, "resourceJson");
		resourcePngOverlay.captureGeometry().write(windowGeometry, "resourcePng");
		WindowGeometry manual = new WindowGeometry(manualDock.docked() ? manualFloatingX : manualWindowX,
				manualDock.docked() ? manualFloatingY : manualWindowY,
				manualDock.docked() ? manualFloatingWidth : manualWindowWidth,
				manualDock.docked() ? manualFloatingHeight : manualWindowHeight);
		if (!manualDock.docked()) manual = manualBoundsAnimation.destination(manual.x(), manual.y(), manual.width(), manual.height());
		manual.write(windowGeometry, "manual");
		WindowGeometry output = new WindowGeometry(outputDock.docked() ? outputFloatingX : outputWindowX,
				outputDock.docked() ? outputFloatingY : outputWindowY,
				outputDock.docked() ? outputFloatingWidth : outputWindowWidth,
				outputDock.docked() ? outputFloatingHeight : outputWindowHeight);
		if (!outputDock.docked()) output = outputBoundsAnimation.destination(output.x(), output.y(), output.width(), output.height());
		output.write(windowGeometry, "output");
		if (gitPanel != null) gitPanel.captureGeometry().write(windowGeometry, "git");
		if (personalizationPanel != null) personalizationPanel.captureGeometry().write(windowGeometry, "personalization");
		if (exportPanel != null) exportPanel.captureGeometry().write(windowGeometry, "export");
		windowGeometry.setProperty("interfaceScale", Float.toString(interfaceScale));
	}

	private void saveSession() {
		rememberCurrentDocument();
		breakpointStore.save();
		session.save(new LuaIdeSession.State(openDocuments.values().stream().map(document ->
				new LuaIdeSession.Document(document.name, document.json, document.resource,
						document.cursor, document.scroll, document.json ? LuaFunctionFolding.Snapshot.EMPTY
								: document.pendingFolds != null ? document.pendingFolds : document.functionFolds.snapshot(document.source))).toList(),
				currentDocument == null ? "" : currentDocument.key()));
	}

	private void rememberWorkspaceLayout() {
		if (nativeOwner != null) return;
		if (!workspaceLayoutRestored || insertOverlay == null || apiDocsOverlay == null
				|| componentEditorOverlay == null || datapackJsonEditorOverlay == null || textJsonOverlay == null
				|| minecraftUiEditorOverlay == null || fileOverlay == null || datapackOverlay == null
				|| resourceDirectoryOverlay == null || resourceJsonOverlay == null || resourcePngOverlay == null
				|| variableOverlay == null) return;
		WorkspaceLayout current = new WorkspaceLayout(layoutLocked,
				leftDockWidth, rightDockWidth, bottomDockHeight,
				manualDock, insertOverlay.dockSide(), apiDocsOverlay.dockSide(),
				componentEditorOverlay.dockSide(), datapackJsonEditorOverlay.dockSide(), textJsonOverlay.dockSide(), minecraftUiEditorOverlay.dockSide(),
				fileOverlay.dockSide(), datapackOverlay.dockSide(),
				resourceDirectoryOverlay.dockSide(), resourceJsonOverlay.dockSide(), resourcePngOverlay.dockSide(), outputDock,
				variableOverlay.dockSide(),
				persistedSelectedDockTool(DockSide.LEFT), persistedSelectedDockTool(DockSide.RIGHT),
				persistedSelectedDockTool(DockSide.BOTTOM),
				manualOpen, insertOverlay.isOpen(), apiDocsOverlay.isOpen(), componentEditorOverlay.isOpen(),
				datapackJsonEditorOverlay.isOpen(), textJsonOverlay.isOpen(), minecraftUiEditorOverlay.isOpen(),
				fileOverlay.isOpen(), datapackOverlay.isOpen(),
				resourceDirectoryOverlay.isOpen(), resourceJsonOverlay.isOpen(), resourcePngOverlay.isOpen(), outputVisible,
				variableOverlay.isOpen(),
				toolDock(ToolWindow.GIT), toolDock(ToolWindow.PERSONALIZATION),
				gitPanel != null && !gitPanel.isClosing(), personalizationPanel != null && !personalizationPanel.isClosing(),
				toolDock(ToolWindow.EXPORT), exportPanel != null && !exportPanel.isClosing());
		Properties extensionState = extensionLayoutSnapshot();
		Properties oldGeometry = new Properties(); oldGeometry.putAll(windowGeometry);
		captureWindowGeometry();
		if (current.equals(rememberedWorkspaceLayout) && extensionState.equals(rememberedExtensionLayout)
				&& oldGeometry.equals(windowGeometry)) return;
		rememberedExtensionLayout = extensionState;
		rememberedWorkspaceLayout = current;
		workspaceLayoutSavePending = true;
		workspaceLayoutSaveDueNanos = System.nanoTime() + 500_000_000L;
	}

	private ToolWindow persistedSelectedDockTool(DockSide side) {
		ToolWindow selected = selectedDockTool(side);
		if (selected != null && toolWindows().contains(selected) && toolDock(selected) == side) return selected;
		for (ToolWindow tool : toolWindows()) {
			if (toolDock(tool) == side) return tool;
		}
		return null;
	}

	private void flushWorkspaceLayout() {
		if (!workspaceLayoutSavePending) return;
		saveWorkspaceLayout(rememberedWorkspaceLayout);
		LuaIdeSession.writeProperties(WINDOW_GEOMETRY_FILE, windowGeometry);
		saveExtensionLayout();
		workspaceLayoutSavePending = false;
	}

	private void setDockBounds(ToolWindow tool, int x, int y, int width, int height) {
		switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).setDockBounds(x, y, width, height);
			case MANUAL -> manualBoundsAnimation.animateTo(
					manualWindowX, manualWindowY, manualWindowWidth, manualWindowHeight,
					x, y, width, height);
			case INSERT -> insertOverlay.setDockBounds(x, y, width, height);
			case API_DOCS -> apiDocsOverlay.setDockBounds(x, y, width, height);
			case COMPONENT_EDITOR -> componentEditorOverlay.setDockBounds(x, y, width, height);
			case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay.setDockBounds(x, y, width, height);
			case TEXT_JSON -> textJsonOverlay.setDockBounds(x, y, width, height);
			case MINECRAFT_UI -> minecraftUiEditorOverlay.setDockBounds(x, y, width, height);
			case FILES -> fileOverlay.setDockBounds(x, y, width, height);
			case DATAPACK_FILES -> datapackOverlay.setDockBounds(x, y, width, height);
			case RESOURCE_FILES -> resourceDirectoryOverlay.setDockBounds(x, y, width, height);
			case RESOURCE_JSON -> resourceJsonOverlay.setDockBounds(x, y, width, height);
			case RESOURCE_PNG -> resourcePngOverlay.setDockBounds(x, y, width, height);
			case OUTPUT -> outputBoundsAnimation.animateTo(
					outputWindowX, outputWindowY, outputWindowWidth, outputWindowHeight,
					x, y, width, height);
			case VARIABLES -> variableOverlay.setDockBounds(x, y, width, height);
			case GIT -> gitPanel.setDockBounds(x, y, width, height);
			case PERSONALIZATION -> personalizationPanel.setDockBounds(x, y, width, height);
			case EXPORT -> exportPanel.setDockBounds(x, y, width, height);
		}
	}

	private boolean beginDockResize(double mouseX, double mouseY) {
		if (!canResizeDock()) return false;
		applyDockLayout();
		DockSide hit = dockResizeSideAt(mouseX, mouseY);
		if (!hit.docked()) return false;
		activeDockResize = hit;
		return true;
	}

	private DockSide dockResizeSideAt(double mouseX, double mouseY) {
		// Shared by pointer handling and hover feedback; scrollbar pixels always
		// belong to scrolling, including during layout animations and at corners.
		if (LuaEditorViewport.scrollbarContains(editor, mouseX, mouseY)) return DockSide.FLOATING;
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
				return DockSide.BOTTOM;
			}
		}
		if (activeToolAt(DockSide.LEFT) != null
				&& mouseY >= WORKSPACE_TOP && mouseY <= workBottom
				&& Math.abs(mouseX - (workLeft + visibleLeftWidth)) <= DOCK_DIVIDER_HIT) {
			return DockSide.LEFT;
		}
		if (activeToolAt(DockSide.RIGHT) != null
				&& mouseY >= WORKSPACE_TOP && mouseY <= workBottom
				&& Math.abs(mouseX - (workRight - visibleRightWidth)) <= DOCK_DIVIDER_HIT) {
			return DockSide.RIGHT;
		}
		return DockSide.FLOATING;
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

	private LuaResourcePackToolOverlay resourceTool(ToolWindow tool) {
		return switch (tool.kind) {
			case RESOURCE_FILES -> resourceDirectoryOverlay;
			case RESOURCE_JSON -> resourceJsonOverlay;
			case RESOURCE_PNG -> resourcePngOverlay;
			default -> null;
		};
	}

	private boolean keepDockedWindowOnEscape(KeyEvent event, ToolWindow tool) {
		return event.key() == InputConstants.KEY_ESCAPE && layoutLocked
				&& toolDock(tool).docked() && !shortcutOpenedTools.contains(tool);
	}

	private boolean closeIdeOnPinnedEscape(KeyEvent event) {
		if (event.key() != InputConstants.KEY_ESCAPE || !layoutLocked || !activeMenu.isEmpty()) return false;
		for (ToolWindow tool : toolWindows()) {
			if (toolOpen(tool) && toolCanInteract(tool) && keepDockedWindowOnEscape(event, tool)) {
				onClose();
				return true;
			}
		}
		return false;
	}

	private LuaCursorContext.TextJsonArgument currentTextJsonArgumentContext() {
		if (editor == null) return null;
		MultilineTextField field = textField();
		return LuaCursorContext.textJsonArgumentAt(field.value(), field.cursor());
	}

	private void openTextJsonEditorForArgument(LuaCursorContext.TextJsonArgument argument) {
		if (textJsonOverlay == null) return;
		shortcutOpenedTools.add(ToolWindow.TEXT_JSON);
		MultilineTextField field = textField();
		String source = field.value();
		int start = Math.max(0, Math.min(source.length(), argument.replacementStart()));
		int end = Math.max(start, Math.min(source.length(), argument.replacementEnd()));
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, start);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, end);
		field.setSelecting(false);
		textJsonInsertionTarget = new TextJsonInsertionTarget(scriptName, source, start, end);
		hideMenus();
		manualOnTop = false;
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		textJsonOverlay.open();
		textJsonOverlay.requestKeyboardFocus();
		activateDockedTool(ToolWindow.TEXT_JSON);
		clearSuggestions();
		status = "已识别 " + argument.symbol() + " 的参数" + (argument.index() + 1)
				+ " 为 JSON 文本 · 插入时替换当前参数";
		syncOverlayState();
	}

	private void openComponentEditorForArgument(LuaCursorContext.ComponentArgument argument) {
		if (componentEditorOverlay == null) return;
		shortcutOpenedTools.add(ToolWindow.COMPONENT_EDITOR);
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
		shortcutOpenedTools.add(ToolWindow.INSERT);
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

	private boolean canUseEditorContext() {
		return sourceLoaded && currentDocument != null && currentDocument == editorContextDocument
				&& !currentDocument.binary() && editor != null && editor.active
				&& !recoveryPrompt && pendingCloseAction == null;
	}

	private void updateEditorContextAvailability() {
		if (!canUseEditorContext()) { editorContextMenu.close(); return; }
		editorContextMenu.availability(textField().hasSelection(), !minecraft.keyboardHandler.getClipboard().isEmpty(),
				!undoHistory.isEmpty());
	}

	private void focusCodeEditor() {
		focusExtensionWindow(null); focusedSettingsPanel = null;
		manualKeyboardFocused = variableWindowFocused = outputKeyboardFocused = false;
		if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
		if (datapackJsonEditorOverlay != null) datapackJsonEditorOverlay.releaseKeyboardFocus();
		if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
		if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
		if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
		if (datapackOverlay != null) datapackOverlay.releaseKeyboardFocus();
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_FILES, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_PNG)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null) overlay.releaseKeyboardFocus();
		}
		setFocused(editor);
	}

	private void runEditorContextAction(LuaEditorContextMenu.Action action) {
		if (!canUseEditorContext() || !editorContextMenu.enabled(action)) return;
		editorContextMenu.close(); focusCodeEditor(); clearSuggestions();
		switch (action) {
			case COPY -> { minecraft.keyboardHandler.setClipboard(selectedEditorText()); status = "已复制选中文字"; }
			case CUT -> {
				minecraft.keyboardHandler.setClipboard(selectedEditorText());
				textField().insertText(""); status = "已剪切选中文字";
			}
			case PASTE -> pasteSmart();
			case DELETE -> { textField().insertText(""); status = "已删除选中文字"; }
			case UNDO -> undo();
			case SEARCH -> openSearchFromSelection();
		}
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
		int lineStart = source.lastIndexOf('\n', selectionStart - 1) + 1;
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
		if (textJsonInsertionTarget != null) {
			TextJsonInsertionTarget target = textJsonInsertionTarget;
			textJsonInsertionTarget = null;
			if (!scriptName.equals(target.script()) || !field.value().equals(target.source())) {
				status = "源文件或参数已变化，请重新将光标放在 JSON 文本参数中按 Ctrl+I";
				return;
			}
			start = target.start();
			end = target.end();
		}
		replaceRange(start, end, luaJsonString);
		clearSuggestions();
		status = "已插入 Minecraft 文本组件 JSON 字符串";
	}

	private String currentDatapackJsonCategory() {
		return currentDocument != null && currentDocument.json && !currentDocument.resource
				? DatapackJsonDocumentCategory.fromPath(currentDocument.name) : null;
	}

	private String currentDatapackJson() {
		return currentDatapackJsonCategory() != null && editor != null ? textField().value() : null;
	}

	private void insertDatapackJson(String category, String json) {
		if (!category.equals(currentDatapackJsonCategory()) || editor == null) {
			status = "请先在 datapack 目录中打开同类型的 JSON 文件，再插入配置";
			return;
		}
		replaceRange(0, textField().value().length(), json);
		clearSuggestions();
		status = "数据包 JSON 已写入当前编辑器；请检查文件名与内容后保存";
	}

	private void insertMinecraftUiTemplate(String fields) {
		if (fields == null || fields.isBlank()) return;
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) textField();
		int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		LuaUiDefinitionInsertion.Edit edit = LuaUiDefinitionInsertion.plan(textField().value(), start, end, fields);
		replaceRange(edit.start(), edit.end(), edit.text());
		clearSuggestions();
		status = edit.filledExistingTable() ? "已填入 UI 表内代码" : "已插入 UI 定义表";
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
		LuaTextHistory.trim(undoHistory);
		redoHistory.clear();
		lastSnapshot = value;
	}

	private void scheduleValidation(String source) {
		aiProblem = null;
		int revision = validationRevision.incrementAndGet();
		boolean json = currentDocument != null && currentDocument.json;
		boolean function = json && currentDocument.name.endsWith(".mcfunction");
		syntaxMessage = "● 正在检查…";
		validationDetail = function ? "命令函数文本；暂不检查命令语义"
				: json ? "正在检查 JSON 格式…" : "正在检查 Lua 语法、变量作用域、表达式和 API 参数…";
		syntaxColor = 0xFFFFFF55;
		editorErrorRanges = List.of();
		errorLineNumbers = Set.of();
		warningLineNumbers = Set.of();
		LuaSyntaxHighlighter.clearErrors(editor);
		CompletableFuture.supplyAsync(() -> {
			if (revision != validationRevision.get()) {
				return null;
			}
			return function ? new LuaCodeAnalyzer.Analysis(List.of())
					: json ? JsonCodeAnalyzer.analyze(source) : LuaCodeAnalyzer.analyze(scriptName, source);
		}, CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS)).whenComplete((analysis, failure) ->
				minecraft.execute(() -> {
					if (revision != validationRevision.get()) {
						return;
					}
					if (failure != null) {
						editorErrorRanges = List.of();
						errorLineNumbers = Set.of();
						warningLineNumbers = Set.of();
						syntaxMessage = "✗ 检查失败";
						validationDetail = "检查器发生错误：" + ScriptManager.message(failure);
						syntaxColor = 0xFFFF5555;
						LuaSyntaxHighlighter.clearErrors(editor);
					} else if (analysis.diagnostics().isEmpty()) {
						editorErrorRanges = List.of();
						errorLineNumbers = Set.of();
						warningLineNumbers = Set.of();
						syntaxMessage = function ? "● 命令未检查" : "✓ 检查通过";
						validationDetail = function ? "命令函数文本未检查命令语义"
								: json ? "JSON 格式正确" : "未发现静态错误；动态加载的值仍需在运行时检查";
						syntaxColor = function ? 0xFFFFFF55 : 0xFF55FF55;
						LuaSyntaxHighlighter.clearErrors(editor);
					} else {
						LuaCodeAnalyzer.Diagnostic first = analysis.first();
						errorLineNumbers = Set.copyOf(analysis.diagnostics().stream()
								.filter(diagnostic -> diagnostic.severity() == LuaCodeAnalyzer.Severity.ERROR)
								.map(LuaCodeAnalyzer.Diagnostic::line)
								.filter(line -> line > 0)
								.toList());
						warningLineNumbers = Set.copyOf(analysis.diagnostics().stream()
								.filter(diagnostic -> diagnostic.severity() == LuaCodeAnalyzer.Severity.WARNING)
								.map(LuaCodeAnalyzer.Diagnostic::line).filter(line -> line > 0).toList());
						syntaxMessage = analysis.errorCount() > 0 ? "✗ " + analysis.errorCount() + " 个错误"
								+ (analysis.warningCount() > 0 ? " / " + analysis.warningCount() + " 个警告" : "")
								: "⚠ " + analysis.warningCount() + " 个警告";
						validationDetail = first.displayMessage()
								+ (analysis.diagnostics().size() > 1 ? "（另有 "
								+ (analysis.diagnostics().size() - 1) + " 个问题）" : "");
						syntaxColor = analysis.errorCount() > 0 ? 0xFFFF5555 : 0xFFE6AD45;
						editorErrorRanges = analysis.diagnostics().stream()
								.map(diagnostic -> new LuaSyntaxHighlighter.ErrorRange(
										diagnostic.start(), diagnostic.end(), diagnostic.severity() == LuaCodeAnalyzer.Severity.WARNING))
								.sorted(java.util.Comparator.comparingInt(LuaSyntaxHighlighter.ErrorRange::start)
										.thenComparing(LuaSyntaxHighlighter.ErrorRange::warning))
								.toList();
						LuaSyntaxHighlighter.setErrors(editor, editorErrorRanges);
						if (currentDocument != null && sourceLoaded && analysis.errorCount() > 0) {
							var errors = analysis.diagnostics().stream().filter(d -> d.severity() == LuaCodeAnalyzer.Severity.ERROR).limit(12).toList();
							aiProblem = new LuaAiAgent.Problem(aiPath(currentDocument), source,
									String.join("\n", errors.stream().map(LuaCodeAnalyzer.Diagnostic::displayMessage).toList()),
									errors.stream().map(LuaCodeAnalyzer.Diagnostic::line).toList());
						}
					}
				}));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (editorContextMenu.isOpen()) {
			updateEditorContextAvailability();
			if (event.key() == InputConstants.KEY_ESCAPE) { editorContextMenu.close(); return true; }
			if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
				editorContextMenu.move(event.key() == InputConstants.KEY_UP ? -1 : 1); return true;
			}
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				var action = editorContextMenu.selected();
				if (action != null) runEditorContextAction(action);
				return true;
			}
			editorContextMenu.close();
		}
		if (event.key() == InputConstants.KEY_ESCAPE && hoveredAiMark != null && !leftMouseHeld
				&& !recoveryPrompt && pendingCloseAction == null) {
			if (hoveredAiMark.severity().equals("mcp")) { clearMcpMarks(); status = "已清除全部 MCP 修改标记"; }
			else { ai.clearMarks(); status = "已清除全部 AI 标注"; }
			hoveredAiMark = null; return true;
		}
		if (recoveryPrompt) {
			if (event.key() == InputConstants.KEY_ESCAPE) chooseRecoveryPrompt(1);
			else if (event.key() == InputConstants.KEY_TAB || event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT)
				recoveryPromptChoice = 1 - recoveryPromptChoice;
			else if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) chooseRecoveryPrompt(recoveryPromptChoice);
			return true;
		}
		if (pendingCloseAction != null) {
			if (!closeSaving) {
				if (event.key() == InputConstants.KEY_ESCAPE) cancelClose();
				else if (event.key() == InputConstants.KEY_TAB) closeChoice = Math.floorMod(closeChoice + (event.hasShiftDown() ? -1 : 1), 3);
				else if (event.key() == InputConstants.KEY_LEFT) closeChoice = Math.floorMod(closeChoice - 1, 3);
				else if (event.key() == InputConstants.KEY_RIGHT) closeChoice = (closeChoice + 1) % 3;
				else if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) chooseClose(closeChoice);
			}
			return true;
		}
		LuaWindowShortcuts.Target shortcut = personalization.windowShortcuts.match(event.key(), event.modifiers());
		if (shortcut != LuaWindowShortcuts.Target.NONE) {
			openShortcutWindow(shortcut);
			return true;
		}
		if (event.key() == InputConstants.KEY_F12 && (event.modifiers() & 15) == 0) {
			minecraft.setScreen(new LuaDebugScreen(this)); return true;
		}
		if (variableWindowFocused && variableOverlay != null && variableOverlay.isOpen()
				&& toolCanInteract(ToolWindow.VARIABLES) && variableOverlay.keyPressed(event)) return true;
		if (outputKeyboardFocused && outputInteractive() && toolCanInteract(ToolWindow.OUTPUT)
				&& event.hasControlDown()) {
			if (event.key() == InputConstants.KEY_A) {
				outputSelectionSource = String.join("\n", outputLines);
				outputSelectionAnchor = 0; outputSelectionCaret = outputSelectionSource.length();
				return true;
			}
			if (event.key() == InputConstants.KEY_C) { copyOutput(false); return true; }
		}
		var debugAction = LuaDebugKeyBindings.match(event);
		if (debugAction != null) { performDebuggerAction(debugAction); return true; }
		if (activeMenu.isEmpty() && focusedExtensionWindow != null && focusedExtensionWindow.hasFocus()
				&& toolCanInteract(ToolWindow.extension(focusedExtensionWindow.id()))) {
			if (event.key() == InputConstants.KEY_ESCAPE) {
				if (keepDockedWindowOnEscape(event, ToolWindow.extension(focusedExtensionWindow.id()))) onClose();
				else { focusedExtensionWindow.close(); focusExtensionWindow(null); syncOverlayState(); }
			} else focusedExtensionWindow.keyPressed(event);
			return true;
		}
		if (focusedSettingsPanel() != null) {
			if (event.key() == InputConstants.KEY_ESCAPE) {
				if (layoutLocked && ((focusedSettingsPanel == gitPanel && gitPanel.dockSide().docked()
						&& !shortcutOpenedTools.contains(ToolWindow.GIT))
						|| (focusedSettingsPanel == personalizationPanel
						&& personalizationPanel.dockSide().docked()
						&& !shortcutOpenedTools.contains(ToolWindow.PERSONALIZATION))
						|| (focusedSettingsPanel == exportPanel && exportPanel.dockSide().docked()))) onClose();
				else focusedSettingsPanel.onClose();
			}
			else focusedSettingsPanel.keyPressed(event);
			return true;
		}
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
		if (event.hasControlDown() && event.key() == InputConstants.KEY_I && isFunctionDocument()
				&& (datapackOverlay == null || !datapackOverlay.hasKeyboardFocus())) {
			toggleFunctionInsertOverlay();
			if (insertOverlay != null && insertOverlay.isOpen()) shortcutOpenedTools.add(ToolWindow.INSERT);
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_I && currentDatapackJsonCategory() != null
				&& (datapackOverlay == null || !datapackOverlay.hasKeyboardFocus())) {
			hideMenus();
			datapackJsonEditorOverlay.openForDocument(currentDatapackJsonCategory(), currentDatapackJson());
			activateDockedTool(ToolWindow.DATAPACK_JSON_EDITOR);
			shortcutOpenedTools.add(ToolWindow.DATAPACK_JSON_EDITOR);
			clearSuggestions();
			syncOverlayState();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_I && !isJsonDocument()
				&& (datapackOverlay == null || !datapackOverlay.hasKeyboardFocus())) {
			LuaCursorContext.ComponentArgument componentArgument = currentItemComponentsArgumentContext();
			LuaCursorContext.TextJsonArgument textArgument = currentTextJsonArgumentContext();
			LuaCursorContext.Argument idArgument = currentArgumentContext();
			if (idArgument != null && idArgument.kind() == LuaInsertCatalog.Kind.ENCHANTMENT) {
				textJsonInsertionTarget = null;
				toggleInsertOverlay();
				if (insertOverlay != null && insertOverlay.isOpen()) shortcutOpenedTools.add(ToolWindow.INSERT);
			} else if (textArgument != null) {
				openTextJsonEditorForArgument(textArgument);
			} else if (componentArgument != null) {
				textJsonInsertionTarget = null;
				openComponentEditorForArgument(componentArgument);
			} else {
				textJsonInsertionTarget = null;
				toggleInsertOverlay();
				if (insertOverlay != null && insertOverlay.isOpen()) shortcutOpenedTools.add(ToolWindow.INSERT);
			}
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_TAB && !openDocuments.isEmpty()) {
			List<OpenDocument> documents = new ArrayList<>(openDocuments.values());
			int current = documents.indexOf(currentDocument);
			int direction = event.hasShiftDown() ? -1 : 1;
			OpenDocument next = documents.get(Math.floorMod(current + direction, documents.size()));
			if (next.resource) openResourcePackFile(next.name);
			else if (next.json) openDatapackFile(next.name);
			else openScript(next.name);
			return true;
		}
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_FILES, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_PNG)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && overlay.isOpen() && overlay.hasKeyboardFocus() && toolCanInteract(tool)
					&& (!keepDockedWindowOnEscape(event, tool) || overlay.hasTransientUi())
					&& overlay.keyPressed(event)) {
				syncOverlayState();
				return true;
			}
		}
		if (currentResourcePng() != null && event.hasControlDown()) {
			if (event.key() == InputConstants.KEY_Z) {
				if (event.hasShiftDown()) currentResourcePng().redo(); else currentResourcePng().undo();
				return true;
			}
			if (event.key() == InputConstants.KEY_Y) { currentResourcePng().redo(); return true; }
		}
		if (minecraftUiEditorOverlay != null && minecraftUiEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.MINECRAFT_UI)
				&& !keepDockedWindowOnEscape(event, ToolWindow.MINECRAFT_UI)) {
			boolean handled = minecraftUiEditorOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (insertOverlay != null && insertOverlay.isOpen() && toolCanInteract(ToolWindow.INSERT)) {
			if (!keepDockedWindowOnEscape(event, ToolWindow.INSERT)) {
				boolean handled = insertOverlay.keyPressed(event);
				syncOverlayState();
				if (handled) return true;
			}
		}
		if (componentEditorOverlay != null && componentEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.COMPONENT_EDITOR)
				&& !keepDockedWindowOnEscape(event, ToolWindow.COMPONENT_EDITOR)) {
			boolean handled = componentEditorOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (datapackJsonEditorOverlay != null && datapackJsonEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.DATAPACK_JSON_EDITOR)
				&& !keepDockedWindowOnEscape(event, ToolWindow.DATAPACK_JSON_EDITOR)) {
			boolean handled = datapackJsonEditorOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (textJsonOverlay != null && textJsonOverlay.isOpen()
				&& toolCanInteract(ToolWindow.TEXT_JSON)
				&& !keepDockedWindowOnEscape(event, ToolWindow.TEXT_JSON)) {
			boolean handled = textJsonOverlay.keyPressed(event);
			syncOverlayState();
			if (handled) return true;
		}
		if (fileOverlay != null && fileOverlay.isOpen() && toolCanInteract(ToolWindow.FILES)) {
			if (!keepDockedWindowOnEscape(event, ToolWindow.FILES)) {
				boolean handled = fileOverlay.keyPressed(event);
				if (handled) {
					syncOverlayState();
					return true;
				}
			}
		}
		if (datapackOverlay != null && datapackOverlay.isOpen()
				&& toolCanInteract(ToolWindow.DATAPACK_FILES)
				&& !keepDockedWindowOnEscape(event, ToolWindow.DATAPACK_FILES)) {
			boolean handled = datapackOverlay.keyPressed(event);
			if (handled) { syncOverlayState(); return true; }
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_F) {
			openSearchFromSelection();
			return true;
		}
		if (event.key() == InputConstants.KEY_F1 && (event.modifiers() & 15) == 0) {
			toggleManual();
			if (manualOpen) shortcutOpenedTools.add(ToolWindow.MANUAL);
			return true;
		}
		if (searchOpen && (getFocused() == searchBox || event.key() == InputConstants.KEY_ESCAPE)) {
			return handleSearchKey(event);
		}
		if (variableWindowFocused && variableOverlay != null && variableOverlay.isOpen()
				&& toolCanInteract(ToolWindow.VARIABLES)) {
			if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
				variableOverlay.scrollRows(event.key() == InputConstants.KEY_UP ? -1 : 1);
				return true;
			}
			if (event.key() == InputConstants.KEY_ESCAPE
					&& !keepDockedWindowOnEscape(event, ToolWindow.VARIABLES)) {
				variableOverlay.close();
				variableWindowFocused = false;
				applyDockLayout();
				return true;
			}
		}
		if (apiDocsOverlay != null && apiDocsOverlay.isOpen()
				&& toolCanInteract(ToolWindow.API_DOCS)
				&& !keepDockedWindowOnEscape(event, ToolWindow.API_DOCS)
				&& apiDocsOverlay.keyPressed(event)) {
			syncOverlayState();
			return true;
		}
		if (manualOpen && manualKeyboardFocused && toolCanInteract(ToolWindow.MANUAL)
				&& event.hasControlDown() && event.key() == InputConstants.KEY_C
				&& hasManualSelection()) {
			copyManualSelection();
			return true;
		}
		if (manualOpen && manualKeyboardFocused && toolCanInteract(ToolWindow.MANUAL)
				&& event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			manualSelectionAnchor = 0;
			manualSelectionCaret = manualTextIndex.text().length();
			status = "已全选使用手册，按 Ctrl+C 复制";
			return true;
		}
		if (manualOpen && toolCanInteract(ToolWindow.MANUAL) && event.key() == InputConstants.KEY_ESCAPE
				&& !keepDockedWindowOnEscape(event, ToolWindow.MANUAL)
				&& (insertOverlay == null || !insertOverlay.isOpen() || manualOnTop)) {
			manualOpen = false;
			rememberWorkspaceLayout();
			return true;
		}
		if (manualOpen && toolCanInteract(ToolWindow.MANUAL) && event.key() == InputConstants.KEY_ESCAPE
				&& !keepDockedWindowOnEscape(event, ToolWindow.MANUAL)) {
			manualOpen = false;
			rememberWorkspaceLayout();
			return true;
		}
		if (closeIdeOnPinnedEscape(event)) return true;
		if (editor.isFocused() && textField().hasSelection()) clearSuggestions();
		if (editor.isFocused() && event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			pasteSmart();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_Z) {
			if (event.hasShiftDown()) redo(); else undo();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_Y) {
			redo();
			return true;
		}
		if (editor.isFocused() && (!isJsonDocument() || isFunctionDocument())
				&& event.hasControlDown() && event.key() == InputConstants.KEY_SLASH) {
			toggleCurrentLineComment();
			return true;
		}
		if (editor.isFocused() && !event.hasShiftDown() && !suggestions.isEmpty() && event.key() == InputConstants.KEY_UP) {
			moveSuggestionSelection(-1);
			return true;
		}
		if (editor.isFocused() && !event.hasShiftDown() && !suggestions.isEmpty() && event.key() == InputConstants.KEY_DOWN) {
			moveSuggestionSelection(1);
			return true;
		}
		if (editor.isFocused() && !event.hasShiftDown() && event.key() == InputConstants.KEY_RIGHT) {
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
			if (isFunctionDocument()) {
				if (!suggestions.isEmpty()) applySelectedSuggestion();
				else if (textField().value().substring(textField().value().lastIndexOf('\n',
						textField().cursor() - 1) + 1, textField().cursor()).isBlank())
					textField().insertText("    ");
				else refreshFunctionSuggestions();
			} else if (isJsonDocument()) {
				textField().insertText("    ");
			} else if (suggestions.isEmpty()) {
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
			dismissedSuggestionSource = textField().value();
			dismissedSuggestionCursor = textField().cursor();
			clearSuggestions();
			return true;
		}
		if (editor.isFocused() && event.key() == InputConstants.KEY_RETURN && event.hasControlDown()) {
			if (isJsonDocument() && !isFunctionDocument()) saveScript();
			else toggleRun();
			return true;
		}
		if (event.key() == InputConstants.KEY_S && event.hasControlDown()) {
			saveScript();
			return true;
		}
		if (editor.isFocused() && !isJsonDocument() && event.key() == InputConstants.KEY_RETURN) {
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
		editorContextMenu.close();
		if (recoveryPrompt || pendingCloseAction != null) return true;
		if (activeMenu.isEmpty() && focusedExtensionWindow != null && focusedExtensionWindow.hasFocus()
				&& toolCanInteract(ToolWindow.extension(focusedExtensionWindow.id()))) {
			focusedExtensionWindow.charTyped(event); return true;
		}
		if (focusedSettingsPanel() != null) {
			focusedSettingsPanel.charTyped(event);
			return true;
		}
		if (scaleInput != null && scaleInput.visible && getFocused() == scaleInput) {
			return super.charTyped(event);
		}
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_FILES, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_PNG)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && overlay.isOpen() && overlay.hasKeyboardFocus() && toolCanInteract(tool)
					&& overlay.charTyped(event)) return true;
		}
		if (insertOverlay != null && insertOverlay.isOpen() && toolCanInteract(ToolWindow.INSERT)
				&& insertOverlay.charTyped(event)) return true;
		if (componentEditorOverlay != null && componentEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.COMPONENT_EDITOR)
				&& componentEditorOverlay.charTyped(event)) return true;
		if (datapackJsonEditorOverlay != null && datapackJsonEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.DATAPACK_JSON_EDITOR)
				&& datapackJsonEditorOverlay.charTyped(event)) return true;
		if (textJsonOverlay != null && textJsonOverlay.isOpen()
				&& toolCanInteract(ToolWindow.TEXT_JSON)
				&& textJsonOverlay.charTyped(event)) return true;
		if (minecraftUiEditorOverlay != null && minecraftUiEditorOverlay.isOpen()
				&& toolCanInteract(ToolWindow.MINECRAFT_UI)
				&& minecraftUiEditorOverlay.charTyped(event)) return true;
		if (fileOverlay != null && fileOverlay.isOpen() && toolCanInteract(ToolWindow.FILES)
				&& fileOverlay.charTyped(event)) return true;
		if (datapackOverlay != null && datapackOverlay.isOpen()
				&& toolCanInteract(ToolWindow.DATAPACK_FILES) && datapackOverlay.charTyped(event)) return true;
		if (searchOpen && getFocused() == searchBox) return super.charTyped(event);
		if (apiDocsOverlay != null && apiDocsOverlay.isOpen() && toolCanInteract(ToolWindow.API_DOCS)
				&& apiDocsOverlay.charTyped(event)) return true;
		boolean handled = super.charTyped(event);
		if (handled && editor.isFocused()) {
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
		LuaTextHistory.trim(redoHistory);
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
		LuaTextHistory.trim(undoHistory);
		applyHistory(next);
		status = "已重做";
	}

	private void applyHistory(String source) {
		int cursor = LuaTextHistory.mapCursor(editor.getValue(), source, textField().cursor());
		double scroll = editor.scrollAmount();
		int horizontal = LuaEditorViewport.offset(editor);
		internalTextChange = true;
		try {
			editor.setValue(source);
			textField().setSelecting(false);
			textField().seekCursor(Whence.ABSOLUTE, cursor);
			editor.setScrollAmount(scroll);
			LuaEditorViewport.restoreOffset(editor, horizontal);
		} finally { internalTextChange = false; }
		lastSnapshot = source;
		clearSuggestions();
		scheduleValidation(source);
	}

	private void toggleCurrentLineComment() {
		if (currentDocument != null && currentDocument.json
				&& !currentDocument.name.endsWith(".mcfunction")) {
			status = "JSON 不支持行注释";
			return;
		}
		String marker = currentDocument != null && currentDocument.name.endsWith(".mcfunction") ? "#" : "--";
		MultilineTextField field = textField();
		String source = field.value();
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		int selectionStart = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int selectionEnd = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int lineStart = source.lastIndexOf('\n', selectionStart - 1) + 1;
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
			if (!line.isBlank() && !line.stripLeading().startsWith(marker)) {
				allCommented = false;
				break;
			}
		}
		String transformed = allCommented
				? block.replaceAll("(?m)^([\\t ]*)" + marker + " ?", "$1")
				: block.replaceAll("(?m)^([\\t ]*)(?=\\S)", "$1" + marker + " ");
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
		if (editorContextMenu.isOpen()) { clearSuggestions(); return; }
		if (editor != null && textField().hasSelection()) {
			clearSuggestions();
			return;
		}
		if (isFunctionDocument()) {
			refreshFunctionSuggestions();
			return;
		}
		if (currentDocument != null && currentDocument.json) {
			clearSuggestions();
			return;
		}
		if (editor == null || !editor.isFocused()) {
			clearSuggestions();
			return;
		}
		MultilineTextField field = textField();
		String source = field.value();
		int cursor = field.cursor();
		if (cursor == dismissedSuggestionCursor && source.equals(dismissedSuggestionSource)) { clearSuggestions(); return; }
		dismissedSuggestionSource = null;
		dismissedSuggestionCursor = -1;
		LuaCursorContext.Parameter parameter = LuaCursorContext.parameterAt(source, cursor);
		if (parameter != null) {
			refreshArgumentSuggestions(source, cursor, parameter);
			return;
		}
		argumentSuggestionChoices = Map.of();
		moduleSuggestionChoices = Map.of();
		int start = cursor;
		while (start > 0) {
			char character = source.charAt(start - 1);
			if (!Character.isLetterOrDigit(character) && character != '_' && character != '.' && character != ':') {
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
		List<String> visibleVariables = LuaCodeAnalyzer.visibleVariables(source, cursor);
		Set<String> variableNames = Set.copyOf(visibleVariables);
		LuaFunctionModel functions = functionModel();
		moduleSuggestionChoices = moduleChoices(source, cursor, prefix);
		List<String> nextSuggestions = java.util.stream.Stream.concat(java.util.stream.Stream.concat(completions(visibleVariables).stream(),
				functions == null ? java.util.stream.Stream.empty() : functions.symbols(cursor).stream()), moduleSuggestionChoices.keySet().stream())
				.filter(value -> (value.regionMatches(true, 0, prefix, 0, prefix.length())
						|| moduleSuggestionChoices.containsKey(value) && value.replace(':', '.').regionMatches(true, 0,
								prefix.replace(':', '.'), 0, prefix.length())
						|| functions != null && functions.find(value, cursor) != null
						&& functions.find(value, cursor).displayName().regionMatches(true, 0, prefix, 0, prefix.length()))
						&& !value.equalsIgnoreCase(prefix))
				.distinct()
				.sorted(Comparator.comparingInt((String value) -> variableNames.contains(value) ? 0 : 1)
						.thenComparingInt(String::length).thenComparing(String::compareTo))
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

	private void refreshArgumentSuggestions(String source, int cursor, LuaCursorContext.Parameter parameter) {
		if (argumentCatalogRevision != ScriptManager.catalogRevision()) {
			argumentCatalogRevision = ScriptManager.catalogRevision();
			argumentCatalogs.clear();
			argumentCatalogRequests.clear();
		}
		argumentApiRevision = LuaApiDocumentation.revision();
		LuaInsertCatalog.Kind kind = LuaArgumentCompletion.kind(parameter);
		List<LuaInsertCatalog.Entry> catalog = kind == null ? List.of() : argumentCatalog(kind);
		List<LuaArgumentCompletion.Choice> choices = LuaArgumentCompletion.suggest(source, cursor, parameter, catalog);
		moduleSuggestionChoices = parameter.quoted() ? Map.of() : moduleChoices(source, cursor, parameter.query());
		if (!moduleSuggestionChoices.isEmpty()) {
			List<LuaArgumentCompletion.Choice> combined = new ArrayList<>(choices);
			boolean reference = LuaCodeAnalyzer.parameterType(parameter) == LuaCodeAnalyzer.ExpectedType.FUNCTION;
			for (var choice : moduleSuggestionChoices.values()) combined.add(reference
					? new LuaArgumentCompletion.Choice(choice.insertion(), choice.label(), choice.tooltip(), choice.rank(), false) : choice);
			choices = combined;
		}
		if (!parameter.quoted() && parameter.query().contains(".")) {
			List<LuaArgumentCompletion.Choice> withStandardLibraries = new ArrayList<>(choices);
			for (String symbol : BASE_COMPLETIONS) if (symbol.regionMatches(true, 0, parameter.query(), 0, parameter.query().length())
					&& !symbol.equals(parameter.query()))
				withStandardLibraries.add(new LuaArgumentCompletion.Choice(symbol, symbol, "Lua 标准库函数", 2));
			choices = withStandardLibraries;
		}
		if (choices.isEmpty()) { clearSuggestions(); return; }
		String previous = suggestions.isEmpty() ? null : suggestions.get(Math.clamp(selectedSuggestion, 0, suggestions.size() - 1));
		completionStart = parameter.start();
		completionEnd = parameter.end();
		Map<String, LuaArgumentCompletion.Choice> indexed = new LinkedHashMap<>();
		for (var choice : choices) indexed.putIfAbsent(choice.insertion(), choice);
		argumentSuggestionChoices = Map.copyOf(indexed);
		functionSuggestionTooltips = Map.of();
		suggestions = List.copyOf(indexed.keySet());
		int preserved = previous == null ? -1 : suggestions.indexOf(previous);
		selectedSuggestion = preserved < 0 ? 0 : preserved;
		if (!suggestionAnimationReady || preserved < 0) resetSuggestionAnimation();
	}

	private List<LuaInsertCatalog.Entry> argumentCatalog(LuaInsertCatalog.Kind kind) {
		List<LuaInsertCatalog.Entry> cached = argumentCatalogs.get(kind);
		if (cached == null) {
			cached = LuaInsertCatalog.load(kind, minecraft);
			argumentCatalogs.put(kind, cached);
		}
		if (!kind.registryId().isEmpty() && argumentCatalogRequests.add(kind)) {
			int revision = argumentCatalogRevision;
			ScriptManager.registryCatalog(kind.registryId()).whenComplete((remote, error) -> minecraft.execute(() -> {
				if (revision != argumentCatalogRevision || revision != ScriptManager.catalogRevision()) return;
				if (error == null && remote != null) {
					argumentCatalogs.put(kind, LuaResourceAssetCatalog.merge(remote, argumentCatalogs.getOrDefault(kind, List.of())));
					if (isCurrentIde() && editor != null && editor.isFocused() && !isJsonDocument() && !isFunctionDocument()) {
						LuaCursorContext.Parameter current = LuaCursorContext.parameterAt(textField().value(), textField().cursor());
						if (current != null && LuaArgumentCompletion.kind(current) == kind) refreshSuggestions();
					}
				}
			}));
		}
		return argumentCatalogs.getOrDefault(kind, cached);
	}

	private String suggestionLabel(String value) {
		var choice = argumentSuggestionChoices.get(value);
		if (choice == null) choice = moduleSuggestionChoices.get(value);
		if (choice != null) return choice.label();
		var functions = functionModel();
		var function = functions == null ? null : functions.find(value, textField().cursor());
		return function == null ? LuaApiDocumentation.completionLabel(value) : function.label();
	}

	private Map<String, LuaArgumentCompletion.Choice> moduleChoices(String source, int cursor, String prefix) {
		if (prefix.isEmpty()) return Map.of();
		Map<String, LuaArgumentCompletion.Choice> result = new LinkedHashMap<>();
		for (var variable : LuaCodeAnalyzer.visibleVariableValues(source, cursor).entrySet()) {
			String alias = variable.getKey(), module = variable.getValue().moduleName();
			if (module == null || !(prefix.startsWith(alias + ".") || prefix.startsWith(alias + ":"))) continue;
			for (var choice : moduleCompletion.choices(alias, module, prefix, this::moduleSource))
				result.putIfAbsent(choice.insertion(), choice);
		}
		return result;
	}

	private String moduleSource(String module) {
		OpenDocument opened = openDocuments.get("lua:" + module);
		if (opened != null && opened.loaded) return opened == currentDocument && editor != null ? textField().value() : opened.source;
		ModuleSource cached = moduleSources.get(module);
		long now = System.nanoTime();
		if ((cached == null || now - cached.readAt > TimeUnit.SECONDS.toNanos(30))
				&& moduleSourceRequests.size() < 16 && now >= moduleSourceFailures.getOrDefault(module, 0L)
				&& moduleSourceRequests.add(module)) {
			ScriptManager.read(module).whenComplete((source, failure) -> minecraft.execute(() -> {
				moduleSourceRequests.remove(module);
				if (failure != null) {
					moduleSourceFailures.put(module, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
					return;
				}
				moduleSourceFailures.remove(module);
				// Keep client completion memory bounded, independently of script limits.
				if (source.length() <= LuaProtocol.MAX_SCRIPT_CHARS) {
					moduleSources.put(module, new ModuleSource(source, System.nanoTime()));
					int chars = moduleSources.values().stream().mapToInt(value -> value.source.length()).sum();
					while (moduleSources.size() > 64 || chars > 20_000_000) {
						String first = moduleSources.keySet().iterator().next(); chars -= moduleSources.remove(first).source.length();
					}
				} else moduleSourceFailures.put(module, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
				if (isCurrentIde() && editor != null && editor.isFocused() && !isJsonDocument()) refreshSuggestions();
			}));
		}
		return cached == null ? null : cached.source;
	}

	private record ModuleSource(String source, long readAt) { }

	private void refreshFunctionSuggestions() {
		argumentSuggestionChoices = Map.of();
		if (editor == null || !editor.isFocused() || textField().hasSelection() || minecraft.getConnection() == null) {
			clearSuggestions();
			return;
		}
		MultilineTextField field = textField();
		String source = field.value();
		int cursor = field.cursor();
		int lineStart = source.lastIndexOf('\n', cursor - 1) + 1;
		int commandStart = lineStart;
		while (commandStart < cursor && Character.isWhitespace(source.charAt(commandStart))) commandStart++;
		if (commandStart >= cursor || source.charAt(commandStart) == '#') {
			clearSuggestions();
			return;
		}
		if (source.charAt(commandStart) == '$') commandStart++;
		if (commandStart >= cursor) { clearSuggestions(); return; }
		String command = source.substring(commandStart, cursor);
		int absoluteCommandStart = commandStart;
		OpenDocument document = currentDocument;
		int revision = ++functionSuggestionRevision;
		try {
			var connection = minecraft.getConnection();
			var dispatcher = connection.getCommands();
			var parsed = dispatcher.parse(command, connection.getSuggestionsProvider());
			dispatcher.getCompletionSuggestions(parsed, command.length()).whenComplete((result, error) ->
					minecraft.execute(() -> {
						if (revision != functionSuggestionRevision || currentDocument != document || !isFunctionDocument()
								|| editor == null || !editor.isFocused() || !source.equals(textField().value())
								|| textField().hasSelection() || cursor != textField().cursor()) return;
						if (error != null || result == null || result.isEmpty()) {
							clearSuggestions();
							return;
						}
						String previous = suggestions.isEmpty() ? null
								: suggestions.get(Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1)));
						completionStart = absoluteCommandStart + result.getRange().getStart();
						completionEnd = absoluteCommandStart + result.getRange().getEnd();
						if (completionStart < 0 || completionEnd > source.length() || completionStart > cursor) {
							clearSuggestions(); return;
						}
						String typed = source.substring(completionStart, cursor);
						List<String> next = result.getList().stream().map(Suggestion::getText)
								.filter(value -> !value.equalsIgnoreCase(typed)).distinct().toList();
						if (next.isEmpty()) { clearSuggestions(); return; }
						Map<String, String> tooltips = new LinkedHashMap<>();
						for (Suggestion suggestion : result.getList()) if (suggestion.getTooltip() != null)
							tooltips.put(suggestion.getText(), suggestion.getTooltip().getString());
						functionSuggestionTooltips = Map.copyOf(tooltips);
						suggestions = next;
						int preserved = previous == null ? -1 : next.indexOf(previous);
						selectedSuggestion = preserved < 0 ? 0 : preserved;
						resetSuggestionAnimation();
					}));
		} catch (RuntimeException failure) {
			clearSuggestions();
		}
	}

	private void moveSuggestionSelection(int direction) {
		if (suggestions.isEmpty() || direction == 0) return;
		selectedSuggestion = Math.max(0,
				Math.min(suggestions.size() - 1, selectedSuggestion + direction));
	}

	private void clearSuggestions() {
		functionSuggestionRevision++;
		functionSuggestionTooltips = Map.of();
		argumentSuggestionChoices = Map.of();
		moduleSuggestionChoices = Map.of();
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
		if (editor == null || suggestions.isEmpty()) return;
		if (textField().hasSelection()) {
			clearSuggestions();
			return;
		}
		String completion = suggestions.get(selectedSuggestion);
		String completionLabel = suggestionLabel(completion);
		MultilineTextField field = textField();
		String source = field.value();
		var argumentChoice = argumentSuggestionChoices.get(completion);
		boolean apiFunction = !isFunctionDocument() && (argumentChoice == null || argumentChoice.functionCall())
				&& (LuaApiDocumentation.find(completion) != null
						|| functionModel() != null && functionModel().find(completion, field.cursor()) != null
						|| moduleSuggestionChoices.containsKey(completion));
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
		status = "已补全：" + (isFunctionDocument() ? completion : completionLabel);
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
		if (isJsonDocument()) {
			field.insertText(clipboard);
			status = isFunctionDocument() ? "已粘贴命令函数文本" : "已粘贴 JSON 文本";
			return;
		}
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) field;
		String source = field.value();
		int selectionStart = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int selectionEnd = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int replaceStart = selectionStart;
		boolean multiline = LuaIndentation.pasteWillBeMultiline(clipboard);
		if (multiline) {
			int lineStart = source.lastIndexOf('\n', selectionStart - 1) + 1;
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
		String inserted = isFunctionDocument() || isInsideQuotedString(source, selectionStart)
				? entry.value() : "\"" + entry.value().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		replaceRange(selectionStart, selectionEnd, inserted);
		clearSuggestions();
		status = "已插入：" + entry.value();
	}

	private boolean insertPickedColor(LuaColorModel color) {
		if (recoveryPrompt || pendingCloseAction != null || !sourceLoaded || currentDocument == null || currentDocument.binary()) return false;
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) textField();
		int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		String value = color.text();
		if (color.stringOutput() && !isFunctionDocument() && !isInsideQuotedString(editor.getValue(), start)) value = "\"" + value + "\"";
		replaceRange(start, end, value); clearSuggestions(); status = "已插入颜色：" + color.text();
		return true;
	}

	private boolean isInsideQuotedString(String source, int cursor) {
		int safeCursor = Math.max(0, Math.min(cursor, source.length()));
		int lineStart = source.lastIndexOf('\n', safeCursor - 1) + 1;
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
		int lineStart = source.lastIndexOf('\n', cursor - 1) + 1;
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

	private boolean isJsonDocument() {
		return currentDocument != null && currentDocument.json && !currentDocument.binary();
	}

	private boolean isFunctionDocument() {
		return currentDocument != null && currentDocument.json && !currentDocument.resource
				&& currentDocument.name.endsWith(".mcfunction");
	}

	private boolean saveScript() {
		if (currentDocument == null) {
			status = "请先在文件窗口新建或读取一个脚本";
			return false;
		}
		if (currentDocument.binary()) {
			if (currentDocument.resource && currentDocument.pngCanvas != null) {
				try { currentDocument.pngCanvas.save(); snapshotRecovery(); status = "PNG 已保存到客户端 resourcepacks：" + currentDocument.name; return true; }
				catch (Exception error) { status = "PNG 保存失败：" + error.getMessage(); return false; }
			}
			status = "数据包 PNG、OGG 和 NBT 仅支持只读预览";
			return false;
		}
		if (!sourceLoaded) {
			status = "脚本尚未从服务器加载完成";
			return false;
		}
		try {
			String source = editor.getValue();
			OpenDocument document = currentDocument;
			if (document.resource) {
				LuaResourcePackWorkspace.saveText(document.name, source);
				document.savedSource = source;
				lastSavedSource = source;
				snapshotRecovery();
				status = "已保存客户端资源包：" + document.name + " · 游戏资源需重新加载才会显示";
				return true;
			}
			status = LuaUiText.text("正在保存到服务器…");
			(document.json ? ScriptManager.saveDatapackFile(document.name, source)
					: ScriptManager.save(document.name, source)).whenComplete((ignored, failure) -> minecraft.execute(() -> {
				if (failure != null) {
					status = LuaUiText.text("保存失败：") + ScriptManager.message(failure);
					return;
				}
				document.savedSource = source;
				if (currentDocument == document) lastSavedSource = source;
				snapshotRecovery();
				status = "已保存到服务器：" + document.displayName()
						+ (document.json ? " · 保存不会自动重载数据包" : "");
			}));
			return true;
		} catch (Exception error) {
			status = LuaUiText.text("保存失败：") + error.getMessage();
			return false;
		}
	}

	private void toggleRun() {
		if (currentDocument != null && currentDocument.resource) {
			status = "资源包文件不能运行；Ctrl+S 保存后需重新加载资源包";
			return;
		}
		if (currentDocument != null && currentDocument.binary()) {
			status = "二进制预览文件不能运行";
			return;
		}
		if (currentDocument != null && currentDocument.json && !isFunctionDocument() && !isScriptRunning()) {
			status = "JSON 文件不能运行；请先保存，数据包变更需重载";
			return;
		}
		if (runningExecution != null && runningExecution.isRunning()) {
			status = runningExecution.displayName().endsWith(".mcfunction")
					? "原版函数无法中途停止，请等待本次运行结束"
					: "正在停止 " + runningExecution.displayName() + "…";
			runningExecution.stop();
			return;
		}
		if (isFunctionDocument()) runFunction();
		else runScript();
	}

	private void runFunction() {
		if (!sourceLoaded) {
			status = "函数文件尚未从服务器加载完成";
			return;
		}
		ScriptManager.ScriptExecution active = ScriptManager.activeExecution();
		if (active != null) {
			if (active.name().equals(scriptName)) {
				attachExecution(active, true);
				status = "已恢复后台运行的 " + active.displayName();
			} else status = "已有文件在后台运行：" + active.displayName();
			return;
		}
		outputLines.clear();
		outputScrollLines = 0;
		outputVisible = true;
		ensureOutputGeometry();
		status = "正在运行 " + scriptName + "…";
		try {
			OpenDocument document = currentDocument;
			String source = editor.getValue();
			runningExecution = ScriptManager.startFunction(scriptName, source,
					line -> { }, result -> { });
			trackRunSave(runningExecution, document, source);
			attachExecution(runningExecution, false);
			updateRunControls();
		} catch (Exception error) {
			status = "运行失败：" + error.getMessage();
			appendOutput("✗ " + error.getMessage());
			runningExecution = null;
			updateRunControls();
		}
	}

	private void runScript() {
		if (currentDocument != null && currentDocument.json) {
			status = "数据包文件不能作为 Lua 运行";
			return;
		}
		if (currentDocument == null) {
			status = "请先在文件窗口新建或读取一个脚本";
			return;
		}
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
				status = "已有文件在后台运行：" + active.displayName();
			}
			return;
		}
		outputLines.clear();
		outputScrollLines = 0;
		outputVisible = true;
		ensureOutputGeometry();
		status = "正在运行 " + scriptName + ".lua…";
		try {
			String source = editor.getValue();
			runningExecution = nativeOwner != null && !nativeDebugRun
					? ScriptManager.startNormal(scriptName, source, line -> { }, result -> { })
					: ScriptManager.start(scriptName, source, line -> { }, result -> { });
			trackRunSave(runningExecution, currentDocument, source);
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
		status = "已恢复后台运行的 " + active.displayName() + " 及其实时日志";
	}

	private void restoreLastRun() {
		ScriptManager.ScriptExecution latest = ScriptManager.lastExecution();
		if (latest == null) return;
		if (latest != outputExecution) attachExecution(latest, true);
		else {
			// removed() cancels pending/in-flight analysis; replay state through the per-run receipt.
			observedExecutionResult = null;
			observedAiCallbackError = 0;
			outputVisible = true;
			activateDockedTool(ToolWindow.OUTPUT);
			ensureOutputGeometry();
		}
		syncExecutionState();
		if (latest.isRunning()) status = "已恢复后台运行的 " + latest.displayName() + " 及其实时日志";
		else status = "已恢复上次运行日志：" + latest.displayName()
				+ (latest.result() != null && latest.result().error() != null ? "（运行报错）" : "（已结束）");
	}

	private void attachExecution(ScriptManager.ScriptExecution execution, boolean animateOpen) {
		if (outputExecution != execution) {
			ai.cancel(false); // Do not send a previous run's queued error or append its reply to the new log.
			aiRuntimeProblem = null;
		}
		aiRunSource = new LuaAiAgent.Problem(execution.displayName().endsWith(".mcfunction")
				? "datapack/" + execution.name() : "lua/" + execution.name() + ".lua", execution.originalSource(), "", List.of());
		observedAiCallbackError = 0;
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
		var callbackError = outputExecution.reportedError();
		if (callbackError != null && callbackError.sequence() != observedAiCallbackError && aiRunSource != null) {
			observedAiCallbackError = callbackError.sequence();
			aiRuntimeProblem = new LuaAiAgent.Problem(aiRunSource.path(), aiRunSource.source(), callbackError.message(), LuaAiController.runtimeLines(callbackError.message()));
			ai.runtimeError(aiRuntimeProblem);
		}
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
		else status = outputExecution.displayName().endsWith(".mcfunction")
				? "函数执行完成" : "脚本执行完成";
		if (result.error() != null && aiRunSource != null) {
			aiRuntimeProblem = new LuaAiAgent.Problem(aiRunSource.path(), aiRunSource.source(), result.error(), LuaAiController.runtimeLines(result.error()));
			ai.runtimeError(aiRuntimeProblem);
		}
	}

	private void closeOutputConsole() {
		outputSelecting = false; outputKeyboardFocused = false;
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

	private static String aiPath(OpenDocument document) {
		return document.resource ? "resourcepacks/" + document.name : document.json ? "datapack/" + document.name : "lua/" + document.name + ".lua";
	}

	private LuaAiAgent.Problem currentAiProblem() {
		if (currentDocument == null || currentDocument.binary() || !sourceLoaded) return null;
		String path = aiPath(currentDocument), source = editor.getValue();
		if (aiProblem != null && aiProblem.path().equals(path) && aiProblem.source().equals(source)) return aiProblem;
		if (aiRuntimeProblem != null && aiRuntimeProblem.path().equals(path) && aiRuntimeProblem.source().equals(source)) return aiRuntimeProblem;
		return null;
	}

	private void appendAiOutput(String text) {
		if (outputExecution != null) { outputExecution.appendIdeMessage(text); syncExecutionState(); }
		else appendOutput(text);
		outputVisible = true; activateDockedTool(ToolWindow.OUTPUT); ensureOutputGeometry();
	}

	private LuaAiAgent.Workspace aiWorkspace() {
		// Capture unsaved buffers and docs on the client thread. The worker sees no mutable IDE collections.
		Map<String, String> open = new LinkedHashMap<>(), docs = new HashMap<>();
		for (OpenDocument document : openDocuments.values()) if (document.loaded && !document.binary())
			open.put(aiPath(document), document == currentDocument ? editor.getValue() : document.source);
		for (String symbol : LuaApiDocumentation.symbols()) {
			var entry = LuaApiDocumentation.find(symbol);
			if (entry != null) docs.put(symbol, entry.signature() + "\n作用：" + entry.rawSummary() + "\n返回：" + entry.rawReturns() + "\n" + entry.rawDetails()
					+ "\n参数：" + entry.parameterDescriptions() + "\n" + LuaApiDocumentation.usageTemplate(symbol));
		}
		var connection = minecraft.getConnection();
		return new LuaAiAgent.Workspace() {
			private <T> CompletableFuture<T> remote(java.util.function.Supplier<CompletableFuture<T>> operation) {
				CompletableFuture<T> result = new CompletableFuture<>();
				minecraft.execute(() -> {
					if (connection == null || minecraft.getConnection() != connection) { result.completeExceptionally(new IllegalStateException("连接已改变")); return; }
					try { operation.get().whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); else result.complete(value); }); }
					catch (Exception error) { result.completeExceptionally(error); }
				}); return result;
			}
			public CompletableFuture<List<String>> list() {
				var lua = remote(ScriptManager::listScripts).exceptionally(error -> List.of());
				var data = remote(ScriptManager::listDatapackTree).exceptionally(error -> List.of());
				return lua.thenCombine(data, (scripts, entries) -> {
					List<String> paths = new ArrayList<>(open.keySet());
					scripts.forEach(name -> paths.add("lua/" + name + ".lua"));
					entries.stream().filter(entry -> !entry.directory()).forEach(entry -> paths.add("datapack/" + entry.path()));
					return paths;
				}).thenApplyAsync(paths -> {
					try { LuaResourcePackWorkspace.list().stream().filter(entry -> !entry.directory()).forEach(entry -> paths.add("resourcepacks/" + entry.path())); }
					catch (Exception ignored) { }
					return paths.stream().filter(LuaAiAgent::textPath).distinct().sorted().limit(4000).toList();
				});
			}
			public CompletableFuture<String> read(String path) {
				if (open.containsKey(path)) return CompletableFuture.completedFuture(open.get(path));
				if (!LuaAiAgent.textPath(path)) return CompletableFuture.failedFuture(new IllegalArgumentException("不允许的文件路径"));
				if (path.startsWith("lua/")) return remote(() -> ScriptManager.read(path.substring(4, path.length() - 4)));
				if (path.startsWith("datapack/")) return remote(() -> ScriptManager.readDatapackFile(path.substring(9)));
				return CompletableFuture.supplyAsync(() -> {
					try { return LuaResourcePackWorkspace.readText(path.substring(14)); }
					catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
				});
			}
			public String api(String symbol) { return docs.getOrDefault(symbol, "当前没有这个 API，请不要编造调用方式"); }
		};
	}

	record AiRectangle(int left, int top, int right, int bottom, LuaAiAgent.Mark mark) { }
	List<AiRectangle> aiRectangles(MultilineTextField field) {
		if (currentDocument == null) return List.of();
		String source = field.value(); List<LuaAiAgent.Mark> marks = new ArrayList<>(ai.marks(aiPath(currentDocument), source));
		if (source.equals(currentDocument.mcpSource)) marks.addAll(currentDocument.mcpMarks);
		if (marks.isEmpty()) return List.of();
		List<AiRectangle> rectangles = new ArrayList<>(); int row = 0, rowHeight = scaledCodeLineHeight(), horizontal = LuaEditorViewport.offset(editor);
		for (Object rawView : field.iterateLines()) {
			var view = (StringViewAccessor) rawView;
			int top = editor.getY() + 4 + row++ * rowHeight - (int) editor.scrollAmount();
			if (top + rowHeight < editor.getY()) continue;
			if (top >= editor.getBottom()) break;
			int begin = view.mcLuaIde$getBeginIndex(), end = view.mcLuaIde$getEndIndex();
			for (var mark : marks) {
				int start = Math.max(begin, mark.start()), finish = Math.min(end, mark.end()); if (start >= finish) continue;
				int left = editor.getX() + 4 - horizontal + Math.round(codeFont.width(source.substring(begin, start)) * characterWidthScale());
				int right = left + Math.max(2, Math.round(codeFont.width(source.substring(start, finish)) * characterWidthScale()));
				left = Math.max(editor.getX() + 1, left); right = Math.min(editor.getRight() - 1, right);
				int clippedTop = Math.max(editor.getY() + 1, top), bottom = Math.min(editor.getBottom() - 1, top + rowHeight);
				if (left < right && clippedTop < bottom) rectangles.add(new AiRectangle(left, clippedTop, right, bottom, mark));
			}
		} return rectangles;
	}
	private void extractAiHighlights(GuiGraphicsExtractor graphics, MultilineTextField field) {
		for (var rect : aiRectangles(field)) {
			int color = switch (rect.mark().severity()) { case "mcp" -> 0x6643B9A5; case "error" -> 0x66E95772; case "warning" -> 0x66D7AA46; default -> 0x665584C7; };
			graphics.fill(rect.left(), rect.top(), rect.right(), rect.bottom(), color);
		}
	}
	private boolean extractAiHover(GuiGraphicsExtractor graphics, MultilineTextField field, int mx, int my) {
		if (leftMouseHeld) return false;
		for (var rect : aiRectangles(field)) if (mx >= rect.left() && mx < rect.right() && my >= rect.top() && my < rect.bottom()) {
			hoveredAiMark = rect.mark();
			boolean mcp = rect.mark().severity().equals("mcp");
			List<Component> lines = new ArrayList<>(); lines.add(Component.literal(mcp ? "MCP 修改" : "AI 标注 · " + switch (rect.mark().severity()) { case "error" -> LuaUiText.text("错误"); case "warning" -> LuaUiText.text("警告"); default -> "说明"; }).withColor(mcp ? 0x68DBC6 : 0x91BFFF));
			String text = rect.mark().message(); int maxWidth = Math.max(80, Math.min(380, width - 30));
			for (String paragraph : text.split("\\R", -1)) while (!paragraph.isEmpty() && lines.size() < 20) {
				String part = font.plainSubstrByWidth(paragraph, maxWidth); if (part.isEmpty()) part = paragraph.substring(0, 1);
				lines.add(Component.literal(part)); paragraph = paragraph.substring(part.length());
			}
			lines.add(Component.literal(mcp ? "悬浮时按 Esc 清除全部 MCP 修改标记" : "悬浮时按 Esc 清除全部标注").withColor(0xA5B4C7));
			graphics.setComponentTooltipForNextFrame(font, lines, mx, my); return true;
		} return false;
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
		LuaEditorBackground.render(graphics, minecraft, personalization,
				editor.getX(), editor.getY(), editor.getRight(), editor.getBottom());
		graphics.fill(editorOuterX, editor.getY(), editor.getX() - 1, editor.getBottom(), lineNumberBackground());
		graphics.fill(editor.getX() - 1, editor.getY() + 1, editor.getX(), editor.getBottom() - 1, 0xFF4A515C);
		graphics.outline(editorOuterX, editor.getY(), editor.getRight() - editorOuterX,
				editor.getHeight(), 0xFF555555);
		int infoHalf = Math.max(80, width / 2);
		renderDocumentTabs(graphics, mouseX, mouseY);
		int syntaxWidth = Math.max(70, Math.min(infoHalf - 20, font.width(syntaxMessage) + 8));
		graphics.text(font, font.plainSubstrByWidth(syntaxMessage, syntaxWidth), 12, 55, syntaxColor);
		String visibleDetail = font.plainSubstrByWidth(validationDetail,
				Math.max(40, width - syntaxWidth - 32));
		graphics.text(font, visibleDetail, syntaxWidth + 20, 55, syntaxColor);
		MultilineTextField field = textField();
		boolean binaryPreview = currentDocument != null && currentDocument.binary();
		if (binaryPreview) {
			if (currentDocument.pngCanvas != null)
				currentDocument.pngCanvas.render(graphics, font,
						editorOuterX, editor.getY(), editor.getRight(), editor.getBottom(),
						mouseX, mouseY, !pointInsideFloatingTool(mouseX, mouseY));
			else if (currentDocument.preview != null)
				currentDocument.preview.render(graphics, font, minecraft,
						editorOuterX, editor.getY(), editor.getRight(), editor.getBottom());
			else graphics.text(font, sourceLoading ? "正在加载预览…" : status,
						editorOuterX + 12, editor.getY() + 16, 0xFFAAC8CD);
		} else {
			extractPausedLine(graphics, field);
			extractAiHighlights(graphics, field);
			LuaEditorViewport.extractOccurrences(graphics, editor);
			extractErrorHighlights(graphics, field);
			extractSearchHighlights(graphics, field);
		}
		extractToolWindows(graphics, mouseX, mouseY, true);
		extractDockTabs(graphics, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (!binaryPreview) LuaEditorViewport.extractHorizontalScrollbar(graphics, editor, mouseX, mouseY);
		if (!binaryPreview) { extractLineNumbers(graphics, field); extractFunctionFoldSummaries(graphics, field); }
		String displayStatus = LuaBreakpointClient.state().paused() ? LuaBreakpointClient.status() : status;
		if (System.nanoTime() < toolNoticeUntil) displayStatus = toolNotice;
		if (pausedSourceChanged()) displayStatus += " · 文件已修改，运行的仍是旧代码；停止并重新运行后生效";
		String visibleStatus = font.plainSubstrByWidth(displayStatus, Math.max(80, width - 125));
		graphics.text(font, visibleStatus, 12, height - 24, status.startsWith("运行失败") ? 0xFFFF7777 : 0xFFAAAAAA);
		int logicalCursorLine = LuaSyntaxHighlighter.logicalLineAt(editor, field.value(), field.cursor());
		String position = binaryPreview ? currentDocument.pngCanvas != null ? "PNG 画布" : LuaUiText.text("只读预览")
				: "Ln " + logicalCursorLine + ", Col " + currentColumn(field);
		graphics.text(font, position, width - font.width(position) - 12, height - 24, 0xFF888888);
		boolean suggestionCovered = !suggestions.isEmpty() && suggestionPopupContains(mouseX, mouseY);
		hoveredAiMark = null;
		if (!editorContextMenu.isOpen() && !binaryPreview && !pointInsideFloatingTool(mouseX, mouseY)
				&& !pointInsideSearch(mouseX, mouseY)
				&& !menuWidgetAt(mouseX, mouseY)
				&& !suggestionCovered) {
			if (!extractAiHover(graphics, field, mouseX, mouseY)) extractApiHover(graphics, field, mouseX, mouseY);
		}
		if (!binaryPreview && !editorContextMenu.isOpen()) extractSuggestions(graphics, field, mouseX, mouseY);
		extractDockPreview(graphics, mouseX, mouseY);
		extractDockDividers(graphics, mouseX, mouseY);
		extractToolWindows(graphics, mouseX, mouseY, false);
		extractSearch(graphics, mouseX, mouseY, partialTick);
		extractFloatingSettingsPanels(graphics, mouseX, mouseY, partialTick);
		if (fileOverlay != null && toolCanInteract(ToolWindow.FILES)) fileOverlay.renderPopup(graphics, mouseX, mouseY);
		if (datapackOverlay != null && toolCanInteract(ToolWindow.DATAPACK_FILES)) datapackOverlay.renderPopup(graphics, mouseX, mouseY);
		if (resourceDirectoryOverlay != null && toolCanInteract(ToolWindow.RESOURCE_FILES))
			resourceDirectoryOverlay.renderDirectoryPopup(graphics, mouseX, mouseY);
		extractMenusOnTop(graphics, mouseX, mouseY, partialTick);
		if (insertOverlay != null) {
			boolean previewEnabled = toolCanInteract(ToolWindow.INSERT) && minecraft.getOverlay() == null
					&& !recoveryPrompt && pendingCloseAction == null && activeMenu.isEmpty() && !editorContextMenu.isOpen();
			insertOverlay.updateSoundPreview(mouseX, mouseY, previewEnabled, !insertPreviewCovered(mouseX, mouseY));
		}
		if (editorContextMenu.isOpen()) {
			updateEditorContextAvailability();
			editorContextMenu.render(graphics, font, mouseX, mouseY);
		}
		if (pendingCloseAction != null) renderCloseDialog(graphics, mouseX, mouseY);
		if (recoveryPrompt) renderRecoveryPrompt(graphics, mouseX, mouseY);
	}


	private boolean insertPreviewCovered(double x, double y) {
		if (pointInsideSearch(x, y) || settingsPanelAt(x, y) != null || extensionWindowAt(x, y) != null) return true;
		if (fileOverlay != null && fileOverlay.contextContains(x, y)) return true;
		if (datapackOverlay != null && datapackOverlay.contextContains(x, y)) return true;
		if (resourceDirectoryOverlay != null && resourceDirectoryOverlay.directoryContextContains(x, y)) return true;
		if (insertOverlay.dockSide().docked()) return pointInsideFloatingTool(x, y);
		if (manualOnTop && !manualDock.docked() && manualProgress > .01F && pointInsideManual(x, y)) return true;
		return apiDocsOverlay != null && !apiDocsOverlay.dockSide().docked() && apiDocsOverlay.contains(x, y)
				|| textJsonOverlay != null && !textJsonOverlay.dockSide().docked() && textJsonOverlay.contains(x, y)
				|| minecraftUiEditorOverlay != null && !minecraftUiEditorOverlay.dockSide().docked() && minecraftUiEditorOverlay.contains(x, y)
				|| componentEditorOverlay != null && !componentEditorOverlay.dockSide().docked() && componentEditorOverlay.contains(x, y)
				|| fileOverlay != null && !fileOverlay.dockSide().docked() && fileOverlay.contains(x, y)
				|| datapackOverlay != null && !datapackOverlay.dockSide().docked() && datapackOverlay.contains(x, y)
				|| datapackJsonEditorOverlay != null && !datapackJsonEditorOverlay.dockSide().docked() && datapackJsonEditorOverlay.contains(x, y)
				|| resourceDirectoryOverlay != null && !resourceDirectoryOverlay.dockSide().docked() && resourceDirectoryOverlay.contains(x, y)
				|| resourceJsonOverlay != null && !resourceJsonOverlay.dockSide().docked() && resourceJsonOverlay.contains(x, y)
				|| resourcePngOverlay != null && !resourcePngOverlay.dockSide().docked() && resourcePngOverlay.contains(x, y);
	}

	private void initializeExtensionWindows() {
		Properties saved = readExtensionLayout();
		List<McLuaIdeWindowApi.Definition> definitions = new ArrayList<>(McLuaIdeWindowApi.definitions());
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "color_picker")
				.title(Component.literal(LuaUiText.text("取色"))).size(480, 430).minimumSize(220, 230)
				.content(context -> new LuaColorWindow(context, this::insertPickedColor)).build());
		// Reuse the host-managed frame, without exposing a built-in tool as a third-party registration.
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "breakpoint_debugger")
				.title(Component.literal(LuaUiText.text("断点调试"))).size(300, 220).minimumSize(190, 160)
				.content(context -> new LuaBreakpointWindow(context.font(), this::performDebuggerAction,
						this::openDebuggerLocation, this::isBreakpointDocument, this::pausedSourceChanged)).build());
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "server_resource_pack")
				.title(Component.literal(LuaUiText.text("服务器资源包"))).size(500, 340).minimumSize(280, 240)
				.content(LuaServerPackWindow::new).build());
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "mcp_client")
				.title(Component.literal(LuaUiText.text("MCP 服务器 · 客户端"))).size(530, 380).minimumSize(280, 180)
				.content(context -> new LuaMcpWindow(context, false)).build());
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "mcp_server")
				.title(Component.literal(LuaUiText.text("MCP 服务器 · 服务端"))).size(530, 380).minimumSize(280, 180)
				.content(context -> new LuaMcpWindow(context, true)).build());
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "ai_tools")
				.title(Component.literal(LuaUiText.text("AI工具"))).size(550, 430).minimumSize(280, 180)
				.content(context -> new LuaAiWindow(context, ai)).build());
		definitions.add(McLuaIdeWindowApi.window("mc_lua_ide", "file_recovery")
				.title(Component.literal(LuaUiText.text("文件恢复"))).size(460, 300).minimumSize(250, 160)
				.content(context -> new LuaFileRecoveryWindow(context, fileRecovery, this::requestRecovery)).build());
		for (McLuaIdeWindowApi.Definition definition : definitions) {
			ToolWindow tool = ToolWindow.extension(definition.id());
			LuaExtensionWindow window = extensionWindows.get(tool);
			if (window == null) {
				window = new LuaExtensionWindow(font, definition, new McLuaIdeWindowApi.Context() {
					public net.minecraft.client.Minecraft minecraft() { return minecraft; }
					public Font font() { return font; }
					public String documentName() { return currentDocument == null ? "" : currentDocument.name; }
					public String source() { return editor == null ? "" : editor.getValue(); }
					public String selectedText() {
						if (editor == null) return "";
						MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) textField();
						int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
						int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
						return source().substring(start, end);
					}
					public boolean insertText(String text) {
						if (recoveryPrompt || pendingCloseAction != null || text == null || !sourceLoaded || currentDocument == null || currentDocument.binary()) return false;
						textField().insertText(text); return true;
					}
					public void copyText(String text) { minecraft.keyboardHandler.setClipboard(text == null ? "" : text); }
					public void close() { setExtensionWindowOpen(definition.id(), false); }
				});
				extensionWindows.put(tool, window);
				window.resize(width, height);
				window.loadLayout(saved);
			} else window.resize(width, height);
		}
		rememberedExtensionLayout = extensionLayoutSnapshot();
	}

	private Properties readExtensionLayout() {
		Properties saved = new Properties();
		if (Files.isRegularFile(EXTENSION_LAYOUT_FILE)) {
			try (var reader = Files.newBufferedReader(EXTENSION_LAYOUT_FILE, StandardCharsets.UTF_8)) { saved.load(reader); }
			catch (Exception error) { McLuaIdeMod.LOGGER.warn("Could not read extension window layout", error); }
		}
		return saved;
	}

	private void saveExtensionLayout() {
		// Preserve layouts belonging to temporarily uninstalled mods.
		Properties saved = readExtensionLayout();
		saved.putAll(rememberedExtensionLayout);
		Path temporary = EXTENSION_LAYOUT_FILE.resolveSibling("extension-window-layout.properties.tmp");
		try {
			Files.createDirectories(EXTENSION_LAYOUT_FILE.getParent());
			try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				saved.store(writer, "Third-party IDE window layout");
			}
			try { Files.move(temporary, EXTENSION_LAYOUT_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException error) {
				Files.move(temporary, EXTENSION_LAYOUT_FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception error) { McLuaIdeMod.LOGGER.warn("Could not save extension window layout", error); }
	}

	/** Internal bridge for the public client window API. Does not grant server access. */
	public boolean setExtensionWindowOpen(String id, boolean open) {
		LuaExtensionWindow window = extensionWindows.get(ToolWindow.extension(id));
		if (window == null) return false;
		if (open) {
			window.open();
			activateDockedTool(ToolWindow.extension(id));
			focusExtensionWindow(window);
		} else {
			window.close();
			if (focusedExtensionWindow == window) focusExtensionWindow(null);
		}
		syncOverlayState();
		return true;
	}

	private void focusExtensionWindow(LuaExtensionWindow target) {
		focusedExtensionWindow = target;
		extensionWindows.values().forEach(window -> window.focus(window == target));
		if (target == null) return;
		setFocused(null);
		manualKeyboardFocused = false; variableWindowFocused = false; focusedSettingsPanel = null;
		if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
		if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
		if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
		if (datapackOverlay != null) datapackOverlay.releaseKeyboardFocus();
		if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
		if (datapackJsonEditorOverlay != null) datapackJsonEditorOverlay.releaseKeyboardFocus();
		if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
		if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
		if (resourceDirectoryOverlay != null) resourceDirectoryOverlay.releaseKeyboardFocus();
		if (resourceJsonOverlay != null) resourceJsonOverlay.releaseKeyboardFocus();
		if (resourcePngOverlay != null) resourcePngOverlay.releaseKeyboardFocus();
	}

	private LuaExtensionWindow extensionWindowAt(double x, double y) {
		if (focusedExtensionWindow != null && !focusedExtensionWindow.dockSide().docked()
				&& focusedExtensionWindow.hitFrame(x, y)) return focusedExtensionWindow;
		List<LuaExtensionWindow> windows = new ArrayList<>(extensionWindows.values());
		for (int i = windows.size() - 1; i >= 0; i--) {
			LuaExtensionWindow window = windows.get(i);
			if (!window.dockSide().docked() && window.hitFrame(x, y)) return window;
		}
		if (pointInsideFloatingTool(x, y)) return null;
		for (var entry : extensionWindows.entrySet())
			if (entry.getValue().dockSide().docked() && toolCanInteract(entry.getKey())
					&& entry.getValue().contains(x, y)) return entry.getValue();
		return null;
	}

	private void buildExtensionMenu() {
		extensionMenuItems.forEach(this::removeWidget);
		extensionMenuItems.clear();
		List<ToolWindow> windows = new ArrayList<>(extensionWindows.keySet());
		windows.remove(ToolWindow.extension(LuaBreakpointWindow.ID));
		windows.remove(ToolWindow.extension(LuaServerPackWindow.ID));
		windows.remove(ToolWindow.extension(LuaFileRecoveryWindow.ID));
		windows.remove(ToolWindow.extension(LuaMcpWindow.CLIENT_ID));
		windows.remove(ToolWindow.extension(LuaMcpWindow.SERVER_ID));
		windows.remove(ToolWindow.extension(LuaAiWindow.ID));
		windows.remove(ToolWindow.extension(LuaColorWindow.ID));
		int perPage = Math.max(1, Math.min(12, (height - 70) / 22 - 2));
		int pages = Math.max(1, (windows.size() + perPage - 1) / perPage);
		extensionMenuPage = Math.clamp(extensionMenuPage, 0, pages - 1);
		int menuX = Math.max(0, Math.min(240, width - 210)), row = 0;
		for (int index = extensionMenuPage * perPage; index < Math.min(windows.size(), (extensionMenuPage + 1) * perPage); index++) {
			ToolWindow tool = windows.get(index);
			LuaExtensionWindow window = extensionWindows.get(tool);
			String label = font.plainSubstrByWidth(window.title() + " [" + tool.id.split(":")[0] + "]", 188);
			extensionMenuItems.add(menuItem(label, menuX, 30 + row++ * 22, 210, () -> {
				if (window.isOpen() && !revealDockedTool(tool)) setExtensionWindowOpen(tool.id, false);
				else setExtensionWindowOpen(tool.id, true);
			}));
		}
		if (windows.isEmpty()) {
			Button empty = menuItem(LuaUiText.text("未注册第三方窗口"), menuX, 30, 210, () -> { });
			empty.active = false; extensionMenuItems.add(empty);
		}
		if (pages > 1) {
			final int currentPage = extensionMenuPage;
			for (int delta : new int[]{-1, 1}) {
				Button page = Button.builder(Component.literal(delta < 0 ? LuaUiText.text("上一页") : LuaUiText.text("下一页")), ignored -> {
					extensionMenuPage = currentPage + delta;
					buildExtensionMenu();
					extensionMenuItems.forEach(item -> item.visible = true);
				}).bounds(menuX + (delta < 0 ? 0 : 107), 30 + row * 22, 103, 20).build();
				page.active = currentPage + delta >= 0 && currentPage + delta < pages;
				addRenderableWidget(page); extensionMenuItems.add(page);
			}
		}
		extensionMenuItems.forEach(item -> item.visible = false);
	}

	private void toggleBreakpointWindow() {
		ToolWindow tool = ToolWindow.extension(LuaBreakpointWindow.ID);
		LuaExtensionWindow window = extensionWindows.get(tool);
		if (window == null || revealDockedTool(tool)) return;
		setExtensionWindowOpen(tool.id, !window.isOpen());
		hideMenus();
	}

	private void buildViewMenu() {
		viewMenuItems.forEach(this::removeWidget);
		viewMenuItems.clear();
		String[] names = {LuaUiText.text("使用手册"), LuaUiText.text("插入"), LuaUiText.text("API 文档"), LuaUiText.text("运行日志"), LuaUiText.text("断点调试"), LuaUiText.text("变量显示"),
				LuaUiText.text("数据组件编辑器"), LuaUiText.text("数据包 JSON 编辑器"), LuaUiText.text("资源包 JSON 编辑器"), LuaUiText.text("文本 JSON 编辑器"),
				LuaUiText.text("Minecraft UI 编辑器"), LuaUiText.text("图片工具"), LuaUiText.text("取色")};
		Runnable[] actions = {this::toggleManual, this::toggleInsertOverlay, this::toggleApiDocs,
				this::toggleOutputConsole, this::toggleBreakpointWindow, this::toggleVariableOverlay,
				this::toggleComponentEditor, this::toggleDatapackJsonEditor, this::toggleResourceJson,
				this::toggleTextJsonEditor, this::toggleMinecraftUiEditor, this::toggleResourcePng,
				() -> setExtensionWindowOpen(LuaColorWindow.ID, true)};
		int perPage = Math.max(1, Math.min(names.length, (height - 38) / 22 - 1));
		int pages = (names.length + perPage - 1) / perPage;
		viewMenuPage = Math.clamp(viewMenuPage, 0, pages - 1);
		int menuX = Math.max(0, Math.min(240, width - 150)), row = 0;
		for (int index = viewMenuPage * perPage; index < Math.min(names.length, (viewMenuPage + 1) * perPage); index++)
			viewMenuItems.add(menuItem(names[index], menuX, 30 + row++ * 22, 150, actions[index]));
		if (pages > 1) {
			for (int delta : new int[]{-1, 1}) {
				Button page = Button.builder(Component.literal(delta < 0 ? LuaUiText.text("上一页") : LuaUiText.text("下一页")), ignored -> {
					viewMenuPage += delta; buildViewMenu();
					viewMenuItems.forEach(item -> item.visible = true);
				}).bounds(menuX + (delta < 0 ? 0 : 77), 30 + row * 22, 73, 20).build();
				page.active = viewMenuPage + delta >= 0 && viewMenuPage + delta < pages;
			addRenderableWidget(page); viewMenuItems.add(page);
			}
		}
		viewMenuItems.forEach(item -> item.visible = false);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (recoveryPrompt || pendingCloseAction != null) return true;
		if (activeMenu.isEmpty() && focusedExtensionWindow != null && focusedExtensionWindow.hasFocus()) {
			focusedExtensionWindow.keyReleased(event); return true;
		}
		return super.keyReleased(event);
	}

	private void toggleVariableOverlay() {
		if (revealDockedTool(ToolWindow.VARIABLES)) return;
		if (variableOverlay.isOpen()) {
			variableOverlay.close();
			variableWindowFocused = false;
		}
		else {
			variableOverlay.open();
			variableWindowFocused = true;
			activateDockedTool(ToolWindow.VARIABLES);
		}
		hideMenus();
		applyDockLayout();
	}

	private void extractFloatingSettingsPanels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (gitPanel != null && frontSettingsPanel != gitPanel && !gitPanel.dockSide().docked())
			gitPanel.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (personalizationPanel != null && frontSettingsPanel != personalizationPanel && !personalizationPanel.dockSide().docked())
			personalizationPanel.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (exportPanel != null && frontSettingsPanel != exportPanel && !exportPanel.dockSide().docked())
			exportPanel.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (frontSettingsPanel == gitPanel && gitPanel != null && !gitPanel.dockSide().docked())
			gitPanel.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (frontSettingsPanel == personalizationPanel && personalizationPanel != null && !personalizationPanel.dockSide().docked())
			personalizationPanel.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (frontSettingsPanel == exportPanel && exportPanel != null && !exportPanel.dockSide().docked())
			exportPanel.extractRenderState(graphics, mouseX, mouseY, partialTick);
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
		if (variableOverlay != null && !variableOverlay.dockSide().docked())
			variableOverlay.render(graphics, mouseX, mouseY);
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
		if (minecraftUiEditorOverlay != null && !minecraftUiEditorOverlay.dockSide().docked()) {
			minecraftUiEditorOverlay.render(graphics, mouseX, mouseY);
		}
		if (componentEditorOverlay != null && !componentEditorOverlay.dockSide().docked()) {
			componentEditorOverlay.render(graphics, mouseX, mouseY);
		}
		if (fileOverlay != null && !fileOverlay.dockSide().docked()) {
			fileOverlay.render(graphics, mouseX, mouseY);
		}
		if (datapackOverlay != null && !datapackOverlay.dockSide().docked())
			datapackOverlay.render(graphics, mouseX, mouseY);
		if (resourceDirectoryOverlay != null && !resourceDirectoryOverlay.dockSide().docked())
			resourceDirectoryOverlay.render(graphics, mouseX, mouseY);
		if (resourceJsonOverlay != null && !resourceJsonOverlay.dockSide().docked())
			resourceJsonOverlay.render(graphics, mouseX, mouseY);
		if (resourcePngOverlay != null && !resourcePngOverlay.dockSide().docked())
			resourcePngOverlay.render(graphics, mouseX, mouseY);
		if (datapackJsonEditorOverlay != null && !datapackJsonEditorOverlay.dockSide().docked())
			datapackJsonEditorOverlay.render(graphics, mouseX, mouseY);
		for (LuaExtensionWindow window : extensionWindows.values())
			if (!window.dockSide().docked() && window != focusedExtensionWindow) window.render(graphics, mouseX, mouseY);
		if (focusedExtensionWindow != null && !focusedExtensionWindow.dockSide().docked())
			focusedExtensionWindow.render(graphics, mouseX, mouseY);
	}

	private void extractToolWindow(ToolWindow tool, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).render(graphics, mouseX, mouseY);
			case MANUAL -> extractManual(graphics, mouseX, mouseY);
			case INSERT -> insertOverlay.render(graphics, mouseX, mouseY);
			case API_DOCS -> apiDocsOverlay.render(graphics, mouseX, mouseY);
			case COMPONENT_EDITOR -> componentEditorOverlay.render(graphics, mouseX, mouseY);
			case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay.render(graphics, mouseX, mouseY);
			case TEXT_JSON -> textJsonOverlay.render(graphics, mouseX, mouseY);
			case MINECRAFT_UI -> minecraftUiEditorOverlay.render(graphics, mouseX, mouseY);
			case FILES -> fileOverlay.render(graphics, mouseX, mouseY);
			case DATAPACK_FILES -> datapackOverlay.render(graphics, mouseX, mouseY);
			case RESOURCE_FILES -> resourceDirectoryOverlay.render(graphics, mouseX, mouseY);
			case RESOURCE_JSON -> resourceJsonOverlay.render(graphics, mouseX, mouseY);
			case RESOURCE_PNG -> resourcePngOverlay.render(graphics, mouseX, mouseY);
			case OUTPUT -> {
				if (isOutputRendered()) extractOutputConsole(graphics, mouseX, mouseY);
			}
			case VARIABLES -> variableOverlay.render(graphics, mouseX, mouseY);
			case GIT -> {
				if (gitPanel != null) gitPanel.extractRenderState(graphics, mouseX, mouseY, 0);
			}
			case PERSONALIZATION -> {
				if (personalizationPanel != null)
					personalizationPanel.extractRenderState(graphics, mouseX, mouseY, 0);
			}
			case EXPORT -> {
				if (exportPanel != null) exportPanel.extractRenderState(graphics, mouseX, mouseY, 0);
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
			boolean dragging = dockTabDragging && pressedDockTabSide == side && tools.contains(pressedDockTab);
			for (int index = 0; index < tools.size(); index++) {
				ToolWindow tool = tools.get(index);
				float position = Math.clamp(animateDockTabPosition(side, tool, index), 0.0F, tools.size() - 1.0F);
				int left = region.x() + Math.round(region.width() * position / tools.size());
				int right = Math.min(region.right(), left + region.width() * (index + 1) / tools.size() - region.width() * index / tools.size());
				boolean hovered = !dragging && mouseX >= left && mouseX < right
						&& mouseY >= region.y() && mouseY < region.y() + DOCK_TAB_HEIGHT;
				boolean selected = tool == active;
				if (dragging && tool == pressedDockTab) {
					int slotLeft = region.x() + region.width() * index / tools.size();
					int slotRight = region.x() + region.width() * (index + 1) / tools.size();
					graphics.fill(slotLeft, region.y(), slotRight, region.y() + DOCK_TAB_HEIGHT, 0xFF0E1925);
					graphics.fill(slotLeft, region.y(), slotRight, region.y() + 2, 0xFF8DCDFF);
					continue;
				}
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
			if (dragging) {
				int ghostWidth = Math.max(1, region.width() / tools.size());
				int ghostLeft = Math.clamp((int) Math.round(dockTabDragX - dockTabDragOffsetX), region.x(), region.right() - ghostWidth);
				graphics.fill(ghostLeft, region.y() + 1, ghostLeft + ghostWidth, region.y() + DOCK_TAB_HEIGHT - 1, 0xF13C5D7C);
				graphics.outline(ghostLeft, region.y() + 1, ghostWidth, DOCK_TAB_HEIGHT - 2, 0xFF9BD5FF);
				graphics.centeredText(font, font.plainSubstrByWidth(dockTabLabel(pressedDockTab), Math.max(1, ghostWidth - 10)),
						ghostLeft + ghostWidth / 2, region.y() + 7, 0xFFFFFFFF);
			}
		}
	}

	private float animateDockTabPosition(DockSide side, ToolWindow tool, int target) {
		Map<ToolWindow, Float> positions = animatedDockTabPositions.computeIfAbsent(side, ignored -> new LinkedHashMap<>());
		float current = positions.getOrDefault(tool, (float) target);
		current += (target - current) * 0.32F;
		if (Math.abs(target - current) < 0.015F) current = target;
		positions.put(tool, current);
		return current;
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
		return switch (tool.kind) {
			case EXTENSION -> extensionWindows.get(tool).title();
			case MANUAL -> LuaUiText.text("手册");
			case INSERT -> LuaUiText.text("插入");
			case API_DOCS -> "API";
			case COMPONENT_EDITOR -> LuaUiText.text("组件", "Components");
			case DATAPACK_JSON_EDITOR -> LuaUiText.text("数据包", "Datapack");
			case TEXT_JSON -> LuaUiText.text("文本");
			case MINECRAFT_UI -> "UI";
			case FILES -> "Lua";
			case DATAPACK_FILES -> "datapack";
			case RESOURCE_FILES -> "resourcepacks";
			case RESOURCE_JSON -> LuaUiText.text("资源 JSON", "Resource JSON");
			case RESOURCE_PNG -> LuaUiText.text("图片工具");
			case OUTPUT -> LuaUiText.text("日志");
			case VARIABLES -> LuaUiText.text("变量");
			case GIT -> "Git";
			case PERSONALIZATION -> LuaUiText.text("个性化");
			case EXPORT -> LuaUiText.text("导出", "Export");
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
			if (!layoutLocked) {
				pressedDockTab = tool;
				pressedDockTabSide = side;
				dockTabPressX = dockTabDragX = mouseX;
				dockTabPressY = mouseY;
				dockTabDragOffsetX = mouseX - (region.x() + region.width() * index / tools.size());
				dockTabDragging = false;
				setDragging(true);
			}
			setSelectedDockTool(side, tool);
			manualKeyboardFocused = tool == ToolWindow.MANUAL;
			variableWindowFocused = tool == ToolWindow.VARIABLES;
			focusExtensionWindow(tool.kind == ToolWindow.Kind.EXTENSION ? extensionWindows.get(tool) : null);
			if (manualKeyboardFocused || variableWindowFocused) setFocused(null);
			focusedSettingsPanel = tool == ToolWindow.GIT ? gitPanel
					: tool == ToolWindow.PERSONALIZATION ? personalizationPanel
					: tool == ToolWindow.EXPORT ? exportPanel : null;
			if (fileOverlay != null && tool != ToolWindow.FILES) fileOverlay.releaseKeyboardFocus();
			if (datapackOverlay != null && tool != ToolWindow.DATAPACK_FILES)
				datapackOverlay.releaseKeyboardFocus();
			if (apiDocsOverlay != null && tool != ToolWindow.API_DOCS) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null && tool != ToolWindow.INSERT) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null && tool != ToolWindow.COMPONENT_EDITOR) {
				componentEditorOverlay.releaseKeyboardFocus();
			}
			if (datapackJsonEditorOverlay != null && tool != ToolWindow.DATAPACK_JSON_EDITOR)
				datapackJsonEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null && tool != ToolWindow.TEXT_JSON) textJsonOverlay.releaseKeyboardFocus();
			if (minecraftUiEditorOverlay != null && tool != ToolWindow.MINECRAFT_UI) {
				minecraftUiEditorOverlay.releaseKeyboardFocus();
			}
			if (tool == ToolWindow.INSERT && insertOverlay != null) insertOverlay.requestKeyboardFocus();
			if (tool == ToolWindow.COMPONENT_EDITOR && componentEditorOverlay != null) componentEditorOverlay.requestKeyboardFocus();
			if (tool == ToolWindow.DATAPACK_JSON_EDITOR && datapackJsonEditorOverlay != null)
				datapackJsonEditorOverlay.requestKeyboardFocus();
			if (tool == ToolWindow.TEXT_JSON && textJsonOverlay != null) textJsonOverlay.requestKeyboardFocus();
			if (tool == ToolWindow.MINECRAFT_UI && minecraftUiEditorOverlay != null) {
				minecraftUiEditorOverlay.requestKeyboardFocus();
			}
			applyDockLayout();
			status = "已切换到" + dockTabLabel(tool) + "窗口";
			return true;
		}
		return false;
	}

	private void updateDockTabDrag(double mouseX, double mouseY) {
		if (layoutLocked || pressedDockTab == null || toolDock(pressedDockTab) != pressedDockTabSide) return;
		if (!dockTabDragging && Math.hypot(mouseX - dockTabPressX, mouseY - dockTabPressY) < 4.0) return;
		dockTabDragging = true;
		dockTabDragX = mouseX;
		DockRegion region = dockRegion(pressedDockTabSide);
		List<ToolWindow> tools = dockedToolsAt(pressedDockTabSide);
		if (region == null || tools.size() < 2 || !tools.contains(pressedDockTab)
				|| mouseY < region.y() - 12 || mouseY >= region.y() + DOCK_TAB_HEIGHT + 12) return;
		int target = Math.clamp((int) ((mouseX - region.x()) * tools.size() / Math.max(1, region.width())), 0, tools.size() - 1);
		if (dockTabOrder.move(pressedDockTabSide, tools.stream().map(ToolWindow::name).toList(), pressedDockTab.name(), target)) {
			status = "已调整" + dockSideLabel(pressedDockTabSide) + "标签顺序 · 松开鼠标保存";
		}
	}

	private void clearDockTabDrag() {
		pressedDockTab = null;
		pressedDockTabSide = DockSide.FLOATING;
		dockTabDragging = false;
		setDragging(false);
	}

	private void extractMenusOnTop(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		for (Button button : topMenuButtons) {
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		for (Button button : visibleMenuButtons()) {
			if (foldingMenuItems.contains(button)) continue;
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		for (Button button : foldingMenuItems) if (button.visible)
			button.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (scaleInput != null && scaleInput.visible) {
			scaleInput.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		if (scaleApplyButton != null && scaleApplyButton.visible) {
			scaleApplyButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	private List<Button> visibleMenuButtons() {
		List<Button> visible = new ArrayList<>();
		for (List<Button> items : List.of(foldingMenuItems, fileMenuItems, toolMenuItems, windowMenuItems, viewMenuItems,
				fontMenuItems, interfaceScaleMenuItems, themeMenuItems, extensionMenuItems)) {
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
		for (var entry : extensionWindows.entrySet()) if (entry.getValue().isDragging()) return entry.getKey();
		if (gitPanel != null && gitPanel.isDraggingPanel()) return ToolWindow.GIT;
		if (personalizationPanel != null && personalizationPanel.isDraggingPanel()) return ToolWindow.PERSONALIZATION;
		if (exportPanel != null && exportPanel.isDraggingPanel()) return ToolWindow.EXPORT;
		if (manualDragging) return ToolWindow.MANUAL;
		if (insertOverlay != null && insertOverlay.isDragging()) return ToolWindow.INSERT;
		if (apiDocsOverlay != null && apiDocsOverlay.isDragging()) return ToolWindow.API_DOCS;
		if (variableOverlay != null && variableOverlay.isDragging()) return ToolWindow.VARIABLES;
		if (componentEditorOverlay != null && componentEditorOverlay.isDragging()) return ToolWindow.COMPONENT_EDITOR;
		if (datapackJsonEditorOverlay != null && datapackJsonEditorOverlay.isDragging()) return ToolWindow.DATAPACK_JSON_EDITOR;
		if (textJsonOverlay != null && textJsonOverlay.isDragging()) return ToolWindow.TEXT_JSON;
		if (minecraftUiEditorOverlay != null && minecraftUiEditorOverlay.isDragging()) return ToolWindow.MINECRAFT_UI;
		if (fileOverlay != null && fileOverlay.isDragging()) return ToolWindow.FILES;
		if (datapackOverlay != null && datapackOverlay.isDragging()) return ToolWindow.DATAPACK_FILES;
		if (resourceDirectoryOverlay != null && resourceDirectoryOverlay.isDragging()) return ToolWindow.RESOURCE_FILES;
		if (resourceJsonOverlay != null && resourceJsonOverlay.isDragging()) return ToolWindow.RESOURCE_JSON;
		if (resourcePngOverlay != null && resourcePngOverlay.isDragging()) return ToolWindow.RESOURCE_PNG;
		if (outputDragging) return ToolWindow.OUTPUT;
		return null;
	}

	private void extractDockDividers(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int workLeft = 8;
		int workRight = width - 8;
		int workBottom = height - WORKSPACE_BOTTOM_MARGIN;
		int normal = canResizeDock() ? 0xFF3F6F91 : 0xFF59616B;
		int hovered = 0xFF75C7FF;
		int leftExtent = renderedDockExtent(animatedLeftDockExtent);
		int rightExtent = renderedDockExtent(animatedRightDockExtent);
		int bottomExtent = renderedDockExtent(animatedBottomDockExtent);
		DockSide hoveredSide = canResizeDock() ? dockResizeSideAt(mouseX, mouseY) : DockSide.FLOATING;
		if (activeToolAt(DockSide.LEFT) != null) {
			int x = workLeft + leftExtent + 2;
			boolean highlight = canResizeDock() && (activeDockResize == DockSide.LEFT
					|| hoveredSide == DockSide.LEFT);
			graphics.fill(highlight ? x - 1 : x, WORKSPACE_TOP,
					highlight ? x + 2 : x + 1, workBottom, highlight ? hovered : normal);
		}
		if (activeToolAt(DockSide.RIGHT) != null) {
			int x = workRight - rightExtent - 3;
			boolean highlight = canResizeDock() && (activeDockResize == DockSide.RIGHT
					|| hoveredSide == DockSide.RIGHT);
			graphics.fill(highlight ? x - 1 : x, WORKSPACE_TOP,
					highlight ? x + 2 : x + 1, workBottom, highlight ? hovered : normal);
		}
		if (activeToolAt(DockSide.BOTTOM) != null) {
			int y = workBottom - bottomExtent - 3;
			int left = activeToolAt(DockSide.LEFT) == null ? EDITOR_OUTER_X
					: workLeft + leftExtent + DOCK_GAP;
			int right = activeToolAt(DockSide.RIGHT) == null ? width - 12
					: workRight - rightExtent - DOCK_GAP;
			boolean highlight = canResizeDock() && (activeDockResize == DockSide.BOTTOM
					|| hoveredSide == DockSide.BOTTOM);
			graphics.fill(left, highlight ? y - 1 : y, right,
					highlight ? y + 2 : y + 1, highlight ? hovered : normal);
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
				boolean warning = warningLineNumbers.contains(logicalLine);
				if (logicalLine == currentLine) {
					graphics.fill(editorOuterX + 1, y - 1, editor.getX() - 1,
							y + codeLineHeight, error ? 0x553D1017 : currentLineBackground());
				}
				if (error || warning) {
					graphics.fill(editorOuterX + 1, y - 1, editorOuterX + 4,
							y + codeLineHeight, error ? 0xFFFF3F52 : 0xFFE6AD45);
				}
				if (isBreakpointDocument() && breakpointStore.lines(currentDocument.name).contains(logicalLine)) {
					int dotY = y + Math.max(0, (Math.min(20, codeLineHeight) - 9) / 2);
					int color = pausedSourceChanged() ? 0xFF94646A : 0xFFF44D62;
					graphics.fill(editorOuterX + 7, dotY + 2, editorOuterX + 16, dotY + 7, color);
					graphics.fill(editorOuterX + 9, dotY, editorOuterX + 14, dotY + 9, color);
				}
				if (pausedLine() == logicalLine) {
					int arrowY = y + Math.max(0, (Math.min(20, codeLineHeight) - 9) / 2);
					graphics.fill(editorOuterX + 18, arrowY + 3, editorOuterX + 25, arrowY + 6, 0xFFFFD06C);
					for (int row = 0; row < 9; row++) {
						int length = 5 - Math.abs(row - 4);
						graphics.fill(editorOuterX + 24, arrowY + row, editorOuterX + 24 + length, arrowY + row + 1, 0xFFFFD06C);
					}
				}
				drawLineNumber(graphics, Integer.toString(logicalLine), y,
						error ? 0xFFFF5555 : warning ? 0xFFE6AD45 : logicalLine == currentLine ? 0xFF7FC8FF : lineNumberColor());
				var functions = functionModel();
				var fold = functions == null ? null : functions.atLine(logicalLine);
				if (fold != null) {
					int x = editor.getX() - 8, top = y + Math.max(0, (codeLineHeight - 7) / 2);
					graphics.fill(x, top, x + 7, top + 7, 0xFF6B91AC);
					graphics.fill(x + 1, top + 1, x + 6, top + 6, 0xFF202A34);
					graphics.fill(x + 2, top + 3, x + 5, top + 4, 0xFFBBDEEF);
					if (LuaFunctionFolding.state(field).collapsed(fold))
						graphics.fill(x + 3, top + 2, x + 4, top + 5, 0xFFBBDEEF);
				}
			}
			previousLogicalLine = logicalLine;
			visualLine++;
		}
		graphics.disableScissor();
	}

	private LuaFunctionModel functionModel() {
		if (editor == null || isJsonDocument()) return null;
		var state = LuaFunctionFolding.state(textField());
		return state == null ? null : state.model(textField().value());
	}

	private void extractFunctionFoldSummaries(GuiGraphicsExtractor graphics, MultilineTextField field) {
		var model = functionModel();
		if (model == null) return;
		var state = LuaFunctionFolding.state(field);
		int visual = 0, previous = -1;
		graphics.enableScissor(editor.getX() + 4, editor.getY() + 1, editor.getRight() - 4, editor.getBottom() - 1);
		for (Object raw : field.iterateLines()) {
			var view = (StringViewAccessor) raw;
			int begin = view.mcLuaIde$getBeginIndex(), end = view.mcLuaIde$getEndIndex();
			int line = model.lineAt(begin);
			var fold = model.atLine(line);
			int y = editor.getY() + 4 + visual++ * scaledCodeLineHeight() - (int) editor.scrollAmount();
			if (line != previous && fold != null && state.collapsed(fold) && y >= editor.getY() - scaledCodeLineHeight() && y < editor.getBottom()) {
				int x = editor.getX() + 4 - LuaEditorViewport.offset(editor)
						+ Math.round(codeFont.width(model.source.substring(begin, end)) * characterWidthScale());
				graphics.pose().pushMatrix();
				graphics.pose().translate(x, y);
				graphics.pose().scale(characterWidthScale(), codeScale);
				graphics.text(codeFont, "  … " + (fold.endLine() - fold.line()) + " 行"
						+ (fold.displayName().isBlank() ? "" : " · " + fold.displayName()), 0, 0, 0xFF8BA5B8);
				graphics.pose().popMatrix();
			}
			previous = line;
		}
		graphics.disableScissor();
	}

	boolean isBreakpointDocument() {
		return editor != null && currentDocument != null && !currentDocument.json && sourceLoaded;
	}
	void performDebuggerAction(LuaDebugKeyBindings.Action action) {
		if (!action.editorOnly) { LuaBreakpointClient.control(action.command); return; }
		if (!isBreakpointDocument()) { status = "断点只适用于已加载的 Lua 脚本"; return; }
		if (action == LuaDebugKeyBindings.Action.CLEAR_BREAKPOINTS) {
			breakpointStore.clear(currentDocument.name); LuaBreakpointClient.changed(); status = "已清空当前文件的断点";
		} else toggleBreakpoint(LuaSyntaxHighlighter.logicalLineAt(editor, editor.getValue(), textField().cursor()));
	}
	private boolean pausedSourceChanged() {
		var state = LuaBreakpointClient.state();
		return isBreakpointDocument() && state.paused() && state.source().equals(currentDocument.name)
				&& !state.hash().equals(breakpointStore.hash(currentDocument.name));
	}
	private int pausedLine() {
		var state = LuaBreakpointClient.state();
		return isBreakpointDocument() && state.paused() && state.source().equals(currentDocument.name)
				&& !pausedSourceChanged() ? state.line() : -1;
	}
	private void toggleBreakpoint(int line) {
		if (!isBreakpointDocument()) { status = "断点只适用于已加载的 Lua 脚本"; return; }
		try {
			boolean added = breakpointStore.toggle(currentDocument.name, line, editor.getValue());
			LuaBreakpointClient.changed();
			status = (added ? "已添加" : "已移除") + "断点：" + currentDocument.name + ".lua:" + line
					+ " · " + LuaDebugKeyBindings.runHint();
		} catch (IllegalStateException error) { status = error.getMessage(); }
	}
	private int gutterLineAt(double mouseY) {
		String source = textField().value(); int visual = 0;
		for (Object row : textField().iterateLines()) {
			int y = editor.getY() + 4 + visual++ * scaledCodeLineHeight() - (int) editor.scrollAmount();
			if (mouseY >= y && mouseY < y + scaledCodeLineHeight())
				return LuaSyntaxHighlighter.logicalLineAt(editor, source,
						((StringViewAccessor) row).mcLuaIde$getBeginIndex());
		}
		return -1;
	}
	private void extractPausedLine(GuiGraphicsExtractor graphics, MultilineTextField field) {
		int paused = pausedLine(); if (paused < 1) return;
		graphics.enableScissor(editor.getX(), editor.getY() + 1, editor.getRight() - 1, editor.getBottom() - 1);
		int visual = 0;
		for (Object row : field.iterateLines()) {
			int y = editor.getY() + 4 + visual++ * scaledCodeLineHeight() - (int) editor.scrollAmount();
			if (LuaSyntaxHighlighter.logicalLineAt(editor, field.value(), ((StringViewAccessor) row).mcLuaIde$getBeginIndex()) == paused)
				graphics.fill(editor.getX(), y - 1, editor.getRight() - 1, y + scaledCodeLineHeight(), 0x554D3E13);
		}
		graphics.disableScissor();
	}
	private void revealDebuggerStop() {
		var state = LuaBreakpointClient.state();
		if (recoveryPrompt || !state.paused() || state.line() < 1 || pendingCloseAction != null) return;
		String stop = state.id() + ":" + state.sequence();
		if (!stop.equals(observedDebuggerStop)) {
			observedDebuggerStop = stop;
			pendingDebuggerReveal = state.source();
			if (currentDocument == null || currentDocument.json || !currentDocument.name.equals(state.source()))
				openDocument(state.source(), false);
		}
		if (!pendingDebuggerReveal.isEmpty() && isBreakpointDocument() && currentDocument.name.equals(pendingDebuggerReveal)) {
			pendingDebuggerReveal = "";
			if (pausedSourceChanged()) return;
			int start = LuaBreakpointStore.lineStart(editor.getValue(), state.line());
			if (start >= 0) {
				textField().setSelecting(false);
				textField().seekCursor(Whence.ABSOLUTE, start);
				int visual = 0;
				for (Object row : textField().iterateLines()) {
					if (((StringViewAccessor) row).mcLuaIde$getBeginIndex() >= start) break;
					visual++;
				}
				int y = visual * scaledCodeLineHeight();
				if (y < editor.scrollAmount() || y + scaledCodeLineHeight() > editor.scrollAmount() + editor.getHeight() - 8)
					editor.setScrollAmount(Math.max(0, y - editor.getHeight() / 3));
				clearSuggestions();
			}
		}
	}

	private void openDebuggerLocation(String name, int line) {
		if (recoveryPrompt || pendingCloseAction != null || line < 1 || !wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames.isValid(name)) return;
		openDocument(name, false);
		if (currentDocument == null || currentDocument.json || !currentDocument.name.equals(name)) return;
		pendingDebuggerLocation = name;
		pendingDebuggerLine = line;
		revealDebuggerLocation();
	}

	private void revealDebuggerLocation() {
		if (pendingDebuggerLocation.isEmpty() || !isBreakpointDocument()) return;
		if (!currentDocument.name.equals(pendingDebuggerLocation)) { pendingDebuggerLocation = ""; return; }
		pendingDebuggerLocation = "";
		int start = LuaBreakpointStore.lineStart(editor.getValue(), pendingDebuggerLine);
		if (start < 0) { status = "该文件已变化，目标行不存在"; return; }
		textField().setSelecting(false);
		textField().seekCursor(Whence.ABSOLUTE, start);
		int visual = 0;
		for (Object row : textField().iterateLines()) {
			if (((StringViewAccessor) row).mcLuaIde$getBeginIndex() >= start) break;
			visual++;
		}
		editor.setScrollAmount(Math.max(0, visual * scaledCodeLineHeight() - editor.getHeight() / 3));
		clearSuggestions();
		status = "已跳转到 " + currentDocument.name + ".lua:" + pendingDebuggerLine
				+ (pausedSourceChanged() ? " · 当前代码与运行版本不同" : "");
	}

	private void drawLineNumber(GuiGraphicsExtractor graphics, String number, int y, int color) {
		int scaledWidth = Math.round(font.width(number) * codeScale);
		int x = editor.getX() - 12 - scaledWidth;
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
		return Math.max(1, Math.round(codeFont.lineHeight * codeScale * personalization.lineHeight));
	}

	private float characterWidthScale() {
		return codeScale * personalization.cellWidth;
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
				// Errors must remain red when a possible-nil warning overlaps the
				// same invalid argument. Paint warning backgrounds before errors.
				for (int pass = 0; pass < 2; pass++) {
					for (LuaSyntaxHighlighter.ErrorRange range : editorErrorRanges) {
						if (range.end() <= begin) continue;
						if (range.start() >= end) break;
						if (range.warning() != (pass == 0)) continue;
						int partStart = Math.max(begin, range.start());
						int partEnd = Math.min(end, range.end());
						if (partStart >= partEnd) continue;
						int left = editor.getX() + 4 - LuaEditorViewport.offset(editor)
								+ Math.round(codeFont.width(source.substring(begin, partStart)) * characterWidthScale());
						int right = left + Math.max(2,
								Math.round(codeFont.width(source.substring(partStart, partEnd)) * characterWidthScale()));
						int rowBottom = Math.min(editor.getBottom(), y + codeLineHeight);
						graphics.fill(left, y - 1, right, rowBottom, range.warning() ? 0x44E6AD45 : 0x44FF3045);
						graphics.fill(left, Math.max(y, rowBottom - 2), right, rowBottom,
								range.warning() ? 0xFFE6AD45 : 0xFFFF4D62);
					}
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
					int left = editor.getX() + 4 - LuaEditorViewport.offset(editor) + Math.round(codeFont.width(source.substring(begin, partStart)) * characterWidthScale());
					int right = left + Math.max(2,
							Math.round(codeFont.width(source.substring(partStart, partEnd)) * characterWidthScale()));
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
		String state = runningExecution != null && runningExecution.isRunning() ? "运行中" : LuaUiText.text("运行日志");
		graphics.text(font, "OUTPUT · " + state, left + 9, top + 8,
				runningExecution != null && runningExecution.isRunning() ? 0xFF7ED6A5 : 0xFF8BA7C7);
		int closeLeft = right - 25;
		String windowHint = font.plainSubstrByWidth("标题栏拖至边缘吸附 · 边框缩放",
				Math.max(0, closeLeft - left - 134));
		if (!windowHint.isEmpty()) graphics.text(font, windowHint, left + 104, top + 8, 0xFF758393);
		boolean closeHovered = mouseX >= closeLeft && mouseX <= right - 5
				&& mouseY >= top + 3 && mouseY <= top + 20;
		graphics.fill(closeLeft, top + 3, right - 5, top + 20, closeHovered ? 0xFFB83A46 : 0xFF303640);
		graphics.centeredText(font, "×", closeLeft + 10, top + 5, 0xFFFFFFFF);
		graphics.fill(closeLeft - 24, top + 3, closeLeft - 3, top + 20, 0xFF303640);
		graphics.centeredText(font, "复", closeLeft - 14, top + 5, 0xFFFFFFFF);
		if (mouseX >= closeLeft - 24 && mouseX < closeLeft - 3 && mouseY >= top + 3 && mouseY <= top + 20)
			status = "日志：拖动选择 · Ctrl+C 复制 · Ctrl+A 全选 · 复按钮复制全部";

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
		boolean showSelection = !outputSelectionSource.isEmpty() && String.join("\n", outputLines).startsWith(outputSelectionSource);
		int y = contentTop;
		graphics.enableScissor(left + 2, contentTop - 1, right - 2, contentBottom + 1);
		for (int index = firstLine; index < lastLine; index++) {
			OutputVisualLine line = visualLines.get(index);
			if (showSelection) {
				int start = Math.max(line.start(), Math.min(outputSelectionAnchor, outputSelectionCaret));
				int end = Math.min(line.end(), Math.max(outputSelectionAnchor, outputSelectionCaret));
				if (end > start) {
					int selectionX = contentLeft + font.width(line.text().substring(0, start - line.start()));
					graphics.fill(selectionX, y, selectionX + font.width(line.text().substring(start - line.start(), end - line.start())),
							y + font.lineHeight, 0xAA315D91);
				}
			}
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
		int offset = 0;
		for (String rawLine : outputLines) {
			int color = rawLine.startsWith("✗") ? 0xFFFF6868
					: rawLine.startsWith("⚠") ? 0xFFFFCB6B
					: rawLine.startsWith("✓") ? 0xFF62E68A
					: rawLine.startsWith("▶") || rawLine.startsWith("■") ? 0xFF7FC8FF : 0xFFDDDDDD;
			int base = offset;
			if (rawLine.isEmpty()) visualLines.add(new OutputVisualLine("", color, base, base));
			else font.getSplitter().splitLines(rawLine, maxWidth, net.minecraft.network.chat.Style.EMPTY, false,
					(style, start, end) -> visualLines.add(new OutputVisualLine(rawLine.substring(start, end), color, base + start, base + end)));
			offset += rawLine.length() + 1;
		}
		return visualLines;
	}

	private int outputOffsetAt(double mouseX, double mouseY) {
		List<OutputVisualLine> lines = outputVisualLines(Math.max(20, outputWindowWidth - 26));
		if (lines.isEmpty()) return 0;
		int contentTop = outputPanelY() + OUTPUT_TITLE_HEIGHT + 5;
		int count = Math.max(1, (outputWindowHeight - OUTPUT_TITLE_HEIGHT - 12) / font.lineHeight);
		int last = Math.max(0, lines.size() - Math.clamp(outputScrollLines, 0, Math.max(0, lines.size() - count)));
		int first = Math.max(0, last - count);
		int index = Math.clamp(first + (int) Math.floor((mouseY - contentTop) / font.lineHeight), first, Math.max(first, last - 1));
		OutputVisualLine line = lines.get(index);
		int column = font.getSplitter().plainIndexAtWidth(line.text(), Math.max(0, (int) (mouseX - outputWindowX - 9)), net.minecraft.network.chat.Style.EMPTY);
		return Math.min(line.end(), line.start() + column);
	}

	private void copyOutput(boolean all) {
		String current = String.join("\n", outputLines);
		String copied = all ? current : current.startsWith(outputSelectionSource)
				? outputSelectionSource.substring(Math.min(outputSelectionAnchor, outputSelectionCaret),
						Math.max(outputSelectionAnchor, outputSelectionCaret)) : "";
		if (!copied.isEmpty()) { minecraft.keyboardHandler.setClipboard(copied); status = all ? "已复制全部日志" : "已复制选中日志"; }
	}

	@Override
	public void tick() {
		syncMcpFiles();
		if (!isCurrentIde()) return;
		if (System.nanoTime() >= nextRecoverySnapshot) {
			nextRecoverySnapshot = System.nanoTime() + 1_000_000_000L;
			snapshotRecovery();
		}
		if (!minecraft.isWindowActive()) leftMouseHeld = false;
		revealDebuggerStop();
		revealDebuggerLocation();
		if (editor != null && editor.isFocused() && !isJsonDocument() && !isFunctionDocument()
				&& (argumentCatalogRevision != ScriptManager.catalogRevision()
						|| argumentApiRevision != LuaApiDocumentation.revision())) {
			argumentCatalogRevision = ScriptManager.catalogRevision();
			argumentApiRevision = LuaApiDocumentation.revision();
			argumentCatalogs.clear(); argumentCatalogRequests.clear();
			refreshSuggestions();
		}
		if (pendingPersonalizationRebuild) {
			pendingPersonalizationRebuild = false;
			rebuildWidgets();
		}
		if (pendingInterfaceScale != null) {
			interfaceScale = pendingInterfaceScale;
			pendingInterfaceScale = null;
			rememberedInterfaceScale = interfaceScale;
			applyLogicalDimensions();
			rebuildWidgets();
			status = "整个 IDE 界面已缩放为 " + pendingInterfacePercent + "%";
		}
		super.tick();
		shortcutOpenedTools.removeIf(tool -> !toolOpen(tool));
		syncExecutionState();
		ai.tick();
		if (insertOverlay != null) insertOverlay.tick();
		if (apiDocsOverlay != null) apiDocsOverlay.tick();
		if (variableOverlay != null) variableOverlay.tick();
		extensionWindows.values().forEach(LuaExtensionWindow::tick);
		if (focusedExtensionWindow != null && (!focusedExtensionWindow.isOpen()
				|| !toolCanInteract(ToolWindow.extension(focusedExtensionWindow.id())))) focusExtensionWindow(null);
		if (componentEditorOverlay != null) componentEditorOverlay.tick();
		if (datapackJsonEditorOverlay != null) datapackJsonEditorOverlay.tick();
		if (textJsonOverlay != null) textJsonOverlay.tick();
		if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.tick();
		if (fileOverlay != null) fileOverlay.tick();
		if (datapackOverlay != null) datapackOverlay.tick();
		if (resourceDirectoryOverlay != null) resourceDirectoryOverlay.tick();
		if (resourceJsonOverlay != null) resourceJsonOverlay.tick();
		if (resourcePngOverlay != null) resourcePngOverlay.tick();
		if (gitPanel != null) gitPanel.tick();
		if (personalizationPanel != null) personalizationPanel.tick();
		if (exportPanel != null) exportPanel.tick();
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
		return mouseScrolledLogical(mouseX, mouseY, horizontalAmount, verticalAmount, minecraft.hasControlDown());
	}

	private boolean mouseScrolledLogical(double mouseX, double mouseY,
			double horizontalAmount, double verticalAmount, boolean controlDown) {
		if (recoveryPrompt || pendingCloseAction != null) return true;
		if (editorContextMenu.isOpen()) {
			if (editorContextMenu.contains(mouseX, mouseY)) return true;
			editorContextMenu.close();
		}
		if (!menuWidgetAt(mouseX, mouseY)) {
			if (fileOverlay != null && toolCanInteract(ToolWindow.FILES) && fileOverlay.contextContains(mouseX, mouseY))
				return fileOverlay.mouseScrolled(mouseX, mouseY, verticalAmount);
			if (datapackOverlay != null && toolCanInteract(ToolWindow.DATAPACK_FILES) && datapackOverlay.contextContains(mouseX, mouseY))
				return datapackOverlay.mouseScrolled(mouseX, mouseY, verticalAmount);
		}
		if (resourceDirectoryOverlay != null && toolCanInteract(ToolWindow.RESOURCE_FILES)
				&& resourceDirectoryOverlay.directoryContextContains(mouseX, mouseY))
			return resourceDirectoryOverlay.mouseScrolled(mouseX, mouseY, verticalAmount);
		Screen settingsPanel = settingsPanelAt(mouseX, mouseY);
		if (settingsPanel == null && !menuWidgetAt(mouseX, mouseY) && !pointInsideSearch(mouseX, mouseY)) {
			LuaExtensionWindow extension = extensionWindowAt(mouseX, mouseY);
			if (extension != null) return extension.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		if (settingsPanel != null) {
			settingsPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
			return true;
		}
		ensureManualGeometry();
		boolean floatingToolHit = pointInsideFloatingTool(mouseX, mouseY);
		if (datapackOverlay != null && toolCanInteract(ToolWindow.DATAPACK_FILES)
				&& (!datapackOverlay.dockSide().docked() || !floatingToolHit)
				&& datapackOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_PNG, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_FILES)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && toolCanInteract(tool) && (!overlay.dockSide().docked() || !floatingToolHit)
					&& overlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		}
		if (fileOverlay != null && toolCanInteract(ToolWindow.FILES)
				&& (!fileOverlay.dockSide().docked() || !floatingToolHit)
				&& fileOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (componentEditorOverlay != null && toolCanInteract(ToolWindow.COMPONENT_EDITOR)
				&& (!componentEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& componentEditorOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (datapackJsonEditorOverlay != null && toolCanInteract(ToolWindow.DATAPACK_JSON_EDITOR)
				&& (!datapackJsonEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& datapackJsonEditorOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (textJsonOverlay != null && toolCanInteract(ToolWindow.TEXT_JSON)
				&& (!textJsonOverlay.dockSide().docked() || !floatingToolHit)
				&& textJsonOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (minecraftUiEditorOverlay != null && toolCanInteract(ToolWindow.MINECRAFT_UI)
				&& (!minecraftUiEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& minecraftUiEditorOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (pointInsideSearch(mouseX, mouseY)) return true;
		if (apiDocsOverlay != null && toolCanInteract(ToolWindow.API_DOCS)
				&& (!apiDocsOverlay.dockSide().docked() || !floatingToolHit)
				&& apiDocsOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (variableOverlay != null && toolCanInteract(ToolWindow.VARIABLES)
				&& (!variableOverlay.dockSide().docked() || !floatingToolHit)
				&& variableOverlay.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
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
		if (currentDocument != null && currentDocument.preview != null
				&& mouseX >= editorOuterX() && mouseX <= editor.getRight()
				&& mouseY >= editor.getY() && mouseY <= editor.getBottom())
			return currentDocument.preview.scroll(verticalAmount, editor.getY(), editor.getBottom());
		if (currentResourcePng() != null && currentResourcePng().scroll(mouseX, mouseY, verticalAmount)) return true;
		if (editor != null && editor.visible && editor.active
				&& mouseX >= editorOuterX() && mouseX < editor.getRight() + editor.scrollbarWidth()
				&& mouseY >= editor.getY() && mouseY <= editor.getBottom()) {
			if (controlDown) {
				zoomCodeFont(verticalAmount);
				return true;
			}
			if (LuaEditorViewport.scroll(editor, horizontalAmount, verticalAmount,
					minecraft.hasShiftDown(), personalization.codeWheelSensitivity())) return true;
			return editor.mouseScrolled(mouseX, mouseY, horizontalAmount,
					verticalAmount * personalization.codeWheelSensitivity());
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent logicalEvent = logicalEvent(event);
		return mouseClickedLogical(logicalEvent, doubleClick);
	}

	private boolean mouseClickedLogical(MouseButtonEvent event, boolean doubleClick) {
		if (editorContextMenu.isOpen()) {
			if (editorContextMenu.contains(event.x(), event.y())) {
				if (event.button() == 0) {
					updateEditorContextAvailability();
					var action = editorContextMenu.at(event.x(), event.y());
					if (action != null) runEditorContextAction(action);
				}
				return true;
			}
			editorContextMenu.close();
		}
		outputKeyboardFocused = false;
		if (event.button() == 0) leftMouseHeld = true;
		if (recoveryPrompt) {
			if (event.button() == 0) {
				int[] b = closeDialogBounds(); int buttonWidth = (b[2] - 28) / 2;
				for (int i = 0; i < 2; i++) {
					int x = b[0] + 12 + i * (buttonWidth + 4), y = b[1] + b[3] - 34;
					if (insideSearch(event.x(), event.y(), x, y, x + buttonWidth, y + 22)) chooseRecoveryPrompt(i);
				}
			}
			return true;
		}
		if (pendingCloseAction != null) {
			if (!closeSaving && event.button() == 0) {
				int[] bounds = closeDialogBounds();
				int buttonWidth = (bounds[2] - 32) / 3;
				for (int index = 0; index < 3; index++) {
					int x = bounds[0] + 12 + index * (buttonWidth + 4);
					if (insideSearch(event.x(), event.y(), x, bounds[1] + bounds[3] - 34,
							x + buttonWidth, bounds[1] + bounds[3] - 12)) chooseClose(index);
				}
			}
			return true;
		}
		if (event.button() == 0) {
			clearDockTabDrag();
			manualKeyboardFocused = false;
			variableWindowFocused = false;
		}
		if (menuWidgetAt(event.x(), event.y())) {
			dismissDirectoryMenus();
			return super.mouseClicked(event, doubleClick);
		}
		if (fileOverlay != null && toolCanInteract(ToolWindow.FILES) && fileOverlay.contextContains(event.x(), event.y())) {
			focusExtensionWindow(null); focusedSettingsPanel = null;
			fileOverlay.mouseClicked(event); syncOverlayState(); return true;
		}
		if (datapackOverlay != null && toolCanInteract(ToolWindow.DATAPACK_FILES) && datapackOverlay.contextContains(event.x(), event.y())) {
			focusExtensionWindow(null); focusedSettingsPanel = null;
			datapackOverlay.mouseClicked(event); syncOverlayState(); return true;
		}
		if (resourceDirectoryOverlay != null && toolCanInteract(ToolWindow.RESOURCE_FILES)
				&& resourceDirectoryOverlay.directoryContextContains(event.x(), event.y())) {
			focusExtensionWindow(null); focusedSettingsPanel = null;
			resourceDirectoryOverlay.mouseClicked(event, doubleClick);
			syncOverlayState(); return true;
		}
		dismissDirectoryMenus();
		if (searchOpen && searchBox != null && searchBox.isMouseOver(event.x(), event.y())) {
			focusExtensionWindow(null);
			setFocused(searchBox);
			return searchBox.mouseClicked(event, doubleClick);
		}
		Screen settingsPanel = settingsPanelAt(event.x(), event.y());
		if (settingsPanel != null && event.button() == 0
				&& ((settingsPanel == gitPanel && gitPanel.dockSide().docked())
					|| (settingsPanel == personalizationPanel && personalizationPanel.dockSide().docked())
					|| (settingsPanel == exportPanel && exportPanel.dockSide().docked()))
				&& beginDockResize(event.x(), event.y())) return true;
		if (settingsPanel != null) {
			focusExtensionWindow(null);
			frontSettingsPanel = settingsPanel;
			focusedSettingsPanel = settingsPanel;
			pressedSettingsPanel = settingsPanel;
			settingsPanel.mouseClicked(event, doubleClick);
			return true;
		}
		focusedSettingsPanel = null;
		LuaExtensionWindow extension = pointInsideSearch(event.x(), event.y()) ? null
				: extensionWindowAt(event.x(), event.y());
		if (extension != null) {
			if (extension.dockSide().docked() && event.button() == 0 && beginDockResize(event.x(), event.y())) return true;
			focusExtensionWindow(extension);
			pressedExtensionWindow = extension;
			extension.mouseClicked(event, doubleClick);
			syncOverlayState(); return true;
		}
		focusExtensionWindow(null);
		ensureManualGeometry();
		if (datapackJsonEditorOverlay != null && !datapackJsonEditorOverlay.contains(event.x(), event.y()))
			datapackJsonEditorOverlay.releaseKeyboardFocus();
		if (datapackOverlay != null && !datapackOverlay.contains(event.x(), event.y()))
			datapackOverlay.releaseKeyboardFocus();
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_FILES, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_PNG)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && !overlay.contains(event.x(), event.y())) overlay.releaseKeyboardFocus();
		}
		if (minecraftUiEditorOverlay != null && !minecraftUiEditorOverlay.contains(event.x(), event.y())) {
			minecraftUiEditorOverlay.releaseKeyboardFocus();
		}
		boolean floatingToolHit = pointInsideFloatingTool(event.x(), event.y());
		if (event.button() == 0 && !floatingToolHit
				&& handleDocumentTabClick(event.x(), event.y())) return true;
		if (event.button() == 0 && !floatingToolHit && handleDockTabClick(event.x(), event.y())) return true;
		if (event.button() == 0 && !floatingToolHit && beginDockResize(event.x(), event.y())) return true;
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_PNG, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_FILES)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && toolCanInteract(tool) && (!overlay.dockSide().docked() || !floatingToolHit)
					&& overlay.mouseClicked(event, doubleClick)) {
				if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
				if (datapackOverlay != null) datapackOverlay.releaseKeyboardFocus();
				syncOverlayState();
				return true;
			}
		}
		if (datapackJsonEditorOverlay != null && toolCanInteract(ToolWindow.DATAPACK_JSON_EDITOR)
				&& (!datapackJsonEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& datapackJsonEditorOverlay.mouseClicked(event, doubleClick, currentDatapackJson())) {
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			if (datapackOverlay != null) datapackOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (datapackOverlay != null && toolCanInteract(ToolWindow.DATAPACK_FILES)
				&& (!datapackOverlay.dockSide().docked() || !floatingToolHit)
				&& datapackOverlay.mouseClicked(event)) {
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (fileOverlay != null && toolCanInteract(ToolWindow.FILES)
				&& (!fileOverlay.dockSide().docked() || !floatingToolHit)
				&& fileOverlay.mouseClicked(event)) {
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
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
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (minecraftUiEditorOverlay != null && toolCanInteract(ToolWindow.MINECRAFT_UI)
				&& (!minecraftUiEditorOverlay.dockSide().docked() || !floatingToolHit)
				&& minecraftUiEditorOverlay.mouseClicked(event)) {
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (textJsonOverlay != null && toolCanInteract(ToolWindow.TEXT_JSON)
				&& (!textJsonOverlay.dockSide().docked() || !floatingToolHit)
				&& textJsonOverlay.mouseClicked(event)) {
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (pointInsideSearch(event.x(), event.y())) {
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
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
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
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
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (!manualHit && insertHit && (!insertOverlay.dockSide().docked() || !floatingToolHit)
				&& insertOverlay.mouseClicked(event)) {
			manualOnTop = false;
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (textJsonOverlay != null) textJsonOverlay.releaseKeyboardFocus();
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
			syncOverlayState();
			return true;
		}
		if (manualHit && (!manualDock.docked() || !floatingToolHit)) {
			manualOnTop = true;
			manualKeyboardFocused = true;
			setFocused(null);
		}
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
		if (variableOverlay != null && toolCanInteract(ToolWindow.VARIABLES)
				&& (!variableOverlay.dockSide().docked() || !floatingToolHit)
				&& variableOverlay.mouseClicked(event, doubleClick)) {
			variableWindowFocused = variableOverlay.isOpen();
			setFocused(null);
			applyDockLayout();
			return true;
		}
		if (outputInteractive() && toolCanInteract(ToolWindow.OUTPUT)
				&& (!outputDock.docked() || !floatingToolHit)) {
			ensureOutputGeometry();
			if (event.button() == 0 && event.x() >= outputWindowX + outputWindowWidth - 49
					&& event.x() < outputWindowX + outputWindowWidth - 28
					&& event.y() >= outputPanelY() + 3 && event.y() <= outputPanelY() + 20) {
				copyOutput(true); outputKeyboardFocused = true; setFocused(null); return true;
			}
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
				outputKeyboardFocused = true; setFocused(null);
				if (event.button() == 0 && event.y() >= outputPanelY() + OUTPUT_TITLE_HEIGHT + 5) {
					outputSelectionSource = String.join("\n", outputLines);
					outputSelectionAnchor = outputSelectionCaret = outputOffsetAt(event.x(), event.y());
					outputSelecting = true;
				}
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
		if ((event.button() == 0 || event.button() == 1)
				&& currentDocument != null && currentDocument.preview != null
				&& !floatingToolHit && currentDocument.preview.click(minecraft, event.button(), event.x(), event.y(),
					editorOuterX(), editor.getY(), editor.getRight(), editor.getBottom())) return true;
		if (currentResourcePng() != null && !floatingToolHit
				&& currentResourcePng().click(event.x(), event.y(), event.button())) return true;
		if (event.button() == 0) {
			int suggestionIndex = suggestionIndexAt(event.x(), event.y());
			if (suggestionIndex >= 0) {
				selectedSuggestion = suggestionIndex;
				animatedSuggestionSelection = suggestionIndex;
				applySelectedSuggestion();
				return true;
			}
		}
		if (event.button() == 1 && !floatingToolHit && pointInsideEditor(event.x(), event.y())
				&& editor.active && sourceLoaded && currentDocument != null && !currentDocument.binary()
				&& !LuaEditorViewport.scrollbarContains(editor, event.x(), event.y())) {
			focusCodeEditor();
			var access = (MultilineTextFieldAccessor) textField();
			int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
			int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
			int offset = editorCharacterAt(textField(), event.x(), event.y());
			if (start == end || offset < start || offset >= end) {
				editor.mouseClicked(new MouseButtonEvent(event.x(), event.y(),
						new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
			}
			hideMenus(); dismissDirectoryMenus(); clearSuggestions();
			leftMouseHeld = false; setDragging(false);
			editorContextMenu.open(event.x(), event.y(), width, height);
			editorContextDocument = currentDocument;
			updateEditorContextAvailability();
			return true;
		}
		if (!floatingToolHit && LuaEditorViewport.mouseClicked(editor, event.x(), event.y(), event.button())) {
			setFocused(editor);
			clearSuggestions();
			return true;
		}
		if (event.button() == 0 && event.hasControlDown()
				&& pointInsideEditor(event.x(), event.y())) {
			String symbol = apiSymbolAt(textField(), event.x(), event.y());
			if (openApiDocumentation(symbol)) return true;
		}
		if (event.button() == 0 && !floatingToolHit && functionModel() != null
				&& event.x() >= editor.getX() - 10 && event.x() < editor.getX()
				&& event.y() > editor.getY() && event.y() < editor.getBottom()
				&& LuaFunctionFolding.toggle(textField(), gutterLineAt(event.y()))) {
			clearSuggestions();
			setFocused(editor);
			return true;
		}
		if (event.button() == 0 && !floatingToolHit && isBreakpointDocument()
				&& event.x() > editorOuterX() + 4 && event.x() < editor.getX() - 1
				&& event.y() > editor.getY() && event.y() < editor.getBottom()) {
			int line = gutterLineAt(event.y());
			if (line > 0) toggleBreakpoint(line);
			return true;
		}
		boolean editorHit = event.button() == 0 && pointInsideEditor(event.x(), event.y());
		if (editorHit) {
			variableWindowFocused = false;
			if (apiDocsOverlay != null) apiDocsOverlay.releaseKeyboardFocus();
			if (insertOverlay != null) insertOverlay.releaseKeyboardFocus();
			if (componentEditorOverlay != null) componentEditorOverlay.releaseKeyboardFocus();
			if (fileOverlay != null) fileOverlay.releaseKeyboardFocus();
			if (minecraftUiEditorOverlay != null) minecraftUiEditorOverlay.releaseKeyboardFocus();
			syncOverlayState();
		}
		// Use our glyph-based token selection directly. Vanilla's word selection can move
		// the cursor/viewport to another token before our double-click hit test runs.
		boolean handled = super.mouseClicked(event, doubleClick && !editorHit);
		if (editorHit && doubleClick && editor.isFocused()) smartSelectEditorToken(event.x(), event.y());
		if (editorHit && editor.isFocused()) {
			// MultiLineEditBox updates its cursor during mouseClicked. Refresh afterwards so the
			// popup follows the new caret and never keeps results from the previous token.
			refreshSuggestions();
		}
		return handled;
	}

	private void smartSelectEditorToken(double mouseX, double mouseY) {
		MultilineTextField field = textField();
		String source = field.value();
		if (source.isEmpty()) return;
		// The vanilla double-click selection can snap to the next word. Hit-test the
		// rendered glyph instead of using the cursor/selection that vanilla left behind.
		int offset = editorCharacterAt(field, mouseX, mouseY);
		if (offset < 0) return;
		char current = source.charAt(offset);
		int start = offset;
		int end = offset + 1;
		int[] stringRange = quotedRangeAt(source, offset);
		if (stringRange != null) {
			// 点引号选中完整字符串；点内容只选中内容，方便立即替换而不破坏 Lua 引号。
			start = current == source.charAt(stringRange[0]) ? stringRange[0] : stringRange[0] + 1;
			end = current == source.charAt(stringRange[1] - 1) ? stringRange[1] : stringRange[1] - 1;
		} else if (isEditorIdentifierCharacter(current)) {
			while (start > 0 && isEditorIdentifierCharacter(source.charAt(start - 1))) start--;
			while (end < source.length() && isEditorIdentifierCharacter(source.charAt(end))) end++;
		} else if (Character.isWhitespace(current)) {
			while (start > 0 && Character.isWhitespace(source.charAt(start - 1))
					&& source.charAt(start - 1) != '\n' && source.charAt(start - 1) != '\r') start--;
			while (end < source.length() && Character.isWhitespace(source.charAt(end))
					&& source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
		} else if (isOpeningDelimiter(current) || isClosingDelimiter(current)) {
			int match = matchingDelimiter(source, offset);
			if (match >= 0) {
				start = Math.min(offset, match);
				end = Math.max(offset, match) + 1;
			}
		} else if (isEditorOperator(current)) {
			while (start > 0 && isEditorOperator(source.charAt(start - 1))) start--;
			while (end < source.length() && isEditorOperator(source.charAt(end))) end++;
		}
		field.setSelecting(false);
		field.seekCursor(Whence.ABSOLUTE, start);
		field.setSelecting(true);
		field.seekCursor(Whence.ABSOLUTE, end);
		field.setSelecting(false);
	}

	private int editorCharacterAt(MultilineTextField field, double mouseX, double mouseY) {
		double documentY = mouseY - editor.getY() - 4 + editor.scrollAmount();
		if (documentY < 0) return -1;
		int requestedLine = (int) (documentY / scaledCodeLineHeight());
		StringViewAccessor selectedView = null;
		int lineIndex = 0;
		for (Object rawView : field.iterateLines()) {
			if (lineIndex++ == requestedLine) {
				selectedView = (StringViewAccessor) rawView;
				break;
			}
		}
		if (selectedView == null) return -1;
		String source = field.value();
		int begin = Math.max(0, Math.min(source.length(), selectedView.mcLuaIde$getBeginIndex()));
		int end = Math.max(begin, Math.min(source.length(), selectedView.mcLuaIde$getEndIndex()));
		if (begin == end) return -1;
		double documentX = (mouseX - editor.getX() - 4 + LuaEditorViewport.offset(editor)) / Math.max(0.001F, characterWidthScale());
		if (documentX < 0) return -1;
		String visibleLine = source.substring(begin, end);
		int relative = codeFont.getSplitter().plainIndexAtWidth(visibleLine, (int) Math.floor(documentX), Style.EMPTY);
		if (relative < visibleLine.length()) return begin + relative;
		return documentX < codeFont.width(visibleLine) + 2 ? source.offsetByCodePoints(end, -1) : -1;
	}

	void verifyDoubleClickForTesting() {
		sourceLoaded = true;
		verifyDoubleClickForTesting("MC.Player.addExperience", "Player");
		verifyDoubleClickForTesting("local exp = 5 + level * 2", "5");
	}

	private void verifyDoubleClickForTesting(String source, String expected) {
		editor.setValue(source);
		editor.active = true;
		int tokenStart = source.indexOf(expected);
		double mouseX = editor.getX() + 4 + characterWidthScale() * (codeFont.width(source.substring(0, tokenStart))
				+ codeFont.width(expected) / 2.0);
		double mouseY = editor.getY() + 4 + scaledCodeLineHeight() / 2.0 - editor.scrollAmount();
		mouseClickedLogical(new MouseButtonEvent(mouseX, mouseY,
				new net.minecraft.client.input.MouseButtonInfo(0, 0)), true);
		MultilineTextFieldAccessor access = (MultilineTextFieldAccessor) textField();
		int left = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		int right = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
		String selected = textField().value().substring(left, right);
		if (!expected.equals(selected)) throw new AssertionError("Double-click selected '" + selected
				+ "' instead of '" + expected + "' in " + source + ", glyph="
				+ editorCharacterAt(textField(), mouseX, mouseY) + ", editorHit="
				+ pointInsideEditor(mouseX, mouseY) + ", focused=" + editor.isFocused()
				+ ", active=" + editor.active + ", mouse=" + mouseX + "," + mouseY);
	}

	private static boolean isEditorIdentifierCharacter(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static boolean isEditorOperator(char value) {
		return ".:+-*/%^#=<>~,&|".indexOf(value) >= 0;
	}

	private static boolean isOpeningDelimiter(char value) {
		return value == '(' || value == '[' || value == '{';
	}

	private static boolean isClosingDelimiter(char value) {
		return value == ')' || value == ']' || value == '}';
	}

	private static int matchingDelimiter(String source, int offset) {
		char selected = source.charAt(offset);
		char opening = selected == ')' ? '(' : selected == ']' ? '[' : selected == '}' ? '{' : selected;
		char closing = opening == '(' ? ')' : opening == '[' ? ']' : '}';
		int direction = isOpeningDelimiter(selected) ? 1 : -1;
		int depth = 0;
		char quote = 0;
		boolean escaped = false;
		for (int index = offset; index >= 0 && index < source.length(); index += direction) {
			char value = source.charAt(index);
			if (quote != 0) {
				if (escaped) escaped = false;
				else if (value == '\\') escaped = true;
				else if (value == quote) quote = 0;
				continue;
			}
			if (value == '"' || value == '\'') {
				quote = value;
				continue;
			}
			if (value == (direction > 0 ? opening : closing)) depth++;
			else if (value == (direction > 0 ? closing : opening) && --depth == 0) return index;
		}
		return -1;
	}

	private static int[] quotedRangeAt(String source, int offset) {
		int lineStart = Math.max(source.lastIndexOf('\n', offset - 1), source.lastIndexOf('\r', offset - 1)) + 1;
		int lineEnd = source.indexOf('\n', offset);
		if (lineEnd < 0) lineEnd = source.length();
		char quote = 0;
		int opening = -1;
		boolean escaped = false;
		for (int index = lineStart; index < lineEnd; index++) {
			char value = source.charAt(index);
			if (quote == 0) {
				if (value == '"' || value == '\'') {
					quote = value;
					opening = index;
				}
				continue;
			}
			if (escaped) {
				escaped = false;
				continue;
			}
			if (value == '\\') {
				escaped = true;
				continue;
			}
			if (value == quote) {
				if (offset >= opening && offset <= index) return new int[]{opening, index + 1};
				quote = 0;
				opening = -1;
			}
		}
		return quote != 0 && offset >= opening ? new int[]{opening, lineEnd} : null;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		MouseButtonEvent logicalEvent = logicalEvent(event);
		return mouseDraggedLogical(logicalEvent, dragX / interfaceScale, dragY / interfaceScale);
	}

	private boolean mouseDraggedLogical(MouseButtonEvent event, double dragX, double dragY) {
		if (editorContextMenu.isOpen()) return true;
		if (event.button() == 0) leftMouseHeld = true;
		if (recoveryPrompt || pendingCloseAction != null) return true;
		if (LuaEditorViewport.mouseDragged(editor, event.x(), event.y(), event.button())) return true;
		if (pressedDockTab != null && event.button() == 0) {
			updateDockTabDrag(event.x(), event.y()); return true;
		}
		if (pressedExtensionWindow != null && pressedExtensionWindow.mouseDragged(event, dragX, dragY)) {
			applyDockLayout(); return true;
		}
		if (currentDocument != null && currentDocument.preview != null
				&& currentDocument.preview.drag(event.x(), event.y())) return true;
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_PNG, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_FILES)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && overlay.isInteracting()) {
				overlay.undockForDrag(event.x(), event.y());
				if (!overlay.mouseDragged(event)) continue;
				applyDockLayout(); return true;
			}
		}
		if (currentResourcePng() != null && currentResourcePng().drag(event.x(), event.y())) return true;
		if (activeDockResize.docked()) {
			resizeDock(event.x(), event.y());
			return true;
		}
		if (pressedSettingsPanel != null) {
			pressedSettingsPanel.mouseDragged(event, dragX, dragY);
			applyDockLayout();
			return true;
		}
		if (datapackOverlay != null && datapackOverlay.isInteracting() && datapackOverlay.mouseDragged(event)) {
			applyDockLayout();
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
		if (variableOverlay != null && variableOverlay.isInteracting() && variableOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (componentEditorOverlay != null && componentEditorOverlay.isInteracting()
				&& componentEditorOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (datapackJsonEditorOverlay != null && datapackJsonEditorOverlay.isInteracting()
				&& datapackJsonEditorOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (textJsonOverlay != null && textJsonOverlay.isInteracting()
				&& textJsonOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (minecraftUiEditorOverlay != null && minecraftUiEditorOverlay.isInteracting()
				&& minecraftUiEditorOverlay.mouseDragged(event)) {
			applyDockLayout();
			return true;
		}
		if (outputSelecting) {
			int max = Math.max(0, outputVisualLines(Math.max(20, outputWindowWidth - 26)).size()
					- Math.max(1, (outputWindowHeight - OUTPUT_TITLE_HEIGHT - 12) / font.lineHeight));
			if (event.y() < outputPanelY() + OUTPUT_TITLE_HEIGHT + 5) outputScrollLines = Math.min(max, outputScrollLines + 1);
			else if (event.y() > outputPanelY() + outputWindowHeight - 7) outputScrollLines = Math.max(0, outputScrollLines - 1);
			outputSelectionCaret = Math.min(outputSelectionSource.length(), outputOffsetAt(event.x(), event.y()));
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
		boolean handled = super.mouseDragged(event, dragX, dragY);
		if (handled && editor.isFocused() && textField().hasSelection()) clearSuggestions();
		return handled;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return mouseReleasedLogical(logicalEvent(event));
	}

	private boolean mouseReleasedLogical(MouseButtonEvent event) {
		// Reset before any early return (scrollbars, dock dividers, overlays, etc.).
		if (event.button() == 0) leftMouseHeld = false;
		if (LuaEditorViewport.mouseReleased(editor, event.button())) return true;
		if (recoveryPrompt || pendingCloseAction != null) return true;
		if (pressedDockTab != null && event.button() == 0) {
			updateDockTabDrag(event.x(), event.y());
			boolean reordered = dockTabDragging;
			clearDockTabDrag();
			if (reordered) status = "窗口标签顺序已保存";
			rememberWorkspaceLayout();
			return true;
		}
		if (pressedExtensionWindow != null) {
			LuaExtensionWindow window = pressedExtensionWindow;
			pressedExtensionWindow = null;
			boolean wasDragging = window.isDragging();
			window.mouseReleased(event);
			finishToolDrag(ToolWindow.extension(window.id()), wasDragging, event.x(), event.y());
			syncOverlayState(); return true;
		}
		if (currentDocument != null && currentDocument.preview != null
				&& currentDocument.preview.release(minecraft, event.button(), event.x())) return true;
		if (activeDockResize.docked()) {
			activeDockResize = DockSide.FLOATING;
			applyDockLayout();
			return true;
		}
		if (pressedSettingsPanel != null) {
			Screen panel = pressedSettingsPanel;
			pressedSettingsPanel = null;
			boolean wasDragging = panel == gitPanel ? gitPanel.isDraggingPanel()
					: panel == personalizationPanel ? personalizationPanel.isDraggingPanel()
					: panel == exportPanel && exportPanel.isDraggingPanel();
			panel.mouseReleased(event);
			if (panel == gitPanel) finishToolDrag(ToolWindow.GIT, wasDragging, event.x(), event.y());
			if (panel == personalizationPanel)
				finishToolDrag(ToolWindow.PERSONALIZATION, wasDragging, event.x(), event.y());
			if (panel == exportPanel)
				finishToolDrag(ToolWindow.EXPORT, wasDragging, event.x(), event.y());
			return true;
		}
		if (datapackOverlay != null && datapackOverlay.isInteracting()) {
			boolean wasDragging = datapackOverlay.isDragging();
			datapackOverlay.mouseReleased();
			finishToolDrag(ToolWindow.DATAPACK_FILES, wasDragging, event.x(), event.y());
			syncOverlayState();
			return true;
		}
		for (ToolWindow tool : List.of(ToolWindow.RESOURCE_PNG, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_FILES)) {
			LuaResourcePackToolOverlay overlay = resourceTool(tool);
			if (overlay != null && overlay.isInteracting()) {
				boolean wasDragging = overlay.isDragging();
				overlay.mouseReleased();
				finishToolDrag(tool, wasDragging, event.x(), event.y());
				syncOverlayState(); return true;
			}
		}
		if (currentResourcePng() != null && currentResourcePng().release()) return true;
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
		if (variableOverlay != null && variableOverlay.isInteracting()) {
			boolean wasDragging = variableOverlay.isDragging();
			variableOverlay.mouseReleased();
			finishToolDrag(ToolWindow.VARIABLES, wasDragging, event.x(), event.y());
			return true;
		}
		if (datapackJsonEditorOverlay != null && datapackJsonEditorOverlay.isInteracting()) {
			boolean wasDragging = datapackJsonEditorOverlay.isDragging();
			if (!datapackJsonEditorOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.DATAPACK_JSON_EDITOR, wasDragging, event.x(), event.y());
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
		if (minecraftUiEditorOverlay != null && minecraftUiEditorOverlay.isInteracting()) {
			boolean wasDragging = minecraftUiEditorOverlay.isDragging();
			if (!minecraftUiEditorOverlay.mouseReleased()) return false;
			finishToolDrag(ToolWindow.MINECRAFT_UI, wasDragging, event.x(), event.y());
			syncOverlayState();
			return true;
		}
		if (outputSelecting) { outputSelecting = false; return true; }
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
		for (LuaExtensionWindow window : extensionWindows.values())
			if (!window.dockSide().docked() && window.hitFrame(mouseX, mouseY)) return true;
		if (gitPanel != null && !gitPanel.dockSide().docked() && gitPanel.contains(mouseX, mouseY)) return true;
		if (personalizationPanel != null && !personalizationPanel.dockSide().docked()
				&& personalizationPanel.contains(mouseX, mouseY)) return true;
		if (exportPanel != null && !exportPanel.dockSide().docked()
				&& exportPanel.contains(mouseX, mouseY)) return true;
		if (datapackOverlay != null && !datapackOverlay.dockSide().docked()
				&& datapackOverlay.contains(mouseX, mouseY)) return true;
		if (resourceDirectoryOverlay != null && !resourceDirectoryOverlay.dockSide().docked()
				&& resourceDirectoryOverlay.contains(mouseX, mouseY)) return true;
		if (resourceJsonOverlay != null && !resourceJsonOverlay.dockSide().docked()
				&& resourceJsonOverlay.contains(mouseX, mouseY)) return true;
		if (resourcePngOverlay != null && !resourcePngOverlay.dockSide().docked()
				&& resourcePngOverlay.contains(mouseX, mouseY)) return true;
		if (fileOverlay != null && !fileOverlay.dockSide().docked()
				&& fileOverlay.contains(mouseX, mouseY)) return true;
		if (apiDocsOverlay != null && !apiDocsOverlay.dockSide().docked()
				&& apiDocsOverlay.contains(mouseX, mouseY)) return true;
		if (variableOverlay != null && !variableOverlay.dockSide().docked()
				&& variableOverlay.contains(mouseX, mouseY)) return true;
		if (componentEditorOverlay != null && !componentEditorOverlay.dockSide().docked()
				&& componentEditorOverlay.contains(mouseX, mouseY)) return true;
		if (datapackJsonEditorOverlay != null && !datapackJsonEditorOverlay.dockSide().docked()
				&& datapackJsonEditorOverlay.contains(mouseX, mouseY)) return true;
		if (textJsonOverlay != null && !textJsonOverlay.dockSide().docked()
				&& textJsonOverlay.contains(mouseX, mouseY)) return true;
		if (minecraftUiEditorOverlay != null && !minecraftUiEditorOverlay.dockSide().docked()
				&& minecraftUiEditorOverlay.contains(mouseX, mouseY)) return true;
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
		graphics.text(font, LuaUiText.text("▣  MC Lua IDE 使用手册"), panelX + 10, manualWindowY + 9, 0xFF78BEFF);
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
				graphics.centeredText(font, LuaUiText.text("复制"), (copyX + panelRight - 17) / 2, y + 7, 0xFFFFFFFF);
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
			String label = suggestionLabel(suggestions.get(index));
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
		if (hoveredSuggestion >= 0 && !leftMouseHeld) {
			String suggestion = suggestions.get(hoveredSuggestion);
			var argumentChoice = argumentSuggestionChoices.get(suggestion);
			if (argumentChoice == null) argumentChoice = moduleSuggestionChoices.get(suggestion);
			String tooltip = functionSuggestionTooltips.get(suggestion);
			if (argumentChoice != null)
				graphics.setComponentTooltipForNextFrame(font, argumentChoice.tooltip().lines().<Component>map(Component::literal).toList(), mouseX, mouseY);
			else if (isFunctionDocument() && tooltip != null && !tooltip.isBlank())
				graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal(tooltip)), mouseX, mouseY);
			else if (!isFunctionDocument() && !showFunctionTooltip(graphics, suggestion, textField().cursor(), mouseX, mouseY))
				showApiTooltip(graphics, LuaApiDocumentation.find(suggestion), mouseX, mouseY);
		}
	}

	private SuggestionPopup suggestionPopup(MultilineTextField field) {
		if (suggestions.isEmpty() || editor == null) return null;
		if (field.hasSelection()) {
			clearSuggestions();
			return null;
		}
		int lineIndex = field.getLineAtCursor();
		String source = field.value();
		int cursor = Math.max(0, Math.min(field.cursor(), source.length()));
		// A caret at offset 0 is before a leading newline, so the search must start at -1.
		int logicalLineStart = source.lastIndexOf('\n', cursor - 1) + 1;
		String beforeCursor = source.substring(logicalLineStart, cursor);
		int popupWidth = suggestions.stream()
				.map(this::suggestionLabel)
				.mapToInt(font::width).max().orElse(80) + 16;
		popupWidth = Math.max(110, Math.min(Math.max(110, width - 24), Math.min(360, popupWidth)));
		int maxRowsByEditor = Math.max(1, (editor.getHeight() - 10) / SUGGESTION_ROW_HEIGHT);
		int visibleRows = Math.min(suggestions.size(), Math.min(SUGGESTION_MAX_VISIBLE_ROWS, maxRowsByEditor));
		int popupHeight = visibleRows * SUGGESTION_ROW_HEIGHT + 6;
		int popupX = editor.getX() + 4 - LuaEditorViewport.offset(editor) + Math.round(codeFont.width(beforeCursor) * characterWidthScale());
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
		if (leftMouseHeld) return;
		if (mouseX < editor.getX() + 4 || mouseX > editor.getRight() - 4
				|| mouseY < editor.getY() + 4 || mouseY > editor.getBottom() - 4) {
			return;
		}
		String symbol = apiSymbolAt(field, mouseX, mouseY);
		var model = functionModel();
		if (symbol != null && (model == null || !model.identifierAt(hoveredSymbolOffset)
				|| !showFunctionTooltip(graphics, symbol, hoveredSymbolOffset, mouseX, mouseY))) {
			showApiTooltip(graphics, LuaApiDocumentation.find(symbol), mouseX, mouseY);
		}
	}

	private String apiSymbolAt(MultilineTextField field, double mouseX, double mouseY) {
		String source = field.value();
		int offset = editorCharacterAt(field, mouseX, mouseY);
		if (offset < 0 || offset >= source.length()) return null;
		if (!isApiSymbolCharacter(source.charAt(offset))) {
			if (offset > 0 && source.charAt(offset) == '(') offset--;
			else return null;
		}
		hoveredSymbolOffset = offset;
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
		var model = functionModel();
		if (model != null && model.identifierAt(offset)
				&& (model.find(symbol, offset) != null || !model.variableTooltip(symbol, offset).isEmpty())) return symbol;
		return LuaApiDocumentation.find(symbol) == null ? null : symbol;
	}

	private boolean isApiSymbolCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_' || character == '.' || character == ':';
	}

	private boolean showFunctionTooltip(GuiGraphicsExtractor graphics, String symbol, int offset, int mouseX, int mouseY) {
		var model = functionModel();
		if (model == null) return false;
		var function = model.find(symbol, offset);
		List<String> lines = function == null ? model.variableTooltip(symbol, offset) : function.tooltip();
		if (lines.isEmpty()) return false;
		graphics.setComponentTooltipForNextFrame(font, lines.stream().<Component>map(Component::literal).toList(), mouseX, mouseY);
		return true;
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
		return cursor - source.lastIndexOf('\n', cursor - 1);
	}

	private void trackRunSave(ScriptManager.ScriptExecution execution, OpenDocument document, String source) {
		aiRunSource = new LuaAiAgent.Problem(aiPath(document), source, "", List.of());
		execution.saved().thenRun(() -> minecraft.execute(() -> {
			document.savedSource = source;
			if (currentDocument == document) lastSavedSource = source;
			snapshotRecovery();
		}));
	}

	private static final class RecoveryTracking {
		final String id;
		String source, savedSource;
		long imageRevision = -1;
		RecoveryTracking(String id) { this.id = id; }
	}

	private void snapshotRecovery() {
		if (recoveryClosed || editor == null) return;
		fileRecovery.retryFailures();
		rememberCurrentDocument();
		for (OpenDocument document : openDocuments.values()) {
			RecoveryTracking tracked = recoveryTracking.get(document.key());
			if (!document.dirty()) {
				if (tracked != null && document.loaded) clearRecoveryDraft(document.key());
				continue;
			}
			if (tracked == null) {
				tracked = new RecoveryTracking(java.util.UUID.randomUUID().toString());
				recoveryTracking.put(document.key(), tracked);
			}
			long imageRevision = document.pngCanvas == null ? -1 : document.pngCanvas.document().recoveryRevision();
			if (document.source.equals(tracked.source) && document.savedSource.equals(tracked.savedSource)
					&& imageRevision == tracked.imageRevision) continue;
			var metadata = new LuaIdeSession.Document(document.name, document.json, document.resource, document.cursor, document.scroll);
			var image = document.pngCanvas == null ? null : document.pngCanvas.document().recoveryImage();
			fileRecovery.save(new LuaFileRecovery.Draft(new LuaFileRecovery.Entry(tracked.id, metadata, System.currentTimeMillis(), image != null),
					document.source, document.savedSource, image));
			tracked.source = document.source; tracked.savedSource = document.savedSource; tracked.imageRevision = imageRevision;
		}
		String failure = fileRecovery.failure();
		if (!failure.isEmpty() && !failure.equals(reportedRecoveryFailure)) status = failure;
		reportedRecoveryFailure = failure;
	}

	private void clearRecoveryDraft(String key) {
		RecoveryTracking tracked = recoveryTracking.remove(key);
		if (tracked != null) fileRecovery.discard(tracked.id);
	}

	private void requestRecovery(LuaFileRecovery.Entry entry) {
		if (recoveryClosed || recoveryRestoreQueue.stream().anyMatch(e -> e.id().equals(entry.id()))) return;
		recoveryRestoreQueue.addLast(entry);
		if (!recoveryRestoring) restoreNextRecovery();
	}

	private void restoreNextRecovery() {
		if (recoveryClosed || recoveryRestoreQueue.isEmpty()) { recoveryRestoring = false; return; }
		recoveryRestoring = true;
		var entry = recoveryRestoreQueue.removeFirst();
		status = "正在恢复 " + entry.name() + "…";
		fileRecovery.read(entry.id()).whenComplete((draft, error) -> minecraft.execute(() -> {
			if (recoveryClosed) { recoveryRestoreQueue.clear(); recoveryRestoring = false; return; }
			try {
				if (error != null) throw new java.io.IOException(ScriptManager.message(error));
				if (fileRecovery.entries().stream().noneMatch(e -> e.id().equals(entry.id()))) return;
				rememberCurrentDocument();
				String key = draft.entry().document().key();
				OpenDocument old = openDocuments.get(key);
				// Never replace newer edits, including another recovered version of the same path.
				if (old != null && old.dirty()) { status = "未恢复 " + entry.name() + "：该标签有未保存修改，请先保存或关闭"; return; }
				if (old == null && openDocuments.size() >= LuaIdeSession.MAX_DOCUMENTS) { status = "恢复暂停：请先关闭一些标签（最多 32 个）"; return; }
				if (old == null) {
					var d = draft.entry().document(); old = new OpenDocument(d.name(), d.json(), d.resource());
					openDocuments.put(key, old);
				}
				if (old.pngCanvas != null) old.pngCanvas.close();
				old.pngCanvas = draft.image() == null ? null : new LuaResourcePngCanvas(minecraft, old.name, draft.image());
				old.readRevision++; old.loading = false; old.loaded = true;
				old.source = draft.source(); old.savedSource = draft.savedSource(); old.lastSnapshot = old.source;
				old.cursor = draft.entry().document().cursor(); old.scroll = draft.entry().document().scroll();
				old.undo.clear(); old.redo.clear();
				// A recoverable draft belongs to this session only after the user restores it.
				recoveryTracking.put(key, new RecoveryTracking(entry.id()));
				if (old == currentDocument) currentDocument = null; // do not re-copy disk text over the restored draft
				openDocument(old.name, old.json, old.resource);
				status = "已恢复 " + entry.name() + " · 请检查内容后 Ctrl+S 保存（原文件未更改）";
				snapshotRecovery();
			} catch (Exception failure) { status = "恢复失败：" + ScriptManager.message(failure); }
			finally { restoreNextRecovery(); }
		}));
	}

	private void chooseRecoveryPrompt(int choice) {
		recoveryPrompt = false; leftMouseHeld = false;
		if (choice == 0) setExtensionWindowOpen(LuaFileRecoveryWindow.ID, true);
		else status = "恢复草稿已保留，可从“工具 → 文件恢复”随时查看";
	}

	private void renderRecoveryPrompt(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int[] b = closeDialogBounds(); int x = b[0], y = b[1], w = b[2], h = b[3];
		graphics.fill(0, 0, width, height, 0xB0000000);
		graphics.fill(x, y, x + w, y + h, 0xFF202832); graphics.outline(x, y, w, h, 0xFF659EBD);
		graphics.text(font, LuaUiText.text("发现上次未正常关闭的未保存文件"), x + 12, y + 12, 0xFFFFFFFF);
		graphics.text(font, "共 " + fileRecovery.entries().size() + " 份恢复草稿，是否恢复？", x + 12, y + 32, 0xFFE7CB89);
		graphics.text(font, LuaUiText.text("恢复只打开编辑内容，不会覆盖原文件。"), x + 12, y + 54, 0xFFACC1D4);
		graphics.text(font, LuaUiText.text("稍后可在“工具 → 文件恢复”中选择文件。"), x + 12, y + 72, 0xFFACC1D4);
		String[] labels = {"选择恢复文件", "稍后"}; int buttonWidth = (w - 28) / 2;
		for (int i = 0; i < 2; i++) {
			int bx = x + 12 + i * (buttonWidth + 4), by = y + h - 34;
			boolean hover = insideSearch(mouseX, mouseY, bx, by, bx + buttonWidth, by + 22);
			graphics.fill(bx, by, bx + buttonWidth, by + 22, hover ? 0xFF45697C : 0xFF344F62);
			if (recoveryPromptChoice == i) graphics.outline(bx, by, buttonWidth, 22, 0xFF9BCBE8);
			graphics.centeredText(font, labels[i], bx + buttonWidth / 2, by + 7, 0xFFFFFFFF);
		}
	}

	@Override
	public void onClose() {
		if (nativeOwner != null) { nativeOwner.onClose(); return; }
		if (recoveryPrompt) { chooseRecoveryPrompt(1); return; }
		if (pendingCloseAction != null) return;
		rememberCurrentDocument();
		requestClose(openDocuments.values().stream().filter(OpenDocument::dirty).toList(), this::finishClose);
	}

	private void finishClose() {
		recoveryClosed = true;
		for (String key : List.copyOf(recoveryTracking.keySet())) clearRecoveryDraft(key);
		saveSession();
		rememberWorkspaceLayout();
		flushWorkspaceLayout();
		minecraft.setScreen(previousScreen);
	}

	private void requestClose(List<OpenDocument> dirty, Runnable action) {
		if (dirty.isEmpty()) { action.run(); return; }
		pendingCloseAction = action;
		pendingCloseDocuments = List.copyOf(dirty);
		closeSaving = false; closeError = ""; closeChoice = 0;
		hideMenus(); clearSuggestions();
	}

	private void cancelClose() {
		pendingCloseAction = null; pendingCloseDocuments = List.of(); closeError = "";
	}

	private void chooseClose(int choice) {
		if (pendingCloseAction == null || closeSaving) return;
		if (choice == 2) { cancelClose(); return; }
		if (choice == 1) { completeClose(); return; }
		closeSaving = true; closeError = "";
		LuaCloseSaveQueue.saveAll(pendingCloseDocuments, document ->
				saveDocumentForClose(document).exceptionallyCompose(failure -> CompletableFuture.failedFuture(
						new IllegalStateException(document.displayName() + " · " + ScriptManager.message(failure), failure))),
				minecraft::execute).whenComplete((ignored, failure) -> minecraft.execute(() -> {
			if (!isCurrentIde() || pendingCloseAction == null) return;
			if (failure != null) {
				closeSaving = false;
				closeError = LuaUiText.text("保存失败：") + ScriptManager.message(failure);
				status = closeError;
			} else completeClose();
		}));
	}

	private void completeClose() {
		Runnable action = pendingCloseAction;
		cancelClose(); closeSaving = false;
		if (action != null) action.run();
	}

	private CompletableFuture<Void> saveDocumentForClose(OpenDocument document) {
		if (!document.dirty()) return CompletableFuture.completedFuture(null);
		try {
			if (document.pngCanvas != null) {
				document.pngCanvas.save();
				return CompletableFuture.completedFuture(null);
			}
			String source = document.source;
			CompletableFuture<Void> saved;
			if (document.resource) {
				LuaResourcePackWorkspace.saveText(document.name, source);
				saved = CompletableFuture.completedFuture(null);
			} else saved = document.json ? ScriptManager.saveDatapackFile(document.name, source) : ScriptManager.save(document.name, source);
			return saved.thenRun(() -> {
				document.savedSource = source;
				if (currentDocument == document) lastSavedSource = source;
			});
		} catch (Exception error) { return CompletableFuture.failedFuture(error); }
	}

	private int[] closeDialogBounds() {
		int w = Math.min(460, Math.max(100, width - 20));
		int h = Math.min(190, Math.max(100, height - 20));
		return new int[]{(width - w) / 2, (height - h) / 2, w, h};
	}

	private void renderCloseDialog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int[] b = closeDialogBounds();
		int x = b[0], y = b[1], w = b[2], h = b[3];
		graphics.fill(0, 0, width, height, 0xB0000000);
		graphics.fill(x, y, x + w, y + h, 0xFF202832);
		graphics.outline(x, y, w, h, 0xFF659EBD);
		graphics.text(font, LuaUiText.text("有未保存的修改，是否保存？"), x + 12, y + 12, 0xFFFFFFFF);
		graphics.text(font, "共 " + pendingCloseDocuments.size() + " 个文件（包括图片）", x + 12, y + 30, 0xFFACC1D4);
		int count = Math.min(4, pendingCloseDocuments.size());
		for (int index = 0; index < count; index++)
			graphics.text(font, font.plainSubstrByWidth("• " + pendingCloseDocuments.get(index).displayName(), w - 24),
					x + 12, y + 49 + index * 13, 0xFFE7CB89);
		String message = closeSaving ? "正在保存，请稍候…" : closeError.isEmpty()
				? "不保存将丢弃修改；取消则继续编辑。" : closeError;
		graphics.text(font, font.plainSubstrByWidth(message, w - 24), x + 12, y + h - 53,
				closeError.isEmpty() ? 0xFFACC1D4 : 0xFFFF8585);
		String[] labels = {"保存后关闭", LuaUiText.text("不保存"), LuaUiText.text("取消")};
		int buttonWidth = (w - 32) / 3;
		for (int index = 0; index < 3; index++) {
			int bx = x + 12 + index * (buttonWidth + 4), by = y + h - 34;
			boolean hover = insideSearch(mouseX, mouseY, bx, by, bx + buttonWidth, by + 22);
			graphics.fill(bx, by, bx + buttonWidth, by + 22, closeSaving ? 0xFF343D46 : hover ? 0xFF45697C : 0xFF344F62);
			if (!closeSaving && closeChoice == index) graphics.outline(bx, by, buttonWidth, 22, 0xFF9BCBE8);
			graphics.text(font, labels[index], bx + (buttonWidth - font.width(labels[index])) / 2, by + 7,
					closeSaving ? 0xFF7E8B97 : 0xFFFFFFFF);
		}
	}

	@Override
	public void removed() {
		if (variableOverlay != null) variableOverlay.clearSelection();
		restoreRunPending = true;
		if (insertOverlay != null) insertOverlay.stopSoundPreview();
		ai.cancel(false); hoveredAiMark = null;
		snapshotRecovery(); // also covers disconnection/replaced screens, not just a normal IDE close
		leftMouseHeld = false;
		clearDockTabDrag();
		saveSession();
		rememberWorkspaceLayout();
		flushWorkspaceLayout();
		LuaDebugHud.setIdeVariablesEnabled(false);
		focusExtensionWindow(null);
		pressedExtensionWindow = null;
		extensionWindows.values().forEach(LuaExtensionWindow::dispose);
		LuaOggPreviewPlayer.stop();
		for (OpenDocument document : openDocuments.values()) {
			if (document.preview != null) document.preview.close(minecraft);
			if (document.pngCanvas != null) document.pngCanvas.close();
			document.preview = null;
			// Releasing the texture must not discard unsaved layers when temporarily opening settings/F12.
			if (recoveryClosed || document.pngCanvas == null || !document.pngCanvas.document().dirty()) document.pngCanvas = null;
			if (document.binary() && document.pngCanvas == null) document.loaded = false;
		}
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// The same controllers power both frontends. ImGui owns the frames; no second
	// copy of pack schemas, image tools, native file choosers or extension logic.
	private boolean isCurrentIde() {
		return minecraft.screen == this || nativeOwner != null && minecraft.screen == nativeOwner;
	}
	record NativeTool(String id, String title) { }
	record NativeDocument(String name, boolean json, boolean resource, String source, String saved, int cursor, boolean binary) { }
	List<NativeTool> nativeTools() {
		return toolWindows().stream().map(tool -> new NativeTool(tool.name(), toolWindowLabel(tool))).toList();
	}
	/** The extension window behind a tool pane id ("EXT:..."), or null. */
	LuaExtensionWindow nativeExtension(String id) {
		try { return extensionWindows.get(ToolWindow.valueOf(id)); } catch (RuntimeException ignored) { return null; }
	}
	List<String> nativeOpenTools() { return toolWindows().stream().filter(this::toolOpen).map(ToolWindow::name).toList(); }
	void nativeOpenTool(String id) {
		ToolWindow tool = ToolWindow.valueOf(id);
		if (toolOpen(tool)) return;
		nativeToolSizes.remove(id);
		switch (tool.kind) {
			case FILES -> fileOverlay.open();
			case DATAPACK_FILES -> datapackOverlay.open();
			case RESOURCE_FILES -> resourceDirectoryOverlay.open();
			case RESOURCE_JSON -> toggleResourceJson();
			case RESOURCE_PNG -> resourcePngOverlay.open();
			case DATAPACK_JSON_EDITOR -> toggleDatapackJsonEditor();
			case COMPONENT_EDITOR -> {
				var argument = currentItemComponentsArgumentContext();
				if (argument != null) openComponentEditorForArgument(argument); else componentEditorOverlay.open();
			}
			case TEXT_JSON -> {
				var argument = currentTextJsonArgumentContext();
				if (argument != null) openTextJsonEditorForArgument(argument); else textJsonOverlay.open();
			}
			case MINECRAFT_UI -> minecraftUiEditorOverlay.open();
			case INSERT -> toggleInsertOverlay();
			case API_DOCS -> toggleApiDocs();
			case MANUAL -> { manualOpen = true; manualProgress = 1; }
			case VARIABLES -> toggleVariableOverlay();
			case OUTPUT -> { outputVisible = true; outputProgress = 1; }
			case GIT -> openGitScreen();
			case PERSONALIZATION -> openPersonalization();
			case EXPORT -> exportCurrentApiDocumentation();
			case EXTENSION -> setExtensionWindowOpen(tool.id, true);
		}
		hideMenus();
	}
	void nativeCloseTool(String id) {
		ToolWindow tool = ToolWindow.valueOf(id);
		switch (tool.kind) {
			case FILES -> fileOverlay.close(); case DATAPACK_FILES -> datapackOverlay.close();
			case RESOURCE_FILES -> resourceDirectoryOverlay.close(); case RESOURCE_JSON -> resourceJsonOverlay.close();
			case RESOURCE_PNG -> resourcePngOverlay.close(); case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay.close();
			case COMPONENT_EDITOR -> componentEditorOverlay.close(); case TEXT_JSON -> textJsonOverlay.close();
			case MINECRAFT_UI -> minecraftUiEditorOverlay.close(); case INSERT -> insertOverlay.close();
			case API_DOCS -> apiDocsOverlay.close(); case MANUAL -> manualOpen = false;
			case VARIABLES -> variableOverlay.close(); case OUTPUT -> outputVisible = false;
			case GIT -> gitPanel = null; case PERSONALIZATION -> personalizationPanel = null; case EXPORT -> exportPanel = null;
			case EXTENSION -> setExtensionWindowOpen(tool.id, false);
		}
	}
	void nativePrepare(String id, int w, int h, boolean input) {
		width = w; height = h; interfaceScale = 1; layoutLocked = nativeOwner.layoutLocked();
		activeMenu = ""; activeSubmenu = "";
		if (input) {
			if (!id.equals("FILES")) fileOverlay.releaseKeyboardFocus();
			if (!id.equals("DATAPACK_FILES")) datapackOverlay.releaseKeyboardFocus();
			if (!id.equals("INSERT")) insertOverlay.releaseKeyboardFocus();
			if (!id.equals("API_DOCS")) apiDocsOverlay.releaseKeyboardFocus();
			if (!id.equals("COMPONENT_EDITOR")) componentEditorOverlay.releaseKeyboardFocus();
			if (!id.equals("DATAPACK_JSON_EDITOR")) datapackJsonEditorOverlay.releaseKeyboardFocus();
			if (!id.equals("TEXT_JSON")) textJsonOverlay.releaseKeyboardFocus();
			if (!id.equals("MINECRAFT_UI")) minecraftUiEditorOverlay.releaseKeyboardFocus();
			for (var kind : List.of(ToolWindow.RESOURCE_FILES, ToolWindow.RESOURCE_JSON, ToolWindow.RESOURCE_PNG)) if (!id.equals(kind.name())) resourceTool(kind).releaseKeyboardFocus();
		}
		if (id.equals("CODE")) {
			nativeInputTool = null;
			if (input) { focusedSettingsPanel = null; focusExtensionWindow(null); manualKeyboardFocused = variableWindowFocused = outputKeyboardFocused = false; }
			editor.setX(EDITOR_X); editor.setY(WORKSPACE_TOP);
			int codeWidth = Math.max(40, w - EDITOR_X - 12), codeHeight = Math.max(40, h - WORKSPACE_TOP);
			if (editor.getWidth() != codeWidth) editor.setWidth(codeWidth);
			if (editor.getHeight() != codeHeight) editor.setHeight(codeHeight);
			updateEditorFlowWidth(); if (input && !searchOpen) { setFocused(editor); editor.setFocused(true); } return;
		}
		ToolWindow tool = ToolWindow.valueOf(id); nativeInputTool = tool;
		if (!(w + ":" + h).equals(nativeToolSizes.put(id, w + ":" + h))) {
			switch (tool.kind) {
				case FILES -> fileOverlay.resize(w,h); case DATAPACK_FILES -> datapackOverlay.resize(w,h);
				case RESOURCE_FILES -> resourceDirectoryOverlay.resize(w,h); case RESOURCE_JSON -> resourceJsonOverlay.resize(w,h); case RESOURCE_PNG -> resourcePngOverlay.resize(w,h);
				case DATAPACK_JSON_EDITOR -> datapackJsonEditorOverlay.resize(w,h); case COMPONENT_EDITOR -> componentEditorOverlay.resize(w,h);
				case TEXT_JSON -> textJsonOverlay.resize(w,h); case MINECRAFT_UI -> minecraftUiEditorOverlay.resize(w,h);
				case INSERT -> insertOverlay.resize(w,h); case API_DOCS -> apiDocsOverlay.resize(w,h); case VARIABLES -> variableOverlay.resize(w,h);
				case GIT -> gitPanel.resize(w,h); case PERSONALIZATION -> personalizationPanel.resize(w,h); case EXPORT -> exportPanel.resize(w,h);
				case EXTENSION -> extensionWindows.get(tool).resize(w,h); default -> { }
			}
		}
		setToolDock(tool, DockSide.LEFT); setSelectedDockTool(DockSide.LEFT, tool);
		WindowBoundsAnimation.immediate(() -> {
			if (tool == ToolWindow.MANUAL) { manualWindowX = 0; manualWindowY = 0; manualWindowWidth = w; manualWindowHeight = h; manualProgress = 1; manualBoundsAnimation.snapTo(0,0,w,h); }
			else if (tool == ToolWindow.OUTPUT) { outputWindowX = 0; outputWindowY = 0; outputWindowWidth = w; outputWindowHeight = h; outputProgress = 1; outputBoundsAnimation.snapTo(0,0,w,h); }
			else setDockBounds(tool, 0, 0, w, h);
		});
		if (!input) return;
		focusedSettingsPanel = tool == ToolWindow.GIT ? gitPanel : tool == ToolWindow.PERSONALIZATION ? personalizationPanel : tool == ToolWindow.EXPORT ? exportPanel : null;
		focusExtensionWindow(tool.kind == ToolWindow.Kind.EXTENSION ? extensionWindows.get(tool) : null);
		if (tool == ToolWindow.COMPONENT_EDITOR) componentEditorOverlay.requestKeyboardFocus();
		if (tool == ToolWindow.DATAPACK_JSON_EDITOR) datapackJsonEditorOverlay.requestKeyboardFocus();
		if (tool == ToolWindow.TEXT_JSON) textJsonOverlay.requestKeyboardFocus();
		if (tool == ToolWindow.MINECRAFT_UI) minecraftUiEditorOverlay.requestKeyboardFocus();
		if (tool == ToolWindow.INSERT) insertOverlay.requestKeyboardFocus();
		if (tool == ToolWindow.FILES) fileOverlay.requestKeyboardFocus();
		if (tool == ToolWindow.DATAPACK_FILES) datapackOverlay.requestKeyboardFocus();
		if (resourceTool(tool) != null) resourceTool(tool).requestKeyboardFocus();
		manualKeyboardFocused = tool == ToolWindow.MANUAL; variableWindowFocused = tool == ToolWindow.VARIABLES;
		outputKeyboardFocused = tool == ToolWindow.OUTPUT;
	}
	void nativeRender(String id, GuiGraphicsExtractor graphics, int mx, int my) {
		if (id.equals("CODE")) {
			if (currentDocument != null && currentDocument.binary()) {
				if (currentDocument.pngCanvas != null) currentDocument.pngCanvas.render(graphics, font, EDITOR_OUTER_X, WORKSPACE_TOP, width - 12, height, mx, my, true);
				else if (currentDocument.preview != null) currentDocument.preview.render(graphics, font, minecraft, EDITOR_OUTER_X, WORKSPACE_TOP, width - 12, height);
				return;
			}
			graphics.fill(EDITOR_OUTER_X, editor.getY(), editor.getRight(), editor.getBottom(), theme.background());
			LuaEditorBackground.render(graphics, minecraft, personalization, editor.getX(), editor.getY(), editor.getRight(), editor.getBottom());
			graphics.fill(EDITOR_OUTER_X, editor.getY(), editor.getX() - 1, editor.getBottom(), lineNumberBackground());
			LuaEditorViewport.extractOccurrences(graphics, editor);
			editor.extractRenderState(graphics, mx, my, 0);
			LuaEditorViewport.extractHorizontalScrollbar(graphics, editor, mx, my);
			var field = textField(); extractLineNumbers(graphics, field); extractFunctionFoldSummaries(graphics, field);
			extractErrorHighlights(graphics, field); extractSearchHighlights(graphics, field); extractAiHighlights(graphics, field);
			extractPausedLine(graphics, field); extractSuggestions(graphics, field, mx, my); extractApiHover(graphics, field, mx, my);
			if (searchOpen) extractSearch(graphics, mx, my, 0);
			extractAiHover(graphics, field, mx, my);
			if (editorContextMenu.isOpen()) editorContextMenu.render(graphics, font, mx, my);
		} else {
			ToolWindow tool = ToolWindow.valueOf(id); extractToolWindow(tool, graphics, mx, my);
			if (tool == ToolWindow.FILES) fileOverlay.renderPopup(graphics, mx, my);
			if (tool == ToolWindow.DATAPACK_FILES) datapackOverlay.renderPopup(graphics, mx, my);
			if (tool == ToolWindow.RESOURCE_FILES) resourceDirectoryOverlay.renderDirectoryPopup(graphics, mx, my);
			if (tool == ToolWindow.INSERT) insertOverlay.updateSoundPreview(mx, my, true, true);
		}
	}
	/** Native frontend dispatch. Never invoke extractToolWindow or any Minecraft widget renderer. */
	void nativeDrawToolImGui(String id) {
		ToolWindow tool=ToolWindow.valueOf(id);
		switch(tool.kind){
			case FILES->fileOverlay.renderImGui();case DATAPACK_FILES->datapackOverlay.renderImGui();
			case RESOURCE_FILES->resourceDirectoryOverlay.renderImGui();case RESOURCE_JSON->resourceJsonOverlay.renderImGui();case RESOURCE_PNG->resourcePngOverlay.renderImGui();
			case DATAPACK_JSON_EDITOR->datapackJsonEditorOverlay.renderImGui();case COMPONENT_EDITOR->componentEditorOverlay.renderImGui();case TEXT_JSON->textJsonOverlay.renderImGui();case MINECRAFT_UI->minecraftUiEditorOverlay.renderImGui();
			case INSERT->insertOverlay.renderImGui();case API_DOCS->apiDocsOverlay.renderImGui();case VARIABLES->variableOverlay.renderImGui();
			case GIT->{if(gitPanel!=null)gitPanel.renderImGui();}case PERSONALIZATION->{if(personalizationPanel!=null)personalizationPanel.renderImGui();}case EXPORT->{if(exportPanel!=null)exportPanel.renderImGui();}
			case EXTENSION->{var window=extensionWindows.get(tool);if(window!=null)window.renderImGui();}
			case MANUAL->nativeManualImGui();
			case OUTPUT->{
				NativeUi.action("复制全部",()->NativeUi.copy(String.join("\n",outputLines)));imgui.ImGui.sameLine();NativeUi.action("清空日志",outputLines::clear);
				NativeUi.readOnly("##logs",String.join("\n",outputLines),Math.max(80,imgui.ImGui.getContentRegionAvailY()));
			}
		}
	}
	private void nativeManualImGui(){
		int lastBlock=-1,index=0;
		for(var line:manualDocument.lines()){
			imgui.ImGui.pushID(index++);
			if(line.kind()==MarkdownManualDocument.Kind.CODE){
				if(line.codeBlockIndex()!=lastBlock){lastBlock=line.codeBlockIndex();var block=manualDocument.codeBlocks().get(lastBlock);
					NativeUi.action("插入",()->nativeInsert(block.source()));imgui.ImGui.sameLine();NativeUi.action("复制",()->NativeUi.copy(block.source()));
					NativeUi.code("##example",block.source(),Math.max(65,Math.min(400,block.source().lines().count()*imgui.ImGui.getTextLineHeightWithSpacing()+15)));
				}
			}else if(line.kind()==MarkdownManualDocument.Kind.SPACE)imgui.ImGui.spacing();
			else {if(line.kind()==MarkdownManualDocument.Kind.HEADING||line.kind()==MarkdownManualDocument.Kind.SUBHEADING)imgui.ImGui.separator();NativeUi.readOnly("##paragraph",line.text(),Math.max(38,(float)Math.ceil(imgui.ImGui.calcTextSize(line.text()).x/Math.max(80,imgui.ImGui.getContentRegionAvailX()))*imgui.ImGui.getTextLineHeightWithSpacing()+12));}
			imgui.ImGui.popID();
		}
	}
	void nativeDrawBinaryImGui(){if(currentDocument!=null){if(currentDocument.pngCanvas!=null)currentDocument.pngCanvas.renderImGui();else if(currentDocument.preview!=null)currentDocument.preview.renderImGui(minecraft);}}
	NativeDocument nativeSnapshot() {
		if (currentDocument == null) return null;
		rememberCurrentDocument();
		return new NativeDocument(currentDocument.name, currentDocument.json, currentDocument.resource,
				currentDocument.source, currentDocument.savedSource, currentDocument.cursor, currentDocument.binary());
	}
	void nativeBind(ImGuiIdeDocument document) {
		String key = (document.kind == ImGuiIdeDocument.Kind.LUA ? "lua:" : document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK ? "resourcepack:" : "datapack:") + document.path;
		if (document.binary()) {
			if (currentDocument == null || !currentDocument.key().equals(key)) {
				nativeOpening = true;
				try { if (document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK) openResourcePackFile(document.path); else openDatapackFile(document.path); }
				finally { nativeOpening = false; }
			}
			return;
		}
		if (currentDocument == null || !currentDocument.key().equals(key)) {
			rememberCurrentDocument();
			currentDocument = openDocuments.computeIfAbsent(key, ignored -> new OpenDocument(document.path, !document.lua(), document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK));
			currentDocument.loaded = true; currentDocument.source = document.source; currentDocument.savedSource = document.saved;
			currentDocument.cursor = document.restoreOffset; sourceLoaded = true; scriptName = document.path;
			if (document.restoredMetadata != null) {
				currentDocument.pendingFolds = document.restoredMetadata.folds(); currentDocument.scroll = document.restoredMetadata.scroll();
				document.restoredMetadata = null;
			}
			LuaSyntaxHighlighter.setJsonMode(editor, !document.lua() && !document.path.endsWith(".mcfunction"));
			LuaSyntaxHighlighter.setFunctionMode(editor, document.path.endsWith(".mcfunction"));
			LuaFunctionFolding.bind(textField(), document.lua() ? currentDocument.functionFolds : null);
			clearSuggestions();
			restoreCurrentDocument();
			fileOverlay.setCurrentScript(document.lua() ? document.path : "");
			datapackOverlay.setCurrentScript(document.kind == ImGuiIdeDocument.Kind.DATAPACK ? document.path : "");
			if (document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK) resourceJsonOverlay.setJsonSource(document.source);
		} else if (!textField().value().equals(document.source)) {
			editor.setValue(document.source); currentDocument.source = document.source;
		}
		currentDocument.savedSource = document.saved;
		lastSavedSource = document.saved;
	}
	int nativeCursor() { return editor == null ? 0 : textField().cursor(); }
	void nativeReplace(int start, int end, String text) {
		nativeSelect(start, end); textField().insertText(text);
	}
	void nativeSelect(int start, int end) {
		var field = textField(); int size = field.value().length(); field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, Math.clamp(start,0,size));
		field.setSelecting(true); field.seekCursor(Whence.ABSOLUTE, Math.clamp(end,0,size)); field.setSelecting(false);
	}
	int nativeAnchor(){return ((MultilineTextFieldAccessor)textField()).mcLuaIde$getSelectCursor();}
	Component nativeHighlight(int start,int end,int line){return LuaSyntaxHighlighter.highlight(editor,textField().value(),textField().value().substring(start,end),start,line);}
	LuaFunctionFolding.State nativeFolds(){return currentDocument==null||currentDocument.json?null:currentDocument.functionFolds;}
	void nativeToggleFold(int line){LuaFunctionFolding.toggle(textField(),line);}
	void nativeToggleBreakpoint(int line){toggleBreakpoint(line);}
	/** Conditions, hit counts and log messages of the breakpoints in the current document, by line. */
	Map<Integer, wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints.Option> nativeBreakpointOptions() {
		return isBreakpointDocument() ? breakpointStore.options(currentDocument.name) : Map.of();
	}
	/** Sets a breakpoint's conditions (creating the breakpoint when needed); false with a message in the status on failure. */
	boolean nativeSetBreakpointOption(int line, wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints.Option option) {
		if (!isBreakpointDocument()) { status = "断点只适用于已加载的 Lua 脚本"; return false; }
		try {
			breakpointStore.setOption(currentDocument.name, line, option, editor.getValue());
			LuaBreakpointClient.changed();
			status = "已更新断点：" + currentDocument.name + ".lua:" + line;
			return true;
		} catch (IllegalStateException error) { status = error.getMessage(); return false; }
	}
	/** Name (without extension) of the current document, as the server knows it. */
	String nativeDocumentName() { return currentDocument == null ? "" : currentDocument.name; }
	Set<Integer> nativeBreakpoints(){return isBreakpointDocument()?breakpointStore.lines(currentDocument.name):Set.of();}
	int nativePausedLine(){return pausedLine();}
	List<LuaAiAgent.Mark> nativeMarks(){if(currentDocument==null)return List.of();var marks=new ArrayList<>(ai.marks(aiPath(currentDocument),textField().value()));if(textField().value().equals(currentDocument.mcpSource))marks.addAll(currentDocument.mcpMarks);return marks;}
	void nativeDismissMarks(){ai.clearMarks();clearMcpMarks();}
	void nativeCodeKey(KeyEvent event){nativePrepare("CODE",900,600,true);clearSuggestions();keyPressed(event);}
	void nativeCodeCharacter(CharacterEvent event){nativePrepare("CODE",900,600,true);charTyped(event);}
	record NativeCompletion(String insertion,String label,String tooltip){}
	List<NativeCompletion> nativeCompletions(){
		nativePrepare("CODE",900,600,true);refreshSuggestions();return suggestions.stream().map(value->{
			var choice=argumentSuggestionChoices.getOrDefault(value,moduleSuggestionChoices.get(value));
			return new NativeCompletion(value,suggestionLabel(value),choice!=null?choice.tooltip():functionSuggestionTooltips.getOrDefault(value,""));
		}).toList();
	}
	void nativeAcceptCompletion(String value){nativePrepare("CODE",900,600,true);refreshSuggestions();int index=suggestions.indexOf(value);if(index>=0){selectedSuggestion=index;applySelectedSuggestion();}}
	void nativeSmartSelect(int offset){
		var field=textField();String source=field.value();if(source.isEmpty())return;offset=Math.clamp(offset,0,source.length()-1);
		int start=offset,end=offset+1;char current=source.charAt(offset);int[] quoted=quotedRangeAt(source,offset);
		if(quoted!=null){start=current==source.charAt(quoted[0])?quoted[0]:quoted[0]+1;end=current==source.charAt(quoted[1]-1)?quoted[1]:quoted[1]-1;}
		else if(isEditorIdentifierCharacter(current)){while(start>0&&isEditorIdentifierCharacter(source.charAt(start-1)))start--;while(end<source.length()&&isEditorIdentifierCharacter(source.charAt(end)))end++;}
		else if(Character.isWhitespace(current)){while(start>0&&source.charAt(start-1)!='\n'&&Character.isWhitespace(source.charAt(start-1)))start--;while(end<source.length()&&source.charAt(end)!='\n'&&Character.isWhitespace(source.charAt(end)))end++;}
		else if(isOpeningDelimiter(current)||isClosingDelimiter(current)){int match=matchingDelimiter(source,offset);if(match>=0){start=Math.min(offset,match);end=Math.max(offset,match)+1;}}
		nativeSelect(start,end);
	}
	void nativeInsert(String text) { textField().insertText(text); }
	boolean nativeDirty(String name, boolean json, boolean resource) {
		var doc = openDocuments.get((resource ? "resourcepack:" : json ? "datapack:" : "lua:") + name);
		return doc != null && doc.dirty();
	}
	CompletableFuture<Void> nativeSave() { rememberCurrentDocument(); return currentDocument == null ? CompletableFuture.completedFuture(null) : saveDocumentForClose(currentDocument); }
	String nativeStatus() { return status; }
	LuaPersonalizationSettings nativePersonalization(){return personalization;}
	boolean nativeShortcutTool(String id){return !id.startsWith("NATIVE_")&&shortcutOpenedTools.contains(ToolWindow.valueOf(id));}
	void nativeOpenApi(String symbol){apiDocsOverlay.openAt(symbol);nativeActivatedTool="API_DOCS";}
	void nativeDebugMode(boolean debug) { nativeDebugRun = debug; }
	void nativeRun(boolean debug) { nativeDebugRun = debug; toggleRun(); }
	boolean nativeResizeLocked() { return personalization.resizeDockWhenLocked; }
	String nativeTakeActivatedTool() { String tool = nativeActivatedTool; nativeActivatedTool = null; return tool; }
	LuaIdeSession.Document nativeMetadata(ImGuiIdeDocument document) {
		rememberCurrentDocument();
		var doc = openDocuments.get((document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK ? "resourcepack:" : document.lua() ? "lua:" : "datapack:") + document.path);
		return doc == null ? new LuaIdeSession.Document(document.path, !document.lua(), document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK, document.restoreOffset, 0)
				: new LuaIdeSession.Document(doc.name, doc.json, doc.resource, doc.cursor, doc.scroll, doc.pendingFolds != null ? doc.pendingFolds : doc.functionFolds.snapshot(doc.source));
	}
	void nativeForget(ImGuiIdeDocument document, boolean discard) {
		var key = (document.kind == ImGuiIdeDocument.Kind.RESOURCEPACK ? "resourcepack:" : document.lua() ? "lua:" : "datapack:") + document.path;
		var doc = openDocuments.remove(key);
		if (doc != null) {
			if (doc.preview != null) doc.preview.close(minecraft);
			if (doc.pngCanvas != null) doc.pngCanvas.close();
			if (discard) clearRecoveryDraft(key);
			if (currentDocument == doc) { currentDocument = null; sourceLoaded = false; editor.setValue(""); }
		}
	}
	void nativeDispose() {
		ai.cancel(false); snapshotRecovery();
		extensionWindows.values().forEach(LuaExtensionWindow::dispose); LuaOggPreviewPlayer.stop();
		for (var document : openDocuments.values()) { if (document.preview != null) document.preview.close(minecraft); if (document.pngCanvas != null) document.pngCanvas.close(); }
	}
	void nativeDiscardAll() {
		recoveryClosed = true;
		for (String key : List.copyOf(recoveryTracking.keySet())) clearRecoveryDraft(key);
	}
	void nativeCommand(String command) {
		if (command.startsWith("font:")) { applyScale(ScaleTarget.FONT, Integer.parseInt(command.substring(5))); return; }
		if (command.startsWith("theme:")) { var selected = LuaSyntaxHighlighter.Theme.valueOf(command.substring(6)); setTheme(selected, selected.name()); return; }
		switch (command) {
			case "format" -> formatCurrentCode(); case "reload_resources" -> reloadClientResourcePacks(); case "reload_datapacks" -> reloadServerDatapacks();
			case "settings" -> nativeOwner.openServerSettings();
			case "expand_all" -> applyCodeFolding(LuaFunctionFolding.Action.EXPAND_ALL);
			case "fold_all" -> applyCodeFolding(LuaFunctionFolding.Action.COLLAPSE_ALL);
			case "fold_functions" -> applyCodeFolding(LuaFunctionFolding.Action.COLLAPSE_FUNCTIONS);
			case "search" -> openSearchFromSelection();
		}
	}

	private static final class OpenDocument {
		String name;
		final boolean json;
		final boolean resource;
		LuaBinaryPreview preview;
		LuaResourcePngCanvas pngCanvas;
		String source = "";
		String savedSource = "";
		String lastSnapshot = "";
		boolean loaded;
		boolean loading;
		long readRevision;
		long mcpRevision;
		String mcpSource = "";
		List<LuaAiAgent.Mark> mcpMarks = List.of();
		int cursor;
		double scroll;
		final Deque<String> undo = new ArrayDeque<>();
		final Deque<String> redo = new ArrayDeque<>();
		final LuaFunctionFolding.State functionFolds = new LuaFunctionFolding.State();
		LuaFunctionFolding.Snapshot pendingFolds;

		OpenDocument(String name, boolean json, boolean resource) {
			this.name = name;
			this.json = json;
			this.resource = resource;
		}

		String key() { return (resource ? "resourcepack:" : json ? "datapack:" : "lua:") + name; }
		boolean binary() { return json && (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".nbt")); }
		boolean dirty() { return pngCanvas != null && pngCanvas.document().dirty()
				|| loaded && !binary() && !source.equals(savedSource); }
		String displayName() { return json ? name : name + ".lua"; }
		String tabLabel() {
			if (!json) return displayName();
			int slash = name.lastIndexOf('/');
			return (slash < 0 ? name : name.substring(slash + 1)) + " · "
					+ (resource ? "resourcepacks/" : "datapack/") + name.split("/", 2)[0];
		}
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

	private record OutputVisualLine(String text, int color, int start, int end) {
	}

	private record ManualTextSpan(int x, int y, String text, int start, int end) {
	}
}
