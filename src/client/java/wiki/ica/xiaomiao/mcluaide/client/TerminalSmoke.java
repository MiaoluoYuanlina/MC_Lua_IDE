package wiki.ica.xiaomiao.mcluaide.client;

import java.util.ArrayList;
import java.util.List;

/** Pure-logic checks for the terminal emulator and the key encoder. */
public final class TerminalSmoke {
	private static int checks;

	private TerminalSmoke() { }

	public static void main(String[] args) {
		text(); cursor(); erase(); colours(); scrolling(); alternateScreen(); replies(); modes(); resize(); input(); robustness();
		System.out.println("Terminal smoke passed: " + checks + " checks; text, cursor, erase, colours, scroll regions, alt screen, replies, modes, resize, key encoding, hostile input");
	}

	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

	private static String row(TerminalEmulator t, int line) {
		var l = t.lineAt(t.scrollbackSize() + line); StringBuilder out = new StringBuilder();
		for (int c = 0; c < t.cols(); c++) if (l.ch[c] != TerminalEmulator.CONTINUATION) out.appendCodePoint(l.ch[c]);
		return out.toString().stripTrailing();
	}

	private static void text() {
		var t = new TerminalEmulator(10, 4);
		t.feed("hello\r\nworld");
		require(row(t, 0).equals("hello") && row(t, 1).equals("world"), "plain text and CRLF");
		require(t.cursorX() == 5 && t.cursorY() == 1, "cursor after text");
		t.feed("\r\nabcdefghijkl");
		require(row(t, 2).equals("abcdefghij") && row(t, 3).equals("kl"), "autowrap at the right edge");
		require(t.lineAt(t.scrollbackSize() + 2).wrapped, "wrapped rows are flagged");
		require(t.text(0, 0, 3, 9).equals("hello\nworld\nabcdefghijkl"), "copying joins wrapped rows and trims blanks, got " + t.text(0, 0, 3, 9).replace("\n", "|"));
		var wide = new TerminalEmulator(6, 2);
		wide.feed("a中b");
		require(wide.lineAt(wide.scrollbackSize()).ch[1] == 0x4e2d && wide.lineAt(wide.scrollbackSize()).ch[2] == TerminalEmulator.CONTINUATION, "wide characters take two cells");
		require(wide.cursorX() == 4, "cursor after a wide character");
		var wrapWide = new TerminalEmulator(3, 2);
		wrapWide.feed("ab中");
		require(row(wrapWide, 0).equals("ab") && row(wrapWide, 1).equals("中"), "a wide character that does not fit wraps whole");
		var tab = new TerminalEmulator(20, 2);
		tab.feed("a\tb");
		require(tab.lineAt(tab.scrollbackSize()).ch[8] == 'b', "tab stops every 8 columns");
		var drawing = new TerminalEmulator(5, 1);
		drawing.feed("\u001b(0lqk\u001b(B");
		require(row(drawing, 0).equals("┌─┐"), "DEC line drawing");
		var combining = new TerminalEmulator(5, 1);
		combining.feed("éx");
		require(row(combining, 0).equals("ex"), "zero width marks do not take a cell");
	}

	private static void cursor() {
		var t = new TerminalEmulator(10, 5);
		t.feed("\u001b[3;4H*");
		require(t.lineAt(t.scrollbackSize() + 2).ch[3] == '*', "CUP");
		t.feed("\u001b[2A\u001b[2D#");
		require(t.lineAt(t.scrollbackSize()).ch[2] == '#', "CUU and CUB");
		t.feed("\u001b[99;99H");
		require(t.cursorX() == 9 && t.cursorY() == 4, "positions are clamped");
		t.feed("\u001b[1;1H\u001b7\u001b[5;5H\u001b8@");
		require(t.lineAt(t.scrollbackSize()).ch[0] == '@', "save and restore cursor");
		t.feed("\u001b[1G\u001b[3d!");
		require(t.lineAt(t.scrollbackSize() + 2).ch[0] == '!', "CHA and VPA");
	}

	private static void erase() {
		var t = new TerminalEmulator(6, 3);
		t.feed("aaaaaa\r\nbbbbbb\r\ncccccc\u001b[2;3H\u001b[K");
		require(row(t, 1).equals("bb"), "EL 0");
		t.feed("\u001b[1;3H\u001b[1K");
		require(row(t, 0).equals("   aaa"), "EL 1");
		t.feed("\u001b[2;1H\u001b[J");
		require(row(t, 1).isEmpty() && row(t, 2).isEmpty() && !row(t, 0).isEmpty(), "ED 0");
		t.feed("\u001b[2J");
		require(row(t, 0).isEmpty(), "ED 2");
		t.feed("\u001b[1;1Habcdef\u001b[1;3H\u001b[2P");
		require(row(t, 0).equals("abef"), "DCH");
		t.feed("\u001b[1;2H\u001b[2@");
		require(row(t, 0).equals("a  bef"), "ICH");
		t.feed("\u001b[1;2H\u001b[3X");
		require(row(t, 0).equals("a   ef"), "ECH");
	}

	private static void colours() {
		var t = new TerminalEmulator(8, 1);
		t.feed("\u001b[1;31mA\u001b[0mB\u001b[38;5;200mC\u001b[38;2;1;2;3;48;5;4mD\u001b[7mE\u001b[0m\u001b[92mF\u001b[102mG");
		var l = t.lineAt(t.scrollbackSize());
		require(l.fg[0] == (TerminalEmulator.INDEXED | 1) && (l.attr[0] & TerminalEmulator.BOLD) != 0, "bold red");
		require(l.fg[1] == 0 && l.attr[1] == 0, "reset");
		require(l.fg[2] == (TerminalEmulator.INDEXED | 200), "256 colours");
		require(l.fg[3] == (TerminalEmulator.RGB | 0x010203) && l.bg[3] == (TerminalEmulator.INDEXED | 4), "true colour foreground with indexed background");
		require((l.attr[4] & TerminalEmulator.INVERSE) != 0, "inverse");
		require(l.fg[5] == (TerminalEmulator.INDEXED | 10) && l.bg[6] == (TerminalEmulator.INDEXED | 10), "bright colours");
		var erased = new TerminalEmulator(4, 1);
		erased.feed("\u001b[44m\u001b[2K");
		require(erased.lineAt(erased.scrollbackSize()).bg[2] == (TerminalEmulator.INDEXED | 4), "erase uses the current background");
	}

	private static void scrolling() {
		var t = new TerminalEmulator(4, 3);
		t.feed("1\r\n2\r\n3\r\n4");
		require(row(t, 0).equals("2") && row(t, 2).equals("4") && t.scrollbackSize() == 1, "scroll pushes the top line to history");
		require(t.lineAt(0).ch[0] == '1', "history is addressable");
		var region = new TerminalEmulator(4, 5);
		region.feed("a\r\nb\r\nc\r\nd\r\ne\u001b[2;4r\u001b[4;1H\n");
		require(row(region, 0).equals("a") && row(region, 1).equals("c") && row(region, 2).equals("d") && row(region, 3).isEmpty() && row(region, 4).equals("e"), "scroll region keeps the lines outside");
		require(region.scrollbackSize() == 0, "a partial region never feeds the history");
		region.feed("\u001b[2;1H\u001bM");
		require(row(region, 1).isEmpty() && row(region, 2).equals("c"), "reverse index scrolls down at the region top");
		var lines = new TerminalEmulator(4, 4);
		lines.feed("a\r\nb\r\nc\r\nd\u001b[2;1H\u001b[L");
		require(row(lines, 1).isEmpty() && row(lines, 2).equals("b"), "IL");
		lines.feed("\u001b[M");
		require(row(lines, 1).equals("b"), "DL");
		var limit = new TerminalEmulator(4, 2);
		limit.setScrollbackLimit(3);
		for (int i = 0; i < 20; i++) limit.feed("x\r\n");
		require(limit.scrollbackSize() == 3, "history is bounded");
	}

	private static void alternateScreen() {
		var t = new TerminalEmulator(6, 3);
		t.feed("main\u001b[?1049h");
		require(t.alternateScreen() && row(t, 0).isEmpty(), "alternate screen starts blank");
		t.feed("\u001b[Halt\r\n\r\n\r\n\r\nmore");
		require(t.scrollbackSize() == 0, "the alternate screen has no history");
		t.feed("\u001b[?1049l");
		require(!t.alternateScreen() && row(t, 0).equals("main"), "the main screen is restored");
	}

	private static void replies() {
		var t = new TerminalEmulator(10, 5);
		List<String> replies = new ArrayList<>();
		t.onReply(replies::add);
		t.feed("\u001b[3;4H\u001b[6n\u001b[5n\u001b[c");
		require(replies.equals(List.of("\u001b[3;4R", "\u001b[0n", "\u001b[?62;c")), "DSR and DA replies, got " + replies);
		var titles = new ArrayList<String>();
		t.onTitle(titles::add);
		t.feed("\u001b]0;my title\u0007\u001b]2;second\u001b\\");
		require(titles.equals(List.of("my title", "second")) && t.title().equals("second"), "window titles from BEL and ST terminated OSC");
		t.feed("\u001b]52;c;aGVsbG8=\u0007");
		require(replies.size() == 3, "OSC 52 clipboard requests are ignored");
	}

	private static void modes() {
		var t = new TerminalEmulator(10, 3);
		t.feed("\u001b[?1h\u001b[?2004h\u001b[?25l\u001b[?1000h\u001b[?1006h");
		require(t.applicationCursor() && t.bracketedPaste() && !t.cursorVisible() && t.mouseMode() == 1000 && t.mouseSgr(), "private modes on");
		t.feed("\u001b[?1l\u001b[?2004l\u001b[?25h\u001b[?1000l\u001b[?1006l");
		require(!t.applicationCursor() && !t.bracketedPaste() && t.cursorVisible() && t.mouseMode() == 0 && !t.mouseSgr(), "private modes off");
		var insert = new TerminalEmulator(6, 1);
		insert.feed("abc\u001b[4h\u001b[1GX");
		require(row(insert, 0).equals("Xabc"), "insert mode");
		var nowrap = new TerminalEmulator(3, 2);
		nowrap.feed("\u001b[?7labcde");
		require(row(nowrap, 0).equals("abe") && row(nowrap, 1).isEmpty(), "autowrap off overwrites the last column");
		var reset = new TerminalEmulator(5, 2);
		reset.feed("abc\u001bc");
		require(row(reset, 0).isEmpty() && reset.cursorX() == 0, "RIS");
	}

	private static void resize() {
		var t = new TerminalEmulator(6, 4);
		t.feed("1\r\n2\r\n3\r\n4");
		t.resize(6, 2);
		require(t.rows() == 2 && row(t, 1).equals("4") && t.cursorY() == 1, "shrinking keeps the cursor line and moves the top to history");
		require(t.scrollbackSize() == 2, "overflow moved to history");
		t.resize(6, 4);
		require(t.rows() == 4 && row(t, 3).equals("4") && t.scrollbackSize() == 0, "growing pulls history back, got " + row(t, 3));
		t.resize(3, 4);
		require(t.cols() == 3 && t.cursorX() <= 2, "narrower grid clamps the cursor");
		t.feed("\u001b[?1049h");
		t.resize(8, 5);
		require(t.rows() == 5 && t.cols() == 8, "alternate screen resizes too");
	}

	private static void input() {
		require(TerminalInput.key("Up", false, false, false, false).equals("\u001b[A"), "normal cursor keys");
		require(TerminalInput.key("Up", false, false, false, true).equals("\u001bOA"), "application cursor keys");
		require(TerminalInput.key("Left", true, false, false, false).equals("\u001b[1;5D"), "Ctrl+Left");
		require(TerminalInput.key("Delete", false, false, false, false).equals("\u001b[3~"), "Delete");
		require(TerminalInput.key("F5", false, false, true, false).equals("\u001b[15;2~"), "Shift+F5");
		require(TerminalInput.key("F1", false, false, false, false).equals("\u001bOP"), "F1");
		require(TerminalInput.key("Tab", false, false, true, false).equals("\u001b[Z"), "Shift+Tab");
		require(TerminalInput.key("Backspace", false, false, false, false).equals("\u007f"), "Backspace");
		require(TerminalInput.key("Nothing", false, false, false, false) == null, "unknown key");
		require(TerminalInput.control('c').equals("\u0003") && TerminalInput.control('C').equals("\u0003") && TerminalInput.control('[').equals("\u001b"), "control characters");
		require(TerminalInput.control('!') == null, "no control code");
		require(TerminalInput.paste("a\nb", false).equals("a\rb"), "paste newlines become returns");
		require(TerminalInput.paste("a\u001b[201~b", true).equals("\u001b[200~a[201~b\u001b[201~"), "bracketed paste cannot be closed from inside the payload");
		require(TerminalInput.mouse(0, false, false, false, false, false, 4, 2, true).equals("\u001b[<0;5;3M"), "SGR mouse press");
		require(TerminalInput.mouse(0, true, false, false, false, false, 4, 2, true).equals("\u001b[<0;5;3m"), "SGR mouse release");
		require(TerminalInput.mouse(0, false, false, false, false, false, 300, 2, false) == null, "legacy mouse reports cannot encode large coordinates");
	}

	private static void robustness() {
		var t = new TerminalEmulator(20, 6);
		java.util.Random random = new java.util.Random(7);
		StringBuilder noise = new StringBuilder();
		String[] pieces = {"\u001b[", "\u001b]", "\u001bP", "\u001b", "\u0007", ";", "9999", "H", "J", "K", "m", "r", "L", "M", "@", "P", "X", "S", "T", "A", "B", "?1049h", "?1049l", "\r", "\n", "\t", "中", "x", "\u0000", "\u001b[1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16;17;18;19;20;21;22;23;24;25;26;27;28;29;30;31;32;33;34;35m"};
		for (int i = 0; i < 20000; i++) noise.append(pieces[random.nextInt(pieces.length)]);
		t.feed(noise.toString());
		require(t.cursorX() >= 0 && t.cursorX() < t.cols() && t.cursorY() >= 0 && t.cursorY() < t.rows(), "random escape noise keeps the cursor on screen");
		for (int i = 0; i < 50; i++) { t.resize(2 + random.nextInt(40), 1 + random.nextInt(20)); t.feed(noise.substring(i * 20, i * 20 + 200)); }
		require(t.cursorX() < t.cols() && t.cursorY() < t.rows(), "resizing between noise keeps the cursor on screen");
		String huge = "\u001b]0;" + "t".repeat(100000) + "\u0007";
		t.feed(huge);
		require(t.title().length() <= 200, "titles are bounded");
		t.feed("\u001b[" + "9".repeat(5000) + "H");
		require(t.cursorX() < t.cols(), "overlong parameters are bounded");
	}
}
