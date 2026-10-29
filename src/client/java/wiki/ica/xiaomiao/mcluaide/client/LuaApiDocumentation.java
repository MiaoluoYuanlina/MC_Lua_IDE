package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Short IDE metadata for completion labels and hover documentation. */
final class LuaApiDocumentation {
	private static final Map<String, Entry> BUILTIN_ENTRIES = Collections.unmodifiableMap(createEntries());
	private static volatile Map<String, Entry> entries = BUILTIN_ENTRIES;
	private static volatile Set<String> apiRoots = collectApiRoots(BUILTIN_ENTRIES.keySet());
	private static volatile List<DocumentPage> pages = List.of();
	private static volatile int revision;
	private static List<McLuaIdeApi.Descriptor> externalDescriptors = List.of();
	private static List<McLuaIdeDocsApi.Page> externalPages = List.of();
	private static String documentationLanguage = LuaUiText.language();

	private static void ensureLanguage() {
		if (!documentationLanguage.equals(LuaUiText.language())) replaceExternal(externalDescriptors, externalPages);
	}

	private LuaApiDocumentation() {
	}

	static List<String> symbols() {
		ensureLanguage();
		return List.copyOf(entries.keySet());
	}

	static Entry find(String symbol) {
		ensureLanguage();
		Map<String, Entry> snapshot = entries;
		Entry direct = snapshot.get(symbol);
		if (direct != null || symbol == null) return direct;
		return snapshot.entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(symbol))
				.map(Map.Entry::getValue)
				.findFirst().orElse(null);
	}

	static boolean isApiRoot(String value) {
		return value != null && apiRoots.contains(value.toLowerCase(Locale.ROOT));
	}

	static List<DocumentEntry> allEntries() {
		ensureLanguage();
		return entries.entrySet().stream()
				.map(entry -> new DocumentEntry(entry.getKey(), entry.getValue()))
				.toList();
	}

	static List<DocumentPage> allPages() {
		ensureLanguage();
		return pages;
	}

	static int revision() { ensureLanguage(); return revision; }

	static String usageTemplate(String symbol) {
		Entry entry = find(symbol);
		if (entry == null) return "";
		if (symbol.equals("MC.Event.on") || symbol.startsWith("MC.Event.on")
				&& !symbol.equals("MC.Event.names")) {
			return eventUsageTemplate(symbol, entry);
		}
		return switch (symbol) {
			case "MC.Sound.play" -> """
					-- 全服广播，不分坐标；从头播放无需普通玩家安装本模组
					MC.Sound.play("minecraft:block.note_block.pling", nil, nil, nil)
					-- 只向主世界全部玩家广播，不受距离限制
					MC.Sound.play("minecraft:block.note_block.pling", nil, nil, nil,
					    1, 1, "master", "minecraft:overworld")
					-- 非零起播位置需要开启“Mod Menu → MC Lua IDE → 配置 → 服务器接入 → 要求玩家安装客户端模组”
					local player = MC.Player.list()[1]
					if player and MC.Client.available(player.uuid) then
					    local p = MC.Player.position(player.uuid)
					    MC.Sound.play("minecraft:music_disc.cat", p.x, p.y, p.z,
					        1, 1, "record", p.dimension, player.uuid, 30.5)
					end
					""";
			case "MC.Client.toast" -> """
					-- 需要先在“Mod Menu → MC Lua IDE → 配置 → 服务器接入”开启强制客户端模式
					local player = MC.Player.list()[1]
					if player and MC.Client.available(player.uuid) then
					    MC.Client.toast(player.uuid, "任务完成", "客户端已收到服务端脚本通知")
					end
					""";
			case "MC.Client.notice" -> """
					local uuid = MC.Player.list()[1].uuid
					local info = MC.Client.info(uuid)
					if info.required and info.available then
					    MC.Client.notice(uuid, "服务器公告", "这是仅安装本 Mod 的客户端可见页面。")
					end
					""";
			case "MC.Client.openUrlPrompt" -> """
					-- 只显示确认框；玩家必须手动确认才会打开链接
					local uuid = MC.Player.list()[1].uuid
					MC.Client.openUrlPrompt(uuid, "https://example.com/help")
					""";
			case "MC.UI.register", "MC.UI.show" -> """
					-- 原版 26.1+ 服务端对话框；普通玩家无需安装本模组
					local definition = {
					    type = "form",
					    title = "服务器传送菜单",
					    body = {"填写目标并选择模式："},
					    canCloseWithEscape = true,
					    afterAction = "close",
					    columns = 2,
					    inputs = {
					        {key = "target", type = "text", label = "目标", initial = "主城", maxLength = 64},
					        {key = "safe", type = "boolean", label = "安全传送", initial = true},
					        {key = "distance", type = "number", label = "距离", min = 1, max = 100, initial = 10, step = 1},
					        {key = "mode", type = "select", label = "方式", options = {
					            {id = "walk", text = "步行", initial = true},
					            {id = "teleport", text = "传送"}
					        }}
					    },
					    buttons = {
					        {id = "submit", label = "提交", tooltip = "把输入发送给服务端脚本"},
					        {id = "cancel", label = "取消"}
					    }
					}
					MC.UI.register("teleport_menu", definition)
					MC.Event.onUiAction(function(event)
					    if event.uiId == "teleport_menu" and event.actionId == "submit" then
					        print(event.playerUuid, event.inputs.target, event.inputs.mode,
					            event.inputs.safe, event.inputs.distance)
					    end
					end)
					MC.UI.open("玩家UUID", "teleport_menu")
					""";
			case "MC.Block.get" -> """
					-- 读取方块，并安全访问返回字段
					local block, reason = MC.Block.get(0, 64, 0, "minecraft:overworld")
					if block then
					    print(block.id, block.name, block.air)
					    print(block.x, block.y, block.z, block.dimension)
					    print(block.blockEntityDataJson) -- 箱子内容／告示牌文字等真实方块实体数据
					else
					    print("本次读取跳过：", reason)
					end
					""";
			case "MC.Entity.components" -> """
					-- 玩家也属于实体；可把 player.uuid 换成其他已加载实体的 UUID。
					local player = MC.Player.list()[1]
					if player then
					    local data, reason = MC.Entity.components(player.uuid)
					    if data then
					        print(data.componentsJson, data.entityDataJson)
					    else
					        print(reason)
					    end
					end
					""";
			case "MC.Entity.equipment", "MC.Item.equipment", "MC.Item.getSlot" -> """
					local player = MC.Player.list()[1]
					if player then
					    local item = %s(player.uuid, %s)
					    if item then
					        print(item.id, item.count, item.empty, item.componentsJson)
					    end
					end
					""".formatted(symbol, symbol.equals("MC.Item.getSlot") ? "0" : "\"offhand\"");
			case "MC.Item.held" -> """
					-- UUID 必须属于在线玩家
					local item = MC.Item.held("玩家UUID")
					if not item.empty then
					    print(item.id, item.name, item.count, item.componentsJson)
					end
					""";
			case "MC.Item.give" -> """
					-- components 是数据组件补丁；完整命名空间也支持其他 Mod 注册的组件
					local components = {
					    ["minecraft:custom_name"] = {text = "管理员之剑", color = "gold"},
					    ["minecraft:unbreakable"] = {}
					}
					MC.Item.give("玩家UUID", "minecraft:diamond_sword", 1, components)
					""";
			case "MC.Item.inventory" -> """
					local uuid = MC.Player.list()[1].uuid
					for _, stack in ipairs(MC.Item.inventory(uuid)) do
					    if not stack.empty then
					        print(stack.slot, stack.id, stack.count)
					    end
					end
					""";
			case "MC.Player.list" -> """
					-- 遍历服务端全部在线玩家
					for _, player in ipairs(MC.Player.list()) do
					    print(player.uuid, player.name)
					end
					""";
			case "MC.Player.position" -> """
					local position = MC.Player.position("玩家UUID")
					print(position.x, position.y, position.z)
					print(position.yaw, position.pitch, position.dimension)
					""";
			case "MC.Player.facing" -> """
					local facing = MC.Player.facing("玩家UUID")
					print(facing.yaw, facing.pitch, facing.direction)
					""";
			case "MC.Player.relativePosition" -> """
					-- 玩家正前方 5 格（只按水平朝向），例如放置方块
					local target = MC.Player.relativePosition("玩家UUID", "front", "horizontal", 5)
					MC.Block.set(target.blockX, target.blockY, target.blockZ, "minecraft:stone")
					""";
			case "MC.Player.lookDirection" -> """
					local look = MC.Player.lookDirection("玩家UUID")
					print(look.x, look.y, look.z)
					""";
			case "MC.Player.lookingAt" -> """
					-- 准星指向的精确坐标；第二个参数 true 表示不限距离
					local hit = MC.Player.lookingAt("玩家UUID", false)
					if hit then print(hit.x, hit.y, hit.z, hit.blockId, hit.face) end
					""";
			case "MC.Player.health" -> """
					local health = MC.Player.health("玩家UUID")
					print("生命值：", health.current, "/", health.max)
					""";
			case "MC.Player.openCrafting" -> """
					local players = MC.Player.list()
					if #players == 0 then error("当前没有在线玩家") end
					local containerId = MC.Player.openCrafting(players[1].uuid, "脚本工作台")
					print("已打开工作台，容器 ID：", containerId)
					""";
			case "MC.Player.openEnderChest" -> """
					local uuid = MC.Player.list()[1].uuid
					MC.Player.openEnderChest(uuid, "个人末影箱")
					""";
			case "MC.Player.openBlock" -> """
					local player = MC.Player.list()[1]
					local position = MC.Player.position(player.uuid)
					MC.Player.openBlock(player.uuid, math.floor(position.x),
					    math.floor(position.y) - 1, math.floor(position.z), position.dimension)
					""";
			case "MC.Container.open" -> """
					local player = MC.Player.list()[1]
					local position = MC.Player.position(player.uuid)
					MC.Container.open(player.uuid, math.floor(position.x),
					    math.floor(position.y) - 1, math.floor(position.z), position.dimension)
					""";
			case "MC.Container.list" -> """
					for _, stack in ipairs(MC.Container.list(10, 64, 10)) do
					    if not stack.empty then
					        print(stack.slot, stack.id, stack.count)
					    end
					end
					""";
			case "MC.World.dimensions" -> """
					for _, dimension in ipairs(MC.World.dimensions()) do
					    print("已加载维度：", dimension)
					end
					""";
			case "MC.World.weather" -> """
					local weather = MC.World.weather("minecraft:overworld")
					print(weather.name, weather.raining, weather.thundering)
					""";
			case "MC.Mod.list" -> """
					for _, mod in ipairs(MC.Mod.list()) do
					    print(mod.id, mod.name, mod.version)
					end
					""";
			case "MC.Event.off" -> """
					local registrationId = MC.Event.onBlockPlaced(function(event)
					    print(event.blockId)
					end)
					-- 在需要时只注销这一项监听
					local removed = MC.Event.off(registrationId)
					print("是否注销：", removed)
					""";
			case "MC.Event.clear" -> """
					-- 一次移除当前脚本注册的全部事件
					local removedCount = MC.Event.clear()
					print("已移除：", removedCount)
					""";
			default -> "-- " + entry.summary() + "\n" + entry.example() + "\n";
		};
	}

	static synchronized void replaceExternal(List<McLuaIdeApi.Descriptor> descriptors) {
		replaceExternal(descriptors, List.of());
	}

	static synchronized void replaceExternal(List<McLuaIdeApi.Descriptor> descriptors,
			List<McLuaIdeDocsApi.Page> documentationPages) {
		externalDescriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
		externalPages = documentationPages == null ? List.of() : List.copyOf(documentationPages);
		documentationLanguage = LuaUiText.language();
		Map<String, Entry> merged = new LinkedHashMap<>(BUILTIN_ENTRIES);
		String locale = documentationLanguage;
		for (McLuaIdeApi.Descriptor descriptor : descriptors == null ? List.<McLuaIdeApi.Descriptor>of() : descriptors) {
			McLuaIdeApi.Translation translation = descriptor.translation(locale);
			Map<String, String> parameterDescriptions = translation.parameterDescriptions();
			List<McLuaIdeApi.ValueType> parameterTypes = descriptor.parameters().stream()
					.map(McLuaIdeApi.Parameter::type).toList();
			merged.putIfAbsent(descriptor.symbol(), new Entry(
					descriptor.signature(), translation.summary(), translation.returns(), translation.usage(), false,
					translation.details(), parameterDescriptions, parameterTypes, descriptor.ownerModId(),
					translation.namespaceName(), descriptor.clientRequired()));
		}
		entries = Collections.unmodifiableMap(merged);
		apiRoots = collectApiRoots(merged.keySet());
		List<DocumentPage> translatedPages = new ArrayList<>();
		for (McLuaIdeDocsApi.Page page : documentationPages == null
				? List.<McLuaIdeDocsApi.Page>of() : documentationPages) {
			translatedPages.add(new DocumentPage(page.ownerModId(), page.categoryId(), page.pageId(),
					page.translation(locale)));
		}
		pages = List.copyOf(translatedPages);
		revision++;
	}

	private static Set<String> collectApiRoots(Iterable<String> symbols) {
		Set<String> roots = new LinkedHashSet<>();
		for (String symbol : symbols) {
			int dot = symbol.indexOf('.');
			if (dot > 0) roots.add(symbol.substring(0, dot).toLowerCase(Locale.ROOT));
		}
		return Set.copyOf(roots);
	}

	private static String eventUsageTemplate(String symbol, Entry entry) {
		if (symbol.equals("MC.Event.onPlayerRespawned")) {
			return """
					-- 监听玩家复活（死亡后重生）；停止脚本自动注销。
					-- alive 是重生前是否存活，不是重生后的生命状态。
					local registrationId = MC.Event.onPlayerRespawned(function(event)
					    if not event.alive then
					        print("玩家已复活：", event.playerName, event.playerUuid)
					        print("重生位置：", event.dimension, event.x, event.y, event.z)
					    end
					end)
					print("复活监听注册 ID：", registrationId)
					""";
		}
		if (symbol.equals("MC.Event.onScriptStarted")) {
			return """
					-- 注册后在下一次调度时执行一次；不会单独让脚本保持运行
					MC.Event.onScriptStarted(function(event)
					    print("脚本已启动：", event.scriptName)
					end)
					""";
		}
		if (symbol.equals("MC.Event.onScriptStopped")) {
			return """
					-- 正常结束、手动停止、报错均会执行；清理回调不能等待或注册新事件
					MC.Event.onScriptStopped(function(event)
					    print("脚本已结束：", event.scriptName, "原因：", event.reason)
					    if event.error then print(event.error) end
					end)
					""";
		}
		if (symbol.equals("MC.Event.onInterval")) {
			return """
					-- 每 1000 毫秒执行一次；第一次执行也等待 1000 毫秒。
					-- 回调过慢时跳过错过的次数，同一个监听不会重叠执行。
					local registrationId = MC.Event.onInterval(1000, function(event)
					    print("第", event.count, "次；间隔", event.intervalMs, "ms")
					    if event.count >= 5 then
					        MC.Event.off(event.registrationId)
					    end
					end)
					print("定时事件注册 ID：", registrationId)
					""";
		}
		if (symbol.equals("MC.Event.on")) {
			return """
					-- 通用注册：名称可由 MC.Event.names() 获取
					local registrationId = MC.Event.on("blockBroken", function(event)
					    print(event.playerName, event.blockId, event.x, event.y, event.z)
					end)
					print("事件注册 ID：", registrationId)
					""";
		}
		String preview = switch (symbol) {
			case "MC.Event.onBlockPlaced", "MC.Event.onBlockBroken", "MC.Event.onBlockAttacked",
					"MC.Event.onBlockUsed" -> "print(event.playerName, event.blockId, event.x, event.y, event.z)";
			case "MC.Event.onItemUsed" -> "print(event.playerName, event.itemId, event.count, event.hand)";
			case "MC.Event.onEntityAttacked", "MC.Event.onEntityUsed" ->
					"print(event.playerName, event.targetType, event.targetUuid)";
			case "MC.Event.onEntityLoaded", "MC.Event.onEntityUnloaded",
					"MC.Event.onEntityChangedDimension" ->
					"print(event.entityType, event.entityUuid, event.dimension)";
			case "MC.Event.onEntityDamaged" ->
					"print(event.entityName, event.damageType, event.damageTaken, event.remainingHealth)";
			case "MC.Event.onEntityDied" -> "print(event.entityName, event.entityType, event.damageType)";
			case "MC.Event.onPlayerDied" -> "print(event.playerName, event.x, event.y, event.z, event.dimension, event.deathMessage)";
			case "MC.Event.onEntityKilledOther" ->
					"print(event.killerName, \"击杀\", event.killedName, event.damageType)";
			case "MC.Event.onProjectileHitBlock" ->
					"print(event.projectileType, event.blockId, event.hitX, event.hitY, event.hitZ, event.face)";
			case "MC.Event.onProjectileHitEntity" ->
					"print(event.projectileType, event.targetName, event.playerName)";
			case "MC.Event.onEquipmentChanged" ->
					"print(event.entityName, event.slot, event.oldItemId, event.newItemId)";
			case "MC.Event.onPlayerJoined", "MC.Event.onPlayerLeft", "MC.Event.onPlayerRespawned" ->
					"print(event.playerName, event.playerUuid, event.dimension)";
			case "MC.Event.onPlayerChangedDimension" ->
					"print(event.playerName, event.fromDimension, event.toDimension)";
			case "MC.Event.onEffectAdded", "MC.Event.onEffectRemoved" ->
					"print(event.entityName, event.effectId, event.duration, event.amplifier)";
			case "MC.Event.onSleepStarted", "MC.Event.onSleepStopped" ->
					"print(event.entityName, event.bedX, event.bedY, event.bedZ)";
			case "MC.Event.onChunkLoaded", "MC.Event.onChunkGenerated", "MC.Event.onChunkUnloaded" ->
					"print(event.dimension, event.chunkX, event.chunkZ)";
			case "MC.Event.onLevelLoaded", "MC.Event.onLevelUnloaded" ->
					"print(event.dimension, event.gameTime, event.dayTime)";
			case "MC.Event.onServerTick" -> "print(event.tick, event.playerCount)";
			case "MC.Event.onLevelTick" -> "print(event.tick, event.dimension, event.dayTime)";
			case "MC.Event.onChat" -> "print(event.playerName, event.message)";
			case "MC.Event.onCommand" -> "print(event.sourceName, event.command)";
			case "MC.Event.onUiAction" ->
					"print(event.playerName, event.uiId, event.actionId, event.inputs.target)";
			default -> "print(event.type)";
		};
		String interval = symbol.equals("MC.Event.onServerTick") || symbol.equals("MC.Event.onLevelTick")
				? ", 20" : "";
		return "-- " + entry.summary() + "\n"
				+ "-- 脚本会保持运行；点击停止或执行 /lua stop 可注销监听\n"
				+ "local registrationId = " + symbol + "(function(event)\n"
				+ "    " + preview + "\n"
				+ "end" + interval + ")\n"
				+ "print(\"事件注册 ID：\", registrationId)\n";
	}

	static String completionLabel(String symbol) {
		Entry entry = find(symbol);
		return entry == null ? symbol : symbol + " - " + entry.summary();
	}

	/** Returns the runtime catalog suitable for a function argument, or {@code null}. */
	static LuaInsertCatalog.Kind insertionKind(String symbol, int argumentIndex) {
		if (symbol == null) return null;
		Entry documented = find(symbol);
		if (documented != null && argumentIndex >= 0 && argumentIndex < documented.parameterTypes().size()) {
			LuaInsertCatalog.Kind documentedKind = switch (documented.parameterTypes().get(argumentIndex)) {
				case BLOCK_ID -> LuaInsertCatalog.Kind.BLOCK;
				case ITEM_ID -> LuaInsertCatalog.Kind.ITEM;
				case ENTITY_ID, ENTITY_UUID -> LuaInsertCatalog.Kind.ENTITY;
				case PLAYER_UUID -> LuaInsertCatalog.Kind.PLAYER;
				case EFFECT_ID -> LuaInsertCatalog.Kind.EFFECT;
				case ENCHANTMENT_ID -> LuaInsertCatalog.Kind.ENCHANTMENT;
				case SOUND_ID -> LuaInsertCatalog.Kind.SOUND;
				case PARTICLE_ID -> LuaInsertCatalog.Kind.PARTICLE;
				case STRUCTURE_ID -> LuaInsertCatalog.Kind.STRUCTURE;
				case LOOT_TABLE_ID -> LuaInsertCatalog.Kind.LOOT_TABLE;
				default -> null;
			};
			if (documentedKind != null) return documentedKind;
		}
		// Built-ins and third-party string parameters share the same name-based dimension hint.
		// Never override an explicitly declared item/entity/numeric/etc. parameter type.
		if (documented != null && argumentIndex >= 0 && argumentIndex < documented.parameters().size()) {
			McLuaIdeApi.ValueType type = argumentIndex < documented.parameterTypes().size()
					? documented.parameterTypes().get(argumentIndex) : null;
			String name = documented.parameters().get(argumentIndex).strip().replace("?", "")
					.replace("_", "").toLowerCase(Locale.ROOT);
			if ((type == null || type == McLuaIdeApi.ValueType.STRING || type == McLuaIdeApi.ValueType.ANY
					|| type == McLuaIdeApi.ValueType.RESOURCE_ID)
					&& (name.equals("dimension") || name.equals("dimensionid"))) return LuaInsertCatalog.Kind.DIMENSION;
		}
		String normalized = symbol.toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mc.entity.name" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.ENTITY : null;
			case "mc.enchantment.name" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.ENCHANTMENT : null;
			case "mc.player.setmenuitem" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : argumentIndex == 2 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.player.setcarried" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : argumentIndex == 1 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.player.openchest", "mc.player.menuitem", "mc.player.carried", "mc.item.trade" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			case "mc.gamerule.set" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.GAME_RULE : null;
			case "mc.effect.give", "mc.effect.clear" -> argumentIndex == 1 ? LuaInsertCatalog.Kind.EFFECT : null;
			case "mc.sound.play" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.SOUND : null;
			case "mc.sound.stop" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER
					: argumentIndex == 1 ? LuaInsertCatalog.Kind.SOUND : null;
			case "mc.particle.spawn" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PARTICLE : null;
			case "mc.structure.place" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.STRUCTURE : null;
			case "mc.loot.give" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER
					: argumentIndex == 1 ? LuaInsertCatalog.Kind.LOOT_TABLE : null;
			case "mc.loot.insert" -> argumentIndex == 3 ? LuaInsertCatalog.Kind.LOOT_TABLE : null;
			case "mc.block.set" -> argumentIndex == 3 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.block.exists", "mc.block.name" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.block.fill" -> argumentIndex == 6 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.block.replace" -> argumentIndex == 6 || argumentIndex == 7 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.item.held" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			case "mc.item.count", "mc.item.give", "mc.item.isoncooldown", "mc.item.startcooldown",
					"mc.item.setcooldown", "mc.item.clearcooldown" -> argumentIndex == 0
					? LuaInsertCatalog.Kind.PLAYER
					: argumentIndex == 1 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.item.validate" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.item.exists", "mc.item.name", "mc.item.maxstack" ->
					argumentIndex == 0 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.item.clear", "mc.item.replaceheld" -> argumentIndex == 0
					? LuaInsertCatalog.Kind.PLAYER
					: argumentIndex == 1 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.item.inventory", "mc.item.getslot", "mc.item.clearslot", "mc.item.swapslots",
					"mc.item.selectedslot", "mc.item.setselectedslot", "mc.item.equipment" ->
					argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			case "mc.item.setslot", "mc.item.setequipment" -> argumentIndex == 0
					? LuaInsertCatalog.Kind.PLAYER
					: argumentIndex == 2 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.player.name", "mc.player.position", "mc.player.health", "mc.player.gamemode",
					"mc.player.teleport", "mc.player.sethealth", "mc.player.setgamemode",
					"mc.player.online", "mc.player.food", "mc.player.setfood", "mc.player.experience",
					"mc.player.addexperience", "mc.player.setexperience", "mc.player.heal",
					"mc.player.damage", "mc.player.kill", "mc.player.kick", "mc.player.setspawn",
					"mc.player.setrotation", "mc.chat.message", "mc.command.runas",
					"mc.title.show", "mc.title.subtitle", "mc.title.actionbar", "mc.title.times",
					"mc.title.clear", "mc.title.reset", "mc.advancement.grant", "mc.advancement.revoke",
					"mc.recipe.give", "mc.recipe.take", "mc.player.openblock",
					"mc.player.opencrafting", "mc.player.openenderchest", "mc.player.openanvil",
					"mc.player.openenchanting", "mc.player.opengrindstone", "mc.player.openloom",
					"mc.player.opencartography", "mc.player.openstonecutter", "mc.player.opensmithing",
					"mc.player.closemenu", "mc.player.menu", "mc.player.abilities", "mc.player.setflying",
					"mc.player.setspeeds", "mc.player.absorption", "mc.player.setabsorption",
					"mc.container.open" ->
					argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			case "mc.container.setslot" -> argumentIndex == 4 ? LuaInsertCatalog.Kind.ITEM : null;
			case "mc.entity.spawn" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.ENTITY : null;
			case "mc.ui.open", "mc.ui.show", "mc.ui.close" ->
					argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			case "mc.client.available", "mc.client.info", "mc.client.message", "mc.client.toast",
					"mc.client.notice", "mc.client.closenotice", "mc.client.openurlprompt",
					"mc.client.openide" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			default -> null;
		};
	}

	/** Returns whether the documented argument accepts a Minecraft text-component JSON string. */
	static boolean isTextJsonArgument(String symbol, int argumentIndex) {
		if ("MC.Player.openChest".equalsIgnoreCase(symbol) && argumentIndex == 1) return true;
		if (symbol == null || argumentIndex < 0) return false;
		Entry documented = find(symbol);
		if (documented == null) return false;
		List<String> parameters = documented.parameters();
		if (argumentIndex >= parameters.size()) return false;
		String parameter = parameters.get(argumentIndex).strip();
		if (parameter.endsWith("?")) parameter = parameter.substring(0, parameter.length() - 1);
		String normalized = parameter.replace("_", "").toLowerCase(Locale.ROOT);
		return normalized.endsWith("orjson") || normalized.equals("textjson")
				|| normalized.equals("jsontext");
	}

	/** Returns whether the documented argument accepts an item-stack component table. */
	static boolean isItemComponentsArgument(String symbol, int argumentIndex) {
		if (symbol == null || argumentIndex < 0) return false;
		Entry documented = find(symbol);
		if (documented == null) return false;
		List<String> parameters = documented.parameters();
		if (argumentIndex >= parameters.size()) return false;
		String parameter = parameters.get(argumentIndex).strip();
		if (parameter.endsWith("?")) parameter = parameter.substring(0, parameter.length() - 1);
		String normalized = parameter.replace("_", "").toLowerCase(Locale.ROOT);
		return normalized.equals("components") || normalized.equals("itemcomponents")
				|| normalized.equals("stackcomponents");
	}

	/** Finds the resource argument that owns an item-component argument. */
	static ComponentTarget componentTarget(String symbol, int componentArgumentIndex) {
		if (symbol == null || componentArgumentIndex <= 0) return null;
		Entry documented = find(symbol);
		if (documented == null) return null;
		List<String> parameters = documented.parameters();
		int last = Math.min(componentArgumentIndex - 1, parameters.size() - 1);
		for (int index = last; index >= 0; index--) {
			McLuaIdeApi.ValueType type = index < documented.parameterTypes().size()
					? documented.parameterTypes().get(index) : null;
			LuaInsertCatalog.Kind typedKind = switch (type == null ? McLuaIdeApi.ValueType.ANY : type) {
				case ITEM_ID -> LuaInsertCatalog.Kind.ITEM;
				case BLOCK_ID -> LuaInsertCatalog.Kind.BLOCK;
				case ENTITY_ID -> LuaInsertCatalog.Kind.ENTITY;
				default -> null;
			};
			if (typedKind != null) return new ComponentTarget(typedKind, index);

			String parameter = parameters.get(index).strip();
			if (parameter.endsWith("?")) parameter = parameter.substring(0, parameter.length() - 1);
			String normalized = parameter.replace("_", "").toLowerCase(Locale.ROOT);
			if (normalized.equals("itemid") || normalized.equals("itemtype")) {
				return new ComponentTarget(LuaInsertCatalog.Kind.ITEM, index);
			}
			if (normalized.equals("blockid") || normalized.equals("blocktype")) {
				return new ComponentTarget(LuaInsertCatalog.Kind.BLOCK, index);
			}
			if (normalized.equals("entityid") || normalized.equals("entitytype")) {
				return new ComponentTarget(LuaInsertCatalog.Kind.ENTITY, index);
			}
			if (normalized.equals("id")) {
				String lowerSymbol = symbol.toLowerCase(Locale.ROOT);
				if (lowerSymbol.startsWith("mc.item.")) {
					return new ComponentTarget(LuaInsertCatalog.Kind.ITEM, index);
				}
				if (lowerSymbol.startsWith("mc.block.")) {
					return new ComponentTarget(LuaInsertCatalog.Kind.BLOCK, index);
				}
			}
		}
		return null;
	}

	record ComponentTarget(LuaInsertCatalog.Kind kind, int argumentIndex) {
	}

	private static Map<String, Entry> createEntries() {
		Map<String, Entry> entries = new LinkedHashMap<>();
		add(entries, "MC.apiVersion", "MC.apiVersion()", "获取服务端脚本 API 版本；当前版本为 1", "字符串 \"1\"", "print(MC.apiVersion()) -- 1", false);
		add(entries, "MC.sleep", "MC.sleep(milliseconds)", "仅暂停当前主流程或当前事件回调指定毫秒数，其他事件（包括同一监听的后续触发）仍可执行，不暂停服务器；停止脚本会取消全部等待。各流程共享变量，只在等待处交错执行。单次范围 0–3600000 毫秒，等待计入运行时限；等待流程数量受事件队列容量限制", "true；当前流程等待结束后继续执行", "print(\"开始\"); MC.sleep(1000); print(\"一秒后\")", false);

		add(entries, "MC.Block.get", "MC.Block.get(x, y, z, dimension?)", "读取服务端坐标处真实方块的 ID、方向／开关等 states，以及箱子内容、熔炉进度、告示牌文字等 blockEntityData；不加载未加载区块，不是物品或模板的默认数据。修改返回表不会修改世界", "方块信息表：states、hasBlockEntity、blockEntityData/blockEntityDataJson（无方块实体时为空对象），保留 itemComponents/itemComponentsJson；单个数据字段最多 262144 字符", "local b = MC.Block.get(0, 64, 0); if b then print(b.blockEntityDataJson) end", false);
		add(entries, "MC.Block.id", "MC.Block.id(x, y, z, dimension?)", "从服务端读取方块资源 ID", "字符串", "print(MC.Block.id(0, 64, 0))", false);
		add(entries, "MC.Block.isAir", "MC.Block.isAir(x, y, z, dimension?)", "从服务端判断坐标是否为空气", "布尔值", "print(MC.Block.isAir(0, 64, 0))", false);
		add(entries, "MC.Block.set", "MC.Block.set(x, y, z, id, dimension?, components?)", "放置真实方块；第六参数推荐使用 {states={facing=\"north\"}, blockEntityData={...}}，也兼容原有方块物品组件表", "是否发生变化", "MC.Block.set(0, 64, 0, \"minecraft:oak_stairs\", nil, {states={facing=\"north\",half=\"top\"}})", false);
		add(entries, "MC.Block.breakAt", "MC.Block.breakAt(x, y, z, drops?, dimension?)", "在服务端破坏方块", "是否成功破坏", "MC.Block.breakAt(0, 64, 0, true)", false);

		add(entries, "MC.Item.held", "MC.Item.held(uuid)", "读取指定玩家的主手物品", "物品堆表，含 components/componentsJson", "local item = MC.Item.held(uuid)", false);
		add(entries, "MC.Item.isOnCooldown", "MC.Item.isOnCooldown(uuid, id, components?)", "查询玩家背包中匹配物品是否正在原版使用冷却；任一匹配物品在冷却即为 true，没有匹配物品返回 false。支持组件子集筛选和 use_cooldown 共享冷却组；不是攻击蓄力或事件节流，普通玩家无需安装客户端模组", "布尔值", "print(MC.Item.isOnCooldown(uuid, \"minecraft:ender_pearl\"))", false);
		add(entries, "MC.Item.startCooldown", "MC.Item.startCooldown(uuid, id, ticks?, components?)", "立即开始或重新开始匹配背包物品的原版使用冷却，并同步客户端；省略 ticks 使用物品 use_cooldown.seconds（没有该组件默认 20 Tick）。共享组只处理一次，默认时取匹配物品最长时长。ticks=0 立即结束；不修改物品组件，普通玩家无需安装客户端模组", "处理的不同冷却组数量；背包没有匹配物品为 0", "MC.Item.startCooldown(uuid, \"minecraft:ender_pearl\", 100)", false);
		add(entries, "MC.Item.setCooldown", "MC.Item.setCooldown(uuid, id, ticks, components?)", "将匹配背包物品的当前剩余使用冷却重新设置为 ticks，从调用时开始计时（不是追加时间）；未在冷却也会立即开始，0 立即结束。遵守共享冷却组，不修改 use_cooldown 组件或以后使用时的默认时长，普通玩家无需安装客户端模组", "处理的不同冷却组数量；背包没有匹配物品为 0", "MC.Item.setCooldown(uuid, \"minecraft:knowledge_book\", 60, {[\"minecraft:custom_data\"]={new_type=\"fire_charge\"}})", false);
		add(entries, "MC.Item.clearCooldown", "MC.Item.clearCooldown(uuid, id, components?)", "立即完成匹配背包物品的原版使用冷却并同步客户端。原版以冷却组存储：同组的其他物品也会一起恢复；不清除其他组，没有匹配物品不操作，普通玩家无需安装客户端模组", "处理的不同冷却组数量；背包没有匹配物品为 0", "MC.Item.clearCooldown(uuid, \"minecraft:ender_pearl\")", false);
		add(entries, "MC.Item.count", "MC.Item.count(uuid, id, components?)", "统计玩家背包中符合条件的物品；只检查传入的组件，其他组件不影响匹配。custom_data 按 NBT 子集匹配，其余指定组件按完整值比较", "整数数量", "print(MC.Item.count(uuid, \"minecraft:knowledge_book\", {[\"minecraft:custom_data\"]={new_type=\"fire_charge\"}}))", false);
		add(entries, "MC.Item.give", "MC.Item.give(uuid, id, count?, components?)", "由服务端给予带数据组件的物品", "实际给予数量", "MC.Item.give(uuid, \"diamond_sword\", 1, {[\"minecraft:unbreakable\"] = {}})", false);
		add(entries, "MC.Item.validate", "MC.Item.validate(id, count?, components?)", "校验并规范化带数据组件的物品堆，不修改游戏", "物品堆信息表", "local stack = MC.Item.validate(\"diamond_sword\", 1, {[\"minecraft:unbreakable\"] = {}})", false);
		add(entries, "MC.Item.inventory", "MC.Item.inventory(uuid)", "读取玩家全部背包槽位", "物品堆数组；每项含 components/componentsJson", "local slots = MC.Item.inventory(uuid)", false);
		add(entries, "MC.Item.getSlot", "MC.Item.getSlot(uuid, slot)", "读取在线玩家指定背包槽位及该堆的组件，slot 从 0 开始，快捷栏为 0～8；主手／副手及穿戴位置也可用 MC.Item.equipment", "物品堆信息表，含 id/count/empty/slot/components/componentsJson；空槽 empty=true、组件为空对象", "local item = MC.Item.getSlot(uuid, 0); if item then print(item.componentsJson) end", false);
		add(entries, "MC.Item.setSlot", "MC.Item.setSlot(uuid, slot, itemId?, count?, components?)", "设置或清空玩家指定背包槽位，可传数据组件", "设置后的物品堆信息表", "MC.Item.setSlot(uuid, 0, \"minecraft:diamond\", 16)", false);
		add(entries, "MC.Item.clearSlot", "MC.Item.clearSlot(uuid, slot)", "清空玩家指定背包槽位", "清空前的物品堆信息表", "local old = MC.Item.clearSlot(uuid, 0)", false);
		add(entries, "MC.Item.swapSlots", "MC.Item.swapSlots(uuid, firstSlot, secondSlot)", "交换玩家两个背包槽位", "true", "MC.Item.swapSlots(uuid, 0, 8)", false);
		add(entries, "MC.Item.selectedSlot", "MC.Item.selectedSlot(uuid)", "读取玩家当前快捷栏槽位", "0 到 8 的整数", "print(MC.Item.selectedSlot(uuid))", false);
		add(entries, "MC.Item.setSelectedSlot", "MC.Item.setSelectedSlot(uuid, slot)", "切换玩家当前快捷栏槽位", "实际槽位（0 到 8）", "MC.Item.setSelectedSlot(uuid, 2)", false);
		add(entries, "MC.Item.equipment", "MC.Item.equipment(uuid, equipmentSlot)", "读取在线玩家指定主手 mainhand、副手 offhand 或穿戴槽的真实物品堆及组件", "物品堆信息表，含 id/count/empty/slot/components/componentsJson；空槽 empty=true", "local item = MC.Item.equipment(uuid, \"offhand\"); if item then print(item.componentsJson) end", false);
		add(entries, "MC.Item.setEquipment", "MC.Item.setEquipment(uuid, equipmentSlot, itemId?, count?, components?)", "设置或清空玩家指定装备槽，可传数据组件", "设置后的物品堆信息表", "MC.Item.setEquipment(uuid, \"head\", \"minecraft:diamond_helmet\")", false);

		add(entries, "MC.Player.list", "MC.Player.list()", "列出全部在线服务端玩家", "玩家信息数组", "local players = MC.Player.list()", false);
		add(entries, "MC.Player.uuid", "MC.Player.uuid(name)", "按在线玩家名称查询 UUID", "UUID 字符串或 nil", "local uuid = MC.Player.uuid(\"Steve\")", false);
		add(entries, "MC.Player.name", "MC.Player.name(uuid)", "按 UUID 获取在线玩家名称", "字符串", "print(MC.Player.name(uuid))", false);
		add(entries, "MC.Player.position", "MC.Player.position(uuid)", "读取指定玩家位置和朝向", "{x, y, z, yaw, pitch, dimension}", "local p = MC.Player.position(uuid)", false);
		entries.put("MC.Player.facing", new Entry("MC.Player.facing(uuid)", "读取玩家当前水平和垂直朝向角度", "{yaw, pitch, direction}",
				"local f = MC.Player.facing(uuid); print(f.yaw, f.pitch, f.direction)", false,
				"yaw 为 -180～180 度（0 朝南，90 朝西，-90 朝东，±180 朝北），pitch 为 -90（朝上）～90（朝下）；direction 是 north、south、east、west 之一，按水平朝向取最接近的方向。"));
		entries.put("MC.Player.lookDirection", new Entry("MC.Player.lookDirection(uuid)", "读取玩家当前三维视线方向", "{x, y, z}",
				"local look = MC.Player.lookDirection(uuid); print(look.x, look.y, look.z)", false,
				"返回长度为 1 的方向向量（x 向东、y 向上、z 向南），例如玩家朝东略向上看时约为 {x = 0.98, y = 0.2, z = 0}。这是方向而不是目标点：要得到玩家前方某处的坐标，用 玩家位置 + 方向 × 距离，或 MC.Player.relativePosition。"));
		entries.put("MC.Player.lookingAt", new Entry("MC.Player.lookingAt(uuid, infinite?)", "读取玩家准星指向的方块上的精确坐标（带小数）",
				"{x, y, z, blockX, blockY, blockZ, face, blockId, distance, dimension}；没有命中时为 nil",
				"local hit = MC.Player.lookingAt(uuid, false); if hit then print(hit.x, hit.y, hit.z) end", false,
				"infinite 为 false 或省略时只检测玩家的方块交互距离，为 true 时最远检测 1024 格（仍受已加载区块限制）。从玩家眼睛出发，忽略流体，命中方块的碰撞轮廓；face 是被命中的面（north、south、east、west、up、down）。准星指向实体时不计入。没有命中任何方块时返回 nil，使用前请判空。"));
		entries.put("MC.Player.relativePosition", new Entry("MC.Player.relativePosition(uuid, direction, plane, distance)",
				"计算玩家前、后、左、右指定距离处的坐标", "{x, y, z, blockX, blockY, blockZ, dimension}",
				"local p = MC.Player.relativePosition(uuid, \"front\", \"horizontal\", 5)", false,
				"direction 为 front、back、left、right；plane 为 horizontal（只按水平朝向 yaw）、vertical（只按垂直朝向 pitch，front／back 沿上下移动，不能用于 left／right）或 both（front／back 按完整视线方向，left／right 保持水平）。distance 为方块数，可为负数，绝对值不超过 100000；起点是玩家当前位置（脚部）。只做计算，不检查该位置是否已加载或可放置。"));
		add(entries, "MC.Player.health", "MC.Player.health(uuid)", "读取指定玩家生命值", "{current, max}", "local hp = MC.Player.health(uuid)", false);
		add(entries, "MC.Player.gameMode", "MC.Player.gameMode(uuid)", "读取指定玩家游戏模式", "模式名称字符串", "print(MC.Player.gameMode(uuid))", false);
		add(entries, "MC.Player.teleport", "MC.Player.teleport(uuid, x, y, z, dimension?)", "由服务端传送指定玩家", "是否成功", "MC.Player.teleport(uuid, 0, 80, 0)", false);
		add(entries, "MC.Player.setHealth", "MC.Player.setHealth(uuid, value)", "设置指定玩家生命值", "实际生命值", "MC.Player.setHealth(uuid, 20)", false);
		add(entries, "MC.Player.setGameMode", "MC.Player.setGameMode(uuid, mode)", "设置指定玩家游戏模式", "是否发生变化", "MC.Player.setGameMode(uuid, \"creative\")", false);
		add(entries, "MC.Player.openBlock", "MC.Player.openBlock(uuid, x, y, z, dimension?)", "让玩家打开坐标处方块的原版菜单", "容器 ID；无法打开时 nil/错误", "MC.Player.openBlock(uuid, 10, 64, 10)", false);
		add(entries, "MC.Player.openCrafting", "MC.Player.openCrafting(uuid, title?)", "直接为玩家打开工作台", "容器 ID 或 nil", "MC.Player.openCrafting(uuid)", false);
		add(entries, "MC.Player.openChest", "MC.Player.openChest(uuid, title?, items?, rows?, readOnly?)", "打开没有世界位置的临时原版箱子界面，普通客户端无需安装模组。items 为 {id,count,components} 数组，也可直接填物品 ID；允许稀疏数组，索引从 1 开始。rows 为 1～6，省略时自动按内容至少 3 行；readOnly=true 阻止全部物品移动，用 onContainerClicked 实现安全商店。内容不落地、不持久保存，关闭后丢弃剩余内容，不能当仓库存储；脚本结束自动关闭其创建的菜单", "容器 ID（整数）或 nil", "local id = MC.Player.openChest(uuid, \"商店\", {{id=\"minecraft:diamond\",count=8,components={[\"minecraft:custom_name\"]={text=\"商品\",color=\"aqua\"}}}}, 3, true)", false);
		add(entries, "MC.Player.menuItem", "MC.Player.menuItem(uuid, slotId, expectedContainerId?)", "读取当前打开菜单的格子，支持虚拟箱子；slotId 为原版菜单槽号，从 0 开始，包含下方玩家背包；expectedContainerId 可防止对已关闭或换掉的窗口操作", "物品堆信息表，含 id/count/components/componentsJson/slotId/containerId；过期菜单返回 nil, 原因", "local item = MC.Player.menuItem(uuid, 0)", false);
		add(entries, "MC.Player.setMenuItem", "MC.Player.setMenuItem(uuid, slotId, itemId?, count?, components?, expectedContainerId?)", "替换玩家当前菜单的指定格子并同步到客户端，可修改没有实际箱子的虚拟 UI；itemId=nil/air 或 count=0 清空；槽号从 0 开始。属于管理操作，不触发玩家取放事件；建议传 expectedContainerId 避免异步回调误改新菜单", "物品堆信息表；过期菜单返回 nil, 原因", "MC.Player.setMenuItem(uuid, 0, \"minecraft:emerald\", 8, {[\"minecraft:custom_name\"]={text=\"刷新商品\"}}, containerId)", false);
		add(entries, "MC.Player.carried", "MC.Player.carried(uuid, expectedContainerId?)", "读取玩家鼠标光标上拿着的物品，不是主手／副手物品；未打开容器时也可读取背包界面的光标", "物品堆信息表；空光标 empty=true/count=0；过期菜单 nil, 原因", "print(MC.Player.carried(uuid).id)", false);
		add(entries, "MC.Player.setCarried", "MC.Player.setCarried(uuid, itemId?, count?, components?, expectedContainerId?)", "替换并同步玩家鼠标上拿着的物品，支持组件；itemId=nil/air 或 count=0 清空；不会触发玩家从槽位拿取到鼠标的事件", "物品堆信息表；过期菜单 nil, 原因", "MC.Player.setCarried(uuid, \"minecraft:diamond\", 2)\nMC.Player.setCarried(uuid, nil)", false);
		add(entries, "MC.Item.trade", "MC.Item.trade(uuid, costs, rewards, expectedContainerId?)", "服务端原子商店交易：先模拟扣费与发货，货币或背包空间不足时完全不修改背包，不会只扣费不发货。costs/rewards 为连续 {id,count?,components?} 数组，最多 54 项，仅使用主背包与快捷栏 0～35；费用仅检查传入的组件，custom_data 支持 NBT 子集；未写组件或 {} 只检查物品 ID。奖励按填写的完整组件生成，只有完整组件相同才合堆。交易失败不掉落物品；可绑定菜单 ID 防止关闭后仍购买", "布尔值：true 成功；false, 原因 表示余额／空间不足；菜单已改变返回 nil, 原因", "local ok, reason = MC.Item.trade(uuid, {{id=\"minecraft:iron_ingot\",count=4}}, {{id=\"minecraft:white_wool\",count=16}}, containerId)", false);
		add(entries, "MC.Player.openEnderChest", "MC.Player.openEnderChest(uuid, title?)", "直接为玩家打开自己的末影箱", "容器 ID 或 nil", "MC.Player.openEnderChest(uuid)", false);
		add(entries, "MC.Player.openAnvil", "MC.Player.openAnvil(uuid, title?)", "直接为玩家打开铁砧", "容器 ID 或 nil", "MC.Player.openAnvil(uuid)", false);
		add(entries, "MC.Player.openEnchanting", "MC.Player.openEnchanting(uuid, title?)", "直接为玩家打开附魔台", "容器 ID 或 nil", "MC.Player.openEnchanting(uuid)", false);
		add(entries, "MC.Player.openGrindstone", "MC.Player.openGrindstone(uuid, title?)", "直接为玩家打开砂轮", "容器 ID 或 nil", "MC.Player.openGrindstone(uuid)", false);
		add(entries, "MC.Player.openLoom", "MC.Player.openLoom(uuid, title?)", "直接为玩家打开织布机", "容器 ID 或 nil", "MC.Player.openLoom(uuid)", false);
		add(entries, "MC.Player.openCartography", "MC.Player.openCartography(uuid, title?)", "直接为玩家打开制图台", "容器 ID 或 nil", "MC.Player.openCartography(uuid)", false);
		add(entries, "MC.Player.openStonecutter", "MC.Player.openStonecutter(uuid, title?)", "直接为玩家打开切石机", "容器 ID 或 nil", "MC.Player.openStonecutter(uuid)", false);
		add(entries, "MC.Player.openSmithing", "MC.Player.openSmithing(uuid, title?)", "直接为玩家打开锻造台", "容器 ID 或 nil", "MC.Player.openSmithing(uuid)", false);
		add(entries, "MC.Player.closeMenu", "MC.Player.closeMenu(uuid)", "关闭玩家当前打开的容器菜单", "关闭前是否有菜单打开", "MC.Player.closeMenu(uuid)", false);
		add(entries, "MC.Player.menu", "MC.Player.menu(uuid)", "读取玩家当前菜单状态", "{open, containerId, slots, className, type}", "local menu = MC.Player.menu(uuid)", false);
		add(entries, "MC.Player.abilities", "MC.Player.abilities(uuid)", "读取玩家能力与移动速度", "{invulnerable, flying, mayFly, instantBuild, mayBuild, walkingSpeed, flyingSpeed}", "local a = MC.Player.abilities(uuid)", false);
		add(entries, "MC.Player.setFlying", "MC.Player.setFlying(uuid, mayFly, flying?)", "设置玩家飞行权限和当前飞行状态", "true", "MC.Player.setFlying(uuid, true, true)", false);
		add(entries, "MC.Player.setSpeeds", "MC.Player.setSpeeds(uuid, walkingSpeed, flyingSpeed)", "设置玩家行走与飞行速度", "true", "MC.Player.setSpeeds(uuid, 0.1, 0.05)", false);
		add(entries, "MC.Player.absorption", "MC.Player.absorption(uuid)", "读取玩家伤害吸收值（金色生命）", "数字", "print(MC.Player.absorption(uuid))", false);
		add(entries, "MC.Player.setAbsorption", "MC.Player.setAbsorption(uuid, value)", "设置玩家伤害吸收值（金色生命）", "实际数值", "MC.Player.setAbsorption(uuid, 8)", false);

		add(entries, "MC.Container.info", "MC.Container.info(x, y, z, dimension?)", "读取坐标处方块容器信息", "{blockId, blockEntityType, size, empty, x, y, z, dimension}", "local c = MC.Container.info(10, 64, 10)", false);
		add(entries, "MC.Container.list", "MC.Container.list(x, y, z, dimension?)", "读取方块容器全部槽位", "物品堆数组", "local slots = MC.Container.list(10, 64, 10)", false);
		add(entries, "MC.Container.getSlot", "MC.Container.getSlot(x, y, z, slot, dimension?)", "读取方块容器指定槽位（从 0 开始）", "物品堆信息表", "print(MC.Container.getSlot(10, 64, 10, 0).id)", false);
		add(entries, "MC.Container.setSlot", "MC.Container.setSlot(x, y, z, slot, itemId?, count?, components?, dimension?)", "设置或清空方块容器槽位，可传数据组件", "设置后的物品堆信息表", "MC.Container.setSlot(10, 64, 10, 0, \"minecraft:diamond\", 8)", false);
		add(entries, "MC.Container.clear", "MC.Container.clear(x, y, z, dimension?)", "清空方块容器全部槽位", "移除的物品总数", "print(MC.Container.clear(10, 64, 10))", false);
		add(entries, "MC.Container.open", "MC.Container.open(uuid, x, y, z, dimension?)", "让玩家打开坐标处方块的原版菜单", "容器 ID；无法打开时 nil/错误", "MC.Container.open(uuid, 10, 64, 10)", false);

		add(entries, "MC.World.dimensions", "MC.World.dimensions()", "列出服务端全部已加载维度", "维度 ID 数组", "for _, id in ipairs(MC.World.dimensions()) do print(id) end", false);
		add(entries, "MC.World.gameTime", "MC.World.gameTime(dimension?)", "读取服务端世界累计游戏刻", "数字", "print(MC.World.gameTime())", false);
		add(entries, "MC.World.clockTime", "MC.World.clockTime(dimension?)", "读取服务端世界时钟刻数", "数字", "print(MC.World.clockTime())", false);
		add(entries, "MC.World.weather", "MC.World.weather(dimension?)", "读取服务端天气信息", "{name, raining, thundering, dimension}", "local w = MC.World.weather()", false);
		add(entries, "MC.World.seed", "MC.World.seed(dimension?)", "读取完整服务端世界种子", "字符串", "print(MC.World.seed())", false);

		add(entries, "MC.Chat.message", "MC.Chat.message(uuid, textOrJson)", "向指定玩家发送服务端系统消息；支持普通字符串、文本组件 JSON 或 Lua 表", "true", "MC.Chat.message(uuid, {text = \"脚本已启动\", color = \"#55FF55\", underlined = true})", false);
		add(entries, "MC.Chat.broadcast", "MC.Chat.broadcast(textOrJson)", "向全部在线玩家广播系统消息；支持普通字符串、文本组件 JSON 或 Lua 表", "true", "MC.Chat.broadcast({text = \"服务器消息\", color = \"gold\", bold = true})", false);
		add(entries, "MC.Command.run", "MC.Command.run(command)", "以服务端控制台身份执行命令", "true；表示命令已提交", "MC.Command.run(\"time set day\")", false);
		add(entries, "MC.Command.runAs", "MC.Command.runAs(uuid, command)", "以指定玩家身份执行命令", "true；结果取决于玩家权限", "MC.Command.runAs(uuid, \"spawnpoint\")", false);

		add(entries, "MC.Mod.isLoaded", "MC.Mod.isLoaded(id)", "检查 Fabric 模组是否加载", "布尔值", "print(MC.Mod.isLoaded(\"fabric-api\"))", false);
		add(entries, "MC.Mod.version", "MC.Mod.version(id)", "获取 Fabric 模组版本", "版本字符串或 nil", "print(MC.Mod.version(\"fabric-api\"))", false);
		add(entries, "MC.Mod.list", "MC.Mod.list()", "列出全部已加载模组", "{ {id, name, version}, ... }", "for _, m in ipairs(MC.Mod.list()) do print(m.id) end", false);

		add(entries, "MC.Game.version", "MC.Game.version()", "获取 Minecraft 版本", "字符串", "print(MC.Game.version())", false);
		add(entries, "MC.Game.loaderVersion", "MC.Game.loaderVersion()", "获取 Fabric Loader 版本", "字符串", "print(MC.Game.loaderVersion())", false);

		add(entries, "MC.Server.playerCount", "MC.Server.playerCount()", "获取服务端在线玩家数量", "整数", "print(MC.Server.playerCount())", false);
		add(entries, "MC.Server.isDedicated", "MC.Server.isDedicated()", "判断是否为独立服务端", "布尔值", "print(MC.Server.isDedicated())", false);
		add(entries, "MC.Server.motd", "MC.Server.motd()", "获取服务端 MOTD", "字符串", "print(MC.Server.motd())", false);

		add(entries, "MC.Block.exists", "MC.Block.exists(id)", "检查方块资源 ID 是否已注册", "布尔值", "print(MC.Block.exists(\"minecraft:stone\"))", false);
		add(entries, "MC.Block.name", "MC.Block.name(id, language?)", "按方块资源 ID 获取指定语言名称，支持其他模组；如 zh_cn/en_us/ja_jp，大小写及横线自动规范化。不填语言保持默认名称行为；不会改变游戏全局语言。首次缺少原版语言文件会从 Mojang 官方资源下载并缓存，不在服务器主线程等待", "名称字符串；指定语言时还返回实际语言代码：目标代码/en_us（英文回退）/literal（固定文本）/untranslated（缺少译名）；资源不可用时 nil, 原因并黄色警告", "local name, language = MC.Block.name(\"minecraft:stone\", \"zh_cn\")\nprint(name, language)", false);
		add(entries, "MC.Block.random", "MC.Block.random()", "从当前服务端全部已注册方块（含其他模组）中随机取得一个 ID", "方块资源 ID 字符串", "local blockId = MC.Block.random()", false);
		add(entries, "MC.Block.fill", "MC.Block.fill(x1, y1, z1, x2, y2, z2, blockId, dimension?)", "直接填充长方体区域，不受原版命令方块数量上限影响", "是否至少修改一个方块", "MC.Block.fill(0, 64, 0, 4, 68, 4, \"minecraft:stone\")", false);
		add(entries, "MC.Block.replace", "MC.Block.replace(x1, y1, z1, x2, y2, z2, fromId, toId, dimension?)", "直接替换区域内指定方块，不修改原版命令实现", "是否至少修改一个方块", "MC.Block.replace(0, 64, 0, 8, 72, 8, \"minecraft:dirt\", \"minecraft:grass_block\")", false);
		add(entries, "MC.Block.addData", "MC.Block.addData(x, y, z, data, dimension?)", "向方块的方块实体添加数据：Lua 表里带命名空间的键（如 minecraft:custom_name）是数据组件，其余键是 NBT 字段（也可传 SNBT 字符串）", "成功返回 true", "MC.Block.addData(0, 64, 0, {\n    [\"minecraft:custom_name\"] = \"宝箱\",\n})", false);
		add(entries, "MC.Block.hideData", "MC.Block.hideData(x, y, z, keys, dimension?)", "隐藏（移除）方块实体的数据组件（如 minecraft:custom_name）或顶层 NBT 字段，游戏会按默认值读取；id、x、y、z 不可隐藏", "成功返回 true", "MC.Block.hideData(0, 64, 0, {\"minecraft:custom_name\"})", false);
		add(entries, "MC.Block.clone","MC.Block.clone(x1, y1, z1, x2, y2, z2, toX, toY, toZ, dimension?)", "直接复制方块区域并保留方块实体数据，不受原版 clone 数量上限影响", "是否至少修改一个方块", "MC.Block.clone(0, 64, 0, 4, 68, 4, 16, 64, 16)", false);

		add(entries, "MC.Item.exists", "MC.Item.exists(id)", "检查物品资源 ID 是否已注册", "布尔值", "print(MC.Item.exists(\"minecraft:diamond\"))", false);
		add(entries, "MC.Item.name", "MC.Item.name(id, language?)", "按物品 ID 查询指定语言的默认名称，支持其他模组和自定义翻译键。省略语言兼容旧用法。不读取玩家资源包、不要求玩家安装本 Mod；取服务端 Mod assets 和官方原版语言缓存，可用 config/mc_lua_ide/lang/<语言>.json 离线覆盖", "名称字符串；指定语言时第二返回值为实际语言代码或 en_us/literal/untranslated；资源不可用时 nil, 原因并黄色警告", "local chinese = MC.Item.name(\"minecraft:diamond\", \"zh_cn\")\nlocal english = MC.Item.name(\"minecraft:diamond\", \"en_us\")\nprint(chinese, english)", false);
		add(entries, "MC.Entity.name", "MC.Entity.name(entityType, language?)", "通过实体类型资源 ID 获取指定语言名称，如 minecraft:pig；不是运行中实体 UUID，也不会生成实体。支持其他模组的实体及语言文件。省略语言使用默认名称", "名称字符串；指定语言时第二值为实际语言代码或 en_us/literal/untranslated；资源不可用时 nil, 原因", "print(MC.Entity.name(\"minecraft:pig\", \"zh_cn\"))", false);
		add(entries, "MC.Enchantment.name", "MC.Enchantment.name(enchantmentId, language?)", "从当前服务端动态附魔注册表读取描述文本并翻译，包含数据包及其他模组注册的附魔。纯文字描述返回原文字，不伪造翻译。名称不含等级，如 minecraft:sharpness → 锋利。无效 ID 仍报错", "名称字符串；指定语言时第二值为实际语言代码或 en_us/literal/untranslated；资源不可用时 nil, 原因", "print(MC.Enchantment.name(\"minecraft:sharpness\", \"zh_cn\"))", false);
		add(entries, "MC.Item.maxStack", "MC.Item.maxStack(id)", "获取物品默认最大堆叠数", "整数", "print(MC.Item.maxStack(\"minecraft:ender_pearl\"))", false);
		add(entries, "MC.Item.random", "MC.Item.random()", "从当前服务端全部已注册物品（含其他模组）中随机取得一个 ID", "物品资源 ID 字符串", "local itemId = MC.Item.random()", false);
		add(entries, "MC.Item.clear", "MC.Item.clear(uuid, id?, count?, components?)", "清除玩家背包中符合条件的物品；只检查传入的组件，其他组件不影响匹配。custom_data 按 NBT 子集匹配，其余指定组件按完整值比较；传组件时必须填写物品 ID", "true", "MC.Item.clear(uuid, \"minecraft:knowledge_book\", 1, {[\"minecraft:custom_data\"]={new_type=\"fire_charge\"}})", false);
		add(entries, "MC.Item.replaceHeld", "MC.Item.replaceHeld(uuid, id, count?, components?)", "替换玩家主手物品，可传数据组件", "true", "MC.Item.replaceHeld(\"玩家UUID\", \"minecraft:diamond_sword\", 1)", false);

		add(entries, "MC.Player.online", "MC.Player.online(uuid)", "检查玩家 UUID 当前是否在线", "布尔值", "print(MC.Player.online(\"玩家UUID\"))", false);
		add(entries, "MC.Player.food", "MC.Player.food(uuid)", "读取玩家饥饿与饱和状态", "{level, saturation, needsFood}", "local food = MC.Player.food(\"玩家UUID\"); print(food.level, food.saturation)", false);
		add(entries, "MC.Player.setFood", "MC.Player.setFood(uuid, food, saturation?)", "设置玩家饥饿值和饱和度", "true", "MC.Player.setFood(\"玩家UUID\", 20, 5)", false);
		add(entries, "MC.Player.experience", "MC.Player.experience(uuid)", "读取玩家经验等级、总经验和进度", "{level, total, progress}", "local xp = MC.Player.experience(\"玩家UUID\"); print(xp.level, xp.total)", false);
		add(entries, "MC.Player.addExperience", "MC.Player.addExperience(uuid, amount, levels?)", "增加或减少玩家经验点/等级", "true；命令已提交", "MC.Player.addExperience(\"玩家UUID\", 5, true)", false);
		add(entries, "MC.Player.setExperience", "MC.Player.setExperience(uuid, amount, levels?)", "设置玩家经验点或等级", "true；命令已提交", "MC.Player.setExperience(\"玩家UUID\", 30, true)", false);
		add(entries, "MC.Player.heal", "MC.Player.heal(uuid, amount?)", "治疗玩家；不填数量时恢复至满血", "实际恢复生命值", "print(MC.Player.heal(\"玩家UUID\", 6))", false);
		add(entries, "MC.Player.damage", "MC.Player.damage(uuid, amount, damageType?)", "用指定伤害类型伤害玩家", "true；命令已提交", "MC.Player.damage(\"玩家UUID\", 4, \"minecraft:generic\")", false);
		add(entries, "MC.Player.kill", "MC.Player.kill(uuid)", "杀死指定在线玩家", "true；命令已提交", "MC.Player.kill(\"玩家UUID\")", false);
		add(entries, "MC.Player.kick", "MC.Player.kick(uuid, reason?)", "将玩家踢出服务器", "true；命令已提交", "MC.Player.kick(\"玩家UUID\", \"服务器维护\")", false);
		add(entries, "MC.Player.setSpawn", "MC.Player.setSpawn(uuid, x, y, z, dimension?)", "设置玩家个人重生点", "true；命令已提交", "MC.Player.setSpawn(\"玩家UUID\", 0, 64, 0)", false);
		add(entries, "MC.Player.setRotation", "MC.Player.setRotation(uuid, yaw, pitch)", "设置玩家视角方向", "true；命令已提交", "MC.Player.setRotation(\"玩家UUID\", 180, 0)", false);

		add(entries, "MC.Entity.get", "MC.Entity.get(uuid)", "读取已加载实体的完整基础信息，包括 vehicleUuid（当前坐骑 UUID，未骑乘时 nil）和 passengerUuids（直接乘客 UUID 数组，不含更深层乘客）", "实体信息表或 nil", "local e = MC.Entity.get(\"实体UUID\"); if e then print(e.type, e.name, e.health, e.vehicleUuid) end", false);
		add(entries, "MC.Entity.components", "MC.Entity.components(uuid)", "按运行中 UUID 读取已加载实体（含玩家）的真实组件和实体 NBT 数据快照，支持其他 Mod，不是刷怪蛋数据；含生命值、装备、展示实体／掉落物专有数据。只读取，不修改实体；需要实体和物品 API 权限", "快照表：uuid/type、components/componentsJson（可持久化的注册组件，含默认值）、entityData/entityDataJson（实体实际序列化 NBT）、equipment（槽名到物品堆，内含组件）、glowing/invulnerable/noGravity；有生命实体另含 health/maxHealth。无装备能力时 equipment 为空；第三方组件读取／编码失败列在 componentErrors，不影响其他组件；实体不存在或未加载返回 nil, 原因并黄色警告。单个数据字段最多 262144 字符；不是 spawn 的可直接回传配置", "local data, reason = MC.Entity.components(\"实体UUID\"); if data then print(data.componentsJson, data.entityDataJson) else print(reason) end", false);
		add(entries, "MC.Entity.exists", "MC.Entity.exists(uuid)", "检查运行中实体是否存在且已加载", "布尔值", "print(MC.Entity.exists(\"实体UUID\"))", false);
		add(entries, "MC.Entity.random", "MC.Entity.random()", "从当前服务端全部已注册实体类型（含其他模组）中随机取得一个 ID", "实体类型资源 ID 字符串", "local entityId = MC.Entity.random()", false);
		add(entries, "MC.Entity.list", "MC.Entity.list(dimension?)", "列出维度内全部已加载实体", "实体信息数组", "for _, e in ipairs(MC.Entity.list()) do print(e.uuid, e.type) end", false);
		add(entries, "MC.Entity.nearby", "MC.Entity.nearby(x, y, z, radius, dimension?)", "列出坐标附近的已加载实体", "实体信息数组", "for _, e in ipairs(MC.Entity.nearby(0, 64, 0, 16)) do print(e.name) end", false);
		add(entries, "MC.Entity.spawn", "MC.Entity.spawn(entityType, x, y, z, dimension?, components?)", "生成实体并返回其 UUID；第六参数支持实体数据组件、通用状态和 fabric:attachments（附件 ID 到持久化数据的表）。例如 ['fabric:attachments']={['touhou_little_maid:profile']={model_id='geckolib:zhiban_hanfu'}}，按服务端提供附件的模组 Codec 校验并通过 Fabric API 设置，生成前生效，不执行命令、不重载实体 NBT；不覆盖 UUID/坐标或未指定附件，客户端同步遵循附件所属模组。未知/非持久化附件、无效数据报错。item_display、block_display、text_display 支持 transformation、billboard、亮度、插值等；minecraft:item 支持 Item={id,count,components}、Health、Age、PickupDelay、Owner/Thrower UUID，Health 不等于生物 health；物品组件只放 Item.components", "生成实体的 UUID 字符串；加入世界失败返回 false；无效参数或无法生成的实体类型会报错", "local entityUuid = MC.Entity.spawn(\"minecraft:text_display\", 0, 64, 0, nil, {text={text=\"欢迎\",color=\"gold\"},billboard=\"center\",line_width=200})\nif entityUuid then\n    print(\"实体 UUID：\", entityUuid)\n    MC.Entity.setGlowing(entityUuid, true)\nend", false);
		add(entries, "MC.Entity.teleport", "MC.Entity.teleport(uuid, x, y, z, dimension?)", "传送已加载实体", "是否成功", "MC.Entity.teleport(\"实体UUID\", 0, 80, 0)", false);
		add(entries, "MC.Entity.ride", "MC.Entity.ride(uuid, vehicleUuid, force?)", "让第一个实体骑乘第二个实体；两个参数都是运行中的 UUID，玩家也可作为乘客。默认 force=true，跳过普通上车冷却和座位容量检查；false 时遵守这些检查。重复骑乘同一坐骑返回 true，成功换乘会离开旧坐骑。保留原版/其他模组的基本限制；实体不存在或未加载时显示黄色警告并返回 nil, 原因；自己骑自己、循环骑乘、跨维度骑乘以及玩家作为坐骑仍报错。使用原版骑乘关系，普通玩家无需安装客户端模组", "布尔值：成功（或已在该坐骑上）返回 true，原版/模组拒绝骑乘返回 false", "local passengerUuid = MC.Entity.spawn(\"minecraft:pig\", 0, 64, 0)\nlocal vehicleUuid = MC.Entity.spawn(\"minecraft:cow\", 0, 64, 0)\nif passengerUuid and vehicleUuid then\n    print(\"是否骑乘成功：\", MC.Entity.ride(passengerUuid, vehicleUuid))\nend", false);
		add(entries, "MC.Entity.dismount", "MC.Entity.dismount(uuid)", "让指定实体（包括玩家）离开当前坐骑；使用原版下坐骑逻辑，普通玩家无需安装客户端模组。实体不存在或未加载时显示黄色警告并返回 nil, 原因，脚本继续运行", "布尔值：实际离开坐骑返回 true；原本未骑乘或模组拒绝下坐骑返回 false", "local player = MC.Player.list()[1]\nif player then\n    print(\"是否离开坐骑：\", MC.Entity.dismount(player.uuid))\nend", false);
		add(entries, "MC.Entity.velocity", "MC.Entity.velocity(uuid, x, y, z)", "设置实体三轴速度", "true", "MC.Entity.velocity(\"实体UUID\", 0, 1, 0)", false);
		add(entries, "MC.Entity.kill", "MC.Entity.kill(uuid)", "调用实体的原版 kill 逻辑；有生命实体（LivingEntity，包括玩家）可触发正常死亡流程，非生命实体通常直接移除，不保证触发 onEntityDied", "true", "MC.Entity.kill(\"实体UUID\")", false);
		add(entries, "MC.Entity.remove", "MC.Entity.remove(uuid)", "直接移除非玩家实体", "true", "MC.Entity.remove(\"实体UUID\")", false);
		add(entries, "MC.Entity.setName", "MC.Entity.setName(uuid, nameOrJson, visible?)", "设置实体自定义名称及可见状态；名称支持文本组件 JSON 或 Lua 表", "true", "MC.Entity.setName(\"实体UUID\", {text = \"守卫\", color = \"#55FFFF\", bold = true}, true)", false);
		add(entries, "MC.Entity.setGlowing", "MC.Entity.setGlowing(uuid, value)", "设置实体发光轮廓", "true", "MC.Entity.setGlowing(\"实体UUID\", true)", false);
		add(entries, "MC.Entity.setInvulnerable", "MC.Entity.setInvulnerable(uuid, value)", "设置实体是否无敌", "true", "MC.Entity.setInvulnerable(\"实体UUID\", true)", false);
		add(entries, "MC.Entity.setGravity", "MC.Entity.setGravity(uuid, enabled)", "启用或关闭实体重力", "true", "MC.Entity.setGravity(\"实体UUID\", false)", false);
		add(entries, "MC.Entity.equipment", "MC.Entity.equipment(uuid, equipmentSlot)", "读取已加载有生命实体（LivingEntity，包括玩家）的主手、副手及穿戴装备，不适用于展示实体／掉落物", "物品堆信息表，含 id/count/empty/slot/components/componentsJson；空槽 empty=true", "local held = MC.Entity.equipment(\"实体UUID\", \"mainhand\"); if held then print(held.componentsJson) end", false);
		add(entries, "MC.Entity.setEquipment", "MC.Entity.setEquipment(uuid, equipmentSlot, itemId?, count?, components?)", "设置或清空已加载有生命实体（LivingEntity，包括玩家）的装备", "设置后的物品堆信息表", "MC.Entity.setEquipment(\"实体UUID\", \"mainhand\", \"minecraft:diamond_sword\", 1)", false);
		add(entries, "MC.Entity.fire", "MC.Entity.fire(uuid, seconds)", "设置实体燃烧时间；0 表示灭火", "true", "MC.Entity.fire(\"实体UUID\", 5)", false);
		add(entries, "MC.Entity.tags", "MC.Entity.tags(uuid)", "列出实体的命令标签", "字符串数组", "for _, tag in ipairs(MC.Entity.tags(\"实体UUID\")) do print(tag) end", false);
		add(entries, "MC.Entity.addTag", "MC.Entity.addTag(uuid, tag)", "添加实体命令标签", "是否发生变化", "MC.Entity.addTag(\"实体UUID\", \"boss\")", false);
		add(entries, "MC.Entity.addData", "MC.Entity.addData(uuid, data)", "向已存在的实体添加数据：Lua 表里带命名空间的键（如 minecraft:custom_name）是数据组件，其余键是 NBT 字段（也可传 SNBT 字符串；不含玩家）", "成功返回 true", "MC.Entity.addData(entityUuid, {\n    [\"minecraft:custom_name\"] = \"队伍\",\n})", false);
		add(entries, "MC.Entity.hideData", "MC.Entity.hideData(uuid, keys)", "隐藏（移除）实体的数据组件（如 minecraft:custom_name）或顶层 NBT 字段，游戏会按默认值读取；id、UUID、Pos、Passengers 不可隐藏", "成功返回 true", "MC.Entity.hideData(entityUuid, {\"minecraft:custom_name\"})", false);
		add(entries, "MC.Entity.removeTag","MC.Entity.removeTag(uuid, tag)", "移除实体命令标签", "是否发生变化", "MC.Entity.removeTag(\"实体UUID\", \"boss\")", false);

		add(entries, "MC.World.setTime", "MC.World.setTime(time, dimension?)", "设置世界时间，可传 day/night/noon/midnight 或刻数", "true；命令已提交", "MC.World.setTime(\"day\")", false);
		add(entries, "MC.World.addTime", "MC.World.addTime(ticks, dimension?)", "增加世界时间", "true；命令已提交", "MC.World.addTime(1000)", false);
		add(entries, "MC.World.setWeather", "MC.World.setWeather(weather, duration?)", "设置 clear/rain/thunder 天气", "true；命令已提交", "MC.World.setWeather(\"rain\", 120)", false);
		add(entries, "MC.World.difficulty", "MC.World.difficulty()", "读取当前游戏难度", "难度字符串", "print(MC.World.difficulty())", false);
		add(entries, "MC.World.setDifficulty", "MC.World.setDifficulty(difficulty)", "设置游戏难度", "true；命令已提交", "MC.World.setDifficulty(\"hard\")", false);
		add(entries, "MC.World.setSpawn", "MC.World.setSpawn(x, y, z, dimension?)", "设置维度的世界出生点", "true；命令已提交", "MC.World.setSpawn(0, 64, 0)", false);
		add(entries, "MC.World.explode", "MC.World.explode(x, y, z, fuseTicks?, power?, dimension?)", "生成可设置引信和威力的 TNT 爆炸", "true；命令已提交", "MC.World.explode(0, 64, 0, 0, 4)", false);
		add(entries, "MC.World.lightning", "MC.World.lightning(x, y, z, dimension?)", "在指定坐标生成闪电", "true；命令已提交", "MC.World.lightning(0, 64, 0)", false);
		add(entries, "MC.World.forceLoad", "MC.World.forceLoad(chunkX, chunkZ, dimension?)", "强制加载指定区块", "true；命令已提交", "MC.World.forceLoad(0, 0)", false);
		add(entries, "MC.World.unforceLoad", "MC.World.unforceLoad(chunkX, chunkZ, dimension?)", "取消区块强制加载", "true；命令已提交", "MC.World.unforceLoad(0, 0)", false);
		add(entries, "MC.World.isChunkLoaded", "MC.World.isChunkLoaded(chunkX, chunkZ, dimension?)", "检查区块当前是否加载", "布尔值", "print(MC.World.isChunkLoaded(0, 0))", false);

		add(entries, "MC.Effect.give", "MC.Effect.give(uuid, effectId, duration?, amplifier?, showParticles?)", "给予有生命实体（LivingEntity，包括玩家）状态效果", "true；命令已提交", "MC.Effect.give(\"实体UUID\", \"minecraft:speed\", 30, 1, true)", false);
		add(entries, "MC.Effect.clear", "MC.Effect.clear(uuid, effectId?)", "移除有生命实体（LivingEntity，包括玩家）的一个或全部状态效果", "true；命令已提交", "MC.Effect.clear(\"实体UUID\", \"minecraft:speed\")", false);
		add(entries, "MC.Effect.list", "MC.Effect.list(uuid)", "读取有生命实体（LivingEntity，包括玩家）当前全部状态效果", "效果信息数组", "for _, e in ipairs(MC.Effect.list(\"实体UUID\")) do print(e.id, e.duration) end", false);

		entries.put("MC.Sound.play", new Entry("MC.Sound.play(soundId, x?, y?, z?, volume?, pitch?, category?, dimension?, playerUuid?, startSeconds?)",
				"播放位置声音或全维度／指定维度广播，可从指定秒数开始", "true；播放请求已提交，不代表客户端音频已解码完成",
				"MC.Sound.play(\"minecraft:block.note_block.pling\", nil, nil, nil, 1, 1, \"master\", nil)", false,
				"x、y、z 必须同时为数字，或同时为 nil（可省略）。坐标全为 nil 时不分坐标，向 dimension 指定维度的全部玩家广播；"
				+ "dimension 省略或为 nil 时向全服所有维度广播，不受坐标和距离限制；要播放有距离衰减的位置声音，必须填写三个坐标并明确传入维度。"
				+ "playerUuid 省略或为 nil 表示全部目标玩家，填写 UUID 则仍只向该在线玩家播放，并遵守指定维度筛选。广播声音跟随听者，音量不会因远离某个固定坐标而衰减。"
				+ "普通广播使用原版声音协议，不要求普通玩家安装本模组。"
				+ "startSeconds 默认 0，单位秒，可用小数，表示音频文件起播位置而非延时。省略或为 0 时普通玩家无需安装本模组；"
				+ "【非零起播时间仅强制客户端模式】服务器必须开启要求玩家安装客户端模组，且脚本启动时也已开启；目标客户端须安装兼容版本。"
				+ "声音从客户端当前已加载资源包解析，可使用其他模组或资源包 sounds.json 的声音事件 ID。"
				+ "保留游戏音量、音调、声音分类及 MC.Sound.stop；位置模式只播放给同维度且在音量对应范围内的玩家，广播模式不检查距离。"
				+ "音频总长度不足时不播放；随机声音事件定位的是本次随机选中的音频。定位解码在后台完成，单个 OGG 最大 32 MiB、总占用 128 MiB、最多同时 32 个定位声音。"));
		add(entries, "MC.Sound.stop", "MC.Sound.stop(playerUuid, soundId?, category?)", "停止玩家听到的指定声音或声音分类", "true；命令已提交", "MC.Sound.stop(\"玩家UUID\", nil, \"music\")", false);
		add(entries, "MC.Particle.spawn", "MC.Particle.spawn(particleId, x, y, z, count?, spread?, speed?, force?, dimension?)", "生成服务端粒子并发送给玩家", "true；命令已提交", "MC.Particle.spawn(\"minecraft:flame\", 0, 65, 0, 40, 1, 0.02, true)", false);

		add(entries, "MC.Title.show", "MC.Title.show(uuid, textOrJson)", "向玩家显示主标题；支持普通字符串、文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Title.show(\"玩家UUID\", {text = \"任务完成\", color = \"#55FFFF\", bold = true})", false);
		add(entries, "MC.Title.subtitle", "MC.Title.subtitle(uuid, textOrJson)", "向玩家显示副标题；支持普通字符串、文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Title.subtitle(\"玩家UUID\", {text = \"获得奖励\", italic = true})", false);
		add(entries, "MC.Title.actionbar", "MC.Title.actionbar(uuid, textOrJson)", "在玩家快捷栏上方显示文本组件", "true；命令已提交", "MC.Title.actionbar(\"玩家UUID\", \"{\\\"text\\\":\\\"生命恢复中\\\",\\\"color\\\":\\\"#FF5555\\\"}\")", false);
		add(entries, "MC.Title.times", "MC.Title.times(uuid, fadeIn, stay, fadeOut)", "设置玩家标题动画时间（Tick）", "true；命令已提交", "MC.Title.times(\"玩家UUID\", 10, 70, 20)", false);
		add(entries, "MC.Title.clear", "MC.Title.clear(uuid)", "清除玩家当前标题", "true；命令已提交", "MC.Title.clear(\"玩家UUID\")", false);
		add(entries, "MC.Title.reset", "MC.Title.reset(uuid)", "清除标题并恢复默认动画时间", "true；命令已提交", "MC.Title.reset(\"玩家UUID\")", false);

		add(entries, "MC.Score.create", "MC.Score.create(objective, criteria?, displayNameOrJson?)", "创建计分板目标；显示名支持文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Score.create(\"coins\", \"dummy\", {text = \"金币\", color = \"gold\"})", false);
		add(entries, "MC.Score.remove", "MC.Score.remove(objective)", "删除计分板目标", "true；命令已提交", "MC.Score.remove(\"coins\")", false);
		add(entries, "MC.Score.set", "MC.Score.set(holder, objective, value)", "设置玩家或虚拟名称的分数", "true；命令已提交", "MC.Score.set(\"玩家UUID\", \"coins\", 10)", false);
		add(entries, "MC.Score.add", "MC.Score.add(holder, objective, amount)", "增加或减少分数", "true；命令已提交", "MC.Score.add(\"玩家UUID\", \"coins\", 1)", false);
		add(entries, "MC.Score.reset", "MC.Score.reset(holder, objective?)", "清除一个或全部目标上的分数", "true；命令已提交", "MC.Score.reset(\"玩家UUID\", \"coins\")", false);
		add(entries, "MC.Score.display", "MC.Score.display(slot, objective?)", "设置或清空计分板显示位置", "true；命令已提交", "MC.Score.display(\"sidebar\", \"coins\")", false);

		add(entries, "MC.Team.create", "MC.Team.create(team, displayNameOrJson?)", "创建计分板队伍；显示名支持文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Team.create(\"red\", {text = \"红队\", color = \"red\"})", false);
		add(entries, "MC.Team.remove", "MC.Team.remove(team)", "删除队伍", "true；命令已提交", "MC.Team.remove(\"red\")", false);
		add(entries, "MC.Team.join", "MC.Team.join(team, holder)", "让任意实体加入队伍；支持在线玩家、生物、掉落物、展示实体的 UUID，也兼容玩家名和虚拟名称；自动退出原队伍", "true；已加入（重复加入同一队伍也返回 true）；队伍不存在时报错", "local entityUuid = MC.Entity.spawn(\"minecraft:pig\", 0, 64, 0)\nMC.Team.create(\"red\")\nMC.Team.join(\"red\", entityUuid)", false);
		add(entries, "MC.Team.leave", "MC.Team.leave(holder)", "让任意实体离开当前队伍；支持在线玩家、生物、掉落物、展示实体的 UUID，也兼容玩家名和虚拟名称", "boolean；true 表示已退出，false 表示原本不在队伍中", "MC.Team.leave(\"实体或玩家UUID\")", false);
		add(entries, "MC.Team.has", "MC.Team.has(team, holder)", "查询任意实体是否属于指定队伍；支持在线玩家、生物、掉落物、展示实体的 UUID，也兼容玩家名和虚拟名称", "boolean；属于指定队伍返回 true，否则返回 false；队伍不存在也返回 false", "local entityUuid = MC.Entity.spawn(\"minecraft:pig\", 0, 64, 0)\nMC.Team.create(\"red\")\nMC.Team.join(\"red\", entityUuid)\nprint(MC.Team.has(\"red\", entityUuid)) -- true", false);
		add(entries, "MC.Team.color", "MC.Team.color(team, color)", "设置队伍颜色", "true；命令已提交", "MC.Team.color(\"red\", \"red\")", false);
		add(entries, "MC.Team.friendlyFire", "MC.Team.friendlyFire(team, enabled)", "设置队伍友军伤害", "true；命令已提交", "MC.Team.friendlyFire(\"red\", false)", false);
		add(entries, "MC.Team.prefix", "MC.Team.prefix(team, textOrJson)", "设置队伍名称前缀；支持文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Team.prefix(\"red\", {text = \"[红队] \", color = \"#FF5555\", bold = true})", false);
		add(entries, "MC.Team.suffix", "MC.Team.suffix(team, textOrJson)", "设置队伍名称后缀；支持文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Team.suffix(\"red\", {text = \" ★\", color = \"#FFFF55\"})", false);

		add(entries, "MC.GameRule.names", "MC.GameRule.names()", "列出当前版本注册的全部游戏规则资源 ID", "字符串数组", "for _, id in ipairs(MC.GameRule.names()) do print(id) end", false);
		entries.put("MC.GameRule.set", new Entry("MC.GameRule.set(ruleId, value, dimension?)",
				"设置服务端游戏规则", "true；命令已提交",
				"MC.GameRule.set(\"minecraft:keep_inventory\", true)", false,
				"第一个参数是游戏规则名称／资源 ID，不是玩家 UUID。使用当前版本注册的 ID；可用 MC.GameRule.names() 查看全部规则。"
				+ "规则参数支持 Tab 补全、中文名称和拼音首字母搜索；Ctrl+I 自动打开游戏规则分类，包括服务端模组注册的规则。"
				+ "布尔规则传 true/false，整数规则传整数，也可使用命令接受的字符串值。普通玩家无需安装本模组。",
				Map.of("ruleId", "游戏规则名称／资源 ID（字符串），例如 minecraft:keep_inventory；不是玩家 UUID",
						"value", "规则值：布尔规则传 true/false，整数规则传整数，也支持字符串；须符合所选规则类型"),
				List.of(McLuaIdeApi.ValueType.RESOURCE_ID, McLuaIdeApi.ValueType.ANY, McLuaIdeApi.ValueType.RESOURCE_ID),
				"mc_lua_ide", "", false));
		add(entries, "MC.Border.set", "MC.Border.set(size, seconds?)", "设置世界边界直径", "true；命令已提交", "MC.Border.set(1000, 10)", false);
		add(entries, "MC.Border.add", "MC.Border.add(amount, seconds?)", "增加或缩小世界边界", "true；命令已提交", "MC.Border.add(-100, 5)", false);
		add(entries, "MC.Border.center", "MC.Border.center(x, z)", "设置世界边界中心", "true；命令已提交", "MC.Border.center(0, 0)", false);
		add(entries, "MC.Border.warning", "MC.Border.warning(distance)", "设置世界边界警告距离", "true；命令已提交", "MC.Border.warning(10)", false);
		add(entries, "MC.Border.damage", "MC.Border.damage(amount)", "设置越界每格伤害", "true；命令已提交", "MC.Border.damage(0.2)", false);

		add(entries, "MC.Attribute.setBase", "MC.Attribute.setBase(uuid, attributeId, value)", "设置有生命实体（LivingEntity，包括玩家）已有属性的基础值", "true；命令已提交", "MC.Attribute.setBase(\"实体UUID\", \"minecraft:max_health\", 40)", false);
		add(entries, "MC.Attribute.addModifier", "MC.Attribute.addModifier(uuid, attributeId, modifierId, amount, operation)", "为有生命实体（LivingEntity，包括玩家）已有属性添加永久修改器", "true；命令已提交", "MC.Attribute.addModifier(\"实体UUID\", \"minecraft:movement_speed\", \"mymod:boost\", 0.2, \"add_multiplied_base\")", false);
		add(entries, "MC.Attribute.removeModifier", "MC.Attribute.removeModifier(uuid, attributeId, modifierId)", "移除有生命实体（LivingEntity，包括玩家）的指定属性修改器", "true；命令已提交", "MC.Attribute.removeModifier(\"实体UUID\", \"minecraft:movement_speed\", \"mymod:boost\")", false);
		add(entries, "MC.Advancement.grant", "MC.Advancement.grant(uuid, advancementId)", "授予玩家完整进度", "true；命令已提交", "MC.Advancement.grant(\"玩家UUID\", \"minecraft:story/mine_stone\")", false);
		add(entries, "MC.Advancement.revoke", "MC.Advancement.revoke(uuid, advancementId)", "撤销玩家完整进度", "true；命令已提交", "MC.Advancement.revoke(\"玩家UUID\", \"minecraft:story/mine_stone\")", false);
		add(entries, "MC.Recipe.give", "MC.Recipe.give(uuid, recipeId)", "解锁玩家配方", "true；命令已提交", "MC.Recipe.give(\"玩家UUID\", \"minecraft:crafting_table\")", false);
		add(entries, "MC.Recipe.take", "MC.Recipe.take(uuid, recipeId)", "收回玩家配方", "true；命令已提交", "MC.Recipe.take(\"玩家UUID\", \"minecraft:crafting_table\")", false);
		add(entries, "MC.Recipe.forItem", "MC.Recipe.forItem(itemId)", "按产物 ID 查询工作台合成配方，包含数据包与模组配方", "配方数组；每项的 grid[1..9] 是可直接传给 fromGrid 的物品 ID，ingredients[1..9] 是各格全部可选 ID；ordered/unordered 标记有序/无序", "for _, recipe in ipairs(MC.Recipe.forItem(\"minecraft:crafting_table\")) do print(recipe.id, MC.Recipe.fromGrid(recipe.grid).id) end", false);
		add(entries, "MC.Recipe.fromGrid", "MC.Recipe.fromGrid(grid)", "传入 1～9 格物品 ID 数组，按工作台实际规则查询合成产物；空格填 nil", "匹配时返回物品表（id、count、components、recipeId、ordered、unordered），无匹配返回 nil", "local result = MC.Recipe.fromGrid({\"minecraft:oak_planks\", \"minecraft:oak_planks\", nil, \"minecraft:oak_planks\", \"minecraft:oak_planks\"}); print(result and result.id)", false);
		add(entries, "MC.Recipe.usingItem", "MC.Recipe.usingItem(grid)", "MC.Recipe.fromGrid(grid) 的兼容别名；参数是九宫格数组，不是单个物品 ID", "匹配时返回产物物品表，无匹配返回 nil", "local result = MC.Recipe.usingItem({\"minecraft:oak_planks\", \"minecraft:oak_planks\", nil, \"minecraft:oak_planks\", \"minecraft:oak_planks\"}); print(result and result.id)", false);
		add(entries, "MC.Pack.putData", "MC.Pack.putData(kind, id, json)", "在当前世界生成服务端数据包 JSON，并执行完整服务端数据重载；可能造成短暂卡顿，维度和世界生成类还需重启世界。普通玩家无需安装本模组", "状态表：status、path、reloadCompleted、requiresRestart、applied、message；重载完成不保证单条 JSON 被游戏接受，请检查服务端日志", "local r = MC.Pack.putData(\"tags/block\", \"example:building\", [[{\"values\":[\"minecraft:stone\"]}]]); print(r.status, r.message)", false);
		add(entries, "MC.Pack.putDataBatch", "MC.Pack.putDataBatch(entries)", "批量写入最多 256 条数据包 JSON，只进行一次完整服务端重载；失败时恢复本批次原文件", "状态表：status、count、path、reloadCompleted、requiresRestart、applied、message", "MC.Pack.putDataBatch({{kind=\"tags/block\", id=\"example:a\", json=[[{\"values\":[\"minecraft:stone\"]}]]}, {kind=\"tags/block\", id=\"example:b\", json=[[{\"values\":[\"minecraft:dirt\"]}]]}})", false);
		add(entries, "MC.Pack.putAssetJson", "MC.Pack.putAssetJson(kind, id, json)", "写入原版客户端资源包 JSON，如 items、models/item、blockstates；需手动导出并发布给玩家", "状态表；status 为 needs_export_and_distribution", "MC.Pack.putAssetJson(\"items\", \"example:my_item\", jsonText)", false);
		add(entries, "MC.Pack.putAssetPng", "MC.Pack.putAssetPng(kind, id, pngBase64)", "写入客户端资源包 PNG，如 textures/item 或 textures/block", "状态表；status 为 needs_export_and_distribution", "MC.Pack.putAssetPng(\"textures/item\", \"example:my_item\", pngBase64)", false);
		add(entries, "MC.Pack.putTexture", "MC.Pack.putTexture(id, pngBase64)", "putAssetPng('textures/item', id, pngBase64) 的快捷写法", "状态表；需导出并发布 ZIP", "MC.Pack.putTexture(\"example:my_item\", pngBase64)", false);
		add(entries, "MC.Pack.exportAssets", "MC.Pack.exportAssets()", "打包导出当前世界的生成资源包 ZIP，供管理员手动发布给原版客户端玩家", "状态表；path 为 ZIP 绝对路径，status 为 ready_to_distribute", "local r = MC.Pack.exportAssets(); print(r.path)", false);
		add(entries, "MC.Loot.give", "MC.Loot.give(uuid, lootTableId)", "将战利品表生成物给予玩家", "true；命令已提交", "MC.Loot.give(\"玩家UUID\", \"minecraft:chests/simple_dungeon\")", false);
		add(entries, "MC.Loot.insert", "MC.Loot.insert(x, y, z, lootTableId, dimension?)", "把战利品表生成物插入容器", "true；命令已提交", "MC.Loot.insert(0, 64, 0, \"minecraft:chests/simple_dungeon\")", false);
		add(entries, "MC.Structure.place", "MC.Structure.place(structureId, x, y, z, dimension?)", "放置已注册结构模板", "true；命令已提交", "MC.Structure.place(\"minecraft:village/plains/houses/plains_small_house_1\", 0, 64, 0)", false);

		add(entries, "MC.Registry.kinds", "MC.Registry.kinds()", "列出支持查询的注册表类型", "注册表类型字符串数组", "for _, kind in ipairs(MC.Registry.kinds()) do print(kind) end", false);
		add(entries, "MC.Registry.list", "MC.Registry.list(kind)", "按类型列出运行时全部资源 ID，包含其他 Mod", "资源 ID 字符串数组", "for _, id in ipairs(MC.Registry.list(\"sounds\")) do print(id) end", false);
		add(entries, "MC.Registry.blocks", "MC.Registry.blocks()", "列出全部方块资源 ID", "字符串数组", "print(#MC.Registry.blocks())", false);
		add(entries, "MC.Registry.items", "MC.Registry.items()", "列出全部物品资源 ID", "字符串数组", "print(#MC.Registry.items())", false);
		add(entries, "MC.Registry.entities", "MC.Registry.entities()", "列出全部实体类型资源 ID", "字符串数组", "print(#MC.Registry.entities())", false);
		add(entries, "MC.Registry.effects", "MC.Registry.effects()", "列出全部状态效果资源 ID", "字符串数组", "print(#MC.Registry.effects())", false);
		add(entries, "MC.Registry.sounds", "MC.Registry.sounds()", "列出全部声音事件资源 ID", "字符串数组", "print(#MC.Registry.sounds())", false);
		add(entries, "MC.Registry.particles", "MC.Registry.particles()", "列出全部粒子类型资源 ID", "字符串数组", "print(#MC.Registry.particles())", false);
		add(entries, "MC.Registry.potions", "MC.Registry.potions()", "列出全部药水资源 ID", "字符串数组", "print(#MC.Registry.potions())", false);
		add(entries, "MC.Registry.gameRules", "MC.Registry.gameRules()", "列出全部游戏规则资源 ID", "字符串数组", "print(#MC.Registry.gameRules())", false);
		add(entries, "MC.Registry.dataComponents", "MC.Registry.dataComponents()", "列出全部物品数据组件类型，包含其他 Mod", "组件 ID 字符串数组", "for _, id in ipairs(MC.Registry.dataComponents()) do print(id) end", false);

		add(entries, "MC.Server.tickCount", "MC.Server.tickCount()", "读取服务端已运行 Tick 数", "整数", "print(MC.Server.tickCount())", false);
		add(entries, "MC.Server.maxPlayers", "MC.Server.maxPlayers()", "读取最大玩家数量", "整数", "print(MC.Server.maxPlayers())", false);
		add(entries, "MC.Server.viewDistance", "MC.Server.viewDistance()", "读取服务端视距", "整数区块数", "print(MC.Server.viewDistance())", false);
		add(entries, "MC.Server.simulationDistance", "MC.Server.simulationDistance()", "读取模拟距离", "整数区块数", "print(MC.Server.simulationDistance())", false);
		add(entries, "MC.Server.tickRate", "MC.Server.tickRate()", "读取当前每秒 Tick 速率", "数字", "print(MC.Server.tickRate())", false);
		add(entries, "MC.Server.setTickRate", "MC.Server.setTickRate(rate)", "设置服务端 Tick 速率", "true", "MC.Server.setTickRate(20)", false);
		add(entries, "MC.Server.frozen", "MC.Server.frozen()", "检查游戏 Tick 是否冻结", "布尔值", "print(MC.Server.frozen())", false);
		add(entries, "MC.Server.setFrozen", "MC.Server.setFrozen(value)", "冻结或恢复游戏 Tick", "true", "MC.Server.setFrozen(true)", false);
		add(entries, "MC.Server.step", "MC.Server.step(ticks?)", "冻结状态下步进指定 Tick", "true", "MC.Server.step(20)", false);
		add(entries, "MC.Server.save", "MC.Server.save()", "立即保存全部世界数据", "是否保存成功", "print(MC.Server.save())", false);

		add(entries, "MC.UI.available", "MC.UI.available()", "检查当前服务端是否支持原版自定义界面", "true", "assert(MC.UI.available())", false);
		entries.put("MC.UI.register", new Entry("MC.UI.register(uiId, definition)",
				"注册当前脚本可重复打开的原版自定义界面", "true",
				"MC.UI.register(\"menu\", {title=\"菜单\", buttons={{id=\"ok\", label=\"确定\"}}})", false,
				"definition 支持 form/notice/confirmation、正文、按钮、退出按钮，以及 text/boolean/number/select 输入；完整模板可点“插入”"));
		add(entries, "MC.UI.unregister", "MC.UI.unregister(uiId)", "移除当前脚本注册的界面", "是否找到并移除", "MC.UI.unregister(\"menu\")", false);
		add(entries, "MC.UI.clearRegistered", "MC.UI.clearRegistered()", "移除当前脚本注册的全部界面", "移除数量", "print(MC.UI.clearRegistered())", false);
		add(entries, "MC.UI.list", "MC.UI.list()", "列出当前脚本已注册的界面 ID", "界面 ID 字符串数组", "for _, id in ipairs(MC.UI.list()) do print(id) end", false);
		add(entries, "MC.UI.open", "MC.UI.open(playerUuid, uiId)", "向指定在线玩家打开已注册的原版界面", "成功时 true，否则抛出中文错误", "MC.UI.open(uuid, \"menu\")", false);
		entries.put("MC.UI.show", new Entry("MC.UI.show(playerUuid, uiId, definition)",
				"不注册，直接向指定在线玩家打开一次原版界面", "成功时 true，否则抛出中文错误",
				"MC.UI.show(uuid, \"notice\", {type=\"notice\", title=\"提示\", body=\"完成\"})", false,
				"uiId 会随按钮事件返回；definition 结构与 MC.UI.register 相同"));
		add(entries, "MC.UI.close", "MC.UI.close(playerUuid)", "关闭指定玩家当前的原版自定义界面", "是否存在由当前 API 打开的会话", "MC.UI.close(uuid)", false);

		addClient(entries, "MC.Client.available", "MC.Client.available(playerUuid)", "检查目标玩家客户端是否满足客户端 API 条件", "布尔值", "print(MC.Client.available(uuid))");
		addClient(entries, "MC.Client.info", "MC.Client.info(playerUuid)", "读取目标客户端兼容状态", "{playerUuid, online, required, available, protocol}", "local info = MC.Client.info(uuid)");
		addClient(entries, "MC.Client.list", "MC.Client.list()", "列出安装了兼容客户端模组的在线玩家", "{uuid, name, protocol} 数组", "for _, p in ipairs(MC.Client.list()) do print(p.uuid) end");
		addClient(entries, "MC.Client.message", "MC.Client.message(playerUuid, textOrJson)", "只在目标客户端聊天栏显示本地系统消息；支持文本组件 JSON 或 Lua 表", "数据包发送成功时 true", "MC.Client.message(uuid, {text = \"仅你可见\", color = \"#55FFFF\"})");
		addClient(entries, "MC.Client.toast", "MC.Client.toast(playerUuid, titleOrJson, messageOrJson)", "在目标客户端右上角显示支持 JSON 样式的系统 Toast", "数据包发送成功时 true", "MC.Client.toast(uuid, {text = \"完成\", color = \"gold\", bold = true}, \"任务已经完成\")");
		addClient(entries, "MC.Client.notice", "MC.Client.notice(playerUuid, titleOrJson, bodyOrJson)", "在目标客户端打开支持 JSON 样式的通知页面", "数据包发送成功时 true", "MC.Client.notice(uuid, {text = \"公告\", color = \"#FFAA00\"}, \"欢迎进入服务器\")");
		addClient(entries, "MC.Client.closeNotice", "MC.Client.closeNotice(playerUuid)", "关闭由 MC.Client.notice 打开的客户端页面", "数据包发送成功时 true", "MC.Client.closeNotice(uuid)");
		addClient(entries, "MC.Client.openUrlPrompt", "MC.Client.openUrlPrompt(playerUuid, url)", "让客户端显示安全链接确认框，不会自动打开", "数据包发送成功时 true", "MC.Client.openUrlPrompt(uuid, \"https://example.com\")");
		addClient(entries, "MC.Client.openIde", "MC.Client.openIde(playerUuid)", "请求目标客户端打开 Lua IDE；仍需通过管理员权限校验", "数据包发送成功时 true", "MC.Client.openIde(uuid)");
		addClient(entries, "MC.Client.hasResourcePack", "MC.Client.hasResourcePack(playerUuid, packName)", "询问目标客户端是否已启用指定名称或 ID 的材质包", "已启用时 true；未找到或未启用时 false", "if MC.Client.hasResourcePack(uuid, \"My Pack\") then print(\"已启用\") end");
		addClient(entries, "MC.Client.enableResourcePack", "MC.Client.enableResourcePack(playerUuid, packName)", "在目标客户端选择指定名称或 ID 的本地材质包并等待资源重载完成", "成功启用时 true；找不到或重载失败会抛出中文错误", "MC.Client.enableResourcePack(uuid, \"My Pack\")");

		addEvent(entries, "MC.Event.on", "MC.Event.on(eventName, callback, intervalTicks?)", "按事件名称注册通用服务端回调；interval 事件的第三个参数是毫秒，默认 1000", "事件注册 ID（整数）", "type，以及所选事件对应的字段");
		entries.put("MC.Event.onScriptStarted", new Entry("MC.Event.onScriptStarted(callback)",
				"监听当前脚本开始运行，一次性启动回调", "事件注册 ID（整数）",
				"MC.Event.onScriptStarted(function(event) print(\"已启动\", event.scriptName) end)", false,
				"event.type = \"scriptStarted\"；字段：registrationId, scriptName, ownerUuid, startedAt（Unix 毫秒）, elapsedMs（脚本启动后实际经过的毫秒）。"
				+ "注册后在下一次调度时触发一次：主代码返回或调用 MC.sleep 让出执行权后执行；同一回调不会重复触发。"
				+ "如果需要在主代码第一条操作之前初始化，请直接写在文件开头。启动回调可以注册游戏事件。"
				+ "支持 MC.Event.on(\"scriptStarted\", callback)、off / clear；执行前注销则不触发。不会单独保持脚本运行；受服务器事件权限和监听数量限制，普通玩家无需客户端模组。"));
		entries.put("MC.Event.onScriptStopped", new Entry("MC.Event.onScriptStopped(callback)",
				"监听当前脚本结束，在清理阶段执行一次", "事件注册 ID（整数）",
				"MC.Event.onScriptStopped(function(event) print(\"已结束\", event.reason, event.elapsedMs) end)", false,
				"event.type = \"scriptStopped\"；字段：registrationId, scriptName, ownerUuid, startedAt, elapsedMs, reason, stopped, success, error（没有错误时为 nil）。"
				+ "reason = completed（正常结束）、stopped（主动停止，包括退出/关闭服务器时的停止请求）、error（运行错误或超时）。"
				+ "旧执行流程退出后，按注册顺序执行清理回调；支持 print 和当前已授权的 MC 游戏 API，并保留脚本局部变量/闭包。"
				+ "不会单独让脚本常驻；启动/停止监听也可用 MC.Event.on 注册，off / clear 可注销。"
				+ "清理时不能使用 MC.sleep / MC.wait 或注册新事件，不进入断点暂停；单回调最多 1 秒，全部回调合计最多 2 秒。"
				+ "回调错误写入日志并尝试下一个回调，不覆盖原始结束原因；服务器完全关闭、进程崩溃或强制终止时不能保证游戏 API/回调完成。"
				+ "受服务器事件权限和监听数量限制，普通玩家无需客户端模组。"));
		entries.put("MC.Event.onInterval", new Entry("MC.Event.onInterval(intervalMs, callback)",
				"按真实毫秒间隔重复执行服务端回调，不依赖游戏 Tick", "事件注册 ID（整数）",
				"MC.Event.onInterval(1000, function(event) print(event.count, event.elapsedMs) end)", false,
				"event.type = \"interval\"；字段：registrationId, intervalMs, count, scheduledCount, elapsedMs, deltaMs, actualElapsedMs, actualDeltaMs, lateMs, skippedCount, totalSkippedCount。"
				+ "首次执行等待一个间隔；count 是实际执行次数。elapsedMs 现为注册后计划经过的毫秒（scheduledCount × intervalMs），deltaMs 为计划时间差，不累计执行延迟。"
				+ "actualElapsedMs/actualDeltaMs 是实际经过时间/实际执行间隔；lateMs 是相对本次计划时间的延迟。scheduledCount 是计划周期序号；skippedCount/totalSkippedCount 是本次/累计跳过周期数。"
				+ "计时使用服务端单调时钟；系统调度、忙碌代码、服务端 API 与断点可能延后执行，不保证硬实时。"
				+ "同一监听不会重叠或积压补发：回调（含 MC.sleep）未完成时跳过该监听的重复触发；其他监听仍可运行。"
				+ "也可用 MC.Event.on(\"interval\", callback, intervalMs) 注册。"
				+ "MC.Event.off / clear、停止脚本、脚本错误和运行时间限制会移除监听；已开始的回调不会被 off / clear 强制中断，停止脚本会中断。"
				+ "受服务器事件权限、监听数量和执行队列容量限制；普通玩家不需要客户端模组。"));
		add(entries, "MC.Event.names", "MC.Event.names()", "列出当前支持的全部事件名称", "事件名称字符串数组", "for _, name in ipairs(MC.Event.names()) do print(name) end", false);
		addEvent(entries, "MC.Event.onBlockPlaced", "MC.Event.onBlockPlaced(callback)", "监听玩家成功放置方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, x, y, z, dimension, hand, itemId, itemCount, itemComponents");
		addEvent(entries, "MC.Event.onBlockBroken", "MC.Event.onBlockBroken(callback)", "监听玩家成功破坏方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, x, y, z, dimension, hadBlockEntity");
		Entry blockBroken = entries.get("MC.Event.onBlockBroken");
		entries.put("MC.Event.onBlockBroken", new Entry(blockBroken.signature(), blockBroken.summary, blockBroken.rawReturns(),
				blockBroken.example(), false, blockBroken.rawDetails()
				+ "；包括创造模式挖床头／床尾等联动拆除，每次操作按玩家实际挖掘位置触发一次，使用破坏前的方块 ID 和坐标。"
				+ "兼容 Axiom 的特殊挖掘路径；普通编辑、命令和脚本移除方块不算玩家挖掘，仍遵守模组原有的权限和取消结果。"
				+ "另一半自动消失不额外触发；被取消、权限拒绝或挖空气不触发，不改变原版掉落和拆除行为。"));
		addEvent(entries, "MC.Event.onBlockAttacked", "MC.Event.onBlockAttacked(callback)", "监听玩家左键攻击方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, x, y, z, dimension, hand, face");
		addEvent(entries, "MC.Event.onBlockUsed", "MC.Event.onBlockUsed(callback)", "监听玩家右键使用方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, itemId, itemCount, itemComponents, x, y, z, dimension, hand, face");
		addEvent(entries, "MC.Event.onItemUsed", "MC.Event.onItemUsed(callback)", "监听玩家使用手中物品", "事件注册 ID（整数）", "playerUuid, playerName, itemId, itemCount, itemComponents, count, hand, x, y, z, dimension");
		addEvent(entries, "MC.Event.onContainerClicked", "MC.Event.onContainerClicked(callback)", "监听玩家点击容器／背包菜单（含只读虚拟商店）；不代表成功拿取。回调异步执行，不支持事后取消点击；只读模式在服务端原子阻止全部取放。clickType=PICKUP/QUICK_MOVE/SWAP/THROW/QUICK_CRAFT/CLONE/PICKUP_ALL", "事件注册 ID（整数）", "playerUuid, playerName, containerId, containerType, containerTitle（虚拟箱子）, virtual, readOnly, slotId（0起）, containerSlotId, slotArea（container/inventory/outside）, clickType, button, changed, itemId, count, itemComponents, item, cursorBefore, cursorAfter, x/y/z/dimension（无世界位置时 nil）, playerX/playerY/playerZ/playerDimension");
		addEvent(entries, "MC.Event.onContainerItemTaken", "MC.Event.onContainerItemTaken(callback)", "监听玩家操作导致的容器槽位物品实际减少，支持左／右键、Shift、数字键交换、拖放、丢弃、双击归拢；交换不同物品会分别发出取出／放入事件。只读拒绝、纯 API 修改、漏斗自动搬运不触发；回调不能取消已完成动作", "事件注册 ID（整数）", "playerUuid, playerName, containerId, containerType, virtual, readOnly, slotId（菜单0起）, containerSlotId（底层容器0起）, clickedSlotId, slotArea, itemId, count（本次减少量）, itemComponents, item, before, after, cursorBefore, cursorAfter, clickType, button, x/y/z/dimension（未知或无世界位置 nil）, playerX/playerY/playerZ/playerDimension");
		addEvent(entries, "MC.Event.onContainerItemPlaced", "MC.Event.onContainerItemPlaced(callback)", "监听玩家操作导致的容器槽位物品实际增加；count 为本次放入量而非最终总量。无坐标的虚拟菜单返回 nil 坐标；纯 API 修改、漏斗、未改变物品的点击不触发", "事件注册 ID（整数）", "playerUuid, playerName, containerId, containerType, virtual, slotId, containerSlotId, clickedSlotId, itemId, count, itemComponents, item, before, after, cursorBefore, cursorAfter, clickType, button, x/y/z/dimension（无世界位置 nil）");
		addEvent(entries, "MC.Event.onContainerItemToCursor", "MC.Event.onContainerItemToCursor(callback)", "监听物品从非玩家背包的容器格子实际进入鼠标光标；Shift 快移／数字键只进背包而不进鼠标时不触发；右键分半、换持不同物品、双击从多个格子归拢时按来源格子报告", "事件注册 ID（整数）", "playerUuid, playerName, containerId, containerType, slotId, containerSlotId, itemId, count（本次进入光标数量）, itemComponents, item, before, after, cursorBefore, cursorAfter, clickType, button, x/y/z/dimension（无世界位置 nil）");
		addEvent(entries, "MC.Event.onInventoryItemToCursor", "MC.Event.onInventoryItemToCursor(callback)", "监听玩家从自己的背包格子把物品实际拿到鼠标上；在普通背包及打开其他容器时均生效。slotId 为当前菜单槽号，containerSlotId 为背包实际槽号；不是玩家拾取地上的掉落物事件", "事件注册 ID（整数）", "playerUuid, playerName, containerId, containerType, slotId, containerSlotId, slotArea=inventory, itemId, count, itemComponents, item, before, after, cursorBefore, cursorAfter, clickType, button, x/y/z/dimension（背包自身无世界位置时 nil）");
		addEvent(entries, "MC.Event.onEntityAttacked", "MC.Event.onEntityAttacked(callback)", "监听玩家攻击实体", "事件注册 ID（整数）", "playerUuid, playerName, hand, targetUuid, targetType, targetName, targetRuntimeId, targetX, targetY, targetZ");
		addEvent(entries, "MC.Event.onEntityUsed", "MC.Event.onEntityUsed(callback)", "监听玩家右键交互实体", "事件注册 ID（整数）", "playerUuid, playerName, hand, itemId, itemCount, itemComponents，以及 target* 实体字段");
		addEvent(entries, "MC.Event.onEntityLoaded", "MC.Event.onEntityLoaded(callback)", "监听实体加载到服务端世界", "事件注册 ID（整数）", "entityUuid, entityType, entityName, entityRuntimeId, entityX, entityY, entityZ, dimension");
		addEvent(entries, "MC.Event.onEntityUnloaded", "MC.Event.onEntityUnloaded(callback)", "监听实体从服务端世界卸载", "事件注册 ID（整数）", "entityUuid, entityType, entityName, entityRuntimeId, entityX, entityY, entityZ, dimension");
		addEvent(entries, "MC.Event.onEntityChangedDimension", "MC.Event.onEntityChangedDimension(callback)", "监听非玩家实体切换维度", "事件注册 ID（整数）", "entity* 字段, originalUuid, fromDimension, toDimension");
		addEvent(entries, "MC.Event.onEntityDamaged", "MC.Event.onEntityDamaged(callback)", "监听有生命实体（LivingEntity，包括玩家）伤害结算后的结果；致死伤害使用 onEntityDied", "事件注册 ID（整数）", "entity* 字段, damageType, baseDamage, damageTaken, blocked, remainingHealth，以及可选 attacker*/direct* 字段");
		addEvent(entries, "MC.Event.onEntityDied", "MC.Event.onEntityDied(callback)", "监听实体死亡（仅 LivingEntity 死亡流程，包括玩家）；不包括掉落物、展示实体等的移除，也不包括直接移除或区块卸载；只监听玩家请使用 onPlayerDied", "事件注册 ID（整数）", "entity* 字段, dimension, damageType，以及可选 attacker*/direct* 字段");
		addEvent(entries, "MC.Event.onEntityKilledOther", "MC.Event.onEntityKilledOther(callback)", "监听实体杀死另一个有生命实体（LivingEntity，包括玩家）", "事件注册 ID（整数）", "killer* 字段, killed* 字段, damageType, dimension，以及可选 attacker*/direct* 字段");
		addEvent(entries, "MC.Event.onProjectileHitBlock", "MC.Event.onProjectileHitBlock(callback)", "监听任意弹射物（箭、三叉戟、雪球、火球、风弹及模组弹射物）击中方块，在弹射物作出反应之前触发", "事件注册 ID（整数）",
				"projectileUuid, projectileType, projectileName, projectileRuntimeId, projectileX, projectileY, projectileZ, hasOwner, ownerUuid, ownerType, ownerName, ownerRuntimeId, ownerX, ownerY, ownerZ, playerUuid, playerName, hitX, hitY, hitZ, blockId, x, y, z, face, inside, dimension。hasOwner 为 false 时没有 owner* 字段；射击者是玩家时才有 playerUuid／playerName；hitX／hitY／hitZ 是精确命中点，x／y／z 是被击中方块的坐标，face 是被击中的面");
		addEvent(entries, "MC.Event.onProjectileHitEntity", "MC.Event.onProjectileHitEntity(callback)", "监听任意弹射物击中实体，在弹射物作出反应之前触发", "事件注册 ID（整数）",
				"projectileUuid, projectileType, projectileName, projectileRuntimeId, projectileX, projectileY, projectileZ, hasOwner, ownerUuid, ownerType, ownerName, ownerRuntimeId, ownerX, ownerY, ownerZ, playerUuid, playerName, hitX, hitY, hitZ, targetUuid, targetType, targetName, targetRuntimeId, targetX, targetY, targetZ, targetIsPlayer, targetPlayerName, dimension。hasOwner 为 false 时没有 owner* 字段；射击者是玩家时才有 playerUuid／playerName；被击中的是玩家时 targetIsPlayer 为 true 并带有 targetPlayerName");
		addEvent(entries, "MC.Event.onEquipmentChanged", "MC.Event.onEquipmentChanged(callback)", "监听有生命实体（LivingEntity，包括玩家）装备槽发生变化", "事件注册 ID（整数）", "entity* 字段, slot, oldItemId, oldItemCount, oldItemComponents, oldCount, newItemId, newItemCount, newItemComponents, newCount, dimension");
		addEvent(entries, "MC.Event.onPlayerJoined", "MC.Event.onPlayerJoined(callback)", "监听玩家加入服务器", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension");
		addEvent(entries, "MC.Event.onPlayerLeft", "MC.Event.onPlayerLeft(callback)", "监听玩家离开服务器", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension");
		addEvent(entries, "MC.Event.onPlayerDied", "MC.Event.onPlayerDied(callback)", "监听玩家确认死亡；不包含普通生物或被不死图腾等机制阻止的死亡，不可取消；停止脚本自动注销，普通玩家无需安装模组", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension, damageType（原版伤害消息类型，如 fall / genericKill）, deathMessage（原版死亡消息纯文本）；可选 attackerUuid/Type/Name/RuntimeId/X/Y/Z（攻击者）、directUuid/Type/Name/RuntimeId/X/Y/Z（直接来源，例如箭）。环境伤害不存在来源时这些字段为 nil；回调异步执行，坐标等为死亡发生时的快照");
		addEvent(entries, "MC.Event.onPlayerRespawned", "MC.Event.onPlayerRespawned(callback)", "监听玩家复活／重生完成；只监听死亡后复活时判断 not event.alive；停止脚本自动注销，普通玩家无需安装模组", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension（重生后的玩家位置快照）, alive（布尔值：重生前是否存活，false 表示死亡后复活；true 表示存活时重建，例如从末地返回，并非重生后是否活着）。服务端在新玩家实体重建完成后通知，Lua 回调异步执行，不可取消重生；通用注册为 MC.Event.on(\"playerRespawned\", callback)");
		addEvent(entries, "MC.Event.onPlayerChangedDimension", "MC.Event.onPlayerChangedDimension(callback)", "监听玩家切换维度完成", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, fromDimension, toDimension");
		addEvent(entries, "MC.Event.onEffectAdded", "MC.Event.onEffectAdded(callback)", "监听有生命实体（LivingEntity，包括玩家）获得状态效果", "事件注册 ID（整数）", "entity* 字段, effectId, duration, amplifier, ambient, visible, fromCommand, dimension");
		addEvent(entries, "MC.Event.onEffectRemoved", "MC.Event.onEffectRemoved(callback)", "监听有生命实体（LivingEntity，包括玩家）移除状态效果", "事件注册 ID（整数）", "entity* 字段, effectId, duration, amplifier, ambient, visible, fromCommand, dimension");
		addEvent(entries, "MC.Event.onSleepStarted", "MC.Event.onSleepStarted(callback)", "监听支持睡眠的有生命实体（LivingEntity，包括玩家）开始睡眠", "事件注册 ID（整数）", "entity* 字段, bedX, bedY, bedZ, dimension");
		addEvent(entries, "MC.Event.onSleepStopped", "MC.Event.onSleepStopped(callback)", "监听支持睡眠的有生命实体（LivingEntity，包括玩家）停止睡眠", "事件注册 ID（整数）", "entity* 字段, bedX, bedY, bedZ, dimension");
		addEvent(entries, "MC.Event.onChunkLoaded", "MC.Event.onChunkLoaded(callback)", "监听服务端区块加载", "事件注册 ID（整数）", "chunkX, chunkZ, minBlockX, minBlockZ, dimension, newChunk");
		addEvent(entries, "MC.Event.onChunkGenerated", "MC.Event.onChunkGenerated(callback)", "监听新区块完成生成", "事件注册 ID（整数）", "chunkX, chunkZ, minBlockX, minBlockZ, dimension");
		addEvent(entries, "MC.Event.onChunkUnloaded", "MC.Event.onChunkUnloaded(callback)", "监听服务端区块卸载", "事件注册 ID（整数）", "chunkX, chunkZ, minBlockX, minBlockZ, dimension");
		addEvent(entries, "MC.Event.onLevelLoaded", "MC.Event.onLevelLoaded(callback)", "监听服务端维度加载", "事件注册 ID（整数）", "dimension, gameTime, dayTime");
		addEvent(entries, "MC.Event.onLevelUnloaded", "MC.Event.onLevelUnloaded(callback)", "监听服务端维度卸载", "事件注册 ID（整数）", "dimension, gameTime, dayTime");
		addEvent(entries, "MC.Event.onServerTick", "MC.Event.onServerTick(callback, intervalTicks?)", "按间隔监听服务端游戏刻", "事件注册 ID（整数）", "tick, playerCount");
		addEvent(entries, "MC.Event.onLevelTick", "MC.Event.onLevelTick(callback, intervalTicks?)", "按间隔监听每个已加载维度的游戏刻", "事件注册 ID（整数）", "tick, dimension, gameTime, dayTime");
		addEvent(entries, "MC.Event.onChat", "MC.Event.onChat(callback)", "监听玩家发送的服务端聊天消息", "事件注册 ID（整数）", "playerUuid, playerName, message, decoratedMessage, x, y, z, dimension");
		addEvent(entries, "MC.Event.onCommand", "MC.Event.onCommand(callback)", "监听提交给服务端的全部命令", "事件注册 ID（整数）", "command, sourceName, source* 字段（如果有实体）, playerUuid/playerName（如果是玩家）, x, y, z, dimension");
		addEvent(entries, "MC.Event.onUiAction", "MC.Event.onUiAction(callback)", "监听玩家提交原版自定义界面的按钮和输入", "事件注册 ID（整数）", "playerUuid, playerName, uiId, actionId, inputs（按定义键名组成的表）");
		add(entries, "MC.Event.off", "MC.Event.off(registrationId)", "注销指定事件回调", "是否找到并注销该回调", "MC.Event.off(registrationId)", false);
		add(entries, "MC.Event.clear", "MC.Event.clear()", "注销当前脚本注册的全部事件回调", "已注销的回调数量", "MC.Event.clear()", false);

		add(entries, "MC.Limits.current", "MC.Limits.current()", "读取本次脚本启动时固定的服务端限制快照", "包含全部限制键的表", "local limits = MC.Limits.current(); print(limits.maxRuntimeSeconds)", false);
		add(entries, "MC.Script.require", "MC.Script.require(moduleName)", "安全加载服务端 scripts 子目录内的 Lua 模块；可用 shared.math 或 shared/math，同次运行只执行一次", "模块 return 的值；未返回时为 true", "local util = MC.Script.require(\"shared.util\"); print(util.hello())", false);
		add(entries, "MC.File.read", "MC.File.read(path)", "从当前脚本的隔离数据目录读取 UTF-8 文件", "文件内容字符串", "local text = MC.File.read(\"state.txt\")", false);
		add(entries, "MC.File.exists", "MC.File.exists(path)", "检查隔离数据目录中的文件是否存在", "布尔值", "print(MC.File.exists(\"state.txt\"))", false);
		add(entries, "MC.File.list", "MC.File.list(directory?)", "列出隔离数据目录中的文件", "相对路径字符串数组", "for _, path in ipairs(MC.File.list()) do print(path) end", false);
		add(entries, "MC.File.write", "MC.File.write(path, content)", "覆盖写入当前脚本的隔离 UTF-8 数据文件", "本次写入字节数", "MC.File.write(\"state.txt\", \"ready\")", false);
		add(entries, "MC.File.append", "MC.File.append(path, content)", "向当前脚本的隔离数据文件追加 UTF-8 内容", "本次追加字节数", "MC.File.append(\"events.log\", \"joined\\n\")", false);
		add(entries, "MC.File.delete", "MC.File.delete(path)", "删除当前脚本的隔离数据文件", "是否删除了文件", "MC.File.delete(\"state.txt\")", false);
		add(entries, "MC.Network.get", "MC.Network.get(url)", "发送受超时和大小限制的 HTTP/HTTPS GET 请求", "{status, body, headers}", "local r = MC.Network.get(\"https://example.com\"); print(r.status, r.body)", false);
		add(entries, "MC.Network.post", "MC.Network.post(url, body, contentType?)", "发送 UTF-8 HTTP/HTTPS POST 请求", "{status, body, headers}", "local r = MC.Network.post(\"https://example.com/api\", \"{}\", \"application/json\")", false);
		add(entries, "MC.Network.request", "MC.Network.request(method, url, body?, contentType?)", "发送 GET/POST/PUT/PATCH/DELETE/HEAD 请求，不自动重定向", "{status, body, headers}", "local r = MC.Network.request(\"HEAD\", \"https://example.com\")", false);
		return entries;
	}

	private static void add(Map<String, Entry> entries, String symbol, String signature, String summary,
			String returns, String example, boolean singleplayerOnly) {
		String details = symbol.matches("MC\\.(Block|Item|Player|Entity|Container|Chat|Effect|Title|Attribute|Advancement|Recipe|Loot|UI)\\..*")
				? "可恢复状态失败（区块未加载／玩家离线／实体不存在）时，返回 nil, 原因并显示黄色警告，后续代码继续执行；查询结果须判空。参数、权限、未知资源 ID 与超时等错误仍报错。"
				: "";
		entries.put(symbol, new Entry(signature, summary, returns, example, singleplayerOnly, details));
	}

	private static final class EventPayloads {
		static final Map<String, String> BY_SYMBOL = new java.util.concurrent.ConcurrentHashMap<>();
	}

	/** Field names of the {@code event} table passed to a callback of {@code MC.Event.on<Name>}; "type" is always present. */
	static List<String> eventFields(String eventName) {
		if (eventName == null || eventName.isBlank()) return List.of("type");
		find("MC.Event.on"); // makes sure the documentation (and with it the payload list) is built
		String name = Character.toUpperCase(eventName.charAt(0)) + eventName.substring(1);
		String payload = switch (name) {
			case "ScriptStarted" -> "registrationId, scriptName, ownerUuid, startedAt, elapsedMs";
			case "ScriptStopped" -> "registrationId, scriptName, ownerUuid, startedAt, elapsedMs, reason, stopped, success, error";
			case "Interval" -> "registrationId, intervalMs, count, scheduledCount, elapsedMs, deltaMs, actualElapsedMs, actualDeltaMs, lateMs, skippedCount, totalSkippedCount";
			default -> EventPayloads.BY_SYMBOL.get("MC.Event.on" + name);
		};
		List<String> fields = new ArrayList<>(List.of("type"));
		if (payload == null) return fields;
		for (String part : payload.split("[,，、]")) {
			var matcher = java.util.regex.Pattern.compile("^\s*([A-Za-z_][A-Za-z0-9_]*)").matcher(part);
			if (matcher.find() && !fields.contains(matcher.group(1))) fields.add(matcher.group(1));
		}
		return fields;
	}

	private static void addEvent(Map<String, Entry> entries, String symbol, String signature, String summary,
			String returns, String payload) {
		EventPayloads.BY_SYMBOL.put(symbol, payload);
		String eventName = symbol.equals("MC.Event.on") ? "" : symbol.substring("MC.Event.on".length());
		String example = symbol.equals("MC.Event.on")
				? "MC.Event.on(\"blockBroken\", function(event) print(event.blockId) end)"
				: symbol + "(function(event) print(event.type) end)";
		String details = symbol.equals("MC.Event.on")
				? "event.type 等于所选事件名称；字段：" + payload
				: "event.type = \"" + Character.toLowerCase(eventName.charAt(0)) + eventName.substring(1)
						+ "\"；字段：" + payload;
		entries.put(symbol, new Entry(signature, summary, returns, example, false, details));
	}

	private static void addClient(Map<String, Entry> entries, String symbol, String signature, String summary,
			String returns, String example) {
		entries.put(symbol, new Entry(signature, "【仅强制客户端模式】" + summary, returns, example, false,
				"限制：服务器必须开启“要求玩家安装客户端模组”，目标玩家必须在线并安装协议兼容版本；显示类 API 还受聊天、显示与界面权限控制"));
	}

	record Entry(String signature, String summary, String returns, String example, boolean singleplayerOnly,
			String details, Map<String, String> parameterDescriptions,
			List<McLuaIdeApi.ValueType> parameterTypes, String ownerModId, String namespaceLabel,
			boolean clientRequired) {
		Entry(String signature, String summary, String returns, String example, boolean singleplayerOnly,
				String details) {
			this(signature, summary, returns, example, singleplayerOnly, details,
					Map.of(), List.of(), "mc_lua_ide", "", signature.startsWith("MC.Client."));
		}

		Entry {
			parameterDescriptions = Map.copyOf(parameterDescriptions == null ? Map.of() : parameterDescriptions);
			parameterTypes = List.copyOf(parameterTypes == null ? List.of() : parameterTypes);
			ownerModId = ownerModId == null || ownerModId.isBlank() ? "mc_lua_ide" : ownerModId;
			namespaceLabel = namespaceLabel == null ? "" : namespaceLabel;
		}

		// Keep raw type hints available to the analyzer; presentation follows the current game language.
		String rawReturns() { return returns; }
		String rawSummary() { return summary; }
		String rawDetails() { return details; }
		public String summary() { return LuaUiText.chinese() || !ownerModId.equals("mc_lua_ide") ? summary : LuaUiText.japanese() ? LuaApiJapanese.summary(signature) : LuaApiEnglish.summary(signature); }
		public String returns() { return LuaUiText.chinese() || !ownerModId.equals("mc_lua_ide") ? returns : LuaUiText.japanese() ? LuaApiJapanese.returns(returns) : LuaApiEnglish.returns(returns); }
		public String details() { return LuaUiText.chinese() || !ownerModId.equals("mc_lua_ide") || details.isEmpty() ? details : LuaUiText.japanese() ? LuaApiJapanese.details(details, clientRequired) : LuaApiEnglish.details(signature, details, clientRequired); }

		List<Component> tooltip() {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(signature).withStyle(ChatFormatting.AQUA));
			lines.add(Component.literal(LuaUiText.text("作用：") + summary()).withStyle(ChatFormatting.WHITE));
			lines.add(Component.literal(LuaUiText.text("返回：") + returns()).withStyle(ChatFormatting.GREEN));
			List<String> parameters = parameters();
			if (parameters.isEmpty()) {
				lines.add(Component.literal(LuaUiText.text("参数：无")).withStyle(ChatFormatting.GRAY));
			} else {
				for (int index = 0; index < parameters.size(); index++) {
					lines.add(Component.literal((LuaUiText.chinese() ? "参数" + (index + 1) + "：" : LuaUiText.japanese() ? "引数" + (index + 1) + "：" : "Parameter " + (index + 1) + ": ") + describe(parameters.get(index)))
							.withStyle(ChatFormatting.YELLOW));
				}
			}
			if (singleplayerOnly) {
				lines.add(Component.literal(LuaUiText.text("限制：仅可在内置单人世界使用", "Restriction: integrated single-player server only")).withStyle(ChatFormatting.GOLD));
			}
			if (clientRequired) {
				lines.add(Component.literal(LuaUiText.text("限制：需要目标玩家安装本 Mod，且服务器已开启强制客户端模式", "Restriction: target must have the client mod; forced-client mode must be enabled"))
						.withStyle(ChatFormatting.GOLD));
			}
			if (!details.isEmpty()) {
				lines.add(Component.literal(LuaUiText.text("说明：") + details()).withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			return lines;
		}

		List<String> parameters() {
			int open = signature.indexOf('(');
			int close = signature.lastIndexOf(')');
			if (open < 0 || close <= open + 1) return List.of();
			return List.of(signature.substring(open + 1, close).split(",\\s*"));
		}

		String describe(String parameter) {
			boolean optional = parameter.endsWith("?");
			String name = optional ? parameter.substring(0, parameter.length() - 1) : parameter;
			if (!LuaUiText.chinese() && ownerModId.equals("mc_lua_ide"))
				return (LuaUiText.japanese() ? LuaApiJapanese.parameter(name, signature) : LuaApiEnglish.parameter(name, signature))
						+ (optional ? LuaUiText.text("（可选）", " (optional)") : "");
			String supplied = parameterDescriptions.get(name);
			if (supplied != null && !supplied.isBlank()) {
				String description = name + " - " + supplied;
				return optional && !description.contains("可选") && !description.contains("省略") && !description.contains("任意")
						&& !description.toLowerCase(Locale.ROOT).contains("optional") ? description + LuaUiText.text("（可选）", " (optional)") : description;
			}
			String description = switch (name) {
				case "x" -> signature.startsWith("MC.Sound.play(") ? "x - X 坐标；x、y、z 同时为 nil 表示不分坐标广播"
						: signature.startsWith("MC.Block.") || signature.startsWith("MC.Container.")
						? "x - 方块 X 坐标（整数）" : "x - 目标 X 坐标（数字）";
				case "y" -> signature.startsWith("MC.Sound.play(") ? "y - Y 坐标；x、y、z 同时为 nil 表示不分坐标广播"
						: signature.startsWith("MC.Block.") || signature.startsWith("MC.Container.")
						? "y - 方块 Y 坐标（整数）" : "y - 目标 Y 坐标（数字）";
				case "z" -> signature.startsWith("MC.Sound.play(") ? "z - Z 坐标；x、y、z 同时为 nil 表示不分坐标广播"
						: signature.startsWith("MC.Block.") || signature.startsWith("MC.Container.")
						? "z - 方块 Z 坐标（整数）" : "z - 目标 Z 坐标（数字）";
				case "id" -> signature.startsWith("MC.Block.")
						? "id - 方块资源 ID（字符串）" : signature.startsWith("MC.Item.")
						? "id - 物品资源 ID（字符串）" : "id - Fabric 模组 ID（字符串）";
				case "drops" -> "drops - 是否生成掉落物（布尔值，默认 true）";
				case "uuid" -> signature.startsWith("MC.Entity.ride(") || signature.startsWith("MC.Entity.dismount(")
						? "uuid - 已加载乘客的 UUID（字符串，也可传在线玩家 UUID；ride 的第一个参数是上面的实体）"
						: signature.startsWith("MC.Entity.") || signature.startsWith("MC.Effect.")
						|| signature.startsWith("MC.Attribute.")
						? "uuid - 已加载实体 UUID（字符串）" : "uuid - 在线玩家 UUID（字符串）";
				case "playerUuid" -> signature.startsWith("MC.Sound.play(")
						? "playerUuid - 在线玩家 UUID；nil 表示全部目标玩家，填写时只播放给该玩家"
						: "playerUuid - 在线玩家 UUID（字符串）";
				case "vehicleUuid" -> "vehicleUuid - 已加载坐骑的 UUID（字符串；ride 的第二个参数是下面的实体，必须同维度，不能是玩家）";
				case "uiId" -> "uiId - 当前脚本内唯一的界面 ID（1–64 位安全字符）";
				case "definition" -> "definition - 原版界面定义表；支持正文、输入和按钮";
				case "name" -> "name - 在线玩家名称（字符串）";
				case "dimension" -> signature.startsWith("MC.Sound.play(")
						? "dimension - 维度 ID；nil 或省略表示全服广播（所有维度，不受距离限制）"
						: "dimension - 维度 ID（字符串，默认 minecraft:overworld）";
				case "count" -> "count - 数量（整数）";
				case "itemId" -> "itemId - 物品资源 ID；省略或传 nil 表示清空";
				case "language" -> "language - 语言代码，例如 zh_cn/en_us/ja_jp；不填保持默认名称。缺少目标词条时回退英文，第二返回值说明实际语言";
				case "enchantmentId" -> "enchantmentId - 当前服务端注册的附魔资源 ID，例如 minecraft:sharpness；支持数据包和其他 Mod";
				case "rows" -> "rows - 箱子行数，整数 1～6；每行 9 格";
				case "readOnly" -> "readOnly - true 只读商店，禁止全部物品转移；使用 onContainerClicked 接收商品点击";
				case "slotId" -> "slotId - 当前原版菜单槽号，从 0 开始；包含下方玩家背包，不是 Lua 数组索引";
				case "expectedContainerId" -> "expectedContainerId - 预期菜单 ID；与当前窗口不符则跳过，避免回调修改已切换的窗口";
				case "items", "costs", "rewards" -> name + " - {id=物品ID,count=数量,components=组件表/JSON} 数组；Lua 索引从 1 开始";
				case "components" -> signature.startsWith("MC.Block.set(")
						? "components - 方块配置表：states、blockEntityData、itemComponents；旧式物品组件表仍兼容（可选）"
						: signature.startsWith("MC.Entity.spawn(")
						? "components - 实体配置表：通用状态、实体数据组件、fabric:attachments={附件ID=持久化数据}、展示实体专有字段；掉落物支持 Item={id,count,components}、Health、Age、PickupDelay、Owner/Thrower UUID（可选）"
						: signature.startsWith("MC.Item.count(") || signature.startsWith("MC.Item.clear(")
						|| signature.startsWith("MC.Item.isOnCooldown(") || signature.startsWith("MC.Item.startCooldown(")
						|| signature.startsWith("MC.Item.setCooldown(") || signature.startsWith("MC.Item.clearCooldown(")
						? "components - 筛选条件（Lua 表或 JSON 对象字符串）；只检查传入的组件，custom_data 为 NBT 子集，其余指定组件完整值相等；{} 或省略不限制组件；!组件ID={} 要求该组件不存在"
						: "components - 数据组件补丁（Lua 表或 JSON 对象字符串）；键为完整组件 ID，可选";
				case "title" -> signature.startsWith("MC.Client.")
						? "title - 客户端提示标题（字符串）"
						: "title - 菜单标题文本（默认使用对应工作站名称）";
				case "value" -> "value - 要设置的数值或布尔状态";
				case "mayFly" -> "mayFly - 是否允许玩家飞行（布尔值）";
				case "flying" -> "flying - 是否立即进入飞行状态（默认与 mayFly 相同）";
				case "walkingSpeed" -> "walkingSpeed - 行走速度（0–2，原版默认 0.1）";
				case "flyingSpeed" -> "flyingSpeed - 飞行速度（0–2，原版默认 0.05）";
				case "mode" -> "mode - survival/creative/adventure/spectator";
				case "text" -> "text - 要显示或发送的文本（字符串）";
				case "textOrJson" -> "textOrJson - 普通字符串、Minecraft 文本组件 JSON 字符串或 Lua 表";
				case "displayNameOrJson" -> "displayNameOrJson - 显示名；支持普通字符串、文本组件 JSON 字符串或 Lua 表";
				case "nameOrJson" -> "nameOrJson - 名称；支持普通字符串、文本组件 JSON 字符串或 Lua 表";
				case "titleOrJson" -> "titleOrJson - 标题；支持普通字符串、文本组件 JSON 字符串或 Lua 表";
				case "messageOrJson" -> "messageOrJson - 消息正文；支持普通字符串、文本组件 JSON 字符串或 Lua 表";
				case "bodyOrJson" -> "bodyOrJson - 页面正文；支持普通字符串、文本组件 JSON 字符串或 Lua 表";
				case "body" -> "body - 客户端通知页面正文（字符串）";
				case "message" -> "message - Toast 的正文内容（字符串）";
				case "url" -> "url - http/https 地址；客户端只显示确认框";
				case "command" -> "command - 命令文本（字符串，/ 可省略）";
				case "x1", "y1", "z1" -> name + " - 来源区域第一个角的整数坐标";
				case "x2", "y2", "z2" -> name + " - 来源区域第二个角的整数坐标";
				case "toX", "toY", "toZ" -> name + " - 复制目标起点的整数坐标";
				case "blockId" -> "blockId - 方块资源 ID，例如 minecraft:stone";
				case "fromId" -> "fromId - 只替换这种方块资源 ID";
				case "toId" -> "toId - 替换后的方块资源 ID";
				case "entityType" -> "entityType - 实体类型资源 ID，例如 minecraft:zombie";
				case "effectId" -> "effectId - 状态效果资源 ID，例如 minecraft:speed";
				case "soundId" -> "soundId - 声音事件资源 ID";
				case "particleId" -> "particleId - 粒子类型资源 ID";
				case "attributeId" -> "attributeId - 实体属性资源 ID";
				case "modifierId" -> "modifierId - 属性修改器的唯一资源 ID";
				case "advancementId" -> "advancementId - 进度资源 ID";
				case "recipeId" -> "recipeId - 配方资源 ID";
				case "lootTableId" -> "lootTableId - 战利品表资源 ID";
				case "structureId" -> "structureId - 结构模板资源 ID";
				case "food" -> "food - 饥饿值（整数，0–20）";
				case "saturation" -> "saturation - 饱和度（数字，默认不超过饥饿值）";
				case "amount" -> "amount - 要增加、减少、治疗或伤害的数值";
				case "levels" -> "levels - true 按经验等级处理，false 按经验点处理";
				case "damageType" -> "damageType - 伤害类型资源 ID（默认 minecraft:generic）";
				case "reason" -> "reason - 踢出玩家时显示的原因文本";
				case "yaw" -> "yaw - 水平旋转角度";
				case "pitch" -> signature.startsWith("MC.Sound.") ? "pitch - 播放音调（默认 1）" : "pitch - 垂直旋转角度";
				case "radius" -> "radius - 搜索半径（0–1024 格）";
				case "visible" -> "visible - 是否显示实体自定义名称";
				case "enabled" -> "enabled - 是否启用该功能（布尔值）";
				case "seconds" -> "seconds - 持续时间（秒）";
				case "ticks" -> signature.startsWith("MC.Item.")
						? "ticks - 使用冷却时间（整数，0–1728000；正常 20 Tick = 1 秒，0 立即结束）；startCooldown 省略时读取物品 use_cooldown，否则默认 20 Tick"
						: "ticks - 游戏 Tick 数（整数，正常 20 Tick = 1 秒）";
				case "milliseconds" -> "milliseconds - 暂停当前脚本的时间（毫秒，整数，0–3600000）";
				case "duration" -> "duration - 持续时间（秒）";
				case "amplifier" -> "amplifier - 效果等级，从 0 开始";
				case "showParticles" -> "showParticles - 是否显示状态效果粒子";
				case "volume" -> "volume - 音量（默认 1）";
				case "startSeconds" -> "startSeconds - 音频起播位置，非负秒数，可用小数，默认 0；非零需要强制客户端模式，不是延时";
				case "category" -> "category - 声音分类，如 master/music/block/player";
				case "spread" -> "spread - 三轴扩散范围";
				case "speed" -> "speed - 粒子速度";
				case "force" -> signature.startsWith("MC.Entity.ride(")
						? "force - 是否跳过普通骑乘冷却和座位容量检查（布尔值，默认 true；不绕过循环、维度和坐骑类型限制）"
						: "force - 是否强制远距离显示粒子";
				case "fadeIn" -> "fadeIn - 标题淡入 Tick 数";
				case "stay" -> "stay - 标题停留 Tick 数";
				case "fadeOut" -> "fadeOut - 标题淡出 Tick 数";
				case "objective" -> "objective - 计分板目标名称";
				case "criteria" -> "criteria - 计分标准（默认 dummy）";
				case "displayName" -> "displayName - 显示名称文本";
				case "holder" -> signature.startsWith("MC.Team.")
						? "holder - 任意实体 UUID（在线玩家、生物、掉落物、展示实体等）；兼容玩家名和虚拟名称。非玩家实体未加载时仍可按 UUID 处理；离线玩家请传玩家名"
						: "holder - 玩家 UUID 或虚拟计分名称";
				case "slot" -> signature.startsWith("MC.Item.") || signature.startsWith("MC.Container.")
						? "slot - 槽位索引（整数，从 0 开始）"
						: "slot - 显示位置，例如 sidebar/list/below_name";
				case "firstSlot" -> "firstSlot - 第一个背包槽位（整数，从 0 开始）";
				case "secondSlot" -> "secondSlot - 第二个背包槽位（整数，从 0 开始）";
				case "equipmentSlot" -> "equipmentSlot - mainhand/offhand/feet/legs/chest/head/body/saddle";
				case "team" -> "team - 队伍名称";
				case "color" -> "color - Minecraft 文本颜色名称";
				case "weather" -> "weather - clear、rain 或 thunder";
				case "difficulty" -> "difficulty - peaceful/easy/normal/hard";
				case "time" -> "time - day/night/noon/midnight 或时间刻数";
				case "fuseTicks" -> "fuseTicks - TNT 引信 Tick 数（默认 0）";
				case "power" -> "power - 爆炸威力（默认 4）";
				case "chunkX" -> "chunkX - 区块 X 坐标";
				case "chunkZ" -> "chunkZ - 区块 Z 坐标";
				case "size" -> "size - 世界边界直径";
				case "distance" -> "distance - 世界边界警告距离";
				case "rate" -> "rate - 每秒 Tick 数（1–10000）";
				case "tag" -> "tag - 实体命令标签";
				case "operation" -> "operation - add_value/add_multiplied_base/add_multiplied_total";
				case "kind" -> "kind - MC.Registry.kinds() 返回的注册表类型";
				case "callback" -> "callback - 事件触发时调用的 Lua 函数；接收 event 表";
				case "eventName" -> "eventName - MC.Event.names() 中的事件名称（字符串）";
				case "intervalTicks" -> "intervalTicks - Tick 事件间隔（1–72000，默认 1）；MC.Event.on 的 interval 事件改为毫秒（1–3600000，默认 1000）";
				case "intervalMs" -> "intervalMs - 重复间隔，1–3600000 的整数毫秒；首次等待一个间隔，不依赖游戏 Tick";
				case "registrationId" -> "registrationId - 注册事件时返回的整数 ID";
				default -> name;
			};
			return optional && !description.contains("默认") ? description + "（可选）" : description;
		}
	}

	record DocumentEntry(String symbol, Entry documentation) {
		String apiRoot() {
			int dot = symbol.indexOf('.');
			return dot < 0 ? symbol : symbol.substring(0, dot);
		}

		String namespace() {
			if (!apiRoot().equals("MC")) return apiRoot();
			int start = symbol.indexOf('.') + 1;
			int end = symbol.indexOf('.', start);
			return end < 0 ? "Core" : symbol.substring(start, end);
		}

		String methodName() {
			int dot = symbol.lastIndexOf('.');
			return dot < 0 ? symbol : symbol.substring(dot + 1);
		}

		String namespaceLabel() {
			return documentation.namespaceLabel().isBlank() ? namespace() : documentation.namespaceLabel();
		}
	}

	record DocumentPage(String ownerModId, String categoryId, String pageId,
			McLuaIdeDocsApi.Translation translation) {
		String key() { return ownerModId + ':' + categoryId + '/' + pageId; }
	}
}
