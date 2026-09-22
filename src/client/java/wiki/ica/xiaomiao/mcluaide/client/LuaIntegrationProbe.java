package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in end-to-end protocol probe used by development launches only. */
final class LuaIntegrationProbe {
	private static boolean started;

	private LuaIntegrationProbe() {
	}

	static void runIfEnabled() {
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
					return ScriptManager.validateComponents("entity", catalog.requestedId(),
							"{\"minecraft:custom_name\":{\"text\":\"组件探针实体\"}}");
				})
				.thenCompose(canonical -> {
					if (!canonical.contains("minecraft:custom_name")) {
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
							&& line.contains("13") && line.contains("file-smoke") && line.contains("true"))) {
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
					}
				});
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

		assertDiagnostic("MC.Player.teleport(\"123e4567-e89b-12d3-a456-426614174000\", 1)", "参数不足", ")");
		assertDiagnostic("MC.Block.set(1, 2, 3, true)", "类型错误", "true");
		assertDiagnostic("MC.Player.setGameMode(\"123e4567-e89b-12d3-a456-426614174000\", \"builder\")",
				"填写错误", "\"builder\"");
		assertDiagnostic("MC.Block.set(1, 2, 3, \"stone\")", "namespace:path", "\"stone\"");
		assertDiagnostic("MC.Network.get(\"example.com\")", "http://", "\"example.com\"");
		assertDiagnostic("MC.UI.register(\"probe\", \"bad\")", "类型错误", "\"bad\"");
		assertDiagnostic("MC.Player.Teleport()", "大小写", "MC.Player.Teleport");
		assertDiagnostic("MC.Player.notARealMethod()", "未知的 API", "MC.Player.notARealMethod");

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
