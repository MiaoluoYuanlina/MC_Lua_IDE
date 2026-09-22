package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

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

	private LuaApiDocumentation() {
	}

	static List<String> symbols() {
		return List.copyOf(entries.keySet());
	}

	static Entry find(String symbol) {
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
		return entries.entrySet().stream()
				.map(entry -> new DocumentEntry(entry.getKey(), entry.getValue()))
				.toList();
	}

	static String usageTemplate(String symbol) {
		Entry entry = find(symbol);
		if (entry == null) return "";
		if (symbol.equals("MC.Event.on") || symbol.startsWith("MC.Event.on")
				&& !symbol.equals("MC.Event.names")) {
			return eventUsageTemplate(symbol, entry);
		}
		return switch (symbol) {
			case "MC.Client.toast" -> """
					-- 需要先在“设置 → 服务器接入”开启强制客户端模式
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
					local block = MC.Block.get(0, 64, 0, "minecraft:overworld")
					print(block.id, block.name, block.air)
					print(block.x, block.y, block.z, block.dimension)
					""";
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
		Map<String, Entry> merged = new LinkedHashMap<>(BUILTIN_ENTRIES);
		Minecraft minecraft = Minecraft.getInstance();
		String locale = minecraft == null ? Locale.getDefault().toString()
				: minecraft.getLanguageManager().getSelected();
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
			case "MC.Event.onEntityKilledOther" ->
					"print(event.killerName, \"击杀\", event.killedName, event.damageType)";
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
				default -> null;
			};
			if (documentedKind != null) return documentedKind;
		}
		String normalized = symbol.toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "mc.block.set" -> argumentIndex == 3 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.block.exists", "mc.block.name" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.block.fill" -> argumentIndex == 6 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.block.replace" -> argumentIndex == 6 || argumentIndex == 7 ? LuaInsertCatalog.Kind.BLOCK : null;
			case "mc.item.held" -> argumentIndex == 0 ? LuaInsertCatalog.Kind.PLAYER : null;
			case "mc.item.count", "mc.item.give" -> argumentIndex == 0
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
					"mc.recipe.give", "mc.recipe.take", "mc.loot.give", "mc.player.openblock",
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
		add(entries, "MC.apiVersion", "MC.apiVersion()", "获取服务端脚本 API 版本", "字符串", "print(MC.apiVersion())", false);

		add(entries, "MC.Block.get", "MC.Block.get(x, y, z, dimension?)", "从服务端读取方块详细信息及方块物品组件", "含 itemComponents/itemComponentsJson 的方块信息表", "local b = MC.Block.get(0, 64, 0)", false);
		add(entries, "MC.Block.id", "MC.Block.id(x, y, z, dimension?)", "从服务端读取方块资源 ID", "字符串", "print(MC.Block.id(0, 64, 0))", false);
		add(entries, "MC.Block.isAir", "MC.Block.isAir(x, y, z, dimension?)", "从服务端判断坐标是否为空气", "布尔值", "print(MC.Block.isAir(0, 64, 0))", false);
		add(entries, "MC.Block.set", "MC.Block.set(x, y, z, id, dimension?, components?)", "在服务端放置方块，并向方块实体传递物品组件", "是否发生变化", "MC.Block.set(0, 64, 0, \"chest\", nil, {[\"minecraft:custom_name\"] = {text = \"奖励箱\"}})", false);
		add(entries, "MC.Block.breakAt", "MC.Block.breakAt(x, y, z, drops?, dimension?)", "在服务端破坏方块", "是否成功破坏", "MC.Block.breakAt(0, 64, 0, true)", false);

		add(entries, "MC.Item.held", "MC.Item.held(uuid)", "读取指定玩家的主手物品", "物品堆表，含 components/componentsJson", "local item = MC.Item.held(uuid)", false);
		add(entries, "MC.Item.count", "MC.Item.count(uuid, id, components?)", "统计指定玩家背包中的物品；可按完整组件匹配", "整数数量", "print(MC.Item.count(uuid, \"diamond\"))", false);
		add(entries, "MC.Item.give", "MC.Item.give(uuid, id, count?, components?)", "由服务端给予带数据组件的物品", "实际给予数量", "MC.Item.give(uuid, \"diamond_sword\", 1, {[\"minecraft:unbreakable\"] = {}})", false);
		add(entries, "MC.Item.validate", "MC.Item.validate(id, count?, components?)", "校验并规范化带数据组件的物品堆，不修改游戏", "物品堆信息表", "local stack = MC.Item.validate(\"diamond_sword\", 1, {[\"minecraft:unbreakable\"] = {}})", false);
		add(entries, "MC.Item.inventory", "MC.Item.inventory(uuid)", "读取玩家全部背包槽位", "物品堆数组；每项含 components/componentsJson", "local slots = MC.Item.inventory(uuid)", false);
		add(entries, "MC.Item.getSlot", "MC.Item.getSlot(uuid, slot)", "读取玩家指定背包槽位（从 0 开始）", "物品堆信息表", "print(MC.Item.getSlot(uuid, 0).id)", false);
		add(entries, "MC.Item.setSlot", "MC.Item.setSlot(uuid, slot, itemId?, count?, components?)", "设置或清空玩家指定背包槽位，可传数据组件", "设置后的物品堆信息表", "MC.Item.setSlot(uuid, 0, \"minecraft:diamond\", 16)", false);
		add(entries, "MC.Item.clearSlot", "MC.Item.clearSlot(uuid, slot)", "清空玩家指定背包槽位", "清空前的物品堆信息表", "local old = MC.Item.clearSlot(uuid, 0)", false);
		add(entries, "MC.Item.swapSlots", "MC.Item.swapSlots(uuid, firstSlot, secondSlot)", "交换玩家两个背包槽位", "true", "MC.Item.swapSlots(uuid, 0, 8)", false);
		add(entries, "MC.Item.selectedSlot", "MC.Item.selectedSlot(uuid)", "读取玩家当前快捷栏槽位", "0 到 8 的整数", "print(MC.Item.selectedSlot(uuid))", false);
		add(entries, "MC.Item.setSelectedSlot", "MC.Item.setSelectedSlot(uuid, slot)", "切换玩家当前快捷栏槽位", "实际槽位（0 到 8）", "MC.Item.setSelectedSlot(uuid, 2)", false);
		add(entries, "MC.Item.equipment", "MC.Item.equipment(uuid, equipmentSlot)", "读取玩家指定装备槽", "物品堆信息表", "local helmet = MC.Item.equipment(uuid, \"head\")", false);
		add(entries, "MC.Item.setEquipment", "MC.Item.setEquipment(uuid, equipmentSlot, itemId?, count?, components?)", "设置或清空玩家指定装备槽，可传数据组件", "设置后的物品堆信息表", "MC.Item.setEquipment(uuid, \"head\", \"minecraft:diamond_helmet\")", false);

		add(entries, "MC.Player.list", "MC.Player.list()", "列出全部在线服务端玩家", "玩家信息数组", "local players = MC.Player.list()", false);
		add(entries, "MC.Player.uuid", "MC.Player.uuid(name)", "按在线玩家名称查询 UUID", "UUID 字符串或 nil", "local uuid = MC.Player.uuid(\"Steve\")", false);
		add(entries, "MC.Player.name", "MC.Player.name(uuid)", "按 UUID 获取在线玩家名称", "字符串", "print(MC.Player.name(uuid))", false);
		add(entries, "MC.Player.position", "MC.Player.position(uuid)", "读取指定玩家位置和朝向", "{x, y, z, yaw, pitch, dimension}", "local p = MC.Player.position(uuid)", false);
		add(entries, "MC.Player.health", "MC.Player.health(uuid)", "读取指定玩家生命值", "{current, max}", "local hp = MC.Player.health(uuid)", false);
		add(entries, "MC.Player.gameMode", "MC.Player.gameMode(uuid)", "读取指定玩家游戏模式", "模式名称字符串", "print(MC.Player.gameMode(uuid))", false);
		add(entries, "MC.Player.teleport", "MC.Player.teleport(uuid, x, y, z, dimension?)", "由服务端传送指定玩家", "是否成功", "MC.Player.teleport(uuid, 0, 80, 0)", false);
		add(entries, "MC.Player.setHealth", "MC.Player.setHealth(uuid, value)", "设置指定玩家生命值", "实际生命值", "MC.Player.setHealth(uuid, 20)", false);
		add(entries, "MC.Player.setGameMode", "MC.Player.setGameMode(uuid, mode)", "设置指定玩家游戏模式", "是否发生变化", "MC.Player.setGameMode(uuid, \"creative\")", false);
		add(entries, "MC.Player.openBlock", "MC.Player.openBlock(uuid, x, y, z, dimension?)", "让玩家打开坐标处方块的原版菜单", "容器 ID；无法打开时 nil/错误", "MC.Player.openBlock(uuid, 10, 64, 10)", false);
		add(entries, "MC.Player.openCrafting", "MC.Player.openCrafting(uuid, title?)", "直接为玩家打开工作台", "容器 ID 或 nil", "MC.Player.openCrafting(uuid)", false);
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
		add(entries, "MC.Block.name", "MC.Block.name(id)", "获取方块的本地化显示名称", "字符串", "print(MC.Block.name(\"minecraft:stone\"))", false);
		add(entries, "MC.Block.fill", "MC.Block.fill(x1, y1, z1, x2, y2, z2, blockId, dimension?)", "直接填充长方体区域，不受原版命令方块数量上限影响", "是否至少修改一个方块", "MC.Block.fill(0, 64, 0, 4, 68, 4, \"minecraft:stone\")", false);
		add(entries, "MC.Block.replace", "MC.Block.replace(x1, y1, z1, x2, y2, z2, fromId, toId, dimension?)", "直接替换区域内指定方块，不修改原版命令实现", "是否至少修改一个方块", "MC.Block.replace(0, 64, 0, 8, 72, 8, \"minecraft:dirt\", \"minecraft:grass_block\")", false);
		add(entries, "MC.Block.clone", "MC.Block.clone(x1, y1, z1, x2, y2, z2, toX, toY, toZ, dimension?)", "直接复制方块区域并保留方块实体数据，不受原版 clone 数量上限影响", "是否至少修改一个方块", "MC.Block.clone(0, 64, 0, 4, 68, 4, 16, 64, 16)", false);

		add(entries, "MC.Item.exists", "MC.Item.exists(id)", "检查物品资源 ID 是否已注册", "布尔值", "print(MC.Item.exists(\"minecraft:diamond\"))", false);
		add(entries, "MC.Item.name", "MC.Item.name(id)", "获取物品的本地化显示名称", "字符串", "print(MC.Item.name(\"minecraft:diamond\"))", false);
		add(entries, "MC.Item.maxStack", "MC.Item.maxStack(id)", "获取物品默认最大堆叠数", "整数", "print(MC.Item.maxStack(\"minecraft:ender_pearl\"))", false);
		add(entries, "MC.Item.clear", "MC.Item.clear(uuid, id?, count?, components?)", "清除玩家背包中的指定物品；可按完整组件匹配", "true", "MC.Item.clear(\"玩家UUID\", \"minecraft:dirt\", 64)", false);
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

		add(entries, "MC.Entity.get", "MC.Entity.get(uuid)", "读取已加载实体的完整基础信息", "实体信息表或 nil", "local e = MC.Entity.get(\"实体UUID\"); if e then print(e.type, e.name, e.health) end", false);
		add(entries, "MC.Entity.exists", "MC.Entity.exists(uuid)", "检查运行中实体是否存在且已加载", "布尔值", "print(MC.Entity.exists(\"实体UUID\"))", false);
		add(entries, "MC.Entity.list", "MC.Entity.list(dimension?)", "列出维度内全部已加载实体", "实体信息数组", "for _, e in ipairs(MC.Entity.list()) do print(e.uuid, e.type) end", false);
		add(entries, "MC.Entity.nearby", "MC.Entity.nearby(x, y, z, radius, dimension?)", "列出坐标附近的已加载实体", "实体信息数组", "for _, e in ipairs(MC.Entity.nearby(0, 64, 0, 16)) do print(e.name) end", false);
		add(entries, "MC.Entity.spawn", "MC.Entity.spawn(entityType, x, y, z, dimension?, components?)", "在服务端世界直接生成实体，并传递数据组件", "实体是否成功加入世界", "MC.Entity.spawn(\"minecraft:zombie\", 0, 64, 0)", false);
		add(entries, "MC.Entity.teleport", "MC.Entity.teleport(uuid, x, y, z, dimension?)", "传送已加载实体", "是否成功", "MC.Entity.teleport(\"实体UUID\", 0, 80, 0)", false);
		add(entries, "MC.Entity.velocity", "MC.Entity.velocity(uuid, x, y, z)", "设置实体三轴速度", "true", "MC.Entity.velocity(\"实体UUID\", 0, 1, 0)", false);
		add(entries, "MC.Entity.kill", "MC.Entity.kill(uuid)", "杀死实体并触发正常死亡逻辑", "true", "MC.Entity.kill(\"实体UUID\")", false);
		add(entries, "MC.Entity.remove", "MC.Entity.remove(uuid)", "直接移除非玩家实体", "true", "MC.Entity.remove(\"实体UUID\")", false);
		add(entries, "MC.Entity.setName", "MC.Entity.setName(uuid, nameOrJson, visible?)", "设置实体自定义名称及可见状态；名称支持文本组件 JSON 或 Lua 表", "true", "MC.Entity.setName(\"实体UUID\", {text = \"守卫\", color = \"#55FFFF\", bold = true}, true)", false);
		add(entries, "MC.Entity.setGlowing", "MC.Entity.setGlowing(uuid, value)", "设置实体发光轮廓", "true", "MC.Entity.setGlowing(\"实体UUID\", true)", false);
		add(entries, "MC.Entity.setInvulnerable", "MC.Entity.setInvulnerable(uuid, value)", "设置实体是否无敌", "true", "MC.Entity.setInvulnerable(\"实体UUID\", true)", false);
		add(entries, "MC.Entity.setGravity", "MC.Entity.setGravity(uuid, enabled)", "启用或关闭实体重力", "true", "MC.Entity.setGravity(\"实体UUID\", false)", false);
		add(entries, "MC.Entity.equipment", "MC.Entity.equipment(uuid, equipmentSlot)", "读取任意已加载生物的装备及数据组件", "物品堆信息表", "local held = MC.Entity.equipment(\"实体UUID\", \"mainhand\")", false);
		add(entries, "MC.Entity.setEquipment", "MC.Entity.setEquipment(uuid, equipmentSlot, itemId?, count?, components?)", "设置或清空任意已加载生物的装备", "设置后的物品堆信息表", "MC.Entity.setEquipment(\"实体UUID\", \"mainhand\", \"minecraft:diamond_sword\", 1)", false);
		add(entries, "MC.Entity.fire", "MC.Entity.fire(uuid, seconds)", "设置实体燃烧时间；0 表示灭火", "true", "MC.Entity.fire(\"实体UUID\", 5)", false);
		add(entries, "MC.Entity.tags", "MC.Entity.tags(uuid)", "列出实体的命令标签", "字符串数组", "for _, tag in ipairs(MC.Entity.tags(\"实体UUID\")) do print(tag) end", false);
		add(entries, "MC.Entity.addTag", "MC.Entity.addTag(uuid, tag)", "添加实体命令标签", "是否发生变化", "MC.Entity.addTag(\"实体UUID\", \"boss\")", false);
		add(entries, "MC.Entity.removeTag", "MC.Entity.removeTag(uuid, tag)", "移除实体命令标签", "是否发生变化", "MC.Entity.removeTag(\"实体UUID\", \"boss\")", false);

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

		add(entries, "MC.Effect.give", "MC.Effect.give(uuid, effectId, duration?, amplifier?, showParticles?)", "给予生物状态效果", "true；命令已提交", "MC.Effect.give(\"实体UUID\", \"minecraft:speed\", 30, 1, true)", false);
		add(entries, "MC.Effect.clear", "MC.Effect.clear(uuid, effectId?)", "移除一个或全部状态效果", "true；命令已提交", "MC.Effect.clear(\"实体UUID\", \"minecraft:speed\")", false);
		add(entries, "MC.Effect.list", "MC.Effect.list(uuid)", "读取生物当前全部状态效果", "效果信息数组", "for _, e in ipairs(MC.Effect.list(\"实体UUID\")) do print(e.id, e.duration) end", false);

		add(entries, "MC.Sound.play", "MC.Sound.play(soundId, x, y, z, volume?, pitch?, category?, dimension?, playerUuid?)", "从服务端播放注册声音", "true；命令已提交", "MC.Sound.play(\"minecraft:block.note_block.pling\", 0, 64, 0, 1, 1)", false);
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
		add(entries, "MC.Team.join", "MC.Team.join(team, holder)", "让玩家或虚拟名称加入队伍", "true；命令已提交", "MC.Team.join(\"red\", \"玩家UUID\")", false);
		add(entries, "MC.Team.leave", "MC.Team.leave(holder)", "让玩家或虚拟名称离开队伍", "true；命令已提交", "MC.Team.leave(\"玩家UUID\")", false);
		add(entries, "MC.Team.color", "MC.Team.color(team, color)", "设置队伍颜色", "true；命令已提交", "MC.Team.color(\"red\", \"red\")", false);
		add(entries, "MC.Team.friendlyFire", "MC.Team.friendlyFire(team, enabled)", "设置队伍友军伤害", "true；命令已提交", "MC.Team.friendlyFire(\"red\", false)", false);
		add(entries, "MC.Team.prefix", "MC.Team.prefix(team, textOrJson)", "设置队伍名称前缀；支持文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Team.prefix(\"red\", {text = \"[红队] \", color = \"#FF5555\", bold = true})", false);
		add(entries, "MC.Team.suffix", "MC.Team.suffix(team, textOrJson)", "设置队伍名称后缀；支持文本组件 JSON 或 Lua 表", "true；命令已提交", "MC.Team.suffix(\"red\", {text = \" ★\", color = \"#FFFF55\"})", false);

		add(entries, "MC.GameRule.names", "MC.GameRule.names()", "列出当前版本注册的全部游戏规则资源 ID", "字符串数组", "for _, id in ipairs(MC.GameRule.names()) do print(id) end", false);
		add(entries, "MC.GameRule.set", "MC.GameRule.set(name, value, dimension?)", "设置服务端游戏规则", "true；命令已提交", "MC.GameRule.set(\"doDaylightCycle\", \"false\")", false);
		add(entries, "MC.Border.set", "MC.Border.set(size, seconds?)", "设置世界边界直径", "true；命令已提交", "MC.Border.set(1000, 10)", false);
		add(entries, "MC.Border.add", "MC.Border.add(amount, seconds?)", "增加或缩小世界边界", "true；命令已提交", "MC.Border.add(-100, 5)", false);
		add(entries, "MC.Border.center", "MC.Border.center(x, z)", "设置世界边界中心", "true；命令已提交", "MC.Border.center(0, 0)", false);
		add(entries, "MC.Border.warning", "MC.Border.warning(distance)", "设置世界边界警告距离", "true；命令已提交", "MC.Border.warning(10)", false);
		add(entries, "MC.Border.damage", "MC.Border.damage(amount)", "设置越界每格伤害", "true；命令已提交", "MC.Border.damage(0.2)", false);

		add(entries, "MC.Attribute.setBase", "MC.Attribute.setBase(uuid, attributeId, value)", "设置生物属性基础值", "true；命令已提交", "MC.Attribute.setBase(\"实体UUID\", \"minecraft:max_health\", 40)", false);
		add(entries, "MC.Attribute.addModifier", "MC.Attribute.addModifier(uuid, attributeId, modifierId, amount, operation)", "添加永久属性修改器", "true；命令已提交", "MC.Attribute.addModifier(\"实体UUID\", \"minecraft:movement_speed\", \"mymod:boost\", 0.2, \"add_multiplied_base\")", false);
		add(entries, "MC.Attribute.removeModifier", "MC.Attribute.removeModifier(uuid, attributeId, modifierId)", "移除指定属性修改器", "true；命令已提交", "MC.Attribute.removeModifier(\"实体UUID\", \"minecraft:movement_speed\", \"mymod:boost\")", false);
		add(entries, "MC.Advancement.grant", "MC.Advancement.grant(uuid, advancementId)", "授予玩家完整进度", "true；命令已提交", "MC.Advancement.grant(\"玩家UUID\", \"minecraft:story/mine_stone\")", false);
		add(entries, "MC.Advancement.revoke", "MC.Advancement.revoke(uuid, advancementId)", "撤销玩家完整进度", "true；命令已提交", "MC.Advancement.revoke(\"玩家UUID\", \"minecraft:story/mine_stone\")", false);
		add(entries, "MC.Recipe.give", "MC.Recipe.give(uuid, recipeId)", "解锁玩家配方", "true；命令已提交", "MC.Recipe.give(\"玩家UUID\", \"minecraft:crafting_table\")", false);
		add(entries, "MC.Recipe.take", "MC.Recipe.take(uuid, recipeId)", "收回玩家配方", "true；命令已提交", "MC.Recipe.take(\"玩家UUID\", \"minecraft:crafting_table\")", false);
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

		addEvent(entries, "MC.Event.on", "MC.Event.on(eventName, callback, intervalTicks?)", "按事件名称注册通用服务端回调", "事件注册 ID（整数）", "type，以及所选事件对应的字段");
		add(entries, "MC.Event.names", "MC.Event.names()", "列出当前支持的全部事件名称", "事件名称字符串数组", "for _, name in ipairs(MC.Event.names()) do print(name) end", false);
		addEvent(entries, "MC.Event.onBlockPlaced", "MC.Event.onBlockPlaced(callback)", "监听玩家成功放置方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, x, y, z, dimension, hand, itemId, itemCount, itemComponents");
		addEvent(entries, "MC.Event.onBlockBroken", "MC.Event.onBlockBroken(callback)", "监听玩家成功破坏方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, x, y, z, dimension, hadBlockEntity");
		addEvent(entries, "MC.Event.onBlockAttacked", "MC.Event.onBlockAttacked(callback)", "监听玩家左键攻击方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, x, y, z, dimension, hand, face");
		addEvent(entries, "MC.Event.onBlockUsed", "MC.Event.onBlockUsed(callback)", "监听玩家右键使用方块", "事件注册 ID（整数）", "playerUuid, playerName, blockId, itemId, itemCount, itemComponents, x, y, z, dimension, hand, face");
		addEvent(entries, "MC.Event.onItemUsed", "MC.Event.onItemUsed(callback)", "监听玩家使用手中物品", "事件注册 ID（整数）", "playerUuid, playerName, itemId, itemCount, itemComponents, count, hand, x, y, z, dimension");
		addEvent(entries, "MC.Event.onEntityAttacked", "MC.Event.onEntityAttacked(callback)", "监听玩家攻击实体", "事件注册 ID（整数）", "playerUuid, playerName, hand, targetUuid, targetType, targetName, targetRuntimeId, targetX, targetY, targetZ");
		addEvent(entries, "MC.Event.onEntityUsed", "MC.Event.onEntityUsed(callback)", "监听玩家右键交互实体", "事件注册 ID（整数）", "playerUuid, playerName, hand, itemId, itemCount, itemComponents，以及 target* 实体字段");
		addEvent(entries, "MC.Event.onEntityLoaded", "MC.Event.onEntityLoaded(callback)", "监听实体加载到服务端世界", "事件注册 ID（整数）", "entityUuid, entityType, entityName, entityRuntimeId, entityX, entityY, entityZ, dimension");
		addEvent(entries, "MC.Event.onEntityUnloaded", "MC.Event.onEntityUnloaded(callback)", "监听实体从服务端世界卸载", "事件注册 ID（整数）", "entityUuid, entityType, entityName, entityRuntimeId, entityX, entityY, entityZ, dimension");
		addEvent(entries, "MC.Event.onEntityChangedDimension", "MC.Event.onEntityChangedDimension(callback)", "监听非玩家实体切换维度", "事件注册 ID（整数）", "entity* 字段, originalUuid, fromDimension, toDimension");
		addEvent(entries, "MC.Event.onEntityDamaged", "MC.Event.onEntityDamaged(callback)", "监听生物受到伤害后的结果", "事件注册 ID（整数）", "entity* 字段, damageType, baseDamage, damageTaken, blocked, remainingHealth，以及可选 attacker*/direct* 字段");
		addEvent(entries, "MC.Event.onEntityDied", "MC.Event.onEntityDied(callback)", "监听生物死亡", "事件注册 ID（整数）", "entity* 字段, damageType，以及可选 attacker*/direct* 字段");
		addEvent(entries, "MC.Event.onEntityKilledOther", "MC.Event.onEntityKilledOther(callback)", "监听实体杀死另一个生物", "事件注册 ID（整数）", "killer* 字段, killed* 字段, damageType, dimension，以及可选 attacker*/direct* 字段");
		addEvent(entries, "MC.Event.onEquipmentChanged", "MC.Event.onEquipmentChanged(callback)", "监听生物装备槽发生变化", "事件注册 ID（整数）", "entity* 字段, slot, oldItemId, oldItemCount, oldItemComponents, oldCount, newItemId, newItemCount, newItemComponents, newCount, dimension");
		addEvent(entries, "MC.Event.onPlayerJoined", "MC.Event.onPlayerJoined(callback)", "监听玩家加入服务器", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension");
		addEvent(entries, "MC.Event.onPlayerLeft", "MC.Event.onPlayerLeft(callback)", "监听玩家离开服务器", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension");
		addEvent(entries, "MC.Event.onPlayerRespawned", "MC.Event.onPlayerRespawned(callback)", "监听玩家重生完成", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, dimension, alive");
		addEvent(entries, "MC.Event.onPlayerChangedDimension", "MC.Event.onPlayerChangedDimension(callback)", "监听玩家切换维度完成", "事件注册 ID（整数）", "playerUuid, playerName, x, y, z, fromDimension, toDimension");
		addEvent(entries, "MC.Event.onEffectAdded", "MC.Event.onEffectAdded(callback)", "监听生物获得状态效果", "事件注册 ID（整数）", "entity* 字段, effectId, duration, amplifier, ambient, visible, fromCommand, dimension");
		addEvent(entries, "MC.Event.onEffectRemoved", "MC.Event.onEffectRemoved(callback)", "监听生物移除状态效果", "事件注册 ID（整数）", "entity* 字段, effectId, duration, amplifier, ambient, visible, fromCommand, dimension");
		addEvent(entries, "MC.Event.onSleepStarted", "MC.Event.onSleepStarted(callback)", "监听生物开始睡眠", "事件注册 ID（整数）", "entity* 字段, bedX, bedY, bedZ, dimension");
		addEvent(entries, "MC.Event.onSleepStopped", "MC.Event.onSleepStopped(callback)", "监听生物停止睡眠", "事件注册 ID（整数）", "entity* 字段, bedX, bedY, bedZ, dimension");
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
		entries.put(symbol, new Entry(signature, summary, returns, example, singleplayerOnly, ""));
	}

	private static void addEvent(Map<String, Entry> entries, String symbol, String signature, String summary,
			String returns, String payload) {
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

		List<Component> tooltip() {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(signature).withStyle(ChatFormatting.AQUA));
			lines.add(Component.literal("作用：" + summary).withStyle(ChatFormatting.WHITE));
			lines.add(Component.literal("返回：" + returns).withStyle(ChatFormatting.GREEN));
			List<String> parameters = parameters();
			if (parameters.isEmpty()) {
				lines.add(Component.literal("参数：无").withStyle(ChatFormatting.GRAY));
			} else {
				for (int index = 0; index < parameters.size(); index++) {
					lines.add(Component.literal("参数" + (index + 1) + "：" + describe(parameters.get(index)))
							.withStyle(ChatFormatting.YELLOW));
				}
			}
			if (singleplayerOnly) {
				lines.add(Component.literal("限制：仅可在内置单人世界使用").withStyle(ChatFormatting.GOLD));
			}
			if (clientRequired) {
				lines.add(Component.literal("限制：需要目标玩家安装本 Mod，且服务器已开启强制客户端模式")
						.withStyle(ChatFormatting.GOLD));
			}
			if (!details.isEmpty()) {
				lines.add(Component.literal("说明：" + details).withStyle(ChatFormatting.LIGHT_PURPLE));
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
			String supplied = parameterDescriptions.get(name);
			if (supplied != null && !supplied.isBlank()) {
				String description = name + " - " + supplied;
				return optional && !description.contains("可选") ? description + "（可选）" : description;
			}
			String description = switch (name) {
				case "x" -> signature.startsWith("MC.Block.") || signature.startsWith("MC.Container.")
						? "x - 方块 X 坐标（整数）" : "x - 目标 X 坐标（数字）";
				case "y" -> signature.startsWith("MC.Block.") || signature.startsWith("MC.Container.")
						? "y - 方块 Y 坐标（整数）" : "y - 目标 Y 坐标（数字）";
				case "z" -> signature.startsWith("MC.Block.") || signature.startsWith("MC.Container.")
						? "z - 方块 Z 坐标（整数）" : "z - 目标 Z 坐标（数字）";
				case "id" -> signature.startsWith("MC.Block.")
						? "id - 方块资源 ID（字符串）" : signature.startsWith("MC.Item.")
						? "id - 物品资源 ID（字符串）" : "id - Fabric 模组 ID（字符串）";
				case "drops" -> "drops - 是否生成掉落物（布尔值，默认 true）";
				case "uuid" -> signature.startsWith("MC.Entity.") || signature.startsWith("MC.Effect.")
						|| signature.startsWith("MC.Attribute.")
						? "uuid - 已加载实体 UUID（字符串）" : "uuid - 在线玩家 UUID（字符串）";
				case "playerUuid" -> "playerUuid - 在线玩家 UUID（字符串）";
				case "uiId" -> "uiId - 当前脚本内唯一的界面 ID（1–64 位安全字符）";
				case "definition" -> "definition - 原版界面定义表；支持正文、输入和按钮";
				case "name" -> "name - 在线玩家名称（字符串）";
				case "dimension" -> "dimension - 维度 ID（字符串，默认 minecraft:overworld）";
				case "count" -> "count - 数量（整数）";
				case "itemId" -> "itemId - 物品资源 ID；省略或传 nil 表示清空";
				case "components" -> "components - 数据组件补丁（Lua 表或 JSON 对象字符串）；键为完整组件 ID，可选";
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
				case "duration" -> "duration - 持续时间（秒）";
				case "amplifier" -> "amplifier - 效果等级，从 0 开始";
				case "showParticles" -> "showParticles - 是否显示状态效果粒子";
				case "volume" -> "volume - 音量（默认 1）";
				case "category" -> "category - 声音分类，如 master/music/block/player";
				case "spread" -> "spread - 三轴扩散范围";
				case "speed" -> "speed - 粒子速度";
				case "force" -> "force - 是否强制远距离显示粒子";
				case "fadeIn" -> "fadeIn - 标题淡入 Tick 数";
				case "stay" -> "stay - 标题停留 Tick 数";
				case "fadeOut" -> "fadeOut - 标题淡出 Tick 数";
				case "objective" -> "objective - 计分板目标名称";
				case "criteria" -> "criteria - 计分标准（默认 dummy）";
				case "displayName" -> "displayName - 显示名称文本";
				case "holder" -> "holder - 玩家 UUID 或虚拟计分名称";
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
				case "ticks" -> "ticks - Tick 数量";
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
				case "intervalTicks" -> "intervalTicks - Tick 事件触发间隔（可选，1–72000，默认 1）";
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
}
