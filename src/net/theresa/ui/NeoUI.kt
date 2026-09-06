package net.theresa.ui

import libsrc.lwjglx.input.Keyboard
import libsrc.lwjglx.input.Mouse
import net.minecraft.client.Minecraft
import net.theresa.render.vulkan.VulkanContext
import net.theresa.ui.render.UiRenderer

/**
 * NeoUI facade: the single integration point between the game loop / Vulkan
 * renderer and the UI framework.
 *
 * Frame contract (Vulkan mode):
 *  - [tick]       early in Minecraft.runGameLoop: drains input in menu context,
 *                 advances screens/animations (input & screens from M3/M4 on).
 *  - [prepare]    before the main render pass: records the offscreen panorama
 *                 + blur passes into the same command buffer.
 *  - [renderInPass] inside the main render pass: records all UI draws
 *                 (menu background; HUD overlay once HUD screens exist).
 */
object NeoUI {

    private var renderer: UiRenderer? = null

    val isReady: Boolean get() = renderer != null

    fun init(ctx: VulkanContext, window: Long, imageFormat: Int, width: Int, height: Int) {
        destroy()
        try {
            renderer = UiRenderer(ctx, window, imageFormat, width, height)
        } catch (t: Throwable) {
            System.err.println("[NeoUI] init failed, GUI disabled this session: $t")
            t.printStackTrace()
            renderer = null
        }
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
        // screen stack + animation ticking arrive with the scene layer
    }

    /** Offscreen backdrop passes; must be recorded before the main render pass. */
    fun prepare(cmd: org.lwjgl.vulkan.VkCommandBuffer) {
        renderer?.prepare(cmd)
    }

    /**
     * UI draws inside the main render pass, after terrain (or after the clear
     * in menu mode). Menu background only renders with no world loaded.
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

    private fun menuContext(): Boolean {
        val mc = Minecraft.getMinecraft() ?: return true
        return mc.theWorld == null
    }
}
