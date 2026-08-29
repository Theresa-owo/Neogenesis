package net.theresa.neogenesis.events.eventhandlers.registries

import net.theresa.neogenesis.events.eventhandlers.KeyEventHandler

object KeyEventHandlerRegistry
{
    val handlers = mutableListOf<KeyEventHandler>()

    fun register(handler: KeyEventHandler) {
        handlers.add(handler)
    }
}