package wiki.ica.xiaomiao.mcluaide.client;

/** Headless menu placement and enabled-action regression. Real editor mutations are covered by the game probe. */
public final class LuaEditorContextMenuSmoke {
    public static void main(String[] args) {
        int checks = 0;
        var menu = new LuaEditorContextMenu();
        for (int width : new int[]{240, 320, 854, 1920}) for (int height : new int[]{240, 480, 1080}) {
            for (double x : new double[]{-40, 5, width / 2.0, width + 40}) for (double y : new double[]{-40, 5, height / 2.0, height + 40}) {
                menu.open(x, y, width, height);
                require(menu.x() >= 4 && menu.y() >= 4 && menu.x() + menu.width() <= width - 4
                        && menu.y() + menu.height() <= height - 4, "Menu escaped screen"); checks++;
                menu.availability(true, true, true);
                for (var action : LuaEditorContextMenu.Action.values()) {
                    double rowY = menu.y() + 3 + action.ordinal() * menu.rowHeight() + menu.rowHeight() / 2.0;
                    require(menu.at(menu.x() + 6, rowY) == action, "Wrong row action: " + action); checks++;
                }
                require(menu.at(menu.x() - 1, menu.y() + 9) == null && menu.at(menu.x() + 8, menu.y()) == null,
                        "Padding/outside click invoked action"); checks++;
            }
        }
        menu.availability(false, false, false);
        menu.move(1);
        require(menu.selected() == LuaEditorContextMenu.Action.SEARCH, "Disabled rows were not skipped"); checks++;
        menu.move(-1);
        require(menu.selected() == LuaEditorContextMenu.Action.SEARCH, "Single enabled row did not wrap"); checks++;
        for (var action : LuaEditorContextMenu.Action.values()) {
            require(menu.enabled(action) == (action == LuaEditorContextMenu.Action.SEARCH), "Wrong disabled action"); checks++;
        }
        menu.close(); require(!menu.contains(menu.x(), menu.y()) && menu.selected() == null, "Closed menu still interactive"); checks++;
        menu.open(30, 30, 320, 240); menu.availability(true, true, true);
        menu.move(-1); require(menu.selected() == LuaEditorContextMenu.Action.SEARCH, "Up did not start at last action"); checks++;
        menu.move(1); require(menu.selected() == LuaEditorContextMenu.Action.COPY, "Keyboard navigation did not wrap"); checks++;
        System.out.println("Editor context menu smoke passed: " + checks + " checks");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
