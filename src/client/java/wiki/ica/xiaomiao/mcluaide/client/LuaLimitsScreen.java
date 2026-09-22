package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paged editor for the server-authoritative Lua execution policy. */
public final class LuaLimitsScreen extends Screen {
	private static final List<Page> PAGES = List.of(
			new Page("执行资源", "限制脚本体积、运行时间以及输出量", List.of(
					number("maxScriptChars", "脚本最大字符数", "1,024 - 100,000；保存和执行时检查"),
					number("maxRuntimeSeconds", "最长运行秒数", "0 表示不限时；事件脚本也会计时"),
					number("maxOutputLines", "最大输出行数", "每次 print 计算一行"),
					number("maxOutputCharsPerLine", "单行输出字符数", "超过后终止脚本，避免网络日志洪泛"),
					number("instructionHookInterval", "指令检查间隔", "数值越小，停止和超时响应越快；范围 100 - 100,000"))),
			new Page("事件限制", "控制事件脚本的注册数量、频率和积压", List.of(
					bool("allowEvents", "允许注册游戏事件", "关闭后 MC.Event 不会提供给新脚本"),
					number("maxEventCallbacks", "最大监听数量", "0 表示完全禁止注册；最大 2,048"),
					number("eventQueueCapacity", "事件队列容量", "队列满时丢弃最旧事件；范围 16 - 65,536"),
					number("minTickEventInterval", "Tick 最小间隔", "限制 serverTick/levelTick 的最低间隔；范围 1 - 1,200"))),
			new Page("MC API · 世界", "按命名空间禁用高权限游戏控制能力", List.of(
					dangerBool("allowCommandApi", "命令 API", "脚本会以服务端控制台身份执行命令，可能绕过普通 API 限制和权限检查"),
					warningBool("allowWorldApi", "世界与方块 API", "脚本可以修改方块、规则、边界、结构和战利品，错误代码可能大范围破坏世界"),
					warningBool("allowPlayerApi", "玩家 API", "脚本可以传送、伤害或修改指定 UUID 玩家的状态、属性与效果"),
					warningBool("allowEntityApi", "实体 API", "脚本可以生成、移动、修改或移除实体，错误循环可能造成卡顿"),
					warningBool("allowInventoryApi", "物品与容器 API", "脚本可以修改玩家物品、容器内容和菜单，可能造成物品丢失或复制"))),
			new Page("MC API · 服务", "控制显示、服务端数据和注册表访问", List.of(
					warningBool("allowPresentationApi", "聊天、显示与界面 API", "脚本可以发送聊天、标题、声音、粒子、交互对话框和强制客户端提示；界面可能诱导玩家提交文本或执行操作"),
					dangerBool("allowServerApi", "服务端管理 API", "脚本可以控制服务器、游戏规则、模组数据、计分板、队伍、进度与配方"),
					warningBool("allowRegistryApi", "注册表 API", "脚本可以枚举本服和其他 Mod 的注册内容，可能暴露服务端模组信息"))),
			new Page("文件沙箱", "文件只能位于 config/mc_lua_ide/data/<脚本名>/", List.of(
					warningBool("allowFileRead", "允许读取/列出文件", "脚本可以读取自己的隔离目录，可能在日志或网络请求中泄露其中的数据"),
					warningBool("allowFileWrite", "允许写入/追加文件", "脚本可以持续写入自己的隔离目录，配额过大时可能占用磁盘空间"),
					warningBool("allowFileDelete", "允许删除文件", "脚本可以永久删除自己隔离目录内的数据，误操作可能无法恢复"),
					number("maxDataFileBytes", "单文件最大字节数", "范围 1,024 - 16,777,216"),
					number("maxDataFiles", "每个脚本最大文件数", "范围 1 - 1,024"))),
			new Page("Lua 语言能力", "关闭不需要的动态语言功能可进一步缩小脚本能力", List.of(
					warningBool("allowDynamicCode", "动态编译代码", "脚本可以用 load/loadstring 运行动态生成的代码，审查脚本时更难确认真实行为"),
					dangerBool("allowCoroutine", "协程库", "新建协程可能脱离当前执行钩子的完整控制，增加超时或停止失效的风险"),
					warningBool("allowMetatables", "元表与 raw 操作", "脚本可以改写对象行为或绕过常规字段访问，使行为更难审查和限制"),
					warningBool("allowGcControl", "垃圾回收控制", "脚本可以触发或调整垃圾回收，频繁调用可能造成整个服务端卡顿"))),
			new Page("核心安全边界", "允许这些能力会削弱或完全移除 Lua 沙箱，请谨慎开启", List.of(
					dangerBool("allowJavaInterop", "Java / luajava", "脚本可以访问 JVM、文件、网络和服务端内部对象，等同于放弃主要沙箱边界"),
					dangerBool("allowOperatingSystem", "操作系统库 os", "脚本可能执行系统命令、读取进程环境或影响运行服务端的主机"),
					dangerBool("allowUnrestrictedFileIo", "原生 io 与任意路径", "io、dofile 和 loadfile 可以读写任意服务端路径，不再受脚本目录隔离"),
					dangerBool("allowPackageLoading", "package / require", "脚本可以从包路径载入未经限制的外部模块，模块可能获得额外主机能力"),
					dangerBool("allowDebugLibrary", "Lua debug 库", "脚本可以移除停止与超时钩子，失控脚本可能无法从 IDE 正常终止"))),
			new Page("网络访问", "允许脚本主动请求外部或内网 HTTP 服务，可能造成 SSRF", List.of(
					dangerBool("allowNetworkAccess", "HTTP/HTTPS 网络 API", "脚本可以访问公网、服务器内网和本机 HTTP 服务，可能泄露数据或实施 SSRF"),
					number("networkTimeoutSeconds", "网络请求超时秒数", "范围 1 - 60；连接和完整请求均受限"),
					number("maxNetworkResponseBytes", "请求/响应最大字节数", "范围 1,024 - 8,388,608；禁止自动重定向"))));

	private final Screen previousScreen;
	private final Map<String, String> values = new LinkedHashMap<>(LuaLimits.defaults().toMap());
	private int pageIndex;
	private boolean loaded;
	private boolean loading;
	private boolean loadAttempted;
	private boolean saving;
	private String status = "正在读取服务端限制…";

	public LuaLimitsScreen(Screen previousScreen) {
		super(Component.literal("Lua 限制"));
		this.previousScreen = previousScreen;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(560, width - 36);
		int left = (width - panelWidth) / 2;
		Page page = PAGES.get(pageIndex);

		Button previous = Button.builder(Component.literal("‹"), button -> changePage(-1))
				.bounds(left, 28, 28, 20).build();
		previous.active = pageIndex > 0;
		addRenderableWidget(previous);
		Button next = Button.builder(Component.literal("›"), button -> changePage(1))
				.bounds(left + panelWidth - 28, 28, 28, 20).build();
		next.active = pageIndex + 1 < PAGES.size();
		addRenderableWidget(next);

		int rowY = 70;
		for (Setting setting : page.settings()) {
			if (setting.type() == SettingType.NUMBER) {
				EditBox box = new EditBox(font, left + panelWidth - 150, rowY, 150, 20,
						Component.literal(setting.label()));
				box.setMaxLength(12);
				box.setValue(values.getOrDefault(setting.key(), ""));
				box.setResponder(value -> values.put(setting.key(), value.strip()));
				box.setTooltip(Tooltip.create(Component.literal(setting.description())));
				box.setEditable(loaded && !saving);
				addRenderableWidget(box);
			} else if (setting.type() == SettingType.BOOLEAN
					|| setting.type() == SettingType.WARNING_BOOLEAN
					|| setting.type() == SettingType.DANGEROUS_BOOLEAN) {
				boolean enabled = Boolean.parseBoolean(values.getOrDefault(setting.key(), "false"));
				boolean risk = setting.type() != SettingType.BOOLEAN;
				boolean dangerous = setting.type() == SettingType.DANGEROUS_BOOLEAN;
				Component value = Component.literal(risk
						? enabled ? dangerous ? "高危：允许" : "警告：允许" : "安全：禁止"
						: enabled ? "允许" : "禁止")
						.withStyle(risk
								? enabled ? dangerous ? ChatFormatting.RED : ChatFormatting.YELLOW : ChatFormatting.GREEN
								: enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
				Button toggle = Button.builder(value, button -> toggle(setting.key()))
						.bounds(left + panelWidth - 150, rowY, 150, 20).build();
				toggle.active = loaded && !saving;
				toggle.setTooltip(Tooltip.create(Component.literal(setting.description())));
				addRenderableWidget(toggle);
			}
			rowY += rowHeight(setting, panelWidth);
		}

		Button defaults = Button.builder(Component.literal("恢复默认"), button -> resetDefaults())
				.bounds(left, height - 32, 90, 20).build();
		defaults.active = loaded && !saving;
		addRenderableWidget(defaults);
		String saveLabel = saving ? "保存中…" : loaded ? "保存到服务器" : "重试读取";
		Button save = Button.builder(Component.literal(saveLabel), button -> {
			if (loaded) save();
			else load();
		})
				.bounds(width / 2 - 65, height - 32, 130, 20).build();
		save.active = !loading && !saving;
		addRenderableWidget(save);
		addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
				.bounds(left + panelWidth - 90, height - 32, 90, 20).build());

		if (!loaded && !loading && !loadAttempted) load();
	}

	private void load() {
		loadAttempted = true;
		loading = true;
		status = "正在读取服务端限制…";
		ScriptManager.getLimits().whenComplete((limits, failure) -> minecraft.execute(() -> {
			loading = false;
			loaded = failure == null;
			if (failure != null) {
				status = "读取失败：" + ScriptManager.message(failure);
			} else {
				values.clear();
				values.putAll(limits.toMap());
				status = "已读取；修改只影响下一次启动的脚本";
			}
			if (minecraft.screen == this) rebuildWidgets();
		}));
	}

	private void save() {
		LuaLimits limits;
		try {
			limits = LuaLimits.fromMap(values);
		} catch (RuntimeException error) {
			status = "输入无效：" + error.getMessage();
			return;
		}
		saving = true;
		status = "正在保存到服务器…";
		rebuildWidgets();
		ScriptManager.saveLimits(limits).whenComplete((saved, failure) -> minecraft.execute(() -> {
			saving = false;
			if (failure != null) {
				status = "保存失败：" + ScriptManager.message(failure);
			} else {
				values.clear();
				values.putAll(saved.toMap());
				status = "已保存；新限制将在下次运行脚本时生效";
			}
			if (minecraft.screen == this) rebuildWidgets();
		}));
	}

	private void toggle(String key) {
		values.put(key, Boolean.toString(!Boolean.parseBoolean(values.getOrDefault(key, "false"))));
		rebuildWidgets();
	}

	private void resetDefaults() {
		values.clear();
		values.putAll(LuaLimits.defaults().toMap());
		status = "已恢复默认值，点击“保存到服务器”后生效";
		rebuildWidgets();
	}

	private void changePage(int amount) {
		pageIndex = Math.max(0, Math.min(PAGES.size() - 1, pageIndex + amount));
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelWidth = Math.min(560, width - 36);
		int left = (width - panelWidth) / 2;
		Page page = PAGES.get(pageIndex);
		graphics.centeredText(font, title, width / 2, 7, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("服务端全局设置：所有管理员与所有新脚本共用"),
				width / 2, 18, 0xFFFF5555);
		graphics.centeredText(font, Component.literal(page.title() + "  " + (pageIndex + 1) + "/" + PAGES.size()),
				width / 2, 34, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal(page.description()), width / 2, 52, 0xFF999999);
		int rowY = 70;
		for (Setting setting : page.settings()) {
			graphics.text(font, Component.literal(setting.label()), left, rowY + 6, 0xFFE0E0E0);
			if (isRisk(setting)) {
				boolean dangerous = setting.type() == SettingType.DANGEROUS_BOOLEAN;
				int riskColor = dangerous ? 0xFFFF5555 : 0xFFFFCC55;
				String riskLabel = dangerous ? "⚠ 高危" : "⚠ 警告";
				graphics.text(font, Component.literal(riskLabel), left + panelWidth - 210, rowY + 6, riskColor);
				String reason = (dangerous ? "危险理由：" : "警告理由：") + setting.description();
				int reasonY = rowY + 24;
				for (var line : font.split(Component.literal(reason), panelWidth - 4)) {
					graphics.text(font, line, left + 2, reasonY, riskColor);
					reasonY += 10;
				}
			}
			rowY += rowHeight(setting, panelWidth);
		}
		graphics.centeredText(font, Component.literal(status), width / 2, height - 48,
				status.contains("失败") || status.contains("无效") ? 0xFFFF7777 : 0xFFAAAAAA);
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

	private static Setting number(String key, String label, String description) {
		return new Setting(key, label, description, SettingType.NUMBER);
	}

	private static Setting bool(String key, String label, String description) {
		return new Setting(key, label, description, SettingType.BOOLEAN);
	}

	private static Setting warningBool(String key, String label, String description) {
		return new Setting(key, label, description, SettingType.WARNING_BOOLEAN);
	}

	private static Setting dangerBool(String key, String label, String description) {
		return new Setting(key, label, description, SettingType.DANGEROUS_BOOLEAN);
	}

	private boolean isRisk(Setting setting) {
		return setting.type() == SettingType.WARNING_BOOLEAN
				|| setting.type() == SettingType.DANGEROUS_BOOLEAN;
	}

	private int rowHeight(Setting setting, int panelWidth) {
		if (!isRisk(setting)) return 28;
		String prefix = setting.type() == SettingType.DANGEROUS_BOOLEAN ? "危险理由：" : "警告理由：";
		return 30 + font.split(Component.literal(prefix + setting.description()), panelWidth - 4).size() * 10;
	}

	private enum SettingType { NUMBER, BOOLEAN, WARNING_BOOLEAN, DANGEROUS_BOOLEAN }

	private record Setting(String key, String label, String description, SettingType type) {
	}

	private record Page(String title, String description, List<Setting> settings) {
	}
}
