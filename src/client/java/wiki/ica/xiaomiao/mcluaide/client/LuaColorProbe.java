package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Native IDE regression at the title screen, with a local throwaway resource JSON document. */
final class LuaColorProbe {
	private static int stage, checks;
	private static long started, until;
	private static LuaIdeScreen ide;
	private static LuaExtensionWindow frame;
	private static LuaColorWindow color;
	private static String originalClipboard;
	private static Object panel;
	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); originalClipboard = client.keyboardHandler.getClipboard();
				require(client.gameDirectory.toPath().toString().contains("color-picker-probe"), "Unsafe probe directory");
				client.options.guiScale().set(1); client.resizeGui();
				String pack = "color_probe_" + System.nanoTime(); LuaResourcePackWorkspace.createPack(pack);
				String path = pack + "/assets/test/colors.json";
				LuaResourcePackWorkspace.createText(path, "{\"color\":\"old\",\"rgb\":[]}");
				ide = new LuaIdeScreen(client.screen, ""); client.setScreen(ide);
				set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
				invoke(ide, "openResourcePackFile", new Class[]{String.class}, path);
				var menu = (java.util.List<?>) get(ide, "viewMenuItems");
				require(menu.stream().anyMatch(value -> ((net.minecraft.client.gui.components.Button) value).getMessage().getString().equals("取色")), "Missing view menu color picker");
				require(ide.setExtensionWindowOpen(LuaColorWindow.ID, true), "Color picker not registered");
				frame = (LuaExtensionWindow) get(ide, "focusedExtensionWindow"); color = (LuaColorWindow) get(frame, "content");
				waitFor(1); return;
			}
			require(System.nanoTime() - started < 60_000_000_000L, "Color probe timeout at " + stage);
			if (System.nanoTime() < until) return;
			if (stage == 1) {
				if (!(boolean) get(ide, "sourceLoaded")) return;
				color.color.rgb(0xFF8000); key(InputConstants.KEY_TAB); key(InputConstants.KEY_DOWN);
				require(color.color.rgb() != 0xFF8000 && get(color, "focus").toString().equals("HUE"), "IDE Tab/hue arrows not routed");
				key(InputConstants.KEY_TAB); int red = color.color.channel(0); key(InputConstants.KEY_LEFT);
				require(color.color.channel(0) == red - 1, "RGB keyboard control failed");
				color.color.rgb(0xFF8000); selectToken("\"old\""); key(InputConstants.KEY_RETURN);
				require(document().get("color").getAsString().equals("#FF8000"), "Hex insertion lost quotes");
				color.color.rgb(0x00FF00); selectToken("#FF8000"); key(InputConstants.KEY_RETURN);
				require(document().get("color").getAsString().equals("#00FF00"), "Hex insertion doubled quotes in existing string");
				color.color.rgb(0xFF8000); key(InputConstants.KEY_TAB); key(InputConstants.KEY_TAB); key(InputConstants.KEY_TAB);
				require(get(color, "focus").toString().equals("FORMAT"), "Tab format not reachable");
				key(InputConstants.KEY_RIGHT); require(color.color.format == LuaColorModel.Format.BYTE, "Format selection failed");
				selectArray(); key(InputConstants.KEY_RETURN);
				var rgb = document().getAsJsonArray("rgb"); require(rgb.size() == 3 && rgb.get(0).getAsInt() == 255 && rgb.get(1).getAsInt() == 128, "Byte output not inserted as three values");
				key(InputConstants.KEY_RIGHT); selectArray(); key(InputConstants.KEY_RETURN);
				rgb = document().getAsJsonArray("rgb"); require(rgb.get(0).getAsDouble() == 1 && rgb.get(1).getAsDouble() == .5 && rgb.get(2).getAsDouble() == 0, "Unit output incorrect");
				// Inspect and resize the same real extension frame, including scrolling through compact controls.
				set(frame, "width", 260); set(frame, "height", 250);
				color.resize(248, 212); for (int i = 0; i < 3; i++) key(InputConstants.KEY_TAB);
				require((int) get(color, "scroll") > 0, "Compact options did not auto-scroll");
				set(frame, "width", 480); set(frame, "height", 440); color.resize(468, 402);
				key(InputConstants.KEY_TAB); key(InputConstants.KEY_TAB); // Wrap back into palette controls.
				waitFor(2); return;
			}
			if (stage == 2) {
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "color-picker.png", client.getMainRenderTarget(), 1, ignored -> { });
				click((int) invoke(frame, "panelX") + (int) get(frame, "width") - 16, (int) get(frame, "y") + 10);
				require(!frame.isOpen() && client.screen == ide, "Color close closed IDE or started dragging");
				invoke(ide, "openPersonalization"); panel = get(ide, "personalizationPanel"); waitFor(3); return;
			}
			if (stage == 3) {
				closePanel("panelRight", "panelTop"); require((boolean) get(panel, "closing") && !(boolean) get(panel, "dragging"), "Personalization cross started dragging");
				waitFor(4); return;
			}
			if (stage == 4) {
				require(get(ide, "personalizationPanel") == null, "Personalization close animation stuck");
				invoke(ide, "openGitScreen"); panel = get(ide, "gitPanel"); waitFor(5); return;
			}
			if (stage == 5) {
				closePanel("right", "top"); require((boolean) get(panel, "closing") && !(boolean) get(panel, "dragging"), "Git cross started dragging"); waitFor(6); return;
			}
			if (stage == 6) {
				require(get(ide, "gitPanel") == null, "Git close animation stuck");
				invoke(ide, "exportCurrentApiDocumentation"); panel = get(ide, "exportPanel"); waitFor(7); return;
			}
			if (stage == 7) {
				closePanel("right", "top"); require((boolean) get(panel, "closing") && !(boolean) get(panel, "dragging"), "Export cross started dragging"); waitFor(8); return;
			}
			if (stage == 8) {
				require(get(ide, "exportPanel") == null, "Export close animation stuck");
				client.keyboardHandler.setClipboard(originalClipboard);
				System.out.println("Color picker native IDE probe passed: " + checks + " checks; menu, focus/arrows/Tab, hex quoting, byte/unit insertion, compact scrolling, top-right close and animations for color/Git/personalization/export");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) {
			int failed = stage; stage = 99; if (originalClipboard != null) client.keyboardHandler.setClipboard(originalClipboard);
			client.stop(); throw new IllegalStateException("Color probe failed at " + failed, error);
		}
	}
	private static void waitFor(int next) { stage = next; until = System.nanoTime() + 1_500_000_000L; }
	private static void closePanel(String right, String top) throws Exception { click((int) get(panel, right) - 16, (int) get(panel, top) + 10); }
	private static void click(double x, double y) { ide.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false); }
	private static void key(int key) { ide.keyPressed(new KeyEvent(key, 0, 0)); }
	private static com.google.gson.JsonObject document() throws Exception { return JsonParser.parseString(((MultiLineEditBox) get(ide, "editor")).getValue()).getAsJsonObject(); }
	private static void selectToken(String token) throws Exception { var text = ((MultiLineEditBox) get(ide, "editor")).getValue(); int start = text.indexOf(token); require(start >= 0, "Missing token " + token); select(start, start + token.length()); }
	private static void selectArray() throws Exception { var text = ((MultiLineEditBox) get(ide, "editor")).getValue(); select(text.indexOf('[') + 1, text.indexOf(']')); }
	private static void select(int start, int end) throws Exception { var field = (net.minecraft.client.gui.components.MultilineTextField) invoke(ide, "textField"); field.setSelecting(false); field.seekCursor(Whence.ABSOLUTE, start); field.setSelecting(true); field.seekCursor(Whence.ABSOLUTE, end); field.setSelecting(false); }
	private static Object get(Object object, String name) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
	private static void set(Object object, String name, Object value) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
	private static Object invoke(Object object, String name, Class<?>[] types, Object... args) throws Exception { var m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(object, args); }
	private static Object invoke(Object object, String name) throws Exception { return invoke(object, name, new Class[0]); }
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
