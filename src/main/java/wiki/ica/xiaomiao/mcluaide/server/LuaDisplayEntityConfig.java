package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The three display entities store their visual properties as entity NBT, not data components. */
final class LuaDisplayEntityConfig {
	private static final Set<String> COMMON_FIELDS = Set.of("transformation", "interpolation_duration",
			"start_interpolation", "teleport_duration", "billboard", "brightness", "view_range",
			"shadow_radius", "shadow_strength", "width", "height", "glow_color_override");
	private static final Set<String> TEXT_FIELDS = Set.of("text", "line_width", "text_opacity", "background",
			"shadow", "see_through", "default_background", "alignment");
	private LuaDisplayEntityConfig() { }

	record Field(String id, String label, String description, JsonElement initial,
			ComponentTreeSchema.Node schema) { }

	static boolean isDisplay(Entity entity) { return entity instanceof Display; }

	static List<Field> fields(Entity entity) {
		if (!isDisplay(entity)) return List.of();
		List<Field> fields = new ArrayList<>();
		add(fields, "transformation", "变换", "平移、缩放及左右旋转；旋转为四元数 [x,y,z,w]",
				"{\"translation\":[0,0,0],\"left_rotation\":[0,0,0,1],\"scale\":[1,1,1],\"right_rotation\":[0,0,0,1]}",
				object("变换", "{}", Map.of(
					"translation", vector("平移", 0, 0, 0), "scale", vector("缩放", 1, 1, 1),
					"left_rotation", vector("左旋转四元数", 0, 0, 0, 1),
					"right_rotation", vector("右旋转四元数", 0, 0, 0, 1))));
		add(fields, "interpolation_duration", "变换插值时长", "变换平滑过渡的游戏刻数", "0", integer("游戏刻数", 0));
		add(fields, "start_interpolation", "开始插值延迟", "变换插值开始前等待的游戏刻数", "0", integer("游戏刻数", 0));
		add(fields, "teleport_duration", "位置旋转插值时长", "传送及实体朝向平滑过渡，游戏限制为 0～59 刻", "0", integer("游戏刻数", 0));
		add(fields, "billboard", "面向镜头", "展示面如何朝向玩家镜头", "\"fixed\"",
				ComponentTreeSchema.choices("面向方式", "fixed", "fixed", "vertical", "horizontal", "center"));
		add(fields, "brightness", "亮度覆盖", "分别设置天空光和方块光，范围均为 0～15", "{\"sky\":15,\"block\":15}",
				object("亮度", "{}", Map.of("sky", integer("天空光", 15), "block", integer("方块光", 15))));
		add(fields, "view_range", "可见距离系数", "相对于默认渲染距离的倍数", "1.0", number("距离系数", 1));
		add(fields, "shadow_radius", "阴影半径", "展示实体阴影的半径", "0.0", number("半径", 0));
		add(fields, "shadow_strength", "阴影强度", "展示实体阴影的不透明度", "1.0", number("强度", 1));
		add(fields, "width", "剔除宽度", "用于可见性剔除的宽度，不是模型缩放", "0.0", number("宽度", 0));
		add(fields, "height", "剔除高度", "用于可见性剔除的高度，不是模型缩放", "0.0", number("高度", 0));
		add(fields, "glow_color_override", "发光轮廓颜色", "RGB 整数；-1 表示使用队伍颜色，需同时开启发光", "-1", integer("RGB 颜色", -1));
		if (entity instanceof Display.ItemDisplay) {
			add(fields, "item", "展示物品堆", "物品 ID、数量及完整物品数据组件", "{\"id\":\"minecraft:stone\",\"count\":1}",
				ComponentTreeSchema.itemStack());
			add(fields, "item_display", "物品展示姿态", "应用的物品模型显示上下文", "\"none\"",
				ComponentTreeSchema.choices("展示姿态", "none", "none", "thirdperson_lefthand", "thirdperson_righthand",
						"firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed", "on_shelf"));
		} else if (entity instanceof Display.BlockDisplay) {
			Map<String, ComponentTreeSchema.Node> state = new LinkedHashMap<>();
			state.put("Name", new ComponentTreeSchema.Node("方块 ID", "string", JsonParser.parseString("\"minecraft:stone\""),
					Map.of(), null, List.of(), "minecraft:block", false));
			state.put("Properties", new ComponentTreeSchema.Node("方块状态属性", "object", new JsonObject(),
					Map.of(), ComponentTreeSchema.scalar("属性值", "string", "\"\""), List.of(), "", true));
			add(fields, "block_state", "展示方块状态", "方块 ID 与该方块支持的状态属性；不包括方块实体物品数据", "{\"Name\":\"minecraft:stone\"}",
					object("方块状态", "{}", state));
		} else if (entity instanceof Display.TextDisplay) {
			add(fields, "text", "展示文字", "JSON 文本组件，支持颜色、点击和悬浮事件", "{\"text\":\"文字\"}", ComponentTreeSchema.textComponent());
			add(fields, "line_width", "换行宽度", "文字自动换行的像素宽度", "200", integer("像素", 200));
			add(fields, "text_opacity", "文字不透明度", "字节值 -128～127；-1 表示完全不透明", "-1", integer("不透明度字节", -1));
			add(fields, "background", "背景颜色", "ARGB 32 位整数，包含透明度", "1073741824", integer("ARGB 颜色", 1073741824));
			add(fields, "shadow", "文字阴影", "是否绘制文字阴影", "true", ComponentTreeSchema.scalar("文字阴影", "boolean", "true"));
			add(fields, "see_through", "透过方块显示", "文字是否无视深度遮挡", "false", ComponentTreeSchema.scalar("透视", "boolean", "false"));
			add(fields, "default_background", "默认背景", "使用游戏默认背景颜色", "false", ComponentTreeSchema.scalar("默认背景", "boolean", "false"));
			add(fields, "alignment", "文字对齐", "多行文字的对齐方式", "\"center\"",
				ComponentTreeSchema.choices("对齐方式", "center", "left", "center", "right"));
		}
		return List.copyOf(fields);
	}

	static boolean supports(Entity entity, String key) {
		return entity instanceof Display && (COMMON_FIELDS.contains(key)
				|| entity instanceof Display.ItemDisplay && (key.equals("item") || key.equals("item_display"))
				|| entity instanceof Display.BlockDisplay && key.equals("block_state")
				|| entity instanceof Display.TextDisplay && TEXT_FIELDS.contains(key));
	}

	static void apply(MinecraftServer server, Entity entity, JsonObject values) {
		if (values.isEmpty()) return;
		if (!isDisplay(entity)) throw new IllegalArgumentException("该实体不是展示实体");
		TagValueOutput patch = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		for (var field : values.entrySet()) {
			String key = field.getKey();
			JsonElement value = field.getValue();
			if (!supports(entity, key)) throw new IllegalArgumentException("此展示实体不支持字段：" + key);
			switch (key) {
				case "transformation" -> store(patch, key, Transformation.EXTENDED_CODEC, ops, completeTransformation(value));
				case "billboard" -> store(patch, key, Display.BillboardConstraints.CODEC, ops, value);
				case "brightness" -> store(patch, key, Brightness.CODEC, ops, value);
				case "item" -> store(patch, key, ItemStack.CODEC, ops, value);
				case "item_display" -> store(patch, key, ItemDisplayContext.CODEC, ops, value);
				case "block_state" -> store(patch, key, BlockState.CODEC, ops, value);
				case "text" -> store(patch, key, ComponentSerialization.CODEC, ops, value);
				case "alignment" -> store(patch, key, Display.TextDisplay.Align.CODEC, ops, value);
				case "shadow", "see_through", "default_background" -> patch.putBoolean(key, booleanValue(key, value));
				case "interpolation_duration", "start_interpolation", "teleport_duration", "glow_color_override",
						"line_width", "background" -> {
					int number = integerValue(key, value);
					if (key.equals("teleport_duration") && (number < 0 || number > 59))
						throw new IllegalArgumentException("teleport_duration 必须在 0～59 之间");
					if ((key.equals("interpolation_duration") || key.equals("start_interpolation") || key.equals("line_width")) && number < 0)
						throw new IllegalArgumentException(key + " 不能为负数");
					patch.putInt(key, number);
				}
				case "text_opacity" -> {
					int number = integerValue(key, value);
					if (number < -128 || number > 255) throw new IllegalArgumentException("text_opacity 必须在 -128～255 之间");
					patch.putByte(key, (byte) number);
				}
				case "view_range", "shadow_radius", "shadow_strength", "width", "height" -> {
					float number = floatValue(key, value);
					if (number < 0) throw new IllegalArgumentException(key + " 不能为负数");
					patch.putFloat(key, number);
				}
				default -> throw new IllegalArgumentException("未知展示实体字段：" + key);
			}
		}
		// Reload a complete snapshot so omitted entity fields retain their original values.
		TagValueOutput base = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		entity.saveWithoutId(base);
		var merged = base.buildResult();
		merged.merge(patch.buildResult());
		entity.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), merged));
	}

	private static <T> void store(TagValueOutput output, String key, Codec<T> codec,
			RegistryOps<JsonElement> ops, JsonElement raw) {
		T value = codec.parse(ops, raw).getOrThrow(problem -> new IllegalArgumentException(key + " 无效：" + problem));
		output.store(key, codec, value);
	}

	static JsonElement completeTransformation(JsonElement value) {
		if (!value.isJsonObject()) return value;
		JsonObject result = JsonParser.parseString("{\"translation\":[0,0,0],\"left_rotation\":[0,0,0,1],\"scale\":[1,1,1],\"right_rotation\":[0,0,0,1]}").getAsJsonObject();
		value.getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue().deepCopy()));
		return result;
	}

	private static int integerValue(String key, JsonElement value) {
		try {
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | IllegalStateException | NumberFormatException ex) {
			throw new IllegalArgumentException(key + " 必须为 32 位整数", ex);
		}
	}

	private static float floatValue(String key, JsonElement value) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
			throw new IllegalArgumentException(key + " 必须为数字");
		float number = value.getAsFloat();
		if (!Float.isFinite(number)) throw new IllegalArgumentException(key + " 必须为有限数字");
		return number;
	}

	private static boolean booleanValue(String key, JsonElement value) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
			throw new IllegalArgumentException(key + " 必须为 true 或 false");
		return value.getAsBoolean();
	}

	private static void add(List<Field> fields, String id, String label, String description,
			String initial, ComponentTreeSchema.Node schema) {
		fields.add(new Field(id, label, description, JsonParser.parseString(initial), schema));
	}

	private static ComponentTreeSchema.Node object(String label, String seed, Map<String, ComponentTreeSchema.Node> fields) {
		return new ComponentTreeSchema.Node(label, "object", JsonParser.parseString(seed), fields, null, List.of(), "", false);
	}

	private static ComponentTreeSchema.Node integer(String label, int value) {
		return ComponentTreeSchema.scalar(label, "integer", Integer.toString(value));
	}

	private static ComponentTreeSchema.Node number(String label, double value) {
		return ComponentTreeSchema.scalar(label, "number", Double.toString(value));
	}

	private static ComponentTreeSchema.Node vector(String label, double... values) {
		var array = new com.google.gson.JsonArray();
		for (double value : values) array.add(value);
		return new ComponentTreeSchema.Node(label, "array", array, Map.of(), number("坐标/旋转分量", 0), List.of(), "", false);
	}
}
