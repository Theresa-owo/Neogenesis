package net.theresa.neogenesis.modules.tab

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HideTabListPing : IToggleable
{
    override var name: String = "隐藏TabList内延迟显示"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: HideTabListPing
    }

    init {
        Instance = this
    }
}