package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import wiki.ica.xiaomiao.mcluaide.network.LuaLimits;
import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in real editor and Fabric packet splitting checks in a fresh, isolated test world. */
final class LuaLargeFileProbe {
	private static final String SOURCE = "-- 大文件中文测试与表情😀：保留每一行内容和换行符\n".repeat(50_000) + "local total = 0\n";
	private static final String JSON = "{\"text\":\"" + "中文😀".repeat(350_000) + "\"}";
	private static final String FULL = "--" + "中".repeat(LuaProtocol.MAX_SCRIPT_CHARS - 2);
	private static int stage, checks, ticks;
	private static long started;
	private static CompletableFuture<?> request;
	private static LuaIdeScreen ide;
	private static MultiLineEditBox editor;
	private static MultilineTextField field;
	private static LuaLimits originalLimits;
	private static ScriptManager.ScriptExecution deniedExecution;
	private static String edited;

	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); stage = 1;
				client.options.renderDistance().set(4); client.options.simulationDistance().set(4);
				CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("World creation cancelled"); }); return;
			}
			if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(240)) throw new AssertionError("Timed out at stage " + stage);
			if (stage == 1) {
				if (!(client.screen instanceof CreateWorldScreen create)) return;
				create.getUiState().setName("large_files_" + System.currentTimeMillis());
				create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
				Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true);
				stage = 2; method.invoke(create); return;
			}
			if (stage == 2) {
				if (client.player == null || !ScriptManager.isServerAvailable()) return;
				request = ScriptManager.checkAccess().thenCompose(ignored -> ScriptManager.save("large/nested/source", SOURCE))
						.thenCompose(ignored -> ScriptManager.read("large/nested/source")); stage = 3; return;
			}
			if (stage == 3) {
				if (!ready()) return;
				require(request.join().equals(SOURCE), "50,001 lines lost/truncated in actual network read/save");
				request = ScriptManager.getLimits(); stage = 4; return;
			}
			if (stage == 4) {
				if (!ready()) return; originalLimits = (LuaLimits) request.join();
				var values = originalLimits.toMap(); values.put("maxScriptChars", "100000");
				request = ScriptManager.saveLimits(LuaLimits.fromMap(values))
						.thenCompose(ignored -> ScriptManager.save("large/nested/source", SOURCE))
						.thenCompose(ignored -> ScriptManager.read("large/nested/source")); stage = 5; return;
			}
			if (stage == 5) {
				if (!ready()) return;
				require(request.join().equals(SOURCE), "Configured execution limit incorrectly restricts edit/save");
				deniedExecution = ScriptManager.startNormal("large/nested/source", SOURCE, ignored -> { }, ignored -> { }); stage = 6; return;
			}
			if (stage == 6) {
				if (deniedExecution.result() == null) return;
				require(deniedExecution.result().error() != null && deniedExecution.result().error().contains("100000"), "Runtime policy was bypassed");
				request = ScriptManager.saveLimits(originalLimits).thenCompose(ignored -> ScriptManager.save("large/full_capacity", FULL))
						.thenCompose(ignored -> ScriptManager.read("large/full_capacity")); stage = 7; return;
			}
			if (stage == 7) {
				if (!ready()) return;
				require(request.join().equals(FULL), "Exact 5m/15MiB Unicode network boundary lost contents");
				request = ScriptManager.createDatapack("large_probe")
						.thenCompose(ignored -> ScriptManager.saveDatapackFile("large_probe/data/large_probe/function/large.mcfunction", "# 中文命令函数\n".repeat(50_000)))
						.thenCompose(ignored -> ScriptManager.readDatapackFile("large_probe/data/large_probe/function/large.mcfunction"))
						.thenApply(value -> { require(value.equals("# 中文命令函数\n".repeat(50_000)), "Large mcfunction round-trip"); return value; })
						.thenCompose(ignored -> ScriptManager.saveDatapackFile("large_probe/data/large_probe/custom/large.json", JSON))
						.thenCompose(ignored -> ScriptManager.readDatapackFile("large_probe/data/large_probe/custom/large.json")); stage = 8; return;
			}
			if (stage == 8) {
				if (!ready()) return;
				require(request.join().equals(JSON), "Large data pack JSON round-trip");
				LuaResourcePackWorkspace.createPack("large_probe");
				String path = "large_probe/assets/large_probe/lang/zh_cn.json";
				LuaResourcePackWorkspace.createText(path, JSON);
				require(LuaResourcePackWorkspace.readText(path).equals(JSON), "Resource JSON over old 1MiB limit");
				LuaResourcePackWorkspace.saveText(path, JSON.replace("text", "value"));
				require(LuaResourcePackWorkspace.readText(path).equals(JSON.replace("text", "value")), "Resource JSON save/read");
				ide = new LuaIdeScreen(null, "large/nested/source"); client.setScreen(ide);
				set("manualOpen", false); set("manualProgress", 0F);
				stage = 9; return;
			}
			if (stage == 9) {
				if (!(boolean) get("sourceLoaded")) return;
				editor = (MultiLineEditBox) get("editor"); field = (MultilineTextField) invoke("textField");
				require(editor.getValue().equals(SOURCE), "Actual IDE initial load truncated");
				require(field.getLineCount() >= 50_001, "Display line count");
				editor.active = true; ide.setFocused(editor); field.setSelecting(false);
				field.seekCursor(Whence.ABSOLUTE, SOURCE.length());
				long begin = System.nanoTime();
				require(ide.charTyped(new CharacterEvent('p')), "Actual character input not handled");
				require(editor.getValue().equals(SOURCE + "p"), "Typing above old character limit lost input");
				System.out.println("Large file character input ms: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
				invoke("undo"); require(editor.getValue().equals(SOURCE), "Large undo loses source");
				invoke("redo"); require(editor.getValue().equals(SOURCE + "p"), "Large redo loses source");
				for (int i = 0; i < 15; i++) ide.charTyped(new CharacterEvent('r'));
				@SuppressWarnings("unchecked") Deque<String> history = (Deque<String>) get("undoHistory");
				require(history.stream().mapToLong(String::length).sum() <= LuaTextHistory.MAX_CHARS, "Actual IDE history memory unbounded");
				field.insertText("\nprint(total)\n"); edited = editor.getValue();
				require(edited.equals(SOURCE + "p" + "r".repeat(15) + "\nprint(total)\n"), "Large multiline paste lost source");
				ide.keyPressed(new KeyEvent(InputConstants.KEY_S, 0, 2)); stage = 10; return;
			}
			if (stage == 10) {
				if (!(String.valueOf(get("status")).startsWith("已保存"))) return;
				request = ScriptManager.read("large/nested/source"); stage = 11; return;
			}
			if (stage == 11) {
				if (!ready()) return;
				require(request.join().equals(edited), "Actual Ctrl+S save changed/truncated edited source");
				if (++ticks < 5) return;
				System.out.println("Large file real game probe passed: " + checks + " checks; 50,001-line edit/paste/undo/redo/Ctrl+S, 5m Unicode split-packet round-trip, data/resource packs, preserved runtime policy");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) {
			int failedStage = stage; stage = 99; client.stop();
			throw new IllegalStateException("Large file real game probe failed at stage " + failedStage, error);
		}
	}
	private static boolean ready() { if (!request.isDone()) return false; request.join(); return true; }
	private static Object get(String name) throws Exception { Field value = LuaIdeScreen.class.getDeclaredField(name); value.setAccessible(true); return value.get(ide); }
	private static void set(String name, Object value) throws Exception { Field field = LuaIdeScreen.class.getDeclaredField(name); field.setAccessible(true); field.set(ide, value); }
	private static Object invoke(String name) throws Exception { Method method = LuaIdeScreen.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(ide); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
