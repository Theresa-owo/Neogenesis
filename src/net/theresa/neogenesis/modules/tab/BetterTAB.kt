package net.theresa.neogenesis.modules.tab

import net.theresa.neogenesis.interfaces.modules.IToggleable

class BetterTAB : IToggleable
{
    override var name: String = "更好的TAB栏"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: BetterTAB
    }

    init {
        Instance = this
    }
}