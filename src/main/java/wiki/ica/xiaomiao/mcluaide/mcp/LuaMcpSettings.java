package wiki.ica.xiaomiao.mcluaide.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** Secrets stay in local config/admin replies, never in MCP call logs. Both roles start disabled. */
public record LuaMcpSettings(boolean enabled, int port, boolean allowWrite, boolean allowExecution,
                             String token, String ownerUuid, String scope, boolean allowRemote) {
    /** Compatibility constructor: listening stays on this computer only. */
    public LuaMcpSettings(boolean enabled, int port, boolean allowWrite, boolean allowExecution, String token, String ownerUuid, String scope) {
        this(enabled, port, allowWrite, allowExecution, token, ownerUuid, scope, false);
    }
    public LuaMcpSettings {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("端口必须在 1024～65535 之间");
        token = token == null || !token.matches("[A-Za-z0-9_-]{43}") ? newToken() : token;
        ownerUuid = ownerUuid == null ? "" : ownerUuid; scope = scope == null ? "" : scope;
    }
    public static LuaMcpSettings defaults(boolean server) { return new LuaMcpSettings(false, server ? 8766 : 8765, false, false, null, "", "", false); }
    public static String newToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public String url() { return "http://127.0.0.1:" + port + "/mcp"; }
    public LuaMcpSettings update(boolean enabled, int port, boolean write, boolean execution, String owner, String scope, boolean rotate) {
        return update(enabled, port, write, execution, owner, scope, rotate, allowRemote);
    }
    /** {@code remote} lets devices with any IP address connect (the access token still applies); otherwise only this computer can. */
    public LuaMcpSettings update(boolean enabled, int port, boolean write, boolean execution, String owner, String scope, boolean rotate, boolean remote) {
        return new LuaMcpSettings(enabled, port, write, execution, rotate || !ownerUuid.equals(owner) || !this.scope.equals(scope) ? newToken() : token, owner, scope, remote);
    }
    public static LuaMcpSettings load(Path path, boolean server) {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(path)) try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
            return new LuaMcpSettings(Boolean.parseBoolean(p.getProperty("enabled", "false")),
                    Integer.parseInt(p.getProperty("port", server ? "8766" : "8765")),
                    Boolean.parseBoolean(p.getProperty("allowWrite", "false")), Boolean.parseBoolean(p.getProperty("allowExecution", "false")),
                    p.getProperty("token"), p.getProperty("ownerUuid", ""), p.getProperty("scope", ""), Boolean.parseBoolean(p.getProperty("allowRemote", "false")));
        } catch (Exception invalid) { return defaults(server); }
    }
    public void save(Path path) throws java.io.IOException {
        Files.createDirectories(path.getParent()); Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled)); p.setProperty("port", Integer.toString(port));
        p.setProperty("allowWrite", Boolean.toString(allowWrite)); p.setProperty("allowExecution", Boolean.toString(allowExecution));
        p.setProperty("token", token); p.setProperty("ownerUuid", ownerUuid); p.setProperty("scope", scope); p.setProperty("allowRemote", Boolean.toString(allowRemote));
        Path temp = Files.createTempFile(path.getParent(), "mcp-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "MC Lua IDE MCP - contains a private access token"); }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
