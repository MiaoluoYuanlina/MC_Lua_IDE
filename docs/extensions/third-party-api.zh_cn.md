# MC Lua IDE 第三方扩展 API 手册

[English](third-party-api.md) · 简体中文

本手册面向想为 MC Lua IDE 增加功能的其他 Fabric 模组作者。请先阅读[扩展概览](overview.zh_cn.md)，在扩展清单、[编辑器 API](editor-api.zh_cn.md) 和下面的内容之间做选择。本手册涵盖的 API 分为四组，**安装位置不同**，开始前先看下表：

| 你想做什么 | 使用的入口 | 谁需要安装你的模组 |
| --- | --- | --- |
| 给 Lua 增加函数，例如 `MC.Example.welcome()` 或 `TestMod.playerCount()` | `McLuaIdeApi` | 只需**服务端** |
| 在 IDE 的“API 文档”里增加分类和说明页 | `McLuaIdeDocsApi` | 只需**服务端** |
| 给注册表、条目、物品组件字段加名称、注释和 ID 类型提示 | `McLuaIdeRegistryApi` | 只需**服务端** |
| 在 IDE 里增加可拖动、缩放、吸附的工具窗口 | `McLuaIdeWindowApi` | **管理员客户端** |

前三组的元数据由服务端在权限校验后自动发给拥有管理权限的 IDE 客户端，所以普通的服务端扩展模组不要求玩家或管理员安装任何东西。只有“独立 IDE 窗口”运行的是客户端 Java 代码，因此必须装在管理员客户端。

版本号：

| 常量 | 当前值 | 含义 |
| --- | --- | --- |
| `McLuaIdeWindowApi.EXTENSION_API_VERSION` | `2` | IDE 窗口接口版本。2 = 新版 `NativeContent`（Dear ImGui）；1 = 旧版 `Content`（仍可用，见[兼容旧版窗口](#兼容旧版窗口content)） |
| `McLuaIdeRegistryApi.EXTENSION_API_VERSION` | 见类内常量 | 注册表注释接口版本，可用于兼容性检查 |
| Lua 中的 `MC.apiVersion()` | 模组版本，如 `"1.0.12"` | MC Lua IDE 的模组版本，与上面两项无关 |

---

## 1. 开发环境设置

把 MC Lua IDE 的 JAR 加入扩展模组的依赖：

```gradle
dependencies {
    modImplementation files("libs/mc_lua_ide-1.0.0.jar")

    // 只有写“独立 IDE 窗口”时才需要：用 ImGui 绘制控件。
    // compileOnly 即可，运行库和 native 库由 MC Lua IDE 提供，不要再打包第二份。
    compileOnly "io.github.spair:imgui-java-binding:1.92.7.1"
}
```

在 `fabric.mod.json` 声明依赖：

```json
{
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~26.1.2",
    "mc_lua_ide": ">=1.0.0"
  }
}
```

注册入口放在哪里：

| API | 注册位置 |
| --- | --- |
| `McLuaIdeApi` / `McLuaIdeDocsApi` / `McLuaIdeRegistryApi` | 公共 `ModInitializer#onInitialize` |
| `McLuaIdeWindowApi` | `ClientModInitializer#onInitializeClient`，且要在第一次打开 IDE 之前完成 |
| `McLuaIdeEditorApi` | `ClientModInitializer#onInitializeClient`；见[编辑器 API](editor-api.zh_cn.md) |

`McLuaIdeWindowApi` 只存在于客户端源码集。不要在公共初始化器、服务端类或 Lua 脚本中直接引用它，否则服务端会因找不到类而崩溃。

---

## 2. 注册 Lua 函数（`McLuaIdeApi`）

注册后的函数会同时出现在：

- 服务端 Lua 环境中的 `MC.<命名空间>.<函数>` 或 `<独立大类>.<函数>`；
- IDE 的 Tab 补全、中文简介和鼠标悬浮详情；
- API 文档的多级分类、筛选与拼音首字母搜索；
- 参数个数、参数类型和资源 ID / UUID 的静态检查；
- API 文档里的“复制”“插入”模板。

### 2.1 完整示例：`MC.Example.welcome(playerUuid, message?)`

```java
package com.example.examplemod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.network.chat.Component;
import org.luaj.vm2.LuaValue;
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;

import java.util.UUID;

import static wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi.ValueType.PLAYER_UUID;
import static wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi.ValueType.STRING;

public final class ExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        McLuaIdeApi.register(
            McLuaIdeApi.function("example_mod", "Example", "welcome", (context, args) -> {
                String playerUuid = args.checkjstring(1);
                String message = args.optjstring(2, "欢迎来到服务器！");

                // Lua 运行在独立脚本线程。访问世界、玩家、实体必须用 onServer。
                return context.onServer(server -> {
                    var player = server.getPlayerList().getPlayer(UUID.fromString(playerUuid));
                    if (player == null) throw new IllegalArgumentException("玩家不在线：" + playerUuid);
                    player.sendSystemMessage(Component.literal(message));
                    return LuaValue.valueOf(true);
                });
            })
            .required("playerUuid", PLAYER_UUID)
            .optional("message", STRING)
            .translation("zh_cn", McLuaIdeApi.translation("示例功能", "向在线玩家发送欢迎消息")
                .returns("布尔值；发送成功时为 true")
                .details("只能向当前在线的玩家 UUID 发送；由 example_mod 在服务端执行")
                .parameter("playerUuid", "在线玩家 UUID")
                .parameter("message", "要发送的文本，默认“欢迎来到服务器！”")
                .usage("""
                    local players = MC.Player.list()
                    if #players > 0 then
                        print("发送结果：", MC.Example.welcome(players[1].uuid, "你好，欢迎回来！"))
                    end
                    """)
                .build())
            .translation("en_us", McLuaIdeApi.translation("Example APIs", "Send a welcome message to an online player")
                .returns("boolean; true when the message was sent")
                .details("The UUID must belong to an online player; executed by example_mod on the server")
                .parameter("playerUuid", "UUID of an online player")
                .parameter("message", "message text; defaults to a welcome message")
                .usage("print(MC.Example.welcome(MC.Player.list()[1].uuid, \"Welcome back!\"))")
                .build())
            .build()
        );
    }
}
```

### 2.2 创建独立顶级大类（`rootFunction`）

`rootFunction` 生成不带 `MC.` 前缀的大类，例如 `TestMod.playerCount()`。API 文档会自动创建“TestMod API”分类。

```java
McLuaIdeApi.register(
    McLuaIdeApi.rootFunction("test_mod", "TestMod", "playerCount", (context, args) ->
        context.onServer(server -> LuaValue.valueOf(server.getPlayerCount())))
    .translation("zh_cn", McLuaIdeApi.translation("TestMod API", "获取服务器在线玩家数量")
        .returns("整数；当前在线玩家数量")
        .usage("print(\"当前在线：\", TestMod.playerCount())")
        .build())
    .translation("en_us", McLuaIdeApi.translation("TestMod APIs", "Get the online player count")
        .returns("integer; current online player count")
        .usage("print(\"Online:\", TestMod.playerCount())")
        .build())
    .build());
```

同一个模组可以用同一个 `rootName` 注册任意数量的方法；其他模组不能往已经属于 `test_mod` 的 `TestMod` 大类里加方法。

### 2.3 注册器参数

```java
McLuaIdeApi.function(ownerModId, namespace, method, function)       // → MC.<namespace>.<method>
McLuaIdeApi.rootFunction(ownerModId, rootName, method, function)    // → <rootName>.<method>
```

| 参数 | 含义 |
| --- | --- |
| `ownerModId` | 注册函数的 Fabric 模组 ID，例如 `example_mod` |
| `namespace` | `MC` 下的 Lua 命名空间，例如 `Example`（仅 `function`） |
| `rootName` | 独立顶级大类名，例如 `TestMod`（仅 `rootFunction`） |
| `method` | Lua 函数名，例如 `welcome` |
| `function` | 实现；接收 `FunctionContext` 与 LuaJ `Varargs`，返回 `Varargs` |

参数按顺序声明，**必填参数必须在可选参数之前**：

```java
.required("playerUuid", McLuaIdeApi.ValueType.PLAYER_UUID)
.optional("message", McLuaIdeApi.ValueType.STRING)
```

声明会生成函数签名，并用于 IDE 的实时检查。

### 2.4 参数类型（`ValueType`）

| 类型 | IDE 检查 | 插入窗口 |
| --- | --- | --- |
| `ANY` | 任意 Lua 值 | 无 |
| `STRING` | 字符串 | 无 |
| `NUMBER` | 整数或小数 | 无 |
| `INTEGER` | 整数 | 无 |
| `BOOLEAN` | 布尔值 | 无 |
| `TABLE` | Lua 表 | 无 |
| `FUNCTION` | Lua 函数 | 无 |
| `BLOCK_ID` | `namespace:path` 字符串 | 自动切换到方块 |
| `ITEM_ID` | `namespace:path` 字符串 | 自动切换到物品 |
| `ENTITY_ID` | `namespace:path` 字符串 | 自动切换到实体类型 |
| `RESOURCE_ID` | 通用 `namespace:path` 字符串 | 无 |
| `PLAYER_UUID` | 标准 UUID 字符串 | 自动切换到玩家头像列表 |
| `ENTITY_UUID` | 标准 UUID 字符串 | 自动切换到实体列表 |

### 2.5 多语言文档

每个函数至少需要一种翻译，建议同时提供 `zh_cn` 与 `en_us`：

```java
.translation("zh_cn", McLuaIdeApi.translation("分类显示名", "函数简介")
    .returns("返回值说明")
    .details("额外限制、线程行为或注意事项")
    .parameter("参数名", "参数说明")
    .usage("可直接复制运行的 Lua 模板")
    .build())
```

语言代码不区分大小写，`-` 会转换为 `_`。客户端优先使用系统语言，其次匹配相同语言前缀，再回退到 `en_us` 或第一项翻译。同一命名空间或大类内的各函数应使用相同的“分类显示名”。

### 2.6 客户端 Mod 专用函数

只有目标玩家装了客户端 Mod 才有效的函数，在 `.build()` 前加：

```java
.requiresClientMod()
```

它会被放入 API 文档顶部独立的“客户端 Mod 专用 API”大类，并在悬浮详情中显示醒目的安装限制。未声明的 `MC.*` 扩展进入“服务端 / 原版 API”，未声明的 `rootFunction` 进入自己的独立大类。

这个声明**只做元数据分类和警告**，不会替你完成网络握手或权限校验。实现里仍要检查服务器是否允许该客户端能力、目标玩家是否在线、客户端通道是否存在、协议是否兼容，并在不满足时返回明确错误。

### 2.7 线程与 `FunctionContext`

Lua 主体运行在 MC Lua IDE 的脚本线程，**不是** Minecraft 服务端主线程。读写玩家、世界、实体、容器等游戏状态必须这样做：

```java
context.onServer(server -> {
    // 在服务端主线程安全访问 Minecraft 对象
    return LuaValue.TRUE;
});
```

`onServer` 最多等待 10 秒；脚本被停止、线程被中断或超时都会转换成可读的 Lua 错误。

```java
context.server();      // 原始 MinecraftServer；不要在脚本线程直接改游戏状态
context.scriptName();  // 当前脚本名
context.ownerModId();  // 当前扩展函数所属模组 ID
context.symbol();      // 完整符号，如 MC.Example.welcome 或 TestMod.playerCount
```

### 2.8 返回值与错误

函数返回 LuaJ `Varargs`：

```java
return LuaValue.NIL;
return LuaValue.valueOf(true);
return LuaValue.valueOf("text");
return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(1), LuaValue.valueOf(2) });
```

抛出 `LuaError` 可给出明确的脚本错误；其他异常会被转换成：

```text
[模组ID] 完整API符号 调用失败：具体原因
```

### 2.9 命名与冲突规则

- 命名空间、函数名、参数名必须是 Lua 标识符：字母开头，只含字母、数字、下划线。
- 不能覆盖内置命名空间，例如 `Block`、`Player`、`Event`、`UI`、`File`。
- 一个第三方命名空间、一个独立大类只能属于一个模组；同一拥有者可在其中注册任意数量的不重名方法。
- 独立大类不能使用 `MC`、`print`、`math`、`string`、`table`、`require` 等 Lua / 沙箱保留名。
- 函数符号按不区分大小写判重。
- 注册只影响**之后**启动的 Lua 脚本，请在模组初始化阶段完成。

---

## 3. 自定义 API 文档页面（`McLuaIdeDocsApi`）

除了函数条目，还可以向管理员的“API 文档”窗口注册自己的分类标题和页面。页面由标题、正文、提示框和 Lua 代码块组成；代码块带“插入”“复制”按钮。分类和页面可被中文、拼音首字母或 Mod ID 搜索，也不会被误当成 Lua 函数补全。

```java
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;

McLuaIdeDocsApi.page("test_mod", "guide", "first_script")
    .translation("zh_cn", McLuaIdeDocsApi.translation("TestMod 使用指南", "第一个脚本")
        .heading("准备")
        .text("这个页面由 test_mod 在服务端注册。管理员客户端会自动收到文档。")
        .callout("先安装 test_mod，再运行以下 Lua 示例。")
        .code("print(TestMod.playerCount())")
        .build())
    .translation("en_us", McLuaIdeDocsApi.translation("TestMod guide", "First script")
        .text("Run the Lua example below.")
        .code("print(TestMod.playerCount())")
        .build())
    .register();
```

- `categoryId`（上例 `guide`）决定一级标题；同一模组可在其下注册多个不同 `pageId` 的页面，也可用另一个 `categoryId` 建第二个一级标题。
- `categoryTitle` 与 `pageTitle` 分别是用户看到的分类名与页面名。
- 每种语言至少 1 个、最多 32 个页面元素。`code` 只是可复制 / 插入的文本，不会在客户端执行。
- `ownerModId + categoryId + pageId` 重复会报错。
- 页面是**声明式**的：服务端模组不能下发任意 Java、HTML 或脚本到客户端。需要给玩家打开游戏内交互界面，请使用运行时的 `MC.UI`，那是另一种能力。

---

## 4. 注册表 ID、条目注释与组件字段提示（`McLuaIdeRegistryApi`）

IDE 不维护固定的“常见 ID 列表”：它先枚举当前服务端的全部注册表类型，再按需分页读取所选注册表的全部条目，因此自定义注册表、动态注册表和其他模组的命名空间都会自动出现。`McLuaIdeRegistryApi` 用来给这些内容**加说明**，它不会凭空创建游戏 ID。

```java
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;
import static wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi.Target.KEY;
import static wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi.Target.VALUE;
```

给自己的注册表类型加说明：

```java
McLuaIdeRegistryApi.registry("example_mod", "example_mod:spell")
    .translation("zh_cn", "法术 ID", "example_mod 提供的全部法术类型")
    .translation("en_us", "Spell ID", "All spell types provided by example_mod")
    .register();
```

给具体条目加名称和用途注释（条目可在原版注册表里，只要条目自身属于你的命名空间）：

```java
McLuaIdeRegistryApi.entry("example_mod", "minecraft:item", "example_mod:magic_wand")
    .translation("zh_cn", "魔法杖", "施放法术时使用的物品")
    .translation("en_us", "Magic Wand", "Item used to cast a spell")
    .register();
```

名称、说明、完整 ID、命名空间与拼音首字母都可用于筛选。没有显式注释的物品、方块、实体、附魔、状态效果仍会尽量使用游戏本地化名；其余未知类型显示完整资源 ID。

### 组件字段提示

如果模组提供自己的物品堆组件，可以声明组件 JSON 中**哪个字段**应填**哪类 ID**。路径类似 JSON Pointer：空字符串表示组件值根节点，`*` 匹配一层对象键或数组下标；字段名里的 `/`、`~` 分别转义为 `~1`、`~0`。

```java
// example_mod:spell_focus = {"spell":"example_mod:fireball"}
McLuaIdeRegistryApi.componentHint("example_mod", "example_mod:spell_focus", "/spell", VALUE, "example_mod:spell")
    .translation("zh_cn", "法术 ID", "选择法杖绑定的法术")
    .translation("en_us", "Spell ID", "Select the spell bound to this focus")
    .register();

// example_mod:spell_levels = {"example_mod:fireball":3}   对象的键是法术 ID
McLuaIdeRegistryApi.componentHint("example_mod", "example_mod:spell_levels", "/*", KEY, "example_mod:spell")
    .translation("zh_cn", "法术 ID", "对象键是法术 ID")
    .translation("en_us", "Spell ID", "Object keys are spell IDs")
    .register();
```

- 路径始终相对于**所属组件的根节点**：即使该组件出现在收纳袋 → 容器 → 物品 → `components` 内，`/spell` 也会自动生效。
- 同一数据结构既支持简写字符串又支持完整对象时，分别给根路径 `""` 和对象字段路径（如 `/id`）注册提示。
- 组件编辑器打开该字段时自动切换到声明的注册表，支持上下键、Tab / Enter、鼠标选择。无法识别的字段可点“ID类型”手动选择。
- 统一组件树会用这些提示补出默认值里没有的可选 ID 字段；已有的对象 / 列表结构不会被字符串提示覆盖。
- 语义不明确的自定义字段请用 `componentHint`，不要依赖 IDE 猜测。

规则：普通模组只能注释**自己命名空间**的注册表、条目或组件；同一个目标不能重复注册；请在 `onInitialize` 中完成。搜索框输入 `#` 开头会列出注册表标签，是否允许标签由字段的 Codec 决定。

---

## 5. 自定义独立 IDE 窗口（`McLuaIdeWindowApi`，客户端）

扩展模组可以注册真正的独立 IDE 工具窗口，用户可以拖动、缩放、吸附、与内置窗口合并为标签页。入口：

```java
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
```

### 5.1 快速开始（推荐：`NativeContent`，版本 2）

```java
public final class TestModClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        McLuaIdeWindowApi.window("test_mod", "script_tools")
            .title(Component.literal("TestMod 脚本工具"))
            .size(360, 220).minimumSize(180, 120)
            .content(ToolContent::new)
            .register();
    }

    private static final class ToolContent implements McLuaIdeWindowApi.NativeContent {
        private final McLuaIdeWindowApi.Context context;
        private final imgui.type.ImString code = new imgui.type.ImString("print(\"Hello TestMod!\")", 5000);
        ToolContent(McLuaIdeWindowApi.Context context) { this.context = context; }

        @Override public void renderImGui() {
            imgui.ImGui.textWrapped("输入代码后插入当前编辑文件");
            imgui.ImGui.setNextItemWidth(-1);
            imgui.ImGui.inputTextMultiline("##code", code, -1, 110);
            if (imgui.ImGui.button("插入代码")) context.insertText(code.get() + "\n");
            imgui.ImGui.sameLine();
            if (imgui.ImGui.button("复制")) context.copyText(code.get());
            imgui.ImGui.textWrapped("当前：" + context.documentName());
        }
    }
}
```

`fabric.mod.json` 添加客户端入口：

```json
{ "entrypoints": { "client": ["com.example.testmod.TestModClient"] } }
```

完整可编译示例见仓库 `examples/third_party_windows/TestModClient.java`。

### 5.2 `NativeContent` 编写要点

IDE 已经建立好 ImGui 上下文、窗口和唯一 ID 作用域，`renderImGui()` 只需要画窗口**里面**的内容：

- 不要调用 `createContext` / `newFrame` / `render`，不要安装 GLFW 回调，不要为自己的外框调用 `begin` / `end`。
- 可以使用子区域（`beginChild`）、表格、树、弹出菜单；`beginChild` / `beginTable` / `pushID` 等必须成对。
- 键盘、鼠标、剪贴板直接使用 ImGui 的输入控件，**不需要**再实现 `keyPressed` / `charTyped` / `mouseClicked` 转发。
- `resize(width, height)` 仍会在内容区尺寸变化时被调用；`tick()` 仍按游戏 tick 调用。
- 标题可以本地化：`.title(Component.translatable("window.test_mod.script_tools"))`，翻译放在你自己的 `assets/test_mod/lang/*.json`。窗口标题不是 Lua 函数，不会混入补全或函数列表。

### 5.3 注册与主动开关

```java
McLuaIdeWindowApi.window(ownerModId, windowId)
    .title(Component.literal("窗口标题"))
    .size(360, 240)           // 含标题栏和边框，IDE 逻辑像素；默认 360×240
    .minimumSize(160, 100)    // 默认 160×100；宽 ≥ 100、高 ≥ 70
    .content(context -> new MyWindowContent(context))
    .register();

// 已打开 IDE 时，在客户端主线程调用：
boolean opened = McLuaIdeWindowApi.open("test_mod:script_tools");
boolean closed = McLuaIdeWindowApi.close("test_mod:script_tools");

// 从工作线程请求时先调度回客户端线程：
Minecraft.getInstance().execute(() -> McLuaIdeWindowApi.open("test_mod:script_tools"));
```

- 完整 ID 为 `ownerModId:windowId`。窗口 ID 只用小写字母、数字、`_`、`-`、`.`，可用 `/` 分段。同一模组可注册多个 ID，不同模组可使用相同 `windowId`；完整 ID 重复会抛 `IllegalArgumentException`，不会覆盖别人的窗口。
- 默认尺寸不得小于最小尺寸，且不超过 4096。实际尺寸受屏幕可用空间限制，并服从用户保存的布局。
- `open` / `close` **不会自己打开 IDE**。没有活动 IDE 或窗口未注册到当前会话时返回 `false`；成功返回 `true`。必须在客户端主线程调用，否则抛 `IllegalStateException`。
- 可用 `.build()` 得到 `Definition` 再交给 `McLuaIdeWindowApi.register(definition)`；`definitions()` 返回当前注册定义的只读快照。
- 不要在游戏循环中反复注册。

### 5.4 `Context`：与代码编辑器交互

| 方法 | 作用 |
| --- | --- |
| `minecraft()` | 当前客户端；访问游戏状态仍要在客户端线程 |
| `font()` | IDE 普通界面字体（旧版绘制用） |
| `documentName()` | 当前文件名；没有打开文件时为空字符串 |
| `source()` | 当前主代码框文本 |
| `selectedText()` | 选中文本；没有选中时为空 |
| `insertText(text)` | 在主代码框光标处插入原始文本并替换选区，参与撤销与高亮；没有已加载的可编辑文件时返回 `false`。不自动保存或运行 |
| `copyText(text)` | 复制到系统剪贴板 |
| `close()` | 关闭自己的窗口 |

`Context` 与控件属于当前 IDE 会话，`disposed()` 之后不要再使用。不要依赖 `LuaIdeScreen`、`ImGuiIdeScreen`、`DockSide` 等内部实现类。

### 5.5 生命周期回调

所有回调都在客户端主线程调用，都有默认空实现。工厂 `content(factory)` 应**每次返回新实例**，不能把同一个可变对象共享给不同窗口或 IDE 会话。

| 回调 | 时机 |
| --- | --- |
| `resize(width, height)` | 内容区尺寸变化时、绘制前；按此尺寸布局，不要再减标题栏高度 |
| `tick()` | 窗口开启时每游戏 tick 一次（含被同侧另一个标签遮住的窗口）；关闭时不调用 |
| `opened()` / `closed()` | 开启 / 关闭通知。首次打开才创建内容；同一会话中关了再开会复用内容 |
| `focusChanged(focused)` | 焦点变化（鼠标 / 标签 / 主动打开） |
| `disposed()` | IDE 会话结束，释放动态纹理、缓冲等资源；已关闭的内容也会收到。不要在 `closed()` 里销毁下次打开还要用的资源 |

不要在绘制或 tick 回调中同步等待网络、读大文件或做耗时任务；后台结果请通过 `Minecraft.execute` 回到客户端线程。

内容抛出普通异常或链接错误时，IDE 只停用**这一个**窗口的回调，显示红色错误提示，并在 `latest.log` 记录完整窗口 ID 与错误；其他窗口不受影响。

### 5.6 用户看到的行为

- 新版 IDE 中，窗口列在 **UI与窗口 → 视图** 里，显示标题；点击打开，再点关闭。
- IDE 负责统一的标题栏、关闭按钮、拖动、边缘缩放、停靠与标签合并。窗口出现 / 消失有淡入淡出与滑动动画，停靠 / 吸附时四条边同时过渡到新位置。
- 位置、尺寸、吸附状态与开关随 IDE 布局自动保存；暂时卸载某个模组不会删除它的布局。
- 获得焦点的悬浮窗口按 Esc 关闭；布局固定的吸附窗口按 Esc 关闭整个 IDE。
- **游戏视口**：用户在“游戏视口”窗口里双击可以直接操控游戏，此时 IDE 保持绘制但不再接收鼠标键盘，第三方窗口也不会收到输入；按 Esc 回到 IDE。你不需要为此做任何处理。

### 5.7 安装条件与能力边界

- **管理员客户端必须同时安装 MC Lua IDE 和提供窗口的扩展模组**。服务端、普通玩家都不必安装仅提供界面的扩展模组。
- 独立窗口运行的是可信的客户端 Java 代码，不通过服务器下发或执行任意 Java / HTML。
- 本接口只扩展已经打开的 IDE，**不授予管理员权限**：打开 IDE、读取服务端文件、执行脚本仍由原有的服务端权限校验控制。
- 窗口需要服务端数据时，由你的模组自己设计网络协议，并在**服务端**验证权限；不能把“客户端菜单是否可见”当作权限校验。
- 这不是玩家游戏内的 `MC.UI`，也不是替换 Minecraft 的 `Screen`。

---

## 6. 兼容旧版窗口（`Content`）

早期版本的扩展窗口实现的是 `McLuaIdeWindowApi.Content`：用 Minecraft 的 `GuiGraphicsExtractor` 绘制，输入通过 `mouseClicked` / `keyPressed` 等回调转发。**这些扩展不需要重新编译或修改就能继续使用**，新版 IDE 会通过兼容层显示它们，但会**发出警告**，提醒作者迁移。

### 6.1 兼容层如何工作

1. IDE 按旧版的 GUI 缩放把 `render(graphics, mouseX, mouseY)` 画到一张离屏纹理里，再作为图片显示在窗口内容区。
2. 鼠标位置、点击（含双击）、拖动、释放、滚轮被换算成内容区局部坐标，回调旧接口的 `mouseClicked` / `mouseDragged` / `mouseReleased` / `mouseScrolled`。
3. 键盘事件 `keyPressed` / `keyReleased` / `charTyped` 只转发给**当前获得焦点**的旧版窗口；窗口失焦时会调用 `focusChanged(false)`。
4. `resize`、`tick`、`opened`、`closed`、`disposed` 与 `Context`（`insertText`、`copyText` 等）与以前完全一致。
5. 抛出异常的处理与新版相同：只停用该窗口并写日志。

### 6.2 警告提示

每个旧版窗口会在三个地方提醒，且**每个窗口 ID 每次游戏运行只提示一次日志和聊天**，不会刷屏：

| 位置 | 内容 |
| --- | --- |
| `latest.log`（`WARN`） | `IDE window "<id>" uses the legacy Content.render(GuiGraphicsExtractor) API and runs in a compatibility layer. Update it to McLuaIdeWindowApi.NativeContent (extension API version 2).` |
| 聊天栏（黄色，一次） | `[MC Lua IDE] ⚠ IDE 窗口“<标题>”(<id>)仍使用旧版 Content 接口，已启用兼容显示；请扩展作者更新为 NativeContent。` |
| 窗口顶部橙色横幅（每次显示） | `⚠ 此扩展仍使用旧版 Content 接口，已通过兼容层显示（外观与输入可能与新版控件不同）。请更新为 NativeContent。` |

### 6.3 兼容层的限制

- 外观是旧版 Minecraft 控件，不会跟随新版 IDE 的配色主题；缩放窗口时画面会按旧 GUI 缩放重新布局。
- 内容是以纹理方式绘制的，复杂界面每帧都会重新光栅化，开销比 `NativeContent` 大。
- 输入框等控件是旧版自己的 `EditBox`，输入法、剪贴板等行为取决于它自己的实现。
- 在“游戏视口”直接操控游戏时，旧版窗口与新版窗口一样不接收输入。
- 兼容层只是过渡方案，**后续版本可能移除**。请尽快迁移。

### 6.4 迁移到 `NativeContent`

| 旧版 | 新版 |
| --- | --- |
| `implements McLuaIdeWindowApi.Content` | `implements McLuaIdeWindowApi.NativeContent` |
| `render(graphics, mouseX, mouseY)` + `graphics.text(...)` | `renderImGui()` + `ImGui.text(...)` |
| `EditBox` / `Button` + 手动转发鼠标键盘 | `ImGui.inputText(...)` / `ImGui.button(...)`，无需转发 |
| `mouseClicked` / `keyPressed` / `charTyped` | 删除，ImGui 自己处理 |
| `focusChanged` 里取消输入框焦点 | 删除 |
| `resize` 里手动布局控件 | 可保留，也可直接用 ImGui 自动布局（`setNextItemWidth(-1)` 等） |
| `context.font()` | 通常不再需要 |
| 注册、`Context`、`open/close`、`tick`、`disposed` | **不变** |

迁移前后对比（同一个工具窗口）：

```java
// ── 旧版（仍可运行，但会警告）──────────────────────────
private static final class ToolContent implements McLuaIdeWindowApi.Content {
    private final McLuaIdeWindowApi.Context context;
    private final EditBox input;
    private final Button insert;

    ToolContent(McLuaIdeWindowApi.Context context) {
        this.context = context;
        input = new EditBox(context.font(), 8, 28, 200, 20, Component.literal("代码"));
        input.setValue("print(\"Hello TestMod!\")");
        insert = Button.builder(Component.literal("插入代码"),
            b -> context.insertText(input.getValue() + "\n")).bounds(8, 56, 100, 20).build();
    }
    @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
        g.text(context.font(), "输入代码后插入当前编辑文件", 8, 8, 0xFFC8D9EA);
        input.extractRenderState(g, mx, my, 0);
        insert.extractRenderState(g, mx, my, 0);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        boolean hit = input.mouseClicked(e, twice); input.setFocused(hit);
        return hit || insert.mouseClicked(e, twice);
    }
    @Override public boolean keyPressed(KeyEvent e) { return input.isFocused() && input.keyPressed(e); }
    @Override public boolean charTyped(CharacterEvent e) { return input.isFocused() && input.charTyped(e); }
    @Override public void focusChanged(boolean f) { if (!f) input.setFocused(false); }
}

// ── 新版 ─────────────────────────────────────────────
private static final class ToolContent implements McLuaIdeWindowApi.NativeContent {
    private final McLuaIdeWindowApi.Context context;
    private final imgui.type.ImString code = new imgui.type.ImString("print(\"Hello TestMod!\")", 5000);
    ToolContent(McLuaIdeWindowApi.Context context) { this.context = context; }
    @Override public void renderImGui() {
        imgui.ImGui.textWrapped("输入代码后插入当前编辑文件");
        imgui.ImGui.setNextItemWidth(-1);
        imgui.ImGui.inputText("##code", code);
        if (imgui.ImGui.button("插入代码")) context.insertText(code.get() + "\n");
    }
}
```

注意：更早的 MC Lua IDE 构建里没有 `NativeContent` 接口。迁移后的模组只能运行在带有 `NativeContent` 的版本上（`EXTENSION_API_VERSION = 2`）；如果你还需要支持更早的构建，请为它们继续发布旧版本的模组 JAR，不要在同一个 JAR 里混用两种实现。

---

## 7. 常见问题

**我的函数在补全里看不到。** 检查是否在 `onInitialize` 中注册、是否至少给了一种 `translation`、函数和命名空间名是否是合法 Lua 标识符、是否与内置命名空间或别的模组冲突（注册时会抛出明确的异常）。注册只影响之后启动的脚本，修改后需要重启服务端。

**IDE 提示协议版本不兼容。** 服务端和管理员客户端的 MC Lua IDE 版本不一致时 IDE 会拒绝打开；请两端使用同一版本。

**窗口出现了红色错误提示。** 你的内容回调抛出了异常或链接错误，查看 `latest.log` 中带窗口完整 ID 的记录。其他窗口不受影响。

**旧版窗口显示了橙色横幅，聊天里还出现了黄色提示。** 这是兼容层的正常警告，见[第 6 章](#6-兼容旧版窗口content)。按[迁移表](#64-迁移到-nativecontent)改成 `NativeContent` 即可消除。

**服务端启动时报找不到 `McLuaIdeWindowApi`。** 这个类只存在于客户端。请把窗口注册代码放在 `ClientModInitializer`，不要从公共初始化器或服务端类引用。

**旧版窗口里键盘没反应。** 旧版窗口只在获得焦点时才收键盘事件：先点击窗口内容区使其获得焦点。

---

## 8. 管理员打开 IDE 时的同步行为

管理员按 `U` 或执行 `/lua` 打开 IDE 时，客户端在权限校验后从服务端读取第三方 API 元数据。普通玩家无权读取。连接断开时客户端清理服务器提供的扩展文档，避免切换服务器后残留。服务器与管理员客户端协议版本不一致时 IDE 拒绝打开并提示。
