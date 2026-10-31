package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/** Detached snapshots of real world objects, never their item or spawn-egg templates. */
final class LuaComponentSnapshots {
    private LuaComponentSnapshots() { }

    static LuaTable entity(MinecraftServer server, Entity entity) {
        LuaTable result = new LuaTable();
        result.set("uuid", entity.getStringUUID());
        result.set("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        JsonObject components = new JsonObject();
        LuaTable errors = new LuaTable();
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (var type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
            if (type.isTransient()) continue;
            String id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
            try {
                Object value = entity.get(type);
                if (value != null) components.add(id,
                        TypedDataComponent.createUnchecked(type, value).encodeValue(ops).getOrThrow());
            } catch (RuntimeException error) {
                // A third-party getter/codec must not hide the remaining readable components.
                errors.set(id, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }
        putJson(result, "components", components);
        result.set("componentErrors", errors);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        entity.saveWithoutId(output);
        putJson(result, "entityData", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, output.buildResult()).getAsJsonObject());
        result.set("glowing", LuaValue.valueOf(entity.isCurrentlyGlowing()));
        result.set("invulnerable", LuaValue.valueOf(entity.isInvulnerable()));
        result.set("noGravity", LuaValue.valueOf(entity.isNoGravity()));
        LuaTable equipment = new LuaTable();
        if (entity instanceof LivingEntity living) {
            result.set("health", living.getHealth());
            result.set("maxHealth", living.getMaxHealth());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                equipment.set(slot.getName(), LuaItemStackCodec.toLua(server, living.getItemBySlot(slot)));
            }
        }
        result.set("equipment", equipment);
        return result;
    }

    static void blockEntity(MinecraftServer server, BlockEntity entity, LuaTable result) {
        result.set("hasBlockEntity", LuaValue.valueOf(entity != null));
        JsonObject data = entity == null ? new JsonObject()
                : NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, entity.saveCustomOnly(server.registryAccess())).getAsJsonObject();
        putJson(result, "blockEntityData", data);
    }

    private static void putJson(LuaTable result, String field, JsonObject value) {
        String json = value.toString();
        if (json.length() > 262_144) throw new LuaError("读取的 " + field + " 数据超过 262144 字符，请改为读取指定装备／容器槽位");
        LuaValue table = LuaItemStackCodec.jsonObjectToLua(json);
        result.set(field, table);
        result.set(field + "Json", table); // the same table: structured data, not text
    }
}
