package wiki.ica.xiaomiao.mcluaide.client;

/** Turns keys, pasted text and mouse events into the byte sequences a terminal application expects. */
final class TerminalInput {
	private TerminalInput() { }

	/** Named non-text keys. Returns null for keys that have no sequence (the caller then relies on typed characters). */
	static String key(String name, boolean ctrl, boolean alt, boolean shift, boolean applicationCursor) {
		int modifier = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
		String result = switch (name) {
			case "Enter" -> "\r";
			case "Backspace" -> ctrl ? "\u0008" : "\u007f";
			case "Tab" -> shift ? "\u001b[Z" : "\t";
			case "Escape" -> "\u001b";
			case "Up" -> cursor('A', modifier, applicationCursor);
			case "Down" -> cursor('B', modifier, applicationCursor);
			case "Right" -> cursor('C', modifier, applicationCursor);
			case "Left" -> cursor('D', modifier, applicationCursor);
			case "Home" -> cursor('H', modifier, applicationCursor);
			case "End" -> cursor('F', modifier, applicationCursor);
			case "Insert" -> tilde(2, modifier);
			case "Delete" -> tilde(3, modifier);
			case "PageUp" -> tilde(5, modifier);
			case "PageDown" -> tilde(6, modifier);
			case "F1" -> function('P', modifier); case "F2" -> function('Q', modifier);
			case "F3" -> function('R', modifier); case "F4" -> function('S', modifier);
			case "F5" -> tilde(15, modifier); case "F6" -> tilde(17, modifier); case "F7" -> tilde(18, modifier);
			case "F8" -> tilde(19, modifier); case "F9" -> tilde(20, modifier); case "F10" -> tilde(21, modifier);
			case "F11" -> tilde(23, modifier); case "F12" -> tilde(24, modifier);
			default -> null;
		};
		if (result != null && alt && modifier == 3 && result.length() == 1) result = "\u001b" + result; // Alt+Enter, Alt+Tab ...
		return result;
	}

	private static String cursor(char final_, int modifier, boolean application) {
		if (modifier > 1) return "\u001b[1;" + modifier + final_;
		return (application ? "\u001bO" : "\u001b[") + final_;
	}
	private static String function(char final_, int modifier) { return modifier > 1 ? "\u001b[1;" + modifier + final_ : "\u001bO" + final_; }
	private static String tilde(int number, int modifier) { return "\u001b[" + number + (modifier > 1 ? ";" + modifier : "") + "~"; }

	/** Ctrl+letter and the other control characters; null when the combination has no control code. */
	static String control(int character) {
		int c = Character.toLowerCase(character);
		if (c >= 'a' && c <= 'z') return String.valueOf((char) (c - 'a' + 1));
		return switch (c) {
			case ' ', '@', '2' -> "\u0000";
			case '[', '3' -> "\u001b";
			case '\\', '4' -> "\u001c";
			case ']', '5' -> "\u001d";
			case '^', '6' -> "\u001e";
			case '_', '7', '/' -> "\u001f";
			case '8', '?' -> "\u007f";
			default -> null;
		};
	}

	/** Text typed with Alt held is sent with an escape prefix. */
	static String alt(String text) { return "\u001b" + text; }

	/** Wraps pasted text in the bracketed-paste markers when the program asked for them, and strips the marker itself from the payload. */
	static String paste(String text, boolean bracketed) {
		String clean = text.replace("\r\n", "\r").replace('\n', '\r');
		if (!bracketed) return clean;
		return "\u001b[200~" + clean.replace("\u001b", "") + "\u001b[201~";
	}

	/** xterm mouse report. {@code button}: 0 left, 1 middle, 2 right, 3 release (legacy), 64/65 wheel. Coordinates are 0-based cells. */
	static String mouse(int button, boolean release, boolean motion, boolean shift, boolean alt, boolean ctrl, int column, int row, boolean sgr) {
		int code = button + (shift ? 4 : 0) + (alt ? 8 : 0) + (ctrl ? 16 : 0) + (motion ? 32 : 0);
		if (sgr) return "\u001b[<" + code + ";" + (column + 1) + ";" + (row + 1) + (release ? "m" : "M");
		if (column > 222 || row > 222) return null;
		int legacy = release ? 3 + (shift ? 4 : 0) + (alt ? 8 : 0) + (ctrl ? 16 : 0) : code;
		return "\u001b[M" + (char) (32 + legacy) + (char) (33 + column) + (char) (33 + row);
	}

	static String focus(boolean gained) { return gained ? "\u001b[I" : "\u001b[O"; }
}
