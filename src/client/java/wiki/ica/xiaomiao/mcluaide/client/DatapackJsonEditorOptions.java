package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wiki.ica.xiaomiao.mcluaide.editor.ComponentTreeSchema;

import java.util.ArrayList;
import java.util.List;

/** Server-derived samples and observed field suggestions for one JSON category. */
record DatapackJsonEditorOptions(List<String> sources, int total, int observed,
		ComponentTreeSchema.Node schema, boolean limited, boolean sourcesLimited) {
	static DatapackJsonEditorOptions decode(String encoded) {
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		List<String> sources = new ArrayList<>();
		for (JsonElement value : root.getAsJsonArray("sources")) sources.add(value.getAsString());
		return new DatapackJsonEditorOptions(List.copyOf(sources), root.get("total").getAsInt(),
				root.get("observed").getAsInt(),
				ComponentTreeSchema.Node.decode(root.getAsJsonObject("schema")),
				root.has("schemaLimited") && root.get("schemaLimited").getAsBoolean(),
				root.has("sourcesLimited") && root.get("sourcesLimited").getAsBoolean());
	}
}
