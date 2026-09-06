-- Settings screen: modern categorized layout (OneConfig-inspired, original
-- execution). Registered under "settings" AND "options" (the main menu opens
-- "options").
--
-- Structure: one large glass card (1100dp) -> topbar (title + search field),
-- body split (220dp category sidebar | scrollable settings content), footer
-- with a full-width Done button. All five categories are built up front into
-- ONE heroui.scrollarea; only the selected category's wrapper is visible, the
-- others are collapsed (setVisible(false) + setDpHeight(0) -> they occupy no
-- layout space; invisible nodes skip render + hit-test but still occupy
-- layout, hence the explicit height collapse).
--
-- SILK: switching categories animates the new content root through its
-- coerced __node — offsetY slides 10 -> 0 and every descendant's fill/border/
-- text alpha fades 0 -> original over 140ms (ease-out cubic). Original colors
-- are captured per node the first time they are faded, so repeated fades
-- always restore the true base colors.
--
-- SEARCH: the topbar textfield filters the CURRENT category by label/key
-- (substring, case-insensitive). Non-matching rows collapse to zero height so
-- the column reflows tightly (the scroll content column uses spacing 0; row
-- slots carry their own 12dp bottom gap). An empty query restores everything.
--
-- Engine facts used (see UiNode.kt / LuaUiRuntime.kt / heroui.lua):
--   * layoutFullscreen runs every frame -> runtime setDpHeight/setVisible
--     reflow on the next frame; spec tables are kept in sync for re-opens.
--   * retained trees re-convert only on open; all runtime edits go through
--     the coerced __node handles.
--   * the scroll content is sized so every category fits the viewport
--     (longest = 592dp <= 600dp): scrollY stays 0, which keeps the heroui
--     sliders' Lua-side layout-coordinate hit-tests exact. The scrollarea is
--     still fully wired — if content ever exceeds the viewport the wheel
--     scrolls it (engine clip transform), matching heroui's contract.
--   * spec.clip is not propagated by the Lua converter, so the scroll box's
--     clip flag is applied here through the coerced node on every open.

local heroui = require("heroui")

local M = {}

local C = heroui.colors
local R = heroui.radius

-- --------------------------------------------------------------------
-- metrics
-- --------------------------------------------------------------------

local CARD_W    = 1100
local TOP_H     = 72
local TOP_PAD   = 28
local NAV_W     = 220
local BODY_H    = 656
local SCROLL_H  = 600          -- viewport; tallest category is 592dp
local ROW_H     = 56           -- 44dp control + 12dp gap
local SUB_H     = 44           -- section subheader slot
local NOTE_H    = 44           -- muted note slot
local SLIDER_W  = 300
local SEG_W     = 68
local SEG_H     = 36
local FADE_S    = 0.14         -- category transition duration (140ms)

-- --------------------------------------------------------------------
-- i18n + color helpers
-- --------------------------------------------------------------------

local function tr(key, fallback)
    if key then
        local ok, v = pcall(function() return neoui.i18n(key) end)
        if ok and type(v) == "string" and v ~= "" and v ~= key then return v end
    end
    return fallback or key or ""
end

-- ARGB <-> (rgb, alpha) split without bitwise ops (Lua 5.1 doubles)
local function splitColor(c)
    local u = tonumber(c) or 0
    if u < 0 then u = u + 4294967296 end
    local a = math.floor(u / 16777216)
    return u - a * 16777216, a
end

local function withAlpha(rgb, a)
    local n = math.floor(a + 0.5) * 16777216 + rgb
    if n >= 2147483648 then n = n - 4294967296 end
    return n
end

-- --------------------------------------------------------------------
-- value formatters (vanilla GuiOptions parity where it makes sense)
-- --------------------------------------------------------------------

local function fmtPercent(v) return string.format("%d%%", math.floor(v * 100 + 0.5)) end

local FORMATTERS = {
    percent = fmtPercent,
    fps = function(v)
        if math.floor(v + 0.5) == 0 then return tr("options.framerateLimit.max", "Unlimited") end
        return string.format("%d fps", math.floor(v + 0.5))
    end,
    fov = function(v)
        v = math.floor(v + 0.5)
        if v == 70 then return tr("options.fov.min", "Normal") end
        if v == 110 then return tr("options.fov.max", "Quake Pro") end
        return tostring(v)
    end,
    gamma = function(v)
        if v <= 0.005 then return tr("options.gamma.min", "Dark") end
        if v >= 0.995 then return tr("options.gamma.max", "Bright") end
        return "+" .. fmtPercent(v)
    end,
    sens = function(v)
        if v <= 0.005 then return tr("options.sensitivity.min", "*yawn*") end
        if v >= 0.995 then return tr("options.sensitivity.max", "HYPERSPEED!!!") end
        return string.format("%d%%", math.floor(v * 200 + 0.5)) -- vanilla 200% scale
    end,
    chunks = function(v) return string.format("%d chunks", math.floor(v + 0.5)) end,
    mipmap = function(v)
        v = math.floor(v + 0.5)
        if v == 0 then return tr("options.off", "OFF") end
        return tostring(v)
    end,
    px = function(v) return string.format("%d px", math.floor(v + 0.5)) end,
}

local function formatValue(def, v)
    local f = def.fmt and FORMATTERS[def.fmt]
    if f then
        local ok, s = pcall(f, tonumber(v) or 0)
        if ok then return tostring(s) end
    end
    return tostring(v)
end

-- --------------------------------------------------------------------
-- settings model (keys/domains match ApiGame.kt; live values + i18n labels
-- arrive from game.settings_list() on every open/change)
-- --------------------------------------------------------------------

local CATS = {
    { id = "game", label = "Game", sections = {
        { title = "DISPLAY", rows = {
            { key = "fov",             lk = "options.fov",            fb = "FOV",             kind = "slider", min = 30, max = 110, step = 1,   fmt = "fov" },
            { key = "gui_scale",       lk = "options.guiScale",       fb = "GUI Scale",       kind = "cycle",  values = { "Auto", "Small", "Normal", "Large" } },
            { key = "max_fps",         lk = "options.framerateLimit", fb = "Max Framerate",   kind = "slider", min = 0,  max = 260, step = 5,   fmt = "fps" },
            { key = "vsync",           lk = "options.vsync",          fb = "VSync",           kind = "toggle" },
        } },
        { title = "GAMEPLAY", rows = {
            { key = "smooth_graphics",   lk = "options.graphics",          fb = "Smooth Graphics",     kind = "toggle" },
            { key = "view_bobbing",      lk = "options.viewBobbing",       fb = "View Bobbing",        kind = "toggle" },
            { key = "particles",         lk = "options.particles",         fb = "Particles",           kind = "cycle", values = { "All", "Decreased", "Minimal" } },
            { key = "pause_on_lost_focus", lk = nil,                       fb = "Pause on Lost Focus", kind = "toggle" },
            { key = "reduced_debug",     lk = "options.reducedDebugInfo",  fb = "Reduced Debug Info",  kind = "toggle" },
        } },
    } },
    { id = "video", label = "Video", sections = {
        { title = "WORLD", rows = {
            { key = "render_distance", lk = "options.renderDistance", fb = "Render Distance", kind = "slider", min = 2, max = 16, step = 1, fmt = "chunks" },
            { key = "brightness",      lk = "options.gamma",          fb = "Brightness",      kind = "slider", min = 0, max = 1,  step = 0.05, fmt = "gamma" },
            { key = "clouds",          lk = "options.renderClouds",   fb = "Clouds",          kind = "cycle",  values = { "Off", "Fast", "Fancy" } },
        } },
        { title = "RENDERING", rows = {
            { key = "mipmap",        lk = "options.mipmapLevels",    fb = "Mipmap Levels",   kind = "slider", min = 0, max = 4, step = 1, fmt = "mipmap" },
            { key = "anaglyph",      lk = "options.anaglyph",        fb = "Anaglyph 3D",     kind = "toggle" },
            { key = "fbo",           lk = "options.fboEnable",       fb = "FBOs",            kind = "toggle" },
            { key = "vbo",           lk = "options.vbo",             fb = "VBOs",            kind = "toggle" },
            { key = "entity_shadows", lk = "options.entityShadows",  fb = "Entity Shadows",  kind = "toggle" },
            { key = "fullscreen",    lk = "options.fullscreen",      fb = "Fullscreen",      kind = "toggle" },
        } },
    } },
    { id = "sound", label = "Sound", sections = {
        { title = "VOLUME", rows = {
            { key = "vol_master",  lk = "soundCategory.master",  fb = "Master",          kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_music",   lk = "soundCategory.music",   fb = "Music",           kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_records", lk = "soundCategory.record",  fb = "Records",         kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_weather", lk = "soundCategory.weather", fb = "Weather",         kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_blocks",  lk = "soundCategory.block",   fb = "Blocks",          kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_hostile", lk = "soundCategory.hostile", fb = "Hostile Mobs",    kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_animals", lk = "soundCategory.neutral", fb = "Animals",         kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_players", lk = "soundCategory.player",  fb = "Players",         kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
            { key = "vol_ambient", lk = "soundCategory.ambient", fb = "Ambient",         kind = "slider", min = 0, max = 1, step = 0.05, fmt = "percent" },
        } },
    } },
    { id = "chat", label = "Chat", sections = {
        { title = "LAYOUT", rows = {
            { key = "chat_visibility",         lk = "options.chat.visibility",         fb = "Chat Visibility",         kind = "cycle",  values = { "Full", "Compact", "Hidden" } },
            { key = "chat_opacity",            lk = "options.chat.opacity",            fb = "Chat Opacity",            kind = "slider", min = 0, max = 1,   step = 0.05, fmt = "percent" },
            { key = "chat_scale",              lk = "options.chat.scale",              fb = "Chat Scale",              kind = "slider", min = 0, max = 1,   step = 0.05, fmt = "percent" },
            { key = "chat_width",              lk = "options.chat.width",              fb = "Chat Width",              kind = "slider", min = 40, max = 320, step = 1,  fmt = "px" },
            { key = "chat_height_focused",     lk = "options.chat.height.focused",     fb = "Chat Height (Focused)",   kind = "slider", min = 20, max = 180, step = 1,  fmt = "px" },
            { key = "chat_height_unfocused",   lk = "options.chat.height.unfocused",   fb = "Chat Height (Unfocused)", kind = "slider", min = 20, max = 180, step = 1,  fmt = "px" },
        } },
        { title = "BEHAVIOR", rows = {
            { key = "chat_colors",       lk = "options.chat.color",        fb = "Chat Colors",  kind = "toggle" },
            { key = "chat_links",        lk = "options.chat.links",        fb = "Chat Links",   kind = "toggle" },
            { key = "chat_links_prompt", lk = "options.chat.links.prompt", fb = "Link Prompt",  kind = "toggle" },
        } },
    } },
    { id = "controls", label = "Controls", sections = {
        { title = "MOUSE", rows = {
            { key = "invert_mouse",      lk = "options.invertMouse",      fb = "Invert Mouse",      kind = "toggle" },
            { key = "mouse_sensitivity", lk = "options.sensitivity",      fb = "Mouse Sensitivity", kind = "slider", min = 0, max = 1, step = 0.05, fmt = "sens" },
            { key = "touchscreen",       lk = "options.touchscreen",      fb = "Touchscreen",       kind = "toggle" },
        } },
        { title = "GENERAL", rows = {
            { key = "force_unicode_font", lk = "options.forceUnicodeFont", fb = "Force Unicode Font", kind = "toggle" },
        } },
        note = "Key bind remapping coming soon",
    } },
}

-- --------------------------------------------------------------------
-- screen module state
-- --------------------------------------------------------------------

function M.register()
    local state = { cat = 1, query = "", values = {}, labels = {}, cycle_values = {} }
    local cats = {}          -- [i] -> { def, wrap, col, h, sections, btn }
    local handles = {}       -- key -> row rec (widget-bearing)
    local scrollSpec, scrollSeen
    local emptyRec           -- "no matching settings" slot (collapses to 0)
    local trans = nil        -- active category transition

    local refresh            -- forward declarations
    local applyFilter
    local applyCategory
    local beginTransition

    -- ----------------------------------------------------------------
    -- small spec/node helpers
    -- ----------------------------------------------------------------

    local function set_text(spec, txt)
        spec.text = txt
        local n = spec.__node
        if n then n:setText(txt) end
    end

    local function invisibleBox(spec)
        spec.fillColor = "#00000000"; spec.fillEndColor = "#00000000"
        spec.borderColor = "#00000000"
        spec.shadow = false; spec.textShadow = false
        return spec
    end

    -- visibility + height collapse (invisible nodes still occupy layout)
    local function setSlotVisible(rec, vis)
        local h = vis and rec.h or 0
        rec.row.visible = vis
        rec.row.h = h
        local n = rec.row.__node
        if n then
            n:setVisible(vis)
            n:setDpHeight(h)
        end
    end

    -- solid-primary vs light-muted button paint (sidebar + segments)
    local function paintButton(seg, active, inactiveText)
        seg.fillColor = active and C.primary or "#00000000"
        seg.fillEndColor = seg.fillColor
        seg.textColor = active and C.primaryForeground or (inactiveText or C.foreground)
        seg.borderColor = "#00000000"
        local n = seg.__node
        if n then
            n:setFillColor(heroui.argb(seg.fillColor))
            n:setFillEndColor(heroui.argb(seg.fillEndColor))
            n:setBorderColor(heroui.argb(seg.borderColor))
            n:setTextColor(heroui.argb(seg.textColor))
        end
    end

    -- push a live engine value into a retained slider/toggle (mirrors the
    -- heroui components' internal apply() through their exposed sub-specs)
    local function sync_widget(rec, v)
        local w = rec.widget
        if not w then return end
        if rec.def.kind == "slider" then
            v = tonumber(v) or w.min or 0
            w.value = v
            local trackW = SLIDER_W - 76 -- heroui: w - 64 (value label) - 12 (thumb clear)
            local frac = 0
            if (w.max or 0) > (w.min or 0) then
                frac = (v - w.min) / (w.max - w.min)
                if frac < 0 then frac = 0 elseif frac > 1 then frac = 1 end
            end
            local fill, thumb = w.__fill, w.__thumb
            if fill then
                fill.w = frac * trackW
                local n = fill.__node
                if n then n:setDpWidth(frac * trackW) end
            end
            if thumb then
                thumb.offset = { frac * trackW, 0 }
                local n = thumb.__node
                if n then n:setOffsetX(frac * trackW) end
            end
            if w.__valLabel and w.format then
                local ok, s = pcall(w.format, v)
                if ok then
                    local txt = tostring(s)
                    w.__valLabel.text = txt
                    local n = w.__valLabel.__node
                    if n and n:getText() ~= txt then n:setText(txt) end
                end
            end
        elseif rec.def.kind == "toggle" then
            v = (tonumber(v) or 0) ~= 0
            w.value = v
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

    -- ----------------------------------------------------------------
    -- row builders
    -- ----------------------------------------------------------------

    local function setOption(key, value)
        pcall(function() game.set_option(key, value) end)
        refresh()
    end

    local function build_row(def)
        local title = heroui.label {
            text = tr(def.lk, def.fb), w = "wrap", textSize = 21,
            textColor = C.foreground,
            anchor = { 0, 0.5 }, pivot = { 0, 0.5 },
        }
        local row = invisibleBox { type = "box", w = "match", h = ROW_H, children = { title } }
        local rec = { def = def, row = row, title = title, h = ROW_H,
                      match = ((def.fb or "") .. " " .. (def.key or "")):lower() }

        if def.kind == "slider" then
            local widget = heroui.slider {
                w = SLIDER_W, value = def.min, min = def.min, max = def.max, step = def.step,
                format = function(v) return formatValue(def, v) end,
                onChange = function(v) setOption(def.key, v) end,
            }
            widget.anchor = { 1, 0.5 }; widget.pivot = { 1, 0.5 }
            rec.widget = widget
            table.insert(row.children, widget)
        elseif def.kind == "toggle" then
            local widget = heroui.toggle {
                value = false,
                onChange = function(b) setOption(def.key, b and 1 or 0) end,
            }
            widget.anchor = { 1, 0.5 }; widget.pivot = { 1, 0.5 }
            rec.widget = widget
            table.insert(row.children, widget)
        else -- cycle: HeroUI segmented control, one small button per value
            local segRow = heroui.row { w = "wrap", spacing = 4, children = {} }
            segRow.anchor = { 1, 0.5 }; segRow.pivot = { 1, 0.5 }
            rec.segments = {}
            for i = 1, #(def.values or {}) do
                local seg = heroui.button {
                    text = (def.values or {})[i] or tostring(i - 1),
                    w = SEG_W, h = SEG_H, textSize = 15, radius = R.small,
                    variant = "light",
                    onClick = function() setOption(def.key, i - 1) end,
                }
                rec.segments[i] = seg
                paintButton(seg, false, C.foreground)
                table.insert(segRow.children, seg)
            end
            rec.widget = segRow
            table.insert(row.children, segRow)
        end

        handles[def.key] = rec
        return rec
    end

    local function build_note(text)
        local lbl = heroui.label {
            text = text, w = "wrap", textSize = 16, bold = false,
            textColor = C.foregroundMuted,
            anchor = { 0, 0.5 }, pivot = { 0, 0.5 },
        }
        local row = invisibleBox { type = "box", w = "match", h = NOTE_H, children = { lbl } }
        return { def = {}, row = row, title = lbl, h = NOTE_H, note = true,
                 match = (text or ""):lower() }
    end

    -- ----------------------------------------------------------------
    -- build all categories into one scrollarea
    -- ----------------------------------------------------------------

    local scrollChildren = {}

    for ci, catDef in ipairs(CATS) do
        local catRec = { def = catDef, sections = {}, h = 0 }
        local colChildren = {}

        for _, secDef in ipairs(catDef.sections) do
            local sec = { rows = {} }
            if secDef.title then
                local hdr = heroui.label {
                    text = secDef.title, w = "wrap", textSize = 15, bold = true,
                    textColor = C.foregroundMuted, letterSpacing = 1.5,
                    anchor = { 0, 0.5 }, pivot = { 0, 0.5 },
                }
                local hrow = invisibleBox { type = "box", w = "match", h = SUB_H, children = { hdr } }
                sec.header = { row = hrow, h = SUB_H }
                sec.headerRec = { row = hrow, h = SUB_H }
                table.insert(colChildren, hrow)
                catRec.h = catRec.h + SUB_H
            end
            for _, def in ipairs(secDef.rows) do
                local rec = build_row(def)
                rec.hdr = sec.header
                table.insert(sec.rows, rec)
                table.insert(colChildren, rec.row)
                catRec.h = catRec.h + ROW_H
            end
            table.insert(catRec.sections, sec)
        end
        if catDef.note then
            local rec = build_note(catDef.note)
            rec.noteCat = true
            table.insert(colChildren, rec.row)
            catRec.h = catRec.h + NOTE_H
            -- notes ride with the last section for filtering
            local lastSec = catRec.sections[#catRec.sections]
            if lastSec then table.insert(lastSec.rows, rec) end
        end

        local col = heroui.column {
            w = "match", h = "wrap", spacing = 0, gravity = "start",
            anchor = { 0, 0 }, pivot = { 0, 0 },
            children = colChildren,
        }
        local wrap = invisibleBox {
            type = "box", w = "match", h = catRec.h,
            visible = (ci == 1),
            children = { col },
        }
        catRec.wrap = wrap
        catRec.col = col
        table.insert(cats, catRec)
        table.insert(scrollChildren, wrap)
    end

    -- "no results" slot, collapsed unless the active filter hides everything
    do
        local lbl = heroui.label {
            text = "No matching settings", w = "wrap", textSize = 16, bold = false,
            textColor = C.foregroundMuted, anchor = { 0, 0.5 }, pivot = { 0, 0.5 },
        }
        local row = invisibleBox { type = "box", w = "match", h = 0, visible = false, children = { lbl } }
        emptyRec = { row = row, h = ROW_H }
        table.insert(scrollChildren, row)
    end

    scrollSpec = heroui.scrollarea {
        w = "match", h = SCROLL_H, step = 48, spacing = 0,
        children = scrollChildren,
    }

    -- ----------------------------------------------------------------
    -- search filter (current category; collapsing slots keep the flow tight)
    -- ----------------------------------------------------------------

    local function trim(s)
        s = tostring(s or ""):gsub("^%s+", ""):gsub("%s+$", "")
        return s
    end

    applyFilter = function()
        local q = trim(state.query):lower()
        local catRec = cats[state.cat]
        if not catRec then return end
        local visH = 0

        for _, sec in ipairs(catRec.sections) do
            local any = false
            for _, rec in ipairs(sec.rows) do
                local match
                if rec.noteCat then
                    match = (q == "") -- notes are not settings; hide while filtering
                elseif q == "" then
                    match = true
                else
                    local label = (rec.title.text or ""):lower()
                    match = label:find(q, 1, true) ~= nil
                         or rec.match:find(q, 1, true) ~= nil
                end
                setSlotVisible(rec, match)
                if match then
                    any = true
                    visH = visH + rec.h
                end
            end
            if sec.headerRec then
                setSlotVisible(sec.headerRec, any)
                if any then visH = visH + sec.headerRec.h end
            end
        end

        local none = (visH == 0)
        setSlotVisible(emptyRec, none)
        if none then visH = visH + emptyRec.h end

        catRec.wrap.visible = true
        catRec.wrap.h = visH
        local wn = catRec.wrap.__node
        if wn then
            wn:setVisible(true) -- re-show after a previous deselect on this tree
            wn:setDpHeight(visH)
        end

        -- a fresh filter reads from the top
        local sr = scrollSpec.__node
        if sr then pcall(function() sr:setScrollY(0) end) end
    end

    -- ----------------------------------------------------------------
    -- category switching
    -- ----------------------------------------------------------------

    applyCategory = function(animate)
        for i, catRec in ipairs(cats) do
            local sel = (i == state.cat)
            local w = catRec.wrap
            if not sel then
                w.visible = false
                w.h = 0
                local n = w.__node
                if n then n:setVisible(false); n:setDpHeight(0) end
            end
            paintButton(catRec.btn, sel, C.foregroundMuted)
        end
        applyFilter() -- heights + visibility for the selected category

        local sr = scrollSpec.__node
        if sr then pcall(function() sr:setScrollY(0) end) end
        if animate then beginTransition() end
    end

    -- ----------------------------------------------------------------
    -- SILK: 140ms fade+slide on the incoming category's content
    -- ----------------------------------------------------------------

    local colorCache = {}
    local function recordNode(node)
        local rec = colorCache[node]
        if not rec then
            local fr, fa = splitColor(node:getFillColor())
            local fer, fea = splitColor(node:getFillEndColor())
            local br, ba = splitColor(node:getBorderColor())
            local tr2, ta = splitColor(node:getTextColor())
            rec = { fr = fr, fa = fa, fer = fer, fea = fea, br = br, ba = ba, tr = tr2, ta = ta }
            colorCache[node] = rec
        end
        return rec
    end

    local function collectNodes(root)
        local out = {}
        local function walk(n)
            table.insert(out, { node = n, rec = recordNode(n) })
            local ok, kids = pcall(function() return n:getChildren() end)
            if ok and kids then
                for i = 0, kids:size() - 1 do walk(kids:get(i)) end
            end
        end
        walk(root)
        return out
    end

    local function paintAlpha(entry, t)
        local n, rec = entry.node, entry.rec
        n:setFillColor(withAlpha(rec.fr, rec.fa * t))
        n:setFillEndColor(withAlpha(rec.fer, rec.fea * t))
        n:setBorderColor(withAlpha(rec.br, rec.ba * t))
        n:setTextColor(withAlpha(rec.tr, rec.ta * t))
    end

    beginTransition = function()
        local catRec = cats[state.cat]
        if catRec and catRec.col then trans = { col = catRec.col, start = nil } end
    end

    -- ----------------------------------------------------------------
    -- live value refresh (pulls settings_list; rewrites titles + widgets)
    -- ----------------------------------------------------------------

    refresh = function()
        local ok, list = pcall(function() return game.settings_list() end)
        if ok and type(list) == "table" then
            for _, d in ipairs(list) do
                if type(d) == "table" and d.key then
                    if d.value ~= nil then state.values[d.key] = d.value end
                    if type(d.label) == "string" and d.label ~= "" then
                        state.labels[d.key] = d.label
                    end
                    if d.kind == "cycle" and type(d.values) == "table" then
                        state.cycle_values[d.key] = d.values
                    end
                end
            end
        end

        for _, catRec in ipairs(cats) do
            for _, sec in ipairs(catRec.sections) do
                for _, rec in ipairs(sec.rows) do
                    if rec.widget then
                        local def = rec.def
                        local lbl = state.labels[def.key]
                        set_text(rec.title, lbl or tr(def.lk, def.fb))
                        local v = state.values[def.key]
                        if v == nil then
                            v = (def.kind == "slider") and def.min or 0
                        end
                        if def.kind == "cycle" then
                            local labels = state.cycle_values[def.key] or def.values or {}
                            local cur = math.floor(tonumber(v) or 0)
                            for i, seg in ipairs(rec.segments or {}) do
                                set_text(seg, labels[i] or (def.values or {})[i] or tostring(i - 1))
                                paintButton(seg, i == cur + 1, C.foreground)
                            end
                        else
                            sync_widget(rec, v)
                        end
                    end
                end
            end
        end
    end

    -- ----------------------------------------------------------------
    -- layout
    -- ----------------------------------------------------------------

    -- sidebar: vertical category buttons (selected = solid primary)
    local navCol = heroui.column {
        w = NAV_W, h = "match", padding = 20, spacing = 8, gravity = "start", children = {},
    }
    for i, catRec in ipairs(cats) do
        local btn = heroui.button {
            text = catRec.def.label, w = "match", h = 44, textSize = 17,
            radius = R.small, variant = "light",
            onClick = function()
                if state.cat == i then return end
                state.cat = i
                applyCategory(true)
            end,
        }
        catRec.btn = btn
        paintButton(btn, i == state.cat, C.foregroundMuted)
        table.insert(navCol.children, btn)
    end

    -- topbar: bold title + muted subtitle on the left, search field right
    local titleCol = heroui.column {
        w = "wrap", h = "wrap", spacing = 2,
        anchor = { 0, 0.5 }, pivot = { 0, 0.5 },
        children = {
            heroui.label { text = "Settings", w = "wrap", textSize = 26 },
            heroui.label { text = "Neogenesis · Vulkan", w = "wrap", textSize = 14,
                           textColor = C.foregroundMuted },
        },
    }
    local search = heroui.textfield {
        w = 260, h = 44, placeholder = "Search settings...",
        onChange = function(t)
            state.query = t or ""
            applyFilter()
        end,
    }
    search.anchor = { 1, 0.5 }; search.pivot = { 1, 0.5 }
    local topbar = invisibleBox {
        type = "box", w = "match", h = TOP_H, padding = TOP_PAD,
        children = { titleCol, search },
    }

    local function divider(vertical)
        return {
            type = "panel",
            w = vertical and 1 or "match",
            h = vertical and "match" or 1,
            radius = 0,
            fillColor = C.divider, fillEndColor = C.divider,
            borderColor = "#00000000",
            shadow = false, textShadow = false,
        }
    end

    local content = heroui.column {
        w = "match", h = "match", padding = TOP_PAD, spacing = 0,
        children = { scrollSpec },
    }
    local body = heroui.row {
        w = "match", h = BODY_H, spacing = 0,
        children = { navCol, divider(true), content },
    }

    local done = heroui.button {
        text = tr("gui.done", "Done"), variant = "solid", w = "match", h = 44,
        onClick = function() neoui.pop() end,
    }

    local inner = heroui.column {
        w = "match", spacing = 0,
        anchor = { 0, 0 }, pivot = { 0, 0 },
        children = { topbar, divider(false), body, divider(false),
                     heroui.spacer(16), done, heroui.spacer(20) },
    }
    local card = heroui.card {
        w = CARD_W, padding = 0,
        anchor = { 0.5, 0.5 }, pivot = { 0.5, 0.5 },
        children = { inner },
    }

    local tree = { type = "box", children = { card } }

    -- main menu opens "options"; the canonical id is "settings"
    neoui.register_screen { id = "settings", tree = tree }
    neoui.register_screen { id = "options", tree = tree }

    -- ----------------------------------------------------------------
    -- single frame hook: open detection + category transition
    -- ----------------------------------------------------------------

    local layoutDumped = false
    neoui.every_frame(function()
        -- open detection: nodeFromLua stamps a fresh __node per open; an
        -- identity change means the screen was just (re)opened
        local sr = scrollSpec.__node
        if sr ~= scrollSeen then
            scrollSeen = sr
            layoutDumped = false
            if sr then
                pcall(function() sr:setClip(true) end) -- spec.clip is not bridged
                pcall(function() applyCategory(false) end)
                pcall(refresh)
                beginTransition() -- silk entrance
            end
        end

        -- one-shot layout dump (after the first real layout pass)
        if not layoutDumped then
            local cd = card.__node
            if cd and cd:getWidth() > 0 then
                layoutDumped = true
                local id = inner.__node
                neoui.log(string.format("settings card: (%d,%d) %dx%d",
                    cd:getX(), cd:getY(), cd:getWidth(), cd:getHeight()))
                if id then
                    neoui.log(string.format("settings inner: (%d,%d) %dx%d",
                        id:getX(), id:getY(), id:getWidth(), id:getHeight()))
                end
                local bd = body and body.__node
                if bd then
                    neoui.log(string.format("settings body: (%d,%d) %dx%d",
                        bd:getX(), bd:getY(), bd:getWidth(), bd:getHeight()))
                end
                local ct = content and content.__node
                if ct then
                    neoui.log(string.format("settings content: (%d,%d) %dx%d",
                        ct:getX(), ct:getY(), ct:getWidth(), ct:getHeight()))
                end
            end
        end

        if trans then
            local colNode = trans.col and trans.col.__node
            if not colNode then
                trans = nil
            else
                if not trans.start then
                    trans.start = neoui.time()
                    trans.nodes = collectNodes(colNode)
                    for _, entry in ipairs(trans.nodes) do paintAlpha(entry, 0) end
                    pcall(function() colNode:setOffsetY(10) end)
                end
                local a = (neoui.time() - trans.start) / FADE_S
                if a >= 1 then
                    for _, entry in ipairs(trans.nodes) do paintAlpha(entry, 1) end
                    pcall(function() colNode:setOffsetY(0) end)
                    trans = nil
                else
                    local e = 1 - (1 - a) * (1 - a) * (1 - a) -- ease-out cubic
                    for _, entry in ipairs(trans.nodes) do paintAlpha(entry, e) end
                    pcall(function() colNode:setOffsetY(10 * (1 - e)) end)
                end
            end
        end
    end)

    pcall(refresh)
end

-- self-register on require (init.lua only needs the require line); calling
-- M.register() again is safe, it simply re-registers the screen ids
M.register()

return M
