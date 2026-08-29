package net.theresa.neogenesis.modules.autosprint

import net.theresa.neogenesis.events.KeyboardEvent
import net.theresa.neogenesis.events.eventhandlers.KeyEventHandler
import net.theresa.neogenesis.interactions.KeyBindingLang
import net.theresa.neogenesis.interactions.KeySettings
import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.theresa.neogenesis.modules.chat.SmoothChat
import net.theresa.neogenesis.modules.chat.SmoothChat.Companion
import net.minecraft.client.settings.KeyBinding

class AutoSprint
(
    description: String,
    keyCode: Int,
    category: String,
    val pressedGetter: () -> Boolean,
) : KeyBinding(description, keyCode, category), IToggleable, KeyEventHandler
{
    override var name: String = "自动疾跑"
    override var toggled: Boolean = true
    override var key: KeyBindingLang = KeySettings.keyToggleSprint

    companion object {
        lateinit var Instance: AutoSprint
    }

    override fun handleKeys(keyEvent: KeyboardEvent) {
        //if (keyEvent.keyCode != key.keyCode) return
        toggled = !toggled
    }

    override fun isKeyDown() = if (toggled) (super.isKeyDown() || pressedGetter()) else false
    override fun isPressed() = if (toggled) (super.isPressed() || pressedGetter()) else false
}