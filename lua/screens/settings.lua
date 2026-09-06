-- Settings screen: HeroUI migration of the vanilla options menu.
-- Registered under "settings" AND "options" (the main menu opens "options").
--
-- Rows are built from a static descriptor list; live values are pulled from
-- game.settings_list() and every change writes through to GameSettings
-- immediately via game.set_option() (sliders deliver the resolved number in
-- the slider's [min,max] domain, toggles deliver a bool). Values re-sync from
-- the engine whenever the screen is (re)opened — see the open-detection
-- watcher at the bottom.
--
-- Layout guardrails: the engine has no scrolling and a wrap card grows until
-- it overflows the screen, so the seven rows are split into two pages living
-- in a fixed-height page area. Both pages are overlaid top-left; the hidden
-- one is setVisible(false), which the renderer AND the input hit-tester both
-- skip (layout still reserves its space, hence the fixed-height area).

local heroui = require("heroui")

local M = {}

local C = heroui.colors
local R = heroui.radius

-- i18n with fallback (vanilla I18n.format returns the key itself when missing)
local function tr(key, fallback)
    local ok, v = pcall(function() return neoui.i18n(key) end)
    if ok and type(v) == "string" and v ~= "" and v ~= key then return v end
    return fallback or key
end

-- curated settings subset (keys/domains match ApiGame.kt)
-- page 1 = sliders, page 2 = cycle + toggles
local DEFS = {
    { key = "fov",             lk = "options.fov",            kind = "slider", min = 30, max = 110, step = 1,    value = 70,   page = 1 },
    { key = "music",           lk = "options.music",          kind = "slider", min = 0,  max = 1,   step = 0.05, value = 1,    page = 1 },
    { key = "sound",           lk = "options.sounds",         kind = "slider", min = 0,  max = 1,   step = 0.05, value = 1,    page = 1 },
    { key = "max_fps",         lk = "options.framerateLimit", kind = "slider", min = 30, max = 260, step = 5,    value = 120,  page = 1 },
    { key = "gui_scale",       lk = "options.guiScale",       kind = "cycle",                                  value = 0,    page = 2 },
    { key = "vsync",           lk = "options.vsync",          kind = "toggle",                                 value = true, page = 2 },
    { key = "smooth_graphics", lk = "options.graphics",       kind = "toggle",                                 value = true, page = 2 },
}

local PAGE_COUNT   = 2
local PAGE_ROWS    = 4                                   -- longest page
local PAGE_AREA_H  = PAGE_ROWS * 44 + (PAGE_ROWS - 1) * 12
local SLIDER_W     = 240
local CYCLE_LABELS = { "Auto", "1x", "2x", "3x" }

function M.register()
    local state = { values = {}, labels = {}, cycle_labels = nil, page = 1 }
    local handles = {}       -- key -> { def, title, value = label spec, widget, segments }
    local page_cols = {}     -- [page] -> page column spec (watched for (re)open)
    local pager_btns = {}    -- [page] -> pager button spec

    local refresh            -- forward declarations (used in callbacks)
    local set_page

    -- keep the engine value -> display string mapping in one place
    local function fmt(def, v)
        if def.kind == "toggle" then
            return v and tr("options.on", "ON") or tr("options.off", "OFF")
        end
        if def.kind == "cycle" then
            local labels = state.cycle_labels or CYCLE_LABELS
            if type(v) == "number" and labels[math.floor(v) + 1] then
                return labels[math.floor(v) + 1]
            end
            return tostring(v)
        end
        if type(v) ~= "number" then return tostring(v) end
        if def.min == 0 and def.max == 1 then
            return string.format("%d%%", math.floor(v * 100 + 0.5))
        end
        return string.format("%d", v)
    end

    -- rewrite a spec's text + its retained node (if already converted)
    local function set_text(spec, txt)
        spec.text = txt
        local n = spec.__node
        if n then n:setText(txt) end
    end

    -- repaint a segmented/pager button for its active/inactive state
    local function paint_choice(seg, active)
        seg.variant = active and "solid" or "light"
        seg.fillColor = active and C.primary or "#00000000"
        seg.fillEndColor = seg.fillColor
        seg.textColor = active and C.primaryForeground or C.primary
        seg.borderColor = "#00000000"
        local n = seg.__node
        if n then
            n:setFillColor(heroui.argb(seg.fillColor))
            n:setFillEndColor(heroui.argb(seg.fillEndColor))
            n:setBorderColor(heroui.argb(seg.borderColor))
            n:setTextColor(heroui.argb(seg.textColor))
        end
    end

    -- push a live value into a retained slider/toggle: the heroui components
    -- only repaint themselves on user interaction, so refresh mirrors their
    -- internal apply() through the node handles they expose on the spec
    local function sync_widget(h, v)
        local w = h.widget
        if not w then return end
        w.value = v
        if h.def.kind == "slider" then
            local frac = 0
            if (w.max or 0) > (w.min or 0) then
                frac = (v - w.min) / (w.max - w.min)
                if frac < 0 then frac = 0 elseif frac > 1 then frac = 1 end
            end
            local fill, thumb = w.__fill, w.__thumb
            if fill then
                fill.w = frac * SLIDER_W
                local n = fill.__node
                if n then n:setDpWidth(frac * SLIDER_W) end
            end
            if thumb then
                thumb.offset = { frac * SLIDER_W, 0 }
                local n = thumb.__node
                if n then n:setOffsetX(frac * SLIDER_W) end
            end
        elseif h.def.kind == "toggle" then
            local pill, knob = w.__pill, w.__knob
            if pill then
                pill.fillColor = v and C.primary or C.surface3
                pill.fillEndColor = pill.fillColor
                pill.borderColor = "#00000000"
                local n = pill.__node
                if n then
                    n:setFillColor(heroui.argb(pill.fillColor))
                    n:setFillEndColor(heroui.argb(pill.fillEndColor))
                    n:setBorderColor(heroui.argb(pill.borderColor))
                end
            end
            if knob then
                knob.borderColor = v and "#00000000" or "#22000000"
                local n = knob.__node
                if n then n:setBorderColor(heroui.argb(knob.borderColor)) end
            end
        end
    end

    local function build_row(def)
        local title = heroui.label { text = tr(def.lk), textSize = 15, w = 168 }

        -- neutral 240x44 slot so geometry stays identical whichever widget
        -- lands inside (children of a "box" are centered by their anchor)
        local slot = { type = "box", w = 240, h = 44,
                       fillColor = "#00000000", fillEndColor = "#00000000",
                       borderColor = "#00000000", shadow = false, children = {} }

        local widget, segments
        if def.kind == "slider" then
            widget = heroui.slider { w = SLIDER_W, value = def.value,
                                     min = def.min, max = def.max, step = def.step,
                                     onChange = function(v)
                                         pcall(function() game.set_option(def.key, v) end)
                                         refresh()
                                     end }
        elseif def.kind == "toggle" then
            widget = heroui.toggle { value = def.value,
                                     onChange = function(b)
                                         pcall(function() game.set_option(def.key, b) end)
                                         refresh()
                                     end }
        else -- cycle: HeroUI segmented control, one small button per value
            segments = {}
            local seg_row = heroui.row { w = SLIDER_W, spacing = 4, children = {} }
            local labels = state.cycle_labels or CYCLE_LABELS
            for i = 1, 4 do
                local seg = heroui.button { text = labels[i] or tostring(i - 1),
                                            w = 57, h = 36, textSize = 13,
                                            variant = "light", radius = R.small,
                                            onClick = function()
                                                pcall(function() game.set_option(def.key, i - 1) end)
                                                refresh()
                                            end }
                segments[i] = seg
                table.insert(seg_row.children, seg)
            end
            widget = seg_row
        end
        slot.children = { widget }

        local value_label = heroui.label { text = "", textSize = 13, w = 64,
                                           textColor = C.foregroundMuted }

        handles[def.key] = { def = def, title = title, value = value_label,
                             widget = widget, segments = segments }
        return heroui.row { w = "match", children = { title, slot, value_label } }
    end

    refresh = function()
        -- pull live values from the engine (kept if the module is missing)
        local ok, list = pcall(function() return game.settings_list() end)
        if ok and type(list) == "table" then
            for _, d in ipairs(list) do
                if type(d) == "table" and d.key then
                    state.values[d.key] = d.value
                    if type(d.label) == "string" and d.label ~= "" then
                        state.labels[d.key] = d.label
                    end
                    if d.kind == "cycle" and type(d.values) == "table" then
                        local labels = {}
                        for i, lbl in ipairs(d.values) do labels[i] = tostring(lbl) end
                        if #labels > 0 then state.cycle_labels = labels end
                    end
                end
            end
        end
        if not state.cycle_labels then state.cycle_labels = CYCLE_LABELS end

        for _, def in ipairs(DEFS) do
            local h = handles[def.key]
            if h then
                local v = state.values[def.key]
                if v == nil then v = h.def.value end
                -- row title prefers the live I18n label from settings_list
                set_text(h.title, state.labels[def.key] or tr(h.def.lk))
                -- right-hand live value label
                set_text(h.value, fmt(h.def, v))
                if h.def.kind == "cycle" then
                    local labels = state.cycle_labels or CYCLE_LABELS
                    local cur = (type(v) == "number") and math.floor(v) or 0
                    if cur < 0 or cur > 3 then cur = 0 end
                    for i, seg in ipairs(h.segments or {}) do
                        set_text(seg, labels[i] or tostring(i - 1))
                        paint_choice(seg, i == cur + 1)
                    end
                else
                    sync_widget(h, v)
                end
            end
        end
    end

    set_page = function(p)
        if p < 1 or p > PAGE_COUNT then return end
        state.page = p
        for i, col in ipairs(page_cols) do
            local vis = (i == p)
            col.visible = vis
            local n = col.__node
            if n then n:setVisible(vis) end
        end
        for i, btn in ipairs(pager_btns) do
            paint_choice(btn, i == p)
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

    add(heroui.label { text = tr("menu.options", "Options"), textSize = 30 })
    add(heroui.label { text = "Neogenesis · Vulkan", textSize = 14,
                       textColor = C.foregroundMuted })
    add(heroui.accent_bar())
    add(heroui.spacer(8))

    -- pager: the active page is the solid button
    local pager = heroui.row { spacing = 8, children = {} }
    for p = 1, PAGE_COUNT do
        local btn = heroui.button { text = string.format("Page %d/%d", p, PAGE_COUNT),
                                    w = 120, h = 34, textSize = 13,
                                    variant = "solid", radius = R.small,
                                    onClick = function() set_page(p) end }
        pager_btns[p] = btn
        table.insert(pager.children, btn)
    end
    add(pager)

    -- fixed-height page area: both pages overlaid top-left, exactly one
    -- visible — the area never changes height, so the card never reflows
    local page1 = heroui.column { spacing = 12, w = "match",
                                  anchor = { 0, 0 }, pivot = { 0, 0 }, children = {} }
    local page2 = heroui.column { spacing = 12, w = "match",
                                  anchor = { 0, 0 }, pivot = { 0, 0 }, children = {} }
    for _, def in ipairs(DEFS) do
        table.insert(((def.page == 2) and page2 or page1).children, build_row(def))
    end
    page_cols = { page1, page2 }
    set_page(1)  -- paint pager variants + spec visibility before first conversion

    local area = { type = "box", w = "match", h = PAGE_AREA_H,
                   fillColor = "#00000000", fillEndColor = "#00000000",
                   borderColor = "#00000000", shadow = false,
                   children = { page1, page2 } }
    add(area)

    add(heroui.spacer(10))
    add(heroui.button { text = tr("gui.done", "Done"), variant = "solid", w = "match",
                        onClick = function() neoui.pop() end })

    local tree = { type = "box", children = {} }
    table.insert(tree.children, heroui.card { w = 560, padding = 28,
        anchor = { 0.5, 0.5 }, pivot = { 0.5, 0.5 }, children = { column } })

    -- main menu opens "options"; the canonical id is "settings"
    neoui.register_screen { id = "settings", tree = tree }
    neoui.register_screen { id = "options", tree = tree }

    -- open detection: nodeFromLua stamps a fresh __node on our spec tables each
    -- time the screen is pushed — identity change means "just opened", so
    -- re-apply the page and sync the widgets with live engine values before
    -- the first render
    neoui.every_frame(function()
        local dirty = false
        for _, s in ipairs(page_cols) do
            if s.__node ~= s.__seen then
                s.__seen = s.__node
                dirty = true
            end
        end
        if dirty then
            pcall(function() set_page(state.page) end)
            pcall(refresh)
        end
    end)

    pcall(refresh)
end

-- self-register on require (init.lua only needs the require line); calling
-- M.register() again is safe, it simply re-registers the screen ids
M.register()

return M
