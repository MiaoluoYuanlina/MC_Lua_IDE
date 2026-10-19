package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.Map;

/** Display-only translations for dimension data. JSON keys and values stay untouched. */
final class DimensionJsonTranslations implements JsonTreeEditor.NodeTranslation {
	static final DimensionJsonTranslations INSTANCE = new DimensionJsonTranslations();

	private record Info(String label, String explanation) { }

	private static final Map<String, Info> FIELDS = Map.ofEntries(
			field("type", "维度类型", "控制维度的高度、光照、天空和物理规则；引用维度类型注册表。"),
			field("generator", "地形生成器", "定义地形、地层与生物群系的生成方式。"),
			field("generator/type", "生成器类型", "选择噪声、超平坦、调试等地形生成器。"),
			field("generator/settings", "生成设置", "噪声生成器使用预设 ID；超平坦生成器使用包含地层的对象。"),
			field("generator/biome_source", "生物群系源", "决定维度中的生物群系如何分布。"),
			field("generator/biome_source/type", "群系源类型", "可使用固定群系、多噪声等群系分布方式。"),
			field("generator/biome_source/biome", "固定生物群系", "整个维度使用的单一生物群系 ID。"),
			field("generator/biome_source/preset", "群系分布预设", "从注册表选择多噪声生物群系分布预设。"),
			field("generator/biome_source/biomes", "群系映射", "逐项定义气候参数与对应的生物群系。"),
			field("generator/biome_source/biomes/*/biome", "生物群系", "这一项气候条件对应的生物群系 ID。"),
			field("generator/biome_source/biomes/*/parameters", "气候参数", "该生物群系出现时使用的多噪声参数范围。"),
			field("generator/settings/biome", "超平坦生物群系", "超平坦世界使用的生物群系 ID。"),
			field("generator/settings/layers", "地层列表", "超平坦世界的方块层；列表顺序从底层向上。"),
			field("generator/settings/layers/*/block", "层方块", "当前地层使用的方块 ID。"),
			field("generator/settings/layers/*/height", "层厚度", "当前地层占用的方块高度。"),
			field("generator/settings/structure_overrides", "结构集覆盖", "超平坦世界允许生成的结构集。"),
			field("generator/settings/features", "生成地物", "超平坦世界是否生成地物，例如草和树。"),
			field("generator/settings/lakes", "生成湖泊", "超平坦世界是否生成湖泊。"),
			field("generator/settings/default_block", "默认地形方块", "噪声生成地形的默认方块状态。"),
			field("generator/settings/default_fluid", "默认流体", "噪声生成地形的默认流体状态。"),
			field("generator/settings/sea_level", "海平面", "世界生成使用的海平面高度。"),
			field("generator/settings/disable_mob_generation", "禁用生物生成", "启用后阻止生成器产生生物。"),
			field("generator/settings/aquifers_enabled", "启用含水层", "控制洞穴地下水和岩浆的含水层生成。"),
			field("generator/settings/ore_veins_enabled", "启用矿脉", "控制大型矿脉的生成。"),
			field("generator/settings/legacy_random_source", "旧版随机源", "使用旧版世界生成随机数算法。"),
			field("generator/settings/noise", "噪声参数", "决定地形采样高度及噪声大小。"),
			field("generator/settings/surface_rule", "地表规则", "决定草、沙、基岩等地表方块的放置规则。"),
			field("generator/settings/spawn_target", "出生目标", "世界出生点附近的气候参数范围。"),
			field("generator/settings/noise_router", "噪声路由", "组合多个密度函数，决定地形与洞穴形状。"));

	private static final Map<String, Info> COMMON = Map.ofEntries(
			field("block", "方块", "所使用的方块 ID 或方块状态。"),
			field("Name", "方块 ID", "方块状态所引用的方块 ID。"),
			field("Properties", "方块属性", "方向、半格等方块状态属性。"),
			field("height", "高度", "以方块为单位的高度。"),
			field("biome", "生物群系", "生物群系注册表中的 ID。"),
			field("temperature", "温度", "多噪声气候参数中的温度范围。"),
			field("humidity", "湿度", "多噪声气候参数中的湿度范围。"),
			field("continentalness", "大陆性", "决定海洋、海岸与内陆的气候参数。"),
			field("erosion", "侵蚀度", "影响山地和平原分布的气候参数。"),
			field("weirdness", "奇异度", "影响山峰和山谷分布的气候参数。"),
			field("depth", "深度", "气候参数中的垂直深度范围。"),
			field("offset", "偏移", "多噪声参数的匹配偏移。"),
			field("min_y", "最低高度", "生成范围的起始 Y 坐标。"),
			field("max_y", "最高高度", "生成范围的结束 Y 坐标。"),
			field("size_horizontal", "水平采样尺寸", "噪声生成时的水平采样尺寸。"),
			field("size_vertical", "垂直采样尺寸", "噪声生成时的垂直采样尺寸。"),
			field("final_density", "最终密度", "用于确定位置是否形成实心地形的密度函数。"),
			field("barrier", "屏障噪声", "地下流体屏障使用的噪声函数。"),
			field("fluid_level_floodedness", "流体淹没度", "控制地下含水层是否填充流体。"),
			field("fluid_level_spread", "流体层扩散", "控制地下流体高度变化。"));

	private static final Map<String, String> VALUES = Map.ofEntries(
			Map.entry("minecraft:overworld", "主世界"),
			Map.entry("minecraft:the_nether", "下界"),
			Map.entry("minecraft:the_end", "末地"),
			Map.entry("minecraft:noise", "噪声地形"),
			Map.entry("minecraft:flat", "超平坦"),
			Map.entry("minecraft:debug", "调试地形"),
			Map.entry("minecraft:fixed", "固定群系"),
			Map.entry("minecraft:multi_noise", "多噪声群系"),
			Map.entry("minecraft:checkerboard", "棋盘群系"),
			Map.entry("minecraft:plains", "平原"),
			Map.entry("minecraft:forest", "森林"),
			Map.entry("minecraft:desert", "沙漠"),
			Map.entry("minecraft:nether_wastes", "下界荒地"));

	private DimensionJsonTranslations() { }

	@Override public String rootLabel() { return "维度 JSON"; }

	static String dimensionName(String id) {
		if (!LuaUiText.chinese() && java.util.Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end").contains(id))
			return LuaInsertCatalog.dimensionName(net.minecraft.resources.Identifier.parse(id));
		String explicit = VALUES.get(id);
		if (explicit != null) return explicit;
		String[] parts = id.split(":", 2);
		if (parts.length != 2) return "";
		String key = "dimension." + parts[0] + "." + parts[1].replace('/', '.');
		return I18n.exists(key) ? I18n.get(key) : "";
	}

	private static Map.Entry<String, Info> field(String path, String label, String explanation) {
		return Map.entry(path, new Info(label, explanation));
	}

	@Override
	public String label(List<String> path, String key, JsonElement value) {
		Info info = info(path, key);
		return info == null ? null : info.label();
	}

	@Override
	public String description(List<String> path, String key, JsonElement value) {
		if (path.isEmpty()) return "维度定义由维度类型和地形生成器组成；修改后需校验、保存并重新加载世界数据。";
		Info info = info(path, key);
		return info == null ? "此字段没有内置译名，可能由其他模组定义；原始 JSON 键名保持不变。" : info.explanation();
	}

	@Override
	public String valueLabel(List<String> path, JsonElement value) {
		if (value == null || !value.isJsonPrimitive()) return null;
		if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean() ? "已启用" : "已关闭";
		if (!value.getAsJsonPrimitive().isString()) return null;
		String raw = value.getAsString();
		String explicit = VALUES.get(raw);
		if (explicit != null) return explicit;
		if (raw.indexOf(':') < 0) return null;
		String[] parts = raw.split(":", 2);
		if (parts.length != 2) return null;
		String pathText = String.join("/", path);
		String prefix = pathText.contains("biome") ? "biome." : pathText.equals("type") ? "dimension_type."
				: pathText.contains("block") ? "block." : "";
		if (prefix.isEmpty()) return null;
		String key = prefix + parts[0] + "." + parts[1].replace('/', '.');
		return I18n.exists(key) ? I18n.get(key) : null;
	}

	private static Info info(List<String> path, String key) {
		String normalized = String.join("/", path.stream()
				.map(part -> part.matches("[0-9]+") ? "*" : part).toList());
		Info precise = FIELDS.get(normalized);
		return precise != null ? precise : COMMON.get(key);
	}
}
