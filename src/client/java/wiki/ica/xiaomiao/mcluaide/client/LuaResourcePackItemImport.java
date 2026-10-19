package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Imports one flat item texture and its modern item definition without replacing existing assets. */
final class LuaResourcePackItemImport {
	record Result(String id, String texturePath, String modelPath, String definitionPath) { }
	private static final int MAX_BYTES = 16 * 1024 * 1024;
	private LuaResourcePackItemImport() { }

	static Result paths(String namespace, String requestedId) throws IOException {
		String input = requestedId == null ? "" : requestedId.strip();
		String id = input.indexOf(':') < 0 ? namespace + ":" + input : input;
		if (!namespace.matches("[a-z0-9_.-]{1,64}") || namespace.equals(".") || namespace.equals("..")
				|| !id.startsWith(namespace + ":")) throw new IOException("物品模型 ID 必须属于当前命名空间：" + namespace);
		String name = id.substring(namespace.length() + 1);
		if (name.isEmpty() || name.length() > 180) throw new IOException("请输入物品模型 ID，例如 " + namespace + ":my_item");
		for (String segment : name.split("/", -1))
			if (!segment.matches("[a-z0-9_.-]{1,64}") || segment.equals(".") || segment.equals(".."))
				throw new IOException("ID 只能用小写字母、数字、下划线、点、连字符和 /，不能包含空路径或 ..");
		return new Result(id, "textures/item/" + name + ".png", "models/item/" + name + ".json", "items/" + name + ".json");
	}

	static synchronized Result importItem(Path namespaceDirectory, Path source, String requestedId) throws IOException {
		Path root = namespaceDirectory.toAbsolutePath().normalize();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("请选择已存在的资源包命名空间目录");
		Result result = paths(root.getFileName().toString(), requestedId);
		byte[] png = readPng(source);
		String reference = result.id().replace(":", ":item/");
		JsonObject textures = new JsonObject(); textures.addProperty("layer0", reference);
		JsonObject model = new JsonObject(); model.addProperty("parent", "minecraft:item/generated"); model.add("textures", textures);
		JsonObject binding = new JsonObject(); binding.addProperty("type", "minecraft:model"); binding.addProperty("model", reference);
		JsonObject item = new JsonObject(); item.add("model", binding);
		var gson = new GsonBuilder().setPrettyPrinting().create();
		List<Path> destinations = List.of(root.resolve(result.texturePath()), root.resolve(result.modelPath()), root.resolve(result.definitionPath()));
		List<byte[]> contents = List.of(png, (gson.toJson(model) + "\n").getBytes(StandardCharsets.UTF_8),
				(gson.toJson(item) + "\n").getBytes(StandardCharsets.UTF_8));
		for (Path target : destinations) {
			checkDestination(root, target);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("文件已存在，未覆盖：" + root.relativize(target));
		}
		List<Path> created = new ArrayList<>();
		try {
			for (int i = 0; i < destinations.size(); i++) {
				Path target = destinations.get(i);
				Files.createDirectories(target.getParent()); checkDestination(root, target);
				// Record ownership immediately after CREATE_NEW so partial writes can also be rolled back.
				try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
					created.add(target); output.write(contents.get(i));
				}
			}
		} catch (IOException | RuntimeException error) {
			for (Path target : created.reversed()) try { Files.deleteIfExists(target); }
			catch (IOException cleanup) { error.addSuppressed(cleanup); }
			throw error;
		}
		return result;
	}

	private static byte[] readPng(Path source) throws IOException {
		if (source == null || source.getFileName() == null || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
				|| !Files.isRegularFile(source)) throw new IOException("请选择 PNG 图片文件");
		if (Files.size(source) > MAX_BYTES) throw new IOException("PNG 文件超过 16 MiB");
		byte[] bytes;
		try (var stream = Files.newInputStream(source)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
		if (bytes.length > MAX_BYTES) throw new IOException("PNG 文件超过 16 MiB");
		try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) throw new IOException("无法读取 PNG 图片");
			var reader = readers.next();
			try {
				if (!reader.getFormatName().equalsIgnoreCase("png")) throw new IOException("文件不是真正的 PNG 格式");
				reader.setInput(input);
				int width = reader.getWidth(0), height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > 2048 || height > 2048 || (long) width * height > 1_048_576)
					throw new IOException("PNG 尺寸不支持（最多 2048 边长、1048576 像素）");
				if (reader.read(0) == null) throw new IOException("PNG 图片数据损坏");
			} finally { reader.dispose(); }
		} catch (RuntimeException error) { throw new IOException("无法读取 PNG 图片", error); }
		return bytes;
	}

	private static void checkDestination(Path root, Path target) throws IOException {
		if (!target.normalize().startsWith(root)) throw new IOException("物品材质路径超出命名空间");
		for (Path cursor = target; cursor != null; cursor = cursor.getParent())
			if (Files.isSymbolicLink(cursor)) throw new IOException("物品材质目标路径包含符号链接");
	}
}
