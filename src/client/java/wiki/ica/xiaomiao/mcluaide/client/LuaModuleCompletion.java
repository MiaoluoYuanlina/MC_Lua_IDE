package wiki.ica.xiaomiao.mcluaide.client;

import org.luaj.vm2.Lua;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.ast.*;
import org.luaj.vm2.parser.LuaParser;
import org.luaj.vm2.parser.ParseException;
import org.luaj.vm2.parser.TokenMgrError;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.io.StringReader;
import java.util.*;
import java.util.function.Function;

/** Reads exported function declarations only; never executes require or user code. */
final class LuaModuleCompletion {
	private static final int MAX_CACHE_CHARS = 20_000_000, MAX_CHOICES = 1000;
	private final Map<String, Snapshot> cache = new LinkedHashMap<>(16, .75F, true);
	private int cachedChars;

	static String requiredModule(List<LuaCodeAnalyzer.Token> expression) {
		if (expression.isEmpty()) return null;
		int i = 0;
		StringBuilder callee = new StringBuilder(expression.get(i++).text());
		while (i + 1 < expression.size() && expression.get(i).text().equals(".")) {
			callee.append('.').append(expression.get(i + 1).text()); i += 2;
		}
		if (!Set.of("require", "MC.Script.require", "MC.script.require", "mc.Script.require", "mc.script.require").contains(callee.toString())) return null;
		boolean parentheses = i < expression.size() && expression.get(i).text().equals("(");
		if (parentheses) i++;
		if (i >= expression.size() || expression.get(i).type() != LuaCodeAnalyzer.TokenType.STRING) return null;
		String requested = LuaCodeAnalyzer.stringValue(expression.get(i++).text());
		if (parentheses && (i >= expression.size() || !expression.get(i++).text().equals(")"))) return null;
		if (i != expression.size() || requested == null) return null;
		try { return LuaScriptNames.moduleName(requested); }
		catch (IllegalArgumentException invalid) { return null; }
	}

	List<LuaArgumentCompletion.Choice> choices(String variable, String module, String prefix, Function<String, String> sources) {
		List<LuaArgumentCompletion.Choice> result = new ArrayList<>();
		Shape root = load(module, sources);
		if (root == null) return result;
		Set<String> visiting = new HashSet<>(Set.of(module));
		flatten(root, variable, module, prefix, sources, visiting, 0, result);
		return List.copyOf(result);
	}

	private Shape load(String module, Function<String, String> sources) {
		String source = sources.apply(module);
		if (source == null || source.length() > LuaProtocol.MAX_SCRIPT_CHARS) return null;
		Snapshot old = cache.get(module);
		if (old != null && old.source.equals(source)) return old.root;
		Shape root = parse(source);
		if (old != null) { cache.remove(module); cachedChars -= old.source.length(); }
		while (!cache.isEmpty() && (cache.size() >= 64 || cachedChars + source.length() > MAX_CACHE_CHARS)) {
			String first = cache.keySet().iterator().next(); cachedChars -= cache.remove(first).source.length();
		}
		cache.put(module, new Snapshot(source, root)); cachedChars += source.length();
		return root;
	}

	private void flatten(Shape shape, String symbol, String module, String prefix, Function<String, String> sources,
			Set<String> visiting, int depth, List<LuaArgumentCompletion.Choice> out) {
		if (depth > 8 || out.size() >= MAX_CHOICES) return;
		if (shape.reference != null) {
			if (!visiting.add(shape.reference)) return;
			Shape root = load(shape.reference, sources);
			if (root != null) flatten(root, symbol, shape.reference, prefix, sources, visiting, depth + 1, out);
			visiting.remove(shape.reference); return;
		}
		if (shape.function != null) {
			String comparable = symbol.replace(':', '.');
			String query = prefix.replace(':', '.');
			if (comparable.regionMatches(true, 0, query, 0, query.length()) && !symbol.equals(prefix)) {
				FunctionInfo info = shape.function;
				String signature = symbol + "(" + String.join(", ", info.parameters) + ")";
				String label = signature + " - " + (info.description.isBlank() ? "模块函数" : info.description);
				String tooltip = signature + "\n来源：" + module + ".lua · 第 " + info.line + " 行"
						+ (info.description.isBlank() ? "" : "\n作用：" + info.description)
						+ (info.introduction.isBlank() ? "" : "\n介绍：" + info.introduction);
				for (var note : info.notes.entrySet()) tooltip += "\n参数 " + note.getKey() + "：" + note.getValue();
				out.add(new LuaArgumentCompletion.Choice(symbol, label, tooltip, 1, true));
			}
		}
		for (var entry : shape.members.entrySet()) {
			if (out.size() >= MAX_CHOICES) break;
			String separator = entry.getValue().method ? ":" : ".";
			if (prefix.startsWith(symbol + ":")) separator = ":";
			flatten(entry.getValue(), symbol + separator + entry.getKey(), module, prefix, sources, visiting, depth + 1, out);
		}
	}

	private static Shape parse(String source) {
		try {
			Chunk chunk = new LuaParser(new StringReader(source)).Chunk();
			Exports exports = new Exports(LuaFunctionModel.parse(source));
			exports.block(chunk.block, new LinkedHashMap<>());
			Shape root = new Shape();
			for (Shape returned : exports.returned) {
				root.members.putAll(returned.members);
				if (returned.function != null) root.function = returned.function;
				if (returned.reference != null) root.reference = returned.reference;
			}
			return root;
		} catch (ParseException | TokenMgrError | StackOverflowError unsupported) { return new Shape(); }
	}

	private static final class Exports {
		final LuaFunctionModel model;
		final List<Shape> returned = new ArrayList<>();
		Exports(LuaFunctionModel model) { this.model = model; }
		void block(Block block, Map<String, Shape> env) {
			if (block.stats == null) return;
			for (Object raw : block.stats) {
				Stat stat = (Stat) raw;
				if (stat instanceof Stat.LocalAssign local) {
					List<Shape> values = values(local.values, env);
					for (int i = 0; i < local.names.size(); i++) env.put(((Name) local.names.get(i)).name,
							i < values.size() ? values.get(i) : new Shape());
				} else if (stat instanceof Stat.Assign assign) {
					List<Shape> values = values(assign.exps, env);
					for (int i = 0; i < assign.vars.size(); i++) write((Exp) assign.vars.get(i),
							i < values.size() ? values.get(i) : new Shape(), env);
				} else if (stat instanceof Stat.LocalFuncDef func) env.put(func.name.name, function(func.body, func.name.name, false));
				else if (stat instanceof Stat.FuncDef func) {
					List<String> path = new ArrayList<>(List.of(func.name.name.name));
					if (func.name.dots != null) for (Object part : func.name.dots) path.add(part.toString());
					if (func.name.method != null) path.add(func.name.method);
					Shape value = function(func.body, String.join(".", path), func.name.method != null);
					if (path.size() == 1) env.put(path.getFirst(), value);
					else {
						Shape owner = env.get(path.getFirst());
						for (int i = 1; owner != null && i + 1 < path.size(); i++) owner = owner.members.get(path.get(i));
						if (owner != null) owner.members.put(path.getLast(), value);
					}
				} else if (stat instanceof Stat.Return ret) {
					if (ret.values != null && !ret.values.isEmpty()) returned.add(value((Exp) ret.values.getFirst(), env));
					return;
				} else if (stat instanceof Stat.IfThenElse branch) {
					block(branch.ifblock, new LinkedHashMap<>(env));
					if (branch.elseifblocks != null) for (Object part : branch.elseifblocks) block((Block) part, new LinkedHashMap<>(env));
					if (branch.elseblock != null) block(branch.elseblock, new LinkedHashMap<>(env));
				} else if (stat instanceof Block nested) block(nested, new LinkedHashMap<>(env));
			}
		}
		List<Shape> values(List<?> expressions, Map<String, Shape> env) {
			List<Shape> values = new ArrayList<>();
			if (expressions != null) for (Object exp : expressions) values.add(value((Exp) exp, env));
			return values;
		}
		Shape value(Exp exp, Map<String, Shape> env) {
			if (exp instanceof Exp.NameExp name) return env.getOrDefault(name.name.name, new Shape());
			if (exp instanceof Exp.ParensExp parens) return value(parens.exp, env);
			if (exp instanceof Exp.AnonFuncDef func) return function(func.body, "", false);
			if (exp instanceof Exp.FieldExp field) return value(field.lhs, env).members.getOrDefault(field.name.name, new Shape());
			if (exp instanceof Exp.IndexExp index) return value(index.lhs, env).members.getOrDefault(key(index.exp), new Shape());
			if (exp instanceof TableConstructor table) {
				Shape shape = new Shape();
				if (table.fields != null) for (Object raw : table.fields) {
					TableField field = (TableField) raw;
					String name = field.name == null ? key(field.index) : field.name;
					if (identifier(name)) shape.members.put(name, value(field.rhs, env));
				}
				return shape;
			}
			if (exp instanceof Exp.BinopExp op && op.op == Lua.OP_OR) {
				Shape a = value(op.lhs, env), b = value(op.rhs, env);
				if (a.function != null || a.reference != null || !a.members.isEmpty()) return a;
				return b;
			}
			if (exp instanceof Exp.FuncCall call && call.args.exps != null && call.args.exps.size() == 1) {
				String symbol = symbol(call.lhs);
				Exp argument = (Exp) call.args.exps.getFirst();
				if (Set.of("require", "MC.Script.require", "MC.script.require", "mc.Script.require", "mc.script.require").contains(symbol)
						&& argument instanceof Exp.Constant literal && literal.value.type() == LuaValue.TSTRING) {
					try {
						Shape shape = new Shape(); shape.reference = LuaScriptNames.moduleName(literal.value.tojstring()); return shape;
					} catch (IllegalArgumentException | IndexOutOfBoundsException invalid) { return new Shape(); }
				}
			}
			return new Shape();
		}
		void write(Exp target, Shape value, Map<String, Shape> env) {
			if (target instanceof Exp.NameExp name) env.put(name.name.name, value);
			else if (target instanceof Exp.FieldExp field) value(field.lhs, env).members.put(field.name.name, value);
			else if (target instanceof Exp.IndexExp index) {
				String key = key(index.exp);
				if (identifier(key)) value(index.lhs, env).members.put(key, value);
			}
		}
		Shape function(FuncBody body, String symbol, boolean method) {
			List<String> params = new ArrayList<>();
			if (body.parlist.names != null) for (Object name : body.parlist.names) params.add(((Name) name).name);
			if (body.parlist.isvararg) params.add("...");
			LuaFunctionModel.Function metadata = model.functions.stream()
					.filter(f -> !symbol.isBlank() && f.symbol().replace(':', '.').equals(symbol.replace(':', '.'))
							&& f.line() == body.beginLine).findFirst().orElse(null);
			if (metadata == null) metadata = model.functions.stream().filter(f -> f.line() == body.beginLine
					&& f.parameters().equals(params)).findFirst().orElse(null);
			Shape shape = new Shape(); shape.method = method;
			shape.function = new FunctionInfo(List.copyOf(params), metadata == null ? "" : metadata.displayName(),
					metadata == null ? "" : metadata.introduction(), metadata == null ? Map.of() : metadata.notes(),
					metadata == null ? Math.max(1, body.beginLine) : metadata.line());
			return shape;
		}
		static String symbol(Exp exp) {
			if (exp instanceof Exp.NameExp name) return name.name.name;
			if (exp instanceof Exp.FieldExp field) return symbol(field.lhs) + "." + field.name.name;
			return "";
		}
		static String key(Exp exp) {
			if (!(exp instanceof Exp.Constant constant) || constant.value.type() != LuaValue.TSTRING) return "";
			try { return constant.value.tojstring(); }
			catch (IndexOutOfBoundsException invalid) { return ""; }
		}
		static boolean identifier(String name) { return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*"); }
	}
	private static final class Shape {
		final Map<String, Shape> members = new LinkedHashMap<>();
		FunctionInfo function;
		boolean method;
		String reference;
	}
	private record FunctionInfo(List<String> parameters, String description, String introduction, Map<String, String> notes, int line) { }
	private record Snapshot(String source, Shape root) { }
}
