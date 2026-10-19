package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Opt-in isolated input/menu/filesystem regression check; does not connect or open a save. */
final class LuaDirectoryInputProbe {
	private static int stage;
	private static long until;
	private static CompletableFuture<Void> reload;
	private static ProbeScreen screen;
	static void tick(Minecraft client) {
		if (stage == 4 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				client.getLanguageManager().setSelected("zh_cn"); client.options.languageCode = "zh_cn";
				client.options.guiScale().set(1); client.resizeGui();
				reload = client.reloadResourcePacks(); stage = 1; return;
			}
			if (stage == 1) {
				if (!reload.isDone()) return; reload.join();
				screen = new ProbeScreen(); client.setScreen(screen); screen.verify();
				until = System.nanoTime() + 800_000_000L; stage = 2; return;
			}
			if (System.nanoTime() < until) return;
			if (stage == 2) {
				screenshot(client, "directory-name-inputs.png");
				screen.rightClick(screen.previewPath); until = System.nanoTime() + 800_000_000L; stage = 3; return;
			}
			if (stage == 3) {
				screenshot(client, "resource-directory-context.png");
				System.out.println("Directory real UI probe passed: all three filename-only inputs, hidden parent preservation, Home/Backspace/Ctrl+A, narrow viewport/mouse caret, full-path editing, JSON creation, context rename/cancel/delete confirmation, protected open files and popup bounds");
				stage = 4; client.stop();
			}
		} catch (Exception | AssertionError failure) {
			stage = 4; client.stop(); throw new IllegalStateException("Directory input probe failed", failure);
		}
	}
	private static final class ProbeScreen extends Screen {
		LuaFileOverlay lua, datapack;
		LuaResourcePackToolOverlay resources;
		String pack, previewPath;
		final List<String> opened = new ArrayList<>();
		ProbeScreen() { super(Component.literal("目录输入框测试")); }
		@Override protected void init() {
			lua = new LuaFileOverlay(minecraft, font, "", ignored -> { }, ignored -> { });
			datapack = new LuaFileOverlay(minecraft, font, "", ignored -> { }, ignored -> { }, LuaFileOverlay.Mode.DATAPACK);
			resources = new LuaResourcePackToolOverlay(minecraft, font, LuaResourcePackToolOverlay.Kind.DIRECTORY,
					opened::add, ignored -> { }, () -> null, ignored -> { }, path -> !path.contains("protected"));
			lua.resize(width, height); datapack.resize(width, height); resources.resize(width, height);
			lua.restoreGeometry(new WindowGeometry(10, 38, 180, Math.min(400, height - 48)));
			datapack.restoreGeometry(new WindowGeometry(200, 38, 220, Math.min(400, height - 48)));
			resources.restoreGeometry(new WindowGeometry(430, 38, 250, Math.min(400, height - 48)));
			lua.open(); datapack.open(); resources.open();
			for (int index = 0; index < 40; index++) tick();
		}
		@Override public void tick() { lua.tick(); datapack.tick(); resources.tick(); }
		@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			graphics.fill(0, 0, width, height, 0xFF141C28);
			graphics.text(font, "Lua / datapack / resourcepacks · 完整目录在上方，输入框编辑名称", 12, 15, 0xFFE8EFFF);
			lua.render(graphics, mouseX, mouseY); datapack.render(graphics, mouseX, mouseY); resources.render(graphics, mouseX, mouseY);
			lua.renderPopup(graphics, mouseX, mouseY); datapack.renderPopup(graphics, mouseX, mouseY);
			resources.renderDirectoryPopup(graphics, mouseX, mouseY);
		}
		@Override public boolean isPauseScreen() { return false; }
		void verify() throws Exception {
			verifyFile(lua, "tools/a_very_long_parent_directory/initial", "new_script");
			verifyFile(datapack, "demo/data/test/worldgen/noise_settings/initial", "new_world.json");
			pack = "directory_probe_" + System.currentTimeMillis();
			LuaResourcePackWorkspace.createPack(pack);
			String parent = pack + "/assets/" + pack + "/models/item";
			invoke(resources, "refresh");
			prepareResource("JSON", parent, "new_resource", ".json");
			resources.keyPressed(key(InputConstants.KEY_A, 2)); type(resources, "created.json");
			require(value(resources, "path").equals(parent + "/created.json"), "Resource filename replacement changed parent");
			resources.keyPressed(key(InputConstants.KEY_RETURN, 0));
			require(exists(parent + "/created.json") && opened.contains(parent + "/created.json"), "Pending JSON creation wrong target");
			String[] buttons = (String[]) invoke(resources, "directoryActions");
			require(Arrays.stream(buttons).noneMatch(label -> label.contains("重命名") || label.contains("删除")) && buttons.length == 5, "Old rename/delete toolbar buttons remain");
			rightClick(parent + "/created.json"); clickContext("RENAME");
			require(value(resources, "pendingCreate").toString().equals("RENAME"), "Right-click rename did not begin");
			resources.keyPressed(key(InputConstants.KEY_A, 2)); type(resources, "renamed.json");
			resources.keyPressed(key(InputConstants.KEY_RETURN, 0));
			require(!exists(parent + "/created.json") && exists(parent + "/renamed.json"), "Context rename failed");
			rightClick(parent + "/renamed.json"); clickContext("RENAME");
			resources.keyPressed(key(InputConstants.KEY_A, 2)); type(resources, "cancelled.json");
			resources.keyPressed(key(InputConstants.KEY_ESCAPE, 0));
			require(value(resources, "path").equals(parent + "/renamed.json") && !exists(parent + "/cancelled.json"), "Rename cancellation changed filesystem/path");
			rightClick(parent + "/renamed.json"); clickContext("DELETE");
			require(exists(parent + "/renamed.json"), "Delete did not ask for confirmation");
			rightClick(parent + "/renamed.json");
			require(contextLabel("DELETE").equals("确认删除"), "Right-click lost delete confirmation");
			clickContext("DELETE"); require(!exists(parent + "/renamed.json"), "Confirmed context delete failed");
			LuaResourcePackWorkspace.createText(parent + "/protected.json", "{}\n"); invoke(resources, "refresh");
			rightClick(parent + "/protected.json"); clickContext("RENAME");
			require(value(resources, "pendingCreate").toString().equals("NONE"), "Rename ignored open file safety");
			rightClick(parent + "/protected.json"); clickContext("DELETE");
			require(exists(parent + "/protected.json") && value(resources, "pendingDelete").equals(""), "Delete ignored open file safety");
			previewPath = parent + "/a_long_filename_for_preview.json";
			LuaResourcePackWorkspace.createText(previewPath, "{}\n"); invoke(resources, "refresh");
			setResourcePath(previewPath);
			resources.keyPressed(key(InputConstants.KEY_HOME, 0));
			int start = DirectoryPathInput.leafStart(previewPath);
			resources.keyPressed(key(InputConstants.KEY_BACKSPACE, 0));
			require(value(resources, "path").equals(previewPath) && (int) value(resources, "pathCursor") == start, "Home/Backspace removed resource parent");
			int rx = (int) value(resources, "x"), ry = (int) value(resources, "y");
			resources.mouseClicked(mouse(rx + 17, ry + 26 + 22 + 8, 0), false);
			require((int) value(resources, "pathCursor") == start, "Resource mouse caret at basename start failed");
			resources.mouseClicked(mouse(rx + 20, ry + 26 + 8, 0), false);
			require((boolean) value(resources, "editingFullPath"), "Cannot edit full resource path from breadcrumb");
			resources.keyPressed(key(InputConstants.KEY_ESCAPE, 0));
			require(resources.isOpen() && !(boolean) value(resources, "editingFullPath"), "Esc did not return to filename mode");
			setResourcePath(previewPath);
			resources.keyPressed(key(InputConstants.KEY_A, 2));
			require((int) value(resources, "pathAnchor") == start, "Resource select-all includes hidden parent");
		}
		private void verifyFile(LuaFileOverlay overlay, String path, String replacement) throws Exception {
			set(overlay, "name", path); set(overlay, "cursor", path.length()); set(overlay, "anchor", path.length());
			set(overlay, "inputFocused", true); set(overlay, "keyboardFocused", true);
			overlay.keyPressed(key(InputConstants.KEY_A, 2));
			require((int) value(overlay, "anchor") == DirectoryPathInput.leafStart(path), "File select-all includes hidden parent");
			replacement.codePoints().forEach(point -> overlay.charTyped(new CharacterEvent(point)));
			String expected = DirectoryPathInput.parent(path) + replacement;
			require(value(overlay, "name").equals(expected), "Filename typing changed full target path");
			overlay.keyPressed(key(InputConstants.KEY_HOME, 0)); overlay.keyPressed(key(InputConstants.KEY_BACKSPACE, 0));
			require(value(overlay, "name").equals(expected), "Backspace removes hidden directory");
			int x = (int) value(overlay, "windowX"), y = (int) value(overlay, "windowY");
			overlay.mouseClicked(mouse(x + 17, y + 49 + 8, 0));
			require((int) value(overlay, "cursor") == DirectoryPathInput.leafStart(expected), "Mouse caret not at visible name start");
			overlay.mouseClicked(mouse(x + 20, y + 34, 0));
			require((boolean) value(overlay, "editingFullPath"), "File parent line does not enable full path editing");
			overlay.keyPressed(key(InputConstants.KEY_A, 2));
			require((int) value(overlay, "anchor") == 0, "Explicit full-path mode cannot select parent");
			overlay.keyPressed(key(InputConstants.KEY_ESCAPE, 0));
			require(overlay.isOpen() && !(boolean) value(overlay, "editingFullPath"), "Esc closed file window instead of restoring basename");
			overlay.keyPressed(key(InputConstants.KEY_A, 2));
		}
		private void setResourcePath(String path) throws Exception {
			set(resources, "path", path); set(resources, "pathCursor", path.length()); set(resources, "pathAnchor", path.length());
			set(resources, "editingFullPath", false); set(resources, "inputFocus", true); set(resources, "keyboardFocus", true);
		}
		private void prepareResource(String kind, String parent, String base, String extension) throws Exception {
			Class<?> type = Class.forName(LuaResourcePackToolOverlay.class.getName() + "$PendingCreate");
			Method method = resources.getClass().getDeclaredMethod("prepareCreate", type, String.class, String.class, String.class); method.setAccessible(true);
			method.invoke(resources, enumValue(type, kind), parent, base, extension);
		}
		@SuppressWarnings("unchecked") void rightClick(String path) throws Exception {
			invoke(resources, "refresh");
			List<LuaResourcePackWorkspace.Entry> visible = (List<LuaResourcePackWorkspace.Entry>) value(resources, "visible");
			int index = -1; for (int row = 0; row < visible.size(); row++) if (visible.get(row).path().equals(path)) index = row;
			require(index >= 0, "Fixture entry not visible: " + path);
			set(resources, "listScroll", index);
			int x = (int) value(resources, "x"), y = (int) value(resources, "y");
			resources.dismissDirectoryContext(); resources.mouseClicked(mouse(x + 20, y + 26 + 124 + 5, 1), false);
			require((boolean) value(resources, "contextOpen"), "Right click did not open context menu");
			int menuY = (int) value(resources, "contextY");
			List<?> actions = (List<?>) invoke(resources, "contextActions");
			require(menuY >= 4 && menuY + 6 + actions.size() * 22 <= height, "Context actions are off screen");
		}
		private void clickContext(String action) throws Exception {
			List<?> actions = (List<?>) invoke(resources, "contextActions"); int row = -1;
			for (int index = 0; index < actions.size(); index++) if (actions.get(index).toString().equals(action)) row = index;
			require(row >= 0, "Missing context action " + action);
			resources.mouseClicked(mouse((int) value(resources, "contextX") + 10, (int) value(resources, "contextY") + 3 + row * 22 + 8, 0), false);
		}
		private String contextLabel(String action) throws Exception {
			Class<?> type = Class.forName(LuaResourcePackToolOverlay.class.getName() + "$ContextAction");
			Method method = resources.getClass().getDeclaredMethod("contextLabel", type); method.setAccessible(true);
			return (String) method.invoke(resources, enumValue(type, action));
		}
	}
	private static boolean exists(String path) throws Exception { return LuaResourcePackWorkspace.list().stream().anyMatch(entry -> entry.path().equals(path)); }
	private static void type(LuaResourcePackToolOverlay overlay, String value) { value.codePoints().forEach(point -> overlay.charTyped(new CharacterEvent(point))); }
	@SuppressWarnings({"rawtypes", "unchecked"}) private static Object enumValue(Class<?> type, String value) { return Enum.valueOf((Class) type, value); }
	private static KeyEvent key(int key, int modifiers) { return new KeyEvent(key, 0, modifiers); }
	private static MouseButtonEvent mouse(double x, double y, int button) { return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)); }
	private static Object value(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
	private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
	private static Object invoke(Object object, String name) throws Exception { Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static void screenshot(Minecraft client, String name) { net.minecraft.client.Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), 1, ignored -> { }); }
}
