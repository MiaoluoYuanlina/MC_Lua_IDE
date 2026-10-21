package wiki.ica.xiaomiao.mcluaide.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports and imports this computer's IDE settings: appearance, shortcuts, layouts, per-server sessions (breakpoints, watches,
 * open files), UI templates, background images and extension manifests. Never included: scripts, server settings, and anything
 * that can hold a secret (MCP and GitHub tokens). An import first saves the current settings, so it can be undone.
 */
final class LuaSettingsBackup {
	static final Set<String> FILES = Set.of("personalization.properties", "debug-keys.properties", "debug-hud-layout.properties",
			"debug-view.properties", "ide-layout.properties", "window-geometry.properties", "insert-picker.properties");
	static final Set<String> FOLDERS = Set.of("sessions", "ui_templates", "backgrounds", "extensions");
	private static final long MAX_BYTES = 64L * 1024 * 1024;
	private static final int MAX_ENTRIES = 5000;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private LuaSettingsBackup() { }

	static String stamp() { return LocalDateTime.now().format(STAMP); }

	/** Whether a path inside the settings folder (with '/' separators) belongs in a backup. */
	static boolean included(String relative) {
		if (relative.isEmpty() || relative.contains("..") || relative.startsWith("/") || relative.contains("\\")) return false;
		int slash = relative.indexOf('/');
		if (slash < 0) return FILES.contains(relative);
		return FOLDERS.contains(relative.substring(0, slash));
	}

	/** Writes the settings of {@code root} (config/mc_lua_ide) into {@code zipFile}; returns the number of files. */
	static int export(Path root, Path zipFile) throws IOException {
		List<Path> files = new ArrayList<>();
		if (Files.isDirectory(root)) try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.toList()) {
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				if (included(root.relativize(path).toString().replace('\\', '/'))) files.add(path);
			}
		}
		if (files.size() > MAX_ENTRIES) throw new IOException("设置文件过多");
		Files.createDirectories(zipFile.toAbsolutePath().getParent());
		long total = 0;
		try (OutputStream out = Files.newOutputStream(zipFile); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.setComment("MC Lua IDE settings");
			for (Path file : files) {
				total += Files.size(file);
				if (total > MAX_BYTES) throw new IOException("设置超过 64 MB（背景图片过大？）");
				zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
		return files.size();
	}

	/**
	 * Restores settings from {@code zipFile} into {@code root}. The current settings are exported to {@code undoFile} first.
	 * Entries outside the allowed files and folders are skipped; returns the number of files written.
	 */
	static int restore(Path root, Path zipFile, Path undoFile) throws IOException {
		// Read and check everything before touching the current settings.
		List<String> names = new ArrayList<>();
		List<byte[]> contents = new ArrayList<>();
		long total = 0;
		try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				if (entry.isDirectory()) continue;
				if (names.size() >= MAX_ENTRIES) throw new IOException("设置包条目过多");
				String name = entry.getName();
				if (!included(name) || !root.resolve(name).normalize().startsWith(root.normalize())) continue;
				byte[] data = zip.readNBytes((int) Math.min(MAX_BYTES + 1, Integer.MAX_VALUE - 8));
				total += data.length;
				if (total > MAX_BYTES) throw new IOException("设置包超过 64 MB");
				names.add(name); contents.add(data);
			}
		}
		if (names.isEmpty()) throw new IOException("这不是 MC Lua IDE 的设置包（没有可导入的设置）");
		export(root, undoFile);
		for (int i = 0; i < names.size(); i++) {
			Path target = root.resolve(names.get(i)).normalize();
			Files.createDirectories(target.getParent());
			Path temporary = target.resolveSibling(target.getFileName() + ".importing");
			Files.write(temporary, contents.get(i));
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return names.size();
	}
}
