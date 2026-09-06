package net.theresa.ui.lua

import net.theresa.ui.NeoUI
import net.theresa.ui.scene.UiNode
import net.theresa.ui.screen.NeoScreen
import net.theresa.ui.screen.ScreenManager
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The NeoUI Lua scripting layer. All screens are authored in Lua (loaded from
 * `lua/` at the working directory, written there from embedded sources on
 * first run — the same flow as shaders_vk), while the Kotlin engine keeps
 * layout, rendering and input.
 *
 * Exposed `neoui` table:
 *   neoui.log(msg)                  — stdout
 *   neoui.time()                    — seconds since start
 *   neoui.i18n(key)                 — vanilla I18n lookup
 *   neoui.show_screen{id, tree}     — replace the screen stack
 *   neoui.push_screen{id, tree}     — push on top
 *   neoui.open(id)                  — open a registered screen (placeholder if unknown)
 *   neoui.pop()                     — pop the stack
 *   neoui.every_frame(fn)           — fn(dtSeconds) each frame (animations)
 *
 * Node tables (nested): type/w/h/anchor/pivot/offset/spacing/padding/text/
 * textSize/textColor/fillColor/fillEndColor/borderColor/radius/shadow/
 * drawsSurface/onClick(function)/children — converted 1:1 to UiNode.
 */
class LuaUiRuntime {

    private val globals: Globals = JsePlatform.standardGlobals()
    private val frameHooks = ArrayList<LuaValue>()
    private val registeredScreens = HashMap<String, LuaTable>()
    private var startTime = System.nanoTime()
    private var dbgCount = 0

    fun start() {
        writeEmbeddedScripts()
        globals.set("neoui", coerceApi())
        val chunk = globals.load(Files.readString(Paths.get("lua", "init.lua")), "init")
        chunk.call()
    }

    fun tickFrame(dtSeconds: Float) {
        for (fn in frameHooks) {
            try {
                fn.call(LuaValue.valueOf(dtSeconds.toDouble()))
            } catch (t: Throwable) {
                System.err.println("[NeoUI lua] every_frame hook failed: $t")
                frameHooks.clear()
                break
            }
        }
    }

    fun destroy() {
        frameHooks.clear()
    }

    // ------------------------------------------------------------------
    // neoui bindings
    // ------------------------------------------------------------------

    private fun coerceApi(): LuaValue {
        val api = LuaValue.tableOf()
        api.set("log", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                println("[NeoUI lua] " + args.arg1().tojstring())
                return LuaValue.NIL
            }
        })
        api.set("time", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue =
                LuaValue.valueOf((System.nanoTime() - startTime) / 1e9)
        })
        api.set("i18n", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue =
                LuaValue.valueOf(net.minecraft.client.resources.I18n.format(args.arg1().tojstring()))
        })
        api.set("every_frame", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                frameHooks.add(args.arg1().checkfunction())
                return LuaValue.NIL
            }
        })
        api.set("show_screen", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val spec = args.arg1().checktable()
                val id = spec.get("id").tojstring()
                val root = nodeFromLua(spec.get("tree").checktable())
                ScreenManager.show(NeoScreen(id, root))
                return LuaValue.NIL
            }
        })
        api.set("push_screen", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val spec = args.arg1().checktable()
                val id = spec.get("id").tojstring()
                val root = nodeFromLua(spec.get("tree").checktable())
                ScreenManager.push(NeoScreen(id, root))
                return LuaValue.NIL
            }
        })
        api.set("open", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                NeoUI.handleAction("open:" + args.arg1().tojstring())
                return LuaValue.NIL
            }
        })
        api.set("pop", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                ScreenManager.pop()
                return LuaValue.NIL
            }
        })
        api.set("handle", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                NeoUI.handleAction(args.arg1().tojstring())
                return LuaValue.NIL
            }
        })
        api.set("register_screen", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val spec = args.arg1().checktable()
                registeredScreens[spec.get("id").tojstring()] = spec
                return LuaValue.NIL
            }
        })
        return api
    }

    /** open:<id> actions resolve Lua-registered screens first; true when handled. */
    fun openScreen(id: String): Boolean {
        val spec = registeredScreens[id] ?: return false
        val root = nodeFromLua(spec.get("tree").checktable())
        ScreenManager.push(NeoScreen(id, root))
        return true
    }

    // ------------------------------------------------------------------
    // LuaTable -> UiNode
    // ------------------------------------------------------------------

    private fun nodeFromLua(t: LuaValue): UiNode {
        val typeName = t.get("type").optjstring("box")
        val node = when (typeName) {
            "label" -> net.theresa.ui.scene.Widgets.label("")
            "button" -> net.theresa.ui.scene.Widgets.button("")
            "column" -> net.theresa.ui.scene.Widgets.column()
            "row" -> net.theresa.ui.scene.Widgets.row()
            "spacer" -> net.theresa.ui.scene.Widgets.spacer(0f)
            "panel" -> net.theresa.ui.scene.Widgets.panel("panel", UiNode.STYLE_GLASS)
            else -> net.theresa.ui.scene.Widgets.panel("box")
        }
        node.type = typeName
        applyNodeProperties(node, t)
        val children = t.get("children")
        if (children.istable()) {
            for (i in 1..children.length()) {
                node.add(nodeFromLua(children.get(i)))
            }
        }
        // hand the node back to Lua so every_frame hooks can animate it
        t.set("__node", org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(node))
        return node
    }

    private fun applyNodeProperties(node: UiNode, t: LuaValue) {
        t.get("style").optjstring("solid").let { s ->
            node.style = when (s) {
                "glass" -> UiNode.STYLE_GLASS
                "primary" -> UiNode.STYLE_PRIMARY
                "ghost" -> UiNode.STYLE_GHOST
                else -> UiNode.STYLE_SOLID
            }
            net.theresa.ui.scene.Widgets.applyThemeStyle(node)
        }
        applySize(t.get("w"), node, true)
        applySize(t.get("h"), node, false)
        (t.get("anchor") as? LuaTable)?.let {
            node.anchorX = it.get(1).optdouble(0.5).toFloat(); node.anchorY = it.get(2).optdouble(0.5).toFloat()
        }
        (t.get("pivot") as? LuaTable)?.let {
            node.pivotX = it.get(1).optdouble(0.5).toFloat(); node.pivotY = it.get(2).optdouble(0.5).toFloat()
        }
        (t.get("offset") as? LuaTable)?.let {
            node.offsetX = it.get(1).optdouble(0.0).toFloat(); node.offsetY = it.get(2).optdouble(0.0).toFloat()
        }
        node.spacing = t.get("spacing").optdouble(node.spacing.toDouble()).toFloat()
        node.padding = t.get("padding").optdouble(node.padding.toDouble()).toFloat()
        node.gravity = when (t.get("gravity").optjstring("center")) {
            "start" -> UiNode.GRAVITY_START
            "end" -> UiNode.GRAVITY_END
            else -> UiNode.GRAVITY_CENTER
        }
        node.radius = t.get("radius").optdouble(node.radius.toDouble()).toFloat()
        node.shadow = t.get("shadow").optboolean(node.shadow)
        node.visible = t.get("visible").optboolean(node.visible)
        node.text = t.get("text").optjstring(node.text)
        node.textSize = t.get("textSize").optdouble(node.textSize.toDouble()).toFloat()
        t.get("textColor")?.let { node.textColor = colorOf(it) }
        t.get("fillColor")?.let { node.fillColor = colorOf(it) }
        t.get("fillEndColor")?.let { node.fillEndColor = colorOf(it) }
        t.get("borderColor")?.let { node.borderColor = colorOf(it) }
        if (t.get("hoverT") != null) node.hoverT = t.get("hoverT").optdouble(node.hoverT.toDouble()).toFloat()
        val onClick = t.get("onClick")
        if (onClick.isfunction()) {
            val fn = onClick.checkfunction()
            node.onClick = {
                try {
                    fn.call()
                } catch (t2: Throwable) {
                    System.err.println("[NeoUI lua] onClick failed: $t2")
                }
            }
        }
    }

    private fun applySize(e: LuaValue, node: UiNode, horizontal: Boolean) {
        if (e == null || e.isnil()) return
        when {
            // LuaJ isstring() is true for numbers too — test numbers FIRST,
            // otherwise numeric w/h take the string branch and only set the
            // size mode, leaving dpWidth/dpHeight at the factory default (0)
            e.isnumber() -> {
                val v = e.tofloat()
                if (horizontal) { node.dpWidth = v; node.widthMode = UiNode.SIZE_FIXED }
                else { node.dpHeight = v; node.heightMode = UiNode.SIZE_FIXED }
            }
            e.isstring() -> {
                val mode = when (e.tojstring()) {
                    "match" -> UiNode.SIZE_MATCH
                    "wrap" -> UiNode.SIZE_WRAP
                    else -> UiNode.SIZE_FIXED
                }
                if (horizontal) node.widthMode = mode else node.heightMode = mode
            }
        }
    }

    private fun colorOf(v: LuaValue): Int {
        if (v.isnumber()) return v.toint()
        val s = v.tojstring().removePrefix("#")
        val argb = when (s.length) {
            6 -> 0xFF000000L or s.toLong(16)
            8 -> s.toLong(16)
            else -> 0xFFFFFFFFL
        }
        return argb.toInt()
    }

    // ------------------------------------------------------------------
    // Script bootstrap
    // ------------------------------------------------------------------

    private fun writeEmbeddedScripts() {
        val dir: Path = Paths.get("lua", "heroui")
        Files.createDirectories(dir)
        writeIfMissing(Paths.get("lua", "init.lua"), EMBED_INIT)
        writeIfMissing(Paths.get("lua", "heroui", "theme.lua"), EMBED_THEME)
        writeIfMissing(Paths.get("lua", "main_menu.lua"), EMBED_MAIN_MENU)
    }

    private fun writeIfMissing(path: Path, content: String) {
        if (!Files.exists(path)) {
            Files.write(path, content.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private val EMBED_INIT = """
            package.path = package.path .. ";./lua/?.lua;"
            local main_menu = require("main_menu")
            main_menu.register()
        """.trimIndent() + "\n"

        private val EMBED_THEME = """
            -- HeroUI-style dark theme tokens (https://heroui.com)
            local M = {}
            M.colors = {
                background = "#000000",
                surface = "#18181B",      -- content1
                surface2 = "#27272A",     -- content2
                surface3 = "#3F3F46",     -- content3
                foreground = "#1F2126",
                foregroundMuted = "#6E7078",
                primary = "#006FEE",
                primaryHover = "#005CC4",
                primaryForeground = "#FFFFFF",
                secondary = "#9353F3",
                danger = "#F31260",
                divider = "#26FFFFFF",
                glass = "#B3141218",
                glassBorder = "#24FFFFFF",
                shadow = "#52000000",
            }
            M.radius = { small = 12, medium = 14, large = 16 }
            M.button = { height = 44, width = 400 }
            return M
        """.trimIndent() + "\n"

        private val EMBED_MAIN_MENU = """
            local heroui = require("heroui.theme")

            local M = {}

            -- HeroUI button: variants solid | bordered | flat | light
            local function button(spec)
                local v = spec.variant or "solid"
                local c = heroui.colors
                spec.type = "button"
                spec.h = spec.h or heroui.button.height
                spec.radius = heroui.radius.medium
                spec.shadow = false
                if v == "solid" then
                    spec.fillColor = c.primary; spec.fillEndColor = c.primary
                    spec.textColor = c.primaryForeground
                elseif v == "bordered" then
                    spec.fillColor = "#00000000"; spec.fillEndColor = "#00000000"
                    spec.textColor = c.primary; spec.borderColor = c.primary
                elseif v == "flat" then
                    spec.fillColor = "#26006FEE"; spec.fillEndColor = "#26006FEE"
                    spec.textColor = c.primary
                else -- light
                    spec.fillColor = "#00000000"; spec.fillEndColor = "#00000000"
                    spec.textColor = spec.textColor or c.primary
                end
                return spec
            end

            function M.register()
                local c = heroui.colors

                -- built programmatically: no brace soup, easy to extend
                local column = { type = "column", spacing = 12, w = "match", children = {} }
                local function add(node)
                    table.insert(column.children, node)
                    return node
                end

                add { type = "label", text = "NEOGENESIS", textSize = 44, textColor = c.foreground, w = "match" }
                add { type = "label", text = "Vulkan Native UI", textSize = 16,
                      textColor = c.foregroundMuted, w = "match" }
                local bar = add { type = "panel", w = 64, h = 4, radius = 2,
                                  fillColor = c.primary, fillEndColor = c.primary,
                                  borderColor = "#00000000", shadow = false }
                add { type = "spacer", h = 12 }
                add(button { text = neoui.i18n("menu.singleplayer"), variant = "solid",
                             onClick = function() neoui.open("singleplayer") end })
                add(button { text = neoui.i18n("menu.multiplayer"), variant = "bordered",
                             onClick = function() neoui.open("multiplayer") end })
                local row = { type = "row", spacing = 8, w = 400, children = {} }
                table.insert(row.children, button {
                    text = neoui.i18n("menu.options"), variant = "flat", w = 196,
                    onClick = function() neoui.open("options") end })
                table.insert(row.children, button {
                    text = neoui.i18n("options.language"), variant = "flat", w = 196,
                    onClick = function() neoui.open("language") end })
                add(row)
                add(button { text = neoui.i18n("menu.quit"), variant = "light",
                             textColor = c.danger,
                             onClick = function() neoui.handle("quit") end })

                local tree = { type = "box", children = {} }
                table.insert(tree.children, {
                    type = "panel", style = "glass", w = 480,
                    anchor = {0.5, 0.5}, pivot = {0.5, 0.5}, padding = 28,
                    children = { column } })
                table.insert(tree.children, { type = "label", text = "Neogenesis 1.8.9 Vulkan",
                    textSize = 13, textColor = "#FF55555C",
                    anchor = {0, 1}, pivot = {0, 1}, offset = {16, -14} })
                table.insert(tree.children, { type = "label",
                    text = "Copyright Mojang AB. Do not distribute!",
                    textSize = 13, textColor = "#FF55555C",
                    anchor = {1, 1}, pivot = {1, 1}, offset = {-16, -14} })

                neoui.show_screen { id = "main_menu", tree = tree }

                -- Lua-driven animation: pulse the accent bar width through the
                -- coerced node handle (__node) from an every_frame hook
                local barNode = bar.__node
                local t0 = neoui.time()
                neoui.every_frame(function(dt)
                    barNode:setDpWidth(64 + math.sin((neoui.time() - t0) * 2.0) * 14)
                end)
            end

            return M
        """.trimIndent() + "\n"
    }
}
