package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.mcp.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Local listener bound to one administrator and game connection; server validates every tool request. */
final class LuaClientMcpService {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/mcp-client.properties");
    private static LuaMcpSettings settings = LuaMcpSettings.load(FILE, false);
    private static LuaMcpServer endpoint;
    private static List<LuaMcpServer.Log> history = List.of();
    private static String problem = "";
    private static int revision;
    private LuaClientMcpService() { }
    private static String scope() {
        var client = Minecraft.getInstance(); var server = client.getSingleplayerServer();
        return server != null ? "world:" + server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                : client.getCurrentServer() == null ? "" : "server:" + client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
    }
    static LuaMcpStatus status() { return new LuaMcpStatus(settings, endpoint != null && endpoint.running(), problem, logs()); }
    private static List<LuaMcpServer.Log> logs() {
        List<LuaMcpServer.Log> all = new ArrayList<>(history); if (endpoint != null) all.addAll(endpoint.logs());
        return List.copyOf(all.subList(Math.max(0, all.size() - 500), all.size()));
    }
    static void clearLogs() { history = List.of(); if (endpoint != null) endpoint.clearLogs(); }
    static void disconnected() { closeEndpoint(); problem = "已断开；开启设置保留，重进相同世界/服务器且权限有效时自动恢复"; }
    static void shutdown() { closeEndpoint(); }
    private static void closeEndpoint() { revision++; if (endpoint != null) { endpoint.close(); history = logs(); endpoint = null; } }
    static CompletableFuture<LuaMcpStatus> configure(boolean enabled, int port, boolean write, boolean execute, boolean rotate) { return configure(enabled, port, write, execute, rotate, settings.allowRemote()); }
    static CompletableFuture<LuaMcpStatus> configure(boolean enabled, int port, boolean write, boolean execute, boolean rotate, boolean remote) { return configure(enabled, port, write, execute, rotate, remote, settings.authMode(), null); }
    static CompletableFuture<LuaMcpStatus> configure(boolean enabled, int port, boolean write, boolean execute, boolean rotate, boolean remote, String authMode, String password) {
        Minecraft client = Minecraft.getInstance();
        return (enabled ? ScriptManager.checkAccess() : CompletableFuture.<Void>completedFuture(null)).thenApply(ignored -> {
            try {
                String owner = client.player == null ? settings.ownerUuid() : client.player.getUUID().toString();
                LuaMcpSettings next = settings.update(enabled, port, write, execute, owner, client.player == null ? settings.scope() : scope(), rotate, remote);
                next = next.withAuth(authMode, password);
                next.save(FILE); settings = next; restore(); return status();
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }
    static void restore() {
        closeEndpoint(); problem = ""; if (!settings.enabled()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !settings.ownerUuid().equals(client.player.getUUID().toString()) || !settings.scope().equals(scope())) {
            problem = "已记住开启，但当前玩家或世界/服务器与绑定不同；请手动确认开启"; return;
        }
        int epoch = revision; var connection = client.getConnection();
        endpoint = new LuaMcpServer("mc-lua-ide-client", (tool, args) -> {
            CompletableFuture<JsonObject> result = new CompletableFuture<>();
            client.execute(() -> {
                try {
                    if (result.isCancelled()) return;
                    checkLease(epoch, connection);
                    CompletableFuture<JsonObject> response;
                    if (tool.equals("mc_api_documentation")) response = ScriptManager.refreshApiDocumentation().thenApply(ignored -> {
                        checkLease(epoch, connection); String query = LuaMcpTools.string(args, "query", "").toLowerCase(Locale.ROOT);
                        var entries = LuaApiDocumentation.allEntries().stream().filter(e -> query.isBlank() || LuaMcpServer.JSON.toJson(e).toLowerCase(Locale.ROOT).contains(query)).toList();
                        var pages = LuaApiDocumentation.allPages().stream().filter(e -> query.isBlank() || LuaMcpServer.JSON.toJson(e).toLowerCase(Locale.ROOT).contains(query)).toList();
                        return LuaMcpTools.page(LuaApiMarkdownExporter.render(entries, pages), args);
                    }); else response = ScriptManager.mcpTool(tool, args);
                    response.whenComplete((data, error) -> {
                        try {
                            checkLease(epoch, connection);
                            if (error != null) result.completeExceptionally(error);
                            else { if (tool.equals("mc_status")) { data.addProperty("allowWrite", settings.allowWrite()); data.addProperty("allowExecution", settings.allowExecution()); } result.complete(data); }
                        } catch (Exception invalid) { result.completeExceptionally(invalid); }
                    });
                } catch (Exception error) { result.completeExceptionally(error); }
            }); return result;
        });
        try { endpoint.start(settings); }
        catch (Exception error) { endpoint.close(); endpoint = null; problem = "MCP 启动失败：端口占用或无法绑定（" + error.getClass().getSimpleName() + "）"; }
    }
    private static void checkLease(int epoch, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        Minecraft client = Minecraft.getInstance();
        if (revision != epoch || endpoint == null || !endpoint.running() || client.player == null || client.getConnection() != connection
                || !settings.ownerUuid().equals(client.player.getUUID().toString()) || !settings.scope().equals(scope()))
            throw new IllegalStateException("MCP 服务已关闭、重配或游戏连接已改变");
    }
}
