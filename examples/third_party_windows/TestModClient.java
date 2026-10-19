package com.example.testmod;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;

public final class TestModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        McLuaIdeWindowApi.window("test_mod", "script_tools")
            .title(Component.literal("TestMod 脚本工具"))
            .size(360, 220)
            .minimumSize(180, 120)
            .content(ToolContent::new)
            .register();
    }

    private static final class ToolContent implements McLuaIdeWindowApi.Content {
        private final McLuaIdeWindowApi.Context context;
        private final EditBox input;
        private final Button insert;
        private final Button copy;
        private int width = 200;

        ToolContent(McLuaIdeWindowApi.Context context) {
            this.context = context;
            input = new EditBox(context.font(), 8, 28, 200, 20, Component.literal("代码"));
            input.setMaxLength(5000);
            input.setValue("print(\"Hello TestMod!\")");
            insert = Button.builder(Component.literal("插入代码"), button ->
                context.insertText(input.getValue() + "\n")).bounds(8, 56, 100, 20).build();
            copy = Button.builder(Component.literal("复制"), button ->
                context.copyText(input.getValue())).bounds(112, 56, 64, 20).build();
        }

        @Override
        public void resize(int width, int height) {
            this.width = width;
            input.setWidth(Math.max(1, width - 16));
            insert.setWidth(Math.min(100, Math.max(40, (width - 20) / 2)));
            copy.setX(insert.getRight() + 4);
            copy.setWidth(Math.max(1, Math.min(64, width - copy.getX() - 8)));
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            graphics.text(context.font(), "输入代码后插入当前编辑文件", 8, 8, 0xFFC8D9EA);
            input.extractRenderState(graphics, mouseX, mouseY, 0);
            insert.extractRenderState(graphics, mouseX, mouseY, 0);
            copy.extractRenderState(graphics, mouseX, mouseY, 0);
            graphics.text(context.font(),
                context.font().plainSubstrByWidth("当前：" + context.documentName(), Math.max(1, width - 16)),
                8, 90, 0xFF9CB1C8);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            boolean selected = input.mouseClicked(event, doubleClick);
            input.setFocused(selected);
            return selected || insert.mouseClicked(event, doubleClick) || copy.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return input.isFocused() && input.keyPressed(event);
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return input.isFocused() && input.charTyped(event);
        }

        @Override
        public void focusChanged(boolean focused) {
            if (!focused) input.setFocused(false);
        }
    }
}
