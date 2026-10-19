package wiki.ica.xiaomiao.mcluaide.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Confined saved-file snapshots, including binary datapack assets. Unrelated packs are never touched. */
final class GitWorkspaceFiles {
	private final Path scripts, datapacks;
	GitWorkspaceFiles(Path scripts, Path datapacks) {
		this.scripts = scripts.toAbsolutePath().normalize();
		this.datapacks = datapacks.toAbsolutePath().normalize();
	}

	Map<String, byte[]> capture() throws IOException {
		Map<String, byte[]> snapshot = new LinkedHashMap<>();
		long[] total = {0};
		readTree(scripts, "lua/", snapshot, total);
		GitWorkspaceRepository.assertSafe(datapacks, datapacks);
		if (Files.isDirectory(datapacks, LinkOption.NOFOLLOW_LINKS)) try (var packs = Files.list(datapacks)) {
			for (Path pack : packs.toList()) {
				String name = pack.getFileName().toString();
				if (!name.startsWith("mc_lua_ide_edit_")) continue;
				if (Files.isSymbolicLink(pack)) throw new IOException("IDE 数据包包含符号链接");
				if (Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS))
					readTree(pack, "datapacks/" + name.substring("mc_lua_ide_edit_".length()) + "/", snapshot, total);
			}
		}
		GitWorkspaceRepository.validateSnapshot(snapshot);
		return snapshot;
	}

	private void readTree(Path root, String prefix, Map<String, byte[]> snapshot, long[] total) throws IOException {
		GitWorkspaceRepository.assertSafe(root, root);
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
		try (var paths = Files.walk(root)) {
			var iterator = paths.iterator();
			while (iterator.hasNext()) {
				Path path = iterator.next();
				if (Files.isSymbolicLink(path)) throw new IOException("工作区包含符号链接，拒绝提交或恢复");
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String name = prefix + root.relativize(path).toString().replace('\\', '/');
				try { GitWorkspaceRepository.validatePath(name); }
				catch (IOException ignored) { continue; }
				long size = Files.size(path); total[0] += size;
				if (size > GitWorkspaceRepository.MAX_FILE_BYTES || total[0] > GitWorkspaceRepository.MAX_TOTAL_BYTES
						|| snapshot.size() >= GitWorkspaceRepository.MAX_FILES) throw new IOException("工作区超过 Git 版本容量限制");
				try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
					byte[] bytes = input.readNBytes(GitWorkspaceRepository.MAX_FILE_BYTES + 1);
					if (bytes.length > GitWorkspaceRepository.MAX_FILE_BYTES) throw new IOException("工作区文件过大");
					snapshot.put(name, bytes);
				}
			}
		}
	}

	/** Validate every destination before changing anything; roll back writes if any operation fails. */
	void restore(Map<String, byte[]> desired, Map<String, byte[]> before) throws IOException {
		preflight(desired); preflight(before);
		try { apply(desired, before); }
		catch (IOException failure) {
			try { apply(before, desired); }
			catch (IOException rollback) { failure.addSuppressed(rollback); }
			throw new IOException("恢复失败，已尝试回滚；恢复前版本仍保留在本地 Git 历史中：" + failure.getMessage(), failure);
		}
	}

	private void preflight(Map<String, byte[]> files) throws IOException {
		GitWorkspaceRepository.validateSnapshot(files);
		for (String name : files.keySet()) {
			Path path = path(name);
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("恢复目标不是普通文件：" + name);
			for (Path parent = path.getParent(); parent != null && parent.startsWith(base(name)); parent = parent.getParent())
				if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("恢复目标的上级不是目录：" + name);
		}
	}

	private void apply(Map<String, byte[]> desired, Map<String, byte[]> old) throws IOException {
		for (var entry : desired.entrySet()) {
			Path path = path(entry.getKey());
			GitWorkspaceRepository.atomicWrite(base(entry.getKey()), path, entry.getValue());
		}
		for (String name : old.keySet()) if (!desired.containsKey(name)) {
			Path path = path(name);
			Files.deleteIfExists(path);
			Path limit = base(name);
			for (Path parent = path.getParent(); parent != null && !parent.equals(limit); parent = parent.getParent()) {
				if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) break;
				try (var children = Files.list(parent)) { if (children.findAny().isPresent()) break; }
				Files.delete(parent);
			}
		}
	}

	private Path base(String name) { return name.startsWith("lua/") ? scripts : datapacks; }
	private Path path(String name) throws IOException {
		GitWorkspaceRepository.validatePath(name);
		Path path;
		if (name.startsWith("lua/")) path = scripts.resolve(name.substring(4));
		else {
			String relative = name.substring(10);
			int slash = relative.indexOf('/');
			path = datapacks.resolve("mc_lua_ide_edit_" + relative.substring(0, slash)).resolve(relative.substring(slash + 1));
		}
		path = path.normalize(); GitWorkspaceRepository.assertSafe(base(name), path);
		return path;
	}
}
