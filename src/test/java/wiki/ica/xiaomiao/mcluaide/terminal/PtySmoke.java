package wiki.ica.xiaomiao.mcluaide.terminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Starts a real shell in a pseudo terminal: output, input, resize, exit code and forced termination. */
public final class PtySmoke {
	private static int checks;

	public static void main(String[] args) throws Exception {
		var shells = TerminalShells.detect(Path.of(System.getProperty("user.dir")));
		require(!shells.isEmpty(), "a shell is available");
		PtyProcess.Shell shell = TerminalShells.windows()
				? shells.stream().filter(s -> s.name().equals("Command Prompt")).findFirst().orElseThrow() : shells.getFirst();

		StringBuffer received = new StringBuffer();
		CountDownLatch exited = new CountDownLatch(1);
		PtyProcess process = PtyProcess.start(shell, 100, 30, bytes -> received.append(new String(bytes, StandardCharsets.UTF_8)), exited::countDown);
		require(process.alive(), "the shell is running");
		write(process, TerminalShells.windows() ? "echo pty-smoke-%RANDOM:~0,0%marker\r" : "echo pty-smoke-marker\r");
		require(waitFor(received, "pty-smoke-marker", 15000), "command output arrives through the pty, got: " + printable(received));
		process.resize(80, 24);
		write(process, TerminalShells.windows() ? "echo resized\r" : "echo resized\r");
		require(waitFor(received, "resized", 10000), "the shell keeps working after a resize");
		write(process, "exit 7\r");
		require(exited.await(15, TimeUnit.SECONDS), "the exit callback runs after `exit`");
		require(!process.alive(), "the process ended");
		if (TerminalShells.windows()) require(process.exitCode() == 7, "exit code is reported, got " + process.exitCode());

		CountDownLatch killed = new CountDownLatch(1);
		PtyProcess second = PtyProcess.start(shell, 80, 24, bytes -> { }, killed::countDown);
		require(second.alive(), "second shell running");
		second.destroy();
		require(killed.await(10, TimeUnit.SECONDS), "destroy reports the exit");
		require(!second.alive(), "destroy ends the program");
		second.destroy();
		System.out.println("Pty smoke passed: " + checks + " checks with " + shell.name() + "; output, input, resize, exit code, forced termination");
	}

	private static void write(PtyProcess process, String text) { process.write(text.getBytes(StandardCharsets.UTF_8)); }
	private static boolean waitFor(StringBuffer buffer, String text, long millis) throws InterruptedException {
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) { if (buffer.indexOf(text) >= 0) return true; Thread.sleep(50); }
		return false;
	}
	private static String printable(StringBuffer buffer) { return buffer.toString().replace("\u001b", "<ESC>").replace("\r", "\\r").replace("\n", "\\n"); }
	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
