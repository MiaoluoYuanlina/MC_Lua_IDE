package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip snapshots of the server's Lua script folder in {@code config/mc_lua_ide/snapshots}: taken automatically every
 * {@code intervalMinutes} when something changed, on request from the IDE, and before every restore. The newest {@code keep}
 * automatic snapshots are kept (manual and pre-restore ones count too). Settings: {@code config/mc_lua_ide/snapshots.properties}.
 * This is independent of the optional Git integration.
 */
public final class LuaWorkspaceSnapshots {
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
	private static final long MAX_SNAPSHOT_BYTES = 256L * 1024 * 1024;
	private static final int MAX_ENTRIES = 20_000;
	private static volatile Path testRoot;
	private static ScheduledExecutorService scheduler;
	private static ScheduledFuture<?> task;
	private static String lastFingerprint = "";

	/** Settings, re-read each time a snapshot is due. */
	record Settings(boolean enabled, int intervalMinutes, int keep) {
		static Settings defaults() { return new Settings(true, 30, 20); }
	}

	private LuaWorkspaceSnapshots() { }

	/** Smoke tests point the scripts folder and the snapshot folder at a scratch directory. */
	public static void useDirectoryForTests(Path root) { testRoot = root; }
	private static Path base() { return testRoot != null ? testRoot : FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide"); }
	static Path scripts() { return base().resolve("scripts"); }
	static Path folder() { return base().resolve("snapshots"); }

	static Settings settings() {
		Path file = base().resolve("snapshots.properties");
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, """
						# MC Lua IDE: zip snapshots of config/mc_lua_ide/scripts in config/mc_lua_ide/snapshots.
						enabled=true
						# Minutes between automatic snapshots (only taken when a script changed).
						intervalMinutes=30
						# How many snapshots to keep; older ones are deleted.
						keep=20
						""", StandardCharsets.UTF_8);
				return Settings.defaults();
			}
			Properties properties = new Properties();
			try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
			return new Settings(Boolean.parseBoolean(properties.getProperty("enabled", "true").strip()),
					Math.clamp(Integer.parseInt(properties.getProperty("intervalMinutes", "30").strip()), 5, 24 * 60),
					Math.clamp(Integer.parseInt(properties.getProperty("keep", "20").strip()), 1, 500));
		} catch (IOException | NumberFormatException error) {
			return Settings.defaults();
		}
	}

	/** Starts the automatic snapshots (server start). */
	public static synchronized void start() {
		stop();
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "mc-lua-ide-snapshots"); thread.setDaemon(true); return thread;
		});
		int minutes = settings().intervalMinutes();
		task = scheduler.scheduleWithFixedDelay(() -> {
			try { if (settings().enabled()) create("auto", false); }
			catch (Throwable error) { McLuaIdeMod.LOGGER.warn("Automatic script snapshot failed", error); }
		}, minutes, minutes, TimeUnit.MINUTES);
	}

	public static synchronized void stop() {
		if (task != null) task.cancel(false);
		if (scheduler != null) scheduler.shutdownNow();
		task = null; scheduler = null;
	}

	/** Lists snapshots, newest first: {"snapshots":[{"name","bytes","kind"}],"intervalMinutes","keep","enabled"}. */
	static String list() throws IOException {
		JsonArray array = new JsonArray();
		for (Path zip : snapshots()) {
			JsonObject item = new JsonObject();
			String name = zip.getFileName().toString();
			item.addProperty("name", name);
			item.addProperty("bytes", Files.size(zip));
			item.addProperty("kind", name.contains("-manual") ? "manual" : name.contains("-before-restore") ? "before-restore" : "auto");
			array.add(item);
		}
		Settings settings = settings();
		JsonObject reply = new JsonObject();
		reply.add("snapshots", array);
		reply.addProperty("enabled", settings.enabled()); reply.addProperty("intervalMinutes", settings.intervalMinutes()); reply.addProperty("keep", settings.keep());
		return reply.toString();
	}

	private static List<Path> snapshots() throws IOException {
		if (!Files.isDirectory(folder())) return List.of();
		try (Stream<Path> files = Files.list(folder())) {
			return files.filter(path -> path.getFileName().toString().matches("scripts-[0-9_-]+(-[a-z-]+)?\\.zip"))
					.sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed()).toList();
		}
	}

	/**
	 * Zips the script folder. {@code kind} is "auto", "manual" or "before-restore"; an automatic snapshot is skipped when nothing
	 * changed since the last one. Returns the file name, or "" when skipped.
	 */
	static synchronized String create(String kind, boolean force) throws IOException {
		Path source = scripts();
		Files.createDirectories(source);
		List<Path> files;
		try (Stream<Path> walk = Files.walk(source)) {
			files = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
		}
		if (files.size() > MAX_ENTRIES) throw new IOException("脚本目录文件过多，无法创建快照");
		String fingerprint = fingerprint(source, files);
		if (!force && fingerprint.equals(lastFingerprint)) return "";
		Files.createDirectories(folder());
		String stamp = LocalDateTime.now().format(NAME), suffix = kind.equals("auto") ? "" : "-" + kind;
		String name = "scripts-" + stamp + suffix + ".zip";
		for (int n = 2; Files.exists(folder().resolve(name)); n++) name = "scripts-" + stamp + "-" + n + suffix + ".zip";
		Path target = folder().resolve(name), temporary = folder().resolve(name + ".tmp");
		long total = 0;
		try (OutputStream out = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Path file : files) {
				total += Files.size(file);
				if (total > MAX_SNAPSHOT_BYTES) throw new IOException("脚本目录超过 256 MB，无法创建快照");
				zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		} catch (IOException error) {
			Files.deleteIfExists(temporary);
			throw error;
		}
		Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		lastFingerprint = fingerprint;
		prune(settings().keep());
		return name;
	}

	private static void prune(int keep) throws IOException {
		List<Path> all = snapshots();
		for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
	}

	private static String fingerprint(Path root, List<Path> files) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Path file : files) {
				digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
				digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
				digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	/**
	 * Replaces the script folder with a snapshot. The current scripts are snapshotted first ("before-restore"), so a restore can
	 * itself be undone. Entries that would leave the folder are refused.
	 */
	static synchronized String restore(String name) throws IOException {
		if (name == null || !name.matches("scripts-[0-9_-]+(-[a-z-]+)?\\.zip")) throw new IllegalArgumentException("快照名称无效");
		Path zipFile = folder().resolve(name);
		if (!Files.isRegularFile(zipFile)) throw new IOException("快照不存在：" + name);
		Path source = scripts(), staging = base().resolve("scripts.restoring");
		deleteTree(staging);
		Files.createDirectories(staging);
		int entries = 0;
		try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				if (++entries > MAX_ENTRIES) throw new IOException("快照条目过多");
				Path target = staging.resolve(entry.getName()).normalize();
				if (!target.startsWith(staging)) throw new IOException("快照包含无效路径：" + entry.getName());
				if (entry.isDirectory()) { Files.createDirectories(target); continue; }
				Files.createDirectories(target.getParent());
				Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException error) {
			deleteTree(staging);
			throw error;
		}
		// Taken only now: pruning old snapshots could otherwise delete the one being restored before it was read.
		String backup = create("before-restore", true);
		Path old = base().resolve("scripts.replaced");
		deleteTree(old);
		if (Files.exists(source)) Files.move(source, old);
		Files.move(staging, source);
		deleteTree(old);
		lastFingerprint = "";
		return backup;
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(root)) { paths = new ArrayList<>(walk.toList()); }
		paths.sort(Comparator.reverseOrder());
		for (Path path : paths) Files.deleteIfExists(path);
	}
}
