package wiki.ica.xiaomiao.mcluaide.server;

import java.util.Locale;

/**
 * The point a distance in front of / behind / left of / right of a player, from the player's yaw and pitch.
 * Minecraft yaw 0 faces south (+Z), 90 west (-X), -90 east (+X); positive pitch looks down.
 */
public final class LuaRelativePosition {
	public record Point(double x, double y, double z) { }
	public record Facing(double yaw, double pitch, String direction) { }

	public static final double MAX_DISTANCE = 100_000;

	private LuaRelativePosition() { }

	/**
	 * @param direction front, back, left or right (forward/backward accepted)
	 * @param plane horizontal (yaw only), vertical (pitch only: front/back move up or down) or both (the full view direction for front/back)
	 */
	public static Point offset(double x, double y, double z, double yaw, double pitch, String direction, String plane, double distance) {
		if (!Double.isFinite(distance) || Math.abs(distance) > MAX_DISTANCE) throw new IllegalArgumentException("距离必须是绝对值不超过 " + (long) MAX_DISTANCE + " 的数字");
		String side = direction == null ? "" : direction.strip().toLowerCase(Locale.ROOT);
		String mode = plane == null ? "" : plane.strip().toLowerCase(Locale.ROOT);
		boolean sideways = switch (side) {
			case "front", "forward", "back", "backward" -> false;
			case "left", "right" -> true;
			default -> throw new IllegalArgumentException("方向必须是 front、back、left 或 right：" + direction);
		};
		boolean horizontal = mode.equals("horizontal"), vertical = mode.equals("vertical"), both = mode.equals("both");
		if (!horizontal && !vertical && !both) throw new IllegalArgumentException("平面必须是 horizontal、vertical 或 both：" + plane);
		if (sideways && vertical) throw new IllegalArgumentException("left 和 right 只能用于 horizontal 或 both");
		double sign = side.startsWith("b") || side.equals("left") ? -1 : 1;
		double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
		double dx, dy = 0, dz;
		if (sideways) {
			// Right of a player facing +Z is -X.
			dx = -Math.cos(yawRadians) * distance * sign;
			dz = -Math.sin(yawRadians) * distance * sign;
		} else if (vertical) {
			dx = 0; dz = 0;
			dy = -Math.sin(pitchRadians) * distance * sign;
		} else {
			double flat = both ? Math.cos(pitchRadians) : 1;
			dx = -Math.sin(yawRadians) * flat * distance * sign;
			dz = Math.cos(yawRadians) * flat * distance * sign;
			if (both) dy = -Math.sin(pitchRadians) * distance * sign;
		}
		return new Point(x + dx, y + dy, z + dz);
	}

	public static Facing facing(double yaw, double pitch) {
		double normalized = ((yaw % 360) + 540) % 360 - 180; // -180..180
		String compass = Math.abs(normalized) <= 45 ? "south" : normalized > 45 && normalized <= 135 ? "west" : Math.abs(normalized) >= 135 ? "north" : "east";
		return new Facing(normalized, pitch, compass);
	}
}
