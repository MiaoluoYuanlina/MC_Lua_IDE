package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.opengl.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * A floating, freely resizable window that shows the live game picture.
 * <p>
 * Minecraft has already composed the frame (world, HUD, any screen) into its main render target when the UI layer runs.
 * That texture is sampled directly and scaled to whatever size the user gives the window, so there is no per-frame copy.
 * "Pause" holds a single copied frame while the game keeps running.
 */
final class GameViewport {
    enum Fit {
        FIT("适应窗口（保持比例）", "Fit (keep aspect)"), FILL("填满窗口（裁切）", "Fill (crop)"),
        STRETCH("拉伸", "Stretch"), PIXELS("原始像素 1:1", "Actual pixels 1:1");
        final String zh, en;
        Fit(String zh, String en) { this.zh = zh; this.en = en; }
        String label() { return LuaUiText.text(zh, en); }
    }

    private static int liveId, frozenId, textureWidth, textureHeight, frozenWidth, frozenHeight, configuredId;

    private Fit fit = Fit.FIT;
    private boolean paused, showInfo = true, wasPaused;
    private float zoom = 1;
    private final Runnable enterControl;
    private final java.util.function.BooleanSupplier controlling;

    /** @param enterControl called when the viewport is double-clicked; @param controlling whether the game currently owns the input */
    GameViewport(Runnable enterControl, java.util.function.BooleanSupplier controlling) { this.enterControl = enterControl; this.controlling = controlling; }

    /** The game's own main render target already holds the finished frame, so it is sampled directly: no copy per frame. */
    private static boolean resolveLive() {
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null || !(target.getColorTexture() instanceof GlTexture color)) return false;
        liveId = color.glId(); textureWidth = target.width; textureHeight = target.height;
        if (liveId != configuredId) {
            // ImGui samples the texture object itself, bypassing Minecraft's sampler objects, so give it usable filtering.
            int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, liveId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, old);
            configuredId = liveId;
        }
        return true;
    }

    /** Copies the current frame into an own texture so "pause" can hold the picture while the game keeps running. */
    private static void freeze() {
        if (!resolveLive()) return;
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                oldReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int fbo = GL30.glGenFramebuffers();
        try {
            if (frozenId == 0) frozenId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, frozenId);
            if (frozenWidth != textureWidth || frozenHeight != textureHeight) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, textureWidth, textureHeight, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                frozenWidth = textureWidth; frozenHeight = textureHeight;
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, liveId, 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, textureWidth, textureHeight);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glDeleteFramebuffers(fbo);
            GL11.glReadBuffer(oldReadBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
        }
    }

    static boolean hasFrame() { return liveId != 0 && textureWidth > 0; }

    static void release() {
        if (frozenId != 0) GL11.glDeleteTextures(frozenId);
        liveId = frozenId = textureWidth = textureHeight = frozenWidth = frozenHeight = configuredId = 0;
    }
    /** Draws the window's contents: a small toolbar and the scaled game picture. */
    void draw() {
        boolean live = resolveLive();
        if (ImGui.beginCombo("##gv_fit", fit.label(), 0)) {
            for (var option : Fit.values()) if (ImGui.selectable(option.label(), option == fit)) fit = option;
            ImGui.endCombo();
        }
        ImGui.sameLine(); paused = NativeUi.check(LuaUiText.text("暂停画面", "Pause view"), paused);
        if (paused && !wasPaused) freeze();
        wasPaused = paused;
        ImGui.sameLine(); showInfo = NativeUi.check(LuaUiText.text("信息", "Info"), showInfo);
        if (fit == Fit.PIXELS) { ImGui.sameLine(); ImGui.setNextItemWidth(110); float[] value = {zoom}; if (ImGui.sliderFloat("##gv_zoom", value, .25f, 4, "%.2fx")) zoom = value[0]; }
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        float cw = Math.max(16, ImGui.getContentRegionAvailX()), ch = Math.max(16, ImGui.getContentRegionAvailY());
        ImGui.invisibleButton("##gv_canvas", cw, ch);
        boolean inControl = controlling.getAsBoolean(), hovered = ImGui.isItemHovered();
        // Double-click gives the keyboard and mouse to the game; Esc (handled by GameControl) takes them back.
        if (!inControl && hovered && ImGui.isMouseDoubleClicked(0)) enterControl.run();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + cw, y + ch, ImGui.getColorU32(ImGuiCol.ChildBg), 6);
        int shownId = paused && frozenId != 0 ? frozenId : liveId;
        if (!live || textureWidth < 1) {
            ImGui.setCursorScreenPos(x + 12, y + 10); ImGui.textDisabled(LuaUiText.text("正在等待游戏画面…", "Waiting for the game picture…")); return;
        }
        float sourceWidth = textureWidth, sourceHeight = textureHeight, imageWidth, imageHeight;
        switch (fit) {
            case FILL -> { float s = Math.max(cw / sourceWidth, ch / sourceHeight); imageWidth = sourceWidth * s; imageHeight = sourceHeight * s; }
            case STRETCH -> { imageWidth = cw; imageHeight = ch; }
            case PIXELS -> { imageWidth = sourceWidth * zoom; imageHeight = sourceHeight * zoom; }
            default -> { float s = Math.min(cw / sourceWidth, ch / sourceHeight); imageWidth = sourceWidth * s; imageHeight = sourceHeight * s; }
        }
        // Centered image rectangle, clipped to the window; the texture coordinates follow the clipping.
        float x0 = x + (cw - imageWidth) / 2, y0 = y + (ch - imageHeight) / 2;
        float dx0 = Math.max(x0, x), dy0 = Math.max(y0, y), dx1 = Math.min(x0 + imageWidth, x + cw), dy1 = Math.min(y0 + imageHeight, y + ch);
        float u0 = (dx0 - x0) / imageWidth, u1 = (dx1 - x0) / imageWidth;
        // OpenGL textures start at the bottom row, so v runs from the top edge (1) to the bottom edge (0).
        float vTop = 1 - (dy0 - y0) / imageHeight, vBottom = 1 - (dy1 - y0) / imageHeight;
        draw.addImage(shownId, dx0, dy0, dx1, dy1, u0, vTop, u1, vBottom);
        draw.addRect(x, y, x + cw, y + ch, ImGui.getColorU32(ImGuiCol.Border), 6);
        if (inControl) {
            int accent = ImGui.getColorU32(ImGuiCol.CheckMark);
            draw.addRect(x + 1, y + 1, x + cw - 1, y + ch - 1, accent, 6, 0, 3);
            String badge = "● " + LuaUiText.text("控制中 · Esc 返回", "Controlling · Esc to return");
            var size = ImGui.calcTextSize(badge);
            draw.addRectFilled(x + 10, y + 10, x + size.x + 28, y + size.y + 22, 0xC0000000, 6);
            draw.addText(x + 19, y + 16, 0xFF5AA0FF, badge);
        } else if (hovered) {
            String hint = LuaUiText.text("双击进入游戏控制", "Double-click to control the game");
            var size = ImGui.calcTextSize(hint);
            float hx = x + (cw - size.x) / 2, hy = y + 12;
            draw.addRectFilled(hx - 10, hy - 5, hx + size.x + 10, hy + size.y + 5, 0xB0000000, 8);
            draw.addText(hx, hy, 0xFFFFFFFF, hint);
        }
        if (showInfo) {
            String info = textureWidth + "×" + textureHeight + "  →  " + Math.round(Math.min(imageWidth, cw)) + "×" + Math.round(Math.min(imageHeight, ch))
                    + (paused ? "  · " + LuaUiText.text("已暂停", "paused") : "");
            var size = ImGui.calcTextSize(info);
            draw.addRectFilled(x + 8, y + ch - size.y - 14, x + size.x + 22, y + ch - 6, 0xA0000000, 5);
            draw.addText(x + 15, y + ch - size.y - 10, 0xFFE8EEF8, info);
        }
    }
}
