package wiki.ica.xiaomiao.mcluaide.network;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** A relative Lua path is a single token, but unlike Brigadier's string/word may contain '/'. */
public final class LuaScriptCommandArgument implements ArgumentType<String> {
	private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(new LiteralMessage(
			"脚本路径应为 名称 或 子目录/名称，可带 .lua；不能使用绝对路径、空格或 .."));
	private LuaScriptCommandArgument() { }
	public static LuaScriptCommandArgument script() { return new LuaScriptCommandArgument(); }
	@Override public String parse(StringReader reader) throws CommandSyntaxException {
		int start = reader.getCursor();
		String value;
		if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) value = reader.readString();
		else {
			while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
			value = reader.getString().substring(start, reader.getCursor());
		}
		if (value.endsWith(".lua")) value = value.substring(0, value.length() - 4);
		if (!LuaScriptNames.isValid(value)) { reader.setCursor(start); throw INVALID.createWithContext(reader); }
		return value;
	}
	@Override public Collection<String> getExamples() { return List.of("example", "tools/main", "tools/main.lua", "\"tools/main\""); }

	/** Builds replacements for the whole relative path; no directory/file text is dropped after '/'. */
	public static Suggestions complete(List<String> scripts, SuggestionsBuilder builder) {
		String remaining = builder.getRemaining();
		boolean quoted = !remaining.isEmpty() && StringReader.isQuotedStringStart(remaining.charAt(0));
		char quote = quoted ? remaining.charAt(0) : '"';
		String prefix = quoted ? remaining.substring(1) : remaining;
		if (quoted && !prefix.isEmpty() && prefix.charAt(prefix.length() - 1) == quote) prefix = prefix.substring(0, prefix.length() - 1);
		boolean withExtension = prefix.indexOf('.') >= 0;
		String query = prefix.toLowerCase(Locale.ROOT);
		boolean basename = query.indexOf('/') < 0;
		for (String name : scripts.stream().filter(LuaScriptNames::isValid).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
			String candidate = name + (withExtension ? ".lua" : ""), lower = candidate.toLowerCase(Locale.ROOT);
			if (!lower.startsWith(query) && !(basename && lower.substring(lower.lastIndexOf('/') + 1).startsWith(query))) continue;
			builder.suggest(quoted ? quote + candidate + quote : candidate, new LiteralMessage("Lua 脚本：" + name + ".lua"));
		}
		return builder.build();
	}
}
