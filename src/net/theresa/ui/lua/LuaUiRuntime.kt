package net.theresa.ui.lua

import net.theresa.ui.NeoUI
import net.theresa.ui.scene.UiNode
import net.theresa.ui.screen.NeoScreen
import libsrc.lwjglx.input.Mouse
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
    var keyListener: LuaValue? = null
    private var startTime = System.nanoTime()

    fun dispatchKey(key: Int, ch: Char, down: Boolean) {
        keyListener?.let {
            try {
                it.call(LuaValue.valueOf(key), LuaValue.valueOf(ch.toString()), LuaValue.valueOf(down))
            } catch (t: Throwable) {
                System.err.println("[NeoUI lua] key_listener failed: $t")
            }
        }
    }
    private var dbgCount = 0

    fun start() {
        writeEmbeddedScripts()
        globals.set("neoui", coerceApi())
        globals.set("neoui", globals.get("neoui"))
        LuaApiRegistry.installAll(globals, this)
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
        // cursor state for Lua-side drag/slider components
        api.set("mouse", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val mc = net.minecraft.client.Minecraft.getMinecraft()
                val mx = libsrc.lwjglx.input.Mouse.getX().toFloat()
                val my = (mc.displayHeight - libsrc.lwjglx.input.Mouse.getY()).toFloat()
                val t = LuaTable()
                t.set("x", LuaValue.valueOf(mx.toDouble()))
                t.set("y", LuaValue.valueOf(my.toDouble()))
                t.set("left", LuaValue.valueOf(libsrc.lwjglx.input.Mouse.isButtonDown(0)))
                t.set("right", LuaValue.valueOf(libsrc.lwjglx.input.Mouse.isButtonDown(1)))
                t.set("wheel", LuaValue.valueOf(libsrc.lwjglx.input.Mouse.getDWheel().toDouble()))
                return t
            }
        })
        // raw keyboard events for text fields (consumed BEFORE InputDispatcher's
        // ESC handling? no — dispatched after; ESC still pops screens)
        api.set("key_listener", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                keyListener = args.arg1().checkfunction()
                return LuaValue.NIL
            }
        })
        // text fields suppress ESC-pops-while-editing
        api.set("set_pop_suppressed", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                net.theresa.ui.screen.InputDispatcher.popSuppressed = args.arg1().toboolean()
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

    fun nodeFromLua(t: LuaValue): UiNode {
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
        if (node.type == "button") node.textSize = NeoUI.theme.fontSize
        node.textSize = t.get("textSize").optdouble(node.textSize.toDouble()).toFloat()
        t.get("textColor")?.let { node.textColor = colorOf(it) }
        t.get("fillColor")?.let { node.fillColor = colorOf(it) }
        t.get("fillEndColor")?.let { node.fillEndColor = colorOf(it) }
        t.get("borderColor")?.let { node.borderColor = colorOf(it) }
        if (t.get("hoverT") != null) node.hoverT = t.get("hoverT").optdouble(node.hoverT.toDouble()).toFloat()
        node.bold = t.get("bold").optboolean(node.bold)
        node.letterSpacing = t.get("letterSpacing").optdouble(node.letterSpacing.toDouble()).toFloat()
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
        writeIfMissing(Paths.get("lua", "heroui.lua"), EMBED_HEROUI)
        writeIfMissing(Paths.get("lua", "main_menu.lua"), EMBED_MAIN_MENU)
    }

    private fun writeIfMissing(path: Path, content: String) {
        if (!Files.exists(path)) {
            Files.write(path, content.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private val EMBED_INIT = """
            package.path = package.path .. ";./lua/?.lua;./lua/screens/?.lua;"
            local main_menu = require("main_menu")
            main_menu.register()
            -- screens self-register on require (open:<id> routes to them)
            require("screens.settings")
            require("screens.singleplayer")
            require("screens.multiplayer")
        """.trimIndent() + "\n"

        private val EMBED_HEROUI = """
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
        """.trimIndent() + "\n"

        private val EMBED_MAIN_MENU = """
            local heroui = require("heroui")

            local M = {}

            function M.register()
                local column = heroui.column { children = {} }
                local function add(node)
                    table.insert(column.children, node)
                    return node
                end

                add(heroui.label { text = "NEOGENESIS", textSize = 50, bold = true, letterSpacing = 2 })
                add(heroui.label { text = "Vulkan Native UI", textSize = 18,
                                   textColor = heroui.colors.foregroundMuted })
                local bar = add(heroui.accent_bar())
                add(heroui.spacer(16))
                add(heroui.button { text = neoui.i18n("menu.singleplayer"), variant = "solid",
                                    onClick = function() neoui.open("singleplayer") end })
                add(heroui.button { text = neoui.i18n("menu.multiplayer"), variant = "bordered",
                                    onClick = function() neoui.open("multiplayer") end })
                local row = heroui.row { w = 400, children = {
                    heroui.button { text = neoui.i18n("menu.options"), variant = "bordered", w = 196,
                                    onClick = function() neoui.open("options") end },
                    heroui.button { text = neoui.i18n("options.language"), variant = "bordered", w = 196,
                                    onClick = function() neoui.open("language") end } } }
                add(row)
                add(heroui.button { text = neoui.i18n("menu.quit"), variant = "light",
                                    textColor = heroui.colors.danger,
                                    onClick = function() neoui.handle("quit") end })

                local tree = { type = "box", children = {} }
                table.insert(tree.children, heroui.card { w = 640, padding = 36,
                    anchor = {0.5, 0.5}, pivot = {0.5, 0.5}, children = { column } })
                -- single merged footer, bottom-center; no Mojang attribution
                table.insert(tree.children, heroui.label {
                    text = "Neogenesis 1.8.9 Vulkan",
                    textSize = 14, textColor = "#FF55555C",
                    anchor = {0.5, 1}, pivot = {0.5, 1}, offset = {0, -12} })

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
