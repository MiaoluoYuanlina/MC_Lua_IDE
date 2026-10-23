package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandSourceStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every command of the running server with its usage lines, grouped into one category for vanilla and one per mod.
 * Brigadier keeps no author, so a command belongs to the mod whose jar defines its handler class; commands that
 * cannot be traced to a mod are listed under "other". Descriptions are not stored here (the client has them for vanilla).
 */
public final class LuaCommandReference {
	private static final int MAX_USAGE_LINES = 300;
	public static final String VANILLA = "minecraft";
	public static final String OTHER = "other";

	private LuaCommandReference() {}

	public static String build(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
		Map<String, List<CommandNode<CommandSourceStack>>> groups = new TreeMap<>();
		for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren())
			groups.computeIfAbsent(category(node), key -> new ArrayList<>()).add(node);
		JsonArray categories = new JsonArray();
		// Vanilla first, then mods alphabetically, then what could not be traced.
		List<String> order = new ArrayList<>();
		if (groups.containsKey(VANILLA)) order.add(VANILLA);
		groups.keySet().stream().filter(key -> !key.equals(VANILLA) && !key.equals(OTHER)).forEach(order::add);
		if (groups.containsKey(OTHER)) order.add(OTHER);
		for (String key : order) {
			JsonObject category = new JsonObject();
			category.addProperty("id", key);
			category.addProperty("name", name(key));
			JsonArray commands = new JsonArray();
			List<CommandNode<CommandSourceStack>> nodes = groups.get(key);
			nodes.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			for (CommandNode<CommandSourceStack> node : nodes) {
				JsonObject command = new JsonObject();
				command.addProperty("name", node.getName());
				JsonArray usages = new JsonArray();
				for (String usage : usages(dispatcher, node, source)) usages.add(usage);
				command.add("usages", usages);
				commands.add(command);
			}
			category.add("commands", commands);
			categories.add(category);
		}
		return categories.toString();
	}

	private static List<String> usages(CommandDispatcher<CommandSourceStack> dispatcher, CommandNode<CommandSourceStack> node, CommandSourceStack source) {
		CommandNode<CommandSourceStack> target = node.getRedirect() != null && node.getCommand() == null && node.getChildren().isEmpty() ? node.getRedirect() : node;
		List<String> result = new ArrayList<>();
		if (target.getCommand() != null) result.add(node.getName());
		try {
			for (String usage : dispatcher.getAllUsage(target, source, false)) {
				if (result.size() >= MAX_USAGE_LINES) break;
				if (usage.isBlank()) continue;
				result.add(node.getName() + " " + usage);
			}
		} catch (RuntimeException ignored) {
			// A broken third-party node must not hide the other commands.
		}
		if (result.isEmpty()) result.add(node.getName());
		return result;
	}

	static String category(CommandNode<CommandSourceStack> node) {
		Class<?> origin = firstHandler(node, 0);
		if (origin == null) return OTHER;
		String className = origin.getName();
		if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) return VANILLA;
		String mod = modOf(origin);
		return mod == null ? OTHER : mod;
	}

	private static Class<?> firstHandler(CommandNode<CommandSourceStack> node, int depth) {
		if (node.getCommand() != null) return node.getCommand().getClass();
		if (depth > 6) return null;
		for (CommandNode<CommandSourceStack> child : node.getChildren()) {
			Class<?> found = firstHandler(child, depth + 1);
			if (found != null) return found;
		}
		return node.getRequirement() == null ? null : node.getRequirement().getClass();
	}

	private static String modOf(Class<?> type) {
		try {
			var source = type.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) return null;
			Path location = Path.of(source.getLocation().toURI());
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				for (Path path : mod.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH ? mod.getOrigin().getPaths() : List.<Path>of()) {
					if (location.startsWith(path) || path.startsWith(location)) return mod.getMetadata().getId();
				}
			}
		} catch (Exception | LinkageError ignored) {
			// Unknown class origin: fall through to "other".
		}
		return null;
	}

	private static String name(String key) {
		if (key.equals(VANILLA)) return "Minecraft";
		if (key.equals(OTHER)) return "other";
		return FabricLoader.getInstance().getModContainer(key).map(mod -> mod.getMetadata().getName()).orElse(key);
	}

}
