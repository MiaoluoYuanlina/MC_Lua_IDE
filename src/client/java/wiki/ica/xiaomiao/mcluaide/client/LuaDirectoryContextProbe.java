package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

import static wiki.ica.xiaomiao.mcluaide.client.DirectoryContextMenuSmoke.*;

/** Actual IDE event routing with menus outside docked/floating frames; no native OS dialogs. */
final class LuaDirectoryContextProbe {
	private static int stage, checks;
	private static long until;
	private static LuaIdeScreen ide;
	private static LuaFileOverlay lua, datapack;
	private static LuaResourcePackToolOverlay resources;

	static void tick(Minecraft client) {
		if (stage == 3 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				client.options.guiScale().set(1); client.resizeGui();
				ide = new LuaIdeScreen(client.screen, ""); client.setScreen(ide);
				set(ide, "sourceLoaded", true); set(ide, "manualOpen", false); set(ide, "manualProgress", 0F); set(ide, "firstManualPending", false);
				lua = (LuaFileOverlay) value(ide, "fileOverlay"); datapack = (LuaFileOverlay) value(ide, "datapackOverlay");
				resources = (LuaResourcePackToolOverlay) value(ide, "resourceDirectoryOverlay");
				for (float scale : new float[]{1F, 1.5F}) for (boolean locked : new boolean[]{false, true}) for (DockSide side : DockSide.values()) {
					set(ide, "interfaceScale", scale); set(ide, "layoutLocked", locked);
					fileCase(lua, LuaFileOverlay.Mode.LUA, side);
					fileCase(datapack, LuaFileOverlay.Mode.DATAPACK, side);
					resourceCase(side);
				}
				set(ide, "interfaceScale", 1F); setupResource(DockSide.FLOATING); set(resources, "quickAddOpen", true);
				set(resources, "choosingOgg", false);
				set(resources, "visible", List.of(new LuaResourcePackWorkspace.Entry("demo", true, 0),
						new LuaResourcePackWorkspace.Entry("demo/assets", true, 1), new LuaResourcePackWorkspace.Entry("demo/assets/demo", true, 2)));
				until = System.nanoTime() + 600_000_000L; stage = 1; return;
			}
			if (stage == 1) {
				if (System.nanoTime() < until) return;
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "directory-overflow-popup.png", client.getMainRenderTarget(), 1, ignored -> { });
				stage = 2; until = System.nanoTime() + 600_000_000L; return;
			}
			if (stage == 2 && System.nanoTime() >= until) {
				System.out.println("Directory context real IDE probe passed: " + checks + " checks; floating/left/right/bottom, locked/unlocked, 100%/150%, child and primary menus outside frames, overlap priority, scrolling and dismissal");
				stage = 3; client.stop();
			}
		} catch (Exception | AssertionError error) { stage = 3; client.stop(); throw new IllegalStateException("Directory context real IDE probe failed", error); }
	}

	private static void closeMenus() throws Exception {
		lua.close(); datapack.close(); resources.close();
		set(lua, "progress", 0F); set(datapack, "progress", 0F); set(resources, "progress", 0F);
	}
	private static void fileCase(LuaFileOverlay overlay, LuaFileOverlay.Mode mode, DockSide side) throws Exception {
		closeMenus();
		overlay.setDockSide(DockSide.FLOATING); overlay.restoreGeometry(new WindowGeometry(12, 75, 180, 280)); overlay.setDockSide(side);
		set(overlay, "open", true); set(overlay, "progress", 1F);
		var entry = new ScriptManager.ScriptEntry(mode == LuaFileOverlay.Mode.LUA ? "tools" : "demo/data/demo", true);
		set(overlay, "contextEntry", entry); set(overlay, "contextOpen", true); set(overlay, "contextX", 330); set(overlay, "contextY", 110);
		// A different floating tool overlaps the menu: only the menu may receive this click.
		resources.setDockSide(DockSide.FLOATING); resources.restoreGeometry(new WindowGeometry(310, 90, 200, 300));
		set(resources, "open", true); set(resources, "progress", 1F); set(resources, "path", "unchanged");
		List<?> actions = (List<?>) invoke(overlay, "contextActions", new Class<?>[]{ScriptManager.ScriptEntry.class}, entry);
		int row = index(actions, mode == LuaFileOverlay.Mode.LUA ? "NEW_SCRIPT" : "NEW_DIRECTORY"), y = 110 + 3 + row * 22 + 8;
		require(ide.mouseScrolled(physical(340), physical(y), 0, -1), "Overflow menu scroll priority");
		require((boolean) value(overlay, "contextOpen"), "Scroll dismissed file context unexpectedly");
		require(ide.mouseClicked(mouseAt(340, y, 0), false), "IDE file popup click handled " + mode + "/" + side);
		require(value(overlay, "name").equals(entry.path() + "/") && !(boolean) value(overlay, "contextOpen"), "File popup action dispatched through IDE");
		require(value(resources, "path").equals("unchanged"), "Background resource window intercepted file popup");
	}
	private static void setupResource(DockSide side) throws Exception {
		closeMenus(); resources.setDockSide(DockSide.FLOATING);
		resources.restoreGeometry(new WindowGeometry(12, 90, 170, 280)); resources.setDockSide(side);
		set(resources, "open", true); set(resources, "progress", 1F);
		set(resources, "contextEntry", new LuaResourcePackWorkspace.Entry("demo/assets/demo", true, 2));
		set(resources, "contextX", 300); set(resources, "contextY", 100); set(resources, "contextOpen", true);
	}
	private static void resourceCase(DockSide side) throws Exception {
		setupResource(side);
		List<?> actions = (List<?>) invoke(resources, "contextActions");
		int y = 100 + 3 + index(actions, "NEW_DIRECTORY") * 22 + 8;
		require(ide.mouseClicked(mouseAt(310, y, 0), false), "IDE primary popup overflow " + side);
		require(value(resources, "pendingCreate").toString().equals("DIRECTORY"), "Primary resource action dispatched");
		setupResource(side); actions = (List<?>) invoke(resources, "contextActions");
		y = 100 + 3 + index(actions, "QUICK_ADD") * 22 + 8;
		require(ide.mouseClicked(mouseAt(310, y, 0), false) && (boolean) value(resources, "quickAddOpen"), "IDE child submenu opens");
		int childX = (int) invoke(resources, "quickAddX"), childY = (int) invoke(resources, "quickAddY");
		require(resources.contains(childX + 10, childY + 10) && childX > 182, "Submenu is outside parent frame");
		for (int childRow = 0; childRow < 2; childRow++) {
			set(resources, "quickAddOpen", true); set(resources, "contextOpen", true);
			set(resources, "choosingOgg", true); // Dispatch only; never open a native chooser in this automated check.
			require(resources.contains(childX + 10, childY + 10 + childRow * 22), "IDE child row overflow owns input");
			require(ide.mouseClicked(mouseAt(childX + 10, childY + 10 + childRow * 22, 0), false), "IDE OGG/PNG child submenu click handled");
			require(!(boolean) value(resources, "quickAddOpen") && !(boolean) value(resources, "contextOpen"), "IDE dispatched actual child action");
		}
		set(resources, "choosingOgg", false); set(resources, "contextOpen", true); set(resources, "quickAddOpen", true);
		ide.mouseClicked(mouseAt(700, 420, 0), false);
		require(!(boolean) value(resources, "quickAddOpen") && !(boolean) value(resources, "contextOpen"), "Outside click dismisses both levels");
	}
	private static double physical(double logical) throws Exception { return logical * (float) value(ide, "interfaceScale"); }
	private static MouseButtonEvent mouseAt(double x, double y, int button) throws Exception { return mouse(physical(x), physical(y), button); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
