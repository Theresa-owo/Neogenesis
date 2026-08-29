package net.theresa.neogenesis.modules.culling.entityculling

import net.theresa.neogenesis.events.Listener
import net.theresa.neogenesis.events.Subscribe
import net.theresa.neogenesis.events.TickEvent
import net.theresa.neogenesis.interfaces.modules.IToggleable
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class EntityCulling : EntityCullingBase(), Listener, IToggleable {
    private fun doClientTick() {
        this.clientTick()
    }

    private fun doWorldTick() {
        this.worldTick()
    }

    @Subscribe
    fun onTickPre() {
        this.doClientTick()
        this.doWorldTick()
    }

    override var toggled: Boolean = true

    override var name: String = "削弱实体渲染"

    companion object {
        lateinit var Instance: EntityCulling
        val logger: Logger = LogManager.getLogger("EntityCulling")
    }

    init {
        Instance = this
    }
}