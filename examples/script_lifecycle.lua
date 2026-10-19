-- 启动与停止事件示例：运行后每秒输出，点击停止或执行 /lua stop 测试清理回调。
-- 两个生命周期监听本身不让脚本常驻，下面的定时监听负责保持运行。
local ticks = 0

MC.Event.onScriptStarted(function(event)
    print("脚本启动：", event.scriptName, "管理员 UUID：", event.ownerUuid)
end)

MC.Event.onScriptStopped(function(event)
    -- 在这里移除自己创建的实体、恢复方块/状态等。
    -- 不能等待或注册新事件；单回调最多 1 秒，全部清理合计最多 2 秒。
    print("脚本结束：", event.reason, "运行时间：", event.elapsedMs, "ms")
    print("共执行：", ticks, "次")
    if event.error then print("结束前的错误：", event.error) end
end)

MC.Event.onInterval(1000, function(event)
    ticks = event.count
    print("运行中：", ticks)
end)
