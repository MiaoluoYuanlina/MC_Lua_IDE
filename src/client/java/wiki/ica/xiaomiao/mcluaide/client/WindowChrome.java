package wiki.ica.xiaomiao.mcluaide.client;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

/**
 * Puts the IDE's menus and window buttons into the window's title bar row, as an option in the personalization settings.
 * <ul>
 * <li><b>Windows</b>: native frame kept (borders, shadow, Aero Snap, drag, double-click to maximise); the system caption is
 * replaced by the IDE's strip. See {@link WindowsFrame}.</li>
 * <li><b>macOS</b>: transparent native title bar, the traffic lights stay and the menus start to their right. See {@link MacFrame}.</li>
 * <li><b>Linux (X11)</b>: the window manager's decorations are replaced by the IDE's own strip with minimise / maximise / close,
 * dragging, double-click to maximise and edge resizing. Wayland cannot position windows from the client, so the normal title bar stays.</li>
 * </ul>
 * Anything that fails, a fullscreen window, or the setting being off leaves the normal title bar untouched.
 */
final class WindowChrome {
	enum Kind { NONE, WINDOWS, MAC, CUSTOM }

	private static final Kind KIND = detect();
	private static String unsupported = "";
	private static boolean active, failed;
	private static boolean wasMaximized;
	// manual window dragging (macOS, Linux)
	private static boolean dragging;
	private static double dragPressX, dragPressY;
	// custom edge resizing (Linux)
	private static int resizeEdges;
	private static boolean resizing;
	private static double resizePressX, resizePressY;
	private static int[] resizeStart = new int[4];
	private static long[] cursors = new long[4];

	private WindowChrome() { }

	private static Kind detect() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (os.contains("win")) return Kind.WINDOWS;
		if (os.contains("mac")) return Kind.MAC;
		if (os.contains("linux") || os.contains("bsd")) {
			try {
				if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_X11) return Kind.CUSTOM;
				unsupported = "Wayland";
			} catch (Throwable error) { unsupported = "unknown windowing system"; }
		} else unsupported = os;
		return Kind.NONE;
	}

	static Kind kind() { return KIND; }
	/** On by default only on Windows, where it has been tested. */
	static boolean defaultEnabled() { return KIND == Kind.WINDOWS; }
	static boolean supported() { return KIND != Kind.NONE && !failed; }
	static boolean active() { return active; }

	static String supportNote() {
		if (KIND == Kind.NONE) return LuaUiText.text("此系统无法把菜单放进标题栏，会继续使用系统标题栏。", "Not available on this system; the normal title bar is used.") + " (" + unsupported + ")";
		if (failed) return LuaUiText.text("初始化失败，已恢复系统标题栏（详见日志）。", "Setup failed; the normal title bar was restored (see the log).");
		return switch (KIND) {
			case WINDOWS -> LuaUiText.text("保留系统边框、吸附和阴影；菜单与窗口按钮绘制在标题栏位置。", "Keeps the system frame, snapping and shadow; menus and window buttons are drawn in the title bar.");
			case MAC -> LuaUiText.text("保留系统红绿灯按钮；菜单显示在它们右侧。", "Keeps the system traffic-light buttons; menus sit to their right.");
			default -> LuaUiText.text("用 IDE 自绘的标题栏替换窗口管理器标题栏（拖动、双击最大化、边缘缩放）。", "Replaces the window manager's title bar with the IDE's own (drag, double-click to maximise, edge resize).");
		};
	}

	private static long handle() { return Minecraft.getInstance().getWindow().handle(); }

	/** Call once per frame on the render thread with the user's setting; installs or removes the integration as needed. */
	static void update(boolean wanted) {
		boolean should = wanted && supported() && !Minecraft.getInstance().getWindow().isFullscreen();
		if (should == active) return;
		try {
			if (should) activate(); else deactivate();
			active = should;
		} catch (Throwable error) {
			failed = true; active = false;
			McLuaIdeMod.LOGGER.error("Could not change the integrated title bar; using the normal one", error);
			try { deactivate(); } catch (Throwable ignored) { }
		}
	}

	/** Gives the window its normal title bar back (IDE closed, setting off, game exiting). */
	static void release() {
		if (!active) return;
		try { deactivate(); } catch (Throwable error) { McLuaIdeMod.LOGGER.warn("Could not restore the title bar", error); }
		active = false;
	}

	private static void activate() throws Throwable {
		switch (KIND) {
			case WINDOWS -> WindowsFrame.install(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle()));
			case MAC -> MacFrame.apply(org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle()));
			case CUSTOM -> GLFW.glfwSetWindowAttrib(handle(), GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			default -> { }
		}
	}

	private static void deactivate() throws Throwable {
		switch (KIND) {
			case WINDOWS -> WindowsFrame.disable();
			case MAC -> MacFrame.revert();
			case CUSTOM -> GLFW.glfwSetWindowAttrib(handle(), GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			default -> { }
		}
		dragging = false; resizing = false;
	}

	// ---- drawing and input, called from the IDE's menu bar ---------------------------------------------------------------------
	/** Space the IDE leaves at the left of the title strip (macOS keeps its traffic lights there). */
	static float leftInset() {
		if (!active || KIND != Kind.MAC) return 0;
		float[] scale = new float[1], unused = new float[1];
		GLFW.glfwGetWindowContentScale(handle(), scale, unused);
		return 78f * Math.max(1f, scale[0]);
	}

	private static float buttonWidth() { return ImGui.getFrameHeight() * 1.55f; }

	/**
	 * Call as the last thing inside the main menu bar, with the right edge of the last item. Draws the window buttons (Windows, Linux),
	 * makes the empty part of the strip a drag handle and tells the Windows frame where the caption is.
	 */
	static void finishBar(float lastItemRight) {
		if (!active) return;
		float width = ImGui.getMainViewport().getSizeX(), height = ImGui.getWindowHeight();
		boolean drawButtons = KIND == Kind.WINDOWS || KIND == Kind.CUSTOM;
		float buttons = drawButtons ? buttonWidth() * 3 : 0, dragFrom = lastItemRight + 12, dragTo = width - buttons;
		if (KIND == Kind.WINDOWS) WindowsFrame.layout(height, dragFrom, dragTo);
		if (dragTo > dragFrom) {
			ImGui.setCursorScreenPos(dragFrom, ImGui.getWindowPosY());
			ImGui.invisibleButton("##titlebar_drag", dragTo - dragFrom, height);
			if (KIND != Kind.WINDOWS) manualDrag();
		}
		if (drawButtons) drawButtons(width - buttons, height);
		if (KIND == Kind.CUSTOM) edgeResize();
	}

	private static void drawButtons(float x, float height) {
		long window = handle();
		boolean maximized = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
		float w = buttonWidth(), y = ImGui.getWindowPosY();
		ImDrawList draw = ImGui.getWindowDrawList();
		int text = ImGui.getColorU32(ImGuiCol.Text);
		for (int i = 0; i < 3; i++) {
			float x0 = x + i * w;
			ImGui.setCursorScreenPos(x0, y);
			boolean pressed = ImGui.invisibleButton("##caption_" + i, w, height);
			boolean hover = ImGui.isItemHovered(), down = ImGui.isItemActive();
			boolean close = i == 2;
			if (hover || down) draw.addRectFilled(x0, y, x0 + w, y + height, close ? (down ? 0xFF3040C8 : 0xFF2311E8) : ImGui.getColorU32(down ? ImGuiCol.HeaderActive : ImGuiCol.HeaderHovered));
			int color = close && hover ? 0xFFFFFFFF : text;
			float cx = x0 + w / 2, cy = y + height / 2, s = Math.max(4.5f, height * .17f);
			switch (i) {
				case 0 -> draw.addLine(cx - s, cy, cx + s, cy, color, 1.2f);
				case 1 -> {
					if (maximized) {
						draw.addRect(cx - s + 1.5f, cy - s + 0f, cx + s + 0.5f, cy + s - 1.5f, color, 0, 0, 1.2f);
						draw.addLine(cx - s - .5f, cy - s + 3f, cx - s - .5f, cy + s + 0.5f, color, 1.2f);
						draw.addLine(cx - s - .5f, cy + s + 0.5f, cx + s - 2.5f, cy + s + .5f, color, 1.2f);
					} else draw.addRect(cx - s, cy - s, cx + s, cy + s, color, 0, 0, 1.2f);
				}
				default -> { draw.addLine(cx - s, cy - s, cx + s, cy + s, color, 1.3f); draw.addLine(cx + s, cy - s, cx - s, cy + s, color, 1.3f); }
			}
			if (hover) ImGui.setTooltip(i == 0 ? LuaUiText.text("最小化", "Minimize") : i == 1 ? (maximized ? LuaUiText.text("还原", "Restore") : LuaUiText.text("最大化", "Maximize")) : LuaUiText.text("关闭", "Close"));
			if (pressed) {
				switch (i) {
					case 0 -> GLFW.glfwIconifyWindow(window);
					case 1 -> { if (maximized) GLFW.glfwRestoreWindow(window); else GLFW.glfwMaximizeWindow(window); }
					default -> GLFW.glfwSetWindowShouldClose(window, true);
				}
			}
		}
	}

	/** Moves the window by dragging the empty part of the strip, and maximises / restores it on a double-click (macOS, Linux). */
	private static void manualDrag() {
		long window = handle();
		double[] x = new double[1], y = new double[1];
		GLFW.glfwGetCursorPos(window, x, y);
		if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
			if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) GLFW.glfwRestoreWindow(window); else GLFW.glfwMaximizeWindow(window);
			dragging = false; return;
		}
		if (ImGui.isItemActivated() && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) != GLFW.GLFW_TRUE) { dragging = true; dragPressX = x[0]; dragPressY = y[0]; }
		if (dragging) {
			if (!ImGui.isMouseDown(0)) { dragging = false; return; }
			int[] wx = new int[1], wy = new int[1];
			GLFW.glfwGetWindowPos(window, wx, wy);
			// The window moves under the cursor, so the offset from the press point is what remains to be applied.
			GLFW.glfwSetWindowPos(window, wx[0] + (int) Math.round(x[0] - dragPressX), wy[0] + (int) Math.round(y[0] - dragPressY));
		}
	}

	private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8, GRIP = 6;

	/** Resizing from the edges of the undecorated window (Linux). */
	private static void edgeResize() {
		long window = handle();
		if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) { resizing = false; return; }
		double[] x = new double[1], y = new double[1];
		GLFW.glfwGetCursorPos(window, x, y);
		int[] w = new int[1], h = new int[1], wx = new int[1], wy = new int[1];
		GLFW.glfwGetWindowSize(window, w, h); GLFW.glfwGetWindowPos(window, wx, wy);
		if (!resizing) {
			int edges = (x[0] < GRIP ? LEFT : x[0] >= w[0] - GRIP ? RIGHT : 0) | (y[0] < GRIP ? TOP : y[0] >= h[0] - GRIP ? BOTTOM : 0);
			if (edges != 0 && !ImGui.isAnyItemActive()) {
				setResizeCursor(window, edges);
				if (ImGui.isMouseClicked(0)) {
					resizing = true; resizeEdges = edges;
					resizePressX = wx[0] + x[0]; resizePressY = wy[0] + y[0];
					resizeStart = new int[]{wx[0], wy[0], w[0], h[0]};
				}
			}
			return;
		}
		if (!ImGui.isMouseDown(0)) { resizing = false; GLFW.glfwSetCursor(window, 0); return; }
		double dx = wx[0] + x[0] - resizePressX, dy = wy[0] + y[0] - resizePressY;
		int nx = resizeStart[0], ny = resizeStart[1], nw = resizeStart[2], nh = resizeStart[3];
		if ((resizeEdges & RIGHT) != 0) nw = (int) Math.max(480, resizeStart[2] + dx);
		if ((resizeEdges & BOTTOM) != 0) nh = (int) Math.max(320, resizeStart[3] + dy);
		if ((resizeEdges & LEFT) != 0) { nw = (int) Math.max(480, resizeStart[2] - dx); nx = resizeStart[0] + resizeStart[2] - nw; }
		if ((resizeEdges & TOP) != 0) { nh = (int) Math.max(320, resizeStart[3] - dy); ny = resizeStart[1] + resizeStart[3] - nh; }
		GLFW.glfwSetWindowPos(window, nx, ny);
		GLFW.glfwSetWindowSize(window, nw, nh);
	}

	private static void setResizeCursor(long window, int edges) {
		int shape = (edges & (LEFT | RIGHT)) != 0 && (edges & (TOP | BOTTOM)) != 0 ? GLFW.GLFW_RESIZE_ALL_CURSOR
				: (edges & (LEFT | RIGHT)) != 0 ? GLFW.GLFW_RESIZE_EW_CURSOR : GLFW.GLFW_RESIZE_NS_CURSOR;
		int slot = shape == GLFW.GLFW_RESIZE_EW_CURSOR ? 0 : shape == GLFW.GLFW_RESIZE_NS_CURSOR ? 1 : 2;
		if (cursors[slot] == 0) cursors[slot] = GLFW.glfwCreateStandardCursor(shape);
		if (cursors[slot] != 0) GLFW.glfwSetCursor(window, cursors[slot]);
	}
}
