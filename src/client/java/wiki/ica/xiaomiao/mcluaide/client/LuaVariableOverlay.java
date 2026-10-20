package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/** IDE window for the same authorized variable snapshot used by the in-game HUD. */
final class LuaVariableOverlay {
    void renderImGui() {
        text.update(LuaDebugHud.displayRows());
        NativeUi.action("复制全部",()->NativeUi.copy(text.lines().stream().map(line->line.text()).collect(java.util.stream.Collectors.joining("\n"))));
        imgui.ImGui.sameLine();imgui.ImGui.textDisabled(LuaDebugHud.script());
        for(var line:text.lines()) {
            var row=line.row(); imgui.ImGui.indent(row.depth()*12);
            imgui.ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,NativeUi.rgba(LuaDebugHud.valueColor(row.type())));
            imgui.ImGui.selectable(row.text()+(row.value().isEmpty()?"":" = "+row.value()));
            if(imgui.ImGui.beginPopupContextItem()) { NativeUi.action("复制",()->NativeUi.copy(row.text()+" = "+row.value())); imgui.ImGui.endPopup(); }
            imgui.ImGui.popStyleColor();imgui.ImGui.unindent(row.depth()*12);
        }
        if(text.lines().isEmpty())imgui.ImGui.textWrapped(LuaDebugHud.status());
    }
	private static final int TITLE_HEIGHT = 28;
	private static final int ROW_HEIGHT = 14;
	private static final int MIN_WIDTH = 190;
	private static final int MIN_HEIGHT = 120;
	private static final int RESIZE_MARGIN = 6;
	private final Font font;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();
	private int screenWidth;
	private int screenHeight;
	private int x = -1;
	private int y = 40;
	private int width = -1;
	private int height = -1;
	private int floatingX = -1;
	private int floatingY = 40;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private int scroll;
	private int resizeEdges;
	private int resizeStartX;
	private int resizeStartY;
	private int resizeStartWidth;
	private int resizeStartHeight;
	private double startMouseX;
	private double startMouseY;
	private double dragOffsetX;
	private double dragOffsetY;
	private boolean open;
	private boolean dragging;
	private boolean layoutLocked;
	private DockSide dockSide = DockSide.FLOATING;
	private float progress;
	private final LuaVariableText text = new LuaVariableText();

	LuaVariableOverlay(Font font) { this.font = font; }

	void resize(int screenWidth, int screenHeight) {
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		ensureGeometry();
	}

	void open() {
		open = true;
		LuaDebugHud.setIdeVariablesEnabled(true);
	}

	void close() {
		clearSelection();
		open = false;
		dragging = false;
		resizeEdges = 0;
		LuaDebugHud.setIdeVariablesEnabled(false);
	}

	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F || open; }
	boolean isInteracting() { return dragging || resizeEdges != 0 || text.selecting(); }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }


	WindowGeometry captureGeometry() {
		if (!dockSide.docked()) return boundsAnimation.destination(x, y, width, height);
		return new WindowGeometry(dockSide.docked() && floatingWidth > 0 ? floatingX : x,
				dockSide.docked() && floatingHeight > 0 ? floatingY : y,
				dockSide.docked() && floatingWidth > 0 ? floatingWidth : width,
				dockSide.docked() && floatingHeight > 0 ? floatingHeight : height);
	}

	void restoreGeometry(WindowGeometry geometry) {
		if (geometry == null) return;
		x = geometry.x(); y = geometry.y();
		width = Math.min(geometry.width(), Math.max(1, screenWidth));
		height = Math.min(geometry.height(), Math.max(1, screenHeight));
		ensureGeometry();
		x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
		y = Math.clamp(y, 0, Math.max(0, screenHeight - height));
		floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height;
		boundsAnimation.snapTo(x, y, width, height);
	}

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		if (!dockSide.docked() && side.docked()) {
			floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height;
		}
		DockSide previous = dockSide;
		dockSide = side;
		resizeEdges = 0;
		if (previous.docked() && !side.docked()) {
			x = floatingX; y = floatingY; width = floatingWidth; height = floatingHeight;
			ensureGeometry();
			boundsAnimation.snapTo(x, y, width, height);
		}
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (WindowBoundsAnimation.isImmediate()) {
			this.x = x; this.y = y; this.width = width; this.height = height; progress = 1;
			boundsAnimation.snapTo(x,y,width,height); return;
		}
		if (dockSide.docked()) boundsAnimation.animateTo(this.x, this.y, this.width, this.height,
				x, y, width, height);
	}

	void tick() {
		float target = open ? 1.0F : 0.0F;
		progress += (target - progress) * 0.28F;
		if (Math.abs(target - progress) < 0.01F) progress = target;
		if (!isInteracting()) {
			WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(x, y, width, height);
			x = bounds.x(); y = bounds.y(); width = bounds.width(); height = bounds.height();
		}
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isVisible()) return;
		ensureGeometry();
		int left = panelX();
		int right = left + width;
		int bottom = y + height;
		graphics.fill(left - 4, y + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(left, y, right, bottom, 0xFE101821);
		graphics.outline(left, y, width, height, 0xFF657F9C);
		graphics.fill(left + 1, y + 1, right - 1, y + TITLE_HEIGHT, 0xFF223448);
		graphics.text(font, font.plainSubstrByWidth(LuaUiText.text("◇  Lua 变量显示", "◇  Lua Variables"), width - 82), left + 9, y + 9, 0xFFE4F0FF);
		boolean copyHover = inside(mouseX, mouseY, right - 53, y + 4, right - 28, y + 24);
		graphics.fill(right - 53, y + 4, right - 28, y + 24, copyHover ? 0xFF52768F : 0xFF385067);
		graphics.centeredText(font, LuaUiText.text("复制"), right - 40, y + 10, 0xFFFFFFFF);
		if (copyHover) graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal(LuaUiText.text("复制全部变量（含未显示的行和被截断的值）"))), mouseX, mouseY);
		boolean closeHover = inside(mouseX, mouseY, right - 25, y + 4, right - 5, y + 24);
		graphics.fill(right - 25, y + 4, right - 5, y + 24, closeHover ? 0xFFB34851 : 0xFF385067);
		graphics.centeredText(font, "×", right - 15, y + 10, 0xFFFFFFFF);

		String script = LuaDebugHud.script();
		text.update(LuaDebugHud.displayRows());
		var rows = text.lines();
		String summary = rows.isEmpty() ? LuaDebugHud.status()
				: script + " · " + LuaDebugHud.variableCount() + " 个变量"
				+ (LuaDebugHud.omittedCount() > 0 ? "（另有 " + LuaDebugHud.omittedCount() + " 个）" : "");
		graphics.text(font, font.plainSubstrByWidth(summary, Math.max(1, width - 18)),
				left + 9, y + TITLE_HEIGHT + 7, 0xFF9CB1C8);
		int contentTop = y + TITLE_HEIGHT + 22;
		int contentBottom = bottom - 22;
		int visible = Math.max(1, (contentBottom - contentTop) / ROW_HEIGHT);
		scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
		graphics.enableScissor(left + 5, contentTop, right - 5, contentBottom);
		if (rows.isEmpty()) {
			graphics.text(font, font.plainSubstrByWidth(LuaDebugHud.status(), Math.max(1, width - 20)),
					left + 10, contentTop + 4, 0xFFFFCB82);
		} else {
			for (int index = scroll; index < Math.min(rows.size(), scroll + visible); index++) {
				var line = rows.get(index); LuaDebugHud.DisplayRow row = line.row();
				int rowY = contentTop + (index - scroll) * ROW_HEIGHT;
				int indent = Math.min(row.depth(), 8) * 12;
				if (text.hasSelection() && text.end() > line.start() && text.start() < line.end()) {
					int a = Math.clamp(text.start() - line.textStart(), 0, line.text().length());
					int b = Math.clamp(text.end() - line.textStart(), a, line.text().length());
					int sx = left + 10 + indent + font.width(line.text().substring(0, a));
					int ex = left + 10 + indent + font.width(line.text().substring(0, b));
					if (text.start() < line.textStart()) sx = left + 10;
					graphics.fill(sx, rowY + 1, Math.max(sx + 2, ex), rowY + ROW_HEIGHT, 0x995375A1);
				}
				LuaDebugHud.renderRow(graphics, font, row, left + 10 + indent, rowY + 3,
						width - 27 - indent,
						row.depth() == 0 ? 0xFFAFCBFF : row.branch() ? 0xFFE5EEF8 : 0xFFC8D9EA);
			}
		}
		graphics.disableScissor();
		graphics.text(font, font.plainSubstrByWidth(text.hasSelection() || text.selecting()
				? LuaUiText.text("选择已锁定 · Ctrl+C复制 · Esc恢复实时", "Snapshot locked · Ctrl+C Copy · Esc Resume")
				: LuaUiText.text("拖动选字 · 双击选行 · Ctrl+A全选", "Drag to Select · Double-click Row · Ctrl+A All"), Math.max(1, width - 18)), left + 9, bottom - 15, 0xFF9CB1C8);
		if (rows.size() > visible) {
			int trackTop = contentTop + 2;
			int trackHeight = Math.max(1, contentBottom - trackTop - 2);
			int thumbHeight = Math.max(12, trackHeight * visible / rows.size());
			int thumbTop = trackTop + (trackHeight - thumbHeight) * scroll / Math.max(1, rows.size() - visible);
			graphics.fill(right - 6, trackTop, right - 4, trackTop + trackHeight, 0xFF293A4C);
			graphics.fill(right - 7, thumbTop, right - 3, thumbTop + thumbHeight, 0xFF82B6E7);
		}
		int edges = resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY);
		int resizeColor = 0xFF97C8F7;
		if ((edges & 1) != 0) graphics.fill(left - 1, y, left + 2, bottom, resizeColor);
		if ((edges & 2) != 0) graphics.fill(right - 2, y, right + 1, bottom, resizeColor);
		if ((edges & 4) != 0) graphics.fill(left, y - 1, right, y + 2, resizeColor);
		if ((edges & 8) != 0) graphics.fill(left, bottom - 2, right, bottom + 1, resizeColor);
	}

	boolean contains(double mouseX, double mouseY) {
		return isVisible() && inside(mouseX, mouseY, panelX(), y, panelX() + width, y + height);
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !contains(mouseX, mouseY)) return false;
		scrollRows(-Math.max(1, (int) Math.round(Math.abs(amount) * 3)) * (amount > 0 ? 1 : -1));
		return true;
	}

	void scrollRows(int delta) {
		text.update(LuaDebugHud.displayRows());
		int visible = Math.max(1, (height - TITLE_HEIGHT - 44) / ROW_HEIGHT);
		int max = Math.max(0, text.lines().size() - visible);
		scroll = Math.clamp(scroll + delta, 0, max);
	}

	boolean mouseClicked(MouseButtonEvent event) {
		return mouseClicked(event, false);
	}
	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!open || progress < 0.5F) return false;
		int left = panelX();
		int right = left + width;
		if (event.button() == 0 && inside(event.x(), event.y(), right - 53, y + 4, right - 28, y + 24)) {
			copy(true); return true;
		}
		if (inside(event.x(), event.y(), right - 25, y + 4, right - 5, y + 24)) {
			close(); return true;
		}
		if (event.button() == 0) {
			int edges = resizeEdgesAt(event.x(), event.y());
			if (edges != 0) {
				resizeEdges = edges;
				startMouseX = event.x(); startMouseY = event.y();
				resizeStartX = left; resizeStartY = y;
				resizeStartWidth = width; resizeStartHeight = height;
				return true;
			}
		}
		if (!contains(event.x(), event.y())) return false;
		if (event.button() == 0 && event.y() <= y + TITLE_HEIGHT) {
			if (dockSide.docked() && layoutLocked) return true;
			dragging = true;
			dragOffsetX = event.x() - left;
			dragOffsetY = event.y() - y;
		} else if (event.button() == 0 && event.y() >= y + TITLE_HEIGHT + 22 && event.y() < y + height - 22) {
			text.update(LuaDebugHud.displayRows());
			int row = scroll + Math.max(0, (int) (event.y() - y - TITLE_HEIGHT - 22) / ROW_HEIGHT);
			if (doubleClick) text.selectLine(row);
			else text.begin(offsetAt(event.x(), event.y()));
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (resizeEdges != 0) {
			int dx = (int) Math.round(event.x() - startMouseX);
			int dy = (int) Math.round(event.y() - startMouseY);
			int left = resizeStartX;
			int right = left + resizeStartWidth;
			int top = resizeStartY;
			int bottom = top + resizeStartHeight;
			int minimumWidth = Math.min(MIN_WIDTH, screenWidth);
			int minimumHeight = Math.min(MIN_HEIGHT, screenHeight);
			if ((resizeEdges & 1) != 0) left = Math.max(0, Math.min(right - minimumWidth, left + dx));
			if ((resizeEdges & 2) != 0) right = Math.min(screenWidth, Math.max(left + minimumWidth, right + dx));
			if ((resizeEdges & 4) != 0) top = Math.max(0, Math.min(bottom - minimumHeight, top + dy));
			if ((resizeEdges & 8) != 0) bottom = Math.min(screenHeight, Math.max(top + minimumHeight, bottom + dy));
			x = left; y = top; width = right - left; height = bottom - top;
			boundsAnimation.snapTo(x, y, width, height);
			return true;
		}
		if (text.selecting()) {
			if (event.y() < y + TITLE_HEIGHT + 22) scrollRows(-1);
			else if (event.y() >= y + height - 22) scrollRows(1);
			text.drag(offsetAt(event.x(), event.y())); return true;
		}
		if (!dragging) return false;
		if (dockSide.docked()) {
			setDockSide(DockSide.FLOATING);
			dragOffsetX = Math.min(Math.max(18, dragOffsetX), Math.max(18, width - 18));
			dragOffsetY = 12;
		}
		x = Math.clamp((int) Math.round(event.x() - dragOffsetX), 0, Math.max(0, screenWidth - width));
		y = Math.clamp((int) Math.round(event.y() - dragOffsetY), 0, Math.max(0, screenHeight - height));
		boundsAnimation.snapTo(x, y, width, height);
		return true;
	}

	boolean mouseReleased() {
		if (!isInteracting()) return false;
		text.release();
		dragging = false;
		resizeEdges = 0;
		return true;
	}

	void clearSelection() { text.clear(); }
	boolean keyPressed(KeyEvent event) {
		if (!open) return false;
		if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			text.update(LuaDebugHud.displayRows()); text.selectAll(); return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_C) { copy(false); return true; }
		if (event.key() == InputConstants.KEY_ESCAPE && (text.hasSelection() || text.selecting())) { clearSelection(); return true; }
		return false;
	}
	private void copy(boolean all) {
		text.update(LuaDebugHud.displayRows()); String value = all ? text.source() : text.selected();
		if (!value.isEmpty()) Minecraft.getInstance().keyboardHandler.setClipboard(value);
	}
	private int offsetAt(double mx, double my) {
		int visible = Math.max(1, (height - TITLE_HEIGHT - 44) / ROW_HEIGHT);
		int row = scroll + Math.clamp((int) Math.floor((my - y - TITLE_HEIGHT - 22) / ROW_HEIGHT), 0, visible - 1);
		if (text.lines().isEmpty()) return 0;
		var line = text.lines().get(Math.min(row, text.lines().size() - 1));
		if (mx >= panelX() + width - 8) return line.end();
		return text.offsetAt(row, mx - panelX() - 10 - Math.min(line.row().depth(), 8) * 12, font::width);
	}

	private int panelX() {
		float eased = 1.0F - (1.0F - progress) * (1.0F - progress);
		return Math.round(screenWidth + (x - screenWidth) * eased);
	}

	private int resizeEdgesAt(double mouseX, double mouseY) {
		if (dockSide.docked()) return 0;
		int left = panelX();
		int right = left + width;
		int bottom = y + height;
		if (!inside(mouseX, mouseY, left - RESIZE_MARGIN, y - RESIZE_MARGIN,
				right + RESIZE_MARGIN, bottom + RESIZE_MARGIN)) return 0;
		int edges = 0;
		if (Math.abs(mouseX - left) <= RESIZE_MARGIN) edges |= 1;
		if (Math.abs(mouseX - right) <= RESIZE_MARGIN) edges |= 2;
		if (Math.abs(mouseY - y) <= RESIZE_MARGIN) edges |= 4;
		if (Math.abs(mouseY - bottom) <= RESIZE_MARGIN) edges |= 8;
		return edges;
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0 || dockSide.docked()) return;
		int minWidth = Math.min(MIN_WIDTH, screenWidth);
		int minHeight = Math.min(MIN_HEIGHT, screenHeight);
		if (width < 0) width = Math.min(screenWidth, Math.max(minWidth, (int) (screenWidth * 0.33F)));
		if (height < 0) height = Math.min(screenHeight, Math.max(minHeight, (int) (screenHeight * 0.48F)));
		width = Math.clamp(width, minWidth, screenWidth);
		height = Math.clamp(height, minHeight, screenHeight);
		if (x < 0) x = Math.max(0, screenWidth - width - 20);
		x = Math.clamp(x, 0, screenWidth - width);
		y = Math.clamp(y, 0, screenHeight - height);
	}

	private static boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
		return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
	}
}
