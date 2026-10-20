package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Local administrator drafts, separate from originals and scoped to the session's world/server/player. */
final class LuaFileRecovery {
	private static final Map<Path, java.lang.ref.WeakReference<LuaFileRecovery>> OPEN_STORES = new HashMap<>();
	static synchronized LuaFileRecovery forSession(LuaIdeSession session) {
		var reference = OPEN_STORES.get(session.file);
		LuaFileRecovery existing = reference == null ? null : reference.get();
		if (existing != null) return existing; // immediate reopen also sees writes still in flight
		LuaFileRecovery store = new LuaFileRecovery(session);
		OPEN_STORES.entrySet().removeIf(e -> e.getValue().get() == null);
		OPEN_STORES.put(session.file, new java.lang.ref.WeakReference<>(store)); return store;
	}
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MC Lua IDE recovery"); thread.setDaemon(true); return thread;
	});
	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			WRITER.shutdown();
			try { WRITER.awaitTermination(5, TimeUnit.SECONDS); }
			catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
		}, "MC Lua IDE recovery shutdown"));
	}
	private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
	final Path directory;
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Map<String, Draft> pending = new LinkedHashMap<>(); // null means explicit discard
	private final Map<String, Draft> failed = new LinkedHashMap<>();
	private boolean writing;
	private volatile String failure = "";

	record Entry(String id, LuaIdeSession.Document document, long updated, boolean image) {
		String name() { return document.name() + (document.json() ? "" : ".lua"); }
		String type() { return document.resource() ? "资源包" : document.json() ? "数据包" : "Lua"; }
	}
	record Draft(Entry entry, String source, String savedSource, LuaPngLayerDocument.RecoveryImage image) { }

	LuaFileRecovery(LuaIdeSession session) {
		directory = session.file.getParent().getParent().resolve("recovery")
				.resolve(session.file.getFileName().toString().replace(".properties", ""));
		if (!Files.isDirectory(directory)) return;
		try (var files = Files.list(directory)) {
			for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".zip")).toList()) {
				try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
					if (Files.size(path) > MAX_ARCHIVE_BYTES) continue;
					Entry entry = readEntry(zip);
					if (path.equals(path(entry.id()))) entries.put(entry.id(), entry);
				} catch (Exception error) { McLuaIdeMod.LOGGER.warn("Ignored damaged IDE recovery {}", path, error); }
			}
		} catch (IOException error) { fail(error); }
	}
	synchronized List<Entry> entries() {
		return entries.values().stream().sorted(Comparator.comparingLong(Entry::updated).reversed()).toList();
	}
	String failure() { return failure; }
	void save(Draft draft) {
		validate(draft.entry());
		if (draft.entry().image() != (draft.image() != null)) throw new IllegalArgumentException("恢复图片与元信息不一致");
		if (draft.source().length() > LuaProtocol.MAX_SCRIPT_CHARS || draft.savedSource().length() > LuaProtocol.MAX_SCRIPT_CHARS)
			throw new IllegalArgumentException("恢复文本超过编辑器限制");
		synchronized (this) { entries.put(draft.entry().id(), draft.entry()); enqueue(draft.entry().id(), draft); }
	}
	void discard(String id) {
		path(id); // never accept a path from a document or UI
		synchronized (this) { entries.remove(id); enqueue(id, null); }
	}
	private void enqueue(String id, Draft draft) {
		failed.remove(id);
		pending.put(id, draft);
		if (!writing) { writing = true; WRITER.execute(this::drain); }
	}
	private void drain() {
		while (true) {
			Map<String, Draft> changes;
			synchronized (this) {
				if (pending.isEmpty()) { writing = false; return; }
				changes = new LinkedHashMap<>(pending); pending.clear();
			}
			for (var change : changes.entrySet()) try {
				if (change.getValue() == null) Files.deleteIfExists(path(change.getKey()));
				else write(change.getValue());
				synchronized (this) { failed.remove(change.getKey()); if (failed.isEmpty()) failure = ""; }
			} catch (Exception error) {
				synchronized (this) { if (!pending.containsKey(change.getKey())) failed.put(change.getKey(), change.getValue()); }
				fail(error);
			}
		}
	}
	synchronized void retryFailures() {
		Map<String, Draft> retry = new LinkedHashMap<>(failed);
		for (var entry : retry.entrySet()) enqueue(entry.getKey(), entry.getValue());
	}
	CompletableFuture<Void> flush() { return CompletableFuture.runAsync(() -> { }, WRITER); }
	CompletableFuture<Draft> read(String id) {
		return CompletableFuture.supplyAsync(() -> {
			try { return readNow(id); }
			catch (IOException error) { throw new CompletionException(error); }
		}, WRITER);
	}
	Draft readNow(String id) throws IOException {
		Path file = path(id);
		if (Files.size(file) > MAX_ARCHIVE_BYTES) throw new IOException("恢复文件过大");
		try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
			Entry entry = readEntry(zip);
			if (!entry.id().equals(id)) throw new IOException("恢复文件标识不一致");
			String source = new String(bytes(zip, "source.txt", LuaProtocol.MAX_SCRIPT_CHARS * 4), StandardCharsets.UTF_8);
			String saved = new String(bytes(zip, "saved.txt", LuaProtocol.MAX_SCRIPT_CHARS * 4), StandardCharsets.UTF_8);
			if (source.length() > LuaProtocol.MAX_SCRIPT_CHARS || saved.length() > LuaProtocol.MAX_SCRIPT_CHARS)
				throw new IOException("恢复文本超过编辑上限");
			LuaPngLayerDocument.RecoveryImage image = null;
			if (entry.image()) {
				Properties p = properties(zip);
				int count = Integer.parseInt(p.getProperty("layers", "0"));
				if (count < 1 || count > 16) throw new IOException("图层数量错误");
				List<LuaPngLayerDocument.Layer> layers = new ArrayList<>();
				for (int index = 0; index < count; index++) {
					byte[] encoded = bytes(zip, "layer_" + index + ".png", 8 * 1024 * 1024);
					// Validate dimensions BEFORE allocating the decoded image.
					try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
						var readers = ImageIO.getImageReaders(input);
						if (!readers.hasNext()) throw new IOException("无效的恢复图片");
						var reader = readers.next();
						try {
							reader.setInput(input);
							int w = reader.getWidth(0), h = reader.getHeight(0);
							if (w < 1 || h < 1 || w > 2048 || h > 2048 || (long) w * h > 1_048_576)
								throw new IOException("恢复图片尺寸超过画布限制");
							if (!layers.isEmpty() && (layers.getFirst().image().getWidth() != w || layers.getFirst().image().getHeight() != h))
								throw new IOException("恢复图层尺寸不一致");
							layers.add(new LuaPngLayerDocument.Layer(p.getProperty("layer." + index + ".name", "图层"),
									reader.read(0), Boolean.parseBoolean(p.getProperty("layer." + index + ".visible", "true")),
										LuaPngLayerDocument.parseOpacity(p.getProperty("layer." + index + ".opacity", "1"))));
						} finally { reader.dispose(); }
					}
				}
				image = new LuaPngLayerDocument.RecoveryImage(List.copyOf(layers),
						Math.clamp(Integer.parseInt(p.getProperty("selected", "0")), 0, count - 1));
			}
			return new Draft(entry, source, saved, image);
		} catch (IllegalArgumentException error) { throw new IOException("恢复数据格式错误", error); }
	}
	private Entry readEntry(ZipFile zip) throws IOException {
		Properties p = properties(zip);
		try {
			var doc = new LuaIdeSession.Document(p.getProperty("name", ""), Boolean.parseBoolean(p.getProperty("json")),
					Boolean.parseBoolean(p.getProperty("resource")), Math.max(0, Integer.parseInt(p.getProperty("cursor", "0"))),
					Double.parseDouble(p.getProperty("scroll", "0")));
			Entry entry = new Entry(p.getProperty("id", ""), doc, Long.parseLong(p.getProperty("updated", "0")),
					Boolean.parseBoolean(p.getProperty("image")));
			validate(entry); return entry;
		} catch (IllegalArgumentException error) { throw new IOException("无效的恢复文件元信息", error); }
	}
	private static Properties properties(ZipFile zip) throws IOException {
		Properties p = new Properties();
		p.load(new InputStreamReader(new ByteArrayInputStream(bytes(zip, "draft.properties", 64 * 1024)), StandardCharsets.UTF_8));
		return p;
	}
	private static byte[] bytes(ZipFile zip, String name, int limit) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null || entry.getSize() > limit) throw new IOException("恢复文件缺少内容或内容过大：" + name);
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] data = input.readNBytes(limit + 1);
			if (data.length > limit) throw new IOException("恢复内容超过大小限制");
			return data;
		}
	}
	private void write(Draft draft) throws IOException {
		Files.createDirectories(directory);
		Path temporary = Files.createTempFile(directory, "draft-", ".tmp");
		try {
			Entry e = draft.entry(); LuaIdeSession.Document d = e.document();
			Properties p = new Properties();
			p.setProperty("id", e.id()); p.setProperty("name", d.name()); p.setProperty("json", "" + d.json());
			p.setProperty("resource", "" + d.resource()); p.setProperty("cursor", "" + d.cursor()); p.setProperty("scroll", "" + d.scroll());
			p.setProperty("updated", "" + e.updated()); p.setProperty("image", "" + e.image());
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
				put(zip, "source.txt", draft.source().getBytes(StandardCharsets.UTF_8));
				put(zip, "saved.txt", draft.savedSource().getBytes(StandardCharsets.UTF_8));
				if (draft.image() != null) {
					p.setProperty("layers", "" + draft.image().layers().size()); p.setProperty("selected", "" + draft.image().selected());
					int index = 0;
					for (var layer : draft.image().layers()) {
						p.setProperty("layer." + index + ".name", layer.name()); p.setProperty("layer." + index + ".visible", "" + layer.visible()); p.setProperty("layer." + index + ".opacity", "" + layer.opacity());
						ByteArrayOutputStream output = new ByteArrayOutputStream();
						if (!ImageIO.write(layer.image(), "PNG", output)) throw new IOException("无法编码恢复图片");
						put(zip, "layer_" + index++ + ".png", output.toByteArray());
					}
				}
				StringWriter metadata = new StringWriter(); p.store(metadata, "MC Lua IDE unsaved recovery draft");
				put(zip, "draft.properties", metadata.toString().getBytes(StandardCharsets.UTF_8));
			}
			try { Files.move(temporary, path(e.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path(e.id()), StandardCopyOption.REPLACE_EXISTING); }
		} finally { Files.deleteIfExists(temporary); }
	}
	private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
		zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry();
	}
	private static void validate(Entry entry) {
		if (!entry.id().matches("[a-f0-9\\-]{36}") || !entry.document().valid()
				|| !Double.isFinite(entry.document().scroll()) || entry.document().scroll() < 0
				|| entry.image() && !(entry.document().resource() && entry.document().name().endsWith(".png")))
			throw new IllegalArgumentException("无效恢复文件标识");
	}
	private Path path(String id) {
		if (id == null || !id.matches("[a-f0-9\\-]{36}")) throw new IllegalArgumentException("无效恢复文件标识");
		return directory.resolve(id + ".zip");
	}
	private void fail(Exception error) {
		String message = "恢复草稿读写失败：" + error.getMessage();
		if (!message.equals(failure)) McLuaIdeMod.LOGGER.warn("Could not persist IDE recovery drafts in {}", directory, error);
		failure = message;
	}
}
