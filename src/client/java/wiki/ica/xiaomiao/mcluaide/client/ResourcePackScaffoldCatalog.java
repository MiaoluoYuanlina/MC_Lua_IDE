package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Current client asset layout plus readable templates for new resource files. */
final class ResourcePackScaffoldCatalog {
	record Category(String path, String label, String purpose, String extension, String example) { }

	private static final List<Category> CATEGORIES = List.of(
			new Category("items", "物品定义", "将物品 ID 映射到模型或条件分支", ".json",
					"{\n  \"model\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/diamond\"}\n}\n"),
			new Category("models/item", "物品模型", "定义平面物品纹理、父模型与显示变换", ".json",
					"{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\"layer0\": \"minecraft:item/diamond\"}\n}\n"),
			new Category("models/block", "方块模型", "定义方块形状、材质与各面 UV", ".json",
					"{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": {\"all\": \"minecraft:block/stone\"}\n}\n"),
			new Category("blockstates", "方块状态", "按方块状态选择模型或组合多个模型", ".json",
					"{\n  \"variants\": {\"\": {\"model\": \"minecraft:block/stone\"}}\n}\n"),
			new Category("textures/item", "物品纹理", "物品模型引用的透明 PNG，可在画布中编辑", ".png", ""),
			new Category("textures/block", "方块纹理", "方块模型各面使用的 PNG", ".png", ""),
			new Category("textures/entity", "实体纹理", "实体模型使用的 PNG；尺寸和 UV 必须匹配实体", ".png", ""),
			new Category("textures/gui/sprites", "界面精灵", "界面控件和图标使用的 PNG", ".png", ""),
			new Category("atlases", "纹理图集", "定义从哪些资源路径收集纹理精灵", ".json",
					"{\n  \"sources\": [{\"type\": \"minecraft:directory\", \"source\": \"item\", \"prefix\": \"item/\"}]\n}\n"),
			new Category("font", "字体", "字体提供器列表，常用于自定义字符或图片字形", ".json",
					"{\n  \"providers\": []\n}\n"),
			new Category("particles", "粒子", "指定粒子动画使用的纹理列表", ".json",
					"{\n  \"textures\": [\"minecraft:particle/generic_0\"]\n}\n"),
			new Category("equipment", "装备外观", "定义盔甲和鞘翅等装备的图层纹理", ".json",
					"{\n  \"layers\": {}\n}\n"),
			new Category("lang", "语言翻译", "翻译键到显示文本的映射，文件名如 zh_cn.json", ".json",
					"{\n  \"item.example.example\": \"示例物品\"\n}\n"),
			new Category("sounds", "声音文件", "OGG 音频；具体声音事件由命名空间根部 sounds.json 引用", ".ogg", ""),
			new Category("post_effect", "后处理效果", "画面后处理图形配置；字段取决于当前版本着色器", ".json", "{}\n"),
			new Category("waypoint_style", "路径点样式", "路径点的视觉样式与图标", ".json", "{}\n")
	);

	private ResourcePackScaffoldCatalog() { }

	static List<Category> categories() { return CATEGORIES; }

	static Category categoryFor(String path) {
		if (path == null) return null;
		String[] parts = path.split("/", 5);
		if (parts.length < 4 || !parts[1].equals("assets")) return null;
		String relative = path.substring(parts[0].length() + parts[1].length() + parts[2].length() + 3);
		Category best = null;
		for (Category category : CATEGORIES)
			if ((relative.equals(category.path()) || relative.startsWith(category.path() + "/"))
					&& (best == null || category.path().length() > best.path().length())) best = category;
		return best;
	}

	static String purpose(String path) {
		if (path == null) return "";
		if (!path.contains("/")) return "资源包根目录：pack.mcmeta 描述包版本与名称";
		if (path.endsWith("/assets")) return "按命名空间存放所有客户端资源";
		if (path.matches("[^/]+/assets/[^/]+")) return "命名空间，可覆盖同 ID 的原版或模组资源";
		Category category = categoryFor(path);
		return category == null ? "自定义或模组资源目录" : category.purpose();
	}

	static String template(String path) {
		if (path.endsWith("/sounds.json")) return "{\n  \"example\": {\"sounds\": [\"example\"]}\n}\n";
		if (path.endsWith(".png.mcmeta")) return "{\n  \"animation\": {\"frametime\": 2}\n}\n";
		Category category = categoryFor(path);
		return category != null && !category.example().isEmpty() ? category.example() : "{}\n";
	}

	/** Reads examples from the currently loaded vanilla/mod/resource-pack assets, not a hard-coded ID list. */
	static List<String> loadedExamples(Minecraft minecraft, String category) {
		List<String> values = new ArrayList<>();
		for (Identifier id : minecraft.getResourceManager().listResources(category,
				value -> value.getPath().endsWith(".json")).keySet()) {
			values.add(id.toString());
			if (values.size() >= 2000) break;
		}
		for (var file : LuaResourceAssetCatalog.workspaceFiles()) {
			if (file.directory()) continue;
			Identifier id = wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.path(), "assets");
			if (id != null && id.getPath().startsWith(category + "/") && id.getPath().endsWith(".json")
					&& !values.contains(id.toString()) && values.size() < 2000) values.add(id.toString());
		}
		values.sort(String::compareToIgnoreCase);
		return values;
	}

	static String readLoadedExample(Minecraft minecraft, String resourceId) throws IOException {
		Identifier id = Identifier.tryParse(resourceId);
		if (id == null) throw new IOException("资源 ID 无效");
		var resource = minecraft.getResourceManager().getResource(id).orElse(null);
		if (resource == null) {
			for (var file : LuaResourceAssetCatalog.workspaceFiles()) {
				if (!file.directory() && id.equals(wiki.ica.xiaomiao.mcluaide.editor.PackResourceId.workspaceFile(file.path(), "assets")))
					return LuaResourcePackWorkspace.readText(file.path());
			}
			throw new IOException("游戏和编辑目录中都没有该资源");
		}
		try (var reader = resource.openAsReader()) {
			StringBuilder result = new StringBuilder();
			char[] buffer = new char[4096];
			int count;
			while ((count = reader.read(buffer)) != -1) {
				result.append(buffer, 0, count);
				if (result.length() > 128 * 1024) throw new IOException("示例 JSON 超过 128 KiB");
			}
			return result.isEmpty() ? "{}\n" : result.toString();
		}
	}
}
