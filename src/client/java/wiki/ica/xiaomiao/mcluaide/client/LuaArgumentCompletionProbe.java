package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Opt-in isolated real editor input regression; no world, server script or existing file is opened. */
final class LuaArgumentCompletionProbe {
	private static int stage;
	private static LuaIdeScreen ide;
	private static MultiLineEditBox editor;
	private static MultilineTextField text;
	private static CompletableFuture<Void> languageReload;
	private static int renderedSelectionTicks;

	static void tick(Minecraft client) {
		if (stage == 4 || client.getOverlay() != null) return;
		try {
			if (stage == 3) {
				if (++renderedSelectionTicks < 10) return;
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "selection-leading-blank.png", client.getMainRenderTarget(), 1, ignored -> { });
				require(text.hasSelection() && suggestions().isEmpty(), "Selection changed while rendering");
				System.out.println("Selection real editor regression passed: leading blank, 2201-character source, backwards drag, Ctrl+A, Shift arrows/Home, comment, paste formatting, newline and folded-source copy");
				stage = 4;
				client.stop();
				return;
			}
			if (stage == 0) {
				client.getLanguageManager().setSelected("zh_cn");
				client.options.languageCode = "zh_cn";
				languageReload = client.reloadResourcePacks();
				stage = 1;
				return;
			}
			if (stage == 1) {
				if (!languageReload.isDone()) return;
				languageReload.join();
				LuaArgumentCompletionSmoke.verify();
				ide = new LuaIdeScreen(client.screen, "");
				client.setScreen(ide);
				set("sourceLoaded", true);
				set("manualOpen", false);
				set("manualProgress", 0F);
				editor = (MultiLineEditBox) get("editor");
				text = (MultilineTextField) invoke("textField");
				editor.active = true;
				ide.setFocused(editor);
				prepare("MC.Item.name(|)");
				stage = 2;
				return;
			}
			verifySelectionRegression();
			@SuppressWarnings("unchecked") var catalogs = (java.util.Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>>) get("argumentCatalogs");
			catalogs.put(LuaInsertCatalog.Kind.DIMENSION, LuaArgumentCompletionSmoke.DIMENSIONS);
			var rules = LuaInsertCatalog.load(LuaInsertCatalog.Kind.GAME_RULE, client);
			var keepInventory = rules.stream().filter(entry -> entry.value().equals("minecraft:keep_inventory")).findFirst().orElseThrow();
			require(keepInventory.name().contains("物品"), "Runtime Chinese game-rule translation missing: " + keepInventory.name());
			prepare("MC.GameRule.set('物品|', true)");
			select("minecraft:keep_inventory");
			String label = (String) invoke("suggestionLabel", new Class<?>[]{String.class}, "minecraft:keep_inventory");
			require(label.contains(keepInventory.name()), "Game-rule completion label lost translation: " + label);
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.GameRule.set('minecraft:keep_inventory', true)"), "Game-rule Chinese Tab completion failed: " + text.value());
			prepare("MC.GameRule.set('" + keepInventory.initials() + "|', false)");
			select("minecraft:keep_inventory");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.GameRule.set('minecraft:keep_inventory', false)"), "Game-rule Chinese initials Tab completion failed");
			prepare("MC.GameRule.set(mi|, 3)");
			select("\"minecraft:random_tick_speed\"");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.GameRule.set(\"minecraft:random_tick_speed\", 3)"), "Game-rule bare completion lost quotes");
			System.out.println("Game-rule real editor probe passed: " + rules.size() + " registered rules, actual Chinese labels, initials, quoted/bare IDs and Tab insertion");
			prepare("MC.Player.teleport(uuid, 0, 80, 0, 'xj|')");
			select("minecraft:the_nether");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.Player.teleport(uuid, 0, 80, 0, 'minecraft:the_nether')"), "Dimension quoted Tab completion failed: " + text.value());
			prepare("MC.World.gameTime(test:moon/|)");
			select("\"test:moon/sub\"");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.World.gameTime(\"test:moon/sub\")"), "Mod dimension bare Tab completion lost namespace/path: " + text.value());
			prepare("MC.Block.get(0, 64, 0, |)");
			require(suggestions().contains("\"minecraft:overworld\"") && suggestions().contains("nil"), "Empty optional dimension lacks IDs/nil");
			prepare("MC.Item.name(|)");
			require(!suggestions().isEmpty(), "Empty ID argument had no popup");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
			require((int) get("selectedSuggestion") == 1, "Down arrow did not select next candidate");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_UP, 0, 0));
			require((int) get("selectedSuggestion") == 0, "Up arrow did not select previous candidate");
			select("\"minecraft:diamond\"");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.Item.name(\"minecraft:diamond\")"), "Bare ID completion did not add quotes: " + text.value());
			prepare("MC.Item.name('dia|mond')");
			select("minecraft:diamond");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.Item.name('minecraft:diamond')"), "Quoted completion damaged suffix/quote: " + text.value());
			prepare("MC.Item.name(\"dia|)");
			select("minecraft:diamond\"");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.Item.name(\"minecraft:diamond\")"), "Unfinished string did not close correctly: " + text.value());
			prepare("local count = 16\nlocal text = 'wrong'\nMC.Item.give(uuid, 'diamond', co|)");
			require(suggestions().contains("count") && !suggestions().contains("text"), "Typed variables did not filter correctly");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().endsWith("MC.Item.give(uuid, 'diamond', count)"), "Variable completion added quotes");
			prepare("MC.Item.give(MC.Player.uu|)");
			select("MC.Player.uuid");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
			require(text.value().equals("MC.Item.give(MC.Player.uuid())"), "Nested function completion lost parentheses: " + text.value());
			prepare("MC.Item.name(\"zs|\")");
			require(suggestions().contains("minecraft:diamond"), "Chinese initials did not find diamond");
			select("minecraft:diamond");
			// Ensure the selected row is visible, then dispatch a real mouse click at that row.
			int row = (int) get("selectedSuggestion");
			set("animatedSuggestionScroll", (double) row);
			Object popup = invoke("suggestionPopup", new Class<?>[]{MultilineTextField.class}, text);
			int x = component(popup, "x") + 10;
			int y = component(popup, "y") + 6;
			float scale = (float) get("interfaceScale");
			ide.mouseClicked(new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(0, 0)), false);
			require(text.value().equals("MC.Item.name(\"minecraft:diamond\")"), "Mouse candidate click did not complete: " + text.value());
			prepare("MC.Item.name(\"di|\")");
			require(suggestions().contains("minecraft:diamond"), "Item catalog missing diamond");
			prepare("MC.Block.name(\"di|\")");
			require(!suggestions().contains("minecraft:diamond") && suggestions().contains("minecraft:diamond_block"), "Argument kind did not switch");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
			invoke("refreshSuggestions");
			require(suggestions().isEmpty(), "Dismissed popup reopened without a cursor/text change");
			System.out.println("Argument completion real editor probe passed: parameter kinds, variable types, quoted/bare IDs, Tab, arrows, mouse and dismiss");
			prepare("\nlocal function example(value)\n    --#name=选中与折叠验证\n    return value\nend\n\nMC.Pl|");
			require(LuaFunctionFolding.toggle(text, 2), "Function fold setup failed");
			text.seekCursor(Whence.ABSOLUTE, text.value().length());
			invoke("refreshSuggestions");
			ide.keyPressed(new KeyEvent(InputConstants.KEY_HOME, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL | org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT));
			require(text.getSelectedText().equals(text.value()) && suggestions().isEmpty(), "Backwards selection must include folded source and dismiss completion");
			stage = 3;
		} catch (Exception | AssertionError failure) { throw new IllegalStateException("Argument completion real editor probe failed", failure); }
	}

	private static void verifySelectionRegression() throws Exception {
		// Matches the crash report's source length and invalid substring range [1, 0).
		String marked = "\nMC.Pl|" + " ".repeat(2195);
		prepare(marked);
		require(text.value().length() == 2201 && !suggestions().isEmpty(), "Reported-crash setup did not open completion");
		text.seekCursor(Whence.ABSOLUTE, 0);
		invoke("suggestionPopup", new Class<?>[]{MultilineTextField.class}, text);
		require((int) invoke("currentColumn", new Class<?>[]{MultilineTextField.class}, text) == 1, "Leading empty line must start at column 1");
		prepare(marked);
		text.setSelecting(true);
		text.seekCursor(Whence.ABSOLUTE, 0);
		require(invoke("suggestionPopup", new Class<?>[]{MultilineTextField.class}, text) == null && suggestions().isEmpty(), "Backwards selection left stale popup visible");
		invoke("refreshSuggestions");
		require(suggestions().isEmpty(), "Completion reopened over a selection");
		String selected = text.getSelectedText();
		ide.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT));
		require(text.hasSelection() && text.cursor() == 1 && selected.endsWith(text.getSelectedText()), "Shift+Right must extend the existing selection");
		prepare(marked);
		ide.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
		require(text.getSelectedText().equals(text.value()) && suggestions().isEmpty(), "Ctrl+A must select all and dismiss completion");
		prepare(marked);
		ide.keyPressed(new KeyEvent(InputConstants.KEY_UP, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT));
		require(text.hasSelection() && suggestions().isEmpty(), "Shift+Up was intercepted by completion navigation");
		prepare("|\nprint('hello')");
		ide.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
		require(text.value().equals("\n\nprint('hello')"), "Enter at a leading blank line changed existing source");
		require(LuaIndentation.formatPaste("print(1)\nprint(2)", "\nprint(3)", 0).equals("print(1)\nprint(2)"), "Paste formatting at offset 0 failed");
		prepare("\nprint('hello')|");
		text.setSelecting(true);
		text.seekCursor(Whence.ABSOLUTE, 0);
		ide.keyPressed(new KeyEvent(InputConstants.KEY_SLASH, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
		require(text.value().equals("\n-- print('hello')"), "Commenting a selection beginning at offset 0 failed: " + text.value());
		ide.keyPressed(new KeyEvent(InputConstants.KEY_SLASH, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
		require(text.value().equals("\nprint('hello')"), "Removing comments changed the leading empty line");
		require(McFunctionCursorContext.at("\ngive @p minecraft:stone", 0) == null, "Command completion failed at a leading blank line");
		prepare(marked);
		int height = (int) invoke("scaledCodeLineHeight");
		float scale = (float) get("interfaceScale");
		var codeFont = (net.minecraft.client.gui.Font) get("codeFont");
		float cell = (float) invoke("characterWidthScale");
		double startX = editor.getX() + 4 + codeFont.width("MC.Pl") * cell;
		double startY = editor.getY() + 4 + height * 1.5;
		boolean clicked = ide.mouseClicked(new MouseButtonEvent(startX * scale, startY * scale, new MouseButtonInfo(0, 0)), false);
		// The 2201-character wrapped row can scroll the initial blank line out of view on click.
		editor.setScrollAmount(0);
		double endX = editor.getX() + 4, endY = editor.getY() + 4 + height * .5;
		boolean dragged = ide.mouseDragged(new MouseButtonEvent(endX * scale, endY * scale, new MouseButtonInfo(0, 0)),
				(endX - startX) * scale, (endY - startY) * scale);
		require(text.hasSelection() && text.cursor() == 0, "Actual backwards mouse drag did not reach file start: click=" + clicked + " drag=" + dragged + " cursor=" + text.cursor() + " selection=" + text.hasSelection() + " focused=" + editor.isFocused() + " scroll=" + editor.scrollAmount());
		require(invoke("suggestionPopup", new Class<?>[]{MultilineTextField.class}, text) == null, "Drag selection must hide completion");
		ide.mouseReleased(new MouseButtonEvent(endX * scale, endY * scale, new MouseButtonInfo(0, 0)));
		require(text.value().equals(marked.replace("|", "")), "Selection mutated raw source");
	}

	private static void prepare(String marked) throws Exception {
		editor.active = true;
		ide.setFocused(editor);
		editor.setValue(marked.replace("|", ""));
		text.setSelecting(false);
		text.seekCursor(Whence.ABSOLUTE, marked.indexOf('|'));
		invoke("refreshSuggestions");
	}

	private static void select(String value) throws Exception {
		int index = suggestions().indexOf(value);
		require(index >= 0, "Missing real editor candidate " + value + ": " + suggestions());
		set("selectedSuggestion", index);
	}

	@SuppressWarnings("unchecked") private static List<String> suggestions() throws Exception { return (List<String>) get("suggestions"); }
	private static Object get(String name) throws Exception { Field field = LuaIdeScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(ide); }
	private static void set(String name, Object value) throws Exception { Field field = LuaIdeScreen.class.getDeclaredField(name); field.setAccessible(true); field.set(ide, value); }
	private static Object invoke(String name) throws Exception { return invoke(name, new Class<?>[0]); }
	private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception { Method method = LuaIdeScreen.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(ide, args); }
	private static int component(Object record, String name) throws Exception { Method method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return (int) method.invoke(record); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
