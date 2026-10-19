package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.server.LuaLimitConfig;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Opt-in complete Fabric/Lua runner check. Only isolated build/interval-probe config, no save is opened. */
final class LuaIntervalProbe {
    private static CompletableFuture<Void> task;
    private static int checks;
    private static boolean finished;

    static void tick(Minecraft client) {
        if (finished || client.getOverlay() != null) return;
        if (task == null) task = CompletableFuture.runAsync(() -> {
            try { test(); } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        else if (task.isDone()) {
            task.join(); finished = true;
            System.out.println("Interval real Lua runner probe passed: " + checks + " checks; millisecond callbacks without ticks, independent sleeps, off/clear, errors, limits, debug/normal, breakpoint and restart");
            client.stop();
        }
    }

    private static void test() throws Exception {
        testLifecycle();
        String shortTimer = """
                local id
                id = MC.Event.onInterval(5, function(event)
                    assert(event.type == 'interval' and event.registrationId == id)
                    assert(event.intervalMs == 5 and event.elapsedMs >= 5 and event.deltaMs > 0)
                    assert(event.elapsedMs == event.scheduledCount * event.intervalMs)
                    assert(event.actualElapsedMs >= event.elapsedMs and event.actualDeltaMs > 0)
                    assert(event.scheduledCount == event.count + event.totalSkippedCount)
                    print('timer', event.count)
                    if event.count == 5 then assert(MC.Event.off(event.registrationId)) end
                end)
                MC.sleep(100)
                print('main-end')
                """;
        for (boolean debug : List.of(false, true)) {
            try (Run run = new Run(shortTimer, debug)) {
                require(run.complete().succeeded(), "Basic timer succeeds " + debug);
                require(run.output.stream().filter(line -> line.startsWith("timer\t")).toList()
                        .equals(List.of("timer\t1", "timer\t2", "timer\t3", "timer\t4", "timer\t5")), "Payload/count and self-off " + debug);
                require(run.output.contains("main-end"), "Main sleep resumes " + debug);
            }
        }
        try (Run run = new Run("MC.Event.onInterval(2,function(e) if e.count==20 then print('cadence',e.elapsedMs); MC.Event.off(e.registrationId) end end)", false)) {
            require(run.complete().succeeded(), "Short intervals run without server ticks");
            String line = run.output.stream().filter(value -> value.startsWith("cadence\t")).findFirst().orElseThrow();
            double elapsed = Double.parseDouble(line.substring("cadence\t".length()));
            require(elapsed >= 40 && elapsed < 350, "2ms callbacks not quantized to 25ms idle polls: " + elapsed);
            System.out.println("Interval short-period cadence: 20 callbacks in " + elapsed + "ms");
        }
        String concurrent = """
                local busy = false
                MC.Event.onInterval(30, function(event)
                    assert(not busy, 'timer-overlapped')
                    local intervalLocal = event.intervalMs
                    busy = true
                    print('slow-start', event.count)
                    MC.sleep(100)
                    busy = false
                    print('slow-end', event.count)
                    if event.count == 2 then MC.Event.off(event.registrationId) end
                end)
                MC.Event.onInterval(10, function(event)
                    if busy then print('fast-during-slow') end
                    if event.count == 20 then MC.Event.off(event.registrationId) end
                end)
                """;
        try (Run run = new Run(concurrent, true)) {
            run.awaitLine("slow-start\t1");
            await(() -> ServerScriptManager.debugSnapshot().contains("intervalLocal"));
            require(ServerScriptManager.debugSnapshot().contains("interval"), "Timer callback debug context/locals visible");
            require(run.complete().succeeded() && run.output.contains("fast-during-slow"), "Sleeping listener does not block other timers");
            require(run.output.stream().filter(line -> line.startsWith("slow-")).toList()
                    .equals(List.of("slow-start\t1", "slow-end\t1", "slow-start\t2", "slow-end\t2")), "No overlap, skipped intervals not counted or replayed");
        }
        try (Run run = new Run("local id=MC.Event.onInterval(3600000,function(e) print('unexpected') end); assert(MC.Event.off(id)); print('off-ok')", false)) {
            require(run.complete().succeeded() && run.output.contains("off-ok") && !run.output.contains("unexpected"), "Immediate off cancels first occurrence");
        }
        try (Run run = new Run("MC.Event.onInterval(5,function(e) print('cleared'); MC.Event.clear() end); MC.Event.onInterval(1000,function(e) print('unexpected') end)", false)) {
            require(run.complete().succeeded() && run.output.contains("cleared") && !run.output.contains("unexpected"), "Clear removes all timers");
        }
        try (Run run = new Run("MC.Event.on('interval',function(e) assert(e.intervalMs==15); print('generic'); MC.Event.off(e.registrationId) end,15)", false)) {
            require(run.complete().succeeded() && run.output.contains("generic"), "Generic interval registration uses milliseconds");
        }
        try (Run run = new Run("local found=false; for _,name in ipairs(MC.Event.names()) do if name=='interval' then found=true end end; assert(found); MC.Event.on('interval',function(e) assert(e.intervalMs==1000); print('default'); MC.Event.clear() end)", false)) {
            require(run.complete().succeeded() && run.output.contains("default"), "Names and default generic 1000ms interval");
        }
        for (String invalid : List.of("0", "-1", "0.5", "1.5", "3600001", "math.huge", "0/0", "'bad'")) {
            try (Run run = new Run("MC.Event.onInterval(" + invalid + ",function(e) end)", false))
                { require(run.complete().error() != null, "Invalid interval rejected " + invalid); }
        }
        try (Run run = new Run("MC.Event.onInterval(5, 1)", false)) { require(run.complete().error() != null, "Invalid callback rejected"); }
        try (Run run = new Run("MC.Event.onInterval(5,function(e) error('timer-failed') end); MC.sleep(3600000)", false)) {
            require(run.complete().error().contains("timer-failed"), "Timer error stops waiting main flow and propagates");
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            try (Run run = new Run("MC.Event.onInterval(5,function(e) print('sleeping'); MC.sleep(3600000); print('unexpected') end)", false)) {
                run.awaitLine("sleeping"); run.execution.stop();
                var result = run.complete();
                require(result.stopped() && !run.output.contains("unexpected"), "Stop interrupts sleeping timer immediately: " + result);
            }
        }
        String breakpoint = "MC.Event.onInterval(5,function(event)\nlocal intervalLocal=event.intervalMs\nprint('breakpoint')\nMC.Event.off(event.registrationId)\nend)\n";
        UUID owner = UUID.randomUUID();
        ServerScriptManager.configureBreakpoints(owner, new LuaBreakpoints(Map.of("interval_probe",
                new LuaBreakpoints.File(LuaBreakpoints.hash(breakpoint), Set.of(3)))).encode());
        try (Run run = new Run(breakpoint, true, owner)) {
            await(() -> LuaDebuggerState.decode(ServerScriptManager.debuggerState()).paused());
            Thread.sleep(120);
            var state = LuaDebuggerState.decode(ServerScriptManager.debuggerState());
            require(state.line() == 3 && !run.result.isDone() && !run.output.contains("breakpoint"), "Timer respects breakpoint pause");
            require(ServerScriptManager.debugSnapshot().contains("intervalLocal"), "Paused timer locals captured");
            ServerScriptManager.controlDebugger(state.id(), "continue");
            require(run.complete().succeeded() && run.output.stream().filter(line -> line.equals("breakpoint")).count() == 1, "Breakpoint resume produces no timer backlog");
        }
        LuaLimits original = LuaLimitConfig.current();
        try {
            limits(original, Map.of("maxEventCallbacks", "1"));
            try (Run run = new Run("local id=MC.Event.onInterval(3600000,function(e) end); local ok,err=pcall(MC.Event.onInterval,5,function(e) end); assert(not ok); print(err); MC.Event.off(id)", false)) {
                require(run.complete().succeeded() && run.output.stream().anyMatch(line -> line.contains("监听数量")), "Timers share event listener cap");
            }
            limits(original, Map.of("maxRuntimeSeconds", "1"));
            try (Run run = new Run("MC.Event.onInterval(3600000,function(e) end)", false)) {
                require(run.complete().error().contains("最长运行时间"), "Long timers cannot bypass runtime limit");
            }
            limits(original, Map.of("allowEvents", "false"));
            try (Run run = new Run("assert(MC.Event == nil); print('events-disabled')", false)) {
                require(run.complete().succeeded() && run.output.contains("events-disabled"), "Event permission also hides timer API");
            }
        } finally { LuaLimitConfig.save(original); }
        try (Run run = new Run("print('restart-ok')", false)) {
            require(run.complete().succeeded() && run.output.equals(List.of("restart-ok")), "No stale timers after restart");
        }
        String template = LuaApiDocumentation.usageTemplate("MC.Event.onInterval");
        require(LuaCodeAnalyzer.analyze("interval", template).valid(), "API documentation insertion template is valid");
        require(LuaCodeAnalyzer.analyze("interval", "MC.Event.on('interval', function(e) end, 500)").valid(), "Generic event name recognized by analyzer");
        require(!LuaCodeAnalyzer.analyze("interval", "MC.Event.onInterval(1.5, function(e) end)").valid(), "Fractional interval type highlighted");
        String prefix = "local ms=500\nlocal fraction=1.5\nlocal text='bad'\nMC.Event.onInterval(";
        var context = LuaCursorContext.parameterAt(prefix, prefix.length());
        var choices = LuaArgumentCompletion.suggest(prefix, prefix.length(), context, List.of());
        require(choices.stream().anyMatch(choice -> choice.insertion().equals("ms"))
                && choices.stream().noneMatch(choice -> choice.insertion().equals("fraction") || choice.insertion().equals("text")), "Integer interval variable completion");
    }

    private static void limits(LuaLimits original, Map<String, String> overrides) throws Exception {
        var values = new LinkedHashMap<>(original.toMap()); values.putAll(overrides); LuaLimitConfig.save(LuaLimits.fromMap(values));
    }

    private static void testLifecycle() throws Exception {
        for (boolean debug : List.of(false, true)) {
            String source = """
                    local value = 0
                    local startedId
                    local stoppedId
                    startedId = MC.Event.onScriptStarted(function(e)
                        assert(e.type == 'scriptStarted' and e.registrationId == startedId)
                        assert(e.scriptName == 'interval_probe' and type(e.ownerUuid) == 'string')
                        assert(e.startedAt > 0 and e.elapsedMs >= 0)
                        value = value + 1
                        print('life-start', value)
                    end)
                    stoppedId = MC.Event.onScriptStopped(function(e)
                        assert(e.type == 'scriptStopped' and e.registrationId == stoppedId)
                        assert(e.reason == 'completed' and e.success and not e.stopped and e.error == nil)
                        assert(value == 1)
                        print('life-stop', value)
                    end)
                    print('life-main')
                    """;
            try (Run run = new Run(source, debug)) {
                require(run.complete().succeeded(), "Lifecycle-only script naturally completes " + debug);
                require(run.output.equals(List.of("life-main", "life-start\t1", "life-stop\t1")), "Once/order/closure/payload " + debug);
            }
            try (Run run = new Run("""
                    local n = 7
                    MC.Event.on('scriptStopped',function(e)
                        assert(e.reason == 'stopped' and e.stopped and not e.success and e.error == nil)
                        print('manual-cleanup', n)
                    end)
                    MC.Event.on('scriptStarted',function(e) print('manual-started') end)
                    print('waiting-for-stop')
                    MC.sleep(3600000)
                    print('unexpected-main-resume')
                    """, debug)) {
                run.awaitLine("manual-started");
                run.execution.stop(); run.execution.stop();
                require(run.complete().stopped(), "Manual stopped result " + debug);
                require(run.output.stream().filter(line -> line.equals("manual-cleanup\t7")).count() == 1
                        && !run.output.contains("unexpected-main-resume"), "Repeated stop cleans up once after sleep unwinds " + debug);
            }
            try (Run run = new Run("MC.Event.onScriptStopped(function(e) assert(e.reason=='error' and not e.success and e.error:find('life-main-failed',1,true)); print('error-cleanup') end); error('life-main-failed')", debug)) {
                require(run.complete().error().contains("life-main-failed") && run.output.contains("error-cleanup"), "Main error keeps original cause and executes cleanup " + debug + ": " + run.output);
            }
            try (Run run = new Run("MC.Event.onScriptStopped(function(e) assert(e.error:find('life-start-failed',1,true)); print('startup-error-cleanup') end); MC.Event.onScriptStarted(function(e) error('life-start-failed') end)", debug)) {
                require(run.complete().error().contains("life-start-failed") && run.output.contains("startup-error-cleanup"), "Startup error executes cleanup " + debug);
            }
            try (Run run = new Run("local s=MC.Event.onScriptStarted(function(e) error('unexpected') end); local t=MC.Event.onScriptStopped(function(e) error('unexpected') end); assert(MC.Event.off(s)); assert(MC.Event.off(t)); assert(not MC.Event.off(t)); print('life-off')", debug)) {
                require(run.complete().succeeded() && run.output.equals(List.of("life-off")), "Lifecycle off before firing " + debug);
            }
            try (Run run = new Run("MC.Event.onScriptStopped(function(e) error('unexpected') end); MC.Event.onScriptStarted(function(e) error('unexpected') end); assert(MC.Event.clear()==2); print('life-clear')", debug)) {
                require(run.complete().succeeded() && run.output.equals(List.of("life-clear")), "Lifecycle clear cancels both " + debug);
            }
        }
        try (Run run = new Run("""
                MC.Event.onScriptStopped(function(e) print('startup-timer-cleanup', e.reason) end)
                MC.Event.onScriptStarted(function(e)
                    MC.Event.onInterval(5,function(t)
                        print('startup-timer', t.count)
                        MC.Event.off(t.registrationId)
                    end)
                end)
                """, false)) {
            require(run.complete().succeeded() && run.output.equals(List.of("startup-timer\t1", "startup-timer-cleanup\tcompleted")),
                    "Startup can register game listeners and keep the script alive until they end");
        }
        String pausedSource = """
                MC.Event.onScriptStopped(function(e)
                    print('paused-cleanup', e.reason)
                end)
                print('breakpoint-main')
                """;
        UUID pausedOwner = UUID.randomUUID();
        ServerScriptManager.configureBreakpoints(pausedOwner, new LuaBreakpoints(Map.of("interval_probe",
                new LuaBreakpoints.File(LuaBreakpoints.hash(pausedSource), Set.of(2, 4)))).encode());
        try (Run run = new Run(pausedSource, true, pausedOwner)) {
            await(() -> LuaDebuggerState.decode(ServerScriptManager.debuggerState()).paused());
            var state = LuaDebuggerState.decode(ServerScriptManager.debuggerState());
            require(state.line() == 4 && !run.output.contains("breakpoint-main"), "Main pauses before lifecycle cleanup");
            ServerScriptManager.controlDebugger(state.id(), "stop");
            require(run.complete().stopped() && run.output.equals(List.of("paused-cleanup\tstopped")),
                    "Stopping a breakpoint runs cleanup without pausing at cleanup breakpoints");
        }
        try (Run run = new Run("""
                MC.Event.onScriptStopped(function(e) error('cleanup-bad') end)
                MC.Event.onScriptStopped(function(e)
                    local ok = pcall(MC.sleep, 0); assert(not ok)
                    ok = pcall(MC.Event.onItemUsed, function(ev) end); assert(not ok)
                    ok = pcall(MC.Event.onInterval, 1, function(ev) end); assert(not ok)
                    print('cleanup-after-error')
                end)
                """, false)) {
            require(run.complete().succeeded() && run.output.contains("cleanup-after-error"), "Cleanup errors don't skip later callbacks or change original result");
            require(run.output.stream().anyMatch(line -> line.contains("cleanup-bad")), "Cleanup error is logged");
        }
        try (Run run = new Run("local second; MC.Event.onScriptStopped(function(e) assert(MC.Event.off(second)); print('cancel-next') end); second=MC.Event.onScriptStopped(function(e) error('unexpected-next') end)", false)) {
            require(run.complete().succeeded() && run.output.equals(List.of("cancel-next")), "Cleanup can unregister a later callback");
        }
        long begin = System.nanoTime();
        try (Run run = new Run("MC.Event.onScriptStopped(function(e) while true do end end); MC.Event.onScriptStopped(function(e) print('after-timeout') end)", false)) {
            require(run.complete().succeeded() && run.output.contains("after-timeout"), "Infinite cleanup is bounded and later cleanup still runs");
            require(run.output.stream().anyMatch(line -> line.contains("清理时间限制")), "Cleanup timeout explained");
        }
        require(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(3), "Infinite cleanup prevented script completion");
        for (String symbol : List.of("MC.Event.onScriptStarted", "MC.Event.onScriptStopped"))
            require(LuaCodeAnalyzer.analyze("lifecycle", LuaApiDocumentation.usageTemplate(symbol)).valid(), "Lifecycle insertion template: " + symbol);
        require(LuaCodeAnalyzer.analyze("lifecycle", "MC.Event.on('scriptStarted',function(e) end); MC.Event.on('scriptStopped',function(e) end)").valid(), "Generic lifecycle names in analyzer");
        String prefix = "MC.Event.on('script";
        var context = LuaCursorContext.parameterAt(prefix, prefix.length());
        var choices = LuaArgumentCompletion.suggest(prefix, prefix.length(), context, List.of());
        require(choices.stream().anyMatch(choice -> choice.insertion().contains("scriptStarted"))
                && choices.stream().anyMatch(choice -> choice.insertion().contains("scriptStopped")), "Lifecycle event name completion");
        LuaLimits original = LuaLimitConfig.current();
        try {
            limits(original, Map.of("maxRuntimeSeconds", "1"));
            try (Run run = new Run("MC.Event.onScriptStopped(function(e) assert(e.reason=='error' and e.error:find('最长运行时间')); print('timeout-cleanup') end); MC.sleep(3600000)", false)) {
                require(run.complete().error().contains("最长运行时间") && run.output.contains("timeout-cleanup"), "Runtime timeout still permits cleanup");
            }
            limits(original, Map.of("maxEventCallbacks", "1"));
            try (Run run = new Run("MC.Event.onScriptStopped(function(e) print('cap-cleanup') end); MC.Event.onScriptStarted(function(e) end)", false)) {
                require(run.complete().error().contains("监听数量") && run.output.contains("cap-cleanup"), "Lifecycle registrations share listener cap");
            }
        } finally { LuaLimitConfig.save(original); }
        try (Run run = new Run("local names=MC.Event.names(); local a,b=false,false; for _,name in ipairs(names) do a=a or name=='scriptStarted'; b=b or name=='scriptStopped' end; assert(a and b); print('life-restart')", false)) {
            require(run.complete().succeeded() && run.output.equals(List.of("life-restart")), "Lifecycle names and restart have no stale cleanup");
        }
    }
    private static final class Run implements AutoCloseable {
        final List<String> output = new CopyOnWriteArrayList<>();
        final CompletableFuture<ServerScriptManager.ExecutionResult> result = new CompletableFuture<>();
        final ServerScriptManager.ScriptExecution execution;
        Run(String source, boolean debug) { this(source, debug, UUID.randomUUID()); }
        Run(String source, boolean debug, UUID owner) {
            execution = ServerScriptManager.start(null, owner, "interval_probe", source, debug, output::add, result::complete);
            ServerScriptManager.debugSnapshot();
        }
        ServerScriptManager.ExecutionResult complete() throws Exception { return result.get(5, TimeUnit.SECONDS); }
        void awaitLine(String line) throws Exception {
            await(() -> { if (result.isDone()) throw new AssertionError("Runner ended early: " + result.join()); return output.contains(line); });
        }
        @Override public void close() throws Exception { if (!result.isDone()) execution.stop(); result.get(3, TimeUnit.SECONDS); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) throw new AssertionError("Interval probe timed out"); Thread.sleep(3); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
