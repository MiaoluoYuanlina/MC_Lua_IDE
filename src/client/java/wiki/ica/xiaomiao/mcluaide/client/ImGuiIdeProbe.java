package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Opt-in real-GL smoke test in its own run directory. Never enters or changes a world. */
final class ImGuiIdeProbe {
    private static int ticks, stage;
    private static ImGuiIdeScreen screen;
    private static ImGuiIdeDocument document;
    private static int checks;
    private static java.util.List<String> toolIds;
    private static int toolIndex, nextFrame;
    private static String pack, pngPath;
    private static ImGuiIdeDocument pngDocument;
    private static int extensionTicks, extensionStart, extensionBaseline;
    private static boolean extensionDisposed;
    private static String screenshotName = "imgui-ui.png";
    static boolean capture;
    private static int legacyRendered, legacyClicks, legacyKeys, legacyChars;
    static void screenshotNameOverride(String name) { screenshotName = name; }

    static void tick(Minecraft client) {
        if (++ticks > 1800) throw new IllegalStateException("ImGui probe timed out at stage " + stage);
        if (stage == 0 && client.screen instanceof TitleScreen && ticks > 60) {
            client.options.languageCode = "zh_cn";
            // This probe stays at the title screen: 26.1 binds prototypes on world
            // join, so supply only this isolated fixture item's default components.
            net.minecraft.world.item.Items.DIAMOND.builtInRegistryHolder().bindComponents(
                    net.minecraft.core.component.DataComponentMap.builder()
                            .addAll(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS)
                            .set(net.minecraft.core.component.DataComponents.ITEM_MODEL,net.minecraft.resources.Identifier.withDefaultNamespace("diamond")).build());
            wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.window("ui_probe","surface")
                    .title(net.minecraft.network.chat.Component.literal("第三方窗口测试"))
                    .content(context -> new wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.NativeContent() {
                        public void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics,int x,int y) {
                            throw new AssertionError("Legacy Minecraft controls must not render");
                        }
                        private boolean inserted;
                        public void renderImGui(){imgui.ImGui.textUnformatted("第三方原生控件");imgui.ImGui.button("新版按钮");if(!inserted){inserted=true;context.insertText("-- 插件输入 测\n");}}
                        public void tick(){ extensionTicks++; }
                        public boolean charTyped(CharacterEvent event){return context.insertText("-- 插件输入 " + new String(Character.toChars(event.codepoint())) + "\n");}
                        public void disposed(){extensionDisposed=true;}
                    }).register();
            // An old-style extension (Content.render with Minecraft drawing) must still show, receive input and warn.
            wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.window("ui_probe","legacy")
                    .title(net.minecraft.network.chat.Component.literal("旧版扩展窗口测试"))
                    .content(context -> new wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.Content() {
                        public void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics,int x,int y) {
                            legacyRendered++; graphics.fill(4,4,140,40,0xFF2D6CDF);
                            graphics.text(context.font(),"Legacy Content",10,18,0xFFFFFFFF);
                            graphics.text(context.font(),"mouse "+x+","+y+" clicks "+legacyClicks,10,50,0xFFFFFFFF);
                        }
                        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){ legacyClicks++; return true; }
                        public boolean keyPressed(KeyEvent event){ legacyKeys++; return true; }
                        public boolean charTyped(CharacterEvent event){ legacyChars++; return true; }
                    }).register();
            screen = new ImGuiIdeScreen(client.screen, "");
            document = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.LUA, "ui_smoke", "-- 中文 / 日本語\nlocal level = 5\nprint(level)\n");
            screen.probeDocument(document); client.setScreen(screen); stage = 1;
        } else if (stage == 1 && screen.renderedFrames() > 25) {
            require(client.screen == screen && ImGuiIdeRuntime.available(), "new UI fell back to classic");
            ImGuiIdeRuntime.inContext(() -> {
                var standalone = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.LUA, "native_widget", "local value = 5\n");
                var editor = standalone.widget();
                require(editor.getLanguageName().equalsIgnoreCase("Lua"), "Lua syntax mode missing");
                require(editor.getTabSize() == 4 && editor.isInsertSpacesOnTabs(), "four-space tab settings");
                editor.setCursor(1, 0); editor.replaceTextInCurrentCursor("-- 中文测试\n"); standalone.capture();
                require(standalone.source.contains("-- 中文测试") && standalone.dirty(), "native Unicode insertion");
                editor.undo(); standalone.capture(); require(!standalone.source.contains("中文测试"), "native undo");
                editor.redo(); standalone.capture(); require(standalone.source.contains("中文测试"), "native redo");
                require(editor.getMainCursorPosition().line < 4, "undo/redo moved cursor to EOF");
                standalone.destroy();
                var fixture=new ImGuiIdeDocument(ImGuiIdeDocument.Kind.LUA,"native_features","local level = 3\nlocal exp = 5 + level * 2\nMC.Player.addExperience\nif level > 0 then\n    print(exp)\nend\nlocal values = {\n    1, 2\n}\n");
                var workspace=screen.probeWorkspace();screen.probeDocument(fixture);workspace.bind(fixture);
                int number=fixture.source.indexOf("5 +");workspace.controller.nativeSmartSelect(number);
                require(fixture.source.substring(Math.min(workspace.controller.nativeAnchor(),workspace.controller.nativeCursor()),Math.max(workspace.controller.nativeAnchor(),workspace.controller.nativeCursor())).equals("5"),"native number selection");
                int player=fixture.source.indexOf("Player");workspace.controller.nativeSmartSelect(player+2);
                require(fixture.source.substring(Math.min(workspace.controller.nativeAnchor(),workspace.controller.nativeCursor()),Math.max(workspace.controller.nativeAnchor(),workspace.controller.nativeCursor())).equals("Player"),"native dotted member selection");
                workspace.controller.nativeSelect(0,0);workspace.command("fold_all");
                require(workspace.controller.nativeFolds().snapshot(fixture.source).starts().size()>=2,"native if/table folds");
                require(fixture.source.contains("print(exp)")&&fixture.source.contains("1, 2"),"folding changed source");workspace.command("expand_all");
                workspace.controller.nativeSelect(fixture.source.length(),fixture.source.length());fixture.insert("local native_variable = 2\nnative_v");
                require(workspace.controller.nativeCompletions().stream().anyMatch(c->c.insertion().equals("native_variable")),"native local variable completion");
                screen.probeDocument(document);workspace.bind(document);
                screen.probeWorkspace().focusCode();
                document.replace(0, 0, "-- 中文测试\n");
                require(document.source.startsWith("-- 中文测试"), "shared editor insertion");
                screen.probeWorkspace().editorShortcut(GLFW.GLFW_KEY_Z, false);
                require(!document.source.contains("中文测试"), "shared editor undo");
                screen.probeWorkspace().editorShortcut(GLFW.GLFW_KEY_Z, true);
                require(document.source.contains("中文测试"), "shared editor redo");
                screen.charTyped(new CharacterEvent('测'));
                screen.charTyped(new CharacterEvent('试'));
            });
            stage = 2;
        } else if (stage == 2 && screen.renderedFrames() > 55) {
            ImGuiIdeRuntime.inContext(() -> {
                document.capture(); require(document.source.contains("测试"), "character input did not reach native editor");
                require(document.cursorOffset() >= 0 && document.cursorOffset() <= document.source.length(), "native cursor mapping");
                int at = document.source.indexOf("中文测试");
                document.replace(at, at + "中文测试".length(), "日本語");
                require(document.source.contains("日本語") && !document.source.contains("中文测试"), "UTF-16/native coordinate replacement");
                screen.probeWorkspace().editorShortcut(GLFW.GLFW_KEY_Z, false); document.capture(); require(document.source.contains("中文测试"), "Unicode replacement undo");
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_I, 0, GLFW.GLFW_MOD_CONTROL));
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                var workspace = screen.probeWorkspace();
                require(workspace.ids().containsAll(java.util.List.of("DATAPACK_FILES", "RESOURCE_FILES", "RESOURCE_JSON", "RESOURCE_PNG", "DATAPACK_JSON_EDITOR", "GIT", "PERSONALIZATION", "EXPORT")), "full tool inventory");
                toolIds = workspace.ids();
                for (String id : toolIds) workspace.close(id);
                toolIndex = 0; workspace.open(toolIds.getFirst()); nextFrame = screen.renderedFrames() + 12;
            });
            stage = 3;
        } else if (stage == 3 && screen.renderedFrames() > nextFrame) {
            var workspace = screen.probeWorkspace();
            require(workspace.frames(toolIds.get(toolIndex)) > 0, "tool was not rendered: " + toolIds.get(toolIndex));
            if (++toolIndex < toolIds.size()) {
                String id=toolIds.get(toolIndex);
                if (id.equals("EXT:ui_probe:surface")) { extensionStart=ticks; extensionBaseline=extensionTicks; require(wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.open("ui_probe:surface"),"extension open API"); }
                else workspace.open(id);
                nextFrame = screen.renderedFrames() + 12;
            }
            else {
                int observed=extensionTicks-extensionBaseline, elapsed=ticks-extensionStart;
                require(observed>0&&observed<=elapsed+1,"extension tick/render mismatch: " + observed + " callbacks in " + elapsed + " game ticks");
                workspace.focus("EXT:ui_probe:surface"); workspace.pull();document.capture();
                require(document.source.contains("插件输入 测"),"extension input/context insertion");
                require(wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi.close("ui_probe:surface"),"extension close API");
                for (String id : toolIds) workspace.close(id);
                workspace.open("DATAPACK_FILES"); workspace.open("DATAPACK_JSON_EDITOR");
                var json = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.DATAPACK, "ui_probe/data/test/dimension/example.json", "{\"type\":\"minecraft:overworld\",\"generator\":{\"type\":\"minecraft:flat\",\"settings\":{\"biome\":\"minecraft:plains\",\"layers\":[]}}}");
                screen.probeDocument(json); workspace.bind(json); workspace.focusCode();
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_I,0,GLFW.GLFW_MOD_CONTROL));
                require(workspace.isOpen("DATAPACK_JSON_EDITOR"), "JSON Ctrl+I contextual tool");
                call("insertDatapackJson", new Class[]{String.class,String.class}, "dimension", "{\"type\":\"minecraft:overworld\"}");
                workspace.pull();
                json.capture(); require(json.source.equals("{\"type\":\"minecraft:overworld\"}"), "JSON insertion did not reach central document");
                nextFrame = screen.renderedFrames()+15; screenshotName = "imgui-datapack.png"; stage=8;
            }
        } else if (stage == 8 && screen.renderedFrames() > nextFrame) {
            capture=true; nextFrame=screen.renderedFrames()+15; stage=9;
        } else if (stage == 9 && screen.renderedFrames() > nextFrame) {
            try {
                pack = "imgui_probe_" + System.currentTimeMillis(); LuaResourcePackWorkspace.createPack(pack);
                String path = pack + "/assets/" + pack + "/items/example.json";
                var json = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.RESOURCEPACK,path,LuaResourcePackWorkspace.readText(path));
                screen.probeDocument(json); var workspace=screen.probeWorkspace(); workspace.bind(json);
                call("replaceResourceJsonSource", new Class[]{String.class}, "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/tnt\"}}");
                workspace.pull();
                json.capture(); require(json.source.contains("minecraft:item/tnt"),"resource JSON insertion");
                workspace.save(json).join(); require(LuaResourcePackWorkspace.readText(path).equals(json.source) && !json.dirty(),"resource JSON save");
                pngPath=pack + "/assets/" + pack + "/textures/item/example.png";
                pngDocument = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.RESOURCEPACK,pngPath,"");
                screen.probeDocument(pngDocument); workspace.bind(pngDocument); workspace.open("RESOURCE_FILES"); workspace.open("RESOURCE_PNG");
                nextFrame=screen.renderedFrames()+15; screenshotName="imgui-resourcepack.png"; stage=10;
            } catch(Exception error) { throw new IllegalStateException(error); }
        } else if (stage == 10 && screen.renderedFrames() > nextFrame) {
            var canvas = (LuaResourcePngCanvas)call("currentResourcePng",new Class[0]);
            require(canvas!=null,"PNG canvas not loaded");
            canvas.document().beginStroke(); canvas.document().stroke(0,0,LuaPngLayerDocument.Tool.PENCIL,0xff12abcd,1); canvas.document().endStroke(); canvas.markDirty();
            require(pngDocument.dirty(),"PNG dirty status not shared");
            screen.probeWorkspace().save(pngDocument).join();
            try { require(LuaResourcePackWorkspace.readPng(pngPath).getRGB(0,0)==0xff12abcd&&!pngDocument.dirty(),"PNG save"); }
            catch(Exception error){throw new IllegalStateException(error);}
            capture=true; nextFrame=screen.renderedFrames()+15; stage=11;
        } else if (stage == 11 && screen.renderedFrames() > nextFrame) {
            // Palette switching must restyle the whole UI, not only the editor.
            screen.probeWorkspace().command("theme:LIGHT"); screenshotName="imgui-light.png"; nextFrame=screen.renderedFrames()+45; stage=20;
        } else if (stage == 20 && screen.renderedFrames() > nextFrame) {
            capture=true; nextFrame=screen.renderedFrames()+10; stage=21;
        } else if (stage == 21 && screen.renderedFrames() > nextFrame) {
            screen.probeWorkspace().command("theme:HIGH_CONTRAST"); screenshotName="imgui-contrast.png"; nextFrame=screen.renderedFrames()+45; stage=22;
        } else if (stage == 22 && screen.renderedFrames() > nextFrame) {
            capture=true; nextFrame=screen.renderedFrames()+10; stage=23;
        } else if (stage == 23 && screen.renderedFrames() > nextFrame) {
            screen.probeWorkspace().command("theme:DARK"); nextFrame=screen.renderedFrames()+30; stage=24;
        } else if (stage == 24 && screen.renderedFrames() > nextFrame) {
            // Opening a window must show the slide-in ghost frame; capture a frame mid-animation.
            screen.probeWorkspace().open("MANUAL"); screen.probeWorkspace().open("NATIVE_GAMEVIEW"); screenshotName="imgui-motion.png"; nextFrame=screen.renderedFrames()+3; stage=25;
        } else if (stage == 25 && screen.renderedFrames() > nextFrame) {
            capture=true; nextFrame=screen.renderedFrames()+12; stage=26;
        } else if (stage == 26 && screen.renderedFrames() > nextFrame) {
            screenshotName="imgui-gameview.png"; nextFrame=screen.renderedFrames()+30; stage=27;
        } else if (stage == 27 && screen.renderedFrames() > nextFrame) {
            require(GameViewport.hasFrame(),"game viewport never captured a frame");
            capture=true; nextFrame=screen.renderedFrames()+10; stage=28;
        } else if (stage == 28 && screen.renderedFrames() > nextFrame) {
            screen.probeWorkspace().close("NATIVE_GAMEVIEW"); screen.probeWorkspace().open("EXT:ui_probe:legacy");
            screenshotName="imgui-legacy.png"; nextFrame=screen.renderedFrames()+25; stage=29;
        } else if (stage == 29 && screen.renderedFrames() > nextFrame) {
            require(legacyRendered > 0, "legacy Content.render never ran in the new UI");
            // Keyboard routing needs the legacy window to be the focused pane.
            screen.probeWorkspace().focus("EXT:ui_probe:legacy");
            capture=true; nextFrame=screen.renderedFrames()+12; stage=30;
        } else if (stage == 30 && screen.renderedFrames() > nextFrame) {
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A,0,0)); screen.charTyped(new CharacterEvent('a'));
            require(legacyKeys > 0 && legacyChars > 0, "keyboard input did not reach the focused legacy window (keys=" + legacyKeys + ", chars=" + legacyChars + ")");
            screen.probeWorkspace().close("EXT:ui_probe:legacy"); screen.probeScale(10); nextFrame=screen.renderedFrames()+15; stage=12;
        } else if (stage == 12 && screen.renderedFrames() > nextFrame) {
            require(client.screen==screen && ImGuiIdeRuntime.available(),"10% UI scale failed");
            screen.probeScale(300); nextFrame=screen.renderedFrames()+15; stage=13;
        } else if (stage == 13 && screen.renderedFrames() > nextFrame) {
            require(client.screen==screen && ImGuiIdeRuntime.available(),"300% UI scale failed");
            screen.probeScale(100); nextFrame=screen.renderedFrames()+15; stage=7;
        } else if (stage == 7 && !capture && screen.renderedFrames() > nextFrame) {
            screen.onClose(); stage = 4;
        } else if (stage == 4 && screen.renderedFrames() > 100) {
            require(client.screen == screen, "dirty close lost unsaved source");
            // Forced replacement must still retain an independently recoverable draft.
            client.setScreen(new TitleScreen());
            require(extensionDisposed,"extension resources not disposed");
            require(screen.probeHasRecovery(), "forced close did not protect draft");
            stage = 5;
        } else if (stage == 5 && ticks > 180) {
            McLuaIdeMod.LOGGER.info("IMGUI_PROBE_OK {} checks", checks);
            client.stop(); stage = 6;
        }
    }
    /**
     * The probe has no world, so the game picture would be empty. Paint a known pattern into the main render target
     * (red bottom-left quarter on magenta) so the viewport window can be checked for content and orientation.
     */
    static void paintGameTarget(Minecraft client) {
        if (!(client.getMainRenderTarget().getColorTexture() instanceof com.mojang.blaze3d.opengl.GlTexture color)) return;
        int w = client.getMainRenderTarget().width, h = client.getMainRenderTarget().height;
        int oldDraw = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int fbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, fbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, color.glId(), 0);
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL11.glScissor(0, 0, w, h); org.lwjgl.opengl.GL11.glClearColor(.9f, 0f, .9f, 1f); org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            org.lwjgl.opengl.GL11.glScissor(0, 0, w / 2, h / 2); org.lwjgl.opengl.GL11.glClearColor(1f, .1f, .1f, 1f); org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
        } finally {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
        }
    }
    static void screenshot(Minecraft client) {
        if (!capture) return; capture = false;
        int w = client.getWindow().getWidth(), h = client.getWindow().getHeight();
        int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        var pixels = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int offset = (y * w + x) * 4;
                image.setRGB(x, h - y - 1, 0xff000000 | (pixels.get(offset) & 255) << 16 | (pixels.get(offset+1) & 255) << 8 | pixels.get(offset+2) & 255);
            }
            ImageIO.write(image, "png", new java.io.File(client.gameDirectory, screenshotName));
        } catch (Exception error) { throw new IllegalStateException(error); }
        finally { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous); }
    }
    private static void require(boolean condition, String text) { checks++; if (!condition) throw new AssertionError(text); }
    private static Object call(String name, Class<?>[] types, Object... arguments) {
        try { var method=LuaIdeScreen.class.getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(screen.probeWorkspace().controller,arguments); }
        catch(Exception error){throw new IllegalStateException(error);}
    }
}
