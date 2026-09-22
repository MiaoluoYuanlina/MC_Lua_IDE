package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class McLuaIdeClient implements ClientModInitializer {
	private static KeyMapping openIdeKey;

	@Override
	public void onInitializeClient() {
		ScriptManager.registerNetworking();

		KeyMapping.Category category = KeyMapping.Category.register(
				wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.id("keys"));
		openIdeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mc_lua_ide.open",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_U,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openIdeKey.consumeClick()) openIde(client);
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
							.then(ClientCommands.argument("name", StringArgumentType.string()).executes(command -> {
								String name = StringArgumentType.getString(command, "name");
								Minecraft client = command.getSource().getClient();
								ScriptManager.read(name).whenComplete((source, failure) -> client.execute(() -> {
									if (failure != null) {
										command.getSource().sendError(Component.literal(
												"启动失败：" + ScriptManager.message(failure)));
										return;
									}
									try {
										ScriptManager.start(name, source,
												line -> client.execute(() -> command.getSource().sendFeedback(
														Component.literal("[" + name + "] " + line))),
												result -> client.execute(() -> {
													if (result.stopped()) {
														command.getSource().sendFeedback(Component.literal("脚本已停止：" + name));
													} else if (result.error() != null) {
														command.getSource().sendError(Component.literal("运行失败：" + result.error()));
													} else {
														command.getSource().sendFeedback(Component.literal("脚本执行完成：" + name));
													}
												}));
										command.getSource().sendFeedback(Component.literal("正在运行脚本 " + name + "…"));
									} catch (RuntimeException error) {
										command.getSource().sendError(Component.literal("启动失败：" + error.getMessage()));
									}
								}));
								return 1;
							})))
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
			ScriptDirectoryScreen directory = new ScriptDirectoryScreen(client.screen);
			ScriptManager.ScriptExecution active = ScriptManager.activeExecution();
			client.setScreen(active == null ? directory : new LuaIdeScreen(directory, active.name()));
		}));
	}
}
