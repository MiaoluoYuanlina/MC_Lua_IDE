package wiki.ica.xiaomiao.mcluaide.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.mixin.CommandSourceStackAccessor;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptCommandArgument;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** The native server command tree: usable by console, vanilla admin clients and command blocks. */
public final class LuaServerCommands {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final UUID CONSOLE_OWNER = UUID.nameUUIDFromBytes("mc_lua_ide:server_command".getBytes(StandardCharsets.UTF_8));
	private static final SimpleCommandExceptionType EXTRA_PATH = new SimpleCommandExceptionType(
			Component.literal("脚本名称后不能有额外参数，请使用 lua run 子目录/名称"));
	private LuaServerCommands() { }

	public static void register() {
		if (REGISTERED.compareAndSet(false, true))
			CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("lua").requires(LuaServerCommands::allowed)
				.executes(command -> {
					command.getSource().sendSuccess(() -> Component.literal(
							"Lua：/lua list、/lua run <名称>、/lua testrun <名称>、/lua stop；管理员客户端按 U 打开 IDE"), false);
					return 1;
				})
				.then(Commands.literal("list").executes(command -> list(command.getSource())))
				.then(Commands.literal("stop").executes(command -> {
					boolean stopped = ServerScriptManager.stopActive();
					if (!stopped) throw failure("当前没有正在运行的脚本");
					command.getSource().sendSuccess(() -> Component.literal("正在停止 Lua 脚本…"), false);
					return 1;
				}));
		for (String mode : List.of("run", "testrun"))
			root.then(Commands.literal(mode).then(Commands.argument("name", StringArgumentType.greedyString())
					// Use vanilla's argument on the wire: an unmodded client must deserialize this tree too.
					.suggests((command, builder) -> suggestions(command.getSource(), builder))
					.executes(command -> run(command.getSource(), parseName(StringArgumentType.getString(command, "name")),
							mode.equals("testrun")))));
		dispatcher.register(root);
	}

	public static String parseName(String raw) throws CommandSyntaxException {
		StringReader reader = new StringReader(raw);
		String name = LuaScriptCommandArgument.script().parse(reader);
		reader.skipWhitespace();
		if (reader.canRead()) throw EXTRA_PATH.createWithContext(reader);
		return name;
	}

	static boolean allowed(CommandSourceStack source) {
		if (source.permissions().hasPermission(Permissions.COMMANDS_OWNER)) return true;
		CommandSource original = ((CommandSourceStackAccessor) (Object) source).mcLuaIde$getSource();
		if (original instanceof ServerPlayer player && source.getServer().isSingleplayerOwner(player.nameAndId())) return true;
		return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
				&& LuaCommandBlockContext.current(source) != null;
	}

	private static int run(CommandSourceStack source, String name, boolean debug) throws CommandSyntaxException {
		MinecraftServer server = source.getServer();
		LuaCommandBlockContext.Origin block = LuaCommandBlockContext.current(source);
		CommandSource original = ((CommandSourceStackAccessor) (Object) source).mcLuaIde$getSource();
		UUID owner = block != null ? block.owner() : original instanceof ServerPlayer player ? player.getUUID() : CONSOLE_OWNER;
		try {
			String text = ServerScriptManager.read(name); // Reads only validated paths below the server script root.
			ServerScriptManager.start(server, owner, name, text, debug,
					line -> feedback(server, source, block, "[" + name + "] " + line, false),
					result -> {
						String message = result.stopped() ? "脚本已停止：" + name : result.error() == null
								? "脚本执行完成：" + name : "运行失败：" + result.error();
						feedback(server, source, block, message, result.error() != null);
					});
			source.sendSuccess(() -> Component.literal("正在" + (debug ? "调试" : "运行") + "脚本 " + name + "…"), false);
			McLuaIdeMod.LOGGER.info("Lua command source {} started {}.lua (debug={})", source.getTextName(), name, debug);
			return 1;
		} catch (Exception error) {
			// A normal return of zero still counts as a successful command block execution.
			// Throw so vanilla's result callback reports failure to conditional chains too.
			throw failure("启动失败：" + message(error));
		}
	}

	private static int list(CommandSourceStack source) throws CommandSyntaxException {
		try {
			List<String> scripts = ServerScriptManager.listScripts();
			source.sendSuccess(() -> Component.literal(scripts.isEmpty() ? "暂无 Lua 脚本"
					: "Lua 脚本：" + String.join(", ", scripts)), false);
			return scripts.size();
		} catch (Exception error) {
			throw failure("读取脚本失败：" + message(error));
		}
	}

	private static CompletableFuture<Suggestions> suggestions(CommandSourceStack source, SuggestionsBuilder builder) {
		if (!allowed(source)) return builder.buildFuture();
		CompletableFuture<Suggestions> result = new CompletableFuture<>();
		Thread.startVirtualThread(() -> {
			try { result.complete(LuaScriptCommandArgument.complete(ServerScriptManager.listScripts(), builder)); }
			catch (Exception error) { result.complete(builder.build()); }
		});
		return result;
	}

	private static void feedback(MinecraftServer server, CommandSourceStack source,
			LuaCommandBlockContext.Origin block, String text, boolean error) {
		String bounded = text.length() > 8192 ? text.substring(0, 8192) + "…" : text;
		server.execute(() -> {
			Component component = bounded.startsWith("⚠")
					? Component.literal(bounded).withStyle(net.minecraft.ChatFormatting.YELLOW)
					: Component.literal(bounded);
			if (block != null) {
				if (!source.isSilent()) block.output(component);
				if (error) McLuaIdeMod.LOGGER.warn("Command-block Lua execution failed: {}", bounded);
			} else if (error) source.sendFailure(component);
			else source.sendSuccess(() -> component, false);
		});
	}

	private static String message(Exception error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}
	private static CommandSyntaxException failure(String message) {
		return new SimpleCommandExceptionType(Component.literal(message)).create();
	}
}
