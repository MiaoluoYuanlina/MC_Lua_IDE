package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.loader.api.FabricLoader;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Persistent server-owned Lua and connection policy. Clients may only access it through the administrator protocol. */
public final class LuaLimitConfig {
	private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("mc_lua_ide").resolve("lua-limits.properties");
	private static volatile LuaLimits current = loadFromDisk();

	private LuaLimitConfig() {
	}

	public static LuaLimits current() {
		return current;
	}

	public static synchronized void save(LuaLimits limits) throws IOException {
		Files.createDirectories(CONFIG_FILE.getParent());
		Path temporary = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
		Files.writeString(temporary,
				"# MC Lua IDE server-side settings\n" + limits.encode(), StandardCharsets.UTF_8);
		try {
			Files.move(temporary, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
		}
		current = limits;
	}

	private static LuaLimits loadFromDisk() {
		if (!Files.isRegularFile(CONFIG_FILE)) return LuaLimits.defaults();
		try {
			return LuaLimits.decode(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8));
		} catch (Exception error) {
			McLuaIdeMod.LOGGER.error("Could not load {}; safe defaults will be used",
					CONFIG_FILE, error);
			return LuaLimits.defaults();
		}
	}
}
