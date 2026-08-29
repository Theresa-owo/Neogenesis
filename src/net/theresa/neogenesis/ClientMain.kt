package net.theresa.neogenesis

import net.theresa.neogenesis.config.Configuration
import net.theresa.neogenesis.events.EventManager
import net.theresa.neogenesis.events.Listener
import net.theresa.neogenesis.modules.ModuleLoader
import net.theresa.neogenesis.utils.VersionManager

class ClientMain : Listener
{
    companion object
    {
        lateinit var eventManager: EventManager
        lateinit var Instance: ClientMain
        val VERSION: VersionManager = VersionManager(5)

        @JvmStatic
        fun initialize() {
            Instance = ClientMain()
            this.eventManager = EventManager()
            ModuleLoader.load()
        }
    }
}