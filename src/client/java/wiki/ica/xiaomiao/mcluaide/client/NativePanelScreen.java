package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Native Mod Menu/F12 pages when the full IDE is not open. */
final class NativePanelScreen extends Screen implements NativeUiScreen {
    private final Screen previous;
    private final boolean debug;
    private final NativeIdePanels panels;
    private final ImBoolean open=new ImBoolean(true);
    private String notice="";
    NativePanelScreen(Screen previous,boolean debug){
        super(Component.literal(LuaUiText.text(debug?"Lua 调试":"MC Lua IDE 设置")));
        this.previous=previous;this.debug=debug;panels=new NativeIdePanels(this,this::openTool);
    }
    private void openTool(String id){
        ScriptManager.checkAccess().whenComplete((v,e)->Minecraft.getInstance().execute(()->{
            if(e!=null){notice=ScriptManager.message(e);return;}
            var ide=new ImGuiIdeScreen(previous,"");Minecraft.getInstance().setScreen(ide);ide.openTool(id);
        }));
    }
    @Override public void drawUi(){
        // While the drag-and-drop HUD editor is open it replaces the page; Esc (handled by the panels) returns to the page.
        if(panels.layoutEditing()){panels.overlay();return;}
        var viewport=ImGui.getMainViewport();ImGui.setNextWindowPos(viewport.getCenterX(),viewport.getCenterY(),ImGuiCond.Appearing,.5f,.5f);
        ImGui.setNextWindowSize(690,580,ImGuiCond.FirstUseEver);ImGui.setNextWindowSizeConstraints(260,180,Float.MAX_VALUE,Float.MAX_VALUE);
        if(ImGui.begin(title.getString()+"###native_configuration",open,ImGuiWindowFlags.NoCollapse|ImGuiWindowFlags.NoDocking)){
            if(debug)panels.debug();else panels.settings();if(!notice.isBlank())NativeUi.status(notice);
        }ImGui.end();if(!open.get())ImGuiIdeRuntime.defer(this::onClose);
    }
    @Override public boolean keyPressed(KeyEvent e){
        boolean[] handled={false};ImGuiIdeRuntime.inContext(()->handled[0]=panels.key(e));if(handled[0])return true;
        if(e.key()==GLFW.GLFW_KEY_ESCAPE||debug&&e.key()==GLFW.GLFW_KEY_F12){onClose();return true;}
        ImGuiIdeRuntime.key(e.key(),e.scancode(),GLFW.GLFW_PRESS,e.modifiers());return true;
    }
    @Override public boolean keyReleased(KeyEvent e){ImGuiIdeRuntime.key(e.key(),e.scancode(),GLFW.GLFW_RELEASE,e.modifiers());return true;}
    @Override public boolean charTyped(CharacterEvent e){ImGuiIdeRuntime.character(e.codepoint());return true;}
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean twice){ImGuiIdeRuntime.mouse(e.button(),true);return true;}
    @Override public boolean mouseReleased(MouseButtonEvent e){ImGuiIdeRuntime.mouse(e.button(),false);return true;}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){ImGuiIdeRuntime.scroll(h,v);return true;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float tick){g.blurBeforeThisStratum();g.fill(0,0,width,height,0x80090C13);}
    @Override public void rendererFailed(Throwable error){wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.LOGGER.error("Native settings UI failed",error);onClose();}
    @Override public void onClose(){Minecraft.getInstance().setScreen(previous);}
    @Override public void removed(){ImGuiIdeRuntime.clearInput();}
    @Override public boolean isPauseScreen(){return false;}
}
