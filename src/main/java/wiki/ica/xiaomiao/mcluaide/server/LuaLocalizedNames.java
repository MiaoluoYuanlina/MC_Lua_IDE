package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.*;

final class LuaLocalizedNames {
    static void install(LuaTable mc, MinecraftServer server) {
        for (String kind : List.of("Item", "Block", "Entity", "Enchantment")) {
            LuaTable table = mc.get(kind).istable() ? mc.get(kind).checktable() : new LuaTable();
            table.set("name", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    String raw = args.checkjstring(1);
                    Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
                    if (id == null) throw new LuaError("无效资源 ID：" + raw);
                    String locale = args.arg(2).isnil() ? null : LuaLanguageAssets.normalize(args.checkjstring(2));
                    Component description = MinecraftLuaInteractionApi.onServer(server, current -> switch (kind) {
                        case "Item" -> {
                            var item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new LuaError("未知物品：" + id));
                            yield item.getName(item.getDefaultInstance()).copy();
                        }
                        case "Block" -> Component.translatable(BuiltInRegistries.BLOCK.getOptional(id)
                                .orElseThrow(() -> new LuaError("未知方块：" + id)).getDescriptionId());
                        case "Entity" -> BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                                .orElseThrow(() -> new LuaError("未知实体类型：" + id)).getDescription().copy();
                        default -> current.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                                .get(ResourceKey.create(Registries.ENCHANTMENT,id))
                                .orElseThrow(() -> new LuaError("未知附魔：" + id)).value().description().copy();
                    });
                    if (locale == null) return LuaValue.valueOf(description.getString()); // Preserve existing Item/Block name(id).
                    if (LuaNameTranslation.isLiteral(description)) return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(description.getString()),LuaValue.valueOf("literal")});
                    if (server.isSameThread()) throw new LuaRecoverableError("指定语言名称查询须在 Lua 执行线程调用，不能在服务器主线程等待语言资源");
                    Map<String,String> target = LuaLanguageAssets.load(locale);
                    Map<String,String> english = locale.equals("en_us") ? target : LuaLanguageAssets.load("en_us");
                    var result = LuaNameTranslation.translate(description,locale,target,english);
                    return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(result.name()),LuaValue.valueOf(result.language())});
                }
            });
            mc.set(kind,table); mc.set(kind.toLowerCase(Locale.ROOT),table);
        }
    }
}
