package wiki.ica.xiaomiao.mcluaide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command reference for .mcfunction files: every command of the connected server with its usage lines, one top-level
 * category for vanilla and one per mod. Vanilla commands carry a short description (bundled, in three languages);
 * mod commands show their formats only, because Brigadier stores no descriptions.
 */
final class CommandReferencePanel {
	static final String ID = "NATIVE_COMMANDS";
	private record Command(String name, String description, List<String> usages) { }
	private record Category(String id, String name, List<Command> commands) { }
	private static JsonObject descriptions;

	private final EditorHost host;
	private List<Category> categories = List.of();
	private final ImString filter = new ImString(128);
	private boolean loading, loaded;
	private String error = "", selected = "", status = "";

	CommandReferencePanel(EditorHost host) { this.host = host; }

	void draw() {
		if (!loaded && !loading) refresh();
		ImGui.beginDisabled(loading);
		if (ImGui.button(LuaUiText.text("刷新", "Refresh"))) refresh();
		ImGui.endDisabled();
		ImGui.sameLine();
		ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 8));
		ImGui.inputTextWithHint("##command_filter", LuaUiText.text("搜索命令、格式或说明…", "Search commands, formats or descriptions…"), filter);
		if (!error.isEmpty()) ImGui.textColored(1f, .5f, .45f, 1f, error);
		ImGui.textDisabled(LuaUiText.text("来自当前服务器：原版命令一个分类，每个模组各一个分类。点击格式选中，双击插入到函数文件。",
				"From the connected server: one category for vanilla and one per mod. Click a format to select it; double-click inserts it into the function file."));
		String needle = filter.get().strip().toLowerCase(Locale.ROOT);
		float footer = ImGui.getFrameHeightWithSpacing() * 2 + 8;
		ImGui.beginChild("command_tree", 0, Math.max(60, ImGui.getContentRegionAvailY() - footer), true);
		for (Category category : categories) {
			List<Command> shown = new ArrayList<>();
			for (Command command : category.commands()) if (matches(category, command, needle)) shown.add(command);
			if (shown.isEmpty()) continue;
			ImGui.pushID(category.id());
			if (!needle.isEmpty()) ImGui.setNextItemOpen(true, imgui.flag.ImGuiCond.Always);
			String title = (category.id().equals("minecraft") ? LuaUiText.text("原版命令", "Vanilla commands") : category.id().equals("other") ? LuaUiText.text("其他命令（无法判定所属模组）", "Other commands (mod unknown)") : category.name() + "  (" + category.id() + ")") + "  [" + shown.size() + "]";
			if (ImGui.treeNodeEx(title, ImGuiTreeNodeFlags.SpanAvailWidth | (category.id().equals("minecraft") ? ImGuiTreeNodeFlags.DefaultOpen : 0))) {
				for (Command command : shown) drawCommand(command, !needle.isEmpty());
				ImGui.treePop();
			}
			ImGui.popID();
		}
		if (loaded && categories.isEmpty() && error.isEmpty()) ImGui.textDisabled(LuaUiText.text("没有可显示的命令。", "No commands to show."));
		ImGui.endChild();
		ImGui.textWrapped(selected.isEmpty() ? LuaUiText.text("尚未选择命令格式", "No command format selected") : selected);
		ImGui.beginDisabled(selected.isEmpty());
		if (ImGui.button(LuaUiText.text("插入到编辑器", "Insert into editor"))) insert(selected);
		ImGui.sameLine();
		if (ImGui.button(LuaUiText.text("复制", "Copy"))) { NativeUi.copy(selected); status = LuaUiText.text("已复制命令格式", "Copied the command format"); }
		ImGui.endDisabled();
		if (!status.isEmpty()) { ImGui.sameLine(); ImGui.textDisabled(status); }
	}

	private void drawCommand(Command command, boolean open) {
		ImGui.pushID(command.name());
		if (open) ImGui.setNextItemOpen(true, imgui.flag.ImGuiCond.Always);
		boolean expanded = ImGui.treeNodeEx(command.name(), ImGuiTreeNodeFlags.SpanAvailWidth);
		if (!command.description().isEmpty()) {
			ImGui.sameLine();
			ImGui.textDisabled("— " + command.description());
		}
		if (expanded) {
			if (!command.description().isEmpty()) ImGui.textWrapped(command.description());
			int index = 0;
			for (String usage : command.usages()) {
				ImGui.pushID(index++);
				if (ImGui.selectable(usage, usage.equals(selected), imgui.flag.ImGuiSelectableFlags.AllowDoubleClick)) {
					selected = usage;
					if (ImGui.isMouseDoubleClicked(0)) insert(usage);
				}
				ImGui.popID();
			}
			ImGui.treePop();
		}
		ImGui.popID();
	}

	private void insert(String text) {
		status = host.insertText(text) ? LuaUiText.text("已插入命令格式", "Inserted the command format") : LuaUiText.text("请先打开一个可编辑的文件", "Open an editable file first");
	}

	private static boolean matches(Category category, Command command, String needle) {
		if (needle.isEmpty()) return true;
		if ((category.id() + " " + category.name()).toLowerCase(Locale.ROOT).contains(needle)) return true;
		if ((command.name() + " " + command.description()).toLowerCase(Locale.ROOT).contains(needle)) return true;
		for (String usage : command.usages()) if (usage.toLowerCase(Locale.ROOT).contains(needle)) return true;
		return false;
	}

	private void refresh() {
		if (!ScriptManager.isServerAvailable()) { error = LuaUiText.text("未连接到装有本模组的服务器", "Not connected to a server with this mod"); loaded = true; return; }
		loading = true; error = "";
		ScriptManager.commandReference().whenComplete((data, failure) -> Minecraft.getInstance().execute(() -> {
			loading = false; loaded = true;
			if (failure != null) { error = ScriptManager.message(failure); return; }
			try { categories = parse(data); } catch (RuntimeException invalid) { error = LuaUiText.text("命令数据格式无效", "Invalid command data"); }
		}));
	}

	static List<Category> parse(String data) {
		List<Category> result = new ArrayList<>();
		for (var element : JsonParser.parseString(data).getAsJsonArray()) {
			JsonObject category = element.getAsJsonObject();
			String id = category.get("id").getAsString();
			List<Command> commands = new ArrayList<>();
			for (var item : category.getAsJsonArray("commands")) {
				JsonObject command = item.getAsJsonObject();
				List<String> usages = new ArrayList<>();
				command.getAsJsonArray("usages").forEach(usage -> usages.add(usage.getAsString()));
				String name = command.get("name").getAsString();
				boolean documented = id.equals("minecraft") || (id.equals("mc_lua_ide") && name.equals("lua"));
				commands.add(new Command(name, documented ? description(name) : "", List.copyOf(usages)));
			}
			result.add(new Category(id, category.get("name").getAsString(), List.copyOf(commands)));
		}
		return List.copyOf(result);
	}

	private static String description(String command) {
		if (descriptions == null) {
			try (var input = CommandReferencePanel.class.getResourceAsStream("/assets/mc_lua_ide/commands/descriptions.json")) {
				descriptions = input == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (java.io.IOException | RuntimeException failure) { descriptions = new JsonObject(); }
		}
		var entry = descriptions.get(command);
		if (entry == null || !entry.isJsonObject()) return "";
		String key = LuaUiText.chinese() ? "zh" : LuaUiText.japanese() ? "ja" : "en";
		var text = entry.getAsJsonObject().get(key);
		return text == null ? "" : text.getAsString();
	}
}
