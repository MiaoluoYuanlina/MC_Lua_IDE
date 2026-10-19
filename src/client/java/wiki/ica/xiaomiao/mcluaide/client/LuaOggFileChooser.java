package wiki.ica.xiaomiao.mcluaide.client;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Minecraft's bundled native dialogs work even though Minecraft forces AWT headless mode. */
final class LuaOggFileChooser {
	private LuaOggFileChooser() { }
	static CompletableFuture<Path> choose() {
		// Opening the native dialog is blocking, so never call it on Minecraft's render thread.
		return CompletableFuture.supplyAsync(() -> openNative(false));
	}
	static CompletableFuture<Path> choosePng() {
		return CompletableFuture.supplyAsync(() -> openNative(true));
	}

	private static synchronized Path openNative(boolean png) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var filters = stack.mallocPointer(1);
			filters.put(stack.UTF8(png ? "*.png" : "*.ogg")).flip();
			String selected = TinyFileDialogs.tinyfd_openFileDialog(
					png ? "选择物品材质 PNG" : "选择要导入的 OGG 音效", "", filters,
					png ? "PNG image (*.png)" : "Ogg Vorbis (*.ogg)", false);
			return selected == null || selected.isBlank() ? null : Path.of(selected);
		}
	}
}
