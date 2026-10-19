-- 生成原版客户端资源包：test:test 的物品模型显示为 TNT 方块。
-- 这不会注册新的物品类型；实际物品 ID 仍需是已有的物品。
local itemModelId = "test:test"

-- assets/test/models/item/test.json：真正引用 TNT 方块模型。
local model = MC.Pack.putAssetJson("models/item", itemModelId,
    [[{"parent":"minecraft:block/tnt"}]])

-- assets/test/items/test.json：把 item_model = test:test 指向上面的模型。
local definition = MC.Pack.putAssetJson("items", itemModelId,
    [[{"model":{"type":"minecraft:model","model":"test:item/test"}}]])

local exported = MC.Pack.exportAssets()
print("模型文件：" .. model.path)
print("物品定义：" .. definition.path)
print("资源包 ZIP：" .. exported.path)
print("请把 ZIP 发给玩家并让玩家启用资源包，然后再测试外观。")

-- 可选预览：安装 ZIP 后，在最后一行填入玩家 UUID 并取消注释。
-- 木棍的实际物品 ID 仍是 minecraft:stick，不会影响普通木棍的外观。
local function givePreview(uuid)
    local count = MC.Item.give(uuid, "minecraft:stick", 1,
        {["minecraft:item_model"] = itemModelId})
    print("预览物品已给予，数量：" .. count)
end
-- givePreview("123e4567-e89b-12d3-a456-426614174000")
