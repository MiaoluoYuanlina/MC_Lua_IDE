package wiki.ica.xiaomiao.mcluaide.client;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Native picker off the render thread; no AWT/headless dependency. */
final class LuaZipFileChooser {
	private LuaZipFileChooser() { }
	static CompletableFuture<Path> choose(Path initialDirectory) {
		return CompletableFuture.supplyAsync(() -> open(initialDirectory),
				command -> Thread.ofVirtual().name("mc-lua-pack-picker").start(command));
	}
	private static synchronized Path open(Path directory) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var filter = stack.mallocPointer(1); filter.put(stack.UTF8("*.zip")).flip();
			String selected = TinyFileDialogs.tinyfd_openFileDialog("选择上传到服务器的资源包 ZIP",
					directory.toAbsolutePath() + java.io.File.separator, filter, "Minecraft 资源包 (*.zip)", false);
			return selected == null || selected.isBlank() ? null : Path.of(selected);
		}
	}
}
