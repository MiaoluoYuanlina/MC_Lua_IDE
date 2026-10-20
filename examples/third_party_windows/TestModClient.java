package com.example.testmod;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import imgui.ImGui;
import imgui.type.ImString;

/** compileOnly("io.github.spair:imgui-java-binding:1.92.7.1"); natives are supplied by MC Lua IDE. */
public final class TestModClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        McLuaIdeWindowApi.window("test_mod","script_tools")
                .title(Component.literal("TestMod 脚本工具"))
                .size(360,220).minimumSize(180,120)
                .content(ToolContent::new).register();
    }
    private static final class ToolContent implements McLuaIdeWindowApi.NativeContent {
        private final McLuaIdeWindowApi.Context context;
        private final ImString code=new ImString("print(\"Hello TestMod!\")",5000);
        ToolContent(McLuaIdeWindowApi.Context context){this.context=context;}
        @Override public void renderImGui(){
            ImGui.textWrapped("输入代码后插入当前编辑文件");ImGui.setNextItemWidth(-1);
            ImGui.inputTextMultiline("##code",code,-1,110);
            if(ImGui.button("插入代码"))context.insertText(code.get()+"\n");
            ImGui.sameLine();if(ImGui.button("复制"))context.copyText(code.get());
            ImGui.textWrapped("当前："+context.documentName());
        }
    }
}
