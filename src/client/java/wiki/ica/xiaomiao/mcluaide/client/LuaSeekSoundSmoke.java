package wiki.ica.xiaomiao.mcluaide.client;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.sounds.SoundSource;
import wiki.ica.xiaomiao.mcluaide.network.LuaSeekSound;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exact offsets, bounded protocol, cancellation races and IDE parameter metadata without a game window. */
public final class LuaSeekSoundSmoke {
    private static int checks;
    private static final Identifier ID = Identifier.parse("test:custom.sound");
    private static final Identifier PATH = Identifier.parse("test:sounds/music/sample.ogg");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (var source : SoundSource.values()) {
            for (boolean relative : new boolean[]{false, true}) {
            LuaSeekSound expected = new LuaSeekSound(ID, source, Identifier.parse("test:dimension"),
                    -12.125, 65.5, 123.875, 2.5f, 1.25f, 30.125, Long.MIN_VALUE, relative);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
            try {
                LuaSeekSound.CODEC.encode(buffer, expected);
                require(buffer.readableBytes() < 256, "Bounded sound request");
                require(expected.equals(LuaSeekSound.CODEC.decode(buffer)) && !buffer.isReadable(), "Codec exact round-trip " + source);
            } finally { buffer.release(); }
            }
        }
        var broadcast = new LuaSeekSoundPlayer.SeekInstance(ResourceProvider.EMPTY,
                new LuaSeekSound(ID, SoundSource.MASTER, Identifier.parse("minecraft:the_nether"), 0, 0, 0, 1, 1, 30, 5, true));
        require(broadcast.isRelative() && broadcast.getAttenuation() == net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE,
                "Broadcast seek has no fixed position or attenuation");
        var positional = new LuaSeekSoundPlayer.SeekInstance(ResourceProvider.EMPTY, request(30));
        require(!positional.isRelative() && positional.getAttenuation() == net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR,
                "Old positional constructor retains attenuation");
        for (double time : new double[]{-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            invalid(() -> request(time), "Invalid offset rejected " + time);
        require(request(0).startSeconds() == 0 && request(0.0001).startSeconds() == 0.0001, "Zero/fractional compatibility");
        invalid(() -> new LuaSeekSound(ID, SoundSource.MASTER, Identifier.parse("minecraft:overworld"),
                Double.NaN, 0, 0, 1, 1, 1, 1), "Invalid coordinate");
        for (float volume : new float[]{-1, Float.POSITIVE_INFINITY, Float.NaN})
            invalid(() -> new LuaSeekSound(ID, SoundSource.MASTER, Identifier.parse("minecraft:overworld"),
                    0, 0, 0, volume, 1, 1, 1), "Invalid volume");
        for (float pitch : new float[]{-1, 2.1f, Float.POSITIVE_INFINITY, Float.NaN})
            invalid(() -> new LuaSeekSound(ID, SoundSource.MASTER, Identifier.parse("minecraft:overworld"),
                    0, 0, 0, 1, pitch, 1, 1), "Invalid pitch");

        byte[] audio = Files.readAllBytes(Path.of("src/test/resources/resourcepacks/silence.ogg"));
        ResourceProvider resources = path -> path.equals(PATH)
                ? Optional.of(new Resource(null, () -> new ByteArrayInputStream(audio))) : Optional.empty();
        for (int offset : new int[]{1, 576, 1000}) {
            var sound = new LuaSeekSoundPlayer.SeekInstance(resources, request(offset / 22050.0));
            try (var stream = sound.getAudioStream(null, PATH, false).get(5, TimeUnit.SECONDS)) {
                require(sound.prepared && sound.error.isEmpty(), "Prepared without error");
                long frames = 0;
                for (var chunk = stream.read(4096); chunk.hasRemaining(); chunk = stream.read(4096))
                    frames += chunk.remaining() / stream.getFormat().getFrameSize();
                require(frames == 1152 - offset, "Playback starts at exact sample " + offset + ": " + frames);
                require(LuaSeekSoundPlayer.audioBytes() == audio.length, "Encoded audio budget reserved");
                sound.cancel();
                require(!stream.read(4096).hasRemaining(), "Cancelled stream cannot restart");
                sound.cancel(); stream.close();
            }
            require(LuaSeekSoundPlayer.audioBytes() == 0, "Decoder freed exactly once");
        }
        for (double offset : new double[]{1152.0 / 22050, 100, Double.MAX_VALUE}) {
            var sound = new LuaSeekSoundPlayer.SeekInstance(resources, request(offset));
            try (var stream = sound.getAudioStream(null, PATH, false).get(5, TimeUnit.SECONDS)) {
                require(sound.isStopped() && !stream.read(1024).hasRemaining() && sound.error.isEmpty(), "EOF never restarts or throws");
            }
        }
        var missing = new LuaSeekSoundPlayer.SeekInstance(ResourceProvider.EMPTY, request(0.01));
        try (var stream = missing.getAudioStream(null, PATH, false).get(5, TimeUnit.SECONDS)) {
            require(missing.isStopped() && !missing.error.isEmpty() && !stream.read(1024).hasRemaining(), "Missing resource fails closed");
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ResourceProvider slow = path -> Optional.of(new Resource(null, () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Test timed out"); }
            catch (InterruptedException error) { throw new java.io.IOException(error); }
            return new ByteArrayInputStream(audio);
        }));
        var cancelled = new LuaSeekSoundPlayer.SeekInstance(slow, request(0.01));
        var future = cancelled.getAudioStream(null, PATH, false);
        require(entered.await(5, TimeUnit.SECONDS), "Decode happens asynchronously");
        cancelled.cancel(); release.countDown();
        try (var stream = future.get(5, TimeUnit.SECONDS)) {
            require(cancelled.isStopped() && !cancelled.prepared && !stream.read(1024).hasRemaining(), "Stop before resource opens cancels pending playback");
        }
        require(LuaSeekSoundPlayer.audioBytes() == 0, "No leaked native resource reservations");

        var doc = LuaApiDocumentation.find("MC.Sound.play");
        require(doc.signature().endsWith("startSeconds?)") && doc.details().contains("仅强制客户端模式"), "Offset and client restriction documented");
        require(doc.describe("startSeconds?").contains("非负秒数"), "Chinese hover parameter");
        require(doc.describe("x?").contains("nil") && doc.describe("dimension?").contains("全服广播"), "Nil coordinate/dimension hover metadata");
        String sound = "'minecraft:block.note_block.pling'";
        for (String invocation : new String[]{"MC.Sound.play(" + sound + ")", "MC.Sound.play(" + sound + ",nil,nil,nil)",
                "MC.Sound.play(" + sound + ",nil,nil,nil,1,1,'master','minecraft:overworld')",
                "MC.Sound.play(" + sound + ",0,64,0,1,1,'master',nil)", "MC.Sound.play " + sound,
                "local play=MC.Sound.play; play(" + sound + ",nil,nil,nil)",
                "local x,y,z=nil,nil,nil; MC.Sound.play(" + sound + ",x,y,z)",
                "function play(x,y,z) MC.Sound.play(" + sound + ",x,y,z) end"}) {
            var result = LuaCodeAnalyzer.analyze("broadcast", invocation);
            require(result.valid(), "Nil broadcast not falsely flagged: " + invocation + " " + result.diagnostics());
        }
        for (String coordinate : new String[]{"nil,64,0", "0,nil,0", "0,64,nil", "0,nil,nil", "nil,64,nil", "nil,nil,0", "0"}) {
            var result = LuaCodeAnalyzer.analyze("partial_nil", "MC.Sound.play(" + sound + "," + coordinate + ")");
            require(result.diagnostics().stream().anyMatch(d -> d.message().contains("必须同时")), "Mixed coordinates not diagnosed: " + coordinate);
        }
        String mixed = "MC.Sound.play(" + sound + ",0,nil,0)";
        var group = LuaCodeAnalyzer.analyze("mixed", mixed).diagnostics().stream().filter(d -> d.message().contains("必须同时")).findFirst().orElseThrow();
        require(mixed.substring(group.start(), group.end()).equals("nil"), "Mixed group red range not on nil argument");
        String multiValue = "local function getYZ() return 64,0 end; MC.Sound.play(" + sound + ",0,getYZ())";
        require(LuaCodeAnalyzer.analyze("multi_coordinate", multiValue).valid(), "Multiple return coordinates falsely flagged by AST");
        require(LuaCodeAnalyzer.analyze("long_multi_coordinate", "--" + "x".repeat(65_540) + "\n" + multiValue).valid(),
                "Multiple return coordinates falsely flagged by long-line token fallback");
        String prefix = "local uuid = '123e4567-e89b-12d3-a456-426614174000'\nMC.Sound.play('minecraft:music_disc.cat',0,64,0,1,1,'record',nil,uuid,";
        require(LuaCodeAnalyzer.analyze("seek", prefix + "30.5)").diagnostics().isEmpty(), "Valid offset not falsely flagged");
        require(LuaCodeAnalyzer.analyze("seek", prefix + "'wrong')").diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("startSeconds")), "Wrong offset type highlighted");
        require(LuaCodeAnalyzer.analyze("seek", LuaApiDocumentation.usageTemplate("MC.Sound.play")).diagnostics().isEmpty(), "Complete usage template valid");
        String code = "local seconds = 3.5\nlocal text = 'wrong'\n" + prefix;
        var context = LuaCursorContext.parameterAt(code, code.length());
        require(context != null && context.index() == 9, "Cursor recognizes final offset parameter");
        var suggestions = LuaArgumentCompletion.suggest(code, code.length(), context, List.of());
        require(suggestions.stream().anyMatch(item -> item.insertion().equals("seconds"))
                && suggestions.stream().noneMatch(item -> item.insertion().equals("text")), "Numeric variable completion only");
        System.out.println("Seek sound smoke passed: " + checks + " checks");
    }

    private static LuaSeekSound request(double seconds) {
        return new LuaSeekSound(ID, SoundSource.RECORDS, Identifier.parse("minecraft:overworld"), 0, 0, 0, 1, 1, seconds, 123);
    }
    private static void invalid(Runnable action, String message) {
        boolean failed = false; try { action.run(); } catch (IllegalArgumentException expected) { failed = true; }
        require(failed, message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
