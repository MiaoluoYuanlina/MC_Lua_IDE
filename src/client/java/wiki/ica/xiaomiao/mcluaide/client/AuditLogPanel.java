package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import imgui.ImGui;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The server's audit log: who saved, deleted, ran or reconfigured what, newest first. Read-only. */
final class AuditLogPanel {
	static final String ID = "NATIVE_AUDIT";
	private record Entry(String time, String player, String action, String target, String outcome) { }
	private List<Entry> entries = List.of();
	private final ImString filter = new ImString(128);
	private boolean loading, loaded;
	private String error = "";

	void draw() {
		if (!loaded && !loading) refresh();
		ImGui.beginDisabled(loading);
		if (ImGui.button(LuaUiText.text("刷新", "Refresh"))) refresh();
		ImGui.endDisabled();
		ImGui.sameLine();
		ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 8));
		ImGui.inputTextWithHint("##audit_filter", LuaUiText.text("筛选玩家、操作或文件…", "Filter by player, action or file…"), filter);
		if (!error.isEmpty()) ImGui.textColored(1f, .5f, .45f, 1f, error);
		ImGui.textDisabled(LuaUiText.text("服务器上的 config/mc_lua_ide/audit.log，最多显示最近 300 条。", "config/mc_lua_ide/audit.log on the server; the newest 300 entries."));
		String needle = filter.get().strip().toLowerCase(Locale.ROOT);
		if (!ImGui.beginTable("audit_table", 5, ImGuiTableFlags.ScrollY | ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.Resizable)) return;
		ImGui.tableSetupScrollFreeze(0, 1);
		ImGui.tableSetupColumn(LuaUiText.text("时间", "Time"), ImGuiTableColumnFlags.WidthFixed, 150);
		ImGui.tableSetupColumn(LuaUiText.text("玩家", "Player"), ImGuiTableColumnFlags.WidthFixed, 110);
		ImGui.tableSetupColumn(LuaUiText.text("操作", "Action"), ImGuiTableColumnFlags.WidthFixed, 150);
		ImGui.tableSetupColumn(LuaUiText.text("对象", "Target"), ImGuiTableColumnFlags.WidthStretch);
		ImGui.tableSetupColumn(LuaUiText.text("结果", "Result"), ImGuiTableColumnFlags.WidthStretch);
		ImGui.tableHeadersRow();
		for (Entry entry : entries) {
			if (!needle.isEmpty() && !(entry.player() + " " + entry.action() + " " + entry.target() + " " + entry.outcome()).toLowerCase(Locale.ROOT).contains(needle)) continue;
			ImGui.tableNextRow();
			ImGui.tableNextColumn(); ImGui.textUnformatted(entry.time());
			ImGui.tableNextColumn(); ImGui.textUnformatted(entry.player());
			ImGui.tableNextColumn(); ImGui.textUnformatted(entry.action());
			ImGui.tableNextColumn(); ImGui.textUnformatted(entry.target());
			ImGui.tableNextColumn();
			if (entry.outcome().startsWith("failed")) ImGui.textColored(1f, .5f, .45f, 1f, entry.outcome()); else ImGui.textDisabled(entry.outcome());
		}
		ImGui.endTable();
	}

	private void refresh() {
		if (!ScriptManager.isServerAvailable()) { error = LuaUiText.text("未连接到装有本模组的服务器", "Not connected to a server with this mod"); loaded = true; return; }
		loading = true; error = "";
		ScriptManager.auditLog().whenComplete((data, failure) -> Minecraft.getInstance().execute(() -> {
			loading = false; loaded = true;
			if (failure != null) { error = ScriptManager.message(failure); return; }
			try {
				List<Entry> list = new ArrayList<>();
				for (var element : JsonParser.parseString(data).getAsJsonArray()) {
					var item = element.getAsJsonObject();
					String time = item.get("time").getAsString().replace('T', ' ');
					if (time.length() > 19) time = time.substring(0, 19);
					list.add(new Entry(time, item.get("player").getAsString(), item.get("action").getAsString(), item.get("target").getAsString(), item.get("outcome").getAsString()));
				}
				entries = List.copyOf(list);
			} catch (RuntimeException invalid) { error = LuaUiText.text("审计日志格式无效", "Invalid audit log data"); }
		}));
	}
}
