package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import com.mojang.serialization.JsonOps;
import com.mojang.math.Transformation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.ComponentSerialization;
import org.luaj.vm2.LuaValue;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;
import java.util.List;

final class ComponentTreeSchemaSmoke {
	static void run(RegistryAccess registries) {
		var displayOps = RegistryOps.create(JsonOps.INSTANCE, registries);
		Transformation.EXTENDED_CODEC.parse(displayOps, LuaDisplayEntityConfig.completeTransformation(
				JsonParser.parseString("{\"translation\":[0,0.5,0],\"scale\":[1,1,1]}")))
				.getOrThrow(problem -> new AssertionError("Display transformation: " + problem));
		Brightness.CODEC.parse(displayOps, JsonParser.parseString("{\"sky\":15,\"block\":15}"))
				.getOrThrow(problem -> new AssertionError("Display brightness: " + problem));
		BlockState.CODEC.parse(displayOps, JsonParser.parseString("{\"Name\":\"minecraft:oak_stairs\",\"Properties\":{\"facing\":\"north\"}}"))
				.getOrThrow(problem -> new AssertionError("Block display state: " + problem));
		ItemDisplayContext.CODEC.parse(displayOps, new JsonPrimitive("on_shelf"))
				.getOrThrow(problem -> new AssertionError("Item display context: " + problem));
		Display.BillboardConstraints.CODEC.parse(displayOps, new JsonPrimitive("center"))
				.getOrThrow(problem -> new AssertionError("Display billboard: " + problem));
		Display.TextDisplay.Align.CODEC.parse(displayOps, new JsonPrimitive("center"))
				.getOrThrow(problem -> new AssertionError("Text display alignment: " + problem));
		ComponentSerialization.CODEC.parse(displayOps, JsonParser.parseString("{\"text\":\"欢迎\"}"))
				.getOrThrow(problem -> new AssertionError("Text display text: " + problem));
		var equipmentStack = ComponentTreeSchema.itemStack();
		if (!equipmentStack.fields().containsKey("id") || !equipmentStack.fields().containsKey("components")
				|| ComponentTreeSchema.itemComponents().fields().containsKey("health")) {
			throw new AssertionError("Entity equipment must contain item stacks, not entity state fields");
		}
		JsonObject blockCatalog = JsonParser.parseString(LuaComponentEditorCatalog.describe(
				null, "block", "minecraft:oak_stairs")).getAsJsonObject();
		boolean foundStates = false;
		for (JsonElement raw : blockCatalog.getAsJsonArray("components")) {
			JsonObject field = raw.getAsJsonObject();
			if (field.get("id").getAsString().equals("minecraft:max_stack_size"))
				throw new AssertionError("Block editor leaked item-stack components");
			if (field.get("id").getAsString().equals("states")) {
				foundStates = field.getAsJsonObject("schema").getAsJsonObject("fields").has("facing");
			}
		}
		if (!foundStates) throw new AssertionError("Block editor omitted facing property");
		String blockConfig = LuaComponentEditorCatalog.validate(null, "block\nminecraft:oak_stairs",
				"{\"states\":{\"facing\":\"north\",\"half\":\"top\"}}");
		if (!blockConfig.contains("north") || !blockConfig.contains("top"))
			throw new AssertionError("Block configuration lost validated properties");
		try {
			LuaComponentEditorCatalog.validate(null, "block\nminecraft:oak_stairs",
					"{\"states\":{\"facing\":\"invalid\"}}");
			throw new AssertionError("Invalid block facing was accepted");
		} catch (IllegalArgumentException expected) {
			// Invalid state values must be rejected before a script changes the world.
		}
		JsonObject chestCatalog = JsonParser.parseString(LuaComponentEditorCatalog.describe(
				null, "block", "minecraft:chest")).getAsJsonObject();
		JsonObject chestData = blockEntitySchema(chestCatalog);
		JsonObject itemList = chestData.getAsJsonObject("fields").getAsJsonObject("Items");
		if (!itemList.getAsJsonObject("element").getAsJsonObject("fields").has("Slot")
				|| !itemList.getAsJsonObject("element").getAsJsonObject("fields").has("id"))
			throw new AssertionError("Chest editor lacks slot and item ID fields");
		String chestItems = "{\"Items\":[{\"Slot\":0,\"id\":\"minecraft:diamond\",\"count\":2}]}";
		LuaComponentEditorCatalog.validate(null, "block\nminecraft:chest", "{\"blockEntityData\":" + chestItems + "}");
		String emptyChest = LuaComponentEditorCatalog.validate(null, "block\nminecraft:chest",
				"{\"blockEntityData\":{\"Items\":{}}}");
		if (!emptyChest.contains("\"Items\":[]")) throw new AssertionError("Empty Lua container list was not normalized");
		try {
			LuaComponentEditorCatalog.validate(null, "block\nminecraft:chest",
					"{\"blockEntityData\":{\"Items\":[{\"Slot\":27,\"id\":\"minecraft:stone\"}]}}");
			throw new AssertionError("Out-of-range chest slot was accepted");
		} catch (IllegalArgumentException expected) {
			// A chest half has only slots 0..26.
		}
		JsonObject furnaceCatalog = JsonParser.parseString(LuaComponentEditorCatalog.describe(
				null, "block", "minecraft:furnace")).getAsJsonObject();
		JsonObject furnaceFields = blockEntitySchema(furnaceCatalog).getAsJsonObject("fields");
		for (String field : List.of("Items", "cooking_time_spent", "cooking_total_time",
				"lit_time_remaining", "lit_total_time")) {
			if (!furnaceFields.has(field)) throw new AssertionError("Furnace editor lacks " + field);
		}
		LuaComponentEditorCatalog.validate(null, "block\nminecraft:furnace",
				"{\"blockEntityData\":{\"Items\":[{\"Slot\":0,\"id\":\"minecraft:iron_ore\",\"count\":1}],\"cooking_time_spent\":40}}");
		try {
			LuaComponentEditorCatalog.validate(null, "block\nminecraft:furnace",
					"{\"blockEntityData\":{\"lit_time_remaining\":32768}}");
			throw new AssertionError("Overflowing furnace timer was accepted");
		} catch (IllegalArgumentException expected) {
			// Furnace timers are stored as shorts in this Minecraft version.
		}
		JsonObject signCatalog = JsonParser.parseString(LuaComponentEditorCatalog.describe(
				null, "block", "minecraft:oak_sign")).getAsJsonObject();
		JsonObject signFields = blockEntitySchema(signCatalog).getAsJsonObject("fields");
		if (!signFields.has("front_text") || !signFields.has("back_text") || !signFields.has("is_waxed"))
			throw new AssertionError("Sign editor lacks text faces");
		String signText = "{\"front_text\":{\"messages\":[{\"text\":\"第一行\"},{\"text\":\"\"},{\"text\":\"\"},{\"text\":\"\"}]}}";
		LuaComponentEditorCatalog.validate(null, "block\nminecraft:oak_sign", "{\"blockEntityData\":" + signText + "}");
		BlockEntity sign = newBlockEntity("minecraft:oak_sign");
		sign.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries,
				toTag(signText)));
		if (!(sign instanceof SignBlockEntity signEntity)
				|| !signEntity.getText(true).getMessage(0, false).getString().equals("第一行"))
			throw new AssertionError("Sign text did not survive block entity loading");
		for (String id : List.of("food", "consumable", "tool", "attribute_modifiers", "equippable", "repairable",
				"container", "bundle_contents", "charged_projectiles", "use_remainder", "potion_contents",
				"death_protection", "tooltip_display", "use_cooldown", "custom_model_data", "profile", "custom_name", "can_break", "can_place_on")) {
			var schema = ComponentTreeSchema.component("minecraft:" + id, new JsonObject());
			JsonObject patch = new JsonObject(); patch.add("minecraft:" + id, schema.value());
			LuaItemStackCodec.normalizeComponents(registries, LuaValue.valueOf(patch.toString()));
			if (!ComponentTreeSchema.Node.decode(schema.encode()).encode().equals(schema.encode())) throw new AssertionError("Schema wire round trip: " + id);
		}
		var potion = ComponentTreeSchema.component("minecraft:potion_contents", new JsonObject());
		LuaItemStackCodec.normalizeComponents(registries, LuaValue.valueOf("{\"minecraft:can_break\":[{\"blocks\":\"minecraft:chest\",\"state\":{\"facing\":\"north\"},\"nbt\":{\"Items\":[]}}]}"));
		if (!potion.fields().containsKey("custom_effects") || !potion.seed().getAsJsonObject().isEmpty()) throw new AssertionError("Optional fields must remain virtual");
		var remainder = ComponentTreeSchema.component("minecraft:use_remainder", new JsonPrimitive("minecraft:glass_bottle"));
		if (!remainder.seed().getAsJsonObject().get("id").getAsString().equals("minecraft:glass_bottle")) throw new AssertionError("Lost compact stack default");
		var tool = ComponentTreeSchema.withHints(ComponentTreeSchema.component("minecraft:container", new JsonArray()), McLuaIdeRegistryApi.componentHints("minecraft:container"), "zh_cn");
		if (tool.element().fields().containsKey("id")) throw new AssertionError("Legacy hint must not create a non-existent container slot field");
		var consume = ComponentTreeSchema.component("minecraft:consumable", new JsonObject()).fields().get("on_consume_effects").element();
		for (String effect : List.of("remove_effects", "clear_all_effects", "teleport_randomly", "play_sound", "apply_effects")) {
			var variant = ComponentTreeSchema.variant(consume, JsonParser.parseString("{\"type\":\"minecraft:" + effect + "\"}"));
			JsonObject entry = variant.value().getAsJsonObject();
			JsonArray list = new JsonArray(); list.add(entry);
			JsonObject value = new JsonObject(); value.add("on_consume_effects", list);
			JsonObject patch = new JsonObject(); patch.add("minecraft:consumable", value);
			LuaItemStackCodec.normalizeComponents(registries, LuaValue.valueOf(patch.toString()));
			if (effect.equals("remove_effects") && !variant.fields().get("effects").element().kind().equals("string")) throw new AssertionError("Remove-effects requires effect IDs, not effect objects");
		}
		System.out.println("Component tree schema smoke test passed: typed templates, optional fields, codec validation and wire round trip");
	}

	private static JsonObject blockEntitySchema(JsonObject catalog) {
		for (JsonElement raw : catalog.getAsJsonArray("components")) {
			JsonObject field = raw.getAsJsonObject();
			if (field.get("id").getAsString().equals("blockEntityData")) return field.getAsJsonObject("schema");
		}
		throw new AssertionError("Block entity data editor is missing");
	}

	private static BlockEntity newBlockEntity(String id) {
		var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
		return ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
	}

	private static CompoundTag toTag(String json) {
		return (CompoundTag) JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, JsonParser.parseString(json));
	}
}
