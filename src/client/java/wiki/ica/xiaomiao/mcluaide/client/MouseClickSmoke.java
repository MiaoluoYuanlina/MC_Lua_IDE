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
                var tracker = new MouseClickReporter.ButtonTracker();
                require(tracker.accept(true, button, action) == (button <= 1 && action == 1), "new presses only; no orphan releases");
            }
        }
        for (int button = 0; button <= 1; button++) {
            var tracker = new MouseClickReporter.ButtonTracker();
            require(!tracker.accept(false, button, 1) && !tracker.accept(false, button, 0), "GUI-only click ignored");
            for (int i = 0; i < 20; i++) {
                require(tracker.accept(true, button, 1), "rapid presses preserved");
                require(!tracker.accept(true, button, 1), "duplicate press ignored");
                require(!tracker.accept(true, button, 2), "repeat ignored");
                require(tracker.accept(true, button, 0), "release delivered");
                require(!tracker.accept(true, button, 0), "duplicate release ignored");
            }
            require(tracker.accept(true, button, 1) && tracker.accept(false, button, 0), "release after opening GUI or losing focus");
            require(tracker.accept(true, button, 1), "press before disconnect");
            tracker.clear();
            require(!tracker.accept(true, button, 0), "no stale release after reconnect");
            require(tracker.accept(true, button, 1) && tracker.accept(true, button, 0), "new connection starts fresh");
            for (boolean pressed : new boolean[]{true, false}) {
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    var packet = new LuaProtocol.MouseButtonAction(button == 0, pressed);
                    LuaProtocol.MouseButtonAction.CODEC.encode(buffer, packet);
                    require(LuaProtocol.MouseButtonAction.CODEC.decode(buffer).equals(packet) && !buffer.isReadable(), "press/release wire round trip");
                } finally { buffer.release(); }
            }
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var packet = new LuaProtocol.MouseClick(button == 0);
                LuaProtocol.MouseClick.CODEC.encode(buffer, packet);
                require(LuaProtocol.MouseClick.CODEC.decode(buffer).equals(packet) && !buffer.isReadable(), "legacy 1.0.19 wire compatibility");
            } finally { buffer.release(); }
        }
        var both = new MouseClickReporter.ButtonTracker();
        require(both.accept(true, 0, 1) && both.accept(true, 1, 1), "independent buttons pressed");
        require(both.accept(true, 0, 0) && both.accept(false, 1, 0), "independent buttons released");
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
            require(choices.containsAll(java.util.List.of("event.playerUuid", "event.itemComponents", "event.dimension", "event.pressed")), "event field completion");
        }
        var gameEvent = Class.forName("wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager$GameEvent");
        var eventConstructor = gameEvent.getDeclaredConstructors()[0];
        eventConstructor.setAccessible(true);
        var toLua = gameEvent.getDeclaredMethod("toLuaTable");
        toLua.setAccessible(true);
        for (String eventType : new String[]{"playerLeftClick", "playerRightClick"}) {
            for (boolean pressed : new boolean[]{true, false}) {
                LuaValue event = (LuaValue) toLua.invoke(eventConstructor.newInstance(eventType, java.util.Map.of("pressed", pressed)));
                require(event.get("pressed").isboolean() && event.get("pressed").checkboolean() == pressed, "Lua boolean preserves false on release");
                require(event.get("type").tojstring().equals(eventType), "left/right callback event type");
            }
        }
        System.out.println("Mouse click smoke passed: " + checks + " checks");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
