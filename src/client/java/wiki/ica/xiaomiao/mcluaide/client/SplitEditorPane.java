package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;

/**
 * A second, fully editable code view that can be docked beside the main editor: another file, or the same file at another place.
 * It has its own hidden editor model (caret, selection, undo), and both views share the document text: whatever one view changes
 * is picked up by the other on its next frame, keeping that view's caret where it was.
 */
final class SplitEditorPane {
	static final String ID = "NATIVE_SPLIT";
	private final ImGuiIdeScreen owner;
	private LuaIdeScreen model;
	private NativeCodeArea area;
	private ImGuiIdeDocument document;
	private String documentKey = "", lastSource;

	SplitEditorPane(ImGuiIdeScreen owner) { this.owner = owner; }

	private void ensure() {
		if (model != null) return;
		var client = Minecraft.getInstance();
		model = new LuaIdeScreen(null, "", owner);
		model.init(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
		area = new NativeCodeArea(model);
	}

	/** The document shown here, or null. */
	ImGuiIdeDocument document() { return document; }
	boolean focused() { return area != null && area.focused(); }
	void beginFrame() { if (area != null) area.beginFrame(); }
	NativeCodeArea area() { ensure(); return area; }

	void draw(List<ImGuiIdeDocument> open, ImGuiIdeDocument current) {
		ensure();
		List<ImGuiIdeDocument> text = open.stream().filter(doc -> !doc.binary()).toList();
		if (document == null || !text.contains(document)) document = current != null && !current.binary() ? current : text.isEmpty() ? null : text.getFirst();
		if (document == null) { ImGui.textDisabled(LuaUiText.text("打开一个文本文件后，可以在这里并排查看和编辑。", "Open a text file to view and edit it here, side by side.")); return; }
		ImGui.setNextItemWidth(Math.min(320, ImGui.getContentRegionAvailX()));
		if (ImGui.beginCombo("##split_document", document.filename())) {
			for (int i = 0; i < text.size(); i++) if (ImGui.selectable(text.get(i).filename() + "##split" + i, text.get(i) == document)) document = text.get(i);
			ImGui.endCombo();
		}
		ImGui.sameLine();
		ImGui.textDisabled(document.dirty() ? LuaUiText.text("● 未保存 · Ctrl+S 保存", "● unsaved · Ctrl+S saves") : document.path);
		syncIn();
		ImGuiIdeRuntime.pushEditorFont(ImGui.getFontSize() * model.nativePersonalization().fontPercent / 100f);
		try { area.render(document); } finally { ImGui.popFont(); }
		syncOut();
	}

	/** Gives this view's model the document's current text (opening it, or after the other view changed it). */
	private void syncIn() {
		if (!document.key().equals(documentKey) || !document.source.equals(lastSource)) {
			model.nativeBind(document);
			documentKey = document.key(); lastSource = document.source;
		}
	}

	/** Publishes this view's edits to the shared document, where the main editor picks them up. */
	private void syncOut() {
		var snapshot = model.nativeSnapshot();
		if (snapshot == null || document == null || !snapshot.name().equals(document.path)) return;
		if (!snapshot.source().equals(lastSource)) { document.update(snapshot.source()); lastSource = document.source; }
	}

	boolean key(KeyEvent event) {
		if (document == null) return false;
		syncIn();
		boolean handled = area.key(event);
		syncOut();
		return handled;
	}

	boolean character(CharacterEvent event) {
		if (document == null) return false;
		syncIn();
		if (!area.preCharacter(event.codepoint())) model.nativeCodeCharacter(event);
		syncOut();
		return true;
	}

	void close() { if (model != null) model.nativeDispose(); }
}
