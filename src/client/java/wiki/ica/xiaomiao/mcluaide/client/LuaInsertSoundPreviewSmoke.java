package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;

public final class LuaInsertSoundPreviewSmoke {
    public static void main(String[] args) {
        List<String> operations = new ArrayList<>();
        String[] active = {null};
        var preview = new LuaInsertSoundPreview(new LuaInsertSoundPreview.Audio() {
            public String play(String id) {
                if (active[0] != null) throw new AssertionError("Multiple preview instances");
                operations.add("play:" + id);
                if (id.equals("missing")) return "未加载";
                if (id.equals("failed")) throw new IllegalStateException("fixture");
                active[0] = id; return null;
            }
            public void stop() { if (active[0] != null) operations.add("stop:" + active[0]); active[0] = null; }
        });
        preview.pointer(10, 10, "first"); require("first".equals(active[0]), "Hover did not play");
        int size = operations.size();
        for (int i = 0; i < 100; i++) preview.pointer(10, 10, "first");
        require(operations.size() == size, "Stationary hover restarted sound");
        preview.pointer(10, 30, "second");
        require(operations.equals(List.of("play:first", "stop:first", "play:second")), "Switch did not stop before play");
        preview.keyboard("third"); preview.pointer(10, 30, "second");
        require("third".equals(active[0]), "Stationary mouse overrode keyboard selection");
        preview.pointer(11, 30, "second"); require("second".equals(active[0]), "Mouse movement did not resume hover selection");
        preview.pointer(11, 99, null); require(active[0] == null, "Leaving rows did not stop");
        preview.keyboard("third"); preview.pointer(11, 99, null);
        require("third".equals(active[0]), "Keyboard playback required hovering a row");
        preview.mouseNavigation(); preview.pointer(11, 99, null); require(active[0] == null, "Scroll did not relinquish keyboard mode");
        preview.pointer(20, 20, "first"); preview.suspend(); preview.pointer(20, 20, "first");
        require(active[0] == null, "Insert/focus loss restarted under stationary pointer");
        preview.pointer(21, 20, "first"); require("first".equals(active[0]), "New mouse input did not resume");
        preview.clear(); preview.keyboard("third"); preview.pointer(500, 500, null);
        require("third".equals(active[0]), "First pointer observation cancelled key selection");
        preview.clear(); require(active[0] == null && preview.currentId() == null, "Close/hide did not clear instance");
        preview.pointer(0, 0, "missing"); require(preview.message().equals("未加载"), "Missing resource explanation lost");
        size = operations.size(); preview.pointer(0, 0, "missing"); require(operations.size() == size, "Missing resource spammed play");
        preview.pointer(1, 0, "failed"); require(!preview.message().isBlank() && active[0] == null, "Backend failure leaked instance or exception");
        for (int i = 0; i < 1000; i++) preview.keyboard("sound_" + i);
        require("sound_999".equals(active[0]) && preview.message().isBlank(), "Rapid switching failed");
        preview.clear(); require(active[0] == null, "Rapid switching cleanup failed");
        System.out.println("Insert sound preview smoke passed: hover, keyboard/mouse arbitration, stop ordering, no replay, suspend, hide, missing resources and 1,000 rapid switches");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
