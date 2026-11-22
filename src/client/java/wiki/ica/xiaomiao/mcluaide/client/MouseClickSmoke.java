package wiki.ica.xiaomiao.mcluaide.client;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.luaj.vm2.LuaValue;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

/** Input filtering, rapid presses, game hook signature, wire format and Lua API discovery. */
public final class MouseClickSmoke {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (int button = 0; button <= 7; button++) {
            for (int action = 0; action <= 2; action++) {
                require(MouseClickReporter.shouldReport(true, false, true, true, button, action)
                        == (button <= 1 && action == 1), "press/release/repeat/button");
            }
        }
        for (int button = 0; button <= 1; button++) {
            require(!MouseClickReporter.shouldReport(false, false, true, true, button, 1), "no world");
            require(!MouseClickReporter.shouldReport(true, true, true, true, button, 1), "GUI clicks");
            require(!MouseClickReporter.shouldReport(true, false, false, true, button, 1), "ungrabbed mouse");
            require(!MouseClickReporter.shouldReport(true, false, true, false, button, 1), "inactive window");
            for (int i = 0; i < 20; i++) {
                require(MouseClickReporter.shouldReport(true, false, true, true, button, 1), "rapid clicks preserved");
                require(!MouseClickReporter.shouldReport(true, false, true, true, button, 0), "release excluded");
            }
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var packet = new LuaProtocol.MouseClick(button == 0);
                LuaProtocol.MouseClick.CODEC.encode(buffer, packet);
                require(LuaProtocol.MouseClick.CODEC.decode(buffer).equals(packet) && !buffer.isReadable(), "wire round trip");
            } finally { buffer.release(); }
        }
        require(MouseHandler.class.getDeclaredMethod("onButton", long.class, MouseButtonInfo.class, int.class) != null, "mixin target");
        var apiClass = Class.forName("wiki.ica.xiaomiao.mcluaide.server.MinecraftLuaApi");
        var factory = java.util.Arrays.stream(apiClass.getDeclaredMethods()).filter(m -> m.getName().equals("eventApi")).findFirst().orElseThrow();
        factory.setAccessible(true);
        // Standalone smoke runs outside the game launcher; provide an isolated loader config path.
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        var configDirectory = loader.getClass().getDeclaredField("configDir");
        configDirectory.setAccessible(true);
        configDirectory.set(loader, java.nio.file.Path.of("build").toAbsolutePath());
        var executionClass = factory.getParameterTypes()[0];
        var constructor = executionClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object execution = constructor.newInstance(null, java.util.UUID.randomUUID(), "mouse_click_smoke",
                wiki.ica.xiaomiao.mcluaide.network.LuaLimits.defaults(), false);
        LuaValue api = (LuaValue) factory.invoke(null, execution);
        LuaValue callback = org.luaj.vm2.lib.jse.JsePlatform.standardGlobals().load("return function(event) end").call();
        for (String side : new String[]{"Left", "Right"}) {
            require(api.get("onPlayer" + side + "Click").isfunction(), "Lua convenience API");
            boolean found = false;
            LuaValue names = api.get("names").call();
            for (int i = 1; i <= names.length(); i++) found |= names.get(i).tojstring().equals("player" + side + "Click");
            require(found, "generic event discovery");
            int id = api.get("onPlayer" + side + "Click").call(callback).checkint();
            require(api.get("off").call(LuaValue.valueOf(id)).checkboolean(), "unsubscribe convenience callback");
            int genericId = api.get("on").call(LuaValue.valueOf("player" + side + "Click"), callback).checkint();
            require(api.get("off").call(LuaValue.valueOf(genericId)).checkboolean(), "generic registration and removal");
            String source = "MC.Event.onPlayer" + side + "Click(function(event)\n print(event.";
            var choices = LuaMemberCompletion.choices(source, source.length(), "event.").stream().map(LuaArgumentCompletion.Choice::insertion).toList();
            require(choices.containsAll(java.util.List.of("event.playerUuid", "event.itemComponents", "event.dimension")), "event field completion");
        }
        System.out.println("Mouse click smoke passed: " + checks + " checks");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
