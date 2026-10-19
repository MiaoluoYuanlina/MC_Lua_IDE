package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Detached client camera. No spectator mode, player teleport, or abilities changes. */
public final class LuaDebuggerCamera {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/debug-view.properties");
	private static boolean enabled = load(), pending;
	private static int epoch;
	private static long lastStateAt;
	private static String suspendedStop = "";
	private static String cameraStop = "";
	private static RemotePlayer camera;
	private static LocalPlayer owner;
	private static Entity previousCamera;
	private static CameraType previousType;
	private LuaDebuggerCamera() { }
	static boolean enabled() { return enabled; }
	public static boolean active() { return camera != null; }
	static String label() { return "暂停时自由摄像机：" + (enabled ? "开" : "关") + (active() ? " · 已进入" : pending ? " · 等待服务端" : ""); }
	private static boolean load() {
		if (!Files.isRegularFile(FILE)) return false;
		try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Properties settings = new Properties(); settings.load(reader);
			return Boolean.parseBoolean(settings.getProperty("freeCameraOnPause", "false"));
		} catch (IOException error) { McLuaIdeMod.LOGGER.warn("Could not load debugger camera preference", error); return false; }
	}
	static void setEnabled(boolean value) {
		enabled = value; suspendedStop = "";
		try {
			Properties settings = new Properties(); settings.setProperty("freeCameraOnPause", Boolean.toString(value));
			Files.createDirectories(FILE.getParent()); Path temporary = FILE.resolveSibling("debug-view.properties.tmp");
			try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { settings.store(writer, "Client-local Lua debugger view options"); }
			try { Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException error) { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING); }
		} catch (IOException error) { LuaBreakpointClient.notice("摄像机选项已生效，但保存失败：" + error.getMessage()); }
		synchronize(Minecraft.getInstance());
	}
	static void stateUpdated() { lastStateAt = System.nanoTime(); synchronize(Minecraft.getInstance()); }
	private static boolean wanted(Minecraft client) {
		var state = LuaBreakpointClient.state();
		return enabled && client.player != null && client.level != null && client.player.isAlive()
				&& ScriptManager.isServerAvailable() && state.running() && state.enabled() && state.paused()
				&& System.nanoTime() - lastStateAt < 2_000_000_000L;
	}
	static void synchronize(Minecraft client) {
		var state = LuaBreakpointClient.state();
		String stop = state.id() + ":" + state.sequence();
		if (!wanted(client)) { stop(client, true); return; }
		if (camera != null) {
			if (owner != client.player || camera.level() != client.level || client.getCameraEntity() != camera) {
				suspendedStop = stop; stop(client, true);
				return;
			}
			if (cameraStop.equals(stop)) return;
			// A single step releases the server anchor; acquire it again for the new stop.
			stop(client, true);
		}
		if (pending || suspendedStop.equals(stop)) return;
		// Do not commandeer another mod's camera or vanilla spectator target.
		if (client.getCameraEntity() != client.player) { suspendedStop = stop; return; }
		pending = true; int token = ++epoch; LocalPlayer expectedPlayer = client.player;
		ScriptManager.setDebuggerCamera(true).whenComplete((allowed, error) -> client.execute(() -> {
			if (token != epoch) return; // stop() has already sent the ordered release request.
			pending = false;
			if (error != null || !Boolean.TRUE.equals(allowed)) {
				suspendedStop = stop;
				LuaBreakpointClient.notice("自由摄像机未开启：" + (error == null ? "脚本已离开暂停状态" : ScriptManager.message(error))); return;
			}
			if (!wanted(client) || client.player != expectedPlayer || client.getCameraEntity() != client.player) {
				ScriptManager.setDebuggerCamera(false).exceptionally(failure -> false); return;
			}
			owner = client.player; previousCamera = client.getCameraEntity(); previousType = client.options.getCameraType(); cameraStop = stop;
			camera = new RemotePlayer(client.level, owner.getGameProfile());
			Vec3 eye = owner.getEyePosition();
			camera.snapTo(eye.x, eye.y - camera.getEyeHeight(), eye.z, owner.getYRot(), owner.getXRot());
			client.options.setCameraType(CameraType.FIRST_PERSON); client.setCameraEntity(camera);
		}));
	}
	static void tick(Minecraft client) {
		synchronize(client);
		if (camera == null) return;
		camera.setOldPosAndRot();
		if (client.screen != null || client.isPaused() || !client.mouseHandler.isMouseGrabbed()) return;
		var keys = client.options;
		double forward = (keys.keyUp.isDown() ? 1 : 0) - (keys.keyDown.isDown() ? 1 : 0);
		double right = (keys.keyRight.isDown() ? 1 : 0) - (keys.keyLeft.isDown() ? 1 : 0);
		double up = (keys.keyJump.isDown() ? 1 : 0) - (keys.keyShift.isDown() ? 1 : 0);
		double yaw = Math.toRadians(camera.getYRot());
		Vec3 move = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot()).scale(forward)
				.add(-Math.cos(yaw) * right, up, -Math.sin(yaw) * right);
		if (move.lengthSqr() > 1) move = move.normalize();
		camera.setPos(camera.position().add(move.scale(keys.keySprint.isDown() ? 1.5 : 0.5)));
	}
	public static boolean suppressPlayerMovement() {
		var state = LuaBreakpointClient.state();
		Minecraft client = Minecraft.getInstance();
		return active() || state.gamePaused() && !state.allowPlayerMovement() && client.level != null
				&& client.level.tickRateManager().isFrozen() && ScriptManager.isServerAvailable()
				&& System.nanoTime() - lastStateAt < 2_000_000_000L;
	}
	/** Called with vanilla's sensitivity, inversion and smoothing already applied. */
	public static void turn(LocalPlayer player, double x, double y) {
		if (camera != null && player == owner) camera.turn(x, y); else player.turn(x, y);
	}
	static void stop(Minecraft client, boolean notifyServer) {
		boolean release = camera != null || pending;
		if (!release) return;
		epoch++; pending = false;
		if (camera != null) {
			boolean ownView = client.getCameraEntity() == camera;
			if (ownView) {
				Entity restore = previousCamera != null && !previousCamera.isRemoved() && previousCamera.level() == client.level
						? previousCamera : client.player;
				client.setCameraEntity(restore);
			}
			if (previousType != null && (ownView || client.getCameraEntity() == client.player)) client.options.setCameraType(previousType);
		}
		camera = null; owner = null; previousCamera = null; previousType = null; cameraStop = "";
		if (notifyServer && ScriptManager.isServerAvailable()) ScriptManager.setDebuggerCamera(false).exceptionally(error -> false);
	}
	static void disconnected() { stop(Minecraft.getInstance(), false); epoch++; suspendedStop = ""; lastStateAt = 0; }
	static void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (!active() || client.screen != null || client.options.hideGui) return;
		renderPanel(graphics, LuaHudLayout.current().bounds(LuaHudLayout.Panel.CAMERA, graphics.guiWidth(), graphics.guiHeight()));
	}
	static void renderPanel(net.minecraft.client.gui.GuiGraphicsExtractor graphics, LuaHudLayout.Rect bounds) {
		graphics.nextStratum();
		Minecraft client = Minecraft.getInstance();
		int width = bounds.width(), x = bounds.x(), y = bounds.y();
		graphics.enableScissor(x, y, bounds.right(), bounds.bottom());
		try {
			graphics.fill(x, y, x + width, bounds.bottom(), LuaHudLayout.current().backgroundColor(LuaHudLayout.Panel.CAMERA));
			graphics.text(client.font, client.font.plainSubstrByWidth(active() ? "自由摄像机 · 玩家原地不动 · 继续/停止后返回"
					: "自由摄像机提示 · 未激活（仅预览）", width - 12), x + 6, y + 4, 0xFF9DDCFF);
			graphics.text(client.font, client.font.plainSubstrByWidth("移动键飞行 · 跳跃上升 · 潜行下降 · 疾跑加速 · F12 设置", width - 12), x + 6, y + 16, 0xFFADBCCF);
		} finally { graphics.disableScissor(); }
	}
	public static void clearWorldActions(Minecraft client) {
		if (!active()) return;
		for (var key : new net.minecraft.client.KeyMapping[]{client.options.keyAttack, client.options.keyUse,
				client.options.keyPickItem, client.options.keyDrop, client.options.keySwapOffhand,
				client.options.keyTogglePerspective}) {
			key.setDown(false); while (key.consumeClick()) { }
		}
	}
}
