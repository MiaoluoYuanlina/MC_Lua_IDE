# MC Lua IDE Third-Party Extension API

English · [简体中文](third-party-api.zh_cn.md)

This manual is for Fabric mod authors who want to extend MC Lua IDE. Start with the [extension overview](overview.md) to choose between manifests, the [editor API](editor-api.md) and the parts below. This manual covers four parts that **install in different places**:

| You want to… | Entry point | Who must install your mod |
| --- | --- | --- |
| Add Lua functions such as `MC.Example.welcome()` or `TestMod.playerCount()` | `McLuaIdeApi` | **Server** only |
| Add categories and guide pages to the IDE's API documentation | `McLuaIdeDocsApi` | **Server** only |
| Add names, notes and ID-type hints for registries, entries and item-component fields | `McLuaIdeRegistryApi` | **Server** only |
| Add draggable, resizable, dockable tool windows to the IDE | `McLuaIdeWindowApi` | **Administrator client** |

The first three send their metadata from the server to administrator IDE clients after a permission check, so a server-side extension needs nothing installed on clients. Only IDE windows run client-side Java code and therefore must be installed on the administrator's client.

Versions:

| Constant | Value | Meaning |
| --- | --- | --- |
| `McLuaIdeWindowApi.EXTENSION_API_VERSION` | `2` | IDE window interface. 2 = native `NativeContent` (Dear ImGui); 1 = legacy `Content`, still works — see [Legacy windows](#6-legacy-windows-content) |
| `McLuaIdeRegistryApi.EXTENSION_API_VERSION` | constant in the class | Registry annotation interface; use for compatibility checks |
| `MC.apiVersion()` in Lua | `1` | Lua runtime version, unrelated to the two above |

---

## 1. Setting up your project

```gradle
dependencies {
    modImplementation files("libs/mc_lua_ide-1.0.0.jar")

    // Only needed for IDE windows (they draw with ImGui). compileOnly: MC Lua IDE ships the
    // runtime and natives — never bundle a second copy.
    compileOnly "io.github.spair:imgui-java-binding:1.92.7.1"
}
```

`fabric.mod.json`:

```json
{
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~26.1.2",
    "mc_lua_ide": ">=1.0.0"
  }
}
```

Where to register:

| API | Register in |
| --- | --- |
| `McLuaIdeApi` / `McLuaIdeDocsApi` / `McLuaIdeRegistryApi` | common `ModInitializer#onInitialize` |
| `McLuaIdeWindowApi` | `ClientModInitializer#onInitializeClient`, before the IDE is first opened |
| `McLuaIdeEditorApi` | `ClientModInitializer#onInitializeClient`; see the [editor API](editor-api.md) |

`McLuaIdeWindowApi` exists only in the client source set. Never reference it from common initializers, server classes or Lua, or a dedicated server will crash with a missing class.

---

## 2. Registering Lua functions (`McLuaIdeApi`)

Registered functions appear in: the server Lua environment (`MC.<Namespace>.<fn>` or `<Root>.<fn>`); IDE Tab completion, summaries and hover details; the API documentation (multi-level categories, filtering, pinyin-initial search); static checks of argument count, types and resource IDs / UUIDs; and the documentation's Copy / Insert templates.

### 2.1 Full example: `MC.Example.welcome(playerUuid, message?)`

```java
public final class ExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        McLuaIdeApi.register(
            McLuaIdeApi.function("example_mod", "Example", "welcome", (context, args) -> {
                String playerUuid = args.checkjstring(1);
                String message = args.optjstring(2, "Welcome to the server!");

                // Lua runs on its own script thread. World, player and entity access MUST use onServer.
                return context.onServer(server -> {
                    var player = server.getPlayerList().getPlayer(UUID.fromString(playerUuid));
                    if (player == null) throw new IllegalArgumentException("Player is not online: " + playerUuid);
                    player.sendSystemMessage(Component.literal(message));
                    return LuaValue.valueOf(true);
                });
            })
            .required("playerUuid", PLAYER_UUID)
            .optional("message", STRING)
            .translation("en_us", McLuaIdeApi.translation("Example APIs", "Send a welcome message to an online player")
                .returns("boolean; true when the message was sent")
                .details("The UUID must belong to an online player; executed by example_mod on the server")
                .parameter("playerUuid", "UUID of an online player")
                .parameter("message", "message text; defaults to a welcome message")
                .usage("print(MC.Example.welcome(MC.Player.list()[1].uuid, \"Welcome back!\"))")
                .build())
            .translation("zh_cn", McLuaIdeApi.translation("示例功能", "向在线玩家发送欢迎消息")
                .returns("布尔值；发送成功时为 true")
                .parameter("playerUuid", "在线玩家 UUID")
                .parameter("message", "要发送的文本")
                .build())
            .build());
    }
}
```

### 2.2 Standalone top-level category (`rootFunction`)

`rootFunction` creates a category without the `MC.` prefix, e.g. `TestMod.playerCount()`. The API documentation gets its own “TestMod API” category.

```java
McLuaIdeApi.register(
    McLuaIdeApi.rootFunction("test_mod", "TestMod", "playerCount", (context, args) ->
        context.onServer(server -> LuaValue.valueOf(server.getPlayerCount())))
    .translation("en_us", McLuaIdeApi.translation("TestMod APIs", "Get the online player count")
        .returns("integer; current online player count")
        .usage("print(\"Online:\", TestMod.playerCount())")
        .build())
    .build());
```

A mod may register any number of methods under one `rootName`; no other mod can add methods to a root that belongs to `test_mod`.

### 2.3 Registrars

```java
McLuaIdeApi.function(ownerModId, namespace, method, function)       // → MC.<namespace>.<method>
McLuaIdeApi.rootFunction(ownerModId, rootName, method, function)    // → <rootName>.<method>
```

| Parameter | Meaning |
| --- | --- |
| `ownerModId` | Fabric mod ID registering the function, e.g. `example_mod` |
| `namespace` | Lua namespace under `MC`, e.g. `Example` (`function` only) |
| `rootName` | Standalone top-level category, e.g. `TestMod` (`rootFunction` only) |
| `method` | Lua function name, e.g. `welcome` |
| `function` | Implementation: receives a `FunctionContext` and LuaJ `Varargs`, returns `Varargs` |

Declare parameters in order; **all required parameters must precede optional ones**:

```java
.required("playerUuid", McLuaIdeApi.ValueType.PLAYER_UUID)
.optional("message", McLuaIdeApi.ValueType.STRING)
```

### 2.4 Parameter types (`ValueType`)

| Type | IDE check | Insert window |
| --- | --- | --- |
| `ANY` | any Lua value | — |
| `STRING` | string | — |
| `NUMBER` | integer or decimal | — |
| `INTEGER` | integer | — |
| `BOOLEAN` | boolean | — |
| `TABLE` | Lua table | — |
| `FUNCTION` | Lua function | — |
| `BLOCK_ID` | `namespace:path` string | switches to blocks |
| `ITEM_ID` | `namespace:path` string | switches to items |
| `ENTITY_ID` | `namespace:path` string | switches to entity types |
| `RESOURCE_ID` | generic `namespace:path` string | — |
| `PLAYER_UUID` | standard UUID string | switches to the player-head list |
| `ENTITY_UUID` | standard UUID string | switches to the entity list |

### 2.5 Translations

Every function needs at least one translation; provide `zh_cn` and `en_us`:

```java
.translation("en_us", McLuaIdeApi.translation("Category display name", "Function summary")
    .returns("Return value")
    .details("Extra limits, threading notes")
    .parameter("name", "Parameter description")
    .usage("Lua template ready to copy and run")
    .build())
```

Language codes are case-insensitive and `-` becomes `_`. The client prefers the current language, then the same language prefix, then `en_us`, then the first translation. Use the same category display name for all functions in a namespace/root.

### 2.6 Client-mod-only functions

For functions that only work when the target player has a client mod, add before `.build()`:

```java
.requiresClientMod()
```

They move to the top “Client mod only API” category with a prominent warning. This is **metadata only**: it does not perform networking handshakes or permission checks. Your implementation must still verify the server allows the client feature, the player is online, the channel exists and the protocol matches, and return a clear error otherwise.

### 2.7 Threading and `FunctionContext`

Lua runs on the IDE's script thread, **not** the Minecraft server thread. Read or change players, worlds, entities and containers only inside:

```java
context.onServer(server -> {
    // safe access to Minecraft objects on the server thread
    return LuaValue.TRUE;
});
```

`onServer` waits up to 10 seconds; a stopped script, interrupted thread or timeout becomes a readable Lua error.

```java
context.server();      // raw MinecraftServer — do not mutate game state from the script thread
context.scriptName();  // current script
context.ownerModId();  // mod that owns the function
context.symbol();      // full symbol, e.g. MC.Example.welcome or TestMod.playerCount
```

### 2.8 Return values and errors

```java
return LuaValue.NIL;
return LuaValue.valueOf(true);
return LuaValue.valueOf("text");
return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(1), LuaValue.valueOf(2) });
```

Throw `LuaError` for a clear script error. Other exceptions are converted to `[modId] full.API.symbol call failed: reason`.

### 2.9 Naming and conflicts

- Namespaces, function names and parameter names are Lua identifiers: start with a letter, then letters, digits, underscores.
- Built-in namespaces (`Block`, `Player`, `Event`, `UI`, `File`, …) cannot be overridden.
- A third-party namespace or root category belongs to exactly one mod; its owner may add any number of distinct methods.
- Roots cannot use `MC`, `print`, `math`, `string`, `table`, `require` or other reserved Lua/sandbox names.
- Symbols are de-duplicated case-insensitively.
- Registration affects scripts started **afterwards**; do it during mod initialization.

---

## 3. Documentation pages (`McLuaIdeDocsApi`)

Register your own category and pages in the administrator's “API documentation” window. A page consists of headings, text, callouts and Lua code blocks (with Insert / Copy buttons). Categories and pages are searchable by name, pinyin initials or mod ID, and never appear as Lua completions.

```java
McLuaIdeDocsApi.page("test_mod", "guide", "first_script")
    .translation("en_us", McLuaIdeDocsApi.translation("TestMod guide", "First script")
        .heading("Preparation")
        .text("Run the Lua example below.")
        .callout("Install test_mod first.")
        .code("print(TestMod.playerCount())")
        .build())
    .register();
```

`categoryId` defines the top-level heading (many pages may share it); each language needs 1–32 elements; `code` is only copyable text and is never executed on the client; a duplicate `ownerModId + categoryId + pageId` throws. Pages are **declarative** — a server mod cannot ship arbitrary client Java, HTML or scripts. For in-game player UIs use the runtime `MC.UI` API instead.

---

## 4. Registry IDs, entry notes and component field hints (`McLuaIdeRegistryApi`)

The IDE enumerates every registry on the server and reads entries on demand, so custom and dynamic registries and other mods' namespaces appear automatically. `McLuaIdeRegistryApi` only **annotates** them; it never creates game IDs.

```java
McLuaIdeRegistryApi.registry("example_mod", "example_mod:spell")
    .translation("en_us", "Spell ID", "All spell types provided by example_mod")
    .register();

McLuaIdeRegistryApi.entry("example_mod", "minecraft:item", "example_mod:magic_wand")
    .translation("en_us", "Magic Wand", "Item used to cast a spell")
    .register();
```

Entries may live in a vanilla registry as long as the entry itself belongs to your namespace. Names, notes, full IDs, namespaces and pinyin initials are all filterable.

### Component field hints

Declare which field of your item component's JSON takes which kind of ID. Paths are JSON-Pointer-like: `""` is the component root, `*` matches one object key or array index, and `/` and `~` inside names are escaped as `~1` and `~0`.

```java
// example_mod:spell_focus = {"spell":"example_mod:fireball"}
McLuaIdeRegistryApi.componentHint("example_mod", "example_mod:spell_focus", "/spell", VALUE, "example_mod:spell")
    .translation("en_us", "Spell ID", "Select the spell bound to this focus").register();

// example_mod:spell_levels = {"example_mod:fireball":3}   (object keys are spell IDs)
McLuaIdeRegistryApi.componentHint("example_mod", "example_mod:spell_levels", "/*", KEY, "example_mod:spell")
    .translation("en_us", "Spell ID", "Object keys are spell IDs").register();
```

Paths are relative to the **owning component's root**, so the hint still applies when the component is nested inside a bundle → container → item → `components`. If a structure accepts both a short string and a full object, hint the root `""` and the object field (e.g. `/id`) separately. The unified component tree uses hints to add optional ID fields missing from the defaults. For fields whose meaning is not obvious, always declare a hint — do not rely on guessing.

Rules: a mod may annotate only registries, entries and components in **its own namespace**; a target cannot be registered twice; register in `onInitialize`.

---

## 5. Custom IDE windows (`McLuaIdeWindowApi`, client)

Extensions may register real IDE tool windows that users can drag, resize, snap and merge into tab groups with built-in windows.

### 5.1 Quick start (recommended: `NativeContent`, version 2)

```java
public final class TestModClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        McLuaIdeWindowApi.window("test_mod", "script_tools")
            .title(Component.literal("TestMod Script Tools"))
            .size(360, 220).minimumSize(180, 120)
            .content(ToolContent::new)
            .register();
    }

    private static final class ToolContent implements McLuaIdeWindowApi.NativeContent {
        private final McLuaIdeWindowApi.Context context;
        private final imgui.type.ImString code = new imgui.type.ImString("print(\"Hello TestMod!\")", 5000);
        ToolContent(McLuaIdeWindowApi.Context context) { this.context = context; }

        @Override public void renderImGui() {
            imgui.ImGui.textWrapped("Type code, then insert it into the current file");
            imgui.ImGui.setNextItemWidth(-1);
            imgui.ImGui.inputTextMultiline("##code", code, -1, 110);
            if (imgui.ImGui.button("Insert")) context.insertText(code.get() + "\n");
            imgui.ImGui.sameLine();
            if (imgui.ImGui.button("Copy")) context.copyText(code.get());
            imgui.ImGui.textWrapped("Current: " + context.documentName());
        }
    }
}
```

`fabric.mod.json`: `{ "entrypoints": { "client": ["com.example.testmod.TestModClient"] } }`. A compilable copy lives in `examples/third_party_windows/TestModClient.java`.

### 5.2 Writing `NativeContent`

The IDE already owns the ImGui context, window and ID scope; `renderImGui()` draws only what goes **inside** the window.

- Do not call `createContext` / `newFrame` / `render`, install GLFW callbacks, or `begin` / `end` your own frame.
- Child regions, tables, trees and popups are fine; keep `beginChild` / `beginTable` / `pushID` calls paired.
- Use ImGui's own input widgets and clipboard — no `keyPressed` / `charTyped` / `mouseClicked` forwarding is needed.
- `resize(width, height)` is still called when the content area changes; `tick()` is still called every game tick.
- Localize titles with `.title(Component.translatable("window.test_mod.script_tools"))` and your own `assets/test_mod/lang/*.json`.

### 5.3 Registering, opening and closing

```java
McLuaIdeWindowApi.window(ownerModId, windowId)
    .title(Component.literal("Title"))
    .size(360, 240)          // including title bar and border, IDE logical pixels; default 360×240
    .minimumSize(160, 100)   // default 160×100; width ≥ 100, height ≥ 70
    .content(context -> new MyWindowContent(context))
    .register();

boolean opened = McLuaIdeWindowApi.open("test_mod:script_tools");   // client thread only
boolean closed = McLuaIdeWindowApi.close("test_mod:script_tools");
Minecraft.getInstance().execute(() -> McLuaIdeWindowApi.open("test_mod:script_tools")); // from other threads
```

- The full ID is `ownerModId:windowId`; window IDs use lowercase letters, digits, `_`, `-`, `.`, with `/` segments. Duplicate full IDs throw `IllegalArgumentException` and never override someone else's window.
- Default size must not be below the minimum nor exceed 4096; the actual size is limited by the screen and follows the user's saved layout.
- `open` / `close` never open the IDE by themselves: they return `false` if no IDE is active or the window is not registered in the current session, `true` on success. Calling off the client thread throws `IllegalStateException`.
- `.build()` returns a `Definition` for `McLuaIdeWindowApi.register(definition)`; `definitions()` is a read-only snapshot.

### 5.4 `Context`

| Method | Purpose |
| --- | --- |
| `minecraft()` | The client (touch game state only on the client thread) |
| `font()` | IDE font (legacy drawing) |
| `documentName()` | Current file name; empty if none |
| `source()` | Text of the main code editor |
| `selectedText()` | Selected text, empty if none |
| `insertText(text)` | Insert raw text at the caret, replacing the selection, through normal undo/highlighting; `false` if no editable file is loaded. Does not save or run |
| `copyText(text)` | Copy to the system clipboard |
| `close()` | Close your own window |

`Context` belongs to the current IDE session — do not use it after `disposed()`. Do not depend on internal classes such as `LuaIdeScreen`, `ImGuiIdeScreen` or `DockSide`.

### 5.5 Lifecycle

All callbacks run on the client thread and default to no-ops. The factory must return a **new instance each time**.

| Callback | When |
| --- | --- |
| `resize(w, h)` | Content area changed, before drawing |
| `tick()` | Every game tick while open (also while hidden behind another tab); not when closed |
| `opened()` / `closed()` | Open / close; content is created on first open and reused when reopened in the same session |
| `focusChanged(focused)` | Focus changes |
| `disposed()` | IDE session ended — release textures and buffers; closed content receives it too |

Never block on networking or heavy IO in draw/tick; return results through `Minecraft.execute`. If a callback throws, only **that** window is disabled, a red notice is shown and the full ID and stack trace go to `latest.log`.

### 5.6 What users see

- In the new IDE, windows are listed under **UI & windows → View**; click to open, click again to close.
- The IDE provides the title bar, close button, dragging, edge resizing, docking and tab merging. Windows fade and slide in and out, and when a window snaps or docks all four borders move to the new position together.
- Position, size, docking and open state are saved with the IDE layout; temporarily uninstalling a mod does not delete its layout.
- A focused floating window closes with Esc; a docked window in a locked layout closes the whole IDE on Esc.
- **Game viewport**: double-clicking the “Game viewport” window gives the game direct control; the IDE is still drawn but is non-interactive (third-party windows receive no input) until Esc. Nothing is required from your window.

### 5.7 Requirements and boundaries

- The **administrator client needs both MC Lua IDE and your mod**. Servers and ordinary players do not need a mod that only provides a window.
- Window code is trusted client Java; nothing is downloaded or executed from the server.
- The API **grants no administrator rights**. Opening the IDE, reading server files and running scripts stay under the original server-side checks.
- For server data design your own network protocol and verify permission on the **server** — whether a menu is visible on the client is not authorization.
- This is not the in-game `MC.UI` API, nor a replacement of Minecraft's `Screen`.

---

## 6. Legacy windows (`Content`)

Early versions implemented `McLuaIdeWindowApi.Content`: drawing with Minecraft's `GuiGraphicsExtractor` and receiving input through `mouseClicked` / `keyPressed` / … callbacks. **Such extensions keep working without recompiling.** The new IDE displays them through a compatibility layer and **raises a warning** so authors know to migrate.

### 6.1 How the compatibility layer works

1. `render(graphics, mouseX, mouseY)` is drawn into an off-screen texture at the old GUI scale and shown in the window's content area.
2. Mouse position, clicks (including double-click), drags, releases and wheel are converted to content-local coordinates and delivered to `mouseClicked` / `mouseDragged` / `mouseReleased` / `mouseScrolled`.
3. `keyPressed` / `keyReleased` / `charTyped` go **only to the focused** legacy window; losing focus calls `focusChanged(false)`.
4. `resize`, `tick`, `opened`, `closed`, `disposed` and `Context` behave as before.
5. Exceptions are handled as for native windows: only that window is disabled and logged.

### 6.2 The warning

Each legacy window reports in three places; the log line and chat message appear **once per window ID per game run**:

| Where | Message |
| --- | --- |
| `latest.log` (`WARN`) | `IDE window "<id>" uses the legacy Content.render(GuiGraphicsExtractor) API and runs in a compatibility layer. Update it to McLuaIdeWindowApi.NativeContent (extension API version 2).` |
| Chat (yellow, once) | `[MC Lua IDE] ⚠ ` followed by the same text as the log line (the Chinese UI shows a Chinese sentence naming the window title and ID) |
| Orange banner at the top of the window (always) | `⚠ This extension uses the legacy Content API and is shown through a compatibility layer (look and input may differ). Please update it to NativeContent.` |

### 6.3 Limitations

- It looks like old Minecraft widgets and does not follow the new IDE color theme.
- Content is rasterized to a texture every frame, which costs more than `NativeContent`.
- Input boxes are the extension's own `EditBox`es; IME and clipboard behavior depend on them.
- While the game is being controlled from the Game viewport, legacy windows receive no input (like native ones).
- The layer is a transition aid and **may be removed in a future version**. Please migrate.

### 6.4 Migrating to `NativeContent`

| Legacy | Native |
| --- | --- |
| `implements McLuaIdeWindowApi.Content` | `implements McLuaIdeWindowApi.NativeContent` |
| `render(graphics, mx, my)` + `graphics.text(...)` | `renderImGui()` + `ImGui.text(...)` |
| `EditBox` / `Button` + manual event forwarding | `ImGui.inputText(...)` / `ImGui.button(...)`, no forwarding |
| `mouseClicked` / `keyPressed` / `charTyped` | delete — ImGui handles them |
| un-focusing inputs in `focusChanged` | delete |
| manual layout in `resize` | optional; ImGui lays out automatically (`setNextItemWidth(-1)`) |
| `context.font()` | usually unnecessary |
| registration, `Context`, `open/close`, `tick`, `disposed` | **unchanged** |

```java
// Legacy (still runs, warns)
@Override public void render(GuiGraphicsExtractor g, int mx, int my) {
    g.text(context.font(), "Type code", 8, 8, 0xFFC8D9EA);
    input.extractRenderState(g, mx, my, 0);
    insert.extractRenderState(g, mx, my, 0);
}
// + mouseClicked / keyPressed / charTyped / focusChanged forwarding …

// Native
@Override public void renderImGui() {
    imgui.ImGui.textWrapped("Type code");
    imgui.ImGui.setNextItemWidth(-1);
    imgui.ImGui.inputText("##code", code);
    if (imgui.ImGui.button("Insert")) context.insertText(code.get() + "\n");
}
```

Note: older MC Lua IDE builds have no `NativeContent`. A migrated mod runs only on builds with `EXTENSION_API_VERSION = 2`; keep publishing an older jar for earlier builds rather than mixing both implementations in one jar.

---

## 7. FAQ

**My function is missing from completion.** Check that you register in `onInitialize`, provided at least one `translation`, used valid Lua identifiers and no conflicting names (conflicts throw a clear exception). Registration affects scripts started afterwards, so restart the server after changes.

**The IDE says the protocol versions are incompatible.** Server and administrator client must run the same MC Lua IDE version.

**My window shows a red error.** A callback threw; look for the window's full ID in `latest.log`. Other windows are unaffected.

**An old window shows an orange banner and a yellow chat message.** That is the normal [legacy warning](#62-the-warning); migrate with the [table above](#64-migrating-to-nativecontent).

**A dedicated server crashes with a missing `McLuaIdeWindowApi`.** The class is client-only; register windows from a `ClientModInitializer`.

**Keyboard does nothing in my legacy window.** Legacy windows receive keys only when focused: click inside the content area first.

---

## 8. Synchronization

When an administrator presses `U` or runs `/lua`, the client reads third-party API metadata from the server after the permission check. Ordinary players cannot read it. The client clears server-provided extension documentation on disconnect, so nothing lingers after switching servers. If server and client protocol versions differ the IDE refuses to open and says so.
