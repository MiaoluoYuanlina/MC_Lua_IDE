# 编辑器 API（`McLuaIdeEditorApi`，客户端）

[English](editor-api.md) · 简体中文

面向 Fabric 模组作者。可为管理员的 IDE 添加**命令**、**代码片段**、**补全提供器**和**终端 shell**。这是仅客户端、受信任的 Java：**管理员客户端需要同时安装 MC Lua IDE 与你的模组**，服务器和普通玩家不需要。如果只需要代码片段或简单的插入/复制命令，用[扩展清单](manifests.zh_cn.md)即可，完全不用写 Java。

`McLuaIdeEditorApi.EXTENSION_API_VERSION` 为 `1`。像 [`McLuaIdeWindowApi`](third-party-api.zh_cn.md) 一样，请在 `ClientModInitializer` 中注册。这里所有回调都在客户端线程运行。API 不授予任何管理员权限：打开 IDE、读取服务器文件和运行脚本仍由服务端检查。

## 命令

```java
McLuaIdeEditorApi.command("test_mod", "wrap_in_pcall")
    .title(Component.translatable("command.test_mod.wrap_in_pcall"))
    .shortcutHint("demo")                     // 仅用于显示，API 不绑定按键
    .contextMenu(true)                        // 同时出现在编辑器右键菜单
    .enabledWhen(context -> !context.selectedText().isEmpty())
    .run(context -> context.insertText("pcall(function()\n" + context.selectedText() + "\nend)"))
    .register();
```

命令出现在命令面板（Ctrl+Shift+P），设置 `contextMenu(true)` 后也出现在代码编辑器的右键菜单。完整 ID 为 `ownerModId:commandId`，重复会抛出 `IllegalArgumentException`。

`EditorContext`（只在回调执行期间有效，请勿保存）：

| 方法 | 用途 |
| --- | --- |
| `documentName()` | 当前文件名；没有则为空 |
| `source()` | 当前编辑器文本 |
| `selectedText()` | 选中文本，没有则为空 |
| `caretOffset()` / `caretLine()` | 光标的字符偏移 / 行号（从 1 开始） |
| `insertText(text)` | 在光标处插入（替换选区），走正常撤销和高亮；没有可编辑文件时返回 `false`。不会保存或运行 |
| `copyText(text)` | 复制到系统剪贴板 |
| `notice(message)` | 在 IDE 中显示一条短提示 |
| `openWindow(windowId)` | 打开已注册的 IDE 窗口；未知窗口返回 `false` |

如果你的动作或 `enabledWhen` 抛出异常，IDE 会连同命令 ID 记录堆栈、显示提示并继续运行。

## 代码片段

```java
McLuaIdeEditorApi.snippet("test_mod", "giveitem", "给予物品",
    "MC.Item.give(${1:playerUuid}, \"${2:minecraft:stone}\")$0");
```

输入触发词后按 Tab 展开；Tab 继续在 `${1:…}`、`${2:…}` … 之间跳转，最后到 `$0`。触发词是最长 32 个字符的标识符。内置模板不能被替换，同一触发词只能注册一次。内容最长 4000 个字符，并在注册时检查（未闭合的 `${`、非数字的占位符编号），所以错误会在启动时抛出，而不是等玩家按 Tab 时才发现。

## 补全提供器

```java
McLuaIdeEditorApi.completionProvider("test_mod", "my_api", request -> {
    if (!request.prefix().startsWith("TestMod.")) return List.of();
    return List.of(new McLuaIdeEditorApi.CompletionItem("TestMod.playerCount", "playerCount()", "在线玩家数量"));
});
```

- 每次补全列表重新计算时在客户端线程调用，所以必须**快速且不能阻塞**。
- `CompletionRequest` 包含 `documentName`、`source`、`caretOffset`、`prefix`（光标前的标识符/点路径，例如 `TestMod.pl`）和 `explicit`（玩家主动触发补全）。
- 条目的 `insertion` 必须以 `prefix` 开头（且不等于它）；接受时会替换该前缀，不满足的条目会被丢弃。每个提供器最多贡献 50 项；`insertion` 为单行，最长 200 个字符。
- 扩展条目列在 IDE 自带建议之后，不会替换它们。
- 抛出异常的提供器在本次会话中被关闭，并只记录一次日志。
- 输入两个字符后显示补全，玩家主动触发时则立即显示。

如果你的 Lua 函数需要在各处被补全、有文档和类型检查，请改用服务端的 [`McLuaIdeApi`](third-party-api.zh_cn.md) 注册：补全、悬浮说明和参数检查都是自动的。

## 终端 shell

```java
McLuaIdeEditorApi.shell("test_mod", "my_tool", "My Tool Shell", List.of("C:/tools/mytool.exe", "--interactive"));
```

在本地终端的 **+** 菜单中添加一项。`command` 有 1–32 个非空参数，名称 1–40 个字符。这会在玩家电脑上启动程序，所以只有受信任的 Java 和玩家自己 `config/` 下的清单可以添加。服务器终端永远不会使用客户端注册的 shell。

## 响应变化与清理

`McLuaIdeEditorApi.onChange(listener)` 会告知哪个注册表变化（`COMMANDS`、`SNIPPETS`、`COMPLETION`、`SHELLS`）。`unregisterOwner(ownerModId)` 删除某个所有者注册的全部内容，IDE 在重新加载清单时使用它。不要注销其他模组的内容。

只读快照：`commands()`、`snippets()`、`shells()`。

## 校验汇总

| 项目 | 规则 |
| --- | --- |
| `ownerModId` | `[a-z][a-z0-9_-]{1,63}` |
| 命令 / 提供器 / shell ID | 小写字母、数字、`_`、`-`、`.`，可用 `/` 分段 |
| 重复 | 抛出 `IllegalArgumentException`；任何扩展都不能覆盖其他扩展 |
| 片段内容 | 1–4000 个字符，占位符有效 |
| 补全条目 | 单行、≤ 200 个字符、每个提供器 ≤ 50 项 |

## 兼容性

后续版本可能新增方法；API 版本保持 `1` 时，现有方法的行为不变。请勿依赖 `ImGuiIdeScreen`、`NativeCodeArea`、`ExtensionManifests` 等内部类。
