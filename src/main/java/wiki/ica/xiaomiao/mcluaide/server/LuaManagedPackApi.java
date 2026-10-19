package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Server-owned data packs and exportable vanilla resource packs. */
final class LuaManagedPackApi {
	private static final String PACK_NAME = "mc_lua_ide_generated";
	private static final int MAX_JSON_CHARS = 262_144;
	private static final int MAX_BATCH_BYTES = 16 * 1024 * 1024;
	private static final int MAX_BATCH_ENTRIES = 256;
	private static final int MAX_TEXTURE_BYTES = 4 * 1024 * 1024;
	private static final int RELOAD_TIMEOUT_SECONDS = 120;
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
	private static final Set<String> RESTART_KINDS = Set.of("dimension", "dimension_type", "biome",
			"noise_settings", "density_function", "world_clock", "timeline");

	private LuaManagedPackApi() {
	}

	static void install(LuaTable mc, MinecraftServer server) {
		LuaTable api = new LuaTable();
		bind(api, "putData", args -> putData(server, args.checkjstring(1), args.checkjstring(2), args.checkjstring(3)));
		bind(api, "putDataBatch", args -> putDataBatch(server, args.checktable(1)));
		bind(api, "putAssetJson", args -> putAssetJson(server, args.checkjstring(1), args.checkjstring(2), args.checkjstring(3)));
		bind(api, "putAssetPng", args -> putAssetPng(server, args.checkjstring(1), args.checkjstring(2), args.checkjstring(3)));
		bind(api, "putTexture", args -> putAssetPng(server, "textures/item", args.checkjstring(1), args.checkjstring(2)));
		bind(api, "exportAssets", args -> exportAssets(server));
		mc.set("Pack", api);
		mc.set("pack", api);
	}

	private static LuaTable putData(MinecraftServer server, String kind, String rawId, String json) throws IOException {
		return putDataEntries(server, List.of(new DataEntry(kind, rawId, json)));
	}

	private static LuaTable putDataBatch(MinecraftServer server, LuaTable entries) throws IOException {
		int size = entries.length();
		if (size < 1 || size > MAX_BATCH_ENTRIES)
			throw new LuaError("每批必须包含 1～" + MAX_BATCH_ENTRIES + " 条 JSON 资源");
		List<DataEntry> parsed = new java.util.ArrayList<>(size);
		for (int index = 1; index <= size; index++) {
			LuaTable entry = entries.get(index).checktable();
			parsed.add(new DataEntry(entry.get("kind").checkjstring(),
					entry.get("id").checkjstring(), entry.get("json").checkjstring()));
		}
		return putDataEntries(server, parsed);
	}

	private static synchronized LuaTable putDataEntries(MinecraftServer server, List<DataEntry> entries) throws IOException {
		Path root = dataPackRoot(server);
		Map<Path, byte[]> pending = new LinkedHashMap<>();
		boolean restart = false;
		int bytes = 0;
		for (DataEntry entry : entries) {
			validateJson(entry.json());
			Path target = resourceFile(root, "data", entry.kind(), entry.id(), ".json");
			byte[] content = entry.json().getBytes(StandardCharsets.UTF_8);
			bytes += content.length;
			if (bytes > MAX_BATCH_BYTES) throw new LuaError("一批 JSON 总大小不能超过 16 MiB");
			pending.put(target, content);
			restart |= requiresRestart(entry.kind());
		}
		writeMetadata(root, PackType.SERVER_DATA);
		Map<Path, byte[]> previous = new LinkedHashMap<>();
		for (Path target : pending.keySet()) {
			checkNoLinks(root, target);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
				throw new LuaError("目标不是普通文件：" + target);
			previous.put(target, Files.exists(target, LinkOption.NOFOLLOW_LINKS)
					? Files.readAllBytes(target) : null);
		}
		List<Path> written = new java.util.ArrayList<>();
		try {
			for (Map.Entry<Path, byte[]> pendingEntry : pending.entrySet()) {
				atomicWrite(root, pendingEntry.getKey(), pendingEntry.getValue());
				written.add(pendingEntry.getKey());
			}
			reloadDataPack(server);
		} catch (IOException | RuntimeException error) {
			if (error instanceof ReloadOutcomeUnknown)
				throw new LuaError("数据包重载结果暂不确定，生成文件已保留；请稍后检查服务端日志与资源状态："
						+ error.getMessage());
			try {
				for (Path target : written) {
					byte[] oldContent = previous.get(target);
					if (oldContent == null) Files.deleteIfExists(target);
					else atomicWrite(root, target, oldContent);
				}
			} catch (IOException | RuntimeException rollbackError) {
				error.addSuppressed(rollbackError);
				throw new LuaError("数据包写入或重载失败，且回滚不完整；请检查服务端日志：" + error.getMessage());
			}
			throw new LuaError("数据包写入或重载失败；已恢复本批次原文件，请检查服务端日志：" + error.getMessage());
		}
		LuaTable result = status(restart ? "world_restart_required" : "server_reload_completed",
				pending.size() == 1 ? pending.keySet().iterator().next() : root,
				false, restart, true,
				restart ? "已保存并启用数据包；维度或世界生成数据须重启世界后生效"
						: "已完成服务端数据重载；请检查服务端日志确认 JSON 已被游戏接受");
		result.set("count", pending.size());
		return result;
	}

	private static synchronized LuaTable putAssetJson(MinecraftServer server, String kind, String rawId, String json) throws IOException {
		validateJson(json);
		Path root = assetPackRoot(server);
		Path target = resourceFile(root, "assets", kind, rawId, ".json");
		writeMetadata(root, PackType.CLIENT_RESOURCES);
		atomicWrite(root, target, json.getBytes(StandardCharsets.UTF_8));
		return status("needs_export_and_distribution", target, false, false, false,
				"客户端资源已保存；调用 MC.Pack.exportAssets() 并向玩家发布 ZIP 后生效");
	}

	private static synchronized LuaTable putAssetPng(MinecraftServer server, String kind, String rawId, String base64) throws IOException {
		if (base64.length() > MAX_TEXTURE_BYTES * 2) throw new LuaError("贴图 Base64 过长");
		byte[] png;
		try {
			png = Base64.getDecoder().decode(base64);
		} catch (IllegalArgumentException error) {
			throw new LuaError("贴图必须是合法的 PNG Base64 数据");
		}
		if (png.length < 24 || png.length > MAX_TEXTURE_BYTES || !hasPngSignature(png))
			throw new LuaError("贴图必须是小于 4 MiB 的 PNG 文件");
		Path root = assetPackRoot(server);
		Path target = resourceFile(root, "assets", kind, rawId, ".png");
		writeMetadata(root, PackType.CLIENT_RESOURCES);
		atomicWrite(root, target, png);
		return status("needs_export_and_distribution", target, false, false, false,
				"贴图已保存；导出并向玩家发布资源包后生效");
	}

	private static synchronized LuaTable exportAssets(MinecraftServer server) throws IOException {
		Path zip = exportAssetFolder(assetPackRoot(server));
		return status("ready_to_distribute", zip, false, false, false,
				"ZIP 已导出；由管理员发布给玩家，原版客户端无需安装本模组");
	}

	static Path exportAssetFolder(Path root) throws IOException {
		writeMetadata(root, PackType.CLIENT_RESOURCES);
		Path zip = root.resolveSibling(PACK_NAME + "_resources.zip");
		Path temporary = zip.resolveSibling(zip.getFileName() + ".tmp");
		if (Files.isSymbolicLink(temporary)) throw new LuaError("导出目标不允许是符号链接");
		List<Path> files;
		try (var paths = Files.walk(root)) {
			files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> !path.getFileName().toString().endsWith(".tmp"))
					.filter(path -> {
						Path relative = root.relativize(path);
						return relative.toString().equals("pack.mcmeta") || relative.toString().equals("pack.png")
								|| relative.getNameCount() > 1 && relative.getName(0).toString().equals("assets");
					})
					.sorted(Comparator.comparing(Path::toString)).toList();
		}
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
			for (Path file : files) {
				checkNoLinks(root, file);
				String entry = root.relativize(file).toString().replace('\\', '/');
				output.putNextEntry(new ZipEntry(entry));
				Files.copy(file, output);
				output.closeEntry();
			}
		} catch (IOException | RuntimeException error) {
			Files.deleteIfExists(temporary);
			throw error;
		}
		moveAtomic(temporary, zip);
		return zip;
	}

	private static void reloadDataPack(MinecraftServer server) {
		try {
			List<String> selected = server.submit(() -> {
				PackRepository repository = server.getPackRepository();
				repository.reload();
				String id = "file/" + PACK_NAME;
				if (!repository.isAvailable(id))
					throw new IllegalStateException("Minecraft 未识别生成的数据包 " + id);
				repository.addPack(id);
				return List.copyOf(repository.getSelectedIds());
			}).get(10, TimeUnit.SECONDS);
			server.reloadResources(selected).get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new ReloadOutcomeUnknown("脚本已停止；服务端可能仍在完成数据重载");
		} catch (TimeoutException error) {
			throw new ReloadOutcomeUnknown("等待服务端数据重载超时");
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			throw new LuaError(cause == null ? "服务端数据重载失败" : cause.getMessage());
		}
	}

	private static boolean requiresRestart(String kind) {
		return kind.startsWith("worldgen/") || RESTART_KINDS.contains(kind);
	}

	private static boolean hasPngSignature(byte[] png) {
		for (int i = 0; i < PNG_SIGNATURE.length; i++) if (png[i] != PNG_SIGNATURE[i]) return false;
		return true;
	}

	private static Path dataPackRoot(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME).toAbsolutePath().normalize();
	}

	private static Path assetPackRoot(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(PACK_NAME + "_resources").toAbsolutePath().normalize();
	}

	private static Path resourceFile(Path root, String section, String kind, String rawId, String extension) {
		if (!validPath(kind)) throw new LuaError("无效资源类别：" + kind);
		Identifier id = Identifier.tryParse(rawId.contains(":") ? rawId : "minecraft:" + rawId);
		if (id == null || !validPath(id.getPath()) || id.getPath().endsWith(extension))
			throw new LuaError("无效资源 ID：" + rawId);
		Path target = root.resolve(section).resolve(id.getNamespace()).resolve(kind)
				.resolve(id.getPath() + extension).normalize();
		if (!target.startsWith(root)) throw new LuaError("资源路径超出生成包目录");
		return target;
	}

	private static boolean validPath(String value) {
		if (value.isEmpty() || value.length() > 180 || !value.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")) return false;
		for (String segment : value.split("/")) if (segment.equals(".") || segment.equals("..")) return false;
		return true;
	}

	private static void validateJson(String json) {
		if (json.length() > MAX_JSON_CHARS) throw new LuaError("JSON 超过 262144 字符");
		try {
			JsonParser.parseString(json);
		} catch (RuntimeException error) {
			throw new LuaError("JSON 格式错误：" + error.getMessage());
		}
	}

	static void writeMetadata(Path root, PackType type) throws IOException {
		Path metadata = root.resolve("pack.mcmeta");
		PackFormat format = SharedConstants.getCurrentVersion().packVersion(type);
		String version = format.minor() == 0 ? Integer.toString(format.major())
				: "[" + format.major() + "," + format.minor() + "]";
		String json = "{\"pack\":{\"description\":\"MC Lua IDE generated resources\","
				+ "\"min_format\":" + version + ",\"max_format\":" + version + "}}";
		atomicWrite(root, metadata, json.getBytes(StandardCharsets.UTF_8));
	}

	private static void atomicWrite(Path root, Path target, byte[] content) throws IOException {
		checkNoLinks(root, target);
		Files.createDirectories(target.getParent());
		checkNoLinks(root, target);
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		if (Files.isSymbolicLink(temporary)) throw new LuaError("目标目录含符号链接");
		try {
			Files.write(temporary, content);
			moveAtomic(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void moveAtomic(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void checkNoLinks(Path root, Path target) {
		for (Path current = target; current != null && current.startsWith(root); current = current.getParent()) {
			if (Files.isSymbolicLink(current)) throw new LuaError("资源目录中不允许使用符号链接：" + current);
		}
	}

	private static LuaTable status(String state, Path path, boolean applied, boolean restart,
			boolean reloadCompleted, String message) {
		LuaTable result = new LuaTable();
		result.set("status", state);
		result.set("path", path.toString());
		result.set("applied", LuaValue.valueOf(applied));
		result.set("requiresRestart", LuaValue.valueOf(restart));
		result.set("reloadCompleted", LuaValue.valueOf(reloadCompleted));
		result.set("message", message);
		return result;
	}

	private static void bind(LuaTable api, String name, Operation operation) {
		api.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				try {
					return operation.run(args);
				} catch (LuaError error) {
					throw error;
				} catch (Exception error) {
					throw new LuaError(error.getMessage() == null ? error.toString() : error.getMessage());
				}
			}
		});
	}

	@FunctionalInterface
	private interface Operation {
		LuaValue run(Varargs args) throws Exception;
	}

	private record DataEntry(String kind, String id, String json) {
	}

	private static final class ReloadOutcomeUnknown extends RuntimeException {
		private ReloadOutcomeUnknown(String message) {
			super(message);
		}
	}
}
