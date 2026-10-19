package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.client.mixin.FontProviderAccessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Properties;

/** Client-local IDE appearance; never sent to the server. */
public final class LuaPersonalizationSettings {
	static final float MIN_CODE_WHEEL_SENSITIVITY = 0.1F;
	static final float MAX_CODE_WHEEL_SENSITIVITY = 20.0F;
	static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide").resolve("personalization.properties");
	static final Path BACKGROUNDS = FILE.getParent().resolve("backgrounds");
	enum FontFace { DEFAULT, UNIFORM }
	public enum CursorShape { BAR, UNDERLINE, BLOCK }
	enum BackgroundFit { NONE, CONTAIN, COVER, STRETCH }
	enum BackgroundAlignment {
		TOP_LEFT(0, 0, "左上角"), TOP(1, 0, "上方"), TOP_RIGHT(2, 0, "右上角"),
		LEFT(0, 1, "左侧"), CENTER(1, 1, "居中"), RIGHT(2, 1, "右侧"),
		BOTTOM_LEFT(0, 2, "左下角"), BOTTOM(1, 2, "下方"), BOTTOM_RIGHT(2, 2, "右下角");
		final int column;
		final int row;
		final String label;
		BackgroundAlignment(int column, int row, String label) {
			this.column = column;
			this.row = row;
			this.label = label;
		}
		float horizontal() { return column / 2.0F; }
		float vertical() { return row / 2.0F; }
	}

	LuaSyntaxHighlighter.Theme theme = LuaSyntaxHighlighter.Theme.DARK;
	FontFace fontFace = FontFace.DEFAULT;
	int fontPercent = 100;
	float lineHeight = 1.0F;
	float cellWidth = 1.0F;
	float codeWheelSensitivity = 1.0F;
	boolean codeWordWrap = true;
	CursorShape cursorShape = CursorShape.BAR;
	boolean cursorBlink = true;
	int cursorWidth = 1;
	int cursorColor = 0xFFE6F3FF;
	String backgroundFile = "";
	int backgroundOpacity = 45;
	BackgroundFit backgroundFit = BackgroundFit.COVER;
	BackgroundAlignment backgroundAlignment = BackgroundAlignment.CENTER;
	boolean resizeDockWhenLocked;
	LuaWindowShortcuts windowShortcuts = new LuaWindowShortcuts();
	final EnumMap<LuaSyntaxHighlighter.ColorRole, Integer> syntaxColors =
			new EnumMap<>(LuaSyntaxHighlighter.ColorRole.class);

	LuaPersonalizationSettings copy() {
		LuaPersonalizationSettings copy = new LuaPersonalizationSettings();
		copy.theme = theme; copy.fontFace = fontFace; copy.fontPercent = fontPercent;
		copy.lineHeight = lineHeight; copy.cellWidth = cellWidth;
		copy.codeWheelSensitivity = codeWheelSensitivity;
		copy.codeWordWrap = codeWordWrap;
		copy.cursorShape = cursorShape; copy.cursorBlink = cursorBlink;
		copy.cursorWidth = cursorWidth; copy.cursorColor = cursorColor;
		copy.backgroundFile = backgroundFile; copy.backgroundOpacity = backgroundOpacity;
		copy.backgroundFit = backgroundFit; copy.backgroundAlignment = backgroundAlignment;
		copy.resizeDockWhenLocked = resizeDockWhenLocked;
		copy.windowShortcuts = windowShortcuts.copy();
		copy.syntaxColors.putAll(syntaxColors);
		return copy;
	}

	Font editorFont(Minecraft minecraft) {
		if (fontFace == FontFace.DEFAULT) return minecraft.font;
		Font.Provider original = ((FontProviderAccessor) minecraft.font).mcLuaIde$getProvider();
		FontDescription uniform = new FontDescription.Resource(Minecraft.UNIFORM_FONT);
		return new Font(new Font.Provider() {
			@Override public GlyphSource glyphs(FontDescription ignored) { return original.glyphs(uniform); }
			@Override public EffectGlyph effect() { return original.effect(); }
		});
	}

	static LuaPersonalizationSettings load() {
		return load(FILE);
	}

	static LuaPersonalizationSettings load(Path file) {
		LuaPersonalizationSettings value = new LuaPersonalizationSettings();
		if (!Files.isRegularFile(file)) return value;
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
			value.theme = enumValue(LuaSyntaxHighlighter.Theme.class, properties.getProperty("theme"), value.theme);
			value.fontFace = enumValue(FontFace.class, properties.getProperty("fontFace"), value.fontFace);
			value.fontPercent = intValue(properties, "fontPercent", 10, 300, value.fontPercent);
			value.lineHeight = floatValue(properties, "lineHeight", 0.8F, 2.0F, value.lineHeight);
			value.cellWidth = floatValue(properties, "cellWidth", 0.5F, 2.0F, value.cellWidth);
			value.codeWheelSensitivity = floatValue(properties, "codeWheelSensitivity",
					MIN_CODE_WHEEL_SENSITIVITY, MAX_CODE_WHEEL_SENSITIVITY, value.codeWheelSensitivity);
			value.codeWordWrap = Boolean.parseBoolean(properties.getProperty("codeWordWrap", "true"));
			value.cursorShape = enumValue(CursorShape.class, properties.getProperty("cursorShape"), value.cursorShape);
			value.cursorBlink = Boolean.parseBoolean(properties.getProperty("cursorBlink", "true"));
			value.cursorWidth = intValue(properties, "cursorWidth", 1, 4, value.cursorWidth);
			value.cursorColor = colorValue(properties.getProperty("cursorColor"), value.cursorColor);
			value.backgroundFile = safeBackgroundName(properties.getProperty("backgroundFile", ""));
			value.backgroundOpacity = intValue(properties, "backgroundOpacity", 0, 80, value.backgroundOpacity);
			value.backgroundFit = enumValue(BackgroundFit.class, properties.getProperty("backgroundFit"), value.backgroundFit);
			value.backgroundAlignment = enumValue(BackgroundAlignment.class,
					properties.getProperty("backgroundAlignment"), value.backgroundAlignment);
			value.resizeDockWhenLocked = Boolean.parseBoolean(properties.getProperty("resizeDockWhenLocked", "false"));
			value.windowShortcuts.read(properties);
			for (LuaSyntaxHighlighter.ColorRole role : LuaSyntaxHighlighter.ColorRole.values()) {
				Integer color = rgbValue(properties.getProperty("syntaxColor." + role.name()));
				if (color != null) value.syntaxColors.put(role, color);
			}
		} catch (Exception error) {
			McLuaIdeMod.LOGGER.warn("Could not read IDE personalization settings", error);
		}
		return value;
	}

	void save() throws java.io.IOException {
		save(FILE);
	}

	void save(Path file) throws java.io.IOException {
		Properties properties = new Properties();
		properties.setProperty("theme", theme.name()); properties.setProperty("fontFace", fontFace.name());
		properties.setProperty("fontPercent", Integer.toString(fontPercent));
		properties.setProperty("lineHeight", Float.toString(lineHeight));
		properties.setProperty("cellWidth", Float.toString(cellWidth));
		properties.setProperty("codeWheelSensitivity", Float.toString(codeWheelSensitivity()));
		properties.setProperty("codeWordWrap", Boolean.toString(codeWordWrap));
		properties.setProperty("cursorShape", cursorShape.name());
		properties.setProperty("cursorBlink", Boolean.toString(cursorBlink));
		properties.setProperty("cursorWidth", Integer.toString(cursorWidth));
		properties.setProperty("cursorColor", Integer.toHexString(cursorColor));
		properties.setProperty("backgroundFile", safeBackgroundName(backgroundFile));
		properties.setProperty("backgroundOpacity", Integer.toString(backgroundOpacity));
		properties.setProperty("backgroundFit", backgroundFit.name());
		properties.setProperty("backgroundAlignment", backgroundAlignment.name());
		properties.setProperty("resizeDockWhenLocked", Boolean.toString(resizeDockWhenLocked));
		windowShortcuts.write(properties);
		for (var entry : syntaxColors.entrySet())
			properties.setProperty("syntaxColor." + entry.getKey().name(),
					String.format(java.util.Locale.ROOT, "#%06X", entry.getValue()));
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			properties.store(writer, "MC Lua IDE local appearance");
		}
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	float codeWheelSensitivity() {
		return Float.isFinite(codeWheelSensitivity)
					? Math.clamp(codeWheelSensitivity, MIN_CODE_WHEEL_SENSITIVITY, MAX_CODE_WHEEL_SENSITIVITY) : 1.0F;
	}

	static Float parseCodeWheelSensitivity(String text) {
		try {
			float value = Float.parseFloat(text.trim());
			return Float.isFinite(value) && value >= MIN_CODE_WHEEL_SENSITIVITY
					&& value <= MAX_CODE_WHEEL_SENSITIVITY ? value : null;
		} catch (Exception ignored) { return null; }
	}

	static String safeBackgroundName(String name) {
		if (name == null || name.isBlank() || name.length() > 100
				|| !name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
				|| name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
				|| name.chars().anyMatch(Character::isISOControl)) return "";
		return name;
	}

	int syntaxColor(LuaSyntaxHighlighter.ColorRole role) {
		return syntaxColors.getOrDefault(role, theme.defaultColor(role));
	}

	static Integer rgbValue(String text) {
		if (text == null) return null;
		String normalized = text.trim();
		if (normalized.startsWith("#")) normalized = normalized.substring(1);
		if (normalized.length() != 6 || !normalized.matches("[0-9a-fA-F]{6}")) return null;
		return Integer.parseInt(normalized, 16);
	}

	private static <T extends Enum<T>> T enumValue(Class<T> type, String raw, T fallback) {
		try { return Enum.valueOf(type, raw); } catch (Exception ignored) { return fallback; }
	}
	private static int intValue(Properties values, String key, int min, int max, int fallback) {
		try { return Math.clamp(Integer.parseInt(values.getProperty(key)), min, max); }
		catch (Exception ignored) { return fallback; }
	}
	private static float floatValue(Properties values, String key, float min, float max, float fallback) {
		try {
			float parsed = Float.parseFloat(values.getProperty(key));
			return Float.isFinite(parsed) ? Math.clamp(parsed, min, max) : fallback;
		} catch (Exception ignored) { return fallback; }
	}
	private static int colorValue(String raw, int fallback) {
		try { return 0xFF000000 | (int) Long.parseLong(raw, 16); }
		catch (Exception ignored) { return fallback; }
	}
}
