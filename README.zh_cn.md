<p align="center"><img src="src/main/resources/assets/mc_lua_ide/icon.png" width="128" alt="MC Lua IDE 图标"></p>

<h1 align="center">MC Lua IDE</h1>

<p align="center">在 Minecraft 服务端运行 Lua 脚本，并为管理员提供游戏内的完整 IDE。</p>

<p align="center"><a href="README.md">English</a> · 简体中文</p>

---

MC Lua IDE 是一个适用于 **Minecraft 26.1.2** 的 Fabric 模组。服务器管理员可以在游戏运行时编写、运行和调试服务端 Lua 脚本。脚本在权威服务端上运行并受可配置的限制约束；普通玩家无需安装任何东西。

```lua
print("来自服务器的问候！")
for _, player in ipairs(MC.Player.list()) do
    print(player.name, player.uuid)
end
```

## 功能

- **游戏内 IDE**（按 **U** 打开）：可停靠窗口、标签页、配色方案，支持中文 / 英文 / 日文界面。
- **编辑器**：语法高亮与诊断，API / 变量 / 资源 ID 补全，查找与替换（正则），转到定义、查找引用、重命名（可跨文件）、大纲、代码片段、多光标、小地图、文件对比、在文件中查找、命令面板。
- **调试器**：条件断点、命中次数、日志点、异常断点、调用栈、监视、悬停查看值、协程列表、单步进入 / 越过 / 跳出、运行到光标，可选冻结世界。
- **Minecraft API**：玩家、物品、方块、事件、计时、聊天与 JSON 文本、虚拟菜单、音效等，IDE 内置 API 文档。
- **数据包与资源包**：与脚本一起编辑，提供 JSON 辅助编辑、PNG 绘图、音效与结构预览以及重载按钮。
- **多人协作与安全**：显示谁在编辑、带冲突检查的保存、审计日志、自动脚本快照与恢复、设置导出/导入，以及拆分编辑器。
- **内置终端**：本地 shell，以及可选的服务器 shell（默认关闭，仅限服务器所有者）。
- **集成**：Git 历史、面向 AI 工具的 MCP（默认关闭），以及供其他模组添加 Lua 函数、IDE 窗口、命令、代码片段和补全的 API。

## 运行要求

| | |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.3 或更新 |
| Fabric API | 0.155.2 或更新 |
| Java | 25 |

安装在**服务端**（或单人游戏）以及使用 IDE 的**管理员客户端**上。使用 IDE 需要 OP 等级 4（或单人世界房主）。安装客户端模组本身不会授予任何权限。

## 快速开始

1. 将 `mc_lua_ide-<版本>.jar` 和 Fabric API 放入服务端与管理员客户端的 `mods` 目录。
2. 进入世界后按 **U**。
3. **文件 → Lua 目录** → 新建脚本，**Ctrl+S** 保存，**Ctrl+Enter** 运行。

命令：`/lua run <脚本>`、`/lua testrun <脚本>`（启用调试）、`/lua stop`。

## 文档

全部文档位于 [`docs/`](docs/README.zh_cn.md)：

- [IDE 使用指南](docs/guide/ide-guide.zh_cn.md)：窗口、编辑器、调试、终端。
- 游戏内手册（[简体中文](docs/manual/zh_cn.md) · [English](docs/manual/en_us.md) · [日本語](docs/manual/ja_jp.md)）：完整的 Lua API，IDE 内也可查看。
- [扩展 IDE](docs/extensions/overview.zh_cn.md)：JSON 清单以及供其他模组使用的 Java API。

## 构建

```bash
./gradlew build
```

JAR 输出到 `build/libs/`。`build` 也会运行冒烟测试；Git 工作区测试需要 `PATH` 中有 Git。

## 状态与已知限制

- 已在 Windows 上测试。**macOS 与 Linux** 上的本地终端和标题栏合并功能尚未在真实机器上测试。
- 代码导航基于词法：会跨文件追踪全局变量和 require 的模块，但不追踪通过表或函数调用传递的值。
- 问题反馈与建议：[GitHub Issues](https://github.com/MiaoluoYuanlina/MC_Lua_IDE/issues)。

## 许可

[MIT](LICENSE)。内置的第三方库遵循各自的许可：[LuaJ](https://github.com/luaj/luaj)（MIT）、[imgui-java](https://github.com/SpaiR/imgui-java) 与 [Dear ImGui](https://github.com/ocornut/imgui)（MIT）、[JNA](https://github.com/java-native-access/jna)（Apache 2.0 / LGPL 2.1）。
