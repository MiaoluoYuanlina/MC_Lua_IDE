package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Opt-in regression test in a fresh disposable build/runtime-warnings-probe world. */
final class LuaRuntimeWarningsProbe {
    private static int stage;
    private static long started;
    private static CompletableFuture<Void> task;
    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                stage = 1; started = System.nanoTime();
                client.options.renderDistance().set(3); client.options.simulationDistance().set(6);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("World cancelled"); }); return;
            }
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(180)) throw new AssertionError("Probe timed out");
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("runtime_warning_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                var method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
                stage = 2; method.invoke(create); return;
            }
            if (stage == 2) {
                if (client.player == null || client.getSingleplayerServer() == null || !ScriptManager.isServerAvailable()) return;
                client.setScreen(null);
                MinecraftServer server = client.getSingleplayerServer(); UUID owner = client.player.getUUID();
                task = CompletableFuture.runAsync(() -> {
                    try { verify(server, owner, false); verify(server, owner, true); }
                    catch (Exception e) { throw new CompletionException(e); }
                }); stage = 3; return;
            }
            if (stage == 3 && task.isDone()) {
                task.join(); stage = 99;
                System.out.println("Runtime warnings real game probe passed: normal/debug, unloaded chunks, bulk preflight, offline players, missing entities, callbacks, warning cap, source lines, hard errors and no force-load");
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            stage = 99; client.stop(); throw new IllegalStateException("Runtime warnings probe failed", failure);
        }
    }

    private static void verify(MinecraftServer server, UUID owner, boolean debug) throws Exception {
        String absent = "00000000-0000-0000-0000-000000000099";
        String source = """
                local p = MC.Player.position('%s')
                local function warning(fn, text)
                    local value, reason = fn()
                    assert(value == nil and type(reason) == 'string' and string.find(reason, text, 1, true), tostring(reason))
                end
                local skipped, reason = MC.Block.get(1000000, 64, 1000000)
                assert(skipped == nil and reason)
                warning(function() return MC.Block.get(1000000, 64, 1000000) end, '区块')
                warning(function() return MC.block.id(1000000, 64, 1000000) end, '区块')
                warning(function() return MC.Block.set(1000000, 64, 1000000, 'minecraft:stone') end, '区块')
                warning(function() return MC.Block.breakAt(1000000, 64, 1000000) end, '区块')
                warning(function() return MC.Block.fill(1000000, 64, 1000000, 1000032, 64, 1000032, 'minecraft:stone') end, '区块')
                warning(function() return MC.Block.clone(p.x, 64, p.z, p.x, 64, p.z, 1000000, 64, 1000000) end, '区块')
                warning(function() return MC.Container.info(1000000, 64, 1000000) end, '区块')
                warning(function() return MC.Player.position('%s') end, '玩家不在线')
                warning(function() return MC.Player.openCrafting('%s') end, '玩家不在线')
                warning(function() return MC.Entity.setName('%s', 'test') end, '实体不存在')
                warning(function() return MC.Entity.equipment('%s', 'mainhand') end, '实体不存在')
                assert(not pcall(function() MC.Block.get('bad', 64, 0) end))
                assert(not pcall(function() MC.Player.position('invalid-uuid') end))
                assert(not pcall(function() MC.Block.get(0, 99999, 0) end))
                assert(not pcall(function() MC.Block.get(0, 64, 0, 'test:missing_dimension') end))
                assert(not pcall(function() MC.Block.fill(p.x, 64, p.z, p.x, 64, p.z, 'test:missing_block') end))
                assert(MC.Block.get(p.x, 64, p.z).id)
                local original = MC.Block.get(p.x, 64, p.z).id
                warning(function() return MC.Block.fill(p.x, 64, p.z, 1000000, 64, 1000000, 'minecraft:stone') end, '区块')
                assert(MC.Block.get(p.x, 64, p.z).id == original, 'Partial bulk mutation')
                MC.Block.fill(p.x, 64, p.z, p.x, 64, p.z, 'minecraft:diamond_block')
                assert(MC.Block.get(p.x, 64, p.z).id == 'minecraft:diamond_block')
                MC.Block.clone(p.x, 64, p.z, p.x, 64, p.z, p.x + 1, 64, p.z)
                assert(MC.Block.get(p.x + 1, 64, p.z).id == 'minecraft:diamond_block')
                MC.Block.set(p.x, 64, p.z, original)
                -- Lots of distinct safe failures must not hit the normal print budget or terminate the script.
                for i = 1, 150 do MC.Block.get(1000000 + i * 16, 64, 1000000) end
                local count, registration = 0, nil
                registration = MC.Event.onInterval(30, function(e)
                    MC.Block.get(1000000, 64, 1000000)
                    count = count + 1
                    if count >= 3 then MC.Event.off(registration) end
                end)
                for i = 1, 100 do
                    if count >= 3 then break end
                    MC.sleep(30)
                end
                assert(count >= 3, 'Callbacks stopped after warning')
                print('warnings-ok')
                """.formatted(owner, absent, absent, absent, absent);
        List<String> output = new CopyOnWriteArrayList<>();
        var completed = new CompletableFuture<ServerScriptManager.ExecutionResult>();
        server.execute(() -> {
            try { ServerScriptManager.start(server, owner, "runtime_warning_probe", source, debug, output::add, completed::complete); }
            catch (Throwable e) { completed.completeExceptionally(e); }
        });
        var result = completed.get(30, TimeUnit.SECONDS);
        if (result.error() != null || result.stopped() || !output.contains("warnings-ok"))
            throw new AssertionError("Warning execution failed: " + result + " " + output);
        if (output.stream().noneMatch(line -> line.startsWith("⚠") && line.contains("runtime_warning_probe.lua:6")))
            throw new AssertionError("Warning source missing: " + output);
        if (output.stream().filter(line -> line.startsWith("⚠")).count() > 51
                || output.stream().noneMatch(line -> line.contains("后续可恢复警告已省略")))
            throw new AssertionError("Warnings not bounded: " + output);
        if (server.submit(() -> server.overworld().hasChunkAt(new BlockPos(1000000, 64, 1000000))).get(10, TimeUnit.SECONDS))
            throw new AssertionError("Warning caused a force-loaded chunk");
        var fatal = new CompletableFuture<ServerScriptManager.ExecutionResult>();
        List<String> fatalOutput = new CopyOnWriteArrayList<>();
        server.execute(() -> {
            try { ServerScriptManager.start(server, owner, "runtime_warning_fatal_probe",
                    "MC.Block.get('invalid', 64, 0)\nprint('must-not-run')", debug, fatalOutput::add, fatal::complete); }
            catch (Throwable e) { fatal.completeExceptionally(e); }
        });
        if (fatal.get(10, TimeUnit.SECONDS).error() == null || fatalOutput.contains("must-not-run"))
            throw new AssertionError("Invalid parameter no longer stops script");
    }
}
