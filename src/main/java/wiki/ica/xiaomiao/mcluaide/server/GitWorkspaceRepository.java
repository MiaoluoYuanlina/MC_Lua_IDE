package wiki.ica.xiaomiao.mcluaide.server;

import wiki.ica.xiaomiao.mcluaide.network.LuaGit;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Dedicated version store. It never opens the mod source repository or executes repository hooks. */
final class GitWorkspaceRepository {
	static final int PAGE_SIZE = 30, MAX_FILES = 4096, MAX_FILE_BYTES = 20 * 1024 * 1024;
	static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
	private static final String PREFIX = "mc_lua_ide/";
	private final Path repository, hooks;
	private final String sessionToken;
	private record Result(int code, byte[] bytes, String error) {
		String text() { return new String(bytes, StandardCharsets.UTF_8).strip(); }
	}

	GitWorkspaceRepository(Path repository) throws IOException {
		this(repository, "");
	}
	GitWorkspaceRepository(Path repository, String sessionToken) throws IOException {
		this.sessionToken = sessionToken == null ? "" : sessionToken;
		this.repository = repository.toAbsolutePath().normalize();
		assertSafe(this.repository, this.repository.resolve(".git"));
		Files.createDirectories(this.repository);
		hooks = this.repository.resolve("empty-hooks");
		assertSafe(this.repository, hooks);
		Files.createDirectories(hooks);
	}

	void initialize(String branch) throws Exception {
		GitWorkspaceService.validateBranch(branch);
		Path metadata = repository.resolve(".git");
		if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("版本库存储位置已经是外部 Git 工作树，拒绝操作");
		if (Files.exists(metadata.resolve("commondir"), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("版本库不能与外部仓库共用 Git 数据目录");
		if (!Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS))
			run("init", "--initial-branch=" + branch);
		if (!Path.of(run("rev-parse", "--show-toplevel")).toRealPath().equals(repository.toRealPath())
				|| !Path.of(run("rev-parse", "--absolute-git-dir")).toRealPath().equals(metadata.toRealPath()))
			throw new IOException("Git 配置重定向到了其他工作区，拒绝操作");
		// Dedicated repositories have one branch; changing the user's configured branch uses a different store.
	}

	/** Fetch only; advance the local branch only when it is a fast-forward. Never overwrite pending commits. */
	String synchronize(String remote, String branch) throws Exception {
		if (git(4000, "remote", "get-url", "origin").code != 0) run("remote", "add", "origin", remote);
		else if (!run("remote", "get-url", "origin").equals(remote)) throw new IOException("仓库地址不匹配，请重新打开 Git 窗口");
		Result heads = git(4000, "ls-remote", "--exit-code", "--heads", "origin", "refs/heads/" + branch);
		if (heads.code == 2) return "远端分支尚不存在，第一次上传时创建";
		require(heads, "读取远端分支失败");
		run("fetch", "--no-tags", "--filter=blob:none", "origin",
				"+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
		String remoteHead = run("rev-parse", "refs/remotes/origin/" + branch);
		String localHead = head();
		if (localHead.isEmpty() || git(4000, "merge-base", "--is-ancestor", localHead, remoteHead).code == 0) {
			run("update-ref", "refs/heads/" + branch, remoteHead);
			return "已同步 GitHub 历史";
		}
		if (git(4000, "merge-base", "--is-ancestor", remoteHead, localHead).code == 0)
			return "存在尚未上传的本地提交，可点击提交并上传重试";
		throw new IOException("本地与 GitHub 都有新提交，历史已分叉；未覆盖任何版本。请先在服务器使用 Git 合并后重试：" + repository);
	}

	String refresh(String remote, String branch) throws Exception {
		try { return synchronize(remote, branch); }
		catch (Exception error) {
			if (head().isEmpty()) throw error;
			return "同步失败，显示缓存历史：" + error.getMessage();
		}
	}

	String head() throws Exception {
		Result result = git(4000, "rev-parse", "--verify", "HEAD");
		return result.code == 0 ? result.text() : "";
	}

	String commit(Map<String, byte[]> snapshot, String message, boolean allowEmpty) throws Exception {
		validateSnapshot(snapshot);
		String head = head();
		if (!head.isEmpty()) run("read-tree", "HEAD");
		else run("read-tree", "--empty");
		Path target = repository.resolve("mc_lua_ide");
		Files.createDirectories(target);
		// Delete only files in our dedicated cache, never the user's workspace or other remote directories.
		try (var paths = Files.walk(target)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				assertSafe(repository, path);
				if (path.equals(target)) continue;
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					try (var children = Files.list(path)) { if (children.findAny().isEmpty()) Files.delete(path); }
				} else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
				else throw new IOException("Git 缓存包含非普通文件");
			}
		}
		for (var entry : snapshot.entrySet()) atomicWrite(target, target.resolve(entry.getKey()), entry.getValue());
		atomicWrite(target, target.resolve(".workspace"), "MC Lua IDE snapshot v1\n".getBytes(StandardCharsets.UTF_8));
		run("add", "-A", "-f", "--", "mc_lua_ide");
		Result diff = git(4000, "diff", "--cached", "--quiet", "--exit-code");
		if (diff.code == 0 && !allowEmpty) return "";
		if (diff.code != 0 && diff.code != 1) require(diff, "比较修改失败");
		List<String> args = new ArrayList<>(List.of("-c", "user.name=MC Lua IDE", "-c",
				"user.email=mc-lua-ide@users.noreply.github.com", "commit", "--no-gpg-sign", "-m", message));
		if (allowEmpty) args.add("--allow-empty");
		args.addAll(List.of("--", "mc_lua_ide"));
		run(args.toArray(String[]::new));
		return head();
	}

	void push(String branch) throws Exception {
		run("push", "origin", "HEAD:refs/heads/" + branch);
	}

	LuaGit.History history(int offset, String branch, String notice) throws Exception {
		if (offset < 0 || offset > 100_000) throw new IOException("历史页码超出范围");
		if (head().isEmpty()) return new LuaGit.History(List.of(), offset, false, repository.toString(), notice);
		Result published = git(4_000_000, "rev-list", "--max-count=100000", "refs/remotes/origin/" + branch);
		Set<String> pushed = published.code == 0 ? Set.copyOf(List.of(published.text().split("\n"))) : Set.of();
		String log = run("log", "--first-parent", "--skip=" + offset, "-n", Integer.toString(PAGE_SIZE + 1),
				"--format=%H%x09%ct%x09%s");
		List<LuaGit.Commit> commits = new ArrayList<>();
		for (String line : log.split("\n")) {
			String[] parts = line.split("\t", 3);
			if (parts.length == 3) commits.add(new LuaGit.Commit(parts[0], Long.parseLong(parts[1]),
					parts[2].substring(0, Math.min(parts[2].length(), 240)), pushed.contains(parts[0])));
		}
		boolean more = commits.size() > PAGE_SIZE;
		return new LuaGit.History(commits.subList(0, Math.min(PAGE_SIZE, commits.size())), offset, more,
				repository.toString(), notice);
	}

	LuaGit.Detail detail(String sha, String requestedFile) throws Exception {
		validateCommit(sha);
		Result commitMessage = git(24_000, true, "show", "-s", "--format=%B", sha);
		require(commitMessage, "读取版本简介失败");
		String message = commitMessage.text();
		if (message.length() > 4000) message = message.substring(0, 4000) + "…（简介过长，已截断）";
		String names = run("diff-tree", "--root", "--no-commit-id", "--name-status", "-r", sha, "--", "mc_lua_ide");
		List<LuaGit.Change> files = new ArrayList<>();
		for (String line : names.split("\n")) {
			String[] parts = line.split("\t", 2);
			if (parts.length == 2 && parts[1].startsWith(PREFIX) && !parts[1].endsWith("/.workspace")) {
				String path = parts[1].substring(PREFIX.length());
				validatePath(path); files.add(new LuaGit.Change(path, parts[0]));
			}
		}
		String file = requestedFile == null || requestedFile.isEmpty() ? files.isEmpty() ? "" : files.getFirst().path() : requestedFile;
		int totalFiles = files.size();
		if (files.size() > 512) files = files.subList(0, 512);
		if (file.isEmpty()) return new LuaGit.Detail(sha, message, files, totalFiles, "", "（此版本没有文件内容修改）", "", false);
		validatePath(file);
		Result patch = git(400_000, true, "show", "--format=", "--no-ext-diff", "--no-textconv", sha, "--", PREFIX + file);
		require(patch, "读取差异失败");
		String diff = new String(patch.bytes, StandardCharsets.UTF_8);
		String source = "（文件在此版本中已删除）";
		if (git(4000, "cat-file", "-e", sha + ":" + PREFIX + file).code == 0) {
			if (binary(file)) source = "（二进制文件：" + file + "；恢复时保留完整原始数据）";
			else source = new String(blob(sha, file), StandardCharsets.UTF_8);
		}
		boolean truncated = diff.length() > 80_000 || source.length() > 80_000;
		LuaGit.Detail detail = new LuaGit.Detail(sha, message, files, totalFiles, file, clip(diff), clip(source), truncated);
		// JSON escaping can expand arbitrary source text; stay within the control-response packet bound.
		while (LuaGit.JSON.toJson(detail).length() > LuaProtocol.MAX_RESPONSE_CHARS) {
			detail = new LuaGit.Detail(sha, message, files, totalFiles, file,
					detail.diff().substring(0, detail.diff().length() / 2), detail.source().substring(0, detail.source().length() / 2), true);
		}
		return detail;
	}

	Map<String, byte[]> snapshot(String sha) throws Exception {
		validateCommit(sha);
		Result tree = git(2_000_000, "ls-tree", "-r", "-z", sha, "--", "mc_lua_ide/");
		require(tree, "读取版本文件失败");
		Map<String, byte[]> files = new LinkedHashMap<>();
		long total = 0;
		for (String row : new String(tree.bytes, StandardCharsets.UTF_8).split("\u0000")) {
			if (row.isEmpty()) continue;
			String[] record = row.split("\t", 2), meta = record[0].split(" ");
			if (record.length != 2 || meta.length != 3 || !meta[1].equals("blob")
					|| !(meta[0].equals("100644") || meta[0].equals("100755")))
				throw new IOException("历史版本含符号链接或特殊文件，拒绝恢复");
			String path = record[1].substring(PREFIX.length());
			if (path.equals(".workspace")) continue;
			validatePath(path);
			byte[] bytes = blob(sha, path);
			total += bytes.length;
			if (total > MAX_TOTAL_BYTES || files.size() >= MAX_FILES) throw new IOException("版本超过工作区恢复限制");
			files.put(path, bytes);
		}
		validateSnapshot(files);
		return files;
	}

	private byte[] blob(String sha, String path) throws Exception {
		String object = sha + ":" + PREFIX + path;
		if (Long.parseLong(run("cat-file", "-s", object)) > MAX_FILE_BYTES) throw new IOException("历史文件超过 20 MiB：" + path);
		Result result = git(MAX_FILE_BYTES, "cat-file", "blob", object);
		require(result, "读取历史代码失败");
		return result.bytes;
	}

	private void validateCommit(String sha) throws Exception {
		if (sha == null || !sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
				|| git(4000, "merge-base", "--is-ancestor", sha, "HEAD").code != 0)
			throw new IOException("只能选择当前仓库历史中的完整提交 ID");
	}

	static void validatePath(String path) throws IOException {
		if (path == null || path.length() > 256 || path.contains("\\")) throw new IOException("无效版本文件路径");
		if (path.startsWith("lua/") && path.endsWith(".lua")
				&& LuaScriptNames.isValid(path.substring(4, path.length() - 4))) return;
		if (path.startsWith("datapacks/") && path.length() <= 250) {
			String[] parts = path.substring(10).split("/", -1);
			boolean valid = parts.length >= 2;
			for (String part : parts) valid &= part.length() <= 64 && part.matches("[a-z0-9_.-]+")
					&& !part.equals(".") && !part.equals("..");
			if (valid && (path.endsWith(".json") || path.endsWith("/pack.mcmeta") || binary(path)
					|| path.matches("datapacks/[^/]+/data/[^/]+/function/.+\\.mcfunction"))) return;
		}
		throw new IOException("历史文件不属于 IDE 工作区：" + path);
	}

	static boolean binary(String path) { return path.endsWith(".png") || path.endsWith(".ogg") || path.endsWith(".nbt"); }
	static void validateSnapshot(Map<String, byte[]> files) throws IOException {
		long size = 0;
		if (files.size() > MAX_FILES) throw new IOException("工作区文件不能超过 " + MAX_FILES + " 个");
		Set<String> caseNames = new java.util.HashSet<>();
		for (var entry : files.entrySet()) {
			validatePath(entry.getKey());
			if (!caseNames.add(entry.getKey().toLowerCase(java.util.Locale.ROOT))) throw new IOException("存在仅大小写不同的同名文件");
			byte[] bytes = entry.getValue(); size += bytes.length;
			if (bytes.length > MAX_FILE_BYTES || size > MAX_TOTAL_BYTES) throw new IOException("工作区超过 20 MiB/文件或 256 MiB/版本限制");
			if (!binary(entry.getKey())) {
				try {
					String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
					if (text.length() > LuaProtocol.MAX_SCRIPT_CHARS) throw new IOException("历史代码超过编辑器容量");
				} catch (java.nio.charset.CharacterCodingException error) { throw new IOException("历史代码不是 UTF-8", error); }
			}
		}
		for (String name : caseNames) {
			for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
				if (caseNames.contains(name.substring(0, slash))) throw new IOException("历史版本存在文件与目录的路径冲突");
		}
	}

	static void assertSafe(Path root, Path path) throws IOException {
		if (!path.normalize().startsWith(root.normalize())) throw new IOException("文件路径超出 Git 工作区");
		for (Path current = path; current != null; current = current.getParent())
			if (Files.isSymbolicLink(current)) throw new IOException("Git 工作区不能包含符号链接：" + current);
	}

	static void atomicWrite(Path root, Path path, byte[] bytes) throws IOException {
		assertSafe(root, path);
		Files.createDirectories(path.getParent());
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("目标不是普通文件：" + path);
		Path temporary = Files.createTempFile(path.getParent(), ".mc-lua-git-", ".tmp");
		try {
			Files.write(temporary, bytes);
			try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
		} finally { Files.deleteIfExists(temporary); }
	}

	private String run(String... args) throws Exception { return require(git(2_000_000, args), "Git 操作失败").text(); }
	private static Result require(Result result, String context) throws IOException {
		if (result.code != 0) throw new IOException(context + "：" + result.error + " " + result.text());
		return result;
	}
	private static String clip(String value) { return value.length() > 80_000 ? value.substring(0, 80_000) + "\n…（预览已截断，恢复使用完整内容）" : value; }

	private Result git(int limit, String... args) throws Exception { return git(limit, false, args); }
	private Result git(int limit, boolean truncate, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + hooks,
				"-c", "core.quotePath=false", "-c", "core.autocrlf=false", "-c", "core.attributesFile=NUL"));
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command).directory(repository.toFile());
		// Inherited Git environment must not redirect this operation to some unrelated repository/index.
		builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		builder.environment().put("GCM_INTERACTIVE", "Never");
		builder.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
		String token = sessionToken.isEmpty() ? System.getenv("MC_LUA_IDE_GITHUB_TOKEN") : sessionToken;
		String encoded = token == null ? "" : Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
		if (token != null && !token.isBlank()) {
			builder.environment().put("GIT_CONFIG_COUNT", "3");
			builder.environment().put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader");
			builder.environment().put("GIT_CONFIG_VALUE_0", "");
			builder.environment().put("GIT_CONFIG_KEY_1", "http.https://github.com/.extraHeader");
			builder.environment().put("GIT_CONFIG_VALUE_1", "Authorization: Basic " + encoded);
			// Do not consult another administrator's credential helper while using a session token.
			builder.environment().put("GIT_CONFIG_KEY_2", "credential.helper");
			builder.environment().put("GIT_CONFIG_VALUE_2", "");
		}
		Path output = Files.createTempFile(hooks, "output-", ".tmp"), error = Files.createTempFile(hooks, "error-", ".tmp");
		try {
			Process process = builder.redirectOutput(output.toFile()).redirectError(error.toFile()).start();
			process.getOutputStream().close();
			if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Git 操作超时（90 秒）"); }
			byte[] bytes;
			try (var input = Files.newInputStream(output)) { bytes = input.readNBytes(limit + 1); }
			if (bytes.length > limit && !truncate) throw new IOException("Git 输出超过安全限制");
			String errors;
			try (var input = Files.newInputStream(error)) { errors = new String(input.readNBytes(4000), StandardCharsets.UTF_8); }
			if (token != null && !token.isBlank()) errors = errors.replace(token, "[已隐藏]").replace(encoded, "[已隐藏]");
			return new Result(process.exitValue(), bytes, errors.strip());
		} finally { Files.deleteIfExists(output); Files.deleteIfExists(error); }
	}
}
