package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Consumer;

/** Dockable Minecraft JSON text-component builder with a live formatted preview. */
final class LuaTextJsonOverlay {
	private static final int TITLE_HEIGHT = 28;
	private static final int RESIZE_MARGIN = 6;
	private static final int MIN_WIDTH = 430;
	private static final int MIN_HEIGHT = 330;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;
	private static final List<PaletteColor> COLORS = List.of(
			new PaletteColor("黑", "#000000"), new PaletteColor("深蓝", "#0000AA"),
			new PaletteColor("深绿", "#00AA00"), new PaletteColor("深青", "#00AAAA"),
			new PaletteColor("深红", "#AA0000"), new PaletteColor("紫", "#AA00AA"),
			new PaletteColor("金", "#FFAA00"), new PaletteColor("灰", "#AAAAAA"),
			new PaletteColor("深灰", "#555555"), new PaletteColor("蓝", "#5555FF"),
			new PaletteColor("绿", "#55FF55"), new PaletteColor("青", "#55FFFF"),
			new PaletteColor("红", "#FF5555"), new PaletteColor("粉紫", "#FF55FF"),
			new PaletteColor("黄", "#FFFF55"), new PaletteColor("白", "#FFFFFF"));

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<String> insertion;
	private final Consumer<String> feedback;
	private String text = "示例文字";
	private String color = "#55FFFF";
	private String fontId = "minecraft:default";
	private int cursor = text.length();
	private Focus focus = Focus.TEXT;
	private boolean bold;
	private boolean italic;
	private boolean underlined;
	private boolean strikethrough;
	private boolean obfuscated;
	private String status = "编辑后可插入 Lua 代码或复制原始 JSON";
	private boolean open;
	private boolean keyboardFocused;
	private float progress;
	private int screenWidth;
	private int screenHeight;
	private int windowX = -1;
	private int windowY = 18;
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
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked;
	private int floatingX = -1;
	private int floatingY = 18;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();

	LuaTextJsonOverlay(Minecraft minecraft, Font font, Consumer<String> insertion,
			Consumer<String> feedback) {
		this.minecraft = minecraft;
		this.font = font;
		this.insertion = insertion;
		this.feedback = feedback;
	}

	void resize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
		ensureGeometry();
	}

	void open() {
		open = true;
		keyboardFocused = true;
	}

	void close() {
		open = false;
		keyboardFocused = false;
		dragging = false;
		resizeEdges = 0;
	}

	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F; }
	boolean contains(double x, double y) { return isVisible() && pointInside(x, y); }
	boolean isInteracting() { return dragging || resizeEdges != 0; }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }
	void releaseKeyboardFocus() { keyboardFocused = false; }
	void requestKeyboardFocus() { if (open) keyboardFocused = true; }

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		DockSide previous = dockSide;
		if (!dockSide.docked() && side.docked()) rememberFloatingGeometry();
		dockSide = side;
		if (previous.docked() && !side.docked()) restoreFloatingGeometry();
		resizeEdges = 0;
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (dockSide.docked()) {
			boundsAnimation.animateTo(windowX, windowY, windowWidth, windowHeight, x, y, width, height);
		}
	}

	void tick() {
		float target = open ? 1.0F : 0.0F;
		progress += (target - progress) * 0.28F;
		if (Math.abs(target - progress) < 0.01F) progress = target;
		if (!dragging && resizeEdges == 0) {
			WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(windowX, windowY, windowWidth, windowHeight);
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
		graphics.fill(x - 4, windowY + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(x, windowY, right, bottom, 0xFD101720);
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFF4FD1C5);
		graphics.fill(x + 1, windowY + 1, right - 1, windowY + TITLE_HEIGHT, 0xFF163B42);
		graphics.text(font, "✦  文本 JSON 编辑器", x + 10, windowY + 9, 0xFF8FF5E9);
		drawButton(graphics, mouseX, mouseY, right - 26, windowY + 4, 20, 20, "×", 0xFF28545B, 0xFFB83A46);

		int contentX = x + 12;
		int contentWidth = windowWidth - 24;
		int y = windowY + 38;
		drawLabel(graphics, "文字", contentX, y);
		drawInput(graphics, contentX + 52, y - 4, contentWidth - 52, Focus.TEXT, text, "输入要显示的文字…");
		y += 28;
		drawLabel(graphics, "颜色", contentX, y);
		drawInput(graphics, contentX + 52, y - 4, Math.min(170, contentWidth - 52), Focus.COLOR, color, "#RRGGBB");
		int swatchX = contentX + Math.min(228, contentWidth - 24);
		int rgb = parsedColor();
		graphics.fill(swatchX, y - 3, swatchX + 20, y + 15, 0xFF000000 | rgb);
		graphics.outline(swatchX, y - 3, 20, 18, 0xFFCCCCCC);
		y += 26;

		int cellWidth = Math.max(18, contentWidth / 8);
		for (int index = 0; index < COLORS.size(); index++) {
			PaletteColor preset = COLORS.get(index);
			int row = index / 8;
			int column = index % 8;
			int left = contentX + column * cellWidth;
			int top = y + row * 19;
			int presetColor = Integer.parseInt(preset.hex().substring(1), 16);
			boolean selected = color.equalsIgnoreCase(preset.hex());
			graphics.fill(left, top, left + cellWidth - 3, top + 16, 0xFF000000 | presetColor);
			graphics.outline(left, top, cellWidth - 3, 16, selected ? 0xFFFFFFFF : 0xFF384A52);
			if (inside(mouseX, mouseY, left, top, left + cellWidth - 3, top + 16)) {
				graphics.text(font, preset.label(), left + 2, top + 4,
						(presetColor & 0xFFFFFF) == 0 ? 0xFFFFFFFF : 0xFF000000);
			}
		}
		y += 44;
		drawLabel(graphics, "字体 ID", contentX, y);
		drawInput(graphics, contentX + 52, y - 4, contentWidth - 52, Focus.FONT, fontId,
				"minecraft:default");
		y += 29;

		String[] labels = {"粗体", "斜体", "下划线", "删除线", "乱码"};
		boolean[] values = {bold, italic, underlined, strikethrough, obfuscated};
		int toggleWidth = Math.max(56, (contentWidth - 16) / labels.length);
		for (int index = 0; index < labels.length; index++) {
			int left = contentX + index * (toggleWidth + 4);
			drawButton(graphics, mouseX, mouseY, left, y, toggleWidth, 20,
					(values[index] ? "✓ " : "□ ") + labels[index],
					values[index] ? 0xFF276E66 : 0xFF26343D, 0xFF35877D);
		}
		y += 31;

		graphics.text(font, "实时预览", contentX, y, 0xFF83C7BF);
		graphics.fill(contentX, y + 13, right - 12, y + 44, 0xFF080D12);
		graphics.outline(contentX, y + 13, contentWidth, 31, 0xFF324B52);
		graphics.text(font, previewComponent(), contentX + 8, y + 24, 0xFFFFFFFF);
		y += 53;
		graphics.text(font, "生成的 JSON", contentX, y, 0xFF83C7BF);
		String json = generatedJson();
		graphics.fill(contentX, y + 13, right - 12, y + 35, 0xFF080D12);
		graphics.text(font, font.plainSubstrByWidth(json, contentWidth - 12), contentX + 6, y + 20, 0xFFB5D8D3);

		int buttonY = bottom - 30;
		int buttonWidth = Math.max(84, (contentWidth - 8) / 3);
		drawButton(graphics, mouseX, mouseY, contentX, buttonY, buttonWidth, 21,
				"插入 JSON 字符串", 0xFF21675D, 0xFF2D8276);
		drawButton(graphics, mouseX, mouseY, contentX + buttonWidth + 4, buttonY, buttonWidth, 21,
				"复制 JSON", 0xFF314858, 0xFF3D5B70);
		drawButton(graphics, mouseX, mouseY, contentX + (buttonWidth + 4) * 2, buttonY,
				right - 12 - (contentX + (buttonWidth + 4) * 2), 21,
				"重置效果", 0xFF493C52, 0xFF5B4A66);
		graphics.text(font, font.plainSubstrByWidth(status, contentWidth - 4), contentX, bottom - 44,
				status.startsWith("错误") ? 0xFFFF7777 : 0xFF789D99);
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (dockSide.docked()) return true;
			close();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			insertTyped(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " "));
			return true;
		}
		if (event.key() == InputConstants.KEY_TAB) {
			focus = Focus.values()[(focus.ordinal() + 1) % Focus.values().length];
			cursor = activeValue().length();
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			if (cursor > 0) {
				String value = activeValue();
				int start = value.offsetByCodePoints(cursor, -1);
				setActiveValue(value.substring(0, start) + value.substring(cursor));
				cursor = start;
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE) {
			String value = activeValue();
			if (cursor < value.length()) {
				int end = value.offsetByCodePoints(cursor, 1);
				setActiveValue(value.substring(0, cursor) + value.substring(end));
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_LEFT) cursor = Math.max(0, cursor - 1);
		else if (event.key() == InputConstants.KEY_RIGHT) cursor = Math.min(activeValue().length(), cursor + 1);
		else if (event.key() == InputConstants.KEY_HOME) cursor = 0;
		else if (event.key() == InputConstants.KEY_END) cursor = activeValue().length();
		else return true;
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		int codepoint = event.codepoint();
		if (!Character.isISOControl(codepoint)) insertTyped(new String(Character.toChars(codepoint)));
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return open && pointInside(mouseX, mouseY);
	}

	boolean mouseClicked(MouseButtonEvent event) {
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
			dragging = true;
			dragOffsetX = event.x() - x;
			dragOffsetY = event.y() - windowY;
			return true;
		}

		int contentX = x + 12;
		int contentWidth = windowWidth - 24;
		int textX = contentX + 52;
		int textWidth = contentWidth - 52;
		int textY = windowY + 34;
		if (inside(event.x(), event.y(), textX, textY, textX + textWidth, textY + 20)) {
			selectAt(Focus.TEXT, event.x(), textX, textWidth);
			return true;
		}
		int colorX = contentX + 52;
		int colorWidth = Math.min(170, contentWidth - 52);
		int colorY = windowY + 62;
		if (inside(event.x(), event.y(), colorX, colorY, colorX + colorWidth, colorY + 20)) {
			selectAt(Focus.COLOR, event.x(), colorX, colorWidth);
			return true;
		}
		int y = windowY + 92;
		int cellWidth = Math.max(18, contentWidth / 8);
		for (int index = 0; index < COLORS.size(); index++) {
			int left = contentX + index % 8 * cellWidth;
			int top = y + index / 8 * 19;
			if (inside(event.x(), event.y(), left, top, left + cellWidth - 3, top + 16)) {
				color = COLORS.get(index).hex();
				select(Focus.COLOR);
				return true;
			}
		}
		int fontX = contentX + 52;
		int fontWidth = contentWidth - 52;
		int fontY = windowY + 132;
		if (inside(event.x(), event.y(), fontX, fontY, fontX + fontWidth, fontY + 20)) {
			selectAt(Focus.FONT, event.x(), fontX, fontWidth);
			return true;
		}
		y = windowY + 165;
		int toggleWidth = Math.max(56, (contentWidth - 16) / 5);
		for (int index = 0; index < 5; index++) {
			int left = contentX + index * (toggleWidth + 4);
			if (inside(event.x(), event.y(), left, y, left + toggleWidth, y + 20)) {
				toggle(index);
				return true;
			}
		}
		int buttonY = bottom - 30;
		int buttonWidth = Math.max(84, (contentWidth - 8) / 3);
		if (inside(event.x(), event.y(), contentX, buttonY, contentX + buttonWidth, buttonY + 21)) {
			if (!validate()) return true;
			insertion.accept(luaJsonString());
			status = "已把 Minecraft 文本组件 JSON 字符串插入代码";
			feedback.accept(status);
		} else if (inside(event.x(), event.y(), contentX + buttonWidth + 4, buttonY,
				contentX + buttonWidth * 2 + 4, buttonY + 21)) {
			if (!validate()) return true;
			minecraft.keyboardHandler.setClipboard(generatedJson());
			status = "已复制原始 Minecraft 文本组件 JSON";
			feedback.accept(status);
		} else if (inside(event.x(), event.y(), contentX + (buttonWidth + 4) * 2, buttonY,
				right - 12, buttonY + 21)) {
			bold = italic = underlined = strikethrough = obfuscated = false;
			color = "#FFFFFF";
			fontId = "minecraft:default";
			status = "已重置格式效果";
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (resizeEdges != 0) {
			resize(event.x(), event.y());
			return true;
		}
		if (!dragging) return false;
		if (dockSide.docked()) undockForDrag(event.x(), event.y());
		windowX = clamp((int) Math.round(event.x() - dragOffsetX), 0, Math.max(0, screenWidth - windowWidth));
		windowY = clamp((int) Math.round(event.y() - dragOffsetY), 0, Math.max(0, screenHeight - windowHeight));
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
		return true;
	}

	boolean mouseReleased() {
		if (!isInteracting()) return false;
		dragging = false;
		resizeEdges = 0;
		return true;
	}

	private void select(Focus next) {
		focus = next;
		cursor = activeValue().length();
	}

	private void selectAt(Focus next, double mouseX, int fieldX, int fieldWidth) {
		focus = next;
		String value = activeValue();
		int start = visibleStart(value, fieldWidth - 10);
		int local = Math.max(0, (int) Math.round(mouseX - fieldX - 5));
		int best = start;
		int bestDistance = Integer.MAX_VALUE;
		for (int index = start; index <= value.length(); index++) {
			int width = font.width(value.substring(start, index));
			int distance = Math.abs(width - local);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = index;
			}
			if (width > local && distance > bestDistance) break;
		}
		cursor = best;
	}

	private void toggle(int index) {
		switch (index) {
			case 0 -> bold = !bold;
			case 1 -> italic = !italic;
			case 2 -> underlined = !underlined;
			case 3 -> strikethrough = !strikethrough;
			case 4 -> obfuscated = !obfuscated;
			default -> { }
		}
	}

	private void insertTyped(String value) {
		if (value == null || value.isEmpty()) return;
		String current = activeValue();
		setActiveValue(current.substring(0, cursor) + value + current.substring(cursor));
		cursor += value.length();
	}

	private String activeValue() {
		return switch (focus) {
			case TEXT -> text;
			case COLOR -> color;
			case FONT -> fontId;
		};
	}

	private void setActiveValue(String value) {
		switch (focus) {
			case TEXT -> text = value;
			case COLOR -> color = value;
			case FONT -> fontId = value;
		}
	}

	private int parsedColor() {
		String value = color.strip();
		if (value.startsWith("#")) value = value.substring(1);
		if (value.length() != 6) return 0xFFFFFF;
		try { return Integer.parseInt(value, 16); }
		catch (NumberFormatException ignored) { return 0xFFFFFF; }
	}

	private boolean validate() {
		if (!color.matches("#[0-9a-fA-F]{6}")) {
			status = "错误：颜色必须使用 #RRGGBB 六位十六进制格式";
			feedback.accept(status);
			return false;
		}
		if (!fontId.isBlank() && Identifier.tryParse(fontId.strip()) == null) {
			status = "错误：字体 ID 格式无效，应类似 minecraft:default";
			feedback.accept(status);
			return false;
		}
		return true;
	}

	private JsonObject jsonObject() {
		JsonObject result = new JsonObject();
		result.addProperty("text", text);
		result.addProperty("color", color.toUpperCase());
		if (!fontId.isBlank()) result.addProperty("font", fontId.strip());
		if (bold) result.addProperty("bold", true);
		if (italic) result.addProperty("italic", true);
		if (underlined) result.addProperty("underlined", true);
		if (strikethrough) result.addProperty("strikethrough", true);
		if (obfuscated) result.addProperty("obfuscated", true);
		return result;
	}

	private String generatedJson() { return jsonObject().toString(); }
	private String luaJsonString() { return new JsonPrimitive(generatedJson()).toString(); }

	private Component previewComponent() {
		Style style = Style.EMPTY.withColor(TextColor.fromRgb(parsedColor()))
				.withBold(bold).withItalic(italic).withUnderlined(underlined)
				.withStrikethrough(strikethrough).withObfuscated(obfuscated);
		Identifier id = Identifier.tryParse(fontId.strip());
		if (id != null) style = style.withFont(new FontDescription.Resource(id));
		return Component.literal(text.isEmpty() ? "（空文字）" : text).withStyle(style);
	}

	private void drawInput(GuiGraphicsExtractor graphics, int x, int y, int width, Focus field,
			String value, String hint) {
		graphics.fill(x, y, x + width, y + 20, 0xFF080D12);
		graphics.outline(x, y, width, 20, focus == field ? 0xFF71E3D6 : 0xFF40555C);
		int start = focus == field ? visibleStart(value, width - 10) : 0;
		String shown = value.substring(Math.min(start, value.length()));
		if (focus == field && keyboardFocused) {
			int caret = Math.min(cursor, value.length());
			shown = value.substring(start, caret) + "_" + value.substring(caret);
		}
		if (shown.isEmpty()) shown = hint;
		graphics.text(font, font.plainSubstrByWidth(shown, width - 10), x + 5, y + 6,
				value.isEmpty() ? 0xFF617078 : 0xFFE5F2F0);
	}

	private int visibleStart(String value, int width) {
		if (value.isEmpty() || focus == null) return 0;
		int caret = Math.min(cursor, value.length());
		int start = 0;
		while (start < caret && font.width(value.substring(start, caret) + "_") > width * 3 / 4) {
			start = value.offsetByCodePoints(start, 1);
		}
		return start;
	}

	private void drawLabel(GuiGraphicsExtractor graphics, String label, int x, int y) {
		graphics.text(font, label, x, y + 2, 0xFF9AB6B2);
	}

	private void drawButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int y, int width, int height, String label, int base, int hover) {
		boolean hovered = inside(mouseX, mouseY, x, y, x + width, y + height);
		graphics.fill(x, y, x + width, y + height, hovered ? hover : base);
		graphics.outline(x, y, width, height, hovered ? 0xFFD1FFFA : 0xFF4A6269);
		graphics.centeredText(font, font.plainSubstrByWidth(label, Math.max(8, width - 6)),
				x + width / 2, y + (height - 8) / 2, 0xFFFFFFFF);
	}

	private int panelX() { return Math.round(screenWidth + (windowX - screenWidth) * progress); }
	private boolean pointInside(double x, double y) {
		int left = panelX();
		return inside(x, y, left, windowY, left + windowWidth, windowY + windowHeight);
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
		int dx = (int) Math.round(mouseX - resizeStartMouseX);
		int dy = (int) Math.round(mouseY - resizeStartMouseY);
		int left = resizeStartX;
		int right = resizeStartX + resizeStartWidth;
		int top = resizeStartY;
		int bottom = resizeStartY + resizeStartHeight;
		if ((resizeEdges & EDGE_LEFT) != 0) left = Math.max(0, Math.min(right - MIN_WIDTH, resizeStartX + dx));
		if ((resizeEdges & EDGE_RIGHT) != 0) right = Math.min(screenWidth,
				Math.max(left + MIN_WIDTH, resizeStartX + resizeStartWidth + dx));
		if ((resizeEdges & EDGE_TOP) != 0) top = Math.max(0, Math.min(bottom - MIN_HEIGHT, resizeStartY + dy));
		if ((resizeEdges & EDGE_BOTTOM) != 0) bottom = Math.min(screenHeight,
				Math.max(top + MIN_HEIGHT, resizeStartY + resizeStartHeight + dy));
		windowX = left;
		windowY = top;
		windowWidth = right - left;
		windowHeight = bottom - top;
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0 || dockSide.docked()) return;
		int availableWidth = Math.max(300, screenWidth - 20);
		int availableHeight = Math.max(220, screenHeight - 20);
		if (windowWidth < 0) windowWidth = Math.min(availableWidth, Math.max(MIN_WIDTH, (int) (screenWidth * 0.58F)));
		if (windowHeight < 0) windowHeight = Math.min(availableHeight, Math.max(MIN_HEIGHT, (int) (screenHeight * 0.70F)));
		windowWidth = Math.min(windowWidth, screenWidth);
		windowHeight = Math.min(windowHeight, screenHeight);
		if (windowX < 0) windowX = Math.max(0, (screenWidth - windowWidth) / 2);
		windowX = clamp(windowX, 0, Math.max(0, screenWidth - windowWidth));
		windowY = clamp(windowY, 0, Math.max(0, screenHeight - windowHeight));
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
		windowX = clamp((int) Math.round(mouseX - dragOffsetX), 0, Math.max(0, screenWidth - windowWidth));
		windowY = clamp((int) Math.round(mouseY - dragOffsetY), 0, Math.max(0, screenHeight - windowHeight));
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	private enum Focus { TEXT, COLOR, FONT }
	private record PaletteColor(String label, String hex) { }
}
