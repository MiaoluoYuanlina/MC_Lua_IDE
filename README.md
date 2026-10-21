<p align="center"><img src="src/main/resources/assets/mc_lua_ide/icon.png" width="128" alt="MC Lua IDE icon"></p>

<h1 align="center">MC Lua IDE</h1>

<p align="center">Server-side Lua scripting for Minecraft, with a full IDE for administrators: right inside the game.</p>

<p align="center">English · <a href="README.zh_cn.md">简体中文</a></p>

---

MC Lua IDE is a Fabric mod for **Minecraft 26.1.2** that lets server administrators write, run and debug Lua scripts on the server while the game is running. Scripts run on the authoritative server with configurable limits; ordinary players don't need to install anything.

```lua
print("Hello from the server!")
for _, player in ipairs(MC.Player.list()) do
    print(player.name, player.uuid)
end
```

## Features

- **In-game IDE** (press **U**): dockable windows, tabs, themes, Chinese / English / Japanese interface.
- **Editor**: syntax highlighting and diagnostics, completion for APIs, variables and resource IDs, find & replace (regex), go to definition, find references and rename (across files), outline, snippets, multiple carets, minimap, file compare, find in files, command palette.
- **Debugger**: breakpoints with conditions, hit counts and log messages, exception breakpoints, call stack, watches, hover values, coroutine list, step into / over / out, run to cursor, optional world freeze.
- **Minecraft API**: players, items, blocks, events, timers, chat and JSON text, virtual menus, sounds and more, with an API reference built into the IDE.
- **Datapacks and resource packs**: edit both alongside your scripts, with JSON helpers, a PNG painter, sound and structure previews, and reload buttons.
- **Teams and safety**: who-is-editing hints, conflict-checked saves, an audit log, automatic script snapshots with restore, settings export/import, and a split editor.
- **Built-in terminal**: local shells, plus an optional server shell (off by default, owner-only).
- **Integrations**: Git history, MCP for AI tools (off by default), and APIs for other mods to add Lua functions, IDE windows, commands, snippets and completion.

## Requirements

| | |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.155.2 or newer |
| Java | 25 |

Install on the **server** (or in singleplayer) and on the clients of **administrators** who use the IDE. Using the IDE needs operator level 4 (or the singleplayer host). Installing the client mod grants no rights.

## Getting started

1. Put `mc_lua_ide-<version>.jar` and Fabric API in the `mods` folder of the server and the administrator's client.
2. Join the world and press **U**.
3. **File → Lua Files** → create a script, press **Ctrl+S** to save, **Ctrl+Enter** to run.

Commands: `/lua run <script>`, `/lua testrun <script>` (with debugging), `/lua stop`.

## Documentation

All documentation is in [`docs/`](docs/README.md):

- [IDE guide](docs/guide/ide-guide.md): windows, editor, debugging, terminal.
- In-game manual ([English](docs/manual/en_us.md) · [简体中文](docs/manual/zh_cn.md) · [日本語](docs/manual/ja_jp.md)): the full Lua API, also readable inside the IDE.
- [Extending the IDE](docs/extensions/overview.md): JSON manifests and Java APIs for other mods.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. `build` also runs the smoke tests; Git must be on the `PATH` for the Git workspace test.

## Status and known limits

- Tested on Windows. The local terminal and the integrated title bar on **macOS and Linux** have not been tested on real machines yet.
- Code navigation is lexical: it follows globals and required modules across files, but not values passed through tables or function calls.
- Bug reports and suggestions: [GitHub issues](https://github.com/MiaoluoYuanlina/MC_Lua_IDE/issues).

## License

[MIT](LICENSE). Bundled libraries keep their own licenses: [LuaJ](https://github.com/luaj/luaj) (MIT), [imgui-java](https://github.com/SpaiR/imgui-java) and [Dear ImGui](https://github.com/ocornut/imgui) (MIT), [JNA](https://github.com/java-native-access/jna) (Apache 2.0 / LGPL 2.1).
