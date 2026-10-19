package wiki.ica.xiaomiao.mcluaide.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import wiki.ica.xiaomiao.mcluaide.network.LuaGit;

/** No network access: validates GitHub target restrictions before any subprocess is started. */
public final class GitWorkspaceSmoke {
	private static int checks;
	private GitWorkspaceSmoke() { }

	public static void main(String[] args) throws Exception {
		GitWorkspaceService.validateRepository("https://github.com/example/project.git");
		GitWorkspaceService.validateRepository("https://github.com/example/project");
		GitWorkspaceService.validateBranch("main");
		GitWorkspaceService.validateBranch("dev_2");
		GitWorkspaceService.validateBranch("release/v1.2");
		GitWorkspaceService.validateBranch("功能/测试");
		rejectRepository("https://github.com/evil/project.git@other.example/a");
		rejectRepository("https://github.com/example/project.git?token=secret");
		rejectRepository("https://evil.example/example/project.git");
		rejectRepository("git@github.com:example/project.git");
		rejectBranch("../main");
		rejectBranch("-force");
		rejectBranch("main;rm");
		for (String invalid : List.of("feature//main", "feature/.main", "feature/main.lock", "main.", "@", "@{1}", "HEAD:other", "main\nother", "feature/")) rejectBranch(invalid);
		check(GitWorkspaceService.message(new LuaGit.CommitRequest("简介", "详细说明")).equals("简介\n\n详细说明"), "Commit description");
		reject(() -> GitWorkspaceService.message(new LuaGit.CommitRequest("", "")), "Empty summary");
		reject(() -> GitWorkspaceService.message(new LuaGit.CommitRequest("x\ny", "")), "Control in summary");
		reject(() -> GitWorkspaceRepository.validatePath("lua/../../server.properties"), "Traversal");
		reject(() -> GitWorkspaceRepository.validatePath("datapacks/test/../other/pack.mcmeta"), "Pack traversal");
		reject(() -> GitWorkspaceRepository.validatePath("lua/-danger.sh"), "Executable outside Lua scope");
		reject(() -> GitWorkspaceRepository.validateSnapshot(Map.of("lua/Test.lua", bytes("1"), "lua/test.lua", bytes("2"))), "Case collision");
		reject(() -> GitWorkspaceRepository.validateSnapshot(Map.of("datapacks/test/data/test/a.json", bytes("{}"),
				"datapacks/test/data/test/a.json/b.json", bytes("{}"))), "File-directory collision");
		Path fixture = Files.createTempDirectory("mc-lua-git-smoke-").toAbsolutePath().normalize();
		try { verifyVersions(fixture); verifyRemote(fixture); }
		finally {
			// Only this test's newly allocated, exact temporary fixture is cleaned up.
			if (!fixture.getFileName().toString().startsWith("mc-lua-git-smoke-")) throw new AssertionError("Unsafe fixture cleanup");
			try (var paths = Files.walk(fixture)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
				if (dos != null) dos.setReadOnly(false);
				Files.delete(path);
			} }
		}
		System.out.println("Git workspace smoke passed: " + checks + " checks; local history, binary snapshots, restore backups, pagination, remote upload and conflicts (no GitHub access)");
	}

	private static void verifyVersions(Path fixture) throws Exception {
		Path foreign = fixture.resolve("foreign-worktree");
		write(foreign.resolve(".git"), "gitdir: " + fixture.resolve("outside.git"));
		reject(() -> new GitWorkspaceRepository(foreign).initialize("main"), "Foreign Git pointer rejected");
		Path scripts = fixture.resolve("scripts"), packs = fixture.resolve("datapacks");
		write(scripts.resolve("sub/main.lua"), "print('first')\n");
		write(packs.resolve("mc_lua_ide_edit_test/pack.mcmeta"), "{}\n");
		write(packs.resolve("mc_lua_ide_edit_test/data/test/function/main.mcfunction"), "say first\n");
		Path nbt = packs.resolve("mc_lua_ide_edit_test/data/test/structure/test.nbt");
		Files.createDirectories(nbt.getParent()); Files.write(nbt, new byte[]{0, 1, (byte)255, 7});
		write(packs.resolve("other/pack.mcmeta"), "untouched");
		write(scripts.resolve("notes.txt"), "untouched");
		var files = new GitWorkspaceFiles(scripts, packs);
		var repo = new GitWorkspaceRepository(fixture.resolve("local")); repo.initialize("main");
		check(repo.history(0, "main", "").commits().isEmpty(), "Empty history");
		var first = files.capture(); check(first.size() == 4, "Only managed files exported, including binary NBT");
		String sha1 = repo.commit(first, "第一个版本\n\n详细说明", false);
		write(scripts.resolve("sub/main.lua"), "print('second')\n");
		write(scripts.resolve("new.lua"), "print('new')\n"); Files.delete(nbt);
		var second = files.capture(); String sha2 = repo.commit(second, "第二个版本", false);
		check(!sha1.equals(sha2), "Different version hashes");
		check(repo.commit(second, "No change", false).isEmpty(), "No duplicate unchanged version");
		var detail = repo.detail(sha2, "lua/sub/main.lua");
		check(detail.diff().contains("-print('first')") && detail.diff().contains("+print('second')"), "Readable per-file diff");
		check(detail.source().equals("print('second')\n"), "Historical code preview");
		check(detail.files().stream().anyMatch(f -> f.status().equals("D") && f.path().endsWith("test.nbt")), "Deleted files shown");
		check(repo.detail(sha1, "lua/sub/main.lua").message().contains("详细说明"), "Description retained");
		check(repo.history(0, "main", "").commits().getFirst().sha().equals(sha2), "Newest commit first");
		var desired = repo.snapshot(sha1); String backup = repo.commit(second, "恢复前自动备份", true);
		files.restore(desired, second); String restored = repo.commit(desired, "恢复到第一个版本", true);
		check(Files.readString(scripts.resolve("sub/main.lua")).contains("first"), "Saved code restored");
		check(!Files.exists(scripts.resolve("new.lua")), "Newer file removed on restore");
		check(Arrays.equals(Files.readAllBytes(nbt), first.get("datapacks/test/data/test/structure/test.nbt")), "Binary NBT restored byte-for-byte");
		check(Files.readString(packs.resolve("other/pack.mcmeta")).equals("untouched"), "Other packs preserved");
		check(Files.readString(scripts.resolve("notes.txt")).equals("untouched"), "Unsupported files preserved");
		check(repo.snapshot(backup).containsKey("lua/new.lua"), "Backup can recover newer files");
		check(!restored.equals(sha1) && repo.history(0, "main", "").commits().size() == 4, "Restore appends history, never rewinds");
		reject(() -> repo.snapshot("HEAD~1"), "Untrusted revision expression");
		reject(() -> files.restore(Map.of("lua/../../outside.lua", bytes("x")), first), "Restore escape");
		Path blocked = scripts.resolve("blocked.lua"); Files.createDirectory(blocked);
		reject(() -> files.restore(Map.of("lua/blocked.lua", bytes("x")), first), "Directory target preflight");
		check(Files.readString(scripts.resolve("sub/main.lua")).contains("first"), "Preflight failure leaves code unchanged");
		Files.delete(blocked);
		write(scripts.resolve("blocked"), "keep this non-Lua file");
		reject(() -> files.restore(Map.of("lua/sub/main.lua", bytes("changed"), "lua/blocked/main.lua", bytes("new")), desired), "Parent-file preflight");
		check(Files.readString(scripts.resolve("sub/main.lua")).contains("first"), "All destinations preflighted before writes");
		files.restore(Map.of(), desired);
		check(!Files.exists(nbt) && !Files.exists(packs.resolve("mc_lua_ide_edit_test")), "Restore empty version removes owned pack cleanly");
		check(Files.readString(packs.resolve("other/pack.mcmeta")).equals("untouched"), "Empty restore still preserves other packs");
		files.restore(desired, Map.of());
		for (int i = 0; i < 28; i++) repo.commit(Map.of("lua/page.lua", bytes("print(" + i + ")")), "页码 " + i, false);
		var page = repo.history(0, "main", "");
		check(page.commits().size() == 30 && page.more(), "History pagination bound");
		check(repo.history(30, "main", "").commits().size() == 2 && !repo.history(30, "main", "").more(), "Older history page");
		check(new GitWorkspaceRepository(fixture.resolve("local")).head().equals(repo.head()), "History survives reopening");
		String huge = "print('many')\n".repeat(15_000);
		String largeSha = repo.commit(Map.of("lua/large.lua", bytes(huge)), "长代码", false);
		var preview = repo.detail(largeSha, "lua/large.lua");
		check(preview.truncated() && preview.source().length() < 81_000, "Large preview bounded");
		check(Arrays.equals(repo.snapshot(largeSha).get("lua/large.lua"), bytes(huge)), "Full large file recoverable");
		String escaped = repo.commit(Map.of("lua/escaped.lua", bytes("\u0001".repeat(80_000))), "特殊字符预览", false);
		var escapedPreview = repo.detail(escaped, "lua/escaped.lua");
		check(escapedPreview.truncated() && LuaGit.JSON.toJson(escapedPreview).length() <= wiki.ica.xiaomiao.mcluaide.network.LuaProtocol.MAX_RESPONSE_CHARS,
				"Escaped source cannot overflow network response");
		// A malicious symbolic-link entry in Git must be rejected even on Windows without symlink privileges.
		String object = git(fixture.resolve("local"), "rev-parse", largeSha + ":mc_lua_ide/lua/large.lua");
		git(fixture.resolve("local"), "update-index", "--add", "--cacheinfo", "120000," + object + ",mc_lua_ide/lua/evil.lua");
		git(fixture.resolve("local"), "-c", "user.name=Smoke", "-c", "user.email=smoke@example.invalid", "commit", "--no-gpg-sign", "-m", "unsafe tree");
		reject(() -> repo.snapshot(repo.head()), "Symbolic link version rejected");
	}

	private static void verifyRemote(Path fixture) throws Exception {
		Path bare = fixture.resolve("remote.git"); Files.createDirectories(bare);
		git(bare, "init", "--bare", "--initial-branch=main");
		String url = bare.toUri().toString();
		var repo = new GitWorkspaceRepository(fixture.resolve("github-cache")); repo.initialize("main");
		check(repo.synchronize(url, "main").contains("尚不存在"), "Empty remote branch");
		String first = repo.commit(Map.of("lua/main.lua", bytes("print(1)")), "上传 1", false); repo.push("main");
		check(git(bare, "rev-parse", "main").equals(first), "Upload arrives at remote");
		check(repo.history(0, "main", "").commits().getFirst().published(), "Uploaded badge");
		// Simulate another author adding an unrelated root file; our scoped commit preserves it.
		var other = new GitWorkspaceRepository(fixture.resolve("other-cache")); other.initialize("main"); other.synchronize(url, "main");
		write(fixture.resolve("other-cache/README.md"), "keep this file");
		git(fixture.resolve("other-cache"), "add", "README.md");
		git(fixture.resolve("other-cache"), "-c", "user.name=Smoke", "-c", "user.email=smoke@example.invalid", "commit", "--no-gpg-sign", "-m", "other file");
		other.push("main"); repo.synchronize(url, "main");
		repo.commit(Map.of("lua/main.lua", bytes("print(2)")), "上传 2", false); repo.push("main");
		check(git(bare, "show", "main:README.md").equals("keep this file"), "Other repository files preserved");
		other.synchronize(url, "main");
		String pending = repo.commit(Map.of("lua/main.lua", bytes("print(3)")), "待上传", false);
		check(!repo.history(0, "main", "").commits().getFirst().published(), "Pending commit visible");
		other.commit(Map.of("lua/main.lua", bytes("print(4)")), "远端修改", false); other.push("main");
		reject(() -> repo.synchronize(url, "main"), "Divergence refuses overwrite");
		reject(() -> repo.push("main"), "Non-fast-forward upload rejected");
		check(repo.head().equals(pending), "Failed push preserves pending commit");
		check(repo.refresh(url, "main").contains("缓存历史"), "Cached history readable despite divergence");
		check(git(bare, "show", "main:mc_lua_ide/lua/main.lua").equals("print(4)"), "Remote code not overwritten");
	}

	@FunctionalInterface private interface Checked { void run() throws Exception; }
	private static void reject(Checked action, String message) throws Exception { try { action.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError(message); }
	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private static void write(Path path, String text) throws IOException { Files.createDirectories(path.getParent()); Files.writeString(path, text, StandardCharsets.UTF_8); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
	private static String git(Path cwd, String... args) throws Exception {
		var command = new java.util.ArrayList<>(List.of("git", "-c", "core.autocrlf=false")); command.addAll(List.of(args));
		var process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		if (process.waitFor() != 0) throw new IOException("Fixture Git failed: " + output); return output;
	}

	private static void rejectRepository(String value) throws Exception {
		try { GitWorkspaceService.validateRepository(value); }
		catch (IOException expected) { return; }
		throw new AssertionError("Unsafe Git repository accepted: " + value);
	}

	private static void rejectBranch(String value) throws Exception {
		try { GitWorkspaceService.validateBranch(value); }
		catch (IOException expected) { return; }
		throw new AssertionError("Unsafe Git branch accepted: " + value);
	}
}
