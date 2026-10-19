package wiki.ica.xiaomiao.mcluaide.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Last selected ID category; separate from the floating/docked window layout. */
final class LuaInsertPickerPreferences {
	private LuaInsertPickerPreferences() { }
	static LuaInsertCatalog.Kind load(Path file) {
		if (file != null && Files.isRegularFile(file)) try {
			Properties properties = new Properties();
			try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) { properties.load(reader); }
			return LuaInsertCatalog.Kind.valueOf(properties.getProperty("kind", "ITEM"));
		} catch (Exception ignored) { }
		return LuaInsertCatalog.Kind.ITEM;
	}
	static void save(Path file, LuaInsertCatalog.Kind kind) throws java.io.IOException {
		Properties properties = new Properties(); properties.setProperty("kind", kind.name());
		if (!LuaIdeSession.writeProperties(file, properties)) throw new java.io.IOException("Could not save insertion category");
	}
}
