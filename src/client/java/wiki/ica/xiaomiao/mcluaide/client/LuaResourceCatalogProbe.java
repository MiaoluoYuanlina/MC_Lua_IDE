package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Isolated opt-in client check: real files, picker, sounds.json, save invalidation and F3+T-style reload. */
final class LuaResourceCatalogProbe {
    private static String NS;
    private static String pack;
    private static int stage;
    private static long start;
    private static LuaInsertOverlay picker;
    private static CompletableFuture<Void> reload;
    private static List<String> originalPacks;

    static void tick(Minecraft minecraft) {
        if (stage == 3 || minecraft.getOverlay() != null) return;
        try {
            if (stage == 0) {
                pack = "catalog_probe_" + Long.toUnsignedString(System.nanoTime());
                NS = pack;
                start = System.nanoTime();
                LuaResourcePackWorkspace.createPack(pack);
                String base = pack + "/assets/" + NS + "/";
                LuaResourcePackWorkspace.createText(base + "models/item/demo.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/diamond\"}}");
                LuaResourcePackWorkspace.createText(base + "items/demo.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NS + ":item/demo\"}}");
                LuaResourcePackWorkspace.createPng(base + "textures/item/demo.png", 16, 16);
                LuaResourcePackWorkspace.createText(base + "font/demo.json", "{\"providers\":[]}");
                LuaResourcePackWorkspace.createText(base + "equipment/demo.json", "{\"layers\":{}}");
                LuaResourcePackWorkspace.createText(base + "particles/demo.json", "{\"textures\":[\"minecraft:generic_0\"]}");
                LuaResourcePackWorkspace.createText(base + "blockstates/demo.json", "{\"variants\":{}}");
                LuaResourcePackWorkspace.createText(base + "atlases/demo.json", "{\"sources\":[]}");
                LuaResourcePackWorkspace.createPng(base + "textures/gui/sprites/tooltip/demo_background.png", 16, 16);
                LuaResourcePackWorkspace.createPng(base + "textures/gui/sprites/tooltip/demo_frame.png", 16, 16);
                LuaResourcePackWorkspace.importSound(pack + "/assets/" + NS,
                        Path.of(System.getProperty("mc_lua_ide.packCatalogProbe")), NS + ":music/demo");
                LuaResourcePackWorkspace.saveText(base + "sounds.json", sounds("custom.ping"));
                picker = new LuaInsertOverlay(minecraft, minecraft.font, ignored -> { });
                picker.resize(380, 350);
                verifyResources(minecraft, false);
                verifyPicker(LuaInsertCatalog.Kind.SOUND, NS + ":custom.ping");
                require(ScriptManager.registryCatalog("minecraft:sound_event").join().stream()
                        .anyMatch(entry -> entry.value().equals(NS + ":custom.ping")), "Client sound IDs depend on server access");
                LuaResourcePackWorkspace.saveText(base + "sounds.json", sounds("custom.pong"));
                picker.tick();
                verifyPicker(LuaInsertCatalog.Kind.SOUND, NS + ":custom.pong");
                require(ScriptManager.registryCatalog("minecraft:sound_event").join().stream()
                        .noneMatch(entry -> entry.value().equals(NS + ":custom.ping")), "Saved sound keys retained stale IDs");
                LuaResourcePackWorkspace.rename(base + "models/item/demo.json", base + "models/item/renamed.json");
                verifyPicker(LuaInsertCatalog.Kind.MODEL, NS + ":item/renamed");
                require(LuaResourceAssetCatalog.load(LuaResourceAssetCatalog.MODEL, minecraft).stream()
                        .noneMatch(entry -> entry.value().equals(NS + ":item/demo")), "Renamed pending model retained old ID");
                LuaResourcePackWorkspace.rename(base + "models/item/renamed.json", base + "models/item/demo.json");
                require(LuaInsertCatalog.load(LuaInsertCatalog.Kind.ITEM, minecraft).stream()
                        .noneMatch(entry -> entry.value().equals(NS + ":demo")), "Resource model was advertised as a registered item");
                picker.open(LuaInsertCatalog.Kind.ATLAS, "");
                for (int i = 0; i < 30; i++) picker.tick();
                picker.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
                require(picker.kind() == LuaInsertCatalog.Kind.DIMENSION, "Right arrow did not switch resource/game category without group buttons");
                picker.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, 0, 0));
                require(picker.kind() == LuaInsertCatalog.Kind.ATLAS, "Left arrow did not restore resource category");
                var repository = minecraft.getResourcePackRepository();
                repository.reload();
                originalPacks = List.copyOf(repository.getSelectedIds());
                String id = repository.getAvailableIds().stream().filter(value -> value.endsWith("mc_lua_ide_edit_" + pack)).findFirst().orElseThrow();
                var selected = new ArrayList<>(originalPacks);
                selected.add(id);
                repository.setSelected(selected);
                stage = 1;
                reload = minecraft.reloadResourcePacks();
            } else if (stage == 1 && reload.isDone()) {
                reload.join();
                verifyResources(minecraft, true);
                verifyPicker(LuaInsertCatalog.Kind.SOUND, NS + ":custom.pong");
                require(LuaResourceAssetCatalog.sounds(minecraft).stream().filter(entry -> entry.value().equals(NS + ":custom.pong"))
                        .anyMatch(entry -> entry.description().contains("已加载")), "Reload failed to index enabled sounds.json");
                minecraft.getResourcePackRepository().setSelected(originalPacks);
                stage = 2;
                reload = minecraft.reloadResourcePacks();
            } else if (stage == 2 && reload.isDone()) {
                reload.join();
                LuaResourcePackWorkspace.delete(pack); // Only this probe's newly created isolated pack.
                picker.tick();
                require(LuaResourceAssetCatalog.load(LuaResourceAssetCatalog.TEXTURE, minecraft).stream()
                        .noneMatch(entry -> entry.namespace().equals(NS)), "Deleted workspace IDs were not removed");
                System.out.println("Resource catalog probe passed: separate pages for 10 resource types and game IDs without group buttons, custom sounds, save/rename/delete invalidation, actual resource reload");
                stage = 3;
                minecraft.stop();
            }
            if (stage != 3 && System.nanoTime() - start > 90_000_000_000L) throw new IllegalStateException("Catalog probe timed out");
        } catch (Exception exception) { throw new IllegalStateException("Resource catalog probe failed", exception); }
    }

    private static void verifyResources(Minecraft minecraft, boolean loaded) throws Exception {
        for (var kind : LuaInsertCatalog.Kind.group(true)) {
            String path = switch (kind) {
                case MODEL, TEXTURE -> "item/demo";
                case SOUND_FILE -> "music/demo";
                default -> "demo";
            };
            String id = NS + ":" + path;
            var entry = LuaResourceAssetCatalog.load(kind.registryId(), minecraft).stream()
                    .filter(value -> value.value().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("Missing " + kind + " " + id));
            if (kind != LuaInsertCatalog.Kind.TOOLTIP_STYLE)
                require(entry.description().contains("尚未") != loaded, "Wrong loaded/pending status for " + id);
            verifyPicker(kind, id);
        }
    }

    private static void verifyPicker(LuaInsertCatalog.Kind kind, String id) throws Exception {
        picker.open(kind, id);
        @SuppressWarnings("unchecked") var entries = (List<LuaInsertCatalog.TypedEntry>) field(picker, "filtered");
        require(entries.stream().anyMatch(entry -> entry.kind() == kind && entry.value().equals(id)), "Picker omitted " + kind + " " + id);
    }
    private static String sounds(String key) { return "{\"" + key + "\":{\"sounds\":[\"" + NS + ":music/demo\"]}}"; }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Object invoke(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
