package wiki.ica.xiaomiao.mcluaide.mcp;

import com.google.gson.*;
import java.util.List;

public final class LuaMcpTools {
    public enum Permission { READ, WRITE, EXECUTE }
    public record Tool(String name, String description, Permission permission, JsonObject schema) {
        public JsonObject json() {
            JsonObject out = new JsonObject(); out.addProperty("name", name); out.addProperty("description", description);
            out.add("inputSchema", schema.deepCopy()); JsonObject hints = new JsonObject();
            hints.addProperty("readOnlyHint", permission == Permission.READ); hints.addProperty("destructiveHint", permission != Permission.READ);
            hints.addProperty("openWorldHint", true); out.add("annotations", hints); return out;
        }
    }
    public static final List<Tool> ALL = List.of(
            tool("mc_status", "当前游戏连接、运行脚本和 MCP 权限状态。", Permission.READ),
            tool("lua_list_scripts", "分页列出服务端 Lua 脚本；子目录以 / 分隔。", Permission.READ, "query", "offset:number", "limit:number"),
            tool("lua_read_script", "读取服务端已保存 Lua 文件，不读取 IDE 未保存草稿。", Permission.READ, "name!", "offset:number", "limit:number"),
            tool("lua_save_script", "保存服务端 Lua 文件；会覆盖原内容，需开启写文件权限。管理员 IDE 自动同步并高亮修改；有未保存草稿时由用户确认，不静默覆盖。", Permission.WRITE, "name!", "source!"),
            tool("lua_run_script", "运行服务端已保存的 Lua 脚本，不覆盖文件。脚本可调用全部已授权 MC.* 和其他模组 API；需开启执行权限。", Permission.EXECUTE, "name!"),
            tool("lua_stop_script", "停止 MCP 启动的当前脚本；不会停止其他人/IDE 启动的脚本。", Permission.EXECUTE),
            tool("lua_execution_logs", "读取这套 MCP 服务启动的脚本的有界日志和运行状态。", Permission.READ),
            tool("mc_api_documentation", "查询内置手册和当前模组的 API 文档，先查询用法再写脚本。", Permission.READ, "query", "offset:number", "limit:number"),
            tool("mc_registry_ids", "查询游戏/模组动态注册 ID，含名称和注释；例如 minecraft:item、minecraft:game_rule、minecraft:dimension。", Permission.READ, "registry!", "query", "offset:number", "limit:number")
    );
    private static Tool tool(String name, String description, Permission permission, String... fields) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject(); JsonArray required = new JsonArray();
        for (String field : fields) {
            boolean needed = field.endsWith("!"); field = field.replace("!", ""); String[] parts = field.split(":");
            JsonObject type = new JsonObject(); type.addProperty("type", parts.length > 1 ? "integer" : "string");
            if (parts.length > 1) type.addProperty("minimum", parts[0].equals("limit") ? 1 : 0); else type.addProperty("maxLength", parts[0].equals("source") ? 1_000_000 : 512);
            properties.add(parts[0], type); if (needed) required.add(parts[0]);
        }
        schema.add("properties", properties); schema.add("required", required); return new Tool(name, description, permission, schema);
    }
    public static Tool find(String name) { return ALL.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("未知 MCP 工具")); }
    public static void validate(Tool tool, JsonObject args) {
        JsonObject properties = tool.schema().getAsJsonObject("properties");
        for (JsonElement required : tool.schema().getAsJsonArray("required")) if (!args.has(required.getAsString())) throw new IllegalArgumentException("缺少参数：" + required.getAsString());
        for (var entry : args.entrySet()) {
            if (!properties.has(entry.getKey()) || !entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("无效参数：" + entry.getKey());
            JsonPrimitive value = entry.getValue().getAsJsonPrimitive(); JsonObject property = properties.getAsJsonObject(entry.getKey());
            if (property.get("type").getAsString().equals("string")) {
                if (!value.isString() || value.getAsString().length() > property.get("maxLength").getAsInt()) throw new IllegalArgumentException("字符串参数超限或类型错误：" + entry.getKey());
            } else if (!value.isNumber() || value.getAsBigDecimal().stripTrailingZeros().scale() > 0 || value.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(property.get("minimum").getAsInt())) < 0
                    || value.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) throw new IllegalArgumentException("参数必须为非负整数：" + entry.getKey());
        }
    }
    public static String string(JsonObject args, String key, String fallback) { return args.has(key) ? args.get(key).getAsString() : fallback; }
    public static int integer(JsonObject args, String key, int fallback, int max) { return Math.min(max, args.has(key) ? args.get(key).getAsInt() : fallback); }
    public static JsonObject text(String text) { JsonObject result = new JsonObject(); result.addProperty("text", text); return result; }
    public static JsonObject page(String source, JsonObject args) {
        int start = Math.min(source.length(), integer(args, "offset", 0, Integer.MAX_VALUE));
        if (start > 0 && start < source.length() && Character.isLowSurrogate(source.charAt(start)) && Character.isHighSurrogate(source.charAt(start - 1))) start--;
        int end = Math.min(source.length(), start + Math.max(1, integer(args, "limit", 12_000, 60_000)));
        if (end < source.length() && Character.isHighSurrogate(source.charAt(end - 1)) && Character.isLowSurrogate(source.charAt(end))) end++;
        JsonObject result = text(source.substring(start, end)); result.addProperty("total", source.length()); result.addProperty("nextOffset", end < source.length() ? end : -1); return result;
    }
    public static String manual(String query) {
        try (var stream = LuaMcpTools.class.getResourceAsStream("/assets/mc_lua_ide/manual/zh_cn.md")) {
            String source = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (query.isBlank()) return source;
            StringBuilder found = new StringBuilder(); String[] lines = source.split("\n");
            for (int i = 0, last = -1; i < lines.length; i++) if (lines[i].toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) {
                for (int j = Math.max(last + 1, i - 2); j <= Math.min(lines.length - 1, i + 5); j++) { found.append(lines[j]).append('\n'); last = j; }
            }
            return found.isEmpty() ? "手册未找到匹配；请查询第三方 API 文档。" : found.toString();
        } catch (Exception error) { return "内置手册读取失败"; }
    }
    private LuaMcpTools() { }
}
