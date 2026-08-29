package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HideFire : IToggleable
{
    override var name: String = "隐藏火焰"
    override var toggled: Boolean = false

    companion object
    {
        lateinit var Instance: HideFire
    }

    init {
        Instance = this
    }
}