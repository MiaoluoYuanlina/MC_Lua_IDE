package wiki.ica.xiaomiao.mcluaide.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * macOS: the native traffic-light buttons stay where they are, but the title bar becomes transparent and the content extends under
 * it, so the IDE's menus share the title bar row (the IDE leaves room at the left for the buttons). Done with a few Objective-C
 * messages to the NSWindow through the foreign-function API: titlebarAppearsTransparent, a hidden title and the full-size-content-view
 * style bit. Everything is reverted when the feature is switched off.
 */
final class MacFrame {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.dylib", Arena.global());
	private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfByte B = ValueLayout.JAVA_BYTE;
	private static final long FULL_SIZE_CONTENT_VIEW = 1L << 15;

	private static final MethodHandle SEL_REGISTER = LINKER.downcallHandle(OBJC.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(L, ValueLayout.ADDRESS));
	private static final MemorySegment MSG_SEND = OBJC.find("objc_msgSend").orElseThrow();
	private static final MethodHandle SEND_VOID_BOOL = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.ofVoid(L, L, B));
	private static final MethodHandle SEND_VOID_LONG = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.ofVoid(L, L, L));
	private static final MethodHandle SEND_LONG = LINKER.downcallHandle(MSG_SEND, FunctionDescriptor.of(L, L, L));

	private static long window, originalMask;
	private static boolean applied;

	private MacFrame() { }

	private static long selector(String name) throws Throwable {
		try (Arena arena = Arena.ofConfined()) { return (long) SEL_REGISTER.invokeExact(arena.allocateFrom(name)); }
	}

	/** {@code nsWindow} is the pointer from glfwGetCocoaWindow. */
	static void apply(long nsWindow) throws Throwable {
		if (applied) return;
		window = nsWindow;
		originalMask = (long) SEND_LONG.invokeExact(window, selector("styleMask"));
		SEND_VOID_LONG.invokeExact(window, selector("setStyleMask:"), originalMask | FULL_SIZE_CONTENT_VIEW);
		SEND_VOID_BOOL.invokeExact(window, selector("setTitlebarAppearsTransparent:"), (byte) 1);
		SEND_VOID_LONG.invokeExact(window, selector("setTitleVisibility:"), 1L); // NSWindowTitleHidden
		applied = true;
	}

	static void revert() throws Throwable {
		if (!applied) return;
		SEND_VOID_LONG.invokeExact(window, selector("setTitleVisibility:"), 0L);
		SEND_VOID_BOOL.invokeExact(window, selector("setTitlebarAppearsTransparent:"), (byte) 0);
		SEND_VOID_LONG.invokeExact(window, selector("setStyleMask:"), originalMask);
		applied = false;
	}
}
