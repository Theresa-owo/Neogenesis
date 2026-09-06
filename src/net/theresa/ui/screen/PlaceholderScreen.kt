package net.theresa.ui.screen

import net.theresa.ui.scene.UiNode
import net.theresa.ui.scene.Widgets

/**
 * Placeholder for screens not yet migrated to NeoUI: keeps every function
 * entry point reachable while the migration proceeds, styled consistently.
 */
object PlaceholderScreen {

    fun build(forId: String): UiNode {
        val t = net.theresa.ui.NeoUI.theme
        val root = Widgets.panel("panel", UiNode.STYLE_GLASS).apply {
            dpWidth = 480f
            heightMode = UiNode.SIZE_WRAP
            anchorX = 0.5f; anchorY = 0.5f; pivotX = 0.5f; pivotY = 0.5f
            padding = 24f
            add(Widgets.column(spacingDp = 12f).apply {
                widthMode = UiNode.SIZE_MATCH
                add(Widgets.label("§bNeoUI", t.fontSize + 6f))
                add(Widgets.label("§7该界面正在迁移至 NeoUI：§f$forId", t.fontSize))
                add(Widgets.spacer(6f))
                add(Widgets.button("§f返回", UiNode.STYLE_GHOST).apply {
                    onClick = { net.theresa.ui.NeoUI.handleAction("back") }
                })
            })
        }
        return root
    }
}
