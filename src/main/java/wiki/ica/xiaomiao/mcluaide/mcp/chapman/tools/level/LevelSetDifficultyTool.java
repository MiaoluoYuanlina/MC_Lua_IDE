package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_set_difficulty", description = "Sets the world-wide difficulty.", admin = true)
public final class LevelSetDifficultyTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required(
                            "difficulty",
                            Schemas.enumOf("Difficulty", "peaceful", "easy", "normal", "hard"))
                    .build();

    public LevelSetDifficultyTool() {
        super("level_set_difficulty");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String d = reader(arguments).requireString("difficulty");
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().levelSetDifficulty(d);
                    return ToolResult.ofText("Difficulty set to " + d);
                });
    }
}
