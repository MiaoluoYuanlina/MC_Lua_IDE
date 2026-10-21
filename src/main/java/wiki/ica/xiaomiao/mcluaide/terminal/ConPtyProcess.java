package wiki.ica.xiaomiao.mcluaide.terminal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Windows 10 1809+ pseudo console (ConPTY) driven through the foreign-function API: no native library is shipped. */
final class ConPtyProcess implements PtyProcess {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
	private static final ValueLayout.OfLong H = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	private static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;

	private static MethodHandle function(String name, FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(KERNEL32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
	}
	private static final MethodHandle CREATE_PIPE = function("CreatePipe", FunctionDescriptor.of(I, P, P, P, I));
	private static final MethodHandle CREATE_PSEUDO_CONSOLE = function("CreatePseudoConsole", FunctionDescriptor.of(I, I, H, H, I, P));
	private static final MethodHandle RESIZE_PSEUDO_CONSOLE = function("ResizePseudoConsole", FunctionDescriptor.of(I, H, I));
	private static final MethodHandle CLOSE_PSEUDO_CONSOLE = function("ClosePseudoConsole", FunctionDescriptor.ofVoid(H));
	private static final MethodHandle INIT_ATTRIBUTES = function("InitializeProcThreadAttributeList", FunctionDescriptor.of(I, P, I, I, P));
	private static final MethodHandle UPDATE_ATTRIBUTE = function("UpdateProcThreadAttribute", FunctionDescriptor.of(I, P, I, H, H, H, P, P));
	private static final MethodHandle DELETE_ATTRIBUTES = function("DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(P));
	private static final MethodHandle CREATE_PROCESS = function("CreateProcessW", FunctionDescriptor.of(I, P, P, P, P, I, I, P, P, P, P));
	private static final MethodHandle READ_FILE = function("ReadFile", FunctionDescriptor.of(I, H, P, I, P, P));
	private static final MethodHandle WRITE_FILE = function("WriteFile", FunctionDescriptor.of(I, H, P, I, P, P));
	private static final MethodHandle CLOSE_HANDLE = function("CloseHandle", FunctionDescriptor.of(I, H));
	private static final MethodHandle EXIT_CODE = function("GetExitCodeProcess", FunctionDescriptor.of(I, H, P));
	private static final MethodHandle TERMINATE = function("TerminateProcess", FunctionDescriptor.of(I, H, I));
	private static final MethodHandle WAIT = function("WaitForSingleObject", FunctionDescriptor.of(I, H, I));

	private static final int EXTENDED_STARTUPINFO_PRESENT = 0x80000, CREATE_UNICODE_ENVIRONMENT = 0x400;
	private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
	private static final int STILL_ACTIVE = 259;

	private final long console, process, thread, inputWrite, outputRead;
	private final AtomicBoolean destroyed = new AtomicBoolean(), exitedReported = new AtomicBoolean();
	private final LinkedBlockingQueue<byte[]> pending = new LinkedBlockingQueue<>(4096);
	private final Runnable exited;
	private final AtomicBoolean consoleClosed = new AtomicBoolean();
	private volatile boolean handlesClosed;
	private volatile int exitCode = -1;

	ConPtyProcess(Shell shell, int cols, int rows, Consumer<byte[]> output, Runnable exited) throws IOException {
		this.exited = exited;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment readEnd = arena.allocate(8), writeEnd = arena.allocate(8);
			// Pipe 1: our keystrokes -> the console; pipe 2: the console's output -> us.
			if ((int) CREATE_PIPE.invokeExact(readEnd, writeEnd, MemorySegment.NULL, 0) == 0) throw new IOException("CreatePipe 失败");
			long inputRead = readEnd.get(H, 0); inputWrite = writeEnd.get(H, 0);
			if ((int) CREATE_PIPE.invokeExact(readEnd, writeEnd, MemorySegment.NULL, 0) == 0) { close(inputRead); close(inputWrite); throw new IOException("CreatePipe 失败"); }
			outputRead = readEnd.get(H, 0); long outputWrite = writeEnd.get(H, 0);

			MemorySegment consoleHandle = arena.allocate(8);
			int result = (int) CREATE_PSEUDO_CONSOLE.invokeExact(coord(cols, rows), inputRead, outputWrite, 0, consoleHandle);
			close(inputRead); close(outputWrite);
			if (result != 0) { close(inputWrite); close(outputRead); throw new IOException("系统不支持伪终端（需要 Windows 10 1809 或更新版本），HRESULT 0x" + Integer.toHexString(result)); }
			console = consoleHandle.get(H, 0);

			MemorySegment size = arena.allocate(8);
			int probe = (int) INIT_ATTRIBUTES.invokeExact(MemorySegment.NULL, 1, 0, size);
			MemorySegment attributes = arena.allocate(Math.max(64, size.get(H, 0)));
			if ((int) INIT_ATTRIBUTES.invokeExact(attributes, 1, 0, size) == 0) { cleanupFailed(); throw new IOException("InitializeProcThreadAttributeList 失败"); }
			if ((int) UPDATE_ATTRIBUTE.invokeExact(attributes, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, console, 8L, MemorySegment.NULL, MemorySegment.NULL) == 0) {
				DELETE_ATTRIBUTES.invokeExact(attributes); cleanupFailed(); throw new IOException("UpdateProcThreadAttribute 失败");
			}
			MemorySegment startup = arena.allocate(112); // STARTUPINFOEXW
			startup.set(I, 0, 112);
			// A redirected parent (a launcher, a test harness) would otherwise hand its own std handles to the child instead of the console.
			startup.set(I, 60, 0x100);
			startup.set(H, 80, -1L); startup.set(H, 88, -1L); startup.set(H, 96, -1L);
			startup.set(P, 104, attributes);
			MemorySegment info = arena.allocate(24);
			MemorySegment commandLine = wide(arena, commandLine(shell.command()), true);
			MemorySegment directory = shell.directory() == null ? MemorySegment.NULL : wide(arena, shell.directory().toString(), false);
			int created = (int) CREATE_PROCESS.invokeExact(MemorySegment.NULL, commandLine, MemorySegment.NULL, MemorySegment.NULL, 0,
					EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT, MemorySegment.NULL, directory, startup, info);
			DELETE_ATTRIBUTES.invokeExact(attributes);
			if (created == 0) { cleanupFailed(); throw new IOException("无法启动 " + shell.name() + "：" + shell.command().getFirst()); }
			process = info.get(H, 0); thread = info.get(H, 8);
			close(thread);
		} catch (IOException error) {
			throw error;
		} catch (Throwable error) {
			throw new IOException("无法创建伪终端：" + error, error);
		}
		Thread reader = new Thread(() -> readLoop(output), "terminal-conpty-reader");
		reader.setDaemon(true); reader.start();
		// Older ConPTY builds keep the output pipe open after the program ends: closing the console is what delivers the end of the stream.
		Thread watcher = new Thread(() -> {
			try { int ignored = (int) WAIT.invokeExact(process, -1); } catch (Throwable error) { return; }
			closeConsole();
		}, "terminal-conpty-watch");
		watcher.setDaemon(true); watcher.start();
		Thread writer = new Thread(this::writeLoop, "terminal-conpty-writer");
		writer.setDaemon(true); writer.start();
	}

	private void cleanupFailed() {
		try { CLOSE_PSEUDO_CONSOLE.invokeExact(console); } catch (Throwable ignored) { }
		close(inputWrite); close(outputRead);
	}

	private static int coord(int cols, int rows) { return (cols & 0xffff) | (rows << 16); }

	private static String commandLine(java.util.List<String> command) {
		StringBuilder line = new StringBuilder();
		for (String part : command) {
			if (!line.isEmpty()) line.append(' ');
			if (!part.isEmpty() && part.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) line.append(part);
			else line.append('"').append(part.replace("\"", "\\\"")).append('"');
		}
		return line.toString();
	}

	private static MemorySegment wide(Arena arena, String text, boolean writable) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_16LE);
		MemorySegment segment = arena.allocate(bytes.length + 2L);
		MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
		return segment;
	}

	private static void close(long handle) { try { int ignored = (int) CLOSE_HANDLE.invokeExact(handle); } catch (Throwable ignored) { } }

	private void readLoop(Consumer<byte[]> output) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(16384), count = arena.allocate(4);
			while (!destroyed.get()) {
				int ok = (int) READ_FILE.invokeExact(outputRead, buffer, 16384, count, MemorySegment.NULL);
				int n = count.get(I, 0);
				if (ok == 0 || n <= 0) break;
				output.accept(buffer.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE));
			}
		} catch (Throwable ignored) {
		} finally {
			updateExit();
			handlesClosed = true;
			close(outputRead); close(inputWrite); close(process);
			reportExit();
		}
	}

	private void closeConsole() {
		if (!consoleClosed.compareAndSet(false, true)) return;
		try { CLOSE_PSEUDO_CONSOLE.invokeExact(console); } catch (Throwable ignored) { }
	}

	private void writeLoop() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(8192), count = arena.allocate(4);
			while (!destroyed.get()) {
				byte[] data = pending.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (data == null) continue;
				for (int offset = 0; offset < data.length && !destroyed.get(); ) {
					int n = Math.min(8192, data.length - offset);
					MemorySegment.copy(data, offset, buffer, ValueLayout.JAVA_BYTE, 0, n);
					int ok = (int) WRITE_FILE.invokeExact(inputWrite, buffer, n, count, MemorySegment.NULL);
					if (ok == 0) return;
					offset += Math.max(1, count.get(I, 0));
				}
			}
		} catch (Throwable ignored) { }
	}

	private void updateExit() {
		try (Arena arena = Arena.ofConfined()) {
			// The output pipe closes when the console closes; give the process a moment to publish its exit status.
			int wait = (int) WAIT.invokeExact(process, 1500);
			MemorySegment code = arena.allocate(4);
			int ok = (int) EXIT_CODE.invokeExact(process, code);
			int value = code.get(I, 0);
			exitCode = ok != 0 && value != STILL_ACTIVE ? value : wait == 0 ? 0 : -1;
		} catch (Throwable ignored) { }
	}

	private void reportExit() { if (exitedReported.compareAndSet(false, true)) exited.run(); }

	@Override public void write(byte[] data) {
		if (destroyed.get() || data.length == 0) return;
		if (!pending.offer(data)) throw new IllegalStateException("终端输入队列已满");
	}

	@Override public void resize(int cols, int rows) {
		if (destroyed.get()) return;
		try { int ignored = (int) RESIZE_PSEUDO_CONSOLE.invokeExact(console, coord(Math.clamp(cols, 2, 500), Math.clamp(rows, 1, 300))); } catch (Throwable ignored) { }
	}

	@Override public boolean alive() {
		if (destroyed.get() || handlesClosed) return false;
		try { return (int) WAIT.invokeExact(process, 0) == 0x102; } catch (Throwable error) { return false; }
	}

	@Override public int exitCode() { return alive() ? -1 : exitCode; }

	@Override public void destroy() {
		if (!destroyed.compareAndSet(false, true)) return;
		try { int ignored = (int) TERMINATE.invokeExact(process, 1); } catch (Throwable ignored) { }
		// ClosePseudoConsole can block while its output is unread, so it runs on its own thread; the reader then ends and closes the handles.
		Thread closer = new Thread(this::closeConsole, "terminal-conpty-closer");
		closer.setDaemon(true); closer.start();
		reportExit();
	}
}
