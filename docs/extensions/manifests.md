# Extension manifests (data-only extensions)

English · [简体中文](manifests.zh_cn.md)

A manifest is a JSON file that adds **snippets**, **commands** and **terminal shells** to the IDE without writing Java. Use it when you are a player, a modpack author or a script author. If you need real logic (completion that reads your own data, windows, anything that runs code), use the [editor API](editor-api.md) instead.

A manifest cannot run code. Its commands can only insert text, copy text, show a message, or open a registered IDE window.

## Where manifests live

| Location | Who writes it | Can add shells |
| --- | --- | --- |
| `config/mc_lua_ide/extensions/<name>.json` | the player | yes |
| `config/mc_lua_ide/extensions/<name>/extension.json` | the player | yes |
| `assets/<modid>/mc_lua_ide/extension.json` inside any installed mod's jar | a mod or modpack author | **no** (ignored with a message) |

For a manifest inside a mod, the owner is always that mod's id and the `"id"` field is ignored. For a file under `config/`, `"id"` must not be the id of an installed mod (that would look like the mod's own contributions).

Manifests are read the first time the IDE opens in a game run. After editing one, run **Reload extension manifests** from the command palette (Ctrl+Shift+P); **Show extension status** prints how many entries loaded. Problems are listed in `latest.log` with the prefix `Extension manifest:`.

## Format

```json
{
  "schema": 1,
  "id": "hello_pack",
  "name": "Hello Pack",
  "version": "1.0.0",
  "snippets": [ ... ],
  "commands": [ ... ],
  "shells": [ ... ]
}
```

A complete working file is in [`examples/hello-pack.json`](examples/hello-pack.json); the build loads it in a test, so it stays valid.

| Field | Meaning |
| --- | --- |
| `schema` | Optional, must be `1` if present. A different number rejects the file with a clear message. |
| `id` | Required for files under `config/`. `[a-z][a-z0-9_-]{1,63}`; unique among manifests. |
| `name`, `version` | Optional, shown in status messages. |

### `snippets`

```json
{ "trigger": "giveitem", "description": "Give an item", "body": ["MC.Item.give(${1:player}, \"${2:minecraft:stone}\")", "$0"] }
```

- Typing `trigger` and pressing **Tab** expands `body`. `body` is a string, or an array of lines joined with newlines.
- Placeholders: `${1:default}`, `$1`, `$0` (final caret). Tab moves through them in numeric order, `$0` last. `\n` and `\t` follow the current line's indentation.
- `trigger`: an identifier, up to 32 characters. The built-in templates (`fn`, `lf`, `anon`, `for`, `fori`, `forp`, `if`, `ife`, `eif`, `while`, `rep`, `req`, `pcall`, `tbl`, `print`) cannot be replaced, and two extensions cannot use the same trigger; the later one is rejected.
- `body`: up to 4000 characters; placeholder numbers must be 0–99 and `${` must be closed.

### `commands`

```json
{
  "id": "wrap",
  "title": { "zh_cn": "包裹选中内容", "en_us": "Wrap selection" },
  "hint": "demo",
  "context_menu": true,
  "action": { "type": "insert", "text": "(${selection})" }
}
```

- `id`: lowercase letters, digits, `_`, `-`, `.`. The full command id is `<owner>:<id>`.
- `title`: a string, or an object with `zh_cn` and/or `en_us`. The language is chosen when the manifest loads; reload after switching language.
- `hint`: optional text shown next to the command in the palette (it does not bind a key).
- `context_menu`: also list the command in the code editor's right-click menu.
- Commands appear in the command palette (**Ctrl+Shift+P**).

Action types:

| `type` | Fields | Effect |
| --- | --- | --- |
| `insert` | `text` | Inserts at the caret (replacing the selection) with normal undo. If no editable file is open, a message is shown. |
| `copy` | `text` | Copies to the clipboard. |
| `notice` | `text` | Shows a short message in the IDE. |
| `open_window` | `window` | Opens a registered IDE window by its full id (`modid:windowid`). |

`text` may contain the variables `${selection}` (selected text), `${file}` (file name) and `${line}` (caret line, from 1). Anything else, including other `${...}`, is kept as written. `text` is limited to 8000 characters and may be an array of lines.

Any other `type` is reported and the command is skipped; it is never run.

### `shells`

```json
{ "id": "my_bash", "name": "My Bash", "command": ["C:/tools/bash.exe", "--login", "-i"] }
```

Adds an entry to the local terminal's **+** menu. Only manifests under `config/mc_lua_ide/extensions` can do this, because a shell entry starts a program on your computer. The server terminal never uses shells defined by a client.

`command` has 1–32 non-blank arguments; the first is the program.

## Limits and error handling

- File size: 256 KB. At most 200 snippets, 100 commands and 20 shells per file (extra entries are dropped with a message).
- A bad entry is skipped and reported; the rest of the file still loads. Invalid JSON skips the whole file.
- Manifests never change server files, run Lua, or talk to the network.
