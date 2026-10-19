package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.nio.file.*;

/** Executes only mocked MC operations: never connects to or runs scripts in a game/server. */
public final class LuaConfiguredShopSmoke {
    public static void main(String[] args) throws Exception {
        String module = Files.readString(Path.of(args[0]));
        Globals globals = JsePlatform.standardGlobals();
        globals.set("rawget", LuaValue.NIL); // Match the default server sandbox, no raw/metatable features.
        globals.load("""
            Test = {menus={}, opens=0, trades=0, events={}, balance={}, received={}, teams={}}
            local function clone(v)
                if type(v)~='table' then return v end
                local r={} for k,c in pairs(v) do r[k]=clone(c) end return r
            end
            MC = {
                Item = {
                    validate=function(id,count,components)
                        assert(type(id)=='string' and id~='invalid:item' and id~='minecraft:red_nonexistent')
                        return {id=id,count=count,components=clone(components or {}),name=id,
                            maxStack=id=='minecraft:iron_sword' and 1 or 64,empty=id=='minecraft:air'}
                    end,
                    name=function(id,language) return id,language end,
                    trade=function(uuid,costs,rewards,expected)
                        Test.trades=Test.trades+1
                        if not Test.menus[uuid] or Test.menus[uuid].containerId~=expected then return nil,'stale menu' end
                        local c=costs[1] local r=rewards[1]
                        local balance=Test.balance[uuid] or {}
                        if (balance[c.id] or 0)<c.count then return false,'货币不足' end
                        if Test.full then return false,'背包空间不足' end
                        balance[c.id]=balance[c.id]-c.count
                        Test.received[uuid]=Test.received[uuid] or {}
                        table.insert(Test.received[uuid],clone(r))
                        return true
                    end
                },
                Player = {
                    openChest=function(uuid,title,items,rows,readonly)
                        if uuid=='offline' then return nil,'玩家不在线' end
                        Test.opens=Test.opens+1
                        Test.menus[uuid]={containerId=Test.opens,title=title,items=items,rows=rows,readOnly=readonly}
                        return Test.opens
                    end,
                    menu=function(uuid) return Test.menus[uuid] or {containerId=0} end,
                    closeMenu=function(uuid) Test.menus[uuid]=nil return true end
                },
                Team={has=function(team,uuid)
                    if uuid=='team-unavailable' then return nil,'玩家不在线' end
                    return Test.teams[uuid]==team
                end},
                Chat={message=function(uuid,text) Test.message=text end},
                Event={
                    onContainerClicked=function(fn) Test.events.clicked=fn Test.registrations=(Test.registrations or 0)+1 return 1 end,
                    onPlayerLeft=function(fn) Test.events.left=fn Test.registrations=(Test.registrations or 0)+1 return 2 end
                }
            }
            function Test.click(uuid,slot,overrides)
                local e={playerUuid=uuid,containerId=MC.Player.menu(uuid).containerId,slotId=slot,
                    virtual=true,readOnly=true,slotArea='container',clickType='PICKUP',button=0}
                for k,v in pairs(overrides or {}) do e[k]=v end
                Test.events.clicked(e)
            end
            """, "shop_mock").call();
        LuaScriptModuleLoader.install(globals, name -> {
            if (!name.equals("Bed_Wars/Shop/Main")) throw new java.io.IOException("Unexpected module " + name);
            return module;
        }, () -> {}, 100_000);
        globals.load("""
            local Shop=require('Bed_Wars/Shop/Main')
            assert(Shop==require('Bed_Wars.Shop.Main') and Shop.open==Open_Shop and Shop.open==Shop.Open_Shop)
            assert(Test.opens==0 and Test.registrations==2) -- loading never targets every online player
            assert(Config_Shop_Item[1][1][3]['minecraft:item_model']=='minecraft:bedrock')
            local id=Shop.open('alice',1)
            assert(id and Test.menus.alice.rows==6 and Test.menus.alice.readOnly)
            assert(Test.menus.alice.items[1].id=='minecraft:white_wool' and Test.menus.alice.items[1].count==16)
            assert(Test.menus.alice.items[1].components['minecraft:item_model']=='minecraft:bedrock')
            assert(Test.menus.alice.items[46]==nil and Test.menus.alice.items[51].id=='minecraft:arrow')
            assert(Config_Shop_Item[1][1][3]['minecraft:lore']==nil)
            Test.balance.alice={['minecraft:iron_ingot']=3,['minecraft:gold_ingot']=6}
            Test.click('alice',0)
            assert(Test.balance.alice['minecraft:iron_ingot']==2)
            local gift=Test.received.alice[1]
            assert(gift.id=='minecraft:white_wool' and gift.count==16 and gift.components['minecraft:item_model']=='minecraft:bedrock')
            assert(gift.components['minecraft:lore']==nil and gift.components['minecraft:custom_name']==nil)
            Test.click('alice',50) -- next page
            assert(Test.menus.alice.title.text:find('第 2 页',1,true))
            assert(Test.menus.alice.items[1].id=='minecraft:iron_sword' and Test.menus.alice.items[46].id=='minecraft:arrow')
            assert(Test.menus.alice.items[51]==nil)
            Test.click('alice',0)
            assert(Test.balance.alice['minecraft:gold_ingot']==0 and Test.received.alice[2].id=='minecraft:iron_sword')
            Test.click('alice',0) -- insufficient funds: do not grant another sword
            assert(#Test.received.alice==2)
            Test.click('alice',45) -- previous page
            assert(Test.menus.alice.items[1].id=='minecraft:white_wool')
            local calls=Test.trades
            for _,extra in ipairs({{button=1},{clickType='QUICK_MOVE'},{readOnly=false},{virtual=false},
                {slotArea='inventory'},{containerId=id}}) do Test.click('alice',0,extra) end
            assert(Test.trades==calls)
            Test.full=true Test.click('alice',0) Test.full=false
            assert(Test.balance.alice['minecraft:iron_ingot']==2 and #Test.received.alice==2)
            local current=Test.menus.alice
            Config_Shop_Item[1][1][5]=99 -- existing page keeps displayed price
            Test.click('alice',0)
            assert(Test.balance.alice['minecraft:iron_ingot']==1 and #Test.received.alice==3)
            Shop.open('alice',1) Test.click('alice',0)
            assert(Test.balance.alice['minecraft:iron_ingot']==1 and #Test.received.alice==3)
            local bob=Shop.open('bob',2)
            assert(bob and Test.menus.alice~=nil and Test.menus.bob.items[1].id=='minecraft:iron_sword')
            Test.click('alice',53) assert(Test.menus.alice==nil and Test.menus.bob~=nil)
            Test.events.left({playerUuid='bob'})
            Test.click('bob',0) assert(Test.trades==calls+3)
            assert(Shop.close('bob')==false)
            Test.menus.bob={containerId=10000} assert(Shop.close('bob')==false and Test.menus.bob.containerId==10000)
            local offline,reason=Shop.open('offline',1) assert(offline==nil and reason)
            assert(not pcall(Shop.open,'alice',99))
            assert(not pcall(Shop.configure,{[1]={[46]={'minecraft:stone',1,{},'minecraft:iron_ingot',1}}}))
            assert(not pcall(Shop.configure,{[1]={{'minecraft:stone',1,{},'minecraft:iron_ingot',0}}}))
            Shop.configure({[3]={[2]={'minecraft:white_wool',130,{},'minecraft:iron_ingot',1}},[7]={}})
            Shop.open('alice') assert(Test.menus.alice.items[1]==nil and Test.menus.alice.items[2].count==64)
            Test.click('alice',50) assert(Test.menus.alice.title.text:find('第 7 页',1,true))
            -- Color is opt-in, validates atomically, and does not mutate shared configuration.
            local colors={'red','green','blue','yellow'}
            assert(Shop.Color(colors)==Shop)
            colors[1]='black' -- the module owns a copy
            local config={[1]={
                {'minecraft:white_wool',16,{['minecraft:item_model']='minecraft:white_wool'},'minecraft:iron_ingot',1},
                {'white_mod:white_stained_glass',2,{},'minecraft:white_dye',2},
                {'minecraft:white_white',1,{},'minecraft:iron_ingot',1}
            }}
            Shop.configure(config)
            for _,color in ipairs({'red','green','blue','yellow'}) do
                Test.teams.alice=color
                Shop.open('alice',1)
                local items=Test.menus.alice.items
                assert(items[1].id=='minecraft:'..color..'_wool')
                assert(items[2].id=='white_mod:'..color..'_stained_glass')
                assert(items[3].id=='minecraft:'..color..'_'..color)
                assert(items[1].components['minecraft:item_model']=='minecraft:white_wool')
                Test.balance.alice={['minecraft:iron_ingot']=2,['minecraft:'..color..'_dye']=2}
                Test.click('alice',0)
                local gift=Test.received.alice[#Test.received.alice]
                assert(gift.id=='minecraft:'..color..'_wool' and gift.count==16)
                Test.click('alice',1)
                assert(Test.balance.alice['minecraft:'..color..'_dye']==0)
                assert(Test.received.alice[#Test.received.alice].id=='white_mod:'..color..'_stained_glass')
            end
            assert(config[1][1][1]=='minecraft:white_wool' and config[1][2][4]=='minecraft:white_dye')
            Test.teams.bob='blue' Shop.open('bob',1)
            Test.teams.alice='red' Shop.open('alice',1)
            assert(Test.menus.bob.items[1].id=='minecraft:blue_wool')
            local previousTrades=Test.trades
            Test.teams.alice='green' Test.click('alice',0)
            assert(Test.trades==previousTrades and Test.menus.alice.items[1].id=='minecraft:green_wool')
            Test.teams.alice=nil Test.click('alice',0)
            assert(Test.trades==previousTrades and Test.menus.alice.items[1].id=='minecraft:white_wool')
            Test.teams.alice='red'
            for _,bad in ipairs({'red',false,{'pinkish'},{'red','red'},{[2]='red'},{red=true},{[17]='red'}}) do
                assert(not pcall(Shop.Color,bad))
            end
            Shop.open('alice',1) assert(Test.menus.alice.items[1].id=='minecraft:red_wool')
            local unavailable,why=Shop.open('team-unavailable',1)
            assert(unavailable==nil and why=='玩家不在线')
            local beforeMenu=Test.menus.alice
            Shop.configure({[1]={{'minecraft:white_nonexistent',1,{},'minecraft:iron_ingot',1}}})
            assert(not pcall(Shop.open,'alice',1) and Test.menus.alice==beforeMenu)
            Shop.configure(config)
            Shop.Color({}) Shop.open('alice',1)
            assert(Test.menus.alice.items[1].id=='minecraft:white_wool')
            Shop.Color({'red'}) Shop.Color(nil) Shop.open('alice',1)
            assert(Test.menus.alice.items[1].id=='minecraft:white_wool')
            print('Configured shop smoke passed: module caching, targeted opening, tuple configs, pagination, component-safe gifts, prices, failures and stale clicks')
            print('Shop.Color smoke passed: four teams, display and trade, colored currency, namespaces, components, configuration isolation, team changes, validation and disable')
            """, "shop_test").call();
        // A new execution can supply its own global configuration before loading the module.
        Globals configured=JsePlatform.standardGlobals();
        configured.set("MC",globals.get("MC"));
        configured.load("Config_Shop_Item={[9]={{'minecraft:stone',1,{},'minecraft:iron_ingot',2}}}").call();
        LuaScriptModuleLoader.install(configured,name->module,()->{},100_000);
        configured.load("local s=require('Bed_Wars/Shop/Main'); assert(Config_Shop_Item[1]==nil); assert(Config_Shop_Item[9][1][1]=='minecraft:stone')").call();
    }
}
