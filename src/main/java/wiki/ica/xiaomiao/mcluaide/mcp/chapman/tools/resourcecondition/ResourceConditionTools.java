package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.resourcecondition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.MinecraftAdapter;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

/** Evaluates a Fabric {@code ResourceCondition} JSON object against the live registry. */
public final class ResourceConditionTools {

    private ResourceConditionTools() {}

    @McpTool(
            name = "resource_condition_evaluate",
            description =
                    "Parses a Fabric ResourceCondition JSON object and evaluates it against the"
                            + " server's registry context. Returns matches + decoded condition id.",
            requiredFabricModules = {"fabric-resource-conditions-api-v1"})
    public static final class Evaluate extends BaseTool {
        private static final JsonNode SCHEMA =
                Schemas.object()
                        .required(
                                "condition_json",
                                Schemas.string("Serialized ResourceCondition JSON object"))
                        .build();

        public Evaluate() {
            super("resource_condition_evaluate");
        }

        @Override
        public JsonNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            String json = reader(arguments).requireString("condition_json");
            return onMainThread(
                    context,
                    ignored -> {
                        MinecraftAdapter.ResourceConditionResult res =
                                context.adapter().resourceConditionEvaluate(json);
                        ObjectNode n = context.mapper().createObjectNode();
                        n.put("matches", res.matches());
                        n.put("condition_id", res.conditionId());
                        return ToolResult.ofToon(n);
                    });
        }
    }
}
