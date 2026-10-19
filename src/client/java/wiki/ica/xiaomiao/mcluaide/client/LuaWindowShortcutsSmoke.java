package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Properties;

final class LuaWindowShortcutsSmoke {
	private static int checks;
	public static void main(String[] args) {
		LuaWindowShortcuts settings = new LuaWindowShortcuts();
		for (int m = 1; m <= 7; m++) for (int f = 1; f <= 12; f++) {
			equal(settings.get(m, f), LuaWindowShortcuts.defaultTarget(m, f));
			equal(settings.match(289 + f, m), settings.get(m, f));
			equal(settings.match(289 + f, m | 16 | 32), settings.get(m, f));
			equal(settings.match(289 + f, m | 8), LuaWindowShortcuts.Target.NONE);
			equal(settings.match(289 + f, 0), LuaWindowShortcuts.Target.NONE);
			settings.set(m, f, LuaWindowShortcuts.Target.values()[(m + f) % LuaWindowShortcuts.Target.values().length]);
		}
		Properties file = new Properties(); settings.write(file);
		LuaWindowShortcuts loaded = new LuaWindowShortcuts(); loaded.read(file);
		for (int m = 1; m <= 7; m++) for (int f = 1; f <= 12; f++) equal(loaded.get(m, f), settings.get(m, f));
		LuaWindowShortcuts draft = loaded.copy(); draft.set(2, 1, LuaWindowShortcuts.Target.NONE);
		equal(loaded.get(2, 1), settings.get(2, 1));
		draft.write(file); loaded.read(file); equal(loaded.get(2, 1), LuaWindowShortcuts.Target.NONE);
		file.setProperty("windowShortcut.2.F1", "invalid"); loaded.read(file);
		equal(loaded.get(2, 1), LuaWindowShortcuts.Target.API_DOCS);
		loaded.read(new Properties());
		for (int m = 1; m <= 7; m++) for (int f = 1; f <= 12; f++)
			equal(loaded.get(m, f), LuaWindowShortcuts.defaultTarget(m, f));
		for (int key : new int[]{-1, 0, 65, 289, 302, Integer.MAX_VALUE})
			equal(loaded.match(key, 2), LuaWindowShortcuts.Target.NONE);
		for (int m = 0; m < 8; m++) equal(loaded.match(301, m), LuaWindowShortcuts.Target.NONE);
		System.out.println("Window shortcuts smoke passed: " + checks + " checks");
	}
	private static void equal(Object actual, Object expected) {
		checks++; if (actual != expected) throw new AssertionError(actual + " != " + expected);
	}
}
