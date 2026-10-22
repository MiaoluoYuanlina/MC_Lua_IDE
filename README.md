<p align="center"><img src="src/main/resources/assets/mc_lua_ide/icon.png" width="128" alt="MC Lua IDE icon"></p>

<h1 align="center">MC Lua IDE</h1>

<p align="center">Lua scripting for Minecraft servers, with a full IDE for administrators right inside the game.</p>

<p align="center">English · <a href="README.zh_cn.md">简体中文</a> · <a href="README.ja_jp.md">日本語</a></p>

---

## What it is

MC Lua IDE is a Fabric mod for **Minecraft 26.1.2**. It lets server administrators write Lua scripts that run **on the server** while the game is running, and gives them an in-game IDE for it: editor, debugger, API reference, terminal, datapack and resource-pack tools.

- **Scripts run on the server.** They can read and change the world, react to events, talk to players and open menus. Ordinary players need nothing installed.
- **The IDE runs on the administrator's client.** Press **U** in game to open it. Only server owners (operator level 4, or the host in singleplayer) can use it; installing the mod grants no rights.
- **Safe by default.** Scripts run in a sandbox with time, output and event limits. Risky features (Java access, file and OS access, the server terminal, MCP for AI tools) are off until a server owner turns them on.

```lua
-- Greet everyone who joins.
MC.Event.onPlayerJoined(function(event)
    MC.Chat.message(event.playerUuid, {text = "Welcome, " .. event.playerName .. "!", color = "#55FF55"})
end)
```

## Features

| Area | What you get |
| --- | --- |
| Editor | Lua, JSON and mcfunction highlighting and live diagnostics; completion for APIs, variables and registry IDs (items, blocks, sounds…); find & replace with regex; go to definition, find references and rename across files; outline; snippets; multiple carets; minimap; split editor; file compare; find in files; command palette |
| Debugger | Breakpoints with conditions, hit counts and log messages; exception breakpoints; step into / over / out; run to cursor; call stack, locals, watches, hover values, coroutines; optional world freeze while paused |
| Minecraft API | Players, items and data components, blocks, entities, events, timers, chat and JSON text, titles, sounds, particles, virtual chest menus, commands, data files, resource packs; full reference with examples inside the IDE |
| Datapacks & resource packs | Edit them next to your scripts: JSON editors with game examples, PNG painter with layers, sound and structure previews, one-click reload |
| Terminal | Local shells (PowerShell, cmd, bash…) and an optional server shell |
| Teams & safety | Who-is-editing hints, conflict-checked saves, audit log, automatic script snapshots with restore, settings export/import, Git history |
| Extending | JSON manifests for snippets and commands; Java APIs for other mods to add Lua functions, IDE windows, commands and completion |

Interface and manual in Chinese, English and Japanese (follows the game language).

## Getting started

Requirements: Minecraft 26.1.2, Fabric Loader 0.19.3+, Fabric API 0.155.2+, Java 25. Put the jar and Fabric API into `mods/` on the server and on the clients of administrators who use the IDE (in singleplayer, just your client).

1. Join the world and press **U**. On the first start a **Welcome** page offers to create a sample project under `examples/`.
2. **File → Lua Files**, then **Ctrl+N** to create a script.
3. Write some code, **Ctrl+S** to save, **Ctrl+Enter** to run. Output appears in the **Run Log**.

## Usage

### Writing scripts

Scripts live on the server in `config/mc_lua_ide/scripts/` (subfolders allowed). Every API is under the `MC` table; the **API Reference** window (**Ctrl+F1**) lists all of it with parameters and examples, and completion suggests functions, arguments and IDs as you type.

```lua
-- Give the first online player three diamonds and print where they are.
local player = MC.Player.list()[1]
if player then
    local pos = MC.Player.position(player.uuid)
    print(player.name, pos.x, pos.y, pos.z)
    MC.Item.give(player.uuid, "minecraft:diamond", 3)
end

-- Place a block (x, y, z, block id).
MC.Block.set(0, 64, 0, "minecraft:gold_block")
```

**Events and time.** Listeners keep a script running until you stop it. `MC.sleep` pauses only the current script, never the game.

```lua
MC.Event.onBlockPlaced(function(event)
    print(event.playerName, "placed", event.blockId, "at", event.x, event.y, event.z)
end)

MC.Event.onInterval(1000, function(event)
    print("one second passed", event.elapsedMs)
end)

MC.Event.onScriptStopped(function()
    MC.Chat.broadcast({text = "Script stopped", color = "#FF5555"})
end)
```

**Modules.** Split code into files and load them with `require`; a module returns a table.

```lua
-- lib/greeting.lua
local M = {}
function M.greet(name) return "Hello, " .. name .. "!" end
return M

-- main.lua
local greeting = require("lib.greeting")
print(greeting.greet("Steve"))
```

**Running.** Use the **Run** button or **Ctrl+Enter** in the IDE, or commands (also from the console and command blocks):

| Command | Does |
| --- | --- |
| `/lua run <script>` | Run a script |
| `/lua testrun <script>` | Run with debugging (breakpoints, variables) |
| `/lua stop` | Stop the running script and unregister its listeners |
| `/lua list` | List scripts |

### The IDE

- **Windows:** everything is a dockable window. Drag titles to dock, split or merge into tabs; the layout is remembered. **UI & Windows → View** opens any tool.
- **Command palette (Ctrl+Shift+P):** every feature by name. **Ctrl+P** opens files, **Ctrl+Shift+O** jumps to a function, **:** followed by a number jumps to a line.
- **Essential shortcuts:**

| Keys | Action |
| --- | --- |
| Ctrl+S · Ctrl+Enter | Save · Run / stop |
| Ctrl+F · Ctrl+H · Ctrl+Shift+F | Find · Replace · Find in files |
| Ctrl+B or Ctrl+click · Ctrl+Shift+B · F2 | Go to definition · Find references · Rename |
| Ctrl+D · Alt+click | Next occurrence · Extra caret |
| Ctrl+I | Insert an ID or a data component at the cursor |
| Tab | Accept completion / expand a snippet (`fn`, `for`, `if`, `pcall`…) |
| Ctrl+/ | Toggle comments |
| Ctrl+` | Terminal |
| F12 | Debugger settings and shortcuts |

- **Personalization:** themes, fonts, cursor, background image, syntax colours, and an option to put the menus into the window's title bar.

### Debugging

1. Click a line number to set a breakpoint. Right-click it for a **condition**, a **hit count** or a **log message** (a logpoint prints instead of pausing).
2. Tick **Debug** in the menu bar (or use `/lua testrun`) and run.
3. When the script pauses: step into / over / out, run to cursor, inspect the call stack and variables, add **watches**, hover over a variable to see its value.

The debugger window can also pause on **errors** (uncaught only, or all) at the line that raised them, and lists the script's **coroutines**. Optionally the world freezes while a script is paused.

### Datapacks and resource packs

**File → Datapacks / Resource Packs** open their workspaces. JSON files get structured editors with templates and examples taken from the running game. PNG textures open in a layered painter, sounds and structures have previews. **Tools** reloads datapacks or resource packs after saving.

### Terminal

**Ctrl+`** opens a terminal tab running a shell on your computer, with colours, mouse support and full-screen programs. A **server terminal** (a shell on the server machine) exists for server owners but is disabled until enabled in `config/mc_lua_ide/terminal.properties`.

### Working together and staying safe

- When another administrator has the same file open, the editor shows it. A save that would overwrite someone else's newer version is refused, and you can compare, overwrite or load theirs.
- The **Audit log** lists who saved, ran or changed what.
- **Backups:** the server snapshots the scripts folder every 30 minutes when something changed, and any snapshot can be restored (a restore can itself be undone). Your IDE settings can be exported and imported.

### Configuration files

All under `config/mc_lua_ide/` (server side unless noted):

| File | Purpose |
| --- | --- |
| `scripts/` | Your Lua scripts |
| `lua-limits.properties` | Execution limits and which risky libraries are allowed (also editable in the IDE) |
| `terminal.properties` | Server terminal (off by default) |
| `snapshots.properties` · `snapshots/` | Automatic script snapshots |
| `audit.log` | Audit log |
| `extensions/` | Your own snippet and command manifests (client) |
| `personalization.properties`, `sessions/` | IDE appearance and per-server layout (client) |

## Documentation

- [In-game manual](docs/manual/en_us.md): the complete Lua API with examples ([中文](docs/manual/zh_cn.md) · [日本語](docs/manual/ja_jp.md)); also readable inside the IDE.
- [IDE guide](docs/guide/ide-guide.md): every IDE feature in detail.
- [Extending the IDE](docs/extensions/overview.md): manifests and Java APIs for other mods.
- [Changelog](CHANGELOG.md)

## Building from source

```bash
./gradlew build
```

The jar is written to `build/libs/`. `build` also runs the smoke tests (Git must be on the `PATH`).

## Status

Tested on Windows. The local terminal and the title-bar integration on macOS and Linux have not been tested on real machines yet. Reports and ideas are welcome in [Issues](https://github.com/MiaoluoYuanlina/MC_Lua_IDE/issues).

## License

[GPL-3.0](LICENSE). Copyright (c) 2026 XiaoMiao_ICa. Bundled libraries keep their own (GPL-compatible) licenses: [LuaJ](https://github.com/luaj/luaj) (MIT), [imgui-java](https://github.com/SpaiR/imgui-java) and [Dear ImGui](https://github.com/ocornut/imgui) (MIT), [JNA](https://github.com/java-native-access/jna) (Apache 2.0 / LGPL 2.1).
