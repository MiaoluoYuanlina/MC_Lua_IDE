package wiki.ica.xiaomiao.mcluaide.client;

import java.nio.file.*;
import java.util.*;

public final class LuaBreakpointStoreSmoke {
	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("lua-breakpoints-");
		try {
			Path session = root.resolve("world.properties");
			LuaBreakpointStore store = new LuaBreakpointStore(session);
			String initial = "local first = 1\nlocal second = 2\nprint(second)\n";
			store.toggle("folder/main", 2, initial);
			store.edit("folder/main", initial, "\n" + initial);
			require(store.lines("folder/main").equals(Set.of(3)), "Enter before breakpoint did not shift marker");
			store.edit("folder/main", "\n" + initial, initial);
			require(store.lines("folder/main").equals(Set.of(2)), "Undo did not restore line anchor");
			store.edit("folder/main", initial, initial.replace("second = 2", "second = 5"));
			require(store.lines("folder/main").equals(Set.of(2)), "Editing line lost marker");
			store.save();
			require(new LuaBreakpointStore(session).config().equals(store.config()), "Breakpoint persistence lost metadata");
			require(new LuaBreakpointStore(root.resolve("other-world.properties")).config().files().isEmpty(), "Breakpoints leaked to other world");
			String changed = initial.replace("second = 2", "second = 5");
			store.edit("folder/main", changed, "local first = 1\nprint(second)\n");
			require(store.lines("folder/main").isEmpty(), "Deleted line retained breakpoint on unrelated code");
			store.toggle("module", 1, "print(1)\n"); store.clear("module");
			require(store.lines("module").isEmpty(), "Clear failed");
			System.out.println("Breakpoint store smoke passed: toggle, edit/Enter/undo/delete anchors, persistence and scope isolation");
		} finally {
			try (var files = Files.walk(root)) {
				for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
			}
		}
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
