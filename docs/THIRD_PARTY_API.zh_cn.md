# MC Lua IDE 第三方模组扩展 API

其他 Fabric 服务端模组可以通过公开 Java API 向 Lua 环境注册自己的函数。既可以继续注册到 `MC.<命名空间>.*`，也可以创建属于模组自己的顶级 API 大类，例如 `TestMod.*`。注册后的函数会同时出现在：

- 服务端 Lua 运行环境中的 `MC.<命名空间>.<函数>` 或 `<独立大类>.<函数>`；
- IDE 的 Tab 补全及中文简介；
- 鼠标悬浮详情；
- API 文档的多级分类、筛选及拼音首字母搜索；
- 参数数量、参数类型和资源 ID/UUID 静态检查；
- API 文档的“复制”和“插入”模板。

第三方文档由服务端自动发送给拥有管理权限的 IDE 客户端。因此，普通的服务端扩展模组可以只安装在服务端，管理员客户端不需要额外安装该扩展模组。只有确实会向目标玩家客户端发送专用数据的函数，才应声明为“客户端 Mod 专用 API”。

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
