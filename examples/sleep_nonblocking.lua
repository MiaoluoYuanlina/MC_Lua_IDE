-- 使用物品后等待一秒；期间放置方块、再次使用物品都能触发回调。
-- 运行后持续监听；点击 IDE 的停止按钮或使用 /lua stop 结束。
MC.Event.onItemUsed(function(event)
    local playerName = event.playerName
    print(playerName, "使用了物品，开始等待一秒")
    MC.sleep(1000)
    print(playerName, "等待结束")
end)

MC.Event.onBlockPlaced(function(event)
    print(event.playerName, "放置了", event.blockId)
end)

print("监听已注册，主流程等待两秒；这期间上面的监听也能执行")
MC.sleep(2000)
print("主流程等待结束，监听仍保持运行")
