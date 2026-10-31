package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;
import wiki.ica.xiaomiao.mcluaide.server.LuaVirtualChestMenu;
import java.util.*;
import java.util.concurrent.*;

/** Isolated vanilla-protocol/menu-click integration, never opens an existing world or contacts AI. */
final class LuaContainerProbe {
    private static int stage, checks;
    private static long started;
    private static CompletableFuture<Void> task;
    static void tick(Minecraft client) {
        if (stage == 99 || client.getOverlay() != null) return;
        try {
            if (stage == 0) {
                stage = 1; started = System.nanoTime();
                client.options.renderDistance().set(2); client.options.simulationDistance().set(6);
                CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Creation cancelled"); }); return;
            }
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(180)) throw new AssertionError("Probe timeout");
            if (stage == 1) {
                if (!(client.screen instanceof CreateWorldScreen create)) return;
                create.getUiState().setName("container_" + System.currentTimeMillis());
                create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
                var method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
                stage = 2; method.invoke(create); return;
            }
            if (stage == 2) {
                if (client.player == null || client.getSingleplayerServer() == null || !ScriptManager.isServerAvailable()) return;
                MinecraftServer server = client.getSingleplayerServer(); UUID owner = client.player.getUUID();
                task = CompletableFuture.runAsync(() -> {
                    try {
                        verify(server, owner, false); verify(server, owner, true);
                        verifyComponentReads(server, owner, false); verifyComponentReads(server, owner, true);
                        verifyItemFilters(server, owner, false); verifyItemFilters(server, owner, true);
                        verifyShop(server, owner);
                    }
                    catch (Exception e) { throw new CompletionException(e); }
                }); stage = 3; return;
            }
            if (stage == 3 && task.isDone()) {
                task.join(); LuaVariableCopyProbe.verify(client); stage = 99;
                System.out.println("Container real game probe passed: " + checks + " checks; vanilla packets, virtual chest/cursor APIs/components, 1-6 rows, nil/real coordinates, shift/swap/cursor deltas, readonly exploit routes, atomic trade, actual Bed Wars shop, entity/player equipment and entity/block component snapshots; normal/debug");
                client.stop();
            }
        } catch (Exception | AssertionError error) {
            stage = 99; client.stop(); throw new IllegalStateException("Container integration probe failed", error);
        }
    }
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
    private record Run(List<String> output, CompletableFuture<ServerScriptManager.ExecutionResult> result) { }
    private static Run start(MinecraftServer server, UUID owner, String source, boolean debug) {
        List<String> output = new CopyOnWriteArrayList<>(); var result = new CompletableFuture<ServerScriptManager.ExecutionResult>();
        server.execute(() -> {
            try { ServerScriptManager.start(server, owner, "container_probe", source, debug, output::add, result::complete); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        }); return new Run(output, result);
    }
    private static void await(Run run, String marker) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (run.output.stream().noneMatch(line -> line.startsWith(marker))) {
            if (run.result.isDone() || System.nanoTime() > until) throw new AssertionError("Missing " + marker + ": " + (run.result.isDone() ? run.result.join() : "running") + " " + run.output);
            Thread.sleep(10);
        }
        checks++;
    }
    private static void click(ServerPlayer player, int slot, int button, ContainerInput input) {
        var menu = player.containerMenu;
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                (short)slot, (byte)button, input, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
    }
    private static void verify(MinecraftServer server, UUID uuid, boolean debug) throws Exception {
        BlockPos pos = server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            p.closeContainer(); p.getInventory().clearContent(); p.containerMenu.setCarried(ItemStack.EMPTY);
            BlockPos at = p.blockPosition().offset(2, 1, 2);
            server.overworld().setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity)server.overworld().getBlockEntity(at)).setItem(0, new ItemStack(Items.EMERALD, 6));
            return at;
        }).get(10, TimeUnit.SECONDS);
        String source = """
                local uuid = '%s'
                local phase, finished = 0, false
                local comps = {['minecraft:custom_name']={text='Probe',color='gold'}}
                for rows = 1, 6 do
                    local id = MC.Player.openChest(uuid, 'Rows '..rows, {{id='minecraft:diamond',count=8,components=comps}}, rows)
                    assert(type(id)=='number' and MC.Player.menu(uuid).type=='minecraft:generic_9x'..rows)
                end
                local id = MC.Player.menu(uuid).containerId
                assert(MC.Player.menu(uuid).x == nil and MC.Player.menu(uuid).virtual)
                assert(MC.Player.menuItem(uuid, 0, id).components['minecraft:custom_name'].text == 'Probe')
                local skipped, reason = MC.Player.setMenuItem(uuid,0,'minecraft:emerald',1,nil,id+999)
                assert(skipped==nil and reason and MC.Player.menuItem(uuid,0,id).id=='minecraft:diamond')
                assert(not pcall(function() MC.Player.openChest(uuid,'bad',{{id='invalid:no_item'}},3) end))
                assert(MC.Player.menu(uuid).containerId==id)
                assert(not pcall(function() MC.Player.openChest(uuid,'bad',{},7) end))
                assert(not pcall(function() MC.Player.setMenuItem(uuid,999,'minecraft:stone') end))
                MC.Player.setCarried(uuid,'minecraft:emerald',3,comps,id)
                assert(MC.Player.carried(uuid).count==3 and MC.Player.carried(uuid).components['minecraft:custom_name'].text=='Probe')
                MC.Player.setCarried(uuid,nil,nil,nil,id)
                assert(MC.Player.carried(uuid).empty)
                MC.Player.setMenuItem(uuid,0,'minecraft:diamond',8,comps,id)
                -- A full inventory cannot charge 4 from a stack of 8 and then lose the reward.
                for i=0,35 do MC.Item.setSlot(uuid,i,'minecraft:dirt',64) end
                MC.Item.setSlot(uuid,0,'minecraft:iron_ingot',8)
                local ok, why = MC.Item.trade(uuid,{{id='minecraft:iron_ingot',count=4}},{{id='minecraft:white_wool',count=16}},id)
                assert(ok==false and why and MC.Item.getSlot(uuid,0).count==8)
                assert(MC.Item.trade(uuid,{{id='minecraft:iron_ingot',count=8}},{{id='minecraft:white_wool',count=16}},id))
                assert(MC.Item.getSlot(uuid,0).id=='minecraft:white_wool' and MC.Item.getSlot(uuid,0).count==16)
                assert(not MC.Item.trade(uuid,{{id='minecraft:emerald',count=999}},{{id='minecraft:stone',count=1}},id))
                for i=0,35 do MC.Item.clearSlot(uuid,i) end
                local function observe(e)
                    assert(type(e.playerUuid)=='string' and type(e.count)=='number' and type(e.itemComponents)=='table')
                    assert(e.item.id==e.itemId and e.item.count==e.count and e.containerId~=nil)
                    if e.virtual or e.containerType=='minecraft:inventory' then assert(e.x==nil and e.y==nil and e.z==nil) end
                    if not e.virtual and e.slotArea=='container' then assert(e.x==%d and e.y==%d and e.z==%d) end
                    print(e.type..':'..e.slotId..':'..e.count)
                end
                MC.Event.onContainerItemTaken(observe)
                MC.Event.onContainerItemPlaced(observe)
                MC.Event.onContainerItemToCursor(observe)
                MC.Event.onInventoryItemToCursor(observe)
                MC.Event.onContainerClicked(function(e)
                    if e.slotId==26 and phase==0 then
                        phase=1
                        MC.Player.setCarried(uuid,nil)
                        MC.Player.openChest(uuid,'Readonly',{{id='minecraft:diamond',count=8}},3,true)
                        print('readonly-ready')
                    elseif e.slotId==26 and phase==1 then
                        phase=2
                        MC.Player.openBlock(uuid,%d,%d,%d)
                        print('physical-ready')
                    elseif e.slotId==26 and phase==2 then
                        phase=3
                        MC.Player.setCarried(uuid,nil)
                        MC.Player.closeMenu(uuid)
                        MC.Item.setSlot(uuid,0,'minecraft:gold_ingot',5)
                        print('inventory-ready')
                    elseif e.slotId==0 and phase==3 then
                        finished=true
                    end
                end)
                MC.Player.openChest(uuid,'Mutable',{{id='minecraft:diamond',count=8,components=comps}},3,false)
                print('mutable-ready')
                while not finished do MC.sleep(25) end
                MC.Player.setCarried(uuid,nil)
                MC.Event.clear()
                print('done')
                """.formatted(uuid,pos.getX(),pos.getY(),pos.getZ(),pos.getX(),pos.getY(),pos.getZ());
        var diagnostics = LuaCodeAnalyzer.analyze("container_probe", source);
        check(diagnostics.errorCount()==0, "New menu API analyzer rejected example: " + diagnostics.diagnostics());
        Run run = start(server,uuid,source,debug); await(run,"mutable-ready");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),0,0,ContainerInput.PICKUP)).get();
        await(run,"containerItemTaken:0:8"); await(run,"containerItemToCursor:0:8");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),1,1,ContainerInput.PICKUP)).get();
        await(run,"containerItemPlaced:1:1");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),0,0,ContainerInput.PICKUP)).get();
        await(run,"containerItemPlaced:0:7");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),1,0,ContainerInput.QUICK_MOVE)).get();
        await(run,"containerItemTaken:1:1");
        check(run.output.stream().noneMatch(line->line.startsWith("containerItemToCursor:1:")),"Shift falsely moved to cursor");
        server.submit(() -> {
            var p=server.getPlayerList().getPlayer(uuid); p.getInventory().setItem(0,new ItemStack(Items.GOLD_INGOT,3));
            click(p,0,0,ContainerInput.SWAP);
        }).get();
        await(run,"containerItemPlaced:0:3");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),26,0,ContainerInput.PICKUP)).get();
        await(run,"readonly-ready");
        int changes=(int)run.output.stream().filter(line->line.startsWith("containerItem")).count();
        server.submit(() -> {
            var p=server.getPlayerList().getPlayer(uuid);
            for (var input:ContainerInput.values()) for(int button=0;button<3;button++) click(p,0,button,input);
            check(p.containerMenu instanceof LuaVirtualChestMenu && p.containerMenu.getSlot(0).getItem().getCount()==8
                    && p.containerMenu.getCarried().isEmpty(),"Readonly menu leaked icons/cursor");
        }).get();
        Thread.sleep(150);
        check(run.output.stream().filter(line->line.startsWith("containerItem")).count()==changes,"Readonly emitted fake transfer event");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),26,0,ContainerInput.PICKUP)).get();
        await(run,"physical-ready");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),0,1,ContainerInput.PICKUP)).get();
        await(run,"containerItemToCursor:0:3");
        server.submit(() -> click(server.getPlayerList().getPlayer(uuid),26,0,ContainerInput.PICKUP)).get();
        await(run,"inventory-ready");
        int inventorySlot=server.submit(()->{
            var p=server.getPlayerList().getPlayer(uuid);
            for(var slot:p.containerMenu.slots) if(slot.container==p.getInventory()&&slot.getContainerSlot()==0)return slot.index;
            throw new AssertionError("No native inventory slot");
        }).get();
        server.submit(()->click(server.getPlayerList().getPlayer(uuid),inventorySlot,0,ContainerInput.PICKUP)).get();
        await(run,"inventoryItemToCursor:"+inventorySlot+":5");
        server.submit(()->click(server.getPlayerList().getPlayer(uuid),0,0,ContainerInput.PICKUP)).get();
        await(run,"done");
        var result=run.result.get(10,TimeUnit.SECONDS);
        check(result.error()==null&&!result.stopped(),"Native menu script failed: "+result+run.output);
    }

    private static void verifyComponentReads(MinecraftServer server, UUID uuid, boolean debug) throws Exception {
        BlockPos pos = server.submit(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            player.closeContainer(); player.getInventory().clearContent(); player.getInventory().setSelectedSlot(0);
            return player.blockPosition().offset(3, 1, 3);
        }).get(10, TimeUnit.SECONDS);
        String source = """
                local uuid = '%s'
                local x, y, z = %d, %d, %d
                local comps = {['minecraft:custom_data']={read_probe=7}}
                MC.Item.setEquipment(uuid, 'mainhand', 'minecraft:diamond', 2, comps)
                MC.Item.setEquipment(uuid, 'offhand', 'minecraft:shield', 1, comps)
                assert(MC.Item.equipment(uuid,'offhand').components['minecraft:custom_data'].read_probe==7)
                assert(MC.Item.getSlot(uuid,0).id=='minecraft:diamond')
                assert(MC.Item.getSlot(uuid,0).components['minecraft:custom_data'].read_probe==7)
                assert(MC.Entity.equipment(uuid,'mainhand').count==2)
                local playerData = MC.Entity.components(uuid)
                assert(playerData.uuid==uuid and playerData.type=='minecraft:player')
                assert(playerData.equipment.offhand.id=='minecraft:shield')
                assert(type(playerData.entityData)=='table' and type(playerData.health)=='number')
                assert(type(playerData.components)=='table' and type(playerData.componentErrors)=='table')
                assert(type(playerData.entityDataJson)=='table' and type(playerData.componentsJson)=='table')
                playerData.equipment.offhand.components['minecraft:custom_data'].read_probe=99
                assert(MC.Item.equipment(uuid,'offhand').components['minecraft:custom_data'].read_probe==7)
                MC.Item.setEquipment(uuid,'offhand',nil)
                local empty=MC.Item.equipment(uuid,'offhand')
                assert(empty.empty and empty.count==0 and next(empty.componentsJson)==nil)
                local entity=MC.Entity.spawn('minecraft:armor_stand',x,y,z,nil,{
                    health=12,invulnerable=true,equipment={offhand={id='minecraft:stick',count=1,components=comps}},
                    ['minecraft:custom_name']={text='SnapshotProbe',color='gold'}})
                local data=MC.Entity.components(entity)
                assert(data.health==12 and data.invulnerable and data.noGravity==false)
                assert(data.equipment.offhand.components['minecraft:custom_data'].read_probe==7)
                assert(MC.Entity.equipment(entity,'offhand').id=='minecraft:stick')
                assert(data.componentsJson['minecraft:custom_name']~=nil)
                assert(data.entityDataJson==data.entityData)
                local display=MC.Entity.spawn('minecraft:text_display',x,y,z,nil,{text={text='ReadDisplay',color='red'}})
                local displayData=MC.Entity.components(display)
                assert(displayData.health==nil and next(displayData.equipment)==nil)
                assert(displayData.entityData.text~=nil and displayData.entityDataJson.text~=nil)
                local item=MC.Entity.spawn('minecraft:item',x,y,z,nil,{
                    Item={id='minecraft:diamond',count=3,components=comps},PickupDelay=32767})
                local dropped=MC.Entity.components(item)
                assert(dropped.entityData.Item.id=='minecraft:diamond' and dropped.entityData.Item.count==3)
                assert(dropped.entityData.Item.components['minecraft:custom_data'].read_probe==7)
                assert(dropped.entityData.PickupDelay==32767)
                MC.Block.set(x,y,z,'minecraft:chest',nil,{states={facing='east'}})
                MC.Container.setSlot(x,y,z,0,'minecraft:diamond',3,comps)
                local block=MC.Block.get(x,y,z)
                assert(block.hasBlockEntity and block.states.facing=='east')
                assert(block.blockEntityData.Items[1].id=='minecraft:diamond')
                assert(block.blockEntityData.Items[1].components['minecraft:custom_data'].read_probe==7)
                assert(type(block.itemComponentsJson)=='table' and type(block.blockEntityDataJson)=='table')
                block.blockEntityData.Items[1].count=99
                assert(MC.Container.getSlot(x,y,z,0).count==3)
                MC.Block.set(x+1,y,z,'minecraft:stone')
                local stone=MC.Block.get(x+1,y,z)
                assert(not stone.hasBlockEntity and next(stone.blockEntityData)==nil and next(stone.blockEntityDataJson)==nil)
                MC.Block.set(x+2,y,z,'minecraft:oak_sign',nil,{blockEntityData={
                    front_text={messages={'ReadSign','','',''},color='blue',has_glowing_text=true}}})
                local sign=MC.Block.get(x+2,y,z)
                assert(sign.blockEntityData.front_text.color=='blue')
                assert(sign.blockEntityDataJson.front_text.color=='blue')
                assert(not pcall(function() MC.Entity.components('invalid-uuid') end))
                local missing, reason=MC.Entity.components('00000000-0000-0000-0000-000000000001')
                assert(missing==nil and type(reason)=='string')
                MC.Entity.remove(entity); MC.Entity.remove(display); MC.Entity.remove(item)
                MC.Block.set(x,y,z,'minecraft:air'); MC.Block.set(x+1,y,z,'minecraft:air'); MC.Block.set(x+2,y,z,'minecraft:air')
                print('component-reads-ok')
                """.formatted(uuid, pos.getX(), pos.getY(), pos.getZ());
        var diagnostics=LuaCodeAnalyzer.analyze("component_reads_probe", source);
        // Runtime-negative cases above deliberately use malformed IDs; document samples are checked separately.
        check(LuaApiDocumentation.find("MC.Entity.components")!=null,"Missing component API document");
        Run run=start(server,uuid,source,debug);
        var result=run.result.get(30,TimeUnit.SECONDS);
        check(result.error()==null&&!result.stopped()&&run.output.contains("component-reads-ok"),
                "Component snapshot run failed: "+result+" "+run.output+" diagnostics="+diagnostics.diagnostics());
    }

    private static void verifyItemFilters(MinecraftServer server, UUID uuid, boolean debug) throws Exception {
        server.submit(() -> { var p = server.getPlayerList().getPlayer(uuid); p.closeContainer(); p.getInventory().clearContent(); }).get();
        String source = """
                local uuid = '%s'
                local id = 'minecraft:knowledge_book'
                local full = {
                    ['minecraft:custom_data']={new_type='fire_charge', nested={n=3, extra=true}},
                    ['minecraft:item_model']='minecraft:fire_charge', ['minecraft:item_name']='火焰弹',
                    ['minecraft:lore']={'右键发射'}, ['minecraft:max_stack_size']=64
                }
                local filter = {['minecraft:custom_data']={new_type='fire_charge'}}
                MC.Item.setSlot(uuid,0,id,8,full)
                MC.Item.setSlot(uuid,1,id,4,{['minecraft:custom_data']={new_type='other'},['minecraft:max_stack_size']=64})
                MC.Item.setSlot(uuid,2,id,2,{['minecraft:max_stack_size']=64})
                assert(MC.Item.count(uuid,id)==14, 'fixture total='..MC.Item.count(uuid,id))
                assert(MC.Item.count(uuid,id,{})==14)
                assert(MC.Item.count(uuid,id,filter)==8)
                assert(MC.Item.count(uuid,id,'{"minecraft:custom_data":{"new_type":"fire_charge"}}')==8)
                assert(MC.Item.count(uuid,id,{['minecraft:custom_data']={nested={n=3}}})==8)
                assert(MC.Item.count(uuid,id,{['minecraft:custom_data']={nested={n=99}}})==0)
                assert(MC.Item.clear(uuid,id,1,filter))
                assert(MC.Item.getSlot(uuid,0).count==7 and MC.Item.getSlot(uuid,1).count==4 and MC.Item.getSlot(uuid,2).count==2)
                assert(MC.Item.clear(uuid,id,0,filter) and MC.Item.count(uuid,id,filter)==7)
                assert(MC.Item.trade(uuid,{{id=id,count=4,components=filter}},{{id='minecraft:paper',count=1}}))
                assert(MC.Item.count(uuid,id,filter)==3 and MC.Item.count(uuid,'minecraft:paper')==1)
                assert(MC.Item.clear(uuid,id,nil,filter))
                assert(MC.Item.count(uuid,id,filter)==0 and MC.Item.count(uuid,id)==6)
                print('partial-filters-ok')
                """.formatted(uuid);
        Run run = start(server, uuid, source, debug);
        var result = run.result.get(20, TimeUnit.SECONDS);
        check(result.error() == null && run.output.contains("partial-filters-ok"), "Partial component Lua APIs failed: " + result + " " + run.output);
    }

    private static void verifyShop(MinecraftServer server, UUID uuid) throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("mc_lua_ide.shopProbeSource")));
        var analysis = LuaCodeAnalyzer.analyze("Bed_Wars/Shop/Main", source);
        check(analysis.errorCount()==0,"Shop has static errors: "+analysis.diagnostics());
        server.submit(()->{
            var p=server.getPlayerList().getPlayer(uuid);p.closeContainer();p.getInventory().clearContent();
            p.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,8));
        }).get();
        String startup="local Shop=(function()\n"+source+"\nend)()\nShop.open('"+uuid+"',1)\nprint('shop-module-ready')";
        Run run=start(server,uuid,startup,false);await(run,"shop-module-ready");
        server.submit(()->click(server.getPlayerList().getPlayer(uuid),0,0,ContainerInput.PICKUP)).get();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(server.submit(()->server.getPlayerList().getPlayer(uuid).getInventory().countItem(Items.WHITE_WOOL)).get()!=16){
            if(run.result.isDone()||System.nanoTime()>until)throw new AssertionError("Shop purchase failed: "+run.output);
            Thread.sleep(20);
        }
        server.submit(()->{
            var p=server.getPlayerList().getPlayer(uuid);
            check(p.getInventory().countItem(Items.IRON_INGOT)==7,"Wrong configured shop charge");
            check(p.containerMenu.getCarried().isEmpty()&&p.containerMenu.getSlot(0).getItem().getCount()==16,"Shop display escaped to player cursor");
            ItemStack wool=ItemStack.EMPTY;
            for(int slot=0;slot<36;slot++)if(p.getInventory().getItem(slot).is(Items.WHITE_WOOL))wool=p.getInventory().getItem(slot);
            check("minecraft:bedrock".equals(String.valueOf(wool.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))),"Configured item_model lost on purchase");
            click(p,50,0,ContainerInput.PICKUP);
        }).get();
        until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!server.submit(()->server.getPlayerList().getPlayer(uuid).containerMenu.getSlot(0).getItem().is(Items.IRON_SWORD)).get()){
            if(run.result.isDone()||System.nanoTime()>until)throw new AssertionError("Shop pagination failed: "+run.output);
            Thread.sleep(20);
        }
        server.submit(()->click(server.getPlayerList().getPlayer(uuid),0,0,ContainerInput.PICKUP)).get(); // No gold.
        Thread.sleep(200);
        server.submit(()->{
            var p=server.getPlayerList().getPlayer(uuid);
            check(p.getInventory().countItem(Items.IRON_SWORD)==0&&p.getInventory().countItem(Items.IRON_INGOT)==7,"Insufficient balance changed inventory");
            ServerScriptManager.stopActive();
        }).get();
        run.result.get(10,TimeUnit.SECONDS);
        server.submit(()->{
            var p=server.getPlayerList().getPlayer(uuid);
            check(p.containerMenu==p.inventoryMenu,"Stopping script left stale owned shop");
        }).get();
    }
}
