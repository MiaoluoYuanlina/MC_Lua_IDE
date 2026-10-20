package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/** Animated API reference with availability/custom-root -> namespace -> method navigation. */
final class LuaApiDocsOverlay {
    void renderImGui() {
        refreshDocumentation();
        String query=NativeUi.input("##API filter",filterQuery);
        if(!query.equals(filterQuery)){filterQuery=query;onFilterChanged();}
        if(pinyinToggleVisible()) pinyinSearchEnabled=NativeUi.check("拼音首字母",pinyinSearchEnabled);
        if(romajiToggleVisible()) romajiSearchEnabled=NativeUi.check("ローマ字検索",romajiSearchEnabled);
        if(imgui.ImGui.beginTable("api",2,imgui.flag.ImGuiTableFlags.Resizable|imgui.flag.ImGuiTableFlags.BordersInnerV)) {
            imgui.ImGui.tableSetupColumn("navigation",imgui.flag.ImGuiTableColumnFlags.WidthFixed,220);
            imgui.ImGui.tableSetupColumn("details"); imgui.ImGui.tableNextColumn();
            imgui.ImGui.beginChild("navigation");
            int index=0;
            for(var row:treeRows()) {
                imgui.ImGui.pushID(index++); imgui.ImGui.indent(row.depth()*12);
                boolean leaf=row.type()==RowType.METHOD||row.type()==RowType.PAGE;
                if(leaf) {
                    if(imgui.ImGui.selectable(row.label(),row.entry()!=null?row.entry()==selected:row.page()==selectedPage))activate(row);
                } else {
                    boolean expanded=switch(row.type()) {
                        case ROOT->rootExpanded;
                        case SECTION->filterQuery.isBlank()?expandedSections.contains(row.section().key()):!searchCollapsedSections.contains(row.section().key());
                        case GROUP->filterQuery.isBlank()?expandedGroups.contains(groupKey(row.section(),row.group())):!searchCollapsedGroups.contains(groupKey(row.section(),row.group()));
                        default->false;
                    };
                    imgui.ImGui.setNextItemOpen(expanded);
                    boolean now=imgui.ImGui.treeNodeEx(row.label(),imgui.flag.ImGuiTreeNodeFlags.NoTreePushOnOpen|imgui.flag.ImGuiTreeNodeFlags.SpanAvailWidth);
                    if(now!=expanded)activate(row);
                }
                imgui.ImGui.unindent(row.depth()*12); imgui.ImGui.popID();
            }
            imgui.ImGui.endChild(); imgui.ImGui.tableNextColumn(); imgui.ImGui.beginChild("details");
            if(selectedPage!=null) {
                imgui.ImGui.textWrapped(selectedPage.translation().pageTitle());
                for(var element:selectedPage.translation().elements()) {
                    imgui.ImGui.pushID(index++);
                    if(element.kind()==McLuaIdeDocsApi.ElementKind.CODE) nativeCode(element.content());
                    else NativeUi.readOnly("##text",element.content(),Math.max(46,imgui.ImGui.calcTextSize(element.content()).y+20));
                    imgui.ImGui.popID();
                }
            } else if(selected!=null) {
                var entry=selected.documentation(); imgui.ImGui.textWrapped(selected.symbol());
                if(entry.clientRequired())imgui.ImGui.textColored(1,.68f,.25f,1,LuaUiText.text("客户端 Mod 专用：目标玩家必须安装兼容版本"));
                StringBuilder description=new StringBuilder(entry.signature()).append("\n\n作用：").append(entry.summary()).append("\n返回：").append(entry.returns());
                for(int i=0;i<entry.parameters().size();i++)description.append("\n参数").append(i+1).append("：").append(entry.describe(entry.parameters().get(i)));
                description.append("\n\n").append(entry.details()).append("\n\n").append(usageHint(selected));
                NativeUi.readOnly("##description",description.toString(),Math.max(180,imgui.ImGui.getContentRegionAvailY()*.46f));
                nativeCode(LuaApiDocumentation.usageTemplate(selected.symbol()));
            } else imgui.ImGui.textWrapped(LuaUiText.text("展开目录或搜索 API"));
            imgui.ImGui.endChild(); imgui.ImGui.endTable();
        }
    }
    private void nativeCode(String source) {
        NativeUi.action("插入",()->insertion.accept(source)); imgui.ImGui.sameLine(); NativeUi.action("复制",()->NativeUi.copy(source));
        NativeUi.code("##template",source,Math.max(100,Math.min(380,source.lines().count()*imgui.ImGui.getTextLineHeightWithSpacing()+15)));
    }
	private static final int TITLE_HEIGHT = 28;
	private static final int BREADCRUMB_HEIGHT = 25;
	private static final int FILTER_HEIGHT = 30;
	private static final int FOOTER_HEIGHT = 24;
	private static final int ROW_HEIGHT = 19;
	private static final int RESIZE_MARGIN = 6;
	private static final int MIN_WIDTH = 600;
	private static final int MIN_HEIGHT = 330;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;
	private static final String NAVIGATION_PREFIX = "apiDocs.navigation.";
	private static final int MAX_SAVED_BRANCHES = 1024;

	private static final Map<String, String> GROUP_LABELS = Map.ofEntries(
			Map.entry("Core", "基础 Core"),
			Map.entry("Block", "方块 Block"),
			Map.entry("Item", "物品 Item"),
			Map.entry("Container", "容器 Container"),
			Map.entry("Player", "玩家 Player"),
			Map.entry("World", "世界 World"),
			Map.entry("Chat", "消息 Chat"),
			Map.entry("Command", "命令 Command"),
			Map.entry("Mod", "模组 Mod"),
			Map.entry("Game", "游戏 Game"),
			Map.entry("Server", "服务端 Server"),
			Map.entry("Event", "事件 Event"),
			Map.entry("Entity", "实体 Entity"),
			Map.entry("Effect", "效果 Effect"),
			Map.entry("Sound", "声音 Sound"),
			Map.entry("Particle", "粒子 Particle"),
			Map.entry("Title", "标题 Title"),
			Map.entry("Score", "计分板 Score"),
			Map.entry("Team", "队伍 Team"),
			Map.entry("GameRule", "游戏规则 GameRule"),
			Map.entry("Border", "世界边界 Border"),
			Map.entry("Attribute", "属性 Attribute"),
			Map.entry("Advancement", "进度 Advancement"),
			Map.entry("Recipe", "配方 Recipe"),
			Map.entry("Pack", "数据与资源包 Pack"),
			Map.entry("Loot", "战利品 Loot"),
			Map.entry("Structure", "结构 Structure"),
			Map.entry("Registry", "注册表 Registry")
	);

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<String> insertion;
	private List<LuaApiDocumentation.DocumentEntry> entries = LuaApiDocumentation.allEntries();
	private List<LuaApiDocumentation.DocumentPage> pages = LuaApiDocumentation.allPages();
	private Map<String, List<LuaApiDocumentation.DocumentEntry>> groups = buildGroups();
	private final Set<String> expandedSections = new LinkedHashSet<>();
	private final Set<String> expandedGroups = new LinkedHashSet<>();
	// Search opens matching branches by default, but explicit folds must still take effect.
	private final Set<String> searchCollapsedSections = new LinkedHashSet<>();
	private final Set<String> searchCollapsedGroups = new LinkedHashSet<>();
	private final List<SelectableSpan> textSpans = new ArrayList<>();
	private final StringBuilder selectableText = new StringBuilder();
	private LuaApiDocumentation.DocumentEntry selected;
	private LuaApiDocumentation.DocumentPage selectedPage;
	private String pendingSelectedSymbol = "";
	private String pendingSelectedPageKey = "";
	private boolean rootExpanded;
	private boolean open;
	private float progress;
	private int screenWidth;
	private int screenHeight;
	private int windowX = -1;
	private int windowY = 34;
	private int windowWidth = -1;
	private int windowHeight = -1;
	private int navigationScroll;
	private int preferredSidebarWidth = -1;
	private boolean draggingSidebarDivider;
	private int contentScroll;
	private int contentHeight;
	private boolean dragging;
	private boolean selectingText;
	private int selectionAnchor = -1;
	private int selectionCaret = -1;
	private int resizeEdges;
	private double dragOffsetX;
	private double dragOffsetY;
	private double resizeStartMouseX;
	private double resizeStartMouseY;
	private int resizeStartX;
	private int resizeStartY;
	private int resizeStartWidth;
	private int resizeStartHeight;
	private String filterQuery = "";
	private int filterCursor;
	private int filterAnchor;
	private boolean filterFocused;
	private boolean keyboardFocused;
	private boolean pinyinSearchEnabled = true;
	private boolean romajiSearchEnabled = true;
	private ButtonBounds insertButtonBounds;
	private ButtonBounds copyButtonBounds;
	private final List<PageCodeButton> pageCodeButtons = new ArrayList<>();
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked;
	private int floatingX = -1;
	private int floatingY = 34;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();
	private int documentationRevision = -1;

	LuaApiDocsOverlay(Minecraft minecraft, Font font, Consumer<String> insertion) {
		this.minecraft = minecraft;
		this.font = font;
		this.insertion = insertion;
	}

	void resize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
		ensureGeometry();
	}

	void open() {
		refreshDocumentation();
		open = true;
		keyboardFocused = true;
		filterFocused = true;
	}

	boolean openAt(String symbol) {
		refreshDocumentation();
		if (symbol == null || symbol.isBlank()) return false;
		LuaApiDocumentation.DocumentEntry target = entries.stream()
				.filter(entry -> entry.symbol().equals(symbol))
				.findFirst()
				.orElseGet(() -> entries.stream()
						.filter(entry -> entry.symbol().equalsIgnoreCase(symbol))
						.findFirst().orElse(null));
		if (target == null) return false;

		filterQuery = "";
		filterCursor = 0;
		filterAnchor = 0;
		filterFocused = false;
		keyboardFocused = true;
		open = true;
		rootExpanded = true;
		selected = target;
		selectedPage = null;
		clearPendingSelection();
		navigationScroll = 0;
		contentScroll = 0;
		selectionAnchor = -1;
		selectionCaret = -1;
		expandSelectedPath();
		ensureSelectedNavigationVisible();
		return true;
	}

	void close() {
		open = false;
		keyboardFocused = false;
		dragging = false;
		selectingText = false;
		filterFocused = false;
		resizeEdges = 0;
		draggingSidebarDivider = false;
	}

	boolean isOpen() {
		return open;
	}

	boolean isVisible() {
		return progress > 0.01F;
	}

	boolean contains(double mouseX, double mouseY) {
		return isVisible() && pointInside(mouseX, mouseY);
	}

	boolean isInteracting() {
		return dragging || selectingText || resizeEdges != 0 || draggingSidebarDivider;
	}

	boolean isDragging() {
		return dragging;
	}

	DockSide dockSide() {
		return dockSide;
	}

	void setLayoutLocked(boolean locked) {
		layoutLocked = locked;
	}


	WindowGeometry captureGeometry() {
		if (!dockSide.docked()) return boundsAnimation.destination(windowX, windowY, windowWidth, windowHeight);
		return new WindowGeometry(dockSide.docked() && floatingWidth > 0 ? floatingX : windowX,
				dockSide.docked() && floatingHeight > 0 ? floatingY : windowY,
				dockSide.docked() && floatingWidth > 0 ? floatingWidth : windowWidth,
				dockSide.docked() && floatingHeight > 0 ? floatingHeight : windowHeight);
	}

	int preferredSidebarWidth() { return preferredSidebarWidth; }
	void restoreSidebarWidth(int value) { preferredSidebarWidth = value > 0 ? Math.min(value, 32768) : -1; }

	/** Browsing metadata only; no scripts or documentation bodies are saved. */
	void captureNavigation(Properties state) {
		state.setProperty(NAVIGATION_PREFIX + "rootExpanded", Boolean.toString(rootExpanded));
		writeBranches(state, "sections", expandedSections);
		writeBranches(state, "groups", expandedGroups);
		writeBranches(state, "searchCollapsedSections", searchCollapsedSections);
		writeBranches(state, "searchCollapsedGroups", searchCollapsedGroups);
		state.setProperty(NAVIGATION_PREFIX + "symbol", selected == null ? pendingSelectedSymbol : selected.symbol());
		state.setProperty(NAVIGATION_PREFIX + "page", selectedPage == null ? pendingSelectedPageKey : selectedPage.key());
		state.setProperty(NAVIGATION_PREFIX + "treeScroll", Integer.toString(navigationScroll));
		state.setProperty(NAVIGATION_PREFIX + "contentScroll", Integer.toString(contentScroll));
		state.setProperty(NAVIGATION_PREFIX + "filter", filterQuery);
		state.setProperty(NAVIGATION_PREFIX + "pinyin", Boolean.toString(pinyinSearchEnabled));
		state.setProperty(NAVIGATION_PREFIX + "romaji", Boolean.toString(romajiSearchEnabled));
	}

	void restoreNavigation(Properties state) {
		refreshDocumentation();
		rootExpanded = Boolean.parseBoolean(state.getProperty(NAVIGATION_PREFIX + "rootExpanded", "false"));
		readBranches(state, "sections", expandedSections);
		readBranches(state, "groups", expandedGroups);
		readBranches(state, "searchCollapsedSections", searchCollapsedSections);
		readBranches(state, "searchCollapsedGroups", searchCollapsedGroups);
		resolveSelection(savedText(state, "symbol", 512), savedText(state, "page", 512));
		navigationScroll = savedInteger(state, "treeScroll");
		contentScroll = savedInteger(state, "contentScroll");
		contentHeight = 0; // Recalculate against the current font and window dimensions on render.
		filterQuery = savedText(state, "filter", 160);
		filterCursor = filterQuery.length();
		filterAnchor = filterCursor;
		pinyinSearchEnabled = Boolean.parseBoolean(state.getProperty(NAVIGATION_PREFIX + "pinyin", "true"));
		romajiSearchEnabled = Boolean.parseBoolean(state.getProperty(NAVIGATION_PREFIX + "romaji", "true"));
		selectionAnchor = selectionCaret = -1;
	}

	private static void writeBranches(Properties state, String name, Set<String> branches) {
		int oldCount = Math.min(MAX_SAVED_BRANCHES, savedInteger(state, name + ".count"));
		for (int index = 0; index < oldCount; index++) state.remove(NAVIGATION_PREFIX + name + '.' + index);
		int count = 0;
		for (String key : branches) {
			if (count == MAX_SAVED_BRANCHES) break;
			state.setProperty(NAVIGATION_PREFIX + name + '.' + count++, key);
		}
		state.setProperty(NAVIGATION_PREFIX + name + ".count", Integer.toString(count));
	}

	private static void readBranches(Properties state, String name, Set<String> branches) {
		branches.clear();
		int count = Math.min(MAX_SAVED_BRANCHES, savedInteger(state, name + ".count"));
		for (int index = 0; index < count; index++) {
			String key = savedText(state, name + '.' + index, 512);
			if (!key.isBlank()) branches.add(key);
		}
	}

	private static int savedInteger(Properties state, String name) {
		try { return Math.max(0, Integer.parseInt(state.getProperty(NAVIGATION_PREFIX + name, "0"))); }
		catch (NumberFormatException ignored) { return 0; }
	}

	private static String savedText(Properties state, String name, int maximumPoints) {
		String text = state.getProperty(NAVIGATION_PREFIX + name, "").replaceAll("[\\p{Cntrl}]", " ");
		int count = text.codePointCount(0, text.length());
		return count > maximumPoints ? text.substring(0, text.offsetByCodePoints(0, maximumPoints)) : text;
	}

	void restoreGeometry(WindowGeometry geometry) {
		if (geometry == null) return;
		windowX = geometry.x(); windowY = geometry.y();
		windowWidth = Math.min(geometry.width(), Math.max(1, screenWidth));
		windowHeight = Math.min(geometry.height(), Math.max(1, screenHeight));
		ensureGeometry();
		windowX = Math.clamp(windowX, 0, Math.max(0, screenWidth - windowWidth));
		windowY = Math.clamp(windowY, 0, Math.max(0, screenHeight - windowHeight));
		floatingX = windowX; floatingY = windowY; floatingWidth = windowWidth; floatingHeight = windowHeight;
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		DockSide previous = dockSide;
		if (!dockSide.docked() && side.docked()) rememberFloatingGeometry();
		dockSide = side;
		if (previous.docked() && !side.docked()) restoreFloatingGeometry();
		resizeEdges = 0;
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (WindowBoundsAnimation.isImmediate()) {
			windowX = x; windowY = y; windowWidth = width; windowHeight = height; progress = 1;
			boundsAnimation.snapTo(x,y,width,height); return;
		}
		if (!dockSide.docked()) return;
		boundsAnimation.animateTo(windowX, windowY, windowWidth, windowHeight,
				x, y, width, height);
	}

	void releaseKeyboardFocus() {
		keyboardFocused = false;
		filterFocused = false;
		selectingText = false;
	}

	void tick() {
		if (open && documentationRevision != LuaApiDocumentation.revision()) refreshDocumentation();
		float target = open ? 1.0F : 0.0F;
		progress += (target - progress) * 0.28F;
		if (Math.abs(target - progress) < 0.01F) progress = target;
		if (!dragging && resizeEdges == 0) {
			WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(
					windowX, windowY, windowWidth, windowHeight);
			windowX = bounds.x();
			windowY = bounds.y();
			windowWidth = bounds.width();
			windowHeight = bounds.height();
		}
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isVisible()) return;
		ensureGeometry();
		int x = panelX();
		int right = x + windowWidth;
		int bottom = windowY + windowHeight;
		int breadcrumbTop = windowY + TITLE_HEIGHT;
		int filterTop = breadcrumbTop + BREADCRUMB_HEIGHT;
		int mainTop = filterTop + FILTER_HEIGHT;
		int mainBottom = bottom - FOOTER_HEIGHT;
		int sidebarWidth = sidebarWidth();
		int dividerX = x + sidebarWidth;

		graphics.fill(x - 5, windowY + 5, right + 5, bottom + 5, 0x75000000);
		graphics.fill(x, windowY, right, bottom, 0xFE0E141D);
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFFB084F5);
		graphics.fill(x + 1, windowY + 1, right - 1, breadcrumbTop, 0xFF2B2040);
		graphics.text(font, LuaUiText.text("◈  MC Lua IDE · API 文档"), x + 10, windowY + 9, 0xFFD6B7FF);
		int filteredCount = filteredEntries().size() + filteredPages().size();
		int totalCount = entries.size() + pages.size();
		String countLabel = filterQuery.isBlank() ? LuaUiText.text("当前共 ") + totalCount + LuaUiText.text(" 项文档")
				: "筛选 " + filteredCount + " / " + totalCount + " 项";
		graphics.text(font, countLabel, x + 184, windowY + 9, 0xFFA995C7);

		boolean closeHovered = inside(mouseX, mouseY, right - 26, windowY + 4, right - 6, windowY + 24);
		graphics.fill(right - 26, windowY + 4, right - 6, windowY + 24,
				closeHovered ? 0xFFB83A46 : 0xFF4A355E);
		graphics.centeredText(font, "×", right - 16, windowY + 10, 0xFFFFFFFF);

		graphics.fill(x + 1, breadcrumbTop, right - 1, mainTop, 0xFF171E2A);
		String breadcrumb = breadcrumb();
		graphics.text(font, font.plainSubstrByWidth(breadcrumb, windowWidth - 24),
				x + 10, breadcrumbTop + 8, 0xFFC8D5E6);

		renderFilter(graphics, mouseX, mouseY, x, filterTop, right);

		graphics.fill(x + 1, mainTop, dividerX, mainBottom, 0xFF111925);
		boolean dividerHovered = mouseY >= mainTop && mouseY < mainBottom
				&& Math.abs(mouseX - dividerX) <= 5;
		renderNavigation(graphics, mouseX, mouseY, x, mainTop, dividerX, mainBottom);
		renderDetails(graphics, mouseX, mouseY, dividerX + 1, mainTop, right, mainBottom);
		graphics.fill(dividerX - (dividerHovered || draggingSidebarDivider ? 2 : 0), mainTop,
				dividerX + (dividerHovered || draggingSidebarDivider ? 3 : 1), mainBottom,
				dividerHovered || draggingSidebarDivider ? 0xFFB084F5 : 0xFF3D344D);

		graphics.fill(x + 1, mainBottom, right - 1, bottom - 1, 0xFF171E2A);
		graphics.text(font, LuaUiText.text("黄色为筛选命中 · 右侧拖选复制 · 首拼开关可独立关闭"),
				x + 10, mainBottom + 8, 0xFF8391A5);
		renderResizeEdges(graphics, mouseX, mouseY, x, right, bottom);
	}

	boolean pinyinToggleVisible() { return LuaUiText.chinese(); }
	private boolean pinyinSearchActive() { return pinyinToggleVisible() && pinyinSearchEnabled; }
	boolean romajiToggleVisible() { return LuaUiText.japanese(); }
	private boolean phoneticToggleVisible() { return pinyinToggleVisible() || romajiToggleVisible(); }
	private int phoneticToggleWidth() { return romajiToggleVisible() ? 112 : 86; }
	int filterInputRight(int right) { return right - (phoneticToggleVisible() ? phoneticToggleWidth() + 17 : 10); }

	private void renderFilter(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int right) {
		graphics.fill(left + 1, top, right - 1, top + FILTER_HEIGHT, 0xFF121923);
		int inputLeft = left + 10;
		int inputTop = top + 5;
		int toggleWidth = phoneticToggleWidth();
		int toggleLeft = right - toggleWidth - 10;
		int inputRight = filterInputRight(right);
		boolean hovered = inside(mouseX, mouseY, inputLeft, inputTop, inputRight, inputTop + 20);
		graphics.fill(inputLeft, inputTop, inputRight, inputTop + 20, 0xFF080D14);
		graphics.outline(inputLeft, inputTop, inputRight - inputLeft, 20,
				filterFocused ? 0xFFB084F5 : hovered ? 0xFF765D96 : 0xFF3D4858);
		graphics.text(font, "⌕", inputLeft + 7, inputTop + 6, 0xFFA98AD2);

		int textLeft = inputLeft + 22;
		int textRight = inputRight - 24;
		if (filterQuery.isEmpty()) {
			graphics.text(font, LuaUiText.text("筛选 API、中文说明或拼音首字母，例如 wj、ml…"),
					textLeft, inputTop + 6, 0xFF667486);
		} else {
			int inputWidth = Math.max(20, textRight - textLeft);
			int visibleStart = filterVisibleStart(inputWidth);
			String visible = font.plainSubstrByWidth(filterQuery.substring(visibleStart), inputWidth);
			int visibleEnd = visibleStart + visible.length();
			int selectionStart = Math.max(visibleStart, Math.min(filterCursor, filterAnchor));
			int selectionEnd = Math.min(visibleEnd, Math.max(filterCursor, filterAnchor));
			if (selectionStart < selectionEnd) {
				int highlightLeft = textLeft + font.width(filterQuery.substring(visibleStart, selectionStart));
				int highlightRight = textLeft + font.width(filterQuery.substring(visibleStart, selectionEnd));
				graphics.fill(highlightLeft, inputTop + 3, highlightRight, inputTop + 17, 0xB34A82C2);
			}
			graphics.text(font, visible, textLeft, inputTop + 6, 0xFFFFFFFF);
			if (filterFocused && filterCursor >= visibleStart) {
				int cursorX = textLeft + font.width(filterQuery.substring(visibleStart, filterCursor));
				graphics.fill(cursorX, inputTop + 4, cursorX + 1, inputTop + 17, 0xFFFFFFFF);
			}
		}
		if (!filterQuery.isEmpty()) {
			boolean clearHovered = inside(mouseX, mouseY, inputRight - 20, inputTop + 1, inputRight - 1, inputTop + 19);
			graphics.centeredText(font, "×", inputRight - 10, inputTop + 6,
					clearHovered ? 0xFFFF7777 : 0xFF9CA8B7);
		}
		if (!phoneticToggleVisible()) return;
		boolean phoneticEnabled = romajiToggleVisible() ? romajiSearchEnabled : pinyinSearchEnabled;
		boolean toggleHovered = inside(mouseX, mouseY, toggleLeft, inputTop, right - 10, inputTop + 20);
		graphics.fill(toggleLeft, inputTop, right - 10, inputTop + 20,
				phoneticEnabled ? (toggleHovered ? 0xFF8B641D : 0xFF664B1A)
						: (toggleHovered ? 0xFF394657 : 0xFF252E3A));
		graphics.outline(toggleLeft, inputTop, toggleWidth, 20,
				phoneticEnabled ? 0xFFFFC857 : 0xFF526174);
		graphics.centeredText(font, (romajiToggleVisible() ? "ローマ字 " : "首拼 ") + (phoneticEnabled ? LuaUiText.text("开") : LuaUiText.text("关")),
				toggleLeft + toggleWidth / 2, inputTop + 6,
				phoneticEnabled ? 0xFFFFE39A : 0xFFABB6C4);
	}

	private void renderNavigation(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int right, int bottom) {
		List<TreeRow> rows = treeRows();
		int visibleRows = Math.max(1, (bottom - top - 4) / ROW_HEIGHT);
		navigationScroll = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), navigationScroll));
		graphics.enableScissor(left + 1, top + 1, right, bottom - 1);
		for (int visual = 0; visual < visibleRows; visual++) {
			int index = navigationScroll + visual;
			if (index >= rows.size()) break;
			TreeRow row = rows.get(index);
			int y = top + 3 + visual * ROW_HEIGHT;
			boolean hovered = inside(mouseX, mouseY, left + 2, y - 1, right - 2, y + ROW_HEIGHT - 2);
			boolean active = row.entry() != null && selected != null && row.entry().symbol().equals(selected.symbol())
					|| row.page() != null && selectedPage != null && row.page().key().equals(selectedPage.key());
			if (active || hovered) {
				graphics.fill(left + 3, y - 1, right - 3, y + ROW_HEIGHT - 2,
						active ? 0xFF493267 : 0xFF222D3D);
			}
			int textX = left + 9 + row.depth() * 14;
			int color = switch (row.type()) {
				case ROOT -> 0xFFD6B7FF;
				case SECTION -> row.section().color();
				case GROUP -> 0xFF8CC8FF;
				case METHOD -> 0xFFD7DFEA;
				case PAGE -> 0xFFFFD479;
			};
			String visibleLabel = font.plainSubstrByWidth(row.label(), Math.max(20, right - textX - 8));
			drawFilterHighlights(graphics, visibleLabel, textX, y + 4);
			graphics.text(font, visibleLabel, textX, y + 4, color);
		}
		graphics.disableScissor();
		if (rows.size() > visibleRows) {
			int trackHeight = bottom - top - 8;
			int thumbHeight = Math.max(18, trackHeight * visibleRows / rows.size());
			int thumbY = top + 4 + (trackHeight - thumbHeight) * navigationScroll
					/ Math.max(1, rows.size() - visibleRows);
			graphics.fill(right - 4, top + 3, right - 2, bottom - 3, 0xFF263140);
			graphics.fill(right - 5, thumbY, right - 1, thumbY + thumbHeight, 0xFF7957A5);
		}
	}

	private void renderDetails(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int right, int bottom) {
		insertButtonBounds = null;
		copyButtonBounds = null;
		pageCodeButtons.clear();
		if (selectedPage != null) {
			renderPageDetails(graphics, mouseX, mouseY, left, top, right, bottom);
			return;
		}
		if (selected == null) {
			textSpans.clear();
			selectableText.setLength(0);
			contentHeight = 0;
			String hint = filterQuery.isBlank() ? LuaUiText.text("展开左侧目录，选择 API 查看文档")
					: LuaUiText.text("没有匹配的 API，请更换筛选关键词");
			graphics.centeredText(font, hint,
					(left + right) / 2, top + 22, 0xFF8E9AAA);
			return;
		}
		LuaApiDocumentation.Entry entry = selected.documentation();
		int contentLeft = left + 14;
		int contentRight = right - 14;
		int availableWidth = Math.max(80, contentRight - contentLeft);
		int y = top + 12 - contentScroll;
		textSpans.clear();
		selectableText.setLength(0);
		graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);

		y = drawDetailText(graphics, selected.symbol(), contentLeft, y, availableWidth, 0xFFD6B7FF, 4);
		if (!entry.ownerModId().equals("mc_lua_ide")) {
			y = drawDetailText(graphics, LuaUiText.text("来源模组：") + entry.ownerModId(), contentLeft, y,
					availableWidth, 0xFFFFB86B, 5);
		}
		if (entry.clientRequired()) {
			y = drawDetailText(graphics,
					LuaUiText.text("⚠ 客户端 Mod 专用：服务器必须开启强制客户端模式，目标玩家必须安装兼容版本"),
					contentLeft, y, availableWidth, 0xFFFFB85C, 7);
		}
		y = drawDetailText(graphics, entry.signature(), contentLeft, y, availableWidth, 0xFF71D8FF, 9);
		y = drawDetailText(graphics, LuaUiText.text("作用：") + entry.summary(), contentLeft, y, availableWidth, 0xFFF0F3F7, 5);
		y = drawDetailText(graphics, LuaUiText.text("返回：") + entry.returns(), contentLeft, y, availableWidth, 0xFF7CE6A3, 8);

		List<String> parameters = entry.parameters();
		if (parameters.isEmpty()) {
			y = drawDetailText(graphics, LuaUiText.text("参数：无"), contentLeft, y, availableWidth, 0xFFB4BECC, 8);
		} else {
			for (int index = 0; index < parameters.size(); index++) {
				y = drawDetailText(graphics, LuaUiText.text("参数") + (index + 1) + "：" + entry.describe(parameters.get(index)),
						contentLeft, y, availableWidth, 0xFFFFD479, 3);
			}
			y += 5;
		}
		if (!entry.details().isEmpty()) {
			y = drawDetailText(graphics, LuaUiText.text("说明：") + entry.details(), contentLeft, y,
					availableWidth, 0xFFE7A8FF, 8);
		}

		y = drawDetailText(graphics, LuaUiText.text("详细模板"), contentLeft, y, availableWidth, 0xFFD6B7FF, 4);
		String template = LuaApiDocumentation.usageTemplate(selected.symbol()).stripTrailing();
		List<String> codeLines = List.of(template.split("\\R", -1));
		int codeHeight = 27 + codeLines.size() * (font.lineHeight + 3) + 7;
		graphics.fill(contentLeft, y, contentRight, y + codeHeight, 0xFF090D13);
		graphics.outline(contentLeft, y, availableWidth, codeHeight, 0xFF3E5067);
		graphics.fill(contentLeft + 1, y + 1, contentRight - 1, y + 24, 0xFF1A2431);
		graphics.text(font, LuaUiText.text("Lua · 可直接运行"), contentLeft + 8, y + 8, 0xFF73D7FF);
		int insertLeft = contentRight - 112;
		int copyLeft = contentRight - 54;
		insertButtonBounds = new ButtonBounds(insertLeft, y + 4, copyLeft - 4, y + 21);
		copyButtonBounds = new ButtonBounds(copyLeft, y + 4, contentRight - 5, y + 21);
		boolean insertHovered = inside(mouseX, mouseY, insertLeft, y + 4, copyLeft - 4, y + 21);
		boolean copyHovered = inside(mouseX, mouseY, copyLeft, y + 4, contentRight - 5, y + 21);
		graphics.fill(insertLeft, y + 4, copyLeft - 4, y + 21, insertHovered ? 0xFF654594 : 0xFF3C3151);
		graphics.centeredText(font, LuaUiText.text("插入"), (insertLeft + copyLeft - 4) / 2, y + 8, 0xFFFFFFFF);
		graphics.fill(copyLeft, y + 4, contentRight - 5, y + 21, copyHovered ? 0xFF2874A6 : 0xFF29394C);
		graphics.centeredText(font, LuaUiText.text("复制"), (copyLeft + contentRight - 5) / 2, y + 8, 0xFFFFFFFF);
		int codeY = y + 30;
		for (String line : codeLines) {
			String visible = font.plainSubstrByWidth(line, availableWidth - 18);
			Component highlighted = LuaSyntaxHighlighter.highlightSnippet(visible, LuaSyntaxHighlighter.Theme.DARK);
			drawSelectableText(graphics, visible, highlighted, contentLeft + 9, codeY, 0xFFFFFFFF);
			codeY += font.lineHeight + 3;
		}
		y += codeHeight + 10;
		y = drawDetailText(graphics, LuaUiText.text("使用提示"), contentLeft, y, availableWidth, 0xFFD6B7FF, 4);
		y = drawDetailText(graphics, usageHint(selected), contentLeft, y, availableWidth, 0xFFB7C5D8, 5);

		contentHeight = Math.max(0, y + contentScroll - top + 10);
		contentScroll = Math.max(0, Math.min(maxContentScroll(top, bottom), contentScroll));
		graphics.disableScissor();
		renderDetailScrollbar(graphics, left, top, right, bottom);
	}

	private void renderPageDetails(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int right, int bottom) {
		LuaApiDocumentation.DocumentPage page = selectedPage;
		McLuaIdeDocsApi.Translation translation = page.translation();
		int contentLeft = left + 14;
		int contentRight = right - 14;
		int availableWidth = Math.max(80, contentRight - contentLeft);
		int y = top + 12 - contentScroll;
		textSpans.clear();
		selectableText.setLength(0);
		graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
		y = drawDetailText(graphics, translation.pageTitle(), contentLeft, y,
				availableWidth, 0xFFD6B7FF, 8);
		y = drawDetailText(graphics, LuaUiText.text("来源模组：") + page.ownerModId(), contentLeft, y,
				availableWidth, 0xFFFFB86B, 10);
		for (McLuaIdeDocsApi.Element element : translation.elements()) {
			switch (element.kind()) {
				case HEADING -> y = drawDetailText(graphics, element.content(), contentLeft, y,
						availableWidth, 0xFF71D8FF, 7);
				case TEXT -> y = drawDetailText(graphics, element.content(), contentLeft, y,
						availableWidth, 0xFFE0E7F0, 9);
				case CALLOUT -> {
					int boxTop = y;
					y = drawDetailText(graphics, LuaUiText.text("提示：") + element.content(), contentLeft + 9, y + 7,
							availableWidth - 18, 0xFFFFD479, 7);
					graphics.outline(contentLeft, boxTop, availableWidth, y - boxTop, 0xFF80623A);
					y += 9;
				}
				case CODE -> y = renderPageCode(graphics, mouseX, mouseY, element.content(),
						contentLeft, contentRight, y, availableWidth);
			}
		}
		contentHeight = Math.max(0, y + contentScroll - top + 10);
		contentScroll = Math.max(0, Math.min(maxContentScroll(top, bottom), contentScroll));
		graphics.disableScissor();
		renderDetailScrollbar(graphics, left, top, right, bottom);
	}

	private int renderPageCode(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			String code, int left, int right, int y, int availableWidth) {
		List<String> lines = List.of(code.split("\\R", -1));
		int codeHeight = 27 + lines.size() * (font.lineHeight + 3) + 7;
		graphics.fill(left, y, right, y + codeHeight, 0xFF090D13);
		graphics.outline(left, y, availableWidth, codeHeight, 0xFF3E5067);
		graphics.fill(left + 1, y + 1, right - 1, y + 24, 0xFF1A2431);
		graphics.text(font, LuaUiText.text("Lua · 示例代码"), left + 8, y + 8, 0xFF73D7FF);
		int insertLeft = right - 112;
		int copyLeft = right - 54;
		ButtonBounds insert = new ButtonBounds(insertLeft, y + 4, copyLeft - 4, y + 21);
		ButtonBounds copy = new ButtonBounds(copyLeft, y + 4, right - 5, y + 21);
		pageCodeButtons.add(new PageCodeButton(insert, copy, code));
		graphics.fill(insert.left(), insert.top(), insert.right(), insert.bottom(),
				insert.contains(mouseX, mouseY) ? 0xFF654594 : 0xFF3C3151);
		graphics.centeredText(font, LuaUiText.text("插入"), (insert.left() + insert.right()) / 2, y + 8, 0xFFFFFFFF);
		graphics.fill(copy.left(), copy.top(), copy.right(), copy.bottom(),
				copy.contains(mouseX, mouseY) ? 0xFF2874A6 : 0xFF29394C);
		graphics.centeredText(font, LuaUiText.text("复制"), (copy.left() + copy.right()) / 2, y + 8, 0xFFFFFFFF);
		int codeY = y + 30;
		for (String line : lines) {
			String visible = font.plainSubstrByWidth(line, availableWidth - 18);
			Component highlighted = LuaSyntaxHighlighter.highlightSnippet(visible, LuaSyntaxHighlighter.Theme.DARK);
			drawSelectableText(graphics, visible, highlighted, left + 9, codeY, 0xFFFFFFFF);
			codeY += font.lineHeight + 3;
		}
		return y + codeHeight + 10;
	}

	private int drawDetailText(GuiGraphicsExtractor graphics, String text, int x, int y,
			int maxWidth, int color, int bottomMargin) {
		for (String line : wrap(text, maxWidth)) {
			drawSelectableText(graphics, line, Component.literal(line), x, y, color);
			y += font.lineHeight + 2;
		}
		return y + bottomMargin;
	}

	private void drawSelectableText(GuiGraphicsExtractor graphics, String text, Component styled,
			int x, int y, int color) {
		int start = selectableText.length();
		selectableText.append(text);
		int end = selectableText.length();
		textSpans.add(new SelectableSpan(x, y, text, start, end));
		drawFilterHighlights(graphics, text, x, y);
		if (hasTextSelection()) {
			int selectionStart = Math.min(selectionAnchor, selectionCaret);
			int selectionEnd = Math.max(selectionAnchor, selectionCaret);
			int overlapStart = Math.max(selectionStart, start);
			int overlapEnd = Math.min(selectionEnd, end);
			if (overlapStart < overlapEnd) {
				int localStart = overlapStart - start;
				int localEnd = overlapEnd - start;
				int highlightLeft = x + font.width(text.substring(0, localStart));
				int highlightRight = x + font.width(text.substring(0, localEnd));
				graphics.fill(highlightLeft, y - 1, Math.max(highlightLeft + 1, highlightRight),
						y + font.lineHeight, 0xB34A82C2);
			}
		}
		graphics.text(font, styled, x, y, color);
		selectableText.append('\n');
	}

	private void drawFilterHighlights(GuiGraphicsExtractor graphics, String text, int x, int y) {
		for (TextRange range : filterMatchRanges(text)) {
			int left = x + font.width(text.substring(0, range.start()));
			int right = x + font.width(text.substring(0, range.end()));
			graphics.fill(left, y - 1, Math.max(left + 1, right), y + font.lineHeight, 0xC08A6819);
		}
	}

	private List<TextRange> filterMatchRanges(String text) {
		String needle = normalizedFilter();
		if (needle.isEmpty() || text.isEmpty()) return List.of();
		List<TextRange> ranges = new ArrayList<>();
		String lower = text.toLowerCase(Locale.ROOT);
		for (int index = lower.indexOf(needle); index >= 0;
				index = lower.indexOf(needle, index + Math.max(1, needle.length()))) {
			ranges.add(new TextRange(index, Math.min(text.length(), index + needle.length())));
		}
		if (pinyinSearchActive()) {
			StringBuilder initials = new StringBuilder();
			List<Integer> starts = new ArrayList<>();
			List<Integer> ends = new ArrayList<>();
			for (int offset = 0; offset < text.length();) {
				int codePoint = text.codePointAt(offset);
				int next = offset + Character.charCount(codePoint);
				String initial = ChineseSearchIndex.initials(text.substring(offset, next));
				for (int index = 0; index < initial.length(); index++) {
					initials.append(initial.charAt(index));
					starts.add(offset);
					ends.add(next);
				}
				offset = next;
			}
			String initialText = initials.toString().toLowerCase(Locale.ROOT);
			for (int index = initialText.indexOf(needle); index >= 0;
					index = initialText.indexOf(needle, index + Math.max(1, needle.length()))) {
				int last = index + needle.length() - 1;
				if (index < starts.size() && last < ends.size()) {
					ranges.add(new TextRange(starts.get(index), ends.get(last)));
				}
			}
		}
		if (LuaUiText.japanese()) {
			for (JapaneseSearchIndex.Range range : JapaneseSearchIndex.ranges(text, needle, romajiSearchEnabled))
				ranges.add(new TextRange(range.start(), range.end()));
		}
		if (ranges.isEmpty()) return List.of();
		ranges.sort(Comparator.comparingInt(TextRange::start).thenComparingInt(TextRange::end));
		List<TextRange> merged = new ArrayList<>();
		for (TextRange range : ranges) {
			if (merged.isEmpty() || range.start() > merged.getLast().end()) {
				merged.add(range);
			} else {
				TextRange previous = merged.removeLast();
				merged.add(new TextRange(previous.start(), Math.max(previous.end(), range.end())));
			}
		}
		return merged;
	}

	private String normalizedFilter() {
		return filterQuery.strip().toLowerCase(Locale.ROOT);
	}

	private List<String> wrap(String text, int maxWidth) {
		if (text.isEmpty()) return List.of("");
		List<String> lines = new ArrayList<>();
		String remaining = text;
		while (!remaining.isEmpty()) {
			String part = font.plainSubstrByWidth(remaining, Math.max(20, maxWidth));
			if (part.isEmpty()) part = remaining.substring(0, 1);
			lines.add(part);
			remaining = remaining.substring(part.length());
		}
		return lines;
	}

	private String usageHint(LuaApiDocumentation.DocumentEntry document) {
		if (!document.documentation().ownerModId().equals("mc_lua_ide")) {
			return "此函数由服务端模组 “" + document.documentation().ownerModId()
					+ "” 注册；执行能力、参数含义和兼容性由该模组提供。";
		}
		String namespace = document.namespace();
		if (Set.of("MC.Item.name", "MC.Block.name", "MC.Entity.name", "MC.Enchantment.name").contains(document.symbol()))
			return "指定 language 获取目标语言名称，不改变客户端／服务端语言。原版缺失语言从官方资源下载缓存；服务端 Mod assets 和本地 lang/<language>.json 可提供翻译。第二返回值说明实际语言，不依赖客户端安装。";
		return switch (namespace) {
			case "Block", "World", "Border", "Structure", "Loot" -> "坐标和世界数据都从服务端读取；dimension 不填时默认使用主世界。资源 ID 可通过“插入”窗口选择。";
			case "Item", "Player", "Chat" -> "涉及玩家的接口必须传入在线玩家 UUID。可按 Ctrl+I 打开插入器并切换到“玩家”分类。";
			case "Entity", "Effect", "Attribute" -> "控制已有实体时传入运行中实体的 UUID；生成实体时传入实体类型资源 ID。玩家操作应优先使用 Player 接口。";
			case "Sound", "Particle", "Title" -> "显示与播放由服务端发送给玩家；资源 ID 支持其他 Mod 注册的内容。";
			case "Score", "Team", "GameRule", "Advancement", "Recipe" -> "此分类封装原版服务端命令并进行参数校验；返回 true 表示命令已提交。";
			case "Registry" -> "注册表在运行时读取，结果包含 Minecraft 与已加载 Mod 注册的资源 ID。";
			case "Command" -> "命令以服务端权限上下文提交，command 参数开头的 / 可以省略。runAs 会改用指定在线玩家身份。";
			case "Event" -> "事件回调在脚本执行线程中按顺序处理。注册后脚本会保持运行，点击停止或 /lua stop 会自动清理回调。";
			default -> LuaUiText.text("点击“复制”可复制完整模板；点击“插入”会把模板直接写入当前编辑器光标位置。");
		};
	}

	private void renderDetailScrollbar(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		int viewport = bottom - top;
		if (contentHeight <= viewport) return;
		int track = viewport - 8;
		int thumbHeight = Math.max(20, track * viewport / contentHeight);
		int thumbY = top + 4 + (track - thumbHeight) * contentScroll / Math.max(1, contentHeight - viewport);
		graphics.fill(right - 5, top + 3, right - 3, bottom - 3, 0xFF263140);
		graphics.fill(right - 6, thumbY, right - 2, thumbY + thumbHeight, 0xFF7957A5);
	}

	private void renderResizeEdges(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int right, int bottom) {
		int edges = resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY);
		int color = 0xFFB084F5;
		if ((edges & EDGE_LEFT) != 0) graphics.fill(left - 1, windowY, left + 2, bottom, color);
		if ((edges & EDGE_RIGHT) != 0) graphics.fill(right - 2, windowY, right + 1, bottom, color);
		if ((edges & EDGE_TOP) != 0) graphics.fill(left, windowY - 1, right, windowY + 2, color);
		if ((edges & EDGE_BOTTOM) != 0) graphics.fill(left, bottom - 2, right, bottom + 1, color);
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (event.key() == InputConstants.KEY_ESCAPE && !filterFocused) {
			close();
			return true;
		}
		if (filterFocused) {
			if (event.key() == InputConstants.KEY_ESCAPE) {
				filterFocused = false;
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
				filterAnchor = 0;
				filterCursor = filterQuery.length();
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_C) {
				if (hasFilterSelection()) minecraft.keyboardHandler.setClipboard(selectedFilterText());
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_X) {
				if (hasFilterSelection()) {
					minecraft.keyboardHandler.setClipboard(selectedFilterText());
					replaceFilterSelection("");
				}
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
				replaceFilterSelection(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " "));
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (hasFilterSelection()) replaceFilterSelection("");
				else if (filterCursor > 0) {
					int start = filterQuery.offsetByCodePoints(filterCursor, -1);
					filterAnchor = start;
					replaceFilterSelection("");
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_DELETE) {
				if (hasFilterSelection()) replaceFilterSelection("");
				else if (filterCursor < filterQuery.length()) {
					filterAnchor = filterQuery.offsetByCodePoints(filterCursor, 1);
					replaceFilterSelection("");
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
				moveFilterCursor(event.key() == InputConstants.KEY_LEFT ? -1 : 1, event.hasShiftDown());
				return true;
			}
			if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
				filterCursor = event.key() == InputConstants.KEY_HOME ? 0 : filterQuery.length();
				if (!event.hasShiftDown()) filterAnchor = filterCursor;
				return true;
			}
			if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
				selectRelative(event.key() == InputConstants.KEY_UP ? -1 : 1);
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				filterFocused = false;
				return true;
			}
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_C && hasTextSelection()) {
			copySelection();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			selectionAnchor = 0;
			selectionCaret = selectableText.length();
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			close();
			return true;
		}
		if (event.hasControlDown()
				&& (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)) {
			selectRelative(event.key() == InputConstants.KEY_UP ? -1 : 1);
			return true;
		}
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		int codepoint = event.codepoint();
		if (filterFocused && !Character.isISOControl(codepoint)
				&& filterQuery.codePointCount(0, filterQuery.length()) < 160) {
			replaceFilterSelection(new String(Character.toChars(codepoint)));
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !pointInside(mouseX, mouseY)) return false;
		int x = panelX();
		int mainTop = mainTop();
		int mainBottom = windowY + windowHeight - FOOTER_HEIGHT;
		if (mouseY < mainTop || mouseY > mainBottom) return true;
		if (mouseX <= x + sidebarWidth()) {
			int visibleRows = Math.max(1, (mainBottom - mainTop - 4) / ROW_HEIGHT);
			navigationScroll = Math.max(0, Math.min(Math.max(0, treeRows().size() - visibleRows),
					navigationScroll - (int) Math.round(amount * 3)));
		} else {
			contentScroll = Math.max(0, Math.min(maxContentScroll(mainTop, mainBottom),
					contentScroll - (int) Math.round(amount * 32)));
		}
		return true;
	}

	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!open || progress < 0.5F) return false;
		int x = panelX();
		int right = x + windowWidth;
		int bottom = windowY + windowHeight;
		if (inside(event.x(), event.y(), right - 26, windowY + 4, right - 6, windowY + 24)) {
			close();
			return true;
		}
		int edges = resizeEdgesAt(event.x(), event.y());
		if (edges != 0) {
			keyboardFocused = true;
			resizeEdges = edges;
			resizeStartMouseX = event.x();
			resizeStartMouseY = event.y();
			resizeStartX = x;
			resizeStartY = windowY;
			resizeStartWidth = windowWidth;
			resizeStartHeight = windowHeight;
			return true;
		}
		if (!pointInside(event.x(), event.y())) return false;
		keyboardFocused = true;
		if (inside(event.x(), event.y(), x, windowY, right, windowY + TITLE_HEIGHT)) {
			if (dockSide.docked() && layoutLocked) return true;
			filterFocused = false;
			dragging = true;
			dragOffsetX = event.x() - x;
			dragOffsetY = event.y() - windowY;
			return true;
		}

		int filterTop = windowY + TITLE_HEIGHT + BREADCRUMB_HEIGHT;
		int filterLeft = x + 10;
		int toggleWidth = phoneticToggleWidth();
		int toggleLeft = right - toggleWidth - 10;
		int filterRight = filterInputRight(right);
		if (phoneticToggleVisible() && inside(event.x(), event.y(), toggleLeft, filterTop + 5, right - 10, filterTop + 25)) {
			if (romajiToggleVisible()) romajiSearchEnabled = !romajiSearchEnabled;
			else pinyinSearchEnabled = !pinyinSearchEnabled;
			onFilterChanged();
			filterFocused = true;
			return true;
		}
		if (inside(event.x(), event.y(), filterLeft, filterTop + 5, filterRight, filterTop + 25)) {
			if (!filterQuery.isEmpty() && event.x() >= filterRight - 20) {
				setFilterQuery("");
				filterFocused = true;
				return true;
			}
			filterFocused = true;
			filterCursor = filterOffsetAt(event.x(), filterLeft + 22, filterRight - 24);
			filterAnchor = filterCursor;
			return true;
		}
		filterFocused = false;
		int mainTop = mainTop();
		int mainBottom = bottom - FOOTER_HEIGHT;
		int dividerX = x + sidebarWidth();
		if (event.button() == 0 && event.y() >= mainTop && event.y() < mainBottom
				&& Math.abs(event.x() - dividerX) <= 5) {
			draggingSidebarDivider = true;
			return true;
		}
		if (event.y() >= mainTop && event.y() <= mainBottom && event.x() <= dividerX) {
			int visual = Math.max(0, (int) (event.y() - mainTop - 3) / ROW_HEIGHT);
			List<TreeRow> rows = treeRows();
			int index = navigationScroll + visual;
			if (index < rows.size()) activate(rows.get(index));
			return true;
		}
		if ((selected != null || selectedPage != null) && event.x() > dividerX
				&& event.y() >= mainTop && event.y() <= mainBottom) {
			if (selectedPage != null) {
				for (PageCodeButton button : pageCodeButtons) {
					if (button.copy().contains(event.x(), event.y())) {
						minecraft.keyboardHandler.setClipboard(button.code());
						return true;
					}
					if (button.insert().contains(event.x(), event.y())) {
						insertion.accept(button.code());
						return true;
					}
				}
			}
			if (copyButtonBounds != null && copyButtonBounds.contains(event.x(), event.y())) {
				minecraft.keyboardHandler.setClipboard(LuaApiDocumentation.usageTemplate(selected.symbol()));
				return true;
			}
			if (insertButtonBounds != null && insertButtonBounds.contains(event.x(), event.y())) {
				insertion.accept(LuaApiDocumentation.usageTemplate(selected.symbol()));
				return true;
			}
			int offset = textOffsetAt(event.x(), event.y());
			if (doubleClick) {
				selectWord(offset);
			} else {
				selectionAnchor = offset;
				selectionCaret = offset;
				selectingText = true;
			}
			return true;
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (draggingSidebarDivider) {
			preferredSidebarWidth = (int) Math.round(event.x() - panelX());
			preferredSidebarWidth = sidebarWidth();
			return true;
		}
		if (resizeEdges != 0) {
			resize(event.x(), event.y());
			return true;
		}
		if (dragging) {
			if (dockSide.docked()) undockForDrag(event.x(), event.y());
			windowX = Math.max(0, Math.min(screenWidth - windowWidth,
					(int) Math.round(event.x() - dragOffsetX)));
			windowY = Math.max(0, Math.min(screenHeight - windowHeight,
					(int) Math.round(event.y() - dragOffsetY)));
			boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
			return true;
		}
		if (selectingText) {
			int mainTop = mainTop();
			int mainBottom = windowY + windowHeight - FOOTER_HEIGHT;
			if (event.y() < mainTop + 8) contentScroll = Math.max(0, contentScroll - 12);
			if (event.y() > mainBottom - 8) contentScroll = Math.min(maxContentScroll(mainTop, mainBottom), contentScroll + 12);
			selectionCaret = textOffsetAt(event.x(), event.y());
			return true;
		}
		return false;
	}

	boolean mouseReleased() {
		if (!isInteracting()) return false;
		dragging = false;
		selectingText = false;
		resizeEdges = 0;
		draggingSidebarDivider = false;
		return true;
	}

	private int filterVisibleStart(int maxWidth) {
		int start = filterCursor;
		while (start > 0) {
			int previous = filterQuery.offsetByCodePoints(start, -1);
			if (font.width(filterQuery.substring(previous, filterCursor)) > maxWidth) break;
			start = previous;
		}
		return start;
	}

	private int filterOffsetAt(double mouseX, int textLeft, int textRight) {
		int maxWidth = Math.max(20, textRight - textLeft);
		int start = filterVisibleStart(maxWidth);
		String visible = font.plainSubstrByWidth(filterQuery.substring(start), maxWidth);
		int end = start + visible.length();
		if (mouseX <= textLeft) return start;
		int previousWidth = 0;
		for (int index = start; index < end;) {
			int next = filterQuery.offsetByCodePoints(index, 1);
			int nextWidth = font.width(filterQuery.substring(start, next));
			if (mouseX < textLeft + (previousWidth + nextWidth) / 2.0) return index;
			previousWidth = nextWidth;
			index = next;
		}
		return end;
	}

	private boolean hasFilterSelection() {
		return filterCursor != filterAnchor;
	}

	private String selectedFilterText() {
		int start = Math.min(filterCursor, filterAnchor);
		int end = Math.max(filterCursor, filterAnchor);
		return filterQuery.substring(start, end);
	}

	private void replaceFilterSelection(String replacement) {
		int start = Math.min(filterCursor, filterAnchor);
		int end = Math.max(filterCursor, filterAnchor);
		String clean = replacement == null ? "" : replacement;
		String next = filterQuery.substring(0, start) + clean + filterQuery.substring(end);
		if (next.codePointCount(0, next.length()) > 160) {
			int allowed = Math.max(0, 160 - filterQuery.substring(0, start).codePointCount(0, start)
					- filterQuery.substring(end).codePointCount(0, filterQuery.length() - end));
			int replacementPoints = clean.codePointCount(0, clean.length());
			if (replacementPoints > allowed) clean = clean.substring(0, clean.offsetByCodePoints(0, allowed));
			next = filterQuery.substring(0, start) + clean + filterQuery.substring(end);
		}
		filterQuery = next;
		filterCursor = start + clean.length();
		filterAnchor = filterCursor;
		onFilterChanged();
	}

	private void setFilterQuery(String value) {
		filterQuery = value == null ? "" : value;
		filterCursor = filterQuery.length();
		filterAnchor = filterCursor;
		onFilterChanged();
	}

	private void moveFilterCursor(int direction, boolean selecting) {
		if (!selecting && hasFilterSelection()) {
			filterCursor = direction < 0 ? Math.min(filterCursor, filterAnchor) : Math.max(filterCursor, filterAnchor);
			filterAnchor = filterCursor;
			return;
		}
		if (direction < 0 && filterCursor > 0) filterCursor = filterQuery.offsetByCodePoints(filterCursor, -1);
		if (direction > 0 && filterCursor < filterQuery.length()) filterCursor = filterQuery.offsetByCodePoints(filterCursor, 1);
		if (!selecting) filterAnchor = filterCursor;
	}

	private void onFilterChanged() {
		searchCollapsedSections.clear(); searchCollapsedGroups.clear();
		clearPendingSelection();
		List<LuaApiDocumentation.DocumentEntry> filtered = filteredEntries();
		List<LuaApiDocumentation.DocumentPage> filteredDocumentationPages = filteredPages();
		if (!filterQuery.isBlank()) rootExpanded = true;
		navigationScroll = 0;
		contentScroll = 0;
		selectionAnchor = -1;
		selectionCaret = -1;
		if (selectedPage != null && !filteredDocumentationPages.contains(selectedPage)) selectedPage = null;
		if (selectedPage == null && !filtered.contains(selected)) {
			selected = filtered.isEmpty() ? null : filtered.getFirst();
			if (selected == null && !filteredDocumentationPages.isEmpty()) selectedPage = filteredDocumentationPages.getFirst();
		}
	}

	private boolean hasTextSelection() {
		return selectionAnchor >= 0 && selectionCaret >= 0 && selectionAnchor != selectionCaret;
	}

	private void copySelection() {
		int start = Math.max(0, Math.min(selectionAnchor, selectionCaret));
		int end = Math.min(selectableText.length(), Math.max(selectionAnchor, selectionCaret));
		minecraft.keyboardHandler.setClipboard(selectableText.substring(start, end));
	}

	private int textOffsetAt(double mouseX, double mouseY) {
		if (textSpans.isEmpty()) return 0;
		SelectableSpan closest = textSpans.getFirst();
		double closestDistance = Double.MAX_VALUE;
		for (SelectableSpan span : textSpans) {
			double verticalDistance = mouseY < span.y() ? span.y() - mouseY
					: mouseY > span.y() + font.lineHeight ? mouseY - span.y() - font.lineHeight : 0;
			if (verticalDistance < closestDistance) {
				closest = span;
				closestDistance = verticalDistance;
			}
			if (verticalDistance == 0) break;
		}
		if (closest.text().isEmpty() || mouseX <= closest.x()) return closest.start();
		int previousWidth = 0;
		for (int index = 1; index <= closest.text().length(); index++) {
			int nextWidth = font.width(closest.text().substring(0, index));
			if (mouseX < closest.x() + (previousWidth + nextWidth) / 2.0) return closest.start() + index - 1;
			previousWidth = nextWidth;
		}
		return closest.end();
	}

	private void selectWord(int offset) {
		if (selectableText.isEmpty()) return;
		int cursor = Math.max(0, Math.min(selectableText.length() - 1, offset));
		if (Character.isWhitespace(selectableText.charAt(cursor)) && cursor > 0) cursor--;
		int start = cursor;
		int end = cursor + 1;
		while (start > 0 && isWordCharacter(selectableText.charAt(start - 1))) start--;
		while (end < selectableText.length() && isWordCharacter(selectableText.charAt(end))) end++;
		selectionAnchor = start;
		selectionCaret = end;
		selectingText = false;
	}

	private static boolean isWordCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_';
	}

	private void activate(TreeRow row) {
		switch (row.type()) {
			case ROOT -> rootExpanded = !rootExpanded;
			case SECTION -> {
				Set<String> branches = filterQuery.isBlank() ? expandedSections : searchCollapsedSections;
				if (!branches.add(row.section().key())) branches.remove(row.section().key());
			}
			case GROUP -> {
				String key = groupKey(row.section(), row.group());
				Set<String> branches = filterQuery.isBlank() ? expandedGroups : searchCollapsedGroups;
				if (!branches.add(key)) branches.remove(key);
			}
			case METHOD -> {
				selected = row.entry();
				selectedPage = null;
				clearPendingSelection();
				contentScroll = 0;
				selectionAnchor = -1;
				selectionCaret = -1;
			}
			case PAGE -> {
				selectedPage = row.page();
				selected = null;
				clearPendingSelection();
				contentScroll = 0;
				selectionAnchor = -1;
				selectionCaret = -1;
			}
		}
	}

	private void selectRelative(int direction) {
		List<TreeRow> visible = treeRows().stream()
				.filter(row -> row.type() == RowType.METHOD || row.type() == RowType.PAGE).toList();
		if (visible.isEmpty()) return;
		int current = -1;
		for (int index = 0; index < visible.size(); index++) {
			TreeRow row = visible.get(index);
			if (row.entry() != null && row.entry().equals(selected)
					|| row.page() != null && row.page().equals(selectedPage)) {
				current = index;
				break;
			}
		}
		if (current < 0) current = direction > 0 ? -1 : 0;
		TreeRow target = visible.get(Math.floorMod(current + direction, visible.size()));
		selected = target.entry();
		selectedPage = target.page();
		clearPendingSelection();
		rootExpanded = true;
		expandSelectedPath();
		contentScroll = 0;
		selectionAnchor = -1;
		selectionCaret = -1;
		ensureSelectedNavigationVisible();
	}

	private void ensureSelectedNavigationVisible() {
		List<TreeRow> rows = treeRows();
		int rowIndex = 0;
		for (int index = 0; index < rows.size(); index++) {
			if (rows.get(index).entry() != null && rows.get(index).entry().equals(selected)
					|| rows.get(index).page() != null && rows.get(index).page().equals(selectedPage)) {
				rowIndex = index;
				break;
			}
		}
		int mainHeight = windowHeight - TITLE_HEIGHT - BREADCRUMB_HEIGHT - FILTER_HEIGHT - FOOTER_HEIGHT;
		int visibleRows = Math.max(1, (mainHeight - 4) / ROW_HEIGHT);
		if (rowIndex < navigationScroll) navigationScroll = rowIndex;
		if (rowIndex >= navigationScroll + visibleRows) navigationScroll = rowIndex - visibleRows + 1;
	}

	private List<TreeRow> treeRows() {
		List<LuaApiDocumentation.DocumentEntry> filtered = filteredEntries();
		List<LuaApiDocumentation.DocumentPage> filteredDocumentationPages = filteredPages();
		List<TreeRow> rows = new ArrayList<>();
		String total = filterQuery.isBlank() ? Integer.toString(entries.size() + pages.size())
				: (filtered.size() + filteredDocumentationPages.size()) + " / " + (entries.size() + pages.size());
		rows.add(new TreeRow(RowType.ROOT, 0, (rootExpanded ? "▾ " : "▸ ") + "Lua API (" + total + ")",
				null, null, null));
		if (!rootExpanded) return rows;
		for (ApiSection section : sections(filtered, filteredDocumentationPages)) {
			List<LuaApiDocumentation.DocumentEntry> sectionEntries = filtered.stream()
					.filter(section::contains).toList();
			List<LuaApiDocumentation.DocumentPage> sectionPages = filteredDocumentationPages.stream()
					.filter(section::contains).toList();
			if (sectionEntries.isEmpty() && sectionPages.isEmpty()) continue;
			boolean sectionExpanded = filterQuery.isBlank() ? expandedSections.contains(section.key())
					: !searchCollapsedSections.contains(section.key());
			rows.add(new TreeRow(RowType.SECTION, 1,
					(sectionExpanded ? "▾ " : "▸ ") + section.label() + " ("
							+ (sectionEntries.size() + sectionPages.size()) + ")",
					section, null, null));
			if (!sectionExpanded) continue;
			if (!sectionPages.isEmpty()) {
				for (LuaApiDocumentation.DocumentPage page : sectionPages) {
					rows.add(new TreeRow(RowType.PAGE, 2, page.translation().pageTitle(),
							section, null, null, page));
				}
				continue;
			}
			if (!section.grouped()) {
				for (LuaApiDocumentation.DocumentEntry entry : sectionEntries) {
					rows.add(new TreeRow(RowType.METHOD, 2,
							entry.methodName() + " - " + entry.documentation().summary(),
							section, null, entry));
				}
				continue;
			}
			for (Map.Entry<String, List<LuaApiDocumentation.DocumentEntry>> group : groups.entrySet()) {
				List<LuaApiDocumentation.DocumentEntry> groupEntries = group.getValue().stream()
						.filter(filtered::contains).filter(section::contains).toList();
				if (groupEntries.isEmpty()) continue;
				boolean expanded = filterQuery.isBlank() ? expandedGroups.contains(groupKey(section, group.getKey()))
						: !searchCollapsedGroups.contains(groupKey(section, group.getKey()));
				rows.add(new TreeRow(RowType.GROUP, 2,
						(expanded ? "▾ " : "▸ ") + groupLabel(group.getKey()) + " (" + groupEntries.size() + ")",
						section, group.getKey(), null));
				if (expanded) {
					for (LuaApiDocumentation.DocumentEntry entry : groupEntries) {
						rows.add(new TreeRow(RowType.METHOD, 3,
								entry.methodName() + " - " + entry.documentation().summary(),
								section, group.getKey(), entry));
					}
				}
			}
		}
		return rows;
	}

	private List<LuaApiDocumentation.DocumentEntry> filteredEntries() {
		String needle = filterQuery.strip().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) return entries;
		return entries.stream().filter(entry -> matchesFilter(entry, needle)).toList();
	}

	private boolean matchesFilter(LuaApiDocumentation.DocumentEntry entry, String needle) {
		LuaApiDocumentation.Entry documentation = entry.documentation();
		StringBuilder searchable = new StringBuilder()
				.append(entry.symbol()).append(' ')
				.append(sectionFor(entry).label()).append(' ')
				.append(entry.apiRoot()).append(' ')
				.append(entry.namespace()).append(' ')
				.append(groupLabel(entry.namespace())).append(' ')
				.append(documentation.signature()).append(' ')
				.append(documentation.summary()).append(' ')
				.append(documentation.returns()).append(' ')
				.append(documentation.details());
		for (String parameter : documentation.parameters()) {
			searchable.append(' ').append(documentation.describe(parameter));
		}
		String text = searchable.toString().toLowerCase(Locale.ROOT);
		return text.contains(needle)
				|| pinyinSearchActive() && ChineseSearchIndex.initials(text).contains(needle)
				|| LuaUiText.japanese() && JapaneseSearchIndex.matches(text, needle, romajiSearchEnabled);
	}

	private Map<String, List<LuaApiDocumentation.DocumentEntry>> buildGroups() {
		Map<String, List<LuaApiDocumentation.DocumentEntry>> result = new LinkedHashMap<>();
		for (LuaApiDocumentation.DocumentEntry entry : entries) {
			result.computeIfAbsent(entry.namespace(), ignored -> new ArrayList<>()).add(entry);
		}
		return result;
	}

	private void expandSelectedPath() {
		if (selected == null && selectedPage == null) return;
		ApiSection section = selectedPage != null ? sectionFor(selectedPage) : sectionFor(selected);
		if (filterQuery.isBlank()) {
			expandedSections.add(section.key());
			if (section.grouped() && selected != null) expandedGroups.add(groupKey(section, selected.namespace()));
		} else {
			searchCollapsedSections.remove(section.key());
			if (section.grouped() && selected != null) searchCollapsedGroups.remove(groupKey(section, selected.namespace()));
		}
	}

	private static String groupKey(ApiSection section, String namespace) {
		return section.key() + ':' + namespace;
	}

	private String breadcrumb() {
		if (selectedPage != null) return LuaUiText.text("API 文档  >  ", "API Reference  >  ") + sectionFor(selectedPage).label()
				+ "  >  " + selectedPage.translation().pageTitle();
		if (selected == null) return LuaUiText.text("API 文档  >  Lua API");
		ApiSection section = sectionFor(selected);
		StringBuilder result = new StringBuilder(LuaUiText.text("API 文档  >  ", "API Reference  >  ")).append(section.label());
		if (section.grouped()) result.append("  >  ").append(groupLabel(selected.namespace()));
		return result.append("  >  ").append(selected.methodName()).toString();
	}

	private List<ApiSection> sections(List<LuaApiDocumentation.DocumentEntry> source,
			List<LuaApiDocumentation.DocumentPage> pageSource) {
		List<ApiSection> result = new ArrayList<>();
		if (source.stream().anyMatch(CLIENT_SECTION::contains)) result.add(CLIENT_SECTION);
		Map<String, ApiSection> pageSections = new LinkedHashMap<>();
		for (LuaApiDocumentation.DocumentPage page : pageSource) {
			ApiSection section = sectionFor(page);
			pageSections.putIfAbsent(section.key(), section);
		}
		result.addAll(pageSections.values());
		Map<String, String> customRoots = new LinkedHashMap<>();
		for (LuaApiDocumentation.DocumentEntry entry : source) {
			if (!entry.documentation().clientRequired() && !entry.apiRoot().equals("MC")) {
				customRoots.putIfAbsent(entry.apiRoot().toLowerCase(Locale.ROOT), entry.apiRoot());
			}
		}
		customRoots.values().forEach(root -> result.add(customSection(root)));
		if (source.stream().anyMatch(SERVER_SECTION::contains)) result.add(SERVER_SECTION);
		return result;
	}

	private ApiSection sectionFor(LuaApiDocumentation.DocumentEntry entry) {
		if (entry.documentation().clientRequired()) return CLIENT_SECTION;
		return entry.apiRoot().equals("MC") ? SERVER_SECTION : customSection(entry.apiRoot());
	}

	private ApiSection customSection(String apiRoot) {
		String translated = entries.stream()
				.filter(entry -> entry.apiRoot().equals(apiRoot))
				.map(LuaApiDocumentation.DocumentEntry::namespaceLabel)
				.filter(label -> !label.isBlank())
				.findFirst().orElse(apiRoot + " API");
		String label = translated.toLowerCase(Locale.ROOT).contains(apiRoot.toLowerCase(Locale.ROOT))
				? translated : translated + " · " + apiRoot;
		return new ApiSection("root:" + apiRoot.toLowerCase(Locale.ROOT), "◆ " + label,
				0xFF67D5D0, apiRoot, false, false);
	}

	private String groupLabel(String namespace) {
		String builtIn = GROUP_LABELS.get(namespace);
		if (builtIn != null) return LuaUiText.text(builtIn);
		for (LuaApiDocumentation.DocumentEntry entry : entries) {
			if (!entry.namespace().equals(namespace)) continue;
			String label = entry.namespaceLabel();
			return label.equals(namespace) || label.contains(namespace) ? label : label + " " + namespace;
		}
		return namespace;
	}

	private int sidebarWidth() {
		int minimum = Math.min(160, Math.max(80, windowWidth / 3));
		int maximum = Math.max(minimum, windowWidth - Math.min(220, Math.max(110, windowWidth / 2)));
		int desired = preferredSidebarWidth < 0 ? Math.min(280, Math.max(190, windowWidth / 3))
				: preferredSidebarWidth;
		return Math.clamp(desired, minimum, maximum);
	}

	private ApiSection sectionFor(LuaApiDocumentation.DocumentPage page) {
		return new ApiSection("docs:" + page.ownerModId() + ':' + page.categoryId(),
				"◆ " + page.translation().categoryTitle(), 0xFFFFD479, "", false, false);
	}

	private List<LuaApiDocumentation.DocumentPage> filteredPages() {
		String needle = normalizedFilter();
		if (needle.isEmpty()) return pages;
		return pages.stream().filter(page -> {
			StringBuilder searchable = new StringBuilder(page.ownerModId()).append(' ')
					.append(page.categoryId()).append(' ').append(page.pageId()).append(' ')
					.append(page.translation().categoryTitle()).append(' ')
					.append(page.translation().pageTitle());
			for (McLuaIdeDocsApi.Element element : page.translation().elements()) {
				searchable.append(' ').append(element.content());
			}
			String text = searchable.toString().toLowerCase(Locale.ROOT);
			return text.contains(needle) || pinyinSearchActive() && ChineseSearchIndex.initials(text).contains(needle)
					|| LuaUiText.japanese() && JapaneseSearchIndex.matches(text, needle, romajiSearchEnabled);
		}).toList();
	}

	private void refreshDocumentation() {
		if (documentationRevision == LuaApiDocumentation.revision()) return;
		String selectedSymbol = selected == null ? pendingSelectedSymbol : selected.symbol();
		String selectedPageKey = selectedPage == null ? pendingSelectedPageKey : selectedPage.key();
		entries = LuaApiDocumentation.allEntries();
		pages = LuaApiDocumentation.allPages();
		groups = buildGroups();
		resolveSelection(selectedSymbol, selectedPageKey);
		documentationRevision = LuaApiDocumentation.revision();
	}

	private void resolveSelection(String selectedSymbol, String selectedPageKey) {
		selected = selectedSymbol.isEmpty() ? null : entries.stream()
				.filter(entry -> entry.symbol().equals(selectedSymbol)).findFirst().orElse(null);
		selectedPage = selectedPageKey.isEmpty() ? null : pages.stream()
				.filter(page -> page.key().equals(selectedPageKey)).findFirst().orElse(null);
		pendingSelectedSymbol = selected == null ? selectedSymbol : "";
		pendingSelectedPageKey = selectedPage == null ? selectedPageKey : "";
	}

	private void clearPendingSelection() {
		pendingSelectedSymbol = pendingSelectedPageKey = "";
	}

	private int maxContentScroll(int top, int bottom) {
		return Math.max(0, contentHeight - Math.max(1, bottom - top));
	}

	private int mainTop() {
		return windowY + TITLE_HEIGHT + BREADCRUMB_HEIGHT + FILTER_HEIGHT;
	}

	private int panelX() {
		float eased = 1.0F - (1.0F - progress) * (1.0F - progress);
		return Math.round(screenWidth + (windowX - screenWidth) * eased);
	}

	private boolean pointInside(double mouseX, double mouseY) {
		int x = panelX();
		return inside(mouseX, mouseY, x, windowY, x + windowWidth, windowY + windowHeight);
	}

	private int resizeEdgesAt(double mouseX, double mouseY) {
		if (dockSide.docked()) return 0;
		int x = panelX();
		int right = x + windowWidth;
		int bottom = windowY + windowHeight;
		if (mouseX < x - RESIZE_MARGIN || mouseX > right + RESIZE_MARGIN
				|| mouseY < windowY - RESIZE_MARGIN || mouseY > bottom + RESIZE_MARGIN) return 0;
		int edges = 0;
		if (Math.abs(mouseX - x) <= RESIZE_MARGIN) edges |= EDGE_LEFT;
		if (Math.abs(mouseX - right) <= RESIZE_MARGIN) edges |= EDGE_RIGHT;
		if (Math.abs(mouseY - windowY) <= RESIZE_MARGIN) edges |= EDGE_TOP;
		if (Math.abs(mouseY - bottom) <= RESIZE_MARGIN) edges |= EDGE_BOTTOM;
		return edges;
	}

	private void resize(double mouseX, double mouseY) {
		int deltaX = (int) Math.round(mouseX - resizeStartMouseX);
		int deltaY = (int) Math.round(mouseY - resizeStartMouseY);
		int left = resizeStartX;
		int right = resizeStartX + resizeStartWidth;
		int top = resizeStartY;
		int bottom = resizeStartY + resizeStartHeight;
		int minimumWidth = Math.min(MIN_WIDTH, screenWidth);
		int minimumHeight = Math.min(MIN_HEIGHT, screenHeight);
		if ((resizeEdges & EDGE_LEFT) != 0) left = Math.max(0, Math.min(right - minimumWidth, resizeStartX + deltaX));
		if ((resizeEdges & EDGE_RIGHT) != 0) right = Math.min(screenWidth,
				Math.max(left + minimumWidth, resizeStartX + resizeStartWidth + deltaX));
		if ((resizeEdges & EDGE_TOP) != 0) top = Math.max(0, Math.min(bottom - minimumHeight, resizeStartY + deltaY));
		if ((resizeEdges & EDGE_BOTTOM) != 0) bottom = Math.min(screenHeight,
				Math.max(top + minimumHeight, resizeStartY + resizeStartHeight + deltaY));
		windowX = left;
		windowY = top;
		windowWidth = right - left;
		windowHeight = bottom - top;
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
		contentScroll = Math.min(contentScroll, maxContentScroll(
				mainTop(), windowY + windowHeight - FOOTER_HEIGHT));
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0) return;
		if (dockSide.docked()) return;
		int minimumWidth = Math.min(MIN_WIDTH, screenWidth);
		int minimumHeight = Math.min(MIN_HEIGHT, screenHeight);
		if (windowWidth < 0) windowWidth = Math.min(screenWidth - 16, Math.max(minimumWidth, (int) (screenWidth * 0.84F)));
		if (windowHeight < 0) windowHeight = Math.min(screenHeight - 16, Math.max(minimumHeight, (int) (screenHeight * 0.86F)));
		windowWidth = Math.max(minimumWidth, Math.min(windowWidth, screenWidth));
		windowHeight = Math.max(minimumHeight, Math.min(windowHeight, screenHeight));
		if (windowX < 0) windowX = Math.max(0, (screenWidth - windowWidth) / 2);
		windowX = Math.max(0, Math.min(screenWidth - windowWidth, windowX));
		windowY = Math.max(0, Math.min(screenHeight - windowHeight, windowY));
	}

	private void rememberFloatingGeometry() {
		floatingX = windowX;
		floatingY = windowY;
		floatingWidth = windowWidth;
		floatingHeight = windowHeight;
	}

	private void restoreFloatingGeometry() {
		if (floatingWidth > 0) windowWidth = floatingWidth;
		if (floatingHeight > 0) windowHeight = floatingHeight;
		if (floatingX >= 0) windowX = floatingX;
		windowY = floatingY;
		ensureGeometry();
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private void undockForDrag(double mouseX, double mouseY) {
		dockSide = DockSide.FLOATING;
		restoreFloatingGeometry();
		dragOffsetX = Math.min(Math.max(18.0, dragOffsetX), Math.max(18.0, windowWidth - 18.0));
		dragOffsetY = 12.0;
		windowX = Math.max(0, Math.min(screenWidth - windowWidth, (int) Math.round(mouseX - dragOffsetX)));
		windowY = Math.max(0, Math.min(screenHeight - windowHeight, (int) Math.round(mouseY - dragOffsetY)));
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	private static final ApiSection CLIENT_SECTION = new ApiSection(
			"client", "⚠ 客户端 Mod 专用 API", 0xFFFFB85C, "", true, true);
	private static final ApiSection SERVER_SECTION = new ApiSection(
			"server", "◆ 服务端 / 原版 API", 0xFF7CE6A3, "MC", false, true);

	private record ApiSection(String key, String label, int color, String apiRoot,
			boolean clientOnly, boolean grouped) {
		public String label() { return LuaUiText.text(label); }
		boolean contains(LuaApiDocumentation.DocumentEntry entry) {
			if (clientOnly) return entry.documentation().clientRequired();
			return !entry.documentation().clientRequired() && entry.apiRoot().equals(apiRoot);
		}

		boolean contains(LuaApiDocumentation.DocumentPage page) {
			return key.equals("docs:" + page.ownerModId() + ':' + page.categoryId());
		}
	}

	private enum RowType { ROOT, SECTION, GROUP, METHOD, PAGE }

	private record TreeRow(RowType type, int depth, String label, ApiSection section, String group,
			LuaApiDocumentation.DocumentEntry entry, LuaApiDocumentation.DocumentPage page) {
		TreeRow(RowType type, int depth, String label, ApiSection section, String group,
				LuaApiDocumentation.DocumentEntry entry) {
			this(type, depth, label, section, group, entry, null);
		}
	}

	private record SelectableSpan(int x, int y, String text, int start, int end) {
	}

	private record TextRange(int start, int end) {
	}

	private record ButtonBounds(int left, int top, int right, int bottom) {
		boolean contains(double x, double y) {
			return x >= left && x <= right && y >= top && y <= bottom;
		}
	}

	private record PageCodeButton(ButtonBounds insert, ButtonBounds copy, String code) { }
}
