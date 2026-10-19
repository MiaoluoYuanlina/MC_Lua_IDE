package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Properties;

/** Client-only HUD geometry and appearance; contains no debug data or enable switches. */
final class LuaHudLayout {
	enum Panel {
		VARIABLES("Lua 变量", 140, 66), BREAKPOINTS("断点信息", 140, 92),
		LOGS("Lua 日志", 140, 56), CAMERA("自由摄像机提示", 140, 42);
		final String label;
		final int minWidth, minHeight;
		Panel(String label, int minWidth, int minHeight) {
			this.label = label; this.minWidth = minWidth; this.minHeight = minHeight;
		}
	}
	record Rect(int x, int y, int width, int height) {
		int right() { return x + width; }
		int bottom() { return y + height; }
		boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
	}
	private record Relative(double x, double y, double width, double height) {
		boolean valid() {
			return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && Double.isFinite(height)
					&& x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.000001 && y + height <= 1.000001;
		}
	}
	private static LuaHudLayout shared;
	private final Path file;
	private final EnumMap<Panel, Relative> positions = new EnumMap<>(Panel.class);
	private final EnumMap<Panel, Integer> transparencies = new EnumMap<>(Panel.class);
	LuaHudLayout(Path file) { this.file = file; read(LuaIdeSession.readProperties(file)); }
	static LuaHudLayout current() {
		if (shared == null) shared = new LuaHudLayout(FabricLoader.getInstance().getConfigDir()
				.resolve("mc_lua_ide").resolve("debug-hud-layout.properties"));
		return shared;
	}
	Rect bounds(Panel panel, int viewportWidth, int viewportHeight) {
		int w = Math.max(1, viewportWidth), h = Math.max(1, viewportHeight);
		Relative saved = positions.get(panel);
		Rect rect;
		if (saved != null) rect = new Rect((int) Math.round(saved.x * w), (int) Math.round(saved.y * h),
				(int) Math.round(saved.width * w), (int) Math.round(saved.height * h));
		else {
			int pw = Math.min(w - 16, panel == Panel.VARIABLES ? Math.min(360, Math.max(180, w / 3))
					: panel == Panel.BREAKPOINTS ? 340 : panel == Panel.CAMERA ? 365 : 360);
			// Four previews must remain independently reachable even at Minecraft's minimum GUI size.
			pw = Math.min(pw, Math.max(1, (w - 24) / 2));
			boolean right = panel == Panel.BREAKPOINTS || panel == Panel.LOGS;
			boolean bottom = panel == Panel.LOGS || panel == Panel.CAMERA;
			int available = Math.max(1, h - 93);
			int ph = Math.min(Math.max(1, bottom ? available / 3 : available * 2 / 3), switch (panel) {
				case VARIABLES -> 245; case BREAKPOINTS -> 145; case LOGS -> 137; case CAMERA -> 40;
			});
			rect = new Rect(right ? w - pw - 8 : 8, bottom ? h - ph - 43 : 42, pw, ph);
		}
		return clamp(panel, rect, w, h);
	}
	static Rect clamp(Panel panel, Rect rect, int viewportWidth, int viewportHeight) {
		int vw = Math.max(1, viewportWidth), vh = Math.max(1, viewportHeight);
		int w = Math.clamp(rect.width, Math.min(panel.minWidth, vw), vw);
		int h = Math.clamp(rect.height, Math.min(panel.minHeight, vh), vh);
		return new Rect(Math.clamp(rect.x, 0, vw - w), Math.clamp(rect.y, 0, vh - h), w, h);
	}
	void set(Panel panel, Rect rect, int viewportWidth, int viewportHeight) {
		int w = Math.max(1, viewportWidth), h = Math.max(1, viewportHeight);
		Rect safe = clamp(panel, rect, w, h);
		positions.put(panel, new Relative(safe.x / (double) w, safe.y / (double) h,
				safe.width / (double) w, safe.height / (double) h));
	}
	static Rect drag(Panel panel, Rect start, int edges, int dx, int dy, int vw, int vh) {
		if (edges == 0) return clamp(panel, new Rect(start.x + dx, start.y + dy, start.width, start.height), vw, vh);
		int left = start.x, top = start.y, right = start.right(), bottom = start.bottom();
		int minW = Math.min(panel.minWidth, Math.max(1, vw)), minH = Math.min(panel.minHeight, Math.max(1, vh));
		if ((edges & 1) != 0) left = Math.clamp(left + dx, 0, right - minW);
		if ((edges & 2) != 0) right = Math.clamp(right + dx, left + minW, Math.max(1, vw));
		if ((edges & 4) != 0) top = Math.clamp(top + dy, 0, bottom - minH);
		if ((edges & 8) != 0) bottom = Math.clamp(bottom + dy, top + minH, Math.max(1, vh));
		return new Rect(left, top, right - left, bottom - top);
	}
	static int edges(Rect rect, double x, double y) {
		if (x < rect.x - 4 || x > rect.right() + 4 || y < rect.y - 4 || y > rect.bottom() + 4) return 0;
		int edges = 0;
		if (Math.abs(x - rect.x) <= 4) edges |= 1;
		if (Math.abs(x - rect.right()) <= 4) edges |= 2;
		if (Math.abs(y - rect.y) <= 4) edges |= 4;
		if (Math.abs(y - rect.bottom()) <= 4) edges |= 8;
		return edges;
	}
	private static int defaultBackground(Panel panel) {
		return panel == Panel.VARIABLES || panel == Panel.LOGS ? 0xBC10131A : 0xD719202B;
	}
	int transparency(Panel panel) {
		return transparencies.getOrDefault(panel, Math.round((255 - (defaultBackground(panel) >>> 24)) * 100F / 255));
	}
	void setTransparency(Panel panel, int percent) { transparencies.put(panel, Math.clamp(percent, 0, 100)); }
	void resetTransparency(Panel panel) { transparencies.remove(panel); }
	int backgroundColor(Panel panel) {
		int base = defaultBackground(panel);
		Integer percent = transparencies.get(panel);
		return percent == null ? base : Math.round(255 * (100 - percent) / 100F) << 24 | base & 0xFFFFFF;
	}
	void reset() { positions.clear(); transparencies.clear(); }
	void read(Properties properties) {
		positions.clear();
		transparencies.clear();
		for (Panel panel : Panel.values()) {
			String p = panel.name() + ".";
			try {
				Relative value = new Relative(Double.parseDouble(properties.getProperty(p + "x", "")),
						Double.parseDouble(properties.getProperty(p + "y", "")),
						Double.parseDouble(properties.getProperty(p + "width", "")),
						Double.parseDouble(properties.getProperty(p + "height", "")));
				if (value.valid()) positions.put(panel, value);
			} catch (NumberFormatException ignored) { }
			try {
				int percent = Integer.parseInt(properties.getProperty(p + "backgroundTransparency", ""));
				if (percent >= 0 && percent <= 100) transparencies.put(panel, percent);
			} catch (NumberFormatException ignored) { }
		}
	}
	Properties properties() {
		Properties result = new Properties();
		positions.forEach((panel, value) -> {
			String p = panel.name() + ".";
			result.setProperty(p + "x", Double.toString(value.x)); result.setProperty(p + "y", Double.toString(value.y));
			result.setProperty(p + "width", Double.toString(value.width)); result.setProperty(p + "height", Double.toString(value.height));
		});
		transparencies.forEach((panel, percent) -> result.setProperty(panel.name() + ".backgroundTransparency", Integer.toString(percent)));
		return result;
	}
	boolean save() { return LuaIdeSession.writeProperties(file, properties()); }
}
