-- 示例：让小猪坐在牛上；请按自己的存档修改坐标。
-- 两个参数都是运行中的实体 UUID，不是 minecraft:pig 这样的类型 ID。
-- 服务端执行；普通玩家无需安装客户端模组。
local passengerUuid = MC.Entity.spawn("minecraft:pig", 0, 64, 0)
local vehicleUuid = MC.Entity.spawn("minecraft:cow", 0, 64, 0)

if passengerUuid and vehicleUuid then
    local success = MC.Entity.ride(passengerUuid, vehicleUuid)
    print("骑乘成功：", success)
    local passenger = MC.Entity.get(passengerUuid)
    print("乘客：", passengerUuid, "当前坐骑：", passenger.vehicleUuid)
    for _, uuid in ipairs(MC.Entity.get(vehicleUuid).passengerUuids) do
        print("牛的乘客：", uuid)
    end
    -- MC.Entity.dismount(passengerUuid) -- 让小猪下坐骑
    -- MC.Entity.ride(passengerUuid, vehicleUuid, false) -- 保留冷却和座位检查
else
    print("生成实体失败")
end
-- 骑乘是持久的世界状态，不会在停止脚本时自动解除。
