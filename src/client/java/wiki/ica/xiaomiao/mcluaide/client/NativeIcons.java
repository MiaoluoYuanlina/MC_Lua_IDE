package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;

/** Vector toolbar glyphs; no Minecraft button sprites or textures. */
final class NativeIcons {
    private NativeIcons() { }
    static boolean button(String id,String hint,boolean selected){
        float x=ImGui.getCursorScreenPosX(),y=ImGui.getCursorScreenPosY(),size=Math.max(25,ImGui.getTextLineHeight()+12);
        boolean clicked=ImGui.invisibleButton(id,size,size),hover=ImGui.isItemHovered();var d=ImGui.getWindowDrawList();
        d.addRectFilled(x,y,x+size,y+size,selected?0xFF906746:hover?0xFF724D32:0xFF513525,4);
        float k=size/30f;int color=0xFFF0E6DA;
        switch(id){
            case "PENCIL","BRUSH"->{d.addLine(x+9*k,y+20*k,x+22*k,y+7*k,color,3*k);d.addLine(x+8*k,y+23*k,x+12*k,y+21*k,color,2*k);if(id.equals("BRUSH"))d.addTriangleFilled(x+8*k,y+19*k,x+5*k,y+25*k,x+13*k,y+23*k,color);}
            case "ERASER"->{d.addQuad(x+7*k,y+18*k,x+17*k,y+8*k,x+24*k,y+15*k,x+14*k,y+25*k,color,2*k);d.addLine(x+9*k,y+17*k,x+17*k,y+24*k,color,2*k);}
            case "FILL"->{d.addQuad(x+6*k,y+15*k,x+14*k,y+6*k,x+23*k,y+15*k,x+14*k,y+23*k,color,2*k);d.addLine(x+6*k,y+15*k,x+23*k,y+15*k,color,2*k);d.addCircleFilled(x+23*k,y+24*k,2*k,color);}
            case "PICKER"->{d.addLine(x+8*k,y+23*k,x+23*k,y+8*k,color,3*k);d.addLine(x+17*k,y+7*k,x+24*k,y+14*k,color,3*k);}
            case "folder"->{d.addRect(x+5*k,y+10*k,x+25*k,y+24*k,color,2*k);d.addLine(x+6*k,y+7*k,x+14*k,y+7*k,color,2*k);d.addLine(x+5*k,y+7*k,x+5*k,y+11*k,color,2*k);d.addLine(x+14*k,y+7*k,x+17*k,y+10*k,color,2*k);}
            case "refresh"->{d.addCircle(x+15*k,y+15*k,8*k,color,0,2*k);d.addTriangleFilled(x+21*k,y+4*k,x+26*k,y+10*k,x+18*k,y+10*k,color);}
            default->{d.addRect(x+7*k,y+5*k,x+23*k,y+25*k,color,2*k);d.addLine(x+11*k,y+15*k,x+19*k,y+15*k,color,2*k);d.addLine(x+15*k,y+11*k,x+15*k,y+19*k,color,2*k);}
        }
        NativeUi.hint(hint);return clicked;
    }
}
