package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.network.chat.Style;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultilineTextFieldAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.StringViewAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Display-only horizontal scrolling; the text field always owns the unmodified source. */
public final class LuaEditorViewport {
    private static final Map<MultiLineEditBox, State> EDITORS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int UNWRAPPED_WIDTH = 1_000_000_000;

    private static final class State {
        final Font font;
        final boolean wrap;
        String source;
        int longestLine;
        double horizontal;
        int dragAxis;
        double grabOffset;
        boolean mouseSeeking;
        final LuaSelectionOccurrences occurrences = new LuaSelectionOccurrences();
        State(Font font, boolean wrap) { this.font = font; this.wrap = wrap; }
    }

    private LuaEditorViewport() { }

    static void register(MultiLineEditBox editor, Font font, boolean wrap) { EDITORS.put(editor, new State(font, wrap)); }

    static int flowWidth(boolean wrap, int viewportWidth, float scale) {
        return wrap ? Math.max(1, Math.round((viewportWidth - 8) / Math.max(.10F, scale))) : UNWRAPPED_WIDTH;
    }

    public static int offset(MultiLineEditBox editor) {
        State state = EDITORS.get(editor);
        if (state == null || state.wrap) return 0;
        refresh(editor, state);
        return (int) Math.round(state.horizontal);
    }

    static boolean scroll(MultiLineEditBox editor, double horizontal, double vertical, boolean shift, float sensitivity) {
        State state = EDITORS.get(editor);
        if (state == null || state.wrap || (!shift && horizontal == 0)) return false;
        refresh(editor, state);
        state.horizontal = Math.clamp(state.horizontal - (shift ? vertical + horizontal : horizontal) * 30 * sensitivity,
                0, maximum(editor, state));
        return true;
    }

    static void restoreOffset(MultiLineEditBox editor, int offset) {
        State state = EDITORS.get(editor);
        if (state == null || state.wrap) return;
        refresh(editor, state);
        state.horizontal = Math.clamp(offset, 0, maximum(editor, state));
        revealCursor(editor);
    }

    public static void revealCursor(MultiLineEditBox editor) {
        State state = EDITORS.get(editor);
        if (state == null || state.wrap || state.mouseSeeking || state.dragAxis != 0) return;
        refresh(editor, state);
        var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        int cursor = Math.clamp(field.cursor(), 0, field.value().length());
        int begin = field.value().lastIndexOf('\n', cursor - 1) + 1;
        double caret = state.font.width(field.value().substring(begin, cursor)) * scale(editor);
        double visible = Math.max(1, editor.getWidth() - 12);
        if (caret < state.horizontal) state.horizontal = caret;
        else if (caret > state.horizontal + visible) state.horizontal = caret - visible;
        state.horizontal = Math.clamp(state.horizontal, 0, maximum(editor, state));
    }

    private static void refresh(MultiLineEditBox editor, State state) {
        String source = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField().value();
        if (!source.equals(state.source)) {
            state.source = source;
            state.longestLine = 0;
            for (int start = 0; start < source.length();) {
                int end = source.indexOf('\n', start);
                if (end < 0) end = source.length();
                state.longestLine = Math.max(state.longestLine, state.font.width(source.substring(start, end)));
                start = end + 1;
            }
        }
        state.horizontal = Math.clamp(state.horizontal, 0, maximum(editor, state));
    }

    private static double maximum(MultiLineEditBox editor, State state) {
        return Math.max(0, state.longestLine * scale(editor) - Math.max(1, editor.getWidth() - 12));
    }
    private static float scale(MultiLineEditBox editor) {
        return LuaSyntaxHighlighter.scale(editor) * LuaSyntaxHighlighter.cellWidth(editor);
    }

    public static int lineHeight(MultiLineEditBox editor) {
        return Math.max(1, Math.round(9 * LuaSyntaxHighlighter.scale(editor) * LuaSyntaxHighlighter.lineHeight(editor)));
    }

    /** Input and rendering must use the same rounded row height, even at fractional zoom. */
    public static void seekCursorToPoint(MultiLineEditBox editor, MultilineTextField field, double x, double y) {
        State state = EDITORS.get(editor);
        boolean previous = state != null && state.mouseSeeking;
        // Clicking inside the viewport must not move it before double-click hit testing.
        if (state != null) state.mouseSeeking = x >= 0 && x <= editor.getWidth() - 8;
        try {
            field.seekCursorToPoint((x + offset(editor)) / Math.max(.001F, scale(editor)),
                    y * 9.0 / lineHeight(editor));
        } finally { if (state != null) state.mouseSeeking = previous; }
    }

    record Rectangle(int left, int top, int right, int bottom) { }

    /** Document-space rectangles; the widget's pose/scissor applies scrolling exactly once. */
    static List<Rectangle> selectionRectangles(MultiLineEditBox editor) {
        State state = EDITORS.get(editor);
        if (state == null) return List.of();
        var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        var access = (MultilineTextFieldAccessor) field;
        int start = Math.min(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
        int end = Math.max(access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
        if (start == end) return List.of();
        String source = field.value();
        int rowHeight = lineHeight(editor), index = 0;
        List<Rectangle> rectangles = new ArrayList<>();
        for (Object rawView : field.iterateLines()) {
            var view = (StringViewAccessor) rawView;
            int top = editor.getY() + 4 + index++ * rowHeight;
            if (top + rowHeight < editor.getY() + editor.scrollAmount()) continue;
            if (top >= editor.getBottom() + editor.scrollAmount()) break;
            int begin = view.mcLuaIde$getBeginIndex(), limit = view.mcLuaIde$getEndIndex();
            if (start > limit || end <= begin) continue;
            int partStart = Math.max(begin, start), partEnd = Math.min(limit, end);
            if (partStart == partEnd && end <= limit) continue;
            int left = editor.getX() + 4 + Math.round(state.font.width(source.substring(begin, partStart)) * scale(editor));
            int right = editor.getX() + 4 + Math.round(state.font.width(source.substring(begin, partEnd)) * scale(editor));
            // A selection through the newline includes the actual long row, not just the screen width.
            if (end > limit) right = Math.max(right, editor.getRight() - 4 + offset(editor));
            rectangles.add(new Rectangle(left, top, Math.max(left + 1, right), top + rowHeight));
        }
        return rectangles;
    }

    public static void extractSelection(GuiGraphicsExtractor graphics, MultiLineEditBox editor) {
        for (Rectangle rect : selectionRectangles(editor))
            graphics.textHighlight(rect.left(), rect.top(), rect.right(), rect.bottom(), true);
    }

    static void extractOccurrences(GuiGraphicsExtractor graphics, MultiLineEditBox editor) {
        for (Rectangle rect : occurrenceRectangles(editor))
            graphics.fill(rect.left(), rect.top(), rect.right(), rect.bottom(), 0x66598CC4);
    }

    /** Screen-space, visible-only matches; this does not modify the real selection or Ctrl+F state. */
    static List<Rectangle> occurrenceRectangles(MultiLineEditBox editor) {
        State state = EDITORS.get(editor);
        if (state == null) return List.of();
        var field = ((MultiLineEditBoxAccessor) editor).mcLuaIde$getTextField();
        var access = (MultilineTextFieldAccessor) field;
        String source = field.value();
        state.occurrences.update(source, access.mcLuaIde$getCursor(), access.mcLuaIde$getSelectCursor());
        if (!state.occurrences.active()) return List.of();
        int rowHeight = lineHeight(editor), index = 0, horizontal = offset(editor);
        float scale = scale(editor);
        List<Rectangle> rectangles = new ArrayList<>();
        for (Object rawView : field.iterateLines()) {
            var view = (StringViewAccessor) rawView;
            int top = editor.getY() + 4 + index++ * rowHeight - (int) editor.scrollAmount();
            if (top + rowHeight < editor.getY()) continue;
            if (top >= editor.getBottom()) break;
            int begin = view.mcLuaIde$getBeginIndex(), end = view.mcLuaIde$getEndIndex();
            String row = source.substring(begin, end);
            int visibleBegin = begin + state.font.getSplitter().plainIndexAtWidth(row,
                    Math.max(0, (int) (horizontal / scale)), Style.EMPTY);
            int visibleEnd = begin + state.font.getSplitter().plainIndexAtWidth(row,
                    (int) Math.ceil((horizontal + editor.getWidth()) / scale), Style.EMPTY);
            if (visibleEnd < end) visibleEnd = source.offsetByCodePoints(visibleEnd, 1);
            int measuredTo = begin, measuredWidth = 0;
            for (LuaSelectionOccurrences.Range range : state.occurrences.visible(visibleBegin, visibleEnd)) {
                int partStart = Math.max(begin, range.start()), partEnd = Math.min(end, range.end());
                if (partStart >= partEnd) continue;
                // Sorted matches: measure the long hidden prefix once, not once per match.
                measuredWidth += state.font.width(source.substring(measuredTo, partStart));
                measuredTo = partStart;
                int left = editor.getX() + 4 - horizontal + Math.round(measuredWidth * scale);
                int right = editor.getX() + 4 - horizontal + Math.round((measuredWidth
                        + state.font.width(source.substring(partStart, partEnd))) * scale);
                int clippedLeft = Math.max(editor.getX() + 1, left), clippedTop = Math.max(editor.getY() + 1, top);
                int clippedRight = Math.min(editor.getRight() - 1, right), clippedBottom = Math.min(editor.getBottom() - 1, top + rowHeight);
                if (clippedRight > clippedLeft && clippedBottom > clippedTop)
                    rectangles.add(new Rectangle(clippedLeft, clippedTop, clippedRight, clippedBottom));
            }
        }
        return rectangles;
    }

    record Scrollbar(int left, int top, int right, int bottom, int thumbStart, int thumbSize,
                     double maximum, boolean vertical) {
        int trackStart() { return vertical ? top : left; }
        int trackLength() { return vertical ? bottom - top : right - left; }
        boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
        double valueAt(double coordinate, double grab) {
            return Math.clamp((coordinate - trackStart() - grab) / Math.max(1, trackLength() - thumbSize), 0, 1) * maximum;
        }
    }

    static Scrollbar verticalScrollbar(MultiLineEditBox editor) {
        int max = editor.maxScrollAmount(), height = editor.getHeight();
        if (max == 0) return null;
        int thumb = Math.clamp((int) ((float) height * height / (height + max)), 32, height - 8);
        // AbstractTextAreaWidget renders its vertical bar OUTSIDE getRight(), unlike
        // AbstractScrollArea. Use that same strip for hit testing and dragging.
        return new Scrollbar(editor.getRight(), editor.getY(), editor.getRight() + editor.scrollbarWidth(), editor.getBottom(),
                editor.scrollBarY(), thumb, max, true);
    }

    static Scrollbar horizontalScrollbar(MultiLineEditBox editor) {
        State state = EDITORS.get(editor);
        if (state == null || state.wrap) return null;
        refresh(editor, state);
        double max = maximum(editor, state);
        if (max == 0) return null;
        int left = editor.getX() + 4;
        int right = editor.getRight() - 4;
        int length = Math.max(1, right - left);
        int thumb = Math.clamp((int) Math.round(length * length / (length + max)), Math.min(20, length), length);
        int at = left + (int) Math.round((length - thumb) * state.horizontal / max);
        return new Scrollbar(left, editor.getBottom() - 4, right, editor.getBottom(), at, thumb, max, false);
    }

    static void extractHorizontalScrollbar(GuiGraphicsExtractor graphics, MultiLineEditBox editor, int mouseX, int mouseY) {
        Scrollbar bar = horizontalScrollbar(editor);
        if (bar == null || !editor.visible) return;
        State state = EDITORS.get(editor);
        graphics.fill(bar.left(), bar.top(), bar.right(), bar.bottom(), 0xFF222832);
        graphics.fill(bar.thumbStart(), bar.top(), bar.thumbStart() + bar.thumbSize(), bar.bottom(),
                state.dragAxis == 1 || bar.contains(mouseX, mouseY) ? 0xFF9FC9FF : 0xFF66788F);
    }

    private static Scrollbar scrollbarAt(MultiLineEditBox editor, double x, double y) {
        if (editor == null || !EDITORS.containsKey(editor) || !editor.active || !editor.visible) return null;
        Scrollbar bar = verticalScrollbar(editor);
        if (bar == null || !bar.contains(x, y)) bar = horizontalScrollbar(editor);
        return bar != null && bar.contains(x, y) ? bar : null;
    }

    static boolean scrollbarContains(MultiLineEditBox editor, double x, double y) {
        return scrollbarAt(editor, x, y) != null;
    }

    static boolean mouseClicked(MultiLineEditBox editor, double x, double y, int button) {
        State state = EDITORS.get(editor);
        if (state == null || button != 0 || !editor.active || !editor.visible) return false;
        Scrollbar bar = scrollbarAt(editor, x, y);
        if (bar == null) return false;
        double coordinate = bar.vertical() ? y : x;
        state.dragAxis = bar.vertical() ? 2 : 1;
        state.grabOffset = coordinate >= bar.thumbStart() && coordinate < bar.thumbStart() + bar.thumbSize()
                ? coordinate - bar.thumbStart() : bar.thumbSize() / 2.0;
        applyDrag(editor, state, bar, coordinate);
        return true;
    }

    static boolean mouseDragged(MultiLineEditBox editor, double x, double y, int button) {
        State state = EDITORS.get(editor);
        if (state == null || state.dragAxis == 0 || button != 0) return false;
        Scrollbar bar = state.dragAxis == 2 ? verticalScrollbar(editor) : horizontalScrollbar(editor);
        if (bar != null) applyDrag(editor, state, bar, bar.vertical() ? y : x);
        return true;
    }

    static boolean mouseReleased(MultiLineEditBox editor, int button) {
        State state = EDITORS.get(editor);
        if (state == null || state.dragAxis == 0 || button != 0) return false;
        state.dragAxis = 0;
        return true;
    }

    private static void applyDrag(MultiLineEditBox editor, State state, Scrollbar bar, double coordinate) {
        double amount = bar.valueAt(coordinate, state.grabOffset);
        if (bar.vertical()) editor.setScrollAmount(amount);
        else state.horizontal = amount;
    }
}
