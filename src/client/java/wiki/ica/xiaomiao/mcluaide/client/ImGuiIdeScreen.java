package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.*;
import imgui.type.*;
import net.fabricmc.loader.api.FabricLoader;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** First independent UI frontend. All server actions reuse the existing permission-checked transport. */
public final class ImGuiIdeScreen extends Screen implements NativeUiScreen, ExtensionWindowHost, EditorHost {
    private final Screen previous;
    private final Object connection;
    private final LuaIdeSession session;
    private final LuaFileRecovery recovery;
    private final Path layoutFile;
    private final LinkedHashMap<String, ImGuiIdeDocument> documents = new LinkedHashMap<>();
    private final Map<ImGuiIdeDocument.Kind, List<ScriptManager.ScriptEntry>> trees = new EnumMap<>(ImGuiIdeDocument.Kind.class);
    private final Map<String, ImBoolean> windows = new LinkedHashMap<>();
    private final Properties preferences;
    private ImGuiToolWorkspace tools;
    private boolean suspended;
    private final Map<String, Boolean> pendingTools = new LinkedHashMap<>();
    private final ImInt interfacePercent = new ImInt(100), codePercent = new ImInt(100);
    float uiScale() { return interfacePercent.get() / 100f; }
    boolean layoutLocked() { return lockLayout.get(); }
    LuaIdeSession nativeSession() { return session; }
    ImGuiIdeDocument acceptToolDocument(LuaIdeScreen.NativeDocument snapshot) {
        var kind = snapshot.resource() ? ImGuiIdeDocument.Kind.RESOURCEPACK : snapshot.json() ? ImGuiIdeDocument.Kind.DATAPACK : ImGuiIdeDocument.Kind.LUA;
        var doc = documents.computeIfAbsent(kind + ":" + snapshot.name(), ignored -> new ImGuiIdeDocument(kind, snapshot.name(), snapshot.source()));
        doc.update(snapshot.source()); doc.saved = snapshot.saved(); doc.restoreOffset = snapshot.cursor();
        current = selectTab = doc; show("editor");
        return doc;
    }
    void datapackRenamed(LuaFileOverlay.RenameResult renamed) {
        for (var doc : List.copyOf(documents.values())) if (doc.kind == ImGuiIdeDocument.Kind.DATAPACK && (doc.path.equals(renamed.oldPath()) || renamed.directory() && doc.path.startsWith(renamed.oldPath() + "/"))) {
            documents.remove(doc.key()); doc.path = renamed.newPath() + doc.path.substring(renamed.oldPath().length()); documents.put(doc.key(), doc);
        }
    }
    void openServerSettings() { openTool("NATIVE_SETTINGS"); }
    LuaIdeScreen controller() { return tools == null ? null : tools.controller; }
    @Override public void openTool(String id) { if (tools != null) tools.open(id); else pendingTools.put(id, true); }
    @Override public boolean insertText(String text) { return tools != null && tools.editorContext().insertText(text); }

    // ---- EditorHost: what the outline, search, compare and palette panels use ----------------------------------------------
    private final CommandPalette palette = new CommandPalette(new PaletteSource());
    private String jumpKey = ""; private int jumpStart, jumpEnd;
    @Override public ImGuiIdeDocument currentDocument() { return current; }
    @Override public List<ImGuiIdeDocument> openDocuments() { return List.copyOf(documents.values()); }
    @Override public NativeCodeArea codeArea() { return tools.codeArea; }
    @Override public List<ScriptManager.ScriptEntry> tree(ImGuiIdeDocument.Kind kind) { return trees.getOrDefault(kind, List.of()); }
    @Override public void openAt(ImGuiIdeDocument.Kind kind, String path, int start, int end) {
        jumpKey = kind.name() + ":" + path; jumpStart = start; jumpEnd = end;
        open(kind, path, true);
    }
    @Override public <T> void deliver(CompletableFuture<T> future, java.util.function.Consumer<T> success, java.util.function.Consumer<String> failure) {
        future.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
            if (!validConnection()) return;
            if (error != null) failure.accept(ScriptManager.message(error)); else success.accept(value);
        }));
    }
    @Override public CompletableFuture<String> read(ImGuiIdeDocument.Kind kind, String path) {
        return kind == ImGuiIdeDocument.Kind.LUA ? ScriptManager.read(path)
                : kind == ImGuiIdeDocument.Kind.DATAPACK ? ScriptManager.readDatapackFile(path) : local(() -> LuaResourcePackWorkspace.readText(path));
    }
    @Override public CompletableFuture<Void> write(ImGuiIdeDocument.Kind kind, String path, String text) {
        CompletableFuture<Void> saved = kind == ImGuiIdeDocument.Kind.LUA ? ScriptManager.save(path, text)
                : kind == ImGuiIdeDocument.Kind.DATAPACK ? ScriptManager.saveDatapackFile(path, text)
                : local(() -> { LuaResourcePackWorkspace.saveText(path, text); return null; });
        return saved.thenApply(ignored -> {
            // A file that was rewritten on disk must not leave a stale copy in an open tab.
            Minecraft.getInstance().execute(() -> {
                var open = documents.get(kind.name() + ":" + path);
                if (open != null && validConnection()) { open.external(text); open.saved = text; }
            });
            return null;
        });
    }
    @Override public void notice(String message) { notice = message; }

    /** Items for the command palette: files, symbols of the current file and every IDE command. */
    private final class PaletteSource implements CommandPalette.Source {
        @Override public List<CommandPalette.Entry> files() {
            List<CommandPalette.Entry> entries = new ArrayList<>();
            for (var kind : ImGuiIdeDocument.Kind.values()) for (var entry : trees.getOrDefault(kind, List.of())) {
                if (entry.directory()) continue;
                String name = entry.path() + (kind == ImGuiIdeDocument.Kind.LUA ? ".lua" : "");
                String detail = kind == ImGuiIdeDocument.Kind.LUA ? "Lua" : kind == ImGuiIdeDocument.Kind.DATAPACK ? "datapack" : "resourcepack";
                entries.add(new CommandPalette.Entry(name, detail, () -> open(kind, entry.path(), true)));
            }
            return entries;
        }
        @Override public List<CommandPalette.Entry> symbols() {
            var index = tools == null ? null : tools.codeArea.symbolIndex();
            if (index == null || current == null) return List.of();
            List<CommandPalette.Entry> entries = new ArrayList<>();
            for (var symbol : index.outline()) {
                int line = LuaSymbolIndex.lineOf(current.source, symbol.offset()) + 1;
                entries.add(new CommandPalette.Entry(symbol.name(), ":" + line, () -> tools.codeArea.goToOffset(symbol.offset())));
            }
            return entries;
        }
        @Override public List<CommandPalette.Entry> commands() { return paletteCommands(); }
        @Override public void goToLine(int line) { if (tools != null) tools.codeArea.goToLineNumber(line); }
    }

    private List<CommandPalette.Entry> paletteCommands() {
        List<CommandPalette.Entry> list = new ArrayList<>();
        var area = tools == null ? null : tools.codeArea;
        java.util.function.BiConsumer<String[], Runnable> add = (names, action) -> list.add(new CommandPalette.Entry(tr(names[0], names[1]), names[2], action));
        add.accept(new String[]{"保存", "Save", "Ctrl+S"}, () -> { if (current != null) save(current); });
        add.accept(new String[]{"全部保存", "Save all", ""}, this::saveAll);
        add.accept(new String[]{"新建 Lua 文件…", "New Lua file…", "Ctrl+N"}, () -> createDialog(ImGuiIdeDocument.Kind.LUA, "file", ""));
        add.accept(new String[]{"运行 / 停止", "Run / stop", "Ctrl+Enter"}, this::toggleRun);
        add.accept(new String[]{"整理代码", "Format code", ""}, () -> tools.command("format"));
        add.accept(new String[]{"查找", "Find", "Ctrl+F"}, () -> area.find());
        add.accept(new String[]{"替换", "Replace", "Ctrl+H"}, () -> area.findAndReplace());
        add.accept(new String[]{"转到行…", "Go to line…", "Ctrl+G"}, () -> area.goToLine());
        add.accept(new String[]{"转到定义", "Go to definition", "Ctrl+B"}, () -> area.goToDefinition());
        add.accept(new String[]{"查找引用", "Find references", "Ctrl+Shift+B"}, () -> area.findReferences());
        add.accept(new String[]{"重命名符号…", "Rename symbol…", "F2"}, () -> area.requestRename());
        add.accept(new String[]{"选中下一处相同内容", "Add next occurrence", "Ctrl+D"}, () -> area.addNextOccurrence());
        add.accept(new String[]{"在文件中查找…", "Find in files…", "Ctrl+Shift+F"}, () -> { openTool("NATIVE_SEARCH"); tools.searchPanel().focusQuery(); });
        add.accept(new String[]{"对比文件…", "Compare files…", ""}, () -> openTool("NATIVE_DIFF"));
        add.accept(new String[]{"显示大纲", "Show outline", ""}, () -> openTool("NATIVE_OUTLINE"));
        add.accept(new String[]{"切换终端", "Toggle terminal", "Ctrl+`"}, this::toggleTerminal);
        add.accept(new String[]{"查看审计日志", "Show audit log", ""}, () -> openTool(AuditLogPanel.ID));
        add.accept(new String[]{"备份与恢复", "Backups", ""}, () -> openTool(BackupPanel.ID));
        add.accept(new String[]{"命令参考", "Command reference", ""}, () -> openTool(CommandReferencePanel.ID));
        add.accept(new String[]{"拆分编辑器", "Split editor", ""}, () -> openTool(SplitEditorPane.ID));
        add.accept(new String[]{"欢迎页与示例项目", "Welcome and sample project", ""}, () -> openTool(WelcomePanel.ID));
        add.accept(new String[]{"新建本地终端", "New local terminal", "Ctrl+Shift+T"}, () -> { openTool(TerminalPanel.ID); try { TerminalPanel.openLocal(""); } catch (RuntimeException error) { notice(error.getMessage()); } });
        add.accept(new String[]{"新建服务器终端", "New server terminal", ""}, () -> { openTool(TerminalPanel.ID); try { TerminalPanel.openServer(); } catch (RuntimeException error) { notice(error.getMessage()); } });
        add.accept(new String[]{"显示 / 隐藏小地图", "Toggle minimap", ""}, () -> area.setMinimapVisible(!area.minimapVisible()));
        add.accept(new String[]{"展开全部代码", "Expand all", ""}, () -> tools.command("expand_all"));
        add.accept(new String[]{"折叠全部代码", "Collapse all", ""}, () -> tools.command("fold_all"));
        add.accept(new String[]{"折叠函数代码", "Collapse functions", ""}, () -> tools.command("fold_functions"));
        add.accept(new String[]{"重载数据包", "Reload datapacks", ""}, () -> tools.command("reload_datapacks"));
        add.accept(new String[]{"重载资源包", "Reload resource packs", ""}, () -> tools.command("reload_resources"));
        add.accept(new String[]{"刷新目录", "Refresh explorer", ""}, this::refreshTrees);
        add.accept(new String[]{"深色配色", "Dark theme", ""}, () -> tools.command("theme:DARK"));
        add.accept(new String[]{"浅色配色", "Light theme", ""}, () -> tools.command("theme:LIGHT"));
        add.accept(new String[]{"高对比度配色", "High contrast theme", ""}, () -> tools.command("theme:HIGH_CONTRAST"));
        add.accept(new String[]{"固定 / 解除窗口布局", "Lock / unlock layout", ""}, () -> lockLayout.set(!lockLayout.get()));
        add.accept(new String[]{"恢复默认布局", "Reset layout", ""}, () -> resetLayout = true);
        add.accept(new String[]{"重新加载扩展清单", "Reload extension manifests", ""}, () -> notice(ExtensionManifests.reload().summary()));
        add.accept(new String[]{"显示扩展状态", "Show extension status", ""}, () -> notice(ExtensionManifests.last().summary()));
        for (var command : McLuaIdeEditorApi.commands())
            list.add(new CommandPalette.Entry(command.title().getString(), command.shortcutHint().isEmpty() ? command.ownerModId() : command.ownerModId() + " · " + command.shortcutHint(), () -> runExtension(command)));
        if (tools != null) for (String id : tools.ids()) {
            String title = tools.title(id);
            list.add(new CommandPalette.Entry(tr("打开窗口：", "Open window: ") + title, "", () -> tools.open(id)));
        }
        return list;
    }

    /** Runs an extension command; a failing command never takes the IDE down. */
    void runExtension(McLuaIdeEditorApi.Command command) {
        if (tools == null) return;
        var context = tools.editorContext();
        try {
            if (!command.enabled().test(context)) { notice(tr("此命令现在不可用：", "Command is not available now: ") + command.title().getString()); return; }
            command.action().accept(context);
        } catch (Throwable error) {
            McLuaIdeMod.LOGGER.error("Editor command {} failed", command.id(), error);
            notice(tr("扩展命令失败：", "Extension command failed: ") + command.id());
        }
    }
    public boolean setExtensionWindowOpen(String id, boolean open) {
        if (tools != null) {
            boolean known = tools.ids().contains("EXT:" + id); if (!known) return false;
            if (open) tools.open("EXT:" + id); else tools.close("EXT:" + id); return true;
        }
        if (wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.definitions().stream().noneMatch(definition -> definition.id().equals(id))) return false;
        pendingTools.put("EXT:" + id, open); return true;
    }
    boolean handleAltFunctionClose(int modifiers) { return tools != null && tools.controller.handleAltFunctionClose(modifiers); }
    @Override protected void init() { suspended = false; }
    /** Double-click in the game viewport: hand the keyboard and mouse to the game. The screen closes after this frame. */
    boolean enterGameControl() {
        var client = Minecraft.getInstance();
        if (!GameControl.enter(this)) { notice = tr("进入世界后才能直接控制游戏", "Join a world to control the game directly"); return false; }
        suspended = true;
        ImGuiIdeRuntime.defer(() -> { if (GameControl.active()) client.setScreen(null); });
        return true;
    }
    /** The world was left while controlling; release everything the suspended screen still holds. */
    void abandonGameControl() { suspended = false; removed(); }
    private final ImString apiFilter = new ImString(512), fileFilter = new ImString(256), idFilter = new ImString(256);
    private final ImString newPath = new ImString(512);
    private final ImBoolean phonetic = new ImBoolean(true), debugRun = new ImBoolean(true), autoLog = new ImBoolean(true);
    private final ImBoolean lockLayout = new ImBoolean(false);
    private final float[] fontSize = {17};
    private ImGuiIdeDocument current, selectTab, closeDocument;
    private String notice = "", apiSymbol = "MC.apiVersion", namespace = "", pendingDialog = "";
    private String focusNext = "";
    private final Map<String, LuaIdeSession.Document> restoreDocuments = new HashMap<>();
    private String restoreActive = "";
    private final Set<String> docked = new HashSet<>();
    private String variables = "";
    private TextView variableView, logView, manualView, apiExample;
    private String apiExampleSymbol = "", apiExampleText = "";
    private List<LuaInsertCatalog.Entry> ids = List.of(), filteredIds = List.of();
    private LuaInsertCatalog.Kind insertKind = LuaInsertCatalog.Kind.ITEM;
    private ImGuiIdeDocument insertTarget;
    private LuaCursorContext.Parameter insertParameter;
    private int idSelection, idRequest, frameCount;
    private long nextVariables, nextRecovery;
    private boolean variablePending, initializedUi, disposed, closeRequested, switchClassic, savingClose, resetLayout;
    private boolean idShortcut, completionOpen, completionRequested;
    private List<String> completions = List.of();
    private int completionIndex;
    private String completionKey = "";
    private boolean completionHovered;
    private int completionStart, completionEnd;
    private final Map<String, LuaArgumentCompletion.Choice> completionDetails = new HashMap<>();
    private final Map<String, List<LuaInsertCatalog.Entry>> completionCatalogs = new HashMap<>();
    private final Set<String> catalogRequests = new HashSet<>();
    private final LuaModuleCompletion modules = new LuaModuleCompletion();
    private final Map<String, String> moduleSources = new LinkedHashMap<>();
    private final Set<String> moduleRequests = new HashSet<>();
    private ImGuiIdeDocument.Kind creationKind = ImGuiIdeDocument.Kind.LUA;
    private String creationAction = "file", deletePath = "";
    private ImGuiIdeDocument.Kind deleteKind;
    private static final int EDITOR_FLAGS = ImGuiWindowFlags.NoCollapse;

    public ImGuiIdeScreen(Screen previous, String script) {
        super(Component.literal("MC Lua IDE · ImGui"));
        this.previous = previous;
        var client = Minecraft.getInstance(); connection = client.getConnection();
        var server = client.getSingleplayerServer();
        String scope = server != null ? "world:" + server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                : client.getCurrentServer() != null ? "server:" + client.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "local";
        if (client.player != null) scope += ":player:" + client.player.getUUID();
        Path root = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide");
        session = new LuaIdeSession(root, "imgui:" + scope); recovery = LuaFileRecovery.forSession(session);
        layoutFile = session.file.resolveSibling(session.file.getFileName().toString().replace(".properties", "-imgui.ini"));
        preferences = LuaIdeSession.readProperties(layoutFile.resolveSibling(layoutFile.getFileName() + ".properties"));
        for (String window : List.of("files", "editor", "api", "insert", "logs", "variables", "manual", "settings", "problems", "recovery"))
            windows.put(window, new ImBoolean(Boolean.parseBoolean(preferences.getProperty(window,
                    Boolean.toString(Set.of("files", "editor", "api", "manual").contains(window))))));
        try { fontSize[0] = Math.clamp(Float.parseFloat(preferences.getProperty("font", "17")), 10, 80); } catch (NumberFormatException ignored) { }
        lockLayout.set(Boolean.parseBoolean(preferences.getProperty("locked", "false")));
        debugRun.set(Boolean.parseBoolean(preferences.getProperty("debug", "true")));
        try { interfacePercent.set(Math.clamp(Integer.parseInt(preferences.getProperty("scale", "100")), 10, 300)); } catch (NumberFormatException ignored) { }
        var saved = session.load();
        // Import only file metadata on the first launch. Sources always come from the authorized current server.
        if (saved.documents().isEmpty()) saved = new LuaIdeSession(root, scope).load();
        restoreActive = saved.active();
        for (var entry : saved.documents()) {
            restoreDocuments.put(entry.key(), entry);
            open(entry.resource() ? ImGuiIdeDocument.Kind.RESOURCEPACK : entry.json()
                    ? ImGuiIdeDocument.Kind.DATAPACK : ImGuiIdeDocument.Kind.LUA, entry.name(), false);
        }
        if (script != null && !script.isBlank()) open(ImGuiIdeDocument.Kind.LUA, script, true);
        if (!recovery.entries().isEmpty()) windows.get("recovery").set(true);
        refreshTrees();
    }
    private static String tr(String zh, String en) { return LuaUiText.text(zh, en); }
    private static String title(String id) {
        return switch (id) {
            case "files" -> tr("文件目录", "Explorer"); case "editor" -> tr("代码编辑器", "Code editor");
            case "api" -> tr("API 文档", "API documentation"); case "insert" -> tr("插入 ID", "Insert ID");
            case "logs" -> tr("运行日志", "Run log"); case "variables" -> tr("变量", "Variables");
            case "manual" -> tr("使用手册", "User manual"); case "settings" -> tr("个性化", "Personalization");
            case "problems" -> tr("代码问题", "Problems"); case "recovery" -> tr("文件恢复", "File recovery");
            default -> id;
        } + "###mc_lua_ide_" + id;
    }
    private void show(String id) {
        String tool = switch (id) { case "files" -> "FILES"; case "api" -> "API_DOCS"; case "insert" -> "INSERT";
            case "logs" -> "OUTPUT"; case "variables" -> "VARIABLES"; case "manual" -> "MANUAL";
            case "settings" -> "PERSONALIZATION"; case "recovery" -> "EXT:" + LuaFileRecoveryWindow.ID; default -> ""; };
        if (tools != null && !tool.isEmpty()) tools.open(tool);
        else { windows.get(id).set(true); focusNext = id; }
    }
    private boolean validConnection() { return !disposed && Minecraft.getInstance().getConnection() == connection; }
    private <T> void receive(CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
            if (!validConnection()) return;
            if (error != null) notice = ScriptManager.message(error);
            else success.accept(value);
        }));
    }
    private <T> CompletableFuture<T> local(java.util.concurrent.Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> { try { return work.call(); } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); } });
    }

    @Override public void drawUi() {
        if (disposed) return;
        if (!initializedUi) {
            initializedUi = true;
            if (Files.isRegularFile(layoutFile)) try {
                if (Files.size(layoutFile) < 1_000_000) ImGui.loadIniSettingsFromMemory(Files.readString(layoutFile));
            } catch (Exception error) { notice = ScriptManager.message(error); }
        }
        ImGui.pushFont(ImGui.getIO().getFontDefault(), fontSize[0] * uiScale());
        if (tools == null) {
            tools = new ImGuiToolWorkspace(this, preferences);
            tools.codeArea.onExtensionCommand(this::runExtension);
            crossFiles = new CrossFileActions(this, this::notice);
            tools.codeArea.crossFile(crossFiles);
            if (!WelcomePanel.shownBefore()) tools.open(WelcomePanel.ID);
            if (ExtensionManifests.loadOnce() && (!ExtensionManifests.last().loaded().isEmpty() || !ExtensionManifests.last().problems().isEmpty())) notice(ExtensionManifests.last().summary());
        }
        if (!pendingTools.isEmpty()) {
            pendingTools.forEach((id, open) -> { if (open) tools.open(id); else tools.close(id); }); pendingTools.clear();
        }
        tools.controller.nativeDebugMode(debugRun.get());
        // The whole UI follows the editor color scheme and eases between palettes.
        ImGuiIdeRuntime.setTheme(tools.controller.nativePersonalization().theme, !themeSeen); themeSeen = true;
        ImGuiIdeRuntime.animateTheme();
        tools.beginFrame();
        WindowChrome.update(tools.controller.nativePersonalization().integratedTitleBar);
        menuBar();
        int dock = ImGui.dockSpaceOverViewport(0, ImGui.getMainViewport(), lockLayout.get() && !tools.controller.nativeResizeLocked() ? ImGuiDockNodeFlags.NoResize : 0);
        if (resetLayout || !preferences.getProperty("workspace.version", "").equals("2") && frameCount == 0) {
            createLayout(dock); resetLayout = false;
        }
        ImGui.getIO().setConfigDockingNoSplit(lockLayout.get());
        for (var doc : List.copyOf(documents.values())) { doc.capture(); if (!doc.binary()) doc.analyze(); }
        drawEditor(); tools.draw();drawCompletions();
        drawProblems(); dialogs(); palette.draw();
        if (closeRequested) ImGui.openPopup("close_confirmation");
        closeRequested = false;
        closeDialog();
        conflictDialog();
        if (crossFiles != null) crossFiles.draw();
        motion.drawGhosts();
        ImGui.popFont();
    }

    private void menuBar() {
        if (!ImGui.beginMainMenuBar()) return;
        if (WindowChrome.active()) ImGui.setCursorPosX(ImGui.getCursorPosX() + WindowChrome.leftInset());
        if (ImGui.beginMenu(tr("文件", "File"))) {
            tools.menu("FILES"); tools.menu("DATAPACK_FILES"); tools.menu("RESOURCE_FILES"); ImGui.separator();
            if (ImGui.menuItem(tr("新建 Lua 文件…", "New Lua file…"), "Ctrl+N")) createDialog(ImGuiIdeDocument.Kind.LUA, "file", "");
            if (ImGui.menuItem(tr("保存", "Save"), "Ctrl+S", false, current != null)) save(current);
            if (ImGui.menuItem(tr("全部保存", "Save all"))) saveAll();
            ImGui.separator();
            if (ImGui.menuItem(tr("关闭", "Close"), "Esc")) requestClose(false, null);
            ImGui.endMenu();
        }
        if (ImGui.beginMenu(tr("编辑", "Edit"))) {
            boolean enabled = current != null;
            if (ImGui.menuItem(tr("撤销", "Undo"), "Ctrl+Z", false, enabled)) tools.editorShortcut(GLFW.GLFW_KEY_Z, false);
            if (ImGui.menuItem(tr("重做", "Redo"), "Ctrl+Shift+Z", false, enabled)) tools.editorShortcut(GLFW.GLFW_KEY_Z, true);
            if (ImGui.menuItem(tr("复制", "Copy"), "Ctrl+C", false, enabled)) tools.editorShortcut(GLFW.GLFW_KEY_C, false);
            if (ImGui.menuItem(tr("剪切", "Cut"), "Ctrl+X", false, enabled)) tools.editorShortcut(GLFW.GLFW_KEY_X, false);
            if (ImGui.menuItem(tr("粘贴", "Paste"), "Ctrl+V", false, enabled)) tools.editorShortcut(GLFW.GLFW_KEY_V, false);
            if (ImGui.menuItem(tr("搜索", "Find"), "Ctrl+F", false, enabled)) tools.command("search");
            if (ImGui.menuItem(tr("切换注释", "Toggle comments"), "Ctrl+/", false, enabled)) tools.editorShortcut(GLFW.GLFW_KEY_SLASH, false);
            ImGui.separator();
            if (ImGui.menuItem(tr("替换…", "Replace…"), "Ctrl+H", false, enabled)) tools.codeArea.findAndReplace();
            if (ImGui.menuItem(tr("在文件中查找…", "Find in files…"), "Ctrl+Shift+F")) { openTool("NATIVE_SEARCH"); tools.searchPanel().focusQuery(); }
            if (ImGui.menuItem(tr("转到行…", "Go to line…"), "Ctrl+G", false, enabled)) tools.codeArea.goToLine();
            if (ImGui.menuItem(tr("转到文件…", "Go to file…"), "Ctrl+P")) palette.open("");
            if (ImGui.menuItem(tr("转到符号…", "Go to symbol…"), "Ctrl+Shift+O", false, enabled)) palette.open("@");
            if (ImGui.menuItem(tr("命令面板…", "Command palette…"), "Ctrl+Shift+P")) palette.open(">");
            ImGui.separator();
            if (ImGui.menuItem(tr("转到定义", "Go to definition"), "Ctrl+B", false, enabled)) tools.codeArea.goToDefinition();
            if (ImGui.menuItem(tr("查找引用", "Find references"), "Ctrl+Shift+B", false, enabled)) tools.codeArea.findReferences();
            if (ImGui.menuItem(tr("重命名符号…", "Rename symbol…"), "F2", false, enabled)) tools.codeArea.requestRename();
            if (ImGui.menuItem(tr("选中下一处相同内容", "Add next occurrence"), "Ctrl+D", false, enabled)) tools.codeArea.addNextOccurrence();
            if (ImGui.menuItem(tr("显示小地图", "Show minimap"), "", tools.codeArea.minimapVisible())) tools.codeArea.setMinimapVisible(!tools.codeArea.minimapVisible());
            ImGui.endMenu();
        }
        if (ImGui.beginMenu(tr("UI与窗口", "UI & windows"))) {
            if (ImGui.beginMenu(tr("视图", "View"))) {
                ImGui.menuItem(tr("代码编辑器", "Code editor"), "", windows.get("editor"));
                ImGui.menuItem(tr("代码问题", "Problems"), "", windows.get("problems"));
                tools.allMenus();
                ImGui.endMenu();
            }
            ImGui.menuItem(tr("固定窗口布局", "Lock layout"), "", lockLayout);
            if (ImGui.menuItem(tr("恢复默认布局", "Reset layout"))) resetLayout = true;
            if (ImGui.menuItem(tr("个性化", "Personalization"))) show("settings");
            if (ImGui.beginMenu(tr("字号", "Font size"))) {
                for (int percent : new int[]{10,50,100,150,200,300}) if (ImGui.menuItem(percent + "%")) tools.command("font:" + percent);
                ImGui.setNextItemWidth(140); ImGui.inputInt("%##code_scale", codePercent);
                if (ImGui.button(tr("应用", "Apply") + "##code_scale")) tools.command("font:" + Math.clamp(codePercent.get(),10,300));
                ImGui.endMenu();
            }
            if (ImGui.beginMenu(tr("界面缩放", "Interface scale"))) {
                ImGui.setNextItemWidth(140);
                if (ImGui.inputInt("%##interface_scale", interfacePercent)) interfacePercent.set(Math.clamp(interfacePercent.get(),10,300));
                ImGui.endMenu();
            }
            if (ImGui.beginMenu(tr("配色", "Theme"))) {
                if (ImGui.menuItem(tr("深色", "Dark"))) tools.command("theme:DARK");
                if (ImGui.menuItem(tr("浅色", "Light"))) tools.command("theme:LIGHT");
                if (ImGui.menuItem(tr("高对比度", "High contrast"))) tools.command("theme:HIGH_CONTRAST");
                ImGui.endMenu();
            }
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Git")) { tools.menu("GIT"); ImGui.endMenu(); }
        if (ImGui.beginMenu(tr("工具", "Tools"))) {
            if (ImGui.menuItem(tr("整理代码", "Format code"), "", false, current != null)) {
                tools.command("format");
            }
            if (ImGui.menuItem(tr("刷新目录", "Refresh explorer"))) refreshTrees();
            if (ImGui.menuItem(tr("重载数据包", "Reload datapacks"))) tools.command("reload_datapacks");
            if (ImGui.menuItem(tr("重载资源包", "Reload resource packs"))) tools.command("reload_resources");
            tools.menu("EXT:" + LuaServerPackWindow.ID); tools.menu("EXPORT");
            if (ImGui.menuItem(tr("服务器配置…", "Server configuration…"))) tools.command("settings");
            tools.menu("EXT:" + LuaFileRecoveryWindow.ID);
            if (ImGui.beginMenu(tr("代码折叠", "Code folding"))) {
                if (ImGui.menuItem(tr("展开全部代码", "Expand all"))) tools.command("expand_all");
                if (ImGui.menuItem(tr("折叠全部代码", "Collapse all"))) tools.command("fold_all");
                if (ImGui.menuItem(tr("折叠函数代码", "Collapse functions"))) tools.command("fold_functions");
                ImGui.endMenu();
            }
            tools.menu("EXT:" + LuaMcpWindow.CLIENT_ID); tools.menu("EXT:" + LuaMcpWindow.SERVER_ID); tools.menu("EXT:" + LuaAiWindow.ID);
            ImGui.endMenu();
        }
        ImGui.separator();
        boolean running = ScriptManager.activeExecution() != null;
        boolean runnable = running || current != null;
        float iconSize = ImGui.getTextLineHeight();
        // Green play triangle for Run, red square for Stop; the label takes the same colour (dimmed while nothing can run).
        int tint = running ? ImGui.colorConvertFloat4ToU32(.95f, .42f, .4f, 1f) : runnable ? ImGui.colorConvertFloat4ToU32(.4f, .85f, .55f, 1f) : ImGui.colorConvertFloat4ToU32(.5f, .5f, .5f, .6f);
        ImGui.pushStyleColor(ImGuiCol.Text, tint);
        if (ImGui.menuItem("      " + (running ? tr("停止", "Stop") : tr("运行", "Run")), "Ctrl+Enter", false, runnable)) toggleRun();
        ImGui.popStyleColor();
        float left = ImGui.getItemRectMinX() + ImGui.getStyle().getFramePaddingX() + 2, top = ImGui.getItemRectMinY() + (ImGui.getItemRectSizeY() - iconSize * .7f) / 2, side = iconSize * .7f;
        var draw = ImGui.getWindowDrawList();
        if (running) draw.addRectFilled(left, top, left + side, top + side, tint, 2);
        else draw.addTriangleFilled(left, top, left, top + side, left + side * 1.05f, top + side / 2, tint);
        ImGui.checkbox(tr("调试", "Debug"), debugRun);
        ImGui.sameLine();
        if (running) { float pulse = (float) (.5 + .5 * Math.sin(System.nanoTime() / 1.0e9 * 5)); ImGui.textColored(.4f, .85f, .55f, .35f + .65f * pulse, "●"); ImGui.sameLine(); }
        if (GameControl.active()) { ImGui.textColored(1f, .55f, .3f, 1f, "● " + tr("游戏控制中 · Esc 返回 IDE", "Controlling game · Esc returns to IDE")); ImGui.sameLine(); }
        ImGui.textDisabled("Dear ImGui · " + (running ? tr("运行中", "Running") : tr("就绪", "Ready")));
        WindowChrome.finishBar(ImGui.getItemRectMaxX());
        ImGui.endMainMenuBar();
    }
    private void createLayout(int dock) {
        var api = new ImInt(); var center = new ImInt(); var left = new ImInt(); var bottom = new ImInt();
        imgui.internal.ImGui.dockBuilderRemoveNode(dock);
        imgui.internal.ImGui.dockBuilderAddNode(dock, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        var viewport = ImGui.getMainViewport();
        imgui.internal.ImGui.dockBuilderSetNodeSize(dock, viewport.getWorkSizeX(), viewport.getWorkSizeY());
        imgui.internal.ImGui.dockBuilderSplitNode(dock, ImGuiDir.Left, .20f, left, center);
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Right, .32f, api, center);
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, .26f, bottom, center);
        tools.dockDefaults(left.get(), api.get(), bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(title("problems"), bottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(title("editor"), center.get());
        imgui.internal.ImGui.dockBuilderFinish(dock);
    }
    private boolean begin(String id) {
        ImGui.setNextWindowSizeConstraints(130, 90, Float.MAX_VALUE, Float.MAX_VALUE);
        if (focusNext.equals(id)) { ImGui.setNextWindowFocus(); focusNext = ""; }
        motion.begin(id, false);
        boolean visible = ImGui.begin(title(id), windows.get(id), EDITOR_FLAGS | (lockLayout.get() && docked.contains(id) ? ImGuiWindowFlags.NoMove : 0));
        motion.observe(id);
        if (ImGui.isWindowDocked()) docked.add(id); else docked.remove(id);
        return visible;
    }
    private final WindowMotion motion = new WindowMotion();
    private void endWindow() { ImGui.end(); motion.end(); }

    /** For tool panes that created files on the server. */
    void refreshTreesFromTool() { refreshTrees(); }
    void openPalette(String prefix) { palette.open(prefix); }

    private void refreshTrees() {
        receive(ScriptManager.listScriptTree(), entries -> trees.put(ImGuiIdeDocument.Kind.LUA, entries));
        receive(ScriptManager.listDatapackTree(), entries -> trees.put(ImGuiIdeDocument.Kind.DATAPACK, entries));
        receive(local(() -> LuaResourcePackWorkspace.list().stream().map(e -> new ScriptManager.ScriptEntry(e.path(), e.directory())).toList()),
                entries -> trees.put(ImGuiIdeDocument.Kind.RESOURCEPACK, entries));
    }
    private void treeChildren(ImGuiIdeDocument.Kind kind, String parent, List<ScriptManager.ScriptEntry> entries) {
        String filter = fileFilter.get().toLowerCase(Locale.ROOT);
        for (var entry : entries) {
            String path = entry.path(); int slash = path.lastIndexOf('/');
            if (!Objects.equals(parent, slash < 0 ? "" : path.substring(0, slash))) continue;
            if (!filter.isBlank() && !path.toLowerCase(Locale.ROOT).contains(filter)
                    && (!entry.directory() || entries.stream().noneMatch(e -> e.path().startsWith(path + "/") && e.path().toLowerCase(Locale.ROOT).contains(filter)))) continue;
            ImGui.pushID(kind.name() + path);
            boolean folder = entry.directory();
            if (folder) {
                boolean expanded = ImGui.treeNodeEx(path.substring(slash + 1), filter.isBlank() ? 0 : ImGuiTreeNodeFlags.DefaultOpen);
                if (ImGui.beginPopupContextItem("context")) { treeMenu(kind, path, true); ImGui.endPopup(); }
                if (expanded) { treeChildren(kind, path, entries); ImGui.treePop(); }
            } else {
                if (ImGui.selectable(path.substring(slash + 1) + (kind == ImGuiIdeDocument.Kind.LUA ? ".lua" : ""),
                        current != null && current.kind == kind && current.path.equals(path))) open(kind, path, true);
                if (ImGui.beginPopupContextItem("context")) { treeMenu(kind, path, false); ImGui.endPopup(); }
            }
            ImGui.popID();
        }
    }
    private void treeMenu(ImGuiIdeDocument.Kind kind, String path, boolean directory) {
        if (directory) {
            if (ImGui.menuItem(tr("新建文件…", "New file…"))) createDialog(kind, "file", path);
            if (ImGui.menuItem(tr("新建文件夹…", "New folder…"))) createDialog(kind, "folder", path);
            if (kind != ImGuiIdeDocument.Kind.LUA && path.isEmpty() && ImGui.menuItem(tr("新建包模板…", "New pack template…"))) createDialog(kind, "pack", "");
        } else if (ImGui.menuItem(tr("打开", "Open"))) open(kind, path, true);
        if (!path.isEmpty() && ImGui.menuItem(tr("删除…", "Delete…"))) {
            deleteKind = kind; deletePath = path; pendingDialog = "delete_file";
        }
    }

    void open(ImGuiIdeDocument.Kind kind, String path, boolean select) {
        String key = kind.name() + ":" + path;
        if (documents.containsKey(key)) { if (select) { current = documents.get(key); selectTab = current; show("editor"); } return; }
        if (documents.size() >= LuaIdeSession.MAX_DOCUMENTS) { notice = tr("打开文件过多，请先关闭部分文件", "Too many files open; close a tab first"); return; }
        boolean binary = kind != ImGuiIdeDocument.Kind.LUA && (path.endsWith(".png") || path.endsWith(".ogg") || path.endsWith(".nbt"));
        var future = binary ? CompletableFuture.completedFuture("") : kind == ImGuiIdeDocument.Kind.LUA ? ScriptManager.read(path)
                : kind == ImGuiIdeDocument.Kind.DATAPACK ? ScriptManager.readDatapackFile(path) : local(() -> LuaResourcePackWorkspace.readText(path));
        receive(future, source -> {
            if (documents.size() >= LuaIdeSession.MAX_DOCUMENTS && !documents.containsKey(key)) return;
            var doc = documents.computeIfAbsent(key, ignored -> new ImGuiIdeDocument(kind, path, source));
            String metadataKey = metadataKey(doc);
            var restored = restoreDocuments.remove(metadataKey);
            if (restored != null) {
                doc.restoredMetadata = restored; doc.restoreOffset = restored.cursor();
                int offset = Math.clamp(restored.cursor(), 0, source.length()); int lineStart = offset == 0 ? 0 : source.lastIndexOf('\n', offset - 1) + 1;
                doc.restoreLine = (int)source.substring(0, offset).chars().filter(c -> c == '\n').count();
                doc.restoreColumn = source.codePointCount(lineStart, offset);
            }
            if (select || current == null || metadataKey.equals(restoreActive)) { current = doc; selectTab = doc; if (select) show("editor"); }
        });
    }
    private void drawEditor() {
        if (!windows.get("editor").get()) return;
        if (begin("editor")) {
            if (documents.isEmpty()) {
                ImGui.spacing(); ImGui.textColored(.35f, .8f, .95f, 1, "MC LUA IDE");
                ImGui.textWrapped(tr("从左侧目录打开文件，或创建一个脚本。拖动窗口标题可移动、拆分和停靠。", "Open a file in Explorer or create a script. Drag window titles to move, split and dock panels."));
                if (ImGui.button(tr("新建 Lua 文件", "New Lua file"))) createDialog(ImGuiIdeDocument.Kind.LUA, "file", "");
            } else if (ImGui.beginTabBar("documents", ImGuiTabBarFlags.Reorderable)) {
                for (var doc : List.copyOf(documents.values())) {
                    var open = new ImBoolean(true);
                    boolean selected = ImGui.beginTabItem(doc.filename() + "###" + doc.key(), open,
                            (doc.dirty() ? ImGuiTabItemFlags.UnsavedDocument : 0) | (selectTab == doc ? ImGuiTabItemFlags.SetSelected : 0));
                    if (selected) {
                        current = doc; if (selectTab == doc) selectTab = null;
                        if (doc.pendingMcp != null) {
                            ImGui.textWrapped(tr("MCP 已修改此文件，本地也有未保存的改动。", "MCP changed this file; local edits are unsaved."));
                            if (ImGui.smallButton(tr("采用 MCP 版本", "Use MCP version"))) { doc.external(doc.pendingMcp); doc.saved = doc.pendingMcp; if (doc.kind == ImGuiIdeDocument.Kind.LUA) ScriptManager.rememberBase(doc.path, doc.pendingMcp); doc.pendingMcp = null; }
                            ImGui.sameLine(); if (ImGui.smallButton(tr("保留本地", "Keep local"))) doc.pendingMcp = null;
                        }
                        ImGui.textDisabled(doc.path + (doc.saving ? " · " + tr("保存中…", "Saving…") : ""));
                        EditPresenceClient.banner(doc);
                        ImGuiIdeRuntime.pushEditorFont(fontSize[0]*uiScale()*tools.controller.nativePersonalization().fontPercent/100f);
                        // Switching tabs slides the code in from the side the new tab sits on.
                        if (doc != slideDoc) {
                            var order = new ArrayList<>(documents.values());
                            tabSlide = slideDoc == null ? 0 : order.indexOf(doc) > order.indexOf(slideDoc) ? 1 : -1; slideDoc = doc;
                        }
                        tabSlide = ImGuiIdeRuntime.approach(tabSlide, 0, 15);
                        float slideOffset = tabSlide * 36; ImGui.indent(slideOffset);
                        tools.drawCode(doc); ImGui.unindent(slideOffset); ImGui.popFont();
                        // "Open at" from search results, references and the outline: select the range once the file is shown.
                        if (!jumpKey.isEmpty() && doc.key().equals(jumpKey) && !doc.binary()) { tools.codeArea.selectRange(jumpStart, jumpEnd); tools.codeArea.focus(); jumpKey = ""; }
                        if(!doc.binary()&&tools.codeArea.focused())updateCompletions(doc);
                        ImGui.endTabItem();
                    }
                    if (!open.get()) requestClose(false, doc);
                }
                ImGui.endTabBar();
            }
        } endWindow();
    }
    private String apiAt(ImGuiIdeDocument doc,String word){
        if(LuaApiDocumentation.find(word)!=null)return word;
        return LuaApiDocumentation.symbols().stream().filter(s->s.endsWith("."+word)).findFirst().orElse(word);
    }
    private CompletableFuture<Void> save(ImGuiIdeDocument doc) {
        if (doc == null) return CompletableFuture.completedFuture(null);
        if (tools != null) return tools.save(doc);
        if (doc.saving) return doc.saveTask.thenCompose(ignored -> doc.dirty() ? save(doc) : CompletableFuture.completedFuture(null));
        doc.capture(); String snapshot = doc.source; doc.saving = true;
        var future = doc.kind == ImGuiIdeDocument.Kind.LUA ? ScriptManager.save(doc.path, snapshot)
                : doc.kind == ImGuiIdeDocument.Kind.DATAPACK ? ScriptManager.saveDatapackFile(doc.path, snapshot)
                : local(() -> { LuaResourcePackWorkspace.saveText(doc.path, snapshot); return (Void)null; });
        var applied = new CompletableFuture<Void>();
        doc.saveTask = applied;
        future.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
            doc.saving = false;
            if (error != null) { if (validConnection()) notice = ScriptManager.message(error); applied.completeExceptionally(error); }
            else { doc.saved = snapshot; doc.recoveryRevision = -1; if (doc.recoveryId != null && !doc.dirty()) recovery.discard(doc.recoveryId);
                if (validConnection()) notice = tr("已保存：", "Saved: ") + doc.filename(); applied.complete(null); }
        }));
        return applied;
    }
    private CompletableFuture<Void> saveAll() {
        return CompletableFuture.allOf(documents.values().stream().filter(d -> d.dirty() || d.saving).map(this::save).toArray(CompletableFuture[]::new));
    }
    private void toggleTerminal() {
        if (tools != null && tools.isOpen(TerminalPanel.ID) && tools.terminalCaptures()) tools.close(TerminalPanel.ID);
        else openTool(TerminalPanel.ID);
    }
    private void toggleRun() {
        if (tools != null) {
            if (ScriptManager.activeExecution() != null) { ScriptManager.activeExecution().stop(); return; }
            if (current != null) { tools.bind(current); tools.controller.nativeRun(debugRun.get()); show("logs"); }
            return;
        }
        if (ScriptManager.activeExecution() != null) { receive(ScriptManager.stopServer(), stopped -> notice = tr("已请求停止", "Stop requested")); return; }
        if (current == null) return;
        var doc = current; doc.capture(); windows.get("logs").set(true);
        try {
            ScriptManager.ScriptExecution execution;
            if (doc.kind == ImGuiIdeDocument.Kind.LUA) execution = debugRun.get()
                    ? ScriptManager.start(doc.path, doc.source, line -> { }, result -> { })
                    : ScriptManager.startNormal(doc.path, doc.source, line -> { }, result -> { });
            else if (doc.kind == ImGuiIdeDocument.Kind.DATAPACK && doc.path.endsWith(".mcfunction"))
                execution = ScriptManager.startFunction(doc.path, doc.source, line -> { }, result -> { });
            else { notice = tr("该文件类型不能直接运行", "This file type cannot run directly"); return; }
            String snapshot = doc.source;
            receive(execution.saved(), ignored -> doc.saved = snapshot);
        } catch (RuntimeException error) { notice = ScriptManager.message(error); }
    }

    private void openInsert(boolean shortcut) {
        insertTarget = current; insertParameter = current != null && current.lua() ? LuaCursorContext.parameterAt(current.source, current.cursorOffset()) : null;
        var expected = insertParameter == null ? null : LuaArgumentCompletion.kind(insertParameter);
        if (expected != null) insertKind = expected;
        windows.get("insert").set(true); idShortcut = shortcut; idFilter.set(""); namespace = ""; loadIds();
        focusNext = "insert";
    }
    private void loadIds() {
        int request = ++idRequest; var selected = insertKind;
        ids = List.of(); filteredIds = List.of();
        receive(ScriptManager.registryCatalog(selected.registryId()), catalog -> { if (request == idRequest) { ids = catalog; refilterIds(); } });
    }
    private void refilterIds() {
        String needle = idFilter.get().toLowerCase(Locale.ROOT);
        filteredIds = ids.stream().filter(e -> (namespace.isEmpty() || e.namespace().equals(namespace)) && e.matches(needle)).toList();
        idSelection = Math.clamp(idSelection, 0, Math.max(0, filteredIds.size() - 1));
    }
    private void insertId(String value) {
        var target = insertTarget == null ? current : insertTarget;
        if (target == null) { ImGui.setClipboardText(value); notice = tr("已复制 ID", "ID copied"); return; }
        // Do not replace stale parameter offsets if the user edited or moved the caret while searching.
        var now = target.lua() ? LuaCursorContext.parameterAt(target.source, target.cursorOffset()) : null;
        if (insertParameter != null && now != null && now.symbol().equals(insertParameter.symbol()) && now.index() == insertParameter.index()) {
            String insertion = now.quoted() ? value : "\"" + value + "\"";
            if (now.quoted() && (now.end() >= target.source.length() || target.source.charAt(now.end()) != target.source.charAt(now.start() - 1)))
                insertion += target.source.charAt(now.start() - 1);
            target.replace(now.start(), now.end(), insertion);
        } else target.insert("\"" + value + "\"");
        selectTab = current = target;
        show("editor");
        if (idShortcutClose) windows.get("insert").set(false);
    }
    private boolean idShortcutClose;

    private void drawProblems() {
        if (!windows.get("problems").get()) return;
        if (begin("problems")) for (var doc : documents.values()) {
            for (var d : doc.analysis.diagnostics()) {
                ImGui.pushID(doc.key() + d.start());
                if (ImGui.selectable(doc.filename() + ":" + d.line() + ":" + d.column() + "  " + d.message())) {
                    current = selectTab = doc; windows.get("editor").set(true);
                    tools.bind(doc); tools.controller.nativeSelect(d.start(), d.start());
                }
                ImGui.popID();
            }
        } endWindow();
    }

    private void createDialog(ImGuiIdeDocument.Kind kind, String action, String parent) {
        creationKind = kind; creationAction = action;
        newPath.set(parent.isEmpty() ? "" : parent + "/"); pendingDialog = "new_file";
    }
    private void dialogs() {
        if (!pendingDialog.isEmpty()) { ImGui.openPopup(pendingDialog); pendingDialog = ""; }
        if (modal("new_file")) {
            ImGui.textWrapped(tr("输入相对路径（支持子目录）", "Enter a relative path (subdirectories supported)"));
            ImGui.setNextItemWidth(360); ImGui.inputText("##new_path", newPath);
            if (ImGui.button(tr("创建", "Create"))) {
                String path = newPath.get().strip().replace('\\', '/');
                CompletableFuture<?> task;
                if (creationKind == ImGuiIdeDocument.Kind.LUA) {
                    if (path.endsWith(".lua")) path = path.substring(0, path.length() - 4);
                    String name = path;
                    task = creationAction.equals("folder") ? ScriptManager.createDirectory(name)
                            : ScriptManager.listScriptTree().thenApply(entries -> { if (entries.stream().anyMatch(e -> e.path().equals(name))) throw new IllegalArgumentException("File already exists"); return name; })
                            .thenCompose(ignored -> ScriptManager.save(name, "-- " + name + "\n\n"));
                } else if (creationKind == ImGuiIdeDocument.Kind.DATAPACK) task = switch (creationAction) {
                    case "pack" -> ScriptManager.createDatapack(path); case "folder" -> ScriptManager.createDatapackDirectory(path);
                    default -> ScriptManager.createDatapackExample(path);
                };
                else { String name = path; task = local(() -> {
                    switch (creationAction) { case "pack" -> LuaResourcePackWorkspace.createPack(name); case "folder" -> LuaResourcePackWorkspace.createDirectory(name);
                        default -> LuaResourcePackWorkspace.createText(name, name.endsWith(".json") ? "{\n}\n" : ""); } return null;
                }); }
                String created = path; var kind = creationKind; boolean file = creationAction.equals("file");
                receive(task, ignored -> { refreshTrees(); if (file) open(kind, created, true); }); ImGui.closeCurrentPopup();
            }
            ImGui.sameLine(); if (ImGui.button(tr("取消", "Cancel"))) ImGui.closeCurrentPopup();
            endModal();
        }
        if (modal("delete_file")) {
            ImGui.textWrapped(tr("确认删除？", "Delete this path?") + "\n" + deletePath);
            ImGui.textWrapped(tr("目录会连同其中的文件一起删除；此操作不能撤销。", "Folders and their contents will be removed. This cannot be undone."));
            if (ImGui.button(tr("删除", "Delete"))) {
                String name = deletePath; var kind = deleteKind;
                boolean dirty = documents.values().stream().anyMatch(d -> d.kind == kind && (d.path.equals(name) || d.path.startsWith(name + "/")) && (d.dirty() || d.saving));
                if (dirty) notice = tr("请先保存或关闭此路径下未保存的文件", "Save or close unsaved files under this path first");
                else {
                    boolean folder = trees.getOrDefault(kind, List.of()).stream().anyMatch(e -> e.path().equals(name) && e.directory());
                    CompletableFuture<?> task = kind == ImGuiIdeDocument.Kind.LUA ? folder ? ScriptManager.deleteDirectory(name) : ScriptManager.delete(name)
                            : kind == ImGuiIdeDocument.Kind.DATAPACK ? ScriptManager.deleteDatapackPath(name)
                            : local(() -> { LuaResourcePackWorkspace.delete(name); return null; });
                    receive(task, ignored -> { refreshTrees(); for (var d : List.copyOf(documents.values())) if (d.kind == kind && (d.path.equals(name) || d.path.startsWith(name + "/"))) removeDocument(d); });
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine(); if (ImGui.button(tr("取消", "Cancel"))) ImGui.closeCurrentPopup();
            endModal();
        }
    }

    private final Map<String, McLuaIdeEditorApi.CompletionItem> extensionCompletions = new HashMap<>();
    private int extensionCompletionStart;

    /** Items from McLuaIdeEditorApi completion providers, appended after the built-in ones (never replacing them). */
    private void addExtensionCompletions(ImGuiIdeDocument doc, boolean explicit, List<String> into) {
        int caret = doc.cursorOffset(), start = caret;
        while (start > 0 && (LuaLexer.isNamePart(doc.source.charAt(start - 1)) || doc.source.charAt(start - 1) == '.')) start--;
        String prefix = doc.source.substring(start, caret);
        if (prefix.isEmpty() || !explicit && prefix.length() < 2) return;
        extensionCompletionStart = start;
        for (var item : McLuaIdeEditorApi.complete(new McLuaIdeEditorApi.CompletionRequest(doc.path, doc.source, caret, prefix, explicit))) {
            if (completionDetails.containsKey(item.insertion())) continue;
            extensionCompletions.put(item.insertion(), item);
            completionDetails.put(item.insertion(), new LuaArgumentCompletion.Choice(item.insertion(), item.label(), item.tooltip(), 0));
            into.add(item.insertion());
        }
    }

    private void updateCompletions(ImGuiIdeDocument doc) {
        if(tools!=null){
            doc.capture();String key=doc.key()+":"+doc.revision+":"+doc.cursorOffset();
            if(!key.equals(completionKey)||completionRequested){boolean explicit=completionRequested;completionRequested=false;completionKey=key;completionIndex=0;completionDetails.clear();extensionCompletions.clear();
                var choices=tools.controller.nativeCompletions();
                var merged=new ArrayList<>(choices.stream().map(LuaIdeScreen.NativeCompletion::insertion).toList());
                for(var choice:choices)completionDetails.put(choice.insertion(),new LuaArgumentCompletion.Choice(choice.insertion(),choice.label(),choice.tooltip(),0));
                addExtensionCompletions(doc,explicit,merged);
                completions=merged;
                completionOpen=!completions.isEmpty();
            }return;
        }
        doc.capture();
        int offset = doc.cursorOffset(); int start = offset;
        while (start > 0 && (Character.isJavaIdentifierPart(doc.source.charAt(start - 1)) || doc.source.charAt(start - 1) == '.')) start--;
        String query = doc.source.substring(start, offset);
        String key = doc.key() + ":" + doc.revision + ":" + offset;
        if (!key.equals(completionKey) || completionRequested) {
            completionKey = key; completionIndex = 0;
            boolean explicit = completionRequested; completionRequested = false;
            completionStart = start; completionEnd = offset; completionDetails.clear();
            var parameter = LuaCursorContext.parameterAt(doc.source, offset);
            if (parameter != null) {
                var kind = LuaArgumentCompletion.kind(parameter);
                List<LuaInsertCatalog.Entry> catalog = List.of();
                if (kind != null) {
                    String registry = kind.registryId(); catalog = completionCatalogs.getOrDefault(registry, List.of());
                    if (!completionCatalogs.containsKey(registry) && catalogRequests.add(registry)) {
                        receive(ScriptManager.registryCatalog(registry), entries -> {
                            catalogRequests.remove(registry); completionCatalogs.put(registry, entries); completionKey = "";
                        });
                    }
                }
                var choices = LuaArgumentCompletion.suggest(doc.source, offset, parameter, catalog);
                if (!choices.isEmpty()) {
                    for (var choice : choices) completionDetails.put(choice.insertion(), choice);
                    completions = choices.stream().map(LuaArgumentCompletion.Choice::insertion).toList();
                    completionStart = parameter.start(); completionEnd = parameter.end(); completionOpen = true; return;
                }
            }
            if (query.isBlank() || !explicit && query.length() < 2) { completions = List.of(); completionOpen = false; return; }
            var options = new LinkedHashSet<String>();
            for (String symbol : LuaApiDocumentation.symbols()) if (symbol.startsWith(query) && !symbol.equals(query)) options.add(symbol);
            for (var variable : LuaCodeAnalyzer.visibleVariableValues(doc.source, offset).entrySet()) {
                String name = variable.getKey();
                if (name.startsWith(query) && !name.equals(query)) options.add(name);
                String module = variable.getValue().moduleName();
                if (module != null && query.startsWith(name + ".")) for (var choice : modules.choices(name, module, query, this::moduleSource)) {
                    options.add(choice.insertion()); completionDetails.put(choice.insertion(), choice);
                }
            }
            for (String name : List.of("local", "function", "return", "print", "require", "pairs", "ipairs", "true", "false", "nil", "end", "then", "else"))
                if (name.startsWith(query) && !name.equals(query)) options.add(name);
            completions = options.stream().sorted().toList(); completionOpen = !completions.isEmpty();
        }
    }
    private float completionFade;
    private final Map<String, Float> popupFades = new HashMap<>();
    private ImGuiIdeDocument slideDoc;
    private float tabSlide;
    /** Modal dialog that fades in and rises into place; pair with {@link #endModal()} when it returned true. */
    private boolean modal(String id) {
        float fade = popupFades.getOrDefault(id, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, .04f + .96f * ImGuiIdeRuntime.easeOut(fade));
        var viewport = ImGui.getMainViewport();
        ImGui.setNextWindowPos(viewport.getCenterX(), viewport.getCenterY() + (1 - ImGuiIdeRuntime.easeOut(fade)) * 18, ImGuiCond.Always, .5f, .5f);
        boolean open = ImGui.beginPopupModal(id, ImGuiWindowFlags.AlwaysAutoResize);
        if (open) popupFades.put(id, ImGuiIdeRuntime.approach(fade, 1, 14));
        else { popupFades.put(id, 0f); ImGui.popStyleVar(); }
        return open;
    }
    private void endModal() { ImGui.endPopup(); ImGui.popStyleVar(); }
    private boolean themeSeen;
    private void drawCompletions() {
        if(tools!=null&&!tools.codeArea.focused()&&!completionHovered)completionOpen=false;
        boolean showing = completionOpen && current != null && !completions.isEmpty();
        if (!showing) { completionFade = 0; return; }
        completionFade = ImGuiIdeRuntime.approach(completionFade, 1, 20);
        float popupWidth = 460, popupHeight = Math.min(260, completions.size() * (fontSize[0] * uiScale() + 12) + 44);
        var viewport = ImGui.getMainViewport();
        float[] caret = tools != null ? tools.codeArea.caret() : new float[]{viewport.getCenterX(), viewport.getCenterY(), fontSize[0]};
        // Open directly under the caret's line; flip above it only when there is no room below.
        boolean below = caret[1] + caret[2] + 6 + popupHeight <= viewport.getPosY() + viewport.getSizeY() - 8;
        float popupX = Math.clamp(caret[0], viewport.getPosX() + 8, Math.max(viewport.getPosX() + 8, viewport.getPosX() + viewport.getSizeX() - popupWidth - 8));
        float slide = (1 - ImGuiIdeRuntime.easeOut(completionFade)) * 8 * (below ? -1 : 1);
        float popupY = (below ? caret[1] + caret[2] + 4 : caret[1] - popupHeight - 4) + slide;
        ImGui.setNextWindowPos(popupX, popupY, ImGuiCond.Always);
        ImGui.setNextWindowSize(popupWidth, popupHeight, ImGuiCond.Always);
        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, completionFade);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 10);
        boolean open = ImGui.begin(tr("代码补全", "Completion") + "###completion", ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoCollapse
                | ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings);
        if (open) {
            ImGui.textDisabled(tr("↑ ↓ 选择 · Tab 确认 · Esc 关闭", "↑ ↓ select · Tab accept · Esc dismiss"));
            ImGui.beginChild("completion_choices", 0, 0);
            for (int i = 0; i < completions.size(); i++) {
                String value = completions.get(i);
                var details = completionDetails.get(value);
                String label = details != null ? details.label() : LuaApiDocumentation.find(value) == null ? value : LuaApiDocumentation.completionLabel(value);
                if (ImGui.selectable(label + "##" + i, i == completionIndex)) { completionIndex = i; acceptCompletion(); }
                if (ImGui.isItemHovered() && details != null) ImGui.setTooltip(details.tooltip());
                if (i == completionIndex) ImGui.setScrollHereY();
            }
            ImGui.endChild();
            completionHovered = ImGui.isWindowHovered(ImGuiHoveredFlags.ChildWindows | ImGuiHoveredFlags.AllowWhenBlockedByActiveItem);
        } ImGui.end();
        ImGui.popStyleVar(2);
    }
    private void acceptCompletion() {
        if (current == null || completions.isEmpty()) return;
        if(tools!=null&&extensionCompletions.containsKey(completions.get(Math.clamp(completionIndex,0,completions.size()-1)))){
            tools.controller.nativeReplace(extensionCompletionStart,tools.controller.nativeCursor(),completions.get(Math.clamp(completionIndex,0,completions.size()-1)));
            tools.pull();completionOpen=false;tools.focusCode();return;
        }
        if(tools!=null){tools.controller.nativeAcceptCompletion(completions.get(Math.clamp(completionIndex,0,completions.size()-1)));tools.pull();completionOpen=false;tools.focusCode();return;}
        var editor = current.widget();
        current.replace(completionStart, completionEnd, completions.get(Math.clamp(completionIndex, 0, completions.size() - 1)));
        completionOpen = false; editor.setFocus();
    }
    private String moduleSource(String name) {
        var open = documents.get(ImGuiIdeDocument.Kind.LUA + ":" + name);
        if (open != null) return open.source;
        if (!moduleSources.containsKey(name) && moduleRequests.size() < 16 && moduleRequests.add(name)) {
            receive(ScriptManager.read(name), source -> {
                moduleSources.put(name, source); completionKey = "";
                long size = moduleSources.values().stream().mapToLong(String::length).sum();
                while (moduleSources.size() > 64 || size > 20_000_000) {
                    String first = moduleSources.keySet().iterator().next(); size -= moduleSources.remove(first).length();
                }
            });
        }
        return moduleSources.get(name);
    }

    private void requestClose(boolean classic, ImGuiIdeDocument doc) {
        closeDocument = doc; switchClassic = classic;
        if (doc != null) doc.capture(); else documents.values().forEach(ImGuiIdeDocument::capture);
        boolean dirty = doc != null ? doc.dirty() || doc.saving : documents.values().stream().anyMatch(d -> d.dirty() || d.saving);
        if (dirty) closeRequested = true;
        else ImGuiIdeRuntime.defer(() -> { if (doc == null) leave(classic); else removeDocument(doc); });
    }
    private CrossFileActions crossFiles;
    private ImGuiIdeDocument conflictDoc;
    private String conflictText = "";
    private boolean conflictRequested;

    /** A save was refused because someone else changed the file since this editor loaded it. */
    void saveConflict(ImGuiIdeDocument doc, String text) { conflictDoc = doc; conflictText = text; conflictRequested = true; }

    private void conflictDialog() {
        if (conflictRequested) { ImGui.openPopup(tr("保存冲突", "Save conflict") + "###save_conflict"); conflictRequested = false; }
        if (!modal(tr("保存冲突", "Save conflict") + "###save_conflict")) return;
        var doc = conflictDoc;
        ImGui.textWrapped(conflictText);
        ImGui.textDisabled(doc == null ? "" : doc.path);
        ImGui.spacing();
        if (doc != null && ImGui.button(tr("对比服务器版本", "Compare with the server version"))) {
            receive(ScriptManager.peek(doc.path), text -> { selectTab = doc; openTool("NATIVE_DIFF"); tools.diffPanel().compareWith(tr("服务器上的版本", "Server version"), text); });
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (doc != null && ImGui.button(tr("覆盖服务器版本", "Overwrite the server version"))) {
            doc.capture(); String snapshot = doc.source;
            receive(ScriptManager.overwrite(doc.path, snapshot), ignored -> { doc.saved = snapshot; notice = tr("已覆盖保存：", "Overwritten: ") + doc.filename(); });
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (doc != null && ImGui.button(tr("放弃我的改动并加载服务器版本", "Discard mine and load the server version"))) {
            receive(ScriptManager.read(doc.path), text -> { doc.external(text); doc.saved = text; });
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (ImGui.button(tr("取消", "Cancel"))) ImGui.closeCurrentPopup();
        endModal();
    }

    private void closeDialog() {
        if (!modal("close_confirmation")) return;
        ImGui.textWrapped(tr("有未保存的文件，是否保存？", "There are unsaved files. Save changes?"));
        ImGui.beginDisabled(savingClose);
        if (ImGui.button(tr("保存并继续", "Save and continue"))) {
            savingClose = true;
            var target = closeDocument; boolean classic = switchClassic;
            (target == null ? saveAll() : save(target)).whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
                if (!validConnection()) return; savingClose = false;
                if (error != null) notice = ScriptManager.message(error);
                else ImGuiIdeRuntime.defer(() -> { if (target == null) leave(classic); else removeDocument(target); });
            }));
        }
        ImGui.sameLine(); if (ImGui.button(tr("不保存", "Don't save"))) {
            var target = closeDocument; boolean classic = switchClassic;
            if (target == null && tools != null) tools.controller.nativeDiscardAll();
            if (target == null) for (var doc : documents.values()) { if (doc.recoveryId != null) recovery.discard(doc.recoveryId); doc.saved = doc.source; }
            else { if (target.recoveryId != null) recovery.discard(target.recoveryId); target.saved = target.source; }
            ImGui.closeCurrentPopup(); ImGuiIdeRuntime.defer(() -> { if (target == null) leave(classic); else removeDocument(target); });
        }
        ImGui.sameLine(); if (ImGui.button(tr("取消", "Cancel"))) ImGui.closeCurrentPopup();
        ImGui.textWrapped(notice);
        ImGui.endDisabled(); endModal();
    }
    private void removeDocument(ImGuiIdeDocument doc) {
        if (tools != null) tools.forget(doc, true);
        documents.remove(doc.key()); ImGuiIdeRuntime.inContext(doc::destroy);
        if (current == doc) current = documents.isEmpty() ? null : documents.values().iterator().next();
        completionOpen = false;
    }
    private void leave(boolean classic) {
        if (classic) Minecraft.getInstance().setScreen(new LuaIdeScreen(previous, current != null && current.lua() ? current.path : ""));
        else Minecraft.getInstance().setScreen(previous);
    }
    private void snapshot() {
        for (var doc : documents.values()) if (doc.backend == null && !doc.binary() && doc.dirty() && doc.recoveryRevision != doc.revision) {
            if (doc.recoveryId == null) doc.recoveryId = UUID.randomUUID().toString();
            recovery.save(new LuaFileRecovery.Draft(new LuaFileRecovery.Entry(doc.recoveryId,
                    new LuaIdeSession.Document(doc.path, !doc.lua(), doc.kind == ImGuiIdeDocument.Kind.RESOURCEPACK, doc.cursorOffset(), 0),
                    System.currentTimeMillis(), false), doc.source, doc.saved, null));
            doc.recoveryRevision = doc.revision;
        }
    }
    private void persist() {
        var metadata = documents.values().stream().map(d -> tools == null ? new LuaIdeSession.Document(d.path, !d.lua(), d.kind == ImGuiIdeDocument.Kind.RESOURCEPACK, d.cursorOffset(), 0) : tools.controller.nativeMetadata(d)).toList();
        session.save(new LuaIdeSession.State(metadata, current == null ? "" : metadataKey(current)));
        windows.forEach((key, value) -> preferences.setProperty(key, Boolean.toString(value.get())));
        preferences.setProperty("font", Float.toString(fontSize[0])); preferences.setProperty("locked", Boolean.toString(lockLayout.get()));
        preferences.setProperty("debug", Boolean.toString(debugRun.get())); preferences.setProperty("layout", "true");
        preferences.setProperty("scale", Integer.toString(interfacePercent.get())); preferences.setProperty("workspace.version", "2");
        if (tools != null) tools.persist();
        LuaIdeSession.writeProperties(layoutFile.resolveSibling(layoutFile.getFileName() + ".properties"), preferences);
        if (initializedUi) ImGuiIdeRuntime.inContext(() -> {
            try { Files.createDirectories(layoutFile.getParent()); Files.writeString(layoutFile, ImGui.saveIniSettingsToMemory()); }
            catch (Exception e) { McLuaIdeMod.LOGGER.warn("Could not save ImGui layout", e); }
        });
    }
    @Override public void frameRendered() { frameCount++; }
    @Override public void rendererFailed(Throwable error) {
        snapshot();
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                tr("新版 UI 初始化失败，已保留恢复草稿：", "New UI failed; recovery drafts preserved: ") + ScriptManager.message(error)));
        leave(false);
    }
    @Override public void tick() {
        if (!validConnection()) return;
        EditPresenceClient.tick(documents.values());
        if (tools != null) { tools.tick(); return; }
        for (var doc : documents.values()) if (doc.lua()) {
            var update = LuaMcpFileSync.latest(doc.path);
            if (update != null && update.revision() > doc.mcpRevision) {
                doc.mcpRevision = update.revision();
                if (doc.dirty() && !doc.source.equals(update.change().source())) doc.pendingMcp = update.change().source();
                else { doc.external(update.change().source()); doc.saved = doc.source; if (doc.kind == ImGuiIdeDocument.Kind.LUA) ScriptManager.rememberBase(doc.path, doc.source); }
            }
        }
        long now = System.nanoTime();
        if (now >= nextRecovery) { nextRecovery = now + 2_000_000_000L; snapshot(); }
        if (windows.get("variables").get() && !variablePending && now >= nextVariables) {
            variablePending = true; nextVariables = now + 500_000_000L;
            ScriptManager.debugVariables().whenComplete((text, error) -> Minecraft.getInstance().execute(() -> {
                if (!validConnection()) return; variablePending = false;
                variables = error == null ? text : ScriptManager.message(error);
            }));
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float partialTick) { }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void onClose() { ImGuiIdeRuntime.defer(() -> ImGuiIdeRuntime.inContext(() -> requestClose(false, null))); }
    @Override public boolean charTyped(CharacterEvent event) {
        if (tools != null && tools.terminalCaptures()) { boolean[] typed={false}; ImGuiIdeRuntime.inContext(()->typed[0]=tools.terminalCharacter(event)); if (typed[0]) return true; }
        if (tools != null) {boolean[] handled={false};ImGuiIdeRuntime.inContext(()->handled[0]=tools.character(event));if(handled[0])return true;}
        ImGuiIdeRuntime.character(event.codepoint()); return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (ImGuiIdeRuntime.popupOpen()) { ImGuiIdeRuntime.key(event.key(), event.scancode(), GLFW.GLFW_PRESS, event.modifiers()); return true; }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_N) { createDialog(ImGuiIdeDocument.Kind.LUA, "file", ""); return true; }
        if (event.key() == GLFW.GLFW_KEY_F12 && (event.modifiers() & 15) == 0) {
            if(tools!=null){if(tools.isOpen("NATIVE_DEBUG"))tools.close("NATIVE_DEBUG");else tools.open("NATIVE_DEBUG");}else openTool("NATIVE_DEBUG");return true;
        }
        int key = event.key(); boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_GRAVE_ACCENT) { ImGuiIdeRuntime.inContext(this::toggleTerminal); return true; }
        if (tools != null && tools.terminalCaptures()) { boolean[] used={false}; ImGuiIdeRuntime.inContext(()->used[0]=tools.terminalKey(event)); if (used[0]) return true; }
        if (completionOpen && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_TAB)) {
            if (key == GLFW.GLFW_KEY_TAB) ImGuiIdeRuntime.inContext(this::acceptCompletion);
            else completionIndex = Math.floorMod(completionIndex + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), completions.size());
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (palette.isOpen()) { palette.close(); return true; }
            if (tools!=null&&tools.endLayoutEditing())return true;
            if (tools!=null&&tools.codeArea.dismissMarks())return true;
            if (completionOpen) completionOpen = false;
            else if(tools!=null&&tools.escape())return true;
            else if (windows.get("insert").get() && idShortcutClose) windows.get("insert").set(false);
            else onClose(); return true;
        }
        if (tools != null) {boolean[] handled={false};ImGuiIdeRuntime.inContext(()->handled[0]=tools.key(event,false));if(handled[0])return true;}
        boolean shifted = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (ctrl && !shifted && key == GLFW.GLFW_KEY_P) { palette.open(""); return true; }
        if (ctrl && shifted && key == GLFW.GLFW_KEY_P) { palette.open(">"); return true; }
        if (ctrl && shifted && key == GLFW.GLFW_KEY_O) { palette.open("@"); return true; }
        if (ctrl && shifted && key == GLFW.GLFW_KEY_F) { ImGuiIdeRuntime.inContext(() -> { openTool("NATIVE_SEARCH"); tools.searchPanel().focusQuery(); }); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_S) {
            // Ctrl+S saves the file of the editor that has the keyboard: the split view or the main editor.
            var split = tools == null ? null : tools.focusedSplit();
            var target = split != null && split.document() != null ? split.document() : current;
            ImGuiIdeRuntime.inContext(() -> save(target)); return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_N) { createDialog(ImGuiIdeDocument.Kind.LUA, "file", ""); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_I) { ImGuiIdeRuntime.inContext(() -> { idShortcutClose = true; openInsert(true); }); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_SPACE) { completionRequested = true; return true; }
        if (ctrl && key == GLFW.GLFW_KEY_F1) { show("api"); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_F3) { show("logs"); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_SLASH && current != null) { ImGuiIdeRuntime.inContext(() -> current.widget().toggleComments()); return true; }
        if (key == GLFW.GLFW_KEY_F5) { ImGuiIdeRuntime.inContext(this::toggleRun); return true; }
        ImGuiIdeRuntime.key(key, event.scancode(), GLFW.GLFW_PRESS, event.modifiers()); return true;
    }
    @Override public boolean keyReleased(KeyEvent event) {
        if (tools != null && tools.key(event, true)) return true;
        ImGuiIdeRuntime.key(event.key(), event.scancode(), GLFW.GLFW_RELEASE, event.modifiers()); return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) { ImGuiIdeRuntime.mouse(event.button(), true); if (tools != null) tools.mouseClicked(event, twice); return true; }
    @Override public boolean mouseReleased(MouseButtonEvent event) { ImGuiIdeRuntime.mouse(event.button(), false); if (tools != null) tools.mouseReleased(event); return true; }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return tools != null && tools.mouseDragged(event, dx, dy); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (tools != null && tools.scroll(x, y, horizontal, vertical)) return true;
        long handle = Minecraft.getInstance().getWindow().handle();
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS) fontSize[0] = Math.clamp(fontSize[0] + (float)vertical, 10, 80);
        else ImGuiIdeRuntime.scroll(horizontal, vertical); return true;
    }
    @Override public void removed() {
        if (!suspended) WindowChrome.release();
        if (suspended) { ImGuiIdeRuntime.clearInput(); return; }
        if (disposed) return;
        ImGuiIdeRuntime.inContext(() -> documents.values().forEach(ImGuiIdeDocument::capture));
        snapshot(); persist(); disposed = true;
        if (tools != null) tools.close();
        ImGuiIdeRuntime.inContext(() -> {
            documents.values().forEach(ImGuiIdeDocument::destroy);
            for (var view : new TextView[]{variableView, logView, manualView, apiExample}) if (view != null) view.destroy();
        });
        ImGuiIdeRuntime.clearInput(); super.removed();
    }
    int renderedFrames() { return frameCount; }
    private static String metadataKey(ImGuiIdeDocument d) { return (d.kind == ImGuiIdeDocument.Kind.RESOURCEPACK ? "resourcepack:" : d.kind == ImGuiIdeDocument.Kind.DATAPACK ? "datapack:" : "lua:") + d.path; }
    void probeDocument(ImGuiIdeDocument doc) { documents.put(doc.key(), doc); current = selectTab = doc; }
    ImGuiToolWorkspace probeWorkspace() { return tools; }
    ImGuiIdeDocument probeCurrent() { return current; }
    void probePalette(String initial) { palette.open(initial); }
    boolean probePaletteOpen() { return palette.isOpen(); }
    void probeScale(int percent) { interfacePercent.set(percent); }
    void probeWindows() { for (String id : List.of("api", "logs", "variables", "problems")) windows.get(id).set(true); }
    boolean probeHasRecovery() { return !recovery.entries().isEmpty(); }
    void probeCompletion() {
        String before = current.source;
        current.external("MC.Pl"); current.widget().setCursor(0, 5); completionKey = "";
        updateCompletions(current);
        if (completions.isEmpty()) throw new AssertionError("API completion list missing");
        completionIndex = 0; acceptCompletion();
        if (!current.source.startsWith("MC.Player.")) throw new AssertionError("API completion replacement failed: " + current.source);
        String partial = "MC.Item.give(\"00000000-0000-0000-0000-000000000000\", \"minecraft:dia\")";
        current.external(partial);
        var widget = current.widget(); var position = current.coordinates(partial.lastIndexOf('"'));
        widget.setCursor(position[0], position[1]);
        completionCatalogs.put(LuaInsertCatalog.Kind.ITEM.registryId(), List.of(new LuaInsertCatalog.Entry("minecraft:diamond", "钻石", "minecraft")));
        completionKey = ""; updateCompletions(current);
        if (completions.stream().noneMatch(v -> v.contains("minecraft:diamond"))) throw new AssertionError("Typed ID completion missing");
        completionIndex = 0; acceptCompletion();
        if (!current.source.contains("\"minecraft:diamond\"") || current.source.contains("\"\"minecraft")) throw new AssertionError("ID completion quotes corrupt: " + current.source);
        current.external(before); current.widget(); completionOpen = false;
    }
    static final class TextView {
        final ImString buffer = new ImString(256);
        final imgui.extension.texteditor.TextEditor editor;
        String text = "";
        TextView() { this(false); }
        TextView(boolean proportional) {
            editor = proportional ? null : new imgui.extension.texteditor.TextEditor();
            if (editor != null) { editor.setReadOnlyEnabled(true); editor.setShowLineNumbersEnabled(false); editor.setShowWhitespacesEnabled(false); }
        }
        boolean set(String value) {
            if (text.equals(value)) return false; text = value;
            if (editor != null) editor.setText(value); else buffer.set(value, true);
            return true;
        }
        void render(String name, float w, float h) {
            if (editor == null) ImGui.inputTextMultiline("##" + name, buffer, w, h, ImGuiInputTextFlags.ReadOnly);
            else { ImGuiIdeRuntime.pushEditorFont(ImGui.getFontSize()); editor.render(name, w, h, true); ImGui.popFont(); }
        }
        void destroy() { if (editor != null) editor.destroy(); }
    }
}
