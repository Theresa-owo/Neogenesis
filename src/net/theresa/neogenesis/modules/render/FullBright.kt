package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class FullBright : IToggleable
{
    override var name: String = "无限夜视"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: FullBright
    }

    init {
        Instance = this
    }
}