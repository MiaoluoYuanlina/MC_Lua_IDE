package wiki.ica.xiaomiao.mcluaide.terminal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A child process attached to a pseudo terminal, shared by the client's local terminal and the server terminal.
 * Windows uses ConPTY through the Java foreign-function API, other systems use a {@code script}-allocated pty (see {@link PosixPtyProcess}).
 */
public interface PtyProcess {
	/** What to launch: a label for menus, the command line and the working directory. */
	record Shell(String name, List<String> command, Path directory) { }

	/** Queues bytes for the program's stdin; never blocks the caller. */
	void write(byte[] data);
	void resize(int cols, int rows);
	boolean alive();
	/** The exit code once {@link #alive()} is false, otherwise -1. */
	int exitCode();
	/** Terminates the program (and its console) and releases every handle. Safe to call more than once. */
	void destroy();

	/** Starts the shell. {@code output} runs on a background thread for each chunk; {@code exited} runs once when the program ends. */
	static PtyProcess start(Shell shell, int cols, int rows, Consumer<byte[]> output, Runnable exited) throws IOException {
		cols = Math.clamp(cols, 2, 500); rows = Math.clamp(rows, 1, 300);
		if (TerminalShells.windows()) return new ConPtyProcess(shell, cols, rows, output, exited);
		return new PosixPtyProcess(shell, cols, rows, output, exited);
	}
}
