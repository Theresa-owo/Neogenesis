// NOTE: this file lives at src/net/theresa/ui/hud/ApiHud.kt but declares the
// net.theresa.ui.lua package on purpose: LuaApiRegistry discovers @LuaPlugin
// installers with a Reflections scan rooted at net.theresa.ui.lua.
package net.theresa.ui.lua

import net.minecraft.client.Minecraft
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.theresa.ui.hud.HudRenderer
import net.theresa.ui.hud.ItemIcons
import net.theresa.ui.scene.UiNode
import net.theresa.ui.screen.NeoScreen
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import java.nio.file.Files
import java.nio.file.Paths

/**
 * `hudapi` Lua module: registers the in-world HUD screen and feeds it player
 * state each frame.
 *
 *   hudapi.show_hud(tree)  convert the Lua tree (nodeFromLua) and render it
 *                          as the HUD screen over the world (in-world only)
 *   hudapi.hide_hud()      stop drawing the HUD
 *   hudapi.player()        { health, maxHealth, food, air, xp, xpProgress,
 *                            selected (1-based), invSig, slots = {
 *                            { count, atlasIndex, u0, v0, u1, v1, name, id }
 *                            x9 }, heldCount, heldAtlas, heldU0..heldV1,
 *                            heldName } — nil when no world/player
 *   hudapi.set_icon(nodeHandle, atlasIndex, u0, v0, u1, v1)
 *                          bind an atlas sprite rect to an "icon" UiNode
 *                          (the coerced __node handle from the tree)
 */
@LuaPlugin(name = "hudapi")
object ApiHud {

    fun install(globals: Globals, runtime: LuaUiRuntime) {
        val api = LuaValue.tableOf()

        api.set("show_hud", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                // never propagate: a throw inside tickFrame drops every frame hook
                try {
                    val tree = args.arg1().checktable()
                    val node = runtime.nodeFromLua(tree)
                    seedIconSpecs(tree)
                    HudRenderer.screen = NeoScreen("hud", node)
                } catch (t: Throwable) {
                    System.err.println("[NeoUI lua] show_hud failed: $t")
                }
                return LuaValue.NIL
            }
        })

        api.set("hide_hud", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                HudRenderer.screen = null
                return LuaValue.NIL
            }
        })

        api.set("set_icon", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val node = args.arg1().optuserdata(UiNode::class.java, null) as? UiNode
                    ?: return LuaValue.NIL
                ItemIcons.registerSpec(
                    node, ItemIcons.IconSpec(
                        args.arg(2).optint(0),
                        args.arg(3).optdouble(0.0).toFloat(),
                        args.arg(4).optdouble(0.0).toFloat(),
                        args.arg(5).optdouble(1.0).toFloat(),
                        args.arg(6).optdouble(1.0).toFloat()
                    )
                )
                return LuaValue.NIL
            }
        })

        api.set("player", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                // never propagate: a throw inside tickFrame drops every frame hook
                return try {
                    playerTable()
                } catch (t: Throwable) {
                    System.err.println("[NeoUI lua] hudapi.player failed: $t")
                    LuaValue.NIL
                }
            }
        })

        globals.set("hudapi", api)
        val neoui = globals.get("neoui")
        if (neoui.istable()) neoui.set("hudapi", api)

        loadHudScript(globals)
    }

    /** Player state for Lua; nil when there is no world/player. */
    private fun playerTable(): LuaValue {
        val mc = Minecraft.getMinecraft() ?: return LuaValue.NIL
        val player = mc.thePlayer ?: return LuaValue.NIL
        if (mc.theWorld == null) return LuaValue.NIL
        val inv = player.inventory ?: return LuaValue.NIL

        val t = LuaTable()
        t.set("health", LuaValue.valueOf(player.health.toDouble()))
        t.set("maxHealth", LuaValue.valueOf(player.maxHealth.toDouble()))
        t.set("food", LuaValue.valueOf(player.foodStats.foodLevel.toDouble()))
        t.set("air", LuaValue.valueOf(player.air.toDouble()))
        t.set("maxAir", LuaValue.valueOf(300.0))
        t.set("xp", LuaValue.valueOf(player.experienceLevel.toDouble()))
        t.set("xpProgress", LuaValue.valueOf(player.experience.toDouble()))
        t.set("selected", LuaValue.valueOf(inv.currentItem + 1))

        val sig = StringBuilder()
        val slots = LuaTable()
        for (i in 0 until 9) {
            val stack = inv.mainInventory[i]
            val row = LuaTable()
            val icon = ItemIcons.iconFor(stack)
            if (stack != null && stack.stackSize > 0) {
                sig.append(itemId(stack)).append(':').append(stack.metadata)
                    .append(':').append(stack.stackSize).append(';')
                row.set("count", LuaValue.valueOf(stack.stackSize))
                row.set("name", LuaValue.valueOf(stack.displayName))
                row.set("id", LuaValue.valueOf(itemId(stack)))
                if (icon != null) {
                    row.set("atlasIndex", LuaValue.valueOf(icon.atlasIndex))
                    row.set("u0", LuaValue.valueOf(icon.u0.toDouble()))
                    row.set("v0", LuaValue.valueOf(icon.v0.toDouble()))
                    row.set("u1", LuaValue.valueOf(icon.u1.toDouble()))
                    row.set("v1", LuaValue.valueOf(icon.v1.toDouble()))
                }
            } else {
                sig.append("empty;")
                row.set("count", LuaValue.valueOf(0))
            }
            slots.set(i + 1, row)
        }
        sig.append("sel=").append(inv.currentItem)
        t.set("slots", slots)
        t.set("invSig", LuaValue.valueOf(sig.toString()))

        // held item (hotbar slot under the selector), flattened for Lua
        val held = inv.getCurrentItem()
        val heldIcon = ItemIcons.iconFor(held)
        t.set("heldCount", LuaValue.valueOf(if (held != null) held.stackSize else 0))
        t.set("heldName", LuaValue.valueOf(if (held != null) held.displayName else ""))
        if (held != null && heldIcon != null) {
            t.set("heldAtlas", LuaValue.valueOf(heldIcon.atlasIndex))
            t.set("heldU0", LuaValue.valueOf(heldIcon.u0.toDouble()))
            t.set("heldV0", LuaValue.valueOf(heldIcon.v0.toDouble()))
            t.set("heldU1", LuaValue.valueOf(heldIcon.u1.toDouble()))
            t.set("heldV1", LuaValue.valueOf(heldIcon.v1.toDouble()))
        }
        return t
    }

    // ------------------------------------------------------------------
    // Icon spec wiring
    // ------------------------------------------------------------------

    /**
     * Post-pass over the just-converted tree: "icon" nodes draw no surface
     * quad, and when the Lua spec carries explicit u0..v1 numbers the sprite
     * rect is registered directly. Specs for nodes no longer present in the
     * new tree are pruned so rebuild-per-frame callers don't leak entries.
     */
    private fun seedIconSpecs(tree: LuaValue) {
        val keep = HashSet<UiNode>()
        walkLua(tree) { t ->
            val node = t.get("__node").optuserdata(UiNode::class.java, null) as? UiNode
            if (node != null) {
                keep.add(node)
                if (t.get("type").optjstring("box") == "icon") {
                    node.drawsSurface = false
                    node.shadow = false
                    node.radius = 0f
                    val u0 = t.get("u0"); val v0 = t.get("v0")
                    val u1 = t.get("u1"); val v1 = t.get("v1")
                    if (u0.isnumber() && v0.isnumber() && u1.isnumber() && v1.isnumber()) {
                        ItemIcons.registerSpec(
                            node, ItemIcons.IconSpec(
                                t.get("atlasIndex").optint(0),
                                u0.tofloat(), v0.tofloat(), u1.tofloat(), v1.tofloat()
                            )
                        )
                    }
                }
            }
        }
        ItemIcons.pruneSpecs(keep)
    }

    private fun walkLua(t: LuaValue, visit: (LuaValue) -> Unit) {
        visit(t)
        val children = t.get("children")
        if (children.istable()) {
            for (i in 1..children.length()) walkLua(children.get(i), visit)
        }
    }

    private fun itemId(stack: ItemStack): String = try {
        Item.itemRegistry.getNameForObject(stack.item)?.toString() ?: "unknown"
    } catch (t: Throwable) {
        "unknown"
    }

    // ------------------------------------------------------------------
    // hud.lua bootstrap (written to disk if missing, then executed)
    // ------------------------------------------------------------------

    private fun loadHudScript(globals: Globals) {
        try {
            val path = Paths.get("lua", "hud.lua")
            if (!Files.exists(path)) {
                Files.createDirectories(path.parent)
                Files.write(path, HUD_LUA.toByteArray(Charsets.UTF_8))
            }
            val source = String(Files.readAllBytes(path), Charsets.UTF_8)
            globals.load(source, "hud").call()
            System.out.println("[NeoUI lua] hud.lua loaded")
        } catch (t: Throwable) {
            System.err.println("[NeoUI lua] hud.lua failed to load: $t")
        }
    }

    /** Embedded fallback copy of lua/hud.lua (kept in sync with the file). */
    private val HUD_LUA = """
        -- In-world HUD for NeoUI: hotbar, health/hunger pips, XP bar, crosshair and
        -- held item - all authored as a NeoUI tree and driven from Kotlin data.
        --
        -- Loaded automatically by the `hudapi` Kotlin plugin (ApiHud) when the Lua
        -- runtime boots; `renderInPass` only draws HudRenderer screens in-world, so
        -- the menu path is untouched. Style: HeroUI light tokens - #006FEE accent,
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
    """.trimIndent() + "\n"
}
