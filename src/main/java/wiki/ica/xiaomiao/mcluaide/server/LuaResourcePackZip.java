package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.network.LuaServerPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Validate without extraction: ZIP traversal, expansion bombs, duplicate names and metadata. */
final class LuaResourcePackZip {
	private LuaResourcePackZip() { }
	static String validate(Path path, String expectedSha256) throws Exception {
		if (Files.size(path) <= 0 || Files.size(path) > LuaServerPack.MAX_BYTES) throw new IOException("资源包大小超过限制");
		if (!hash(path, "SHA-256").equals(expectedSha256)) throw new IOException("资源包上传校验失败，请重新上传");
		long total = 0;
		int count = 0;
		boolean assets = false;
		var names = new HashSet<String>();
		try (ZipFile zip = new ZipFile(path.toFile())) {
			var entries = zip.entries();
			byte[] buffer = new byte[64 * 1024];
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				String name = entry.getName();
				if (++count > 20_000 || name.length() > 1024 || name.startsWith("/") || name.contains("\\") || name.contains(":")
						|| name.indexOf('\0') >= 0 || java.util.Arrays.stream(name.split("/", -1)).anyMatch(part -> part.equals("..") || part.equals("."))
						|| !names.add(name)) throw new IOException("资源包包含不安全或重复的 ZIP 路径，或文件数量超过限制");
				if (entry.isDirectory()) continue;
				if (entry.getSize() < 0 || entry.getSize() > 128L * 1024 * 1024) throw new IOException("资源包中的单个文件过大");
				if (name.startsWith("assets/")) assets = true;
				long read = 0;
				try (var input = zip.getInputStream(entry)) {
					int bytes;
					while ((bytes = input.read(buffer)) >= 0) {
						read += bytes; total += bytes;
						if (read > 128L * 1024 * 1024 || total > 512L * 1024 * 1024)
							throw new IOException("资源包解压大小超过安全限制（单文件 128 MiB / 总计 512 MiB）");
					}
				}
				if (read != entry.getSize()) throw new IOException("ZIP 文件大小校验失败");
			}
			var meta = zip.getEntry("pack.mcmeta");
			if (meta == null || meta.isDirectory() || meta.getSize() > 65_536) throw new IOException("ZIP 根目录必须包含 pack.mcmeta（不能多套一层目录）");
			try (var input = zip.getInputStream(meta)) {
				var json = JsonParser.parseString(new String(input.readNBytes(65_537), StandardCharsets.UTF_8)).getAsJsonObject();
				var pack = json.getAsJsonObject("pack");
				if (pack == null || !pack.has("description") || !(pack.has("pack_format") || pack.has("min_format") && pack.has("max_format")))
					throw new IOException("pack.mcmeta 缺少 pack.description 或资源包版本格式");
			}
			if (!assets) throw new IOException("ZIP 中没有 assets 资源文件；数据包不能作为服务器资源包上传");
		}
		return hash(path, "SHA-1");
	}
	static String hash(Path path, String algorithm) throws Exception {
		MessageDigest digest = MessageDigest.getInstance(algorithm);
		try (var input = Files.newInputStream(path)) {
			byte[] buffer = new byte[64 * 1024]; int size;
			while ((size = input.read(buffer)) >= 0) digest.update(buffer, 0, size);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
