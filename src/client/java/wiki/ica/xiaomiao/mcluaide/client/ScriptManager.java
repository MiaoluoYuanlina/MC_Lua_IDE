package wiki.ica.xiaomiao.mcluaide.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.lib.jse.JsePlatform;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaApiDescriptorCodec;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Asynchronous client facade for the privileged server-side script service. */
public final class ScriptManager {
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,48}");
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong(1);
	private static final Map<Long, CompletableFuture<LuaProtocol.Response>> PENDING =
			new ConcurrentHashMap<>();
	private static final Map<Long, ScriptExecution> EXECUTIONS = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<List<LuaInsertCatalog.Entry>>> REGISTRY_CATALOGS =
			new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<List<LuaRegistryCatalogData.RegistryType>>> REGISTRY_TYPES =
			new ConcurrentHashMap<>();
	private static volatile ScriptExecution activeExecution;

	private ScriptManager() {
	}

	public static void registerNetworking() {
		if (!REGISTERED.compareAndSet(false, true)) return;
		ClientLoginNetworking.registerGlobalReceiver(LuaProtocol.CLIENT_CHECK_ID,
				(client, handler, challenge, listenerAdder) -> {
					int serverVersion = challenge.readVarInt();
					McLuaIdeMod.LOGGER.info("Server requires MC Lua IDE client protocol {}", serverVersion);
					FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
					response.writeVarInt(LuaProtocol.VERSION);
					return CompletableFuture.completedFuture(response);
				});
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.Response.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.ExecutionEvent.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(LuaProtocol.ClientCommand.TYPE, (payload, context) ->
				context.client().execute(() -> LuaClientCommandHandler.handle(context.client(), payload)));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (!isServerAvailable()) {
				McLuaIdeMod.LOGGER.info("Connected server does not expose the MC Lua IDE protocol");
				return;
			}
			checkAccess().whenComplete((ignored, failure) -> client.execute(() -> {
				if (failure == null) {
					McLuaIdeMod.LOGGER.info("MC Lua IDE administrator protocol ready");
					LuaIntegrationProbe.runIfEnabled();
				}
				else McLuaIdeMod.LOGGER.info("MC Lua IDE server available but access was denied: {}",
						message(failure));
			}));
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> disconnect("已与服务器断开连接")));
	}

	public static boolean isServerAvailable() {
		try {
			return ClientPlayNetworking.canSend(LuaProtocol.Request.TYPE);
		} catch (IllegalArgumentException | IllegalStateException ignored) {
			return false;
		}
	}

	public static CompletableFuture<Void> checkAccess() {
		return request(LuaProtocol.Action.ACCESS, "", "").thenApply(response -> {
			int version;
			try {
				version = Integer.parseInt(response.data());
			} catch (NumberFormatException error) {
				throw new IllegalStateException("服务器返回了无效的协议版本");
			}
			if (version != LuaProtocol.VERSION) {
				throw new IllegalStateException("客户端/服务端协议版本不一致：客户端 "
						+ LuaProtocol.VERSION + "，服务端 " + version);
			}
			return null;
		});
	}

	public static CompletableFuture<List<String>> listScripts() {
		return request(LuaProtocol.Action.LIST, "", "").thenApply(response -> {
			if (response.data().isBlank()) return List.of();
			return Arrays.stream(response.data().split("\\n"))
					.filter(ScriptManager::isValidName)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		});
	}

	public static CompletableFuture<String> read(String name) {
		return request(LuaProtocol.Action.READ, requireName(name), "").thenApply(LuaProtocol.Response::data);
	}

	public static CompletableFuture<Void> save(String name, String source) {
		requireSource(source);
		return request(LuaProtocol.Action.SAVE, requireName(name), source).thenApply(ignored -> null);
	}

	public static CompletableFuture<Boolean> delete(String name) {
		return request(LuaProtocol.Action.DELETE, requireName(name), "")
				.thenApply(response -> Boolean.parseBoolean(response.data()));
	}

	public static CompletableFuture<LuaLimits> getLimits() {
		return request(LuaProtocol.Action.GET_LIMITS, "", "")
				.thenApply(response -> LuaLimits.decode(response.data()));
	}

	public static CompletableFuture<LuaLimits> saveLimits(LuaLimits limits) {
		return request(LuaProtocol.Action.SAVE_LIMITS, "", limits.encode())
				.thenApply(response -> LuaLimits.decode(response.data()));
	}

	public static CompletableFuture<Integer> refreshApiDocumentation() {
		return request(LuaProtocol.Action.GET_API_DOCUMENTATION, "", "").thenApply(response -> {
			List<wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi.Descriptor> descriptors =
					LuaApiDescriptorCodec.decode(response.data());
			LuaApiDocumentation.replaceExternal(descriptors);
			return descriptors.size();
		});
	}

	static CompletableFuture<LuaComponentEditorData> componentEditorData(String kind, String id) {
		return request(LuaProtocol.Action.GET_COMPONENT_EDITOR_DATA, kind, selectedLocale() + "\n" + id)
				.thenApply(response -> LuaComponentEditorData.decode(response.data()));
	}

	static CompletableFuture<String> validateItemComponents(String itemId, String componentsJson) {
		return validateComponents("item", itemId, componentsJson);
	}

	static CompletableFuture<String> validateComponents(String kind, String targetId, String componentsJson) {
		return request(LuaProtocol.Action.VALIDATE_ITEM_COMPONENTS, kind + "\n" + targetId, componentsJson)
				.thenApply(LuaProtocol.Response::data);
	}

	static CompletableFuture<List<LuaInsertCatalog.Entry>> registryCatalog(String registryId) {
		String locale = selectedLocale();
		String cacheKey = registryId + '\0' + locale;
		return REGISTRY_CATALOGS.computeIfAbsent(cacheKey, ignored -> {
			List<LuaInsertCatalog.Entry> entries = new ArrayList<>();
			return loadRegistryPage(registryId, locale, 0, entries)
					.whenComplete((value, failure) -> {
						if (failure != null) REGISTRY_CATALOGS.remove(cacheKey);
					});
		});
	}

	static CompletableFuture<List<LuaRegistryCatalogData.RegistryType>> registryTypes() {
		String locale = selectedLocale();
		return REGISTRY_TYPES.computeIfAbsent(locale, key ->
				request(LuaProtocol.Action.GET_REGISTRY_CATALOG, "*", key)
						.thenApply(response -> LuaRegistryCatalogData.decodeTypes(response.data()))
						.whenComplete((ignored, failure) -> {
							if (failure != null) REGISTRY_TYPES.remove(key);
						}));
	}

	private static CompletableFuture<List<LuaInsertCatalog.Entry>> loadRegistryPage(String registryId,
			String locale, int offset, List<LuaInsertCatalog.Entry> accumulated) {
		return request(LuaProtocol.Action.GET_REGISTRY_CATALOG, registryId, locale + "\n" + offset)
				.thenCompose(response -> {
					LuaRegistryCatalogData.Page page = LuaRegistryCatalogData.decodePage(
							response.data(), Minecraft.getInstance());
					accumulated.addAll(page.entries());
					if (page.nextOffset() >= 0) {
						return loadRegistryPage(registryId, locale, page.nextOffset(), accumulated);
					}
					return CompletableFuture.completedFuture(List.copyOf(accumulated));
				});
	}

	private static String selectedLocale() {
		String selected = Minecraft.getInstance().getLanguageManager().getSelected();
		return selected == null || selected.isBlank() ? "zh_cn" : selected;
	}

	public static ScriptExecution start(String name, String source, Consumer<String> output,
			Consumer<ExecutionResult> completion) {
		requireName(name);
		requireSource(source);
		ensureAvailable();
		ScriptExecution current = activeExecution;
		if (current != null && current.isRunning()) {
			throw new IllegalStateException("已有脚本正在运行：" + current.name());
		}
		long runId = nextRequestId();
		ScriptExecution execution = new ScriptExecution(runId, name, output, completion);
		activeExecution = execution;
		EXECUTIONS.put(runId, execution);
		request(runId, LuaProtocol.Action.RUN, name, source).whenComplete((response, failure) -> {
			if (failure != null) execution.finish(new ExecutionResult(false, message(failure)));
		});
		return execution;
	}

	public static boolean stopActive() {
		ScriptExecution execution = activeExecution;
		if (execution == null || !execution.isRunning()) return false;
		execution.stop();
		return true;
	}

	/** Returns the client-side handle for the script that is still running on the server. */
	public static ScriptExecution activeExecution() {
		ScriptExecution execution = activeExecution;
		return execution != null && execution.isRunning() ? execution : null;
	}

	public static CompletableFuture<Boolean> stopServer() {
		return request(LuaProtocol.Action.STOP, "", "")
				.thenApply(response -> Boolean.parseBoolean(response.data()));
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

	public static boolean isValidName(String name) {
		return name != null && VALID_NAME.matcher(name).matches();
	}

	public static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private static CompletableFuture<LuaProtocol.Response> request(
			LuaProtocol.Action action, String name, String body) {
		return request(nextRequestId(), action, name, body);
	}

	private static CompletableFuture<LuaProtocol.Response> request(
			long requestId, LuaProtocol.Action action, String name, String body) {
		try {
			ensureAvailable();
		} catch (RuntimeException error) {
			return CompletableFuture.failedFuture(error);
		}
		CompletableFuture<LuaProtocol.Response> future = new CompletableFuture<>();
		PENDING.put(requestId, future);
		try {
			ClientPlayNetworking.send(new LuaProtocol.Request(requestId, action, name, body));
		} catch (Throwable error) {
			PENDING.remove(requestId);
			future.completeExceptionally(error);
			return future;
		}
		CompletableFuture.delayedExecutor(12, TimeUnit.SECONDS).execute(() -> {
			CompletableFuture<LuaProtocol.Response> waiting = PENDING.remove(requestId);
			if (waiting != null) waiting.completeExceptionally(new TimeoutException("等待服务器响应超时"));
		});
		return future;
	}

	private static void receive(LuaProtocol.Response response) {
		CompletableFuture<LuaProtocol.Response> future = PENDING.remove(response.requestId());
		if (future == null) return;
		if (response.success()) future.complete(response);
		else future.completeExceptionally(new IllegalStateException(response.data()));
	}

	private static void receive(LuaProtocol.ExecutionEvent event) {
		ScriptExecution execution = EXECUTIONS.get(event.runId());
		if (execution == null) return;
		LuaProtocol.ExecutionKind kind;
		try {
			kind = LuaProtocol.ExecutionKind.byId(event.kindId());
		} catch (RuntimeException error) {
			execution.finish(new ExecutionResult(false, error.getMessage()));
			return;
		}
		switch (kind) {
			case OUTPUT -> execution.emitOutput(event.message());
			case FINISHED -> execution.finish(new ExecutionResult(false, null));
			case FAILED -> execution.finish(new ExecutionResult(false, event.message()));
			case STOPPED -> execution.finish(new ExecutionResult(true, null));
		}
	}

	private static void disconnect(String reason) {
		PENDING.values().forEach(future -> future.completeExceptionally(new IllegalStateException(reason)));
		PENDING.clear();
		List.copyOf(EXECUTIONS.values()).forEach(execution ->
				execution.finish(new ExecutionResult(false, reason)));
		EXECUTIONS.clear();
		activeExecution = null;
		REGISTRY_CATALOGS.clear();
		REGISTRY_TYPES.clear();
		LuaApiDocumentation.replaceExternal(List.of());
	}

	private static void ensureAvailable() {
		if (!isServerAvailable()) {
			throw new IllegalStateException("服务器未安装或未启用 MC Lua IDE");
		}
	}

	private static String requireName(String name) {
		if (!isValidName(name)) {
			throw new IllegalArgumentException("脚本名称只能包含字母、数字、下划线和连字符，长度 1-48");
		}
		return name;
	}

	private static void requireSource(String source) {
		if (source == null || source.length() > LuaProtocol.MAX_SCRIPT_CHARS) {
			throw new IllegalArgumentException("脚本内容不能超过 " + LuaProtocol.MAX_SCRIPT_CHARS + " 个字符");
		}
	}

	private static long nextRequestId() {
		long id = NEXT_REQUEST_ID.getAndIncrement();
		return id == 0 ? NEXT_REQUEST_ID.getAndIncrement() : id;
	}

	public record ExecutionResult(boolean stopped, String error) {
		public boolean succeeded() {
			return !stopped && error == null;
		}
	}

	public static final class ScriptExecution {
		private final long runId;
		private final String name;
		private final Consumer<String> output;
		private final Consumer<ExecutionResult> completion;
		private final AtomicBoolean terminal = new AtomicBoolean();
		private final List<String> outputLines = new ArrayList<>();
		private volatile boolean running = true;
		private volatile ExecutionResult result;
		private long outputLineCount;

		private ScriptExecution(long runId, String name, Consumer<String> output,
				Consumer<ExecutionResult> completion) {
			this.runId = runId;
			this.name = name;
			this.output = output;
			this.completion = completion;
			appendHistory("▶ 开始运行 " + name + ".lua");
		}

		public String name() {
			return name;
		}

		public boolean isRunning() {
			return running;
		}

		/** A bounded snapshot used by IDE instances reopened while this script is running. */
		public synchronized List<String> outputLines() {
			return List.copyOf(outputLines);
		}

		public synchronized long outputLineCount() {
			return outputLineCount;
		}

		public ExecutionResult result() {
			return result;
		}

		public void stop() {
			if (!running) return;
			stopServer().whenComplete((stopped, failure) -> {
				if (failure != null) emitOutput("停止失败：" + message(failure));
				else if (!stopped) emitOutput("服务器上没有正在运行的脚本");
			});
		}

		private void emitOutput(String text) {
			appendHistory(text);
			output.accept(text);
		}

		private synchronized void appendHistory(String text) {
			for (String line : text.split("\\R", -1)) {
				outputLines.add(line);
				outputLineCount++;
			}
			while (outputLines.size() > 200) outputLines.removeFirst();
		}

		private void finish(ExecutionResult result) {
			if (!terminal.compareAndSet(false, true)) return;
			running = false;
			this.result = result;
			if (result.stopped()) appendHistory("■ 脚本已停止");
			else if (result.error() != null) appendHistory("✗ " + result.error());
			else appendHistory("✓ 脚本执行完成");
			EXECUTIONS.remove(runId);
			if (activeExecution == this) activeExecution = null;
			completion.accept(result);
		}
	}
}
