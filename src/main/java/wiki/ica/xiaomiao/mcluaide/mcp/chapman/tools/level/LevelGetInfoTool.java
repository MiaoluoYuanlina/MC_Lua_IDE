package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.LevelInfo;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.ErrorCodes;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.McpException;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.Jsons;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(
        name = "level_get_info",
        description =
                "Returns dynamic state of one dimension: time, weather, difficulty, default game mode,"
                        + " spawn point. weather is the weather the server has set; rainLevel and"
                        + " thunderLevel (0.0-1.0) are what the sky shows, which fades towards the"
                        + " set weather over a few seconds after a change.")
public final class LevelGetInfoTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object().required("dimension", Schemas.string("Dimension identifier")).build();

    public LevelGetInfoTool() {
        super("level_get_info");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String dim = reader(arguments).requireString("dimension");
        return onMainThread(
                context,
                ignored -> {
                    LevelInfo info =
                            context.adapter()
                                    .levelGetInfo(dim)
                                    .orElseThrow(
                                            () ->
                                                    new McpException(
                                                            ErrorCodes.TOOL_HANDLER_ERROR,
                                                            "Unknown dimension: " + dim));
                    return ToolResult.ofToon(Jsons.levelInfo(context.mapper(), info));
                });
    }
}
