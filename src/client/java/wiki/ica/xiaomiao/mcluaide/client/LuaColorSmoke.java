package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;

final class LuaColorSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		LuaColorModel model = new LuaColorModel();
		java.util.Random random = new java.util.Random(41);
		for (int i = 0; i < 10_000; i++) {
			int rgb = random.nextInt(1 << 24); model.rgb(rgb); check(model.rgb() == rgb, "RGB/HSV round trip lost precision");
			int channel = i % 3, value = random.nextInt(256), before = model.rgb(); model.channel(channel, value);
			int shift = 16 - channel * 8;
			check(model.rgb() == ((before & ~(255 << shift)) | value << shift), "Channel update changed other channels");
		}
		model.rgb(0xFF8000); check(model.text().equals("#FF8000"), "Hex hash format");
		model.hexNumber = true; check(model.text().equals("0xFF8000") && !model.stringOutput(), "Hex numeric format");
		model.format = LuaColorModel.Format.BYTE; check(model.text().equals("255, 128, 0"), "Byte format");
		model.format = LuaColorModel.Format.UNIT; check(model.text().equals("1.00, 0.50, 0.00"), "Unit format");
		for (int rgb : new int[]{0, 0xFFFFFF}) {
			model.rgb(rgb); check(model.text().equals(rgb == 0 ? "0.00, 0.00, 0.00" : "1.00, 1.00, 1.00"), "Unit boundary");
		}
		String[] copied = {null}, inserted = {null};
		McLuaIdeWindowApi.Context context = new McLuaIdeWindowApi.Context() {
			public net.minecraft.client.Minecraft minecraft() { return null; }
			public net.minecraft.client.gui.Font font() { return null; }
			public String documentName() { return "fixture"; } public String source() { return ""; } public String selectedText() { return ""; }
			public boolean insertText(String value) { inserted[0] = value; return true; }
			public void copyText(String value) { copied[0] = value; } public void close() { }
		};
		LuaColorWindow window = new LuaColorWindow(context, value -> context.insertText(value.text()));
		window.color.rgb(0xFF8000);
		key(window, InputConstants.KEY_TAB, 0); check(focus(window).equals("HUE"), "Tab to hue");
		key(window, InputConstants.KEY_DOWN, 0); check(window.color.rgb() != 0xFF8000, "Hue arrow did not move");
		key(window, InputConstants.KEY_TAB, 0); check(focus(window).equals("RED"), "Tab to red");
		window.color.rgb(0); key(window, InputConstants.KEY_RIGHT, 0); check(window.color.channel(0) == 1, "RGB arrow step");
		key(window, InputConstants.KEY_RIGHT, 1); check(window.color.channel(0) == 11, "Shift arrow step");
		key(window, InputConstants.KEY_TAB, 1); check(focus(window).equals("HUE"), "Reverse tab");
		window.resize(220, 188);
		for (int i = 0; i < 7; i++) key(window, InputConstants.KEY_TAB, 0);
		check(focus(window).equals("COPY") && (int) get(window, "scroll") > 0, "Compact focus not scrolled into view");
		key(window, InputConstants.KEY_RETURN, 0); check(inserted[0].equals(window.color.text()) && copied[0] == null, "Enter must always insert");
		key(window, InputConstants.KEY_SPACE, 0); check(copied[0].equals(window.color.text()), "Copy via focused button");
		key(window, InputConstants.KEY_TAB, 0); check(focus(window).equals("SQUARE"), "Focus did not wrap");
		window.resize(468, 438);
		var click = new MouseButtonEvent(1, 20, new MouseButtonInfo(0, 0)); window.mouseClicked(click, false);
		window.mouseDragged(new MouseButtonEvent(-100, 10000, new MouseButtonInfo(0, 0)), 0, 0);
		check(window.color.rgb() == 0, "Palette drag did not clamp"); window.mouseReleased(click);
		window.mouseClicked(new MouseButtonEvent(225, 20, new MouseButtonInfo(0, 0)), false); window.mouseReleased(click);
		for (int right : new int[]{220, 380, 800}) {
			check(IdeWindowCloseButton.contains(right - 16, 15, right, 5), "Close center failed");
			check(!IdeWindowCloseButton.contains(right - 27, 15, right, 5)
					&& !IdeWindowCloseButton.contains(right - 16, 30, right, 5), "Close hitbox leaked outside header");
		}
		System.out.println("Color picker smoke passed: " + checks + " checks; exact RGB gamut, formats, keyboard/Tab, scrolling, mouse drag and close hitboxes");
	}
	private static void key(LuaColorWindow window, int key, int modifiers) { window.keyPressed(new KeyEvent(key, 0, modifiers)); }
	private static Object get(Object object, String name) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
	private static String focus(LuaColorWindow window) throws Exception { return get(window, "focus").toString(); }
	private static void check(boolean value, String text) { checks++; if (!value) throw new AssertionError(text); }
}
