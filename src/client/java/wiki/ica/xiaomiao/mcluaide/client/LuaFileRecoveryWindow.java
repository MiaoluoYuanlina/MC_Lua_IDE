package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** Content only: the shared IDE host owns movement, resizing, docking and animation. */
final class LuaFileRecoveryWindow implements McLuaIdeWindowApi.Content {
	static final String ID = "mc_lua_ide:file_recovery";
	private final McLuaIdeWindowApi.Context context;
	private final LuaFileRecovery recovery;
	private final Consumer<LuaFileRecovery.Entry> restore;
	private int width = 430, height = 250, scroll;
	private String selected = "", confirmDelete = "";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	LuaFileRecoveryWindow(McLuaIdeWindowApi.Context context, LuaFileRecovery recovery, Consumer<LuaFileRecovery.Entry> restore) {
		this.context = context; this.recovery = recovery; this.restore = restore;
	}
	@Override public void resize(int width, int height) { this.width = width; this.height = height; }
	private int bottom() { return height - 48; }
	private List<LuaFileRecovery.Entry> rows() { return recovery.entries(); }
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		var entries = rows();
		scroll = Math.clamp(scroll, 0, Math.max(0, entries.size() * 34 - Math.max(1, bottom() - 30)));
		graphics.text(context.font(), context.font().plainSubstrByWidth("恢复到编辑标签；保存前不会覆盖原文件", width - 6), 3, 4, 0xFF9EC7EE);
		graphics.text(context.font(), context.font().plainSubstrByWidth("共 " + entries.size() + " 份草稿 · 点击选择，双击恢复", width - 6), 3, 17, 0xFF92A9C4);
		graphics.enableScissor(0, 30, width, bottom());
		if (entries.isEmpty()) graphics.text(context.font(), "没有可恢复的未保存文件", 5, 36, 0xFF92A9C4);
		for (int index = 0; index < entries.size(); index++) {
			var entry = entries.get(index); int y = 30 + index * 34 - scroll;
			boolean hover = mouseY >= y && mouseY < y + 32 && mouseY >= 30 && mouseY < bottom();
			graphics.fill(1, y, width - 1, y + 32, entry.id().equals(selected) ? 0xFF365578 : hover ? 0xFF293D53 : 0xFF1C2939);
			graphics.text(context.font(), context.font().plainSubstrByWidth(entry.name(), width - 10), 5, y + 4, 0xFFEAF2FF);
			graphics.text(context.font(), entry.type() + (entry.image() ? " · 图片图层" : "") + " · " + TIME.format(Instant.ofEpochMilli(entry.updated())), 5, y + 18, 0xFF92A9C4);
		}
		graphics.disableScissor();
		String message = recovery.failure().isEmpty() ? "草稿仅保存在当前客户端；可恢复 Lua、JSON、函数和图片" : recovery.failure();
		graphics.text(context.font(), context.font().plainSubstrByWidth(message, width - 6), 3, bottom() + 4,
				recovery.failure().isEmpty() ? 0xFF92A9C4 : 0xFFFF8793);
		int cell = (width - 6) / 3;
		button(graphics, "恢复选中", 0, cell, mouseX, mouseY);
		button(graphics, "恢复全部", cell + 3, cell, mouseX, mouseY);
		button(graphics, selected.equals(confirmDelete) && !selected.isEmpty() ? "确认删除草稿" : "删除草稿", (cell + 3) * 2, width - (cell + 3) * 2, mouseX, mouseY);
	}
	private void button(GuiGraphicsExtractor graphics, String label, int x, int w, int mx, int my) {
		int y = height - 25;
		graphics.fill(x, y, x + w, y + 22, mx >= x && mx < x + w && my >= y && my < y + 22 ? 0xFF3E648A : 0xFF2A435E);
		graphics.centeredText(context.font(), context.font().plainSubstrByWidth(label, Math.max(1, w - 4)), x + w / 2, y + 7, 0xFFEAF2FF);
	}
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return false;
		var entries = rows();
		if (event.y() >= 30 && event.y() < bottom()) {
			int index = (int) (event.y() - 30 + scroll) / 34;
			if (index >= 0 && index < entries.size()) {
				selected = entries.get(index).id(); confirmDelete = "";
				if (doubleClick) restore.accept(entries.get(index));
			}
		} else if (event.y() >= height - 25 && event.y() < height - 3) {
			int cell = (width - 6) / 3;
			if (event.x() < cell) restoreSelected();
			else if (event.x() < 2 * cell + 3) entries.forEach(restore);
			else if (!selected.isEmpty()) {
				if (selected.equals(confirmDelete)) { recovery.discard(selected); selected = confirmDelete = ""; }
				else confirmDelete = selected;
			}
		}
		return true;
	}
	private void restoreSelected() { rows().stream().filter(e -> e.id().equals(selected)).findFirst().ifPresent(restore); }
	@Override public boolean keyPressed(KeyEvent event) {
		var entries = rows();
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) { restoreSelected(); return true; }
		if (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN) {
			if (entries.isEmpty()) return true;
			int index = -1;
			for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(selected)) index = i;
			index = Math.clamp(index + (event.key() == InputConstants.KEY_UP ? -1 : 1), 0, entries.size() - 1);
			selected = entries.get(index).id(); confirmDelete = "";
			if (index * 34 < scroll) scroll = index * 34;
			if ((index + 1) * 34 > scroll + bottom() - 30) scroll = Math.max(0, (index + 1) * 34 - (bottom() - 30));
			return true;
		}
		return false;
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll = Math.max(0, scroll - (int) Math.round(vertical * 34)); return true;
	}
}
