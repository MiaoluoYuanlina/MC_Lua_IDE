package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.glfw.GLFW;

/** Native controls over authorized models/actions; never renders legacy GUI surfaces. */
final class ImGuiToolWorkspace implements AutoCloseable {
    private final ImGuiIdeScreen owner;
    final LuaIdeScreen controller;
    private final LinkedHashMap<String,Pane> panes=new LinkedHashMap<>();
    private final Properties preferences;
    private final WindowMotion motion=new WindowMotion();
    private final GameViewport gameView;
    private ImGuiIdeDocument bound;
    private String lastSource="",focusNext="";
    private boolean synchronizing;
    private String activePane="";
    private final NativeIdePanels nativePanels;
    final NativeCodeArea codeArea;
    private final OutlinePanel outline;
    private final SearchPanel search;
    private final DiffPanel diff;
    final TerminalPanel terminal;
    private final AuditLogPanel audit = new AuditLogPanel();
    private BackupPanel backups;
    SearchPanel searchPanel(){return search;}
    DiffPanel diffPanel(){return diff;}
    /** Whether the focused pane is the terminal: it then owns the keyboard, apart from the few global shortcuts the screen handles first. */
    boolean terminalCaptures(){return activePane.equals(TerminalPanel.ID)&&isOpen(TerminalPanel.ID)&&terminal.captures();}
    String title(String id){var pane=panes.get(id);return pane==null?id:pane.title;}
    ImGuiToolWorkspace(ImGuiIdeScreen owner,Properties preferences){
        this.owner=owner;this.preferences=preferences;gameView=new GameViewport(owner::enterGameControl,GameControl::active);nativePanels=new NativeIdePanels(owner);
        var client=Minecraft.getInstance();controller=new LuaIdeScreen(null,"",owner);
        controller.init(client.getWindow().getGuiScaledWidth(),client.getWindow().getGuiScaledHeight());
        codeArea=new NativeCodeArea(controller);
        outline=new OutlinePanel(owner);search=new SearchPanel(owner);diff=new DiffPanel(owner);terminal=new TerminalPanel(controller::nativePersonalization);
        for(var tool:controller.nativeTools())panes.put(tool.id(),new Pane(tool.id(),tool.title(),Boolean.parseBoolean(preferences.getProperty("tool."+tool.id(),"false"))));
        for(var pane:panes.values())controller.nativeCloseTool(pane.id);
        panes.put("NATIVE_SETTINGS",new Pane("NATIVE_SETTINGS",LuaUiText.text("服务器配置"),Boolean.parseBoolean(preferences.getProperty("tool.NATIVE_SETTINGS","false"))));
        panes.put("NATIVE_GAMEVIEW",new Pane("NATIVE_GAMEVIEW",LuaUiText.text("游戏视口","Game viewport"),Boolean.parseBoolean(preferences.getProperty("tool.NATIVE_GAMEVIEW","false"))));
        panes.put("NATIVE_OUTLINE",new Pane("NATIVE_OUTLINE",LuaUiText.text("大纲","Outline"),Boolean.parseBoolean(preferences.getProperty("tool.NATIVE_OUTLINE","false"))));
        panes.put("NATIVE_SEARCH",new Pane("NATIVE_SEARCH",LuaUiText.text("在文件中查找","Search in files"),Boolean.parseBoolean(preferences.getProperty("tool.NATIVE_SEARCH","false"))));
        panes.put("NATIVE_DIFF",new Pane("NATIVE_DIFF",LuaUiText.text("文件对比","Compare"),Boolean.parseBoolean(preferences.getProperty("tool.NATIVE_DIFF","false"))));
        panes.put(WelcomePanel.ID,new Pane(WelcomePanel.ID,LuaUiText.text("欢迎","Welcome"),false));
        panes.put(SplitEditorPane.ID,new Pane(SplitEditorPane.ID,LuaUiText.text("拆分编辑器","Split editor"),Boolean.parseBoolean(preferences.getProperty("tool."+SplitEditorPane.ID,"false"))));
        panes.put(BackupPanel.ID,new Pane(BackupPanel.ID,LuaUiText.text("备份与恢复","Backups"),Boolean.parseBoolean(preferences.getProperty("tool."+BackupPanel.ID,"false"))));
        panes.put(AuditLogPanel.ID,new Pane(AuditLogPanel.ID,LuaUiText.text("审计日志","Audit log"),Boolean.parseBoolean(preferences.getProperty("tool."+AuditLogPanel.ID,"false"))));
        panes.put(TerminalPanel.ID,new Pane(TerminalPanel.ID,LuaUiText.text("终端","Terminal"),Boolean.parseBoolean(preferences.getProperty("tool."+TerminalPanel.ID,"false"))));
        panes.put("NATIVE_DEBUG",new Pane("NATIVE_DEBUG",LuaUiText.text("调试设置 · F12"),Boolean.parseBoolean(preferences.getProperty("tool.NATIVE_DEBUG","false"))));
        if(!preferences.containsKey("tool.initialized"))for(String id:List.of("FILES","API_DOCS","MANUAL"))panes.get(id).open.set(true);
        for(var pane:panes.values())if(pane.open.get()&&!pane.id.startsWith("NATIVE_"))controller.nativeOpenTool(pane.id);
        if(owner.probeHasRecovery())open("EXT:"+LuaFileRecoveryWindow.ID);
    }
    void bind(ImGuiIdeDocument document){
        if(document==null)return;
        document.capture();
        if(bound!=document){pull();bound=document;lastSource=document.source;controller.nativeBind(document);}
        else if(!document.source.equals(lastSource)){controller.nativeBind(document);lastSource=document.source;}
        document.backend=new ImGuiIdeDocument.Backend(){
            public boolean capture(){return bound==document&&pull();}public int cursor(){return bound==document?controller.nativeCursor():document.restoreOffset;}
            public void insert(String s){bind(document);controller.nativeInsert(s);pull();}
            public void replace(int a,int b,String s){bind(document);controller.nativeReplace(a,b,s);pull();}
            public boolean dirty(){return controller.nativeDirty(document.path,!document.lua(),document.kind==ImGuiIdeDocument.Kind.RESOURCEPACK);}
        };
    }
    boolean pull(){
        if(synchronizing)return false;synchronizing=true;
        try{
            var snapshot=controller.nativeSnapshot();if(snapshot==null)return false;
            if(bound==null||!snapshot.name().equals(bound.path)||snapshot.resource()!=(bound.kind==ImGuiIdeDocument.Kind.RESOURCEPACK)||snapshot.json()!=!bound.lua()){
                bound=owner.acceptToolDocument(snapshot);lastSource=snapshot.source();return true;
            }
            bound.saved=snapshot.saved();
            if(!snapshot.source().equals(lastSource)&&bound.source.equals(lastSource)&&!bound.binary()){
                bound.update(snapshot.source());lastSource=bound.source;bound.restoreOffset=snapshot.cursor();return true;
            }
            bound.restoreOffset=snapshot.cursor();
            return false;
        }finally{synchronizing=false;}
    }
    void tick(){ImGuiIdeRuntime.inContext(()->{
        if(bound!=null)bind(bound);controller.tick();pull();syncOpen();
        for(var tool:controller.nativeTools())panes.computeIfAbsent(tool.id(),id->new Pane(id,tool.title(),false));
    });}
    private void syncOpen(){
        var open=Set.copyOf(controller.nativeOpenTools());
        for(var pane:panes.values()){if(pane.id.startsWith("NATIVE_"))continue;boolean showing=open.contains(pane.id);if(showing&&!pane.open.get())focusNext=pane.id;pane.open.set(showing);}
        String activated=controller.nativeTakeActivatedTool();if(activated!=null&&open.contains(activated))focusNext=activated;
    }
    void open(String id){var pane=panes.get(id);if(pane==null)return;if(bound!=null)bind(bound);if(!pane.open.get()&&!id.startsWith("NATIVE_"))controller.nativeOpenTool(id);pane.open.set(true);pane.closing=false;focusNext=id;}
    /** Starts the fade-out; the window and its controller tool are released once the animation finishes (see draw). */
    void close(String id){var pane=panes.get(id);if(pane==null)return;if(pane.open.get())pane.closing=true;else if(!id.startsWith("NATIVE_"))controller.nativeCloseTool(id);}
    boolean isOpen(String id){var pane=panes.get(id);return pane!=null&&pane.open.get()&&!pane.closing;}
    void menu(String id){var pane=panes.get(id);if(pane==null)return;if(ImGui.menuItem(pane.title,"",isOpen(id))){if(isOpen(id))close(id);else open(id);}}
    void allMenus(){for(var pane:panes.values())menu(pane.id);}
    void beginFrame(){codeArea.beginFrame();if(split!=null)split.beginFrame();activePane="";}
    private SplitEditorPane split;
    private WelcomePanel welcome;
    SplitEditorPane probeSplit(){return split;}
    /** The split view when it is open and focused, so keys and characters go to it instead of the main editor. */
    SplitEditorPane focusedSplit(){return split!=null&&isOpen(SplitEditorPane.ID)&&split.focused()?split:null;}
    private String windowTitle(Pane pane){return pane.title+"###tool_"+pane.id;}
    void dockDefaults(int left,int right,int bottom){for(var pane:panes.values())if(!pane.id.equals("NATIVE_GAMEVIEW"))imgui.internal.ImGui.dockBuilderDockWindow(windowTitle(pane),switch(pane.id){case "FILES","DATAPACK_FILES","RESOURCE_FILES"->left;case "OUTPUT","VARIABLES","NATIVE_SEARCH","NATIVE_DIFF","NATIVE_TERMINAL","NATIVE_AUDIT"->bottom;default->right;});}
    void draw(){
        for(var pane:List.copyOf(panes.values())){
            if(!pane.open.get()){motion.reset(pane.id);pane.closing=false;continue;}
            if(pane.closing&&motion.faded(pane.id)){
                pane.open.set(false);pane.closing=false;motion.reset(pane.id);
                if(!pane.id.startsWith("NATIVE_"))controller.nativeCloseTool(pane.id);
                continue;
            }
            if(pane.id.equals("NATIVE_GAMEVIEW"))ImGui.setNextWindowPos(120,70,ImGuiCond.FirstUseEver);
            boolean smallWindow=pane.id.equals("NATIVE_GAMEVIEW");ImGui.setNextWindowSize(smallWindow?500:650,smallWindow?320:470,ImGuiCond.FirstUseEver);ImGui.setNextWindowSizeConstraints(140,90,Float.MAX_VALUE,Float.MAX_VALUE);
            if(focusNext.equals(pane.id)){ImGui.setNextWindowFocus();focusNext="";}
            motion.begin(pane.id,pane.closing);
            boolean visible=ImGui.begin(windowTitle(pane),pane.open,ImGuiWindowFlags.NoCollapse|(owner.layoutLocked()&&pane.docked?ImGuiWindowFlags.NoMove:0));
            motion.observe(pane.id);
            pane.docked=ImGui.isWindowDocked();
            if(visible){if(ImGui.isWindowFocused(ImGuiFocusedFlags.ChildWindows))activePane=pane.id;if(bound!=null)bind(bound);ImGui.pushID(pane.id);if(pane.id.equals("NATIVE_SETTINGS"))nativePanels.settings();else if(pane.id.equals("NATIVE_DEBUG"))nativePanels.debug();else if(pane.id.equals("NATIVE_GAMEVIEW"))gameView.draw();else if(pane.id.equals("NATIVE_OUTLINE"))outline.draw();else if(pane.id.equals("NATIVE_SEARCH"))search.draw();else if(pane.id.equals("NATIVE_DIFF"))diff.draw();else if(pane.id.equals(TerminalPanel.ID))terminal.draw();else if(pane.id.equals(AuditLogPanel.ID))audit.draw();else if(pane.id.equals(WelcomePanel.ID)){if(welcome==null)welcome=new WelcomePanel(owner,preferences);welcome.draw();}else if(pane.id.equals(SplitEditorPane.ID)){if(split==null){split=new SplitEditorPane(owner);split.area().onExtensionCommand(owner::runExtension);}split.draw(owner.openDocuments(),owner.currentDocument());}else if(pane.id.equals(BackupPanel.ID)){if(backups==null)backups=new BackupPanel(owner);backups.draw();}else controller.nativeDrawToolImGui(pane.id);ImGui.popID();pane.frames++;pull();}
            ImGui.end();motion.end();
            // The title-bar X only clears the flag; keep drawing while the window fades out, then release it above.
            if(!pane.open.get()){pane.open.set(true);pane.closing=true;}
        }
        motion.drawGhosts();
        nativePanels.overlay();
        syncOpen();
    }
    void drawCode(ImGuiIdeDocument document){bind(document);if(document.binary())controller.nativeDrawBinaryImGui();else codeArea.render(document);}
    // All ordinary input is owned by ImGui, not hidden Minecraft input boxes.
    boolean mouseClicked(MouseButtonEvent event,boolean twice){return false;}
    boolean mouseReleased(MouseButtonEvent event){return false;}
    boolean mouseDragged(MouseButtonEvent event,double x,double y){return false;}
    boolean scroll(double x,double y,double h,double v){return terminal.scroll(v)||codeArea.scroll(h,v);}
    /** What extension commands see and may change: the active editor tab. Valid only while the command runs. */
    wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi.EditorContext editorContext(){
        return new wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi.EditorContext(){
            private String text(){if(bound==null||bound.binary())return "";bound.capture();return bound.source;}
            public String documentName(){return bound==null?"":bound.path;}
            public String source(){return text();}
            public String selectedText(){String s=text();int a=Math.clamp(controller.nativeAnchor(),0,s.length()),b=Math.clamp(controller.nativeCursor(),0,s.length());return s.substring(Math.min(a,b),Math.max(a,b));}
            public int caretOffset(){return Math.clamp(controller.nativeCursor(),0,text().length());}
            public int caretLine(){String s=text();int caret=caretOffset(),line=1;for(int i=0;i<caret;i++)if(s.charAt(i)=='\n')line++;return line;}
            public boolean insertText(String s){if(bound==null||bound.binary()||s==null)return false;bind(bound);controller.nativeInsert(s);pull();return true;}
            public void copyText(String s){Minecraft.getInstance().keyboardHandler.setClipboard(s==null?"":s);}
            public void notice(String message){owner.notice(message);}
            public boolean openWindow(String id){return owner.setExtensionWindowOpen(id,true);}
        };
    }
    boolean terminalCharacter(CharacterEvent event){return terminal.character(event);}
    boolean terminalKey(KeyEvent event){return terminal.key(event);}
    /** The focused extension pane when it still uses the legacy Content API (it expects raw keyboard events). */
    private LuaExtensionWindow legacyWindow(){
        if(!activePane.startsWith("EXT:")||!isOpen(activePane))return null;
        var window=controller.nativeExtension(activePane);
        return window!=null&&window.legacyContent()?window:null;
    }
    boolean character(CharacterEvent event){
        var legacy=legacyWindow();if(legacy!=null&&legacy.charTyped(event))return true;
        var splitView=focusedSplit();if(splitView!=null)return splitView.character(event);
        if(bound==null||bound.binary()||!codeArea.focused())return false;
        // Auto-closing brackets, multiple carets and wrapping a selection are handled before the character reaches the model.
        if(codeArea.preCharacter(event.codepoint())){pull();return true;}
        controller.nativeCodeCharacter(event);pull();return true;
    }
    boolean key(KeyEvent event,boolean release){
        var legacy=legacyWindow();
        if(legacy!=null){if(release)return legacy.keyReleased(event);if(legacy.keyPressed(event))return true;}
        if(release)return false;
        var splitView=focusedSplit();if(splitView!=null)return splitView.key(event);
        if(nativePanels.key(event))return true;
        boolean contextual=event.hasControlDown()&&event.key()==GLFW.GLFW_KEY_I||event.key()>=GLFW.GLFW_KEY_F1&&event.key()<=GLFW.GLFW_KEY_F12&&(event.modifiers()&7)!=0;
        if(!contextual){boolean handled=codeArea.key(event);pull();syncOpen();return handled;}
        if(bound!=null)bind(bound);controller.nativePrepare("CODE",900,600,true);controller.keyPressed(event);pull();syncOpen();return true;
    }
    /** Esc first leaves the HUD layout editor when it is open. */
    void beginLayoutEditing(){nativePanels.beginLayoutEditing();}
    /** Probe helper: whether the layout editor is currently open (the argument only keeps the call explicit). */
    boolean endLayoutEditingProbe(boolean ignored){return nativePanels.layoutEditing();}
    boolean endLayoutEditing(){if(!nativePanels.layoutEditing())return false;nativePanels.endLayoutEditing();return true;}
    boolean escape(){
        if(activePane.isEmpty()||!isOpen(activePane))return false;var pane=panes.get(activePane);
        if(pane!=null&&(!pane.docked||controller.nativeShortcutTool(activePane))){close(activePane);focusCode();return true;}return false;
    }
    void command(String command){
        if(bound!=null)bind(bound);
        if(command.equals("search")&&bound!=null&&!bound.binary()){codeArea.find();return;}
        controller.nativeCommand(command);pull();syncOpen();
    }
    void editorShortcut(int key,boolean shift){
        if(bound==null||bound.binary())return;codeArea.editKey(key,shift);pull();
    }
    CompletableFuture<Void> save(ImGuiIdeDocument document){
        if(document.saving&&document.saveTask!=null)return document.saveTask;
        bind(document);String snapshot=document.source;document.saving=true;var task=controller.nativeSave();document.saveTask=task;
        task.whenComplete((v,e)->Minecraft.getInstance().execute(()->{document.saving=false;if(e==null)document.saved=snapshot;else if(ScriptManager.isConflict(e))owner.saveConflict(document,ScriptManager.conflictText(e));}));return task;
    }
    void forget(ImGuiIdeDocument document,boolean discard){controller.nativeForget(document,discard);document.backend=null;if(bound==document){bound=null;lastSource="";}}
    void persist(){preferences.setProperty("tool.initialized","true");for(var pane:panes.values())preferences.setProperty("tool."+pane.id,Boolean.toString(pane.open.get()&&!pane.closing));}
    List<String> ids(){return List.copyOf(panes.keySet());}
    int frames(String id){return panes.get(id).frames;}
    void focusCode(){if(bound!=null&&!bound.binary())codeArea.focus();}
    void focus(String id){focusNext=id;}
    @Override public void close(){pull();controller.nativeDispose();if(split!=null)split.close();NativeUi.clear();}
    private static final class Pane{final String id,title;final ImBoolean open;int frames;boolean docked,closing;Pane(String id,String title,boolean open){this.id=id;this.title=title;this.open=new ImBoolean(open);}}
}
