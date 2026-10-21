package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeEditorApi.EditorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The editor extension API and the data-only manifests: validation, isolation of failures, and what manifests may (not) do. */
public final class ExtensionApiSmoke {
	private static int checks;

	public static void main(String[] args) throws Exception {
		javaApi(); manifests(); documentedExample();
		System.out.println("Extension API smoke passed: " + checks + " checks; commands, snippets, completion providers, shells, manifests, limits and isolation");
	}

	private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

	private static void rejects(Runnable action, String message) {
		try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
		throw new AssertionError(message);
	}

	private static final class FakeContext implements EditorContext {
		String inserted = "", copied = "", notice = "", opened = "", source = "local x = 1\nprint(x)", selection = "x";
		boolean editable = true;
		public String documentName() { return "demo"; }
		public String source() { return source; }
		public String selectedText() { return selection; }
		public int caretOffset() { return 5; }
		public int caretLine() { return 2; }
		public boolean insertText(String text) { if (!editable) return false; inserted = text; return true; }
		public void copyText(String text) { copied = text; }
		public void notice(String message) { notice = message; }
		public boolean openWindow(String id) { opened = id; return id.startsWith("known:"); }
	}

	private static void javaApi() {
		List<McLuaIdeEditorApi.Registration> changes = new ArrayList<>();
		McLuaIdeEditorApi.onChange(changes::add);
		FakeContext context = new FakeContext();

		McLuaIdeEditorApi.command("smoke_mod", "wrap").title(Component.literal("Wrap")).shortcutHint("Ctrl+Alt+W").contextMenu(true)
				.enabledWhen(c -> !c.selectedText().isEmpty()).run(c -> c.insertText("(" + c.selectedText() + ")")).register();
		var command = McLuaIdeEditorApi.commands().stream().filter(c -> c.id().equals("smoke_mod:wrap")).findFirst().orElseThrow();
		require(command.contextMenu() && command.enabled().test(context), "command is registered with its menu flag and predicate");
		command.action().accept(context);
		require(context.inserted.equals("(x)"), "the command acts through the editor context");
		rejects(() -> McLuaIdeEditorApi.command("smoke_mod", "wrap").run(c -> { }).register(), "duplicate command ids are rejected");
		rejects(() -> McLuaIdeEditorApi.command("Bad Owner", "x").run(c -> { }).register(), "owner ids are validated");
		rejects(() -> McLuaIdeEditorApi.command("smoke_mod", "Upper").run(c -> { }).register(), "command ids are validated");
		rejects(() -> McLuaIdeEditorApi.command("smoke_mod", "noaction").register(), "a command needs an action");

		McLuaIdeEditorApi.snippet("smoke_mod", "mysnip", "demo", "print(${1:value})$0");
		require(LuaSnippets.find("mysnip") != null && LuaSnippets.find("mysnip").body().contains("${1:value}"), "extension snippets are found by the editor");
		require(LuaSnippets.all().stream().anyMatch(s -> s.trigger().equals("mysnip")) && LuaSnippets.find("for") != null, "built-in and extension templates are listed together");
		rejects(() -> McLuaIdeEditorApi.snippet("smoke_mod", "for", "shadow", "x"), "built-in templates cannot be replaced");
		rejects(() -> McLuaIdeEditorApi.snippet("other_mod", "mysnip", "dup", "x"), "duplicate triggers are rejected");
		rejects(() -> McLuaIdeEditorApi.snippet("smoke_mod", "1bad", "x", "x"), "triggers must be identifiers");
		rejects(() -> McLuaIdeEditorApi.snippet("smoke_mod", "open", "x", "${1:never closed"), "an unclosed placeholder is rejected at registration");
		rejects(() -> McLuaIdeEditorApi.snippet("smoke_mod", "alpha", "x", "${abc:x}"), "a non-numeric placeholder is rejected at registration");
		rejects(() -> McLuaIdeEditorApi.snippet("smoke_mod", "huge", "x", "a".repeat(5000)), "snippet size is bounded");
		var expansion = LuaSnippets.expand(LuaSnippets.find("mysnip"), "");
		require(expansion.text().equals("print(value)") && expansion.stops().get(0).index() == 1, "an extension snippet expands like a built-in one");

		McLuaIdeEditorApi.completionProvider("smoke_mod", "words", request -> List.of(
				new McLuaIdeEditorApi.CompletionItem("MC.Smoke.alpha", "alpha()", "first"), new McLuaIdeEditorApi.CompletionItem("MC.Other.beta"), new McLuaIdeEditorApi.CompletionItem("MC.Smoke")));
		var items = McLuaIdeEditorApi.complete(new McLuaIdeEditorApi.CompletionRequest("demo", "MC.Sm", 5, "MC.Sm", false));
		require(items.size() == 2 && items.get(0).insertion().equals("MC.Smoke.alpha") && items.stream().noneMatch(i -> i.insertion().contains("Other")), "only items that extend the typed prefix are used, got " + items);
		McLuaIdeEditorApi.completionProvider("smoke_mod", "many", request -> { List<McLuaIdeEditorApi.CompletionItem> list = new ArrayList<>(); for (int i = 0; i < 500; i++) list.add(new McLuaIdeEditorApi.CompletionItem("MC.Sm" + i)); return list; });
		require(McLuaIdeEditorApi.complete(new McLuaIdeEditorApi.CompletionRequest("demo", "MC.Sm", 5, "MC.Sm", false)).size() == 52, "a provider contributes at most 50 items");
		int[] calls = {0};
		McLuaIdeEditorApi.completionProvider("smoke_mod", "broken", request -> { calls[0]++; throw new IllegalStateException("boom"); });
		McLuaIdeEditorApi.complete(new McLuaIdeEditorApi.CompletionRequest("demo", "MC.Sm", 5, "MC.Sm", false));
		McLuaIdeEditorApi.complete(new McLuaIdeEditorApi.CompletionRequest("demo", "MC.Sm", 5, "MC.Sm", false));
		require(calls[0] == 1, "a provider that throws is switched off after the first failure");
		rejects(() -> new McLuaIdeEditorApi.CompletionItem("bad\nline"), "multi-line insertions are rejected");

		McLuaIdeEditorApi.shell("smoke_mod", "tool", "Smoke Shell", List.of("some-program", "--flag"));
		require(McLuaIdeEditorApi.shells().stream().anyMatch(s -> s.name().equals("Smoke Shell")), "shell profiles are listed");
		rejects(() -> McLuaIdeEditorApi.shell("smoke_mod", "empty", "X", List.of()), "an empty command is rejected");
		rejects(() -> McLuaIdeEditorApi.shell("smoke_mod", "blank", "X", List.of(" ")), "blank arguments are rejected");

		require(!changes.isEmpty() && changes.contains(McLuaIdeEditorApi.Registration.SNIPPETS), "listeners are told what changed");
		McLuaIdeEditorApi.unregisterOwner("smoke_mod");
		require(McLuaIdeEditorApi.commands().stream().noneMatch(c -> c.ownerModId().equals("smoke_mod")) && LuaSnippets.find("mysnip") == null
				&& McLuaIdeEditorApi.shells().isEmpty(), "unregisterOwner removes every contribution of an owner");
		require(McLuaIdeEditorApi.complete(new McLuaIdeEditorApi.CompletionRequest("demo", "MC.Sm", 5, "MC.Sm", false)).isEmpty(), "providers are removed too");
	}

	private static ExtensionManifests.Source source(String label, String owner, boolean trusted, String json) { return new ExtensionManifests.Source(label, owner, trusted, json); }

	/** The manifest printed in the documentation must load without a single problem. */
	private static void documentedExample() throws java.io.IOException {
		java.nio.file.Path file = java.nio.file.Path.of("docs", "extensions", "examples", "hello-pack.json");
		require(java.nio.file.Files.isRegularFile(file), "the documented example exists at " + file.toAbsolutePath());
		var report = ExtensionManifests.loadSources(List.of(source("hello-pack.json", null, true, java.nio.file.Files.readString(file))), Set.of());
		require(report.problems().isEmpty() && report.loaded().size() == 1, "hello-pack.json loads cleanly: " + report.problems());
		var loaded = report.loaded().get(0);
		require(loaded.owner().equals("hello_pack") && loaded.snippets() == 2 && loaded.commands() == 3, "all documented entries load: " + loaded);
		ExtensionManifests.loadSources(List.of(), Set.of());
	}

	private static void manifests() {
		String good = """
				{ "schema": 1, "id": "pack_one", "name": "Pack One", "version": "1.2",
				  "snippets": [
				    {"trigger": "giveitem", "description": "give", "body": ["MC.Item.give(${1:player}, \\"${2:minecraft:stone}\\")", "$0"]},
				    {"trigger": "for", "body": "shadow"},
				    {"trigger": "badone", "body": "${x:oops}"}
				  ],
				  "commands": [
				    {"id": "wrap", "title": {"zh_cn": "包裹", "en_us": "Wrap"}, "context_menu": true, "action": {"type": "insert", "text": "(${selection})"}},
				    {"id": "where", "title": "Where", "action": {"type": "notice", "text": "${file}:${line}"}},
				    {"id": "win", "title": "Window", "action": {"type": "open_window", "window": "known:tools"}},
				    {"id": "run", "title": "Run", "action": {"type": "run_script", "text": "os.execute('x')"}},
				    {"id": "copy", "title": "Copy", "action": {"type": "copy", "text": "hello"}}
				  ],
				  "shells": [ {"id": "x", "name": "Mine", "command": ["prog"]} ] }
				""";
		var report = ExtensionManifests.loadSources(List.of(source("good.json", null, true, good)), Set.of("fabricloader"));
		require(report.loaded().size() == 1 && report.loaded().get(0).snippets() == 1 && report.loaded().get(0).commands() == 4 && report.loaded().get(0).shells() == 1,
				"valid entries load, invalid ones are skipped: " + report.loaded() + " " + report.problems());
		require(report.problems().stream().anyMatch(p -> p.contains("shadow") || p.contains("built-in")), "shadowing a built-in template is reported");
		require(report.problems().stream().anyMatch(p -> p.contains("run_script")), "unknown action types are reported, not run");
		require(LuaSnippets.find("giveitem").body().contains("\n$0"), "an array body is joined with newlines");

		FakeContext context = new FakeContext();
		var commands = McLuaIdeEditorApi.commands();
		var wrap = commands.stream().filter(c -> c.id().equals("pack_one:wrap")).findFirst().orElseThrow();
		wrap.action().accept(context);
		require(context.inserted.equals("(x)") && wrap.contextMenu(), "insert actions expand ${selection} and honour context_menu");
		commands.stream().filter(c -> c.id().equals("pack_one:where")).findFirst().orElseThrow().action().accept(context);
		require(context.notice.equals("demo:2"), "${file} and ${line} are expanded");
		commands.stream().filter(c -> c.id().equals("pack_one:copy")).findFirst().orElseThrow().action().accept(context);
		require(context.copied.equals("hello"), "copy action");
		commands.stream().filter(c -> c.id().equals("pack_one:win")).findFirst().orElseThrow().action().accept(context);
		require(context.opened.equals("known:tools"), "open_window action");
		context.editable = false;
		wrap.action().accept(context);
		require(!context.notice.isEmpty(), "an insert without an editable file tells the user instead of failing silently");

		var reloaded = ExtensionManifests.loadSources(List.of(), Set.of());
		require(reloaded.loaded().isEmpty() && LuaSnippets.find("giveitem") == null && McLuaIdeEditorApi.commands().isEmpty(), "reloading replaces what the previous load added");

		var rules = ExtensionManifests.loadSources(List.of(
				source("not-json.json", null, true, "[1, 2"),
				source("schema.json", null, true, "{\"schema\": 9, \"id\": \"future_pack\"}"),
				source("noid.json", null, true, "{\"schema\": 1}"),
				source("impersonate.json", null, true, "{\"id\": \"fabricloader\"}"),
				source("dup_a.json", null, true, "{\"id\": \"twin\"}"),
				source("dup_b.json", null, true, "{\"id\": \"twin\"}"),
				source("mod.json", "some_mod", false, "{\"id\": \"ignored\", \"snippets\": [{\"trigger\": \"fromjar\", \"body\": \"x\"}], \"shells\": [{\"id\": \"s\", \"name\": \"Evil\", \"command\": [\"evil\"]}]}"),
				source("huge.json", null, true, "{\"id\": \"big_pack\", \"snippets\": [" + "{\"trigger\": \"s0\", \"body\": \"x\"},".repeat(1) + "{\"trigger\": \"s1\", \"body\": \"x\"}]}")),
				Set.of("fabricloader"));
		require(report != rules && rules.loaded().stream().map(ExtensionManifests.Loaded::owner).toList().equals(List.of("twin", "some_mod", "big_pack")),
				"only well-formed manifests load, got " + rules.loaded());
		require(rules.problems().size() >= 5, "each rejected manifest is explained: " + rules.problems());
		require(LuaSnippets.find("fromjar") != null, "a mod-owned manifest contributes snippets under the mod's id");
		require(McLuaIdeEditorApi.shells().isEmpty(), "shells from mod-owned manifests are refused");
		ExtensionManifests.loadSources(List.of(), Set.of());
	}
}
