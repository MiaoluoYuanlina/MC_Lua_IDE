package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/** Opt-in real widget/settings regression check, isolated from user config and saves. */
final class LuaPersonalizationProbe {
	private static int stage;
	private static long started, until;
	private static CompletableFuture<Void> reload;
	private static LuaIdeScreen ide;
	private static LuaPersonalizationScreen panel;

	static void tick(Minecraft client) {
		if (stage == 5 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime();
				client.getLanguageManager().setSelected("zh_cn"); client.options.languageCode = "zh_cn";
				client.options.guiScale().set(1); client.resizeGui();
				reload = client.reloadResourcePacks(); stage = 1; return;
			}
			if (System.nanoTime() - started > 40_000_000_000L) throw new AssertionError("Probe timeout at " + stage);
			if (stage == 1) {
				if (!reload.isDone()) return; reload.join();
				verifySettings(client.gameDirectory.toPath().resolve("test-settings.properties"));
				ide = new LuaIdeScreen(client.screen, ""); client.setScreen(ide);
				set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
				verifyScrolling(1F);
				set(ide, "pendingInterfaceScale", 1.25F); ide.tick(); verifyScrolling(1.25F);
				set(ide, "pendingInterfaceScale", 1F); ide.tick();
				panel = new LuaPersonalizationScreen(ide, new LuaPersonalizationSettings()); client.setScreen(panel);
				until = System.nanoTime() + 600_000_000L; stage = 2; return;
			}
			if (System.nanoTime() < until) return;
			if (stage == 2) {
				EditBox input = (EditBox) value(panel, "wheelSensitivityInput");
				require(input.visible && input.getWidth() > 30, "Sensitivity input not visible/usable");
				click(panel, input.getX() + 4, input.getY() + 4);
				panel.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, 2));
				"3.25".codePoints().forEach(point -> panel.charTyped(new CharacterEvent(point)));
				require(input.getValue().equals("3.25"), "Custom typing/select-all failed");
				close(((LuaPersonalizationSettings) value(panel, "draft")).codeWheelSensitivity, 3.25);
				clickWrapToggle();
				require(!((LuaPersonalizationSettings) value(panel, "draft")).codeWordWrap, "Wrap toggle mouse click failed");
				input.setValue("NaN"); clickButton(panel, "saveButton");
				require(client.screen == panel, "Invalid value was saved");
				input.setValue("3.25"); panel.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				clickButton(panel, "saveButton"); require(client.screen == ide, "Save did not apply/close");
				close(((LuaPersonalizationSettings) value(ide, "personalization")).codeWheelSensitivity, 3.25);
				close(LuaPersonalizationSettings.load().codeWheelSensitivity, 3.25);
				require(!((LuaPersonalizationSettings) value(ide, "personalization")).codeWordWrap
						&& !LuaPersonalizationSettings.load().codeWordWrap, "Wrap toggle save/apply/reload failed");
				panel = new LuaPersonalizationScreen(ide, LuaPersonalizationSettings.load());
				client.setScreen(panel);
				// Exercise the same compact layout used by a narrow docked settings frame.
				var bounds = panel.getClass().getDeclaredMethod("applyBounds", int.class, int.class, int.class, int.class);
				bounds.setAccessible(true); bounds.invoke(panel, 15, 15, 360, 240);
				until = System.nanoTime() + 600_000_000L; stage = 3; return;
			}
			if (stage == 3) {
				EditBox input = (EditBox) value(panel, "wheelSensitivityInput");
				require(input.visible && input.getWidth() >= 60, "Narrow layout clipped input");
				click(panel, input.getRight() + 9, input.getY() + 9);
				close(((LuaPersonalizationSettings) value(panel, "draft")).codeWheelSensitivity, 3.35);
				click(panel, input.getX() - 15, input.getY() + 9);
				close(((LuaPersonalizationSettings) value(panel, "draft")).codeWheelSensitivity, 3.25);
				input.setValue("8");
				// Settings-page scrolling must retain its own fixed rate, not multiply by 8.
				panel.mouseScrolled((int) value(panel, "contentLeft") + 20, (int) value(panel, "viewTop") + 10, 0, -1);
				require((int) value(panel, "scroll") == 32, "Settings page inherited code sensitivity");
				panel.mouseScrolled((int) value(panel, "contentLeft") + 20, (int) value(panel, "viewTop") + 10, 0, 1);
				until = System.nanoTime() + 600_000_000L; stage = 4; return;
			}
			if (stage == 4) {
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "wheel-sensitivity.png",
						client.getMainRenderTarget(), 1, ignored -> { });
				clickButton(panel, "cancelButton"); require(client.screen == ide, "Discard did not close");
				close(LuaPersonalizationSettings.load().codeWheelSensitivity, 3.25);
				System.out.println("Personalization real UI probe passed: wrap toggle/save/reload, wheel multipliers, fractional deltas, gutter, UI scaling, bounds, custom input, validation, +/- controls, compact layout, save/reload, discard and independent settings scrolling");
				stage = 5; client.stop();
			}
		} catch (Exception | AssertionError error) {
			stage = 5; client.stop(); throw new IllegalStateException("Personalization probe failed", error);
		}
	}

	private static void clickWrapToggle() throws Exception {
		EditBox input = (EditBox) value(panel, "wheelSensitivityInput");
		int right = (int) value(panel, "contentRight");
		boolean narrow = right - (int) value(panel, "contentLeft") - 22 < 350;
		int y = input.getY() - (narrow ? 43 : 14) + (narrow ? 85 : 65) + 28;
		click(panel, right - 35, y);
	}

	private static void verifySettings(Path file) throws Exception {
		LuaPersonalizationSettings original = new LuaPersonalizationSettings();
		require(original.codeWordWrap, "Wrap defaults to enabled");
		original.codeWordWrap = false;
		close(original.codeWheelSensitivity(), 1);
		original.codeWheelSensitivity = 2.75F; original.save(file);
		close(LuaPersonalizationSettings.load(file).codeWheelSensitivity(), 2.75);
		close(original.copy().codeWheelSensitivity, 2.75);
		require(!LuaPersonalizationSettings.load(file).codeWordWrap && !original.copy().codeWordWrap, "Wrap preference save/reload/copy");
		original.copy().codeWheelSensitivity = 8; close(original.codeWheelSensitivity, 2.75);
		Properties properties = new Properties(); LuaIdeSession.writeProperties(file, properties);
		require(LuaPersonalizationSettings.load(file).codeWordWrap, "Old settings preserve enabled wrap");
		close(LuaPersonalizationSettings.load(file).codeWheelSensitivity(), 1);
		for (String invalid : List.of("NaN", "Infinity", "-Infinity", "abc", "")) {
			properties.setProperty("codeWheelSensitivity", invalid); LuaIdeSession.writeProperties(file, properties);
			close(LuaPersonalizationSettings.load(file).codeWheelSensitivity(), 1);
			require(LuaPersonalizationSettings.parseCodeWheelSensitivity(invalid) == null, "Invalid input accepted: " + invalid);
		}
		for (String outOfRange : List.of("0", "-4", "99")) {
			properties.setProperty("codeWheelSensitivity", outOfRange); LuaIdeSession.writeProperties(file, properties);
			close(LuaPersonalizationSettings.load(file).codeWheelSensitivity(), outOfRange.equals("99") ? 20 : .1);
			require(LuaPersonalizationSettings.parseCodeWheelSensitivity(outOfRange) == null, "Out of range input accepted");
		}
		close(LuaPersonalizationSettings.parseCodeWheelSensitivity(" 0.25 "), .25);
	}

	private static void verifyScrolling(float scale) throws Exception {
		MultiLineEditBox editor = (MultiLineEditBox) value(ide, "editor");
		editor.visible = editor.active = true; editor.setValue("local value = 1\n".repeat(400));
		LuaPersonalizationSettings settings = (LuaPersonalizationSettings) value(ide, "personalization");
		double x = editor.getX() + 12, y = editor.getY() + 12;
		editor.setScrollAmount(100); editor.mouseScrolled(x, y, 0, -1);
		double base = editor.scrollAmount() - 100; require(base > 0, "Baseline wheel scroll failed");
		for (float multiplier : new float[]{.1F, .5F, 1F, 2F, 20F}) {
			settings.codeWheelSensitivity = multiplier; editor.setScrollAmount(100);
			ide.mouseScrolled(x * scale, y * scale, 0, -1);
			close(editor.scrollAmount() - 100, base * multiplier);
			editor.setScrollAmount(100); ide.mouseScrolled(x * scale, y * scale, 0, -.125);
			close(editor.scrollAmount() - 100, base * multiplier * .125);
			editor.setScrollAmount(100); ide.mouseScrolled((editor.getX() - 10) * scale, y * scale, 0, 1);
			close(editor.scrollAmount() - 100, -base * multiplier);
		}
		editor.setScrollAmount(100); ide.mouseScrolled(x * scale, y * scale, 0, 100000); close(editor.scrollAmount(), 0);
		settings.codeWheelSensitivity = 1F;
	}

	private static Object value(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
	private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
	private static void click(LuaPersonalizationScreen screen, double x, double y) { screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false); }
	private static void clickButton(LuaPersonalizationScreen screen, String name) throws Exception { Button button = (Button) value(screen, name); click(screen, button.getX() + 5, button.getY() + 5); }
	private static void close(double actual, double expected) { require(Math.abs(actual - expected) < .0001, "Expected " + expected + ", got " + actual); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
