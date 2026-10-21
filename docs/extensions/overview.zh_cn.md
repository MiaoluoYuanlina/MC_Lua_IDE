# 扩展 MC Lua IDE

[English](overview.md) · 简体中文

扩展 IDE 有两种方式，按需求选择，而不是按身份选择。

| 你想… | 使用 | 需要 Java | 运行位置 |
| --- | --- | --- | --- |
| 给自己的 IDE 或整合包添加代码片段，或插入/复制文本的命令 | [扩展清单](manifests.zh_cn.md)（JSON） | 不需要 | 管理员客户端 |
| 添加读取自己数据的补全、可启用/禁用的命令、来自模组的终端 shell | [编辑器 API](editor-api.zh_cn.md) | 需要 | 管理员客户端 |
| 添加可拖动的工具窗口 | [窗口 API](third-party-api.zh_cn.md) | 需要 | 管理员客户端 |
| 添加 Lua 函数（`MC.Example.welcome()`）、文档页、注册表说明 | [Lua / 文档 / 注册表 API](third-party-api.zh_cn.md) | 需要 | 仅**服务端** |

适用于所有扩展的规则：

- 扩展不获得管理员权限。打开 IDE、读取服务器文件和运行脚本仍由服务端检查。
- 名称总有所有者：`ownerModId:id`。任何人都不能覆盖其他扩展的命令、片段触发词、窗口或 Lua 函数；重复会被拒绝。
- 出错的扩展会被隔离。抛出异常的命令只显示提示，抛出异常的补全提供器会被关闭，清单中的错误条目会被跳过并在 `latest.log` 中说明。
- 任何会在玩家电脑上启动程序的功能（终端 shell）只允许来自受信任的 Java 或玩家自己的 `config/` 文件，绝不允许来自模组内的清单，也绝不用于服务器。

## 快速开始

**纯数据**：把文件放入 `config/mc_lua_ide/extensions/`，例如 [`examples/hello-pack.json`](examples/hello-pack.json)，然后在命令面板执行「重新加载扩展清单」。

**Java**：在你的 `ClientModInitializer` 中：

```java
McLuaIdeEditorApi.snippet("test_mod", "giveitem", "给予物品", "MC.Item.give(${1:player}, \"${2:minecraft:stone}\")$0");
McLuaIdeEditorApi.command("test_mod", "say_hi").title(Component.literal("打招呼"))
    .run(context -> context.notice("来自 " + context.documentName())).register();
```

## 依赖配置

Gradle 依赖与 `fabric.mod.json` 的配置见[第三方 API 手册第 1 节](third-party-api.zh_cn.md)。`McLuaIdeEditorApi` 与 `McLuaIdeWindowApi` 只存在于客户端源集：请在 `ClientModInitializer#onInitializeClient` 中注册，绝不要在服务端代码中引用它们（专用服务器会因缺少类而崩溃）。编辑器 API 不需要 ImGui 依赖，只有用 ImGui 绘制的窗口才需要。

## 兼容性承诺

| API | 版本常量 | 说明 |
| --- | --- | --- |
| `McLuaIdeEditorApi` | `EXTENSION_API_VERSION = 1` | 可能新增方法，现有行为保持不变 |
| `McLuaIdeWindowApi` | `EXTENSION_API_VERSION = 2` | 旧版 `Content` 窗口仍可使用并显示警告 |
| 清单 | `"schema": 1` | 其他 schema 数字会被拒绝并给出明确提示 |
