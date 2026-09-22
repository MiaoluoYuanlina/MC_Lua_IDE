package wiki.ica.xiaomiao.mcluaide.network;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, version-tolerant server policy applied to each new Lua execution. */
public record LuaLimits(
		int maxScriptChars,
		int maxRuntimeSeconds,
		int maxOutputLines,
		int maxOutputCharsPerLine,
		int maxEventCallbacks,
		int eventQueueCapacity,
		int minTickEventInterval,
		int instructionHookInterval,
		int maxDataFileBytes,
		int maxDataFiles,
		int networkTimeoutSeconds,
		int maxNetworkResponseBytes,
		boolean requireClientMod,
		boolean allowEvents,
		boolean allowCommandApi,
		boolean allowWorldApi,
		boolean allowPlayerApi,
		boolean allowEntityApi,
		boolean allowInventoryApi,
		boolean allowPresentationApi,
		boolean allowServerApi,
		boolean allowRegistryApi,
		boolean allowFileRead,
		boolean allowFileWrite,
		boolean allowFileDelete,
		boolean allowDynamicCode,
		boolean allowCoroutine,
		boolean allowMetatables,
		boolean allowGcControl,
		boolean allowJavaInterop,
		boolean allowOperatingSystem,
		boolean allowUnrestrictedFileIo,
		boolean allowPackageLoading,
		boolean allowDebugLibrary,
		boolean allowNetworkAccess) {

	public static final int MAX_DATA_FILE_BYTES_HARD = 16 * 1024 * 1024;

	public LuaLimits {
		range("脚本最大字符数", maxScriptChars, 1_024, LuaProtocol.MAX_SCRIPT_CHARS);
		range("最长运行秒数", maxRuntimeSeconds, 0, 86_400);
		range("最大输出行数", maxOutputLines, 10, 1_000_000);
		range("单行输出字符数", maxOutputCharsPerLine, 128, LuaProtocol.MAX_MESSAGE_CHARS);
		range("事件监听数量", maxEventCallbacks, 0, 2_048);
		range("事件队列容量", eventQueueCapacity, 16, 65_536);
		range("Tick 事件最小间隔", minTickEventInterval, 1, 1_200);
		range("指令检查间隔", instructionHookInterval, 100, 100_000);
		range("单个数据文件字节数", maxDataFileBytes, 1_024, MAX_DATA_FILE_BYTES_HARD);
		range("数据文件数量", maxDataFiles, 1, 1_024);
		range("网络超时秒数", networkTimeoutSeconds, 1, 60);
		range("网络响应字节数", maxNetworkResponseBytes, 1_024, 8 * 1024 * 1024);
	}

	public static LuaLimits defaults() {
		return new LuaLimits(
				100_000, 0, 10_000, 8_192,
				128, 1_024, 1, 500,
				1_048_576, 64, 10, 1_048_576,
				false,
				true, true, true, true, true, true, true, true, true,
				false, false, false,
				false, false, true, false,
				false, false, false, false, false, false);
	}

	public String encode() {
		StringBuilder result = new StringBuilder();
		toMap().forEach((key, value) -> result.append(key).append('=').append(value).append('\n'));
		return result.toString();
	}

	public Map<String, String> toMap() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("maxScriptChars", Integer.toString(maxScriptChars));
		values.put("maxRuntimeSeconds", Integer.toString(maxRuntimeSeconds));
		values.put("maxOutputLines", Integer.toString(maxOutputLines));
		values.put("maxOutputCharsPerLine", Integer.toString(maxOutputCharsPerLine));
		values.put("maxEventCallbacks", Integer.toString(maxEventCallbacks));
		values.put("eventQueueCapacity", Integer.toString(eventQueueCapacity));
		values.put("minTickEventInterval", Integer.toString(minTickEventInterval));
		values.put("instructionHookInterval", Integer.toString(instructionHookInterval));
		values.put("maxDataFileBytes", Integer.toString(maxDataFileBytes));
		values.put("maxDataFiles", Integer.toString(maxDataFiles));
		values.put("networkTimeoutSeconds", Integer.toString(networkTimeoutSeconds));
		values.put("maxNetworkResponseBytes", Integer.toString(maxNetworkResponseBytes));
		values.put("requireClientMod", Boolean.toString(requireClientMod));
		values.put("allowEvents", Boolean.toString(allowEvents));
		values.put("allowCommandApi", Boolean.toString(allowCommandApi));
		values.put("allowWorldApi", Boolean.toString(allowWorldApi));
		values.put("allowPlayerApi", Boolean.toString(allowPlayerApi));
		values.put("allowEntityApi", Boolean.toString(allowEntityApi));
		values.put("allowInventoryApi", Boolean.toString(allowInventoryApi));
		values.put("allowPresentationApi", Boolean.toString(allowPresentationApi));
		values.put("allowServerApi", Boolean.toString(allowServerApi));
		values.put("allowRegistryApi", Boolean.toString(allowRegistryApi));
		values.put("allowFileRead", Boolean.toString(allowFileRead));
		values.put("allowFileWrite", Boolean.toString(allowFileWrite));
		values.put("allowFileDelete", Boolean.toString(allowFileDelete));
		values.put("allowDynamicCode", Boolean.toString(allowDynamicCode));
		values.put("allowCoroutine", Boolean.toString(allowCoroutine));
		values.put("allowMetatables", Boolean.toString(allowMetatables));
		values.put("allowGcControl", Boolean.toString(allowGcControl));
		values.put("allowJavaInterop", Boolean.toString(allowJavaInterop));
		values.put("allowOperatingSystem", Boolean.toString(allowOperatingSystem));
		values.put("allowUnrestrictedFileIo", Boolean.toString(allowUnrestrictedFileIo));
		values.put("allowPackageLoading", Boolean.toString(allowPackageLoading));
		values.put("allowDebugLibrary", Boolean.toString(allowDebugLibrary));
		values.put("allowNetworkAccess", Boolean.toString(allowNetworkAccess));
		return values;
	}

	public static LuaLimits decode(String encoded) {
		Map<String, String> values = defaults().toMap();
		if (encoded != null) {
			for (String line : encoded.split("\\R")) {
				int separator = line.indexOf('=');
				if (separator <= 0) continue;
				String key = line.substring(0, separator).strip();
				if (values.containsKey(key)) values.put(key, line.substring(separator + 1).strip());
			}
		}
		return fromMap(values);
	}

	public static LuaLimits fromMap(Map<String, String> values) {
		LuaLimits defaults = defaults();
		Map<String, String> fallback = defaults.toMap();
		return new LuaLimits(
				integer(values, fallback, "maxScriptChars"),
				integer(values, fallback, "maxRuntimeSeconds"),
				integer(values, fallback, "maxOutputLines"),
				integer(values, fallback, "maxOutputCharsPerLine"),
				integer(values, fallback, "maxEventCallbacks"),
				integer(values, fallback, "eventQueueCapacity"),
				integer(values, fallback, "minTickEventInterval"),
				integer(values, fallback, "instructionHookInterval"),
				integer(values, fallback, "maxDataFileBytes"),
				integer(values, fallback, "maxDataFiles"),
				integer(values, fallback, "networkTimeoutSeconds"),
				integer(values, fallback, "maxNetworkResponseBytes"),
				bool(values, fallback, "requireClientMod"),
				bool(values, fallback, "allowEvents"),
				bool(values, fallback, "allowCommandApi"),
				bool(values, fallback, "allowWorldApi"),
				bool(values, fallback, "allowPlayerApi"),
				bool(values, fallback, "allowEntityApi"),
				bool(values, fallback, "allowInventoryApi"),
				bool(values, fallback, "allowPresentationApi"),
				bool(values, fallback, "allowServerApi"),
				bool(values, fallback, "allowRegistryApi"),
				bool(values, fallback, "allowFileRead"),
				bool(values, fallback, "allowFileWrite"),
				bool(values, fallback, "allowFileDelete"),
				bool(values, fallback, "allowDynamicCode"),
				bool(values, fallback, "allowCoroutine"),
				bool(values, fallback, "allowMetatables"),
				bool(values, fallback, "allowGcControl"),
				bool(values, fallback, "allowJavaInterop"),
				bool(values, fallback, "allowOperatingSystem"),
				bool(values, fallback, "allowUnrestrictedFileIo"),
				bool(values, fallback, "allowPackageLoading"),
				bool(values, fallback, "allowDebugLibrary"),
				bool(values, fallback, "allowNetworkAccess"));
	}

	private static int integer(Map<String, String> values, Map<String, String> fallback, String key) {
		String raw = values.getOrDefault(key, fallback.get(key));
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException(key + " 必须是整数");
		}
	}

	private static boolean bool(Map<String, String> values, Map<String, String> fallback, String key) {
		String raw = values.getOrDefault(key, fallback.get(key));
		if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
			throw new IllegalArgumentException(key + " 必须是 true 或 false");
		}
		return Boolean.parseBoolean(raw);
	}

	private static void range(String label, int value, int minimum, int maximum) {
		if (value < minimum || value > maximum) {
			throw new IllegalArgumentException(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
		}
	}
}
