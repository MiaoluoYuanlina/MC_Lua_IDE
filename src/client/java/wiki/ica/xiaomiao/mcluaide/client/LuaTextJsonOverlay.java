package wiki.ica.xiaomiao.mcluaide.client;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Dockable Minecraft JSON text-component builder with a live formatted preview. */
final class LuaTextJsonOverlay {
	void renderImGui() {
		if(imgui.ImGui.beginTable("segments",2,imgui.flag.ImGuiTableFlags.Resizable|imgui.flag.ImGuiTableFlags.BordersInnerV)) {
			imgui.ImGui.tableSetupColumn("分段",imgui.flag.ImGuiTableColumnFlags.WidthFixed,180);imgui.ImGui.tableSetupColumn("属性");imgui.ImGui.tableNextColumn();
			NativeUi.action("添加",this::addSegment);imgui.ImGui.sameLine();NativeUi.action("删除",this::deleteSegment);
			NativeUi.action("复制分段",this::duplicateSegment);NativeUi.action("上移",()->moveSegment(-1));imgui.ImGui.sameLine();NativeUi.action("下移",()->moveSegment(1));
			for(int i=0;i<segments.size();i++)if(imgui.ImGui.selectable((i+1)+" · "+segments.get(i).previewText(),i==selectedIndex))selectSegment(i);
			imgui.ImGui.tableNextColumn();var segment=segments.get(selectedIndex);
			segment.kind=NativeUi.combo("内容类型",segment.kind,List.of(LuaTextJsonDocument.Kind.values()),v->LuaUiText.text(v.label));
			switch(segment.kind) {
				case TEXT->segment.text=NativeUi.field("文字",segment.text);
				case SCORE->{segment.scoreName=NativeUi.field("持有者 / 选择器",segment.scoreName);segment.scoreObjective=NativeUi.field("积分项",segment.scoreObjective);}
				case SELECTOR->{segment.selector=NativeUi.field("目标选择器",segment.selector);segment.separator=NativeUi.field("分隔符",segment.separator);}
				case TRANSLATE->{segment.translate=NativeUi.field("翻译键",segment.translate);segment.fallback=NativeUi.field("后备文字",segment.fallback);}
				case KEYBIND->segment.keybind=NativeUi.field("按键 ID",segment.keybind);
				case LUA_VARIABLE->segment.luaVariable=NativeUi.field("Lua 变量 / 表达式",segment.luaVariable);
			}
			if(imgui.ImGui.collapsingHeader(LuaUiText.text("颜色"),imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)){
				segment.gradient=NativeUi.check("启用渐变",segment.gradient);
				String stops=NativeUi.field("十六进制色标（逗号分隔）",segment.gradientStops);
				if(!stops.equals(segment.gradientStops)){segment.gradientStops=stops;String first=stops.split("[,，;；\\s]+",2)[0];if(first.matches("#?[0-9a-fA-F]{6}"))segment.color=first.startsWith("#")?first:"#"+first;}
				int picked=NativeUi.color("插入颜色",0xff000000|safeRgb(segment.color));String hex=String.format("#%06X",picked&0xffffff);
				if(!hex.equalsIgnoreCase(segment.color)){segment.color=hex;String[] parts=segment.gradientStops.split(",",2);segment.gradientStops=hex+(parts.length>1?","+parts[1]:"");}
				if(imgui.ImGui.button(LuaUiText.text("添加色标")))segment.gradientStops+=", "+hex;
			}
			if(imgui.ImGui.collapsingHeader(LuaUiText.text("样式"),imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)){
				String color=NativeUi.field("颜色 ID",segment.color);if(!color.equals(segment.color)){segment.color=color;String[] parts=segment.gradientStops.split(",",2);segment.gradientStops=color+(parts.length>1?","+parts[1]:"");}
				segment.fontId=NativeUi.field("字体 ID",segment.fontId);segment.bold=NativeUi.check("粗体",segment.bold);imgui.ImGui.sameLine();segment.italic=NativeUi.check("斜体",segment.italic);
				segment.underlined=NativeUi.check("下划线",segment.underlined);imgui.ImGui.sameLine();segment.strikethrough=NativeUi.check("删除线",segment.strikethrough);segment.obfuscated=NativeUi.check("随机字符",segment.obfuscated);
			}
			if(imgui.ImGui.collapsingHeader(LuaUiText.text("悬浮显示"))){
				segment.hover=NativeUi.combo("悬浮类型",segment.hover,List.of(LuaTextJsonDocument.Hover.values()),v->LuaUiText.text(v.label));
				switch(segment.hover){case TEXT->segment.hoverText=NativeUi.field("悬浮文字",segment.hoverText);case ITEM->{segment.hoverItem=NativeUi.field("物品 ID",segment.hoverItem);segment.hoverCount=NativeUi.field("数量",segment.hoverCount);}case ENTITY->{segment.hoverEntityType=NativeUi.field("实体类型",segment.hoverEntityType);segment.hoverEntityUuid=NativeUi.field("实体 UUID",segment.hoverEntityUuid);segment.hoverEntityName=NativeUi.field("实体名称",segment.hoverEntityName);}default->{}}
			}
			if(imgui.ImGui.collapsingHeader(LuaUiText.text("点击事件"))){segment.click=NativeUi.combo("点击动作",segment.click,List.of(LuaTextJsonDocument.Click.values()),v->LuaUiText.text(v.label));segment.clickValue=NativeUi.field("点击参数",segment.clickValue);}
			imgui.ImGui.endTable();
		}
		imgui.ImGui.separatorText("分段预览");
		int previewWidth=Math.max(32,(int)imgui.ImGui.getContentRegionAvailX());
		NativeUi.media("text_component_preview",previewWidth,48,g->{int px=6;for(var segment:segments){String text=segment.previewText();List<Integer> colors=null;try{if(segment.gradient&&segment.kind==LuaTextJsonDocument.Kind.TEXT)colors=LuaTextJsonDocument.gradientColors(segment.gradientStops,text.codePointCount(0,text.length()));}catch(IllegalArgumentException ignored){}int charIndex=0;for(int position=0;position<text.length()&&px<previewWidth-8;){int cp=text.codePointAt(position);String ch=new String(Character.toChars(cp));Component styled=Component.literal(ch).withStyle(previewStyle(segment,colors==null?safeRgb(firstColorText(segment)):colors.get(charIndex++)));g.text(font,styled,px,17,0xFFFFFFFF);px+=font.width(styled);position+=Character.charCount(cp);}}});
		for(int i=0;i<segments.size();i++){var segment=segments.get(i);int rgb=safeRgb(segment.color);imgui.ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,(rgb>>16&255)/255f,(rgb>>8&255)/255f,(rgb&255)/255f,1);if(imgui.ImGui.selectable(segment.previewText()+"##preview"+i,i==selectedIndex))selectSegment(i);NativeUi.hint(hoverSummary(segment));imgui.ImGui.popStyleColor();}
		NativeUi.readOnly("##generated",generatedJsonSafe(),120);NativeUi.action("插入",this::insertJson);imgui.ImGui.sameLine();NativeUi.action("复制",this::copyJson);NativeUi.status(status);
	}
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
	private final List<LuaTextJsonDocument.Segment> segments = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private int selectedIndex;
	private Tab tab = Tab.CONTENT;
	private int detailScroll;
	private int detailContentHeight;
	private int detailTop;
	private int detailBottom;
	private int sidebarLeft;
	private int sidebarRight;
	private int sidebarListTop;
	private int sidebarListBottom;
	private int segmentScroll;
	private static final int SEGMENT_ROW_HEIGHT = 29;
	private boolean colorPickerOpen;
	private int pickerDragMode;
	private int pickerX;
	private int pickerY;
	private int pickerWidth;
	private int pickerHeight;
	private int pickerSquareX;
	private int pickerSquareY;
	private int pickerSquareSize;
	private int pickerHueX;
	private float pickerHue;
	private float pickerSaturation;
	private float pickerBrightness;
	private double lastClickX;
	private int cursor = "示例文字".length();
	private Focus focus = Focus.TEXT;
	private String status = "可添加多个片段；点击预览可选中片段，动态内容由服务器解析";
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
		segments.add(new LuaTextJsonDocument.Segment());
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
		colorPickerOpen = false;
		pickerDragMode = 0;
		keyboardFocused = false;
		dragging = false;
		resizeEdges = 0;
	}

	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F; }
	boolean contains(double x, double y) { return isVisible() && pointInside(x, y); }
	boolean isInteracting() { return dragging || resizeEdges != 0 || pickerDragMode != 0; }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }
	void releaseKeyboardFocus() { keyboardFocused = false; }
	void requestKeyboardFocus() { if (open) keyboardFocused = true; }

	void showDemoForTesting() {
		segments.clear();
		LuaTextJsonDocument.Segment first = new LuaTextJsonDocument.Segment();
		first.text = "多色渐变文字";
		first.gradient = true;
		first.hover = LuaTextJsonDocument.Hover.TEXT;
		first.hoverText = "悬浮提示";
		first.click = LuaTextJsonDocument.Click.RUN_COMMAND;
		first.clickValue = "/help";
		segments.add(first);
		LuaTextJsonDocument.Segment second = new LuaTextJsonDocument.Segment();
		second.kind = LuaTextJsonDocument.Kind.SCORE;
		segments.add(second);
		selectedIndex = 0;
		tab = Tab.GRADIENT;
		focus = Focus.GRADIENT_STOPS;
		cursor = activeValue().length();
		detailScroll = 0;
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
		hits.clear();
		int x = panelX();
		int right = x + windowWidth;
		int bottom = windowY + windowHeight;
		graphics.fill(x - 4, windowY + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(x, windowY, right, bottom, 0xFD101720);
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFF4FD1C5);
		graphics.fill(x + 1, windowY + 1, right - 1, windowY + TITLE_HEIGHT, 0xFF163B42);
		graphics.text(font, LuaUiText.text("✦  文本 JSON 编辑器 · 分段"), x + 10, windowY + 9, 0xFF8FF5E9);
		drawButton(graphics, mouseX, mouseY, right - 26, windowY + 4, 20, 20, "×", 0xFF28545B, 0xFFB83A46);

		int sidebarWidth = Math.min(138, Math.max(104, windowWidth / 4));
		sidebarLeft = x + 10;
		sidebarRight = sidebarLeft + sidebarWidth;
		int contentX = sidebarRight + 8;
		int contentWidth = right - 12 - contentX;
		boolean compact = windowHeight < 310;
		detailTop = windowY + 61;
		detailBottom = bottom - (compact ? 91 : 126);
		renderSegmentSidebar(graphics, mouseX, mouseY, bottom);
		renderTabs(graphics, mouseX, mouseY, contentX, contentWidth);
		graphics.fill(contentX, detailTop, right - 12, detailBottom, 0xFF111C24);
		graphics.enableScissor(contentX, detailTop, right - 12, detailBottom);
		int y = detailTop + 5 - detailScroll;
		y = switch (tab) {
			case CONTENT -> renderContentTab(graphics, mouseX, mouseY, contentX + 5, contentWidth - 10, y);
			case STYLE -> renderStyleTab(graphics, mouseX, mouseY, contentX + 5, contentWidth - 10, y);
			case INTERACTION -> renderInteractionTab(graphics, mouseX, mouseY, contentX + 5, contentWidth - 10, y);
			case GRADIENT -> renderGradientTab(graphics, mouseX, mouseY, contentX + 5, contentWidth - 10, y);
		};
		detailContentHeight = Math.max(0, y + detailScroll - detailTop + 5);
		detailScroll = Math.min(detailScroll,
				Math.max(0, detailContentHeight - (detailBottom - detailTop)));
		graphics.disableScissor();
		if (detailContentHeight > detailBottom - detailTop) {
			int track = detailBottom - detailTop;
			int thumb = Math.max(12, track * track / detailContentHeight);
			int thumbY = detailTop + detailScroll * (track - thumb)
					/ Math.max(1, detailContentHeight - track);
			graphics.fill(right - 16, detailTop, right - 13, detailBottom, 0xFF263845);
			graphics.fill(right - 17, thumbY, right - 12, thumbY + thumb, 0xFF59A99E);
		}
		int previewY = bottom - (compact ? 81 : 116);
		renderPreview(graphics, mouseX, mouseY, contentX, previewY, contentWidth);
		if (!compact) {
			graphics.text(font, LuaTextJsonDocument.hasVariables(segments) ? "Lua" : "JSON", contentX, bottom - 70, 0xFF83C7BF);
			graphics.fill(contentX + 34, bottom - 74, right - 12, bottom - 54, 0xFF080D12);
			graphics.text(font, font.plainSubstrByWidth(generatedJsonSafe(), contentWidth - 48),
					contentX + 39, bottom - 68, 0xFFB5D8D3);
		}
		int buttonY = bottom - 30;
		int buttonWidth = (contentWidth - 8) / 3;
		button(graphics, mouseX, mouseY, contentX, buttonY, buttonWidth, 21,
				LuaTextJsonDocument.hasVariables(segments) ? "插入 Lua 拼接" : "插入 JSON 字符串", 0xFF21675D, 0xFF2D8276, false, this::insertJson);
		button(graphics, mouseX, mouseY, contentX + buttonWidth + 4, buttonY, buttonWidth, 21,
				LuaTextJsonDocument.hasVariables(segments) ? "复制 Lua 拼接" : LuaUiText.text("复制 JSON"), 0xFF314858, 0xFF3D5B70, false, this::copyJson);
		button(graphics, mouseX, mouseY, contentX + (buttonWidth + 4) * 2, buttonY,
				right - 12 - (contentX + (buttonWidth + 4) * 2), 21,
				"重置本段", 0xFF493C52, 0xFF5B4A66, false, this::resetSelectedSegment);
		graphics.text(font, font.plainSubstrByWidth(status, contentWidth - 4), contentX, bottom - 44,
				status.startsWith(LuaUiText.text("错误")) ? 0xFFFF7777 : 0xFF789D99);
		if (colorPickerOpen) renderColorPicker(graphics, mouseX, mouseY);
		ResizeBorderHint.render(graphics, x, windowY, right, bottom,
				resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY), 0xFF8FF5E9);
	}

	private void renderSegmentSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int bottom) {
		int width = sidebarRight - sidebarLeft;
		int top = windowY + 33;
		int footer = bottom - 56;
		graphics.fill(sidebarLeft, top, sidebarRight, bottom - 34, 0xFF14232C);
		graphics.outline(sidebarLeft, top, width, bottom - 34 - top, 0xFF36555C);
		graphics.text(font, "分段 " + segments.size(), sidebarLeft + 5, top + 7, 0xFFA7E5DD);
		button(graphics, mouseX, mouseY, sidebarRight - 44, top + 3, 18, 19, "+",
				0xFF20665F, 0xFF2E867C, false, this::addSegment);
		button(graphics, mouseX, mouseY, sidebarRight - 23, top + 3, 18, 19, "⧉",
				0xFF344B59, 0xFF477183, false, this::duplicateSegment);
		sidebarListTop = top + 27;
		sidebarListBottom = Math.max(sidebarListTop, footer - 3);
		int visibleHeight = sidebarListBottom - sidebarListTop;
		segmentScroll = clamp(segmentScroll, 0, Math.max(0, segments.size() * SEGMENT_ROW_HEIGHT - visibleHeight));
		graphics.enableScissor(sidebarLeft + 1, sidebarListTop, sidebarRight - 1, sidebarListBottom);
		for (int index = 0; index < segments.size(); index++) {
			int rowY = sidebarListTop + index * SEGMENT_ROW_HEIGHT - segmentScroll;
			if (rowY + SEGMENT_ROW_HEIGHT < sidebarListTop || rowY > sidebarListBottom) continue;
			LuaTextJsonDocument.Segment item = segments.get(index);
			graphics.fill(sidebarLeft + 3, rowY + 1, sidebarRight - 3, rowY + SEGMENT_ROW_HEIGHT - 2,
					index == selectedIndex ? 0xFF28645F : inside(mouseX, mouseY,
						sidebarLeft + 3, rowY, sidebarRight - 3, rowY + SEGMENT_ROW_HEIGHT) ? 0xFF304751 : 0xFF1D3039);
			graphics.fill(sidebarLeft + 6, rowY + 5, sidebarLeft + 9, rowY + 22,
					0xFF000000 | safeRgb(firstColorText(item)));
			graphics.text(font, (index + 1) + " · " + item.kind.label,
					sidebarLeft + 13, rowY + 4, index == selectedIndex ? 0xFFFFFFFF : 0xFFBDD5D1);
			graphics.text(font, font.plainSubstrByWidth(item.previewText(), width - 19),
					sidebarLeft + 13, rowY + 16, 0xFF839F9E);
		}
		graphics.disableScissor();
		int controlWidth = (width - 12) / 3;
		button(graphics, mouseX, mouseY, sidebarLeft + 3, footer, controlWidth, 18,
				"↑", 0xFF2A4656, 0xFF3B6B7F, false, () -> moveSegment(-1));
		button(graphics, mouseX, mouseY, sidebarLeft + 6 + controlWidth, footer, controlWidth, 18,
				"↓", 0xFF2A4656, 0xFF3B6B7F, false, () -> moveSegment(1));
		button(graphics, mouseX, mouseY, sidebarLeft + 9 + controlWidth * 2, footer,
				width - 12 - controlWidth * 2, 18, "删", 0xFF563B49, 0xFF824355,
				false, this::deleteSegment);
	}

	private void renderTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x, int width) {
		int y = windowY + 34;
		int tabWidth = width / Tab.values().length;
		for (Tab candidate : Tab.values()) {
			int left = x + candidate.ordinal() * tabWidth;
			int actualWidth = candidate == Tab.GRADIENT ? width - candidate.ordinal() * tabWidth : tabWidth - 2;
			button(graphics, mouseX, mouseY, left, y, actualWidth, 22, candidate.label,
					candidate == tab ? 0xFF28645F : 0xFF1B303B, 0xFF327970, false,
					() -> { tab = candidate; detailScroll = 0; focus = firstFocus(); cursor = activeValue().length(); });
		}
	}

	private int renderContentTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int width, int y) {
		LuaTextJsonDocument.Segment segment = selected();
		y = choiceRow(graphics, mouseX, mouseY, x, width, y, LuaUiText.text("内容类型"), segment.kind.label, () -> {
			segment.kind = next(segment.kind);
			focus = firstFocus(); cursor = activeValue().length(); detailScroll = 0;
		});
		switch (segment.kind) {
			case TEXT -> y = inputRow(graphics, x, width, y, LuaUiText.text("文字"), Focus.TEXT, "输入当前片段文字");
			case SCORE -> {
				y = inputRow(graphics, x, width, y, "计分对象", Focus.SCORE_NAME, "玩家名、假玩家名或 @s");
				y = inputRow(graphics, x, width, y, "目标名称", Focus.SCORE_OBJECTIVE, "计分板目标，如 points");
			}
			case SELECTOR -> {
				y = inputRow(graphics, x, width, y, LuaUiText.text("目标选择器"), Focus.SELECTOR, "@p、@a[tag=...] 等");
				y = inputRow(graphics, x, width, y, "分隔文字", Focus.SEPARATOR, "多个实体之间的分隔文字");
			}
			case TRANSLATE -> {
				y = inputRow(graphics, x, width, y, "翻译键", Focus.TRANSLATE, "如 chat.type.text");
				y = inputRow(graphics, x, width, y, "回退文字", Focus.FALLBACK, "翻译缺失时显示");
			}
			case KEYBIND -> y = inputRow(graphics, x, width, y, "按键 ID", Focus.KEYBIND, "如 key.jump");
			case LUA_VARIABLE -> y = inputRow(graphics, x, width, y, "Lua 变量", Focus.LUA_VARIABLE, "如 name、event.playerName、items[1].count");
		}
		y += 4;
		button(graphics, mouseX, mouseY, x + 2, y, (width - 8) / 2, 20,
				"← 本段前移", 0xFF2A4656, 0xFF3B6B7F, true, () -> moveSegment(-1));
		button(graphics, mouseX, mouseY, x + (width - 8) / 2 + 6, y,
				(width - 8) / 2, 20, "本段后移 →", 0xFF2A4656, 0xFF3B6B7F,
				true, () -> moveSegment(1));
		y += 28;
		return note(graphics, x, y, segment.kind == LuaTextJsonDocument.Kind.LUA_VARIABLE
				? "Lua 变量在脚本运行时读取，以 tostring 转文字并安全转义，通过 .. 拼接；预览不执行表达式。"
				: "分数和选择器由服务器在使用文本组件时解析；预览显示占位符。");
	}

	private int renderStyleTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int width, int y) {
		LuaTextJsonDocument.Segment segment = selected();
		y = inputRow(graphics, x, width, y, "文字颜色", Focus.COLOR, "#RRGGBB");
		int rgb = safeRgb(firstColorText(segment));
		graphics.fill(x + width - 25, y - 25, x + width - 7, y - 7, 0xFF000000 | rgb);
		graphics.outline(x + width - 25, y - 25, 18, 18, 0xFFC8D5D9);
		int cellWidth = Math.max(20, width / 8);
		for (int index = 0; index < COLORS.size(); index++) {
			PaletteColor preset = COLORS.get(index);
			int left = x + index % 8 * cellWidth;
			int top = y + index / 8 * 20;
			int presetColor = Integer.parseInt(preset.hex().substring(1), 16);
			graphics.fill(left, top, left + cellWidth - 3, top + 17, 0xFF000000 | presetColor);
			graphics.outline(left, top, cellWidth - 3, 17,
					firstColorText(segment).equalsIgnoreCase(preset.hex()) ? 0xFFFFFFFF : 0xFF384A52);
			hits.add(new Hit(left, top, left + cellWidth - 3, top + 17, true,
					() -> { setFirstColor(segment, preset.hex()); focus = Focus.COLOR;
						cursor = segment.color.length(); }));
		}
		y += 45;
		y = inputRow(graphics, x, width, y, "字体 ID", Focus.FONT, "minecraft:default");
		String[] labels = {LuaUiText.text("粗体"), LuaUiText.text("斜体"), LuaUiText.text("下划线"), LuaUiText.text("删除线"), "乱码"};
		boolean[] values = {segment.bold, segment.italic, segment.underlined,
				segment.strikethrough, segment.obfuscated};
		int toggleWidth = Math.max(48, (width - 16) / labels.length);
		for (int index = 0; index < labels.length; index++) {
			int position = index;
			button(graphics, mouseX, mouseY, x + index * (toggleWidth + 4), y, toggleWidth, 20,
					(values[index] ? "✓ " : "□ ") + labels[index],
					values[index] ? 0xFF276E66 : 0xFF26343D, 0xFF35877D,
					true, () -> toggle(position));
		}
		return y + 27;
	}

	private int renderInteractionTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int width, int y) {
		LuaTextJsonDocument.Segment segment = selected();
		y = choiceRow(graphics, mouseX, mouseY, x, width, y, "悬浮事件", segment.hover.label,
				() -> { segment.hover = next(segment.hover); detailScroll = 0;
					focus = firstFocus(); cursor = activeValue().length(); });
		switch (segment.hover) {
			case TEXT -> y = inputRow(graphics, x, width, y, "悬浮文字", Focus.HOVER_TEXT, "文字或文本组件 JSON");
			case ITEM -> {
				y = inputRow(graphics, x, width, y, LuaUiText.text("物品 ID"), Focus.HOVER_ITEM, "minecraft:diamond");
				y = inputRow(graphics, x, width, y, "数量", Focus.HOVER_COUNT, "1");
			}
			case ENTITY -> {
				y = inputRow(graphics, x, width, y, "实体类型", Focus.HOVER_ENTITY_ID, "minecraft:pig");
				y = inputRow(graphics, x, width, y, "实体 UUID", Focus.HOVER_ENTITY_UUID, "完整 UUID");
				y = inputRow(graphics, x, width, y, "显示名称", Focus.HOVER_ENTITY_NAME, "可留空");
			}
			case NONE -> { }
		}
		y += 4;
		y = choiceRow(graphics, mouseX, mouseY, x, width, y, "点击事件", segment.click.label,
				() -> { segment.click = next(segment.click); seedClickValue(segment); });
		if (segment.click != LuaTextJsonDocument.Click.NONE)
			y = inputRow(graphics, x, width, y, switch (segment.click) {
				case OPEN_URL -> "网址"; case CHANGE_PAGE -> "页码"; case SHOW_DIALOG -> "对话框 ID";
				case COPY -> "复制内容"; default -> "命令";
			}, Focus.CLICK_VALUE, "点击后使用的参数");
		y += 3;
		return note(graphics, x, y, "预览不会执行点击动作；实际交互由 Minecraft 客户端处理。");
	}

	private int renderGradientTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int width, int y) {
		LuaTextJsonDocument.Segment segment = selected();
		y = choiceRow(graphics, mouseX, mouseY, x, width, y, "逐字渐变", segment.gradient ? "已开启" : "已关闭",
				() -> segment.gradient = !segment.gradient);
		y = inputRow(graphics, x, width, y, "颜色 ID", Focus.GRADIENT_STOPS,
				"#FF0000, #00FF00, #0000FF");
		int buttonWidth = (width - 8) / 2;
		button(graphics, mouseX, mouseY, x + 2, y, buttonWidth, 20, LuaUiText.text("插入颜色"),
				0xFF314858, 0xFF3D5B70, true, this::openColorPicker);
		button(graphics, mouseX, mouseY, x + buttonWidth + 6, y, buttonWidth, 20,
				"反转色标顺序", 0xFF314858, 0xFF3D5B70, true, () -> {
					String[] stops = segment.gradientStops.split("[，,;；\\s]+");
					List<String> reversed = new ArrayList<>(List.of(stops));
					java.util.Collections.reverse(reversed);
					segment.gradientStops = String.join(", ", reversed);
					syncFirstColor(segment);
				});
		y += 27;
		try {
			List<Integer> colors = segment.gradient
					? LuaTextJsonDocument.gradientColors(segment.gradientStops, 40)
					: java.util.Collections.nCopies(40,
						LuaTextJsonDocument.firstColor(segment.gradientStops));
			for (int index = 0; index < 40; index++) {
				int left = x + index * (width - 2) / 40;
				int right = x + (index + 1) * (width - 2) / 40;
				graphics.fill(left, y, right, y + 17, 0xFF000000 | colors.get(index));
			}
		} catch (IllegalArgumentException error) {
			graphics.text(font, error.getMessage(), x + 2, y + 4, 0xFFFF8888);
		}
		y += 24;
		return note(graphics, x, y, "单色使用第一个颜色；渐变支持 2～32 个色标。文字格式、交互效果会保留。"
				+ (segment.kind == LuaTextJsonDocument.Kind.TEXT ? "" : " 动态内容不支持逐字渐变。"));
	}

	private int inputRow(GuiGraphicsExtractor graphics, int x, int width, int y,
			String label, Focus field, String hint) {
		graphics.text(font, label, x + 2, y + 6, 0xFF9AB6B2);
		int inputX = x + 87;
		int inputWidth = Math.max(35, width - (field == Focus.COLOR ? 120 : 95));
		drawInput(graphics, inputX, y, inputWidth, field, value(field), hint);
		hits.add(new Hit(inputX, y, inputX + inputWidth, y + 20, true,
				() -> selectAt(field, lastClickX, inputX, inputWidth)));
		return y + 28;
	}

	private int choiceRow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int width, int y, String label, String value, Runnable action) {
		graphics.text(font, label, x + 2, y + 6, 0xFF9AB6B2);
		button(graphics, mouseX, mouseY, x + 87, y, width - 95, 20,
				value + "  ⌄", 0xFF263B48, 0xFF345A66, true, action);
		return y + 28;
	}

	private int note(GuiGraphicsExtractor graphics, int x, int y, String message) {
		graphics.text(font, font.plainSubstrByWidth(message, Math.max(40, windowWidth - 37)),
				x + 2, y + 2, 0xFF789D99);
		return y + 18;
	}

	private void button(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int y, int width, int height, String label, int base, int hover,
			boolean detail, Runnable action) {
		drawButton(graphics, mouseX, mouseY, x, y, width, height, label, base, hover);
		hits.add(new Hit(x, y, x + width, y + height, detail, action));
	}

	private void renderPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int y, int width) {
		graphics.text(font, LuaUiText.text("分段预览 · 点击选中"), x, y, 0xFF83C7BF);
		graphics.fill(x, y + 13, x + width, y + 36, 0xFF080D12);
		graphics.outline(x, y + 13, width, 23, 0xFF324B52);
		graphics.enableScissor(x + 3, y + 14, x + width - 3, y + 35);
		int drawX = x + 7;
		String tooltip = null;
		for (int index = 0; index < segments.size() && drawX < x + width - 7; index++) {
			LuaTextJsonDocument.Segment segment = segments.get(index);
			int startX = drawX;
			String preview = segment.previewText();
			List<String> graphemes = LuaTextJsonDocument.graphemes(preview);
			List<Integer> gradient = null;
			if (segment.gradient && segment.kind == LuaTextJsonDocument.Kind.TEXT) {
				try { gradient = LuaTextJsonDocument.gradientColors(segment.gradientStops, graphemes.size()); }
				catch (IllegalArgumentException ignored) { }
			}
			for (int character = 0; character < graphemes.size() && drawX < x + width - 7; character++) {
				String value = graphemes.get(character);
				int color = gradient == null ? safeRgb(firstColorText(segment)) : gradient.get(character);
				Component styled = Component.literal(value).withStyle(previewStyle(segment, color));
				graphics.text(font, styled, drawX, y + 21, 0xFFFFFFFF);
				drawX += font.width(styled);
			}
			int segmentIndex = index;
			hits.add(new Hit(startX, y + 15, Math.max(startX + 4, drawX), y + 35,
					false, () -> selectSegment(segmentIndex)));
			if (inside(mouseX, mouseY, startX, y + 15, drawX, y + 35)) {
				tooltip = "第 " + (index + 1) + " 段";
				if (segment.hover != LuaTextJsonDocument.Hover.NONE)
					tooltip += " · 悬浮：" + segment.hover.label + " " + hoverSummary(segment);
				if (segment.click != LuaTextJsonDocument.Click.NONE)
					tooltip += " · 点击：" + segment.click.label;
			}
		}
		graphics.disableScissor();
		if (tooltip != null) {
			int popupWidth = Math.min(width, font.width(tooltip) + 12);
			int popupX = Math.min(Math.max(x, mouseX + 7), x + width - popupWidth);
			graphics.fill(popupX, y - 15, popupX + popupWidth, y + 1, 0xFF314957);
			graphics.text(font, font.plainSubstrByWidth(tooltip, popupWidth - 8),
					popupX + 4, y - 11, 0xFFE5F6F2);
		}
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (colorPickerOpen) {
			if (event.key() == InputConstants.KEY_ESCAPE) colorPickerOpen = false;
			else if (event.key() == InputConstants.KEY_RETURN) insertPickedColor();
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			close();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			if (focusIsVisible()) insertTyped(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " "));
			return true;
		}
		if (event.key() == InputConstants.KEY_TAB) {
			Focus[] fields = visibleFields();
			if (fields.length == 0) return true;
			int index = java.util.Arrays.asList(fields).indexOf(focus);
			focus = fields[(index + 1) % fields.length];
			cursor = activeValue().length();
			return true;
		}
		if (!focusIsVisible()) return true;
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
		if (colorPickerOpen) return true;
		if (!focusIsVisible()) return true;
		int codepoint = event.codepoint();
		if (!Character.isISOControl(codepoint)) insertTyped(new String(Character.toChars(codepoint)));
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !pointInside(mouseX, mouseY)) return false;
		if (colorPickerOpen) return true;
		if (inside(mouseX, mouseY, sidebarLeft, sidebarListTop, sidebarRight, sidebarListBottom)) {
			segmentScroll = clamp(segmentScroll - (int) Math.round(amount * SEGMENT_ROW_HEIGHT),
					0, Math.max(0, segments.size() * SEGMENT_ROW_HEIGHT
							- (sidebarListBottom - sidebarListTop)));
			return true;
		}
		if (mouseY >= detailTop && mouseY < detailBottom) {
			int limit = Math.max(0, detailContentHeight - (detailBottom - detailTop));
			detailScroll = clamp(detailScroll - (int) Math.round(amount * 27), 0, limit);
		}
		return true;
	}

	boolean mouseClicked(MouseButtonEvent event) {
		if (!open || progress < 0.5F) return false;
		if (colorPickerOpen) return colorPickerClicked(event.x(), event.y());
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
		lastClickX = event.x();
		if (inside(event.x(), event.y(), sidebarLeft, sidebarListTop,
				sidebarRight, sidebarListBottom)) {
			int row = ((int) event.y() - sidebarListTop + segmentScroll) / SEGMENT_ROW_HEIGHT;
			if (row >= 0 && row < segments.size()) selectSegment(row);
			return true;
		}
		for (int index = hits.size() - 1; index >= 0; index--) {
			Hit hit = hits.get(index);
			if (hit.detail && (event.y() < detailTop || event.y() >= detailBottom)) continue;
			if (inside(event.x(), event.y(), hit.left, hit.top, hit.right, hit.bottom)) {
				hit.action.run();
				return true;
			}
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (pickerDragMode != 0) {
			updatePickerFromMouse(event.x(), event.y());
			return true;
		}
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
		pickerDragMode = 0;
		dragging = false;
		resizeEdges = 0;
		return true;
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
		LuaTextJsonDocument.Segment segment = selected();
		switch (index) {
			case 0 -> segment.bold = !segment.bold;
			case 1 -> segment.italic = !segment.italic;
			case 2 -> segment.underlined = !segment.underlined;
			case 3 -> segment.strikethrough = !segment.strikethrough;
			case 4 -> segment.obfuscated = !segment.obfuscated;
			default -> { }
		}
	}

	private LuaTextJsonDocument.Segment selected() { return segments.get(selectedIndex); }

	private void selectSegment(int index) {
		selectedIndex = Math.floorMod(index, segments.size());
		detailScroll = 0;
		int visible = Math.max(1, sidebarListBottom - sidebarListTop);
		int top = selectedIndex * SEGMENT_ROW_HEIGHT;
		if (top < segmentScroll) segmentScroll = top;
		else if (top + SEGMENT_ROW_HEIGHT > segmentScroll + visible)
			segmentScroll = top + SEGMENT_ROW_HEIGHT - visible;
		focus = firstFocus();
		cursor = activeValue().length();
		status = "正在编辑第 " + (selectedIndex + 1) + " / " + segments.size() + " 段";
	}

	private void addSegment() {
		LuaTextJsonDocument.Segment segment = new LuaTextJsonDocument.Segment();
		segment.text = "";
		segments.add(selectedIndex + 1, segment);
		selectSegment(selectedIndex + 1);
	}

	private void duplicateSegment() {
		segments.add(selectedIndex + 1, selected().copy());
		selectSegment(selectedIndex + 1);
	}

	private void deleteSegment() {
		if (segments.size() == 1) { resetSelectedSegment(); return; }
		segments.remove(selectedIndex);
		selectSegment(Math.min(selectedIndex, segments.size() - 1));
	}

	private void resetSelectedSegment() {
		segments.set(selectedIndex, new LuaTextJsonDocument.Segment());
		selectSegment(selectedIndex);
		status = "已重置当前片段，其余片段未修改";
	}

	private void moveSegment(int offset) {
		int target = selectedIndex + offset;
		if (target < 0 || target >= segments.size()) return;
		java.util.Collections.swap(segments, selectedIndex, target);
		selectSegment(target);
	}

	private Focus firstFocus() {
		Focus[] fields = visibleFields();
		return fields.length == 0 ? Focus.TEXT : fields[0];
	}

	private boolean focusIsVisible() {
		for (Focus field : visibleFields()) if (field == focus) return true;
		return false;
	}

	private Focus[] visibleFields() {
		return switch (tab) {
			case CONTENT -> switch (selected().kind) {
				case TEXT -> new Focus[]{Focus.TEXT};
				case SCORE -> new Focus[]{Focus.SCORE_NAME, Focus.SCORE_OBJECTIVE};
				case SELECTOR -> new Focus[]{Focus.SELECTOR, Focus.SEPARATOR};
				case TRANSLATE -> new Focus[]{Focus.TRANSLATE, Focus.FALLBACK};
				case KEYBIND -> new Focus[]{Focus.KEYBIND};
				case LUA_VARIABLE -> new Focus[]{Focus.LUA_VARIABLE};
			};
			case STYLE -> new Focus[]{Focus.COLOR, Focus.FONT};
			case INTERACTION -> {
				List<Focus> fields = new ArrayList<>();
				switch (selected().hover) {
					case TEXT -> fields.add(Focus.HOVER_TEXT);
					case ITEM -> { fields.add(Focus.HOVER_ITEM); fields.add(Focus.HOVER_COUNT); }
					case ENTITY -> { fields.add(Focus.HOVER_ENTITY_ID); fields.add(Focus.HOVER_ENTITY_UUID);
						fields.add(Focus.HOVER_ENTITY_NAME); }
					case NONE -> { }
				}
				if (selected().click != LuaTextJsonDocument.Click.NONE) fields.add(Focus.CLICK_VALUE);
				yield fields.toArray(Focus[]::new);
			}
			case GRADIENT -> new Focus[]{Focus.GRADIENT_STOPS};
		};
	}

	private void seedClickValue(LuaTextJsonDocument.Segment segment) {
		segment.clickValue = switch (segment.click) {
			case NONE -> "";
			case RUN_COMMAND, SUGGEST_COMMAND -> "/help";
			case OPEN_URL -> "https://minecraft.net";
			case COPY -> segment.previewText();
			case CHANGE_PAGE -> "1";
			case SHOW_DIALOG -> "minecraft:server_links";
		};
		focus = Focus.CLICK_VALUE;
		cursor = segment.clickValue.length();
	}

	private void insertTyped(String value) {
		if (value == null || value.isEmpty()) return;
		String current = activeValue();
		setActiveValue(current.substring(0, cursor) + value + current.substring(cursor));
		cursor += value.length();
	}

	private String activeValue() {
		return value(focus);
	}

	private String value(Focus field) {
		LuaTextJsonDocument.Segment segment = selected();
		return switch (field) {
			case TEXT -> segment.text;
			case SCORE_NAME -> segment.scoreName;
			case SCORE_OBJECTIVE -> segment.scoreObjective;
			case SELECTOR -> segment.selector;
			case SEPARATOR -> segment.separator;
			case TRANSLATE -> segment.translate;
			case FALLBACK -> segment.fallback;
			case KEYBIND -> segment.keybind;
			case LUA_VARIABLE -> segment.luaVariable;
			case COLOR -> segment.color;
			case FONT -> segment.fontId;
			case HOVER_TEXT -> segment.hoverText;
			case HOVER_ITEM -> segment.hoverItem;
			case HOVER_COUNT -> segment.hoverCount;
			case HOVER_ENTITY_ID -> segment.hoverEntityType;
			case HOVER_ENTITY_UUID -> segment.hoverEntityUuid;
			case HOVER_ENTITY_NAME -> segment.hoverEntityName;
			case CLICK_VALUE -> segment.clickValue;
			case GRADIENT_STOPS -> segment.gradientStops;
		};
	}

	private void setActiveValue(String value) {
		LuaTextJsonDocument.Segment segment = selected();
		switch (focus) {
			case TEXT -> segment.text = value;
			case SCORE_NAME -> segment.scoreName = value;
			case SCORE_OBJECTIVE -> segment.scoreObjective = value;
			case SELECTOR -> segment.selector = value;
			case SEPARATOR -> segment.separator = value;
			case TRANSLATE -> segment.translate = value;
			case FALLBACK -> segment.fallback = value;
			case KEYBIND -> segment.keybind = value;
			case LUA_VARIABLE -> segment.luaVariable = value;
			case COLOR -> setFirstColor(segment, value);
			case FONT -> segment.fontId = value;
			case HOVER_TEXT -> segment.hoverText = value;
			case HOVER_ITEM -> segment.hoverItem = value;
			case HOVER_COUNT -> segment.hoverCount = value;
			case HOVER_ENTITY_ID -> segment.hoverEntityType = value;
			case HOVER_ENTITY_UUID -> segment.hoverEntityUuid = value;
			case HOVER_ENTITY_NAME -> segment.hoverEntityName = value;
			case CLICK_VALUE -> segment.clickValue = value;
			case GRADIENT_STOPS -> { segment.gradientStops = value; syncFirstColor(segment); }
		}
	}

	private static String firstColorText(LuaTextJsonDocument.Segment segment) {
		String[] values = segment.gradientStops.strip().split("[，,;；\\s]+", 2);
		return values.length == 0 ? "" : values[0];
	}

	private static void setFirstColor(LuaTextJsonDocument.Segment segment, String value) {
		segment.color = value;
		String stops = segment.gradientStops;
		String first = firstColorText(segment);
		if (stops.isBlank() || first.isBlank()) segment.gradientStops = value;
		else segment.gradientStops = value + stops.substring(stops.length()
				- stops.stripLeading().length() + first.length());
	}

	private static void syncFirstColor(LuaTextJsonDocument.Segment segment) {
		segment.color = firstColorText(segment);
	}

	private void openColorPicker() {
		int color = safeRgb(firstColorText(selected()));
		float[] hsb = java.awt.Color.RGBtoHSB((color >> 16) & 255,
				(color >> 8) & 255, color & 255, null);
		pickerHue = hsb[0];
		pickerSaturation = hsb[1];
		pickerBrightness = hsb[2];
		pickerDragMode = 0;
		colorPickerOpen = true;
	}

	private void renderColorPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelX();
		graphics.fill(left + 2, windowY + TITLE_HEIGHT, left + windowWidth - 2,
				windowY + windowHeight - 2, 0xC9000000);
		pickerWidth = Math.min(318, windowWidth - 20);
		pickerHeight = Math.min(220, windowHeight - 16);
		pickerX = left + (windowWidth - pickerWidth) / 2;
		pickerY = windowY + (windowHeight - pickerHeight) / 2;
		graphics.fill(pickerX, pickerY, pickerX + pickerWidth, pickerY + pickerHeight, 0xFF20313B);
		graphics.outline(pickerX, pickerY, pickerWidth, pickerHeight, 0xFF79D8CD);
		graphics.fill(pickerX + 1, pickerY + 1, pickerX + pickerWidth - 1, pickerY + 22, 0xFF30535A);
		graphics.text(font, LuaUiText.text("选择颜色 · RGB 调色板"), pickerX + 8, pickerY + 7, 0xFFFFFFFF);
		drawButton(graphics, mouseX, mouseY, pickerX + pickerWidth - 22,
				pickerY + 2, 19, 18, "×", 0xFF5A3C47, 0xFF864457);
		pickerSquareX = pickerX + 11;
		pickerSquareY = pickerY + 29;
		pickerSquareSize = Math.min(145, pickerHeight - 81);
		int step = 3;
		for (int yy = 0; yy < pickerSquareSize; yy += step) {
			float brightness = 1F - (float) yy / Math.max(1, pickerSquareSize - 1);
			for (int xx = 0; xx < pickerSquareSize; xx += step) {
				float saturation = (float) xx / Math.max(1, pickerSquareSize - 1);
				int color = java.awt.Color.HSBtoRGB(pickerHue, saturation, brightness);
				graphics.fill(pickerSquareX + xx, pickerSquareY + yy,
						pickerSquareX + Math.min(pickerSquareSize, xx + step),
						pickerSquareY + Math.min(pickerSquareSize, yy + step), color);
			}
		}
		graphics.outline(pickerSquareX, pickerSquareY, pickerSquareSize, pickerSquareSize, 0xFFB6D0CE);
		int markerX = pickerSquareX + Math.round(pickerSaturation * (pickerSquareSize - 1));
		int markerY = pickerSquareY + Math.round((1F - pickerBrightness) * (pickerSquareSize - 1));
		graphics.outline(markerX - 3, markerY - 3, 7, 7, 0xFF000000);
		graphics.outline(markerX - 2, markerY - 2, 5, 5, 0xFFFFFFFF);
		pickerHueX = pickerSquareX + pickerSquareSize + 10;
		for (int yy = 0; yy < pickerSquareSize; yy += 2) {
			graphics.fill(pickerHueX, pickerSquareY + yy, pickerHueX + 13,
					pickerSquareY + Math.min(pickerSquareSize, yy + 2),
					java.awt.Color.HSBtoRGB((float) yy / pickerSquareSize, 1F, 1F));
		}
		int hueMarker = pickerSquareY + Math.round(pickerHue * (pickerSquareSize - 1));
		graphics.outline(pickerHueX - 2, hueMarker - 2, 17, 5, 0xFFFFFFFF);
		int infoX = pickerHueX + 22;
		int rgb = java.awt.Color.HSBtoRGB(pickerHue, pickerSaturation, pickerBrightness) & 0xFFFFFF;
		String hex = String.format(java.util.Locale.ROOT, "#%06X", rgb);
		graphics.text(font, LuaUiText.text("当前颜色"), infoX, pickerSquareY + 2, 0xFFA9C9C5);
		graphics.fill(infoX, pickerSquareY + 17, pickerX + pickerWidth - 11,
				pickerSquareY + 43, 0xFF000000 | rgb);
		graphics.outline(infoX, pickerSquareY + 17, pickerX + pickerWidth - 11 - infoX,
				26, 0xFFE4F1EF);
		graphics.text(font, hex, infoX, pickerSquareY + 50, 0xFFFFFFFF);
		graphics.text(font, "R " + ((rgb >> 16) & 255), infoX, pickerSquareY + 66, 0xFFFFA3A3);
		graphics.text(font, "G " + ((rgb >> 8) & 255), infoX, pickerSquareY + 80, 0xFFAAFFAB);
		graphics.text(font, "B " + (rgb & 255), infoX, pickerSquareY + 94, 0xFFA5C8FF);
		graphics.text(font, LuaUiText.text("拖动色板与色相条"), pickerX + 11,
				pickerY + pickerHeight - 48, 0xFFB2CBC8);
		int buttonY = pickerY + pickerHeight - 27;
		drawButton(graphics, mouseX, mouseY, pickerX + pickerWidth - 154,
				buttonY, 72, 21, LuaUiText.text("取消"), 0xFF42515B, 0xFF5A6C77);
		drawButton(graphics, mouseX, mouseY, pickerX + pickerWidth - 77,
				buttonY, 69, 21, LuaUiText.text("插入颜色"), 0xFF286A62, 0xFF3B877C);
	}

	private boolean colorPickerClicked(double mouseX, double mouseY) {
		if (inside(mouseX, mouseY, pickerX + pickerWidth - 22, pickerY + 2,
				pickerX + pickerWidth - 3, pickerY + 20)
				|| inside(mouseX, mouseY, pickerX + pickerWidth - 154,
					pickerY + pickerHeight - 27, pickerX + pickerWidth - 82,
					pickerY + pickerHeight - 6)) {
			colorPickerOpen = false;
			return true;
		}
		if (inside(mouseX, mouseY, pickerX + pickerWidth - 77,
				pickerY + pickerHeight - 27, pickerX + pickerWidth - 8,
				pickerY + pickerHeight - 6)) {
			insertPickedColor();
			return true;
		}
		if (inside(mouseX, mouseY, pickerSquareX, pickerSquareY,
				pickerSquareX + pickerSquareSize, pickerSquareY + pickerSquareSize)) pickerDragMode = 1;
		else if (inside(mouseX, mouseY, pickerHueX, pickerSquareY,
				pickerHueX + 13, pickerSquareY + pickerSquareSize)) pickerDragMode = 2;
		if (pickerDragMode != 0) updatePickerFromMouse(mouseX, mouseY);
		return true;
	}

	private void updatePickerFromMouse(double mouseX, double mouseY) {
		if (pickerDragMode == 1) {
			pickerSaturation = Math.max(0F, Math.min(1F,
					(float) (mouseX - pickerSquareX) / Math.max(1, pickerSquareSize - 1)));
			pickerBrightness = 1F - Math.max(0F, Math.min(1F,
					(float) (mouseY - pickerSquareY) / Math.max(1, pickerSquareSize - 1)));
		} else if (pickerDragMode == 2) {
			pickerHue = Math.max(0F, Math.min(1F,
					(float) (mouseY - pickerSquareY) / Math.max(1, pickerSquareSize - 1)));
		}
	}

	private void insertPickedColor() {
		String picked = String.format(java.util.Locale.ROOT, "#%06X",
				java.awt.Color.HSBtoRGB(pickerHue, pickerSaturation, pickerBrightness) & 0xFFFFFF);
		LuaTextJsonDocument.Segment segment = selected();
		if (!segment.gradient || segment.gradientStops.isBlank()) {
			setFirstColor(segment, picked);
			focus = Focus.GRADIENT_STOPS;
			cursor = picked.length();
		} else {
			String current = segment.gradientStops;
			int position = focus == Focus.GRADIENT_STOPS ? clamp(cursor, 0, current.length()) : current.length();
			if (position > 0 && position < current.length()
					&& !isStopSeparator(current.charAt(position - 1))
					&& !isStopSeparator(current.charAt(position))) {
				while (position < current.length() && !isStopSeparator(current.charAt(position))) position++;
			}
			String before = current.substring(0, position);
			String after = current.substring(position);
			String prefix = before.isBlank() || before.matches("(?s).*[，,;；\\s]$") ? "" : ", ";
			String suffix = after.isBlank() || after.matches("(?s)^[，,;；\\s].*") ? "" : ", ";
			segment.gradientStops = before + prefix + picked + suffix + after;
			focus = Focus.GRADIENT_STOPS;
			cursor = before.length() + prefix.length() + picked.length();
			syncFirstColor(segment);
		}
		colorPickerOpen = false;
		pickerDragMode = 0;
		status = "已插入颜色 " + picked + (segment.gradient ? " · 可继续添加色标" : " · 已同步样式颜色");
	}

	private static boolean isStopSeparator(char value) {
		return value == ',' || value == '，' || value == ';' || value == '；'
				|| Character.isWhitespace(value);
	}

	private static int safeRgb(String value) {
		try { return LuaTextJsonDocument.rgb(value); }
		catch (IllegalArgumentException ignored) { return 0xFFFFFF; }
	}

	private Style previewStyle(LuaTextJsonDocument.Segment segment, int rgb) {
		Style style = Style.EMPTY.withColor(TextColor.fromRgb(rgb))
				.withBold(segment.bold).withItalic(segment.italic).withUnderlined(segment.underlined)
				.withStrikethrough(segment.strikethrough).withObfuscated(segment.obfuscated);
		Identifier id = Identifier.tryParse(segment.fontId.strip());
		if (id != null) style = style.withFont(new FontDescription.Resource(id));
		return style;
	}

	private static String hoverSummary(LuaTextJsonDocument.Segment segment) {
		return switch (segment.hover) {
			case NONE -> "";
			case TEXT -> segment.hoverText;
			case ITEM -> segment.hoverItem;
			case ENTITY -> segment.hoverEntityType;
		};
	}

	private String generatedJsonSafe() {
		try { return LuaTextJsonDocument.hasVariables(segments)
				? LuaTextJsonDocument.luaExpression(segments) : LuaTextJsonDocument.build(segments).toString(); }
		catch (IllegalArgumentException error) { return LuaUiText.text("错误：") + error.getMessage(); }
	}

	private void insertJson() {
		try {
			validatedJson();
			insertion.accept(LuaTextJsonDocument.luaExpression(segments));
			status = "已插入由 " + segments.size() + " 段组成的"
					+ (LuaTextJsonDocument.hasVariables(segments) ? " Lua 动态文本拼接表达式" : "文本 JSON 字符串");
			feedback.accept(status);
		} catch (IllegalArgumentException error) {
			status = LuaUiText.text("错误：") + error.getMessage(); feedback.accept(status);
		}
	}

	private void copyJson() {
		try {
			String json = validatedJson();
			boolean dynamic = LuaTextJsonDocument.hasVariables(segments);
			minecraft.keyboardHandler.setClipboard(dynamic ? LuaTextJsonDocument.luaExpression(segments) : json);
			status = dynamic ? "已复制 Lua 动态文本拼接表达式（变量在运行时读取）" : "已复制原始 Minecraft 文本组件 JSON";
			feedback.accept(status);
		} catch (IllegalArgumentException error) {
			status = LuaUiText.text("错误：") + error.getMessage(); feedback.accept(status);
		}
	}

	private String validatedJson() {
		var json = LuaTextJsonDocument.build(segments);
		if (minecraft.level != null) {
			var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
					minecraft.level.registryAccess());
			var parsed = net.minecraft.network.chat.ComponentSerialization.CODEC.parse(ops, json);
			if (parsed.result().isEmpty())
				throw new IllegalArgumentException("游戏无法解析文本组件：" + parsed.error()
						.map(Object::toString).orElse("未知格式错误"));
		}
		return json.toString();
	}

	private static <T extends Enum<T>> T next(T value) {
		T[] choices = value.getDeclaringClass().getEnumConstants();
		return choices[(value.ordinal() + 1) % choices.length];
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

	private enum Tab {
		CONTENT("内容"), STYLE(LuaUiText.text("样式")), INTERACTION("交互"), GRADIENT(LuaUiText.text("颜色"));
		final String label;
		Tab(String label) { this.label = label; }
	}
	private enum Focus {
		TEXT, SCORE_NAME, SCORE_OBJECTIVE, SELECTOR, SEPARATOR, TRANSLATE, FALLBACK, KEYBIND, LUA_VARIABLE,
		COLOR, FONT, HOVER_TEXT, HOVER_ITEM, HOVER_COUNT, HOVER_ENTITY_ID, HOVER_ENTITY_UUID,
		HOVER_ENTITY_NAME, CLICK_VALUE, GRADIENT_STOPS
	}
	private record Hit(int left, int top, int right, int bottom, boolean detail, Runnable action) { }
	private record PaletteColor(String label, String hex) { }
}
