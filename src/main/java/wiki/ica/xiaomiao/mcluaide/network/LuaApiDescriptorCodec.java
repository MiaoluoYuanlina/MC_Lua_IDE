package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;

import java.lang.reflect.Type;
import java.util.List;

/** JSON wire codec for server-provided third-party Lua API documentation. */
public final class LuaApiDescriptorCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Type LIST_TYPE = new TypeToken<List<McLuaIdeApi.Descriptor>>() { }.getType();

	public record Documentation(List<McLuaIdeApi.Descriptor> functions,
			List<McLuaIdeDocsApi.Page> pages) {
		public Documentation {
			functions = List.copyOf(functions == null ? List.of() : functions);
			pages = List.copyOf(pages == null ? List.of() : pages);
		}
	}

	private LuaApiDescriptorCodec() {
	}

	public static String encode(List<McLuaIdeApi.Descriptor> descriptors) {
		return GSON.toJson(descriptors == null ? List.of() : descriptors, LIST_TYPE);
	}

	public static String encode(Documentation documentation) {
		return GSON.toJson(documentation);
	}

	public static List<McLuaIdeApi.Descriptor> decode(String json) {
		if (json == null || json.isBlank()) return List.of();
		if (json.stripLeading().startsWith("{")) return decodeDocumentation(json).functions();
		List<McLuaIdeApi.Descriptor> result = GSON.fromJson(json, LIST_TYPE);
		return result == null ? List.of() : List.copyOf(result);
	}

	public static Documentation decodeDocumentation(String json) {
		if (json == null || json.isBlank()) return new Documentation(List.of(), List.of());
		if (json.stripLeading().startsWith("[")) return new Documentation(decode(json), List.of());
		Documentation result = GSON.fromJson(json, Documentation.class);
		return result == null ? new Documentation(List.of(), List.of())
				: new Documentation(result.functions(), result.pages());
	}
}
