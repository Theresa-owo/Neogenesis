-- World Ender Console — a NeoUI replica of the WorldEnder WebUI
-- (React + HeroUI + Material 3 dynamic color, source:
--  E:/Development/Modifier/World Ender/WorldEnderWebUI).
--
-- Reproduces the AppShell (top bar with brand / horizontal tabs / connection
-- chip), the Material 3 dark scheme for source #6750a4, and the Overview
-- page's FeatureCard grid (live coordinates / connection / quick toggles /
-- quick links). Tab switching re-converts the tree (neoui.show_screen) —
-- spec tables hold per-page control state, so controls keep their values.

local heroui = require("heroui")

local M = {}

-- Material 3 dark scheme for source #6750a4 (mirrors materialTheme.ts).
local C = {
    primary              = "#FFD0BCFF",
    onPrimary            = "#FF381E72",
    primaryContainer     = "#FF4F378B",
    onPrimaryContainer   = "#FFEADDFF",
    secondaryContainer   = "#FF4A4458",
    surface              = "#FF141218",
    surfaceContainer     = "#FF211F26",
    onSurface            = "#FFE6E0E9",
    onSurfaceVariant     = "#FFCAC4D0",
    success              = "#FF17C964",
}

local NAV = {
    { id = "overview",    label = "Overview" },
    { id = "coordinates", label = "Coordinates" },
    { id = "player",      label = "Player" },
    { id = "world",       label = "World" },
    { id = "rendering",   label = "Rendering" },
    { id = "runtime",     label = "Runtime" },
}

local state = { page = "overview", toggles = { noclip = false, speed = false, dash = false } }

-- ------------------------------------------------------------------
-- builders (Material 3 dark)
-- ------------------------------------------------------------------

local function card(spec)
    spec.w = spec.w or "match" -- grid cards fill their column
    spec.type = "panel"
    spec.style = "solid"
    spec.radius = 12
    spec.fillColor = C.surfaceContainer
    spec.fillEndColor = C.surfaceContainer
    spec.borderColor = "#00000000"
    spec.shadow = false
    spec.padding = 20
    return spec
end

local function label(spec)
    spec.type = "label"
    spec.shadow = false
    spec.textShadow = false
    if spec.bold == nil then spec.bold = false end
    return spec
end

local function pageHeader(eyebrow, title, desc)
    return { type = "column", spacing = 4, w = "match", children = {
        label { text = string.upper(eyebrow), textSize = 12, bold = true,
                letterSpacing = 3, textColor = C.primary },
        label { text = title, textSize = 30, bold = true, textColor = C.onSurface },
        label { text = desc, textSize = 14, textColor = C.onSurfaceVariant },
    } }
end

local function featureCardHeader(overline, title)
    return { type = "column", spacing = 2, w = "match", children = {
        label { text = string.upper(overline), textSize = 11, bold = true,
                letterSpacing = 2, textColor = C.primary },
        label { text = title, textSize = 19, bold = true, textColor = C.onSurface },
    } }
end

local function kvRow(key, value)
    return { type = "row", w = "match", children = {
        label { text = key, textSize = 14, textColor = C.onSurfaceVariant, w = "match" },
        label { text = value, textSize = 14, bold = true, textColor = C.onSurface },
    } }
end

-- ------------------------------------------------------------------
-- pages
-- ------------------------------------------------------------------

local function overviewPage()
    local colL = { type = "column", spacing = 16, w = "match", children = {} }
    local colR = { type = "column", spacing = 16, w = "match", children = {} }
    local function addL(node) table.insert(colL.children, node) end
    local function addR(node) table.insert(colR.children, node) end

    -- Live Position card
    local coord = card { children = { featureCardHeader("live position", "Live Position") } }
    local xyzRow = { type = "row", spacing = 16, w = "match", children = {} }
    for _, axis in ipairs({ "X", "Y", "Z" }) do
        table.insert(xyzRow.children, { type = "column", spacing = 2, w = "match", children = {
            label { text = axis, textSize = 11, bold = true, letterSpacing = 2,
                    textColor = C.onSurfaceVariant },
            label { text = "1427.5", textSize = 22, bold = true, textColor = C.onSurface },
            label { text = "Δ 0.000", textSize = 11, textColor = C.onSurfaceVariant },
        } })
    end
    table.insert(coord.children, xyzRow)
    table.insert(coord.children, { type = "row", w = "match", children = {
        label { text = "Moving", textSize = 12, textColor = C.onSurfaceVariant, w = "match" },
        label { text = "12 ms", textSize = 12, textColor = C.onSurfaceVariant },
    } })
    addL(coord)

    -- Connection card
    local conn = card { children = { featureCardHeader("connection", "Connected") } }
    for _, kv in ipairs({ { "connection.state", "Connected" },
                          { "coordStrategy", "SSE" },
                          { "positionAgeMs", "12 ms" } }) do
        table.insert(conn.children, kvRow(kv[1], kv[2]))
    end
    addL(conn)

    -- Quick Toggles card
    local quick = card { children = { featureCardHeader("ability", "Quick Toggles") } }
    for _, def in ipairs({ { key = "noclip", label = "Noclip" },
                           { key = "speed", label = "Movement Speed" },
                           { key = "dash",  label = "Infinite Stamina" } }) do
        local row = { type = "row", w = "match", children = {
            label { text = def.label, textSize = 15, bold = true,
                    textColor = C.onSurface, w = "match" },
        } }
        local t = heroui.toggle {
            value = state.toggles[def.key],
            onChange = function(v) state.toggles[def.key] = v end,
        }
        t.h = 28
        table.insert(row.children, t)
        table.insert(quick.children, row)
    end
    addR(quick)

    -- Quick Links card
    local links = card { children = { featureCardHeader("quick links", "Quick Links") } }
    for _, pg in ipairs(NAV) do
        table.insert(links.children, heroui.button {
            text = pg.label, variant = "bordered", w = "match", h = 36, textSize = 14,
            onClick = function() state.page = pg.id end,
        })
    end
    addR(links)

    return { type = "column", spacing = 16, w = "match", children = {
        pageHeader("overview", "Dashboard",
                   "World Ender console — live status and feature control."),
        { type = "row", spacing = 16, w = "match", children = { colL, colR } },
    } }
end

local function placeholderPage(id)
    local name = id
    for _, pg in ipairs(NAV) do if pg.id == id then name = pg.label end end
    return { type = "column", spacing = 16, w = "match", children = {
        pageHeader(id, name, "Page body is being ported from the WorldEnder WebUI."),
        card { children = { label { text = "Content coming up next.",
                                    textSize = 15, textColor = C.onSurfaceVariant } } },
    } }
end

-- ------------------------------------------------------------------
-- shell
-- ------------------------------------------------------------------

function M.register()
    local tabRecs = {}
    local paintTabs
    local tree

    -- top bar ------------------------------------------------------------
    local topbar = { type = "row", w = "match", h = 42, children = {} }
    table.insert(topbar.children, { type = "panel", w = 30, h = 30, radius = 8,
        fillColor = C.primary, fillEndColor = C.primary, borderColor = "#00000000",
        shadow = false, children = { label { text = "W", textSize = 15, bold = true,
            textColor = C.onPrimary } } })
    table.insert(topbar.children, label { text = "World Ender", textSize = 15,
        bold = true, textColor = C.onSurface })
    local tabs = { type = "row", spacing = 4, children = {} }
    for _, pg in ipairs(NAV) do
        local lbl = label { text = pg.label, textSize = 14, textColor = C.onSurface }
        local btn = { type = "panel", w = "wrap", h = 30, radius = 8,
                      fillColor = "#00000000", fillEndColor = "#00000000",
                      borderColor = "#00000000", shadow = false,
                      children = { lbl },
                      onClick = function()
                          state.page = pg.id
                          neoui.show_screen { id = "we", tree = tree }
                          paintTabs()
                      end }
        table.insert(tabRecs, { id = pg.id, spec = btn, lbl = lbl })
        table.insert(tabs.children, btn)
    end
    table.insert(topbar.children, { type = "box", w = 8, h = 8, shadow = false })
    table.insert(topbar.children, tabs)
    table.insert(topbar.children, { type = "box", w = "match", h = 8, shadow = false })
    table.insert(topbar.children, { type = "panel", w = 96, h = 26, radius = 13,
        fillColor = "#1F17C964", fillEndColor = "#1F17C964", borderColor = "#00000000",
        shadow = false, children = { label { text = "Connected", textSize = 12, bold = true,
            textColor = C.success } } })
    table.insert(topbar.children, label { text = "⌕", textSize = 18,
        textColor = C.onSurface })
    table.insert(topbar.children, label { text = "⚙", textSize = 17,
        textColor = C.onSurface })

    -- page content ---------------------------------------------------------
    local function pageContent()
        if state.page == "overview" then return overviewPage() end
        return placeholderPage(state.page)
    end

    paintTabs = function(nodes)
        for _, rec in ipairs(tabRecs) do
            local active = rec.id == state.page
            local n = rec.spec.__node
            local ln = rec.lbl.__node
            if n then
                local fill = active and C.primaryContainer or "#00000000"
                n:setFillColor(neoui.argb_of(fill))
                n:setFillEndColor(neoui.argb_of(fill))
            end
            if ln then
                ln:setTextColor(neoui.argb_of(
                    active and C.onPrimaryContainer or C.onSurface))
            end
        end
    end

    tree = { type = "box", children = {} }
    table.insert(tree.children, { type = "panel", w = "match", h = "match",
        radius = 0, fillColor = C.surface, fillEndColor = C.surface,
        borderColor = "#00000000", shadow = false,
        children = { { type = "column", w = "match", h = "match", spacing = 0,
                       padding = 8,
                       children = { topbar,
                                    { type = "box", w = "match", h = 8, shadow = false },
                                    pageContent() } } } })

    -- tab switching: re-showing the same screen re-converts the (mutated)
    -- spec tree, so tab paint + page content refresh through fresh __nodes
    neoui.register_screen {
        id = "we",
        tree = function() return tree end,
    }
end

M.register() -- self-register on require; opened via neoui.open("we")

return M
