package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.resources.Identifier;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/** Dimension/range selection independent of player positions, networking and client-mod policy. */
public final class LuaSoundAudienceSmoke {
	private static int checks;
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");
	private static final Identifier MOD_WORLD = Identifier.parse("test:moon");

	public static void main(String[] args) {
		LuaSoundAudience all = LuaSoundAudience.read(values(LuaValue.NIL, LuaValue.NIL, LuaValue.NIL), null);
		require(!all.positional() && !all.hasCoordinates(), "nil positions create non-positional broadcast");
		for (Identifier dimension : new Identifier[]{OVERWORLD, NETHER, MOD_WORLD}) {
			require(all.includes(dimension, 30_000_000, 400, -30_000_000, 1), "Global includes far listener in " + dimension);
		}
		LuaSoundAudience scoped = LuaSoundAudience.read(values(LuaValue.NIL, LuaValue.NIL, LuaValue.NIL), OVERWORLD);
		require(!scoped.positional() && scoped.includes(OVERWORLD, 1_000_000, 320, 1_000_000, 1), "Scoped broadcast bypasses distance");
		require(!scoped.includes(NETHER, 0, 64, 0, 10000), "Scoped broadcast excludes other dimension");
		LuaSoundAudience positioned = LuaSoundAudience.read(values(0, 64, 0), OVERWORLD);
		require(positioned.positional() && positioned.hasCoordinates(), "Explicit coordinate/dimension retain positional mode");
		require(positioned.includes(OVERWORLD, 16, 64, 0, 1), "Inclusive audible range boundary");
		require(!positioned.includes(OVERWORLD, 16.01, 64, 0, 1), "Far player excluded from positional mode");
		require(!positioned.includes(NETHER, 0, 64, 0, 1), "Positional mode excludes wrong dimension");
		require(positioned.includes(OVERWORLD, 32, 64, 0, 2), "Volume scales positional range");
		require(!positioned.includes(OVERWORLD, 32.01, 64, 0, 2), "Volume range not unbounded");
		LuaSoundAudience globalPosition = LuaSoundAudience.read(values(0, 64, 0), null);
		require(!globalPosition.positional() && globalPosition.includes(MOD_WORLD, 10_000, 100, 10_000, 1),
				"nil dimension overrides position with full-server broadcast");
		LuaSoundAudience omitted = LuaSoundAudience.read(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("test:sound")}), null);
		require(!omitted.positional() && omitted.includes(NETHER, 10_000, 0, 10_000, 1), "All omitted coordinates allowed");
		for (int mask = 1; mask < 7; mask++) {
			final int selection = mask;
			invalid(() -> LuaSoundAudience.read(values((selection & 1) != 0 ? LuaValue.NIL : LuaValue.valueOf(0),
					(selection & 2) != 0 ? LuaValue.NIL : LuaValue.valueOf(64),
					(selection & 4) != 0 ? LuaValue.NIL : LuaValue.valueOf(0)), OVERWORLD), "Partial nil coordinates rejected " + mask);
		}
		for (double number : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
			invalid(() -> LuaSoundAudience.read(values(number, 64, 0), null), "Non-finite global coordinates still rejected");
		invalid(() -> LuaSoundAudience.read(values(LuaValue.valueOf("bad"), LuaValue.valueOf(64), LuaValue.valueOf(0)), OVERWORLD),
				"Wrong coordinate type rejected");
		System.out.println("Sound audience smoke passed: " + checks + " checks; nil coordinates, all dimensions, range and invalid groups");
	}
	private static Varargs values(double x, double y, double z) { return values(LuaValue.valueOf(x), LuaValue.valueOf(y), LuaValue.valueOf(z)); }
	private static Varargs values(LuaValue x, LuaValue y, LuaValue z) {
		return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("test:sound"), x, y, z});
	}
	private static void invalid(Runnable action, String message) {
		boolean rejected = false;
		try { action.run(); } catch (LuaError expected) { rejected = true; }
		require(rejected, message);
	}
	private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
