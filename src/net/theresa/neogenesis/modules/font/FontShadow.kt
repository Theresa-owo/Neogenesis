package net.theresa.neogenesis.modules.font

import net.theresa.neogenesis.interfaces.modules.IToggleable

class FontShadow : IToggleable
{
    override var name: String = "渲染文字阴影"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: FontShadow
    }

    init {
        Instance = this
    }
}