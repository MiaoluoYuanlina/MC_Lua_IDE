# 扩展清单（纯数据扩展）

[English](manifests.md) · 简体中文

清单是一个 JSON 文件，不用写 Java 就能给 IDE 添加**代码片段**、**命令**和**终端 shell**。适合玩家、整合包作者和脚本作者。需要真正的逻辑（读取自己数据的补全、窗口等会运行代码的功能）时，请使用 [编辑器 API](editor-api.zh_cn.md)。

清单**不能运行代码**：命令只能插入文本、复制文本、显示提示，或打开一个已注册的 IDE 窗口。

## 清单放在哪里

| 位置 | 作者 | 可添加 shell |
| --- | --- | --- |
| `config/mc_lua_ide/extensions/<名称>.json` | 玩家 | 可以 |
| `config/mc_lua_ide/extensions/<名称>/extension.json` | 玩家 | 可以 |
| 任意已安装模组 JAR 内的 `assets/<modid>/mc_lua_ide/extension.json` | 模组/整合包作者 | **不可以**（会提示并忽略） |

模组内的清单，所有者固定为该模组 ID，`"id"` 字段会被忽略。`config/` 下的清单，`"id"` 不能与已安装模组的 ID 相同（否则看起来像该模组自己的内容）。

清单在每次游戏运行中第一次打开 IDE 时读取。修改后，在命令面板（Ctrl+Shift+P）执行「重新加载扩展清单」；「显示扩展状态」会显示已加载的数量。问题会写入 `latest.log`，前缀为 `Extension manifest:`。

## 格式

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

完整可用的文件见 [`examples/hello-pack.json`](examples/hello-pack.json)；构建时会在测试中加载它，因此它始终有效。

| 字段 | 含义 |
| --- | --- |
| `schema` | 可选，若填写必须为 `1`；其他数字会让整份文件被拒绝并给出明确提示。 |
| `id` | `config/` 下的文件必填。格式 `[a-z][a-z0-9_-]{1,63}`，清单之间不能重复。 |
| `name`、`version` | 可选，用于状态提示。 |

### `snippets`

```json
{ "trigger": "giveitem", "description": "给予物品", "body": ["MC.Item.give(${1:player}, \"${2:minecraft:stone}\")", "$0"] }
```

- 输入 `trigger` 后按 **Tab** 展开 `body`。`body` 可以是字符串，也可以是行数组（用换行连接）。
- 占位符：`${1:默认值}`、`$1`、`$0`（最后的光标位置）。Tab 按数字顺序跳转，`$0` 最后。`\n`、`\t` 会跟随当前行缩进。
- `trigger`：标识符，最长 32 个字符。内置模板（`fn`、`lf`、`anon`、`for`、`fori`、`forp`、`if`、`ife`、`eif`、`while`、`rep`、`req`、`pcall`、`tbl`、`print`）不能被替换；两个扩展也不能使用同一触发词，后者会被拒绝。
- `body` 最长 4000 个字符；占位符编号必须是 0–99，`${` 必须闭合。

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

- `id`：小写字母、数字、`_`、`-`、`.`。完整命令 ID 为 `<所有者>:<id>`。
- `title`：字符串，或带 `zh_cn` / `en_us` 的对象。语言在清单加载时确定，切换语言后请重新加载。
- `hint`：可选，显示在命令面板中命令旁边（不会绑定按键）。
- `context_menu`：同时列入代码编辑器的右键菜单。
- 命令出现在命令面板（**Ctrl+Shift+P**）。

动作类型：

| `type` | 字段 | 效果 |
| --- | --- | --- |
| `insert` | `text` | 在光标处插入（替换选区），可正常撤销。没有可编辑文件时会提示。 |
| `copy` | `text` | 复制到剪贴板。 |
| `notice` | `text` | 在 IDE 中显示一条短提示。 |
| `open_window` | `window` | 按完整 ID（`modid:windowid`）打开已注册的 IDE 窗口。 |

`text` 可以使用变量 `${selection}`（选中文本）、`${file}`（文件名）、`${line}`（光标所在行，从 1 开始）。其他内容（包括别的 `${...}`）保持原样。`text` 最长 8000 个字符，也可以是行数组。

其他 `type` 会被报告并跳过，绝不会被执行。

### `shells`

```json
{ "id": "my_bash", "name": "My Bash", "command": ["C:/tools/bash.exe", "--login", "-i"] }
```

在本地终端的 **+** 菜单中添加一项。只有 `config/mc_lua_ide/extensions` 下的清单可以这样做，因为 shell 条目会在你的电脑上启动程序。服务器终端永远不会使用客户端定义的 shell。

`command` 有 1–32 个非空参数，第一个是程序。

## 限制与错误处理

- 文件最大 256 KB；每个文件最多 200 个片段、100 个命令、20 个 shell（多出的条目会被丢弃并提示）。
- 有问题的条目会被跳过并报告，同一文件的其余内容仍会加载；JSON 无效则整份文件被跳过。
- 清单不会修改服务器文件、运行 Lua 或访问网络。
