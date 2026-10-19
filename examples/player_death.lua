-- 服务端监听所有玩家的真实死亡；普通玩家不需要安装本模组。
-- 回调里的位置是死亡发生时的快照；停止脚本会自动注销监听。
local registrationId = MC.Event.onPlayerDied(function(event)
    print("玩家死亡：", event.playerName, event.playerUuid)
    print("死亡位置：", event.dimension, event.x, event.y, event.z)
    print("死亡原因：", event.damageType, event.deathMessage)
    if event.attackerUuid then
        print("攻击者：", event.attackerName, event.attackerType, event.attackerUuid)
    end
end)
print("玩家死亡监听已注册：", registrationId)
