package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;
import java.util.function.Consumer;

/** Animated, draggable and resizable Lua script manager used inside the IDE. */
final class LuaFileOverlay {
	private static final int TITLE_HEIGHT = 28;
	private static final int ROW_HEIGHT = 24;
	private static final int RESIZE_MARGIN = 6;
	private static final int MIN_WIDTH = 430;
	private static final int MIN_HEIGHT = 260;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;

	private final Minecraft minecraft;
	private final Font font;
	private final String currentScript;
	private final Consumer<String> opener;
	private final Consumer<String> externalStatus;
	private List<String> scripts = List.of();
	private int refreshRevision;
	private String pendingSelection;
	private String name = "";
	private String status = "输入名称新建，或读取已有 Lua 脚本";
	private int cursor;
	private int anchor;
	private int scrollRow;
	private int selectedRow;
	private boolean inputFocused = true;
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
		this.minecraft = minecraft;
		this.font = font;
		this.currentScript = currentScript;
		this.opener = opener;
		this.externalStatus = externalStatus;
	}

	void resize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
		ensureGeometry();
	}

	void open() {
		open = true;
		inputFocused = true;
		keyboardFocused = true;
		refreshScripts();
	}

	void close() {
		open = false;
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
		return isVisible() && pointInside(mouseX, mouseY);
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

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		DockSide previous = dockSide;
		if (!dockSide.docked() && side.docked()) rememberFloatingGeometry();
		dockSide = side;
		if (previous.docked() && !side.docked()) restoreFloatingGeometry();
		resizeEdges = 0;
	}

	void setDockBounds(int x, int y, int width, int height) {
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
		graphics.text(font, "▣  文件 · Lua 脚本", x + 10, windowY + 9, 0xFFFFC47D);
		graphics.text(font, scripts.size() + " 个脚本", x + 142, windowY + 9, 0xFFB89C7A);
		boolean closeHovered = inside(mouseX, mouseY, right - 26, windowY + 4, right - 6, windowY + 24);
		graphics.fill(right - 26, windowY + 4, right - 6, windowY + 24,
				closeHovered ? 0xFFB83A46 : 0xFF5A4030);
		graphics.centeredText(font, "×", right - 16, windowY + 10, 0xFFFFFFFF);

		int inputLeft = x + 10;
		int inputTop = windowY + 35;
		int createLeft = right - 78;
		int inputRight = createLeft - 7;
		graphics.fill(inputLeft, inputTop, inputRight, inputTop + 20, 0xFF090D12);
		graphics.outline(inputLeft, inputTop, inputRight - inputLeft, 20,
				inputFocused ? 0xFFE0A45E : 0xFF54483D);
		renderNameInput(graphics, inputLeft + 7, inputTop + 6, inputRight - 7);
		boolean createHovered = inside(mouseX, mouseY, createLeft, inputTop, right - 10, inputTop + 20);
		graphics.fill(createLeft, inputTop, right - 10, inputTop + 20,
				createHovered ? 0xFFA8652E : 0xFF704A2C);
		graphics.centeredText(font, "新建", (createLeft + right - 10) / 2, inputTop + 6, 0xFFFFFFFF);

		graphics.text(font, font.plainSubstrByWidth(status, windowWidth - 24), x + 12, windowY + 63,
				status.contains("失败") || status.contains("不能") ? 0xFFFF7777 : 0xFFAFA59A);
		int listTop = windowY + 78;
		int listBottom = bottom - 28;
		graphics.fill(x + 10, listTop, right - 10, listBottom, 0xFF080B0F);
		graphics.outline(x + 10, listTop, windowWidth - 20, listBottom - listTop, 0xFF4E443A);
		graphics.enableScissor(x + 11, listTop + 1, right - 11, listBottom - 1);
		int visibleRows = visibleRows();
		int hoveredRow = rowAt(mouseX, mouseY);
		for (int visual = 0; visual < visibleRows; visual++) {
			int index = scrollRow + visual;
			if (index >= scripts.size()) break;
			int y = listTop + 2 + visual * ROW_HEIGHT;
			boolean active = index == selectedRow;
			if (active || index == hoveredRow) {
				graphics.fill(x + 12, y - 1, right - 12, y + 21,
						index == hoveredRow ? 0xFF493426 : 0xFF32271F);
			}
			String script = scripts.get(index);
			int removeLeft = right - 63;
			int readLeft = removeLeft - 59;
			int color = script.equals(currentScript) ? 0xFFFFC47D : 0xFFE5E8EC;
			graphics.text(font, font.plainSubstrByWidth(script + ".lua", Math.max(30, readLeft - x - 30)),
					x + 18, y + 6, color);
			renderRowButton(graphics, mouseX, mouseY, readLeft, y + 1, 54, "读取", false);
			renderRowButton(graphics, mouseX, mouseY, removeLeft, y + 1, 51, "移除", true);
		}
		graphics.disableScissor();
		if (scripts.isEmpty()) {
			graphics.centeredText(font, "暂无 Lua 脚本", (x + right) / 2, listTop + 14, 0xFF77818C);
		}
		graphics.text(font, "↑↓ 选择 · Enter 读取 · 滚轮浏览 · 当前脚本为橙色",
				x + 12, bottom - 18, 0xFF8C8176);
		renderResizeEdges(graphics, mouseX, mouseY, x, right, bottom);
	}

	private void renderNameInput(GuiGraphicsExtractor graphics, int left, int y, int right) {
		if (name.isEmpty()) {
			graphics.text(font, "脚本名称（不需要 .lua）", left, y, 0xFF6F7780);
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

	private void renderRowButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int width, String label, boolean destructive) {
		boolean hovered = inside(mouseX, mouseY, left, top, left + width, top + 19);
		int color = destructive ? (hovered ? 0xFFB83A46 : 0xFF59343A)
				: (hovered ? 0xFF9A642F : 0xFF503C2A);
		graphics.fill(left, top, left + width, top + 19, color);
		graphics.centeredText(font, label, left + width / 2, top + 5, 0xFFFFFFFF);
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (dockSide.docked()) return true;
			close();
			return true;
		}
		if (inputFocused) {
			if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
				anchor = 0;
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
				else if (cursor > 0) {
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
				cursor = event.key() == InputConstants.KEY_HOME ? 0 : name.length();
				if (!event.hasShiftDown()) anchor = cursor;
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				createScript();
				return true;
			}
		}
		if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
			selectedRow = Math.max(0, Math.min(Math.max(0, scripts.size() - 1),
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
		if (inputFocused && !Character.isISOControl(codepoint) && name.codePointCount(0, name.length()) < 48) {
			replaceSelection(new String(Character.toChars(codepoint)));
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !pointInside(mouseX, mouseY)) return false;
		scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.round(amount * 3)));
		return true;
	}

	boolean mouseClicked(MouseButtonEvent event) {
		if (!open || progress < 0.5F) return false;
		int x = panelX();
		int right = x + windowWidth;
		if (inside(event.x(), event.y(), right - 26, windowY + 4, right - 6, windowY + 24)) {
			close();
			return true;
		}
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
		if (!pointInside(event.x(), event.y())) return false;
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
		int inputTop = windowY + 35;
		int createLeft = right - 78;
		int inputRight = createLeft - 7;
		if (inside(event.x(), event.y(), inputLeft, inputTop, inputRight, inputTop + 20)) {
			inputFocused = true;
			cursor = nameOffsetAt(event.x(), inputLeft + 7, inputRight - 7);
			anchor = cursor;
			return true;
		}
		inputFocused = false;
		if (inside(event.x(), event.y(), createLeft, inputTop, right - 10, inputTop + 20)) {
			createScript();
			return true;
		}
		int row = rowAt(event.x(), event.y());
		if (row >= 0 && row < scripts.size()) {
			selectedRow = row;
			int removeLeft = right - 63;
			int readLeft = removeLeft - 59;
			if (event.x() >= removeLeft) deleteSelected();
			else if (event.x() >= readLeft) openSelected();
			return true;
		}
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
		String requested = name.trim();
		if (!ScriptManager.isValidName(requested)) {
			setStatus("不能新建：名称只能包含字母、数字、下划线和连字符，长度 1-48");
			return;
		}
		if (scripts.contains(requested)) {
			setStatus("不能新建：" + requested + ".lua 已存在");
			return;
		}
		setStatus("正在服务器新建 " + requested + ".lua…");
		ScriptManager.save(requested, "-- " + requested + ".lua\nprint(\"Hello from " + requested + "!\")\n")
				.whenComplete((ignored, failure) -> minecraft.execute(() -> {
					if (failure != null) {
						setStatus("新建失败：" + ScriptManager.message(failure));
						return;
					}
					name = "";
					cursor = 0;
					anchor = 0;
					pendingSelection = requested;
					setStatus("已新建 " + requested + ".lua；点击读取进入编辑");
					refreshScripts();
				}));
	}

	private void openSelected() {
		if (selectedRow < 0 || selectedRow >= scripts.size()) return;
		String selected = scripts.get(selectedRow);
		setStatus("正在读取 " + selected + ".lua");
		opener.accept(selected);
	}

	private void deleteSelected() {
		if (selectedRow < 0 || selectedRow >= scripts.size()) return;
		String selected = scripts.get(selectedRow);
		setStatus("正在从服务器移除 " + selected + ".lua…");
		ScriptManager.delete(selected).whenComplete((deleted, failure) -> minecraft.execute(() -> {
			if (failure != null) {
				setStatus("移除失败：" + ScriptManager.message(failure));
				return;
			}
			setStatus(deleted ? "已移除 " + selected + ".lua"
					+ (selected.equals(currentScript) ? "；当前编辑器再次保存会重新创建它" : "")
					: "脚本不存在：" + selected + ".lua");
			refreshScripts();
		}));
	}

	private void refreshScripts() {
		int revision = ++refreshRevision;
		ScriptManager.listScripts().whenComplete((result, failure) -> minecraft.execute(() -> {
			if (revision != refreshRevision) return;
			if (failure != null) {
				scripts = List.of();
				setStatus("读取目录失败：" + ScriptManager.message(failure));
				return;
			}
			scripts = result;
			if (pendingSelection != null) {
				selectedRow = Math.max(0, scripts.indexOf(pendingSelection));
				pendingSelection = null;
			} else {
				selectedRow = Math.max(0, Math.min(selectedRow, scripts.size() - 1));
			}
			scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
			ensureSelectedVisible();
		}));
	}

	private void setStatus(String value) {
		status = value;
		externalStatus.accept(value);
	}

	private int rowAt(double mouseX, double mouseY) {
		int x = panelX();
		int listTop = windowY + 78;
		int listBottom = windowY + windowHeight - 28;
		if (!inside(mouseX, mouseY, x + 10, listTop, x + windowWidth - 10, listBottom)) return -1;
		return scrollRow + Math.max(0, (int) (mouseY - listTop - 2) / ROW_HEIGHT);
	}

	private int visibleRows() {
		return Math.max(1, (windowHeight - 108) / ROW_HEIGHT);
	}

	private int maxScroll() {
		return Math.max(0, scripts.size() - visibleRows());
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
		int start = Math.min(cursor, anchor);
		int end = Math.max(cursor, anchor);
		String clean = replacement == null ? "" : replacement;
		String next = name.substring(0, start) + clean + name.substring(end);
		if (next.codePointCount(0, next.length()) > 48) {
			int allowed = Math.max(0, 48 - name.substring(0, start).codePointCount(0, start)
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

	private void moveCursor(int direction, boolean selecting) {
		if (!selecting && hasSelection()) {
			cursor = direction < 0 ? Math.min(cursor, anchor) : Math.max(cursor, anchor);
			anchor = cursor;
			return;
		}
		if (direction < 0 && cursor > 0) cursor = name.offsetByCodePoints(cursor, -1);
		if (direction > 0 && cursor < name.length()) cursor = name.offsetByCodePoints(cursor, 1);
		if (!selecting) anchor = cursor;
	}

	private int visibleNameStart(int maxWidth) {
		int start = cursor;
		while (start > 0) {
			int previous = name.offsetByCodePoints(start, -1);
			if (font.width(name.substring(previous, cursor)) > maxWidth) break;
			start = previous;
		}
		return start;
	}

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
