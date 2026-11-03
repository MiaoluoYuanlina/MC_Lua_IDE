package wiki.ica.xiaomiao.mcluaide.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.impl.MinecraftAdapterImpl;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.compat.McEnvironment;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.compat.ToolAccess;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.compat.ToolCategory;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.compat.ToolCompatibilityFilter;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.config.Config;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.EventBus;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolRegistry;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.runtime.AsyncJobRegistry;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.runtime.MinecraftMainThreadExecutor;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.ToolRegistration;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.events.EventWiring;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * World tools ported from minecraft-java-fabric-mcp-server (MIT, see META-INF/licenses). They are served by the
 * MC Lua IDE MCP endpoints, behind the same token, owner check and write/execute switches as the Lua tools.
 * Their handlers block on the game thread, so calls run on a separate worker, never on the game thread itself.
 */
public final class LuaMcpWorldTools {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    /** Every category and access level: MC Lua IDE gates calls with its own permission switches instead. */
    public static final Config CONFIG = new Config("127.0.0.1", 0, false, null, false, List.of(), 15_000L, 60, 16 * 1024 * 1024,
            1024, 256, "info", null, null, false, Arrays.stream(ToolCategory.values()).map(ToolCategory::wireName).toList(),
            List.of(), ToolAccess.ADMIN.wireName(), false);
    /** Non-read tools that create or delete files on disk need the write-file switch; other changes need the execute switch. */
    private static final Set<String> FILE_TOOLS = Set.of("structure_save_from_world", "structure_delete", "structure_file_write", "structure_file_delete");
    private static final ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MC Lua IDE MCP world tool-", 0).factory());
    private static final EventBus EVENTS = new EventBus(CONFIG.eventBufferSize());
    private static ToolRegistry registry;
    private static List<LuaMcpTools.Tool> descriptors;
    private static volatile ToolContext context;
    private static MinecraftAdapterImpl adapter;
    private static MinecraftMainThreadExecutor executor;
    private static AsyncJobRegistry jobs;

    private LuaMcpWorldTools() { }

    public static ToolCompatibilityFilter filter() { return new ToolCompatibilityFilter(McEnvironment.capture(), CONFIG); }

    private static synchronized ToolRegistry registry() {
        if (registry == null) registry = ToolRegistration.buildRegistry(filter());
        return registry;
    }

    /** Tool descriptors in tools/list form; the same on a client (which forwards the calls) and on the server. */
    public static synchronized List<LuaMcpTools.Tool> serverTools() {
        if (descriptors == null) descriptors = registry().list().stream().map(LuaMcpWorldTools::describe).toList();
        return descriptors;
    }

    public static boolean isServerTool(String name) { return registry().lookup(name).isPresent(); }

    public static LuaMcpTools.Tool describe(ToolRegistry.Entry entry) {
        var descriptor = entry.descriptor();
        LuaMcpTools.Permission permission = descriptor.access() == ToolAccess.READ ? LuaMcpTools.Permission.READ
                : FILE_TOOLS.contains(descriptor.name()) ? LuaMcpTools.Permission.WRITE : LuaMcpTools.Permission.EXECUTE;
        JsonObject schema = gson(entry.tool().inputSchema());
        return new LuaMcpTools.Tool(descriptor.name(), descriptor.description(), permission, schema == null ? new JsonObject() : schema, true);
    }

    /** Called once from the mod initializer: binds the adapter to whichever server (dedicated or integrated) is running. */
    public static void install() {
        EventWiring.install(EVENTS, MAPPER);
        ServerLifecycleEvents.SERVER_STARTING.register(LuaMcpWorldTools::bind);
        ServerTickEvents.END_SERVER_TICK.register(server -> { var current = jobs; var bound = adapter; if (current != null && bound != null) current.tick(bound); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> unbind());
    }

    private static synchronized void bind(MinecraftServer server) {
        unbind();
        adapter = new MinecraftAdapterImpl(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev"));
        adapter.bind(server);
        executor = new MinecraftMainThreadExecutor(CONFIG.commandTimeoutMs());
        executor.attach(server::execute);
        jobs = new AsyncJobRegistry();
        context = new ToolContext(adapter, executor, EVENTS, CONFIG, MAPPER, registry(), jobs);
    }

    private static synchronized void unbind() {
        context = null;
        if (adapter != null) adapter.unbind();
        if (executor != null) executor.detach();
        if (jobs != null) jobs.shutdown();
        adapter = null; executor = null; jobs = null;
    }

    /** Runs a server tool off the game thread. The caller has already checked the owner and permission switches. */
    public static CompletableFuture<JsonObject> call(String name, JsonObject args) {
        ToolContext current = context;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("游戏服务器未运行，世界工具不可用"));
        var entry = registry().lookup(name).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("未知 MCP 工具"));
        return CompletableFuture.supplyAsync(() -> run(entry, current, args), WORKERS);
    }

    /** Executes one ported tool and wraps its MCP content blocks for {@link LuaMcpServer}. Tool errors become exceptions. */
    public static JsonObject run(ToolRegistry.Entry entry, ToolContext context, JsonObject args) {
        try {
            JsonNode arguments = MAPPER.readTree(args.toString());
            ToolResult result = entry.tool().execute(arguments, context);
            JsonObject out = new JsonObject();
            out.add(LuaMcpTools.RAW_CONTENT, JsonParser.parseString(MAPPER.writeValueAsString(result.content())));
            out.addProperty(LuaMcpTools.RAW_ERROR, result.isError());
            return out;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), failure);
        }
    }

    private static JsonObject gson(JsonNode node) {
        if (node == null) return null;
        try { return JsonParser.parseString(MAPPER.writeValueAsString(node)).getAsJsonObject(); }
        catch (Exception invalid) { return null; }
    }
}
