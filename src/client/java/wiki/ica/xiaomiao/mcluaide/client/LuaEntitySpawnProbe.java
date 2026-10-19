package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.MinecraftServer;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Opt-in real Lua/server check in a fresh build/entity-spawn-probe save, never an existing world. */
final class LuaEntitySpawnProbe {
    private static int stage;
    private static long started;
    private static CompletableFuture<Void> task;

    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.createPersistent(
                        net.minecraft.resources.Identifier.parse("mc_lua_ide:probe_profile"),
                        com.mojang.serialization.Codec.STRING.fieldOf("model_id").codec());
                net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.createPersistent(
                        net.minecraft.resources.Identifier.parse("mc_lua_ide:probe_other"), com.mojang.serialization.Codec.INT);
                net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.create(
                        net.minecraft.resources.Identifier.parse("mc_lua_ide:probe_transient"));
                started = System.nanoTime(); stage = 1;
                client.options.renderDistance().set(3); client.options.simulationDistance().set(6);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Probe creation cancelled"); });
                return;
            }
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(180))
                throw new AssertionError("Entity spawn probe timed out at stage " + stage);
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("entity_spawn_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
                stage = 2; method.invoke(create); return;
            }
            if (stage == 2) {
                if (client.player == null || client.getSingleplayerServer() == null || !ScriptManager.isServerAvailable()) return;
                client.setScreen(null);
                MinecraftServer server = client.getSingleplayerServer(); UUID owner = client.player.getUUID();
                task = CompletableFuture.runAsync(() -> {
                    try {
                        verify(server, owner, false); verify(server, owner, true);
                        verifyAttachments(server, owner, false); verifyAttachments(server, owner, true);
						verifyDroppedItems(server, owner, false); verifyDroppedItems(server, owner, true);
                        // Ensure the second dimension has a loaded target chunk for the rejection check.
                        server.submit(() -> {
                            var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
                            nether.setChunkForced(0, 0, true);
                            nether.getChunk(0, 0);
                        }).get(20, TimeUnit.SECONDS);
                        try {
                            verifyRiding(server, owner, false); verifyRiding(server, owner, true);
                            verifyTeams(server, owner, false); verifyTeams(server, owner, true);
                        }
                        finally {
                            server.submit(() -> server.getLevel(net.minecraft.world.level.Level.NETHER).setChunkForced(0, 0, false))
                                    .get(20, TimeUnit.SECONDS);
                        }
                        verifyPlayerDeath(server, owner);
                    }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
                stage = 3; return;
            }
            if (stage == 3 && task.isDone()) {
                task.join(); stage = 99;
                System.out.println("Entity spawn UUID real game probe passed: normal/debug, unique UUIDs, lookup, mutation, living/display components, optional dimension, removal and invalid types");
                System.out.println("Entity attachment real game probe passed: normal/debug, native persistent attachments, model_id codecs, JSON/Lua inputs, snapshots, equipment/health/position/UUID preservation, missing/transient/type guards");
                System.out.println("Entity riding real game probe passed: normal/debug, native passenger/vehicle UUIDs, repeat, switching, capacity/cooldown force, stacks, dismount, player passenger, self/cycle/dimension/UUID guards");
                System.out.println("Player death real game probe passed: dedicated/generic listeners, native player/mob deaths, payload snapshots, exactly once and off");
				System.out.println("Dropped item entity editor probe passed: dynamic NBT defaults, nested Item schema/ID hints, native Lua spawn, item components, Health/Age/PickupDelay/Owner/Thrower, position/UUID preservation and invalid fields/types");
                System.out.println("Team real Lua/server probe passed: normal/debug, player UUID/name, mobs, dropped items, displays, armor stands, vehicles, projectiles, cross-dimension entities, native membership, switching, leave, virtual/unloaded UUID, invalid team and selector guards");
                client.stop();
            }
        } catch (Exception | AssertionError failure) {
            int failed = stage; stage = 99; client.stop();
            throw new IllegalStateException("Entity spawn UUID probe failed at stage " + failed, failure);
        }
    }

    private static void verify(MinecraftServer server, UUID owner, boolean debug) throws Exception {
        String source = """
                local player = MC.Player.position('%s')
                local first = MC.Entity.spawn('minecraft:pig', player.x, player.y + 1, player.z)
                assert(type(first) == 'string' and #first == 36 and string.match(first, '^[0-9a-f%%-]+$'))
                assert(MC.Entity.exists(first) and MC.Entity.get(first).uuid == first)
                MC.Entity.setName(first, 'UUID probe')
                MC.Entity.setGlowing(first, true)
                local info = MC.Entity.get(first)
                assert(info.type == 'minecraft:pig' and info.name == 'UUID probe' and info.glowing)
                local second = MC.Entity.spawn('minecraft:pig', player.x, player.y + 1, player.z, player.dimension, {health = 6})
                assert(type(second) == 'string' and second ~= first)
                assert(MC.Entity.get(second).uuid == second and MC.Entity.get(second).health == 6)
                local display = MC.Entity.spawn('minecraft:text_display', player.x, player.y + 2, player.z, nil,
                    {text = {text = 'UUID display', color = 'gold'}, billboard = 'center'})
                assert(type(display) == 'string' and display ~= first and display ~= second)
                assert(MC.Entity.get(display).uuid == display and MC.Entity.get(display).type == 'minecraft:text_display')
                assert(not pcall(function() MC.Entity.spawn('test:missing_entity', player.x, player.y, player.z) end))
                assert(not pcall(function() MC.Entity.spawn('minecraft:player', player.x, player.y, player.z) end))
                MC.Entity.remove(first); MC.Entity.remove(second); MC.Entity.remove(display)
                assert(not MC.Entity.exists(first) and not MC.Entity.exists(second) and not MC.Entity.exists(display))
                print('spawn-uuid-ok')
                """.formatted(owner);
        verifySource(server, owner, debug, source, "spawn-uuid-ok");
    }

    private static void verifyAttachments(MinecraftServer server, UUID owner, boolean debug) throws Exception {
        String source = """
                local p = MC.Player.position('%s')
                local id = MC.Entity.spawn('minecraft:pig', p.x, p.y+4, p.z, p.dimension, {
                    ['minecraft:custom_name'] = {text='商人'}, health=4, invulnerable=true, noGravity=true,
                    equipment={head={id='minecraft:cake',count=1}},
                    ['fabric:attachments']={
                        ['mc_lua_ide:probe_profile']={model_id='geckolib:zhiban_hanfu'},
                        ['mc_lua_ide:probe_other']=37
                    }
                })
                local d = MC.Entity.components(id)
                assert(d.uuid==id and d.health==4 and d.invulnerable and d.noGravity)
                assert(d.equipment.head.id=='minecraft:cake' and d.equipment.head.count==1)
                assert(d.entityData['fabric:attachments']['mc_lua_ide:probe_profile'].model_id=='geckolib:zhiban_hanfu')
                assert(d.entityData['fabric:attachments']['mc_lua_ide:probe_other']==37)
                assert(MC.Entity.get(id).name=='商人')
                MC.Entity.remove(id)
                local json = MC.Entity.spawn('minecraft:pig', p.x,p.y+4,p.z,p.dimension,
                    '{"health":6,"fabric:attachments":{"mc_lua_ide:probe_profile":{"model_id":"json-model"}}}')
                local saved = MC.Entity.components(json)
                assert(saved.health==6 and saved.entityData['fabric:attachments']['mc_lua_ide:probe_profile'].model_id=='json-model')
                MC.Entity.remove(json)
                local empty = MC.Entity.spawn('minecraft:pig',p.x,p.y+4,p.z,p.dimension,{['fabric:attachments']={}})
                assert(type(empty)=='string'); MC.Entity.remove(empty)
                local function rejected(config, expected)
                    local ok, reason=pcall(MC.Entity.spawn,'minecraft:pig',p.x,p.y+4,p.z,p.dimension,config)
                    assert(not ok and string.find(tostring(reason),expected,1,true),tostring(reason))
                end
                rejected({['fabric:attachments']=false},'对象')
                rejected({['fabric:attachments']={['missing:attachment']={}}},'未知 Fabric 附件')
                rejected({['fabric:attachments']={['mc_lua_ide:probe_transient']={}}},'持久化')
                rejected({['fabric:attachments']={['mc_lua_ide:probe_profile']={model_id=7}}},'数据无效')
                rejected({['fabric:attachments']={['mc_lua_ide:probe_other']='bad'}},'数据无效')
                print('entity-attachments-ok')
                """.formatted(owner);
        verifySource(server, owner, debug, source, "entity-attachments-ok");
    }

    private static void verifyRiding(MinecraftServer server, UUID owner, boolean debug) throws Exception {
        String source = """
                local playerUuid = '%s'
                local p = MC.Player.position(playerUuid)
                local passenger = MC.Entity.spawn('minecraft:pig', p.x, p.y + 4, p.z)
                local vehicle = MC.Entity.spawn('minecraft:cow', p.x, p.y + 4, p.z)
                local root = MC.Entity.spawn('minecraft:pig', p.x, p.y + 4, p.z)
                assert(passenger and vehicle and root)
                MC.Entity.setGravity(passenger, false); MC.Entity.setGravity(vehicle, false); MC.Entity.setGravity(root, false)
                MC.Entity.setInvulnerable(passenger, true); MC.Entity.setInvulnerable(vehicle, true); MC.Entity.setInvulnerable(root, true)
                assert(MC.Entity.get(passenger).vehicleUuid == nil and #MC.Entity.get(vehicle).passengerUuids == 0)
                assert(MC.Entity.ride(passenger, vehicle))
                assert(MC.Entity.get(passenger).vehicleUuid == vehicle and MC.Entity.get(vehicle).passengerUuids[1] == passenger)
                assert(MC.Entity.ride(passenger, vehicle, false)) -- Repeat is idempotent.
                assert(not MC.Entity.ride(root, vehicle, false)) -- Occupied ordinary seat.
                assert(MC.Entity.get(root).vehicleUuid == nil and #MC.Entity.get(vehicle).passengerUuids == 1)
                assert(MC.Entity.ride(root, vehicle, true) and #MC.Entity.get(vehicle).passengerUuids == 2)
                assert(MC.Entity.dismount(root) and not MC.Entity.dismount(root))
                assert(MC.Entity.ride(passenger, root)) -- Switch from the original vehicle.
                assert(MC.Entity.get(passenger).vehicleUuid == root and #MC.Entity.get(vehicle).passengerUuids == 0)
                assert(MC.Entity.dismount(passenger) and not MC.Entity.dismount(passenger))
                assert(not MC.Entity.ride(passenger, vehicle, false)) -- Fresh dismount cooldown.
                assert(MC.Entity.ride(passenger, vehicle, nil)) -- Default force bypasses cooldown.
                assert(MC.Entity.ride(vehicle, root))
                assert(MC.Entity.get(vehicle).vehicleUuid == root and MC.Entity.get(root).passengerUuids[1] == vehicle)
                assert(#MC.Entity.get(root).passengerUuids == 1) -- Direct, not recursive, children.
                local function rejected(action, text)
                    local ok, problem = pcall(action)
                    assert(not ok and string.find(tostring(problem), text, 1, true), tostring(problem))
                end
                rejected(function() MC.Entity.ride(passenger, passenger) end, '不能骑乘自己')
                rejected(function() MC.Entity.ride(root, passenger) end, '循环骑乘')
                rejected(function() MC.Entity.ride(passenger, playerUuid) end, '不能作为坐骑')
                rejected(function() MC.Entity.ride(passenger, 'minecraft:cow') end, 'UUID')
                local missingRide, missingReason = MC.Entity.ride(passenger, '00000000-0000-0000-0000-000000000000')
                assert(missingRide == nil and string.find(missingReason, '实体不存在', 1, true))
                local other = MC.Entity.spawn('minecraft:text_display', 0, 80, 0, 'minecraft:the_nether',
                    {text = {text = 'Dimension probe'}})
                assert(other)
                -- Tracking of a newly loaded dimension may become visible on the following server tick.
                for attempt = 1, 100 do
                    if MC.Entity.exists(other) then break end
                    MC.sleep(50)
                end
                assert(MC.Entity.exists(other), 'Cross-dimension fixture did not become tracked: ' .. other)
                assert(MC.Entity.exists(passenger), 'Passenger disappeared before dimension test: ' .. passenger)
                rejected(function() MC.Entity.ride(passenger, other) end, '同一个维度')
                assert(MC.Entity.get(passenger).vehicleUuid == vehicle and MC.Entity.get(vehicle).vehicleUuid == root)
                assert(MC.Entity.ride(playerUuid, root)) -- Players can be native passengers.
                assert(MC.Entity.get(playerUuid).vehicleUuid == root)
                assert(MC.Entity.dismount(playerUuid) and MC.Entity.get(playerUuid).vehicleUuid == nil)
                assert(MC.Entity.dismount(vehicle))
                assert(MC.Entity.get(passenger).vehicleUuid == vehicle and #MC.Entity.get(root).passengerUuids == 0)
                assert(MC.Entity.dismount(passenger))
                MC.Entity.remove(passenger); MC.Entity.remove(vehicle); MC.Entity.remove(root); MC.Entity.remove(other)
                local absent, warning = MC.Entity.dismount(passenger)
                assert(absent == nil and string.find(warning, '实体不存在', 1, true))
                print('ride-ok')
                """.formatted(owner);
        verifySource(server, owner, debug, source, "ride-ok");
    }

    private static void verifyPlayerDeath(MinecraftServer server, UUID owner) throws Exception {
        String source = """
                local playerUuid = '%s'
                local named, generic, mobs, allPlayers = 0, 0, 0, 0
                local position = MC.Player.position(playerUuid)
                local function checkDeath(e)
                    assert(e.type == 'playerDied' and e.playerUuid == playerUuid)
                    assert(type(e.playerName) == 'string' and #e.playerName > 0)
                    assert(e.dimension == position.dimension)
                    assert(type(e.x) == 'number' and type(e.y) == 'number' and type(e.z) == 'number')
					assert(e.x == position.x and e.y == position.y and e.z == position.z, 'Death coordinates differ from death position')
                    assert(type(e.damageType) == 'string' and type(e.deathMessage) == 'string' and #e.deathMessage > 0)
                    assert(e.attackerUuid == nil and e.directUuid == nil)
                end
                local entityId = MC.Event.onEntityDied(function(e)
                    if e.entityType == 'minecraft:player' then
                        assert(e.entityUuid == playerUuid)
                        allPlayers = allPlayers + 1
                    else mobs = mobs + 1 end
                end)
                local namedId = MC.Event.onPlayerDied(function(e) checkDeath(e); named = named + 1 end)
                local genericId = MC.Event.on('playerDied', function(e) checkDeath(e); generic = generic + 1 end)
                local found = false
                for _, name in ipairs(MC.Event.names()) do if name == 'playerDied' then found = true end end
                assert(found)
                local mob = MC.Entity.spawn('minecraft:pig', position.x, position.y + 1, position.z)
                MC.Entity.kill(mob)
                for attempt = 1, 100 do if mobs > 0 then break end; MC.sleep(10) end
                assert(mobs == 1 and named == 0 and generic == 0, 'Non-player death triggered player listeners')
				position = MC.Player.position(playerUuid)
                MC.Entity.kill(playerUuid)
                for attempt = 1, 100 do if named > 0 and generic > 0 and allPlayers > 0 then break end; MC.sleep(10) end
                MC.sleep(150)
                assert(named == 1 and generic == 1 and allPlayers == 1, 'Missing or duplicate death callbacks')
                assert(MC.Event.off(namedId) and MC.Event.off(genericId) and MC.Event.off(entityId))
                print('player-death-ok')
                """.formatted(owner);
        if (!LuaCodeAnalyzer.analyze("player_death", source).valid())
            throw new AssertionError("Player death example incorrectly rejected by analyzer");
        if (!LuaCodeAnalyzer.analyze("player_death", LuaApiDocumentation.usageTemplate("MC.Event.onPlayerDied")).valid())
            throw new AssertionError("Player death documentation template rejected");
        verifySource(server, owner, false, source, "player-death-ok");
    }

	private static void verifyDroppedItems(MinecraftServer server, UUID owner, boolean debug) throws Exception {
		server.submit(() -> {
			try {
				Class<?> catalogType = Class.forName("wiki.ica.xiaomiao.mcluaide.server.LuaComponentEditorCatalog");
				Method describe = catalogType.getDeclaredMethod("describe", MinecraftServer.class, String.class, String.class);
				describe.setAccessible(true);
				LuaComponentEditorData catalog = LuaComponentEditorData.decode((String) describe.invoke(null, server, "entity", "minecraft:item"));
				for (String field : List.of("Item", "Health", "Age", "PickupDelay", "Owner", "Thrower"))
					if (catalog.components().stream().noneMatch(info -> info.id().equals(field))) throw new AssertionError("Missing dropped item field " + field);
				var stack = catalog.components().stream().filter(info -> info.id().equals("Item")).findFirst().orElseThrow().schema();
				if (!stack.fields().get("id").registry().equals("minecraft:item") || !stack.fields().containsKey("components"))
					throw new AssertionError("Dropped stack lost item ID search or nested components");
				if (catalog.components().stream().anyMatch(info -> info.id().equals("health") || info.id().equals("equipment")
						|| info.id().equals("minecraft:item_model"))) throw new AssertionError("Item entity leaked living/item fields");
				var entity = net.minecraft.world.entity.EntityType.ITEM.create(server.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess());
				entity.saveWithoutId(out);
				var defaults = net.minecraft.nbt.NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, out.buildResult()).getAsJsonObject();
				for (String field : List.of("Health", "Age", "PickupDelay")) {
					var entry = catalog.components().stream().filter(info -> info.id().equals(field)).findFirst().orElseThrow();
					if (!entry.defaultValue().equals(defaults.get(field))) throw new AssertionError("Editor did not read runtime default " + field);
				}
				return true;
			} catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
		}).get(10, TimeUnit.SECONDS);
		String source = """
				local playerUuid = '%s'
				local p = MC.Player.position(playerUuid)
				local id = MC.Entity.spawn('minecraft:item', p.x, p.y + 3, p.z, p.dimension, {
				    Item = {id = 'minecraft:diamond', count = 3, components = {
				        ['minecraft:custom_name'] = {text = '掉落物编辑测试'},
				        ['minecraft:enchantments'] = {['minecraft:unbreaking'] = 2}
				    }},
				    Health = 4, Age = -32768, PickupDelay = 32767,
				    Owner = playerUuid, Thrower = playerUuid, noGravity = true, glowing = true
				})
				assert(MC.Entity.exists(id))
				print('drop-item-id=' .. id)
				local function rejected(config)
				    local ok = pcall(function() MC.Entity.spawn('minecraft:item', p.x, p.y + 3, p.z, p.dimension, config) end)
				    assert(not ok, 'Invalid item config accepted')
				end
				rejected({Health = 'invalid'})
				rejected({Health = 1.5})
				rejected({Age = 32768})
				rejected({PickupDelay = -1})
				rejected({Owner = 'bad-uuid'})
				rejected({Thrower = 123})
				rejected({Item = {id = 'missing:bad_item', count = 1}})
				rejected({Item = {id = 'minecraft:air', count = 1}})
				local ok = pcall(function() MC.Entity.spawn('minecraft:pig', p.x, p.y, p.z, p.dimension, {Health = 5}) end)
				assert(not ok, 'Dropped Health accepted on living entity')
				print('dropped-item-ok')
				""".formatted(owner);
		List<String> output = verifySource(server, owner, debug, source, "dropped-item-ok");
		String line = output.stream().filter(text -> text.contains("drop-item-id=")).findFirst().orElseThrow();
		UUID uuid = UUID.fromString(line.substring(line.indexOf("drop-item-id=") + 13).strip());
		server.submit(() -> {
			var entity = server.getPlayerList().getPlayer(owner).level().getEntity(uuid);
			if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity item)) throw new AssertionError("Dropped item disappeared");
			try {
				if (!item.getItem().is(net.minecraft.world.item.Items.DIAMOND) || item.getItem().getCount() != 3
						|| !item.getItem().getHoverName().getString().equals("掉落物编辑测试")) throw new AssertionError("Nested item components not applied");
				var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess());
				item.saveWithoutId(out);
				var saved = net.minecraft.nbt.NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, out.buildResult()).getAsJsonObject();
				if (saved.get("Health").getAsInt() != 4 || saved.get("Age").getAsInt() != -32768
						|| saved.get("PickupDelay").getAsInt() != 32767 || !item.isNoGravity() || !item.isCurrentlyGlowing())
					throw new AssertionError("Dropped item NBT or flags not applied: " + saved);
				var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
				for (String key : List.of("Owner", "Thrower"))
					if (!net.minecraft.core.UUIDUtil.CODEC.parse(ops, saved.get(key)).getOrThrow().equals(owner)) throw new AssertionError("Wrong " + key);
				return true;
			} finally { entity.discard(); }
		}).get(10, TimeUnit.SECONDS);
	}

    private static void verifyTeams(MinecraftServer server, UUID owner, boolean debug) throws Exception {
        String red = "team_probe_red", blue = "team_probe_blue";
        var originalTeam = server.submit(() -> server.getPlayerList().getPlayer(owner).getTeam()).get(10, TimeUnit.SECONDS);
        List<UUID> entities = new java.util.ArrayList<>();
        try {
            server.submit(() -> {
                var player = server.getPlayerList().getPlayer(owner);
                var level = player.level();
                for (String type : List.of("pig", "armor_stand", "text_display", "block_display", "item_display", "oak_boat", "arrow")) {
                    var entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getValue(net.minecraft.resources.Identifier.withDefaultNamespace(type));
                    var entity = entityType.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    if (entity == null) throw new AssertionError("Team fixture unavailable: " + type);
                    entity.setPos(player.getX(), player.getY() + 3, player.getZ());
                    entity.setNoGravity(true); entity.setInvulnerable(true);
                    if (!level.addFreshEntity(entity)) throw new AssertionError("Team fixture rejected: " + type);
                    entities.add(entity.getUUID());
                }
                var item = new net.minecraft.world.entity.item.ItemEntity(level, player.getX(), player.getY() + 5,
                        player.getZ(), new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
                item.setNoGravity(true); item.setInvulnerable(true); item.setNeverPickUp();
                if (!level.addFreshEntity(item)) throw new AssertionError("Item fixture rejected");
                entities.add(item.getUUID());
                var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
                var mob = net.minecraft.world.entity.EntityType.PIG.create(nether, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                mob.setPos(0, 100, 0); mob.setNoGravity(true); mob.setInvulnerable(true);
                if (!nether.addFreshEntity(mob)) throw new AssertionError("Nether fixture rejected");
                entities.add(mob.getUUID());
                return true;
            }).get(10, TimeUnit.SECONDS);
            String members = "'" + owner + "', " + entities.stream().map(uuid -> "'" + uuid + "'").collect(java.util.stream.Collectors.joining(", "));
            String source = """
                    local red, blue = 'team_probe_red', 'team_probe_blue'
                    MC.Team.create(red); MC.Team.create(blue)
                    MC.Team.color(red, 'red'); MC.Team.friendlyFire(red, false)
                    MC.Team.prefix(red, {text='[测试]'}); MC.Team.suffix(red, '完成')
                    local members = {%s}
                    for _, member in ipairs(members) do
                        assert(not MC.Team.has(red, member))
                        assert(not MC.Team.has('nonexistent_team', member))
                        assert(MC.Team.join(red, member) and MC.Team.join(red, member))
                        assert(MC.Team.has(red, member) and not MC.Team.has(blue, member))
                        assert(MC.Team.join(blue, member))
                        assert(not MC.Team.has(red, member) and MC.Team.has(blue, member))
                        assert(MC.Team.leave(member) and not MC.Team.leave(member))
                        assert(not MC.Team.has(blue, member))
                        MC.Team.join(red, member)
                    end
                    local playerName = MC.Player.name('%s')
                    assert(MC.Team.has(red, playerName))
                    assert(MC.Team.leave(playerName) and not MC.Team.has(red, '%s'))
                    MC.Team.join(red, '%s')
                    for _, member in ipairs({'virtual_member', '00000000-0000-0000-0000-000000000001'}) do
                        assert(MC.Team.join(red, member) and MC.Team.has(red, member))
                        assert(MC.Team.leave(member) and not MC.Team.has(red, member))
                    end
                    assert(not pcall(function() MC.Team.join('nonexistent_team', '%s') end))
                    assert(not pcall(function() MC.Team.join(red, '@e') end))
                    assert(not pcall(function() MC.Team.has(red, {}) end))
                    print('team-all-entities-ok')
                    """.formatted(members, owner, owner, owner, owner);
            // This regression intentionally passes a table to a string parameter inside pcall.
            // Documentation examples are checked separately by argumentCompletionSmoke.
            verifySource(server, owner, debug, source, "team-all-entities-ok");
            server.submit(() -> {
                var team = server.getScoreboard().getPlayerTeam(red);
                var player = server.getPlayerList().getPlayer(owner);
                if (player.getTeam() != team || !team.getPlayers().contains(player.getScoreboardName())
                        || team.getPlayers().contains(owner.toString())) throw new AssertionError("Player UUID was not resolved to native scoreboard name");
                for (UUID uuid : entities) {
                    net.minecraft.world.entity.Entity found = null;
                    for (var level : server.getAllLevels()) {
                        found = level.getEntity(uuid); if (found != null) break;
                    }
                    if (found == null || found.getTeam() != team || !team.getPlayers().contains(found.getScoreboardName()))
                        throw new AssertionError("Native entity team membership missing: " + uuid);
                }
                if (team.isAllowFriendlyFire() || team.getColor() != net.minecraft.ChatFormatting.RED
                        || !team.getPlayerPrefix().getString().equals("[测试]") || !team.getPlayerSuffix().getString().equals("完成"))
                    throw new AssertionError("Team configuration did not apply to entity team");
                return true;
            }).get(10, TimeUnit.SECONDS);
        } finally {
            server.submit(() -> {
                for (var level : server.getAllLevels()) for (UUID uuid : entities) {
                    var entity = level.getEntity(uuid); if (entity != null) entity.discard();
                }
                for (String name : List.of(red, blue)) {
                    var team = server.getScoreboard().getPlayerTeam(name);
                    if (team != null) server.getScoreboard().removePlayerTeam(team);
                }
                if (originalTeam != null) server.getScoreboard().addPlayerToTeam(server.getPlayerList().getPlayer(owner).getScoreboardName(), originalTeam);
                return true;
            }).get(10, TimeUnit.SECONDS);
        }
    }

    private static List<String> verifySource(MinecraftServer server, UUID owner, boolean debug, String source, String marker) throws Exception {
        List<String> output = new CopyOnWriteArrayList<>();
        CompletableFuture<ServerScriptManager.ExecutionResult> completed = new CompletableFuture<>();
        server.execute(() -> {
            try { ServerScriptManager.start(server, owner, "entity_spawn_probe", source, debug, output::add, completed::complete); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        });
        var result = completed.get(20, TimeUnit.SECONDS);
        if (result.error() != null || result.stopped() || output.stream().noneMatch(line -> line.contains(marker)))
            throw new AssertionError("Entity verification failed (" + marker + ", debug=" + debug + "): " + result + " " + output);
		return List.copyOf(output);
    }
}
