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
            screen.probeWorkspace().close("EXT:ui_probe:legacy");
            screen.probeWorkspace().open("NATIVE_DEBUG"); screenshotName="imgui-f12.png"; nextFrame=screen.renderedFrames()+25; stage=40;
        } else if (stage == 40 && screen.renderedFrames() > nextFrame) {
            capture=true; nextFrame=screen.renderedFrames()+12; stage=41;
        } else if (stage == 41 && screen.renderedFrames() > nextFrame) {
            // The drag-and-drop HUD editor must open, cover the screen and leave again with Esc.
            screen.probeWorkspace().beginLayoutEditing(); screenshotName="imgui-f12-layout.png"; nextFrame=screen.renderedFrames()+20; stage=42;
        } else if (stage == 42 && screen.renderedFrames() > nextFrame) {
            require(screen.probeWorkspace().endLayoutEditingProbe(false), "layout editor did not open");
            capture=true; nextFrame=screen.renderedFrames()+12; stage=43;
        } else if (stage == 43 && screen.renderedFrames() > nextFrame) {
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE,0,0));
            require(!screen.probeWorkspace().endLayoutEditingProbe(false), "Esc did not leave the layout editor");
            require(client.screen == screen, "Esc closed the IDE instead of the layout editor");
            screen.probeWorkspace().close("NATIVE_DEBUG");
            var editorDoc = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.LUA, "editor_tools", "local function greet(name)\n    local message = \"hi \" .. name\n    print(message)\nend\nlocal x = 1\nprint(x)\n");
            screen.probeDocument(editorDoc); screen.probeWorkspace().bind(editorDoc);
            nextFrame=screen.renderedFrames()+15; stage=50;
        } else if (stage == 50 && screen.renderedFrames() > nextFrame) {
            editorToolChecks();
            nextFrame=screen.renderedFrames()+15; screenshotName="imgui-editor.png"; stage=51;
        } else if (stage == 51 && screen.renderedFrames() > nextFrame) {
            capture=true; nextFrame=screen.renderedFrames()+12; stage=52;
        } else if (stage == 52 && screen.renderedFrames() > nextFrame) {
            screen.probeWorkspace().open("NATIVE_OUTLINE"); screen.probeWorkspace().open("NATIVE_DIFF"); screen.probePalette(">");
            screenshotName="imgui-editor-panels.png"; nextFrame=screen.renderedFrames()+25; stage=53;
        } else if (stage == 53 && screen.renderedFrames() > nextFrame) {
            require(screen.probePaletteOpen(), "command palette did not stay open");
            capture=true; nextFrame=screen.renderedFrames()+12; stage=54;
        } else if (stage == 54 && screen.renderedFrames() > nextFrame) {
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE,0,0));
            require(!screen.probePaletteOpen() && client.screen == screen, "Esc should close only the palette");
            screen.probeWorkspace().close("NATIVE_OUTLINE"); screen.probeWorkspace().close("NATIVE_DIFF");
            screen.probeWorkspace().open("NATIVE_TERMINAL"); nextFrame=screen.renderedFrames()+40; stage=60;
        } else if (stage == 60 && screen.renderedFrames() > nextFrame) {
            // A real shell through the IDE's own input path: characters and Enter go through the screen, not through the model.
            var tab = TerminalPanel.current();
            require(tab != null && tab.session != null, "the terminal pane starts a local shell");
            require(screen.probeWorkspace().terminalCaptures(), "the focused terminal pane owns the keyboard");
            for (char c : "echo probe-term-ok".toCharArray()) screen.charTyped(new CharacterEvent(c));
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            nextFrame=screen.renderedFrames()+80; screenshotName="imgui-terminal.png"; stage=61;
        } else if (stage == 61 && screen.renderedFrames() > nextFrame) {
            var tab = TerminalPanel.current();
            String all = tab.emulator.text(0, 0, tab.emulator.totalLines() - 1, tab.emulator.cols() - 1);
            require(all.contains("probe-term-ok"), "typed command output reached the terminal screen, got: " + all);
            require(tab.emulator.cols() > 20 && tab.emulator.rows() > 3, "the terminal grid follows the pane size");
            capture=true; nextFrame=screen.renderedFrames()+12; stage=62;
        } else if (stage == 62 && screen.renderedFrames() > nextFrame) {
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
            require(client.screen == screen, "Esc inside the terminal must not close the IDE");
            TerminalPanel.closeTab(TerminalPanel.current());
            screen.probeWorkspace().close("NATIVE_TERMINAL");
            if (WindowChrome.defaultEnabled()) require(WindowChrome.active(), "the integrated title bar should be active by default where it is supported");
            // The personalization switch must hand the system title bar back, and take it over again.
            screen.probeWorkspace().controller.nativePersonalization().integratedTitleBar = false;
            nextFrame=screen.renderedFrames()+6; stage=63;
        } else if (stage == 63 && screen.renderedFrames() > nextFrame) {
            require(!WindowChrome.active(), "switching the setting off should restore the system title bar");
            screen.probeWorkspace().controller.nativePersonalization().integratedTitleBar = WindowChrome.defaultEnabled();
            nextFrame=screen.renderedFrames()+6; stage=64;
        } else if (stage == 64 && screen.renderedFrames() > nextFrame) {
            require(WindowChrome.active() == WindowChrome.defaultEnabled(), "switching the setting back on should take the title bar over again");
            // New panes: they must draw without a server (the probe has none) and the split view must edit the shared document.
            var workspace = screen.probeWorkspace();
            workspace.open(SplitEditorPane.ID); workspace.open(WelcomePanel.ID); workspace.open(AuditLogPanel.ID); workspace.open(BackupPanel.ID);
            nextFrame=screen.renderedFrames()+30; stage=65;
        } else if (stage == 65 && screen.renderedFrames() > nextFrame) {
            var split = screen.probeWorkspace().probeSplit();
            require(split != null && split.document() != null, "the split editor shows an open document");
            split.area().focus();
            screen.probeWorkspace().focus(SplitEditorPane.ID);
            nextFrame=screen.renderedFrames()+8; screenshotName="imgui-split-panes.png"; stage=66;
        } else if (stage == 66 && screen.renderedFrames() > nextFrame) {
            var workspace = screen.probeWorkspace();
            var split = workspace.probeSplit();
            require(workspace.focusedSplit() == split, "the focused split view takes the keyboard");
            var doc = split.document();
            for (char c : "--split".toCharArray()) screen.charTyped(new CharacterEvent(c));
            require(doc.source.contains("--split"), "typing in the split view changes the shared document");
            capture=true; nextFrame=screen.renderedFrames()+12; stage=67;
        } else if (stage == 67 && screen.renderedFrames() > nextFrame) {
            var workspace = screen.probeWorkspace();
            var doc = workspace.probeSplit().document();
            workspace.bind(doc);
            require(workspace.controller.nativeSnapshot().source().contains("--split"), "the main editor sees the split view's edit");
            workspace.close(SplitEditorPane.ID); workspace.close(WelcomePanel.ID); workspace.close(AuditLogPanel.ID); workspace.close(BackupPanel.ID);
            screen.probeScale(10); nextFrame=screen.renderedFrames()+15; stage=12;
        } else if (stage == 12&& screen.renderedFrames() > nextFrame) {
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
    /** Functional checks of the editor tools against the live editor model (rename, replace, auto-close, multi-caret, snippets). */
    private static void editorToolChecks() {
        var workspace = screen.probeWorkspace();
        var area = workspace.codeArea;
        var controller = workspace.controller;
        ImGuiIdeRuntime.inContext(() -> {
            var doc = screen.probeCurrent();
            require(area.symbolIndex() != null, "the symbol index is built once the editor has been drawn");
            require(area.symbolIndex().outline().size() == 1 && area.symbolIndex().outline().get(0).name().equals("greet"), "outline lists greet");
            // Rename: the caret on the parameter renames its declaration and its use.
            int caret = doc.source.indexOf("name)") + 1;
            controller.nativeSelect(caret, caret);
            require(area.renameTo("who"), "rename accepted");
            workspace.pull(); doc.capture();
            require(doc.source.contains("greet(who)") && doc.source.contains("\"hi \" .. who"), "rename changed both references, got: " + doc.source);
            require(!area.renameTo("end"), "a keyword is not a valid name");
            // Replace all through the find bar model.
            area.setSearch("message", "text", false); area.replaceEverything();
            workspace.pull(); doc.capture();
            require(doc.source.contains("local text =") && doc.source.contains("print(text)") && !doc.source.contains("message"), "replace all");
            // Go to line.
            area.goToLineNumber(5);
            require(controller.nativeCursor() == doc.source.indexOf("local x"), "go to line 5");
            // Auto-closing brackets: typing '(' adds ')' and ')' steps over it.
            int end = doc.source.length();
            controller.nativeSelect(end, end);
            require(area.preCharacter('('), "auto-close consumed '('");
            workspace.pull(); doc.capture();
            require(doc.source.endsWith("()") && controller.nativeCursor() == doc.source.length() - 1, "caret sits between the pair");
            int before = doc.source.length();
            require(area.preCharacter(')'), "closing bracket steps over");
            workspace.pull(); doc.capture();
            require(doc.source.length() == before && controller.nativeCursor() == before, "stepping over adds nothing");
            // Wrapping a selection.
            int start = doc.source.indexOf("x = 1") + 4;
            controller.nativeSelect(start, start + 1);
            require(area.preCharacter('"'), "quote wraps the selection");
            workspace.pull(); doc.capture();
            require(doc.source.contains("x = \"1\""), "selection wrapped in quotes, got " + doc.source);

            // Multiple carets: three identical lines edited at once.
            var lines = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.LUA, "multi_caret", "a\na\na\n");
            screen.probeDocument(lines); workspace.bind(lines);
            controller.nativeSelect(0, 1);
            area.addNextOccurrence(); area.addNextOccurrence();
            require(area.extraCaretCount() == 2, "two extra carets, got " + area.extraCaretCount());
            require(area.preCharacter('b'), "typing is handled for every caret");
            workspace.pull(); lines.capture();
            require(lines.source.equals("b\nb\nb\n"), "all three lines changed, got " + lines.source.replace("\n", "|"));

            require(area.dismissMarks() && area.extraCaretCount() == 0, "Esc collapses the extra carets");
            // Snippets: "for" + Tab expands and selects the first placeholder, Tab moves on.
            var snippet = new ImGuiIdeDocument(ImGuiIdeDocument.Kind.LUA, "snippet", "for");
            screen.probeDocument(snippet); workspace.bind(snippet);
            controller.nativeSelect(3, 3);
            require(area.tab(false), "Tab expanded the snippet");
            workspace.pull(); snippet.capture();
            require(snippet.source.startsWith("for i = 1, 10 do"), "snippet text, got " + snippet.source);
            require(selected(controller, snippet).equals("i"), "first placeholder selected");
            require(area.tab(false) && selected(controller, snippet).equals("1"), "Tab moves to the second placeholder");

            screen.probeDocument(doc); workspace.bind(doc);
            area.setSearch("print", "", false);
        });
    }
    private static String selected(LuaIdeScreen controller, ImGuiIdeDocument doc) {
        int a = controller.nativeAnchor(), b = controller.nativeCursor();
        return doc.source.substring(Math.min(a, b), Math.max(a, b));
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
