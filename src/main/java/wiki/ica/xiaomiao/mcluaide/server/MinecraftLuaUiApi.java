package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Server-authored vanilla dialogs and their authenticated Lua event bridge. */
public final class MinecraftLuaUiApi {
	private static final Identifier ACTION_ID = McLuaIdeMod.id("ui_action");
	private static final String TOKEN_KEY = "_mc_lua_token";
	private static final String UI_KEY = "_mc_lua_ui";
	private static final String ACTION_KEY = "_mc_lua_action";
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	private static final int API_TIMEOUT_SECONDS = 10;
	private static final int MAX_BODY_LINES = 32;
	private static final int MAX_INPUTS = 32;
	private static final int MAX_BUTTONS = 64;
	private static final int MAX_DEFINITIONS = 128;
	private static final Map<ServerScriptManager.ScriptExecution, Map<String, UiDefinition>> DEFINITIONS =
			new IdentityHashMap<>();
	private static final Map<UUID, UiSession> SESSIONS = new HashMap<>();
	private static final Map<UUID, UUID> PLAYER_TOKENS = new HashMap<>();

	private MinecraftLuaUiApi() {
	}

	static void install(LuaTable mc, MinecraftServer server, ServerScriptManager.ScriptExecution execution) {
		LuaTable api = new LuaTable();
		bind(api, "available", args -> LuaValue.TRUE);
		bind(api, "register", args -> {
			String uiId = safeId(args.checkjstring(1), "界面 ID");
			UiDefinition definition = parseDefinition(args.checktable(2));
			synchronized (MinecraftLuaUiApi.class) {
				Map<String, UiDefinition> registered = definitions(execution);
				if (!registered.containsKey(uiId) && registered.size() >= MAX_DEFINITIONS) {
					throw new LuaError("每个脚本最多只能注册 " + MAX_DEFINITIONS + " 个界面");
				}
				registered.put(uiId, definition);
			}
			return LuaValue.TRUE;
		});
		bind(api, "unregister", args -> {
			String uiId = safeId(args.checkjstring(1), "界面 ID");
			synchronized (MinecraftLuaUiApi.class) {
				Map<String, UiDefinition> registered = DEFINITIONS.get(execution);
				return LuaValue.valueOf(registered != null && registered.remove(uiId) != null);
			}
		});
		bind(api, "clearRegistered", args -> {
			synchronized (MinecraftLuaUiApi.class) {
				Map<String, UiDefinition> registered = DEFINITIONS.get(execution);
				int count = registered == null ? 0 : registered.size();
				if (registered != null) registered.clear();
				return LuaValue.valueOf(count);
			}
		});
		bind(api, "list", args -> {
			List<String> ids;
			synchronized (MinecraftLuaUiApi.class) {
				Map<String, UiDefinition> registered = DEFINITIONS.get(execution);
				ids = registered == null ? List.of() : List.copyOf(registered.keySet());
			}
			LuaTable result = new LuaTable();
			for (int index = 0; index < ids.size(); index++) result.set(index + 1, ids.get(index));
			return result;
		});
		bind(api, "open", args -> {
			UUID playerUuid = parseUuid(args.checkjstring(1));
			String uiId = safeId(args.checkjstring(2), "界面 ID");
			UiDefinition definition;
			synchronized (MinecraftLuaUiApi.class) {
				Map<String, UiDefinition> registered = DEFINITIONS.get(execution);
				definition = registered == null ? null : registered.get(uiId);
			}
			if (definition == null) throw new LuaError("尚未注册界面：" + uiId);
			return LuaValue.valueOf(open(server, execution, playerUuid, uiId, definition));
		});
		bind(api, "show", args -> {
			UUID playerUuid = parseUuid(args.checkjstring(1));
			String uiId = safeId(args.checkjstring(2), "界面 ID");
			UiDefinition definition = parseDefinition(args.checktable(3));
			return LuaValue.valueOf(open(server, execution, playerUuid, uiId, definition));
		});
		bind(api, "close", args -> {
			UUID playerUuid = parseUuid(args.checkjstring(1));
			return LuaValue.valueOf(close(server, playerUuid));
		});
		setNamespace(mc, "UI", api);
	}

	/** Called by the common-packet mixin after the packet has reached the server thread. */
	public static void handleCustomClick(ServerPlayer player, ServerboundCustomClickActionPacket packet) {
		if (!ACTION_ID.equals(packet.id())) return;
		CompoundTag payload = packet.payload().flatMap(Tag::asCompound).orElse(null);
		if (payload == null) return;
		UUID token;
		try {
			token = UUID.fromString(payload.getStringOr(TOKEN_KEY, ""));
		} catch (IllegalArgumentException error) {
			return;
		}
		String actionId = payload.getStringOr(ACTION_KEY, "");
		UiSession session;
		synchronized (MinecraftLuaUiApi.class) {
			session = SESSIONS.get(token);
			if (session == null || !session.playerUuid().equals(player.getUUID())
					|| !session.actions().contains(actionId)
					|| !ServerScriptManager.isActive(session.execution())) {
				return;
			}
		}
		Map<String, Object> inputs = new LinkedHashMap<>();
		try {
			for (Map.Entry<String, InputDecoder> entry : session.inputs().entrySet()) {
				Tag value = payload.get(entry.getKey());
				if (value == null) return;
				inputs.put(entry.getKey(), entry.getValue().decode(value));
			}
		} catch (IllegalArgumentException error) {
			return;
		}
		synchronized (MinecraftLuaUiApi.class) {
			if (SESSIONS.get(token) != session) return;
			SESSIONS.remove(token);
			PLAYER_TOKENS.remove(player.getUUID(), token);
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("playerUuid", player.getUUID().toString());
		event.put("playerName", player.getName().getString());
		event.put("uiId", session.uiId());
		event.put("actionId", actionId);
		event.put("inputs", inputs);
		ServerScriptManager.emitEvent("uiAction", event);
	}

	static void stopExecution(ServerScriptManager.ScriptExecution execution, boolean closeDialogs) {
		List<ServerPlayer> players = new ArrayList<>();
		synchronized (MinecraftLuaUiApi.class) {
			DEFINITIONS.remove(execution);
			List<UUID> tokens = SESSIONS.entrySet().stream()
					.filter(entry -> entry.getValue().execution() == execution)
					.map(Map.Entry::getKey).toList();
			for (UUID token : tokens) {
				UiSession removed = SESSIONS.remove(token);
				if (removed != null) {
					PLAYER_TOKENS.remove(removed.playerUuid(), token);
					if (closeDialogs) players.add(removed.player());
				}
			}
		}
		if (closeDialogs) players.forEach(player -> player.connection.send(ClientboundClearDialogPacket.INSTANCE));
	}

	private static boolean open(MinecraftServer server, ServerScriptManager.ScriptExecution execution,
			UUID playerUuid, String uiId, UiDefinition definition) {
		return onServer(server, current -> {
			if (!ServerScriptManager.isActive(execution)) throw new LuaError("当前脚本已停止");
			ServerPlayer player = current.getPlayerList().getPlayer(playerUuid);
			if (player == null) throw new LuaRecoverableError("玩家不在线：" + playerUuid);
			UUID token = UUID.randomUUID();
			UiSession session = new UiSession(token, execution, player, playerUuid, uiId,
					definition.actionIds(), definition.inputDecoders());
			synchronized (MinecraftLuaUiApi.class) {
				UUID previous = PLAYER_TOKENS.put(playerUuid, token);
				if (previous != null) SESSIONS.remove(previous);
				SESSIONS.put(token, session);
			}
			player.connection.send(new ClientboundShowDialogPacket(Holder.direct(definition.dialog(token, uiId))));
			return true;
		});
	}

	private static boolean close(MinecraftServer server, UUID playerUuid) {
		return onServer(server, current -> {
			ServerPlayer player = current.getPlayerList().getPlayer(playerUuid);
			if (player == null) throw new LuaRecoverableError("玩家不在线：" + playerUuid);
			boolean hadSession;
			synchronized (MinecraftLuaUiApi.class) {
				UUID token = PLAYER_TOKENS.remove(playerUuid);
				hadSession = token != null && SESSIONS.remove(token) != null;
			}
			player.connection.send(ClientboundClearDialogPacket.INSTANCE);
			return hadSession;
		});
	}

	private static UiDefinition parseDefinition(LuaTable table) {
		String typeName = optionalString(table, "type", "form", 32).toLowerCase();
		DialogType type = switch (typeName) {
			case "form" -> DialogType.FORM;
			case "notice" -> DialogType.NOTICE;
			case "confirmation" -> DialogType.CONFIRMATION;
			default -> throw new LuaError("definition.type 只能是 form、notice 或 confirmation");
		};
		String title = optionalString(table, "title", "Lua 界面", 256);
		boolean canClose = optionalBoolean(table, "canCloseWithEscape", true);
		boolean pause = optionalBoolean(table, "pause", false);
		String afterName = optionalString(table, "afterAction", "close", 32).toLowerCase();
		DialogAction afterAction = switch (afterName) {
			case "close" -> DialogAction.CLOSE;
			case "none" -> DialogAction.NONE;
			case "wait_for_response", "wait" -> DialogAction.WAIT_FOR_RESPONSE;
			default -> throw new LuaError("definition.afterAction 只能是 close、none 或 wait_for_response");
		};
		if (pause && afterAction == DialogAction.NONE) {
			throw new LuaError("暂停游戏的界面不能使用 afterAction = none，否则客户端可能无法恢复");
		}
		List<DialogBody> body = parseBody(table.get("body"));
		ParsedInputs parsedInputs = parseInputs(table.get("inputs"));
		List<ButtonSpec> buttons = parseButtons(table.get("buttons"));
		if (buttons.isEmpty()) buttons = switch (type) {
			case FORM, NOTICE -> List.of(new ButtonSpec("close", "确定", null, 150));
			case CONFIRMATION -> List.of(
					new ButtonSpec("yes", "确定", null, 150),
					new ButtonSpec("no", "取消", null, 150));
		};
		if (type == DialogType.CONFIRMATION && buttons.size() != 2) {
			throw new LuaError("confirmation 界面必须正好有两个 buttons");
		}
		if (type == DialogType.NOTICE && buttons.size() != 1) {
			throw new LuaError("notice 界面必须正好有一个 button");
		}
		Set<String> actionIds = new LinkedHashSet<>();
		for (ButtonSpec button : buttons) {
			if (!actionIds.add(button.id())) throw new LuaError("按钮 ID 重复：" + button.id());
		}
		ButtonSpec exit = parseExit(table.get("exit"));
		if (exit != null && !actionIds.add(exit.id())) throw new LuaError("退出按钮 ID 与普通按钮重复：" + exit.id());
		int columns = optionalInt(table, "columns", 1, 1, 8);
		return new UiDefinition(type, title, canClose, pause, afterAction, body,
				parsedInputs.inputs(), parsedInputs.decoders(), buttons, exit, columns, Set.copyOf(actionIds));
	}

	private static List<DialogBody> parseBody(LuaValue value) {
		if (value.isnil()) return List.of();
		List<DialogBody> body = new ArrayList<>();
		if (value.isstring()) {
			body.add(new PlainMessage(Component.literal(limited(value.checkjstring(), "正文", 4_096)), 300));
			return List.copyOf(body);
		}
		LuaTable lines = value.checktable();
		if (lines.length() > MAX_BODY_LINES) throw new LuaError("正文最多只能有 " + MAX_BODY_LINES + " 段");
		for (int index = 1; index <= lines.length(); index++) {
			String line = limited(lines.get(index).checkjstring(), "正文第 " + index + " 段", 4_096);
			body.add(new PlainMessage(Component.literal(line), 300));
		}
		return List.copyOf(body);
	}

	private static ParsedInputs parseInputs(LuaValue value) {
		if (value.isnil()) return new ParsedInputs(List.of(), Map.of());
		LuaTable inputTable = value.checktable();
		if (inputTable.length() > MAX_INPUTS) throw new LuaError("输入项最多只能有 " + MAX_INPUTS + " 个");
		List<Input> inputs = new ArrayList<>();
		Map<String, InputDecoder> decoders = new LinkedHashMap<>();
		for (int index = 1; index <= inputTable.length(); index++) {
			LuaTable spec = inputTable.get(index).checktable();
			String key = safeId(requiredString(spec, "key", "输入项 key", 64), "输入项 key");
			if (key.startsWith("_mc_lua_")) throw new LuaError("输入项 key 不能使用内部前缀 _mc_lua_");
			if (decoders.containsKey(key)) throw new LuaError("输入项 key 重复：" + key);
			String kind = optionalString(spec, "type", "text", 32).toLowerCase();
			String label = optionalString(spec, "label", key, 256);
			int width = optionalInt(spec, "width", 300, 1, 1_024);
			switch (kind) {
				case "text" -> {
					String initial = optionalString(spec, "initial", "", 8_192);
					int maxLength = optionalInt(spec, "maxLength", 256, 1, 8_192);
					if (initial.length() > maxLength) throw new LuaError("输入项 " + key + " 的 initial 超过 maxLength");
					boolean labelVisible = optionalBoolean(spec, "labelVisible", true);
					boolean multiline = optionalBoolean(spec, "multiline", false);
					Optional<TextInput.MultilineOptions> options = multiline
							? Optional.of(new TextInput.MultilineOptions(
							optionalInteger(spec, "maxLines", 1, 128), optionalInteger(spec, "height", 1, 512)))
							: Optional.empty();
					inputs.add(new Input(key, new TextInput(width, Component.literal(label), labelVisible,
							initial, maxLength, options)));
					decoders.put(key, InputDecoder.TEXT);
				}
				case "boolean" -> {
					boolean initial = optionalBoolean(spec, "initial", false);
					String onTrue = optionalString(spec, "onTrue", "true", 128);
					String onFalse = optionalString(spec, "onFalse", "false", 128);
					if (onTrue.equals(onFalse)) throw new LuaError("输入项 " + key + " 的 onTrue 与 onFalse 不能相同");
					inputs.add(new Input(key, new BooleanInput(Component.literal(label), initial, onTrue, onFalse)));
					decoders.put(key, tag -> {
						String raw = requireStringTag(tag);
						if (!raw.equals(onTrue) && !raw.equals(onFalse)) throw new IllegalArgumentException("invalid boolean");
						return raw.equals(onTrue);
					});
				}
				case "number" -> {
					float minimum = optionalFloat(spec, "min", 0);
					float maximum = optionalFloat(spec, "max", 100);
					if (!(minimum < maximum)) throw new LuaError("输入项 " + key + " 的 min 必须小于 max");
					Optional<Float> initial = optionalFloatValue(spec, "initial");
					Optional<Float> step = optionalFloatValue(spec, "step");
					if (initial.isPresent() && (initial.get() < minimum || initial.get() > maximum)) {
						throw new LuaError("输入项 " + key + " 的 initial 必须位于 min 与 max 之间");
					}
					if (step.isPresent() && step.get() <= 0) throw new LuaError("输入项 " + key + " 的 step 必须大于 0");
					String labelFormat = optionalString(spec, "labelFormat", "options.generic_value", 256);
					inputs.add(new Input(key, new NumberRangeInput(width, Component.literal(label), labelFormat,
							new NumberRangeInput.RangeInfo(minimum, maximum, initial, step))));
					decoders.put(key, InputDecoder.NUMBER);
				}
				case "select" -> {
					LuaTable options = spec.get("options").checktable();
					if (options.length() < 1 || options.length() > 64) {
						throw new LuaError("输入项 " + key + " 的 options 数量必须在 1 到 64 之间");
					}
					List<SingleOptionInput.Entry> entries = new ArrayList<>();
					Set<String> optionIds = new LinkedHashSet<>();
					boolean hasExplicitInitial = false;
					for (int optionIndex = 1; optionIndex <= options.length(); optionIndex++) {
						if (!options.get(optionIndex).checktable().get("initial").isnil()) {
							hasExplicitInitial = true;
							break;
						}
					}
					boolean hasInitial = false;
					for (int optionIndex = 1; optionIndex <= options.length(); optionIndex++) {
						LuaTable option = options.get(optionIndex).checktable();
						String optionId = safeId(requiredString(option, "id", "选项 ID", 64), "选项 ID");
						if (!optionIds.add(optionId)) throw new LuaError("输入项 " + key + " 的选项 ID 重复：" + optionId);
						boolean selected = optionalBoolean(option, "initial", !hasExplicitInitial && optionIndex == 1);
						if (selected && hasInitial) throw new LuaError("输入项 " + key + " 只能有一个 initial 选项");
						if (selected) hasInitial = true;
						String text = optionalString(option, "text", optionId, 256);
						entries.add(new SingleOptionInput.Entry(optionId, Optional.of(Component.literal(text)), selected));
					}
					inputs.add(new Input(key, new SingleOptionInput(width, List.copyOf(entries),
							Component.literal(label), optionalBoolean(spec, "labelVisible", true))));
					Set<String> allowedOptions = Set.copyOf(optionIds);
					decoders.put(key, tag -> {
						String raw = requireStringTag(tag);
						if (!allowedOptions.contains(raw)) throw new IllegalArgumentException("invalid option");
						return raw;
					});
				}
				default -> throw new LuaError("输入项 " + key + " 的 type 只能是 text、boolean、number 或 select");
			}
		}
		return new ParsedInputs(List.copyOf(inputs), Map.copyOf(decoders));
	}

	private static List<ButtonSpec> parseButtons(LuaValue value) {
		if (value.isnil()) return List.of();
		LuaTable table = value.checktable();
		if (table.length() > MAX_BUTTONS) throw new LuaError("按钮最多只能有 " + MAX_BUTTONS + " 个");
		List<ButtonSpec> buttons = new ArrayList<>();
		for (int index = 1; index <= table.length(); index++) {
			buttons.add(parseButton(table.get(index).checktable(), "按钮 " + index));
		}
		return List.copyOf(buttons);
	}

	private static ButtonSpec parseExit(LuaValue value) {
		return value.isnil() ? null : parseButton(value.checktable(), "退出按钮");
	}

	private static ButtonSpec parseButton(LuaTable table, String label) {
		String id = safeId(requiredString(table, "id", label + " ID", 64), label + " ID");
		String text = optionalString(table, "label", id, 256);
		String tooltip = table.get("tooltip").isnil() ? null : optionalString(table, "tooltip", "", 1_024);
		int width = optionalInt(table, "width", 150, 1, 1_024);
		return new ButtonSpec(id, text, tooltip, width);
	}

	private static Map<String, UiDefinition> definitions(ServerScriptManager.ScriptExecution execution) {
		return DEFINITIONS.computeIfAbsent(execution, ignored -> new LinkedHashMap<>());
	}

	private static String requiredString(LuaTable table, String key, String label, int maximum) {
		LuaValue value = table.get(key);
		if (value.isnil()) throw new LuaError(label + "不能为空");
		return limited(value.checkjstring(), label, maximum);
	}

	private static String optionalString(LuaTable table, String key, String fallback, int maximum) {
		LuaValue value = table.get(key);
		return value.isnil() ? fallback : limited(value.checkjstring(), key, maximum);
	}

	private static boolean optionalBoolean(LuaTable table, String key, boolean fallback) {
		LuaValue value = table.get(key);
		return value.isnil() ? fallback : value.checkboolean();
	}

	private static int optionalInt(LuaTable table, String key, int fallback, int minimum, int maximum) {
		LuaValue value = table.get(key);
		int result = value.isnil() ? fallback : value.checkint();
		if (result < minimum || result > maximum) {
			throw new LuaError(key + " 必须在 " + minimum + " 到 " + maximum + " 之间");
		}
		return result;
	}

	private static Optional<Integer> optionalInteger(LuaTable table, String key, int minimum, int maximum) {
		LuaValue value = table.get(key);
		return value.isnil() ? Optional.empty() : Optional.of(optionalInt(table, key, 0, minimum, maximum));
	}

	private static float optionalFloat(LuaTable table, String key, float fallback) {
		return optionalFloatValue(table, key).orElse(fallback);
	}

	private static Optional<Float> optionalFloatValue(LuaTable table, String key) {
		LuaValue value = table.get(key);
		if (value.isnil()) return Optional.empty();
		float result = (float) value.checkdouble();
		if (!Float.isFinite(result)) throw new LuaError(key + " 必须是有限数字");
		return Optional.of(result);
	}

	private static String safeId(String value, String label) {
		if (!SAFE_ID.matcher(value).matches()) {
			throw new LuaError(label + " 只能包含字母、数字、下划线、点和连字符，长度 1-64");
		}
		return value;
	}

	private static String limited(String value, String label, int maximum) {
		if (value.length() > maximum) throw new LuaError(label + " 不能超过 " + maximum + " 个字符");
		return value;
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new LuaError("玩家 UUID 格式错误：" + raw);
		}
	}

	private static String requireStringTag(Tag tag) {
		if (tag.getId() != Tag.TAG_STRING) throw new IllegalArgumentException("expected string");
		return tag.asString().orElseThrow(() -> new IllegalArgumentException("expected string"));
	}

	private static <T> T onServer(MinecraftServer server, Function<MinecraftServer, T> action) {
		if (server.isSameThread()) return action.apply(server);
		CompletableFuture<T> future = server.submit(() -> action.apply(server));
		try {
			return future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new LuaError("脚本已停止");
		} catch (TimeoutException error) {
			throw new LuaError("等待 Minecraft 服务端响应超时");
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			throw cause instanceof LuaError luaError ? luaError
					: new LuaError(cause == null || cause.getMessage() == null ? "打开界面失败" : cause.getMessage());
		}
	}

	private static void bind(LuaTable table, String name, LuaCallable callable) {
		table.set(name, new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return callable.invoke(args);
			}
		});
	}

	private static void setNamespace(LuaTable mc, String name, LuaTable namespace) {
		mc.set(name, namespace);
		mc.set(name.toLowerCase(), namespace);
	}

	@FunctionalInterface
	private interface LuaCallable {
		Varargs invoke(Varargs args);
	}

	@FunctionalInterface
	private interface InputDecoder {
		InputDecoder TEXT = MinecraftLuaUiApi::requireStringTag;
		InputDecoder NUMBER = tag -> {
			double value = tag.asNumber().map(Number::doubleValue)
					.orElseThrow(() -> new IllegalArgumentException("expected number"));
			if (!Double.isFinite(value)) throw new IllegalArgumentException("expected finite number");
			return value;
		};

		Object decode(Tag value);
	}

	private enum DialogType { FORM, NOTICE, CONFIRMATION }

	private record ParsedInputs(List<Input> inputs, Map<String, InputDecoder> decoders) {
	}

	private record ButtonSpec(String id, String label, String tooltip, int width) {
		ActionButton create(UUID token, String uiId) {
			CompoundTag additions = new CompoundTag();
			additions.putString(TOKEN_KEY, token.toString());
			additions.putString(UI_KEY, uiId);
			additions.putString(ACTION_KEY, id);
			CommonButtonData data = new CommonButtonData(Component.literal(label),
					Optional.ofNullable(tooltip).map(Component::literal), width);
			return new ActionButton(data, Optional.of(new CustomAll(ACTION_ID, Optional.of(additions))));
		}
	}

	private record UiDefinition(DialogType type, String title, boolean canCloseWithEscape, boolean pause,
			DialogAction afterAction, List<DialogBody> body, List<Input> inputs,
			Map<String, InputDecoder> inputDecoders, List<ButtonSpec> buttons, ButtonSpec exit,
			int columns, Set<String> actionIds) {
		Dialog dialog(UUID token, String uiId) {
			CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(),
					canCloseWithEscape, pause, afterAction, body, inputs);
			List<ActionButton> actions = buttons.stream().map(button -> button.create(token, uiId)).toList();
			return switch (type) {
				case FORM -> new MultiActionDialog(common, actions,
						Optional.ofNullable(exit).map(button -> button.create(token, uiId)), columns);
				case NOTICE -> new NoticeDialog(common, actions.getFirst());
				case CONFIRMATION -> new ConfirmationDialog(common, actions.get(0), actions.get(1));
			};
		}
	}

	private record UiSession(UUID token, ServerScriptManager.ScriptExecution execution, ServerPlayer player,
			UUID playerUuid, String uiId, Set<String> actions, Map<String, InputDecoder> inputs) {
	}
}
