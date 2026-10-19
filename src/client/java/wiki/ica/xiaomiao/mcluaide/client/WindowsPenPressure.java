package wiki.ica.xiaomiao.mcluaide.client;

import com.sun.jna.CallbackReference;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Samples Windows Ink pen pressure without consuming GLFW's normal mouse events. */
final class WindowsPenPressure {
	private static final int GWLP_WNDPROC = -4;
	private static final int WM_POINTERUPDATE = 0x0245;
	private static final int WM_POINTERDOWN = 0x0246;
	private static final int WM_POINTERUP = 0x0247;
	private static final int WM_POINTERLEAVE = 0x024A;
	private static final int PEN_MASK_PRESSURE = 1;
	private static final long STALE_NANOS = 250_000_000L;
	private static User32 user32;
	private static WindowProc procedure;
	private static Pointer hwnd, previousProcedure;
	private static volatile boolean penInContact, penSeen;
	private static volatile double pressure = 1.0;
	private static volatile long lastUpdate;

	private WindowsPenPressure() { }

	static synchronized void install(Minecraft minecraft) {
		if (procedure != null || !System.getProperty("os.name", "").startsWith("Windows")) return;
		try {
			Pointer window = new Pointer(GLFWNativeWin32.glfwGetWin32Window(minecraft.getWindow().handle()));
			User32 api = com.sun.jna.Native.load("user32", User32.class);
			WindowProc hook = WindowsPenPressure::handleMessage;
			Pointer original = api.SetWindowLongPtrW(window, GWLP_WNDPROC,
					CallbackReference.getFunctionPointer(hook));
			if (original == null) return;
			user32 = api;
			hwnd = window;
			previousProcedure = original;
			procedure = hook; // Keep the native callback alive until the GLFW window is destroyed.
		} catch (LinkageError | RuntimeException ignored) {
			// An unsupported Windows input stack must not prevent PNG editing with a mouse.
		}
	}

	static synchronized void uninstall() {
		if (procedure == null) return;
		try {
			if (user32.IsWindow(hwnd) && previousProcedure != null
					&& CallbackReference.getFunctionPointer(procedure).equals(
							user32.GetWindowLongPtrW(hwnd, GWLP_WNDPROC)))
				user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, previousProcedure);
		} catch (LinkageError | RuntimeException ignored) { }
		penInContact = false;
		// Retain the callback until process exit: a queued native call may still be returning.
	}

	static boolean detected() { return penSeen; }

	static double currentPressure() {
		return penInContact && System.nanoTime() - lastUpdate < STALE_NANOS ? pressure : 1.0;
	}

	private static long handleMessage(Pointer window, int message, long wParam, long lParam) {
		try {
			if (message == WM_POINTERDOWN || message == WM_POINTERUPDATE) {
				PointerPenInfo info = new PointerPenInfo();
				if (user32.GetPointerPenInfo((int) (wParam & 0xFFFF), info)) {
					info.read();
					penSeen = true;
					penInContact = message == WM_POINTERDOWN || (wParam & 0x00040000L) != 0;
					pressure = (info.penMask & PEN_MASK_PRESSURE) != 0
							? Math.clamp(info.pressure / 1024.0, 0.05, 1.0) : 1.0;
					lastUpdate = System.nanoTime();
				}
			} else if (message == WM_POINTERUP || message == WM_POINTERLEAVE) {
				penInContact = false;
			}
		} catch (Throwable ignored) {
			penInContact = false;
		}
		// GLFW still receives every message, including the synthetic mouse events it uses.
		return user32.CallWindowProcW(previousProcedure, window, message, wParam, lParam);
	}

	private interface WindowProc extends StdCallLibrary.StdCallCallback {
		long callback(Pointer window, int message, long wParam, long lParam);
	}

	private interface User32 extends StdCallLibrary {
		Pointer SetWindowLongPtrW(Pointer window, int index, Pointer value);
		Pointer GetWindowLongPtrW(Pointer window, int index);
		long CallWindowProcW(Pointer previous, Pointer window, int message, long wParam, long lParam);
		boolean GetPointerPenInfo(int pointerId, PointerPenInfo info);
		boolean IsWindow(Pointer window);
	}

	@Structure.FieldOrder({"x", "y"})
	public static class Point extends Structure {
		public int x, y;
	}

	@Structure.FieldOrder({"pointerType", "pointerId", "frameId", "pointerFlags", "sourceDevice",
			"hwndTarget", "ptPixelLocation", "ptHimetricLocation", "ptPixelLocationRaw",
			"ptHimetricLocationRaw", "dwTime", "historyCount", "inputData", "dwKeyStates",
			"performanceCount", "buttonChangeType"})
	public static class PointerInfo extends Structure {
		public int pointerType, pointerId, frameId, pointerFlags;
		public Pointer sourceDevice, hwndTarget;
		public Point ptPixelLocation, ptHimetricLocation, ptPixelLocationRaw, ptHimetricLocationRaw;
		public int dwTime, historyCount, inputData, dwKeyStates;
		public long performanceCount;
		public int buttonChangeType;
	}

	@Structure.FieldOrder({"pointerInfo", "penFlags", "penMask", "pressure", "rotation", "tiltX", "tiltY"})
	public static class PointerPenInfo extends Structure {
		public PointerInfo pointerInfo;
		public int penFlags, penMask, pressure, rotation, tiltX, tiltY;
	}
}
