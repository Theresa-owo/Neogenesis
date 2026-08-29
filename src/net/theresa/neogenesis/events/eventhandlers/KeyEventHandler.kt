package net.theresa.neogenesis.events.eventhandlers

import net.theresa.neogenesis.events.KeyboardEvent
import net.theresa.neogenesis.events.eventhandlers.registries.KeyEventHandlerRegistry
import net.theresa.neogenesis.interactions.KeyBindingLang

interface KeyEventHandler
{
    fun handleKeys(keyEvent: KeyboardEvent)
    var key: KeyBindingLang
    companion object
    {
        @JvmStatic
        fun handleAllKeys(event: KeyboardEvent) {
            for (handler in KeyEventHandlerRegistry.handlers)
            {
                if (handler.key.keyCode != event.keyCode) continue;
                handler.handleKeys(event)
            }
        }
    }
}