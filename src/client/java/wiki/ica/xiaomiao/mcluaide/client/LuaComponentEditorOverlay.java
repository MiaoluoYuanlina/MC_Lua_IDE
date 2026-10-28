package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Dockable visual editor for server-authoritative item, block and entity data components. */
final class LuaComponentEditorOverlay {
	void renderImGui() {
		Category next=NativeUi.combo("目标类型",category,List.of(Category.values()),c->c.label);
		if(next!=category){category=next;clearLoadedTarget();refreshTargets(true);}
		targetPaneExpanded=NativeUi.check("选择目标 ID",targetPaneExpanded);
		if(targetPaneExpanded) {
			String text=NativeUi.field("搜索目标",targetQuery);if(!text.equals(targetQuery)){targetQuery=text;refreshTargets(false);}
			imgui.ImGui.beginChild("targets",0,160,true);
			for(int i=0;i<targets.size();i++)if(imgui.ImGui.selectable(targets.get(i).label(),selectedTarget!=null&&selectedTarget.value().equals(targets.get(i).value()))){targetRow=i;loadSelectedTarget(true);}
			imgui.ImGui.endChild();
		} else if(selectedTarget!=null)imgui.ImGui.textUnformatted(selectedTarget.label());
		String text=NativeUi.field("筛选组件",componentQuery);if(!text.equals(componentQuery)){componentQuery=text;filterComponents();}
		boolean all=NativeUi.check("显示全部可用字段",showAll);if(all!=showAll){showAll=all;filterComponents();}
		if(data!=null)treeEditor.renderImGui();else imgui.ImGui.textWrapped(loading?"正在读取游戏组件…":"选择目标以读取可用组件、状态和数据");
		NativeUi.action("插入",()->export(Export.INSERT));imgui.ImGui.sameLine();NativeUi.action(functionDocument.getAsBoolean()?"复制命令格式":"复制 Lua",()->export(Export.COPY_STACK));imgui.ImGui.sameLine();NativeUi.action("复制 JSON",()->export(Export.COPY_JSON));
		if(imgui.ImGui.getIO().getKeyCtrl()&&imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter))export(Export.INSERT);
		NativeUi.status(status);
	}
	private static final int TITLE_HEIGHT = 28;
	private static final int RESIZE_MARGIN = 6;
	private static final int MIN_WIDTH = 520;
	private static final int MIN_HEIGHT = 330;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;
	private static final int ROW_HEIGHT = 20;

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<String> insertion;
	private java.util.function.BooleanSupplier functionDocument = () -> false;
	/** In a .mcfunction file the editor writes command syntax instead of Lua tables. */
	void setFunctionDocument(java.util.function.BooleanSupplier supplier) { this.functionDocument = supplier; }
	private String commandText(JsonObject checked, boolean withId) {
		return switch (data.kind()) {
			case "entity" -> LuaComponentEditorData.commandEntity(checked);
			case "block" -> LuaComponentEditorData.commandBlock(data.requestedId(), checked, withId);
			default -> (withId ? data.itemId() : "") + LuaComponentEditorData.commandComponents(checked);
		};
	}
	private final Consumer<String> feedback;
	private final JsonTreeEditor treeEditor;
	private final Map<Category, List<LuaInsertCatalog.Entry>> catalogs = new EnumMap<>(Category.class);
	private final Map<String, JsonElement> enabledValues = new LinkedHashMap<>();
	private final Map<String, String> valueDrafts = new HashMap<>();
	private final Set<String> invalidValues = new HashSet<>();
	private Category category = Category.ITEM;
	private List<LuaInsertCatalog.Entry> targets = List.of();
	private List<LuaComponentEditorData.ComponentInfo> components = List.of();
	private String targetQuery = "";
	private String componentQuery = "";
	private int targetRow;
	private int targetScroll;
	private LuaInsertCatalog.Entry selectedTarget;
	private boolean targetPaneExpanded = true;
	private int componentRow;
	private int componentScroll;
	private LuaComponentEditorData data;
	private LuaComponentEditorData.ComponentInfo selectedComponent;
	private String valueText = "";
	private int valueCursor;
	private Focus focus = Focus.TARGET_SEARCH;
	private boolean showAll = true;
	private boolean loading;
	private boolean exporting;
	private boolean closeAfterInsert;
	private int openRevision;
	private int requestRevision;
	private String status = LuaUiText.text("选择物品、方块或实体后编辑组件");
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

	LuaComponentEditorOverlay(Minecraft minecraft, Font font, Consumer<String> insertion,
			Consumer<String> feedback) {
		this.minecraft = minecraft;
		this.font = font;
		this.insertion = insertion;
		this.feedback = feedback;
		this.treeEditor = new JsonTreeEditor(minecraft, font, this::applyTreeValue, message -> {
			status = message;
			feedback.accept(message);
		});
	}

	void resize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
		ensureGeometry();
	}

	static LuaComponentEditorOverlay previewTreeForTesting(Minecraft client, LuaComponentEditorData data, String patch) {
		LuaComponentEditorOverlay overlay = new LuaComponentEditorOverlay(client, client.font, ignored -> {}, ignored -> {});
		overlay.data = data; overlay.open = true; overlay.progress = 1; overlay.keyboardFocused = true;
		overlay.targetPaneExpanded = false;
		overlay.selectedTarget = new LuaInsertCatalog.Entry(data.requestedId(), data.name(), "minecraft");
		overlay.componentQuery = "potion";
		JsonObject parsed = JsonParser.parseString(patch).getAsJsonObject();
		parsed.entrySet().forEach(entry -> overlay.enabledValues.put(entry.getKey(), entry.getValue()));
		overlay.treeEditor.setComponentCatalog(data, parsed);
		overlay.filterComponents(); overlay.treeEditor.expandPotionPreview();
		overlay.status = LuaUiText.text("展开字段预览；单击字段编辑；灰色字段可添加");
		return overlay;
	}

	void verifyTreeWindowForTesting() {
		treeEditor.verifyRoutedForestClick(event -> mouseClicked(event, false), this::keyPressed);
		if (enabledValues.get("minecraft:potion_contents").getAsJsonObject().getAsJsonArray("custom_effects")
				.get(0).getAsJsonObject().get("amplifier").getAsInt() != 4) throw new IllegalStateException("编辑结果未传回主窗口");
	}

	void open() {
		open = true;
		openRevision++;
		closeAfterInsert = false;
		keyboardFocused = true;
		targetPaneExpanded = data == null;
		if (targets.isEmpty()) refreshTargets(true);
	}

	void openForContext(LuaInsertCatalog.Kind preferredKind, String preferredId) {
		Category preferredCategory = Category.from(preferredKind);
		if (preferredCategory == null) {
			open();
			closeAfterInsert = false; // stays open so several components can be inserted; close it with its X or Esc
			return;
		}
		open = true;
		openRevision++;
		closeAfterInsert = false;
		keyboardFocused = true;
		category = preferredCategory;
		targetQuery = "";
		targetPaneExpanded = true;
		refreshTargets(false);

		String normalizedId = normalizeResourceId(preferredId);
		int exact = indexOfTarget(normalizedId);
		if (exact >= 0) {
			targetRow = exact;
			ensureTargetVisible();
			loadSelectedTarget(true);
			return;
		}

		clearLoadedTarget();
		if (preferredId != null && !preferredId.isBlank()) {
			targetQuery = preferredId.strip();
			refreshTargets(false);
			status = "已识别为" + category.label + " ID，请从筛选结果中选择";
		} else {
			status = "已识别为" + category.label + " ID，请选择目标";
		}
		focus = Focus.TARGET_SEARCH;
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
		boundsAnimation.animateTo(windowX, windowY, windowWidth, windowHeight, x, y, width, height);
	}

	void releaseKeyboardFocus() {
		keyboardFocused = false;
	}

	void requestKeyboardFocus() {
		if (open) keyboardFocused = true;
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
		graphics.fill(x, windowY, right, bottom, 0xFD10151E);
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFFB388FF);
		graphics.fill(x + 1, windowY + 1, right - 1, windowY + TITLE_HEIGHT, 0xFF302148);
		graphics.text(font, LuaUiText.text("◈  物品 / 方块 / 实体数据编辑器"), x + 10, windowY + 9, 0xFFD7B8FF);
		drawButton(graphics, mouseX, mouseY, right - 26, windowY + 4, 20, 20, "×", 0xFF4B3562, 0xFFB83A46);

		int tabsTop = windowY + 34;
		int tabWidth = Math.max(1, (windowWidth - 20) / Category.values().length);
		for (int index = 0; index < Category.values().length; index++) {
			Category value = Category.values()[index];
			int left = x + 10 + index * tabWidth;
			int tabRight = index == Category.values().length - 1 ? right - 10 : left + tabWidth - 2;
			boolean hovered = inside(mouseX, mouseY, left, tabsTop, tabRight, tabsTop + 20);
			graphics.fill(left, tabsTop, tabRight, tabsTop + 20,
					value == category ? 0xFF69469A : hovered ? 0xFF433158 : 0xFF261E32);
			graphics.centeredText(font, value.label, (left + tabRight) / 2, tabsTop + 6,
					value == category ? 0xFFFFFFFF : 0xFFC9B8D9);
		}

		int contentTop = windowY + 61;
		int contentBottom = bottom - 36;
		int leftWidth = targetPaneWidth();
		int splitX = x + leftWidth;
		if (targetPaneExpanded) {
			renderSearch(graphics, x + 10, contentTop, leftWidth - 16, targetQuery,
					LuaUiText.chinese() ? LuaUiText.text("搜索") + category.label + " ID / 中文 / 首拼…" : "Search IDs or names…", focus == Focus.TARGET_SEARCH);
			int targetListTop = contentTop + 25;
			graphics.fill(x + 10, targetListTop, splitX - 6, contentBottom, 0xFF090C12);
			graphics.outline(x + 10, targetListTop, leftWidth - 16, contentBottom - targetListTop, 0xFF493A5C);
			graphics.enableScissor(x + 11, targetListTop + 1, splitX - 7, contentBottom - 1);
			int targetVisible = targetVisibleRows();
			for (int row = 0; row < targetVisible; row++) {
				int absolute = targetScroll + row;
				if (absolute >= targets.size()) break;
				int y = targetListTop + 2 + row * ROW_HEIGHT;
				if (absolute == targetRow) graphics.fill(x + 12, y - 1, splitX - 8, y + 18, 0xFF3A2850);
				LuaInsertCatalog.Entry entry = targets.get(absolute);
				renderTargetIcon(graphics, entry, x + 14, y);
				graphics.text(font, font.plainSubstrByWidth(entry.label(), leftWidth - 48), x + 35, y + 4,
						absolute == targetRow ? 0xFFFFFFFF : 0xFFD8CCE4);
			}
			graphics.disableScissor();
		} else {
			renderCompactTarget(graphics, mouseX, mouseY, x + 10, contentTop);
		}

		int rx = splitX + 3;
		int rw = right - rx - 10;
		int toggleWidth = 64;
		boolean itemCategory = category == Category.ITEM;
		renderSearch(graphics, rx, contentTop, Math.max(50, rw - (itemCategory ? toggleWidth + 4 : 0)), componentQuery,
				LuaUiText.chinese() ? (itemCategory ? "筛选组件 ID / 中文 / 首拼…" : "筛选状态 / 字段 / 中文…")
						: "Search component IDs, states or fields…", focus == Focus.COMPONENT_SEARCH);
		if (itemCategory) drawButton(graphics, mouseX, mouseY, rx + rw - toggleWidth, contentTop, toggleWidth, 20,
				showAll ? LuaUiText.text("全部组件") : LuaUiText.text("建议组件"), showAll ? 0xFF594078 : 0xFF2E5D50, 0xFF7556A0);

		int componentListTop = contentTop + 25;
		if (loading || data == null) {
			graphics.text(font, font.plainSubstrByWidth(status, rw - 10), rx + 5, componentListTop + 12, 0xFFD7B8FF);
		} else {
			treeEditor.render(graphics, mouseX, mouseY, rx, componentListTop, rw, contentBottom - 15);
			graphics.text(font, font.plainSubstrByWidth(enabledValues.size() +
					(itemCategory ? " 个组件覆盖 · " : " 个字段覆盖 · ") + status, rw - 10),
					rx + 5, contentBottom - 12, status.startsWith(LuaUiText.text("错误")) ? 0xFFFF7777 : 0xFF9F8EB2);
		}
		int buttonY = bottom - 29;
		int available = windowWidth - 20;
		int buttonWidth = Math.max(72, (available - 8) / 3);
		drawButton(graphics, mouseX, mouseY, x + 10, buttonY, buttonWidth, 21,
				exporting ? LuaUiText.text("校验中…") : LuaUiText.text("插入配置"), 0xFF5F3E8D, 0xFF7A55AE);
		drawButton(graphics, mouseX, mouseY, x + 14 + buttonWidth, buttonY, buttonWidth, 21,
				data != null && data.kind().equals("entity") ? LuaUiText.text("复制实体配置")
						: data != null && data.kind().equals("block") ? LuaUiText.text("复制方块配置") : LuaUiText.text("复制物品堆"),
				0xFF2C4D65, 0xFF376783);
		drawButton(graphics, mouseX, mouseY, x + 18 + buttonWidth * 2, buttonY,
				available - buttonWidth * 2 - 8, 21, LuaUiText.text("复制 JSON"), 0xFF2C4D65, 0xFF376783);

		// Tree add/edit and registry-selection dialogs are modal. Draw them in the
		// window's final pass so no footer control can appear above the dialog.
		treeEditor.renderPopupOverlay(graphics, mouseX, mouseY);
		ResizeBorderHint.render(graphics, x, windowY, right, bottom,
				resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY), 0xFFD7B8FF);
	}

	private void renderEditor(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int top, int width, int bottom) {
		graphics.fill(x, top, x + width, bottom, 0xFF121722);
		graphics.outline(x, top, width, bottom - top, 0xFF493A5C);
		if (loading) {
			graphics.centeredText(font, LuaUiText.text("正在从服务器读取组件…"), x + width / 2, top + 18, 0xFFD7B8FF);
			return;
		}
		if (data == null) {
			graphics.centeredText(font, status, x + width / 2, top + 18, 0xFFB8A9C7);
			return;
		}
		String heading = data.name() + "  ·  " + data.requestedId();
		graphics.text(font, font.plainSubstrByWidth(heading, width - 16), x + 7, top + 6, 0xFFE9D9FF);
		graphics.text(font, font.plainSubstrByWidth(data.sourceHint(), width - 16), x + 7, top + 18, 0xFF8F7EA2);
		if (selectedComponent == null) return;
		graphics.text(font, selectedComponent.label() + "  " + selectedComponent.id(), x + 7, top + 33,
				0xFFCDA9FF);
		graphics.text(font, font.plainSubstrByWidth(selectedComponent.description(), width - 16),
				x + 7, top + 45, 0xFFB7B0C0);
		int inputTop = top + 59;
		boolean enabled = enabledValues.containsKey(selectedComponent.id());
		String type = selectedComponent.editor();
		if (type.equals("unit")) {
			drawButton(graphics, mouseX, mouseY, x + 7, inputTop, Math.min(150, width - 14), 20,
					enabled ? LuaUiText.text("✓ 已启用（无参数）") : LuaUiText.text("＋ 启用此组件"), enabled ? 0xFF624196 : 0xFF31404C, 0xFF7654A7);
		} else if (type.equals("boolean")) {
			boolean value = enabled && enabledValues.get(selectedComponent.id()).getAsBoolean();
			drawButton(graphics, mouseX, mouseY, x + 7, inputTop, Math.min(170, width - 14), 20,
					enabled ? LuaUiText.text("值：") + value + LuaUiText.text("（点击切换）") : LuaUiText.text("＋ 启用并设为 true"),
					enabled ? 0xFF624196 : 0xFF31404C, 0xFF7654A7);
		} else if (usesTreeEditor(selectedComponent)) {
			treeEditor.render(graphics, mouseX, mouseY, x + 7, inputTop, width - 14, bottom - 16);
		} else {
			graphics.fill(x + 7, inputTop, x + width - 7, inputTop + 20, 0xFF080B10);
			graphics.outline(x + 7, inputTop, width - 14, 20,
					focus == Focus.VALUE ? 0xFFD0A7FF : 0xFF554666);
			String shown = visibleValue(width - 26);
			graphics.text(font, shown.isEmpty() ? LuaUiText.text("输入值…") : shown,
					x + 12, inputTop + 6, shown.isEmpty() ? 0xFF6F6878 : 0xFFF1E9FA);
		}
		if (!treeEditor.hasPopup()) {
			String summary = enabledValues.size() + (category == Category.ITEM ? " 个组件已启用 · " : " 个字段已启用 · ") + status;
			graphics.text(font, font.plainSubstrByWidth(summary, width - 16), x + 7, bottom - 13,
					status.startsWith(LuaUiText.text("错误")) ? 0xFFFF7777 : 0xFF8F7EA2);
		}
	}

	boolean keyPressed(KeyEvent event) {
		if (!open) return false;
		if (!keyboardFocused) return false;
		if (event.hasControlDown() && event.key() == InputConstants.KEY_RETURN) {
			if (treeEditor.commitPendingEdit()) export(Export.INSERT);
			return true;
		}
		if (treeEditor.hasPopup() && treeEditor.keyPressed(event)) return true;
		if (event.key() == InputConstants.KEY_ESCAPE) {
			close();
			return true;
		}
		if (!keyboardFocused) return false;
		if (focus == Focus.TREE && treeEditor.keyPressed(event)) return true;
		if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			if (focus != Focus.TREE) insertTyped(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " "));
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			backspace();
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE) {
			deleteForward();
			return true;
		}
		if (focus == Focus.VALUE) {
			if (event.key() == InputConstants.KEY_LEFT) valueCursor = Math.max(0, valueCursor - 1);
			else if (event.key() == InputConstants.KEY_RIGHT) valueCursor = Math.min(valueText.length(), valueCursor + 1);
			else if (event.key() == InputConstants.KEY_HOME) valueCursor = 0;
			else if (event.key() == InputConstants.KEY_END) valueCursor = valueText.length();
			else if (event.key() == InputConstants.KEY_RETURN) commitValue();
			else return true;
			return true;
		}
		if (event.key() == InputConstants.KEY_UP) {
			if (focus == Focus.COMPONENT_SEARCH) { focus = Focus.TREE; treeEditor.keyPressed(event); } else moveTarget(-1);
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			if (focus == Focus.COMPONENT_SEARCH) { focus = Focus.TREE; treeEditor.keyPressed(event); } else moveTarget(1);
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			if (focus == Focus.TARGET_SEARCH) loadSelectedTarget(true); else focus = Focus.TREE;
			return true;
		}
		if (event.key() == InputConstants.KEY_TAB) {
			if (targetPaneExpanded) {
				focus = focus == Focus.TARGET_SEARCH ? Focus.COMPONENT_SEARCH : Focus.TARGET_SEARCH;
			} else {
				focus = Focus.COMPONENT_SEARCH;
			}
			return true;
		}
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		if (focus == Focus.TREE) {
			treeEditor.charTyped(event);
			return true;
		}
		int codepoint = event.codepoint();
		if (!Character.isISOControl(codepoint)) insertTyped(new String(Character.toChars(codepoint)));
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open) return false;
		if (treeEditor.hasPopup()) return treeEditor.mouseScrolled(mouseX, mouseY, amount);
		if (!pointInside(mouseX, mouseY)) return false;
		keyboardFocused = true;
		if (treeEditor.mouseScrolled(mouseX, mouseY, amount)) {
			focus = Focus.TREE;
			return true;
		}
		int x = panelX();
		int leftWidth = targetPaneWidth();
		int contentTop = windowY + 61;
		int contentBottom = windowY + windowHeight - 36;
		if (targetPaneExpanded
				&& inside(mouseX, mouseY, x + 10, contentTop + 25, x + leftWidth - 6, contentBottom)) {
			targetScroll = clamp(targetScroll - (int) Math.round(amount * 3), 0, maxTargetScroll());
		} else {
			componentScroll = clamp(componentScroll - (int) Math.round(amount * 3), 0, maxComponentScroll());
		}
		return true;
	}

	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!open || progress < 0.5F) return false;
		if (treeEditor.hasPopup()) {
			treeEditor.mouseClicked(event, doubleClick);
			keyboardFocused = true;
			focus = Focus.TREE;
			// The popup is modal: clicks outside it are consumed instead of reaching
			// copy/insert buttons or other controls below the overlay.
			return true;
		}
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
		int tabsTop = windowY + 34;
		if (event.y() >= tabsTop && event.y() <= tabsTop + 20) {
			int tabWidth = Math.max(1, (windowWidth - 20) / Category.values().length);
			int index = clamp((int) (event.x() - x - 10) / tabWidth, 0, Category.values().length - 1);
			if (event.x() >= x + 10 && event.x() <= right - 10 && category != Category.values()[index]) {
				category = Category.values()[index];
				targetQuery = "";
				targetPaneExpanded = true;
				focus = Focus.TARGET_SEARCH;
				refreshTargets(true);
			}
			return true;
		}
		int contentTop = windowY + 61;
		int contentBottom = bottom - 36;
		int leftWidth = targetPaneWidth();
		int splitX = x + leftWidth;
		if (!targetPaneExpanded && inside(event.x(), event.y(), x + 10, contentTop, x + 36, contentTop + 26)) {
			targetPaneExpanded = true;
			focus = Focus.TARGET_SEARCH;
			return true;
		}
		if (targetPaneExpanded
				&& inside(event.x(), event.y(), x + 10, contentTop, splitX - 6, contentTop + 20)) {
			focus = Focus.TARGET_SEARCH;
			return true;
		}
		int targetIndex = targetScroll + Math.max(0, (int) (event.y() - contentTop - 27) / ROW_HEIGHT);
		if (targetPaneExpanded
				&& inside(event.x(), event.y(), x + 10, contentTop + 25, splitX - 6, contentBottom)
				&& targetIndex < targets.size()) {
			targetRow = targetIndex;
			loadSelectedTarget(true);
			return true;
		}
		int rx = splitX + 3;
		int rw = right - rx - 10;
		int toggleWidth = 64;
		if (inside(event.x(), event.y(), rx, contentTop,
				rx + rw - (category == Category.ITEM ? toggleWidth + 4 : 0), contentTop + 20)) {
			focus = Focus.COMPONENT_SEARCH;
			return true;
		}
		if (category == Category.ITEM && inside(event.x(), event.y(), rx + rw - toggleWidth,
				contentTop, rx + rw, contentTop + 20)) {
			showAll = !showAll;
			filterComponents();
			return true;
		}
		if (data != null && !loading && treeEditor.mouseClicked(event, doubleClick)) {
			focus = Focus.TREE;
			return true;
		}
		int buttonY = bottom - 29;
		int available = windowWidth - 20;
		int buttonWidth = Math.max(72, (available - 8) / 3);
		if (inside(event.x(), event.y(), x + 10, buttonY, x + 10 + buttonWidth, buttonY + 21)) {
			export(Export.INSERT);
			return true;
		}
		if (inside(event.x(), event.y(), x + 14 + buttonWidth, buttonY,
				x + 14 + buttonWidth * 2, buttonY + 21)) {
			export(Export.COPY_STACK);
			return true;
		}
		if (inside(event.x(), event.y(), x + 18 + buttonWidth * 2, buttonY, right - 10, buttonY + 21)) {
			export(Export.COPY_JSON);
			return true;
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

	private void refreshTargets(boolean selectFirst) {
		List<LuaInsertCatalog.Entry> source = catalogs.computeIfAbsent(category,
				key -> LuaInsertCatalog.load(key.catalogKind, minecraft));
		String needle = targetQuery.strip().toLowerCase(Locale.ROOT);
		targets = source.stream().filter(entry -> entry.matches(needle)).toList();
		targetRow = clamp(selectFirst ? 0 : targetRow, 0, Math.max(0, targets.size() - 1));
		targetScroll = 0;
		if (selectFirst && !targets.isEmpty()) loadSelectedTarget(false);
	}

	private void loadSelectedTarget(boolean collapsePicker) {
		if (targetRow < 0 || targetRow >= targets.size()) return;
		LuaInsertCatalog.Entry target = targets.get(targetRow);
		selectedTarget = target;
		if (collapsePicker) {
			targetPaneExpanded = false;
			focus = Focus.COMPONENT_SEARCH;
		}
		int revision = ++requestRevision;
		loading = true;
		data = null;
		components = List.of();
		enabledValues.clear();
		valueDrafts.clear();
		invalidValues.clear();
		status = LuaUiText.text("正在读取 ") + target.value() + "…";
		ScriptManager.componentEditorData(category.id, target.value()).whenComplete((result, failure) ->
				minecraft.execute(() -> {
					if (revision != requestRevision) return;
					loading = false;
					if (failure != null) {
						status = LuaUiText.text("错误：") + ScriptManager.message(failure);
						feedback.accept(status);
						return;
					}
					data = result;
					selectedComponent = null;
					treeEditor.setComponentCatalog(result, new JsonObject());
					status = "已读取 " + result.components().size() + LuaUiText.text(" 个") +
							(result.kind().equals("item") ? LuaUiText.text("物品组件") : LuaUiText.text("可编辑字段"));
					filterComponents();
				}));
	}

	private void clearLoadedTarget() {
		requestRevision++;
		loading = false;
		data = null;
		selectedTarget = null;
		components = List.of();
		selectedComponent = null;
		enabledValues.clear();
		valueDrafts.clear();
		invalidValues.clear();
	}

	private int indexOfTarget(String id) {
		if (id.isEmpty()) return -1;
		for (int index = 0; index < targets.size(); index++) {
			if (targets.get(index).value().equalsIgnoreCase(id)) return index;
		}
		return -1;
	}

	private static String normalizeResourceId(String raw) {
		if (raw == null || raw.isBlank()) return "";
		String value = raw.strip().toLowerCase(Locale.ROOT);
		return value.indexOf(':') < 0 ? "minecraft:" + value : value;
	}

	private void filterComponents() {
		if (data == null) {
			components = List.of();
			return;
		}
		String needle = componentQuery.strip().toLowerCase(Locale.ROOT);
		components = data.components().stream()
				.filter(component -> showAll || component.relevance() > 0)
				.filter(component -> LuaUiText.matchesSearch(component.searchable(), needle))
				.toList();
		componentRow = clamp(componentRow, 0, Math.max(0, components.size() - 1));
		componentScroll = 0;
		treeEditor.filterComponents(components);
	}

	private void selectComponent(int index) {
		commitValue();
		if (index < 0 || index >= components.size()) {
			selectedComponent = null;
			valueText = "";
			return;
		}
		componentRow = index;
		selectedComponent = components.get(index);
		JsonElement value = enabledValues.getOrDefault(selectedComponent.id(), selectedComponent.defaultValue());
		valueText = valueDrafts.getOrDefault(selectedComponent.id(), displayValue(selectedComponent, value));
		valueCursor = valueText.length();
		if (usesTreeEditor(selectedComponent)) {
			treeEditor.setValue(selectedComponent.id(), value, selectedComponent.idHints(), data.componentIdHints());
		}
		ensureComponentVisible();
	}

	private void applyTreeValue(JsonElement value) {
		if (data == null || value == null || !value.isJsonObject()) return;
		enabledValues.clear();
		value.getAsJsonObject().entrySet().forEach(entry -> enabledValues.put(entry.getKey(), entry.getValue().deepCopy()));
		valueDrafts.clear(); invalidValues.clear();
	}

	private void toggleSelectedComponent() {
		if (selectedComponent == null) return;
		if (enabledValues.remove(selectedComponent.id()) == null) {
			enabledValues.put(selectedComponent.id(), selectedComponent.defaultValue().deepCopy());
			valueDrafts.put(selectedComponent.id(), displayValue(selectedComponent, selectedComponent.defaultValue()));
			status = "已启用 " + selectedComponent.label();
		} else {
			valueDrafts.remove(selectedComponent.id());
			invalidValues.remove(selectedComponent.id());
			status = "已停用 " + selectedComponent.label();
		}
		selectComponent(componentRow);
	}

	private void toggleBoolean() {
		if (selectedComponent == null) return;
		JsonElement current = enabledValues.get(selectedComponent.id());
		boolean next = current == null || !current.getAsBoolean();
		enabledValues.put(selectedComponent.id(), new JsonPrimitive(next));
		valueText = Boolean.toString(next);
		valueDrafts.put(selectedComponent.id(), valueText);
		invalidValues.remove(selectedComponent.id());
		status = LuaUiText.text("已设置 ") + selectedComponent.label() + " = " + next;
	}

	private void commitValue() {
		if (selectedComponent == null || usesTreeEditor(selectedComponent)
				|| !enabledValues.containsKey(selectedComponent.id())) return;
		try {
			enabledValues.put(selectedComponent.id(), parseValue(selectedComponent, valueText));
			valueDrafts.put(selectedComponent.id(), valueText);
			invalidValues.remove(selectedComponent.id());
			if (status.startsWith(LuaUiText.text("错误："))) status = LuaUiText.text("值格式正确，导出时会由服务器再次校验");
		} catch (RuntimeException error) {
			valueDrafts.put(selectedComponent.id(), valueText);
			invalidValues.add(selectedComponent.id());
			status = LuaUiText.text("错误：") + (error.getMessage() == null ? LuaUiText.text("值格式无效") : error.getMessage());
		}
	}

	private JsonElement parseValue(LuaComponentEditorData.ComponentInfo component, String raw) {
		String value = raw.strip();
		return switch (component.editor()) {
			case "unit" -> new JsonObject();
			case "boolean" -> {
				if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(LuaUiText.text("请输入 true 或 false"));
				yield new JsonPrimitive(Boolean.parseBoolean(value));
			}
			case "integer" -> new JsonPrimitive(Integer.parseInt(value));
			case "number" -> {
				double number = Double.parseDouble(value);
				if (!Double.isFinite(number)) throw new IllegalArgumentException(LuaUiText.text("请输入有限数字"));
				yield new JsonPrimitive(number);
			}
			case "text_component" -> {
				JsonObject text = new JsonObject();
				text.addProperty("text", raw);
				yield text;
			}
			case "resource", "enum" -> new JsonPrimitive(raw);
			default -> JsonParser.parseString(value);
		};
	}

	private String displayValue(LuaComponentEditorData.ComponentInfo component, JsonElement value) {
		if (component.editor().equals("text_component") && value.isJsonObject()
				&& value.getAsJsonObject().has("text")) return value.getAsJsonObject().get("text").getAsString();
		if ((component.editor().equals("resource") || component.editor().equals("enum"))
				&& value.isJsonPrimitive()) return value.getAsString();
		return value.toString();
	}

	private static boolean usesTreeEditor(LuaComponentEditorData.ComponentInfo component) {
		return component != null && (component.editor().equals("json") || component.editor().equals("resource"));
	}

	private void export(Export action) {
		if (data == null || exporting) return;
		commitValue();
		if (!invalidValues.isEmpty()) {
			status = "错误：仍有 " + invalidValues.size() + " 个组件值格式无效";
			return;
		}
		JsonObject patch = LuaComponentEditorData.patch(enabledValues);
		int insertionRevision = openRevision;
		exporting = true;
		status = LuaUiText.text("正在由服务器校验配置…");
		ScriptManager.validateComponents(data.kind(), data.requestedId(), patch.toString()).whenComplete((canonical, failure) ->
				minecraft.execute(() -> {
					exporting = false;
					if (failure != null) {
						status = LuaUiText.text("错误：") + ScriptManager.message(failure);
						feedback.accept(status);
						return;
					}
					JsonObject checked = JsonParser.parseString(canonical).getAsJsonObject();
					if (action == Export.INSERT) {
						insertion.accept(functionDocument.getAsBoolean() ? commandText(checked, false) : LuaComponentEditorData.luaComponents(checked));
						status = LuaUiText.text("配置表已插入代码编辑器");
						if (open && closeAfterInsert && openRevision == insertionRevision) close();
					} else if (action == Export.COPY_STACK && functionDocument.getAsBoolean()) {
						minecraft.keyboardHandler.setClipboard(commandText(checked, true));
						status = LuaUiText.text("已复制命令格式");
					} else if (action == Export.COPY_STACK) {
						if (data.kind().equals("entity")) {
							minecraft.keyboardHandler.setClipboard(
									LuaComponentEditorData.luaEntity(data.requestedId(), checked));
							status = "已复制 Lua 实体组件配置";
						} else if (data.kind().equals("block")) {
							minecraft.keyboardHandler.setClipboard(
									LuaComponentEditorData.luaBlock(data.requestedId(), checked));
							status = "已复制 Lua 方块配置";
						} else {
							minecraft.keyboardHandler.setClipboard(LuaComponentEditorData.luaStack(data.itemId(), checked));
							status = "已复制 Lua 物品堆表";
						}
					} else {
						minecraft.keyboardHandler.setClipboard(checked.toString());
						status = LuaUiText.text("已复制组件 JSON");
					}
					feedback.accept(status);
				}));
	}

	private void insertTyped(String text) {
		if (text == null || text.isEmpty()) return;
		if (focus == Focus.TARGET_SEARCH) {
			targetQuery += text;
			refreshTargets(false);
		} else if (focus == Focus.COMPONENT_SEARCH) {
			componentQuery += text;
			filterComponents();
		} else {
			if (selectedComponent == null) return;
			if (!enabledValues.containsKey(selectedComponent.id())) {
				enabledValues.put(selectedComponent.id(), selectedComponent.defaultValue().deepCopy());
			}
			valueText = valueText.substring(0, valueCursor) + text + valueText.substring(valueCursor);
			valueCursor += text.length();
			valueDrafts.put(selectedComponent.id(), valueText);
			commitValue();
		}
	}

	private void backspace() {
		if (focus == Focus.TARGET_SEARCH && !targetQuery.isEmpty()) {
			targetQuery = removeLastCodepoint(targetQuery);
			refreshTargets(false);
		} else if (focus == Focus.COMPONENT_SEARCH && !componentQuery.isEmpty()) {
			componentQuery = removeLastCodepoint(componentQuery);
			filterComponents();
		} else if (focus == Focus.VALUE && valueCursor > 0) {
			int start = valueText.offsetByCodePoints(valueCursor, -1);
			valueText = valueText.substring(0, start) + valueText.substring(valueCursor);
			valueCursor = start;
			commitValue();
		}
	}

	private void deleteForward() {
		if (focus == Focus.TARGET_SEARCH) {
			targetQuery = "";
			refreshTargets(false);
		} else if (focus == Focus.COMPONENT_SEARCH) {
			componentQuery = "";
			filterComponents();
		} else if (focus == Focus.VALUE && valueCursor < valueText.length()) {
			int end = valueText.offsetByCodePoints(valueCursor, 1);
			valueText = valueText.substring(0, valueCursor) + valueText.substring(end);
			commitValue();
		}
	}

	private void moveTarget(int direction) {
		targetRow = clamp(targetRow + direction, 0, Math.max(0, targets.size() - 1));
		ensureTargetVisible();
	}

	private void moveComponent(int direction) {
		componentRow = clamp(componentRow + direction, 0, Math.max(0, components.size() - 1));
		selectComponent(componentRow);
	}

	private void renderSearch(GuiGraphicsExtractor graphics, int x, int y, int width,
			String value, String hint, boolean active) {
		graphics.fill(x, y, x + width, y + 20, 0xFF080B10);
		graphics.outline(x, y, width, 20, active ? 0xFFD0A7FF : 0xFF554666);
		String shown = value.isEmpty() ? hint : value + (active ? "_" : "");
		graphics.text(font, font.plainSubstrByWidth(shown, width - 10), x + 5, y + 6,
				value.isEmpty() ? 0xFF6F6878 : 0xFFF1E9FA);
	}

	private String visibleValue(int maximumWidth) {
		if (valueText.isEmpty()) return "";
		int caret = Math.min(valueCursor, valueText.length());
		int start = 0;
		if (focus == Focus.VALUE) {
			while (start < caret && font.width(valueText.substring(start, caret) + "_") > maximumWidth * 2 / 3) {
				start = valueText.offsetByCodePoints(start, 1);
			}
		}
		String shown = valueText.substring(start, caret) + (focus == Focus.VALUE ? "_" : "")
				+ valueText.substring(caret);
		return font.plainSubstrByWidth(shown, maximumWidth);
	}

	private void renderTargetIcon(GuiGraphicsExtractor graphics, LuaInsertCatalog.Entry entry, int x, int y) {
		try {
			if (category == Category.ENTITY) {
				drawFallbackIcon(graphics, x, y, "实");
				return;
			}
			Identifier id = Identifier.parse(entry.value());
			ItemStack stack = switch (category) {
				case ITEM -> BuiltInRegistries.ITEM.getValue(id).getDefaultInstance();
				case BLOCK -> BuiltInRegistries.BLOCK.getValue(id).asItem().getDefaultInstance();
				case ENTITY -> ItemStack.EMPTY;
			};
			if (!stack.isEmpty()) graphics.item(stack, x, y, entry.value().hashCode());
			else drawFallbackIcon(graphics, x, y, category == Category.BLOCK ? "□" : "E");
		} catch (RuntimeException | LinkageError ignored) {
			drawFallbackIcon(graphics, x, y, "?");
		}
	}

	private void renderCompactTarget(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x, int y) {
		boolean hovered = inside(mouseX, mouseY, x, y, x + 26, y + 26);
		graphics.fill(x, y, x + 26, y + 26, hovered ? 0xFF433158 : 0xFF20192A);
		graphics.outline(x, y, 26, 26, hovered ? 0xFFD0A7FF : 0xFF7654A7);
		if (selectedTarget != null) renderTargetIcon(graphics, selectedTarget, x + 5, y + 5);
		else drawFallbackIcon(graphics, x + 5, y + 5, "?");
		if (hovered) {
			String target = selectedTarget == null ? LuaUiText.text("尚未选择目标") : selectedTarget.label();
			graphics.setComponentTooltipForNextFrame(font, List.of(
					Component.literal(target), Component.literal(LuaUiText.text("点击重新选择"))), mouseX, mouseY);
		}
	}

	private void drawFallbackIcon(GuiGraphicsExtractor graphics, int x, int y, String label) {
		graphics.fill(x, y, x + 16, y + 16, 0xFF20192A);
		graphics.outline(x, y, 16, 16, 0xFF7654A7);
		graphics.centeredText(font, label, x + 8, y + 4, 0xFFD7B8FF);
	}

	private void drawButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int y, int width, int height, String label, int color, int hover) {
		graphics.fill(x, y, x + width, y + height,
				inside(mouseX, mouseY, x, y, x + width, y + height) ? hover : color);
		graphics.outline(x, y, width, height, 0xFF80669A);
		graphics.centeredText(font, font.plainSubstrByWidth(label, width - 6), x + width / 2, y + 6, 0xFFFFFFFF);
	}

	private int targetVisibleRows() {
		return Math.max(1, (windowHeight - 36 - (windowY + 86 - windowY) - 3) / ROW_HEIGHT);
	}

	private int targetPaneWidth() {
		return targetPaneExpanded ? Math.max(86, Math.min(285, (int) (windowWidth * 0.36F))) : 42;
	}

	private int componentVisibleRows() {
		int contentTop = windowY + 61;
		int contentBottom = windowY + windowHeight - 36;
		int listTop = contentTop + 25;
		int editorTop = listTop + Math.max(60, (contentBottom - listTop) * 34 / 100);
		return Math.max(1, (editorTop - listTop - 8) / ROW_HEIGHT);
	}

	private int maxTargetScroll() {
		return Math.max(0, targets.size() - targetVisibleRows());
	}

	private int maxComponentScroll() {
		return Math.max(0, components.size() - componentVisibleRows());
	}

	private void ensureTargetVisible() {
		if (targetRow < targetScroll) targetScroll = targetRow;
		if (targetRow >= targetScroll + targetVisibleRows()) targetScroll = targetRow - targetVisibleRows() + 1;
		targetScroll = clamp(targetScroll, 0, maxTargetScroll());
	}

	private void ensureComponentVisible() {
		if (componentRow < componentScroll) componentScroll = componentRow;
		if (componentRow >= componentScroll + componentVisibleRows()) {
			componentScroll = componentRow - componentVisibleRows() + 1;
		}
		componentScroll = clamp(componentScroll, 0, maxComponentScroll());
	}

	private int panelX() {
		return Math.round(screenWidth + (windowX - screenWidth) * progress);
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
		if (windowWidth < 0) windowWidth = Math.min(availableWidth, Math.max(MIN_WIDTH, (int) (screenWidth * 0.78F)));
		if (windowHeight < 0) windowHeight = Math.min(availableHeight, Math.max(MIN_HEIGHT, (int) (screenHeight * 0.82F)));
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

	private static String removeLastCodepoint(String value) {
		return value.substring(0, value.offsetByCodePoints(value.length(), -1));
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	private enum Category {
		ITEM("item", LuaUiText.text("物品"), LuaInsertCatalog.Kind.ITEM),
		BLOCK("block", LuaUiText.text("方块"), LuaInsertCatalog.Kind.BLOCK),
		ENTITY("entity", LuaUiText.text("实体"), LuaInsertCatalog.Kind.ENTITY);

		private final String id;
		private final String label;
		private final LuaInsertCatalog.Kind catalogKind;

		Category(String id, String label, LuaInsertCatalog.Kind catalogKind) {
			this.id = id;
			this.label = label;
			this.catalogKind = catalogKind;
		}

		private static Category from(LuaInsertCatalog.Kind kind) {
			if (kind == null) return null;
			return switch (kind) {
				case ITEM -> ITEM;
				case BLOCK -> BLOCK;
				case ENTITY -> ENTITY;
				default -> null;
			};
		}
	}

	private enum Focus {
		TARGET_SEARCH,
		COMPONENT_SEARCH,
		VALUE,
		TREE
	}

	private enum Export {
		INSERT,
		COPY_STACK,
		COPY_JSON
	}
}
