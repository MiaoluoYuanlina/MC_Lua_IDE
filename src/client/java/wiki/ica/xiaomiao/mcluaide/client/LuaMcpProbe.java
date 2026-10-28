package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.*;
import net.minecraft.server.MinecraftServer;
import wiki.ica.xiaomiao.mcluaide.mcp.*;
import wiki.ica.xiaomiao.mcluaide.server.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in real menu/window/network/MCP/game checks in a new isolated build/mcp-probe world. */
final class LuaMcpProbe {
    private static int stage, checks, clientPort, serverPort;
    private static long started;
    private static LuaIdeScreen ide;
    private static LuaExtensionWindow localFrame, remoteFrame;
    private static CompletableFuture<Void> task;
    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                started = System.nanoTime(); stage = 1;
                client.options.guiScale().set(1); client.resizeGui(); client.options.renderDistance().set(2); client.options.simulationDistance().set(6);
                Path root = client.gameDirectory.toPath().toAbsolutePath().normalize().resolve("config/mc_lua_ide");
                require(root.startsWith(client.gameDirectory.toPath().toAbsolutePath().normalize()) && root.toString().contains("mcp-probe"), "Unsafe MCP fixture directory");
                LuaMcpSettings.defaults(false).save(root.resolve("mcp-client.properties")); LuaMcpSettings.defaults(true).save(root.resolve("mcp-server.properties"));
                clientPort = freePort(); serverPort = freePort(); while (serverPort == clientPort) serverPort = freePort();
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("MCP probe cancelled"); }); return;
            }
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(200), "MCP probe timed out at stage " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("mcp_" + System.currentTimeMillis()); create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true); stage = 2; method.invoke(create); return;
            }
            if (stage == 2) {
                if (client.player == null || !ScriptManager.isServerAvailable()) return;
                require(!LuaClientMcpService.status().running() && !LuaClientMcpService.status().settings().enabled(), "Client started by default");
                ide = new LuaIdeScreen(null, ""); client.setScreen(ide); set(ide, "manualOpen", false); set(ide, "manualProgress", 0F);
                var toggle = LuaIdeScreen.class.getDeclaredMethod("toggleMenu", String.class); toggle.setAccessible(true); toggle.invoke(ide, "tools");
                @SuppressWarnings("unchecked") var items = (List<Button>) get(ide, "toolMenuItems");
                Button mcp = items.stream().filter(button -> button.getMessage().getString().startsWith("MCP 服务器")).findFirst().orElseThrow();
                require(ide.mouseClicked(mouse(mcp.getX() + 5, mcp.getY() + 5), false), "Tool menu MCP option not clickable");
                stage = 3; return;
            }
            if (stage == 3) {
                localFrame = frame(LuaMcpWindow.CLIENT_ID); if (get(localFrame, "content") == null) return;
                require(localFrame.isOpen() && get(get(localFrame, "content"), "data") != null, "Client MCP window failed");
                var settings = new LuaSettingsScreen(ide); client.setScreen(settings);
                Button mcp = settings.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(button -> button.getMessage().getString().contains("MCP 服务器")).findFirst().orElseThrow();
                require(settings.mouseClicked(mouse(mcp.getX()+5, mcp.getY()+5), false), "Server configuration MCP option not clickable"); stage = 4; return;
            }
            if (stage == 4) {
                if (client.screen != ide) return;
                localFrame = frame(LuaMcpWindow.CLIENT_ID); remoteFrame = frame(LuaMcpWindow.SERVER_ID);
                Object local = get(localFrame, "content"), remote = get(remoteFrame, "content");
                if (local == null || remote == null || get(remote, "data") == null || get(local, "data") == null) return;
                require(remoteFrame.isOpen() && !((LuaMcpStatus)get(remote, "data")).settings().enabled(), "Server MCP not disabled by default");
                ((EditBox)get(local, "port")).setValue(Integer.toString(clientPort)); ((EditBox)get(remote, "port")).setValue(Integer.toString(serverPort));
                for (int i = 0; i < 20; i++) { localFrame.tick(); remoteFrame.tick(); }
                click(localFrame, 10, 129); click(remoteFrame, 10, 129);
                stage = 5; return;
            }
            if (stage == 5) {
                Object remote = get(remoteFrame, "content"); LuaMcpStatus status = (LuaMcpStatus)get(remote, "data");
                if (!LuaClientMcpService.status().running() || status == null || !status.running()) return;
                require(LuaClientMcpService.status().settings().port() == clientPort && status.settings().port() == serverPort, "Port edits did not apply");
                click(localFrame, 10, 155);
                require(client.keyboardHandler.getClipboard().contains(LuaClientMcpService.status().settings().token()), "Connection prompt copy failed");
                MinecraftServer server = client.getSingleplayerServer(); UUID owner = client.player.getUUID();
                task = CompletableFuture.runAsync(() -> { try { verify(client, server, owner); } catch (Exception error) { throw new CompletionException(error); } }); stage = 6; return;
            }
            if (stage == 6 && task.isDone()) {
                task.join();
                require(!LuaClientMcpService.status().running(), "Client listener remained enabled after disable");
                ide.setExtensionWindowOpen(LuaMcpWindow.CLIENT_ID, true); ide.setExtensionWindowOpen(LuaMcpWindow.SERVER_ID, true);
                localFrame = frame(LuaMcpWindow.CLIENT_ID); remoteFrame = frame(LuaMcpWindow.SERVER_ID);
                localFrame.setDockSide(DockSide.LEFT); remoteFrame.setDockSide(DockSide.RIGHT);
                localFrame.setDockBounds(8, 68, 280, 350); remoteFrame.setDockBounds(560, 68, 280, 350);
                for (int i = 0; i < 20; i++) { localFrame.tick(); remoteFrame.tick(); }
                require(localFrame.dockSide() == DockSide.LEFT && remoteFrame.dockSide() == DockSide.RIGHT, "MCP windows did not dock independently");
                client.setScreen(null); ide = new LuaIdeScreen(null, ""); client.setScreen(ide);
                require(frame(LuaMcpWindow.CLIENT_ID).isOpen() && frame(LuaMcpWindow.SERVER_ID).isOpen(), "MCP window visibility was not saved");
                require(frame(LuaMcpWindow.CLIENT_ID).dockSide() == DockSide.LEFT && frame(LuaMcpWindow.SERVER_ID).dockSide() == DockSide.RIGHT, "MCP dock positions lost after reopening IDE");
                stage = 99; System.out.println("MCP real game/UI probe passed: " + checks + " checks; both menus/windows, HTTP tools, live current/background file sync, multi-edit highlights/hover/Esc, font scale, unsaved conflict choices/undo, permissions, execution/logs, rotation, restore and docking"); client.stop();
            }
        } catch (Exception | AssertionError failure) { int failed = stage; stage = 99; client.stop(); throw new IllegalStateException("MCP game probe failed at stage " + failed, failure); }
    }
    private static void verify(Minecraft client, MinecraftServer server, UUID owner) throws Exception {
        LuaMcpStatus remote = server.submit(() -> LuaServerMcpService.status(server)).get(10, TimeUnit.SECONDS);
        LuaMcpStatus local = onClient(client, LuaClientMcpService::status).get(10, TimeUnit.SECONDS);
        try (Connection a = new Connection(local.settings()); Connection b = new Connection(remote.settings())) {
            for (Connection connection : List.of(a, b)) {
                require(!connection.call("mc_status", new JsonObject()).get("isError").getAsBoolean(), "Live status failed");
                require(connection.call("lua_save_script", object("name", "mcp/no_permission", "source", "print('bad')")).get("isError").getAsBoolean(), "Write disabled by default was bypassed");
                require(connection.call("lua_run_script", object("name", "mcp/no_permission")).get("isError").getAsBoolean(), "Execute disabled by default was bypassed");
                require(connection.call("mc_api_documentation", object("query", "MC.GameRule.set")).getAsJsonObject("structuredContent").get("text").getAsString().contains("ruleId"), "API help query failed");
                require(connection.call("mc_registry_ids", object("registry", "minecraft:item", "query", "apple")).getAsJsonObject("structuredContent").getAsJsonArray("entries").toString().contains("minecraft:apple"), "Actual item registry missing");
            }
        }
        local = onClientFuture(client, () -> LuaClientMcpService.configure(true, clientPort, true, true, false)).get(10, TimeUnit.SECONDS);
        remote = onServer(server, () -> LuaServerMcpService.configure(server, server.getPlayerList().getPlayer(owner), object("enabled", true, "port", serverPort, "allowWrite", true, "allowExecution", true))).get(10, TimeUnit.SECONDS);
        try (Connection a = new Connection(local.settings()); Connection b = new Connection(remote.settings())) {
            for (Connection connection : List.of(a, b)) {
                String name = connection == a ? "mcp/from_client" : "mcp/from_server";
                String source = "print('mcp-live')\nMC.Event.onInterval(1000, function(e) print(e.count) end)";
                require(!connection.call("lua_save_script", object("name", name, "source", source)).get("isError").getAsBoolean(), "MCP save failed");
                require(connection.call("lua_read_script", object("name", name)).getAsJsonObject("structuredContent").get("text").getAsString().equals(source), "MCP read round trip failed");
                require(!connection.call("lua_run_script", object("name", name)).get("isError").getAsBoolean(), "MCP run failed");
                require(onServer(server, () -> ServerScriptManager.read(name)).get().equals(source), "Running rewrote script contents");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); String logs;
                do { logs = connection.call("lua_execution_logs", new JsonObject()).toString(); if (!logs.contains("mcp-live")) Thread.sleep(20); }
                while (!logs.contains("mcp-live") && System.nanoTime() < until);
                require(logs.contains("mcp-live"), "Live Lua output missing");
                (connection == a ? b : a).call("lua_stop_script", new JsonObject());
                require(server.submit(() -> name.equals(ServerScriptManager.activeScriptName())).get(), "Other MCP role stopped this role's script");
                connection.call("lua_stop_script", new JsonObject());
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (server.submit(ServerScriptManager::activeScriptName).get() != null && System.nanoTime() < until) Thread.sleep(20);
                require(server.submit(ServerScriptManager::activeScriptName).get() == null, "MCP script did not stop");
                verifyFileSync(client, connection, name);
            }
        }
        var before = local.settings();
        local = onClientFuture(client, () -> LuaClientMcpService.configure(true, serverPort, true, true, false)).get(10, TimeUnit.SECONDS);
        require(!local.running() && !local.problem().isBlank(), "Port collision not surfaced in window status");
        local = onClientFuture(client, () -> LuaClientMcpService.configure(true, clientPort, true, true, true)).get(10, TimeUnit.SECONDS);
        require(local.running() && !local.settings().token().equals(before.token()), "Token rotation failed");
        try (Connection rotated = new Connection(local.settings())) { require(!rotated.call("mc_status", new JsonObject()).get("isError").getAsBoolean(), "Rotated listener broken"); }
        var root = client.gameDirectory.toPath().resolve("config/mc_lua_ide");
        verifyPermissions(server, owner);
        require(LuaMcpSettings.load(root.resolve("mcp-client.properties"), false).enabled() && LuaMcpSettings.load(root.resolve("mcp-server.properties"), true).enabled(), "Enabled config was not persisted");
        server.submit(() -> { LuaServerMcpService.stopped(server); LuaServerMcpService.started(server); }).get(10, TimeUnit.SECONDS);
        remote = server.submit(() -> LuaServerMcpService.status(server)).get(); require(remote.running(), "Server auto-restore failed");
        try (Connection restored = new Connection(remote.settings())) { require(!restored.call("mc_status", new JsonObject()).get("isError").getAsBoolean(), "Restored server listener failed"); }
        onClient(client, () -> { LuaClientMcpService.disconnected(); return null; }).get();
        require(!onClient(client, LuaClientMcpService::status).get().running() && LuaMcpSettings.load(root.resolve("mcp-client.properties"), false).enabled(), "Disconnect lost enabled preference or left listener open");
        onClient(client, () -> { LuaClientMcpService.restore(); return null; }).get(); require(onClient(client, LuaClientMcpService::status).get().running(), "Client reconnect restore failed");
        var callLogs = server.submit(() -> LuaServerMcpService.status(server)).get();
        require(!callLogs.logs().isEmpty() && !LuaMcpServer.JSON.toJson(callLogs.logs()).contains(callLogs.settings().token()), "MCP logs absent or leaked bearer");
        onClientFuture(client, () -> LuaClientMcpService.configure(false, clientPort, true, true, false)).get();
        remote = onServer(server, () -> LuaServerMcpService.configure(server, server.getPlayerList().getPlayer(owner), object("enabled", false, "port", serverPort, "allowWrite", true, "allowExecution", true))).get();
        require(!remote.running() && !LuaMcpSettings.load(root.resolve("mcp-client.properties"), false).enabled() && !LuaMcpSettings.load(root.resolve("mcp-server.properties"), true).enabled(), "Disabled preference not persisted");
        server.submit(() -> { LuaServerMcpService.stopped(server); LuaServerMcpService.started(server); }).get();
        require(!server.submit(() -> LuaServerMcpService.status(server)).get().running(), "Server restarted despite disabled preference");
    }
    private static void verifyFileSync(Minecraft client, Connection connection, String name) throws Exception {
        String base = "local price = 1\nprint(price)\nlocal count = 2\nprint(count)\nlocal last = 3\nprint(last)\n";
        String updated = base.replace("price = 1", "price = 11").replace("last = 3", "last = 33");
        require(!connection.call("lua_save_script", object("name", name, "source", base)).get("isError").getAsBoolean(), "Prepare sync fixture");
        onClient(client, () -> {
            var method = LuaIdeScreen.class.getDeclaredMethod("openScript", String.class); method.setAccessible(true); method.invoke(ide, name);
            ide.setExtensionWindowOpen(LuaMcpWindow.CLIENT_ID, false); ide.setExtensionWindowOpen(LuaMcpWindow.SERVER_ID, false);
            return null;
        }).get();
        waitClient(client, () -> editor().getValue().equals(base), "Initial file failed to load");
        onClient(client, () -> { field().seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, base.indexOf("print(count)")); return null; }).get();
        connection.call("lua_save_script", object("name", name, "source", updated));
        waitClient(client, () -> editor().getValue().equals(updated), "Live MCP edit did not synchronize current editor");
        onClient(client, () -> {
            require(get(get(ide, "currentDocument"), "savedSource").equals(updated), "Synced file marked dirty");
            require(field().cursor() == updated.indexOf("print(count)"), "MCP update moved caret away from unchanged code");
            @SuppressWarnings("unchecked") var marks = (List<LuaAiAgent.Mark>) get(get(ide, "currentDocument"), "mcpMarks");
            require(marks.size() == 2 && marks.stream().noneMatch(m -> m.start() <= updated.indexOf("print(count)") && m.end() > updated.indexOf("print(count)")), "Multi-edit marks include unchanged middle code");
            for (float scale : new float[]{1F, 1.5F, 2F}) {
                set(ide, "codeScale", scale); LuaSyntaxHighlighter.setScale(editor(), scale);
                var rectangles = ide.aiRectangles(field()); require(!rectangles.isEmpty(), "MCP highlight absent at font scale " + scale);
                var rect = rectangles.getFirst(); int x = (rect.left() + rect.right()) / 2, y = (rect.top() + rect.bottom()) / 2;
                var graphics = new net.minecraft.client.gui.GuiGraphicsExtractor(client, new net.minecraft.client.renderer.state.gui.GuiRenderState(), x, y);
                var hover = LuaIdeScreen.class.getDeclaredMethod("extractAiHover", net.minecraft.client.gui.GuiGraphicsExtractor.class, net.minecraft.client.gui.components.MultilineTextField.class, int.class, int.class); hover.setAccessible(true);
                require((boolean) hover.invoke(ide, graphics, field(), x, y), "MCP hover tooltip missing");
                set(ide, "leftMouseHeld", true);
                require(!(boolean) hover.invoke(ide, graphics, field(), x, y), "MCP tooltip covers drag-selection"); set(ide, "leftMouseHeld", false);
            }
            require(ide.keyPressed(new KeyEvent(256, 0, 0)) && client.screen == ide, "Esc hovering MCP modification closed IDE");
            require(((List<?>) get(get(ide, "currentDocument"), "mcpMarks")).isEmpty(), "Esc left MCP backgrounds behind");
            set(ide, "codeScale", 1F); LuaSyntaxHighlighter.setScale(editor(), 1F);
            return null;
        }).get();
        // Background tab updates must not switch the currently selected file.
        String background = name + "_background";
        connection.call("lua_save_script", object("name", background, "source", "print('old')"));
        onClient(client, () -> { var open = LuaIdeScreen.class.getDeclaredMethod("openScript", String.class); open.setAccessible(true); open.invoke(ide, background); return null; }).get();
        waitClient(client, () -> editor().getValue().equals("print('old')"), "Background fixture load");
        onClient(client, () -> { var open = LuaIdeScreen.class.getDeclaredMethod("openScript", String.class); open.setAccessible(true); open.invoke(ide, name); return null; }).get();
        connection.call("lua_save_script", object("name", background, "source", "print('new')"));
        waitClient(client, () -> {
            var docs = (Map<?, ?>) get(ide, "openDocuments");
            return get(docs.get("lua:" + background), "source").equals("print('new')");
        }, "Background tab was not updated");
        onClient(client, () -> { require(get(get(ide, "currentDocument"), "name").equals(name) && editor().getValue().equals(updated), "Background synchronization switched active tab"); return null; }).get();
        // An MCP change over unsaved edits is applied right away (no dialog, the old text stays in undo) and the changed code is marked.
        String draft = updated + "-- unsaved\n", incoming = updated + "-- MCP\n";
        onClient(client, () -> { editor().setValue(draft); return null; }).get();
        connection.call("lua_save_script", object("name", name, "source", incoming));
        waitClient(client, () -> { try { return editor().getValue().equals(incoming); } catch (Exception failure) { return false; } }, "MCP change over an unsaved draft was not applied");
        onClient(client, () -> {
            require(client.screen == ide && !(client.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen), "A confirmation screen replaced the IDE");
            require(get(get(ide, "currentDocument"), "savedSource").equals(incoming), "Applied MCP change has a stale server baseline");
            var undo = LuaIdeScreen.class.getDeclaredMethod("undo"); undo.setAccessible(true); undo.invoke(ide);
            require(editor().getValue().equals(draft), "Applying MCP discarded the unsaved draft's undo");
            editor().setValue(incoming); return null;
        }).get();
    }
    private static net.minecraft.client.gui.components.MultiLineEditBox editor() throws Exception { return (net.minecraft.client.gui.components.MultiLineEditBox) get(ide, "editor"); }
    private static net.minecraft.client.gui.components.MultilineTextField field() throws Exception { return ((wiki.ica.xiaomiao.mcluaide.client.mixin.MultiLineEditBoxAccessor) editor()).mcLuaIde$getTextField(); }
    private static void waitClient(Minecraft client, Callable<Boolean> ready, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!onClient(client, ready).get(10, TimeUnit.SECONDS)) { if (System.nanoTime() >= deadline) throw new AssertionError(failure); Thread.sleep(20); }
        require(true, failure);
    }

    private static void verifyPermissions(MinecraftServer server, UUID owner) throws Exception {
        UUID guestId = UUID.randomUUID();
        var identity = new net.minecraft.server.players.NameAndId(guestId, "McpProbeGuest");
        onServer(server, () -> {
            var guest = new net.minecraft.server.level.ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(guestId, identity.name()), net.minecraft.server.level.ClientInformation.createDefault());
            boolean denied = false;
            try { LuaServerMcpService.clientCall(server, guest, "lua_list_scripts", new JsonObject()); } catch (IllegalStateException expected) { denied = true; }
            require(denied, "Non-admin gained server scripts through client MCP bridge");
            denied = false;
            try { LuaServerMcpService.configure(server, guest, object("enabled", true, "port", serverPort, "allowWrite", false, "allowExecution", false)); } catch (IllegalStateException expected) { denied = true; }
            require(denied, "Non-admin configured server MCP");
            server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(identity, net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER, false));
            var administrator = new net.minecraft.server.level.ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(guestId, identity.name()), net.minecraft.server.level.ClientInformation.createDefault());
            return LuaServerMcpService.configure(server, administrator, object("enabled", true, "port", serverPort, "allowWrite", false, "allowExecution", false));
        }).get(10, TimeUnit.SECONDS);
        try {
            var settings = server.submit(() -> LuaServerMcpService.status(server).settings()).get();
            try (Connection offlineOwner = new Connection(settings)) {
                require(!offlineOwner.call("mc_status", new JsonObject()).get("isError").getAsBoolean(), "Offline OP-4 binding not supported");
                server.submit(() -> server.getPlayerList().getOps().remove(identity)).get();
                require(offlineOwner.call("lua_list_scripts", new JsonObject()).get("isError").getAsBoolean(), "Revoked administrator still read scripts via HTTP");
            }
        } finally {
            onServer(server, () -> { server.getPlayerList().getOps().remove(identity); return LuaServerMcpService.configure(server, server.getPlayerList().getPlayer(owner), object("enabled", true, "port", serverPort, "allowWrite", true, "allowExecution", true)); }).get();
        }
    }

    private static final class Connection implements AutoCloseable {
        final LuaMcpSettings settings; final HttpClient client = HttpClient.newHttpClient(); final String session;
        Connection(LuaMcpSettings settings) throws Exception { this.settings = settings; var initialized = send("initialize", object("protocolVersion", LuaMcpServer.VERSION), null, 1); session = initialized.headers().firstValue("MCP-Session-Id").orElseThrow(); send("notifications/initialized", new JsonObject(), session, null); }
        JsonObject call(String name, JsonObject args) throws Exception {
            JsonObject response = JsonParser.parseString(send("tools/call", object("name", name, "arguments", args), session, 2).body()).getAsJsonObject();
            require(response.has("result"), "MCP tool returned a protocol error"); return response.getAsJsonObject("result");
        }
        HttpResponse<String> send(String method, JsonObject params, String session, Integer id) throws Exception {
            JsonObject body = object("jsonrpc", "2.0", "method", method, "params", params); if (id != null) body.addProperty("id", id);
            var request = HttpRequest.newBuilder(URI.create(settings.url())).timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + settings.token())
                    .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json");
            if (session != null) request.header("MCP-Session-Id", session).header("MCP-Protocol-Version", LuaMcpServer.VERSION);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { client.close(); }
    }
    private static int freePort() throws Exception { try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static JsonObject object(Object... fields) { JsonObject json = new JsonObject(); for (int i = 0; i < fields.length; i += 2) json.add(fields[i].toString(), LuaMcpServer.JSON.toJsonTree(fields[i+1])); return json; }
    private static <T> CompletableFuture<T> onClient(Minecraft client, Callable<T> call) { var future = new CompletableFuture<T>(); client.execute(() -> { try { future.complete(call.call()); } catch (Exception error) { future.completeExceptionally(error); } }); return future; }
    private static <T> CompletableFuture<T> onServer(MinecraftServer server, Callable<T> call) { var future = new CompletableFuture<T>(); server.execute(() -> { try { future.complete(call.call()); } catch (Exception error) { future.completeExceptionally(error); } }); return future; }
    private static <T> CompletableFuture<T> onClientFuture(Minecraft client, Callable<CompletableFuture<T>> call) { return onClient(client, call).thenCompose(value -> value); }
    @SuppressWarnings("unchecked") private static LuaExtensionWindow frame(String id) throws Exception { return ((Map<?, LuaExtensionWindow>)get(ide, "extensionWindows")).values().stream().filter(frame -> frame.id().equals(id)).findFirst().orElseThrow(); }
    private static void click(LuaExtensionWindow frame, int x, int y) throws Exception {
        var panel = LuaExtensionWindow.class.getDeclaredMethod("panelX"); panel.setAccessible(true);
        require(frame.mouseClicked(mouse((int)panel.invoke(frame) + 6 + x, (int)get(frame, "y") + 32 + y), false), "MCP frame content click failed"); frame.mouseReleased(mouse(0, 0));
    }
    private static MouseButtonEvent mouse(double x, double y) { return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)); }
    private static Object get(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
