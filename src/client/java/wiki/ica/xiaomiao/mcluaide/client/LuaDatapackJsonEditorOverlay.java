package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;
import wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog;

/** A dockable data-pack JSON editor backed by examples from the active server packs. */
final class LuaDatapackJsonEditorOverlay {
	void renderImGui() {
		if(imgui.ImGui.button(LuaUiText.text("类型")))showSources=false; imgui.ImGui.sameLine();if(imgui.ImGui.button(LuaUiText.text("游戏示例")))showSources=true;
		imgui.ImGui.sameLine();NativeUi.action("刷新",this::refreshOptions);
		String next=NativeUi.input("##filter",query);if(!next.equals(query)){query=next;listScroll=0;}
        boolean narrow=imgui.ImGui.getContentRegionAvailX()<480;
        if(narrow){String choice=NativeUi.combo("类别 / 示例",showSources?selectedId:category,visibleEntries(),entry->showSources?entry:categories.stream().filter(c->c.path().equals(entry)).findFirst().map(c->c.label()+" · "+c.path()).orElse(entry));if(!choice.equals(showSources?selectedId:category))activateEntry(choice);tree.renderImGui();}
		if(!narrow&&imgui.ImGui.beginTable("json_split",2,imgui.flag.ImGuiTableFlags.Resizable|imgui.flag.ImGuiTableFlags.BordersInnerV)) {
			imgui.ImGui.tableSetupColumn("类别 / 示例",imgui.flag.ImGuiTableColumnFlags.WidthFixed,210);imgui.ImGui.tableSetupColumn("编辑");imgui.ImGui.tableNextColumn();
			imgui.ImGui.beginChild("categories",0,Math.max(100,imgui.ImGui.getContentRegionAvailY()-70),true);
			for(String entry:visibleEntries())if(imgui.ImGui.selectable(showSources?entry:categories.stream().filter(c->c.path().equals(entry)).findFirst().map(c->c.label()+" · "+c.path()).orElse(entry),entry.equals(selectedId)||!showSources&&entry.equals(category)))activateEntry(entry);
			imgui.ImGui.endChild();imgui.ImGui.tableNextColumn();tree.renderImGui();imgui.ImGui.endTable();
		}
		NativeUi.action("插入配置",()->validateAndInsert(false));imgui.ImGui.sameLine();NativeUi.action("复制 JSON",()->NativeUi.copy(PRETTY.toJson(document)));
		imgui.ImGui.sameLine();NativeUi.action("读取当前文件",()->{String source=currentFileJson.get();if(source!=null)importJson(source);});NativeUi.status(status);
	}
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	private static final int TITLE = 28;
	private static final int ROW = 21;
	private static final int BORDER = 6;
	private static final List<LuaComponentEditorData.IdHint> DIMENSION_HINTS = List.of(
			new LuaComponentEditorData.IdHint("/type", "value", "minecraft:dimension_type", LuaUiText.text("维度类型"), LuaUiText.text("维度环境、光照与高度")),
			new LuaComponentEditorData.IdHint("/generator/settings", "value", "minecraft:worldgen/noise_settings", LuaUiText.text("噪声设置"), LuaUiText.text("噪声地形生成预设")),
			new LuaComponentEditorData.IdHint("/generator/biome_source/biome", "value", "minecraft:worldgen/biome", LuaUiText.text("生物群系"), LuaUiText.text("单一生物群系")),
			new LuaComponentEditorData.IdHint("/generator/settings/biome", "value", "minecraft:worldgen/biome", LuaUiText.text("生物群系"), LuaUiText.text("超平坦生物群系")),
			new LuaComponentEditorData.IdHint("/generator/biome_source/preset", "value", "minecraft:worldgen/multi_noise_biome_source_parameter_list", LuaUiText.text("群系参数预设"), LuaUiText.text("多噪声群系源")),
			new LuaComponentEditorData.IdHint("/generator/settings/layers/*/block", "value", "minecraft:block", LuaUiText.text("方块 ID"), LuaUiText.text("超平坦层方块")),
			new LuaComponentEditorData.IdHint("/generator/settings/structure_overrides/*", "value", "minecraft:worldgen/structure_set", LuaUiText.text("结构集 ID"), LuaUiText.text("超平坦结构")));

	private final Minecraft minecraft;
	private final Font font;
	private final BiConsumer<String, String> insert;
	private final Consumer<String> feedback;
	private final Supplier<String> currentTarget;
	private final Supplier<String> currentFileJson;
	private final JsonTreeEditor tree;
	private final List<DatapackScaffoldCatalog.Category> categories = new ArrayList<>(DatapackScaffoldCatalog.jsonCategories());
	private List<String> sources = List.of();
	private ComponentTreeSchema.Node observedSchema;
	private String category = "dimension";
	private boolean showSources;
	private int sampleCount;
	private int observedCount;
	private JsonElement document = new JsonObject();
	private String query = "";
	private String selectedId = "";
	private String status = "选择左侧 JSON 类型；示例来自当前游戏已加载的数据包";
	private int selectedRow;
	private int listScroll;
	private int optionsRevision;
	private int observedCatalogRevision = -1;
	private int sourceRevision;
	private boolean loading;
	private boolean validating;
	private boolean open;
	private boolean keyboardFocused;
	private boolean searchFocused;
	private float progress;
	private int screenWidth;
	private int screenHeight;
	private int x = -1;
	private int y = 18;
	private int width = -1;
	private int height = -1;
	private boolean dragging;
	private int resizeEdges;
	private double dragOffsetX;
	private double dragOffsetY;
	private double resizeMouseX;
	private double resizeMouseY;
	private int resizeX;
	private int resizeY;
	private int resizeWidth;
	private int resizeHeight;
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked;
	private int floatingX = -1;
	private int floatingY = 18;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private final WindowBoundsAnimation animation = new WindowBoundsAnimation();

	LuaDatapackJsonEditorOverlay(Minecraft minecraft, Font font, BiConsumer<String, String> insert,
			Consumer<String> feedback, Supplier<String> currentTarget, Supplier<String> currentFileJson) {
		this.minecraft = minecraft;
		this.font = font;
		this.insert = insert;
		this.feedback = feedback;
		this.currentTarget = currentTarget;
		this.currentFileJson = currentFileJson;
		categories.addFirst(new DatapackScaffoldCatalog.Category("pack.mcmeta", LuaUiText.text("数据包元信息"),
				"描述数据包名称、版本兼容信息等元数据", ".json", "{}\n"));
		this.tree = new JsonTreeEditor(minecraft, font, changed -> document = changed,
				message -> status = message);
		tree.setTranslation(DimensionJsonTranslations.INSTANCE);
		tree.setValue("mc_lua_ide:dimension", document, DIMENSION_HINTS);
	}

	void resize(int screenWidth, int screenHeight) {
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		ensureGeometry();
	}

	void open() {
		open = true;
		keyboardFocused = true;
		refreshCategories();
		refreshOptions();
	}

	private void refreshCategories() {
		ScriptManager.datapackJsonCategories().whenComplete((paths, error) -> minecraft.execute(() -> {
			if (!open || error != null) return;
			for (String path : paths) {
				if (categories.stream().noneMatch(entry -> entry.path().equals(path)))
					categories.add(new DatapackScaffoldCatalog.Category(path, path,
							"当前游戏数据包或模组提供的 JSON 类型", ".json", "{}\n"));
			}
		}));
	}

	void close() {
		open = false;
		optionsRevision++;
		sourceRevision++;
		keyboardFocused = false;
		dragging = false;
		resizeEdges = 0;
	}

	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F; }
	boolean isInteracting() { return dragging || resizeEdges != 0; }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean value) { layoutLocked = value; }
	void releaseKeyboardFocus() { keyboardFocused = false; }
	void requestKeyboardFocus() { if (open) keyboardFocused = true; }
	boolean contains(double px, double py) { return isVisible() && inside(px, py, panelX(), y, panelX() + width, y + height); }


	WindowGeometry captureGeometry() {
		if (!dockSide.docked()) return animation.destination(x, y, width, height);
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
		animation.snapTo(x, y, width, height);
	}

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		DockSide old = dockSide;
		if (!old.docked() && side.docked()) rememberFloating();
		dockSide = side;
		if (old.docked() && !side.docked()) restoreFloating();
		resizeEdges = 0;
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (WindowBoundsAnimation.isImmediate()) {
			this.x = x; this.y = y; this.width = width; this.height = height; progress = 1;
			animation.snapTo(x,y,width,height); return;
		}
		if (dockSide.docked()) animation.animateTo(this.x, this.y, this.width, this.height, x, y, width, height);
	}

	void tick() {
		int revision = ScriptManager.catalogRevision();
		if (revision != observedCatalogRevision) {
			observedCatalogRevision = revision;
			if (open) { refreshCategories(); refreshOptions(); }
		}
		float target = open ? 1 : 0;
		progress += (target - progress) * 0.28F;
		if (Math.abs(target - progress) < 0.01F) progress = target;
		if (!dragging && resizeEdges == 0) {
			WindowBoundsAnimation.Bounds bounds = animation.tick(x, y, width, height);
			x = bounds.x(); y = bounds.y(); width = bounds.width(); height = bounds.height();
		}
	}

	void importJson(String source) {
		try {
			JsonElement parsed = JsonParser.parseString(source);
			if (parsed.isJsonNull()) throw new IllegalArgumentException("JSON 不能为空");
			document = parsed;
			resetTree();
			status = "已导入当前文件；在树中双击字段修改，＋ 可添加字段";
		} catch (Exception error) {
			status = LuaUiText.text("导入失败：") + error.getMessage();
		}
	}

	void openForDocument(String documentCategory, String source) {
		if (!category.equals(documentCategory)) selectCategory(documentCategory);
		if (!open) open();
		showSources = false;
		selectedId = "";
		if (source != null && !source.isBlank()) importJson(source);
		keyboardFocused = true;
	}

	private void selectCategory(String value) {
		category = value;
		sourceRevision++;
		if (categories.stream().noneMatch(entry -> entry.path().equals(value)))
			categories.add(new DatapackScaffoldCatalog.Category(value, value, "模组或自定义 JSON 类型", ".json", "{}\n"));
		document = new JsonObject();
		observedSchema = null;
		selectedId = "";
		sources = List.of();
		query = "";
		listScroll = 0;
		resetTree();
		refreshOptions();
	}

	private void resetTree() {
		DatapackScaffoldCatalog.Category selected = categoryInfo();
		tree.setTranslation(category.equals("dimension") ? DimensionJsonTranslations.INSTANCE
				: new DatapackJsonTranslations(selected.label(), selected.purpose()));
		tree.setValue("mc_lua_ide:datapack_json", document, category.equals("dimension") ? DIMENSION_HINTS : List.of());
		tree.setObservedSchema(observedSchema);
	}

	private DatapackScaffoldCatalog.Category categoryInfo() {
		return categories.stream().filter(entry -> entry.path().equals(category)).findFirst()
				.orElse(new DatapackScaffoldCatalog.Category(category, category, LuaUiText.text("数据包 JSON"), ".json", "{}\n"));
	}

	private void refreshOptions() {
		int revision = ++optionsRevision;
		loading = true;
		ScriptManager.datapackJsonOptions(category).whenComplete((options, error) -> minecraft.execute(() -> {
			if (revision != optionsRevision || !open) return;
			loading = false;
			if (error != null) {
				status = "读取当前游戏示例失败：" + ScriptManager.message(error);
				return;
			}
			sources = options.sources();
			sampleCount = options.total();
			observedCount = options.observed();
			observedSchema = options.schema();
			tree.setObservedSchema(observedSchema);
			status = options.limited() ? "示例过多，已限制字段建议；仍可手动添加字段"
					: "当前游戏共 " + sampleCount + " 个示例；字段来自最多 " + observedCount + " 个采样示例，不代表完整规则"
					+ (options.sourcesLimited() ? "；列表仅显示前 " + sources.size() + LuaUiText.text(" 个") : "");
		}));
	}

	private void loadSource(String id) {
		int revision = ++sourceRevision;
		loading = true;
		status = "正在从服务器读取 " + id + "…";
		ScriptManager.datapackJsonSource(category, id).whenComplete((value, error) -> minecraft.execute(() -> {
			if (revision != sourceRevision || !open) return;
			loading = false;
			if (error != null) {
				status = LuaUiText.text("读取失败：") + ScriptManager.message(error);
				return;
			}
			selectedId = id;
			importJson(value);
			status = "已读取当前游戏示例 " + id + "；可修改后插入到同类数据包文件";
		}));
	}

	/** Ctrl+Enter: validates, inserts and then closes the window. */
	void insertAndClose() { validateAndInsert(true); }

	private void validateAndInsert(boolean closeAfter) {
		if (validating || !tree.commitPendingEdit()) return;
		String target = currentTarget.get();
		if (target == null || target.isBlank()) {
			status = "请先打开一个同类型的数据包 JSON 文件";
			return;
		}
		if (!category.equals(DatapackJsonDocumentCategory.fromPath(target))) {
			status = "当前文件与所选 JSON 类型不同；请切到对应类型或打开对应文件";
			return;
		}
		String json = PRETTY.toJson(document) + "\n";
		String originalSource = currentFileJson.get();
		validating = true;
		status = "正在校验数据包 JSON…";
		String selectedCategory = category;
		ScriptManager.validateDatapackJson(selectedCategory, json).whenComplete((result, error) -> minecraft.execute(() -> {
			validating = false;
			if (error != null) {
				status = "数据包 JSON 错误：" + ScriptManager.message(error);
				feedback.accept(status);
				return;
			}
			if (!target.equals(currentTarget.get()) || !selectedCategory.equals(category)
					|| !java.util.Objects.equals(originalSource, currentFileJson.get())) {
				status = "插入已取消：校验期间文件或编辑内容发生变化";
				return;
			}
			insert.accept(selectedCategory, json);
			status = "已插入 JSON；" + result + "。保存后需重载数据包才会生效";
			if (closeAfter) close();
		}));
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isVisible()) return;
		ensureGeometry();
		int left = panelX();
		int right = left + width;
		int bottom = y + height;
		graphics.fill(left - 4, y + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(left, y, right, bottom, 0xFD10151E);
		graphics.outline(left, y, width, height, 0xFFB388FF);
		graphics.fill(left + 1, y + 1, right - 1, y + TITLE, 0xFF302148);
		graphics.text(font, LuaUiText.text("◈  数据包 JSON 编辑器 · ") + categoryInfo().label(), left + 9, y + 9, 0xFFD7B8FF);
		button(graphics, mouseX, mouseY, right - 27, y + 4, 21, 20, "×", 0xFF4B3562);
		int listWidth = Math.max(125, Math.min(190, width / 3));
		int listLeft = left + 8;
		int listRight = listLeft + listWidth;
		int listTop = y + TITLE + 61;
		int contentBottom = bottom - 47;
		int tabWidth = listWidth / 2;
		button(graphics, mouseX, mouseY, listLeft, y + TITLE + 4, tabWidth - 2, 20, LuaUiText.text("类型"), showSources ? 0xFF342D3F : 0xFF61408F);
		button(graphics, mouseX, mouseY, listLeft + tabWidth, y + TITLE + 4, listWidth - tabWidth, 20,
				"示例 " + sampleCount, showSources ? 0xFF61408F : 0xFF342D3F);
		graphics.fill(listLeft, y + TITLE + 29, listRight, y + TITLE + 50, 0xFF090C12);
		graphics.outline(listLeft, y + TITLE + 29, listWidth, 21, 0xFF554666);
		graphics.text(font, query.isEmpty() ? LuaUiText.text("搜索 ID / 中文 / 首拼…", "Search IDs or names…") : query,
				listLeft + 5, y + TITLE + 35, query.isEmpty() ? 0xFF7A7486 : 0xFFF1E9FA);
		graphics.fill(listLeft, listTop, listRight, contentBottom, 0xFF0B1018);
		graphics.enableScissor(listLeft + 1, listTop + 1, listRight - 1, contentBottom - 1);
		List<String> filtered = visibleEntries();
		int visible = Math.max(1, (contentBottom - listTop) / ROW);
		for (int row = 0; row < visible && row + listScroll < filtered.size(); row++) {
			String entry = filtered.get(row + listScroll);
			int rowY = listTop + row * ROW;
			if (entry.equals(showSources ? selectedId : category)) graphics.fill(listLeft + 1, rowY, listRight - 1, rowY + ROW, 0xFF43305D);
			DatapackScaffoldCatalog.Category info = showSources ? null : categories.stream().filter(item -> item.path().equals(entry)).findFirst().orElse(null);
			String translated = showSources && category.equals("dimension") ? DimensionJsonTranslations.dimensionName(entry) : "";
			String display = info != null ? info.label() + " · " + entry : translated.isBlank() ? entry : translated + " · " + entry;
			graphics.text(font, font.plainSubstrByWidth(display, listWidth - 9),
					listLeft + 5, rowY + 5, entry.equals(showSources ? selectedId : category) ? 0xFFFFFFFF : 0xFFD1C4DE);
			if (inside(mouseX, mouseY, listLeft, rowY, listRight, rowY + ROW)) {
				graphics.setComponentTooltipForNextFrame(font, List.of(
						Component.literal(info == null ? entry : info.label() + " · " + info.path()),
						Component.literal(info == null ? LuaUiText.text("当前游戏已加载的 JSON 示例") : info.purpose())), mouseX, mouseY);
			}
		}
		graphics.disableScissor();
		int treeLeft = listRight + 8;
		int treeWidth = right - treeLeft - 8;
		graphics.text(font, font.plainSubstrByWidth(loading ? LuaUiText.text("正在读取示例…") :
				(selectedId.isBlank() ? "当前文件 / 自定义 " + categoryInfo().label() : selectedId), treeWidth),
				treeLeft, y + TITLE + 6, 0xFFE9D9FF);
		tree.render(graphics, mouseX, mouseY, treeLeft, y + TITLE + 19, treeWidth, contentBottom);
		graphics.text(font, font.plainSubstrByWidth(status, width - 18), left + 9, bottom - 42,
				status.contains(LuaUiText.text("错误")) || status.contains(LuaUiText.text("失败")) ? 0xFFFF7777 : 0xFFAA9DB8);
		int buttonY = bottom - 28;
		int buttonWidth = Math.max(65, (width - 24) / 3);
		button(graphics, mouseX, mouseY, left + 8, buttonY, buttonWidth, 20,
				validating ? LuaUiText.text("校验中…") : LuaUiText.text("校验并插入"), 0xFF61408F);
		button(graphics, mouseX, mouseY, left + 12 + buttonWidth, buttonY, buttonWidth, 20,
				LuaUiText.text("复制 JSON"), 0xFF2C4D65);
		button(graphics, mouseX, mouseY, left + 16 + buttonWidth * 2, buttonY,
				width - buttonWidth * 2 - 24, 20, LuaUiText.text("读取当前文件"), 0xFF2C4D65);
		tree.renderPopupOverlay(graphics, mouseX, mouseY);
		ResizeBorderHint.render(graphics, left, y, right, bottom,
				resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY), 0xFFD7B8FF);
	}

	private void button(GuiGraphicsExtractor graphics, int mx, int my, int x, int y,
			int width, int height, String label, int color) {
		graphics.fill(x, y, x + width, y + height,
				inside(mx, my, x, y, x + width, y + height) ? 0xFF7852A4 : color);
		graphics.centeredText(font, font.plainSubstrByWidth(label, width - 5), x + width / 2, y + 6, 0xFFFFFFFF);
	}

	private List<String> visibleEntries() {
		String needle = query.strip().toLowerCase(Locale.ROOT);
		if (showSources) return sources.stream().filter(id -> LuaUiText.matchesSearch(id + " "
				+ DimensionJsonTranslations.dimensionName(id), needle)).toList();
		return categories.stream().filter(entry -> LuaUiText.matchesSearch(entry.path() + " " + entry.label()
				+ " " + entry.purpose(), needle)).map(DatapackScaffoldCatalog.Category::path).toList();
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (event.hasControlDown() && event.key() == InputConstants.KEY_RETURN) {
			validateAndInsert(false); return true;
		}
		if (tree.hasPopup()) return tree.keyPressed(event);
		if (event.key() == InputConstants.KEY_ESCAPE) { close(); return true; }
		if (searchFocused && event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			query = ""; listScroll = 0; return true;
		}
		if (searchFocused && event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			query += minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " ");
			if (query.length() > 80) query = query.substring(0, 80);
			listScroll = 0;
			return true;
		}
		if (searchFocused && event.key() == InputConstants.KEY_BACKSPACE && !query.isEmpty()) {
			query = query.substring(0, query.offsetByCodePoints(query.length(), -1)); listScroll = 0; return true;
		}
		if (searchFocused && event.key() == InputConstants.KEY_RETURN) {
			List<String> filtered = visibleEntries();
			if (!filtered.isEmpty()) activateEntry(filtered.get(Math.min(selectedRow, filtered.size() - 1)));
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN || event.key() == InputConstants.KEY_UP) {
			if (!searchFocused) return tree.keyPressed(event);
			selectedRow = Math.max(0, Math.min(visibleEntries().size() - 1,
					selectedRow + (event.key() == InputConstants.KEY_DOWN ? 1 : -1)));
			return true;
		}
		return tree.keyPressed(event);
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		if (tree.hasPopup()) return tree.charTyped(event);
		if (!searchFocused || event.codepoint() < 32 || query.length() >= 80) return false;
		query += new String(Character.toChars(event.codepoint()));
		listScroll = 0;
		return true;
	}

	boolean mouseScrolled(double mx, double my, double amount) {
		if (!open || !contains(mx, my)) return false;
		int left = panelX();
		int listRight = left + 8 + Math.max(125, Math.min(190, width / 3));
		if (mx < listRight) {
			listScroll = Math.max(0, Math.min(Math.max(0, visibleEntries().size() - 1),
					listScroll - (int) Math.signum(amount) * 3));
			return true;
		}
		return tree.mouseScrolled(mx, my, amount);
	}

	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick, String currentFileJson) {
		if (!open) return false;
		int left = panelX();
		int right = left + width;
		int bottom = y + height;
		if (tree.hasPopup()) return tree.mouseClicked(event, doubleClick);
		int edges = resizeEdgesAt(event.x(), event.y());
		if (edges != 0 && !layoutLocked) {
			resizeEdges = edges; resizeMouseX = event.x(); resizeMouseY = event.y();
			resizeX = x; resizeY = y; resizeWidth = width; resizeHeight = height;
			return true;
		}
		if (!contains(event.x(), event.y())) return false;
		keyboardFocused = true;
		searchFocused = false;
		if (inside(event.x(), event.y(), right - 27, y + 4, right - 6, y + 24)) { close(); return true; }
		if (inside(event.x(), event.y(), left, y, right - 30, y + TITLE)) {
			if (!layoutLocked || !dockSide.docked()) {
				dragging = true; dragOffsetX = event.x() - left; dragOffsetY = event.y() - y;
			}
			return true;
		}
		int buttonY = bottom - 28;
		int buttonWidth = Math.max(65, (width - 24) / 3);
		if (inside(event.x(), event.y(), left + 8, buttonY, left + 8 + buttonWidth, buttonY + 20)) {
			validateAndInsert(false); return true;
		}
		if (inside(event.x(), event.y(), left + 12 + buttonWidth, buttonY,
				left + 12 + buttonWidth * 2, buttonY + 20)) {
			if (tree.commitPendingEdit()) {
				minecraft.keyboardHandler.setClipboard(PRETTY.toJson(document) + "\n");
				status = LuaUiText.text("数据包 JSON 已复制");
			}
			return true;
		}
		if (inside(event.x(), event.y(), left + 16 + buttonWidth * 2, buttonY, right - 8, buttonY + 20)) {
			if (currentFileJson == null || currentFileJson.isBlank()) status = LuaUiText.text("请先打开一个数据包 JSON 文件");
			else {
				String path = currentTarget.get();
				String detected = DatapackJsonDocumentCategory.fromPath(path);
				if (detected != null && !category.equals(detected)) selectCategory(detected);
				importJson(currentFileJson);
				selectedId = "";
			}
			return true;
		}
		int listRight = left + 8 + Math.max(125, Math.min(190, width / 3));
		int tabWidth = (listRight - left - 8) / 2;
		if (inside(event.x(), event.y(), left + 8, y + TITLE + 4, listRight, y + TITLE + 24)) {
			showSources = event.x() >= left + 8 + tabWidth;
			query = ""; listScroll = 0; selectedRow = 0;
			return true;
		}
		if (inside(event.x(), event.y(), left + 8, y + TITLE + 29, listRight, y + TITLE + 50)) {
			searchFocused = true;
			return true;
		}
		int listTop = y + TITLE + 61;
		if (inside(event.x(), event.y(), left + 8, listTop, listRight, bottom - 47)) {
			int row = listScroll + (int) (event.y() - listTop) / ROW;
			List<String> filtered = visibleEntries();
			if (row >= 0 && row < filtered.size()) {
				searchFocused = false;
				selectedRow = row;
				activateEntry(filtered.get(row));
			}
			return true;
		}
		tree.mouseClicked(event, doubleClick);
		return true;
	}

	private void activateEntry(String value) {
		if (showSources) loadSource(value);
		else selectCategory(value);
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (resizeEdges != 0) {
			int dx = (int) Math.round(event.x() - resizeMouseX);
			int dy = (int) Math.round(event.y() - resizeMouseY);
			int left = resizeX, right = resizeX + resizeWidth, top = resizeY, bottom = resizeY + resizeHeight;
			if ((resizeEdges & 1) != 0) left = clamp(resizeX + dx, 0, right - 400);
			if ((resizeEdges & 2) != 0) right = clamp(right + dx, left + 400, screenWidth);
			if ((resizeEdges & 4) != 0) top = clamp(resizeY + dy, 0, bottom - 280);
			if ((resizeEdges & 8) != 0) bottom = clamp(bottom + dy, top + 280, screenHeight);
			x = left; y = top; width = right - left; height = bottom - top;
			animation.snapTo(x, y, width, height);
			return true;
		}
		if (!dragging) return false;
		if (dockSide.docked()) {
			dockSide = DockSide.FLOATING;
			restoreFloating();
		}
		x = clamp((int) Math.round(event.x() - dragOffsetX), 0, Math.max(0, screenWidth - width));
		y = clamp((int) Math.round(event.y() - dragOffsetY), 0, Math.max(0, screenHeight - height));
		animation.snapTo(x, y, width, height);
		return true;
	}

	boolean mouseReleased() {
		if (!dragging && resizeEdges == 0) return false;
		dragging = false;
		resizeEdges = 0;
		return true;
	}

	private int panelX() { return Math.round(screenWidth + (x - screenWidth) * progress); }
	private int resizeEdgesAt(double mx, double my) {
		if (dockSide.docked()) return 0;
		int left = panelX(), right = left + width, bottom = y + height;
		if (!inside(mx, my, left - BORDER, y - BORDER, right + BORDER, bottom + BORDER)) return 0;
		int edges = 0;
		if (Math.abs(mx - left) <= BORDER) edges |= 1;
		if (Math.abs(mx - right) <= BORDER) edges |= 2;
		if (Math.abs(my - y) <= BORDER) edges |= 4;
		if (Math.abs(my - bottom) <= BORDER) edges |= 8;
		return edges;
	}
	private void ensureGeometry() {
		if (screenWidth < 1 || screenHeight < 1 || dockSide.docked()) return;
		if (width < 0) width = Math.min(screenWidth - 8, Math.max(400, screenWidth * 3 / 4));
		if (height < 0) height = Math.min(screenHeight - 8, Math.max(280, screenHeight * 3 / 4));
		width = Math.min(width, screenWidth);
		height = Math.min(height, screenHeight);
		if (x < 0) x = (screenWidth - width) / 2;
		x = clamp(x, 0, Math.max(0, screenWidth - width));
		y = clamp(y, 0, Math.max(0, screenHeight - height));
	}
	private void rememberFloating() {
		floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height;
	}
	private void restoreFloating() {
		if (floatingWidth > 0) width = floatingWidth;
		if (floatingHeight > 0) height = floatingHeight;
		if (floatingX >= 0) x = floatingX;
		y = floatingY;
		ensureGeometry();
		animation.snapTo(x, y, width, height);
	}
	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}
}
