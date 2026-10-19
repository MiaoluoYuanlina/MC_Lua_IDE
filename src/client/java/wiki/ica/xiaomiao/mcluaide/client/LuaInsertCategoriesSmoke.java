package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Categories remain reachable without the old game/resource group buttons. */
final class LuaInsertCategoriesSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>> fixtures = new EnumMap<>(LuaInsertCatalog.Kind.class);
		for (var kind : LuaInsertCatalog.Kind.values()) fixtures.put(kind, List.of(entry("test:shared"), entry("minecraft:shared"), entry("test:shared")));
		require(LuaInsertCatalog.allEntries(fixtures).size() == LuaInsertCatalog.Kind.values().length * 2, "Catalog lost IDs");
		List<LuaInsertCatalog.Entry> inserted = new ArrayList<>();
		var picker = new LuaInsertOverlay(null, null, inserted::add); picker.resize(600, 400);
		@SuppressWarnings("unchecked") var catalog = (Map<LuaInsertCatalog.Kind, List<LuaInsertCatalog.Entry>>) get(picker, "catalogs");
		catalog.putAll(fixtures); invoke(picker, "rebuildCatalog", new Class[]{boolean.class}, true);
		for (var kind : LuaInsertCatalog.Kind.values()) {
			select(picker, kind);
			require(rows(picker).size() == 2 && rows(picker).stream().allMatch(row -> row.kind() == kind), "Wrong category " + kind);
			int start = (int) get(picker, "categoryPageStart"), slots = (int) invoke(picker, "categorySlots");
			require(start <= kind.ordinal() && kind.ordinal() < start + slots, "Selected tab hidden");
		}
		select(picker, LuaInsertCatalog.Kind.SOUND);
		@SuppressWarnings("unchecked") var namespaces = (List<String>) get(picker, "namespaces");
		set(picker, "namespaceIndex", namespaces.indexOf("test") + 1); invoke(picker, "invalidateFilter");
		require(rows(picker).size() == 1 && rows(picker).getFirst().kind() == LuaInsertCatalog.Kind.SOUND, "Namespace leaked categories");
		catalog.put(LuaInsertCatalog.Kind.SOUND, List.of(entry("aaa:first"), entry("test:shared")));
		invoke(picker, "rebuildCatalog", new Class[]{boolean.class}, false);
		require(rows(picker).size() == 1 && rows(picker).getFirst().namespace().equals("test"), "Async refresh lost namespace");
		set(picker, "query", "声音"); invoke(picker, "invalidateFilter"); require(rows(picker).size() == 1, "Chinese search failed");
		invoke(picker, "insertSelected"); require(inserted.getFirst().value().equals("test:shared"), "Inserted label instead of raw ID");
		String[] active = {null};
		var preview = new LuaInsertSoundPreview(new LuaInsertSoundPreview.Audio() {
			public String play(String id) { active[0] = id; return null; }
			public void stop() { active[0] = null; }
		});
		set(picker, "soundPreview", preview); set(picker, "open", true); set(picker, "keyboardFocused", true); set(picker, "progress", 1F);
		catalog.put(LuaInsertCatalog.Kind.SOUND, List.of(entry("test:first"), entry("test:second")));
		set(picker, "query", ""); set(picker, "namespaceIndex", 0); invoke(picker, "rebuildCatalog", new Class[]{boolean.class}, true);
		key(picker, InputConstants.KEY_DOWN); require("test:second".equals(active[0]), "Sound page arrow preview failed");
		key(picker, InputConstants.KEY_RIGHT); require(picker.kind() == LuaInsertCatalog.Kind.PARTICLE && active[0] == null, "Leaving sound page retained preview");
		set(picker, "filtered", List.of(new LuaInsertCatalog.TypedEntry(LuaInsertCatalog.Kind.SOUND, entry("test:first")))); set(picker, "selectedRow", 0);
		key(picker, InputConstants.KEY_DOWN); require(active[0] == null, "Other category played stale sound row");
		picker.updateSoundPreview((int) get(picker, "windowX") + 35, (int) get(picker, "windowY") + (int) get(picker, "LIST_TOP") + 5, true, true);
		require(active[0] == null, "Other category hover played audio");
		key(picker, InputConstants.KEY_LEFT); require(picker.kind() == LuaInsertCatalog.Kind.SOUND, "Left did not restore category");
		key(picker, InputConstants.KEY_UP); require("test:first".equals(active[0]), "Sound page failed to resume preview");
		select(picker, LuaInsertCatalog.Kind.SOUND_FILE); key(picker, InputConstants.KEY_DOWN); require(active[0] == null, "OGG page played sound event");
		for (int i = 0; i < LuaInsertCatalog.Kind.values().length; i++) {
			var previous = picker.kind(); key(picker, InputConstants.KEY_RIGHT);
			require(picker.kind().ordinal() == (previous.ordinal() + 1) % LuaInsertCatalog.Kind.values().length, "Category traversal skipped kind");
		}
		set(picker, "windowWidth", 380); select(picker, LuaInsertCatalog.Kind.ITEM); set(picker, "categoryPageStart", 0);
		for (int page = 0; page * (int) invoke(picker, "categorySlots") < LuaInsertCatalog.Kind.values().length; page++) {
			int start = (int) get(picker, "categoryPageStart"), slots = (int) invoke(picker, "categorySlots");
			int last = Math.min(LuaInsertCatalog.Kind.values().length - 1, start + slots - 1);
			int x = (int) invoke(picker, "panelX"), y = (int) get(picker, "windowY") + 70;
			invoke(picker, "categoryClicked", new Class[]{double.class, double.class},
					(double) (x + 34 + (last - start) * (int) invoke(picker, "categoryWidth") + 9), (double) y);
			require(picker.kind().ordinal() == last, "Mouse tab click failed on page " + page);
			invoke(picker, "categoryClicked", new Class[]{double.class, double.class}, (double) (x + 360), (double) y);
		}
		picker.close(); require(active[0] == null, "Close retained sound");
		var dir = java.nio.file.Files.createTempDirectory("lua-insert-category-");
		try {
			var file = dir.resolve("picker.properties"); require(LuaInsertPickerPreferences.load(file) == LuaInsertCatalog.Kind.ITEM, "Missing default");
			for (var kind : LuaInsertCatalog.Kind.values()) {
				LuaInsertPickerPreferences.save(file, kind); require(LuaInsertPickerPreferences.load(file) == kind, "Category did not persist " + kind);
			}
		} finally { java.nio.file.Files.deleteIfExists(dir.resolve("picker.properties")); java.nio.file.Files.deleteIfExists(dir); }
		System.out.println("Classified ID insertion smoke passed: " + checks + " checks; categories, namespaces, paging, persistence, type selection and sound-page-only preview");
	}
	private static LuaInsertCatalog.Entry entry(String id) { return new LuaInsertCatalog.Entry(id, id, id.substring(0, id.indexOf(':'))); }
	@SuppressWarnings("unchecked") private static List<LuaInsertCatalog.TypedEntry> rows(Object picker) throws Exception { return (List<LuaInsertCatalog.TypedEntry>) get(picker, "filtered"); }
	private static Object get(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
	private static void set(Object owner, String name, Object value) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
	private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception { var m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args); }
	private static Object invoke(Object owner, String name) throws Exception { return invoke(owner, name, new Class[0]); }
	private static void select(Object picker, LuaInsertCatalog.Kind kind) throws Exception { invoke(picker, "selectKind", new Class[]{LuaInsertCatalog.Kind.class}, kind); }
	private static void key(LuaInsertOverlay picker, int key) { picker.keyPressed(new KeyEvent(key, 0, 0)); }
	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
