package wiki.ica.xiaomiao.mcluaide.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Linux / macOS: the system {@code script} utility allocates the pseudo terminal, so no native code is needed.
 * The terminal size is fixed when the program starts (the pty cannot be resized from outside {@code script}); the shell is told the
 * current size through {@code stty} when it changes. Without {@code script} the shell still runs, but without a controlling terminal.
 */
final class PosixPtyProcess implements PtyProcess {
	private final Process process;
	private final OutputStream stdin;
	private final LinkedBlockingQueue<byte[]> pending = new LinkedBlockingQueue<>(4096);
	private final AtomicBoolean destroyed = new AtomicBoolean(), exitedReported = new AtomicBoolean();
	private final Runnable exited;
	private final boolean hasPty;

	PosixPtyProcess(Shell shell, int cols, int rows, Consumer<byte[]> output, Runnable exited) throws IOException {
		this.exited = exited;
		String script = TerminalShells.onPath("script");
		hasPty = script != null;
		List<String> command = new ArrayList<>();
		if (hasPty) {
			command.add(script);
			String line = "stty cols " + cols + " rows " + rows + " 2>/dev/null; exec " + quoted(shell.command());
			if (TerminalShells.mac_()) { command.add("-q"); command.add("/dev/null"); command.addAll(shell.command()); }
			else { command.add("-qfc"); command.add(line); command.add("/dev/null"); }
		} else command.addAll(shell.command());
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		if (shell.directory() != null) builder.directory(shell.directory().toFile());
		builder.environment().put("TERM", "xterm-256color");
		builder.environment().put("COLORTERM", "truecolor");
		builder.environment().put("COLUMNS", Integer.toString(cols));
		builder.environment().put("LINES", Integer.toString(rows));
		process = builder.start();
		stdin = process.getOutputStream();
		Thread reader = new Thread(() -> readLoop(process.getInputStream(), output), "terminal-pty-reader");
		reader.setDaemon(true); reader.start();
		Thread writer = new Thread(this::writeLoop, "terminal-pty-writer");
		writer.setDaemon(true); writer.start();
	}

	private static String quoted(List<String> command) {
		StringBuilder line = new StringBuilder();
		for (String part : command) { if (!line.isEmpty()) line.append(' '); line.append('\'').append(part.replace("'", "'\\''")).append('\''); }
		return line.toString();
	}

	private void readLoop(InputStream in, Consumer<byte[]> output) {
		byte[] buffer = new byte[16384];
		try {
			int n;
			while (!destroyed.get() && (n = in.read(buffer)) > 0) output.accept(java.util.Arrays.copyOf(buffer, n));
		} catch (IOException ignored) {
		} finally {
			if (exitedReported.compareAndSet(false, true)) exited.run();
		}
	}

	private void writeLoop() {
		try {
			while (!destroyed.get()) {
				byte[] data = pending.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (data == null) continue;
				stdin.write(data); stdin.flush();
			}
		} catch (IOException | InterruptedException ignored) { }
	}

	@Override public void write(byte[] data) {
		if (destroyed.get() || data.length == 0) return;
		if (!pending.offer(data)) throw new IllegalStateException("终端输入队列已满");
	}

	@Override public void resize(int cols, int rows) {
		if (destroyed.get() || !hasPty) return;
		// Executed by the interactive shell itself; the leading space keeps it out of most shells' history.
		write((" stty cols " + Math.clamp(cols, 2, 500) + " rows " + Math.clamp(rows, 1, 300) + " 2>/dev/null\r").getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Override public boolean alive() { return !destroyed.get() && process.isAlive(); }
	@Override public int exitCode() { return process.isAlive() ? -1 : process.exitValue(); }

	@Override public void destroy() {
		if (!destroyed.compareAndSet(false, true)) return;
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		if (exitedReported.compareAndSet(false, true)) exited.run();
	}
}
