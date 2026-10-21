package wiki.ica.xiaomiao.mcluaide.client;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** One terminal: the byte stream of a session decoded into an emulator, plus the view state (scroll position and selection). */
final class TerminalTab {
	/** What a tab talks to: a local pseudo terminal or the server. All methods must be cheap and thread-safe. */
	interface Session {
		void write(byte[] data);
		void resize(int cols, int rows);
		boolean alive();
		int exitCode();
		void close();
	}

	/** Creates the session once the tab exists, so that output can be routed into it from the first byte. */
	interface Factory { Session open(TerminalTab tab, int cols, int rows) throws Exception; }

	private static int nextId;
	final int id = ++nextId;
	final String shellName;
	final boolean server;
	final TerminalEmulator emulator;
	volatile Session session;
	volatile boolean exited;
	volatile String failure = "";
	// view state, touched on the render thread only
	int scrollOffset, lastScrollback;
	int selectionStartLine = -1, selectionStartColumn, selectionEndLine, selectionEndColumn;
	boolean selecting;

	private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
	private final ByteBuffer pending = ByteBuffer.allocate(8);
	private final Object decodeLock = new Object();

	TerminalTab(String shellName, boolean server, int cols, int rows) {
		this.shellName = shellName; this.server = server;
		emulator = new TerminalEmulator(cols, rows);
		emulator.onReply(text -> send(text));
		pending.flip();
	}

	String label() {
		String title = emulator.title();
		String base = title.isBlank() ? shellName : title;
		if (base.length() > 28) base = base.substring(0, 27) + "…";
		return (server ? "☁ " : "") + base + (exited ? " ✕" : "");
	}

	/** Bytes from the program, in any chunking: incomplete UTF-8 sequences wait for the rest. */
	void receive(byte[] data) {
		synchronized (decodeLock) {
			ByteBuffer input = ByteBuffer.allocate(pending.remaining() + data.length);
			input.put(pending).put(data).flip();
			CharBuffer out = CharBuffer.allocate(input.remaining() + 8);
			decoder.decode(input, out, false);
			pending.clear(); pending.put(input); pending.flip();
			out.flip();
			if (out.hasRemaining()) emulator.feed(out.toString());
		}
	}

	void send(String text) {
		Session current = session;
		if (current == null || text.isEmpty()) return;
		try { current.write(text.getBytes(StandardCharsets.UTF_8)); } catch (RuntimeException ignored) { }
	}

	void markExited() {
		exited = true;
		int code = session == null ? -1 : session.exitCode();
		receive(("\r\n\u001b[2m[" + LuaUiText.text("进程已结束", "Process exited") + (code >= 0 ? " · " + code : "") + "]\u001b[0m\r\n").getBytes(StandardCharsets.UTF_8));
	}

	void close() {
		Session current = session;
		session = null; exited = true;
		if (current != null) current.close();
	}

	boolean hasSelection() { return selectionStartLine >= 0 && (selectionStartLine != selectionEndLine || selectionStartColumn != selectionEndColumn); }
	void clearSelection() { selectionStartLine = -1; selecting = false; }

	String selectedText() {
		if (!hasSelection()) return "";
		return emulator.text(selectionStartLine, selectionStartColumn, selectionEndLine, selectionEndColumn);
	}

	/** True when the absolute line / column lies inside the selection (either drag direction). */
	boolean selected(int line, int column) {
		if (!hasSelection()) return false;
		int aLine = selectionStartLine, aColumn = selectionStartColumn, bLine = selectionEndLine, bColumn = selectionEndColumn;
		if (aLine > bLine || aLine == bLine && aColumn > bColumn) { int l = aLine, c = aColumn; aLine = bLine; aColumn = bColumn; bLine = l; bColumn = c; }
		if (line < aLine || line > bLine) return false;
		if (line == aLine && column < aColumn) return false;
		return !(line == bLine && column > bColumn);
	}
}
