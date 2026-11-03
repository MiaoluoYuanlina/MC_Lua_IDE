package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.server;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "server_set_motd", description = "Sets the server MOTD. Note: not persisted across restarts.")
public final class ServerSetMotdTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object().required("motd", Schemas.string("The new MOTD string.")).build();

    public ServerSetMotdTool() {
        super("server_set_motd");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String motd = reader(arguments).requireString("motd");
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().serverSetMotd(motd);
                    return ToolResult.ofText("MOTD updated.");
                });
    }
}
