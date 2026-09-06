-- HeroUI for NeoUI (https://heroui.com): design tokens + components.
-- Every component takes a spec table and returns a completed node
-- table for neoui.show_screen.
--
-- Interactive components (slider/toggle/checkbox/textfield) keep their
-- state inside the spec table and animate through the coerced Kotlin
-- node handle (spec.__node) from neoui.every_frame tickers. Tickers are
-- pcall-guarded and null-safe: they idle while their screen is closed
-- and can never throw into the engine's frame-hook loop (one bad hook
-- would otherwise clear every registered hook).
--
-- Engine facts this file relies on (see UiNode.kt / UiRenderer.kt /
-- InputDispatcher.kt):
--   * Kotlin `var x` exposes getX()/setX() to LuaJ coercion. There are NO
--     is-prefixed booleans on UiNode, so booleans are get/set too:
--     getHover/setHover, getPressed/setPressed, getVisible/setVisible,
--     getFocused/setFocused. Calls like isHover()/isPressed() DO NOT EXIST.
--   * Text is always CENTERED inside the node's bounds; wrap-sized nodes
--     therefore hug their text and can be positioned like a left/right run.
--   * Layout formula (anchor box):
--       childX = parentX + parentPadding*scale + anchorX*parentInnerW
--              + offsetX*scale - pivotX*childWidth
--     Converter-made panels default to padding 0 (heroui.card overrides).
--   * Draw order = tree order; hitTest picks the LAST visible node with an
--     onClick that contains the point (topmost drawn wins). visible=false
--     skips both rendering and hit-testing.
--   * The retained node tree re-converts from the spec tables only when a
--     screen is opened; runtime edits must go through the coerced __node.

local M = {}

-- Module-wide drag mutex: only ONE slider may be in a drag at a time.
-- Without this, dragging one slider and sweeping the cursor across another
-- starts the second one dragging too (each ticker independently sees
-- left-button + hover over its own root).
local activeDrag = nil

M.colors = {
    background = "#FFFFFF",
    surface = "#FFFFFF",      -- content1
    surface2 = "#F4F4F5",     -- content2
    surface3 = "#E4E4E7",     -- content3
    foreground = "#1F2126",
    foregroundMuted = "#6E7078",
    primary = "#006FEE",
    primaryHover = "#005CC4",
    primaryTint = "#1A006FEE",
    primaryForeground = "#FFFFFF",
    secondary = "#9353F3",
    danger = "#F31260",
    divider = "#14000000",
}

M.radius = { small = 12, medium = 14, large = 16, card = 20 }
M.metrics = { buttonHeight = 44, buttonWidth = 400, controlHeight = 44 }

-- --------------------------------------------------------------------
-- helpers
-- --------------------------------------------------------------------

local function clamp(v, lo, hi)
    if v < lo then return lo end
    if v > hi then return hi end
    return v
end

--- "#RRGGBB" / "#AARRGGBB" -> signed 32-bit ARGB int for node setters
--- (setFillColor/setBorderColor take a Kotlin Int; values >= 2^31 must
--- be passed as negative numbers, doubles would saturate).
local function argb(s)
    if type(s) ~= "string" then return 0 end
    local hex = s:gsub("#", "")
    local n = tonumber(hex, 16) or 0
    if #hex == 6 then n = n + 0xFF000000 end
    if n >= 0x80000000 then n = n - 0x100000000 end
    return n
end
M.argb = argb

--- rgb (0xRRGGBB) + alpha byte (0..255) -> signed 32-bit ARGB for the
--- coerced node setters (used by the toast fade, Lua 5.1: no bitwise ops).
local function alphaOver(rgb, a)
    local n = math.floor(a + 0.5) * 0x1000000 + rgb
    if n >= 0x80000000 then n = n - 0x100000000 end
    return n
end

--- Removes the last UTF-8 character (walks back over continuation bytes).
local function stripLastChar(s)
    if #s == 0 then return s end
    local i = #s
    while i > 1 do
        local b = string.byte(s, i)
        if b < 0x80 or b >= 0xC0 then break end
        i = i - 1
    end
    return s:sub(1, i - 1)
end

-- Central ticker installer: every_frame hooks persist for the whole
-- runtime and cannot be removed, so each one guards itself (pcall) and
-- stays null-safe once its nodes are gone. A transient failure (e.g. a
-- node mid-teardown) must NOT kill a component forever, so a ticker only
-- retires after a full second of consecutive errors; the first error is
-- logged once for diagnosis. The pcall also keeps the engine's frame-hook
-- loop safe (one throwing hook would otherwise clear every hook).
local function addTicker(fn)
    local alive = true
    local strikes = 0
    neoui.every_frame(function(dt)
        if not alive then return end
        local ok, err = pcall(fn, dt)
        if ok then strikes = 0 return end
        strikes = strikes + 1
        if strikes == 1 then
            pcall(function() neoui.log("heroui ticker error: " .. tostring(err)) end)
        end
        if strikes > 90 then alive = false end -- ~1s of hard failure: retire
    end)
end

-- Lazily resolves the coerced Kotlin node handle: read spec.__node at
-- hook time (not build time) so re-opened screens pick up fresh nodes.
local function nodeOf(specTable)
    if type(specTable) ~= "table" then return nil end
    return specTable.__node
end

-- px bounds hit-test against a coerced node handle. pcall'd so a coercion
-- hiccup reports "not inside" instead of killing the calling ticker.
local function inside(node, x, y)
    local ok, res = pcall(function()
        local nx, ny = node:getX(), node:getY()
        return x >= nx and x < nx + node:getWidth() and y >= ny and y < ny + node:getHeight()
    end)
    if ok then return res == true end
    return false
end

-- --------------------------------------------------------------------
-- base components
-- --------------------------------------------------------------------

function M.label(spec)
    spec.type = "label"
    spec.textSize = spec.textSize or 18
    spec.textColor = spec.textColor or M.colors.foreground
    spec.w = spec.w or "match"
    if spec.bold == nil then spec.bold = true end -- allow explicit false
    spec.shadow = false
    spec.textShadow = false
    return spec
end

-- variants: solid | bordered | flat | light (HeroUI button styles)
function M.button(spec)
    local v = spec.variant or "solid"
    local c = M.colors
    spec.type = "button"
    spec.h = spec.h or M.metrics.buttonHeight
    spec.w = spec.w or M.metrics.buttonWidth
    spec.radius = spec.radius or M.radius.medium
    spec.shadow = false
    spec.textShadow = false
    if spec.bold == nil then spec.bold = true end -- allow explicit false
    if v == "solid" then
        spec.fillColor = c.primary; spec.fillEndColor = c.primary
        spec.textColor = c.primaryForeground
    elseif v == "bordered" then
        spec.fillColor = "#00000000"; spec.fillEndColor = "#00000000"
        spec.textColor = c.primary; spec.borderColor = c.primary
    elseif v == "flat" then
        spec.fillColor = c.primaryTint; spec.fillEndColor = c.primaryTint
        spec.textColor = c.primary
    else -- light
        spec.fillColor = "#00000000"; spec.fillEndColor = "#00000000"
        spec.textColor = spec.textColor or c.primary
    end
    return spec
end

-- frosted glass surface container (no drop shadow: the blur IS the
-- separation — a dark ring over the blurred backdrop reads as dirt)
function M.card(spec)
    spec.type = "panel"
    spec.style = "glass"
    spec.radius = spec.radius or M.radius.card
    spec.shadow = false
    spec.textShadow = false
    spec.padding = spec.padding or 28
    return spec
end

function M.column(spec) spec.type = "column"; spec.spacing = spec.spacing or 12; spec.w = spec.w or "match"; return spec end
function M.row(spec) spec.type = "row"; spec.spacing = spec.spacing or 8; return spec end
function M.spacer(h) return { type = "spacer", h = h } end

function M.accent_bar(w)
    return { type = "panel", w = w or 64, h = 4, radius = 2,
             fillColor = M.colors.primary, fillEndColor = M.colors.primary,
             borderColor = "#00000000", shadow = false }
end

-- form row: fixed-width muted label on the left, the given control spec on
-- the right (settings-screen pattern). The row centers children vertically.
-- heroui.form_row("Music volume", heroui.slider{...}) -> row spec
function M.form_row(labelText, controlSpec, labelW)
    local c = M.colors
    local lbl = M.label {
        text = labelText or "", w = labelW or 140, textSize = 15,
        textColor = c.foregroundMuted,
    }
    return M.row { w = "match", spacing = 12, children = { lbl, controlSpec } }
end

-- --------------------------------------------------------------------
-- progress: heroui.progress { w=240, value=0..1, height=6 }
-- Static progress bar: muted track + primary fill, like the slider track
-- without a thumb. Value is set at build time; spec.__fill/__track expose
-- the sub-specs so callers can animate via their coerced __node, and
-- spec.set(v) updates both the spec and the live node in one call.
-- --------------------------------------------------------------------

function M.progress(spec)
    local c = M.colors
    local wNum = tonumber(spec.w) or 240
    local h = tonumber(spec.height) or 6
    if h < 4 then h = 4 end
    spec.value = clamp(tonumber(spec.value) or 0, 0, 1)

    local track = {
        type = "panel", w = wNum, h = h, radius = h / 2,
        anchor = {0, 0.5}, pivot = {0, 0.5},
        fillColor = c.surface3, fillEndColor = c.surface3,
        borderColor = "#00000000", shadow = false, textShadow = false,
    }
    local fill = {
        type = "panel", w = spec.value * wNum, h = h, radius = h / 2,
        anchor = {0, 0.5}, pivot = {0, 0.5},
        fillColor = c.primary, fillEndColor = c.primary,
        borderColor = "#00000000", shadow = false, textShadow = false,
    }

    spec.__track, spec.__fill = track, fill
    spec.type = "box"
    spec.w = wNum
    spec.h = spec.h or h
    spec.children = { track, fill }

    -- convenience: spec.set(0.42) keeps spec + live node in sync
    function spec.set(v)
        spec.value = clamp(tonumber(v) or 0, 0, 1)
        fill.w = spec.value * wNum
        local f = nodeOf(fill)
        if f then f:setDpWidth(fill.w) end
    end

    return spec
end

-- --------------------------------------------------------------------
-- dropdown: heroui.dropdown { w=220, options={"a","b",...},
--                             value=1 (1-based), onChange(index, optionText) }
-- A bordered trigger button showing the current option; clicking it opens
-- an options card just below the trigger. The engine has no popups, so the
-- "modal" is composed from two extra children of the dropdown's own box:
-- a huge invisible click-catcher (its onClick closes the menu) and the
-- options card drawn after it (tree order = draw + hit-test priority, so
-- the rows win over the catcher). Both ship visible=false and are toggled
-- through setVisible on the coerced nodes.
--
-- LIMITATIONS (engine, not taste): the open list is a child of the
-- dropdown, so screen nodes that come LATER in tree order draw on top of
-- it and can steal its clicks — place dropdowns late in the layout. No
-- clipping/scrolling: all options render; keep lists short.
-- --------------------------------------------------------------------

function M.dropdown(spec)
    local c = M.colors
    spec.options = spec.options or {}
    local wNum = tonumber(spec.w) or 220
    local n = #spec.options
    spec.value = math.floor(tonumber(spec.value) or 1)
    if spec.value < 1 or (n > 0 and spec.value > n) then spec.value = n > 0 and 1 or 0 end
    spec.__open = false

    -- forward declarations: the trigger/row onClicks below close over these
    -- locals (declaring them late would silently make them GLOBALS, and two
    -- dropdowns on one screen would then drive each other's menus)
    local setOpen, select

    local trigger = M.button {
        text = spec.options[spec.value] or "", variant = "bordered",
        w = wNum, h = spec.h or M.metrics.controlHeight,
        radius = M.radius.small,
        onClick = function() setOpen(true) end,
    }

    -- full-screen invisible click-catcher: centered on the trigger and far
    -- bigger than any screen ("match" would only fill this 220x44 box).
    -- box + no style => drawsSurface=false in the converter: invisible.
    local catcher = {
        type = "box", w = 8192, h = 4096,
        anchor = {0.5, 0.5}, pivot = {0.5, 0.5},
        fillColor = "#00000000", fillEndColor = "#00000000",
        borderColor = "#00000000", shadow = false, textShadow = false,
        visible = false,
        onClick = function() setOpen(false) end, -- click anywhere closes
    }

    -- options card just outside the trigger (6dp gap), white surface.
    -- spec.up opens the card ABOVE the trigger (for controls near the
    -- screen bottom); default opens below. A plain panel is an ANCHOR box
    -- (children would stack centered on the same spot), so the rows live
    -- in a column inside the card.
    local list = M.column { spacing = 2, w = "match", children = {} }
    local card = {
        type = "panel", style = "solid", w = wNum, radius = M.radius.medium,
        padding = 6, shadow = true, textShadow = false,
        anchor = spec.up and {0, 0} or {0, 1},
        pivot = spec.up and {0, 1} or {0, 0},
        offset = {0, spec.up and -6 or 6},
        visible = false,
        children = { list },
    }
    local rows = {}
    for i = 1, n do
        local row = M.button {
            text = spec.options[i], variant = "light", w = "match", h = 40,
            radius = 10, bold = false,
            onClick = function() select(i) end,
        }
        rows[i] = row
        table.insert(list.children, row)
    end

    spec.__trigger, spec.__card, spec.__catcher = trigger, card, catcher

    local function styleRows()
        for i, r in ipairs(rows) do
            local sel = (i == spec.value)
            r.fillColor = sel and c.primaryTint or "#00000000"
            r.fillEndColor = r.fillColor
            r.textColor = sel and c.primary or c.foreground
            r.bold = sel
            local rn = nodeOf(r)
            if rn then
                rn:setFillColor(argb(r.fillColor))
                rn:setFillEndColor(argb(r.fillEndColor))
                rn:setTextColor(argb(r.textColor))
            end
        end
        trigger.text = spec.options[spec.value] or ""
        local tn = nodeOf(trigger)
        if tn then
            local txt = trigger.text
            if txt ~= tn:getText() then tn:setText(txt) end
        end
    end

    function setOpen(open)
        spec.__open = open and true or false
        catcher.visible = spec.__open
        card.visible = spec.__open
        local cn, cdn = nodeOf(catcher), nodeOf(card)
        if cn then cn:setVisible(spec.__open) end
        if cdn then cdn:setVisible(spec.__open) end
    end

    function select(i)
        if i < 1 or i > n then return end
        local changed = (i ~= spec.value)
        spec.value = i
        styleRows()
        setOpen(false)
        if changed and spec.onChange then pcall(spec.onChange, i, spec.options[i]) end
    end

    styleRows() -- paint initial selection state into the specs

    spec.type = "box"
    spec.w = wNum
    spec.h = spec.h or M.metrics.controlHeight
    spec.children = { trigger, catcher, card }
    return spec
end

-- --------------------------------------------------------------------
-- slider: heroui.slider { w=240, value, min, max, step, onChange(v),
--                         format(v)->string }
-- Track + fill + thumb composed from panels; dragging is driven by
-- polling neoui.mouse() from a ticker (own bounds hit-test through the
-- coerced node handles). onChange fires while dragging (only when the
-- quantized value actually changes) and once more on release if the
-- final value has not been reported yet.
-- --------------------------------------------------------------------

function M.slider(spec)
    local c = M.colors
    spec.min = tonumber(spec.min) or 0
    spec.max = tonumber(spec.max) or 1
    if spec.max < spec.min then spec.min, spec.max = spec.max, spec.min end
    spec.value = tonumber(spec.value) or (spec.min + spec.max) / 2
    spec.value = clamp(spec.value, spec.min, spec.max)
    spec.__drag = false
    spec.__lastEmitted = spec.value

    local wNum = spec.w
    if type(wNum) ~= "number" then wNum = 240 end
    local valueW = spec.format and 64 or 0
    -- Alignment fix: at frac=1 the thumb's right edge reaches trackW + 8
    -- (thumb radius). Reserve 12dp before the value label's box so the
    -- thumb never collides with the label (whose text is center-aligned
    -- inside its 64dp box by the engine).
    local thumbClear = valueW > 0 and 12 or 0
    local trackW = wNum - valueW - thumbClear
    if trackW < 40 then trackW = 40 end

    local function fracOf(v)
        if spec.max <= spec.min then return 0 end
        return clamp((v - spec.min) / (spec.max - spec.min), 0, 1)
    end

    local track = {
        type = "panel", w = trackW, h = 6, radius = 3,
        anchor = {0, 0.5}, pivot = {0, 0.5},
        fillColor = c.surface3, fillEndColor = c.surface3,
        borderColor = "#00000000", shadow = false, textShadow = false,
    }
    local fill = {
        type = "panel", w = fracOf(spec.value) * trackW, h = 6, radius = 3,
        anchor = {0, 0.5}, pivot = {0, 0.5},
        fillColor = c.primary, fillEndColor = c.primary,
        borderColor = "#00000000", shadow = false, textShadow = false,
    }
    local thumb = {
        type = "panel", w = 16, h = 16, radius = 8,
        anchor = {0, 0.5}, pivot = {0.5, 0.5}, offset = {fracOf(spec.value) * trackW, 0},
        fillColor = "#FFFFFF", fillEndColor = "#FFFFFF",
        borderColor = c.primary, shadow = false, textShadow = false,
        onClick = function() end, -- engine hit-test -> hover/press visuals
    }
    local valueLabel = nil
    if spec.format then
        local ok, s = pcall(spec.format, spec.value)
        valueLabel = {
            type = "label", w = valueW, textSize = 14, bold = true,
            textColor = c.foreground, shadow = false, textShadow = false,
            anchor = {1, 0.5}, pivot = {1, 0.5},
            text = ok and tostring(s) or "",
        }
    end

    spec.__track, spec.__fill, spec.__thumb, spec.__valLabel = track, fill, thumb, valueLabel
    spec.type = "box"
    spec.h = spec.h or M.metrics.controlHeight
    -- forward declarations: the onClick closure below captures THESE locals —
    -- `local function` after the closure would bind a different variable and
    -- leave the closure calling nil
    local apply, setValue, setFromX
    -- click-to-jump is EVENT-driven here (onClick fires synchronously from the
    -- input dispatcher with the cursor over the control): a polling ticker can
    -- miss a synthetic click entirely when down+up land inside one frame.
    spec.onClick = function()
        local m = neoui.mouse()
        local root = nodeOf(spec)
        if root and inside(root, m.x, m.y) then setFromX(m.x) end
    end
    spec.children = valueLabel and { track, fill, thumb, valueLabel } or { track, fill, thumb }

    function apply()
        local frac = fracOf(spec.value)
        -- keep the spec table in sync too: registered screens re-convert
        -- their tree on every open, and the tables are the initial state
        fill.w = frac * trackW
        thumb.offset = { frac * trackW, 0 }
        local f, th, vl = nodeOf(fill), nodeOf(thumb), nodeOf(valueLabel)
        if f then f:setDpWidth(frac * trackW) end
        if th then th:setOffsetX(frac * trackW) end
        if vl and spec.format then
            local ok, s = pcall(spec.format, spec.value)
            if ok then
                local txt = tostring(s)
                valueLabel.text = txt
                if txt ~= vl:getText() then vl:setText(txt) end
            end
        end
    end

    function setValue(v)
        if spec.step and spec.step > 0 then
            -- round to the nearest step; the epsilon absorbs float dust from
            -- the division (0.95/0.1 == 9.4999... would round the wrong way)
            local nn = (v - spec.min) / spec.step
            nn = math.floor(nn + 0.5 + 1e-9)
            v = spec.min + nn * spec.step
            v = math.floor(v * 1e6 + 0.5) / 1e6 -- kill float dust from the multiply
        end
        v = clamp(v, spec.min, spec.max)
        if v ~= spec.value then
            spec.value = v
            apply()
            if v ~= spec.__lastEmitted then
                spec.__lastEmitted = v
                if spec.onChange then pcall(spec.onChange, v) end
            end
        end
    end

    function setFromX(mx)
        local tr = nodeOf(track)
        if not tr then return end
        local tw = tr:getWidth()
        if tw <= 0 then return end
        local t = clamp((mx - tr:getX()) / tw, 0, 1)
        setValue(spec.min + t * (spec.max - spec.min))
    end

    addTicker(function()
        local root = nodeOf(spec)
        if not root then
            if activeDrag == spec then activeDrag = nil end
            return
        end
        local m = neoui.mouse()
        if m.left then
            if not spec.__drag then
                -- Drag starts on left-button + cursor inside the control.
                -- Do NOT gate this on engine hover: a press that teleports the
                -- cursor (no intermediate move) never fires a cursorPos event,
                -- so hover stays stale for a frame-set and the drag would
                -- never start. Node liveness is already handled by nodeOf.
                if activeDrag ~= nil and activeDrag ~= spec then
                    return -- another slider owns the drag (mutex)
                end
                if inside(root, m.x, m.y) then
                    spec.__drag = true
                    activeDrag = spec
                    setFromX(m.x) -- click-to-jump
                end
            else
                setFromX(m.x) -- keep following while held (clamped to the track)
            end
        elseif spec.__drag then
            spec.__drag = false
            if activeDrag == spec then activeDrag = nil end
            if spec.value ~= spec.__lastEmitted then
                spec.__lastEmitted = spec.value
                if spec.onChange then pcall(spec.onChange, spec.value) end
            end
        end
    end)

    return spec
end

-- --------------------------------------------------------------------
-- toggle: heroui.toggle { value=false, onChange(bool) }
-- Pill switch: accent pill when on, muted grey when off; the knob eases
-- between the ends through setOffsetX from a ticker.
-- --------------------------------------------------------------------

function M.toggle(spec)
    local c = M.colors
    spec.value = spec.value and true or false

    local apply -- forward declaration: the pill's onClick closes over it

    -- knob.x = pillX + offsetX - pivot*width(13dp); pill padding is 0, so
    -- OFF center at 16 leaves a 3dp left margin (16-13) and ON center at
    -- 36 leaves the mirrored 3dp right margin (52-36-13).
    local knobOffX, knobOnX = 16, 36
    local knob = {
        type = "panel", w = 26, h = 26, radius = 13,
        anchor = {0, 0.5}, pivot = {0.5, 0.5},
        offset = { spec.value and knobOnX or knobOffX, 0 },
        fillColor = "#FFFFFF", fillEndColor = "#FFFFFF",
        borderColor = spec.value and "#00000000" or "#22000000",
        shadow = false, textShadow = false,
    }
    local pill = {
        type = "panel", w = 52, h = 32, radius = 16,
        anchor = {0, 0.5}, pivot = {0, 0.5},
        fillColor = spec.value and c.primary or c.surface3,
        fillEndColor = spec.value and c.primary or c.surface3,
        borderColor = "#00000000", shadow = false, textShadow = false,
        onClick = function()
            spec.value = not spec.value
            apply()
            if spec.onChange then pcall(spec.onChange, spec.value) end
        end,
        children = { knob },
    }
    spec.__pill, spec.__knob = pill, knob
    spec.type = "box"
    spec.w = spec.w or 52
    spec.h = spec.h or M.metrics.controlHeight
    spec.onClick = pill.onClick -- the whole 44dp strip toggles
    spec.children = { pill }

    function apply()
        pill.fillColor = spec.value and c.primary or c.surface3
        pill.fillEndColor = pill.fillColor
        pill.borderColor = "#00000000"
        knob.borderColor = spec.value and "#00000000" or "#22000000"
        local p, k = nodeOf(pill), nodeOf(knob)
        if p then
            p:setFillColor(argb(pill.fillColor))
            p:setFillEndColor(argb(pill.fillEndColor))
            p:setBorderColor(argb(pill.borderColor))
        end
        if k then k:setBorderColor(argb(knob.borderColor)) end
    end

    local knobX = spec.value and knobOnX or knobOffX
    addTicker(function(dt)
        local k = nodeOf(knob)
        if not k then return end
        local target = spec.value and knobOnX or knobOffX
        knobX = knobX + (target - knobX) * clamp((dt or 0.016) * 16, 0, 1)
        if math.abs(target - knobX) < 0.05 then knobX = target end
        k:setOffsetX(knobX)
    end)

    return spec
end

-- --------------------------------------------------------------------
-- checkbox: heroui.checkbox { checked=false, label="text", onChange(bool) }
-- Box + white check mark (small accent panel) + text label; the whole
-- row is clickable, the box shows engine hover/press visuals.
-- --------------------------------------------------------------------

function M.checkbox(spec)
    local c = M.colors
    spec.checked = spec.checked and true or false

    local apply -- forward declaration: the box's onClick closes over it

    local mark = {
        type = "panel", w = 10, h = 10, radius = 3,
        anchor = {0.5, 0.5}, pivot = {0.5, 0.5},
        fillColor = "#FFFFFF", fillEndColor = "#FFFFFF",
        borderColor = "#00000000", shadow = false, textShadow = false,
        visible = spec.checked,
    }
    local box = {
        type = "panel", w = 22, h = 22, radius = 6,
        fillColor = spec.checked and c.primary or "#FFFFFF",
        fillEndColor = spec.checked and c.primary or "#FFFFFF",
        borderColor = spec.checked and c.primary or "#4026262B",
        shadow = false, textShadow = false,
        onClick = function()
            spec.checked = not spec.checked
            apply()
            if spec.onChange then pcall(spec.onChange, spec.checked) end
        end,
        children = { mark },
    }
    local lbl = M.label { text = spec.label or "", w = "wrap", textSize = 16,
                          textColor = c.foreground }
    local row = M.row { spacing = 10, children = { box, lbl } }

    function apply()
        box.fillColor = spec.checked and c.primary or "#FFFFFF"
        box.fillEndColor = box.fillColor
        box.borderColor = spec.checked and c.primary or "#4026262B"
        mark.visible = spec.checked
        local b, mk = nodeOf(box), nodeOf(mark)
        if b then
            b:setFillColor(argb(box.fillColor))
            b:setFillEndColor(argb(box.fillEndColor))
            b:setBorderColor(argb(box.borderColor))
        end
        if mk then mk:setVisible(spec.checked) end
    end

    spec.type = "box"
    spec.w = spec.w or "wrap"
    spec.h = spec.h or M.metrics.controlHeight
    spec.onClick = box.onClick -- label clicks toggle too
    spec.children = { row }
    return spec
end

-- --------------------------------------------------------------------
-- text field: heroui.textfield { w=300, placeholder="", onChange(text),
--                                password=false, maxLen=32 }
-- Click focuses (white border + brighter fill while focused, muted
-- otherwise). While a field is focused a single global key listener
-- (installed once below) routes printable chars / backspace into it and
-- ESC/enter/clicking-away blurs. ESC popping is suppressed while
-- focused. The caret is the "|" appended to the rendered text, blinking
-- on a 530ms cycle from a ticker. Passwords render asterisks.
-- --------------------------------------------------------------------

local activeField = nil
local suppressedByUs = false

local function updateDisplay(spec)
    local lbl = spec.__label
    if not lbl then return end
    local v = tostring(spec.value or "")
    local shown = spec.password and string.rep("*", #v) or v
    local color
    if spec.__focused then
        shown = shown .. (spec.__cursorOn and "|" or " ")
        color = M.colors.foreground
    elseif #v == 0 then
        shown = spec.placeholder or ""
        color = M.colors.foregroundMuted
    else
        color = M.colors.foreground
    end
    lbl.text = shown
    lbl.textColor = color
    local n = nodeOf(lbl)
    if n then
        if shown ~= spec.__lastShown then
            spec.__lastShown = shown
            n:setText(shown)
        end
        n:setTextColor(argb(color))
    end
end

local function blurField(spec)
    if activeField ~= spec then return end
    activeField = nil
    spec.__focused = false
    spec.borderColor = "#3326262B"
    spec.fillColor = "#C8FFFFFF"; spec.fillEndColor = "#C8FFFFFF"
    local n = nodeOf(spec)
    if n then
        n:setBorderColor(argb(spec.borderColor))
        n:setFillColor(argb(spec.fillColor))
        n:setFillEndColor(argb(spec.fillEndColor))
        n:setFocused(false)
    end
    neoui.set_pop_suppressed(false)
    suppressedByUs = false
    updateDisplay(spec)
end

local function focusField(spec)
    if activeField == spec then return end
    if activeField then blurField(activeField) end
    activeField = spec
    spec.__focused = true
    spec.__cursorOn = true
    spec.__blink = 0
    spec.borderColor = "#FFFFFFFF"
    spec.fillColor = "#E6FFFFFF"; spec.fillEndColor = "#E6FFFFFF"
    local n = nodeOf(spec)
    if n then
        n:setBorderColor(argb(spec.borderColor))
        n:setFillColor(argb(spec.fillColor))
        n:setFillEndColor(argb(spec.fillEndColor))
        n:setFocused(true)
    end
    neoui.set_pop_suppressed(true)
    suppressedByUs = true
    updateDisplay(spec)
end

M.focus_textfield = focusField
M.blur_textfield = blurField

function M.textfield(spec)
    spec.value = tostring(spec.value or "")
    spec.placeholder = spec.placeholder or ""
    spec.maxLen = spec.maxLen or 32
    spec.__focused = false
    spec.__cursorOn = true
    spec.__blink = 0

    local wNum = spec.w
    if type(wNum) ~= "number" then wNum = 300 end

    local lbl = {
        type = "label", w = "wrap", textSize = 16, bold = true,
        anchor = {0, 0.5}, pivot = {0, 0.5}, offset = {16, 0},
        shadow = false, textShadow = false,
    }
    spec.__label = lbl
    spec.type = "panel"
    spec.style = "glass"
    spec.w = wNum
    spec.h = spec.h or M.metrics.controlHeight
    spec.radius = spec.radius or M.radius.medium
    spec.fillColor = "#C8FFFFFF"; spec.fillEndColor = "#C8FFFFFF"
    spec.borderColor = "#3326262B"
    spec.shadow = false; spec.textShadow = false
    spec.onClick = function() focusField(spec) end
    spec.children = { lbl }

    updateDisplay(spec)

    addTicker(function(dt)
        -- self-heal: if the field's screen was popped from Lua without a
        -- blur (so ESC never ran), make sure ESC pops the next screen
        if suppressedByUs and activeField == nil then
            suppressedByUs = false
            neoui.set_pop_suppressed(false)
        end
        local n = nodeOf(spec)
        if not n then return end
        if spec.__focused then
            spec.__blink = spec.__blink + (dt or 0.016)
            if spec.__blink >= 0.53 then
                spec.__blink = 0
                spec.__cursorOn = not spec.__cursorOn
                updateDisplay(spec)
            end
        end
        if activeField == spec then
            local m = neoui.mouse()
            if m.left and not inside(n, m.x, m.y) then
                blurField(spec) -- clicking away blurs
            end
        end
    end)

    return spec
end

-- The ONE global key listener (neoui.key_listener is a single slot):
-- installed once here, routed to the focused text field. Other scripts
-- must not overwrite neoui.key_listener or text fields go dead.
if neoui and neoui.key_listener then
    neoui.key_listener(function(key, chStr, down)
        local f = activeField
        if not f or not down then return end
        pcall(function()
            if key == 1 then blurField(f) return end             -- ESC
            if key == 28 or key == 156 then blurField(f) return end -- enter / numpad enter
            if key == 14 then                                    -- backspace
                local v = tostring(f.value or "")
                if #v > 0 then
                    f.value = stripLastChar(v)
                    updateDisplay(f)
                    if f.onChange then pcall(f.onChange, f.value) end
                end
                return
            end
            if type(chStr) == "string" and #chStr > 0 then
                local b = string.byte(chStr)
                if b ~= nil and b >= 32 and b ~= 127 and #tostring(f.value) < (f.maxLen or 32) then
                    f.value = tostring(f.value or "") .. chStr
                    updateDisplay(f)
                    if f.onChange then pcall(f.onChange, f.value) end
                end
            end
        end)
    end)
end

-- --------------------------------------------------------------------
-- toast: heroui.mount_toast(rootSpec, text, ms)
-- A dark-glass pill, bottom-center, that holds for `ms` then fades and
-- slides away. rootSpec must be the SCREEN ROOT box spec (a table whose
-- "type" is "box"); the toast is appended to its children, so it draws
-- above everything already in the tree (draw order = tree order).
--
-- LIMITATIONS, honestly: the engine cannot remove nodes from a live tree
-- and has no z-ordering between screens, so "dismissal" is faked — the
-- ticker fades fill/border/text alpha to 0, slides the pill 28dp down via
-- setOffsetY, then parks it off-screen with setVisible(false) and drops
-- the spec from root.children. Until the screen is re-converted the parked
-- node still exists in the retained tree; it is just invisible and
-- off-screen. A new mount strips previous toast specs from root.children
-- so re-opened screens never resurrect or accumulate old toasts.
--
-- Live screens: inserting into root.children only materializes on the
-- NEXT conversion — but the node tree can be re-parented at runtime via
-- the coerced handles. So mount_toast converts the toast through a
-- throwaway donor screen (push_screen), moves the fresh node into the
-- live root with root.__node:add(node), and pops the donor again (only
-- the top of the screen stack ever renders, so nothing flickers and the
-- stack depth is restored). When the root is not live yet, the spec
-- simply rides along and appears on the next open.
-- --------------------------------------------------------------------

function M.mount_toast(root, text, ms)
    if type(root) ~= "table" or type(root.children) ~= "table" then return nil end

    -- strip previous toast specs: re-converts must not accumulate ghosts
    for i = #root.children, 1, -1 do
        if type(root.children[i]) == "table" and root.children[i].__toast then
            table.remove(root.children, i)
        end
    end

    -- a screen rebuild (below) orphans focus state; blur first so the
    -- ESC suppression flag cannot get stuck on
    if activeField then blurField(activeField) end

    local fillRGB, textRGB = 0x1F2126, 0xFFFFFF -- dark glass / white text
    local toast = {
        type = "panel", style = "solid", text = tostring(text or ""),
        textSize = 14, bold = true, textColor = "#FFFFFF",
        radius = 20, padding = 20,
        fillColor = "#F21F2126", fillEndColor = "#F21F2126",
        borderColor = "#00000000", shadow = false, textShadow = false,
        anchor = {0.5, 1}, pivot = {0.5, 1}, offset = {0, -96},
        __toast = true,
    }
    table.insert(root.children, toast)

    local live = root.__node ~= nil
    local wasSuppressed = suppressedByUs
    if live then
        -- materialize NOW: convert through a throwaway donor screen, move
        -- the fresh node under the live root, pop the donor. push/pop run
        -- InputDispatcher.reset() (hover only) — safe mid-click, and the
        -- donor never renders because only the stack top is drawn.
        neoui.push_screen { id = "heroui_toast_donor", tree = { type = "box", children = { toast } } }
        local n, r = nodeOf(toast), root.__node
        if n and r then r:add(n) end
        neoui.pop()
        if wasSuppressed then neoui.set_pop_suppressed(true) end -- donor dance reset it
    end
    -- if root was NOT live: the toast spec rides along and shows the next
    -- time this tree is converted (documented limitation, see above).

    -- fade/slide-away timer: hold for ms, then 260ms of fade + 28dp slide,
    -- then park (invisible + off-screen + spec removed) and go idle.
    local holdS = (tonumber(ms) or 2000) / 1000
    local fadeS = 0.26
    local baseY = -96
    local t0 = neoui.time()
    local parked = false
    addTicker(function()
        if parked then return end -- parked tickers idle out politely
        local n = nodeOf(toast)
        if not n then parked = true return end -- screen tree gone
        local t = neoui.time() - t0 - holdS
        if t < 0 then return end -- still holding
        local a = 1 - clamp(t / fadeS, 0, 1)
        if a <= 0 then
            parked = true
            toast.visible = false
            n:setVisible(false)
            n:setOffsetY(400) -- park well below the screen bottom
            if type(root.children) == "table" then
                for i = #root.children, 1, -1 do
                    if root.children[i] == toast then
                        table.remove(root.children, i) -- no ghost on re-open
                        break
                    end
                end
            end
            return
        end
        local af = math.floor(0xF2 * a + 0.5)
        local ta = math.floor(0xFF * a + 0.5)
        n:setFillColor(alphaOver(fillRGB, af))
        n:setFillEndColor(alphaOver(fillRGB, af))
        n:setTextColor(alphaOver(textRGB, ta))
        n:setOffsetY(baseY + (1 - a) * 28) -- slide down while fading
    end)

    return toast
end

-- --------------------------------------------------------------------
-- navbar: heroui.navbar { title="...", items={ {text="x", onClick=fn}, ... } }
-- Full-width glass top bar (52dp): bold title left, light buttons right.
-- Anchors to the top-left of its parent box by default.
-- --------------------------------------------------------------------

function M.navbar(spec)
    local c = M.colors
    local items = {}
    for _, it in ipairs(spec.items or {}) do
        table.insert(items, M.button {
            text = it.text or "", variant = it.variant or "light",
            w = it.w or 120, h = 36, onClick = it.onClick,
        })
    end
    local bar = {
        type = "panel", style = "glass", w = "match", h = 52, radius = 0,
        shadow = false, textShadow = false,
        anchor = spec.anchor or {0, 0}, pivot = spec.pivot or {0, 0},
        children = {
            {
                type = "label", text = spec.title or "", w = "wrap", textSize = 20,
                bold = true, textColor = c.foreground, shadow = false, textShadow = false,
                anchor = {0, 0.5}, pivot = {0, 0.5}, offset = {24, 0},
            },
            M.row { spacing = 8, anchor = {1, 0.5}, pivot = {1, 0.5}, offset = {-24, 0},
                    children = items },
        },
    }
    return bar
end

-- --------------------------------------------------------------------
-- scrollarea: heroui.scrollarea { w, h=300, step=48, children }
-- Engine-clipped scroll region: the box node sets clip=true and Lua drives
-- scrollY from the wheel (neoui.mouse().wheel accumulates per frame).
-- Children keep layout positions; the renderer shifts them by -scrollY and
-- scissors to the viewport, and the input dispatcher hit-tests the same way.
-- --------------------------------------------------------------------
function M.scrollarea(spec)
    spec.type = "box"
    spec.clip = true
    spec.h = spec.h or 300
    spec.shadow = false
    spec.textShadow = false
    spec.scrollY = 0
    spec.step = spec.step or 48
    -- wrap children in a match-width column (the scrolling content)
    if not (spec.children and #spec.children > 0 and spec.children[1].type == "column") then
        spec.children = { M.column { spacing = spec.spacing or 8, w = "match",
                                     children = spec.children or {} } }
    end
    local col = spec.children[1]
    col.anchor = { 0, 0 }; col.pivot = { 0, 0 }

    addTicker(function()
        local root = nodeOf(spec)
        if not root then return end
        local m = neoui.mouse()
        if m.wheel == 0 then return end
        if not inside(root, m.x, m.y) then return end
        local contentH = (col.__node and col.__node:getHeight()) or 0
        local maxScroll = math.max(0, contentH - root:getHeight())
        if maxScroll <= 0 then return end
        -- scale = viewport px / viewport dp, so the step is resolution-correct
        local scale = (spec.h and spec.h > 0) and (root:getHeight() / spec.h) or 1
        local dir = m.wheel > 0 and -1 or 1 -- wheel up scrolls content up
        local newY = root:getScrollY() + dir * spec.step * scale
        root:setScrollY(clamp(newY, 0, maxScroll))
    end)

    return spec
end

-- --------------------------------------------------------------------
-- showcase screen: neoui.open("ui_demo")
-- --------------------------------------------------------------------

function M.build_demo()
    local c = M.colors
    local column = M.column { spacing = 10, gravity = "start", children = {} }
    local function add(node) table.insert(column.children, node) return node end

    local function header(text)
        add(M.label { text = text, w = "wrap", textSize = 12, bold = true,
                      textColor = c.foregroundMuted, letterSpacing = 1.5 })
    end

    local status
    local function note(s)
        status.text = s
        local n = nodeOf(status)
        if n then n:setText(s) end
    end

    local root -- forward declaration: the toast button closes over it

    add(M.label { text = "Component Showcase", w = "wrap", textSize = 26 })
    add(M.label { text = "NeoUI primitives + HeroUI controls", w = "wrap", textSize = 14,
                  textColor = c.foregroundMuted })
    add(M.accent_bar())
    add(M.spacer(4))

    header("SLIDER")
    add(M.slider {
        w = 320, value = 0.65, min = 0, max = 1, step = 0.05,
        format = function(v) return string.format("%d%%", math.floor(v * 100 + 0.5)) end,
        onChange = function(v) note(string.format("slider -> %.2f", v)) end,
    })

    header("TOGGLE")
    add(M.row { spacing = 12, children = {
        M.toggle { value = false, onChange = function(v) note("toggle -> " .. tostring(v)) end },
        M.label { text = "VSync", w = "wrap", textSize = 16 },
    } })

    header("CHECKBOX")
    add(M.checkbox {
        checked = true, label = "Show FPS overlay",
        onChange = function(v) note("checkbox -> " .. tostring(v)) end,
    })

    header("TEXT FIELD")
    add(M.textfield {
        w = 320, placeholder = "Username", maxLen = 24,
        onChange = function(t) note("text -> " .. t) end,
    })
    add(M.textfield {
        w = 320, placeholder = "Password", password = true, maxLen = 24,
        onChange = function(t) note(string.format("password -> %d chars", #t)) end,
    })

    header("FORM ROWS")
    add(M.form_row("Music volume",
        M.slider {
            w = 180, value = 0.5, min = 0, max = 1, step = 0.05,
            format = function(v) return string.format("%d%%", math.floor(v * 100 + 0.5)) end,
            onChange = function(v) note(string.format("music -> %d%%", math.floor(v * 100 + 0.5))) end,
        }, 140))
    add(M.form_row("Autosave",
        M.toggle { value = true, onChange = function(v) note("autosave -> " .. tostring(v)) end },
        140))

    header("PROGRESS")
    local prog = add(M.progress { w = 320, value = 0, height = 6 })
    add(M.label { text = "animated via an every_frame ticker", w = "wrap",
                  textSize = 12, textColor = c.foregroundMuted })

    header("TOAST")
    add(M.button {
        text = "Show toast", variant = "flat", w = 200,
        onClick = function()
            M.mount_toast(root, "Settings saved", 1800)
            note("toast mounted bottom-center")
        end,
    })

    add(M.spacer(2))
    status = M.label { text = "interact with the controls", w = "wrap", textSize = 13,
                       textColor = c.foregroundMuted }
    add(status)

    add(M.spacer(6))
    add(M.button { text = "Back", variant = "bordered", w = 200,
                   onClick = function() neoui.handle("back") end })

    -- The dropdown goes LAST in the column: its open options card is a child
    -- of the dropdown box, and later tree-order siblings would draw over it
    -- (and win its clicks). It opens UPWARD because it sits at the bottom of
    -- a tall column — a downward card would fall off the screen. See the
    -- dropdown notes above.
    header("DROPDOWN")
    add(M.dropdown {
        w = 220, value = 1, up = true,
        options = { "Survival", "Creative", "Adventure", "Spectator" },
        onChange = function(i, opt) note("dropdown -> " .. tostring(opt) .. " (#" .. i .. ")") end,
    })

    -- progress demo animation: ping-pong 0..1 through spec.set, which keeps
    -- both the spec table (re-open state) and the live node in sync
    local p0 = neoui.time()
    addTicker(function()
        local f = nodeOf(prog.__fill)
        if not f then return end
        prog.set((math.sin((neoui.time() - p0) * 1.2) + 1) / 2)
    end)

    root = { type = "box", children = {
        M.navbar {
            title = "Neogenesis UI",
            items = { { text = "Back", onClick = function() neoui.handle("back") end } },
        },
        M.card { w = 540, anchor = {0.5, 0.52}, pivot = {0.5, 0.5}, children = { column } },
    } }
    return root
end

if neoui and neoui.register_screen then
    neoui.register_screen { id = "ui_demo", tree = M.build_demo() }
end

return M
