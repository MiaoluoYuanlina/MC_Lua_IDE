package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;
import wiki.ica.xiaomiao.mcluaide.mcp.*;
import wiki.ica.xiaomiao.mcluaide.network.LuaApiDescriptorCodec;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One server-wide configuration. All game work and current owner permissions are checked on the server thread. */
public final class LuaServerMcpService {
    private static final Map<MinecraftServer, LuaServerMcpService> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Path file;
    private LuaMcpSettings settings;
    private LuaMcpServer endpoint;
    private List<LuaMcpServer.Log> history = List.of();
    private String problem = "";
    private int revision;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private static final class Job {
        ServerScriptManager.ScriptExecution execution;
        final Deque<String> lines = new ArrayDeque<>();
        volatile ServerScriptManager.ExecutionResult result;
        synchronized void log(String line) { lines.addLast(line.substring(0, Math.min(1000, line.length()))); while (lines.size() > 120) lines.removeFirst(); }
        synchronized JsonObject snapshot() {
            JsonObject out = new JsonObject(); out.addProperty("script", execution == null ? "" : execution.name());
            out.addProperty("running", execution != null && execution.isRunning()); out.add("lines", LuaMcpServer.JSON.toJsonTree(List.copyOf(lines)));
            out.add("result", LuaMcpServer.JSON.toJsonTree(result)); return out;
        }
    }
    private LuaServerMcpService(MinecraftServer server) {
        this.server = server; file = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/mcp-server.properties");
        settings = LuaMcpSettings.load(file, true);
    }
    private static LuaServerMcpService get(MinecraftServer server) { return SERVICES.computeIfAbsent(server, LuaServerMcpService::new); }
    public static void started(MinecraftServer server) { get(server).restore(); }
    public static void stopped(MinecraftServer server) { var service = SERVICES.remove(server); if (service != null) service.closeEndpoint(); }
    public static LuaMcpStatus status(MinecraftServer server) { return get(server).status(); }
    private List<LuaMcpServer.Log> logs() {
        var all = new ArrayList<>(history); if (endpoint != null) all.addAll(endpoint.logs());
        return List.copyOf(all.subList(Math.max(0, all.size() - 500), all.size()));
    }
    private LuaMcpStatus status() { return new LuaMcpStatus(settings, endpoint != null && endpoint.running(), problem, logs()); }
    private boolean ownerAllowed() {
        try {
            UUID id = UUID.fromString(settings.ownerUuid()); ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) return LuaServerNetworking.isAdministrator(server, player);
            NameAndId identity = new NameAndId(id, "MCP owner");
            if (server.getSingleplayerProfile() != null && server.getSingleplayerProfile().id().equals(id)) return true;
            var entry = server.getPlayerList().getOps().get(identity);
            return entry != null && entry.permissions().hasPermission(Permissions.COMMANDS_OWNER);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private void restore() {
        closeEndpoint(); problem = "";
        if (!settings.enabled()) return;
        if (!ownerAllowed()) { problem = "已记住开启，但绑定管理员当前没有所有者权限；请管理员重新配置"; return; }
        int epoch = revision;
        endpoint = new LuaMcpServer("mc-lua-ide-server", (tool, args) -> {
            CompletableFuture<JsonObject> result = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (result.isCancelled()) return;
                    if (revision != epoch || !settings.enabled() || endpoint == null || !endpoint.running()) throw new IllegalStateException("MCP 服务已关闭或重新配置");
                    if (!ownerAllowed()) throw new IllegalStateException("绑定管理员的所有者权限已被移除");
                    result.complete(call("server", UUID.fromString(settings.ownerUuid()), tool, args));
                } catch (Exception error) { result.completeExceptionally(error); }
            });
            return result;
        });
        try { endpoint.start(settings); }
        catch (Exception error) { endpoint.close(); endpoint = null; problem = "MCP 启动失败：端口被占用或无法绑定（" + error.getClass().getSimpleName() + "）"; }
    }
    private void closeEndpoint() { revision++; if (endpoint != null) { endpoint.close(); history = logs(); endpoint = null; } }
    public static LuaMcpStatus configure(MinecraftServer server, ServerPlayer player, JsonObject args) throws Exception {
        if (!LuaServerNetworking.isAdministrator(server, player)) throw new IllegalStateException("需要服务器所有者权限");
        var service = get(server);
        LuaMcpSettings next = service.settings.update(args.get("enabled").getAsBoolean(), args.get("port").getAsInt(),
                args.get("allowWrite").getAsBoolean(), args.get("allowExecution").getAsBoolean(), player.getUUID().toString(), "server",
                args.has("rotate") && args.get("rotate").getAsBoolean(),
                args.has("allowRemote") ? args.get("allowRemote").getAsBoolean() : service.settings.allowRemote());
        next.save(service.file); service.settings = next; service.restore(); return service.status();
    }
    public static LuaMcpStatus clearLogs(MinecraftServer server) { var service = get(server); service.history = List.of(); if (service.endpoint != null) service.endpoint.clearLogs(); return service.status(); }

    /** Client-local MCP uses this same authority boundary, even while the server HTTP listener is off. */
    public static JsonObject clientCall(MinecraftServer server, ServerPlayer player, String tool, JsonObject args) throws Exception {
        if (!LuaServerNetworking.isAdministrator(server, player)) throw new IllegalStateException("需要服务器所有者权限");
        JsonObject result = get(server).call("client:" + player.getUUID(), player.getUUID(), tool, args);
        if (LuaMcpServer.JSON.toJson(result).length() > wiki.ica.xiaomiao.mcluaide.network.LuaProtocol.MAX_RESPONSE_CHARS)
            throw new IllegalStateException("MCP 结果超过网络限制，请缩小 limit 或分段查询；未返回截断 JSON");
        return result;
    }
    private JsonObject call(String actor, UUID owner, String tool, JsonObject args) throws Exception {
        LuaMcpTools.Tool definition = LuaMcpTools.find(tool); LuaMcpTools.validate(definition, args);
        if (GitWorkspaceService.restoring() && definition.permission() != LuaMcpTools.Permission.READ) throw new IllegalStateException("Git 正在恢复工作区，请稍后操作");
        Job job = jobs.get(actor);
        return switch (tool) {
            case "mc_status" -> {
                JsonObject out = new JsonObject(); out.addProperty("role", actor.equals("server") ? "server" : "client");
                out.addProperty("apiVersion", 1); out.addProperty("activeScript", ServerScriptManager.activeScriptName());
                out.addProperty("world", server.getWorldData().getLevelName());
                out.addProperty("players", server.getPlayerList().getPlayerCount()); out.addProperty("ownerUuid", owner.toString());
                out.addProperty("mcpRunningScript", job != null && job.execution != null && job.execution.isRunning()); yield out;
            }
            case "lua_list_scripts" -> {
                String query = LuaMcpTools.string(args, "query", "").toLowerCase(Locale.ROOT);
                var scripts = ServerScriptManager.listScripts().stream().filter(name -> query.isBlank() || name.toLowerCase(Locale.ROOT).contains(query)).toList();
                int start = Math.min(scripts.size(), LuaMcpTools.integer(args, "offset", 0, Integer.MAX_VALUE));
                int end = Math.min(scripts.size(), start + LuaMcpTools.integer(args, "limit", 100, 250));
                JsonObject out = new JsonObject(); out.add("scripts", LuaMcpServer.JSON.toJsonTree(scripts.subList(start, end)));
                out.addProperty("total", scripts.size()); out.addProperty("nextOffset", end < scripts.size() ? end : -1); yield out;
            }
            case "lua_read_script" -> LuaMcpTools.page(ServerScriptManager.read(args.get("name").getAsString()), args);
            case "lua_save_script" -> {
                String source = args.get("source").getAsString();
                if (source.length() > LuaLimitConfig.current().maxScriptChars()) throw new IllegalArgumentException("源码超过服务器脚本长度限制");
                String name = args.get("name").getAsString(), before = ServerScriptManager.readIfPresent(name);
                ServerScriptManager.save(name, source);
                LuaServerNetworking.notifyMcpFileChanged(server, name, before, source);
                yield LuaMcpTools.text("已保存并通知管理员 IDE 同步；未保存草稿会提示确认，修改区域以 MCP 背景色标记");
            }
            case "lua_run_script" -> {
                String name = args.get("name").getAsString(); String source = ServerScriptManager.read(name); Job started = new Job();
                if (jobs.size() >= 64 && !jobs.containsKey(actor)) {
                    var obsolete = jobs.entrySet().stream().filter(entry -> entry.getValue().execution == null || !entry.getValue().execution.isRunning()).findFirst();
                    if (obsolete.isPresent()) jobs.remove(obsolete.get().getKey()); else throw new IllegalStateException("MCP 运行记录已满");
                }
                started.execution = ServerScriptManager.start(server, owner, name, source, false, started::log, result -> { started.result = result; started.log(result.error() == null ? result.stopped() ? "脚本已停止" : "脚本已完成" : result.error()); });
                jobs.put(actor, started); yield LuaMcpTools.text("已启动：" + name + "；使用 lua_execution_logs 查询，不启用调试，不改写文件");
            }
            case "lua_stop_script" -> {
                if (job == null || job.execution == null || !job.execution.isRunning()) yield LuaMcpTools.text("这套 MCP 没有正在运行的脚本，未停止其他脚本");
                job.execution.stop(); yield LuaMcpTools.text("已请求停止 MCP 启动的脚本");
            }
            case "lua_execution_logs" -> job == null ? LuaMcpTools.text("这套 MCP 尚未启动脚本") : job.snapshot();
            case "mc_api_documentation" -> {
                String query = LuaMcpTools.string(args, "query", "");
                String external = LuaApiDescriptorCodec.encode(new LuaApiDescriptorCodec.Documentation(
                        McLuaIdeApi.descriptors().stream().filter(d -> query.isBlank() || LuaMcpServer.JSON.toJson(d).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList(),
                        McLuaIdeDocsApi.pages().stream().filter(d -> query.isBlank() || LuaMcpServer.JSON.toJson(d).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList()));
                yield LuaMcpTools.page(LuaMcpTools.manual(query) + "\n\n当前第三方 API/文档 JSON：\n" + external, args);
            }
            case "mc_registry_ids" -> {
                int offset = LuaMcpTools.integer(args, "offset", 0, Integer.MAX_VALUE), limit = LuaMcpTools.integer(args, "limit", 100, 250);
                JsonObject page = JsonParser.parseString(LuaRegistryCatalog.describe(server, args.get("registry").getAsString(), "zh_cn\n" + offset)).getAsJsonObject();
                JsonArray results = new JsonArray(); int scanned = 0; String query = LuaMcpTools.string(args, "query", "").toLowerCase(Locale.ROOT);
                for (var entry : page.getAsJsonArray("entries")) {
                    if (results.size() >= limit) break; scanned++;
                    if (query.isBlank() || entry.toString().toLowerCase(Locale.ROOT).contains(query)) results.add(entry);
                }
                int next = scanned < page.getAsJsonArray("entries").size() ? offset + scanned : page.get("nextOffset").getAsInt();
                page.add("entries", results); page.addProperty("nextOffset", next); yield page;
            }
            default -> throw new IllegalArgumentException("未知 MCP 工具");
        };
    }
}
