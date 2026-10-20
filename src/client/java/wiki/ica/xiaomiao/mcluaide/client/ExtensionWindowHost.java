package wiki.ica.xiaomiao.mcluaide.client;

/**
 * What the extension-window API needs from whichever IDE screen is open. The public API talks to this small interface instead
 * of naming concrete screen classes, so screens can be replaced without touching it.
 */
public interface ExtensionWindowHost {
	/** @return true when a window with this id exists in the current IDE session and was opened or closed */
	boolean setExtensionWindowOpen(String id, boolean open);
}