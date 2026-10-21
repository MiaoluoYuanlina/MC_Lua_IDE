package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTabItemFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import wiki.ica.xiaomiao.mcluaide.terminal.PtyProcess;
import wiki.ica.xiaomiao.mcluaide.terminal.TerminalShells;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The terminal pane: tabs of local (pseudo terminal on this machine) and server terminals, drawn cell by cell with the editor font.
 * Tabs live in static state, so closing the IDE window does not end running programs; they end with the game or on disconnect (server tabs).
 */
final class TerminalPanel {
	static final String ID = "NATIVE_TERMINAL";
	private static final int MAX_TABS = 8;
	private static final List<TerminalTab> TABS = new CopyOnWriteArrayList<>();
	private static int active;
	private static float fontSize = 16f;
	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> TABS.forEach(TerminalTab::close), "terminal-shutdown"));
	}

	private final Supplier<LuaPersonalizationSettings> settings;
	private boolean hovered, focused, autoOpened;
	private String notice = "";
	private long lastBlink;

	TerminalPanel(Supplier<LuaPersonalizationSettings> settings) { this.settings = settings; }

	// ---- tab management ----------------------------------------------------------------------------------------------------
	static int tabCount() { return TABS.size(); }
	static TerminalTab current() { return TABS.isEmpty() ? null : TABS.get(Math.clamp(active, 0, TABS.size() - 1)); }
	static List<TerminalTab> tabs() { return List.copyOf(TABS); }

	static List<String> localShellNames() {
		return TerminalShells.detect(directory()).stream().map(PtyProcess.Shell::name).toList();
	}
	private static Path directory() { return Minecraft.getInstance().gameDirectory.toPath(); }

	/** Opens a terminal on this machine. {@code shellName} may be blank for the default shell. */
	static TerminalTab openLocal(String shellName) {
		if (TABS.size() >= MAX_TABS) throw new IllegalStateException(LuaUiText.text("终端标签页已达上限", "Too many terminal tabs"));
		PtyProcess.Shell shell;
		try { shell = TerminalShells.find(shellName, directory()); }
		catch (RuntimeException error) { return failed(shellName, false, error.getMessage()); }
		TerminalTab tab = new TerminalTab(shell.name(), false, 100, 30);
		try {
			PtyProcess process = PtyProcess.start(shell, 100, 30, tab::receive, tab::markExited);
			tab.session = new TerminalTab.Session() {
				@Override public void write(byte[] data) { process.write(data); }
				@Override public void resize(int cols, int rows) { process.resize(cols, rows); }
				@Override public boolean alive() { return process.alive(); }
				@Override public int exitCode() { return process.exitCode(); }
				@Override public void close() { process.destroy(); }
			};
		} catch (Throwable error) {
			tab.exited = true;
			tab.receive(("\u001b[31m" + LuaUiText.text("无法启动终端：", "Cannot start the terminal: ") + error.getMessage() + "\u001b[0m\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		add(tab);
		return tab;
	}

	/** Opens a terminal that runs on the game server (needs the server to allow it, and operator level 4). */
	static TerminalTab openServer() {
		if (TABS.size() >= MAX_TABS) throw new IllegalStateException(LuaUiText.text("终端标签页已达上限", "Too many terminal tabs"));
		TerminalTab tab = new TerminalTab(LuaUiText.text("服务器", "Server"), true, 100, 30);
		tab.receive((LuaUiText.text("正在连接服务器终端…", "Connecting to the server terminal…") + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		RemoteTerminalSession.open(tab, 100, 30);
		add(tab);
		return tab;
	}

	private static TerminalTab failed(String name, boolean server, String message) {
		TerminalTab tab = new TerminalTab(name == null || name.isBlank() ? "shell" : name, server, 100, 30);
		tab.exited = true;
		tab.receive(("\u001b[31m" + message + "\u001b[0m\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		add(tab);
		return tab;
	}

	private static void add(TerminalTab tab) { TABS.add(tab); active = TABS.size() - 1; }

	static void closeTab(TerminalTab tab) {
		int index = TABS.indexOf(tab);
		if (index < 0) return;
		TABS.remove(tab);
		tab.close();
		if (active >= index) active = Math.max(0, active - 1);
	}

	/** Called when the game disconnects: server terminals die with the connection. */
	static void disconnected() {
		for (TerminalTab tab : TABS) if (tab.server) closeTab(tab);
	}

	// ---- input from the screen -----------------------------------------------------------------------------------------------
	boolean captures() { return focused && current() != null; }

	boolean character(CharacterEvent event) {
		TerminalTab tab = current();
		if (tab == null) return false;
		long window = Minecraft.getInstance().getWindow().handle();
		boolean alt = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS;
		boolean ctrl = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
		int codepoint = event.codepoint();
		if (codepoint < 32 || codepoint == 127 || alt && !ctrl) return true; // control codes and Alt+key come through key()
		tab.scrollOffset = 0; tab.clearSelection();
		tab.send(new String(Character.toChars(codepoint)));
		return true;
	}

	/** Must run inside the ImGui context (clipboard access). */
	boolean key(KeyEvent event) {
		TerminalTab tab = current();
		if (tab == null) return false;
		int key = event.key();
		boolean ctrl = event.hasControlDown(), alt = event.hasAltDown(), shift = event.hasShiftDown();
		if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL || key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
				|| key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT || key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER
				|| key == GLFW.GLFW_KEY_CAPS_LOCK || key == GLFW.GLFW_KEY_NUM_LOCK || key == GLFW.GLFW_KEY_SCROLL_LOCK) return true;
		if (ctrl && shift && key == GLFW.GLFW_KEY_C || ctrl && !shift && key == GLFW.GLFW_KEY_INSERT || ctrl && !shift && !alt && key == GLFW.GLFW_KEY_C && tab.hasSelection()) { copy(tab); return true; }
		if (ctrl && shift && key == GLFW.GLFW_KEY_V || shift && !ctrl && key == GLFW.GLFW_KEY_INSERT) { paste(tab); return true; }
		if (shift && !ctrl && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
			int page = Math.max(1, tab.emulator.rows() - 1);
			tab.scrollOffset = Math.clamp(tab.scrollOffset + (key == GLFW.GLFW_KEY_PAGE_UP ? page : -page), 0, tab.emulator.scrollbackSize());
			return true;
		}
		if (ctrl && shift && key == GLFW.GLFW_KEY_T) { try { openLocal(""); } catch (RuntimeException error) { notice = error.getMessage(); } return true; }
		if (ctrl && shift && key == GLFW.GLFW_KEY_W) { closeTab(tab); return true; }
		if (tab.exited && tab.session == null) return true;
		String name = keyName(key);
		String sequence = null;
		if (name != null) sequence = TerminalInput.key(name, ctrl, alt, shift, tab.emulator.applicationCursor());
		else if (ctrl) {
			int character = controlCharacter(key);
			if (character != 0) sequence = TerminalInput.control(character);
		} else if (alt) {
			String typed = GLFW.glfwGetKeyName(key, event.scancode());
			if (typed != null && !typed.isEmpty()) sequence = TerminalInput.alt(shift ? typed.toUpperCase(java.util.Locale.ROOT) : typed);
		}
		if (sequence != null && alt && ctrl && name == null) sequence = TerminalInput.alt(sequence);
		else if (sequence != null && alt && name == null && !sequence.startsWith("\u001b")) sequence = TerminalInput.alt(sequence);
		if (sequence != null) { tab.scrollOffset = 0; tab.clearSelection(); tab.send(sequence); }
		return true;
	}

	private static String keyName(int key) {
		return switch (key) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "Enter";
			case GLFW.GLFW_KEY_BACKSPACE -> "Backspace"; case GLFW.GLFW_KEY_TAB -> "Tab"; case GLFW.GLFW_KEY_ESCAPE -> "Escape";
			case GLFW.GLFW_KEY_UP -> "Up"; case GLFW.GLFW_KEY_DOWN -> "Down"; case GLFW.GLFW_KEY_LEFT -> "Left"; case GLFW.GLFW_KEY_RIGHT -> "Right";
			case GLFW.GLFW_KEY_HOME -> "Home"; case GLFW.GLFW_KEY_END -> "End"; case GLFW.GLFW_KEY_INSERT -> "Insert"; case GLFW.GLFW_KEY_DELETE -> "Delete";
			case GLFW.GLFW_KEY_PAGE_UP -> "PageUp"; case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
			default -> key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12 ? "F" + (key - GLFW.GLFW_KEY_F1 + 1) : null;
		};
	}

	private static int controlCharacter(int key) {
		if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return 'a' + (key - GLFW.GLFW_KEY_A);
		return switch (key) {
			case GLFW.GLFW_KEY_SPACE -> ' '; case GLFW.GLFW_KEY_LEFT_BRACKET -> '['; case GLFW.GLFW_KEY_BACKSLASH -> '\\';
			case GLFW.GLFW_KEY_RIGHT_BRACKET -> ']'; case GLFW.GLFW_KEY_SLASH -> '/'; case GLFW.GLFW_KEY_MINUS -> '_';
			case GLFW.GLFW_KEY_2 -> '@'; case GLFW.GLFW_KEY_6 -> '^'; case GLFW.GLFW_KEY_8 -> '?';
			default -> 0;
		};
	}

	private void copy(TerminalTab tab) {
		String text = tab.selectedText();
		if (!text.isEmpty()) ImGui.setClipboardText(text);
		tab.clearSelection();
	}

	private void paste(TerminalTab tab) {
		String text = ImGui.getClipboardText();
		if (text == null || text.isEmpty()) return;
		if (text.length() > 200_000) { notice = LuaUiText.text("粘贴内容过大", "Pasted text is too large"); return; }
		tab.scrollOffset = 0; tab.clearSelection();
		tab.send(TerminalInput.paste(text, tab.emulator.bracketedPaste()));
	}

	boolean scroll(double vertical) {
		TerminalTab tab = current();
		if (!hovered || tab == null) return false;
		if (ImGui.getIO().getKeyCtrl()) { fontSize = Math.clamp(fontSize + (float) vertical, 9, 40); return true; }
		int steps = (int) Math.signum(vertical) * Math.max(1, (int) Math.abs(vertical)) * 3;
		var emulator = tab.emulator;
		if (emulator.mouseMode() != 0 && !ImGui.getIO().getKeyShift()) {
			String report = TerminalInput.mouse(vertical > 0 ? 64 : 65, false, false, false, false, false, lastCellColumn, lastCellRow, emulator.mouseSgr());
			if (report != null) for (int i = 0; i < Math.abs(steps) / 3; i++) tab.send(report);
		} else if (emulator.alternateScreen()) {
			String arrow = TerminalInput.key(vertical > 0 ? "Up" : "Down", false, false, false, emulator.applicationCursor());
			for (int i = 0; i < Math.abs(steps); i++) tab.send(arrow);
		} else tab.scrollOffset = Math.clamp(tab.scrollOffset + steps, 0, emulator.scrollbackSize());
		return true;
	}

	// ---- drawing ------------------------------------------------------------------------------------------------------------
	private int lastCellColumn, lastCellRow, reportedColumn = -1, reportedRow = -1;
	private final String[] asciiText = new String[128];
	private final java.util.HashMap<Integer, String> glyphText = new java.util.HashMap<>();

	void draw() {
		if (!autoOpened) {
			autoOpened = true;
			if (TABS.isEmpty()) try { openLocal(""); } catch (RuntimeException error) { notice = error.getMessage(); }
		}
		focused = ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.ChildWindows);
		drawToolbar();
		if (!notice.isEmpty()) { ImGui.textColored(1f, .55f, .45f, 1f, notice); }
		TerminalTab tab = current();
		if (tab == null) {
			ImGui.textDisabled(LuaUiText.text("没有终端。点击 + 打开一个。", "No terminal open. Click + to start one."));
			hovered = false;
			return;
		}
		drawCanvas(tab);
	}

	private void drawToolbar() {
		if (ImGui.button("+##new_terminal")) ImGui.openPopup("new_terminal_menu");
		if (ImGui.isItemHovered()) ImGui.setTooltip(LuaUiText.text("新建终端（Ctrl+Shift+T）", "New terminal (Ctrl+Shift+T)"));
		if (ImGui.beginPopup("new_terminal_menu")) {
			ImGui.textDisabled(LuaUiText.text("本地终端", "Local terminal"));
			for (String shell : localShellNames()) {
				if (ImGui.menuItem(shell)) { try { openLocal(shell); notice = ""; } catch (RuntimeException error) { notice = error.getMessage(); } }
			}
			ImGui.separator();
			ImGui.textDisabled(LuaUiText.text("服务器终端（需要管理员，且服务器已开启）", "Server terminal (operator level 4, must be enabled on the server)"));
			if (ImGui.menuItem(LuaUiText.text("服务器终端", "Server terminal"), "", false, ScriptManager.isServerAvailable())) {
				try { openServer(); notice = ""; } catch (RuntimeException error) { notice = error.getMessage(); }
			}
			ImGui.endPopup();
		}
		ImGui.sameLine();
		if (ImGui.beginTabBar("terminal_tabs", ImGuiTabBarFlags.AutoSelectNewTabs | ImGuiTabBarFlags.FittingPolicyScroll)) {
			TerminalTab closing = null;
			for (int i = 0; i < TABS.size(); i++) {
				TerminalTab tab = TABS.get(i);
				ImBoolean open = new ImBoolean(true);
				if (ImGui.beginTabItem(tab.label() + "###terminal_tab_" + tab.id, open, 0)) {
					if (active != i) active = i;
					ImGui.endTabItem();
				}
				if (!open.get()) closing = tab;
			}
			ImGui.endTabBar();
			if (closing != null) closeTab(closing);
		}
	}

	private static int abgr(int rgb) { return 0xFF000000 | (rgb & 0xFF) << 16 | (rgb >> 8 & 0xFF) << 8 | (rgb >> 16 & 0xFF); }

	private static final int[] DARK = {0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
			0x666666, 0xF14C4C, 0x23D18B, 0xF5F43D, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF};
	private static final int[] LIGHT = {0x000000, 0xCD3131, 0x00A000, 0x949800, 0x0451A5, 0xBC05BC, 0x0598BC, 0x555555,
			0x666666, 0xCD3131, 0x14A814, 0xA0A000, 0x0451A5, 0xBC05BC, 0x0598BC, 0x888888};

	private static int paletteColor(int index, boolean light) {
		if (index < 16) return abgr((light ? LIGHT : DARK)[index]);
		if (index < 232) {
			int n = index - 16, r = n / 36, g = n / 6 % 6, b = n % 6;
			int[] levels = {0, 95, 135, 175, 215, 255};
			return abgr(levels[r] << 16 | levels[g] << 8 | levels[b]);
		}
		int gray = 8 + (index - 232) * 10;
		return abgr(gray << 16 | gray << 8 | gray);
	}

	private String glyph(int cp) {
		if (cp < 128) { String s = asciiText[cp]; if (s == null) asciiText[cp] = s = String.valueOf((char) cp); return s; }
		return glyphText.computeIfAbsent(cp, c -> new String(Character.toChars(c)));
	}

	private void drawCanvas(TerminalTab tab) {
		var emulator = tab.emulator;
		ImGuiIdeRuntime.pushEditorFont(fontSize);
		try {
			float cellWidth = Math.max(1f, ImGui.calcTextSize("M").x), lineHeight = Math.max(1f, ImGui.getTextLineHeight() + 1f);
			float width = Math.max(cellWidth * 4, ImGui.getContentRegionAvailX()), height = Math.max(lineHeight * 2, ImGui.getContentRegionAvailY());
			int columns = Math.max(2, (int) (width / cellWidth)), rows = Math.max(1, (int) (height / lineHeight));
			if (columns != emulator.cols() || rows != emulator.rows()) {
				emulator.resize(columns, rows);
				var session = tab.session;
				if (session != null) session.resize(columns, rows);
			}
			float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
			ImGui.invisibleButton("##terminal_canvas", width, height);
			hovered = ImGui.isItemHovered();
			if (focused) ImGui.getIO().setWantTextInput(true);
			var draw = ImGui.getWindowDrawList();
			var theme = settings.get();
			int background = theme == null ? 0xFF1E1E1E : NativeUi.rgba(theme.theme.background());
			boolean light = ((background & 0xFF) + (background >> 8 & 0xFF) + (background >> 16 & 0xFF)) / 3 > 140;
			int foreground = ImGui.getColorU32(ImGuiCol.Text);
			draw.addRectFilled(x0, y0, x0 + width, y0 + height, background);
			draw.pushClipRect(x0, y0, x0 + width, y0 + height, true);

			int mouseColumn = Math.clamp((int) ((ImGui.getMousePosX() - x0) / cellWidth), 0, columns - 1);
			int mouseRow = Math.clamp((int) ((ImGui.getMousePosY() - y0) / lineHeight), 0, rows - 1);
			lastCellColumn = mouseColumn; lastCellRow = mouseRow;

			synchronized (emulator) {
				int history = emulator.scrollbackSize();
				if (tab.scrollOffset > 0 && history > tab.lastScrollback) tab.scrollOffset += history - tab.lastScrollback;
				tab.lastScrollback = history;
				tab.scrollOffset = Math.clamp(tab.scrollOffset, 0, history);
				int total = emulator.totalLines(), first = Math.max(0, total - rows - tab.scrollOffset);
				boolean reverse = emulator.reverseVideo();
				long now = System.currentTimeMillis();
				boolean blinkOn = (now / 530) % 2 == 0;
				int selectionColor = (ImGui.getColorU32(ImGuiCol.TextSelectedBg) & 0x00FFFFFF) | 0x88000000;
				for (int r = 0; r < rows && first + r < total; r++) {
					var line = emulator.lineAt(first + r);
					float y = y0 + r * lineHeight;
					int count = Math.min(columns, line.ch.length);
					for (int c = 0; c < count; c++) {
						int cp = line.ch[c];
						int attr = line.attr[c] & 0xFF;
						int fg = line.fg[c], bg = line.bg[c];
						boolean inverse = (attr & TerminalEmulator.INVERSE) != 0 ^ reverse;
						int fgColor = resolve(fg, foreground, light, (attr & TerminalEmulator.BOLD) != 0), bgColor = resolve(bg, 0, light, false);
						if (inverse) { int swap = fg == 0 ? foreground : fgColor; fgColor = bg == 0 ? background : bgColor; bgColor = swap; }
						boolean paintBackground = bg != 0 || inverse;
						boolean wide = c + 1 < count && line.ch[c + 1] == TerminalEmulator.CONTINUATION;
						float x = x0 + c * cellWidth, w = cellWidth * (wide ? 2 : 1);
						if (paintBackground) draw.addRectFilled(x, y, x + w, y + lineHeight, bgColor);
						if (tab.hasSelection() && tab.selected(first + r, c)) draw.addRectFilled(x, y, x + cellWidth, y + lineHeight, selectionColor);
						if (cp != ' ' && cp != TerminalEmulator.CONTINUATION && (attr & TerminalEmulator.HIDDEN) == 0) {
							int color = (attr & TerminalEmulator.DIM) != 0 ? (fgColor & 0x00FFFFFF) | 0x99000000 : fgColor;
							draw.addText(x, y, color, glyph(cp));
							if ((attr & TerminalEmulator.ITALIC) != 0) { /* the editor font has no italic face: shown upright */ }
						}
						if ((attr & TerminalEmulator.UNDERLINE) != 0 && cp != TerminalEmulator.CONTINUATION) draw.addLine(x, y + lineHeight - 2, x + w, y + lineHeight - 2, fgColor, 1f);
						if ((attr & TerminalEmulator.STRIKE) != 0) draw.addLine(x, y + lineHeight * .55f, x + w, y + lineHeight * .55f, fgColor, 1f);
					}
				}
				if (emulator.cursorVisible() && tab.scrollOffset == 0 && first + emulator.cursorY() >= 0) {
					float cx = x0 + emulator.cursorX() * cellWidth, cy = y0 + (emulator.cursorY() + (total - rows - first)) * lineHeight;
					if (focused) {
						if (blinkOn || ImGui.isMouseDown(0)) {
							draw.addRectFilled(cx, cy, cx + cellWidth, cy + lineHeight, (foreground & 0x00FFFFFF) | 0xCC000000);
							var line = emulator.lineAt(emulator.scrollbackSize() + emulator.cursorY());
							int cp = emulator.cursorX() < line.ch.length ? line.ch[emulator.cursorX()] : ' ';
							if (cp != ' ' && cp != TerminalEmulator.CONTINUATION) draw.addText(cx, cy, background, glyph(cp));
						}
					} else draw.addRect(cx, cy, cx + cellWidth, cy + lineHeight, foreground, 0f, 0, 1f);
				}
			}
			if (tab.scrollOffset > 0) {
				String label = LuaUiText.text("已滚动 ", "Scrolled ") + tab.scrollOffset + LuaUiText.text(" 行 · 输入或滚动到底部返回", " lines · type or scroll down to return");
				float labelWidth = ImGui.calcTextSize(label).x + 12;
				draw.addRectFilled(x0 + width - labelWidth - 6, y0 + 4, x0 + width - 6, y0 + lineHeight + 6, 0xCC202020, 4f);
				draw.addText(x0 + width - labelWidth, y0 + 5, 0xFFE0E0E0, label);
			}
			draw.popClipRect();

			handleMouse(tab, x0, y0, cellWidth, lineHeight, mouseColumn, mouseRow);
			if (ImGui.beginPopupContextItem("terminal_context", ImGuiPopupFlags.MouseButtonRight) ) {
				if (ImGui.menuItem(LuaUiText.text("复制", "Copy"), "Ctrl+Shift+C", false, tab.hasSelection())) copy(tab);
				if (ImGui.menuItem(LuaUiText.text("粘贴", "Paste"), "Ctrl+Shift+V")) paste(tab);
				if (ImGui.menuItem(LuaUiText.text("清屏", "Clear screen"))) { tab.send(tab.emulator.alternateScreen() ? "\f" : "\u001b[2J\u001b[H"); }
				ImGui.separator();
				if (ImGui.menuItem(LuaUiText.text("放大字体", "Larger font"), "Ctrl+wheel")) fontSize = Math.min(40, fontSize + 1);
				if (ImGui.menuItem(LuaUiText.text("缩小字体", "Smaller font"))) fontSize = Math.max(9, fontSize - 1);
				if (ImGui.menuItem(LuaUiText.text("关闭此终端", "Close this terminal"), "Ctrl+Shift+W")) closeTab(tab);
				ImGui.endPopup();
			}
		} finally {
			ImGui.popFont();
		}
	}

	private static int resolve(int color, int fallback, boolean light, boolean bold) {
		if (color == 0) return fallback;
		if ((color & TerminalEmulator.INDEXED) != 0) {
			int index = color & 0xFF;
			if (bold && index < 8) index += 8;
			return paletteColor(index, light);
		}
		return abgr(color & 0xFFFFFF);
	}

	private void handleMouse(TerminalTab tab, float x0, float y0, float cellWidth, float lineHeight, int column, int row) {
		var emulator = tab.emulator;
		var io = ImGui.getIO();
		boolean shift = io.getKeyShift(), alt = io.getKeyAlt(), ctrl = io.getKeyCtrl();
		int first = Math.max(0, emulator.totalLines() - emulator.rows() - tab.scrollOffset);
		if (emulator.mouseMode() != 0 && !shift) {
			if (hovered) {
				for (int button = 0; button < 3; button++) {
					if (ImGui.isMouseClicked(button)) { report(tab, button, false, false, alt, ctrl, column, row); reportedColumn = column; reportedRow = row; }
					if (ImGui.isMouseReleased(button)) report(tab, button, true, false, alt, ctrl, column, row);
				}
				boolean anyDown = ImGui.isMouseDown(0) || ImGui.isMouseDown(1) || ImGui.isMouseDown(2);
				if ((column != reportedColumn || row != reportedRow) && (emulator.mouseMode() == 1003 || emulator.mouseMode() == 1002 && anyDown)) {
					int button = ImGui.isMouseDown(0) ? 0 : ImGui.isMouseDown(1) ? 2 : ImGui.isMouseDown(2) ? 1 : 3;
					report(tab, button, false, true, alt, ctrl, column, row); reportedColumn = column; reportedRow = row;
				}
			}
			return;
		}
		int line = first + row;
		if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
			if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
				int[] word = wordAt(emulator, line, column);
				tab.selectionStartLine = line; tab.selectionStartColumn = word[0]; tab.selectionEndLine = line; tab.selectionEndColumn = word[1];
				tab.selecting = false;
			} else {
				tab.selectionStartLine = line; tab.selectionStartColumn = column; tab.selectionEndLine = line; tab.selectionEndColumn = column; tab.selecting = true;
			}
		}
		if (tab.selecting) {
			if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
				float my = ImGui.getMousePosY();
				if (my < y0) tab.scrollOffset = Math.min(emulator.scrollbackSize(), tab.scrollOffset + 1);
				else if (my > y0 + emulator.rows() * lineHeight) tab.scrollOffset = Math.max(0, tab.scrollOffset - 1);
				tab.selectionEndLine = first + Math.clamp((int) ((my - y0) / lineHeight), 0, emulator.rows() - 1);
				tab.selectionEndColumn = Math.clamp((int) ((ImGui.getMousePosX() - x0) / cellWidth), 0, emulator.cols() - 1);
			} else {
				tab.selecting = false;
				if (!tab.hasSelection()) tab.clearSelection();
			}
		}
	}

	private void report(TerminalTab tab, int button, boolean release, boolean motion, boolean alt, boolean ctrl, int column, int row) {
		String report = TerminalInput.mouse(button, release, motion, ImGui.getIO().getKeyShift(), alt, ctrl, column, row, tab.emulator.mouseSgr());
		if (report != null) tab.send(report);
	}

	private static int[] wordAt(TerminalEmulator emulator, int line, int column) {
		var row = emulator.lineAt(Math.clamp(line, 0, emulator.totalLines() - 1));
		int start = Math.min(column, row.ch.length - 1), end = start;
		if (row.ch[start] == ' ') return new int[]{start, start};
		while (start > 0 && row.ch[start - 1] != ' ') start--;
		while (end < row.ch.length - 1 && row.ch[end + 1] != ' ') end++;
		return new int[]{start, end};
	}
}
