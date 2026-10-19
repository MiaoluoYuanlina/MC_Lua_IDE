package wiki.ica.xiaomiao.mcluaide.server;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaServerPack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;

/** One world-owned publication. Vanilla clients receive normal resource-pack packets, never custom payloads. */
final class LuaServerPackService {
	static final UUID PACK_ID = UUID.nameUUIDFromBytes("mc_lua_ide:server_resource_pack".getBytes(StandardCharsets.UTF_8));
	private static final Map<MinecraftServer, LuaServerPackService> SERVICES = new WeakHashMap<>();
	private final Path root;
	private final Semaphore workSlots = new Semaphore(16), downloads = new Semaphore(4);
	private volatile HttpServer http;
	private volatile boolean closed;
	private boolean loaded;
	private volatile LuaServerPack.Status status = empty("");
	private LuaServerPack.Status pending;
	private Session upload;
	private static final long UPLOAD_TTL = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
	private record Session(UUID id, UUID owner, Path path, LuaServerPack.Upload info, long received, long touched) { }
	@FunctionalInterface interface Operation { String run(LuaServerPackService service) throws Exception; }

	LuaServerPackService(Path root) { this.root = root.toAbsolutePath().normalize(); }
	private static LuaServerPack.Status empty(String problem) {
		return new LuaServerPack.Status(LuaServerPack.Settings.defaults(), "", "", 0, "", problem);
	}
	static synchronized LuaServerPackService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, ignored -> new LuaServerPackService(
				server.getWorldPath(LevelResource.ROOT).resolve("mc_lua_ide_server_pack")));
	}
	static void started(MinecraftServer server) {
		get(server).execute(service -> service.describe()).exceptionally(error -> {
			McLuaIdeMod.LOGGER.warn("Could not initialize server resource pack", error); return null;
		});
	}
	static synchronized void stopped(MinecraftServer server) {
		LuaServerPackService service = SERVICES.remove(server);
		if (service == null) return;
		service.closed = true;
		HttpServer web = service.http;
		if (web != null) web.stop(0);
		Thread.ofVirtual().name("mc-lua-pack-cleanup").start(() -> {
			synchronized (service) {
				HttpServer pendingHttp = service.http;
				if (pendingHttp != null) pendingHttp.stop(0);
				try { service.cancelSession(); } catch (IOException error) { McLuaIdeMod.LOGGER.warn("Pack upload cleanup failed", error); }
			}
		});
	}
	static void disconnected(MinecraftServer server, UUID owner) {
		get(server).execute(service -> {
			if (service.upload != null && service.upload.owner.equals(owner)) service.cancelSession();
			return "cancelled";
		});
	}
	static void joined(MinecraftServer server, ServerPlayer player) {
		LuaServerPackService service = get(server);
		service.execute(LuaServerPackService::describe).whenComplete((ignored, error) -> {
			if (error == null) server.execute(() -> {
				if (server.getPlayerList().getPlayer(player.getUUID()) == player) service.push(player, false);
			});
		});
	}
	CompletableFuture<String> execute(Operation operation) {
		if (closed || !workSlots.tryAcquire()) return CompletableFuture.failedFuture(new IOException("资源包服务已关闭或繁忙，请稍后重试"));
		CompletableFuture<String> future = new CompletableFuture<>();
		Thread.ofVirtual().name("mc-lua-server-pack").start(() -> {
			try {
				synchronized (this) {
					if (closed) throw new IOException("服务器资源包服务已关闭");
					load();
					if (upload != null && System.nanoTime() - upload.touched > UPLOAD_TTL) cancelSession();
					future.complete(operation.run(this));
				}
			} catch (Exception error) { future.completeExceptionally(error); }
			finally { workSlots.release(); }
		});
		return future;
	}

	private void safeRoot() throws IOException {
		if (Files.isSymbolicLink(root)) throw new IOException("服务器资源包目录不能是符号链接");
		Files.createDirectories(root);
	}
	private Path safeFile(String name) throws IOException {
		Path path = root.resolve(name).normalize();
		if (!path.getParent().equals(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(path)) throw new IOException("资源包文件路径不安全");
		return path;
	}
	private Path packFile(String sha1) throws IOException {
		if (!sha1.matches("[a-f0-9]{40}")) throw new IOException("资源包 SHA-1 无效");
		return safeFile(sha1 + ".zip");
	}
	private void load() throws Exception {
		if (loaded) return;
		safeRoot();
		// Exact private prefix only; interrupted uploads are not published and can be safely discarded.
		try (var files = Files.list(root)) {
			for (Path path : files.filter(file -> file.getFileName().toString().matches("upload-[a-f0-9-]{36}\\.part")).toList())
				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
		}
		Path config = safeFile("server-pack.properties");
		if (Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
			try {
				if (Files.size(config) > 16_384) throw new IOException("资源包设置文件过大");
				Properties data = new Properties();
				try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { data.load(reader); }
				LuaServerPack.Settings settings = LuaServerPack.Settings.decode(data.getProperty("settings", ""));
				String sha1 = data.getProperty("sha1", "");
				long size = Long.parseLong(data.getProperty("size", "0"));
				if (!sha1.isEmpty() && (!Files.isRegularFile(packFile(sha1), LinkOption.NOFOLLOW_LINKS)
						|| size <= 0 || size > LuaServerPack.MAX_BYTES || Files.size(packFile(sha1)) != size
						|| !LuaResourcePackZip.hash(packFile(sha1), "SHA-1").equals(sha1))) throw new IOException("已保存资源包不存在或校验失败");
				if (settings.enabled() && sha1.isEmpty()) throw new IOException("尚未上传资源包");
				status = new LuaServerPack.Status(settings, data.getProperty("fileName", ""), sha1, size,
						sha1.isEmpty() || settings.url().isEmpty() ? "" : settings.downloadUrl(sha1), "");
				if (settings.enabled() && settings.hosted()) {
					try { http = openHttp(settings.port()); }
					catch (IOException error) {
						var disabled = new LuaServerPack.Settings(false, settings.hosted(), settings.port(), settings.url(), settings.required());
						status = new LuaServerPack.Status(disabled, status.fileName(), status.sha1(), status.size(), status.downloadUrl(),
								"下载服务启动失败：" + error.getMessage() + "；请调整端口后重新应用");
					}
				}
			} catch (Exception error) {
				status = empty("资源包发布未启用：" + error.getMessage());
				McLuaIdeMod.LOGGER.warn("Could not restore world server resource pack", error);
			}
		}
		loaded = true;
	}
	String describe() { return (pending == null ? status : pending).encode(); }
	String begin(UUID owner, String body) throws Exception {
		LuaServerPack.Upload info = LuaServerPack.Upload.decode(body);
		if (info == null) throw new IOException("上传信息为空");
		if (upload != null) {
			if (!upload.owner.equals(owner)) throw new IOException("另一位管理员正在上传资源包");
			cancelSession();
		}
		long stored = 0;
		try (var files = Files.list(root)) {
			for (Path file : files.filter(path -> path.getFileName().toString().matches("[a-f0-9]{40}\\.zip")).toList())
				if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) stored += Files.size(file);
		}
		if (stored + info.size() > 1024L * 1024 * 1024) throw new IOException("服务器资源包存储超过 1 GiB，请管理员清理不再使用的旧 ZIP 后重试");
		UUID id = UUID.randomUUID(); Path temporary = safeFile("upload-" + id + ".part");
		Files.createFile(temporary);
		upload = new Session(id, owner, temporary, info, 0, System.nanoTime());
		return id.toString();
	}
	private Session session(UUID owner, String id) throws IOException {
		if (upload == null || !upload.owner.equals(owner) || !upload.id.toString().equals(id)) throw new IOException("上传会话不存在、已过期或不属于当前玩家");
		return upload;
	}
	String chunk(UUID owner, String id, String body) throws Exception {
		Session current = session(owner, id);
		int delimiter = body.indexOf('\n');
		if (delimiter < 1 || body.length() > LuaServerPack.CHUNK_BYTES * 4 / 3 + 32) throw new IOException("上传数据块格式无效");
		long offset = Long.parseLong(body.substring(0, delimiter));
		byte[] bytes = Base64.getDecoder().decode(body.substring(delimiter + 1));
		if (offset != current.received || bytes.length == 0 || bytes.length > LuaServerPack.CHUNK_BYTES
				|| offset + bytes.length > current.info.size()) throw new IOException("上传数据块顺序或大小无效");
		if (!Files.isRegularFile(current.path, LinkOption.NOFOLLOW_LINKS) || Files.size(current.path) != offset) throw new IOException("上传临时文件已改变");
		Files.write(current.path, bytes, java.nio.file.StandardOpenOption.APPEND);
		upload = new Session(current.id, owner, current.path, current.info, offset + bytes.length, System.nanoTime());
		return Long.toString(upload.received);
	}
	String finish(UUID owner, String id) throws Exception {
		Session current = session(owner, id);
		if (current.received != current.info.size()) throw new IOException("资源包尚未完整上传");
		try {
			String sha1 = LuaResourcePackZip.validate(current.path, current.info.sha256());
			if (closed) throw new IOException("服务器资源包服务已关闭");
			Path target = packFile(sha1);
			if (Files.exists(target)) {
				if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !LuaResourcePackZip.hash(target, "SHA-1").equals(sha1))
					throw new IOException("同名服务器 ZIP 文件校验失败");
				Files.delete(current.path);
			} else {
				try { Files.move(current.path, target, StandardCopyOption.ATOMIC_MOVE); }
				catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(current.path, target); }
			}
			// A new upload must be explicitly published; existing live clients keep the previous pack until then.
			LuaServerPack.Settings old = status.settings();
			LuaServerPack.Settings waiting = new LuaServerPack.Settings(false, old.hosted(), old.port(), old.url(), old.required());
			LuaServerPack.Status next = new LuaServerPack.Status(waiting, current.info.fileName(), sha1, current.info.size(),
					waiting.url().isEmpty() ? "" : waiting.downloadUrl(sha1), "已上传，尚未设置为服务器资源包");
			pending = next;
			return describe();
		} finally { cancelSession(); }
	}
	String cancel(UUID owner, String id) throws IOException { session(owner, id); cancelSession(); return "cancelled"; }
	private void cancelSession() throws IOException {
		if (upload == null) return;
		Files.deleteIfExists(safeFile(upload.path.getFileName().toString())); upload = null;
	}
	String configure(String body) throws Exception {
		LuaServerPack.Settings settings = LuaServerPack.Settings.decode(body);
		LuaServerPack.Status base = pending == null ? status : pending;
		if (settings.enabled() && base.sha1().isEmpty()) throw new IOException("请先选择并上传 ZIP 资源包");
		if (settings.enabled() && (!Files.isRegularFile(packFile(base.sha1()), LinkOption.NOFOLLOW_LINKS)
				|| !LuaResourcePackZip.hash(packFile(base.sha1()), "SHA-1").equals(base.sha1()))) throw new IOException("服务器 ZIP 校验失败，请重新上传");
		HttpServer candidate = http;
		boolean reuse = settings.enabled() && settings.hosted() && candidate != null && candidate.getAddress().getPort() == settings.port();
		if (settings.enabled() && settings.hosted() && !reuse) candidate = openHttp(settings.port());
		LuaServerPack.Status next = new LuaServerPack.Status(settings, base.fileName(), base.sha1(), base.size(),
				base.sha1().isEmpty() || settings.url().isEmpty() ? "" : settings.downloadUrl(base.sha1()), "");
		try { persist(next); }
		catch (Exception error) { if (candidate != null && candidate != http) candidate.stop(0); throw error; }
		HttpServer old = http;
		http = settings.enabled() && settings.hosted() ? candidate : null;
		status = next;
		pending = null;
		if (old != null && old != http) old.stop(0);
		return describe();
	}
	private void persist(LuaServerPack.Status value) throws IOException {
		if (closed) throw new IOException("服务器资源包服务已关闭");
		safeRoot(); Path config = safeFile("server-pack.properties"), temporary = Files.createTempFile(root, "settings-", ".tmp");
		Properties data = new Properties();
		data.setProperty("settings", value.settings().encode()); data.setProperty("fileName", value.fileName());
		data.setProperty("sha1", value.sha1()); data.setProperty("size", Long.toString(value.size()));
		try {
			try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { data.store(writer, "MC Lua IDE vanilla server resource pack"); }
			try { Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING); }
		} finally { Files.deleteIfExists(temporary); }
	}
	private HttpServer openHttp(int port) throws IOException {
		if (closed) throw new IOException("服务器资源包服务已关闭");
		HttpServer web = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 16);
		web.setExecutor(command -> Thread.ofVirtual().name("mc-lua-pack-download").start(command));
		web.createContext("/mc-lua-pack/", exchange -> {
			if (closed || !downloads.tryAcquire()) {
				try (exchange) { exchange.sendResponseHeaders(503, -1); } return;
			}
			try (exchange) {
				if (!(exchange.getRequestMethod().equals("GET") || exchange.getRequestMethod().equals("HEAD"))) { exchange.sendResponseHeaders(405, -1); return; }
				String request = exchange.getRequestURI().getRawPath();
				if (!request.matches("/mc-lua-pack/[a-f0-9]{40}\\.zip")) { exchange.sendResponseHeaders(404, -1); return; }
				Path file = packFile(request.substring("/mc-lua-pack/".length(), request.length() - 4));
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) { exchange.sendResponseHeaders(404, -1); return; }
				long size = Files.size(file);
				if (size <= 0 || size > LuaServerPack.MAX_BYTES) { exchange.sendResponseHeaders(404, -1); return; }
				exchange.getResponseHeaders().set("Content-Type", "application/zip");
				exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
				exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
				exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
				if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); return; }
				exchange.sendResponseHeaders(200, size);
				try (var input = Files.newInputStream(file)) { input.transferTo(exchange.getResponseBody()); }
			} catch (IOException error) { McLuaIdeMod.LOGGER.debug("Server pack download ended: {}", error.getMessage()); }
			finally { downloads.release(); }
		});
		web.start();
		if (closed) { web.stop(0); throw new IOException("服务器资源包服务已关闭"); }
		McLuaIdeMod.LOGGER.info("Vanilla server resource pack download service enabled on port {} (only ZIP endpoint)", port);
		return web;
	}
	void publishToPlayers(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) push(player, true);
	}
	private void push(ServerPlayer player, boolean replace) {
		LuaServerPack.Status snapshot = status;
		if (replace) player.connection.send(new ClientboundResourcePackPopPacket(Optional.of(PACK_ID)));
		if (closed || !snapshot.settings().enabled() || snapshot.sha1().isEmpty()) return;
		player.connection.send(new ClientboundResourcePackPushPacket(PACK_ID, snapshot.downloadUrl(), snapshot.sha1(),
				snapshot.settings().required(), Optional.of(Component.literal("服务器资源包：" + snapshot.fileName()))));
	}
}
