package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ServerScriptManager {
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,48}");
	private static final Path SCRIPT_DIRECTORY = FabricLoader.getInstance()
			.getConfigDir().resolve("mc_lua_ide").resolve("scripts");
	private static final String STOP_SIGNAL = "__MC_LUA_IDE_STOPPED__";
	static final List<String> EVENT_NAMES = List.of(
			"blockPlaced", "blockBroken", "blockAttacked", "blockUsed", "itemUsed",
			"entityAttacked", "entityUsed", "entityLoaded", "entityUnloaded", "entityChangedDimension",
			"entityDamaged", "entityDied", "entityKilledOther", "equipmentChanged",
			"playerJoined", "playerLeft", "playerRespawned", "playerChangedDimension",
			"effectAdded", "effectRemoved", "sleepStarted", "sleepStopped",
			"chunkLoaded", "chunkGenerated", "chunkUnloaded", "levelLoaded", "levelUnloaded",
			"serverTick", "levelTick", "chat", "command", "uiAction");
	private static final Set<String> EVENT_NAME_SET = Set.copyOf(EVENT_NAMES);
	private static ScriptExecution activeExecution;

	private ServerScriptManager() {
	}

	public static List<String> listScripts() throws IOException {
		ensureDirectory();
		try (Stream<Path> paths = Files.list(SCRIPT_DIRECTORY)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".lua"))
					.map(path -> path.getFileName().toString().replaceFirst("\\.lua$", ""))
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		}
	}

	public static String read(String name) throws IOException {
		String source = Files.readString(resolve(name), StandardCharsets.UTF_8);
		if (source.length() > LuaProtocol.MAX_SCRIPT_CHARS) {
			throw new IOException("脚本内容超过 " + LuaProtocol.MAX_SCRIPT_CHARS + " 个字符");
		}
		return source;
	}

	public static void save(String name, String source) throws IOException {
		int maximum = LuaLimitConfig.current().maxScriptChars();
		if (source == null || source.length() > maximum) {
			throw new IllegalArgumentException("脚本内容不能超过当前限制的 " + maximum + " 个字符");
		}
		ensureDirectory();
		Files.writeString(resolve(name), source, StandardCharsets.UTF_8);
	}

	public static boolean delete(String name) throws IOException {
		return Files.deleteIfExists(resolve(name));
	}

	public static synchronized ScriptExecution start(
			MinecraftServer server, UUID owner, String name, String source,
			Consumer<String> output, Consumer<ExecutionResult> completion) {
		if (activeExecution != null && activeExecution.isRunning()) {
			throw new IllegalStateException("已有脚本正在运行：" + activeExecution.name());
		}
		LuaLimits limits = LuaLimitConfig.current();
		if (source == null || source.length() > limits.maxScriptChars()) {
			throw new IllegalArgumentException("脚本内容不能超过当前限制的 "
					+ limits.maxScriptChars() + " 个字符");
		}
		ScriptExecution execution = new ScriptExecution(owner, name, limits);
		activeExecution = execution;
		execution.thread = Thread.startVirtualThread(() -> execute(execution, server, source, output, completion));
		return execution;
	}

	public static ScriptExecution start(
			MinecraftServer server, UUID owner, String name,
			Consumer<String> output, Consumer<ExecutionResult> completion) throws IOException {
		return start(server, owner, name, read(name), output, completion);
	}

	public static synchronized boolean stopActive() {
		if (activeExecution == null || !activeExecution.isRunning()) {
			return false;
		}
		activeExecution.stop();
		return true;
	}

	public static synchronized boolean stopOwnedBy(UUID owner) {
		if (activeExecution == null || !activeExecution.isRunning()
				|| !activeExecution.owner().equals(owner)) {
			return false;
		}
		activeExecution.stop();
		return true;
	}

	public static synchronized String activeScriptName() {
		return activeExecution != null && activeExecution.isRunning() ? activeExecution.name() : null;
	}

	static synchronized boolean isActive(ScriptExecution execution) {
		return activeExecution == execution && execution != null && execution.isRunning();
	}

	private static void execute(
			ScriptExecution execution, MinecraftServer server, String source,
			Consumer<String> output, Consumer<ExecutionResult> completion) {
		ExecutionResult result;
		try {
			Globals globals = JsePlatform.debugGlobals();
			LuaValue stopHook = new ZeroArgFunction() {
				@Override
				public LuaValue call() {
					if (execution.cancelled.get() || Thread.currentThread().isInterrupted()) {
						throw new LuaError(STOP_SIGNAL);
					}
					execution.checkRuntime();
					return LuaValue.NIL;
				}
			};
			globals.get("debug").get("sethook").call(stopHook, LuaValue.valueOf(""),
					LuaValue.valueOf(execution.limits().instructionHookInterval()));
			sandbox(globals, execution.limits());
			globals.set("print", new VarArgFunction() {
				@Override
				public Varargs invoke(Varargs values) {
					execution.emitOutput(values, output);
					return LuaValue.NIL;
				}
			});
			MinecraftLuaApi.install(globals, server, execution, execution.limits());
			globals.load(source, execution.name() + ".lua").call();
			if (execution.hasEventCallbacks()) {
				output.accept("● 事件监听已启动；停止脚本可注销全部事件");
				execution.runEventLoop();
			}
			result = new ExecutionResult(false, null);
		} catch (LuaError error) {
			String message = error.getMessage();
			if (execution.cancelled.get() || message != null && message.contains(STOP_SIGNAL)) {
				result = new ExecutionResult(true, null);
			} else {
				result = new ExecutionResult(false, message);
			}
		} catch (Throwable error) {
			result = new ExecutionResult(execution.cancelled.get(), execution.cancelled.get() ? null : error.getMessage());
		} finally {
			execution.finish();
			synchronized (ServerScriptManager.class) {
				if (activeExecution == execution) {
					activeExecution = null;
				}
			}
		}
		completion.accept(result);
	}

	public static String validate(String name, String source) {
		try {
			JsePlatform.standardGlobals().load(source, name + ".lua");
			return null;
		} catch (LuaError error) {
			return error.getMessage();
		} catch (Exception error) {
			return error.getMessage();
		}
	}

	private static void sandbox(Globals globals, LuaLimits limits) {
		// High-risk libraries stay disabled by default, but server owners may explicitly enable them.
		if (!limits.allowJavaInterop()) globals.set("luajava", LuaValue.NIL);
		if (!limits.allowUnrestrictedFileIo()) {
			globals.set("io", LuaValue.NIL);
			globals.set("dofile", LuaValue.NIL);
			globals.set("loadfile", LuaValue.NIL);
		}
		if (!limits.allowOperatingSystem()) globals.set("os", LuaValue.NIL);
		if (!limits.allowPackageLoading()) {
			globals.set("package", LuaValue.NIL);
			globals.set("require", LuaValue.NIL);
		}
		if (!limits.allowDebugLibrary()) globals.set("debug", LuaValue.NIL);
		if (!limits.allowDynamicCode()) {
			globals.set("load", LuaValue.NIL);
			globals.set("loadstring", LuaValue.NIL);
		}
		if (!limits.allowCoroutine()) globals.set("coroutine", LuaValue.NIL);
		if (!limits.allowMetatables()) {
			globals.set("getmetatable", LuaValue.NIL);
			globals.set("setmetatable", LuaValue.NIL);
			globals.set("rawget", LuaValue.NIL);
			globals.set("rawset", LuaValue.NIL);
			globals.set("rawequal", LuaValue.NIL);
		}
		if (!limits.allowGcControl()) globals.set("collectgarbage", LuaValue.NIL);
	}

	public record ExecutionResult(boolean stopped, String error) {
		public boolean succeeded() {
			return !stopped && error == null;
		}
	}

	public static final class ScriptExecution {
		private final UUID owner;
		private final String name;
		private final LuaLimits limits;
		private final long deadlineNanos;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final ArrayBlockingQueue<GameEvent> eventQueue;
		private final Map<Integer, EventRegistration> eventCallbacks = new LinkedHashMap<>();
		private int nextCallbackId = 1;
		private int outputLines;
		private volatile boolean running = true;
		private volatile Thread thread;

		private ScriptExecution(UUID owner, String name, LuaLimits limits) {
			this.owner = owner;
			this.name = name;
			this.limits = limits;
			this.deadlineNanos = limits.maxRuntimeSeconds() == 0 ? Long.MAX_VALUE
					: System.nanoTime() + TimeUnit.SECONDS.toNanos(limits.maxRuntimeSeconds());
			this.eventQueue = new ArrayBlockingQueue<>(limits.eventQueueCapacity());
		}

		public UUID owner() {
			return owner;
		}

		public String name() {
			return name;
		}

		public boolean isRunning() {
			return running;
		}

		LuaLimits limits() {
			return limits;
		}

		public void stop() {
			cancelled.set(true);
			MinecraftLuaUiApi.stopExecution(this, true);
			synchronized (eventCallbacks) {
				eventCallbacks.clear();
			}
			eventQueue.clear();
			Thread current = thread;
			if (current != null) {
				current.interrupt();
			}
		}

		int registerEvent(String eventName, LuaValue callback, int interval) {
			if (!limits.allowEvents()) throw new LuaError("服务器已禁止 Lua 注册游戏事件");
			if (!EVENT_NAME_SET.contains(eventName)) {
				throw new LuaError("未知事件：" + eventName + "；可使用 MC.Event.names() 查看全部事件");
			}
			if (interval < 1 || interval > 72_000) {
				throw new LuaError("事件间隔必须在 1 到 72000 tick 之间");
			}
			if ((eventName.equals("serverTick") || eventName.equals("levelTick"))
					&& interval < limits.minTickEventInterval()) {
				throw new LuaError("Tick 事件间隔不能小于服务器限制的 "
						+ limits.minTickEventInterval() + " tick");
			}
			synchronized (eventCallbacks) {
				if (eventCallbacks.size() >= limits.maxEventCallbacks()) {
					throw new LuaError("事件监听数量已达到服务器限制：" + limits.maxEventCallbacks());
				}
				int id = nextCallbackId++;
				eventCallbacks.put(id, new EventRegistration(eventName, callback, interval));
				return id;
			}
		}

		boolean unregisterEvent(int id) {
			synchronized (eventCallbacks) {
				return eventCallbacks.remove(id) != null;
			}
		}

		int clearEvents() {
			synchronized (eventCallbacks) {
				int count = eventCallbacks.size();
				eventCallbacks.clear();
				eventQueue.clear();
				return count;
			}
		}

		private boolean hasEventCallbacks() {
			synchronized (eventCallbacks) {
				return !eventCallbacks.isEmpty();
			}
		}

		private boolean accepts(GameEvent event) {
			synchronized (eventCallbacks) {
				Object tick = event.data().get("tick");
				return eventCallbacks.values().stream().anyMatch(registration -> {
					if (!registration.eventName().equals(event.type())) return false;
					return registration.interval() <= 1 || !(tick instanceof Number number)
							|| Math.floorMod(number.longValue(), registration.interval()) == 0;
				});
			}
		}

		private void enqueue(GameEvent event) {
			if (cancelled.get() || !running || !accepts(event)) return;
			if (!eventQueue.offer(event)) {
				eventQueue.poll();
				eventQueue.offer(event);
			}
		}

		private void runEventLoop() throws InterruptedException {
			while (!cancelled.get() && hasEventCallbacks()) {
				checkRuntime();
				GameEvent event;
				if (deadlineNanos == Long.MAX_VALUE) {
					event = eventQueue.take();
				} else {
					long remaining = deadlineNanos - System.nanoTime();
					if (remaining <= 0) {
						checkRuntime();
						return;
					}
					event = eventQueue.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)),
							TimeUnit.NANOSECONDS);
					if (event == null) continue;
				}
				List<EventRegistration> callbacks;
				synchronized (eventCallbacks) {
					callbacks = eventCallbacks.values().stream()
							.filter(registration -> registration.eventName().equals(event.type()))
							.toList();
				}
				for (EventRegistration registration : callbacks) {
					if (cancelled.get()) throw new InterruptedException(STOP_SIGNAL);
					checkRuntime();
					Object tick = event.data().get("tick");
					if (registration.interval() > 1 && tick instanceof Number number
							&& Math.floorMod(number.longValue(), registration.interval()) != 0) {
						continue;
					}
					registration.callback().call(event.toLuaTable());
				}
			}
		}

		private void checkRuntime() {
			if (System.nanoTime() > deadlineNanos) {
				throw new LuaError("Lua 脚本超过服务器限制的最长运行时间："
						+ limits.maxRuntimeSeconds() + " 秒");
			}
		}

		private void emitOutput(Varargs values, Consumer<String> output) {
			if (cancelled.get()) throw new LuaError(STOP_SIGNAL);
			checkRuntime();
			if (++outputLines > limits.maxOutputLines()) {
				throw new LuaError("Lua 输出超过服务器限制的 " + limits.maxOutputLines() + " 行");
			}
			StringBuilder line = new StringBuilder();
			for (int index = 1; index <= values.narg(); index++) {
				if (index > 1) line.append('\t');
				line.append(values.arg(index).tojstring());
				if (line.length() > limits.maxOutputCharsPerLine()) {
					throw new LuaError("Lua 单行输出超过服务器限制的 "
							+ limits.maxOutputCharsPerLine() + " 个字符");
				}
			}
			output.accept(line.toString());
		}

		private void finish() {
			running = false;
			MinecraftLuaUiApi.stopExecution(this, false);
			synchronized (eventCallbacks) {
				eventCallbacks.clear();
			}
			eventQueue.clear();
		}

		private record EventRegistration(String eventName, LuaValue callback, int interval) {
		}
	}

	static void emitEvent(String type, Map<String, ?> data) {
		ScriptExecution execution;
		synchronized (ServerScriptManager.class) {
			execution = activeExecution;
		}
		if (execution == null || !execution.isRunning()) return;
		execution.enqueue(new GameEvent(type, Map.copyOf(data)));
	}

	static void onBlockPlaced(ServerPlayer player, ServerLevel level, BlockPos pos,
			BlockState state, InteractionHand hand) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("playerUuid", player.getUUID().toString());
		data.put("playerName", player.getName().getString());
		data.put("blockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		data.put("x", pos.getX());
		data.put("y", pos.getY());
		data.put("z", pos.getZ());
		data.put("dimension", level.dimension().identifier().toString());
		data.put("hand", hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand");
		var stack = player.getItemInHand(hand);
		data.put("itemId", stack.isEmpty() ? "minecraft:air"
				: BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		data.put("itemCount", stack.isEmpty() ? 0 : stack.getCount());
		data.put("itemComponents", LuaItemStackCodec.toJavaComponents(level.getServer(), stack));
		emitEvent("blockPlaced", data);
	}

	private record GameEvent(String type, Map<String, ?> data) {
		LuaTable toLuaTable() {
			LuaTable event = new LuaTable();
			event.set("type", type);
			for (Map.Entry<String, ?> entry : data.entrySet()) {
				event.set(entry.getKey(), toLuaValue(entry.getValue()));
			}
			return event;
		}
	}

	private static LuaValue toLuaValue(Object value) {
		if (value == null) return LuaValue.NIL;
		if (value instanceof Boolean bool) return LuaValue.valueOf(bool);
		if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
			return LuaValue.valueOf(((Number) value).longValue());
		}
		if (value instanceof Number number) return LuaValue.valueOf(number.doubleValue());
		if (value instanceof Map<?, ?> map) {
			LuaTable table = new LuaTable();
			map.forEach((key, item) -> table.set(String.valueOf(key), toLuaValue(item)));
			return table;
		}
		if (value instanceof List<?> list) {
			LuaTable table = new LuaTable();
			for (int index = 0; index < list.size(); index++) table.set(index + 1, toLuaValue(list.get(index)));
			return table;
		}
		return LuaValue.valueOf(value.toString());
	}

	public static boolean isValidName(String name) {
		return name != null && VALID_NAME.matcher(name).matches();
	}

	private static Path resolve(String name) {
		if (!isValidName(name)) {
			throw new IllegalArgumentException("脚本名称只能包含字母、数字、下划线和连字符，长度 1-48");
		}
		return SCRIPT_DIRECTORY.resolve(name + ".lua");
	}

	private static void ensureDirectory() throws IOException {
		Files.createDirectories(SCRIPT_DIRECTORY);
	}
}
