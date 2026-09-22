package wiki.ica.xiaomiao.mcluaide.api;

import net.minecraft.server.MinecraftServer;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Public extension API used by other server mods to add documented functions to {@code MC.*}
 * or to an owned top-level table such as {@code TestMod.*}.
 * Registrations are installed into every subsequently created Lua execution environment.
 */
public final class McLuaIdeApi {
	public static final int EXTENSION_API_VERSION = 3;
	private static final int SERVER_CALL_TIMEOUT_SECONDS = 10;
	private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern LUA_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
	private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(?:_[a-z]{2})?");
	private static final Set<String> RESERVED_NAMESPACES = Set.of(
			"block", "item", "container", "player", "world", "chat", "command", "mod", "game",
			"server", "event", "entity", "effect", "sound", "particle", "title", "score", "team",
			"gamerule", "border", "attribute", "advancement", "recipe", "loot", "structure",
			"registry", "ui", "client", "file", "network", "limits");
	private static final Set<String> RESERVED_GLOBALS = Set.of(
			"_g", "_version", "assert", "collectgarbage", "coroutine", "debug", "dofile", "error",
			"bit32", "getmetatable", "io", "ipairs", "load", "loadfile", "loadstring", "luajava",
			"math", "mc", "module", "next", "os", "package",
			"pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset", "require", "select",
			"setmetatable", "string", "table", "tonumber", "tostring", "type", "utf8", "xpcall");
	private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
	private static final Map<String, String> NAMESPACE_OWNERS = new LinkedHashMap<>();
	private static final Map<String, String> ROOT_OWNERS = new LinkedHashMap<>();

	private McLuaIdeApi() {
	}

	public static DefinitionBuilder function(String ownerModId, String namespace, String method,
			LuaFunction function) {
		return new DefinitionBuilder(ownerModId, "MC", namespace, method, function);
	}

	/**
	 * Creates a function in an owned top-level Lua API table. For example, root {@code TestMod}
	 * and method {@code hello} produce {@code TestMod.hello(...)}.
	 */
	public static DefinitionBuilder rootFunction(String ownerModId, String rootName, String method,
			LuaFunction function) {
		return new DefinitionBuilder(ownerModId, rootName, "", method, function);
	}

	public static TranslationBuilder translation(String namespaceName, String summary) {
		return new TranslationBuilder(namespaceName, summary);
	}

	/** Registers one function. Call this from the owning mod's common initializer. */
	public static synchronized void register(Definition definition) {
		Objects.requireNonNull(definition, "definition");
		if (definition.apiRoot().equals("MC")) {
			String namespaceKey = definition.namespace().toLowerCase(Locale.ROOT);
			if (RESERVED_NAMESPACES.contains(namespaceKey)) {
				throw new IllegalArgumentException("不能覆盖 MC Lua IDE 内置命名空间：MC."
						+ definition.namespace());
			}
			String previousOwner = NAMESPACE_OWNERS.putIfAbsent(namespaceKey, definition.ownerModId());
			if (previousOwner != null && !previousOwner.equals(definition.ownerModId())) {
				throw new IllegalArgumentException("Lua 命名空间 MC." + definition.namespace()
						+ " 已属于模组 " + previousOwner);
			}
		} else {
			String rootKey = definition.apiRoot().toLowerCase(Locale.ROOT);
			if (RESERVED_GLOBALS.contains(rootKey)) {
				throw new IllegalArgumentException("不能覆盖 Lua 或 MC Lua IDE 保留的全局名称："
						+ definition.apiRoot());
			}
			String previousOwner = ROOT_OWNERS.putIfAbsent(rootKey, definition.ownerModId());
			if (previousOwner != null && !previousOwner.equals(definition.ownerModId())) {
				throw new IllegalArgumentException("Lua API 大类 " + definition.apiRoot()
						+ " 已属于模组 " + previousOwner);
			}
		}
		String key = definition.symbol().toLowerCase(Locale.ROOT);
		if (DEFINITIONS.putIfAbsent(key, definition) != null) {
			throw new IllegalArgumentException("Lua 函数已注册：" + definition.symbol());
		}
	}

	/** Returns immutable metadata snapshots for IDE synchronization and diagnostics. */
	public static synchronized List<Descriptor> descriptors() {
		return DEFINITIONS.values().stream().map(Definition::descriptor).toList();
	}

	/** Runtime hook used by MC Lua IDE; third-party mods normally only call {@link #register}. */
	public static synchronized void installInto(Globals globals, LuaTable mc,
			MinecraftServer server, String scriptName) {
		Map<String, LuaTable> namespaces = new LinkedHashMap<>();
		Map<String, LuaTable> roots = new LinkedHashMap<>();
		for (Definition definition : DEFINITIONS.values()) {
			LuaTable target;
			if (definition.apiRoot().equals("MC")) {
				String namespaceKey = definition.namespace().toLowerCase(Locale.ROOT);
				target = namespaces.computeIfAbsent(namespaceKey, ignored -> {
					LuaTable created = new LuaTable();
					mc.set(definition.namespace(), created);
					mc.set(namespaceKey, created);
					return created;
				});
			} else {
				String rootKey = definition.apiRoot().toLowerCase(Locale.ROOT);
				target = roots.computeIfAbsent(rootKey, ignored -> {
					LuaTable created = new LuaTable();
					globals.set(definition.apiRoot(), created);
					return created;
				});
			}
			target.set(definition.method(), runtimeFunction(definition, server, scriptName));
		}
	}

	private static VarArgFunction runtimeFunction(Definition definition, MinecraftServer server,
			String scriptName) {
		return new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs arguments) {
				try {
					Varargs result = definition.function().invoke(
							new FunctionContext(server, scriptName, definition), arguments);
					return result == null ? LuaValue.NIL : result;
				} catch (LuaError error) {
					throw error;
				} catch (Throwable error) {
					throw new LuaError("[" + definition.ownerModId() + "] " + definition.symbol()
							+ " 调用失败：" + message(error));
				}
			}
		};
	}

	@FunctionalInterface
	public interface LuaFunction {
		Varargs invoke(FunctionContext context, Varargs arguments) throws Exception;
	}

	@FunctionalInterface
	public interface ServerFunction<T> {
		T apply(MinecraftServer server) throws Exception;
	}

	/** Context passed to an extension function. Lua calls execute off the Minecraft server thread. */
	public static final class FunctionContext {
		private final MinecraftServer server;
		private final String scriptName;
		private final Definition definition;

		private FunctionContext(MinecraftServer server, String scriptName, Definition definition) {
			this.server = server;
			this.scriptName = scriptName;
			this.definition = definition;
		}

		public MinecraftServer server() {
			return server;
		}

		public String scriptName() {
			return scriptName;
		}

		public String ownerModId() {
			return definition.ownerModId();
		}

		public String symbol() {
			return definition.symbol();
		}

		/** Runs Minecraft state access on the authoritative server thread and waits at most 10 seconds. */
		public <T> T onServer(ServerFunction<T> action) {
			Objects.requireNonNull(action, "action");
			if (server.isSameThread()) {
				try {
					return action.apply(server);
				} catch (LuaError error) {
					throw error;
				} catch (Exception error) {
					throw new LuaError(message(error));
				}
			}
			CompletableFuture<T> future = server.submit(() -> {
				try {
					return action.apply(server);
				} catch (Exception error) {
					throw new RuntimeException(error);
				}
			});
			try {
				return future.get(SERVER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new LuaError("脚本已停止");
			} catch (TimeoutException error) {
				throw new LuaError("等待 Minecraft 服务端响应超时");
			} catch (ExecutionException error) {
				throw new LuaError(message(error.getCause()));
			}
		}
	}

	public enum ValueType {
		ANY,
		STRING,
		NUMBER,
		INTEGER,
		BOOLEAN,
		TABLE,
		FUNCTION,
		BLOCK_ID,
		ITEM_ID,
		ENTITY_ID,
		RESOURCE_ID,
		PLAYER_UUID,
		ENTITY_UUID
	}

	public record Parameter(String name, ValueType type, boolean optional) {
		public Parameter {
			requireLuaName(name, "参数名");
			type = Objects.requireNonNull(type, "type");
		}
	}

	public record Translation(String namespaceName, String summary, String returns, String details,
			String usage, Map<String, String> parameterDescriptions) {
		public Translation {
			namespaceName = boundedRequired(namespaceName, "分类名称", 96);
			summary = boundedRequired(summary, "函数简介", 512);
			returns = bounded(returns, 512);
			details = bounded(details, 2_048);
			usage = boundedRequired(usage, "用法模板", 8_192);
			Map<String, String> safe = new LinkedHashMap<>();
			if (parameterDescriptions != null) {
				parameterDescriptions.forEach((name, description) -> {
					requireLuaName(name, "参数说明名称");
					safe.put(name, bounded(description, 512));
				});
			}
			parameterDescriptions = Collections.unmodifiableMap(safe);
		}
	}

	public record Descriptor(String ownerModId, String apiRoot, String namespace, String method,
			List<Parameter> parameters, Map<String, Translation> translations, boolean clientRequired) {
		public Descriptor {
			requireModId(ownerModId);
			requireLuaName(apiRoot, "API 大类");
			if (apiRoot.equals("MC")) requireLuaName(namespace, "命名空间");
			else if (namespace == null || !namespace.isEmpty()) {
				throw new IllegalArgumentException("自定义 API 大类目前不接受额外命名空间");
			}
			requireLuaName(method, "函数名");
			parameters = List.copyOf(parameters == null ? List.of() : parameters);
			translations = immutableTranslations(translations);
		}

		public String symbol() {
			return apiRoot.equals("MC") ? "MC." + namespace + "." + method : apiRoot + "." + method;
		}

		public String signature() {
			return symbol() + "(" + String.join(", ", parameters.stream()
					.map(parameter -> parameter.name() + (parameter.optional() ? "?" : ""))
					.toList()) + ")";
		}

		public Translation translation(String requestedLocale) {
			String locale = normalizeLocale(requestedLocale);
			Translation exact = translations.get(locale);
			if (exact != null) return exact;
			int underscore = locale.indexOf('_');
			if (underscore > 0) {
				String language = locale.substring(0, underscore);
				for (Map.Entry<String, Translation> entry : translations.entrySet()) {
					if (entry.getKey().equals(language) || entry.getKey().startsWith(language + "_")) {
						return entry.getValue();
					}
				}
			}
			Translation english = translations.get("en_us");
			return english != null ? english : translations.values().iterator().next();
		}
	}

	public static final class Definition {
		private final Descriptor descriptor;
		private final LuaFunction function;

		private Definition(Descriptor descriptor, LuaFunction function) {
			this.descriptor = descriptor;
			this.function = Objects.requireNonNull(function, "function");
		}

		public String ownerModId() {
			return descriptor.ownerModId();
		}

		public String namespace() {
			return descriptor.namespace();
		}

		public String apiRoot() {
			return descriptor.apiRoot();
		}

		public String method() {
			return descriptor.method();
		}

		public String symbol() {
			return descriptor.symbol();
		}

		public Descriptor descriptor() {
			return descriptor;
		}

		public LuaFunction function() {
			return function;
		}
	}

	public static final class DefinitionBuilder {
		private final String ownerModId;
		private final String apiRoot;
		private final String namespace;
		private final String method;
		private final LuaFunction function;
		private final List<Parameter> parameters = new ArrayList<>();
		private final Map<String, Translation> translations = new LinkedHashMap<>();
		private boolean optionalStarted;
		private boolean clientRequired;

		private DefinitionBuilder(String ownerModId, String apiRoot, String namespace, String method,
				LuaFunction function) {
			requireModId(ownerModId);
			requireLuaName(apiRoot, "API 大类");
			if (apiRoot.equals("MC")) requireLuaName(namespace, "命名空间");
			else if (namespace == null || !namespace.isEmpty()) {
				throw new IllegalArgumentException("自定义 API 大类目前不接受额外命名空间");
			}
			requireLuaName(method, "函数名");
			this.ownerModId = ownerModId;
			this.apiRoot = apiRoot;
			this.namespace = namespace;
			this.method = method;
			this.function = Objects.requireNonNull(function, "function");
		}

		public DefinitionBuilder required(String name, ValueType type) {
			if (optionalStarted) throw new IllegalStateException("必填参数不能放在可选参数之后");
			parameters.add(new Parameter(name, type, false));
			return this;
		}

		public DefinitionBuilder optional(String name, ValueType type) {
			optionalStarted = true;
			parameters.add(new Parameter(name, type, true));
			return this;
		}

		public DefinitionBuilder translation(String locale, Translation translation) {
			String normalized = normalizeLocale(locale);
			if (!LOCALE.matcher(normalized).matches()) throw new IllegalArgumentException("无效语言代码：" + locale);
			if (translations.putIfAbsent(normalized, Objects.requireNonNull(translation, "translation")) != null) {
				throw new IllegalArgumentException("语言翻译重复：" + normalized);
			}
			return this;
		}

		/** Places this function in the IDE's client-Mod-only category. */
		public DefinitionBuilder requiresClientMod() {
			clientRequired = true;
			return this;
		}

		public Definition build() {
			if (translations.isEmpty()) throw new IllegalStateException("至少需要提供一种文档翻译");
			for (Translation translation : translations.values()) {
				for (String described : translation.parameterDescriptions().keySet()) {
					if (parameters.stream().noneMatch(parameter -> parameter.name().equals(described))) {
						throw new IllegalStateException("翻译描述了不存在的参数：" + described);
					}
				}
			}
			return new Definition(new Descriptor(ownerModId, apiRoot, namespace, method, parameters, translations,
					clientRequired), function);
		}
	}

	public static final class TranslationBuilder {
		private final String namespaceName;
		private final String summary;
		private String returns = "nil";
		private String details = "";
		private String usage;
		private final Map<String, String> parameterDescriptions = new LinkedHashMap<>();

		private TranslationBuilder(String namespaceName, String summary) {
			this.namespaceName = namespaceName;
			this.summary = summary;
		}

		public TranslationBuilder returns(String returns) {
			this.returns = returns;
			return this;
		}

		public TranslationBuilder details(String details) {
			this.details = details;
			return this;
		}

		public TranslationBuilder usage(String usage) {
			this.usage = usage;
			return this;
		}

		public TranslationBuilder parameter(String name, String description) {
			if (parameterDescriptions.putIfAbsent(name, description) != null) {
				throw new IllegalArgumentException("参数说明重复：" + name);
			}
			return this;
		}

		public Translation build() {
			return new Translation(namespaceName, summary, returns, details, usage, parameterDescriptions);
		}
	}

	private static Map<String, Translation> immutableTranslations(Map<String, Translation> translations) {
		if (translations == null || translations.isEmpty()) throw new IllegalArgumentException("翻译不能为空");
		Map<String, Translation> safe = new LinkedHashMap<>();
		translations.forEach((locale, translation) -> {
			String normalized = normalizeLocale(locale);
			if (!LOCALE.matcher(normalized).matches()) throw new IllegalArgumentException("无效语言代码：" + locale);
			safe.put(normalized, Objects.requireNonNull(translation, "translation"));
		});
		return Collections.unmodifiableMap(safe);
	}

	private static void requireModId(String value) {
		if (value == null || !MOD_ID.matcher(value).matches()) throw new IllegalArgumentException("无效模组 ID：" + value);
	}

	private static void requireLuaName(String value, String label) {
		if (value == null || !LUA_NAME.matcher(value).matches()) throw new IllegalArgumentException(label + "不是有效 Lua 标识符：" + value);
	}

	private static String normalizeLocale(String locale) {
		return locale == null ? "" : locale.strip().replace('-', '_').toLowerCase(Locale.ROOT);
	}

	private static String boundedRequired(String value, String label, int maximum) {
		String result = bounded(value, maximum);
		if (result.isBlank()) throw new IllegalArgumentException(label + "不能为空");
		return result;
	}

	private static String bounded(String value, int maximum) {
		String result = value == null ? "" : value;
		if (result.length() > maximum) throw new IllegalArgumentException("文档字段不能超过 " + maximum + " 个字符");
		return result;
	}

	private static String message(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}
}
