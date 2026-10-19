package wiki.ica.xiaomiao.mcluaide.client;

import java.util.*;
import java.util.function.Function;

/** Stable per-edge tab order, retaining closed windows and temporarily unavailable extensions. */
final class DockTabOrder {
	private static final String PREFIX = "dockTabs.";
	private final Map<DockSide, List<String>> orders = new EnumMap<>(DockSide.class);

	DockTabOrder(Properties properties) {
		for (DockSide side : DockSide.values()) {
			if (!side.docked()) continue;
			List<String> tokens = Arrays.stream(properties.getProperty(PREFIX + side.name(), "").split(","))
					.map(String::trim).filter(token -> !token.isEmpty() && token.length() <= 512)
					.distinct().limit(256).toList();
			orders.put(side, new ArrayList<>(tokens));
		}
	}

	<T> List<T> ordered(DockSide side, List<T> available, Function<T, String> token) {
		Map<String, Integer> ranks = new HashMap<>();
		List<String> order = orders.getOrDefault(side, List.of());
		for (int index = 0; index < order.size(); index++) ranks.put(order.get(index), index);
		List<T> result = new ArrayList<>(available);
		// Stable sorting leaves new windows in their existing default order, after remembered tabs.
		result.sort(Comparator.comparingInt(item -> ranks.getOrDefault(token.apply(item), Integer.MAX_VALUE)));
		return result;
	}

	boolean move(DockSide side, List<String> visible, String dragged, int targetIndex) {
		if (!side.docked() || !visible.contains(dragged)) return false;
		int from = visible.indexOf(dragged), to = Math.clamp(targetIndex, 0, visible.size() - 1);
		if (from == to) return false;
		List<String> reordered = new ArrayList<>(visible);
		reordered.remove(from); reordered.add(to, dragged);
		List<String> order = orders.computeIfAbsent(side, ignored -> new ArrayList<>());
		for (String token : visible) if (!order.contains(token)) order.add(token);
		Set<String> visibleTokens = new HashSet<>(visible);
		int index = 0;
		for (int slot = 0; slot < order.size(); slot++) {
			if (visibleTokens.contains(order.get(slot))) order.set(slot, reordered.get(index++));
		}
		return true;
	}

	void save(Properties properties) {
		for (DockSide side : DockSide.values()) {
			if (side.docked()) properties.setProperty(PREFIX + side.name(), String.join(",", orders.getOrDefault(side, List.of())));
		}
	}
}
