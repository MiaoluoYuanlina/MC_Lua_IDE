package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.DimensionInfo;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.ErrorCodes;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.McpException;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(
        name = "level_get_dimension_info",
        description = "Returns dimension type, height range, current time, and core dimension flags. The legacy biomeSource field was removed in v0.2.x; call level_list_biomes_in_dimension instead.")
public final class LevelGetDimensionInfoTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required("dimension", Schemas.string("Dimension identifier (e.g. minecraft:overworld)"))
                    .build();

    public LevelGetDimensionInfoTool() {
        super("level_get_dimension_info");
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
                    DimensionInfo d =
                            context.adapter()
                                    .levelGetDimensionInfo(dim)
                                    .orElseThrow(
                                            () ->
                                                    new McpException(
                                                            ErrorCodes.TOOL_HANDLER_ERROR,
                                                            "Unknown dimension: " + dim));
                    ObjectNode payload = context.mapper().createObjectNode();
                    payload.put("id", d.id());
                    payload.put("typeId", d.typeId());
                    payload.put("minY", d.minY());
                    payload.put("maxY", d.maxY());
                    payload.put("timeOfDay", d.timeOfDay());
                    payload.put("hasCeiling", d.hasCeiling());
                    payload.put("ultrawarm", d.ultrawarm());
                    payload.put("piglinSafe", d.piglinSafe());
                    payload.put("natural", d.natural());
                    return ToolResult.ofToon(payload);
                });
    }
}
