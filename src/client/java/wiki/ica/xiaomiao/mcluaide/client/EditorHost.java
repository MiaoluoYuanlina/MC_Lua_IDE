package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** What the editor tool panels (outline, search, compare, palette) need from the IDE screen that hosts them. */
interface EditorHost {
	/** The document in the active editor tab, or null. */
	ImGuiIdeDocument currentDocument();
	List<ImGuiIdeDocument> openDocuments();
	NativeCodeArea codeArea();
	/** The cached file tree of a workspace area (scripts, datapacks or resource packs). */
	List<ScriptManager.ScriptEntry> tree(ImGuiIdeDocument.Kind kind);
	/** Opens a file in the editor and selects the given range once it is loaded (start == end places the caret). */
	void openAt(ImGuiIdeDocument.Kind kind, String path, int start, int end);
	/** Runs {@code success} on the client thread when the future completes, ignoring results from a connection that has since closed. */
	<T> void deliver(CompletableFuture<T> future, Consumer<T> success, Consumer<String> failure);
	/** Reads a workspace file (unsaved edits are not included). */
	CompletableFuture<String> read(ImGuiIdeDocument.Kind kind, String path);
	/** Writes a workspace file and updates the open editor tab for it, if any. */
	CompletableFuture<Void> write(ImGuiIdeDocument.Kind kind, String path, String text);
	void notice(String message);
	/** Opens one of the tool panes by id (see ImGuiToolWorkspace). */
	void openTool(String id);
	/** Inserts text at the caret of the active editor tab (replacing the selection); false when no editable file is open. */
	boolean insertText(String text);
}
