package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The only interactive mode for game HUDs; never changes switches, camera state or script controls. */
final class LuaHudLayoutScreen extends Screen {
	private final Screen previous;
	private final LuaHudLayout layout = LuaHudLayout.current();
	private final List<LuaHudLayout.Panel> order = new ArrayList<>(Arrays.asList(LuaHudLayout.Panel.values()));
	private LuaHudLayout.Panel selected = LuaHudLayout.Panel.VARIABLES, dragging;
	private LuaHudLayout.Rect dragStart;
	private int dragEdges;
	private double dragX, dragY;
	private boolean dirty;
	private LuaHudLayout.Panel contextPanel;
	private LuaHudLayout.Rect contextBounds;
	private TransparencySlider transparencySlider;
	private Button transparencyReset, contextClose;
	private String notice = "拖动移动 · 边框缩放 · 右键设置背景透明度 · Tab 切换 · 方向键微调（Ctrl 缩放）";
	LuaHudLayoutScreen(Screen previous) { super(Component.literal(LuaUiText.text("调试浮窗位置调整"))); this.previous = previous; }
	@Override protected void init() {
		dragging = null;
		closeContext();
		int doneWidth = Math.min(66, Math.max(20, width / 5)), resetWidth = Math.min(82, Math.max(20, width / 4));
		addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("恢复默认")), button -> {
			layout.reset(); dirty = true; dragging = null; closeContext();
		}).bounds(width - doneWidth - resetWidth - 14, 5, resetWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("完成")), button -> onClose())
				.bounds(width - doneWidth - 8, 5, doneWidth, 20).build());
	}
	@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
		// No blur: the game stays visible so the layout can be judged against the HUD/world.
		graphics.fill(0, 0, width, height, 0x42080D16);
	}
	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
		for (LuaHudLayout.Panel panel : order) {
			var r = layout.bounds(panel, width, height);
			switch (panel) {
				case VARIABLES -> LuaDebugHud.renderPanel(graphics, r, true);
				case BREAKPOINTS -> LuaBreakpointClient.renderPanel(graphics, r, true);
				case LOGS -> LuaLogHud.renderPanel(graphics, r, true);
				case CAMERA -> LuaDebuggerCamera.renderPanel(graphics, r);
			}
			graphics.nextStratum();
			graphics.outline(r.x(), r.y(), r.width(), r.height(), panel == selected ? 0xFFFFD175 : 0xFF71839D);
			int edges = panel == dragging ? dragEdges : panel == hit(mouseX, mouseY) ? LuaHudLayout.edges(r, mouseX, mouseY) : 0;
			ResizeBorderHint.render(graphics, r.x(), r.y(), r.right(), r.bottom(), edges, 0xFFFFD175);
			String label = panel.label + " · " + r.width() + " × " + r.height();
			graphics.text(font, font.plainSubstrByWidth(label, r.width() - 12), r.x() + 6, r.bottom() - 11, 0xFFFFD175);
		}
		graphics.nextStratum();
		graphics.fill(0, 0, width, 38, 0xF019202D);
		graphics.text(font, font.plainSubstrByWidth(title.getString(), Math.max(0, width - 175)), 8, 9, 0xFFF1F3FF);
		graphics.text(font, font.plainSubstrByWidth(notice, Math.max(0, width - 16)), 8, 26, 0xFFCFD7E8);
		if (contextPanel != null) {
			var r = contextBounds;
			graphics.nextStratum();
			graphics.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF202938);
			graphics.outline(r.x(), r.y(), r.width(), r.height(), 0xFF8FA7FF);
			graphics.text(font, font.plainSubstrByWidth(contextPanel.label + " · 背景设置", r.width() - 16), r.x() + 8, r.y() + 8, 0xFFE8ECFF);
			graphics.text(font, LuaUiText.text("0% 不透明 · 100% 全透明 · 文字不变"), r.x() + 8, r.y() + 23, 0xFFADBBD0);
			graphics.text(font, LuaUiText.text("← → 微调 · Shift 加速 · Esc 关闭菜单"), r.x() + 8, r.y() + 96, 0xFFADBBD0);
			graphics.nextStratum();
		}
		super.extractRenderState(graphics, mouseX, mouseY, tick);
	}
	private final class TransparencySlider extends AbstractSliderButton {
		private final LuaHudLayout.Panel panel;
		TransparencySlider(LuaHudLayout.Panel panel, LuaHudLayout.Rect r) {
			super(r.x() + 10, r.y() + 39, r.width() - 20, 20, Component.empty(), layout.transparency(panel) / 100D);
			this.panel = panel; updateMessage();
		}
		@Override protected void updateMessage() { setMessage(Component.literal(LuaUiText.text("背景透明度：") + Math.round(value * 100) + "%")); }
		@Override protected void applyValue() { layout.setTransparency(panel, (int) Math.round(value * 100)); dirty = true; }
		void restoreDefault() { value = layout.transparency(panel) / 100D; updateMessage(); }
		@Override public boolean keyPressed(KeyEvent event) {
			int direction = event.key() == InputConstants.KEY_LEFT ? -1 : event.key() == InputConstants.KEY_RIGHT ? 1 : 0;
			if (direction != 0) { setValue((Math.round(value * 100) + direction * (event.hasShiftDown() ? 10 : 1)) / 100D); return true; }
			return super.keyPressed(event);
		}
	}
	private void openContext(LuaHudLayout.Panel panel, double x, double y) {
		closeContext(); dragging = null; select(panel); contextPanel = panel;
		int pw = Math.min(248, width - 12), ph = 112;
		contextBounds = new LuaHudLayout.Rect(Math.clamp((int) x, 6, width - pw - 6),
				Math.clamp((int) y, Math.min(40, height - ph - 6), height - ph - 6), pw, ph);
		transparencySlider = addRenderableWidget(new TransparencySlider(panel, contextBounds));
		transparencyReset = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("恢复默认透明度")), button -> {
			layout.resetTransparency(panel); transparencySlider.restoreDefault(); dirty = true;
		}).bounds(contextBounds.x() + 10, contextBounds.y() + 67, pw - 88, 20).build());
		contextClose = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("关闭")), button -> closeContext())
				.bounds(contextBounds.right() - 70, contextBounds.y() + 67, 60, 20).build());
		setFocused(transparencySlider);
	}
	private void closeContext() {
		if (getFocused() == transparencySlider || getFocused() == transparencyReset || getFocused() == contextClose) setFocused(null);
		if (transparencySlider != null) removeWidget(transparencySlider);
		if (transparencyReset != null) removeWidget(transparencyReset);
		if (contextClose != null) removeWidget(contextClose);
		contextPanel = null; contextBounds = null; transparencySlider = null; transparencyReset = null; contextClose = null;
		setDragging(false);
	}
	private LuaHudLayout.Panel hit(double x, double y) {
		for (int i = order.size() - 1; i >= 0; i--) {
			var panel = order.get(i); var r = layout.bounds(panel, width, height);
			if (r.contains(x, y) || LuaHudLayout.edges(r, x, y) != 0) return panel;
		}
		return null;
	}
	private void select(LuaHudLayout.Panel panel) { selected = panel; order.remove(panel); order.add(panel); }
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (contextPanel != null) {
			if (contextBounds.contains(event.x(), event.y())) {
				super.mouseClicked(event, doubleClick);
				if (contextPanel == null) setFocused(null);
				return true;
			}
			closeContext();
			if (event.button() != 1) return true; // Closing the menu must not start a window drag or click a toolbar button.
		}
		if (event.button() == 1) {
			var panel = hit(event.x(), event.y());
			if (panel != null) { openContext(panel, event.x(), event.y()); return true; }
			return false;
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0) return false;
		var panel = hit(event.x(), event.y()); if (panel == null) return false;
		select(panel); dragging = panel; dragStart = layout.bounds(panel, width, height);
		dragEdges = LuaHudLayout.edges(dragStart, event.x(), event.y()); dragX = event.x(); dragY = event.y();
		return true;
	}
	@Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (contextPanel != null) return super.mouseDragged(event, dx, dy);
		if (dragging == null || event.button() != 0) return false;
		layout.set(dragging, LuaHudLayout.drag(dragging, dragStart, dragEdges,
				(int) Math.round(event.x() - dragX), (int) Math.round(event.y() - dragY), width, height), width, height);
		dirty = true; return true;
	}
	@Override public boolean mouseReleased(MouseButtonEvent event) {
		if (contextPanel != null) return super.mouseReleased(event);
		if (event.button() == 0 && dragging != null) { dragging = null; return true; }
		return super.mouseReleased(event);
	}
	@Override public boolean keyPressed(KeyEvent event) {
		if (contextPanel != null && event.key() != InputConstants.KEY_F12) {
			if (event.key() == InputConstants.KEY_ESCAPE) { closeContext(); return true; }
			if (event.key() == InputConstants.KEY_TAB) {
				var controls = List.of(transparencySlider, transparencyReset, contextClose);
				int index = controls.indexOf(getFocused());
				setFocused(controls.get(Math.floorMod(index + (event.hasShiftDown() ? -1 : 1), controls.size())));
				return true;
			}
			return super.keyPressed(event);
		}
		if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_F12) { onClose(); return true; }
		if (event.key() == InputConstants.KEY_TAB) {
			var panels = LuaHudLayout.Panel.values();
			select(panels[Math.floorMod(selected.ordinal() + (event.hasShiftDown() ? -1 : 1), panels.length)]);
			setFocused(null); return true;
		}
		int dx = event.key() == InputConstants.KEY_RIGHT ? 1 : event.key() == InputConstants.KEY_LEFT ? -1 : 0;
		int dy = event.key() == InputConstants.KEY_DOWN ? 1 : event.key() == InputConstants.KEY_UP ? -1 : 0;
		if (dx != 0 || dy != 0) {
			int step = event.hasShiftDown() ? 10 : 1;
			layout.set(selected, LuaHudLayout.drag(selected, layout.bounds(selected, width, height),
					event.hasControlDown() ? 2 | 8 : 0, dx * step, dy * step, width, height), width, height);
			dirty = true; return true;
		}
		return super.keyPressed(event);
	}
	private boolean save() {
		if (!dirty || layout.save()) { dirty = false; return true; }
		notice = "布局保存失败，请检查客户端配置目录权限后重试"; return false;
	}
	@Override public void onClose() { dragging = null; closeContext(); if (save()) minecraft.setScreen(previous); }
	@Override public void removed() { dragging = null; closeContext(); save(); super.removed(); }
	@Override public boolean isPauseScreen() { return false; }
}
