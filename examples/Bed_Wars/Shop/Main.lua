-- 起床战争分页商店模块：local Shop = require("Bed_Wars/Shop/Main")
-- 打开指定玩家：Shop.open("玩家UUID", 1) 或 Open_Shop("玩家UUID", 1)
-- 队伍配色：Shop.Color({"red", "green", "blue", "yellow"})；nil 或 {} 关闭。
-- 每页 45 个商品，索引 1～45 对应上部槽位 0～44；底行是导航。
-- 格式：{兑换物品ID, 数量, 物品组件, 货币ID, 价格}
-- require 不会自动打开任何玩家的商店，也不会自动绑定村民／物品触发。
-- 启动脚本可在 require 前定义 Config_Shop_Item，或调用 Shop.configure 替换配置。

if _G.Config_Shop_Item == nil then
    Config_Shop_Item = {} -- 商店物品配置
    Config_Shop_Item[1] = {} -- 页面码 1
    Config_Shop_Item[1][1] = {
        "minecraft:white_wool", 16,
        {["minecraft:item_model"] = "minecraft:bedrock"},
        "minecraft:iron_ingot", 1
    }
    Config_Shop_Item[2] = {} -- 页面码 2
    Config_Shop_Item[2][1] = {
        "minecraft:iron_sword", 1, {}, "minecraft:gold_ingot", 6
    }
end

local Shop = {}
local sessions = {}
local teamColors = {}
local allowedColors = {
    white = true, orange = true, magenta = true, light_blue = true,
    yellow = true, lime = true, pink = true, gray = true,
    light_gray = true, cyan = true, purple = true, blue = true,
    brown = true, green = true, red = true, black = true
}
local PREVIOUS_SLOT, PAGE_SLOT, NEXT_SLOT, CLOSE_SLOT = 45, 49, 50, 53

local function copy(value)
    if type(value) ~= "table" then return value end
    local result = {}
    for key, child in pairs(value) do result[key] = copy(child) end
    return result
end

local function positiveInteger(value, maximum)
    return type(value) == "number" and value >= 1 and value <= maximum and value == math.floor(value)
end

-- 数组中的颜色同时作为队伍名；复制保存，避免调用者修改数组影响商店。
function Shop.Color(colors)
    if colors == nil then colors = {} end
    assert(type(colors) == "table", "Shop.Color 的参数必须是队伍颜色数组、nil 或 {}")
    local checked, seen, count = {}, {}, 0
    for index, color in pairs(colors) do
        assert(positiveInteger(index, 16), "Shop.Color 的数组索引必须为 1～16 的连续整数")
        assert(type(color) == "string" and allowedColors[color],
            "Shop.Color 不支持的物品颜色：" .. tostring(color))
        assert(not seen[color], "Shop.Color 的队伍颜色重复：" .. color)
        checked[index], seen[color] = color, true
        count = count + 1
    end
    for index = 1, count do
        assert(checked[index] ~= nil, "Shop.Color 的数组索引必须从 1 开始连续填写")
    end
    teamColors = checked
    return Shop
end

local function playerColor(uuid)
    for _, color in ipairs(teamColors) do
        local belongs, reason = MC.Team.has(color, uuid)
        if belongs == nil then return nil, reason or "无法读取玩家队伍" end
        if belongs then return color end
    end
    return nil
end

local function coloredId(id, color)
    if not color then return id end
    -- 只替换路径中的 white，不改模组命名空间或物品组件。
    local namespace, path = string.match(id, "^([^:]+:)(.*)$")
    if not namespace then namespace, path = "", id end
    return namespace .. (string.gsub(path, "white", color))
end

local function checkConfiguration(config)
    assert(type(config) == "table", "Config_Shop_Item 必须是一个表")
    local pages = {}
    for page, entries in pairs(config) do
        assert(positiveInteger(page, 999999), "页面码必须是正整数")
        assert(type(entries) == "table", "页面 " .. page .. " 的商品配置必须是一个表")
        pages[#pages + 1] = page
        for index, entry in pairs(entries) do
            local where = "Config_Shop_Item[" .. page .. "][" .. tostring(index) .. "]"
            assert(positiveInteger(index, 45), where .. "：每页商品索引必须为 1～45")
            assert(type(entry) == "table", where .. "：商品必须使用五项数组")
            assert(type(entry[1]) == "string" and entry[1] ~= "", where .. "：第1项应为兑换物品 ID")
            assert(positiveInteger(entry[2], 999999), where .. "：第2项数量必须为正整数")
            assert(entry[3] == nil or type(entry[3]) == "table" or type(entry[3]) == "string",
                where .. "：第3项组件必须是表、JSON 字符串或 nil")
            assert(type(entry[4]) == "string" and entry[4] ~= "", where .. "：第4项应为货币物品 ID")
            assert(positiveInteger(entry[5], 999999), where .. "：第5项价格必须为正整数")
        end
    end
    table.sort(pages)
    return pages
end

local function message(uuid, text, color)
    MC.Chat.message(uuid, {text = "[商店] " .. text, color = color or "yellow"})
end

local function button(id, text, color, lore)
    return {
        id = id, count = 1,
        components = {
            ["minecraft:custom_name"] = {text = text, color = color or "yellow", italic = false},
            ["minecraft:lore"] = {{text = lore or "左键点击", color = "gray", italic = false}}
        }
    }
end

local function product(entry, color)
    local reward = MC.Item.validate(coloredId(entry[1], color), 1, entry[3])
    local currency = MC.Item.validate(coloredId(entry[4], color), 1)
    assert(not reward.empty and not currency.empty, "商品或货币不能是空气")
    local result = {
        id = reward.id, count = entry[2], components = copy(reward.components),
        currencyId = currency.id, price = entry[5], currencyName = currency.name,
        name = reward.name, maxStack = reward.maxStack
    }
    -- 展示用价格说明不写进真正发放的奖励，也不修改配置表。
    local displayComponents = copy(result.components)
    displayComponents["minecraft:custom_name"] = {
        text = result.name .. " ×" .. result.count, color = "aqua", italic = false
    }
    local lore = copy(displayComponents["minecraft:lore"] or {})
    lore[#lore + 1] = {text = "价格：" .. result.price .. " " .. result.currencyName, color = "gold", italic = false}
    lore[#lore + 1] = {text = "左键购买；余额或背包空间不足不扣费", color = "gray", italic = false}
    displayComponents["minecraft:lore"] = lore
    result.display = {id = result.id, count = math.min(result.count, result.maxStack), components = displayComponents}
    return result
end

-- 成功返回容器 ID；离线等状态失败返回 nil, 原因。
function Shop.open(uuid, page)
    assert(type(uuid) == "string" and uuid ~= "", "Shop.open 的第1个参数必须是玩家 UUID 字符串")
    local config = _G.Config_Shop_Item
    local pages = checkConfiguration(config)
    assert(#pages > 0, "Config_Shop_Item 没有配置任何页面")
    page = page or pages[1]
    assert(positiveInteger(page, 999999) and type(config[page]) == "table", "未配置商店页面：" .. tostring(page))
    local color, colorReason = playerColor(uuid)
    if colorReason then return nil, colorReason end
    local position = 1
    for index, pageId in ipairs(pages) do
        if pageId == page then position = index end
    end
    local session = {
        page = page, products = {}, color = color,
        previous = pages[position - 1], nextPage = pages[position + 1]
    }
    local items = {}
    for index, entry in pairs(config[page]) do
        local prepared = product(entry, color)
        session.products[index - 1] = prepared
        items[index] = prepared.display
    end
    if session.previous then
        items[PREVIOUS_SLOT + 1] = button("minecraft:arrow", "上一页")
    end
    items[PAGE_SLOT + 1] = button("minecraft:paper", "页面 " .. page .. "（" .. position .. "/" .. #pages .. "）",
        "gold", "商品和组件由 Config_Shop_Item 定义")
    if session.nextPage then
        items[NEXT_SLOT + 1] = button("minecraft:arrow", "下一页")
    end
    items[CLOSE_SLOT + 1] = button("minecraft:barrier", "关闭商店", "red")
    local containerId, reason = MC.Player.openChest(uuid,
        {text = "起床战争 · 商店 · 第 " .. page .. " 页", color = "gold"}, items, 6, true)
    if not containerId then return nil, reason end
    session.containerId = containerId
    sessions[uuid] = session
    return containerId
end

-- 已打开页面保持当时显示的价格；重开后读取新配置。
function Shop.configure(config)
    checkConfiguration(config)
    Config_Shop_Item = config
    return Shop
end

function Shop.close(uuid)
    local session = sessions[uuid]
    if not session then return false end
    sessions[uuid] = nil
    local menu = MC.Player.menu(uuid)
    if menu and menu.containerId == session.containerId then
        return MC.Player.closeMenu(uuid)
    end
    return false
end

local function clicked(event)
    local uuid = event.playerUuid
    local session = sessions[uuid]
    if not session or session.busy or not event.virtual or not event.readOnly
        or session.containerId ~= event.containerId or event.slotArea ~= "container"
        or event.clickType ~= "PICKUP" or event.button ~= 0 then return end
    local menu = MC.Player.menu(uuid)
    if not menu or menu.containerId ~= session.containerId then return end
    if event.slotId == CLOSE_SLOT then
        Shop.close(uuid)
    elseif event.slotId == PREVIOUS_SLOT and session.previous then
        Shop.open(uuid, session.previous)
    elseif event.slotId == NEXT_SLOT and session.nextPage then
        Shop.open(uuid, session.nextPage)
    else
        local selected = session.products[event.slotId]
        if not selected then return end
        -- 玩家换队或配色设置改变后，先刷新展示，不能按旧颜色直接发货。
        local color, colorReason = playerColor(uuid)
        if colorReason then
            message(uuid, colorReason, "red")
            return
        end
        if color ~= session.color then
            local opened, reason = Shop.open(uuid, session.page)
            message(uuid, opened and "队伍颜色已变化，已刷新商店，请重新选择商品" or tostring(reason))
            return
        end
        session.busy = true
        local invoked, ok, reason = pcall(function()
            return MC.Item.trade(uuid,
                {{id = selected.currencyId, count = selected.price}},
                {{id = selected.id, count = selected.count, components = selected.components}},
                session.containerId)
        end)
        session.busy = false
        if not invoked then error(ok) end
        if ok then
            message(uuid, "购买成功：" .. selected.name .. " ×" .. selected.count, "green")
        else
            message(uuid, "购买失败：" .. tostring(reason), "red")
        end
    end
end

MC.Event.onContainerClicked(function(event)
    local ok, reason = pcall(clicked, event)
    if not ok then
        print("[商店] 操作失败：", reason)
        message(event.playerUuid, "配置或操作有误，请管理员查看运行日志", "red")
    end
end)

MC.Event.onPlayerLeft(function(event)
    sessions[event.playerUuid] = nil
end)

Shop.Open_Shop = Shop.open
Open_Shop = Shop.open
return Shop
