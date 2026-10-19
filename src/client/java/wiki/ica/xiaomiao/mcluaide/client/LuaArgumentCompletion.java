package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Pure argument suggestions; registry fetching and stale-result guards belong to the screen. */
final class LuaArgumentCompletion {
	private LuaArgumentCompletion() { }

	static LuaInsertCatalog.Kind kind(LuaCursorContext.Parameter parameter) {
		LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(parameter.symbol());
		if (entry != null && parameter.index() < entry.parameterTypes().size()
				&& entry.parameterTypes().get(parameter.index()) == McLuaIdeApi.ValueType.ENTITY_UUID) return null;
		return LuaApiDocumentation.insertionKind(parameter.symbol(), parameter.index());
	}

	static List<Choice> suggest(String source, int cursor, LuaCursorContext.Parameter parameter,
			List<LuaInsertCatalog.Entry> catalog) {
		LuaCodeAnalyzer.ExpectedType expected = LuaCodeAnalyzer.parameterType(parameter);
		LuaInsertCatalog.Kind kind = kind(parameter);
		String query = parameter.query().toLowerCase(Locale.ROOT);
		List<Choice> result = new ArrayList<>();
		String context = parameter.symbol() + " · 参数" + (parameter.index() + 1) + "：" + parameter.name()
				+ " · " + (kind == null ? expected.chinese() : kind.label());
		if (!parameter.quoted()) {
			Map<String, LuaCodeAnalyzer.StaticValue> variables = LuaCodeAnalyzer.visibleVariableValues(source, cursor);
			for (var variable : variables.entrySet()) {
				String name = variable.getKey();
				LuaCodeAnalyzer.StaticValue value = variable.getValue();
				boolean optionalNil = parameter.name().endsWith("?") && value.type() == LuaCodeAnalyzer.ValueType.NIL;
				if (!startsWith(name, query) || name.equals(parameter.query())
						|| !optionalNil && !LuaCodeAnalyzer.accepts(expected, value) || !compatibleLiteral(parameter, value, kind, catalog)) continue;
				boolean unknown = value.type() == LuaCodeAnalyzer.ValueType.UNKNOWN;
				String type = unknown ? "类型待确认" : value.type().chinese();
				result.add(new Choice(name, name + " - 变量 · " + type,
						context + "\n变量：" + name + "\n类型：" + type
								+ (value.literal() == null ? "" : "\n值：" + value.literal()), unknown ? 3 : 0));
			}
			if (expected == LuaCodeAnalyzer.ExpectedType.BOOLEAN || expected == LuaCodeAnalyzer.ExpectedType.ANY) {
				for (String literal : List.of("true", "false")) if (startsWith(literal, query) && !literal.equals(query))
					result.add(new Choice(literal, literal + " - 布尔值", context, 1));
			}
			if (parameter.name().endsWith("?") && startsWith("nil", query) && !query.equals("nil"))
				result.add(new Choice("nil", "nil - 省略可选参数", context, 2));
		}
		if (kind != null) for (LuaInsertCatalog.Entry entry : catalog) {
			if (!entry.matches(query) || entry.value().equals(parameter.query())) continue;
			String insertion = stringInsertion(source, parameter, entry.value());
			boolean direct = startsWith(entry.value(), query) || startsWith(path(entry.value()), query);
			result.add(new Choice(insertion, entry.value() + " - " + kind.tabLabel()
					+ (entry.name().isBlank() || entry.name().equals(entry.value()) ? "" : " · " + entry.name()),
					context + "\n" + entry.label(), direct ? 1 : 2));
		}
		for (String literal : LuaCodeAnalyzer.parameterValues(parameter)) {
			if (!startsWith(literal, query) || literal.equals(parameter.query())) continue;
			result.add(new Choice(stringInsertion(source, parameter, literal),
					literal + " - 参数可选值", context, 1));
		}
		// A nested API call is still an expression: retain function-name completion inside arguments.
		if (!parameter.quoted() && query.contains(".")) {
			for (String symbol : LuaApiDocumentation.symbols()) {
				if (!startsWith(symbol, query) || symbol.equals(parameter.query())) continue;
				LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(symbol);
				result.add(new Choice(symbol, LuaApiDocumentation.completionLabel(symbol),
						context + "\n" + entry.signature() + "\n" + LuaUiText.text("返回：") + entry.returns(), 2, true));
			}
		}
		return result.stream().sorted(Comparator.comparingInt(Choice::rank)
				.thenComparingInt(choice -> choice.insertion().length()).thenComparing(Choice::insertion))
				.distinct().toList();
	}

	private static boolean compatibleLiteral(LuaCursorContext.Parameter parameter, LuaCodeAnalyzer.StaticValue value,
			LuaInsertCatalog.Kind kind, List<LuaInsertCatalog.Entry> catalog) {
		if (value.literal() == null || value.type() != LuaCodeAnalyzer.ValueType.STRING) return true;
		LuaApiDocumentation.Entry documentation = LuaApiDocumentation.find(parameter.symbol());
		boolean entityUuid = documentation != null && parameter.index() < documentation.parameterTypes().size()
				&& documentation.parameterTypes().get(parameter.index()) == McLuaIdeApi.ValueType.ENTITY_UUID;
		if (kind == LuaInsertCatalog.Kind.PLAYER || entityUuid || parameter.name().toLowerCase(Locale.ROOT).contains("uuid")) {
			try { UUID.fromString(value.literal()); return true; }
			catch (IllegalArgumentException ignored) { return false; }
		}
		if (kind == null || catalog.isEmpty()) return true;
		String literal = value.literal().contains(":") ? value.literal() : "minecraft:" + value.literal();
		return catalog.stream().anyMatch(entry -> entry.value().equals(literal));
	}

	private static boolean startsWith(String text, String query) {
		return text.regionMatches(true, 0, query, 0, query.length());
	}

	private static String path(String id) { return id.substring(id.indexOf(':') + 1); }

	private static String stringInsertion(String source, LuaCursorContext.Parameter parameter, String value) {
		if (!parameter.quoted()) return '"' + value + '"';
		char quote = source.charAt(parameter.start() - 1);
		return parameter.end() < source.length() && source.charAt(parameter.end()) == quote ? value : value + quote;
	}

	record Choice(String insertion, String label, String tooltip, int rank, boolean functionCall) {
		Choice(String insertion, String label, String tooltip, int rank) { this(insertion, label, tooltip, rank, false); }
	}
}
