package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

public final class LuaResourcePackItemImportSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path sandbox = Files.createTempDirectory("mc_lua_item_import_test_");
		try {
			Path root = Files.createDirectory(sandbox.resolve("test"));
			Path source = sandbox.resolve("sample.PNG");
			BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(3, 4, 0x8077AABB); ImageIO.write(image, "PNG", source.toFile());
			var result = LuaResourcePackItemImport.importItem(root, source, "test:shop/gem");
			require(result.id().equals("test:shop/gem"), "Namespaced ID");
			require(result.texturePath().equals("textures/item/shop/gem.png") && result.modelPath().equals("models/item/shop/gem.json")
					&& result.definitionPath().equals("items/shop/gem.json"), "Three destination paths");
			require(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(root.resolve(result.texturePath()))), "PNG bytes/alpha preserved");
			var model = JsonParser.parseString(Files.readString(root.resolve(result.modelPath()))).getAsJsonObject();
			require(model.get("parent").getAsString().equals("minecraft:item/generated")
					&& model.getAsJsonObject("textures").get("layer0").getAsString().equals("test:item/shop/gem"), "Flat item texture model");
			var definition = JsonParser.parseString(Files.readString(root.resolve(result.definitionPath()))).getAsJsonObject().getAsJsonObject("model");
			require(definition.get("type").getAsString().equals("minecraft:model") && definition.get("model").getAsString().equals("test:item/shop/gem"), "Modern item definition");
			reject(() -> LuaResourcePackItemImport.importItem(root, source, "shop/gem"));
			for (String id : new String[]{"", "other:a", "../escape", "a//b", "/a", "test:a:b", "test:UPPER", "a/../b", "a\\b", "a/"})
				reject(() -> LuaResourcePackItemImport.importItem(root, source, id));
			var simple = LuaResourcePackItemImport.importItem(root, source, "simple");
			require(simple.id().equals("test:simple"), "Unqualified ID inferred namespace");
			// Any one conflicting destination cancels the entire import, preserving all existing content.
			for (int i = 0; i < 3; i++) {
				var paths = LuaResourcePackItemImport.paths("test", "occupied" + i);
				Path occupied = root.resolve(new String[]{paths.texturePath(), paths.modelPath(), paths.definitionPath()}[i]);
				Files.createDirectories(occupied.getParent()); Files.writeString(occupied, "untouched");
				int index = i; reject(() -> LuaResourcePackItemImport.importItem(root, source, "occupied" + index));
				require(Files.readString(occupied).equals("untouched"), "Existing destination overwritten");
				for (String name : new String[]{paths.texturePath(), paths.modelPath(), paths.definitionPath()})
					if (!root.resolve(name).equals(occupied)) require(!Files.exists(root.resolve(name)), "Conflict left orphan asset");
			}
			Path bad = sandbox.resolve("bad.png"); Files.writeString(bad, "not png");
			reject(() -> LuaResourcePackItemImport.importItem(root, bad, "invalid"));
			Path wrong = sandbox.resolve("renamed.png"); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", wrong.toFile());
			reject(() -> LuaResourcePackItemImport.importItem(root, wrong, "jpeg"));
			Path huge = sandbox.resolve("large.png"); ImageIO.write(new BufferedImage(2049, 1, BufferedImage.TYPE_INT_ARGB), "PNG", huge.toFile());
			reject(() -> LuaResourcePackItemImport.importItem(root, huge, "huge"));
			require(!Files.exists(root.resolve("items/invalid.json")) && !Files.exists(root.resolve("textures/item/huge.png")), "Invalid image wrote files");
			// Force a later parent creation failure, after PNG has been written; rollback must remove it.
			Path broken = Files.createDirectory(sandbox.resolve("broken")); Files.writeString(broken.resolve("models"), "not a directory");
			reject(() -> LuaResourcePackItemImport.importItem(broken, source, "rollback"));
			require(!Files.exists(broken.resolve("textures/item/rollback.png")) && !Files.exists(broken.resolve("items/rollback.json")), "Failed import left partial files");
			require(Files.readString(broken.resolve("models")).equals("not a directory"), "Rollback modified existing path");
			checkMenu();
			System.out.println("Item texture import smoke passed: " + checks + " checks; PNG validation, alpha, three linked assets, IDs, conflicts, rollback, submenu overflow and item-ID prompt");
		} finally {
			try (var paths = Files.walk(sandbox)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
		}
	}
	private static void checkMenu() throws Exception {
		var overlay = new LuaResourcePackToolOverlay(null, null, LuaResourcePackToolOverlay.Kind.DIRECTORY,
				ignored -> {}, ignored -> {}, () -> null, ignored -> {}, ignored -> true);
		overlay.resize(1000, 700); set(overlay, "open", true); set(overlay, "progress", 1F);
		set(overlay, "contextEntry", new LuaResourcePackWorkspace.Entry("pack/assets/test", true, 2));
		set(overlay, "contextX", 680); set(overlay, "contextY", 650); set(overlay, "contextOpen", true); set(overlay, "quickAddOpen", true);
		int x = (int) invoke(overlay, "quickAddX"), y = (int) invoke(overlay, "quickAddY");
		require(y + 50 <= 696, "Two-row submenu extends past bottom of screen");
		require(overlay.directoryContextContains(x + 10, y + 33) && overlay.contains(x + 10, y + 33), "PNG row outside parent frame does not capture input");
		set(overlay, "choosingOgg", true); // Suppress native OS dialog in automated tests.
		require(overlay.mouseClicked(new MouseButtonEvent(x + 10, y + 33, new MouseButtonInfo(0, 0)), false), "PNG menu row cannot be clicked");
		require(!(boolean) get(overlay, "quickAddOpen") && !(boolean) get(overlay, "contextOpen"), "PNG action not dispatched");
		var field = LuaResourcePackToolOverlay.class.getDeclaredField("pendingCreate"); field.setAccessible(true);
		@SuppressWarnings({"unchecked", "rawtypes"}) Object kind = Enum.valueOf((Class) field.getType(), "ITEM_TEXTURE");
		field.set(overlay, kind); set(overlay, "path", "test:shop/gem"); set(overlay, "pendingSoundNamespace", "pack/assets/test");
		require((int) invoke(overlay, "pathNameStart") == 0, "Item ID input incorrectly hides path prefix");
		require(invoke(overlay, "directoryLocation").equals("物品模型 ID · pack/assets/test"), "Wrong prompt title");
		require(Arrays.equals((String[]) invoke(overlay, "directoryActions"), new String[]{"确认导入", "取消"}), "Wrong prompt buttons");
		set(overlay, "choosingOgg", false); set(overlay, "pendingOgg", Path.of("selected.png"));
		invoke(overlay, "cancelPendingCreate");
		require(!overlay.hasTransientUi() && get(overlay, "pendingOgg") == null, "Cancel did not clear import prompt/source");
	}
	private static void set(Object target, String name, Object value) throws Exception { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
	private static Object get(Object target, String name) throws Exception { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
	private static Object invoke(Object target, String name) throws Exception { var m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target); }
	private interface Action { void run() throws IOException; }
	private static void reject(Action action) throws IOException { try { action.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError("Invalid/conflicting import accepted"); }
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
