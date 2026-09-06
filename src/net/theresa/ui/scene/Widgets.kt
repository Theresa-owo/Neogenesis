package net.theresa.ui.scene

import net.theresa.ui.NeoUI
import net.theresa.ui.style.Theme

/** Widget factories applying [Theme] styles. The DSL and JSON loader both sit on these. */
object Widgets {

    fun node(type: String): UiNode = UiNode(type)

    fun panel(type: String = "box", style: Int = UiNode.STYLE_SOLID): UiNode = UiNode(type).apply {
        this.style = style
        // cards hug their content unless the layout overrides w/h explicitly
        widthMode = UiNode.SIZE_WRAP
        heightMode = UiNode.SIZE_WRAP
        applyThemeStyle(this)
        // "box" is a layout container: no surface unless a style is set explicitly
        if (type == "box" && style == UiNode.STYLE_SOLID) drawsSurface = false
    }

    fun applyThemeStyle(node: UiNode) {
        node.radius = NeoUI.theme.radius
        node.shadowColor = NeoUI.theme.shadowArgb
        when (node.style) {
            UiNode.STYLE_GLASS -> {
                node.fillColor = NeoUI.theme.surfaceArgb; node.fillEndColor = NeoUI.theme.surfaceArgb
                node.borderColor = NeoUI.theme.surfaceBorderArgb
                node.shadow = true
            }
            UiNode.STYLE_PRIMARY -> {
                node.fillColor = NeoUI.theme.accentArgb; node.fillEndColor = NeoUI.theme.accentEndArgb
                node.borderColor = 0x33FFFFFF
                node.shadow = false
            }
            UiNode.STYLE_GHOST -> {
                node.fillColor = NeoUI.theme.ghostArgb; node.fillEndColor = NeoUI.theme.ghostArgb
                node.borderColor = NeoUI.theme.ghostBorderArgb
                node.shadow = false
            }
            else -> {
                node.fillColor = NeoUI.theme.surfaceSolidArgb; node.fillEndColor = NeoUI.theme.surfaceSolidArgb
                node.borderColor = NeoUI.theme.surfaceBorderArgb
                node.shadow = true
            }
        }
    }

    fun label(text: String, sizeDp: Float = NeoUI.theme.fontSize, color: Int = NeoUI.theme.textArgb): UiNode =
        UiNode("label").apply {
            this.text = text
            textSize = sizeDp
            textColor = color
            widthMode = UiNode.SIZE_WRAP
            heightMode = UiNode.SIZE_WRAP
            shadow = false
        drawsSurface = false
        }

    fun button(text: String, style: Int = UiNode.STYLE_PRIMARY, wDp: Float = 380f, hDp: Float = 56f): UiNode =
        panel("button", style).apply {
            widthMode = UiNode.SIZE_FIXED
            heightMode = UiNode.SIZE_FIXED
            dpWidth = wDp
            dpHeight = hDp
            radius = NeoUI.theme.radius
            add(
                label(text, NeoUI.theme.fontSize + 3f, NeoUI.theme.textArgb).apply {
                    anchorX = 0.5f; anchorY = 0.5f; pivotX = 0.5f; pivotY = 0.5f
                }
            )
        }

    fun column(spacingDp: Float = 0f, padDp: Float = 0f): UiNode = UiNode("column").apply {
        spacing = spacingDp
        padding = padDp
        widthMode = UiNode.SIZE_WRAP
        heightMode = UiNode.SIZE_WRAP
        shadow = false
        drawsSurface = false
    }

    fun row(spacingDp: Float = 0f, padDp: Float = 0f): UiNode = UiNode("row").apply {
        spacing = spacingDp
        padding = padDp
        widthMode = UiNode.SIZE_WRAP
        heightMode = UiNode.SIZE_WRAP
        shadow = false
        drawsSurface = false
    }

    fun spacer(hDp: Float): UiNode = UiNode("spacer").apply {
        dpHeight = hDp
        widthMode = UiNode.SIZE_MATCH
        shadow = false
        drawsSurface = false
    }
}
