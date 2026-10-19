package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.loader.api.FabricLoader;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Sandboxed UTF-8 file API rooted in config/mc_lua_ide/data/&lt;script&gt;. */
final class MinecraftLuaFileApi {
	private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_.\\-/]{1,160}");
	private static final Path DATA_DIRECTORY = FabricLoader.getInstance().getConfigDir()
			.resolve("mc_lua_ide").resolve("data").toAbsolutePath().normalize();

	private MinecraftLuaFileApi() {
	}

	static void install(LuaTable mc, ServerScriptManager.ScriptExecution execution) {
		LuaLimits limits = execution.limits();
		if (!limits.allowFileRead() && !limits.allowFileWrite() && !limits.allowFileDelete()) return;

		Path root = DATA_DIRECTORY.resolve(execution.name()).normalize();
		LuaTable api = new LuaTable();
		bind(api, "read", args -> {
			require(limits.allowFileRead(), "服务器已禁止 Lua 读取文件");
			Path path = resolve(root, args.checkjstring(1), false);
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new LuaError("文件不存在：" + args.arg(1));
			long size = Files.size(path);
			if (size > limits.maxDataFileBytes()) throw new LuaError("文件超过读取限制：" + size + " 字节");
			byte[] bytes = Files.readAllBytes(path);
			if (bytes.length > limits.maxDataFileBytes()) throw new LuaError("文件超过读取限制");
			return LuaValue.valueOf(new String(bytes, StandardCharsets.UTF_8));
		});
		bind(api, "exists", args -> {
			require(limits.allowFileRead(), "服务器已禁止 Lua 检查文件");
			Path path = resolve(root, args.checkjstring(1), false);
			return LuaValue.valueOf(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
		});
		bind(api, "list", args -> {
			require(limits.allowFileRead(), "服务器已禁止 Lua 列出文件");
			ensureRoot(root);
			String relative = args.optjstring(1, "");
			Path directory = relative.isBlank() ? root : resolve(root, relative, true);
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new LuaError("目录不存在：" + relative);
			List<String> files;
			try (Stream<Path> paths = Files.walk(directory)) {
				files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
						.limit(limits.maxDataFiles())
						.map(root::relativize)
						.map(path -> path.toString().replace('\\', '/'))
						.sorted(String.CASE_INSENSITIVE_ORDER)
						.toList();
			}
			LuaTable result = new LuaTable();
			for (int index = 0; index < files.size(); index++) result.set(index + 1, files.get(index));
			return result;
		});
		bind(api, "write", args -> write(root, limits, args.checkjstring(1), args.checkjstring(2), false));
		bind(api, "append", args -> write(root, limits, args.checkjstring(1), args.checkjstring(2), true));
		bind(api, "delete", args -> {
			require(limits.allowFileDelete(), "服务器已禁止 Lua 删除文件");
			Path path = resolve(root, args.checkjstring(1), false);
			boolean deleted = Files.deleteIfExists(path);
			if (deleted) cleanupEmptyDirectories(root, path.getParent());
			return LuaValue.valueOf(deleted);
		});
		mc.set("File", api);
		mc.set("file", api);
	}

	private static LuaValue write(Path root, LuaLimits limits, String relative, String content, boolean append)
			throws IOException {
		require(limits.allowFileWrite(), "服务器已禁止 Lua 写入文件");
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		Path path = resolve(root, relative, false);
		long existingSize = append && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
		if (existingSize + bytes.length > limits.maxDataFileBytes()) {
			throw new LuaError("写入后文件将超过服务器限制的 " + limits.maxDataFileBytes() + " 字节");
		}
		ensureRoot(root);
		Path parent = path.getParent();
		if (parent != null) {
			ensureNoSymlinks(root, parent);
			Files.createDirectories(parent);
			ensureNoSymlinks(root, parent);
		}
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && countFiles(root) >= limits.maxDataFiles()) {
			throw new LuaError("数据文件数量已达到服务器限制：" + limits.maxDataFiles());
		}
		if (append) {
			Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
					LinkOption.NOFOLLOW_LINKS);
		} else {
			Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					LinkOption.NOFOLLOW_LINKS);
		}
		return LuaValue.valueOf(bytes.length);
	}

	private static Path resolve(Path root, String relative, boolean allowDirectory) throws IOException {
		if (relative == null || relative.isBlank()) {
			if (allowDirectory) return root;
			throw new LuaError("文件路径不能为空");
		}
		String normalizedText = relative.replace('\\', '/');
		if (!SAFE_PATH.matcher(normalizedText).matches() || normalizedText.startsWith("/")
				|| normalizedText.contains("../") || normalizedText.equals("..") || normalizedText.contains(":")) {
			throw new LuaError("文件路径只能使用安全的相对路径");
		}
		Path resolved = root.resolve(normalizedText).normalize();
		if (!resolved.startsWith(root)) throw new LuaError("文件路径超出脚本数据目录");
		ensureRoot(root);
		ensureNoSymlinks(root, resolved);
		return resolved;
	}

	private static void ensureRoot(Path root) throws IOException {
		Files.createDirectories(DATA_DIRECTORY);
		if (Files.isSymbolicLink(DATA_DIRECTORY)) throw new LuaError("Lua 数据根目录不能是符号链接");
		ensureNoSymlinks(DATA_DIRECTORY, root);
		Files.createDirectories(root);
		ensureNoSymlinks(DATA_DIRECTORY, root);
	}

	private static void ensureNoSymlinks(Path root, Path target) throws IOException {
		Path current = root;
		Path relative = root.relativize(target);
		for (Path part : relative) {
			current = current.resolve(part);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
				throw new LuaError("文件路径不能经过符号链接");
			}
		}
	}

	private static long countFiles(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).count();
		}
	}

	private static void cleanupEmptyDirectories(Path root, Path directory) throws IOException {
		Path current = directory;
		while (current != null && current.startsWith(root)) {
			try {
				Files.deleteIfExists(current);
			} catch (DirectoryNotEmptyException ignored) {
				break;
			}
			if (current.equals(root)) break;
			current = current.getParent();
		}
	}

	private static void require(boolean allowed, String message) {
		if (!allowed) throw new LuaError(message);
	}

	private static void bind(LuaTable table, String name, FileCallable function) {
		table.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				try {
					return function.invoke(args);
				} catch (LuaError error) {
					throw error;
				} catch (Throwable error) {
					throw new LuaError(message(error));
				}
			}
		});
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	@FunctionalInterface
	private interface FileCallable {
		Varargs invoke(Varargs args) throws Exception;
	}
}
