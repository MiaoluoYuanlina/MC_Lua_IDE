package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in end-to-end protocol probe used by development launches only. */
final class LuaIntegrationProbe {
	private static boolean started;

	private LuaIntegrationProbe() {
	}

	public static void main(String[] args) throws IOException {
		verifyCodeAnalyzer();
		verifyEntityApiScopes();
		LuaSemanticAnalyzerSmoke.verify();
		verifyVariableCompletions();
		LuaArgumentCompletionSmoke.verify();
		LuaIndentationSmoke.verify();
		try { LuaCodeFormatterSmoke.verify(); } catch (Exception error) { throw new IOException(error); }
		LuaModuleCompletionSmoke.verify();
		verifyJsonAnalyzer();
		LuaSyntaxHighlighter.verifyFunctionHighlighting();
		McFunctionCursorContext.verify();
		verifyDimensionTranslations();
		verifyDatapackJsonCategory();
		verifyLocalDatapackResolver();
		verifyApiMarkdownExport();
		List<Path> examples = args.length == 0
				? List.of(Path.of("examples", "diamond_world.lua"),
						Path.of("examples", "test_tnt_item_model.lua"),
						Path.of("examples", "entity_spawn_uuid.lua"),
						Path.of("examples", "entity_ride.lua"),
						Path.of("examples", "script_lifecycle.lua"),
						Path.of("examples", "player_death.lua"),
						Path.of("examples", "player_respawn.lua"))
				: java.util.Arrays.stream(args).map(Path::of).toList();
		for (Path path : examples) {
			String name = path.getFileName().toString();
			String example = Files.readString(path);
			LuaCodeAnalyzer.Analysis exampleAnalysis = LuaCodeAnalyzer.analyze(name, example);
			if (!exampleAnalysis.valid())
				throw new IllegalStateException(name + " 示例被误报：" + exampleAnalysis.diagnostics());
		}
		System.out.println("Lua code analyzer smoke test passed");
	}

	private static void verifyEntityApiScopes() {
		List<String> livingApis = List.of("MC.Entity.kill", "MC.Entity.equipment", "MC.Entity.setEquipment",
				"MC.Effect.give", "MC.Effect.clear", "MC.Effect.list", "MC.Attribute.setBase",
				"MC.Attribute.addModifier", "MC.Attribute.removeModifier", "MC.Event.onEntityDamaged",
				"MC.Event.onEntityDied", "MC.Event.onEntityKilledOther", "MC.Event.onEquipmentChanged",
				"MC.Event.onEffectAdded", "MC.Event.onEffectRemoved", "MC.Event.onSleepStarted", "MC.Event.onSleepStopped");
		for (String symbol : livingApis) {
			var entry = LuaApiDocumentation.find(symbol);
			if (entry == null || !entry.summary().contains("LivingEntity") || !entry.summary().contains("玩家"))
				throw new IllegalStateException("API 目标范围缺少 LivingEntity / 玩家说明：" + symbol);
		}
		String death = LuaApiDocumentation.find("MC.Event.onEntityDied").summary();
		if (!death.contains("监听实体死亡") || !death.contains("掉落物") || !death.contains("卸载"))
			throw new IllegalStateException("实体死亡监听缺少非生命实体 / 卸载边界说明");
		String kill = LuaApiDocumentation.find("MC.Entity.kill").summary();
		if (!kill.contains("不保证触发 onEntityDied"))
			throw new IllegalStateException("kill 文档错误地承诺全部实体触发死亡监听");
		var respawn = LuaApiDocumentation.find("MC.Event.onPlayerRespawned");
		String respawnTemplate = LuaApiDocumentation.usageTemplate("MC.Event.onPlayerRespawned");
		if (!respawn.summary().contains("复活") || !respawn.details().contains("重生前是否存活")
				|| !respawnTemplate.contains("if not event.alive then")
				|| !LuaCodeAnalyzer.analyze("respawn_template", respawnTemplate).valid()
				|| !LuaCodeAnalyzer.analyze("respawn_generic", "MC.Event.on('playerRespawned', function(event) end)").valid())
			throw new IllegalStateException("复活监听文档、alive 筛选或通用注册模板有误");
		System.out.println("Entity API scope documentation smoke passed: " + livingApis.size() + " APIs");
	}

	private static void verifyVariableCompletions() {
		String source = "local playerUuid = ''\n"
				+ "local score, level = 1, 2\n"
				+ "function show(playerName)\n"
				+ "  local result = score + level\n"
				+ "  pla|\n"
				+ "end\n";
		assertVisible(source, "playerUuid", "score", "level", "show", "playerName", "result");
		assertHidden("local x = 1\nfunction show(playerName)\n  local result = x\nend\n|",
				"playerName", "result");
		assertVisible("for tickIndex = 1, 3 do\n  ti|\nend", "tickIndex");
		assertHidden("for tickIndex = 1, 3 do\nend\n|", "tickIndex");
		assertVisible("totalPoints = 4\nto|", "totalPoints");
		assertHidden("-- local ghost = 1\nlocal text = 'local phantom = 2'\n|",
				"ghost", "phantom");
		assertHidden("local config = { hiddenField = 1 }\n|", "hiddenField");
		assertHidden("local config = { sourceValue, hiddenField = 1 }\n|", "sourceValue", "hiddenField");
		assertVisible("repeat\n  local retries = 2\nuntil ret| > 0", "retries");
		assertHidden("repeat\n  local retries = 2\nuntil retries > 0\n|", "retries");
		assertHidden("la|\nlocal later = 1", "later");
	}

	private static void assertVisible(String marked, String... expected) {
		List<String> visible = variablesAtMarker(marked);
		for (String name : expected) if (!visible.contains(name))
			throw new IllegalStateException("Tab 补全缺少变量 " + name + "：" + visible);
	}

	private static void assertHidden(String marked, String... hidden) {
		List<String> visible = variablesAtMarker(marked);
		for (String name : hidden) if (visible.contains(name))
			throw new IllegalStateException("Tab 补全泄漏了不可见变量 " + name + "：" + visible);
	}

	private static List<String> variablesAtMarker(String marked) {
		int cursor = marked.indexOf('|');
		if (cursor < 0) throw new IllegalArgumentException("测试代码缺少光标标记");
		return LuaCodeAnalyzer.visibleVariables(marked.substring(0, cursor) + marked.substring(cursor + 1), cursor);
	}

	private static void verifyApiMarkdownExport() {
		McLuaIdeDocsApi.Translation translation = McLuaIdeDocsApi.translation("测试标题", "介绍")
				.heading("入门").code("print(\"```\")").build();
		LuaApiDocumentation.DocumentPage page = new LuaApiDocumentation.DocumentPage(
				"test_mod", "guide", "intro", translation);
		String markdown = LuaApiMarkdownExporter.render(LuaApiDocumentation.allEntries(), List.of(page));
		if (!markdown.contains("MC.Block.fill") || !markdown.contains("来源模组：`mc_lua_ide`")
				|| !markdown.contains("## 测试标题 / 介绍") || !markdown.contains("````lua")) {
			throw new IllegalStateException("API Markdown 导出缺少内置函数、第三方页面或安全代码围栏");
		}
	}

	private static void verifyJsonAnalyzer() {
		if (!JsonCodeAnalyzer.analyze("{\"pack\":{\"description\":\"示例\"},\"values\":[1,true,null]}").valid())
			throw new IllegalStateException("有效 JSON 被误报");
		LuaCodeAnalyzer.Analysis invalid = JsonCodeAnalyzer.analyze("{\n  \"values\": [1, }\n}");
		if (invalid.valid() || invalid.first().line() != 2)
			throw new IllegalStateException("无效 JSON 未定位到第 2 行：" + invalid.diagnostics());
		if (JsonCodeAnalyzer.analyze("{\"values\": [1,]}").valid())
			throw new IllegalStateException("严格 JSON 检查未拒绝尾随逗号");
	}

	private static void verifyDimensionTranslations() {
		DimensionJsonTranslations translations = DimensionJsonTranslations.INSTANCE;
		if (!"维度类型".equals(translations.label(List.of("type"), "type", new JsonPrimitive("minecraft:overworld"))))
			throw new IllegalStateException("维度类型缺少中文译名");
		if (!"层方块".equals(translations.label(
				List.of("generator", "settings", "layers", "0", "block"), "block", new JsonPrimitive("minecraft:stone"))))
			throw new IllegalStateException("嵌套地层字段缺少中文译名");
		if (!"超平坦".equals(translations.valueLabel(
				List.of("generator", "type"), new JsonPrimitive("minecraft:flat"))))
			throw new IllegalStateException("生成器取值缺少中文译名");
	}

	private static void verifyDatapackJsonCategory() {
		if (!"worldgen/biome".equals(DatapackJsonDocumentCategory.fromPath("demo/data/example/worldgen/biome/test.json"))
				|| !"tags/item".equals(DatapackJsonDocumentCategory.fromPath("demo/data/example/tags/item/custom/test.json"))
				|| !"pack.mcmeta".equals(DatapackJsonDocumentCategory.fromPath("demo/pack.mcmeta"))
				|| !"mod_custom".equals(DatapackJsonDocumentCategory.fromPath("demo/data/example/mod_custom/nested/test.json"))
				|| !"tags/worldgen/noise".equals(DatapackJsonDocumentCategory.fromPath("demo/data/example/tags/worldgen/noise/test.json"))
				|| DatapackJsonDocumentCategory.fromPath("demo/data/example/function/test.mcfunction") != null)
			throw new IllegalStateException("数据包 JSON 类型识别失败");
		if (wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog.jsonCategories().stream()
				.noneMatch(entry -> entry.path().equals("recipe")))
			throw new IllegalStateException("配方 JSON 类型缺失");
	}

	private static void verifyLocalDatapackResolver() throws IOException {
		Path root = Files.createTempDirectory("mc-lua-ide-datapack-open-");
		Path pack = root.resolve("mc_lua_ide_edit_demo");
		Path data = pack.resolve("data");
		Path namespace = data.resolve("demo");
		Path file = namespace.resolve("example.json");
		try {
			Files.createDirectories(namespace);
			Files.writeString(file, "{}");
			if (!LocalDatapackLauncher.resolve(root, "demo/data/demo/example.json", false).equals(file)
					|| !LocalDatapackLauncher.resolve(root, "demo/data/demo", true).equals(namespace))
				throw new IllegalStateException("本地数据包文件与目录定位错误");
			for (String invalid : List.of("../outside", "demo/../outside", "demo\\data", "other/data")) {
				try {
					LocalDatapackLauncher.resolve(root, invalid, true);
					throw new IllegalStateException("不安全的数据包路径被接受：" + invalid);
				} catch (IOException expected) {
					// Rejected before any OS process is launched.
				}
			}
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(namespace);
			Files.deleteIfExists(data);
			Files.deleteIfExists(pack);
			Files.deleteIfExists(root);
		}
	}

	static void runIfEnabled() {
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_TEXT_JSON_TEST"))) {
			if (!started) { started = true; runTextJsonProbe(); }
			return;
		}
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PERSONALIZATION_TEST"))) {
			if (!started) { started = true; runPersonalizationProbe(); }
			return;
		}
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_DOUBLE_CLICK_TEST"))) {
			if (!started) { started = true; runDoubleClickProbe(); }
			return;
		}
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_COMPONENT_TREE_TEST"))) {
			if (!started) { started = true; runComponentTreeProbe(); }
			return;
		}
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_ID_EDITOR_TEST"))) {
			if (!started) { started = true; runIdEditorProbe(); }
			return;
		}
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_UI_EDITOR_TEST"))) {
			if (!started) { started = true; runCanvasOnlyProbe(); }
			return;
		}
		boolean enabled = Boolean.getBoolean("mc_lua_ide.integrationTest")
				|| "true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_INTEGRATION_TEST"));
		if (!enabled || started) return;
		started = true;
		try {
			verifyCodeAnalyzer();
			verifyCursorContext();
			verifyResourceAssetCatalog();
		} catch (RuntimeException error) {
			McLuaIdeMod.LOGGER.error("MC Lua IDE code analyzer probe failed", error);
			return;
		}
		String name = "_mc_lua_ide_test_" + Long.toUnsignedString(System.nanoTime(), 36);
		List<String> output = new ArrayList<>();
		String source = """
				assert(MC.Command == nil, "command API should be disabled by probe policy")
				assert(MC.Network == nil, "network API should be disabled by probe policy")
				assert(luajava == nil and os == nil and io == nil and package == nil)
				assert(require == nil and debug == nil)
				local limits = MC.Limits.current()
				assert(MC.UI.available())
				local player = MC.Player.list()[1]
				assert(player ~= nil)
				-- 33 x 31 x 33 = 33759，明确超过原版 /fill 的 32768 限制；高空填 air 不破坏测试世界。
				local bx, bz = math.floor(player.x) - 16, math.floor(player.z) - 16
				assert(type(MC.Block.fill(bx, 289, bz, bx + 32, 319, bz + 32, "minecraft:air")) == "boolean")
				assert(MC.Chat.message(player.uuid, {
				    text = "text-json-smoke", color = "#55FF55",
				    font = "minecraft:default", underlined = true
				}))
				assert(MC.Title.actionbar(player.uuid,
				    [[{"text":"title-json-smoke","color":"#55FFFF","bold":true}]]))
				assert(MC.UI.register("probe", {
				    type = "notice", title = "UI probe", body = "packet test",
				    buttons = {{id = "ok", label = "确定"}}
				}))
				assert(#MC.UI.list() == 1)
				assert(MC.UI.open(player.uuid, "probe"))
				MC.UI.close(player.uuid)
				assert(MC.UI.unregister("probe"))
				assert(type(MC.Block.random()) == "string")
				assert(type(MC.Item.random()) == "string")
				assert(type(MC.Entity.random()) == "string")
				if limits.requireClientMod then
				    assert(MC.Client.available(player.uuid))
				    local clientInfo = MC.Client.info(player.uuid)
				    assert(clientInfo.online and clientInfo.required and clientInfo.available)
				    assert(clientInfo.protocol == %d)
				    assert(MC.Client.message(player.uuid,
				        {text = "client-api-smoke", color = "#55FFFF"}))
				    assert(MC.Client.toast(player.uuid,
				        {text = "客户端 API", color = "gold", bold = true}, "集成测试通过"))
				    assert(MC.Client.notice(player.uuid,
				        {text = "客户端 API", color = "#FFAA00"}, "通知页面测试"))
				    assert(MC.Client.closeNotice(player.uuid))
				    assert(type(MC.Client.hasResourcePack(player.uuid, "vanilla")) == "boolean")
				end
				MC.File.write("probe.txt", "file-smoke")
				print("network-smoke", MC.apiVersion(), MC.Server.playerCount(),
				    MC.File.read("probe.txt"), limits.allowFileRead)
				assert(MC.File.delete("probe.txt"))
				""".formatted(LuaProtocol.VERSION);
		AtomicReference<LuaLimits> originalLimits = new AtomicReference<>();
		AtomicReference<LuaLimits> probeLimits = new AtomicReference<>();

		ScriptManager.componentEditorData("item", "minecraft:diamond_sword")
				.thenCompose(catalog -> {
					if (!catalog.itemId().equals("minecraft:diamond_sword")
							|| catalog.components().stream().noneMatch(component ->
							component.id().equals("minecraft:unbreakable"))) {
						throw new IllegalStateException("组件编辑器没有返回预期的服务端目录");
					}
					LuaComponentEditorData.ComponentInfo potion = catalog.components().stream()
							.filter(component -> component.id().equals("minecraft:potion_contents"))
							.findFirst().orElseThrow(() -> new IllegalStateException("组件目录缺少 potion_contents"));
					if (potion.idHints().stream().noneMatch(hint -> hint.registry().equals("minecraft:mob_effect"))) {
						throw new IllegalStateException("组件字段没有同步状态效果注册表提示");
					}
					assertComponentHint(catalog, "minecraft:item_model", "", "mc_lua_ide:item_model_asset");
					assertComponentHint(catalog, "minecraft:tooltip_style", "", "mc_lua_ide:tooltip_style_asset");
					assertComponentHint(catalog, "minecraft:equippable", "/asset_id", "mc_lua_ide:equipment_asset");
					assertComponentHint(catalog, "minecraft:equippable", "/camera_overlay", "mc_lua_ide:texture_asset");
					assertComponentHint(catalog, "minecraft:damage_type", "", "minecraft:damage_type");
					assertComponentHint(catalog, "minecraft:tooltip_display", "/hidden_components/*",
							"minecraft:data_component_type");
					assertComponentHint(catalog, "minecraft:consumable", "/sound", "minecraft:sound_event");
					assertComponentHint(catalog, "minecraft:container_loot", "/loot_table", "minecraft:loot_table");
					return ScriptManager.validateItemComponents(catalog.itemId(),
							"{\"minecraft:custom_name\":{\"text\":\"组件探针\"},\"minecraft:unbreakable\":{}}");
				})
				.thenCompose(canonical -> {
					if (!canonical.contains("minecraft:custom_name") || !canonical.contains("minecraft:unbreakable")) {
						throw new IllegalStateException("组件编辑器服务端校验没有保留组件：" + canonical);
					}
					return ScriptManager.componentEditorData("block", "minecraft:oak_stairs");
				})
				.thenCompose(catalog -> {
					if (!catalog.kind().equals("block") || catalog.components().stream().anyMatch(component ->
							component.id().equals("minecraft:max_stack_size"))) {
						throw new IllegalStateException("方块编辑器错误地返回物品堆叠组件");
					}
					var states = catalog.components().stream().filter(component -> component.id().equals("states"))
							.findFirst().orElseThrow(() -> new IllegalStateException("方块目录缺少实际状态属性"));
					if (!states.schema().fields().containsKey("facing"))
						throw new IllegalStateException("方块状态缺少 facing 方向字段");
					return ScriptManager.validateComponents("block", catalog.requestedId(),
							"{\"states\":{\"facing\":\"north\",\"half\":\"top\"}}");
				})
				.thenCompose(canonical -> {
					if (!canonical.contains("north") || !canonical.contains("top"))
						throw new IllegalStateException("方块状态校验未保留属性：" + canonical);
					return ScriptManager.componentEditorData("block", "minecraft:chest");
				})
				.thenCompose(catalog -> {
					var data = catalog.components().stream().filter(component -> component.id().equals("blockEntityData"))
							.findFirst().orElseThrow(() -> new IllegalStateException("箱子缺少方块实体数据树"));
					if (!data.schema().fields().containsKey("Items"))
						throw new IllegalStateException("箱子没有容器物品槽模板");
					return ScriptManager.validateComponents("block", catalog.requestedId(),
							"{\"blockEntityData\":{\"Items\":[{\"Slot\":0,\"id\":\"minecraft:diamond\",\"count\":2}]}}");
				})
				.thenCompose(canonical -> {
					if (!canonical.contains("minecraft:diamond"))
						throw new IllegalStateException("箱子物品槽校验未保留物品：" + canonical);
					return ScriptManager.componentEditorData("block", "minecraft:furnace");
				})
				.thenCompose(catalog -> {
					var data = catalog.components().stream().filter(component -> component.id().equals("blockEntityData"))
							.findFirst().orElseThrow(() -> new IllegalStateException("熔炉缺少方块实体数据树"));
					if (!data.schema().fields().containsKey("lit_time_remaining"))
						throw new IllegalStateException("熔炉缺少燃烧进度模板");
					return ScriptManager.componentEditorData("block", "minecraft:oak_sign");
				})
				.thenCompose(catalog -> {
					var data = catalog.components().stream().filter(component -> component.id().equals("blockEntityData"))
							.findFirst().orElseThrow(() -> new IllegalStateException("告示牌缺少方块实体数据树"));
					if (!data.schema().fields().containsKey("front_text") || !data.schema().fields().containsKey("back_text"))
						throw new IllegalStateException("告示牌缺少正反文字模板");
					return ScriptManager.validateComponents("block", catalog.requestedId(),
							"{\"blockEntityData\":{\"front_text\":{\"messages\":[{\"text\":\"第一行\"},{\"text\":\"\"},{\"text\":\"\"},{\"text\":\"\"}]}}}");
				})
				.thenCompose(canonical -> {
					if (!canonical.contains("第一行"))
						throw new IllegalStateException("告示牌文字校验未保留内容：" + canonical);
					return ScriptManager.componentEditorData("entity", "minecraft:wolf");
				})
				.thenCompose(catalog -> {
					if (!catalog.kind().equals("entity") || !catalog.requestedId().equals("minecraft:wolf")) {
						throw new IllegalStateException("实体组件目录目标错误：" + catalog);
					}
					for (String forbidden : List.of("minecraft:item_model", "minecraft:entity_data",
							"minecraft:max_stack_size")) {
						if (catalog.components().stream().anyMatch(component -> component.id().equals(forbidden))) {
							throw new IllegalStateException("实体组件目录混入了刷怪蛋/物品组件：" + forbidden);
						}
					}
					if (catalog.components().stream().noneMatch(component ->
							component.id().equals("minecraft:custom_data"))) {
						throw new IllegalStateException("实体组件目录缺少实体自定义数据组件");
					}
					for (String field : List.of("health", "equipment")) {
						if (catalog.components().stream().noneMatch(component -> component.id().equals(field)))
							throw new IllegalStateException("实体编辑器缺少 " + field);
					}
					return ScriptManager.validateComponents("entity", catalog.requestedId(),
							"{\"minecraft:custom_name\":{\"text\":\"组件探针实体\"},\"health\":1,\"equipment\":{\"mainhand\":{\"id\":\"minecraft:iron_sword\",\"count\":1}}}");
				})
				.thenCompose(canonical -> {
					if (!canonical.contains("minecraft:custom_name") || !canonical.contains("equipment") || !canonical.contains("health")) {
						throw new IllegalStateException("实体组件校验没有保留自定义名称：" + canonical);
					}
					return ScriptManager.registryTypes();
				})
				.thenCompose(types -> {
					for (String expected : List.of("minecraft:timeline", "minecraft:world_clock",
							"minecraft:item", "minecraft:mob_effect")) {
						if (types.stream().noneMatch(type -> type.id().equals(expected))) {
							throw new IllegalStateException("服务器注册表类型目录缺少 " + expected);
						}
					}
					return ScriptManager.registryCatalog("minecraft:timeline");
				})
				.thenCompose(entries -> {
					if (entries.stream().noneMatch(entry -> entry.value().equals("minecraft:day")
							&& entry.label().contains("昼夜时间轴"))) {
						throw new IllegalStateException("时间轴注册表缺少带注释的 minecraft:day，实际条目：" + entries);
					}
					return ScriptManager.registryCatalog("minecraft:item");
				})
				.thenCompose(entries -> {
					if (entries.stream().noneMatch(entry -> entry.value().equals("minecraft:apple"))) {
						throw new IllegalStateException("物品注册表缺少 minecraft:apple");
					}
					return ScriptManager.registryCatalog("minecraft:mob_effect");
				})
				.thenCompose(entries -> {
					if (entries.stream().noneMatch(entry -> entry.value().equals("minecraft:dolphins_grace")
							&& entry.label().contains("海豚的恩惠"))) {
						throw new IllegalStateException("状态效果注册表缺少带注释的 minecraft:dolphins_grace");
					}
					return ScriptManager.registryCatalog("minecraft:enchantment");
				})
				.thenApply(entries -> {
					LuaInsertCatalog.Entry sharpness = entries.stream()
							.filter(entry -> entry.value().equals("minecraft:sharpness"))
							.findFirst().orElse(null);
					if (sharpness == null) {
						throw new IllegalStateException("服务端注册表目录缺少 minecraft:sharpness");
					}
					if (!sharpness.matches("锋利") || !sharpness.matches("fl")) {
						throw new IllegalStateException("附魔 ID 不支持中文或首拼搜索：" + sharpness);
					}
					return null;
				})
				.thenCompose(ignored -> ScriptManager.getLimits())
				.thenApply(limits -> {
					originalLimits.set(limits);
					Map<String, String> values = limits.toMap();
					values.put("maxRuntimeSeconds", "1");
					values.put("maxScriptChars", "100000");
					values.put("allowCommandApi", "false");
					values.put("allowServerApi", "true");
					values.put("allowPresentationApi", "true");
					values.put("requireClientMod", "true");
					values.put("allowFileRead", "true");
					values.put("allowFileWrite", "true");
					values.put("allowFileDelete", "true");
					values.put("allowJavaInterop", "false");
					values.put("allowOperatingSystem", "false");
					values.put("allowUnrestrictedFileIo", "false");
					values.put("allowPackageLoading", "false");
					values.put("allowDebugLibrary", "false");
					values.put("allowNetworkAccess", "false");
					LuaLimits testLimits = LuaLimits.fromMap(values);
					probeLimits.set(testLimits);
					return testLimits;
				})
				.thenCompose(ScriptManager::saveLimits)
				.thenCompose(ignored -> ScriptManager.save(name, source))
				.thenCompose(ignored -> ScriptManager.read(name))
				.thenApply(read -> {
					if (!source.equals(read)) throw new IllegalStateException("保存后回读内容不一致");
					return read;
				})
				.thenCompose(ignored -> ScriptManager.listScripts())
				.thenCompose(scripts -> {
					if (!scripts.contains(name)) throw new IllegalStateException("脚本目录没有新建文件");
					return execute(name, source, output, false);
				})
				.thenApply(result -> {
					if (!result.succeeded()) throw new IllegalStateException("普通脚本没有成功结束");
					if (output.stream().noneMatch(line -> line.contains("network-smoke")
							&& line.contains("13")
							&& line.contains("file-smoke") && line.contains("true"))) {
						throw new IllegalStateException("没有收到预期的实时输出：" + output);
					}
					return null;
				})
				.thenCompose(ignored -> {
					Map<String, String> values = probeLimits.get().toMap();
					values.put("allowJavaInterop", "true");
					values.put("allowOperatingSystem", "true");
					values.put("allowUnrestrictedFileIo", "true");
					values.put("allowPackageLoading", "true");
					values.put("allowDebugLibrary", "true");
					values.put("allowNetworkAccess", "true");
					return ScriptManager.saveLimits(LuaLimits.fromMap(values));
				})
				.thenCompose(ignored -> execute(name, """
						assert(luajava ~= nil and os ~= nil and io ~= nil and package ~= nil)
						assert(require ~= nil and debug ~= nil and MC.Network ~= nil)
						""", new ArrayList<>(), false))
				.thenApply(result -> {
					if (!result.succeeded()) throw new IllegalStateException("高危边界开关没有正确开放库：" + result);
					return null;
				})
				.thenCompose(ignored -> ScriptManager.saveLimits(probeLimits.get()))
				.thenCompose(ignored -> execute(name, "while true do end\n", new ArrayList<>(), false))
				.thenApply(result -> {
					if (result.error() == null || !result.error().contains("最长运行时间")) {
						throw new IllegalStateException("运行时间限制没有终止无限循环：" + result);
					}
					return null;
				})
				.thenCompose(ignored -> execute(name, "while true do end\n", new ArrayList<>(), true))
				.thenApply(result -> {
					if (!result.stopped()) throw new IllegalStateException("远程停止没有返回 stopped 状态");
					return null;
				})
				.thenCompose(ignored -> ScriptManager.saveLimits(originalLimits.get()))
				.thenCompose(ignored -> ScriptManager.delete(name))
				.whenComplete((deleted, failure) -> {
					if (failure != null) {
						McLuaIdeMod.LOGGER.error("MC Lua IDE integration probe failed", failure);
						CompletableFuture<?> restore = originalLimits.get() == null
								? CompletableFuture.completedFuture(null)
								: ScriptManager.saveLimits(originalLimits.get());
						restore.thenCompose(ignored -> ScriptManager.delete(name)).whenComplete((removed, cleanupFailure) -> {
							if (cleanupFailure != null) McLuaIdeMod.LOGGER.warn(
									"Failed to clean integration probe script {}", name, cleanupFailure);
						});
					} else if (!deleted) {
						McLuaIdeMod.LOGGER.error("MC Lua IDE integration probe could not remove its temporary script");
					} else {
						McLuaIdeMod.LOGGER.info("MC Lua IDE integration probe passed: component catalog/validation, dynamic registry ID search, UI dialogs, syntax and API diagnostics, precise ranges, permissions, global limits, danger switches, sandbox files, timeout, output and stop");
						runUiSmoke();
					}
				});
	}

	private static void runTextJsonProbe() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			boolean screenshotPending = false;
			try {
				var dynamicOps = net.minecraft.resources.RegistryOps.create(
						com.mojang.serialization.JsonOps.INSTANCE, client.level.registryAccess());
				List<LuaTextJsonDocument.Segment> cases = new ArrayList<>();
				LuaTextJsonDocument.Segment literal = new LuaTextJsonDocument.Segment();
				literal.text = "你好世界";
				literal.gradient = true;
				literal.gradientStops = "#FF0000, #00FF00, #0000FF";
				literal.bold = true;
				literal.hover = LuaTextJsonDocument.Hover.TEXT;
				literal.hoverText = "悬浮提示";
				literal.click = LuaTextJsonDocument.Click.RUN_COMMAND;
				literal.clickValue = "/help";
				cases.add(literal);
				for (LuaTextJsonDocument.Kind kind : LuaTextJsonDocument.Kind.values()) {
					LuaTextJsonDocument.Segment item = new LuaTextJsonDocument.Segment();
					item.kind = kind;
					cases.add(item);
				}
				for (LuaTextJsonDocument.Hover hover : LuaTextJsonDocument.Hover.values()) {
					LuaTextJsonDocument.Segment item = new LuaTextJsonDocument.Segment();
					item.hover = hover;
					cases.add(item);
				}
				for (LuaTextJsonDocument.Click click : LuaTextJsonDocument.Click.values()) {
					LuaTextJsonDocument.Segment item = new LuaTextJsonDocument.Segment();
					item.click = click;
					item.clickValue = switch (click) {
						case NONE -> ""; case RUN_COMMAND, SUGGEST_COMMAND -> "/help";
						case OPEN_URL -> "https://minecraft.net"; case COPY -> "复制文字";
						case CHANGE_PAGE -> "1"; case SHOW_DIALOG -> "minecraft:server_links";
					};
					cases.add(item);
				}
				for (LuaTextJsonDocument.Segment item : cases) {
					var json = LuaTextJsonDocument.build(List.of(item));
					var decoded = net.minecraft.network.chat.ComponentSerialization.CODEC.parse(dynamicOps, json);
					if (decoded.result().isEmpty()) throw new AssertionError("Minecraft rejected text component "
							+ json + ": " + decoded.error());
				}
				var combined = LuaTextJsonDocument.build(cases);
				if (net.minecraft.network.chat.ComponentSerialization.CODEC.parse(dynamicOps, combined)
						.result().isEmpty()) throw new AssertionError("Minecraft rejected segmented text component");
				var gradient = LuaTextJsonDocument.build(List.of(literal)).getAsJsonObject().getAsJsonArray("extra");
				if (gradient.size() != LuaTextJsonDocument.graphemes(literal.text).size()
						|| !gradient.get(0).getAsJsonObject().get("color").getAsString().equals("#FF0000")
						|| !gradient.get(gradient.size() - 1).getAsJsonObject().get("color").getAsString().equals("#0000FF"))
					throw new AssertionError("Multi-stop gradient endpoints or visible-character split are wrong");
				for (var part : gradient)
					if (!part.getAsJsonObject().has("hover_event") || !part.getAsJsonObject().has("click_event"))
						throw new AssertionError("Gradient character lost its hover or click event");
				LuaTextJsonDocument.Segment solid = new LuaTextJsonDocument.Segment();
				solid.color = "#FF0000";
				solid.gradientStops = "#123ABC, #00FF00";
				var solidJson = LuaTextJsonDocument.build(List.of(solid)).getAsJsonObject();
				if (!"#123ABC".equals(solidJson.get("color").getAsString()))
					throw new AssertionError("Solid text did not use the first color ID");
				if (net.minecraft.network.chat.ComponentSerialization.CODEC.parse(dynamicOps, solidJson)
						.result().isEmpty()) throw new AssertionError("Minecraft rejected first-stop solid color");
				McLuaIdeMod.LOGGER.info("Text JSON probe passed: {} component variants and combined document", cases.size());
				Screen previous = client.screen;
				Screen preview = new Screen(net.minecraft.network.chat.Component.literal("Text JSON editor probe")) {
					private final LuaTextJsonOverlay overlay = new LuaTextJsonOverlay(client, client.font,
							ignored -> { }, ignored -> { });
					@Override protected void init() {
						overlay.resize(width, height);
						overlay.showDemoForTesting();
						overlay.open();
					}
					@Override public void tick() { overlay.tick(); }
					@Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
							int mouseX, int mouseY, float partialTick) {
						graphics.fill(0, 0, width, height, 0xFF10151D);
						overlay.render(graphics, mouseX, mouseY);
					}
					@Override public boolean isPauseScreen() { return false; }
				};
				client.setScreen(preview);
				CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
					try {
						if (client.screen != preview) throw new AssertionError("Text editor preview closed early");
						net.minecraft.client.Screenshot.grab(client.gameDirectory, "text-json-editor-probe.png",
								client.getMainRenderTarget(), 1,
								message -> McLuaIdeMod.LOGGER.info("Text JSON screenshot: {}", message.getString()));
					} catch (Exception | AssertionError error) {
						McLuaIdeMod.LOGGER.error("Text JSON preview failed", error);
					} finally {
						client.setScreen(previous);
						if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT"))) client.stop();
					}
				}));
				screenshotPending = true;
			} catch (Exception | AssertionError error) {
				McLuaIdeMod.LOGGER.error("Text JSON probe failed", error);
			} finally {
				if (!screenshotPending && "true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT")))
					client.stop();
			}
		});
	}

	private static void runPersonalizationProbe() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			Screen previous = client.screen;
			java.nio.file.Path temporary = null;
			try {
				LuaPersonalizationSettings draft = LuaPersonalizationSettings.load();
				draft.fontFace = LuaPersonalizationSettings.FontFace.UNIFORM;
				draft.fontPercent = 140;
				draft.lineHeight = 1.3F;
				draft.cellWidth = 1.2F;
				draft.cursorShape = LuaPersonalizationSettings.CursorShape.BLOCK;
				draft.syntaxColors.put(LuaSyntaxHighlighter.ColorRole.KEYWORD, 0xFF6699);
				draft.syntaxColors.put(LuaSyntaxHighlighter.ColorRole.COMMENT, 0x76CE8A);
				java.nio.file.Path example = client.gameDirectory.toPath().resolve("screenshots/ui-canvas-probe.png");
				if (java.nio.file.Files.isRegularFile(example)) {
					java.nio.file.Files.createDirectories(LuaPersonalizationSettings.BACKGROUNDS);
					temporary = LuaPersonalizationSettings.BACKGROUNDS.resolve("_appearance_probe.png");
					java.nio.file.Files.copy(example, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					draft.backgroundFile = temporary.getFileName().toString();
				}
				LuaIdeScreen ide = new LuaIdeScreen(previous, "test");
				ide.setPersonalization(draft);
				client.setScreen(ide);
				LuaPersonalizationScreen settings = new LuaPersonalizationScreen(ide, draft);
				client.setScreen(settings);
				settings.showSyntaxColorsForTesting();
				java.nio.file.Path created = temporary;
				CompletableFuture.delayedExecutor(1200, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
					try {
						if (client.screen != settings) throw new IllegalStateException("Personalization page closed early");
						net.minecraft.client.Screenshot.grab(client.gameDirectory, "personalization-probe.png",
								client.getMainRenderTarget(), 1,
								message -> McLuaIdeMod.LOGGER.info("Personalization screenshot: {}", message.getString()));
						client.setScreen(ide);
					} catch (Exception | AssertionError error) {
						McLuaIdeMod.LOGGER.error("MC Lua IDE personalization probe failed", error);
						finishPersonalizationProbe(client, previous, created);
						return;
					}
					CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
						try {
							if (client.screen != ide) throw new IllegalStateException("IDE preview closed early");
							net.minecraft.client.Screenshot.grab(client.gameDirectory, "personalization-editor-probe.png",
									client.getMainRenderTarget(), 1,
									message -> McLuaIdeMod.LOGGER.info("Personalization editor screenshot: {}", message.getString()));
							ide.verifyDoubleClickForTesting();
							McLuaIdeMod.LOGGER.info("MC Lua IDE personalization probe passed: page, uniform font, text metrics, background, cursor and selection");
						} catch (Exception | AssertionError error) {
							McLuaIdeMod.LOGGER.error("MC Lua IDE personalization probe failed", error);
						} finally {
							finishPersonalizationProbe(client, previous, created);
						}
					}));
				}));
			} catch (Exception error) {
				McLuaIdeMod.LOGGER.error("MC Lua IDE personalization setup failed", error);
				client.setScreen(previous);
				if (temporary != null) try { java.nio.file.Files.deleteIfExists(temporary); }
				catch (Exception ignored) { }
				if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT"))) client.stop();
			}
		});
	}

	private static void finishPersonalizationProbe(Minecraft client, Screen previous, java.nio.file.Path created) {
		client.setScreen(previous);
		if (created != null) try { java.nio.file.Files.deleteIfExists(created); }
		catch (Exception error) { McLuaIdeMod.LOGGER.warn("Could not remove probe background {}", created, error); }
		if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT"))) client.stop();
	}

	private static void runDoubleClickProbe() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			Screen previous = client.screen;
			LuaIdeScreen screen = new LuaIdeScreen(previous, "test");
			client.setScreen(screen);
			try {
				screen.verifyDoubleClickForTesting();
				McLuaIdeMod.LOGGER.info("MC Lua IDE double-click selection probe passed: Player and 5");
			} catch (Exception | AssertionError error) {
				McLuaIdeMod.LOGGER.error("MC Lua IDE double-click selection probe failed", error);
			} finally {
				client.setScreen(previous);
				if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT"))) client.stop();
			}
		});
	}

	private static void runUiSmoke() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			Screen previous = client.screen;
			LuaIdeScreen screen = new LuaIdeScreen(previous, "test");
			client.setScreen(screen);
			screen.openMinecraftUiEditorForTesting();
			CompletableFuture.delayedExecutor(900, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
				if (client.screen == screen) client.setScreen(previous);
				McLuaIdeMod.LOGGER.info("MC Lua IDE visual UI editor smoke test passed");
			}));
		});
	}

	private static void runCanvasOnlyProbe() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			try {
				LuaMinecraftUiEditorOverlay overlay = LuaMinecraftUiEditorOverlay.verifyCanvasEditor(client);
				Screen previous = client.screen;
				Screen preview = new Screen(net.minecraft.network.chat.Component.literal("UI canvas probe")) {
					@Override protected void init() { overlay.resize(width, height); }
					@Override public void tick() { overlay.tick(); }
					@Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float partial) {
						graphics.fill(0, 0, width, height, 0xFF111820);
						overlay.render(graphics, x, y);
					}
				};
				client.setScreen(preview);
				CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
					net.minecraft.client.Screenshot.grab(client.gameDirectory, "ui-canvas-probe.png",
							client.getMainRenderTarget(), 1, message -> McLuaIdeMod.LOGGER.info("UI canvas screenshot: {}", message.getString()));
					CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
						if (client.screen == preview) client.setScreen(previous);
						if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT"))) client.stop();
					}));
				}));
				McLuaIdeMod.LOGGER.info("MC Lua IDE canvas editor interaction probe passed");
			} catch (Exception error) {
				McLuaIdeMod.LOGGER.error("MC Lua IDE canvas editor interaction probe failed", error);
			}
		});
	}

	private static void runIdEditorProbe() {
		Minecraft client = Minecraft.getInstance();
		ScriptManager.registryTypes().thenCompose(types -> {
			for (String id : List.of("minecraft:item", "minecraft:timeline", "minecraft:mob_effect", "minecraft:recipe", "minecraft:advancement", "minecraft:loot_table")) {
				if (types.stream().noneMatch(type -> type.id().equals(id))) throw new IllegalStateException("目录漏掉 " + id);
			}
			CompletableFuture<?>[] loads = List.of("minecraft:item", "minecraft:timeline", "minecraft:mob_effect", "minecraft:enchantment", "minecraft:recipe", "minecraft:advancement", "minecraft:loot_table")
					.stream().map(id -> ScriptManager.registryCatalog(id).thenAccept(entries -> {
						if (entries.isEmpty()) throw new IllegalStateException("目录为空：" + id);
						if (id.equals("minecraft:item") && entries.stream().noneMatch(entry -> entry.value().startsWith("#"))) throw new IllegalStateException("缺少物品标签");
						McLuaIdeMod.LOGGER.info("ID editor registry probe: {} = {} entries", id, entries.size());
					})).toArray(CompletableFuture[]::new);
			return CompletableFuture.allOf(loads);
		}).thenCompose(ignored -> ScriptManager.componentEditorData("item", "minecraft:bundle"))
				.whenComplete((data, failure) -> client.execute(() -> {
			if (failure != null) { McLuaIdeMod.LOGGER.error("ID editor registry probe failed", failure); return; }
			try {
				JsonTreeEditor editor = JsonTreeEditor.verifyIdEditor(client, data);
				Screen previous = client.screen;
				Screen preview = new Screen(net.minecraft.network.chat.Component.literal("ID editor probe")) {
					@Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float partial) {
						graphics.fill(0, 0, width, height, 0xFF111820);
						editor.render(graphics, x, y, 8, 8, 160, height - 8);
						editor.renderPopupOverlay(graphics, x, y);
					}
				};
				client.setScreen(preview);
				CompletableFuture.delayedExecutor(1800, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
					if (client.screen != preview) return;
					net.minecraft.client.Screenshot.grab(client.gameDirectory, "id-editor-probe.png", client.getMainRenderTarget(), 1,
							message -> McLuaIdeMod.LOGGER.info("ID editor screenshot: {}", message.getString()));
					try {
						editor.verifyRenderedIdPicker();
						McLuaIdeMod.LOGGER.info("MC Lua IDE ID editor interaction probe passed");
					} catch (Exception error) { McLuaIdeMod.LOGGER.error("ID editor interaction probe failed", error); }
					finally { client.setScreen(previous); }
				}));
			} catch (Exception error) { McLuaIdeMod.LOGGER.error("ID editor inference probe failed", error); }
		}));
	}

	private static void runComponentTreeProbe() {
		Minecraft client = Minecraft.getInstance();
		ScriptManager.componentEditorData("item", "minecraft:bundle").whenComplete((data, failure) -> client.execute(() -> {
			if (failure != null) { McLuaIdeMod.LOGGER.error("Component tree catalog probe failed", failure); return; }
			try {
				JsonTreeEditor editor = JsonTreeEditor.verifyComponentForest(client, data);
				ScriptManager.validateComponents("item", "minecraft:bundle", editor.probePatch()).whenComplete((result, error) -> {
					if (error != null) McLuaIdeMod.LOGGER.error("Component tree codec probe failed", error);
					else McLuaIdeMod.LOGGER.info("MC Lua IDE component tree interaction and codec probe passed: {}", result);
				});
				Screen previous = client.screen;
				int oldScale = client.options.guiScale().get();
				client.options.guiScale().set(1); client.resizeGui();
				LuaComponentEditorOverlay overlay = LuaComponentEditorOverlay.previewTreeForTesting(client, data, editor.probePatch());
				Screen preview = new Screen(net.minecraft.network.chat.Component.literal("Component tree probe")) {
					@Override protected void init() { overlay.resize(width, height); }
					@Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float partial) {
						graphics.fill(0, 0, width, height, 0xFF111820);
						overlay.render(graphics, x, y);
					}
				};
				client.setScreen(preview);
				CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
					if (client.screen != preview) return;
					net.minecraft.client.Screenshot.grab(client.gameDirectory, "component-tree-probe.png", client.getMainRenderTarget(), 1,
							message -> McLuaIdeMod.LOGGER.info("Component tree screenshot: {}", message.getString()));
					try {
						overlay.verifyTreeWindowForTesting();
						McLuaIdeMod.LOGGER.info("Component tree real window mouse and keyboard probe passed");
					} catch (Exception error) { McLuaIdeMod.LOGGER.error("Component tree real window probe failed", error); }
					finally {
						client.options.guiScale().set(oldScale); client.resizeGui(); client.setScreen(previous);
						if ("true".equalsIgnoreCase(System.getenv("MC_LUA_IDE_PROBE_EXIT"))) client.stop();
					}
				}));
			} catch (Exception error) { McLuaIdeMod.LOGGER.error("Component tree interaction probe failed", error); }
		}));
	}

	private static void assertComponentHint(LuaComponentEditorData catalog, String componentId,
			String path, String registry) {
		LuaComponentEditorData.ComponentInfo component = catalog.components().stream()
				.filter(candidate -> candidate.id().equals(componentId))
				.findFirst().orElseThrow(() -> new IllegalStateException("组件目录缺少 " + componentId));
		if (component.idHints().stream().noneMatch(hint -> hint.path().equals(path)
				&& hint.registry().equals(registry))) {
			throw new IllegalStateException(componentId + " 缺少 ID 搜索提示 " + path + " -> " + registry);
		}
	}

	private static void verifyResourceAssetCatalog() {
		Minecraft minecraft = Minecraft.getInstance();
		assertLocalAsset(minecraft, LuaResourceAssetCatalog.ITEM_MODEL, "minecraft:stone");
		assertLocalAsset(minecraft, LuaResourceAssetCatalog.EQUIPMENT, "minecraft:iron");
		assertLocalAsset(minecraft, LuaResourceAssetCatalog.TEXTURE, "minecraft:misc/pumpkinblur");
		assertLocalAsset(minecraft, LuaResourceAssetCatalog.FONT, "minecraft:default");
		List<LuaInsertCatalog.Entry> modifiers = LuaResourceAssetCatalog.load(
				LuaResourceAssetCatalog.ATTRIBUTE_MODIFIER, minecraft);
		if (modifiers == null || modifiers.isEmpty()) {
			throw new IllegalStateException("客户端资源目录没有收集到属性修饰符 ID");
		}
		if (LuaResourceAssetCatalog.types(minecraft).stream().noneMatch(type ->
				type.id().equals(LuaResourceAssetCatalog.TOOLTIP_STYLE))) {
			throw new IllegalStateException("客户端资源目录缺少提示框样式类型");
		}
	}

	private static void assertLocalAsset(Minecraft minecraft, String catalogId, String expected) {
		List<LuaInsertCatalog.Entry> entries = LuaResourceAssetCatalog.load(catalogId, minecraft);
		if (entries == null || entries.stream().noneMatch(entry -> entry.value().equals(expected))) {
			throw new IllegalStateException(catalogId + " 缺少客户端资源 " + expected);
		}
	}

	private static void verifyCursorContext() {
		assertComponentTarget(
				"MC.Item.give(uuid, \"minecraft:diamond_sword\", 1, {[\"minecraft:unbreakable\"] = {}})",
				"minecraft:unbreakable", LuaInsertCatalog.Kind.ITEM, "minecraft:diamond_sword");
		assertComponentTarget(
				"MC.Block.set(0, 64, 0, \"minecraft:chest\", nil, {[\"minecraft:custom_name\"] = {text = \"箱子\"}})",
				"minecraft:custom_name", LuaInsertCatalog.Kind.BLOCK, "minecraft:chest");
		assertComponentTarget(
				"MC.Entity.setEquipment(uuid, \"mainhand\", \"minecraft:iron_sword\", 1, {})",
				"{}", LuaInsertCatalog.Kind.ITEM, "minecraft:iron_sword");

		String partial = "MC.Entity.spawn(\"minecraft:zom\")";
		LuaCursorContext.Argument completion = LuaCursorContext.argumentAt(partial,
				partial.indexOf("minecraft:zom") + "minecraft:zom".length());
		if (completion == null || completion.kind() != LuaInsertCatalog.Kind.ENTITY
				|| !completion.query().equals("minecraft:zom")) {
			throw new IllegalStateException("Ctrl+I 没有识别当前实体 ID：" + completion);
		}
		assertInsertKind("MC.Effect.give(uuid, \"minecraft:speed\", 30)", "minecraft:speed", LuaInsertCatalog.Kind.EFFECT);
		assertInsertKind("MC.Sound.play(\"minecraft:block.note_block.pling\", 0, 64, 0)", "minecraft:block.note_block.pling", LuaInsertCatalog.Kind.SOUND);
		assertInsertKind("MC.Particle.spawn(\"minecraft:flame\", 0, 64, 0)", "minecraft:flame", LuaInsertCatalog.Kind.PARTICLE);
		assertInsertKind("MC.Structure.place(\"minecraft:test\", 0, 64, 0)", "minecraft:test", LuaInsertCatalog.Kind.STRUCTURE);
		assertInsertKind("MC.Loot.give(uuid, \"minecraft:chests/simple_dungeon\")", "minecraft:chests/simple_dungeon", LuaInsertCatalog.Kind.LOOT_TABLE);
		assertInsertKind("MC.Item.give(uuid, \"minecraft:book\", 1, {[\"minecraft:enchantments\"] = {[\"minecraft:sharpness\"] = 1}})",
				"minecraft:sharpness", LuaInsertCatalog.Kind.ENCHANTMENT);
		assertTextJsonTarget("MC.Chat.message(uuid, {text = \"hello\"})", "hello", "MC.Chat.message", 1);
		assertTextJsonTarget("MC.Score.create(\"score\", \"dummy\", \"\")", "\"\")", "MC.Score.create", 2);
		assertTextJsonTarget("MC.Client.toast(uuid, \"title\", \"body\")", "body", "MC.Client.toast", 2);
		if (LuaCursorContext.textJsonArgumentAt("MC.Item.give(uuid, \"diamond\", 1)", 24) != null)
			throw new IllegalStateException("Ctrl+I 把物品参数误判为 JSON 文本");
	}

	private static void assertInsertKind(String source, String marker, LuaInsertCatalog.Kind expected) {
		int cursor = source.indexOf(marker) + marker.length();
		LuaCursorContext.Argument context = LuaCursorContext.argumentAt(source, cursor);
		if (context == null || context.kind() != expected || !context.query().equals(marker))
			throw new IllegalStateException("Ctrl+I 分类错误：" + expected + " / " + context);
	}

	private static void assertTextJsonTarget(String source, String cursorMarker,
			String expectedSymbol, int expectedIndex) {
		int cursor = source.indexOf(cursorMarker);
		if (cursor < 0) throw new IllegalStateException("探针代码缺少光标标记：" + cursorMarker);
		LuaCursorContext.TextJsonArgument context = LuaCursorContext.textJsonArgumentAt(source, cursor);
		if (context == null || !context.symbol().equals(expectedSymbol) || context.index() != expectedIndex
				|| context.replacementStart() > cursor || context.replacementEnd() <= cursor)
			throw new IllegalStateException("JSON 文本快捷键目标识别错误：" + context);
	}

	private static void assertComponentTarget(String source, String cursorMarker,
			LuaInsertCatalog.Kind expectedKind, String expectedId) {
		int cursor = source.indexOf(cursorMarker);
		if (cursor < 0) throw new IllegalStateException("探针代码缺少光标标记：" + cursorMarker);
		LuaCursorContext.ComponentArgument context = LuaCursorContext.itemComponentsArgumentAt(source, cursor);
		if (context == null || context.targetKind() != expectedKind || !context.targetId().equals(expectedId)) {
			throw new IllegalStateException("组件快捷键目标识别错误：" + context);
		}
	}

	private static void verifyCodeAnalyzer() {
		String spawnedEntity = "local entityUuid = MC.Entity.spawn('minecraft:pig', 0, 64, 0)\n"
				+ "if entityUuid then\nMC.Entity.setName(entityUuid, '测试实体')\n"
				+ "MC.Entity.get(entityUuid)\nMC.Entity.remove(entityUuid)\nend";
		if (!LuaCodeAnalyzer.analyze("spawn_uuid", spawnedEntity).valid())
			throw new IllegalStateException("spawn 返回的实体 UUID 被误报为非字符串");
		if (!LuaApiDocumentation.find("MC.Entity.spawn").returns().contains("UUID 字符串"))
			throw new IllegalStateException("spawn 文档未声明 UUID 字符串返回值");
		String riding = "local passenger = MC.Entity.spawn('minecraft:pig', 0, 64, 0)\n"
				+ "local vehicle = MC.Entity.spawn('minecraft:cow', 0, 64, 0)\n"
				+ "if passenger and vehicle then\nMC.Entity.ride(passenger, vehicle)\n"
				+ "MC.Entity.ride(passenger, vehicle, false)\nMC.Entity.dismount(passenger)\nend";
		if (!LuaCodeAnalyzer.analyze("entity_ride", riding).valid())
			throw new IllegalStateException("正确的骑乘参数被误报");
		String queriedVehicle = "local uuid = MC.Entity.spawn('minecraft:pig', 0, 64, 0)\n"
				+ "local vehicle = MC.Entity.get(uuid).vehicleUuid\n"
				+ "if vehicle then MC.Entity.ride(uuid, vehicle) end\n"
				+ "local info = MC.Entity.get(uuid)\nlocal otherVehicle = info.vehicleUuid\n"
				+ "if otherVehicle then MC.Entity.get(otherVehicle) end";
		if (!LuaCodeAnalyzer.analyze("queried_vehicle", queriedVehicle).valid())
			throw new IllegalStateException("查询返回的坐骑 UUID 被误报为非字符串");
		String entityUuid = "123e4567-e89b-12d3-a456-426614174000";
		assertDiagnostic("MC.Entity.ride('" + entityUuid + "', 'minecraft:pig')", "UUID 字符串", "'minecraft:pig'");
		assertDiagnostic("MC.Entity.ride('" + entityUuid + "', true)", "类型错误", "true");
		assertDiagnostic("MC.Entity.ride('" + entityUuid + "', '" + entityUuid + "', 1)", "类型错误", "1");
		assertDiagnostic("MC.Entity.ride('" + entityUuid + "')", "参数不足", ")");
		assertDiagnostic("MC.Entity.dismount('minecraft:pig')", "UUID 字符串", "'minecraft:pig'");
		for (String symbol : List.of("MC.Entity.ride", "MC.Entity.dismount")) {
			if (!LuaCodeAnalyzer.analyze("ride_template", LuaApiDocumentation.usageTemplate(symbol)).valid())
				throw new IllegalStateException("骑乘 API 插入模板被误报：" + symbol);
		}
		String valid = """
				local uuid = "123e4567-e89b-12d3-a456-426614174000"
				MC.Player.teleport(uuid, 1, 64, 2, "minecraft:overworld")
				MC.UI.register("probe", {type = "notice", title = "测试"})
				MC.Client.toast(uuid, {text = "标题", color = "gold"}, "正文")
				MC.Chat.message(uuid, {text = "彩色消息", color = "#55FF55", underlined = true})
				MC.Title.show(uuid, {text = "标题", color = "#55FFFF", bold = true})
				MC.Event.onBlockPlaced(function(event)
				    print(event.blockId, event.x)
				end)
				""";
		if (!LuaCodeAnalyzer.analyze("probe", valid).valid()) {
			throw new IllegalStateException("正确脚本被静态分析器误报");
		}
		String diamondWorld = """
				local DIMENSION_ID = "mclua_demo:diamond_world"
				local dimensionJson = [[{"type":"minecraft:overworld"}]]
				MC.Pack.putData("dimension", DIMENSION_ID, dimensionJson)
				local playerUuid = ""
				local players = MC.Player.list()
				playerUuid = players[1].uuid
				MC.Player.teleport(playerUuid, 0, 67, 0, DIMENSION_ID)
				""";
		if (!LuaCodeAnalyzer.analyze("diamond_world", diamondWorld).valid()) {
			throw new IllegalStateException("钻石世界示例被静态分析器误报："
					+ LuaCodeAnalyzer.analyze("diamond_world", diamondWorld).diagnostics());
		}
		String validPackAndRecipes = """
				MC.Pack.putAssetJson("models/item", "test:test", [[{"parent":"minecraft:block/tnt"}]])
				MC.Pack.putDataBatch({{kind="tags/item", id="test:sample", json=[[{"values":["minecraft:stone"]}]]}})
				MC.Recipe.fromGrid({"stone", "minecraft:stone"})
				MC.Block.set(1, 64, 1, "stone")
				local suffix = "stone"
				local itemId = "minecraft:" .. suffix
				MC.Block.set(1, 64, 1, itemId)
				local reassignedId = "Bad ID"
				reassignedId = "minecraft:" .. suffix
				MC.Block.set(1, 64, 1, reassignedId)
				MC.Block.set(1, 64, 1, "minecraft:" .. suffix)
				""";
		if (!LuaCodeAnalyzer.analyze("probe", validPackAndRecipes).valid())
			throw new IllegalStateException("有效的资源包、配方或省略命名空间写法被误报："
					+ LuaCodeAnalyzer.analyze("probe", validPackAndRecipes).diagnostics());

		assertDiagnostic("MC.Player.teleport(\"123e4567-e89b-12d3-a456-426614174000\", 1)", "参数不足", ")");
		assertDiagnostic("MC.Block.set(1, 2, 3, true)", "类型错误", "true");
		assertDiagnostic("MC.Player.setGameMode(\"123e4567-e89b-12d3-a456-426614174000\", \"builder\")",
				"填写错误", "\"builder\"");
		assertDiagnostic("MC.Block.set(1, 2, 3, \"Bad ID\")", "资源 ID 格式无效", "\"Bad ID\"");
		assertDiagnostic("MC.Network.get(\"example.com\")", "http://", "\"example.com\"");
		assertDiagnostic("MC.Recipe.fromGrid(\"stone\")", "类型错误", "\"stone\"");
		assertDiagnostic("MC.Pack.putDataBatch(\"not-a-table\")", "类型错误", "\"not-a-table\"");
		assertDiagnostic("MC.Registry.list(\"dimension\")", "可用值为", "\"dimension\"");
		assertDiagnostic("MC.UI.register(\"probe\", \"bad\")", "类型错误", "\"bad\"");
		assertDiagnostic("MC.Player.Teleport()", "大小写", "MC.Player.Teleport");
		assertDiagnostic("MC.Player.notARealMethod()", "未知的 API", "MC.Player.notARealMethod");
		assertDiagnostic("print(missingValue)", "未定义变量", "missingValue");
		assertDiagnostic("print(declaredLater)\nlocal declaredLater = 1", "未定义变量", "declaredLater");
		String scoped = "if true then\n  local hidden = 1\nend\nprint(hidden)";
		assertDiagnostic(scoped, "未定义变量", "hidden");
		String validScopes = "local config = {title = \"ok\"}\nlocal function show(value) print(value, config.title) end\nshow(1)";
		if (!LuaCodeAnalyzer.analyze("probe", validScopes).valid()) {
			throw new IllegalStateException("局部变量、函数参数或表字段被错误标记为未定义");
		}

		String repeated = "MC.Chat.broadcast(true)\nMC.Chat.broadcast(true)";
		LuaCodeAnalyzer.Analysis repeatedAnalysis = LuaCodeAnalyzer.analyze("probe", repeated);
		if (repeatedAnalysis.diagnostics().size() != 2
				|| repeatedAnalysis.diagnostics().get(0).start() == repeatedAnalysis.diagnostics().get(1).start()) {
			throw new IllegalStateException("重复字段的错误区间没有分别定位");
		}
		LuaCodeAnalyzer.Analysis syntax = LuaCodeAnalyzer.analyze("probe", "if true then\nprint(\"x\")\n");
		if (syntax.valid() || !syntax.first().message().contains("缺少 end")) {
			throw new IllegalStateException("Lua 语法错误没有得到中文解释：" + syntax.diagnostics());
		}
		LuaCodeAnalyzer.Diagnostic syntaxDiagnostic = syntax.first();
		String syntaxRange = "if true then\nprint(\"x\")\n".substring(
				syntaxDiagnostic.start(), syntaxDiagnostic.end());
		if (!syntaxRange.equals("if")) {
			throw new IllegalStateException("未闭合代码块没有标记对应的 if：" + syntaxRange);
		}
		assertStableTrailingNewlineDiagnostic("local value =");
		assertStableTrailingNewlineDiagnostic("if true");
		assertStableTrailingNewlineDiagnostic("for index = 1, 3");
		if (!LuaCodeAnalyzer.analyze("probe", "-- MC.Block.set(true)\nprint(\"MC.Player.teleport()\")").valid()) {
			throw new IllegalStateException("注释或字符串中的 API 被错误分析");
		}
	}

	private static void assertStableTrailingNewlineDiagnostic(String source) {
		LuaCodeAnalyzer.Diagnostic baseline = LuaCodeAnalyzer.analyze("probe", source).first();
		if (baseline == null) throw new IllegalStateException("预期脚本应有语法错误：" + source);
		for (int extraLines = 1; extraLines <= 3; extraLines++) {
			String expanded = source + "\n".repeat(extraLines);
			LuaCodeAnalyzer.Diagnostic diagnostic = LuaCodeAnalyzer.analyze("probe", expanded).first();
			if (diagnostic == null || diagnostic.line() != baseline.line()
					|| diagnostic.start() >= expanded.length()
					|| Character.isWhitespace(expanded.charAt(diagnostic.start()))) {
				throw new IllegalStateException("尾部回车导致错误定位漂移：" + diagnostic);
			}
		}
	}

	private static void assertDiagnostic(String source, String messagePart, String markedText) {
		LuaCodeAnalyzer.Analysis analysis = LuaCodeAnalyzer.analyze("probe", source);
		if (analysis.valid() || !analysis.first().message().contains(messagePart)) {
			throw new IllegalStateException("没有得到预期诊断“" + messagePart + "”：" + analysis.diagnostics());
		}
		LuaCodeAnalyzer.Diagnostic diagnostic = analysis.first();
		String actual = source.substring(diagnostic.start(), diagnostic.end());
		if (!actual.equals(markedText)) {
			throw new IllegalStateException("错误区间不正确：预期“" + markedText + "”，实际“" + actual + "”");
		}
	}

	private static CompletableFuture<ScriptManager.ExecutionResult> execute(
			String name, String source, List<String> output, boolean stop) {
		CompletableFuture<ScriptManager.ExecutionResult> completion = new CompletableFuture<>();
		ScriptManager.ScriptExecution execution = ScriptManager.start(name, source, output::add, completion::complete);
		if (stop) {
			CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() ->
					Minecraft.getInstance().execute(execution::stop));
		}
		return completion.orTimeout(12, TimeUnit.SECONDS);
	}
}
