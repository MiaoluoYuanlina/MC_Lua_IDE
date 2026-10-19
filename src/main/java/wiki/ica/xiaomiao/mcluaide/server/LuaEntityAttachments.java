package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import java.util.ArrayList;
import java.util.List;

/** Fabric persistent attachments, NOT data components or an unrestricted entity NBT reload. */
final class LuaEntityAttachments {
    static final String FIELD = "fabric:attachments";
    private LuaEntityAttachments() { }

    private record Value<T>(AttachmentType<T> type, T value) {
        void apply(AttachmentTarget target) { target.setAttached(type, value); }
        JsonElement encode(MinecraftServer server) {
            return type.persistenceCodec().encodeStart(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), value)
                    .getOrThrow(error -> new IllegalArgumentException("无法编码附件 " + type.identifier() + "：" + error));
        }
    }

    static JsonObject validate(MinecraftServer server, JsonElement input) {
        JsonObject result = new JsonObject();
        for (Value<?> entry : decode(server, input)) result.add(entry.type().identifier().toString(), entry.encode(server));
        return result;
    }

    static void apply(MinecraftServer server, Entity entity, JsonElement input) {
        // Decode every entry before invoking any mod hooks. Never replaces UUID, position, equipment or unrelated attachments.
        List<Value<?>> values = decode(server, input);
        for (Value<?> entry : values) entry.apply((AttachmentTarget) entity);
    }

    private static List<Value<?>> decode(MinecraftServer server, JsonElement input) {
        if (input == null || !input.isJsonObject()) throw new IllegalArgumentException(FIELD + " 必须是附件 ID 到数据的对象");
        if (input.getAsJsonObject().size() > 128 || input.toString().length() > 262144)
            throw new IllegalArgumentException(FIELD + " 最多 128 项，数据最多 262144 字符");
        List<Value<?>> result = new ArrayList<>();
        for (var entry : input.getAsJsonObject().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null) throw new IllegalArgumentException("无效附件 ID：" + entry.getKey());
            // Fabric 2.2 has no public lookup-by-ID API; keep the version-specific registry access isolated here.
            AttachmentType<?> type = AttachmentRegistryImpl.get(id);
            if (type == null) throw new IllegalArgumentException("未知 Fabric 附件：" + id + "；请确认提供它的模组已安装在服务端");
            if (type.persistenceCodec() == null) throw new IllegalArgumentException("附件不支持持久化数据：" + id);
            result.add(decodeValue(server, type, entry.getValue()));
        }
        return result;
    }

    private static <T> Value<T> decodeValue(MinecraftServer server, AttachmentType<T> type, JsonElement input) {
        T value = type.persistenceCodec().parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), input)
                .getOrThrow(error -> new IllegalArgumentException("附件 " + type.identifier() + " 数据无效：" + error));
        return new Value<>(type, value);
    }
}
