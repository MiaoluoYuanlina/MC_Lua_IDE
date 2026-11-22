-- Clicking players need MC Lua IDE on their clients.
-- Fires on press (true) and release (false) on air, blocks or entities.
-- Holding does not repeat.
MC.Event.onPlayerLeftClick(function(event)
    print(event.playerName, "left click", event.pressed, event.itemId)
end)

MC.Event.onPlayerRightClick(function(event)
    print(event.playerName, "right click", event.pressed, event.itemId)
end)
