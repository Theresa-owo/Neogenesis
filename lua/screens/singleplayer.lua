-- Singleplayer screen: HeroUI migration of the vanilla "Select World" menu.
-- World rows come from game.worlds() (ApiGame.kt). Selection highlights the
-- row with the HeroUI primary tint; Play launches the integrated server,
-- Create generates a fresh "World_<n>" save (next free index) through
-- game.create_world() and launches it, Delete removes the save and refreshes
-- the list in place.
--
-- Rows are fixed "slots" (count fixed at registration, since node trees are
-- retained); refresh() rewrites slot text/geometry and collapses unused slots
-- (h = 0, empty text) — layout has no visibility-based skip, so collapsing is
-- how rows disappear. The slot list re-syncs from live data every time the
-- screen is (re)opened — see the open-detection watcher at the bottom.

local heroui = require("heroui")

local M = {}

local C = heroui.colors
local R = heroui.radius

local SLOT_COUNT = 10

-- ARGB numerics for retained-node mutation (strings are parsed on conversion,
-- but the live nodes need numbers for the coerced Kotlin setters)
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
    local state = { worlds = {}, selected = nil }

    local watched = {}   -- specs whose __node identity marks an (re)open
    local slots = {}     -- row button specs, index = slot number
    local empty_label, more_label, info_label

    local refresh        -- forward declaration (used in callbacks)

    -- ----------------------------------------------------------------
    -- data
    -- ----------------------------------------------------------------

    local function pull()
        local ok, list = pcall(function() return game.worlds() end)
        state.worlds = (ok and type(list) == "table") and list or {}
    end

    -- rewrite one slot's spec + live node for the given world (or collapse)
    local function paint_slot(i, w, selected)
        local spec = slots[i]
        if not spec then return end
        local n = spec.__node
        if w then
            local txt = w.displayName or w.name or "???"
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
            -- collapse: no visibility-based layout skip exists in UiNode
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
        local count = #state.worlds
        if state.selected and (state.selected < 1 or state.selected > count) then
            state.selected = nil
        end
        if not state.selected and count > 0 then state.selected = 1 end

        for i = 1, SLOT_COUNT do
            paint_slot(i, state.worlds[i], state.selected == i)
        end

        local function set_text(spec, txt)
            spec.text = txt
            local n = spec.__node
            if n then n:setText(txt) end
        end

        set_text(empty_label, count == 0 and "No worlds yet." or "")
        set_text(more_label, count > SLOT_COUNT
                 and string.format("+%d more worlds", count - SLOT_COUNT) or "")

        local sel = state.selected and state.worlds[state.selected] or nil
        local info = ""
        if sel then
            info = string.format("%s · last played %s",
                                 sel.name or "?", sel.lastPlayed or "?")
        end
        set_text(info_label, info)
    end

    -- ----------------------------------------------------------------
    -- actions
    -- ----------------------------------------------------------------

    local function select(i)
        if state.worlds[i] then
            state.selected = i
            refresh()
        end
    end

    local function play()
        local sel = state.selected and state.worlds[state.selected]
        if not sel or not sel.name then return end
        -- launch_world blocks while the world loads; on failure the list stays
        -- open for another pick
        local ok, res = pcall(function() return game.launch_world(sel.name) end)
        if ok and res ~= false then
            pcall(function() neoui.pop() end)
        end
    end

    local function delete_selected()
        local sel = state.selected and state.worlds[state.selected]
        if not sel or not sel.name then return end
        pcall(function() game.delete_world(sel.name) end)
        state.selected = nil
        refresh()
    end

    -- "World_<n>": next free index across the current save list
    local function next_world_name()
        pull()
        local taken = {}
        for _, w in ipairs(state.worlds) do
            if type(w.name) == "string" then taken[w.name] = true end
        end
        for n = 1, 999 do
            local candidate = string.format("World_%d", n)
            if not taken[candidate] then return candidate end
        end
        return nil
    end

    local function create_world()
        local name = next_world_name()
        if not name then return end
        -- create_world re-checks the name is still free, builds vanilla
        -- WorldSettings (survival, structures on, cheats off) and launches
        -- the integrated server — it blocks while the world loads, exactly
        -- like play(); on success pop so the main menu is on top when the
        -- player quits the world
        local ok, res = pcall(function() return game.create_world(name) end)
        if ok and res ~= false then
            pcall(function() neoui.pop() end)
        else
            pcall(function()
                neoui.log("singleplayer: create_world failed for '" .. name .. "'")
            end)
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

    add(heroui.label { text = tr("selectWorld.title", "Select World"), textSize = 30 })
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
        heroui.button { text = tr("selectWorld.select", "Play"), variant = "solid",
                        w = 190,
                        onClick = function() play() end },
        heroui.button { text = tr("selectWorld.create", "Create New World"),
                        variant = "bordered", w = 190,
                        onClick = function() create_world() end },
        heroui.button { text = tr("selectWorld.delete", "Delete"), variant = "bordered",
                        w = 130,
                        onClick = function() delete_selected() end } } }
    add(actions)

    add(heroui.button { text = tr("gui.cancel", "Cancel"), variant = "light", w = "match",
                        onClick = function() neoui.pop() end })

    local tree = { type = "box", children = {} }
    table.insert(tree.children, heroui.card { w = 640, padding = 32,
        anchor = { 0.5, 0.5 }, pivot = { 0.5, 0.5 }, children = { column } })

    neoui.register_screen { id = "singleplayer", tree = tree }

    -- open detection: a fresh __node stamp on any slot means the tree was just
    -- converted (screen opened) — resync the list from live data
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
