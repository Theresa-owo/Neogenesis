package net.theresa.ui

import libsrc.lwjglx.input.Keyboard
import libsrc.lwjglx.input.Mouse
import net.minecraft.client.Minecraft
import net.theresa.render.vulkan.VulkanContext
import net.theresa.ui.font.FontEngine
import net.theresa.ui.render.UiRenderer
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
        theme = Theme.load()
        try {
            renderer = UiRenderer(ctx, window, imageFormat, width, height)
            font = renderer!!.font
        } catch (t: Throwable) {
            System.err.println("[NeoUI] init failed, GUI disabled this session: $t")
            t.printStackTrace()
            renderer = null
        }
        registerBuiltins()
        // Demo entry: the main menu (replaced by world/HUD routing in M4).
        if (menuContext()) {
            ScreenManager.show(NeoScreens.create("main_menu"))
        }
    }

    private fun registerBuiltins() {
        NeoScreens.register("main_menu") { MainMenuScreen.build() }
    }

    /** Swapchain extent changed: rebuild quarter-res backdrop targets. */
    fun onResized(width: Int, height: Int) {
        renderer?.onResized(width, height)
    }

    /**
     * Per-frame logic on the client thread. In menu context (no world) the
     * vanilla gameplay input loops have nothing meaningful to consume, so NeoUI
     * owns the event queues here; in-world they are left to vanilla.
     */
    fun tick() {
        if (menuContext()) {
            while (Mouse.next()) {
                // consumed by the input dispatcher (M4)
            }
            while (Keyboard.next()) {
                // consumed by the input dispatcher (M4)
            }
        }
        // animation ticking arrives with M4
    }

    /** Offscreen backdrop passes; must be recorded before the main render pass. */
    fun prepare(cmd: org.lwjgl.vulkan.VkCommandBuffer) {
        renderer?.prepare(cmd)
    }

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
        renderer?.destroy()
        renderer = null
    }

    /** onClick action protocol from layouts/widgets (see ScreenManager). */
    fun handleAction(action: String) {
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
