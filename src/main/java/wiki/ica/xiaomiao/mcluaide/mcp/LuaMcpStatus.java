package wiki.ica.xiaomiao.mcluaide.mcp;

import java.util.List;

/** Sent only through the existing OP-4 administrator protocol; contains the private connection token. */
public record LuaMcpStatus(LuaMcpSettings settings, boolean running, String problem, List<LuaMcpServer.Log> logs) {
    public String encode() { return LuaMcpServer.JSON.toJson(this); }
    public static LuaMcpStatus decode(String json) { return LuaMcpServer.JSON.fromJson(json, LuaMcpStatus.class); }
}
