package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import wiki.ica.xiaomiao.mcluaide.network.LuaServerPack;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Opt-in isolated real UI/network/server/vanilla resource-pack regression. Never opens a user save. */
final class LuaToolsProbe {
	private static final String SOURCE = "MC.Event.onItemUsed(function(event)\n        if event then\nprint(event)\nend\n    end)\n";
	private static int stage, checks, catalogRevision, originalWidth, port;
	private static long started, until;
	private static CompletableFuture<?> task;
	private static LuaIdeScreen ide;
	private static LuaExtensionWindow frame;
	private static LuaServerPackWindow content;
	private static MultiLineEditBox editor;
	private static Path zip;

	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); stage = 1;
				client.options.guiScale().set(1); client.resizeGui();
				client.options.renderDistance().set(3); client.options.simulationDistance().set(3);
				CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Probe creation cancelled"); }); return;
			}
			if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(180)) throw new AssertionError("Tools probe timeout at " + stage);
			if (stage >= 11 && stage <= 12 && client.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm
					&& confirm.getClass().getName().contains("PackConfirmScreen")) {
				var yesField = net.minecraft.client.gui.screens.ConfirmScreen.class.getDeclaredField("yesButton"); yesField.setAccessible(true);
				Button yes = (Button) yesField.get(confirm);
				require(confirm.mouseClicked(new MouseButtonEvent(yes.getX() + 5, yes.getY() + 5, new MouseButtonInfo(0, 0)), false), "Vanilla resource-pack acceptance prompt works");
				return;
			}
			if (stage >= 11 && client.screen == ide && get(frame, "content") != null)
				content = (LuaServerPackWindow) get(frame, "content");
			if (stage == 1) {
				if (!(client.screen instanceof CreateWorldScreen create)) return;
				create.getUiState().setName("tools_" + System.currentTimeMillis());
				create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
				Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
				stage = 2; method.invoke(create); return;
			}
			if (stage == 2) {
				if (client.player == null || !ScriptManager.isServerAvailable() || client.getSingleplayerServer() == null) return;
				task = ScriptManager.checkAccess().thenCompose(ignored -> ScriptManager.save("tools_probe", SOURCE))
						.thenCompose(ignored -> ScriptManager.createDatapack("tools_probe"))
						.thenCompose(ignored -> ScriptManager.saveDatapackFile("tools_probe/data/tools_probe/function/reloaded.mcfunction", "say tools probe\n"));
				stage = 3; return;
			}
			if (stage == 3) {
				if (!ready()) return;
				ide = new LuaIdeScreen(null, "tools_probe"); client.setScreen(ide);
				call(ide, "openDocument", new Class<?>[]{String.class, boolean.class}, "tools_probe", false);
				set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
				Map<?, LuaExtensionWindow> windows = (Map<?, LuaExtensionWindow>) get(ide, "extensionWindows");
				frame = windows.values().stream().filter(window -> window.id().equals(LuaServerPackWindow.ID)).findFirst().orElseThrow();
				ide.setExtensionWindowOpen(LuaServerPackWindow.ID, false); dock(DockSide.FLOATING);
				stage = 4; return;
			}
			if (stage == 4) {
				if (!(boolean) get(ide, "sourceLoaded")) return;
				editor = (MultiLineEditBox) get(ide, "editor");
				require(editor.getValue().equals(SOURCE), "Server source loaded");
				var store = (LuaBreakpointStore) get(ide, "breakpointStore"); store.clear("tools_probe"); store.toggle("tools_probe", 3, SOURCE);
				var field = ((wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
				field.seekCursor(Whence.ABSOLUTE, SOURCE.indexOf("print"));
				pressTool(0); stage = 5; return;
			}
			if (stage == 5) {
				if ((boolean) get(ide, "formattingCode")) return;
				var store = (LuaBreakpointStore) get(ide, "breakpointStore");
				require(editor.getValue().equals(LuaIndentation.formatDocument(SOURCE)), "Real menu formats four-space indentation");
				require(store.lines("tools_probe").equals(Set.of(3)), "Formatting keeps breakpoint line");
				var field = ((wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
				require(field.cursor() == editor.getValue().indexOf("print"), "Formatting keeps caret on original token");
				ide.setFocused(editor); editor.active = true;
				ide.keyPressed(new KeyEvent(InputConstants.KEY_Z, 0, 2));
				require(editor.getValue().equals(SOURCE), "Single Ctrl+Z undoes formatting");
				require(store.lines("tools_probe").equals(Set.of(3)), "Undo keeps breakpoint line");
				catalogRevision = ScriptManager.catalogRevision(); pressTool(1); stage = 6; return;
			}
			if (stage == 6) {
				if ((boolean) get(ide, "reloadingResourcePacks")) return;
				require(((String) get(ide, "toolNotice")).contains("重载完成"), "Resource reload completion survives editor rebuild");
				require(ScriptManager.catalogRevision() > catalogRevision, "Resource reload invalidates catalog");
				catalogRevision = ScriptManager.catalogRevision(); pressTool(2); stage = 7; return;
			}
			if (stage == 7) {
				if ((boolean) get(ide, "reloadingDatapacks")) return;
				require(((String) get(ide, "status")).contains("重载完成"), "Data reload reports actual completion");
				require(ScriptManager.catalogRevision() > catalogRevision, "Data reload notifies administrator catalog");
				var server = client.getSingleplayerServer(); var owner = client.player.getUUID();
				task = server.submit(() -> {
					try {
						require(server.getFunctions().get(Identifier.parse("tools_probe:reloaded")).isPresent(), "New data-pack function actually loaded");
						var ordinary = new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Ordinary"), net.minecraft.server.level.ClientInformation.createDefault());
						var auth = Class.forName("wiki.ica.xiaomiao.mcluaide.server.LuaServerNetworking").getDeclaredMethod("isAdministrator", net.minecraft.server.MinecraftServer.class, ServerPlayer.class);
						auth.setAccessible(true);
						require(!(boolean) auth.invoke(null, server, ordinary), "Ordinary player rejected by privileged operation gate");
						require((boolean) auth.invoke(null, server, server.getPlayerList().getPlayer(owner)), "Owner authorized");
						return true;
					} catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
				}); stage = 8; return;
			}
			if (stage == 8) {
				if (!ready()) return;
				editor = (MultiLineEditBox) get(ide, "editor"); originalWidth = editor.getWidth(); pressTool(3);
				Map<?, LuaExtensionWindow> windows = (Map<?, LuaExtensionWindow>) get(ide, "extensionWindows");
				frame = windows.values().stream().filter(window -> window.id().equals(LuaServerPackWindow.ID)).findFirst().orElseThrow();
				require(frame.isOpen(), "Upload menu opens shared IDE window");
				content = (LuaServerPackWindow) get(frame, "content"); stage = 9; return;
			}
			if (stage == 9) {
				if ((boolean) get(content, "busy")) return;
				require(get(content, "serverStatus") != null && !(boolean) get(frame, "failed"), "Upload window loads authenticated server settings");
				dock(DockSide.RIGHT);
				until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); stage = 10; return;
			}
			if (stage == 10) {
				if (System.nanoTime() < until) return;
				editor = (MultiLineEditBox) get(ide, "editor");
				require(editor.getWidth() < originalWidth, "Docked upload window shrinks code workspace: " + editor.getWidth() + " / " + originalWidth);
				require(!(boolean) get(frame, "failed"), "Window resize/dock rendering has no content error");
				dock(DockSide.FLOATING);
				zip = client.gameDirectory.toPath().resolve("tool-probe-pack.zip");
				var version = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
					entry(output, "pack.mcmeta", ("{\"pack\":{\"description\":\"Tools probe\",\"min_format\":[" + version.major() + "," + version.minor() + "],\"max_format\":[" + version.major() + "," + version.minor() + "]}}").getBytes(StandardCharsets.UTF_8));
					entry(output, "assets/tools_probe/models/item/test.json", "{\"parent\":\"minecraft:block/tnt\"}".getBytes(StandardCharsets.UTF_8));
					byte[] padding = new byte[130_000]; new java.util.Random(123).nextBytes(padding);
					entry(output, "assets/tools_probe/padding.bin", padding);
				}
				try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
				set(content, "selected", zip);
				((EditBox) get(content, "port")).setValue(Integer.toString(port));
				((EditBox) get(content, "url")).setValue("http://127.0.0.1:" + port);
				client.getDownloadedPackSource().configureForServerControl(client.getConnection().getConnection(), net.minecraft.client.resources.server.ServerPackManager.PackPromptStatus.ALLOWED);
				call(content, "apply", new Class<?>[]{}); stage = 11; return;
			}
			if (stage == 11) {
				if ((boolean) get(content, "busy")) return;
				var published = (LuaServerPack.Status) get(content, "serverStatus");
				require(published.settings().enabled(), "Shared window uploads and publishes ZIP through real network");
				require(published.size() > LuaServerPack.CHUNK_BYTES * 2L && published.size() == Files.size(zip), "Multi-chunk upload complete");
				require(!published.settings().required(), "Pack remains optional by default");
				stage = 12; return;
			}
			if (stage == 12) {
				var resource = client.getResourceManager().getResource(Identifier.parse("tools_probe:models/item/test.json"));
				if (resource.isEmpty()) return;
				try (var input = resource.get().open()) { require(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("minecraft:block/tnt"), "Vanilla download path actually loaded uploaded assets"); }
				call(content, "disable", new Class<?>[]{}); stage = 13; return;
			}
			if (stage == 13) {
				if ((boolean) get(content, "busy") || client.getResourceManager().getResource(Identifier.parse("tools_probe:models/item/test.json")).isPresent()) return;
				require(!((LuaServerPack.Status) get(content, "serverStatus")).settings().enabled(), "Disable removes vanilla pack");
				require(!(boolean) get(frame, "failed"), "No upload content rendering error after reload");
				System.out.println("Tools real game probe passed: " + checks + " checks; menu, format/undo/caret/breakpoints, both reloads, admin gate, docking, chunk upload, vanilla HTTP download/load and removal");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError failure) {
			int failed = stage; stage = 99; client.stop(); throw new IllegalStateException("Tools real game probe failed at " + failed, failure);
		}
	}
	private static void entry(ZipOutputStream out, String name, byte[] bytes) throws Exception { out.putNextEntry(new ZipEntry(name)); out.write(bytes); out.closeEntry(); }
	@SuppressWarnings("unchecked") private static void pressTool(int index) throws Exception {
		call(ide, "toggleMenu", new Class<?>[]{String.class}, "tools");
		List<Button> buttons = (List<Button>) get(ide, "toolMenuItems");
		require(buttons.size() == 7, "Tools, API export, server settings and file recovery retained");
		Button button = buttons.get(index); require(button.visible, "Tool item visible");
		float scale = (float) get(ide, "interfaceScale");
		require(ide.mouseClicked(new MouseButtonEvent((button.getX() + 10) * scale, (button.getY() + 10) * scale, new MouseButtonInfo(0, 0)), false), "Menu click handled");
	}
	private static Object call(Object target, String name, Class<?>[] signature, Object... args) throws Exception { var method = target.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(target, args); }
	private static void dock(DockSide side) throws Exception {
		Class<?> toolType = Class.forName(LuaIdeScreen.class.getName() + "$ToolWindow");
		var extension = toolType.getDeclaredMethod("extension", String.class); extension.setAccessible(true);
		call(ide, "dockTool", new Class<?>[]{toolType, DockSide.class}, extension.invoke(null, LuaServerPackWindow.ID), side);
	}
	private static Object get(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
	private static void set(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
	private static boolean ready() { if (!task.isDone()) return false; task.join(); return true; }
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
