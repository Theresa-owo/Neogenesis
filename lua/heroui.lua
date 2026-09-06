-- HeroUI for NeoUI (https://heroui.com): design tokens + components.
-- Every component takes a spec table and returns a completed node
-- table for neoui.show_screen.

local M = {}

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
M.metrics = { buttonHeight = 44, buttonWidth = 400 }

function M.label(spec)
    spec.type = "label"
    spec.textSize = spec.textSize or 18
    spec.textColor = spec.textColor or M.colors.foreground
    spec.w = spec.w or "match"
    spec.bold = spec.bold or true
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
    spec.bold = spec.bold or true
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

return M
