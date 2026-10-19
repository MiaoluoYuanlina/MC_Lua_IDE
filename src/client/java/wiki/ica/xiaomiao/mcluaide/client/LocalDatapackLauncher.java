package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Opens an IDE-managed datapack path only when its server is in this client process. */
final class LocalDatapackLauncher {
	private LocalDatapackLauncher() { }

	static void open(Minecraft minecraft, String entry, boolean directory, boolean terminal) throws IOException {
		MinecraftServer server = minecraft.getSingleplayerServer();
		LocalWorkspaceLauncher.requireLocalServer(server != null, "数据包");
		Path root = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
		Path target = entry == null || entry.isEmpty() ? LocalWorkspaceLauncher.existingTarget(root, root, true)
				: resolve(root, entry, directory);
		Path workingDirectory = directory ? target : target.getParent();
		if (terminal) openTerminal(workingDirectory);
		else LocalWorkspaceLauncher.openExplorer(target, directory);
	}

	static Path resolve(Path root, String entry, boolean directory) throws IOException {
		return LocalWorkspaceLauncher.resolveManagedPack(root, entry, directory, "数据包");
	}

	private static void openTerminal(Path directory) throws IOException {
		String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (system.startsWith("windows")) {
			try {
				new ProcessBuilder("wt.exe", "-d", directory.toString()).start();
			} catch (IOException unavailable) {
				String quotedPath = "'" + directory.toString().replace("'", "''") + "'";
				new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
						"Start-Process -FilePath 'cmd.exe' -WorkingDirectory " + quotedPath).start();
			}
		} else if (system.contains("mac")) {
			new ProcessBuilder("open", "-a", "Terminal", directory.toString()).start();
		} else {
			new ProcessBuilder("x-terminal-emulator").directory(directory.toFile()).start();
		}
	}
}
