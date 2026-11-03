package wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.level;

import com.fasterxml.jackson.databind.JsonNode;

import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.Schemas;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolContext;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.protocol.ToolResult;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.BaseTool;
import wiki.ica.xiaomiao.mcluaide.mcp.chapman.tools.annotations.McpTool;

@McpTool(name = "level_set_weather", description = "Sets the weather and its duration for a dimension.")
public final class LevelSetWeatherTool extends BaseTool {

    private static final JsonNode SCHEMA =
            Schemas.object()
                    .required("dimension", Schemas.string("Dimension identifier"))
                    .required("weather", Schemas.enumOf("Weather type", "clear", "rain", "thunder"))
                    .optional("duration_ticks", Schemas.integer("Duration in ticks (default 6000)"))
                    .build();

    public LevelSetWeatherTool() {
        super("level_set_weather");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        var r = reader(arguments);
        String dim = r.requireString("dimension");
        String weather = r.requireString("weather");
        int duration = r.optInt("duration_ticks", 6000);
        return onMainThread(
                context,
                ignored -> {
                    context.adapter().levelSetWeather(dim, weather, duration);
                    return ToolResult.ofText("Weather set to " + weather + " for " + duration + " ticks.");
                });
    }
}
