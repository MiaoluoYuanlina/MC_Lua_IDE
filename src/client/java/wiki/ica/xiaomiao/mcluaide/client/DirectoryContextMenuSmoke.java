package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Headless checks; never opens Explorer, a terminal or a native file chooser. */
public final class DirectoryContextMenuSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		checkMenus(); checkPaths();
		System.out.println("Directory context menu smoke passed: " + checks + " checks; all three menus, overflow, submenus, roots, safe paths and remote refusal");
	}

	private static void checkMenus() throws Exception {
		for (var mode : LuaFileOverlay.Mode.values()) {
			var overlay = new LuaFileOverlay(null, null, "", ignored -> { }, ignored -> { }, mode);
			for (var entry : List.of(new ScriptManager.ScriptEntry("", true), new ScriptManager.ScriptEntry("demo", true),
					new ScriptManager.ScriptEntry(mode == LuaFileOverlay.Mode.LUA ? "demo/main" : "demo/data/demo/file.json", false),
					new ScriptManager.ScriptEntry(mode == LuaFileOverlay.Mode.LUA ? "tools" : "demo/pack.mcmeta", false))) {
				List<?> actions = (List<?>) invoke(overlay, "contextActions", new Class<?>[]{ScriptManager.ScriptEntry.class}, entry);
				require(actions.stream().filter(value -> value.toString().equals("OPEN_EXPLORER")).count() == 1, "Exactly one explorer action " + mode + "/" + entry.path());
			}
			var entry = new ScriptManager.ScriptEntry(mode == LuaFileOverlay.Mode.LUA ? "demo" : "demo/data/demo", true);
			set(overlay, "contextEntry", entry); set(overlay, "contextOpen", true); set(overlay, "open", true); set(overlay, "progress", 1F);
			set(overlay, "windowX", 10); set(overlay, "windowY", 30); set(overlay, "windowWidth", 160); set(overlay, "windowHeight", 280);
			set(overlay, "screenWidth", 800); set(overlay, "screenHeight", 600); set(overlay, "contextX", 300); set(overlay, "contextY", 90);
			List<?> actions = (List<?>) invoke(overlay, "contextActions", new Class<?>[]{ScriptManager.ScriptEntry.class}, entry);
			int row = index(actions, mode == LuaFileOverlay.Mode.LUA ? "NEW_SCRIPT" : "NEW_DIRECTORY");
			int y = 90 + 3 + row * 22 + 8;
			require(overlay.contains(310, y) && overlay.contextContains(310, y), "File popup owns overflow region");
			require(overlay.mouseScrolled(310, y, -1) && (boolean) value(overlay, "contextOpen"), "Popup scroll is not passed to background");
			require(overlay.mouseClicked(mouse(310, y, 0)), "Overflow file action click");
			require(value(overlay, "name").equals(entry.path() + "/") && !(boolean) value(overlay, "contextOpen"), "Overflow file action ran");
			overlay.dismissContext(); require(!overlay.contextContains(310, y), "Dismissed file popup releases hit region");
		}
		var resource = new LuaResourcePackToolOverlay(null, null, LuaResourcePackToolOverlay.Kind.DIRECTORY,
				ignored -> { }, ignored -> { }, () -> null, ignored -> { }, ignored -> true);
		for (var entry : List.of(new LuaResourcePackWorkspace.Entry("demo", true, 0),
				new LuaResourcePackWorkspace.Entry("demo/assets/demo", true, 2),
				new LuaResourcePackWorkspace.Entry("demo/assets/demo/sounds.json", false, 3))) {
			set(resource, "contextEntry", entry);
			List<?> actions = (List<?>) invoke(resource, "contextActions");
			require(actions.stream().filter(value -> value.toString().equals("OPEN_EXPLORER")).count() == 1, "Resource explorer action " + entry.path());
		}
		set(resource, "contextEntry", null);
		require(index((List<?>) invoke(resource, "contextActions"), "OPEN_EXPLORER") >= 0, "Empty resource tree opens root");
		set(resource, "contextEntry", new LuaResourcePackWorkspace.Entry("demo/assets/demo", true, 2));
		set(resource, "open", true); set(resource, "progress", 1F); set(resource, "contextOpen", true);
		set(resource, "screenWidth", 800); set(resource, "screenHeight", 600); set(resource, "x", 10); set(resource, "y", 180);
		set(resource, "width", 170); set(resource, "height", 280); set(resource, "contextX", 300); set(resource, "contextY", 80);
		List<?> actions = (List<?>) invoke(resource, "contextActions"); int row = index(actions, "NEW_DIRECTORY");
		int y = 80 + 3 + row * 22 + 8;
		require(resource.contains(310, y) && resource.directoryContextContains(310, y), "Primary resource popup overflow hit");
		require(resource.mouseClicked(mouse(310, y, 0), false), "Primary popup outside body clickable");
		require(value(resource, "pendingCreate").toString().equals("DIRECTORY"), "Primary popup action executed");
		set(resource, "contextOpen", true); set(resource, "pendingCreate", enumValue(resource, "PendingCreate", "NONE"));
		row = index(actions, "QUICK_ADD"); y = 80 + 3 + row * 22 + 8;
		require(resource.mouseClicked(mouse(310, y, 0), false) && (boolean) value(resource, "quickAddOpen"), "Child submenu opens");
		int childX = (int) invoke(resource, "quickAddX"), childY = (int) invoke(resource, "quickAddY");
		require(childX >= 180 && resource.contains(childX + 10, childY + 10), "Child submenu outside body owns clicks");
		// Exercise dispatch without spawning a native chooser in a headless process.
		set(resource, "choosingOgg", true);
		require(resource.mouseClicked(mouse(childX + 10, childY + 10, 0), false), "Child submenu overflow clickable");
		require(!(boolean) value(resource, "quickAddOpen") && !(boolean) value(resource, "contextOpen"), "Child action dispatched");
		set(resource, "choosingOgg", false); resource.dismissDirectoryContext();
		require(!resource.contains(childX + 10, childY + 10), "Closed resource popup releases hit region");
	}

	private static void checkPaths() throws Exception {
		Path sandbox = Files.createTempDirectory("mc_lua_directory_launch_");
		try {
			Path scripts = Files.createDirectories(sandbox.resolve("含空格的 scripts/tools"));
			Path root = scripts.getParent(); Path script = scripts.resolve("main.lua"); Files.writeString(script, "print(1)");
			require(LocalWorkspaceLauncher.resolveLua(root, "tools/main", false).equals(script), "Lua file .lua suffix/location");
			require(LocalWorkspaceLauncher.resolveLua(root, "tools", true).equals(scripts), "Lua subdirectory");
			require(LocalWorkspaceLauncher.resolveLua(root, "", true).equals(root), "Lua root");
			for (String invalid : List.of("../outside", "tools/../main", "tools\\main", "C:/Windows", "/tools", "tools//main", "tools/main.lua", "missing"))
				reject(() -> LocalWorkspaceLauncher.resolveLua(root, invalid, false), "Invalid Lua path " + invalid);
			reject(() -> LocalWorkspaceLauncher.resolveLua(root, "tools/main", true), "Lua file as directory");
			reject(() -> LocalWorkspaceLauncher.existingTarget(root, sandbox, true), "Path outside root");
			for (String kind : List.of("数据包", "资源包")) {
				Path packs = Files.createDirectory(sandbox.resolve(kind));
				Path directory = Files.createDirectories(packs.resolve("mc_lua_ide_edit_demo/data/demo"));
				Path file = directory.resolve("file.json"); Files.writeString(file, "{}");
				require(LocalWorkspaceLauncher.resolveManagedPack(packs, "demo/data/demo/file.json", false, kind).equals(file), "Managed pack file " + kind);
				require(LocalWorkspaceLauncher.resolveManagedPack(packs, "demo/data/demo", true, kind).equals(directory), "Managed pack directory " + kind);
				if (kind.equals("数据包")) require(LocalDatapackLauncher.resolve(packs, "demo/data/demo/file.json", false).equals(file), "Existing datapack resolver preserved");
				for (String invalid : List.of("../outside", "demo/../outside", "demo\\data", "demo//data", "other/data", "demo/data/missing"))
					reject(() -> LocalWorkspaceLauncher.resolveManagedPack(packs, invalid, true, kind), "Invalid managed pack path " + invalid);
			}
			reject(() -> LocalWorkspaceLauncher.requireLocalServer(false, "Lua 脚本"), "Remote Lua paths cannot open client scripts");
			reject(() -> LocalWorkspaceLauncher.requireLocalServer(false, "数据包"), "Remote datapacks cannot open client world");
			LocalWorkspaceLauncher.requireLocalServer(true, "Lua 脚本"); checks++;
		} finally {
			try (var paths = Files.walk(sandbox)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
	@FunctionalInterface private interface IoAction { void run() throws Exception; }
	private static void reject(IoAction action, String reason) throws Exception { try { action.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError(reason); }
	static MouseButtonEvent mouse(double x, double y, int button) { return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)); }
	static int index(List<?> actions, String name) { for (int i = 0; i < actions.size(); i++) if (actions.get(i).toString().equals(name)) return i; throw new AssertionError("Missing " + name); }
	static Object value(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
	static void set(Object owner, String name, Object value) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
	static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args); }
	static Object invoke(Object owner, String name) throws Exception { return invoke(owner, name, new Class<?>[0]); }
	@SuppressWarnings({"rawtypes", "unchecked"}) static Object enumValue(Object owner, String type, String name) throws ClassNotFoundException { return Enum.valueOf((Class) Class.forName(owner.getClass().getName() + "$" + type), name); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
