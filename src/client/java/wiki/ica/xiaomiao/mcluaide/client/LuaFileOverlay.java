package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

/** Animated, draggable and resizable Lua script manager used inside the IDE. */
final class LuaFileOverlay {
	void renderImGui() {
		imgui.ImGui.textDisabled(mode==Mode.LUA?"lua/":"datapacks/");
		int start=editingFullPath?0:inputNameStart();String prefix=name.substring(0,Math.min(start,name.length()));
		if(!prefix.isEmpty())imgui.ImGui.textWrapped(prefix);
		String text=NativeUi.input("##name",name.substring(Math.min(start,name.length())));name=prefix+text;cursor=anchor=name.length();
		if(inputAction!=InputAction.NONE){NativeUi.action("确认",this::submitInputAction);imgui.ImGui.sameLine();NativeUi.action("取消",()->cancelInputAction(true));}
		else {if(NativeIcons.button("file","新建文件",false))createScript();NativeUi.next("　");if(NativeIcons.button("folder",mode==Mode.LUA?"新建文件夹":"新建数据包",false)){if(mode==Mode.LUA)createDirectory();else createPackTemplate();}}
		NativeUi.next("　");if(NativeIcons.button("refresh","刷新目录",false))refreshScripts();
		imgui.ImGui.beginChild("files",0,Math.max(80,imgui.ImGui.getContentRegionAvailY()-65),true); nativeChildren("");
		if(imgui.ImGui.beginPopupContextWindow("root",1|imgui.flag.ImGuiPopupFlags.NoOpenOverItems)){contextEntry=new ScriptManager.ScriptEntry("",true);nativeContext();imgui.ImGui.endPopup();}imgui.ImGui.endChild();
		if(paletteOpen)imgui.ImGui.openPopup("categories");
		if(imgui.ImGui.beginPopup("categories")) {
			paletteQuery=NativeUi.input("##query",paletteQuery);imgui.ImGui.beginChild("types",480,300,true);
			for(var option:filteredCategories()){if(imgui.ImGui.selectable(option.label()+" · "+option.path())){createScaffoldDirectory(option);imgui.ImGui.closeCurrentPopup();}NativeUi.hint(option.purpose());}
			imgui.ImGui.endChild();if(imgui.ImGui.button("关闭")){paletteOpen=false;imgui.ImGui.closeCurrentPopup();}imgui.ImGui.endPopup();
		}
		NativeUi.status(status);
	}
	private void nativeChildren(String parent) {
		for(var entry:entries) {
			int slash=entry.path().lastIndexOf('/');if(!parent.equals(slash<0?"":entry.path().substring(0,slash)))continue;
			imgui.ImGui.pushID(entry.path());String label=entry.path().substring(slash+1)+(mode==Mode.LUA&&!entry.directory()?".lua":"");
			if(mode==Mode.DATAPACK&&entry.directory()){var info=DatapackScaffoldCatalog.category(relativeDatapackDirectory(entry.path()));if(info!=null)label+=" - "+info.label();}
			boolean expanded=false;
			if(entry.directory()){imgui.ImGui.setNextItemOpen(!collapsedDirectories.contains(entry.path()));expanded=imgui.ImGui.treeNodeEx(label,imgui.flag.ImGuiTreeNodeFlags.OpenOnArrow);if(expanded)collapsedDirectories.remove(entry.path());else collapsedDirectories.add(entry.path());}
			else if(imgui.ImGui.selectable(label,entry.path().equals(currentScript)))opener.accept(entry.path());
			if(imgui.ImGui.beginPopupContextItem("actions")){contextEntry=entry;rebuildVisible();selectedRow=indexOfVisible(entry.path());nativeContext();imgui.ImGui.endPopup();}
			if(expanded){nativeChildren(entry.path());imgui.ImGui.treePop();}imgui.ImGui.popID();
		}
	}
	private void nativeContext() {
		for(var action:contextActions(contextEntry)) {
			if(action==ContextAction.QUICK_CATEGORY) {
				if(imgui.ImGui.beginMenu("快捷创建")){paletteParent=contextEntry.path();for(var option:filteredCategories()){if(imgui.ImGui.menuItem(option.label()+" · "+leaf(option.path())))createScaffoldDirectory(option);NativeUi.hint(option.purpose());}imgui.ImGui.endMenu();}
			} else if(imgui.ImGui.menuItem(contextLabel(action)))runContextAction(action);
		}
	}
	enum Mode { LUA, DATAPACK }
	record RenameResult(String oldPath, String newPath, boolean directory) { }
	private enum ContextAction { OPEN, NEW_SCRIPT, NEW_LUA_DIRECTORY, NEW_JSON, NEW_NAMESPACE, NEW_DIRECTORY, QUICK_CATEGORY, NEW_TYPED_FILE, RENAME, OPEN_EXPLORER, OPEN_TERMINAL, DELETE }
	private enum InputAction { NONE, NEW_NAMESPACE, NEW_DIRECTORY, NEW_TYPED_FILE, RENAME }
	private static final int TITLE_HEIGHT = 28;
	private static final int ROW_HEIGHT = 24;
	private static final int RESIZE_MARGIN = 6;
	static final int MIN_WIDTH = 160;
	private static final int MIN_HEIGHT = 260;
	private static final int CREATE_SIZE = 22;
	private static final int INPUT_TOP = 49, LIST_TOP = 92;
	private static final int CONTEXT_WIDTH = 140;
	private static final int CONTEXT_ROW_HEIGHT = 22;
	private static final int PALETTE_ROW_HEIGHT = 36;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;

	private final Minecraft minecraft;
	private final Font font;
	private final Mode mode;
	private String currentScript;
	private final Consumer<String> opener;
	private final Consumer<String> externalStatus;
	private final Consumer<RenameResult> renameListener;
	private List<ScriptManager.ScriptEntry> entries = List.of();
	private List<ScriptManager.ScriptEntry> visibleEntries = List.of();
	private final Set<String> collapsedDirectories = new HashSet<>();
	private int refreshRevision;
	private String pendingSelection;
	private String name = "";
	private String status = LuaUiText.text("输入名称或路径新建；用 / 分隔子目录");
	private int cursor;
	private int anchor;
	private int scrollRow;
	private int selectedRow;
	private boolean inputFocused = true;
	private boolean editingFullPath;
	private boolean contextOpen;
	private int contextX;
	private int contextY;
	private ScriptManager.ScriptEntry contextEntry;
	private String pendingDeletePath;
	private InputAction inputAction = InputAction.NONE;
	private String inputSource = "";
	private String inputBeforeAction = "";
	private boolean inputSourceDirectory;
	private boolean inputSubmitting;
	private DatapackScaffoldCatalog.Category inputCategory;
	private boolean paletteOpen;
	private String paletteParent = "";
	private String paletteQuery = "";
	private int paletteScroll;
	private int paletteSelected;
	private boolean open;
	private float progress;
	private int screenWidth;
	private int screenHeight;
	private int windowX = -1;
	private int windowY = 36;
	private int windowWidth = -1;
	private int windowHeight = -1;
	private boolean dragging;
	private int resizeEdges;
	private double dragOffsetX;
	private double dragOffsetY;
	private double resizeStartMouseX;
	private double resizeStartMouseY;
	private int resizeStartX;
	private int resizeStartY;
	private int resizeStartWidth;
	private int resizeStartHeight;
	private boolean keyboardFocused;
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked;
	private int floatingX = -1;
	private int floatingY = 36;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();

	LuaFileOverlay(Minecraft minecraft, Font font, String currentScript,
			Consumer<String> opener, Consumer<String> externalStatus) {
		this(minecraft, font, currentScript, opener, externalStatus, Mode.LUA);
	}

	LuaFileOverlay(Minecraft minecraft, Font font, String currentScript,
			Consumer<String> opener, Consumer<String> externalStatus, Mode mode) {
		this(minecraft, font, currentScript, opener, externalStatus, mode, ignored -> { });
	}

	LuaFileOverlay(Minecraft minecraft, Font font, String currentScript,
			Consumer<String> opener, Consumer<String> externalStatus, Mode mode,
			Consumer<RenameResult> renameListener) {
		this.minecraft = minecraft;
		this.font = font;
		this.mode = mode;
		if (mode == Mode.DATAPACK) this.status = LuaUiText.text("输入数据包名建模板，或输入路径新建 JSON");
		this.currentScript = currentScript;
		this.opener = opener;
		this.externalStatus = externalStatus;
		this.renameListener = renameListener;
	}

	void resize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
		ensureGeometry();
	}

	void open() {
		open = true;
		contextOpen = false;
		paletteOpen = false;
		inputFocused = true;
		keyboardFocused = true;
		refreshScripts();
	}

	void close() {
		editingFullPath = false;
		if (inputAction != InputAction.NONE && !inputSubmitting) cancelInputAction(false);
		open = false;
		contextOpen = false;
		paletteOpen = false;
		keyboardFocused = false;
		dragging = false;
		resizeEdges = 0;
	}

	boolean isOpen() {
		return open;
	}

	boolean isVisible() {
		return progress > 0.01F;
	}

	boolean contains(double mouseX, double mouseY) {
		return isVisible() && (pointInside(mouseX, mouseY) || contextContains(mouseX, mouseY));
	}

	boolean contextContains(double mouseX, double mouseY) {
		return open && contextOpen && contextEntry != null && inside(mouseX, mouseY,
				contextX, contextY, contextX + CONTEXT_WIDTH, contextY + contextMenuHeight());
	}

	void dismissContext() { contextOpen = false; }

	void renderPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (open) renderContextMenu(graphics, mouseX, mouseY);
	}

	boolean isInteracting() {
		return dragging || resizeEdges != 0;
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

	void releaseKeyboardFocus() {
		keyboardFocused = false;
		inputFocused = false;
	}

	void requestKeyboardFocus() { keyboardFocused = true; }

	boolean hasKeyboardFocus() {
		return keyboardFocused;
	}

	void setCurrentScript(String name) {
		currentScript = name == null ? "" : name;
	}


	WindowGeometry captureGeometry() {
		if (!dockSide.docked()) return boundsAnimation.destination(windowX, windowY, windowWidth, windowHeight);
		return new WindowGeometry(dockSide.docked() && floatingWidth > 0 ? floatingX : windowX,
				dockSide.docked() && floatingHeight > 0 ? floatingY : windowY,
				dockSide.docked() && floatingWidth > 0 ? floatingWidth : windowWidth,
				dockSide.docked() && floatingHeight > 0 ? floatingHeight : windowHeight);
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

	void tick() {
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

		graphics.fill(x - 5, windowY + 5, right + 5, bottom + 5, 0x75000000);
		graphics.fill(x, windowY, right, bottom, 0xFE11151B);
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFFE0A45E);
		graphics.fill(x + 1, windowY + 1, right - 1, windowY + TITLE_HEIGHT, 0xFF3B2A1F);
		graphics.text(font, font.plainSubstrByWidth(mode == Mode.LUA ? LuaUiText.text("▣  Lua 目录") : LuaUiText.text("▣  datapack 目录"), windowWidth - 44),
				x + 10, windowY + 9, 0xFFFFC47D);
		long scriptCount = entries.stream().filter(entry -> !entry.directory()).count();
		if (windowWidth >= 290) graphics.text(font, scriptCount + (mode == Mode.LUA ? " 个脚本" : LuaUiText.text(" 个文件")), x + 145, windowY + 9, 0xFFB89C7A);
		boolean closeHovered = inside(mouseX, mouseY, right - 26, windowY + 4, right - 6, windowY + 24);
		graphics.fill(right - 26, windowY + 4, right - 6, windowY + 24,
				closeHovered ? 0xFFB83A46 : 0xFF5A4030);
		graphics.centeredText(font, "×", right - 16, windowY + 10, 0xFFFFFFFF);

		int inputLeft = x + 10;
		String parent = (mode == Mode.LUA ? "lua/" : "datapacks/") + DirectoryPathInput.parent(name);
		graphics.text(font, DirectoryPathInput.shorten(parent, windowWidth - 24, font::width), x + 12, windowY + 34, 0xFFE5C58C);
		int inputTop = windowY + INPUT_TOP;
		int folderLeft = right - 10 - CREATE_SIZE;
		int createLeft = folderLeft - 5 - CREATE_SIZE;
		int inputRight = createLeft - 6;
		graphics.fill(inputLeft, inputTop, inputRight, inputTop + 20, 0xFF090D12);
		graphics.outline(inputLeft, inputTop, inputRight - inputLeft, 20,
				inputFocused ? 0xFFE0A45E : 0xFF54483D);
		renderNameInput(graphics, inputLeft + 7, inputTop + 6, inputRight - 7);
		boolean createHovered = inside(mouseX, mouseY, createLeft, inputTop, createLeft + CREATE_SIZE, inputTop + CREATE_SIZE);
		graphics.fill(createLeft, inputTop, createLeft + CREATE_SIZE, inputTop + CREATE_SIZE,
				createHovered ? 0xFFA8652E : 0xFF704A2C);
		if (inputAction == InputAction.NONE) {
			graphics.outline(createLeft + 5, inputTop + 4, 9, 13, 0xFFFFFFFF);
			graphics.text(font, "+", createLeft + 12, inputTop + 10, 0xFFFFFFFF);
		} else graphics.centeredText(font, "✓", createLeft + CREATE_SIZE / 2, inputTop + 7, 0xFFFFFFFF);
		boolean folderHovered = inside(mouseX, mouseY, folderLeft, inputTop, folderLeft + CREATE_SIZE, inputTop + CREATE_SIZE);
		graphics.fill(folderLeft, inputTop, folderLeft + CREATE_SIZE, inputTop + CREATE_SIZE,
				folderHovered ? 0xFF736642 : 0xFF50482F);
		if (inputAction == InputAction.NONE) {
			graphics.fill(folderLeft + 4, inputTop + 7, folderLeft + 17, inputTop + 16, 0xFFFFE1A9);
			graphics.fill(folderLeft + 4, inputTop + 5, folderLeft + 10, inputTop + 9, 0xFFFFE1A9);
			graphics.text(font, "+", folderLeft + 12, inputTop + 9, 0xFF30261B);
		} else graphics.centeredText(font, "×", folderLeft + CREATE_SIZE / 2, inputTop + 7, 0xFFFFFFFF);

		String statusLine = inputAction != InputAction.NONE
				? createHovered ? LuaUiText.text("确认") : folderHovered ? LuaUiText.text("取消") : status
				: createHovered ? (mode == Mode.LUA ? LuaUiText.text("新建 Lua 脚本") : LuaUiText.text("新建 JSON 文件"))
				: folderHovered ? (mode == Mode.LUA ? LuaUiText.text("新建目录") : LuaUiText.text("新建数据包模板")) : status;
		graphics.text(font, font.plainSubstrByWidth(statusLine, windowWidth - 24), x + 12, windowY + 77,
				statusLine.contains(LuaUiText.text("失败")) || statusLine.contains("不能") ? 0xFFFF7777 : 0xFFAFA59A);
		int listTop = windowY + LIST_TOP;
		int listBottom = bottom - 28;
		graphics.fill(x + 10, listTop, right - 10, listBottom, 0xFF080B0F);
		graphics.outline(x + 10, listTop, windowWidth - 20, listBottom - listTop, 0xFF4E443A);
		if (paletteOpen) renderCategoryPalette(graphics, mouseX, mouseY, x, right, listTop, listBottom);
		else {
			graphics.enableScissor(x + 11, listTop + 1, right - 11, listBottom - 1);
			int visibleRows = visibleRows();
			int hoveredRow = rowAt(mouseX, mouseY);
			for (int visual = 0; visual < visibleRows; visual++) {
				int index = scrollRow + visual;
				if (index >= visibleEntries.size()) break;
				int y = listTop + 2 + visual * ROW_HEIGHT;
				boolean active = index == selectedRow;
				if (active || index == hoveredRow) {
					graphics.fill(x + 12, y - 1, right - 12, y + 21,
							index == hoveredRow ? 0xFF493426 : 0xFF32271F);
				}
				ScriptManager.ScriptEntry entry = visibleEntries.get(index);
				int indent = Math.max(0, Math.min(Math.min(72, depth(entry.path()) * 13), windowWidth - 105));
				int color = entry.path().equals(currentScript) ? 0xFFFFC47D
						: entry.directory() ? 0xFFE5C58C : 0xFFE5E8EC;
				String display = entry.directory()
						? (collapsedDirectories.contains(entry.path()) ? "▸ " : "▾ ") + leaf(entry.path())
							+ (mode == Mode.DATAPACK ? " - " + directoryPurpose(entry.path()) : "")
						: "• " + leaf(entry.path()) + (mode == Mode.LUA ? ".lua" : "");
				graphics.text(font, font.plainSubstrByWidth(display,
						Math.max(0, windowWidth - 46 - indent)), x + 18 + indent, y + 6, color);
			}
			graphics.disableScissor();
			if (entries.isEmpty()) {
				graphics.centeredText(font,
						font.plainSubstrByWidth(mode == Mode.LUA ? LuaUiText.text("暂无 Lua 脚本或目录") : LuaUiText.text("暂无数据包 · 点击文件夹图标创建模板"), windowWidth - 24),
						(x + right) / 2, listTop + 14, 0xFF77818C);
			}
		}
		graphics.text(font, font.plainSubstrByWidth(mode == Mode.LUA
				? LuaUiText.text("左键读取/展开 · 右键更多操作 · 目录需为空才可移除")
				: paletteOpen ? LuaUiText.text("选择类型立即创建 · Esc 返回目录") : LuaUiText.text("右键快捷创建/重命名 · 保存后需重载"), windowWidth - 24),
				x + 12, bottom - 18, 0xFF8C8176);
		renderResizeEdges(graphics, mouseX, mouseY, x, right, bottom);
		if (paletteOpen) {
			int hovered = paletteRowAt(mouseX, mouseY);
			List<DatapackScaffoldCatalog.Category> choices = filteredCategories();
			if (hovered >= 0 && hovered < choices.size())
				renderHintTooltip(graphics, choices.get(hovered).purpose(), mouseX, mouseY);
		} else if (mode == Mode.DATAPACK && !contextOpen) {
			int hovered = rowAt(mouseX, mouseY);
			if (hovered >= 0 && hovered < visibleEntries.size() && visibleEntries.get(hovered).directory()) {
				ScriptManager.ScriptEntry entry = visibleEntries.get(hovered);
				renderHintTooltip(graphics, leaf(entry.path()) + " - " + directoryPurpose(entry.path()),
						mouseX, mouseY);
			}
		}
		if (!contextOpen && inside(mouseX, mouseY, x + 10, windowY + 29, right - 10, windowY + 46))
			renderHintTooltip(graphics, parent + " · 单击修改完整路径；输入框只编辑名称", mouseX, mouseY);
	}

	private void renderNameInput(GuiGraphicsExtractor graphics, int left, int y, int right) {
		if (name.length() == inputNameStart()) {
			graphics.text(font, font.plainSubstrByWidth(mode == Mode.LUA
					? "脚本或目录名称" : LuaUiText.text("文件或目录名称"),
					Math.max(20, right - left)), left, y, 0xFF6F7780);
			return;
		}
		int inputWidth = Math.max(20, right - left);
		int start = visibleNameStart(inputWidth);
		String visible = font.plainSubstrByWidth(name.substring(start), inputWidth);
		int visibleEnd = start + visible.length();
		int selectionStart = Math.max(start, Math.min(cursor, anchor));
		int selectionEnd = Math.min(visibleEnd, Math.max(cursor, anchor));
		if (selectionStart < selectionEnd) {
			int selectionLeft = left + font.width(name.substring(start, selectionStart));
			int selectionRight = left + font.width(name.substring(start, selectionEnd));
			graphics.fill(selectionLeft, y - 3, selectionRight, y + font.lineHeight + 2, 0xB37E5830);
		}
		graphics.text(font, visible, left, y, 0xFFFFFFFF);
		if (inputFocused && cursor >= start) {
			int cursorX = left + font.width(name.substring(start, cursor));
			graphics.fill(cursorX, y - 2, cursorX + 1, y + font.lineHeight + 1, 0xFFFFFFFF);
		}
	}

	private void renderContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!contextOpen || contextEntry == null) return;
		int height = contextMenuHeight();
		graphics.fill(contextX + 3, contextY + 3, contextX + CONTEXT_WIDTH + 3, contextY + height + 3, 0x99000000);
		graphics.fill(contextX, contextY, contextX + CONTEXT_WIDTH, contextY + height, 0xFF1B2028);
		graphics.outline(contextX, contextY, CONTEXT_WIDTH, height, 0xFFE0A45E);
		List<ContextAction> actions = contextActions(contextEntry);
		int count = actions.size();
		for (int index = 0; index < count; index++) {
			ContextAction action = actions.get(index);
			int top = contextY + 3 + index * CONTEXT_ROW_HEIGHT;
			if (inside(mouseX, mouseY, contextX + 2, top, contextX + CONTEXT_WIDTH - 2, top + CONTEXT_ROW_HEIGHT))
				graphics.fill(contextX + 2, top, contextX + CONTEXT_WIDTH - 2, top + CONTEXT_ROW_HEIGHT, 0xFF60432B);
			graphics.text(font, font.plainSubstrByWidth(contextLabel(action), CONTEXT_WIDTH - 18),
					contextX + 10, top + 6,
					action == ContextAction.DELETE ? 0xFFFF8F91 : 0xFFECE7DE);
		}
	}

	private void renderCategoryPalette(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int right, int top, int bottom) {
		graphics.fill(left + 11, top + 1, right - 11, top + 30, 0xFF24303B);
		graphics.text(font, font.plainSubstrByWidth("分类 · " + paletteParent, windowWidth - 40),
				left + 16, top + 5, 0xFFFFC47D);
		graphics.text(font, font.plainSubstrByWidth("搜索: " + paletteQuery + "_", windowWidth - 40),
				left + 16, top + 17, 0xFFBFD5DE);
		List<DatapackScaffoldCatalog.Category> choices = filteredCategories();
		int rows = paletteVisibleRows();
		paletteScroll = Math.max(0, Math.min(paletteScroll, Math.max(0, choices.size() - rows)));
		int hovered = paletteRowAt(mouseX, mouseY);
		graphics.enableScissor(left + 11, top + 31, right - 11, bottom - 1);
		for (int visual = 0; visual < rows; visual++) {
			int index = paletteScroll + visual;
			if (index >= choices.size()) break;
			DatapackScaffoldCatalog.Category category = choices.get(index);
			int y = top + 31 + visual * PALETTE_ROW_HEIGHT;
			if (index == hovered || index == paletteSelected)
				graphics.fill(left + 12, y, right - 12, y + PALETTE_ROW_HEIGHT - 1,
						index == hovered ? 0xFF60432B : 0xFF253743);
			graphics.text(font, font.plainSubstrByWidth(category.label() + "  /" + leaf(category.path()),
					windowWidth - 38), left + 17, y + 4, 0xFFECE7DE);
			graphics.text(font, font.plainSubstrByWidth(category.purpose(), windowWidth - 38),
					left + 17, y + 18, 0xFF93A9B5);
		}
		graphics.disableScissor();
		if (choices.isEmpty()) graphics.text(font, LuaUiText.text("无匹配类型"), left + 17, top + 39, 0xFF93A9B5);
	}

	private void renderHintTooltip(GuiGraphicsExtractor graphics, String description, int mouseX, int mouseY) {
		int tooltipWidth = Math.min(screenWidth - 16, Math.max(150, font.width(description) + 14));
		int tooltipX = Math.max(8, Math.min(screenWidth - tooltipWidth - 8, mouseX + 12));
		int tooltipY = Math.max(8, Math.min(screenHeight - 23, mouseY + 12));
		graphics.fill(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + 20, 0xFF202B35);
		graphics.outline(tooltipX, tooltipY, tooltipWidth, 20, 0xFFE0A45E);
		graphics.text(font, font.plainSubstrByWidth(description, tooltipWidth - 12),
				tooltipX + 6, tooltipY + 6, 0xFFFFFFFF);
	}

	private List<DatapackScaffoldCatalog.Category> filteredCategories() {
		List<DatapackScaffoldCatalog.Category> children = DatapackScaffoldCatalog.children(paletteRelativePath());
		String query = paletteQuery.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) return children;
		return children.stream().filter(category ->
				(category.label() + " " + category.path() + " " + category.purpose())
						.toLowerCase(Locale.ROOT).contains(query)).toList();
	}

	private int paletteVisibleRows() {
		return Math.max(1, (windowHeight - 78 - 28 - 32) / PALETTE_ROW_HEIGHT);
	}

	private int paletteRowAt(double mouseX, double mouseY) {
		int left = panelX();
		int top = windowY + LIST_TOP + 31;
		if (!paletteOpen || mouseX < left + 11 || mouseX >= left + windowWidth - 11
				|| mouseY < top || mouseY >= windowY + windowHeight - 28) return -1;
		int row = paletteScroll + ((int) mouseY - top) / PALETTE_ROW_HEIGHT;
		return row < filteredCategories().size() ? row : -1;
	}

	private String paletteRelativePath() {
		String[] parts = paletteParent.split("/", 4);
		return parts.length >= 4 ? parts[3] : "";
	}

	private int contextMenuHeight() {
		return 6 + (contextEntry == null ? 0 : contextActions(contextEntry).size()) * CONTEXT_ROW_HEIGHT;
	}

	private List<ContextAction> contextActions(ScriptManager.ScriptEntry entry) {
		if (entry.path().isEmpty()) return List.of(ContextAction.OPEN_EXPLORER);
		if (mode == Mode.LUA) return entry.directory()
				? List.of(ContextAction.OPEN, ContextAction.NEW_SCRIPT, ContextAction.NEW_LUA_DIRECTORY,
					ContextAction.OPEN_EXPLORER, ContextAction.DELETE)
				: List.of(ContextAction.OPEN, ContextAction.OPEN_EXPLORER, ContextAction.DELETE);
		if (!entry.directory()) return entry.path().endsWith("/pack.mcmeta")
				? List.of(ContextAction.OPEN, ContextAction.OPEN_EXPLORER, ContextAction.OPEN_TERMINAL)
				: List.of(ContextAction.OPEN, ContextAction.RENAME, ContextAction.OPEN_EXPLORER,
					ContextAction.OPEN_TERMINAL, ContextAction.DELETE);
		if (isDataRoot(entry.path())) return List.of(ContextAction.OPEN, ContextAction.NEW_NAMESPACE,
				ContextAction.NEW_DIRECTORY, ContextAction.OPEN_EXPLORER, ContextAction.OPEN_TERMINAL);
		List<ContextAction> actions = new ArrayList<>();
		actions.add(ContextAction.OPEN);
		String relative = relativeDatapackDirectory(entry.path());
		if (relative != null && !DatapackScaffoldCatalog.children(relative).isEmpty())
			actions.add(ContextAction.QUICK_CATEGORY);
		DatapackScaffoldCatalog.Category category = relative == null ? null
				: DatapackScaffoldCatalog.fileCategory(relative);
		if (category != null && category.textFile()) actions.add(ContextAction.NEW_TYPED_FILE);
		else if (category == null) actions.add(ContextAction.NEW_JSON);
		actions.add(ContextAction.NEW_DIRECTORY);
		actions.add(ContextAction.RENAME);
		actions.add(ContextAction.OPEN_EXPLORER);
		actions.add(ContextAction.OPEN_TERMINAL);
		actions.add(ContextAction.DELETE);
		return actions;
	}

	private String contextLabel(ContextAction action) {
		return switch (action) {
			case OPEN -> contextEntry.directory()
					? collapsedDirectories.contains(contextEntry.path()) ? LuaUiText.text("展开目录") : LuaUiText.text("收起目录")
					: mode == Mode.LUA ? LuaUiText.text("读取脚本")
					: contextEntry.path().endsWith(".png") || contextEntry.path().endsWith(".ogg")
						|| contextEntry.path().endsWith(".nbt") ? LuaUiText.text("只读预览") : LuaUiText.text("打开文件");
			case NEW_SCRIPT -> LuaUiText.text("在此新建脚本");
			case NEW_LUA_DIRECTORY -> LuaUiText.text("在此新建目录");
			case NEW_JSON -> LuaUiText.text("在此新建 JSON");
			case NEW_NAMESPACE -> LuaUiText.text("新建命名空间");
			case NEW_DIRECTORY -> LuaUiText.text("新建文件夹");
			case QUICK_CATEGORY -> LuaUiText.text("快捷创建类型…");
			case NEW_TYPED_FILE -> {
				DatapackScaffoldCatalog.Category category = DatapackScaffoldCatalog.fileCategory(
						relativeDatapackDirectory(contextEntry.path()));
				yield category == null ? LuaUiText.text("新建文件") : LuaUiText.text("新建") + category.label();
			}
			case RENAME -> LuaUiText.text("重命名");
			case OPEN_EXPLORER -> LuaUiText.text("在资源管理器中打开");
			case OPEN_TERMINAL -> LuaUiText.text("在终端中打开");
			case DELETE -> deleteLabel(contextEntry.path(), mode == Mode.LUA
					? contextEntry.directory() ? LuaUiText.text("移除目录") : LuaUiText.text("移除脚本")
					: contextEntry.directory() ? LuaUiText.text("删除目录") : LuaUiText.text("删除文件"));
		};
	}

	private static boolean isDataRoot(String path) {
		return path != null && path.matches("[^/]+/data");
	}

	private static String relativeDatapackDirectory(String path) {
		if (path == null) return null;
		String[] parts = path.split("/", 4);
		if (parts.length < 3 || !parts[1].equals("data")) return null;
		return parts.length == 3 ? "" : parts[3];
	}

	private static String directoryPurpose(String path) {
		if (path == null || !path.contains("/")) return LuaUiText.text("数据包根目录");
		if (isDataRoot(path)) return LuaUiText.text("存放各命名空间的数据目录");
		String relative = relativeDatapackDirectory(path);
		if (relative == null) return LuaUiText.text("自定义目录");
		if (relative.isEmpty()) return LuaUiText.text("命名空间，按类型存放数据文件");
		DatapackScaffoldCatalog.Category category = DatapackScaffoldCatalog.category(relative);
		if (category != null) return category.purpose();
		DatapackScaffoldCatalog.Category parent = DatapackScaffoldCatalog.fileCategory(relative);
		if (parent != null) return parent.label() + LuaUiText.text("子目录，用于组织同类型文件");
		return LuaUiText.text("自定义目录");
	}

	private String deleteLabel(String path, String normal) {
		return path.equals(pendingDeletePath) ? LuaUiText.text("确认删除") : normal;
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (paletteOpen) {
			if (event.key() == InputConstants.KEY_ESCAPE) { paletteOpen = false; return true; }
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (!paletteQuery.isEmpty()) paletteQuery = paletteQuery.substring(0, paletteQuery.length() - 1);
				paletteScroll = paletteSelected = 0;
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
				paletteQuery = "";
				paletteScroll = paletteSelected = 0;
				return true;
			}
			if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
				paletteSelected = Math.max(0, Math.min(Math.max(0, filteredCategories().size() - 1),
						paletteSelected + (event.key() == InputConstants.KEY_UP ? -1 : 1)));
				paletteScroll = Math.max(0, Math.min(paletteScroll, paletteSelected));
				if (paletteSelected >= paletteScroll + paletteVisibleRows())
					paletteScroll = paletteSelected - paletteVisibleRows() + 1;
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				List<DatapackScaffoldCatalog.Category> choices = filteredCategories();
				if (paletteSelected < choices.size()) createScaffoldDirectory(choices.get(paletteSelected));
				return true;
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (contextOpen) contextOpen = false;
			else if (editingFullPath) { editingFullPath = false; cursor = anchor = name.length(); }
			else if (inputAction != InputAction.NONE) cancelInputAction(true);
			else close();
			return true;
		}
		if (contextOpen) contextOpen = false;
		if (inputFocused) {
			cursor = Math.clamp(cursor, inputNameStart(), name.length());
			anchor = Math.clamp(anchor, inputNameStart(), name.length());
			if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
				anchor = inputNameStart();
				cursor = name.length();
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_C) {
				if (hasSelection()) minecraft.keyboardHandler.setClipboard(selectedText());
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_X) {
				if (hasSelection()) {
					minecraft.keyboardHandler.setClipboard(selectedText());
					replaceSelection("");
				}
				return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
				replaceSelection(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", ""));
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (hasSelection()) replaceSelection("");
				else if (cursor > inputNameStart()) {
					anchor = name.offsetByCodePoints(cursor, -1);
					replaceSelection("");
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_DELETE) {
				if (hasSelection()) replaceSelection("");
				else if (cursor < name.length()) {
					anchor = name.offsetByCodePoints(cursor, 1);
					replaceSelection("");
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
				moveCursor(event.key() == InputConstants.KEY_LEFT ? -1 : 1, event.hasShiftDown());
				return true;
			}
			if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
				cursor = event.key() == InputConstants.KEY_HOME ? inputNameStart() : name.length();
				if (!event.hasShiftDown()) anchor = cursor;
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				if (inputAction != InputAction.NONE) submitInputAction();
				else createScript();
				return true;
			}
		}
		if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
			selectedRow = Math.max(0, Math.min(Math.max(0, visibleEntries.size() - 1),
					selectedRow + (event.key() == InputConstants.KEY_UP ? -1 : 1)));
			ensureSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			openSelected();
			return true;
		}
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		int codepoint = event.codepoint();
		if (paletteOpen) {
			if (!Character.isISOControl(codepoint) && paletteQuery.length() < 40) {
				paletteQuery += new String(Character.toChars(codepoint));
				paletteScroll = paletteSelected = 0;
			}
			return true;
		}
		if (inputFocused && !Character.isISOControl(codepoint)
				&& name.codePointCount(0, name.length()) < maxPathLength()) {
			replaceSelection(new String(Character.toChars(codepoint)));
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (contextContains(mouseX, mouseY)) return true;
		if (!open || !pointInside(mouseX, mouseY)) return false;
		contextOpen = false;
		if (paletteOpen) {
			paletteScroll = Math.max(0, Math.min(Math.max(0, filteredCategories().size() - paletteVisibleRows()),
					paletteScroll - (int) Math.round(amount * 3)));
			paletteSelected = Math.max(paletteScroll,
					Math.min(paletteSelected, paletteScroll + paletteVisibleRows() - 1));
			return true;
		}
		scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.round(amount * 3)));
		return true;
	}

	boolean mouseClicked(MouseButtonEvent event) {
		if (!open || progress < 0.5F) return false;
		int x = panelX();
		int right = x + windowWidth;
		if (contextOpen && event.button() == 0) {
			if (inside(event.x(), event.y(), contextX, contextY,
					contextX + CONTEXT_WIDTH, contextY + contextMenuHeight())) {
				int action = ((int) event.y() - contextY - 3) / CONTEXT_ROW_HEIGHT;
				List<ContextAction> actions = contextActions(contextEntry);
				if (event.y() >= contextY + 3 && action >= 0 && action < actions.size())
					runContextAction(actions.get(action));
				contextOpen = false;
				return true;
			}
			contextOpen = false;
		}
		if (event.button() == 0) {
			int edges = resizeEdgesAt(event.x(), event.y());
			if (edges != 0) {
				resizeEdges = edges;
				resizeStartMouseX = event.x();
				resizeStartMouseY = event.y();
				resizeStartX = x;
				resizeStartY = windowY;
				resizeStartWidth = windowWidth;
				resizeStartHeight = windowHeight;
				return true;
			}
		}
		if (!pointInside(event.x(), event.y())) return false;
		if (event.button() != 0 && event.button() != 1) return true;
		if (paletteOpen) {
			keyboardFocused = true;
			if (event.button() == 1) { paletteOpen = false; return true; }
			if (inside(event.x(), event.y(), right - 26, windowY + 4, right - 6, windowY + 24)) {
				close();
				return true;
			}
			int row = paletteRowAt(event.x(), event.y());
			List<DatapackScaffoldCatalog.Category> choices = filteredCategories();
			if (row >= 0 && row < choices.size()) createScaffoldDirectory(choices.get(row));
			return true;
		}
		if (event.button() == 1) {
			int row = rowAt(event.x(), event.y());
			if (row >= 0 && row < visibleEntries.size()) {
				if (inputAction != InputAction.NONE) cancelInputAction(false);
				selectedRow = row;
				contextEntry = visibleEntries.get(row);
				if (pendingDeletePath != null && !pendingDeletePath.equals(contextEntry.path())) pendingDeletePath = null;
				int height = contextMenuHeight();
				placeContextMenu(event.x(), event.y(), height);
				contextOpen = true;
				keyboardFocused = true;
				inputFocused = false;
			} else if (inside(event.x(), event.y(), x + 10, windowY + LIST_TOP, right - 10, windowY + windowHeight - 28)) {
				contextEntry = new ScriptManager.ScriptEntry("", true);
				placeContextMenu(event.x(), event.y(), contextMenuHeight());
				contextOpen = true; keyboardFocused = true; inputFocused = false;
			} else contextOpen = false;
			return true;
		}
		if (inside(event.x(), event.y(), right - 26, windowY + 4, right - 6, windowY + 24)) {
			close();
			return true;
		}
		keyboardFocused = true;
		if (inside(event.x(), event.y(), x, windowY, right, windowY + TITLE_HEIGHT)) {
			if (dockSide.docked() && layoutLocked) return true;
			inputFocused = false;
			dragging = true;
			dragOffsetX = event.x() - x;
			dragOffsetY = event.y() - windowY;
			return true;
		}
		int inputLeft = x + 10;
		if (inside(event.x(), event.y(), x + 10, windowY + 29, right - 10, windowY + 46)) {
			editingFullPath = true; inputFocused = true;
			anchor = 0; cursor = name.length();
			setStatus("正在编辑完整路径 · Esc 返回名称编辑"); return true;
		}
		int inputTop = windowY + INPUT_TOP;
		int folderLeft = right - 10 - CREATE_SIZE;
		int createLeft = folderLeft - 5 - CREATE_SIZE;
		int inputRight = createLeft - 6;
		if (inside(event.x(), event.y(), inputLeft, inputTop, inputRight, inputTop + 20)) {
			editingFullPath = false;
			cursor = Math.clamp(cursor, inputNameStart(), name.length());
			inputFocused = true;
			cursor = nameOffsetAt(event.x(), inputLeft + 7, inputRight - 7);
			anchor = cursor;
			return true;
		}
		inputFocused = false;
		if (inside(event.x(), event.y(), createLeft, inputTop, createLeft + CREATE_SIZE, inputTop + CREATE_SIZE)) {
			if (inputAction != InputAction.NONE) submitInputAction();
			else createScript();
			return true;
		}
		if (inside(event.x(), event.y(), folderLeft, inputTop, folderLeft + CREATE_SIZE, inputTop + CREATE_SIZE)) {
			if (inputAction != InputAction.NONE) cancelInputAction(true);
			else createDirectory();
			return true;
		}
		int row = rowAt(event.x(), event.y());
		if (row >= 0 && row < visibleEntries.size()) {
			selectedRow = row;
			openSelected();
			return true;
		}
		return true;
	}

	private void runContextAction(ContextAction action) {
		if (contextEntry == null) return;
		selectedRow = indexOfVisible(contextEntry.path());
		switch (action) {
			case OPEN -> openSelected();
			case NEW_SCRIPT, NEW_LUA_DIRECTORY, NEW_JSON -> {
				editingFullPath = false;
				name = contextEntry.path() + "/";
				cursor = anchor = name.length();
				inputFocused = true;
				setStatus(action == ContextAction.NEW_LUA_DIRECTORY ? "输入目录名后点击文件夹图标"
						: action == ContextAction.NEW_SCRIPT ? "输入脚本名后点击文件图标"
						: "输入 JSON 名后点击文件图标");
			}
			case NEW_NAMESPACE -> beginInputAction(InputAction.NEW_NAMESPACE, contextEntry.path());
			case NEW_DIRECTORY -> beginInputAction(InputAction.NEW_DIRECTORY, contextEntry.path());
			case QUICK_CATEGORY -> {
				paletteParent = contextEntry.path();
				paletteQuery = "";
				paletteScroll = 0;
				paletteSelected = 0;
				paletteOpen = true;
				inputFocused = false;
				setStatus("选择类型创建目录；悬浮可查看作用，输入文字筛选");
			}
			case NEW_TYPED_FILE -> {
				inputCategory = DatapackScaffoldCatalog.fileCategory(
						relativeDatapackDirectory(contextEntry.path()));
				beginInputAction(InputAction.NEW_TYPED_FILE, contextEntry.path());
			}
			case RENAME -> beginInputAction(InputAction.RENAME, contextEntry.path());
			case OPEN_EXPLORER -> openLocalEntry(false);
			case OPEN_TERMINAL -> openLocalEntry(true);
			case DELETE -> deleteSelected();
		}
	}

	private void placeContextMenu(double mouseX, double mouseY, int height) {
		contextX = Math.clamp((int) mouseX, 4, Math.max(4, screenWidth - CONTEXT_WIDTH - 4));
		contextY = Math.clamp((int) mouseY, 4, Math.max(4, screenHeight - height - 4));
	}

	private void openLocalEntry(boolean terminal) {
		if (contextEntry == null) return;
		try {
			if (mode == Mode.DATAPACK) LocalDatapackLauncher.open(minecraft, contextEntry.path(), contextEntry.directory(), terminal);
			else LocalWorkspaceLauncher.openLua(minecraft, contextEntry.path(), contextEntry.directory());
			setStatus(terminal ? "已在终端打开对应目录" : "已在资源管理器打开对应位置");
		} catch (Exception error) {
			setStatus((terminal ? "打开终端失败：" : "打开资源管理器失败：") + error.getMessage());
		}
	}

	private void createScaffoldDirectory(DatapackScaffoldCatalog.Category category) {
		String path = paletteParent + "/" + leaf(category.path());
		paletteOpen = false;
		if (entries.stream().anyMatch(entry -> entry.path().equals(path))) {
			setStatus("目录已存在：" + path + " · " + category.purpose());
			return;
		}
		setStatus("正在创建 " + category.label() + " 目录…");
		ScriptManager.createDatapackDirectory(path).whenComplete((ignored, failure) -> minecraft.execute(() -> {
			if (failure != null) { setStatus(LuaUiText.text("创建失败：") + ScriptManager.message(failure)); return; }
			collapsedDirectories.remove(paletteParent);
			pendingSelection = path;
			name = path + "/";
			cursor = anchor = name.length();
			setStatus(LuaUiText.text("已创建 ") + path + " · " + category.purpose());
			refreshScripts();
		}));
	}

	private void beginInputAction(InputAction action, String source) {
		editingFullPath = false;
		inputBeforeAction = name;
		inputAction = action;
		inputSource = source;
		inputSourceDirectory = contextEntry != null && contextEntry.directory();
		inputSubmitting = false;
		name = action == InputAction.RENAME ? source
				: action == InputAction.NEW_TYPED_FILE ? source + "/example" : source + "/";
		cursor = name.length();
		anchor = action == InputAction.RENAME ? source.lastIndexOf('/') + 1
				: action == InputAction.NEW_TYPED_FILE ? source.length() + 1 : cursor;
		inputFocused = true;
		keyboardFocused = true;
		setStatus(switch (action) {
			case NEW_NAMESPACE -> "输入命名空间名称，按 Enter 或 ✓ 创建；Esc 取消";
			case NEW_DIRECTORY -> "输入文件夹名称，按 Enter 或 ✓ 创建；Esc 取消";
			case NEW_TYPED_FILE -> LuaUiText.text("新建") + inputCategory.label() + "：" + inputCategory.purpose();
			case RENAME -> "修改选中的名称，按 Enter 或 ✓ 重命名；Esc 取消";
			case NONE -> "";
		});
	}

	private void cancelInputAction(boolean showStatus) {
		if (inputSubmitting) return;
		editingFullPath = false;
		name = inputBeforeAction;
		cursor = anchor = name.length();
		inputAction = InputAction.NONE;
		inputCategory = null;
		inputSource = "";
		inputBeforeAction = "";
		if (showStatus) setStatus(LuaUiText.text("已取消操作"));
	}

	private void submitInputAction() {
		if (inputAction == InputAction.NONE || inputSubmitting) return;
		String target = name.trim();
		String source = inputSource;
		String extension = inputAction == InputAction.NEW_TYPED_FILE ? inputCategory.extension()
				: inputAction == InputAction.RENAME && !inputSourceDirectory
					? source.substring(source.lastIndexOf('.'))
					: source.endsWith(".mcfunction") ? ".mcfunction" : ".json";
		if ((inputAction == InputAction.NEW_TYPED_FILE || inputAction == InputAction.RENAME && !inputSourceDirectory)
				&& !target.endsWith(extension)) target += extension;
		if (!validDatapackPath(target) || !parentOf(target).equals(
				inputAction == InputAction.RENAME ? parentOf(source) : source)) {
			setStatus("只能在当前文件夹中填写一个合法的新名称，不能移动路径");
			return;
		}
		if (inputAction == InputAction.NEW_NAMESPACE && !isDataRoot(source)) {
			setStatus("命名空间只能创建在 data 根目录下");
			return;
		}
		if (inputAction == InputAction.RENAME && target.equals(source)) {
			setStatus(LuaUiText.text("请输入不同的新名称"));
			return;
		}
		if (inputAction == InputAction.RENAME && !inputSourceDirectory && !target.endsWith(extension)) {
			setStatus("文件名必须以 " + extension + " 结尾");
			return;
		}
		InputAction action = inputAction;
		String newPath = target;
		boolean directory = inputSourceDirectory;
		inputSubmitting = true;
		setStatus(action == InputAction.RENAME ? "正在重命名 " + source + "…"
				: action == InputAction.NEW_TYPED_FILE ? "正在创建文件 " + newPath + "…"
				: "正在创建目录 " + newPath + "…");
		var request = action == InputAction.RENAME
				? ScriptManager.renameDatapackPath(source, newPath)
				: action == InputAction.NEW_TYPED_FILE ? ScriptManager.createDatapackExample(newPath)
				: ScriptManager.createDatapackDirectory(newPath);
		request.whenComplete((ignored, failure) -> minecraft.execute(() -> {
			inputSubmitting = false;
			if (failure != null) {
				setStatus((action == InputAction.RENAME ? LuaUiText.text("重命名")
						: action == InputAction.NEW_TYPED_FILE ? "创建文件" : "创建目录") + "失败："
						+ ScriptManager.message(failure));
				return;
			}
			if (action == InputAction.RENAME) {
				remapCollapsedPaths(source, newPath);
				if (currentScript.equals(source) || directory && currentScript.startsWith(source + "/"))
					currentScript = newPath + currentScript.substring(source.length());
				renameListener.accept(new RenameResult(source, newPath, directory));
			}
			inputAction = InputAction.NONE;
			inputCategory = null;
			inputSource = "";
			inputBeforeAction = "";
			name = action == InputAction.NEW_TYPED_FILE ? newPath
					: directory || action != InputAction.RENAME ? newPath + "/" : newPath;
			cursor = anchor = name.length();
			pendingSelection = newPath;
			setStatus(action == InputAction.RENAME ? LuaUiText.text("已重命名为 ") + newPath
					: action == InputAction.NEW_TYPED_FILE ? LuaUiText.text("已创建 ") + newPath + "；已填入可修改的示例代码"
					: "已创建目录 " + newPath);
			refreshScripts();
			if (action == InputAction.NEW_TYPED_FILE) opener.accept(newPath);
		}));
	}

	private void remapCollapsedPaths(String source, String target) {
		Set<String> replacement = new HashSet<>();
		for (String path : collapsedDirectories) {
			if (path.equals(source) || path.startsWith(source + "/"))
				replacement.add(target + path.substring(source.length()));
			else replacement.add(path);
		}
		collapsedDirectories.clear();
		collapsedDirectories.addAll(replacement);
	}

	private static String parentOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	private static boolean validDatapackPath(String path) {
		if (path == null || path.isEmpty() || path.length() > 240 || path.contains("\\")) return false;
		for (String part : path.split("/", -1))
			if (!part.matches("[a-z0-9_.-]{1,64}") || part.equals(".") || part.equals("..")) return false;
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
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
		return false;
	}

	boolean mouseReleased() {
		if (!isInteracting()) return false;
		dragging = false;
		resizeEdges = 0;
		return true;
	}

	private void createScript() {
		if (mode == Mode.DATAPACK) {
			createJsonFile();
			return;
		}
		String requested = name.trim();
		if (!ScriptManager.isValidName(requested)) {
			setStatus("不能新建：用 / 分隔目录；每层仅可用字母、数字、下划线和连字符");
			return;
		}
		if (entries.stream().anyMatch(entry -> !entry.directory() && entry.path().equals(requested))) {
			setStatus("不能新建：" + requested + ".lua 已存在");
			return;
		}
		setStatus("正在服务器新建 " + requested + ".lua…");
		ScriptManager.save(requested, "-- " + requested + ".lua\nprint(\"Hello from " + requested + "!\")\n")
				.whenComplete((ignored, failure) -> minecraft.execute(() -> {
					if (failure != null) {
						setStatus(LuaUiText.text("新建失败：") + ScriptManager.message(failure));
						return;
					}
					name = "";
					cursor = 0;
					anchor = 0;
					pendingSelection = requested;
					setStatus(LuaUiText.text("已新建 ") + requested + ".lua；正在打开标签");
					refreshScripts();
					opener.accept(requested);
				}));
	}

	private void createJsonFile() {
		String requested = name.trim();
		DatapackScaffoldCatalog.Category category = fileCategoryForPath(requested);
		if (category != null && !category.textFile()) {
			setStatus(category.label() + "使用二进制 " + category.extension() + " 文件，当前文本编辑器不能生成");
			return;
		}
		if (!requested.endsWith(".json") && !requested.endsWith(".mcfunction")
				&& !requested.endsWith("/pack.mcmeta")) {
			requested += category == null ? ".json" : category.extension();
		}
		if (!validDatapackFilePath(requested)) {
			setStatus("路径格式：数据包名/data/命名空间/类别/文件.json 或 function/文件.mcfunction");
			return;
		}
		String path = requested;
		if (entries.stream().anyMatch(entry -> !entry.directory() && entry.path().equals(path))) {
			setStatus("文件已存在：" + path);
			return;
		}
		setStatus("正在新建 " + path + "…");
		boolean hasExample = fileCategoryForPath(path) != null;
		var request = hasExample
				? ScriptManager.createDatapackExample(path)
				: ScriptManager.createDatapackFile(path, "{}\n");
		request.whenComplete((ignored, failure) -> minecraft.execute(() -> {
			if (failure != null) { setStatus(LuaUiText.text("新建失败：") + ScriptManager.message(failure)); return; }
			name = "";
			cursor = anchor = 0;
			pendingSelection = path;
			setStatus(LuaUiText.text("已新建 ") + path + (hasExample ? " 并填入示例代码" : "") + "；正在打开标签");
			refreshScripts();
			opener.accept(path);
		}));
	}

	private static boolean validDatapackFilePath(String path) {
		if (path == null || path.length() > 240 || path.contains("\\")) return false;
		String[] parts = path.split("/", -1);
		if (parts.length < 2) return false;
		for (String part : parts) if (!part.matches("[a-z0-9_.-]{1,64}") || part.equals(".") || part.equals("..")) return false;
		if (path.endsWith(".mcfunction")) {
			DatapackScaffoldCatalog.Category category = fileCategoryForPath(path);
			return category != null && category.extension().equals(".mcfunction");
		}
		return path.endsWith(".json") || path.endsWith("/pack.mcmeta");
	}

	private static DatapackScaffoldCatalog.Category fileCategoryForPath(String path) {
		int slash = path == null ? -1 : path.lastIndexOf('/');
		if (slash < 0) return null;
		String relative = relativeDatapackDirectory(path.substring(0, slash));
		DatapackScaffoldCatalog.Category category = relative == null ? null
				: DatapackScaffoldCatalog.fileCategory(relative);
		String fileName = path.substring(slash + 1);
		if (category == null) return null;
		if (fileName.endsWith(".json") || fileName.endsWith(".mcfunction"))
			return fileName.endsWith(category.extension()) ? category : null;
		return category;
	}

	private void createDirectory() {
		if (mode == Mode.DATAPACK) {
			createPackTemplate();
			return;
		}
		String requested = name.trim();
		if (!ScriptManager.isValidName(requested)) {
			setStatus("不能新建目录：用 / 分隔路径；每层仅可用字母、数字、下划线和连字符");
			return;
		}
		if (entries.stream().anyMatch(entry -> entry.directory() && entry.path().equals(requested))) {
			setStatus("目录已存在：" + requested);
			return;
		}
		setStatus("正在服务器新建目录 " + requested + "…");
		ScriptManager.createDirectory(requested).whenComplete((ignored, failure) -> minecraft.execute(() -> {
			if (failure != null) { setStatus("新建目录失败：" + ScriptManager.message(failure)); return; }
			name = requested + "/";
			cursor = name.length();
			anchor = cursor;
			pendingSelection = requested;
			setStatus("已新建目录 " + requested + "；可输入 " + requested + "/文件名 来创建脚本");
			refreshScripts();
		}));
	}

	private void createPackTemplate() {
		String requested = name.trim();
		if (!requested.matches("[a-z0-9_.-]{1,64}") || requested.equals(".") || requested.equals("..")) {
			setStatus("数据包名称只能使用小写字母、数字、_、- 和 .，不能包含路径");
			return;
		}
		setStatus("正在创建数据包模板 " + requested + "…");
		ScriptManager.createDatapack(requested).whenComplete((ignored, failure) -> minecraft.execute(() -> {
			if (failure != null) { setStatus(LuaUiText.text("创建失败：") + ScriptManager.message(failure)); return; }
			name = requested + "/data/" + requested + "/recipe/";
			cursor = anchor = name.length();
			pendingSelection = requested;
			setStatus("已创建模板 " + requested + "；可输入 JSON 路径，新内容需重载后生效");
			refreshScripts();
		}));
	}

	private void openSelected() {
		editingFullPath = false;
		if (selectedRow < 0 || selectedRow >= visibleEntries.size()) return;
		ScriptManager.ScriptEntry entry = visibleEntries.get(selectedRow);
		if (entry.directory()) {
			String path = entry.path();
			if (!collapsedDirectories.add(path)) collapsedDirectories.remove(path);
			name = path + "/";
			cursor = name.length();
			anchor = cursor;
			rebuildVisible();
			selectedRow = indexOfVisible(path);
			ensureSelectedVisible();
			if (mode == Mode.DATAPACK) setStatus(leaf(path) + "：" + directoryPurpose(path));
			return;
		}
		String selected = entry.path();
		setStatus(LuaUiText.text("正在读取 ") + selected + (mode == Mode.LUA ? ".lua" : ""));
		opener.accept(selected);
	}

	private void deleteSelected() {
		if (selectedRow < 0 || selectedRow >= visibleEntries.size()) return;
		ScriptManager.ScriptEntry entry = visibleEntries.get(selectedRow);
		String selected = entry.path();
		if (mode == Mode.DATAPACK) {
			if (!selected.equals(pendingDeletePath)) {
				pendingDeletePath = selected;
				setStatus("再次右键选择“确认删除”：" + selected + (entry.directory() ? "（含子目录）" : ""));
				return;
			}
			pendingDeletePath = null;
			setStatus("正在删除 " + selected + "…");
			ScriptManager.deleteDatapackPath(selected).whenComplete((deleted, failure) -> minecraft.execute(() -> {
				if (failure != null) { setStatus("删除失败：" + ScriptManager.message(failure)); return; }
				setStatus(deleted ? LuaUiText.text("已删除 ") + selected : "路径不存在：" + selected);
				refreshScripts();
			}));
			return;
		}
		setStatus("正在从服务器移除 " + selected + (entry.directory() ? "/" : ".lua") + "…");
		var request = entry.directory() ? ScriptManager.deleteDirectory(selected) : ScriptManager.delete(selected);
		request.whenComplete((deleted, failure) -> minecraft.execute(() -> {
			if (failure != null) {
				setStatus("移除失败：" + ScriptManager.message(failure));
				return;
			}
			setStatus(deleted ? "已移除 " + selected + (entry.directory() ? "/" : ".lua")
					+ (selected.equals(currentScript) ? "；当前编辑器再次保存会重新创建它" : "")
					: "路径不存在：" + selected);
			refreshScripts();
		}));
	}

	void refreshScripts() {
		contextOpen = false;
		int revision = ++refreshRevision;
		(mode == Mode.LUA ? ScriptManager.listScriptTree() : ScriptManager.listDatapackTree())
				.whenComplete((result, failure) -> minecraft.execute(() -> {
			if (revision != refreshRevision) return;
			if (failure != null) {
				entries = List.of();
				visibleEntries = List.of();
				setStatus(LuaUiText.text("读取目录失败：") + ScriptManager.message(failure));
				return;
			}
			entries = result;
			if (mode == Mode.DATAPACK) ScriptManager.invalidateAllRegistryCatalogs();
			if (pendingSelection != null) {
				expandAncestors(pendingSelection);
				rebuildVisible();
				selectedRow = indexOfVisible(pendingSelection);
				pendingSelection = null;
			} else {
				rebuildVisible();
				selectedRow = Math.max(0, Math.min(selectedRow, visibleEntries.size() - 1));
			}
			scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
			ensureSelectedVisible();
		}));
	}

	private void rebuildVisible() {
		Map<String, List<ScriptManager.ScriptEntry>> children = new HashMap<>();
		for (ScriptManager.ScriptEntry entry : entries) {
			String path = entry.path();
			int slash = path.lastIndexOf('/');
			String parent = slash < 0 ? "" : path.substring(0, slash);
			children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(entry);
		}
		Comparator<ScriptManager.ScriptEntry> order = Comparator
				.comparing(ScriptManager.ScriptEntry::directory).reversed()
				.thenComparing(entry -> leaf(entry.path()), String.CASE_INSENSITIVE_ORDER);
		children.values().forEach(list -> list.sort(order));
		List<ScriptManager.ScriptEntry> result = new ArrayList<>();
		appendVisible("", children, result);
		visibleEntries = List.copyOf(result);
	}

	private void appendVisible(String parent,
			Map<String, List<ScriptManager.ScriptEntry>> children,
			List<ScriptManager.ScriptEntry> result) {
		for (ScriptManager.ScriptEntry entry : children.getOrDefault(parent, List.of())) {
			result.add(entry);
			if (entry.directory() && !collapsedDirectories.contains(entry.path()))
				appendVisible(entry.path(), children, result);
		}
	}

	private void expandAncestors(String path) {
		int slash = path.indexOf('/');
		while (slash >= 0) {
			collapsedDirectories.remove(path.substring(0, slash));
			slash = path.indexOf('/', slash + 1);
		}
	}

	private int indexOfVisible(String path) {
		for (int index = 0; index < visibleEntries.size(); index++)
			if (visibleEntries.get(index).path().equals(path)) return index;
		return 0;
	}

	private static int depth(String path) {
		int count = 0;
		for (int index = 0; index < path.length(); index++) if (path.charAt(index) == '/') count++;
		return count;
	}

	private static String leaf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	private void setStatus(String value) {
		status = value;
		externalStatus.accept(value);
	}

	private int rowAt(double mouseX, double mouseY) {
		int x = panelX();
		int listTop = windowY + LIST_TOP;
		int listBottom = windowY + windowHeight - 28;
		if (!inside(mouseX, mouseY, x + 10, listTop, x + windowWidth - 10, listBottom)) return -1;
		return scrollRow + Math.max(0, (int) (mouseY - listTop - 2) / ROW_HEIGHT);
	}

	private int visibleRows() {
		return Math.max(1, (windowHeight - LIST_TOP - 30) / ROW_HEIGHT);
	}

	private int maxScroll() {
		return Math.max(0, visibleEntries.size() - visibleRows());
	}

	private void ensureSelectedVisible() {
		if (selectedRow < scrollRow) scrollRow = selectedRow;
		if (selectedRow >= scrollRow + visibleRows()) scrollRow = selectedRow - visibleRows() + 1;
	}

	private boolean hasSelection() {
		return cursor != anchor;
	}

	private String selectedText() {
		return name.substring(Math.min(cursor, anchor), Math.max(cursor, anchor));
	}

	private void replaceSelection(String replacement) {
		int start = Math.clamp(Math.min(cursor, anchor), inputNameStart(), name.length());
		int end = Math.clamp(Math.max(cursor, anchor), start, name.length());
		String clean = replacement == null ? "" : replacement;
		String next = name.substring(0, start) + clean + name.substring(end);
		if (next.codePointCount(0, next.length()) > maxPathLength()) {
			int allowed = Math.max(0, maxPathLength()
					- name.substring(0, start).codePointCount(0, start)
					- name.substring(end).codePointCount(0, name.length() - end));
			if (clean.codePointCount(0, clean.length()) > allowed) {
				clean = clean.substring(0, clean.offsetByCodePoints(0, allowed));
			}
			next = name.substring(0, start) + clean + name.substring(end);
		}
		name = next;
		cursor = start + clean.length();
		anchor = cursor;
	}

	private int maxPathLength() {
		return mode == Mode.DATAPACK ? 240 : LuaScriptNames.MAX_PATH_LENGTH;
	}

	private void moveCursor(int direction, boolean selecting) {
		if (!selecting && hasSelection()) {
			cursor = direction < 0 ? Math.min(cursor, anchor) : Math.max(cursor, anchor);
			anchor = cursor;
			return;
		}
		if (direction < 0 && cursor > inputNameStart()) cursor = name.offsetByCodePoints(cursor, -1);
		if (direction > 0 && cursor < name.length()) cursor = name.offsetByCodePoints(cursor, 1);
		if (!selecting) anchor = cursor;
	}

	private int visibleNameStart(int maxWidth) {
		return DirectoryPathInput.view(name, cursor, inputNameStart(), maxWidth, font::width).start();
	}
	private int inputNameStart() { return editingFullPath ? 0 : DirectoryPathInput.leafStart(name); }

	private int nameOffsetAt(double mouseX, int textLeft, int textRight) {
		int maxWidth = Math.max(20, textRight - textLeft);
		int start = visibleNameStart(maxWidth);
		String visible = font.plainSubstrByWidth(name.substring(start), maxWidth);
		int end = start + visible.length();
		if (mouseX <= textLeft) return start;
		int previousWidth = 0;
		for (int index = start; index < end;) {
			int next = name.offsetByCodePoints(index, 1);
			int nextWidth = font.width(name.substring(start, next));
			if (mouseX < textLeft + (previousWidth + nextWidth) / 2.0) return index;
			previousWidth = nextWidth;
			index = next;
		}
		return end;
	}

	private void renderResizeEdges(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int right, int bottom) {
		int edges = resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY);
		int color = 0xFFFFB968;
		if ((edges & EDGE_LEFT) != 0) graphics.fill(left - 1, windowY, left + 2, bottom, color);
		if ((edges & EDGE_RIGHT) != 0) graphics.fill(right - 2, windowY, right + 1, bottom, color);
		if ((edges & EDGE_TOP) != 0) graphics.fill(left, windowY - 1, right, windowY + 2, color);
		if ((edges & EDGE_BOTTOM) != 0) graphics.fill(left, bottom - 2, right, bottom + 1, color);
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
		scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0) return;
		if (dockSide.docked()) return;
		int minimumWidth = Math.min(MIN_WIDTH, screenWidth);
		int minimumHeight = Math.min(MIN_HEIGHT, screenHeight);
		if (windowWidth < 0) windowWidth = Math.min(screenWidth - 18,
				Math.max(minimumWidth, (int) (screenWidth * 0.65F)));
		if (windowHeight < 0) windowHeight = Math.min(screenHeight - 18,
				Math.max(minimumHeight, (int) (screenHeight * 0.72F)));
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
}
