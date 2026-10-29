# Changelog

All notable changes to MC Lua IDE are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.9] - 2026-10-28

### Fixed
- The code checker rejected a table for `MC.Entity.addData` / `MC.Block.addData` ("expected string") and for the `keys` of `hideData`; tables, lists and SNBT strings are now accepted.

### Tested
- `addData` / `hideData` were run in a real game world: entity and block `minecraft:custom_name` add and hide, SNBT strings, mixed component + NBT tables, protected fields, players, and blocks without a block entity.

## [1.0.8] - 2026-10-28

### Changed
- `MC.Entity.addData` / `hideData` and `MC.Block.addData` / `hideData` take data components: table keys with a namespace such as `["minecraft:custom_name"]` are components, other keys stay NBT fields. The API documentation examples use this table form.

## [1.0.7] - 2026-10-28

### Added
- `MC.Entity.addData` / `MC.Entity.hideData` and `MC.Block.addData` / `MC.Block.hideData`: add (SNBT string or Lua table) or hide top-level data fields on a live entity or a placed block's block entity.

### Changed
- Data component editor: the tree is keyboard driven (arrow keys move and fold, Enter opens the editing panel with the cursor in its input). The editing panel is no longer modal, so the code area stays editable.

### Fixed
- The log window and other live windows kept updating while the game had control, instead of freezing and not scrolling.

## [1.0.6] - 2026-10-28

### Added
- Events `projectileHitBlock` / `projectileHitEntity` (`MC.Event.onProjectileHitBlock`, `MC.Event.onProjectileHitEntity`) for any projectile, with the projectile, its owner, the exact hit point and the block or target.

### Changed
- An MCP change to a file with unsaved edits is applied at once (no dialog); the unsaved text stays in undo, the changed code is highlighted, clicking it selects the change and Esc clears the highlight.
- The insert window and the data component editor opened with Ctrl+I stay open after an insertion.

### Fixed
- Importing code through MCP could open the old window and leave the IDE stuck.

## [1.0.5] - 2026-10-25

### Added
- `MC.Player.lookDirection(uuid)` (the 3D view direction as a unit vector) and `MC.Player.lookingAt(uuid, infinite?)` (the exact fractional coordinates of the block surface under the crosshair, optionally with unlimited range).
- MCP access password: keep the generated token, set your own password, or turn password protection off.
- Data component editor: entities list every data component their class handles, plus their NBT fields (such as `Motion` and `ExplosionPower`) with Chinese, English and Japanese labels for the common ones.

### Fixed
- Gutter: sideways-scrolled code no longer overlaps line numbers and fold arrows.

## [1.0.4] - 2026-10-23

### Added
- Completion after `variable.`: the `event` parameter of `MC.Event` callbacks offers its fields, and so do results of MC API calls that return a table, `MC.Player.list` / `MC.Entity.list` loop entries, table constructors, and strings (`s:upper`).
- `MC.Player.facing(uuid)` (yaw, pitch and compass direction) and `MC.Player.relativePosition(uuid, direction, plane, distance)` (coordinates in front of, behind, left or right of a player, horizontally, vertically or both).

### Fixed
- The MCP "allow non-local IP addresses" toggle (and the write/execute toggles) no longer switch themselves off before they are applied.
- Sideways-scrolled code no longer runs over the line numbers, breakpoint markers and fold arrows.
- The run log follows new output even when it has reached its line cap.
- Entity icons in the insert window are drawn at the right scale instead of showing only a quarter.
- Input methods: the game is told when an ImGui text field is active, modifier keys in the terminal and Ctrl+Space under Chinese, Japanese and Korean are left to the input method (completion is Alt+/ there).

## [1.0.3] - 2026-10-23

### Added
- Command reference window: every command of the connected server with its formats, grouped into one Minecraft category and one category per mod; vanilla commands have descriptions in Chinese, English and Japanese. Double-click a format to insert it into the open file.
- The data component editor and the text JSON editor write command syntax in `.mcfunction` files (and Lua in Lua files), like the insert window already did.
- MCP: an opt-in toggle that allows connections from non-local IP addresses (off by default; the access token is still required).

### Changed
- Personalization shortcuts page: one row per window with a Set button that waits for the key combination, Clear, and translations.
- Run log follows new output; read-only text wraps instead of scrolling sideways; new windows open centred and cascaded; the text JSON preview is larger and wraps.
- Pressing the IDE key (or running `/lua`) while the IDE is controlling the game returns to the IDE you already have open.

## [1.0.2] - 2026-10-22

### Added
- The Run button has an icon: a green play triangle, a red square with a red label while a script runs, and grey when nothing can run.

### Changed
- English and Japanese translations for the main IDE windows (files, Git, resource packs, JSON and component editors, text JSON, personalization, debugger, MCP, AI, server resource packs), plus word-by-word fallback for text built at run time.

## [1.0.1] - 2026-10-21

### Changed
- Relicensed under GPL-3.0.
- In-game manual: compact layout without extra blank space, tables shown as rows, new "Copy whole manual" button.
- API reference: the navigation sidebar opens wider, and descriptions use compact wrapped text with a Copy button.

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

[1.0.9]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.9
[1.0.8]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.8
[1.0.7]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.7
[1.0.6]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.6
[1.0.5]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.5
[1.0.4]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.4
[1.0.3]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.3
[1.0.2]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.2
[1.0.1]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.1
[1.0.0]: https://github.com/MiaoluoYuanlina/MC_Lua_IDE/releases/tag/v1.0.0
