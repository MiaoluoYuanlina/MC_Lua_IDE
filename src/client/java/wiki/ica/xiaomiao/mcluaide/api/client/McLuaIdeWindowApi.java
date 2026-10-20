package wiki.ica.xiaomiao.mcluaide.api.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.client.ExtensionWindowHost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Client-only, trusted Java extensions to the IDE's floating/docked window system. */
public final class McLuaIdeWindowApi {
	public static final int EXTENSION_API_VERSION = 2;
	private static final Map<String, Definition> WINDOWS = new LinkedHashMap<>();
	private McLuaIdeWindowApi() { }

	public static Builder window(String ownerModId, String windowId) {
		return new Builder(ownerModId, windowId);
	}

	/** Register during ClientModInitializer, before opening the IDE. Duplicate IDs are rejected. */
	public static synchronized void register(Definition definition) {
		Objects.requireNonNull(definition, "definition");
		if (WINDOWS.putIfAbsent(definition.id(), definition) != null)
			throw new IllegalArgumentException("IDE window already registered: " + definition.id());
	}

	public static synchronized List<Definition> definitions() { return List.copyOf(WINDOWS.values()); }

	/** Client thread only. Does not open an IDE or bypass server administrator authorization. */
	public static boolean open(String id) {
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread()) throw new IllegalStateException("Use Minecraft.execute on the client thread");
		return client.screen instanceof ExtensionWindowHost host && host.setExtensionWindowOpen(id, true);
	}

	public static boolean close(String id) {
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread()) throw new IllegalStateException("Use Minecraft.execute on the client thread");
		return client.screen instanceof ExtensionWindowHost host && host.setExtensionWindowOpen(id, false);
	}

	/**
	 * Legacy window content that draws with Minecraft's {@link GuiGraphicsExtractor}. One content instance per IDE session;
	 * drawing and mouse coordinates are content-local.
	 * <p>
	 * Still supported through a compatibility layer: the IDE rasterizes {@link #render} into a texture, shows it inside the
	 * window and translates mouse and keyboard input back into these callbacks. Each such window logs a warning, sends one
	 * chat notice per game run and shows a banner. Implement {@link NativeContent} instead for the native look and input.
	 */
	public interface Content {
		void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY);
		default void resize(int width, int height) { }
		default void tick() { }
		default void opened() { }
		default void closed() { }
		default void focusChanged(boolean focused) { }
		default boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) { return false; }
		default boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) { return false; }
		default boolean mouseReleased(MouseButtonEvent event) { return false; }
		default boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
		default boolean keyPressed(KeyEvent event) { return false; }
		default boolean keyReleased(KeyEvent event) { return false; }
		default boolean charTyped(CharacterEvent event) { return false; }
		/** Release textures/resources when this IDE session ends, including hidden windows. */
		default void disposed() { }
	}

	/** Native Dear ImGui controls. Called within the IDE's context, window and ID scope.
	 * Do not call NewFrame/Render/Begin for the host window or install GLFW callbacks. */
	public interface NativeContent extends Content {
		void renderImGui();
		@Override default void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) { }
	}

	/** Available on the client thread only; retain no references after disposed(). */
	public interface Context {
		Minecraft minecraft();
		Font font();
		String documentName();
		String source();
		String selectedText();
		/** Insert at the code caret, replacing its selection; false if no editable file is open. */
		boolean insertText(String text);
		void copyText(String text);
		void close();
	}

	public record Definition(String ownerModId, String windowId, Component title,
			int width, int height, int minWidth, int minHeight, Function<Context, Content> factory) {
		public Definition {
			validateOwner(ownerModId);
			if (windowId == null || !windowId.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*"))
				throw new IllegalArgumentException("Invalid windowId: " + windowId);
			Objects.requireNonNull(title, "title");
			title = title.copy();
			Objects.requireNonNull(factory, "factory");
			if (minWidth < 100 || minHeight < 70 || width < minWidth || height < minHeight
					|| width > 4096 || height > 4096)
				throw new IllegalArgumentException("Window size must be >= minimum (100 x 70), and <= 4096");
		}
		public String id() { return ownerModId + ":" + windowId; }
	}

	public static final class Builder {
		private final String owner;
		private final String id;
		private Component title;
		private int width = 360, height = 240, minWidth = 160, minHeight = 100;
		private Function<Context, Content> factory;
		private Builder(String owner, String id) {
			validateOwner(owner);
			this.owner = owner; this.id = id; title = Component.literal(owner + ":" + id);
		}
		public Builder title(Component value) { title = value; return this; }
		public Builder size(int width, int height) { this.width = width; this.height = height; return this; }
		public Builder minimumSize(int width, int height) { minWidth = width; minHeight = height; return this; }
		public Builder content(Function<Context, Content> factory) { this.factory = factory; return this; }
		public Definition build() { return new Definition(owner, id, title, width, height, minWidth, minHeight, factory); }
		public void register() { McLuaIdeWindowApi.register(build()); }
	}

	private static void validateOwner(String owner) {
		if (owner == null || !owner.matches("[a-z][a-z0-9_-]{1,63}"))
			throw new IllegalArgumentException("Invalid ownerModId: " + owner);
	}
}
