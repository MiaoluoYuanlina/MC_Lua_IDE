# Changelog

All notable changes to MC Lua IDE are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-21

First public release, for Minecraft 26.1.2 (Fabric Loader 0.19.3+, Fabric API 0.155.2+, Java 25).

### Scripting
- Server-authoritative Lua (LuaJ) with an allowlist sandbox and configurable execution limits.
- `MC.*` API for players, items, blocks, events, timers, chat and JSON text, virtual menus, sounds, item cooldowns and more.
- Commands: `/lua run`, `/lua testrun` (with debugging) and `/lua stop`.

### IDE (press U)
- Standalone Dear ImGui interface: dockable windows and tabs, themes and personalization, layout and session restore, file recovery after a crash.
- Chinese, English and Japanese interface and in-game manual.
- Editor: highlighting and diagnostics, completion for APIs, variables and resource IDs, find and replace with regex, go to definition, find references and rename (across files for globals and required modules), outline, snippets, multiple carets, minimap, file compare, find in files, quick open and command palette.
- Datapack and resource-pack workspaces with JSON helpers, PNG painter with layers, OGG and structure previews, and reload actions.
- Game viewport window with direct game control.
- Menus can share the window's title bar (Personalization → Window). On by default on Windows; macOS and Linux X11 are untested.

### Debugging
- Breakpoints with conditions, hit counts and log messages; call stack with frame selection; watches; hover values; step into / over / out; run to cursor; optional world freeze while paused.
- Exception breakpoints (uncaught errors only, or all errors) that stop at the line that raised the error.
- Conditions, watches and hover values run with an instruction and time budget, so an endless expression cannot hang the script.
- Coroutine list while paused, with status and position.

### Terminal
- Local terminal (ConPTY on Windows; `script` on Linux and macOS, untested) with a VT/xterm emulator: colours, alternate screen, scrollback, mouse, bracketed paste.
- Optional server terminal: disabled by default, enabled only in `config/mc_lua_ide/terminal.properties`, operator level 4 only, bounded and audited.

### Several administrators, audit and backups
- Who-is-editing hints and conflict-checked saves for Lua scripts (compare, overwrite or load the server version).
- Server audit log of saves, deletes, runs, stops and setting changes, viewable in the IDE.
- Automatic zip snapshots of the script folder with restore (and undo of a restore); IDE settings export and import.
- Split editor: a second editable view of any open file.
- First-run welcome page with a sample project.

### Integrations and extension APIs
- Git history, commit and restore for the script workspace; GitHub sign-in.
- MCP server for AI tools (off by default).
- Java APIs for other mods: Lua functions (`McLuaIdeApi`), documentation pages, registry notes, IDE windows (`McLuaIdeWindowApi`, legacy windows still work with a warning) and editor commands, snippets, completion and shells (`McLuaIdeEditorApi`).
- Data-only extension manifests (JSON) for snippets and simple commands.

### Fixed before release
- Datapack ID and category indexes no longer list the pack root, which logged `Invalid path ''` errors and returned nothing.

### Known limits
- macOS and Linux paths of the terminal and the integrated title bar have not been tested on real machines.
- Code navigation is lexical; it does not follow values through tables or function calls.
- Debugger messages from the server (pause reasons, evaluation errors) are in Chinese.
- Script snapshots cover the Lua scripts folder only, not datapacks.

[1.0.0]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.0
