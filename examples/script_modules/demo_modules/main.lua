local util = MC.Script.require("demo_modules.util")

print(util.greet("Lua IDE"))
print("7 的平方 = " .. util.square(7))
print("模块缓存正常：" .. tostring(util == require("demo_modules/util")))
