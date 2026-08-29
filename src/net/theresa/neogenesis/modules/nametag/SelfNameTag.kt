package net.theresa.neogenesis.modules.nametag

import net.theresa.neogenesis.interfaces.modules.IToggleable

class SelfNameTag : IToggleable
{
    override var name: String = "自身名称标签"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: SelfNameTag
    }

    init {
        Instance = this
    }
}