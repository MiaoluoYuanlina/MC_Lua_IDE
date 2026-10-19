package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import wiki.ica.xiaomiao.mcluaide.network.LuaScriptNames;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Validates actual local workspace paths before opening an OS file manager. */
final class LocalWorkspaceLauncher {
	private LocalWorkspaceLauncher() { }

	static void openLua(Minecraft minecraft, String entry, boolean directory) throws IOException {
		requireLocalServer(minecraft.getSingleplayerServer() != null, "Lua 脚本");
		Path root = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/scripts").toAbsolutePath().normalize();
		openExplorer(resolveLua(root, entry, directory), directory);
	}

	static void openResourcePack(String entry, boolean directory) throws IOException {
		Path root = LuaResourcePackWorkspace.root();
		Path target = entry == null || entry.isEmpty() ? existingTarget(root, root, true)
				: resolveManagedPack(root, entry, directory, "资源包");
		openExplorer(target, directory);
	}

	static void requireLocalServer(boolean local, String kind) throws IOException {
		if (!local) throw new IOException("远程服务器的文件不在本机；请到服务器主机上打开" + kind + "目录");
	}

	static Path resolveLua(Path root, String entry, boolean directory) throws IOException {
		root = root.toAbsolutePath().normalize();
		if (entry == null || entry.isEmpty()) return existingTarget(root, root, true);
		if (!LuaScriptNames.isValid(entry)) throw new IOException("无效的 Lua 脚本路径");
		return existingTarget(root, root.resolve(entry + (directory ? "" : ".lua")), directory);
	}

	static Path resolveManagedPack(Path root, String entry, boolean directory, String kind) throws IOException {
		root = root.toAbsolutePath().normalize();
		if (entry == null || entry.isBlank() || entry.length() > 240 || entry.contains("\\"))
			throw new IOException("无效的" + kind + "路径");
		String[] parts = entry.split("/", -1);
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 64 || part.equals(".") || part.equals("..")
					|| !part.matches("[a-z0-9_.-]+")) throw new IOException("无效的" + kind + "路径：" + entry);
		}
		Path pack = root.resolve("mc_lua_ide_edit_" + parts[0]).normalize();
		if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException(kind + "不存在：" + parts[0]);
		Path target = pack;
		for (int index = 1; index < parts.length; index++) target = target.resolve(parts[index]);
		return existingTarget(root, target, directory);
	}

	static Path existingTarget(Path root, Path target, boolean directory) throws IOException {
		root = root.toAbsolutePath().normalize(); target = target.toAbsolutePath().normalize();
		if (!target.startsWith(root)) throw new IOException("路径超出当前工作目录");
		for (Path current = target; current != null && current.startsWith(root); current = current.getParent())
			if (Files.isSymbolicLink(current)) throw new IOException("不能打开符号链接的工作目录路径");
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
				|| (directory ? !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
						: !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)))
			throw new IOException("文件或目录已不存在");
		if (!target.toRealPath().startsWith(root.toRealPath())) throw new IOException("路径超出当前工作目录");
		return target;
	}

	static void openExplorer(Path target, boolean directory) throws IOException {
		String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (system.startsWith("windows")) {
			new ProcessBuilder("explorer.exe", directory ? target.toString() : "/select," + target).start();
			return;
		}
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
			throw new IOException("当前系统不支持打开文件管理器");
		Desktop.getDesktop().open((directory ? target : target.getParent()).toFile());
	}
}
