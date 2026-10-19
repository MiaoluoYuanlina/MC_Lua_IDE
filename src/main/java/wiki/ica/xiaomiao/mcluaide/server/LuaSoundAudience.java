package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.resources.Identifier;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.Varargs;

/** Sound-only nil semantics; other APIs keep their usual default dimension. */
record LuaSoundAudience(Identifier dimension, boolean hasCoordinates, double x, double y, double z) {
	static LuaSoundAudience read(Varargs args, Identifier dimension) {
		int omitted = 0;
		for (int i = 2; i <= 4; i++) if (args.arg(i).isnil()) omitted++;
		if (omitted != 0 && omitted != 3)
			throw new LuaError("MC.Sound.play 的 x、y、z 必须同时填写数字，或同时传 nil（不分坐标播放）");
		if (omitted == 3) return new LuaSoundAudience(dimension, false, 0, 0, 0);
		double x = args.checkdouble(2), y = args.checkdouble(3), z = args.checkdouble(4);
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
			throw new LuaError("MC.Sound.play 的坐标必须是有限数字");
		return new LuaSoundAudience(dimension, true, x, y, z);
	}

	boolean positional() { return hasCoordinates && dimension != null; }

	boolean includes(Identifier playerDimension, double playerX, double playerY, double playerZ, float volume) {
		if (dimension != null && !dimension.equals(playerDimension)) return false;
		if (!positional()) return true;
		double range = Math.max(1.0, volume) * 16.0;
		double dx = playerX - x, dy = playerY - y, dz = playerZ - z;
		return dx * dx + dy * dy + dz * dz <= range * range;
	}
}
