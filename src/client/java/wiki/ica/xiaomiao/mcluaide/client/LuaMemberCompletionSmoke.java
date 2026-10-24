package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;

/** Completion after "variable." for event tables, API results, loop entries, table constructors and strings. */
public final class LuaMemberCompletionSmoke {
	private static int checks;

	public static void main(String[] args) {
		List<String> event = complete("MC.Event.onItemUsed(function(event)\n    print(event.", "event.");
		check(event.contains("event.playerName") && event.contains("event.itemId") && event.contains("event.type") && event.contains("event.hand"), "onItemUsed event fields: " + event);
		check(complete("MC.Event.onItemUsed(function(event)\n    event.pl", "event.pl").equals(List.of("event.playerUuid", "event.playerName")), "prefix filter keeps order");
		check(complete("MC.Event.on(\"blockPlaced\", function(e)\n  local id = e.", "e.").contains("e.blockId"), "MC.Event.on with an event name");
		check(complete("MC.Event.onInterval(1000, function(tick)\n  tick.", "tick.").contains("tick.elapsedMs"), "interval callback is the second argument");
		check(complete("MC.Event.onItemUsed(function(event) end)\nprint(event.", "event.").isEmpty(), "the parameter ends with its function");
		check(complete("local event = {}\nMC.Event.onItemUsed(function(other)\n  print(event.", "event.").isEmpty(), "outer table without fields");
		check(complete("MC.Event.onItemUsed(function(event)\n  local event = 5\n  print(event.", "event.").isEmpty(), "a local shadows the parameter");
		List<String> position = complete("local p = MC.Player.position(uuid)\nprint(p.", "p.");
		check(position.containsAll(List.of("p.x", "p.y", "p.z", "p.yaw", "p.dimension")), "documented table result: " + position);
		check(complete("for _, player in ipairs(MC.Player.list()) do\n  print(player.", "player.").containsAll(List.of("player.uuid", "player.name")), "player list entries");
		check(complete("for i, e in pairs(MC.Entity.list()) do\n  print(e.", "e.").contains("e.health"), "entity list entries");
		check(complete("local cfg = {alpha = 1, beta = {gamma = 2}}\nprint(cfg.", "cfg.").equals(List.of("cfg.alpha", "cfg.beta")), "table constructor keys (top level only)");
		List<String> text = complete("local s = \"abc\"\nprint(s:", "s:");
		check(text.contains("s:upper") && text.contains("s:sub"), "string methods after colon: " + text);
		check(complete("local s = tostring(5)\ns.", "s.").contains("s:upper"), "dot on a string offers colon methods");
		check(complete("local copy = event\ncopy.", "copy.").isEmpty(), "unknown alias");
		check(complete("print(unknown.", "unknown.").isEmpty(), "unknown variable");
		check(!complete("MC.Event.onItemUsed(function(event)\n  if true then\n    print(event.", "event.").isEmpty(), "inside nested blocks");
		check(complete("MC.Event.onItemUsed(function(event)\n  for i = 1, 3 do end\n  print(event.", "event.").size() > 3, "after a closed loop");
		System.out.println("Member completion smoke passed: " + checks + " checks");
	}

	private static List<String> complete(String source, String prefix) {
		return LuaMemberCompletion.choices(source, source.length(), prefix).stream().map(LuaArgumentCompletion.Choice::insertion).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}
