# MC Lua IDE 使用手册

> 管理员在 Minecraft 客户端内编辑脚本，由安装本 Mod 的服务端保存并安全执行。

> 手册里的每张 Lua 示例卡片右上角都有“复制”按钮。点击后可直接在编辑器中按 `Ctrl+V` 使用。

> 手册正文可以用鼠标拖动选择；按 `Ctrl+C` 复制选区，按 `Ctrl+A` 选择全文，双击可选择词语。

## 快速开始

1. 进入安装了 MC Lua IDE 的服务器后，使用服务器所有者账号或 OP 等级 4 的账号按 `U`。
2. 输入脚本名称，点击“新建”。
3. 在编辑器内编写 Lua，顶部状态会实时检查语法。
4. 按 `Ctrl+S` 保存，按 `Ctrl+Enter` 运行。
5. 点击顶部常驻的绿色“▶ 运行”按钮开始调试；运行后按钮会变为红色“■ 停止”，可随时中断脚本。

## 脚本目录

- 点击 `名称.lua` 打开脚本。
- 点击脚本右侧的 `×` 删除脚本。
- 名称可使用英文字母、数字、下划线和连字符，最长 48 个字符。
- 文件保存在服务端 `config/mc_lua_ide/scripts`；客户端不再保存服务端脚本副本。

目录页底部的“设置”会打开设置首页。“Lua 限制”用于编辑服务端执行策略；“服务器接入”用于决定普通玩家是否必须安装客户端模组。限制按执行资源、事件、MC API、文件沙箱、Lua 语言能力和核心安全边界分页显示；鼠标悬停具体选项可查看说明。这里调整的是一份服务端全局配置，不按管理员账号分别保存：任意管理员保存后，其他管理员重新进入或刷新都会读取相同设置。只有服务器所有者或 OP 等级 4 可以读取和保存，配置位于服务端 `config/mc_lua_ide/lua-limits.properties`。

## 多人服务器安装与权限

- 服务端必须安装 MC Lua IDE、Fabric Loader 和匹配版本的 Fabric API；Lua 运行器、脚本文件、事件监听和全部 `MC.*` API 都只在服务端运行。
- 需要使用 IDE 的管理员客户端必须安装同版本 MC Lua IDE。客户端只负责编辑界面、语法提示和发送受限请求。
- 默认情况下普通玩家客户端不需要安装本 Mod，也可以正常连接服务器。
- 管理员可在“设置 → 服务器接入”开启“要求玩家安装客户端模组”。开启后，服务器会在玩家进入世界前发起登录握手；未安装 MC Lua IDE 或协议版本不一致的客户端会收到中文原因并被拒绝登录。修改只影响之后的新连接，不会踢出现有在线玩家。
- 只有单人世界所有者或拥有 OP 等级 4（`COMMANDS_OWNER`）的在线玩家可以列出、读取、保存、删除、运行和停止脚本。
- 权限在服务端对每一次请求重新校验。修改客户端、伪造数据包或直接发送网络请求都不能绕过权限检查。
- 强制安装关闭时，客户端与服务端协议版本不一致、服务端没有安装本 Mod 或权限不足会使 U 键无法打开 IDE，并在聊天栏显示具体原因；强制安装开启时，版本检查会提前到登录阶段。
- 独立服务器通常使用 `op <玩家名>` 授权，并保持 `server.properties` 中 `op-permission-level=4`。

编辑脚本时可以点击顶部最左侧的“文件 → 目录”，在不返回编辑器的情况下打开脚本管理窗口。“文件”二级菜单还包含“保存”和“运行/停止”；顶部功能栏同时提供一个常驻的独立“运行/停止”按钮，方便反复调试。

- 顶部输入名称并点击“新建”，可以创建新的 `.lua` 文件
- 每一行都提供“读取”和“移除”；当前编辑中的脚本会以橙色显示
- 支持 `↑`、`↓` 选择，按 `Enter` 读取所选脚本，鼠标滚轮浏览全部文件
- 标题栏可拖动，窗口四条边和四角都可以调整大小
- 移除当前正在编辑的脚本后，如果再次保存，文件会被重新创建

## 窗口吸附与布局

文件、使用手册、插入器、API 文档、物品堆叠组件编辑器和运行日志都可以作为浮动窗口使用，也可以像桌面 IDE 一样停靠到工作区。

- 拖动窗口标题栏到屏幕左侧、右侧或底部边缘；出现蓝色吸附预览后松开鼠标即可停靠
- 停靠面板会占用对应区域，代码编辑器会自动缩小，不会被面板覆盖
- 拖动停靠面板与编辑器之间的蓝色分隔线，可以连续调整左右区域宽度或底部区域高度
- 从停靠面板的标题栏向工作区中间拖动，可以恢复为原来的浮动尺寸和位置
- 同一侧可以停靠多个窗口；它们会组成标签组，并在该区域顶部显示“文件 / 手册 / 插入 / API / 组件 / 文本 / 日志”等标签
- 点击区域顶部的标签即可切换窗口；未选中的窗口会保留在该吸附区域，不会被挤回浮动状态
- 吸附或取消吸附时，窗口的位置、宽高与代码编辑区会连续过渡；调整停靠分隔线时仍保持跟手
- 标签切换带有滑动指示线和方向性内容揭示动画；窗口关闭后会先播放退出动画，再收回对应编辑区域
- 拖动窗口靠近可吸附边缘时会显示呼吸式预览；已有窗口的边缘会提示加入该区域的标签组
- 点击顶部“窗口 → 固定当前布局”可锁定所有已停靠面板和分隔线；未停靠的浮动窗口仍能自由移动
- 锁定布局后，浮动窗口不会意外吸附；再次关闭“固定当前布局”即可继续修改停靠结构
- 已停靠的面板位于代码编辑器的上一层，但仍低于浮动窗口、搜索框和菜单；浮动窗口不会再被已固定面板遮挡
- 停靠位置、当前标签、区域尺寸、锁定状态以及每个窗口的显示/隐藏状态都会自动保存，退出并重新启动游戏后也会恢复
- 布局保存在客户端配置目录的 `mc_lua_ide/ide-layout.properties`；不影响服务端脚本或其他管理员的客户端布局
- “窗口 → 视图”三级菜单集中提供“使用手册”“插入”“API 文档”“数据组件编辑器”“文本 JSON 编辑器”和“运行日志”；日志按钮即使脚本没有运行也可以打开空日志窗口

## ID 插入器

点击“窗口 → 视图 → 插入”，或按 `Ctrl+I`，可以打开可拖动、可缩放的运行时 ID 插入窗口。

“使用手册”和“插入”窗口可以同时打开。两个窗口重叠时，点击可见窗口区域或“窗口 → 视图”中的对应菜单项会把它切换到前面；再次点击已经位于前面的窗口菜单项才会关闭窗口。两个窗口仍可分别拖动和缩放。

- “物品 ID”：读取完整物品注册表
- “方块 ID”：读取完整方块注册表
- “实体 ID”：读取完整实体类型注册表
- “玩家 UUID”：读取当前服务器的在线玩家列表
- 输入文字可按完整 ID、路径、命名空间、本地化名称或中文首拼搜索；例如输入 `mlfk` 可以找到“命令方块”
- 点击“命名空间”打开独立选择器；每项会显示命名空间、对应模组名称和当前分类中的条目数量
- 命名空间选择器可按 Mod ID、模组显示名、中文或拼音首字母搜索；支持滚轮、`↑`、`↓`、`PageUp`、`PageDown`、`Home`、`End` 和 `Enter`
- 插入器打开时可按 `Ctrl+N` 快速打开或关闭命名空间选择器；按 `Esc` 会先关闭选择器，再关闭插入窗口
- 点击标题栏的 `↻` 重新读取当前分类
- 每个结果左侧会显示预览：物品模型、方块模型、实体本体，或包含帽子/外层皮肤的玩家头像
- 鼠标滚轮浏览结果，单击条目或用 `↑`、`↓` 选择；按 `Enter` 插入并自动关闭窗口
- 按 `←`、`→` 可在物品、方块、实体和玩家分类之间循环切换
- 拖动标题栏移动窗口，拖动边框改变窗口大小

物品、方块和实体数据直接来自游戏运行时注册表，因此会自动包含其他 Mod 注册的 ID。插入器会分析光标所在的函数和参数序号，例如光标位于 `MC.Block.set` 的第 4 个参数时自动打开“方块 ID”，位于 `MC.Item.give` 的第 1 个参数时自动打开“玩家 UUID”、第 2 个参数时自动打开“物品 ID”。插入器通常会自动添加双引号；如果光标已经位于 Lua 的单引号或双引号字符串内，则只插入原始 ID，避免产生重复引号。

## 数据组件编辑器

点击“窗口 → 视图 → 数据组件编辑器”打开可视化编辑器。它与其他工具窗口一样可以拖动、调整大小、吸附到左侧/右侧/底部，并会保存显示状态和停靠布局。

- 当代码光标位于 `MC.Item.give`、`MC.Item.setSlot`、`MC.Container.setSlot` 等 API 的 `components` 参数时，按 `Ctrl+I` 会优先打开此编辑器，并选中当前完整参数；点击“插入组件”会精确替换该参数。即使打开窗口后移动过代码光标，只要源码未修改，仍会替换最初识别的位置
- 顶部可在“物品 / 方块 / 实体”间切换。选择方块时编辑对应的方块物品；选择实体时会由服务端创建该实体类型并读取实体本身的数据组件，不再把实体错误地当成刷怪蛋
- 左侧搜索支持资源 ID、中文名和拼音首字母；右侧组件搜索同样支持中文和首拼
- 组件数据由服务端当前注册表读取，包含其他 Mod 注册且拥有持久化 Codec 的数据组件。标记为“默认”的组件来自所选物品的默认值，“适用”是编辑器识别出的常用组件，“高级”组件由所属 Mod 决定是否会对该物品产生效果
- 点击组件左侧复选框启用或停用覆盖值。无参数组件使用开关；布尔、整数、数字、文本和资源 ID 使用对应的可视输入
- 复杂组件使用可递归展开的树形编辑器：对象和数组可以一层层展开或折叠，选中节点后可新增子项、修改键和值、切换字符串/整数/小数/布尔/对象/数组/空值类型，或删除节点。双击叶子节点可直接修改，方向键可浏览树，`Insert` 新增、`Delete` 删除
- 编辑 `minecraft:enchantments` 时，“＋附魔”会预填 `minecraft:sharpness` 和等级 `1`；附魔 ID、等级均可修改，也可以填写其他 Mod 注册的附魔 ID。最终可用性以当前服务器注册表和组件 Codec 校验结果为准
- 树节点输入框会结合组件类型、键名和完整树路径识别资源 ID。编辑器先枚举当前服务器的全部注册表类型，再按需分页读取所选类型中的全部 ID；例如 `minecraft:timeline` 中的 `minecraft:day` 是时间轴 ID（`minecraft:world_clock` 则是世界时钟定义）、`minecraft:item` 中的 `minecraft:apple` 是物品 ID、`minecraft:mob_effect` 中的 `minecraft:dolphins_grace` 是状态效果 ID。其他 Mod 的注册表和命名空间也会自动出现
- 自动识别不到时点击节点窗口里的“ID类型”，可按完整 ID、注册表名称、命名空间、中文或拼音首字母筛选任意注册表。条目列表同样支持中文和首拼搜索，常用条目会显示用途注释
- 其他 Mod 可以通过公开的 `McLuaIdeRegistryApi` 添加注册表类型说明、条目名称/用途翻译，以及自己数据组件字段的 ID 类型提示；详见发行包中的 `docs/THIRD_PARTY_API.zh_cn.md`
- ID 候选支持完整 ID、路径、命名空间、本地化名称、中文和拼音首字母搜索。使用 `↑` / `↓` 循环选择，按 `Tab` 或 `Enter` 填入，也可以直接单击候选；选择后再次按 `Enter` 保存树节点
- 树形编辑器右上角的“源码”仍支持粘贴或修改完整 JSON，确认后会重新生成整棵树，适合复制已有复杂组件或编辑特别大的结构
- “全部组件 / 建议组件”可在完整注册表与当前选项的常用组件间切换
- “插入组件”会把 Lua 组件表插入当前光标；“复制物品堆”复制包含 `id`、`count` 和 `components` 的完整 Lua 表；“复制 JSON”复制精确组件补丁
- 插入或复制之前会把结果发给服务端的 Minecraft 数据组件编解码器校验。字段、类型或资源 ID 无效时不会导出，并显示服务端返回的具体错误

## 文本 JSON 编辑器

点击“窗口 → 视图 → 文本 JSON 编辑器”打开生成器。窗口可以移动、缩放、吸附并自动保存布局。

- 可编辑显示文字、六位十六进制颜色（`#RRGGBB`）和字体资源 ID（例如 `minecraft:default`）
- 提供原版 16 色快速色板，也可以直接输入任意十六进制颜色
- 可独立切换粗体、斜体、下划线、删除线和乱码（obfuscated）效果，并实时预览
- “插入 JSON 字符串”会生成已正确转义的 Lua 字符串，可直接放进 `MC.Chat.message`、`MC.Title.show`、`MC.Team.prefix` 等参数
- “复制 JSON”复制原始 Minecraft 文本组件 JSON，便于在命令、配置或其他 Mod 中复用

```lua
local styled = "{\"text\":\"任务完成\",\"color\":\"#55FFFF\",\"font\":\"minecraft:default\",\"bold\":true,\"underlined\":true}"
MC.Title.show(uuid, styled)
```

## API 文档窗口

点击“窗口 → 视图 → API 文档”打开完整接口参考。文档直接读取 IDE 当前的 API 元数据，会列出全部可用 API。

- 顶部筛选框支持 API 名称、英文和中文说明；筛选命中的文字会使用黄色背景标出
- 筛选框右侧的“首拼 开/关”控制拼音首字母匹配；开启后例如 `wj` 可筛选并高亮“玩家”，`ml` 可筛选并高亮“命令”
- 筛选框支持鼠标定位、方向键移动光标、`Shift` 选择、`Ctrl+A/C/X/V`、退格和 Delete
- 左侧是 `Lua API → 大类 → 命名空间 → 方法` 多级菜单。最上方独立的“客户端 Mod 专用 API”收纳所有要求目标玩家安装本 Mod、且服务器开启强制客户端模式才能生效的接口；“服务端 / 原版 API”收纳内置 `MC.*` 接口；其他模组还可以创建 `TestMod.*` 这样的独立顶级大类
- 点击根目录、大类或命名空间可以展开、收起，点击方法立即跳转到详情；客户端专用大类及详情会使用醒目的黄色警告
- 右侧依次显示函数签名、作用、返回值、每个参数的含义和使用提示
- 每个接口都有可运行的 Lua 模板；点击“复制”写入剪贴板，点击“插入”直接写到当前代码光标位置
- 事件、玩家、方块和世界等接口提供包含字段读取、遍历或回调处理的详细模板
- 左右区域可以分别滚动；标题栏可以移动窗口，边框可以调整大小
- 按住 `Ctrl` 使用 `↑`、`↓` 可以快速跳到上一个或下一个 API

## 编辑快捷键

- `Ctrl+S`：保存当前脚本
- `Ctrl+Enter`：运行；正在运行时停止
- `Ctrl+Z`：撤销
- `Ctrl+Y`：重做
- `Ctrl+/`：注释或取消注释当前行/选中的多行
- `Ctrl+V`：智能粘贴；多行 Lua 会按当前位置重新缩进，单行压缩代码会在 `then`、`do`、`else`、`end`、函数参数等结构位置自动拆行
- `Ctrl+I`：光标位于物品堆叠组件参数时打开组件编辑器；其他位置打开或关闭 ID 插入器
- `Ctrl+N`：ID 插入器打开时，打开或关闭可搜索的命名空间选择器
- `Ctrl+F`：打开代码搜索；如果编辑器中已有选区，会自动把选中文字填入搜索栏。搜索框支持正常移动光标、选择、替换、粘贴和删除
- `Ctrl+鼠标左键`：打开鼠标所指向函数的 API 文档，并自动定位到对应方法；支持 `MC.*` 和其他 Mod 注册的自定义根 API
- `Ctrl+F1`：打开代码光标所在或紧邻函数的 API 文档；当前位置不是已注册 API 时仍按 `F1` 打开或关闭使用手册
- 全部匹配结果都会显示背景标记，当前结果会使用更醒目的颜色；搜索保持打开时仍可点击编辑器并自由移动代码光标
- 搜索框获得焦点时按 `Enter` 或 `↓` 查找下一项，按 `↑` 查找上一项，按 `Esc` 关闭；编辑器中重新选中文字后，点击搜索栏左侧的搜索图标可快速替换查找内容
- `Enter`：根据当前 Lua 块结构生成下一行缩进
- `↑` / `↓`：切换自动补全候选项
- `Tab`：补全函数并生成括号；光标位于可识别的 ID/UUID 参数时打开对应参数候选；其他位置无候选时插入四个空格
- `F1`：打开或关闭本手册
- `Esc`：关闭候选列表、手册或编辑器

输入 `end`、`else`、`elseif`、`until` 或右括号时，当前行会自动退回正确层级。智能粘贴不会修改 Lua 长字符串内部的空格或制表符，避免改变字符串内容。

## 自动补全

输入关键字或标准函数的开头，光标附近会出现候选框。例如输入 `pri`，然后按 `Tab`，会补全为 `print`。输入 `string.`、`table.`、`math.` 可以补全常用库函数。补全 `MC.*` 函数时会自动生成括号并把光标放入参数位置；继续按 `Tab`，如果当前参数需要方块 ID、物品 ID、实体 ID 或玩家 UUID，就会打开已经筛选到正确分类的运行时参数候选窗口。

## 语法高亮

- 紫色：Lua 流程关键字
- 蓝色：`true`、`false`、`nil` 等字面量
- 绿色：单双引号字符串和跨行长字符串
- 金色：整数、小数、十六进制与科学计数数字
- 青色：标准函数、函数调用和对象成员
- 黄色：运算符与函数参数
- 灰色：单行注释和跨行块注释
- 红色：实时检查精确定位到的错误字段或错误参数；不会再把整行或同名的其他字段一起标红

高亮器会跨行跟踪 Lua 词法状态，因此块注释和长字符串不会影响后续正常代码的颜色：

```lua
-- 普通单行注释
--[=[
这里是跨行注释，支持任意内容
]=]

local description = [[
这是跨行字符串
]]

local function formatScore(playerName, score)
    local result = { name = playerName, value = 0x2A }
    print(string.format("%s: %d", result.name, score))
end
```

## 实时语法与参数检查

停止输入约 250 毫秒后会自动编译当前文本，但不会执行它。

- `✓ 检查通过`：Lua 语法正确，并且没有发现可静态判断的 MC API 参数错误
- `● 正在检查…`：正在检查 Lua 语法、API 名称、参数数量和参数类型
- `✗ n 个问题`：下方诊断栏会用中文显示第一个问题的行列位置、原因和剩余问题数量

编辑器会检查 `MC.*` 方法是否存在、大小写是否正确、必填参数是否缺失、参数是否过多，并检查可以静态确定的字符串、数字、整数、布尔值和回调函数类型。字符串常量还会检查 UUID、`namespace:path` 资源 ID、游戏模式、天气、难度、事件名称、网络方法和 URL 等格式。错误只标红对应的调用、参数或符号，自动换行和同一行重复字段不会改变错误位置。

变量或函数返回值的实际类型有时只有运行后才能确定。检查器无法确定时不会猜测或阻止运行，服务端 API 仍会在执行时进行最终校验。

## Minecraft API：`MC`

每个脚本都可以直接使用全局表 `MC`。脚本源码通过受限协议提交给当前 Minecraft 服务端，随后由服务端 Lua 沙箱执行；所有方块、世界、玩家、容器和背包操作都在服务端线程读取或修改，不使用客户端缓存数据。单人世界同样走这套协议，只是客户端与内置服务器位于同一进程。

脚本 API 版本现为 `13`。玩家相关函数不再隐式使用打开 IDE 的玩家，而是显式接收玩家 UUID。命名空间推荐使用首字母大写形式，例如 `MC.Player.health(uuid)`；`MC.player`、`MC.item` 等小写别名也可以使用。资源 ID 省略命名空间时默认使用 `minecraft:`。

输入 `MC.` 或 `MC.Block.` 后，候选项会以 `方法名 - 中文简介` 显示。用 `↑`、`↓` 选择候选，按 `Tab` 会补全可执行的方法名和调用括号，不会把中文说明写进代码。光标进入支持运行时选择的参数后再次按 `Tab`，可直接选择 ID 或 UUID。把鼠标悬浮在候选项或编辑器里的完整 `MC.*` 方法名上，可以按“作用、返回、参数1、参数2……”的固定格式查看详情；无参数方法会显示“参数：无”，受限方法还会显示单人世界限制。`MC.apiVersion()` 返回当前脚本 API 版本。

服务端上的其他 Fabric 模组既可以注册 `MC.<模组命名空间>.*`，也可以创建 `TestMod.*` 这样的独立顶级 API 大类，并为每个方法提供参数类型、用法模板和多语言说明。管理员打开 IDE 时会从服务端同步这些元数据；扩展函数会自动加入 Tab 补全、语法配色、悬浮提示、实时参数检查和“API 文档”，详情中会标出来源模组。扩展模组还可声明函数需要客户端 Mod，使其自动进入“客户端 Mod 专用 API”大类。普通玩家不能读取扩展 API 文档。模组作者接入方式见项目中的 `docs/THIRD_PARTY_API.zh_cn.md`。

### 第三方模组创建独立 API 大类

模组初始化时使用公开 Java 方法 `McLuaIdeApi.rootFunction(ownerModId, rootName, method, function)`。例如下面的注册会创建 `TestMod.playerCount()`，翻译对象中的作用、返回值、详细说明和用法模板会自动写入游戏内 API 文档：

```java
McLuaIdeApi.register(
    McLuaIdeApi.rootFunction("test_mod", "TestMod", "playerCount", (context, args) ->
        context.onServer(server -> LuaValue.valueOf(server.getPlayerCount())))
    .translation("zh_cn", McLuaIdeApi.translation("TestMod API", "获取服务器在线玩家数量")
        .returns("整数；当前在线玩家数量")
        .details("由 test_mod 在服务端读取")
        .usage("print(TestMod.playerCount())")
        .build())
    .build()
);
```

```lua
print("当前在线玩家：", TestMod.playerCount())
```

同一模组可使用相同 `rootName` 继续注册更多方法。每个顶级大类只能属于一个模组，并且不能使用 `MC`、`print`、`math` 等保留名称。需要目标客户端安装 Mod 的方法还应调用 `.requiresClientMod()`；这类方法仍会统一放到“客户端 Mod 专用 API”大类。

### 获取玩家 UUID

- `MC.Player.list()`：返回全部在线服务端玩家，每项包含 `{uuid, name, dimension, x, y, z}`
- `MC.Player.uuid(name)`：按在线玩家名称查找 UUID；未找到返回 `nil`

```lua
local players = MC.Player.list()
if #players == 0 then
    error("当前没有在线玩家")
end

local uuid = players[1].uuid
print("目标玩家", players[1].name, uuid)
```

### 方块：`MC.Block`

- `MC.Block.get(x, y, z, dimension?)`：返回方块信息，并包含可再次传入的 `itemComponents/itemComponentsJson`
- `MC.Block.id(x, y, z, dimension)`：只返回方块资源 ID
- `MC.Block.isAir(x, y, z, dimension)`：当前位置为空气时返回 `true`
- `MC.Block.set(x, y, z, id, dimension?, components?)`：在服务端放置方块并返回是否变化；对箱子、告示牌等方块实体，可把方块物品的数据组件传入方块实体
- `MC.Block.breakAt(x, y, z, drops, dimension)`：在服务端破坏方块；`drops` 默认为 `true`
- `MC.Block.exists(id)`、`MC.Block.name(id)`：检查注册 ID、读取显示名称
- `MC.Block.fill(...)`：使用一个方块填充长方体区域
- `MC.Block.replace(...)`：替换区域内指定类型的方块
- `MC.Block.clone(...)`：复制方块区域到新位置

所有 `dimension` 参数都可省略，默认是 `minecraft:overworld`。读取未加载区块会返回错误，不会在后台意外加载大量区块。

```lua
local uuid = MC.Player.list()[1].uuid
local pos = MC.Player.position(uuid)
local x = math.floor(pos.x)
local y = math.floor(pos.y) - 1
local z = math.floor(pos.z)

local block = MC.Block.get(x, y, z, pos.dimension)
print("脚下方块", block.id, block.name)

MC.Block.set(x + 2, y, z, "gold_block", pos.dimension)
MC.Block.set(x + 3, y, z, "minecraft:chest", pos.dimension, {
    ["minecraft:custom_name"] = {text = "脚本奖励箱", color = "gold"}
})
MC.Block.breakAt(x + 2, y, z, true, pos.dimension)
```

物品堆组件只属于“物品形式”。普通方块状态没有物品堆组件；`MC.Block.set` 仅在目标方块确实拥有方块实体时应用可传递组件，不支持的方块会给出错误。大范围 `fill/replace/clone` 操作的是方块状态，因此不接收物品堆组件；其中容器里的物品请用 `MC.Container.setSlot` 写入。

### 物品：`MC.Item`

- `MC.Item.held(uuid)`：读取指定在线玩家的主手物品；结果包含 `id, name, count, empty, maxStack, damage, maxDamage, components, componentsJson`
- `MC.Item.count(uuid, id, components?)`：统计指定玩家背包中的物品；传组件时只统计 ID 和完整组件都相同的物品
- `MC.Item.give(uuid, id, count?, components?)`：由服务端给予指定玩家带数据组件的物品并返回数量；`count` 默认为 1，范围 1–999。背包已满时，剩余物品会掉落在该玩家身边
- `MC.Item.validate(id, count?, components?)`：只校验并规范化物品堆，不修改玩家或世界；成功时返回完整物品堆信息，组件错误时立即报错
- `MC.Item.exists(id)`、`MC.Item.name(id)`、`MC.Item.maxStack(id)`：读取物品注册和基础信息
- `MC.Item.clear(uuid, id?, count?, components?)`：清除指定物品；传组件时按完整组件精确匹配，省略 ID 时清空背包
- `MC.Item.replaceHeld(uuid, id, count?, components?)`：用带数据组件的物品替换玩家主手物品
- `MC.Item.inventory(uuid)`、`getSlot(uuid, slot)`：读取整个背包或单个槽位
- `MC.Item.setSlot(uuid, slot, itemId?, count?, components?)`、`clearSlot(...)`、`swapSlots(...)`：直接修改背包槽位
- `MC.Item.selectedSlot(uuid)`、`setSelectedSlot(...)`：读取或切换玩家的快捷栏选择
- `MC.Item.equipment(...)`、`setEquipment(uuid, equipmentSlot, itemId?, count?, components?)`：读取或修改主手、副手和装备槽

背包槽位使用从 `0` 开始的索引；快捷栏为 `0–8`。装备槽名称为 `mainhand`、`offhand`、`feet`、`legs`、`chest`、`head`、`body`、`saddle`。`setSlot` 或 `setEquipment` 的 `itemId` 传 `nil` 时会清空对应位置。

所有创建物品堆的方法统一使用 `components` 参数。它可以是 Lua 表，也可以是 JSON 对象字符串；最外层键是完整的数据组件 ID，值采用该组件在 Minecraft 数据编解码器中的结构。服务端会通过当前注册表校验组件，因此也支持其他 Mod 注册的数据组件；未知组件、字段错误或参数类型错误会直接返回中文错误。返回物品堆中的 `components` 是相对于该物品默认值的组件补丁，`componentsJson` 是不会混淆空对象和空数组的精确 JSON 形式，可直接传回任意 `components` 参数。

需要移除物品默认自带的某个持久化组件时，可使用带 `!` 的键，例如 `["!minecraft:某组件"] = {}`。临时运行时组件没有可持久化 Codec，读取时不会放进 `components/componentsJson`，也不能跨脚本重新创建。

```lua
local uuid = MC.Player.list()[1].uuid
local held = MC.Item.held(uuid)
if held.empty then
    print("主手为空")
else
    print("主手", held.name, held.id, held.count)
end

local swordComponents = {
    ["minecraft:custom_name"] = {text = "管理员之剑", color = "gold"},
    ["minecraft:unbreakable"] = {}
}

MC.Item.give(uuid, "minecraft:diamond_sword", 1, swordComponents)
print("钻石总数", MC.Item.count(uuid, "diamond"))

MC.Item.setSlot(uuid, 0, "minecraft:diamond_sword", 1, swordComponents)
MC.Item.setSelectedSlot(uuid, 0)
MC.Item.setEquipment(uuid, "head", "minecraft:diamond_helmet", 1, {
    ["minecraft:custom_name"] = {text = "守卫头盔"}
})

-- 读取后无损复制组件到另一个物品堆
local stack = MC.Item.getSlot(uuid, 0)
MC.Item.give(uuid, stack.id, stack.count, stack.componentsJson)
```

### 玩家：`MC.Player`

- `MC.Player.name(uuid)`：返回指定在线玩家名称
- `MC.Player.position(uuid)`：返回 `{x, y, z, yaw, pitch, dimension}`
- `MC.Player.health(uuid)`：返回 `{current, max}`
- `MC.Player.gameMode(uuid)`：返回 `survival`、`creative`、`adventure` 或 `spectator`
- `MC.Player.teleport(uuid, x, y, z, dimension)`：由服务端传送玩家；省略维度时留在原维度
- `MC.Player.setHealth(uuid, value)`：设置玩家生命值并限制在有效范围
- `MC.Player.setGameMode(uuid, mode)`：设置指定玩家的游戏模式
- `MC.Player.online(uuid)`：检查 UUID 是否在线
- `MC.Player.food(uuid)`、`MC.Player.setFood(...)`：读取或修改饥饿值、饱和度
- `MC.Player.experience(uuid)`、`addExperience(...)`、`setExperience(...)`：控制经验点或经验等级
- `MC.Player.heal(uuid, amount?)`、`damage(...)`、`kill(uuid)`：治疗、伤害或杀死玩家
- `MC.Player.kick(uuid, reason?)`：从服务器断开玩家
- `MC.Player.setSpawn(...)`、`setRotation(...)`：设置个人重生点或视角
- `MC.Player.openBlock(uuid, x, y, z, dimension?)`：打开指定坐标方块的真实原版菜单，例如箱子、熔炉、发射器
- `MC.Player.openCrafting(uuid, title?)`：不依赖方块，直接打开工作台
- `MC.Player.openEnderChest(uuid, title?)`：直接打开该玩家自己的末影箱
- `openAnvil`、`openEnchanting`、`openGrindstone`、`openLoom`、`openCartography`、`openStonecutter`、`openSmithing`：直接打开对应工作站
- `MC.Player.menu(uuid)`、`closeMenu(uuid)`：查询或关闭当前打开的菜单
- `MC.Player.abilities(uuid)`、`setFlying(...)`、`setSpeeds(...)`：读取和修改飞行能力与移动速度
- `MC.Player.absorption(uuid)`、`setAbsorption(...)`：读取和设置金色伤害吸收生命值

```lua
local uuid = MC.Player.list()[1].uuid
local p = MC.Player.position(uuid)
local hp = MC.Player.health(uuid)
print(MC.Player.name(uuid), p.x, p.y, p.z, p.dimension)
print("生命", hp.current, "/", hp.max)

MC.Player.teleport(uuid, p.x, p.y + 5, p.z)
MC.Player.setGameMode(uuid, "creative")

-- 这些菜单由服务端直接打开，不要求附近真的放置对应工作站
MC.Player.openCrafting(uuid, "脚本工作台")
-- MC.Player.openEnderChest(uuid, "个人末影箱")
```

直接工作站函数创建的是临时菜单，关闭后菜单输入槽会按原版规则归还或处理物品。`openBlock` 打开的是真实方块菜单，仍遵守原版有效距离和维度限制，因此目标玩家应位于该方块附近且与方块处于相同维度。

### 方块容器：`MC.Container`

- `MC.Container.info(x, y, z, dimension?)`：读取真实方块实体的类型、槽位数和是否为空
- `MC.Container.list(...)`、`getSlot(...)`：读取箱子、熔炉、漏斗等容器的全部或单个槽位
- `MC.Container.setSlot(x, y, z, slot, itemId?, count?, components?, dimension?)`：写入带数据组件的物品；`itemId` 传 `nil` 时清空该槽位
- `MC.Container.clear(...)`：清空容器并返回移除的物品总数
- `MC.Container.open(uuid, x, y, z, dimension?)`：等同 `MC.Player.openBlock`，让指定玩家打开该方块

容器槽位同样从 `0` 开始。读写只操作指定坐标处的真实方块实体，不会绕过“区块必须已经加载”的安全限制；大型箱子按指定的方块实体处理，使用 `open` 时仍由原版菜单合并相邻箱子。

```lua
local players = MC.Player.list()
if #players == 0 then error("当前没有在线玩家") end
local uuid = players[1].uuid

local x, y, z = 10, 64, 10
local info = MC.Container.info(x, y, z, "minecraft:overworld")
print(info.blockId, info.size, info.empty)

for _, stack in ipairs(MC.Container.list(x, y, z)) do
    if not stack.empty then
        print("槽位", stack.slot, stack.id, stack.count)
    end
end

MC.Container.setSlot(x, y, z, 0, "minecraft:diamond_sword", 1, {
    ["minecraft:custom_name"] = {text = "箱中的剑"}
}, "minecraft:overworld")
MC.Container.open(uuid, x, y, z)
```

### 世界：`MC.World`

- `MC.World.dimensions()`：返回服务端全部已加载维度 ID
- `MC.World.gameTime(dimension)`：返回指定维度的累计游戏刻
- `MC.World.clockTime(dimension)`：返回指定维度关联的时钟刻数
- `MC.World.weather(dimension)`：返回 `{name, raining, thundering, dimension}`
- `MC.World.seed(dimension)`：返回完整种子字符串
- `MC.World.setTime(...)`、`addTime(...)`：设置或增加世界时间
- `MC.World.setWeather(weather, duration?)`：设置晴天、雨天或雷雨
- `MC.World.difficulty()`、`setDifficulty(...)`：读取或设置游戏难度
- `MC.World.setSpawn(...)`：设置世界出生点
- `MC.World.explode(...)`、`lightning(...)`：创建爆炸或闪电
- `MC.World.forceLoad(...)`、`unforceLoad(...)`、`isChunkLoaded(...)`：管理区块加载

这里的 `dimension` 同样可省略，默认使用 `minecraft:overworld`。

```lua
local weather = MC.World.weather()
for _, dimension in ipairs(MC.World.dimensions()) do
    print("已加载维度", dimension)
end
print("游戏刻", MC.World.gameTime())
print("天气", weather.name)
print("种子", MC.World.seed())
```

### 聊天与命令

- `MC.Chat.message(uuid, textOrJson)`：由服务端向指定玩家发送系统消息
- `MC.Chat.broadcast(textOrJson)`：由服务端向所有在线玩家广播系统消息
- `MC.Command.run(command)`：以服务端控制台身份执行命令，拥有控制台权限
- `MC.Command.runAs(uuid, command)`：以指定玩家身份执行命令，权限由该玩家决定

命令开头的 `/` 可以省略。返回 `true` 表示命令已提交；命令自身的结果会按 Minecraft 原有方式输出。

```lua
local uuid = MC.Player.list()[1].uuid
MC.Chat.message(uuid, "这条消息发给指定玩家")
MC.Chat.broadcast({text = "Lua 服务端脚本已启动", color = "#55FF55", bold = true})
MC.Command.run("time set day")
MC.Command.runAs(uuid, "spawnpoint")
```

### Fabric 模组：`MC.Mod`

这一组函数直接读取 Fabric Loader 的模组容器与元数据。

- `MC.Mod.isLoaded(id)`：指定模组已加载时返回 `true`
- `MC.Mod.version(id)`：返回模组版本；未找到时返回 `nil`
- `MC.Mod.list()`：返回所有已加载模组组成的数组，每项包含 `{id, name, version}`

```lua
print("Fabric API", MC.Mod.version("fabric-api"))

for _, mod in ipairs(MC.Mod.list()) do
    print(mod.id, mod.version)
end
```

### 游戏信息：`MC.Game`

- `MC.Game.version()`：Minecraft 版本
- `MC.Game.loaderVersion()`：Fabric Loader 版本

### 服务端信息：`MC.Server`

- `MC.Server.playerCount()`：返回在线玩家数量
- `MC.Server.isDedicated()`：是否为独立服务端
- `MC.Server.motd()`：返回服务端 MOTD
- `MC.Server.tickCount()`：服务端运行 Tick 数
- `MC.Server.maxPlayers()`、`viewDistance()`、`simulationDistance()`：读取服务器容量和距离配置
- `MC.Server.tickRate()`、`setTickRate(rate)`：读取或改变 Tick 速率
- `MC.Server.frozen()`、`setFrozen(value)`、`step(ticks?)`：冻结、恢复或逐步推进游戏
- `MC.Server.save()`：立即保存全部世界

### 实体：`MC.Entity`

已有实体用运行中 UUID 定位；实体类型使用注册 ID。玩家也属于实体，但玩家专用操作优先使用 `MC.Player`。

- `get(uuid)`、`exists(uuid)`：读取或检查实体
- `list(dimension?)`、`nearby(x, y, z, radius, dimension?)`：列出维度或半径内实体
- `spawn(entityType, x, y, z, dimension?)`：生成任意已注册实体，包括其他 Mod 实体
- `teleport(...)`、`velocity(...)`：传送实体或设置速度
- `kill(uuid)`：正常杀死；`remove(uuid)`：不走死亡流程直接移除非玩家实体
- `setName(...)`、`setGlowing(...)`、`setInvulnerable(...)`、`setGravity(...)`、`fire(...)`：改变实体状态
- `equipment(uuid, equipmentSlot)`：读取任意已加载生物的装备，返回值包含 `components/componentsJson`
- `setEquipment(uuid, equipmentSlot, itemId?, count?, components?)`：给任意已加载生物设置带数据组件的装备；`itemId` 为 `nil` 时清空
- `tags(uuid)`、`addTag(...)`、`removeTag(...)`：管理命令标签

`get`、`list`、`nearby` 返回项包含 `uuid, type, name, runtimeId, x, y, z, yaw, pitch, dimension, living, glowing, invulnerable, gravity`；生物还包含 `health, maxHealth`。

```lua
for _, entity in ipairs(MC.Entity.nearby(0, 64, 0, 20)) do
    print(entity.uuid, entity.type, entity.name)
end

MC.Entity.spawn("minecraft:zombie", 0, 64, 0)

-- 假设 zombieUuid 是上方生成后查到的实体 UUID
MC.Entity.setEquipment(zombieUuid, "mainhand", "minecraft:diamond_sword", 1, {
    ["minecraft:custom_name"] = {text = "僵尸之刃", color = "dark_green"},
    ["minecraft:unbreakable"] = {}
})
```

### 状态效果、属性：`MC.Effect`、`MC.Attribute`

- `MC.Effect.give(uuid, effectId, duration?, amplifier?, showParticles?)`
- `MC.Effect.clear(uuid, effectId?)`
- `MC.Effect.list(uuid)`：每项包含 `id, duration, amplifier, ambient, visible`
- `MC.Attribute.setBase(uuid, attributeId, value)`
- `MC.Attribute.addModifier(uuid, attributeId, modifierId, amount, operation)`
- `MC.Attribute.removeModifier(uuid, attributeId, modifierId)`

属性运算方式为 `add_value`、`add_multiplied_base` 或 `add_multiplied_total`。修改器 ID 应使用属于脚本或 Mod 的唯一命名空间 ID。

### 声音、粒子和屏幕标题

- `MC.Sound.play(soundId, x, y, z, volume?, pitch?, category?, dimension?, playerUuid?)`
- `MC.Sound.stop(playerUuid, soundId?, category?)`
- `MC.Particle.spawn(particleId, x, y, z, count?, spread?, speed?, force?, dimension?)`
- `MC.Title.show(uuid, textOrJson)`、`subtitle(...)`、`actionbar(...)`
- `MC.Title.times(uuid, fadeIn, stay, fadeOut)`、`clear(uuid)`、`reset(uuid)`

声音、粒子 ID 都来自运行时注册表，支持其他 Mod。`force=true` 会让粒子在更远距离可见，应避免一次生成过多粒子。

```lua
local uuid = MC.Player.list()[1].uuid
MC.Sound.play("minecraft:block.note_block.pling", 0, 64, 0)
MC.Particle.spawn("minecraft:flame", 0, 65, 0, 30, 1, 0.02, true)
MC.Title.times(uuid, 10, 60, 20)
MC.Title.show(uuid, {
    text = "任务完成",
    color = "#55FFFF",
    font = "minecraft:default",
    bold = true,
    underlined = true
})
```

### 计分板和队伍：`MC.Score`、`MC.Team`

- `MC.Score.create(objective, criteria?, displayNameOrJson?)`、`remove(objective)`
- `MC.Score.set(holder, objective, value)`、`add(holder, objective, amount)`、`reset(holder, objective?)`
- `MC.Score.display(slot, objective?)`：设置 `sidebar`、`list`、`below_name` 等显示位置
- `MC.Team.create(team, displayName?)`、`remove(team)`
- `MC.Team.join(team, holder)`、`leave(holder)`
- `MC.Team.color(team, color)`、`friendlyFire(team, enabled)`、`prefix(team, text)`、`suffix(team, text)`

`holder` 可以是在线玩家 UUID，也可以是由字母、数字、点、下划线、加号、减号组成的虚拟计分名称。

### 游戏规则和世界边界

- `MC.GameRule.names()`：读取当前版本全部已注册规则 ID
- `MC.GameRule.set(name, value, dimension?)`：按命令名称和值设置规则
- `MC.Border.set(size, seconds?)`、`add(amount, seconds?)`：设置或渐变边界大小
- `MC.Border.center(x, z)`、`warning(distance)`、`damage(amount)`：控制边界中心、警告和伤害

### 进度、配方、战利品和结构

- `MC.Advancement.grant/revoke(uuid, advancementId)`：授予或撤销进度
- `MC.Recipe.give/take(uuid, recipeId)`：解锁或收回配方
- `MC.Loot.give(uuid, lootTableId)`：把战利品表结果给予玩家
- `MC.Loot.insert(x, y, z, lootTableId, dimension?)`：把结果插入目标容器
- `MC.Structure.place(structureId, x, y, z, dimension?)`：放置结构模板

### 运行时注册表：`MC.Registry`

这一组接口直接读取 Minecraft 启动后形成的注册表，因此会自动包含其他 Mod 的资源，不依赖硬编码列表。

- `MC.Registry.kinds()`：可查询类型列表
- `MC.Registry.list(kind)`：查询任意受支持类型
- `blocks()`、`items()`、`entities()`、`effects()`、`sounds()`、`particles()`、`potions()`、`gameRules()`：常用快捷方法
- `dataComponents()`：列出当前运行时全部物品数据组件类型，包含其他 Mod 注册的组件
- 通用查询还支持 `fluids`、`blockEntities`、`attributes`、`dataComponents`

```lua
for _, kind in ipairs(MC.Registry.kinds()) do
    local ids = MC.Registry.list(kind)
    print(kind, #ids)
end
```

这些高层接口会验证 UUID、资源 ID、数值范围和枚举值。尚未单独封装的原版或 Mod 命令仍可通过 `MC.Command.run(...)` 使用，因此服务端命令系统提供最终的完整控制入口。任意第三方 Mod 的 Java API 没有统一 ABI，只有该 Mod 主动提供兼容入口时才能安全直接绑定；它注册到 Minecraft 注册表和命令树中的内容则可立即使用。

### 原版自定义界面：`MC.UI`

`MC.UI` 使用 Minecraft 26.1+ 的原版 Dialog 协议，由服务端直接向指定玩家打开界面。因此只负责查看和操作界面的普通玩家默认不需要安装本模组；如果服务器管理员打开了“要求玩家安装客户端模组”，则所有新连接都必须安装。可用 `MC.UI.available()` 检查功能是否存在。

- `MC.UI.register(uiId, definition)`：为当前脚本注册可重复使用的界面定义
- `MC.UI.open(playerUuid, uiId)`：向指定在线玩家打开已注册界面
- `MC.UI.show(playerUuid, uiId, definition)`：不注册，直接打开一次界面
- `MC.UI.close(playerUuid)`：关闭指定玩家当前的原版 Dialog
- `MC.UI.list()`：列出当前脚本注册的全部界面 ID
- `MC.UI.unregister(uiId)`：移除一个定义
- `MC.UI.clearRegistered()`：移除全部定义并返回移除数量
- `MC.Event.onUiAction(callback)`：监听按钮提交；事件含 `playerUuid, playerName, uiId, actionId, inputs`

界面 `type` 可选 `form`、`notice`、`confirmation`。通用字段如下：

- `title`：标题；`body`：正文字符串或字符串数组
- `canCloseWithEscape`：是否允许 Esc 关闭，默认 `true`
- `pause`：单人游戏中是否暂停，默认 `false`
- `afterAction`：按钮后的行为，可选 `close`、`none`、`wait_for_response`
- `columns`：`form` 的按钮列数，范围 1–8
- `buttons`：普通按钮数组；每项支持 `id, label, tooltip, width`
- `exit`：`form` 的可选退出按钮，结构与普通按钮相同

`inputs` 中每项都需要唯一的 `key`，支持四种类型：

- `text`：文本框；支持 `label, initial, maxLength, width, labelVisible, multiline, maxLines, height`
- `boolean`：勾选框；支持 `label, initial, onTrue, onFalse`
- `number`：数字滑块；支持 `label, min, max, initial, step, width, labelFormat`
- `select`：单选列表；`options` 每项支持 `id, text, initial`

完整示例：

```lua
local players = MC.Player.list()
if #players == 0 then error("当前没有在线玩家") end

MC.UI.register("server_menu", {
    type = "form",
    title = "服务器菜单",
    body = {"请输入留言并选择操作："},
    canCloseWithEscape = true,
    afterAction = "close",
    columns = 2,
    inputs = {
        {key = "message", type = "text", label = "留言", maxLength = 128},
        {key = "public", type = "boolean", label = "公开显示", initial = false},
        {key = "amount", type = "number", label = "数量", min = 1, max = 64, initial = 1, step = 1},
        {key = "mode", type = "select", label = "模式", options = {
            {id = "info", text = "查询", initial = true},
            {id = "execute", text = "执行"}
        }}
    },
    buttons = {
        {id = "submit", label = "提交", tooltip = "发送给服务端 Lua 脚本"},
        {id = "cancel", label = "取消"}
    }
})

MC.Event.onUiAction(function(event)
    if event.uiId ~= "server_menu" then return end
    print("玩家", event.playerName, "点击", event.actionId)
    if event.actionId == "submit" then
        print(event.inputs.message, event.inputs.public,
            event.inputs.amount, event.inputs.mode)
    end
end)

MC.UI.open(players[1].uuid, "server_menu")
```

每次打开都会生成一次性随机令牌。服务端只接受对应玩家、当前运行脚本、已声明按钮和已声明输入键的回传；客户端附加或伪造的其他字段会被忽略。玩家提交一次后令牌立即失效，脚本若要显示下一步界面，应在 `onUiAction` 中再次调用 `MC.UI.open`。停止脚本会注销界面监听并关闭尚未提交的界面。

### 强制客户端 API：`MC.Client`

API 文档中所有 `MC.Client.*` 条目都会集中显示在独立的“客户端 Mod 专用 API”大类，并显示醒目的“仅强制客户端模式”标记。这些控制函数必须同时满足以下条件，否则服务端会返回中文错误：

1. 在“设置 → 服务器接入”开启“要求玩家安装客户端模组”。
2. 开启后重新启动 Lua 脚本，使脚本获得新的限制快照。
3. 目标玩家在线，并安装了协议兼容的 MC Lua IDE 客户端。
4. “聊天、显示与界面 API”没有被服务器限制关闭。

状态查询：

- `MC.Client.available(playerUuid)`：目标玩家满足客户端 API 条件时返回 `true`
- `MC.Client.info(playerUuid)`：返回 `{playerUuid, online, required, available, protocol}`
- `MC.Client.list()`：列出已安装兼容客户端的在线玩家，包含 `uuid, name, protocol`

客户端操作：

- `MC.Client.message(playerUuid, textOrJson)`：只在目标客户端的聊天栏显示本地系统消息
- `MC.Client.toast(playerUuid, titleOrJson, messageOrJson)`：显示右上角系统 Toast
- `MC.Client.notice(playerUuid, titleOrJson, bodyOrJson)`：打开客户端通知页，玩家可点击返回
- `MC.Client.closeNotice(playerUuid)`：只关闭由 `MC.Client.notice` 打开的页面，不会关闭箱子等其他界面
- `MC.Client.openUrlPrompt(playerUuid, url)`：显示原版安全链接确认框；必须由玩家手动确认，脚本不能静默打开网页
- `MC.Client.openIde(playerUuid)`：请求打开 IDE；目标玩家仍必须通过服务器所有者或 OP 4 权限校验

```lua
local player = MC.Player.list()[1]
if not player then error("没有在线玩家") end

local client = MC.Client.info(player.uuid)
if not client.required then
    error("请先开启强制客户端模式，并重新启动脚本")
end
if not client.available then
    error("目标客户端不兼容")
end

MC.Client.message(player.uuid, {text = "这条消息只在你的客户端显示", color = "#55FFFF"})
MC.Client.toast(player.uuid, {text = "任务完成", color = "gold", bold = true}, "服务端脚本已经执行完成")
MC.Client.notice(player.uuid, {text = "服务器公告", color = "#FFAA00"}, "点击下方按钮返回之前的页面。")

-- 只弹确认框，不会直接打开浏览器
MC.Client.openUrlPrompt(player.uuid, "https://example.com/help")
```

客户端 API 不提供截图、读取按键内容、静默修改剪贴板或绕过确认直接打开外部链接等隐私能力。强制安装握手与专用数据包仅用于兼容性和功能协商，并不把客户端变成可信执行环境。

### 服务端事件：`MC.Event`

当前提供 32 种常用服务端事件，覆盖方块、物品、实体、玩家、状态效果、睡眠、区块、维度、Tick、聊天、命令和自定义界面操作。事件来自服务端世界；涉及玩家的事件始终提供 `playerUuid`，不要用客户端玩家对象代替 UUID。

事件回调不会在主代码执行完后消失。只要至少注册了一个事件，脚本就保持“运行中”，可用编辑器“停止”按钮或 `/lua stop` 停止并自动注销全部回调。回调在脚本线程中按顺序处理，不直接阻塞服务端线程，因此它们是观察型事件，不能取消原本的放置、攻击或交互。队列最多保留 1024 项，持续来不及处理时会丢弃最旧事件。

#### 注册与管理

- `MC.Event.on(eventName, callback, intervalTicks?)`：按名称注册；返回整数注册 ID
- `MC.Event.names()`：返回当前支持的全部事件名称，可用于遍历或动态注册
- `MC.Event.off(registrationId)`：只注销指定回调；找到时返回 `true`
- `MC.Event.clear()`：注销当前脚本的全部回调；返回注销数量
- 所有 `onXxx(callback)` 都是通用 `on` 的易读快捷写法
- `onServerTick`、`onLevelTick` 可把间隔作为第二个参数传入；例如 `20` 约为一秒一次

每个回调收到一个 `event` 表，`event.type` 是事件名称。资源 ID 都是完整的 `namespace:path`，所以也支持其他 Mod 注册的方块、物品、实体、效果和维度。

```lua
for _, eventName in ipairs(MC.Event.names()) do
    print("支持事件", eventName)
end

local id = MC.Event.on("blockBroken", function(event)
    print(event.playerUuid, event.blockId, event.x, event.y, event.z)
end)
print("注册 ID", id)
```

#### 方块与物品

- `blockPlaced` / `onBlockPlaced`：成功放置方块；`playerUuid, playerName, blockId, x, y, z, dimension, hand, itemId, itemCount, itemComponents`
- `blockBroken` / `onBlockBroken`：成功破坏方块；上列玩家、方块、坐标字段，加 `hadBlockEntity`
- `blockAttacked` / `onBlockAttacked`：左键攻击方块；玩家、方块、坐标字段，加 `hand, face`
- `blockUsed` / `onBlockUsed`：右键使用方块；玩家、方块、坐标字段，加 `itemId, itemCount, itemComponents, hand, face`
- `itemUsed` / `onItemUsed`：尝试使用手中物品；`playerUuid, playerName, itemId, itemCount, itemComponents, count, hand, x, y, z, dimension`

```lua
MC.Event.onBlockPlaced(function(event)
    print("放置", event.playerName, event.blockId, event.x, event.y, event.z)
    if event.blockId == "minecraft:diamond_block" then
        MC.Chat.message(event.playerUuid, "你放置了钻石块！")
    end
end)

MC.Event.onBlockBroken(function(event)
    print("破坏", event.blockId, "含方块实体", event.hadBlockEntity)
end)
```

#### 实体与战斗

实体字段按角色使用统一前缀。例如 `entityUuid, entityType, entityName, entityRuntimeId, entityX, entityY, entityZ`；目标实体使用 `target*`，击杀者和被击杀者使用 `killer*`、`killed*`，伤害来源使用可选的 `attacker*`、`direct*`。

- `entityAttacked` / `onEntityAttacked`：玩家攻击目标；玩家字段、`hand` 和 `target*`
- `entityUsed` / `onEntityUsed`：玩家右键实体；玩家字段、`hand, itemId, itemCount, itemComponents` 和 `target*`
- `entityLoaded` / `onEntityLoaded`：实体加载；`entity*` 和 `dimension`
- `entityUnloaded` / `onEntityUnloaded`：实体卸载；`entity*` 和 `dimension`
- `entityChangedDimension` / `onEntityChangedDimension`：非玩家实体切换维度；`entity*, originalUuid, fromDimension, toDimension`
- `entityDamaged` / `onEntityDamaged`：生物结算伤害后；`entity*, damageType, baseDamage, damageTaken, blocked, remainingHealth`，以及可选伤害来源字段
- `entityDied` / `onEntityDied`：生物死亡；`entity*, damageType`，以及可选伤害来源字段
- `entityKilledOther` / `onEntityKilledOther`：实体击杀生物；`killer*, killed*, damageType, dimension`，以及可选伤害来源字段
- `equipmentChanged` / `onEquipmentChanged`：装备槽变化；`entity*, slot, oldItemId, oldItemCount, oldItemComponents, oldCount, newItemId, newItemCount, newItemComponents, newCount, dimension`

```lua
MC.Event.onEntityDamaged(function(event)
    print(event.entityName, "受到", event.damageTaken, "点伤害")
    print("剩余生命", event.remainingHealth, "伤害类型", event.damageType)
    if event.attackerUuid then
        print("攻击者", event.attackerName, event.attackerUuid)
    end
end)

MC.Event.onEntityDied(function(event)
    print("死亡", event.entityType, event.entityUuid, event.dimension)
end)
```

#### 玩家、效果与睡眠

- `playerJoined` / `onPlayerJoined`：玩家加入；`playerUuid, playerName, x, y, z, dimension`
- `playerLeft` / `onPlayerLeft`：玩家离开；字段同加入事件
- `playerRespawned` / `onPlayerRespawned`：重生完成；玩家字段，加 `alive`
- `playerChangedDimension` / `onPlayerChangedDimension`：玩家切换维度；玩家字段、`fromDimension, toDimension`
- `effectAdded` / `onEffectAdded`：获得状态效果；`entity*, effectId, duration, amplifier, ambient, visible, fromCommand, dimension`
- `effectRemoved` / `onEffectRemoved`：移除状态效果；字段同添加事件
- `sleepStarted` / `onSleepStarted`：开始睡眠；`entity*, bedX, bedY, bedZ, dimension`
- `sleepStopped` / `onSleepStopped`：停止睡眠；字段同开始事件

```lua
MC.Event.onPlayerJoined(function(event)
    MC.Chat.message(event.playerUuid, "欢迎，" .. event.playerName)
end)

MC.Event.onPlayerChangedDimension(function(event)
    print(event.playerName, event.fromDimension, "->", event.toDimension)
end)
```

#### 区块、维度与 Tick

- `chunkLoaded` / `onChunkLoaded`：区块加载；`chunkX, chunkZ, minBlockX, minBlockZ, dimension, newChunk`
- `chunkGenerated` / `onChunkGenerated`：新区块生成完成；区块坐标和维度字段
- `chunkUnloaded` / `onChunkUnloaded`：区块卸载；区块坐标和维度字段
- `levelLoaded` / `onLevelLoaded`：维度加载；`dimension, gameTime, dayTime`
- `levelUnloaded` / `onLevelUnloaded`：维度卸载；字段同加载事件
- `serverTick` / `onServerTick`：服务器 Tick；`tick, playerCount`
- `levelTick` / `onLevelTick`：每个已加载维度的 Tick；`tick, dimension, gameTime, dayTime`

Tick 默认每 Tick 触发。建议传入间隔以降低脚本开销：20 Tick 约一秒，1200 Tick 约一分钟。

```lua
MC.Event.onServerTick(function(event)
    print("服务器 Tick", event.tick, "在线", event.playerCount)
end, 20)

MC.Event.onLevelTick(function(event)
    if event.dimension == "minecraft:overworld" then
        print("主世界时间", event.dayTime)
    end
end, 200)
```

#### 聊天与命令

- `chat` / `onChat`：玩家发送聊天；`playerUuid, playerName, message, decoratedMessage, x, y, z, dimension`
- `command` / `onCommand`：命令提交给服务端；`command, sourceName, x, y, z, dimension`；若来源是实体则有 `source*`，若来源是玩家则另有 `playerUuid, playerName`

`command` 不含开头的 `/`。在命令回调里再次执行同类命令时要自行设置条件，避免产生递归监听。

```lua
MC.Event.onChat(function(event)
    print("<" .. event.playerName .. "> " .. event.message)
end)

MC.Event.onCommand(function(event)
    print("命令来源", event.sourceName, "内容", event.command)
end)
```

#### 自定义界面操作

- `uiAction` / `onUiAction`：玩家点击脚本界面按钮；`playerUuid, playerName, uiId, actionId, inputs`
- `inputs` 是 Lua 表，键和值来自界面定义；文本和选择返回字符串，勾选框返回布尔值，数字滑块返回数字
- 玩家按 Esc 直接关闭时不会触发按钮事件

#### 注销示例

```lua
local joinId = MC.Event.onPlayerJoined(function(event)
    print(event.playerName)
end)

MC.Event.off(joinId)
-- MC.Event.clear() 可以一次清除当前脚本的全部监听
```

多人模式下，脚本始终保存在并运行于远端服务端。执行输出通过专用通道实时返回给发起运行的管理员；管理员断开连接时，由该管理员启动的活动脚本会自动停止，防止无人管理的脚本继续运行。

## 基础示例

```lua
local message = "Hello Minecraft"
print(message)
```

## 条件和循环

```lua
local score = 12

if score >= 10 then
    print("通过")
else
    print("未通过")
end

for i = 1, 5 do
    print("计数", i)
end
```

## 函数与表

```lua
local function greet(name)
    return "你好，" .. name
end

local players = { "Alex", "Steve" }
for index, name in ipairs(players) do
    print(index, greet(name))
end
```

## 实时输出

运行期间，下方会自动展开 `OUTPUT` 控制台。每次调用 `print(...)`，内容都会立即追加到控制台，不必等待脚本结束。

```lua
for i = 1, 20 do
    print("当前进度", i)
end
```

## 停止脚本

运行时点击“停止”，或再次按 `Ctrl+Enter`。即使脚本进入无限循环，执行器也会通过指令钩子安全中断。

```lua
while true do
    -- 可以通过停止按钮中断
end
```

## 管理员客户端命令

- `/lua`：通过权限检查后打开服务端脚本目录
- `/lua list`：列出服务端保存的所有脚本
- `/lua run <名称>`：请求服务端运行指定脚本，`print` 输出实时发送到管理员聊天栏
- `/lua stop`：停止服务端当前正在运行的脚本

## 配色、字号和界面缩放

编辑器顶部已经把原“UI”菜单合并进“窗口”。“窗口 → 字号”支持预设值和 10%–300% 自定义百分比，会同步缩放代码、行号、语法高亮、行间距、换行宽度和光标定位；“窗口 → 配色”提供深色、浅色和高对比度配色。

“窗口 → 界面缩放”同样支持 10%–300% 的预设值和自定义百分比，会缩放整个 IDE，包括菜单、编辑区、吸附区域、浮动窗口和文字。鼠标命中、拖动、悬浮与边框缩放会同步换算，不需要改变 Minecraft 的全局 GUI 缩放。

## 安全限制

Java/luajava、操作系统库、原生文件 IO、package/require 和 Lua debug 库默认全部禁用，但服务器所有者可以在“核心安全边界”页分别开启。所有高风险开关旁都会显示红色“⚠ 高危”提示：

- `Java / luajava`：脚本可能获得 JVM、服务端内部对象、文件和网络访问能力
- `操作系统库 os`：脚本可能执行系统命令或读取进程环境
- `原生 io 与任意路径`：启用 `io`、`dofile`、`loadfile`，不再受每脚本数据目录限制
- `package / require`：允许从包路径加载额外模块
- `Lua debug 库`：脚本可以移除停止和超时钩子，可能导致脚本无法正常终止
- `HTTP/HTTPS 网络 API`：提供 `MC.Network`，可访问公网、服务器内网或本机 HTTP 服务，存在 SSRF 风险

限制页面会按影响分级并常驻显示具体理由：红色“高危”表示能力可能越过 Lua 沙箱、访问主机或以控制台权限操作服务器；黄色“警告”表示能力仍受游戏 API 或脚本目录边界限制，但可能修改世界、玩家、物品或造成性能与数据风险。关闭时按钮显示绿色“安全：禁止”，开启时按钮使用对应的红色或黄色。

这些开关直接降低服务端安全性，只应在完全信任全部服务端 Lua 脚本时开启。网络页还可以限制连接/请求超时和最大请求响应字节数，并且不会自动跟随重定向；这些限制不能消除访问内网服务的风险。启用 Java 互操作后也可能绕过 `MC.Network` 的限制直接进行网络访问。

“Lua 限制”页面还可以分别控制：

- 脚本长度、最长运行时间、输出行数、单行长度和中断检查频率
- 事件监听数量、事件队列容量和 Tick 事件最低间隔
- 命令、世界、玩家、实体、物品容器、聊天显示与自定义界面、服务端管理和注册表等 `MC.*` API 分类
- `load/loadstring`、协程、元表/raw 操作以及垃圾回收控制
- 隔离文件的读取、写入和删除权限、单文件大小和文件数量

“服务器接入”页面单独提供“要求玩家安装客户端模组”开关。它属于黄色警告项：开启不会扩大 Lua 权限，但会阻止原版客户端和旧协议客户端登录。服务端只接受与当前 `MC Lua IDE` 协议版本一致的握手；这个检查用于版本兼容和安装要求，不是反作弊认证，能够自行实现协议的自定义客户端仍可完成握手。

隔离文件权限默认全部关闭。只启用 `MC.File` 读取、写入或删除时，不会恢复 Lua 原生 `io`，脚本只能访问自己的 `config/mc_lua_ide/data/<脚本名>/` 目录；相对路径、符号链接、文件数量和大小都会由服务端验证。如果另外打开红色高危的“原生 io 与任意路径”，此隔离边界将不再成立：

```lua
MC.File.write("state.txt", "ready")
MC.File.append("events.log", "started\n")
print(MC.File.read("state.txt"))

for _, path in ipairs(MC.File.list()) do
    print(path)
end
```

脚本可通过 `MC.Limits.current()` 读取本次启动使用的限制快照。`MC.Command.run` 拥有服务端控制台能力，因此整个 IDE 和设置协议只向服务器所有者权限开放。

启用红色高危的网络开关后，可以使用：

```lua
local response = MC.Network.get("https://example.com")
print(response.status, response.body)

local posted = MC.Network.post(
    "https://example.com/api",
    "{\"message\":\"hello\"}",
    "application/json"
)
print(posted.status)
```

## 常见问题

### 为什么脚本能通过语法检查，运行时仍然报错？

编辑器可以提前发现 Lua 结构错误以及可静态确定的 `MC.*` API 名称、参数数量、参数类型和常见格式问题。变量的运行时内容、玩家是否在线、资源是否真实存在、区块是否加载等动态状态，仍需运行到对应代码时才能最终确认。

### 为什么 OUTPUT 面板消失了？

OUTPUT 会在开始运行时自动展开，脚本结束或停止后保留日志。点击输出区域右上角的关闭按钮即可收起。

### 如何编辑脚本文件？

既可以使用管理员客户端的游戏内编辑器，也可以由服务器管理者直接修改服务端 `config/mc_lua_ide/scripts` 中的 `.lua` 文件，然后在 IDE 中重新读取。
