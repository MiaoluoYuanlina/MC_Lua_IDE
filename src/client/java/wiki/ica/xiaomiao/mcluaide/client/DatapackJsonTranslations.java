package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;

/** Common display-only labels; the original JSON keys are never translated on export. */
final class DatapackJsonTranslations implements JsonTreeEditor.NodeTranslation {
	private static final Map<String, String> LABELS = Map.ofEntries(
			Map.entry("type", "类型"), Map.entry("values", "值列表"), Map.entry("replace", "替换"),
			Map.entry("description", "描述"), Map.entry("criteria", "触发条件"),
			Map.entry("requirements", "完成条件"), Map.entry("rewards", "奖励"),
			Map.entry("display", "显示"), Map.entry("parent", "父项"),
			Map.entry("result", "产物"), Map.entry("ingredients", "原料列表"),
			Map.entry("ingredient", "原料"), Map.entry("pattern", "合成图案"),
			Map.entry("key", "图案键"), Map.entry("count", "数量"),
			Map.entry("item", "物品"), Map.entry("block", "方块"),
			Map.entry("biome", "生物群系"), Map.entry("entity", "实体"),
			Map.entry("functions", "函数列表"), Map.entry("conditions", "条件列表"),
			Map.entry("pools", "抽取池"), Map.entry("entries", "条目"),
			Map.entry("name", "名称"), Map.entry("weight", "权重"),
			Map.entry("quality", "品质"), Map.entry("rolls", "抽取次数"),
			Map.entry("bonus_rolls", "额外抽取次数"), Map.entry("effects", "效果"),
			Map.entry("slots", "装备槽"), Map.entry("max_level", "最高等级"),
			Map.entry("min_cost", "最低附魔花费"), Map.entry("max_cost", "最高附魔花费"));

	private final String type;
	private final String purpose;

	DatapackJsonTranslations(String type, String purpose) {
		this.type = type;
		this.purpose = purpose;
	}

	@Override public String rootLabel() { return type + " JSON"; }
	@Override public String label(List<String> path, String key, JsonElement value) { return LABELS.get(key); }
	@Override public String description(List<String> path, String key, JsonElement value) {
		return path.isEmpty() ? purpose + "。灰色字段从当前游戏已加载示例中汇总，不代表完整格式。"
				: LABELS.containsKey(key) ? LABELS.get(key) + "；保留原始 JSON 键名。" : "当前游戏示例字段或自定义字段；请按对应模组格式填写。";
	}
	@Override public String valueLabel(List<String> path, JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
				? value.getAsBoolean() ? "是" : "否" : null;
	}
}
