package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptCommandArgument;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class McLuaIdeClient implements ClientModInitializer {
	private static KeyMapping openIdeKey;
	private static KeyMapping openDebugKey;
	private static KeyMapping debugScrollUpKey;
	private static KeyMapping debugScrollDownKey;

	@Override
	public void onInitializeClient() {
		ScriptManager.registerNetworking();
		net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
				.registerReloadListener(wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("editor_catalogs"),
						(net.minecraft.server.packs.resources.ResourceManagerReloadListener) resources -> {
							LuaOggPreviewPlayer.stop();
							ScriptManager.invalidateAllRegistryCatalogs();
						});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { ImGuiIdeRuntime.shutdown(); LuaClientMcpService.shutdown(); LuaDebuggerCamera.disconnected(); WindowsPenPressure.uninstall(); });

		KeyMapping.Category category = KeyMapping.Category.register(
				wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("keys"));
		openIdeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mc_lua_ide.open",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_U,
				category));
		openDebugKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mc_lua_ide.debug",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F12,
				category));
		debugScrollUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mc_lua_ide.debug_scroll_up",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UP,
				category));
		debugScrollDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mc_lua_ide.debug_scroll_down",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_DOWN,
				category));
		HudElementRegistry.addLast(wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("debug_variables"),
				LuaDebugHud::render);
		HudElementRegistry.addLast(wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("debug_breakpoints"), LuaBreakpointClient::render);
		HudElementRegistry.addLast(wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("debug_camera"), LuaDebuggerCamera::render);
		HudElementRegistry.addLast(wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("debug_logs"), LuaLogHud::render);
		ClientTickEvents.START_CLIENT_TICK.register(LuaDebuggerCamera::tick);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (Boolean.getBoolean("mc_lua_ide.imGuiProbe")) ImGuiIdeProbe.tick(client);
				GameControl.tick(client);
				if (Boolean.getBoolean("mc_lua_ide.gameControlProbe")) GameControlProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.fileRecoveryProbe")) LuaFileRecoveryProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.gitProbe")) LuaGitProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.toolsProbe")) LuaToolsProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.sleepProbe")) LuaSleepProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.intervalProbe")) LuaIntervalProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.entitySpawnProbe")) LuaEntitySpawnProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.runtimeWarningsProbe")) LuaRuntimeWarningsProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.containerProbe")) LuaContainerProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.variableCopyProbe")) LuaVariableCopyProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.hudLayoutProbe")) LuaHudLayoutProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.localizedNamesProbe")) LuaLocalizedNamesProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.editorContextProbe")) LuaEditorContextProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.textJsonProbe")) LuaTextJsonProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.insertSoundProbe")) LuaInsertSoundProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.windowShortcutsProbe")) LuaWindowShortcutsProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.colorPickerProbe")) LuaColorProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.blockBreakProbe")) LuaBlockBreakProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.mcpProbe")) LuaMcpProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.aiProbe")) LuaAiProbe.tick(client);
			if (System.getProperty("mc_lua_ide.oggPlaybackProbe") != null) LuaOggPlaybackProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.soundSeekProbe")) LuaSeekSoundProbe.tick(client);
			if (System.getProperty("mc_lua_ide.packCatalogProbe") != null) LuaResourceCatalogProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.argumentCompletionProbe")) LuaArgumentCompletionProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.functionEditorProbe")) LuaFunctionEditorProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.breakpointProbe")) LuaBreakpointProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.debugGamePauseProbe")) LuaDebuggerGamePauseProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.directoryInputProbe")) LuaDirectoryInputProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.directoryContextProbe")) LuaDirectoryContextProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.scriptCommandProbe")) LuaScriptCommandProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.personalizationProbe")) LuaPersonalizationProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.largeFileProbe")) LuaLargeFileProbe.tick(client);
			if (Boolean.getBoolean("mc_lua_ide.editorInteractionProbe")) LuaEditorInteractionProbe.tick(client);
			while (openIdeKey.consumeClick()) openIde(client);
			while (openDebugKey.consumeClick()) {
				if (client.screen == null && client.player != null)
					client.setScreen(new NativePanelScreen(null,true));
			}
			while (debugScrollUpKey.consumeClick()) {
				if (client.screen == null && client.player != null) LuaDebugHud.scroll(-1);
			}
			while (debugScrollDownKey.consumeClick()) {
				if (client.screen == null && client.player != null) LuaDebugHud.scroll(1);
			}
			LuaDebugHud.tick(client);
			LuaBreakpointClient.tick(client);
			LuaDebuggerCamera.synchronize(client);
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
			dispatcher.register(ClientCommands.literal("lua")
					.executes(command -> {
						openIde(command.getSource().getClient());
						return 1;
					})
					.then(ClientCommands.literal("list").executes(command -> {
						Minecraft client = command.getSource().getClient();
						ScriptManager.listScripts().whenComplete((scripts, failure) -> client.execute(() -> {
							if (failure != null) {
								command.getSource().sendError(Component.literal(
										"读取脚本失败：" + ScriptManager.message(failure)));
							} else {
								command.getSource().sendFeedback(Component.literal(scripts.isEmpty()
										? "暂无 Lua 脚本" : "Lua 脚本：" + String.join(", ", scripts)));
							}
						}));
						return 1;
					}))
					.then(ClientCommands.literal("run")
							.then(ClientCommands.argument("name", LuaScriptCommandArgument.script())
									.suggests((command, builder) -> scriptSuggestions(builder))
									.executes(command -> runScript(command, false))))
					.then(ClientCommands.literal("testrun")
							.then(ClientCommands.argument("name", LuaScriptCommandArgument.script())
									.suggests((command, builder) -> scriptSuggestions(builder))
									.executes(command -> runScript(command, true))))
					.then(ClientCommands.literal("stop").executes(command -> {
						Minecraft client = command.getSource().getClient();
						ScriptManager.stopServer().whenComplete((stopped, failure) -> client.execute(() -> {
							if (failure != null) {
								command.getSource().sendError(Component.literal(
										"停止失败：" + ScriptManager.message(failure)));
							} else if (stopped) {
								command.getSource().sendFeedback(Component.literal("正在停止 Lua 脚本…"));
							} else {
								command.getSource().sendError(Component.literal("当前没有正在运行的脚本"));
							}
						}));
						return 1;
					}))));
	}

	private static java.util.concurrent.CompletableFuture<Suggestions> scriptSuggestions(SuggestionsBuilder builder) {
		if (!ScriptManager.isServerAvailable()) return builder.buildFuture();
		// Use the permission-checked server list on every request: includes nested files and never leaks another server's cached paths.
		return ScriptManager.listScripts().handle((scripts, failure) -> LuaScriptCommandArgument.complete(
				failure == null ? scripts : java.util.List.of(), builder));
	}
	private static int runScript(CommandContext<FabricClientCommandSource> command, boolean debug) {
		String name = command.getArgument("name", String.class);
		Minecraft client = command.getSource().getClient();
		ScriptManager.read(name).whenComplete((source, failure) -> client.execute(() -> {
			if (failure != null) {
				command.getSource().sendError(Component.literal("启动失败：" + ScriptManager.message(failure)));
				return;
			}
			try {
				java.util.function.Consumer<String> output = line -> client.execute(() ->
						command.getSource().sendFeedback(Component.literal("[" + name + "] " + line)));
				java.util.function.Consumer<ScriptManager.ExecutionResult> completion = result -> client.execute(() -> {
					if (result.stopped()) command.getSource().sendFeedback(Component.literal("脚本已停止：" + name));
					else if (result.error() != null)
						command.getSource().sendError(Component.literal("运行失败：" + result.error()));
					else command.getSource().sendFeedback(Component.literal("脚本执行完成：" + name));
				});
				if (debug) ScriptManager.start(name, source, output, completion);
				else ScriptManager.startNormal(name, source, output, completion);
				command.getSource().sendFeedback(Component.literal("正在" + (debug ? "调试" : "运行")
						+ "脚本 " + name + "…"));
			} catch (RuntimeException error) {
				command.getSource().sendError(Component.literal("启动失败：" + error.getMessage()));
			}
		}));
		return 1;
	}

	static void openIde(Minecraft client) {
		if (!ScriptManager.isServerAvailable()) {
			if (client.player != null) client.player.sendSystemMessage(
					Component.literal("服务器未安装或未启用 MC Lua IDE"));
			return;
		}
		ScriptManager.checkAccess().thenCompose(ignored -> ScriptManager.refreshApiDocumentation())
				.whenComplete((extensionCount, failure) -> client.execute(() -> {
			if (failure != null) {
				if (client.player != null) client.player.sendSystemMessage(
						Component.literal("无法打开 Lua IDE：" + ScriptManager.message(failure)));
				return;
			}
			ScriptManager.ScriptExecution active = ScriptManager.activeExecution();
			if(!ImGuiIdeRuntime.available()) {if(client.player!=null)client.player.sendSystemMessage(Component.literal("新版 UI 渲染器不可用，请查看客户端日志并重启；未保存代码可从恢复草稿找回。"));return;}
			client.setScreen(new ImGuiIdeScreen(client.screen, active == null ? "" : active.name()));
		}));
	}
}
