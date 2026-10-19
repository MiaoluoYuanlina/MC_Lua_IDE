-- 必须在 Mod Menu → MC Lua IDE → 配置 → 服务器接入 开启“要求玩家安装客户端模组”。
-- 开启后重新启动脚本；目标客户端也必须安装兼容版本。
local player = MC.Player.list()[1]
if not player then
    error("没有在线玩家")
end
if not MC.Client.available(player.uuid) then
    error("非零起播时间需要强制客户端模式和兼容客户端模组")
end

local p = MC.Player.position(player.uuid)
local soundId = "minecraft:music_disc.cat"
-- 最后一个参数是音频起播秒数，支持小数，不是延迟执行时间。
MC.Sound.play(soundId, p.x, p.y, p.z, 1, 1, "record", p.dimension, player.uuid, 30.5)
print("已请求从第 30.5 秒播放唱片，5 秒后停止")
MC.sleep(5000)
MC.Sound.stop(player.uuid, soundId, "record")
print("已停止")
