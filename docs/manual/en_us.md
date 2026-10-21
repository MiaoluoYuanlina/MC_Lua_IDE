# MC Lua IDE User Guide

## Installation and permissions

Install the mod on the server and on administrator clients that use the IDE. Ordinary players do not need it unless the server enables forced-client mode. Script files and execution belong to the server. Administrative permissions and the server's Lua limits control editing, execution and APIs. Installing an administrator client does not grant server privileges.

Client-only APIs are a separate category in the API reference. They require a compatible mod on the target player's client and forced-client mode on the server. Vanilla menus, chat and ordinary server control do not have this requirement.

The IDE follows Minecraft's selected language. Chinese variants keep the Chinese interface and Pinyin-initial search. Japanese uses a Japanese interface and guide, with kana/romaji reading search and a separate Romaji toggle. Other languages use English; Pinyin and Romaji controls are hidden. IDs, code, user-provided text and saved file names are never translated.

## First steps

Press **U** to open the IDE. Use **File → Lua Files** to create or open a script. Subfolders are supported; separate path components with `/`. Click files to open them in editor tabs. Right-click files and folders for creation, rename, deletion and local-folder actions where available.

Use **Ctrl+S** to save. The toolbar has a dedicated Run/Stop button. **Ctrl+Enter** runs the current script; Stop unregisters script-owned listeners and cancels pending waits. Lua scripts run on the authoritative server, not as the local player. Player APIs normally need an explicit online player UUID.

```lua
print("Hello from the server!")
local players = MC.Player.list()
for _, player in ipairs(players) do
    print(player.name, player.uuid)
end
```

Commands include `/lua run <script>`, `/lua testrun <script>` for debugging, and `/lua stop`. Command completion includes script subfolders. Normal `run` does not enable variable/breakpoint debugging.

## Editing and shortcuts

- **Tab** accepts completion; arrow keys select suggestions. Completion includes local variables, API methods, compatible argument variables and registered resource IDs.
- **Ctrl+I** opens the context-sensitive insertion tool. It selects the relevant ID category, component editor or text JSON editor according to the cursor's parameter.
- **Ctrl+F** finds text; an editor selection can fill the search box.
- **Ctrl+A/C/X/V** select all, copy, cut and paste in the focused editor or input.
- **Ctrl+Z** undoes; **Ctrl+Shift+Z** redoes. Undo keeps the cursor near the edited code.
- **Ctrl+F1/F2/F3/F4** open API Reference, ID Picker, Run Log and Data Components. Configure modifier/F-key bindings in Personalization.
- **Ctrl+mouse wheel** changes code font size. With word wrap disabled, **Shift+wheel** scrolls horizontally.
- Hover a function for parameter help; **Ctrl+click** or **Ctrl+F1** opens its reference.

**Tools → Format Code** applies four-space indentation. Folding controls expand all, collapse all, or collapse functions. Syntax diagnostics underline problems and mark the corresponding line number. Static checks are helpful, but runtime data and third-party APIs can require server validation.

## Windows and appearance

Use **UI & Windows → View** to open tools. Drag window headers to move windows, drag highlighted edges to resize, and drag to the left, right or bottom to dock. Several windows can share a dock as tabs. Drag the tabs to reorder them. Close windows using the top-right close button.

Lock Layout prevents moving docked windows. Personalization can still allow divider resizing while the layout is locked. Floating windows remain movable. Layout, visible tools, tabs and selected documents are restored on reopening. Unsaved files prompt on close; File Recovery helps recover drafts after an abnormal exit.

Personalization includes font size, line height, character width, scroll sensitivity, word wrap, cursor shape/blink, background image scaling/alignment/opacity, shortcut bindings and custom hexadecimal syntax colors. Save & Apply commits the settings; Discard Changes cancels the draft.

## Lua basics

```lua
local level = 5                 -- local variable
local enabled = true            -- boolean
local message = "Hello"         -- string
local list = {"first", "second"} -- arrays start at 1
local config = {count = 3}      -- table fields

local function describe(value)
    if value > 3 then
        return "large"
    else
        return "small"
    end
end

for index, value in ipairs(list) do
    print(index, value, describe(level))
end
```

Use `local` unless you intentionally need a global. Event callbacks retain captured locals. Tables and callbacks share state within a script. Dot calls such as `MC.Item.give(...)` are not colon calls: `:` adds an extra receiver argument.

Modules can be loaded with `require`. Export functions in a table and return that table. Use module names corresponding to server script subfolders. The editor can suggest exported module functions where it can resolve the source.

## Players, blocks and items

```lua
local player = MC.Player.list()[1]
if player then
    local uuid = player.uuid
    local pos = MC.Player.position(uuid)
    if pos then print(player.name, pos.x, pos.y, pos.z) end
    MC.Item.give(uuid, "minecraft:diamond", 3)
end
```

Item IDs such as `minecraft:diamond` are not inventory slot numbers. Inventory slots are zero-based and differ from menu slot IDs. `MC.Item.held(uuid)` reads the main hand. `getSlot`/`setSlot` use inventory slots; menu functions operate on the currently opened menu. Cursor-carried items are separate from the main hand.

```lua
local player = MC.Player.list()[1]
if player then
    MC.Item.give(player.uuid, "minecraft:knowledge_book", 1, {
        ["minecraft:custom_data"] = {new_type = "fire_charge"},
        ["minecraft:item_name"] = "Fire Charge",
        ["minecraft:item_model"] = "minecraft:fire_charge",
        ["minecraft:lore"] = {"Right-click to activate"}
    })
end
```

Creation APIs accept complete component patches. Item filters (`count`, `clear`, trade costs and cooldown filters) check only the supplied component keys. `minecraft:custom_data` uses recursive NBT subset matching; other supplied component values must match completely. Omitted components or `{}` impose no component restriction. Different item IDs never match. Reward merging still uses exact components.

```lua
local filter = {["minecraft:custom_data"] = {new_type = "fire_charge"}}
local player = MC.Player.list()[1]
if player then
    print(MC.Item.count(player.uuid, "minecraft:knowledge_book", filter))
    MC.Item.clear(player.uuid, "minecraft:knowledge_book", 1, filter)
end
```

`MC.Block.get` reads loaded blocks, states and block entity data. Blocks, entities and item stacks have different data formats: do not treat entity equipment or block facing as ordinary item components. The Data Components window selects the appropriate editor and dynamic registry suggestions.

An unloaded chunk, offline player or missing entity can return `nil, reason` with a yellow warning. Check the result before indexing it. Invalid arguments, missing permissions, unknown IDs and other genuine errors can still stop a script.

## Item use cooldowns

```lua
local player = MC.Player.list()[1]
if player then
    local uuid = player.uuid
    print(MC.Item.isOnCooldown(uuid, "minecraft:ender_pearl"))
    MC.Item.startCooldown(uuid, "minecraft:ender_pearl", 100)
    MC.Item.setCooldown(uuid, "minecraft:ender_pearl", 40)
    MC.Item.clearCooldown(uuid, "minecraft:ender_pearl")
end
```

Times are integer game ticks, normally 20 ticks per second, from 0 to 1728000. Zero immediately clears a cooldown. `startCooldown` without ticks uses `use_cooldown.seconds`, or 20 ticks when absent. `setCooldown` replaces the current remaining time from now; it does not modify future default cooldown components. The modifying functions return the number of distinct matching cooldown groups. No matching inventory item means no modification.

Vanilla cooldowns belong to a group, not a slot. The default group is the item ID; `use_cooldown.cooldown_group` can override it. Items sharing a group cool down and recover together. These APIs do not provide isolated per-slot cooldowns, attack charge timers, or automatic suppression of Lua events.

## Events and timing

```lua
MC.Event.onBlockPlaced(function(event)
    print(event.playerName, event.blockId, event.x, event.y, event.z)
end)

MC.Event.onInterval(1000, function(event)
    print("One interval", event.elapsedMs)
end)

MC.Event.onScriptStopped(function(event)
    print("Cleaning up")
end)
```

Listeners keep the script running. Stop the script to unregister them. `MC.sleep(milliseconds)` pauses the current coroutine only; other event callbacks can continue. It does not freeze Minecraft. Callbacks share local tables, so design state changes accordingly.

Player death and respawn are separate events. Entity death applies to living entities and is not a general unload/remove notification. Use the API Reference for the specific payload and constraints of an event.

## Chat, JSON text and virtual menus

Chat/title functions that accept text components support plain strings, JSON strings and Lua text-component tables. The Text JSON window supports segments, styling, hexadecimal colors, gradients, scores, selectors, hover/click events and Lua-variable concatenation. Preview is illustrative: some server-resolved values require a player or runtime context.

```lua
MC.Chat.broadcast({text = "Hello!", color = "#55AAFF", bold = true})
local player = MC.Player.list()[1]
if player then
    MC.Player.openChest(player.uuid, "Example Shop", {
        {id = "minecraft:diamond", count = 1},
        {id = "minecraft:apple", count = 8}
    }, 3, true)
end
```

Virtual chest UIs are not world containers. Do not use them as persistent storage. Read-only menus reject transfers server-side; use click events to process purchases, and atomic `MC.Item.trade` to avoid partial charges. Item-to-cursor events are notifications of actual changes, not cancellable pre-transfer hooks. Worldless containers have nil coordinates.

## Debugging, logs and recovery

Use test-run mode to collect local/global/upvalue snapshots and enable breakpoints. F12 opens debugging controls and HUD toggles. The Debugger window shows the paused statement, stack, breakpoints, Continue/Step/Stop controls and pause-game options. Pause-game settings can affect the whole server; movement and camera options have separate scopes.

The Variables window supports drag selection, double-click selection of a row, Ctrl+A/C and a Copy button. Selecting text freezes the displayed snapshot; Esc clears the selection and resumes updates. The normal noninteractive game HUD is separate. Run Log text can also be selected and copied; completed runs retain their logs until closed.

AI error analysis is disabled by default. When enabled, relevant runtime errors and code are sent to the configured provider and may incur charges. It does not continuously analyze code while typing. MCP access is disabled by default and uses the configured authentication and permissions. Changes from MCP are highlighted in the IDE; hover and press Esc to dismiss those marks.

## Editor tools, advanced debugging and the terminal

Editor: Ctrl+F / Ctrl+H find and replace (case, whole word, regex), bracket matching and auto-close, minimap, indent guides, Ctrl+G go to line, Ctrl+B go to definition, Ctrl+Shift+B find references, F2 rename (current file), outline, Ctrl+D / Alt+click multiple carets, snippets (type `fn`, `for`, `if`… then Tab), file compare, Ctrl+Shift+F find in files, Ctrl+P quick open and Ctrl+Shift+P command palette.

Breakpoints: right-click the gutter to add a condition, a hit count or a log message (logpoint). While paused, click a call-stack frame to inspect it, add expressions to the Watch list, hover a variable to see its value, or choose Run to cursor from the editor menu.

Terminal: open it from View → Terminal or Ctrl+`. The + button starts a shell on your computer, or a server terminal. A server terminal is a shell on the server machine; it is disabled until the server operator sets `enabled=true` in `config/mc_lua_ide/terminal.properties`, and only operator level 4 can use it.

Extensions: snippets and simple commands can be added without Java through JSON files in `config/mc_lua_ide/extensions`; use Reload extension manifests in the command palette after editing. See docs/extensions in the project for the manifest format and the editor API.

## Datapacks and resource packs

Open their separate directories from File. Right-click for contextual creation templates, namespace/folder creation, rename and other supported actions. JSON files open in the main editor, while Datapack JSON / Resource Pack JSON are helper windows for templates, game examples and structured editing. `Ctrl+I` uses the current JSON or function-command argument context.

Use Tools to reload datapacks or enabled client resource packs after saving. Reloads cannot safely hot-swap every dynamic registry entry; some dimension/registry changes require reopening a world. The editor does not turn a resource-pack model into a newly registered item type: use an existing item and its `minecraft:item_model` component.

PNG files open an editable canvas with separate tools for pencil/brush/eraser/fill, palette, layers, resolution and undo. OGG previews show metadata and a seekable playback bar. Structure NBT previews use game block textures and support rotation and zoom.

Resource-pack quick import can add an OGG sound or PNG item texture and its associated JSON definitions. These files are edited on the administrator client; publish them to players separately or configure the server resource pack workflow. Merely editing local files does not distribute them automatically.

## API reference and third-party extensions

API Reference contains the built-in namespaces and APIs supplied by installed server mods. Search by API name or description. Chinese additionally offers Pinyin initials; Japanese offers romaji and hiragana/katakana matching, including halfwidth kana and common long-vowel/spelling variants. For example, `komando` finds `コマンド`, and the bundled domain dictionary lets `hensuu` find `変数`. Unknown kanji names require their literal spelling/ID; the index does not invent readings or apply Chinese Pinyin. The Japanese Romaji and Chinese Pinyin preferences are saved separately. The sidebar remains foldable during search; navigation and filter settings are retained.

Third-party mods can provide their own API namespace, parameter metadata, translations, documentation pages and IDE windows. Their own descriptions and examples are owned by that mod. English translations supplied by the mod are selected automatically; missing translations follow its fallback rules. Use Tools → Export API Reference to save a Markdown snapshot of the available API.
