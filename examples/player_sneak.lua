-- 检测玩家按住潜行键（Shift）。
-- MC.Player.input 返回客户端最近上报的按键状态，服务端执行，普通玩家无需安装客户端模组。
-- 每 50 毫秒（约 1 tick）检查一次，只在按下和松开的瞬间输出，避免刷屏。
local wasSneaking = {}

local timerId = MC.Event.onInterval(50, function()
    for _, player in ipairs(MC.Player.list()) do
        local sneaking = MC.Player.input(player.uuid, "sneak")
        if sneaking and not wasSneaking[player.uuid] then
            print(player.name .. " 按下了 Shift")
        elseif not sneaking and wasSneaking[player.uuid] then
            print(player.name .. " 松开了 Shift")
        end
        wasSneaking[player.uuid] = sneaking
    end
end)
print("潜行检测已启动，监听 ID：", timerId)

-- 不带 key 参数会返回全部按键的表：
--   local keys = MC.Player.input(uuid)
--   print(keys.forward, keys.backward, keys.left, keys.right, keys.jump, keys.sneak, keys.sprint)
-- 停止脚本自动注销监听。
