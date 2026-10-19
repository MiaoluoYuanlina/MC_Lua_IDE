-- 成功返回生成实体的 UUID 字符串，加入世界失败返回 false。
-- 请按自己的存档修改坐标；普通玩家无需安装客户端模组。
local entityUuid = MC.Entity.spawn("minecraft:pig", 0, 64, 0)

if entityUuid then
    print("生成的实体 UUID：", entityUuid)
    MC.Entity.setName(entityUuid, "Lua 生成的小猪")
    MC.Entity.setGlowing(entityUuid, true)
    local entity = MC.Entity.get(entityUuid)
    print(entity.uuid, entity.type, entity.x, entity.y, entity.z)
    -- 需要移除时可调用 MC.Entity.remove(entityUuid)。
else
    print("实体未能加入世界")
end
