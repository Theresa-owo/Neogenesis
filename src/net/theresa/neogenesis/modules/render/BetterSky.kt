package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class BetterSky : IToggleable
{
    override var name: String = "更好的天空"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: BetterSky
    }

    init {
        Instance = this
    }
}