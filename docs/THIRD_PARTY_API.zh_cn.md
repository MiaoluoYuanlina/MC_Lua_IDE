# MC Lua IDE 第三方模组扩展 API

其他 Fabric 服务端模组可以通过公开 Java API 向 Lua 环境注册自己的函数。既可以继续注册到 `MC.<命名空间>.*`，也可以创建属于模组自己的顶级 API 大类，例如 `TestMod.*`。注册后的函数会同时出现在：

- 服务端 Lua 运行环境中的 `MC.<命名空间>.<函数>` 或 `<独立大类>.<函数>`；
- IDE 的 Tab 补全及中文简介；
- 鼠标悬浮详情；
- API 文档的多级分类、筛选及拼音首字母搜索；
- 参数数量、参数类型和资源 ID/UUID 静态检查；
- API 文档的“复制”和“插入”模板。

第三方文档由服务端自动发送给拥有管理权限的 IDE 客户端。因此，普通的服务端扩展模组可以只安装在服务端，管理员客户端不需要额外安装该扩展模组。只有确实会向目标玩家客户端发送专用数据的函数，才应声明为“客户端 Mod 专用 API”。

此外，客户端扩展可以使用 `McLuaIdeWindowApi` 创建可拖动、缩放和吸附的独立 IDE 窗口。此能力需要管理员客户端安装扩展 Mod，与自动同步的文档页面不同，详见下文“自定义独立 IDE 窗口（客户端 API）”。

## 依赖设置

开发时把 MC Lua IDE 的 JAR 加入模组工程依赖：

```gradle
dependencies {
    modImplementation files("libs/mc_lua_ide-1.0.0.jar")
}
```

在扩展模组的 `fabric.mod.json` 中声明依赖：

```json
{
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~26.1.2",
    "mc_lua_ide": ">=1.0.0"
  }
}
```

公共入口类为：

```java
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeApi;
```

## 完整注册示例

在扩展模组的公共 `ModInitializer#onInitialize` 中注册。下面的函数会成为 `MC.Example.welcome(playerUuid, message?)`：

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

                // Lua 在独立脚本线程执行。访问世界、玩家或实体必须使用 onServer。
                return context.onServer(server -> {
                    var player = server.getPlayerList().getPlayer(UUID.fromString(playerUuid));
                    if (player == null) {
                        throw new IllegalArgumentException("玩家不在线：" + playerUuid);
                    }
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
                        local sent = MC.Example.welcome(players[1].uuid, "你好，欢迎回来！")
                        print("发送结果：", sent)
                    end
                    """)
                .build())
            .translation("en_us", McLuaIdeApi.translation("Example APIs", "Send a welcome message to an online player")
                .returns("boolean; true when the message was sent")
                .details("The UUID must belong to an online player; executed by example_mod on the server")
                .parameter("playerUuid", "UUID of an online player")
                .parameter("message", "message text; defaults to a welcome message")
                .usage("""
                    local players = MC.Player.list()
                    if #players > 0 then
                        print(MC.Example.welcome(players[1].uuid, "Welcome back!"))
                    end
                    """)
                .build())
            .build()
        );
    }
}
```

Lua 脚本随后可以直接调用：

```lua
MC.Example.welcome("玩家UUID", "欢迎回来！")
```

## 创建独立顶级 API 大类

使用 `rootFunction` 可以创建不带 `MC.` 前缀的独立大类。下面会注册 `TestMod.playerCount()`，并把方法签名、作用、返回值和可复制模板一起写入 IDE 文档：

```java
McLuaIdeApi.register(
    McLuaIdeApi.rootFunction("test_mod", "TestMod", "playerCount", (context, args) ->
        context.onServer(server -> LuaValue.valueOf(server.getPlayerCount()))
    )
    .translation("zh_cn", McLuaIdeApi.translation("TestMod API", "获取服务器在线玩家数量")
        .returns("整数；当前在线玩家数量")
        .details("由 test_mod 在服务端主线程读取，不需要玩家安装客户端模组")
        .usage("print(\"当前在线：\", TestMod.playerCount())")
        .build())
    .translation("en_us", McLuaIdeApi.translation("TestMod APIs", "Get the online player count")
        .returns("integer; current online player count")
        .details("Read on the server thread by test_mod")
        .usage("print(\"Online:\", TestMod.playerCount())")
        .build())
    .build()
);
```

Lua 中直接调用：

```lua
local count = TestMod.playerCount()
print("当前在线玩家：", count)
```

同一个模组可以使用相同的 `rootName` 多次注册方法，例如 `TestMod.giveReward(...)`、`TestMod.openMenu(...)`。API 文档会自动创建独立的“TestMod API”大类，并把这些方法放在该大类下。其他模组不能向已经属于 `test_mod` 的 `TestMod` 大类添加方法。

## 自定义 API 文档标题与页面 UI

除了函数条目，其他 Mod 还可以向管理员的“API 文档”窗口注册独立的分类标题和页面。页面 UI 由标题、正文、提示框和 Lua 代码块组成；代码块会显示“插入”和“复制”按钮。分类与页面可被中文、拼音首字母或 Mod ID 搜索。它不会把文档页面误当成 Lua 函数补全。

入口为 `wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi`。在公共 `ModInitializer#onInitialize` 中注册：

```java
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

`categoryId`（这里是 `guide`）决定页面归属的一级标题；同一 Mod 可在这个标题下注册多个不同 `pageId` 的页面，也可使用另一个 `categoryId` 创建第二个一级标题。`categoryTitle` 和 `pageTitle` 分别控制用户看到的分类名与页面名。每种语言至少需要一个页面元素，最多 32 个；`code` 是可复制/插入的 Lua 文本，不会作为客户端代码执行。重复的 `ownerModId + categoryId + pageId` 会报错。

页面是声明式 UI，不允许服务端模组发送任意客户端 Java、HTML 或脚本代码。这样不安装扩展 Mod 的管理员客户端仍可显示它。若需要给玩家打开 Minecraft 游戏内的交互界面，应使用运行时 `MC.UI` API；这与 API 文档页面是两种不同能力。

## 自定义独立 IDE 窗口（客户端 API）

从本版本起，其他 Mod 可以注册真正的独立 IDE 工具窗口，不再局限于 API 文档中的页面。入口：

```java
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
```

`McLuaIdeWindowApi.EXTENSION_API_VERSION = 1`。它只存在于客户端源码集；请在扩展 Mod 的 **ClientModInitializer#onInitializeClient** 中注册，在第一次打开 IDE 之前完成。不要从公共初始化器、服务端类或 Lua 脚本直接引用此类。

### 安装条件与能力边界

- **管理员客户端必须同时安装 MC Lua IDE 和提供窗口的扩展 Mod**。服务端不必安装仅提供此界面的扩展 Mod，普通玩家也不必安装它。
- 原有函数/文档注册 API 仍支持“扩展只装服务端，管理员自动收到文档”。独立窗口运行的是可信的客户端 Java 代码，不通过服务器下发或执行任意 Java/HTML。
- 本接口只扩展已经打开的 IDE，不授予管理员权限。打开 IDE、读取服务端文件和执行脚本仍受原来的服务端权限校验控制。
- 内容需要访问服务端数据时，由扩展 Mod 自行设计网络协议，并在服务端验证权限。不能把客户端菜单是否可见当作权限验证。
- 这不是玩家游戏内界面的 `MC.UI` API，也不是单独替换 Minecraft 的 `Screen`。

### 用户操作与布局

窗口自动列在 **UI与窗口 → Mod 窗口** 的子菜单，显示窗口标题及来源 Mod。点击打开，再次点击关闭；若它吸附后被其他标签遮住，点击会先切换到该窗口。窗口很多时有上一页/下一页。

IDE 负责统一的标题栏、关闭按钮、边框、右侧出现/退出动画、拖动移动、边缘缩放提示、左/右/下吸附，以及吸附后的代码区域缩小。同侧多个内置/第三方窗口共享标签组，可切换，不会相互挤掉。

位置、悬浮尺寸、吸附侧、开关状态，以及当前激活的吸附标签会自动保存。第三方窗口布局保存在管理员客户端的 `config/mc_lua_ide/extension-window-layout.properties`，暂时卸载某个 Mod 后不会删除其布局。固定布局时吸附窗口不能移动；“个性化 → 窗口布局 → 固定布局时可调边框”继续适用。获得焦点的悬浮窗口按 Esc 关闭；布局固定的吸附窗口按 Esc 关闭整个 IDE、保留窗口布局。

### 完整示例：输入框、插入按钮、复制按钮

源码另见仓库的 `examples/third_party_windows/TestModClient.java`。以下类放在扩展 Mod 的客户端源码集中；本仓库构建时会编译验证这个示例：

```java
package com.example.testmod;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;

public final class TestModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        McLuaIdeWindowApi.window("test_mod", "script_tools")
            .title(Component.literal("TestMod 脚本工具"))
            .size(360, 220)
            .minimumSize(180, 120)
            .content(ToolContent::new)
            .register();
    }

    private static final class ToolContent implements McLuaIdeWindowApi.Content {
        private final McLuaIdeWindowApi.Context context;
        private final EditBox input;
        private final Button insert;
        private final Button copy;
        private int width = 200;

        ToolContent(McLuaIdeWindowApi.Context context) {
            this.context = context;
            input = new EditBox(context.font(), 8, 28, 200, 20, Component.literal("代码"));
            input.setMaxLength(5000);
            input.setValue("print(\"Hello TestMod!\")");
            insert = Button.builder(Component.literal("插入代码"), button ->
                context.insertText(input.getValue() + "\n")).bounds(8, 56, 100, 20).build();
            copy = Button.builder(Component.literal("复制"), button ->
                context.copyText(input.getValue())).bounds(112, 56, 64, 20).build();
        }

        @Override
        public void resize(int width, int height) {
            this.width = width;
            input.setWidth(Math.max(1, width - 16));
            insert.setWidth(Math.min(100, Math.max(40, (width - 20) / 2)));
            copy.setX(insert.getRight() + 4);
            copy.setWidth(Math.max(1, Math.min(64, width - copy.getX() - 8)));
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            graphics.text(context.font(), "输入代码后插入当前编辑文件", 8, 8, 0xFFC8D9EA);
            input.extractRenderState(graphics, mouseX, mouseY, 0);
            insert.extractRenderState(graphics, mouseX, mouseY, 0);
            copy.extractRenderState(graphics, mouseX, mouseY, 0);
            graphics.text(context.font(),
                context.font().plainSubstrByWidth("当前：" + context.documentName(), Math.max(1, width - 16)),
                8, 90, 0xFF9CB1C8);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            boolean selected = input.mouseClicked(event, doubleClick);
            input.setFocused(selected);
            return selected || insert.mouseClicked(event, doubleClick) || copy.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return input.isFocused() && input.keyPressed(event);
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return input.isFocused() && input.charTyped(event);
        }

        @Override
        public void focusChanged(boolean focused) {
            if (!focused) input.setFocused(false);
        }
    }
}
```

扩展 Mod 的 `fabric.mod.json` 添加客户端入口，依赖设置沿用上文：

```json
{
  "entrypoints": {
    "client": ["com.example.testmod.TestModClient"]
  }
}
```

需要本地化标题时，用 `.title(Component.translatable("window.test_mod.script_tools"))`，并在扩展 Mod 自己的 `assets/test_mod/lang/zh_cn.json`、`en_us.json` 中添加翻译。标题不是 Lua 函数，不会混入 Tab 补全或 API 文档函数列表。

### 注册与主动开关

```java
McLuaIdeWindowApi.window(ownerModId, windowId)
    .title(Component.literal("窗口标题"))
    .size(360, 240)
    .minimumSize(160, 100)
    .content(context -> new MyWindowContent(context))
    .register();

// 已打开 IDE 时，在客户端主线程调用：
boolean opened = McLuaIdeWindowApi.open("test_mod:script_tools");
boolean closed = McLuaIdeWindowApi.close("test_mod:script_tools");

// 若从工作线程请求，先调度回客户端线程：
Minecraft.getInstance().execute(() -> McLuaIdeWindowApi.open("test_mod:script_tools"));
```

完整 ID 为 `ownerModId:windowId`。同一 Mod 可注册多个不同 ID，不同 Mod 可使用相同 `windowId`；完整 ID 重复时抛出 `IllegalArgumentException`，不会覆盖别人的窗口。窗口 ID 使用小写字母、数字、`_`、`-`、`.`，可用 `/` 分段。不要在游戏循环中反复注册。

`size`/`minimumSize` 指包含标题栏和边框的窗口尺寸，以 IDE 逻辑像素计；默认尺寸 360×240，默认最小尺寸 160×100。最小宽度至少 100、最小高度至少 70，默认尺寸不得小于最小尺寸或超过 4096。实际尺寸会受屏幕可用空间限制，并服从用户保存的布局。

`open/close` 不会自行打开 IDE。当没有活动的 IDE 或该窗口未注册到当前 IDE 会话时返回 `false`；成功找到并操作窗口返回 `true`。它们必须在客户端主线程调用，否则抛出 `IllegalStateException`。注册定义可通过 `.build()` 再交给 `McLuaIdeWindowApi.register(definition)`；`definitions()` 返回当前注册定义的只读快照。

### Content 内容接口

只需实现 `render`；其余回调都有默认空实现。工厂应每次返回一个新实例，不能把同一个可变内容对象共享给不同窗口或 IDE 会话。

- `render(graphics, mouseX, mouseY)`：绘制内容。原点为**窗口内部内容区左上角**，不包含外框与标题栏；鼠标坐标也已经转换为这个原点。IDE 自动裁剪越界绘制，并统一缩放。鼠标在内容区外时坐标可以为负或超出内容尺寸。
- `resize(width, height)`：内容区域尺寸变化时、绘制前调用；按传入尺寸布局自己的控件，不要再减一遍标题栏高度。
- `tick()`：窗口开启时每游戏 tick 调用，包括被同侧另一个标签遮住的窗口；关闭时不调用。
- `opened()/closed()`：窗口开启/关闭通知。首次打开才创建内容；同一 IDE 会话中关闭再打开会复用内容。
- `focusChanged(focused)`：鼠标/标签/主动打开带来的焦点变化；失去焦点时应取消自有输入框焦点。
- `mouseClicked(event, doubleClick)`、`mouseDragged(event, dragX, dragY)`、`mouseReleased(event)`：内容区鼠标交互。坐标是内容局部坐标，拖动开始后即使鼠标移出窗口也会继续转发，直到释放。标题拖动和边框缩放不传给内容。
- `mouseScrolled(x, y, horizontal, vertical)`：窗口内滚轮事件。
- `keyPressed(event)`、`keyReleased(event)`、`charTyped(event)`：只转发给当前激活且获得焦点的窗口。内容没有处理的键也不会误输入代码编辑器。Esc 和顶部菜单由 IDE 管理。
- `disposed()`：IDE 会话结束时释放动态纹理、缓冲区等资源；已经关闭的内容也会收到。不要在 `closed()` 中销毁还需在下一次打开时使用的资源。

所有内容回调在客户端主线程调用。不要在绘制或 tick 回调中同步等待网络、读大文件或执行耗时任务；后台任务的结果应通过 `Minecraft.execute` 回到客户端线程更新。内容可使用原版 `EditBox`、`Button` 或自己的绘制，但需要自行转发事件；不要把内部控件注册到父 IDE 的根控件列表。保持绘制矩阵与裁剪栈平衡，不要绘制/接管其他窗口的外框。

内容抛出普通异常或链接错误时，IDE 会停用这个窗口的内容回调，显示红色错误提示，并在 `latest.log` 中记录完整窗口 ID 与错误；其他窗口保持工作。退出 IDE 仍会尝试执行资源释放回调。

### Context 与代码编辑器交互

| 方法 | 作用 |
| --- | --- |
| `minecraft()` | 当前客户端；访问游戏状态仍要在客户端线程 |
| `font()` | IDE 普通界面字体 |
| `documentName()` | 当前文件名；没有打开文件时为空字符串 |
| `source()` | 当前主代码框文本 |
| `selectedText()` | 当前主代码框选中文本，没有选中时为空 |
| `insertText(text)` | 在主代码框光标处插入原始文本、替换选区，参与正常撤销/高亮流程；没有已加载的可编辑文件时返回 false。不自动保存或运行 |
| `copyText(text)` | 复制到系统剪贴板 |
| `close()` | 关闭自己的窗口 |

`Context` 与控件都属于当前 IDE 会话，请勿在 `disposed()` 之后继续使用。尺寸、焦点与外框由 IDE 控制，扩展代码不应依赖内部 `LuaIdeScreen` 或 `DockSide` 等实现类。

## 注册器说明

```java
McLuaIdeApi.function(ownerModId, namespace, method, function)
McLuaIdeApi.rootFunction(ownerModId, rootName, method, function)
```

| 参数 | 含义 |
| --- | --- |
| `ownerModId` | 注册函数的 Fabric 模组 ID，例如 `example_mod` |
| `namespace` | `MC` 下的独立 Lua 命名空间，例如 `Example` |
| `rootName` | 独立顶级 API 大类名称，例如 `TestMod`；仅用于 `rootFunction` |
| `method` | Lua 函数名，例如 `welcome` |
| `function` | 实际实现，接收 `FunctionContext` 和 LuaJ `Varargs` |

`function(...)` 生成 `MC.<namespace>.<method>`；`rootFunction(...)` 生成 `<rootName>.<method>`。`rootName` 会同时成为 API 文档中的独立大类，例如 `TestMod.playerCount()` 位于“TestMod API”下。

函数参数按顺序声明：

```java
.required("playerUuid", McLuaIdeApi.ValueType.PLAYER_UUID)
.optional("message", McLuaIdeApi.ValueType.STRING)
```

所有必填参数必须写在可选参数之前。参数声明会生成函数签名，并用于 IDE 实时检查。

### 客户端 Mod 专用函数

如果函数只有在目标玩家安装了客户端 Mod 时才会生效，在 `.build()` 前添加：

```java
.requiresClientMod()
```

声明后，该函数会被放入 API 文档最上方独立的“客户端 Mod 专用 API”大类，并在悬浮详情和函数详情中显示醒目的安装限制。未声明的 `MC.*` 扩展进入“服务端 / 原版 API”；未声明的 `rootFunction` 则进入自己创建的独立大类。

这个声明只负责 IDE 元数据分类和警告，不会替第三方模组完成网络握手或权限校验。函数实现仍必须检查服务器是否允许对应客户端能力、目标玩家是否在线、客户端通道是否存在以及协议是否兼容，并在不满足时返回明确错误。

## 参数类型

| `ValueType` | IDE 检查 | 插入窗口 |
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

## 多语言文档

每个函数至少需要一种翻译。建议总是提供 `zh_cn` 和 `en_us`：

```java
.translation("zh_cn", McLuaIdeApi.translation("分类显示名", "函数简介")
    .returns("返回值说明")
    .details("额外限制、线程行为或注意事项")
    .parameter("参数名", "参数说明")
    .usage("可直接复制运行的 Lua 模板")
    .build())
```

语言代码不区分大小写，`-` 会转换为 `_`。客户端优先使用当前系统语言，其次匹配相同语言前缀，然后回退到 `en_us` 或第一项翻译。

同一命名空间或独立大类内的各函数应使用相同“分类显示名”。API 文档会把需要客户端 Mod 的函数统一归入“客户端 Mod 专用 API”，把普通 `MC.*` 扩展归入“服务端 / 原版 API”，并为每个普通 `rootFunction` 根名称创建独立大类。详情始终显示函数来源模组。

## 注册表 ID、条目注释与组件字段提示

IDE 不再维护一张固定的“常见 ID 列表”。它会先枚举当前服务端的全部注册表类型，再按需分页读取所选注册表中的全部条目，因此自定义注册表、动态注册表以及其他 Mod 的命名空间都会自动出现。例如：

- `minecraft:timeline` 注册表中的 `minecraft:day` 是时间轴 ID；`minecraft:world_clock` 则保存 `minecraft:overworld` 等世界时钟定义；
- `minecraft:item` 注册表中的 `minecraft:apple` 是物品 ID；
- `minecraft:mob_effect` 注册表中的 `minecraft:dolphins_grace` 是状态效果 ID。

公开入口为：

```java
import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi;
import static wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi.Target.KEY;
import static wiki.ica.xiaomiao.mcluaide.api.McLuaIdeRegistryApi.Target.VALUE;
```

给模组自己的注册表类型添加中文和英文说明：

```java
McLuaIdeRegistryApi.registry("example_mod", "example_mod:spell")
    .translation("zh_cn", "法术 ID", "example_mod 提供的全部法术类型")
    .translation("en_us", "Spell ID", "All spell types provided by example_mod")
    .register();
```

给具体条目添加名称和用途注释。条目可以位于原版注册表中，只要条目自身属于注册模组的命名空间：

```java
McLuaIdeRegistryApi.entry("example_mod", "minecraft:item", "example_mod:magic_wand")
    .translation("zh_cn", "魔法杖", "施放法术时使用的物品")
    .translation("en_us", "Magic Wand", "Item used to cast a spell")
    .register();

McLuaIdeRegistryApi.entry("example_mod", "example_mod:spell", "example_mod:fireball")
    .translation("zh_cn", "火球术", "发射一枚会爆炸的火球")
    .translation("en_us", "Fireball", "Launches an exploding fireball")
    .register();
```

名称、说明、完整 ID、命名空间和中文拼音首字母都可用于筛选。没有显式注释的物品、方块、实体、附魔和状态效果仍会尽量使用游戏本地化名称；其余未知类型会显示完整资源 ID。

如果模组提供自己的物品堆组件，还可以声明组件 JSON 中哪个字段应该填写哪类 ID。路径类似 JSON Pointer，空字符串表示组件值根节点，`*` 匹配一层对象键或数组下标：

```java
// example_mod:spell_focus = {"spell":"example_mod:fireball"}
McLuaIdeRegistryApi.componentHint(
        "example_mod",
        "example_mod:spell_focus",
        "/spell",
        VALUE,
        "example_mod:spell")
    .translation("zh_cn", "法术 ID", "选择法杖绑定的法术")
    .translation("en_us", "Spell ID", "Select the spell bound to this focus")
    .register();

// example_mod:spell_levels = {"example_mod:fireball":3}
McLuaIdeRegistryApi.componentHint(
        "example_mod",
        "example_mod:spell_levels",
        "/*",
        KEY,
        "example_mod:spell")
    .translation("zh_cn", "法术 ID", "对象键是法术 ID")
    .translation("en_us", "Spell ID", "Object keys are spell IDs")
    .register();
```

组件编辑器打开字段时会自动切换到声明的注册表，并提供上下键、Tab/Enter 和鼠标选择。识别不到的字段也可以点击“ID类型”，在服务器全部注册表类型中手动筛选。注释由服务端发送给管理员客户端，所以扩展模组仍然只需安装在服务端。

提示路径始终相对于所属组件的根节点。即使 `example_mod:spell_focus` 出现在收纳袋 → 容器 → 物品 → `components` 内，原来的 `/spell` 声明也会自动生效，无需重复注册外层路径。字段名中含 `/` 或 `~` 时，分别使用 JSON Pointer 转义 `~1` 或 `~0`。同一个数据结构支持简写字符串与完整对象时，应分别为根路径 `""` 和对象字段路径（例如 `/id`）注册提示。

统一组件树也会使用这些提示补充默认值中没有出现的可选 ID 字段。例如上面的 `/spell` 会成为可以直接点击添加的字符串字段，并关联 `example_mod:spell` 的搜索。已存在的对象/列表结构不会被字符串提示覆盖。其他非 ID 字段可从物品默认组件值中推导类型；没有默认值或提示的任意 Codec 不能保证完整推导，此时保留自定义字段编辑入口。

实际 ID 来自服务端，而不是注释表；注册注释不会凭空创建游戏 ID。目录同时枚举静态、动态、可重载注册表，并单独读取配方、进度和已加载维度。搜索输入以 `#` 开头时会列出当前注册表标签；是否允许标签仍由字段所属 Codec 决定。未声明字段会尝试按键名和附近结构推断，语义不明确的自定义字段应使用 `componentHint`，不要依赖猜测。

所有注册建议在 `ModInitializer#onInitialize` 中完成。为避免模组互相篡改说明：普通模组只能注释自己命名空间的注册表、条目或组件；同一个目标不能重复注册。`McLuaIdeRegistryApi.EXTENSION_API_VERSION` 可用于兼容性检查。

## 线程安全

Lua 主体运行在 MC Lua IDE 的脚本线程，不在 Minecraft 服务端主线程。读取或修改玩家、世界、实体、容器等游戏状态时必须使用：

```java
context.onServer(server -> {
    // 在服务端主线程安全访问 Minecraft 对象
    return LuaValue.TRUE;
});
```

`onServer` 最多等待 10 秒。脚本被停止、线程被中断或等待超时时会转换成可读的 Lua 错误。

`FunctionContext` 还提供：

```java
context.server();      // 原始 MinecraftServer；不要在脚本线程直接操作游戏状态
context.scriptName();  // 当前脚本名
context.ownerModId();  // 当前扩展函数的模组 ID
context.symbol();      // 完整符号，例如 MC.Example.welcome 或 TestMod.playerCount
```

## 返回值和错误

函数实现返回 LuaJ `Varargs`。常见返回方式：

```java
return LuaValue.NIL;
return LuaValue.valueOf(true);
return LuaValue.valueOf("text");
return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(1), LuaValue.valueOf(2) });
```

可以抛出 `LuaError` 提供明确的脚本错误；其他异常会自动转换为：

```text
[模组ID] 完整API符号 调用失败：具体原因
```

## 命名和冲突规则

- 命名空间、函数名和参数名必须是 Lua 标识符：字母开头，只能包含字母、数字和下划线。
- 第三方模组不能覆盖 MC Lua IDE 的内置命名空间，例如 `Block`、`Player`、`Event`、`UI`、`File`。
- 一个第三方命名空间只能属于一个模组，避免两个模组悄悄混入同一 API 分类。
- 独立大类不能使用 `MC`、`print`、`math`、`string`、`table`、`require` 等 Lua 或沙箱保留全局名称。
- 一个独立大类只能属于一个模组；同一拥有者可以在该大类中注册任意数量的不重名方法。
- 函数符号按不区分大小写的方式判重。
- 注册只影响之后启动的 Lua 脚本；应在模组初始化阶段完成注册。

## IDE 同步行为

管理员按 U 或执行 `/lua` 打开 IDE 时，客户端会在权限校验后从服务端读取第三方 API 元数据。普通玩家无权读取这些文档。连接断开时客户端会清理服务器提供的扩展文档，避免切换服务器后残留。

如果服务器和管理员客户端协议版本不一致，IDE 会拒绝打开并提示版本不兼容。
