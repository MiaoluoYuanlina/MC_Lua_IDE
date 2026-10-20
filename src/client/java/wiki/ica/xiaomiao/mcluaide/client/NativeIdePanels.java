package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/** Settings/debug controls in the same native workspace, never Minecraft child screens. */
final class NativeIdePanels {
    private final net.minecraft.client.gui.screens.Screen owner;
    private final java.util.function.Consumer<String> opener;
    private LuaLimitsScreen limits;
    private ServerAccessSettingsScreen access;
    private LuaDebugKeyBindings.Action listening;
    private String notice="";
    NativeIdePanels(ImGuiIdeScreen owner){this(owner,owner::openTool);}
    NativeIdePanels(net.minecraft.client.gui.screens.Screen owner,java.util.function.Consumer<String> opener){this.owner=owner;this.opener=opener;}
    void settings(){
        if(limits==null){limits=new LuaLimitsScreen(owner);limits.init(1000,720);access=new ServerAccessSettingsScreen(owner);access.init(1000,720);}
        if(ImGui.beginTabBar("server_settings")){
            if(ImGui.beginTabItem(LuaUiText.text("Lua 限制"))){limits.renderImGui();ImGui.endTabItem();}
            if(ImGui.beginTabItem(LuaUiText.text("服务器接入"))){access.renderImGui();ImGui.endTabItem();}
            if(ImGui.beginTabItem("MCP")){
                ImGui.textWrapped(LuaUiText.text("MCP 让外部 AI 工具通过本机令牌读取/修改 Lua 文件，并可选运行脚本。服务端与客户端各有独立开关，默认关闭。"));
                NativeUi.action("服务端 MCP 配置",()->opener.accept("EXT:"+LuaMcpWindow.SERVER_ID));ImGui.sameLine();
                NativeUi.action("客户端 MCP 配置",()->opener.accept("EXT:"+LuaMcpWindow.CLIENT_ID));ImGui.endTabItem();}
            ImGui.endTabBar();
        }
    }
    void debug(){
        boolean variables=NativeUi.check("游戏画面显示 Lua 变量",LuaDebugHud.variablesEnabled());if(variables!=LuaDebugHud.variablesEnabled())LuaDebugHud.setVariablesEnabled(variables);
        boolean logs=NativeUi.check("游戏画面显示 Lua 日志",LuaLogHud.enabled());if(logs!=LuaLogHud.enabled())LuaLogHud.setEnabled(logs);
        boolean info=NativeUi.check("游戏画面显示断点信息",LuaBreakpointClient.showInfo());if(info!=LuaBreakpointClient.showInfo())LuaBreakpointClient.setShowInfo(info);
        boolean pause=NativeUi.check("断点暂停游戏（全服）",LuaBreakpointClient.state().pauseGame());if(pause!=LuaBreakpointClient.state().pauseGame())LuaBreakpointClient.setPauseGame(pause);
        NativeUi.action("打开断点调试窗口",()->opener.accept("EXT:"+LuaBreakpointWindow.ID));
        NativeUi.action("F 键预设",()->{LuaDebugKeyBindings.settings().apply(LuaDebugKeyBindings.Preset.FUNCTION_KEYS);LuaDebugKeyBindings.settings().save();});ImGui.sameLine();
        NativeUi.action("小键盘预设",()->{LuaDebugKeyBindings.settings().apply(LuaDebugKeyBindings.Preset.NUMPAD);LuaDebugKeyBindings.settings().save();});
        for(var action:LuaDebugKeyBindings.Action.values()){
            ImGui.pushID(action.ordinal());if(ImGui.button(LuaUiText.text(action.label)+" : "+(listening==action?"…":LuaDebugKeyBindings.label(action))))listening=action;
            ImGui.sameLine();NativeUi.action("解绑",()->{LuaDebugKeyBindings.settings().assign(action,-1,0);LuaDebugKeyBindings.settings().save();});ImGui.popID();
        }
        if(ImGui.collapsingHeader(LuaUiText.text("游戏浮窗位置 / 背景")))hud();NativeUi.status(notice);
    }
    boolean key(KeyEvent event){
        if(listening==null)return false;if(event.key()==256){listening=null;return true;}if(LuaDebugKeyBindings.modifierKey(event.key()))return true;
        try{LuaDebugKeyBindings.settings().assign(listening,event.key(),event.modifiers());LuaDebugKeyBindings.settings().save();listening=null;notice="已保存快捷键";}catch(IllegalArgumentException error){notice=error.getMessage();}return true;
    }
    private void hud(){
        var layout=LuaHudLayout.current();var window=Minecraft.getInstance().getWindow();int width=window.getGuiScaledWidth(),height=window.getGuiScaledHeight();
        for(var panel:LuaHudLayout.Panel.values()){
            ImGui.pushID(panel.ordinal());if(ImGui.treeNode(LuaUiText.text(panel.label))){
                var r=layout.bounds(panel,width,height);int x=NativeUi.integer("X",r.x(),0,width),y=NativeUi.integer("Y",r.y(),0,height),w=NativeUi.integer("宽度",r.width(),40,width),h=NativeUi.integer("高度",r.height(),30,height);
                var updated=new LuaHudLayout.Rect(x,y,w,h);int transparency=NativeUi.integer("背景透明度 %",layout.transparency(panel),0,100);
                if(!updated.equals(r)||transparency!=layout.transparency(panel)){layout.set(panel,updated,width,height);layout.setTransparency(panel,transparency);layout.save();}ImGui.treePop();
            }ImGui.popID();
        }
    }
}
