package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaServerPack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Built-in content uses the IDE's shared animation, resizing, docking and saved-layout frame. */
final class LuaServerPackWindow implements McLuaIdeWindowApi.Content {
	static final String ID = "mc_lua_ide:server_resource_pack";
	private final McLuaIdeWindowApi.Context context;
	private final EditBox url, port;
	private int width = 480, height = 300, scroll;
	private boolean hosted = true, required, busy, disposed;
	private Path selected;
	private LuaServerPack.Status serverStatus;
	private ScriptManager.PackUpload job;
	private String status = "正在读取服务器资源包设置…";
	private record Row(String text, int color) { }

	LuaServerPackWindow(McLuaIdeWindowApi.Context context) {
		this.context = context;
		url = new EditBox(context.font(), 0, 0, width, 20, Component.literal(LuaUiText.text("玩家可访问的资源包下载地址")));
		url.setMaxLength(2048); url.setHint(Component.literal(LuaUiText.text("http://服务器公网地址:8080")));
		port = new EditBox(context.font(), 0, 0, 75, 20, Component.literal(LuaUiText.text("服务端下载端口")));
		port.setMaxLength(5); port.setValue("8080");
		load();
	}
	private void load() {
		busy = true;
		ScriptManager.serverResourcePack().whenComplete((value, error) -> context.minecraft().execute(() -> {
			if (disposed) return;
			busy = false;
			if (error != null) status = LuaUiText.text("读取失败：") + ScriptManager.message(error);
			else {
				serverStatus = value; hosted = value.settings().hosted(); required = value.settings().required();
				url.setValue(value.settings().url()); port.setValue(Integer.toString(value.settings().port()));
				status = !value.problem().isBlank() ? value.problem()
						: value.settings().enabled() ? "已启用：新玩家使用原版提示下载，已在线玩家已收到资源包请求" : "未启用服务器资源包";
			}
		}));
	}
	@Override public void resize(int width, int height) { this.width = width; this.height = height; layoutFields(); }
	private int footerTop() { return height - 26; }
	private void layoutFields() {
		url.setX(2); url.setY(82 - scroll); url.setWidth(Math.max(80, width - 4));
		port.setX(120); port.setY(160 - scroll); port.setWidth(Math.max(50, Math.min(100, width - 124)));
		url.active = port.active = !busy;
	}
	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		String file = selected == null ? serverStatus == null || serverStatus.fileName().isBlank() ? "尚未选择 ZIP" : "服务端：" + serverStatus.fileName() : selected.getFileName().toString();
		addWrapped(rows, "ZIP 文件：" + file, 0xFFBBDCF8);
		if (hosted) {
			addWrapped(rows, "下载地址填写玩家能访问的服务器地址和端口，例如 http://example.com:8080。不会自动配置公网映射或防火墙。", 0xFFFFCF77);
			addWrapped(rows, "应用后开启上述端口的 HTTP ZIP 下载服务；只公开资源包，不公开脚本、控制台或其他文件。", 0xFFFFCF77);
		} else addWrapped(rows, "外部托管：填写 ZIP 直链。上传只保存到游戏服务端，你仍需把相同 ZIP 发布到此地址；下载内容必须与 SHA-1 一致。", 0xFFFFCF77);
		if (required) addWrapped(rows, "强制加载：拒绝或下载失败的玩家可能被原版断开连接，请先验证下载地址。", 0xFFFF8793);
		if (serverStatus != null && !serverStatus.sha1().isBlank()) {
			addWrapped(rows, "大小：" + String.format(java.util.Locale.ROOT, "%.2f MiB", serverStatus.size() / 1048576.0), 0xFF92A9C4);
			addWrapped(rows, "SHA-1：" + serverStatus.sha1(), 0xFF92A9C4);
		}
		addWrapped(rows, job != null && !job.result.isDone() ? job.phase + " " + (job.total == 0 ? 0 : job.sent * 100 / job.total) + "%" : status, 0xFFEAF2FF);
		return rows;
	}
	private void addWrapped(List<Row> rows, String text, int color) {
		while (!text.isEmpty()) {
			String part = context.font().plainSubstrByWidth(text, Math.max(20, width - 8));
			if (part.isEmpty()) part = text.substring(0, 1);
			rows.add(new Row(part, color)); text = text.substring(part.length());
		}
	}
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Row> rows = rows();
		scroll = Math.clamp(scroll, 0, Math.max(0, 188 + rows.size() * 14 - footerTop())); layoutFields();
		graphics.enableScissor(0, 0, width, footerTop() - 2);
		graphics.text(context.font(), "原版客户端兼容 · 服务端保存并分发 · 仅管理员可配置", 2, 6 - scroll, 0xFF9EC7EE);
		button(graphics, "选择资源包 ZIP…", 2, 27 - scroll, width - 4, !busy, mouseX, mouseY);
		graphics.text(context.font(), hosted ? "玩家可访问的下载基础地址" : "公开资源包 ZIP 直链", 2, 64 - scroll, 0xFFCEDAEA);
		url.extractRenderState(graphics, mouseX, mouseY, 0);
		button(graphics, "[" + (hosted ? "✓" : " ") + "] 使用模组的 ZIP 下载服务", 2, 108 - scroll, width - 4, !busy, mouseX, mouseY);
		button(graphics, "[" + (required ? "✓" : " ") + "] 要求玩家接受服务器资源包", 2, 134 - scroll, width - 4, !busy, mouseX, mouseY);
		graphics.text(context.font(), LuaUiText.text("服务端下载端口"), 2, 166 - scroll, hosted ? 0xFFCEDAEA : 0xFF637489);
		port.active = !busy && hosted; port.extractRenderState(graphics, mouseX, mouseY, 0);
		for (int i = 0; i < rows.size(); i++) graphics.text(context.font(), rows.get(i).text, 2, 188 + i * 14 - scroll, rows.get(i).color);
		graphics.disableScissor();
		int cell = (width - 8) / 3;
		String action = job != null && !job.result.isDone() ? "上传 " + (job.total == 0 ? 0 : job.sent * 100 / job.total) + "%"
				: busy ? "请稍候…" : selected == null ? "应用现有 ZIP" : "上传并应用";
		button(graphics, action, 0, footerTop(), cell, !busy && (selected != null || serverStatus != null && !serverStatus.sha1().isBlank()), mouseX, mouseY);
		button(graphics, "停用资源包", cell + 4, footerTop(), cell, !busy && serverStatus != null, mouseX, mouseY);
		button(graphics, job != null && !job.result.isDone() ? "取消上传" : LuaUiText.text("关闭"), (cell + 4) * 2, footerTop(), width - (cell + 4) * 2, true, mouseX, mouseY);
	}
	private void button(GuiGraphicsExtractor graphics, String text, int x, int y, int buttonWidth, boolean enabled, int mouseX, int mouseY) {
		boolean hover = inside(mouseX, mouseY, x, y, buttonWidth, 22);
		graphics.fill(x, y, x + buttonWidth, y + 22, !enabled ? 0xFF1D2733 : hover ? 0xFF3E648A : 0xFF2A435E);
		graphics.centeredText(context.font(), context.font().plainSubstrByWidth(text, Math.max(1, buttonWidth - 6)), x + buttonWidth / 2, y + 7, enabled ? 0xFFEAF2FF : 0xFF637489);
	}
	private static boolean inside(double x, double y, int left, int top, int width, int height) { return x >= left && x < left + width && y >= top && y < top + height; }
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return false;
		if (event.y() >= footerTop()) {
			url.setFocused(false); port.setFocused(false);
			int cell = (width - 8) / 3;
			if (event.x() < cell) { if (!busy) apply(); }
			else if (event.x() < 2 * cell + 4) { if (!busy && serverStatus != null) disable(); }
			else if (job != null && !job.result.isDone()) { job.cancel(); status = "正在取消上传…"; }
			else context.close();
			return true;
		}
		if (event.y() < 0 || event.y() >= footerTop() - 2) return false;
		if (!busy && inside(event.x(), event.y(), 2, 27 - scroll, width - 4, 22)) { choose(); return true; }
		if (!busy && inside(event.x(), event.y(), 2, 108 - scroll, width - 4, 22)) {
			hosted = !hosted; url.setHint(Component.literal(hosted ? LuaUiText.text("http://服务器公网地址:8080") : "https://example.com/pack.zip")); return true;
		}
		if (!busy && inside(event.x(), event.y(), 2, 134 - scroll, width - 4, 22)) { required = !required; return true; }
		url.setFocused(false); port.setFocused(false);
		if (url.mouseClicked(event, doubleClick)) { url.setFocused(true); return true; }
		if (hosted && port.mouseClicked(event, doubleClick)) { port.setFocused(true); return true; }
		return true;
	}
	private void choose() {
		busy = true; status = "请选择 ZIP 资源包（最大 128 MiB，根目录包含 pack.mcmeta 与 assets）…";
		LuaZipFileChooser.choose(context.minecraft().gameDirectory.toPath().resolve("resourcepacks"))
				.whenComplete((file, error) -> context.minecraft().execute(() -> {
					if (disposed) return;
					busy = false;
					if (error != null) status = "选择失败：" + ScriptManager.message(error);
					else if (file == null) status = "已取消选择，服务器设置未改变";
					else { selected = file; status = "已选择；填写下载地址后点击“上传并应用”"; }
				}));
	}
	private void apply() {
		LuaServerPack.Settings settings;
		try { settings = new LuaServerPack.Settings(true, hosted, Integer.parseInt(port.getValue()), url.getValue(), required); }
		catch (Exception error) { status = "设置无效：" + ScriptManager.message(error); return; }
		busy = true;
		if (selected == null) { publish(settings); return; }
		job = ScriptManager.uploadServerResourcePack(selected);
		ScriptManager.PackUpload currentJob = job;
		job.result.whenComplete((value, error) -> context.minecraft().execute(() -> {
			if (disposed) return;
			if (error != null || currentJob.cancelled()) { busy = false; status = error == null ? "已取消，资源包未发布" : "上传失败：" + ScriptManager.message(error); return; }
			serverStatus = value; selected = null; publish(settings);
		}));
	}
	private void publish(LuaServerPack.Settings settings) {
		status = "正在保存并向在线玩家发送原版资源包请求…";
		ScriptManager.setServerResourcePack(settings).whenComplete((value, error) -> context.minecraft().execute(() -> {
			if (disposed) return;
			busy = false;
			if (error != null) status = "应用失败：" + ScriptManager.message(error);
			else { serverStatus = value; status = settings.enabled() ? "已设为服务器资源包；在线玩家已收到请求，重新进入也会自动提示加载" : "已停用本模组设置的服务器资源包和下载服务"; }
		}));
	}
	private void disable() {
		var old = serverStatus.settings(); busy = true;
		publish(new LuaServerPack.Settings(false, old.hosted(), old.port(), old.url(), old.required()));
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll = Math.max(0, scroll - (int) Math.round(vertical * 25)); layoutFields(); return true;
	}
	@Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return url.isFocused() ? url.mouseDragged(event, dx, dy) : port.isFocused() && port.mouseDragged(event, dx, dy);
	}
	@Override public boolean mouseReleased(MouseButtonEvent event) { return url.mouseReleased(event) || port.mouseReleased(event); }
	@Override public boolean keyPressed(KeyEvent event) {
		return url.isFocused() ? url.keyPressed(event) : port.isFocused() && port.keyPressed(event);
	}
	@Override public boolean charTyped(CharacterEvent event) {
		return url.isFocused() ? url.charTyped(event) : port.isFocused() && port.charTyped(event);
	}
	@Override public void focusChanged(boolean focus) {
		if (!focus) { url.setFocused(false); port.setFocused(false); }
	}
	@Override public void disposed() { disposed = true; if (job != null) job.cancel(); }
}
