package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in authoritative break regression in a fresh build/block-break-probe world only. */
final class LuaBlockBreakProbe {
    private static int stage, checks, fabricEvents;
    private static long started;
    private static CompletableFuture<Void> task;
    private static final AtomicReference<BlockPos> cancelled = new AtomicReference<>();
    private static final AtomicReference<BlockPos> throwing = new AtomicReference<>();
    private static final AtomicReference<BlockPos> nested = new AtomicReference<>();
    private static BlockPos nestedTarget;

    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                started = System.nanoTime(); stage = 1;
                client.options.renderDistance().set(2); client.options.simulationDistance().set(2);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Block-break probe cancelled"); });
                return;
            }
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(180), "Block-break probe timeout at " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("block_break_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
                stage = 2; method.invoke(create); return;
            }
            if (stage == 2) {
                if (client.player == null || client.getSingleplayerServer() == null || !ScriptManager.isServerAvailable()) return;
                client.setScreen(null);
                PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
                    if (pos.equals(throwing.get())) throw new IllegalStateException("block-break-probe-expected");
                    BlockPos outer = nested.get();
                    if (pos.equals(outer) && nested.compareAndSet(outer, null))
                        require(((ServerPlayer) player).gameMode.destroyBlock(nestedTarget), "Nested break was rejected");
                    return !pos.equals(cancelled.get());
                });
                PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> fabricEvents++);
                MinecraftServer server = client.getSingleplayerServer(); UUID owner = client.player.getUUID();
                task = CompletableFuture.runAsync(() -> {
                    try { verify(server, owner, false); verify(server, owner, true);
                        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("axiom")) verifyAxiom(server, owner);
                        else { verifyNetwork(client, server, owner, false); verifyNetwork(client, server, owner, true); } }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
                stage = 3; return;
            }
            if (stage == 3 && task.isDone()) {
                task.join(); stage = 99;
                System.out.println("Block-break real Lua/server probe passed: " + checks
                        + " checks; normal/debug, creative/survival beds both parts/all facings, doors, stone, chest, original target payload, exactly once, cancelled/adventure/air, direct removal, nested and exception cleanup; "
                        + (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("axiom") ? "Axiom actual server packet handler" : "exact user script through client run/break packets and IDE logs"));
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            int failed = stage; stage = 99; client.stop();
            throw new IllegalStateException("Block-break probe failed at stage " + failed, failure);
        }
    }

    private static <T> CompletableFuture<T> onClient(Minecraft client, java.util.concurrent.Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>(); client.execute(() -> {
            try { result.complete(action.call()); } catch (Throwable error) { result.completeExceptionally(error); }
        }); return result;
    }
    private static void verifyNetwork(Minecraft client, MinecraftServer server, UUID owner, boolean debug) throws Exception {
        String source = """
                local registrationId = MC.Event.onBlockBroken(function(event)
                    print(event.playerName, event.blockId, event.x, event.y, event.z)
                end)
                print("事件注册 ID：", registrationId)
                """;
        var run = onClient(client, () -> debug ? ScriptManager.start("block_break_network_probe", source, text -> {}, result -> {})
                : ScriptManager.startNormal("block_break_network_probe", source, text -> {}, result -> {})).get(10, TimeUnit.SECONDS);
        LuaIdeScreen ide = null;
        try {
            await(() -> run.outputLines().stream().anyMatch(line -> line.startsWith("事件注册 ID：")) || !run.isRunning());
            require(run.isRunning(), "Exact user script ended before player break: " + run.result());
            ide = onClient(client, () -> { var screen = new LuaIdeScreen(null, "block_break_network_probe"); client.setScreen(screen); return screen; }).get(10, TimeUnit.SECONDS);
            BlockPos target = server.submit(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(owner); player.setGameMode(GameType.CREATIVE);
                BlockPos pos = player.blockPosition().offset(1, 1, 0); player.level().setBlock(pos, Blocks.STONE.defaultBlockState(), 3); return pos;
            }).get(10, TimeUnit.SECONDS);
            await(() -> client.level.getBlockState(target).is(Blocks.STONE));
            await(() -> client.player.getAbilities().instabuild);
            onClient(client, () -> { client.setScreen(null); client.hitResult = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(target), Direction.UP, target, false); return null; }).get(10, TimeUnit.SECONDS);
            require(onClient(client, () -> {
                client.hitResult = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(target), Direction.UP, target, false);
                return client.gameMode.startDestroyBlock(target, Direction.UP);
            }).get(10, TimeUnit.SECONDS), "Client rejected break");
            try { await(() -> run.outputLines().stream().anyMatch(line -> line.contains("\tminecraft:stone\t")) || !run.isRunning()); }
            catch (AssertionError error) {
                String state = server.submit(() -> "target=" + target + ", server=" + server.getPlayerList().getPlayer(owner).level().getBlockState(target)
                        + ", player=" + server.getPlayerList().getPlayer(owner).position() + ", mode=" + server.getPlayerList().getPlayer(owner).gameMode.getGameModeForPlayer()).get(5, TimeUnit.SECONDS);
                throw new AssertionError("Network callback timeout: " + state + ", client=" + client.level.getBlockState(target) + ", output=" + run.outputLines(), error);
            }
            require(run.isRunning(), "Client break callback failed: " + run.result());
            long count = run.outputLines().stream().filter(line -> line.contains("\tminecraft:stone\t")).count(); require(count == 1, "Client break callback not exactly once: " + run.outputLines());
            LuaIdeScreen screen = ide;
            boolean visible = onClient(client, () -> {
                client.setScreen(screen);
                var sync = LuaIdeScreen.class.getDeclaredMethod("syncExecutionState"); sync.setAccessible(true); sync.invoke(screen);
                var history = LuaIdeScreen.class.getDeclaredField("outputLines"); history.setAccessible(true);
                return ((List<?>)history.get(screen)).stream().anyMatch(line -> line.toString().contains("\tminecraft:stone\t"));
            }).get(10, TimeUnit.SECONDS);
            require(visible, "Block break output did not reach IDE log");
            System.out.println("Exact user onBlockBroken script network check passed (debug=" + debug + "): " + run.outputLines().stream().filter(line -> line.contains("\tminecraft:stone\t")).findFirst().orElseThrow());
        } finally {
            onClient(client, () -> { if (run.isRunning()) run.stop(); client.setScreen(null); return null; }).get(10, TimeUnit.SECONDS);
            await(() -> !run.isRunning());
        }
    }

    private static void verifyAxiom(MinecraftServer server, UUID owner) throws Exception {
        Class<?> packet = Class.forName("com.moulberry.axiom.packets.AxiomServerboundSetBlock");
        var constructor = packet.getConstructor(java.util.Map.class, boolean.class, int.class, boolean.class,
                net.minecraft.world.phys.BlockHitResult.class, net.minecraft.world.InteractionHand.class, int.class);
        var handle = packet.getMethod("handle", MinecraftServer.class, ServerPlayer.class);
        int reason = packet.getField("REASON_NOUPDATES").getInt(null);
        List<String> output = new CopyOnWriteArrayList<>(); var completed = new CompletableFuture<ServerScriptManager.ExecutionResult>();
        String source = "MC.Event.onBlockBroken(function(e) print('axiom-broken', e.blockId, e.x, e.y, e.z, e.hadBlockEntity) end)\nprint('axiom-ready')";
        server.submit(() -> ServerScriptManager.start(server, owner, "axiom_break_probe", source, false, output::add, completed::complete)).get(10, TimeUnit.SECONDS);
        try {
            await(() -> output.contains("axiom-ready") || completed.isDone()); require(!completed.isDone(), "Axiom listener ended");
            int before = fabricEvents;
            server.submit(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(owner); player.setGameMode(GameType.CREATIVE);
                    BlockPos pos = player.blockPosition().offset(1, 2, 0); var level = player.level(); level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                    var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    Object request = constructor.newInstance(java.util.Map.of(pos, Blocks.AIR.defaultBlockState()), false, reason, true, hit, net.minecraft.world.InteractionHand.MAIN_HAND, -1);
                    handle.invoke(request, server, player); require(level.getBlockState(pos).isAir(), "Axiom fixture did not remove stone");
                    return null;
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            }).get(10, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(200);
            long count = output.stream().filter(line -> line.startsWith("axiom-broken")).count();
            require(fabricEvents == before, "Axiom unexpectedly fired Fabric AFTER; compatibility assumptions changed");
            System.out.println("AXIOM COMPAT: actual stone removed, Fabric AFTER=0, Lua callbacks=" + count);
            if (Boolean.getBoolean("mc_lua_ide.axiomFixedProbe")) require(count == 1, "Axiom removal missed Lua onBlockBroken");
            if (Boolean.getBoolean("mc_lua_ide.axiomFixedProbe")) {
                List<String> expected = server.submit(() -> {
                    List<String> rows = new ArrayList<>(); ServerPlayer player = server.getPlayerList().getPlayer(owner); var level = player.level();
                    BlockPos pos = player.blockPosition().offset(2, 2, 0);
                    try {
                        for (boolean updates : List.of(false, true)) {
                            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 2);
                            axiomPacket(constructor, handle, server, player, pos, Map.of(pos, Blocks.AIR.defaultBlockState()), reason, updates, true);
                            require(level.getBlockState(pos).isAir(), "Axiom chest remained");
                            rows.add("axiom-broken\tminecraft:chest\t" + pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ() + "\ttrue");
                        }
                        for (BedPart part : BedPart.values()) {
                            placeBed(player, pos, Direction.NORTH); BlockPos head = pos.north(), target = part == BedPart.FOOT ? pos : head;
                            axiomPacket(constructor, handle, server, player, target, Map.of(pos, Blocks.AIR.defaultBlockState(), head, Blocks.AIR.defaultBlockState()), reason, false, true);
                            require(level.getBlockState(pos).isAir() && level.getBlockState(head).isAir(), "Axiom bed remained");
                            rows.add("axiom-broken\tminecraft:red_bed\t" + target.getX() + "\t" + target.getY() + "\t" + target.getZ() + "\ttrue");
                        }
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2); cancelled.set(pos);
                        axiomPacket(constructor, handle, server, player, pos, Map.of(pos, Blocks.AIR.defaultBlockState()), reason, false, true);
                        require(level.getBlockState(pos).is(Blocks.STONE), "Axiom compatibility bypassed cancelled break"); cancelled.set(null);
                        // Editor replacement, not a player-break packet: should not emit even if it sets AIR.
                        axiomPacket(constructor, handle, server, player, pos, Map.of(pos, Blocks.AIR.defaultBlockState()), reason, false, false);
                        require(level.getBlockState(pos).isAir(), "Axiom non-breaking edit changed behaviour");
                        axiomPacket(constructor, handle, server, player, pos, Map.of(pos, Blocks.AIR.defaultBlockState()), reason, false, true);
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                        var guest = new ServerPlayer(server, level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "AxiomProbeGuest"), net.minecraft.server.level.ClientInformation.createDefault());
                        axiomPacket(constructor, handle, server, guest, pos, Map.of(pos, Blocks.AIR.defaultBlockState()), reason, false, true);
                        require(level.getBlockState(pos).is(Blocks.STONE), "Axiom compatibility bypassed permission");
                        return rows;
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    finally { cancelled.set(null); player.setGameMode(GameType.CREATIVE); }
                }).get(10, TimeUnit.SECONDS);
                await(() -> output.stream().filter(line -> line.startsWith("axiom-broken")).count() >= 1 + expected.size() || completed.isDone());
                TimeUnit.MILLISECONDS.sleep(200);
                List<String> actual = output.stream().filter(line -> line.startsWith("axiom-broken")).toList();
                require(actual.size() == 1 + expected.size(), "Axiom cancelled/air/non-break/permission emitted extra or duplicate events: " + actual);
                require(actual.subList(1, actual.size()).equals(expected), "Axiom target snapshot (including bed halves/chest entity) changed: " + actual);
                require(!completed.isDone(), "Axiom callback failed: " + completed.getNow(null));
                System.out.println("Axiom 6.0.5 native compatibility checks passed: stone, chest with/without updates, bed both halves exactly once, cancelled, permission, air and non-breaking editor operations");
            }
        } finally {
            server.submit(ServerScriptManager::stopActive).get(5, TimeUnit.SECONDS); completed.get(5, TimeUnit.SECONDS);
        }
    }

    private static void axiomPacket(java.lang.reflect.Constructor<?> constructor, Method handle, MinecraftServer server, ServerPlayer player,
            BlockPos clicked, Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks, int reason, boolean updates, boolean breaking) throws Exception {
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(clicked), Direction.UP, clicked, false);
        handle.invoke(constructor.newInstance(blocks, updates, reason, breaking, hit, net.minecraft.world.InteractionHand.MAIN_HAND, -1), server, player);
    }

    private static void verify(MinecraftServer server, UUID owner, boolean debug) throws Exception {
        List<String> output = new CopyOnWriteArrayList<>();
        CompletableFuture<ServerScriptManager.ExecutionResult> completed = new CompletableFuture<>();
        String source = """
                local function record(kind, e)
                    assert(e.type == 'blockBroken' and e.playerUuid == '%s')
                    assert(type(e.playerName) == 'string' and type(e.hadBlockEntity) == 'boolean')
                    print(kind, e.x, e.y, e.z, e.blockId, tostring(e.hadBlockEntity), e.playerUuid, e.dimension)
                end
                MC.Event.onBlockBroken(function(e) record('named', e) end)
                MC.Event.on('blockBroken', function(e) record('generic', e) end)
                print('break-ready')
                """.formatted(owner);
        server.submit(() -> ServerScriptManager.start(server, owner, "block_break_probe", source, debug, output::add, completed::complete))
                .get(10, TimeUnit.SECONDS);
        try {
            await(() -> output.contains("break-ready") || completed.isDone());
            require(!completed.isDone(), "Lua listener startup failed: " + output);
            List<String> expected = server.submit(() -> breakFixtures(server.getPlayerList().getPlayer(owner)))
                    .get(20, TimeUnit.SECONDS);
            await(() -> output.stream().filter(line -> line.startsWith("named\t")).count() >= expected.size() || completed.isDone());
            TimeUnit.MILLISECONDS.sleep(200);
            require(!completed.isDone(), "Lua callbacks failed: " + output);
            for (String prefix : List.of("named", "generic")) {
                List<String> actual = output.stream().filter(line -> line.startsWith(prefix + "\t"))
                        .map(line -> line.substring(prefix.length())).sorted().toList();
                require(actual.equals(expected.stream().sorted().toList()), "Missing/duplicate/wrong payload (" + prefix + ", debug=" + debug + "): " + actual + " expected " + expected);
            }
        } finally {
            server.submit(ServerScriptManager::stopActive).get(10, TimeUnit.SECONDS);
            var result = completed.get(10, TimeUnit.SECONDS);
            require(result.error() == null && result.stopped(), "Listener failed during cleanup: " + result + " " + output);
        }
    }

    private static List<String> breakFixtures(ServerPlayer player) {
        var level = player.level();
        var originalMode = player.gameMode.getGameModeForPlayer();
        BlockPos foot = player.blockPosition().offset(0, 8, 0), regular = foot.offset(4, 0, 0);
        List<String> expected = new ArrayList<>();
        try {
            for (GameType mode : List.of(GameType.CREATIVE, GameType.SURVIVAL)) {
                player.setGameMode(mode);
                for (Direction facing : Direction.Plane.HORIZONTAL) for (BedPart part : BedPart.values()) {
                    BlockPos head = foot.relative(facing);
                    placeBed(player, foot, facing);
                    BlockPos target = part == BedPart.FOOT ? foot : head;
                    remember(player, target, expected);
                    int beforeFabric = fabricEvents;
                    require(player.gameMode.destroyBlock(target), "Bed break failed: " + mode + " " + part + " " + facing);
                    require(fabricEvents - beforeFabric == (mode == GameType.CREATIVE && part == BedPart.FOOT ? 0 : 1),
                            "Vanilla/Fabric event behaviour changed; creative FOOT must use only the Lua fallback");
                    require(level.getBlockState(foot).isAir() && level.getBlockState(head).isAir(), "Bed no longer removes both parts");
                }
                level.setBlock(regular, Blocks.STONE.defaultBlockState(), 2); remember(player, regular, expected);
                require(player.gameMode.destroyBlock(regular) && level.getBlockState(regular).isAir(), "Stone break changed");
                level.setBlock(regular, Blocks.CHEST.defaultBlockState(), 2); remember(player, regular, expected);
                require(player.gameMode.destroyBlock(regular), "Chest break failed");
                for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                    level.setBlock(regular.below(), Blocks.STONE.defaultBlockState(), 2);
                    level.setBlock(regular, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
                    level.setBlock(regular.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
                    BlockPos target = half == DoubleBlockHalf.LOWER ? regular : regular.above(); remember(player, target, expected);
                    require(player.gameMode.destroyBlock(target), "Door break failed");
                    require(level.getBlockState(regular).isAir() && level.getBlockState(regular.above()).isAir(), "Door no longer removes both halves");
                }
            }
            player.setGameMode(GameType.CREATIVE);
            placeBed(player, foot, Direction.NORTH); cancelled.set(foot);
            try {
                require(!player.gameMode.destroyBlock(foot), "Cancelled bed was accepted");
                require(level.getBlockState(foot).is(Blocks.RED_BED) && level.getBlockState(foot.north()).is(Blocks.RED_BED), "Cancellation removed bed");
            } finally { cancelled.set(null); }
            // BEFORE can invoke another genuine player break; it must not suppress the outer fallback.
            level.setBlock(regular, Blocks.STONE.defaultBlockState(), 2); remember(player, regular, expected); remember(player, foot, expected);
            nestedTarget = regular; nested.set(foot);
            try { require(player.gameMode.destroyBlock(foot), "Outer nested bed break failed"); }
            finally { nested.set(null); }
            placeBed(player, foot, Direction.NORTH); remember(player, foot, expected);
            nestedTarget = foot; nested.set(foot);
            try { require(player.gameMode.destroyBlock(foot), "Same-target reentrant break failed"); }
            finally { nested.set(null); }
            level.setBlock(regular, Blocks.STONE.defaultBlockState(), 2); throwing.set(regular);
            try {
                try { player.gameMode.destroyBlock(regular); throw new AssertionError("Throwing fixture did not throw"); }
                catch (IllegalStateException expectedError) { require(expectedError.getMessage().equals("block-break-probe-expected"), "Unexpected native break exception"); }
            } finally { throwing.set(null); }
            require(level.getBlockState(regular).is(Blocks.STONE), "Exception fixture removed block");
            remember(player, regular, expected); require(player.gameMode.destroyBlock(regular), "Scope leaked after exception");
            player.gameMode.destroyBlock(regular); // Native creative accepts air, but it must not produce a Lua event.
            level.setBlock(regular, Blocks.STONE.defaultBlockState(), 2);
            player.setGameMode(GameType.ADVENTURE);
            require(!player.gameMode.destroyBlock(regular) && level.getBlockState(regular).is(Blocks.STONE), "Adventure permission was bypassed");
            level.removeBlock(regular, false); // Non-player world removal is not onBlockBroken.
            return expected;
        } finally { cancelled.set(null); throwing.set(null); nested.set(null); player.setGameMode(originalMode); }
    }

    private static void placeBed(ServerPlayer player, BlockPos foot, Direction facing) {
        var level = player.level(); BlockPos head = foot.relative(facing);
        level.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), 2); level.setBlock(head.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.HEAD), 2);
        require(level.getBlockState(foot).is(Blocks.RED_BED) && level.getBlockState(head).is(Blocks.RED_BED), "Bed fixture incomplete");
    }

    private static void remember(ServerPlayer player, BlockPos pos, List<String> expected) {
        var level = player.level();
        expected.add("\t" + pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ() + "\t"
                + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + "\t" + (level.getBlockEntity(pos) != null)
                + "\t" + player.getUUID() + "\t" + level.dimension().identifier());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Waiting for block-break callbacks timed out");
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
