package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaSeekSound;
import wiki.ica.xiaomiao.mcluaide.server.LuaLimitConfig;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in. Fresh build/sound-seek-probe save and config; exercises the real server/client sound packet. */
final class LuaSeekSoundProbe {
    private static final Identifier CAT = Identifier.parse("minecraft:music_disc.cat");
    private static final Identifier PLING = Identifier.parse("minecraft:block.note_block.pling");
    private static int stage, checks;
    private static long started;
    private static CompletableFuture<?> task;
    private static LuaSeekSoundPlayer.SeekInstance pling;
    private static net.minecraft.client.resources.sounds.EntityBoundSoundInstance vanillaBroadcast;

    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                started = System.nanoTime(); stage = 1;
                client.options.renderDistance().set(3); client.options.simulationDistance().set(3);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Probe creation cancelled"); }); return;
            }
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(180)) throw new AssertionError("Sound probe timeout at " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("sound_seek_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
                stage = 2; method.invoke(create); return;
            }
            if (stage == 2) {
                if (client.player == null || !ScriptManager.isServerAvailable() || client.getSingleplayerServer() == null) return;
                client.setScreen(null);
                UUID player = client.player.getUUID(); MinecraftServer server = client.getSingleplayerServer();
                task = CompletableFuture.runAsync(() -> {
                    try {
                        setRequired(server, false);
                        require(run(server, player, "MC.Sound.play('minecraft:block.note_block.pling',0,64,0)").error() == null, "Legacy omitted offset does not require client policy");
                        require(run(server, player, "MC.Sound.play('minecraft:block.note_block.pling',nil,nil,nil)").error() == null, "Global nil coordinate broadcast uses vanilla protocol without policy");
                        require(run(server, player, "MC.Sound.play('minecraft:block.note_block.pling',nil,nil,nil,1,1,'master','minecraft:overworld')").error() == null, "Dimension nil coordinate broadcast uses vanilla protocol");
                        require(run(server, player, "MC.Sound.play('minecraft:block.note_block.pling',0,nil,0)").error().contains("必须同时"), "Mixed nil group rejected by real API");
                        require(run(server, player, "local p=MC.Player.position('" + player + "'); MC.Sound.play('minecraft:block.note_block.pling',p.x,p.y,p.z,1,1,'master',p.dimension,'" + player + "',0)").error() == null, "Explicit zero retains vanilla path");
                        require(run(server, player, play(player, "record", "0.1")).error().contains("要求玩家安装客户端模组"), "Disabled policy rejects positive offset");
                        for (String offset : List.of("-1", "math.huge", "0/0", "'bad'"))
                            require(run(server, player, play(player, "record", offset)).error() != null, "Bad offset rejected " + offset);
                        setRequired(server, true);
                        require(run(server, player, "MC.Sound.play('minecraft:music_disc.cat',0,64,0,1,1,'record','minecraft:the_nether','" + player + "',1)").error().contains("同维度"), "Wrong dimension rejected");
                        require(run(server, player, "local p=MC.Player.position('" + player + "'); MC.Sound.play('minecraft:music_disc.cat',p.x+10000,p.y,p.z,1,1,'record',p.dimension,'" + player + "',1)").error().contains("播放范围"), "Out-of-range target rejected");
                        require(run(server, player, play(player, "record", "30.5") + "\n" + play(player, "music", "10.25")).error() == null, "Two offsets sent through real network");
                    } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }); stage = 3; return;
            }
            if (stage == 3) {
                if (!ready()) return;
                var sounds = active();
                if (sounds.size() != 2 || sounds.stream().anyMatch(sound -> !sound.prepared)) return;
                require(sounds.stream().allMatch(sound -> sound.error.isEmpty() && sound.duration > 31 && client.getSoundManager().isActive(sound)), "Native decoder and OpenAL playback active");
                require(sounds.stream().anyMatch(sound -> sound.getSource() == SoundSource.RECORDS)
                        && sounds.stream().anyMatch(sound -> sound.getSource() == SoundSource.MUSIC), "Categories retained independently");
                require(sounds.stream().allMatch(sound -> !sound.isRelative()
                        && sound.getAttenuation() == net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR), "Normal positional attenuation retained");
                UUID player = client.player.getUUID(); MinecraftServer server = client.getSingleplayerServer();
                task = CompletableFuture.runAsync(() -> {
                    try { require(run(server, player, "MC.Sound.stop('" + player + "','minecraft:music_disc.cat','record')").error() == null, "Lua stop request submitted"); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }); stage = 4; return;
            }
            if (stage == 4) {
                if (!ready()) return;
                var sounds = active();
                if (sounds.size() != 1) return;
                require(sounds.getFirst().getSource() == SoundSource.MUSIC && !sounds.getFirst().isStopped(), "Stop ID/category cancels only matching playback");
                var definition = client.getSoundManager().getSoundEvent(PLING).getSound(RandomSource.create(1));
                require(!definition.shouldStream(), "Probe includes non-streamed vanilla sound");
                pling = LuaSeekSoundPlayer.play(client, new LuaSeekSound(PLING, SoundSource.MASTER,
                        client.level.dimension().identifier(), client.player.getX(), client.player.getY(), client.player.getZ(),
                        0.5f, 0.75f, 0.01, 1));
                require(pling != null && pling.getSound().shouldStream(), "Non-streamed sound forced to per-instance streaming");
                require(!client.getSoundManager().getSoundEvent(PLING).getSound(RandomSource.create(1)).shouldStream(), "Shared vanilla sound definition unchanged");
                stage = 5; return;
            }
            if (stage == 5) {
                if (!pling.prepared) { if (!pling.error.isEmpty()) throw new AssertionError(pling.error); return; }
                require(pling.error.isEmpty() && pling.getPitch() == 0.75f && pling.getVolume() == 0.5f, "Pitch/volume preserved on offset stream");
                client.getSoundManager().stop(PLING, SoundSource.MASTER);
                require(pling.isStopped(), "Native /stopsound route cancels decoder");
                // Stop immediately before either worker has time to prepare the requested audio.
                var pending = LuaSeekSoundPlayer.play(client, new LuaSeekSound(CAT, SoundSource.RECORDS,
                        client.level.dimension().identifier(), client.player.getX(), client.player.getY(), client.player.getZ(),
                        1, 1, 0.1, 2));
                client.getSoundManager().stop(CAT, SoundSource.RECORDS);
                require(pending != null && pending.isStopped(), "Stop-before-decode native race cancels pending sound");
                task = client.reloadResourcePacks(); stage = 6; return;
            }
            if (stage == 6) {
                if (!ready() || LuaSeekSoundPlayer.audioBytes() != 0) return;
                require(LuaSeekSoundPlayer.activeCount() == 0, "Resource reload cancels all offsets and releases native memory");
                // A one-sample sound stream should finish naturally and release the budget too.
                var bytes = client.getResourceManager().getResourceOrThrow(pling.getSound().getPath()).open();
                double duration; try (bytes) { duration = LuaOggMetadata.read(bytes.readAllBytes()).duration(); }
                pling = LuaSeekSoundPlayer.play(client, new LuaSeekSound(PLING, SoundSource.MASTER,
                        client.level.dimension().identifier(), client.player.getX(), client.player.getY(), client.player.getZ(),
                        1, 1, Math.max(0.001, duration - 1.0 / 44100), 1));
                stage = 7; return;
            }
            if (stage == 7) {
                if (!pling.prepared || LuaSeekSoundPlayer.activeCount() != 0 || LuaSeekSoundPlayer.audioBytes() != 0) return;
                require(pling.error.isEmpty() && pling.isStopped(), "Natural completion frees offset decoder");
                UUID player = client.player.getUUID(); MinecraftServer server = client.getSingleplayerServer();
                task = CompletableFuture.runAsync(() -> {
                    try {
                        require(run(server, player, "MC.Sound.play('minecraft:music_disc.cat',nil,nil,nil,1,1,'record','minecraft:the_nether','" + player + "',1)").error().contains("同维度"), "Scoped nil broadcast excludes wrong dimension");
                        require(run(server, player, "MC.Sound.play('minecraft:music_disc.cat',nil,nil,nil,1,1,'record','minecraft:overworld','" + player + "',30.5)").error() == null, "Scoped non-positional offset broadcast submitted");
                        require(run(server, player, "MC.Sound.play('minecraft:music_disc.cat',1000000,64,1000000,1,1,'music',nil,'" + player + "',10.25)").error() == null, "Global nil dimension ignores far position");
                    } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }); stage = 8; return;
            }
            if (stage == 8) {
                if (!ready()) return;
                var sounds = active();
                if (sounds.size() != 2 || sounds.stream().anyMatch(sound -> !sound.prepared)) return;
                require(sounds.stream().allMatch(sound -> sound.error.isEmpty() && sound.isRelative()
                        && sound.getAttenuation() == net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE
                        && client.getSoundManager().isActive(sound)), "Broadcast offsets play through mixer without position or attenuation");
                client.getSoundManager().stop(CAT, null);
                require(active().isEmpty() && LuaSeekSoundPlayer.audioBytes() == 0, "Stop cancels both global and dimension broadcasts");
                UUID player = client.player.getUUID(); MinecraftServer server = client.getSingleplayerServer();
                task = CompletableFuture.runAsync(() -> {
                    try {
                        setRequired(server, false);
                        require(run(server, player, "MC.Sound.play('minecraft:music_disc.cat',nil,nil,nil,1,1,'record',nil,'" + player + "')").error() == null,
                                "Vanilla non-positional broadcast does not require client policy");
                    } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }); stage = 9; return;
            }
            if (stage == 9) {
                if (!ready()) return;
                var engine = ((wiki.ica.xiaomiao.mcluaide.client.mixin.SoundManagerAccessor) client.getSoundManager()).mcLuaIde$getSoundEngine();
                var channelsField = net.minecraft.client.sounds.SoundEngine.class.getDeclaredField("instanceToChannel"); channelsField.setAccessible(true);
                var channels = (java.util.Map<?, ?>) channelsField.get(engine);
                vanillaBroadcast = channels.keySet().stream()
                        .filter(sound -> sound instanceof net.minecraft.client.resources.sounds.EntityBoundSoundInstance
                                && ((net.minecraft.client.resources.sounds.SoundInstance) sound).getIdentifier().equals(CAT))
                        .map(sound -> (net.minecraft.client.resources.sounds.EntityBoundSoundInstance) sound).findFirst().orElse(null);
                if (vanillaBroadcast == null || !client.getSoundManager().isActive(vanillaBroadcast)) return;
                require(vanillaBroadcast.getVolume() == 1 && vanillaBroadcast.getPitch() == 1, "Vanilla broadcast preserves loudness/pitch");
                double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
                try {
                    client.player.setPos(x + 128, y, z + 128); vanillaBroadcast.tick();
                    require(Math.abs(vanillaBroadcast.getX() - client.player.getX()) < .01
                            && Math.abs(vanillaBroadcast.getZ() - client.player.getZ()) < .01, "Vanilla broadcast follows listener after movement");
                } finally { client.player.setPos(x, y, z); vanillaBroadcast.tick(); }
                UUID player = client.player.getUUID(); MinecraftServer server = client.getSingleplayerServer();
                task = CompletableFuture.runAsync(() -> {
                    try { require(run(server, player, "MC.Sound.stop('" + player + "','minecraft:music_disc.cat','record')").error() == null, "Lua stop vanilla broadcast submitted"); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }); stage = 10; return;
            }
            if (stage == 10) {
                if (!ready() || client.getSoundManager().isActive(vanillaBroadcast)) return;
                require(!client.getSoundManager().isActive(vanillaBroadcast), "Lua stops vanilla non-positional broadcast");
                System.out.println("Sound seek real game probe passed: " + checks + " checks; policy, real vanilla/seek packets, nil broadcasts, listener movement, positional ranges, stop/race, reload and natural completion");
                stage = 99; client.stop();
            }
        } catch (Exception | AssertionError failure) {
            int failed = stage; stage = 99; client.stop();
            throw new IllegalStateException("Sound seek real game probe failed at stage " + failed, failure);
        }
    }

    private static String play(UUID uuid, String category, String seconds) {
        return "local p=MC.Player.position('" + uuid + "'); MC.Sound.play('minecraft:music_disc.cat',p.x,p.y,p.z,1,1,'" + category + "',p.dimension,'" + uuid + "'," + seconds + ")";
    }
    private static ServerScriptManager.ExecutionResult run(MinecraftServer server, UUID player, String code) throws Exception {
        CompletableFuture<ServerScriptManager.ExecutionResult> completed = new CompletableFuture<>();
        onServer(server, () -> ServerScriptManager.start(server, player, "sound_seek_probe", code, false, ignored -> { }, completed::complete));
        return completed.get(10, TimeUnit.SECONDS);
    }
    private static void setRequired(MinecraftServer server, boolean value) throws Exception {
        onServer(server, () -> {
            LuaLimitConfig.save(LuaLimits.decode(LuaLimitConfig.current().encode().replaceAll("requireClientMod=(true|false)", "requireClientMod=" + value)));
            return true;
        });
    }
    @SuppressWarnings("unchecked") private static List<LuaSeekSoundPlayer.SeekInstance> active() throws Exception {
        var field = LuaSeekSoundPlayer.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
        return List.copyOf((Set<LuaSeekSoundPlayer.SeekInstance>) field.get(null));
    }
    private static <T> T onServer(MinecraftServer server, Callable<T> action) throws Exception {
        return server.submit(() -> { try { return action.call(); } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); } }).get(5, TimeUnit.SECONDS);
    }
    private static boolean ready() { if (!task.isDone()) return false; task.join(); return true; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
