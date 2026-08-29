package net.theresa.neogenesis.modules.tab

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HideTabHeader : IToggleable
{
    override var name: String = "隐藏TabList的顶部"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: HideTabHeader
    }

    init {
        Instance = this
    }
}