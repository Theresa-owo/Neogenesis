-- Multiplayer screen: HeroUI migration of the vanilla server list.
-- Rows come from game.servers() (ApiGame.kt, reads servers.dat); Join hands
-- the selected entry to game.connect_server(index) which opens the vanilla
-- GuiConnecting flow, then pops this screen so the NeoUI list doesn't render
-- over the connection state.
--
-- Same fixed-slot pattern as singleplayer.lua: slots are rewritten and unused
-- ones collapsed (h = 0, empty text) on every refresh; the list re-syncs from
-- live data each time the screen is (re)opened via the watcher below.

local heroui = require("heroui")

local M = {}

local C = heroui.colors
local R = heroui.radius

local SLOT_COUNT = 10

-- ARGB numerics for retained-node mutation (see singleplayer.lua)
local ARGV = {
    sel_fill    = tonumber("1A006FEE", 16), -- HeroUI primaryTint
    sel_text    = tonumber("FF006FEE", 16), -- primary
    sel_border  = tonumber("FF006FEE", 16),
    idle_fill   = 0,
    idle_text   = tonumber("FF1F2126", 16), -- foreground
    idle_border = 0,
}

local function tr(key, fallback)
    local ok, v = pcall(function() return neoui.i18n(key) end)
    if ok and type(v) == "string" and v ~= "" and v ~= key then return v end
    return fallback or key
end

function M.register()
    local state = { servers = {}, selected = nil }

    local watched = {}
    local slots = {}
    local empty_label, more_label, info_label

    local refresh        -- forward declaration (used in callbacks)

    -- ----------------------------------------------------------------
    -- data
    -- ----------------------------------------------------------------

    local function pull()
        local ok, list = pcall(function() return game.servers() end)
        state.servers = (ok and type(list) == "table") and list or {}
    end

    local function paint_slot(i, s, selected)
        local spec = slots[i]
        if not spec then return end
        local n = spec.__node
        if s then
            local txt = s.name or s.ip or "???"
            spec.text = txt
            spec.h = 40
            spec.fillColor = selected and "#1A006FEE" or "#00000000"
            spec.fillEndColor = spec.fillColor
            spec.textColor = selected and C.primary or C.foreground
            spec.borderColor = selected and C.primary or "#00000000"
            if n then
                local fill = selected and ARGV.sel_fill or ARGV.idle_fill
                n:setText(txt)
                n:setDpHeight(40)
                n:setFillColor(fill)
                n:setFillEndColor(fill)
                n:setTextColor(selected and ARGV.sel_text or ARGV.idle_text)
                n:setBorderColor(selected and ARGV.sel_border or ARGV.idle_border)
            end
        else
            spec.text = ""
            spec.h = 0
            spec.fillColor = "#00000000"
            spec.fillEndColor = "#00000000"
            spec.borderColor = "#00000000"
            if n then
                n:setText("")
                n:setDpHeight(0)
                n:setFillColor(ARGV.idle_fill)
                n:setFillEndColor(ARGV.idle_fill)
                n:setBorderColor(ARGV.idle_border)
            end
        end
    end

    refresh = function()
        pull()
        local count = #state.servers
        if state.selected and (state.selected < 1 or state.selected > count) then
            state.selected = nil
        end
        if not state.selected and count > 0 then state.selected = 1 end

        for i = 1, SLOT_COUNT do
            paint_slot(i, state.servers[i], state.selected == i)
        end

        local function set_text(spec, txt)
            spec.text = txt
            local n = spec.__node
            if n then n:setText(txt) end
        end

        set_text(empty_label, count == 0
                 and "No servers saved yet." or "")
        set_text(more_label, count > SLOT_COUNT
                 and string.format("+%d more servers", count - SLOT_COUNT) or "")

        local sel = state.selected and state.servers[state.selected] or nil
        local info = ""
        if sel then
            info = string.format("%s%s", sel.ip or "?",
                                 sel.motd and sel.motd ~= "" and (" · " .. sel.motd) or "")
        end
        set_text(info_label, info)
    end

    -- ----------------------------------------------------------------
    -- actions
    -- ----------------------------------------------------------------

    local function select(i)
        if state.servers[i] then
            state.selected = i
            refresh()
        end
    end

    local function join()
        local sel = state.selected and state.servers[state.selected]
        if not sel then return end
        local idx = sel.index
        if type(idx) ~= "number" then idx = state.selected - 1 end
        -- connect_server opens the vanilla GuiConnecting (its connector thread
        -- takes over from here); pop so the NeoUI list doesn't draw over the
        -- connection state. On failure the disconnect screen / main menu shows.
        local ok, res = pcall(function() return game.connect_server(idx) end)
        if ok and res ~= false then
            pcall(function() neoui.pop() end)
        end
    end

    -- ----------------------------------------------------------------
    -- layout
    -- ----------------------------------------------------------------

    local column = heroui.column { spacing = 12, w = "match", children = {} }
    local function add(node)
        table.insert(column.children, node)
        return node
    end

    add(heroui.label { text = tr("multiplayer.title", "Play Multiplayer"), textSize = 30 })
    add(heroui.label { text = "Neogenesis · Vulkan", textSize = 14,
                       textColor = C.foregroundMuted })
    add(heroui.accent_bar())
    add(heroui.spacer(6))

    local list = heroui.column { spacing = 8, w = "match", children = {} }
    for i = 1, SLOT_COUNT do
        local spec = heroui.button { text = "", variant = "light",
                                     textColor = C.foreground, w = "match", h = 40,
                                     radius = R.small,
                                     onClick = function() select(i) end }
        slots[i] = spec
        table.insert(watched, spec)
        table.insert(list.children, spec)
    end
    add(list)

    empty_label = heroui.label { text = "", textSize = 15, w = "match",
                                 textColor = C.foregroundMuted }
    add(empty_label)

    info_label = heroui.label { text = "", textSize = 13, w = "match",
                                textColor = C.foregroundMuted }
    add(info_label)

    more_label = heroui.label { text = "", textSize = 13, w = "match",
                                textColor = C.foregroundMuted }
    add(more_label)

    add(heroui.spacer(8))

    local actions = heroui.row { w = "match", children = {
        heroui.button { text = tr("selectServer.select", "Join Server"), variant = "solid",
                        w = 220,
                        onClick = function() join() end },
        heroui.button { text = tr("menu.refresh", "Refresh"), variant = "bordered",
                        w = 140,
                        onClick = function() refresh() end } } }
    add(actions)

    add(heroui.button { text = tr("gui.cancel", "Cancel"), variant = "light", w = "match",
                        onClick = function() neoui.pop() end })

    local tree = { type = "box", children = {} }
    table.insert(tree.children, heroui.card { w = 640, padding = 32,
        anchor = { 0.5, 0.5 }, pivot = { 0.5, 0.5 }, children = { column } })

    neoui.register_screen { id = "multiplayer", tree = tree }

    -- open detection: fresh __node stamps mean the tree was just converted
    neoui.every_frame(function()
        local dirty = false
        for _, s in ipairs(watched) do
            if s.__node ~= s.__seen then
                s.__seen = s.__node
                dirty = true
            end
        end
        if dirty then pcall(refresh) end
    end)

    pcall(refresh)
end

-- self-register on require (init.lua only needs the require line); calling
-- M.register() again is safe, it simply re-registers the screen id
M.register()

return M
