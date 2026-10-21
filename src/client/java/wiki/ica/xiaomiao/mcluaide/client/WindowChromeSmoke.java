package wiki.ica.xiaomiao.mcluaide.client;


/** The title-bar integration: platform default, detection and the explanation shown in the settings (no window needed). */
public final class WindowChromeSmoke {
	private static int checks;

	public static void main(String[] args) throws Exception {
		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		require(WindowChrome.defaultEnabled() == windows, "on by default only on Windows, where it is verified");
		require(!WindowChrome.active(), "nothing is active before the IDE draws a frame");
		require(WindowChrome.supported() == (WindowChrome.kind() != WindowChrome.Kind.NONE), "support follows the detected platform");
		require(!WindowChrome.supportNote().isBlank(), "the settings page always has an explanation");
		require(WindowChrome.leftInset() == 0f, "no inset while inactive");

		System.out.println("Window chrome smoke passed: " + checks + " checks; platform default, platform detection and explanation text");
	}

	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
