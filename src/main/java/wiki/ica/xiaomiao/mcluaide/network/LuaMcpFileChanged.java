package wiki.ica.xiaomiao.mcluaide.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import java.util.ArrayList;
import java.util.List;

/** Administrator-only authoritative snapshot, emitted only after a successful MCP save. */
public record LuaMcpFileChanged(String name, String beforeHash, String source, List<LuaTextChanges.Edit> edits) implements CustomPacketPayload {
	public static final Type<LuaMcpFileChanged> TYPE = new Type<>(McLuaIdeMod.id("mcp_file_changed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LuaMcpFileChanged> CODEC = StreamCodec.of(
			(buffer, value) -> {
				buffer.writeUtf(value.name, LuaProtocol.MAX_NAME_CHARS); buffer.writeUtf(value.beforeHash, 64);
				buffer.writeUtf(value.source, LuaProtocol.MAX_SCRIPT_CHARS); buffer.writeVarInt(value.edits.size());
				for (var edit : value.edits) {
					buffer.writeVarInt(edit.oldStart()); buffer.writeVarInt(edit.oldEnd()); buffer.writeVarInt(edit.start()); buffer.writeVarInt(edit.end());
				}
			}, buffer -> {
				String name = buffer.readUtf(LuaProtocol.MAX_NAME_CHARS), hash = buffer.readUtf(64), source = buffer.readUtf(LuaProtocol.MAX_SCRIPT_CHARS);
				int count = buffer.readVarInt();
				if (count < 0 || count > LuaTextChanges.MAX_EDITS) throw new IllegalArgumentException("MCP 修改范围过多");
				List<LuaTextChanges.Edit> edits = new ArrayList<>();
				for (int i = 0; i < count; i++) edits.add(new LuaTextChanges.Edit(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
				return new LuaMcpFileChanged(name, hash, source, edits);
			});
	public LuaMcpFileChanged {
		LuaScriptNames.requireValid(name);
		if (source == null || source.length() > LuaProtocol.MAX_SCRIPT_CHARS || beforeHash == null || !beforeHash.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("MCP 文件快照无效");
		edits = List.copyOf(edits);
		if (edits.size() > LuaTextChanges.MAX_EDITS) throw new IllegalArgumentException("MCP 修改范围过多");
		int oldEnd = 0, newEnd = 0;
		for (var edit : edits) {
			if (edit.oldStart() < oldEnd || edit.oldEnd() < edit.oldStart() || edit.oldEnd() > LuaProtocol.MAX_SCRIPT_CHARS
					|| edit.start() < newEnd || edit.end() < edit.start() || edit.end() > source.length())
				throw new IllegalArgumentException("MCP 修改范围无效");
			oldEnd = edit.oldEnd(); newEnd = edit.end();
		}
	}
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
