package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * Native draw-list code view. The existing editing model supplies edits/undo, never GUI rendering.
 * <p>
 * On top of plain text editing this class provides the editor conveniences people expect from a modern IDE: find and replace
 * (case, whole word, regular expression), bracket matching and auto-closing, current-line and same-symbol highlighting, indent
 * guides, a minimap, go to line / definition / references, rename, multiple carets, snippets and Tab navigation between
 * placeholders. The text logic itself lives in small pure classes (LuaLexer, BracketMatcher, EditorSearch, LuaSymbolIndex,
 * LuaSnippets) which are covered by EditorToolsSmoke.
 */
final class NativeCodeArea {
	private record Row(int start, int end, int line, float[] advances) { float width() { return advances[advances.length - 1]; } }
	/** A selection (or an empty one, a plain caret). {@code cursor} is where the caret is drawn. */
	record Caret(int anchor, int cursor) {
		int start() { return Math.min(anchor, cursor); }
		int end() { return Math.max(anchor, cursor); }
		boolean hasSelection() { return anchor != cursor; }
	}
	record Reference(int start, int end, int line, String preview) { }

	private static final int MINIMAP_WIDTH = 86;
	private final LuaIdeScreen model;
	private final Map<Integer, Float> glyphs = new HashMap<>();
	private List<Row> rows = List.of();
	private String source = "", document = "", layoutKey = "";
	private boolean focused, dragging, reveal, hovered, focusRequested;
	private int anchor, lastCursor = -1, hoverOffset;
	private float originX, originY, gutter, lineHeight, scrollX, scrollY, viewWidth, viewHeight;
	private LuaAiAgent.Mark hoveredMark;
	private float fontSize;

	// --- find / replace
	private boolean searchOpen, replaceMode, searchFocus, caseSensitive, wholeWord, regexMode;
	private String search = "", replacement = "", searchError = "", matchKey = "", searchNotice = "";
	private List<EditorSearch.Match> matches = List.of();

	// --- analysis caches (rebuilt only when the text changes)
	private String analysisKey = "";
	private List<BracketMatcher.Pair> bracketPairs = List.of();
	private List<Integer> unmatchedBrackets = List.of();
	private LuaSymbolIndex symbols;

	// --- multiple carets and snippet session
	private final List<Caret> extraCarets = new ArrayList<>();
	private List<int[]> snippetStops = new ArrayList<>();
	private int snippetIndex, snippetTextLength;

	// --- popups
	private String popupRequest = "";
	private final ImString lineInput = new ImString(32), renameInput = new ImString(128);
	private String popupError = "";
	private List<Reference> references = List.of();
	private boolean minimap = true, minimapDragging;

	// --- debugger: breakpoint options popup and value-on-hover
	private int optionLine, gutterHoverLine, contextOffset = -1;
	private final ImString conditionInput = new ImString(512), logInput = new ImString(512);
	private final imgui.type.ImInt hitsInput = new imgui.type.ImInt();
	private String hoverExpression = "";
	private long hoverSince;
	private static final java.util.regex.Pattern DOTTED_NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

	NativeCodeArea(LuaIdeScreen model) { this.model = model; }

	// ---- state exposed to the workspace -----------------------------------------------------------------------------------

	private float caretX, caretTop;
	/** Screen position of the caret: x, top of its line, and line height. Valid after render(). */
	float[] caret() { return new float[]{caretX, caretTop, lineHeight}; }
	boolean focused() { return focused; }
	void beginFrame() { focused = false; hovered = false; }
	void focus() { reveal = true; focused = true; focusRequested = true; }
	boolean minimapVisible() { return minimap; }
	void setMinimapVisible(boolean value) { minimap = value; }
	LuaSymbolIndex symbolIndex() { return symbols; }
	String sourceSnapshot() { return source; }

	/** Esc closes, in order: extra carets and the snippet session, the find bar, then AI/MCP marks. */
	boolean dismissMarks() {
		if (!extraCarets.isEmpty() || !snippetStops.isEmpty()) { extraCarets.clear(); snippetStops = new ArrayList<>(); return true; }
		if (searchOpen) { searchOpen = false; matches = List.of(); matchKey = ""; return true; }
		if (hoveredMark == null) return false;
		model.nativeDismissMarks(); hoveredMark = null; return true;
	}

	private String currentText() {
		var snapshot = model.nativeSnapshot();
		return snapshot == null ? source : snapshot.source();
	}
	private Caret primary() { return new Caret(model.nativeAnchor(), model.nativeCursor()); }

	// ---- public editor commands ---------------------------------------------------------------------------------------------

	void find() { openFind(false); }
	void findAndReplace() { openFind(true); }
	private void openFind(boolean replace) {
		searchOpen = true; replaceMode = replace; searchFocus = true; searchNotice = "";
		int a = model.nativeAnchor(), b = model.nativeCursor();
		String text = currentText();
		if (a != b && Math.abs(a - b) < 200 && !text.substring(Math.min(a, b), Math.max(a, b)).contains("\n"))
			search = text.substring(Math.min(a, b), Math.max(a, b));
		matchKey = "";
	}
	void goToLine() { popupRequest = "goto_line"; lineInput.set(""); popupError = ""; }
	/** Navigation that can leave the file: globals and members of required modules (see CrossFileActions). */
	interface CrossFile {
		/** True when the target spans files and the request was taken over. */
		boolean definition(String source, int offset);
		boolean references(String source, int offset);
		boolean rename(String source, int offset, String newName);
		boolean spansFiles(String source, int offset);
	}
	private CrossFile crossFile;
	private boolean renameSpansFiles;
	private final imgui.type.ImBoolean renameAllFiles = new imgui.type.ImBoolean(true);
	void crossFile(CrossFile handler) { crossFile = handler; }

	void requestRename() {
		var occurrence = symbols == null ? null : symbols.occurrenceAt(model.nativeCursor());
		if (occurrence == null) { searchNotice = LuaUiText.text("光标不在可重命名的标识符上", "The caret is not on a renamable identifier"); return; }
		renameInput.set(symbols.symbol(occurrence.symbol()).name()); popupError = ""; popupRequest = "rename";
		renameSpansFiles = crossFile != null && crossFile.spansFiles(currentText(), model.nativeCursor());
	}
	/** Renames the symbol at the caret everywhere in this file (one undo step); false when the name is invalid or no symbol is there. */
	boolean renameTo(String newName) {
		var edits = symbols == null ? null : symbols.renameEdits(model.nativeCursor(), newName);
		if (edits == null) return false;
		String text = currentText();
		int caret = model.nativeCursor();
		String renamed = LuaSymbolIndex.apply(text, edits);
		model.nativeReplace(0, text.length(), renamed);
		int restored = Math.min(renamed.length(), caret);
		model.nativeSelect(restored, restored);
		searchNotice = LuaUiText.text("已重命名 " + edits.size() + " 处", "Renamed " + edits.size() + " references");
		return true;
	}
	/** Sets the find and replace fields programmatically (used by tests and "find in files" hand-offs). */
	void setSearch(String query, String replace, boolean regex) { search = query; replacement = replace; regexMode = regex; matchKey = ""; searchOpen = true; replaceMode = true; }
	int extraCaretCount() { return extraCarets.size(); }
	void goToOffset(int offset) { model.nativeSelect(offset, offset); reveal = true; focus(); }
	void selectRange(int start, int end) { model.nativeSelect(start, end); reveal = true; }
	void goToLineNumber(int oneBasedLine) {
		String text = currentText();
		int offset = 0, line = 1;
		while (line < oneBasedLine) { int next = text.indexOf('\n', offset); if (next < 0) break; offset = next + 1; line++; }
		goToOffset(offset);
	}

	/** Jumps to the declaration of the symbol under the caret; on the declaration itself it lists the references instead. */
	void goToDefinition() { goToDefinitionAt(model.nativeCursor()); }
	private void goToDefinitionAt(int offset) {
		if (crossFile != null && crossFile.definition(currentText(), offset)) return;
		if (symbols == null) return;
		var occurrence = symbols.occurrenceAt(offset);
		if (occurrence == null) return;
		var symbol = symbols.symbol(occurrence.symbol());
		if (occurrence.declaration()) { findReferencesAt(offset); return; }
		selectRange(symbol.declStart(), symbol.declEnd()); focus();
	}
	void findReferences() { findReferencesAt(model.nativeCursor()); }
	private void findReferencesAt(int offset) {
		if (crossFile != null && crossFile.references(currentText(), offset)) return;
		if (symbols == null) return;
		var occurrence = symbols.occurrenceAt(offset);
		if (occurrence == null) return;
		String text = currentText();
		List<Reference> list = new ArrayList<>();
		for (var use : symbols.referencesOf(occurrence.symbol())) {
			int lineStart = text.lastIndexOf('\n', Math.max(0, use.start() - 1)) + 1, lineEnd = text.indexOf('\n', use.start());
			list.add(new Reference(use.start(), use.end(), LuaSymbolIndex.lineOf(text, use.start()) + 1,
					text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd).strip()));
		}
		references = list; popupRequest = "references";
	}

	/** Ctrl+D: select the word, then add the next occurrence of the selection as another caret. */
	void addNextOccurrence() {
		String text = currentText();
		Caret now = primary();
		if (!now.hasSelection()) {
			int start = now.cursor(), end = now.cursor();
			while (start > 0 && LuaLexer.isNamePart(text.charAt(start - 1))) start--;
			while (end < text.length() && LuaLexer.isNamePart(text.charAt(end))) end++;
			if (end > start) selectRange(start, end);
			return;
		}
		String selected = text.substring(now.start(), now.end());
		int from = now.end();
		for (Caret extra : extraCarets) from = Math.max(from, extra.end());
		int found = text.indexOf(selected, from);
		if (found < 0) found = text.indexOf(selected);
		if (found < 0) return;
		for (Caret caret : allCarets()) if (found < caret.end() && found + selected.length() > caret.start()) return; // already selected
		extraCarets.add(now);
		model.nativeSelect(found, found + selected.length());
		reveal = true;
	}
	private List<Caret> allCarets() { var all = new ArrayList<Caret>(); all.add(primary()); all.addAll(extraCarets); return all; }

	// ---- find bar ---------------------------------------------------------------------------------------------------------

	private boolean toggle(String label, boolean value, String tooltip) {
		if (value) ImGui.pushStyleColor(ImGuiCol.Button, ImGui.getColorU32(ImGuiCol.ButtonActive));
		boolean pressed = ImGui.smallButton(label);
		if (value) ImGui.popStyleColor();
		if (ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
		return pressed ? !value : value;
	}

	private void refreshMatches(ImGuiIdeDocument doc) {
		String key = doc.key() + ":" + doc.revision + ":" + search + ":" + caseSensitive + wholeWord + regexMode;
		if (key.equals(matchKey)) return;
		matchKey = key; searchError = "";
		if (search.isEmpty()) { matches = List.of(); return; }
		try { matches = EditorSearch.findAll(doc.source, search, new EditorSearch.Options(caseSensitive, wholeWord, regexMode)); }
		catch (EditorSearch.QueryException error) { matches = List.of(); searchError = error.getMessage(); }
	}
	private EditorSearch.Options options() { return new EditorSearch.Options(caseSensitive, wholeWord, regexMode); }

	/** Index of the match that is selected, or -1. */
	private int currentMatch() {
		int a = model.nativeAnchor(), c = model.nativeCursor(), start = Math.min(a, c), end = Math.max(a, c);
		for (int i = 0; i < matches.size(); i++) if (matches.get(i).start() == start && matches.get(i).end() == end) return i;
		return -1;
	}
	private void stepMatch(int direction) {
		if (matches.isEmpty()) return;
		int current = currentMatch(), caret = model.nativeCursor(), target;
		if (current >= 0) target = Math.floorMod(current + direction, matches.size());
		else {
			target = direction > 0 ? 0 : matches.size() - 1;
			for (int i = 0; i < matches.size(); i++) if (matches.get(i).start() >= caret) { target = direction > 0 ? i : Math.floorMod(i - 1, matches.size()); break; }
		}
		var match = matches.get(target); selectRange(match.start(), match.end());
	}
	private void replaceCurrent() {
		int current = currentMatch();
		if (current < 0) { stepMatch(1); return; }
		var match = matches.get(current);
		try {
			String text = EditorSearch.replacementFor(currentText(), match, search, options(), replacement);
			model.nativeReplace(match.start(), match.end(), text);
			matchKey = ""; searchNotice = "";
			model.nativeSelect(match.start() + text.length(), match.start() + text.length());
		} catch (EditorSearch.QueryException error) { searchError = error.getMessage(); }
	}
	void replaceEverything() {
		try {
			String text = currentText();
			var result = EditorSearch.replaceAll(text, search, options(), replacement);
			if (result.count() == 0) { searchNotice = LuaUiText.text("没有可替换的内容", "Nothing to replace"); return; }
			int caret = Math.min(model.nativeCursor(), result.text().length());
			model.nativeReplace(0, text.length(), result.text());
			model.nativeSelect(caret, caret); matchKey = "";
			searchNotice = LuaUiText.text("已替换 " + result.count() + " 处", "Replaced " + result.count());
		} catch (EditorSearch.QueryException error) { searchError = error.getMessage(); }
	}

	private void drawFindBar(ImGuiIdeDocument doc) {
		if (!searchOpen) return;
		refreshMatches(doc);
		ImGui.pushID("find_bar");
		if (searchFocus) { ImGui.setKeyboardFocusHere(); searchFocus = false; }
		ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 360));
		String typed = NativeUi.input("##find", search);
		boolean enter = ImGui.isItemFocused() && ImGui.isKeyPressed(ImGuiKey.Enter);
		if (!typed.equals(search)) { search = typed; matchKey = ""; searchNotice = ""; }
		boolean shift = ImGui.getIO().getKeyShift();
		ImGui.sameLine(); caseSensitive = toggle("Aa", caseSensitive, LuaUiText.text("区分大小写", "Match case"));
		ImGui.sameLine(); wholeWord = toggle("W", wholeWord, LuaUiText.text("全字匹配", "Whole word"));
		ImGui.sameLine(); regexMode = toggle(".*", regexMode, LuaUiText.text("正则表达式", "Regular expression"));
		ImGui.sameLine();
		int current = currentMatch();
		ImGui.textDisabled(search.isEmpty() ? "" : matches.isEmpty() ? LuaUiText.text("无结果", "No results")
				: (current >= 0 ? String.valueOf(current + 1) : "?") + "/" + matches.size() + (matches.size() >= EditorSearch.MAX_MATCHES ? "+" : ""));
		ImGui.sameLine(); if (ImGui.smallButton("↑##prev")) stepMatch(-1);
		ImGui.sameLine(); if (ImGui.smallButton("↓##next")) stepMatch(1);
		ImGui.sameLine(); if (ImGui.smallButton(replaceMode ? "▾##mode" : "▸##mode")) replaceMode = !replaceMode;
		ImGui.sameLine(); if (ImGui.smallButton("×##close")) { searchOpen = false; matches = List.of(); matchKey = ""; }
		if (enter) { stepMatch(shift ? -1 : 1); searchFocus = true; }
		if (replaceMode) {
			ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 220));
			replacement = NativeUi.input("##replace", replacement);
			ImGui.sameLine(); if (ImGui.smallButton(LuaUiText.text("替换", "Replace") + "##one")) replaceCurrent();
			ImGui.sameLine(); if (ImGui.smallButton(LuaUiText.text("全部替换", "Replace all") + "##all")) replaceEverything();
		}
		if (!searchError.isEmpty()) ImGui.textColored(1f, .45f, .4f, 1f, searchError);
		else if (!searchNotice.isEmpty()) ImGui.textDisabled(searchNotice);
		ImGui.popID();
	}

	private void refreshAnalysis(ImGuiIdeDocument doc) {
		String key = doc.key() + ":" + doc.revision;
		if (key.equals(analysisKey)) return;
		analysisKey = key;
		if (!doc.lua() || doc.source.length() > 400_000) { bracketPairs = List.of(); unmatchedBrackets = List.of(); symbols = null; return; }
		var tokens = LuaLexer.tokenize(doc.source);
		bracketPairs = BracketMatcher.pairs(tokens);
		unmatchedBrackets = BracketMatcher.unmatched(tokens);
		symbols = LuaSymbolIndex.build(doc.source);
	}

	// ---- drawing ----------------------------------------------------------------------------------------------------------

	void render(ImGuiIdeDocument doc) {
		if (!document.equals(doc.key())) {
			document = doc.key(); lastCursor = -1; reveal = true; extraCarets.clear(); snippetStops = new ArrayList<>(); matchKey = ""; analysisKey = "";
		}
		drawFindBar(doc);
		refreshAnalysis(doc);
		var settings = model.nativePersonalization();
		lineHeight = Math.max(6, ImGui.getTextLineHeight() * settings.lineHeight + 3);
		gutter = ImGui.calcTextSize("000000").x + 28;
		int flags = ImGuiWindowFlags.NoNavInputs | (settings.codeWordWrap ? 0 : ImGuiWindowFlags.HorizontalScrollbar);
		ImGui.pushStyleColor(ImGuiCol.ChildBg, NativeUi.rgba(settings.theme.background()));
		ImGui.beginChild("native_code", 0, Math.max(40, ImGui.getContentRegionAvailY()), true, flags);
		if (focusRequested) { ImGui.setWindowFocus(); focusRequested = false; }
		focused = ImGui.isWindowFocused(); hovered = ImGui.isWindowHovered();
		viewWidth = Math.max(30, ImGui.getWindowWidth() - ImGui.getStyle().getWindowPaddingX() * 2);
		viewHeight = Math.max(30, ImGui.getWindowHeight() - ImGui.getStyle().getWindowPaddingY() * 2);
		float size = ImGui.getFontSize();
		if (fontSize != size) { glyphs.clear(); fontSize = size; }
		var folds = model.nativeFolds();
		if (folds != null) folds.model(doc.source);
		float reserve = minimap ? MINIMAP_WIDTH : 0;
		String key = doc.key() + ":" + doc.revision + ":" + size + ":" + viewWidth + ":" + settings.codeWordWrap + ":" + settings.cellWidth + ":" + (folds == null ? 0 : folds.revision()) + ":" + reserve;
		if (!key.equals(layoutKey)) {
			source = doc.source; layoutKey = key;
			layout(settings.codeWordWrap ? Math.max(20, viewWidth - gutter - 18 - reserve) : Float.MAX_VALUE, settings.cellWidth, folds);
		}
		int cursor = model.nativeCursor(), selection = model.nativeAnchor();
		if (cursor != lastCursor) { reveal = true; lastCursor = cursor; }
		if (reveal && !rows.isEmpty()) {
			int row = rowAtOffset(cursor);
			float yy = row * lineHeight, xx = gutter + advance(rows.get(row), cursor);
			if (yy < ImGui.getScrollY()) ImGui.setScrollY(yy);
			else if (yy + lineHeight > ImGui.getScrollY() + viewHeight) ImGui.setScrollY(yy - viewHeight + lineHeight);
			if (!settings.codeWordWrap) {
				if (xx < ImGui.getScrollX() + gutter) ImGui.setScrollX(Math.max(0, xx - gutter));
				else if (xx > ImGui.getScrollX() + viewWidth - 25 - reserve) ImGui.setScrollX(xx - viewWidth + 25 + reserve);
			}
			reveal = false;
		}
		scrollX = ImGui.getScrollX(); scrollY = ImGui.getScrollY(); originX = ImGui.getCursorScreenPosX(); originY = ImGui.getCursorScreenPosY();
		{ int caretIndex = rowAtOffset(cursor); caretX = originX + gutter + advance(rows.get(caretIndex), cursor); caretTop = originY + caretIndex * lineHeight; }
		var draw = ImGui.getWindowDrawList();
		float maxWidth = viewWidth;
		if (!settings.codeWordWrap) for (var row : rows) maxWidth = Math.max(maxWidth, row.width() + gutter + 40 + reserve);
		ImGui.dummy(maxWidth, Math.max(viewHeight, rows.size() * lineHeight + 16));
		float left = ImGui.getWindowPosX() + 1, top = ImGui.getWindowPosY() + 1, right = left + ImGui.getWindowWidth() - 12, bottom = top + ImGui.getWindowHeight() - 12;
		draw.pushClipRect(left, top, right, bottom, true);
		LuaEditorBackground.renderImGui(settings, left, top, right, bottom);
		draw.addRectFilled(left, top, originX + scrollX + gutter - 7, bottom, NativeUi.rgba(settings.theme.background()));
		int first = Math.max(0, (int) (scrollY / lineHeight)), last = Math.min(rows.size(), first + (int) (viewHeight / lineHeight) + 3);
		var errors = doc.analysis.diagnostics();
		var marks = model.nativeMarks();
		var breakpoints = model.nativeBreakpoints();
		var breakpointOptions = model.nativeBreakpointOptions();
		int paused = model.nativePausedLine();
		hoveredMark = null; hoverOffset = -1; gutterHoverLine = -1;
		float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();
		boolean overMinimap = minimap && mx >= right - MINIMAP_WIDTH && mx <= right && my >= top && my <= bottom;
		String selected = selection == cursor ? "" : source.substring(Math.min(selection, cursor), Math.max(selection, cursor));
		int caretRow = rows.isEmpty() ? -1 : rowAtOffset(cursor);
		int guideColor = ImGui.getColorU32(ImGuiCol.Border, .55f), accent = ImGui.getColorU32(ImGuiCol.CheckMark);
		var bracket = BracketMatcher.at(bracketPairs, cursor);
		var onSymbol = symbols == null ? null : symbols.occurrenceAt(cursor);
		for (int index = first; index < last; index++) {
			var row = rows.get(index);
			float y = originY + index * lineHeight, x = originX + gutter;
			int visibleStart = offsetAt(row, Math.max(0, left - x), false), visibleEnd = Math.min(row.end, offsetAt(row, Math.max(0, right - x), true) + 2);
			boolean main = index == 0 || rows.get(index - 1).line != row.line;
			if (paused == row.line) draw.addRectFilled(x, y, right, y + lineHeight, 0x5534BAFF);
			else if (index == caretRow && selection == cursor && focused) draw.addRectFilled(x - 4, y, right, y + lineHeight, ImGui.getColorU32(ImGuiCol.Header, .28f));
			boolean error = errors.stream().anyMatch(d -> d.line() == row.line && d.severity() == LuaCodeAnalyzer.Severity.ERROR);
			float numberX = originX + scrollX + 4;
			if (main) {
				draw.addText(numberX, y, error ? 0xFF7777FF : ImGui.getColorU32(ImGuiCol.TextDisabled), Integer.toString(row.line));
				if (breakpoints.contains(row.line)) drawBreakpointMarker(draw, numberX + gutter - 28, y + lineHeight * .4f, breakpointOptions.get(row.line));
				if (hovered && mx < originX + scrollX + gutter - 3 && mx > left && my >= y && my < y + lineHeight) gutterHoverLine = row.line;
				var fold = folds == null ? null : folds.model(source).atLine(row.line);
				if (fold != null) draw.addText(numberX + gutter - 18, y, ImGui.getColorU32(ImGuiCol.Text), folds.collapsed(fold) ? ">" : "v");
				// Indent guides: a faint line at every indentation level of a non-blank line.
				int whitespace = 0;
				while (row.start + whitespace < row.end && (source.charAt(row.start + whitespace) == ' ' || source.charAt(row.start + whitespace) == '\t')) whitespace++;
				if (whitespace > 0 && row.start + whitespace < row.end) {
					int step = source.charAt(row.start) == '\t' ? 1 : 4;
					for (int column = step; column < whitespace; column += step) {
						float gx = x + advance(row, row.start + column);
						draw.addLine(gx, y, gx, y + lineHeight, guideColor, 1);
					}
				}
			}
			if (!selected.isEmpty() && selected.length() < 4096) {
				int p = source.indexOf(selected, Math.max(row.start, visibleStart - selected.length()));
				while (p >= row.start && p < visibleEnd) { rect(draw, row, p, Math.min(row.end, p + selected.length()), x, y, 0x446DAFE8); p = source.indexOf(selected, p + Math.max(1, selected.length())); }
			}
			if (searchOpen && !matches.isEmpty()) {
				int currentIndex = currentMatch();
				for (int m = firstMatchEndingAfter(row.start); m < matches.size() && matches.get(m).start() < row.end; m++) {
					var match = matches.get(m);
					rect(draw, row, Math.max(row.start, match.start()), Math.min(row.end, match.end()), x, y, m == currentIndex ? 0xAA3DC9F5 : 0x663DC9F5);
				}
			}
			if (onSymbol != null && selection == cursor && symbols != null) {
				for (var use : symbols.referencesOf(onSymbol.symbol())) {
					int a = Math.max(row.start, use.start()), b = Math.min(row.end, use.end());
					if (a < b) rect(draw, row, a, b, x, y, 0x33FFFFFF);
				}
			}
			if (cursor != selection) rect(draw, row, Math.max(row.start, Math.min(cursor, selection)), Math.min(row.end, Math.max(cursor, selection)), x, y, 0x99674638);
			for (var caret : extraCarets) if (caret.hasSelection()) rect(draw, row, Math.max(row.start, caret.start()), Math.min(row.end, caret.end()), x, y, 0x99674638);
			for (var mark : marks) {
				int a = Math.max(row.start, mark.start()), b = Math.min(row.end, mark.end());
				if (a >= b) continue;
				rect(draw, row, a, b, x, y, switch (mark.severity()) { case "error" -> 0x667257E9; case "warning" -> 0x6646AAD7; case "mcp" -> 0x66A5B943; default -> 0x66C78455; });
				if (hovered && !ImGui.isMouseDown(0) && my >= y && my < y + lineHeight && mx >= x + advance(row, a) && mx < x + advance(row, b)) hoveredMark = mark;
			}
			for (var diagnostic : errors) {
				int a = Math.max(row.start, diagnostic.start()), b = Math.min(row.end, diagnostic.end());
				if (a >= b) continue;
				float ax = x + advance(row, a), bx = x + advance(row, b);
				draw.addLine(ax, y + lineHeight - 2, bx, y + lineHeight - 2, diagnostic.severity() == LuaCodeAnalyzer.Severity.ERROR ? 0xFF7777FF : 0xFF55CCEE, 1);
			}
			final int[] offset = {visibleStart};
			model.nativeHighlight(visibleStart, visibleEnd, row.line).visit((style, text) -> {
				int color = style.getColor() == null ? settings.syntaxColor(LuaSyntaxHighlighter.ColorRole.NORMAL) : style.getColor().getValue();
				for (int p = 0; p < text.length(); ) {
					int cp = text.codePointAt(p), count = Character.charCount(cp);
					if (cp != '\t') draw.addText(x + advance(row, offset[0]), y, NativeUi.rgba(0xFF000000 | color), new String(Character.toChars(cp)));
					offset[0] += count; p += count;
				}
				return Optional.empty();
			}, Style.EMPTY);
			if (folds != null && main) {
				var fold = folds.model(source).atLine(row.line);
				if (fold != null && folds.collapsed(fold)) draw.addText(x + row.width() + 10, y, ImGui.getColorU32(ImGuiCol.TextDisabled), "…  " + (fold.endLine() - fold.line()) + " lines");
			}
			// Matching brackets: a box around both; unbalanced brackets get a red underline.
			if (bracket != null) for (int at : new int[]{bracket.open(), bracket.close()}) {
				if (at >= row.start && at < row.end) draw.addRect(x + advance(row, at) - 1, y, x + advance(row, at + 1) + 1, y + lineHeight - 1, accent, 2, 0, 1.5f);
			}
			for (int bad : unmatchedBrackets) if (bad >= row.start && bad < row.end) {
				float ax = x + advance(row, bad), bx = x + advance(row, bad + 1);
				draw.addLine(ax, y + lineHeight - 2, bx, y + lineHeight - 2, 0xFF5555FF, 2);
			}
			if (focused) {
				if (cursor >= row.start && (cursor < row.end || cursor == row.end && (index == rows.size() - 1 || rows.get(index + 1).start != cursor))) drawCaret(draw, settings, row, cursor, x, y);
				for (var caret : extraCarets)
					if (caret.cursor() >= row.start && (caret.cursor() < row.end || caret.cursor() == row.end && (index == rows.size() - 1 || rows.get(index + 1).start != caret.cursor())))
						drawCaret(draw, settings, row, caret.cursor(), x, y);
			}
			if (hovered && !overMinimap && my >= y && my < y + lineHeight) hoverOffset = offsetAt(row, mx - x, false);
		}
		if (minimap) drawMinimap(draw, settings, left, top, right, bottom, accent);
		draw.popClipRect();
		handleMouse(folds, selection, top, bottom, mx, my, overMinimap);
		boolean overGutter = hovered && mx < originX + scrollX + gutter - 3;
		if (overGutter && gutterHoverLine > 0 && ImGui.isMouseClicked(1)) openBreakpointOptions(gutterHoverLine);
		if (overGutter && gutterHoverLine > 0 && breakpointOptions.containsKey(gutterHoverLine)) {
			var option = breakpointOptions.get(gutterHoverLine);
			ImGui.setTooltip((option.logpoint() ? LuaUiText.text("日志点：", "Logpoint: ") + option.logMessage() : "")
					+ (option.condition().isEmpty() ? "" : LuaUiText.text("条件：", "Condition: ") + option.condition())
					+ (option.hitCount() > 1 ? "\n" + LuaUiText.text("第 " + option.hitCount() + " 次及以后命中", "Hit count ≥ " + option.hitCount()) : "")
					+ "\n" + LuaUiText.text("右键编辑", "Right-click to edit"));
		}
		if (!hovered || hoverOffset < 0) hoverExpression = "";
		if (hovered && ImGui.isMouseClicked(1)) contextOffset = hoverOffset;
		if (hoveredMark != null) ImGui.setTooltip(hoveredMark.message() + "\nEsc · " + LuaUiText.text("清除全部标记"));
		else if (hovered && !overMinimap && hoverOffset >= 0 && !ImGui.isMouseDown(0)) {
			var api = LuaApiDocumentation.find(symbolAt(hoverOffset));
			if (api != null) {
				ImGui.beginTooltip(); ImGui.pushTextWrapPos(520);
				ImGui.textUnformatted(api.signature()); ImGui.separator();
				ImGui.textWrapped(LuaUiText.text("作用：") + api.summary()); ImGui.textWrapped(LuaUiText.text("返回：") + api.returns());
				for (int i = 0; i < api.parameters().size(); i++) ImGui.textWrapped(LuaUiText.text("参数") + (i + 1) + LuaUiText.text("：") + api.describe(api.parameters().get(i)));
				ImGui.popTextWrapPos(); ImGui.endTooltip();
			}
			for (var diagnostic : errors) if (hoverOffset >= diagnostic.start() && hoverOffset < diagnostic.end()) ImGui.setTooltip(diagnostic.message());
				if (api == null && paused > 0) hoverValue(hoverOffset);
		}
		if (!overGutter && ImGui.beginPopupContextWindow("code_actions", ImGuiPopupFlags.MouseButtonRight | ImGuiPopupFlags.NoOpenOverExistingPopup)) {
			contextMenu();
			ImGui.endPopup();
		}
		if (focused) ImGui.getIO().setWantTextInput(true);
		ImGui.endChild(); ImGui.popStyleColor(); doc.capture();
		drawPopups();
	}

	/** Red dot: plain breakpoint. Orange dot with a white centre: conditional (condition and/or hit count). Blue diamond: logpoint. */
	private void drawBreakpointMarker(imgui.ImDrawList draw, float x, float y, wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints.Option option) {
		if (option == null || option.isPlain()) { draw.addCircleFilled(x, y, 4, 0xFF6666F0); return; }
		if (option.logpoint()) { draw.addQuadFilled(x, y - 5, x + 5, y, x, y + 5, x - 5, y, 0xFFE8A040); return; }
		draw.addCircleFilled(x, y, 4.5f, 0xFF3C9CF5);
		draw.addCircleFilled(x, y, 1.6f, 0xFFFFFFFF);
	}

	/** The dotted name (a, a.b.c) under the offset, or an empty string. Calls and arbitrary expressions are never evaluated on hover. */
	private String dottedNameAt(int offset) {
		String text = source;
		if (offset < 0 || offset >= text.length()) return "";
		int a = offset, b = offset;
		while (a > 0 && (Character.isLetterOrDigit(text.charAt(a - 1)) || text.charAt(a - 1) == '_' || text.charAt(a - 1) == '.')) a--;
		while (b < text.length() && (Character.isLetterOrDigit(text.charAt(b)) || text.charAt(b) == '_')) b++;
		String name = text.substring(a, b);
		if (!DOTTED_NAME.matcher(name).matches() || LuaLexer.KEYWORDS.contains(name)) return "";
		return name;
	}

	/** Value-on-hover while the script is paused: debounced, and only for plain variable chains. */
	private void hoverValue(int offset) {
		String name = dottedNameAt(offset);
		if (name.isEmpty() || !LuaBreakpointClient.canEvaluate()) { hoverExpression = ""; return; }
		long now = System.currentTimeMillis();
		if (!name.equals(hoverExpression)) { hoverExpression = name; hoverSince = now; return; }
		if (now - hoverSince < 350) return;
		var value = LuaBreakpointClient.value(name);
		if (value == null) { LuaBreakpointClient.evaluate(List.of(name)); return; }
		ImGui.beginTooltip(); ImGui.pushTextWrapPos(480);
		ImGui.textUnformatted(name + (value.ok() ? "  (" + value.type() + ")" : ""));
		ImGui.separator();
		if (value.ok()) ImGui.textUnformatted(value.text()); else ImGui.textColored(1f, .5f, .45f, 1f, value.error());
		ImGui.textDisabled(LuaUiText.text("右键 → 添加到监视", "Right-click → Add to watch"));
		ImGui.popTextWrapPos(); ImGui.endTooltip();
	}

	private void openBreakpointOptions(int line) {
		optionLine = line;
		var option = model.nativeBreakpointOptions().getOrDefault(line, wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints.Option.NONE);
		conditionInput.set(option.condition()); logInput.set(option.logMessage()); hitsInput.set(Math.max(0, option.hitCount()));
		popupRequest = "breakpoint_options"; popupError = "";
	}

	private void drawBreakpointPopup() {
		if (!ImGui.beginPopupModal("breakpoint_options##editor_popup", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoTitleBar)) return;
		ImGui.textUnformatted(LuaUiText.text("断点选项 · 第 ", "Breakpoint · line ") + optionLine + LuaUiText.text(" 行", ""));
		ImGui.separator();
		ImGui.setNextItemWidth(360);
		ImGui.inputTextWithHint("##condition", LuaUiText.text("条件（Lua 表达式，为真时才暂停）", "Condition (Lua expression, pause when true)"), conditionInput);
		ImGui.setNextItemWidth(360);
		ImGui.inputTextWithHint("##log", LuaUiText.text("日志消息（{表达式} 插值，设置后不暂停）", "Log message ({expr} interpolation; does not pause)"), logInput);
		ImGui.setNextItemWidth(160);
		ImGui.inputInt(LuaUiText.text("第 N 次及以后命中", "Hit count from N"), hitsInput);
		hitsInput.set(Math.clamp(hitsInput.get(), 0, 1_000_000));
		if (!popupError.isEmpty()) ImGui.textColored(1f, .45f, .4f, 1f, popupError);
		if (ImGui.button(LuaUiText.text("确定", "OK"))) {
			try {
				model.nativeSetBreakpointOption(optionLine, new wiki.ica.xiaomiao.mcluaide.network.LuaBreakpoints.Option(conditionInput.get().strip(), hitsInput.get(), logInput.get().strip()));
				ImGui.closeCurrentPopup();
			} catch (RuntimeException error) { popupError = String.valueOf(error.getMessage()); }
		}
		ImGui.sameLine();
		if (ImGui.button(LuaUiText.text("移除断点", "Remove")) ) {
			if (model.nativeBreakpoints().contains(optionLine)) model.nativeToggleBreakpoint(optionLine);
			ImGui.closeCurrentPopup();
		}
		ImGui.sameLine(); if (ImGui.button(LuaUiText.text("取消", "Cancel")) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
		ImGui.endPopup();
	}

	private void drawCaret(imgui.ImDrawList draw, LuaPersonalizationSettings settings, Row row, int cursor, float x, float y) {
		float cx = x + advance(row, cursor), cw = Math.max(1, settings.cursorWidth);
		int color = NativeUi.rgba(settings.cursorColor);
		if (settings.cursorBlink) {
			float phase = (float) (0.5 + 0.5 * Math.cos(System.nanoTime() / 1.0e9 * Math.PI * 1.6));
			int alpha = (int) (((color >>> 24) & 255) * (0.12f + 0.88f * phase));
			color = (color & 0x00FFFFFF) | (alpha << 24);
		}
		switch (settings.cursorShape) {
			case BAR -> draw.addRectFilled(cx, y, cx + cw, y + lineHeight - 2, color);
			case UNDERLINE -> draw.addRectFilled(cx, y + lineHeight - 3, cx + glyph('M') * settings.cellWidth, y + lineHeight - 1, color);
			case BLOCK -> draw.addRectFilled(cx, y, cx + glyph('M') * settings.cellWidth, y + lineHeight - 2, color & 0x66FFFFFF);
		}
	}

	private int firstMatchEndingAfter(int offset) {
		int low = 0, high = matches.size();
		while (low < high) { int mid = (low + high) >>> 1; if (matches.get(mid).end() <= offset) low = mid + 1; else high = mid; }
		return low;
	}

	private static float minimapRowHeight(float height, int total) { return Math.max(1f, Math.min(3f, height / Math.max(1, total))); }
	private static float minimapHeight(float height, int total) { return Math.min(height, Math.max(1, total) * minimapRowHeight(height, total)); }

	/** A scaled-down picture of the whole file at the right edge: click or drag to scroll. */
	private void drawMinimap(imgui.ImDrawList draw, LuaPersonalizationSettings settings, float left, float top, float right, float bottom, int accent) {
		float x0 = right - MINIMAP_WIDTH, height = bottom - top;
		draw.addRectFilled(x0, top, right, bottom, (NativeUi.rgba(settings.theme.background()) & 0x00FFFFFF) | 0xCC000000);
		draw.addLine(x0, top, x0, bottom, ImGui.getColorU32(ImGuiCol.Border), 1);
		int total = rows.size();
		if (total == 0) return;
		float rowHeight = minimapRowHeight(height, total), mapHeight = minimapHeight(height, total);
		int step = Math.max(1, (int) Math.ceil(total * rowHeight / Math.max(1f, height)));
		int dim = ImGui.getColorU32(ImGuiCol.TextDisabled, .55f), text = ImGui.getColorU32(ImGuiCol.Text, .5f);
		draw.pushClipRect(x0, top, right, bottom, true);
		for (int r = 0; r < total; r += step) {
			var row = rows.get(r);
			int a = row.start;
			while (a < row.end && (source.charAt(a) == ' ' || source.charAt(a) == '\t')) a++;
			if (a >= row.end) continue;
			float indent = (a - row.start) * 1.4f, length = Math.min(MINIMAP_WIDTH - 12 - indent, (row.end - a) * 1.4f);
			if (length <= 0) continue;
			float y = top + r / (float) total * mapHeight;
			draw.addRectFilled(x0 + 6 + indent, y, x0 + 6 + indent + length, y + Math.max(1f, rowHeight - .5f), source.startsWith("--", a) ? dim : text);
		}
		float contentHeight = Math.max(1, total * lineHeight);
		float boxTop = top + scrollY / contentHeight * mapHeight, boxHeight = Math.max(8, viewHeight / contentHeight * mapHeight);
		draw.addRectFilled(x0, boxTop, right, Math.min(bottom, boxTop + boxHeight), (accent & 0x00FFFFFF) | 0x26000000);
		draw.addRect(x0, boxTop, right - 1, Math.min(bottom, boxTop + boxHeight), (accent & 0x00FFFFFF) | 0x88000000);
		draw.popClipRect();
	}

	private void handleMouse(LuaFunctionFolding.State folds, int selection, float top, float bottom, float mx, float my, boolean overMinimap) {
		if (minimap && (overMinimap && hovered && ImGui.isMouseClicked(0) || minimapDragging)) {
			if (ImGui.isMouseClicked(0)) minimapDragging = true;
			if (!ImGui.isMouseDown(0)) minimapDragging = false;
			else {
				float height = bottom - top;
				int total = Math.max(1, rows.size());
				float fraction = Math.clamp((my - top) / Math.max(1, minimapHeight(height, total)), 0f, 1f);
				ImGui.setScrollY(Math.max(0, fraction * total * lineHeight - viewHeight / 2));
			}
			return;
		}
		if (hovered && ImGui.isMouseClicked(0) && !rows.isEmpty()) {
			focused = true; ImGui.setWindowFocus();
			int index = Math.clamp((int) ((my - originY) / lineHeight), 0, Math.max(0, rows.size() - 1));
			var row = rows.get(index);
			if (mx < originX + scrollX + gutter - 3) {
				if (mx >= originX + scrollX + gutter - 23 && folds != null && folds.model(source).atLine(row.line) != null) { model.nativeToggleFold(row.line); layoutKey = ""; }
				else model.nativeToggleBreakpoint(row.line);
			} else {
				int hit = offsetAt(row, mx - (originX + gutter), true);
				boolean ctrl = ImGui.getIO().getKeyCtrl(), alt = ImGui.getIO().getKeyAlt();
				if (ctrl) {
					// Ctrl+click: API documentation for engine functions, otherwise jump to the symbol's declaration.
					String symbol = symbolAt(hit);
					if (LuaApiDocumentation.find(symbol) != null) model.nativeOpenApi(symbol);
					else goToDefinitionAt(hit);
				} else if (alt) extraCarets.add(new Caret(hit, hit));
				else if (ImGui.isMouseDoubleClicked(0)) model.nativeSmartSelect(offsetAt(row, mx - (originX + gutter), false));
				else {
					extraCarets.clear(); snippetStops = new ArrayList<>();
					anchor = ImGui.getIO().getKeyShift() ? selection : hit;
					model.nativeSelect(anchor, hit); dragging = true;
				}
			}
		}
		if (dragging) {
			if (!ImGui.isMouseDown(0)) dragging = false;
			else {
				int index = Math.clamp((int) ((my - originY) / lineHeight), 0, rows.size() - 1);
				model.nativeSelect(anchor, offsetAt(rows.get(index), mx - (originX + gutter), true));
				if (my < top) ImGui.setScrollY(Math.max(0, scrollY - lineHeight)); else if (my > bottom) ImGui.setScrollY(scrollY + lineHeight);
			}
		}
	}

	private void contextMenu() {
		String[][] simple = {{"复制", "Copy"}, {"剪切", "Cut"}, {"粘贴", "Paste"}, {"删除", "Delete"}, {"撤销", "Undo"}};
		for (var item : simple) if (ImGui.menuItem(LuaUiText.text(item[0], item[1]))) context(item[0]);
		ImGui.separator();
		int caretAt = Math.clamp(model.nativeCursor(), 0, source.length()), selectedFrom = Math.clamp(model.nativeAnchor(), 0, source.length()), line = 1;
		for (int i = 0; i < caretAt; i++) if (source.charAt(i) == '\n') line++;
		boolean paused = LuaBreakpointClient.canEvaluate() && model.nativePausedLine() > 0;
		String selected = source.substring(Math.min(caretAt, selectedFrom), Math.max(caretAt, selectedFrom)).strip();
		String watched = !selected.isEmpty() ? selected : dottedNameAt(contextOffset);
		if (ImGui.menuItem(LuaUiText.text("运行到光标", "Run to cursor"), "", false, paused)) LuaBreakpointClient.runTo(model.nativeDocumentName(), line);
		if (ImGui.menuItem(LuaUiText.text("添加到监视", "Add to watch"), "", false, !watched.isEmpty() && watched.length() <= 512 && !watched.contains("\n"))) {
			var store = LuaBreakpointClient.store();
			if (store != null && store.addWatch(watched)) store.save();
		}
		if (ImGui.menuItem(LuaUiText.text("条件断点…", "Breakpoint options…"))) openBreakpointOptions(line);
		ImGui.separator();
		boolean lua = symbols != null;
		if (ImGui.menuItem(LuaUiText.text("转到定义", "Go to definition"), "Ctrl+B", false, lua)) goToDefinition();
		if (ImGui.menuItem(LuaUiText.text("查找引用", "Find references"), "Ctrl+Shift+B", false, lua)) findReferences();
		if (ImGui.menuItem(LuaUiText.text("重命名…", "Rename…"), "F2", false, lua)) requestRename();
		ImGui.separator();
		if (ImGui.menuItem(LuaUiText.text("查找", "Find"), "Ctrl+F")) find();
		if (ImGui.menuItem(LuaUiText.text("替换…", "Replace…"), "Ctrl+H")) findAndReplace();
		if (ImGui.menuItem(LuaUiText.text("转到行…", "Go to line…"), "Ctrl+G")) goToLine();
		if (ImGui.menuItem(LuaUiText.text("选中下一处相同内容", "Add next occurrence"), "Ctrl+D")) addNextOccurrence();
		if (ImGui.menuItem(LuaUiText.text("显示小地图", "Show minimap"), "", minimap)) minimap = !minimap;
		boolean separated = false;
		for (var command : wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi.commands()) {
			if (!command.contextMenu() || extensionRunner == null) continue;
			if (!separated) { ImGui.separator(); separated = true; }
			if (ImGui.menuItem(command.title().getString(), command.shortcutHint())) extensionRunner.accept(command);
		}
	}

	private java.util.function.Consumer<wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi.Command> extensionRunner;
	/** The IDE runs extension commands (it owns the editor context and error handling); the area only lists them in its menu. */
	void onExtensionCommand(java.util.function.Consumer<wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi.Command> runner) { extensionRunner = runner; }

	private void drawPopups() {
		if (!popupRequest.isEmpty()) { ImGui.openPopup(popupRequest + "##editor_popup"); popupRequest = ""; }
		drawBreakpointPopup();
		if (ImGui.beginPopupModal("goto_line##editor_popup", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoTitleBar)) {
			ImGui.textUnformatted(LuaUiText.text("转到行（可加列：行:列）", "Go to line (optionally line:column)"));
			ImGui.setNextItemWidth(260);
			if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
			boolean enter = ImGui.inputText("##line", lineInput, ImGuiInputTextFlags.EnterReturnsTrue);
			if (!popupError.isEmpty()) ImGui.textColored(1f, .45f, .4f, 1f, popupError);
			if (enter || ImGui.button(LuaUiText.text("转到", "Go"))) {
				try {
					String[] parts = lineInput.get().strip().split("[:,]");
					goToLineNumber(Math.max(1, Integer.parseInt(parts[0].strip())));
					if (parts.length > 1) {
						int column = Math.max(1, Integer.parseInt(parts[1].strip())), base = model.nativeCursor();
						goToOffset(Math.min(currentText().length(), base + column - 1));
					}
					ImGui.closeCurrentPopup();
				} catch (NumberFormatException error) { popupError = LuaUiText.text("请输入行号", "Enter a line number"); }
			}
			ImGui.sameLine(); if (ImGui.button(LuaUiText.text("取消", "Cancel")) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
		if (ImGui.beginPopupModal("rename##editor_popup", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoTitleBar)) {
			boolean everywhere = renameSpansFiles && renameAllFiles.get();
			ImGui.textUnformatted(everywhere ? LuaUiText.text("重命名（工作区所有脚本中的引用）", "Rename (references in every workspace script)")
					: LuaUiText.text("重命名（仅本文件内的所有引用）", "Rename (every reference in this file)"));
			ImGui.setNextItemWidth(300);
			if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
			boolean enter = ImGui.inputText("##rename", renameInput, ImGuiInputTextFlags.EnterReturnsTrue);
			if (renameSpansFiles) ImGui.checkbox(LuaUiText.text("同时修改其他脚本（先预览）", "Also change other scripts (preview first)"), renameAllFiles);
			if (!popupError.isEmpty()) ImGui.textColored(1f, .45f, .4f, 1f, popupError);
			if (enter || ImGui.button(LuaUiText.text("重命名", "Rename"))) {
				String name = renameInput.get().strip();
				boolean done = renameSpansFiles && renameAllFiles.get() && crossFile != null
						? LuaSymbolIndex.validName(name) && crossFile.rename(currentText(), model.nativeCursor(), name)
						: renameTo(name);
				if (done) ImGui.closeCurrentPopup();
				else popupError = LuaUiText.text("不是有效的 Lua 标识符", "Not a valid Lua identifier");
			}
			ImGui.sameLine(); if (ImGui.button(LuaUiText.text("取消", "Cancel")) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
		if (ImGui.beginPopupModal("references##editor_popup", ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.AlwaysAutoResize)) {
			ImGui.textUnformatted(LuaUiText.text("引用", "References") + " · " + references.size());
			ImGui.separator();
			ImGui.beginChild("reference_list", 520, Math.min(320, references.size() * (ImGui.getTextLineHeightWithSpacing() + 4) + 12), true);
			for (int i = 0; i < references.size(); i++) {
				var reference = references.get(i);
				if (ImGui.selectable(reference.line() + "   " + reference.preview() + "##ref" + i)) { selectRange(reference.start(), reference.end()); focus(); ImGui.closeCurrentPopup(); }
			}
			ImGui.endChild();
			if (ImGui.button(LuaUiText.text("关闭", "Close")) || ImGui.isKeyPressed(ImGuiKey.Escape)) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
	}

	private void context(String action) {
		switch (action) {
			case "搜索" -> find(); case "删除" -> model.nativeInsert("");
			case "复制" -> editKey(GLFW.GLFW_KEY_C, false); case "剪切" -> editKey(GLFW.GLFW_KEY_X, false);
			case "粘贴" -> editKey(GLFW.GLFW_KEY_V, false); case "撤销" -> editKey(GLFW.GLFW_KEY_Z, false);
			default -> { }
		}
		reveal = true;
	}

	boolean scroll(double horizontal, double vertical) {
		if (!hovered) return false;
		var settings = model.nativePersonalization();
		long window = net.minecraft.client.Minecraft.getInstance().getWindow().handle();
		boolean ctrl = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
		if (ctrl) return false;
		boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
		if (shift && !settings.codeWordWrap) ImGuiIdeRuntime.scroll(vertical * settings.codeWheelSensitivity(), 0);
		else ImGuiIdeRuntime.scroll(horizontal, vertical * settings.codeWheelSensitivity());
		return true;
	}

	void editKey(int key, boolean shift) { model.nativeCodeKey(new KeyEvent(key, 0, GLFW.GLFW_MOD_CONTROL | (shift ? GLFW.GLFW_MOD_SHIFT : 0))); reveal = true; }

	// ---- typing and key handling ---------------------------------------------------------------------------------------------

	/**
	 * Called before a typed character reaches the editor. Handles multiple carets, wrapping a selection in brackets or quotes,
	 * stepping over an auto-inserted closer and auto-closing. Returns true when it consumed the character.
	 */
	boolean preCharacter(int codepoint) {
		if (codepoint > 0xFFFF || Character.isISOControl(codepoint)) return false;
		char typed = (char) codepoint;
		if (!extraCarets.isEmpty()) { editAllCarets(caret -> new String[]{String.valueOf(typed)}); return true; }
		String text = currentText();
		Caret now = primary();
		if (now.hasSelection()) {
			char partner = BracketMatcher.autoClose(typed);
			if (partner == 0) return false;
			// Typing a bracket or quote over a selection wraps it.
			String inner = text.substring(now.start(), now.end());
			model.nativeReplace(now.start(), now.end(), typed + inner + partner);
			model.nativeSelect(now.start() + 1, now.start() + 1 + inner.length());
			return true;
		}
		int caret = now.cursor();
		if (BracketMatcher.stepsOver(text, caret, typed)) { model.nativeSelect(caret + 1, caret + 1); return true; }
		if (BracketMatcher.shouldAutoClose(text, caret, typed)) {
			model.nativeReplace(caret, caret, "" + typed + BracketMatcher.autoClose(typed));
			model.nativeSelect(caret + 1, caret + 1);
			return true;
		}
		return false;
	}

	/**
	 * Applies one text change per caret in a single undo step. A change is {text} (replace the caret's selection) or
	 * {text, start, end} (replace an explicit range); null leaves that caret alone. Carets end up after their inserted text.
	 */
	private void editAllCarets(java.util.function.Function<Caret, String[]> change) {
		String text = currentText();
		List<Caret> carets = allCarets();
		List<int[]> edits = new ArrayList<>(); // start, end, caret index
		List<String> replacements = new ArrayList<>();
		for (int i = 0; i < carets.size(); i++) {
			Caret caret = carets.get(i);
			String[] result = change.apply(caret);
			if (result == null) continue;
			int start = caret.start(), end = caret.end();
			if (result.length > 1) { start = Integer.parseInt(result[1]); end = Integer.parseInt(result[2]); }
			edits.add(new int[]{start, end, i}); replacements.add(result[0]);
		}
		Integer[] order = new Integer[edits.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, Comparator.comparingInt(i -> edits.get(i)[0]));
		StringBuilder out = new StringBuilder();
		int cursor = 0;
		int[] newOffsets = new int[carets.size()];
		Arrays.fill(newOffsets, -1);
		for (int index : order) {
			int[] edit = edits.get(index);
			if (edit[0] < cursor) continue; // overlapping carets: keep the earlier one
			out.append(text, cursor, edit[0]);
			out.append(replacements.get(index));
			newOffsets[edit[2]] = out.length();
			cursor = edit[1];
		}
		out.append(text, cursor, text.length());
		model.nativeReplace(0, text.length(), out.toString());
		extraCarets.clear();
		for (int i = 1; i < carets.size(); i++) if (newOffsets[i] >= 0 && newOffsets[i] != newOffsets[0]) extraCarets.add(new Caret(newOffsets[i], newOffsets[i]));
		int main = newOffsets[0] >= 0 ? newOffsets[0] : Math.min(out.length(), model.nativeCursor());
		model.nativeSelect(main, main); reveal = true;
	}

	/** Tab: expand a snippet trigger, or move to the next placeholder of the active snippet. Shift+Tab goes back. */
	boolean tab(boolean shift) {
		if (!snippetStops.isEmpty()) {
			int length = currentText().length(), delta = length - snippetTextLength;
			// Text typed inside the active placeholder shifts every later placeholder.
			if (delta != 0) for (int i = snippetIndex + 1; i < snippetStops.size(); i++) {
				var stop = snippetStops.get(i);
				snippetStops.set(i, new int[]{stop[0] + delta, stop[1] + delta});
			}
			snippetTextLength = length;
			int next = Math.max(0, snippetIndex + (shift ? -1 : 1));
			if (next >= snippetStops.size()) { snippetStops = new ArrayList<>(); return false; }
			snippetIndex = next;
			var stop = snippetStops.get(next);
			selectRange(stop[0], stop[1]);
			if (next == snippetStops.size() - 1) snippetStops = new ArrayList<>(); // $0 reached: the session is over
			return true;
		}
		if (shift || !extraCarets.isEmpty()) return false;
		Caret now = primary();
		if (now.hasSelection()) return false;
		String text = currentText();
		String word = LuaSnippets.wordBefore(text, now.cursor());
		var snippet = word.isEmpty() ? null : LuaSnippets.find(word);
		if (snippet == null) return false;
		int start = now.cursor() - word.length();
		var expansion = LuaSnippets.expand(snippet, LuaSnippets.indentAt(text, start));
		model.nativeReplace(start, now.cursor(), expansion.text());
		snippetTextLength = currentText().length();
		List<int[]> stops = new ArrayList<>();
		for (var stop : expansion.stops()) stops.add(new int[]{start + stop.start(), start + stop.end()});
		selectRange(stops.get(0)[0], stops.get(0)[1]);
		snippetStops = stops.size() > 1 ? stops : new ArrayList<>(); snippetIndex = 0;
		return true;
	}

	boolean key(KeyEvent event) {
		if (!focused) return false;
		int key = event.key();
		if (key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER) return false;
		boolean ctrl = event.hasControlDown(), shift = event.hasShiftDown(), alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
		if (ctrl && !shift && key == GLFW.GLFW_KEY_F) { find(); return true; }
		if (ctrl && !shift && key == GLFW.GLFW_KEY_H) { findAndReplace(); return true; }
		if (ctrl && !shift && key == GLFW.GLFW_KEY_G) { goToLine(); return true; }
		if (ctrl && !shift && key == GLFW.GLFW_KEY_D) { addNextOccurrence(); return true; }
		if (ctrl && !shift && key == GLFW.GLFW_KEY_B) { goToDefinition(); return true; }
		if (ctrl && shift && key == GLFW.GLFW_KEY_B) { findReferences(); return true; }
		if (!ctrl && !alt && key == GLFW.GLFW_KEY_F2) { requestRename(); return true; }
		if (!ctrl && !alt && key == GLFW.GLFW_KEY_TAB && tab(shift)) return true;
		if (!ctrl && !alt && (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
			if (!extraCarets.isEmpty()) { editExtraKey(key); return true; }
			if (key == GLFW.GLFW_KEY_BACKSPACE && !primary().hasSelection() && BracketMatcher.deletesPair(currentText(), model.nativeCursor())) {
				int caret = model.nativeCursor();
				model.nativeReplace(caret - 1, caret + 1, ""); model.nativeSelect(caret - 1, caret - 1);
				return true;
			}
		}
		if (ctrl && key == GLFW.GLFW_KEY_V && !extraCarets.isEmpty()) {
			String clipboard = ImGui.getClipboardText();
			if (clipboard != null && !clipboard.isEmpty()) { editAllCarets(caret -> new String[]{clipboard}); return true; }
		}
		if (!extraCarets.isEmpty() && !(ctrl && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_A))) extraCarets.clear();
		if (!snippetStops.isEmpty() && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) snippetStops = new ArrayList<>();
		if (!ctrl && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END || key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
			if (rows.isEmpty()) return true;
			int cursor = model.nativeCursor(), line = rowAtOffset(cursor), index = line;
			var row = rows.get(line);
			if (key == GLFW.GLFW_KEY_UP) index--;
			if (key == GLFW.GLFW_KEY_DOWN) index++;
			if (key == GLFW.GLFW_KEY_PAGE_UP) index -= (int) (viewHeight / lineHeight);
			if (key == GLFW.GLFW_KEY_PAGE_DOWN) index += (int) (viewHeight / lineHeight);
			index = Math.clamp(index, 0, rows.size() - 1);
			int target = key == GLFW.GLFW_KEY_HOME ? row.start : key == GLFW.GLFW_KEY_END ? row.end : offsetAt(rows.get(index), advance(row, cursor), true);
			model.nativeSelect(event.hasShiftDown() ? model.nativeAnchor() : target, target); reveal = true; return true;
		}
		model.nativeCodeKey(event); reveal = true; return true;
	}

	private void editExtraKey(int key) {
		String text = currentText();
		editAllCarets(caret -> {
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (caret.hasSelection()) return new String[]{""};
				return caret.cursor() == 0 ? null : new String[]{"", String.valueOf(caret.cursor() - 1), String.valueOf(caret.cursor())};
			}
			if (key == GLFW.GLFW_KEY_DELETE) {
				if (caret.hasSelection()) return new String[]{""};
				return caret.cursor() >= text.length() ? null : new String[]{"", String.valueOf(caret.cursor()), String.valueOf(caret.cursor() + 1)};
			}
			return new String[]{"\n" + LuaSnippets.indentAt(text, caret.start())};
		});
	}

	String symbolAt(int offset) {
		int start = Math.clamp(offset, 0, source.length()), end = start;
		while (start > 0 && symbolChar(source.charAt(start - 1))) start--;
		while (end < source.length() && symbolChar(source.charAt(end))) end++;
		return source.substring(start, end);
	}
	private boolean symbolChar(char c) { return Character.isJavaIdentifierPart(c) || c == '.' || c == ':'; }

	// ---- text layout --------------------------------------------------------------------------------------------------------

	private void layout(float width, float cell, LuaFunctionFolding.State folds) {
		var next = new ArrayList<Row>();
		int start = 0, line = 1;
		while (start <= source.length()) {
			int end = source.indexOf('\n', start);
			if (end < 0) end = source.length();
			if (folds == null || !folds.hidden(start)) {
				int part = start;
				float x = 0;
				var advances = new ArrayList<Float>();
				advances.add(0f);
				for (int i = start; i < end; ) {
					int cp = source.codePointAt(i), count = Character.charCount(cp);
					float advance = cp == '\t' ? glyph(' ') * 4 * cell - x % (glyph(' ') * 4 * cell) : glyph(cp) * cell;
					if (x + advance > width && i > part) { next.add(new Row(part, i, line, floats(advances))); part = i; x = 0; advances.clear(); advances.add(0f); }
					for (int c = 1; c < count; c++) advances.add(x);
					x += advance; advances.add(x); i += count;
				}
				next.add(new Row(part, end, line, floats(advances)));
			}
			if (end == source.length()) break;
			start = end + 1; line++;
		}
		rows = next.isEmpty() ? List.of(new Row(0, 0, 1, new float[]{0})) : next;
	}
	private float[] floats(List<Float> list) { float[] values = new float[list.size()]; for (int i = 0; i < values.length; i++) values[i] = list.get(i); return values; }
	private float glyph(int cp) { return glyphs.computeIfAbsent(cp, c -> ImGui.calcTextSize(new String(Character.toChars(c))).x); }
	private float advance(Row row, int offset) { return row.advances[Math.clamp(offset - row.start, 0, row.advances.length - 1)]; }
	private int rowAtOffset(int offset) {
		int low = 0, high = rows.size() - 1;
		while (low < high) { int mid = (low + high + 1) >>> 1; if (rows.get(mid).start <= offset) low = mid; else high = mid - 1; }
		return low;
	}
	private int offsetAt(Row row, float x, boolean nearest) {
		int low = 0, high = row.advances.length - 1;
		while (low < high) {
			int mid = (low + high) >>> 1;
			float edge = nearest ? (row.advances[mid] + row.advances[mid + 1]) * .5f : row.advances[mid + 1];
			if (edge < x) low = mid + 1; else high = mid;
		}
		int result = Math.min(row.end, row.start + low);
		if (result > row.start && result < source.length() && Character.isLowSurrogate(source.charAt(result))) result--;
		return result;
	}
	private void rect(imgui.ImDrawList draw, Row row, int a, int b, float x, float y, int color) {
		if (a < b) draw.addRectFilled(x + advance(row, a), y, x + advance(row, b), y + lineHeight, color);
	}
}
