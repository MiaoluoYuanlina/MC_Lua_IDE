package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.MinecraftServer;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;
import java.util.*;
import java.util.concurrent.*;

/** A fresh world and actual cold-cache official language downloads; never opens a user's save. */
final class LuaLocalizedNamesProbe {
    private static int stage;
    private static long started;
    private static CompletableFuture<Void> task;
    static void tick(Minecraft client) {
        if(stage==99||client.getOverlay()!=null)return;
        try {
            if(stage==0){
                stage=1;started=System.nanoTime();client.options.renderDistance().set(2);client.options.simulationDistance().set(6);
                CreateWorldScreen.testWorld(client,()->{throw new AssertionError("Cancelled");});return;
            }
            if(System.nanoTime()-started>TimeUnit.SECONDS.toNanos(180))throw new AssertionError("Name probe timeout");
            if(stage==1){
                if(!(client.screen instanceof CreateWorldScreen create))return;
                create.getUiState().setName("localized_names_"+System.currentTimeMillis());create.getUiState().setAllowCommands(true);create.getUiState().setGenerateStructures(false);
                var method=CreateWorldScreen.class.getDeclaredMethod("onCreate");method.setAccessible(true);stage=2;method.invoke(create);return;
            }
            if(stage==2){
                if(client.player==null||client.getSingleplayerServer()==null||!ScriptManager.isServerAvailable())return;
                MinecraftServer server=client.getSingleplayerServer();UUID owner=client.player.getUUID();
                task=CompletableFuture.runAsync(()->{try{verify(server,owner,false);verify(server,owner,true);}catch(Exception e){throw new CompletionException(e);}});
                stage=3;return;
            }
            if(stage==3&&task.isDone()){
                task.join();stage=99;System.out.println("Localized names real game probe passed: item/block/entity/enchantment, actual zh_cn/en_us/ja_jp assets, native dynamic registry, offline overrides, fallback metadata, aliases, old calls, invalid IDs/languages and no global Language changes; normal/debug");client.stop();
            }
        }catch(Exception|AssertionError error){stage=99;client.stop();throw new IllegalStateException("Localized names probe failed",error);}
    }
    private static void verify(MinecraftServer server, UUID owner, boolean debug)throws Exception{
        var root=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/lang");
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve("xx_test.json"),"{\"item.minecraft.diamond\":\"测试钻石\",\"enchantment.minecraft.sharpness\":\"测试锋利\"}");
        var original=net.minecraft.locale.Language.getInstance();
        String source="""
                local diamond, locale = MC.Item.name('minecraft:diamond','zh_cn')
                assert(diamond=='钻石' and locale=='zh_cn', tostring(diamond)..' '..tostring(locale))
                assert(MC.Block.name('minecraft:stone','zh_cn')=='石头')
                assert(MC.Entity.name('minecraft:pig','zh_cn')=='猪')
                assert(MC.Enchantment.name('minecraft:sharpness','zh_cn')=='锋利')
                assert(MC.Item.name('minecraft:diamond','en_us')=='Diamond')
                assert(MC.Block.name('minecraft:stone','en_us')=='Stone')
                assert(MC.Entity.name('minecraft:pig','en_us')=='Pig')
                assert(MC.Enchantment.name('minecraft:sharpness','en_us')=='Sharpness')
                local japanese, jp=MC.Item.name('minecraft:diamond','ja_jp')
                assert(japanese~='Diamond' and japanese~='钻石' and jp=='ja_jp')
                assert(MC.item.name('diamond','ZH-CN')=='钻石')
                assert(MC.enchantment.name('sharpness','XX-Test')=='测试锋利')
                assert(MC.Item.name('diamond','xx_test')=='测试钻石')
                local fallback, actual=MC.Block.name('stone','xx_test')
                assert(fallback=='Stone' and actual=='en_us')
                assert(type(MC.Item.name('diamond'))=='string')
                assert(type(MC.Block.name('stone'))=='string')
                assert(type(MC.Entity.name('pig'))=='string')
                assert(type(MC.Enchantment.name('sharpness'))=='string')
                assert(not pcall(function() MC.Item.name('test:no_such_item','zh_cn') end))
                assert(not pcall(function() MC.Block.name('test:no_such_block','zh_cn') end))
                assert(not pcall(function() MC.Entity.name('test:no_such_entity','zh_cn') end))
                assert(not pcall(function() MC.Enchantment.name('test:no_such_enchantment','zh_cn') end))
                assert(not pcall(function() MC.Item.name('diamond','../../secret') end))
                assert(not pcall(function() MC.Item.name('diamond',{}) end))
                local missing, reason=MC.Item.name('diamond','zz_test')
                assert(missing==nil and type(reason)=='string')
                print('localized-names-ok')
                """;
        var diagnostics=LuaCodeAnalyzer.analyze("localized_names_probe", """
                local name, language = MC.Item.name('minecraft:diamond','zh_cn')
                print(MC.Block.name('minecraft:stone','en_us'))
                print(MC.Entity.name('minecraft:pig','zh_cn'))
                print(MC.Enchantment.name('minecraft:sharpness','ja_jp'))
                """);
        if(diagnostics.errorCount()>0)throw new AssertionError("Name API analyzer: "+diagnostics.diagnostics());
        List<String> output=new CopyOnWriteArrayList<>();var completed=new CompletableFuture<ServerScriptManager.ExecutionResult>();
        server.execute(()->{try{ServerScriptManager.start(server,owner,"localized_names_probe",source,debug,output::add,completed::complete);}catch(Throwable e){completed.completeExceptionally(e);}});
        var result=completed.get(60,TimeUnit.SECONDS);
        if(result.error()!=null||result.stopped()||!output.contains("localized-names-ok"))throw new AssertionError("Translation run failed: "+result+" "+output);
        if(net.minecraft.locale.Language.getInstance()!=original)throw new AssertionError("Lookup changed the global game Language");
    }
}
