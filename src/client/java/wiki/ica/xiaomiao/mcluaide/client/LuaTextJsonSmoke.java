package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.ArrayList;
import java.util.List;

public final class LuaTextJsonSmoke {
    private static int checks;
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Globals globals = JsePlatform.standardGlobals();
        var variable = variable("value");
        List<String> strings = new ArrayList<>(List.of("", "玩家名字", "\"quoted\"", "\\path\\", "line\nnext\r\ttab",
                "😀e\u0301\u2028\u2029", "</script>", "\"},\"click_event\":{\"action\":\"run_command\",\"command\":\"bad\"}",
                "[%z\\1-\\31] .. value", "\u007f", "000123"));
        for (int c = 0; c < 32; c++) strings.add("before" + (char)c + "123after");
        for (String value : strings) {
            globals.set("value", LuaValue.valueOf(value));
            JsonElement json = evaluate(globals, List.of(variable));
            require(json.getAsJsonObject().get("text").getAsString().equals(value), "Variable text was not preserved");
            require(!json.getAsJsonObject().has("click_event"), "Variable string injected JSON properties");
            parse(json);
            require(globals.load("return " + LuaTextJsonDocument.luaString(value)).call().checkjstring().equals(value), "Lua literal failed round-trip");
        }
        for (LuaValue value : List.of(LuaValue.valueOf(42), LuaValue.valueOf(2.5), LuaValue.TRUE, LuaValue.FALSE, LuaValue.NIL)) {
            globals.set("value", value);
            require(evaluate(globals, List.of(variable)).getAsJsonObject().get("text").getAsString()
                    .equals(globals.get("tostring").call(value).checkjstring()), "Non-string value was not stringified");
        }
        globals.load("event = {playerName='张三'}; items = {{count=7}}; suffix = '!' ").call();
        require(evaluate(globals, List.of(variable("event.playerName .. '：' .. items[1].count")))
                .getAsJsonObject().get("text").getAsString().equals("张三：7"), "User's concatenated expression lost content");
        var first = new LuaTextJsonDocument.Segment(); first.text = "欢迎：";
        var name = variable("event.playerName"); name.bold = true; name.underlined = true;
        name.hover = LuaTextJsonDocument.Hover.TEXT; name.hoverText = "信息";
        name.click = LuaTextJsonDocument.Click.RUN_COMMAND; name.clickValue = "/help";
        var count = variable("items[1].count");
        var score = new LuaTextJsonDocument.Segment(); score.kind = LuaTextJsonDocument.Kind.SCORE;
        var selector = new LuaTextJsonDocument.Segment(); selector.kind = LuaTextJsonDocument.Kind.SELECTOR;
        var gradient = new LuaTextJsonDocument.Segment(); gradient.text = "彩😀"; gradient.gradient = true;
        List<LuaTextJsonDocument.Segment> segments = List.of(first, name, count, variable("suffix"), score, selector, gradient);
        JsonElement combined = evaluate(globals, segments); parse(combined);
        var extras = combined.getAsJsonObject().getAsJsonArray("extra");
        require(extras.size() == 8 && extras.get(1).getAsJsonObject().get("text").getAsString().equals("张三")
                && extras.get(2).getAsJsonObject().get("text").getAsString().equals("7")
                && extras.get(3).getAsJsonObject().get("text").getAsString().equals("!"), "Mixed segment order changed");
        var styled = extras.get(1).getAsJsonObject();
        require(styled.get("bold").getAsBoolean() && styled.get("underlined").getAsBoolean()
                && styled.getAsJsonObject("hover_event").get("value").getAsString().equals("信息")
                && styled.getAsJsonObject("click_event").get("command").getAsString().equals("/help"), "Variable lost styles or interactions");
        require(extras.get(4).getAsJsonObject().has("score") && extras.get(5).getAsJsonObject().has("selector")
                && !extras.get(6).getAsJsonObject().get("color").equals(extras.get(7).getAsJsonObject().get("color")), "Static/dynamic mixed kinds lost data");
        require(LuaTextJsonDocument.build(List.of(name)).getAsJsonObject().get("text").getAsString().contains("event.playerName"), "Preview did not identify variable");
        var copy = name.copy(); require(copy.luaVariable.equals(name.luaVariable) && copy.bold && copy.click == name.click, "Copied segment lost variable");
        int[] calls = {0};
        globals.set("nextValue", new ZeroArgFunction() { public LuaValue call() { return LuaValue.valueOf(++calls[0]); } });
        var call = variable("nextValue()");
        LuaTextJsonDocument.build(List.of(call)); String expression = LuaTextJsonDocument.luaExpression(List.of(call));
        require(calls[0] == 0, "Preview/generation executed an expression");
        require(JsonParser.parseString(globals.load("return " + expression).call().checkjstring()).getAsJsonObject().get("text").getAsString().equals("1")
                && calls[0] == 1, "Runtime expression evaluated multiple times");
        require(expression.contains(" .. "), "Variable was not joined with Lua concatenation");
        require(globals.load("local function capture(...) return select('#', ...) end\nreturn capture(" + expression + ", 'next')").call().checkint() == 2,
                "Generated expression leaked gsub's second return value");
        var local = variable("name");
        String localJson = globals.load("local name = '作用域'\nreturn " + LuaTextJsonDocument.luaExpression(List.of(local))).call().checkjstring();
        require(JsonParser.parseString(localJson).getAsJsonObject().get("text").getAsString().equals("作用域"), "Local variable scope was lost");
        String lua = "local name = 'name'\nMC.Chat.broadcast(" + LuaTextJsonDocument.luaExpression(List.of(local)) + ")\n";
        var analysis = LuaCodeAnalyzer.analyze("dynamic_text", lua);
        require(analysis.valid(), "Generated variable code was falsely diagnosed: " + analysis);
        for (String invalid : List.of("", " ", "local x = 1", "event.", "1); error('bad'); return (2", "x".repeat(1025))) {
            try { LuaTextJsonDocument.luaExpression(List.of(variable(invalid))); throw new AssertionError("Invalid expression accepted: " + invalid); }
            catch (IllegalArgumentException expected) { require(expected.getMessage().contains("第 1 段"), "Missing segment error context"); }
        }
        variable.gradient = true;
        try { LuaTextJsonDocument.luaExpression(List.of(variable)); throw new AssertionError("Dynamic gradient accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        first.text = "literal\n\"\\\u2028\u2029";
        require(evaluate(globals, List.of(first)).equals(LuaTextJsonDocument.build(List.of(first))), "Static generation changed");
        // Exercise the new input field routing without a real client or clipboard.
        var overlay = new LuaTextJsonOverlay(null, null, ignored -> {}, ignored -> {});
        var listField = LuaTextJsonOverlay.class.getDeclaredField("segments"); listField.setAccessible(true);
        @SuppressWarnings("unchecked") var list = (List<LuaTextJsonDocument.Segment>) listField.get(overlay);
        list.getFirst().kind = LuaTextJsonDocument.Kind.LUA_VARIABLE;
        var firstFocus = LuaTextJsonOverlay.class.getDeclaredMethod("firstFocus"); firstFocus.setAccessible(true);
        var focus = LuaTextJsonOverlay.class.getDeclaredField("focus"); focus.setAccessible(true); focus.set(overlay, firstFocus.invoke(overlay));
        var setValue = LuaTextJsonOverlay.class.getDeclaredMethod("setActiveValue", String.class); setValue.setAccessible(true); setValue.invoke(overlay, "event.playerName");
        var activeValue = LuaTextJsonOverlay.class.getDeclaredMethod("activeValue"); activeValue.setAccessible(true);
        require(activeValue.invoke(overlay).equals("event.playerName") && list.getFirst().luaVariable.equals("event.playerName"), "Variable input routed to wrong field");
        System.out.println("Text JSON variable smoke passed: " + checks + " checks; runtime concat, escaping, mixed kinds/styles, local scope, evaluation bounds, preview, syntax and UI field routing");
    }
    private static LuaTextJsonDocument.Segment variable(String expression) {
        var segment = new LuaTextJsonDocument.Segment(); segment.kind = LuaTextJsonDocument.Kind.LUA_VARIABLE;
        segment.luaVariable = expression; return segment;
    }
    private static JsonElement evaluate(Globals globals, List<LuaTextJsonDocument.Segment> segments) {
        return JsonParser.parseString(globals.load("return " + LuaTextJsonDocument.luaExpression(segments)).call().checkjstring());
    }
    private static void parse(JsonElement json) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var parsed = ComponentSerialization.CODEC.parse(ops, json);
        require(parsed.result().isPresent(), "Game could not parse generated component: " + parsed.error());
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
