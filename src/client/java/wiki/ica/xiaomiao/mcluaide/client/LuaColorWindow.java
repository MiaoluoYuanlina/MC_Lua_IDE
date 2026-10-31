package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Dockable color content. The host owns the frame, close button, animation and layout. */
final class LuaColorWindow implements McLuaIdeWindowApi.NativeContent {
    @Override public void renderImGui() {
        float[] rgb={color.channel(0)/255f,color.channel(1)/255f,color.channel(2)/255f};
        if(imgui.ImGui.colorPicker3("##RGB",rgb,imgui.flag.ImGuiColorEditFlags.DisplayHex|imgui.flag.ImGuiColorEditFlags.PickerHueBar))color.rgb(Math.round(rgb[0]*255)<<16|Math.round(rgb[1]*255)<<8|Math.round(rgb[2]*255));
        color.format=NativeUi.combo("输出格式",color.format,List.of(LuaColorModel.Format.values()),v->LuaUiText.text(v.label));
        color.hexNumber=NativeUi.check("0xRRGGBB 数值",color.hexNumber);
        NativeUi.readOnly("##result",color.text(),45);
        NativeUi.action("插入",this::insert);imgui.ImGui.sameLine();NativeUi.action("复制",()->context.copyText(color.text()));
        if(imgui.ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.ChildWindows)&&imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter)) {
            if(insert()&&imgui.ImGui.getIO().getKeyCtrl())context.close(); // Ctrl+Enter inserts and closes
        }
        NativeUi.status(status);
    }
	static final String ID = "mc_lua_ide:color_picker";
	private enum Focus { SQUARE, HUE, RED, GREEN, BLUE, FORMAT, HEX_STYLE, INSERT, COPY }
	private record Area(Focus focus, int x, int y, int w, int h) {
		boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
	}
	private final McLuaIdeWindowApi.Context context;
	private final Function<LuaColorModel, Boolean> insertion;
	final LuaColorModel color = new LuaColorModel();
	private Focus focus = Focus.SQUARE, dragging;
	private int width = 468, height = 438, scroll;
	private String status = "方向键调色 · Tab 切换 · Enter 插入";
	LuaColorWindow(McLuaIdeWindowApi.Context context, Function<LuaColorModel, Boolean> insertion) {
		this.context = context; this.insertion = insertion;
	}
	@Override public void resize(int width, int height) { this.width = width; this.height = height; scroll = Math.clamp(scroll, 0, maxScroll()); }
	@Override public void focusChanged(boolean focused) { if (!focused) dragging = null; }
	@Override public void closed() { dragging = null; }
	private int virtualHeight() { return Math.max(320, height); }
	private int maxScroll() { return Math.max(0, virtualHeight() - height); }
	private int squareSize() { return Math.max(16, Math.min(width - 30, Math.min(240, virtualHeight() - 224))); }
	private List<Area> areas() {
		int size = squareSize(), row = size + 28;
		List<Area> areas = new ArrayList<>();
		areas.add(new Area(Focus.SQUARE, 0, 20, size, size));
		areas.add(new Area(Focus.HUE, size + 8, 20, 16, size));
		for (int i = 0; i < 3; i++) areas.add(new Area(Focus.values()[Focus.RED.ordinal() + i], 25, row + i * 24, Math.max(16, width - 75), 18));
		areas.add(new Area(Focus.FORMAT, 0, row + 76, width, 23));
		if (color.format == LuaColorModel.Format.HEX) areas.add(new Area(Focus.HEX_STYLE, 0, row + 102, width, 23));
		int by = row + 155, bw = Math.max(20, (width - 5) / 2);
		areas.add(new Area(Focus.INSERT, 0, by, bw, 23)); areas.add(new Area(Focus.COPY, bw + 5, by, bw, 23));
		return areas;
	}
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		var font = context.font(); int size = squareSize(), row = size + 28;
		graphics.pose().pushMatrix(); graphics.pose().translate(0, -scroll);
		try {
			graphics.text(font, LuaUiText.text("色相 + 饱和度 / 亮度 · RGB 全色域"), 0, 2, 0xFFAFC9C3);
			for (int y = 0; y < size; y += 3) for (int x = 0; x < size; x += 3)
				graphics.fill(x, 20 + y, Math.min(size, x + 3), 20 + Math.min(size, y + 3),
						java.awt.Color.HSBtoRGB(color.hue, x / (float) Math.max(1, size - 1), 1 - y / (float) Math.max(1, size - 1)));
			for (int y = 0; y < size; y += 2) graphics.fill(size + 8, 20 + y, size + 24, 20 + Math.min(size, y + 2),
					java.awt.Color.HSBtoRGB(y / (float) Math.max(1, size - 1), 1, 1));
			int sx = Math.round(color.saturation * (size - 1)), sy = 20 + Math.round((1 - color.brightness) * (size - 1));
			graphics.outline(sx - 3, sy - 3, 7, 7, 0xFF000000); graphics.outline(sx - 2, sy - 2, 5, 5, 0xFFFFFFFF);
			graphics.outline(size + 6, 18 + Math.round(color.hue * (size - 1)), 20, 5, 0xFFFFFFFF);
			if (width - size > 94) {
				int x = size + 34;
				graphics.fill(x, 20, width, 60, 0xFF000000 | color.rgb()); graphics.outline(x, 20, width - x, 40, 0xFFB5CEC8);
				graphics.text(font, String.format(java.util.Locale.ROOT, "#%06X", color.rgb()), x, 69, 0xFFE6FFF5);
				graphics.text(font, LuaUiText.text("RGB 三个通道"), x, 87, 0xFF9CB8AF);
			}
			for (int i = 0; i < 3; i++) {
				int y = row + i * 24, length = Math.max(16, width - 75), base = color.rgb(), shift = 16 - i * 8;
				graphics.text(font, new String[]{"R", "G", "B"}[i], 5, y + 5, 0xFFE6FFF5);
				for (int x = 0; x < length; x += 3) graphics.fill(25 + x, y, 25 + Math.min(length, x + 3), y + 18,
						0xFF000000 | (base & ~(255 << shift)) | Math.round(x * 255F / Math.max(1, length - 1)) << shift);
				int marker = 25 + Math.round(color.channel(i) * (length - 1) / 255F);
				graphics.outline(marker - 1, y - 1, 3, 20, 0xFFFFFFFF);
				graphics.text(font, Integer.toString(color.channel(i)), width - 39, y + 5, 0xFFE6FFF5);
			}
			for (Area a : areas()) {
				if (a.focus.ordinal() >= Focus.FORMAT.ordinal()) {
					graphics.fill(a.x, a.y, a.x + a.w, a.y + a.h, a.contains(mouseX, mouseY + scroll) ? 0xFF3A6C62 : 0xFF233D38);
					String label = switch (a.focus) {
						case FORMAT -> LuaUiText.text("输出格式：") + color.format.label + "  ‹ ›";
						case HEX_STYLE -> LuaUiText.text("十六进制样式：") + (color.hexNumber ? LuaUiText.text("0xRRGGBB 数值") : LuaUiText.text("#RRGGBB 颜色字符串")) + "  ‹ ›";
						case INSERT -> LuaUiText.text("插入 · Enter"); case COPY -> LuaUiText.text("复制"); default -> "";
					};
					graphics.text(font, font.plainSubstrByWidth(label, a.w - 12), a.x + 6, a.y + 7, 0xFFE6FFF5);
				}
				if (a.focus == focus) graphics.outline(a.x, a.y, a.w, a.h, 0xFFFFD175);
			}
			graphics.text(font, font.plainSubstrByWidth(color.text(), width - 8), 4, row + 134, 0xFFFFFFFF);
			graphics.text(font, font.plainSubstrByWidth(status, width - 8), 4, row + 185, 0xFFAFC9C3);
		} finally { graphics.pose().popMatrix(); }
	}
	private void keepFocusVisible() {
		for (Area a : areas()) if (a.focus == focus) {
			int targetBottom = focus == Focus.INSERT || focus == Focus.COPY ? virtualHeight() : a.y + a.h;
			if (a.y < scroll) scroll = a.y;
			else if (targetBottom > scroll + height) scroll = targetBottom - height;
			break;
		}
		scroll = Math.clamp(scroll, 0, maxScroll());
	}
	private void format(int delta) { var values = LuaColorModel.Format.values(); color.format = values[Math.floorMod(color.format.ordinal() + delta, values.length)]; }
	private void activate(Focus target) {
		switch (target) {
			case FORMAT -> format(1); case HEX_STYLE -> color.hexNumber = !color.hexNumber;
			case COPY -> { context.copyText(color.text()); status = LuaUiText.text("已复制 ") + color.text(); }
			case INSERT -> insert(); default -> { }
		}
	}
	private boolean insert() {
		boolean inserted = insertion.apply(color);
		status = inserted ? LuaUiText.text("已插入 ") + color.text() : LuaUiText.text("请先打开可编辑的文本文件");
		return inserted;
	}
	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_TAB) {
			var options = areas().stream().map(Area::focus).toList();
			focus = options.get(Math.floorMod(options.indexOf(focus) + (event.hasShiftDown() ? -1 : 1), options.size()));
			keepFocusVisible(); return true;
		}
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			insert(); return true;
		}
		if (event.key() == InputConstants.KEY_SPACE && focus.ordinal() >= Focus.FORMAT.ordinal()) { activate(focus); return true; }
		int dx = event.key() == InputConstants.KEY_RIGHT ? 1 : event.key() == InputConstants.KEY_LEFT ? -1 : 0;
		int dy = event.key() == InputConstants.KEY_UP ? 1 : event.key() == InputConstants.KEY_DOWN ? -1 : 0;
		if (dx != 0 || dy != 0) {
			int step = event.hasShiftDown() ? 10 : 1;
			switch (focus) {
				case SQUARE -> color.square(color.saturation + dx * step / 255F, color.brightness + dy * step / 255F);
				case HUE -> color.hue(color.hue + (dx - dy) * step / 360F);
				case RED, GREEN, BLUE -> { int channel = focus.ordinal() - Focus.RED.ordinal(); color.channel(channel, color.channel(channel) + (dx + dy) * step); }
				case FORMAT -> format(dx != 0 ? dx : dy);
				case HEX_STYLE -> color.hexNumber = !color.hexNumber;
				default -> { }
			}
			return true;
		}
		return false;
	}
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return false;
		for (Area a : areas()) if (a.contains(event.x(), event.y() + scroll)) {
			focus = a.focus;
			if (focus.ordinal() < Focus.FORMAT.ordinal()) { dragging = focus; drag(event.x(), event.y() + scroll); }
			else activate(focus);
			return true;
		}
		return false;
	}
	private void drag(double x, double y) {
		for (Area a : areas()) if (a.focus == dragging) {
			float fx = (float) Math.clamp((x - a.x) / Math.max(1, a.w - 1), 0D, 1D);
			float fy = (float) Math.clamp((y - a.y) / Math.max(1, a.h - 1), 0D, 1D);
			if (dragging == Focus.SQUARE) color.square(fx, 1 - fy);
			else if (dragging == Focus.HUE) color.hue(fy);
			else color.channel(dragging.ordinal() - Focus.RED.ordinal(), Math.round(fx * 255));
			return;
		}
	}
	@Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { if (dragging == null) return false; drag(event.x(), event.y() + scroll); return true; }
	@Override public boolean mouseReleased(MouseButtonEvent event) { boolean handled = dragging != null; dragging = null; return handled; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.clamp(scroll - (int) Math.round(vertical * 28), 0, maxScroll()); return true; }
}
