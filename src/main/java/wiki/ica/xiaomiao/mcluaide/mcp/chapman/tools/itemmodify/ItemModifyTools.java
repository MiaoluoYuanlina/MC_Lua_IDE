package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.itemmodify;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto.Vec3i;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.ErrorCodes;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.error.McpException;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

/** Vanilla {@code /item modify} command surface. */
public final class ItemModifyTools {

    private ItemModifyTools() {}

    @McpTool(
            name = "item_modify_entity_slot",
            description = "Applies a vanilla item modifier to an entity slot.")
    public static final class EntitySlot extends BaseTool {
        private static final JsonNode SCHEMA =
                Schemas.object()
                        .required("entity_uuid", Schemas.string("Entity UUID"))
                        .required("slot", Schemas.string("Slot, e.g. container.0 or weapon.mainhand"))
                        .required("modifier_id", Schemas.string("Item modifier id"))
                        .build();

        public EntitySlot() {
            super("item_modify_entity_slot");
        }

        @Override
        public JsonNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            var r = reader(arguments);
            UUID uuid;
            try {
                uuid = UUID.fromString(r.requireString("entity_uuid"));
            } catch (IllegalArgumentException e) {
                throw new McpException(
                        ErrorCodes.TOOL_INPUT_INVALID,
                        toolName + ": invalid UUID");
            }
            String slot = r.requireString("slot");
            String mod = r.requireString("modifier_id");
            return onMainThread(
                    context,
                    ignored ->
                            ToolResult.ofText(
                                    context.adapter().itemModifyEntitySlot(uuid, slot, mod)
                                            ? "modified"
                                            : "failed"));
        }
    }

    @McpTool(
            name = "item_modify_block_slot",
            description = "Applies a vanilla item modifier to a block container slot.")
    public static final class BlockSlot extends BaseTool {
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
                        .required("slot", Schemas.string("Container slot, e.g. container.0"))
                        .required("modifier_id", Schemas.string("Item modifier id"))
                        .build();

        public BlockSlot() {
            super("item_modify_block_slot");
        }

        @Override
        public JsonNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            var r = reader(arguments);
            String dim = r.requireString("dimension");
            var p = r.requireObject("position");
            Vec3i pos = new Vec3i(p.get("x").asInt(), p.get("y").asInt(), p.get("z").asInt());
            String slot = r.requireString("slot");
            String mod = r.requireString("modifier_id");
            return onMainThread(
                    context,
                    ignored ->
                            ToolResult.ofText(
                                    context.adapter().itemModifyBlockSlot(dim, pos, slot, mod)
                                            ? "modified"
                                            : "failed"));
        }
    }
}
