package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.locale.Language;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Fixed-origin vanilla language downloads plus server-side mod assets; never uses client classes. */
final class LuaLanguageAssets {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final LinkedHashMap<String, Map<String, String>> CACHE = new LinkedHashMap<>(16, .75F, true);
    private static final Map<String, Long> FAILED = new LinkedHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger REVISION = new java.util.concurrent.atomic.AtomicInteger();
    private static int cachedRevision;
    static void invalidate() { REVISION.incrementAndGet(); }
    private static JsonObject index;
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/language-cache")
            .resolve(SharedConstants.getCurrentVersion().id()).toAbsolutePath().normalize();

    static String normalize(String raw) {
        String locale = raw.strip().replace('-', '_').toLowerCase(Locale.ROOT);
        if (!locale.matches("[a-z]{2,8}(?:_[a-z0-9]{2,8})?")) throw new org.luaj.vm2.LuaError("语言代码格式无效，例如 zh_cn、en_us、ja_jp、tok");
        return locale;
    }

    /** Called only on a Lua worker. Do not hold a server-thread future while loading language assets. */
    static synchronized Map<String, String> load(String locale) {
        int revision = REVISION.get();
        if (cachedRevision != revision) { CACHE.clear(); FAILED.clear(); cachedRevision = revision; }
        Map<String, String> cached = CACHE.get(locale);
        if (cached != null) return cached;
        if (FAILED.getOrDefault(locale, 0L) > System.nanoTime())
            throw new LuaRecoverableError("指定语言 " + locale + " 资源尚不可用；已暂缓重试，请检查官方资源网络或本地语言文件");
        try {
            Map<String, String> result = new LinkedHashMap<>();
            // The server jar has en_us. Client-only locales live in the official asset index.
            if (locale.equals("en_us")) {
                try (InputStream stream = Language.class.getResourceAsStream("/assets/minecraft/lang/en_us.json")) {
                    if (stream != null) merge(result, stream.readNBytes(MAX_BYTES + 1));
                }
            }
            Path manual = ROOT.getParent().getParent().resolve("lang/" + locale + ".json");
            boolean manualPresent = Files.isRegularFile(manual, LinkOption.NOFOLLOW_LINKS);
            Exception officialFailure = null;
            // A local override allows fully offline servers; no provider/credentials are involved.
            if (!locale.equals("en_us") && !manualPresent) {
                try {
                    Path official = ROOT.resolve(locale + ".json");
                    if (Files.isRegularFile(official, LinkOption.NOFOLLOW_LINKS)) merge(result, read(official));
                    else {
                        JsonObject entry = assetIndex().getAsJsonObject("minecraft/lang/" + locale + ".json");
                        if (entry == null) throw new IOException("当前游戏版本没有该原版语言资源；可放入本地 lang/" + locale + ".json");
                        String hash = entry.get("hash").getAsString();
                        if (!hash.matches("[a-f0-9]{40}")) throw new IOException("官方语言文件摘要无效");
                        byte[] bytes = fetch("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash);
                        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).equals(hash))
                            throw new IOException("官方语言文件摘要不匹配");
                        merge(result, bytes); save(official, bytes);
                    }
                } catch (InterruptedException error) { throw error; }
                catch (Exception error) { officialFailure = error; }
            }
            // A mod may contribute keys to any namespace, not only to its own mod id.
            for (var mod : FabricLoader.getInstance().getAllMods()) {
                for (Path root : mod.getRootPaths()) {
                    Path assets = root.resolve("assets");
                    if (!Files.isDirectory(assets)) continue;
                    try (var namespaces = Files.list(assets)) {
                        for (Path namespace : namespaces.sorted().toList()) {
                            Path file = namespace.resolve("lang/" + locale + ".json");
                            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) merge(result, read(file));
                        }
                    }
                }
            }
            if (manualPresent) merge(result, read(manual));
            if (result.isEmpty()) throw new IOException(officialFailure == null ? "语言文件为空或未安装" : officialFailure.getMessage());
            Map<String, String> immutable = Collections.unmodifiableMap(result);
            CACHE.put(locale, immutable); FAILED.remove(locale);
            while (CACHE.size() > 16) CACHE.remove(CACHE.keySet().iterator().next());
            return immutable;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new org.luaj.vm2.LuaError("脚本已停止");
        } catch (Exception error) {
            FAILED.put(locale, System.nanoTime() + Duration.ofSeconds(30).toNanos());
            while (FAILED.size() > 64) FAILED.remove(FAILED.keySet().iterator().next());
            throw new LuaRecoverableError("读取指定语言 " + locale + " 失败：" + error.getMessage()
                    + "；可在 config/mc_lua_ide/lang/" + locale + ".json 提供本地语言文件");
        }
    }

    private static JsonObject assetIndex() throws Exception {
        if (index != null) return index;
        Path file = ROOT.resolve("asset-index.json");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return index = JsonParser.parseString(new String(read(file), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("objects");
        JsonObject manifest = json(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        String version = SharedConstants.getCurrentVersion().id(); String url = null;
        for (JsonElement entry : manifest.getAsJsonArray("versions")) {
            JsonObject object = entry.getAsJsonObject();
            if (version.equals(object.get("id").getAsString())) { url = object.get("url").getAsString(); break; }
        }
        if (url == null) throw new IOException("官方清单找不到当前游戏版本 " + version);
        JsonObject reference = json(fetch(url)).getAsJsonObject("assetIndex");
        byte[] bytes = fetch(reference.get("url").getAsString());
        String expected = reference.get("sha1").getAsString();
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).equals(expected)) throw new IOException("官方资源索引摘要不匹配");
        JsonObject parsed = json(bytes).getAsJsonObject("objects");
        save(file, bytes); return index = parsed;
    }
    private static JsonObject json(byte[] bytes) { return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static byte[] fetch(String url) throws Exception {
        URI uri = URI.create(url);
        if (!uri.getScheme().equals("https") || uri.getUserInfo() != null || uri.getPort() != -1
                || !Set.of("piston-meta.mojang.com", "resources.download.minecraft.net").contains(uri.getHost()))
            throw new IOException("拒绝非官方语言资源地址");
        var response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("官方资源 HTTP " + response.statusCode());
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("语言资源超过 8 MiB");
            return bytes;
        }
    }
    private static byte[] read(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("语言文件超过 8 MiB");
            return bytes;
        }
    }
    private static void save(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(ROOT);
        if (Files.isSymbolicLink(ROOT) || Files.isSymbolicLink(file)) throw new IOException("语言缓存路径不允许符号链接");
        Path temporary = Files.createTempFile(ROOT, "language-", ".tmp");
        try { Files.write(temporary, bytes); Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static void merge(Map<String, String> target, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("语言文件超过 8 MiB");
        Language.loadFromJson(new ByteArrayInputStream(bytes), (key, value) -> {
            if (target.size() > 200_000 || key.length() > 2048 || value.length() > 32768)
                throw new IllegalArgumentException("语言文件词条过大");
            target.put(key, value);
        });
    }
}
