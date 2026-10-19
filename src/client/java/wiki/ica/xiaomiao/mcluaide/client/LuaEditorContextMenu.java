package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Editor popup geometry is independent of docked window bounds and uses logical UI coordinates. */
final class LuaEditorContextMenu {
    enum Action {
        COPY("复制", "Ctrl+C"), CUT("剪切", "Ctrl+X"), PASTE("粘贴", "Ctrl+V"),
        DELETE("删除", "Delete"), UNDO("撤销", "Ctrl+Z"), SEARCH("搜索", "Ctrl+F");
        final String label, shortcut;
        Action(String label, String shortcut) { this.label = label; this.shortcut = shortcut; }
    }
    private static final Action[] ACTIONS = Action.values();
    private int x, y, width, rowHeight, selected = -1;
    private boolean open;
    private boolean selection, clipboard, undo;
    private int lastMouseX = Integer.MIN_VALUE, lastMouseY = Integer.MIN_VALUE;

    void open(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        width = Math.min(210, Math.max(1, screenWidth - 8));
        rowHeight = Math.min(22, Math.max(1, (screenHeight - 14) / ACTIONS.length));
        x = Math.clamp((int) mouseX, 4, Math.max(4, screenWidth - width - 4));
        y = Math.clamp((int) mouseY, 4, Math.max(4, screenHeight - height() - 4));
        selected = -1; open = true;
        lastMouseX = lastMouseY = Integer.MIN_VALUE;
    }
    void availability(boolean selection, boolean clipboard, boolean undo) {
        this.selection = selection; this.clipboard = clipboard; this.undo = undo;
    }
    boolean enabled(Action action) {
        return switch (action) {
            case COPY, CUT, DELETE -> selection;
            case PASTE -> clipboard;
            case UNDO -> undo;
            case SEARCH -> true;
        };
    }
    boolean isOpen() { return open; }
    void close() { open = false; selected = -1; }
    int x() { return x; }
    int y() { return y; }
    int width() { return width; }
    int height() { return 6 + ACTIONS.length * rowHeight; }
    int rowHeight() { return rowHeight; }
    boolean contains(double mx, double my) {
        return open && mx >= x && mx < x + width && my >= y && my < y + height();
    }
    private int rowAt(double mx, double my) {
        if (!contains(mx, my) || my < y + 3 || my >= y + height() - 3) return -1;
        return ((int) my - y - 3) / rowHeight;
    }
    Action at(double mx, double my) {
        int row = rowAt(mx, my);
        return row >= 0 && row < ACTIONS.length && enabled(ACTIONS[row]) ? ACTIONS[row] : null;
    }
    void move(int direction) {
        int current = selected < 0 ? direction > 0 ? -1 : 0 : selected;
        for (int i = 0; i < ACTIONS.length; i++) {
            current = Math.floorMod(current + direction, ACTIONS.length);
            if (enabled(ACTIONS[current])) { selected = current; return; }
        }
    }
    Action selected() { return selected >= 0 && enabled(ACTIONS[selected]) ? ACTIONS[selected] : null; }
    void render(GuiGraphicsExtractor graphics, Font font, int mx, int my) {
        if (!open) return;
        int hovered = rowAt(mx, my);
        if (mx != lastMouseX || my != lastMouseY) {
            if (hovered >= 0) selected = hovered;
            lastMouseX = mx; lastMouseY = my;
        }
        graphics.fill(x + 2, y + 2, x + width + 2, y + height() + 2, 0x99000000);
        graphics.fill(x, y, x + width, y + height(), 0xFF1B2028);
        graphics.outline(x, y, width, height(), 0xFF91BFFF);
        for (int i = 0; i < ACTIONS.length; i++) {
            Action action = ACTIONS[i]; boolean available = enabled(action);
            int top = y + 3 + i * rowHeight;
            if (selected == i && available) graphics.fill(x + 2, top, x + width - 2, top + rowHeight, 0xFF304963);
            int baseline = top + Math.max(0, (rowHeight - 9) / 2);
            String shortcut = width >= 140 ? action.shortcut : "";
            graphics.text(font, font.plainSubstrByWidth(action.label, Math.max(1, width - font.width(shortcut) - 28)),
                    x + 9, baseline, available ? 0xFFECE7DE : 0xFF68717E);
            if (!shortcut.isEmpty()) graphics.text(font, shortcut, x + width - font.width(shortcut) - 9, baseline,
                    available ? 0xFF9DACBF : 0xFF56606C);
        }
    }
}
