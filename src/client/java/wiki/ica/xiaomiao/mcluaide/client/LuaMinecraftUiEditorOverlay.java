package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Visual editor for the vanilla Minecraft 26.1 dialog definition consumed by MC.UI. */
final class LuaMinecraftUiEditorOverlay {
    private int uiDrag;
    private float dragCarryX, dragCarryY;
    private List<String> savedTemplates = List.of();
    private boolean templatesLoaded;
    private static int abgr(int r, int g, int b) { return 0xFF000000 | b << 16 | g << 8 | r; }
    private void refreshTemplates() {
        templatesLoaded = true;
        try (var stream = Files.isDirectory(TEMPLATE_DIRECTORY) ? Files.list(TEMPLATE_DIRECTORY) : java.util.stream.Stream.<Path>empty()) {
            savedTemplates = stream.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - 5)).filter(name -> SAFE_TEMPLATE.matcher(name).matches()).sorted().toList();
        } catch (IOException ignored) { savedTemplates = List.of(); }
    }
    private static String partHelp(PartType type) {
        return switch (type) {
            case BODY -> "正文：只显示文字，不产生数据";
            case TEXT -> "文本框：玩家可输入文字；“默认值”为初始文字";
            case BOOLEAN -> "开关：默认值只能是 true 或 false";
            case NUMBER -> "数值：默认值必须在最小值与最大值之间";
            case SELECT -> "选择：用逗号分隔选项，第一项为默认";
            case BUTTON -> "按钮：“默认值”用作鼠标悬停提示，可留空";
        };
    }
    private void drawPartPreview(imgui.ImDrawList draw, UiPart p, float x, float y, float w, float h, boolean selected) {
        int text = abgr(235, 235, 235), dim = abgr(160, 160, 160), box = abgr(24, 24, 28), line = abgr(110, 110, 120);
        draw.pushClipRect(x, y, x + w, y + h, true);
        float lineHeight = imgui.ImGui.getTextLineHeight(), mid = y + Math.max(0, (h - lineHeight) / 2);
        switch (p.type) {
            case BODY -> draw.addText(x + 2, y + 2, text, p.label);
            case TEXT -> {
                float labelWidth = Math.min(w * .4f, imgui.ImGui.calcTextSize(p.label).x + 8);
                draw.addText(x + 2, mid, dim, p.label);
                draw.addRectFilled(x + labelWidth, y, x + w, y + h, box, 2);
                draw.addRect(x + labelWidth, y, x + w, y + h, line, 2);
                draw.addText(x + labelWidth + 4, mid, text, p.value);
            }
            case BOOLEAN -> {
                float side = Math.min(h, 18), top = y + (h - side) / 2;
                draw.addRectFilled(x, top, x + side, top + side, box, 2); draw.addRect(x, top, x + side, top + side, line, 2);
                if (Boolean.parseBoolean(p.value.strip())) draw.addRectFilled(x + 4, top + 4, x + side - 4, top + side - 4, abgr(90, 200, 120), 1);
                draw.addText(x + side + 6, mid, text, p.label);
            }
            case NUMBER -> {
                draw.addRectFilled(x, y, x + w, y + h, box, 2); draw.addRect(x, y, x + w, y + h, line, 2);
                try {
                    double min = Double.parseDouble(p.minimum), max = Double.parseDouble(p.maximum), value = Double.parseDouble(p.value);
                    float ratio = max > min ? (float) Math.clamp((value - min) / (max - min), 0, 1) : 0;
                    draw.addRectFilled(x + 1, y + 1, x + 1 + (w - 2) * ratio, y + h - 1, abgr(70, 120, 200), 2);
                } catch (NumberFormatException ignored) { }
                String shown = p.label + ": " + p.value; draw.addText(x + (w - imgui.ImGui.calcTextSize(shown).x) / 2, mid, text, shown);
            }
            case SELECT -> {
                draw.addRectFilled(x, y, x + w, y + h, box, 2); draw.addRect(x, y, x + w, y + h, line, 2);
                String first = p.value.split("[,，]", -1)[0].strip();
                draw.addText(x + 6, mid, text, p.label + ": " + first); draw.addText(x + w - 14, mid, dim, "v");
            }
            case BUTTON -> {
                draw.addRectFilled(x, y, x + w, y + h, abgr(72, 72, 82), 3); draw.addRect(x, y, x + w, y + h, line, 3);
                draw.addText(x + (w - imgui.ImGui.calcTextSize(p.label).x) / 2, mid, text, p.label);
            }
        }
        draw.popClipRect();
        if (selected) {
            int accent = abgr(255, 180, 60);
            draw.addRect(x - 1, y - 1, x + w + 1, y + h + 1, accent, 0, 0, 2);
            draw.addRectFilled(x + w - 8, y + h - 8, x + w + 3, y + h + 3, accent);
        }
    }
    void renderImGui() {
        if (!templatesLoaded) refreshTemplates();
        // Template row: pick a saved template, or type a new name and save.
        imgui.ImGui.setNextItemWidth(Math.max(120, imgui.ImGui.getContentRegionAvailX() * .35f));
        if (imgui.ImGui.beginCombo("##templates", templateName)) {
            for (String name : savedTemplates) if (imgui.ImGui.selectable(name, name.equals(templateName))) { templateName = name; loadTemplate(); }
            imgui.ImGui.endCombo();
        }
        imgui.ImGui.sameLine(); NativeUi.action("刷新模板", this::refreshTemplates);
        imgui.ImGui.sameLine(); NativeUi.action("新建", this::newBlank);
        imgui.ImGui.sameLine(); NativeUi.action("保存模板", () -> { saveTemplate(); refreshTemplates(); });
        imgui.ImGui.sameLine(); NativeUi.action("检查", () -> { if (generatedLuaFields() != null) feedback.accept(status); });
        imgui.ImGui.sameLine(); NativeUi.action("插入", () -> { String lua = generatedLuaFields(); if (lua != null) insertion.accept(lua); });
        imgui.ImGui.sameLine(); NativeUi.action("复制", () -> { String lua = generatedLuaFields(); if (lua != null) NativeUi.copy(lua); });
        templateName = NativeUi.field("模板名（保存或读取）", templateName);
        if (imgui.ImGui.collapsingHeader(LuaUiText.text("界面属性"), imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            title = NativeUi.field("标题", title); uiId = NativeUi.field("UI ID", uiId);
            dialogType = NativeUi.combo("类型", dialogType, List.of(DialogType.values()), v -> v.label);
            canClose = NativeUi.check("允许 Esc 关闭", canClose); imgui.ImGui.sameLine(); pause = NativeUi.check("暂停游戏", pause);
        }
        if (imgui.ImGui.beginTable("designer", 3, imgui.flag.ImGuiTableFlags.Resizable | imgui.flag.ImGuiTableFlags.BordersInnerV)) {
            imgui.ImGui.tableSetupColumn("palette", imgui.flag.ImGuiTableColumnFlags.WidthFixed, 110);
            imgui.ImGui.tableSetupColumn("canvas"); imgui.ImGui.tableSetupColumn("properties", imgui.flag.ImGuiTableColumnFlags.WidthFixed, 210);
            imgui.ImGui.tableNextColumn();
            imgui.ImGui.textDisabled(LuaUiText.text("添加控件"));
            for (var type : PartType.values()) { NativeUi.action(type.label, () -> addPart(type)); NativeUi.hint(partHelp(type)); }
            imgui.ImGui.separator(); imgui.ImGui.textDisabled(LuaUiText.text("控件列表（后者在上层）"));
            for (int i = 0; i < parts.size(); i++) {
                imgui.ImGui.pushID(i);
                if (imgui.ImGui.selectable(parts.get(i).id + "  [" + parts.get(i).type.label + "]", selectedPart == i)) selectPart(i);
                imgui.ImGui.popID();
            }
            imgui.ImGui.tableNextColumn();
            float x = imgui.ImGui.getCursorScreenPosX(), y = imgui.ImGui.getCursorScreenPosY(), w = Math.max(100, imgui.ImGui.getContentRegionAvailX()), h = Math.max(160, w * CANVAS_HEIGHT / CANVAS_WIDTH);
            float sx = w / CANVAS_WIDTH, sy = h / CANVAS_HEIGHT;
            imgui.ImGui.invisibleButton("canvas", w, h);
            boolean hovered = imgui.ImGui.isItemHovered(), active = imgui.ImGui.isItemActive(), focused = imgui.ImGui.isItemFocused();
            var draw = imgui.ImGui.getWindowDrawList();
            draw.addRectFilled(x, y, x + w, y + h, abgr(40, 34, 30));
            for (int gx = 40; gx < CANVAS_WIDTH; gx += 40) draw.addLine(x + gx * sx, y, x + gx * sx, y + h, abgr(52, 46, 42));
            for (int gy = 40; gy < CANVAS_HEIGHT; gy += 40) draw.addLine(x, y + gy * sy, x + w, y + gy * sy, abgr(52, 46, 42));
            draw.addRect(x, y, x + w, y + h, abgr(120, 120, 130));
            float mouseX = (imgui.ImGui.getMousePosX() - x) / sx, mouseY = (imgui.ImGui.getMousePosY() - y) / sy;
            if (hovered && imgui.ImGui.isMouseClicked(0)) {
                var current = selected();
                if (current != null && Math.abs(mouseX - (current.x + current.width)) <= 10 && Math.abs(mouseY - (current.y + current.height)) <= 10) { uiDrag = 2; dragCarryX = dragCarryY = 0; }
                else {
                    int hit = -1;
                    for (int i = parts.size() - 1; i >= 0 && hit < 0; i--) { var p = parts.get(i); if (mouseX >= p.x && mouseX < p.x + p.width && mouseY >= p.y && mouseY < p.y + p.height) hit = i; }
                    if (hit != selectedPart) selectPart(hit);
                    uiDrag = hit >= 0 ? 1 : 0; dragCarryX = dragCarryY = 0;
                }
            }
            if (!active || !imgui.ImGui.isMouseDown(0)) uiDrag = 0;
            var dragging = selected();
            if (uiDrag != 0 && dragging != null && imgui.ImGui.isMouseDragging(0, 0)) {
                dragCarryX += imgui.ImGui.getIO().getMouseDeltaX() / sx; dragCarryY += imgui.ImGui.getIO().getMouseDeltaY() / sy;
                int stepX = (int) dragCarryX, stepY = (int) dragCarryY; dragCarryX -= stepX; dragCarryY -= stepY;
                if (uiDrag == 1) { dragging.x += stepX; dragging.y += stepY; } else { dragging.width += stepX; dragging.height += stepY; }
                clampPart(dragging);
            }
            if (dragging != null && (focused || hovered) && !imgui.ImGui.getIO().getWantTextInput()) {
                int step = imgui.ImGui.getIO().getKeyShift() ? 10 : 1;
                if (imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.LeftArrow)) { dragging.x -= step; clampPart(dragging); }
                if (imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.RightArrow)) { dragging.x += step; clampPart(dragging); }
                if (imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.UpArrow)) { dragging.y -= step; clampPart(dragging); }
                if (imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.DownArrow)) { dragging.y += step; clampPart(dragging); }
                if (imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.Delete)) removePart();
            }
            draw.pushClipRect(x, y, x + w, y + h, true);
            for (int i = 0; i < parts.size(); i++) { var p = parts.get(i); drawPartPreview(draw, p, x + p.x * sx, y + p.y * sy, p.width * sx, p.height * sy, i == selectedPart); }
            draw.popClipRect();
            imgui.ImGui.textDisabled(LuaUiText.text("拖动移动 · 拖右下角方块调整大小 · 方向键微调（Shift ×10）· Delete 删除"));
            imgui.ImGui.tableNextColumn();
            var p = selected();
            if (p == null) imgui.ImGui.textWrapped(LuaUiText.text("点击画布上的控件，或在左侧列表选择，即可编辑属性。"));
            else {
                imgui.ImGui.pushID(selectedPart);
                imgui.ImGui.textColored(0xFFFFC887, LuaUiText.text(p.type.label));
                imgui.ImGui.textWrapped(LuaUiText.text(partHelp(p.type)));
                p.id = NativeUi.field("组件 ID", p.id);
                p.label = NativeUi.field(p.type == PartType.BODY ? "正文" : "显示文字", p.label);
                if (p.type != PartType.BODY) {
                    p.value = NativeUi.field(switch (p.type) { case SELECT -> "选项（逗号分隔）"; case BUTTON -> "悬停提示"; default -> "默认值"; }, p.value);
                    if (p.type == PartType.BOOLEAN) { boolean on = NativeUi.check("默认开启", Boolean.parseBoolean(p.value.strip())); p.value = Boolean.toString(on); }
                }
                if (p.type == PartType.NUMBER) { p.minimum = NativeUi.field("最小值", p.minimum); p.maximum = NativeUi.field("最大值", p.maximum); }
                imgui.ImGui.separator();
                int[] box = {p.x, p.y, p.width, p.height};
                imgui.ImGui.textUnformatted("X / Y"); imgui.ImGui.setNextItemWidth(-1); imgui.ImGui.dragInt2("##xy", box, 1, 0, CANVAS_WIDTH);
                imgui.ImGui.textUnformatted(LuaUiText.text("宽度 / 高度")); imgui.ImGui.setNextItemWidth(-1);
                int[] size = {box[2], box[3]}; imgui.ImGui.dragInt2("##size", size, 1, 8, CANVAS_WIDTH);
                p.x = box[0]; p.y = box[1]; p.width = size[0]; p.height = size[1]; clampPart(p);
                NativeUi.action("复制组件", this::duplicatePart); imgui.ImGui.sameLine(); NativeUi.action("删除", this::removePart);
                NativeUi.action("上移一层", () -> movePart(1)); imgui.ImGui.sameLine(); NativeUi.action("下移一层", () -> movePart(-1));
                imgui.ImGui.popID();
            }
            imgui.ImGui.endTable();
        }
        NativeUi.status(status);
    }
	private static final int TITLE_HEIGHT = 28;
	private static final int RESIZE_MARGIN = 6;
	private static final int MIN_WIDTH = 580;
	private static final int MIN_HEIGHT = 410;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;
	private static final Pattern SAFE_TEMPLATE = Pattern.compile("[A-Za-z0-9_\\-\\u4e00-\\u9fff]{1,48}");
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	private static final Path TEMPLATE_DIRECTORY = FabricLoader.getInstance().getConfigDir()
			.resolve("mc_lua_ide").resolve("ui_templates");

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<String> insertion;
	private final Consumer<String> feedback;
	private final List<UiPart> parts = new ArrayList<>();
	private String templateName = "default";
	private String uiId = "custom_menu";
	private String title = LuaUiText.text("自定义界面");
	private DialogType dialogType = DialogType.FORM;
	private boolean canClose = true;
	private boolean pause;
	private int selectedPart = -1;
	private int inspectorScroll;
	private int paletteScroll;
	private boolean movingPart;
	private boolean sizingPart;
	private boolean editingInput;
	private double partGrabX;
	private double partGrabY;
	private final Map<Focus, EditBox> fields = new EnumMap<>(Focus.class);
	private boolean syncingFields;
	private static final int CANVAS_WIDTH = 640;
	private static final int CANVAS_HEIGHT = 360;
	private Focus focus = Focus.TITLE;

	private String status = "左侧点击添加 · 画布拖动 · 右下角调整大小 · 右侧编辑属性";
	private boolean open;
	private boolean keyboardFocused;
	private float progress;
	private int screenWidth;
	private int screenHeight;
	private int windowX = -1;
	private int windowY = 16;
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
	private int floatingY = 16;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();

	LuaMinecraftUiEditorOverlay(Minecraft minecraft, Font font, Consumer<String> insertion,
			Consumer<String> feedback) {
		this.minecraft = minecraft;
		this.font = font;
		this.insertion = insertion;
		this.feedback = feedback;
		parts.add(new UiPart(PartType.BODY, "body", "欢迎使用自定义界面", ""));
		parts.add(new UiPart(PartType.BUTTON, "ok", "确定", ""));
		parts.get(0).x = 160; parts.get(0).y = 64;
		parts.get(1).x = 240; parts.get(1).y = 232; parts.get(1).width = 160;
		for (Focus field : Focus.values()) {
			EditBox box = new EditBox(font, 0, 0, 80, 20, Component.literal(field.label));
			box.setMaxLength(field == Focus.PART_VALUE ? 8192 : 256);
			box.setResponder(value -> { if (!syncingFields) updateField(field, value); });
			fields.put(field, box);
		}
		syncFields();
	}

	void resize(int width, int height) { screenWidth = width; screenHeight = height; ensureGeometry(); }
	void open() { open = true; keyboardFocused = true; }
	/** Ctrl+Enter: true when the generated Lua was inserted. */
	boolean insertNow() { String lua = generatedLuaFields(); if (lua == null) return false; insertion.accept(lua); return true; }
	void close() { open = false; keyboardFocused = false; dragging = false; resizeEdges = 0; movingPart = false; sizingPart = false; editingInput = false; blurFields(); }
	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F; }
	boolean contains(double x, double y) { return isVisible() && pointInside(x, y); }
	boolean isInteracting() { return dragging || resizeEdges != 0 || movingPart || sizingPart || editingInput; }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }
	void releaseKeyboardFocus() { keyboardFocused = false; blurFields(); }
	void requestKeyboardFocus() { if (open) keyboardFocused = true; }


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
		if (dockSide.docked()) boundsAnimation.animateTo(windowX, windowY, windowWidth, windowHeight,
				x, y, width, height);
	}

	void tick() {
		float target = open ? 1.0F : 0.0F;
		progress += (target - progress) * 0.28F;
		if (Math.abs(target - progress) < 0.01F) progress = target;
		if (!dragging && resizeEdges == 0) {
			WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(windowX, windowY, windowWidth, windowHeight);
			windowX = bounds.x(); windowY = bounds.y(); windowWidth = bounds.width(); windowHeight = bounds.height();
		}
	}

	// Rendering and hit testing share the same rectangles, including inspector scrolling.
	private EditorLayout layout() {
		int left = panelX() + 8, right = panelX() + windowWidth - 8;
		int top = windowY + 62, bottom = Math.max(top + 24, windowY + windowHeight - 66);
		int paletteWidth = Math.min(72, Math.max(44, windowWidth / 8));
		int inspectorWidth = Math.min(176, Math.max(112, windowWidth / 3));
		return new EditorLayout(new Rect(left, top, paletteWidth, bottom - top),
				new Rect(left + paletteWidth + 6, top, Math.max(24, right - left - paletteWidth - inspectorWidth - 12), bottom - top),
				new Rect(right - inspectorWidth, top, inspectorWidth, bottom - top));
	}

	private List<InputField> inputFields(EditorLayout l) {
		List<InputField> result = new ArrayList<>();
		result.add(new InputField(new Rect(panelX() + 10, windowY + 34, Math.max(32, windowWidth - 130), 20), Focus.TEMPLATE, false));
		int x = l.inspector.x + 7, width = l.inspector.width - 14, y = l.inspector.y + 40 - inspectorScroll;
		Focus[] properties = selected() == null
				? new Focus[]{Focus.UI_ID, Focus.TITLE}
				: selected().type == PartType.NUMBER
				? new Focus[]{Focus.PART_ID, Focus.PART_LABEL, Focus.PART_VALUE, Focus.PART_MIN, Focus.PART_MAX,
					Focus.PART_X, Focus.PART_Y, Focus.PART_WIDTH, Focus.PART_HEIGHT}
				: new Focus[]{Focus.PART_ID, Focus.PART_LABEL, Focus.PART_VALUE,
					Focus.PART_X, Focus.PART_Y, Focus.PART_WIDTH, Focus.PART_HEIGHT};
		for (Focus field : properties) {
			result.add(new InputField(new Rect(x, y + 11, width, 20), field, true));
			y += 38;
		}
		return result;
	}

	private List<Control> controls(EditorLayout l) {
		List<Control> controls = new ArrayList<>();
		int right = panelX() + windowWidth - 10;
		controls.add(new Control(new Rect(right - 104, windowY + 34, 50, 20), LuaUiText.text("保存"), this::saveTemplate));
		controls.add(new Control(new Rect(right - 50, windowY + 34, 50, 20), LuaUiText.text("读取"), this::loadTemplate));
		int y = l.palette.y + 20 - paletteScroll;
		for (PartType type : PartType.values()) {
			controls.add(new Control(new Rect(l.palette.x + 3, y, l.palette.width - 6, 22), "+ " + type.label, () -> addPart(type),
					new Rect(l.palette.x + 1, l.palette.y + 19, l.palette.width - 2, Math.max(1, l.palette.height - 20))));
			y += 27;
		}
		int x = l.inspector.x + 7, width = l.inspector.width - 14;
		controls.add(new Control(new Rect(x, l.inspector.y + 19, width, 18),
				selected() == null ? "界面属性" : "← 界面属性", () -> selectPart(-1)));
		y = l.inspector.y + 40 - inspectorScroll;
		if (selected() == null) {
			y += 2 * 38;
			controls.add(new Control(new Rect(x, y, width, 22), "类型：" + dialogType.label,
					() -> dialogType = DialogType.values()[(dialogType.ordinal() + 1) % DialogType.values().length], inspectorContent(l)));
			controls.add(new Control(new Rect(x, y + 28, width, 22), (canClose ? "✓ " : "□ ") + "ESC 关闭",
					() -> canClose = !canClose, inspectorContent(l)));
			controls.add(new Control(new Rect(x, y + 56, width, 22), (pause ? "✓ " : "□ ") + "暂停游戏",
					() -> pause = !pause, inspectorContent(l)));
		} else {
			y += (selected().type == PartType.NUMBER ? 9 : 7) * 38;
			controls.add(new Control(new Rect(x, y, width, 22), LuaUiText.text("复制此控件"), this::duplicatePart, inspectorContent(l)));
			controls.add(new Control(new Rect(x, y + 28, width / 2 - 2, 22), "下移层级", () -> movePart(-1), inspectorContent(l)));
			controls.add(new Control(new Rect(x + width / 2 + 2, y + 28, width / 2 - 2, 22), "上移层级", () -> movePart(1), inspectorContent(l)));
			controls.add(new Control(new Rect(x, y + 56, width, 22), "删除此控件", this::removePart, inspectorContent(l)));
		}
		int footerX = panelX() + 10, footerWidth = Math.max(20, (windowWidth - 28) / 3);
		int footerY = windowY + windowHeight - 31;
		controls.add(new Control(new Rect(footerX, footerY, footerWidth, 21), "插入 UI 内容", () -> {
			String code = generatedLuaFields(); if (code != null) insertion.accept(code);
		}));
		controls.add(new Control(new Rect(footerX + footerWidth + 4, footerY, footerWidth, 21), "复制 UI 内容", () -> {
			String code = generatedLuaFields(); if (code != null) { minecraft.keyboardHandler.setClipboard(code); status = "已复制 UI 表内代码"; }
		}));
		controls.add(new Control(new Rect(footerX + (footerWidth + 4) * 2, footerY, footerWidth, 21), "新建空白", this::newBlank));
		return controls;
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isVisible()) return;
		ensureGeometry();
		int x = panelX(), right = x + windowWidth, bottom = windowY + windowHeight;
		graphics.fill(x - 4, windowY + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(x, windowY, right, bottom, 0xFD101720);
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFF62B7FF);
		graphics.enableScissor(x + 1, windowY + 1, right - 1, bottom - 1);
		graphics.fill(x + 1, windowY + 1, right - 1, windowY + TITLE_HEIGHT, 0xFF17334D);
		graphics.text(font, LuaUiText.text("▣ Minecraft UI 编辑器"), x + 10, windowY + 9, 0xFFA9D9FF);
		drawButton(graphics, mouseX, mouseY, right - 26, windowY + 4, 20, 20, "×", 0xFF28455C, 0xFFB83A46);
		EditorLayout l = layout();
		clampScroll(l);
		for (Rect panel : List.of(l.palette, l.inspector)) {
			graphics.fill(panel.x, panel.y, panel.right(), panel.bottom(), 0xFF17212D);
			graphics.outline(panel.x, panel.y, panel.width, panel.height, 0xFF344B60);
		}
		drawCanvas(graphics, l);
		for (Control control : controls(l)) {
			Rect clip = control.clip;
			if (clip != null) graphics.enableScissor(clip.x, clip.y, clip.right(), clip.bottom());
			drawButton(graphics, mouseX, mouseY, control.bounds.x, control.bounds.y, control.bounds.width,
					control.bounds.height, control.label, 0xFF293F52, 0xFF3D6685);
			if (clip != null) graphics.disableScissor();
		}
		for (InputField field : inputFields(l)) {
			if (field.inspector) {
				Rect clip = inspectorContent(l);
				graphics.enableScissor(clip.x, clip.y, clip.right(), clip.bottom());
				String label = field.focus == Focus.PART_VALUE && selected() != null ? selected().type.hint : field.focus.label;
				graphics.text(font, font.plainSubstrByWidth(label, field.bounds.width), field.bounds.x, field.bounds.y - 10, 0xFF9CB4C8);
			}
			EditBox box = fields.get(field.focus);
			box.setX(field.bounds.x); box.setY(field.bounds.y); box.setWidth(field.bounds.width);
			box.extractRenderState(graphics, mouseX, mouseY, 0);
			if (field.inspector) graphics.disableScissor();
		}
		// Fixed headings cover scrolled content only; the clip above prevents hidden controls receiving clicks.
		graphics.text(font, LuaUiText.text("控件"), l.palette.x + 5, l.palette.y + 6, 0xFFBFDFFF);
		graphics.text(font, selected() == null ? "界面设置" : selected().type.label + " 属性",
				l.inspector.x + 7, l.inspector.y + 6, 0xFFBFDFFF);
		Rect content = inspectorContent(l);
		int total = inspectorHeight();
		if (total > content.height) {
			int thumb = Math.max(12, content.height * content.height / total);
			int offset = inspectorScroll * Math.max(0, content.height - thumb) / Math.max(1, total - content.height);
			graphics.fill(l.inspector.right() - 3, content.y + offset, l.inspector.right() - 1, content.y + offset + thumb, 0xFF659CC4);
		}
		graphics.text(font, font.plainSubstrByWidth("原版 UI 自动排版；画布位置保存在模板中，导出按坐标排序。", windowWidth - 20),
				x + 10, bottom - 59, 0xFFE1B976);
		graphics.text(font, font.plainSubstrByWidth(status, windowWidth - 20), x + 10, bottom - 44,
				status.startsWith(LuaUiText.text("错误")) ? 0xFFFF7777 : 0xFF8FAEC4);
		graphics.disableScissor();
		ResizeBorderHint.render(graphics, x, windowY, right, bottom,
				resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY), 0xFFA9D9FF);
	}

	private void drawCanvas(GuiGraphicsExtractor graphics, EditorLayout l) {
		Rect viewport = l.canvas;
		graphics.fill(viewport.x, viewport.y, viewport.right(), viewport.bottom(), 0xFF090F17);
		graphics.enableScissor(viewport.x, viewport.y, viewport.right(), viewport.bottom());
		graphics.text(font, font.plainSubstrByWidth("画布 640 × 360 · 拖动布局", viewport.width - 8), viewport.x + 4, viewport.y + 6, 0xFF7E9CB3);
		Rect canvas = canvasRect(l);
		double scale = canvasScale(l);
		graphics.fill(canvas.x, canvas.y, canvas.right(), canvas.bottom(), 0xFF202936);
		for (int cx = 0; cx <= CANVAS_WIDTH; cx += 16) {
			int gx = canvas.x + (int) Math.round(cx * scale);
			graphics.fill(gx, canvas.y, gx + 1, canvas.bottom(), 0xFF283545);
		}
		for (int cy = 0; cy <= CANVAS_HEIGHT; cy += 16) {
			int gy = canvas.y + (int) Math.round(cy * scale);
			graphics.fill(canvas.x, gy, canvas.right(), gy + 1, 0xFF283545);
		}
		graphics.outline(canvas.x, canvas.y, canvas.width, canvas.height, 0xFF49627A);
		for (int i = 0; i < parts.size(); i++) {
			UiPart part = parts.get(i);
			Rect r = partRect(part, l);
			graphics.enableScissor(r.x, r.y, r.right(), r.bottom());
			int base = switch (part.type) {
				case BODY -> 0xFF26384A; case BUTTON -> 0xFF486482;
				case BOOLEAN -> 0xFF2A544A; default -> 0xFF101923;
			};
			graphics.fill(r.x, r.y, r.right(), r.bottom(), base);
			String text = switch (part.type) {
				case BOOLEAN -> (Boolean.parseBoolean(part.value) ? "☑ " : "□ ") + part.label;
				case TEXT -> part.label + ": " + (part.value.isEmpty() ? "..." : part.value);
				case SELECT -> part.label + " ▾";
				case NUMBER -> part.label + ": " + part.value;
				default -> part.label;
			};
			graphics.text(font, font.plainSubstrByWidth(text, Math.max(0, r.width - 6)), r.x + 3,
					r.y + Math.max(2, (r.height - 8) / 2), 0xFFE4EDF5);
			if (part.type == PartType.NUMBER && r.height > 12) {
				graphics.fill(r.x + 3, r.bottom() - 4, r.right() - 3, r.bottom() - 2, 0xFF4D779A);
			}
			graphics.disableScissor();
			graphics.outline(r.x, r.y, r.width, r.height, i == selectedPart ? 0xFF6ECBFF : 0xFF54697D);
			if (i == selectedPart) graphics.fill(r.right() - 5, r.bottom() - 5, r.right(), r.bottom(), 0xFF90DEFF);
		}
		if (parts.isEmpty()) graphics.text(font, font.plainSubstrByWidth("← 点击左侧控件添加", canvas.width - 8), canvas.x + 4, canvas.y + 8, 0xFF89A7BE);
		graphics.disableScissor();
	}

	private double canvasScale(EditorLayout l) {
		return Math.max(0.01, Math.min((l.canvas.width - 12.0) / CANVAS_WIDTH, (l.canvas.height - 30.0) / CANVAS_HEIGHT));
	}
	private Rect canvasRect(EditorLayout l) {
		double scale = canvasScale(l);
		int width = Math.max(1, (int) Math.round(CANVAS_WIDTH * scale));
		int height = Math.max(1, (int) Math.round(CANVAS_HEIGHT * scale));
		return new Rect(l.canvas.x + (l.canvas.width - width) / 2,
				l.canvas.y + 23 + Math.max(0, (l.canvas.height - 26 - height) / 2), width, height);
	}
	private Rect partRect(UiPart part, EditorLayout l) {
		Rect canvas = canvasRect(l);
		double scale = canvasScale(l);
		return new Rect(canvas.x + (int) Math.round(part.x * scale), canvas.y + (int) Math.round(part.y * scale),
				Math.max(1, (int) Math.round(part.width * scale)), Math.max(1, (int) Math.round(part.height * scale)));
	}
	private Rect inspectorContent(EditorLayout l) {
		return new Rect(l.inspector.x + 1, l.inspector.y + 39, l.inspector.width - 2, Math.max(1, l.inspector.height - 40));
	}
	private int inspectorHeight() { return selected() == null ? 160 : (selected().type == PartType.NUMBER ? 9 : 7) * 38 + 84; }
	private void clampScroll(EditorLayout l) {
		inspectorScroll = clamp(inspectorScroll, 0, Math.max(0, inspectorHeight() - inspectorContent(l).height));
		paletteScroll = clamp(paletteScroll, 0, Math.max(0, PartType.values().length * 27 - (l.palette.height - 22)));
	}

	boolean mouseClicked(MouseButtonEvent event) {
		if (!open || progress < 0.5F || !pointInside(event.x(), event.y())) return false;
		if (event.button() != 0) return true;
		int x = panelX(), right = x + windowWidth;
		if (inside(event.x(), event.y(), right - 26, windowY + 4, right - 6, windowY + 24)) { close(); return true; }
		keyboardFocused = true;
		int edges = resizeEdgesAt(event.x(), event.y());
		if (edges != 0) {
			resizeEdges = edges; resizeStartMouseX = event.x(); resizeStartMouseY = event.y();
			resizeStartX = x; resizeStartY = windowY; resizeStartWidth = windowWidth; resizeStartHeight = windowHeight;
			return true;
		}
		if (event.y() < windowY + TITLE_HEIGHT) {
			if (dockSide.docked() && layoutLocked) return true;
			dragging = true; dragOffsetX = event.x() - x; dragOffsetY = event.y() - windowY; return true;
		}
		EditorLayout l = layout();
		clampScroll(l);
		for (InputField field : inputFields(l)) {
			if (!field.bounds.contains(event.x(), event.y())
					|| field.inspector && !inspectorContent(l).contains(event.x(), event.y())) continue;
			blurFields();
			focus = field.focus;
			EditBox box = fields.get(focus);
			box.setX(field.bounds.x); box.setY(field.bounds.y); box.setWidth(field.bounds.width);
			box.setFocused(true);
			box.mouseClicked(event, false);
			editingInput = true;
			return true;
		}
		blurFields();
		syncFields(); // Commit/clamp numeric properties when leaving an input.
		for (Control control : controls(l)) {
			Rect clip = control.clip;
			if (control.bounds.contains(event.x(), event.y()) && (clip == null || clip.contains(event.x(), event.y()))) {
				control.action.run(); return true;
			}
		}
		if (l.canvas.contains(event.x(), event.y())) {
			for (int index = parts.size() - 1; index >= 0; index--) {
				Rect r = partRect(parts.get(index), l);
				if (!r.contains(event.x(), event.y())) continue;
				selectPart(index);
				UiPart part = selected();
				double scale = canvasScale(l);
				Rect canvas = canvasRect(l);
				partGrabX = (event.x() - canvas.x) / scale - part.x;
				partGrabY = (event.y() - canvas.y) / scale - part.y;
				sizingPart = event.x() >= r.right() - 6 && event.y() >= r.bottom() - 6;
				movingPart = !sizingPart;
				return true;
			}
			selectPart(-1);
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (editingInput) {
			EditBox box = fields.get(focus);
			if (box != null && box.isFocused()) box.mouseDragged(event, 0, 0);
			return true;
		}
		if (movingPart || sizingPart) {
			UiPart part = selected();
			if (part == null) return true;
			EditorLayout l = layout();
			Rect canvas = canvasRect(l);
			double scale = canvasScale(l);
			int px = (int) Math.round((event.x() - canvas.x) / scale);
			int py = (int) Math.round((event.y() - canvas.y) / scale);
			if (sizingPart) {
				part.width = clamp(px - part.x, 24, CANVAS_WIDTH - part.x);
				part.height = clamp(py - part.y, 16, CANVAS_HEIGHT - part.y);
			} else {
				part.x = clamp((int) Math.round(px - partGrabX), 0, CANVAS_WIDTH - part.width);
				part.y = clamp((int) Math.round(py - partGrabY), 0, CANVAS_HEIGHT - part.height);
			}
			syncFields();
			status = part.type.label + "  X=" + part.x + " Y=" + part.y + "  " + part.width + "×" + part.height;
			return true;
		}
		if (resizeEdges != 0) { resizeWindow(event.x(), event.y()); return true; }
		if (!dragging) return false;
		if (dockSide.docked()) undockForDrag(event.x(), event.y());
		windowX = clamp((int) Math.round(event.x() - dragOffsetX), 0, Math.max(0, screenWidth - windowWidth));
		windowY = clamp((int) Math.round(event.y() - dragOffsetY), 0, Math.max(0, screenHeight - windowHeight));
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
		return true;
	}

	boolean mouseReleased() {
		if (!isInteracting()) return false;
		dragging = false; resizeEdges = 0; movingPart = false; sizingPart = false; editingInput = false;
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !pointInside(mouseX, mouseY)) return false;
		EditorLayout l = layout();
		if (l.inspector.contains(mouseX, mouseY)) inspectorScroll -= (int) Math.round(amount * 30);
		else if (l.palette.contains(mouseX, mouseY)) paletteScroll -= (int) Math.round(amount * 27);
		clampScroll(l);
		return true;
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (event.key() == InputConstants.KEY_ESCAPE) { close(); return true; }
		EditBox box = fields.get(focus);
		if (event.key() == InputConstants.KEY_TAB) {
			List<InputField> visible = inputFields(layout());
			int current = -1;
			for (int i = 0; i < visible.size(); i++) if (visible.get(i).focus == focus) current = i;
			InputField next = visible.get(Math.floorMod(current + (event.hasShiftDown() ? -1 : 1), visible.size()));
			blurFields(); focus = next.focus; fields.get(focus).setFocused(true);
			if (next.inspector) {
				Rect clip = inspectorContent(layout());
				if (next.bounds.y < clip.y + 10) inspectorScroll -= clip.y + 10 - next.bounds.y;
				else if (next.bounds.bottom() > clip.bottom()) inspectorScroll += next.bounds.bottom() - clip.bottom();
				clampScroll(layout());
			}
			return true;
		}
		if (box != null && box.isFocused()) {
			if (event.key() == InputConstants.KEY_RETURN) { blurFields(); syncFields(); return true; }
			box.keyPressed(event); return true;
		}
		UiPart part = selected();
		if (part != null) {
			if (event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) removePart();
			else if (event.hasControlDown() && event.key() == InputConstants.KEY_D) duplicatePart();
			else {
				int step = event.hasShiftDown() ? 10 : 1;
				switch (event.key()) {
					case InputConstants.KEY_LEFT -> part.x -= step;
					case InputConstants.KEY_RIGHT -> part.x += step;
					case InputConstants.KEY_UP -> part.y -= step;
					case InputConstants.KEY_DOWN -> part.y += step;
					default -> { return true; }
				}
				clampPart(part); syncFields();
			}
		}
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		EditBox box = fields.get(focus);
		if (box != null && box.isFocused()) box.charTyped(event);
		return true;
	}

	private UiPart selected() { return selectedPart >= 0 && selectedPart < parts.size() ? parts.get(selectedPart) : null; }
	private void selectPart(int index) {
		selectedPart = index; inspectorScroll = 0; blurFields(); syncFields();
		status = index < 0 ? "界面属性 · 点击左侧控件添加" : "拖动控件移动 · 右下角调整大小 · 方向键微调 · Delete 删除";
	}
	private void blurFields() { fields.values().forEach(box -> box.setFocused(false)); }
	private void syncFields() {
		syncingFields = true;
		try { for (Focus field : Focus.values()) fields.get(field).setValue(fieldValue(field)); }
		finally { syncingFields = false; }
	}
	private String fieldValue(Focus field) {
		UiPart part = selected();
		return switch (field) {
			case TEMPLATE -> templateName; case UI_ID -> uiId; case TITLE -> title;
			default -> part == null ? "" : switch (field) {
				case PART_ID -> part.id; case PART_LABEL -> part.label; case PART_VALUE -> part.value;
				case PART_X -> Integer.toString(part.x); case PART_Y -> Integer.toString(part.y);
				case PART_WIDTH -> Integer.toString(part.width); case PART_HEIGHT -> Integer.toString(part.height);
				case PART_MIN -> part.minimum; case PART_MAX -> part.maximum;
				default -> "";
			};
		};
	}
	private void updateField(Focus field, String value) {
		UiPart part = selected();
		switch (field) {
			case TEMPLATE -> templateName = value; case UI_ID -> uiId = value; case TITLE -> title = value;
			default -> {
				if (part == null) return;
				switch (field) {
					case PART_ID -> part.id = value; case PART_LABEL -> part.label = value;
					case PART_VALUE -> part.value = value; case PART_MIN -> part.minimum = value; case PART_MAX -> part.maximum = value;
					default -> {
						try {
							int number = Integer.parseInt(value);
							switch (field) {
								case PART_X -> part.x = number; case PART_Y -> part.y = number;
								case PART_WIDTH -> part.width = number; case PART_HEIGHT -> part.height = number;
								default -> { }
							}
							clampPart(part);
						} catch (NumberFormatException ignored) { /* Keep unfinished input until commit. */ }
					}
				}
			}
		}
	}
	private static void clampPart(UiPart part) {
		part.width = clamp(part.width, 24, CANVAS_WIDTH); part.height = clamp(part.height, 16, CANVAS_HEIGHT);
		part.x = clamp(part.x, 0, CANVAS_WIDTH - part.width); part.y = clamp(part.y, 0, CANVAS_HEIGHT - part.height);
	}
	private String uniqueId(String prefix) {
		int serial = 1;
		while (true) {
			String candidate = prefix + serial++;
			if (parts.stream().noneMatch(part -> part.id.equals(candidate))) return candidate;
		}
	}
	private void duplicatePart() {
		UiPart source = selected();
		if (source == null) return;
		if (parts.size() >= 128) { error(LuaUiText.text("画布最多 128 个控件")); return; }
		UiPart copy = new UiPart(source.type, uniqueId("component"), source.label, source.value);
		copy.x = source.x + 16; copy.y = source.y + 16; copy.width = source.width; copy.height = source.height;
		copy.minimum = source.minimum; copy.maximum = source.maximum; clampPart(copy);
		parts.add(copy); selectPart(parts.size() - 1);
	}

	private record Rect(int x, int y, int width, int height) {
		int right() { return x + width; } int bottom() { return y + height; }
		boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
	}
	private record EditorLayout(Rect palette, Rect canvas, Rect inspector) { }
	private record InputField(Rect bounds, Focus focus, boolean inspector) { }
	private record Control(Rect bounds, String label, Runnable action, Rect clip) {
		Control(Rect bounds, String label, Runnable action) { this(bounds, label, action, null); }
	}

	private void addPart(PartType type) {
		if (parts.size() >= 128) { error(LuaUiText.text("画布最多 128 个控件")); return; }
		int ordinal = parts.size() + 1;
		UiPart part = switch (type) {
			case BODY -> new UiPart(type, "body" + ordinal, "说明文字", "");
			case TEXT -> new UiPart(type, "text" + ordinal, "文本输入", "");
			case BOOLEAN -> new UiPart(type, "toggle" + ordinal, LuaUiText.text("开关"), "false");
			case NUMBER -> new UiPart(type, "number" + ordinal, LuaUiText.text("数值"), "0");
			case SELECT -> new UiPart(type, "select" + ordinal, "选择项", "选项一,选项二");
			case BUTTON -> new UiPart(type, "button" + ordinal, LuaUiText.text("按钮"), "");
		};
		part.id = uniqueId(type.name().toLowerCase(java.util.Locale.ROOT));
		part.x = 48 + (parts.size() % 6) * 32; part.y = 40 + (parts.size() % 7) * 36;
		if (type == PartType.BUTTON) part.width = 160;
		clampPart(part); parts.add(part); selectPart(parts.size() - 1);
	}

	private void movePart(int direction) {
		int target = selectedPart + direction;
		if (selectedPart < 0 || target < 0 || target >= parts.size()) return;
		UiPart part = parts.remove(selectedPart); parts.add(target, part); selectedPart = target;
	}

	private void removePart() {
		if (selectedPart < 0 || selectedPart >= parts.size()) return;
		parts.remove(selectedPart); selectPart(Math.min(selectedPart, parts.size() - 1));
	}

	private void newBlank() {
		templateName = "new_template"; uiId = "custom_menu"; title = LuaUiText.text("自定义界面");
		dialogType = DialogType.FORM; canClose = true; pause = false; parts.clear(); selectedPart = -1;
		selectPart(-1); status = "已新建空白 UI";
	}

	private String generatedLuaFields() {
		List<UiPart> buttons = parts.stream().filter(part -> part.type == PartType.BUTTON).toList();
		if (dialogType == DialogType.NOTICE && buttons.size() != 1) { error("notice 类型必须正好有 1 个按钮"); return null; }
		if (dialogType == DialogType.CONFIRMATION && buttons.size() != 2) { error("confirmation 类型必须正好有 2 个按钮"); return null; }
		Set<String> ids = new HashSet<>();
		int bodies = 0, inputsCount = 0;
		for (UiPart part : parts) {
			if (part.type == PartType.BODY) { bodies++; continue; }
			if (!SAFE_ID.matcher(part.id).matches() || part.id.startsWith("_mc_lua_")) { error(part.type.label + " 的 key/id 格式无效"); return null; }
			if (!ids.add(part.id)) { error("控件标识重复：" + part.id); return null; }
			if (part.type != PartType.BUTTON) inputsCount++;
			if (part.type == PartType.NUMBER) {
				try {
					double minimum = Double.parseDouble(part.minimum), maximum = Double.parseDouble(part.maximum), initial = Double.parseDouble(part.value);
					if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || !Double.isFinite(initial)
							|| minimum >= maximum || initial < minimum || initial > maximum
							|| Math.abs(minimum) > Float.MAX_VALUE || Math.abs(maximum) > Float.MAX_VALUE) throw new NumberFormatException();
				} catch (NumberFormatException error) { error(part.id + "：请输入有效的数值上下限，默认值必须在范围内"); return null; }
			}
			if (part.type == PartType.BOOLEAN && !part.value.equalsIgnoreCase("true") && !part.value.equalsIgnoreCase("false")) {
				error(part.id + "：开关默认值必须为 true 或 false"); return null;
			}
			if (part.type == PartType.SELECT && part.value.split("[,，]").length > 64) { error("选择项最多 64 个"); return null; }
		}
		if (bodies > 32 || inputsCount > 32 || buttons.size() > 64) {
			error("原版 UI 上限：正文 32 段、输入控件 32 个、按钮 64 个"); return null;
		}
		StringBuilder body = new StringBuilder();
		StringBuilder inputs = new StringBuilder();
		StringBuilder actions = new StringBuilder();
		for (UiPart part : parts.stream().sorted(Comparator.comparingInt((UiPart p) -> p.y).thenComparingInt(p -> p.x)).toList()) {
			switch (part.type) {
				case BODY -> append(body, luaString(part.label));
				case BUTTON -> append(actions, "{id=" + luaString(part.id) + ", label=" + luaString(part.label) + ", width=" + part.width + ", tooltip=" + luaString(part.value) + "}");
				case TEXT -> append(inputs, "{type=\"text\", key=" + luaString(part.id) + ", label=" + luaString(part.label) + ", width=" + part.width + ", maxLength=8192, initial=" + luaString(part.value) + "}");
				case BOOLEAN -> append(inputs, "{type=\"boolean\", key=" + luaString(part.id) + ", label=" + luaString(part.label) + ", initial=" + Boolean.parseBoolean(part.value.strip()) + "}");
				case NUMBER -> append(inputs, "{type=\"number\", key=" + luaString(part.id) + ", label=" + luaString(part.label) + ", width=" + part.width + ", min=" + finiteNumber(part.minimum) + ", max=" + finiteNumber(part.maximum) + ", initial=" + finiteNumber(part.value) + "}");
				case SELECT -> {
					String[] options = part.value.split("[,，]", -1);
					StringBuilder optionCode = new StringBuilder();
					for (int index = 0; index < options.length; index++) {
						String option = options[index].strip();
						if (option.isEmpty()) continue;
						append(optionCode, "{id=\"option_" + (index + 1) + "\", text=" + luaString(option)
								+ (index == 0 ? ", initial=true}" : "}"));
					}
					if (optionCode.isEmpty()) optionCode.append("{id=\"option_1\", text=\"选项一\", initial=true}");
					append(inputs, "{type=\"select\", key=" + luaString(part.id) + ", label=" + luaString(part.label) + ", width=" + part.width + ", options={" + optionCode + "}}");
				}
			}
		}
		status = "UI 定义有效，可插入或复制";
		return "type = \"" + dialogType.luaName + "\",\n"
				+ "title = " + luaString(title) + ",\n"
				+ "canCloseWithEscape = " + canClose + ",\n"
				+ "pause = " + pause + ",\n"
				+ (body.isEmpty() ? "" : "body = {" + body + "},\n")
				+ (inputs.isEmpty() ? "" : "inputs = {" + inputs + "},\n")
				+ "buttons = {" + actions + "}";
	}

	private static String finiteNumber(String raw) {
		try {
			double value = Double.parseDouble(raw.strip());
			return Double.isFinite(value) ? Double.toString(value) : "0";
		} catch (NumberFormatException ignored) { return "0"; }
	}

	private static void append(StringBuilder target, String value) {
		if (!target.isEmpty()) target.append(", ");
		target.append(value);
	}

	private static String luaString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\r", "\\r").replace("\n", "\\n") + "\"";
	}

	private void saveTemplate() {
		if (!SAFE_TEMPLATE.matcher(templateName).matches()) { error("模板名只能使用中英文、数字、下划线和连字符，长度 1-48"); return; }
		JsonObject root = new JsonObject();
		root.addProperty("uiId", uiId); root.addProperty("title", title); root.addProperty("type", dialogType.luaName);
		root.addProperty("canClose", canClose); root.addProperty("pause", pause);
		root.addProperty("editorVersion", 2);
		JsonArray array = new JsonArray();
		for (UiPart part : parts) {
			JsonObject item = new JsonObject(); item.addProperty("type", part.type.name());
			item.addProperty("id", part.id); item.addProperty("label", part.label); item.addProperty("value", part.value);
			item.addProperty("x", part.x); item.addProperty("y", part.y);
			item.addProperty("width", part.width); item.addProperty("height", part.height);
			item.addProperty("minimum", part.minimum); item.addProperty("maximum", part.maximum);
			array.add(item);
		}
		root.add("parts", array);
		try {
			Files.createDirectories(TEMPLATE_DIRECTORY);
			Files.writeString(templatePath(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
			status = "已保存模板：" + templateName; feedback.accept(status);
		} catch (IOException error) { error("保存模板失败：" + error.getMessage()); }
	}

	private void loadTemplate() {
		if (!SAFE_TEMPLATE.matcher(templateName).matches()) { error("模板名格式无效"); return; }
		Path path = templatePath();
		if (!Files.isRegularFile(path)) { error("没有找到模板：" + templateName); return; }
		try {
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			String loadedId = root.get("uiId").getAsString(), loadedTitle = root.get("title").getAsString();
			DialogType loadedType = DialogType.from(root.get("type").getAsString());
			boolean loadedCanClose = root.get("canClose").getAsBoolean(), loadedPause = root.get("pause").getAsBoolean();
			List<UiPart> loadedParts = new ArrayList<>();
			if (root.getAsJsonArray("parts").size() > 128) throw new IllegalArgumentException("模板最多 128 个控件");
			for (var element : root.getAsJsonArray("parts")) {
				JsonObject item = element.getAsJsonObject();
				UiPart part = new UiPart(PartType.valueOf(item.get("type").getAsString()), item.get("id").getAsString(),
						item.get("label").getAsString(), item.get("value").getAsString());
				part.x = item.has("x") ? item.get("x").getAsInt() : 80;
				part.y = item.has("y") ? item.get("y").getAsInt() : 24 + loadedParts.size() * 48;
				part.width = item.has("width") ? item.get("width").getAsInt() : 240;
				part.height = item.has("height") ? item.get("height").getAsInt() : 40;
				part.minimum = item.has("minimum") ? item.get("minimum").getAsString() : "0";
				part.maximum = item.has("maximum") ? item.get("maximum").getAsString() : "100";
				clampPart(part); loadedParts.add(part);
			}
			uiId = loadedId; title = loadedTitle; dialogType = loadedType; canClose = loadedCanClose; pause = loadedPause;
			parts.clear(); parts.addAll(loadedParts); selectPart(-1);
			status = "已读取模板：" + templateName; feedback.accept(status);
		} catch (RuntimeException | IOException error) { error("读取模板失败：" + error.getMessage()); }
	}

	private Path templatePath() { return TEMPLATE_DIRECTORY.resolve(templateName + ".json"); }
	private void error(String message) { status = LuaUiText.text("错误：") + message; feedback.accept(status); }

	private void drawButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int y, int width, int height, String label, int base, int hover) {
		width = Math.max(1, width);
		boolean hovered = inside(mouseX, mouseY, x, y, x + width, y + height);
		graphics.fill(x, y, x + width, y + height, hovered ? hover : base);
		graphics.outline(x, y, width, height, hovered ? 0xFFCAE9FF : 0xFF465968);
		graphics.centeredText(font, font.plainSubstrByWidth(label, Math.max(8, width - 5)),
				x + width / 2, y + (height - 8) / 2, 0xFFFFFFFF);
	}

	/** Opt-in game probe: exercise real hit testing, scaled drag, field editing and disk round trips. */
	static LuaMinecraftUiEditorOverlay verifyCanvasEditor(Minecraft client) throws IOException {
		LuaMinecraftUiEditorOverlay editor = new LuaMinecraftUiEditorOverlay(client, client.font, code -> {}, message -> {});
		editor.resize(1100, 550); editor.open();
		for (int i = 0; i < 40; i++) editor.tick();
		Control add = editor.controls(editor.layout()).stream().filter(c -> c.label.equals("+ 文本框")).findFirst().orElseThrow();
		editor.mouseClicked(testMouse(add.bounds.x + 5, add.bounds.y + 5));
		testCheck(editor.parts.size() == 3 && editor.selected().type == PartType.TEXT, "palette click");
		UiPart part = editor.selected();
		Rect original = editor.partRect(part, editor.layout());
		int oldX = part.x, oldY = part.y;
		double scale = editor.canvasScale(editor.layout());
		editor.mouseClicked(testMouse(original.x + 4, original.y + 4));
		editor.mouseDragged(testMouse(original.x + 4 + 40 * scale, original.y + 4 + 24 * scale));
		editor.mouseReleased();
		testCheck(Math.abs(part.x - oldX - 40) <= 2 && Math.abs(part.y - oldY - 24) <= 2, "scaled drag");
		Rect moved = editor.partRect(part, editor.layout());
		int oldWidth = part.width;
		editor.mouseClicked(testMouse(moved.right() - 1, moved.bottom() - 1));
		editor.mouseDragged(testMouse(moved.right() + 40 * scale, moved.bottom() + 16 * scale));
		editor.mouseReleased();
		testCheck(part.width > oldWidth + 30, "resize handle");
		editor.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
		InputField label = editor.inputFields(editor.layout()).stream().filter(f -> f.focus == Focus.PART_LABEL).findFirst().orElseThrow();
		editor.mouseClicked(testMouse(label.bounds.x + 6, label.bounds.y + 8)); editor.mouseReleased();
		editor.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, 2));
		testCheck(editor.fields.get(Focus.PART_LABEL).getHighlighted().equals(part.label), "input select all");
		editor.fields.get(Focus.PART_LABEL).insertText(LuaUiText.text("可编辑文字"));
		testCheck(part.label.equals(LuaUiText.text("可编辑文字")), "input responder");
		editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		editor.mouseScrolled(editor.layout().inspector.x + 12, editor.layout().inspector.y + 60, -30);
		testCheck(editor.inspectorScroll > 0, "inspector scrolling");
		Control duplicate = editor.controls(editor.layout()).stream().filter(c -> c.label.equals(LuaUiText.text("复制此控件"))).findFirst().orElseThrow();
		editor.mouseClicked(testMouse(duplicate.bounds.x + 5, duplicate.bounds.y + 5));
		testCheck(editor.parts.size() == 4 && !editor.selected().id.equals(part.id), "scrolled inspector action");
		int savedX = editor.selected().x, savedWidth = editor.selected().width;
		editor.templateName = "_canvas_probe_" + Long.toUnsignedString(System.nanoTime(), 36);
		Path probePath = editor.templatePath();
		try {
			editor.saveTemplate();
			testCheck(Files.isRegularFile(probePath), "template save");
			editor.parts.clear(); editor.loadTemplate();
			testCheck(editor.parts.size() == 4 && editor.parts.getLast().x == savedX
					&& editor.parts.getLast().width == savedWidth, "template geometry restore");
			String code = editor.generatedLuaFields();
			testCheck(code != null && code.contains("width=" + savedWidth), "exported width");
			testCheck(!code.contains("MC.UI.register"), "exported table fields only");
			String source = "local ui = {}";
			LuaUiDefinitionInsertion.Edit insert = LuaUiDefinitionInsertion.plan(source,
					source.indexOf('{') + 1, source.indexOf('{') + 1, code);
			testCheck(insert.filledExistingTable(), "fill existing ui table");
			String filled = source.substring(0, insert.start()) + insert.text() + source.substring(insert.end());
			org.luaj.vm2.lib.jse.JsePlatform.standardGlobals().load(filled, "canvas-editor-probe");
			testCheck(filled.startsWith("local ui = {\n") && filled.endsWith("\n}"), "keeps existing table braces");
			String assignment = "local ui = ";
			LuaUiDefinitionInsertion.Edit standalone = LuaUiDefinitionInsertion.plan(assignment,
					assignment.length(), assignment.length(), code);
			testCheck(!standalone.filledExistingTable(), "standalone table insertion");
			org.luaj.vm2.lib.jse.JsePlatform.standardGlobals().load(
					assignment + standalone.text(), "canvas-editor-standalone-probe");
		} finally {
			Files.deleteIfExists(probePath);
		}
		editor.templateName = "default";
		editor.selectPart(2);
		editor.status = "画布交互测试通过：添加、拖动、缩放、属性、滚动、模板、导出";
		editor.syncFields();
		return editor;
	}

	private static MouseButtonEvent testMouse(double x, double y) {
		return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
	}
	private static void testCheck(boolean condition, String label) {
		if (!condition) throw new IllegalStateException("UI canvas probe failed: " + label);
	}

	private int panelX() { return Math.round(screenWidth + (windowX - screenWidth) * progress); }
	private boolean pointInside(double x, double y) { int left = panelX(); return inside(x, y, left, windowY, left + windowWidth, windowY + windowHeight); }
	private int resizeEdgesAt(double mouseX, double mouseY) {
		if (dockSide.docked()) return 0;
		int x = panelX(), right = x + windowWidth, bottom = windowY + windowHeight;
		if (mouseX < x - RESIZE_MARGIN || mouseX > right + RESIZE_MARGIN || mouseY < windowY - RESIZE_MARGIN || mouseY > bottom + RESIZE_MARGIN) return 0;
		int edges = 0;
		if (Math.abs(mouseX - x) <= RESIZE_MARGIN) edges |= EDGE_LEFT;
		if (Math.abs(mouseX - right) <= RESIZE_MARGIN) edges |= EDGE_RIGHT;
		if (Math.abs(mouseY - windowY) <= RESIZE_MARGIN) edges |= EDGE_TOP;
		if (Math.abs(mouseY - bottom) <= RESIZE_MARGIN) edges |= EDGE_BOTTOM;
		return edges;
	}

	private void resizeWindow(double mouseX, double mouseY) {
		int dx = (int) Math.round(mouseX - resizeStartMouseX), dy = (int) Math.round(mouseY - resizeStartMouseY);
		int left = resizeStartX, right = resizeStartX + resizeStartWidth, top = resizeStartY, bottom = resizeStartY + resizeStartHeight;
		if ((resizeEdges & EDGE_LEFT) != 0) left = Math.max(0, Math.min(right - MIN_WIDTH, resizeStartX + dx));
		if ((resizeEdges & EDGE_RIGHT) != 0) right = Math.min(screenWidth, Math.max(left + MIN_WIDTH, resizeStartX + resizeStartWidth + dx));
		if ((resizeEdges & EDGE_TOP) != 0) top = Math.max(0, Math.min(bottom - MIN_HEIGHT, resizeStartY + dy));
		if ((resizeEdges & EDGE_BOTTOM) != 0) bottom = Math.min(screenHeight, Math.max(top + MIN_HEIGHT, resizeStartY + resizeStartHeight + dy));
		windowX = left; windowY = top; windowWidth = right - left; windowHeight = bottom - top;
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0 || dockSide.docked()) return;
		if (windowWidth < 0) windowWidth = Math.min(screenWidth - 16, Math.max(MIN_WIDTH, (int) (screenWidth * 0.66F)));
		if (windowHeight < 0) windowHeight = Math.min(screenHeight - 16, Math.max(MIN_HEIGHT, (int) (screenHeight * 0.82F)));
		windowWidth = Math.min(windowWidth, screenWidth); windowHeight = Math.min(windowHeight, screenHeight);
		if (windowX < 0) windowX = Math.max(0, (screenWidth - windowWidth) / 2);
		windowX = clamp(windowX, 0, Math.max(0, screenWidth - windowWidth));
		windowY = clamp(windowY, 0, Math.max(0, screenHeight - windowHeight));
	}

	private void rememberFloatingGeometry() { floatingX = windowX; floatingY = windowY; floatingWidth = windowWidth; floatingHeight = windowHeight; }
	private void restoreFloatingGeometry() {
		if (floatingWidth > 0) windowWidth = floatingWidth; if (floatingHeight > 0) windowHeight = floatingHeight;
		if (floatingX >= 0) windowX = floatingX; windowY = floatingY; ensureGeometry();
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}
	private void undockForDrag(double mouseX, double mouseY) {
		dockSide = DockSide.FLOATING; restoreFloatingGeometry();
		dragOffsetX = Math.min(Math.max(18.0, dragOffsetX), Math.max(18.0, windowWidth - 18.0)); dragOffsetY = 12.0;
		windowX = clamp((int) Math.round(mouseX - dragOffsetX), 0, Math.max(0, screenWidth - windowWidth));
		windowY = clamp((int) Math.round(mouseY - dragOffsetY), 0, Math.max(0, screenHeight - windowHeight));
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
	private static boolean inside(double x, double y, int left, int top, int right, int bottom) { return x >= left && x <= right && y >= top && y <= bottom; }

	private enum Focus {
		TEMPLATE("模板名称"), UI_ID("界面 ID（注册时填写）"), TITLE("界面标题"), PART_ID("标识 key / id"),
		PART_LABEL("文字 / 标签"), PART_VALUE("默认值"), PART_MIN("数值下限"), PART_MAX("数值上限"),
		PART_X("X 坐标"), PART_Y("Y 坐标"), PART_WIDTH(LuaUiText.text("宽度")), PART_HEIGHT(LuaUiText.text("高度"));
		private final String label;
		Focus(String label) { this.label = label; }
	}
	private enum DialogType {
		FORM("表单", "form"), NOTICE("通知", "notice"), CONFIRMATION(LuaUiText.text("确认"), "confirmation");
		private final String label; private final String luaName;
		DialogType(String label, String luaName) { this.label = label; this.luaName = luaName; }
		private static DialogType from(String value) {
			for (DialogType type : values()) if (type.luaName.equalsIgnoreCase(value)) return type;
			return FORM;
		}
	}
	private enum PartType {
		BODY("正文", LuaUiText.text("文字")), TEXT("文本框", LuaUiText.text("默认文字")), BOOLEAN(LuaUiText.text("开关"), "true/false"),
		NUMBER(LuaUiText.text("数值"), "0-100"), SELECT("选择", "选项一,选项二"), BUTTON(LuaUiText.text("按钮"), "提示（可空）");
		private final String label; private final String hint;
		PartType(String label, String hint) { this.label = label; this.hint = hint; }
	}
	private static final class UiPart {
		private final PartType type;
		private String id;
		private String label;
		private String value;
		private int x = 40, y = 40, width = 240, height = 40;
		private String minimum = "0", maximum = "100";
		private UiPart(PartType type, String id, String label, String value) {
			this.type = type; this.id = id; this.label = label; this.value = value;
		}
	}
}
