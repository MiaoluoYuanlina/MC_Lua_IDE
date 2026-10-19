package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class LuaRuntimeWarningsSmoke {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    public static void main(String[] ignored) {
        Globals globals = JsePlatform.debugGlobals();
        LuaTable mc = new LuaTable(), block = new LuaTable();
        mc.set("Block", block); mc.set("block", block); mc.set("cycle", mc);
        globals.set("MC", mc);
        globals.set("debug", LuaValue.NIL); // Still keep server-internal source locations.
        block.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { throw new LuaRecoverableError("区块尚未加载"); }
        });
        block.set("ok", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return args; }
        });
        block.set("bad", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { throw new LuaError("区块尚未加载（普通错误不能按文本误分类）"); }
        });
        var output = new ArrayList<String>();
        AtomicLong time = new AtomicLong();
        var warnings = new LuaRuntimeWarnings(output::add, 5, 256, time::get);
        LuaRuntimeWarnings.install(mc, globals, warnings, () -> {});
        check(mc.get("block") == mc.get("Block"));
        globals.load("local v, reason = MC.Block.get()\nassert(v == nil and reason == '区块尚未加载')\n"
                + "local a,b,c = MC.Block.ok(1,'two',false)\nassert(a == 1 and b == 'two' and c == false)\n"
                + "assert(pcall(MC.block.get))\nassert(not pcall(MC.Block.bad))", "warning_smoke.lua").call();
        if (output.size() != 2) throw new AssertionError("Warning output: " + output);
        check(output.size() == 2);
        check(output.getFirst().startsWith("⚠ "));
        check(output.getFirst().contains("warning_smoke.lua:1"));
        check(LuaRecoverableError.find(new java.util.concurrent.CompletionException(new LuaRecoverableError("safe"))) != null);
        check(!(LuaRecoverableError.fromServer(new LuaError("hard"), "hard") instanceof LuaRecoverableError));
        output.clear();
        var bounded = new LuaRuntimeWarnings(output::add, 3, 40, time::get);
        for (int i = 0; i < 1000; i++) bounded.emit("MC.Block.get", "chunk", "test:1");
        check(output.size() == 1);
        time.set(1_000_000_000L); bounded.emit("MC.Block.get", "chunk", "test:1");
        check(output.size() == 2);
        for (int i = 0; i < 1000; i++) bounded.emit("MC.Block.get", "chunk" + i, "test:1");
        check(output.size() == 4); // Three warnings plus one suppression notice, no script failure.
        check(output.stream().allMatch(line -> line.length() <= 40));
        check(output.getLast().startsWith("⚠ "));
        LuaTable stopped = new LuaTable(), stoppedBlock = new LuaTable(); stopped.set("Block", stoppedBlock);
        stoppedBlock.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { throw new LuaRecoverableError("safe"); }
        });
        LuaRuntimeWarnings.install(stopped, globals, warnings, () -> { throw new LuaError("已停止"); });
        try { stopped.get("Block").get("get").call(); throw new AssertionError("Cancellation swallowed"); }
        catch (LuaError expected) { check(expected.getMessage().contains("已停止")); }
        System.out.println("Runtime warning smoke passed: " + checks + " checks");
    }
}
