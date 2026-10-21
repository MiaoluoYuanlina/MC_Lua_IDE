# Extending MC Lua IDE

English · [简体中文](overview.zh_cn.md)

There are two ways to extend the IDE. Pick by what you need, not by who you are.

| You want to… | Use | Needs Java | Where it runs |
| --- | --- | --- | --- |
| Add snippets, or commands that insert / copy text, to your own IDE or a modpack | [Extension manifest](manifests.md) (JSON) | No | Administrator client |
| Add completion that reads your own data, enabled/disabled commands, terminal shells from a mod | [Editor API](editor-api.md) | Yes | Administrator client |
| Add draggable tool windows | [Window API](third-party-api.md#5-custom-ide-windows-mcluaidewindowapi-client) | Yes | Administrator client |
| Add Lua functions (`MC.Example.welcome()`), documentation pages, registry notes | [Lua / docs / registry API](third-party-api.md) | Yes | **Server** only |

Rules that apply to everything:

- Extensions get no administrator rights. Opening the IDE, reading server files and running scripts stay under the server's checks.
- Names are always owned: `ownerModId:id`. Nobody can override another extension's command, snippet trigger, window or Lua function; duplicates are rejected.
- A broken extension is isolated. A command that throws shows a notice, a completion provider that throws is switched off, a bad manifest entry is skipped with a message in `latest.log`.
- Anything that starts a program on the player's computer (terminal shells) is only available from trusted Java or the player's own `config/` files, never from a manifest inside a mod, and never on the server.

## Quick start

**Data only** — drop a file in `config/mc_lua_ide/extensions/`, for example [`examples/hello-pack.json`](examples/hello-pack.json), then run *Reload extension manifests* from the command palette.

**Java** — in your `ClientModInitializer`:

```java
McLuaIdeEditorApi.snippet("test_mod", "giveitem", "Give an item", "MC.Item.give(${1:player}, \"${2:minecraft:stone}\")$0");
McLuaIdeEditorApi.command("test_mod", "say_hi").title(Component.literal("Say hi"))
    .run(context -> context.notice("Hi from " + context.documentName())).register();
```

## Dependency setup

See [section 1 of the third-party API manual](third-party-api.md#1-setting-up-your-project) for the Gradle dependency and `fabric.mod.json` setup. `McLuaIdeEditorApi` and `McLuaIdeWindowApi` exist only in the client source set: register them from `ClientModInitializer#onInitializeClient` and never reference them from server code (a dedicated server would crash with a missing class). The editor API needs no ImGui dependency; only windows that draw with ImGui do.

## Compatibility promises

| API | Version constant | Notes |
| --- | --- | --- |
| `McLuaIdeEditorApi` | `EXTENSION_API_VERSION = 1` | New methods may be added; existing behaviour is kept |
| `McLuaIdeWindowApi` | `EXTENSION_API_VERSION = 2` | Legacy `Content` windows still work and show a warning |
| Manifests | `"schema": 1` | Other schema numbers are rejected with a clear message |
