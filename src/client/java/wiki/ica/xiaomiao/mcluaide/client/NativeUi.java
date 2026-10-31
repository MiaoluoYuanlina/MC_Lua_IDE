package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.*;
import java.util.*;
import java.util.function.Consumer;

/** Native controls only. Business models may be shared, game widgets are never drawn. */
final class NativeUi {
    // Text buffers are re-synchronised from the model every frame, so dropping the least recently used one is harmless.
    // Bounding the map stops widgets with ever-changing IDs from growing it forever.
    private static final int MAX_BUFFERS = 2048;
    private static final Map<Integer, ImString> strings = new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, ImString> eldest) { return size() > MAX_BUFFERS; }
    };
    private static final LinkedHashMap<String,ImGuiMcSurface> media = new LinkedHashMap<>(64,.75f,true);
    private NativeUi() { }
    static String input(String label, String value) { return input(label,value,false,0); }
    static String password(String label,String value) {
        var buffer=strings.computeIfAbsent(ImGui.getID(label),id->new ImString(256));
        if(!buffer.get().equals(value)) buffer.set(value,true);
        ImGui.setNextItemWidth(-1); ImGui.inputText(label,buffer,ImGuiInputTextFlags.Password); return buffer.get();
    }
    static String multiline(String label,String value,float height) { return input(label,value,true,height,false); }
    /** {@code focus} gives the box the keyboard this frame, so the user can type at once. */
    static String multiline(String label,String value,float height,boolean focus) { return input(label,value,true,height,focus); }
    private static String input(String label,String value,boolean multiline,float height) { return input(label,value,multiline,height,false); }
    private static String input(String label,String value,boolean multiline,float height,boolean focus) {
        value=value==null?"":value;
        if(focus) ImGui.setKeyboardFocusHere();
        var buffer=strings.computeIfAbsent(ImGui.getID(label),id->new ImString(256));
        if(!buffer.get().equals(value)) buffer.set(value,true);
        if(multiline) ImGui.inputTextMultiline(label,buffer,-1,height);
        else { ImGui.setNextItemWidth(-1); ImGui.inputText(label,buffer); }
        return buffer.get();
    }
    static String field(String label,String value) { ImGui.textUnformatted(LuaUiText.text(label)); return input("##"+label,value); }
    static String field(String label,String value,boolean focus) { ImGui.textUnformatted(LuaUiText.text(label)); return input("##"+label,value,false,0,focus); }
    static boolean check(String label,boolean value) { var state=new ImBoolean(value); ImGui.checkbox(LuaUiText.text(label),state); return state.get(); }
    static int integer(String label,int value,int min,int max) { var state=new ImInt(value); ImGui.textUnformatted(LuaUiText.text(label)); ImGui.setNextItemWidth(-1); ImGui.inputInt("##"+label,state); return Math.clamp(state.get(),min,max); }
    static double number(String label,double value) { var state=new ImDouble(value); ImGui.textUnformatted(LuaUiText.text(label)); ImGui.setNextItemWidth(-1); ImGui.inputDouble("##"+label,state); return state.get(); }
    static <T> T combo(String label,T value,List<T> values,java.util.function.Function<T,String> names) {
        if(!label.startsWith("##"))ImGui.textUnformatted(LuaUiText.text(label));ImGui.setNextItemWidth(-1);
        if(ImGui.beginCombo("##"+label,value==null?"":names.apply(value))) {
            for(var option:values) if(ImGui.selectable(names.apply(option),Objects.equals(option,value))) value=option;
            ImGui.endCombo();
        }
        return value;
    }
    static <T extends Enum<T>> T combo(String label,T value,T[] values) { return combo(label,value,Arrays.asList(values),entry->LuaUiText.text(entry.name())); }
    static void action(String label,Runnable callback) { if(ImGui.button(LuaUiText.text(label))) callback.run(); }
    static void next(String label){if(ImGui.getItemRectMaxX()+ImGui.calcTextSize(LuaUiText.text(label)).x+ImGui.getStyle().getFramePaddingX()*2+ImGui.getStyle().getItemSpacingX()<ImGui.getWindowPosX()+ImGui.getWindowWidth()-ImGui.getStyle().getWindowPaddingX())ImGui.sameLine();}
    static void hint(String text) { if(ImGui.isItemHovered()) ImGui.setTooltip(LuaUiText.text(text)); }
    static void status(String text) { ImGui.separator(); ImGui.textWrapped(LuaUiText.text(text==null?"":text)); }
    static void copy(String text) { ImGui.setClipboardText(text==null?"":text); }
    /** Read-only text that wraps to the window width (no sideways scrolling); right-click copies it. */
    static void readOnly(String id,String text,float height) {
        String value=text==null?"":text;
        ImGui.beginChild(id+"##readonly",-1,height,true);
        ImGui.textWrapped(value);
        if(ImGui.beginPopupContextWindow()){ if(ImGui.menuItem(LuaUiText.text("复制")))copy(value); ImGui.endPopup(); }
        ImGui.endChild();
    }
    private static final Map<Integer,Integer> logSizes=new HashMap<>();
    /** Wrapped log lines that follow new output: while the view sits at the bottom, new lines scroll into view. */
    static void log(String id,java.util.List<String> lines,float height) {
        int key=ImGui.getID(id);
        ImGui.beginChild(id+"##log",-1,height,true);
        boolean atBottom=ImGui.getScrollY()>=ImGui.getScrollMaxY()-4;
        for(String line:lines)ImGui.textWrapped(line);
        // The view follows the output while it sits at the bottom (the log is capped, so the line count alone can stay constant).
        int previous=logSizes.getOrDefault(key,-1);
        logSizes.put(key,lines.size());
        if(atBottom||previous<0||lines.size()<previous)ImGui.setScrollHereY(1f);
        if(ImGui.beginPopupContextWindow()){ if(ImGui.menuItem(LuaUiText.text("复制全部")))copy(String.join("\n",lines)); ImGui.endPopup(); }
        ImGui.endChild();
    }
    static void code(String id,String source,float height) {
        // Read-only highlighted text drawn run by run with the editor font: the text-editor widget assumed single-width glyphs and garbled Chinese and Japanese.
        ImGui.pushID(id);
        ImGui.beginChild("##code",0,height,true,ImGuiWindowFlags.HorizontalScrollbar);
        ImGuiIdeRuntime.pushEditorFont(ImGui.getFontSize());
        var draw=ImGui.getWindowDrawList();
        float left=ImGui.getCursorScreenPosX(),top=ImGui.getCursorScreenPosY(),lineHeight=ImGui.getTextLineHeightWithSpacing(),widest=0;
        String[] lines=source.replace("\t","    ").stripTrailing().split("\\R",-1);
        for(int row=0;row<lines.length;row++) {
            final float y=top+row*lineHeight; final float[] x={left};
            if(y+lineHeight>=ImGui.getWindowPosY()&&y<=ImGui.getWindowPosY()+ImGui.getWindowHeight()) {
                LuaSyntaxHighlighter.highlightSnippet(lines[row],LuaSyntaxHighlighter.Theme.DARK).visit((style,text)->{
                    int color=style.getColor()==null?0xE6E6E6:style.getColor().getValue();
                    draw.addText(x[0],y,rgba(0xFF000000|color),text); x[0]+=ImGui.calcTextSizeX(text);
                    return Optional.empty();
                },net.minecraft.network.chat.Style.EMPTY);
            } else x[0]+=ImGui.calcTextSizeX(lines[row]);
            widest=Math.max(widest,x[0]-left);
        }
        ImGui.dummy(widest+8,lines.length*lineHeight);
        ImGui.popFont(); ImGui.endChild(); ImGui.popID();
    }
    static void media(String key,int width,int height,java.util.function.Consumer<net.minecraft.client.gui.GuiGraphicsExtractor> drawing) {
        var surface=media.computeIfAbsent(key,id->new ImGuiMcSurface());
        long texture=surface.render(width,height,1,-10000,-10000,drawing);
        ImGui.image(texture,width,height,0,1,1,0);
        if(media.size()>128){ var first=media.entrySet().iterator(); var oldest=first.next(); first.remove(); oldest.getValue().close(); }
    }
    /**
     * Renders old-style Minecraft drawing code into a texture at {@code density} real pixels per logical unit and shows it
     * at {@code logicalWidth * density} x {@code logicalHeight * density}. The mouse position is in logical units.
     */
    static void mediaScaled(String key,int logicalWidth,int logicalHeight,float density,int mouseX,int mouseY,java.util.function.Consumer<net.minecraft.client.gui.GuiGraphicsExtractor> drawing) {
        var surface=media.computeIfAbsent(key,id->new ImGuiMcSurface());
        long texture=surface.render(logicalWidth,logicalHeight,density,mouseX,mouseY,drawing);
        ImGui.image(texture,logicalWidth*density,logicalHeight*density,0,1,1,0);
        if(media.size()>128){ var first=media.entrySet().iterator(); var oldest=first.next(); first.remove(); oldest.getValue().close(); }
    }
    static int rgba(int argb) { return ImGui.getColorU32((argb>>16&255)/255f,(argb>>8&255)/255f,(argb&255)/255f,(argb>>>24)/255f); }
    static int color(String label,int argb) {
        float[] rgba={(argb>>16&255)/255f,(argb>>8&255)/255f,(argb&255)/255f,(argb>>>24)/255f};
        if(!ImGui.colorEdit4(label,rgba,ImGuiColorEditFlags.AlphaBar|ImGuiColorEditFlags.DisplayHex))return argb;
        return Math.round(rgba[3]*255)<<24|Math.round(rgba[0]*255)<<16|Math.round(rgba[1]*255)<<8|Math.round(rgba[2]*255);
    }
    static void clear() { strings.clear(); media.values().forEach(ImGuiMcSurface::close); media.clear(); }
}
