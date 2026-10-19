package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.client.CameraType;
import wiki.ica.xiaomiao.mcluaide.network.*;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in end-to-end test with a new flat save inside an isolated build directory. */
final class LuaDebuggerGamePauseProbe {
	private static final String NAME = "freeze_probe", SOURCE = "local value = 1\nlocal answer = value + 2\nprint(answer)\n";
	private static int stage;
	private static long started, waitUntil, sequence;
	private static MinecraftServer server;
	private static CompletableFuture<?> request;
	private static CompletableFuture<ScriptManager.ExecutionResult> result;
	private static CompletableFuture<Sample> sample;
	private static Sample before;
	private static CompletableFuture<Vec3> pose;
	private static Vec3 bodyBefore, cameraBefore, localBody;
	private static float bodyYaw, bodyPitch;
	private record Sample(long time, int tick, boolean frozen) { }

	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); stage = 1;
				client.getLanguageManager().setSelected("zh_cn"); client.options.languageCode = "zh_cn";
				CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Probe world creation cancelled"); }); return;
			}
			if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(210)) throw new AssertionError("World pause probe timed out at " + stage);
			if (stage == 1) {
				if (!(client.screen instanceof CreateWorldScreen create)) return;
				create.getUiState().setName("debug_pause_probe_" + System.currentTimeMillis());
				create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
				Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true); stage = 2; method.invoke(create); return;
			}
			if (stage == 2) {
				if (client.player == null || client.getSingleplayerServer() == null || !ScriptManager.isServerAvailable()) return;
				server = client.getSingleplayerServer(); client.setScreen(new LuaDebugScreen(null));
				LuaDebuggerCamera.setEnabled(false);
				LuaBreakpointClient.store().clear(NAME); LuaBreakpointClient.store().toggle(NAME, 2, SOURCE);
				request = ScriptManager.checkAccess().thenCompose(ignored -> LuaBreakpointClient.prepareRun(NAME, SOURCE))
						.thenCompose(ignored -> ScriptManager.setDebuggerGamePause(true)); stage = 3; return;
			}
			if (stage == 3) {
				if (!ready()) return; start(); stage = 4; return;
			}
			var state = LuaBreakpointClient.state();
			if (stage == 4) {
				if (!paused(state)) return;
				require(state.line() == 2 && state.code().equals("local answer = value + 2"), "Incorrect runtime code");
				require(state.pauseGame(), "Server setting missing from client state");
				client.setScreen(new LuaDebugKeysScreen(new LuaDebugScreen(null)));
				sample = sample(); stage = 5; return;
			}
			if (stage == 5) {
				if (!sample.isDone()) return; before = sample.join(); require(before.frozen, "World not frozen at breakpoint");
				waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650); stage = 6; return;
			}
			if (stage == 6) {
				if (System.nanoTime() < waitUntil) return; sample = sample(); stage = 7; return;
			}
			if (stage == 7) {
				if (!sample.isDone()) return; Sample after = sample.join();
				require(after.frozen && after.time == before.time && after.tick > before.tick + 3, "World simulation not frozen or main thread/network blocked");
				require(client.level.tickRateManager().isFrozen(), "Vanilla freeze packet not synchronized to client");
				client.setScreen(null); request = ScriptManager.setDebuggerPlayerMovement(true); stage = 200; return;
			}
			if (stage == 200) {
				if (!ready()) return; pose = pose(client); stage = 201; return;
			}
			if (stage == 201) {
				if (!pose.isDone()) return; bodyBefore = pose.join(); client.options.keyUp.setDown(true);
				waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650); stage = 202; return;
			}
			if (stage == 202) {
				if (System.nanoTime() < waitUntil) return; client.options.keyUp.setDown(false); pose = pose(client); stage = 203; return;
			}
			if (stage == 203) {
				if (!pose.isDone()) return; Vec3 moved = pose.join();
				require(Math.abs(moved.x - bodyBefore.x) + Math.abs(moved.z - bodyBefore.z) > 0.1, "Movement-enabled player did not move while world frozen");
				request = ScriptManager.setDebuggerPlayerMovement(false); stage = 204; return;
			}
			if (stage == 204) {
				if (!ready() || state.allowPlayerMovement()) return; pose = pose(client); stage = 205; return;
			}
			if (stage == 205) {
				if (!pose.isDone()) return; bodyBefore = pose.join();
				client.options.keyUp.setDown(true); client.options.keyJump.setDown(true);
				client.player.connection.send(new ServerboundMovePlayerPacket.Pos(bodyBefore.add(0.2, 0, 0), true, false));
				waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(450); stage = 206; return;
			}
			if (stage == 206) {
				if (System.nanoTime() < waitUntil) return; releaseKeys(client); pose = pose(client); stage = 207; return;
			}
			if (stage == 207) {
				if (!pose.isDone()) return;
				require(pose.join().distanceToSqr(bodyBefore) < 0.000001, "Disabled movement accepted player input/raw movement packet");
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				client.setScreen(new LuaDebugKeysScreen(new LuaDebugScreen(null)));
				click(client, "movementToggle"); click(client, "cameraToggle"); stage = 208; return;
			}
			if (stage == 208) {
				if (!LuaDebuggerCamera.active() || !state.allowPlayerMovement()) return;
				require(client.getCameraEntity() != client.player && client.options.getCameraType() == CameraType.FIRST_PERSON, "Detached camera was not installed");
				cameraBefore = client.getCameraEntity().position(); localBody = client.player.position();
				bodyYaw = client.player.getYRot(); bodyPitch = client.player.getXRot();
				client.setScreen(null);
				Field dx = client.mouseHandler.getClass().getDeclaredField("accumulatedDX"); dx.setAccessible(true); dx.setDouble(client.mouseHandler, 100);
				Field dy = client.mouseHandler.getClass().getDeclaredField("accumulatedDY"); dy.setAccessible(true); dy.setDouble(client.mouseHandler, 40);
				Method turn = client.mouseHandler.getClass().getDeclaredMethod("turnPlayer", double.class); turn.setAccessible(true); turn.invoke(client.mouseHandler, 0.05);
				require(client.getCameraEntity().getYRot() != bodyYaw && client.player.getYRot() == bodyYaw && client.player.getXRot() == bodyPitch,
						"Vanilla mouse sensitivity/inversion path turned player instead of camera");
				client.options.keyUp.setDown(true); client.options.keyJump.setDown(true);
				waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(550); stage = 209; return;
			}
			if (stage == 209) {
				if (System.nanoTime() < waitUntil) return; releaseKeys(client);
				require(client.getCameraEntity().position().distanceToSqr(cameraBefore) > 0.1, "Free camera did not fly");
				require(client.player.position().distanceToSqr(localBody) < 0.000001, "Free camera moved the local player's body");
				require(client.player.input.keyPresses.equals(net.minecraft.world.entity.player.Input.EMPTY), "Camera controls leaked into player/vehicle input");
				Method attack = Minecraft.class.getDeclaredMethod("startAttack"); attack.setAccessible(true);
				require(Boolean.FALSE.equals(attack.invoke(client)), "Camera mode allowed attack");
				net.minecraft.client.Screenshot.grab(client.gameDirectory, "debug-free-camera.png", client.getMainRenderTarget(), 1, message -> System.out.println(message.getString()));
				pose = pose(client); stage = 210; return;
			}
			if (stage == 210) {
				if (!pose.isDone()) return;
				require(pose.join().distanceToSqr(bodyBefore) < 0.000001, "Free camera moved the authoritative player's body");
				request = ScriptManager.setDebuggerGamePause(false); stage = 211; return;
			}
			if (stage == 211) {
				if (!ready() || state.pauseGame()) return;
				require(LuaDebuggerCamera.active(), "Camera incorrectly requires whole-world freeze");
				sample = sample(); stage = 212; return;
			}
			if (stage == 212) {
				if (!sample.isDone()) return; before = sample.join();
				client.options.keyRight.setDown(true); waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650); stage = 213; return;
			}
			if (stage == 213) {
				if (System.nanoTime() < waitUntil) return; releaseKeys(client); sample = sample(); pose = pose(client); stage = 214; return;
			}
			if (stage == 214) {
				if (!sample.isDone() || !pose.isDone()) return;
				require(!sample.join().frozen && sample.join().time > before.time, "Camera froze the whole world without opting in");
				require(pose.join().distanceToSqr(bodyBefore) < 0.000001, "Unfrozen-world physics moved anchored body");
				client.setScreen(new LuaDebugKeysScreen(new LuaDebugScreen(null)));
				click(client, "cameraToggle");
				require(!LuaDebuggerCamera.active() && client.getCameraEntity() == client.player && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK,
						"Camera option-off failed to restore original view");
				click(client, "cameraToggle"); stage = 215; return;
			}
			if (stage == 215) {
				if (!LuaDebuggerCamera.active()) return;
				LuaBreakpointClient.control("continue"); stage = 216; return;
			}
			if (stage == 216) {
				if (!result.isDone() || state.running() || LuaDebuggerCamera.active()) return;
				require(client.getCameraEntity() == client.player && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "Continue did not return player view");
				request = ScriptManager.setDebuggerGamePause(true); stage = 217; return;
			}
			if (stage == 217) { if (!ready()) return; start(); stage = 218; return; }
			if (stage == 218) {
				if (!paused(state) || !LuaDebuggerCamera.active()) return;
				sequence = state.sequence(); client.screen.keyPressed(new KeyEvent(InputConstants.KEY_F10, 0, 0)); stage = 71; return;
			}
			if (stage == 71) {
				if (!paused(state) || state.sequence() <= sequence) return;
				require(state.line() == 3 && state.code().equals("print(answer)"), "Stepping while world frozen failed");
				Button toggle = (Button) field(client.screen, "pauseGameToggle");
				require(toggle.getMessage().getString().equals("断点暂停游戏：开"), "F12 toggle does not reflect shared setting");
				client.screen.mouseClicked(mouse(toggle.getX() + 3, toggle.getY() + 3), false);
				stage = 8; return;
			}
			if (stage == 8) {
				if (state.pauseGame() || state.gamePaused()) return;
				require(state.paused(), "Turning off game pause resumed Lua");
				sample = sample(); stage = 9; return;
			}
			if (stage == 9) {
				if (!sample.isDone()) return; before = sample.join(); require(!before.frozen, "Off did not release world freeze");
				waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650); stage = 91; return;
			}
			if (stage == 91) {
				if (System.nanoTime() < waitUntil) return; sample = sample(); stage = 10; return;
			}
			if (stage == 10) {
				if (!sample.isDone()) return; require(sample.join().time > before.time, "World did not advance after off");
				LuaIdeScreen ide = new LuaIdeScreen(null, ""); client.setScreen(ide);
				ide.setExtensionWindowOpen(LuaBreakpointWindow.ID, true);
				Map<?, LuaExtensionWindow> windows = windows(ide);
				LuaExtensionWindow frame = windows.values().stream().filter(window -> window.id().equals(LuaBreakpointWindow.ID)).findFirst().orElseThrow();
				LuaBreakpointWindow content = (LuaBreakpointWindow) field(frame, "content"); content.resize(288, 200);
				Method y = LuaBreakpointWindow.class.getDeclaredMethod("pauseToggleY"); y.setAccessible(true);
				content.mouseClicked(mouse(4, (int) y.invoke(content) + 3), false);
				Method motion = LuaBreakpointWindow.class.getDeclaredMethod("motionToggleY"); motion.setAccessible(true);
				content.mouseClicked(mouse(200, (int) motion.invoke(content) + 3), false);
				require(!LuaDebuggerCamera.enabled() && !LuaDebuggerCamera.active(), "IDE camera button did not restore player view");
				content.mouseClicked(mouse(200, (int) motion.invoke(content) + 3), false);
				require(LuaDebuggerCamera.enabled(), "IDE camera button did not enable preference");
				content.mouseClicked(mouse(4, (int) motion.invoke(content) + 3), false); stage = 11; return;
			}
			if (stage == 11) {
				if (!paused(state) || !state.pauseGame() || state.allowPlayerMovement()) return;
				// An explicit vanilla freeze while already frozen must relinquish debugger ownership.
				request = server.submit(() -> { server.tickRateManager().setFrozen(true); return true; })
						.thenCompose(ignored -> ScriptManager.setDebuggerPlayerMovement(true))
						.thenCompose(ignored -> ScriptManager.setDebuggerGamePause(false)); stage = 12; return;
			}
			if (stage == 12) {
				if (!ready()) return; sample = sample(); stage = 121; return;
			}
			if (stage == 121) {
				if (!sample.isDone()) return; require(sample.join().frozen, "Option off undid explicit vanilla freeze");
				request = ScriptManager.controlDebugger(state.id(), "stop"); stage = 13; return;
			}
			if (stage == 13) {
				if (!ready() || !result.isDone()) return;
				require(result.join().stopped(), "Stop failed while world frozen");
				sample = sample(); stage = 131; return;
			}
			if (stage == 131) {
				if (!sample.isDone()) return; require(sample.join().frozen, "Stop undid vanilla freeze");
				request = server.submit(() -> { server.tickRateManager().setFrozen(false); return true; })
						.thenCompose(ignored -> ScriptManager.setDebuggerGamePause(true)); stage = 14; return;
			}
			if (stage == 14) { if (!ready()) return; start(); stage = 15; return; }
			if (stage == 15) {
				if (!paused(state)) return;
				UUID owner = client.player.getUUID(); request = server.submit(() -> ServerScriptManager.stopOwnedBy(owner)); stage = 16; return;
			}
			if (stage == 16) {
				if (!ready()) return; sample = sample(); stage = 161; return;
			}
			if (stage == 161) {
				if (!sample.isDone()) return; require(!sample.join().frozen, "Owner disconnect cleanup left world frozen");
				request = ScriptManager.setDebuggerGamePause(false); stage = 17; return;
			}
			if (stage == 17) {
				if (!ready() || LuaDebuggerCamera.active()) return; LuaDebuggerCamera.setEnabled(false);
				require(client.getCameraEntity() == client.player && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "Stop/disconnect cleanup did not restore original view");
				System.out.println("Debugger real-world/network pause probe passed: actual code, shared F12/IDE settings, frozen time/live network, enabled movement, rejected disabled movement/raw packets, independent camera flight, vanilla mouse routing, stationary local/server body even with world running, interaction guard, option-off/continue/step/stop/disconnect view restoration, vanilla freeze ownership");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) {
			if (server != null) server.execute(() -> ServerScriptManager.shutdownDebugger(server));
			stage = 99; throw new IllegalStateException("Debugger real-world pause probe failed", error);
		}
	}
	private static boolean ready() { if (!request.isDone()) return false; request.join(); return true; }
	private static boolean paused(LuaDebuggerState state) {
		if (result != null && result.isDone()) throw new AssertionError("Script finished before pause: " + result.join());
		return state.paused() && state.gamePaused();
	}
	private static void start() { result = new CompletableFuture<>(); ScriptManager.start(NAME, SOURCE, ignored -> { }, result::complete); }
	private static CompletableFuture<Sample> sample() { return server.submit(() -> new Sample(server.overworld().getGameTime(), server.getTickCount(), server.tickRateManager().isFrozen())); }
	private static CompletableFuture<Vec3> pose(Minecraft client) { UUID id = client.player.getUUID(); return server.submit(() -> server.getPlayerList().getPlayer(id).position()); }
	private static void releaseKeys(Minecraft client) { client.options.keyUp.setDown(false); client.options.keyJump.setDown(false); client.options.keyRight.setDown(false); }
	private static void click(Minecraft client, String name) throws Exception { Button button = (Button) field(client.screen, name); client.screen.mouseClicked(mouse(button.getX() + 3, button.getY() + 3), false); }
	@SuppressWarnings("unchecked") private static Map<?, LuaExtensionWindow> windows(LuaIdeScreen ide) throws Exception { return (Map<?, LuaExtensionWindow>) field(ide, "extensionWindows"); }
	private static Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
	private static MouseButtonEvent mouse(double x, double y) { return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)); }
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
