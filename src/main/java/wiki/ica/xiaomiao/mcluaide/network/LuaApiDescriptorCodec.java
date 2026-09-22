package wiki.ica.xiaomiao.mcluaide.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.lang.reflect.Type;
import java.util.List;

/** JSON wire codec for server-provided third-party Lua API documentation. */
public final class LuaApiDescriptorCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Type LIST_TYPE = new TypeToken<List<McLuaIdeApi.Descriptor>>() { }.getType();

	private LuaApiDescriptorCodec() {
	}

	public static String encode(List<McLuaIdeApi.Descriptor> descriptors) {
		return GSON.toJson(descriptors == null ? List.of() : descriptors, LIST_TYPE);
	}

	public static List<McLuaIdeApi.Descriptor> decode(String json) {
		if (json == null || json.isBlank()) return List.of();
		List<McLuaIdeApi.Descriptor> result = GSON.fromJson(json, LIST_TYPE);
		return result == null ? List.of() : List.copyOf(result);
	}
}
