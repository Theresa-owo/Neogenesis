package net.theresa.ui.screen

import net.minecraft.client.Minecraft
import net.theresa.ui.NeoUI
import net.theresa.ui.scene.UiNode

/** One NeoUI screen: an id plus a retained node tree. */
class NeoScreen(val id: String, val root: UiNode) {

    internal var openedAt = 0L
    /** 0..1 eased entrance progress; drives the slide+fade (see renderScreenTree). */
    var entranceT = 1f
        private set

    internal fun markOpened(now: Long) {
        openedAt = now
        entranceT = 0f
    }

    internal fun tickAnimations(now: Long, entranceMs: Long) {
        if (openedAt == 0L) openedAt = now
        var t = ((now - openedAt).toFloat() / entranceMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        entranceT = t * t * (3f - 2f * t) // smoothstep easing
    }
}

/**
 * Screen stack + action routing. Completely independent of the vanilla
 * GuiScreen state machine (GL mode keeps vanilla; Vulkan mode runs this).
 */
object ScreenManager {

    private val stack = ArrayDeque<NeoScreen>()

    val current: NeoScreen? get() = stack.lastOrNull()

    /** Replaces the whole stack (menu root). Entrance starts on the first frame. */
    fun show(screen: NeoScreen) {
        stack.clear()
        InputDispatcher.reset()
        stack.addLast(screen)
    }

    fun push(screen: NeoScreen) {
        InputDispatcher.reset()
        stack.addLast(screen)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeLast()
        InputDispatcher.reset()
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
