package wiki.ica.xiaomiao.mcluaide.server;

import wiki.ica.xiaomiao.mcluaide.network.LuaServerPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Isolated ZIP uploads/settings/downloads; never uses a Minecraft save or external network. */
public final class LuaServerPackSmoke {
	private static int checks;
	@FunctionalInterface private interface Throwing { void run() throws Exception; }
	public static void main(String[] args) throws Exception {
		Path temporary = Files.createTempDirectory("mc-lua-server-pack-smoke-").toAbsolutePath().normalize();
		LuaServerPackService service = new LuaServerPackService(temporary.resolve("server"));
		LuaServerPackService restored = null;
		try {
			Path zip = temporary.resolve("valid.zip"); make(zip, "assets/test/models/item/test.json", "{\"parent\":\"minecraft:block/tnt\"}", true);
			String sha256 = LuaResourcePackZip.hash(zip, "SHA-256"), sha1 = LuaResourcePackZip.validate(zip, sha256);
			require(sha1.matches("[a-f0-9]{40}"), "SHA-1 generated");
			reject(() -> LuaResourcePackZip.validate(zip, "0".repeat(64)), "Hash mismatch");
			for (String bad : new String[]{"../secret.txt", "/outside.txt", "assets/../../a", "C:/secret.txt", "assets\\evil.txt"}) {
				Path invalid = temporary.resolve("unsafe.zip"); make(invalid, bad, "x", true);
				reject(() -> LuaResourcePackZip.validate(invalid, LuaResourcePackZip.hash(invalid, "SHA-256")), "Unsafe ZIP path " + bad);
			}
			Path missing = temporary.resolve("missing.zip"); make(missing, "assets/test/example.json", "{}", false);
			reject(() -> LuaResourcePackZip.validate(missing, LuaResourcePackZip.hash(missing, "SHA-256")), "Missing pack.mcmeta");
			Path data = temporary.resolve("data.zip"); make(data, "data/test/function/test.mcfunction", "say test", true);
			reject(() -> LuaResourcePackZip.validate(data, LuaResourcePackZip.hash(data, "SHA-256")), "Datapack instead of resourcepack");
			for (String url : new String[]{"file:///C:/secret", "http://user:password@example.com", "http://example.com/pack.zip#x", "ftp://example.com/pack.zip", "http://example.com/path"})
				reject(() -> new LuaServerPack.Settings(true, true, 8080, url, false), "Unsafe or incorrectly shaped download URL");
			reject(() -> new LuaServerPack.Settings(true, true, 8080, "", false), "Missing public URL");
			reject(() -> LuaServerPack.Upload.decode("{\"fileName\":\"../test.zip\",\"size\":10,\"sha256\":\"" + sha256 + "\"}"), "Path injection via JSON");
			reject(() -> new LuaServerPack.Upload("test.zip", LuaServerPack.MAX_BYTES + 1, sha256), "Upload too large");
			var clientSettings = new LuaServerPack.Settings(true, true, 8080, "http://example.com:8080/", false);
			require(LuaServerPack.Settings.decode(clientSettings.encode()).equals(clientSettings), "Settings round trip");
			require(clientSettings.downloadUrl(sha1).equals("http://example.com:8080/mc-lua-pack/" + sha1 + ".zip"), "Hosted URL content address");
			UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
			var uploadInfo = new LuaServerPack.Upload("valid.zip", Files.size(zip), sha256);
			String id = work(service, pack -> pack.begin(owner, uploadInfo.encode()));
			reject(() -> work(service, pack -> pack.begin(other, uploadInfo.encode())), "Concurrent upload");
			reject(() -> work(service, pack -> pack.finish(owner, id)), "Incomplete upload");
			byte[] bytes = Files.readAllBytes(zip); String chunk = "0\n" + Base64.getEncoder().encodeToString(bytes);
			reject(() -> work(service, pack -> pack.chunk(other, id, chunk)), "Wrong session owner");
			reject(() -> work(service, pack -> pack.chunk(owner, id, "1\n" + Base64.getEncoder().encodeToString(bytes))), "Wrong chunk offset");
			require(work(service, pack -> pack.chunk(owner, id, chunk)).equals(Integer.toString(bytes.length)), "Chunk acknowledged");
			reject(() -> work(service, pack -> pack.chunk(owner, id, chunk)), "Replayed chunk");
			var uploaded = LuaServerPack.Status.decode(work(service, pack -> pack.finish(owner, id)));
			require(uploaded.sha1().equals(sha1) && !uploaded.settings().enabled(), "Validated upload staged, not automatically published");
			reject(() -> work(service, pack -> pack.cancel(other, id)), "Cannot cancel nonexistent/foreign session");
			int port;
			try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
			var settings = new LuaServerPack.Settings(true, true, port, "http://127.0.0.1:" + port, false);
			var published = LuaServerPack.Status.decode(work(service, pack -> pack.configure(settings.encode())));
			require(published.settings().enabled() && published.downloadUrl().contains(sha1), "Pack published");
			HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
			var response = http.send(HttpRequest.newBuilder(java.net.URI.create(published.downloadUrl())).timeout(java.time.Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofByteArray());
			require(response.statusCode() == 200 && java.util.Arrays.equals(response.body(), bytes), "Public ZIP endpoint serves exact uploaded bytes");
			var head = http.send(HttpRequest.newBuilder(java.net.URI.create(published.downloadUrl())).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
			require(head.statusCode() == 200 && head.headers().firstValue("Content-Length").orElse("").equals(Integer.toString(bytes.length)), "HEAD size");
			for (String path : new String[]{"/mc-lua-pack/server-pack.properties", "/mc-lua-pack/../server-pack.properties", "/mc-lua-pack/%2e%2e/server-pack.properties", "/other"}) {
				var rejected = http.send(HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.discarding());
				require(rejected.statusCode() == 404, "Only hashed ZIP public: " + path);
			}
			// Uploading a replacement does not unpublish the current pack.
			String replacement = work(service, pack -> pack.begin(owner, uploadInfo.encode()));
			work(service, pack -> pack.chunk(owner, replacement, chunk)); work(service, pack -> pack.finish(owner, replacement));
			var activeField = LuaServerPackService.class.getDeclaredField("status"); activeField.setAccessible(true);
			require(((LuaServerPack.Status) activeField.get(service)).settings().enabled(), "Old pack stays live while new ZIP is staged");
			stopHttp(service);
			restored = new LuaServerPackService(temporary.resolve("server"));
			var saved = LuaServerPack.Status.decode(work(restored, LuaServerPackService::describe));
			require(saved.settings().equals(settings) && saved.sha1().equals(sha1), "Publication survives restart");
			var restoredService = restored;
			var disabled = new LuaServerPack.Settings(false, true, port, settings.url(), false);
			work(restored, pack -> pack.configure(disabled.encode()));
			require(!LuaServerPack.Status.decode(work(restored, LuaServerPackService::describe)).settings().enabled(), "Disable persisted");
			String cancellation = work(restored, pack -> pack.begin(owner, uploadInfo.encode()));
			work(restored, pack -> pack.cancel(owner, cancellation));
			require(!Files.exists(temporary.resolve("server/upload-" + cancellation + ".part")), "Cancelled upload temp removed");
			System.out.println("Server resource pack smoke passed: " + checks + " checks");
		} finally {
			stopHttp(service); if (restored != null) stopHttp(restored);
			try (var files = Files.walk(temporary)) {
				for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
					if (!path.toAbsolutePath().normalize().startsWith(temporary)) throw new AssertionError("Unsafe test cleanup target");
					Files.deleteIfExists(path);
				}
			}
		}
	}
	private static String work(LuaServerPackService service, LuaServerPackService.Operation operation) throws Exception { return service.execute(operation).get(10, TimeUnit.SECONDS); }
	private static void stopHttp(LuaServerPackService service) throws Exception {
		var field = LuaServerPackService.class.getDeclaredField("http"); field.setAccessible(true);
		var http = (com.sun.net.httpserver.HttpServer) field.get(service); if (http != null) http.stop(0);
	}
	private static void make(Path path, String name, String content, boolean meta) throws Exception {
		try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
			if (meta) { output.putNextEntry(new ZipEntry("pack.mcmeta")); output.write("{\"pack\":{\"description\":\"test\",\"min_format\":[84,0],\"max_format\":[84,0]}}".getBytes(StandardCharsets.UTF_8)); output.closeEntry(); }
			output.putNextEntry(new ZipEntry(name)); output.write(content.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
		}
	}
	private static void reject(Throwing action, String message) throws Exception { try { action.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError("Accepted " + message); }
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
