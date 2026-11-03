package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(
        name = "server_save_all_worlds",
        description = "Saves every loaded world to disk. Equivalent to /save-all flush.")
public final class ServerSaveAllWorldsTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .optional(
                            "flush",
                            Schemas.bool("If true, blocks until the save fully flushes to disk. Default true."))
                    .build();

    public ServerSaveAllWorldsTool() {
        super("server_save_all_worlds");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        boolean flush = reader(arguments).optBoolean("flush", true);
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().serverSaveAllWorlds(flush);
                    return ToolResult.ofText("Saved all worlds (flush=" + flush + ").");
                });
    }
}
