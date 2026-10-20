package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Administrator-only transport and non-interactive breakpoint HUD. */
final class LuaBreakpointClient {
	private static LuaBreakpointStore store;
	private static Path sessionPath;
	private static LuaDebuggerState state = LuaDebuggerState.inactive();
	private static boolean showInfo, pollPending, dirty, controlPending, pauseSettingPending, movementSettingPending;
	private static long nextPoll, changedAt;
	private static int connection;
	private static CompletableFuture<Void> syncTail = CompletableFuture.completedFuture(null);
	private static String notice = "";
	static LuaDebuggerState state() { return state; }
	static void notice(String value) { notice = value; }
	static boolean movementPending() { return movementSettingPending; }
	static String movementLabel() { return "暂停时允许玩家移动：" + (movementSettingPending ? "正在设置…" : state.allowPlayerMovement() ? "开" : "关"); }
	static void setAllowPlayerMovement(boolean enabled) {
		if (movementSettingPending) return;
		if (!ScriptManager.isServerAvailable()) { notice = "无法设置：未连接可用的模组服务器"; return; }
		movementSettingPending = true; int epoch = connection;
		ScriptManager.setDebuggerPlayerMovement(enabled).whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
			if (epoch != connection) return;
			movementSettingPending = false;
			if (error != null) notice = "玩家移动设置失败：" + ScriptManager.message(error); else accept(data);
			nextPoll = 0;
		}));
	}
	static boolean showInfo() { return showInfo; }
	static void setShowInfo(boolean value) { showInfo = value; nextPoll = 0; }
	static boolean pauseGamePending() { return pauseSettingPending; }
	static String pauseGameLabel() {
		return "断点暂停游戏：" + (pauseSettingPending ? "正在设置…" : state.pauseGame() ? "开" : "关");
	}
	static void setPauseGame(boolean enabled) {
		if (pauseSettingPending) return;
		if (!ScriptManager.isServerAvailable()) { notice = "无法设置：未连接可用的模组服务器"; return; }
		pauseSettingPending = true;
		int epoch = connection;
		ScriptManager.setDebuggerGamePause(enabled).whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
			if (epoch != connection) return;
			pauseSettingPending = false;
			if (error != null) notice = "断点暂停游戏设置失败：" + ScriptManager.message(error);
			else accept(data);
			nextPoll = 0;
		}));
	}
	static String status() {
		if (!notice.isEmpty()) return notice;
		if (!state.running()) return "当前无运行中的 Lua 脚本";
		if (!state.enabled()) return "普通运行未启用断点；请使用 IDE 运行或 /lua testrun";
		return state.paused() ? "已暂停 · " + state.source() + ".lua:" + state.line() + " · " + state.reason()
				: "调试运行 · " + state.script() + ".lua · " + state.breakpointCount() + " 个断点";
	}
	static LuaBreakpointStore store() {
		Minecraft client = Minecraft.getInstance();
		var server = client.getSingleplayerServer();
		String scope = server != null ? "world:" + server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.toAbsolutePath().normalize() : client.getCurrentServer() != null
				? "server:" + client.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "local";
		if (client.player != null) scope += ":player:" + client.player.getUUID();
		Path file = new LuaIdeSession(FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide"), scope).file;
		if (!file.equals(sessionPath)) {
			sessionPath = file; store = new LuaBreakpointStore(file);
			dirty = true; changedAt = System.nanoTime();
		}
		return store;
	}
	static void changed() { dirty = true; changedAt = System.nanoTime(); }
	static CompletableFuture<Void> prepareRun(String name, String source) {
		store().source(name, source); changed();
		return synchronize();
	}
	private static CompletableFuture<Void> synchronize() {
		LuaBreakpointStore current = store(); current.save();
		String json = current.config().encode(); dirty = false;
		int epoch = connection;
		syncTail = syncTail.handle((ignored, failure) -> null).thenCompose(ignored -> {
			if (epoch != connection) return CompletableFuture.failedFuture(new IllegalStateException("连接已关闭"));
			return ScriptManager.setBreakpoints(json);
		});
		return syncTail;
	}
	static void tick(Minecraft client) {
		if (client.player == null || !ScriptManager.isServerAvailable()) return;
		boolean watching = showInfo || client.screen instanceof LuaIdeScreen || client.screen instanceof ImGuiIdeScreen || client.screen instanceof LuaDebugScreen || client.screen instanceof LuaDebugKeysScreen || client.screen instanceof LuaHudLayoutScreen
				|| ScriptManager.activeExecution() != null || LuaDebuggerCamera.enabled();
		if (!watching) return;
		store();
		if (dirty && System.nanoTime() - changedAt > 300_000_000L) {
			int epoch = connection;
			synchronize().whenComplete((ignored, error) -> client.execute(() -> {
				if (epoch == connection && error != null) notice = "断点同步失败：" + ScriptManager.message(error);
			}));
		}
		if (!pollPending && System.nanoTime() >= nextPoll) poll();
	}
	private static void poll() {
		pollPending = true; nextPoll = System.nanoTime() + 200_000_000L;
		int epoch = connection;
		ScriptManager.debuggerState().whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
			if (epoch != connection) return;
			pollPending = false;
			if (error != null) { notice = "调试读取失败：" + ScriptManager.message(error); nextPoll = System.nanoTime() + 5_000_000_000L; return; }
			accept(data);
		}));
	}
	private static void accept(String data) {
		try {
			LuaDebuggerState value = LuaDebuggerState.decode(data);
			if (value == null || value.stack() == null || value.stack().size() > 24 || value.code() == null || value.code().length() > 8192) throw new IllegalArgumentException();
			if (value.paused() && (!state.paused() || value.sequence() != state.sequence() || !value.id().equals(state.id())))
				LuaDebugHud.refresh();
			state = value; notice = ""; LuaDebuggerCamera.stateUpdated();
		} catch (RuntimeException invalid) { notice = "调试状态格式无效"; }
	}
	static void control(String command) {
		if (controlPending || !ScriptManager.isServerAvailable()) return;
		controlPending = true;
		int epoch = connection;
		// Refresh first: shortcuts also work after a server-side /lua testrun and never target an old execution.
		ScriptManager.debuggerState().thenCompose(data -> {
			LuaDebuggerState current = LuaDebuggerState.decode(data);
			if (!current.running() || !current.enabled())
				return CompletableFuture.failedFuture(new IllegalStateException("请先在 IDE 调试运行，或使用 /lua testrun"));
			String operation = command.equals("toggle") ? current.paused() ? "continue" : "pause" : command;
			return ScriptManager.controlDebugger(current.id(), operation);
		}).whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
			if (epoch != connection) return;
			controlPending = false;
			if (error != null) notice = "调试操作失败：" + ScriptManager.message(error);
			else accept(data);
			nextPoll = 0;
		}));
	}
	static boolean keyPressed(KeyEvent event) {
		var action = LuaDebugKeyBindings.match(event);
		if (action == null || action.editorOnly) return false;
		control(action.command); return true;
	}
	static void disconnected() {
		LuaDebuggerCamera.disconnected();
		LuaDebugKeyBindings.disconnected();
		connection++; state = LuaDebuggerState.inactive(); store = null; sessionPath = null;
		pollPending = false; controlPending = false; pauseSettingPending = false; movementSettingPending = false; dirty = false; notice = ""; showInfo = false;
		syncTail = CompletableFuture.completedFuture(null); nextPoll = 0;
	}
	static void render(GuiGraphicsExtractor graphics, DeltaTracker tick) {
		Minecraft client = Minecraft.getInstance();
		if (!showInfo || client.player == null || client.options.hideGui || client.screen != null) return;
		renderPanel(graphics, LuaHudLayout.current().bounds(LuaHudLayout.Panel.BREAKPOINTS,
				graphics.guiWidth(), graphics.guiHeight()), false);
	}
	static void renderPanel(GuiGraphicsExtractor graphics, LuaHudLayout.Rect bounds, boolean preview) {
		graphics.nextStratum();
		graphics.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
		try { renderContents(graphics, bounds, preview); } finally { graphics.disableScissor(); }
	}
	private static void renderContents(GuiGraphicsExtractor graphics, LuaHudLayout.Rect bounds, boolean preview) {
		Minecraft client = Minecraft.getInstance();
		int width = bounds.width(), left = bounds.x(), top = bounds.y(), height = bounds.height();
		boolean showCode = state.paused() && !state.code().isEmpty();
		int codeHeight = showCode ? 12 : 0;
		int count = state.paused() ? Math.min(state.stack().size(), Math.max(0, (height - 65 - codeHeight - (preview ? 12 : 0)) / 12)) : 0;
		graphics.fill(left, top, left + width, top + height, LuaHudLayout.current().backgroundColor(LuaHudLayout.Panel.BREAKPOINTS));
		graphics.fill(left, top, left + 2, top + height, state.paused() ? 0xFFFFC65C : 0xFF85B9EE);
		graphics.text(client.font, client.font.plainSubstrByWidth(preview && !showInfo
				? "Lua 断点 · 未开启（仅预览）" : "Lua 断点 · F12 设置", width - 18), left + 9, top + 8, 0xFFDAE5F4);
		graphics.text(client.font, client.font.plainSubstrByWidth(status(), width - 18), left + 9, top + 22,
				state.paused() ? 0xFFFFD178 : 0xFFADBBCD);
		if (showCode) graphics.text(client.font, client.font.plainSubstrByWidth("代码：" + state.code().strip(), width - 18), left + 9, top + 36, 0xFFA9E9CE);
		graphics.text(client.font, client.font.plainSubstrByWidth(LuaDebugKeyBindings.runHint(), width - 18), left + 9, top + 36 + codeHeight, 0xFFA8B4C6);
		graphics.text(client.font, client.font.plainSubstrByWidth(LuaDebugKeyBindings.stepHint(), width - 18), left + 9, top + 48 + codeHeight, 0xFFA8B4C6);
		for (int index = 0; index < count; index++) {
			var frame = state.stack().get(index);
			String label = (index == 0 ? "→ " : "   ") + frame.source() + ".lua:" + frame.line() + " · " + frame.name();
			graphics.text(client.font, client.font.plainSubstrByWidth(label, width - 18), left + 9, top + 62 + codeHeight + index * 12,
					index == 0 ? 0xFFFFCE75 : 0xFFC0C9D7);
		}
	}
}
