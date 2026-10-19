local M = {}

function M.greet(name)
    return "你好，" .. name .. "！"
end

function M.square(number)
    return number * number
end

return M
