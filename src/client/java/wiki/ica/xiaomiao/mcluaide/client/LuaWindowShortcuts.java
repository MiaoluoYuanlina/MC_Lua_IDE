package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Properties;

/** Exact modifier chords for IDE windows; bare function keys are never intercepted. */
final class LuaWindowShortcuts {
	static final int SHIFT = 1, CTRL = 2, ALT = 4, FIRST_F_KEY = 290;
	enum Target {
		NONE("未绑定", ""), API_DOCS("API 文档", "API_DOCS"), INSERT("插入", "INSERT"),
		OUTPUT("运行日志", "OUTPUT"), COMPONENT_EDITOR("数据组件编辑器", "COMPONENT_EDITOR"),
		MANUAL("使用手册", "MANUAL"), FILES("Lua 目录", "FILES"), DATAPACK_FILES("数据包目录", "DATAPACK_FILES"),
		RESOURCE_FILES("资源包目录", "RESOURCE_FILES"), DATAPACK_JSON_EDITOR("数据包 JSON 编辑器", "DATAPACK_JSON_EDITOR"),
		RESOURCE_JSON("资源包 JSON 编辑器", "RESOURCE_JSON"), TEXT_JSON("文本 JSON 编辑器", "TEXT_JSON"),
		MINECRAFT_UI("Minecraft UI 编辑器", "MINECRAFT_UI"), RESOURCE_PNG("图片工具", "RESOURCE_PNG"),
		VARIABLES("变量显示", "VARIABLES"), BREAKPOINTS("断点调试", "EXT:mc_lua_ide:breakpoint_debugger"),
		GIT("Git", "GIT"), PERSONALIZATION("个性化", "PERSONALIZATION"), COLOR("取色", "EXT:mc_lua_ide:color_picker");
		final String label, tool;
		Target(String label, String tool) { this.label = label; this.tool = tool; }
	}
	private final Target[][] bindings = new Target[8][12];
	LuaWindowShortcuts() { reset(); }
	void reset() {
		for (int modifiers = 1; modifiers <= 7; modifiers++)
			for (int f = 1; f <= 12; f++) bindings[modifiers][f - 1] = defaultTarget(modifiers, f);
	}
	static Target defaultTarget(int modifiers, int f) {
		if (modifiers != CTRL && modifiers != SHIFT && modifiers != ALT) return Target.NONE;
		return switch (f) {
			case 1 -> Target.API_DOCS; case 2 -> Target.INSERT;
			case 3 -> Target.OUTPUT; case 4 -> Target.COMPONENT_EDITOR; default -> Target.NONE;
		};
	}
	Target get(int modifiers, int f) {
		return modifiers >= 1 && modifiers <= 7 && f >= 1 && f <= 12 ? bindings[modifiers][f - 1] : Target.NONE;
	}
	void set(int modifiers, int f, Target target) {
		if (modifiers < 1 || modifiers > 7 || f < 1 || f > 12) throw new IllegalArgumentException("Invalid IDE chord");
		bindings[modifiers][f - 1] = java.util.Objects.requireNonNull(target);
	}
	Target match(int key, int modifiers) {
		// Caps/Num lock don't affect a chord; Super/Windows must not match Ctrl/Shift/Alt.
		return (modifiers & ~ (7 | 16 | 32)) == 0 ? get(modifiers & 7, key - FIRST_F_KEY + 1) : Target.NONE;
	}
	LuaWindowShortcuts copy() {
		LuaWindowShortcuts result = new LuaWindowShortcuts();
		for (int modifiers = 1; modifiers <= 7; modifiers++)
			System.arraycopy(bindings[modifiers], 0, result.bindings[modifiers], 0, 12);
		return result;
	}
	void read(Properties properties) {
		reset();
		for (int modifiers = 1; modifiers <= 7; modifiers++) for (int f = 1; f <= 12; f++) {
			String value = properties.getProperty(property(modifiers, f));
			if (value != null) try { set(modifiers, f, Target.valueOf(value)); } catch (IllegalArgumentException ignored) { }
		}
	}
	void write(Properties properties) {
		for (int modifiers = 1; modifiers <= 7; modifiers++) for (int f = 1; f <= 12; f++)
			properties.setProperty(property(modifiers, f), get(modifiers, f).name());
	}
	private static String property(int modifiers, int f) { return "windowShortcut." + modifiers + ".F" + f; }
	static String modifierLabel(int modifiers) {
		return ((modifiers & CTRL) != 0 ? "Ctrl+" : "") + ((modifiers & SHIFT) != 0 ? "Shift+" : "")
				+ ((modifiers & ALT) != 0 ? "Alt+" : "");
	}
}
