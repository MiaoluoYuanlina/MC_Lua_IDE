package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_get_difficulty", description = "Returns the world-wide difficulty.")
public final class LevelGetDifficultyTool extends BaseTool {

    private static final JsonNode SCHEMA = Schemas.object().description("No arguments.").build();

    public LevelGetDifficultyTool() {
        super("level_get_difficulty");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        return onMainThread(context, ignored -> ToolResult.ofText(context.adapter().levelGetDifficulty()));
    }
}
