package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.*;
import wiki.ica.xiaomiao.mcluaide.mcp.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure HTTP/JSON-RPC/security/configuration checks; no Minecraft world or user settings. */
public final class LuaMcpSmoke {
    private static int checks;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mc-lua-mcp-smoke-");
        try {
            require(!LuaMcpSettings.load(root.resolve("client.properties"), false).enabled() && !LuaMcpSettings.load(root.resolve("server.properties"), true).enabled(), "Default MCP must be off");
            require(LuaMcpSettings.defaults(false).port() != LuaMcpSettings.defaults(true).port(), "Client/server default port collision");
            LuaMcpSettings config = LuaMcpSettings.defaults(false).update(true, freePort(), false, false, UUID.randomUUID().toString(), "test", false);
            config.save(root.resolve("settings.properties"));
            require(LuaMcpSettings.load(root.resolve("settings.properties"), false).equals(config), "Enabled, port, permissions or token lost after restart");
            var disabled = config.update(false, config.port(), false, false, config.ownerUuid(), config.scope(), false); disabled.save(root.resolve("settings.properties"));
            require(!LuaMcpSettings.load(root.resolve("settings.properties"), false).enabled(), "Disabled preference lost");
            LuaMcpSettings remoteConfig = config.update(true, freePort(), false, false, config.ownerUuid(), config.scope(), false, true);
            remoteConfig.save(root.resolve("remote.properties"));
            require(LuaMcpSettings.load(root.resolve("remote.properties"), false).allowRemote() && !config.allowRemote(), "Remote-connections setting lost or on by default");
            try (LuaMcpServer remote = new LuaMcpServer("remote", (tool, values) -> CompletableFuture.completedFuture(LuaMcpTools.text("ok")))) {
                remote.start(remoteConfig);
                require(send(remoteConfig, "POST", "{}", null, "wrong", "https://other.example", null).statusCode() == 401, "Remote mode must still require the token");
                require(send(remoteConfig, "POST", "{}", null, remoteConfig.token(), "https://other.example", null).statusCode() != 403, "Remote mode still blocked a foreign origin");
                require(LuaMcpServer.prompt(remoteConfig, true).contains("127.0.0.1"), "Prompt lost the address hint");
            }
            LuaMcpSettings customAuth = config.withAuth(LuaMcpSettings.AUTH_CUSTOM, "my pass".replace(' ', '-') + "123");
            require(customAuth.token().equals("my-pass123") && customAuth.passwordRequired(), "Custom password becomes the bearer secret");
            require(customAuth.withAuth(LuaMcpSettings.AUTH_CUSTOM, "").token().equals("my-pass123"), "A blank password keeps the saved one");
            customAuth.save(root.resolve("custom.properties"));
            require(LuaMcpSettings.load(root.resolve("custom.properties"), false).equals(customAuth), "Custom password lost after restart");
            for (String bad : new String[] {"abc", "has space", "tab\there", "", "x".repeat(129), "中文密码1234"})
                require(!LuaMcpSettings.validPassword(bad), "Invalid password accepted: " + bad);
            boolean refused = false; try { config.withAuth(LuaMcpSettings.AUTH_CUSTOM, "bad pw"); } catch (IllegalArgumentException expected) { refused = true; }
            require(refused, "A password with a space was accepted");
            require(!customAuth.update(true, customAuth.port(), false, false, customAuth.ownerUuid(), customAuth.scope(), true).token().equals(config.token()), "Rotating must not restore a generated token over a custom password");
            require(customAuth.update(true, customAuth.port(), false, false, customAuth.ownerUuid(), customAuth.scope(), true).token().equals("my-pass123"), "Rotating changed the custom password");
            LuaMcpSettings open = config.withAuth(LuaMcpSettings.AUTH_NONE, null);
            require(!open.passwordRequired() && LuaMcpSettings.load(root.resolve("settings.properties"), false).passwordRequired(), "No-password mode flag");
            require(config.withAuth(LuaMcpSettings.AUTH_GENERATED, null).token().equals(config.token()), "Generated token stays while in generated mode");
            require(!customAuth.withAuth(LuaMcpSettings.AUTH_GENERATED, null).token().equals("my-pass123"), "Back to generated must discard the custom password");
            require(!LuaMcpServer.prompt(open, false).contains("Bearer"), "Open mode prompt must not claim a token");
            try (LuaMcpServer secured = new LuaMcpServer("custom", (tool, values) -> CompletableFuture.completedFuture(LuaMcpTools.text("ok")))) {
                LuaMcpSettings withPassword = config.update(true, freePort(), false, false, config.ownerUuid(), config.scope(), false).withAuth(LuaMcpSettings.AUTH_CUSTOM, "open-sesame");
                secured.start(withPassword);
                require(send(withPassword, "POST", "{}", null, withPassword.token(), null, null).statusCode() != 401, "The custom password was rejected");
                require(send(withPassword, "POST", "{}", null, config.token(), null, null).statusCode() == 401, "The old generated token still works after setting a password");
            }
            try (LuaMcpServer unprotected = new LuaMcpServer("open", (tool, values) -> CompletableFuture.completedFuture(LuaMcpTools.text("ok")))) {
                LuaMcpSettings noPassword = config.update(true, freePort(), false, false, config.ownerUuid(), config.scope(), false).withAuth(LuaMcpSettings.AUTH_NONE, null);
                unprotected.start(noPassword);
                require(send(noPassword, "POST", "{}", null, "anything", null, null).statusCode() != 401, "Open mode still demanded a password");
            }
            AtomicInteger calls = new AtomicInteger();
            try (LuaMcpServer endpoint = new LuaMcpServer("smoke", (tool, values) -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(LuaMcpTools.text("ok")); })) {
                endpoint.start(config);
                require(send(config, "POST", "{}", null, "wrong", null, null).statusCode() == 401, "Unauthenticated request accepted");
                require(send(config, "POST", "{}", null, config.token(), "https://evil.example", null).statusCode() == 403, "Cross-origin request accepted");
                require(send(config, "GET", "", null, config.token(), null, null).statusCode() == 405, "Unsupported GET must return 405");
                require(send(config, "POST", "{}", null, config.token(), null, "application/jsonfoo").statusCode() == 415, "Invalid content type accepted");
                require(send(config, "POST", "[]", null, config.token(), null, null).statusCode() == 400, "JSON-RPC batch accepted");
                require(send(config, "POST", "{", null, config.token(), null, null).statusCode() == 400, "Malformed JSON accepted");
                require(send(config, "POST", "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}", null, config.token(), null, null).statusCode() == 400, "Object request id accepted");
                require(send(config, "POST", "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}", null, config.token(), null, null).statusCode() == 400, "Numeric JSON-RPC version accepted");
                require(send(config, "POST", request(1, "tools/list", new JsonObject()), null, config.token(), null, null).statusCode() == 400, "Uninitialized call accepted");
                var initialized = send(config, "POST", request(1, "initialize", object("protocolVersion", LuaMcpServer.VERSION)), null, config.token(), null, null);
                require(initialized.statusCode() == 200 && parsed(initialized).getAsJsonObject("result").get("protocolVersion").getAsString().equals(LuaMcpServer.VERSION), "Version negotiation failed");
                String session = initialized.headers().firstValue("MCP-Session-Id").orElseThrow();
                require(parsed(send(config, "POST", request(2, "tools/list", new JsonObject()), session, config.token(), null, null)).has("error"), "Call before initialized notification accepted");
                require(send(config, "POST", request(null, "notifications/initialized", new JsonObject()), session, config.token(), null, null).statusCode() == 202, "Notification must return empty 202");
                var tools = parsed(send(config, "POST", request(3, "tools/list", new JsonObject()), session, config.token(), null, null)).getAsJsonObject("result").getAsJsonArray("tools");
                require(tools.size() == 9 && tools.get(0).getAsJsonObject().has("inputSchema"), "Tool schemas missing");
                require(!tool(config, session, "mc_status", new JsonObject()).get("isError").getAsBoolean() && calls.get() == 1, "Backend read failed");
                require(tool(config, session, "lua_save_script", object("name", "demo", "source", "secret-source-test")).get("isError").getAsBoolean() && calls.get() == 1, "Read-only mode wrote file");
                require(tool(config, session, "lua_run_script", object("name", "demo")).get("isError").getAsBoolean() && calls.get() == 1, "Execution opt-out bypassed");
                require(parsed(send(config, "POST", request(4, "tools/call", object("name", "unknown")), session, config.token(), null, null)).getAsJsonObject("error").get("code").getAsInt() == -32602, "Unknown tool not rejected");
                require(parsed(send(config, "POST", request(4, "tools/call", object("name", "lua_read_script")), session, config.token(), null, null)).has("error"), "Required argument not checked");
                require(parsed(send(config, "POST", request(4, "unknown/method", new JsonObject()), session, config.token(), null, null)).getAsJsonObject("error").get("code").getAsInt() == -32601, "Unknown method not rejected");
                try (LuaMcpServer collision = new LuaMcpServer("collision", (tool, values) -> CompletableFuture.completedFuture(new JsonObject()))) {
                    boolean rejected = false; try { collision.start(config); } catch (java.io.IOException occupied) { rejected = true; }
                    require(rejected && endpoint.running(), "Port collision was ignored or closed original server");
                }
                String logs = LuaMcpServer.JSON.toJson(endpoint.logs()); require(!logs.contains(config.token()) && !logs.contains("secret-source-test"), "MCP call logs leaked secrets/source");
                send(config, "POST", request(4, config.token(), new JsonObject()), session, config.token(), null, null);
                send(config, "POST", request(4, "tools/call", object("name", config.token())), session, config.token(), null, null);
                require(!LuaMcpServer.JSON.toJson(endpoint.logs()).contains(config.token()), "Unknown method/tool leaked bearer into audit logs");
                var add = LuaMcpServer.class.getDeclaredMethod("addLog", String.class, String.class, String.class, long.class); add.setAccessible(true);
                for (int i = 0; i < 510; i++) add.invoke(endpoint, "ping", "", "成功", 1L);
                require(endpoint.logs().size() == 500, "Log ring unbounded");
                endpoint.clearLogs(); require(endpoint.logs().isEmpty(), "Log clear failed");
                require(send(config, "DELETE", "", session, config.token(), null, null).statusCode() == 200, "Session close failed");
                require(send(config, "POST", request(5, "ping", new JsonObject()), session, config.token(), null, null).statusCode() == 404, "Deleted session accepted");
                endpoint.close();
                var rotated = config.update(true, config.port(), true, true, config.ownerUuid(), config.scope(), true); endpoint.start(rotated);
                require(send(rotated, "POST", "{}", null, config.token(), null, null).statusCode() == 401, "Old token still accepted");
                require(!LuaMcpServer.prompt(rotated, false).contains(config.token()) && LuaMcpServer.prompt(rotated, true).contains("SSH"), "Connection prompt missing transport/tunnel/token rotation");
            }
            JsonObject values = object("name", "demo"); values.addProperty("offset", -1);
            boolean invalid = false; try { LuaMcpTools.validate(LuaMcpTools.find("lua_read_script"), values); } catch (IllegalArgumentException rejected) { invalid = true; }
            require(invalid, "Negative offsets accepted");
            var page = LuaMcpTools.page("a😀b", object("limit", 2)); require(page.get("text").getAsString().equals("a😀") && page.get("nextOffset").getAsInt() == 3, "Unicode paging splits surrogate pair");
            require(LuaMcpTools.manual("MC.GameRule.set").contains("ruleId"), "Bundled manual query failed");
            System.out.println("MCP protocol/security/settings smoke passed: " + checks + " checks");
        } finally {
            try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            CLIENT.close();
        }
    }
    public static int freePort() throws Exception { try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    public static JsonObject object(Object... args) { JsonObject out = new JsonObject(); for (int i = 0; i < args.length; i += 2) out.add(args[i].toString(), LuaMcpServer.JSON.toJsonTree(args[i+1])); return out; }
    private static String request(Integer id, String method, JsonObject params) { JsonObject out = object("jsonrpc", "2.0", "method", method, "params", params); if (id != null) out.addProperty("id", id); return out.toString(); }
    private static JsonObject parsed(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    private static JsonObject tool(LuaMcpSettings config, String session, String name, JsonObject args) throws Exception { return parsed(send(config, "POST", request(8, "tools/call", object("name", name, "arguments", args)), session, config.token(), null, null)).getAsJsonObject("result"); }
    private static HttpResponse<String> send(LuaMcpSettings settings, String verb, String body, String session, String token, String origin, String type) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(settings.url())).timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json, text/event-stream").header("Content-Type", type == null ? "application/json" : type);
        if (origin != null) request.header("Origin", origin); if (session != null) request.header("MCP-Session-Id", session).header("MCP-Protocol-Version", LuaMcpServer.VERSION);
        return CLIENT.send(request.method(verb, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
