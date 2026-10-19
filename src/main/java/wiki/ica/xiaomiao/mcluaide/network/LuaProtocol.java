package wiki.ica.xiaomiao.mcluaide.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.util.concurrent.atomic.AtomicBoolean;

/** Optional client/server protocol used by administrator IDE clients. */
public final class LuaProtocol {
	public static final int VERSION = 38;
	public static final net.minecraft.resources.Identifier CLIENT_CHECK_ID =
			McLuaIdeMod.id("client_check");
	public static final int MAX_NAME_CHARS = 256;
	public static final int MAX_SCRIPT_CHARS = 5_000_000;
	private static final int MAX_CONTROL_CHARS = 100_000;
	public static final int MAX_MESSAGE_CHARS = 131_072;
	public static final int MAX_RESPONSE_CHARS = 524_288;
	public static final int MAX_CLIENT_TEXT_CHARS = 8_192;
	private static final int MAX_PACKET_BYTES = 1_048_576;
	// UTF-8 needs at most 3 bytes per UTF-16 character, plus names and protocol fields.
	// Fabric registerLarge splits and reassembles these packets within this finite bound.
	private static final int MAX_FILE_PACKET_BYTES = 16 * 1024 * 1024;
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();

	private LuaProtocol() {
	}

	public static void registerPayloadTypes() {
		if (!REGISTERED.compareAndSet(false, true)) return;
		PayloadTypeRegistry.serverboundPlay().registerLarge(
				Request.TYPE, Request.CODEC, MAX_FILE_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().registerLarge(
				Response.TYPE, Response.CODEC, MAX_FILE_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().registerLarge(
				ExecutionEvent.TYPE, ExecutionEvent.CODEC, MAX_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().registerLarge(
				ClientCommand.TYPE, ClientCommand.CODEC, MAX_PACKET_BYTES);
		PayloadTypeRegistry.clientboundPlay().register(
				ClientResourcePackRequest.TYPE, ClientResourcePackRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CatalogChanged.TYPE, CatalogChanged.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LuaSeekSound.TYPE, LuaSeekSound.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(LuaMcpFileChanged.TYPE, LuaMcpFileChanged.CODEC, MAX_FILE_PACKET_BYTES);
		PayloadTypeRegistry.serverboundPlay().register(
				ClientResourcePackResponse.TYPE, ClientResourcePackResponse.CODEC);
	}

	public static int requestTextLimit(int actionId) {
		if (actionId < 0 || actionId >= Action.values().length) return MAX_CONTROL_CHARS;
		return switch (Action.byId(actionId)) {
			case SAVE, RUN, RUN_NORMAL, RUN_FUNCTION, SAVE_DATAPACK_FILE, CREATE_DATAPACK_FILE, MCP_TOOL -> MAX_SCRIPT_CHARS;
			default -> MAX_CONTROL_CHARS;
		};
	}

	public static int responseTextLimit(int actionId) {
		if (actionId < 0 || actionId >= Action.values().length) return MAX_RESPONSE_CHARS;
		return switch (Action.byId(actionId)) {
			case READ, READ_DATAPACK_FILE -> MAX_SCRIPT_CHARS;
			default -> MAX_RESPONSE_CHARS;
		};
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
		GET_REGISTRY_CATALOG,
		CREATE_DIRECTORY,
		DELETE_DIRECTORY,
		LIST_DATAPACKS,
		CREATE_DATAPACK,
		READ_DATAPACK_FILE,
		SAVE_DATAPACK_FILE,
		DELETE_DATAPACK_PATH,
		CREATE_DATAPACK_DIRECTORY,
		RENAME_DATAPACK_PATH,
		CREATE_DATAPACK_FILE,
		GET_DIMENSION_JSON,
		VALIDATE_DIMENSION_JSON,
		GET_DATAPACK_JSON_OPTIONS,
		GET_DATAPACK_JSON_SOURCE,
		VALIDATE_DATAPACK_JSON,
		GET_DATAPACK_JSON_CATEGORIES,
		RUN_FUNCTION,
		GET_GIT_CONFIG,
		SAVE_GIT_CONFIG,
		GIT_PUSH,
		READ_DATAPACK_PREVIEW,
		GET_DEBUG_VARIABLES,
		RUN_NORMAL,
		SET_BREAKPOINTS,
		GET_DEBUG_STATE,
		DEBUG_CONTROL,
		SET_DEBUG_GAME_PAUSE,
		SET_DEBUG_PLAYER_MOVEMENT,
		DEBUG_CAMERA_MODE,
		RELOAD_DATAPACKS,
		GET_SERVER_PACK,
		BEGIN_SERVER_PACK_UPLOAD,
		SERVER_PACK_UPLOAD_CHUNK,
		FINISH_SERVER_PACK_UPLOAD,
		CANCEL_SERVER_PACK_UPLOAD,
		SET_SERVER_PACK,
		GET_GIT_HISTORY,
		GET_GIT_DETAIL,
		GIT_COMMIT,
		GIT_RESTORE,
		GITHUB_ACCOUNT,
		GITHUB_CLIENT_ID,
		GITHUB_LOGIN,
		GITHUB_POLL,
		GITHUB_LOGOUT,
		GITHUB_REPOSITORIES,
		GITHUB_SELECT_REPOSITORY,
		GITHUB_CREATE_REPOSITORY,
		GET_MCP_SERVER,
		SET_MCP_SERVER,
		CLEAR_MCP_SERVER_LOGS,
		MCP_TOOL;

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

	public enum ResourcePackAction {
		HAS,
		ENABLE;

		public static ResourcePackAction byId(int id) {
			if (id < 0 || id >= values().length) throw new IllegalArgumentException("未知材质包操作：" + id);
			return values()[id];
		}
	}

	/** No script contents or IDs are pushed; clients re-fetch through permission-checked requests. */
	public record CatalogChanged() implements CustomPacketPayload {
		public static final Type<CatalogChanged> TYPE = new Type<>(McLuaIdeMod.id("catalog_changed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CatalogChanged> CODEC =
				StreamCodec.of((buffer, payload) -> { }, buffer -> new CatalogChanged());
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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
			long id = buffer.readLong();
			int action = buffer.readVarInt();
			return new Request(id, action, buffer.readUtf(MAX_NAME_CHARS), buffer.readUtf(requestTextLimit(action)));
		}

		private static void write(RegistryFriendlyByteBuf buffer, Request payload) {
			buffer.writeLong(payload.requestId);
			buffer.writeVarInt(payload.actionId);
			buffer.writeUtf(payload.name, MAX_NAME_CHARS);
			buffer.writeUtf(payload.body, requestTextLimit(payload.actionId));
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
			long id = buffer.readLong();
			int action = buffer.readVarInt();
			return new Response(id, action, buffer.readBoolean(), buffer.readUtf(responseTextLimit(action)));
		}

		private static void write(RegistryFriendlyByteBuf buffer, Response payload) {
			buffer.writeLong(payload.requestId);
			buffer.writeVarInt(payload.actionId);
			buffer.writeBoolean(payload.success);
			buffer.writeUtf(payload.data, responseTextLimit(payload.actionId));
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

	/** Authenticated server-to-client query for local resource-pack state. */
	public record ClientResourcePackRequest(long requestId, int actionId, String packName)
			implements CustomPacketPayload {
		public static final Type<ClientResourcePackRequest> TYPE =
				new Type<>(McLuaIdeMod.id("client_resource_pack_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClientResourcePackRequest> CODEC = StreamCodec.of(
				ClientResourcePackRequest::write, ClientResourcePackRequest::read);

		public ClientResourcePackRequest(long requestId, ResourcePackAction action, String packName) {
			this(requestId, action.ordinal(), packName == null ? "" : packName);
		}

		private static ClientResourcePackRequest read(RegistryFriendlyByteBuf buffer) {
			return new ClientResourcePackRequest(buffer.readLong(), buffer.readVarInt(),
					buffer.readUtf(MAX_NAME_CHARS));
		}

		private static void write(RegistryFriendlyByteBuf buffer, ClientResourcePackRequest payload) {
			buffer.writeLong(payload.requestId);
			buffer.writeVarInt(payload.actionId);
			buffer.writeUtf(payload.packName, MAX_NAME_CHARS);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client response matched to both a random request id and the expected player UUID. */
	public record ClientResourcePackResponse(long requestId, boolean success, boolean enabled, String message)
			implements CustomPacketPayload {
		public static final Type<ClientResourcePackResponse> TYPE =
				new Type<>(McLuaIdeMod.id("client_resource_pack_response"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClientResourcePackResponse> CODEC = StreamCodec.of(
				ClientResourcePackResponse::write, ClientResourcePackResponse::read);

		private static ClientResourcePackResponse read(RegistryFriendlyByteBuf buffer) {
			return new ClientResourcePackResponse(buffer.readLong(), buffer.readBoolean(), buffer.readBoolean(),
					buffer.readUtf(MAX_CLIENT_TEXT_CHARS));
		}

		private static void write(RegistryFriendlyByteBuf buffer, ClientResourcePackResponse payload) {
			buffer.writeLong(payload.requestId);
			buffer.writeBoolean(payload.success);
			buffer.writeBoolean(payload.enabled);
			buffer.writeUtf(payload.message == null ? "" : payload.message, MAX_CLIENT_TEXT_CHARS);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
