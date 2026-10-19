package wiki.ica.xiaomiao.mcluaide.client;

import org.luaj.vm2.lib.jse.JsePlatform;
import java.util.List;

/** Function metadata, lexical ranges and fold rebasing independent of the game window. */
public final class LuaFunctionModelSmoke {
    private static int checks;

    public static void main(String[] args) {
        String source = """
                local function welcome(player, message, ...)
                    --#name=欢迎玩家
                    --#Introduction=向指定玩家发送欢迎信息
                    --@player=目标玩家 --@message=欢迎内容
                    --@...=其他参数
                    --@count=发送次数
                    local count = 1
                    return message, player, ...
                end
                welcome('Alex', '你好')
                """;
        var model = LuaFunctionModel.parse(source);
        var welcome = model.find("welcome", source.length());
        check(welcome != null && welcome.local(), "local function discovered");
        check(welcome.displayName().equals("欢迎玩家"), "translated name");
        check(welcome.introduction().equals("向指定玩家发送欢迎信息"), "introduction");
        check(welcome.parameters().equals(List.of("player", "message", "...")), "parameters and varargs");
        check(welcome.notes().get("player").equals("目标玩家") && welcome.notes().get("message").equals("欢迎内容"), "multiple directives on one comment");
        check(welcome.notes().get("...").equals("其他参数"), "varargs note");
        check(welcome.tooltip().contains("参数 player：目标玩家") && welcome.tooltip().contains("变量 count：发送次数"), "function tooltip");
        check(model.variableTooltip("player", source.indexOf("player,")).contains("目标玩家"), "parameter declaration hover");
        check(model.variableTooltip("count", source.indexOf("count =")).contains("发送次数"), "local variable hover");
        check(model.variableTooltip("player", source.length()).isEmpty(), "variable notes scoped to body");
        check(!model.identifierAt(source.indexOf("目标玩家")), "no annotation tooltip inside comments");
        check(welcome.foldable() && source.substring(welcome.hiddenStart(), welcome.hiddenEnd()).stripTrailing().endsWith("end"), "fold includes closing end");

        String nested = """
                function outer(value)
                    --#name=外层
                    --@value=外层参数
                    if value then
                        local function inner(value)
                            --#name=内层 --#Introduction=嵌套函数
                            for i = 1, 2 do
                                while i < 2 do i = i + 1 end
                                do print(i) end
                            end
                            repeat value = value - 1 until value == 0
                            return value
                        end
                        inner(value)
                    elseif value == nil then
                        print('end function if')
                    end
                    return value
                end
                outer(3)
                """;
        var nestedModel = LuaFunctionModel.parse(nested);
        var outer = nestedModel.find("outer", nested.length());
        var inner = nestedModel.find("inner", nested.indexOf("inner(value)"));
        check(nestedModel.functions.size() == 2 && outer.complete() && inner.complete(), "nested blocks matched");
        check(outer.endLine() == 19 && inner.endLine() == 13, "nested end ranges");
        check(inner.introduction().equals("嵌套函数"), "same-line function metadata");
        check(nestedModel.find("inner", nested.length()) == null, "local function does not leak scope");
        check(nestedModel.find("inner", inner.declarationStart() - 1) == null, "local function not visible before declaration");
        check(nestedModel.find("inner", nested.indexOf("print('end")) == null, "local function does not leak into sibling if branch");
        check(nestedModel.variableTooltip("value", nested.indexOf("return value")).isEmpty(), "undocumented nested parameter shadows outer note");
        check(nestedModel.variableTooltip("value", nested.lastIndexOf("return value")).contains("外层参数"), "outer note restored outside nested function");

        var lex = LuaFunctionModel.parse("""
                -- function fake() end
                local text = [==[ function another() end ]==]
                function real()
                    --[=[ --#name=忽略长注释 function fake() end ]=]
                    --#name=真实函数
                    --#introduction=大小写兼容
                    local text = "end \\\" function"
                    return [=[end function repeat]=]
                end
                """);
        check(lex.functions.size() == 1 && lex.functions.getFirst().complete(), "strings and long comments ignored");
        check(lex.functions.getFirst().displayName().equals("真实函数") && lex.functions.getFirst().introduction().equals("大小写兼容"), "long-comment directives ignored");

        var forms = LuaFunctionModel.parse("""
                function library:send(消息)
                    --@消息=中文参数
                    return 消息
                end
                local assigned = function(value)
                    --#name=赋值函数
                    return value
                end
                register(function(value)
                    return value
                end)
                """);
        check(forms.functions.size() == 3, "named, assigned and anonymous functions");
        check(forms.find("library.send", forms.source.length()).notes().get("消息").equals("中文参数"), "method lookup and Unicode parameter");
        check(forms.find("assigned", forms.source.length()).displayName().equals("赋值函数"), "assignment metadata");
        check(forms.find("assigned", forms.source.indexOf("assigned =")) != null, "assignment declaration has metadata");
        check(forms.functions.getLast().symbol().isEmpty() && forms.functions.getLast().foldable(), "anonymous callback folds");
        check(forms.source.substring(forms.functions.getLast().hiddenEnd()).startsWith("end)"), "callback closing expression stays visible");
        var incomplete = LuaFunctionModel.parse("function pending()\n --#name=未完成\n if true then\n end\n");
        check(!incomplete.functions.getFirst().foldable() && incomplete.functions.getFirst().displayName().equals("未完成"), "incomplete function retains metadata without folding");
        check(!LuaFunctionModel.parse("function single() return 1 end").functions.getFirst().foldable(), "single line does not fold");
        var sharedLine = LuaFunctionModel.parse("function one()\n return 1\nend; function two()\n return 2\nend\n");
        check(sharedLine.functions.getFirst().hiddenEnd() == sharedLine.source.indexOf("end;"), "fold preserves other declarations on closing line");

        var state = new LuaFunctionFolding.State();
        var foldModel = state.model(nested);
        state.toggle(foldModel.find("outer", nested.length()));
        state.toggle(foldModel.find("inner", nested.indexOf("inner(value)")));
        check(state.hidden(nested.indexOf("return value")), "outer and nested folds hide body");
        check(state.reveal(nested.indexOf("return value")) && !state.hidden(nested.indexOf("return value")), "jump unfolds all containing functions");
        state.toggle(foldModel.find("outer", nested.length()));
        String prefixed = "-- preface\n" + nested;
        var rebased = state.model(prefixed);
        check(state.collapsed(rebased.find("outer", prefixed.length())), "fold rebased after inserted prefix");
        check(state.beforeEdit(rebased.functions.getFirst().start(), rebased.functions.getFirst().start()) && !state.hidden(prefixed.indexOf("return value")), "editing folded header unfolds safely");
        state.toggle(rebased.functions.getFirst());
        state.model("print('deleted')");
        check(!state.hidden(3), "deleted function clears stale fold");

        String structural = """
                local values = {
                    1,
                    {
                        2,
                        3
                    },
                }
                if values then
                    if #values > 0 then
                        print('end } if')
                    else
                        print('empty')
                    end
                elseif values == nil then
                    print('nil')
                else
                    print('other')
                end
                print('after')
                """;
        var structuralModel = LuaFunctionModel.parse(structural);
        check(structuralModel.functions.isEmpty() && structuralModel.folds.size() == 4, "arrays and if do not become completion functions");
        var array = structuralModel.atLine(1);
        var innerArray = structuralModel.atLine(3);
        var conditional = structuralModel.atLine(8);
        check(array != null && array.endLine() == 7 && innerArray.endLine() == 6, "nested array braces matched");
        check(conditional != null && conditional.endLine() == 18 && structuralModel.atLine(9).endLine() == 13, "if folds all elseif and else branches to matching end");
        check(structural.substring(conditional.hiddenStart(), conditional.hiddenEnd()).stripTrailing().endsWith("end"), "if closing end included in fold");
        var structures = new LuaFunctionFolding.State();
        var structureStateModel = structures.model(structural);
        structures.toggle(structureStateModel.atLine(1));
        structures.toggle(structureStateModel.atLine(3));
        check(structures.hidden(structural.indexOf("2,")) && !structures.hidden(structural.indexOf("if values")), "array fold does not hide following statement");
        structures.toggle(structureStateModel.atLine(1));
        check(structures.hidden(structural.indexOf("2,")), "nested array fold survives parent reopen");
        structures.toggle(structureStateModel.atLine(8));
        check(structures.hidden(structural.indexOf("elseif")) && !structures.hidden(structural.indexOf("print('after')")), "conditional boundaries preserve following code");
        check(structures.reveal(structural.indexOf("print('other')")), "cursor reveals collapsed else branch");
        structures.toggle(structureStateModel.atLine(8));
        var shifted = structures.model("-- prefix\n" + structural);
        check(structures.collapsed(shifted.atLine(9)), "conditional fold rebases after insert");
        check(structures.beforeEdit(shifted.atLine(9).start(), shifted.atLine(9).start()), "editing folded if reveals hidden branches");
        check(LuaFunctionModel.parse("-- if a then {\nlocal s = [=[ if a then { end } ]=]\n--[=[ if { end } ]=]\n").folds.isEmpty(), "no structural folds from strings or comments");
        check(LuaFunctionModel.parse("if true then\n print(1)\n").atLine(1) == null, "unfinished if has no fold");
        check(LuaFunctionModel.parse("local t = {\n 1,\n").atLine(1) == null, "unfinished array has no fold");
        check(LuaFunctionModel.parse("local t = {}\nif true then print(1) end").atLine(2) == null, "single-line structures have no fold");
        var mixed = LuaFunctionModel.parse("if (function()\n return true\nend)() then\n local a = {\n function()\n if true then\n return 1\n end\n end,\n }\nend\nprint(2)");
        check(mixed.atLine(1).endLine() == 11 && mixed.atLine(4).endLine() == 10 && mixed.atLine(6).endLine() == 8, "functions in conditions and arrays do not consume outer end");
        String large = "local t = {\n1\n}\nif true then\nprint(1)\nend\n".repeat(2000);
        check(LuaFunctionModel.parse(large).folds.size() == 4000, "many array and if ranges parsed");
        var manyFolds = new LuaFunctionFolding.State();
        var manyModel = manyFolds.model(large);
        for(var fold:manyModel.folds)manyFolds.toggle(fold);
        check(manyFolds.hidden(large.lastIndexOf("print(1)")) && !manyFolds.hidden(large.lastIndexOf("if true")), "many collapsed intervals preserve visible headers");
        check(manyFolds.reveal(large.lastIndexOf("print(1)")) && !manyFolds.hidden(large.lastIndexOf("print(1)")), "interval index refreshed on reveal");

        String bulkSource = structural + nested;
        var bulk = new LuaFunctionFolding.State();
        var bulkModel = bulk.model(bulkSource);
        bulk.apply(LuaFunctionFolding.Action.COLLAPSE_FUNCTIONS);
        check(bulkModel.functions.stream().allMatch(bulk::collapsed), "bulk folds all named and nested functions");
        check(bulkModel.folds.stream().filter(f -> !(f instanceof LuaFunctionModel.Function)).noneMatch(bulk::collapsed), "function-only fold leaves if and tables expanded");
        bulk.apply(LuaFunctionFolding.Action.COLLAPSE_ALL);
        check(bulkModel.folds.stream().filter(LuaFunctionModel.Fold::foldable).allMatch(bulk::collapsed), "bulk folds functions, nested if and arrays");
        check(bulk.visibleCursor(bulkSource.indexOf("2,")) == structuralModel.atLine(1).start(), "hidden caret moves to outer visible header");
        var snapshot = bulk.snapshot(bulkSource);
        var restored = new LuaFunctionFolding.State(); restored.restore(bulkSource, snapshot);
        check(restored.snapshot(bulkSource).equals(snapshot), "all nested folds restored from snapshot");
        restored.restore("-- externally edited\n" + bulkSource, snapshot);
        check(restored.snapshot("-- externally edited\n" + bulkSource).starts().isEmpty(), "changed source does not restore stale offsets");
        bulk.apply(LuaFunctionFolding.Action.EXPAND_ALL);
        check(bulkModel.folds.stream().noneMatch(bulk::collapsed) && bulk.snapshot(bulkSource).starts().isEmpty(), "expand-all also clears hidden nested folds");
        var callbackState = new LuaFunctionFolding.State(); callbackState.model(forms.source);
        callbackState.apply(LuaFunctionFolding.Action.COLLAPSE_FUNCTIONS);
        check(callbackState.model(forms.source).functions.stream().allMatch(callbackState::collapsed), "bulk folds assigned, method and anonymous callback functions");

        var lua = JsePlatform.standardGlobals();
        lua.load("""
                function sum(left, right)
                    --#name=求和
                    --#Introduction=返回两个数的和
                    --@left=左值
                    --@right=右值
                    return left + right
                end
                return sum(2, 3)
                """).call();
        check(lua.get("sum").call(org.luaj.vm2.LuaValue.valueOf(7), org.luaj.vm2.LuaValue.valueOf(8)).toint() == 15, "annotations are ordinary Lua comments at runtime");
        System.out.println("Function editor smoke passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
