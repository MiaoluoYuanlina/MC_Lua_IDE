package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.util.List;

public final class LuaArgumentCompletionSmoke {
	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); verify();
	}
	static final List<LuaInsertCatalog.Entry> DIMENSIONS = List.of(
			new LuaInsertCatalog.Entry("minecraft:overworld", "主世界", "minecraft"),
			new LuaInsertCatalog.Entry("minecraft:the_nether", "下界", "minecraft"),
			new LuaInsertCatalog.Entry("minecraft:the_end", "末地", "minecraft"),
			new LuaInsertCatalog.Entry("test:moon/sub", "测试月球", "test"));
	private static final List<LuaInsertCatalog.Entry> ITEMS = List.of(
			new LuaInsertCatalog.Entry("minecraft:diamond", "钻石", "minecraft"),
			new LuaInsertCatalog.Entry("test:custom_apple", "测试苹果", "test"));

	static void verify() {
		verifyDimensions();
		verifyGameRules();
		String variables = "local amount = 3\nlocal fraction = 1.5\nlocal text = 'hello'\n"
				+ "local item = 'minecraft:diamond'\nlocal unknown = mystery()\nlocal enabled = true\n"
				+ "local config = {}\nlocal function callback(e) end\n";
		contains(variables + "MC.Item.give(uuid, |)", ITEMS, "item", "\"minecraft:diamond\"", "\"test:custom_apple\"", "unknown");
		for (String symbol : List.of("MC.Item.isOnCooldown", "MC.Item.startCooldown", "MC.Item.setCooldown", "MC.Item.clearCooldown")) {
			contains(variables + symbol + "(uuid, |)", ITEMS, "item", "\"minecraft:diamond\"", "\"test:custom_apple\"");
			if (LuaApiDocumentation.insertionKind(symbol, 0) != LuaInsertCatalog.Kind.PLAYER
					|| LuaApiDocumentation.insertionKind(symbol, 1) != LuaInsertCatalog.Kind.ITEM)
				throw new AssertionError("Cooldown ID insertion kind missing: " + symbol);
			boolean timed = symbol.equals("MC.Item.startCooldown") || symbol.equals("MC.Item.setCooldown");
			int componentIndex = timed ? 3 : 2;
			String code = symbol + "(uuid, 'minecraft:diamond', " + (timed ? "100, " : "") + "|)";
			int cursor = code.indexOf('|');
			var context = LuaCursorContext.itemComponentsArgumentAt(code.replace("|", ""), cursor);
			if (context == null || context.index() != componentIndex || context.targetKind() != LuaInsertCatalog.Kind.ITEM)
				throw new AssertionError("Cooldown components insertion context missing: " + symbol);
		}
		contains(variables + "MC.Item.setCooldown(uuid, 'minecraft:diamond', |)", ITEMS, "amount", "unknown");
		excludes(variables + "MC.Item.setCooldown(uuid, 'minecraft:diamond', |)", ITEMS, "fraction", "enabled", "item", "config");
		excludes(variables + "MC.Item.give(uuid, |)", ITEMS, "amount", "fraction", "text", "enabled", "config", "callback");
		contains(variables + "MC.Item.give(uuid, 'diamond', |)", ITEMS, "amount", "unknown", "nil");
		excludes(variables + "MC.Item.give(uuid, 'diamond', |)", ITEMS, "text", "item", "fraction", "enabled", "config", "callback", "\"minecraft:diamond\"");
		contains(variables + "MC.Player.setHealth(uuid, |)", List.of(), "amount", "fraction", "unknown");
		contains(variables + "MC.Block.breakAt(0, 64, 0, |)", List.of(), "enabled", "true", "false", "nil");
		excludes(variables + "MC.Block.breakAt(0, 64, 0, |)", List.of(), "item", "amount", "config");
		contains(variables + "MC.Item.give(uuid, 'diamond', 1, |)", List.of(), "config", "text");
		contains(variables + "MC.Event.onItemUsed(|)", List.of(), "callback");
		contains(variables + "MC.Player.setMenuItem(uuid, 0, |)", ITEMS, "item", "\"minecraft:diamond\"");
		contains(variables + "MC.Player.setCarried(uuid, |)", ITEMS, "item", "\"minecraft:diamond\"");
		contains(variables + "MC.Player.openChest(uuid, 'Shop', config, 3, |)", List.of(), "enabled", "true", "false");
		contains(variables + "MC.Item.trade(uuid, |)", List.of(), "config");
		contains(variables + "MC.Event.onContainerItemToCursor(|)", List.of(), "callback");
		contains(variables + "MC.Item.name('minecraft:diamond', |)", List.of(), "\"zh_cn\"", "\"en_us\"", "\"ja_jp\"");
		if (LuaApiDocumentation.insertionKind("MC.Entity.name", 0) != LuaInsertCatalog.Kind.ENTITY
				|| LuaApiDocumentation.insertionKind("MC.Enchantment.name", 0) != LuaInsertCatalog.Kind.ENCHANTMENT)
			throw new AssertionError("Localized name ID insertion kind missing");
		excludes(variables + "MC.Event.onItemUsed(|)", List.of(), "text", "amount", "config");
		String spawned = "local entityUuid = MC.Entity.spawn('minecraft:pig', 0, 64, 0)\n";
		contains(spawned + "MC.Entity.setName(|)", List.of(), "entityUuid");
		contains(spawned + "MC.Entity.components(|)", List.of(), "entityUuid");
		contains("MC.Entity.equipment('uuid', |)", List.of(), "\"mainhand\"", "\"offhand\"", "\"head\"");
		contains("MC.Item.equipment('uuid', |)", List.of(), "\"mainhand\"", "\"offhand\"");
		for (String symbol : List.of("MC.Entity.components", "MC.Entity.equipment", "MC.Item.equipment", "MC.Item.getSlot", "MC.Block.get")) {
			var sample = LuaCodeAnalyzer.analyze("component_example", LuaApiDocumentation.usageTemplate(symbol));
			if (LuaApiDocumentation.find(symbol) == null
					|| !sample.valid())
				throw new AssertionError("Component reading documentation/analyzer rejected: " + symbol + " " + sample.diagnostics());
		}
		contains(spawned + "MC.Team.join('red', |)", List.of(), "entityUuid");
		contains(spawned + "MC.Team.leave(|)", List.of(), "entityUuid");
		contains(spawned + "MC.Team.has('red', |)", List.of(), "entityUuid");
		excludes(variables + "MC.Team.has('red', |)", List.of(), "amount", "fraction", "enabled", "config", "callback");
		contains("local virtual = 'virtual_member'\nMC.Team.has('red', |)", List.of(), "virtual");
		for (String symbol : List.of("MC.Team.join", "MC.Team.leave", "MC.Team.has")) {
			var entry = LuaApiDocumentation.find(symbol);
			if (entry == null || !entry.describe("holder").contains("任意实体 UUID")
					|| !LuaCodeAnalyzer.analyze("team_example", LuaApiDocumentation.usageTemplate(symbol)).valid())
				throw new AssertionError("Team documentation/completion rejected: " + symbol);
		}
		excludes(spawned + "MC.Entity.setGlowing('uuid', |)", List.of(), "entityUuid");
		contains(spawned + "MC.Entity.ride(|)", List.of(), "entityUuid");
		contains(spawned + "MC.Entity.ride(entityUuid, |)", List.of(), "entityUuid");
		contains(variables + "MC.Entity.ride('uuid', 'uuid', |)", List.of(), "enabled", "true", "false", "nil");
		excludes(variables + "MC.Entity.ride('uuid', 'uuid', |)", List.of(), "text", "amount", "config");
		contains(spawned + "MC.Entity.dismount(|)", List.of(), "entityUuid");
		contains(spawned + "local vehicle = MC.Entity.get(entityUuid).vehicleUuid\nMC.Entity.ride(entityUuid, |)", List.of(), "vehicle");
		contains("MC.Item.give(uuid, 'dia|mond')", ITEMS, "minecraft:diamond");
		contains("MC.Item.give(uuid, 'dia|)", ITEMS, "minecraft:diamond'");
		contains("MC.Item.give(uuid, \"zs|\")", ITEMS, "minecraft:diamond");
		contains("MC.Item.give(uuid, \"测试|\")", ITEMS, "test:custom_apple");
		contains("MC.Player.setGameMode(uuid, 'cre|')", List.of(), "creative");
		contains("MC.Item.give(uuid, |)", ITEMS, "\"test:custom_apple\"");
		excludes("local amount = 1\ndo\nlocal amount = 'wrong'\nMC.Player.setHealth(uuid, |)\nend", List.of(), "amount");
		contains("local amount = 1\ndo\nlocal amount = 'wrong'\nend\nMC.Player.setHealth(uuid, |)", List.of(), "amount");
		excludes("do\nlocal hidden = 1\nend\nMC.Player.setHealth(uuid, |)", List.of(), "hidden");
		excludes("MC.Player.setHealth(uuid, |)\nlocal later = 1", List.of(), "later");
		contains("local a, b = 1, 'wrong'\nMC.Player.setHealth(uuid, |)", List.of(), "a");
		excludes("local a, b = 1, 'wrong'\nMC.Player.setHealth(uuid, |)", List.of(), "b");
		contains("local number = 1\nlocal alias = number\nlocal total = number * 2 + alias\nMC.Player.setHealth(uuid, |)", List.of(), "alias", "total");
		contains("local amount = 1\nMC.Player.setHealth(uuid, 20/am|)", List.of(), "amount");
		excludes("local number = 1\nnumber = 'wrong'\nMC.Player.setHealth(uuid, |)", List.of(), "number");
		contains("local uuid = MC.Player.uuid('Steve')\nMC.Item.give(|)", List.of(), "uuid");
		contains("MC.Item.give(MC.Player.uu|)", List.of(), "MC.Player.uuid");
		contains("local uuid = MC.Player.list()[1].uuid\nMC.Item.give(|)", List.of(), "uuid");
		excludes("local invalid = 'hello'\nMC.Item.give(|)", List.of(), "invalid");
		assertNoParameter("MC.Item.give(uuid, -- dia|\n)");
		assertNoParameter("MC.Item.give(uuid, --[[ dia| ]])");
		assertNoParameter("MC.Item.give(uuid, unknownCall(|))");
		assertNoParameter("MC.Event.onServerTick(function(event)\nlocal a, b = 1, 2\nMC.|\nend)");
		parameter("MC.Event.onServerTick(function(event)\nlocal a, b = 1, 2\nend, |)", "MC.Event.onServerTick", 1);
		contains("MC.Event.onServerTick(function(event)\nlocal amount = 3\nMC.Player.setHealth(event.playerUuid, am|)\nend)", List.of(), "amount");
		contains("local empty = nil\nMC.Item.give(uuid, 'diamond', |)", List.of(), "empty");
		excludes("local decimal = 1.5\nlocal result = decimal + 1\nMC.Item.give(uuid, 'diamond', |)", List.of(), "result");
		assertNoParameter("MC.Item.give(uuid, {nested = '|'} )");
		assertNoParameter("MC.Item.give(uuid, 'diamond'|)");
		parameter("MC.Item.give(uuid, MC.Item.name(|))", "MC.Item.name", 0);
		parameter("MC.Item.give(MC.Player.uuid('a,b'), 'diamond', |)", "MC.Item.give", 2);
		parameter("MC.Item.give(uuid, 'diamond', 1, {a=1,b=2}, |)", null, -1);
		String marked = "MC.Item.give(uuid, di|amond, 2)";
		var parsed = parse(marked);
		String replacement = marked.replace("|", "").substring(0, parsed.start()) + "\"minecraft:diamond\""
				+ marked.replace("|", "").substring(parsed.end());
		if (!replacement.equals("MC.Item.give(uuid, \"minecraft:diamond\", 2)")) throw new AssertionError(replacement);
		marked = "MC.Item.give(uuid, 'dia|mond', 2)";
		parsed = parse(marked);
		replacement = marked.replace("|", "").substring(0, parsed.start()) + "minecraft:diamond"
				+ marked.replace("|", "").substring(parsed.end());
		if (!replacement.equals("MC.Item.give(uuid, 'minecraft:diamond', 2)")) throw new AssertionError(replacement);
		verifyThirdParty();
		System.out.println("Lua typed argument completion smoke passed");
	}

	private static void verifyGameRules() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var documented = LuaApiDocumentation.find("MC.GameRule.set");
		if (!documented.parameters().getFirst().equals("ruleId")
				|| !documented.describe("ruleId").contains("游戏规则")
				|| documented.describe("ruleId").contains("在线玩家名称")
				|| !LuaApiDocumentation.find("MC.Player.uuid").describe("name").contains("在线玩家名称"))
			throw new AssertionError("Game-rule documentation confused rule ID with player name");
		if (LuaApiDocumentation.insertionKind("MC.GameRule.set", 0) != LuaInsertCatalog.Kind.GAME_RULE
				|| LuaApiDocumentation.insertionKind("MC.GameRule.set", 1) != null
				|| LuaApiDocumentation.insertionKind("MC.GameRule.set", 2) != LuaInsertCatalog.Kind.DIMENSION)
			throw new AssertionError("Wrong gamerule argument kinds");
		var runtime = LuaInsertCatalog.load(LuaInsertCatalog.Kind.GAME_RULE, null);
		if (runtime.size() != net.minecraft.core.registries.BuiltInRegistries.GAME_RULE.size())
			throw new AssertionError("Game rules were not loaded from the runtime registry");
		contains("MC.GameRule.set(|, true)", runtime, "\"minecraft:keep_inventory\"", "\"minecraft:advance_time\"", "\"minecraft:random_tick_speed\"");
		var rules = List.of(new LuaInsertCatalog.Entry("minecraft:keep_inventory", "保留物品栏", "minecraft"),
				new LuaInsertCatalog.Entry("test:custom_rule", "测试规则", "test"));
		contains("MC.GameRule.set('blwpl|', true)", rules, "minecraft:keep_inventory");
		contains("MC.GameRule.set('保留|', true)", rules, "minecraft:keep_inventory");
		contains("MC.GameRule.set(test:custom_|, true)", rules, "\"test:custom_rule\"");
		contains("MC.GameRule.set('keep_|)", runtime, "minecraft:keep_inventory'");
		contains("local rule = 'minecraft:keep_inventory'\nlocal wrong = 'hello'\nlocal count = 3\nMC.GameRule.set(|, true)", rules, "rule");
		excludes("local wrong = 'hello'\nlocal count = 3\nMC.GameRule.set(|, true)", rules, "wrong", "count");
		contains("MC.GameRule.set('minecraft:keep_inventory', |)", rules, "true", "false");
		excludes("MC.GameRule.set('minecraft:keep_inventory', |)", rules, "\"minecraft:keep_inventory\"");
		String marked = "MC.GameRule.set('保留|', true)";
		var target = LuaCursorContext.argumentAt(marked.replace("|", ""), marked.indexOf('|'));
		if (target == null || target.kind() != LuaInsertCatalog.Kind.GAME_RULE) throw new AssertionError("Ctrl+I missed game-rule argument");
		var decoded = LuaRegistryCatalogData.decodePage("""
				{"registry":"minecraft:game_rule","entries":[
				{"id":"minecraft:keep_inventory"},
				{"id":"test:custom_rule","annotation":"测试规则","description":"模组添加"}],"nextOffset":-1}
				""", null);
		if (!decoded.entries().get(1).name().equals("测试规则") || !decoded.entries().get(1).matches("csgz")
				|| !decoded.entries().get(1).description().equals("模组添加")) throw new AssertionError("Mod rule annotations lost");
		if (!LuaCodeAnalyzer.analyze("game_rule_example", documented.example()).valid()) throw new AssertionError("Game-rule example is flagged by analyzer");
		System.out.println("Game-rule completion passed: " + runtime.size() + " runtime rules, documentation, quoted/bare IDs, Chinese/initials, variables and server annotations");
	}

	private static void verifyThirdParty() {
		var descriptor = McLuaIdeApi.rootFunction("test_mod", "TestMod", "choose", (context, arguments) -> arguments)
				.required("itemId", McLuaIdeApi.ValueType.ITEM_ID)
				.required("count", McLuaIdeApi.ValueType.INTEGER)
				.translation("zh_cn", McLuaIdeApi.translation("测试", "测试方法").returns("整数")
						.usage("TestMod.choose('diamond', 1)").build()).build().descriptor();
		LuaApiDocumentation.replaceExternal(List.of(descriptor));
		try {
			contains("TestMod.choose(|)", ITEMS, "\"minecraft:diamond\"");
			contains("local count = 1\nlocal text = 'hello'\nTestMod.choose('diamond', |)", ITEMS, "count");
			excludes("local count = 1\nlocal text = 'hello'\nTestMod.choose('diamond', |)", ITEMS, "text");
		} finally { LuaApiDocumentation.replaceExternal(List.of()); }
		var dimension = McLuaIdeApi.rootFunction("test_mod", "TestMod", "travel", (context, arguments) -> arguments)
				.required("dimensionId", McLuaIdeApi.ValueType.STRING)
				.translation("zh_cn", McLuaIdeApi.translation("传送", "测试维度参数")
						.returns("维度 ID").usage("TestMod.travel('minecraft:overworld')").build()).build().descriptor();
		LuaApiDocumentation.replaceExternal(List.of(dimension));
		try { contains("TestMod.travel(|)", DIMENSIONS, "\"test:moon/sub\""); }
		finally { LuaApiDocumentation.replaceExternal(List.of()); }
	}

	private static void verifyDimensions() {
		int count = 0;
		for (String symbol : LuaApiDocumentation.symbols()) {
			var entry = LuaApiDocumentation.find(symbol);
			for (int i = 0; i < entry.parameters().size(); i++) if (entry.parameters().get(i).replace("?", "").equals("dimension")) {
				if (LuaApiDocumentation.insertionKind(symbol, i) != LuaInsertCatalog.Kind.DIMENSION)
					throw new AssertionError("Missing dimension kind: " + symbol + " argument " + i);
				String prefix = i == 0 ? "" : String.join(", ", java.util.Collections.nCopies(i, "nil")) + ", ";
				String marked = symbol + "(" + prefix + "\"mi|\")";
				contains(marked, DIMENSIONS, "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
				var target = LuaCursorContext.argumentAt(marked.replace("|", ""), marked.indexOf('|'));
				if (target == null || target.kind() != LuaInsertCatalog.Kind.DIMENSION) throw new AssertionError("Ctrl+I missed " + symbol);
				count++;
			}
		}
		if (count < 30) throw new AssertionError("Dimension API coverage unexpectedly small: " + count);
		contains("MC.World.gameTime(|)", DIMENSIONS, "\"minecraft:overworld\"", "\"test:moon/sub\"", "nil");
		contains("MC.Player.teleport(uuid, 0, 80, 0, 'xj|')", DIMENSIONS, "minecraft:the_nether");
		contains("MC.World.weather('主世界|')", DIMENSIONS, "minecraft:overworld");
		contains("MC.Entity.list('test:moon/|')", DIMENSIONS, "test:moon/sub");
		contains("MC.World.gameTime('the_e|)", DIMENSIONS, "minecraft:the_end'");
		String variables = "local dimension = 'minecraft:the_end'\nlocal wrong = 'hello'\nlocal count = 1\n";
		contains(variables + "MC.World.gameTime(|)", DIMENSIONS, "dimension");
		excludes(variables + "MC.World.gameTime(|)", DIMENSIONS, "wrong", "count");
		var decoded = LuaRegistryCatalogData.decodePage("""
				{"registry":"minecraft:dimension","entries":[
				{"id":"minecraft:overworld"},{"id":"test:moon/sub","annotation":"月球"},
				{"id":"minecraft:the_end","description":"当前已加载"}],"nextOffset":-1}
				""", null);
		if (!decoded.entries().getFirst().matches("zsj") || !decoded.entries().get(1).name().equals("月球")
				|| !decoded.entries().get(2).description().equals("当前已加载")) throw new AssertionError("Dimension names/annotations lost after server catalog decoding");
		System.out.println("Dimension completion coverage passed: " + count + " API parameters and dynamic catalog names");
	}

	private static List<String> choices(String marked, List<LuaInsertCatalog.Entry> catalog) {
		int cursor = marked.indexOf('|');
		String source = marked.replace("|", "");
		var context = LuaCursorContext.parameterAt(source, cursor);
		if (context == null) throw new AssertionError("Missing parameter: " + marked);
		return LuaArgumentCompletion.suggest(source, cursor, context, catalog).stream()
				.map(LuaArgumentCompletion.Choice::insertion).toList();
	}

	private static void contains(String marked, List<LuaInsertCatalog.Entry> catalog, String... expected) {
		var choices = choices(marked, catalog);
		for (String choice : expected) if (!choices.contains(choice)) throw new AssertionError("Missing " + choice + " at " + marked + " got " + choices);
	}

	private static void excludes(String marked, List<LuaInsertCatalog.Entry> catalog, String... unwanted) {
		var choices = choices(marked, catalog);
		for (String choice : unwanted) if (choices.contains(choice)) throw new AssertionError("Unexpected " + choice + " at " + marked + " got " + choices);
	}

	private static LuaCursorContext.Parameter parse(String marked) { return LuaCursorContext.parameterAt(marked.replace("|", ""), marked.indexOf('|')); }
	private static void assertNoParameter(String marked) { if (parse(marked) != null) throw new AssertionError("Leaked parameter: " + marked); }
	private static void parameter(String marked, String symbol, int index) {
		var parameter = parse(marked);
		if (symbol == null) { assertNoParameter(marked); return; }
		if (parameter == null || !parameter.symbol().equals(symbol) || parameter.index() != index) throw new AssertionError("Wrong context: " + marked + " " + parameter);
	}
}
