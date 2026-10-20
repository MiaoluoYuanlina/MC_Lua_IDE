package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Non-interactive in-game HUD; all values arrive as administrator-authorized snapshots. */
final class LuaDebugHud {
	private static boolean variablesEnabled;
	private static boolean ideVariablesEnabled;
	private static boolean requestPending;
	private static long nextRequestNanos;
	private static String script = "";
	private static String status = LuaUiText.text("当前无运行中的 Lua 脚本");
	private static int omitted;
	private static List<Variable> variables = List.of();
	private static List<DisplayRow> rows = List.of();
	private static int scrollOffset;
	private static int visibleRows = 16;

	private LuaDebugHud() { }

	static boolean variablesEnabled() { return variablesEnabled; }
	static String status() { return status; }
	static String script() { return script; }
	static int variableCount() { return variables.size(); }
	static int omittedCount() { return omitted; }
	static List<DisplayRow> displayRows() { return rows; }
	static void refresh() { nextRequestNanos = 0; }

	static void setIdeVariablesEnabled(boolean enabled) {
		ideVariablesEnabled = enabled;
		if (enabled) {
			nextRequestNanos = 0;
			if (rows.isEmpty()) status = LuaUiText.text("正在读取服务端 Lua 变量…");
		} else if (!variablesEnabled) clearSnapshot();
	}

	static void setVariablesEnabled(boolean enabled) {
		variablesEnabled = enabled;
		if (enabled) {
			status = LuaUiText.text("正在读取服务端 Lua 变量…");
			nextRequestNanos = 0;
		} else if (!ideVariablesEnabled) clearSnapshot();
	}

	static void disconnected() {
		variablesEnabled = false;
		ideVariablesEnabled = false;
		requestPending = false;
		clearSnapshot();
	}

	private static void clearSnapshot() {
		script = "";
		status = LuaUiText.text("当前无运行中的 Lua 脚本");
		variables = List.of();
		rows = List.of();
		omitted = 0;
		scrollOffset = 0;
	}

	static void scroll(int direction) {
		if (!variablesEnabled || rows.size() <= visibleRows) return;
		scrollOffset = Math.clamp(scrollOffset + direction, 0, rows.size() - visibleRows);
	}

	static void tick(Minecraft client) {
		if ((!variablesEnabled && !ideVariablesEnabled) || requestPending || client.player == null
				|| !ScriptManager.isServerAvailable()) return;
		long now = System.nanoTime();
		if (now < nextRequestNanos) return;
		nextRequestNanos = now + TimeUnit.MILLISECONDS.toNanos(650);
		requestPending = true;
		ScriptManager.debugVariables().whenComplete((data, error) -> client.execute(() -> {
			requestPending = false;
			if (!variablesEnabled && !ideVariablesEnabled) return;
			if (error != null) {
				clearSnapshot();
				status = "变量读取失败：" + ScriptManager.message(error);
				variablesEnabled = false;
				ideVariablesEnabled = false;
				return;
			}
			try {
				readSnapshot(data);
			} catch (RuntimeException malformed) {
				clearSnapshot();
				status = "服务端返回了无效的调试数据";
				variablesEnabled = false;
				ideVariablesEnabled = false;
			}
		}));
	}

	private static void readSnapshot(String data) {
		JsonObject snapshot = JsonParser.parseString(data).getAsJsonObject();
		if (!snapshot.get("running").getAsBoolean()) {
			clearSnapshot();
			return;
		}
		String nextScript = snapshot.get("script").getAsString();
		if (!nextScript.equals(script)) scrollOffset = 0;
		script = nextScript;
		if (snapshot.has("debugEnabled") && !snapshot.get("debugEnabled").getAsBoolean()) {
			variables = List.of();
			rows = List.of();
			omitted = 0;
			scrollOffset = 0;
			status = "普通运行未启用调试；请使用 /lua testrun";
			return;
		}
		omitted = Math.max(0, snapshot.get("omitted").getAsInt());
		List<Variable> next = new ArrayList<>();
		for (JsonElement element : snapshot.getAsJsonArray("variables")) {
			JsonObject variable = element.getAsJsonObject();
			next.add(new Variable(variable.get("scope").getAsString(),
					variable.get("name").getAsString(), variable.get("value").getAsString(),
					variable.has("type") ? variable.get("type").getAsString() : "unknown", readChildren(variable)));
		}
		variables = List.copyOf(next);
		rows = treeRows(next);
		scrollOffset = Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows));
		status = next.isEmpty() ? LuaUiText.text("脚本正在运行，等待变量采样…") : "";
	}

	private static List<TreeNode> readChildren(JsonObject parent) {
		if (!parent.has("children")) return List.of();
		List<TreeNode> children = new ArrayList<>();
		for (JsonElement element : parent.getAsJsonArray("children")) {
			JsonObject child = element.getAsJsonObject();
			children.add(new TreeNode(child.get("name").getAsString(), child.get("value").getAsString(),
					child.has("type") ? child.get("type").getAsString() : "unknown", readChildren(child)));
		}
		return List.copyOf(children);
	}

	private static List<DisplayRow> treeRows(List<Variable> variables) {
		List<DisplayRow> result = new ArrayList<>();
		Map<String, List<Variable>> locals = new LinkedHashMap<>();
		List<Variable> globals = new ArrayList<>();
		for (Variable variable : variables) {
			if (variable.scope().equals(LuaUiText.text("全局"))) globals.add(variable);
			else locals.computeIfAbsent(variable.scope(), ignored -> new ArrayList<>()).add(variable);
		}
		if (!locals.isEmpty()) {
			result.add(new DisplayRow(LuaUiText.text("局部与闭包变量"), "", "", 0, true));
			for (Map.Entry<String, List<Variable>> group : locals.entrySet()) {
				result.add(new DisplayRow(group.getKey(), "", "", 1, true));
				for (Variable variable : group.getValue()) {
					result.add(new DisplayRow(variable.name() + " = ", variable.value(), variable.type(), 2,
							!variable.children().isEmpty()));
					appendChildren(result, variable.children(), 3);
				}
			}
		}
		if (!globals.isEmpty()) {
			result.add(new DisplayRow(LuaUiText.text("全局变量"), "", "", 0, true));
			for (Variable variable : globals)
				result.add(new DisplayRow(variable.name() + " = ", variable.value(), variable.type(), 1, false));
		}
		return List.copyOf(result);
	}

	private static void appendChildren(List<DisplayRow> rows, List<TreeNode> children, int depth) {
		for (TreeNode child : children) {
			rows.add(new DisplayRow(child.name() + " = ", child.value(), child.type(), depth,
					!child.children().isEmpty()));
			appendChildren(rows, child.children(), depth + 1);
		}
	}

	static void render(GuiGraphicsExtractor graphics, DeltaTracker ignored) {
		Minecraft client = Minecraft.getInstance();
		if (!variablesEnabled || client.player == null || client.options.hideGui
				|| client.screen instanceof LuaIdeScreen || client.screen instanceof ImGuiIdeScreen || client.screen instanceof LuaHudLayoutScreen) return;
		renderPanel(graphics, LuaHudLayout.current().bounds(LuaHudLayout.Panel.VARIABLES,
				graphics.guiWidth(), graphics.guiHeight()), false);
	}

	static void renderPanel(GuiGraphicsExtractor graphics, LuaHudLayout.Rect bounds, boolean preview) {
		graphics.nextStratum();
		Font font = Minecraft.getInstance().font;
		graphics.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
		try { renderContents(graphics, font, bounds, preview); } finally { graphics.disableScissor(); }
	}

	private static void renderContents(GuiGraphicsExtractor graphics, Font font, LuaHudLayout.Rect bounds, boolean preview) {
		int capacity = Math.max(1, (bounds.height() - (preview ? 48 : 36)) / 11);
		if (!preview) { visibleRows = capacity; scrollOffset = Math.min(scrollOffset, Math.max(0, rows.size() - capacity)); }
		List<DisplayRow> shownRows = preview && rows.isEmpty() ? List.of(
				new DisplayRow(LuaUiText.text("局部与闭包变量"), "", "", 0, true),
				new DisplayRow("event.playerName = ", "\"Player\"", "string", 1, false),
				new DisplayRow("count = ", "16", "number", 1, false),
				new DisplayRow("enabled = ", "true", "boolean", 1, false)) : rows;
		int start = Math.min(scrollOffset, Math.max(0, shownRows.size() - capacity));
		int end = Math.min(shownRows.size(), start + capacity);
		int width = bounds.width(), height = bounds.height(), x = bounds.x(), y = bounds.y();
		graphics.fill(x, y, x + width, y + height, LuaHudLayout.current().backgroundColor(LuaHudLayout.Panel.VARIABLES));
		graphics.fill(x, y, x + width, y + 2, 0xFF8FA7FF);
		String heading = preview && !variablesEnabled ? LuaUiText.text("Lua 变量 · 未开启（仅预览）")
				: script.isBlank() ? LuaUiText.text("Lua 变量监视") : "Lua 变量 · " + script;
		graphics.text(font, font.plainSubstrByWidth(heading, width - 16), x + 7, y + 7, 0xFFE8ECFF);
		String count = (shownRows.size() > capacity ? "↑↓ " + (start + 1) + "–" + end + "/" + shownRows.size() + " 行 · " : "")
				+ "变量 " + variables.size() + (omitted > 0 ? " (另有 " + omitted + ")" : "");
		graphics.text(font, font.plainSubstrByWidth(count, width - 16), x + 7, y + 18, 0xFFAAB3C4);
		if (end == start) {
			graphics.text(font, font.plainSubstrByWidth(status, width - 16), x + 7, y + 32, 0xFFFFCB82);
			return;
		}
		for (int index = start; index < end; index++) {
			DisplayRow row = shownRows.get(index);
			int rowY = y + 32 + (index - start) * 11;
			int indent = Math.min(row.depth(), 7) * 12;
			if (row.depth() > 0) {
				int branchX = x + 8 + indent - 10;
				graphics.fill(branchX, rowY + 4, branchX + 8, rowY + 5, 0xFF596475);
				graphics.fill(branchX, rowY, branchX + 1, rowY + 5, 0xFF596475);
			}
			renderRow(graphics, font, row, x + 7 + indent, rowY,
					width - 16 - indent, row.depth() == 0 ? 0xFFAFC1FF
						: row.branch() ? 0xFFE8ECFF : 0xFFE2E6EC);
		}
	}

	static void renderRow(GuiGraphicsExtractor graphics, Font font, DisplayRow row,
			int x, int y, int availableWidth, int labelColor) {
		int space = Math.max(1, availableWidth);
		String prefix = (row.branch() ? "▾ " : "") + row.text();
		String shownPrefix = font.plainSubstrByWidth(prefix, space);
		graphics.text(font, shownPrefix, x, y, labelColor);
		if (shownPrefix.length() == prefix.length() && !row.value().isEmpty()) {
			int used = font.width(shownPrefix);
			if (used < space) graphics.text(font, font.plainSubstrByWidth(row.value(), space - used),
					x + used, y, valueColor(row.type()));
		}
	}

	static int valueColor(String type) {
		return switch (type) {
			case "string" -> 0xFF9DDFA8;
			case "number" -> 0xFFFFD078;
			case "boolean" -> 0xFFC6A7FF;
			case "table" -> 0xFF82C9F2;
			case "nil" -> 0xFF929BA8;
			case "function" -> 0xFFFFA9D0;
			case "thread", "userdata" -> 0xFFFFAF87;
			case "notice" -> 0xFFFFBD70;
			default -> 0xFFE2E6EC;
		};
	}

	private record Variable(String scope, String name, String value, String type, List<TreeNode> children) { }
	private record TreeNode(String name, String value, String type, List<TreeNode> children) { }
	record DisplayRow(String text, String value, String type, int depth, boolean branch) { }
}
