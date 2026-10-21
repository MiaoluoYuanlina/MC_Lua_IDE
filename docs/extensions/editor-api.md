# Editor API (`McLuaIdeEditorApi`, client)

English · [简体中文](editor-api.zh_cn.md)

For Fabric mod authors. It lets your mod add **commands**, **snippets**, **completion providers** and **terminal shells** to the administrator's IDE. It is client-only trusted Java: the **administrator client needs both MC Lua IDE and your mod**, servers and other players do not. If you only need snippets or simple insert/copy commands, a [manifest](manifests.md) needs no Java at all.

`McLuaIdeEditorApi.EXTENSION_API_VERSION` is `1`. Register from your `ClientModInitializer`, as with [`McLuaIdeWindowApi`](third-party-api.md#5-custom-ide-windows-mcluaidewindowapi-client). Everything here runs on the client thread. The API grants no administrator rights: opening the IDE, reading server files and running scripts stay under the server's checks.

## Commands

```java
McLuaIdeEditorApi.command("test_mod", "wrap_in_pcall")
    .title(Component.translatable("command.test_mod.wrap_in_pcall"))
    .shortcutHint("demo")                     // display only; the API does not bind keys
    .contextMenu(true)                        // also in the editor's right-click menu
    .enabledWhen(context -> !context.selectedText().isEmpty())
    .run(context -> context.insertText("pcall(function()\n" + context.selectedText() + "\nend)"))
    .register();
```

Commands appear in the command palette (Ctrl+Shift+P) and, with `contextMenu(true)`, in the code editor's right-click menu. The full id is `ownerModId:commandId`; duplicates throw `IllegalArgumentException`.

`EditorContext` (valid only while your callback runs; do not keep it):

| Method | Purpose |
| --- | --- |
| `documentName()` | Active file name; empty if none |
| `source()` | Text of the active editor |
| `selectedText()` | Selected text, empty if none |
| `caretOffset()` / `caretLine()` | Caret as a character offset / line (from 1) |
| `insertText(text)` | Insert at the caret, replacing the selection, with normal undo and highlighting; `false` if no editable file is loaded. Does not save or run |
| `copyText(text)` | Copy to the system clipboard |
| `notice(message)` | Show a short message in the IDE |
| `openWindow(windowId)` | Open a registered IDE window; `false` if it is unknown |

If your action or `enabledWhen` throws, the IDE logs the stack trace with the command id, shows a notice and carries on.

## Snippets

```java
McLuaIdeEditorApi.snippet("test_mod", "giveitem", "Give an item",
    "MC.Item.give(${1:playerUuid}, \"${2:minecraft:stone}\")$0");
```

Typing the trigger and pressing Tab expands the body; Tab then moves through `${1:…}`, `${2:…}` … and finally `$0`. Triggers are identifiers up to 32 characters. The built-in templates cannot be replaced and a trigger can be registered once. Bodies are limited to 4000 characters and are checked when you register (unclosed `${`, non-numeric placeholder numbers), so a mistake throws at startup rather than when the player presses Tab.

## Completion providers

```java
McLuaIdeEditorApi.completionProvider("test_mod", "my_api", request -> {
    if (!request.prefix().startsWith("TestMod.")) return List.of();
    return List.of(new McLuaIdeEditorApi.CompletionItem("TestMod.playerCount", "playerCount()", "Number of online players"));
});
```

- Called on the client thread whenever the completion list is recomputed, so it must be **fast and must not block**.
- `CompletionRequest` has `documentName`, `source`, `caretOffset`, `prefix` (the identifier/dotted path before the caret, such as `TestMod.pl`) and `explicit` (the player pressed the completion key).
- An item's `insertion` must start with `prefix` (and differ from it); it replaces the prefix when accepted. Items that do not are dropped. A provider contributes at most 50 items; `insertion` is one line of at most 200 characters.
- Extension items are listed after the IDE's own suggestions and never replace them.
- A provider that throws is switched off for the rest of the session and logged once.
- Completion shows after two typed characters, or immediately when the player asks for it explicitly.

For Lua functions that should be completed, documented and type-checked everywhere, register them with [`McLuaIdeApi`](third-party-api.md#2-registering-lua-functions-mcluaideapi) (server side) instead: those get completion, hover help and argument checks for free.

## Terminal shells

```java
McLuaIdeEditorApi.shell("test_mod", "my_tool", "My Tool Shell", List.of("C:/tools/mytool.exe", "--interactive"));
```

Adds an entry to the local terminal's **+** menu. `command` has 1–32 non-blank arguments; names are 1–40 characters. This starts a program on the player's computer, which is why only trusted Java and the player's own `config/` manifests can add it. The server terminal never uses client-registered shells.

## Reacting to changes and cleaning up

`McLuaIdeEditorApi.onChange(listener)` reports which registry changed (`COMMANDS`, `SNIPPETS`, `COMPLETION`, `SHELLS`). `unregisterOwner(ownerModId)` removes everything an owner registered; the IDE uses it when reloading manifests. Do not unregister other mods' contributions.

Read-only snapshots: `commands()`, `snippets()`, `shells()`.

## Validation summary

| What | Rule |
| --- | --- |
| `ownerModId` | `[a-z][a-z0-9_-]{1,63}` |
| command / provider / shell ids | lowercase letters, digits, `_`, `-`, `.`, with `/` segments |
| duplicates | `IllegalArgumentException`; nobody can override another extension |
| snippet body | 1–4000 characters, valid placeholders |
| completion items | one line, ≤ 200 characters, ≤ 50 per provider |

## Compatibility

New methods may be added in later versions; existing ones keep their behaviour while the API version stays `1`. Do not depend on internal classes such as `ImGuiIdeScreen`, `NativeCodeArea` or `ExtensionManifests`.
