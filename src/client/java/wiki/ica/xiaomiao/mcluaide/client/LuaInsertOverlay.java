package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/** Draggable, resizable and searchable runtime ID picker. */
final class LuaInsertOverlay {
	private static final int TITLE_HEIGHT = 28;
	private static final int RESIZE_MARGIN = 6;
	private static final int MIN_WIDTH = 380;
	private static final int MIN_HEIGHT = 240;
	private static final int EDGE_LEFT = 1;
	private static final int EDGE_RIGHT = 2;
	private static final int EDGE_TOP = 4;
	private static final int EDGE_BOTTOM = 8;
	private static final int ROW_HEIGHT = 20;

	private final Minecraft minecraft;
	private final Font font;
	private final Consumer<LuaInsertCatalog.Entry> insertion;
	private final Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>> catalogs =
			new EnumMap<>(LuaInsertCatalog.Kind.class);
	private final Map<String, EntityRenderState> entityIcons = new HashMap<>();
	private final Set<String> unavailableEntityIcons = new HashSet<>();
	private List<LuaInsertCatalog.Entry> filtered = List.of();
	private List<String> namespaces = List.of();
	private List<NamespaceOption> namespaceOptions = List.of();
	private LuaInsertCatalog.Kind kind = LuaInsertCatalog.Kind.ITEM;
	private String query = "";
	private int namespaceIndex;
	private boolean namespacePickerOpen;
	private String namespaceQuery = "";
	private int namespaceSelectedRow;
	private int namespaceScrollRow;
	private int scrollRow;
	private int selectedRow;
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

	LuaInsertOverlay(Minecraft minecraft, Font font, Consumer<LuaInsertCatalog.Entry> insertion) {
		this.minecraft = minecraft;
		this.font = font;
		this.insertion = insertion;
	}

	void resize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
		ensureGeometry();
	}

	void open() {
		open(null, "");
	}

	void open(LuaInsertCatalog.Kind preferredKind, String initialQuery) {
		open = true;
		keyboardFocused = true;
		catalogs.clear();
		if (preferredKind != null) kind = preferredKind;
		query = initialQuery == null ? "" : initialQuery;
		namespaceIndex = 0;
		namespacePickerOpen = false;
		namespaceQuery = "";
		refreshCatalog();
	}

	void close() {
		open = false;
		keyboardFocused = false;
		namespacePickerOpen = false;
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

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		DockSide previous = dockSide;
		if (!dockSide.docked() && side.docked()) rememberFloatingGeometry();
		dockSide = side;
		if (previous.docked() && !side.docked()) restoreFloatingGeometry();
		resizeEdges = 0;
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (!dockSide.docked()) return;
		boundsAnimation.animateTo(windowX, windowY, windowWidth, windowHeight,
				x, y, width, height);
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
			WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(
					windowX, windowY, windowWidth, windowHeight);
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
		graphics.outline(x, windowY, windowWidth, windowHeight, 0xFF55C9A5);
		graphics.fill(x + 1, windowY + 1, right - 1, windowY + TITLE_HEIGHT, 0xFF18332F);
		graphics.text(font, "＋  插入运行时 ID", x + 10, windowY + 9, 0xFF75E6C1);

		int refreshLeft = right - 51;
		boolean refreshHovered = inside(mouseX, mouseY, refreshLeft, windowY + 4, refreshLeft + 20, windowY + 24);
		graphics.fill(refreshLeft, windowY + 4, refreshLeft + 20, windowY + 24,
				refreshHovered ? 0xFF2D806B : 0xFF29453F);
		graphics.centeredText(font, "↻", refreshLeft + 10, windowY + 10, 0xFFFFFFFF);
		boolean closeHovered = inside(mouseX, mouseY, right - 26, windowY + 4, right - 6, windowY + 24);
		graphics.fill(right - 26, windowY + 4, right - 6, windowY + 24,
				closeHovered ? 0xFFB83A46 : 0xFF29453F);
		graphics.centeredText(font, "×", right - 16, windowY + 10, 0xFFFFFFFF);

		int searchTop = windowY + 35;
		int namespaceWidth = Math.min(190, Math.max(125, windowWidth / 3));
		int namespaceLeft = right - namespaceWidth - 10;
		graphics.fill(x + 10, searchTop, namespaceLeft - 6, searchTop + 20, 0xFF090D12);
		graphics.outline(x + 10, searchTop, namespaceLeft - x - 16, 20, 0xFF4B665F);
		String searchText = query.isEmpty() ? "搜索 ID、中文名称或首拼…" : query + "_";
		graphics.text(font, font.plainSubstrByWidth(searchText, namespaceLeft - x - 30),
				x + 16, searchTop + 6, query.isEmpty() ? 0xFF71817D : 0xFFFFFFFF);
		boolean namespaceHovered = inside(mouseX, mouseY, namespaceLeft, searchTop, right - 10, searchTop + 20);
		graphics.fill(namespaceLeft, searchTop, right - 10, searchTop + 20,
				namespacePickerOpen ? 0xFF26745F : namespaceHovered ? 0xFF285A50 : 0xFF203C36);
		String namespace = namespaceIndex == 0 ? "全部命名空间" : namespaces.get(namespaceIndex - 1);
		String namespaceLabel = namespace + "  ▾";
		graphics.text(font, font.plainSubstrByWidth(namespaceLabel, namespaceWidth - 16),
				namespaceLeft + 6, searchTop + 6, 0xFFE0FFF5);

		int tabsTop = windowY + 63;
		LuaInsertCatalog.Kind[] kinds = LuaInsertCatalog.Kind.values();
		int tabWidth = Math.max(1, (windowWidth - 20) / kinds.length);
		for (int index = 0; index < kinds.length; index++) {
			int left = x + 10 + index * tabWidth;
			int tabRight = index == kinds.length - 1 ? right - 10 : left + tabWidth - 2;
			boolean active = kinds[index] == kind;
			boolean hovered = inside(mouseX, mouseY, left, tabsTop, tabRight, tabsTop + 20);
			graphics.fill(left, tabsTop, tabRight, tabsTop + 20,
					active ? 0xFF26745F : hovered ? 0xFF283C38 : 0xFF1B2926);
			graphics.centeredText(font, kinds[index].label(), (left + tabRight) / 2,
					tabsTop + 6, active ? 0xFFFFFFFF : 0xFFB8CCC6);
		}

		int infoY = windowY + 89;
		graphics.text(font, kind.label() + " · " + filtered.size() + " 项", x + 12, infoY, 0xFF8DA9A1);
		String namespaceHint = namespaces.size() + " 个命名空间 · Ctrl+N 快速选择";
		graphics.text(font, namespaceHint, right - 12 - font.width(namespaceHint), infoY, 0xFF657A74);

		int listTop = windowY + 104;
		int listBottom = bottom - 28;
		graphics.fill(x + 10, listTop, right - 10, listBottom, 0xFF080C10);
		graphics.outline(x + 10, listTop, windowWidth - 20, listBottom - listTop, 0xFF344D47);
		graphics.enableScissor(x + 11, listTop + 1, right - 11, listBottom - 1);
		int visibleRows = visibleRows();
		int hoveredRow = rowAt(mouseX, mouseY);
		for (int row = 0; row < visibleRows; row++) {
			int absolute = scrollRow + row;
			if (absolute >= filtered.size()) break;
			int y = listTop + 2 + row * ROW_HEIGHT;
			if (absolute == selectedRow || absolute == hoveredRow) {
				graphics.fill(x + 12, y - 1, right - 12, y + 18,
						absolute == hoveredRow ? 0xFF245B50 : 0xFF183C35);
			}
			LuaInsertCatalog.Entry entry = filtered.get(absolute);
			renderEntryIcon(graphics, entry, x + 14, y);
			graphics.text(font, font.plainSubstrByWidth(entry.label(), windowWidth - 54),
					x + 35, y + 4, 0xFFE8FFF7);
		}
		graphics.disableScissor();

		String footer = "←→ 分类 · ↑↓ 选择 · Enter 插入并关闭 · Ctrl+I / Esc 关闭";
		graphics.text(font, font.plainSubstrByWidth(footer, windowWidth - 30),
				x + 12, bottom - 18, 0xFF78928B);
		graphics.fill(right - 9, bottom - 3, right - 3, bottom - 2, 0xFF55C9A5);
		graphics.fill(right - 6, bottom - 6, right - 3, bottom - 5, 0xFF55C9A5);
		graphics.fill(right - 3, bottom - 9, right - 2, bottom - 3, 0xFF55C9A5);
		if (namespacePickerOpen) renderNamespacePicker(graphics, mouseX, mouseY,
				namespaceLeft, searchTop + 22, right - 10);
	}

	boolean keyPressed(KeyEvent event) {
		if (!open || !keyboardFocused) return false;
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (namespacePickerOpen) {
				closeNamespacePicker();
				return true;
			}
			if (dockSide.docked()) return true;
			close();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_N) {
			toggleNamespacePicker();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			String clipboard = minecraft.keyboardHandler.getClipboard();
			if (!clipboard.isBlank()) {
				if (namespacePickerOpen) {
					namespaceQuery += clipboard.strip().replaceAll("\\R", " ");
					resetNamespaceResults();
				} else {
					query += clipboard.strip().replaceAll("\\R", " ");
					invalidateFilter();
				}
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			String edited = namespacePickerOpen ? namespaceQuery : query;
			if (!edited.isEmpty()) {
				int start = edited.offsetByCodePoints(edited.length(), -1);
				if (namespacePickerOpen) {
					namespaceQuery = edited.substring(0, start);
					resetNamespaceResults();
				} else {
					query = edited.substring(0, start);
					invalidateFilter();
				}
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE) {
			if (namespacePickerOpen) {
				namespaceQuery = "";
				resetNamespaceResults();
			} else {
				query = "";
				invalidateFilter();
			}
			return true;
		}
		if (namespacePickerOpen) return namespaceKeyPressed(event);
		if (event.key() == InputConstants.KEY_UP) {
			selectedRow = Math.max(0, selectedRow - 1);
			ensureSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			selectedRow = Math.min(Math.max(0, filtered.size() - 1), selectedRow + 1);
			ensureSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_LEFT) {
			switchKind(-1);
			return true;
		}
		if (event.key() == InputConstants.KEY_RIGHT) {
			switchKind(1);
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEUP) {
			selectedRow = Math.max(0, selectedRow - visibleRows());
			ensureSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEDOWN) {
			selectedRow = Math.min(Math.max(0, filtered.size() - 1), selectedRow + visibleRows());
			ensureSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			if (insertSelected()) close();
			return true;
		}
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (!open || !keyboardFocused) return false;
		int codepoint = event.codepoint();
		String edited = namespacePickerOpen ? namespaceQuery : query;
		if (!Character.isISOControl(codepoint) && edited.codePointCount(0, edited.length()) < 120) {
			if (namespacePickerOpen) {
				namespaceQuery += new String(Character.toChars(codepoint));
				resetNamespaceResults();
			} else {
				query += new String(Character.toChars(codepoint));
				invalidateFilter();
			}
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!open || !pointInside(mouseX, mouseY)) return false;
		keyboardFocused = true;
		if (namespacePickerOpen && insideNamespacePicker(mouseX, mouseY)) {
			namespaceScrollRow = Math.max(0, Math.min(maxNamespaceScroll(),
					namespaceScrollRow - (int) Math.round(amount * 3)));
			return true;
		}
		scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.round(amount * 3)));
		return true;
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
		if (inside(event.x(), event.y(), right - 51, windowY + 4, right - 31, windowY + 24)) {
			catalogs.remove(kind);
			if (kind == LuaInsertCatalog.Kind.ENTITY) {
				entityIcons.clear();
				unavailableEntityIcons.clear();
			}
			refreshCatalog();
			return true;
		}
		int edges = resizeEdgesAt(event.x(), event.y());
		if (edges != 0) {
			keyboardFocused = true;
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
		int searchTop = windowY + 35;
		int namespaceWidth = Math.min(190, Math.max(125, windowWidth / 3));
		int namespaceLeft = right - namespaceWidth - 10;
		if (inside(event.x(), event.y(), namespaceLeft, searchTop, right - 10, searchTop + 20)) {
			toggleNamespacePicker();
			return true;
		}
		if (namespacePickerOpen) {
			if (handleNamespacePickerClick(event.x(), event.y())) return true;
			closeNamespacePicker();
		}
		int tabsTop = windowY + 63;
		LuaInsertCatalog.Kind[] kinds = LuaInsertCatalog.Kind.values();
		int tabWidth = Math.max(1, (windowWidth - 20) / kinds.length);
		if (event.y() >= tabsTop && event.y() <= tabsTop + 20 && event.x() >= x + 10 && event.x() <= right - 10) {
			int index = Math.min(kinds.length - 1, (int) (event.x() - x - 10) / tabWidth);
			if (kind != kinds[index]) {
				switchKind(index - kind.ordinal());
			}
			return true;
		}
		int row = rowAt(event.x(), event.y());
		if (row >= 0 && row < filtered.size()) {
			selectedRow = row;
			insertSelected();
			return true;
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event) {
		if (resizeEdges != 0) {
			resize(event.x(), event.y());
			return true;
		}
		if (dragging) {
			if (dockSide.docked()) undockForDrag(event.x(), event.y());
			windowX = Math.max(0, Math.min(screenWidth - windowWidth,
					(int) Math.round(event.x() - dragOffsetX)));
			windowY = Math.max(0, Math.min(screenHeight - windowHeight,
					(int) Math.round(event.y() - dragOffsetY)));
			boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
			return true;
		}
		return false;
	}

	boolean mouseReleased() {
		if (dragging || resizeEdges != 0) {
			dragging = false;
			resizeEdges = 0;
			return true;
		}
		return false;
	}

	private void refreshCatalog() {
		catalogs.computeIfAbsent(kind, key -> LuaInsertCatalog.load(key, minecraft));
		Map<String, Integer> counts = new LinkedHashMap<>();
		TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (LuaInsertCatalog.Entry entry : catalogs.get(kind)) {
			values.add(entry.namespace());
			counts.merge(entry.namespace(), 1, Integer::sum);
		}
		namespaces = List.copyOf(values);
		List<NamespaceOption> options = new ArrayList<>();
		options.add(new NamespaceOption(null, "全部命名空间", catalogs.get(kind).size()));
		for (String value : namespaces) {
			options.add(new NamespaceOption(value, namespaceDisplayName(value), counts.getOrDefault(value, 0)));
		}
		namespaceOptions = List.copyOf(options);
		namespaceIndex = Math.min(namespaceIndex, namespaces.size());
		invalidateFilter();
	}

	private void renderNamespacePicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int right) {
		int bottom = namespacePickerBottom();
		graphics.fill(left - 3, top + 3, right + 3, bottom + 3, 0x80000000);
		graphics.fill(left, top, right, bottom, 0xFF0C1418);
		graphics.outline(left, top, right - left, bottom - top, 0xFF55C9A5);
		graphics.fill(left + 1, top + 1, right - 1, top + 23, 0xFF111E22);
		String search = namespaceQuery.isEmpty() ? "搜索 Mod / 命名空间 / 首拼…" : namespaceQuery + "_";
		graphics.text(font, font.plainSubstrByWidth(search, right - left - 12), left + 6, top + 8,
				namespaceQuery.isEmpty() ? 0xFF71817D : 0xFFFFFFFF);

		List<NamespaceOption> matches = filteredNamespaceOptions();
		int rowsTop = top + 25;
		int visibleRows = visibleNamespaceRows();
		graphics.enableScissor(left + 1, rowsTop, right - 1, bottom - 1);
		for (int row = 0; row < visibleRows; row++) {
			int absolute = namespaceScrollRow + row;
			if (absolute >= matches.size()) break;
			int y = rowsTop + row * 18;
			NamespaceOption option = matches.get(absolute);
			boolean hovered = inside(mouseX, mouseY, left + 2, y, right - 2, y + 17);
			if (absolute == namespaceSelectedRow || hovered) {
				graphics.fill(left + 2, y, right - 2, y + 17,
						hovered ? 0xFF285A50 : 0xFF183C35);
			}
			boolean selected = option.matchesNamespace(selectedNamespace());
			graphics.text(font, selected ? "●" : "·", left + 6, y + 5,
					selected ? 0xFF75E6C1 : 0xFF647A74);
			String label = option.label();
			graphics.text(font, font.plainSubstrByWidth(label, right - left - 31), left + 18, y + 5,
					selected ? 0xFFFFFFFF : 0xFFD0E3DD);
		}
		graphics.disableScissor();
		if (matches.isEmpty()) {
			graphics.centeredText(font, "没有匹配的命名空间", (left + right) / 2, rowsTop + 9, 0xFF9AA9A5);
		}
		if (matches.size() > visibleRows) {
			int trackTop = rowsTop + 2;
			int trackHeight = Math.max(1, bottom - rowsTop - 4);
			int thumbHeight = Math.max(12, trackHeight * visibleRows / matches.size());
			int thumbY = trackTop + (trackHeight - thumbHeight) * namespaceScrollRow
					/ Math.max(1, matches.size() - visibleRows);
			graphics.fill(right - 3, trackTop, right - 1, bottom - 2, 0xFF263B37);
			graphics.fill(right - 4, thumbY, right - 1, thumbY + thumbHeight, 0xFF55C9A5);
		}
	}

	private boolean namespaceKeyPressed(KeyEvent event) {
		List<NamespaceOption> matches = filteredNamespaceOptions();
		if (event.key() == InputConstants.KEY_UP) {
			namespaceSelectedRow = Math.max(0, namespaceSelectedRow - 1);
			ensureNamespaceSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			namespaceSelectedRow = Math.min(Math.max(0, matches.size() - 1), namespaceSelectedRow + 1);
			ensureNamespaceSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEUP) {
			namespaceSelectedRow = Math.max(0, namespaceSelectedRow - visibleNamespaceRows());
			ensureNamespaceSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_PAGEDOWN) {
			namespaceSelectedRow = Math.min(Math.max(0, matches.size() - 1),
					namespaceSelectedRow + visibleNamespaceRows());
			ensureNamespaceSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_HOME) {
			namespaceSelectedRow = 0;
			ensureNamespaceSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_END) {
			namespaceSelectedRow = Math.max(0, matches.size() - 1);
			ensureNamespaceSelectedVisible();
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			applySelectedNamespace();
			return true;
		}
		return true;
	}

	private void toggleNamespacePicker() {
		if (namespacePickerOpen) {
			closeNamespacePicker();
			return;
		}
		namespacePickerOpen = true;
		namespaceQuery = "";
		namespaceSelectedRow = Math.max(0, Math.min(namespaceIndex, namespaceOptions.size() - 1));
		namespaceScrollRow = 0;
		ensureNamespaceSelectedVisible();
	}

	private void closeNamespacePicker() {
		namespacePickerOpen = false;
		namespaceQuery = "";
		namespaceScrollRow = 0;
	}

	private void resetNamespaceResults() {
		namespaceSelectedRow = 0;
		namespaceScrollRow = 0;
	}

	private List<NamespaceOption> filteredNamespaceOptions() {
		String needle = namespaceQuery.strip().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) return namespaceOptions;
		return namespaceOptions.stream().filter(option -> option.matches(needle)).toList();
	}

	private void applySelectedNamespace() {
		List<NamespaceOption> matches = filteredNamespaceOptions();
		if (namespaceSelectedRow < 0 || namespaceSelectedRow >= matches.size()) return;
		NamespaceOption selected = matches.get(namespaceSelectedRow);
		namespaceIndex = selected.namespace() == null ? 0 : namespaces.indexOf(selected.namespace()) + 1;
		if (namespaceIndex < 0) namespaceIndex = 0;
		closeNamespacePicker();
		invalidateFilter();
	}

	private boolean handleNamespacePickerClick(double mouseX, double mouseY) {
		if (!insideNamespacePicker(mouseX, mouseY)) return false;
		int top = namespacePickerTop();
		if (mouseY <= top + 24) return true;
		int row = namespaceScrollRow + Math.max(0, (int) (mouseY - top - 25) / 18);
		List<NamespaceOption> matches = filteredNamespaceOptions();
		if (row >= 0 && row < matches.size()) {
			namespaceSelectedRow = row;
			applySelectedNamespace();
		}
		return true;
	}

	private void ensureNamespaceSelectedVisible() {
		int visible = visibleNamespaceRows();
		if (namespaceSelectedRow < namespaceScrollRow) namespaceScrollRow = namespaceSelectedRow;
		if (namespaceSelectedRow >= namespaceScrollRow + visible) {
			namespaceScrollRow = namespaceSelectedRow - visible + 1;
		}
		namespaceScrollRow = Math.max(0, Math.min(maxNamespaceScroll(), namespaceScrollRow));
	}

	private int maxNamespaceScroll() {
		return Math.max(0, filteredNamespaceOptions().size() - visibleNamespaceRows());
	}

	private int visibleNamespaceRows() {
		return Math.max(1, (namespacePickerBottom() - namespacePickerTop() - 26) / 18);
	}

	private boolean insideNamespacePicker(double mouseX, double mouseY) {
		return inside(mouseX, mouseY, namespacePickerLeft(), namespacePickerTop(),
				namespacePickerRight(), namespacePickerBottom());
	}

	private int namespacePickerLeft() {
		int right = panelX() + windowWidth;
		int namespaceWidth = Math.min(190, Math.max(125, windowWidth / 3));
		return right - namespaceWidth - 10;
	}

	private int namespacePickerRight() {
		return panelX() + windowWidth - 10;
	}

	private int namespacePickerTop() {
		return windowY + 57;
	}

	private int namespacePickerBottom() {
		int desiredRows = Math.min(10, Math.max(1, filteredNamespaceOptions().size()));
		return Math.min(windowY + windowHeight - 29, namespacePickerTop() + 26 + desiredRows * 18);
	}

	private String selectedNamespace() {
		return namespaceIndex == 0 || namespaceIndex > namespaces.size() ? null : namespaces.get(namespaceIndex - 1);
	}

	private String namespaceDisplayName(String namespace) {
		if (namespace.equals("online")) return "在线玩家";
		try {
			return FabricLoader.getInstance().getModContainer(namespace)
					.map(container -> container.getMetadata().getName())
					.filter(name -> !name.isBlank())
					.orElse(namespace);
		} catch (RuntimeException ignored) {
			return namespace;
		}
	}

	private void invalidateFilter() {
		String needle = query.strip().toLowerCase(Locale.ROOT);
		String namespace = namespaceIndex == 0 ? null : namespaces.get(namespaceIndex - 1);
		filtered = catalogs.getOrDefault(kind, List.of()).stream()
				.filter(entry -> namespace == null || entry.namespace().equals(namespace))
				.filter(entry -> entry.matches(needle))
				.toList();
		scrollRow = 0;
		selectedRow = 0;
	}

	private boolean insertSelected() {
		if (selectedRow < 0 || selectedRow >= filtered.size()) return false;
		insertion.accept(filtered.get(selectedRow));
		return true;
	}

	private void switchKind(int direction) {
		LuaInsertCatalog.Kind[] kinds = LuaInsertCatalog.Kind.values();
		kind = kinds[Math.floorMod(kind.ordinal() + direction, kinds.length)];
		query = "";
		namespaceIndex = 0;
		namespacePickerOpen = false;
		namespaceQuery = "";
		refreshCatalog();
	}

	private void renderEntryIcon(GuiGraphicsExtractor graphics, LuaInsertCatalog.Entry entry, int x, int y) {
		try {
			switch (kind) {
				case ITEM -> {
					var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.value()));
					if (item != null) graphics.item(item.getDefaultInstance(), x, y, entry.value().hashCode());
				}
				case BLOCK -> {
					var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(entry.value()));
					ItemStack stack = block == null ? ItemStack.EMPTY : block.asItem().getDefaultInstance();
					if (stack.isEmpty()) drawFallbackIcon(graphics, x, y, "□", 0xFF6B8E75);
					else graphics.item(stack, x, y, entry.value().hashCode());
				}
				case ENTITY -> renderEntityIcon(graphics, entry.value(), x, y);
				case PLAYER -> renderPlayerIcon(graphics, entry.value(), x, y);
			}
		} catch (RuntimeException | LinkageError ignored) {
			drawFallbackIcon(graphics, x, y, kind == LuaInsertCatalog.Kind.PLAYER ? "P" : "?", 0xFF546E7A);
		}
	}

	private void renderPlayerIcon(GuiGraphicsExtractor graphics, String uuidText, int x, int y) {
		if (minecraft.getConnection() == null) {
			drawFallbackIcon(graphics, x, y, "P", 0xFF3D6D9C);
			return;
		}
		PlayerInfo player = minecraft.getConnection().getPlayerInfo(UUID.fromString(uuidText));
		if (player == null) drawFallbackIcon(graphics, x, y, "P", 0xFF3D6D9C);
		else PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), x, y, 16);
	}

	private void renderEntityIcon(GuiGraphicsExtractor graphics, String id, int x, int y) {
		EntityRenderState state = entityIcon(id);
		if (state == null) {
			var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
			var egg = type == null ? java.util.Optional.<net.minecraft.core.Holder<net.minecraft.world.item.Item>>empty()
					: SpawnEggItem.byId(type);
			if (egg.isPresent()) graphics.item(egg.get().value().getDefaultInstance(), x, y, id.hashCode());
			else drawFallbackIcon(graphics, x, y, "E", 0xFF8A5A8E);
			return;
		}
		float largest = Math.max(0.25F, Math.max(state.boundingBoxWidth, state.boundingBoxHeight));
		float scale = Math.min(12.0F, 13.5F / largest);
		Vector3f offset = new Vector3f(0.0F, state.boundingBoxHeight / 2.0F, 0.0F);
		Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf camera = new Quaternionf().rotateX(-0.12F);
		graphics.entity(state, scale, offset, pose, camera, x, y, x + 16, y + 16);
	}

	private EntityRenderState entityIcon(String id) {
		if (entityIcons.containsKey(id)) return entityIcons.get(id);
		if (unavailableEntityIcons.contains(id) || minecraft.level == null) return null;
		try {
			var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
			Entity entity = type == null ? null : type.create(minecraft.level, EntitySpawnReason.COMMAND);
			if (entity == null) {
				unavailableEntityIcons.add(id);
				return null;
			}
			EntityRenderState state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 1.0F);
			state.shadowPieces.clear();
			state.outlineColor = 0;
			state.nameTag = null;
			state.scoreText = null;
			if (state instanceof LivingEntityRenderState living) {
				living.bodyRot = 180.0F;
				living.yRot = 0.0F;
				living.xRot = 0.0F;
			}
			entityIcons.put(id, state);
			return state;
		} catch (RuntimeException | LinkageError ignored) {
			unavailableEntityIcons.add(id);
			return null;
		}
	}

	private void drawFallbackIcon(GuiGraphicsExtractor graphics, int x, int y, String label, int color) {
		graphics.fill(x, y, x + 16, y + 16, 0xFF162029);
		graphics.outline(x, y, 16, 16, color);
		graphics.centeredText(font, label, x + 8, y + 4, color);
	}

	private void ensureSelectedVisible() {
		if (selectedRow < scrollRow) scrollRow = selectedRow;
		if (selectedRow >= scrollRow + visibleRows()) scrollRow = selectedRow - visibleRows() + 1;
		scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow));
	}

	private int rowAt(double mouseX, double mouseY) {
		int x = panelX();
		int listTop = windowY + 104;
		int listBottom = windowY + windowHeight - 28;
		if (!inside(mouseX, mouseY, x + 10, listTop, x + windowWidth - 10, listBottom)) return -1;
		return scrollRow + Math.max(0, (int) (mouseY - listTop - 2) / ROW_HEIGHT);
	}

	private int visibleRows() {
		return Math.max(1, (windowHeight - 28 - (104) - 4) / ROW_HEIGHT);
	}

	private int maxScroll() {
		return Math.max(0, filtered.size() - visibleRows());
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
		int deltaX = (int) Math.round(mouseX - resizeStartMouseX);
		int deltaY = (int) Math.round(mouseY - resizeStartMouseY);
		int left = resizeStartX;
		int right = resizeStartX + resizeStartWidth;
		int top = resizeStartY;
		int bottom = resizeStartY + resizeStartHeight;
		if ((resizeEdges & EDGE_LEFT) != 0) left = Math.max(0, Math.min(right - MIN_WIDTH, resizeStartX + deltaX));
		if ((resizeEdges & EDGE_RIGHT) != 0) right = Math.min(screenWidth,
				Math.max(left + MIN_WIDTH, resizeStartX + resizeStartWidth + deltaX));
		if ((resizeEdges & EDGE_TOP) != 0) top = Math.max(0, Math.min(bottom - MIN_HEIGHT, resizeStartY + deltaY));
		if ((resizeEdges & EDGE_BOTTOM) != 0) bottom = Math.min(screenHeight,
				Math.max(top + MIN_HEIGHT, resizeStartY + resizeStartHeight + deltaY));
		windowX = left;
		windowY = top;
		windowWidth = right - left;
		windowHeight = bottom - top;
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
		scrollRow = Math.min(scrollRow, maxScroll());
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0) return;
		if (dockSide.docked()) return;
		int availableWidth = Math.max(240, screenWidth - 20);
		int availableHeight = Math.max(180, screenHeight - 20);
		if (windowWidth < 0) windowWidth = Math.min(availableWidth, Math.max(MIN_WIDTH, (int) (screenWidth * 0.68F)));
		if (windowHeight < 0) windowHeight = Math.min(availableHeight, Math.max(MIN_HEIGHT, (int) (screenHeight * 0.78F)));
		windowWidth = Math.min(windowWidth, screenWidth);
		windowHeight = Math.min(windowHeight, screenHeight);
		if (windowX < 0) windowX = Math.max(0, screenWidth - windowWidth - 10);
		windowX = Math.max(0, Math.min(screenWidth - windowWidth, windowX));
		windowY = Math.max(0, Math.min(screenHeight - windowHeight, windowY));
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
		windowX = Math.max(0, Math.min(screenWidth - windowWidth, (int) Math.round(mouseX - dragOffsetX)));
		windowY = Math.max(0, Math.min(screenHeight - windowHeight, (int) Math.round(mouseY - dragOffsetY)));
		boundsAnimation.snapTo(windowX, windowY, windowWidth, windowHeight);
	}

	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x <= right && y >= top && y <= bottom;
	}

	private record NamespaceOption(String namespace, String displayName, int count, String searchText,
			String initials) {
		NamespaceOption(String namespace, String displayName, int count) {
			this(namespace, displayName, count,
					((namespace == null ? "" : namespace) + " " + displayName).toLowerCase(Locale.ROOT),
					ChineseSearchIndex.initials(displayName));
		}

		String label() {
			if (namespace == null) return displayName + "  ·  " + count;
			String name = displayName.equalsIgnoreCase(namespace) ? namespace : namespace + " — " + displayName;
			return name + "  ·  " + count;
		}

		boolean matches(String needle) {
			return searchText.contains(needle) || initials.contains(needle);
		}

		boolean matchesNamespace(String selected) {
			return namespace == null ? selected == null : namespace.equals(selected);
		}
	}
}
