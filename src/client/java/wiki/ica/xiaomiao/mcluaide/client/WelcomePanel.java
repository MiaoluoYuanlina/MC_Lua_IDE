package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImGui;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * First-run welcome: the few things to know, and a sample project (a handful of small, commented scripts under examples/) to
 * start from. Shown once automatically; reopen it from the command palette.
 */
final class WelcomePanel {
	static final String ID = "NATIVE_WELCOME";
	/** Marker file: the welcome page is opened automatically once per computer, not once per server. */
	private static java.nio.file.Path marker() { return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide").resolve("welcome-shown"); }
	static boolean shownBefore() { return java.nio.file.Files.exists(marker()); }
	private static void markShown() {
		try { java.nio.file.Files.createDirectories(marker().getParent()); if (!shownBefore()) java.nio.file.Files.writeString(marker(), "1"); } catch (java.io.IOException ignored) { }
	}
	/** Sample scripts: path (without .lua) and text. Only the APIs documented in the manual are used. */
	static final Map<String, String> SAMPLES = new LinkedHashMap<>();
	static {
		SAMPLES.put("examples/hello", """
				-- Hello world. Scripts run on the server, not on your computer.
				-- Press Ctrl+Enter (or Run in the menu bar) to run this file.
				print("Hello from the server!")

				for _, player in ipairs(MC.Player.list()) do
				    print(player.name, player.uuid)
				end
				""");
		SAMPLES.put("examples/countdown", """
				-- MC.sleep pauses this script only; the game keeps running.
				-- Try the debugger: click the number of line 6 to set a breakpoint, tick "Debug", then run.
				for i = 5, 1, -1 do
				    print("Countdown: " .. i)
				    MC.sleep(1000)
				    local remaining = i - 1
				end
				print("Done!")
				""");
		SAMPLES.put("examples/welcome_message", """
				-- Greets every player who joins. Event listeners keep the script running
				-- until you stop it (Stop in the menu bar, or /lua stop).
				MC.Event.onPlayerJoined(function(event)
				    MC.Chat.message(event.playerUuid, {text = "Welcome, " .. event.playerName .. "!", color = "#55FF55"})
				end)

				print("Waiting for players to join...")
				""");
		SAMPLES.put("examples/lib/greeting", """
				-- A module: other scripts load it with require("examples.lib.greeting").
				-- Ctrl+click greet in use_module.lua jumps here (go to definition works across files).
				local M = {}

				function M.greet(name)
				    return "Hello, " .. name .. "!"
				end

				return M
				""");
		SAMPLES.put("examples/use_module", """
				local greeting = require("examples.lib.greeting")

				print(greeting.greet("Steve"))
				""");
	}

	private final ImGuiIdeScreen owner;
	private final Properties preferences;
	private String status = "";
	private boolean creating;

	WelcomePanel(ImGuiIdeScreen owner, Properties preferences) { this.owner = owner; this.preferences = preferences; }

	void draw() {
		markShown();
		ImGui.textColored(.35f, .8f, .95f, 1, "MC Lua IDE");
		ImGui.textWrapped(LuaUiText.text("用 Lua 编写在服务器上运行的脚本。几个要点：", "Write Lua scripts that run on the server. The essentials:"));
		ImGui.spacing();
		row("Ctrl+N", LuaUiText.text("新建脚本；左侧「文件」中打开已有脚本", "new script; open existing ones from Files on the left"));
		row("Ctrl+S / Ctrl+Enter", LuaUiText.text("保存 / 运行或停止", "save / run or stop"));
		row("Ctrl+P · Ctrl+Shift+P", LuaUiText.text("快速打开文件 · 命令面板（所有功能都在这里）", "quick open · command palette (every feature is there)"));
		row("Ctrl+B · F2", LuaUiText.text("转到定义 · 重命名（可跨文件）", "go to definition · rename (across files)"));
		row(LuaUiText.text("行号栏", "Gutter"), LuaUiText.text("点击设置断点；勾选「调试」后运行即可单步执行", "click to set a breakpoint; tick Debug and run to step through"));
		row("Ctrl+`", LuaUiText.text("终端", "terminal"));
		row("Ctrl+F1", LuaUiText.text("API 文档：所有 MC.* 函数及示例", "API reference: every MC.* function with examples"));
		ImGui.spacing();
		ImGui.beginDisabled(creating);
		if (ImGui.button(LuaUiText.text("创建示例项目", "Create the sample project"))) createSamples();
		ImGui.endDisabled();
		if (ImGui.isItemHovered()) ImGui.setTooltip(LuaUiText.text("在服务器的 examples/ 目录中创建几个带注释的小脚本；已存在的文件不会被覆盖。",
				"Creates a few small, commented scripts under examples/ on the server; existing files are not overwritten."));
		ImGui.sameLine();
		if (ImGui.button(LuaUiText.text("打开使用手册", "Open the manual"))) owner.openTool("MANUAL");
		ImGui.sameLine();
		if (ImGui.button(LuaUiText.text("命令面板", "Command palette"))) owner.openPalette(">");
		if (!status.isEmpty()) ImGui.textWrapped(status);
		ImGui.spacing();
		ImGui.textDisabled(LuaUiText.text("之后可在命令面板中输入「欢迎」再次打开此页。", "Reopen this page from the command palette: \"Welcome\"."));
	}

	private static void row(String keys, String text) {
		ImGui.textColored(.95f, .8f, .45f, 1f, keys);
		ImGui.sameLine(180);
		ImGui.textWrapped(text);
	}

	private void createSamples() {
		if (!ScriptManager.isServerAvailable()) { status = LuaUiText.text("未连接到装有本模组的服务器", "Not connected to a server with this mod"); return; }
		creating = true; status = LuaUiText.text("正在创建…", "Creating…");
		var existing = new java.util.HashSet<String>();
		for (var entry : owner.tree(ImGuiIdeDocument.Kind.LUA)) if (!entry.directory()) existing.add(entry.path());
		List<CompletableFuture<Void>> writes = new ArrayList<>();
		int[] created = {0};
		SAMPLES.forEach((path, text) -> {
			if (existing.contains(path)) return;
			created[0]++;
			writes.add(ScriptManager.overwrite(path, text));
		});
		CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
			creating = false;
			if (error != null) { status = ScriptManager.message(error); return; }
			status = created[0] == 0 ? LuaUiText.text("示例文件已存在（examples/）。", "The sample files already exist (examples/).")
					: LuaUiText.text("已创建 " + created[0] + " 个示例脚本（examples/）。", "Created " + created[0] + " sample scripts (examples/).");
			owner.refreshTreesFromTool();
			owner.openAt(ImGuiIdeDocument.Kind.LUA, "examples/hello", 0, 0);
		}));
	}
}
