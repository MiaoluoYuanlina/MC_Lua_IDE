package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** In-game save location chooser; it remains visible while server API metadata is synchronized. */
final class LuaApiExportScreen extends Screen {
	private final LuaIdeScreen previous;
	private Path directory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
	private List<Path> directories = List.of();
	private final List<Button> directoryButtons = new ArrayList<>();
	private EditBox directoryField;
	private EditBox fileNameField;
	private Button saveButton;
	private Button goButton, rootsButton, upButton, backButton;
	private String markdown;
	private String status = LuaUiText.text("正在从服务器同步当前 API…");
	private int left, top, right, bottom;
	private int listTop, listBottom;
	private int scroll;
	private boolean rootView;
	private boolean saving;
	private boolean saved;
	private Path overwriteTarget;
	private int floatingX = -1, floatingY = -1, floatingWidth = -1, floatingHeight = -1;
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked, embedded, dragging;
	private boolean closing, applyingAnimatedBounds;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();
	private int resizeEdges, resizeLeft, resizeTop, resizeRight, resizeBottom;
	private double resizeStartX, resizeStartY, dragOffsetX, dragOffsetY;
	private int visibleRows;

	LuaApiExportScreen(LuaIdeScreen previous) {
		super(Component.literal(LuaUiText.text("MC Lua IDE · 导出当前 API")));
		this.previous = previous;
	}

	void openEmbedded(int width, int height) {
		embedded = true;
		init(width, height);
		rememberFloatingBounds();
		int targetX = left, targetY = top;
		int targetWidth = right - left, targetHeight = bottom - top;
		applyingAnimatedBounds = true;
		applyBounds(width + 12, targetY, targetWidth, targetHeight);
		applyingAnimatedBounds = false;
		boundsAnimation.animateTo(left, top, right - left, bottom - top,
				targetX, targetY, targetWidth, targetHeight);
	}

	boolean isClosing() { return closing; }
	void reopen() {
		if (!closing) return;
		closing = false;
		if (!dockSide.docked()) boundsAnimation.animateTo(left, top, right - left, bottom - top,
				floatingX, floatingY, floatingWidth, floatingHeight);
	}

	boolean contains(double x, double y) {
		int margin = dockSide.docked() ? 0 : 5;
		return embedded && !closing && x >= left - margin && x < right + margin
				&& y >= top - margin && y < bottom + margin;
	}

	boolean isDraggingPanel() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }
	WindowGeometry captureGeometry() {
		if (!dockSide.docked() && !boundsAnimation.active()) rememberFloatingBounds();
		return new WindowGeometry(floatingX, floatingY, floatingWidth, floatingHeight);
	}
	void restoreGeometry(WindowGeometry geometry) {
		if (geometry == null) return;
		floatingX = geometry.x(); floatingY = geometry.y();
		floatingWidth = geometry.width(); floatingHeight = geometry.height();
	}

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		if (!dockSide.docked() && !boundsAnimation.active()) rememberFloatingBounds();
		DockSide previous = dockSide;
		dockSide = side;
		resizeEdges = 0;
		if (previous.docked() && !side.docked() && floatingWidth > 0 && floatingHeight > 0) {
			boundsAnimation.animateTo(left, top, right - left, bottom - top,
					Math.clamp(floatingX, 0, Math.max(0, width - floatingWidth)),
					Math.clamp(floatingY, 0, Math.max(0, height - floatingHeight)),
					Math.min(floatingWidth, width), Math.min(floatingHeight, height));
		}
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (dockSide.docked() && !closing) boundsAnimation.animateTo(left, top,
				right - left, bottom - top, x, y, width, height);
	}

	@Override public void tick() {
		super.tick();
		if (dragging || resizeEdges != 0) return;
		WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(left, top, right - left, bottom - top);
		applyingAnimatedBounds = true;
		applyBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
		applyingAnimatedBounds = false;
		if (closing && !bounds.moving()) previous.closeExportPanel(this);
	}

	@Override
	protected void init() {
		String oldFileName = fileNameField == null ? "mc-lua-ide-api.md" : fileNameField.getValue();
		directoryButtons.clear();
		int panelWidth = Math.min(floatingWidth > 0 ? floatingWidth : 680, Math.max(1, width - 24));
		int panelHeight = Math.min(floatingHeight > 0 ? floatingHeight : 480, Math.max(1, height - 24));
		left = embedded && floatingX >= 0 ? Math.clamp(floatingX, 0, Math.max(0, width - panelWidth))
				: (width - panelWidth) / 2;
		right = left + panelWidth;
		top = embedded && floatingY >= 0 ? Math.clamp(floatingY, 0, Math.max(0, height - panelHeight))
				: (height - panelHeight) / 2;
		bottom = top + panelHeight;
		int innerWidth = right - left - 24;
		directoryField = addRenderableWidget(new EditBox(font, left + 12, top + 61,
				Math.max(80, innerWidth - 65), 20, Component.literal(LuaUiText.text("保存目录"))));
		directoryField.setMaxLength(1024);
		directoryField.setValue(directory.toString());
		goButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("前往")), ignored -> goToTypedDirectory())
				.bounds(right - 63, top + 61, 51, 20).build());
		fileNameField = addRenderableWidget(new EditBox(font, left + 12, top + 99,
				Math.max(80, innerWidth), 20, Component.literal(LuaUiText.text("Markdown 文件名"))));
		fileNameField.setMaxLength(200);
		fileNameField.setValue(oldFileName);
		fileNameField.setResponder(value -> {
			overwriteTarget = null;
			updateSaveButton();
		});
		rootsButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("此电脑")), ignored -> showRoots())
				.bounds(left + 12, top + 28, 62, 20).build());
		upButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("上级目录")), ignored -> upDirectory())
				.bounds(left + 79, top + 28, 72, 20).build());
		listTop = top + 133;
		listBottom = bottom - 58;
		for (int index = 0; index < 12; index++) {
			final int row = index;
			Button button = addRenderableWidget(Button.builder(Component.empty(), ignored -> openRow(row))
					.bounds(left + 12, listTop + index * 22, innerWidth, 20).build());
			directoryButtons.add(button);
		}
		saveButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("导出 Markdown")), ignored -> save())
				.bounds(right - 223, bottom - 34, 120, 22).build());
		backButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("关闭")), ignored -> onClose())
				.bounds(right - 96, bottom - 34, 84, 22).build());
		layoutWidgets();
		refreshDirectory();
		updateSaveButton();
	}

	private void rememberFloatingBounds() {
		floatingX = left; floatingY = top;
		floatingWidth = right - left; floatingHeight = bottom - top;
	}

	private void applyBounds(int x, int y, int panelWidth, int panelHeight) {
		if (left == x && top == y && right - left == panelWidth && bottom - top == panelHeight) return;
		left = x; top = y; right = x + panelWidth; bottom = y + panelHeight;
		layoutWidgets();
		if (!dockSide.docked() && !applyingAnimatedBounds) rememberFloatingBounds();
	}

	private void layoutWidgets() {
		if (directoryField == null) return;
		int innerWidth = right - left - 24;
		directoryField.setX(left + 12); directoryField.setY(top + 61);
		directoryField.setWidth(Math.max(80, innerWidth - 65));
		goButton.setX(right - 63); goButton.setY(top + 61);
		fileNameField.setX(left + 12); fileNameField.setY(top + 99);
		fileNameField.setWidth(Math.max(80, innerWidth));
		rootsButton.setX(left + 12); rootsButton.setY(top + 28);
		upButton.setX(left + 79); upButton.setY(top + 28);
		listTop = top + 133;
		listBottom = bottom - 58;
		visibleRows = Math.max(0, Math.min(directoryButtons.size(), (listBottom - listTop) / 22));
		for (int index = 0; index < directoryButtons.size(); index++) {
			Button button = directoryButtons.get(index);
			button.setX(left + 12); button.setY(listTop + index * 22);
			button.setWidth(Math.max(80, innerWidth));
		}
		saveButton.setX(right - 223); saveButton.setY(bottom - 34);
		backButton.setX(right - 26); backButton.setY(top + 4); backButton.setWidth(20); backButton.setHeight(20); backButton.visible = false;
		saveButton.setX(right - 139);
		updateRows();
	}

	void setMarkdown(String markdown, int functionCount, int pageCount) {
		this.markdown = markdown;
		saved = false;
		overwriteTarget = null;
		status = "已同步 " + functionCount + " 个函数和 " + pageCount + " 个扩展页面；选择目录后导出";
		updateSaveButton();
	}

	void setFailure(String message) {
		status = LuaUiText.text("同步失败：") + message;
		updateSaveButton();
	}

	private void showRoots() {
		rootView = true;
		scroll = 0;
		refreshDirectory();
		updateSaveButton();
	}

	private void upDirectory() {
		if (rootView) return;
		Path parent = directory.getParent();
		if (parent == null) showRoots();
		else navigate(parent);
	}

	private void goToTypedDirectory() {
		try {
			navigate(Path.of(directoryField.getValue().strip()).toAbsolutePath().normalize());
		} catch (RuntimeException error) {
			status = "目录路径无效：" + error.getMessage();
		}
	}

	private void navigate(Path target) {
		if (!Files.isDirectory(target)) {
			status = "目录不存在或无法访问：" + target;
			return;
		}
		directory = target;
		rootView = false;
		scroll = 0;
		overwriteTarget = null;
		directoryField.setValue(target.toString());
		refreshDirectory();
		updateSaveButton();
	}

	private void refreshDirectory() {
		try {
			if (rootView) {
				List<Path> roots = new ArrayList<>();
				FileSystems.getDefault().getRootDirectories().forEach(roots::add);
				directories = List.copyOf(roots);
			} else {
				try (var listed = Files.list(directory)) {
					directories = listed.filter(Files::isDirectory).limit(1000)
							.sorted(Comparator.comparing(path -> path.getFileName() == null ? path.toString()
									: path.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
				}
			}
			status = markdown == null ? LuaUiText.text("正在从服务器同步当前 API…")
					: "选择保存目录；也可在上方直接输入绝对路径并点击“前往”";
		} catch (IOException | SecurityException error) {
			directories = List.of();
			status = LuaUiText.text("读取目录失败：") + error.getMessage();
		}
		updateRows();
	}

	private void updateRows() {
		scroll = Math.max(0, Math.min(scroll, Math.max(0, directories.size() - visibleRows)));
		for (int index = 0; index < directoryButtons.size(); index++) {
			Button button = directoryButtons.get(index);
			int position = scroll + index;
			button.visible = index < visibleRows && position < directories.size();
			button.active = button.visible;
			if (button.visible) {
				Path path = directories.get(position);
				String label = path.getFileName() == null ? path.toString() : path.getFileName().toString();
				button.setMessage(Component.literal(LuaUiText.text("[目录] ") + font.plainSubstrByWidth(label, right - left - 72)));
			}
		}
	}

	private void openRow(int row) {
		int index = scroll + row;
		if (index >= 0 && index < directories.size()) navigate(directories.get(index));
	}

	private void save() {
		if (markdown == null || saving || saved || rootView) return;
		String file = fileNameField.getValue().strip();
		if (file.isEmpty() || file.equals(".") || file.equals("..") || file.contains("/") || file.contains("\\")) {
			status = "请输入不含路径分隔符的 Markdown 文件名";
			return;
		}
		if (!file.toLowerCase(Locale.ROOT).endsWith(".md")) file += ".md";
		Path target;
		try {
			target = directory.resolve(file).toAbsolutePath().normalize();
			if (!target.getParent().equals(directory)) throw new IllegalArgumentException("文件必须位于选定目录");
		} catch (RuntimeException error) {
			status = "文件名无效：" + error.getMessage();
			return;
		}
		if (Files.exists(target) && !target.equals(overwriteTarget)) {
			overwriteTarget = target;
			status = "文件已存在；再次点击“确认覆盖”才会替换：" + target;
			updateSaveButton();
			return;
		}
		saving = true;
		status = "正在写入：" + target;
		updateSaveButton();
		String contents = markdown;
		CompletableFuture.runAsync(() -> {
			try {
				LuaApiMarkdownExporter.writeAtomically(target, contents);
				minecraft.execute(() -> {
					saving = false;
					saved = true;
					status = "导出完成：" + target;
					previous.setExportStatus(status);
					updateSaveButton();
				});
			} catch (IOException | SecurityException error) {
				minecraft.execute(() -> {
					saving = false;
					status = LuaUiText.text("导出失败：") + error.getMessage();
					updateSaveButton();
				});
			}
		});
	}

	private void updateSaveButton() {
		if (saveButton == null) return;
		saveButton.active = markdown != null && !saving && !saved && !rootView;
		saveButton.setMessage(Component.literal(saved ? "已导出" : saving ? LuaUiText.text("正在保存…")
				: overwriteTarget != null ? LuaUiText.text("确认覆盖") : LuaUiText.text("导出 Markdown")));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX >= left + 12 && mouseX < right - 12 && mouseY >= listTop && mouseY < listBottom) {
			scroll = Math.max(0, Math.min(Math.max(0, directories.size() - visibleRows),
					scroll - (int) Math.round(verticalAmount * 3)));
			updateRows();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN && getFocused() == directoryField) {
			goToTypedDirectory();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!embedded) graphics.fill(0, 0, width, height, 0xD90A1018);
		graphics.fill(left - 4, top + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(left, top, right, bottom, 0xFE0E141D);
		graphics.outline(left, top, right - left, bottom - top, 0xFFB084F5);
		graphics.fill(left + 1, top + 1, right - 1, top + 27, 0xFF2B2040);
		graphics.text(font, font.plainSubstrByWidth(LuaUiText.text("◆  导出当前 API · Markdown"), right - left - 52), left + 13, top + 9, 0xFFD6B7FF);
		IdeWindowCloseButton.render(graphics, font, right, top, mouseX, mouseY);
		graphics.text(font, LuaUiText.text("保存目录"), left + 12, top + 49, 0xFFAFC4CE);
		graphics.text(font, LuaUiText.text("文件名"), left + 12, top + 87, 0xFFAFC4CE);
		graphics.text(font, rootView ? "选择磁盘 / 根目录" : LuaUiText.text("子目录 · 单击进入，滚轮浏览"), left + 12,
				listTop - 11, 0xFF96B6C5);
		if (listBottom > listTop) {
			graphics.fill(left + 11, listTop - 1, right - 11, listBottom, 0xFF0D151D);
			graphics.outline(left + 11, listTop - 1, right - left - 22, listBottom - listTop + 1, 0xFF3C5562);
		}
		if (directories.isEmpty()) graphics.text(font, LuaUiText.text("没有可浏览的子目录；可直接使用当前目录"),
				left + 20, listTop + 9, 0xFF879DA8);
		graphics.text(font, font.plainSubstrByWidth(status, right - left - 24),
				left + 12, bottom - 52, status.contains(LuaUiText.text("失败")) ? 0xFFFF7777 : 0xFFE0C984);
		if (embedded && !dockSide.docked()) ResizeBorderHint.render(graphics,
				left, top, right, bottom, resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY),
				0xFFB084F5);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private int resizeEdgesAt(double x, double y) {
		if (dockSide.docked() || x < left - 5 || x > right + 5 || y < top - 5 || y > bottom + 5)
			return 0;
		int edges = 0;
		if (Math.abs(x - left) <= 5) edges |= 1;
		if (Math.abs(x - right) <= 5) edges |= 2;
		if (Math.abs(y - top) <= 5) edges |= 4;
		if (Math.abs(y - bottom) <= 5) edges |= 8;
		return edges;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (embedded && !contains(event.x(), event.y())) return false;
		if (event.button() == 0 && IdeWindowCloseButton.contains(event.x(), event.y(), right, top)) { onClose(); return true; }
		if (embedded && event.button() == 0)
			boundsAnimation.snapTo(left, top, right - left, bottom - top);
		if (embedded && event.button() == 0 && !dockSide.docked()) {
			resizeEdges = resizeEdgesAt(event.x(), event.y());
			if (resizeEdges != 0) {
				resizeStartX = event.x(); resizeStartY = event.y();
				resizeLeft = left; resizeTop = top; resizeRight = right; resizeBottom = bottom;
				return true;
			}
		}
		if (embedded && event.button() == 0 && event.y() >= top && event.y() < top + 27) {
			dragging = !dockSide.docked() || !layoutLocked;
			dragOffsetX = event.x() - left;
			dragOffsetY = event.y() - top;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (resizeEdges != 0) {
			int dx = (int) Math.round(event.x() - resizeStartX);
			int dy = (int) Math.round(event.y() - resizeStartY);
			int minWidth = Math.min(330, Math.max(1, width - 24));
			int minHeight = Math.min(240, Math.max(1, height - 24));
			int newLeft = (resizeEdges & 1) != 0 ? Math.clamp(resizeLeft + dx, 0, resizeRight - minWidth) : resizeLeft;
			int newRight = (resizeEdges & 2) != 0 ? Math.clamp(resizeRight + dx, newLeft + minWidth, width) : resizeRight;
			int newTop = (resizeEdges & 4) != 0 ? Math.clamp(resizeTop + dy, 0, resizeBottom - minHeight) : resizeTop;
			int newBottom = (resizeEdges & 8) != 0 ? Math.clamp(resizeBottom + dy, newTop + minHeight, height) : resizeBottom;
			applyBounds(newLeft, newTop, newRight - newLeft, newBottom - newTop);
			return true;
		}
		if (!dragging) return super.mouseDragged(event, dragX, dragY);
		if (dockSide.docked()) {
			setDockSide(DockSide.FLOATING);
			dragOffsetX = Math.min(dragOffsetX, Math.max(18, right - left - 18));
			dragOffsetY = 12;
		}
		boundsAnimation.snapTo(left, top, right - left, bottom - top);
		int nextX = Math.clamp((int) Math.round(event.x() - dragOffsetX), 0, Math.max(0, width - (right - left)));
		int nextY = Math.clamp((int) Math.round(event.y() - dragOffsetY), 0, Math.max(0, height - (bottom - top)));
		applyBounds(nextX, nextY, right - left, bottom - top);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (resizeEdges != 0) { resizeEdges = 0; return true; }
		if (dragging) { dragging = false; return true; }
		return super.mouseReleased(event);
	}

	@Override public void onClose() {
		if (!embedded) { minecraft.setScreen(previous); return; }
		if (closing) return;
		closing = true;
		int targetX = dockSide == DockSide.LEFT ? -(right - left) - 12
				: dockSide == DockSide.BOTTOM ? left : width + 12;
		int targetY = dockSide == DockSide.BOTTOM ? height + 12 : top;
		boundsAnimation.animateTo(left, top, right - left, bottom - top,
				targetX, targetY, right - left, bottom - top);
	}
	@Override public boolean isPauseScreen() { return false; }
}
