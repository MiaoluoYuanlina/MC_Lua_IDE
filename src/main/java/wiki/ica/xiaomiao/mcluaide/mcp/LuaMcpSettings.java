package wiki.ica.xiaomiao.mcluaide.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/**
 * Secrets stay in local config/admin replies, never in MCP call logs. Both roles start disabled.
 * <p>{@code authMode}: {@code generated} (a random token, the default), {@code custom} (the administrator's own password, sent as
 * the Bearer token) or {@code none} (no password: anybody who can reach the port can call MCP).
 */
public record LuaMcpSettings(boolean enabled, int port, boolean allowWrite, boolean allowExecution,
                             String token, String ownerUuid, String scope, boolean allowRemote, String authMode) {
    public static final String AUTH_GENERATED = "generated", AUTH_CUSTOM = "custom", AUTH_NONE = "none";
    /** Compatibility constructor: a generated token. */
    public LuaMcpSettings(boolean enabled, int port, boolean allowWrite, boolean allowExecution, String token, String ownerUuid, String scope, boolean allowRemote) {
        this(enabled, port, allowWrite, allowExecution, token, ownerUuid, scope, allowRemote, AUTH_GENERATED);
    }
    /** Compatibility constructor: listening stays on this computer only. */
    public LuaMcpSettings(boolean enabled, int port, boolean allowWrite, boolean allowExecution, String token, String ownerUuid, String scope) {
        this(enabled, port, allowWrite, allowExecution, token, ownerUuid, scope, false, AUTH_GENERATED);
    }
    public LuaMcpSettings {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("端口必须在 1024～65535 之间");
        authMode = AUTH_CUSTOM.equals(authMode) || AUTH_NONE.equals(authMode) ? authMode : AUTH_GENERATED;
        if (authMode.equals(AUTH_CUSTOM)) {
            if (!validPassword(token)) throw new IllegalArgumentException("密码须为 4～128 个可见 ASCII 字符（不含空格）");
        } else token = token == null || !token.matches("[A-Za-z0-9_-]{43}") ? newToken() : token;
        ownerUuid = ownerUuid == null ? "" : ownerUuid; scope = scope == null ? "" : scope;
    }
    public static boolean validPassword(String password) { return password != null && password.matches("[\\x21-\\x7E]{4,128}"); }
    public boolean passwordRequired() { return !authMode.equals(AUTH_NONE); }
    public static LuaMcpSettings defaults(boolean server) { return new LuaMcpSettings(false, server ? 8766 : 8765, false, false, null, "", "", false, AUTH_GENERATED); }
    public static String newToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public String url() { return "http://127.0.0.1:" + port + "/mcp"; }
    public LuaMcpSettings update(boolean enabled, int port, boolean write, boolean execution, String owner, String scope, boolean rotate) {
        return update(enabled, port, write, execution, owner, scope, rotate, allowRemote);
    }
    /** {@code remote} lets devices with any IP address connect (the access token still applies); otherwise only this computer can. */
    public LuaMcpSettings update(boolean enabled, int port, boolean write, boolean execution, String owner, String scope, boolean rotate, boolean remote) {
        boolean renew = !authMode.equals(AUTH_CUSTOM) && (rotate || !ownerUuid.equals(owner) || !this.scope.equals(scope));
        return new LuaMcpSettings(enabled, port, write, execution, renew ? newToken() : token, owner, scope, remote, authMode);
    }
    /** Switches the password mode: a random token, the administrator's own password (given as {@code password}) or no password. */
    public LuaMcpSettings withAuth(String mode, String password) {
        if (AUTH_CUSTOM.equals(mode)) {
            if ((password == null || password.isEmpty()) && authMode.equals(AUTH_CUSTOM)) return this; // keep the saved password
            if (!validPassword(password)) throw new IllegalArgumentException("密码须为 4～128 个可见 ASCII 字符（不含空格）");
            return new LuaMcpSettings(enabled, port, allowWrite, allowExecution, password, ownerUuid, scope, allowRemote, AUTH_CUSTOM);
        }
        if (AUTH_NONE.equals(mode)) return new LuaMcpSettings(enabled, port, allowWrite, allowExecution, token, ownerUuid, scope, allowRemote, AUTH_NONE);
        return new LuaMcpSettings(enabled, port, allowWrite, allowExecution, authMode.equals(AUTH_GENERATED) ? token : newToken(), ownerUuid, scope, allowRemote, AUTH_GENERATED);
    }
    public static LuaMcpSettings load(Path path, boolean server) {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(path)) try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
            return new LuaMcpSettings(Boolean.parseBoolean(p.getProperty("enabled", "false")),
                    Integer.parseInt(p.getProperty("port", server ? "8766" : "8765")),
                    Boolean.parseBoolean(p.getProperty("allowWrite", "false")), Boolean.parseBoolean(p.getProperty("allowExecution", "false")),
                    p.getProperty("token"), p.getProperty("ownerUuid", ""), p.getProperty("scope", ""), Boolean.parseBoolean(p.getProperty("allowRemote", "false")), p.getProperty("authMode", AUTH_GENERATED));
        } catch (Exception invalid) { return defaults(server); }
    }
    public void save(Path path) throws java.io.IOException {
        Files.createDirectories(path.getParent()); Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled)); p.setProperty("port", Integer.toString(port));
        p.setProperty("allowWrite", Boolean.toString(allowWrite)); p.setProperty("allowExecution", Boolean.toString(allowExecution));
        p.setProperty("token", token); p.setProperty("ownerUuid", ownerUuid); p.setProperty("scope", scope); p.setProperty("allowRemote", Boolean.toString(allowRemote)); p.setProperty("authMode", authMode);
        Path temp = Files.createTempFile(path.getParent(), "mcp-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "MC Lua IDE MCP - contains a private access token"); }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
