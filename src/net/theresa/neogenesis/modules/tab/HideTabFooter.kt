package net.theresa.neogenesis.modules.tab

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HideTabFooter : IToggleable
{
    override var name: String = "隐藏TabList的尾部"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: HideTabFooter
    }

    init {
        Instance = this
    }
}