-- 钻石世界示例（Minecraft 26.1.2）
-- 第一次运行：注册 mclua_demo:diamond_world 维度。
-- 完全退出当前世界并重新进入后，再运行一次：传送玩家进入钻石世界。
-- 多人服务器请把下面的 PLAYER_UUID 改成要传送的玩家 UUID。
local PLAYER_UUID = ""
local DIMENSION_ID = "mclua_demo:diamond_world"

local loaded = false
for _, id in ipairs(MC.World.dimensions()) do
    if id == DIMENSION_ID then
        loaded = true
        break
    end
end

if not loaded then
    local dimensionJson = [=[
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "minecraft:plains",
      "features": false,
      "lakes": false,
      "layers": [
        {"block": "minecraft:bedrock", "height": 1},
        {"block": "minecraft:diamond_block", "height": 127}
      ],
      "structure_overrides": []
    }
  }
}
]=]
    local result = MC.Pack.putData("dimension", DIMENSION_ID, dimensionJson)
    print("钻石世界已写入：" .. result.path)
    print("状态：" .. result.status)
    print("请完全退出当前世界并重新进入，然后再次运行本脚本。")
    return
end

local playerUuid = PLAYER_UUID
if playerUuid == "" then
    local players = MC.Player.list()
    if #players ~= 1 then
        print("当前在线玩家数为 " .. #players .. "。请先在脚本顶部填写 PLAYER_UUID，避免传送错玩家。")
        return
    end
    playerUuid = players[1].uuid
end

-- 超平坦层从 Y=-64 向上铺设，钻石层顶面约在 Y=64；传送到 Y=67。
local success = MC.Player.teleport(playerUuid, 0, 67, 0, DIMENSION_ID)
print(success and "已进入钻石世界。" or "传送失败，请检查服务端日志。")
