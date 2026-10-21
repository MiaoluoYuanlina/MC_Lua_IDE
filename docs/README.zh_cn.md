# MC Lua IDE 文档

[English](README.md) · 简体中文

项目的全部文档都在这个目录中。

| 分类 | 适合谁 | English | 简体中文 |
| --- | --- | --- | --- |
| **IDE 使用指南** | 使用 IDE：窗口、编辑器工具、调试、终端 | [ide-guide.md](guide/ide-guide.md) | [ide-guide.zh_cn.md](guide/ide-guide.zh_cn.md) |
| **游戏内手册** | 需要完整的 Lua/API 手册（IDE 内也能查看） | [en_us.md](manual/en_us.md) | [zh_cn.md](manual/zh_cn.md) · [ja_jp.md](manual/ja_jp.md)（日本語） |
| **扩展 IDE** | 想添加代码片段、命令、补全、窗口或 Lua API | [overview.md](extensions/overview.md) | [overview.zh_cn.md](extensions/overview.zh_cn.md) |
| ↳ 扩展清单（JSON，无需 Java） | | [manifests.md](extensions/manifests.md) | [manifests.zh_cn.md](extensions/manifests.zh_cn.md) |
| ↳ 编辑器 API（命令、片段、补全、shell） | | [editor-api.md](extensions/editor-api.md) | [editor-api.zh_cn.md](extensions/editor-api.zh_cn.md) |
| ↳ Lua、文档、注册表与窗口 API | | [third-party-api.md](extensions/third-party-api.md) | [third-party-api.zh_cn.md](extensions/third-party-api.zh_cn.md) |
| **参考** | 开发 IDE 本身：本地化规则 | [localization.md](reference/localization.md) | |
| **示例** | 想看完整示例 | [hello-pack.json](extensions/examples/hello-pack.json)（清单）· `examples/third_party_windows/`（窗口模组，构建时会编译） | [bed-wars-shop.zh_cn.md](examples/bed-wars-shop.zh_cn.md)（商店模块） |

## 目录结构

```
docs/
  README.md, README.zh_cn.md      本索引
  guide/                          使用指南
  manual/                         游戏内手册（zh_cn、en_us、ja_jp）
  extensions/                     扩展文档与示例清单
  reference/                      开发者说明
  examples/                       示例脚本的文档
```

## 维护说明

- `docs/manual/*.md` 是游戏内手册的**源文件**，`processResources` 会把它复制到 JAR 内的 `assets/mc_lua_ide/manual/`。不要在 `src/` 下再放第二份。
- 较大的新功能应同时更新三种语言的手册（见 [localization.md](reference/localization.md)）。
- `docs/extensions/examples/hello-pack.json` 会被 `extensionApiSmoke` 加载，所以文档中的清单格式不会和加载器脱节。
- 源码注释和 javadoc 会指向这些文件（例如 `McLuaIdeEditorApi` → `docs/extensions/manifests.md`）；移动文件时请同时更新。
