package net.theresa.neogenesis.modules.nametag

import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.theresa.neogenesis.modules.multiplayer.AutoReconnectTime

class ClearNameTag : IToggleable
{
    override var name: String = "透明名称标签"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: ClearNameTag
    }

    init {
        Instance = this
    }
}