package net.theresa.ui.screen

import net.minecraft.client.Minecraft
import net.theresa.ui.layout.JsonLayout
import net.theresa.ui.scene.UiNode
import net.theresa.ui.scene.Widgets

/** The NeoUI main menu: JSON-defined layout (moddable) with a code fallback. */
object MainMenuScreen {

    fun build(): NeoScreen {
        val root = try {
            JsonLayout.loadScreen("main_menu")
        } catch (t: Throwable) {
            System.err.println("[NeoUI] main_menu.json failed, using code fallback: $t")
            codeFallback()
        }
        // dynamic bits a JSON layout cannot express (version stamp)
        root.add(
            Widgets.label(
                "§fNeogenesis §71.8.9 Vulkan §8| §7" + Minecraft.getMinecraft().getVersion(),
                13f, 0xFF9AA0A8.toInt()
            ).apply {
                anchorX = 0f; anchorY = 1f; pivotX = 0f; pivotY = 1f
                offsetX = 16f; offsetY = -14f
            }
        )
        root.add(
            Widgets.label("§8Copyright Mojang AB. Do not distribute!", 13f, 0xFF9AA0A8.toInt()).apply {
                anchorX = 1f; anchorY = 1f; pivotX = 1f; pivotY = 1f
                offsetX = -16f; offsetY = -14f
            }
        )
        return NeoScreen("main_menu", root)
    }

    /** Same layout as main_menu.json, in code — demonstrates the dual authoring paths. */
    private fun codeFallback(): UiNode {
        val t = net.theresa.ui.NeoUI.theme
        val panel = Widgets.panel("panel", UiNode.STYLE_GLASS).apply {
            dpWidth = 440f
            heightMode = UiNode.SIZE_WRAP
            anchorX = 0.5f; anchorY = 0.5f; pivotX = 0.5f; pivotY = 0.5f
            padding = 20f
        }
        val column = Widgets.column(spacingDp = 8f).apply { widthMode = UiNode.SIZE_MATCH }
        column.add(Widgets.label("§bNEOGENESIS", 34f).apply { widthMode = UiNode.SIZE_MATCH })
        column.add(Widgets.label("§7" + I18n("menu.title"), 15f, t.textMutedArgb).apply { widthMode = UiNode.SIZE_MATCH })
        column.add(Widgets.spacer(10f))
        column.add(Widgets.button(I18n("menu.singleplayer"), UiNode.STYLE_PRIMARY).apply {
            onClick = { net.theresa.ui.NeoUI.handleAction("open:singleplayer") }
        })
        column.add(Widgets.button(I18n("menu.multiplayer"), UiNode.STYLE_GHOST).apply {
            onClick = { net.theresa.ui.NeoUI.handleAction("open:multiplayer") }
        })
        val row = Widgets.row(spacingDp = 8f).apply { widthMode = UiNode.SIZE_MATCH }
        row.add(Widgets.button(I18n("menu.options"), UiNode.STYLE_GHOST, wDp = 186f).apply {
            onClick = { net.theresa.ui.NeoUI.handleAction("open:options") }
        })
        row.add(Widgets.button(I18n("options.language"), UiNode.STYLE_GHOST, wDp = 186f).apply {
            onClick = { net.theresa.ui.NeoUI.handleAction("open:language") }
        })
        column.add(row)
        column.add(Widgets.button(I18n("menu.quit"), UiNode.STYLE_GHOST).apply {
            onClick = { net.theresa.ui.NeoUI.handleAction("quit") }
        })
        panel.add(column)
        return panel
    }

    private fun I18n(key: String): String = net.minecraft.client.resources.I18n.format(key)
}
