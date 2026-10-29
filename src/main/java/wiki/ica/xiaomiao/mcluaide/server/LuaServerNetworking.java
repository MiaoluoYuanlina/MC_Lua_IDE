package wiki.ica.xiaomiao.mcluaide.server;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.MacroFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaApiDescriptorCodec;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
		ServerPlayNetworking.registerGlobalReceiver(LuaProtocol.ClientResourcePackResponse.TYPE,
				(payload, context) -> MinecraftLuaClientApi.receiveResourcePackResponse(context.player(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerScriptManager.stopOwnedBy(handler.getPlayer().getUUID());
			GitHubWorkspaceService.disconnected(handler.getPlayer().getUUID());
			ServerTerminalService.closeAll(handler.getPlayer().getUUID());
			LuaEditPresence.disconnected(handler.getPlayer().getUUID());
			LuaServerPackService.disconnected(server, handler.getPlayer().getUUID());
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> LuaServerPackService.joined(server, handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STARTED.register(LuaServerPackService::started);
		ServerLifecycleEvents.SERVER_STARTED.register(LuaServerMcpService::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(LuaServerMcpService::stopped);
		ServerLifecycleEvents.SERVER_STOPPED.register(LuaServerMcpService::stopped);
		ServerLifecycleEvents.SERVER_STOPPED.register(LuaServerPackService::stopped);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LuaTeamAggression.clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> GitHubWorkspaceService.stopped());
		ServerLifecycleEvents.SERVER_STOPPING.register(ServerScriptManager::shutdownDebugger);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerTerminalService.shutdown());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> LuaWorkspaceSnapshots.start());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> LuaWorkspaceSnapshots.stop());
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) LuaLanguageAssets.invalidate();
			if (success) notifyCatalogChanged(server);
		});
	}

	private static void notifyCatalogChanged(MinecraftServer server) {
		LuaPackIdCatalog.invalidate(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isAdministrator(server, player) && ServerPlayNetworking.canSend(player, LuaProtocol.CatalogChanged.TYPE))
				ServerPlayNetworking.send(player, new LuaProtocol.CatalogChanged());
		}
	}

	static void notifyMcpFileChanged(MinecraftServer server, String name, String before, String source) {
		if (before.equals(source)) return;
		LuaEditPresence.saved(name, "MCP");
		var message = new wiki.ica.xiaomiao.mcluaide.network.LuaMcpFileChanged(name,
				wiki.ica.xiaomiao.mcluaide.network.LuaTextChanges.hash(before), source,
				wiki.ica.xiaomiao.mcluaide.network.LuaTextChanges.between(before, source));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isAdministrator(server, player) && ServerPlayNetworking.canSend(player, message.type()))
				ServerPlayNetworking.send(player, message);
		}
	}

	/** Requests that change files, run code or change server settings: written to the audit log (see LuaAuditLog). */
	private static final java.util.Set<LuaProtocol.Action> AUDITED = java.util.EnumSet.of(
			LuaProtocol.Action.SAVE, LuaProtocol.Action.SAVE_CHECKED, LuaProtocol.Action.DELETE, LuaProtocol.Action.CREATE_DIRECTORY,
			LuaProtocol.Action.DELETE_DIRECTORY, LuaProtocol.Action.CREATE_DATAPACK, LuaProtocol.Action.SAVE_DATAPACK_FILE,
			LuaProtocol.Action.CREATE_DATAPACK_FILE, LuaProtocol.Action.DELETE_DATAPACK_PATH, LuaProtocol.Action.CREATE_DATAPACK_DIRECTORY,
			LuaProtocol.Action.RENAME_DATAPACK_PATH, LuaProtocol.Action.RUN, LuaProtocol.Action.RUN_NORMAL, LuaProtocol.Action.RUN_FUNCTION,
			LuaProtocol.Action.STOP, LuaProtocol.Action.SAVE_LIMITS, LuaProtocol.Action.SAVE_GIT_CONFIG, LuaProtocol.Action.GIT_PUSH,
			LuaProtocol.Action.GIT_COMMIT, LuaProtocol.Action.GIT_RESTORE, LuaProtocol.Action.SET_SERVER_PACK, LuaProtocol.Action.SET_MCP_SERVER,
			LuaProtocol.Action.RELOAD_DATAPACKS, LuaProtocol.Action.DEBUG_CONTROL, LuaProtocol.Action.SET_DEBUG_GAME_PAUSE,
			LuaProtocol.Action.TERMINAL_OPEN, LuaProtocol.Action.TERMINAL_CLOSE, LuaProtocol.Action.CREATE_SNAPSHOT,
			LuaProtocol.Action.RESTORE_SNAPSHOT);

	private static String auditTarget(LuaProtocol.Action action, LuaProtocol.Request request) {
		String target = request.name() == null ? "" : request.name();
		boolean bodyMatters = action == LuaProtocol.Action.RENAME_DATAPACK_PATH || action == LuaProtocol.Action.DEBUG_CONTROL
				|| action == LuaProtocol.Action.SET_DEBUG_GAME_PAUSE;
		if (bodyMatters && request.body() != null && request.body().length() <= 200) target += (target.isEmpty() ? "" : " → ") + request.body();
		return target;
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
			GitHubWorkspaceService.disconnected(player.getUUID());
			ServerTerminalService.closeAll(player.getUUID());
			McLuaIdeMod.LOGGER.warn("Denied Lua IDE {} request from non-administrator {} ({})",
					action, player.getName().getString(), player.getUUID());
			send(player, new LuaProtocol.Response(request.requestId(), action, false,
					"权限不足：需要服务器所有者权限（OP 等级 4）"));
			return;
		}

		if (AUDITED.contains(action)) LuaAuditLog.record(player.getName().getString(), player.getUUID(), action.name(), auditTarget(action, request), "requested");
		try {
			if (GitWorkspaceService.restoring() && switch (action) {
				case SAVE, SAVE_CHECKED, RESTORE_SNAPSHOT, DELETE, CREATE_DIRECTORY, DELETE_DIRECTORY, CREATE_DATAPACK,
						SAVE_DATAPACK_FILE, CREATE_DATAPACK_FILE, DELETE_DATAPACK_PATH,
						CREATE_DATAPACK_DIRECTORY, RENAME_DATAPACK_PATH, RUN, RUN_NORMAL,
						RUN_FUNCTION, RELOAD_DATAPACKS -> true;
				default -> false;
			}) throw new IllegalStateException("Git 正在恢复工作区，请稍后再编辑或运行");
			if (action == LuaProtocol.Action.LIST_DATAPACKS) LuaPackIdCatalog.invalidate(server);
			switch (action) {
				case GET_MCP_SERVER -> sendSuccess(player, request, LuaServerMcpService.status(server).encode());
				case SET_MCP_SERVER -> sendSuccess(player, request, LuaServerMcpService.configure(server, player,
						com.google.gson.JsonParser.parseString(request.body()).getAsJsonObject()).encode());
				case CLEAR_MCP_SERVER_LOGS -> sendSuccess(player, request, LuaServerMcpService.clearLogs(server).encode());
				case MCP_TOOL -> sendSuccess(player, request, wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpServer.JSON.toJson(
						LuaServerMcpService.clientCall(server, player, request.name(), com.google.gson.JsonParser.parseString(request.body()).getAsJsonObject())));
				case ACCESS -> sendSuccess(player, request, Integer.toString(LuaProtocol.VERSION));
				case RELOAD_DATAPACKS -> completeAsync(server, player, request, LuaDatapackReload.reload(server));
				case GET_SERVER_PACK, BEGIN_SERVER_PACK_UPLOAD, SERVER_PACK_UPLOAD_CHUNK,
						FINISH_SERVER_PACK_UPLOAD, CANCEL_SERVER_PACK_UPLOAD, SET_SERVER_PACK -> {
					var service = LuaServerPackService.get(server);
					var future = service.execute(pack -> switch (action) {
						case GET_SERVER_PACK -> pack.describe();
						case BEGIN_SERVER_PACK_UPLOAD -> pack.begin(player.getUUID(), request.body());
						case SERVER_PACK_UPLOAD_CHUNK -> pack.chunk(player.getUUID(), request.name(), request.body());
						case FINISH_SERVER_PACK_UPLOAD -> pack.finish(player.getUUID(), request.name());
						case CANCEL_SERVER_PACK_UPLOAD -> pack.cancel(player.getUUID(), request.name());
						case SET_SERVER_PACK -> pack.configure(request.body());
						default -> throw new IllegalStateException("无效资源包操作");
					});
					if (action == LuaProtocol.Action.SET_SERVER_PACK) {
						future = future.thenCompose(result -> server.submit(() -> {
							service.publishToPlayers(server); return result;
							}));
					}
					completeAsync(server, player, request, future);
				}
				case LIST -> {
					List<String> entries = new java.util.ArrayList<>();
					for (String path : ServerScriptManager.listDirectories()) entries.add("D:" + path);
					for (String path : ServerScriptManager.listScripts()) entries.add("F:" + path);
					if (entries.size() > MAX_LISTED_SCRIPTS)
						throw new IllegalStateException("脚本与目录总数超过 " + MAX_LISTED_SCRIPTS + " 项");
					sendSuccess(player, request, String.join("\n", entries));
				}
				case LIST_SNAPSHOTS -> sendSuccess(player, request, LuaWorkspaceSnapshots.list());
				case CREATE_SNAPSHOT -> completeAsync(server, player, request, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					try { return LuaWorkspaceSnapshots.create("manual", true); } catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
				}));
				case RESTORE_SNAPSHOT -> completeAsync(server, player, request, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					try { return LuaWorkspaceSnapshots.restore(request.name()); } catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
				}).thenCompose(result -> server.submit(() -> { notifyCatalogChanged(server); return result; })));
				case GET_AUDIT_LOG -> sendSuccess(player, request, LuaAuditLog.recent(300));
				case GET_COMMAND_REFERENCE -> sendSuccess(player, request, LuaCommandReference.build(server.getCommands().getDispatcher(), player.createCommandSourceStack()));
				case FILE_PRESENCE -> sendSuccess(player, request, LuaEditPresence.report(player.getUUID(), player.getName().getString(), request.body()));
				case SAVE_CHECKED -> {
					LuaEditPresence.saveChecked(request.name(), player.getName().getString(), request.body());
					sendSuccess(player, request, "saved");
				}
				case READ -> sendSuccess(player, request, ServerScriptManager.read(request.name()));
				case SAVE -> {
					ServerScriptManager.save(request.name(), request.body());
					LuaEditPresence.saved(request.name(), player.getName().getString());
					sendSuccess(player, request, "saved");
				}
				case DELETE -> sendSuccess(player, request,
						Boolean.toString(ServerScriptManager.delete(request.name())));
				case CREATE_DIRECTORY -> {
					ServerScriptManager.createDirectory(request.name());
					sendSuccess(player, request, "created");
				}
				case DELETE_DIRECTORY -> sendSuccess(player, request,
						Boolean.toString(ServerScriptManager.deleteDirectory(request.name())));
				case LIST_DATAPACKS -> sendSuccess(player, request,
						String.join("\n", DatapackWorkspaceManager.list(server)));
				case CREATE_DATAPACK -> {
					DatapackWorkspaceManager.createPack(server, request.name());
					sendSuccess(player, request, "created");
				}
				case READ_DATAPACK_FILE -> sendSuccess(player, request,
						DatapackWorkspaceManager.read(server, request.name()));
				case READ_DATAPACK_PREVIEW -> sendSuccess(player, request,
						DatapackWorkspaceManager.previewChunk(server, request.name(), request.body()));
				case SAVE_DATAPACK_FILE -> {
					DatapackWorkspaceManager.save(server, request.name(), request.body());
					sendSuccess(player, request, "saved");
				}
				case CREATE_DATAPACK_FILE -> {
					String source = request.body().isEmpty()
							? DatapackExampleTemplates.example(server, request.name()) : request.body();
					DatapackWorkspaceManager.createFile(server, request.name(), source);
					sendSuccess(player, request, "created");
				}
				case GET_DIMENSION_JSON -> sendSuccess(player, request,
						DatapackDimensionEditorData.read(server, request.name()));
				case VALIDATE_DIMENSION_JSON -> sendSuccess(player, request,
						DatapackDimensionEditorData.validate(server, request.body()));
				case GET_DATAPACK_JSON_OPTIONS -> sendSuccess(player, request,
						DatapackJsonEditorData.options(server, request.name()));
				case GET_DATAPACK_JSON_SOURCE -> sendSuccess(player, request,
						DatapackJsonEditorData.source(server, request.name(), request.body()));
				case VALIDATE_DATAPACK_JSON -> sendSuccess(player, request,
						DatapackJsonEditorData.validate(server, request.name(), request.body()));
				case GET_DATAPACK_JSON_CATEGORIES -> sendSuccess(player, request,
						DatapackJsonEditorData.categories(server));
				case DELETE_DATAPACK_PATH -> sendSuccess(player, request,
						Boolean.toString(DatapackWorkspaceManager.delete(server, request.name())));
				case CREATE_DATAPACK_DIRECTORY -> {
					DatapackWorkspaceManager.createDirectory(server, request.name());
					sendSuccess(player, request, "created");
				}
				case RENAME_DATAPACK_PATH -> {
					DatapackWorkspaceManager.rename(server, request.name(), request.body());
					sendSuccess(player, request, "renamed");
				}
				case RUN -> startScript(server, player, request, true);
				case RUN_NORMAL -> startScript(server, player, request, false);
				case RUN_FUNCTION -> runFunction(server, player, request);
				case GET_GIT_CONFIG -> sendSuccess(player, request, GitWorkspaceService.load().encode());
				case GITHUB_ACCOUNT, GITHUB_CLIENT_ID, GITHUB_LOGIN, GITHUB_POLL, GITHUB_LOGOUT,
						GITHUB_REPOSITORIES, GITHUB_SELECT_REPOSITORY, GITHUB_CREATE_REPOSITORY ->
						completeAsync(server, player, request, GitHubWorkspaceService.request(player.getUUID(), action, request.body()));
				case GET_GIT_HISTORY -> completeAsync(server, player, request,
						GitWorkspaceService.execute(server, request.name(), "history", Integer.parseInt(request.body()), player.getUUID()));
				case GET_GIT_DETAIL -> completeAsync(server, player, request,
						GitWorkspaceService.execute(server, request.name(), "detail", wiki.ica.xiaomiao.mcluaide.network.LuaGit.JSON
								.fromJson(request.body(), wiki.ica.xiaomiao.mcluaide.network.LuaGit.DetailRequest.class), player.getUUID()));
				case GIT_COMMIT -> completeAsync(server, player, request,
						GitWorkspaceService.execute(server, request.name(), "commit", wiki.ica.xiaomiao.mcluaide.network.LuaGit.JSON
								.fromJson(request.body(), wiki.ica.xiaomiao.mcluaide.network.LuaGit.CommitRequest.class), player.getUUID()));
				case GIT_RESTORE -> completeAsync(server, player, request,
						GitWorkspaceService.execute(server, request.name(), "restore", request.body(), player.getUUID()));
				case SAVE_GIT_CONFIG -> {
					GitWorkspaceService.save(request.name(), request.body());
					sendSuccess(player, request, "saved");
				}
				case GIT_PUSH -> GitWorkspaceService.push(server, request.name()).whenComplete((result, error) ->
						server.execute(() -> {
							if (error == null) sendSuccess(player, request, result);
							else {
								McLuaIdeMod.LOGGER.warn("Git push requested by {} failed: {}",
										player.getName().getString(), safeMessage(error));
								send(player, new LuaProtocol.Response(request.requestId(), request.actionId(),
										false, safeMessage(error)));
							}
						}));
				case STOP -> sendSuccess(player, request,
						Boolean.toString(ServerScriptManager.stopActive()));
				case GET_DEBUG_VARIABLES -> sendSuccess(player, request,
						ServerScriptManager.debugSnapshot());
				case SET_BREAKPOINTS -> {
					ServerScriptManager.configureBreakpoints(player.getUUID(), request.body());
					sendSuccess(player, request, "saved");
				}
				case GET_DEBUG_STATE -> sendSuccess(player, request, ServerScriptManager.debuggerState());
				case SET_DEBUG_GAME_PAUSE -> {
					if (!request.body().equals("true") && !request.body().equals("false"))
						throw new IllegalArgumentException("断点暂停游戏开关必须为 true 或 false");
					sendSuccess(player, request, ServerScriptManager.setDebuggerGamePause(server, Boolean.parseBoolean(request.body())));
				}
				case TERMINAL_OPEN -> completeAsync(server, player, request, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					try { return ServerTerminalService.open(player.getUUID(), player.getName().getString(), request.body()); }
					catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
				}));
				case TERMINAL_IO -> sendSuccess(player, request, ServerTerminalService.io(player.getUUID(), request.name(), request.body()));
				case TERMINAL_CLOSE -> sendSuccess(player, request, ServerTerminalService.close(player.getUUID(), request.name()));
				case DEBUG_EVALUATE -> completeAsync(server, player, request, ServerScriptManager.evaluateDebugger(request.name(), request.body()));
				case DEBUG_CONTROL -> sendSuccess(player, request,
						ServerScriptManager.controlDebugger(request.name(), request.body()));
				case SET_DEBUG_PLAYER_MOVEMENT -> {
					if (!request.body().equals("true") && !request.body().equals("false"))
						throw new IllegalArgumentException("暂停时允许玩家移动开关必须为 true 或 false");
					sendSuccess(player, request, ServerScriptManager.setDebuggerPlayerMovement(server, Boolean.parseBoolean(request.body())));
				}
				case DEBUG_CAMERA_MODE -> {
					if (!request.body().equals("true") && !request.body().equals("false"))
						throw new IllegalArgumentException("自由摄像机开关必须为 true 或 false");
					sendSuccess(player, request, Boolean.toString(ServerScriptManager.setDebuggerCamera(player, Boolean.parseBoolean(request.body()))));
				}
				case GET_LIMITS -> sendSuccess(player, request, LuaLimitConfig.current().encode());
				case SAVE_LIMITS -> {
					LuaLimits limits = LuaLimits.decode(request.body());
					LuaLimitConfig.save(limits);
					sendSuccess(player, request, limits.encode());
					McLuaIdeMod.LOGGER.info("Administrator {} updated the Lua execution limits",
							player.getName().getString());
				}
				case GET_API_DOCUMENTATION -> {
					String documentation = LuaApiDescriptorCodec.encode(new LuaApiDescriptorCodec.Documentation(
							McLuaIdeApi.descriptors(), McLuaIdeDocsApi.pages()));
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
			if (java.util.Set.of(LuaProtocol.Action.CREATE_DATAPACK, LuaProtocol.Action.SAVE_DATAPACK_FILE,
					LuaProtocol.Action.CREATE_DATAPACK_FILE, LuaProtocol.Action.DELETE_DATAPACK_PATH,
					LuaProtocol.Action.RENAME_DATAPACK_PATH, LuaProtocol.Action.RUN_FUNCTION).contains(action)) notifyCatalogChanged(server);
		} catch (Throwable error) {
			String message = safeMessage(error);
			if (AUDITED.contains(action)) LuaAuditLog.record(player.getName().getString(), player.getUUID(), action.name(), auditTarget(action, request), "failed: " + message);
			McLuaIdeMod.LOGGER.warn("Lua IDE {} request from {} failed: {}",
					action, player.getName().getString(), message);
			send(player, new LuaProtocol.Response(request.requestId(), action, false, message));
		}
	}

	private static void startScript(MinecraftServer server, ServerPlayer player, LuaProtocol.Request request,
			boolean debugEnabled)
			throws Exception {
		ServerScriptManager.save(request.name(), request.body());
		UUID owner = player.getUUID();
		long runId = request.requestId();
		ServerScriptManager.start(server, owner, request.name(), request.body(), debugEnabled,
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
		McLuaIdeMod.LOGGER.info("Administrator {} started Lua script {}.lua (debug={})",
				player.getName().getString(), request.name(), debugEnabled);
	}

	private static void completeAsync(MinecraftServer server, ServerPlayer player, LuaProtocol.Request request,
			java.util.concurrent.CompletableFuture<String> future) {
		future.whenComplete((result, error) -> server.execute(() -> {
			if (server.getPlayerList().getPlayer(player.getUUID()) != player) return;
			if (!isAdministrator(server, player)) { GitHubWorkspaceService.disconnected(player.getUUID()); ServerTerminalService.closeAll(player.getUUID()); return; }
			if (error == null) {
				if (request.actionId() == LuaProtocol.Action.GIT_RESTORE.id()) notifyCatalogChanged(server);
				sendSuccess(player, request, result);
			}
			else {
				LuaProtocol.Action failed = LuaProtocol.Action.byId(request.actionId());
				if (AUDITED.contains(failed)) LuaAuditLog.record(player.getName().getString(), player.getUUID(), failed.name(), auditTarget(failed, request), "failed: " + safeMessage(error));
				send(player, new LuaProtocol.Response(request.requestId(), request.actionId(), false, safeMessage(error)));
			}
		}));
	}

	private static void runFunction(MinecraftServer server, ServerPlayer player, LuaProtocol.Request request)
			throws Exception {
		var id = DatapackWorkspaceManager.functionId(request.name());
		var manager = server.getFunctions();
		var source = player.createCommandSourceStack();
		CommandFunction<net.minecraft.commands.CommandSourceStack> function = CommandFunction.fromLines(
				id, manager.getDispatcher(), source, request.body().lines().toList());
		if (function instanceof MacroFunction<?>)
			throw new IllegalArgumentException("宏函数需要参数；目前请通过游戏内 /function 指令运行宏函数");
		DatapackWorkspaceManager.save(server, request.name(), request.body());
		UUID owner = player.getUUID();
		long runId = request.requestId();
		AtomicInteger outputCount = new AtomicInteger();
		CommandSource output = new CommandSource() {
			@Override public void sendSystemMessage(Component message) {
				int count = outputCount.incrementAndGet();
				if (count <= 200) sendExecution(server, owner, runId,
						LuaProtocol.ExecutionKind.OUTPUT, message.getString());
				else if (count == 201) sendExecution(server, owner, runId,
						LuaProtocol.ExecutionKind.OUTPUT, "后续命令反馈过多，已停止显示");
			}
			@Override public boolean acceptsSuccess() { return true; }
			@Override public boolean acceptsFailure() { return true; }
			@Override public boolean shouldInformAdmins() { return false; }
		};
		sendSuccess(player, request, "started");
		try {
			manager.execute(function, source.withSource(output));
			sendExecution(server, owner, runId, LuaProtocol.ExecutionKind.FINISHED, "");
		} catch (Throwable error) {
			McLuaIdeMod.LOGGER.warn("Administrator {} failed to run function {}",
					player.getName().getString(), id, error);
			sendExecution(server, owner, runId, LuaProtocol.ExecutionKind.FAILED, safeMessage(error));
		}
	}

	static boolean isAdministrator(MinecraftServer server, ServerPlayer player) {
		return server.isSingleplayerOwner(player.nameAndId())
				|| player.permissions().hasPermission(Permissions.COMMANDS_OWNER);
	}

	private static void sendSuccess(ServerPlayer player, LuaProtocol.Request request, String data) {
		int maximum = LuaProtocol.responseTextLimit(request.actionId());
		if (maximum == LuaProtocol.MAX_SCRIPT_CHARS && data != null && data.length() > maximum) {
			throw new IllegalArgumentException("文件超过 " + maximum + " 个字符，不能完整读取");
		}
		send(player, new LuaProtocol.Response(request.requestId(), request.actionId(), true,
				bounded(data, maximum)));
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
		return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
	}
}
