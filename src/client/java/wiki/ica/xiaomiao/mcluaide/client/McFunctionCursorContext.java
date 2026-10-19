package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Conservative ID-kind inference for Ctrl+I in Minecraft function commands. */
final class McFunctionCursorContext {
	private McFunctionCursorContext() { }

	record Target(LuaInsertCatalog.Kind kind, int start, int end, String query) { }

	static Target at(String source, int cursor) {
		return at(source, cursor, null, null);
	}

	static <S> Target at(String source, int cursor, CommandDispatcher<S> dispatcher, S commandSource) {
		if (source == null || cursor < 0 || cursor > source.length()) return null;
		int lineStart = source.lastIndexOf('\n', cursor - 1) + 1;
		int lineEnd = source.indexOf('\n', cursor);
		if (lineEnd < 0) lineEnd = source.length();
		String prefix = source.substring(lineStart, cursor).stripLeading();
		if (prefix.startsWith("#")) return null;
		int start = cursor;
		while (start > lineStart && !Character.isWhitespace(source.charAt(start - 1))) start--;
		int end = cursor;
		while (end < lineEnd && !Character.isWhitespace(source.charAt(end))) end++;
		String before = source.substring(lineStart, start).strip();
		if (before.isEmpty()) return null;
		LuaInsertCatalog.Kind dynamicKind = dispatcher == null ? null
				: kindFromCommandTree(source.substring(lineStart, start), dispatcher, commandSource);
		if (dynamicKind != null)
			return new Target(dynamicKind, start, end, source.substring(start, end));
		List<String> words = Arrays.asList(before.split("\\s+"));
		int commandStart = 0;
		for (int index = 0; index < words.size(); index++)
			if (words.get(index).equals("run") && !words.isEmpty() && words.getFirst().equals("execute"))
				commandStart = index + 1;
		if (commandStart >= words.size()) return null;
		List<String> command = words.subList(commandStart, words.size());
		LuaInsertCatalog.Kind kind = kind(command);
		return kind == null ? null : new Target(kind, start, end, source.substring(start, end));
	}

	/** Read the server-supplied command tree so modded commands can use the same ID insertion. */
	private static <S> LuaInsertCatalog.Kind kindFromCommandTree(String prefix,
			CommandDispatcher<S> dispatcher, S commandSource) {
		String command = prefix.stripLeading();
		if (command.startsWith("$")) command = command.substring(1);
		if (command.isBlank()) return null;
		try {
			var parsed = dispatcher.parse(command, commandSource);
			var suggestion = parsed.getContext().findSuggestionContext(command.length());
			LuaInsertCatalog.Kind candidate = null;
			for (CommandNode<S> child : suggestion.parent.getChildren()) {
				if (!(child instanceof ArgumentCommandNode<?, ?> argument)) continue;
				LuaInsertCatalog.Kind kind = kindForArgument(argument);
				if (kind == null) continue;
				if (candidate != null && candidate != kind) return null;
				candidate = kind;
			}
			return candidate;
		} catch (RuntimeException ignored) {
			// An incomplete or unavailable command tree still gets the local fallback below.
			return null;
		}
	}

	private static LuaInsertCatalog.Kind kindForArgument(ArgumentCommandNode<?, ?> argument) {
		String type = argument.getType().getClass().getSimpleName().toLowerCase(Locale.ROOT);
		String name = argument.getName().toLowerCase(Locale.ROOT);
		if (type.contains("itemargument") || type.contains("itempredicateargument")) return LuaInsertCatalog.Kind.ITEM;
		if (type.contains("blockstateargument") || type.contains("blockpredicateargument")) return LuaInsertCatalog.Kind.BLOCK;
		if (type.contains("particleargument")) return LuaInsertCatalog.Kind.PARTICLE;
		if (type.contains("loottableargument")) return LuaInsertCatalog.Kind.LOOT_TABLE;
		if (type.contains("entityargument")) return LuaInsertCatalog.Kind.PLAYER;
		if (type.contains("resource") || type.contains("identifier") || type.contains("stringargument")) {
			if (name.contains("enchant")) return LuaInsertCatalog.Kind.ENCHANTMENT;
			if (name.contains("effect")) return LuaInsertCatalog.Kind.EFFECT;
			if (name.contains("sound")) return LuaInsertCatalog.Kind.SOUND;
			if (name.contains("particle")) return LuaInsertCatalog.Kind.PARTICLE;
			if (name.contains("structure") || name.contains("template")) return LuaInsertCatalog.Kind.STRUCTURE;
			if (name.contains("loot_table") || name.contains("loottable")) return LuaInsertCatalog.Kind.LOOT_TABLE;
			if (name.contains("entity_type") || name.contains("entitytype")) return LuaInsertCatalog.Kind.ENTITY;
			if (name.contains("block")) return LuaInsertCatalog.Kind.BLOCK;
			if (name.contains("item")) return LuaInsertCatalog.Kind.ITEM;
		}
		return null;
	}

	private static LuaInsertCatalog.Kind kind(List<String> words) {
		String command = words.getFirst();
		int argument = words.size();
		return switch (command) {
			case "give" -> argument == 1 ? LuaInsertCatalog.Kind.PLAYER
					: argument == 2 ? LuaInsertCatalog.Kind.ITEM : null;
			case "setblock" -> argument == 4 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "fill" -> argument == 7 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "summon" -> argument == 1 ? LuaInsertCatalog.Kind.ENTITY : null;
			case "particle" -> argument == 1 ? LuaInsertCatalog.Kind.PARTICLE : null;
			case "playsound" -> argument == 1 ? LuaInsertCatalog.Kind.SOUND : null;
			case "enchant" -> argument == 1 ? LuaInsertCatalog.Kind.PLAYER
					: argument == 2 ? LuaInsertCatalog.Kind.ENCHANTMENT : null;
			case "effect" -> words.size() >= 2 && words.get(1).equals("give")
					? argument == 2 ? LuaInsertCatalog.Kind.PLAYER
						: argument == 3 ? LuaInsertCatalog.Kind.EFFECT : null : null;
			case "place" -> words.size() >= 2 && words.get(1).equals("structure") && argument == 2
					? LuaInsertCatalog.Kind.STRUCTURE : null;
			case "item" -> words.size() >= 6 && words.get(1).equals("replace")
					&& words.get(5).equals("with") && argument == 6 ? LuaInsertCatalog.Kind.ITEM : null;
			case "loot" -> words.size() >= 2 && words.getLast().equals("loot")
					? LuaInsertCatalog.Kind.LOOT_TABLE : null;
			default -> null;
		};
	}

	static void verify() {
		assertKind("give @p minecraft:diamond", LuaInsertCatalog.Kind.ITEM);
		assertKind("execute as @p run setblock ~ ~ ~ minecraft:stone", LuaInsertCatalog.Kind.BLOCK);
		assertKind("effect give @p minecraft:speed", LuaInsertCatalog.Kind.EFFECT);
		assertKind("summon minecraft:zombie", LuaInsertCatalog.Kind.ENTITY);
		assertKind("loot give @s loot minecraft:chests/simple_dungeon", LuaInsertCatalog.Kind.LOOT_TABLE);
		Target item = at("give @p minecraft:diamond", "give @p minecraft:diamond".length());
		if (item == null || item.start() != "give @p ".length()
				|| !item.query().equals("minecraft:diamond"))
			throw new IllegalStateException("函数 ID 插入替换范围不正确");
		if (at("# give @p minecraft:diamond", 25) != null)
			throw new IllegalStateException("注释不应提供 ID 插入目标");
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		dispatcher.register(LiteralArgumentBuilder.<Object>literal("modcmd")
				.then(RequiredArgumentBuilder.<Object, String>argument("effect", StringArgumentType.word())));
		String dynamic = "modcmd minecraft:speed";
		Target detected = at(dynamic, dynamic.indexOf(':') + 2, dispatcher, new Object());
		if (detected == null || detected.kind() != LuaInsertCatalog.Kind.EFFECT
				|| !detected.query().equals("minecraft:speed"))
			throw new IllegalStateException("函数动态命令参数或光标所在 ID 识别失败");
		Target empty = at("modcmd ", "modcmd ".length(), dispatcher, new Object());
		if (empty == null || empty.kind() != LuaInsertCatalog.Kind.EFFECT || empty.start() != empty.end())
			throw new IllegalStateException("函数空参数位置识别失败");
	}

	private static void assertKind(String command, LuaInsertCatalog.Kind expected) {
		Target result = at(command, command.length());
		if (result == null || result.kind() != expected)
			throw new IllegalStateException("mcfunction ID 参数识别失败：" + command);
	}
}
