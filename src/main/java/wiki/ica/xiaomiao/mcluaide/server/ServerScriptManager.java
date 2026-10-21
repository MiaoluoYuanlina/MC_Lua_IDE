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
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;
import wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ServerScriptManager {
	private static final Path SCRIPT_DIRECTORY = FabricLoader.getInstance()
			.getConfigDir().resolve("mc_lua_ide").resolve("scripts").toAbsolutePath().normalize();
	private static final String STOP_SIGNAL = "__MC_LUA_IDE_STOPPED__";
	static final List<String> EVENT_NAMES = List.of(
			"blockPlaced", "blockBroken", "blockAttacked", "blockUsed", "itemUsed",
			"entityAttacked", "entityUsed", "entityLoaded", "entityUnloaded", "entityChangedDimension",
			"entityDamaged", "entityDied", "entityKilledOther", "equipmentChanged",
			"playerJoined", "playerLeft", "playerDied", "playerRespawned", "playerChangedDimension",
			"effectAdded", "effectRemoved", "sleepStarted", "sleepStopped",
			"chunkLoaded", "chunkGenerated", "chunkUnloaded", "levelLoaded", "levelUnloaded",
			"serverTick", "levelTick", "chat", "command", "uiAction", "interval",
			"containerClicked", "containerItemTaken", "containerItemPlaced", "containerItemToCursor", "inventoryItemToCursor",
			"scriptStarted", "scriptStopped");
	private static final Set<String> EVENT_NAME_SET = Set.copyOf(EVENT_NAMES);
	private static ScriptExecution activeExecution;
	private static final Map<UUID, LuaBreakpoints> ADMIN_BREAKPOINTS = new LinkedHashMap<>();

	private ServerScriptManager() {
	}

	public static List<String> listScripts() throws IOException {
		ensureDirectory();
		try (Stream<Path> paths = Files.walk(SCRIPT_DIRECTORY)) {
			return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.map(SCRIPT_DIRECTORY::relativize)
					.map(path -> path.toString().replace('\\', '/'))
					.filter(path -> path.endsWith(".lua"))
					.map(path -> path.substring(0, path.length() - 4))
					.filter(LuaScriptNames::isValid)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		}
	}

	public static List<String> listDirectories() throws IOException {
		ensureDirectory();
		try (Stream<Path> paths = Files.walk(SCRIPT_DIRECTORY)) {
			return paths.filter(path -> !path.equals(SCRIPT_DIRECTORY))
					.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.map(SCRIPT_DIRECTORY::relativize)
					.map(path -> path.toString().replace('\\', '/'))
					.filter(LuaScriptNames::isValid)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		}
	}

	public static void createDirectory(String name) throws IOException {
		Path directory = resolve(name, false);
		Files.createDirectories(directory);
		ensureNoLinks(directory);
	}

	public static boolean deleteDirectory(String name) throws IOException {
		Path directory = resolve(name, false);
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("目标不是目录：" + name);
		try { Files.delete(directory); }
		catch (java.nio.file.DirectoryNotEmptyException error) {
			throw new IOException("目录不为空，请先移除其中的脚本和子目录：" + name, error);
		}
		return true;
	}

	public static String read(String name) throws IOException {
		Path path = resolve(name, true);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("脚本不存在：" + name + ".lua");
		if (Files.size(path) > LuaProtocol.MAX_SCRIPT_CHARS * 4L)
			throw new IOException("脚本文件超过读取限制");
		String source = Files.readString(path, StandardCharsets.UTF_8);
		if (source.length() > LuaProtocol.MAX_SCRIPT_CHARS) {
			throw new IOException("脚本内容超过 " + LuaProtocol.MAX_SCRIPT_CHARS + " 个字符");
		}
		return source;
	}

	static String readIfPresent(String name) throws IOException {
		return Files.notExists(resolve(name, true), LinkOption.NOFOLLOW_LINKS) ? "" : read(name);
	}

	public static void save(String name, String source) throws IOException {
		// Storage/editor capacity is independent of an administrator's runtime safety policy.
		int maximum = LuaProtocol.MAX_SCRIPT_CHARS;
		if (source == null || source.length() > maximum) {
			throw new IllegalArgumentException("脚本文件不能超过 " + maximum + " 个字符");
		}
		Path path = resolve(name, true);
		Files.createDirectories(path.getParent());
		ensureNoLinks(path);
		Files.writeString(path, source, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				LinkOption.NOFOLLOW_LINKS);
	}

	public static boolean delete(String name) throws IOException {
		Path path = resolve(name, true);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("目标不是 Lua 脚本：" + name);
		return Files.deleteIfExists(path);
	}

	public static synchronized ScriptExecution start(
			MinecraftServer server, UUID owner, String name, String source,
			Consumer<String> output, Consumer<ExecutionResult> completion) {
		return start(server, owner, name, source, true, output, completion);
	}

	public static synchronized ScriptExecution start(
			MinecraftServer server, UUID owner, String name, String source, boolean debugEnabled,
			Consumer<String> output, Consumer<ExecutionResult> completion) {
		if (GitWorkspaceService.restoring()) throw new IllegalStateException("Git 正在恢复工作区，请稍后运行脚本");
		if (activeExecution != null && activeExecution.isRunning()) {
			throw new IllegalStateException("已有脚本正在运行：" + activeExecution.name());
		}
		LuaLimits limits = LuaLimitConfig.current();
		if (source == null || source.length() > limits.maxScriptChars()) {
			throw new IllegalArgumentException("脚本内容不能超过当前限制的 "
					+ limits.maxScriptChars() + " 个字符");
		}
		ScriptExecution execution = new ScriptExecution(server, owner, name, limits, debugEnabled);
		execution.debugger.source(name, source);
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
		refreshGamePause(activeExecution.server);
		return true;
	}

	public static synchronized boolean stopOwnedBy(UUID owner) {
		if (activeExecution == null || !activeExecution.isRunning()
				|| !activeExecution.owner().equals(owner)) {
			return false;
		}
		activeExecution.stop();
		refreshGamePause(activeExecution.server);
		return true;
	}

	public static synchronized String activeScriptName() {
		return activeExecution != null && activeExecution.isRunning() ? activeExecution.name() : null;
	}

	/** Only returned through the administrator-checked IDE request handler. */
	public static synchronized String debugSnapshot() {
		if (activeExecution == null || !activeExecution.isRunning()) return LuaDebugSnapshot.inactive();
		if (!activeExecution.debugEnabled) return LuaDebugSnapshot.disabled(activeExecution.name());
		activeExecution.watchDebugVariables();
		return activeExecution.debugSnapshot;
	}

	public static synchronized void configureBreakpoints(UUID owner, String json) {
		LuaBreakpoints config = LuaBreakpoints.decode(json);
		if (!ADMIN_BREAKPOINTS.containsKey(owner) && ADMIN_BREAKPOINTS.size() >= 128)
			ADMIN_BREAKPOINTS.remove(ADMIN_BREAKPOINTS.keySet().iterator().next());
		ADMIN_BREAKPOINTS.put(owner, config);
		if (activeExecution != null && activeExecution.owner.equals(owner))
			activeExecution.debugger.configure(config);
	}

	public static synchronized String debuggerState() {
		return rawDebuggerState().withGamePause(LuaDebuggerGamePause.enabled(),
				activeExecution != null && LuaDebuggerGamePause.ownsFreeze(activeExecution.server),
				LuaDebuggerGamePause.allowPlayerMovement()).encode();
	}
	private static LuaDebuggerState rawDebuggerState() {
		return activeExecution == null || !activeExecution.isRunning() ? LuaDebuggerState.inactive() : activeExecution.debugger.state();
	}
	public static synchronized void tickDebugger(MinecraftServer server) {
		LuaDebuggerGamePause.tick(server, rawDebuggerState());
	}
	private static void refreshGamePause(MinecraftServer server) {
		if (server != null && server.isSameThread()) tickDebugger(server);
	}
	/** Called only through the administrator-checked request handler, on the server thread. */
	public static synchronized String setDebuggerGamePause(MinecraftServer server, boolean enabled) throws IOException {
		LuaDebuggerGamePause.setEnabled(enabled);
		refreshGamePause(server);
		return debuggerState();
	}
	public static synchronized String setDebuggerPlayerMovement(MinecraftServer server, boolean enabled) throws IOException {
		LuaDebuggerGamePause.setAllowPlayerMovement(enabled);
		refreshGamePause(server);
		return debuggerState();
	}
	/** The network handler has already checked administrator permissions; camera mode only anchors this player. */
	public static synchronized boolean setDebuggerCamera(net.minecraft.server.level.ServerPlayer player, boolean enabled) {
		var state = rawDebuggerState();
		boolean allowed = enabled && activeExecution != null && activeExecution.server == player.level().getServer()
				&& state.running() && state.enabled() && state.paused();
		LuaDebuggerGamePause.cameraMode(player, allowed);
		return allowed;
	}

	/**
	 * Debugger watch/hover evaluation. The request is {"frame":0,"exprs":[...]}; the reply is {"results":[{ok,text,type|error}]}.
	 * The work runs on the Lua worker (while it is paused), so this only schedules it and returns a future.
	 */
	public static java.util.concurrent.CompletableFuture<String> evaluateDebugger(String executionId, String body) {
		ScriptExecution execution;
		synchronized (ServerScriptManager.class) { execution = activeExecution; }
		if (execution == null || !execution.isRunning())
			return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("当前没有正在运行的 Lua 脚本"));
		int frame; List<String> expressions = new java.util.ArrayList<>();
		try {
			com.google.gson.JsonObject request = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
			frame = request.get("frame").getAsInt();
			for (var element : request.getAsJsonArray("exprs")) expressions.add(element.getAsString());
		} catch (RuntimeException error) {
			return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("求值请求无效"));
		}
		return execution.debugger.evaluate(executionId, frame, expressions).thenApply(results -> {
			com.google.gson.JsonArray array = new com.google.gson.JsonArray();
			for (var result : results) {
				com.google.gson.JsonObject item = new com.google.gson.JsonObject();
				item.addProperty("ok", result.ok());
				if (result.ok()) { item.addProperty("text", result.text()); item.addProperty("type", result.type()); }
				else item.addProperty("error", result.error());
				array.add(item);
			}
			com.google.gson.JsonObject root = new com.google.gson.JsonObject();
			root.add("results", array);
			return root.toString();
		});
	}

	public static synchronized String controlDebugger(String executionId, String command) {
		if (activeExecution == null || !activeExecution.isRunning())
			throw new IllegalStateException("当前没有正在运行的 Lua 脚本");
		if (command.equals("stop")) {
			if (!activeExecution.debugger.state().id().equals(executionId))
				throw new IllegalStateException("脚本已切换，请重新读取断点信息");
			activeExecution.stop();
		} else activeExecution.debugger.control(executionId, command);
		refreshGamePause(activeExecution.server);
		return debuggerState();
	}

	public static synchronized void shutdownDebugger() {
		stopActive();
		ADMIN_BREAKPOINTS.clear();
	}
	public static synchronized void shutdownDebugger(MinecraftServer server) {
		shutdownDebugger();
		LuaDebuggerGamePause.tick(server, LuaDebuggerState.inactive());
	}

	static synchronized boolean isActive(ScriptExecution execution) {
		return activeExecution == execution && execution != null && execution.isRunning();
	}

	static boolean observesAny(String... types) {
		ScriptExecution execution;
		synchronized (ServerScriptManager.class) { execution = activeExecution; }
		if (execution == null || !execution.isRunning()) return false;
		synchronized (execution.eventCallbacks) {
			return execution.eventCallbacks.values().stream().anyMatch(registration -> java.util.Arrays.asList(types).contains(registration.eventName()));
		}
	}

	private static void execute(
			ScriptExecution execution, MinecraftServer server, String source,
			Consumer<String> output, Consumer<ExecutionResult> completion) {
		ExecutionResult result = new ExecutionResult(false, null);
		try {
			Globals globals = LuaSandbox.create(execution.limits());
			execution.globals = globals;
			execution.warnings = new LuaRuntimeWarnings(output, execution.limits.maxOutputLines(),
					execution.limits.maxOutputCharsPerLine());
			LuaValue debugLibrary = globals.get("debug");
			sandbox(globals, execution.limits());
			// Logpoints and breakpoint conditions need the globals and a place to print; output limits apply as for print().
			execution.debugger.attach(globals, text -> execution.emitOutput(LuaValue.valueOf(text), output));
			globals.set("print", new VarArgFunction() {
				@Override
				public Varargs invoke(Varargs values) {
					execution.emitOutput(values, output);
					return LuaValue.NIL;
				}
			});
			MinecraftLuaApi.install(globals, server, execution, execution.limits());
			LuaScriptModuleLoader.install(globals, name -> {
				String moduleSource = read(name);
				execution.debugger.source(name, moduleSource);
				return moduleSource;
			},
					execution::checkRuntime, execution.limits().maxScriptChars(),
					() -> execution.sleepMilliseconds(1), () -> execution.runtime.currentFlow());
			Map<String, LuaValue> baseline = execution.debugEnabled
					? LuaDebugSnapshot.baseline(globals) : Map.of();
			LuaValue stopHook = new VarArgFunction() {
				@Override
				public Varargs invoke(Varargs arguments) {
					if (execution.cancelled.get() || Thread.currentThread().isInterrupted())
						throw new LuaError(STOP_SIGNAL);
					execution.checkRuntime();
					if (execution.debugEnabled) {
						if (arguments.arg1().tojstring().equals("line")) {
							execution.debugger.line(debugLibrary, arguments.arg(2).optint(0),
									() -> execution.captureDebugVariablesNow(globals, debugLibrary, baseline));
							execution.checkRuntime();
						}
						execution.captureDebugVariables(globals, debugLibrary, baseline,
								arguments.arg1().tojstring());
					}
					return LuaValue.NIL;
				}
			};
			LuaValue main = globals.load(source, execution.name() + ".lua");
			execution.runtime = new LuaCooperativeRuntime(globals, stopHook, execution.debugEnabled,
					execution.limits().instructionHookInterval(), execution.limits().eventQueueCapacity() + 1,
					execution::checkRuntime, context -> {
						execution.currentDebugRegistration = (ScriptExecution.EventRegistration) context;
						execution.lastDebugFunction = LuaValue.NIL;
						execution.forcedDebugLines = 0;
					});
			execution.runtime.submit(null, () -> {
				main.call();
				if (execution.debugEnabled)
					execution.captureDebugVariablesAtIdle(globals, debugLibrary, baseline);
				if (execution.hasEventCallbacks())
					output.accept("● 事件监听已启动；停止脚本可注销全部事件");
			});
			execution.runEventLoop();
			// Stop can be observed in the event-loop condition before interrupt() reaches the poll.
			// Preserve the stopped result even when cancellation exits the dispatcher normally.
			result = new ExecutionResult(execution.cancelled.get(), null);
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
			try {
				execution.finish(result, output);
			} catch (Throwable error) {
				McLuaIdeMod.LOGGER.warn("Could not finish Lua script {}", execution.name(), error);
			} finally {
				synchronized (ServerScriptManager.class) {
					if (activeExecution == execution) {
						activeExecution = null;
					}
				}
			}
		}
		completion.accept(result);
	}

	public static String validate(String name, String source) {
		try {
			LuaSandbox.syntaxOnly().load(source, name + ".lua");
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
		private final MinecraftServer server;
		private final UUID owner;
		private final String name;
		private final LuaLimits limits;
		private final boolean debugEnabled;
		private final LuaBreakpointController debugger;
		private final long deadlineNanos;
		private final long startedNanos = System.nanoTime();
		private final long startedAt = System.currentTimeMillis();
		private Globals globals;
		private LuaRuntimeWarnings warnings;

		LuaRuntimeWarnings warnings() { return warnings; }
		private volatile boolean finishing;
		private long cleanupDeadlineNanos;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final ArrayBlockingQueue<GameEvent> eventQueue;
		private final Map<Integer, EventRegistration> eventCallbacks = new LinkedHashMap<>();
		private int nextCallbackId = 1;
		private int outputLines;
		private volatile boolean running = true;
		private volatile Thread thread;
		private volatile LuaCooperativeRuntime runtime;
		private volatile long debugWatchUntilNanos;
		private long lastDebugCaptureNanos;
		private LuaValue lastDebugFunction = LuaValue.NIL;
		private int forcedDebugLines;
		private int lastCapturedRegistrationId;
		private EventRegistration currentDebugRegistration;
		private final LuaDebugSnapshot.History debugHistory = new LuaDebugSnapshot.History();
		private volatile String debugSnapshot;

		private ScriptExecution(MinecraftServer server, UUID owner, String name, LuaLimits limits, boolean debugEnabled) {
			this.server = server;
			this.owner = owner;
			this.name = name;
			this.debugEnabled = debugEnabled;
			this.debugger = new LuaBreakpointController(name, debugEnabled,
					ADMIN_BREAKPOINTS.getOrDefault(owner, LuaBreakpoints.empty()));
			this.debugSnapshot = debugEnabled ? LuaDebugSnapshot.waiting(name)
					: LuaDebugSnapshot.disabled(name);
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

		private void watchDebugVariables() {
			debugWatchUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		}

		private void captureDebugVariables(Globals globals, LuaValue debugLibrary,
				Map<String, LuaValue> baseline, String event) {
			long now = System.nanoTime();
			if (event.equals("return")) {
				lastDebugFunction = LuaValue.NIL;
				forcedDebugLines = 0;
				return;
			}
			boolean newRegistration = currentDebugRegistration == null && event.equals("line")
					&& nextCallbackId - 1 > lastCapturedRegistrationId;
			if (now > debugWatchUntilNanos && !newRegistration) return;
			if (event.equals("line")) {
				try {
					LuaValue info = debugLibrary.get("getinfo").invoke(LuaValue.varargsOf(
							LuaValue.valueOf(1), LuaValue.valueOf("f"))).arg1();
					LuaValue function = info.isnil() ? LuaValue.NIL : info.get("func");
					if (function != lastDebugFunction) {
						lastDebugFunction = function;
						forcedDebugLines = 4;
					}
				} catch (RuntimeException ignored) {
					lastDebugFunction = LuaValue.NIL;
				}
			}
			boolean forced = newRegistration || event.equals("line") && forcedDebugLines > 0;
			if (event.equals("line") && forcedDebugLines > 0) forcedDebugLines--;
			if (!forced && now - lastDebugCaptureNanos < TimeUnit.MILLISECONDS.toNanos(300)) return;
			lastDebugCaptureNanos = now;
			captureDebugVariablesNow(globals, debugLibrary, baseline);
		}

		private void captureDebugVariablesNow(Globals globals, LuaValue debugLibrary,
				Map<String, LuaValue> baseline) {
			lastDebugCaptureNanos = System.nanoTime();
			try {
				String sample = LuaDebugSnapshot.capture(name, globals, debugLibrary, baseline);
				if (currentDebugRegistration == null) {
					debugHistory.recordMain(sample);
					lastCapturedRegistrationId = nextCallbackId - 1;
				} else {
					debugHistory.recordListener(currentDebugRegistration.id(),
							currentDebugRegistration.eventName(), sample);
				}
				debugSnapshot = debugHistory.encoded(name);
			} catch (RuntimeException error) {
				// A debugger must never change whether the user's script keeps running.
				McLuaIdeMod.LOGGER.debug("Could not sample Lua variables for {}", name, error);
			}
		}

		private void captureDebugVariablesAtIdle(Globals globals, LuaValue debugLibrary,
				Map<String, LuaValue> baseline) {
			try {
				debugHistory.recordIdle(LuaDebugSnapshot.capture(name, globals, debugLibrary, baseline));
				debugSnapshot = debugHistory.encoded(name);
			} catch (RuntimeException error) {
				McLuaIdeMod.LOGGER.debug("Could not sample idle Lua variables for {}", name, error);
			}
		}

		LuaLimits limits() {
			return limits;
		}

		void sleepMilliseconds(int milliseconds) {
			if (finishing) throw new LuaError("脚本停止回调不能使用 MC.sleep / MC.wait；请直接执行短小的清理操作");
			runtime.sleepMilliseconds(milliseconds);
		}

		public synchronized void stop() {
			if (finishing) return; // Repeated stop requests must not interrupt cleanup callbacks.
			cancelled.set(true);
			debugger.finish();
			LuaCooperativeRuntime currentRuntime = runtime;
			if (currentRuntime != null) currentRuntime.cancel();
			MinecraftLuaUiApi.stopExecution(this, true);
			synchronized (eventCallbacks) {
				eventCallbacks.values().removeIf(registration -> !registration.eventName().equals("scriptStopped"));
			}
			eventQueue.clear();
			Thread current = thread;
			if (current != null) {
				current.interrupt();
			}
		}

		int registerEvent(String eventName, LuaValue callback, int interval) {
			if (finishing || cancelled.get()) throw new LuaError("脚本已在停止，不能再注册事件");
			if (eventName.equals("interval")) return registerInterval(interval, callback);
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
				eventCallbacks.put(id, new EventRegistration(id, eventName, callback, interval, null));
				return id;
			}
		}

		int registerInterval(double milliseconds, LuaValue callback) {
			if (finishing || cancelled.get()) throw new LuaError("脚本已在停止，不能再注册事件");
			if (!limits.allowEvents()) throw new LuaError("服务器已禁止 Lua 注册事件");
			callback.checkfunction();
			LuaIntervalTimer timer = new LuaIntervalTimer(milliseconds, System.nanoTime());
			synchronized (eventCallbacks) {
				if (eventCallbacks.size() >= limits.maxEventCallbacks())
					throw new LuaError("事件监听数量已达到服务器限制：" + limits.maxEventCallbacks());
				int id = nextCallbackId++;
				eventCallbacks.put(id, new EventRegistration(id, "interval", callback, 1, timer));
				// Wake a sleeping dispatcher immediately when a callback adds a short-period timer.
				// A full queue already guarantees the dispatcher has work and will not stay asleep.
				eventQueue.offer(new GameEvent("__intervalWake", Map.of()));
				return id;
			}
		}

		boolean unregisterEvent(int id) {
			synchronized (eventCallbacks) {
				boolean removed = eventCallbacks.remove(id) != null;
				if (removed && debugEnabled) {
					debugHistory.removeListener(id);
					debugSnapshot = debugHistory.encoded(name);
				}
				return removed;
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
				return eventCallbacks.values().stream().anyMatch(registration -> !isLifecycle(registration.eventName()));
			}
		}

		private static boolean isLifecycle(String eventName) {
			return eventName.equals("scriptStarted") || eventName.equals("scriptStopped");
		}

		private boolean hasStartupCallbacks() {
			synchronized (eventCallbacks) {
				return eventCallbacks.values().stream().anyMatch(registration -> registration.eventName().equals("scriptStarted"));
			}
		}

		private Map<String, Object> lifecycleData(EventRegistration registration) {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("registrationId", registration.id());
			data.put("scriptName", name);
			if (owner != null) data.put("ownerUuid", owner.toString());
			data.put("startedAt", startedAt);
			data.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
			return data;
		}

		private void dispatchStartup() {
			List<EventRegistration> callbacks;
			synchronized (eventCallbacks) {
				callbacks = eventCallbacks.values().stream().filter(registration -> registration.eventName().equals("scriptStarted")).toList();
			}
			for (EventRegistration registration : callbacks) {
				while (!runtime.submit(registration, () -> {
					synchronized (eventCallbacks) {
						if (!eventCallbacks.remove(registration.id(), registration)) return;
					}
					registration.callback().call(new GameEvent("scriptStarted", lifecycleData(registration)).toLuaTable());
				})) {
					runtime.checkFailure(); checkRuntime();
					java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
				}
			}
		}

		private boolean accepts(GameEvent event) {
			synchronized (eventCallbacks) {
				Object tick = event.data().get("tick");
				return eventCallbacks.values().stream().anyMatch(registration -> {
					if (registration.timer() != null || !registration.eventName().equals(event.type())) return false;
					return registration.interval() <= 1 || !(tick instanceof Number number)
							|| Math.floorMod(number.longValue(), registration.interval()) == 0;
				});
			}
		}

		private void enqueue(GameEvent event) {
			if (cancelled.get() || finishing || !running || isLifecycle(event.type()) || !accepts(event)) return;
			if (!eventQueue.offer(event)) {
				eventQueue.poll();
				eventQueue.offer(event);
			}
		}

		private void runEventLoop() throws InterruptedException {
			while (!cancelled.get() && (runtime.hasTasks() || hasEventCallbacks() || hasStartupCallbacks())) {
				runtime.checkFailure();
				runtime.whenAvailable(debugger::idle);
				checkRuntime();
				dispatchStartup();
				dispatchIntervals();
				GameEvent event = eventQueue.poll(nextIntervalWait(), TimeUnit.NANOSECONDS);
				if (event == null) continue;
				List<EventRegistration> callbacks;
				synchronized (eventCallbacks) {
					callbacks = eventCallbacks.values().stream()
							.filter(registration -> registration.timer() == null && registration.eventName().equals(event.type()))
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
					while (!runtime.submit(registration, () -> {
						// off/clear also cancels callbacks that have not started yet.
						synchronized (eventCallbacks) {
							if (eventCallbacks.get(registration.id()) != registration) return;
						}
						registration.callback().call(event.toLuaTable());
					})) {
						runtime.checkFailure();
						checkRuntime();
						TimeUnit.MILLISECONDS.sleep(5);
					}
				}
			}
			runtime.checkFailure();
		}

		private long nextIntervalWait() {
			long now = System.nanoTime(), wait = TimeUnit.MILLISECONDS.toNanos(25);
			synchronized (eventCallbacks) {
				for (EventRegistration registration : eventCallbacks.values())
					if (registration.timer() != null) wait = Math.min(wait, registration.timer().waitNanos(now));
			}
			return wait;
		}

		private void dispatchIntervals() {
			List<EventRegistration> timers;
			synchronized (eventCallbacks) {
				timers = eventCallbacks.values().stream().filter(registration -> registration.timer() != null).toList();
			}
			for (EventRegistration registration : timers) {
				checkRuntime();
				LuaIntervalTimer timer = registration.timer();
				synchronized (eventCallbacks) {
					if (eventCallbacks.get(registration.id()) != registration || !timer.claim(System.nanoTime())) continue;
				}
				boolean submitted = false;
				try {
					submitted = runtime.submit(registration, () -> {
						try {
							Map<String, ?> data;
							synchronized (eventCallbacks) {
								if (eventCallbacks.get(registration.id()) != registration) return;
								data = timer.started(registration.id(), System.nanoTime());
							}
							registration.callback().call(new GameEvent("interval", data).toLuaTable());
						} finally {
							synchronized (eventCallbacks) {
								timer.release(System.nanoTime());
								// The dispatcher may have begun its idle poll while this timer was in flight.
								// Wake it so short intervals are not quantized to the 25ms idle poll.
								if (eventCallbacks.get(registration.id()) == registration)
									eventQueue.offer(new GameEvent("__intervalWake", Map.of()));
							}
						}
					});
				} finally {
					// Capacity pressure skips this occurrence rather than building a timer backlog.
					if (!submitted) synchronized (eventCallbacks) { timer.release(System.nanoTime()); }
				}
			}
		}

		void checkRuntime() {
			if (finishing) {
				if (Thread.currentThread().isInterrupted() || System.nanoTime() >= cleanupDeadlineNanos)
					throw new LuaError("脚本停止回调超过清理时间限制");
				return;
			}
			if (cancelled.get() || Thread.currentThread().isInterrupted())
				throw new LuaError(STOP_SIGNAL);
			if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - debugger.pausedNanos() > deadlineNanos) {
				throw new LuaError("Lua 脚本超过服务器限制的最长运行时间："
						+ limits.maxRuntimeSeconds() + " 秒");
			}
		}

		private void emitOutput(Varargs values, Consumer<String> output) {
			if (cancelled.get() && !finishing) throw new LuaError(STOP_SIGNAL);
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

		private void finish(ExecutionResult result, Consumer<String> output) {
			// Do not let a stop() already in progress interrupt a later cleanup callback.
			synchronized (this) { finishing = true; }
			debugger.finish();
			LuaCooperativeRuntime currentRuntime = runtime;
			if (currentRuntime != null) currentRuntime.close();
			// All old Lua stacks have unwound. Cleanup now owns the VM exclusively.
			Thread.interrupted();
			try { runStopCallbacks(result, output); }
			finally {
				running = false;
				MinecraftLuaUiApi.stopExecution(this, false);
				LuaContainerEvents.closeOwned(server, this);
				synchronized (eventCallbacks) { eventCallbacks.clear(); }
				eventQueue.clear();
			}
		}

		private void runStopCallbacks(ExecutionResult result, Consumer<String> output) {
			if (globals == null) return;
			List<EventRegistration> callbacks;
			synchronized (eventCallbacks) {
				callbacks = eventCallbacks.values().stream().filter(registration -> registration.eventName().equals("scriptStopped")).toList();
			}
			long totalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			LuaThread previous = globals.running;
			for (EventRegistration registration : callbacks) {
				synchronized (eventCallbacks) {
					if (!eventCallbacks.containsKey(registration.id())) continue;
				}
				if (System.nanoTime() >= totalDeadline) {
					output.accept("⚠ 停止回调总清理时间超过 2 秒，剩余回调已跳过"); break;
				}
				cleanupDeadlineNanos = Math.min(totalDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
				LuaThread closing = new LuaThread(globals);
				closing.state.status = LuaThread.STATUS_RUNNING;
				closing.state.hookcount = Math.min(1000, limits.instructionHookInterval());
				closing.state.hookfunc = new VarArgFunction() {
					@Override public Varargs invoke(Varargs args) { checkRuntime(); return LuaValue.NIL; }
				};
				globals.running = closing;
				Thread worker = Thread.currentThread();
				long deadline = cleanupDeadlineNanos;
				Thread watchdog = Thread.startVirtualThread(() -> {
					try {
						TimeUnit.NANOSECONDS.sleep(Math.max(0, deadline - System.nanoTime()));
						worker.interrupt();
					} catch (InterruptedException ignored) { }
				});
				try {
					Map<String, Object> data = lifecycleData(registration);
					data.put("reason", result.stopped() ? "stopped" : result.error() != null ? "error" : "completed");
					data.put("stopped", result.stopped()); data.put("success", result.succeeded());
					if (result.error() != null) data.put("error", result.error());
					registration.callback().call(new GameEvent("scriptStopped", data).toLuaTable());
					checkRuntime();
				} catch (Throwable error) {
					String message = "⚠ 停止回调 #" + registration.id() + " 失败：" + error.getMessage();
					output.accept(message.substring(0, Math.min(message.length(), limits.maxOutputCharsPerLine())));
				} finally {
					watchdog.interrupt();
					while (watchdog.isAlive()) {
						Thread.interrupted();
						try { watchdog.join(); } catch (InterruptedException ignored) { }
					}
					Thread.interrupted(); globals.running = previous;
				}
			}
		}

		private record EventRegistration(int id, String eventName, LuaValue callback, int interval, LuaIntervalTimer timer) {
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
		return LuaScriptNames.isValid(name);
	}

	private static Path resolve(String name, boolean script) throws IOException {
		if (GitWorkspaceService.restoring()) throw new IOException("Git 正在恢复工作区，请稍后读取或保存脚本");
		LuaScriptNames.requireValid(name);
		ensureDirectory();
		Path path = SCRIPT_DIRECTORY.resolve(name + (script ? ".lua" : "")).normalize();
		if (!path.startsWith(SCRIPT_DIRECTORY)) throw new IOException("脚本路径超出脚本目录");
		ensureNoLinks(path);
		return path;
	}

	private static void ensureDirectory() throws IOException {
		Files.createDirectories(SCRIPT_DIRECTORY);
		if (Files.isSymbolicLink(SCRIPT_DIRECTORY)) throw new IOException("脚本根目录不能是符号链接");
	}

	private static void ensureNoLinks(Path path) throws IOException {
		Path current = SCRIPT_DIRECTORY;
		for (Path part : SCRIPT_DIRECTORY.relativize(path)) {
			current = current.resolve(part);
			if (Files.isSymbolicLink(current))
				throw new IOException("脚本路径不能经过符号链接：" + current);
		}
	}
}
