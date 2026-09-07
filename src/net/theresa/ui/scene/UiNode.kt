package net.theresa.ui.scene

import net.theresa.ui.NeoUI
import net.theresa.ui.style.Theme

/**
 * Retained-mode UI node. Bounds are pixels (computed by [layout]); layout
 * parameters are dp (1080dp design height baseline, scaled by the frame).
 *
 * Draw order = tree order: a node paints its surface quad (shadow + fill),
 * then its children, so z is implicit.
 */
open class UiNode(var type: String) {

    // ---- computed bounds (px) ----
    var x = 0f; var y = 0f; var width = 0f; var height = 0f

    val children = mutableListOf<UiNode>()
    var parent: UiNode? = null

    // ---- layout params (dp unless noted) ----
    /** Fixed dp, or [SIZE_MATCH] / [SIZE_WRAP]. */
    var dpWidth: Float = 0f
    var widthMode: Int = SIZE_FIXED
    var dpHeight: Float = 0f
    var heightMode: Int = SIZE_FIXED

    /** Where the node's [pivotX]/[pivotY] point sits inside the parent (0..1). */
    var anchorX = 0.5f
    var anchorY = 0.5f
    /** Which point of the node lands on the anchor (0..1). */
    var pivotX = 0.5f
    var pivotY = 0.5f
    var offsetX = 0f
    var offsetY = 0f

    // containers
    var spacing = 0f
    var padding = 0f
    var gravity = GRAVITY_CENTER

    // ---- style ----
    var style = STYLE_SOLID            // solid | glass | ghost | primary
    var fillColor = 0xF21A1E26.toInt()
    var fillEndColor = 0xF21A1E26.toInt()
    var borderColor = 0x26FFFFFF.toInt()
    var radius = 14f
    var shadow = true
    var shadowColor = 0x66000000.toInt()
    var shadowSpread = 14f
    /** Containers/labels participate in layout and text but draw no surface quad. */
    var drawsSurface = true
    var visible = true

    // ---- text ----
    var text = ""
    var textColor = 0xFFFFFFFF.toInt()
    var textSize = 17f
    var textShadow = false

    // ---- interaction (runtime state) ----
    var onClick: (() -> Unit)? = null
    var hover = false
    var pressed = false
    var hoverT = 0f                     // animated 0..1 (hover state layer)
    var pressedT = 0f                   // animated 0..1 (press scale)
    var bold = false
    var letterSpacing = 0f              // extra px advance per glyph

    // ---- text editing (Lua text fields; purely additive state) ----
    /** True while a text field owns keyboard focus (focus ring / caret state). */
    var focused = false

    // ---- scrolling (engine-clipped scroll areas) ----
    /** When true, children render clipped to this node's bounds and shifted
     *  up by [scrollY]; hit-testing applies the same transform. */
    var clip = false
    /** Content scroll offset in px (Lua-managed via getScrollY/setScrollY). */
    var scrollY = 0f

    fun add(child: UiNode): UiNode {
        child.parent = this
        children.add(child)
        return this
    }

    // ---- layout ----

    /**
     * Resolves the node's px size. MATCH is assigned by the PARENT's arrange
     * (row splits the remaining width across children, column gives the
     * content box); resolveSize only fills a fallback when no assignment
     * happened yet (width/height still 0), and must never overwrite it —
     * a per-frame re-resolve would clobber the parent's split.
     */
    private fun resolveSize(scale: Float, parentW: Float, parentH: Float, theme: Theme) {
        val p = parent
        val availW = if (p != null) p.width - 2 * p.padding * scale else parentW
        val availH = if (p != null) p.height - 2 * p.padding * scale else parentH
        if (widthMode == SIZE_MATCH) {
            if (width <= 0f) width = availW
        } else if (widthMode == SIZE_WRAP) {
            width = -1f
        } else {
            width = dpWidth * scale
        }
        if (heightMode == SIZE_MATCH) {
            if (height <= 0f) height = availH
        } else if (heightMode == SIZE_WRAP) {
            height = -1f
        } else {
            height = dpHeight * scale
        }
    }

    /**
     * Measures and arranges the subtree. [x]/[y] are set to the node's top-left
     * before arrange; containers lay out children within their padding box.
     */
    fun layout(scale: Float, theme: Theme) {
        val parentW = parent?.width ?: 0f
        val parentH = parent?.height ?: 0f
        resolveSize(scale, parentW, parentH, theme)
        if (width < 0 || height < 0) wrapContent(scale, theme)
        arrangeChildren(scale, theme)
        for (c in children) c.layout(scale, theme)
    }

    /** Fills in wrap-content sizes from children (called with bounds unset). */
    private fun wrapContent(scale: Float, theme: Theme) {
        // text nodes measure themselves
        if (text.isNotEmpty() && children.isEmpty()) {
            val f = NeoUI.font
            if (width < 0) width = (f?.measure(text, textSize * scale) ?: 0f) + padding * 2 * scale
            if (height < 0) height = (f?.lineHeight(textSize * scale) ?: textSize * scale * 1.4f) + padding * 2 * scale
            return
        }
        // containers: run a speculative pass on children with parent size 0,
        // then take the max/sum. MATCH children keep their last-frame size as
        // the measurement basis (a 0-width probe collapses nested MATCH chains
        // and makes WRAP cards measure to padding-only heights).
        val probeW = if (width < 0) 0f else width
        val probeH = if (height < 0) 0f else height
        var maxW = 0f
        var sumH = padding * 2 * scale
        var sumW = padding * 2 * scale
        var maxH = 0f
        for (c in children) {
            c.x = 0f; c.y = 0f
            if (!(c.widthMode == SIZE_MATCH && c.width > 0f)) c.width = 0f
            if (!(c.heightMode == SIZE_MATCH && c.height > 0f)) c.height = 0f
            c.probeLayout(scale, probeW.coerceAtLeast(1f), probeH.coerceAtLeast(1f), theme)
            maxW = maxOf(maxW, c.width)
            maxH = maxOf(maxH, c.height)
            sumH += c.height + spacing * scale
            sumW += c.width + spacing * scale
        }
        if (width < 0) width = when (type) {
            "row" -> sumW - spacing * scale
            else -> maxW
        } + padding * 2 * scale
        if (height < 0) height = when (type) {
            "column" -> sumH - spacing * scale
            "row" -> maxH
            else -> maxH
        } + padding * 2 * scale
    }

    /** Speculative layout used by wrap-content measurement. */
    private fun probeLayout(scale: Float, parentW: Float, parentH: Float, theme: Theme) {
        resolveSize(scale, parentW, parentH, theme)
        if (width < 0 || height < 0) wrapContent(scale, theme)
        arrangeChildren(scale, theme)
        for (c in children) c.probeLayout(scale, width, height, theme)
    }

    private fun arrangeChildren(scale: Float, theme: Theme) {
        val pad = padding * scale
        val innerW = width - pad * 2
        val innerH = height - pad * 2
        when (type) {
            "column" -> {
                var cy = y + pad
                for (c in children) {
                    val cw = if (c.widthMode == SIZE_MATCH) innerW else c.width
                    c.width = cw
                    c.x = when (gravity) {
                        GRAVITY_START -> x + pad
                        GRAVITY_END -> x + pad + innerW - cw
                        else -> x + pad + (innerW - cw) / 2
                    }
                    c.y = cy
                    cy += c.height + spacing * scale
                }
            }
            "row" -> {
                // MATCH children split the width left over by fixed/wrap
                // siblings (sidebar 220 + divider 1 -> content takes the rest)
                var used = 0f
                var matchCount = 0
                for (c in children) {
                    if (c.widthMode == SIZE_MATCH) matchCount++ else used += c.width + spacing * scale
                }
                val matchW = if (matchCount > 0)
                    (innerW - used - spacing * scale * (children.size - 1)).coerceAtLeast(0f) / matchCount
                else 0f
                var cx = x + pad
                for (c in children) {
                    if (c.widthMode == SIZE_MATCH) c.width = matchW
                    val ch = if (c.heightMode == SIZE_MATCH) innerH else c.height
                    c.height = ch
                    c.x = cx
                    c.y = when (gravity) {
                        GRAVITY_START -> y + pad
                        GRAVITY_END -> y + pad + innerH - ch
                        else -> y + pad + (innerH - ch) / 2
                    }
                    cx += c.width + spacing * scale
                }
            }
            else -> {
                // anchor box: each child lands its pivot on its anchor point
                for (c in children) {
                    c.x = x + pad + c.anchorX * innerW + c.offsetX * scale - c.pivotX * c.width
                    c.y = y + pad + c.anchorY * innerH + c.offsetY * scale - c.pivotY * c.height
                }
            }
        }
    }

    fun walk(action: (UiNode) -> Unit) {
        action(this)
        for (c in children) c.walk(action)
    }

    /** Root entry: the screen node fills the whole frame, then lays out children. */
    fun layoutFullscreen(scale: Float, theme: Theme, w: Float, h: Float) {
        x = 0f; y = 0f; width = w; height = h
        arrangeChildren(scale, theme)
        for (c in children) c.layout(scale, theme)
    }

    companion object {
        const val SIZE_FIXED = 0
        const val SIZE_MATCH = 1
        const val SIZE_WRAP = 2

        /** Distinct style ids; surfaceQuad maps GLASS -> shader mode 2, others -> 1. */
        const val STYLE_SOLID = 0
        const val STYLE_PRIMARY = 1
        const val STYLE_GHOST = 2
        const val STYLE_GLASS = 3

        const val GRAVITY_CENTER = 0
        const val GRAVITY_START = 1
        const val GRAVITY_END = 2
    }
}
