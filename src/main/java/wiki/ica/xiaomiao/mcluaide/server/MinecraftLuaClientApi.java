package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Client-only actions gated by the server's mandatory-client policy. */
final class MinecraftLuaClientApi {
	private static final int API_TIMEOUT_SECONDS = 10;
	private static final int MAX_URL_CHARS = 2_048;
	private static final int RESOURCE_PACK_TIMEOUT_SECONDS = 45;
	private static final AtomicLong NEXT_RESOURCE_PACK_REQUEST = new AtomicLong(1);
	private static final Map<Long, PendingResourcePackRequest> RESOURCE_PACK_REQUESTS = new ConcurrentHashMap<>();

	private MinecraftLuaClientApi() {
	}

	static void install(LuaTable mc, MinecraftServer server, ServerScriptManager.ScriptExecution execution) {
		LuaTable api = new LuaTable();
		bind(api, "available", args -> LuaValue.valueOf(available(server, parseUuid(args.checkjstring(1)))));
		bind(api, "info", args -> {
			UUID playerUuid = parseUuid(args.checkjstring(1));
			ClientInfo info = onServer(server, current -> clientInfo(current, playerUuid));
			LuaTable result = new LuaTable();
			result.set("playerUuid", playerUuid.toString());
			result.set("online", LuaValue.valueOf(info.online()));
			result.set("required", LuaValue.valueOf(LuaLimitConfig.current().requireClientMod()));
			result.set("available", LuaValue.valueOf(info.available()));
			result.set("protocol", LuaProtocol.VERSION);
			return result;
		});
		bind(api, "list", args -> onServer(server, current -> {
			LuaTable result = new LuaTable();
			List<ServerPlayer> players = current.getPlayerList().getPlayers().stream()
					.filter(player -> ServerPlayNetworking.canSend(player, LuaProtocol.ClientCommand.TYPE))
					.toList();
			for (int index = 0; index < players.size(); index++) {
				ServerPlayer player = players.get(index);
				LuaTable entry = new LuaTable();
				entry.set("uuid", player.getUUID().toString());
				entry.set("name", player.getName().getString());
				entry.set("protocol", LuaProtocol.VERSION);
				result.set(index + 1, entry);
			}
			return result;
		}));
		bind(api, "message", args -> send(server, execution, args.checkjstring(1),
				LuaProtocol.ClientAction.MESSAGE, componentJson(server, args.arg(2), "text"), ""));
		bind(api, "toast", args -> send(server, execution, args.checkjstring(1),
				LuaProtocol.ClientAction.TOAST, componentJson(server, args.arg(2), "title"),
				componentJson(server, args.arg(3), "message")));
		bind(api, "notice", args -> send(server, execution, args.checkjstring(1),
				LuaProtocol.ClientAction.NOTICE, componentJson(server, args.arg(2), "title"),
				componentJson(server, args.arg(3), "body")));
		bind(api, "closeNotice", args -> send(server, execution, args.checkjstring(1),
				LuaProtocol.ClientAction.CLOSE_NOTICE, "", ""));
		bind(api, "openUrlPrompt", args -> {
			String url = limited(args.checkjstring(2), "url", MAX_URL_CHARS);
			validateUrl(url);
			return send(server, execution, args.checkjstring(1),
					LuaProtocol.ClientAction.OPEN_URL_PROMPT, url, "");
		});
		bind(api, "openIde", args -> send(server, execution, args.checkjstring(1),
				LuaProtocol.ClientAction.OPEN_IDE, "", ""));
		bind(api, "hasResourcePack", args -> LuaValue.valueOf(resourcePackRequest(server, execution,
				args.checkjstring(1), LuaProtocol.ResourcePackAction.HAS, args.checkjstring(2))));
		bind(api, "enableResourcePack", args -> LuaValue.valueOf(resourcePackRequest(server, execution,
				args.checkjstring(1), LuaProtocol.ResourcePackAction.ENABLE, args.checkjstring(2))));
		mc.set("Client", api);
		mc.set("client", api);
	}

	private static LuaValue send(MinecraftServer server, ServerScriptManager.ScriptExecution execution,
			String rawUuid, LuaProtocol.ClientAction action, String first, String second) {
		UUID playerUuid = parseUuid(rawUuid);
		boolean delivered = onServer(server, current -> {
			requireEnabled(execution);
			ServerPlayer player = current.getPlayerList().getPlayer(playerUuid);
			if (player == null) throw new LuaRecoverableError("玩家不在线：" + playerUuid);
			if (!ServerPlayNetworking.canSend(player, LuaProtocol.ClientCommand.TYPE)) {
				throw new LuaError("目标玩家未安装兼容的 MC Lua IDE 客户端模组：" + playerUuid);
			}
			ServerPlayNetworking.send(player, new LuaProtocol.ClientCommand(action, first, second));
			return true;
		});
		return LuaValue.valueOf(delivered);
	}

	private static boolean available(MinecraftServer server, UUID playerUuid) {
		if (!LuaLimitConfig.current().requireClientMod()) return false;
		return onServer(server, current -> clientInfo(current, playerUuid).available());
	}

	private static boolean resourcePackRequest(MinecraftServer server,
			ServerScriptManager.ScriptExecution execution, String rawUuid,
			LuaProtocol.ResourcePackAction action, String rawPackName) {
		UUID playerUuid = parseUuid(rawUuid);
		String packName = limited(rawPackName.strip(), "材质包名称", LuaProtocol.MAX_NAME_CHARS);
		if (packName.isEmpty()) throw new LuaError("材质包名称不能为空");
		long requestId = NEXT_RESOURCE_PACK_REQUEST.getAndIncrement();
		CompletableFuture<LuaProtocol.ClientResourcePackResponse> future = new CompletableFuture<>();
		RESOURCE_PACK_REQUESTS.put(requestId, new PendingResourcePackRequest(playerUuid, future));
		try {
			onServer(server, current -> {
				requireEnabled(execution);
				ServerPlayer player = current.getPlayerList().getPlayer(playerUuid);
				if (player == null) throw new LuaRecoverableError("玩家不在线：" + playerUuid);
				if (!ServerPlayNetworking.canSend(player, LuaProtocol.ClientResourcePackRequest.TYPE)) {
					throw new LuaError("目标玩家未安装支持材质包 API 的 MC Lua IDE 客户端模组：" + playerUuid);
				}
				ServerPlayNetworking.send(player,
						new LuaProtocol.ClientResourcePackRequest(requestId, action, packName));
				return true;
			});
			LuaProtocol.ClientResourcePackResponse response = future.get(
					RESOURCE_PACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!response.success()) throw new LuaError(response.message().isBlank()
					? "客户端材质包操作失败" : response.message());
			return response.enabled();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new LuaError("脚本已停止");
		} catch (TimeoutException error) {
			throw new LuaError("等待玩家客户端处理材质包超时");
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			throw cause instanceof LuaError luaError ? luaError
					: new LuaError(cause == null || cause.getMessage() == null ? "客户端材质包操作失败" : cause.getMessage());
		} finally {
			RESOURCE_PACK_REQUESTS.remove(requestId);
		}
	}

	static void receiveResourcePackResponse(ServerPlayer player, LuaProtocol.ClientResourcePackResponse response) {
		PendingResourcePackRequest pending = RESOURCE_PACK_REQUESTS.get(response.requestId());
		if (pending == null || !pending.playerUuid().equals(player.getUUID())) return;
		if (RESOURCE_PACK_REQUESTS.remove(response.requestId(), pending)) pending.future().complete(response);
	}

	private static ClientInfo clientInfo(MinecraftServer server, UUID playerUuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
		return new ClientInfo(player != null,
				player != null && LuaLimitConfig.current().requireClientMod()
						&& ServerPlayNetworking.canSend(player, LuaProtocol.ClientCommand.TYPE));
	}

	private static void requireEnabled(ServerScriptManager.ScriptExecution execution) {
		requireEnabled(execution, "MC.Client");
	}

	static void requireEnabled(ServerScriptManager.ScriptExecution execution, String feature) {
		if (!execution.limits().requireClientMod() || !LuaLimitConfig.current().requireClientMod()) {
			throw new LuaError(feature + "仅在“Mod Menu → MC Lua IDE → 配置 → 服务器接入 → 要求玩家安装客户端模组”开启后可用；请开启后重新启动脚本");
		}
	}

	private static void validateUrl(String raw) {
		try {
			URI uri = URI.create(raw);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException error) {
			throw new LuaError("url 必须是有效的 http:// 或 https:// 地址");
		}
	}

	private static String limited(String value, String label) {
		return limited(value, label, LuaProtocol.MAX_CLIENT_TEXT_CHARS);
	}

	private static String componentJson(MinecraftServer server, LuaValue value, String label) {
		return limited(LuaTextComponentCodec.commandJson(server, value), label);
	}

	private static String limited(String value, String label, int maximum) {
		if (value.length() > maximum) throw new LuaError(label + " 不能超过 " + maximum + " 个字符");
		return value;
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new LuaError("玩家 UUID 格式错误：" + raw);
		}
	}

	private static <T> T onServer(MinecraftServer server, Function<MinecraftServer, T> action) {
		if (server.isSameThread()) return action.apply(server);
		CompletableFuture<T> future = server.submit(() -> action.apply(server));
		try {
			return future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new LuaError("脚本已停止");
		} catch (TimeoutException error) {
			throw new LuaError("等待 Minecraft 服务端响应超时");
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			throw cause instanceof LuaError luaError ? luaError
					: new LuaError(cause == null || cause.getMessage() == null ? "客户端操作失败" : cause.getMessage());
		}
	}

	private static void bind(LuaTable table, String name, LuaCallable callable) {
		table.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return callable.invoke(args);
			}
		});
	}

	@FunctionalInterface
	private interface LuaCallable {
		Varargs invoke(Varargs args);
	}

	private record ClientInfo(boolean online, boolean available) {
	}

	private record PendingResourcePackRequest(UUID playerUuid,
			CompletableFuture<LuaProtocol.ClientResourcePackResponse> future) {
	}
}
