package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Native draw-list code view. The existing editing model supplies edits/undo, never GUI rendering. */
final class NativeCodeArea {
    private record Row(int start,int end,int line,float[] advances) { float width(){return advances[advances.length-1];} }
    private final LuaIdeScreen model;
    private final Map<Integer,Float> glyphs=new HashMap<>();
    private List<Row> rows=List.of();
    private String source="",lowerSource="",document="",layoutKey="";
    private boolean focused,dragging,searchOpen,reveal,hovered,focusRequested,searchFocus;
    private int anchor,lastCursor=-1,hoverOffset;
    private float originX,originY,gutter,lineHeight,scrollX,scrollY,viewWidth,viewHeight;
    private String search="";
    private LuaAiAgent.Mark hoveredMark;
    private float fontSize;
    NativeCodeArea(LuaIdeScreen model){this.model=model;}
    private float caretX,caretTop;
    /** Screen position of the caret: x, top of its line, and line height. Valid after render(). */
    float[] caret(){return new float[]{caretX,caretTop,lineHeight};}
    boolean focused(){return focused;}
    void beginFrame(){focused=false;hovered=false;}
    void focus(){reveal=true;focused=true;focusRequested=true;}
    void find(){searchOpen=true;searchFocus=true;int a=model.nativeAnchor(),b=model.nativeCursor();if(a!=b)search=source.substring(Math.min(a,b),Math.max(a,b));}
    boolean dismissMarks(){if(hoveredMark==null)return false;model.nativeDismissMarks();hoveredMark=null;return true;}
    void render(ImGuiIdeDocument doc){
        if(!document.equals(doc.key())){document=doc.key();lastCursor=-1;reveal=true;}
        if(searchOpen){
            if(searchFocus){ImGui.setKeyboardFocusHere();searchFocus=false;}
            search=NativeUi.input("##find",search);
            NativeUi.action("上一处",()->findNext(-1));ImGui.sameLine();NativeUi.action("下一处",()->findNext(1));ImGui.sameLine();NativeUi.action("关闭搜索",()->searchOpen=false);
        }
        var settings=model.nativePersonalization();
        lineHeight=Math.max(6,ImGui.getTextLineHeight()*settings.lineHeight+3);
        gutter=ImGui.calcTextSize("000000").x+28;
        int flags=ImGuiWindowFlags.NoNavInputs|(settings.codeWordWrap?0:ImGuiWindowFlags.HorizontalScrollbar);
        ImGui.pushStyleColor(ImGuiCol.ChildBg,NativeUi.rgba(settings.theme.background()));
        ImGui.beginChild("native_code",0,Math.max(40,ImGui.getContentRegionAvailY()),true,flags);
        if(focusRequested){ImGui.setWindowFocus();focusRequested=false;}
        focused=ImGui.isWindowFocused();hovered=ImGui.isWindowHovered();
        viewWidth=Math.max(30,ImGui.getWindowWidth()-ImGui.getStyle().getWindowPaddingX()*2);
        viewHeight=Math.max(30,ImGui.getWindowHeight()-ImGui.getStyle().getWindowPaddingY()*2);
        float size=ImGui.getFontSize();if(fontSize!=size){glyphs.clear();fontSize=size;}
        var folds=model.nativeFolds();if(folds!=null)folds.model(doc.source);
        String key=doc.key()+":"+doc.revision+":"+size+":"+viewWidth+":"+settings.codeWordWrap+":"+settings.cellWidth+":"+(folds==null?0:folds.revision());
        if(!key.equals(layoutKey)){source=doc.source;lowerSource=source.toLowerCase(Locale.ROOT);layoutKey=key;layout(settings.codeWordWrap?Math.max(20,viewWidth-gutter-18):Float.MAX_VALUE,settings.cellWidth,folds);}
        int cursor=model.nativeCursor(),selection=model.nativeAnchor();
        if(cursor!=lastCursor){reveal=true;lastCursor=cursor;}
        if(reveal&&!rows.isEmpty()){
            int row=rowAtOffset(cursor);float yy=row*lineHeight,xx=gutter+advance(rows.get(row),cursor);
            if(yy<ImGui.getScrollY())ImGui.setScrollY(yy);else if(yy+lineHeight>ImGui.getScrollY()+viewHeight)ImGui.setScrollY(yy-viewHeight+lineHeight);
            if(!settings.codeWordWrap){if(xx<ImGui.getScrollX()+gutter)ImGui.setScrollX(Math.max(0,xx-gutter));else if(xx>ImGui.getScrollX()+viewWidth-25)ImGui.setScrollX(xx-viewWidth+25);}
            reveal=false;
        }
        scrollX=ImGui.getScrollX();scrollY=ImGui.getScrollY();originX=ImGui.getCursorScreenPosX();originY=ImGui.getCursorScreenPosY();
        {int caretIndex=rowAtOffset(cursor);caretX=originX+gutter+advance(rows.get(caretIndex),cursor);caretTop=originY+caretIndex*lineHeight;}
        var draw=ImGui.getWindowDrawList();float maxWidth=viewWidth;
        if(!settings.codeWordWrap)for(var row:rows)maxWidth=Math.max(maxWidth,row.width()+gutter+40);
        ImGui.dummy(maxWidth,Math.max(viewHeight,rows.size()*lineHeight+16));
        float left=ImGui.getWindowPosX()+1,top=ImGui.getWindowPosY()+1,right=left+ImGui.getWindowWidth()-12,bottom=top+ImGui.getWindowHeight()-12;
        draw.pushClipRect(left,top,right,bottom,true);
        LuaEditorBackground.renderImGui(settings,left,top,right,bottom);
        draw.addRectFilled(left,top,originX+scrollX+gutter-7,bottom,NativeUi.rgba(settings.theme.background()));
        int first=Math.max(0,(int)(scrollY/lineHeight)),last=Math.min(rows.size(),first+(int)(viewHeight/lineHeight)+3);
        var errors=doc.analysis.diagnostics();var marks=model.nativeMarks();var breakpoints=model.nativeBreakpoints();int paused=model.nativePausedLine();
        hoveredMark=null;hoverOffset=-1;
        float mx=ImGui.getMousePosX(),my=ImGui.getMousePosY();
        String selected=selection==cursor?"":source.substring(Math.min(selection,cursor),Math.max(selection,cursor));
        for(int index=first;index<last;index++){
            var row=rows.get(index);float y=originY+index*lineHeight,x=originX+gutter;
            int visibleStart=offsetAt(row,Math.max(0,left-x),false),visibleEnd=Math.min(row.end,offsetAt(row,Math.max(0,right-x),true)+2);
            boolean main=index==0||rows.get(index-1).line!=row.line;
            if(paused==row.line)draw.addRectFilled(x,y,right,y+lineHeight,0x5534BAFF);
            boolean error=errors.stream().anyMatch(d->d.line()==row.line&&d.severity()==LuaCodeAnalyzer.Severity.ERROR);
            float numberX=originX+scrollX+4;
            if(main){
                draw.addText(numberX,y,error?0xFF7777FF:ImGui.getColorU32(ImGuiCol.TextDisabled),Integer.toString(row.line));
                if(breakpoints.contains(row.line))draw.addCircleFilled(numberX+gutter-28,y+lineHeight*.4f,4,0xFF6666F0);
                var fold=folds==null?null:folds.model(source).atLine(row.line);
                if(fold!=null)draw.addText(numberX+gutter-18,y,ImGui.getColorU32(ImGuiCol.Text),folds.collapsed(fold)?">":"v");
            }
            if(!selected.isEmpty()&&selected.length()<4096){int p=source.indexOf(selected,Math.max(row.start,visibleStart-selected.length()));while(p>=row.start&&p<visibleEnd){rect(draw,row,p,Math.min(row.end,p+selected.length()),x,y,0x446DAFE8);p=source.indexOf(selected,p+Math.max(1,selected.length()));}}
            if(!search.isEmpty()&&searchOpen){int p=lowerSource.indexOf(search.toLowerCase(Locale.ROOT),Math.max(row.start,visibleStart-search.length()));while(p>=row.start&&p<visibleEnd){rect(draw,row,p,Math.min(row.end,p+search.length()),x,y,0x663DC9F5);p=lowerSource.indexOf(search.toLowerCase(Locale.ROOT),p+Math.max(1,search.length()));}}
            if(cursor!=selection)rect(draw,row,Math.max(row.start,Math.min(cursor,selection)),Math.min(row.end,Math.max(cursor,selection)),x,y,0x99674638);
            for(var mark:marks){int a=Math.max(row.start,mark.start()),b=Math.min(row.end,mark.end());if(a>=b)continue;
                rect(draw,row,a,b,x,y,switch(mark.severity()){case "error"->0x667257E9;case "warning"->0x6646AAD7;case "mcp"->0x66A5B943;default->0x66C78455;});
                if(hovered&&!ImGui.isMouseDown(0)&&my>=y&&my<y+lineHeight&&mx>=x+advance(row,a)&&mx<x+advance(row,b))hoveredMark=mark;
            }
            for(var diagnostic:errors){int a=Math.max(row.start,diagnostic.start()),b=Math.min(row.end,diagnostic.end());if(a>=b)continue;
                float ax=x+advance(row,a),bx=x+advance(row,b);draw.addLine(ax,y+lineHeight-2,bx,y+lineHeight-2,diagnostic.severity()==LuaCodeAnalyzer.Severity.ERROR?0xFF7777FF:0xFF55CCEE,1);
            }
            final int[] offset={visibleStart};
            model.nativeHighlight(visibleStart,visibleEnd,row.line).visit((style,text)->{
                int color=style.getColor()==null?settings.syntaxColor(LuaSyntaxHighlighter.ColorRole.NORMAL):style.getColor().getValue();
                for(int p=0;p<text.length();){int cp=text.codePointAt(p),count=Character.charCount(cp);if(cp!='\t')draw.addText(x+advance(row,offset[0]),y,NativeUi.rgba(0xFF000000|color),new String(Character.toChars(cp)));offset[0]+=count;p+=count;}return Optional.empty();
            },Style.EMPTY);
            if(folds!=null&&main){var fold=folds.model(source).atLine(row.line);if(fold!=null&&folds.collapsed(fold))draw.addText(x+row.width()+10,y,ImGui.getColorU32(ImGuiCol.TextDisabled),"…  "+(fold.endLine()-fold.line())+" lines");}
            if(cursor>=row.start&&(cursor<row.end||cursor==row.end&&(index==rows.size()-1||rows.get(index+1).start!=cursor))&&focused){
                float cx=x+advance(row,cursor),cw=Math.max(1,settings.cursorWidth);int color=NativeUi.rgba(settings.cursorColor);if(settings.cursorBlink){float phase=(float)(0.5+0.5*Math.cos(System.nanoTime()/1.0e9*Math.PI*1.6));int alpha=(int)(((color>>>24)&255)*(0.12f+0.88f*phase));color=(color&0x00FFFFFF)|(alpha<<24);}
                switch(settings.cursorShape){case BAR->draw.addRectFilled(cx,y,cx+cw,y+lineHeight-2,color);case UNDERLINE->draw.addRectFilled(cx,y+lineHeight-3,cx+glyph('M')*settings.cellWidth,y+lineHeight-1,color);case BLOCK->draw.addRectFilled(cx,y,cx+glyph('M')*settings.cellWidth,y+lineHeight-2,color&0x66FFFFFF);}
            }
            if(hovered&&my>=y&&my<y+lineHeight)hoverOffset=offsetAt(row,mx-x,false);
        }
        draw.popClipRect();
        if(hovered&&ImGui.isMouseClicked(0)){
            focused=true;ImGui.setWindowFocus();int index=Math.clamp((int)((my-originY)/lineHeight),0,Math.max(0,rows.size()-1));var row=rows.get(index);
            if(mx<originX+scrollX+gutter-3){if(mx>=originX+scrollX+gutter-23&&folds!=null&&folds.model(source).atLine(row.line)!=null){model.nativeToggleFold(row.line);layoutKey="";}else model.nativeToggleBreakpoint(row.line);}
            else {int hit=offsetAt(row,mx-(originX+gutter),true);if(ImGui.getIO().getKeyCtrl()){String symbol=symbolAt(hit);if(LuaApiDocumentation.find(symbol)!=null)model.nativeOpenApi(symbol);}
                else if(ImGui.isMouseDoubleClicked(0))model.nativeSmartSelect(offsetAt(row,mx-(originX+gutter),false));
                else {anchor=ImGui.getIO().getKeyShift()?selection:hit;model.nativeSelect(anchor,hit);dragging=true;}}
        }
        if(dragging){if(!ImGui.isMouseDown(0))dragging=false;else{int index=Math.clamp((int)((my-originY)/lineHeight),0,rows.size()-1);model.nativeSelect(anchor,offsetAt(rows.get(index),mx-(originX+gutter),true));if(my<top)ImGui.setScrollY(Math.max(0,scrollY-lineHeight));else if(my>bottom)ImGui.setScrollY(scrollY+lineHeight);}}
        if(hoveredMark!=null){ImGui.setTooltip(hoveredMark.message()+"\nEsc · "+LuaUiText.text("清除全部标记"));}
        else if(hovered&&hoverOffset>=0&&!ImGui.isMouseDown(0)){
            var api=LuaApiDocumentation.find(symbolAt(hoverOffset));
            if(api!=null){ImGui.beginTooltip();ImGui.pushTextWrapPos(520);ImGui.textUnformatted(api.signature());ImGui.separator();ImGui.textWrapped("作用："+api.summary());ImGui.textWrapped("返回："+api.returns());for(int i=0;i<api.parameters().size();i++)ImGui.textWrapped("参数"+(i+1)+"："+api.describe(api.parameters().get(i)));ImGui.popTextWrapPos();ImGui.endTooltip();}
            for(var diagnostic:errors)if(hoverOffset>=diagnostic.start()&&hoverOffset<diagnostic.end())ImGui.setTooltip(diagnostic.message());
        }
        if(ImGui.beginPopupContextWindow("code_actions",ImGuiPopupFlags.MouseButtonRight|ImGuiPopupFlags.NoOpenOverExistingPopup)){
            for(var action:List.of("复制","剪切","粘贴","删除","撤销","搜索"))if(ImGui.menuItem(LuaUiText.text(action)))context(action);ImGui.endPopup();
        }
        if(focused)ImGui.getIO().setWantTextInput(true);
        ImGui.endChild();ImGui.popStyleColor();doc.capture();
    }
    private void context(String action){switch(action){case "搜索"->find();case "删除"->model.nativeInsert("");case "复制"->editKey(GLFW.GLFW_KEY_C,false);case "剪切"->editKey(GLFW.GLFW_KEY_X,false);case "粘贴"->editKey(GLFW.GLFW_KEY_V,false);case "撤销"->editKey(GLFW.GLFW_KEY_Z,false);default->{}}reveal=true;}
    boolean scroll(double horizontal,double vertical){
        if(!hovered)return false;var settings=model.nativePersonalization();long window=net.minecraft.client.Minecraft.getInstance().getWindow().handle();
        boolean ctrl=GLFW.glfwGetKey(window,GLFW.GLFW_KEY_LEFT_CONTROL)==GLFW.GLFW_PRESS||GLFW.glfwGetKey(window,GLFW.GLFW_KEY_RIGHT_CONTROL)==GLFW.GLFW_PRESS;
        if(ctrl)return false;
        boolean shift=GLFW.glfwGetKey(window,GLFW.GLFW_KEY_LEFT_SHIFT)==GLFW.GLFW_PRESS||GLFW.glfwGetKey(window,GLFW.GLFW_KEY_RIGHT_SHIFT)==GLFW.GLFW_PRESS;
        if(shift&&!settings.codeWordWrap)ImGuiIdeRuntime.scroll(vertical*settings.codeWheelSensitivity(),0);
        else ImGuiIdeRuntime.scroll(horizontal,vertical*settings.codeWheelSensitivity());return true;
    }
    void editKey(int key,boolean shift){model.nativeCodeKey(new KeyEvent(key,0,GLFW.GLFW_MOD_CONTROL|(shift?GLFW.GLFW_MOD_SHIFT:0)));reveal=true;}
    boolean key(KeyEvent event){
        if(!focused)return false;
        int key=event.key();if(key>=GLFW.GLFW_KEY_LEFT_SHIFT&&key<=GLFW.GLFW_KEY_RIGHT_SUPER)return false;
        if(event.hasControlDown()&&key==GLFW.GLFW_KEY_F){find();return true;}
        if(!event.hasControlDown()&&(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN||key==GLFW.GLFW_KEY_HOME||key==GLFW.GLFW_KEY_END||key==GLFW.GLFW_KEY_PAGE_UP||key==GLFW.GLFW_KEY_PAGE_DOWN)){
            if(rows.isEmpty())return true;int cursor=model.nativeCursor(),line=rowAtOffset(cursor),index=line;var row=rows.get(line);
            if(key==GLFW.GLFW_KEY_UP)index--;if(key==GLFW.GLFW_KEY_DOWN)index++;if(key==GLFW.GLFW_KEY_PAGE_UP)index-=(int)(viewHeight/lineHeight);if(key==GLFW.GLFW_KEY_PAGE_DOWN)index+=(int)(viewHeight/lineHeight);
            index=Math.clamp(index,0,rows.size()-1);int target=key==GLFW.GLFW_KEY_HOME?row.start:key==GLFW.GLFW_KEY_END?row.end:offsetAt(rows.get(index),advance(row,cursor),true);
            model.nativeSelect(event.hasShiftDown()?model.nativeAnchor():target,target);reveal=true;return true;
        }
        model.nativeCodeKey(event);reveal=true;return true;
    }
    private void findNext(int direction){if(search.isEmpty())return;String lower=source.toLowerCase(Locale.ROOT),needle=search.toLowerCase(Locale.ROOT);int at=direction>0?lower.indexOf(needle,Math.min(source.length(),model.nativeCursor()+1)):lower.lastIndexOf(needle,Math.max(0,model.nativeCursor()-1));if(at<0)at=direction>0?lower.indexOf(needle):lower.lastIndexOf(needle);if(at>=0){model.nativeSelect(at,at+search.length());reveal=true;}}
    String symbolAt(int offset){int start=Math.clamp(offset,0,source.length()),end=start;while(start>0&&symbolChar(source.charAt(start-1)))start--;while(end<source.length()&&symbolChar(source.charAt(end)))end++;return source.substring(start,end);}
    private boolean symbolChar(char c){return Character.isJavaIdentifierPart(c)||c=='.'||c==':';}
    private void layout(float width,float cell,LuaFunctionFolding.State folds){
        var next=new ArrayList<Row>();int start=0,line=1;
        while(start<=source.length()){
            int end=source.indexOf('\n',start);if(end<0)end=source.length();
            if(folds==null||!folds.hidden(start)){
                int part=start;float x=0;var advances=new ArrayList<Float>();advances.add(0f);
                for(int i=start;i<end;){int cp=source.codePointAt(i),count=Character.charCount(cp);float advance=cp=='\t'?glyph(' ')*4*cell-x%(glyph(' ')*4*cell):glyph(cp)*cell;
                    if(x+advance>width&&i>part){next.add(new Row(part,i,line,floats(advances)));part=i;x=0;advances.clear();advances.add(0f);}
                    for(int c=1;c<count;c++)advances.add(x);x+=advance;advances.add(x);i+=count;
                }next.add(new Row(part,end,line,floats(advances)));
            }
            if(end==source.length())break;start=end+1;line++;
        }rows=next.isEmpty()?List.of(new Row(0,0,1,new float[]{0})):next;
    }
    private float[] floats(List<Float> list){float[] values=new float[list.size()];for(int i=0;i<values.length;i++)values[i]=list.get(i);return values;}
    private float glyph(int cp){return glyphs.computeIfAbsent(cp,c->ImGui.calcTextSize(new String(Character.toChars(c))).x);}
    private float advance(Row row,int offset){return row.advances[Math.clamp(offset-row.start,0,row.advances.length-1)];}
    private int rowAtOffset(int offset){int low=0,high=rows.size()-1;while(low<high){int mid=(low+high+1)>>>1;if(rows.get(mid).start<=offset)low=mid;else high=mid-1;}return low;}
    private int offsetAt(Row row,float x,boolean nearest){int low=0,high=row.advances.length-1;while(low<high){int mid=(low+high)>>>1;float edge=nearest?(row.advances[mid]+row.advances[mid+1])*.5f:row.advances[mid+1];if(edge<x)low=mid+1;else high=mid;}int result=Math.min(row.end,row.start+low);if(result>row.start&&result<source.length()&&Character.isLowSurrogate(source.charAt(result)))result--;return result;}
    private void rect(imgui.ImDrawList draw,Row row,int a,int b,float x,float y,int color){if(a<b)draw.addRectFilled(x+advance(row,a),y,x+advance(row,b),y+lineHeight,color);}
}
