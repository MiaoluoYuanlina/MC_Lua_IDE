package wiki.ica.xiaomiao.mcluaide.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

/** Literal selection matches, searched/cached only in the currently visible source ranges. */
final class LuaSelectionOccurrences {
    record Range(int start, int end) { }
    private String source = "", needle = "";
    private int start, end;
    private final LinkedHashMap<Range, List<Range>> windows = new LinkedHashMap<>(32, .75F, true);

    void update(String value, int cursor, int anchor) {
        int from = Math.clamp(Math.min(cursor, anchor), 0, value.length());
        int to = Math.clamp(Math.max(cursor, anchor), from, value.length());
        if (source == value && start == from && end == to) return;
        source = value; start = from; end = to;
        needle = value.substring(from, to);
        if (needle.isBlank() || needle.length() == value.length()) needle = "";
        windows.clear();
    }

    boolean active() { return !needle.isEmpty(); }

    List<Range> visible(int begin, int end) {
        if (!active()) return List.of();
        begin = Math.clamp(begin, 0, source.length());
        end = Math.clamp(end, begin, source.length());
        Range window = new Range(begin, end);
        List<Range> cached = windows.get(window);
        if (cached != null) return cached;
        List<Range> matches = new ArrayList<>();
        int searchBegin = Math.max(0, begin - needle.length() + 1);
        int searchEnd = (int) Math.min(source.length(), (long) end + needle.length() - 1);
        for (int at = searchBegin; at < end;) {
            // The bounded overload avoids scanning an entire huge file for every visible row.
            int match = source.indexOf(needle, at, searchEnd);
            if (match < 0 || match >= end) break;
            int matchEnd = match + needle.length();
            if (matchEnd > begin && (match != start || matchEnd != this.end)) {
                matches.add(new Range(match, matchEnd));
                // Later overlapping matches cannot add visible pixels once this one reaches the edge.
                if (matchEnd >= end) break;
            }
            at = match + 1;
        }
        List<Range> result = List.copyOf(matches);
        windows.put(window, result);
        if (windows.size() > 128) windows.remove(windows.keySet().iterator().next());
        return result;
    }
}
