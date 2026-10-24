package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import wiki.ica.xiaomiao.mcluaide.mcp.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The shared IDE frame supplies movement, docking, resizing, animation and layout persistence. */
final class LuaMcpWindow implements McLuaIdeWindowApi.NativeContent {
    @Override public void renderImGui() {
        imgui.ImGui.textWrapped((server?"服务端":"客户端")+" MCP · Streamable HTTP · 127.0.0.1 · Token");
        imgui.ImGui.beginDisabled(busy);
        String typedPort=NativeUi.field("监听端口",port.getValue()).replaceAll("[^0-9]","");
        if(!typedPort.equals(port.getValue()))port.setValue(typedPort.length()>5?typedPort.substring(0,5):typedPort);
        boolean wasWrite=write,wasExecute=execute,wasRemote=remote;
        write=NativeUi.check("允许写 Lua 文件（覆盖原内容）",write);execute=NativeUi.check("允许运行/停止脚本",execute);
        remote=NativeUi.check("允许非本机 IP 连接（任意设备都能连接）",remote);
        // Without this the periodic status refresh copies the saved values back over an unapplied choice.
        if(write!=wasWrite||execute!=wasExecute||remote!=wasRemote)dirty=true;
        if(dirty)imgui.ImGui.textColored(1f,.83f,.4f,1f,LuaUiText.text("设置已更改，点击“开启 / 应用配置”后生效。"));
        if(remote)imgui.ImGui.textColored(1,.4f,.4f,1,LuaUiText.text("已允许非本机连接：知道地址和令牌的任何设备都能调用 MCP，令牌以明文 HTTP 传输；只在可信网络使用，并注意防火墙。"));
        else imgui.ImGui.textDisabled(LuaUiText.text("默认只监听本机 127.0.0.1。"));
        imgui.ImGui.textColored(1,.4f,.4f,1,LuaUiText.text("高危：执行可改变世界，令牌不要发给不可信的人。"));
        NativeUi.action("开启 / 应用配置",()->apply(true,false));imgui.ImGui.sameLine();NativeUi.action("关闭服务",()->apply(false,false));NativeUi.action("重置令牌",()->apply(data!=null&&data.running(),true));
        if(data!=null){
            NativeUi.action("复制连接提示词",()->context.copyText(LuaMcpServer.prompt(data.settings(),server)));
            NativeUi.action("复制调用日志",()->context.copyText(LuaMcpServer.JSON.toJson(data.logs())));
            NativeUi.action("清空调用日志",()->{if(server)ScriptManager.clearMcpServerLogs().whenComplete((v,e)->context.minecraft().execute(()->{if(!disposed&&e==null)data=v;}));else{LuaClientMcpService.clearLogs();data=LuaClientMcpService.status();}});
        }
        NativeUi.action("刷新",()->refresh(true));imgui.ImGui.endDisabled();NativeUi.status(notice);
        if(data!=null)NativeUi.readOnly("##calls",data.logs().stream().map(log->log.method()+" "+log.tool()+" · "+log.outcome()+" · "+log.milliseconds()+"ms").collect(java.util.stream.Collectors.joining("\n")),Math.max(100,imgui.ImGui.getContentRegionAvailY()));
    }
    static final String CLIENT_ID = "mc_lua_ide:mcp_client", SERVER_ID = "mc_lua_ide:mcp_server";
    private final McLuaIdeWindowApi.Context context;
    private final boolean server;
    private final EditBox port;
    private int width = 500, height = 340, scroll;
    private boolean write, execute, remote, busy, disposed, dirty;
    private LuaMcpStatus data;
    private String notice = "正在读取 MCP 配置…";
    private long refreshed;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    LuaMcpWindow(McLuaIdeWindowApi.Context context, boolean server) {
        this.context = context; this.server = server;
        port = new EditBox(context.font(), 112, 25, 100, 20, Component.literal(LuaUiText.text("MCP 监听端口")));
        port.setMaxLength(5); port.setResponder(value -> dirty = true); refresh(true);
    }
    private void refresh(boolean reset) {
        if (busy) return; refreshed = System.nanoTime();
        (server ? ScriptManager.mcpServerStatus() : CompletableFuture.completedFuture(LuaClientMcpService.status()))
                .whenComplete((value, error) -> context.minecraft().execute(() -> {
                    if (disposed) return;
                    if (error != null) { notice = LuaUiText.text("读取失败：") + ScriptManager.message(error); return; }
                    data = value;
                    if (reset || !dirty && !port.isFocused()) { port.setValue(Integer.toString(value.settings().port())); write = value.settings().allowWrite(); execute = value.settings().allowExecution(); remote = value.settings().allowRemote(); dirty = false; }
                    if (reset) notice = value.problem().isBlank() ? "初次默认关闭；启停配置保存，下次启动恢复" : value.problem();
                }));
    }
    @Override public void tick() { if (!disposed && System.nanoTime() - refreshed > 1_500_000_000L) refresh(false); }
    @Override public void resize(int width, int height) { this.width = width; this.height = height; layout(); }
    private int footer() { return height - 26; }
    private void layout() { port.setX(112); port.setY(25 - scroll); port.setWidth(Math.max(45, Math.min(100, width - 204))); port.active = !busy; }
    private record Line(String text, int color) { }
    private List<Line> lines() {
        List<Line> rows = new ArrayList<>(); wrapped(rows, notice, 0xFFE7D39E);
        if (data != null && !data.problem().isBlank()) wrapped(rows, data.problem(), 0xFFFF8892);
        wrapped(rows, server ? "服务端监听 127.0.0.1，远程请用 SSH/可信端口隧道。所有管理员共享配置。" : "客户端监听 127.0.0.1；每次工具调用仍由游戏服务端检查管理员权限。", 0xFF9EC7EE);
        wrapped(rows, "关闭 MCP 不会停止已经启动的脚本。日志不记令牌、参数和源码；最近 500 条。", 0xFF9AAFC5);
        if (data != null) for (var log : data.logs()) wrapped(rows, TIME.format(Instant.ofEpochMilli(log.time()).atZone(ZoneId.systemDefault()))
                + " " + log.method() + (log.tool().isBlank() ? "" : " · " + log.tool()) + " · " + log.outcome() + " · " + log.milliseconds() + "ms",
                log.outcome().contains(LuaUiText.text("失败")) || log.outcome().contains("拒绝") ? 0xFFFF8B93 : 0xFFBBD5EA);
        return rows;
    }
    private void wrapped(List<Line> rows, String text, int color) {
        while (!text.isEmpty()) { String part = context.font().plainSubstrByWidth(text, Math.max(20, width - 6)); if (part.isEmpty()) part = text.substring(0, 1); rows.add(new Line(part, color)); text = text.substring(part.length()); }
    }
    @Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var rows = lines(); scroll = Math.clamp(scroll, 0, Math.max(0, 198 + rows.size() * 13 - footer())); layout();
        graphics.enableScissor(0, 0, width, Math.max(1, footer() - 2));
        graphics.text(context.font(), (server ? "服务端" : "客户端") + " MCP · Streamable HTTP · 访问令牌保护", 2, 5 - scroll, 0xFF9EC7EE);
        graphics.text(context.font(), "监听端口", 2, 31 - scroll, 0xFFEAF2FF); port.extractRenderState(graphics, mouseX, mouseY, 0);
        graphics.text(context.font(), data != null && data.running() ? "● 已开启" : "● 已关闭", width - 86, 31 - scroll, data != null && data.running() ? 0xFF78DEA0 : 0xFF9AAFC5);
        button(graphics, "[" + (write ? "✓" : " ") + "] 允许写 Lua 文件（覆盖原内容）", 2, 51 - scroll, width - 4, !busy, mouseX, mouseY);
        button(graphics, "[" + (execute ? "✓" : " ") + "] 允许运行/停止 MCP 脚本（控制世界）", 2, 77 - scroll, width - 4, !busy, mouseX, mouseY);
        graphics.text(context.font(), "高危：执行可改变世界，令牌不要发给不可信的人。", 2, 105 - scroll, 0xFFFF777F);
        int cell = (width - 8) / 3;
        String[] labels = {"开启 / 应用配置", LuaUiText.text("关闭"), "重置令牌", LuaUiText.text("复制连接提示词"), "复制调用日志", "清空调用日志"};
        for (int i = 0; i < 6; i++) button(graphics, labels[i], (i % 3) * (cell + 4), 122 + (i / 3) * 26 - scroll,
                i % 3 == 2 ? width - 2 * (cell + 4) : cell, data != null && (i == 3 || i == 4 || !busy), mouseX, mouseY);
        graphics.text(context.font(), "MCP 调用日志 · 滚轮 / ↑↓ / PageUp / PageDown", 2, 179 - scroll, 0xFFEAF2FF);
        for (int i = Math.max(0, (scroll - 194) / 13); i < rows.size() && 194 + i * 13 - scroll < footer(); i++)
            graphics.text(context.font(), rows.get(i).text, 2, 194 + i * 13 - scroll, rows.get(i).color);
        graphics.disableScissor();
        button(graphics, LuaUiText.text("刷新"), 0, footer(), (width - 4) / 2, !busy, mouseX, mouseY);
        button(graphics, "关闭窗口（不关闭服务）", (width + 4) / 2, footer(), (width - 4) / 2, true, mouseX, mouseY);
    }
    private void button(GuiGraphicsExtractor graphics, String text, int x, int y, int w, boolean enabled, int mx, int my) {
        graphics.fill(x, y, x + w, y + 22, !enabled ? 0xFF1D2733 : inside(mx, my, x, y, w, 22) ? 0xFF3E648A : 0xFF2A435E);
        graphics.centeredText(context.font(), context.font().plainSubstrByWidth(text, Math.max(1, w - 6)), x + w / 2, y + 7, enabled ? 0xFFEAF2FF : 0xFF637489);
    }
    private static boolean inside(double x, double y, int left, int top, int w, int h) { return x >= left && x < left + w && y >= top && y < top + h; }
    private void apply(boolean enabled, boolean rotate) {
        if (busy || data == null) return;
        try {
            int value = Integer.parseInt(port.getValue()); new LuaMcpSettings(false, value, write, execute, data.settings().token(), "", ""); busy = true;
            (server ? ScriptManager.configureMcpServer(enabled, value, write, execute, rotate, remote) : LuaClientMcpService.configure(enabled, value, write, execute, rotate, remote))
                    .whenComplete((result, error) -> context.minecraft().execute(() -> {
                        if (disposed) return; busy = false;
                        if (error != null) notice = "配置失败：" + ScriptManager.message(error);
                        else { data = result; dirty = false; notice = result.problem().isBlank() ? enabled ? "已开启并保存" : "已关闭并保存，下次保持关闭" : result.problem(); }
                    }));
        } catch (Exception error) { notice = "端口必须为 1024～65535 的整数"; }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return false;
        if (event.y() >= footer()) { if (event.x() < width / 2) refresh(true); else context.close(); return true; }
        if (event.y() < 0 || event.y() >= footer() - 2) return false;
        if (port.mouseClicked(event, doubleClick)) { port.setFocused(true); return true; } port.setFocused(false);
        double y = event.y() + scroll;
        if (inside(event.x(), y, 2, 51, width - 4, 22) && !busy) { write = !write; dirty = true; return true; }
        if (inside(event.x(), y, 2, 77, width - 4, 22) && !busy) { execute = !execute; dirty = true; return true; }
        int index = Math.min(2, (int) event.x() / ((width - 8) / 3 + 4));
        if (y >= 122 && y < 144) { apply(index != 1 && (index != 2 || data != null && data.settings().enabled()), index == 2); return true; }
        if (y >= 148 && y < 170 && data != null) {
            if (index == 0) { context.copyText(LuaMcpServer.prompt(data.settings(), server)); notice = "已复制连接提示词（含私密令牌，不要公开）"; }
            else if (index == 1) { context.copyText(LuaMcpServer.JSON.toJson(data.logs())); notice = "调用日志已复制"; }
            else if (!busy) {
                if (server) ScriptManager.clearMcpServerLogs().whenComplete((value, error) -> context.minecraft().execute(() -> { if (!disposed && error == null) data = value; }));
                else { LuaClientMcpService.clearLogs(); data = LuaClientMcpService.status(); }
            } return true;
        } return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.max(0, scroll - (int) Math.round(vertical * 30)); return true; }
    @Override public boolean keyPressed(KeyEvent event) {
        if (port.isFocused()) return port.keyPressed(event);
        switch (event.key()) { case 265 -> scroll = Math.max(0, scroll - 13); case 264 -> scroll += 13; case 266 -> scroll = Math.max(0, scroll - 100); case 267 -> scroll += 100; default -> { return false; } } return true;
    }
    @Override public boolean charTyped(CharacterEvent event) { return port.isFocused() && port.charTyped(event); }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return port.isFocused() && port.mouseDragged(event, dx, dy); }
    @Override public boolean mouseReleased(MouseButtonEvent event) { return port.mouseReleased(event); }
    @Override public void focusChanged(boolean focused) { if (!focused) port.setFocused(false); }
    @Override public void disposed() { disposed = true; }
}
