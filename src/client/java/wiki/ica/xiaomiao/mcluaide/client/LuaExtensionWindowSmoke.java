package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless regression checks for the public registry and host-managed frame behavior. */
public final class LuaExtensionWindowSmoke {
	private static final class Content implements McLuaIdeWindowApi.Content {
		int opened, closed, disposed, clicked, dragged, released, focused;
		double clickX, clickY;
		public void render(GuiGraphicsExtractor graphics, int x, int y) { }
		public void opened() { opened++; }
		public void closed() { closed++; }
		public void disposed() { disposed++; }
		public void focusChanged(boolean value) { focused += value ? 1 : -1; }
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			clicked++; clickX = event.x(); clickY = event.y(); return true;
		}
		public boolean mouseDragged(MouseButtonEvent event, double x, double y) { dragged++; return true; }
		public boolean mouseReleased(MouseButtonEvent event) { released++; return true; }
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		AtomicInteger factories = new AtomicInteger();
		Content content = new Content();
		var definition = McLuaIdeWindowApi.window("test_mod", "tools/demo")
				.title(Component.literal("测试窗口")).size(320, 210).minimumSize(150, 100)
				.content(context -> { factories.incrementAndGet(); return content; }).build();
		McLuaIdeWindowApi.register(definition);
		require(McLuaIdeWindowApi.definitions().contains(definition), "Registry snapshot missing window");
		expectInvalid(() -> McLuaIdeWindowApi.register(definition));
		expectInvalid(() -> McLuaIdeWindowApi.window("INVALID", "window"));
		expectInvalid(() -> McLuaIdeWindowApi.window("test_mod", "bad:id").content(c -> content).build());
		expectInvalid(() -> McLuaIdeWindowApi.window("test_mod", "size").size(10, 20).content(c -> content).build());
		McLuaIdeWindowApi.window("other_mod", "tools/demo").content(c -> content).register();
		require(McLuaIdeWindowApi.definitions().size() == 2, "Namespaced window IDs collided");

		LuaExtensionWindow window = new LuaExtensionWindow(null, definition, null);
		window.resize(1000, 700);
		Properties initial = new Properties();
		initial.setProperty(definition.id() + ".x", "50");
		initial.setProperty(definition.id() + ".y", "40");
		window.loadLayout(initial);
		require(factories.get() == 0, "Hidden window instantiated content eagerly");
		window.open(); window.open();
		require(factories.get() == 1 && content.opened == 1, "Open must be idempotent");
		for (int i = 0; i < 30; i++) window.tick();
		window.mouseClicked(mouse(80, 84), false);
		require(content.clickX == 24 && content.clickY == 12, "Mouse coordinates are not content-local");
		window.mouseDragged(mouse(90, 90), 10, 6);
		window.mouseReleased(mouse(90, 90));
		require(content.clicked == 1 && content.dragged == 1 && content.released == 1, "Content mouse capture lost");
		window.mouseClicked(mouse(50, 120), false);
		window.mouseDragged(mouse(70, 120), 20, 0);
		window.mouseReleased(mouse(70, 120));
		Properties resized = new Properties(); window.saveLayout(resized);
		require(resized.getProperty(definition.id() + ".width").equals("300"), "Floating resize failed");
		require(window.hitFrame(69, 120), "Resize margin is not clickable");
		window.mouseClicked(mouse(120, 50), false);
		window.mouseDragged(mouse(170, 80), 50, 30);
		window.mouseReleased(mouse(170, 80));
		window.setDockSide(DockSide.LEFT);
		window.setDockBounds(8, 68, 260, 420);
		for (int i = 0; i < 30; i++) window.tick();
		window.setLayoutLocked(true);
		window.mouseClicked(mouse(60, 80), false);
		require(!window.isDragging(), "Locked docked title can still move");
		window.mouseReleased(mouse(60, 80));
		Properties docked = new Properties(); window.saveLayout(docked);
		require(docked.getProperty(definition.id() + ".width").equals("300"), "Dock overwrote floating dimensions");
		LuaExtensionWindow restored = new LuaExtensionWindow(null, definition, null);
		restored.resize(1000, 700); restored.loadLayout(docked);
		require(restored.isOpen() && restored.dockSide() == DockSide.LEFT, "Visible/docked state not restored");
		window.setLayoutLocked(false);
		window.mouseClicked(mouse(60, 80), false);
		window.mouseDragged(mouse(420, 140), 360, 60);
		window.mouseReleased(mouse(420, 140));
		require(window.dockSide() == DockSide.FLOATING, "Dragging did not undock");
		window.close(); window.close();
		require(content.closed == 1 && !window.hasFocus(), "Close did not release focus exactly once");
		window.dispose(); restored.dispose();
		require(content.disposed == 2, "Dispose not delivered for all content instances");
		System.out.println("Third-party IDE window smoke passed");
	}
	private static MouseButtonEvent mouse(double x, double y) { return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)); }
	private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
	private static void expectInvalid(Runnable action) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError("Invalid/duplicate definition accepted");
	}
}
