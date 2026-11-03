package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "server_reload_resources", description = "Reloads datapacks and resources. Equivalent to /reload.", admin = true)
public final class ServerReloadResourcesTool extends BaseTool {

    private static final JsonNode SCHEMA = Schemas.object().description("No arguments.").build();

    public ServerReloadResourcesTool() {
        super("server_reload_resources");
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
                    context.adapter().serverReloadResources();
                    return ToolResult.ofText("Resources reloading…");
                });
    }
}
