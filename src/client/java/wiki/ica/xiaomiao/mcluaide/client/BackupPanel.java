package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonParser;
import imgui.ImGui;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Backups: zip snapshots of the server's scripts (automatic, manual, restore with undo) and export / import of this computer's IDE
 * settings.
 */
final class BackupPanel {
	static final String ID = "NATIVE_BACKUP";
	private record Snapshot(String name, long bytes, String kind) { }
	private final EditorHost host;
	private List<Snapshot> snapshots = List.of();
	private String settingsLine = "", status = "", restoreName = "";
	private boolean loading, loaded, confirmRestore;

	BackupPanel(EditorHost host) { this.host = host; }

	void draw() {
		if (!loaded && !loading) refresh();
		ImGui.textColored(.6f, .78f, 1f, 1f, LuaUiText.text("服务器脚本快照", "Server script snapshots"));
		ImGui.textDisabled(settingsLine);
		ImGui.beginDisabled(loading);
		if (ImGui.button(LuaUiText.text("立即创建快照", "Create snapshot now"))) after(ScriptManager.createSnapshot(), name -> { status = LuaUiText.text("已创建：", "Created: ") + name; refresh(); });
		ImGui.sameLine();
		if (ImGui.button(LuaUiText.text("刷新", "Refresh"))) refresh();
		ImGui.endDisabled();
		if (ImGui.beginTable("snapshots", 4, ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.ScrollY, 0, Math.min(260, 40 + snapshots.size() * (ImGui.getFrameHeight() + 2)))) {
			ImGui.tableSetupColumn(LuaUiText.text("时间", "Time"), ImGuiTableColumnFlags.WidthStretch);
			ImGui.tableSetupColumn(LuaUiText.text("类型", "Type"), ImGuiTableColumnFlags.WidthFixed, 130);
			ImGui.tableSetupColumn(LuaUiText.text("大小", "Size"), ImGuiTableColumnFlags.WidthFixed, 80);
			ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, 90);
			ImGui.tableHeadersRow();
			for (Snapshot snapshot : snapshots) {
				ImGui.tableNextRow();
				ImGui.tableNextColumn(); ImGui.textUnformatted(time(snapshot.name()));
				ImGui.tableNextColumn(); ImGui.textDisabled(switch (snapshot.kind()) {
					case "manual" -> LuaUiText.text("手动", "manual");
					case "before-restore" -> LuaUiText.text("恢复前自动备份", "before a restore");
					default -> LuaUiText.text("自动", "automatic");
				});
				ImGui.tableNextColumn(); ImGui.textUnformatted(String.format(java.util.Locale.ROOT, "%.1f KB", snapshot.bytes() / 1024.0));
				ImGui.tableNextColumn();
				if (ImGui.smallButton(LuaUiText.text("恢复…", "Restore…") + "##" + snapshot.name())) { restoreName = snapshot.name(); confirmRestore = true; }
			}
			ImGui.endTable();
		}
		if (snapshots.isEmpty() && loaded) ImGui.textDisabled(LuaUiText.text("还没有快照", "No snapshots yet"));

		ImGui.spacing(); ImGui.separator(); ImGui.spacing();
		ImGui.textColored(.6f, .78f, 1f, 1f, LuaUiText.text("本机 IDE 设置", "IDE settings on this computer"));
		ImGui.textWrapped(LuaUiText.text("外观、快捷键、布局、各服务器的会话（断点、监视、打开的文件）、UI 模板、背景图和扩展清单。不包含脚本、服务器设置和任何令牌。",
				"Appearance, shortcuts, layouts, per-server sessions (breakpoints, watches, open files), UI templates, background images and extension manifests. Never scripts, server settings or tokens."));
		if (ImGui.button(LuaUiText.text("导出设置…", "Export settings…"))) exportSettings();
		ImGui.sameLine();
		if (ImGui.button(LuaUiText.text("导入设置…", "Import settings…"))) importSettings();
		if (!status.isEmpty()) ImGui.textWrapped(status);

		if (confirmRestore) { ImGui.openPopup("confirm_snapshot_restore"); confirmRestore = false; }
		if (ImGui.beginPopupModal("confirm_snapshot_restore", imgui.flag.ImGuiWindowFlags.AlwaysAutoResize | imgui.flag.ImGuiWindowFlags.NoTitleBar)) {
			ImGui.textUnformatted(LuaUiText.text("用这个快照替换服务器上的全部脚本？", "Replace all scripts on the server with this snapshot?"));
			ImGui.textDisabled(time(restoreName));
			ImGui.textWrapped(LuaUiText.text("当前脚本会先自动保存为一个快照，因此可以撤销。已打开且未保存的文件不会被覆盖，但保存时会提示冲突。",
					"The current scripts are snapshotted first, so this can be undone. Open files with unsaved changes are not touched; saving them will report a conflict."));
			if (ImGui.button(LuaUiText.text("恢复", "Restore"))) {
				String name = restoreName;
				after(ScriptManager.restoreSnapshot(name), backup -> { status = LuaUiText.text("已恢复。恢复前的脚本保存在：", "Restored. The previous scripts were saved as: ") + backup; refresh(); });
				ImGui.closeCurrentPopup();
			}
			ImGui.sameLine();
			if (ImGui.button(LuaUiText.text("取消", "Cancel"))) ImGui.closeCurrentPopup();
			ImGui.endPopup();
		}
	}

	private static String time(String name) {
		// scripts-2026-10-21_14-03-12-345-manual.zip -> 2026-10-21 14:03:12
		String body = name.replaceFirst("^scripts-", "").replaceFirst("\\.zip$", "");
		if (body.length() < 19) return name;
		return body.substring(0, 10) + " " + body.substring(11, 19).replace('-', ':');
	}

	private <T> void after(CompletableFuture<T> future, java.util.function.Consumer<T> success) {
		loading = true;
		future.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
			loading = false;
			if (error != null) status = ScriptManager.message(error); else success.accept(value);
		}));
	}

	private void refresh() {
		if (!ScriptManager.isServerAvailable()) { loaded = true; settingsLine = LuaUiText.text("未连接到装有本模组的服务器", "Not connected to a server with this mod"); return; }
		loading = true;
		ScriptManager.listSnapshots().whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
			loading = false; loaded = true;
			if (error != null) { status = ScriptManager.message(error); return; }
			try {
				var root = JsonParser.parseString(data).getAsJsonObject();
				List<Snapshot> list = new ArrayList<>();
				for (var element : root.getAsJsonArray("snapshots")) {
					var item = element.getAsJsonObject();
					list.add(new Snapshot(item.get("name").getAsString(), item.get("bytes").getAsLong(), item.get("kind").getAsString()));
				}
				snapshots = List.copyOf(list);
				settingsLine = root.get("enabled").getAsBoolean()
						? LuaUiText.text("自动：脚本有改动时每 " + root.get("intervalMinutes").getAsInt() + " 分钟一次，保留 " + root.get("keep").getAsInt() + " 个（config/mc_lua_ide/snapshots.properties）",
								"Automatic: every " + root.get("intervalMinutes").getAsInt() + " minutes when scripts changed, keeping " + root.get("keep").getAsInt() + " (config/mc_lua_ide/snapshots.properties)",
								"自動: スクリプトに変更があるとき " + root.get("intervalMinutes").getAsInt() + " 分ごと、" + root.get("keep").getAsInt() + " 個保持（config/mc_lua_ide/snapshots.properties）")
						: LuaUiText.text("自动快照已关闭（config/mc_lua_ide/snapshots.properties）", "Automatic snapshots are off (config/mc_lua_ide/snapshots.properties)");
			} catch (RuntimeException invalid) { status = LuaUiText.text("快照列表格式无效", "Invalid snapshot list"); }
		}));
	}

	private static Path settingsRoot() { return FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide"); }

	private void exportSettings() {
		Path target = Minecraft.getInstance().gameDirectory.toPath().resolve("mc_lua_ide-settings-" + LuaSettingsBackup.stamp() + ".zip");
		try {
			int count = LuaSettingsBackup.export(settingsRoot(), target);
			status = LuaUiText.text("已导出 " + count + " 个文件到：", "Exported " + count + " files to: ", count + " 個のファイルをエクスポートしました: ") + target;
			net.minecraft.util.Util.getPlatform().openPath(target.getParent());
		} catch (Exception error) { status = ScriptManager.message(error); }
	}

	private void importSettings() {
		LuaZipFileChooser.choose(Minecraft.getInstance().gameDirectory.toPath()).whenComplete((file, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) { status = ScriptManager.message(error); return; }
			if (file == null) return;
			Path undo = settingsRoot().resolve("backups").resolve("settings-before-import-" + LuaSettingsBackup.stamp() + ".zip");
			try {
				int count = LuaSettingsBackup.restore(settingsRoot(), file, undo);
				status = LuaUiText.text("已导入 " + count + " 个文件。关闭并重新打开 IDE 后生效（部分设置需要重启游戏）。导入前的设置已保存到：",
						"Imported " + count + " files. Close and reopen the IDE to apply them (some need a game restart). Your previous settings were saved to: ",
						count + " 個のファイルをインポートしました。IDE を閉じて開き直すと反映されます（一部はゲームの再起動が必要）。インポート前の設定の保存先: ") + undo;
			} catch (Exception failure) { status = ScriptManager.message(failure); }
		}));
	}
}
