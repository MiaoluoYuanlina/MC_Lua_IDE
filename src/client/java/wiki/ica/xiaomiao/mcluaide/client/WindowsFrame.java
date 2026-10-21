package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Windows: the game window keeps its native frame (resize borders, shadow, Aero Snap, rounded corners, double-click to maximise,
 * dragging) but the title bar is no longer drawn by the system: the client area starts at the top edge and the IDE draws its menus
 * and window buttons there. The window procedure is subclassed through the foreign-function API for three messages only:
 * {@code WM_NCCALCSIZE} (no system caption), {@code WM_NCHITTEST} (the empty part of the IDE's title strip counts as the caption, the
 * top edge as a resize border) and nothing else. Everything else, and any failure, is passed to the original procedure.
 */
final class WindowsFrame {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
	private static final ValueLayout.OfLong H = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	private static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;

	private static MethodHandle function(String name, FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(USER32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
	}
	private static final MethodHandle SET_WINDOW_LONG_PTR = function("SetWindowLongPtrW", FunctionDescriptor.of(H, H, I, H));
	private static final MethodHandle CALL_WINDOW_PROC = function("CallWindowProcW", FunctionDescriptor.of(H, H, H, I, H, H));
	private static final MethodHandle SET_WINDOW_POS = function("SetWindowPos", FunctionDescriptor.of(I, H, H, I, I, I, I, I));
	private static final MethodHandle SCREEN_TO_CLIENT = function("ScreenToClient", FunctionDescriptor.of(I, H, P));
	private static final MethodHandle GET_CLIENT_RECT = function("GetClientRect", FunctionDescriptor.of(I, H, P));
	private static final MethodHandle IS_ZOOMED = function("IsZoomed", FunctionDescriptor.of(I, H));
	private static final MethodHandle GET_DPI = function("GetDpiForWindow", FunctionDescriptor.of(I, H));
	private static final MethodHandle METRICS_FOR_DPI = function("GetSystemMetricsForDpi", FunctionDescriptor.of(I, I, I));

	private static final int GWLP_WNDPROC = -4, WM_NCCALCSIZE = 0x0083, WM_NCHITTEST = 0x0084;
	private static final int HTCLIENT = 1, HTCAPTION = 2, HTTOP = 12, HTTOPLEFT = 13, HTTOPRIGHT = 14;
	private static final int SM_CXSIZEFRAME = 32, SM_CYSIZEFRAME = 33, SM_CXPADDEDBORDER = 92;
	private static final int SWP_FLAGS = 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020; // NOSIZE NOMOVE NOZORDER NOACTIVATE FRAMECHANGED

	private static long window, previous;
	private static MemorySegment stub;
	private static volatile boolean enabled;
	private static volatile float titleHeight = 30, dragStart, dragEnd;
	private static final Arena SCRATCH = Arena.global();
	private static final MemorySegment POINT = SCRATCH.allocate(8), RECT = SCRATCH.allocate(16);

	private WindowsFrame() { }

	/** Subclasses the window. Must run on the thread that owns the window (the game's render thread). */
	static void install(long hwnd) throws Throwable {
		if (previous != 0) { enabled = true; refresh(); return; }
		MethodHandle target = MethodHandles.lookup().findStatic(WindowsFrame.class, "procedure",
				MethodType.methodType(long.class, long.class, int.class, long.class, long.class));
		stub = LINKER.upcallStub(target, FunctionDescriptor.of(H, H, I, H, H), Arena.global());
		window = hwnd; enabled = true;
		previous = (long) SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWLP_WNDPROC, stub.address());
		if (previous == 0) { enabled = false; throw new IllegalStateException("SetWindowLongPtr failed"); }
		refresh();
	}

	/** Passes everything through again and gives the window its system title bar back. */
	static void disable() {
		if (previous == 0 || !enabled) return;
		enabled = false;
		try { refresh(); } catch (Throwable ignored) { }
	}

	static void enable() {
		if (previous == 0 || enabled) return;
		enabled = true;
		try { refresh(); } catch (Throwable ignored) { }
	}

	static boolean installed() { return previous != 0; }

	/** Layout from the IDE (client pixels): the height of the title strip and the horizontal span that behaves like a caption. */
	static void layout(float height, float dragFrom, float dragTo) { titleHeight = height; dragStart = dragFrom; dragEnd = dragTo; }

	private static void refresh() throws Throwable {
		int ignored = (int) SET_WINDOW_POS.invokeExact(window, 0L, 0, 0, 0, 0, SWP_FLAGS);
	}

	private static long callPrevious(long hwnd, int msg, long wParam, long lParam) throws Throwable {
		return (long) CALL_WINDOW_PROC.invokeExact(previous, hwnd, msg, wParam, lParam);
	}

	private static int frameThickness(long hwnd, boolean horizontal) throws Throwable {
		int dpi = (int) GET_DPI.invokeExact(hwnd);
		if (dpi <= 0) dpi = 96;
		int size = (int) METRICS_FOR_DPI.invokeExact(horizontal ? SM_CXSIZEFRAME : SM_CYSIZEFRAME, dpi);
		int padded = (int) METRICS_FOR_DPI.invokeExact(SM_CXPADDEDBORDER, dpi);
		return size + padded;
	}

	/** The window procedure. Never throws: a failure switches the feature off and defers to the original procedure. */
	@SuppressWarnings("unused") // called through the upcall stub
	private static long procedure(long hwnd, int msg, long wParam, long lParam) {
		try {
			if (!enabled || msg != WM_NCCALCSIZE && msg != WM_NCHITTEST) return callPrevious(hwnd, msg, wParam, lParam);
			if (msg == WM_NCCALCSIZE) {
				if (wParam == 0) return callPrevious(hwnd, msg, wParam, lParam);
				MemorySegment params = MemorySegment.ofAddress(lParam).reinterpret(48);
				int top = params.get(I, 4);
				long result = callPrevious(hwnd, msg, wParam, lParam);   // standard left, right and bottom borders
				boolean maximized = (int) IS_ZOOMED.invokeExact(hwnd) != 0;
				// No system caption. A maximised window overhangs the screen by its frame, so keep the content inside.
				params.set(I, 4, maximized ? top + frameThickness(hwnd, false) : top);
				return result;
			}
			long hit = callPrevious(hwnd, msg, wParam, lParam);          // left, right and bottom resize borders
			if (hit != HTCLIENT) return hit;
			POINT.set(I, 0, (short) (lParam & 0xFFFF)); POINT.set(I, 4, (short) ((lParam >> 16) & 0xFFFF));
			int okPoint = (int) SCREEN_TO_CLIENT.invokeExact(hwnd, POINT);
			int okRect = (int) GET_CLIENT_RECT.invokeExact(hwnd, RECT);
			if (okPoint == 0 || okRect == 0) return hit;
			int x = POINT.get(I, 0), y = POINT.get(I, 4), width = RECT.get(I, 8);
			boolean maximized = (int) IS_ZOOMED.invokeExact(hwnd) != 0;
			int edge = Math.max(4, frameThickness(hwnd, false) / 2);
			if (!maximized && y >= 0 && y < edge) return x < edge * 2 ? HTTOPLEFT : x >= width - edge * 2 ? HTTOPRIGHT : HTTOP;
			if (y >= 0 && y < titleHeight && x >= dragStart && x < dragEnd) return HTCAPTION;
			return HTCLIENT;
		} catch (Throwable error) {
			enabled = false;
			McLuaIdeMod.LOGGER.error("Integrated title bar failed and was switched off", error);
			try { return callPrevious(hwnd, msg, wParam, lParam); } catch (Throwable fatal) { return 0; }
		}
	}
}
