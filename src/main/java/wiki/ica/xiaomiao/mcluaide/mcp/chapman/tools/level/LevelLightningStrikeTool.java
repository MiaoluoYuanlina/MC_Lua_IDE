package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.Vec3d;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(
        name = "level_lightning_strike",
        description = "Summons a lightning bolt at a position. When `cosmetic=true` the bolt is spawned via the direct API with setVisualOnly(true) so it does no damage and starts no fires; when `cosmetic=false` (the default) we dispatch /summon minecraft:lightning_bolt for parity with the command surface. Vanilla 1.21+ does not expose a command-line knob for visual-only lightning, so the cosmetic mode bypasses /summon.")
public final class LevelLightningStrikeTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required("dimension", Schemas.string("Dimension identifier"))
                    .required("position", Schemas.position3d("World position"))
                    .optional(
                            "cosmetic",
                            Schemas.bool("If true, the bolt does no damage and does not set fire."))
                    .build();

    public LevelLightningStrikeTool() {
        super("level_lightning_strike");
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
        boolean cosmetic = r.optBoolean("cosmetic", false);
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().levelLightningStrike(dim, pos, cosmetic);
                    return ToolResult.ofText("⚡");
                });
    }
}
