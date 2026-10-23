package wiki.ica.xiaomiao.mcluaide.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.lib.jse.JsePlatform;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaApiDescriptorCodec;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Asynchronous client facade for the privileged server-side script service. */
public final class ScriptManager {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong(1);
	private static final Map<Long, CompletableFuture<LuaProtocol.Response>> PENDING =
			new ConcurrentHashMap<>();
	private static final Map<Long, ScriptExecution> EXECUTIONS = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<List<LuaInsertCatalog.Entry>>> REGISTRY_CATALOGS =
			new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<List<LuaRegistryCatalogData.RegistryType>>> REGISTRY_TYPES =
			new ConcurrentHashMap<>();
	private static volatile ScriptExecution activeExecution;
	private static volatile ScriptExecution lastExecution;
	private static final java.util.concurrent.atomic.AtomicInteger CATALOG_REVISION = new java.util.concurrent.atomic.AtomicInteger();
	static int catalogRevision() { return CATALOG_REVISION.get(); }
	static void invalidateAllRegistryCatalogs() {
		REGISTRY_CATALOGS.clear(); REGISTRY_TYPES.clear();
		LuaResourceAssetCatalog.invalidate();
		CATALOG_REVISION.incrementAndGet();
	}

	private ScriptManager() {
	}

	/**
	 * Development-only self tests are selected by MC_LUA_IDE_*_TEST environment variables. The probe classes are not part of
	 * the released jar, so they are only touched when one of those variables is set.
	 */
	private static void runDevelopmentProbes() {
		if (System.getenv().keySet().stream().noneMatch(name -> name.startsWith("MC_LUA_IDE_") && name.endsWith("_TEST"))) return;
		try { LuaIntegrationProbe.runIfEnabled(); }
		catch (LinkageError missing) { McLuaIdeMod.LOGGER.warn("MC_LUA_IDE_*_TEST is set but the probe classes are not available in this build"); }
	}

	public static void registerNetworking() {
		if (!REGISTERED.compareAndSet(false, true)) return;
		ClientLoginNetworking.registerGlobalReceiver(LuaProtocol.CLIENT_CHECK_ID,
				(client, handler, challenge, listenerAdder) -> {
					int serverVersion = challenge.readVarInt();
					McLuaIdeMod.LOGGER.info("Server requires MC Lua IDE client protocol {}", serverVersion);
					FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
					response.writeVarInt(LuaProtocol.VERSION);
					return CompletableFuture.completedFuture(response);
				});
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.Response.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.CatalogChanged.TYPE, (payload, context) ->
				context.client().execute(ScriptManager::invalidateAllRegistryCatalogs));
		ClientPlayNetworking.registerGlobalReceiver(wiki.ica.xiaomiao.mcluaide.network.LuaMcpFileChanged.TYPE, (payload, context) -> {
			var connection = context.client().getConnection();
			context.client().execute(() -> {
				if (context.client().getConnection() == connection && connection != null) LuaMcpFileSync.receive(payload);
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.ExecutionEvent.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.ClientCommand.TYPE, (payload, context) ->
				context.client().execute(() -> LuaClientCommandHandler.handle(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(wiki.ica.xiaomiao.mcluaide.network.LuaSeekSound.TYPE, (payload, context) ->
				context.client().execute(() -> LuaSeekSoundPlayer.play(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.ClientResourcePackRequest.TYPE, (payload, context) ->
				context.client().execute(() -> LuaClientResourcePackHandler.handle(context.client(), payload)));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (!isServerAvailable()) {
				McLuaIdeMod.LOGGER.info("Connected server does not expose the MC Lua IDE protocol");
				return;
			}
			checkAccess().whenComplete((ignored, failure) -> client.execute(() -> {
				if (failure == null) {
					McLuaIdeMod.LOGGER.info("MC Lua IDE administrator protocol ready");
					LuaClientMcpService.restore();
					runDevelopmentProbes();
				}
				else McLuaIdeMod.LOGGER.info("MC Lua IDE server available but access was denied: {}",
						message(failure));
			}));
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> disconnect("已与服务器断开连接")));
	}

	public static boolean isServerAvailable() {
		try {
			return ClientPlayNetworking.canSend(LuaProtocol.Request.TYPE);
		} catch (IllegalArgumentException | IllegalStateException ignored) {
			return false;
		}
	}

	public static CompletableFuture<Void> checkAccess() {
		return request(LuaProtocol.Action.ACCESS, "", "").thenApply(response -> {
			int version;
			try {
				version = Integer.parseInt(response.data());
			} catch (NumberFormatException error) {
				throw new IllegalStateException("服务器返回了无效的协议版本");
			}
			if (version != LuaProtocol.VERSION) {
				throw new IllegalStateException("客户端/服务端协议版本不一致：客户端 "
						+ LuaProtocol.VERSION + "，服务端 " + version);
			}
			return null;
		});
	}

	public static CompletableFuture<List<String>> listScripts() {
		return listScriptTree().thenApply(entries -> entries.stream()
				.filter(entry -> !entry.directory())
				.map(ScriptEntry::path)
				.toList());
	}

	public static CompletableFuture<List<ScriptEntry>> listScriptTree() {
		return request(LuaProtocol.Action.LIST, "", "").thenApply(response -> {
			if (response.data().isBlank()) return List.of();
			return Arrays.stream(response.data().split("\\n"))
					.filter(line -> line.length() > 2 && line.charAt(1) == ':'
							&& (line.charAt(0) == 'D' || line.charAt(0) == 'F'))
					.map(line -> new ScriptEntry(line.substring(2), line.charAt(0) == 'D'))
					.filter(entry -> isValidName(entry.path()))
					.sorted((first, second) -> String.CASE_INSENSITIVE_ORDER
							.compare(first.path(), second.path()))
					.toList();
		});
	}

	public static CompletableFuture<Void> createDirectory(String name) {
		return request(LuaProtocol.Action.CREATE_DIRECTORY, requireName(name), "")
				.thenApply(ignored -> null);
	}

	public static CompletableFuture<Boolean> deleteDirectory(String name) {
		return request(LuaProtocol.Action.DELETE_DIRECTORY, requireName(name), "")
				.thenApply(response -> Boolean.parseBoolean(response.data()));
	}

	public record ScriptEntry(String path, boolean directory) { }

	public static CompletableFuture<List<ScriptEntry>> listDatapackTree() {
		return request(LuaProtocol.Action.LIST_DATAPACKS, "", "").thenApply(response -> {
			if (response.data().isBlank()) return List.of();
			return Arrays.stream(response.data().split("\\n"))
					.filter(line -> line.length() > 2 && line.charAt(1) == ':'
							&& (line.charAt(0) == 'D' || line.charAt(0) == 'F'))
					.map(line -> new ScriptEntry(line.substring(2), line.charAt(0) == 'D'))
					.sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.path(), second.path()))
					.toList();
		});
	}

	public static CompletableFuture<Void> createDatapack(String name) {
		return request(LuaProtocol.Action.CREATE_DATAPACK, name, "").thenApply(ignored -> null);
	}

	public static CompletableFuture<String> readDatapackFile(String name) {
		return request(LuaProtocol.Action.READ_DATAPACK_FILE, name, "").thenApply(LuaProtocol.Response::data);
	}

	public static CompletableFuture<byte[]> readDatapackPreview(String name) {
		return readDatapackPreviewChunk(name, 0, -1, new ByteArrayOutputStream());
	}

	private static CompletableFuture<byte[]> readDatapackPreviewChunk(String name, int offset,
			int expectedSize, ByteArrayOutputStream output) {
		return request(LuaProtocol.Action.READ_DATAPACK_PREVIEW, name, Integer.toString(offset))
				.thenCompose(response -> {
			String data = response.data();
			int newline = data.indexOf('\n');
			if (newline < 0) return CompletableFuture.failedFuture(
						new IllegalStateException("预览响应格式无效"));
			try {
				int size = Integer.parseInt(data.substring(0, newline));
				if (size < 0 || size > 8 * 1024 * 1024 || expectedSize >= 0 && size != expectedSize)
					throw new IllegalStateException("预览文件大小已改变或超过限制");
				byte[] chunk = Base64.getDecoder().decode(data.substring(newline + 1));
				if (chunk.length > 192 * 1024 || offset + chunk.length > size
						|| chunk.length == 0 && offset < size)
					throw new IllegalStateException("预览数据不完整");
				output.writeBytes(chunk);
				return offset + chunk.length == size
						? CompletableFuture.completedFuture(output.toByteArray())
						: readDatapackPreviewChunk(name, offset + chunk.length, size, output);
			} catch (RuntimeException error) {
				return CompletableFuture.failedFuture(error);
			}
		});
	}

	public static CompletableFuture<Void> saveDatapackFile(String name, String source) {
		requireSource(source);
		return request(LuaProtocol.Action.SAVE_DATAPACK_FILE, name, source).thenApply(ignored -> null);
	}

	public record GitConfig(String repository, String branch, LuaGit.Mode mode) { }

	public static CompletableFuture<String> reloadDatapacks() {
		return request(LuaProtocol.Action.RELOAD_DATAPACKS, "", "").thenApply(LuaProtocol.Response::data);
	}

	public static CompletableFuture<wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Status> serverResourcePack() {
		return request(LuaProtocol.Action.GET_SERVER_PACK, "", "")
				.thenApply(response -> wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Status.decode(response.data()));
	}
	public static CompletableFuture<wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Status> setServerResourcePack(
			wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Settings settings) {
		return request(LuaProtocol.Action.SET_SERVER_PACK, "", settings.encode())
				.thenApply(response -> wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Status.decode(response.data()));
	}

	/** Stream bounded chunks off the render thread; packets are always sent on the client thread. */
	public static final class PackUpload {
		public final CompletableFuture<wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Status> result = new CompletableFuture<>();
		private final AtomicBoolean cancelled = new AtomicBoolean();
		public volatile long sent, total;
		public volatile String phase = "正在校验文件…";
		public void cancel() { cancelled.set(true); }
		public boolean cancelled() { return cancelled.get(); }
	}
	public static PackUpload uploadServerResourcePack(java.nio.file.Path path) {
		PackUpload job = new PackUpload();
		Thread.ofVirtual().name("mc-lua-pack-upload").start(() -> {
			String session = null;
			try {
				if (!java.nio.file.Files.isRegularFile(path)) throw new java.io.IOException("资源包 ZIP 文件不存在");
				job.total = java.nio.file.Files.size(path);
				if (job.total <= 0 || job.total > wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.MAX_BYTES)
					throw new java.io.IOException("ZIP 资源包最大为 128 MiB");
				var digest = java.security.MessageDigest.getInstance("SHA-256");
				try (var input = java.nio.file.Files.newInputStream(path)) {
					byte[] buffer = new byte[65536]; int bytes;
					while ((bytes = input.read(buffer)) >= 0) {
						if (job.cancelled()) throw new java.util.concurrent.CancellationException("已取消上传");
						digest.update(buffer, 0, bytes);
					}
				}
				var info = new wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Upload(path.getFileName().toString(), job.total,
						java.util.HexFormat.of().formatHex(digest.digest()));
				if (job.cancelled()) throw new java.util.concurrent.CancellationException("已取消上传");
				session = requestOnClient(LuaProtocol.Action.BEGIN_SERVER_PACK_UPLOAD, "", info.encode()).get(60, TimeUnit.SECONDS).data();
				job.phase = "正在上传到服务器…";
				try (var input = java.nio.file.Files.newInputStream(path)) {
					while (job.sent < job.total) {
						if (job.cancelled()) throw new java.util.concurrent.CancellationException("已取消上传");
						byte[] bytes = input.readNBytes((int) Math.min(wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.CHUNK_BYTES, job.total - job.sent));
						if (bytes.length == 0) throw new java.io.IOException("上传时文件内容发生变化，请重试");
						long received = Long.parseLong(requestOnClient(LuaProtocol.Action.SERVER_PACK_UPLOAD_CHUNK, session,
								job.sent + "\n" + Base64.getEncoder().encodeToString(bytes)).get(60, TimeUnit.SECONDS).data());
						if (received != job.sent + bytes.length) throw new java.io.IOException("服务器返回的上传进度不正确");
						job.sent = received;
					}
					if (input.read() >= 0) throw new java.io.IOException("上传时文件大小发生变化，请重试");
				}
				if (job.cancelled()) throw new java.util.concurrent.CancellationException("已取消上传");
				job.phase = "正在检查资源包安全性…";
				var response = requestOnClient(LuaProtocol.Action.FINISH_SERVER_PACK_UPLOAD, session, "").get(180, TimeUnit.SECONDS);
				job.result.complete(wiki.ica.xiaomiao.mcluaide.network.LuaServerPack.Status.decode(response.data()));
			} catch (Exception error) {
				if (session != null) requestOnClient(LuaProtocol.Action.CANCEL_SERVER_PACK_UPLOAD, session, "").exceptionally(ignored -> null);
				job.result.completeExceptionally(error);
			}
		});
		return job;
	}
	private static CompletableFuture<LuaProtocol.Response> requestOnClient(LuaProtocol.Action action, String name, String body) {
		CompletableFuture<LuaProtocol.Response> result = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> request(action, name, body).whenComplete((response, error) -> {
			if (error == null) result.complete(response); else result.completeExceptionally(error);
		}));
		return result;
	}

	public static CompletableFuture<GitConfig> gitConfig() {
		return request(LuaProtocol.Action.GET_GIT_CONFIG, "", "").thenApply(response -> {
			String[] parts = response.data().split("\\n", -1);
			return new GitConfig(parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : "main",
					parts.length > 2 ? LuaGit.Mode.valueOf(parts[2]) : LuaGit.Mode.GITHUB);
		});
	}

	public static CompletableFuture<Void> saveGitConfig(String repository, String branch, LuaGit.Mode mode) {
		return request(LuaProtocol.Action.SAVE_GIT_CONFIG, repository, branch + "\n" + mode).thenApply(ignored -> null);
	}

	public static CompletableFuture<LuaGit.History> gitHistory(LuaGit.Mode mode, int offset) {
		return request(LuaProtocol.Action.GET_GIT_HISTORY, mode.name(), Integer.toString(offset))
				.thenApply(response -> LuaGit.JSON.fromJson(response.data(), LuaGit.History.class));
	}
	public static CompletableFuture<LuaGit.Account> githubAccount() { return githubAccount(LuaProtocol.Action.GITHUB_ACCOUNT, ""); }
	public static CompletableFuture<LuaGit.Account> githubLogin() { return githubAccount(LuaProtocol.Action.GITHUB_LOGIN, ""); }
	public static CompletableFuture<LuaGit.Account> githubPoll() { return githubAccount(LuaProtocol.Action.GITHUB_POLL, ""); }
	public static CompletableFuture<LuaGit.Account> githubLogout() { return githubAccount(LuaProtocol.Action.GITHUB_LOGOUT, ""); }
	public static CompletableFuture<LuaGit.Account> githubClientId(String id) { return githubAccount(LuaProtocol.Action.GITHUB_CLIENT_ID, id); }
	private static CompletableFuture<LuaGit.Account> githubAccount(LuaProtocol.Action action, String body) {
		return request(action, "", body).thenApply(response -> LuaGit.JSON.fromJson(response.data(), LuaGit.Account.class));
	}
	public static CompletableFuture<LuaGit.Repositories> githubRepositories(int page) {
		return request(LuaProtocol.Action.GITHUB_REPOSITORIES, "", Integer.toString(page)).thenApply(response -> LuaGit.JSON.fromJson(response.data(), LuaGit.Repositories.class));
	}
	public static CompletableFuture<LuaGit.Repository> githubSelectRepository(String name) {
		return request(LuaProtocol.Action.GITHUB_SELECT_REPOSITORY, "", name).thenApply(response -> LuaGit.JSON.fromJson(response.data(), LuaGit.Repository.class));
	}
	public static CompletableFuture<LuaGit.Repository> githubCreateRepository(String name, String description, boolean privateRepo) {
		return request(LuaProtocol.Action.GITHUB_CREATE_REPOSITORY, "", LuaGit.JSON.toJson(new LuaGit.CreateRepository(name, description, privateRepo)))
				.thenApply(response -> LuaGit.JSON.fromJson(response.data(), LuaGit.Repository.class));
	}
	public static CompletableFuture<LuaGit.Detail> gitDetail(LuaGit.Mode mode, String sha, String file) {
		return request(LuaProtocol.Action.GET_GIT_DETAIL, mode.name(), LuaGit.JSON.toJson(new LuaGit.DetailRequest(sha, file)))
				.thenApply(response -> LuaGit.JSON.fromJson(response.data(), LuaGit.Detail.class));
	}
	public static CompletableFuture<String> commitGit(LuaGit.Mode mode, String summary, String description) {
		return request(LuaProtocol.Action.GIT_COMMIT, mode.name(), LuaGit.JSON.toJson(new LuaGit.CommitRequest(summary, description)))
				.thenApply(LuaProtocol.Response::data);
	}
	public static CompletableFuture<String> restoreGit(LuaGit.Mode mode, String sha) {
		return request(LuaProtocol.Action.GIT_RESTORE, mode.name(), sha).thenApply(LuaProtocol.Response::data);
	}

	public static CompletableFuture<String> pushGit(String commitMessage) {
		return request(LuaProtocol.Action.GIT_PUSH, commitMessage, "").thenApply(LuaProtocol.Response::data);
	}

	public static CompletableFuture<Void> createDatapackFile(String name, String source) {
		requireSource(source);
		return request(LuaProtocol.Action.CREATE_DATAPACK_FILE, name, source).thenApply(ignored -> null);
	}

	public static CompletableFuture<Void> createDatapackExample(String name) {
		return request(LuaProtocol.Action.CREATE_DATAPACK_FILE, name, "").thenApply(ignored -> null);
	}

	public static CompletableFuture<Boolean> deleteDatapackPath(String name) {
		return request(LuaProtocol.Action.DELETE_DATAPACK_PATH, name, "")
				.thenApply(response -> Boolean.parseBoolean(response.data()));
	}

	public static CompletableFuture<Void> createDatapackDirectory(String path) {
		return request(LuaProtocol.Action.CREATE_DATAPACK_DIRECTORY, path, "").thenApply(ignored -> null);
	}

	public static CompletableFuture<Void> renameDatapackPath(String oldPath, String newPath) {
		return request(LuaProtocol.Action.RENAME_DATAPACK_PATH, oldPath, newPath).thenApply(ignored -> null);
	}

	/** Reads a script without treating the result as the version being edited (for comparing after a save conflict). */
	static CompletableFuture<String> peek(String name) {
		return request(LuaProtocol.Action.READ, requireName(name), "").thenApply(LuaProtocol.Response::data);
	}

	public static CompletableFuture<String> read(String name) {
		long requestedAt = LuaMcpFileSync.revision();
		return request(LuaProtocol.Action.READ, requireName(name), "").thenApply(response -> {
			LuaMcpFileSync.observedRead(name, response.data(), requestedAt); rememberBase(name, response.data()); return response.data();
		});
	}
	static CompletableFuture<wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus> mcpServerStatus() {
		return request(LuaProtocol.Action.GET_MCP_SERVER, "", "").thenApply(response -> wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus.decode(response.data()));
	}
	static CompletableFuture<wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus> configureMcpServer(boolean enabled, int port, boolean write, boolean execute, boolean rotate) { return configureMcpServer(enabled, port, write, execute, rotate, null); }
	static CompletableFuture<wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus> configureMcpServer(boolean enabled, int port, boolean write, boolean execute, boolean rotate, Boolean remote) {
		var fields = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("enabled", enabled, "port", port, "allowWrite", write, "allowExecution", execute, "rotate", rotate));
		if (remote != null) fields.put("allowRemote", remote);
		String json = wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpServer.JSON.toJson(fields);
		return request(LuaProtocol.Action.SET_MCP_SERVER, "", json).thenApply(response -> wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus.decode(response.data()));
	}
	static CompletableFuture<wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus> clearMcpServerLogs() {
		return request(LuaProtocol.Action.CLEAR_MCP_SERVER_LOGS, "", "").thenApply(response -> wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpStatus.decode(response.data()));
	}
	static CompletableFuture<com.google.gson.JsonObject> mcpTool(String tool, com.google.gson.JsonObject arguments) {
		return request(LuaProtocol.Action.MCP_TOOL, tool, wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpServer.JSON.toJson(arguments))
				.thenApply(response -> com.google.gson.JsonParser.parseString(response.data()).getAsJsonObject());
	}

	/**
	 * Saves a script. When the version this client last read or saved is known, the save is checked: the server refuses it (with a
	 * message starting with {@link #CONFLICT}) if someone else changed the file meanwhile. Use {@link #overwrite} to save anyway.
	 */
	public static CompletableFuture<Void> save(String name, String source) {
		requireSource(source);
		String base = BASES.get(name);
		if (base == null) return overwrite(name, source);
		com.google.gson.JsonObject body = new com.google.gson.JsonObject();
		body.addProperty("base", base); body.addProperty("source", source);
		return request(LuaProtocol.Action.SAVE_CHECKED, requireName(name), body.toString())
				.thenApply(ignored -> { LuaMcpFileSync.saved(name); rememberBase(name, source); return null; });
	}

	/** Saves without checking for changes by others. */
	public static CompletableFuture<Void> overwrite(String name, String source) {
		requireSource(source);
		return request(LuaProtocol.Action.SAVE, requireName(name), source).thenApply(ignored -> { LuaMcpFileSync.saved(name); rememberBase(name, source); return null; });
	}

	/** The start of the error message of a save refused because the file changed on the server. */
	static final String CONFLICT = "CONFLICT:";
	private static final Map<String, String> BASES = new java.util.concurrent.ConcurrentHashMap<>();
	/** The version of a script this client has (after reading it, saving it or accepting someone else's change). */
	static void rememberBase(String name, String source) { if (name != null && source != null) BASES.put(name, wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints.hash(source)); }
	static boolean isConflict(Throwable error) { String text = message(error); return text != null && text.startsWith(CONFLICT); }
	static String conflictText(Throwable error) { String text = message(error); return text.startsWith(CONFLICT) ? text.substring(CONFLICT.length()) : text; }

	public static CompletableFuture<Boolean> delete(String name) {
		return request(LuaProtocol.Action.DELETE, requireName(name), "")
				.thenApply(response -> Boolean.parseBoolean(response.data()));
	}

	public static CompletableFuture<LuaLimits> getLimits() {
		return request(LuaProtocol.Action.GET_LIMITS, "", "")
				.thenApply(response -> LuaLimits.decode(response.data()));
	}

	public static CompletableFuture<LuaLimits> saveLimits(LuaLimits limits) {
		return request(LuaProtocol.Action.SAVE_LIMITS, "", limits.encode())
				.thenApply(response -> LuaLimits.decode(response.data()));
	}

	public static CompletableFuture<Integer> refreshApiDocumentation() {
		return request(LuaProtocol.Action.GET_API_DOCUMENTATION, "", "").thenApply(response -> {
			LuaApiDescriptorCodec.Documentation documentation =
					LuaApiDescriptorCodec.decodeDocumentation(response.data());
			LuaApiDocumentation.replaceExternal(documentation.functions(), documentation.pages());
			return documentation.functions().size() + documentation.pages().size();
		});
	}

	static CompletableFuture<LuaComponentEditorData> componentEditorData(String kind, String id) {
		return request(LuaProtocol.Action.GET_COMPONENT_EDITOR_DATA, kind, selectedLocale() + "\n" + id)
				.thenApply(response -> LuaComponentEditorData.decode(response.data()));
	}

	static CompletableFuture<String> dimensionJson(String id) {
		return request(LuaProtocol.Action.GET_DIMENSION_JSON, id, "").thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<String> validateDimensionJson(String json) {
		return request(LuaProtocol.Action.VALIDATE_DIMENSION_JSON, "", json)
				.thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<DatapackJsonEditorOptions> datapackJsonOptions(String category) {
		return request(LuaProtocol.Action.GET_DATAPACK_JSON_OPTIONS, category, "")
				.thenApply(response -> DatapackJsonEditorOptions.decode(response.data()));
	}

	static CompletableFuture<List<String>> datapackJsonCategories() {
		return request(LuaProtocol.Action.GET_DATAPACK_JSON_CATEGORIES, "", "")
				.thenApply(response -> {
					var names = new java.util.ArrayList<String>();
					com.google.gson.JsonParser.parseString(response.data()).getAsJsonArray()
							.forEach(item -> names.add(item.getAsString()));
					return List.copyOf(names);
				});
	}

	static CompletableFuture<String> datapackJsonSource(String category, String id) {
		return request(LuaProtocol.Action.GET_DATAPACK_JSON_SOURCE, category, id)
				.thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<String> validateDatapackJson(String category, String json) {
		return request(LuaProtocol.Action.VALIDATE_DATAPACK_JSON, category, json)
				.thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<String> validateItemComponents(String itemId, String componentsJson) {
		return validateComponents("item", itemId, componentsJson);
	}

	static CompletableFuture<String> validateComponents(String kind, String targetId, String componentsJson) {
		return request(LuaProtocol.Action.VALIDATE_ITEM_COMPONENTS, kind + "\n" + targetId, componentsJson)
				.thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<List<LuaInsertCatalog.Entry>> registryCatalog(String registryId) {
		List<LuaInsertCatalog.Entry> local = LuaResourceAssetCatalog.load(registryId, Minecraft.getInstance());
		if (local != null) return CompletableFuture.completedFuture(local);
		boolean sounds = registryId.equals("minecraft:sound_event");
		if (sounds && !isServerAvailable()) return CompletableFuture.completedFuture(localSoundCatalog());
		String locale = selectedLocale();
		String cacheKey = registryId + '\0' + locale;
		int revision = catalogRevision();
		CompletableFuture<List<LuaInsertCatalog.Entry>> remote = REGISTRY_CATALOGS.computeIfAbsent(cacheKey, ignored -> {
			List<LuaInsertCatalog.Entry> entries = new ArrayList<>();
			return loadRegistryPage(registryId, locale, 0, entries);
		});
		// Register failure cleanup only after insertion, so synchronous failures cannot recursively update the map.
		remote.whenComplete((value, failure) -> {
			if (failure != null && revision == catalogRevision()) REGISTRY_CATALOGS.remove(cacheKey, remote);
		});
		return sounds ? remote.handle((loaded, failure) -> LuaResourceAssetCatalog.merge(
				failure == null ? loaded : List.of(), localSoundCatalog())) : remote;
	}

	private static List<LuaInsertCatalog.Entry> localSoundCatalog() {
		List<LuaInsertCatalog.Entry> builtIn = new ArrayList<>();
		for (var id : net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.keySet())
			builtIn.add(new LuaInsertCatalog.Entry(id.toString(), id.getPath().replace('_', ' '), id.getNamespace(), "客户端音效注册 ID"));
		return LuaResourceAssetCatalog.merge(LuaResourceAssetCatalog.sounds(Minecraft.getInstance()), builtIn);
	}

	static void invalidateRegistryCatalog(String registryId) {
		REGISTRY_CATALOGS.remove(registryId + '\0' + selectedLocale());
	}

	static CompletableFuture<List<LuaRegistryCatalogData.RegistryType>> registryTypes() {
		String locale = selectedLocale();
		int revision = catalogRevision();
		return REGISTRY_TYPES.computeIfAbsent(locale, key ->
				request(LuaProtocol.Action.GET_REGISTRY_CATALOG, "*", key)
						.thenApply(response -> LuaRegistryCatalogData.decodeTypes(response.data()))
						.whenComplete((ignored, failure) -> {
							if (failure != null && revision == catalogRevision()) REGISTRY_TYPES.remove(key);
						}));
	}

	private static CompletableFuture<List<LuaInsertCatalog.Entry>> loadRegistryPage(String registryId,
			String locale, int offset, List<LuaInsertCatalog.Entry> accumulated) {
		return request(LuaProtocol.Action.GET_REGISTRY_CATALOG, registryId, locale + "\n" + offset)
				.thenCompose(response -> {
					LuaRegistryCatalogData.Page page = LuaRegistryCatalogData.decodePage(
							response.data(), Minecraft.getInstance());
					accumulated.addAll(page.entries());
					if (page.nextOffset() >= 0) {
						return loadRegistryPage(registryId, locale, page.nextOffset(), accumulated);
					}
					return CompletableFuture.completedFuture(List.copyOf(accumulated));
				});
	}

	private static String selectedLocale() {
		String selected = Minecraft.getInstance().getLanguageManager().getSelected();
		return selected == null || selected.isBlank() ? "zh_cn" : selected;
	}

	public static ScriptExecution start(String name, String source, Consumer<String> output,
			Consumer<ExecutionResult> completion) {
		requireName(name);
		return startExecution(LuaProtocol.Action.RUN, name, name + ".lua", source, output, completion);
	}

	public static ScriptExecution startNormal(String name, String source, Consumer<String> output,
			Consumer<ExecutionResult> completion) {
		requireName(name);
		return startExecution(LuaProtocol.Action.RUN_NORMAL, name, name + ".lua", source, output, completion);
	}

	public static ScriptExecution startFunction(String path, String source, Consumer<String> output,
			Consumer<ExecutionResult> completion) {
		if (path == null || path.length() > LuaProtocol.MAX_NAME_CHARS || !path.endsWith(".mcfunction"))
			throw new IllegalArgumentException("请选择数据包 function 目录中的 .mcfunction 文件");
		return startExecution(LuaProtocol.Action.RUN_FUNCTION, path, path, source, output, completion);
	}

	private static ScriptExecution startExecution(LuaProtocol.Action action, String name, String displayName,
			String source, Consumer<String> output, Consumer<ExecutionResult> completion) {
		requireSource(source);
		ensureAvailable();
		ScriptExecution current = activeExecution;
		if (current != null && current.isRunning()) {
			throw new IllegalStateException("已有脚本正在运行：" + current.name());
		}
		long runId = nextRequestId();
		ScriptExecution execution = new ScriptExecution(runId, name, displayName, source, output, completion);
		activeExecution = execution;
		lastExecution = execution;
		EXECUTIONS.put(runId, execution);
		CompletableFuture<Void> prepared = action == LuaProtocol.Action.RUN
				? LuaBreakpointClient.prepareRun(name, source) : CompletableFuture.completedFuture(null);
		prepared.thenCompose(ignored -> {
			if (!execution.running) return CompletableFuture.completedFuture(null);
			execution.requestSent = true;
			return request(runId, action, name, source);
		}).whenComplete((response, failure) -> {
			if (failure != null) {
				execution.saved.completeExceptionally(failure);
				execution.finish(new ExecutionResult(false, message(failure)));
			} else if (response != null) execution.saved.complete(null);
			else execution.saved.completeExceptionally(new IllegalStateException("启动前已取消，文件未保存"));
		});
		return execution;
	}

	public static boolean stopActive() {
		ScriptExecution execution = activeExecution;
		if (execution == null || !execution.isRunning()) return false;
		execution.stop();
		return true;
	}

	/** Returns the client-side handle for the script that is still running on the server. */
	public static ScriptExecution activeExecution() {
		ScriptExecution execution = activeExecution;
		return execution != null && execution.isRunning() ? execution : null;
	}

	/** Most recently started run in this connection, including completed/failed/stopped runs. */
	static ScriptExecution lastExecution() { return lastExecution; }

	public static CompletableFuture<Boolean> stopServer() {
		ScriptExecution expected = activeExecution;
		return request(LuaProtocol.Action.STOP, "", "")
				.thenApply(response -> {
					boolean stopped = Boolean.parseBoolean(response.data());
					// A missed terminal packet must not leave a phantom execution blocking RUN.
					// Never clear a new/pending run on an older STOP response.
					reconcileStopped(expected, stopped);
					return stopped;
				});
	}

	static void reconcileStopped(ScriptExecution expected, boolean stopped) {
		if (!stopped && expected != null && expected.requestSent && activeExecution == expected)
			expected.finish(new ExecutionResult(true, null));
	}

	public static CompletableFuture<String> debugVariables() {
		return request(LuaProtocol.Action.GET_DEBUG_VARIABLES, "", "")
				.thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<Void> setBreakpoints(String json) {
		return request(LuaProtocol.Action.SET_BREAKPOINTS, "", json).thenApply(ignored -> null);
	}
	static CompletableFuture<String> debuggerState() {
		return request(LuaProtocol.Action.GET_DEBUG_STATE, "", "").thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> controlDebugger(String executionId, String command) {
		return request(LuaProtocol.Action.DEBUG_CONTROL, executionId, command).thenApply(LuaProtocol.Response::data);
	}
	/** Evaluates expressions in a paused frame: body is {"frame":0,"exprs":[...]}. */
	static CompletableFuture<String> evaluateDebugger(String executionId, String body) {
		return request(LuaProtocol.Action.DEBUG_EVALUATE, executionId, body).thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> listSnapshots() {
		return request(LuaProtocol.Action.LIST_SNAPSHOTS, "", "").thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> createSnapshot() {
		return request(LuaProtocol.Action.CREATE_SNAPSHOT, "", "").thenApply(LuaProtocol.Response::data);
	}
	/** Replaces the server's scripts with a snapshot (the server snapshots the current scripts first); returns that backup's name. */
	static CompletableFuture<String> restoreSnapshot(String name) {
		return request(LuaProtocol.Action.RESTORE_SNAPSHOT, name, "").thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> commandReference() {
		return request(LuaProtocol.Action.GET_COMMAND_REFERENCE, "", "").thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> auditLog() {
		return request(LuaProtocol.Action.GET_AUDIT_LOG, "", "").thenApply(LuaProtocol.Response::data);
	}
	/** Reports this IDE's open files; the reply lists other administrators' open files. */
	static CompletableFuture<String> filePresence(String body) {
		return request(LuaProtocol.Action.FILE_PRESENCE, "", body).thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> terminalOpen(String body) {
		return request(LuaProtocol.Action.TERMINAL_OPEN, "", body).thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> terminalIo(String id, String body) {
		return request(LuaProtocol.Action.TERMINAL_IO, id, body).thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> terminalClose(String id) {
		return request(LuaProtocol.Action.TERMINAL_CLOSE, id, "").thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> setDebuggerGamePause(boolean enabled) {
		return request(LuaProtocol.Action.SET_DEBUG_GAME_PAUSE, "", Boolean.toString(enabled)).thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<String> setDebuggerPlayerMovement(boolean enabled) {
		return request(LuaProtocol.Action.SET_DEBUG_PLAYER_MOVEMENT, "", Boolean.toString(enabled)).thenApply(LuaProtocol.Response::data);
	}
	static CompletableFuture<Boolean> setDebuggerCamera(boolean enabled) {
		return request(LuaProtocol.Action.DEBUG_CAMERA_MODE, "", Boolean.toString(enabled))
				.thenApply(response -> Boolean.parseBoolean(response.data()));
	}

	public static String validate(String name, String source) {
		try {
			wiki.ica.xiaomiao.mcluaide.server.LuaSandbox.syntaxOnly().load(source, name + ".lua");
			return null;
		} catch (LuaError error) {
			return error.getMessage();
		} catch (Exception error) {
			return error.getMessage();
		}
	}

	public static boolean isValidName(String name) {
		return LuaScriptNames.isValid(name);
	}

	public static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private static CompletableFuture<LuaProtocol.Response> request(
			LuaProtocol.Action action, String name, String body) {
		return request(nextRequestId(), action, name, body);
	}

	private static CompletableFuture<LuaProtocol.Response> request(
			long requestId, LuaProtocol.Action action, String name, String body) {
		try {
			ensureAvailable();
		} catch (RuntimeException error) {
			return CompletableFuture.failedFuture(error);
		}
		CompletableFuture<LuaProtocol.Response> future = new CompletableFuture<>();
		PENDING.put(requestId, future);
		try {
			ClientPlayNetworking.send(new LuaProtocol.Request(requestId, action, name, body));
		} catch (Throwable error) {
			PENDING.remove(requestId);
			future.completeExceptionally(error);
			return future;
		}
		boolean fileTransfer = LuaProtocol.requestTextLimit(action.id()) == LuaProtocol.MAX_SCRIPT_CHARS
				|| LuaProtocol.responseTextLimit(action.id()) == LuaProtocol.MAX_SCRIPT_CHARS;
		boolean packWork = action == LuaProtocol.Action.RELOAD_DATAPACKS || action == LuaProtocol.Action.FINISH_SERVER_PACK_UPLOAD
				|| action == LuaProtocol.Action.SET_SERVER_PACK;
		boolean gitWork = switch (action) {
			case GIT_PUSH, GET_GIT_HISTORY, GET_GIT_DETAIL, GIT_COMMIT, GIT_RESTORE,
					GITHUB_ACCOUNT, GITHUB_CLIENT_ID, GITHUB_LOGIN, GITHUB_POLL, GITHUB_LOGOUT,
					GITHUB_REPOSITORIES, GITHUB_SELECT_REPOSITORY, GITHUB_CREATE_REPOSITORY -> true;
			default -> false;
		};
		CompletableFuture.delayedExecutor(gitWork ? 600 : packWork ? 180 : fileTransfer ? 60 : 12,
				TimeUnit.SECONDS).execute(() -> {
			CompletableFuture<LuaProtocol.Response> waiting = PENDING.remove(requestId);
			if (waiting != null) waiting.completeExceptionally(new TimeoutException("等待服务器响应超时"));
		});
		return future;
	}

	private static void receive(LuaProtocol.Response response) {
		CompletableFuture<LuaProtocol.Response> future = PENDING.remove(response.requestId());
		if (future == null) return;
		if (response.success()) future.complete(response);
		else future.completeExceptionally(new IllegalStateException(response.data()));
	}

	private static void receive(LuaProtocol.ExecutionEvent event) {
		ScriptExecution execution = EXECUTIONS.get(event.runId());
		if (execution == null) return;
		LuaProtocol.ExecutionKind kind;
		try {
			kind = LuaProtocol.ExecutionKind.byId(event.kindId());
		} catch (RuntimeException error) {
			execution.finish(new ExecutionResult(false, error.getMessage()));
			return;
		}
		switch (kind) {
			case OUTPUT -> execution.emitOutput(event.message());
			case FINISHED -> execution.finish(new ExecutionResult(false, null));
			case FAILED -> execution.finish(new ExecutionResult(false, event.message()));
			case STOPPED -> execution.finish(new ExecutionResult(true, null));
		}
	}

	private static void disconnect(String reason) {
		LuaMcpFileSync.disconnected();
		LuaClientMcpService.disconnected();
		LuaDebugHud.disconnected();
		LuaBreakpointClient.disconnected();
		TerminalPanel.disconnected();
		BASES.clear();
		EditPresenceClient.disconnected();
		List<ScriptExecution> executions = List.copyOf(EXECUTIONS.values());
		EXECUTIONS.clear();
		activeExecution = null;
		PENDING.values().forEach(future -> future.completeExceptionally(new IllegalStateException(reason)));
		PENDING.clear();
		for (ScriptExecution execution : executions) {
			try { execution.finish(new ExecutionResult(false, reason)); }
			catch (RuntimeException error) { McLuaIdeMod.LOGGER.warn("Could not notify disconnected Lua execution", error); }
		}
		// Never show another server's code/logs or submit a disconnected run to AI on reconnect.
		lastExecution = null;
		LuaLogHud.disconnected();
		invalidateAllRegistryCatalogs();
		LuaApiDocumentation.replaceExternal(List.of());
	}

	private static void ensureAvailable() {
		if (!isServerAvailable()) {
			throw new IllegalStateException("服务器未安装或未启用 MC Lua IDE");
		}
	}

	private static String requireName(String name) {
		if (!isValidName(name)) {
			throw new IllegalArgumentException("脚本路径仅能用 / 分隔的字母、数字、下划线和连字符；每层 1-48 字符，最多 6 层");
		}
		return name;
	}

	private static void requireSource(String source) {
		if (source == null || source.length() > LuaProtocol.MAX_SCRIPT_CHARS) {
			throw new IllegalArgumentException("脚本内容不能超过 " + LuaProtocol.MAX_SCRIPT_CHARS + " 个字符");
		}
	}

	private static long nextRequestId() {
		long id = NEXT_REQUEST_ID.getAndIncrement();
		return id == 0 ? NEXT_REQUEST_ID.getAndIncrement() : id;
	}

	public record ExecutionResult(boolean stopped, String error) {
		public boolean succeeded() {
			return !stopped && error == null;
		}
	}

	public static final class ScriptExecution {
		private final CompletableFuture<Void> saved = new CompletableFuture<>();
		/** Completes only after the server acknowledges persisting the submitted source. */
		CompletableFuture<Void> saved() { return saved; }
		private final long runId;
		private final String name;
		private final String displayName;
		private final String originalSource;
		record ReportedError(long sequence, String message) { }
		private volatile ReportedError reportedError;
		private final LuaAiAnalysisHistory aiAnalysisHistory = new LuaAiAnalysisHistory();
		private final Consumer<String> output;
		private final Consumer<ExecutionResult> completion;
		private final AtomicBoolean terminal = new AtomicBoolean();
		private final List<String> outputLines = new ArrayList<>();
		private volatile boolean running = true;
		private volatile boolean requestSent;
		private volatile ExecutionResult result;
		private long outputLineCount;

		private ScriptExecution(long runId, String name, String displayName, String source, Consumer<String> output,
				Consumer<ExecutionResult> completion) {
			this.runId = runId;
			this.name = name;
			this.displayName = displayName;
			this.originalSource = source;
			this.output = output;
			this.completion = completion;
			appendHistory("▶ 开始运行 " + displayName);
		}

		public String name() {
			return name;
		}

		public String displayName() {
			return displayName;
		}
		String originalSource() { return originalSource; }
		ReportedError reportedError() { return reportedError; }
		LuaAiAnalysisHistory aiAnalysisHistory() { return aiAnalysisHistory; }

		public boolean isRunning() {
			return running;
		}

		/** A bounded snapshot used by IDE instances reopened while this script is running. */
		public synchronized List<String> outputLines() {
			return List.copyOf(outputLines);
		}

		public synchronized long outputLineCount() {
			return outputLineCount;
		}
		/** Local IDE diagnostics share history so incoming Lua output cannot erase an AI response. */
		void appendIdeMessage(String text) { appendHistory(text); }

		public ExecutionResult result() {
			return result;
		}

		public void stop() {
			if (!running) return;
			if (displayName.endsWith(".mcfunction")) {
				emitOutput("原版函数执行无法中途停止；请等待本次运行结束");
				return;
			}
			if (!requestSent) { finish(new ExecutionResult(true, null)); return; }
			stopServer().whenComplete((stopped, failure) -> {
				if (failure != null) emitOutput("停止失败：" + message(failure));
				else if (!stopped) emitOutput("服务器上没有正在运行的脚本");
			});
		}

		private void emitOutput(String text) {
			// Cleanup callback failures do not fail the whole execution. Keep their origin separate
			// from local AI replies, so quoted errors in an AI reply cannot start a request loop.
			if (text.startsWith("⚠ 停止回调 #") && text.contains(" 失败：")) {
				ReportedError previous = reportedError;
				reportedError = new ReportedError(previous == null ? 1 : previous.sequence() + 1, text);
			}
			appendHistory(text);
			output.accept(text);
		}

		private synchronized void appendHistory(String text) {
			LuaLogHud.append(displayName, text);
			for (String line : text.split("\\R", -1)) {
				outputLines.add(line);
				outputLineCount++;
			}
			while (outputLines.size() > 200) outputLines.removeFirst();
		}

		private void finish(ExecutionResult result) {
			if (!terminal.compareAndSet(false, true)) return;
			running = false;
			this.result = result;
			if (result.stopped()) appendHistory("■ 脚本已停止");
			else if (result.error() != null) appendHistory("✗ " + result.error());
			else appendHistory(displayName.endsWith(".mcfunction") ? "✓ 函数执行完成" : "✓ 脚本执行完成");
			EXECUTIONS.remove(runId);
			if (activeExecution == this) activeExecution = null;
			completion.accept(result);
		}
	}
}
