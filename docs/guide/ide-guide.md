# The Dear ImGui IDE: user guide

English · [简体中文](ide-guide.zh_cn.md)

The IDE front end is a standalone Dear ImGui interface. It is not an Axiom plugin and does not need Axiom. Windows, buttons, menus, trees, the colour picker and the code canvas are drawn natively; only game content (item/block/entity icons, structure previews, Minecraft text-component previews) is still rendered by the game.

## Opening it

- Join a server where you have permission and press **U**. Permission checks and script execution stay on the server.
- Unsaved files are confirmed before closing. If the renderer fails, the error is logged and a recovery draft is kept; the IDE never silently falls back to another UI, and it never changes server permissions or limits.

## Windows and layout

*File* opens the three workspaces (Lua, datapacks, resource packs); *UI & windows → View* opens every editor and tool. Drag a window title to dock, split or merge into tabs; layout and open files are saved. Editor colours, fonts, background image and cursor come from the personalization settings; the menus offer font size and UI scale. **F12** opens the debug page, per-feature shortcut settings, and the drag-and-drop HUD layout editor (right-click for UI settings).

**Menus in the title bar** (*Personalization → Window → Merge menus into the title bar*): the File / Edit / UI & windows / Run menus and the window buttons share the window's title bar row instead of sitting under it.

- **Windows** (on by default): the window keeps its native frame, shadow, resize borders, Aero Snap, dragging and double-click to maximise; only the system caption is replaced by the IDE's own strip with minimize / maximize / close buttons. The empty part of the strip drags the window.
- **macOS** (off by default): the native traffic-light buttons stay, the title bar becomes transparent and the menus start to their right. Written for NSWindow through Java's foreign-function API but **not tested on a Mac**.
- **Linux, X11** (off by default): the window manager's title bar is replaced by the IDE's strip with the same buttons, dragging, double-click to maximise and edge resizing. **Not tested on real Linux desktops.** On Wayland a client cannot move its own window, so the normal title bar is kept and the option is disabled.
- Fullscreen, switching the option off, closing the IDE, or any failure while switching restores the normal title bar. The close button of the strip closes the game window, like the system one.

Common shortcuts: Ctrl+S save, Ctrl+N new, Ctrl+I insert, Tab accept completion, Ctrl+/ comment, Ctrl+Enter run/stop, Ctrl+P quick open, Ctrl+Shift+P command palette, Ctrl+\` terminal. Debug keys (F5 …) follow the presets or custom bindings in F12.

Third-party windows implement `McLuaIdeWindowApi.NativeContent`. Legacy `Content` windows still work through a compatibility layer and show a warning; see the [extension docs](../extensions/overview.md).

## Editor features

- **Find / replace** (Ctrl+F / Ctrl+H): case, whole word and regular expression toggles. Regular expressions have a 400 ms time limit.
- **Brackets and strings:** matching highlight, unmatched bracket underline, auto-close and step-over, wrapping a selection.
- **Highlights and guides:** current line, other occurrences of the symbol under the caret, indent guides, a minimap you can click or drag.
- **Navigation:** go to line (Ctrl+G, `line:column` works), go to definition (Ctrl+B or Ctrl+click), find references (Ctrl+Shift+B), outline, quick open (Ctrl+P), symbols (Ctrl+Shift+O), find in files (Ctrl+Shift+F).
- **Rename** (F2): renames the symbol and its references **in the current file** only.
- **Snippets:** type a trigger such as `fn`, `for`, `if`, `pcall` and press Tab. Extensions can add more (see [extensions](../extensions/overview.md)).
- **Multiple carets:** Ctrl+D adds the next occurrence, Alt+click adds a caret; Esc clears them.
- **Compare:** a side-by-side diff of two files.
- The symbol analysis is lexical and scope based. It is not full type inference, and rename/references do not cross files.

## Debugging

- Click the gutter to toggle a breakpoint. **Right-click the gutter** (or the editor menu → *Breakpoint options…*) to set a **condition** (Lua expression), a **hit count** (from the Nth hit onward) or a **log message** (`{expression}` interpolation, printed to the script output without pausing). Orange dot = conditional, blue diamond = logpoint.
- While paused: click a **call-stack frame** to select it and jump there; expressions are evaluated in that frame. The **Watch** list evaluates your expressions at every pause and is saved with the world/server metadata. Hover a variable name (`a`, `a.b.c`) to see its value. Right-click → *Run to cursor* / *Add to watch*.
- Conditions, watches and hover values are expressions of at most 512 characters, evaluated on the script's Lua thread in the sandbox. Hover only evaluates variable chains, never function calls.
- Known limits: a single evaluation has no instruction budget, and LuaJ's one hook cannot interrupt an endless loop inside it (for example a condition that calls a function that never returns); restart the script in that case. Not implemented: exception breakpoints, coroutine view, split editor.
- The protocol only gained `DEBUG_EVALUATE`; old action numbers are unchanged (`protocolStabilitySmoke` freezes them).

## Terminal

Open it from *UI & windows → View → Terminal*, **Ctrl+\`**, or the command palette. **+** starts a shell on this computer (PowerShell, PowerShell 7, Command Prompt, Git Bash, WSL on Windows; `$SHELL`, bash, zsh, sh elsewhere) or a server terminal.

- **Local terminal** runs on the player's own computer. Windows uses ConPTY (Windows 10 1809 or newer) through Java's foreign-function API, so no native library is shipped. Linux and macOS use the system `script` utility to allocate a pseudo terminal; that path has not been tested on real machines here, and the size is set when the program starts and then synchronised with `stty`.
- **Emulation:** cursor addressing, scroll regions, 16/256/true colour, alternate screen (vim, less, htop), 5000 lines of scrollback (Shift+PageUp/PageDown or the wheel), wide characters, DEC line drawing, bracketed paste, application cursor keys, mouse reporting (1000/1002/1003/1006), window title.
- **Selection and clipboard:** drag or double-click to select; Ctrl+Shift+C / Ctrl+Shift+V (Ctrl+C also copies when text is selected; Shift+Insert pastes). Ctrl+wheel changes the font size; Ctrl+Shift+T opens, Ctrl+Shift+W closes a tab.
- **Keyboard:** while the terminal is focused every key (Esc, Tab, Ctrl+letter …) goes to the program, apart from F12 and Ctrl+\`.
- **Not supported (ignored):** OSC 52 clipboard access, hyperlinks, sixel images, bold/italic font faces (bold uses the bright colour).
- Tabs live in the client process: closing the IDE window does not end programs; quitting the game does, and server tabs end on disconnect.
- Extra shells can come from [extensions](../extensions/overview.md).

### Server terminal (off by default)

A server terminal is a shell **on the machine that runs the game server**, equivalent to remote command execution as the user that runs the server. Therefore:

- It is disabled until the server operator sets `enabled=true` in `config/mc_lua_ide/terminal.properties` (a commented template is written on first use). It cannot be enabled from the IDE or the protocol.
- Only server owners (operator level 4 or the singleplayer host) can use it; permission is re-checked on every request, and losing it or disconnecting ends all of that player's sessions at once.
- A session belongs to its creator. Defaults: 2 per player, 8 per server, 30 minutes idle timeout (`idleMinutes`), input ≤ 16 KB per request, 512 KB output buffer (overflow is reported as dropped output).
- Opens, closes and refusals are written to the server log with an `AUDIT:` prefix. Keystrokes are not logged.
- It uses the normal request/response protocol with client polling (about 40 ms while active, 300 ms idle), so latency is higher than the local terminal and it is not meant for high-volume output.

## Implementation notes

- Dear ImGui through `io.github.spair:imgui-java-binding:1.92.7.1` with the same-version LWJGL backend and natives for Windows, Linux and macOS. It reuses Minecraft's LWJGL and creates no extra window or GL runtime.
- ImGui has its own context and draws at the end of the Minecraft frame before the buffer swap; tool viewports use render targets and restore GUI state, projection and framebuffer bindings afterwards.
- Minecraft's keyboard and mouse callbacks are not replaced; the screen forwards input and clears key state on exit.
- Fonts are read from the machine; no fonts are distributed. On Linux install Noto Sans CJK and DejaVu Sans Mono, otherwise CJK glyphs may be missing.
- Upstream projects: [imgui-java](https://github.com/SpaiR/imgui-java), [Dear ImGui](https://github.com/ocornut/imgui), [ImGuiColorTextEdit](https://github.com/goossens/ImGuiColorTextEdit) (MIT). No Axiom UI code or assets were copied.

## Verification

`./gradlew runClient -PimGuiProbe` starts an isolated client in `build/imgui-probe` and draws every window, checking Chinese input, undo/redo, Unicode replacement, JSON write-back, resource pack saves, third-party windows, unsaved-close protection, the editor tools and a real shell session. Screenshots are written to the same directory. `./gradlew build` also runs the smoke tests (editor tools, terminal emulator, pty, server terminal policy, breakpoints, extension API and more). Not every OS, GPU and rendering-mod combination has been tested.
