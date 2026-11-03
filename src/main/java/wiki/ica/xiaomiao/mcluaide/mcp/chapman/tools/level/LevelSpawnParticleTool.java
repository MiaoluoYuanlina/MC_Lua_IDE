package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.Vec3d;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_spawn_particle", description = "Spawns particles at a position in a dimension.")
public final class LevelSpawnParticleTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required("dimension", Schemas.string("Dimension identifier"))
                    .required("position", Schemas.position3d("World position"))
                    .required("particle_id", Schemas.string("Particle identifier (e.g. minecraft:flame)"))
                    .optional("count", Schemas.integer("Particle count (default 1)"))
                    .optional("offset", Schemas.position3d("Random offset (default 0,0,0)"))
                    .optional("speed", Schemas.number("Particle speed (default 0)"))
                    .build();

    public LevelSpawnParticleTool() {
        super("level_spawn_particle");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        var r = reader(arguments);
        String dim = r.requireString("dimension");
        var posN = r.requireObject("position");
        Vec3d pos = new Vec3d(posN.get("x").asDouble(), posN.get("y").asDouble(), posN.get("z").asDouble());
        String particle = r.requireString("particle_id");
        int count = r.optInt("count", 1);
        var offN = r.optObject("offset");
        Vec3d offset =
                offN == null
                        ? new Vec3d(0, 0, 0)
                        : new Vec3d(offN.get("x").asDouble(), offN.get("y").asDouble(), offN.get("z").asDouble());
        double speed = r.optDouble("speed", 0);
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().levelSpawnParticle(dim, pos, particle, count, offset, speed);
                    return ToolResult.ofText("Spawned " + count + " " + particle);
                });
    }
}
