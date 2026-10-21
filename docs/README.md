# MC Lua IDE documentation

English · [简体中文](README.zh_cn.md)

All project documentation lives in this directory.

| Section | Read it if you… | English | 简体中文 |
| --- | --- | --- | --- |
| **IDE guide** | use the IDE: windows, editor tools, debugging, terminal | [ide-guide.md](guide/ide-guide.md) | [ide-guide.zh_cn.md](guide/ide-guide.zh_cn.md) |
| **In-game manual** | want the full Lua/API manual (also shown inside the IDE) | [en_us.md](manual/en_us.md) | [zh_cn.md](manual/zh_cn.md) · [ja_jp.md](manual/ja_jp.md) (日本語) |
| **Extending the IDE** | want to add snippets, commands, completion, windows or Lua APIs | [overview.md](extensions/overview.md) | [overview.zh_cn.md](extensions/overview.zh_cn.md) |
| ↳ Extension manifests (JSON, no Java) | | [manifests.md](extensions/manifests.md) | [manifests.zh_cn.md](extensions/manifests.zh_cn.md) |
| ↳ Editor API (commands, snippets, completion, shells) | | [editor-api.md](extensions/editor-api.md) | [editor-api.zh_cn.md](extensions/editor-api.zh_cn.md) |
| ↳ Lua, docs, registry and window APIs | | [third-party-api.md](extensions/third-party-api.md) | [third-party-api.zh_cn.md](extensions/third-party-api.zh_cn.md) |
| **Reference** | work on the IDE itself: localization rules | [localization.md](reference/localization.md) | |
| **Examples** | want worked examples | [hello-pack.json](extensions/examples/hello-pack.json) (manifest) · `examples/third_party_windows/` (window mod, compiled by the build) | [bed-wars-shop.zh_cn.md](examples/bed-wars-shop.zh_cn.md) (shop module) |

## Layout

```
docs/
  README.md, README.zh_cn.md      this index
  guide/                          user guide
  manual/                         in-game manual (zh_cn, en_us, ja_jp)
  extensions/                     extension docs and example manifest
  reference/                      contributor notes
  examples/                       documentation for the example scripts
```

## Notes for maintainers

- `docs/manual/*.md` is the **source** of the in-game manual. `processResources` copies it to `assets/mc_lua_ide/manual/` inside the jar. Do not add a second copy under `src/`.
- New substantive features should update all three manual languages (see [localization.md](reference/localization.md)).
- `docs/extensions/examples/hello-pack.json` is loaded by `extensionApiSmoke`, so the documented manifest format cannot drift from the loader.
- Code comments and javadoc point to these files (for example `McLuaIdeEditorApi` → `docs/extensions/manifests.md`); update both when moving a file.
