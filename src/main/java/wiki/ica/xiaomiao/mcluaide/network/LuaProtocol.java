package wiki.ica.xiaomiao.mcluaide.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.util.concurrent.atomic.AtomicBoolean;

/** Optional client/server protocol used by administrator IDE clients. */
public final class LuaProtocol {
	public static final int VERSION = 13;
	public static final net.minecraft.resources.Identifier CLIENT_CHECK_ID =
			McLuaIdeMod.id("client_check");
	public static final int MAX_NAME_CHARS = 256;
	public static final int MAX_SCRIPT_CHARS = 100_000;
	public static final int MAX_MESSAGE_CHARS = 131_072;
	public static final int MAX_RESPONSE_CHARS = 524_288;
	public static final int MAX_CLIENT_TEXT_CHARS = 8_192;
	private static final int MAX_PACKET_BYTES = 1_048_576;
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();

	private LuaProtocol() {
	}

	public static void registerPayloadTypes() {
		if (!REGISTERED.compareAndSet(false, true)) return;
		PayloadTypeRegistry.serverboundPlay().registerLarge(
				Request.TYPE, Request.CODEC, MAX_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().registerLarge(
				Response.TYPE, Response.CODEC, MAX_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().registerLarge(
				ExecutionEvent.TYPE, ExecutionEvent.CODEC, MAX_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().registerLarge(
				ClientCommand.TYPE, ClientCommand.CODEC, MAX_PACKET_BYTES);
	}

	public enum Action {
		ACCESS,
		LIST,
		READ,
		SAVE,
		DELETE,
		RUN,
		STOP,
		GET_LIMITS,
		SAVE_LIMITS,
		GET_API_DOCUMENTATION,
		GET_COMPONENT_EDITOR_DATA,
		VALIDATE_ITEM_COMPONENTS,
		GET_REGISTRY_CATALOG;

		public static Action byId(int id) {
			if (id < 0 || id >= values().length) {
				throw new IllegalArgumentException("未知请求类型：" + id);
			}
			return values()[id];
		}
	}

	public enum ExecutionKind {
		OUTPUT,
		FINISHED,
		FAILED,
		STOPPED;

		public static ExecutionKind byId(int id) {
			if (id < 0 || id >= values().length) {
				throw new IllegalArgumentException("未知运行消息类型：" + id);
			}
			return values()[id];
		}
	}

	public enum ClientAction {
		MESSAGE,
		TOAST,
		NOTICE,
		CLOSE_NOTICE,
		OPEN_URL_PROMPT,
		OPEN_IDE;

		public static ClientAction byId(int id) {
			if (id < 0 || id >= values().length) {
				throw new IllegalArgumentException("未知客户端操作类型：" + id);
			}
			return values()[id];
		}
	}

	public record Request(long requestId, int actionId, String name, String body)
			implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(McLuaIdeMod.id("ide_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
				Request::write, Request::read);

		public Request(long requestId, Action action, String name, String body) {
			this(requestId, action.ordinal(), name == null ? "" : name, body == null ? "" : body);
		}

		private static Request read(RegistryFriendlyByteBuf buffer) {
			return new Request(buffer.readLong(), buffer.readVarInt(),
					buffer.readUtf(MAX_NAME_CHARS), buffer.readUtf(MAX_SCRIPT_CHARS));
		}

		private static void write(RegistryFriendlyByteBuf buffer, Request payload) {
			buffer.writeLong(payload.requestId);
			buffer.writeVarInt(payload.actionId);
			buffer.writeUtf(payload.name, MAX_NAME_CHARS);
			buffer.writeUtf(payload.body, MAX_SCRIPT_CHARS);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Response(long requestId, int actionId, boolean success, String data)
			implements CustomPacketPayload {
		public static final Type<Response> TYPE = new Type<>(McLuaIdeMod.id("ide_response"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.of(
				Response::write, Response::read);

		public Response(long requestId, Action action, boolean success, String data) {
			this(requestId, action.ordinal(), success, data == null ? "" : data);
		}

		private static Response read(RegistryFriendlyByteBuf buffer) {
			return new Response(buffer.readLong(), buffer.readVarInt(), buffer.readBoolean(),
					buffer.readUtf(MAX_RESPONSE_CHARS));
		}

		private static void write(RegistryFriendlyByteBuf buffer, Response payload) {
			buffer.writeLong(payload.requestId);
			buffer.writeVarInt(payload.actionId);
			buffer.writeBoolean(payload.success);
			buffer.writeUtf(payload.data, MAX_RESPONSE_CHARS);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ExecutionEvent(long runId, int kindId, String message)
			implements CustomPacketPayload {
		public static final Type<ExecutionEvent> TYPE = new Type<>(McLuaIdeMod.id("execution_event"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ExecutionEvent> CODEC = StreamCodec.of(
				ExecutionEvent::write, ExecutionEvent::read);

		public ExecutionEvent(long runId, ExecutionKind kind, String message) {
			this(runId, kind.ordinal(), message == null ? "" : message);
		}

		private static ExecutionEvent read(RegistryFriendlyByteBuf buffer) {
			return new ExecutionEvent(buffer.readLong(), buffer.readVarInt(),
					buffer.readUtf(MAX_MESSAGE_CHARS));
		}

		private static void write(RegistryFriendlyByteBuf buffer, ExecutionEvent payload) {
			buffer.writeLong(payload.runId);
			buffer.writeVarInt(payload.kindId);
			buffer.writeUtf(payload.message, MAX_MESSAGE_CHARS);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Trusted server-to-client request used only while mandatory-client mode is enabled. */
	public record ClientCommand(int actionId, String first, String second)
			implements CustomPacketPayload {
		public static final Type<ClientCommand> TYPE = new Type<>(McLuaIdeMod.id("client_command"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClientCommand> CODEC = StreamCodec.of(
				ClientCommand::write, ClientCommand::read);

		public ClientCommand(ClientAction action, String first, String second) {
			this(action.ordinal(), first == null ? "" : first, second == null ? "" : second);
		}

		private static ClientCommand read(RegistryFriendlyByteBuf buffer) {
			return new ClientCommand(buffer.readVarInt(), buffer.readUtf(MAX_CLIENT_TEXT_CHARS),
					buffer.readUtf(MAX_CLIENT_TEXT_CHARS));
		}

		private static void write(RegistryFriendlyByteBuf buffer, ClientCommand payload) {
			buffer.writeVarInt(payload.actionId);
			buffer.writeUtf(payload.first, MAX_CLIENT_TEXT_CHARS);
			buffer.writeUtf(payload.second, MAX_CLIENT_TEXT_CHARS);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
