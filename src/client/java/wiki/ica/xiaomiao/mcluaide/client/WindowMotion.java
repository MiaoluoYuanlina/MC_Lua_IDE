package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

import java.util.HashMap;
import java.util.Map;

/**
 * Smooth transitions for ImGui windows, which have none of their own.
 * <p>
 * Dear ImGui places docked windows instantly, so a window's real rectangle cannot be tweened. Instead every change of
 * rectangle (opening, closing, docking to a side, undocking, layout jumps) is shown with a "ghost frame": an accent
 * outline whose four edges are interpolated independently from the old rectangle to the new one over the same
 * duration, so left, top, right and bottom arrive together. Opening slides the frame in from the nearest screen side
 * and closing slides it back out. The real window cross-fades underneath.
 * <ul>
 *   <li>appear / disappear: opacity eases in and out (disappearing needs the caller to keep drawing until {@link #faded});</li>
 *   <li>moving: the window lifts (slightly translucent, accent border) while it is being dragged.</li>
 * </ul>
 */
final class WindowMotion {
    private static final float GHOST_SECONDS = .30f;

    private static final class State {
        float fade, settle, lift, x, y, w, h;
        boolean docked, seen, moving, closing;
        // ghost frame: edges x1,y1,x2,y2 of the start and end rectangles, progress 0..1 (>= 1 means finished)
        final float[] from = new float[4], to = new float[4];
        float ghost = 1;
        boolean out;
    }
    private final Map<String, State> states = new HashMap<>();
    private int pushedVars, pushedColors;

    private State state(String id) { return states.computeIfAbsent(id, key -> new State()); }

    private static float ease(float t) { return t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }

    /** The same rectangle pushed toward the nearest screen side, far enough to read as sliding in from that side. */
    private static void slid(float[] rect, float[] result) {
        var viewport = ImGui.getMainViewport();
        float width = rect[2] - rect[0], shift = Math.min(width * .7f, 360);
        boolean left = (rect[0] + rect[2]) * .5f < viewport.getPosX() + viewport.getSizeX() * .5f;
        float dx = left ? -shift : shift;
        result[0] = rect[0] + dx; result[1] = rect[1]; result[2] = rect[2] + dx; result[3] = rect[3];
    }

    /** Current interpolated ghost rectangle, so a new transition can continue from where the last one is. */
    private static void ghostNow(State s, float[] result) {
        float e = ease(Math.clamp(s.ghost, 0f, 1f));
        for (int i = 0; i < 4; i++) result[i] = s.from[i] + (s.to[i] - s.from[i]) * e;
    }

    private void startGhost(State s, float[] from, float[] to, boolean out) {
        float[] start = from.clone();
        if (s.ghost < 1) ghostNow(s, start);
        System.arraycopy(start, 0, s.from, 0, 4); System.arraycopy(to, 0, s.to, 0, 4);
        s.ghost = 0; s.out = out;
    }

    /** Call before {@code begin}: advances the animation and pushes the style for this window. */
    void begin(String id, boolean closing) {
        var s = state(id);
        if (closing && !s.closing && s.seen) {
            float[] now = {s.x, s.y, s.x + s.w, s.y + s.h}, away = new float[4];
            slid(now, away); startGhost(s, now, away, true);
        }
        s.closing = closing;
        s.fade = ImGuiIdeRuntime.approach(s.fade, closing ? 0 : 1, closing ? 18 : 13);
        s.settle = ImGuiIdeRuntime.approach(s.settle, 0, 8);
        s.lift = ImGuiIdeRuntime.approach(s.lift, s.moving ? 1 : 0, 16);
        float alpha = (.12f + .88f * ImGuiIdeRuntime.easeOut(s.fade)) * (1 - .6f * s.settle * s.settle) * (1 - .1f * s.lift);
        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, Math.max(.02f, alpha)); pushedVars = 1;
        pushedColors = 0;
        if (s.lift > .02f) { ImGui.pushStyleColor(ImGuiCol.Border, .5f, .6f, 1f, .9f * s.lift); pushedColors = 1; }
    }

    /** Call right after {@code begin} (whatever it returned): detects dock changes, layout jumps and dragging. */
    void observe(String id) {
        var s = state(id);
        boolean docked = ImGui.isWindowDocked(), down = ImGui.isMouseDown(0);
        float x = ImGui.getWindowPosX(), y = ImGui.getWindowPosY(), w = ImGui.getWindowWidth(), h = ImGui.getWindowHeight();
        float[] now = {x, y, x + w, y + h};
        if (!s.seen) {
            float[] start = new float[4]; slid(now, start); startGhost(s, start, now, false);
        } else {
            float[] before = {s.x, s.y, s.x + s.w, s.y + s.h};
            boolean jumped = Math.abs(w - s.w) > 24 || Math.abs(h - s.h) > 24 || Math.abs(x - s.x) > 24 || Math.abs(y - s.y) > 24;
            if (!s.closing && (docked != s.docked || !down && jumped)) {
                s.settle = Math.max(s.settle, docked != s.docked ? 1f : .75f);
                startGhost(s, before, now, false);
            } else if (s.ghost < 1 && !s.out) System.arraycopy(now, 0, s.to, 0, 4); // keep tracking the live rectangle
            s.moving = down && !docked && Math.abs(x - s.x) + Math.abs(y - s.y) > .5f;
        }
        s.docked = docked; s.x = x; s.y = y; s.w = w; s.h = h; s.seen = true;
    }

    /** Call after {@code end}: restores the style pushed by {@link #begin}. */
    void end() {
        if (pushedColors > 0) ImGui.popStyleColor(pushedColors);
        if (pushedVars > 0) ImGui.popStyleVar(pushedVars);
        pushedColors = pushedVars = 0;
    }

    /**
     * Draws and advances every running ghost frame. Call once per frame after all windows are submitted.
     * The outline sits on the foreground list so it is visible above whatever the window is moving across.
     */
    void drawGhosts() {
        float step = ImGuiIdeRuntime.delta() / GHOST_SECONDS;
        var draw = ImGui.getForegroundDrawList();
        int accent = ImGui.getColorU32(ImGuiCol.CheckMark);
        float[] rect = new float[4];
        for (var s : states.values()) {
            if (s.ghost >= 1) continue;
            s.ghost = Math.min(1, s.ghost + step);
            ghostNow(s, rect);
            float strong = (float) Math.pow(1 - s.ghost, .6);
            draw.addRectFilled(rect[0], rect[1], rect[2], rect[3], withAlpha(accent, .07f * strong), 10);
            draw.addRect(rect[0], rect[1], rect[2], rect[3], withAlpha(accent, .9f * strong), 10, 0, 2);
        }
    }
    private static int withAlpha(int abgr, float alpha) {
        return (abgr & 0x00FFFFFF) | (Math.clamp(Math.round(alpha * 255), 0, 255) << 24);
    }

    /** True once a closing window has fully faded out and its slide-out frame has finished. */
    boolean faded(String id) { var s = state(id); return s.fade < .04f && s.ghost >= 1; }
    /** Forget a window so the next time it opens it slides and fades in from nothing. */
    void reset(String id) { states.remove(id); }
}
