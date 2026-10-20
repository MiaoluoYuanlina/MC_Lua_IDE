package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentIdContext;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Small recursive JSON tree editor used for complex item-stack component values. */
final class JsonTreeEditor {
	void renderImGui() {
		refreshCatalogRevision(); rebuildRows();
		imgui.ImGui.pushID(System.identityHashCode(this));
		NativeUi.action("添加",this::beginAdd); NativeUi.next("编辑"); NativeUi.action("编辑",this::beginEdit);
		NativeUi.next("删除"); NativeUi.action("删除",this::deleteSelected); NativeUi.next("JSON"); NativeUi.action("JSON",this::beginRaw);
		imgui.ImGui.beginChild("tree",0,Math.max(100,imgui.ImGui.getContentRegionAvailY()-28),true);
		for(var row:List.copyOf(rows)) {
			imgui.ImGui.pushID(row.path().toString());
			imgui.ImGui.indent(Math.min(row.depth(),MAX_TREE_DEPTH)*12f);
			boolean present=resolveActual(row.path())!=null;
			if(!present)imgui.ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,.52f,.58f,.66f,1);
			int flags=imgui.flag.ImGuiTreeNodeFlags.NoTreePushOnOpen|imgui.flag.ImGuiTreeNodeFlags.OpenOnArrow|imgui.flag.ImGuiTreeNodeFlags.SpanAvailWidth;
			if(!row.container())flags|=imgui.flag.ImGuiTreeNodeFlags.Leaf;
			if(selected.equals(row.path()))flags|=imgui.flag.ImGuiTreeNodeFlags.Selected;
			imgui.ImGui.setNextItemOpen(!collapsed.contains(row.path()),imgui.flag.ImGuiCond.Always);
			imgui.ImGui.treeNodeEx(translatedRowLabel(row)+"  "+(row.container()?"":scalarText(row.value())),flags);
			if(imgui.ImGui.isItemClicked())selected=row.path();
			if(imgui.ImGui.isItemToggledOpen())toggleCollapsed(row.path());
			if(imgui.ImGui.isItemHovered()&&imgui.ImGui.isMouseDoubleClicked(0)) {
				selected=row.path(); if(row.addAction()){selected=selected.parent();beginAdd();}else if(!row.container())beginEdit();
			}
			if(translation!=null)NativeUi.hint(translation.description(pathSegments(row.path()),rawKey(row.path()),row.value()));
			if(imgui.ImGui.beginPopupContextItem("node_actions")) {
				selected=row.path();
				if(imgui.ImGui.menuItem("添加字段 / 元素"))beginAdd();
				if(imgui.ImGui.menuItem("编辑值"))beginEdit();
				if(imgui.ImGui.menuItem("启用 / 添加默认值")&&!present){materialize(selected);discovered.add(selected);changed();}
				if(imgui.ImGui.menuItem("删除"))deleteSelected();
				imgui.ImGui.endPopup();
			}
			if(!present)imgui.ImGui.popStyleColor();
			imgui.ImGui.unindent(Math.min(row.depth(),MAX_TREE_DEPTH)*12f); imgui.ImGui.popID();
		}
		imgui.ImGui.endChild();
		if(popupMode!=PopupMode.NONE)imgui.ImGui.openPopup("edit_node");
		imgui.ImGui.setNextWindowSize(580,560,imgui.flag.ImGuiCond.Appearing);
		if(imgui.ImGui.beginPopupModal("edit_node")) {
			if(registryPickerOpen) {
				String next=NativeUi.field(fieldPicker?"筛选字段":"筛选注册表",registryQuery);
				if(!next.equals(registryQuery)){registryQuery=next;filterRegistryTypes(true);}
				imgui.ImGui.beginChild("registries",0,Math.max(80,imgui.ImGui.getContentRegionAvailY()-50),true);
				for(int i=0;i<registryTypes.size();i++)if(imgui.ImGui.selectable(registryTypes.get(i).display(),i==registryTypeRow)){registryTypeRow=i;applySelectedRegistryType();break;}
				imgui.ImGui.endChild();
			} else {
				if(needsKeyField()) {
					String next=NativeUi.field("键名",keyText);
					if(imgui.ImGui.isItemActive())inputFocus=InputFocus.KEY;
					if(!next.equals(keyText)){keyText=next;keyCursor=next.length();refreshIdSuggestions(true);}
				}
				if(popupMode!=PopupMode.RAW)draftType=NativeUi.combo("类型",draftType,ValueType.values());
				String next=popupMode==PopupMode.RAW?NativeUi.multiline("##raw",valueText,220):NativeUi.field("值",valueText);
				if(imgui.ImGui.isItemActive())inputFocus=InputFocus.VALUE;
				if(!next.equals(valueText)){valueText=next;valueCursor=next.length();refreshIdSuggestions(true);}
				NativeUi.action("选择 ID 类型",this::openRegistryPicker);
				imgui.ImGui.textDisabled(suggestionLoading?"正在读取 ID…":suggestionLabel);
				imgui.ImGui.beginChild("ids",0,Math.max(60,imgui.ImGui.getContentRegionAvailY()-55),true);
				for(int i=0;i<suggestions.size();i++)if(imgui.ImGui.selectable(suggestions.get(i).label(),i==suggestionRow)){suggestionRow=i;applySelectedSuggestion();}
				imgui.ImGui.endChild();
			}
			if(imgui.ImGui.button("确定")){if(registryPickerOpen)applySelectedRegistryType();else confirmPopup();if(popupMode==PopupMode.NONE)imgui.ImGui.closeCurrentPopup();}
			imgui.ImGui.sameLine(); if(imgui.ImGui.button("取消")){popupMode=PopupMode.NONE;registryPickerOpen=false;imgui.ImGui.closeCurrentPopup();}
			imgui.ImGui.endPopup();
		}
		imgui.ImGui.popID();
	}
	private static final int TOOLBAR_HEIGHT = 22;
	private static final int ROW_HEIGHT = 18;
	private static final int MAX_TREE_NODES = 4_096;
	private static final int MAX_TREE_DEPTH = 64;

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<JsonElement> changeListener;
	private final Consumer<String> feedback;
	private NodeTranslation translation;
	private JsonElement root = new JsonObject();
	private String componentId = "";
	private List<LuaComponentEditorData.IdHint> componentIdHints = List.of();
	private Map<String, List<LuaComponentEditorData.IdHint>> componentHintCatalog = Map.of();
	private boolean componentForest;
	private Map<String, LuaComponentEditorData.ComponentInfo> componentCatalog = Map.of();
	private ComponentTreeSchema.Node forestSchema;
	private ComponentTreeSchema.Node observedSchema;
	private ComponentTreeSchema.Node nestedItemSchema;
	private final Set<Path> discovered = new HashSet<>();
	private Set<String> visibleComponents = Set.of();
	private boolean fieldPicker;
	private Map<String, ComponentTreeSchema.Node> fieldOptions = Map.of();
	private final Set<Path> collapsed = new HashSet<>();
	private List<Row> rows = List.of();
	private Path selected = Path.root();
	private int scroll;
	private PopupMode popupMode = PopupMode.NONE;
	private Path popupPath = Path.root();
	private ValueType draftType = ValueType.STRING;
	private JsonElement popupOriginal = JsonNull.INSTANCE;
	private String keyText = "";
	private String valueText = "";
	private int keyCursor;
	private int valueCursor;
	private InputFocus inputFocus = InputFocus.VALUE;
	private int x;
	private int top;
	private int width;
	private int bottom;
	private Rect addButton;
	private Rect editButton;
	private Rect deleteButton;
	private Rect rawButton;
	private Rect keyField;
	private Rect typeButton;
	private Rect registryButton;
	private Rect valueField;
	private Rect confirmButton;
	private Rect cancelButton;
	private Rect popupBounds;
	private Rect suggestionArea;
	private String suggestionRegistry = "";
	private String suggestionLabel = "";
	private List<LuaInsertCatalog.Entry> suggestionCatalog = List.of();
	private List<LuaInsertCatalog.Entry> suggestions = List.of();
	private int suggestionRow;
	private int suggestionRevision;
	private int observedCatalogRevision = -1;
	private boolean suggestionLoading;
	private IdHint manualIdHint;
	private InputFocus manualHintFocus;
	private boolean registryPickerOpen;
	private boolean registryTypesLoading;
	private int registryTypesRevision;
	private String registryQuery = "";
	private int registryQueryCursor;
	private List<LuaRegistryCatalogData.RegistryType> registryTypeCatalog = List.of();
	private List<LuaRegistryCatalogData.RegistryType> registryTypes = List.of();
	private int registryTypeRow;
	private Rect registrySearchField;
	private Rect registryListArea;
	private Rect registryAutoButton;
	private Rect registryCloseButton;

	JsonTreeEditor(Minecraft minecraft, Font font, Consumer<JsonElement> changeListener,
			Consumer<String> feedback) {
		this.minecraft = minecraft;
		this.font = font;
		this.changeListener = changeListener;
		this.feedback = feedback;
	}

	void setTranslation(NodeTranslation translation) {
		this.translation = translation;
	}

	interface NodeTranslation {
		default String rootLabel() { return LuaUiText.text("数据包 JSON"); }
		String label(List<String> path, String key, JsonElement value);
		String description(List<String> path, String key, JsonElement value);
		String valueLabel(List<String> path, JsonElement value);
	}

	void setValue(String componentId, JsonElement value, List<LuaComponentEditorData.IdHint> idHints) {
		setValue(componentId, value, idHints, Map.of());
	}

	void setValue(String componentId, JsonElement value, List<LuaComponentEditorData.IdHint> idHints,
			Map<String, List<LuaComponentEditorData.IdHint>> catalog) {
		componentForest = false;
		observedSchema = null;
		componentHintCatalog = catalog;
		this.componentId = componentId == null ? "" : componentId;
		this.componentIdHints = idHints == null ? List.of() : List.copyOf(idHints);
		root = value == null ? JsonNull.INSTANCE : value.deepCopy();
		collapsed.clear();
		selected = Path.root();
		scroll = 0;
		popupMode = PopupMode.NONE;
		registryPickerOpen = false;
		manualIdHint = null;
		ensureRegistryTypesLoaded();
		rebuildRows();
	}

	void setComponentCatalog(LuaComponentEditorData data, JsonObject patch) {
		setValue("mc_lua_ide:component_forest", patch, List.of(), data.componentIdHints());
		componentForest = true;
		Map<String, LuaComponentEditorData.ComponentInfo> values = new java.util.LinkedHashMap<>();
		data.components().forEach(info -> values.put(info.id(), info));
		componentCatalog = values;
		Map<String, ComponentTreeSchema.Node> schemas = new java.util.LinkedHashMap<>();
		values.forEach((id, info) -> schemas.put(id, info.schema()));
		forestSchema = new ComponentTreeSchema.Node(data.kind().equals("item") ? "全部物品组件"
				: data.kind().equals("block") ? "方块状态与数据" : "实体状态与组件",
				"object", new JsonObject(), schemas, null, List.of(), "", false);
		nestedItemSchema = data.kind().equals("item") ? forestSchema : ComponentTreeSchema.itemComponents();
		visibleComponents = Set.copyOf(values.keySet());
		discovered.clear(); collapsed.clear();
		rebuildRows();
	}

	void setObservedSchema(ComponentTreeSchema.Node schema) {
		observedSchema = schema;
		discovered.clear();
		collapsed.clear();
		rebuildRows();
	}

	private boolean guidedTree() { return componentForest || observedSchema != null; }

	void filterComponents(List<LuaComponentEditorData.ComponentInfo> components) {
		visibleComponents = components.stream().map(LuaComponentEditorData.ComponentInfo::id).collect(java.util.stream.Collectors.toSet());
		scroll = 0;
		rebuildRows();
	}

	private ComponentTreeSchema.Node schema(Path path) {
		if (!guidedTree()) return null;
		ComponentTreeSchema.Node node = componentForest ? componentMap() : observedSchema;
		Path walked = Path.root();
		for (Step step : path.steps()) {
			if (node == null) return null;
			node = ComponentTreeSchema.variant(node, resolveActual(walked));
			if (componentForest && node.kind().equals("components")) node = nestedItemSchema;
			node = step.objectKey() ? node.fields().getOrDefault(step.key(), node.element()) : node.element();
			walked = step.objectKey() ? walked.childKey(step.key()) : walked.childIndex(step.index());
		}
		node = ComponentTreeSchema.variant(node, resolveActual(path));
		return componentForest && node != null && node.kind().equals("components") ? nestedItemSchema : node;
	}

	private ComponentTreeSchema.Node componentMap() {
		return forestSchema;
	}

	private boolean guidedField() {
		return guidedTree() && popupMode == PopupMode.EDIT && schema(popupPath) != null && !editableKey(popupPath);
	}

	private boolean editableKey(Path path) {
		if (path.steps().isEmpty() || !path.last().objectKey()) return false;
		ComponentTreeSchema.Node parent = schema(path.parent());
		return parent == null || parent.custom() && parent.element() != null || !parent.fields().containsKey(path.last().key());
	}

	boolean hasPopup() {
		return popupMode != PopupMode.NONE;
	}

	boolean contains(double mouseX, double mouseY) {
		return inside(mouseX, mouseY, x, top, x + width, bottom);
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int top, int width, int bottom) {
		refreshCatalogRevision();
		this.x = x;
		this.top = top;
		this.width = width;
		this.bottom = bottom;
		rebuildRows();
		graphics.fill(x, top, x + width, bottom, 0xFF080B10);
		graphics.outline(x, top, width, Math.max(1, bottom - top), 0xFF554666);

		JsonElement selectedValue = resolve(selected);
		boolean container = selectedValue != null && (selectedValue.isJsonObject() || selectedValue.isJsonArray());
		int toolbarY = top + 2;
		int buttonWidth = Math.max(24, Math.min(58, Math.max(1, (width - 12) / 4)));
		boolean compactButtons = buttonWidth < 38;
		String addLabel = componentId.equals("minecraft:enchantments") && selected.steps().isEmpty()
				? compactButtons ? "＋附" : "＋附魔" : compactButtons ? "＋" : "＋子项";
		int toolbarRight = x + width - 4;
		rawButton = new Rect(toolbarRight - buttonWidth, toolbarY, buttonWidth, 18);
		deleteButton = new Rect(rawButton.x() - buttonWidth - 2, toolbarY, buttonWidth, 18);
		editButton = new Rect(deleteButton.x() - buttonWidth - 2, toolbarY, buttonWidth, 18);
		addButton = new Rect(editButton.x() - buttonWidth - 2, toolbarY, buttonWidth, 18);
		String path = selectedLabel();
		int labelWidth = Math.max(12, addButton.x() - x - 8);
		graphics.text(font, font.plainSubstrByWidth(path, labelWidth), x + 5, toolbarY + 5, 0xFFBBA7CF);
		drawButton(graphics, mouseX, mouseY, addButton, addLabel,
				container ? 0xFF355C4E : 0xFF242A31, container ? 0xFF447565 : 0xFF242A31);
		boolean searchableId = selectedValue != null && selectedValue.isJsonPrimitive()
				&& selectedValue.getAsJsonPrimitive().isString();
		drawButton(graphics, mouseX, mouseY, editButton,
				searchableId ? (compactButtons ? "搜/改" : "搜 ID/改") : (compactButtons ? "改" : "修改"),
				0xFF4B3865, 0xFF674B87);
		drawButton(graphics, mouseX, mouseY, deleteButton, compactButtons ? "删" : LuaUiText.text("删除"),
				selected.steps().isEmpty() ? 0xFF242A31 : 0xFF61363C,
				selected.steps().isEmpty() ? 0xFF242A31 : 0xFF85454E);
		drawButton(graphics, mouseX, mouseY, rawButton, compactButtons ? "{}" : "源码", 0xFF304B64, 0xFF3E6485);

		int listTop = top + TOOLBAR_HEIGHT;
		int visible = visibleRows();
		scroll = clamp(scroll, 0, Math.max(0, rows.size() - visible));
		graphics.enableScissor(x + 1, listTop, x + width - 1, bottom - 1);
		for (int visual = 0; visual < visible; visual++) {
			int absolute = scroll + visual;
			if (absolute >= rows.size()) break;
			Row row = rows.get(absolute);
			int rowY = listTop + visual * ROW_HEIGHT;
			if (row.path().equals(selected)) graphics.fill(x + 2, rowY, x + width - 2, rowY + ROW_HEIGHT, 0xFF342747);
			int indent = Math.min(row.depth(), 18) * 12;
			int textX = x + 6 + indent;
			if (row.container()) {
				graphics.text(font, collapsed.contains(row.path()) ? "▶" : "▼", textX, rowY + 5, 0xFFB78AE8);
			} else {
				graphics.text(font, "·", textX + 2, rowY + 5, 0xFF71677C);
			}
			textX += 12;
			boolean present = resolveActual(row.path()) != null;
			if (guidedTree()) {
				for (int level = 1; level <= Math.min(row.depth(), 18); level++) {
					int branchX = x + 6 + level * 12 - 6;
					graphics.fill(branchX, rowY, branchX + 1, rowY + ROW_HEIGHT, 0xFF302A3B);
				}
			}
			String shown = translatedRowLabel(row) + (row.addAction() || guidedTree() && row.path().steps().isEmpty()
					? "" : "  " + translatedPreview(row));
			graphics.text(font, font.plainSubstrByWidth(shown, Math.max(8, x + width - textX - 50)),
					textX, rowY + 5, row.path().equals(selected) ? 0xFFFFFFFF : present ? 0xFFD8CCE4 : 0xFF8E859C);
			if (translation != null && popupMode == PopupMode.NONE && !row.addAction()
					&& inside(mouseX, mouseY, x + 2, rowY, x + width - 2, rowY + ROW_HEIGHT)) {
				List<String> nodePath = pathSegments(row.path());
				String description = translation.description(nodePath, rawKey(row.path()), row.value());
				if (description != null && !description.isBlank()) graphics.setComponentTooltipForNextFrame(font,
						List.of(Component.literal(translatedRowLabel(row)), Component.literal(description),
								Component.literal("JSON 路径：" + (nodePath.isEmpty() ? "/" : "/" + String.join("/", nodePath)))),
						mouseX, mouseY);
			}
			String type = guidedTree() && !row.path().steps().isEmpty()
					? row.value().isJsonArray() ? "＋条目" : !present ? "＋添加" : row.container() ? "＋字段" : "编辑"
					: typeLabel(row.value());
			if (componentForest && row.path().steps().size() == 1 && componentCatalog.containsKey(row.path().last().key())) {
				var info = componentCatalog.get(row.path().last().key());
				type = present ? "✓ 撤销" : info.relevance() == 2 ? "默认 ＋" : info.relevance() == 1 ? "可用 ＋" : "高级 ＋";
			}
			if (row.addAction()) type = "添加";
			graphics.text(font, type, x + width - font.width(type) - 6, rowY + 5, typeColor(row.value()));
		}
		graphics.disableScissor();

	}

	/**
	 * Renders the modal editor after the owning window has drawn all of its normal
	 * controls. Keeping this as a separate pass prevents footer buttons and other
	 * sibling controls from being painted over the popup.
	 */
	void renderPopupOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		refreshCatalogRevision();
		if (popupMode != PopupMode.NONE) renderPopup(graphics, mouseX, mouseY);
	}

	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (popupMode != PopupMode.NONE) {
			if ((popupBounds != null && popupBounds.contains(event.x(), event.y()))
					|| contains(event.x(), event.y())) return clickPopup(event.x(), event.y());
			return false;
		}
		if (!contains(event.x(), event.y())) return false;
		if (addButton != null && addButton.contains(event.x(), event.y())) {
			beginAdd();
			return true;
		}
		if (editButton != null && editButton.contains(event.x(), event.y())) {
			beginEdit();
			return true;
		}
		if (deleteButton != null && deleteButton.contains(event.x(), event.y())) {
			deleteSelected();
			return true;
		}
		if (rawButton != null && rawButton.contains(event.x(), event.y())) {
			beginRaw();
			return true;
		}
		int listTop = top + TOOLBAR_HEIGHT;
		if (event.y() < listTop || event.y() >= bottom) return true;
		int index = scroll + (int) (event.y() - listTop) / ROW_HEIGHT;
		if (index < 0 || index >= rows.size()) return true;
		Row row = rows.get(index);
		selected = row.path();
		if (guidedTree()) {
			if (row.addAction()) { selected = selected.parent(); beginAdd(); return true; }
			if (event.x() >= x + width - 58 && !selected.steps().isEmpty()) {
				if (componentForest && selected.steps().size() == 1 && resolveActual(selected) != null) {
					deleteSelected();
				} else if (resolveActual(selected) == null) {
					materialize(selected); discovered.add(selected); collapsed.remove(selected); changed();
					if (!row.container() && !row.value().getAsJsonPrimitive().isBoolean()) beginEdit();
				} else if (row.container()) beginAdd(); else beginEdit();
			} else if (row.container()) toggleCollapsed(selected); else beginEdit();
			return true;
		}
		int disclosureRight = x + 20 + Math.min(row.depth(), 18) * 12;
		if (row.container() && (event.x() <= disclosureRight || doubleClick)) {
			toggleCollapsed(row.path());
		} else if (doubleClick) {
			beginEdit();
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (popupMode != PopupMode.NONE) {
			if (registryPickerOpen && registryListArea != null && registryListArea.contains(mouseX, mouseY)) {
				registryTypeRow = clamp(registryTypeRow - (int) Math.round(amount * 3), 0, Math.max(0, registryTypes.size() - 1));
			} else if (suggestionArea != null && suggestionArea.contains(mouseX, mouseY)) {
				suggestionRow = clamp(suggestionRow - (int) Math.round(amount * 3), 0, Math.max(0, suggestions.size() - 1));
			}
			return true;
		}
		if (!contains(mouseX, mouseY)) return false;
		scroll = clamp(scroll - (int) Math.round(amount * 3), 0,
				Math.max(0, rows.size() - visibleRows()));
		return true;
	}

	boolean keyPressed(KeyEvent event) {
		if (popupMode != PopupMode.NONE) return keyPressedPopup(event);
		if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
			moveSelection(event.key() == InputConstants.KEY_UP ? -1 : 1);
			return true;
		}
		if (event.key() == InputConstants.KEY_LEFT) {
			JsonElement value = resolve(selected);
			if (value != null && isContainer(value) && !collapsed.contains(selected)) collapsed.add(selected);
			else if (!selected.steps().isEmpty()) selected = selected.parent();
			rebuildRows();
			ensureSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_RIGHT) {
			JsonElement value = resolve(selected);
			if (value != null && isContainer(value)) collapsed.remove(selected);
			rebuildRows();
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			if (rows.stream().anyMatch(row -> row.path().equals(selected) && row.addAction())) { selected = selected.parent(); beginAdd(); return true; }
			JsonElement value = resolve(selected);
			if (value != null && isContainer(value)) toggleCollapsed(selected); else beginEdit();
			return true;
		}
		if (event.key() == InputConstants.KEY_INSERT) {
			beginAdd();
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE) {
			deleteSelected();
			return true;
		}
		return false;
	}

	boolean commitPendingEdit() {
		if (popupMode == PopupMode.NONE) return true;
		if (registryPickerOpen) {
			feedback.accept("请先确认或取消 ID 选择");
			return false;
		}
		confirmPopup();
		return popupMode == PopupMode.NONE;
	}

	boolean charTyped(CharacterEvent event) {
		if (popupMode == PopupMode.NONE) return false;
		int codepoint = event.codepoint();
		if (!Character.isISOControl(codepoint)) {
			String text = new String(Character.toChars(codepoint));
			if (registryPickerOpen) {
				registryQuery = registryQuery.substring(0, registryQueryCursor) + text
						+ registryQuery.substring(registryQueryCursor);
				registryQueryCursor += text.length();
				filterRegistryTypes(true);
			} else {
				insertText(text);
			}
		}
		return true;
	}

	private void renderPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!fieldPicker) refreshIdSuggestions(false);
		graphics.fill(x + 1, top + 1, x + width - 1, bottom - 1, 0xA8000000);
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int screenHeight = minecraft.getWindow().getGuiScaledHeight();
		// A modal ID browser must not inherit the width of a narrow docked tree pane.
		int popupWidth = Math.max(1, Math.min(440, screenWidth - 12));
		boolean narrowPopup = popupWidth < 190;
		int desiredHeight = popupMode == PopupMode.RAW ? 104 : needsKeyField() ? 292 : 268;
		int popupHeight = Math.max(1, Math.min(desiredHeight, screenHeight - 12));
		int px = clamp(x + (width - popupWidth) / 2, 6, Math.max(6, screenWidth - popupWidth - 6));
		int py = clamp((top + bottom - popupHeight) / 2, 6, Math.max(6, screenHeight - popupHeight - 6));
		popupBounds = new Rect(px, py, popupWidth, popupHeight);
		if (fieldPicker) { renderRegistryPicker(graphics, mouseX, mouseY, px, py, popupWidth, popupHeight); return; }
		graphics.fill(px, py, px + popupWidth, py + popupHeight, 0xFF171D29);
		graphics.outline(px, py, popupWidth, popupHeight, 0xFFC28DFF);
		String title = popupMode == PopupMode.ADD ? translation == null ? "新增自定义字段" : "新增维度字段"
				: popupMode == PopupMode.EDIT ? guidedField() ? "编辑：" + schema(popupPath).label()
					: translation == null ? "修改树节点" : "修改维度字段"
					: translation == null ? "高级：编辑完整 JSON" : "高级：编辑完整维度 JSON";
		title = font.plainSubstrByWidth(title, popupWidth - 16);
		graphics.text(font, title, px + 8, py + 8, 0xFFE4CDFF);
		if (!suggestionLabel.isEmpty()) {
			String hint = "ID 搜索 · " + suggestionLabel;
			if (font.width(title) + font.width(hint) + 28 <= popupWidth) {
				graphics.text(font, hint, px + popupWidth - font.width(hint) - 8, py + 8, 0xFF79C9FF);
			}
		}
		int rowY = py + 25;
		keyField = null;
		typeButton = null;
		registryButton = null;
		valueField = null;
		if (popupMode == PopupMode.RAW) {
			valueField = new Rect(px + 8, rowY, popupWidth - 16, 20);
			drawField(graphics, valueField, valueText, "粘贴或输入 JSON…", inputFocus == InputFocus.VALUE);
			graphics.text(font, translation == null ? "会替换当前组件的整棵树；确认后仍会经过服务端 Codec 校验"
					: "会替换当前维度的整棵树；插入前会由服务端校验",
					px + 8, rowY + 25, 0xFF9A8AAA);
			rowY += 47;
		} else {
			if (needsKeyField()) {
				graphics.text(font, componentId.equals("minecraft:enchantments") ? "附魔 ID" : "键", px + 8, rowY + 6, 0xFFBBA7CF);
				if (narrowPopup) rowY += 13;
				keyField = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
						: new Rect(px + 62, rowY, popupWidth - 70, 20);
				drawField(graphics, keyField, keyText,
						componentId.equals("minecraft:enchantments") ? "minecraft:sharpness" : "键名",
						inputFocus == InputFocus.KEY);
				rowY += 24;
			}
			if (!guidedField()) {
			graphics.text(font, LuaUiText.text("类型"), px + 8, rowY + 6, 0xFFBBA7CF);
			if (narrowPopup) rowY += 13;
			typeButton = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
					: new Rect(px + 62, rowY, Math.min(105, popupWidth - 70), 20);
			drawButton(graphics, mouseX, mouseY, typeButton, draftType.label, 0xFF3C3150, 0xFF58436F);
			if (!narrowPopup) graphics.text(font, "点击切换", typeButton.right() + 6, rowY + 6, 0xFF796D84);
			rowY += 24;
			}
			if ((draftType == ValueType.STRING || inputFocus == InputFocus.KEY) && !suggestionRegistry.equals("@choices")) {
			graphics.text(font, "ID类型", px + 8, rowY + 6, 0xFFBBA7CF);
			if (narrowPopup) rowY += 13;
			IdHint activeHint = currentIdHint();
			registryButton = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
					: new Rect(px + 62, rowY, popupWidth - 70, 20);
			drawButton(graphics, mouseX, mouseY, registryButton,
					activeHint == null ? "自动识别 / 选择…" : activeHint.label() + "  ▾",
					0xFF263E51, 0xFF345B76);
			rowY += 24;
			}
			if (draftType.hasTextValue()) {
				graphics.text(font, componentId.equals("minecraft:enchantments") ? "等级" : "值", px + 8, rowY + 6, 0xFFBBA7CF);
				if (narrowPopup) rowY += 13;
				valueField = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
						: new Rect(px + 62, rowY, popupWidth - 70, 20);
				drawField(graphics, valueField, valueText, draftType.hint, inputFocus == InputFocus.VALUE);
				rowY += 24;
			}
			if (componentId.equals("minecraft:enchantments") && py + popupHeight - rowY > 92) {
				graphics.text(font, "键可填写任意原版或 Mod 附魔 ID，整数值为附魔等级", px + 8, rowY + 3, 0xFF9A8AAA);
				rowY += 17;
			}
		}
		int buttonY = py + popupHeight - 26;
		suggestionArea = null;
		int suggestionTop = rowY + 2;
		int availableSuggestionHeight = buttonY - suggestionTop - 3;
		if (!suggestionRegistry.isEmpty() && availableSuggestionHeight >= ROW_HEIGHT) {
			int visibleSuggestions = Math.max(1, Math.min(5, availableSuggestionHeight / ROW_HEIGHT));
			suggestionArea = new Rect(px + 8, suggestionTop, popupWidth - 16,
					visibleSuggestions * ROW_HEIGHT);
			graphics.fill(suggestionArea.x(), suggestionArea.y(), suggestionArea.right(), suggestionArea.bottom(), 0xFF090D14);
			graphics.outline(suggestionArea.x(), suggestionArea.y(), suggestionArea.width(), suggestionArea.height(), 0xFF476A83);
			if (suggestionLoading) {
				graphics.text(font, "正在读取服务器注册表…", suggestionArea.x() + 5, suggestionArea.y() + 5, 0xFF91AFC3);
			} else if (suggestions.isEmpty()) {
				graphics.text(font, "无匹配 ID；可清空搜索或切换 ID 类型", suggestionArea.x() + 5,
						suggestionArea.y() + 5, 0xFFB5A7BD);
			} else {
				int first = suggestionWindowStart(visibleSuggestions);
				for (int visual = 0; visual < visibleSuggestions; visual++) {
					int index = first + visual;
					if (index >= suggestions.size()) break;
					int sy = suggestionArea.y() + visual * ROW_HEIGHT;
					if (index == suggestionRow) graphics.fill(suggestionArea.x() + 1, sy + 1,
							suggestionArea.right() - 1, sy + ROW_HEIGHT, 0xFF304C63);
					LuaInsertCatalog.Entry entry = suggestions.get(index);
					String entryLabel = entry.label();
					if (translation != null) {
						String localized = translation.valueLabel(pathSegments(popupTargetPath()),
								new JsonPrimitive(entry.value()));
						if (localized != null && !localized.isBlank()) entryLabel = localized + " · " + entry.value();
					}
					graphics.text(font, font.plainSubstrByWidth(entryLabel, suggestionArea.width() - 10),
							suggestionArea.x() + 5, sy + 5, index == suggestionRow ? 0xFFFFFFFF : 0xFFC8D7E2);
				}
			}
		}
		int actionWidth = Math.max(34, Math.min(56, (popupWidth - 22) / 2));
		confirmButton = new Rect(px + popupWidth - actionWidth * 2 - 14, buttonY, actionWidth, 20);
		cancelButton = new Rect(px + popupWidth - actionWidth - 8, buttonY, actionWidth, 20);
		drawButton(graphics, mouseX, mouseY, confirmButton, LuaUiText.text("确认"), 0xFF4F6E58, 0xFF668E70);
		drawButton(graphics, mouseX, mouseY, cancelButton, LuaUiText.text("取消"), 0xFF553C4C, 0xFF754F65);
		if (registryPickerOpen) renderRegistryPicker(graphics, mouseX, mouseY, px, py, popupWidth, popupHeight);
	}

	private void renderRegistryPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int px, int py, int popupWidth, int popupHeight) {
		graphics.fill(px + 1, py + 1, px + popupWidth - 1, py + popupHeight - 1, 0xFF131A25);
		graphics.outline(px, py, popupWidth, popupHeight, 0xFF65B8E8);
		graphics.text(font, fieldPicker ? "添加字段 · 自动匹配类型" : "选择服务器注册表类型", px + 8, py + 8, 0xFFD7EFFF);
		registrySearchField = new Rect(px + 8, py + 24, popupWidth - 16, 20);
		drawFieldAtCursor(graphics, registrySearchField, registryQuery,
				LuaUiText.text("搜索类型 / 命名空间 / 中文首拼…", "Search types or namespaces…"), registryQueryCursor, true);
		int listTop = py + 49;
		int buttonY = py + popupHeight - 26;
		int visible = Math.max(1, (buttonY - listTop - 4) / ROW_HEIGHT);
		registryListArea = new Rect(px + 8, listTop, popupWidth - 16, visible * ROW_HEIGHT);
		graphics.fill(registryListArea.x(), registryListArea.y(), registryListArea.right(),
				registryListArea.bottom(), 0xFF080D14);
		graphics.outline(registryListArea.x(), registryListArea.y(), registryListArea.width(),
				registryListArea.height(), 0xFF365F78);
		if (registryTypesLoading && !fieldPicker) {
			graphics.text(font, "正在读取服务器全部注册表类型…", registryListArea.x() + 5,
					registryListArea.y() + 5, 0xFF91AFC3);
		} else if (registryTypes.isEmpty()) {
			graphics.text(font, fieldPicker ? "没有可添加的字段（已有字段可直接展开编辑）" : "没有匹配的注册表类型", registryListArea.x() + 5,
					registryListArea.y() + 5, 0xFF9A8AAA);
		} else {
			int first = registryTypeWindowStart(visible);
			for (int visual = 0; visual < visible; visual++) {
				int index = first + visual;
				if (index >= registryTypes.size()) break;
				int rowY = registryListArea.y() + visual * ROW_HEIGHT;
				if (index == registryTypeRow) graphics.fill(registryListArea.x() + 1, rowY + 1,
						registryListArea.right() - 1, rowY + ROW_HEIGHT, 0xFF2E5067);
				String display = fieldPicker ? registryTypes.get(index).label() + " · " + registryTypes.get(index).id() : registryTypes.get(index).display();
				graphics.text(font, font.plainSubstrByWidth(display, registryListArea.width() - 10),
						registryListArea.x() + 5, rowY + 5,
						index == registryTypeRow ? 0xFFFFFFFF : 0xFFC8DCE8);
			}
		}
		registryAutoButton = new Rect(px + 8, buttonY, Math.min(82, popupWidth / 3), 20);
		registryCloseButton = new Rect(px + popupWidth - 58, buttonY, 50, 20);
		drawButton(graphics, mouseX, mouseY, registryAutoButton, fieldPicker ? "展开字段" : "恢复自动", 0xFF3A4650, 0xFF526674);
		drawButton(graphics, mouseX, mouseY, registryCloseButton, LuaUiText.text("返回"), 0xFF553C4C, 0xFF754F65);
	}

	private boolean clickPopup(double mouseX, double mouseY) {
		if (registryPickerOpen) return clickRegistryPicker(mouseX, mouseY);
		if (keyField != null && keyField.contains(mouseX, mouseY)) {
			inputFocus = InputFocus.KEY;
			keyCursor = offsetAt(keyText, mouseX, keyField.x() + 5, keyField.width() - 10);
			refreshIdSuggestions(true);
			return true;
		}
		if (valueField != null && valueField.contains(mouseX, mouseY)) {
			inputFocus = InputFocus.VALUE;
			valueCursor = offsetAt(valueText, mouseX, valueField.x() + 5, valueField.width() - 10);
			refreshIdSuggestions(true);
			return true;
		}
		if (typeButton != null && typeButton.contains(mouseX, mouseY)) {
			draftType = draftType.next();
			valueText = draftType.defaultValue;
			valueCursor = valueText.length();
			if (!draftType.hasTextValue()) inputFocus = needsKeyField() ? InputFocus.KEY : InputFocus.VALUE;
			refreshIdSuggestions(true);
			return true;
		}
		if (registryButton != null && registryButton.contains(mouseX, mouseY)) {
			openRegistryPicker();
			return true;
		}
		if (suggestionArea != null && suggestionArea.contains(mouseX, mouseY) && !suggestionLoading) {
			int visible = Math.max(1, suggestionArea.height() / ROW_HEIGHT);
			int index = suggestionWindowStart(visible) + (int) (mouseY - suggestionArea.y()) / ROW_HEIGHT;
			if (index >= 0 && index < suggestions.size()) {
				suggestionRow = index;
				applySelectedSuggestion();
			}
			return true;
		}
		if (confirmButton != null && confirmButton.contains(mouseX, mouseY)) {
			confirmPopup();
			return true;
		}
		if (cancelButton != null && cancelButton.contains(mouseX, mouseY)) {
			popupMode = PopupMode.NONE;
			return true;
		}
		return true;
	}

	private boolean clickRegistryPicker(double mouseX, double mouseY) {
		if (registrySearchField != null && registrySearchField.contains(mouseX, mouseY)) {
			registryQueryCursor = offsetAt(registryQuery, mouseX, registrySearchField.x() + 5,
					registrySearchField.width() - 10);
			return true;
		}
		if (registryListArea != null && registryListArea.contains(mouseX, mouseY) && (!registryTypesLoading || fieldPicker)) {
			int visible = Math.max(1, registryListArea.height() / ROW_HEIGHT);
			int index = registryTypeWindowStart(visible)
					+ (int) (mouseY - registryListArea.y()) / ROW_HEIGHT;
			if (index >= 0 && index < registryTypes.size()) {
				registryTypeRow = index;
				applySelectedRegistryType();
			}
			return true;
		}
		if (registryAutoButton != null && registryAutoButton.contains(mouseX, mouseY)) {
			if (fieldPicker) {
				fieldPicker = false; registryPickerOpen = false; popupMode = PopupMode.NONE;
				discovered.add(selected); collapsed.remove(selected); rebuildRows(); return true;
			}
			manualIdHint = null;
			registryPickerOpen = false;
			refreshIdSuggestions(true);
			feedback.accept("已恢复按组件字段自动识别 ID 类型");
			return true;
		}
		if (registryCloseButton != null && registryCloseButton.contains(mouseX, mouseY)) {
			if (fieldPicker) { fieldPicker = false; popupMode = PopupMode.NONE; }
			registryPickerOpen = false;
			return true;
		}
		return true;
	}

	private boolean keyPressedPopup(KeyEvent event) {
		if (registryPickerOpen) return keyPressedRegistryPicker(event);
		if (event.key() == InputConstants.KEY_ESCAPE) {
			popupMode = PopupMode.NONE;
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			if (shouldAcceptSuggestion()) {
				applySelectedSuggestion();
				return true;
			}
			confirmPopup();
			return true;
		}
		if (event.key() == InputConstants.KEY_TAB) {
			if (!suggestions.isEmpty() && !suggestionLoading) {
				applySelectedSuggestion();
				return true;
			}
			if (needsKeyField() && draftType.hasTextValue()) {
				inputFocus = inputFocus == InputFocus.KEY ? InputFocus.VALUE : InputFocus.KEY;
			} else if (needsKeyField()) {
				inputFocus = InputFocus.KEY;
			} else {
				inputFocus = InputFocus.VALUE;
			}
			refreshIdSuggestions(true);
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			insertText(minecraft.keyboardHandler.getClipboard().replaceAll("\\R", " "));
			refreshIdSuggestions(true);
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			if (inputFocus == InputFocus.KEY) {
				keyText = "";
				keyCursor = 0;
			} else {
				valueText = "";
				valueCursor = 0;
			}
			refreshIdSuggestions(true);
			return true;
		}
		if ((event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)
				&& !suggestions.isEmpty() && !suggestionLoading) {
			suggestionRow = Math.floorMod(suggestionRow
					+ (event.key() == InputConstants.KEY_UP ? -1 : 1), suggestions.size());
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			removeCharacter(true);
			refreshIdSuggestions(true);
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE) {
			removeCharacter(false);
			refreshIdSuggestions(true);
			return true;
		}
		if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT
				|| event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
			moveCursor(event.key());
			return true;
		}
		return true;
	}

	private boolean keyPressedRegistryPicker(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (fieldPicker) { fieldPicker = false; popupMode = PopupMode.NONE; }
			registryPickerOpen = false;
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			applySelectedRegistryType();
			return true;
		}
		if ((event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)
				&& !registryTypes.isEmpty() && (!registryTypesLoading || fieldPicker)) {
			registryTypeRow = Math.floorMod(registryTypeRow
					+ (event.key() == InputConstants.KEY_UP ? -1 : 1), registryTypes.size());
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			registryQuery = "";
			registryQueryCursor = 0;
			filterRegistryTypes(true);
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE && registryQueryCursor > 0) {
			int previous = registryQuery.offsetByCodePoints(registryQueryCursor, -1);
			registryQuery = registryQuery.substring(0, previous) + registryQuery.substring(registryQueryCursor);
			registryQueryCursor = previous;
			filterRegistryTypes(true);
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE && registryQueryCursor < registryQuery.length()) {
			int next = registryQuery.offsetByCodePoints(registryQueryCursor, 1);
			registryQuery = registryQuery.substring(0, registryQueryCursor) + registryQuery.substring(next);
			filterRegistryTypes(true);
			return true;
		}
		if (event.key() == InputConstants.KEY_LEFT) registryQueryCursor = Math.max(0, registryQueryCursor - 1);
		if (event.key() == InputConstants.KEY_RIGHT) registryQueryCursor = Math.min(registryQuery.length(), registryQueryCursor + 1);
		if (event.key() == InputConstants.KEY_HOME) registryQueryCursor = 0;
		if (event.key() == InputConstants.KEY_END) registryQueryCursor = registryQuery.length();
		return true;
	}

	private void beginAdd() {
		if (guidedTree()) {
			ComponentTreeSchema.Node node = schema(selected);
			JsonElement current = resolve(selected);
			if (current != null && current.isJsonArray() && node != null && node.element() != null) {
				JsonArray array = materialize(selected).getAsJsonArray();
				Path added = selected.childIndex(array.size());
				JsonElement next = node.element().value();
				if (next.isJsonObject() && next.getAsJsonObject().has("slot")) {
					Set<Integer> occupied = new HashSet<>();
					for (JsonElement entry : array) if (entry.isJsonObject() && entry.getAsJsonObject().has("slot")) occupied.add(entry.getAsJsonObject().get("slot").getAsInt());
					int slot = 0; while (occupied.contains(slot)) slot++;
					next.getAsJsonObject().addProperty("slot", slot);
				}
				array.add(next); discovered.add(selected); collapsed.remove(selected);
				selected = added; discovered.add(added); collapsed.remove(added); changed(); ensureSelectedVisible();
				if (!isContainer(resolve(added))) beginEdit();
				return;
			}
			if (current != null && current.isJsonObject() && node != null && !node.fields().isEmpty()) {
				popupPath = selected; fieldOptions = node.fields(); fieldPicker = true;
				popupMode = PopupMode.ADD; registryPickerOpen = true; registryQuery = ""; registryQueryCursor = 0; registryTypeRow = 0;
				filterRegistryTypes(true); return;
			}
			if (current != null && isContainer(current)) materialize(selected);
		}
		beginCustomAdd();
	}

	private void beginCustomAdd() {
		JsonElement parent = resolve(selected);
		if (parent == null || !isContainer(parent)) {
			feedback.accept("请选择对象或数组节点，再添加子项");
			return;
		}
		popupMode = PopupMode.ADD;
		resetPopupRegistryState();
		popupPath = selected;
		popupOriginal = JsonNull.INSTANCE;
		ComponentIdContext context = idContext(selected);
		boolean enchantments = context.path().isEmpty() && Set.of("enchantments", "stored_enchantments").contains(context.componentPath());
		if (parent.isJsonObject()) {
			keyText = enchantments ? "" : suggestedKey(parent.getAsJsonObject());
			keyCursor = keyText.length();
		}
		if (enchantments && parent.isJsonObject()) {
			draftType = ValueType.INTEGER;
			valueText = "1";
		} else {
			draftType = ValueType.STRING;
			valueText = "";
		}
		valueCursor = valueText.length();
		inputFocus = parent.isJsonObject() ? InputFocus.KEY : InputFocus.VALUE;
		refreshIdSuggestions(true);
	}

	private void beginEdit() {
		JsonElement value = resolve(selected);
		if (value == null) return;
		if (guidedTree() && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
			replace(selected, new JsonPrimitive(!value.getAsBoolean())); changed(); return;
		}
		if (guidedTree() && isContainer(value)) { beginAdd(); return; }
		popupMode = PopupMode.EDIT;
		resetPopupRegistryState();
		popupPath = selected;
		popupOriginal = value.deepCopy();
		draftType = ValueType.of(value);
		valueText = scalarText(value);
		valueCursor = valueText.length();
		Step last = selected.last();
		keyText = last != null && last.objectKey() ? last.key() : "";
		keyCursor = keyText.length();
		ComponentIdContext context = idContext(selected);
		inputFocus = last != null && last.objectKey()
				&& context.path().size() == 1
				&& Set.of("enchantments", "stored_enchantments").contains(context.componentPath())
				? InputFocus.KEY : InputFocus.VALUE;
		refreshIdSuggestions(true);
	}

	private void beginRaw() {
		popupMode = PopupMode.RAW;
		resetPopupRegistryState();
		popupPath = Path.root();
		popupOriginal = root.deepCopy();
		valueText = root.toString();
		valueCursor = valueText.length();
		inputFocus = InputFocus.VALUE;
		clearIdSuggestions();
	}

	private void resetPopupRegistryState() {
		fieldPicker = false;
		manualIdHint = null;
		registryPickerOpen = false;
		registryQuery = "";
		registryQueryCursor = 0;
		registryTypeRow = 0;
	}

	private void confirmPopup() {
		try {
			if (popupMode == PopupMode.RAW) {
				JsonElement parsed = JsonParser.parseString(valueText);
				if (componentForest && !parsed.isJsonObject()) throw new IllegalArgumentException("组件集合必须是对象");
				root = parsed;
				selected = Path.root();
			} else if (popupMode == PopupMode.ADD) {
				JsonElement parent = resolve(popupPath);
				JsonElement value = draftValue();
				if (parent == null || !isContainer(parent)) throw new IllegalArgumentException("父节点已不存在");
				if (parent.isJsonObject()) {
					String key = requiredKey();
					if (parent.getAsJsonObject().has(key)) throw new IllegalArgumentException("键已存在：" + key);
					parent.getAsJsonObject().add(key, value);
					selected = popupPath.childKey(key);
				} else {
					int index = parent.getAsJsonArray().size();
					parent.getAsJsonArray().add(value);
					selected = popupPath.childIndex(index);
				}
				collapsed.remove(popupPath);
			} else if (popupMode == PopupMode.EDIT) {
				JsonElement value = draftValue();
				Step last = popupPath.last();
				ComponentTreeSchema.Node oldParent = componentForest && last != null && "type".equals(last.key()) ? schema(popupPath.parent()) : null;
				if (last != null && last.objectKey() && !last.key().equals(keyText.strip())) {
					renameAndReplace(popupPath, requiredKey(), value);
				} else {
					replace(popupPath, value);
				}
				if (oldParent != null && oldParent.fields().containsKey("type")
						&& oldParent.fields().get("type").registry().equals("minecraft:consume_effect_type") && !value.equals(popupOriginal)) {
					JsonObject parent = resolveActual(popupPath.parent()).getAsJsonObject();
					ComponentTreeSchema.Node variant = ComponentTreeSchema.variant(oldParent, parent);
					// Switching effect type is explicit; replace fields whose meaning/shape changed.
					JsonObject replacement = variant.value().getAsJsonObject();
					replacement.add("type", value);
					replace(popupPath.parent(), replacement);
				}
			}
			popupMode = PopupMode.NONE;
			rebuildRows();
			ensureSelectedVisible();
			changeListener.accept(root.deepCopy());
			feedback.accept(observedSchema != null ? "JSON 树已更新；字段建议不代表完整格式" : "树形组件值已更新；导出时会由服务器再次校验");
		} catch (RuntimeException error) {
			feedback.accept(LuaUiText.text("错误：") + (error.getMessage() == null ? "无法保存树节点" : error.getMessage()));
		}
	}

	private void deleteSelected() {
		if (selected.steps().isEmpty()) {
			feedback.accept("根节点不能删除；可以点击“修改”切换根节点类型");
			return;
		}
		Path parentPath = selected.parent();
		JsonElement parent = resolveActual(parentPath);
		Step last = selected.last();
		if (parent == null || last == null) return;
		if (last.objectKey() && parent.isJsonObject()) parent.getAsJsonObject().remove(last.key());
		else if (!last.objectKey() && parent.isJsonArray() && last.index() < parent.getAsJsonArray().size()) {
			parent.getAsJsonArray().remove(last.index());
		}
		selected = parentPath;
		rebuildRows();
		changeListener.accept(root.deepCopy());
		feedback.accept("已删除树节点");
	}

	private void renameAndReplace(Path path, String newKey, JsonElement value) {
		Path parentPath = path.parent();
		JsonElement parent = resolve(parentPath);
		Step last = path.last();
		if (parent == null || !parent.isJsonObject() || last == null) throw new IllegalArgumentException("无法修改键名");
		JsonObject object = parent.getAsJsonObject();
		if (!newKey.equals(last.key()) && object.has(newKey)) throw new IllegalArgumentException("键已存在：" + newKey);
		JsonObject rebuilt = new JsonObject();
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (entry.getKey().equals(last.key())) rebuilt.add(newKey, value);
			else rebuilt.add(entry.getKey(), entry.getValue());
		}
		replace(parentPath, rebuilt);
		selected = parentPath.childKey(newKey);
	}

	private void replace(Path path, JsonElement value) {
		if (path.steps().isEmpty()) {
			root = value;
			selected = Path.root();
			return;
		}
		JsonElement parent = guidedTree() ? materialize(path.parent()) : resolve(path.parent());
		Step last = path.last();
		if (parent == null || last == null) throw new IllegalArgumentException("节点路径已失效");
		if (last.objectKey() && parent.isJsonObject()) parent.getAsJsonObject().add(last.key(), value);
		else if (!last.objectKey() && parent.isJsonArray()) parent.getAsJsonArray().set(last.index(), value);
		else throw new IllegalArgumentException("节点父类型不匹配");
	}

	private JsonElement draftValue() {
		return switch (draftType) {
			case STRING -> new JsonPrimitive(valueText);
			case INTEGER -> new JsonPrimitive(new BigInteger(valueText.strip()));
			case NUMBER -> new JsonPrimitive(new BigDecimal(valueText.strip()));
			case BOOLEAN -> {
				String normalized = valueText.strip().toLowerCase(java.util.Locale.ROOT);
				if (!normalized.equals("true") && !normalized.equals("false")) {
					throw new IllegalArgumentException("布尔值只能填写 true 或 false");
				}
				yield new JsonPrimitive(Boolean.parseBoolean(normalized));
			}
			case OBJECT -> popupMode == PopupMode.EDIT && popupOriginal.isJsonObject()
					? popupOriginal.deepCopy() : new JsonObject();
			case ARRAY -> popupMode == PopupMode.EDIT && popupOriginal.isJsonArray()
					? popupOriginal.deepCopy() : new JsonArray();
			case NULL -> JsonNull.INSTANCE;
		};
	}

	private String requiredKey() {
		String key = keyText.strip();
		if (key.isEmpty()) throw new IllegalArgumentException("键名不能为空");
		return key;
	}

	private String suggestedKey(JsonObject parent) {
		if (componentId.equals("minecraft:enchantments")) {
			for (String candidate : List.of("minecraft:sharpness", "minecraft:unbreaking",
					"minecraft:mending", "minecraft:efficiency", "minecraft:protection")) {
				if (!parent.has(candidate)) return candidate;
			}
		}
		String base = "new_key";
		if (!parent.has(base)) return base;
		for (int suffix = 2; suffix < 10_000; suffix++) {
			String candidate = base + '_' + suffix;
			if (!parent.has(candidate)) return candidate;
		}
		return "key";
	}

	private boolean needsKeyField() {
		if (popupMode == PopupMode.RAW) return false;
		if (guidedField()) return false;
		if (popupMode == PopupMode.ADD) {
			JsonElement parent = resolve(popupPath);
			return parent != null && parent.isJsonObject();
		}
		Step last = popupPath.last();
		return last != null && last.objectKey();
	}

	private void rebuildRows() {
		List<Row> rebuilt = new ArrayList<>();
		appendRows(rebuilt, Path.root(), componentForest ? "组件树（" + visibleComponents.size() + "）· 灰色字段可添加"
				: observedSchema != null ? "数据包 JSON · 灰色字段来自已加载示例" : "组件值", root, 0);
		rows = List.copyOf(rebuilt);
		if (resolve(selected) == null && rows.stream().noneMatch(row -> row.path().equals(selected))) selected = Path.root();
	}

	private void appendRows(List<Row> target, Path path, String label, JsonElement value, int depth) {
		if (target.size() >= MAX_TREE_NODES) return;
		boolean container = isContainer(value);
		ComponentTreeSchema.Node definition = schema(path);
		if (guidedTree() && !path.steps().isEmpty()) {
			if (container && discovered.add(path)) collapsed.add(path);
			if (definition != null) label = definition.label() + (path.last().objectKey() ? " · " + path.last().key() : " [" + path.last().index() + "]");
			if (path.steps().size() == 1 && componentCatalog.containsKey(path.last().key())) label = componentCatalog.get(path.last().key()).label() + " · " + path.last().key();
		}
		target.add(new Row(path, label, value, depth, container, false));
		if (!container || collapsed.contains(path) || depth >= MAX_TREE_DEPTH) return;
		if (value.isJsonObject()) {
			Set<String> keys = new java.util.LinkedHashSet<>();
			if (definition != null) keys.addAll(definition.fields().keySet());
			keys.addAll(value.getAsJsonObject().keySet());
			for (String key : keys) {
				if (componentForest && path.steps().isEmpty() && componentCatalog.containsKey(key) && !visibleComponents.contains(key)) continue;
				Path child = path.childKey(key);
				JsonElement childValue = value.getAsJsonObject().get(key);
				if (childValue == null && definition != null && definition.fields().containsKey(key))
					childValue = definition.fields().get(key).value();
				if (childValue == null) childValue = JsonNull.INSTANCE;
				appendRows(target, child, key, childValue, depth + 1);
				if (target.size() >= MAX_TREE_NODES) return;
			}
			if (guidedTree() && definition != null && definition.custom()) target.add(new Row(path.childKey("\u0000add"),
					"＋ 添加自定义字段", JsonNull.INSTANCE, depth + 1, false, true));
		} else {
			JsonArray array = value.getAsJsonArray();
			for (int index = 0; index < array.size(); index++) {
				appendRows(target, path.childIndex(index), "[" + index + "]", array.get(index), depth + 1);
				if (target.size() >= MAX_TREE_NODES) return;
			}
			if (guidedTree()) target.add(new Row(path.childIndex(array.size()),
					"＋ 添加" + (definition != null && definition.element() != null ? definition.element().label() : "条目"), JsonNull.INSTANCE, depth + 1, false, true));
		}
	}

	private JsonElement resolve(Path path) {
		JsonElement actual = resolveActual(path);
		if (actual != null || !guidedTree()) return actual;
		ComponentTreeSchema.Node node = schema(path);
		return node == null ? null : node.value();
	}

	private JsonElement materialize(Path path) {
		JsonElement current = resolveActual(path);
		if (current != null) return current;
		ComponentTreeSchema.Node node = schema(path);
		JsonElement created = node == null ? new JsonObject() : node.value();
		if (path.steps().isEmpty()) { root = created; return root; }
		JsonElement parent = materialize(path.parent());
		Step step = path.last();
		if (step.objectKey()) parent.getAsJsonObject().add(step.key(), created);
		else if (step.index() == parent.getAsJsonArray().size()) parent.getAsJsonArray().add(created);
		else parent.getAsJsonArray().set(step.index(), created);
		return created;
	}

	private void changed() {
		rebuildRows(); changeListener.accept(root.deepCopy());
		feedback.accept(componentForest ? "已更新组件树；仅导出已添加/覆盖的组件" : "已更新 JSON 树；灰色建议字段不会自动导出");
	}

	private JsonElement resolveActual(Path path) {
		JsonElement current = root;
		try {
			for (Step step : path.steps()) {
				if (step.objectKey()) {
					if (!current.isJsonObject() || !current.getAsJsonObject().has(step.key())) return null;
					current = current.getAsJsonObject().get(step.key());
				} else {
					if (!current.isJsonArray() || step.index() < 0 || step.index() >= current.getAsJsonArray().size()) return null;
					current = current.getAsJsonArray().get(step.index());
				}
			}
			return current;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private void moveSelection(int direction) {
		rebuildRows();
		int index = 0;
		for (int candidate = 0; candidate < rows.size(); candidate++) {
			if (rows.get(candidate).path().equals(selected)) {
				index = candidate;
				break;
			}
		}
		index = clamp(index + direction, 0, Math.max(0, rows.size() - 1));
		if (!rows.isEmpty()) selected = rows.get(index).path();
		ensureSelectedVisible();
	}

	private void ensureSelectedVisible() {
		int selectedIndex = 0;
		for (int index = 0; index < rows.size(); index++) {
			if (rows.get(index).path().equals(selected)) {
				selectedIndex = index;
				break;
			}
		}
		int visible = visibleRows();
		if (selectedIndex < scroll) scroll = selectedIndex;
		if (selectedIndex >= scroll + visible) scroll = selectedIndex - visible + 1;
		scroll = clamp(scroll, 0, Math.max(0, rows.size() - visible));
	}

	private void toggleCollapsed(Path path) {
		if (!collapsed.remove(path)) collapsed.add(path);
		rebuildRows();
		ensureSelectedVisible();
	}

	private void insertText(String text) {
		if (text == null || text.isEmpty()) return;
		if (inputFocus == InputFocus.KEY) {
			keyText = keyText.substring(0, keyCursor) + text + keyText.substring(keyCursor);
			keyCursor += text.length();
		} else {
			valueText = valueText.substring(0, valueCursor) + text + valueText.substring(valueCursor);
			valueCursor += text.length();
		}
		refreshIdSuggestions(true);
	}

	private void refreshIdSuggestions(boolean resetSelection) {
		ComponentTreeSchema.Node definition = schema(popupTargetPath());
		if (inputFocus == InputFocus.VALUE && definition != null && definition.registry().isEmpty() && !definition.choices().isEmpty()) {
			suggestionRevision++; suggestionRegistry = "@choices"; suggestionLabel = "可选值"; suggestionLoading = false;
			suggestionCatalog = definition.choices().stream().map(option -> new LuaInsertCatalog.Entry(option, option, "")).toList();
			filterIdSuggestions(resetSelection); return;
		}
		IdHint hint = currentIdHint();
		if (hint == null || popupMode == PopupMode.NONE || popupMode == PopupMode.RAW) {
			clearIdSuggestions();
			return;
		}
		if (!hint.registry().equals(suggestionRegistry)) {
			suggestionRegistry = hint.registry();
			suggestionLabel = hint.label();
			suggestionCatalog = List.of();
			suggestions = List.of();
			List<LuaInsertCatalog.Entry> localEntries;
			try {
				localEntries = LuaResourceAssetCatalog.load(hint.registry(), minecraft);
			} catch (RuntimeException failure) {
				localEntries = List.of();
				feedback.accept("客户端资源 ID 目录读取失败：" + ScriptManager.message(failure));
			}
			if (localEntries != null) {
				suggestionCatalog = localEntries;
				suggestionLoading = false;
				filterIdSuggestions(true);
				return;
			}
			suggestionLoading = true;
			int revision = ++suggestionRevision;
			ScriptManager.registryCatalog(hint.registry()).whenComplete((entries, failure) ->
					minecraft.execute(() -> {
						if (revision != suggestionRevision || !hint.registry().equals(suggestionRegistry)) return;
						suggestionLoading = false;
						if (failure != null) {
							feedback.accept("ID 目录读取失败：" + ScriptManager.message(failure));
							return;
						}
						suggestionCatalog = entries;
						filterIdSuggestions(true);
					}));
			return;
		}
		suggestionLabel = hint.label();
		if (!suggestionLoading) filterIdSuggestions(resetSelection);
	}

	private void refreshCatalogRevision() {
		int revision = ScriptManager.catalogRevision();
		if (revision == observedCatalogRevision) return;
		observedCatalogRevision = revision;
		suggestionRevision++; registryTypesRevision++;
		suggestionRegistry = ""; suggestionCatalog = List.of(); suggestions = List.of(); suggestionLoading = false;
		registryTypeCatalog = List.of(); registryTypesLoading = false;
		if (registryPickerOpen && !fieldPicker) ensureRegistryTypesLoaded();
		if (popupMode != PopupMode.NONE && popupMode != PopupMode.RAW) refreshIdSuggestions(false);
	}

	private void filterIdSuggestions(boolean resetSelection) {
		String needle = currentInputText().strip().toLowerCase(java.util.Locale.ROOT);
		if (suggestionRegistry.equals("@choices") && suggestionCatalog.stream().anyMatch(entry -> entry.value().equals(currentInputText()))) needle = "";
		boolean tags = needle.startsWith("#");
		String query = tags ? needle.substring(1) : needle;
		suggestions = suggestionCatalog.stream().filter(entry -> entry.value().startsWith("#") == tags)
				.filter(entry -> entry.matches(query)).toList();
		if (resetSelection) suggestionRow = 0;
		suggestionRow = clamp(suggestionRow, 0, Math.max(0, suggestions.size() - 1));
	}

	private void clearIdSuggestions() {
		if (!suggestionRegistry.isEmpty() || suggestionLoading) suggestionRevision++;
		suggestionRegistry = "";
		suggestionLabel = "";
		suggestionCatalog = List.of();
		suggestions = List.of();
		suggestionRow = 0;
		suggestionLoading = false;
		suggestionArea = null;
	}

	private boolean shouldAcceptSuggestion() {
		if (suggestions.isEmpty() || suggestionLoading) return false;
		String current = currentInputText().strip();
		return !current.equals(suggestions.get(suggestionRow).value());
	}

	private void applySelectedSuggestion() {
		if (suggestions.isEmpty() || suggestionLoading) return;
		String id = suggestions.get(clamp(suggestionRow, 0, suggestions.size() - 1)).value();
		if (inputFocus == InputFocus.KEY) {
			keyText = id;
			keyCursor = id.length();
			if (draftType.hasTextValue()) inputFocus = InputFocus.VALUE;
		} else {
			valueText = id;
			valueCursor = id.length();
		}
		feedback.accept("已选择 " + id + " · 再按 Enter 确认节点");
		refreshIdSuggestions(true);
	}

	private void openRegistryPicker() {
		registryPickerOpen = true;
		registryQuery = "";
		registryQueryCursor = 0;
		registryTypeRow = 0;
		if (!registryTypeCatalog.isEmpty()) {
			filterRegistryTypes(true);
			return;
		}
		ensureRegistryTypesLoaded();
	}

	private void ensureRegistryTypesLoaded() {
		if (registryTypesLoading || !registryTypeCatalog.isEmpty()) return;
		registryTypesLoading = true;
		int revision = ++registryTypesRevision;
		ScriptManager.registryTypes().whenComplete((types, failure) -> minecraft.execute(() -> {
			if (revision != registryTypesRevision) return;
			registryTypesLoading = false;
			if (failure != null) {
				feedback.accept("注册表类型目录读取失败：" + ScriptManager.message(failure));
				return;
			}
			List<LuaRegistryCatalogData.RegistryType> combined = new ArrayList<>(types);
			combined.addAll(LuaResourceAssetCatalog.types(minecraft));
			registryTypeCatalog = List.copyOf(combined);
			filterRegistryTypes(true);
			if (popupMode != PopupMode.NONE && popupMode != PopupMode.RAW) refreshIdSuggestions(true);
		}));
	}

	private void filterRegistryTypes(boolean resetSelection) {
		String needle = registryQuery.strip().toLowerCase(java.util.Locale.ROOT);
		if (fieldPicker) {
			JsonElement actual = resolveActual(popupPath);
			List<LuaRegistryCatalogData.RegistryType> options = new ArrayList<>(fieldOptions.entrySet().stream().filter(entry -> actual == null || !actual.isJsonObject() || !actual.getAsJsonObject().has(entry.getKey()))
					.map(entry -> new LuaRegistryCatalogData.RegistryType(entry.getKey(), entry.getValue().label(), entry.getValue().kind(), "", 0))
					.filter(type -> type.matches(needle)).toList());
			ComponentTreeSchema.Node node = schema(popupPath);
			if (node != null && node.custom()) options.add(new LuaRegistryCatalogData.RegistryType("@custom", "＋自定义字段", "自由指定键名和类型", "", 0));
			registryTypes = List.copyOf(options);
		} else registryTypes = registryTypeCatalog.stream().filter(type -> type.matches(needle)).toList();
		if (resetSelection) registryTypeRow = 0;
		registryTypeRow = clamp(registryTypeRow, 0, Math.max(0, registryTypes.size() - 1));
	}

	private void applySelectedRegistryType() {
		if (registryTypesLoading && !fieldPicker || registryTypes.isEmpty()) return;
		LuaRegistryCatalogData.RegistryType selectedType = registryTypes.get(
				clamp(registryTypeRow, 0, registryTypes.size() - 1));
		if (fieldPicker) {
			Path parent = popupPath;
			if (selectedType.id().equals("@custom")) {
				selected = parent; materialize(parent); fieldPicker = false; registryPickerOpen = false; beginCustomAdd(); return;
			}
			selected = parent.childKey(selectedType.id());
			replace(selected, fieldOptions.get(selectedType.id()).value());
			fieldPicker = false; registryPickerOpen = false; popupMode = PopupMode.NONE;
			discovered.add(parent); collapsed.remove(parent); discovered.add(selected); collapsed.remove(selected); changed(); ensureSelectedVisible();
			if (!isContainer(resolve(selected))) beginEdit();
			return;
		}
		manualIdHint = new IdHint(selectedType.id(), selectedType.label());
		manualHintFocus = inputFocus;
		if (inputFocus == InputFocus.VALUE && draftType != ValueType.STRING) {
			draftType = ValueType.STRING;
			valueText = "";
			valueCursor = 0;
		}
		registryPickerOpen = false;
		refreshIdSuggestions(true);
		feedback.accept("已选择 " + selectedType.label() + " · " + selectedType.id()
				+ "，共 " + selectedType.count() + " 个 ID");
	}

	private int registryTypeWindowStart(int visibleRows) {
		if (registryTypes.size() <= visibleRows) return 0;
		return clamp(registryTypeRow - visibleRows / 2, 0, registryTypes.size() - visibleRows);
	}

	private int suggestionWindowStart(int visibleRows) {
		if (suggestions.size() <= visibleRows) return 0;
		return clamp(suggestionRow - visibleRows / 2, 0, suggestions.size() - visibleRows);
	}

	private String currentInputText() {
		return inputFocus == InputFocus.KEY ? keyText : valueText;
	}

	private IdHint currentIdHint() {
		if (manualIdHint != null && manualHintFocus == inputFocus) return manualIdHint;
		IdHint supplied = suppliedComponentHint();
		if (supplied != null) return supplied;
		ComponentIdContext context = idContext(popupTargetPath());
		if (inputFocus == InputFocus.KEY) {
			if (context.path().size() == 1 && (context.componentPath().equals("enchantments")
					|| context.componentPath().equals("stored_enchantments"))) {
				return new IdHint("minecraft:enchantment", "附魔 ID");
			}
			Path parent = popupMode == PopupMode.ADD ? popupPath : popupPath.parent();
			if (parent.last() != null && "components".equals(parent.last().key())) {
				return new IdHint("minecraft:data_component_type", "组件类型 ID");
			}
			return null;
		}
		if (!draftType.hasTextValue() || draftType != ValueType.STRING) return null;
		if (componentId.equals(LuaUiText.text("资源包 JSON"))) {
			String field = context.field();
			if (field.equals("parent") || field.equals("model")) return new IdHint(LuaResourceAssetCatalog.MODEL, LuaUiText.text("模型资源 ID"));
			if (pathContains(popupTargetPath(), "textures") || field.equals("texture")) return new IdHint(LuaResourceAssetCatalog.TEXTURE, LuaUiText.text("纹理资源 ID"));
			if (field.equals("font")) return new IdHint(LuaResourceAssetCatalog.FONT, LuaUiText.text("字体资源 ID"));
			Step last = popupTargetPath().last();
			if (pathContains(popupTargetPath(), "sounds") && (field.equals("name") || last != null && !last.objectKey()))
				return new IdHint(LuaResourceAssetCatalog.SOUND_FILE, LuaUiText.text("OGG 音频文件 ID"));
		}
		ComponentTreeSchema.Node definition = schema(popupTargetPath());
		if (definition != null && !definition.registry().isEmpty()) return new IdHint(definition.registry(), definition.label());
		String field = context.field();
		String component = context.componentPath();
		String structural = context.structuralRegistry();
		if (structural != null) return new IdHint(structural, registryLabel(structural));
		IdHint direct = directComponentHint(component, field);
		if (direct != null) return direct;
		if (field.equals("id") && context.path().size() == 1) {
			if (Set.of("container", "bundle_contents", "charged_projectiles", "use_remainder").contains(component)) {
				return new IdHint("minecraft:item", LuaUiText.text("物品 ID"));
			}
			if (component.equals("entity_data") || component.equals("bucket_entity_data")) {
				return new IdHint("minecraft:entity_type", LuaUiText.text("实体 ID"));
			}
			if (component.equals("block_entity_data")) {
				return new IdHint("minecraft:block_entity_type", "方块实体 ID");
			}
			if (component.equals("potion_contents") || pathContains(popupPath, "custom_effects")) {
				return new IdHint("minecraft:mob_effect", LuaUiText.text("状态效果 ID"));
			}
		}
		if ((component.equals("enchantments") || component.equals("stored_enchantments")) && field.isEmpty()) {
			return new IdHint("minecraft:enchantment", "附魔 ID");
		}
		if (component.equals("instrument") && field.isEmpty()) return new IdHint("minecraft:instrument", "乐器 ID");
		if (component.equals("recipes") && (field.isEmpty() || field.equals("recipe"))) {
			return new IdHint("minecraft:recipe", "配方 ID");
		}
		if (component.equals("potion_contents") && field.equals("potion")) return new IdHint("minecraft:potion", "药水 ID");
		if (component.equals("attribute_modifiers") && (field.equals("type") || field.equals("attribute"))) {
			return new IdHint("minecraft:attribute", "属性 ID");
		}
		if ((component.equals("can_place_on") || component.equals("can_break") || component.equals("tool"))
				&& (field.equals("block") || field.equals("blocks"))) {
			return new IdHint("minecraft:block", LuaUiText.text("方块 ID"));
		}
		if (component.equals("equippable") && field.equals("allowed_entities")) {
			return new IdHint("minecraft:entity_type", LuaUiText.text("实体 ID"));
		}
		if (component.equals("repairable") && (field.equals("item") || field.equals("items"))) {
			return new IdHint("minecraft:item", LuaUiText.text("物品 ID"));
		}
		if (component.equals("damage_resistant") && (field.equals("type") || field.equals("types"))) {
			return new IdHint("minecraft:damage_type", "伤害类型 ID");
		}
		if (component.equals("trim") && field.equals("material")) return new IdHint("minecraft:trim_material", "盔甲纹饰材料 ID");
		if (component.equals("trim") && field.equals("pattern")) return new IdHint("minecraft:trim_pattern", "盔甲纹饰图案 ID");
		if (component.equals("jukebox_playable") && (field.equals("song") || field.isEmpty())) {
			return new IdHint("minecraft:jukebox_song", "唱片歌曲 ID");
		}
		IdHint generic = genericIdHint(field);
		return generic != null ? generic : dynamicRegistryHint(component, field);
	}

	private IdHint suppliedComponentHint() {
		boolean keyTarget = inputFocus == InputFocus.KEY;
		if (!keyTarget && (!draftType.hasTextValue() || draftType != ValueType.STRING)) return null;
		ComponentIdContext context = idContext(popupTargetPath());
		List<LuaComponentEditorData.IdHint> hints = context.componentId().equals(componentId)
				? componentIdHints : componentHintCatalog.getOrDefault(context.componentId(), List.of());
		for (LuaComponentEditorData.IdHint hint : hints) {
			if (hint.keyTarget() != keyTarget || !context.matches(hint.path())) continue;
			return new IdHint(hint.registry(), hint.label());
		}
		return null;
	}

	private ComponentIdContext idContext(Path path) {
		if (componentForest) {
			JsonObject wrapped = new JsonObject(); wrapped.add("components", root);
			List<String> parts = new ArrayList<>(); parts.add("components");
			path.steps().forEach(step -> parts.add(step.objectKey() ? step.key() : Integer.toString(step.index())));
			return ComponentIdContext.resolve(componentId, wrapped, parts);
		}
		return ComponentIdContext.resolve(componentId, root, path.steps().stream()
				.map(step -> step.objectKey() ? step.key() : Integer.toString(step.index())).toList());
	}

	private String registryLabel(String id) {
		return registryTypeCatalog.stream().filter(type -> type.id().equals(id))
				.map(LuaRegistryCatalogData.RegistryType::label).findFirst().orElse(switch (id) {
					case "minecraft:item" -> LuaUiText.text("物品 ID");
					case "minecraft:block" -> LuaUiText.text("方块 ID");
					case "minecraft:entity_type" -> "实体类型 ID";
					case "minecraft:mob_effect" -> LuaUiText.text("状态效果 ID");
					default -> id;
				});
	}

	private Path popupTargetPath() {
		if (popupMode == PopupMode.ADD) {
			JsonElement parent = resolve(popupPath);
			if (parent != null && parent.isJsonObject()) return popupPath.childKey(keyText.strip());
			if (parent != null && parent.isJsonArray()) return popupPath.childIndex(parent.getAsJsonArray().size());
		}
		if (popupMode == PopupMode.EDIT && needsKeyField()) {
			return popupPath.parent().childKey(keyText.strip());
		}
		return popupPath;
	}

	private IdHint directComponentHint(String component, String field) {
		if (!field.isEmpty()) return null;
		return switch (component) {
			case "damage_type" -> new IdHint("minecraft:damage_type", "伤害类型 ID");
			case "item_model" -> new IdHint(LuaResourceAssetCatalog.ITEM_MODEL, LuaUiText.text("物品模型资源 ID"));
			case "tooltip_style" -> new IdHint(LuaResourceAssetCatalog.TOOLTIP_STYLE, LuaUiText.text("提示框样式 ID"));
			case "instrument" -> new IdHint("minecraft:instrument", "乐器 ID");
			case "provides_trim_material" -> new IdHint("minecraft:trim_material", "纹饰材料 ID");
			case "jukebox_playable" -> new IdHint("minecraft:jukebox_song", "唱片歌曲 ID");
			case "provides_banner_patterns" -> new IdHint("minecraft:banner_pattern", "旗帜图案 ID");
			case "recipes" -> new IdHint("minecraft:recipe", "配方 ID");
			case "break_sound", "note_block_sound" -> new IdHint("minecraft:sound_event", LuaUiText.text("声音 ID"));
			case "villager_variant" -> new IdHint("minecraft:villager_type", "村民变种 ID");
			case "wolf_variant" -> new IdHint("minecraft:wolf_variant", "狼变种 ID");
			case "wolf_sound_variant" -> new IdHint("minecraft:wolf_sound_variant", "狼声音变种 ID");
			case "pig_variant" -> new IdHint("minecraft:pig_variant", "猪变种 ID");
			case "pig_sound_variant" -> new IdHint("minecraft:pig_sound_variant", "猪声音变种 ID");
			case "cow_variant" -> new IdHint("minecraft:cow_variant", "牛变种 ID");
			case "cow_sound_variant" -> new IdHint("minecraft:cow_sound_variant", "牛声音变种 ID");
			case "chicken_variant" -> new IdHint("minecraft:chicken_variant", "鸡变种 ID");
			case "chicken_sound_variant" -> new IdHint("minecraft:chicken_sound_variant", "鸡声音变种 ID");
			case "frog_variant" -> new IdHint("minecraft:frog_variant", "青蛙变种 ID");
			case "painting_variant" -> new IdHint("minecraft:painting_variant", "画变种 ID");
			case "cat_variant" -> new IdHint("minecraft:cat_variant", "猫变种 ID");
			case "cat_sound_variant" -> new IdHint("minecraft:cat_sound_variant", "猫声音变种 ID");
			case "zombie_nautilus_variant" -> new IdHint("minecraft:zombie_nautilus_variant", "僵尸鹦鹉螺变种 ID");
			default -> null;
		};
	}

	private IdHint genericIdHint(String field) {
		return switch (field) {
			case "item", "items", "item_id", "itemid" -> new IdHint("minecraft:item", LuaUiText.text("物品 ID"));
			case "block", "blocks", "block_id", "blockid" -> new IdHint("minecraft:block", LuaUiText.text("方块 ID"));
			case "entity", "entity_type", "entitytype", "entity_id", "entityid", "allowed_entities" ->
					new IdHint("minecraft:entity_type", LuaUiText.text("实体 ID"));
			case "enchantment", "enchantment_id", "enchantmentid" -> new IdHint("minecraft:enchantment", "附魔 ID");
			case "effect", "effect_id", "effectid", "mob_effect", "mobeffect" -> new IdHint("minecraft:mob_effect", LuaUiText.text("状态效果 ID"));
			case "attribute", "attribute_id", "attributeid" -> new IdHint("minecraft:attribute", "属性 ID");
			case "potion" -> new IdHint("minecraft:potion", "药水 ID");
			case "sound", "sound_event", "soundevent", "equip_sound", "shearing_sound",
					"block_sound", "disable_sound", "hit_sound", "note_block_sound" ->
					new IdHint("minecraft:sound_event", LuaUiText.text("声音 ID"));
			case "biome" -> new IdHint("minecraft:biome", "生物群系 ID");
			case "dimension" -> new IdHint("minecraft:dimension", LuaUiText.text("维度 ID"));
			case "dimension_type", "dimensiontype" -> new IdHint("minecraft:dimension_type", "维度类型 ID");
			case "time", "timeline" -> new IdHint("minecraft:timeline", "时间轴 ID");
			case "world_clock", "worldclock" -> new IdHint("minecraft:world_clock", "世界时钟 ID");
			case "particle", "particle_type", "particletype" -> new IdHint("minecraft:particle_type", "粒子 ID");
			case "fluid" -> new IdHint("minecraft:fluid", "流体 ID");
			case "instrument" -> new IdHint("minecraft:instrument", "乐器 ID");
			case "song", "jukebox_song", "jukeboxsong" -> new IdHint("minecraft:jukebox_song", "唱片歌曲 ID");
			case "map_decoration_type", "mapdecorationtype" ->
					new IdHint("minecraft:map_decoration_type", "地图标记类型 ID");
			case "consume_effect_type", "consumeeffecttype" ->
					new IdHint("minecraft:consume_effect_type", "食用效果类型 ID");
			case "painting_variant", "paintingvariant" -> new IdHint("minecraft:painting_variant", "画变种 ID");
			case "banner_pattern", "bannerpattern" -> new IdHint("minecraft:banner_pattern", "旗帜图案 ID");
			case "trim_material", "trimmaterial", "material" -> new IdHint("minecraft:trim_material", "纹饰材料 ID");
			case "trim_pattern", "trimpattern", "pattern" -> new IdHint("minecraft:trim_pattern", "纹饰图案 ID");
			case "recipe" -> new IdHint("minecraft:recipe", "配方 ID");
			case "loot_table", "loottable" -> new IdHint("minecraft:loot_table", LuaUiText.text("战利品表 ID"));
			case "advancement" -> new IdHint("minecraft:advancement", "进度 ID");
			case "game_event", "gameevent" -> new IdHint("minecraft:game_event", "游戏事件 ID");
			case "damage_type", "damagetype" -> new IdHint("minecraft:damage_type", "伤害类型 ID");
			case "component", "component_type", "componenttype" -> new IdHint("minecraft:data_component_type", "组件类型 ID");
			case "item_model", "itemmodel" -> new IdHint(LuaResourceAssetCatalog.ITEM_MODEL, LuaUiText.text("物品模型资源 ID"));
			case "tooltip_style", "tooltipstyle" -> new IdHint(LuaResourceAssetCatalog.TOOLTIP_STYLE, LuaUiText.text("提示框样式 ID"));
			case "asset_id", "assetid", "equipment_asset", "equipmentasset" ->
					new IdHint(LuaResourceAssetCatalog.EQUIPMENT, LuaUiText.text("装备模型资源 ID"));
			case "camera_overlay", "cameraoverlay", "texture", "texture_id", "textureid" ->
					new IdHint(LuaResourceAssetCatalog.TEXTURE, LuaUiText.text("纹理资源 ID"));
			case "font", "font_id", "fontid" -> new IdHint(LuaResourceAssetCatalog.FONT, LuaUiText.text("字体资源 ID"));
			case "hidden_component", "hidden_components" ->
					new IdHint("minecraft:data_component_type", "组件类型 ID");
			case "villager_variant", "villagervariant" -> new IdHint("minecraft:villager_type", "村民变种 ID");
			case "wolf_variant", "wolfvariant" -> new IdHint("minecraft:wolf_variant", "狼变种 ID");
			case "wolf_sound_variant", "wolfsoundvariant" -> new IdHint("minecraft:wolf_sound_variant", "狼声音变种 ID");
			case "pig_variant", "pigvariant" -> new IdHint("minecraft:pig_variant", "猪变种 ID");
			case "pig_sound_variant", "pigsoundvariant" -> new IdHint("minecraft:pig_sound_variant", "猪声音变种 ID");
			case "cow_variant", "cowvariant" -> new IdHint("minecraft:cow_variant", "牛变种 ID");
			case "cow_sound_variant", "cowsoundvariant" -> new IdHint("minecraft:cow_sound_variant", "牛声音变种 ID");
			case "chicken_variant", "chickenvariant" -> new IdHint("minecraft:chicken_variant", "鸡变种 ID");
			case "chicken_sound_variant", "chickensoundvariant" -> new IdHint("minecraft:chicken_sound_variant", "鸡声音变种 ID");
			case "zombie_nautilus_variant", "zombienautilusvariant" ->
					new IdHint("minecraft:zombie_nautilus_variant", "僵尸鹦鹉螺变种 ID");
			case "frog_variant", "frogvariant" -> new IdHint("minecraft:frog_variant", "青蛙变种 ID");
			case "cat_variant", "catvariant" -> new IdHint("minecraft:cat_variant", "猫变种 ID");
			case "cat_sound_variant", "catsoundvariant" -> new IdHint("minecraft:cat_sound_variant", "猫声音变种 ID");
			default -> null;
		};
	}

	/**
	 * Matches fields from third-party components to the live server registry list. For example,
	 * a field named spell_id automatically uses an example_mod:spell registry even when the mod
	 * did not provide an explicit component hint.
	 */
	private IdHint dynamicRegistryHint(String component, String field) {
		if (registryTypeCatalog.isEmpty()) return null;
		List<String> semantics = new ArrayList<>();
		addSemantic(semantics, field);
		ComponentIdContext context = idContext(popupTargetPath());
		for (int index = context.path().size() - 1; index >= 0; index--) {
			String step = context.path().get(index);
			if (!step.matches("[0-9]+")) addSemantic(semantics, step);
		}
		addSemantic(semantics, component);
		LuaRegistryCatalogData.RegistryType best = null;
		int bestScore = 0;
		boolean tied = false;
		for (LuaRegistryCatalogData.RegistryType type : registryTypeCatalog) {
			String registryPath = normalizeSemantic(type.id().substring(type.id().indexOf(':') + 1));
			for (int semanticIndex = 0; semanticIndex < semantics.size(); semanticIndex++) {
				String semantic = semantics.get(semanticIndex);
				int score = semanticScore(semantic, registryPath) - semanticIndex;
				// Prefer the component owner's registry when several mods use the same type name.
				if (score >= 60 && type.id().split(":", 2)[0].equals(context.componentId().split(":", 2)[0])) score += 2;
				if (score > bestScore) {
					best = type;
					bestScore = score;
					tied = false;
				} else if (score == bestScore && score > 0 && best != null && !best.id().equals(type.id())) {
					tied = true;
				}
			}
		}
		return best == null || bestScore < 60 || tied ? null : new IdHint(best.id(), best.label());
	}

	private static void addSemantic(List<String> result, String raw) {
		String normalized = normalizeSemantic(raw);
		if (normalized.isEmpty() || Set.of("id", "type", "value", "key", "name", "entry", "entries",
				"variant", "variants").contains(normalized) || result.contains(normalized)) return;
		result.add(normalized);
	}

	private static String normalizeSemantic(String raw) {
		if (raw == null) return "";
		String value = raw.strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace('.', '_');
		int colon = value.indexOf(':');
		if (colon >= 0) value = value.substring(colon + 1);
		if (value.endsWith("_id")) value = value.substring(0, value.length() - 3);
		if (value.endsWith("_ids")) value = value.substring(0, value.length() - 4);
		if (value.endsWith("s") && value.length() > 4) value = value.substring(0, value.length() - 1);
		return value;
	}

	private static int semanticScore(String semantic, String registry) {
		if (semantic.equals(registry)) return 100;
		String semanticCompact = semantic.replace("_", "");
		String registryCompact = registry.replace("_", "");
		if (semanticCompact.equals(registryCompact)) return 95;
		if (semantic.equals(registry + "_type") || registry.equals(semantic + "_type")) return 88;
		if (semantic.length() >= 5 && registry.length() >= 5
				&& (semantic.endsWith("_" + registry) || registry.endsWith("_" + semantic))) return 65;
		return 0;
	}

	private static boolean pathContains(Path path, String key) {
		for (Step step : path.steps()) {
			if (step.objectKey() && step.key().equalsIgnoreCase(key)) return true;
		}
		return false;
	}

	private void removeCharacter(boolean backwards) {
		String text = inputFocus == InputFocus.KEY ? keyText : valueText;
		int cursor = inputFocus == InputFocus.KEY ? keyCursor : valueCursor;
		if (backwards && cursor > 0) {
			int start = text.offsetByCodePoints(cursor, -1);
			text = text.substring(0, start) + text.substring(cursor);
			cursor = start;
		} else if (!backwards && cursor < text.length()) {
			int end = text.offsetByCodePoints(cursor, 1);
			text = text.substring(0, cursor) + text.substring(end);
		}
		if (inputFocus == InputFocus.KEY) {
			keyText = text;
			keyCursor = cursor;
		} else {
			valueText = text;
			valueCursor = cursor;
		}
	}

	private void moveCursor(int key) {
		String text = inputFocus == InputFocus.KEY ? keyText : valueText;
		int cursor = inputFocus == InputFocus.KEY ? keyCursor : valueCursor;
		if (key == InputConstants.KEY_HOME) cursor = 0;
		else if (key == InputConstants.KEY_END) cursor = text.length();
		else if (key == InputConstants.KEY_LEFT && cursor > 0) cursor = text.offsetByCodePoints(cursor, -1);
		else if (key == InputConstants.KEY_RIGHT && cursor < text.length()) cursor = text.offsetByCodePoints(cursor, 1);
		if (inputFocus == InputFocus.KEY) keyCursor = cursor; else valueCursor = cursor;
	}

	private void drawField(GuiGraphicsExtractor graphics, Rect bounds, String value, String hint, boolean active) {
		int cursor = active ? inputFocus == InputFocus.KEY ? keyCursor : valueCursor : value.length();
		drawFieldAtCursor(graphics, bounds, value, hint, cursor, active);
	}

	private void drawFieldAtCursor(GuiGraphicsExtractor graphics, Rect bounds, String value,
			String hint, int requestedCursor, boolean active) {
		graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xFF080B10);
		graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), active ? 0xFFD0A7FF : 0xFF554666);
		int cursor = clamp(requestedCursor, 0, value.length());
		String shown = value.isEmpty() ? hint : value.substring(0, cursor) + (active ? "_" : "") + value.substring(cursor);
		graphics.text(font, font.plainSubstrByWidth(shown, bounds.width() - 10), bounds.x() + 5, bounds.y() + 6,
				value.isEmpty() ? 0xFF6F6878 : 0xFFF1E9FA);
	}

	private void drawButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			Rect bounds, String label, int color, int hover) {
		graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(),
				bounds.contains(mouseX, mouseY) ? hover : color);
		graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0xFF80669A);
		graphics.centeredText(font, font.plainSubstrByWidth(label, bounds.width() - 4),
				bounds.x() + bounds.width() / 2, bounds.y() + 5, 0xFFFFFFFF);
	}

	private int offsetAt(String text, double mouseX, int left, int maximumWidth) {
		String visible = font.plainSubstrByWidth(text, maximumWidth);
		int relative = Math.max(0, (int) Math.round(mouseX - left));
		int best = 0;
		for (int index = 1; index <= visible.length(); index++) {
			if (font.width(visible.substring(0, index)) > relative) break;
			best = index;
		}
		return best;
	}

	private String selectedLabel() {
		if (selected.steps().isEmpty()) return translation != null ? translation.rootLabel()
				: componentId.equals("minecraft:enchantments") ? "附魔列表" : "组件值树";
		Step last = selected.last();
		if (!last.objectKey()) return "数组项 " + last.index();
		if (translation == null) return last.key();
		String label = translation.label(pathSegments(selected), last.key(), resolve(selected));
		return label == null || label.isBlank() ? last.key() : label + " · " + last.key();
	}

	private String translatedRowLabel(Row row) {
		if (translation == null) return row.label();
		if (row.path().steps().isEmpty()) return translation.rootLabel();
		if (row.addAction() || !row.path().last().objectKey()) return row.label();
		String key = row.path().last().key();
		String label = translation.label(pathSegments(row.path()), key, row.value());
		return label == null || label.isBlank() ? key : label + " · " + key;
	}

	private String translatedPreview(Row row) {
		if (translation != null) {
			String label = translation.valueLabel(pathSegments(row.path()), row.value());
			if (label != null && !label.isBlank()) return label + " · " + preview(row.value());
		}
		return preview(row.value());
	}

	private static String rawKey(Path path) {
		Step last = path.last();
		return last == null ? "" : last.objectKey() ? last.key() : Integer.toString(last.index());
	}

	private static List<String> pathSegments(Path path) {
		return path.steps().stream().map(step -> step.objectKey() ? step.key() : Integer.toString(step.index())).toList();
	}

	private int visibleRows() {
		return Math.max(1, (bottom - top - TOOLBAR_HEIGHT - 2) / ROW_HEIGHT);
	}

	private static boolean isContainer(JsonElement value) {
		return value != null && (value.isJsonObject() || value.isJsonArray());
	}

	private static String preview(JsonElement value) {
		if (value == null || value.isJsonNull()) return "null";
		if (value.isJsonObject()) return "{" + value.getAsJsonObject().size() + " 项}";
		if (value.isJsonArray()) return "[" + value.getAsJsonArray().size() + " 项]";
		String text = value.getAsJsonPrimitive().isString() ? '"' + value.getAsString() + '"' : value.toString();
		return text.length() > 80 ? text.substring(0, 77) + "…" : text;
	}

	private static String scalarText(JsonElement value) {
		if (value == null || value.isJsonNull() || value.isJsonObject() || value.isJsonArray()) return "";
		return value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString();
	}

	private static String typeLabel(JsonElement value) {
		return ValueType.of(value).shortLabel;
	}

	private static int typeColor(JsonElement value) {
		return switch (ValueType.of(value)) {
			case OBJECT, ARRAY -> 0xFFB78AE8;
			case STRING -> 0xFF8FD69B;
			case INTEGER, NUMBER -> 0xFF79C9FF;
			case BOOLEAN -> 0xFFFFC66D;
			case NULL -> 0xFF8D8794;
		};
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	private enum PopupMode {
		NONE,
		ADD,
		EDIT,
		RAW
	}

	private enum InputFocus {
		KEY,
		VALUE
	}

	private enum ValueType {
		STRING(LuaUiText.text("字符串"), "str", "输入文字", ""),
		INTEGER("整数", "int", "例如 5", "1"),
		NUMBER("小数", "num", "例如 1.5", "1.0"),
		BOOLEAN(LuaUiText.text("布尔值"), "bool", "true 或 false", "true"),
		OBJECT("对象", "obj", "", ""),
		ARRAY("数组", "arr", "", ""),
		NULL("空值", "null", "", "");

		private final String label;
		private final String shortLabel;
		private final String hint;
		private final String defaultValue;

		ValueType(String label, String shortLabel, String hint, String defaultValue) {
			this.label = label;
			this.shortLabel = shortLabel;
			this.hint = hint;
			this.defaultValue = defaultValue;
		}

		boolean hasTextValue() {
			return this == STRING || this == INTEGER || this == NUMBER || this == BOOLEAN;
		}

		ValueType next() {
			return values()[(ordinal() + 1) % values().length];
		}

		static ValueType of(JsonElement value) {
			if (value == null || value.isJsonNull()) return NULL;
			if (value.isJsonObject()) return OBJECT;
			if (value.isJsonArray()) return ARRAY;
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isBoolean()) return BOOLEAN;
			if (primitive.isNumber()) return primitive.toString().matches("[-+]?\\d+") ? INTEGER : NUMBER;
			return STRING;
		}
	}

	private record Step(String key, int index) {
		static Step key(String key) {
			return new Step(key, -1);
		}

		static Step index(int index) {
			return new Step(null, index);
		}

		boolean objectKey() {
			return key != null;
		}
	}

	private record Path(List<Step> steps) {
		Path {
			steps = List.copyOf(steps);
		}

		static Path root() {
			return new Path(List.of());
		}

		Path childKey(String key) {
			List<Step> child = new ArrayList<>(steps);
			child.add(Step.key(key));
			return new Path(child);
		}

		Path childIndex(int index) {
			List<Step> child = new ArrayList<>(steps);
			child.add(Step.index(index));
			return new Path(child);
		}

		Path parent() {
			return steps.isEmpty() ? this : new Path(steps.subList(0, steps.size() - 1));
		}

		Step last() {
			return steps.isEmpty() ? null : steps.getLast();
		}
	}

	private record Row(Path path, String label, JsonElement value, int depth, boolean container, boolean addAction) {
	}

	private record Rect(int x, int y, int width, int height) {
		int right() {
			return x + width;
		}

		int bottom() {
			return y + height;
		}

		boolean contains(double mouseX, double mouseY) {
			return inside(mouseX, mouseY, x, y, right(), bottom());
		}
	}

	private record IdHint(String registry, String label) {
	}

	/** Opt-in in-game regression check; edits only a detached test tree. */
	static JsonTreeEditor verifyIdEditor(Minecraft client, LuaComponentEditorData data) {
		JsonTreeEditor editor = new JsonTreeEditor(client, client.font, ignored -> {}, ignored -> {});
		editor.setValue("minecraft:container", JsonParser.parseString("""
				[{"slot":0,"item":{"id":"minecraft:stone","components":{
				 "minecraft:enchantments":{"minecraft:sharpness":1},
				 "minecraft:potion_contents":{"custom_effects":[{"id":"minecraft:speed"}]},
				 "minecraft:use_remainder":"minecraft:glass_bottle"}}}]
				"""), data.componentIdHints().get("minecraft:container"), data.componentIdHints());
		Path stack = Path.root().childIndex(0).childKey("item");
		editor.verifyHint(stack.childKey("id"), "minecraft:item");
		editor.verifyHint(stack.childKey("components").childKey("minecraft:use_remainder"), "minecraft:item");
		editor.verifyHint(stack.childKey("components").childKey("minecraft:potion_contents")
				.childKey("custom_effects").childIndex(0).childKey("id"), "minecraft:mob_effect");
		Path enchants = stack.childKey("components").childKey("minecraft:enchantments");
		editor.verifyHint(enchants.childKey("minecraft:sharpness"), "minecraft:enchantment");
		editor.selected = enchants;
		editor.beginAdd();
		if (editor.draftType != ValueType.INTEGER || !"minecraft:enchantment".equals(editor.currentIdHint().registry())) {
			throw new IllegalStateException("嵌套附魔新增节点没有使用附魔搜索和整数等级");
		}
		editor.verifyHint(stack.childKey("id"), "minecraft:item");
		editor.valueText = "apple";
		editor.valueCursor = 5;
		editor.refreshIdSuggestions(true);
		return editor;
	}

	private void verifyHint(Path path, String registry) {
		selected = path;
		beginEdit();
		IdHint hint = currentIdHint();
		if (hint == null || !registry.equals(hint.registry())) throw new IllegalStateException("ID 类型识别错误：" + path + " -> " + hint);
	}

	void verifyRenderedIdPicker() {
		if (valueField == null || suggestionArea == null || suggestionArea.height() < ROW_HEIGHT
				|| valueField.bottom() > suggestionArea.y() || suggestionArea.bottom() > confirmButton.y()
				|| valueField.right() > popupBounds.right() || popupBounds.width() <= width) {
			throw new IllegalStateException("窄编辑区域的 ID 选择弹窗存在覆盖或候选区域缺失");
		}
		if (suggestions.stream().noneMatch(entry -> entry.value().equals("minecraft:apple"))) {
			throw new IllegalStateException("物品 ID 搜索没有返回 apple");
		}
		clickPopup(suggestionArea.x() + 10, suggestionArea.y() + 8);
		if (!valueText.equals("minecraft:apple")) throw new IllegalStateException("点击 ID 候选没有填入");
		keyPressedPopup(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		if (popupMode != PopupMode.NONE || !resolve(selected).getAsString().equals("minecraft:apple")) {
			throw new IllegalStateException("确认没有保存所选物品 ID");
		}
	}

	static JsonTreeEditor verifyComponentForest(Minecraft client, LuaComponentEditorData data) {
		JsonTreeEditor editor = new JsonTreeEditor(client, client.font, ignored -> {}, ignored -> {});
		editor.setComponentCatalog(data, new JsonObject());
		if (editor.rows.size() < data.components().size()) throw new IllegalStateException("组件树漏掉服务端组件");
		Path potion = Path.root().childKey("minecraft:potion_contents");
		editor.selected = potion; editor.toggleCollapsed(potion);
		if (!editor.root.getAsJsonObject().isEmpty()) throw new IllegalStateException("展开预览不应添加组件");
		Path effects = potion.childKey("custom_effects");
		editor.selected = effects; editor.beginAdd();
		Path first = effects.childIndex(0);
		if (!editor.resolveActual(first).getAsJsonObject().has("id")) throw new IllegalStateException("新增效果没有创建字段");
		editor.verifyHint(first.childKey("id"), "minecraft:mob_effect");
		editor.popupMode = PopupMode.NONE;
		editor.selected = first.childKey("amplifier"); editor.beginEdit();
		if (editor.draftType != ValueType.INTEGER || editor.needsKeyField()) throw new IllegalStateException("整数属性仍要求编辑 JSON 键");
		editor.valueText = "2"; editor.confirmPopup();
		if (editor.resolveActual(first.childKey("amplifier")).getAsInt() != 2) throw new IllegalStateException("未保存可选整数属性");
		editor.selected = first.childKey("show_particles"); editor.beginEdit();
		if (editor.resolveActual(editor.selected).getAsBoolean()) throw new IllegalStateException("布尔开关未切换");
		Path container = Path.root().childKey("minecraft:container");
		editor.selected = container; editor.beginAdd(); editor.selected = container; editor.beginAdd();
		if (editor.resolveActual(container.childIndex(1).childKey("slot")).getAsInt() != 1) throw new IllegalStateException("槽位没有自动递增");
		Path components = container.childIndex(0).childKey("item").childKey("components");
		editor.selected = components; editor.beginAdd();
		editor.registryQuery = "minecraft:enchantments"; editor.filterRegistryTypes(true); editor.applySelectedRegistryType();
		Path enchants = components.childKey("minecraft:enchantments");
		editor.selected = enchants; editor.beginAdd();
		if (editor.draftType != ValueType.INTEGER || !editor.currentIdHint().registry().equals("minecraft:enchantment")) throw new IllegalStateException("嵌套附魔没有类型提示");
		editor.keyText = "minecraft:sharpness"; editor.valueText = "3"; editor.confirmPopup();
		editor.selected = Path.root().childKey("minecraft:rarity"); editor.beginEdit();
		if (editor.suggestions.size() != 4) throw new IllegalStateException("稀有度没有列出四个可选值");
		editor.valueText = "epic"; editor.confirmPopup();
		editor.selected = Path.root().childKey("minecraft:custom_data"); editor.beginAdd();
		editor.keyText = "item_id"; editor.inputFocus = InputFocus.VALUE;
		if (!editor.currentIdHint().registry().equals("minecraft:item")) throw new IllegalStateException("自定义物品字段没有自动识别");
		editor.valueText = "minecraft:apple"; editor.confirmPopup();
		if (!editor.root.getAsJsonObject().has("minecraft:custom_data")) throw new IllegalStateException("自定义数据未保存");
		editor.selected = Path.root().childKey("minecraft:rarity"); editor.deleteSelected();
		if (editor.root.getAsJsonObject().has("minecraft:rarity")) throw new IllegalStateException("移除覆盖失败");
		Path onConsume = Path.root().childKey("minecraft:consumable").childKey("on_consume_effects");
		editor.selected = onConsume; editor.beginAdd();
		Path consumeType = onConsume.childIndex(0).childKey("type");
		editor.selected = consumeType; editor.beginEdit(); editor.valueText = "minecraft:remove_effects"; editor.confirmPopup();
		Path removed = onConsume.childIndex(0).childKey("effects");
		editor.selected = removed; editor.beginAdd();
		if (editor.draftType != ValueType.STRING || !editor.currentIdHint().registry().equals("minecraft:mob_effect")) throw new IllegalStateException("效果变体的子字段类型未更新");
		editor.valueText = "minecraft:speed"; editor.confirmPopup();
		editor.visibleComponents = Set.of("minecraft:potion_contents", "minecraft:container", "minecraft:custom_data");
		editor.selected = first; editor.popupMode = PopupMode.NONE; editor.rebuildRows(); editor.scroll = 0;
		return editor;
	}

	String probePatch() { return root.toString(); }

	void expandPotionPreview() {
		Path potion = Path.root().childKey("minecraft:potion_contents");
		for (Path path : List.of(potion, potion.childKey("custom_effects"), potion.childKey("custom_effects").childIndex(0))) {
			discovered.add(path); collapsed.remove(path);
		}
		selected = potion; rebuildRows(); scroll = 0;
	}

	void verifyRoutedForestClick(java.util.function.Function<MouseButtonEvent, Boolean> mouse,
			java.util.function.Function<KeyEvent, Boolean> keys) {
		Path amplifier = Path.root().childKey("minecraft:potion_contents").childKey("custom_effects").childIndex(0).childKey("amplifier");
		int index = -1;
		for (int i = 0; i < rows.size(); i++) if (rows.get(i).path().equals(amplifier)) index = i;
		if (index < scroll || index >= scroll + visibleRows()) throw new IllegalStateException("可视化属性行不在可见区域");
		var event = new MouseButtonEvent(x + width / 2.0, top + TOOLBAR_HEIGHT + (index - scroll) * ROW_HEIGHT + 7,
				new net.minecraft.client.input.MouseButtonInfo(0, 0));
		if (!mouse.apply(event) || !hasPopup() || !guidedField()) throw new IllegalStateException("实际窗口点击未进入类型化字段编辑");
		valueText = "4"; keys.apply(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		if (hasPopup() || resolveActual(amplifier).getAsInt() != 4) throw new IllegalStateException("实际窗口确认未保存属性");
	}
}
