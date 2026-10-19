package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Administrator-only persistent local/GitHub history. All Git IO is off the game thread. */
final class GitWorkspaceService {
	private static final Pattern REPOSITORY = Pattern.compile("https://github\\.com/[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}(?:\\.git)?");
	private static final Pattern BRANCH = Pattern.compile("[\\p{L}\\p{N}_./@+-]{1,240}");
	private static final AtomicBoolean BUSY = new AtomicBoolean(), RESTORING = new AtomicBoolean();
	private GitWorkspaceService() { }
	static Path root() { return FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide").toAbsolutePath().normalize(); }
	record Config(String repository, String branch, LuaGit.Mode mode) {
		String encode() { return repository + "\n" + branch + "\n" + mode; }
	}

	static Config load() throws IOException {
		Path path = root().resolve("git.properties");
		GitWorkspaceRepository.assertSafe(root(), path);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return new Config("", "main", LuaGit.Mode.LOCAL);
		Properties settings = new Properties();
		try (var input = Files.newInputStream(path)) { settings.load(input); }
		String repository = settings.getProperty("repository", ""), branch = settings.getProperty("branch", "main");
		if (!repository.isBlank()) validateRepository(repository);
		validateBranch(branch);
		return new Config(repository, branch, mode(settings.getProperty("mode", repository.isEmpty() ? "LOCAL" : "GITHUB")));
	}

	static void save(String repository, String encoded) throws IOException {
		if (BUSY.get()) throw new IOException("Git 正在执行，请稍后保存设置");
		String[] parts = encoded.split("\n", -1);
		String branch = parts[0];
		LuaGit.Mode mode = mode(parts.length > 1 ? parts[1] : "GITHUB");
		if (!repository.isBlank() || mode == LuaGit.Mode.GITHUB) validateRepository(repository);
		validateBranch(branch);
		Properties settings = new Properties();
		settings.setProperty("repository", repository); settings.setProperty("branch", branch); settings.setProperty("mode", mode.name());
		var bytes = new java.io.ByteArrayOutputStream(); settings.store(bytes, "MC Lua IDE Git settings");
		GitWorkspaceRepository.atomicWrite(root(), root().resolve("git.properties"), bytes.toByteArray());
	}

	static boolean restoring() { return RESTORING.get(); }
	static LuaGit.Mode mode(String value) throws IOException {
		try { return LuaGit.Mode.valueOf(value); }
		catch (RuntimeException error) { throw new IOException("Git 模式只能为 LOCAL 或 GITHUB"); }
	}

	static CompletableFuture<String> push(MinecraftServer server, String message) throws IOException {
		return execute(server, "GITHUB", "commit", new LuaGit.CommitRequest(message, ""));
	}

	static CompletableFuture<String> execute(MinecraftServer server, String modeName, String operation, Object request) throws IOException {
		return execute(server, modeName, operation, request, null);
	}
	static CompletableFuture<String> execute(MinecraftServer server, String modeName, String operation, Object request, java.util.UUID owner) throws IOException {
		LuaGit.Mode mode = mode(modeName);
		Config config = load();
		String token = mode == LuaGit.Mode.GITHUB ? GitHubWorkspaceService.token(owner) : "";
		if (mode == LuaGit.Mode.GITHUB && owner != null && token.isEmpty())
			throw new IOException("GitHub 登录已失效，请先通过浏览器重新登录（不会使用其他账号凭据）");
		if (mode == LuaGit.Mode.GITHUB && config.repository().isBlank()) throw new IOException("请先在设置中填写 GitHub 仓库地址");
		boolean restore = operation.equals("restore");
		if (restore && ServerScriptManager.activeScriptName() != null) throw new IOException("请先停止正在运行的 Lua 脚本，再恢复工作区");
		if (!BUSY.compareAndSet(false, true)) throw new IOException("已有 Git 操作正在执行，请稍后重试");
		if (restore) RESTORING.set(true);
		Path data = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
		Path stores = data.getParent().resolve("mc_lua_ide_git");
		Path scripts = root().resolve("scripts");
		return CompletableFuture.supplyAsync(() -> {
			try {
				String key = mode == LuaGit.Mode.LOCAL ? "local" : "github-" + digest(config.repository() + "\n" + config.branch());
				GitWorkspaceRepository repo = new GitWorkspaceRepository(stores.resolve(key), token);
				repo.initialize(mode == LuaGit.Mode.LOCAL ? "main" : config.branch());
				String notice = mode == LuaGit.Mode.GITHUB && operation.equals("history") ? repo.refresh(config.repository(), config.branch()) : "本地 Git 不联网";
				if (mode == LuaGit.Mode.GITHUB && operation.equals("commit")) repo.synchronize(config.repository(), config.branch());
				GitWorkspaceFiles files = new GitWorkspaceFiles(scripts, data);
				return switch (operation) {
					case "history" -> LuaGit.JSON.toJson(repo.history((Integer) request, config.branch(), notice));
					case "detail" -> {
						LuaGit.DetailRequest detail = (LuaGit.DetailRequest) request;
						yield LuaGit.JSON.toJson(repo.detail(detail.sha(), detail.file()));
					}
					case "commit" -> {
						String sha = repo.commit(files.capture(), message((LuaGit.CommitRequest) request), false);
						if (mode == LuaGit.Mode.GITHUB) {
							try { repo.push(config.branch()); }
							catch (Exception error) { throw new IOException("上传失败，本地提交已保留，点击提交并上传即可重试：" + error.getMessage(), error); }
							yield "已上传到 GitHub · " + config.branch() + " · " + repo.head().substring(0, 8);
						}
						yield sha.isEmpty() ? "文件没有变化，无需创建版本" : "已保存本地版本 " + sha.substring(0, 8);
					}
					case "restore" -> {
						String sha = (String) request;
						var desired = repo.snapshot(sha);
						var before = files.capture();
						GitWorkspaceRepository backup = mode == LuaGit.Mode.LOCAL ? repo : new GitWorkspaceRepository(stores.resolve("local"));
						backup.initialize("main");
						String safe = backup.commit(before, "恢复前自动备份 · " + sha.substring(0, 8), true);
						files.restore(desired, before);
						try { repo.commit(desired, "恢复到版本 " + sha.substring(0, 8), true); }
						catch (Exception error) { files.restore(before, desired); throw error; }
						yield "已恢复 " + sha.substring(0, 8) + "；恢复前备份 " + safe.substring(0, 8)
								+ (mode == LuaGit.Mode.GITHUB ? "（未上传，点击提交并上传可发布）" : "") + "；数据包需手动重载";
					}
					default -> throw new IOException("未知 Git 操作");
				};
			} catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
			finally { RESTORING.set(false); BUSY.set(false); }
		}, command -> Thread.ofVirtual().name("mc-lua-git-" + operation).start(command));
	}

	static String message(LuaGit.CommitRequest request) throws IOException {
		if (request == null || request.summary() == null || request.summary().isBlank() || request.summary().length() > 240
				|| request.summary().chars().anyMatch(Character::isISOControl)) throw new IOException("版本简介需要 1–240 个可见字符");
		String description = request.description() == null ? "" : request.description().strip();
		if (description.length() > 2000 || description.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
			throw new IOException("详细说明最多 2000 字，不能含控制字符");
		return request.summary().strip() + (description.isEmpty() ? "" : "\n\n" + description);
	}

	private static String digest(String text) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
	}
	static void validateRepository(String repository) throws IOException {
		if (repository == null || !REPOSITORY.matcher(repository).matches() || repository.contains("..") || repository.endsWith("/.git"))
			throw new IOException("仅支持 https://github.com/用户/仓库.git 格式的地址");
	}
	static void validateBranch(String branch) throws IOException {
		if (branch == null || !BRANCH.matcher(branch).matches() || branch.startsWith("-") || branch.equals("@")
				|| branch.endsWith(".") || branch.contains("..") || branch.contains("@{")
				|| branch.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c) || "~^:?*[\\".indexOf(c) >= 0)
				|| java.util.Arrays.stream(branch.split("/", -1)).anyMatch(part -> part.isEmpty() || part.startsWith(".") || part.endsWith(".lock")))
			throw new IOException("分支名需为有效 Git 分支（最多 240 字，可含目录 /，不能含空格、控制符或引用表达式）");
	}
}
