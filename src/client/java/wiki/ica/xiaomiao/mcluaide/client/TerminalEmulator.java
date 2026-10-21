package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A VT100/xterm-style terminal screen: it consumes the text a pseudo terminal produces and keeps a grid of cells plus a scrollback.
 * Supports cursor addressing, scroll regions, SGR (16 / 256 / true colour), erase and insert/delete operations, tab stops, the alternate
 * screen, DEC line drawing, bracketed paste, application cursor keys, mouse reporting modes, wide characters and window titles.
 * Deliberately not supported (ignored, never executed): OSC 52 clipboard access, hyperlinks, sixel, any request that would leave the terminal.
 * All methods are synchronized; a renderer that reads several cells should hold {@code synchronized (emulator)}.
 */
final class TerminalEmulator {
	static final int BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8, BLINK = 16, INVERSE = 32, HIDDEN = 64, STRIKE = 128;
	/** Colour encoding: 0 = default, {@code INDEXED | n} = palette entry n, {@code RGB | 0xRRGGBB} = true colour. */
	static final int INDEXED = 0x02000000, RGB = 0x01000000;
	static final int CONTINUATION = -1;
	private static final int MAX_PARAMS = 32, MAX_OSC = 4096, MAX_SCROLLBACK = 20000;

	/** One row of cells. */
	static final class Line {
		int[] ch, fg, bg; byte[] attr; boolean wrapped;
		Line(int cols) { ch = new int[cols]; fg = new int[cols]; bg = new int[cols]; attr = new byte[cols]; java.util.Arrays.fill(ch, ' '); }
		Line copy(int cols) {
			Line line = new Line(cols); int n = Math.min(cols, ch.length);
			System.arraycopy(ch, 0, line.ch, 0, n); System.arraycopy(fg, 0, line.fg, 0, n);
			System.arraycopy(bg, 0, line.bg, 0, n); System.arraycopy(attr, 0, line.attr, 0, n); line.wrapped = wrapped; return line;
		}
		void clear(int from, int to, int background) {
			for (int i = Math.max(0, from); i < Math.min(ch.length, to); i++) { ch[i] = ' '; fg[i] = 0; bg[i] = background; attr[i] = 0; }
			if (to >= ch.length) wrapped = false;
		}
	}

	private int cols, rows;
	private Line[] screen, mainScreen, altScreen;
	private final ArrayDeque<Line> scrollback = new ArrayDeque<>();
	private int scrollbackLimit = 5000;
	private boolean alternate;
	private int x, y, top, bottom;
	private boolean pendingWrap;
	private int penFg, penBg, penAttr;
	private int savedX, savedY, savedFg, savedBg, savedAttr, savedCharset;
	private boolean savedWrap;
	private boolean[] tabs;
	private int[] charsets = {0, 0};
	private int activeCharset;
	// modes
	private boolean autoWrap = true, cursorVisible = true, applicationCursor, bracketedPaste, insertMode, newlineMode, focusReporting, reverseVideo;
	private int mouseMode; // 0 off, 1000 press/release, 1002 drag, 1003 any motion
	private boolean mouseSgr;
	private String title = "";
	private long revision;
	private Consumer<String> reply = text -> { };
	private Consumer<String> titleListener = text -> { };
	private Runnable bell = () -> { };

	// parser
	private enum State { GROUND, ESCAPE, ESCAPE_INTERMEDIATE, CSI, OSC, STRING, STRING_ESCAPE, OSC_ESCAPE }
	private State state = State.GROUND;
	private final StringBuilder sequence = new StringBuilder();
	private int intermediate;

	TerminalEmulator(int cols, int rows) {
		this.cols = Math.max(2, cols); this.rows = Math.max(1, rows);
		mainScreen = blankScreen(this.cols, this.rows); altScreen = blankScreen(this.cols, this.rows); screen = mainScreen;
		bottom = this.rows - 1; resetTabs();
	}

	private static Line[] blankScreen(int cols, int rows) {
		Line[] result = new Line[rows]; for (int i = 0; i < rows; i++) result[i] = new Line(cols); return result;
	}
	private void resetTabs() { tabs = new boolean[cols]; for (int i = 8; i < cols; i += 8) tabs[i] = true; }

	synchronized void onReply(Consumer<String> listener) { reply = listener; }
	synchronized void onTitle(Consumer<String> listener) { titleListener = listener; }
	synchronized void onBell(Runnable listener) { bell = listener; }
	synchronized void setScrollbackLimit(int lines) { scrollbackLimit = Math.clamp(lines, 0, MAX_SCROLLBACK); while (scrollback.size() > scrollbackLimit) scrollback.pollFirst(); }

	// ---- state for the renderer and the input encoder ---------------------------------------------------------------------
	synchronized int cols() { return cols; }
	synchronized int rows() { return rows; }
	synchronized int cursorX() { return x; }
	synchronized int cursorY() { return y; }
	synchronized boolean cursorVisible() { return cursorVisible; }
	synchronized boolean applicationCursor() { return applicationCursor; }
	synchronized boolean bracketedPaste() { return bracketedPaste; }
	synchronized boolean focusReporting() { return focusReporting; }
	synchronized boolean alternateScreen() { return alternate; }
	synchronized boolean reverseVideo() { return reverseVideo; }
	synchronized int mouseMode() { return mouseMode; }
	synchronized boolean mouseSgr() { return mouseSgr; }
	synchronized String title() { return title; }
	/** Increases whenever anything visible changes; renderers can skip work when it is unchanged. */
	synchronized long revision() { return revision; }
	synchronized int scrollbackSize() { return scrollback.size(); }
	/** Lines before {@code scrollbackSize()} are history; the rest is the screen. */
	synchronized int totalLines() { return scrollback.size() + rows; }

	private List<Line> historyCache; private int historyCacheSize = -1; private long historyCacheRevision = -1;
	synchronized Line lineAt(int index) {
		int history = scrollback.size();
		if (index >= history) return screen[Math.clamp(index - history, 0, rows - 1)];
		if (historyCache == null || historyCacheSize != history || historyCacheRevision != scrollbackGeneration) {
			historyCache = new ArrayList<>(scrollback); historyCacheSize = history; historyCacheRevision = scrollbackGeneration;
		}
		return historyCache.get(Math.max(0, index));
	}
	private long scrollbackGeneration;

	/** Visible text between two cells (inclusive), rows separated by newlines unless the row wrapped; trailing blanks trimmed. */
	synchronized String text(int fromLine, int fromCol, int toLine, int toCol) {
		if (fromLine > toLine || fromLine == toLine && fromCol > toCol) { int l = fromLine, c = fromCol; fromLine = toLine; fromCol = toCol; toLine = l; toCol = c; }
		StringBuilder out = new StringBuilder();
		fromLine = Math.max(0, fromLine); toLine = Math.min(totalLines() - 1, toLine);
		for (int line = fromLine; line <= toLine; line++) {
			Line row = lineAt(line);
			int start = line == fromLine ? Math.max(0, fromCol) : 0, end = line == toLine ? Math.min(cols - 1, toCol) : cols - 1;
			end = Math.min(end, row.ch.length - 1);
			if (line < toLine || end >= row.ch.length - 1) while (end >= start && row.ch[end] == ' ') end--;
			for (int c = start; c <= end; c++) if (row.ch[c] != CONTINUATION) out.appendCodePoint(row.ch[c]);
			if (line < toLine && !row.wrapped) out.append('\n');
		}
		return out.toString();
	}

	// ---- input ------------------------------------------------------------------------------------------------------------
	synchronized void feed(String text) {
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i); i += Character.charCount(cp);
			accept(cp);
		}
		revision++;
	}

	private void accept(int c) {
		switch (state) {
			case GROUND -> ground(c);
			case ESCAPE -> escape(c);
			case ESCAPE_INTERMEDIATE -> { if (c >= 0x30 && c <= 0x7e) { designate(intermediate, c); state = State.GROUND; } else if (c < 0x20) control(c); else intermediate = c; }
			case CSI -> csi(c);
			case OSC -> {
				if (c == 7) { endOsc(); state = State.GROUND; }
				else if (c == 0x1b) state = State.OSC_ESCAPE;
				else if (c == 0x18 || c == 0x1a) { state = State.GROUND; }
				else if (sequence.length() < MAX_OSC) sequence.appendCodePoint(c);
			}
			case OSC_ESCAPE -> { if (c == '\\') { endOsc(); state = State.GROUND; } else { state = State.GROUND; escape(c); } }
			case STRING -> { if (c == 0x1b) state = State.STRING_ESCAPE; else if (c == 7 || c == 0x18 || c == 0x1a) state = State.GROUND; }
			case STRING_ESCAPE -> { state = c == '\\' ? State.GROUND : State.STRING; }
		}
	}

	private void ground(int c) {
		if (c < 0x20) { control(c); return; }
		if (c == 0x7f) return;
		if (c == 0x9b) { beginCsi(); return; }
		put(mapCharset(c));
	}

	private void control(int c) {
		switch (c) {
			case 0x07 -> bell.run();
			case 0x08 -> { if (x > 0) x--; pendingWrap = false; }
			case 0x09 -> { x = nextTab(x); pendingWrap = false; }
			case 0x0a, 0x0b, 0x0c -> { lineFeed(); if (newlineMode) x = 0; }
			case 0x0d -> { x = 0; pendingWrap = false; }
			case 0x0e -> activeCharset = 1;
			case 0x0f -> activeCharset = 0;
			case 0x1b -> { state = State.ESCAPE; }
			case 0x18, 0x1a -> state = State.GROUND;
			default -> { }
		}
	}

	private void escape(int c) {
		state = State.GROUND;
		switch (c) {
			case '[' -> beginCsi();
			case ']' -> { sequence.setLength(0); state = State.OSC; }
			case 'P', 'X', '^', '_' -> state = State.STRING;
			case '(', ')', '*', '+', '#', '%', ' ' -> { intermediate = c; state = State.ESCAPE_INTERMEDIATE; }
			case '7' -> saveCursor();
			case '8' -> restoreCursor();
			case 'D' -> lineFeed();
			case 'E' -> { x = 0; lineFeed(); }
			case 'M' -> reverseIndex();
			case 'H' -> { if (x < cols) tabs[x] = true; }
			case 'c' -> reset();
			case '=', '>', '\\' -> { }
			case 0x1b -> state = State.ESCAPE;
			default -> { if (c < 0x20) control(c); }
		}
	}

	private void designate(int slot, int set) {
		if (slot == '(') charsets[0] = set == '0' ? 1 : 0;
		else if (slot == ')') charsets[1] = set == '0' ? 1 : 0;
	}

	private static final String LINE_DRAWING = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·";
	private int mapCharset(int c) {
		if (charsets[activeCharset] == 1 && c >= 0x60 && c <= 0x7e) return LINE_DRAWING.charAt(c - 0x60);
		return c;
	}

	private void beginCsi() { sequence.setLength(0); state = State.CSI; }

	private void csi(int c) {
		if (c >= 0x40 && c <= 0x7e) { state = State.GROUND; dispatchCsi(c, sequence.toString()); return; }
		if (c == 0x1b) { state = State.ESCAPE; return; }
		if (c < 0x20) { control(c); return; }
		if (sequence.length() < 256) sequence.appendCodePoint(c);
	}

	private void endOsc() {
		String text = sequence.toString();
		int semicolon = text.indexOf(';');
		if (semicolon < 0) return;
		String command = text.substring(0, semicolon), argument = text.substring(semicolon + 1);
		if (command.equals("0") || command.equals("2")) {
			title = argument.length() > 200 ? argument.substring(0, 200) : argument;
			title = title.replaceAll("\\p{Cntrl}", "");
			titleListener.accept(title); revision++;
		}
	}

	// ---- CSI ----------------------------------------------------------------------------------------------------------------
	private void dispatchCsi(int command, String raw) {
		char prefix = 0; int start = 0;
		if (!raw.isEmpty() && "?<=>".indexOf(raw.charAt(0)) >= 0) { prefix = raw.charAt(0); start = 1; }
		int end = raw.length();
		while (end > start && raw.charAt(end - 1) >= 0x20 && raw.charAt(end - 1) <= 0x2f) end--;
		String inter = raw.substring(end);
		int[] params = new int[MAX_PARAMS]; int count = 0;
		java.util.Arrays.fill(params, -1);
		if (end > start) {
			int value = -1;
			for (int i = start; i < end && count < MAX_PARAMS; i++) {
				char ch = raw.charAt(i);
				if (ch >= '0' && ch <= '9') value = Math.min(99999, (value < 0 ? 0 : value) * 10 + (ch - '0'));
				else if (ch == ';' || ch == ':') { params[count++] = value; value = -1; }
			}
			if (count < MAX_PARAMS) params[count++] = value;
		}
		if (count == 0) count = 1;
		pendingWrapOnMove(command);
		int n1 = Math.max(1, params[0] < 0 ? 1 : params[0]);
		if (prefix == '?') { if (command == 'h' || command == 'l') privateModes(params, count, command == 'h'); return; }
		if (prefix == '>') { if (command == 'c') reply.accept("\u001b[>0;0;0c"); return; }
		if (prefix != 0) return;
		if (!inter.isEmpty()) {
			if (inter.equals(" ") && command == 'q') return;           // cursor shape: left to the renderer's settings
			if (inter.equals("!") && command == 'p') { reset(); }
			return;
		}
		switch (command) {
			case '@' -> insertChars(n1);
			case 'A' -> { y = Math.max(y >= top ? top : 0, y - n1); }
			case 'B', 'e' -> { y = Math.min(y <= bottom ? bottom : rows - 1, y + n1); }
			case 'C', 'a' -> x = Math.min(cols - 1, x + n1);
			case 'D' -> x = Math.max(0, x - n1);
			case 'E' -> { y = Math.min(rows - 1, y + n1); x = 0; }
			case 'F' -> { y = Math.max(0, y - n1); x = 0; }
			case 'G', '`' -> x = Math.clamp(n1 - 1, 0, cols - 1);
			case 'H', 'f' -> { y = Math.clamp(n1 - 1, 0, rows - 1); x = Math.clamp(Math.max(1, params[1] < 0 ? 1 : params[1]) - 1, 0, cols - 1); }
			case 'I' -> { for (int i = 0; i < n1; i++) x = nextTab(x); }
			case 'J' -> eraseDisplay(params[0] < 0 ? 0 : params[0]);
			case 'K' -> eraseLine(params[0] < 0 ? 0 : params[0]);
			case 'L' -> { if (y >= top && y <= bottom) scrollRegion(y, bottom, -n1); }
			case 'M' -> { if (y >= top && y <= bottom) scrollRegion(y, bottom, n1); }
			case 'P' -> deleteChars(n1);
			case 'S' -> scrollRegion(top, bottom, n1);
			case 'T' -> scrollRegion(top, bottom, -n1);
			case 'X' -> screen[y].clear(x, x + n1, penBg);
			case 'Z' -> { for (int i = 0; i < n1; i++) x = previousTab(x); }
			case 'd' -> y = Math.clamp(n1 - 1, 0, rows - 1);
			case 'g' -> { int mode = params[0] < 0 ? 0 : params[0]; if (mode == 0 && x < cols) tabs[x] = false; else if (mode == 3) java.util.Arrays.fill(tabs, false); }
			case 'h', 'l' -> ansiModes(params, count, command == 'h');
			case 'm' -> sgr(params, count);
			case 'n' -> {
				int what = params[0];
				if (what == 5) reply.accept("\u001b[0n");
				else if (what == 6) reply.accept("\u001b[" + (y + 1) + ";" + (x + 1) + "R");
			}
			case 'c' -> { if (params[0] <= 0) reply.accept("\u001b[?62;c"); }
			case 'r' -> {
				int t = params[0] < 0 ? 1 : params[0], b = params[1] < 0 ? rows : params[1];
				if (t < b && b <= rows) { top = t - 1; bottom = b - 1; x = 0; y = 0; }
			}
			case 's' -> saveCursor();
			case 'u' -> restoreCursor();
			default -> { }
		}
	}

	private void pendingWrapOnMove(int command) { if (command != 'm') pendingWrap = false; }

	private void ansiModes(int[] params, int count, boolean on) {
		for (int i = 0; i < count; i++) {
			if (params[i] == 4) insertMode = on;
			else if (params[i] == 20) newlineMode = on;
		}
	}

	private void privateModes(int[] params, int count, boolean on) {
		for (int i = 0; i < count; i++) {
			switch (params[i]) {
				case 1 -> applicationCursor = on;
				case 5 -> reverseVideo = on;
				case 7 -> autoWrap = on;
				case 25 -> cursorVisible = on;
				case 47, 1047 -> switchScreen(on, false);
				case 1048 -> { if (on) saveCursor(); else restoreCursor(); }
				case 1049 -> switchScreen(on, true);
				case 1000, 1002, 1003 -> { if (on) mouseMode = params[i]; else if (mouseMode == params[i]) mouseMode = 0; }
				case 1006 -> mouseSgr = on;
				case 1004 -> focusReporting = on;
				case 2004 -> bracketedPaste = on;
				default -> { }
			}
		}
	}

	private void switchScreen(boolean toAlternate, boolean saveRestore) {
		if (toAlternate == alternate) return;
		if (toAlternate) {
			if (saveRestore) saveCursor();
			alternate = true; screen = altScreen;
			for (Line line : screen) line.clear(0, cols, 0);
			top = 0; bottom = rows - 1;
		} else {
			alternate = false; screen = mainScreen; top = 0; bottom = rows - 1;
			if (saveRestore) restoreCursor();
		}
		pendingWrap = false;
	}

	private void sgr(int[] params, int count) {
		for (int i = 0; i < count; i++) {
			int p = params[i] < 0 ? 0 : params[i];
			switch (p) {
				case 0 -> { penFg = 0; penBg = 0; penAttr = 0; }
				case 1 -> penAttr |= BOLD; case 2 -> penAttr |= DIM; case 3 -> penAttr |= ITALIC; case 4 -> penAttr |= UNDERLINE;
				case 5, 6 -> penAttr |= BLINK; case 7 -> penAttr |= INVERSE; case 8 -> penAttr |= HIDDEN; case 9 -> penAttr |= STRIKE;
				case 21 -> penAttr |= UNDERLINE;
				case 22 -> penAttr &= ~(BOLD | DIM); case 23 -> penAttr &= ~ITALIC; case 24 -> penAttr &= ~UNDERLINE;
				case 25 -> penAttr &= ~BLINK; case 27 -> penAttr &= ~INVERSE; case 28 -> penAttr &= ~HIDDEN; case 29 -> penAttr &= ~STRIKE;
				case 39 -> penFg = 0; case 49 -> penBg = 0;
				case 38, 48 -> {
					int kind = i + 1 < count ? params[i + 1] : -1, color = -1;
					if (kind == 5 && i + 2 < count) { color = INDEXED | Math.clamp(params[i + 2], 0, 255); i += 2; }
					else if (kind == 2 && i + 4 < count) {
						color = RGB | (Math.clamp(params[i + 2], 0, 255) << 16) | (Math.clamp(params[i + 3], 0, 255) << 8) | Math.clamp(params[i + 4], 0, 255); i += 4;
					} else i = count;
					if (color >= 0) { if (p == 38) penFg = color; else penBg = color; }
				}
				default -> {
					if (p >= 30 && p <= 37) penFg = INDEXED | (p - 30);
					else if (p >= 40 && p <= 47) penBg = INDEXED | (p - 40);
					else if (p >= 90 && p <= 97) penFg = INDEXED | (p - 90 + 8);
					else if (p >= 100 && p <= 107) penBg = INDEXED | (p - 100 + 8);
				}
			}
		}
	}

	// ---- screen operations -------------------------------------------------------------------------------------------------
	private static int width(int cp) {
		if (cp < 0x300) return 1;
		if (cp >= 0x300 && cp <= 0x36f || cp == 0x200b || cp == 0x200d || cp >= 0xfe00 && cp <= 0xfe0f) return 0;
		if (cp >= 0x1100 && cp <= 0x115f || cp >= 0x2e80 && cp <= 0xa4cf || cp >= 0xac00 && cp <= 0xd7a3 || cp >= 0xf900 && cp <= 0xfaff
				|| cp >= 0xfe30 && cp <= 0xfe6f || cp >= 0xff00 && cp <= 0xff60 || cp >= 0xffe0 && cp <= 0xffe6
				|| cp >= 0x1f300 && cp <= 0x1f64f || cp >= 0x1f900 && cp <= 0x1f9ff || cp >= 0x20000 && cp <= 0x3fffd) return 2;
		return 1;
	}

	static int cellWidth(int cp) { return width(cp); }

	private void put(int cp) {
		int w = width(cp);
		if (w == 0) return;
		if (pendingWrap && autoWrap) { screen[y].wrapped = true; x = 0; lineFeed(); }
		pendingWrap = false;
		if (x + w > cols) {
			if (autoWrap) { screen[y].clear(x, cols, penBg); screen[y].wrapped = true; x = 0; lineFeed(); }
			else x = cols - w;
		}
		Line line = screen[y];
		if (insertMode) { for (int i = cols - 1; i >= x + w; i--) copyCell(line, i - w, i); }
		setCell(line, x, cp);
		if (w == 2) setCell(line, x + 1, CONTINUATION);
		x += w;
		if (x >= cols) { x = cols - 1; pendingWrap = true; }
	}

	private void setCell(Line line, int column, int cp) { line.ch[column] = cp; line.fg[column] = penFg; line.bg[column] = penBg; line.attr[column] = (byte) penAttr; }
	private static void copyCell(Line line, int from, int to) { line.ch[to] = line.ch[from]; line.fg[to] = line.fg[from]; line.bg[to] = line.bg[from]; line.attr[to] = line.attr[from]; }

	private void lineFeed() {
		if (y == bottom) scrollRegion(top, bottom, 1);
		else if (y < rows - 1) y++;
	}

	private void reverseIndex() {
		if (y == top) scrollRegion(top, bottom, -1);
		else if (y > 0) y--;
	}

	/** Positive amounts scroll the region up (new blank lines at the bottom), negative amounts scroll it down. */
	private void scrollRegion(int from, int to, int amount) {
		int size = to - from + 1, n = Math.min(Math.abs(amount), size);
		if (n <= 0) return;
		if (amount > 0) {
			for (int i = 0; i < n; i++) {
				Line gone = screen[from + i];
				if (!alternate && from == 0 && scrollbackLimit > 0) {
					scrollback.addLast(gone); scrollbackGeneration++;
					if (scrollback.size() > scrollbackLimit) scrollback.pollFirst();
				}
			}
			System.arraycopy(screen, from + n, screen, from, size - n);
			for (int i = to - n + 1; i <= to; i++) { screen[i] = new Line(cols); screen[i].clear(0, cols, penBg); }
		} else {
			System.arraycopy(screen, from, screen, from + n, size - n);
			for (int i = from; i < from + n; i++) { screen[i] = new Line(cols); screen[i].clear(0, cols, penBg); }
		}
	}

	private void eraseDisplay(int mode) {
		switch (mode) {
			case 0 -> { screen[y].clear(x, cols, penBg); for (int i = y + 1; i < rows; i++) screen[i].clear(0, cols, penBg); }
			case 1 -> { for (int i = 0; i < y; i++) screen[i].clear(0, cols, penBg); screen[y].clear(0, x + 1, penBg); }
			case 2 -> { for (int i = 0; i < rows; i++) screen[i].clear(0, cols, penBg); }
			case 3 -> { scrollback.clear(); scrollbackGeneration++; }
			default -> { }
		}
	}

	private void eraseLine(int mode) {
		switch (mode) {
			case 0 -> screen[y].clear(x, cols, penBg);
			case 1 -> screen[y].clear(0, x + 1, penBg);
			case 2 -> screen[y].clear(0, cols, penBg);
			default -> { }
		}
	}

	private void insertChars(int n) {
		Line line = screen[y]; n = Math.min(n, cols - x);
		for (int i = cols - 1; i >= x + n; i--) copyCell(line, i - n, i);
		line.clear(x, x + n, penBg);
	}

	private void deleteChars(int n) {
		Line line = screen[y]; n = Math.min(n, cols - x);
		for (int i = x; i < cols - n; i++) copyCell(line, i + n, i);
		line.clear(cols - n, cols, penBg);
	}

	private int nextTab(int from) { int i = from + 1; while (i < cols - 1 && !tabs[i]) i++; return Math.min(i, cols - 1); }
	private int previousTab(int from) { int i = from - 1; while (i > 0 && !tabs[i]) i--; return Math.max(i, 0); }

	private void saveCursor() { savedX = x; savedY = y; savedFg = penFg; savedBg = penBg; savedAttr = penAttr; savedCharset = activeCharset; savedWrap = pendingWrap; }
	private void restoreCursor() { x = Math.min(savedX, cols - 1); y = Math.min(savedY, rows - 1); penFg = savedFg; penBg = savedBg; penAttr = savedAttr; activeCharset = savedCharset; pendingWrap = savedWrap; }

	private void reset() {
		for (Line line : mainScreen) line.clear(0, cols, 0);
		for (Line line : altScreen) line.clear(0, cols, 0);
		screen = mainScreen; alternate = false; x = y = 0; top = 0; bottom = rows - 1; pendingWrap = false;
		penFg = penBg = penAttr = 0; charsets = new int[]{0, 0}; activeCharset = 0;
		autoWrap = true; cursorVisible = true; applicationCursor = false; bracketedPaste = false; insertMode = false; newlineMode = false;
		mouseMode = 0; mouseSgr = false; focusReporting = false; reverseVideo = false; resetTabs();
	}

	/** Changes the grid size. Lines that no longer fit move into the scrollback; growing pulls them back. */
	synchronized void resize(int newCols, int newRows) {
		newCols = Math.max(2, newCols); newRows = Math.max(1, newRows);
		if (newCols == cols && newRows == rows) return;
		Line[] resizedMain = resizeScreen(mainScreen, newCols, newRows, !alternate);
		Line[] resizedAlt = new Line[newRows];
		for (int i = 0; i < newRows; i++) resizedAlt[i] = i < altScreen.length ? altScreen[i].copy(newCols) : new Line(newCols);
		mainScreen = resizedMain; altScreen = resizedAlt;
		screen = alternate ? altScreen : mainScreen;
		for (Line line : scrollback) { /* history keeps its own width; the renderer clips */ }
		cols = newCols; rows = newRows; top = 0; bottom = rows - 1;
		x = Math.min(x, cols - 1); y = Math.min(y, rows - 1); pendingWrap = false; resetTabs(); scrollbackGeneration++; revision++;
	}

	private Line[] resizeScreen(Line[] old, int newCols, int newRows, boolean useHistory) {
		List<Line> lines = new ArrayList<>(java.util.Arrays.asList(old));
		if (newRows < old.length) {
			int drop = old.length - newRows;
			int fromTop = useHistory ? Math.max(0, Math.min(drop, y + 1 - newRows)) : 0;
			// Remove empty lines from the bottom first (keeps the cursor line visible), then push the rest of the overflow into history.
			int removed = 0;
			while (removed < drop - fromTop && lines.size() > y + 1 && isBlank(lines.get(lines.size() - 1))) { lines.remove(lines.size() - 1); removed++; }
			int remaining = drop - removed;
			for (int i = 0; i < remaining; i++) {
				Line gone = lines.remove(0);
				if (useHistory && scrollbackLimit > 0) { scrollback.addLast(gone); if (scrollback.size() > scrollbackLimit) scrollback.pollFirst(); }
				if (y > 0) y--;
			}
		} else if (newRows > old.length) {
			int add = newRows - old.length;
			while (add > 0 && useHistory && !scrollback.isEmpty()) { lines.add(0, scrollback.pollLast()); y++; add--; }
			while (add-- > 0) lines.add(new Line(newCols));
		}
		Line[] result = new Line[newRows];
		for (int i = 0; i < newRows; i++) result[i] = lines.get(i).copy(newCols);
		return result;
	}

	private static boolean isBlank(Line line) {
		for (int i = 0; i < line.ch.length; i++) if (line.ch[i] != ' ' || line.bg[i] != 0) return false;
		return true;
	}
}
