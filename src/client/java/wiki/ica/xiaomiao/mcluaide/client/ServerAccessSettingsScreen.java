package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-global client installation policy. */
public final class ServerAccessSettingsScreen extends Screen {
	private static final String WARNING =
			"开启后，未安装 MC Lua IDE 或协议版本不一致的客户端会在登录阶段被拒绝。";
	private final Screen previousScreen;
	private LuaLimits loadedSettings;
	private boolean requireClientMod;
	private boolean loading;
	private boolean saving;
	private boolean loadAttempted;
	private String status = "正在读取服务端设置…";

	public ServerAccessSettingsScreen(Screen previousScreen) {
		super(Component.literal("服务器接入"));
		this.previousScreen = previousScreen;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(500, width - 40);
		int left = (width - panelWidth) / 2;
		Component state = requireClientMod
				? Component.literal("⚠ 强制安装：开启").withStyle(ChatFormatting.YELLOW)
				: Component.literal("可选安装：关闭").withStyle(ChatFormatting.GREEN);
		Button toggle = Button.builder(state, button -> {
			requireClientMod = !requireClientMod;
			status = "已修改，点击“保存到服务器”后生效";
			rebuildWidgets();
		}).bounds(left + panelWidth - 180, 72, 180, 22).build();
		toggle.active = loadedSettings != null && !saving;
		toggle.setTooltip(Tooltip.create(Component.literal(WARNING)));
		addRenderableWidget(toggle);

		Button save = Button.builder(Component.literal(saving ? "保存中…" :
				loadedSettings == null ? "重试读取" : "保存到服务器"), button -> {
			if (loadedSettings == null) load();
			else save();
		}).bounds(width / 2 - 70, height - 36, 140, 22).build();
		save.active = !loading && !saving;
		addRenderableWidget(save);
		addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
				.bounds(left + panelWidth - 90, height - 36, 90, 22).build());

		if (!loadAttempted && !loading) load();
	}

	private void load() {
		loadAttempted = true;
		loading = true;
		status = "正在读取服务端设置…";
		ScriptManager.getLimits().whenComplete((settings, failure) -> minecraft.execute(() -> {
			loading = false;
			if (failure != null) {
				loadedSettings = null;
				status = "读取失败：" + ScriptManager.message(failure);
			} else {
				loadedSettings = settings;
				requireClientMod = settings.requireClientMod();
				status = "已读取；当前在线玩家不受影响";
			}
			if (minecraft.screen == this) rebuildWidgets();
		}));
	}

	private void save() {
		Map<String, String> values = new LinkedHashMap<>(loadedSettings.toMap());
		values.put("requireClientMod", Boolean.toString(requireClientMod));
		LuaLimits updated = LuaLimits.fromMap(values);
		saving = true;
		status = "正在保存到服务器…";
		rebuildWidgets();
		ScriptManager.saveLimits(updated).whenComplete((saved, failure) -> minecraft.execute(() -> {
			saving = false;
			if (failure != null) {
				status = "保存失败：" + ScriptManager.message(failure);
			} else {
				loadedSettings = saved;
				requireClientMod = saved.requireClientMod();
				status = requireClientMod
						? "已保存；之后登录的玩家必须安装匹配版本"
						: "已保存；之后登录的普通玩家可以不安装客户端模组";
			}
			if (minecraft.screen == this) rebuildWidgets();
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelWidth = Math.min(500, width - 40);
		int left = (width - panelWidth) / 2;
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("服务端全局设置：所有管理员共用"),
				width / 2, 34, 0xFFFF5555);
		graphics.text(font, Component.literal("要求玩家安装客户端模组"), left, 79, 0xFFE0E0E0);
		graphics.text(font, Component.literal("⚠ 警告"), left, 108, 0xFFFFCC55);
		int warningY = 122;
		for (var line : font.split(Component.literal("警告理由：" + WARNING), panelWidth)) {
			graphics.text(font, line, left, warningY, 0xFFFFCC55);
			warningY += 11;
		}
		graphics.text(font, Component.literal("关闭：普通玩家可使用原版客户端进入，管理员仍需安装模组。"),
				left, warningY + 10, 0xFFAAAAAA);
		graphics.text(font, Component.literal("开启：服务器在进入世界前验证 MC Lua IDE 协议版本。"),
				left, warningY + 24, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal(status), width / 2, height - 54,
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
