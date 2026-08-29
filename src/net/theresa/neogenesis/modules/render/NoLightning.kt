package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoLightning : IToggleable
{
    override var name: String = "关闭闪电特效"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoLightning
    }

    init {
        Instance = this
    }
}