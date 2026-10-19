-- 在服务端管理员 IDE 中运行；普通玩家客户端无需安装 MC Lua IDE。
-- 两个标签一次写入，只触发一次服务端数据重载。
local result = MC.Pack.putDataBatch({
    {
        kind = "tags/block",
        id = "mclua_demo:building_blocks",
        json = [[{"values":["minecraft:stone","minecraft:deepslate"]}]]
    },
    {
        kind = "tags/item",
        id = "mclua_demo:building_items",
        json = [[{"values":["minecraft:stone","minecraft:deepslate"]}]]
    }
})
print("数据包：", result.status, "文件数：", result.count, result.message)

-- 以下只导出原版客户端资源包，不会自动推送给玩家。
-- 它将已存在的木棍显示为钻石模型；测试后可删除对应生成文件并重新导出。
-- MC.Pack.putAssetJson("items", "minecraft:stick",
--     [[{"model":{"type":"minecraft:model","model":"minecraft:item/diamond"}}]])
-- local exported = MC.Pack.exportAssets()
-- print("请手动发布 ZIP：", exported.path)
