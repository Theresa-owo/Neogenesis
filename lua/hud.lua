-- In-world HUD for NeoUI: hotbar, health/hunger pips, air bubbles, XP bar,
-- crosshair, held item and a held-item name popup — all authored as a NeoUI
-- tree and driven from Kotlin data.
--
-- Loaded automatically by the `hudapi` Kotlin plugin (ApiHud) when the Lua
-- runtime boots; `renderInPass` only draws HudRenderer screens in-world, so
-- the menu path is untouched. Style: HeroUI light tokens — #006FEE accent,
-- rounded corners, dark frosted glass, no text shadows.
--
-- Extras beyond the static layout, all driven from the single every_frame
-- tick below (each block pcall-guarded: one throwing hook would clear every
-- registered hook in the engine's tickFrame loop):
--   * low-health heartbeat: at <= 6 HP the full heart pips pulse between
--     #F31260 and #FF4D6D on a 600ms lub-dub rhythm (color only — scaling
--     row children would break the pip spacing)
--   * held-item name popup: vanilla-style pill above the hotbar that fades
--     in (120ms), holds (1200ms) and fades out (300ms) on slot/item change
--   * air bubbles: cyan pips above the hunger row while air < max

-- "#RRGGBB" / "#AARRGGBB" -> signed 32-bit int for Kotlin color setters
-- (Java int params saturate on out-of-range doubles, so pre-wrap to signed).
local function C(s)
    local hex = string.sub(s, 2)
    local v = tonumber(hex, 16) or 0
    if string.len(hex) == 6 then v = v + 0xFF000000 end
    if v > 2147483647 then v = v - 4294967296 end
    return v
end

local WHITE          = "#FFFFFFFF"
local ACCENT         = "#FF006FEE"
local SLOT_FILL      = "#99121A26"
local SLOT_FILL_SEL  = "#B3006FEE"
local SLOT_BORDER    = "#2EFFFFFF"
local SLOT_BORDER_SEL = "#FFFFFFFF"
local PIP_EMPTY      = "#40FFFFFF"
local HEART_FULL     = "#FFF31260"
local HEART_HALF     = "#80F31260"
local HEART_BRIGHT   = "#FFFF4D6D"   -- heartbeat pulse peak
local FOOD_FULL      = "#FFF5A524"
local FOOD_HALF      = "#80F5A524"
local BUBBLE         = "#FF22D3EE"   -- air bubble cyan
local XP_TRACK       = "#80101420"
local XP_GREEN       = "#FF58C142"
local GLASS_BORDER   = "#2EFFFFFF"

-- signed-int versions for per-frame Kotlin setters (strings above are parsed
-- by the Kotlin converter when building the tree; setters take raw ints)
local SLOT_FILL_I       = C(SLOT_FILL)
local SLOT_FILL_SEL_I   = C(SLOT_FILL_SEL)
local SLOT_BORDER_I     = C(SLOT_BORDER)
local SLOT_BORDER_SEL_I = C(SLOT_BORDER_SEL)
local HEART_FULL_I      = C(HEART_FULL)
local HEART_HALF_I      = C(HEART_HALF)
local FOOD_FULL_I       = C(FOOD_FULL)
local FOOD_HALF_I       = C(FOOD_HALF)
local PIP_EMPTY_I       = C(PIP_EMPTY)
local BUBBLE_I          = C(BUBBLE)

local HOTBAR_W = 500     -- 9 x 52dp slots + 8 x 4dp gaps
local XP_FILL_W = 0      -- set per frame from xpProgress

-- heartbeat: one lub-dub cycle. The pulse envelope is two sine half-waves
-- per 600ms period (lub peaks at 75ms, dub at 295ms), then rest.
local HB_PERIOD = 0.6
-- popup lifecycle seconds: fade in, hold, fade out, then park invisible
local POP_IN_S, POP_HOLD_S, POP_OUT_S = 0.12, 1.20, 0.30
-- popup colors as RGBA components: the fades lerp the alpha byte, so the
-- setters need per-channel arithmetic (Lua 5.1 has no bitwise ops)
local POP_FILL_A = 0xE6
local POP_LINE_A = 0x2E
local POP_R, POP_G, POP_B = 0x12, 0x1A, 0x26
local HEART_FULL_RGB   = { 243, 18, 96 }   -- #F31260
local HEART_BRIGHT_RGB = { 255, 77, 109 }  -- #FF4D6D

local tree
local slots = {}         -- [i] = { spec, icon, count } node spec tables
local hearts = {}        -- [i] = pip spec
local foods = {}         -- [i] = pip spec
local bubbles = {}       -- [i] = pip spec
local bubbleRow          -- row spec (hidden wholesale while air is full)
local xp = {}            -- { track, fill, level }
local held = {}          -- { panel, icon, count }
local popup = {}         -- { panel, label } held-item name pill

local shown = false
local lastSig = nil
local hbClock = 0        -- heartbeat phase, seconds into the current cycle
local popClock = nil     -- seconds since the popup triggered; nil = parked
local popKey = nil       -- last seen "selected|heldName" trigger key

-- RGBA -> signed 32-bit ARGB for the per-frame setters (alpha is animated,
-- so the string parser can't be used here)
local function packArgb(a, r, g, b)
    local n = math.floor(a + 0.5) * 16777216
        + math.floor(r + 0.5) * 65536
        + math.floor(g + 0.5) * 256
        + math.floor(b + 0.5)
    if n >= 2147483648 then n = n - 4294967296 end
    return n
end

-- heartbeat envelope, t in seconds: two sine bumps per HB_PERIOD, 0..1
local function heartbeatPulse(t)
    local ph = t % HB_PERIOD
    local v = 0
    if ph < 0.15 then                            -- lub
        v = math.sin(ph / 0.15 * math.pi)
    elseif ph >= 0.22 and ph < 0.37 then         -- dub
        v = math.sin((ph - 0.22) / 0.15 * math.pi)
    end
    if v < 0 then v = 0 end
    if v > 1 then v = 1 end
    return v
end

-- blend #F31260 -> #FF4D6D by v (0..1), packed for the color setters
local function heartBeatColor(v)
    local f, b = HEART_FULL_RGB, HEART_BRIGHT_RGB
    return packArgb(255,
        f[1] + (b[1] - f[1]) * v,
        f[2] + (b[2] - f[2]) * v,
        f[3] + (b[3] - f[3]) * v)
end

local function buildTree()
    -- hotbar: 9 glass slots with an icon node + count label each
    local hotbarChildren = {}
    for i = 1, 9 do
        local iconSpec = { type = "icon", w = 40, h = 40,
                           anchor = {0.5, 0.5}, pivot = {0.5, 0.5},
                           fillColor = WHITE, visible = false }
        local countSpec = { type = "label", text = "", textSize = 13, bold = true,
                            textColor = WHITE,
                            anchor = {1, 1}, pivot = {1, 1}, offset = {-5, -2} }
        local slotSpec = { type = "panel", style = "glass", w = 52, h = 52, radius = 12,
                           shadow = false,
                           fillColor = SLOT_FILL, fillEndColor = SLOT_FILL,
                           borderColor = SLOT_BORDER,
                           children = { iconSpec, countSpec } }
        slots[i] = { spec = slotSpec, icon = iconSpec, count = countSpec }
        table.insert(hotbarChildren, slotSpec)
    end
    local hotbar = { type = "row", spacing = 4,
                     anchor = {0.5, 1}, pivot = {0.5, 1}, offset = {0, -24},
                     children = hotbarChildren }

    -- hearts: 10 pips above the hotbar, left-aligned with its left edge
    local heartPips = {}
    for i = 1, 10 do
        local pip = { type = "panel", style = "solid", w = 14, h = 14, radius = 4,
                      shadow = false,
                      fillColor = PIP_EMPTY, fillEndColor = PIP_EMPTY,
                      borderColor = "#00000000" }
        hearts[i] = pip
        table.insert(heartPips, pip)
    end
    local heartRow = { type = "row", spacing = 2,
                       anchor = {0.5, 1}, pivot = {1, 1}, offset = {-262, -88},
                       children = heartPips }

    -- hunger: 10 pips, right-aligned with the hotbar's right edge
    local foodPips = {}
    for i = 1, 10 do
        local pip = { type = "panel", style = "solid", w = 14, h = 14, radius = 4,
                      shadow = false,
                      fillColor = PIP_EMPTY, fillEndColor = PIP_EMPTY,
                      borderColor = "#00000000" }
        foods[i] = pip
        table.insert(foodPips, pip)
    end
    local foodRow = { type = "row", spacing = 2,
                      anchor = {0.5, 1}, pivot = {0, 1}, offset = {262, -88},
                      children = foodPips }

    -- air bubbles: 10 small cyan pips above the hunger row, same right edge;
    -- the row ships visible and the tick hides it while air is full
    local bubblePips = {}
    for i = 1, 10 do
        local pip = { type = "panel", style = "solid", w = 10, h = 10, radius = 5,
                      shadow = false,
                      fillColor = PIP_EMPTY, fillEndColor = PIP_EMPTY,
                      borderColor = "#00000000" }
        bubbles[i] = pip
        table.insert(bubblePips, pip)
    end
    bubbleRow = { type = "row", spacing = 3,
                  anchor = {0.5, 1}, pivot = {0, 1}, offset = {262, -104},
                  children = bubblePips }

    -- XP bar: thin green fill inside a dark track, level number above
    local xpFill = { type = "panel", style = "solid", w = XP_FILL_W, h = "match",
                     radius = 3, shadow = false,
                     fillColor = XP_GREEN, fillEndColor = XP_GREEN,
                     borderColor = "#00000000",
                     anchor = {0, 0.5}, pivot = {0, 0.5} }
    local xpTrack = { type = "panel", style = "solid", w = HOTBAR_W, h = 6, radius = 3,
                      shadow = false,
                      fillColor = XP_TRACK, fillEndColor = XP_TRACK,
                      borderColor = "#24FFFFFF",
                      anchor = {0.5, 1}, pivot = {0.5, 1}, offset = {0, -80},
                      children = { xpFill } }
    local xpLevel = { type = "label", text = "", textSize = 15, bold = true,
                      textColor = XP_GREEN,
                      anchor = {0.5, 1}, pivot = {0.5, 1}, offset = {0, -88} }
    xp = { track = xpTrack, fill = xpFill, level = xpLevel }

    -- crosshair: two thin white panels centered
    local crossH = { type = "panel", style = "solid", w = 18, h = 2, radius = 1,
                     shadow = false,
                     fillColor = "#D9FFFFFF", fillEndColor = "#D9FFFFFF",
                     borderColor = "#00000000",
                     anchor = {0.5, 0.5}, pivot = {0.5, 0.5} }
    local crossV = { type = "panel", style = "solid", w = 2, h = 18, radius = 1,
                     shadow = false,
                     fillColor = "#D9FFFFFF", fillEndColor = "#D9FFFFFF",
                     borderColor = "#00000000",
                     anchor = {0.5, 0.5}, pivot = {0.5, 0.5} }

    -- held item: larger icon above the hotbar's right edge
    local heldIcon = { type = "icon", w = 48, h = 48,
                       anchor = {0.5, 0.5}, pivot = {0.5, 0.5},
                       fillColor = WHITE, visible = false }
    local heldCount = { type = "label", text = "", textSize = 15, bold = true,
                        textColor = WHITE,
                        anchor = {1, 1}, pivot = {1, 1}, offset = {-6, -3} }
    local heldPanel = { type = "panel", style = "glass", w = 60, h = 60, radius = 12,
                        shadow = false,
                        fillColor = SLOT_FILL, fillEndColor = SLOT_FILL,
                        borderColor = GLASS_BORDER,
                        anchor = {0.5, 1}, pivot = {1, 1}, offset = {250, -86},
                        children = { heldIcon, heldCount } }
    held = { panel = heldPanel, icon = heldIcon, count = heldCount }

    -- held-item name popup: dark glass pill centered 64dp above the hotbar's
    -- top edge (hotbar top sits at -76dp). Ships invisible; the tick fades
    -- its fill/border/text alpha and parks it after the fade-out.
    local popLabel = { type = "label", text = "", textSize = 14, bold = true,
                       textColor = "#FFFFFFFF",
                       anchor = {0.5, 0.5}, pivot = {0.5, 0.5} }
    local popPanel = { type = "panel", style = "glass", w = 320, h = 30, radius = 15,
                       shadow = false,
                       fillColor = "#E6121A26", fillEndColor = "#E6121A26",
                       borderColor = "#2EFFFFFF",
                       anchor = {0.5, 1}, pivot = {0.5, 1}, offset = {0, -140},
                       visible = false,
                       children = { popLabel } }
    popup = { panel = popPanel, label = popLabel }

    tree = { type = "box", children =
        { hotbar, heartRow, foodRow, xpTrack, xpLevel, crossH, crossV, heldPanel,
          bubbleRow, popPanel } }
end

local function fmtCount(n)
    return string.format("%d", math.floor(n))
end

local function update(dt)
    local p = hudapi.player()
    if not p then
        if shown then hudapi.hide_hud(); shown = false; lastSig = nil end
        popClock = nil   -- fresh tree on the next world: clean slate
        hbClock = 0
        popKey = nil     -- observe only after (re)join: no popup on spawn
        return
    end

    if not shown then
        hudapi.show_hud(tree)
        shown = true
        lastSig = nil   -- force a full icon re-apply on the fresh tree
    end

    -- delta for the small animations; clamped so a hitch can't skip a whole
    -- fade phase (and nil/negative dt can't stall them)
    local dtc = tonumber(dt) or 0.016
    if dtc <= 0 or dtc > 0.25 then dtc = 0.016 end

    -- selection highlight every frame (cheap property writes)
    for i = 1, 9 do
        local slotNode = slots[i].spec.__node
        if p.selected == i then
            slotNode:setFillColor(SLOT_FILL_SEL_I)
            slotNode:setFillEndColor(SLOT_FILL_SEL_I)
            slotNode:setBorderColor(SLOT_BORDER_SEL_I)
        else
            slotNode:setFillColor(SLOT_FILL_I)
            slotNode:setFillEndColor(SLOT_FILL_I)
            slotNode:setBorderColor(SLOT_BORDER_I)
        end
    end

    -- hotbar icons + counts: only rebuilt when the inventory signature changes
    if p.invSig ~= lastSig then
        lastSig = p.invSig
        for i = 1, 9 do
            local s = p.slots[i]
            local iconNode = slots[i].icon.__node
            local countNode = slots[i].count.__node
            if s ~= nil and s.count ~= nil and s.count > 0 and s.u0 ~= nil then
                hudapi.set_icon(iconNode, s.atlasIndex, s.u0, s.v0, s.u1, s.v1)
                iconNode:setVisible(true)
                countNode:setText(s.count > 1 and fmtCount(s.count) or "")
            else
                iconNode:setVisible(false)
                countNode:setText("")
            end
        end
        local heldIconNode = held.icon.__node
        if p.heldCount ~= nil and p.heldCount > 0 and p.heldU0 ~= nil then
            hudapi.set_icon(heldIconNode, p.heldAtlas, p.heldU0, p.heldV0, p.heldU1, p.heldV1)
            heldIconNode:setVisible(true)
            held.count.__node:setText(p.heldCount > 1 and fmtCount(p.heldCount) or "")
            held.panel.__node:setVisible(true)
        else
            heldIconNode:setVisible(false)
            held.count.__node:setText("")
            held.panel.__node:setVisible(false)
        end
    end

    -- held-item name popup: retrigger when the selected slot changes or the
    -- item in it changes (invSig moves on ANY inventory change, so track the
    -- selection + display name ourselves)
    pcall(function()
        local key = tostring(p.selected or 0) .. "|" .. tostring(p.heldName or "")
        if popKey == nil then
            popKey = key   -- first observed state: no popup on HUD spawn
        elseif key ~= popKey then
            popKey = key
            local name = tostring(p.heldName or "")
            if name ~= "" then
                popClock = 0
                local ln, pn = popup.label.__node, popup.panel.__node
                if ln then ln:setText(name) end
                if pn then pn:setVisible(true) end
            end
        end
        -- lifecycle: fade in -> hold -> fade out -> park (invisible)
        if popClock ~= nil then
            local pn, ln = popup.panel.__node, popup.label.__node
            if not pn or not ln then
                popClock = nil
            else
                popClock = popClock + dtc
                local a
                if popClock < POP_IN_S then
                    a = popClock / POP_IN_S
                elseif popClock < POP_IN_S + POP_HOLD_S then
                    a = 1
                elseif popClock < POP_IN_S + POP_HOLD_S + POP_OUT_S then
                    a = 1 - (popClock - POP_IN_S - POP_HOLD_S) / POP_OUT_S
                else
                    a = 0
                end
                if a <= 0 then
                    popClock = nil
                    pn:setVisible(false)
                else
                    pn:setFillColor(packArgb(POP_FILL_A * a, POP_R, POP_G, POP_B))
                    pn:setFillEndColor(packArgb(POP_FILL_A * a, POP_R, POP_G, POP_B))
                    pn:setBorderColor(packArgb(POP_LINE_A * a, 255, 255, 255))
                    ln:setTextColor(packArgb(255 * a, 255, 255, 255))
                end
            end
        end
    end)

    -- health pips: 2 HP per pip, half pips on odd remainders; while health
    -- is low (<= 6 HP) the FULL pips pulse between #F31260 and #FF4D6D on
    -- the heartbeat rhythm (half/empty pips stay static)
    pcall(function()
        local lowHealth = (p.health or 0) <= 6.0
        if lowHealth then
            hbClock = (hbClock + dtc) % HB_PERIOD
        else
            hbClock = 0   -- next dip starts on a fresh "lub"
        end
        local fullC = lowHealth and heartBeatColor(heartbeatPulse(hbClock))
            or HEART_FULL_I
        for i = 1, 10 do
            local node = hearts[i].__node
            if node then
                local seg = p.health - (i - 1) * 2
                local c = seg >= 2 and fullC or (seg >= 1 and HEART_HALF_I or PIP_EMPTY_I)
                node:setFillColor(c)
                node:setFillEndColor(c)
            end
        end
    end)

    -- hunger pips: 2 food per pip, half pips on odd remainders
    pcall(function()
        for i = 1, 10 do
            local node = foods[i].__node
            if node then
                local seg = p.food - (i - 1) * 2
                local c = seg >= 2 and FOOD_FULL_I or (seg >= 1 and FOOD_HALF_I or PIP_EMPTY_I)
                node:setFillColor(c)
                node:setFillEndColor(c)
            end
        end
    end)

    -- air bubbles: ceil(air / maxAir * 10) cyan pips; the whole row is
    -- parked (invisible) while air is full
    pcall(function()
        local rowNode = bubbleRow.__node
        if not rowNode then return end
        local air = tonumber(p.air) or 0
        local maxAir = tonumber(p.maxAir) or 300
        if maxAir <= 0 then maxAir = 300 end
        if air >= maxAir then
            rowNode:setVisible(false)
        else
            rowNode:setVisible(true)
            local n = math.ceil(air / maxAir * 10)
            if n < 0 then n = 0 end
            if n > 10 then n = 10 end
            for i = 1, 10 do
                local node = bubbles[i].__node
                if node then
                    local c = i <= n and BUBBLE_I or PIP_EMPTY_I
                    node:setFillColor(c)
                    node:setFillEndColor(c)
                end
            end
        end
    end)

    -- xp bar + level
    local progress = p.xpProgress or 0
    if progress < 0 then progress = 0 end
    if progress > 1 then progress = 1 end
    xp.fill.__node:setDpWidth(progress * HOTBAR_W)
    xp.level.__node:setText((p.xp or 0) > 0 and fmtCount(p.xp) or "")
end

buildTree()

neoui.every_frame(update)
