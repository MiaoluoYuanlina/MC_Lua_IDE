package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import wiki.ica.xiaomiao.mcluaide.client.mixin.MultilineTextFieldAccessor;
import wiki.ica.xiaomiao.mcluaide.client.mixin.StringViewAccessor;
import java.util.*;

/** Filters only display rows. Lua values, selections, saves and execution retain the full source. */
public final class LuaFunctionFolding {
    private static final Map<MultilineTextField,State> FIELDS=Collections.synchronizedMap(new WeakHashMap<>());
    enum Action { EXPAND_ALL, COLLAPSE_ALL, COLLAPSE_FUNCTIONS }
    record Snapshot(String sourceHash, List<Integer> starts) {
        static final Snapshot EMPTY = new Snapshot("", List.of());
        Snapshot {
            sourceHash = sourceHash != null && sourceHash.matches("[0-9a-f]{64}") ? sourceHash : "";
            starts = sourceHash.isEmpty() || starts == null ? List.of()
                    : starts.stream().filter(Objects::nonNull).filter(start -> start >= 0)
                    .distinct().sorted().limit(100_000).toList();
        }
    }
    public static final class State {
        private LuaFunctionModel model=LuaFunctionModel.parse("");
        private final Set<Integer> collapsed=new HashSet<>();
        private final NavigableMap<Integer,Integer> hiddenRanges=new TreeMap<>();
        LuaFunctionModel model(String source) {
            if(source.equals(model.source))return model;
            String old=model.source;int prefix=0;
            while(prefix<old.length()&&prefix<source.length()&&old.charAt(prefix)==source.charAt(prefix))prefix++;
            int suffix=0;
            while(suffix<old.length()-prefix&&suffix<source.length()-prefix&&old.charAt(old.length()-suffix-1)==source.charAt(source.length()-suffix-1))suffix++;
            int oldEnd=old.length()-suffix,delta=source.length()-old.length();Set<Integer> next=new HashSet<>();
            for(int start:collapsed){if(start<prefix)next.add(start);else if(start>=oldEnd)next.add(start+delta);}
            model=LuaFunctionModel.parse(source);collapsed.clear();
            for(var f:model.folds)if(f.foldable()&&next.contains(f.start()))collapsed.add(f.start());
            rebuildHiddenRanges();
            return model;
        }
        boolean collapsed(LuaFunctionModel.Fold fold){return collapsed.contains(fold.start());}
        boolean hidden(int offset) {var range=hiddenRanges.floorEntry(offset);return range!=null&&offset<range.getValue();}
        boolean reveal(int offset){
            boolean changed=collapsed.removeIf(start->model.folds.stream().anyMatch(f->f.start()==start&&offset>=f.hiddenStart()&&offset<f.hiddenEnd()));
            if(changed)rebuildHiddenRanges();return changed;
        }
        boolean beforeEdit(int start,int end){
            boolean changed=collapsed.removeIf(anchor->model.folds.stream().anyMatch(f->f.start()==anchor&&start<=f.hiddenEnd()&&end>=f.declarationStart()));
            if(changed)rebuildHiddenRanges();return changed;
        }
        void toggle(LuaFunctionModel.Fold fold){if(!collapsed.add(fold.start()))collapsed.remove(fold.start());rebuildHiddenRanges();}
        void apply(Action action) {
            if (action == Action.EXPAND_ALL) collapsed.clear();
            else for (var fold : model.folds)
                if (fold.foldable() && (action == Action.COLLAPSE_ALL || fold instanceof LuaFunctionModel.Function))
                    collapsed.add(fold.start());
            rebuildHiddenRanges();
        }
        int visibleCursor(int offset) {
            int visible = Math.clamp(offset, 0, model.source.length());
            for (var fold : model.folds)
                if (collapsed(fold) && visible >= fold.hiddenStart() && visible < fold.hiddenEnd()) visible = fold.start();
            return visible;
        }
        Snapshot snapshot(String source) {
            model(source);
            return collapsed.isEmpty() ? Snapshot.EMPTY : new Snapshot(hash(source), List.copyOf(collapsed));
        }
        void restore(String source, Snapshot snapshot) {
            model(source); collapsed.clear();
            // Never hide unrelated code when the server file changed since the IDE was closed.
            if (snapshot != null && !snapshot.starts().isEmpty() && hash(source).equals(snapshot.sourceHash())) {
                Set<Integer> saved = new HashSet<>(snapshot.starts());
                for (var fold : model.folds) if (fold.foldable() && saved.contains(fold.start())) collapsed.add(fold.start());
            }
            rebuildHiddenRanges();
        }
        private void rebuildHiddenRanges() {
            // Index merged display ranges once per fold change, not once per display row.
            NavigableMap<Integer,Integer> ranges=new TreeMap<>();
            for(var fold:model.folds)if(collapsed(fold))ranges.merge(fold.hiddenStart(),fold.hiddenEnd(),Math::max);
            hiddenRanges.clear();
            for(var range:ranges.entrySet()) {
                var previous=hiddenRanges.lastEntry();
                if(previous!=null&&range.getKey()<=previous.getValue())
                    hiddenRanges.put(previous.getKey(),Math.max(previous.getValue(),range.getValue()));
                else hiddenRanges.put(range.getKey(),range.getValue());
            }
        }
    }
    static void bind(MultilineTextField field,State state){if(state==null)FIELDS.remove(field);else FIELDS.put(field,state);}
    static State state(MultilineTextField field){return FIELDS.get(field);}
    public static void filter(MultilineTextField field) {
        State state=FIELDS.get(field);if(state==null)return;state.model(field.value());
        var access=(MultilineTextFieldAccessor)field;
        access.mcLuaIde$getDisplayLines().removeIf(raw->state.hidden(((StringViewAccessor)raw).mcLuaIde$getBeginIndex()));
    }
    public static void revealCursor(MultilineTextField field) {
        State state=FIELDS.get(field);if(state==null)return;state.model(field.value());
        if(state.reveal(field.cursor()))((MultilineTextFieldAccessor)field).mcLuaIde$reflowDisplayLines();
    }
    public static void beforeEdit(MultilineTextField field) {
        State state=FIELDS.get(field);if(state==null)return;state.model(field.value());var access=(MultilineTextFieldAccessor)field;
        if(state.beforeEdit(Math.min(field.cursor(),access.mcLuaIde$getSelectCursor()),Math.max(field.cursor(),access.mcLuaIde$getSelectCursor())))access.mcLuaIde$reflowDisplayLines();
    }
    static boolean toggle(MultilineTextField field,int line) {
        State state=FIELDS.get(field);if(state==null)return false;var f=state.model(field.value()).atLine(line);if(f==null)return false;
        if(!state.collapsed(f)){field.setSelecting(false);field.seekCursor(Whence.ABSOLUTE,f.start());}
        state.toggle(f);((MultilineTextFieldAccessor)field).mcLuaIde$reflowDisplayLines();return true;
    }
    static boolean apply(MultilineTextField field, Action action) {
        State state = FIELDS.get(field); if (state == null) return false;
        state.model(field.value()); state.apply(action);
        if (action != Action.EXPAND_ALL) {
            field.setSelecting(false);
            field.seekCursor(Whence.ABSOLUTE, state.visibleCursor(field.cursor()));
        }
        ((MultilineTextFieldAccessor)field).mcLuaIde$reflowDisplayLines();
        return true;
    }
    private static String hash(String source) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private LuaFunctionFolding() { }
}
