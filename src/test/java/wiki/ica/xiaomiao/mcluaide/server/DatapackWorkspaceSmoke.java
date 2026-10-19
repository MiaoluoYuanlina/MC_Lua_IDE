package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;

/** Exercises namespace creation, nested folders, safe renames and path checks. */
public final class DatapackWorkspaceSmoke {
	private DatapackWorkspaceSmoke() { }

	public static void main(String[] args) throws Exception {
		for (String category : new String[] {
				"advancement", "recipe", "loot_table", "predicate", "item_modifier", "dimension"
		}) {
			String example = DatapackExampleTemplates.curated(category);
			if (example == null || JsonParser.parseString(example).isJsonNull())
				throw new AssertionError(category + " 缺少有效 JSON 示例");
		}
		if (!DatapackExampleTemplates.curated("function").contains("say Hello"))
			throw new AssertionError("函数示例缺少命令");
		if (!DatapackExampleTemplates.categoryForFile("demo/data/demo/worldgen/biome/test.json")
				.path().equals("worldgen/biome"))
			throw new AssertionError("嵌套类型文件的模板识别失败");
		Path root = Files.createTempDirectory("mc-lua-datapack-smoke-").toAbsolutePath().normalize();
		try {
			Path pack = root.resolve("mc_lua_ide_edit_demo");
			Files.createDirectories(pack.resolve("data"));
			Files.writeString(pack.resolve("pack.mcmeta"), "{}");
			Path texture = pack.resolve("assets/example/textures/item/test.png");
			Path sound = pack.resolve("assets/example/sounds/test.ogg");
			Path structure = pack.resolve("data/example/structure/test.nbt");
			Files.createDirectories(texture.getParent());
			Files.createDirectories(sound.getParent());
			Files.createDirectories(structure.getParent());
			Files.write(texture, new byte[] {1, 2, 3});
			Files.write(sound, new byte[] {4, 5, 6});
			byte[] preview = new byte[200_000];
			for (int index = 0; index < preview.length; index++) preview[index] = (byte) index;
			Files.write(structure, preview);
			List<String> listed = DatapackWorkspaceManager.list(root);
			for (String path : List.of("demo/assets/example/textures/item/test.png",
					"demo/assets/example/sounds/test.ogg", "demo/data/example/structure/test.nbt")) {
				if (!listed.contains("F:" + path)) throw new AssertionError("二进制预览文件未显示：" + path);
			}
			String first = DatapackWorkspaceManager.previewChunk(root,
					"demo/data/example/structure/test.nbt", "0");
			String second = DatapackWorkspaceManager.previewChunk(root,
					"demo/data/example/structure/test.nbt", Integer.toString(192 * 1024));
			if (!first.startsWith("200000\n") || !second.startsWith("200000\n")
					|| Base64.getDecoder().decode(first.substring(first.indexOf('\n') + 1)).length != 192 * 1024
					|| Base64.getDecoder().decode(second.substring(second.indexOf('\n') + 1)).length != 3392)
				throw new AssertionError("预览分块读取错误");
			expectFailure(() -> DatapackWorkspaceManager.read(root,
					"demo/data/example/structure/test.nbt"));
			expectFailure(() -> DatapackWorkspaceManager.previewChunk(root,
					"demo/pack.mcmeta", "0"));
			expectFailure(() -> DatapackWorkspaceManager.previewChunk(root,
					"demo/data/example/structure/test.nbt", "-1"));
			DatapackWorkspaceManager.createDirectory(root, "demo/data/example/recipe");
			DatapackWorkspaceManager.createDirectory(root, "demo/data/example/function");
			String functionPath = "demo/data/example/function/greet.mcfunction";
			if (!DatapackWorkspaceManager.functionId(functionPath).toString().equals("example:greet")
					|| !DatapackWorkspaceManager.functionId(
						"demo/data/example/function/sub/greet.mcfunction").toString().equals("example:sub/greet"))
				throw new AssertionError("函数文件与命名空间 ID 的映射不正确");
			expectFailure(() -> DatapackWorkspaceManager.functionId(
					"demo/data/example/recipe/greet.mcfunction"));
			DatapackWorkspaceManager.createFile(root, functionPath, "say hello\n");
			if (!DatapackWorkspaceManager.read(root, functionPath).equals("say hello\n"))
				throw new AssertionError("mcfunction 读取失败");
			expectFailure(() -> DatapackWorkspaceManager.createFile(root, functionPath, "say overwritten\n"));
			DatapackWorkspaceManager.save(root, functionPath, "say changed\n");
			if (!DatapackWorkspaceManager.read(root, functionPath).equals("say changed\n"))
				throw new AssertionError("mcfunction 保存失败");
			DatapackWorkspaceManager.rename(root, functionPath,
					"demo/data/example/function/renamed.mcfunction");
			expectFailure(() -> DatapackWorkspaceManager.createFile(root,
					"demo/data/example/recipe/wrong.mcfunction", "say invalid\n"));
			if (DatapackScaffoldCatalog.children("").stream().noneMatch(value -> value.path().equals("function"))
					|| DatapackScaffoldCatalog.children("worldgen").stream()
							.noneMatch(value -> value.path().equals("worldgen/biome"))
					|| !DatapackScaffoldCatalog.fileCategory("tags/item/custom").template().contains("values"))
				throw new AssertionError("分类目录或文件模板缺失");
			Path recipe = pack.resolve("data/example/recipe/test.json");
			Files.writeString(recipe, "{}");
			DatapackWorkspaceManager.rename(root, "demo/data/example/recipe/test.json",
					"demo/data/example/recipe/renamed.json");
			if (!Files.isRegularFile(recipe.resolveSibling("renamed.json")) || Files.exists(recipe))
				throw new AssertionError("JSON 文件重命名失败");
			DatapackWorkspaceManager.rename(root, "demo/data/example", "demo/data/newspace");
			if (!Files.isRegularFile(pack.resolve("data/newspace/recipe/renamed.json")))
				throw new AssertionError("命名空间目录重命名失败");
			if (!Files.isRegularFile(pack.resolve("data/newspace/function/renamed.mcfunction")))
				throw new AssertionError("命名空间重命名后函数丢失");
			DatapackWorkspaceManager.rename(root, "demo", "renamed_pack");
			if (!Files.isRegularFile(root.resolve("mc_lua_ide_edit_renamed_pack/data/newspace/recipe/renamed.json")))
				throw new AssertionError("数据包根目录重命名失败");
			expectFailure(() -> DatapackWorkspaceManager.rename(root, "renamed_pack/data",
					"renamed_pack/other"));
			expectFailure(() -> DatapackWorkspaceManager.rename(root,
					"renamed_pack/data/newspace/recipe/renamed.json", "renamed_pack/data/newspace/moved.json"));
			expectFailure(() -> DatapackWorkspaceManager.createDirectory(root, "renamed_pack/data/../escape"));
			System.out.println("Datapack workspace smoke test passed");
		} finally {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
					if (error != null) throw error;
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static void expectFailure(CheckedAction action) throws Exception {
		try { action.run(); }
		catch (IOException expected) { return; }
		throw new AssertionError("危险或无效的目录操作未被拒绝");
	}

	@FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
