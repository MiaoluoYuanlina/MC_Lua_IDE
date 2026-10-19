package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.sounds.JOrbisAudioStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Validates an OGG, copies it without overwriting, and merges the namespace's sound events. */
final class LuaResourcePackSoundImport {
	private static final long MAX_OGG_BYTES = 64L * 1024 * 1024;
	private static final int MAX_JSON_BYTES = 1024 * 1024;
	record Result(String id, String audioPath) { }
	private LuaResourcePackSoundImport() { }

	static synchronized Result importSound(Path namespaceDirectory, Path source, String requestedId) throws IOException {
		Path root = namespaceDirectory.toAbsolutePath().normalize();
		String namespace = root.getFileName().toString();
		if (!namespace.matches("[a-z0-9_.-]+") || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("请选择已存在的资源包命名空间目录");
		String input = requestedId == null ? "" : requestedId.strip();
		String id = input.indexOf(':') < 0 ? namespace + ":" + input : input;
		if (!id.startsWith(namespace + ":")) throw new IOException("音效 ID 必须属于当前命名空间：" + namespace);
		String name = id.substring(namespace.length() + 1);
		if (name.isEmpty() || name.length() > 180 || name.indexOf(':') >= 0)
			throw new IOException("音效 ID 无效，例如 " + namespace + ":music/demo");
		for (String segment : name.split("/", -1)) {
			if (!segment.matches("[a-z0-9_.-]{1,64}") || segment.equals(".") || segment.equals(".."))
				throw new IOException("音效 ID 只能用小写字母、数字、下划线、点、连字符和 /，不能包含空路径或 ..");
		}
		if (source == null || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg")
				|| !Files.isRegularFile(source)) throw new IOException("请选择 .ogg 音频文件");
		if (Files.size(source) > MAX_OGG_BYTES) throw new IOException("OGG 文件超过 64 MiB 限制");
		try (var inputStream = Files.newInputStream(source); var audio = new JOrbisAudioStream(inputStream)) {
			// Minecraft plays Ogg Vorbis, not arbitrary files merely renamed to .ogg (including Opus).
			audio.getFormat();
		} catch (IOException | RuntimeException error) { throw new IOException("不是 Minecraft 可播放的 Ogg Vorbis 音频", error); }

		Path target = root.resolve("sounds").resolve(name + ".ogg").normalize();
		Path jsonPath = root.resolve("sounds.json");
		checkDestination(root, target); checkDestination(root, jsonPath);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("音频文件已存在，请使用其他音效 ID");
		boolean jsonExisted = Files.exists(jsonPath, LinkOption.NOFOLLOW_LINKS);
		String original = null;
		JsonObject sounds = new JsonObject();
		if (jsonExisted) {
			if (!Files.isRegularFile(jsonPath, LinkOption.NOFOLLOW_LINKS) || Files.size(jsonPath) > MAX_JSON_BYTES)
				throw new IOException("sounds.json 不是有效文件或超过 1 MiB");
			original = Files.readString(jsonPath, StandardCharsets.UTF_8);
			try {
				var parsed = JsonParser.parseString(original);
				if (!parsed.isJsonObject()) throw new IllegalArgumentException("声音事件需要 JSON 对象");
				sounds = parsed.getAsJsonObject();
			} catch (RuntimeException error) { throw new IOException("sounds.json 格式错误，未导入：" + error.getMessage(), error); }
		}
		if (sounds.has(name)) throw new IOException("音效 ID 已存在：" + id + "，请使用其他 ID");
		JsonObject sound = new JsonObject(); sound.addProperty("name", id); sound.addProperty("stream", true);
		JsonArray files = new JsonArray(); files.add(sound);
		JsonObject event = new JsonObject(); event.add("sounds", files); sounds.add(name, event);
		byte[] updated = (new GsonBuilder().setPrettyPrinting().create().toJson(sounds) + "\n").getBytes(StandardCharsets.UTF_8);
		if (updated.length > MAX_JSON_BYTES) throw new IOException("合并后的 sounds.json 超过 1 MiB");
		// Prepare JSON first; a JSON write failure must not leave an unregistered audio file behind.
		Path temporary = Files.createTempFile(root, ".sound_import_", ".tmp");
		boolean copied = false;
		try {
			Files.write(temporary, updated);
			Files.createDirectories(target.getParent());
			checkDestination(root, target); checkDestination(root, jsonPath);
			Files.copy(source, target); copied = true;
			if (Files.size(target) > MAX_OGG_BYTES) throw new IOException("OGG 文件超过 64 MiB 限制");
			// Do not silently overwrite a sounds.json changed while this import was preparing.
			if (jsonExisted != Files.exists(jsonPath, LinkOption.NOFOLLOW_LINKS)
					|| jsonExisted && !original.equals(Files.readString(jsonPath, StandardCharsets.UTF_8)))
				throw new IOException("sounds.json 已被其他操作修改，请重试");
			try { Files.move(temporary, jsonPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException error) { Files.move(temporary, jsonPath, StandardCopyOption.REPLACE_EXISTING); }
			copied = false;
			return new Result(id, "sounds/" + name + ".ogg");
		} finally {
			if (copied) Files.deleteIfExists(target);
			Files.deleteIfExists(temporary);
		}
	}

	private static void checkDestination(Path root, Path path) throws IOException {
		if (!path.startsWith(root)) throw new IOException("音效路径超出命名空间");
		for (Path cursor = path; cursor != null && cursor.startsWith(root); cursor = cursor.getParent())
			if (Files.isSymbolicLink(cursor)) throw new IOException("音效目标路径包含符号链接");
	}
}
