package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.GameRuleInfo;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_list_game_rules", description = "Lists every defined game rule with its current value.")
public final class LevelListGameRulesTool extends BaseTool {

    private static final JsonNode SCHEMA = Schemas.object().description("No arguments.").build();

    public LevelListGameRulesTool() {
        super("level_list_game_rules");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        return onMainThread(
                context,
                ignored -> {
                    var rules = context.adapter().levelListGameRules();
                    ArrayNode arr = context.mapper().createArrayNode();
                    for (GameRuleInfo r : rules) {
                        ObjectNode n = arr.addObject();
                        n.put("name", r.name());
                        n.put("value", r.value());
                        n.put("type", r.type());
                    }
                    return ToolResult.ofToon(arr);
                });
    }
}
