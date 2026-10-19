package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.List;

/** Mirrors only the execution output already delivered to this administrator client. */
final class LuaLogHud {
	private static boolean enabled;
	private static final ArrayDeque<Line> lines = new ArrayDeque<>();
	private LuaLogHud() { }
	static boolean enabled() { return enabled; }
	static void setEnabled(boolean value) { enabled = value; }
	static synchronized void disconnected() { enabled = false; lines.clear(); }
	static synchronized List<Line> snapshot() { return List.copyOf(lines); }
	static synchronized void append(String script, String text) {
		for (String line : text.split("\\R", -1)) {
			int color = line.startsWith("✗ ") ? 0xFFFF929F
					: line.startsWith("✓ ") ? 0xFF9DDFA8
					: line.startsWith("⚠") ? 0xFFFFCB6B
					: line.startsWith("■ ") ? 0xFFFFCB82 : 0xFFE2E6EC;
			lines.addLast(new Line(script.length() > 120 ? script.substring(0, 120) : script,
					line.length() > 2048 ? line.substring(0, 2048) + "…" : line, color));
			while (lines.size() > 200) lines.removeFirst();
		}
	}
	static void render(GuiGraphicsExtractor graphics, DeltaTracker ignored) {
		Minecraft client = Minecraft.getInstance();
		if (!enabled || client.player == null || client.options.hideGui || client.screen != null) return;
		renderPanel(graphics, LuaHudLayout.current().bounds(LuaHudLayout.Panel.LOGS,
				graphics.guiWidth(), graphics.guiHeight()), false);
	}
	static void renderPanel(GuiGraphicsExtractor graphics, LuaHudLayout.Rect bounds, boolean preview) {
		graphics.nextStratum();
		graphics.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
		try { renderContents(graphics, bounds, preview); } finally { graphics.disableScissor(); }
	}
	private static void renderContents(GuiGraphicsExtractor graphics, LuaHudLayout.Rect bounds, boolean preview) {
		Minecraft client = Minecraft.getInstance();
		int width = bounds.width(), capacity = Math.max(1, (bounds.height() - (preview ? 38 : 26)) / 11);
		List<Line> snapshot = snapshot();
		if (preview && snapshot.isEmpty()) snapshot = List.of(
				new Line("示例", "脚本输出预览", 0xFFE2E6EC),
				new Line("示例", "⚠ 黄色警告", 0xFFFFCB6B), new Line("示例", "✗ 运行错误", 0xFFFF929F));
		int count = Math.min(capacity, snapshot.size());
		int height = bounds.height(), x = bounds.x(), y = bounds.y();
		graphics.fill(x, y, x + width, y + height, LuaHudLayout.current().backgroundColor(LuaHudLayout.Panel.LOGS));
		graphics.fill(x, y, x + width, y + 2, 0xFF8FA7FF);
		graphics.text(client.font, client.font.plainSubstrByWidth(preview && !enabled
				? "Lua 日志 · 未开启（仅预览）" : "Lua 日志 · F12 设置", width - 14), x + 7, y + 7, 0xFFE8ECFF);
		if (count == 0) graphics.text(client.font, "等待脚本输出…", x + 7, y + 22, 0xFFAAB3C4);
		for (int index = 0; index < count; index++) {
			Line line = snapshot.get(snapshot.size() - count + index);
			graphics.text(client.font, client.font.plainSubstrByWidth("[" + line.script() + "] " + line.text(), width - 14),
					x + 7, y + 22 + index * 11, line.color());
		}
	}
	record Line(String script, String text, int color) { }
}
