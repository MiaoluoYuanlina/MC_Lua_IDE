# 分页商店模块

服务端脚本路径：`Bed_Wars/Shop/Main.lua`。需要已支持 `MC.Player.openChest` 和 `MC.Item.trade` 的模组版本。

在你的启动脚本中调用：

```lua
local Shop = require("Bed_Wars/Shop/Main")
local containerId, reason = Shop.open("目标玩家UUID", 1)
if not containerId then print("打开商店失败：", reason) end
```

也可在 `require` 后使用 `Open_Shop(uuid, page)`。页码省略时打开最小页码；`Shop.close(uuid)` 只关闭该模块为玩家打开的当前商店，不会关闭其他窗口。

配置保留五项数组写法：

```lua
Config_Shop_Item = {}
Config_Shop_Item[1] = {}
Config_Shop_Item[1][1] = {
    "minecraft:white_wool", 16,
    {["minecraft:item_model"] = "minecraft:bedrock"},
    "minecraft:iron_ingot", 1
}
Config_Shop_Item[2] = {}
Config_Shop_Item[2][1] = {"minecraft:iron_sword", 1, {}, "minecraft:gold_ingot", 6}

local Shop = require("Bed_Wars/Shop/Main")
Shop.open("目标玩家UUID", 1)
```

五项依次为：兑换物品 ID、发放数量、物品组件表或 JSON 字符串、货币 ID、价格。每页索引 1～45 对应箱子上部槽位 0～44；可以省略位置留空，底行自动显示导航。商品数量超过单堆上限时只限制展示数量，实际发货由交易 API 拆堆。

可以修改模块顶部的默认配置；启动脚本预先定义的配置不会被默认值覆盖，也可以加载后 `Shop.configure(config)` 替换配置。已打开页面保持当时展示的商品／价格，重新打开后读取新配置。加载模块不自动打开任何玩家的商店，不自动注册村民／物品开店行为；如需这些行为，请在启动脚本中显式调用。

商品使用只读原版箱子 UI，左键购买。扣费与发货使用服务端原子交易，余额或背包空间不足不扣费。配置中的物品组件原样用于奖励；额外展示标题和价格说明不会污染实际发放的物品。关闭或切换菜单后，旧点击事件不会购买新窗口的商品。

保存模块不会替换当前已经加载的 `require` 缓存或正在运行的旧脚本。修改后需由管理员重新运行实际启动脚本；不要同时独立运行商店模块与启动脚本。本次模块仅提供函数和购买监听，直接运行该模块不会自动弹出商店。

## 根据玩家队伍替换白色物品

```lua
local Shop = require("Bed_Wars/Shop/Main")
Shop.Color({"red", "green", "blue", "yellow"})
Shop.open("目标玩家UUID", 1)
```

`XXX` 是 `require` 返回的模块变量名，例如 `local XXX = require("Bed_Wars/Shop/Main")` 后可写 `XXX.Color({...})`。

颜色字符串也是游戏队伍名：玩家在 `red` 队时，`minecraft:white_wool` 变为 `minecraft:red_wool`，白色玻璃、床、染料等同理。商品与货币 ID 的路径中所有 `white` 都会替换；命名空间保持不变，也支持其他模组已注册的对应颜色物品。转换后的 ID 必须真实存在，否则打开页面时报告配置错误，不扣费或发货。

支持 Minecraft 的 16 种物品颜色（包括 `lime`、`light_blue`、`light_gray`）。未设置、玩家不属于数组中的队伍，或调用 `Shop.Color(nil)` / `Shop.Color({})` 时保留原 ID。注意物品颜色 `green` 是深绿色，亮绿色使用 `lime`；队伍名必须匹配。

替换不修改 `Config_Shop_Item`，不同队伍同时打开时互不影响。数量、组件与第 6 项介绍保持原样；组件里显式设置的 `minecraft:item_model` 不会随 ID 替换，若希望显示物品默认颜色模型，请移除该覆盖。商店展示和实际交易使用相同的替换结果；玩家在已打开页面中换队后，再次点击商品会先刷新页面，不立即购买。
