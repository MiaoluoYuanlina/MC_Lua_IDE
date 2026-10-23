package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;


/** The command reference lists every root command with usage lines, and resolves aliases to the real command's formats. */
public final class CommandReferenceSmoke {
	public static void main(String[] args) {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		var target = dispatcher.register(CommandReferenceSmoke.<CommandSourceStack>literal("demo")
				.then(CommandReferenceSmoke.<CommandSourceStack>literal("add").then(CommandReferenceSmoke.<CommandSourceStack, Integer>argument("amount", IntegerArgumentType.integer()).executes(context -> 1)))
				.then(CommandReferenceSmoke.<CommandSourceStack>literal("reset").executes(context -> 1)));
		dispatcher.register(CommandReferenceSmoke.<CommandSourceStack>literal("d").redirect(target));
		dispatcher.register(CommandReferenceSmoke.<CommandSourceStack>literal("ping").executes(context -> 1));
		JsonArray categories = JsonParser.parseString(LuaCommandReference.build(dispatcher, null)).getAsJsonArray();
		int checks = 0;
		StringBuilder everything = new StringBuilder();
		for (var category : categories) for (var command : category.getAsJsonObject().getAsJsonArray("commands")) {
			everything.append(command.getAsJsonObject().get("name").getAsString()).append(':').append(command.getAsJsonObject().getAsJsonArray("usages")).append('\n');
		}
		String text = everything.toString();
		require(text.contains("demo add <amount>"), "usage of a subcommand with an argument: " + text); checks++;
		require(text.contains("demo reset"), "usage of a subcommand: " + text); checks++;
		require(text.contains("d add <amount>"), "alias resolved to the real command's formats: " + text); checks++;
		require(text.contains("ping:[\"ping\"]"), "executable root lists its own name: " + text); checks++;
		require(!categories.isEmpty() && categories.get(0).getAsJsonObject().has("name"), "categories have names"); checks++;
		System.out.println("Command reference smoke passed: " + checks + " checks");
	}

	private static <S> com.mojang.brigadier.builder.LiteralArgumentBuilder<S> literal(String name) { return com.mojang.brigadier.builder.LiteralArgumentBuilder.literal(name); }
	private static <S, T> com.mojang.brigadier.builder.RequiredArgumentBuilder<S, T> argument(String name, com.mojang.brigadier.arguments.ArgumentType<T> type) { return com.mojang.brigadier.builder.RequiredArgumentBuilder.argument(name, type); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
