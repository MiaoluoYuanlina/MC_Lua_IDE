-- Clicking players need MC Lua IDE on their clients.
-- Fires once per press on air, blocks or entities; holding does not repeat.
MC.Event.onPlayerLeftClick(function(event)
    print(event.playerName, "left click", event.itemId)
end)

MC.Event.onPlayerRightClick(function(event)
    print(event.playerName, "right click", event.itemId)
end)
