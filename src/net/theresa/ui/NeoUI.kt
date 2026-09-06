package net.theresa.ui

import libsrc.lwjglx.input.Keyboard
import libsrc.lwjglx.input.Mouse
import net.minecraft.client.Minecraft
import net.theresa.render.vulkan.VulkanContext
import net.theresa.ui.font.FontEngine
import net.theresa.ui.lua.LuaUiRuntime
import net.theresa.ui.render.UiRenderer
import net.theresa.ui.screen.InputDispatcher
import net.theresa.ui.screen.MainMenuScreen
import net.theresa.ui.screen.NeoScreens
import net.theresa.ui.screen.NeoScreen
import net.theresa.ui.screen.ScreenManager
import net.theresa.ui.style.Theme

/**
 * NeoUI facade: the single integration point between the game loop / Vulkan
 * renderer and the UI framework.
 *
 * Frame contract (Vulkan mode):
 *  - [tick]       early in Minecraft.runGameLoop: drains input in menu context,
 *                 advances screens/animations (real input in M4).
 *  - [prepare]    before the main render pass: records the offscreen panorama
 *                 + blur passes into the same command buffer.
 *  - [renderInPass] inside the main render pass: records all UI draws
 *                 (menu background + screens; HUD overlay once HUD screens exist).
 */
object NeoUI {

    private var renderer: UiRenderer? = null

    /** Active theme (theme.json overrides, hot-reloadable later). */
    lateinit var theme: Theme
        private set

    /** Glyph engine used by the scene for text measurement. */
    var font: FontEngine? = null
        private set

    val isReady: Boolean get() = renderer != null

    fun init(ctx: VulkanContext, window: Long, imageFormat: Int, width: Int, height: Int) {
        destroy()
        val t0 = System.nanoTime()
        theme = Theme.load()
        try {
            renderer = UiRenderer(ctx, window, imageFormat, width, height)
            font = renderer!!.font
        } catch (t: Throwable) {
            System.err.println("[NeoUI] init failed, GUI disabled this session: $t")
            t.printStackTrace()
            renderer = null
        }
        if (menuContext()) {
            try {
                val runtime = LuaUiRuntime()
                runtime.start()
                luaRuntime = runtime
            } catch (t: Throwable) {
                System.err.println("[NeoUI] Lua UI failed, falling back to code menus: $t")
                t.printStackTrace()
                luaRuntime = null
                registerBuiltins()
                ScreenManager.show(NeoScreens.create("main_menu"))
            }
        }
        System.out.printf("[NeoUI] init complete in %.0fms%n", (System.nanoTime() - t0) / 1e6)
    }

    private fun registerBuiltins() {
        NeoScreens.register("main_menu") { MainMenuScreen.build() }
    }

    /** Swapchain extent changed: rebuild quarter-res backdrop targets. */
    fun onResized(width: Int, height: Int) {
        renderer?.onResized(width, height)
    }

    private var lastTickNano = 0L

    /**
     * Per-frame logic on the client thread. In menu context (no world) the
     * vanilla gameplay input loops have nothing meaningful to consume, so NeoUI
     * owns the event queues here; in-world they are left to vanilla.
     */
    fun tick() {
        val now = System.nanoTime()
        val dtMs = if (lastTickNano == 0L) 16f
        else ((now - lastTickNano) / 1e6).toFloat().coerceIn(0.05f, 100f)
        lastTickNano = now

        val screen = ScreenManager.current
        if (menuContext()) {
            if (screen != null && renderer != null) {
                InputDispatcher.tick(screen)
            } else {
                while (Mouse.next()) {
                }
                while (Keyboard.next()) {
                }
            }
        }
        screen?.tickAnimations(now, theme.entranceMs)
        // hover/press state tweens: frame-rate independent exponential smoothing
        // (~45ms time constant) so animations look identical at any fps
        val k = 1f - Math.exp(-dtMs / 45.0).toFloat()
        screen?.root?.walk { n ->
            val target = if (n.hover) 1f else 0f
            n.hoverT += (target - n.hoverT) * k
            if (kotlin.math.abs(n.hoverT - target) < 0.005f) n.hoverT = target
            val pTarget = if (n.pressed) 1f else 0f
            n.pressedT += (pTarget - n.pressedT) * k
            if (kotlin.math.abs(n.pressedT - pTarget) < 0.005f) n.pressedT = pTarget
        }
        luaRuntime?.tickFrame(dtMs / 1000f)
    }

    /** Offscreen backdrop passes; must be recorded before the main render pass. */
    fun prepare(cmd: org.lwjgl.vulkan.VkCommandBuffer) {
        renderer?.prepare(cmd)
    }

    /** Per-frame fps probe (prints menu render fps every 240 frames). */
    fun probeFrame() {
        renderer?.probeFrame()
    }

    /** F10: restart the Lua runtime and reload every screen from lua/. */
    fun reloadLua() {
        try {
            val width = Minecraft.getMinecraft().displayWidth
            val height = Minecraft.getMinecraft().displayHeight
            luaRuntime?.destroy()
            luaRuntime = null
            LuaUiRuntime().let {
                it.start()
                luaRuntime = it
            }
            System.out.println("[NeoUI] Lua scripts reloaded ($width x $height)")
        } catch (t: Throwable) {
            System.err.println("[NeoUI] Lua reload failed: $t")
            t.printStackTrace()
        }
    }

    private var luaRuntime: LuaUiRuntime? = null

    /**
     * UI draws inside the main render pass, after terrain (or after the clear
     * in menu mode). Menu screens only render with no world loaded.
     */
    fun renderInPass(cmd: org.lwjgl.vulkan.VkCommandBuffer, width: Int, height: Int) {
        val r = renderer ?: return
        r.renderInPass(cmd, width, height, menuContext())
    }

    /** F9 companion: rebuild UI pipelines from the current shaders_vk/ui_* sources. */
    fun reloadPipelines() {
        try {
            renderer?.reloadPipelines()
        } catch (t: Throwable) {
            System.err.println("[NeoUI] pipeline reload failed: $t")
        }
    }

    fun destroy() {
        luaRuntime?.destroy()
        luaRuntime = null
        renderer?.destroy()
        renderer = null
    }

    /** onClick action protocol from layouts/widgets (see ScreenManager). */
    fun handleAction(action: String) {
        if (action.startsWith("open:")) {
            val id = action.removePrefix("open:")
            if (luaRuntime?.openScreen(id) == true) return
        }
        ScreenManager.handleAction(action)
    }

    /** Custom actions for mods: routed on the UI event bus (M4+). */
    fun emitCustom(event: String) {
        System.out.println("[NeoUI] custom action: $event")
    }

    private fun menuContext(): Boolean {
        val mc = Minecraft.getMinecraft() ?: return true
        return mc.theWorld == null
    }
}
