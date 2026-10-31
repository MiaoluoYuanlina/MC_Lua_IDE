package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Map;

/** English reference text for the built-in server API; Lua examples are never rewritten. */
final class LuaApiEnglish {
	private LuaApiEnglish() {}
	private static final Map<String, String> SUMMARIES = Map.ofEntries(
		Map.entry("MC.apiVersion", "Return the MC Lua IDE mod version as a string, for example \"1.0.12\"."),
		Map.entry("MC.Item.setSlotComponents", "Change only the components of the item in a player inventory slot; item and count stay. Use !minecraft:xxx = {} to remove one."),
		Map.entry("MC.Entity.setEquipmentComponents", "Change only the components of the item in an equipment slot; item and count stay."),
		Map.entry("MC.Player.movingForward", "Check whether a player is holding the forward key (W), as last reported by the client."),
		Map.entry("MC.sleep", "Pause the current flow for the given milliseconds (0–3600000). The server and other callbacks keep running."),
		Map.entry("MC.Item.held", "Read a player's main-hand item and its components."),
		Map.entry("MC.Item.count", "Count matching inventory items. Only supplied components are checked; custom_data matches as an NBT subset."),
		Map.entry("MC.Item.clear", "Remove matching inventory items. Only supplied components are checked; an item ID is required with components."),
		Map.entry("MC.Item.give", "Give items with data components to an online player. Items that do not fit are dropped."),
		Map.entry("MC.Item.validate", "Validate and normalize an item stack without changing the game."),
		Map.entry("MC.Item.getSlot", "Read an inventory slot's item and components. Slots are zero-based (hotbar 0–8)."),
		Map.entry("MC.Item.setSlot", "Replace the item in a player inventory slot."),
		Map.entry("MC.Item.trade", "Atomic shop trade: simulates costs and rewards first and changes nothing if funds or space are short."),
		Map.entry("MC.Item.isOnCooldown", "Check whether matching inventory items are on cooldown. Components can filter."),
		Map.entry("MC.Item.startCooldown", "Start or restart the cooldown of matching items. Omitted ticks use the item's own duration; 0 clears it."),
		Map.entry("MC.Item.setCooldown", "Reset the remaining cooldown of matching items to ticks, starting now; 0 clears it."),
		Map.entry("MC.Item.clearCooldown", "Immediately finish a player's item cooldown."),
		Map.entry("MC.Player.list", "List online server players with names and UUIDs."),
		Map.entry("MC.Player.uuid", "Find an online player's UUID by name."),
		Map.entry("MC.Player.online", "Check whether the specified player UUID is online."),
		Map.entry("MC.Player.position", "Read the player's server position, rotation and dimension."),
		Map.entry("MC.Player.facing", "Read a player's yaw and pitch angles and the nearest compass direction."),
		Map.entry("MC.Player.lookDirection", "Read the player's 3D view direction as a unit vector."),
		Map.entry("MC.Player.lookingAt", "Read the exact coordinates of the block surface under the crosshair; range can be unlimited."),
		Map.entry("MC.Player.relativePosition", "Compute the coordinates at a given distance from a player."),
		Map.entry("MC.Player.carried", "Read the item held on a player's cursor."),
		Map.entry("MC.Player.teleport", "Teleport an online player; an omitted dimension keeps the current one."),
		Map.entry("MC.Player.openChest", "Open a worldless chest UI with an item array; rows 1–6, readOnly blocks item moves. Contents are not saved."),
		Map.entry("MC.Player.openBlock", "Open the vanilla menu of a block."),
		Map.entry("MC.Player.openCrafting", "Open a crafting table without a physical block."),
		Map.entry("MC.Player.openEnderChest", "Open the player's own ender chest."),
		Map.entry("MC.Player.setMenuItem", "Replace a slot in the player's current menu, including virtual chests; itemId=nil clears it."),
		Map.entry("MC.Player.setCarried", "Set or clear the item on the player's cursor."),
		Map.entry("MC.Block.get", "Read a real block's ID, states and block entity data. Does not load chunks or modify the world."),
		Map.entry("MC.Block.set", "Place a block with optional states and block entity data."),
		Map.entry("MC.Block.fill", "Fill a region directly, ignoring the vanilla limit; keepExisting fills only air, dropItems drops loot from replaced blocks."),
		Map.entry("MC.Entity.spawn", "Spawn an entity and return its UUID. Pass an entity type ID, not a spawn egg."),
		Map.entry("MC.Entity.ride", "Make the first entity ride the second; force defaults to true. Returns nil, reason if an entity is missing."),
		Map.entry("MC.Entity.dismount", "Make an entity leave its current vehicle."),
		Map.entry("MC.Event.onInterval", "Run a callback every given milliseconds until the script stops."),
		Map.entry("MC.Event.onBlockBroken", "Listen for a player breaking a block (player UUID, block ID, position, dimension)."),
		Map.entry("MC.Event.onPlayerDied", "Listen for player death, including player UUID and death position."),
		Map.entry("MC.Event.onPlayerRespawned", "Listen for player respawn; check alive to tell a death respawn."),
		Map.entry("MC.Event.onScriptStopped", "Register cleanup that runs when the script stops."),
		Map.entry("MC.Event.onScriptStarted", "Register a callback for script startup."),
		Map.entry("MC.GameRule.set", "Set a server game rule; the first argument is the rule ID."),
		Map.entry("MC.Sound.play", "Play a sound at a position, or with nil coordinates non-positionally; nil dimension broadcasts everywhere. Nonzero startSeconds needs the client mod."),
		Map.entry("MC.Chat.broadcast", "Send a message or text-component JSON to all players."),
		Map.entry("MC.Chat.send", "Send a message or text-component JSON to an online player.")
	);

	static String symbol(String signature) { int i = signature.indexOf('('); return i < 0 ? signature : signature.substring(0, i); }
	static String summary(String signature) {
		String symbol = symbol(signature);
		String translated = SUMMARIES.get(symbol);
		if (translated != null) return translated;
		String[] path = symbol.split("\\.");
		String method = path[path.length - 1];
		if (symbol.startsWith("MC.Event.on"))
			return "Register a callback for " + words(method.substring(2)) + ". The event table is passed to the callback; stop the script to unregister it.";
		if (method.equals("name")) return "Get the registered type's translated name. An optional language code selects the requested language; the second return value identifies the actual fallback language.";
		if (method.equals("random")) return "Return a random registered " + (path.length > 1 ? words(path[1]) : "resource") + " ID, including loaded mods.";
		return "Server API: " + words(method) + " (" + (path.length > 1 ? path[1] : "MC") + "). See the signature, parameter descriptions and runnable example below.";
	}

	static String returns(String raw) {
		if (raw == null || raw.isEmpty()) return "No return value";
		if (!raw.matches("(?s).*[\\p{IsHan}].*")) return raw;
		String result = raw.contains("注册 ID") || raw.contains("注册ID") ? "Event registration ID (integer)"
				: raw.contains("UUID") ? "Runtime UUID string"
				: raw.contains("布尔") || raw.startsWith("是否") ? "Boolean"
				: raw.contains("数组") || raw.contains("表") ? "Lua table (see example and field documentation)"
				: raw.contains("整数") || raw.contains("数量") ? "Integer count"
				: raw.contains("字符串") || raw.contains("名称") ? "String"
				: raw.startsWith("true") ? "true on success"
				: raw.contains("数值") || raw.contains("数字") ? "Number"
				: "Result as shown in the example";
		return raw.contains("nil") ? result + "; nil, reason for a recoverable failure" : result;
	}

	static String parameter(String name, String signature) {
		String hint = switch (name) {
			case "uuid", "playerUuid" -> signature.startsWith("MC.Entity.") || signature.startsWith("MC.Effect.") || signature.startsWith("MC.Attribute.")
					? "Runtime UUID of a loaded entity" : "UUID of an online player";
			case "id", "itemId" -> signature.startsWith("MC.Item.") ? "Item resource ID, for example minecraft:diamond" : "Namespaced resource ID";
			case "components" -> "Lua component/configuration table or JSON object string. Item filters check only supplied components; custom_data supports recursive subset matching";
			case "ticks" -> "Integer game ticks (normally 20 ticks = 1 second). Item cooldowns accept 0–1728000; zero clears the cooldown";
			case "slot", "slotId" -> "Zero-based slot number; inventory slots and menu slots are different";
			case "dimension" -> "Dimension resource ID. Check the API example for the default dimension";
			case "callback" -> "Lua function called with an event table";
			case "milliseconds" -> "Milliseconds; sleep accepts integers from 0 to 3600000";
			case "language" -> "Language code, such as en_us, zh_cn or ja_jp; omitted uses the default name";
			case "count", "rows" -> "Integer quantity; chest rows must be between 1 and 6";
			case "x", "y", "z", "x1", "y1", "z1", "x2", "y2", "z2" -> "World coordinate; block coordinates must be integers";
			case "costs", "rewards", "items" -> "Lua array of {id, count, components} entries, indexed from 1";
			case "expectedContainerId" -> "Expected current menu ID; prevents modifying a closed or replaced menu";
			case "textOrJson", "titleOrJson", "messageOrJson", "displayNameOrJson" -> "Plain text, Minecraft text-component JSON string, or a Lua text-component table";
			default -> words(name);
		};
		return name + " - " + hint;
	}

	static String details(String signature, String original, boolean clientRequired) {
		String common = "Runs with server authority. Invalid arguments, missing permissions and unknown resource IDs are errors. Recoverable unloaded-chunk, offline-player or missing-entity failures return nil, reason with a warning; check query results before using them.";
		String translated = LuaUiText.text(original);
		String notes = translated.equals(original) && original.matches("(?s).*[\\p{IsHan}].*")
				? "\n\nOriginal detailed notes (Chinese):\n" + original : "\n\n" + translated;
		return (clientRequired ? "Requires the target player's compatible client mod and forced-client mode on the server. " + common : common) + notes;
	}

	static String words(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
	}
}
