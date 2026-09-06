package net.theresa.ui.screen

import net.minecraft.client.Minecraft
import net.theresa.ui.NeoUI
import net.theresa.ui.scene.UiNode

/** One NeoUI screen: an id plus a retained node tree. */
class NeoScreen(val id: String, val root: UiNode)

/**
 * Screen stack + action routing. Completely independent of the vanilla
 * GuiScreen state machine (GL mode keeps vanilla; Vulkan mode runs this).
 */
object ScreenManager {

    private val stack = ArrayDeque<NeoScreen>()

    val current: NeoScreen? get() = stack.lastOrNull()

    /** Replaces the whole stack (menu root). */
    fun show(screen: NeoScreen) {
        stack.clear()
        stack.addLast(screen)
    }

    fun push(screen: NeoScreen) {
        stack.addLast(screen)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeLast()
    }

    /** onClick action protocol: open:<id> | back | quit | custom:<event>. */
    fun handleAction(action: String) {
        when {
            action == "back" -> pop()
            action == "quit" -> Minecraft.getMinecraft().shutdown()
            action.startsWith("open:") -> push(NeoScreens.create(action.removePrefix("open:")))
            action.startsWith("custom:") -> NeoUI.emitCustom(action.removePrefix("custom:"))
        }
    }
}

/** Screen factories registered by id; mods can register their own. */
object NeoScreens {
    private val factories = LinkedHashMap<String, () -> NeoScreen>()

    fun register(id: String, factory: () -> NeoScreen) {
        factories[id] = factory
    }

    fun create(id: String): NeoScreen {
        val factory = factories[id]
            ?: return NeoScreen("placeholder:$id", PlaceholderScreen.build(id))
        return factory()
    }
}
