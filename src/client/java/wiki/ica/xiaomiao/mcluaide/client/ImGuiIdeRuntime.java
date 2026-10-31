package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.internal.ImGuiContext;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;

/** Own ImGui context, no extra OS windows and no replacement of Minecraft's input callbacks. */
public final class ImGuiIdeRuntime {
    private static ImGuiContext context;
    private static ImGuiImplGlfw platform;
    private static ImGuiImplGl3 renderer;
    private static imgui.ImFont editorFont;
    private static boolean failed;
    private static final Queue<Runnable> afterFrame = new ArrayDeque<>();
    private ImGuiIdeRuntime() { }

    private static final String[] CJK_FONTS = {"C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/YuGothR.ttc", "C:/Windows/Fonts/meiryo.ttc",
            "C:/Windows/Fonts/simsun.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc", "/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"};
    private static final String[] MONO_FONTS = {"C:/Windows/Fonts/consola.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", "/System/Library/Fonts/Menlo.ttc"};
    // Symbols, arrows, punctuation, Korean and anything the main fonts lack; each is merged only if installed.
    private static final String[] FALLBACK_FONTS = {"C:/Windows/Fonts/seguisym.ttf", "C:/Windows/Fonts/malgun.ttf", "C:/Windows/Fonts/segoeui.ttf",
            "C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/noto/NotoSansSymbols2-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf", "/System/Library/Fonts/Apple Symbols.ttf"};

    // Glyph ranges are explicit: the atlas is built up front, so every listed glyph is guaranteed to render.
    private static final short[] MAIN_RANGES = {0x20, 0x24f, 0x370, 0x4ff, 0x2000, 0x2bff, 0x3000, 0x30ff, 0x3400, (short) 0x9fff, (short) 0xff00, (short) 0xffef, 0};
    private static final short[] MONO_RANGES = {0x20, 0x24f, 0x370, 0x4ff, 0x2000, 0x2bff, 0};
    private static final short[] CJK_RANGES = {0x3000, 0x30ff, 0x3400, (short) 0x9fff, (short) 0xff00, (short) 0xffef, 0};
    // Fallback fonts fill what the main fonts lack: accents, Greek/Cyrillic, punctuation, arrows, shapes, symbols and Hangul.
    private static final short[] SYMBOL_RANGES = {0x100, 0x24f, 0x370, 0x4ff, 0x2000, 0x2bff, (short) 0xac00, (short) 0xd7af, 0};

    private static String firstExisting(String[] candidates) {
        for (String candidate : candidates) if (Files.isRegularFile(Path.of(candidate))) return candidate;
        return null;
    }
    private static void mergeFont(imgui.ImGuiIO io, String path, short[] ranges) {
        var config = new imgui.ImFontConfig();
        try { config.setMergeMode(true); io.getFonts().addFontFromFileTTF(path, 17, config, ranges); }
        finally { config.destroy(); }
    }
    /** Every installed CJK font fills the glyphs the first one lacks, so Japanese kana and kanji render even when the first font is Chinese-only. */
    private static void mergeOtherCjk(imgui.ImGuiIO io, String except) {
        for (String candidate : CJK_FONTS) if (!candidate.equals(except) && Files.isRegularFile(Path.of(candidate))) mergeFont(io, candidate, CJK_RANGES);
    }
    private static void mergeFallbacks(imgui.ImGuiIO io, String except) {
        for (String candidate : FALLBACK_FONTS) if (!candidate.equals(except) && Files.isRegularFile(Path.of(candidate))) mergeFont(io, candidate, SYMBOL_RANGES);
    }

    private static float[][] shown, goal;
    private static LuaSyntaxHighlighter.Theme appliedTheme;

    /** Flat, rounded metrics shared by every palette; colors come from {@link #palette}. */
    private static void applyModernStyle() {
        ImGui.styleColorsDark();
        var style = ImGui.getStyle();
        style.setWindowRounding(10); style.setChildRounding(8); style.setPopupRounding(8); style.setFrameRounding(6);
        style.setScrollbarRounding(12); style.setGrabRounding(6); style.setTabRounding(7);
        style.setWindowBorderSize(1); style.setChildBorderSize(1); style.setPopupBorderSize(1); style.setFrameBorderSize(0);
        style.setWindowPadding(12, 10); style.setFramePadding(10, 6); style.setItemSpacing(8, 7); style.setItemInnerSpacing(6, 5);
        style.setScrollbarSize(12); style.setGrabMinSize(12); style.setWindowTitleAlign(0.02f, 0.5f);
        setTheme(LuaSyntaxHighlighter.Theme.DARK, true);
    }

    private static void put(float[][] palette, int slot, int rgb, float alpha) {
        palette[slot] = new float[]{(rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, alpha};
    }

    /** Whole-UI palette for an editor theme, so the windows, buttons and text follow the chosen color scheme. */
    private static float[][] palette(LuaSyntaxHighlighter.Theme theme) {
        float[][] p = new float[ImGuiCol.COUNT][];
        boolean light = theme == LuaSyntaxHighlighter.Theme.LIGHT, high = theme == LuaSyntaxHighlighter.Theme.HIGH_CONTRAST;
        // 0 window, 1 child, 2 popup, 3 frame, 4 frame hover, 5 frame active, 6 title, 7 title active, 8 text, 9 dim text,
        // 10 accent, 11 button, 12 button hover, 13 button active, 14 header, 15 header hover, 16 tab, 17 tab selected
        int[] v = switch (theme) {
            case DARK -> new int[]{0x0F1118, 0x0C0E14, 0x151926, 0x1B2030, 0x242B3F, 0x2D3650, 0x121520, 0x1A2032, 0xF0F3FA, 0xA9B3CA,
                    0x8E9CFF, 0x252D45, 0x35416B, 0x4657A3, 0x2C3A6E, 0x3A4C8C, 0x131722, 0x28345E};
            case LIGHT -> new int[]{0xF4F6FB, 0xFFFFFF, 0xFFFFFF, 0xE7EBF3, 0xDCE2EF, 0xCFD8EA, 0xE8ECF5, 0xDCE3F3, 0x151A26, 0x566077,
                    0x3347D1, 0xDCE2F1, 0xCAD4EE, 0xB3C2EA, 0xC9D4F3, 0xB4C4F0, 0xE2E7F1, 0xFFFFFF};
            case HIGH_CONTRAST -> new int[]{0x000000, 0x000000, 0x0A0A0A, 0x141414, 0x2A2A2A, 0x3C3C3C, 0x000000, 0x1C1C1C, 0xFFFFFF, 0xE6E6E6,
                    0xFFD400, 0x1E1E1E, 0x4A4200, 0x7A6B00, 0x3A3300, 0x5C5000, 0x0A0A0A, 0x3A3300};
        };
        int line = light ? 0x000000 : 0xFFFFFF;
        float border = high ? .9f : light ? .16f : .09f;
        put(p, ImGuiCol.Text, v[8], 1); put(p, ImGuiCol.TextDisabled, v[9], 1);
        put(p, ImGuiCol.WindowBg, v[0], 1); put(p, ImGuiCol.ChildBg, v[1], 1); put(p, ImGuiCol.PopupBg, v[2], .98f);
        put(p, ImGuiCol.Border, line, border); put(p, ImGuiCol.BorderShadow, 0, 0);
        put(p, ImGuiCol.FrameBg, v[3], 1); put(p, ImGuiCol.FrameBgHovered, v[4], 1); put(p, ImGuiCol.FrameBgActive, v[5], 1);
        put(p, ImGuiCol.TitleBg, v[6], 1); put(p, ImGuiCol.TitleBgActive, v[7], 1); put(p, ImGuiCol.TitleBgCollapsed, v[6], .8f);
        put(p, ImGuiCol.MenuBarBg, v[6], 1);
        put(p, ImGuiCol.ScrollbarBg, 0, 0); put(p, ImGuiCol.ScrollbarGrab, line, light ? .22f : .18f);
        put(p, ImGuiCol.ScrollbarGrabHovered, line, light ? .38f : .32f); put(p, ImGuiCol.ScrollbarGrabActive, v[10], .9f);
        put(p, ImGuiCol.CheckMark, v[10], 1); put(p, ImGuiCol.SliderGrab, v[10], 1); put(p, ImGuiCol.SliderGrabActive, v[10], 1);
        put(p, ImGuiCol.Button, v[11], 1); put(p, ImGuiCol.ButtonHovered, v[12], 1); put(p, ImGuiCol.ButtonActive, v[13], 1);
        put(p, ImGuiCol.Header, v[14], .85f); put(p, ImGuiCol.HeaderHovered, v[15], .95f); put(p, ImGuiCol.HeaderActive, v[13], 1);
        put(p, ImGuiCol.Separator, line, border); put(p, ImGuiCol.SeparatorHovered, v[10], .7f); put(p, ImGuiCol.SeparatorActive, v[10], 1);
        put(p, ImGuiCol.ResizeGrip, line, .08f); put(p, ImGuiCol.ResizeGripHovered, v[10], .6f); put(p, ImGuiCol.ResizeGripActive, v[10], .9f);
        put(p, ImGuiCol.Tab, v[16], 1); put(p, ImGuiCol.TabHovered, v[15], 1); put(p, ImGuiCol.TabSelected, v[17], 1);
        put(p, ImGuiCol.TabDimmed, v[16], 1); put(p, ImGuiCol.TabDimmedSelected, v[14], 1);
        put(p, ImGuiCol.DockingPreview, v[10], .35f); put(p, ImGuiCol.DockingEmptyBg, v[1], 1);
        put(p, ImGuiCol.TableHeaderBg, v[3], 1); put(p, ImGuiCol.TableBorderStrong, line, border); put(p, ImGuiCol.TableBorderLight, line, border * .5f);
        put(p, ImGuiCol.TableRowBgAlt, line, .03f); put(p, ImGuiCol.TextSelectedBg, v[10], .4f);
        put(p, ImGuiCol.DragDropTarget, v[10], .9f); put(p, ImGuiCol.ModalWindowDimBg, 0, .5f);
        return p;
    }

    /** Switches the UI palette; with {@code immediate=false} every color eases to the new value over ~0.3 s. */
    static void setTheme(LuaSyntaxHighlighter.Theme theme, boolean immediate) {
        if (theme == null || theme == appliedTheme && !immediate) return;
        appliedTheme = theme; goal = palette(theme);
        if (shown == null || immediate) {
            shown = new float[goal.length][];
            for (int slot = 0; slot < goal.length; slot++) if (goal[slot] != null) shown[slot] = goal[slot].clone();
            pushColors();
        }
    }
    /** Advances the palette transition; call once per frame inside the ImGui context. */
    static void animateTheme() {
        if (goal == null || shown == null) return;
        boolean changed = false;
        for (int slot = 0; slot < goal.length; slot++) {
            float[] target = goal[slot], current = shown[slot];
            if (target == null) continue;
            if (current == null) { shown[slot] = target.clone(); changed = true; continue; }
            for (int k = 0; k < 4; k++) {
                float next = approach(current[k], target[k], 10);
                if (next != current[k]) { current[k] = next; changed = true; }
            }
        }
        if (changed) pushColors();
    }
    private static void pushColors() {
        var style = ImGui.getStyle();
        for (int slot = 0; slot < shown.length; slot++) if (shown[slot] != null) style.setColor(slot, shown[slot][0], shown[slot][1], shown[slot][2], shown[slot][3]);
    }

    /** Frame delta in seconds, clamped so a stalled frame never makes an animation jump. */
    static float delta() { return Math.min(.05f, Math.max(.001f, ImGui.getIO().getDeltaTime())); }
    /** Moves {@code value} toward {@code target} exponentially; {@code speed} ~ 12 settles in about a quarter second. */
    static float approach(float value, float target, float speed) {
        float next = value + (target - value) * (1 - (float) Math.exp(-speed * delta()));
        return Math.abs(next - target) < .002f ? target : next;
    }
    static float easeOut(float t) { float u = 1 - Math.clamp(t, 0f, 1f); return 1 - u * u * u; }

    static boolean available() { return !failed; }
    static void defer(Runnable action) { afterFrame.add(action); }

    private static void initialize() {
        if (context != null) return;
        if (GLFW.glfwGetCurrentContext() == 0 || GL.getCapabilities() == null)
            throw new IllegalStateException("The new IDE requires the OpenGL renderer");
        context = ImGui.createContext();
        var io = ImGui.getIO();
        io.setIniFilename(null); // the screen saves a connection-scoped layout explicitly
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable | ImGuiConfigFlags.NavEnableKeyboard);
        io.setConfigWindowsMoveFromTitleBarOnly(true);
        io.setConfigWindowsResizeFromEdges(true);
        // 1.92 loads glyphs on demand, so no ranges are given: any glyph found in a font (or a fallback) renders.
        // Fonts are read from the local OS and never redistributed.
        String base = firstExisting(CJK_FONTS);
        io.setFontDefault(base == null ? io.getFonts().addFontDefault() : io.getFonts().addFontFromFileTTF(base, 17, MAIN_RANGES));
        if (base != null) { mergeOtherCjk(io, base); mergeFallbacks(io, base); }
        String mono = firstExisting(MONO_FONTS);
        if (mono != null) {
            editorFont = io.getFonts().addFontFromFileTTF(mono, 17, MONO_RANGES);
            String cjk = firstExisting(CJK_FONTS);
            if (cjk != null) { mergeFont(io, cjk, CJK_RANGES); mergeOtherCjk(io, cjk); }
            mergeFallbacks(io, mono);
        }
        applyModernStyle();
        platform = new ImGuiImplGlfw();
        if (!platform.init(Minecraft.getInstance().getWindow().handle(), false))
            throw new IllegalStateException("Could not initialize ImGui input");
        renderer = new ImGuiImplGl3();
        if (!renderer.init("#version 150")) throw new IllegalStateException("Could not initialize ImGui renderer");
    }

    /** Runs immediately before the OpenGL back buffer is presented, after Minecraft's GUI pass. */
    public static void renderFrame() {
        var client = Minecraft.getInstance();
        // Normally the IDE screen is open; during game control it is closed and drawn as a non-interactive overlay.
        boolean overlay = !(client.screen instanceof NativeUiScreen);
        NativeUiScreen screen = overlay ? GameControl.overlay(client) : (NativeUiScreen) client.screen;
        if (screen == null) return;
        ImGuiContext previous = null;
        int oldFramebuffer = 0;
        boolean gl = false;
        try {
            previous = ImGui.getCurrentContext();
            if (context != null) ImGui.setCurrentContext(context);
            initialize();
            oldFramebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0); gl = true;
            if (Boolean.getBoolean("mc_lua_ide.imGuiProbe")) ImGuiIdeProbe.paintGameTarget(client);
            // The game owns the mouse in overlay mode: ImGui must neither react to it nor change the cursor mode.
            int mouseFlags = imgui.flag.ImGuiConfigFlags.NoMouse | imgui.flag.ImGuiConfigFlags.NoMouseCursorChange;
            if (overlay) ImGui.getIO().addConfigFlags(mouseFlags); else ImGui.getIO().removeConfigFlags(mouseFlags);
            renderer.newFrame(); platform.newFrame(); ImGui.newFrame();
            screen.drawUi();
            ImGui.render();
            renderer.renderDrawData(ImGui.getDrawData());
            screen.frameRendered();
            if (Boolean.getBoolean("mc_lua_ide.imGuiProbe") || Boolean.getBoolean("mc_lua_ide.gameControlProbe")) ImGuiIdeProbe.screenshot(client);
        } catch (LinkageError | RuntimeException error) {
            failed = true;
            McLuaIdeMod.LOGGER.error("Native UI failed; preserving drafts", error);
            afterFrame.clear();
            defer(() -> screen.rendererFailed(error));
        } finally {
            if (gl) GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldFramebuffer);
            if (previous != null) ImGui.setCurrentContext(previous);
        }
        while (!afterFrame.isEmpty()) afterFrame.remove().run();
    }

    static void inContext(Runnable action) {
        if (context == null) return;
        var previous = ImGui.getCurrentContext();
        try { ImGui.setCurrentContext(context); action.run(); }
        finally { ImGui.setCurrentContext(previous); }
    }
    static void key(int key, int scan, int action, int mods) {
        inContext(() -> platform.keyCallback(Minecraft.getInstance().getWindow().handle(), key, scan, action, mods));
    }
    static void character(int codepoint) { inContext(() -> ImGui.getIO().addInputCharacter(codepoint)); }
    static boolean nativeTextInput() {
        boolean[] active = {false}; inContext(() -> active[0] = ImGui.getIO().getWantTextInput()); return active[0];
    }
    static boolean popupOpen() {
        boolean[] open = {false}; inContext(() -> open[0] = ImGui.isPopupOpen("", imgui.flag.ImGuiPopupFlags.AnyPopupId | imgui.flag.ImGuiPopupFlags.AnyPopupLevel)); return open[0];
    }
    static void mouse(int button, boolean down) {
        inContext(() -> ImGui.getIO().addMouseButtonEvent(button, down));
    }
    static void scroll(double x, double y) { inContext(() -> ImGui.getIO().addMouseWheelEvent((float)x, (float)y)); }
    static void clearInput() {
        inContext(() -> { var io = ImGui.getIO(); io.clearEventsQueue(); io.clearInputKeys(); io.clearInputMouse(); });
    }
    static void pushEditorFont(float size) { ImGui.pushFont(editorFont == null ? ImGui.getIO().getFontDefault() : editorFont, size); }
    public static void shutdown() {
        if (context == null) return;
        ImGui.setCurrentContext(context);
        GameViewport.release();
        if (renderer != null) renderer.shutdown();
        if (platform != null) platform.shutdown();
        ImGui.destroyContext(context);
        context = null; platform = null; renderer = null;
    }
}
