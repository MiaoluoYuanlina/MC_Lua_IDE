package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.Identifier;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Files owned by the IDE's data-pack editor; never exposes unrelated world packs. */
final class DatapackWorkspaceManager {
	private static final String PREFIX = "mc_lua_ide_edit_";
	private static final int MAX_ENTRIES = 2048;
	private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
	private static final int PREVIEW_CHUNK_BYTES = 192 * 1024;
	private static final Gson STRICT_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

	private DatapackWorkspaceManager() { }

	static List<String> list(MinecraftServer server) throws IOException {
		return list(root(server));
	}

	static List<String> list(Path root) throws IOException {
		List<String> entries = new ArrayList<>();
		if (!Files.isDirectory(root)) return entries;
		try (var packs = Files.list(root)) {
			for (Path pack : packs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> path.getFileName().toString().startsWith(PREFIX)).toList()) {
				String name = pack.getFileName().toString().substring(PREFIX.length());
				if (!validPart(name)) continue;
				entries.add("D:" + name);
				try (var paths = Files.walk(pack)) {
					for (Path path : paths.filter(value -> !value.equals(pack)).toList()) {
						if (Files.isSymbolicLink(path)) continue;
						String relative = pack.relativize(path).toString().replace('\\', '/');
						if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) entries.add("D:" + name + "/" + relative);
						else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
								&& (editableFileName(name + "/" + relative)
									|| previewFileName(name + "/" + relative)))
							entries.add("F:" + name + "/" + relative);
						if (entries.size() > MAX_ENTRIES) throw new IOException("数据包目录超过 " + MAX_ENTRIES + " 项");
					}
				}
			}
		}
		return entries;
	}

	static void createPack(MinecraftServer server, String name) throws IOException {
		if (!validPart(name)) throw new IOException("数据包名称仅可使用小写字母、数字、_、- 和 .");
		Path pack = packRoot(server, name);
		if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("数据包已存在：" + name);
		Files.createDirectories(root(server));
		Files.createDirectory(pack);
		LuaManagedPackApi.writeMetadata(pack, PackType.SERVER_DATA);
		Path example = pack.resolve("data").resolve(name).resolve("tags/item/example.json");
		Files.createDirectories(example.getParent());
		Files.writeString(example, "{\n  \"replace\": false,\n  \"values\": [\"minecraft:diamond\"]\n}\n",
				StandardCharsets.UTF_8);
	}

	static String read(MinecraftServer server, String name) throws IOException {
		return read(root(server), name);
	}

	static String read(Path datapackRoot, String name) throws IOException {
		Path path = file(datapackRoot, name);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("数据包文件不存在：" + name);
		if (Files.size(path) > LuaProtocol.MAX_SCRIPT_CHARS * 4L) throw new IOException("数据包文件超过读取限制");
		String source = Files.readString(path, StandardCharsets.UTF_8);
		if (source.length() > LuaProtocol.MAX_SCRIPT_CHARS) throw new IOException("数据包文件超过编辑器长度限制");
		return source;
	}

	/** A bounded, read-only chunk. The path is resolved inside an IDE-owned pack. */
	static String previewChunk(MinecraftServer server, String name, String offset) throws IOException {
		return previewChunk(root(server), name, offset);
	}

	static String previewChunk(Path datapackRoot, String name, String offsetText) throws IOException {
		if (!previewFileName(name)) throw new IOException("仅支持预览 PNG、OGG 与 NBT 文件");
		Path path = resolve(datapackRoot, name);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("预览文件不存在：" + name);
		long size = Files.size(path);
		if (size > MAX_PREVIEW_BYTES) throw new IOException("预览文件不能超过 8 MiB");
		long offset;
		try { offset = Long.parseLong(offsetText); }
		catch (NumberFormatException error) { throw new IOException("预览偏移量无效", error); }
		if (offset < 0 || offset > size) throw new IOException("预览偏移量超出文件范围");
		ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(PREVIEW_CHUNK_BYTES, size - offset));
		try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			channel.position(offset);
			while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
		}
		return size + "\n" + Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer.array(), buffer.position()));
	}

	static void save(MinecraftServer server, String name, String source) throws IOException {
		save(root(server), name, source);
	}

	static void save(Path datapackRoot, String name, String source) throws IOException {
		validateSource(name, source);
		Path path = file(datapackRoot, name);
		Path pack = packRoot(datapackRoot, name.substring(0, name.indexOf('/')));
		if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("请先创建数据包模板");
		assertNoLinks(pack, path);
		Files.createDirectories(path.getParent());
		assertNoLinks(pack, path);
		Files.writeString(path, source, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
	}

	static void createFile(MinecraftServer server, String name, String source) throws IOException {
		createFile(root(server), name, source);
	}

	static void createFile(Path datapackRoot, String name, String source) throws IOException {
		validateSource(name, source);
		Path path = file(datapackRoot, name);
		Path pack = packRoot(datapackRoot, name.substring(0, name.indexOf('/')));
		if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("请先创建数据包模板");
		assertNoLinks(pack, path);
		Files.createDirectories(path.getParent());
		assertNoLinks(pack, path);
		Files.writeString(path, source, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
	}

	private static void validateSource(String name, String source) throws IOException {
		if (name == null) throw new IOException("数据包文件路径不能为空");
		if (source == null || source.length() > LuaProtocol.MAX_SCRIPT_CHARS)
			throw new IOException("数据包文件超过编辑器长度限制");
		if (!name.endsWith(".mcfunction")) {
			try {
				if (STRICT_JSON.fromJson(source, JsonElement.class) == null)
					throw new IllegalArgumentException("JSON 文件不能为空");
			} catch (RuntimeException error) { throw new IOException("JSON 语法错误：" + error.getMessage(), error); }
		}
	}

	static boolean delete(MinecraftServer server, String name) throws IOException {
		if (name.endsWith("/pack.mcmeta")) throw new IOException("不能单独删除 pack.mcmeta；请删除整个数据包");
		if (isDataRoot(name)) throw new IOException("不能单独删除 data 根目录；请删除整个数据包");
		Path path = resolve(server, name);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
		Path pack = packRoot(server, name.split("/", 2)[0]);
		assertNoLinks(pack, path);
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
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
		return true;
	}

	static void createDirectory(MinecraftServer server, String name) throws IOException {
		createDirectory(root(server), name);
	}

	static void createDirectory(Path datapackRoot, String name) throws IOException {
		Path directory = resolve(datapackRoot, name);
		String[] parts = name.split("/");
		if (parts.length < 2 || parts[parts.length - 1].endsWith(".json")
				|| parts[parts.length - 1].endsWith(".mcfunction")
				|| parts[parts.length - 1].endsWith(".mcmeta"))
			throw new IOException("请在数据包内指定一个文件夹名称");
		Path pack = packRoot(datapackRoot, parts[0]);
		if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("数据包不存在：" + parts[0]);
		Path parent = directory.getParent();
		assertNoLinks(pack, parent);
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("上级目录不存在");
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目录已存在：" + name);
		Files.createDirectory(directory);
	}

	static void rename(MinecraftServer server, String oldName, String newName) throws IOException {
		rename(root(server), oldName, newName);
	}

	static void rename(Path datapackRoot, String oldName, String newName) throws IOException {
		Path source = resolve(datapackRoot, oldName);
		Path target = resolve(datapackRoot, newName);
		if (oldName.equals(newName)) throw new IOException("新旧名称相同");
		if (oldName.endsWith("/pack.mcmeta") || isDataRoot(oldName))
			throw new IOException("数据包必需的 pack.mcmeta 和 data 根目录不能重命名");
		String oldParent = parentName(oldName);
		if (!oldParent.equals(parentName(newName))) throw new IOException("重命名只能修改当前目录下的名称，不能移动文件");
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("原路径不存在：" + oldName);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标名称已存在：" + newName);
		if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
			String suffix = oldName.substring(oldName.lastIndexOf('.'));
			if (!newName.endsWith(suffix) || !(editableFileName(oldName) || previewFileName(oldName)))
				throw new IOException("重命名后仍须保持原文件后缀 " + suffix);
		} else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
			if (newName.endsWith(".json") || newName.endsWith(".mcfunction")
					|| newName.endsWith(".mcmeta"))
				throw new IOException("文件夹名称不能使用 JSON 文件后缀");
		} else throw new IOException("只能重命名普通文件或目录");
		Path pack = packRoot(datapackRoot, oldName.split("/", 2)[0]);
		assertNoLinks(pack, source);
		assertNoLinks(packRoot(datapackRoot, newName.split("/", 2)[0]), target);
		Files.move(source, target);
	}

	private static boolean isDataRoot(String path) {
		return path != null && path.matches("[^/]+/data");
	}

	private static String parentName(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	private static Path file(Path datapackRoot, String name) throws IOException {
		if (!editableFileName(name))
			throw new IOException("仅能编辑 JSON、pack.mcmeta 或 function 目录内的 .mcfunction 文件");
		return resolve(datapackRoot, name);
	}

	private static boolean editableFileName(String name) {
		if (name == null) return false;
		if (name.endsWith(".json") || name.endsWith("/pack.mcmeta")) return true;
		String[] parts = name.split("/", -1);
		return parts.length >= 5 && parts[1].equals("data") && parts[3].equals("function")
				&& parts[parts.length - 1].endsWith(".mcfunction");
	}

	private static boolean previewFileName(String name) {
		return name != null && (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".nbt"));
	}

	/** Maps an IDE-owned function file to its native Minecraft function ID. */
	static Identifier functionId(String name) throws IOException {
		if (!editableFileName(name) || !name.endsWith(".mcfunction"))
			throw new IOException("只能运行数据包 function 目录中的 .mcfunction 文件");
		String[] parts = name.split("/", -1);
		String path = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
		path = path.substring(0, path.length() - ".mcfunction".length());
		Identifier id = Identifier.tryBuild(parts[2], path);
		if (id == null) throw new IOException("函数命名空间或 ID 无效：" + name);
		return id;
	}

	private static Path resolve(MinecraftServer server, String name) throws IOException {
		return resolve(root(server), name);
	}

	private static Path resolve(Path datapackRoot, String name) throws IOException {
		if (GitWorkspaceService.restoring()) throw new IOException("Git 正在恢复工作区，请稍后读取或保存数据包");
		if (name == null || name.isBlank() || name.length() > 240 || name.contains("\\")) throw new IOException("无效数据包路径");
		String[] parts = name.split("/", -1);
		for (String part : parts) if (!validPart(part)) throw new IOException("无效数据包路径：" + name);
		Path pack = packRoot(datapackRoot, parts[0]);
		Path result = pack;
		for (int i = 1; i < parts.length; i++) result = result.resolve(parts[i]);
		result = result.normalize();
		if (!result.startsWith(pack)) throw new IOException("数据包路径超出工作区");
		assertNoLinks(pack, result);
		return result;
	}

	private static Path packRoot(MinecraftServer server, String name) {
		return packRoot(root(server), name);
	}

	private static Path packRoot(Path datapackRoot, String name) {
		return datapackRoot.resolve(PREFIX + name).toAbsolutePath().normalize();
	}

	private static Path root(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
	}

	private static boolean validPart(String part) {
		return part != null && !part.isEmpty() && part.length() <= 64 && !part.equals(".") && !part.equals("..")
				&& part.matches("[a-z0-9_.-]+");
	}

	private static void assertNoLinks(Path pack, Path path) throws IOException {
		for (Path current = path; current != null && current.startsWith(pack); current = current.getParent())
			if (Files.isSymbolicLink(current)) throw new IOException("数据包工作区不允许符号链接");
	}
}
