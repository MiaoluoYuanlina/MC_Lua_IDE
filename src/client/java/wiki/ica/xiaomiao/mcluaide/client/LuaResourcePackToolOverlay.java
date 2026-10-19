package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** Three independent dockable tool windows for client resource-pack editing. */
final class LuaResourcePackToolOverlay {
	enum Kind { DIRECTORY, JSON, PNG }
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	private static final int TITLE = 26, ROW = 18, JSON_ROW = 21;
	private static final int DIRECTORY_ROWS_OFFSET = 124;
	private static final int PATH_TOP = 22, ACTIONS_TOP = 50;
	private static final int CONTEXT_WIDTH = 185, CONTEXT_ROW = 22;
	private enum ContextAction { OPEN, NEW_PACK, NEW_NAMESPACE, QUICK_CATEGORY, QUICK_ADD, NEW_DIRECTORY, NEW_JSON, NEW_PNG, RENAME, OPEN_EXPLORER, DELETE }
	private enum PendingCreate { NONE, PACK, NAMESPACE, DIRECTORY, JSON, PNG, SOUND, ITEM_TEXTURE, RENAME }
	private final Minecraft minecraft;
	private final Font font;
	private final Kind kind;
	private final Consumer<String> opener;
	private final Consumer<String> jsonChange;
	private final Supplier<LuaResourcePngCanvas> canvas;
	private final Consumer<String> message;
	private final Predicate<String> canChangePath;
	private final Supplier<String> currentJsonPath;
	private final Set<String> collapsed = new HashSet<>();
	private List<LuaResourcePackWorkspace.Entry> entries = List.of(), visible = List.of();
	private JsonTreeEditor tree;
	private JsonElement value = JsonParser.parseString("{}");
	private String path = "", selected = "", pendingDelete = "", status = "";
	private int pathCursor, pathAnchor;
	private boolean editingFullPath;
	private String pathBeforeAction = "";
	private boolean contextOpen, categoryPickerOpen;
	// Shared native-file import lifecycle for OGG and PNG (only one import at a time).
	private boolean quickAddOpen, choosingOgg, importingSound;
	private int soundRequest;
	private Path pendingOgg;
	private String pendingSoundNamespace = "";
	private int contextX, contextY, contextCategoryScroll;
	private LuaResourcePackWorkspace.Entry contextEntry;
	private String categoryParent = "";
	private PendingCreate pendingCreate = PendingCreate.NONE;
	private String selectedPngPath = "";
	private String jsonQuery = "", selectedExample = "";
	private boolean jsonShowExamples, jsonSearchFocused;
	private int jsonListScroll;
	private String colorInput = "#EECC55", widthInput = "16", heightInput = "16";
	private int activeInput;
	private String category = "items";
	private List<String> examples = List.of();
	private int listScroll, pngScroll;
	private int color = 0xFFEECC55;
	private float hue = 0.12F;
	private boolean inputFocus, open, keyboardFocus, dragging, layoutLocked;
	private int resizeEdges, x = -1, y = 42, width = 340, height = 420;
	private int screenWidth, screenHeight, floatingX = -1, floatingY = 42, floatingWidth = 340, floatingHeight = 420;
	private double dragOffsetX, dragOffsetY, resizeMouseX, resizeMouseY;
	private int resizeX, resizeY, resizeWidth, resizeHeight;
	private float progress;
	private int observedCatalogRevision = -1;
	private DockSide dock = DockSide.FLOATING;
	private final WindowBoundsAnimation animation = new WindowBoundsAnimation();

	LuaResourcePackToolOverlay(Minecraft minecraft, Font font, Kind kind, Consumer<String> opener,
			Consumer<String> jsonChange, Supplier<LuaResourcePngCanvas> canvas, Consumer<String> message,
			Predicate<String> canChangePath) {
		this(minecraft, font, kind, opener, jsonChange, canvas, message, canChangePath, () -> "");
	}

	LuaResourcePackToolOverlay(Minecraft minecraft, Font font, Kind kind, Consumer<String> opener,
			Consumer<String> jsonChange, Supplier<LuaResourcePngCanvas> canvas, Consumer<String> message,
			Predicate<String> canChangePath, Supplier<String> currentJsonPath) {
		this.minecraft = minecraft; this.font = font; this.kind = kind;
		this.opener = opener; this.jsonChange = jsonChange; this.canvas = canvas; this.message = message;
		this.canChangePath = canChangePath;
		this.currentJsonPath = currentJsonPath;
		if (kind == Kind.JSON) {
			tree = new JsonTreeEditor(minecraft, font, updated -> {
				value = updated;
				selectedExample = "";
				jsonChange.accept(PRETTY.toJson(value) + "\n");
			}, this::setStatus);
			tree.setTranslation(new ResourceJsonTranslation());
			tree.setValue(LuaUiText.text("资源包 JSON"), value, List.of());
		}
	}

	void resize(int screenWidth, int screenHeight) {
		this.screenWidth = screenWidth; this.screenHeight = screenHeight;
		if (x < 0) {
			width = Math.min(kind == Kind.DIRECTORY ? 310 : kind == Kind.JSON ? 610 : 420,
					Math.max(180, screenWidth - 24));
			height = Math.min(kind == Kind.PNG ? 490 : 430, Math.max(180, screenHeight - 80));
			x = Math.max(12, screenWidth - width - 30);
			y = Math.max(40, (screenHeight - height) / 2);
			floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height;
		}
	}

	void open() {
		open = true; keyboardFocus = true;
		if (kind == Kind.DIRECTORY) refresh();
		if (kind == Kind.JSON) loadJsonExamples();
	}
	void close() {
		editingFullPath = false;
		open = false; keyboardFocus = false; dragging = false; resizeEdges = 0;
		contextOpen = categoryPickerOpen = false; pendingCreate = PendingCreate.NONE;
		quickAddOpen = choosingOgg = false; pendingOgg = null; soundRequest++;
	}
	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F; }
	private int panelX() { return Math.round(screenWidth + (x - screenWidth) * progress); }
	boolean contains(double mouseX, double mouseY) {
		int visualX = panelX();
		if (directoryContextContains(mouseX, mouseY)) return true;
		return isVisible() && mouseX >= visualX - 5 && mouseX < visualX + width + 5
				&& mouseY >= y - 5 && mouseY < y + height + 5;
	}
	boolean isInteracting() { return dragging || resizeEdges != 0; }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dock; }
	void setLayoutLocked(boolean value) { layoutLocked = value; }
	void releaseKeyboardFocus() { keyboardFocus = false; inputFocus = false; }
	void requestKeyboardFocus() { keyboardFocus = true; }
	boolean hasKeyboardFocus() { return keyboardFocus; }
	boolean hasTransientUi() { return editingFullPath || contextOpen || categoryPickerOpen || quickAddOpen || choosingOgg || importingSound || pendingCreate != PendingCreate.NONE; }

	WindowGeometry captureGeometry() {
		if (!dock.docked()) return animation.destination(x, y, width, height);
		return new WindowGeometry(dock.docked() && floatingWidth > 0 ? floatingX : x,
				dock.docked() && floatingHeight > 0 ? floatingY : y,
				dock.docked() && floatingWidth > 0 ? floatingWidth : width,
				dock.docked() && floatingHeight > 0 ? floatingHeight : height);
	}

	void restoreGeometry(WindowGeometry geometry) {
		if (geometry == null) return;
		x = geometry.x(); y = geometry.y();
		width = Math.min(geometry.width(), Math.max(1, screenWidth));
		height = Math.min(geometry.height(), Math.max(1, screenHeight));
		resize(screenWidth, screenHeight);
		x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
		y = Math.clamp(y, 0, Math.max(0, screenHeight - height));
		floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height;
		animation.snapTo(x, y, width, height);
	}

	void setDockSide(DockSide side) {
		if (side == null || side == dock) return;
		if (!dock.docked()) { floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height; }
		DockSide old = dock; dock = side; resizeEdges = 0;
		if (old.docked() && !dock.docked()) animation.animateTo(x, y, width, height, floatingX, floatingY, floatingWidth, floatingHeight);
	}
	void setDockBounds(int x, int y, int width, int height) {
		if (dock.docked()) animation.animateTo(this.x, this.y, this.width, this.height, x, y, width, height);
	}
	void undockForDrag(double mouseX, double mouseY) {
		if (!dock.docked() || !dragging) return;
		dock = DockSide.FLOATING;
		width = Math.min(floatingWidth, Math.max(170, screenWidth - 16));
		height = Math.min(floatingHeight, Math.max(180, screenHeight - 35));
		x = Math.clamp((int) mouseX - width / 2, 0, Math.max(0, screenWidth - width));
		y = Math.clamp((int) mouseY - TITLE / 2, 24, Math.max(24, screenHeight - height));
		dragOffsetX = mouseX - x;
		dragOffsetY = mouseY - y;
		animation.snapTo(x, y, width, height);
	}
	void tick() {
		int revision = ScriptManager.catalogRevision();
		if (revision != observedCatalogRevision) {
			observedCatalogRevision = revision;
			if (open && kind == Kind.JSON) loadJsonExamples();
		}
		float target = open ? 1 : 0;
		progress += (target - progress) * 0.28F;
		if (Math.abs(progress - target) < 0.01F) progress = target;
		if (!isInteracting()) {
			var bounds = animation.tick(x, y, width, height);
			x = bounds.x(); y = bounds.y(); width = bounds.width(); height = bounds.height();
		}
	}

	void setJsonSource(String source) {
		if (kind != Kind.JSON || tree == null) return;
		try {
			String detected = jsonCategoryForPath(currentJsonPath.get());
			if (detected != null && !detected.equals(category)) selectJsonCategory(detected);
			value = JsonParser.parseString(source == null || source.isBlank() ? "{}" : source);
			tree.setValue(LuaUiText.text("资源包 JSON"), value, List.of());
			selectedExample = "";
			status = "当前 JSON 可在主代码编辑区直接编辑";
		} catch (RuntimeException error) { status = "请先修正主编辑区 JSON 语法：" + error.getMessage(); }
	}

	private static List<ResourcePackScaffoldCatalog.Category> jsonCategories() {
		List<ResourcePackScaffoldCatalog.Category> result = new ArrayList<>();
		result.add(new ResourcePackScaffoldCatalog.Category("pack.mcmeta", LuaUiText.text("资源包信息"),
				"资源包描述与版本兼容范围", ".json", "{}\n"));
		result.add(new ResourcePackScaffoldCatalog.Category("sounds.json", "声音事件",
				"将声音事件映射到 OGG 文件", ".json", "{}\n"));
		result.add(new ResourcePackScaffoldCatalog.Category("png.mcmeta", "纹理动画",
				"PNG 旁的动画配置文件", ".json", "{}\n"));
		for (var item : ResourcePackScaffoldCatalog.categories())
			if (item.extension().equals(".json")) result.add(item);
		return result;
	}

	private static String jsonCategoryForPath(String path) {
		if (path == null || path.isBlank()) return null;
		if (path.endsWith("/pack.mcmeta")) return "pack.mcmeta";
		if (path.endsWith("/sounds.json")) return "sounds.json";
		if (path.endsWith(".png.mcmeta")) return "png.mcmeta";
		var item = ResourcePackScaffoldCatalog.categoryFor(path);
		return item != null && item.extension().equals(".json") ? item.path() : null;
	}

	private void selectJsonCategory(String selectedCategory) {
		category = selectedCategory;
		selectedExample = "";
		jsonListScroll = 0;
		loadJsonExamples();
	}

	private void loadJsonExamples() {
		if (kind != Kind.JSON) return;
		if (category.equals("pack.mcmeta") || category.equals("sounds.json") || category.equals("png.mcmeta")) {
			examples = List.of();
			return;
		}
		try { examples = ResourcePackScaffoldCatalog.loadedExamples(minecraft, category); }
		catch (RuntimeException error) { examples = List.of(); setStatus("读取游戏资源失败：" + error.getMessage()); }
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (progress <= 0.01F) return;
		int drawX = panelX();
		int drawY = y;
		boolean directory = kind == Kind.DIRECTORY;
		if ((directory || kind == Kind.JSON) && !dock.docked())
			graphics.fill(drawX - 5, drawY + 5, drawX + width + 5, drawY + height + 5, 0x75000000);
		graphics.fill(drawX, drawY, drawX + width, drawY + height,
				directory ? 0xFE11151B : kind == Kind.JSON ? 0xFD10151E : 0xFF17242F);
		graphics.outline(drawX, drawY, width, height,
				directory ? 0xFFE0A45E : kind == Kind.JSON ? 0xFFB388FF : 0xFF8298A8);
		graphics.fill(drawX + 1, drawY + 1, drawX + width - 1, drawY + TITLE,
				directory ? 0xFF3B2A1F : kind == Kind.JSON ? 0xFF302148 : 0xFF30495B);
		graphics.text(font, font.plainSubstrByWidth(switch (kind) {
			case DIRECTORY -> "▣  resourcepacks 目录"; case JSON -> "◈  资源包 JSON 编辑器"; case PNG -> LuaUiText.text("图片工具");
		}, width - 44), drawX + 9, drawY + 9,
				directory ? 0xFFFFC47D : kind == Kind.JSON ? 0xFFD7B8FF : 0xFFE9F5F9);
		if (directory) graphics.fill(drawX + width - 26, drawY + 4, drawX + width - 6, drawY + 24,
				mouseX >= drawX + width - 26 && mouseX < drawX + width - 6 && mouseY >= drawY + 4
						&& mouseY < drawY + 24 ? 0xFFB83A46 : 0xFF5A4030);
		graphics.centeredText(font, "×", drawX + width - 16, drawY + 10,
				directory ? 0xFFFFFFFF : 0xFFFFC5BA);
		if (kind != Kind.JSON)
			graphics.enableScissor(drawX + 2, drawY + TITLE, drawX + width - 2, drawY + height - 22);
		if (kind == Kind.DIRECTORY) renderDirectory(graphics, mouseX, mouseY);
		else if (kind == Kind.JSON) renderJson(graphics, mouseX, mouseY);
		else renderPng(graphics, mouseX, mouseY);
		if (kind != Kind.JSON) graphics.disableScissor();
		if (kind != Kind.JSON) {
			graphics.fill(drawX + 1, drawY + height - 22, drawX + width - 1, drawY + height - 1,
					directory ? 0xFF11151B : 0xFF263B49);
			String footer = status.isEmpty() ? kind == Kind.DIRECTORY
					? "左键打开/展开 · 右键快捷创建" : "滚轮上下滚动 · 左右按钮切换色组" : status;
			graphics.text(font, font.plainSubstrByWidth(footer, width - 20), drawX + 10,
					drawY + height - 16, status.contains(LuaUiText.text("失败")) || status.contains(LuaUiText.text("无效"))
						? 0xFFFF7777 : directory ? 0xFF8C8176 : 0xFFD3E8EE);
		}
		if (!dock.docked()) ResizeBorderHint.render(graphics, drawX, drawY, width, height, mouseX, mouseY);
		if (kind == Kind.JSON && tree != null) tree.renderPopupOverlay(graphics, mouseX, mouseY);
	}

	private void renderDirectory(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = panelX();
		int top = y + TITLE;
		String location = directoryLocation();
		graphics.text(font, DirectoryPathInput.shorten(location, width - 24, font::width), x + 12, top + 8, 0xFFE5C58C);
		if (mouseX >= x + 10 && mouseX < x + width - 10 && mouseY >= top + 3 && mouseY < top + 20)
			graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal(location),
					Component.literal(LuaUiText.text("单击修改完整路径；输入框只编辑名称"))), mouseX, mouseY);
		graphics.fill(x + 10, top + PATH_TOP, x + width - 10, top + PATH_TOP + 20, 0xFF090D12);
		graphics.outline(x + 10, top + PATH_TOP, width - 20, 20, inputFocus ? 0xFFE0A45E : 0xFF54483D);
		renderPathInput(graphics, x + 17, top + PATH_TOP + 6, width - 34);
		boolean soundPrompt = assetImportPrompt();
		String[] actions = directoryActions();
		for (int i = 0; i < actions.length; i++) {
			int column = i % 3, row = i / 3;
			int bx = x + 7 + column * (width - 14) / 3, by = top + ACTIONS_TOP + row * 25;
			int bw = (width - 19) / 3;
			boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 22;
			graphics.fill(bx, by, bx + bw, by + 22, hovered ? 0xFFA8652E : 0xFF704A2C);
			graphics.centeredText(font, font.plainSubstrByWidth(actions[i], Math.max(1, bw - 4)), bx + bw / 2, by + 7, 0xFFFFFFFF);
		}
		if (soundPrompt) {
			graphics.text(font, font.plainSubstrByWidth((pendingCreate == PendingCreate.ITEM_TEXTURE ? "PNG：" : "OGG：")
					+ (pendingOgg == null ? "" : pendingOgg.getFileName()), width - 24),
					x + 12, top + 84, 0xFFE5C58C);
			graphics.text(font, font.plainSubstrByWidth((pendingCreate == PendingCreate.ITEM_TEXTURE ? "物品模型 ID" : "音效 ID")
					+ " · Enter 确认 / Esc 取消", width - 24), x + 12, top + 102, 0xFFAFA59A);
		}
		int rowsTop = top + DIRECTORY_ROWS_OFFSET;
		if (!soundPrompt) graphics.text(font, font.plainSubstrByWidth("目录树 · 左键打开 · 右键管理", width - 24), x + 12, rowsTop - 14, 0xFFAFA59A);
		graphics.fill(x + 10, rowsTop - 2, x + width - 10, y + height - 24, 0xFF080B0F);
		graphics.outline(x + 10, rowsTop - 2, width - 20,
				height - 22 - (DIRECTORY_ROWS_OFFSET + TITLE), 0xFF4E443A);
		int count = Math.max(1, (height - 22 - (rowsTop - y)) / ROW);
		listScroll = Math.clamp(listScroll, 0, Math.max(0, visible.size() - count));
		for (int row = 0; row < count && listScroll + row < visible.size(); row++) {
			var entry = visible.get(listScroll + row);
			int ry = rowsTop + row * ROW;
			if (entry.path().equals(selected) || mouseX >= x + 11 && mouseX < x + width - 11
					&& mouseY >= ry && mouseY < ry + ROW)
				graphics.fill(x + 12, ry, x + width - 12, ry + ROW - 1,
						entry.path().equals(selected) ? 0xFF32271F : 0xFF493426);
			String leaf = entry.path().substring(entry.path().lastIndexOf('/') + 1);
			String label = entry.directory() ? (collapsed.contains(entry.path()) ? "▸ " : "▾ ") + leaf : "• " + leaf;
			int tx = x + 9 + Math.min(12, entry.depth()) * 10;
			graphics.text(font, font.plainSubstrByWidth(label, x + width - tx - 18), tx, ry + 5,
					entry.directory() ? 0xFFE5C58C : 0xFFE5E8EC);
			if (!contextOpen && !categoryPickerOpen && !quickAddOpen && mouseX >= x + 5 && mouseX < x + width - 5
					&& mouseY >= ry && mouseY < ry + ROW)
				graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal(entry.path()),
						Component.literal(ResourcePackScaffoldCatalog.purpose(entry.path()))), mouseX, mouseY);
		}
	}
	private boolean assetImportPrompt() { return pendingCreate == PendingCreate.SOUND || pendingCreate == PendingCreate.ITEM_TEXTURE; }
	private String directoryLocation() {
		return assetImportPrompt() ? (pendingCreate == PendingCreate.ITEM_TEXTURE ? "物品模型 ID · " : "音效 ID · ") + pendingSoundNamespace
				: "resourcepacks/" + DirectoryPathInput.parent(path);
	}
	private String[] directoryActions() {
		if (assetImportPrompt()) return new String[]{importingSound ? "导入中…" : "确认导入", LuaUiText.text("取消")};
		if (pendingCreate != PendingCreate.NONE) return new String[]{pendingCreate == PendingCreate.RENAME ? "确认重命名" : "确认创建", LuaUiText.text("取消")};
		return new String[]{"新建包", LuaUiText.text("新建目录"), "新建 JSON", "新建 PNG", LuaUiText.text("刷新")};
	}
	private int pathNameStart() { return editingFullPath || assetImportPrompt() ? 0 : DirectoryPathInput.leafStart(path); }
	private void renderPathInput(GuiGraphicsExtractor graphics, int left, int textY, int inputWidth) {
		DirectoryPathInput.View view = DirectoryPathInput.view(path, pathCursor, pathNameStart(), inputWidth, font::width);
		int selectionStart = Math.clamp(Math.min(pathCursor, pathAnchor), view.start(), view.end());
		int selectionEnd = Math.clamp(Math.max(pathCursor, pathAnchor), selectionStart, view.end());
		if (inputFocus && selectionEnd > selectionStart)
			graphics.fill(left + font.width(path.substring(view.start(), selectionStart)), textY - 3,
					left + font.width(path.substring(view.start(), selectionEnd)), textY + font.lineHeight + 2, 0xFF805A35);
		graphics.text(font, view.text().isEmpty() ? LuaUiText.text("文件或目录名称") : view.text(), left, textY,
				view.text().isEmpty() ? 0xFF6F7780 : 0xFFFFFFFF);
		if (inputFocus && pathCursor == pathAnchor && pathCursor >= view.start() && pathCursor <= view.end()) {
			int cursorX = left + font.width(path.substring(view.start(), pathCursor));
			graphics.fill(cursorX, textY - 2, cursorX + 1, textY + font.lineHeight + 1, 0xFFFFFFFF);
		}
	}

	private void renderDirectoryContext(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!contextOpen && !categoryPickerOpen) return;
		int count = categoryPickerOpen ? categoryVisibleRows() : contextActions().size();
		int menuHeight = 6 + count * CONTEXT_ROW;
		int menuWidth = contextWidth();
		graphics.fill(contextX + 3, contextY + 3, contextX + menuWidth + 3, contextY + menuHeight + 3,
				0x99000000);
		graphics.fill(contextX, contextY, contextX + menuWidth, contextY + menuHeight, 0xFF1B2028);
		graphics.outline(contextX, contextY, menuWidth, menuHeight, 0xFFE0A45E);
		List<ResourcePackScaffoldCatalog.Category> categories = categoryPickerOpen ? categoryChoices() : List.of();
		for (int row = 0; row < count; row++) {
			int rowY = contextY + 3 + row * CONTEXT_ROW;
			boolean hovered = mouseX >= contextX + 2 && mouseX < contextX + menuWidth - 2
					&& mouseY >= rowY && mouseY < rowY + CONTEXT_ROW;
			if (hovered) graphics.fill(contextX + 2, rowY, contextX + menuWidth - 2,
					rowY + CONTEXT_ROW, 0xFF60432B);
			String label = categoryPickerOpen ? categories.get(contextCategoryScroll + row).path()
					+ " · " + categories.get(contextCategoryScroll + row).label()
					: contextLabel(contextActions().get(row));
			graphics.text(font, font.plainSubstrByWidth(label, menuWidth - 18),
					contextX + 9, rowY + 7, 0xFFECE7DE);
			if (hovered && categoryPickerOpen)
				graphics.setComponentTooltipForNextFrame(font,
						List.of(Component.literal(categories.get(contextCategoryScroll + row).purpose())), mouseX, mouseY);
		}
		if (quickAddOpen) {
			int sx = quickAddX(), sy = quickAddY();
			int sh = quickAddHeight();
			graphics.fill(sx + 3, sy + 3, sx + CONTEXT_WIDTH + 3, sy + sh + 3, 0x99000000);
			graphics.fill(sx, sy, sx + CONTEXT_WIDTH, sy + sh, 0xFF1B2028);
			graphics.outline(sx, sy, CONTEXT_WIDTH, sh, 0xFFE0A45E);
			String[] labels = {LuaUiText.text("快速添加 OGG 文件…"), LuaUiText.text("快速添加物品材质…")};
			for (int row = 0; row < labels.length; row++) {
				int ry = sy + 3 + row * CONTEXT_ROW;
				if (mouseX >= sx && mouseX < sx + CONTEXT_WIDTH && mouseY >= ry && mouseY < ry + CONTEXT_ROW)
					graphics.fill(sx + 2, ry, sx + CONTEXT_WIDTH - 2, ry + CONTEXT_ROW, 0xFF60432B);
				graphics.text(font, labels[row], sx + 9, ry + 7, 0xFFECE7DE);
			}
		}
	}
	void renderDirectoryPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (kind == Kind.DIRECTORY && open) renderDirectoryContext(graphics, mouseX, mouseY);
	}
	boolean directoryContextContains(double mouseX, double mouseY) {
		if (kind != Kind.DIRECTORY || !open || (!contextOpen && !categoryPickerOpen)) return false;
		int count = categoryPickerOpen ? categoryVisibleRows() : contextActions().size();
		return mouseX >= contextX && mouseX < contextX + contextWidth()
				&& mouseY >= contextY && mouseY < contextY + 6 + count * CONTEXT_ROW
				|| quickAddOpen && mouseX >= quickAddX() && mouseX < quickAddX() + CONTEXT_WIDTH
				&& mouseY >= quickAddY() && mouseY < quickAddY() + quickAddHeight();
	}
	void dismissDirectoryContext() { contextOpen = categoryPickerOpen = quickAddOpen = false; }
	private int quickAddX() {
		if (contextX + contextWidth() + CONTEXT_WIDTH <= screenWidth - 4) return contextX + contextWidth();
		if (contextX - CONTEXT_WIDTH >= 4) return contextX - CONTEXT_WIDTH;
		return Math.clamp(contextX + 12, 4, Math.max(4, screenWidth - CONTEXT_WIDTH - 4));
	}
	private int quickAddHeight() { return CONTEXT_ROW * 2 + 6; }
	private int quickAddY() {
		int row = Math.max(0, contextActions().indexOf(ContextAction.QUICK_ADD));
		return Math.clamp(contextY + row * CONTEXT_ROW, 4, Math.max(4, screenHeight - quickAddHeight() - 4));
	}

	private int contextWidth() { return Math.min(CONTEXT_WIDTH, Math.max(130, width - 16)); }
	private int categoryVisibleRows() {
		return Math.min(categoryChoices().size(), Math.max(2, Math.min(8, (height - TITLE - 35) / CONTEXT_ROW)));
	}
	private List<ContextAction> contextActions() {
		if (contextEntry == null) return List.of(ContextAction.NEW_PACK, ContextAction.OPEN_EXPLORER);
		String directory = contextDirectory();
		List<ContextAction> actions = new ArrayList<>();
		actions.add(ContextAction.OPEN);
		if (contextEntry.directory() && directory.matches("[^/]+/assets/[a-z0-9_.-]+")) actions.add(ContextAction.QUICK_ADD);
		if (directory.matches("[^/]+/assets")) actions.add(ContextAction.NEW_NAMESPACE);
		else {
			if (!availableCategories(directory).isEmpty()) actions.add(ContextAction.QUICK_CATEGORY);
			ResourcePackScaffoldCatalog.Category category = ResourcePackScaffoldCatalog.categoryFor(directory);
			if (category == null) {
				actions.add(ContextAction.NEW_JSON);
				actions.add(ContextAction.NEW_PNG);
			} else if (category.extension().equals(".json")) actions.add(ContextAction.NEW_JSON);
			else if (category.extension().equals(".png")) actions.add(ContextAction.NEW_PNG);
		}
		actions.add(ContextAction.NEW_DIRECTORY);
		actions.add(ContextAction.RENAME);
		actions.add(ContextAction.OPEN_EXPLORER);
		actions.add(ContextAction.DELETE);
		return actions;
	}
	private String contextDirectory() {
		return contextEntry == null ? "" : contextEntry.directory()
				? contextEntry.path() : parentOf(contextEntry.path());
	}
	private String contextLabel(ContextAction action) {
		return switch (action) {
			case OPEN -> contextEntry.directory()
					? collapsed.contains(contextEntry.path()) ? LuaUiText.text("展开目录") : LuaUiText.text("收起目录") : LuaUiText.text("打开文件");
			case NEW_PACK -> "新建资源包模板…";
			case NEW_NAMESPACE -> "新建命名空间…";
			case RENAME -> "重命名…";
			case OPEN_EXPLORER -> LuaUiText.text("在资源管理器中打开");
			case DELETE -> contextEntry.path().equals(pendingDelete) ? LuaUiText.text("确认删除") : contextEntry.directory() ? "删除目录…" : "删除文件…";
			case QUICK_CATEGORY -> "快捷创建资源类型…";
			case QUICK_ADD -> "快速添加  ▶";
			case NEW_DIRECTORY -> "在此新建文件夹…";
			case NEW_JSON -> {
				var category = ResourcePackScaffoldCatalog.categoryFor(contextDirectory());
				yield category == null ? "在此新建 JSON…" : LuaUiText.text("新建") + category.label() + "…";
			}
			case NEW_PNG -> {
				var category = ResourcePackScaffoldCatalog.categoryFor(contextDirectory());
				yield category == null ? "在此新建 PNG…" : LuaUiText.text("新建") + category.label() + "…";
			}
		};
	}
	private List<ResourcePackScaffoldCatalog.Category> categoryChoices() {
		return availableCategories(categoryParent);
	}
	private static List<ResourcePackScaffoldCatalog.Category> availableCategories(String directory) {
		String namespace = namespaceRoot(directory);
		if (namespace == null) return List.of();
		String relative = directory.substring(namespace.length());
		if (relative.startsWith("/")) relative = relative.substring(1);
		String prefix = relative.isEmpty() ? "" : relative + "/";
		List<ResourcePackScaffoldCatalog.Category> result = new ArrayList<>();
		for (var category : ResourcePackScaffoldCatalog.categories())
			if (category.path().startsWith(prefix) && !category.path().equals(relative)) result.add(category);
		return result;
	}
	private static String namespaceRoot(String directory) {
		if (directory == null) return null;
		String[] parts = directory.split("/");
		return parts.length < 3 || !parts[1].equals("assets") ? null
				: parts[0] + "/assets/" + parts[2];
	}

	private void renderJson(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelX(), right = left + width, bottom = y + height;
		int listLeft = left + 8, listWidth = jsonListWidth(), listRight = listLeft + listWidth;
		int listTop = y + TITLE + 61, contentBottom = bottom - 47;
		int tabWidth = listWidth / 2;
		jsonButton(graphics, mouseX, mouseY, listLeft, y + TITLE + 4, tabWidth - 2, 20,
				LuaUiText.text("类型"), jsonShowExamples ? 0xFF342D3F : 0xFF61408F);
		jsonButton(graphics, mouseX, mouseY, listLeft + tabWidth, y + TITLE + 4,
				listWidth - tabWidth, 20, "示例 " + examples.size(),
				jsonShowExamples ? 0xFF61408F : 0xFF342D3F);
		graphics.fill(listLeft, y + TITLE + 29, listRight, y + TITLE + 50, 0xFF090C12);
		graphics.outline(listLeft, y + TITLE + 29, listWidth, 21,
				jsonSearchFocused ? 0xFFB388FF : 0xFF554666);
		String shownQuery = jsonQuery.isEmpty() ? LuaUiText.text("搜索 ID / 中文 / 首拼…", "Search IDs or names…")
				: jsonQuery + (jsonSearchFocused ? "|" : "");
		graphics.text(font, font.plainSubstrByWidth(shownQuery, listWidth - 10), listLeft + 5,
				y + TITLE + 35, jsonQuery.isEmpty() ? 0xFF7A7486 : 0xFFF1E9FA);
		graphics.fill(listLeft, listTop, listRight, contentBottom, 0xFF0B1018);
		List<String> entries = visibleJsonEntries();
		int visibleRows = Math.max(1, (contentBottom - listTop) / JSON_ROW);
		jsonListScroll = Math.clamp(jsonListScroll, 0, Math.max(0, entries.size() - visibleRows));
		graphics.enableScissor(listLeft + 1, listTop + 1, listRight - 1, contentBottom - 1);
		for (int row = 0; row < visibleRows && row + jsonListScroll < entries.size(); row++) {
			String entry = entries.get(row + jsonListScroll);
			int rowY = listTop + row * JSON_ROW;
			boolean selectedRow = entry.equals(jsonShowExamples ? selectedExample : category);
			if (selectedRow) graphics.fill(listLeft + 1, rowY, listRight - 1, rowY + JSON_ROW, 0xFF43305D);
			var info = jsonShowExamples ? null : jsonCategories().stream()
					.filter(item -> item.path().equals(entry)).findFirst().orElse(null);
			String label = info == null ? entry : info.label() + " · " + entry;
			graphics.text(font, font.plainSubstrByWidth(label, listWidth - 9), listLeft + 5,
					rowY + 5, selectedRow ? 0xFFFFFFFF : 0xFFD1C4DE);
			if (mouseX >= listLeft && mouseX < listRight && mouseY >= rowY && mouseY < rowY + JSON_ROW)
				graphics.setComponentTooltipForNextFrame(font, List.of(
						Component.literal(info == null ? entry : info.label() + " · " + entry),
						Component.literal(info == null ? "当前客户端已加载的资源包 JSON 示例" : info.purpose())),
						mouseX, mouseY);
		}
		graphics.disableScissor();
		int treeLeft = listRight + 8, treeWidth = right - treeLeft - 8;
		String title = selectedExample.isEmpty() ? "当前文件 / 自定义 " + jsonCategoryLabel() : selectedExample;
		graphics.text(font, font.plainSubstrByWidth(title, treeWidth), treeLeft,
				y + TITLE + 6, 0xFFE9D9FF);
		if (tree != null) tree.render(graphics, mouseX, mouseY, treeLeft,
				y + TITLE + 19, treeWidth, contentBottom);
		graphics.text(font, font.plainSubstrByWidth(status, width - 18), left + 9,
				bottom - 42, status.contains(LuaUiText.text("错误")) || status.contains(LuaUiText.text("失败")) || status.contains(LuaUiText.text("无效"))
					? 0xFFFF7777 : 0xFFAA9DB8);
		int buttonWidth = (width - 24) / 3, buttonY = bottom - 28;
		jsonButton(graphics, mouseX, mouseY, left + 8, buttonY, buttonWidth, 20,
				"写入主编辑器", 0xFF61408F);
		jsonButton(graphics, mouseX, mouseY, left + 12 + buttonWidth, buttonY, buttonWidth, 20,
				LuaUiText.text("复制 JSON"), 0xFF2C4D65);
		jsonButton(graphics, mouseX, mouseY, left + 16 + buttonWidth * 2, buttonY,
				width - buttonWidth * 2 - 24, 20, LuaUiText.text("读取当前文件"), 0xFF2C4D65);
	}

	private void jsonButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int y, int buttonWidth, int buttonHeight, String label, int color) {
		graphics.fill(x, y, x + buttonWidth, y + buttonHeight,
				mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + buttonHeight
						? 0xFF7852A4 : color);
		graphics.centeredText(font, font.plainSubstrByWidth(label, buttonWidth - 5),
				x + buttonWidth / 2, y + 6, 0xFFFFFFFF);
	}

	private int jsonListWidth() { return Math.max(110, Math.min(190, width / 3)); }
	private String jsonCategoryLabel() {
		return jsonCategories().stream().filter(item -> item.path().equals(category))
				.map(ResourcePackScaffoldCatalog.Category::label).findFirst().orElse(category);
	}
	private List<String> visibleJsonEntries() {
		String needle = jsonQuery.strip().toLowerCase(Locale.ROOT);
		if (jsonShowExamples) return examples.stream().filter(id -> needle.isEmpty()
				|| id.toLowerCase(Locale.ROOT).contains(needle)).toList();
		return jsonCategories().stream().filter(item -> LuaUiText.matchesSearch(
				item.path() + " " + item.label() + " " + item.purpose(), needle))
				.map(ResourcePackScaffoldCatalog.Category::path).toList();
	}

	private void renderPng(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = panelX();
		LuaResourcePngCanvas png = canvas.get();
		if (png == null) {
			selectedPngPath = "";
			pngScroll = 0;
			graphics.text(font, LuaUiText.text("在资源包目录中打开 PNG"), x + 10, y + TITLE + 12, 0xFFD4E7ED);
			return;
		}
		if (!png.path().equals(selectedPngPath)) {
			selectedPngPath = png.path();
			pngScroll = 0;
			widthInput = Integer.toString(png.document().width());
			heightInput = Integer.toString(png.document().height());
			colorInput = String.format("#%06X", png.color() & 0xFFFFFF);
		}
		int maxScroll = maxPngScroll(png);
		pngScroll = Math.clamp(pngScroll, 0, maxScroll);
		int top = y + TITLE - pngScroll;
		color = png.color();
		String[] tools = {LuaUiText.text("铅笔"), LuaUiText.text("画笔"), LuaUiText.text("橡皮"), LuaUiText.text("填充"), LuaUiText.text("取色")};
		String[] toolHints = {"硬边方形像素笔触，可调大小", "柔边圆形笔触，可调大小", "擦除当前图层，可调大小", "填充连续的同色区域", "读取图片像素颜色"};
		for (int i = 0; i < tools.length; i++) {
			int bx = x + 10 + i * 28, by = top + 10;
			boolean hovered = mouseX >= bx && mouseX < bx + 24 && mouseY >= by && mouseY < by + 24
					&& mouseY >= y + TITLE && mouseY < y + height - 22;
			graphics.fill(bx, by, bx + 24, by + 24,
					png.tool().ordinal() == i ? 0xFF59869A : hovered ? 0xFF3E6679 : 0xFF2D4A5A);
			graphics.outline(bx, by, 24, 24, png.tool().ordinal() == i ? 0xFFE8F7FB : 0xFF668494);
			renderPngToolIcon(graphics, i, bx, by, 0xFFEAF6FA);
			if (hovered) graphics.setComponentTooltipForNextFrame(font, List.of(
					Component.literal(tools[i]), Component.literal(toolHints[i])), mouseX, mouseY);
		}
		graphics.text(font, tools[png.tool().ordinal()], x + 154, top + 17, 0xFFD8EDF4);
		int palettePage = Math.floorMod(Math.round(hue * 16), 16);
		graphics.text(font, LuaUiText.text("RGB 调色板  ") + (palettePage + 1) + "/16", x + 10, top + 45, 0xFFD2EAF2);
		for (int direction = 0; direction < 2; direction++) {
			int bx = x + width - (direction == 0 ? 59 : 32);
			boolean hovered = mouseX >= bx && mouseX < bx + 22 && mouseY >= top + 37 && mouseY < top + 59;
			graphics.fill(bx, top + 37, bx + 22, top + 59, hovered ? 0xFF527787 : 0xFF2D4A5A);
			graphics.centeredText(font, direction == 0 ? "◀" : "▶", bx + 11, top + 44, 0xFFEAF5F8);
		}
		int swatch = Math.max(5, Math.min(12, (width - 20) / 16));
		int pickerX = x + 10, pickerY = top + 59;
		for (int sy = 0; sy < 16; sy++) for (int sx = 0; sx < 16; sx++)
			graphics.fill(pickerX + sx * swatch, pickerY + sy * swatch,
					pickerX + (sx + 1) * swatch, pickerY + (sy + 1) * swatch,
					Color.HSBtoRGB(hue, sx / 15F, 1 - sy / 15F));
		for (int i = 0; i < 16; i++) graphics.fill(pickerX + i * swatch, pickerY + 16 * swatch + 3,
				pickerX + (i + 1) * swatch, pickerY + 16 * swatch + 15,
				Color.HSBtoRGB(i / 16F, 1, 1));
		graphics.outline(pickerX + palettePage * swatch, pickerY + 16 * swatch + 2,
				swatch, 14, 0xFFFFFFFF);
		int controls = pickerY + 16 * swatch + 22;
		graphics.fill(x + 10, controls, x + 32, controls + 22, color);
		graphics.outline(x + 10, controls, 22, 22, 0xFFE4F3F7);
		graphics.fill(x + 38, controls, x + width - 10, controls + 22, activeInput == 1 ? 0xFF40647A : 0xFF2A4353);
		graphics.text(font, colorInput + (activeInput == 1 ? "_" : ""), x + 43, controls + 7, 0xFFE6F3F7);
		boolean sizeTool = png.tool() == LuaPngLayerDocument.Tool.PENCIL
				|| png.tool() == LuaPngLayerDocument.Tool.BRUSH || png.tool() == LuaPngLayerDocument.Tool.ERASER;
		String sizeLabel = switch (png.tool()) {
			case PENCIL -> LuaUiText.text("铅笔大小");
			case BRUSH -> LuaUiText.text("画笔大小");
			case ERASER -> "橡皮大小";
			case FILL, PICKER -> "笔尖大小";
		};
		graphics.fill(x + 7, controls + 27, x + width - 7, controls + 49, 0xFF263E4D);
		graphics.text(font, sizeLabel, x + 10, controls + 34, sizeTool ? 0xFFD2E9F1 : 0xFF8299A4);
		graphics.centeredText(font, png.toolSize() + "px", x + width - 78, controls + 34,
				sizeTool ? 0xFFE6F3F7 : 0xFF8299A4);
		pngSmallButton(graphics, x + width - 62, controls + 27, "－", sizeTool);
		pngSmallButton(graphics, x + width - 34, controls + 27, "＋", sizeTool);
		graphics.text(font, LuaUiText.text("宽×高"), x + 10, controls + 61, 0xFFD2E9F1);
		graphics.fill(x + 55, controls + 54, x + 90, controls + 76, activeInput == 2 ? 0xFF40647A : 0xFF2A4353);
		graphics.fill(x + 103, controls + 54, x + 138, controls + 76, activeInput == 3 ? 0xFF40647A : 0xFF2A4353);
		graphics.text(font, widthInput + (activeInput == 2 ? "_" : ""), x + 58, controls + 61, 0xFFE6F3F7);
		graphics.text(font, "×", x + 94, controls + 61, 0xFFE6F3F7);
		graphics.text(font, heightInput + (activeInput == 3 ? "_" : ""), x + 106, controls + 61, 0xFFE6F3F7);
		graphics.fill(x + width - 52, controls + 54, x + width - 10, controls + 76, 0xFF375E71);
		graphics.centeredText(font, LuaUiText.text("应用"), x + width - 31, controls + 61, 0xFFEAF6FA);
		graphics.fill(x + 7, controls + 81, x + width - 7, controls + 103, 0xFF304C5B);
		graphics.text(font, LuaUiText.text("图层"), x + 10, controls + 88, 0xFFFFFFFF);
		String[] layerButtons = {"＋", "－", "↑", "↓"};
		for (int i = 0; i < layerButtons.length; i++)
			pngSmallButton(graphics, x + width - 110 + i * 25, controls + 81, layerButtons[i]);
		int layerTop = controls + 105;
		List<LuaPngLayerDocument.Layer> layers = png.document().layers();
		for (int row = 0; row < layers.size(); row++) {
			int index = layers.size() - 1 - row;
			var layer = layers.get(index);
			int ry = layerTop + row * ROW;
			graphics.fill(x + 7, ry, x + width - 7, ry + ROW - 1,
					index == png.document().selected() ? 0xFF416D81 : row % 2 == 0 ? 0xFF263E4D : 0xFF2D4858);
			graphics.text(font, (layer.visible() ? "◉ " : "○ ") + layer.name(), x + 12, ry + 5, 0xFFE4F3F6);
		}
		if (maxScroll > 0) {
			int railTop = y + TITLE + 4, railBottom = y + height - 26;
			int railHeight = railBottom - railTop;
			int thumbHeight = Math.max(24, railHeight * railHeight / (railHeight + maxScroll));
			int thumbTop = railTop + pngScroll * (railHeight - thumbHeight) / maxScroll;
			graphics.fill(x + width - 6, railTop, x + width - 3, railBottom, 0xFF223947);
			graphics.fill(x + width - 6, thumbTop, x + width - 3, thumbTop + thumbHeight, 0xFFB2D5E2);
		}
	}

	private int maxPngScroll(LuaResourcePngCanvas png) {
		int swatch = Math.max(5, Math.min(12, (width - 20) / 16));
		int layerBottom = y + TITLE + 59 + 16 * swatch + 22 + 105
				+ Math.max(1, png.document().layers().size()) * ROW;
		return Math.max(0, layerBottom + 8 - (y + height - 22));
	}

	private void pngSmallButton(GuiGraphicsExtractor graphics, int x, int y, String icon) {
		pngSmallButton(graphics, x, y, icon, true);
	}

	private void pngSmallButton(GuiGraphicsExtractor graphics, int x, int y, String icon, boolean enabled) {
		graphics.fill(x, y, x + 22, y + 22, enabled ? 0xFF3A6174 : 0xFF2B3D48);
		graphics.centeredText(font, icon, x + 11, y + 7, enabled ? 0xFFEAF6FA : 0xFF8299A4);
	}

	private static void renderPngToolIcon(GuiGraphicsExtractor graphics, int index, int x, int y, int color) {
		switch (index) {
			case 0 -> {
				// Photoshop-style pencil: thin diagonal barrel, dark side and pointed nib.
				for (int i = 0; i < 9; i++) {
					graphics.fill(x + 6 + i, y + 16 - i, x + 9 + i, y + 19 - i, 0xFF152832);
					graphics.fill(x + 7 + i, y + 16 - i, x + 8 + i, y + 18 - i, color);
				}
				graphics.fill(x + 15, y + 5, x + 18, y + 8, color);
				graphics.fill(x + 5, y + 18, x + 7, y + 20, color);
			}
			case 1 -> {
				// Photoshop-style brush: angled narrow handle and broad tapered bristles.
				for (int i = 0; i < 7; i++)
					graphics.fill(x + 10 + i, y + 11 - i, x + 12 + i, y + 13 - i, color);
				graphics.fill(x + 8, y + 11, x + 13, y + 14, 0xFF152832);
				graphics.fill(x + 7, y + 13, x + 12, y + 16, color);
				graphics.fill(x + 5, y + 16, x + 10, y + 19, color);
				graphics.fill(x + 5, y + 19, x + 7, y + 20, color);
			}
			case 2 -> {
				graphics.fill(x + 5, y + 9, x + 19, y + 17, color);
				graphics.fill(x + 7, y + 7, x + 17, y + 9, color);
				graphics.fill(x + 8, y + 11, x + 16, y + 13, 0xFF3A6174);
			}
			case 3 -> {
				graphics.fill(x + 6, y + 8, x + 16, y + 11, color);
				graphics.fill(x + 7, y + 11, x + 15, y + 15, color);
				graphics.fill(x + 16, y + 15, x + 19, y + 19, color);
			}
			case 4 -> {
				for (int i = 0; i < 8; i++) graphics.fill(x + 6 + i, y + 17 - i, x + 8 + i, y + 19 - i, color);
				graphics.fill(x + 14, y + 5, x + 19, y + 10, color);
				graphics.fill(x + 15, y + 6, x + 18, y + 9, 0xFF3A6174);
			}
			default -> { }
		}
	}

	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!open || !contains(event.x(), event.y())) return false;
		if (progress < 0.99F) return true;
		int ex = (int) event.x(), ey = (int) event.y();
		if (kind == Kind.DIRECTORY && (contextOpen || categoryPickerOpen)) {
			if (event.button() == 0 && clickDirectoryContext(ex, ey)) return true;
			contextOpen = categoryPickerOpen = false;
			quickAddOpen = false;
		}
		if (event.button() == 0 && dock.docked() && !layoutLocked && edgeAt(ex, ey) != 0) {
			resizeEdges = edgeAt(ex, ey); beginResize(ex, ey); return true;
		}
		if (event.button() == 0 && !dock.docked() && edgeAt(ex, ey) != 0) {
			resizeEdges = edgeAt(ex, ey); beginResize(ex, ey); return true;
		}
		if (ey < y + TITLE) {
			if (ex > x + width - 27) { close(); return true; }
			if (event.button() == 0 && !(dock.docked() && layoutLocked)) {
				dragging = true; dragOffsetX = ex - x; dragOffsetY = ey - y;
			}
			return true;
		}
		if (kind == Kind.DIRECTORY) clickDirectory(event);
		else if (kind == Kind.JSON) clickJson(event, doubleClick);
		else clickPng(ex, ey);
		keyboardFocus = true;
		return true;
	}

	private void clickDirectory(MouseButtonEvent event) {
		if (choosingOgg || importingSound) return;
		int ex = (int) event.x(), ey = (int) event.y();
		int top = y + TITLE;
		int rowsTop = top + DIRECTORY_ROWS_OFFSET;
		if (event.button() == 1) {
			if (ex >= x + 10 && ex < x + width - 10 && ey >= rowsTop && ey < y + height - 24)
				openDirectoryContext(ex, ey, rowsTop);
			return;
		}
		if (event.button() != 0) return;
		if (ex >= x + 10 && ex < x + width - 10 && ey >= top + 3 && ey < top + 20) {
			editingFullPath = true; inputFocus = true; pathAnchor = 0; pathCursor = path.length();
			setStatus("正在编辑完整路径 · Esc 返回名称编辑"); return;
		}
		if (ex >= x + 10 && ex < x + width - 10 && ey >= top + PATH_TOP && ey < top + PATH_TOP + 20) {
			editingFullPath = false; inputFocus = true;
			DirectoryPathInput.View view = DirectoryPathInput.view(path, pathCursor, pathNameStart(), width - 34, font::width);
			pathCursor = pathAnchor = DirectoryPathInput.offsetAt(path, view, ex - x - 17, font::width); return;
		}
		inputFocus = false;
		if (pendingCreate != PendingCreate.NONE) {
			if (ey >= top + ACTIONS_TOP && ey < top + ACTIONS_TOP + 22) {
				int col = Math.clamp((ex - x - 7) * 3 / Math.max(1, width - 14), 0, 2);
				if (col == 0) submitPendingCreate();
				else if (col == 1) cancelPendingCreate();
			}
			return;
		}
		if (ey >= top + ACTIONS_TOP && ey < top + ACTIONS_TOP + 47) {
			int col = Math.clamp((ex - x - 7) * 3 / Math.max(1, width - 14), 0, 2);
			int row = (ey - top - ACTIONS_TOP) / 25;
			switch (row * 3 + col) {
				case 0 -> action(() -> LuaResourcePackWorkspace.createPack(path.strip()), "已创建资源包模板");
				case 1 -> action(() -> LuaResourcePackWorkspace.createDirectory(path.strip()), "已创建目录");
				case 2 -> createText();
				case 3 -> createPng();
				case 4 -> refresh();
			}
			return;
		}
		if (ey < rowsTop) return;
		int index = listScroll + (ey - rowsTop) / ROW;
		if (index < 0 || index >= visible.size()) return;
		var entry = visible.get(index);
		pendingCreate = PendingCreate.NONE;
		editingFullPath = false;
		selected = entry.path(); path = selected; pathCursor = pathAnchor = path.length(); pendingDelete = "";
		if (entry.directory()) {
			if (!collapsed.add(selected)) collapsed.remove(selected);
			rebuildVisible(); setStatus(ResourcePackScaffoldCatalog.purpose(selected));
		} else opener.accept(selected);
	}

	private void openDirectoryContext(int ex, int ey, int rowsTop) {
		quickAddOpen = false;
		int index = listScroll + (ey - rowsTop) / ROW;
		contextEntry = index >= 0 && index < visible.size() ? visible.get(index) : null;
		if (contextEntry != null) {
			if (!contextEntry.path().equals(selected)) pendingDelete = "";
			selected = contextEntry.path(); path = selected; pathCursor = pathAnchor = path.length();
		}
		editingFullPath = false;
		pendingCreate = PendingCreate.NONE;
		categoryPickerOpen = false;
		contextOpen = true;
		placeDirectoryContext(ex, ey, contextActions().size());
		inputFocus = false; keyboardFocus = true;
	}

	private void placeDirectoryContext(int ex, int ey, int rows) {
		int menuHeight = 6 + rows * CONTEXT_ROW;
		contextX = Math.clamp(ex, 4, Math.max(4, screenWidth - contextWidth() - 4));
		contextY = Math.clamp(ey, 4, Math.max(4, screenHeight - menuHeight - 4));
	}

	private boolean clickDirectoryContext(int ex, int ey) {
		if (quickAddOpen && ex >= quickAddX() && ex < quickAddX() + CONTEXT_WIDTH
				&& ey >= quickAddY() && ey < quickAddY() + quickAddHeight()) {
			int row = (ey - quickAddY() - 3) / CONTEXT_ROW;
			if (ey < quickAddY() + 3 || ey >= quickAddY() + quickAddHeight() - 3) return true;
			quickAddOpen = contextOpen = false;
			chooseAsset(contextDirectory(), row == 1);
			return true;
		}
		int count = categoryPickerOpen ? categoryVisibleRows() : contextActions().size();
		if (ex < contextX || ex >= contextX + contextWidth()
				|| ey < contextY || ey >= contextY + 6 + count * CONTEXT_ROW) return false;
		int row = (ey - contextY - 3) / CONTEXT_ROW;
		if (ey >= contextY + 3 && row >= 0 && row < count) {
			if (categoryPickerOpen) {
				var category = categoryChoices().get(contextCategoryScroll + row);
				String namespace = namespaceRoot(categoryParent);
				if (namespace != null) {
					String target = namespace + "/" + category.path();
					revealPath(target);
					if (entries.stream().anyMatch(entry -> entry.directory() && entry.path().equals(target))) {
						rebuildVisible(); setStatus("目录已存在，已展开：" + target);
					} else action(() -> LuaResourcePackWorkspace.createDirectory(target), "已创建资源类型目录：" + target);
				}
				categoryPickerOpen = false;
			} else {
				ContextAction action = contextActions().get(row);
				contextOpen = action == ContextAction.QUICK_ADD;
				quickAddOpen = false;
				runDirectoryContextAction(action);
			}
		}
		return true;
	}

	private void runDirectoryContextAction(ContextAction action) {
		String directory = contextDirectory();
		switch (action) {
			case OPEN -> {
				if (contextEntry == null) return;
				if (contextEntry.directory()) {
					if (!collapsed.add(contextEntry.path())) collapsed.remove(contextEntry.path());
					rebuildVisible();
				} else opener.accept(contextEntry.path());
			}
			case NEW_PACK -> prepareCreate(PendingCreate.PACK, "", "new_pack", "");
			case NEW_NAMESPACE -> prepareCreate(PendingCreate.NAMESPACE, directory, "new_namespace", "");
			case QUICK_CATEGORY -> {
				categoryParent = directory; contextCategoryScroll = 0; categoryPickerOpen = true;
				placeDirectoryContext(contextX, contextY, categoryVisibleRows());
			}
			case QUICK_ADD -> quickAddOpen = true;
			case OPEN_EXPLORER -> {
				try {
					LocalWorkspaceLauncher.openResourcePack(contextEntry == null ? "" : contextEntry.path(),
							contextEntry == null || contextEntry.directory());
					setStatus("已在资源管理器打开对应位置");
				} catch (Exception error) { setStatus("打开资源管理器失败：" + error.getMessage()); }
			}
			case NEW_DIRECTORY -> prepareCreate(PendingCreate.DIRECTORY, directory, "new_folder", "");
			case NEW_JSON -> prepareCreate(PendingCreate.JSON, directory, "new_resource", ".json");
			case NEW_PNG -> prepareCreate(PendingCreate.PNG, directory, "new_texture", ".png");
			case RENAME -> {
				if (!canChangePath.test(selected)) { setStatus("请先关闭此文件或目录下所有已打开的标签"); return; }
				pathBeforeAction = path; path = selected; editingFullPath = false;
				pathAnchor = DirectoryPathInput.leafStart(path); pathCursor = path.length();
				pendingCreate = PendingCreate.RENAME; inputFocus = keyboardFocus = true;
				setStatus("输入新名称 · Enter 或确认重命名 · Esc 取消");
			}
			case DELETE -> delete();
		}
	}

	private void prepareCreate(PendingCreate kind, String directory, String base, String extension) {
		pathBeforeAction = path; editingFullPath = false;
		String prefix = directory.isEmpty() ? "" : directory + "/";
		String candidate = base;
		for (int number = 2; entryExists(prefix + candidate + extension); number++)
			candidate = base + number;
		path = prefix + candidate + extension;
		pathAnchor = prefix.length(); pathCursor = pathAnchor + candidate.length();
		pendingCreate = kind; inputFocus = keyboardFocus = true;
		setStatus("输入名称后按 Enter 创建；Esc 取消");
	}

	private void chooseAsset(String namespaceDirectory, boolean itemTexture) {
		if (choosingOgg || importingSound) return;
		editingFullPath = false;
		choosingOgg = true;
		int request = ++soundRequest;
		var ownerScreen = minecraft.screen;
		setStatus(itemTexture ? "请选择 PNG 文件，之后输入物品模型 ID" : "请选择 OGG 文件，之后输入音效 ID");
		(itemTexture ? LuaOggFileChooser.choosePng() : LuaOggFileChooser.choose()).whenComplete((file, error) -> minecraft.execute(() -> {
			if (request != soundRequest) return;
			choosingOgg = false;
			if (!open || minecraft.screen != ownerScreen) return;
			if (error != null) {
				Throwable reason = error.getCause() == null ? error : error.getCause();
				wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.LOGGER.error("Failed to open native resource file chooser", reason);
				setStatus("选择文件失败：" + reason.getClass().getSimpleName()
						+ (reason.getMessage() == null ? "" : " · " + reason.getMessage())); return;
			}
			if (file == null) { setStatus("已取消选择文件"); return; }
			String extension = itemTexture ? ".png" : ".ogg";
			if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
				setStatus("请选择 " + extension + " 文件"); return;
			}
			pendingOgg = file;
			pendingSoundNamespace = namespaceDirectory;
			String name = file.getFileName().toString();
			name = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
			if (name.isBlank() || name.equals(".") || name.equals("..")) name = itemTexture ? "new_item" : "new_sound";
			if (name.length() > 59) name = name.substring(0, 59);
			String namespace = namespaceDirectory.substring(namespaceDirectory.lastIndexOf('/') + 1);
			path = namespace + ":" + name;
			pathAnchor = namespace.length() + 1; pathCursor = path.length();
			pendingCreate = itemTexture ? PendingCreate.ITEM_TEXTURE : PendingCreate.SOUND;
			inputFocus = keyboardFocus = true;
			setStatus(itemTexture ? "输入物品模型 ID · Enter 导入 / Esc 取消 · 自动创建贴图、模型、物品定义"
					: "输入音效 ID 后按 Enter / 确认导入，Esc 取消；无需填写 .ogg 后缀");
		}));
	}

	private void cancelSoundImport() {
		pendingCreate = PendingCreate.NONE; pendingOgg = null;
		path = selected; pathCursor = pathAnchor = path.length();
		inputFocus = false;
		setStatus("已取消资源导入");
	}

	private void submitSoundImport() {
		if (!assetImportPrompt() || pendingOgg == null || importingSound) return;
		boolean itemTexture = pendingCreate == PendingCreate.ITEM_TEXTURE;
		if (!itemTexture && !canChangePath.test(pendingSoundNamespace + "/sounds.json")) {
			setStatus("请先保存并关闭该命名空间 sounds.json 的编辑标签，再确认导入");
			inputFocus = true; return;
		}
		String namespaceDirectory = pendingSoundNamespace, id = path.strip();
		Path source = pendingOgg;
		int request = soundRequest;
		var ownerScreen = minecraft.screen;
		importingSound = true;
		setStatus(itemTexture ? "正在创建物品材质和模型…" : "正在导入 OGG 并更新 sounds.json…");
		CompletableFuture.supplyAsync(() -> {
			try {
				if (itemTexture) {
					var result = LuaResourcePackWorkspace.importItemTexture(namespaceDirectory, source, id);
					return new AssetImportResult(result.id(), result.texturePath());
				}
				var result = LuaResourcePackWorkspace.importSound(namespaceDirectory, source, id);
				return new AssetImportResult(result.id(), result.audioPath());
			}
			catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
		}).whenComplete((result, error) -> minecraft.execute(() -> {
			importingSound = false;
			if (!open || request != soundRequest || minecraft.screen != ownerScreen) return;
			if (error != null) {
				Throwable reason = error.getCause() == null ? error : error.getCause();
				setStatus(LuaUiText.text("导入失败：") + reason.getMessage()); inputFocus = keyboardFocus = true; return;
			}
			pendingCreate = PendingCreate.NONE; pendingOgg = null; inputFocus = false;
			path = selected = namespaceDirectory + "/" + result.assetPath();
			pathAnchor = pathCursor = path.length();
			revealPath(selected); refresh();
			setStatus("已添加" + (itemTexture ? "物品材质 " : "音效 ") + result.id() + " · 启用资源包后按 F3+T 刷新");
			if (itemTexture) opener.accept(selected);
		}));
	}
	private record AssetImportResult(String id, String assetPath) { }
	private boolean entryExists(String candidate) {
		for (var entry : entries) if (entry.path().equals(candidate)) return true;
		return false;
	}

	private void submitPendingCreate() {
		if (assetImportPrompt()) { submitSoundImport(); return; }
		String requested = path.strip();
		String target = pendingCreate == PendingCreate.JSON && !requested.endsWith(".json") && !requested.endsWith(".mcmeta")
				? requested + ".json" : pendingCreate == PendingCreate.PNG && !requested.endsWith(".png")
				? requested + ".png" : requested;
		boolean success = switch (pendingCreate) {
			case PACK -> action(() -> LuaResourcePackWorkspace.createPack(target), "已创建资源包模板：" + target);
			case NAMESPACE, DIRECTORY -> action(() -> LuaResourcePackWorkspace.createDirectory(target), "已创建目录：" + target);
			case JSON -> action(() -> LuaResourcePackWorkspace.createText(target,
					ResourcePackScaffoldCatalog.template(target)), "已创建 JSON：" + target);
			case PNG -> action(() -> LuaResourcePackWorkspace.createPng(target, 16, 16), "已创建 PNG：" + target);
			case NONE -> false;
			case SOUND, ITEM_TEXTURE -> false; // handled above
			case RENAME -> rename();
		};
		if (success) {
			path = target;
			revealPath(target); rebuildVisible();
			if (pendingCreate == PendingCreate.JSON || pendingCreate == PendingCreate.PNG) opener.accept(target);
			pendingCreate = PendingCreate.NONE; inputFocus = false;
			selected = target; pathCursor = pathAnchor = path.length();
		}
	}
	private void cancelPendingCreate() {
		if (assetImportPrompt()) { cancelSoundImport(); return; }
		boolean renaming = pendingCreate == PendingCreate.RENAME;
		path = pathBeforeAction; pathCursor = pathAnchor = path.length(); editingFullPath = false;
		pendingCreate = PendingCreate.NONE; inputFocus = false;
		setStatus(renaming ? "已取消重命名" : "已取消新建");
	}

	private void revealPath(String target) {
		for (String parent = parentOf(target); parent != null; parent = parentOf(parent)) collapsed.remove(parent);
	}

	private void clickJson(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return;
		if (tree != null && tree.hasPopup()) { tree.mouseClicked(event, doubleClick); return; }
		int left = panelX(), bottom = y + height, buttonY = bottom - 28;
		int buttonWidth = (width - 24) / 3;
		if (event.y() >= buttonY && event.y() < buttonY + 20) {
			if (event.x() >= left + 8 && event.x() < left + 8 + buttonWidth) {
				if (tree == null || tree.commitPendingEdit()) {
					jsonChange.accept(PRETTY.toJson(value) + "\n");
					setStatus("已写入主编辑器 · Ctrl+S 保存");
				}
			} else if (event.x() >= left + 12 + buttonWidth && event.x() < left + 12 + buttonWidth * 2) {
				if (tree == null || tree.commitPendingEdit()) {
					minecraft.keyboardHandler.setClipboard(PRETTY.toJson(value) + "\n");
					setStatus("资源包 JSON 已复制");
				}
			} else if (event.x() >= left + 16 + buttonWidth * 2 && event.x() < left + width - 8)
				message.accept("resource-json-refresh");
			return;
		}
		int listLeft = left + 8, listRight = listLeft + jsonListWidth();
		if (event.x() >= listLeft && event.x() < listRight
				&& event.y() >= y + TITLE + 4 && event.y() < y + TITLE + 24) {
			jsonShowExamples = event.x() >= listLeft + jsonListWidth() / 2;
			jsonQuery = ""; jsonListScroll = 0; jsonSearchFocused = false;
			return;
		}
		if (event.x() >= listLeft && event.x() < listRight
				&& event.y() >= y + TITLE + 29 && event.y() < y + TITLE + 50) {
			jsonSearchFocused = true;
			return;
		}
		jsonSearchFocused = false;
		int listTop = y + TITLE + 61;
		if (event.x() >= listLeft && event.x() < listRight
				&& event.y() >= listTop && event.y() < bottom - 47) {
			List<String> entries = visibleJsonEntries();
			int index = jsonListScroll + ((int) event.y() - listTop) / JSON_ROW;
			if (index >= 0 && index < entries.size()) activateJsonEntry(entries.get(index));
			return;
		}
		if (tree != null) tree.mouseClicked(event, doubleClick);
	}

	private void activateJsonEntry(String entry) {
		if (!jsonShowExamples) {
			selectJsonCategory(entry);
			setStatus("已切换到 " + jsonCategoryLabel() + " · 右侧仍保留当前文件内容");
			return;
		}
		try {
			String source = ResourcePackScaffoldCatalog.readLoadedExample(minecraft, entry);
			value = JsonParser.parseString(source);
			tree.setValue(LuaUiText.text("资源包 JSON"), value, List.of());
			selectedExample = entry;
			jsonChange.accept(PRETTY.toJson(value) + "\n");
			setStatus("已载入 " + entry + " 并写入主编辑器 · Ctrl+S 保存");
		} catch (IOException | RuntimeException error) { setStatus("载入示例失败：" + error.getMessage()); }
	}

	private void clickPng(int ex, int ey) {
		LuaResourcePngCanvas png = canvas.get(); if (png == null) return;
		if (ey < y + TITLE || ey >= y + height - 22) return;
		int top = y + TITLE - pngScroll;
		if (ey >= top + 10 && ey < top + 34) {
			for (int index = 0; index < LuaPngLayerDocument.Tool.values().length; index++) {
				int bx = x + 10 + index * 28;
				if (ex >= bx && ex < bx + 24) {
					png.setTool(LuaPngLayerDocument.Tool.values()[index]);
					activeInput = 0;
					break;
				}
			}
			return;
		}
		if (ey >= top + 37 && ey < top + 59) {
			if (ex >= x + width - 59 && ex < x + width - 37) shiftPalette(-1);
			else if (ex >= x + width - 32 && ex < x + width - 10) shiftPalette(1);
			return;
		}
		int swatch = Math.max(5, Math.min(12, (width - 20) / 16));
		int pickerX = x + 10, pickerY = top + 59;
		if (ex >= pickerX && ex < pickerX + 16 * swatch) {
			if (ey >= pickerY && ey < pickerY + 16 * swatch) {
				int sx = Math.clamp((ex - pickerX) / swatch, 0, 15), sy = Math.clamp((ey - pickerY) / swatch, 0, 15);
				png.setColor(Color.HSBtoRGB(hue, sx / 15F, 1 - sy / 15F));
				colorInput = String.format("#%06X", png.color() & 0xFFFFFF); return;
			}
			if (ey >= pickerY + 16 * swatch + 3 && ey < pickerY + 16 * swatch + 16) {
				hue = Math.clamp((ex - pickerX) / (float) swatch, 0, 15) / 16F; return;
			}
		}
		int controls = pickerY + 16 * swatch + 22;
		if (ey >= controls && ey < controls + 23) {
			if (ex >= x + 38 && ex < x + width - 10) activeInput = 1;
			return;
		}
		if (ey >= controls + 27 && ey < controls + 49) {
			if (ex >= x + width - 62 && ex < x + width - 40) png.setToolSize(png.toolSize() - 1);
			else if (ex >= x + width - 34 && ex < x + width - 12) png.setToolSize(png.toolSize() + 1);
			activeInput = 0;
			return;
		}
		if (ey >= controls + 54 && ey < controls + 76) {
			if (ex >= x + 55 && ex < x + 90) activeInput = 2;
			else if (ex >= x + 103 && ex < x + 138) activeInput = 3;
			else if (ex >= x + width - 52 && ex < x + width - 10) applyPngInputs();
			return;
		}
		if (ey >= controls + 81 && ey < controls + 103) {
			for (int slot = 0; slot < 4; slot++) {
				int bx = x + width - 110 + slot * 25;
				if (ex < bx || ex >= bx + 22) continue;
				switch (slot) {
					case 0 -> png.document().addLayer(); case 1 -> png.document().deleteLayer();
					case 2 -> png.document().moveLayer(1); case 3 -> png.document().moveLayer(-1);
					default -> { }
				}
				png.markDirty();
				break;
			}
			activeInput = 0;
			return;
		}
		int layerTop = controls + 105;
		if (ey >= layerTop) {
			int row = (ey - layerTop) / ROW;
			int index = png.document().layers().size() - 1 - row;
			if (index >= 0 && index < png.document().layers().size()) {
				if (ex < x + 35) { png.document().toggleVisible(index); png.markDirty(); }
				else png.document().select(index);
			}
		}
	}

	private void createText() {
		String file = path.strip(); if (!file.endsWith(".json") && !file.endsWith(".mcmeta")) file += ".json";
		String target = file;
		if (action(() -> LuaResourcePackWorkspace.createText(target, ResourcePackScaffoldCatalog.template(target)), "已创建 JSON"))
			opener.accept(target);
	}
	private void createPng() {
		String file = path.strip(); if (!file.endsWith(".png")) file += ".png";
		String target = file;
		if (action(() -> LuaResourcePackWorkspace.createPng(target, 16, 16), "已创建 16 × 16 PNG"))
			opener.accept(target);
	}
	private boolean rename() {
		if (selected.isEmpty()) { setStatus(LuaUiText.text("请先选择文件或目录")); return false; }
		if (!canChangePath.test(selected)) { setStatus("请先关闭此文件或目录下所有已打开的标签"); return false; }
		String target = path.strip();
		if (!target.contains("/")) { int slash = selected.lastIndexOf('/'); if (slash >= 0) target = selected.substring(0, slash + 1) + target; }
		String finalTarget = target;
		if (finalTarget.equals(selected)) { setStatus(LuaUiText.text("请输入不同的新名称")); return false; }
		if (action(() -> LuaResourcePackWorkspace.rename(selected, finalTarget), "已重命名")) {
			selected = finalTarget; path = finalTarget;
			pathCursor = pathAnchor = path.length(); return true;
		}
		return false;
	}
	private void delete() {
		if (selected.isEmpty()) { setStatus(LuaUiText.text("请先选择文件或目录")); return; }
		if (!canChangePath.test(selected)) { setStatus("请先关闭此文件或目录下所有已打开的标签"); return; }
		if (!selected.equals(pendingDelete)) { pendingDelete = selected; setStatus("再次右键此项并选择“确认删除”：" + selected); return; }
		String target = selected;
		if (action(() -> LuaResourcePackWorkspace.delete(target), LuaUiText.text("已删除 ") + target))
			pendingDelete = selected = "";
	}
	private interface IoAction { void run() throws IOException; }
	private boolean action(IoAction action, String success) {
		try { action.run(); setStatus(success); refresh(); return true; }
		catch (IOException error) { setStatus("操作失败：" + error.getMessage()); return false; }
	}
	private void applyPngInputs() {
		LuaResourcePngCanvas png = canvas.get(); if (png == null) return;
		try {
			String hex = colorInput.strip().replaceFirst("^#", "");
			if (!hex.matches("(?i)[0-9a-f]{6}")) throw new IllegalArgumentException("颜色需为 #RRGGBB");
			png.setColor(0xFF000000 | Integer.parseInt(hex, 16));
			hue = Color.RGBtoHSB((png.color() >>> 16) & 255, (png.color() >>> 8) & 255,
					png.color() & 255, null)[0];
			png.document().resizeCanvas(Integer.parseInt(widthInput.strip()), Integer.parseInt(heightInput.strip()));
			png.markDirty(); activeInput = 0;
			setStatus("颜色与画布分辨率已应用 · Ctrl+S 保存");
		} catch (RuntimeException error) { setStatus("输入无效：" + error.getMessage()); }
	}
	private void setStatus(String text) { status = text == null ? "" : text; }
	private void refresh() {
		try { entries = LuaResourcePackWorkspace.list(); ScriptManager.invalidateAllRegistryCatalogs(); rebuildVisible(); }
		catch (IOException error) { setStatus(LuaUiText.text("读取目录失败：") + error.getMessage()); }
	}
	private void rebuildVisible() {
		List<LuaResourcePackWorkspace.Entry> list = new ArrayList<>();
		for (var entry : entries) {
			String parent = entry.path(); boolean hidden = false;
			while ((parent = parentOf(parent)) != null) if (collapsed.contains(parent)) { hidden = true; break; }
			if (!hidden) list.add(entry);
		}
		visible = List.copyOf(list);
	}
	private static String parentOf(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? null : path.substring(0, slash); }

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !contains(mouseX, mouseY)) return false;
		if (progress < 0.99F) return true;
		if (amount == 0) return true;
		if (kind == Kind.DIRECTORY) {
			if (quickAddOpen || assetImportPrompt() || choosingOgg || importingSound) return true;
			if (contextOpen && directoryContextContains(mouseX, mouseY)) return true;
			if (categoryPickerOpen) contextCategoryScroll = Math.clamp(contextCategoryScroll - (int) Math.round(amount * 3),
					0, Math.max(0, categoryChoices().size() - categoryVisibleRows()));
			else {
				contextOpen = false;
				listScroll = Math.max(0, listScroll - (int) Math.round(amount * 3));
			}
		}
		else if (kind == Kind.JSON) {
			if (mouseX < panelX() + 8 + jsonListWidth()) {
				int visibleRows = Math.max(1, (height - TITLE - 108) / JSON_ROW);
				jsonListScroll = Math.clamp(jsonListScroll - (int) Math.signum(amount) * 3,
						0, Math.max(0, visibleJsonEntries().size() - visibleRows));
			} else if (tree != null) tree.mouseScrolled(mouseX, mouseY, amount);
		} else {
			LuaResourcePngCanvas png = canvas.get();
			if (png != null) pngScroll = Math.clamp(pngScroll - (int) Math.round(amount * 24), 0, maxPngScroll(png));
		}
		return true;
	}
	private void shiftPalette(int delta) {
		int page = Math.floorMod(Math.round(hue * 16) + delta, 16);
		hue = page / 16F;
		setStatus("调色板颜色组 " + (page + 1) + "/16 · 使用左右按钮切换");
	}
	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocus) return false;
		if (kind == Kind.DIRECTORY && importingSound) return true;
		if (kind == Kind.JSON && tree != null && tree.hasPopup()
				&& event.key() == InputConstants.KEY_ESCAPE) return tree.keyPressed(event);
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (quickAddOpen) quickAddOpen = false;
			else if (contextOpen || categoryPickerOpen) contextOpen = categoryPickerOpen = false;
			else if (kind == Kind.DIRECTORY && editingFullPath) { editingFullPath = false; pathCursor = pathAnchor = path.length(); }
			else if (assetImportPrompt() && !importingSound) cancelSoundImport();
			else if (pendingCreate != PendingCreate.NONE) {
				cancelPendingCreate();
			} else close();
			return true;
		}
		if (kind == Kind.DIRECTORY && (choosingOgg || importingSound)) return true;
		if (kind == Kind.PNG && activeInput != 0) {
			if (event.key() == InputConstants.KEY_RETURN) { applyPngInputs(); return true; }
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				String value = activeInput == 1 ? colorInput : activeInput == 2 ? widthInput : heightInput;
				if (!value.isEmpty()) value = value.substring(0, value.length() - 1);
				setActiveInputValue(value); return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_A) { setActiveInputValue(""); return true; }
			if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
				setActiveInputValue(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", "")); return true;
			}
		}
		if (kind == Kind.JSON && tree != null) {
			if (tree.hasPopup()) return tree.keyPressed(event);
			if (jsonSearchFocused) {
				if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
					jsonQuery = ""; jsonListScroll = 0; return true;
				}
				if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
					jsonQuery = (jsonQuery + minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " "));
					if (jsonQuery.length() > 80) jsonQuery = jsonQuery.substring(0, 80);
					jsonListScroll = 0; return true;
				}
				if (event.key() == InputConstants.KEY_BACKSPACE) {
					if (!jsonQuery.isEmpty()) jsonQuery = jsonQuery.substring(0, jsonQuery.offsetByCodePoints(jsonQuery.length(), -1));
					jsonListScroll = 0; return true;
				}
				if (event.key() == InputConstants.KEY_RETURN) {
					List<String> found = visibleJsonEntries();
					if (!found.isEmpty()) activateJsonEntry(found.get(0));
					return true;
				}
			}
			if (tree.keyPressed(event)) return true;
		}
		if (kind == Kind.DIRECTORY && inputFocus) {
			pathCursor = Math.clamp(pathCursor, pathNameStart(), path.length());
			pathAnchor = Math.clamp(pathAnchor, pathNameStart(), path.length());
			if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
				insertPath(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", "")); return true;
			}
			if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
				pathAnchor = pathNameStart(); pathCursor = path.length(); return true;
			}
			if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
				if (event.key() == InputConstants.KEY_LEFT && pathCursor > pathNameStart()) pathCursor = path.offsetByCodePoints(pathCursor, -1);
				else if (event.key() == InputConstants.KEY_RIGHT && pathCursor < path.length()) pathCursor = path.offsetByCodePoints(pathCursor, 1);
				if (!event.hasShiftDown()) pathAnchor = pathCursor;
				return true;
			}
			if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
				pathCursor = event.key() == InputConstants.KEY_HOME ? pathNameStart() : path.length();
				if (!event.hasShiftDown()) pathAnchor = pathCursor;
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE) {
				if (pathCursor != pathAnchor) insertPath("");
				else if (event.key() == InputConstants.KEY_BACKSPACE && pathCursor > pathNameStart()) {
					pathAnchor = path.offsetByCodePoints(pathCursor, -1); insertPath("");
				} else if (event.key() == InputConstants.KEY_DELETE && pathCursor < path.length()) {
					pathAnchor = path.offsetByCodePoints(pathCursor, 1); insertPath("");
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				if (pendingCreate != PendingCreate.NONE) submitPendingCreate();
				else opener.accept(path);
				return true;
			}
		}
		return false;
	}
	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocus) return false;
		if (kind == Kind.DIRECTORY && (choosingOgg || importingSound)) return true;
		if (kind == Kind.PNG && activeInput != 0 && !Character.isISOControl(event.codepoint())) {
			String value = activeInput == 1 ? colorInput : activeInput == 2 ? widthInput : heightInput;
			if (value.length() < 9) setActiveInputValue(value + new String(Character.toChars(event.codepoint())));
			return true;
		}
		if (kind == Kind.JSON && tree != null) {
			if (tree.hasPopup()) return tree.charTyped(event);
			if (jsonSearchFocused && !Character.isISOControl(event.codepoint())) {
				if (jsonQuery.length() < 80) jsonQuery += new String(Character.toChars(event.codepoint()));
				jsonListScroll = 0; return true;
			}
			if (tree.charTyped(event)) return true;
		}
		if (kind == Kind.DIRECTORY && inputFocus && path.length() < 240 && !Character.isISOControl(event.codepoint())) {
			insertPath(new String(Character.toChars(event.codepoint()))); return true;
		}
		return false;
	}
	private void setActiveInputValue(String value) {
		if (activeInput == 1) colorInput = value;
		else if (activeInput == 2) widthInput = value;
		else if (activeInput == 3) heightInput = value;
	}
	private void insertPath(String value) {
		int start = Math.clamp(Math.min(pathCursor, pathAnchor), pathNameStart(), path.length());
		int end = Math.clamp(Math.max(pathCursor, pathAnchor), start, path.length());
		if (path.length() - (end - start) + value.length() > 240) return;
		path = path.substring(0, start) + value + path.substring(end);
		pathCursor = pathAnchor = start + value.length();
	}
	boolean mouseDragged(MouseButtonEvent event) {
		if (dragging) {
			x = Math.clamp((int) (event.x() - dragOffsetX), 0, Math.max(0, screenWidth - width));
			y = Math.clamp((int) (event.y() - dragOffsetY), 22, Math.max(22, screenHeight - height));
			return true;
		}
		if (resizeEdges != 0) {
			int dx = (int) (event.x() - resizeMouseX), dy = (int) (event.y() - resizeMouseY);
			int minWidth = kind == Kind.DIRECTORY ? 170 : kind == Kind.PNG ? 200 : 360;
			int minHeight = kind == Kind.PNG ? 430 : kind == Kind.JSON ? 280 : 230;
			if ((resizeEdges & 1) != 0) { x = Math.min(resizeX + resizeWidth - minWidth, resizeX + dx); width = resizeWidth + resizeX - x; }
			if ((resizeEdges & 2) != 0) width = Math.max(minWidth, resizeWidth + dx);
			if ((resizeEdges & 4) != 0) { y = Math.min(resizeY + resizeHeight - minHeight, resizeY + dy); height = resizeHeight + resizeY - y; }
			if ((resizeEdges & 8) != 0) height = Math.max(minHeight, resizeHeight + dy);
			return true;
		}
		return false;
	}
	boolean mouseReleased() {
		boolean changed = isInteracting(); dragging = false; resizeEdges = 0;
		if (!dock.docked() && changed) { floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height; }
		return changed;
	}
	private int edgeAt(int ex, int ey) {
		if (dock.docked()) return 0;
		int edges = 0;
		if (Math.abs(ex - x) <= 5) edges |= 1;
		if (Math.abs(ex - (x + width)) <= 5) edges |= 2;
		if (Math.abs(ey - y) <= 5) edges |= 4;
		if (Math.abs(ey - (y + height)) <= 5) edges |= 8;
		return edges;
	}
	private void beginResize(int ex, int ey) {
		resizeMouseX = ex; resizeMouseY = ey; resizeX = x; resizeY = y; resizeWidth = width; resizeHeight = height;
	}

	private static final class ResourceJsonTranslation implements JsonTreeEditor.NodeTranslation {
		@Override public String rootLabel() { return LuaUiText.text("资源包 JSON"); }
		@Override public String label(List<String> path, String key, JsonElement value) {
			return switch (key) {
				case "pack" -> LuaUiText.text("资源包信息"); case "description" -> "描述";
				case "min_format" -> "最低资源格式"; case "max_format" -> "最高资源格式";
				case "model" -> LuaUiText.text("模型"); case "parent" -> "父模型";
				case "textures" -> "纹理列表"; case "layer0" -> "第一层纹理";
				case "variants" -> "方块状态变体"; case "multipart" -> "多部分模型";
				case "sources" -> "图集来源"; case "providers" -> "字体提供器";
				case "animation" -> "动画设置"; case "frametime" -> "每帧时长";
				case "sounds" -> "声音文件列表"; case "type" -> LuaUiText.text("类型");
				default -> key;
			};
		}
		@Override public String description(List<String> path, String key, JsonElement value) {
			return switch (key) {
				case "model" -> "模型资源 ID；对应 assets/<命名空间>/models 中的 JSON 路径";
				case "textures", "layer0" -> "纹理资源 ID；省略 textures/ 前缀和 .png 后缀";
				case "variants" -> "方块状态字符串到模型的映射";
				case "sources" -> "图集收集规则，通常包含 type、source、prefix";
				case "min_format", "max_format" -> "客户端资源包兼容的格式版本";
				default -> "JSON 路径：/" + String.join("/", path);
			};
		}
		@Override public String valueLabel(List<String> path, JsonElement value) {
			return value == null ? "" : value.toString();
		}
	}
}
