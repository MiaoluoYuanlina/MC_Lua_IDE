package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.terminal.PtyProcess;
import wiki.ica.xiaomiao.mcluaide.terminal.TerminalShells;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shells on the machine that runs the game server, for server owners (operator level 4) only. This is a remote shell, so it is
 * OFF until the server operator sets {@code enabled=true} in {@code config/mc_lua_ide/terminal.properties}; the file cannot be
 * changed through the protocol. Sessions belong to the player who opened them, are limited in number, end after an idle period,
 * when the player disconnects or loses operator status, and every open/close is written to the server log.
 */
public final class ServerTerminalService {
	private static volatile Path testDirectory;
	/** Smoke tests run without a loader: they point the service at a scratch directory (config and shell working directory). */
	public static void useDirectoryForTests(Path directory) { testDirectory = directory; }
	private static Path configFile() {
		Path override = testDirectory;
		return (override != null ? override : FabricLoader.getInstance().getConfigDir()).resolve("mc_lua_ide").resolve("terminal.properties");
	}
	private static Path workingDirectory() { return testDirectory != null ? testDirectory : FabricLoader.getInstance().getGameDir(); }
	private static final int MAX_INPUT_BYTES = 16 * 1024, MAX_OUTPUT_PER_POLL = 48 * 1024, MAX_BUFFER = 512 * 1024, GLOBAL_SESSIONS = 8;
	private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService SWEEPER = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "mc-lua-ide-terminal-sweeper"); thread.setDaemon(true); return thread;
	});
	static {
		SWEEPER.scheduleWithFixedDelay(ServerTerminalService::sweep, 30, 30, TimeUnit.SECONDS);
	}

	/** Operator-controlled settings, re-read on every open so that changes apply without a restart. */
	public record Settings(boolean enabled, String shell, int maxSessionsPerPlayer, int idleMinutes) {
		static Settings defaults() { return new Settings(false, "", 2, 30); }
	}

	private static final class Session {
		final String id = UUID.randomUUID().toString();
		final UUID owner; final String ownerName; final String shellName;
		PtyProcess process;
		final ArrayDeque<byte[]> output = new ArrayDeque<>();
		int buffered; boolean dropped;
		volatile long lastActive = System.nanoTime();
		volatile boolean exited;
		Session(UUID owner, String ownerName, String shellName) { this.owner = owner; this.ownerName = ownerName; this.shellName = shellName; }
		synchronized void append(byte[] data) {
			output.addLast(data); buffered += data.length;
			while (buffered > MAX_BUFFER && output.size() > 1) { buffered -= output.pollFirst().length; dropped = true; }
		}
		synchronized byte[] drain(int max) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			while (!output.isEmpty() && out.size() < max) {
				byte[] head = output.peekFirst();
				int room = max - out.size();
				if (head.length <= room) { out.writeBytes(head); output.pollFirst(); buffered -= head.length; }
				else { out.write(head, 0, room); output.pollFirst(); output.addFirst(java.util.Arrays.copyOfRange(head, room, head.length)); buffered -= room; }
			}
			return out.toByteArray();
		}
		synchronized boolean hasOutput() { return !output.isEmpty(); }
	}

	private ServerTerminalService() { }

	public static Settings settings() {
		if (!Files.isRegularFile(configFile())) { writeTemplate(); return Settings.defaults(); }
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(configFile())) { properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
		catch (IOException error) { return Settings.defaults(); }
		try {
			return new Settings(Boolean.parseBoolean(properties.getProperty("enabled", "false").strip()), properties.getProperty("shell", "").strip(),
					Math.clamp(Integer.parseInt(properties.getProperty("maxSessionsPerPlayer", "2").strip()), 1, 4),
					Math.clamp(Integer.parseInt(properties.getProperty("idleMinutes", "30").strip()), 1, 24 * 60));
		} catch (NumberFormatException error) { return Settings.defaults(); }
	}

	private static void writeTemplate() {
		try {
			Files.createDirectories(configFile().getParent());
			Files.writeString(configFile(), """
					# MC Lua IDE server terminal.
					# A server terminal is a shell on THIS machine, available to server owners (operator level 4) through the IDE.
					# That is full remote command execution as the user running the server: leave it disabled unless you need it.
					enabled=false
					# Shell name as shown in the IDE (e.g. PowerShell, Command Prompt, bash); blank = the default shell.
					shell=
					maxSessionsPerPlayer=2
					# Sessions with no input or polling for this long are ended.
					idleMinutes=30
					""", StandardCharsets.UTF_8);
		} catch (IOException ignored) { }
	}

	/** Body: {"cols":100,"rows":30}. Returns {"id":"...","shell":"..."}; throws a message that is shown to the user when refused. */
	public static String open(UUID playerId, String playerName, String body) throws IOException {
		Settings settings = settings();
		if (!settings.enabled()) {
			McLuaIdeMod.LOGGER.warn("{} tried to open a server terminal, but it is disabled in {}", playerName, configFile());
			throw new IllegalStateException("服务器未启用终端：服务器管理员需在 config/mc_lua_ide/terminal.properties 中设置 enabled=true（Server terminal is disabled; set enabled=true in config/mc_lua_ide/terminal.properties）");
		}
		JsonObject request = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
		int cols = request.has("cols") ? request.get("cols").getAsInt() : 100, rows = request.has("rows") ? request.get("rows").getAsInt() : 30;
		sweep();
		long own = SESSIONS.values().stream().filter(s -> s.owner.equals(playerId) && !s.exited).count();
		if (own >= settings.maxSessionsPerPlayer()) throw new IllegalStateException("已达到每位玩家的服务器终端数量上限（" + settings.maxSessionsPerPlayer() + "）");
		if (SESSIONS.values().stream().filter(s -> !s.exited).count() >= GLOBAL_SESSIONS) throw new IllegalStateException("服务器终端总数已达上限");
		Path directory = workingDirectory();
		PtyProcess.Shell shell = TerminalShells.find(settings.shell(), directory);
		Session session = new Session(playerId, playerName, shell.name());
		session.process = PtyProcess.start(shell, cols, rows, session::append, () -> {
			session.exited = true;
			McLuaIdeMod.LOGGER.info("Server terminal {} of {} ended", session.id, session.ownerName);
		});
		SESSIONS.put(session.id, session);
		McLuaIdeMod.LOGGER.warn("AUDIT: {} ({}) opened server terminal {} running {}", playerName, playerId, session.id, shell.name());
		JsonObject reply = new JsonObject();
		reply.addProperty("id", session.id); reply.addProperty("shell", shell.name());
		return reply.toString();
	}

	/** Body: {"in":"base64","cols":..,"rows":..}. Returns {"data":"base64","alive":true,"exit":-1,"dropped":false}. */
	public static String io(UUID playerId, String id, String body) {
		Session session = owned(playerId, id);
		JsonObject request = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
		session.lastActive = System.nanoTime();
		if (request.has("in")) {
			byte[] input = Base64.getDecoder().decode(request.get("in").getAsString());
			if (input.length > MAX_INPUT_BYTES) throw new IllegalArgumentException("终端输入过大");
			if (input.length > 0) session.process.write(input);
		}
		if (request.has("cols") && request.has("rows")) session.process.resize(request.get("cols").getAsInt(), request.get("rows").getAsInt());
		byte[] data = session.drain(MAX_OUTPUT_PER_POLL);
		boolean dropped;
		synchronized (session) { dropped = session.dropped; session.dropped = false; }
		JsonObject reply = new JsonObject();
		reply.addProperty("data", Base64.getEncoder().encodeToString(data));
		boolean finished = session.exited && !session.hasOutput();
		reply.addProperty("alive", !finished);
		reply.addProperty("exit", finished ? session.process.exitCode() : -1);
		reply.addProperty("dropped", dropped);
		reply.addProperty("more", session.hasOutput());
		if (finished) SESSIONS.remove(session.id);
		return reply.toString();
	}

	public static String close(UUID playerId, String id) {
		Session session = SESSIONS.get(id);
		if (session != null && session.owner.equals(playerId)) terminate(session, "closed by the player");
		return "closed";
	}

	/** The player left or lost operator status: all of their shells end. */
	public static void closeAll(UUID playerId) {
		for (Session session : List.copyOf(SESSIONS.values())) if (session.owner.equals(playerId)) terminate(session, "player left or lost permission");
	}

	public static void shutdown() {
		for (Session session : List.copyOf(SESSIONS.values())) terminate(session, "server stopping");
	}

	private static Session owned(UUID playerId, String id) {
		Session session = SESSIONS.get(id);
		if (session == null || !session.owner.equals(playerId)) throw new IllegalArgumentException("终端会话不存在或已结束");
		return session;
	}

	private static void terminate(Session session, String reason) {
		if (SESSIONS.remove(session.id) == null) return;
		session.process.destroy();
		McLuaIdeMod.LOGGER.warn("AUDIT: server terminal {} of {} ended ({})", session.id, session.ownerName, reason);
	}

	private static void sweep() {
		long limit = TimeUnit.MINUTES.toNanos(settings().idleMinutes());
		long now = System.nanoTime();
		for (Session session : List.copyOf(SESSIONS.values())) {
			if (now - session.lastActive > limit) terminate(session, "idle timeout");
			else if (session.exited && now - session.lastActive > TimeUnit.MINUTES.toNanos(2)) SESSIONS.remove(session.id);
		}
		if (!settings().enabled()) for (Session session : List.copyOf(SESSIONS.values())) terminate(session, "terminal disabled in the configuration");
	}
}
