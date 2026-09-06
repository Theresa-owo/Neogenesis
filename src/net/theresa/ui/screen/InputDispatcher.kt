package net.theresa.ui.screen

import libsrc.lwjglx.input.Keyboard
import libsrc.lwjglx.input.Mouse
import net.minecraft.client.Minecraft
import net.theresa.ui.scene.UiNode

/**
 * Menu-context input: converts the lwjglx shim event queues into hover/press/
 * click on the retained node tree. Runs only when no world is loaded (the
 * vanilla gameplay loops own input in-world).
 *
 * Coordinates: Mouse reports from the BOTTOM-left (LWJGL2 convention); the
 * scene uses top-left like the framebuffer, so y is flipped here once.
 */
object InputDispatcher {

    private var pressedNode: UiNode? = null
    private var hoveredNode: UiNode? = null

    /** Lua text fields set this to swallow ESC/keys while editing. */
    @Volatile
    var popSuppressed: Boolean = false

    fun tick(screen: NeoScreen) {
        val mc = Minecraft.getMinecraft()
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return

        // absolute cursor position drives hover every frame
        val mx = Mouse.getX().toFloat()
        val my = (h - Mouse.getY()).toFloat()
        updateHover(screen.root, mx, my)

        while (Mouse.next()) {
            val button = Mouse.getEventButton()
            if (button < 0) continue // move/wheel events; hover already handled
            val ex = Mouse.getEventX().toFloat()
            val ey = (h - Mouse.getEventY()).toFloat()
            val state = Mouse.getEventButtonState()
            val target = hitTest(screen.root, ex, ey)
            if (System.getProperty("neogenesis.uiHitDebug") != null && state) {
                System.out.printf(
                    "[UiHit] press (%.0f,%.0f) -> %s '%s' bounds=(%.0f,%.0f %.0fx%.0f)%n",
                    ex, ey, target?.type, target?.text?.take(16),
                    target?.x ?: -1f, target?.y ?: -1f, target?.width ?: -1f, target?.height ?: -1f
                )
            }
            if (state) {
                pressedNode = target
                target?.pressed = true
            } else {
                // click = press + release over the same interactive node
                if (target != null && target === pressedNode) {
                    target.onClick?.invoke()
                }
                pressedNode?.pressed = false
                pressedNode = null
            }
        }

        while (Keyboard.next()) {
            val key = Keyboard.getEventKey()
            val ch = Keyboard.getEventCharacter()
            val down = Keyboard.getEventKeyState()
            // Lua text fields observe every key event (may consume). Snapshot
            // the suppression flag BEFORE dispatching: a focused field that
            // blurs itself on ESC (clearing the flag inside the listener)
            // must not make that same ESC pop the screen.
            val escSuppressed = popSuppressed
            try {
                net.theresa.ui.NeoUI.dispatchLuaKey(key, ch, down)
            } catch (_: Throwable) {
            }
            if (down && key == 1 && !escSuppressed) { // ESC
                ScreenManager.pop()
            }
        }
    }

    /** Deepest interactive node under (x, y); null when none. */
    private fun hitTest(root: UiNode, x: Float, y: Float): UiNode? {
        var best: UiNode? = null
        root.walk { n ->
            if (!n.visible || n.onClick == null) return@walk
            if (x >= n.x && x < n.x + n.width && y >= n.y && y < n.y + n.height) {
                best = n
            }
        }
        return best
    }

    private fun updateHover(root: UiNode, x: Float, y: Float) {
        val target = hitTest(root, x, y)
        if (target !== hoveredNode) {
            hoveredNode?.hover = false
            hoveredNode = target
            target?.hover = true
        }
    }

    /** Clears hover/press state when the screen stack changes. */
    fun reset() {
        hoveredNode?.hover = false
        hoveredNode = null
        pressedNode?.pressed = false
        pressedNode = null
        // A stack change invalidates Lua-side focus state: text fields blur
        // via click-away/ESC before any stack change they cause, and a Lua
        // reload (F10) with a field focused would otherwise leave ESC
        // popping permanently suppressed.
        popSuppressed = false
    }
}
