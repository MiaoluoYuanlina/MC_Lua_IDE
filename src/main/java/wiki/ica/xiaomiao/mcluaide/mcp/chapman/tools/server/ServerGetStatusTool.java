package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.ServerStatus;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.Jsons;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(
        name = "server_get_status",
        description =
                "Returns Minecraft server status — version, uptime, TPS/MSPT, online player count,"
                        + " loaded dimensions, mod version, registered tool count.")
public final class ServerGetStatusTool extends BaseTool {

    private static final JsonNode SCHEMA = Schemas.object().description("No arguments.").build();

    public ServerGetStatusTool() {
        super("server_get_status");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        return onMainThread(
                context,
                ignored -> {
                    ServerStatus s = context.adapter().serverGetStatus();
                    ObjectNode node = Jsons.serverStatus(context.mapper(), s);
                    // The adapter has no view of the tool registry, so it returns -1 for
                    // the count; the tool layer (which holds the registry) fills the real value.
                    if (context.registry() != null) {
                        node.put("registeredToolCount", context.registry().size());
                    }
                    return ToolResult.ofToon(node);
                });
    }
}
