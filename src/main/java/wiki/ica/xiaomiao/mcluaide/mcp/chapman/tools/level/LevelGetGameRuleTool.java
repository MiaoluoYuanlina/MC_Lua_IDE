package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.GameRuleInfo;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.ErrorCodes;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.McpException;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_get_game_rule", description = "Returns the value of a single game rule.")
public final class LevelGetGameRuleTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required("name", Schemas.string("Game rule id (e.g. randomTickSpeed, doMobSpawning) -- call level_list_game_rules to see the live registry."))
                    .build();

    public LevelGetGameRuleTool() {
        super("level_get_game_rule");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String name = reader(arguments).requireString("name");
        return onMainThread(
                context,
                ignored -> {
                    GameRuleInfo info =
                            context.adapter()
                                    .levelGetGameRule(name)
                                    .orElseThrow(
                                            () ->
                                                    new McpException(
                                                            ErrorCodes.TOOL_HANDLER_ERROR,
                                                            "Unknown game rule: " + name));
                    ObjectNode n = context.mapper().createObjectNode();
                    n.put("name", info.name());
                    n.put("value", info.value());
                    n.put("type", info.type());
                    return ToolResult.ofToon(n);
                });
    }
}
