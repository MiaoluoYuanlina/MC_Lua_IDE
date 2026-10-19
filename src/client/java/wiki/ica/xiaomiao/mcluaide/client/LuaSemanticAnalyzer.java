package wiki.ica.xiaomiao.mcluaide.client;

import org.luaj.vm2.Lua;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.ast.*;
import org.luaj.vm2.parser.LuaParser;
import org.luaj.vm2.parser.ParseException;
import org.luaj.vm2.parser.TokenMgrError;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.io.StringReader;
import java.util.*;

import static wiki.ica.xiaomiao.mcluaide.client.LuaCodeAnalyzer.*;

/** Static only: never executes user code, loads modules, or queries a game world. */
final class LuaSemanticAnalyzer {
	private static final int LIMIT = 100;
	private static final Fact UNKNOWN = new Fact(ValueType.UNKNOWN, null, false, null);
	private static final Fact NIL = new Fact(ValueType.NIL, null, true, null);
	private static final Fact FUNCTION = fact(ValueType.FUNCTION);
	private final String source;
	private final List<Token> tokens;
	private final int[] lines;
	private final Map<SyntaxElement, Span> spans = new IdentityHashMap<>();
	private final Map<String, Binding> globals = new LinkedHashMap<>();
	private final Map<Exp.NameExp, Binding> references = new IdentityHashMap<>();
	private final Map<Name, Binding> declarations = new IdentityHashMap<>();
	private final Map<Stat.FuncDef, Binding> functionTargets = new IdentityHashMap<>();
	private final Map<FuncBody, List<Binding>> parameters = new IdentityHashMap<>();
	private final Map<Binding, FuncBody> definitions = new IdentityHashMap<>();
	private final Map<FuncBody, Set<Binding>> functionWrites = new IdentityHashMap<>();
	private final Map<FuncBody, List<Exp.FuncCall>> functionCalls = new IdentityHashMap<>();
	private final List<FuncBody> functions = new ArrayList<>();
	private final Map<Binding, Fact> summaries = new IdentityHashMap<>();
	private final Set<Binding> summarizing = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<Diagnostic> problems = new ArrayList<>();
	private final Set<String> problemKeys = new HashSet<>();
	private int errors, warnings;

	static List<Diagnostic> analyze(String source) {
		try {
			// AST columns are 16-bit; do not let an exceptionally long physical
			// line wrap its diagnostic offsets. The token-based fallback has no such limit.
			int column = 0;
			for (int i = 0; i < source.length(); i++) {
				char c = source.charAt(i);
				if (c == '\r' || c == '\n') column = 0;
				else if (++column > 65_535) return null;
			}
			Chunk chunk = new LuaParser(new StringReader(source)).Chunk();
			LuaSemanticAnalyzer analyzer = new LuaSemanticAnalyzer(source);
			analyzer.bindBlock(chunk.block, new Scope(null, null));
			analyzer.block(chunk.block, new IdentityHashMap<>(), null);
			// A function definition does not run its body. Analyze each body separately,
			// using conservative summaries only for captured variables and globals.
			for (FuncBody body : analyzer.functions) {
				Map<Binding, Fact> environment = new IdentityHashMap<>();
				for (Binding parameter : analyzer.parameters.get(body)) environment.put(parameter, UNKNOWN);
				analyzer.block(body.block, environment, body);
			}
			return analyzer.problems.stream().sorted(Comparator
					.comparing(Diagnostic::severity).thenComparingInt(Diagnostic::start))
					.limit(LIMIT).toList();
		} catch (ParseException | TokenMgrError | StackOverflowError unsupported) {
			// The runtime compiler remains authoritative for syntax. In particular,
			// its accepted grammar can differ from LuaJ's optional AST parser.
			return null;
		}
	}

	private LuaSemanticAnalyzer(String source) {
		this.source = source;
		tokens = lex(source);
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\r') {
				if (i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
				starts.add(i + 1);
			} else if (source.charAt(i) == '\n') starts.add(i + 1);
		}
		lines = starts.stream().mapToInt(Integer::intValue).toArray();
	}

	// Bind names before type analysis, retaining the actual declaration identity.
	// Lua's AST NameResolver puts repeat locals out of scope too early and makes
	// numeric-for locals visible in their bounds, so we deliberately bind ourselves.
	private void bindBlock(Block block, Scope scope) {
		if (block.stats != null) for (Object raw : block.stats) bind((Stat) raw, scope);
	}

	private void bind(Stat stat, Scope scope) {
		if (stat instanceof Stat.LocalAssign local) {
			bindExps(local.values, scope);
			List<Span> names = declarationSpans(local, local.names);
			for (int i = 0; i < local.names.size(); i++) {
				Name name = (Name) local.names.get(i);
				Binding binding = declare(name, names.get(i), scope);
				binding.writes.add(new Write(valueAt(local.values, i), missingIsDynamic(local.values, i)));
				if (valueAt(local.values, i) instanceof Exp.AnonFuncDef definition) definitions.put(binding, definition.body);
			}
		} else if (stat instanceof Stat.Assign assign) {
			bindExps(assign.exps, scope);
			for (int i = 0; i < assign.vars.size(); i++) {
				Exp target = (Exp) assign.vars.get(i);
				bind(target, scope);
				if (target instanceof Exp.NameExp name) {
					Binding binding = references.get(name);
					binding.writes.add(new Write(valueAt(assign.exps, i), missingIsDynamic(assign.exps, i)));
					if (valueAt(assign.exps, i) instanceof Exp.AnonFuncDef definition) definitions.put(binding, definition.body);
					else definitions.remove(binding);
					trackWrite(binding, scope);
				}
			}
		} else if (stat instanceof Stat.LocalFuncDef local) {
			Binding binding = declare(local.name, declarationSpans(local, List.of(local.name)).getFirst(), scope);
			binding.writes.add(new Write(null, true, FUNCTION));
			definitions.put(binding, local.body);
			bindFunction(local.body, scope, false);
		} else if (stat instanceof Stat.FuncDef func) {
			Binding binding = resolve(func.name.name.name, scope);
			functionTargets.put(func, binding);
			if ((func.name.dots == null || func.name.dots.isEmpty()) && func.name.method == null) {
				binding.writes.add(new Write(null, true, FUNCTION));
				definitions.put(binding, func.body); trackWrite(binding, scope);
			}
			bindFunction(func.body, scope, func.name.method != null);
		} else if (stat instanceof Stat.FuncCallStat call) bind(call.funccall, scope);
		else if (stat instanceof Stat.Return ret) bindExps(ret.values, scope);
		else if (stat instanceof Stat.IfThenElse branch) {
			bind(branch.ifexp, scope);
			bindBlock(branch.ifblock, new Scope(scope, scope.function));
			if (branch.elseifexps != null) for (int i = 0; i < branch.elseifexps.size(); i++) {
				bind((Exp) branch.elseifexps.get(i), scope);
				bindBlock((Block) branch.elseifblocks.get(i), new Scope(scope, scope.function));
			}
			if (branch.elseblock != null) bindBlock(branch.elseblock, new Scope(scope, scope.function));
		} else if (stat instanceof Stat.NumericFor loop) {
			bind(loop.initial, scope); bind(loop.limit, scope); bind(loop.step, scope);
			Scope inner = new Scope(scope, scope.function);
			declare(loop.name, declarationSpans(loop, List.of(loop.name)).getFirst(), inner)
					.writes.add(new Write(null, true, fact(ValueType.NUMBER)));
			bindBlock(loop.block, inner);
		} else if (stat instanceof Stat.GenericFor loop) {
			bindExps(loop.exps, scope);
			Scope inner = new Scope(scope, scope.function);
			List<Span> names = declarationSpans(loop, loop.names);
			for (int i = 0; i < loop.names.size(); i++) declare((Name) loop.names.get(i), names.get(i), inner)
					.writes.add(new Write(null, true));
			bindBlock(loop.block, inner);
		} else if (stat instanceof Stat.RepeatUntil loop) {
			Scope inner = new Scope(scope, scope.function);
			bindBlock(loop.block, inner); bind(loop.exp, inner);
		} else if (stat instanceof Stat.WhileDo loop) {
			bind(loop.exp, scope); bindBlock(loop.block, new Scope(scope, scope.function));
		} else if (stat instanceof Block block) bindBlock(block, new Scope(scope, scope.function));
	}

	private void bind(Exp exp, Scope scope) {
		if (exp == null) return;
		if (exp instanceof Exp.NameExp name) references.put(name, resolve(name.name.name, scope));
		else if (exp instanceof Exp.AnonFuncDef func) bindFunction(func.body, scope, false);
		else if (exp instanceof Exp.FieldExp field) bind(field.lhs, scope);
		else if (exp instanceof Exp.IndexExp index) { bind(index.lhs, scope); bind(index.exp, scope); }
		else if (exp instanceof Exp.FuncCall call) {
			bind(call.lhs, scope); bindExps(call.args.exps, scope);
			// call "text" is legal Lua. FuncArgs creates its constant without L().
			if (call.args.exps != null && call.args.exps.size() == 1
					&& call.args.exps.getFirst() instanceof Exp.Constant constant && constant.endLine == 0) {
				int last = tokenAt(span(call.args).end) - 1;
				if (last >= 0) spans.put(constant, new Span(tokens.get(last).start(), tokens.get(last).end()));
			}
			if (scope.function != null) functionCalls.computeIfAbsent(scope.function, ignored -> new ArrayList<>()).add(call);
		}
		else if (exp instanceof Exp.BinopExp op) { bind(op.lhs, scope); bind(op.rhs, scope); }
		else if (exp instanceof Exp.UnopExp op) bind(op.rhs, scope);
		else if (exp instanceof Exp.ParensExp parens) bind(parens.exp, scope);
		else if (exp instanceof TableConstructor table && table.fields != null) for (Object raw : table.fields) {
			TableField field = (TableField) raw;
			bind(field.index, scope); bind(field.rhs, scope);
		}
	}

	private void bindExps(List<?> exps, Scope scope) {
		if (exps != null) for (Object raw : exps) bind((Exp) raw, scope);
	}

	private void bindFunction(FuncBody body, Scope outer, boolean method) {
		functions.add(body);
		Scope scope = new Scope(outer, body);
		List<Binding> params = new ArrayList<>();
		if (method) {
			Binding self = new Binding("self", true, body, span(body));
			scope.locals.put("self", self); params.add(self);
		}
		if (body.parlist.names != null) {
			List<Span> names = declarationSpans(body, body.parlist.names);
			for (int i = 0; i < body.parlist.names.size(); i++) {
				Name name = (Name) body.parlist.names.get(i);
				Binding parameter = declare(name, names.get(i), scope, true);
				parameter.writes.add(new Write(null, true)); params.add(parameter);
			}
		}
		parameters.put(body, params);
		bindBlock(body.block, scope);
	}

	private Binding resolve(String name, Scope scope) {
		for (Scope current = scope; current != null; current = current.outer) {
			Binding binding = current.locals.get(name);
			if (binding != null) return binding;
		}
		return globals.computeIfAbsent(name, key -> new Binding(key, false, null, null));
	}

	private void trackWrite(Binding binding, Scope scope) {
		if (scope.function != null && (!binding.local || binding.function != scope.function))
			functionWrites.computeIfAbsent(scope.function, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(binding);
	}

	private void invalidateCallWrites(Exp.FuncCall call, Map<Binding, Fact> env) {
		FuncBody target = calledBody(call.lhs);
		if (target == null) return;
		Set<FuncBody> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<FuncBody> pending = new ArrayDeque<>(); pending.add(target);
		while (!pending.isEmpty()) {
			FuncBody body = pending.removeFirst();
			if (!visited.add(body)) continue;
			for (Binding binding : functionWrites.getOrDefault(body, Set.of())) env.put(binding, UNKNOWN);
			for (Exp.FuncCall nested : functionCalls.getOrDefault(body, List.of())) {
				FuncBody next = calledBody(nested.lhs);
				if (next != null) pending.add(next);
			}
		}
	}

	private FuncBody calledBody(Exp exp) {
		if (exp instanceof Exp.ParensExp parens) return calledBody(parens.exp);
		if (exp instanceof Exp.AnonFuncDef func) return func.body;
		return exp instanceof Exp.NameExp name ? definitions.get(references.get(name)) : null;
	}

	private Binding declare(Name name, Span span, Scope scope) {
		return declare(name, span, scope, false);
	}

	private Binding declare(Name name, Span span, Scope scope, boolean parameter) {
		Binding outer = null;
		for (Scope current = scope; current != null && outer == null; current = current.outer)
			outer = current.locals.get(name.name);
		if (outer == null) outer = globals.get(name.name);
		if (outer != null && (outer.local || !outer.writes.isEmpty())) {
			warning((parameter ? "参数“" : "局部变量“") + name.name + "”遮蔽同名变量"
					+ (outer.declaration == null ? "" : "（第 " + lineAt(outer.declaration.start) + " 行）")
					+ (parameter ? "；函数内使用的是此参数，而不是外层变量。请检查是否重复填写了同名参数"
						: "；这里赋值不会修改外层变量。若要更新外层变量，请去掉 local"), span);
		} else if (PREDEFINED_GLOBALS.contains(name.name) || LuaApiDocumentation.isApiRoot(name.name))
			warning("局部变量“" + name.name + "”遮蔽内置名称，原有函数或 API 将无法通过此名称访问", span);
		Binding binding = new Binding(name.name, true, scope.function, span);
		scope.locals.put(name.name, binding); declarations.put(name, binding);
		return binding;
	}

	/** Returns whether execution can continue beyond this block. */
	private boolean block(Block block, Map<Binding, Fact> env, FuncBody function) {
		boolean continuing = true;
		if (block.stats != null) for (Object raw : block.stats) {
			Stat stat = (Stat) raw;
			// Keep checking unreachable statements, but do not let them affect live flow.
			if (!continuing) { statement(stat, new IdentityHashMap<>(env), function); continue; }
			continuing = statement(stat, env, function);
		}
		return continuing;
	}

	private boolean statement(Stat stat, Map<Binding, Fact> env, FuncBody function) {
		if (stat instanceof Stat.LocalAssign local) {
			List<Fact> values = values(local.values, env, function);
			for (int i = 0; i < local.names.size(); i++) env.put(declarations.get((Name) local.names.get(i)),
					assigned(values, local.values, i));
		} else if (stat instanceof Stat.Assign assign) {
			List<Fact> values = values(assign.exps, env, function);
			// Assignment expressions are all evaluated before any target is written.
			for (Object raw : assign.vars) if (!(raw instanceof Exp.NameExp)) evaluate((Exp) raw, env, function, true);
			for (int i = 0; i < assign.vars.size(); i++) if (assign.vars.get(i) instanceof Exp.NameExp name)
				env.put(references.get(name), assigned(values, assign.exps, i));
		} else if (stat instanceof Stat.LocalFuncDef local) env.put(declarations.get(local.name), FUNCTION);
		else if (stat instanceof Stat.FuncDef func) {
			Binding target = functionTargets.get(func);
			if ((func.name.dots == null || func.name.dots.isEmpty()) && func.name.method == null) env.put(target, FUNCTION);
			else {
				Fact receiver = read(target, env, function, span(func.name), true);
				checkIndex(receiver, span(func.name));
			}
		} else if (stat instanceof Stat.FuncCallStat call) {
			evaluate(call.funccall, env, function, true);
			// assert(x) is a runtime non-nil guard for subsequent statements.
			if (call.funccall.lhs instanceof Exp.NameExp name && name.name.name.equals("assert")
					&& !references.get(name).local && call.funccall.args.exps != null && !call.funccall.args.exps.isEmpty())
				refine((Exp) call.funccall.args.exps.getFirst(), true, env, function);
		} else if (stat instanceof Stat.Return ret) { values(ret.values, env, function); return false; }
		else if (stat instanceof Stat.IfThenElse branch) branch(branch, env, function);
		else if (stat instanceof Stat.WhileDo loop) {
			evaluate(loop.exp, env, function, true);
			Map<Binding, Fact> inner = new IdentityHashMap<>(env);
			refine(loop.exp, true, inner, function);
			block(loop.block, inner, function); mergeInto(env, List.of(new IdentityHashMap<>(env), inner));
		} else if (stat instanceof Stat.NumericFor loop) {
			Fact initial = evaluate(loop.initial, env, function, true);
			checkNumeric(initial, loop.initial, "for 初始值");
			checkNumeric(evaluate(loop.limit, env, function, true), loop.limit, "for 结束值");
			Fact step = loop.step == null ? new Fact(ValueType.INTEGER, "1", false, null)
					: evaluate(loop.step, env, function, true);
			if (loop.step != null) {
				checkNumeric(step, loop.step, "for 步长");
				if (zero(step)) warning("for 步长为 0，可能造成无法结束的循环", span(loop.step));
			}
			Map<Binding, Fact> inner = new IdentityHashMap<>(env);
			inner.put(declarations.get(loop.name), fact(initial.type == ValueType.INTEGER && step.type == ValueType.INTEGER
					? ValueType.INTEGER : ValueType.NUMBER));
			block(loop.block, inner, function); mergeInto(env, List.of(new IdentityHashMap<>(env), inner));
		} else if (stat instanceof Stat.GenericFor loop) {
			values(loop.exps, env, function);
			Map<Binding, Fact> inner = new IdentityHashMap<>(env);
			for (Object raw : loop.names) inner.put(declarations.get((Name) raw), UNKNOWN);
			block(loop.block, inner, function); mergeInto(env, List.of(new IdentityHashMap<>(env), inner));
		} else if (stat instanceof Stat.RepeatUntil loop) {
			block(loop.block, env, function); evaluate(loop.exp, env, function, true);
		} else if (stat instanceof Block block) return block(block, env, function);
		return true;
	}

	private void branch(Stat.IfThenElse branch, Map<Binding, Fact> env, FuncBody function) {
		List<Map<Binding, Fact>> paths = new ArrayList<>();
		Map<Binding, Fact> remaining = new IdentityHashMap<>(env);
		List<Exp> conditions = new ArrayList<>(List.of(branch.ifexp));
		List<Block> blocks = new ArrayList<>(List.of(branch.ifblock));
		if (branch.elseifexps != null) for (int i = 0; i < branch.elseifexps.size(); i++) {
			conditions.add((Exp) branch.elseifexps.get(i)); blocks.add((Block) branch.elseifblocks.get(i));
		}
		for (int i = 0; i < conditions.size(); i++) {
			Exp condition = conditions.get(i);
			evaluate(condition, remaining, function, true);
			Map<Binding, Fact> taken = new IdentityHashMap<>(remaining);
			refine(condition, true, taken, function);
			if (block(blocks.get(i), taken, function)) paths.add(taken);
			refine(condition, false, remaining, function);
		}
		if (branch.elseblock == null || block(branch.elseblock, remaining, function)) paths.add(remaining);
		if (!paths.isEmpty()) mergeInto(env, paths);
	}

	private Fact read(Binding binding, Map<Binding, Fact> env, FuncBody function, Span where, boolean check) {
		Fact value = env.get(binding);
		if (value != null) return value;
		if ((!check || function != null && (!binding.local || binding.function != function)) && !binding.writes.isEmpty())
			return summary(binding);
		if (!binding.local && (PREDEFINED_GLOBALS.contains(binding.name) || LuaApiDocumentation.isApiRoot(binding.name))) {
			if (LuaApiDocumentation.isApiRoot(binding.name)) return new Fact(ValueType.TABLE, null, false, binding.name);
			return Set.of("assert", "print", "error", "require", "tonumber", "tostring", "type", "pairs", "ipairs",
					"pcall", "xpcall", "unpack", "next", "select", "load", "loadfile", "dofile", "setmetatable",
					"getmetatable", "rawget", "rawset", "rawlen", "rawequal", "collectgarbage").contains(binding.name)
					? FUNCTION : UNKNOWN;
		}
		if (check) error("未定义变量“" + binding.name + "”；请先声明或赋值，并检查变量名和作用域", where);
		return UNKNOWN;
	}

	private Fact summary(Binding binding) {
		Fact cached = summaries.get(binding);
		if (cached != null) return cached;
		if (!summarizing.add(binding)) return UNKNOWN;
		Fact result = null;
		try {
			for (Write write : binding.writes) {
				// 'local id; id = register(callback)' must not report its temporary nil
				// inside the deferred callback. Explicit '= nil' remains meaningful.
				if (write.expression == null && !write.dynamic && binding.writes.size() > 1) continue;
				Fact value = write.fixed != null ? write.fixed : write.expression == null
						? write.dynamic ? UNKNOWN : NIL : evaluate(write.expression, Map.of(), binding.function, false);
				result = result == null ? value : union(result, value);
			}
		} finally { summarizing.remove(binding); }
		result = result == null ? UNKNOWN : result;
		summaries.put(binding, result);
		return result;
	}

	private List<Fact> values(List<?> exps, Map<Binding, Fact> env, FuncBody function) {
		List<Fact> values = new ArrayList<>();
		if (exps != null) for (Object raw : exps) values.add(evaluate((Exp) raw, env, function, true));
		return values;
	}

	private static Fact assigned(List<Fact> values, List<?> expressions, int index) {
		return index < values.size() ? values.get(index) : missingIsDynamic(expressions, index) ? UNKNOWN : NIL;
	}

	private Fact evaluate(Exp exp, Map<Binding, Fact> env, FuncBody function, boolean check) {
		if (exp == null) return UNKNOWN;
		if (exp instanceof Exp.Constant constant) return constant(constant);
		if (exp instanceof Exp.NameExp name) return read(references.get(name), env, function, span(exp), check);
		if (exp instanceof Exp.ParensExp parens) return evaluate(parens.exp, env, function, check);
		if (exp instanceof Exp.AnonFuncDef) return FUNCTION;
		if (exp instanceof TableConstructor table) {
			Set<String> keys = new HashSet<>();
			if (table.fields != null) for (Object raw : table.fields) {
				TableField field = (TableField) raw;
				Fact key = field.index == null ? UNKNOWN : evaluate(field.index, env, function, check);
				String normalized = field.name == null ? tableKey(key) : "s:" + field.name;
				if (check && field.index != null && key.type == ValueType.NIL)
					error("表的键不能是 nil", span(field.index));
				if (check && normalized != null && !keys.add(normalized)) warning(
						"重复的表键“" + (field.name == null ? key.literal : field.name) + "”；后面的值会覆盖前面的值",
						field.index == null ? namedSpan(field, field.name) : span(field.index));
				evaluate(field.rhs, env, function, check);
			}
			return fact(ValueType.TABLE);
		}
		if (exp instanceof Exp.FieldExp field) {
			Fact receiver = evaluate(field.lhs, env, function, check);
			if (check) checkIndex(receiver, span(field.lhs));
			if (receiver.api != null) return new Fact(ValueType.FUNCTION, null, false, receiver.api + "." + field.name.name);
			return UNKNOWN;
		}
		if (exp instanceof Exp.IndexExp index) {
			Fact receiver = evaluate(index.lhs, env, function, check);
			evaluate(index.exp, env, function, check);
			if (check) checkIndex(receiver, span(index.lhs));
			return UNKNOWN;
		}
		if (exp instanceof Exp.FuncCall call) {
			Fact callee = evaluate(call.lhs, env, function, check);
			List<Fact> arguments = new ArrayList<>();
			if (call.args.exps != null) for (Object raw : call.args.exps) arguments.add(evaluate((Exp) raw, env, function, check));
			String symbol = callee.api;
			if (call instanceof Exp.MethodCall method && symbol != null) symbol += "." + method.name;
			if (symbol != null) {
				String canonical = canonicalSymbol(symbol);
				if (check && canonical == null) error("未知的 API：“" + symbol + "”，请使用 Tab 补全或查询 API 文档", span(call.lhs));
				else if (check && !canonical.equals(symbol)) error("API 大小写不正确，应写为 “" + canonical + "”", span(call.lhs));
				else if (canonical != null) {
					if (check && call instanceof Exp.MethodCall) error("此 API 应使用点号 . 调用，冒号 : 会额外传入一个参数", span(call.lhs));
					else if (check) validateApi(canonical, call, arguments);
					return apiReturn(canonical);
				}
				return UNKNOWN;
			}
			if (check && !(call instanceof Exp.MethodCall) && Set.of(ValueType.NIL, ValueType.NUMBER,
					ValueType.INTEGER, ValueType.BOOLEAN, ValueType.STRING).contains(callee.type))
				error("不能调用" + callee.type.chinese() + "；括号前需要函数", span(call.lhs));
			if (check && callee.nullable && callee.type != ValueType.NIL) warning("此值可能是 nil，调用前请先检查", span(call.lhs));
			// Calling a user function can modify captured/global variables. Discard
			// stale literals rather than claiming an old nil/value is still certain.
			if (check) invalidateCallWrites(call, env);
			if (call.lhs instanceof Exp.NameExp name && !references.get(name).local && references.get(name).writes.isEmpty()) {
				if (Set.of("tostring", "type").contains(name.name.name)) return fact(ValueType.STRING);
				if (name.name.name.equals("tonumber")) return new Fact(ValueType.NUMBER, null, true, null);
				if (name.name.name.equals("setmetatable")) return UNKNOWN;
			}
			return UNKNOWN;
		}
		if (exp instanceof Exp.UnopExp op) {
			Fact rhs = evaluate(op.rhs, env, function, check);
			if (op.op == Lua.OP_NOT) return fact(ValueType.BOOLEAN);
			if (op.op == Lua.OP_LEN) {
				if (check && Set.of(ValueType.NIL, ValueType.BOOLEAN, ValueType.NUMBER, ValueType.INTEGER).contains(rhs.type))
					error("长度运算 # 需要字符串或表，当前是" + rhs.type.chinese(), span(op.rhs));
				return fact(ValueType.INTEGER);
			}
			if (check) checkNumeric(rhs, op.rhs, "负号运算");
			if (rhs.literal != null && numeric(rhs)) return number(-number(rhs));
			return numeric(rhs) ? fact(rhs.type == ValueType.INTEGER ? ValueType.INTEGER : ValueType.NUMBER) : UNKNOWN;
		}
		if (exp instanceof Exp.BinopExp op) {
			Fact lhs = evaluate(op.lhs, env, function, check);
			Map<Binding, Fact> rightEnv = env;
			if (op.op == Lua.OP_AND || op.op == Lua.OP_OR) {
				rightEnv = new IdentityHashMap<>(env); refine(op.lhs, op.op == Lua.OP_AND, rightEnv, function);
			}
			Fact rhs = evaluate(op.rhs, rightEnv, function, check);
			Boolean truth = truth(lhs);
			if (op.op == Lua.OP_OR) return Boolean.TRUE.equals(truth) ? lhs : Boolean.FALSE.equals(truth)
					? rhs : union(lhs.nonNil(), rhs);
			if (op.op == Lua.OP_AND) return Boolean.FALSE.equals(truth) ? lhs : Boolean.TRUE.equals(truth) ? rhs : union(lhs, rhs);
			if (Set.of(Lua.OP_EQ, Lua.OP_NEQ, Lua.OP_LT, Lua.OP_LE, Lua.OP_GT, Lua.OP_GE).contains(op.op)) {
				if (check && op.op != Lua.OP_EQ && op.op != Lua.OP_NEQ && lhs.type != ValueType.UNKNOWN
						&& rhs.type != ValueType.UNKNOWN && (lhs.type == ValueType.NIL || rhs.type == ValueType.NIL
						|| lhs.type == ValueType.BOOLEAN || rhs.type == ValueType.BOOLEAN
						|| (lhs.type == ValueType.INTEGER || lhs.type == ValueType.NUMBER) && rhs.type == ValueType.STRING
						|| lhs.type == ValueType.STRING && (rhs.type == ValueType.INTEGER || rhs.type == ValueType.NUMBER)))
					error("大小比较需要两个数字或两个字符串，不能比较不同类型", span(op));
				return fact(ValueType.BOOLEAN);
			}
			if (op.op == Lua.OP_CONCAT) {
				if (check) { checkConcat(lhs, op.lhs); checkConcat(rhs, op.rhs); }
				return new Fact(ValueType.STRING, lhs.literal != null && rhs.literal != null
						? lhs.literal + rhs.literal : null, false, null);
			}
			if (check) { checkNumeric(lhs, op.lhs, "算术运算"); checkNumeric(rhs, op.rhs, "算术运算"); }
			if (check && (op.op == Lua.OP_DIV || op.op == Lua.OP_MOD) && zero(rhs))
				warning("除数为 0，结果可能是无穷大或 NaN，请检查此处的值", span(op.rhs));
			if (!numeric(lhs) || !numeric(rhs)) return UNKNOWN;
			if (lhs.literal != null && rhs.literal != null) {
				double a = number(lhs), b = number(rhs);
				return number(switch (op.op) {
					case Lua.OP_ADD -> a + b; case Lua.OP_SUB -> a - b; case Lua.OP_MUL -> a * b;
					case Lua.OP_DIV -> a / b; case Lua.OP_MOD -> a - Math.floor(a / b) * b;
					case Lua.OP_POW -> Math.pow(a, b); default -> Double.NaN;
				});
			}
			return fact(lhs.type == ValueType.INTEGER && rhs.type == ValueType.INTEGER
					&& op.op != Lua.OP_DIV && op.op != Lua.OP_POW ? ValueType.INTEGER : ValueType.NUMBER);
		}
		return UNKNOWN;
	}

	private void validateApi(String symbol, Exp.FuncCall call, List<Fact> args) {
		LuaApiDocumentation.Entry entry = LuaApiDocumentation.find(symbol);
		List<String> params = entry.parameters();
		int required = 0;
		for (int i = 0; i < params.size(); i++) if (!params.get(i).endsWith("?")) required = i + 1;
		boolean expanded = call.args.exps != null && !call.args.exps.isEmpty() && ((Exp) call.args.exps.getLast()).isvarargexp();
		if (args.size() < required && !expanded) {
			error(symbol + " 参数不足：至少需要 " + required + " 个参数，缺少参数“"
					+ clean(params.get(args.size())) + "”", closeSpan(call)); return;
		}
		if (args.size() > params.size()) error(symbol + " 参数过多：最多只能填写 " + params.size() + " 个参数",
				span((Exp) call.args.exps.get(params.size())));
		if (symbol.equals("MC.Sound.play") && !(expanded && args.size() < 4)) {
			int mixed = mixedSoundCoordinate(args.stream().map(value -> value.type).toList());
			if (mixed >= 0) error("MC.Sound.play 的 x、y、z 必须同时填写数字，或同时传 nil（不分坐标播放）",
					mixed < call.args.exps.size() ? span((Exp) call.args.exps.get(mixed)) : closeSpan(call));
		}
		for (int i = 0; i < Math.min(args.size(), params.size()); i++) {
			String param = clean(params.get(i));
			boolean optional = params.get(i).endsWith("?");
			McLuaIdeApi.ValueType declared = i < entry.parameterTypes().size() ? entry.parameterTypes().get(i) : null;
			ExpectedType expected = expectedType(symbol, param, declared);
			Fact value = args.get(i);
			Span where = span((Exp) call.args.exps.get(i));
			if (value.type == ValueType.NIL && optional) continue;
			if (!matches(expected, value.type)) {
				error(symbol + " 的参数" + (i + 1) + "“" + param + "”类型错误：需要"
						+ expected.chinese() + "，当前是" + value.type.chinese()
						+ (value.type == ValueType.NIL ? "；请检查变量是否赋值、是否被 local 遮蔽" : ""), where);
				continue;
			}
			if (!optional && expected != ExpectedType.ANY && value.nullable && value.type != ValueType.NIL)
				warning(symbol + " 的参数" + (i + 1) + "“" + param + "”可能是 nil；请先判断变量是否有值", where);
			String problem = validateLiteral(symbol, param, new StaticValue(value.type, value.literal), declared);
			if (problem != null) error(symbol + " 的参数" + (i + 1) + "“" + param + "”填写错误：" + problem, where);
			if ((symbol.equals("MC.sleep") || symbol.equals("MC.wait") || symbol.equals("MC.Event.onInterval"))
					&& i == 0 && numeric(value) && value.literal != null) {
				double number = number(value);
				int min = symbol.equals("MC.Event.onInterval") ? 1 : 0;
				if (number < min || number > 3_600_000) error(symbol + " 的毫秒数必须在 " + min + "～3600000 之间", where);
			}
		}
	}

	private Fact apiReturn(String symbol) {
		String returns = LuaApiDocumentation.find(symbol).rawReturns();
		boolean nullable = returns.contains("nil");
		ValueType type = returns.contains("字符串") ? ValueType.STRING
				: returns.contains("信息表") || returns.contains("数组") || returns.startsWith("{") ? ValueType.TABLE
				: returns.contains("整数") || returns.contains("数量") || returns.startsWith("监听 ID") ? ValueType.INTEGER
				: returns.contains("布尔") || returns.startsWith("是否") || returns.startsWith("true") ? ValueType.BOOLEAN
				: ValueType.UNKNOWN;
		return new Fact(type, null, nullable, null);
	}

	private void checkIndex(Fact receiver, Span where) {
		if (Set.of(ValueType.NIL, ValueType.BOOLEAN, ValueType.NUMBER, ValueType.INTEGER).contains(receiver.type))
			error("不能读取或修改" + receiver.type.chinese() + "的字段；请先确认此值是表", where);
		else if (receiver.nullable) warning("此值可能是 nil，访问字段前请先检查", where);
	}

	private void checkNumeric(Fact value, Exp expression, String operation) {
		if (value.type == ValueType.NIL || value.type == ValueType.BOOLEAN
				|| value.type == ValueType.STRING && value.literal != null && !numeric(value))
			error(operation + "需要数字，当前是" + value.type.chinese(), span(expression));
		else if (value.nullable) warning(operation + "中的值可能是 nil，请先检查", span(expression));
		// Tables/userdata can implement arithmetic metamethods; never reject them just by type.
	}

	private void checkConcat(Fact value, Exp expression) {
		if (value.type == ValueType.NIL || value.type == ValueType.BOOLEAN)
			error("连接运算 .. 需要字符串或数字，当前是" + value.type.chinese() + "；可使用 tostring 转换", span(expression));
	}

	private void refine(Exp condition, boolean truth, Map<Binding, Fact> env, FuncBody function) {
		if (condition instanceof Exp.ParensExp parens) refine(parens.exp, truth, env, function);
		else if (condition instanceof Exp.UnopExp op && op.op == Lua.OP_NOT) refine(op.rhs, !truth, env, function);
		else if (condition instanceof Exp.NameExp name && truth) {
			Binding binding = references.get(name);
			env.put(binding, read(binding, env, function, span(name), false).nonNil());
		} else if (condition instanceof Exp.BinopExp op) {
			if (op.op == Lua.OP_AND && truth || op.op == Lua.OP_OR && !truth) {
				refine(op.lhs, truth, env, function); refine(op.rhs, truth, env, function);
			} else if (op.op == Lua.OP_EQ || op.op == Lua.OP_NEQ) {
				Exp.NameExp name = op.lhs instanceof Exp.NameExp n && nilConstant(op.rhs) ? n
						: op.rhs instanceof Exp.NameExp n && nilConstant(op.lhs) ? n : null;
				if (name != null) {
					Binding binding = references.get(name);
					Fact value = read(binding, env, function, span(name), false);
					env.put(binding, truth == (op.op == Lua.OP_NEQ) ? value.nonNil() : NIL);
				}
			}
		}
	}

	private static boolean nilConstant(Exp exp) { return exp instanceof Exp.Constant c && c.value.isnil(); }
	private static Fact fact(ValueType type) { return new Fact(type, null, false, null); }
	private Fact constant(Exp.Constant expression) {
		LuaValue value = expression.value;
		if (value.isnil()) return NIL;
		if (value.type() == LuaValue.TBOOLEAN) return new Fact(ValueType.BOOLEAN, value.tojstring(), false, null);
		if (value.type() == LuaValue.TSTRING) {
			Span range = span(expression);
			String raw = source.substring(range.start, range.end);
			// The optional AST parser truncates non-ASCII string bytes, unlike the
			// runtime compiler. Read Unicode literals from the original source.
			String literal = stringValue(raw);
			// Escaped byte strings may not be valid UTF-8, and the AST's escape
			// decoder differs from the runtime. Keep those literals unknown.
			return new Fact(ValueType.STRING, literal, false, null);
		}
		return number(value.todouble());
	}
	private static Fact number(double value) {
		return new Fact(Double.isFinite(value) && value == Math.rint(value) ? ValueType.INTEGER : ValueType.NUMBER,
				Double.isFinite(value) ? Double.toString(value) : null, false, null);
	}
	private static double number(Fact fact) { return LuaValue.valueOf(fact.literal).tonumber().todouble(); }
	private static boolean numeric(Fact fact) {
		return fact.type == ValueType.INTEGER || fact.type == ValueType.NUMBER
				|| fact.type == ValueType.STRING && fact.literal != null && !LuaValue.valueOf(fact.literal).tonumber().isnil();
	}
	private static boolean zero(Fact fact) { return numeric(fact) && fact.literal != null && number(fact) == 0; }
	private static Boolean truth(Fact value) {
		if (value.type == ValueType.NIL || value.type == ValueType.BOOLEAN && "false".equals(value.literal)) return false;
		if (value.nullable || value.type == ValueType.UNKNOWN || value.type == ValueType.BOOLEAN && value.literal == null) return null;
		return true;
	}
	private static String tableKey(Fact fact) {
		if (fact.literal == null) return null;
		return switch (fact.type) {
			case STRING -> "s:" + fact.literal; case INTEGER, NUMBER -> "n:" + number(fact);
			case BOOLEAN -> "b:" + fact.literal; default -> null;
		};
	}
	private static String clean(String name) { return name.endsWith("?") ? name.substring(0, name.length() - 1) : name; }
	private static Fact union(Fact a, Fact b) {
		if (a.equals(b)) return a;
		if (a.type == ValueType.NIL) return new Fact(b.type, b.literal, true, b.api);
		if (b.type == ValueType.NIL) return new Fact(a.type, a.literal, true, a.api);
		ValueType type = a.type == b.type ? a.type : numeric(a) && numeric(b)
				&& a.type != ValueType.STRING && b.type != ValueType.STRING ? ValueType.NUMBER : ValueType.UNKNOWN;
		return new Fact(type, Objects.equals(a.literal, b.literal) ? a.literal : null,
				a.nullable || b.nullable, Objects.equals(a.api, b.api) ? a.api : null);
	}
	private void mergeInto(Map<Binding, Fact> env, List<Map<Binding, Fact>> paths) {
		Set<Binding> bindings = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Map<Binding, Fact> path : paths) bindings.addAll(path.keySet());
		for (Binding binding : bindings) {
			Fact value = null;
			for (Map<Binding, Fact> path : paths) {
				Fact next = path.getOrDefault(binding, env.getOrDefault(binding, NIL));
				value = value == null ? next : union(value, next);
			}
			env.put(binding, value);
		}
	}
	private static Exp valueAt(List<?> values, int index) {
		return values != null && index < values.size() ? (Exp) values.get(index) : null;
	}
	private static boolean missingIsDynamic(List<?> values, int index) {
		return values != null && index >= values.size() && !values.isEmpty() && ((Exp) values.getLast()).isvarargexp();
	}

	// JavaCC defaults to one-character tabs and short columns. Match declarations against
	// lexer tokens rather than searching the whole source for a repeated name.
	private List<Span> declarationSpans(SyntaxElement element, List<?> names) {
		List<Span> result = new ArrayList<>();
		int cursor = tokenAt(span(element).start);
		if (element instanceof FuncBody) {
			while (cursor < tokens.size() && !tokens.get(cursor).text().equals("(")) cursor++;
			if (cursor < tokens.size()) cursor++;
		}
		for (Object raw : names) {
			String name = ((Name) raw).name;
			while (cursor < tokens.size() && !tokens.get(cursor).text().equals(name)) cursor++;
			if (cursor < tokens.size()) {
				Token token = tokens.get(cursor++); result.add(new Span(token.start(), token.end()));
			} else result.add(span(element));
		}
		return result;
	}
	private Span namedSpan(SyntaxElement element, String name) {
		Span span = span(element);
		for (int i = tokenAt(span.start); i < tokens.size() && tokens.get(i).start() < span.end; i++)
			if (tokens.get(i).text().equals(name)) return new Span(tokens.get(i).start(), tokens.get(i).end());
		return span;
	}
	private Span closeSpan(Exp.FuncCall call) {
		Span span = span(call);
		for (int i = Math.min(tokens.size() - 1, tokenAt(span.end) - 1); i >= 0; i--) {
			Token token = tokens.get(i);
			if (token.start() < span.start) break;
			if (token.text().equals(")")) return new Span(token.start(), token.end());
		}
		return span;
	}
	private Span span(SyntaxElement element) {
		Span cached = spans.get(element);
		if (cached != null) return cached;
		int start = offset(element.beginLine, Short.toUnsignedInt(element.beginColumn), false);
		int end = offset(element.endLine, Short.toUnsignedInt(element.endColumn), true);
		// LuaJ's LineInfo can start at the previously consumed delimiter; binary
		// expression nodes can even have no position. Leaf end tokens and child
		// ranges are authoritative, not the delimiter before an expression.
		if (element instanceof Exp.NameExp || element instanceof Exp.Constant) {
			int index = tokenAt(end) - 1;
			if (index >= 0) { start = tokens.get(index).start(); end = tokens.get(index).end(); }
		} else if (element instanceof Exp.BinopExp op) {
			start = span(op.lhs).start; end = span(op.rhs).end;
		} else if (element instanceof Exp.FieldExp field) start = span(field.lhs).start;
		else if (element instanceof Exp.IndexExp index) start = span(index.lhs).start;
		else if (element instanceof Exp.FuncCall call) start = span(call.lhs).start;
		else if (element instanceof Exp.UnopExp op) {
			int previous = tokenAt(span(op.rhs).start) - 1;
			if (previous >= 0) start = tokens.get(previous).start();
			end = span(op.rhs).end;
		} else if (element instanceof TableConstructor || element instanceof Exp.AnonFuncDef) {
			String opening = element instanceof TableConstructor ? "{" : "function";
			for (int i = tokenAt(start); i < tokens.size() && tokens.get(i).start() < end; i++)
				if (tokens.get(i).text().equals(opening)) { start = tokens.get(i).start(); break; }
		}
		Span result = new Span(start, Math.max(start + (start < source.length() ? 1 : 0), end));
		spans.put(element, result);
		return result;
	}
	private int offset(int line, int column, boolean inclusiveEnd) {
		int index = Math.clamp(line - 1, 0, lines.length - 1);
		int end = index + 1 < lines.length ? lines[index + 1] : source.length();
		while (end > lines[index] && (source.charAt(end - 1) == '\r' || source.charAt(end - 1) == '\n')) end--;
		return Math.min(end, lines[index] + Math.max(0, column - (inclusiveEnd ? 0 : 1)));
	}
	private int tokenAt(int position) {
		int low = 0, high = tokens.size();
		while (low < high) { int mid = (low + high) >>> 1;
			if (tokens.get(mid).start() < position) low = mid + 1; else high = mid; }
		return low;
	}
	private int lineAt(int position) {
		int index = Arrays.binarySearch(lines, position);
		return index >= 0 ? index + 1 : -index - 1;
	}
	private void error(String message, Span span) { report(message, span, Severity.ERROR); }
	private void warning(String message, Span span) { report(message, span, Severity.WARNING); }
	private void report(String message, Span span, Severity severity) {
		if (severity == Severity.ERROR ? errors >= LIMIT : warnings >= LIMIT) return;
		if (!problemKeys.add(severity + ":" + span.start + ":" + span.end + ":" + message)) return;
		Diagnostic range = diagnostic(source, message, span.start, Math.min(source.length(), span.end));
		problems.add(new Diagnostic(message, range.start(), range.end(), range.line(), range.column(), severity));
		if (severity == Severity.ERROR) errors++; else warnings++;
	}

	private record Span(int start, int end) { }
	private record Fact(ValueType type, String literal, boolean nullable, String api) {
		Fact nonNil() { return type == ValueType.NIL ? UNKNOWN : new Fact(type, literal, false, api); }
	}
	private record Write(Exp expression, boolean dynamic, Fact fixed) {
		Write(Exp expression, boolean dynamic) { this(expression, dynamic, null); }
	}
	private static final class Binding {
		final String name;
		final boolean local;
		final FuncBody function;
		final Span declaration;
		final List<Write> writes = new ArrayList<>();
		Binding(String name, boolean local, FuncBody function, Span declaration) {
			this.name = name; this.local = local; this.function = function; this.declaration = declaration;
		}
	}
	private static final class Scope {
		final Scope outer;
		final FuncBody function;
		final Map<String, Binding> locals = new LinkedHashMap<>();
		Scope(Scope outer, FuncBody function) { this.outer = outer; this.function = function; }
	}
}
