package wiki.ica.xiaomiao.mcluaide.api.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Client-only, trusted Java extensions to the code editor: palette/context-menu commands, snippets, completion providers and
 * terminal shell profiles. Register during {@code ClientModInitializer}. Data-only extensions (snippets and simple commands without
 * Java) use extension manifests instead; see docs/extensions/manifests.md.
 */
public final class McLuaIdeEditorApi {
	public static final int EXTENSION_API_VERSION = 1;
	private static final int MAX_SNIPPET_CHARS = 4000, MAX_COMPLETION_ITEMS = 50, MAX_SHELL_ARGUMENTS = 32;
	private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();
	private static final Map<String, Snippet> SNIPPETS = new LinkedHashMap<>();
	private static final Map<String, CompletionProvider> PROVIDERS = new LinkedHashMap<>();
	private static final Map<String, ShellProfile> SHELLS = new LinkedHashMap<>();
	private static final List<Consumer<Registration>> LISTENERS = new ArrayList<>();
	private static final java.util.Set<String> BROKEN = new java.util.HashSet<>();

	private McLuaIdeEditorApi() { }

	/** What the editor offers to extension code. Valid on the client thread while the callback runs; do not keep it. */
	public interface EditorContext {
		/** Name of the file in the active editor tab; empty if none. */
		String documentName();
		String source();
		String selectedText();
		/** Caret as a character offset into {@link #source()}. */
		int caretOffset();
		/** Caret line, starting at 1. */
		int caretLine();
		/** Insert at the caret, replacing the selection, through normal undo/highlighting; false if no editable file is loaded. */
		boolean insertText(String text);
		void copyText(String text);
		/** Shows a short message in the IDE status area. */
		void notice(String message);
		/** Opens a registered IDE window (see {@link McLuaIdeWindowApi}); false if it is unknown. */
		boolean openWindow(String windowId);
	}

	// ---- commands ------------------------------------------------------------------------------------------------------------
	public record Command(String ownerModId, String commandId, Component title, String shortcutHint, boolean contextMenu,
			Predicate<EditorContext> enabled, Consumer<EditorContext> action) {
		public Command {
			validateOwner(ownerModId); validateId(commandId, "commandId");
			Objects.requireNonNull(title, "title"); title = title.copy();
			shortcutHint = shortcutHint == null ? "" : shortcutHint;
			if (shortcutHint.length() > 40) throw new IllegalArgumentException("shortcutHint is too long");
			if (enabled == null) enabled = context -> true;
			if (action == null) throw new IllegalArgumentException("A command needs an action: call run(...)");
		}
		public String id() { return ownerModId + ":" + commandId; }
	}

	public static CommandBuilder command(String ownerModId, String commandId) { return new CommandBuilder(ownerModId, commandId); }

	public static final class CommandBuilder {
		private final String owner, id;
		private Component title;
		private String shortcut = "";
		private boolean contextMenu;
		private Predicate<EditorContext> enabled;
		private Consumer<EditorContext> action;
		private CommandBuilder(String owner, String id) { validateOwner(owner); this.owner = owner; this.id = id; title = Component.literal(owner + ":" + id); }
		public CommandBuilder title(Component value) { title = value; return this; }
		/** Display-only hint shown next to the command (the API does not bind keys). */
		public CommandBuilder shortcutHint(String value) { shortcut = value; return this; }
		/** Also list the command in the editor's right-click menu. */
		public CommandBuilder contextMenu(boolean value) { contextMenu = value; return this; }
		public CommandBuilder enabledWhen(Predicate<EditorContext> value) { enabled = value; return this; }
		public CommandBuilder run(Consumer<EditorContext> value) { action = value; return this; }
		public Command build() { return new Command(owner, id, title, shortcut, contextMenu, enabled, action); }
		public void register() { McLuaIdeEditorApi.register(build()); }
	}

	public static synchronized void register(Command command) {
		Objects.requireNonNull(command, "command");
		if (COMMANDS.putIfAbsent(command.id(), command) != null) throw new IllegalArgumentException("Editor command already registered: " + command.id());
		changed(Registration.COMMANDS);
	}
	public static synchronized List<Command> commands() { return List.copyOf(COMMANDS.values()); }

	// ---- snippets ------------------------------------------------------------------------------------------------------------
	/** Typing {@code trigger} and pressing Tab expands {@code body}. Syntax: {@code ${1:default}}, {@code $1}, {@code $0}; {@code \n} and {@code \t} indent with the line. */
	public record Snippet(String ownerModId, String trigger, String description, String body) {
		public Snippet {
			validateOwner(ownerModId);
			if (trigger == null || !trigger.matches("[A-Za-z_][A-Za-z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid snippet trigger: " + trigger);
			description = description == null ? "" : description;
			if (description.length() > 120) throw new IllegalArgumentException("Snippet description is too long");
			if (body == null || body.isEmpty() || body.length() > MAX_SNIPPET_CHARS) throw new IllegalArgumentException("Snippet body must be 1.." + MAX_SNIPPET_CHARS + " characters");
			validatePlaceholders(body);
		}
	}

	/** Triggers of the built-in templates (fn, for, if…) cannot be replaced; duplicates between extensions are rejected. */
	public static synchronized void snippet(String ownerModId, String trigger, String description, String body) {
		Snippet snippet = new Snippet(ownerModId, trigger, description, body);
		if (BuiltInSnippets.contains(trigger)) throw new IllegalArgumentException("Snippet trigger is used by a built-in template: " + trigger);
		if (SNIPPETS.putIfAbsent(trigger, snippet) != null) throw new IllegalArgumentException("Snippet trigger already registered: " + trigger);
		changed(Registration.SNIPPETS);
	}
	public static synchronized List<Snippet> snippets() { return List.copyOf(SNIPPETS.values()); }
	public static synchronized Snippet snippet(String trigger) { return SNIPPETS.get(trigger); }

	/** The editor registers its template triggers here so that extensions cannot shadow them. */
	public static final class BuiltInSnippets {
		private static final java.util.Set<String> TRIGGERS = new java.util.HashSet<>();
		private BuiltInSnippets() { }
		public static synchronized void set(java.util.Collection<String> triggers) { TRIGGERS.clear(); TRIGGERS.addAll(triggers); }
		static synchronized boolean contains(String trigger) { return TRIGGERS.contains(trigger); }
	}

	private static void validatePlaceholders(String body) {
		for (int i = 0; i < body.length(); i++) {
			if (body.charAt(i) != '$' || i + 1 >= body.length()) continue;
			char next = body.charAt(i + 1);
			if (next == '{') {
				int close = body.indexOf('}', i);
				if (close < 0) throw new IllegalArgumentException("Unclosed ${...} placeholder in snippet");
				int colon = body.indexOf(':', i);
				String number = body.substring(i + 2, colon > 0 && colon < close ? colon : close);
				if (!number.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Placeholder numbers must be 0-99, found: " + number);
				i = close;
			}
		}
	}

	// ---- completion ----------------------------------------------------------------------------------------------------------
	/** What was typed before the caret: {@code prefix} is the identifier/dotted path under completion (for example {@code MC.Ex}). */
	public record CompletionRequest(String documentName, String source, int caretOffset, String prefix, boolean explicit) { }

	/** {@code insertion} must start with the prefix; it replaces the prefix when accepted. */
	public record CompletionItem(String insertion, String label, String tooltip) {
		public CompletionItem {
			Objects.requireNonNull(insertion, "insertion");
			label = label == null || label.isEmpty() ? insertion : label;
			tooltip = tooltip == null ? "" : tooltip;
			if (insertion.isEmpty() || insertion.length() > 200 || insertion.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid completion insertion");
			if (label.length() > 200) label = label.substring(0, 200);
			if (tooltip.length() > 2000) tooltip = tooltip.substring(0, 2000);
		}
		public CompletionItem(String insertion) { this(insertion, insertion, ""); }
	}

	/** Called on the client thread whenever completions are recomputed, so it must be fast and must not block. At most 50 items are used. */
	@FunctionalInterface
	public interface CompletionProvider { List<CompletionItem> complete(CompletionRequest request); }

	public static synchronized void completionProvider(String ownerModId, String providerId, CompletionProvider provider) {
		validateOwner(ownerModId); validateId(providerId, "providerId"); Objects.requireNonNull(provider, "provider");
		String id = ownerModId + ":" + providerId;
		if (PROVIDERS.putIfAbsent(id, provider) != null) throw new IllegalArgumentException("Completion provider already registered: " + id);
		changed(Registration.COMPLETION);
	}

	/** Used by the IDE: asks every provider; one that throws is switched off for the session and logged. */
	public static List<CompletionItem> complete(CompletionRequest request) {
		Map<String, CompletionProvider> snapshot;
		synchronized (McLuaIdeEditorApi.class) { snapshot = new LinkedHashMap<>(PROVIDERS); }
		List<CompletionItem> result = new ArrayList<>();
		for (var entry : snapshot.entrySet()) {
			synchronized (McLuaIdeEditorApi.class) { if (BROKEN.contains(entry.getKey())) continue; }
			try {
				List<CompletionItem> items = entry.getValue().complete(request);
				if (items == null) continue;
				int taken = 0;
				for (CompletionItem item : items) {
					if (item == null || !item.insertion().startsWith(request.prefix()) || item.insertion().equals(request.prefix())) continue;
					result.add(item);
					if (++taken >= MAX_COMPLETION_ITEMS) break;
				}
			} catch (Throwable error) {
				synchronized (McLuaIdeEditorApi.class) { BROKEN.add(entry.getKey()); }
				wiki.ica.xiaomiao.mcluaide.McLuaIdeMod.LOGGER.error("Completion provider {} failed and is disabled for this session", entry.getKey(), error);
			}
		}
		return result;
	}

	// ---- terminal shells -----------------------------------------------------------------------------------------------------
	/** A program the local terminal can start. Local only: the server terminal never runs client-registered programs. */
	public record ShellProfile(String ownerModId, String shellId, String name, List<String> command) {
		public ShellProfile {
			validateOwner(ownerModId); validateId(shellId, "shellId");
			if (name == null || name.isBlank() || name.length() > 40) throw new IllegalArgumentException("Shell name must be 1..40 characters");
			Objects.requireNonNull(command, "command");
			if (command.isEmpty() || command.size() > MAX_SHELL_ARGUMENTS || command.stream().anyMatch(part -> part == null || part.isBlank() || part.length() > 1000))
				throw new IllegalArgumentException("Shell command must have 1.." + MAX_SHELL_ARGUMENTS + " non-blank arguments");
			command = List.copyOf(command);
		}
		public String id() { return ownerModId + ":" + shellId; }
	}

	public static synchronized void shell(String ownerModId, String shellId, String name, List<String> command) {
		ShellProfile profile = new ShellProfile(ownerModId, shellId, name, command);
		if (SHELLS.putIfAbsent(profile.id(), profile) != null) throw new IllegalArgumentException("Shell already registered: " + profile.id());
		changed(Registration.SHELLS);
	}
	public static synchronized List<ShellProfile> shells() { return List.copyOf(SHELLS.values()); }

	// ---- ownership and change notification -------------------------------------------------------------------------------------
	/** Which registry changed; the IDE re-reads what it caches. */
	public enum Registration { COMMANDS, SNIPPETS, COMPLETION, SHELLS }

	public static synchronized void onChange(Consumer<Registration> listener) { LISTENERS.add(Objects.requireNonNull(listener)); }

	private static void changed(Registration kind) { for (var listener : List.copyOf(LISTENERS)) listener.accept(kind); }

	/**
	 * Removes everything registered under {@code ownerModId}. Meant for the manifest loader's reload; a mod should not unregister
	 * other mods' contributions.
	 */
	public static synchronized void unregisterOwner(String ownerModId) {
		COMMANDS.values().removeIf(command -> command.ownerModId().equals(ownerModId));
		SNIPPETS.values().removeIf(snippet -> snippet.ownerModId().equals(ownerModId));
		SHELLS.values().removeIf(shell -> shell.ownerModId().equals(ownerModId));
		PROVIDERS.keySet().removeIf(id -> id.startsWith(ownerModId + ":"));
		BROKEN.removeIf(id -> id.startsWith(ownerModId + ":"));
		for (Registration kind : Registration.values()) changed(kind);
	}

	private static void validateOwner(String owner) {
		if (owner == null || !owner.matches("[a-z][a-z0-9_-]{1,63}")) throw new IllegalArgumentException("Invalid ownerModId: " + owner);
	}
	private static void validateId(String id, String what) {
		if (id == null || !id.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")) throw new IllegalArgumentException("Invalid " + what + ": " + id);
	}
}
