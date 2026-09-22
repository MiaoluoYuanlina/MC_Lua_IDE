package wiki.ica.xiaomiao.mcluaide.server;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaApiDescriptorCodec;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handles every privileged IDE operation on the authoritative server. */
public final class LuaServerNetworking {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final int MAX_LISTED_SCRIPTS = 2_048;

	private LuaServerNetworking() {
	}

	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) return;
		ServerLoginNetworking.registerGlobalReceiver(LuaProtocol.CLIENT_CHECK_ID,
				(server, handler, understood, response, synchronizer, sender) -> {
					if (!LuaLimitConfig.current().requireClientMod()) return;
					if (!understood || response == null) {
						sender.disconnect(Component.literal(
								"此服务器要求安装 MC Lua IDE 客户端模组。请安装与服务器匹配的版本后重试。"));
						McLuaIdeMod.LOGGER.info("Rejected a login without the required MC Lua IDE client mod");
						return;
					}
					int clientVersion;
					try {
						clientVersion = response.readVarInt();
					} catch (RuntimeException error) {
						sender.disconnect(Component.literal("MC Lua IDE 客户端握手数据无效，请重新安装匹配版本。"));
						return;
					}
					if (clientVersion != LuaProtocol.VERSION) {
						sender.disconnect(Component.literal("MC Lua IDE 客户端版本不兼容：客户端协议 "
								+ clientVersion + "，服务器协议 " + LuaProtocol.VERSION + "。"));
						McLuaIdeMod.LOGGER.info("Rejected an incompatible MC Lua IDE client protocol {} (server {})",
								clientVersion, LuaProtocol.VERSION);
					}
				});
		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
			if (!LuaLimitConfig.current().requireClientMod()) return;
			FriendlyByteBuf challenge = new FriendlyByteBuf(Unpooled.buffer());
			challenge.writeVarInt(LuaProtocol.VERSION);
			sender.sendPacket(LuaProtocol.CLIENT_CHECK_ID, challenge);
		});
		ServerPlayNetworking.registerGlobalReceiver(LuaProtocol.Request.TYPE, (payload, context) ->
				context.server().execute(() -> handle(payload, context.server(), context.player())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				ServerScriptManager.stopOwnedBy(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerScriptManager.stopActive());
	}

	private static void handle(LuaProtocol.Request request, MinecraftServer server, ServerPlayer player) {
		LuaProtocol.Action action;
		try {
			action = LuaProtocol.Action.byId(request.actionId());
		} catch (RuntimeException error) {
			McLuaIdeMod.LOGGER.warn("Rejected malformed Lua IDE request {} from {}",
					request.actionId(), player.getName().getString());
			send(player, new LuaProtocol.Response(request.requestId(), request.actionId(), false,
					"请求类型无效"));
			return;
		}

		if (!isAdministrator(server, player)) {
			McLuaIdeMod.LOGGER.warn("Denied Lua IDE {} request from non-administrator {} ({})",
					action, player.getName().getString(), player.getUUID());
			send(player, new LuaProtocol.Response(request.requestId(), action, false,
					"权限不足：需要服务器所有者权限（OP 等级 4）"));
			return;
		}

		try {
			switch (action) {
				case ACCESS -> sendSuccess(player, request, Integer.toString(LuaProtocol.VERSION));
				case LIST -> {
					List<String> scripts = ServerScriptManager.listScripts();
					if (scripts.size() > MAX_LISTED_SCRIPTS) scripts = scripts.subList(0, MAX_LISTED_SCRIPTS);
					sendSuccess(player, request, String.join("\n", scripts));
				}
				case READ -> sendSuccess(player, request, ServerScriptManager.read(request.name()));
				case SAVE -> {
					ServerScriptManager.save(request.name(), request.body());
					sendSuccess(player, request, "saved");
				}
				case DELETE -> sendSuccess(player, request,
						Boolean.toString(ServerScriptManager.delete(request.name())));
				case RUN -> startScript(server, player, request);
				case STOP -> sendSuccess(player, request,
						Boolean.toString(ServerScriptManager.stopActive()));
				case GET_LIMITS -> sendSuccess(player, request, LuaLimitConfig.current().encode());
				case SAVE_LIMITS -> {
					LuaLimits limits = LuaLimits.decode(request.body());
					LuaLimitConfig.save(limits);
					sendSuccess(player, request, limits.encode());
					McLuaIdeMod.LOGGER.info("Administrator {} updated the Lua execution limits",
							player.getName().getString());
				}
				case GET_API_DOCUMENTATION -> {
					String documentation = LuaApiDescriptorCodec.encode(McLuaIdeApi.descriptors());
					if (documentation.length() > LuaProtocol.MAX_RESPONSE_CHARS) {
						throw new IllegalStateException("第三方 Lua API 文档总大小超过协议限制");
					}
					sendSuccess(player, request, documentation);
				}
				case GET_COMPONENT_EDITOR_DATA -> sendSuccess(player, request,
						LuaComponentEditorCatalog.describe(server, request.name(), request.body()));
				case VALIDATE_ITEM_COMPONENTS -> sendSuccess(player, request,
						LuaComponentEditorCatalog.validate(server, request.name(), request.body()));
				case GET_REGISTRY_CATALOG -> sendSuccess(player, request,
						LuaRegistryCatalog.describe(server, request.name(), request.body()));
			}
		} catch (Throwable error) {
			String message = safeMessage(error);
			McLuaIdeMod.LOGGER.warn("Lua IDE {} request from {} failed: {}",
					action, player.getName().getString(), message);
			send(player, new LuaProtocol.Response(request.requestId(), action, false, message));
		}
	}

	private static void startScript(MinecraftServer server, ServerPlayer player, LuaProtocol.Request request)
			throws Exception {
		ServerScriptManager.save(request.name(), request.body());
		UUID owner = player.getUUID();
		long runId = request.requestId();
		ServerScriptManager.start(server, owner, request.name(), request.body(),
				line -> sendExecution(server, owner, runId, LuaProtocol.ExecutionKind.OUTPUT, line),
				result -> {
					LuaProtocol.ExecutionKind kind = result.stopped()
							? LuaProtocol.ExecutionKind.STOPPED
							: result.error() == null
									? LuaProtocol.ExecutionKind.FINISHED
									: LuaProtocol.ExecutionKind.FAILED;
					sendExecution(server, owner, runId, kind,
							result.error() == null ? "" : result.error());
				});
		sendSuccess(player, request, "started");
		McLuaIdeMod.LOGGER.info("Administrator {} started Lua script {}.lua",
				player.getName().getString(), request.name());
	}

	private static boolean isAdministrator(MinecraftServer server, ServerPlayer player) {
		return server.isSingleplayerOwner(player.nameAndId())
				|| player.permissions().hasPermission(Permissions.COMMANDS_OWNER);
	}

	private static void sendSuccess(ServerPlayer player, LuaProtocol.Request request, String data) {
		send(player, new LuaProtocol.Response(request.requestId(), request.actionId(), true,
				bounded(data, LuaProtocol.MAX_RESPONSE_CHARS)));
	}

	private static void sendExecution(MinecraftServer server, UUID playerId, long runId,
			LuaProtocol.ExecutionKind kind, String message) {
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) return;
			send(player, new LuaProtocol.ExecutionEvent(runId, kind,
					bounded(message, LuaProtocol.MAX_MESSAGE_CHARS)));
		});
	}

	private static void send(ServerPlayer player, LuaProtocol.Response response) {
		if (ServerPlayNetworking.canSend(player, LuaProtocol.Response.TYPE)) {
			ServerPlayNetworking.send(player, response);
		}
	}

	private static void send(ServerPlayer player, LuaProtocol.ExecutionEvent event) {
		if (ServerPlayNetworking.canSend(player, LuaProtocol.ExecutionEvent.TYPE)) {
			ServerPlayNetworking.send(player, event);
		}
	}

	private static String safeMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		String message = current.getMessage();
		return bounded(message == null ? current.getClass().getSimpleName() : message, 8_192);
	}

	private static String bounded(String value, int maximum) {
		if (value == null) return "";
		return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
	}
}
