package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Opt-in real editor crash/reopen regression, strictly in build/recovery-probe, no user world. */
final class LuaFileRecoveryProbe {
	private static int stage, checks;
	private static long started, until;
	private static LuaIdeScreen ide;
	private static LuaFileRecovery store;
	private static CompletableFuture<Void> task;
	private static String ignoredId;
	private static Path jsonPath, pngPath;
	private static final String JSON = "recovery/assets/test/models/item/test.json";
	private static final String PNG = "recovery/assets/test/textures/item/test.png";
	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); client.options.guiScale().set(1); client.resizeGui();
				var session = new LuaIdeSession(client.gameDirectory.toPath().resolve("config/mc_lua_ide"), "local");
				session.save(new LuaIdeSession.State(List.of(), ""));
				store = LuaFileRecovery.forSession(session);
				for (var entry : store.entries()) store.discard(entry.id());
				ide = new LuaIdeScreen(new TitleScreen(), ""); client.setScreen(ide);
				set(ide, "manualOpen", false); set(ide, "manualProgress", 0F); set(ide, "recoveryPrompt", false);
				jsonPath = LuaResourcePackWorkspace.diskPath(JSON); pngPath = LuaResourcePackWorkspace.diskPath(PNG);
				Files.createDirectories(jsonPath.getParent()); Files.writeString(jsonPath, "{\"disk\":true}");
				Files.createDirectories(pngPath.getParent()); javax.imageio.ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "PNG", pngPath.toFile());
				var lua = addDocument("alpha", false, false, "print('恢复中文')", "print('disk')");
				addDocument("test/data/test/function/test.mcfunction", true, false, "say recovered", "say disk");
				addDocument(JSON, true, true, "{\"recovered\":true}", "{\"disk\":true}");
				var png = addDocument(PNG, true, true, "", "");
				var image = new LuaPngLayerDocument(pngPath, new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), client.gameDirectory.toPath().resolve("image-project-probe.zip"));
				image.addLayer(); image.beginStroke(); image.stroke(1, 1, LuaPngLayerDocument.Tool.PENCIL, 0xFF123456, 1); image.endStroke(); image.toggleVisible(0);
				set(png, "pngCanvas", new LuaResourcePngCanvas(client, PNG, image.recoveryImage()));
				open("alpha", false, false);
				ignoredId = UUID.randomUUID().toString();
				store.save(new LuaFileRecovery.Draft(new LuaFileRecovery.Entry(ignoredId, new LuaIdeSession.Document("alpha", false, false, 0, 0), 1, false), "print('older ignored')", "print('disk')", null));
				call(ide, "snapshotRecovery", new Class<?>[0]); task = store.flush(); stage = 1; return;
			}
			require(System.nanoTime() - started < 90_000_000_000L, "Recovery probe timeout at " + stage);
			if (stage == 1) {
				if (!task.isDone()) return; task.join();
				require(store.entries().size() == 5, "All modified document kinds backed up");
				client.setScreen(new TitleScreen()); // abnormal replacement: no onClose/save/discard decision
				ide = new LuaIdeScreen(new TitleScreen(), ""); client.setScreen(ide);
				require((boolean) get(ide, "recoveryPrompt"), "Reopen did not offer recovery");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_F12, 0, 0));
				require(client.screen == ide && (boolean) get(ide, "recoveryPrompt"), "Recovery modal did not capture input");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
				require(!(boolean) get(ide, "recoveryPrompt") && store.entries().size() == 5, "Later deleted drafts");
				call(ide, "toggleMenu", new Class<?>[]{String.class}, "tools");
				@SuppressWarnings("unchecked") var tools = (List<Button>) get(ide, "toolMenuItems");
				Button button = tools.stream().filter(b -> b.getMessage().getString().contains("文件恢复")).findFirst().orElseThrow();
				float scale = (float) get(ide, "interfaceScale");
				ide.mouseClicked(new MouseButtonEvent((button.getX() + 5) * scale, (button.getY() + 5) * scale, new MouseButtonInfo(0, 0)), false);
				var frame = frame(); require(frame.isOpen() && frame.title().equals("文件恢复"), "Tools recovery entry did not open shared frame");
				frame.setDockSide(DockSide.RIGHT); require(frame.dockSide() == DockSide.RIGHT, "Recovery cannot dock"); frame.setDockSide(DockSide.FLOATING);
				for (var entry : store.entries()) call(ide, "requestRecovery", new Class<?>[]{LuaFileRecovery.Entry.class}, entry);
				stage = 2; return;
			}
			if (stage == 2) {
				if ((boolean) get(ide, "recoveryRestoring")) return;
				require(get(documents().get("lua:alpha"), "source").equals("print('恢复中文')"), "Lua source recovery or dirty conflict guard failed");
				require(get(documents().get("datapack:test/data/test/function/test.mcfunction"), "source").equals("say recovered"), "Function recovery failed");
				require(get(documents().get("resourcepack:" + JSON), "source").equals("{\"recovered\":true}"), "JSON recovery failed");
				require(Files.readString(jsonPath).equals("{\"disk\":true}"), "Restoring overwrote original JSON");
				var canvas = (LuaResourcePngCanvas) get(documents().get("resourcepack:" + PNG), "pngCanvas");
				require(canvas.document().dirty() && canvas.document().layers().size() == 2 && !canvas.document().layers().getFirst().visible(), "PNG layers/dirty/visibility lost");
				require(canvas.document().composite().getRGB(1, 1) == 0xFF123456, "PNG pixels lost");
				require(javax.imageio.ImageIO.read(pngPath.toFile()).getRGB(1, 1) == 0, "Restoring overwrote original PNG");
				open(PNG, true, true); client.setScreen(new LuaDebugScreen(ide)); client.setScreen(ide);
				require(((LuaResourcePngCanvas) get(documents().get("resourcepack:" + PNG), "pngCanvas")).document().composite().getRGB(1, 1) == 0xFF123456, "Temporary F12 screen discarded restored PNG edits");
				open(JSON, true, true); require((boolean) call(ide, "saveScript", new Class<?>[0]), "Restored JSON could not save");
				require(Files.readString(jsonPath).equals("{\"recovered\":true}"), "Explicit Save did not write restored JSON");
				require(store.entries().size() == 4, "Successful save left its recovery draft");
				open("alpha", false, false);
				// Failed/no-server run must not mark unsaved source clean or remove the draft.
				call(ide, "runScript", new Class<?>[0]); call(ide, "snapshotRecovery", new Class<?>[0]);
				require(!get(documents().get("lua:alpha"), "source").equals(get(documents().get("lua:alpha"), "savedSource")), "Failed run removed unsaved status");
				call(ide, "setExtensionWindowOpen", new Class<?>[]{String.class, boolean.class}, LuaFileRecoveryWindow.ID, true);
				until = System.nanoTime() + 500_000_000L; stage = 3; return;
			}
			if (stage == 3) {
				if (System.nanoTime() < until) return;
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "file-recovery.png", client.getMainRenderTarget(), 1, ignored -> { });
				ide.onClose(); require(get(ide, "pendingCloseAction") != null, "Close unsaved prompt missing");
				call(ide, "chooseClose", new Class<?>[]{int.class}, 1); task = store.flush(); stage = 4; return;
			}
			if (stage == 4) {
				if (!task.isDone()) return; task.join();
				require(store.entries().size() == 1 && store.entries().getFirst().id().equals(ignoredId), "Clean close deleted ignored old draft or retained discarded edits");
				ide = new LuaIdeScreen(new TitleScreen(), ""); client.setScreen(ide);
				require((boolean) get(ide, "recoveryPrompt"), "Unaccepted draft not offered next time");
				call(ide, "chooseRecoveryPrompt", new Class<?>[]{int.class}, 0);
				var content = (LuaFileRecoveryWindow) get(frame(), "content");
				content.resize(430, 250);
				content.mouseClicked(new MouseButtonEvent(10, 35, new MouseButtonInfo(0, 0)), false);
				content.mouseClicked(new MouseButtonEvent(350, 230, new MouseButtonInfo(0, 0)), false);
				require(store.entries().size() == 1, "Deleting recovery did not require confirmation");
				content.mouseClicked(new MouseButtonEvent(350, 230, new MouseButtonInfo(0, 0)), false);
				require(store.entries().isEmpty() && Files.readString(jsonPath).equals("{\"recovered\":true}"), "Confirmed deletion touched original files");
				ide.onClose(); task = store.flush(); stage = 5; return;
			}
			if (stage == 5) {
				if (!task.isDone()) return; task.join();
				ide = new LuaIdeScreen(new TitleScreen(), ""); client.setScreen(ide);
				require(!(boolean) get(ide, "recoveryPrompt"), "Clean restart incorrectly offered recovery");
				System.out.println("File recovery real UI probe passed: " + checks + " checks; abnormal reopen, prompt, tools window/docking, text/PNG restore, save, conflict and clean close");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) { int failed = stage; stage = 99; client.stop(); throw new IllegalStateException("Recovery UI probe failed at " + failed, error); }
	}
	@SuppressWarnings("unchecked") private static Map<String, Object> documents() throws Exception { return (Map<String, Object>) get(ide, "openDocuments"); }
	private static Object addDocument(String name, boolean json, boolean resource, String source, String saved) throws Exception {
		Class<?> type = Class.forName(LuaIdeScreen.class.getName() + "$OpenDocument");
		var constructor = type.getDeclaredConstructor(String.class, boolean.class, boolean.class); constructor.setAccessible(true);
		var doc = constructor.newInstance(name, json, resource); set(doc, "source", source); set(doc, "savedSource", saved); set(doc, "lastSnapshot", source); set(doc, "loaded", true);
		documents().put((resource ? "resourcepack:" : json ? "datapack:" : "lua:") + name, doc); return doc;
	}
	private static void open(String name, boolean json, boolean resource) throws Exception { call(ide, "openDocument", new Class<?>[]{String.class, boolean.class, boolean.class}, name, json, resource); }
	private static LuaExtensionWindow frame() throws Exception {
		return ((Map<?, ?>) get(ide, "extensionWindows")).values().stream().map(LuaExtensionWindow.class::cast).filter(w -> w.id().equals(LuaFileRecoveryWindow.ID)).findFirst().orElseThrow();
	}
	private static Object call(Object object, String name, Class<?>[] signature, Object... args) throws Exception { var method = object.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(object, args); }
	private static Object get(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
	private static void set(Object object, String name, Object value) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
