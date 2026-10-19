package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-local resource packs created by this IDE. Never edits unrelated packs. */
final class LuaResourcePackWorkspace {
	private static final String PREFIX = "mc_lua_ide_edit_";
	private static final int MAX_ENTRIES = 4096;
	private static final long MAX_PNG_BYTES = 16L * 1024 * 1024;
	private static final long MAX_TEXT_BYTES = LuaProtocol.MAX_SCRIPT_CHARS * 4L;
	private static final Path ROOT = FabricLoader.getInstance().getGameDir()
			.resolve("resourcepacks").toAbsolutePath().normalize();

	record Entry(String path, boolean directory, int depth) { }

	private LuaResourcePackWorkspace() { }

	static Path root() { return ROOT; }

	static List<Entry> list() throws IOException {
		List<Entry> entries = new ArrayList<>();
		if (!Files.isDirectory(ROOT, LinkOption.NOFOLLOW_LINKS)) return entries;
		try (var stream = Files.list(ROOT)) {
			for (Path pack : stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> path.getFileName().toString().startsWith(PREFIX)).toList()) {
				String name = pack.getFileName().toString().substring(PREFIX.length());
				if (!validSegment(name)) continue;
				entries.add(new Entry(name, true, 0));
				try (var files = Files.walk(pack)) {
					for (Path file : files.filter(path -> !path.equals(pack)).toList()) {
						if (Files.isSymbolicLink(file)) continue;
						String relative = pack.relativize(file).toString().replace('\\', '/');
						boolean directory = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
						if (!directory && !supportedFile(relative)) continue;
						String path = name + "/" + relative;
						entries.add(new Entry(path, directory, path.split("/").length - 1));
						if (entries.size() > MAX_ENTRIES) throw new IOException("资源包目录超过 " + MAX_ENTRIES + " 项");
					}
				}
			}
		}
		entries.sort(Comparator.comparing(Entry::path, String.CASE_INSENSITIVE_ORDER));
		return entries;
	}

	static void createPack(String name) throws IOException {
		if (!validSegment(name)) throw new IOException("资源包名称只能用小写字母、数字、下划线、点或连字符");
		Path pack = pack(name);
		checkLinks(pack);
		if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("资源包已存在");
		Files.createDirectories(ROOT);
		Files.createDirectory(pack);
		var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
		String version = format.minor() == 0 ? Integer.toString(format.major())
				: "[" + format.major() + "," + format.minor() + "]";
		Files.writeString(pack.resolve("pack.mcmeta"),
				"{\n  \"pack\": {\n    \"description\": \"MC Lua IDE 资源包：" + name
						+ "\",\n    \"min_format\": " + version + ",\n    \"max_format\": " + version
						+ "\n  }\n}\n", StandardCharsets.UTF_8);
		Path items = pack.resolve("assets").resolve(name).resolve("items");
		Path models = pack.resolve("assets").resolve(name).resolve("models/item");
		Path textures = pack.resolve("assets").resolve(name).resolve("textures/item");
		Files.createDirectories(items);
		Files.createDirectories(models);
		Files.createDirectories(textures);
		Files.writeString(items.resolve("example.json"),
				"{\n  \"model\": {\"type\": \"minecraft:model\", \"model\": \"" + name
						+ ":item/example\"}\n}\n", StandardCharsets.UTF_8);
		Files.writeString(models.resolve("example.json"),
				"{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\"layer0\": \""
						+ name + ":item/example\"}\n}\n", StandardCharsets.UTF_8);
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
			image.setRGB(x, y, x == y || x + y == 15 ? 0xFFEAC458 : 0xFF328EAD);
		if (!ImageIO.write(image, "PNG", textures.resolve("example.png").toFile()))
			throw new IOException("PNG 编码不可用");
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static void createDirectory(String path) throws IOException {
		Path target = resolve(path);
		if (!Files.isDirectory(pack(packName(path)), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("请先创建资源包模板");
		if (path.indexOf('/') < 0) throw new IOException("请选择资源包内的路径");
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目录已存在");
		Files.createDirectories(target);
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static void createText(String path, String source) throws IOException {
		if (!editableText(path)) throw new IOException("仅支持 JSON、.mcmeta 和 .lang 文本文件");
		validateText(source);
		Path target = resolve(path);
		if (!Files.isDirectory(pack(packName(path)), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("请先创建资源包模板");
		Files.createDirectories(target.getParent());
		checkLinks(target);
		Files.writeString(target, source, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static void createPng(String path, int width, int height) throws IOException {
		if (path == null || !path.endsWith(".png")) throw new IOException("请输入 .png 路径");
		if (width < 1 || height < 1 || width > 2048 || height > 2048 || (long) width * height > 1_048_576)
			throw new IOException("图片尺寸超过限制");
		Path target = resolve(path);
		if (!Files.isDirectory(pack(packName(path)), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("请先创建资源包模板");
		Files.createDirectories(target.getParent());
		checkLinks(target);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("PNG 已存在");
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		if (!ImageIO.write(image, "PNG", target.toFile())) throw new IOException("PNG 编码不可用");
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static String readText(String path) throws IOException {
		if (!editableText(path)) throw new IOException("此文件不是可编辑文本");
		Path target = resolve(path);
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("文件不存在");
		if (Files.size(target) > MAX_TEXT_BYTES) throw new IOException("文本文件超过 " + MAX_TEXT_BYTES + " 字节限制");
		String source = Files.readString(target, StandardCharsets.UTF_8);
		if (source.length() > LuaProtocol.MAX_SCRIPT_CHARS)
			throw new IOException("文本超过 " + LuaProtocol.MAX_SCRIPT_CHARS + " 个字符");
		return source;
	}

	static void saveText(String path, String source) throws IOException {
		if (!editableText(path)) throw new IOException("此文件不是可编辑文本");
		validateText(source);
		Path target = resolve(path);
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("文件不存在");
		writeReplace(target, source.getBytes(StandardCharsets.UTF_8));
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static BufferedImage readPng(String path) throws IOException {
		if (!path.endsWith(".png")) throw new IOException("只能编辑 PNG 文件");
		Path target = resolve(path);
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("PNG 不存在");
		if (Files.size(target) > MAX_PNG_BYTES) throw new IOException("PNG 超过 16 MiB 限制");
		BufferedImage input = ImageIO.read(target.toFile());
		if (input == null || input.getWidth() < 1 || input.getHeight() < 1
				|| input.getWidth() > 2048 || input.getHeight() > 2048
				|| (long) input.getWidth() * input.getHeight() > 1_048_576)
			throw new IOException("PNG 尺寸不支持（最多 2048 边长、1048576 像素）");
		BufferedImage copy = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
		var graphics = copy.createGraphics();
		try { graphics.drawImage(input, 0, 0, null); }
		finally { graphics.dispose(); }
		return copy;
	}

	static byte[] readOgg(String path) throws IOException {
		if (!path.endsWith(".ogg")) throw new IOException("只能预览 OGG 文件");
		Path target = resolve(path);
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("OGG 文件不存在");
		if (Files.size(target) > 64L * 1024 * 1024) throw new IOException("OGG 预览文件不能超过 64 MiB");
		return Files.readAllBytes(target);
	}

	static void savePng(String path, BufferedImage image) throws IOException {
		if (!path.endsWith(".png")) throw new IOException("只能保存 PNG 文件");
		Path target = resolve(path);
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("PNG 不存在");
		Path temp = Files.createTempFile(target.getParent(), ".mc_lua_ide_png_", ".tmp");
		try {
			if (!ImageIO.write(image, "PNG", temp.toFile())) throw new IOException("PNG 编码不可用");
			if (Files.size(temp) > MAX_PNG_BYTES) throw new IOException("PNG 超过 16 MiB 限制");
			checkLinks(target);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} finally { Files.deleteIfExists(temp); }
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static void rename(String oldPath, String newPath) throws IOException {
		Path source = resolve(oldPath), target = resolve(newPath);
		if (oldPath.equals(newPath)) return;
		if (oldPath.endsWith("/pack.mcmeta") || oldPath.endsWith("/assets"))
			throw new IOException("不能重命名 pack.mcmeta 或 assets 根目录");
		if (oldPath.indexOf('/') >= 0 && !parent(oldPath).equals(parent(newPath)))
			throw new IOException("只能在当前目录重命名，不能移动文件");
		if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				&& !extension(oldPath).equals(extension(newPath))) throw new IOException("文件后缀不能修改");
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("原路径不存在");
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标已存在");
		Files.move(source, target);
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static void delete(String path) throws IOException {
		Path target = resolve(path);
		if (path.endsWith("/pack.mcmeta") || path.endsWith("/assets"))
			throw new IOException("不能单独删除 pack.mcmeta 或 assets 根目录");
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
		try (var paths = Files.walk(target)) {
			if (paths.anyMatch(Files::isSymbolicLink)) throw new IOException("资源包内含符号链接，已取消删除");
		}
		Files.walkFileTree(target, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (Files.isSymbolicLink(file)) throw new IOException("资源包内含符号链接");
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
				if (error != null) throw error;
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
		ScriptManager.invalidateAllRegistryCatalogs();
	}

	static Path diskPath(String path) throws IOException { return resolve(path); }

	static LuaResourcePackSoundImport.Result importSound(String namespacePath, Path source, String id) throws IOException {
		if (namespacePath == null || !namespacePath.matches("[^/]+/assets/[a-z0-9_.-]+"))
			throw new IOException("请选择 assets 下的命名空间目录");
		Path directory = resolve(namespacePath);
		String input = id == null ? "" : id.strip();
		String name = input.substring(input.indexOf(':') + 1);
		resolve(namespacePath + "/sounds/" + name + ".ogg");
		var imported = LuaResourcePackSoundImport.importSound(directory, source, id);
		ScriptManager.invalidateAllRegistryCatalogs();
		return imported;
	}

	static LuaResourcePackItemImport.Result importItemTexture(String namespacePath, Path source, String id) throws IOException {
		if (namespacePath == null || !namespacePath.matches("[^/]+/assets/[a-z0-9_.-]+"))
			throw new IOException("请选择 assets 下的命名空间目录");
		var paths = LuaResourcePackItemImport.paths(namespacePath.substring(namespacePath.lastIndexOf('/') + 1), id);
		resolve(namespacePath + "/" + paths.texturePath());
		resolve(namespacePath + "/" + paths.modelPath());
		resolve(namespacePath + "/" + paths.definitionPath());
		var result = LuaResourcePackItemImport.importItem(resolve(namespacePath), source, id);
		ScriptManager.invalidateAllRegistryCatalogs();
		return result;
	}

	static boolean editableText(String path) {
		return path != null && (path.endsWith(".json") || path.endsWith(".mcmeta"));
	}

	private static boolean supportedFile(String relative) {
		return editableText(relative) || relative.endsWith(".png") || relative.endsWith(".ogg");
	}

	private static void validateText(String source) throws IOException {
		if (source == null || source.length() > LuaProtocol.MAX_SCRIPT_CHARS)
			throw new IOException("文本不能超过 " + LuaProtocol.MAX_SCRIPT_CHARS + " 个字符");
		try { JsonParser.parseString(source); }
		catch (RuntimeException error) { throw new IOException("JSON 语法错误：" + error.getMessage(), error); }
	}

	private static void writeReplace(Path target, byte[] bytes) throws IOException {
		Path temp = Files.createTempFile(target.getParent(), ".mc_lua_ide_json_", ".tmp");
		try {
			Files.write(temp, bytes);
			checkLinks(target);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} finally { Files.deleteIfExists(temp); }
	}

	private static Path resolve(String path) throws IOException {
		if (path == null || path.isBlank() || path.length() > 240 || path.indexOf('\\') >= 0)
			throw new IOException("资源包路径无效");
		String[] segments = path.split("/", -1);
		for (String segment : segments) if (!validSegment(segment)) throw new IOException("路径含无效字符：" + path);
		Path pack = pack(segments[0]);
		Path result = pack;
		for (int index = 1; index < segments.length; index++) result = result.resolve(segments[index]);
		result = result.normalize();
		if (!result.startsWith(pack)) throw new IOException("路径超出资源包");
		checkLinks(result);
		return result;
	}

	private static Path pack(String name) { return ROOT.resolve(PREFIX + name).normalize(); }
	private static String packName(String path) { return path.split("/", 2)[0]; }
	private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
	private static String extension(String path) { int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(dot); }
	private static boolean validSegment(String value) {
		return value != null && value.matches("[a-z0-9_.-]{1,64}") && !value.equals(".") && !value.equals("..");
	}

	private static void checkLinks(Path path) throws IOException {
		if (Files.isSymbolicLink(ROOT)) throw new IOException("资源包目录不能是符号链接");
		for (Path cursor = path; cursor != null && cursor.startsWith(ROOT); cursor = cursor.getParent())
			if (Files.isSymbolicLink(cursor)) throw new IOException("资源包路径包含符号链接");
	}
}
