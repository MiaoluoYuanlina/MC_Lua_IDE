package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
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
    private final HudLayoutEditor layoutEditor=new HudLayoutEditor();
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

    // ---- F12 page ------------------------------------------------------------------------------------------------------

    /** True while the drag-and-drop HUD layout editor covers the screen; the F12 page itself is hidden meanwhile. */
    boolean layoutEditing(){return layoutEditor.active();}
    void endLayoutEditing(){layoutEditor.end();}
    void beginLayoutEditing(){layoutEditor.begin(null);}
    /** Draws the full-screen layout editor when it is active. Called by whichever screen hosts the F12 page, every frame. */
    void overlay(){layoutEditor.draw();}

    /**
     * Each in-game debug panel is one entry: its on/off switch, a right-click menu with its UI settings, and, where the feature has
     * keyboard shortcuts, a sub-menu holding exactly those shortcuts.
     */
    void debug(){
        ImGui.textWrapped(LuaUiText.text("每项功能的快捷键放在它自己的子菜单里。右键任意一项可打开它的 UI 设置；也可以直接拖动调整浮窗位置。","Each feature keeps its own shortcuts in its sub-menu. Right-click any entry for its UI settings, or drag the panels to reposition them."));
        NativeUi.action(LuaUiText.text("拖动调整浮窗位置…","Drag to reposition panels…"),()->layoutEditor.begin(null));
        ImGui.sameLine();NativeUi.action(LuaUiText.text("重置全部位置","Reset all positions"),()->{LuaHudLayout.current().reset();LuaHudLayout.current().save();});
        ImGui.separator();

        panelEntry(LuaHudLayout.Panel.VARIABLES,"游戏画面显示 Lua 变量");
        panelEntry(LuaHudLayout.Panel.LOGS,"游戏画面显示 Lua 日志");

        panelEntry(LuaHudLayout.Panel.BREAKPOINTS,"游戏画面显示断点信息");
        ImGui.indent(18);
        if(ImGui.treeNodeEx(LuaUiText.text("断点与单步调试 · 快捷键","Breakpoints and stepping · shortcuts")+"##breakpoint_shortcuts",ImGuiTreeNodeFlags.DefaultOpen)){
            ImGui.textDisabled(LuaUiText.text("断点（在代码编辑器中）","Breakpoints (in the code editor)"));
            shortcut(LuaDebugKeyBindings.Action.TOGGLE_BREAKPOINT);shortcut(LuaDebugKeyBindings.Action.CLEAR_BREAKPOINTS);
            ImGui.spacing();ImGui.textDisabled(LuaUiText.text("运行控制（调试运行时，游戏内也可用）","Run control (while debugging, also usable in game)"));
            for(var action:new LuaDebugKeyBindings.Action[]{LuaDebugKeyBindings.Action.PAUSE,LuaDebugKeyBindings.Action.STEP_INTO,LuaDebugKeyBindings.Action.STEP_OVER,LuaDebugKeyBindings.Action.STEP_OUT,LuaDebugKeyBindings.Action.STOP})shortcut(action);
            ImGui.spacing();
            boolean pause=NativeUi.check("断点暂停游戏（全服）",LuaBreakpointClient.state().pauseGame());if(pause!=LuaBreakpointClient.state().pauseGame())LuaBreakpointClient.setPauseGame(pause);
            NativeUi.action("打开断点调试窗口",()->opener.accept("EXT:"+LuaBreakpointWindow.ID));
            ImGui.spacing();ImGui.textDisabled(LuaUiText.text("预设","Presets"));
            NativeUi.action("F 键预设",()->{LuaDebugKeyBindings.settings().apply(LuaDebugKeyBindings.Preset.FUNCTION_KEYS);LuaDebugKeyBindings.settings().save();});ImGui.sameLine();
            NativeUi.action("小键盘预设",()->{LuaDebugKeyBindings.settings().apply(LuaDebugKeyBindings.Preset.NUMPAD);LuaDebugKeyBindings.settings().save();});
            ImGui.treePop();
        }
        ImGui.unindent(18);

        panelEntry(LuaHudLayout.Panel.CAMERA,"自由摄像机提示（仅可调整位置）");

        if(ImGui.collapsingHeader(LuaUiText.text("精确数值（高级）","Exact values (advanced)")))hud();
        NativeUi.status(notice);
    }

    /** One panel's row: switch (when it has one), right-click menu, and a small button that opens the same menu. */
    private void panelEntry(LuaHudLayout.Panel panel,String label){
        String popup="ui_settings_"+panel.name();
        ImGui.pushID(panel.name());
        if(HudLayoutEditor.hasSwitch(panel)){
            boolean shown=HudLayoutEditor.visible(panel),next=NativeUi.check(label,shown);
            if(next!=shown)HudLayoutEditor.setVisible(panel,next);
        }else ImGui.selectable(LuaUiText.text(label),false,0,260,0);
        if(ImGui.beginPopupContextItem(popup)){layoutEditor.settingsPopup(panel);ImGui.endPopup();}
        ImGui.sameLine();
        // Opens the very same popup the right-click menu above shows (same ID, same row scope), starting next frame.
        if(ImGui.smallButton(LuaUiText.text("UI 设置","UI settings")))ImGui.openPopup(popup);
        ImGui.popID();
    }

    /** One shortcut: name, current chord (click it, then press the new keys), unbind and restore-default. */
    private void shortcut(LuaDebugKeyBindings.Action action){
        ImGui.pushID(action.ordinal());
        ImGui.alignTextToFramePadding();ImGui.textUnformatted(LuaUiText.text(action.label));
        ImGui.sameLine(Math.max(170,ImGui.getContentRegionMaxX()*.42f));
        String chord=listening==action?LuaUiText.text("请按下新的按键…","Press the new keys…"):LuaDebugKeyBindings.label(action);
        if(ImGui.button(chord+"##chord",170,0))listening=listening==action?null:action;
        ImGui.sameLine();
        if(ImGui.smallButton(LuaUiText.text("解绑","Unbind"))){LuaDebugKeyBindings.settings().assign(action,-1,0);LuaDebugKeyBindings.settings().save();listening=null;}
        ImGui.sameLine();
        if(ImGui.smallButton(LuaUiText.text("默认","Default"))){
            var preset=LuaDebugKeyBindings.Settings.preset(LuaDebugKeyBindings.Preset.FUNCTION_KEYS).get(action);
            try{LuaDebugKeyBindings.settings().assign(action,preset.key(),preset.modifiers());LuaDebugKeyBindings.settings().save();notice="";}
            catch(IllegalArgumentException error){notice=error.getMessage();}
            listening=null;
        }
        ImGui.popID();
    }

    boolean key(KeyEvent event){
        if(layoutEditor.key(event))return true;
        if(listening==null)return false;if(event.key()==256){listening=null;return true;}if(LuaDebugKeyBindings.modifierKey(event.key()))return true;
        try{LuaDebugKeyBindings.settings().assign(listening,event.key(),event.modifiers());LuaDebugKeyBindings.settings().save();listening=null;notice=LuaUiText.text("已保存快捷键","Shortcut saved");}catch(IllegalArgumentException error){notice=error.getMessage();}return true;
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
