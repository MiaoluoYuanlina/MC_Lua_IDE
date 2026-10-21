package wiki.ica.xiaomiao.mcluaide.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

/** The server terminal's policy: off by default, owner only, bounded, and a working shell when enabled. */
public final class ServerTerminalSmoke {
	private static int checks;

	public static void main(String[] args) throws Exception {
		Path directory = Files.createTempDirectory("mc-lua-ide-terminal-smoke");
		ServerTerminalService.useDirectoryForTests(directory);
		UUID owner = UUID.randomUUID(), other = UUID.randomUUID();

		require(!ServerTerminalService.settings().enabled(), "disabled by default");
		require(Files.isRegularFile(directory.resolve("mc_lua_ide").resolve("terminal.properties")), "a documented template is written on first use");
		try { ServerTerminalService.open(owner, "owner", "{}"); require(false, "a disabled terminal must refuse"); }
		catch (IllegalStateException refused) { require(refused.getMessage().contains("terminal.properties"), "the refusal names the file to edit"); }

		Files.writeString(directory.resolve("mc_lua_ide").resolve("terminal.properties"), "enabled=true\nshell=" + (System.getProperty("os.name").toLowerCase().contains("win") ? "Command Prompt" : "") + "\nmaxSessionsPerPlayer=2\nidleMinutes=30\n", StandardCharsets.UTF_8);
		require(ServerTerminalService.settings().enabled(), "enabled by editing the file");

		JsonObject opened = JsonParser.parseString(ServerTerminalService.open(owner, "owner", "{\"cols\":100,\"rows\":30}")).getAsJsonObject();
		String id = opened.get("id").getAsString();
		String marker = "srv-term-ok";
		String command = (System.getProperty("os.name").toLowerCase().contains("win") ? "echo srv-term-%RANDOM:~0,0%ok" : "echo srv-term-ok") + "\r";
		poll(owner, id, command);
		StringBuilder seen = new StringBuilder();
		long end = System.currentTimeMillis() + 15000;
		while (System.currentTimeMillis() < end && !seen.toString().contains(marker)) { Thread.sleep(80); seen.append(poll(owner, id, null)); }
		require(seen.toString().contains(marker), "the shell answers: " + seen);

		try { ServerTerminalService.io(other, id, "{}"); require(false, "another player must not reach the session"); }
		catch (IllegalArgumentException denied) { checks++; }
		ServerTerminalService.close(other, id);
		require(JsonParser.parseString(ServerTerminalService.io(owner, id, "{}")).getAsJsonObject().get("alive").getAsBoolean(), "another player cannot close it");

		try { ServerTerminalService.io(owner, id, "{\"in\":\"" + Base64.getEncoder().encodeToString(new byte[20_000]) + "\"}"); require(false, "oversized input is rejected"); }
		catch (IllegalArgumentException tooLarge) { checks++; }

		ServerTerminalService.open(owner, "owner", "{}");
		try { ServerTerminalService.open(owner, "owner", "{}"); require(false, "the per-player limit applies"); }
		catch (IllegalStateException limit) { checks++; }

		ServerTerminalService.closeAll(owner);
		try { ServerTerminalService.io(owner, id, "{}"); require(false, "closeAll ends every session of the player"); }
		catch (IllegalArgumentException gone) { checks++; }

		ServerTerminalService.shutdown();
		System.out.println("Server terminal smoke passed: " + checks + " checks; disabled by default, file-only enabling, owner isolation, bounds, close on leave");
		System.exit(0);
	}

	private static String poll(UUID player, String id, String input) {
		JsonObject body = new JsonObject();
		if (input != null) body.addProperty("in", Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8)));
		JsonObject reply = JsonParser.parseString(ServerTerminalService.io(player, id, body.toString())).getAsJsonObject();
		return new String(Base64.getDecoder().decode(reply.get("data").getAsString()), StandardCharsets.UTF_8);
	}

	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
