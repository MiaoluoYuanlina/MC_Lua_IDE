package wiki.ica.xiaomiao.mcluaide.server;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

/** Server-wide opt-in, applied on the server thread without blocking networking or debug controls. */
public final class LuaDebuggerGamePause {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/debugger.properties");
	private static final Map<ServerTickRateManager, LuaDebuggerFreezeLease> LEASES = new WeakHashMap<>();
	private static final Map<ServerPlayer, Boolean> CAMERAS = new WeakHashMap<>();
	private static volatile boolean enabled = load("pauseGameOnBreakpoint", false);
	private static volatile boolean allowPlayerMovement = load("allowPlayerMovement", true);
	private static volatile boolean movementControlsActive;
	private LuaDebuggerGamePause() { }
	static boolean enabled() { return enabled; }
	static boolean allowPlayerMovement() { return allowPlayerMovement; }
	private static boolean load(String key, boolean defaultValue) {
		if (!Files.isRegularFile(FILE)) return defaultValue;
		try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Properties settings = new Properties(); settings.load(reader);
			return Boolean.parseBoolean(settings.getProperty(key, Boolean.toString(defaultValue)));
		} catch (IOException error) { McLuaIdeMod.LOGGER.warn("Could not load debugger game pause setting", error); return defaultValue; }
	}
	static void setEnabled(boolean value) throws IOException {
		save("pauseGameOnBreakpoint", value); enabled = value;
	}
	static void setAllowPlayerMovement(boolean value) throws IOException {
		save("allowPlayerMovement", value); allowPlayerMovement = value;
	}
	private static void save(String key, boolean value) throws IOException {
		Properties settings = new Properties();
		if (Files.isRegularFile(FILE)) try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { settings.load(reader); }
		settings.setProperty(key, Boolean.toString(value));
		Files.createDirectories(FILE.getParent());
		Path temporary = FILE.resolveSibling("debugger.properties.tmp");
		try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { settings.store(writer, "Server-wide Lua debugger options"); }
		try { Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
		catch (AtomicMoveNotSupportedException error) { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING); }
	}
	static synchronized void tick(MinecraftServer server, LuaDebuggerState state) {
		if (server == null) return;
		CAMERAS.keySet().removeIf(player -> player.isRemoved() || !player.connection.isAcceptingMessages()
				|| !(player.level().getServer().isSingleplayerOwner(player.nameAndId())
						|| player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER))
				|| player.level().getServer() == server && !(state.running() && state.enabled() && state.paused()));
		ServerTickRateManager ticks = server.tickRateManager();
		LuaDebuggerFreezeLease lease = LEASES.computeIfAbsent(ticks, ignored -> new LuaDebuggerFreezeLease());
		lease.update(state.id() + ":" + state.sequence(), enabled && state.running() && state.enabled() && state.paused(),
				ticks::isFrozen, ticks::setFrozen);
		refreshMovementActive();
	}
	static synchronized boolean ownsFreeze(MinecraftServer server) {
		if (server == null) return false;
		var lease = LEASES.get(server.tickRateManager()); return lease != null && lease.owned();
	}
	/** Only this debugger's freeze locks movement; an unrelated vanilla /tick freeze is untouched. */
	public static boolean movementLocked(MinecraftServer server) {
		return !allowPlayerMovement && ownsFreeze(server);
	}
	public static boolean movementControlsActive() { return movementControlsActive; }
	public static boolean movementLocked(ServerPlayer player) {
		if (!movementControlsActive) return false;
		synchronized (LuaDebuggerGamePause.class) { return CAMERAS.containsKey(player) || movementLocked(player.level().getServer()); }
	}
	static synchronized void cameraMode(ServerPlayer player, boolean active) {
		if (active) CAMERAS.put(player, true); else CAMERAS.remove(player);
		if (active) player.setLastClientInput(net.minecraft.world.entity.player.Input.EMPTY);
		refreshMovementActive();
	}
	private static void refreshMovementActive() {
		movementControlsActive = !CAMERAS.isEmpty() || !allowPlayerMovement && LEASES.values().stream().anyMatch(LuaDebuggerFreezeLease::owned);
	}
	/** Observation only: vanilla commands/API calls are never cancelled or rewritten. */
	public static synchronized void externalFreezeChange(ServerTickRateManager ticks) {
		var lease = LEASES.get(ticks); if (lease != null) lease.externalChange();
		refreshMovementActive();
	}
}
