package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Warning handling stays at the API boundary, not at the whole-script exception handler. */
final class LuaRuntimeWarnings {
    private final Consumer<String> output;
    private final LongSupplier clock;
    private final int lineLimit;
    private final int charLimit;
    private final Map<String, Long> lastWarnings = new LinkedHashMap<>();
    private int lines;
    private boolean suppressionReported;

    LuaRuntimeWarnings(Consumer<String> output, int lineLimit, int charLimit) {
        this(output, lineLimit, charLimit, System::nanoTime);
    }

    LuaRuntimeWarnings(Consumer<String> output, int lineLimit, int charLimit, LongSupplier clock) {
        this.output = output;
        this.lineLimit = Math.max(1, Math.min(50, lineLimit));
        this.charLimit = Math.max(1, charLimit);
        this.clock = clock;
    }

    void emit(String symbol, String reason, String location) {
        if (lines >= lineLimit) {
            if (!suppressionReported) {
                suppressionReported = true;
                write("⚠ 后续可恢复警告已省略，脚本继续运行（每次运行最多显示 " + lineLimit + " 条）");
            }
            return;
        }
        String key = symbol + '\n' + reason + '\n' + location;
        long now = clock.getAsLong();
        Long last = lastWarnings.get(key);
        if (last != null && now - last < 1_000_000_000L) return;
        if (lastWarnings.size() >= 128 && !lastWarnings.containsKey(key))
            lastWarnings.remove(lastWarnings.keySet().iterator().next());
        lastWarnings.put(key, now);
        lines++;
        write("⚠ " + (location.isEmpty() ? "" : location + " · ") + symbol + "：" + reason
                + "；已跳过本次调用，脚本继续运行");
    }

    private void write(String text) {
        text = text.replace('\n', ' ').replace('\r', ' ');
        output.accept(text.length() <= charLimit ? text : text.substring(0, Math.max(0, charLimit - 1)) + "…");
    }

    static void install(LuaTable mc, Globals globals, LuaRuntimeWarnings warnings, Runnable checkRuntime) {
        Set<LuaTable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        wrap(mc, "MC", globals, warnings, checkRuntime, visited);
    }

    private static void wrap(LuaTable table, String path, Globals globals, LuaRuntimeWarnings warnings,
                             Runnable checkRuntime, Set<LuaTable> visited) {
        if (!visited.add(table)) return; // Uppercase/lowercase namespace aliases share a table.
        LuaValue[] keys = table.keys();
        java.util.Arrays.sort(keys, java.util.Comparator.comparing(LuaValue::tojstring));
        for (LuaValue key : keys) {
            LuaValue original = table.get(key);
            String symbol = path + "." + key.tojstring();
            if (original instanceof LuaTable child) wrap(child, symbol, globals, warnings, checkRuntime, visited);
            else if (original.isfunction()) table.set(key, new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try { return original.invoke(args); }
                    catch (LuaRecoverableError error) {
                        checkRuntime.run(); // Cancellation, cleanup deadlines and runtime limits remain fatal.
                        String reason = error.getMessage();
                        warnings.emit(symbol, reason, location(globals));
                        return LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaValue.valueOf(reason)});
                    }
                }
            });
        }
    }

    private static String location(Globals globals) {
        if (globals.debuglib == null) return "";
        for (String line : globals.debuglib.traceback(1).split("\n")) {
            String frame = line.strip();
            if (frame.matches(".*:[0-9]+.*")) {
                int suffix = frame.indexOf(": in ");
                return suffix < 0 ? frame : frame.substring(0, suffix);
            }
        }
        return "";
    }
}
