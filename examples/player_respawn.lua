-- 监听玩家死亡后复活；普通玩家无需安装本模组。
-- alive 表示重生前是否存活；true 的存活重建（如从末地返回）在这里忽略。
-- 停止脚本自动注销监听。
local registrationId = MC.Event.onPlayerRespawned(function(event)
    if not event.alive then
        print("玩家已复活：", event.playerName, event.playerUuid)
        print("重生位置：", event.dimension, event.x, event.y, event.z)
    end
end)
print("玩家复活监听已注册：", registrationId)
