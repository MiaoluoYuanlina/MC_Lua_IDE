package wiki.ica.xiaomiao.mcluaide.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptCommandArgument;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Actual Brigadier parsing/completion, without initializing a client or touching server/user scripts. */
public final class LuaScriptCommandSmoke {
	private static final List<String> FILES = List.of("root", "tools/math", "tools/deep/main", "other/main", "other/helper", "../secret", "/absolute", "root");
	public static void main(String[] args) throws Exception {
		for (String name : List.of("root", "tools/main", "tools/main.lua", "\"tools/main.lua\"", "'tools/main'"))
			require(LuaServerCommands.parseName(name).equals(name.contains("/") ? "tools/main" : "root"), "Native command canonical path wrong");
		for (String invalid : List.of("../secret", "/absolute", "root extra", "\"tools/main\" extra", "tools/main.lua extra")) {
			try { LuaServerCommands.parseName(invalid); throw new AssertionError("Native command accepted " + invalid); }
			catch (CommandSyntaxException expected) { }
		}
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		String[] executed = {""};
		var lua = LiteralArgumentBuilder.<Object>literal("lua");
		for (String mode : List.of("run", "testrun")) lua.then(LiteralArgumentBuilder.<Object>literal(mode)
				.then(RequiredArgumentBuilder.<Object, String>argument("name", LuaScriptCommandArgument.script())
						.suggests((context, builder) -> CompletableFuture.supplyAsync(() -> LuaScriptCommandArgument.complete(FILES, builder)))
						.executes(context -> { executed[0] = context.getArgument("name", String.class); return 1; })));
		dispatcher.register(lua);
		for (String mode : List.of("run", "testrun")) {
			String command = "lua " + mode + " ";
			for (String path : List.of("root", "tools/math", "tools/deep/main.lua", "\"tools/deep/main\"", "'tools/deep/main.lua'")) {
				require(dispatcher.execute(command + path, new Object()) == 1, "Command did not execute " + path);
				require(executed[0].equals(path.contains("/") ? path.contains("deep") ? "tools/deep/main" : "tools/math" : "root"), "Wrong canonical path: " + executed[0]);
			}
			Suggestions all = suggest(dispatcher, command);
			require(names(all).equals(List.of("other/helper", "other/main", "root", "tools/deep/main", "tools/math")), "Missing nested scripts or unsafe paths suggested: " + names(all));
			for (var suggestion : all.getList()) {
				require(suggestion.getTooltip() != null && suggestion.getTooltip().getString().endsWith(".lua"), "Missing file tooltip");
				require(dispatcher.execute(suggestion.apply(command), new Object()) == 1, "Suggested path does not execute");
			}
			require(names(suggest(dispatcher, command + "tools/")).equals(List.of("tools/deep/main", "tools/math")), "Slash prefix completion failed");
			require(names(suggest(dispatcher, command + "TOOLS/d")).equals(List.of("tools/deep/main")), "Case-insensitive path completion failed");
			require(names(suggest(dispatcher, command + "main")).equals(List.of("other/main", "tools/deep/main")), "Filename lookup lost subdirectories");
			require(names(suggest(dispatcher, command + "tools/math.l")).equals(List.of("tools/math.lua")), "Extension completion failed");
			for (String quoted : List.of("\"tools/d", "'tools/d")) {
				Suggestions result = suggest(dispatcher, command + quoted);
				require(result.getList().size() == 1, "Unclosed quoted path did not complete");
				require(dispatcher.execute(result.getList().getFirst().apply(command + quoted), new Object()) == 1, "Quoted suggestion was not parseable");
			}
			for (String invalid : List.of("../secret", "/absolute", "tools//math", "tools/../root", "root extra", "\"root extra\"")) {
				boolean rejected = false; try { dispatcher.execute(command + invalid, new Object()); } catch (CommandSyntaxException expected) { rejected = true; }
				require(rejected, "Unsafe/extra argument accepted: " + invalid);
			}
		}
		System.out.println("Lua script command smoke passed: run/testrun, all nested scripts, slashes, prefix/basename, quotes, extensions, safe paths and async suggestions");
	}
	private static Suggestions suggest(CommandDispatcher<Object> dispatcher, String input) { return dispatcher.getCompletionSuggestions(dispatcher.parse(input, new Object())).join(); }
	private static List<String> names(Suggestions suggestions) { return suggestions.getList().stream().map(item -> item.getText()).toList(); }
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
