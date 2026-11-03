package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpTools;
import wiki.ica.xiaomiao.mcluaide.mcp.LuaMcpWorldTools;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.client.ClientAccessImpl;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolRegistry;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.runtime.MinecraftMainThreadExecutor;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.ClientToolRegistration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client inspection tools (view_capture, sense_*, client_status) ported with the world tools; served only by the client MCP. */
final class LuaMcpClientTools {
	private static final ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MC Lua IDE MCP client tool-", 0).factory());
	private static ToolRegistry registry;
	private static ToolContext context;
	private static List<LuaMcpTools.Tool> descriptors;

	private LuaMcpClientTools() { }

	private static synchronized ToolRegistry registry() {
		if (registry == null) {
			registry = ClientToolRegistration.buildRegistry(LuaMcpWorldTools.filter());
			MinecraftMainThreadExecutor executor = new MinecraftMainThreadExecutor(LuaMcpWorldTools.CONFIG.commandTimeoutMs());
			executor.attach(Minecraft.getInstance()::execute);
			context = new ToolContext(null, null, null, LuaMcpWorldTools.CONFIG, LuaMcpWorldTools.MAPPER, registry, null,
					new ClientAccessImpl(executor, LuaMcpWorldTools.MAPPER));
		}
		return registry;
	}

	static synchronized List<LuaMcpTools.Tool> tools() {
		if (descriptors == null) descriptors = registry().list().stream().map(LuaMcpWorldTools::describe).toList();
		return descriptors;
	}

	static boolean isClientTool(String name) { return registry().lookup(name).isPresent(); }

	/** Must not be called on the client thread: the tools wait for the client thread to sample the frame. */
	static CompletableFuture<JsonObject> call(String name, JsonObject args) {
		var entry = registry().lookup(name).orElse(null);
		if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("未知 MCP 工具"));
		ToolContext current = context;
		return CompletableFuture.supplyAsync(() -> LuaMcpWorldTools.run(entry, current, args), WORKERS);
	}
}
