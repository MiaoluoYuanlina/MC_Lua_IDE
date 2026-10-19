package wiki.ica.xiaomiao.mcluaide.editor;

import java.util.List;

/** Directory names and short explanations for the data-pack format used by this Minecraft version. */
public final class DatapackScaffoldCatalog {
	public record Category(String path, String label, String purpose, String extension, String template) {
		public boolean textFile() { return extension.equals(".json") || extension.equals(".mcfunction"); }
	}

	private static final String EMPTY_JSON = "{}\n";
	private static final String EMPTY_TAG = "{\n  \"replace\": false,\n  \"values\": []\n}\n";
	private static final List<Category> CATEGORIES = List.of(
		json("advancement", "进度", "定义进度的触发条件与奖励"),
		json("recipe", "配方", "定义工作台、熔炉等合成配方"),
		json("loot_table", "战利品表", "定义方块、实体或容器产生的战利品"),
		json("predicate", "谓词", "可复用的条件判断，供命令和战利品表引用"),
		json("item_modifier", "物品修饰器", "对物品堆叠应用战利品函数"),
		new Category("function", "函数", "逐行执行 Minecraft 命令，可由 /function 调用", ".mcfunction",
				"# 每行输入一条 Minecraft 命令，不要带开头的 /\n"),
		folder("tags", "标签", "按注册表类型归类一组 ID"),
		folder("worldgen", "世界生成", "群系、地形、结构等世界生成配置"),
		new Category("structure", "结构", "存放二进制 .nbt 结构模板；请使用结构方块或外部工具导入", ".nbt", ""),
		json("dimension", "维度", "定义维度及其生成设置"),
		json("dimension_type", "维度类型", "定义维度的天空、时间、高度等规则"),
		json("enchantment", "魔咒", "定义魔咒等级、槽位和效果"),
		json("enchantment_provider", "魔咒提供器", "定义附魔台等来源选择魔咒的方式"),
		json("damage_type", "伤害类型", "定义伤害消息、缩放和归属"),
		json("chat_type", "聊天类型", "定义聊天消息的显示格式"),
		json("dialog", "对话框", "定义游戏内可交互的对话界面"),
		json("trial_spawner", "试炼刷怪笼", "定义试炼刷怪笼的生成与奖励配置"),
		json("jukebox_song", "唱片机曲目", "定义唱片对应的声音与播放时长"),
		json("instrument", "山羊角乐器", "定义山羊角声音与演奏时长"),
		json("painting_variant", "画变种", "定义画的尺寸与资源"),
		json("banner_pattern", "旗帜图案", "定义可用于旗帜的图案资源"),
		json("trim_material", "盔甲纹饰材料", "定义纹饰材料的外观与描述"),
		json("trim_pattern", "盔甲纹饰图案", "定义纹饰图案的资源与描述"),
		json("cat_variant", "猫变种", "定义猫的外观变种"),
		json("cat_sound_variant", "猫声音变种", "定义猫的声音变种"),
		json("frog_variant", "青蛙变种", "定义青蛙的外观变种"),
		json("wolf_variant", "狼变种", "定义狼的外观变种"),
		json("wolf_sound_variant", "狼声音变种", "定义狼的声音变种"),
		json("cow_variant", "牛变种", "定义牛的外观变种"),
		json("cow_sound_variant", "牛声音变种", "定义牛的声音变种"),
		json("chicken_variant", "鸡变种", "定义鸡的外观变种"),
		json("chicken_sound_variant", "鸡声音变种", "定义鸡的声音变种"),
		json("pig_variant", "猪变种", "定义猪的外观变种"),
		json("pig_sound_variant", "猪声音变种", "定义猪的声音变种"),
		json("zombie_nautilus_variant", "僵尸鹦鹉螺变种", "定义僵尸鹦鹉螺的外观变种"),
		json("trade_set", "交易集合", "定义可复用的村民交易集合"),
		json("villager_trade", "村民交易", "定义村民交易的具体内容"),
		json("world_clock", "世界时钟", "定义世界时间与时钟行为"),
		json("timeline", "时间线", "定义随游戏时间变化的规则"),
		json("test_environment", "测试环境", "定义游戏测试使用的环境"),
		json("test_instance", "测试实例", "定义游戏测试的实例配置"),
		json("worldgen/biome", "生物群系", "定义群系气候、效果与生成规则"),
		json("worldgen/configured_carver", "洞穴雕刻器", "配置洞穴或峡谷雕刻"),
		json("worldgen/configured_feature", "已配置地物", "定义树木、矿脉等地物的生成内容"),
		json("worldgen/placed_feature", "已放置地物", "指定地物的生成位置与频率"),
		json("worldgen/density_function", "密度函数", "控制地形噪声的密度计算"),
		json("worldgen/noise", "噪声参数", "定义地形生成的噪声参数"),
		json("worldgen/noise_settings", "噪声设置", "定义世界地形高度、噪声与地表"),
		json("worldgen/structure", "结构生成", "定义结构的生成方式"),
		json("worldgen/structure_set", "结构集合", "指定结构的分布与间距"),
		json("worldgen/template_pool", "结构模板池", "定义拼图结构选取的模板集合"),
		json("worldgen/processor_list", "结构处理器", "定义结构生成时的方块替换规则"),
		json("worldgen/world_preset", "世界预设", "定义创建世界界面的世界预设"),
		json("worldgen/flat_level_generator_preset", "超平坦预设", "定义超平坦世界的层与生物群系"),
		json("worldgen/multi_noise_biome_source_parameter_list", "多噪声群系列表", "定义多噪声群系的气候参数"),
		tag("tags/block", "方块标签", "归类方块 ID"),
		tag("tags/item", "物品标签", "归类物品 ID"),
		tag("tags/entity_type", "实体类型标签", "归类实体类型 ID"),
		tag("tags/fluid", "流体标签", "归类流体 ID"),
		tag("tags/function", "函数标签", "归类函数 ID；load 和 tick 可作为特殊入口"),
		tag("tags/game_event", "游戏事件标签", "归类游戏事件 ID"),
		tag("tags/enchantment", "魔咒标签", "归类魔咒 ID"),
		tag("tags/damage_type", "伤害类型标签", "归类伤害类型 ID"),
		tag("tags/potion", "药水标签", "归类药水 ID"),
		tag("tags/dialog", "对话框标签", "归类对话框 ID"),
		tag("tags/timeline", "时间线标签", "归类时间线 ID"),
		tag("tags/villager_trade", "村民交易标签", "归类村民交易 ID"),
		tag("tags/instrument", "乐器标签", "归类乐器 ID"),
		tag("tags/banner_pattern", "旗帜图案标签", "归类旗帜图案 ID"),
		tag("tags/painting_variant", "画变种标签", "归类画变种 ID"),
		tag("tags/point_of_interest_type", "兴趣点标签", "归类村民工作站等兴趣点 ID"),
		folder("tags/worldgen", "世界生成标签", "按世界生成类型归类 ID"),
		tag("tags/worldgen/biome", "群系标签", "归类生物群系 ID"),
		tag("tags/worldgen/configured_feature", "地物标签", "归类已配置地物 ID"),
		tag("tags/worldgen/structure", "结构标签", "归类结构 ID"),
		tag("tags/worldgen/world_preset", "世界预设标签", "归类世界预设 ID"),
		tag("tags/worldgen/flat_level_generator_preset", "超平坦预设标签", "归类超平坦预设 ID")
	);

	private DatapackScaffoldCatalog() { }

	public static List<Category> jsonCategories() {
		return CATEGORIES.stream().filter(category -> category.extension().equals(".json")).toList();
	}

	public static List<Category> children(String parent) {
		return CATEGORIES.stream().filter(category -> parent(category.path()).equals(parent)).toList();
	}

	public static Category category(String path) {
		for (Category category : CATEGORIES) if (category.path().equals(path)) return category;
		return null;
	}

	public static Category fileCategory(String directory) {
		Category best = null;
		for (Category category : CATEGORIES) {
			if (category.extension().isEmpty()) continue;
			if (directory.equals(category.path()) || directory.startsWith(category.path() + "/")) {
				if (best == null || category.path().length() > best.path().length()) best = category;
			}
		}
		return best;
	}

	/** Best-effort type for formats not in the built-in list; nested files remain in one type. */
	public static String jsonType(String directory) {
		Category known = fileCategory(directory);
		if (known != null && known.extension().equals(".json")) return known.path();
		String[] parts = directory.split("/");
		if (parts.length == 0) return directory;
		if (parts[0].equals("tags") && parts.length >= 3 && parts[1].equals("worldgen"))
			return "tags/worldgen/" + parts[2];
		if ((parts[0].equals("tags") || parts[0].equals("worldgen")) && parts.length >= 2)
			return parts[0] + "/" + parts[1];
		return parts[0];
	}

	private static Category json(String path, String label, String purpose) {
		return new Category(path, label, purpose, ".json", EMPTY_JSON);
	}

	private static Category tag(String path, String label, String purpose) {
		return new Category(path, label, purpose, ".json", EMPTY_TAG);
	}

	private static Category folder(String path, String label, String purpose) {
		return new Category(path, label, purpose, "", "");
	}

	private static String parent(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}
}
