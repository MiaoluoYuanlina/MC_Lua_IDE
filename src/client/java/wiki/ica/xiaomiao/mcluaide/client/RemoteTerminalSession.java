package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * A terminal that runs on the game server. The protocol is request / response, so the session polls: each poll carries the keystrokes
 * collected since the last one and returns the output produced meanwhile. Polling is quick while there is traffic and slows down when idle.
 */
final class RemoteTerminalSession implements TerminalTab.Session {
	private static final long FAST_MS = 40, SLOW_MS = 300, IDLE_AFTER_MS = 3000;
	private final TerminalTab tab;
	private final Object lock = new Object();
	private final ByteArrayOutputStream pendingInput = new ByteArrayOutputStream();
	private String id = "";
	private int cols, rows, reportedCols, reportedRows;
	private boolean polling, closed, alive = true;
	private int exitCode = -1;
	private long lastActivity = System.currentTimeMillis();

	private RemoteTerminalSession(TerminalTab tab, int cols, int rows) { this.tab = tab; this.cols = cols; this.rows = rows; reportedCols = cols; reportedRows = rows; }

	/** Opens the terminal asynchronously; the tab shows the outcome (a prompt, or the server's reason for refusing). */
	static void open(TerminalTab tab, int cols, int rows) {
		var session = new RemoteTerminalSession(tab, cols, rows);
		tab.session = session;
		JsonObject body = new JsonObject();
		body.addProperty("cols", cols); body.addProperty("rows", rows);
		ScriptManager.terminalOpen(body.toString()).whenComplete((data, error) -> {
			if (error != null) { session.fail(ScriptManager.message(error)); return; }
			try {
				JsonObject reply = JsonParser.parseString(data).getAsJsonObject();
				synchronized (session.lock) { session.id = reply.get("id").getAsString(); }
				tab.receive(("\u001b[2J\u001b[H").getBytes(StandardCharsets.UTF_8));
				session.schedule(0);
			} catch (RuntimeException invalid) { session.fail(LuaUiText.text("服务器返回了无效的终端响应", "The server sent an invalid terminal response")); }
		});
	}

	private void fail(String message) {
		synchronized (lock) { alive = false; closed = true; }
		tab.receive(("\u001b[31m" + message + "\u001b[0m\r\n").getBytes(StandardCharsets.UTF_8));
		tab.exited = true;
	}

	private void schedule(long delay) {
		synchronized (lock) { if (closed || polling || id.isEmpty()) return; polling = true; }
		CompletableFutureHelper.after(delay, this::poll);
	}

	private void poll() {
		String sessionId; String body;
		synchronized (lock) {
			if (closed) { polling = false; return; }
			sessionId = id;
			JsonObject request = new JsonObject();
			if (pendingInput.size() > 0) { request.addProperty("in", Base64.getEncoder().encodeToString(pendingInput.toByteArray())); pendingInput.reset(); }
			if (cols != reportedCols || rows != reportedRows) { request.addProperty("cols", cols); request.addProperty("rows", rows); reportedCols = cols; reportedRows = rows; }
			body = request.toString();
		}
		ScriptManager.terminalIo(sessionId, body).whenComplete((data, error) -> {
			long next = SLOW_MS;
			try {
				if (error != null) { fail(ScriptManager.message(error)); return; }
				JsonObject reply = JsonParser.parseString(data).getAsJsonObject();
				byte[] output = Base64.getDecoder().decode(reply.get("data").getAsString());
				if (output.length > 0) { tab.receive(output); lastActivity = System.currentTimeMillis(); }
				if (reply.get("dropped").getAsBoolean()) tab.receive(("\r\n\u001b[2m[" + LuaUiText.text("输出已丢弃", "Output was dropped") + "]\u001b[0m\r\n").getBytes(StandardCharsets.UTF_8));
				if (!reply.get("alive").getAsBoolean()) {
					synchronized (lock) { alive = false; closed = true; exitCode = reply.get("exit").getAsInt(); }
					tab.markExited();
					return;
				}
				boolean more = reply.get("more").getAsBoolean();
				next = more || System.currentTimeMillis() - lastActivity < IDLE_AFTER_MS ? FAST_MS : SLOW_MS;
				if (more) next = 0;
			} catch (RuntimeException invalid) {
				fail(LuaUiText.text("服务器返回了无效的终端响应", "The server sent an invalid terminal response")); return;
			} finally {
				synchronized (lock) { polling = false; }
			}
			schedule(next);
		});
	}

	@Override public void write(byte[] data) {
		synchronized (lock) {
			if (closed) return;
			if (pendingInput.size() + data.length > 64 * 1024) return;
			pendingInput.writeBytes(data); lastActivity = System.currentTimeMillis();
		}
		schedule(0);
	}

	@Override public void resize(int cols, int rows) {
		synchronized (lock) { this.cols = cols; this.rows = rows; }
		schedule(0);
	}

	@Override public boolean alive() { synchronized (lock) { return alive; } }
	@Override public int exitCode() { synchronized (lock) { return exitCode; } }

	@Override public void close() {
		String sessionId;
		synchronized (lock) { if (closed && id.isEmpty()) return; closed = true; alive = false; sessionId = id; }
		if (!sessionId.isEmpty() && ScriptManager.isServerAvailable()) ScriptManager.terminalClose(sessionId);
	}

	/** Runs a task after a delay without holding a thread. */
	private static final class CompletableFutureHelper {
		static void after(long delayMs, Runnable task) {
			java.util.concurrent.CompletableFuture.delayedExecutor(Math.max(0, delayMs), TimeUnit.MILLISECONDS, Minecraft.getInstance()::execute).execute(task);
		}
	}
}
