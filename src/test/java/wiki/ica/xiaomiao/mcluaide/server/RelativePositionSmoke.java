package wiki.ica.xiaomiao.mcluaide.server;

/** Offsets in front of / behind / beside a player and the facing summary. */
public final class RelativePositionSmoke {
	private static int checks;

	public static void main(String[] args) {
		// yaw 0 faces south (+Z); yaw 90 faces west (-X); yaw -90 faces east (+X).
		near(LuaRelativePosition.offset(0, 64, 0, 0, 0, "front", "horizontal", 5), 0, 64, 5);
		near(LuaRelativePosition.offset(0, 64, 0, 0, 0, "back", "horizontal", 5), 0, 64, -5);
		near(LuaRelativePosition.offset(0, 64, 0, 90, 0, "front", "horizontal", 5), -5, 64, 0);
		near(LuaRelativePosition.offset(0, 64, 0, -90, 0, "front", "horizontal", 5), 5, 64, 0);
		// Right of a player facing south is west.
		near(LuaRelativePosition.offset(0, 64, 0, 0, 0, "right", "horizontal", 3), -3, 64, 0);
		near(LuaRelativePosition.offset(0, 64, 0, 0, 0, "left", "both", 3), 3, 64, 0);
		// Pitch only matters for vertical and both; positive pitch looks down.
		near(LuaRelativePosition.offset(0, 64, 0, 0, 90, "front", "vertical", 4), 0, 60, 0);
		near(LuaRelativePosition.offset(0, 64, 0, 0, -90, "front", "vertical", 4), 0, 68, 0);
		near(LuaRelativePosition.offset(0, 64, 0, 0, -90, "back", "vertical", 4), 0, 60, 0);
		near(LuaRelativePosition.offset(0, 64, 0, 0, 45, "front", "horizontal", 10), 0, 64, 10);
		var diagonal = LuaRelativePosition.offset(0, 64, 0, 0, 45, "front", "both", 10);
		near(diagonal, 0, 64 - 10 * Math.sin(Math.toRadians(45)), 10 * Math.cos(Math.toRadians(45)));
		near(LuaRelativePosition.offset(1, 2, 3, 0, 0, "Forward", "BOTH", -2), 1, 2, 1);
		rejects(() -> LuaRelativePosition.offset(0, 0, 0, 0, 0, "up", "both", 1), "unknown direction");
		rejects(() -> LuaRelativePosition.offset(0, 0, 0, 0, 0, "front", "diagonal", 1), "unknown plane");
		rejects(() -> LuaRelativePosition.offset(0, 0, 0, 0, 0, "left", "vertical", 1), "sideways in the vertical plane");
		rejects(() -> LuaRelativePosition.offset(0, 0, 0, 0, 0, "front", "both", Double.NaN), "NaN distance");
		rejects(() -> LuaRelativePosition.offset(0, 0, 0, 0, 0, "front", "both", 1_000_000), "huge distance");
		check(LuaRelativePosition.facing(0, 10).direction().equals("south") && LuaRelativePosition.facing(90, 0).direction().equals("west"), "south and west");
		check(LuaRelativePosition.facing(-90, 0).direction().equals("east") && LuaRelativePosition.facing(180, 0).direction().equals("north"), "east and north");
		check(Math.abs(LuaRelativePosition.facing(270, 0).yaw() - (-90)) < 1e-9 && Math.abs(LuaRelativePosition.facing(-190, 0).yaw() - 170) < 1e-9, "yaw wraps into -180..180");
		// The look vector is a unit vector: south for yaw 0, west for 90, up for pitch -90.
		near(LuaRelativePosition.lookVector(0, 0), 0, 0, 1);
		near(LuaRelativePosition.lookVector(90, 0), -1, 0, 0);
		near(LuaRelativePosition.lookVector(-90, 0), 1, 0, 0);
		near(LuaRelativePosition.lookVector(0, -90), 0, 1, 0);
		var tilted = LuaRelativePosition.lookVector(-90, -11.536959032815489);
		check(Math.abs(Math.sqrt(tilted.x() * tilted.x() + tilted.y() * tilted.y() + tilted.z() * tilted.z()) - 1) < 1e-9 && tilted.y() > 0.19 && tilted.y() < 0.21, "unit length and upward tilt: " + tilted);
		System.out.println("Relative position smoke passed: " + checks + " checks");
	}

	private static void near(LuaRelativePosition.Point point, double x, double y, double z) {
		check(Math.abs(point.x() - x) < 1e-6 && Math.abs(point.y() - y) < 1e-6 && Math.abs(point.z() - z) < 1e-6, "expected " + x + "," + y + "," + z + " but got " + point);
	}

	private static void rejects(Runnable action, String what) {
		try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
		throw new AssertionError("accepted " + what);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}
