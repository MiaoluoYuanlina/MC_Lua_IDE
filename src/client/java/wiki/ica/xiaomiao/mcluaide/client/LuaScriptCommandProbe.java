package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.brigadier.suggestion.Suggestions;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import wiki.ica.xiaomiao.mcluaide.network.LuaDebuggerState;
import wiki.ica.xiaomiao.mcluaide.server.ServerScriptManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** Explicit opt-in only. Uses a new world and synthetic scripts inside build/script-command-probe. */
final class LuaScriptCommandProbe {
	private static final String SOURCE = "MC.Event.onServerTick(function(event) end)\nprint('command_path_probe')\n";
	private static int stage;
	private static long started;
	private static CompletableFuture<?> request;
	private static CompletableFuture<List<Suggestions>> completions;
	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime(); stage = 1;
				client.options.renderDistance().set(4); client.options.simulationDistance().set(4);
				CreateWorldScreen.testWorld(client, () -> { throw new AssertionError("Test world cancelled"); }); return;
			}
			if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(120)) throw new AssertionError("Command probe timed out at " + stage);
			if (stage == 1) {
				if (!(client.screen instanceof CreateWorldScreen create)) return;
				create.getUiState().setName("script_commands_" + System.currentTimeMillis());
				create.getUiState().setAllowCommands(true); create.getUiState().setGenerateStructures(false);
				Method method = CreateWorldScreen.class.getDeclaredMethod("onCreate"); method.setAccessible(true); stage = 2; method.invoke(create); return;
			}
			if (stage == 2) {
				if (client.player == null || !ScriptManager.isServerAvailable() || ClientCommands.getActiveDispatcher() == null) return;
				request = ScriptManager.checkAccess().thenCompose(ignored -> ScriptManager.delete("command_tools/new"))
						.thenCompose(ignored -> ScriptManager.save("command_root", SOURCE))
						.thenCompose(ignored -> ScriptManager.save("command_tools/math", SOURCE))
						.thenCompose(ignored -> ScriptManager.save("command_tools/deep/main", SOURCE)); stage = 3; return;
			}
			if (stage == 3) {
				if (!ready()) return;
				require(client.getConnection().getCommands().getRoot().getChild("lua") != null, "Lua command not merged into chat dispatcher");
				completions = CompletableFuture.completedFuture(new ArrayList<>());
				for (String query : List.of("lua run ", "lua testrun command_tools/", "lua run command_tools/deep/ma",
						"lua run main", "lua run \"command_tools/deep/ma", "lua run command_tools/math.l")) {
					completions = completions.thenCompose(results -> suggest(client, query).thenApply(found -> { results.add(found); return results; }));
				}
				stage = 4; return;
			}
			if (stage == 4) {
				if (!completions.isDone()) return; var results = completions.join();
				require(names(results.get(0)).containsAll(List.of("command_root", "command_tools/math", "command_tools/deep/main")), "Chat omitted nested scripts");
				require(names(results.get(1)).equals(List.of("command_tools/deep/main", "command_tools/math")), "testrun directory filtering failed: " + names(results.get(1)));
				require(names(results.get(2)).equals(List.of("command_tools/deep/main")), "Nested prefix filtering failed");
				require(names(results.get(3)).equals(List.of("command_tools/deep/main")), "Filename filtering failed");
				require(names(results.get(4)).equals(List.of("\"command_tools/deep/main\"")), "Quoted path completion failed");
				require(names(results.get(5)).equals(List.of("command_tools/math.lua")), "Extension completion failed");
				FabricClientCommandSource source = (FabricClientCommandSource)(Object) client.getConnection().getSuggestionsProvider();
				ClientCommands.getActiveDispatcher().execute("lua run command_tools/deep/main", source); stage = 5; return;
			}
			if (stage == 5) {
				var execution = ScriptManager.activeExecution(); if (execution == null) return;
				require(execution.name().equals("command_tools/deep/main"), "Client command lost subdirectory");
				request = ScriptManager.debuggerState(); stage = 6; return;
			}
			if (stage == 6) {
				if (!ready()) return; var state = LuaDebuggerState.decode((String) request.join());
				if (!state.running()) { request = ScriptManager.debuggerState(); return; }
				require(state.script().equals("command_tools/deep/main") && !state.enabled(), "Command failed to start nested script in normal mode");
				request = ScriptManager.stopServer().thenCompose(ignored -> ScriptManager.delete("command_tools/math"))
						.thenCompose(ignored -> ScriptManager.save("command_tools/new", SOURCE))
						.thenCompose(ignored -> suggest(client, "lua run command_tools/")); stage = 7; return;
			}
			if (stage == 7) {
				if (!ready()) return;
				require(names((Suggestions) request.join()).equals(List.of("command_tools/deep/main", "command_tools/new")), "Server file changes not reflected in chat suggestions");
				MinecraftServer server = client.getSingleplayerServer();
				request = CompletableFuture.runAsync(() -> {
					try { serverCommands(server); }
					catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
				}); stage = 8; return;
			}
			if (stage == 8) {
				if (!ready()) return;
				System.out.println("Lua script command real network probe passed: chat dispatcher, run/testrun, all nested scripts, prefix/basename, quotes, extensions, unquoted slash execution, normal mode, fresh create/delete list");
				stage = 99; client.stop();
			}
		} catch (Exception | AssertionError error) {
			stage = 99; throw new IllegalStateException("Lua script command real network probe failed", error);
		}
	}

	private static void serverCommands(MinecraftServer server) throws Exception {
		await(() -> ServerScriptManager.activeScriptName() == null);
		onServer(server, () -> {
			require(ServerScriptManager.activeScriptName() == null, "Client test left its runner active");
			ServerScriptManager.save("command_native/hold", SOURCE);
			ServerScriptManager.save("command_native/output", "MC.sleep(120); print('deferred-output'); MC.sleep(600)");
			ServerScriptManager.save("command_native/fail", "MC.sleep(50); error('native-script-error')");
			var dispatcher = server.getCommands().getDispatcher();
			var player = server.getPlayerList().getPlayers().getFirst();
			for (PermissionSet permission : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.ADMIN)) {
				CommandSourceStack fake = server.createCommandSourceStack().withSource(CommandSource.NULL)
						.withPermission(permission).withEntity(player);
				try {
					dispatcher.execute("lua run command_native/hold", fake);
					throw new AssertionError("Low permission or execute-as-player bypassed Lua access");
				} catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
				require(dispatcher.getCompletionSuggestions(dispatcher.parse("lua run command_native/", fake))
						.join().getList().isEmpty(), "Script names leaked to denied source");
			}
			CommandSourceStack console = server.createCommandSourceStack();
			require(dispatcher.execute("lua run command_native/hold.lua", console) == 1, "Native console run failed");
			require(!LuaDebuggerState.decode(ServerScriptManager.debuggerState()).enabled(), "Native run enabled debugger");
			require(dispatcher.execute("lua stop", console) == 1, "Native console stop failed");
			return null;
		});
		await(() -> ServerScriptManager.activeScriptName() == null);
		ServerLevel level = server.overworld();
		BlockPos pos = onServer(server, () -> server.getPlayerList().getPlayers().getFirst().blockPosition().above(5));
		BaseCommandBlock block = onServer(server, () -> {
			require(level.getBlockState(pos).isAir(), "Probe location is not empty");
			level.setBlock(pos, Blocks.COMMAND_BLOCK.defaultBlockState(), 3);
			return ((CommandBlockEntity) level.getBlockEntity(pos)).getCommandBlock();
		});
		try {
			onServer(server, () -> {
				block.setCommand("lua run command_native/hold"); block.performCommand(level);
				require(block.getSuccessCount() == 1, "Actual command block did not start Lua: " + block.getLastOutput());
				require(ServerScriptManager.activeScriptName().equals("command_native/hold"), "Block lost script path");
				require(!ServerScriptManager.stopOwnedBy(server.getPlayerList().getPlayers().getFirst().getUUID()), "Block script was incorrectly owned by a player");
				return null;
			});
			// LastOutput holds one line, not a history; the listening notice follows print().
			awaitOutput(server, block, "事件监听已启动");
			onServer(server, () -> { ServerScriptManager.stopActive(); return null; });
			await(() -> ServerScriptManager.activeScriptName() == null);
			nextTick(server, level);
			onServer(server, () -> {
				block.setCommand("execute as @p run lua run command_native/hold"); block.performCommand(level);
				require(block.getSuccessCount() == 1, "Command block execute-as lost provenance");
				return null;
			});
			onServer(server, () -> { ServerScriptManager.stopActive(); return null; });
			await(() -> ServerScriptManager.activeScriptName() == null);
			nextTick(server, level);
			onServer(server, () -> { block.setCommand("lua run command_native/output"); block.performCommand(level); return null; });
			awaitOutput(server, block, "deferred-output");
			await(() -> ServerScriptManager.activeScriptName() == null);
			nextTick(server, level);
			onServer(server, () -> { block.setCommand("lua run command_native/fail"); block.performCommand(level); return null; });
			awaitOutput(server, block, "native-script-error");
			await(() -> ServerScriptManager.activeScriptName() == null);
			nextTick(server, level);
			onServer(server, () -> {
				block.setTrackOutput(false); block.setCommand("lua run command_native/hold"); block.performCommand(level);
				require(block.getSuccessCount() == 1, "Output-disabled command block could not run Lua");
				return null;
			});
			Thread.sleep(150);
			require(onServer(server, () -> block.getLastOutput().getString().isEmpty()), "Disabled block output was written");
			onServer(server, () -> { ServerScriptManager.stopActive(); return null; });
			await(() -> ServerScriptManager.activeScriptName() == null);
			nextTick(server, level);
			onServer(server, () -> {
				block.setTrackOutput(true); block.setCommand("lua testrun command_native/hold"); block.performCommand(level);
				require(LuaDebuggerState.decode(ServerScriptManager.debuggerState()).enabled(), "Native testrun did not enable debugging");
				return null;
			});
			nextTick(server, level);
			onServer(server, () -> { block.setCommand("lua stop"); block.performCommand(level); require(block.getSuccessCount() == 1, "Block stop failed"); return null; });
			await(() -> ServerScriptManager.activeScriptName() == null);
			nextTick(server, level);
			onServer(server, () -> {
				block.setCommand("lua run ../secret"); block.performCommand(level);
				require(block.getSuccessCount() == 0 && ServerScriptManager.activeScriptName() == null, "Unsafe block path was accepted");
				return null;
			});
			nextTick(server, level);
			onServer(server, () -> {
				block.setCommand("lua run missing_native_script"); block.performCommand(level);
				require(block.getSuccessCount() == 0, "Missing script returned command success");
				return null;
			});
			var minecart = onServer(server, () -> {
				var cart = EntityType.COMMAND_BLOCK_MINECART.create(level, EntitySpawnReason.COMMAND);
				cart.setPos(pos.getX() + 2, pos.getY(), pos.getZ()); level.addFreshEntity(cart);
				cart.getCommandBlock().setCommand("lua run command_native/hold"); cart.getCommandBlock().performCommand(level);
				require(cart.getCommandBlock().getSuccessCount() == 1, "Command-block minecart could not run Lua");
				return cart;
			});
			onServer(server, () -> { ServerScriptManager.stopActive(); minecart.discard(); return null; });
			await(() -> ServerScriptManager.activeScriptName() == null);
			System.out.println("Native Lua server command probe passed: actual blocks/minecart, console, execute-as provenance, normal/debug mode, stop, delayed output/errors, output disabled, safe paths, low permissions and suggestion privacy");
		} finally {
			onServer(server, () -> { ServerScriptManager.stopActive(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); return null; });
			await(() -> ServerScriptManager.activeScriptName() == null);
		}
	}

	private static void nextTick(MinecraftServer server, ServerLevel level) throws Exception {
		// Vanilla permits one performCommand per block per game tick.
		long before = onServer(server, level::getGameTime);
		await(() -> {
			try { return onServer(server, level::getGameTime) > before; }
			catch (Exception error) { throw new RuntimeException(error); }
		});
	}
	private static void awaitOutput(MinecraftServer server, BaseCommandBlock block, String expected) throws Exception {
		try {
			await(() -> {
				try { return onServer(server, () -> block.getLastOutput() != null && block.getLastOutput().getString().contains(expected)); }
				catch (Exception error) { throw new RuntimeException(error); }
			});
		} catch (AssertionError error) {
			throw new AssertionError("Missing block output " + expected + "; actual="
					+ onServer(server, block::getLastOutput), error);
		}
	}
	private static <T> T onServer(MinecraftServer server, Callable<T> action) throws Exception {
		return server.submit(() -> {
			try { return action.call(); }
			catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
		}).get(5, TimeUnit.SECONDS);
	}
	private static void await(BooleanSupplier condition) throws Exception {
		long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > limit) throw new AssertionError("Native command probe timed out");
			Thread.sleep(10);
		}
	}
	private static CompletableFuture<Suggestions> suggest(Minecraft client, String input) {
		CompletableFuture<Suggestions> result = new CompletableFuture<>();
		// Server-backed vanilla suggestions clear their pending slot AFTER completing
		// its future. Queue the next query, never re-enter that completion callback.
		client.schedule(() -> {
			var commands = client.getConnection().getCommands();
			commands.getCompletionSuggestions(commands.parse(input, client.getConnection().getSuggestionsProvider()))
					.whenComplete((suggestions, error) -> {
						if (error == null) result.complete(suggestions); else result.completeExceptionally(error);
					});
		});
		return result;
	}
	private static boolean ready() { if (!request.isDone()) return false; request.join(); return true; }
	private static List<String> names(Suggestions suggestions) { return suggestions.getList().stream().map(item -> item.getText()).toList(); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
