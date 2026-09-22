package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ScriptDirectoryScreen extends Screen {
	private final Screen previousScreen;
	private EditBox nameBox;
	private List<String> scripts = List.of();
	private boolean loaded;
	private boolean loading;
	private boolean loadAttempted;
	private String status = "正在读取服务器脚本目录…";

	public ScriptDirectoryScreen(Screen previousScreen) {
		super(Component.translatable("screen.mc_lua_ide.directory"));
		this.previousScreen = previousScreen;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(420, width - 40);
		int left = (width - panelWidth) / 2;
		nameBox = new EditBox(font, left, 38, panelWidth - 92, 20,
				Component.translatable("screen.mc_lua_ide.script_name"));
		nameBox.setMaxLength(48);
		nameBox.setHint(Component.translatable("screen.mc_lua_ide.script_name"));
		addRenderableWidget(nameBox);
		addRenderableWidget(Button.builder(Component.translatable("screen.mc_lua_ide.new"), button -> createScript())
				.bounds(left + panelWidth - 84, 38, 84, 20).build());

		int maxVisible = Math.max(1, (height - 118) / 24);
		for (int index = 0; index < Math.min(scripts.size(), maxVisible); index++) {
			String name = scripts.get(index);
			int y = 72 + index * 24;
			addRenderableWidget(Button.builder(Component.literal(name + ".lua"), button -> openEditor(name))
					.bounds(left, y, panelWidth - 34, 20).build());
			addRenderableWidget(Button.builder(Component.literal("×"), button -> deleteScript(name))
					.bounds(left + panelWidth - 28, y, 28, 20).build());
		}
		if (loaded && scripts.isEmpty()) status = "暂无服务器脚本，请在上方输入名称并新建";
		else if (loaded && scripts.size() > maxVisible) status = "当前显示 " + maxVisible + " / " + scripts.size() + " 个脚本";

		addRenderableWidget(Button.builder(Component.literal("设置"), button ->
				minecraft.setScreen(new LuaSettingsScreen(this)))
				.bounds(width / 2 - 130, height - 34, 84, 20).build());
		Button refresh = Button.builder(Component.literal(loading ? "读取中…" : "刷新"), button -> refreshScripts())
				.bounds(width / 2 - 42, height - 34, 84, 20).build();
		refresh.active = !loading;
		addRenderableWidget(refresh);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(width / 2 + 46, height - 34, 84, 20).build());
		if (!loaded && !loading && !loadAttempted) refreshScripts();
	}

	private void refreshScripts() {
		loadAttempted = true;
		loading = true;
		status = "正在读取服务器脚本目录…";
		ScriptManager.listScripts().whenComplete((result, failure) -> minecraft.execute(() -> {
			loading = false;
			loaded = failure == null;
			if (failure != null) {
				scripts = List.of();
				status = "读取目录失败：" + ScriptManager.message(failure);
			} else {
				scripts = result;
				status = scripts.isEmpty() ? "暂无服务器脚本，请在上方输入名称并新建"
						: "已读取 " + scripts.size() + " 个服务器脚本";
			}
			if (minecraft.screen == this) rebuildWidgets();
		}));
	}

	private void createScript() {
		String name = nameBox.getValue().trim();
		if (!ScriptManager.isValidName(name)) {
			status = "名称只能包含字母、数字、下划线和连字符";
			return;
		}
		if (scripts.contains(name)) {
			status = "脚本已存在：" + name;
			return;
		}
		status = "正在服务器创建 " + name + ".lua…";
		String source = "-- " + name + ".lua\nprint(\"Hello from " + name + "!\")\n";
		ScriptManager.save(name, source).whenComplete((ignored, failure) -> minecraft.execute(() -> {
			if (failure != null) {
				status = "新建失败：" + ScriptManager.message(failure);
				return;
			}
			openEditor(name);
		}));
	}

	private void deleteScript(String name) {
		status = "正在从服务器删除 " + name + ".lua…";
		ScriptManager.delete(name).whenComplete((deleted, failure) -> minecraft.execute(() -> {
			if (failure != null) {
				status = "删除失败：" + ScriptManager.message(failure);
				return;
			}
			status = deleted ? "已删除 " + name + ".lua" : "脚本不存在";
			loaded = false;
			refreshScripts();
		}));
	}

	private void openEditor(String name) {
		minecraft.setScreen(new LuaIdeScreen(this, name));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal(status), width / 2, height - 50,
				status.contains("失败") ? 0xFFFF7777 : 0xFFAAAAAA);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(previousScreen);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
