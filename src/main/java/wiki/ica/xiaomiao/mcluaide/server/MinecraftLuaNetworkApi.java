package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Optional high-risk outbound HTTP API. It is absent unless the server-wide policy enables it. */
final class MinecraftLuaNetworkApi {
	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

	private MinecraftLuaNetworkApi() {
	}

	static void install(LuaTable mc, LuaLimits limits) {
		if (!limits.allowNetworkAccess()) return;
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(limits.networkTimeoutSeconds()))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		LuaTable api = new LuaTable();
		bind(api, "get", args -> request(client, limits, "GET", args.checkjstring(1), "", ""));
		bind(api, "post", args -> request(client, limits, "POST", args.checkjstring(1),
				args.checkjstring(2), args.optjstring(3, "text/plain; charset=utf-8")));
		bind(api, "request", args -> request(client, limits, args.checkjstring(1),
				args.checkjstring(2), args.optjstring(3, ""),
				args.optjstring(4, "text/plain; charset=utf-8")));
		mc.set("Network", api);
		mc.set("network", api);
	}

	private static LuaValue request(HttpClient client, LuaLimits limits, String rawMethod,
			String rawUrl, String body, String contentType) throws Exception {
		String method = rawMethod.strip().toUpperCase(Locale.ROOT);
		if (!METHODS.contains(method)) throw new LuaError("网络方法只允许 GET/POST/PUT/PATCH/DELETE/HEAD");
		if (rawUrl.length() > 2_048) throw new LuaError("URL 不能超过 2048 个字符");
		URI uri;
		try {
			uri = URI.create(rawUrl);
		} catch (IllegalArgumentException error) {
			throw new LuaError("URL 无效：" + error.getMessage());
		}
		String scheme = uri.getScheme();
		if (scheme == null || uri.getHost() == null
				|| !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
			throw new LuaError("网络 API 只允许完整的 http/https URL");
		}
		if (contentType.length() > 128 || contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
			throw new LuaError("Content-Type 无效");
		}
		byte[] requestBytes = body.getBytes(StandardCharsets.UTF_8);
		if (requestBytes.length > limits.maxNetworkResponseBytes()) {
			throw new LuaError("请求正文超过服务器网络字节限制");
		}

		HttpRequest.BodyPublisher publisher = requestBytes.length == 0
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofByteArray(requestBytes);
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(limits.networkTimeoutSeconds()))
				.header("User-Agent", "MC-Lua-IDE/1.0")
				.method(method, publisher);
		if (requestBytes.length > 0) builder.header("Content-Type", contentType);

		HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		byte[] responseBytes;
		try (InputStream input = response.body()) {
			responseBytes = input.readNBytes(limits.maxNetworkResponseBytes() + 1);
		}
		if (responseBytes.length > limits.maxNetworkResponseBytes()) {
			throw new LuaError("网络响应超过服务器限制的 " + limits.maxNetworkResponseBytes() + " 字节");
		}

		LuaTable result = new LuaTable();
		result.set("status", response.statusCode());
		result.set("body", new String(responseBytes, StandardCharsets.UTF_8));
		LuaTable headers = new LuaTable();
		for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
			headers.set(header.getKey(), String.join(", ", header.getValue()));
		}
		result.set("headers", headers);
		return result;
	}

	private static void bind(LuaTable table, String name, NetworkCallable function) {
		table.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				try {
					return function.invoke(args);
				} catch (LuaError error) {
					throw error;
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					throw new LuaError("网络请求已随脚本停止");
				} catch (Throwable error) {
					throw new LuaError(message(error));
				}
			}
		});
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	@FunctionalInterface
	private interface NetworkCallable {
		Varargs invoke(Varargs args) throws Exception;
	}
}
