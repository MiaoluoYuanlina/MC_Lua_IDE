package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.Vec3i;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_set_spawn_point", description = "Sets the world spawn point for a dimension.")
public final class LevelSetSpawnPointTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required("dimension", Schemas.string("Dimension identifier"))
                    .required(
                            "position",
                            Schemas.object()
                                    .required("x", Schemas.integer("X"))
                                    .required("y", Schemas.integer("Y"))
                                    .required("z", Schemas.integer("Z"))
                                    .build())
                    .build();

    public LevelSetSpawnPointTool() {
        super("level_set_spawn_point");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        var r = reader(arguments);
        String dim = r.requireString("dimension");
        var pos = r.requireObject("position");
        Vec3i p = new Vec3i(pos.get("x").asInt(), pos.get("y").asInt(), pos.get("z").asInt());
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().levelSetSpawnPoint(dim, p);
                    return ToolResult.ofText("Spawn set to (" + p.x() + "," + p.y() + "," + p.z() + ")");
                });
    }
}
