# 独立 ImGui IDE 界面

这是独立的 Dear ImGui 前端，不是 Axiom 插件，不要求安装 Axiom。窗口、按钮、输入、菜单、目录树、JSON 树、颜色面板和代码画布均使用新版原生绘制；复用现有控制器的数据与动作，但不绘制 Minecraft 的按钮、输入框或整页工具截图。

## 打开和切换

- 正常进入有权限的模组服务器，按 U 打开新版 IDE。权限检查、脚本执行仍由服务端负责。
- 新版不再提供切换旧版入口。未保存文件关闭前会先询问保存。
- 渲染器失败会记录日志并保留恢复草稿，不会自动显示旧样式；不会改变服务器权限、安全限制或强制安装设置。

## 已接入

- 独立窗口管理：拖动标题停靠、拆分、标签页合并和分隔线调整，窗口可见性/布局保存。
- 三个完整独立目录：Lua、datapack、resourcepacks。保留目录树、命名空间、快捷目录/文件模板、用途说明、重命名、右键子菜单，以及原有的 PNG/OGG 快速导入、资源管理器/终端入口。
- 主编辑区继续使用经过修复的完整代码控制器：Lua/JSON/mcfunction 高亮、行号、参数和 require 模块补全、折叠、断点、右键菜单、缩进、撤销重做、搜索、长行横向滚动和 AI/MCP 标记。
- 复用现有 Lua/JSON 诊断：延迟分析、过时结果丢弃、行号标记和问题窗口。
- API 分类目录、语言相关搜索、文档说明和示例插入/复制；API 导入仍读取现有第三方注册信息。
- API/变量/require 模块和函数参数补全，ID 插入按参数类型选择目录。
- 服务端运行/停止、普通运行与调试模式、mcfunction 运行、可复制的实时日志及变量树。
- 每个连接范围内的草稿恢复、保存前确认；MCP 修改和未保存本地版本有冲突时不静默覆盖。
- 数据包 JSON 编辑器：动态类别/字段、树形编辑、对应翻译、选项/ID 搜索及回写主编辑器；数据包文件中 Ctrl+I 继续按当前位置识别。
- 资源包 JSON 编辑器与图片工具分开停靠；PNG 画布仍在主文件标签，保留铅笔/画笔、大小、压感、橡皮/填充/取色、图层、撤销和分辨率设置。
- PNG、OGG（进度拖动/详情）和 NBT（结构三维旋转/缩放、实际方块材质）使用原有完整预览逻辑。
- 数据标签、Minecraft 文本 JSON、Minecraft UI 可视化编辑器、取色、完整 Markdown 手册和 API 文档均可独立停靠。
- 工具栏：格式化、资源包/数据包重载、服务器资源包上传、API Markdown 导出、服务器配置、恢复、折叠、MCP 和 AI；Git 与个性化不再跳转全屏兼容 IDE。
- F12 调试页、断点控制窗口、可配置窗口快捷键及第三方自定义窗口；第三方窗口的 Java open/close 与内容插入接口兼容新版。

常用快捷键：Ctrl+S 保存、Ctrl+N 新建、Ctrl+I 插入、Tab 接受补全、Ctrl+/ 注释、Ctrl+Enter 运行/停止。代码编辑器提供 Ctrl+Z、Ctrl+Shift+Z、Ctrl+F 等编辑快捷键；F5 等调试按键按 F12 中的预设/自定义配置使用。

## 窗口与兼容方式

「文件」可分别打开三种目录；「UI与窗口 → 视图」可打开各编辑器和工具。拖动 ImGui 标题可停靠、合并标签和移动，布局与打开的文件会保存。个性化中的编辑器颜色、字体、背景图、光标等沿用同一份设置；菜单提供字号和界面缩放。固定布局时，是否允许调整区域大小仍读取个性化选项。

只有游戏内容素材（物品/方块/实体/皮肤图标、结构预览、实际 Minecraft 文本组件预览）保留游戏渲染。它们不是按钮或输入控件；所有交互、标题与设置均为新版。服务器配置和 F12 设置也使用新版控件，包括从 Mod Menu 或游戏内直接打开的页面。逻辑 tick 不随 UI 帧率加速。

第三方窗口请实现 `McLuaIdeWindowApi.NativeContent.renderImGui()`；扩展接口版本为 2。旧 `Content.render(GuiGraphicsExtractor,...)` 仍可使用：它被绘制到离屏纹理后显示在窗口内，鼠标与键盘事件会换算回旧接口，同时发出警告（日志、聊天提示一次、窗口内橙色横幅）。兼容层只是过渡，请更新为 `NativeContent`。详见 [第三方 API 手册](THIRD_PARTY_API.zh_cn.md)。

新版布局和会话元数据独立保存，不覆盖历史旧版布局。较窄的 JSON 窗口会将类型导航改为下拉选择，控件不再整体缩成旧界面截图；没有测试所有操作系统、显卡和渲染模组组合。

## 实现与依赖

- 固定使用 `io.github.spair:imgui-java-binding:1.92.7.1`、同版本 LWJGL 后端和 Windows/Linux/macOS 原生库。
- 使用 Minecraft 已有的 LWJGL；不打包另一个 GLFW/OpenGL 运行时，不创建额外桌面窗口。
- ImGui 有独立 context，在 Minecraft OpenGL 帧末、交换缓冲前绘制；工具视口使用对应尺寸的 RenderTarget，在 finally 中恢复 GUI 状态、窗口渲染尺寸、主目标、投影及帧缓冲绑定；ImGui 后端负责其余 OpenGL 状态恢复。
- 不替换 Minecraft 的键盘/鼠标回调，由 Screen 转发输入；退出时清理按键状态。
- 界面字体读取本机字体；不分发 Windows/macOS 字体。Linux 推荐安装 Noto Sans CJK 和 DejaVu Sans Mono；缺少 CJK 字体时可能显示缺字。
- 服务端/API/网络协议未因 UI 迁移而改变，普通玩家仍不需要安装用于管理员 IDE 的客户端功能。

上游项目：[imgui-java](https://github.com/SpaiR/imgui-java)、[Dear ImGui](https://github.com/ocornut/imgui)、[ImGuiColorTextEdit](https://github.com/goossens/ImGuiColorTextEdit)。这些组件使用 MIT 许可，许可证随其原始依赖分发。没有复制 Axiom 的界面实现或素材。

## 验证

`./gradlew.bat runClient -PimGuiProbe --offline` 在 `build/imgui-probe` 启动隔离测试客户端，不进入已有存档。测试逐个实际绘制完整窗口清单，检查中文输入、撤销/重做、Unicode 替换、JSON 回写、资源包 JSON/PNG 保存、第三方物品图标绘制及输入/生命周期、未保存关闭拦截和恢复，并生成 `imgui-datapack.png`、`imgui-resourcepack.png`。资源包测试仅在此隔离目录内创建测试模板。

可额外传入 `-PimGuiAxiomJar=绝对路径`，只把指定 Axiom JAR 复制到 `build/imgui-axiom-probe/mods` 进行同装测试，不修改用户安装目录。
