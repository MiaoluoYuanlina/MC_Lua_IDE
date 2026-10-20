package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import java.util.*;

/** Configuration content inside the normal animated/dockable IDE frame. */
final class LuaAiWindow implements McLuaIdeWindowApi.NativeContent {
    @Override public void renderImGui() {
        var provider=NativeUi.combo("API",draft.selected,List.of(LuaAiSettings.Provider.values()),v->v.label);
        if(provider!=draft.selected){rememberProvider();draft.selected=provider;loadProvider();}
        endpoint.setValue(NativeUi.field("HTTPS API 基础地址",endpoint.getValue()));model.setValue(NativeUi.field("模型 ID",model.getValue()));
        imgui.ImGui.textUnformatted("API Key");key.setValue(NativeUi.password("##key",key.getValue()));
        imgui.ImGui.textDisabled(draft.api().key().isBlank()?LuaUiText.text("尚未保存密钥"):LuaUiText.text("已保存 · 留空保留原密钥"));
        NativeUi.action("清除当前服务密钥",()->{rememberProvider();var api=draft.api();draft.apis.put(draft.selected,new LuaAiSettings.Api(api.endpoint(),api.model(),""));loadProvider();});
        draft.enabled=NativeUi.check("AI 自动分析运行报错",draft.enabled);
        imgui.ImGui.textColored(1,.7f,.35f,1,LuaUiText.text("启用后将报错及相关代码发送给所选服务，可能产生费用。"));
        NativeUi.action("保存配置",()->{try{rememberProvider();controller.configure(draft);notice="已加密保存至当前用户注册表";loadProvider();}catch(Exception|LinkageError error){notice="保存失败，没有保存明文密钥";}});
        NativeUi.action("分析当前运行报错",controller::manual);imgui.ImGui.sameLine();NativeUi.action("取消分析 / 清除标注",()->{controller.cancel(true);controller.clearMarks();});
        NativeUi.status(notice+"\n"+controller.status());
        for(String line:explanations())imgui.ImGui.textWrapped(line);
    }
    static final String ID = "mc_lua_ide:ai_tools";
    private final McLuaIdeWindowApi.Context context;
    private final LuaAiController controller;
    private final EditBox endpoint, model, key;
    private LuaAiSettings draft;
    private int width = 500, height = 370, scroll;
    private String notice = "先保存配置，再分析；切换服务会保留各自的配置";
    LuaAiWindow(McLuaIdeWindowApi.Context context, LuaAiController controller) {
        this.context = context; this.controller = controller; draft = controller.settings();
        endpoint = field(LuaUiText.text("HTTPS API 基础地址"), 1024); model = field(LuaUiText.text("模型 ID"), 120); key = field("API Key", 4096);
        key.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        loadProvider();
    }
    private EditBox field(String title, int max) { var field = new EditBox(context.font(), 90, 0, 100, 20, Component.literal(title)); field.setMaxLength(max); return field; }
    private void rememberProvider() {
        String value = key.getValue().isBlank() ? draft.api().key() : key.getValue();
        draft.apis.put(draft.selected, new LuaAiSettings.Api(endpoint.getValue(), model.getValue(), value));
    }
    private void loadProvider() {
        endpoint.setValue(draft.api().endpoint()); model.setValue(draft.api().model()); key.setValue("");
        key.setHint(Component.literal(draft.api().key().isBlank() ? LuaUiText.text("填写密钥（仅显示星号）") : LuaUiText.text("已保存 · 留空保留原密钥")));
        model.setHint(Component.literal(LuaUiText.text("填写你有权限使用的模型 ID"))); focus(null);
    }
    @Override public void resize(int width, int height) { this.width = width; this.height = height; layout(); }
    private int footer() { return height - 26; }
    private void layout() {
        int fieldWidth = Math.max(50, width - 94);
        endpoint.setX(90); endpoint.setY(62 - scroll); endpoint.setWidth(fieldWidth);
        model.setX(90); model.setY(90 - scroll); model.setWidth(fieldWidth);
        key.setX(90); key.setY(118 - scroll); key.setWidth(fieldWidth);
    }
    private List<String> explanations() {
        List<String> rows = new ArrayList<>();
        for (String text : List.of(notice, controller.status(),
                "隐私/费用：开启后会把报错、附近代码和 AI 请求读取的相关文本发送到所选 API 服务（自定义地址也会收到密钥），可能产生费用。",
                "读取范围：当前已打开文本（包括未保存修改）、服务端 Lua/datapack 工作区、客户端 IDE 管理的 resourcepacks；不包含配置目录和任意电脑文件。",
                "工具：列目录、读指定行、搜索、查看 MC/第三方 API、批量标注；不能改文件、执行 Lua 或系统命令。",
                "密钥在当前用户 HKCU 注册表中，经 Windows DPAPI 加密。相同 Windows 用户的恶意程序仍可能解密，勿把代码中的其他密码当成已受保护。",
                "模型 ID 可自行填写。地址填 API 基础地址，不含 /responses、/messages 等接口后缀。GPT 使用 Responses；其他服务使用原生工具调用格式。",
                "自动分析仅针对脚本运行后的报错及停止回调异常；写代码时的静态错误/警告不发送。自动请求间隔至少 30 秒，同一报错不重复发送。关闭 IDE 会取消未完成分析。",
                "标注背景：红色=错误，黄色=警告，蓝色=说明。悬浮查看解释，悬浮时按 Esc 清除全部标注。修改代码会移除旧标注。")) {
            while (!text.isEmpty()) { String part = context.font().plainSubstrByWidth(text, Math.max(20, width - 8)); if (part.isEmpty()) part = text.substring(0, 1); rows.add(part); text = text.substring(part.length()); }
            rows.add("");
        }
        return rows;
    }
    @Override public void render(GuiGraphicsExtractor graphics, int mx, int my) {
        List<String> rows = explanations(); scroll = Math.clamp(scroll, 0, Math.max(0, 251 + rows.size() * 13 - footer())); layout();
        graphics.enableScissor(0, 0, width, Math.max(1, footer() - 2));
        graphics.text(context.font(), LuaUiText.text("API 配置 · 仅当前管理员客户端"), 2, 5 - scroll, 0xFF9EC7EE);
        var providers = LuaAiSettings.Provider.values(); int cell = (width - 6) / 4;
        for (int i = 0; i < 4; i++) button(graphics, providers[i].label, i * (cell + 2), 27 - scroll, cell,
                providers[i] == draft.selected ? 0xFF4775A0 : 0xFF2A435E, mx, my);
        String[] labels = {LuaUiText.text("基础地址"), LuaUiText.text("模型 ID"), "API Key"}; EditBox[] fields = {endpoint, model, key};
        for (int i = 0; i < 3; i++) { graphics.text(context.font(), labels[i], 2, 68 + i * 28 - scroll, 0xFFEAF2FF); fields[i].extractRenderState(graphics, mx, my, 0); }
        button(graphics, LuaUiText.text("清除当前服务密钥（保存后生效）"), 0, 146 - scroll, width, 0xFF2A435E, mx, my);
        button(graphics, "[" + (draft.enabled ? "✓" : " ") + "] AI 自动分析运行报错 · " + (draft.enabled ? LuaUiText.text("开启") : LuaUiText.text("关闭（默认）")), 0, 174 - scroll, width,
                draft.enabled ? 0xFF6B5731 : 0xFF2A435E, mx, my);
        int half = (width - 4) / 2;
        button(graphics, LuaUiText.text("分析当前报错"), 0, 203 - scroll, half, 0xFF2A435E, mx, my);
        button(graphics, "取消分析 / 清除标注", half + 4, 203 - scroll, half, 0xFF2A435E, mx, my);
        graphics.text(context.font(), LuaUiText.text("需保存才生效 · 手动分析也会发送代码"), 2, 235 - scroll, 0xFFE7C17D);
        for (int i = Math.max(0, (scroll - 251) / 13); i < rows.size() && 251 + i * 13 - scroll < footer(); i++)
            graphics.text(context.font(), rows.get(i), 2, 251 + i * 13 - scroll, 0xFFB9CADA);
        graphics.disableScissor();
        button(graphics, LuaUiText.text("保存配置"), 0, footer(), half, 0xFF355B78, mx, my);
        button(graphics, LuaUiText.text("关闭窗口"), half + 4, footer(), half, 0xFF2A435E, mx, my);
    }
    private void button(GuiGraphicsExtractor graphics, String text, int x, int y, int w, int color, int mx, int my) {
        graphics.fill(x, y, x + w, y + 22, inside(mx, my, x, y, w, 22) ? 0xFF4E7094 : color);
        graphics.centeredText(context.font(), context.font().plainSubstrByWidth(text, Math.max(1, w - 6)), x + w / 2, y + 7, 0xFFEAF2FF);
    }
    private static boolean inside(double x, double y, int left, int top, int w, int h) { return x >= left && x < left + w && y >= top && y < top + h; }
    private void focus(EditBox selected) { for (EditBox field : List.of(endpoint, model, key)) field.setFocused(field == selected); }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return false;
        if (event.y() >= footer()) {
            if (event.x() >= width / 2) context.close();
            else try { rememberProvider(); controller.configure(draft); notice = LuaUiText.text("配置已加密保存至当前用户注册表"); loadProvider(); }
            catch (Exception | LinkageError error) { notice = LuaUiText.text("保存失败：") + (error instanceof IllegalArgumentException ? error.getMessage() : "注册表/DPAPI 不可用；没有保存明文密钥"); }
            return true;
        }
        if (event.y() < 0 || event.y() >= footer() - 2) return false;
        for (EditBox field : List.of(endpoint, model, key)) if (field.mouseClicked(event, doubleClick)) { focus(field); return true; }
        focus(null); double y = event.y() + scroll;
        int cell = (width - 6) / 4;
        if (y >= 27 && y < 49) { int index = Math.clamp((int) event.x() / (cell + 2), 0, 3); rememberProvider(); draft.selected = LuaAiSettings.Provider.values()[index]; loadProvider(); return true; }
        if (y >= 146 && y < 168) { rememberProvider(); var api = draft.api(); draft.apis.put(draft.selected, new LuaAiSettings.Api(api.endpoint(), api.model(), "")); loadProvider(); notice = "密钥已从草稿清除，请保存"; return true; }
        if (y >= 174 && y < 196) { draft.enabled = !draft.enabled; notice = draft.enabled ? LuaUiText.text("将发送相关代码并可能计费；点击保存确认启用") : "点击保存关闭自动分析"; return true; }
        if (y >= 203 && y < 225) { if (event.x() < width / 2) { controller.manual(); notice = LuaUiText.text("使用已保存的 API 配置进行手动分析"); } else { controller.cancel(true); controller.clearMarks(); notice = "分析已取消，全部标注已清除"; } return true; }
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == 258) {
            List<EditBox> fields = List.of(endpoint, model, key); int selected = -1;
            for (int i = 0; i < fields.size(); i++) if (fields.get(i).isFocused()) selected = i;
            int next = Math.floorMod(selected + (event.hasShiftDown() ? -1 : 1), 3); focus(fields.get(next)); scroll = Math.max(0, 62 + next * 28 - Math.max(25, footer() - 30)); layout(); return true;
        }
        for (EditBox field : List.of(endpoint, model, key)) if (field.isFocused()) return field.keyPressed(event);
        switch (event.key()) { case 265 -> scroll = Math.max(0, scroll - 13); case 264 -> scroll += 13; case 266 -> scroll = Math.max(0, scroll - 100); case 267 -> scroll += 100; default -> { return false; } } return true;
    }
    @Override public boolean charTyped(CharacterEvent event) { for (EditBox field : List.of(endpoint, model, key)) if (field.isFocused()) return field.charTyped(event); return false; }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { for (EditBox field : List.of(endpoint, model, key)) if (field.isFocused()) return field.mouseDragged(event, dx, dy); return false; }
    @Override public boolean mouseReleased(MouseButtonEvent event) { boolean result = false; for (EditBox field : List.of(endpoint, model, key)) result |= field.mouseReleased(event); return result; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.max(0, scroll - (int) Math.round(vertical * 30)); return true; }
    @Override public void focusChanged(boolean focused) { if (!focused) focus(null); }
    @Override public void disposed() { key.setValue(""); }
}
