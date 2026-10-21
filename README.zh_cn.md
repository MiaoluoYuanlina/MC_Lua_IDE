<p align="center"><img src="src/main/resources/assets/mc_lua_ide/icon.png" width="128" alt="MC Lua IDE 图标"></p>

<h1 align="center">MC Lua IDE</h1>

<p align="center">为 Minecraft 服务器编写 Lua 脚本，并在游戏内为管理员提供完整的 IDE。</p>

<p align="center"><a href="README.md">English</a> · 简体中文 · <a href="README.ja_jp.md">日本語</a></p>

---

## 简介

MC Lua IDE 是适用于 **Minecraft 26.1.2** 的 Fabric 模组。服务器管理员可以在游戏运行时编写**在服务端运行**的 Lua 脚本，并直接在游戏里使用配套的 IDE：编辑器、调试器、API 文档、终端，以及数据包和资源包工具。

- **脚本在服务端运行**：可以读取和修改世界、响应事件、与玩家交互、打开菜单。普通玩家无需安装任何东西。
- **IDE 在管理员的客户端运行**：游戏中按 **U** 打开。只有服务器所有者（OP 等级 4，或单人世界的房主）可以使用；安装模组本身不会授予任何权限。
- **默认安全**：脚本运行在有时间、输出和事件限制的沙箱中。高风险功能（Java 访问、文件与系统访问、服务器终端、面向 AI 工具的 MCP）默认关闭，需要服务器所有者手动开启。

```lua
-- 向每位加入的玩家问好。
MC.Event.onPlayerJoined(function(event)
    MC.Chat.message(event.playerUuid, {text = "欢迎，" .. event.playerName .. "！", color = "#55FF55"})
end)
```

## 功能一览

| 方面 | 内容 |
| --- | --- |
| 编辑器 | Lua、JSON、mcfunction 高亮与实时诊断；API、变量和注册表 ID（物品、方块、音效等）补全；支持正则的查找替换；跨文件的转到定义、查找引用与重命名；大纲；代码片段；多光标；小地图；拆分编辑器；文件对比；在文件中查找；命令面板 |
| 调试器 | 条件断点、命中次数、日志点；异常断点；单步进入 / 越过 / 跳出；运行到光标；调用栈、局部变量、监视、悬停查看值、协程；暂停时可冻结世界 |
| Minecraft API | 玩家、物品与数据组件、方块、实体、事件、计时、聊天与 JSON 文本、标题、音效、粒子、虚拟箱子菜单、命令、数据文件、资源包；IDE 内有带示例的完整文档 |
| 数据包与资源包 | 与脚本一起编辑：带游戏示例的 JSON 编辑器、带图层的 PNG 绘图、音效与结构预览、一键重载 |
| 终端 | 本机 shell（PowerShell、cmd、bash 等）以及可选的服务器 shell |
| 协作与安全 | 显示谁在编辑、带冲突检查的保存、审计日志、自动脚本快照与恢复、设置导出 / 导入、Git 历史 |
| 扩展 | 用 JSON 清单添加代码片段和命令；供其他模组添加 Lua 函数、IDE 窗口、命令和补全的 Java API |

界面和手册支持中文、英文和日文（跟随游戏语言）。

## 快速开始

运行要求：Minecraft 26.1.2、Fabric Loader 0.19.3 及以上、Fabric API 0.155.2 及以上、Java 25。把本模组和 Fabric API 放入服务端以及使用 IDE 的管理员客户端的 `mods/` 目录（单人游戏只需放入自己的客户端）。

1. 进入世界后按 **U**。第一次打开时会显示**欢迎页**，可在 `examples/` 下创建示例项目。
2. **文件 → Lua 目录**，按 **Ctrl+N** 新建脚本。
3. 编写代码，**Ctrl+S** 保存，**Ctrl+Enter** 运行。输出显示在**运行日志**中。

## 使用方法

### 编写脚本

脚本保存在服务端的 `config/mc_lua_ide/scripts/` 中（可以使用子目录）。所有 API 都在 `MC` 表下；**API 文档**窗口（**Ctrl+F1**）列出了全部函数、参数和示例，输入时补全会提示函数、参数和 ID。

```lua
-- 给第一位在线玩家三颗钻石，并输出其位置。
local player = MC.Player.list()[1]
if player then
    local pos = MC.Player.position(player.uuid)
    print(player.name, pos.x, pos.y, pos.z)
    MC.Item.give(player.uuid, "minecraft:diamond", 3)
end

-- 放置方块（x, y, z, 方块 ID）。
MC.Block.set(0, 64, 0, "minecraft:gold_block")
```

**事件与计时**：注册了监听器的脚本会一直运行，直到你停止它。`MC.sleep` 只暂停当前脚本，不会让游戏卡住。

```lua
MC.Event.onBlockPlaced(function(event)
    print(event.playerName, "放置了", event.blockId, "位置", event.x, event.y, event.z)
end)

MC.Event.onInterval(1000, function(event)
    print("过了一秒", event.elapsedMs)
end)

MC.Event.onScriptStopped(function()
    MC.Chat.broadcast({text = "脚本已停止", color = "#FF5555"})
end)
```

**模块**：把代码拆分到多个文件，用 `require` 加载；模块返回一个表。

```lua
-- lib/greeting.lua
local M = {}
function M.greet(name) return "你好，" .. name .. "！" end
return M

-- main.lua
local greeting = require("lib.greeting")
print(greeting.greet("Steve"))
```

**运行**：在 IDE 中点击**运行**或按 **Ctrl+Enter**，也可以使用命令（控制台和命令方块同样可用）：

| 命令 | 作用 |
| --- | --- |
| `/lua run <脚本>` | 运行脚本 |
| `/lua testrun <脚本>` | 以调试模式运行（断点、变量） |
| `/lua stop` | 停止正在运行的脚本并注销它的监听器 |
| `/lua list` | 列出脚本 |

### IDE

- **窗口**：所有工具都是可停靠窗口。拖动标题即可停靠、拆分或合并为标签页，布局会被记住。**UI与窗口 → 视图**可以打开任意工具。
- **命令面板（Ctrl+Shift+P）**：按名称找到所有功能。**Ctrl+P** 打开文件，**Ctrl+Shift+O** 跳转到函数，输入 **:** 加数字跳转到行。
- **常用快捷键：**

| 按键 | 作用 |
| --- | --- |
| Ctrl+S · Ctrl+Enter | 保存 · 运行 / 停止 |
| Ctrl+F · Ctrl+H · Ctrl+Shift+F | 查找 · 替换 · 在文件中查找 |
| Ctrl+B 或 Ctrl+点击 · Ctrl+Shift+B · F2 | 转到定义 · 查找引用 · 重命名 |
| Ctrl+D · Alt+点击 | 选中下一处 · 添加光标 |
| Ctrl+I | 在光标处插入 ID 或数据组件 |
| Tab | 接受补全 / 展开代码片段（`fn`、`for`、`if`、`pcall` 等） |
| Ctrl+/ | 切换注释 |
| Ctrl+` | 终端 |
| F12 | 调试设置与快捷键 |

- **个性化**：配色、字体、光标、背景图、语法颜色，以及把菜单并入窗口标题栏的选项。

### 调试

1. 点击行号设置断点；右键可设置**条件**、**命中次数**或**日志消息**（日志点只输出、不暂停）。
2. 勾选菜单栏中的**调试**（或使用 `/lua testrun`）后运行。
3. 脚本暂停后：单步进入 / 越过 / 跳出、运行到光标、查看调用栈和变量、添加**监视**、鼠标悬停变量查看值。

调试窗口还可以在**错误**处暂停（仅未捕获的错误，或所有错误），停在抛出错误的那一行，并列出脚本的**协程**。脚本暂停时可以选择冻结世界。

### 数据包与资源包

**文件 → 数据包 / 资源包**打开对应的工作区。JSON 文件有结构化编辑器，模板和示例来自正在运行的游戏；PNG 纹理用带图层的绘图工具打开；音效和结构可以预览。保存后在**工具**中重载数据包或资源包。

### 终端

**Ctrl+`** 打开终端标签页，运行本机 shell，支持颜色、鼠标和全屏程序。**服务器终端**（服务端机器上的 shell）仅供服务器所有者使用，并且需要在 `config/mc_lua_ide/terminal.properties` 中开启后才可用。

### 多人协作与安全

- 其他管理员打开同一文件时，编辑器会显示提示。会覆盖他人较新版本的保存会被拒绝，你可以对比、覆盖或加载对方的版本。
- **审计日志**记录了谁保存、运行或修改了什么。
- **备份**：脚本有改动时，服务器每 30 分钟为脚本目录创建一次快照，任何快照都可以恢复（恢复本身也可以撤销）。IDE 设置可以导出和导入。

### 配置文件

均位于 `config/mc_lua_ide/`（除特别说明外都在服务端）：

| 文件 | 用途 |
| --- | --- |
| `scripts/` | Lua 脚本 |
| `lua-limits.properties` | 执行限制以及允许哪些高风险库（也可在 IDE 中修改） |
| `terminal.properties` | 服务器终端（默认关闭） |
| `snapshots.properties` · `snapshots/` | 自动脚本快照 |
| `audit.log` | 审计日志 |
| `extensions/` | 自己的代码片段与命令清单（客户端） |
| `personalization.properties`、`sessions/` | IDE 外观与各服务器的布局（客户端） |

## 文档

- [游戏内手册](docs/manual/zh_cn.md)：完整的 Lua API 与示例（[English](docs/manual/en_us.md) · [日本語](docs/manual/ja_jp.md)），也可以在 IDE 中阅读。
- [IDE 使用指南](docs/guide/ide-guide.zh_cn.md)：所有 IDE 功能的详细说明。
- [扩展 IDE](docs/extensions/overview.zh_cn.md)：清单与供其他模组使用的 Java API。
- [更新日志](CHANGELOG.md)

## 从源码构建

```bash
./gradlew build
```

JAR 输出到 `build/libs/`。`build` 也会运行冒烟测试（`PATH` 中需要有 Git）。

## 状态

已在 Windows 上测试。macOS 与 Linux 上的本地终端和标题栏合并功能尚未在真实机器上测试。欢迎在 [Issues](https://github.com/MiaoluoYuanlina/MC_Lua_IDE/issues) 中反馈问题和建议。

## 许可

[MIT](LICENSE)。内置的第三方库遵循各自的许可：[LuaJ](https://github.com/luaj/luaj)（MIT）、[imgui-java](https://github.com/SpaiR/imgui-java) 与 [Dear ImGui](https://github.com/ocornut/imgui)（MIT）、[JNA](https://github.com/java-native-access/jna)（Apache 2.0 / LGPL 2.1）。
