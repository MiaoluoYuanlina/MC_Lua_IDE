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
	private static final int TOOLBAR_HEIGHT = 22;
	private static final int ROW_HEIGHT = 18;
	private static final int MAX_TREE_NODES = 4_096;
	private static final int MAX_TREE_DEPTH = 64;

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<JsonElement> changeListener;
	private final Consumer<String> feedback;
	private JsonElement root = new JsonObject();
	private String componentId = "";
	private List<LuaComponentEditorData.IdHint> componentIdHints = List.of();
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
	private boolean suggestionLoading;
	private IdHint manualIdHint;
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

	void setValue(String componentId, JsonElement value, List<LuaComponentEditorData.IdHint> idHints) {
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

	boolean hasPopup() {
		return popupMode != PopupMode.NONE;
	}

	boolean contains(double mouseX, double mouseY) {
		return inside(mouseX, mouseY, x, top, x + width, bottom);
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int x, int top, int width, int bottom) {
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
				&& componentIdHints.stream().anyMatch(hint -> !hint.keyTarget() && pathMatches(hint.path(), selected));
		drawButton(graphics, mouseX, mouseY, editButton,
				searchableId ? (compactButtons ? "搜" : "搜 ID") : (compactButtons ? "改" : "修改"),
				0xFF4B3865, 0xFF674B87);
		drawButton(graphics, mouseX, mouseY, deleteButton, compactButtons ? "删" : "删除",
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
			String shown = row.label() + "  " + preview(row.value());
			graphics.text(font, font.plainSubstrByWidth(shown, Math.max(8, x + width - textX - 50)),
					textX, rowY + 5, row.path().equals(selected) ? 0xFFFFFFFF : 0xFFD8CCE4);
			String type = typeLabel(row.value());
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
		int disclosureRight = x + 20 + Math.min(row.depth(), 18) * 12;
		if (row.container() && (event.x() <= disclosureRight || doubleClick)) {
			toggleCollapsed(row.path());
		} else if (doubleClick) {
			beginEdit();
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!contains(mouseX, mouseY) || popupMode != PopupMode.NONE) return false;
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
		refreshIdSuggestions(false);
		graphics.fill(x + 1, top + 1, x + width - 1, bottom - 1, 0xA8000000);
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int screenHeight = minecraft.getWindow().getGuiScaledHeight();
		int popupWidth = Math.max(1, Math.min(Math.min(390, width - 4), screenWidth - 12));
		boolean narrowPopup = popupWidth < 190;
		int desiredHeight = popupMode == PopupMode.RAW ? 104 : needsKeyField() ? 254 : 230;
		int popupHeight = Math.max(1, Math.min(desiredHeight, screenHeight - 12));
		int px = clamp(x + (width - popupWidth) / 2, 6, Math.max(6, screenWidth - popupWidth - 6));
		int py = clamp((top + bottom - popupHeight) / 2, 6, Math.max(6, screenHeight - popupHeight - 6));
		popupBounds = new Rect(px, py, popupWidth, popupHeight);
		graphics.fill(px, py, px + popupWidth, py + popupHeight, 0xFF171D29);
		graphics.outline(px, py, popupWidth, popupHeight, 0xFFC28DFF);
		String title = popupMode == PopupMode.ADD ? "新增树节点"
				: popupMode == PopupMode.EDIT ? "修改树节点" : "编辑完整 JSON 源码";
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
			graphics.text(font, "会替换当前组件的整棵树；确认后仍会经过服务端 Codec 校验",
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
			graphics.text(font, "类型", px + 8, rowY + 6, 0xFFBBA7CF);
			if (narrowPopup) rowY += 13;
			typeButton = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
					: new Rect(px + 62, rowY, Math.min(105, popupWidth - 70), 20);
			drawButton(graphics, mouseX, mouseY, typeButton, draftType.label, 0xFF3C3150, 0xFF58436F);
			if (!narrowPopup) graphics.text(font, "点击切换", typeButton.right() + 6, rowY + 6, 0xFF796D84);
			rowY += 24;
			graphics.text(font, "ID类型", px + 8, rowY + 6, 0xFFBBA7CF);
			if (narrowPopup) rowY += 13;
			IdHint activeHint = currentIdHint();
			registryButton = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
					: new Rect(px + 62, rowY, popupWidth - 70, 20);
			drawButton(graphics, mouseX, mouseY, registryButton,
					activeHint == null ? "自动识别 / 选择…" : activeHint.label() + "  ▾",
					0xFF263E51, 0xFF345B76);
			rowY += 24;
			if (draftType.hasTextValue()) {
				graphics.text(font, componentId.equals("minecraft:enchantments") ? "等级" : "值", px + 8, rowY + 6, 0xFFBBA7CF);
				if (narrowPopup) rowY += 13;
				valueField = narrowPopup ? new Rect(px + 8, rowY, popupWidth - 16, 20)
						: new Rect(px + 62, rowY, popupWidth - 70, 20);
				drawField(graphics, valueField, valueText, draftType.hint, inputFocus == InputFocus.VALUE);
				rowY += 24;
			}
			if (componentId.equals("minecraft:enchantments")) {
				graphics.text(font, "键可填写任意原版或 Mod 附魔 ID，整数值为附魔等级", px + 8, rowY + 3, 0xFF9A8AAA);
				rowY += 17;
			}
		}
		int buttonY = py + popupHeight - 26;
		suggestionArea = null;
		int suggestionTop = rowY + 2;
		int availableSuggestionHeight = buttonY - suggestionTop - 3;
		if ((!suggestions.isEmpty() || suggestionLoading) && availableSuggestionHeight >= ROW_HEIGHT) {
			int visibleSuggestions = Math.max(1, Math.min(5, availableSuggestionHeight / ROW_HEIGHT));
			suggestionArea = new Rect(px + 8, suggestionTop, popupWidth - 16,
					visibleSuggestions * ROW_HEIGHT);
			graphics.fill(suggestionArea.x(), suggestionArea.y(), suggestionArea.right(), suggestionArea.bottom(), 0xFF090D14);
			graphics.outline(suggestionArea.x(), suggestionArea.y(), suggestionArea.width(), suggestionArea.height(), 0xFF476A83);
			if (suggestionLoading) {
				graphics.text(font, "正在读取服务器注册表…", suggestionArea.x() + 5, suggestionArea.y() + 5, 0xFF91AFC3);
			} else {
				int first = suggestionWindowStart(visibleSuggestions);
				for (int visual = 0; visual < visibleSuggestions; visual++) {
					int index = first + visual;
					if (index >= suggestions.size()) break;
					int sy = suggestionArea.y() + visual * ROW_HEIGHT;
					if (index == suggestionRow) graphics.fill(suggestionArea.x() + 1, sy + 1,
							suggestionArea.right() - 1, sy + ROW_HEIGHT, 0xFF304C63);
					LuaInsertCatalog.Entry entry = suggestions.get(index);
					graphics.text(font, font.plainSubstrByWidth(entry.label(), suggestionArea.width() - 10),
							suggestionArea.x() + 5, sy + 5, index == suggestionRow ? 0xFFFFFFFF : 0xFFC8D7E2);
				}
			}
		}
		int actionWidth = Math.max(34, Math.min(56, (popupWidth - 22) / 2));
		confirmButton = new Rect(px + popupWidth - actionWidth * 2 - 14, buttonY, actionWidth, 20);
		cancelButton = new Rect(px + popupWidth - actionWidth - 8, buttonY, actionWidth, 20);
		drawButton(graphics, mouseX, mouseY, confirmButton, "确认", 0xFF4F6E58, 0xFF668E70);
		drawButton(graphics, mouseX, mouseY, cancelButton, "取消", 0xFF553C4C, 0xFF754F65);
		if (registryPickerOpen) renderRegistryPicker(graphics, mouseX, mouseY, px, py, popupWidth, popupHeight);
	}

	private void renderRegistryPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int px, int py, int popupWidth, int popupHeight) {
		graphics.fill(px + 1, py + 1, px + popupWidth - 1, py + popupHeight - 1, 0xFF131A25);
		graphics.outline(px, py, popupWidth, popupHeight, 0xFF65B8E8);
		graphics.text(font, "选择服务器注册表类型", px + 8, py + 8, 0xFFD7EFFF);
		registrySearchField = new Rect(px + 8, py + 24, popupWidth - 16, 20);
		drawFieldAtCursor(graphics, registrySearchField, registryQuery,
				"搜索类型 / 命名空间 / 中文首拼…", registryQueryCursor, true);
		int listTop = py + 49;
		int buttonY = py + popupHeight - 26;
		int visible = Math.max(1, (buttonY - listTop - 4) / ROW_HEIGHT);
		registryListArea = new Rect(px + 8, listTop, popupWidth - 16, visible * ROW_HEIGHT);
		graphics.fill(registryListArea.x(), registryListArea.y(), registryListArea.right(),
				registryListArea.bottom(), 0xFF080D14);
		graphics.outline(registryListArea.x(), registryListArea.y(), registryListArea.width(),
				registryListArea.height(), 0xFF365F78);
		if (registryTypesLoading) {
			graphics.text(font, "正在读取服务器全部注册表类型…", registryListArea.x() + 5,
					registryListArea.y() + 5, 0xFF91AFC3);
		} else if (registryTypes.isEmpty()) {
			graphics.text(font, "没有匹配的注册表类型", registryListArea.x() + 5,
					registryListArea.y() + 5, 0xFF9A8AAA);
		} else {
			int first = registryTypeWindowStart(visible);
			for (int visual = 0; visual < visible; visual++) {
				int index = first + visual;
				if (index >= registryTypes.size()) break;
				int rowY = registryListArea.y() + visual * ROW_HEIGHT;
				if (index == registryTypeRow) graphics.fill(registryListArea.x() + 1, rowY + 1,
						registryListArea.right() - 1, rowY + ROW_HEIGHT, 0xFF2E5067);
				String display = registryTypes.get(index).display();
				graphics.text(font, font.plainSubstrByWidth(display, registryListArea.width() - 10),
						registryListArea.x() + 5, rowY + 5,
						index == registryTypeRow ? 0xFFFFFFFF : 0xFFC8DCE8);
			}
		}
		registryAutoButton = new Rect(px + 8, buttonY, Math.min(82, popupWidth / 3), 20);
		registryCloseButton = new Rect(px + popupWidth - 58, buttonY, 50, 20);
		drawButton(graphics, mouseX, mouseY, registryAutoButton, "恢复自动", 0xFF3A4650, 0xFF526674);
		drawButton(graphics, mouseX, mouseY, registryCloseButton, "返回", 0xFF553C4C, 0xFF754F65);
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
		if (registryListArea != null && registryListArea.contains(mouseX, mouseY) && !registryTypesLoading) {
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
			manualIdHint = null;
			registryPickerOpen = false;
			refreshIdSuggestions(true);
			feedback.accept("已恢复按组件字段自动识别 ID 类型");
			return true;
		}
		if (registryCloseButton != null && registryCloseButton.contains(mouseX, mouseY)) {
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
			registryPickerOpen = false;
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			applySelectedRegistryType();
			return true;
		}
		if ((event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)
				&& !registryTypes.isEmpty() && !registryTypesLoading) {
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
		JsonElement parent = resolve(selected);
		if (parent == null || !isContainer(parent)) {
			feedback.accept("请选择对象或数组节点，再添加子项");
			return;
		}
		popupMode = PopupMode.ADD;
		resetPopupRegistryState();
		popupPath = selected;
		popupOriginal = JsonNull.INSTANCE;
		if (parent.isJsonObject()) {
			keyText = (componentPath().equals("enchantments") || componentPath().equals("stored_enchantments"))
					? "" : suggestedKey(parent.getAsJsonObject());
			keyCursor = keyText.length();
		}
		if (componentId.equals("minecraft:enchantments") && parent.isJsonObject()) {
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
		inputFocus = last != null && last.objectKey()
				&& (componentPath().equals("enchantments") || componentPath().equals("stored_enchantments"))
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
		manualIdHint = null;
		registryPickerOpen = false;
		registryQuery = "";
		registryQueryCursor = 0;
		registryTypeRow = 0;
	}

	private void confirmPopup() {
		try {
			if (popupMode == PopupMode.RAW) {
				root = JsonParser.parseString(valueText);
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
				if (last != null && last.objectKey() && !last.key().equals(keyText.strip())) {
					renameAndReplace(popupPath, requiredKey(), value);
				} else {
					replace(popupPath, value);
				}
			}
			popupMode = PopupMode.NONE;
			rebuildRows();
			ensureSelectedVisible();
			changeListener.accept(root.deepCopy());
			feedback.accept("树形组件值已更新；导出时会由服务器再次校验");
		} catch (RuntimeException error) {
			feedback.accept("错误：" + (error.getMessage() == null ? "无法保存树节点" : error.getMessage()));
		}
	}

	private void deleteSelected() {
		if (selected.steps().isEmpty()) {
			feedback.accept("根节点不能删除；可以点击“修改”切换根节点类型");
			return;
		}
		Path parentPath = selected.parent();
		JsonElement parent = resolve(parentPath);
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
		JsonElement parent = resolve(path.parent());
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
		if (popupMode == PopupMode.ADD) {
			JsonElement parent = resolve(popupPath);
			return parent != null && parent.isJsonObject();
		}
		Step last = popupPath.last();
		return last != null && last.objectKey();
	}

	private void rebuildRows() {
		List<Row> rebuilt = new ArrayList<>();
		appendRows(rebuilt, Path.root(), "组件值", root, 0);
		rows = List.copyOf(rebuilt);
		if (resolve(selected) == null) selected = Path.root();
	}

	private void appendRows(List<Row> target, Path path, String label, JsonElement value, int depth) {
		if (target.size() >= MAX_TREE_NODES) return;
		boolean container = isContainer(value);
		target.add(new Row(path, label, value, depth, container));
		if (!container || collapsed.contains(path) || depth >= MAX_TREE_DEPTH) return;
		if (value.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				appendRows(target, path.childKey(entry.getKey()), entry.getKey(), entry.getValue(), depth + 1);
				if (target.size() >= MAX_TREE_NODES) return;
			}
		} else {
			JsonArray array = value.getAsJsonArray();
			for (int index = 0; index < array.size(); index++) {
				appendRows(target, path.childIndex(index), "[" + index + "]", array.get(index), depth + 1);
				if (target.size() >= MAX_TREE_NODES) return;
			}
		}
	}

	private JsonElement resolve(Path path) {
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

	private void filterIdSuggestions(boolean resetSelection) {
		String needle = currentInputText().strip().toLowerCase(java.util.Locale.ROOT);
		suggestions = suggestionCatalog.stream().filter(entry -> entry.matches(needle)).toList();
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
		registryTypes = registryTypeCatalog.stream().filter(type -> type.matches(needle)).toList();
		if (resetSelection) registryTypeRow = 0;
		registryTypeRow = clamp(registryTypeRow, 0, Math.max(0, registryTypes.size() - 1));
	}

	private void applySelectedRegistryType() {
		if (registryTypesLoading || registryTypes.isEmpty()) return;
		LuaRegistryCatalogData.RegistryType selectedType = registryTypes.get(
				clamp(registryTypeRow, 0, registryTypes.size() - 1));
		manualIdHint = new IdHint(selectedType.id(), selectedType.label());
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
		if (manualIdHint != null) return manualIdHint;
		IdHint supplied = suppliedComponentHint();
		if (supplied != null) return supplied;
		if (inputFocus == InputFocus.KEY) {
			if (componentPath().equals("enchantments") || componentPath().equals("stored_enchantments")) {
				return new IdHint("minecraft:enchantment", "附魔 ID");
			}
			Path parent = popupMode == PopupMode.ADD ? popupPath : popupPath.parent();
			if (pathContains(parent, "components")) {
				return new IdHint("minecraft:data_component_type", "组件类型 ID");
			}
			return null;
		}
		if (!draftType.hasTextValue() || draftType != ValueType.STRING) return null;
		String field = normalizedFieldName();
		String component = componentPath();
		IdHint direct = directComponentHint(component, field);
		if (direct != null) return direct;
		if (field.equals("id")) {
			if (Set.of("container", "bundle_contents", "charged_projectiles", "use_remainder").contains(component)) {
				return new IdHint("minecraft:item", "物品 ID");
			}
			if (component.equals("entity_data") || component.equals("bucket_entity_data")) {
				return new IdHint("minecraft:entity_type", "实体 ID");
			}
			if (component.equals("block_entity_data")) {
				return new IdHint("minecraft:block_entity_type", "方块实体 ID");
			}
			if (component.equals("potion_contents") || pathContains(popupPath, "custom_effects")) {
				return new IdHint("minecraft:mob_effect", "状态效果 ID");
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
			return new IdHint("minecraft:block", "方块 ID");
		}
		if (component.equals("equippable") && field.equals("allowed_entities")) {
			return new IdHint("minecraft:entity_type", "实体 ID");
		}
		if (component.equals("repairable") && (field.equals("item") || field.equals("items"))) {
			return new IdHint("minecraft:item", "物品 ID");
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
		Path targetPath = popupTargetPath();
		for (LuaComponentEditorData.IdHint hint : componentIdHints) {
			if (hint.keyTarget() != keyTarget || !pathMatches(hint.path(), targetPath)) continue;
			return new IdHint(hint.registry(), hint.label());
		}
		return null;
	}

	private Path popupTargetPath() {
		if (popupMode == PopupMode.ADD) {
			JsonElement parent = resolve(popupPath);
			if (parent != null && parent.isJsonObject()) return popupPath.childKey(keyText.strip());
			if (parent != null && parent.isJsonArray()) return popupPath.childIndex(parent.getAsJsonArray().size());
		}
		if (popupMode == PopupMode.EDIT && inputFocus == InputFocus.KEY && needsKeyField()) {
			return popupPath.parent().childKey(keyText.strip());
		}
		return popupPath;
	}

	private static boolean pathMatches(String pattern, Path path) {
		if (pattern == null || pattern.isEmpty()) return path.steps().isEmpty();
		String[] expected = pattern.substring(1).split("/", -1);
		if (expected.length != path.steps().size()) return false;
		for (int index = 0; index < expected.length; index++) {
			if (expected[index].equals("*")) continue;
			Step actual = path.steps().get(index);
			String actualValue = actual.objectKey() ? actual.key() : Integer.toString(actual.index());
			if (!expected[index].equals(actualValue)) return false;
		}
		return true;
	}

	private IdHint directComponentHint(String component, String field) {
		if (!field.isEmpty()) return null;
		return switch (component) {
			case "damage_type" -> new IdHint("minecraft:damage_type", "伤害类型 ID");
			case "item_model" -> new IdHint(LuaResourceAssetCatalog.ITEM_MODEL, "物品模型资源 ID");
			case "tooltip_style" -> new IdHint(LuaResourceAssetCatalog.TOOLTIP_STYLE, "提示框样式 ID");
			case "instrument" -> new IdHint("minecraft:instrument", "乐器 ID");
			case "provides_trim_material" -> new IdHint("minecraft:trim_material", "纹饰材料 ID");
			case "jukebox_playable" -> new IdHint("minecraft:jukebox_song", "唱片歌曲 ID");
			case "provides_banner_patterns" -> new IdHint("minecraft:banner_pattern", "旗帜图案 ID");
			case "recipes" -> new IdHint("minecraft:recipe", "配方 ID");
			case "break_sound", "note_block_sound" -> new IdHint("minecraft:sound_event", "声音 ID");
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
			case "item", "items", "item_id", "itemid" -> new IdHint("minecraft:item", "物品 ID");
			case "block", "blocks", "block_id", "blockid" -> new IdHint("minecraft:block", "方块 ID");
			case "entity", "entity_type", "entitytype", "entity_id", "entityid", "allowed_entities" ->
					new IdHint("minecraft:entity_type", "实体 ID");
			case "enchantment", "enchantment_id", "enchantmentid" -> new IdHint("minecraft:enchantment", "附魔 ID");
			case "effect", "effect_id", "effectid", "mob_effect", "mobeffect" -> new IdHint("minecraft:mob_effect", "状态效果 ID");
			case "attribute", "attribute_id", "attributeid" -> new IdHint("minecraft:attribute", "属性 ID");
			case "potion" -> new IdHint("minecraft:potion", "药水 ID");
			case "sound", "sound_event", "soundevent", "equip_sound", "shearing_sound",
					"block_sound", "disable_sound", "hit_sound", "note_block_sound" ->
					new IdHint("minecraft:sound_event", "声音 ID");
			case "biome" -> new IdHint("minecraft:biome", "生物群系 ID");
			case "dimension" -> new IdHint("minecraft:dimension", "维度 ID");
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
			case "loot_table", "loottable" -> new IdHint("minecraft:loot_table", "战利品表 ID");
			case "advancement" -> new IdHint("minecraft:advancement", "进度 ID");
			case "game_event", "gameevent" -> new IdHint("minecraft:game_event", "游戏事件 ID");
			case "damage_type", "damagetype" -> new IdHint("minecraft:damage_type", "伤害类型 ID");
			case "component", "component_type", "componenttype" -> new IdHint("minecraft:data_component_type", "组件类型 ID");
			case "item_model", "itemmodel" -> new IdHint(LuaResourceAssetCatalog.ITEM_MODEL, "物品模型资源 ID");
			case "tooltip_style", "tooltipstyle" -> new IdHint(LuaResourceAssetCatalog.TOOLTIP_STYLE, "提示框样式 ID");
			case "asset_id", "assetid", "equipment_asset", "equipmentasset" ->
					new IdHint(LuaResourceAssetCatalog.EQUIPMENT, "装备模型资源 ID");
			case "camera_overlay", "cameraoverlay", "texture", "texture_id", "textureid" ->
					new IdHint(LuaResourceAssetCatalog.TEXTURE, "纹理资源 ID");
			case "font", "font_id", "fontid" -> new IdHint(LuaResourceAssetCatalog.FONT, "字体资源 ID");
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
		Path target = popupTargetPath();
		for (int index = target.steps().size() - 1; index >= 0; index--) {
			Step step = target.steps().get(index);
			if (step.objectKey()) addSemantic(semantics, step.key());
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

	private String normalizedFieldName() {
		String name = needsKeyField() ? keyText : nearestObjectKey(popupPath);
		return name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
	}

	private String componentPath() {
		int colon = componentId.indexOf(':');
		return (colon >= 0 ? componentId.substring(colon + 1) : componentId).toLowerCase(java.util.Locale.ROOT);
	}

	private static String nearestObjectKey(Path path) {
		for (int index = path.steps().size() - 1; index >= 0; index--) {
			Step step = path.steps().get(index);
			if (step.objectKey()) return step.key();
		}
		return "";
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
		if (selected.steps().isEmpty()) return componentId.equals("minecraft:enchantments") ? "附魔列表" : "组件值树";
		Step last = selected.last();
		return last.objectKey() ? last.key() : "数组项 " + last.index();
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
		STRING("字符串", "str", "输入文字", ""),
		INTEGER("整数", "int", "例如 5", "1"),
		NUMBER("小数", "num", "例如 1.5", "1.0"),
		BOOLEAN("布尔值", "bool", "true 或 false", "true"),
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

	private record Row(Path path, String label, JsonElement value, int depth, boolean container) {
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
}
