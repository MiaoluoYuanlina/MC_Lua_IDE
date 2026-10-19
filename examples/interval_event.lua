-- 每 1000 毫秒执行一次，执行 5 次后自动注销。
-- 服务端执行，普通玩家无需安装客户端模组。
local timerId = MC.Event.onInterval(1000, function(event)
    print("定时事件", event.registrationId,
        "第", event.count, "次",
        "注册后经过", event.elapsedMs, "ms",
        "本次实际间隔", event.deltaMs, "ms")

    if event.count >= 5 then
        MC.Event.off(event.registrationId)
        print("定时监听已注销")
    end
end)
print("定时监听 ID：", timerId)

-- 注销也可以在其他位置调用 MC.Event.off(timerId)。
-- MC.Event.clear() 注销当前脚本的全部监听。
-- IDE 停止按钮或 /lua stop 会停止脚本并注销全部监听。
