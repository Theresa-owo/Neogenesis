-- In-world HUD for NeoUI: hotbar, health/hunger pips, XP bar, crosshair and
-- held item — all authored as a NeoUI tree and driven from Kotlin data.
--
-- Loaded automatically by the `hudapi` Kotlin plugin (ApiHud) when the Lua
-- runtime boots; `renderInPass` only draws HudRenderer screens in-world, so
-- the menu path is untouched. Style: HeroUI light tokens — #006FEE accent,
-- rounded corners, dark frosted glass, no text shadows.

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
local FOOD_FULL      = "#FFF5A524"
local FOOD_HALF      = "#80F5A524"
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

local HOTBAR_W = 500     -- 9 x 52dp slots + 8 x 4dp gaps
local XP_FILL_W = 0      -- set per frame from xpProgress

local tree
local slots = {}         -- [i] = { spec, icon, count } node spec tables
local hearts = {}        -- [i] = pip spec
local foods = {}         -- [i] = pip spec
local xp = {}            -- { track, fill, level }
local held = {}          -- { panel, icon, count }

local shown = false
local lastSig = nil

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

    tree = { type = "box", children =
        { hotbar, heartRow, foodRow, xpTrack, xpLevel, crossH, crossV, heldPanel } }
end

local function fmtCount(n)
    return string.format("%d", math.floor(n))
end

local function update(dt)
    local p = hudapi.player()
    if not p then
        if shown then hudapi.hide_hud(); shown = false; lastSig = nil end
        return
    end

    if not shown then
        hudapi.show_hud(tree)
        shown = true
        lastSig = nil   -- force a full icon re-apply on the fresh tree
    end

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

    -- health/hunger pips: 2 HP per pip, half pips on odd remainders
    for i = 1, 10 do
        local seg = p.health - (i - 1) * 2
        local c = seg >= 2 and HEART_FULL_I or (seg >= 1 and HEART_HALF_I or PIP_EMPTY_I)
        hearts[i].__node:setFillColor(c)
        hearts[i].__node:setFillEndColor(c)
    end
    for i = 1, 10 do
        local seg = p.food - (i - 1) * 2
        local c = seg >= 2 and FOOD_FULL_I or (seg >= 1 and FOOD_HALF_I or PIP_EMPTY_I)
        foods[i].__node:setFillColor(c)
        foods[i].__node:setFillEndColor(c)
    end

    -- xp bar + level
    local progress = p.xpProgress or 0
    if progress < 0 then progress = 0 end
    if progress > 1 then progress = 1 end
    xp.fill.__node:setDpWidth(progress * HOTBAR_W)
    xp.level.__node:setText((p.xp or 0) > 0 and fmtCount(p.xp) or "")
end

buildTree()

neoui.every_frame(update)
