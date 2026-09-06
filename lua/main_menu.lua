local heroui = require("heroui")

local M = {}

function M.register()
    local column = heroui.column { children = {} }
    local function add(node)
        table.insert(column.children, node)
        return node
    end

    add(heroui.label { text = "NEOGENESIS", textSize = 44 })
    add(heroui.label { text = "Vulkan Native UI", textSize = 16,
                       textColor = heroui.colors.foregroundMuted })
    local bar = add(heroui.accent_bar())
    add(heroui.spacer(14))
    add(heroui.button { text = neoui.i18n("menu.singleplayer"), variant = "solid",
                        onClick = function() neoui.open("singleplayer") end })
    add(heroui.button { text = neoui.i18n("menu.multiplayer"), variant = "bordered",
                        onClick = function() neoui.open("multiplayer") end })
    local row = heroui.row { w = 400, children = {
        heroui.button { text = neoui.i18n("menu.options"), variant = "bordered", w = 196,
                        onClick = function() neoui.open("options") end },
        heroui.button { text = neoui.i18n("options.language"), variant = "bordered", w = 196,
                        onClick = function() neoui.open("language") end } } }
    add(row)
    add(heroui.button { text = neoui.i18n("menu.quit"), variant = "light",
                        textColor = heroui.colors.danger,
                        onClick = function() neoui.handle("quit") end })

    local tree = { type = "box", children = {} }
    table.insert(tree.children, heroui.card { w = 480,
        anchor = {0.5, 0.5}, pivot = {0.5, 0.5}, children = { column } })
    table.insert(tree.children, heroui.label { text = "Neogenesis 1.8.9 Vulkan",
        textSize = 13, textColor = "#FF55555C",
        anchor = {0, 1}, pivot = {0, 1}, offset = {16, -14} })
    table.insert(tree.children, heroui.label {
        text = "Copyright Mojang AB. Do not distribute!",
        textSize = 13, textColor = "#FF55555C",
        anchor = {1, 1}, pivot = {1, 1}, offset = {-16, -14} })

    neoui.show_screen { id = "main_menu", tree = tree }

    -- Lua-driven animation: pulse the accent bar width through the
    -- coerced node handle (__node) from an every_frame hook
    local barNode = bar.__node
    local t0 = neoui.time()
    neoui.every_frame(function(dt)
        barNode:setDpWidth(64 + math.sin((neoui.time() - t0) * 2.0) * 14)
    end)
end

return M
