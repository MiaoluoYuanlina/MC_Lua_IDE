package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

public final class LuaResourcePackSoundImportSmoke {
	public static void main(String[] args) throws Exception {
		// Minecraft's Main sets this itself. File selection must not depend on AWT windows.
		System.setProperty("java.awt.headless", "true");
		require(java.awt.GraphicsEnvironment.isHeadless(), "Headless regression setup failed");
		String nativeVersion = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_getGlobalChar(
				org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_version);
		require(nativeVersion != null && !nativeVersion.isBlank(),
				"Minecraft's native file dialog backend is unavailable in headless mode");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path sandbox = Files.createTempDirectory("mc_lua_sound_import_test_");
		try {
			Path namespace = Files.createDirectory(sandbox.resolve("test"));
			Path fixture = Path.of("src/test/resources/resourcepacks/silence.ogg").toAbsolutePath();
			Path source = sandbox.resolve("source.ogg"); Files.copy(fixture, source);
			Path json = namespace.resolve("sounds.json");
			Files.writeString(json, "{\"existing\":{\"subtitle\":\"my.subtitle\",\"sounds\":[\"test:old\"]}}");
			var result = LuaResourcePackSoundImport.importSound(namespace, source, "test:music/demo");
			require(result.id().equals("test:music/demo") && result.audioPath().equals("sounds/music/demo.ogg"), "ID/path mapping");
			require(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(namespace.resolve(result.audioPath()))), "Copied audio changed");
			var events = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
			require(events.getAsJsonObject("existing").get("subtitle").getAsString().equals("my.subtitle"), "Existing event metadata lost");
			require(events.getAsJsonObject("music/demo").getAsJsonArray("sounds").get(0).getAsJsonObject()
					.get("name").getAsString().equals("test:music/demo"), "Sound reference missing namespace");
			String preserved = Files.readString(json);
			expectFailure(() -> LuaResourcePackSoundImport.importSound(namespace, source, "music/demo"));
			require(Files.readString(json).equals(preserved), "Duplicate import overwrote JSON");
			for (String invalid : new String[]{"other:sound", "../escape", "dir//file", "/absolute", "test:UPPER", "test:a:b", ""})
				expectFailure(() -> LuaResourcePackSoundImport.importSound(namespace, source, invalid));
			require(Files.readString(json).equals(preserved), "Rejected IDs modified JSON");
			Files.writeString(namespace.resolve("sounds/occupied.ogg"), "do not overwrite");
			expectFailure(() -> LuaResourcePackSoundImport.importSound(namespace, source, "occupied"));
			require(Files.readString(namespace.resolve("sounds/occupied.ogg")).equals("do not overwrite"), "Existing audio overwritten");
			Files.writeString(json, "{broken");
			expectFailure(() -> LuaResourcePackSoundImport.importSound(namespace, source, "bad_json"));
			require(!Files.exists(namespace.resolve("sounds/bad_json.ogg")), "Bad JSON left orphan audio");
			Files.writeString(json, "[]");
			expectFailure(() -> LuaResourcePackSoundImport.importSound(namespace, source, "array_json"));
			Files.writeString(json, preserved);
			Path invalidAudio = sandbox.resolve("bad.ogg"); Files.writeString(invalidAudio, "not ogg");
			expectFailure(() -> LuaResourcePackSoundImport.importSound(namespace, invalidAudio, "invalid_audio"));
			require(!Files.exists(namespace.resolve("sounds/invalid_audio.ogg")), "Invalid audio copied");
			Path emptyNamespace = Files.createDirectory(sandbox.resolve("empty"));
			var unqualified = LuaResourcePackSoundImport.importSound(emptyNamespace, source, "notice");
			require(unqualified.id().equals("empty:notice") && Files.exists(emptyNamespace.resolve("sounds.json")), "New JSON/unqualified ID failed");
			try (var files = Files.walk(namespace)) {
				require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "Temporary JSON leaked");
			}
			checkMenu();
			System.out.println("Resource pack sound import smoke passed");
		} finally {
			// Remove only this test's freshly allocated temporary sandbox.
			try (var paths = Files.walk(sandbox)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
	private static void checkMenu() throws Exception {
		var overlay = new LuaResourcePackToolOverlay(null, null, LuaResourcePackToolOverlay.Kind.DIRECTORY,
				ignored -> { }, ignored -> { }, () -> null, ignored -> { }, ignored -> true);
		var entryField = LuaResourcePackToolOverlay.class.getDeclaredField("contextEntry"); entryField.setAccessible(true);
		var actions = LuaResourcePackToolOverlay.class.getDeclaredMethod("contextActions"); actions.setAccessible(true);
		entryField.set(overlay, new LuaResourcePackWorkspace.Entry("pack/assets/test", true, 2));
		require(actions.invoke(overlay).toString().contains("QUICK_ADD"), "Namespace menu missing quick import");
		overlay.resize(1000, 700);
		setField(overlay, "open", true); setField(overlay, "progress", 1F);
		setField(overlay, "contextOpen", true); setField(overlay, "contextX", 680); setField(overlay, "contextY", 250);
		require(overlay.mouseClicked(new MouseButtonEvent(690, 281, new MouseButtonInfo(0, 0)), false), "Quick-add parent cannot be clicked");
		var submenuX = LuaResourcePackToolOverlay.class.getDeclaredMethod("quickAddX"); submenuX.setAccessible(true);
		var submenuY = LuaResourcePackToolOverlay.class.getDeclaredMethod("quickAddY"); submenuY.setAccessible(true);
		int mx = (Integer) submenuX.invoke(overlay) + 10, my = (Integer) submenuY.invoke(overlay) + 10;
		require(overlay.directoryContextContains(mx, my) && overlay.contains(mx, my), "Submenu outside the docked frame is not interactive");
		overlay.dismissDirectoryContext();
		require(!overlay.directoryContextContains(mx, my), "Dismissed submenu still captures input");
		entryField.set(overlay, new LuaResourcePackWorkspace.Entry("pack/assets/test/models", true, 3));
		require(!actions.invoke(overlay).toString().contains("QUICK_ADD"), "Quick import offered outside namespace root");
		entryField.set(overlay, new LuaResourcePackWorkspace.Entry("pack/assets/test/sounds.json", false, 3));
		require(!actions.invoke(overlay).toString().contains("QUICK_ADD"), "Quick import offered on a file");
	}
	private static void setField(Object target, String name, Object value) throws Exception {
		var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
	private interface IoAction { void run() throws IOException; }
	private static void expectFailure(IoAction action) throws IOException {
		try { action.run(); } catch (IOException expected) { return; }
		throw new AssertionError("Invalid/conflicting sound import accepted");
	}
	private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
