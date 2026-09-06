package net.theresa.ui.hud

import net.theresa.ui.screen.NeoScreen

/**
 * In-world HUD render hook. The HUD screen tree is registered from Lua
 * (neoui.hudapi.show_hud{tree}) and drawn over the world every frame with the
 * same batching as menu screens.
 */
object HudRenderer {

    /** The HUD screen; null = draw nothing over the world. */
    var screen: NeoScreen? = null
}
