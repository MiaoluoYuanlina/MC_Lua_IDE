package wiki.ica.xiaomiao.mcluaide.client;

import java.util.*;
import java.util.function.Function;

/** No game/window needed: ordering, hidden tabs, extensions and persisted layout compatibility. */
final class DockTabOrderSmoke {
	public static void main(String[] args) {
		DockTabOrder order = new DockTabOrder(new Properties());
		List<String> initial = List.of("MANUAL", "INSERT", "EXT:test:window");
		for (DockSide side : List.of(DockSide.LEFT, DockSide.RIGHT, DockSide.BOTTOM)) {
			require(order.ordered(side, initial, Function.identity()).equals(initial), "Old layouts must keep default ordering");
			require(order.move(side, initial, "MANUAL", 2), "Cannot move first tab to last");
			require(order.ordered(side, initial, Function.identity()).equals(List.of("INSERT", "EXT:test:window", "MANUAL")), "Wrong forward ordering");
			List<String> visible = order.ordered(side, List.of("MANUAL", "EXT:test:window"), Function.identity());
			require(order.move(side, visible, "MANUAL", 0), "Cannot move last tab to first");
			require(order.ordered(side, initial, Function.identity()).equals(List.of("INSERT", "MANUAL", "EXT:test:window")), "Closed tabs lost their position");
			require(!order.move(side, List.of("MANUAL"), "MANUAL", 99), "Single-tab move should be a no-op");
			require(!order.move(side, initial, "missing", 0), "Unknown tab accepted");
		}
		require(!order.move(DockSide.FLOATING, initial, "MANUAL", 2), "Floating windows cannot reorder dock tabs");
		Properties properties = new Properties(); properties.setProperty("unrelated", "kept"); order.save(properties);
		DockTabOrder loaded = new DockTabOrder(properties);
		for (DockSide side : List.of(DockSide.LEFT, DockSide.RIGHT, DockSide.BOTTOM)) {
			require(loaded.ordered(side, initial, Function.identity()).equals(List.of("INSERT", "MANUAL", "EXT:test:window")), "Tab order not restored");
		}
		require(properties.getProperty("unrelated").equals("kept"), "Other geometry settings overwritten");
		loaded.move(DockSide.LEFT, loaded.ordered(DockSide.LEFT, initial, Function.identity()), "EXT:test:window", -3);
		require(loaded.ordered(DockSide.RIGHT, initial, Function.identity()).equals(List.of("INSERT", "MANUAL", "EXT:test:window")), "Different dock groups share order");
		require(loaded.ordered(DockSide.LEFT, List.of("MANUAL", "INSERT", "API_DOCS", "EXT:test:window"), Function.identity()).getLast().equals("API_DOCS"), "New tabs must remain accessible");
		Properties malformed = new Properties(); malformed.setProperty("dockTabs.LEFT", " ,INSERT,INSERT,,MANUAL,EXT:absent:window");
		require(new DockTabOrder(malformed).ordered(DockSide.LEFT, initial, Function.identity()).equals(List.of("INSERT", "MANUAL", "EXT:test:window")), "Malformed/absent extension entries broke ordering");
		System.out.println("Dock tab order smoke passed: all edges, forward/backward, hidden and extension tabs, new tabs, persistence and old layouts");
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
