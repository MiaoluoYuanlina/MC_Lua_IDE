package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Drag-and-drop editor for the in-game debug HUD panels (variables, breakpoints, log, free-camera hint).
 * <p>
 * It covers the whole screen with a dimmed overlay and draws every panel at its real in-game position and size. A panel is moved
 * by dragging its body and resized by dragging an edge or corner; right-click opens the panel's UI settings. The geometry rules
 * (clamping, minimum sizes, edge hit tests) are the same {@link LuaHudLayout} code the in-game HUD uses, and changes are saved when
 * the mouse is released.
 */
final class HudLayoutEditor {
    private static final int HOVER_MARGIN_PIXELS = 6;
    private boolean active, dirty, popupWasOpen;
    private LuaHudLayout.Panel highlighted, dragging, contextPanel;
    private int dragEdges;
    private LuaHudLayout.Rect dragStart;

    boolean active() { return active; }

    /** Opens the editor; {@code focus} is drawn emphasised (the panel whose menu asked for it), or null. */
    void begin(LuaHudLayout.Panel focus) { active = true; highlighted = focus; dragging = null; dirty = false; }

    void end() {
        if (dirty) LuaHudLayout.current().save();
        active = false; dragging = null; dirty = false; highlighted = null;
    }

    /** Esc leaves the editor first, before it can close the page underneath. */
    boolean key(KeyEvent event) {
        if (!active || event.key() != GLFW.GLFW_KEY_ESCAPE) return false;
        end(); return true;
    }

    // ---- which setting belongs to which panel -------------------------------------------------------------------------

    /** The panel shows something the user can switch off: variables, breakpoints and logs. The camera hint cannot be hidden. */
    static boolean hasSwitch(LuaHudLayout.Panel panel) { return panel != LuaHudLayout.Panel.CAMERA; }
    static boolean visible(LuaHudLayout.Panel panel) {
        return switch (panel) {
            case VARIABLES -> LuaDebugHud.variablesEnabled();
            case BREAKPOINTS -> LuaBreakpointClient.showInfo();
            case LOGS -> LuaLogHud.enabled();
            case CAMERA -> true;
        };
    }
    static void setVisible(LuaHudLayout.Panel panel, boolean value) {
        switch (panel) {
            case VARIABLES -> LuaDebugHud.setVariablesEnabled(value);
            case BREAKPOINTS -> LuaBreakpointClient.setShowInfo(value);
            case LOGS -> LuaLogHud.setEnabled(value);
            case CAMERA -> { }
        }
    }
    private static String sample(LuaHudLayout.Panel panel) {
        return switch (panel) {
            case VARIABLES -> "level = 5\nname = \"MC\"\nitems = {...}";
            case BREAKPOINTS -> LuaUiText.text("● 第 12 行已暂停\n", "● Paused at line 12\n") + LuaDebugKeyBindings.runHint();
            case LOGS -> "[print] hello\n[warn] ...";
            case CAMERA -> LuaUiText.text("自由摄像机 · WASD 移动", "Free camera · WASD to move");
        };
    }

    // ---- UI settings, shared by the F12 list (right-click) and the editor (right-click on a panel) ------------------------

    /** Body of a popup: the panel's visibility, background transparency, resets and a shortcut into the drag editor. */
    void settingsPopup(LuaHudLayout.Panel panel) {
        var layout = LuaHudLayout.current();
        ImGui.textColored(.55f, .65f, 1f, 1f, LuaUiText.text(panel.label));
        ImGui.separator();
        if (hasSwitch(panel)) {
            boolean shown = visible(panel), next = NativeUi.check(LuaUiText.text("在游戏画面显示", "Show in game"), shown);
            if (next != shown) setVisible(panel, next);
        }
        int[] transparency = {layout.transparency(panel)};
        ImGui.textUnformatted(LuaUiText.text("背景透明度", "Background transparency"));
        ImGui.setNextItemWidth(220);
        if (ImGui.sliderInt("##transparency", transparency, 0, 100, "%d%%")) { layout.setTransparency(panel, transparency[0]); dirty = true; }
        if (ImGui.isItemDeactivatedAfterEdit()) { layout.save(); dirty = false; }
        NativeUi.action(LuaUiText.text("恢复默认透明度", "Default transparency"), () -> { layout.resetTransparency(panel); layout.save(); });
        NativeUi.action(LuaUiText.text("重置位置和大小", "Reset position and size"), () -> {
            layout.resetPanel(panel); layout.save();
        });
        if (!active) {
            ImGui.separator();
            NativeUi.action(LuaUiText.text("拖动调整位置…", "Drag to reposition…"), () -> { begin(panel); ImGui.closeCurrentPopup(); });
        }
    }

    // ---- the editor overlay --------------------------------------------------------------------------------------------

    void draw() {
        if (!active) return;
        var window = Minecraft.getInstance().getWindow();
        int guiWidth = Math.max(1, window.getGuiScaledWidth()), guiHeight = Math.max(1, window.getGuiScaledHeight());
        var viewport = ImGui.getMainViewport();
        float originX = viewport.getPosX(), originY = viewport.getPosY(), width = viewport.getSizeX(), height = viewport.getSizeY();
        // GUI units (what the HUD uses) to ImGui coordinates; independent of OS scaling.
        float scaleX = width / guiWidth, scaleY = height / guiHeight;
        var layout = LuaHudLayout.current();

        ImGui.setNextWindowPos(originX, originY);
        ImGui.setNextWindowSize(width, height);
        if (!popupWasOpen) ImGui.setNextWindowFocus(); // refocusing every frame would dismiss an open context menu
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        boolean open = ImGui.begin("##hud_layout_editor", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoBackground);
        ImGui.popStyleVar(2);
        if (open) {
            var draw = ImGui.getWindowDrawList();
            draw.addRectFilled(originX, originY, originX + width, originY + height, 0x96000000);
            float mouseX = ImGui.getMousePosX(), mouseY = ImGui.getMousePosY();
            boolean popupOpen = ImGui.isPopupOpen("hud_layout_context");

            // The topmost panel under the pointer (later panels are drawn on top).
            LuaHudLayout.Panel hovered = null;
            var panels = LuaHudLayout.Panel.values();
            for (int i = panels.length - 1; i >= 0 && hovered == null; i--) {
                var rect = layout.bounds(panels[i], guiWidth, guiHeight);
                float left = originX + rect.x() * scaleX, top = originY + rect.y() * scaleY;
                float right = left + rect.width() * scaleX, bottom = top + rect.height() * scaleY;
                if (mouseX >= left - HOVER_MARGIN_PIXELS && mouseX <= right + HOVER_MARGIN_PIXELS
                        && mouseY >= top - HOVER_MARGIN_PIXELS && mouseY <= bottom + HOVER_MARGIN_PIXELS) hovered = panels[i];
            }

            if (dragging == null && !popupOpen && hovered != null) {
                var rect = layout.bounds(hovered, guiWidth, guiHeight);
                double guiMouseX = (mouseX - originX) / scaleX, guiMouseY = (mouseY - originY) / scaleY;
                int edges = LuaHudLayout.edges(rect, guiMouseX, guiMouseY);
                ImGui.setMouseCursor(cursorFor(edges));
                if (ImGui.isMouseClicked(0)) { dragging = hovered; dragEdges = edges; dragStart = rect; highlighted = hovered; }
                else if (ImGui.isMouseClicked(1)) { contextPanel = hovered; highlighted = hovered; ImGui.openPopup("hud_layout_context"); }
            }
            if (dragging != null) {
                ImGui.setMouseCursor(cursorFor(dragEdges));
                if (ImGui.isMouseDown(0)) {
                    var delta = ImGui.getMouseDragDelta(0, 0);
                    var moved = LuaHudLayout.drag(dragging, dragStart, dragEdges, Math.round(delta.x / scaleX), Math.round(delta.y / scaleY), guiWidth, guiHeight);
                    layout.set(dragging, moved, guiWidth, guiHeight); dirty = true;
                } else { dragging = null; if (dirty) { layout.save(); dirty = false; } }
            }

            int accent = ImGui.getColorU32(ImGuiCol.CheckMark), text = 0xFFFFFFFF, dim = 0xFFC8C8C8;
            for (var panel : panels) {
                var rect = layout.bounds(panel, guiWidth, guiHeight);
                float left = originX + rect.x() * scaleX, top = originY + rect.y() * scaleY;
                float right = left + rect.width() * scaleX, bottom = top + rect.height() * scaleY;
                boolean emphasised = panel == dragging || panel == hovered && !popupOpen || panel == highlighted || panel == contextPanel && popupOpen;
                // The real background colour, but never so transparent that the preview becomes invisible.
                int argb = layout.backgroundColor(panel);
                int background = NativeUi.rgba((Math.max(0x90, argb >>> 24) << 24) | (argb & 0xFFFFFF));
                draw.addRectFilled(left, top, right, bottom, background, 6);
                draw.addRect(left, top, right, bottom, emphasised ? accent : 0x88FFFFFF, 6, 0, emphasised ? 3 : 1);
                draw.pushClipRect(left + 2, top + 2, right - 2, bottom - 2, true);
                draw.addText(left + 8, top + 6, text, LuaUiText.text(panel.label) + (visible(panel) ? "" : "  · " + LuaUiText.text("已关闭", "off")));
                draw.addText(left + 8, top + 6 + ImGui.getTextLineHeightWithSpacing(), dim, sample(panel));
                draw.popClipRect();
                // Resize handle hint in the corner.
                draw.addTriangleFilled(right - 12, bottom - 2, right - 2, bottom - 12, right - 2, bottom - 2, emphasised ? accent : 0xAAFFFFFF);
            }

            // Instructions and the exit buttons.
            String hint = LuaUiText.text("拖动浮窗移动 · 拖动边缘缩放 · 右键打开 UI 设置 · Esc 完成", "Drag a panel to move · drag an edge to resize · right-click for UI settings · Esc to finish");
            var hintSize = ImGui.calcTextSize(hint);
            float hintX = originX + (width - hintSize.x) / 2, hintY = originY + 14;
            draw.addRectFilled(hintX - 12, hintY - 6, hintX + hintSize.x + 12, hintY + hintSize.y + 6, 0xC0000000, 8);
            draw.addText(hintX, hintY, text, hint);
            ImGui.setCursorScreenPos(originX + width / 2 - 110, hintY + hintSize.y + 16);
            NativeUi.action(LuaUiText.text("完成", "Done"), this::end);
            ImGui.sameLine(); NativeUi.action(LuaUiText.text("全部重置", "Reset all"), () -> { layout.reset(); layout.save(); });

            popupWasOpen = false;
            if (ImGui.beginPopup("hud_layout_context")) {
                popupWasOpen = true;
                if (contextPanel != null) settingsPopup(contextPanel);
                ImGui.endPopup();
            }
        }
        ImGui.end();
    }

    private static int cursorFor(int edges) {
        boolean horizontal = (edges & 3) != 0, vertical = (edges & 12) != 0;
        if (edges == 0) return ImGuiMouseCursor.ResizeAll;
        if (horizontal && vertical) return ((edges & 1) != 0) == ((edges & 4) != 0) ? ImGuiMouseCursor.ResizeNWSE : ImGuiMouseCursor.ResizeNESW;
        return horizontal ? ImGuiMouseCursor.ResizeEW : ImGuiMouseCursor.ResizeNS;
    }
}
