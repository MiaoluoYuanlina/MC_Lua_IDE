package wiki.ica.xiaomiao.mcluaide.client;

import imgui.extension.texteditor.TextEditor;
import imgui.extension.texteditor.TextEditorLanguage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** UI-independent source/revision ownership; native widgets are lazy and destroyed by their owning context. */
final class ImGuiIdeDocument {
    enum Kind { LUA, DATAPACK, RESOURCEPACK }
    final Kind kind;
    String path;
    String source, saved;
    long revision;
    boolean saving;
    CompletableFuture<Void> saveTask;
    TextEditor editor;
    private long undoIndex;
    private boolean replaceEditor;
    private long analyzeAfter;
    private CompletableFuture<LuaCodeAnalyzer.Analysis> pendingAnalysis;
    private long analyzedRevision = -1, requestedRevision;
    LuaCodeAnalyzer.Analysis analysis = new LuaCodeAnalyzer.Analysis(List.of());
    int restoreLine, restoreColumn;
    String recoveryId;
    long recoveryRevision = -1;
    long mcpRevision;
    String pendingMcp;
    int restoreOffset;
    LuaIdeSession.Document restoredMetadata;
    interface Backend {
        boolean capture(); int cursor(); void insert(String text); void replace(int start, int end, String text); boolean dirty();
    }
    Backend backend;

    ImGuiIdeDocument(Kind kind, String path, String source) {
        this.kind = kind; this.path = path; this.source = source; this.saved = source;
    }
    String key() { return kind.name() + ":" + path; }
    String filename() { return path.substring(path.lastIndexOf('/') + 1) + (kind == Kind.LUA ? ".lua" : ""); }
    boolean dirty() { return !source.equals(saved) || backend != null && backend.dirty(); }
    boolean binary() { return kind != Kind.LUA && (path.endsWith(".png") || path.endsWith(".ogg") || path.endsWith(".nbt")); }
    boolean lua() { return kind == Kind.LUA; }
    boolean json() { return path.endsWith(".json") || path.endsWith(".mcmeta"); }
    TextEditor widget() {
        if (editor == null) {
            editor = new TextEditor();
            if (lua()) editor.setLanguage(TextEditorLanguage.Lua());
            else if (json()) editor.setLanguage(TextEditorLanguage.Json());
            else if (path.endsWith(".md")) editor.setLanguage(TextEditorLanguage.Markdown());
            editor.setTabSize(4); editor.setInsertSpacesOnTabs(true);
            editor.setAutoIndentEnabled(true); editor.setShowWhitespacesEnabled(false);
            editor.setShowLineNumbersEnabled(true); editor.setShowMatchingBrackets(true);
            editor.setText(source); editor.setCursor(restoreLine, restoreColumn);
            undoIndex = editor.getUndoIndex();
        }
        if (replaceEditor) {
            editor.setText(source); editor.setCursor(restoreLine, restoreColumn);
            undoIndex = editor.getUndoIndex(); replaceEditor = false;
        }
        return editor;
    }
    boolean capture() {
        if (backend != null) return backend.capture();
        if (editor == null || replaceEditor || undoIndex == editor.getUndoIndex()) return false;
        undoIndex = editor.getUndoIndex();
        update(editor.getText());
        return true;
    }
    void update(String next) {
        if (source.equals(next)) return;
        source = next; revision++; analyzeAfter = System.nanoTime() + 300_000_000L;
    }
    void external(String next) {
        if (editor != null) {
            var cursor = editor.getMainCursorPosition(); restoreLine = cursor.line; restoreColumn = cursor.column;
        }
        update(next); replaceEditor = true;
    }
    int cursorOffset() {
        if (backend != null) return backend.cursor();
        if (editor == null) return 0;
        var c = editor.getMainCursorPosition();
        // Ask the native editor: its visual/UTF-8 columns are not Java UTF-16 indices.
        return Math.min(source.length(), editor.getSectionText(0, 0, c.line, c.column).length());
    }
    void insert(String text) {
        if (backend != null) { backend.insert(text); return; }
        widget().replaceTextInCurrentCursor(text); capture();
    }
    void replace(int start, int end, String text) {
        if (backend != null) { backend.replace(start, end, text); return; }
        var a = coordinates(start); var b = coordinates(end);
        widget().selectRegion(a[0], a[1], b[0], b[1]); insert(text);
    }
    int[] coordinates(int requested) {
        int offset = Math.clamp(requested, 0, source.length());
        int lineStart = offset == 0 ? 0 : source.lastIndexOf('\n', offset - 1) + 1;
        int line = (int)source.substring(0, lineStart).chars().filter(c -> c == '\n').count();
        int target = offset - lineStart, low = 0;
        int high = source.substring(lineStart, offset).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // Works with both glyph-index and byte-index editor versions, including tabs and non-BMP text.
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (widget().getSectionText(line, 0, line, mid).length() < target) low = mid + 1; else high = mid;
        }
        return new int[]{line, low};
    }
    void analyze() {
        if (pendingAnalysis != null && pendingAnalysis.isDone()) {
            try {
                var result = pendingAnalysis.join();
                if (requestedRevision == revision) {
                    analysis = result; analyzedRevision = revision;
                    if (editor != null) {
                        editor.clearMarkers();
                        for (var d : result.diagnostics()) editor.addMarker(Math.max(0, d.line() - 1),
                                d.severity() == LuaCodeAnalyzer.Severity.ERROR ? 0xff6767ef : 0xff52cbee,
                                0x222626ef, d.message(), "");
                    }
                }
            } catch (RuntimeException ignored) { analyzedRevision = requestedRevision; }
            pendingAnalysis = null;
        }
        if (pendingAnalysis == null && analyzedRevision != revision && System.nanoTime() >= analyzeAfter) {
            String snapshot = source; requestedRevision = revision;
            pendingAnalysis = CompletableFuture.supplyAsync(() -> lua() ? LuaCodeAnalyzer.analyze(path, snapshot)
                    : json() ? JsonCodeAnalyzer.analyze(snapshot) : new LuaCodeAnalyzer.Analysis(List.of()));
        }
    }
    void destroy() { if (editor != null) { editor.destroy(); editor = null; } }
}
